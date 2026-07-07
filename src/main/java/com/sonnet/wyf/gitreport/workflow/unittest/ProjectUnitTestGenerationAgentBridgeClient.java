package com.sonnet.wyf.gitreport.workflow.unittest;

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
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class ProjectUnitTestGenerationAgentBridgeClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicLong requestIds = new AtomicLong();

    public ProjectUnitTestGenerationAgentBridgeClient(ObjectMapper objectMapper) {
        this(objectMapper, localAgentBridgeHttpClient());
    }

    ProjectUnitTestGenerationAgentBridgeClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public void postPrompt(URI webBaseUrl, String prompt) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", prompt);
        HttpResponse<String> response = sendJson(webBaseUrl.resolve("/prompt"), body);
        requireSuccess(response, "AgentBridge POST /prompt failed");
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
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? objectMapper.createObjectNode() : arguments);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", requestIds.incrementAndGet());
        body.put("method", "tools/call");
        body.set("params", params);

        HttpResponse<String> response = sendJson(mcpUrl, body);
        requireSuccess(response, "AgentBridge MCP tools/call failed: " + name);
        JsonNode root = objectMapper.readTree(response.body());
        if (root.hasNonNull("error")) {
            throw new IllegalStateException("AgentBridge MCP tool failed: " + root.path("error"));
        }
        JsonNode result = root.path("result");
        String text = contentText(result);
        return new ToolResponse(result, text, structured(text, result));
    }

    private HttpResponse<String> sendJson(URI uri, JsonNode body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
