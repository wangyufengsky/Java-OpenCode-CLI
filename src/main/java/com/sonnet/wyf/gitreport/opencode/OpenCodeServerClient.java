package com.sonnet.wyf.gitreport.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenCodeServerClient {
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        HttpRequest request = HttpRequest.newBuilder(resolve(serverUrl, "/session", repo))
                .timeout(Duration.ofSeconds(10))
                .header("content-type", "application/json")
                .header("x-opencode-directory", repo.toAbsolutePath().normalize().toString())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        JsonNode json = sendJson(request);
        String id = firstText(json, "id", "sessionID", "sessionId");
        if (id.isBlank()) {
            throw new IllegalStateException("OpenCode Server /session response missing session id: " + json);
        }
        return new OpenCodeSession(id);
    }

    public void sendPromptAsync(URI serverUrl, Path repo, String sessionId, String text, String model) throws IOException, InterruptedException {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", text);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parts", List.of(part));
        if (model != null && !model.isBlank()) {
            body.put("model", modelObject(model));
        }
        HttpRequest request = HttpRequest.newBuilder(resolve(serverUrl, "/session/" + pathEncode(sessionId) + "/prompt_async", repo))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        sendJson(request);
    }

    public String getSessionStatus(URI serverUrl, Path repo, String sessionId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(resolve(serverUrl, "/session/" + pathEncode(sessionId), repo))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        JsonNode json = sendJson(request);
        String direct = firstText(json, "status", "state");
        if (!direct.isBlank()) {
            return direct;
        }
        JsonNode nested = json.path("session");
        return firstText(nested, "status", "state");
    }

    public boolean abortSession(URI serverUrl, Path repo, String sessionId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(resolve(serverUrl, "/session/" + pathEncode(sessionId) + "/abort", repo))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Map<String, Object> modelObject(String model) {
        String trimmed = model.trim();
        int slash = trimmed.indexOf('/');
        if (slash <= 0 || slash == trimmed.length() - 1) {
            throw new IllegalArgumentException("git-report.opencode.model must use provider/model format when set: " + model);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerID", trimmed.substring(0, slash));
        result.put("modelID", trimmed.substring(slash + 1));
        return result;
    }

    private JsonNode sendJson(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenCode Server request failed: " + request.method() + " " + request.uri() + " status=" + response.statusCode() + " body=" + response.body());
        }
        String body = response.body() == null || response.body().isBlank() ? "{}" : response.body();
        return objectMapper.readTree(body);
    }

    private URI resolve(URI serverUrl, String path) {
        String base = serverUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private URI resolve(URI serverUrl, String path, Path directory) {
        return URI.create(resolve(serverUrl, path) + "?directory=" + pathEncode(directory.toAbsolutePath().normalize().toString()));
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
