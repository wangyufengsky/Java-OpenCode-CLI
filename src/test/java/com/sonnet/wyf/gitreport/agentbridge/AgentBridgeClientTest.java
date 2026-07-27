package com.sonnet.wyf.gitreport.agentbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void acceptsNativeAgentBridgeVersionWithoutInventedCapabilityFields() throws Exception {
        HttpServer server = server();
        server.createContext("/info", exchange -> respond(exchange, 200, "{\"version\":\"1.202.1\"}"));
        server.start();

        client.requireDatabaseMcpSupport(baseUri(server));
    }

    @Test
    void rejectsAgentBridgeVersionWithoutFullToolHistory() throws Exception {
        HttpServer server = server();
        server.createContext("/info", exchange -> respond(exchange, 200, "{\"version\":\"1.202.0\"}"));
        server.start();

        assertThatThrownBy(() -> client.requireDatabaseMcpSupport(baseUri(server)))
                .hasMessageContaining("1.202.0")
                .hasMessageContaining("1.202.1");
    }

    @Test
    void rejectsAgentBridgeVersionsBefore12021() throws Exception {
        HttpServer server = server();
        server.createContext("/info", exchange -> respond(exchange, 200, "{\"version\":\"1.201.9\"}"));
        server.start();

        assertThatThrownBy(() -> client.requireDatabaseMcpSupport(baseUri(server)))
                .hasMessageContaining("1.201.9")
                .hasMessageContaining("1.202.1");
    }

    @Test
    void rejectsNonLoopbackAgentBridgeEndpointsBeforeNetworkAccess() {
        CapturingHttpClient transport = new CapturingHttpClient("{}");
        AgentBridgeClient strictClient = new AgentBridgeClient(objectMapper, transport);

        assertThatThrownBy(() -> strictClient.requireDatabaseMcpSupport(
                URI.create("https://agentbridge.example.com")
        )).hasMessageContaining("loopback");
        assertThat(transport.requestCount).isZero();
    }

    @Test
    void postsPromptsToLoopbackEndpoint() throws Exception {
        CapturingHttpClient webTransport = new CapturingHttpClient("{}");
        AgentBridgeClient webClient = new AgentBridgeClient(objectMapper, webTransport);
        webClient.postPrompt(
                URI.create("http://127.0.0.1:8642"),
                "workflow"
        );
        assertThat(webTransport.lastRequest.uri()).isEqualTo(
                URI.create("http://127.0.0.1:8642/prompt")
        );

    }

    @Test
    void rejectsRemoteWebAndMcpEndpointsBeforeAnyRequestWithoutBindingState() {
        URI remoteWeb = URI.create("https://agentbridge.example.com");
        URI remoteMcp = URI.create("https://agentbridge.example.com/mcp");
        CapturingHttpClient transport = new CapturingHttpClient("{}");
        AgentBridgeClient strictClient = new AgentBridgeClient(objectMapper, transport);

        assertThatThrownBy(() -> strictClient.postPrompt(remoteWeb, "workflow"))
                .hasMessageContaining("loopback");
        assertThatThrownBy(() -> strictClient.isRunning(remoteWeb))
                .hasMessageContaining("loopback");
        assertThatThrownBy(() -> strictClient.listTools(remoteMcp))
                .hasMessageContaining("loopback");
        assertThatThrownBy(() -> strictClient.callTool(remoteMcp, "list_tests", objectMapper.createObjectNode()))
                .hasMessageContaining("loopback");
        assertThat(transport.requestCount).isZero();
    }

    @Test
    void exposesOnlyCurrentDatabaseMcpStateAndNativeToolCallHistoryFields() {
        assertThat(AgentBridgeClient.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain(join("requireMyBatis", "SqlReviewCapabilities"),
                        join("bindMyBatis", "SqlReviewEndpoints"));
        assertThat(AgentBridgeClient.class.getDeclaredClasses())
                .extracting(Class::getSimpleName)
                .doesNotContain(join("MyBatis", "AuditBinding"), join("Bridge", "Identity"));
        assertThat(AgentBridgeClient.ToolCallRecord.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("id", "title", "toolName", "kind", "status", "timestamp",
                        "arguments", "result", "durationMs", "hooks");
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
    void fillsMissingStructuredContentFieldsFromJsonContentText() throws Exception {
        HttpServer server = server();
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            switch (request.path("method").asText()) {
                case "initialize" -> respondWithSession(exchange, 200, "empty-structured-session", """
                        {
                          "jsonrpc": "2.0",
                          "id": 1,
                          "result": {"protocolVersion": "2025-11-25", "capabilities": {}}
                        }
                        """);
                case "notifications/initialized" ->
                        respondWithSession(exchange, 202, "empty-structured-session", "");
                case "tools/call" -> respondWithSession(exchange, 200, "empty-structured-session", """
                        {
                          "jsonrpc": "2.0",
                          "id": 2,
                          "result": {
                            "structuredContent": {
                              "totalTablesFound": 413,
                              "sampledCount": 200
                            },
                            "content": [{
                              "type": "text",
                              "text": "{\\"totalTablesFound\\":413,\\"sampledCount\\":200,\\"tables\\":[{\\"tableName\\":\\"spring_session\\"}]}"
                            }]
                          }
                        }
                        """);
                default -> respond(exchange, 400, "unknown MCP method");
            }
        });
        server.start();

        AgentBridgeClient.ToolResponse response = client.callTool(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"),
                "cmcp_db_database_list_table_schema",
                objectMapper.createObjectNode().put("dataSource", "gaussdb")
        );

        assertThat(response.structured().path("totalTablesFound").intValue()).isEqualTo(413);
        assertThat(response.structured().path("sampledCount").intValue()).isEqualTo(200);
        assertThat(response.structured().path("tables").get(0).path("tableName").asText())
                .isEqualTo("spring_session");
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
    void restartsInitializationWhenInitializedNotificationFindsExpiredSession() throws Exception {
        AtomicInteger initializeCalls = new AtomicInteger();
        AtomicInteger initializedNotifications = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            switch (request.path("method").asText()) {
                case "initialize" -> {
                    String sessionId = initializeCalls.incrementAndGet() == 1
                            ? "expired-during-initialize"
                            : "initialized-session";
                    respondWithSession(exchange, 200, sessionId, """
                            {
                              "jsonrpc": "2.0",
                              "id": 1,
                              "result": {"protocolVersion": "2025-11-25", "capabilities": {}}
                            }
                            """);
                }
                case "notifications/initialized" -> {
                    if (initializedNotifications.incrementAndGet() == 1) {
                        respond(exchange, 404, "");
                    } else {
                        respondWithSession(exchange, 202, "initialized-session", "");
                    }
                }
                case "tools/call" -> {
                    toolCalls.incrementAndGet();
                    respondWithSession(exchange, 200, "initialized-session", """
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "result": {
                                "content": [{
                                  "type": "text",
                                  "text": "{\\"tests\\":[\\"OrderServiceTest\\"]}"
                                }]
                              }
                            }
                            """);
                }
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
        assertThat(initializeCalls).hasValue(2);
        assertThat(initializedNotifications).hasValue(2);
        assertThat(toolCalls).hasValue(1);
    }

    @Test
    void reinitializesExpiredMcpSessionAndRetriesToolCallOnce() throws Exception {
        AtomicInteger initializeCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        List<String> methods = new ArrayList<>();
        List<String> sessionHeaders = new ArrayList<>();
        HttpServer server = server();
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            String method = request.path("method").asText();
            methods.add(method);
            sessionHeaders.add(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            switch (method) {
                case "initialize" -> {
                    String sessionId = initializeCalls.incrementAndGet() == 1
                            ? "expired-session"
                            : "fresh-session";
                    respondWithSession(exchange, 200, sessionId, """
                            {
                              "jsonrpc": "2.0",
                              "id": 1,
                              "result": {"protocolVersion": "2025-11-25", "capabilities": {}}
                            }
                            """);
                }
                case "notifications/initialized" -> respondWithSession(
                        exchange,
                        202,
                        exchange.getRequestHeaders().getFirst("Mcp-Session-Id"),
                        ""
                );
                case "tools/call" -> {
                    if (toolCalls.incrementAndGet() == 1) {
                        respond(exchange, 404, """
                                {
                                  "jsonrpc": "2.0",
                                  "error": {
                                    "code": -32600,
                                    "message": "Unknown or expired MCP session: expired-session"
                                  }
                                }
                                """);
                    } else {
                        respondWithSession(exchange, 200, "fresh-session", """
                                {
                                  "jsonrpc": "2.0",
                                  "id": 2,
                                  "result": {
                                    "content": [{
                                      "type": "text",
                                      "text": "{\\"tests\\":[\\"OrderServiceTest\\"]}"
                                    }]
                                  }
                                }
                                """);
                    }
                }
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
        assertThat(methods).containsExactly(
                "initialize",
                "notifications/initialized",
                "tools/call",
                "initialize",
                "notifications/initialized",
                "tools/call"
        );
        assertThat(sessionHeaders).containsExactly(
                null,
                "expired-session",
                "expired-session",
                null,
                "fresh-session",
                "fresh-session"
        );
    }

    @Test
    void reinitializesMcpSessionWhen404HasNoErrorBody() throws Exception {
        AtomicInteger initializeCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            switch (request.path("method").asText()) {
                case "initialize" -> {
                    String sessionId = initializeCalls.incrementAndGet() == 1
                            ? "expired-session"
                            : "fresh-session";
                    respondWithSession(exchange, 200, sessionId, """
                            {
                              "jsonrpc": "2.0",
                              "id": 1,
                              "result": {"protocolVersion": "2025-11-25", "capabilities": {}}
                            }
                            """);
                }
                case "notifications/initialized" -> respondWithSession(
                        exchange,
                        202,
                        exchange.getRequestHeaders().getFirst("Mcp-Session-Id"),
                        ""
                );
                case "tools/call" -> {
                    if (toolCalls.incrementAndGet() == 1) {
                        respond(exchange, 404, "");
                    } else {
                        respondWithSession(exchange, 200, "fresh-session", """
                                {
                                  "jsonrpc": "2.0",
                                  "id": 2,
                                  "result": {
                                    "content": [{
                                      "type": "text",
                                      "text": "{\\"tests\\":[\\"OrderServiceTest\\"]}"
                                    }]
                                  }
                                }
                                """);
                    }
                }
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
        assertThat(initializeCalls).hasValue(2);
        assertThat(toolCalls).hasValue(2);
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

    @Test
    void listsTypedMcpToolsAfterSessionNegotiation() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = server();
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            requests.add(request);
            switch (request.path("method").asText()) {
                case "initialize" -> respondWithSession(exchange, 200, "tool-list-session", """
                        {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{}}}
                        """);
                case "notifications/initialized" -> respondWithSession(exchange, 202, "tool-list-session", "");
                case "tools/list" -> respondWithSession(exchange, 200, "tool-list-session", """
                        {
                          "jsonrpc":"2.0",
                          "id":2,
                          "result":{"tools":[{
                            "name":"cmcp_db_database_execute_sql_query",
                            "description":"Execute a database query",
                            "inputSchema":{"type":"object","required":["dataSource","sql"]}
                          }]}
                        }
                        """);
                default -> respond(exchange, 400, "unknown MCP method");
            }
        });
        server.start();

        List<AgentBridgeClient.ToolDefinition> tools = client.listTools(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp")
        );

        assertThat(requests).extracting(request -> request.path("method").asText())
                .containsExactly("initialize", "notifications/initialized", "tools/list");
        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("cmcp_db_database_execute_sql_query");
            assertThat(tool.description()).contains("database query");
            assertThat(tool.inputSchema().path("required")).hasSize(2);
        });
    }

    @Test
    void followsMcpToolsListCursorUntilAllPagesAreCollected() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = server();
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            requests.add(request);
            switch (request.path("method").asText()) {
                case "initialize" -> respondWithSession(exchange, 200, "paged-session", """
                        {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{}}}
                        """);
                case "notifications/initialized" -> respondWithSession(exchange, 202, "paged-session", "");
                case "tools/list" -> {
                    String cursor = request.at("/params/cursor").asText("");
                    if (cursor.isEmpty()) {
                        respondWithSession(exchange, 200, "paged-session", """
                                {"jsonrpc":"2.0","id":2,"result":{
                                  "tools":[{"name":"cmcp_db_database_list_datasources","inputSchema":{"type":"object"}}],
                                  "nextCursor":"page-2"
                                }}
                                """);
                    } else {
                        respondWithSession(exchange, 200, "paged-session", """
                                {"jsonrpc":"2.0","id":3,"result":{
                                  "tools":[{"name":"cmcp_db_database_execute_sql_query","inputSchema":{"type":"object"}}]
                                }}
                                """);
                    }
                }
                default -> respond(exchange, 400, "unknown MCP method");
            }
        });
        server.start();

        List<AgentBridgeClient.ToolDefinition> tools = client.listTools(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp")
        );

        assertThat(tools).extracting(AgentBridgeClient.ToolDefinition::name)
                .containsExactly("cmcp_db_database_list_datasources", "cmcp_db_database_execute_sql_query");
        assertThat(requests).extracting(request -> request.path("method").asText())
                .containsExactly("initialize", "notifications/initialized", "tools/list", "tools/list");
        assertThat(requests.get(3).at("/params/cursor").asText()).isEqualTo("page-2");
    }

    @Test
    void restartsToolsListPaginationAfterMcpSessionExpires() throws Exception {
        AtomicInteger initializeCalls = new AtomicInteger();
        List<String> listSessions = new ArrayList<>();
        List<String> listCursors = new ArrayList<>();
        HttpServer server = server();
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            switch (request.path("method").asText()) {
                case "initialize" -> {
                    String sessionId = initializeCalls.incrementAndGet() == 1
                            ? "expired-paged-session"
                            : "fresh-paged-session";
                    respondWithSession(exchange, 200, sessionId, """
                            {
                              "jsonrpc": "2.0",
                              "id": 1,
                              "result": {"protocolVersion": "2025-11-25", "capabilities": {}}
                            }
                            """);
                }
                case "notifications/initialized" -> respondWithSession(
                        exchange,
                        202,
                        exchange.getRequestHeaders().getFirst("Mcp-Session-Id"),
                        ""
                );
                case "tools/list" -> {
                    String sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
                    String cursor = request.at("/params/cursor").asText("");
                    listSessions.add(sessionId);
                    listCursors.add(cursor);
                    if ("expired-paged-session".equals(sessionId) && "page-2".equals(cursor)) {
                        respond(exchange, 404, "");
                    } else if (cursor.isEmpty()) {
                        respondWithSession(exchange, 200, sessionId, """
                                {
                                  "jsonrpc": "2.0",
                                  "id": 2,
                                  "result": {
                                    "tools": [{
                                      "name": "cmcp_db_database_list_datasources",
                                      "inputSchema": {"type": "object"}
                                    }],
                                    "nextCursor": "page-2"
                                  }
                                }
                                """);
                    } else {
                        respondWithSession(exchange, 200, sessionId, """
                                {
                                  "jsonrpc": "2.0",
                                  "id": 3,
                                  "result": {
                                    "tools": [{
                                      "name": "cmcp_db_database_execute_sql_query",
                                      "inputSchema": {"type": "object"}
                                    }]
                                  }
                                }
                                """);
                    }
                }
                default -> respond(exchange, 400, "unknown MCP method");
            }
        });
        server.start();

        List<AgentBridgeClient.ToolDefinition> tools = client.listTools(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp")
        );

        assertThat(tools).extracting(AgentBridgeClient.ToolDefinition::name)
                .containsExactly(
                        "cmcp_db_database_list_datasources",
                        "cmcp_db_database_execute_sql_query"
                );
        assertThat(listSessions).containsExactly(
                "expired-paged-session",
                "expired-paged-session",
                "fresh-paged-session",
                "fresh-paged-session"
        );
        assertThat(listCursors).containsExactly("", "page-2", "", "page-2");
    }

    @Test
    void readsTypedWebToolCallHistory() throws Exception {
        HttpServer server = server();
        nativeInfo(server);
        JsonNode fixtureCall = objectMapper.readTree(
                resource("/mybatis-sql-review-fixtures/tool-calls.json")
        ).get(0);
        server.createContext("/tool-calls", exchange -> respond(exchange, 200,
                objectMapper.createObjectNode()
                        .set("items", objectMapper.createArrayNode().add(fixtureCall.deepCopy()))
                        .toString()));
        server.start();

        List<AgentBridgeClient.ToolCallRecord> calls = client.getToolCalls(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort())
        );

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call-17");
            assertThat(call.timestamp()).isEqualTo(Instant.parse("2026-07-22T09:15:30Z"));
            assertThat(call.arguments().path("dataSource").asText()).isEqualTo("GaussDB-ReadOnly");
            assertThat(call.result().path("rows")).hasSize(1);
            assertThat(call.durationMs()).isEqualTo(42L);
            assertThat(call.hooks().path("post").asBoolean()).isTrue();
        });
    }

    @Test
    void toolCallsParseStringEncodedArgumentsAndResults() throws Exception {
        HttpServer server = server();
        nativeInfo(server);
        server.createContext("/tool-calls", exchange -> respond(exchange, 200, """
                {"items":[{"id":"17","title":"Query","toolName":"cmcp_db_database_execute_sql_query",
                "kind":"execute","status":"success","timestamp":"2026-07-23T00:00:00Z",
                "arguments":"{\\"dataSource\\":\\"GaussDB-ReadOnly\\",\\"sql\\":\\"SELECT id FROM audit.orders LIMIT 1\\",\\"maxRows\\":20,\\"project\\":\\"/workspace/example\\",\\"scope\\":\\"ALL\\"}",
                "result":"[{\\"id\\":1}]","durationMs":25}]}
                """));
        server.start();

        AgentBridgeClient.ToolCallRecord call = client.getToolCalls(baseUri(server)).getFirst();

        assertThat(call.arguments().path("maxRows").intValue()).isEqualTo(20);
        assertThat(call.result().isArray()).isTrue();
    }

    @Test
    void toolCallsPreserveSuccessfulPlainTextResults() throws Exception {
        HttpServer server = server();
        nativeInfo(server);
        server.createContext("/tool-calls", exchange -> respond(exchange, 200, """
                {"items":[{"id":"18","title":"Write File","toolName":"write_file",
                "kind":"edit","status":"success","timestamp":"2026-07-23T00:00:01Z",
                "arguments":"{\\"path\\":\\"/workspace/out/report.md\\",\\"content\\":\\"report\\"}",
                "result":"Created /workspace/out/report.md","durationMs":12}]}
                """));
        server.start();

        AgentBridgeClient.ToolCallRecord call = client.getToolCalls(baseUri(server)).getFirst();

        assertThat(call.result().isTextual()).isTrue();
        assertThat(call.result().asText()).isEqualTo("Created /workspace/out/report.md");
    }

    @Test
    void untruncatedToolCallDoesNotFetchAvailableDetail() throws Exception {
        String arguments = "{\"sql\":\"SELECT 1\"}";
        String result = "{\"rows\":[]}";
        HistoryFixture fixture = historyFixture("41", "read_file", arguments, result);
        AtomicInteger detailRequests = new AtomicInteger();
        HttpServer server = historyServer(fixture, 500, detailRequests);

        AgentBridgeClient.ToolCallRecord record = client.getToolCalls(baseUri(server)).getFirst();

        assertThat(record.arguments().path("sql").asText()).isEqualTo("SELECT 1");
        assertThat(record.result().path("rows").isArray()).isTrue();
        assertThat(detailRequests).hasValue(0);
    }

    @Test
    void legacyUntruncatedHistoryWithoutPayloadMetadataRemainsCompatible() throws Exception {
        AtomicInteger detailRequests = new AtomicInteger();
        ObjectNode call = toolCall("legacy-41", "2026-07-24T04:00:00Z");
        HttpServer server = server();
        nativeInfo(server);
        historyList(server, call);
        server.createContext("/tool-calls/legacy-41", exchange -> {
            detailRequests.incrementAndGet();
            respond(exchange, 500, "{\"error\":\"legacy detail must not be requested\"}");
        });
        server.start();

        AgentBridgeClient.ToolCallRecord record = client.getToolCalls(baseUri(server)).getFirst();

        assertThat(record.arguments().path("sql").asText()).contains("SELECT");
        assertThat(record.result().isObject()).isTrue();
        assertThat(record.result().path("rows").path("id").asInt()).isEqualTo(1);
        assertThat(detailRequests).hasValue(0);
    }

    @Test
    void rejectsMissingOrInconsistentUntruncatedSummaryIntegrityMetadata() throws Exception {
        assertRejectedUntruncatedSummaryMutation(
                summary -> summary.remove("argumentsBytes"),
                "argumentsBytes"
        );
        assertRejectedUntruncatedSummaryMutation(
                summary -> summary.put("argumentsBytes", summary.path("argumentsBytes").longValue() + 1),
                "arguments byte length mismatch"
        );
        assertRejectedUntruncatedSummaryMutation(
                summary -> summary.put("argumentsSha256", "0".repeat(64)),
                "arguments SHA-256 mismatch"
        );
        assertRejectedUntruncatedSummaryMutation(
                summary -> summary.remove("resultSha256"),
                "resultSha256"
        );
        assertRejectedUntruncatedSummaryMutation(
                summary -> summary.put("resultBytes", summary.path("resultBytes").longValue() + 1),
                "result byte length mismatch"
        );
        assertRejectedUntruncatedSummaryMutation(
                summary -> summary.put("resultSha256", "0".repeat(64)),
                "result SHA-256 mismatch"
        );
    }

    @Test
    void fetchesAndVerifiesCompleteLongArgumentsAndResult() throws Exception {
        String arguments = objectMapper.createObjectNode()
                .put("path", "/workspace/out/report.md")
                .put("content", "报".repeat(9_000))
                .toString();
        String result = objectMapper.createObjectNode()
                .put("ok", true)
                .put("evidence", "数".repeat(9_000))
                .toString();
        HistoryFixture fixture = historyFixture("77", "write_file", arguments, result);
        AtomicInteger detailRequests = new AtomicInteger();
        HttpServer server = historyServer(fixture, 200, detailRequests);

        AgentBridgeClient.ToolCallRecord record = client.getToolCalls(baseUri(server)).getFirst();

        assertThat(record.arguments().path("content").asText()).hasSize(9_000);
        assertThat(record.result().path("evidence").asText()).hasSize(9_000);
        assertThat(detailRequests).hasValue(1);
    }

    @Test
    void fetchesCompleteLongJsonResultWhenArgumentsAreNotTruncated() throws Exception {
        String arguments = objectMapper.createObjectNode()
                .put("project", "/workspace/example")
                .put("scope", "ALL")
                .put("dataSource", "gaussdb")
                .put("sql", "SELECT id FROM audit.orders LIMIT 1")
                .put("maxRows", 20)
                .toString();
        ObjectNode resultNode = objectMapper.createObjectNode()
                .put("mode", "QUERY")
                .put("hasResultSet", true)
                .put("rowCount", 1);
        var resultRows = objectMapper.createArrayNode();
        resultRows.addObject()
                .put("id", 1)
                .put("evidence", "数".repeat(9_000));
        resultNode.set("rows", resultRows);
        String result = resultNode.toString();
        HistoryFixture fixture = historyFixture(
                "79",
                "cmcp_db_database_execute_sql_query",
                arguments,
                result
        );
        assertThat(fixture.summary().path("argumentsTruncated").asBoolean()).isFalse();
        assertThat(fixture.summary().path("resultTruncated").asBoolean()).isTrue();
        assertThat(fixture.summary().path("result").asText()).endsWith("[…truncated]");
        AtomicInteger detailRequests = new AtomicInteger();
        HttpServer server = historyServer(fixture, 200, detailRequests);

        AgentBridgeClient.ToolCallRecord record = client.getToolCalls(baseUri(server)).getFirst();

        assertThat(record.arguments().path("sql").asText())
                .isEqualTo("SELECT id FROM audit.orders LIMIT 1");
        assertThat(record.result().isObject()).isTrue();
        assertThat(record.result().path("rows").get(0).path("evidence").asText()).hasSize(9_000);
        assertThat(detailRequests).hasValue(1);
    }

    @Test
    void preservesVerifiedPlainTextResultWhenArgumentsAreTruncated() throws Exception {
        String arguments = objectMapper.createObjectNode()
                .put("path", "/workspace/out/report.md")
                .put("content", "x".repeat(9_000))
                .toString();
        String result = "Created /workspace/out/report.md";
        HistoryFixture fixture = historyFixture("78", "write_file", arguments, result);
        HttpServer server = historyServer(fixture, 200, new AtomicInteger());

        AgentBridgeClient.ToolCallRecord record = client.getToolCalls(baseUri(server)).getFirst();

        assertThat(record.arguments().path("content").asText()).hasSize(9_000);
        assertThat(record.result().asText()).isEqualTo(result);
    }

    @Test
    void rejectsTruncatedHistoryWhenFullPayloadIsUnavailable() throws Exception {
        HistoryFixture fixture = longHistoryFixture();
        fixture.summary().put("fullPayloadAvailable", false);
        fixture.summary().put("fullPayloadUnavailableReason", "memory_budget");
        fixture.summary().remove("detailUrl");
        HttpServer server = server();
        nativeInfo(server);
        historyList(server, fixture.summary());
        server.start();

        assertThatThrownBy(() -> client.getToolCalls(baseUri(server)))
                .hasMessageContaining("full payload unavailable")
                .hasMessageContaining("memory_budget");
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void rejectsMissingOrEvictedToolCallDetails(int status) throws Exception {
        HistoryFixture fixture = longHistoryFixture();
        HttpServer server = historyServer(fixture, status, new AtomicInteger());

        assertThatThrownBy(() -> client.getToolCalls(baseUri(server)))
                .hasMessageContaining("GET /tool-calls/77 failed")
                .hasMessageContaining("HTTP " + status);
    }

    @Test
    void rejectsCrossOriginAndNonCanonicalDetailUrls() throws Exception {
        assertRejectedDetailUrl(
                "http://example.com/tool-calls/77",
                "same origin"
        );
        assertRejectedDetailUrl(
                "/tool-calls/77?download=true",
                "query"
        );
        assertRejectedDetailUrl(
                "/tool-calls/077",
                "exactly /tool-calls/77"
        );
    }

    @Test
    void rejectsToolCallDetailIdentityAndTimingMismatches() throws Exception {
        assertRejectedDetailMutation(detail -> detail.put("id", "other"), "id mismatch");
        assertRejectedDetailMutation(detail -> detail.put("toolName", "run_command"), "toolName mismatch");
        assertRejectedDetailMutation(detail -> detail.put("status", "error"), "status mismatch");
        assertRejectedDetailMutation(
                detail -> detail.put("timestamp", "2026-07-24T04:00:01Z"),
                "timestamp mismatch"
        );
        assertRejectedDetailMutation(detail -> detail.put("durationMs", 43), "durationMs mismatch");
    }

    @Test
    void rejectsToolCallDetailLengthAndSha256Mismatches() throws Exception {
        assertRejectedDetailMutation(
                detail -> detail.put("argumentsBytes", detail.path("argumentsBytes").longValue() + 1),
                "arguments byte length mismatch"
        );
        assertRejectedDetailMutation(
                detail -> detail.put("resultSha256", "0".repeat(64)),
                "result SHA-256 mismatch"
        );
    }

    @Test
    void rejectsDuplicateKeysInToolCallDetail() throws Exception {
        HistoryFixture fixture = longHistoryFixture();
        String duplicateDetail = fixture.detail().toString()
                .replaceFirst("\\{\"id\":77", "{\"id\":77,\"id\":77");
        HttpServer server = server();
        nativeInfo(server);
        historyList(server, fixture.summary());
        server.createContext("/tool-calls/77", exchange -> respond(exchange, 200, duplicateDetail));
        server.start();

        assertThatThrownBy(() -> client.getToolCalls(baseUri(server)))
                .hasMessageContaining("detail must contain unique JSON keys");
    }

    @Test
    void abortsToolCallDetailAboveEightMebibytes() throws Exception {
        HistoryFixture fixture = longHistoryFixture();
        HttpServer server = server();
        nativeInfo(server);
        historyList(server, fixture.summary());
        server.createContext("/tool-calls/77", exchange -> respond(
                exchange,
                200,
                "{\"padding\":\"" + "x".repeat(8 * 1024 * 1024) + "\"}"
        ));
        server.start();

        assertThatThrownBy(() -> client.getToolCalls(baseUri(server)))
                .hasMessageContaining("tool-call detail response")
                .hasMessageContaining("byte limit");
    }

    @Test
    void malformedToolCallJsonFailsClosed() throws Exception {
        HttpServer server = server();
        nativeInfo(server);
        server.createContext("/tool-calls", exchange -> respond(exchange, 200, """
                {"items":[{"id":"17","toolName":"cmcp_db_database_execute_sql_query",
                "status":"success","timestamp":"2026-07-23T00:00:00Z","arguments":"not-json","result":"[]"}]}
                """));
        server.start();

        assertThatThrownBy(() -> client.getToolCalls(baseUri(server)))
                .hasMessageContaining("arguments must contain JSON");
    }

    @Test
    void rejectsDuplicateToolCallIds() throws Exception {
        JsonNode firstCall = toolCall("call-1", "2026-07-22T09:15:30Z");
        HttpServer server = server();
        nativeInfo(server);
        server.createContext("/tool-calls", exchange -> respond(exchange, 200,
                objectMapper.createObjectNode()
                        .set("items", objectMapper.createArrayNode()
                                .add(firstCall.deepCopy())
                                .add(firstCall.deepCopy()))
                        .toString()));
        server.start();

        assertThatThrownBy(() -> client.getToolCalls(baseUri(server)))
                .hasMessageContaining("duplicate id: call-1");
    }

    @Test
    void rejectsHistoryWithoutItemsArray() throws Exception {
        HttpServer server = server();
        nativeInfo(server);
        server.createContext("/tool-calls", exchange -> respond(exchange, 200, "{}"));
        server.start();

        assertThatThrownBy(() -> client.getToolCalls(baseUri(server)))
                .hasMessageContaining("items array");
    }

    @Test
    void rejectsToolCallsFieldAlongsideCurrentItemsHistory() throws Exception {
        HttpServer server = server();
        nativeInfo(server);
        server.createContext("/tool-calls", exchange -> respond(exchange, 200,
                "{\"items\":[],\"toolCalls\":\"unexpected\"}"));
        server.start();

        assertThatThrownBy(() -> client.getToolCalls(baseUri(server)))
                .hasMessageContaining("must not contain toolCalls");
    }

    @Test
    void rejectsDuplicateItemsJsonKeys() throws Exception {
        HttpServer server = server();
        nativeInfo(server);
        server.createContext("/tool-calls", exchange -> respond(exchange, 200,
                "{\"items\":[],\"items\":[]}"));
        server.start();

        assertThatThrownBy(() -> client.getToolCalls(baseUri(server)))
                .hasMessageContaining("unique JSON keys");
    }

    @Test
    void toolCallRecordsDefensivelyCopyStructuredValues() throws Exception {
        HttpServer server = server();
        nativeInfo(server);
        server.createContext("/tool-calls", exchange -> respond(exchange, 200,
                objectMapper.createObjectNode().set(
                        "items", objectMapper.createArrayNode().add(toolCall("call-1", "2026-07-22T09:15:30Z"))
                ).toString()));
        server.start();

        AgentBridgeClient.ToolCallRecord call = client.getToolCalls(baseUri(server)).getFirst();
        ((ObjectNode) call.arguments()).put("tampered", true);
        ((ObjectNode) call.result()).put("tampered", true);
        ((ObjectNode) call.hooks()).put("tampered", true);

        assertThat(call.arguments().has("tampered")).isFalse();
        assertThat(call.result().has("tampered")).isFalse();
        assertThat(call.hooks().has("tampered")).isFalse();

        ObjectNode sourceArguments = objectMapper.createObjectNode().put("sql", "SELECT 1");
        ObjectNode sourceResult = objectMapper.createObjectNode().put("rows", 1);
        ObjectNode sourceHooks = objectMapper.createObjectNode().put("post", true);
        AgentBridgeClient.ToolCallRecord constructed = new AgentBridgeClient.ToolCallRecord(
                "constructed", "Query", "cmcp_db_database_execute_sql_query", "execute", "success",
                Instant.parse("2026-07-22T09:15:30Z"), sourceArguments, sourceResult, 1L, sourceHooks
        );
        sourceArguments.put("sourceMutation", true);
        sourceResult.put("sourceMutation", true);
        sourceHooks.put("sourceMutation", true);

        assertThat(constructed.arguments().has("sourceMutation")).isFalse();
        assertThat(constructed.result().has("sourceMutation")).isFalse();
        assertThat(constructed.hooks().has("sourceMutation")).isFalse();
    }

    @Test
    void structuredMcpOutputsDefensivelyCopyJsonValues() {
        ObjectNode rawResult = objectMapper.createObjectNode();
        rawResult.putObject("structuredContent").put("ok", true);
        ObjectNode inputSchema = objectMapper.createObjectNode().put("type", "object");
        AgentBridgeClient.ToolResponse response = new AgentBridgeClient.ToolResponse(
                rawResult, "{\"ok\":true}", rawResult.path("structuredContent")
        );
        AgentBridgeClient.ToolDefinition tool = new AgentBridgeClient.ToolDefinition(
                "cmcp_db_database_execute_sql_query", "query", inputSchema
        );
        rawResult.put("sourceMutation", true);
        inputSchema.put("sourceMutation", true);

        ((ObjectNode) response.rawResult()).put("tampered", true);
        ((ObjectNode) response.structured()).put("tampered", true);
        ((ObjectNode) tool.inputSchema()).put("tampered", true);

        assertThat(response.rawResult().has("tampered")).isFalse();
        assertThat(response.structured().has("tampered")).isFalse();
        assertThat(tool.inputSchema().has("tampered")).isFalse();
        assertThat(response.rawResult().has("sourceMutation")).isFalse();
        assertThat(tool.inputSchema().has("sourceMutation")).isFalse();
    }

    @Test
    void rejectsHistoryRecordsWithInvalidRequiredFields() throws Exception {
        assertInvalidHistoryRecord("toolName", objectMapper.nullNode(), "toolName must not be blank");
        assertInvalidHistoryRecord("status", objectMapper.getNodeFactory().textNode(" "), "status must not be blank");
        assertInvalidHistoryRecord("timestamp", objectMapper.getNodeFactory().textNode("not-a-time"),
                "timestamp must be a valid ISO-8601 instant");
        assertInvalidHistoryRecord("durationMs", objectMapper.getNodeFactory().numberNode(-1),
                "durationMs must be a non-negative integer");
        assertInvalidHistoryRecord("durationMs", objectMapper.getNodeFactory().numberNode(2.5),
                "durationMs must be a non-negative integer");
    }

    @Test
    void rejectsArrayToolCallHistory() throws Exception {
        HttpServer server = server();
        nativeInfo(server);
        server.createContext("/tool-calls", exchange -> respond(exchange, 200, "[]"));
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());

        assertThatThrownBy(() -> client.getToolCalls(base))
                .hasMessageContaining("items array");
    }

    @Test
    void abortsOversizedToolCallHistoryAndSqlToolResultBodies() throws Exception {
        HttpServer historyServer = server();
        nativeInfo(historyServer);
        historyServer.createContext("/tool-calls", exchange -> respond(exchange, 200,
                "{\"items\":[],\"padding\":\""
                        + "x".repeat(1_200_000) + "\"}"));
        historyServer.start();
        assertThatThrownBy(() -> client.getToolCalls(
                URI.create("http://127.0.0.1:" + historyServer.getAddress().getPort())
        )).hasMessageContaining("byte limit");

        HttpServer mcpServer = server();
        mcpServer.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            switch (request.path("method").asText()) {
                case "initialize" -> respondWithSession(exchange, 200, "size-session", """
                        {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{}}}
                        """);
                case "notifications/initialized" -> respondWithSession(exchange, 202, "size-session", "");
                case "tools/call" -> respondWithSession(exchange, 200, "size-session",
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"structuredContent\":{\"padding\":\""
                                + "x".repeat(300_000) + "\"}}}");
                default -> respond(exchange, 400, "unknown MCP method");
            }
        });
        mcpServer.start();

        assertThatThrownBy(() -> client.callTool(
                URI.create("http://127.0.0.1:" + mcpServer.getAddress().getPort() + "/mcp"),
                "cmcp_db_database_execute_sql_query",
                objectMapper.createObjectNode().put("sql", "SELECT 1")
        )).hasMessageContaining("byte limit");
    }

    private ObjectNode toolCall(String id, String timestamp) {
        ObjectNode call = objectMapper.createObjectNode()
                .put("id", id)
                .put("title", "Execute SQL")
                .put("toolName", "cmcp_db_database_execute_sql_query")
                .put("kind", "mcp")
                .put("status", "completed")
                .put("timestamp", timestamp)
                .put("durationMs", 42);
        call.set("arguments", objectMapper.createObjectNode()
                .put("dataSource", "GaussDB-ReadOnly")
                .put("catalog", "orders")
                .put("schema", "audit")
                .put("sql", "SELECT id FROM orders LIMIT 1"));
        call.set("result", objectMapper.createObjectNode()
                .set("rows", objectMapper.createArrayNode().addObject().put("id", 1)));
        call.set("hooks", objectMapper.createObjectNode().put("post", true));
        return call;
    }

    private HistoryFixture longHistoryFixture() {
        String arguments = objectMapper.createObjectNode()
                .put("path", "/workspace/out/report.md")
                .put("content", "x".repeat(9_000))
                .toString();
        String result = objectMapper.createObjectNode()
                .put("ok", true)
                .put("evidence", "y".repeat(9_000))
                .toString();
        return historyFixture("77", "write_file", arguments, result);
    }

    private HistoryFixture historyFixture(String id, String toolName, String arguments, String result) {
        ObjectNode detail = objectMapper.createObjectNode()
                .put("id", Long.parseLong(id))
                .put("title", "Tool call")
                .put("toolName", toolName)
                .put("kind", "edit")
                .put("status", "success")
                .put("timestamp", "2026-07-24T04:00:00Z")
                .put("arguments", arguments)
                .put("result", result)
                .put("durationMs", 42)
                .put("hasHooks", false);
        addPayloadMetadata(detail, "arguments", arguments);
        addPayloadMetadata(detail, "result", result);

        ObjectNode summary = detail.deepCopy();
        summary.put("arguments", historySummary(arguments));
        summary.put("result", historySummary(result));
        summary.put("fullPayloadAvailable", true);
        summary.put("detailUrl", "/tool-calls/" + id);
        return new HistoryFixture(summary, detail);
    }

    private void addPayloadMetadata(ObjectNode node, String prefix, String value) {
        node.put(prefix + "Truncated", value.length() > 8_000);
        node.put(prefix + "Bytes", value.getBytes(StandardCharsets.UTF_8).length);
        node.put(prefix + "Sha256", sha256(value));
    }

    private String historySummary(String value) {
        return value.length() > 8_000
                ? value.substring(0, 8_000) + "\n[…truncated]"
                : value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void historyList(HttpServer server, ObjectNode summary) {
        server.createContext("/tool-calls", exchange -> respond(
                exchange,
                200,
                objectMapper.createObjectNode()
                        .set("items", objectMapper.createArrayNode().add(summary))
                        .toString()
        ));
    }

    private HttpServer historyServer(HistoryFixture fixture, int detailStatus, AtomicInteger detailRequests)
            throws IOException {
        HttpServer server = server();
        nativeInfo(server);
        historyList(server, fixture.summary());
        server.createContext("/tool-calls/77", exchange -> {
            detailRequests.incrementAndGet();
            respond(exchange, detailStatus, detailStatus == 200 ? fixture.detail().toString() : "{\"error\":\"gone\"}");
        });
        if (!"77".equals(fixture.summary().path("id").asText())) {
            String id = fixture.summary().path("id").asText();
            server.createContext("/tool-calls/" + id, exchange -> {
                detailRequests.incrementAndGet();
                respond(exchange, detailStatus,
                        detailStatus == 200 ? fixture.detail().toString() : "{\"error\":\"gone\"}");
            });
        }
        server.start();
        return server;
    }

    private void assertRejectedDetailMutation(Consumer<ObjectNode> mutation, String message) throws Exception {
        HistoryFixture fixture = longHistoryFixture();
        mutation.accept(fixture.detail());
        HttpServer server = historyServer(fixture, 200, new AtomicInteger());

        assertThatThrownBy(() -> client.getToolCalls(baseUri(server))).hasMessageContaining(message);
    }

    private void assertRejectedUntruncatedSummaryMutation(
            Consumer<ObjectNode> mutation,
            String message
    ) throws Exception {
        HistoryFixture fixture = historyFixture(
                "80",
                "read_file",
                "{\"path\":\"/workspace/input.json\"}",
                "{\"rows\":[]}"
        );
        mutation.accept(fixture.summary());
        HttpServer server = historyServer(fixture, 500, new AtomicInteger());

        assertThatThrownBy(() -> client.getToolCalls(baseUri(server))).hasMessageContaining(message);
    }

    private void assertRejectedDetailUrl(String detailUrl, String message) throws Exception {
        HistoryFixture fixture = longHistoryFixture();
        fixture.summary().put("detailUrl", detailUrl);
        HttpServer server = server();
        nativeInfo(server);
        historyList(server, fixture.summary());
        server.start();

        assertThatThrownBy(() -> client.getToolCalls(baseUri(server))).hasMessageContaining(message);
    }

    private record HistoryFixture(ObjectNode summary, ObjectNode detail) {
    }

    private static String join(String first, String second) {
        return first + second;
    }

    private void assertInvalidHistoryRecord(String field, JsonNode value, String message) throws Exception {
        ObjectNode call = toolCall("invalid", "2026-07-22T09:15:30Z");
        call.set(field, value);
        HttpServer server = server();
        nativeInfo(server);
        server.createContext("/tool-calls", exchange -> respond(exchange, 200,
                objectMapper.createObjectNode().set("items", objectMapper.createArrayNode().add(call)).toString()));
        server.start();

        assertThatThrownBy(() -> client.getToolCalls(baseUri(server))).hasMessageContaining(message);
        server.stop(0);
    }

    private HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(server);
        return server;
    }

    private void nativeInfo(HttpServer server) {
        server.createContext("/info", exchange -> respond(exchange, 200, "{\"version\":\"1.202.1\"}"));
    }

    private URI baseUri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private HttpServer nativeBindingServer() throws IOException {
        HttpServer server = server();
        server.createContext("/info", exchange -> respond(
                exchange, 200, "{\"version\":\"1.202.1\"}"
        ));
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            switch (request.path("method").asText()) {
                case "initialize" -> respondWithSession(exchange, 200, "strict-session", """
                        {
                          "jsonrpc":"2.0",
                          "id":1,
                          "result":{
                            "protocolVersion":"2025-11-25"
                          }
                        }
                        """);
                case "notifications/initialized" -> respondWithSession(
                        exchange, 202, "strict-session", ""
                );
                default -> respond(exchange, 400, "unknown MCP method");
            }
        });
        return server;
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("missing test resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
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
