package com.sonnet.wyf.gitreport.agentbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
import java.time.Duration;
import java.time.Instant;
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
        server.createContext("/info", exchange -> respond(exchange, 200, "{\"version\":\"1.202.0\"}"));
        server.start();

        client.requireMyBatisSqlReviewCapabilities(baseUri(server));
    }

    @Test
    void bindsNativeLoopbackWebAndMcpEndpoints() throws Exception {
        HttpServer server = nativeBindingServer();
        server.start();

        AgentBridgeClient.MyBatisAuditBinding binding = client.bindMyBatisSqlReviewEndpoints(
                baseUri(server), URI.create(baseUri(server) + "/mcp")
        );

        assertThat(binding).isNotNull();
    }

    @Test
    void rejectsAgentBridgeVersionsBefore12020() throws Exception {
        HttpServer server = server();
        server.createContext("/info", exchange -> respond(exchange, 200, "{\"version\":\"1.201.9\"}"));
        server.start();

        assertThatThrownBy(() -> client.requireMyBatisSqlReviewCapabilities(baseUri(server)))
                .hasMessageContaining("1.201.9")
                .hasMessageContaining("1.202.0");
    }

    @Test
    void rejectsNonLoopbackAgentBridgeEndpointsBeforeNetworkAccess() {
        CapturingHttpClient transport = new CapturingHttpClient("{}");
        AgentBridgeClient strictClient = new AgentBridgeClient(objectMapper, transport);

        assertThatThrownBy(() -> strictClient.bindMyBatisSqlReviewEndpoints(
                URI.create("https://agentbridge.example.com"),
                URI.create("https://agentbridge.example.com/mcp")
        )).hasMessageContaining("loopback");
        assertThat(transport.requestCount).isZero();
    }

    @Test
    void usesDirectWebAndMcpEndpointsOutsideNativeDatabaseBinding() throws Exception {
        CapturingHttpClient webTransport = new CapturingHttpClient("{}");
        AgentBridgeClient webClient = new AgentBridgeClient(objectMapper, webTransport);
        webClient.postPrompt(
                URI.create("https://agentbridge.example.com"),
                "workflow"
        );
        assertThat(webTransport.lastRequest.uri()).isEqualTo(
                URI.create("https://agentbridge.example.com/prompt")
        );

        CapturingHttpClient mcpTransport = new CapturingHttpClient("""
                {"jsonrpc":"2.0","id":3,"result":{
                  "content":[{"type":"text","text":"{\\\"ok\\\":true}"}]
                }}
                """);
        AgentBridgeClient mcpClient = new AgentBridgeClient(objectMapper, mcpTransport);
        AgentBridgeClient.ToolResponse response = mcpClient.callTool(
                URI.create("https://agentbridge.example.com/mcp"),
                "list_tests",
                objectMapper.createObjectNode()
        );
        assertThat(response.structured().path("ok").asBoolean()).isTrue();
        assertThat(mcpTransport.lastRequest.uri()).isEqualTo(
                URI.create("https://agentbridge.example.com/mcp")
        );
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
                            "inputSchema":{"type":"object","required":["connectionId","queryText"]}
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
            assertThat(call.arguments().path("connectionId").asText()).isEqualTo("gauss-readonly");
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
                objectMapper.createObjectNode().put("queryText", "SELECT 1")
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
                .put("connectionId", "gauss-readonly")
                .put("databaseName", "orders")
                .put("schemaName", "audit")
                .put("queryText", "SELECT id FROM orders LIMIT 1"));
        call.set("result", objectMapper.createObjectNode()
                .set("rows", objectMapper.createArrayNode().addObject().put("id", 1)));
        call.set("hooks", objectMapper.createObjectNode().put("post", true));
        return call;
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
        server.createContext("/info", exchange -> respond(exchange, 200, "{\"version\":\"1.202.0\"}"));
    }

    private URI baseUri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private HttpServer nativeBindingServer() throws IOException {
        HttpServer server = server();
        server.createContext("/info", exchange -> respond(
                exchange, 200, "{\"version\":\"1.202.0\"}"
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
