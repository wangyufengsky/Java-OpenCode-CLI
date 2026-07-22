package com.sonnet.wyf.gitreport.agentbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class AgentBridgeClient {
    private static final String MCP_CLIENT_PROTOCOL_VERSION = "2025-03-26";
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicLong requestIds = new AtomicLong();
    private final Object mcpSessionLock = new Object();
    private final Map<URI, McpSession> mcpSessions = new HashMap<>();

    public AgentBridgeClient(ObjectMapper objectMapper) {
        this(objectMapper, localAgentBridgeHttpClient());
    }

    AgentBridgeClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public void postPrompt(URI webBaseUrl, String prompt) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", prompt);
        HttpResponse<String> response = sendJson(webBaseUrl.resolve("/prompt"), body);
        requireSuccess(response, "AgentBridge POST /prompt failed");
    }

    public void clearSession(URI webBaseUrl) throws IOException, InterruptedException {
        postPrompt(webBaseUrl, "/session-clear");
    }

    public void waitUntilIdle(URI webBaseUrl, Duration timeout, Duration pollInterval) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        long startupGraceNanos = Math.min(Duration.ofSeconds(30).toNanos(), timeout.toNanos());
        long startedWaiting = System.nanoTime();
        boolean observedRunning = false;
        while (true) {
            boolean running = isRunning(webBaseUrl);
            if (running) {
                observedRunning = true;
            } else if (observedRunning || System.nanoTime() - startedWaiting >= startupGraceNanos) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("AgentBridge agent did not finish within " + timeout.toMinutes() + " minutes");
            }
            Thread.sleep(Math.max(1, pollInterval.toMillis()));
        }
    }

    public boolean isRunning(URI webBaseUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(webBaseUrl.resolve("/info"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "AgentBridge GET /info failed");
        return objectMapper.readTree(response.body()).path("running").asBoolean(false);
    }

    public ToolResponse callTool(URI mcpUrl, String name, JsonNode arguments) throws IOException, InterruptedException {
        McpSession session = mcpSession(mcpUrl);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? objectMapper.createObjectNode() : arguments);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", requestIds.incrementAndGet());
        body.put("method", "tools/call");
        body.set("params", params);

        HttpResponse<String> response = sendJson(mcpUrl, body, toolCallTimeout(arguments), session);
        requireSuccess(response, "AgentBridge MCP tools/call failed: " + name);
        JsonNode root = objectMapper.readTree(response.body());
        if (root.hasNonNull("error")) {
            throw new IllegalStateException("AgentBridge MCP tool failed: " + root.path("error"));
        }
        JsonNode result = root.path("result");
        String text = contentText(result);
        return new ToolResponse(result, text, structured(text, result));
    }

    public List<ToolDefinition> listTools(URI mcpUrl) throws IOException, InterruptedException {
        McpSession session = mcpSession(mcpUrl);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", requestIds.incrementAndGet());
        body.put("method", "tools/list");
        body.set("params", objectMapper.createObjectNode());

        HttpResponse<String> response = sendJson(mcpUrl, body, Duration.ofSeconds(30), session);
        requireSuccess(response, "AgentBridge MCP tools/list failed");
        JsonNode root = objectMapper.readTree(response.body());
        if (root.hasNonNull("error")) {
            throw new IllegalStateException("AgentBridge MCP tools/list failed: " + root.path("error"));
        }
        List<ToolDefinition> tools = new ArrayList<>();
        for (JsonNode tool : root.path("result").path("tools")) {
            tools.add(new ToolDefinition(
                    tool.path("name").asText(),
                    tool.path("description").asText(""),
                    tool.path("inputSchema")
            ));
        }
        return List.copyOf(tools);
    }

    public List<ToolCallRecord> getToolCalls(URI webBaseUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(webBaseUrl.resolve("/tool-calls"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "AgentBridge GET /tool-calls failed");
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode items = root.isArray() ? root : root.path("toolCalls");
        if (!items.isArray()) {
            throw new IllegalStateException("AgentBridge GET /tool-calls returned no tool-call array");
        }
        List<ToolCallRecord> calls = new ArrayList<>();
        for (JsonNode item : items) {
            calls.add(new ToolCallRecord(
                    item.path("id").asText(),
                    item.path("title").asText(""),
                    item.path("toolName").asText(),
                    item.path("kind").asText(""),
                    item.path("status").asText(""),
                    Instant.parse(item.path("timestamp").asText()),
                    item.path("arguments"),
                    item.path("result"),
                    item.hasNonNull("durationMs") ? item.path("durationMs").longValue() : null,
                    item.path("hooks")
            ));
        }
        return List.copyOf(calls);
    }

    private HttpResponse<String> sendJson(URI uri, JsonNode body) throws IOException, InterruptedException {
        return sendJson(uri, body, Duration.ofSeconds(30));
    }

    private HttpResponse<String> sendJson(URI uri, JsonNode body, Duration timeout) throws IOException, InterruptedException {
        return sendJson(uri, body, timeout, null);
    }

    private HttpResponse<String> sendJson(URI uri, JsonNode body, Duration timeout, McpSession session) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (session != null) {
            request.header("Mcp-Session-Id", session.id())
                    .header("MCP-Protocol-Version", session.protocolVersion());
        }
        return httpClient.send(request.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private McpSession mcpSession(URI mcpUrl) throws IOException, InterruptedException {
        synchronized (mcpSessionLock) {
            McpSession existing = mcpSessions.get(mcpUrl);
            if (existing != null) {
                return existing;
            }
            McpSession initialized = initializeMcpSession(mcpUrl);
            mcpSessions.put(mcpUrl, initialized);
            return initialized;
        }
    }

    private McpSession initializeMcpSession(URI mcpUrl) throws IOException, InterruptedException {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", MCP_CLIENT_PROTOCOL_VERSION);
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "java-agentbridge-cli");
        clientInfo.put("version", "1.0");

        ObjectNode initialize = objectMapper.createObjectNode();
        initialize.put("jsonrpc", "2.0");
        initialize.put("id", requestIds.incrementAndGet());
        initialize.put("method", "initialize");
        initialize.set("params", params);
        HttpResponse<String> response = sendJson(mcpUrl, initialize, Duration.ofSeconds(30));
        requireSuccess(response, "AgentBridge MCP initialize failed");

        String sessionId = response.headers().firstValue("Mcp-Session-Id")
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("AgentBridge MCP initialize did not return Mcp-Session-Id"));
        String protocolVersion = objectMapper.readTree(response.body()).path("result").path("protocolVersion")
                .asText(MCP_CLIENT_PROTOCOL_VERSION);
        McpSession session = new McpSession(sessionId, protocolVersion);

        ObjectNode initialized = objectMapper.createObjectNode();
        initialized.put("jsonrpc", "2.0");
        initialized.put("method", "notifications/initialized");
        initialized.set("params", objectMapper.createObjectNode());
        HttpResponse<String> notificationResponse = sendJson(mcpUrl, initialized, Duration.ofSeconds(30), session);
        requireSuccess(notificationResponse, "AgentBridge MCP initialization notification failed");
        return session;
    }

    private Duration toolCallTimeout(JsonNode arguments) {
        if (arguments != null && arguments.path("timeout").canConvertToInt()) {
            int seconds = arguments.path("timeout").asInt();
            if (seconds > 0) {
                return Duration.ofSeconds(seconds).plusSeconds(30);
            }
        }
        return Duration.ofSeconds(30);
    }

    private void requireSuccess(HttpResponse<String> response, String message) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(message + ": HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private String contentText(JsonNode result) {
        StringBuilder text = new StringBuilder();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String value = item.path("text").asText("");
                if (!value.isBlank()) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(value);
                }
            }
        }
        if (text.isEmpty() && result.has("structuredContent")) {
            text.append(result.path("structuredContent"));
        }
        return text.toString();
    }

    private JsonNode structured(String text, JsonNode result) {
        if (result.has("structuredContent")) {
            return result.path("structuredContent");
        }
        if (!text.isBlank()) {
            try {
                return objectMapper.readTree(text);
            } catch (Exception ignored) {
                return objectMapper.createObjectNode();
            }
        }
        return objectMapper.createObjectNode();
    }

    public record ToolResponse(JsonNode rawResult, String text, JsonNode structured) {
    }

    public record ToolDefinition(String name, String description, JsonNode inputSchema) {
    }

    public record ToolCallRecord(
            String id,
            String title,
            String toolName,
            String kind,
            String status,
            Instant timestamp,
            JsonNode arguments,
            JsonNode result,
            Long durationMs,
            JsonNode hooks) {
    }

    private record McpSession(String id, String protocolVersion) {
    }

    private static HttpClient localAgentBridgeHttpClient() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("");
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .sslContext(sslContext)
                    .sslParameters(sslParameters)
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("failed to create AgentBridge HTTP client", exception);
        }
    }
}
