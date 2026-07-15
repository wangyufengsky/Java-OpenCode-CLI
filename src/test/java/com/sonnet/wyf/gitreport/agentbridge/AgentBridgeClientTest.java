package com.sonnet.wyf.gitreport.agentbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;

class AgentBridgeClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentBridgeClient client = new AgentBridgeClient(objectMapper);
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void postsPromptAndPollsInfoUntilRunningFalse() throws Exception {
        AtomicInteger infoCalls = new AtomicInteger();
        List<String> promptBodies = new ArrayList<>();
        HttpServer server = server();
        server.createContext("/prompt", exchange -> {
            promptBodies.add(body(exchange));
            respond(exchange, 200, "{}");
        });
        server.createContext("/info", exchange -> {
            boolean running = infoCalls.getAndIncrement() == 0;
            respond(exchange, 200, "{\"running\":" + running + "}");
        });
        server.start();

        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client.postPrompt(base, "write report");
        client.waitUntilIdle(base, Duration.ofSeconds(2), Duration.ofMillis(1));

        assertThat(promptBodies).hasSize(1);
        assertThat(objectMapper.readTree(promptBodies.get(0)).path("text").asText()).isEqualTo("write report");
        assertThat(infoCalls.get()).isEqualTo(2);
    }

    @Test
    void parsesStructuredToolResponseFromMcpContentText() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = server();
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            requests.add(request);
            switch (request.path("method").asText()) {
                case "initialize" -> respondWithSession(exchange, 200, "test-session", """
                        {
                          "jsonrpc": "2.0",
                          "id": 1,
                          "result": {"protocolVersion": "2025-11-25", "capabilities": {}}
                        }
                        """);
                case "notifications/initialized" -> respondWithSession(exchange, 202, "test-session", "");
                case "tools/call" -> respondWithSession(exchange, 200, "test-session", """
                        {
                          "jsonrpc": "2.0",
                          "id": 2,
                          "result": {
                            "content": [
                              {"type": "text", "text": "{\\"ok\\":true,\\"tests\\":[\\"OrderServiceTest\\"]}"}
                            ]
                          }
                        }
                        """);
                default -> respond(exchange, 400, "unknown MCP method");
            }
        });
        server.start();

        AgentBridgeClient.ToolResponse response = client.callTool(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"),
                "list_tests",
                objectMapper.createObjectNode().put("file_pattern", "OrderServiceTest")
        );

        assertThat(requests).hasSize(3);
        assertThat(requests.get(2).path("method").asText()).isEqualTo("tools/call");
        assertThat(requests.get(2).path("params").path("name").asText()).isEqualTo("list_tests");
        assertThat(response.text()).contains("OrderServiceTest");
        assertThat(response.structured().path("ok").asBoolean()).isTrue();
        assertThat(response.structured().path("tests")).hasSize(1);
    }

    @Test
    void initializesMcpTransportBeforeCallingTools() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        List<String> sessionHeaders = new ArrayList<>();
        List<String> protocolHeaders = new ArrayList<>();
        HttpServer server = server();
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            requests.add(request);
            sessionHeaders.add(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            protocolHeaders.add(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
            switch (request.path("method").asText()) {
                case "initialize" -> respondWithSession(exchange, 200, "test-session", """
                        {
                          "jsonrpc": "2.0",
                          "id": 1,
                          "result": {"protocolVersion": "2025-11-25", "capabilities": {}}
                        }
                        """);
                case "notifications/initialized" -> respondWithSession(exchange, 202, "test-session", "");
                case "tools/call" -> respondWithSession(exchange, 200, "test-session", """
                        {
                          "jsonrpc": "2.0",
                          "id": 2,
                          "result": {"content": [{"type": "text", "text": "{\\"tests\\":[\\"OrderServiceTest\\"]}"}]}
                        }
                        """);
                default -> respond(exchange, 400, "unknown MCP method");
            }
        });
        server.start();

        AgentBridgeClient.ToolResponse response = client.callTool(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"),
                "list_tests",
                objectMapper.createObjectNode().put("file_pattern", "OrderServiceTest")
        );

        assertThat(response.structured().path("tests")).hasSize(1);
        assertThat(requests).extracting(request -> request.path("method").asText())
                .containsExactly("initialize", "notifications/initialized", "tools/call");
        assertThat(sessionHeaders).containsExactly(null, "test-session", "test-session");
        assertThat(protocolHeaders).containsExactly(null, "2025-11-25", "2025-11-25");
    }

    @Test
    void usesToolTimeoutForLongRunningMcpCalls() throws Exception {
        CapturingHttpClient httpClient = new CapturingHttpClient("""
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "result": {
                    "content": [
                      {"type": "text", "text": "{\\"success\\":true}"}
                    ]
                  }
                }
                """);
        AgentBridgeClient client = new AgentBridgeClient(objectMapper, httpClient);

        client.callTool(
                URI.create("http://127.0.0.1:8642/mcp"),
                "run_command",
                objectMapper.createObjectNode().put("timeout", 2400)
        );

        assertThat(httpClient.lastRequest.timeout()).contains(Duration.ofSeconds(2430));
    }

    private HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(server);
        return server;
    }

    private String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange; var responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    private void respondWithSession(HttpExchange exchange, int status, String sessionId, String body) throws IOException {
        exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
        respond(exchange, status, body);
    }

    private static final class CapturingHttpClient extends HttpClient {
        private static final String INITIALIZE_RESPONSE = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "result": {"protocolVersion": "2025-11-25", "capabilities": {}}
                }
                """;
        private final String responseBody;
        private HttpRequest lastRequest;
        private int requestCount;

        private CapturingHttpClient(String responseBody) {
            this.responseBody = responseBody;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> java.net.http.HttpResponse<T> send(
                HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            this.lastRequest = request;
            requestCount++;
            String body = requestCount == 1 ? INITIALIZE_RESPONSE : requestCount == 2 ? "" : responseBody;
            return new java.net.http.HttpResponse<>() {
                @Override
                public int statusCode() {
                    return 200;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<java.net.http.HttpResponse<T>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(requestCount == 1
                            ? Map.of("Mcp-Session-Id", List.of("test-session"))
                            : Map.of(), (name, value) -> true);
                }

                @Override
                public T body() {
                    return (T) body;
                }

                @Override
                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return request.uri();
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }

        @Override
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler,
                java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
