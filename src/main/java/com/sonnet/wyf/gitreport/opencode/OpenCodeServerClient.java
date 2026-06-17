package com.sonnet.wyf.gitreport.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class OpenCodeServerClient {
    private static final Logger log = LoggerFactory.getLogger(OpenCodeServerClient.class);
    private static final DateTimeFormatter SESSION_TITLE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenCodeServerClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    public OpenCodeServerClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public boolean isHealthy(URI serverUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(resolve(serverUrl, "/global/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    public OpenCodeSession createSession(URI serverUrl, Path repo, String title) throws IOException, InterruptedException {
        return createSession(serverUrl, repo, title, "", 60);
    }

    public OpenCodeSession createSession(URI serverUrl, Path repo, String title, int requestTimeoutSeconds) throws IOException, InterruptedException {
        return createSession(serverUrl, repo, title, "", requestTimeoutSeconds);
    }

    public OpenCodeSession createSession(URI serverUrl, Path repo, String title, String sessionModel, int requestTimeoutSeconds) throws IOException, InterruptedException {
        String directory = repo.toAbsolutePath().normalize().toString();
        String sessionTitle = uniqueSessionTitle(title);
        Map<String, Object> body = new LinkedHashMap<>();
        if (sessionTitle != null && !sessionTitle.isBlank()) {
            body.put("title", sessionTitle);
        }
        if (sessionModel != null && !sessionModel.isBlank()) {
            body.put("model", sessionModelObject(sessionModel));
        }
        String requestBody = objectMapper.writeValueAsString(body);
        URI endpoint = resolveWithDirectory(serverUrl, "/session", directory, "");
        log.info("OpenCode API create session request: endpoint={}, directory={}, title={}, sessionTitle={}, timeoutSeconds={}, body={}",
                endpoint, directory, title, sessionTitle, requestTimeoutSeconds, requestBody);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout(requestTimeoutSeconds))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        Instant started = Instant.now();
        JsonNode json = sendCreateSessionWithInFlightDiscovery(request, serverUrl, directory, sessionTitle, requestTimeoutSeconds, requestBody);
        String id = firstText(json, "id");
        if (id.isBlank()) {
            id = firstText(json.path("data"), "id");
        }
        if (id.isBlank()) {
            throw new IllegalStateException("OpenCode Server /session response missing session id: " + json);
        }
        log.info("OpenCode API create session completed: sessionId={}, elapsedMs={}",
                id, Duration.between(started, Instant.now()).toMillis());
        return new OpenCodeSession(id);
    }

    private JsonNode sendCreateSessionWithInFlightDiscovery(
            HttpRequest request,
            URI serverUrl,
            String directory,
            String sessionTitle,
            int requestTimeoutSeconds,
            String requestBody
    ) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<String>> createFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long deadlineNanos = System.nanoTime() + requestTimeout(requestTimeoutSeconds).toNanos();
        try {
            while (true) {
                try {
                    HttpResponse<String> response = createFuture.get(250, TimeUnit.MILLISECONDS);
                    return parseJsonResponse(request, response);
                } catch (TimeoutException ignored) {
                    String discoveredSessionId = findSessionIdByTitle(serverUrl, directory, sessionTitle, requestTimeoutSeconds);
                    if (!discoveredSessionId.isBlank()) {
                        createFuture.cancel(true);
                        log.warn("Discovered OpenCode session before /session response completed: sessionId={}, title={}",
                                discoveredSessionId, sessionTitle);
                        return objectMapper.createObjectNode().put("id", discoveredSessionId);
                    }
                    if (System.nanoTime() >= deadlineNanos) {
                        createFuture.cancel(true);
                        HttpTimeoutException timeout = new HttpTimeoutException("OpenCode /session request timed out");
                        return recoverOrThrowAfterCreateTimeout(serverUrl, directory, sessionTitle, requestTimeoutSeconds, requestBody, timeout);
                    }
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof HttpTimeoutException timeout) {
                        return recoverOrThrowAfterCreateTimeout(serverUrl, directory, sessionTitle, requestTimeoutSeconds, requestBody, timeout);
                    }
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw new IllegalStateException("OpenCode /session request failed: " + cause, cause);
                }
            }
        } catch (InterruptedException exception) {
            createFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private JsonNode recoverOrThrowAfterCreateTimeout(
            URI serverUrl,
            String directory,
            String title,
            int requestTimeoutSeconds,
            String requestBody,
            HttpTimeoutException timeout
    ) throws InterruptedException {
        String recoveredSessionId = recoverCreatedSessionAfterTimeout(serverUrl, directory, title, requestTimeoutSeconds, timeout);
        if (!recoveredSessionId.isBlank()) {
            return objectMapper.createObjectNode().put("id", recoveredSessionId);
        }
        throw new IllegalStateException("OpenCode /session timed out after "
                + Math.max(1, requestTimeoutSeconds)
                + "s, directory=" + directory
                + ", body=" + requestBody
                + ". The server is healthy but did not complete session creation; check opencode-server stderr.log and whether this directory can be opened by `opencode` directly.", timeout);
    }

    private String recoverCreatedSessionAfterTimeout(URI serverUrl, String directory, String title, int requestTimeoutSeconds, HttpTimeoutException timeout) throws InterruptedException {
        if (title == null || title.isBlank()) {
            return "";
        }
        try {
            URI endpoint = sessionsByTitleEndpoint(serverUrl, directory, title);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(Math.max(2, Math.min(10, requestTimeoutSeconds))))
                    .GET()
                    .build();
            log.warn("OpenCode /session create timed out; checking whether session was created anyway: endpoint={}, title={}",
                    endpoint, title);
            JsonNode response = sendJson(request);
            JsonNode sessions = response.isArray() ? response : response.path("data");
            if (!sessions.isArray()) {
                return "";
            }
            for (JsonNode session : sessions) {
                if (title.equals(firstText(session, "title"))) {
                    String id = firstText(session, "id");
                    if (!id.isBlank()) {
                        log.warn("Recovered OpenCode session after create timeout: sessionId={}, title={}", id, title);
                        return id;
                    }
                }
            }
            return "";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception recoveryException) {
            timeout.addSuppressed(recoveryException);
            log.warn("OpenCode session recovery after create timeout failed: title={}, reason={}",
                    title, recoveryException.toString());
            return "";
        }
    }

    private String findSessionIdByTitle(URI serverUrl, String directory, String title, int requestTimeoutSeconds) throws InterruptedException {
        if (title == null || title.isBlank()) {
            return "";
        }
        try {
            JsonNode sessions = listSessionsByTitle(serverUrl, directory, title, Duration.ofSeconds(Math.max(1, Math.min(2, requestTimeoutSeconds))));
            for (JsonNode session : sessions) {
                if (title.equals(firstText(session, "title"))) {
                    String id = firstText(session, "id");
                    if (!id.isBlank()) {
                        return id;
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            log.debug("Unable to discover OpenCode session while create response is pending: title={}, reason={}", title, exception.toString());
        }
        return "";
    }

    private JsonNode listSessionsByTitle(URI serverUrl, String directory, String title, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(sessionsByTitleEndpoint(serverUrl, directory, title))
                .timeout(timeout)
                .GET()
                .build();
        JsonNode response = sendJson(request);
        JsonNode sessions = response.isArray() ? response : response.path("data");
        if (!sessions.isArray()) {
            return objectMapper.createArrayNode();
        }
        return sessions;
    }

    private URI sessionsByTitleEndpoint(URI serverUrl, String directory, String title) {
        String suffix = title == null || title.isBlank() ? "limit=100" : "search=" + queryEncode(title) + "&limit=100";
        return resolveWithDirectory(serverUrl, "/session", directory, suffix);
    }

    private String uniqueSessionTitle(String title) {
        if (title == null || title.isBlank()) {
            return title;
        }
        return title + "-" + SESSION_TITLE_TIMESTAMP.format(Instant.now());
    }

    private ModelRef parseModelRef(String model) {
        String trimmed = model.trim();
        int slash = trimmed.indexOf('/');
        if (slash <= 0 || slash == trimmed.length() - 1) {
            throw new IllegalArgumentException("opencode session-model must use provider/model format when set: " + model);
        }
        return new ModelRef(trimmed.substring(0, slash), trimmed.substring(slash + 1));
    }

    private Map<String, Object> sessionModelObject(String model) {
        ModelRef ref = parseModelRef(model);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerID", ref.providerId());
        result.put("id", ref.modelId());
        return result;
    }

    private Map<String, Object> promptModelObject(String model) {
        ModelRef ref = parseModelRef(model);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerID", ref.providerId());
        result.put("modelID", ref.modelId());
        return result;
    }

    private record ModelRef(String providerId, String modelId) {
    }

    public void sendPromptAsync(URI serverUrl, Path repo, String sessionId, String text) throws IOException, InterruptedException {
        sendPromptAsync(serverUrl, repo, sessionId, text, "", 60);
    }

    public void sendPromptAsync(URI serverUrl, Path repo, String sessionId, String text, int requestTimeoutSeconds) throws IOException, InterruptedException {
        sendPromptAsync(serverUrl, repo, sessionId, text, "", requestTimeoutSeconds);
    }

    public void sendPromptAsync(URI serverUrl, Path repo, String sessionId, String text, String sessionModel, int requestTimeoutSeconds) throws IOException, InterruptedException {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", text);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parts", java.util.List.of(part));
        if (sessionModel != null && !sessionModel.isBlank()) {
            body.put("model", promptModelObject(sessionModel));
        }
        HttpRequest request = HttpRequest.newBuilder(resolveWithDirectory(serverUrl, "/session/" + pathEncode(sessionId) + "/prompt_async", repo.toAbsolutePath().normalize().toString(), ""))
                .timeout(requestTimeout(requestTimeoutSeconds))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        log.info("OpenCode API prompt request: endpoint={}, sessionId={}, textChars={}, timeoutSeconds={}",
                request.uri(), sessionId, text.length(), requestTimeoutSeconds);
        sendJson(request);
    }

    public String getSessionStatus(URI serverUrl, Path repo, String sessionId) throws IOException, InterruptedException {
        return inferStatusFromMessages(serverUrl, repo, sessionId);
    }

    public boolean abortSession(URI serverUrl, Path repo, String sessionId) {
        return false;
    }

    private JsonNode sendJson(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return parseJsonResponse(request, response);
    }

    private JsonNode parseJsonResponse(HttpRequest request, HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenCode Server request failed: " + request.method() + " " + request.uri() + " status=" + response.statusCode() + " body=" + response.body());
        }
        String body = response.body() == null || response.body().isBlank() ? "{}" : response.body();
        return objectMapper.readTree(body);
    }

    private Duration requestTimeout(int requestTimeoutSeconds) {
        return Duration.ofSeconds(Math.max(1, requestTimeoutSeconds));
    }

    private String inferStatusFromMessages(URI serverUrl, Path repo, String sessionId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(resolveWithDirectory(serverUrl, "/session/" + pathEncode(sessionId) + "/message", repo.toAbsolutePath().normalize().toString(), "limit=100"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        JsonNode response = sendJson(request);
        JsonNode messages = response.isArray() ? response : response.path("data");
        if (!messages.isArray()) {
            return "";
        }
        JsonNode lastAssistant = null;
        boolean hasMessage = false;
        for (JsonNode message : messages) {
            hasMessage = true;
            if ("assistant".equals(message.path("type").asText())) {
                lastAssistant = message;
            }
        }
        if (lastAssistant == null) {
            return hasMessage ? "submitted" : "";
        }
        if (messageHasError(lastAssistant)) {
            return "error";
        }
        String finish = lastAssistant.path("finish").asText("");
        if (isAbortFinish(finish)) {
            return "aborted";
        }
        if (!finish.isBlank() || !lastAssistant.path("time").path("completed").isMissingNode()) {
            return "idle";
        }
        return "running";
    }

    private boolean messageHasError(JsonNode message) {
        String finish = message.path("finish").asText("");
        if (finish.equalsIgnoreCase("error") || finish.equalsIgnoreCase("failed")) {
            return true;
        }
        if (!message.path("error").isMissingNode()) {
            return true;
        }
        JsonNode content = message.path("content");
        if (!content.isArray()) {
            return false;
        }
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            if (type.equalsIgnoreCase("error")) {
                return true;
            }
            String status = part.path("state").path("status").asText("");
            if (status.equalsIgnoreCase("error")) {
                return true;
            }
        }
        return false;
    }

    private boolean isAbortFinish(String finish) {
        String normalized = finish == null ? "" : finish.toLowerCase();
        return normalized.equals("abort") || normalized.equals("aborted") || normalized.equals("cancel") || normalized.equals("canceled") || normalized.equals("cancelled");
    }

    private URI resolve(URI serverUrl, String path) {
        String base = serverUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private URI resolveWithDirectory(URI serverUrl, String path, String directory, String suffixQuery) {
        String query = "directory=" + queryEncode(directory);
        if (suffixQuery != null && !suffixQuery.isBlank()) {
            query += "&" + suffixQuery;
        }
        return resolve(serverUrl, path + "?" + query);
    }

    private String queryEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String pathEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String firstText(JsonNode json, String... names) {
        for (String name : names) {
            JsonNode value = json.path(name);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }
}
