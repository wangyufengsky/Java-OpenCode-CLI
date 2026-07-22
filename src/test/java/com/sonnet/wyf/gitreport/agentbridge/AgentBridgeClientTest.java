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
    void acceptsOnlyTheFutureStrictMyBatisAuditProtocolCapabilityContract() throws Exception {
        HttpServer server = server();
        server.createContext("/info", exchange -> respond(exchange, 200, strictAuditInfo()));
        server.start();

        client.requireMyBatisSqlReviewCapabilities(baseUri(server));
    }

    @Test
    void bindsStrictWebAndMcpEndpointsToTheSameInstanceAndPolicy() throws Exception {
        HttpServer server = strictBindingServer("instance-a", "nonce-a-0123456789");
        server.start();

        AgentBridgeClient.MyBatisAuditBinding binding = client.bindMyBatisSqlReviewEndpoints(
                baseUri(server), URI.create(baseUri(server) + "/mcp")
        );

        assertThat(binding.identity().instanceId()).isEqualTo("instance-a");
        assertThat(binding.identity().projectId()).isEqualTo("project-a");
        assertThat(binding.policyFingerprint()).startsWith("sha256:");
    }

    @Test
    void rejectsWebAndMcpInstanceIdentityMismatch() throws Exception {
        HttpServer server = strictBindingServer(
                "instance-a", "nonce-a-0123456789",
                "instance-b", "nonce-b-0123456789"
        );
        server.start();

        assertThatThrownBy(() -> client.bindMyBatisSqlReviewEndpoints(
                baseUri(server), URI.create(baseUri(server) + "/mcp")
        )).hasMessageContaining("identity mismatch");
    }

    @Test
    void revalidatesBoundMyBatisWebIdentityBeforeSendingEachPrompt() throws Exception {
        AtomicInteger infoCalls = new AtomicInteger();
        AtomicInteger promptCalls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/info", exchange -> {
            boolean initialBinding = infoCalls.getAndIncrement() == 0;
            respond(exchange, 200, strictAuditInfo(
                    initialBinding ? "instance-a" : "instance-b",
                    initialBinding ? "nonce-a-0123456789" : "nonce-b-0123456789"
            ));
        });
        server.createContext("/prompt", exchange -> {
            promptCalls.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            switch (request.path("method").asText()) {
                case "initialize" -> respondWithSession(exchange, 200, "strict-session", """
                        {"jsonrpc":"2.0","id":1,"result":{
                          "protocolVersion":"2025-11-25",
                          "identity":{"instanceId":"instance-a","projectId":"project-a",
                            "instanceNonce":"nonce-a-0123456789"},
                          "policyFingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        }}
                        """);
                case "notifications/initialized" -> respondWithSession(
                        exchange, 202, "strict-session", "");
                default -> respond(exchange, 400, "unknown MCP method");
            }
        });
        server.start();
        URI web = baseUri(server);
        client.bindMyBatisSqlReviewEndpoints(web, URI.create(web + "/mcp"));

        assertThatThrownBy(() -> client.postPrompt(web, "must not reach another instance"))
                .hasMessageContaining("identity or policy fingerprint mismatch");
        assertThat(promptCalls).hasValue(0);
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
    void preservesLegacyNonLoopbackWebAndMcpCompatibilityOutsideStrictMyBatisBinding() throws Exception {
        CapturingHttpClient webTransport = new CapturingHttpClient("{}");
        AgentBridgeClient legacyWebClient = new AgentBridgeClient(objectMapper, webTransport);
        legacyWebClient.postPrompt(
                URI.create("https://legacy-agentbridge.example.com"),
                "legacy workflow"
        );
        assertThat(webTransport.lastRequest.uri()).isEqualTo(
                URI.create("https://legacy-agentbridge.example.com/prompt")
        );

        CapturingHttpClient mcpTransport = new CapturingHttpClient("""
                {"jsonrpc":"2.0","id":3,"result":{
                  "content":[{"type":"text","text":"{\\\"ok\\\":true}"}]
                }}
                """);
        AgentBridgeClient legacyMcpClient = new AgentBridgeClient(objectMapper, mcpTransport);
        AgentBridgeClient.ToolResponse response = legacyMcpClient.callTool(
                URI.create("https://legacy-agentbridge.example.com/mcp"),
                "list_tests",
                objectMapper.createObjectNode()
        );
        assertThat(response.structured().path("ok").asBoolean()).isTrue();
        assertThat(mcpTransport.lastRequest.uri()).isEqualTo(
                URI.create("https://legacy-agentbridge.example.com/mcp")
        );
    }

    @Test
    void rejectsStrictCapabilityWithoutServerEnforcedExecuteSqlPolicy() throws Exception {
        HttpServer server = server();
        ObjectNode info = (ObjectNode) objectMapper.readTree(strictAuditInfo());
        ((ObjectNode) info.at("/capabilities/mybatisSqlReviewAudit")).remove("executeSqlQueryPolicy");
        server.createContext("/info", exchange -> respond(exchange, 200, info.toString()));
        server.start();

        assertThatThrownBy(() -> client.requireMyBatisSqlReviewCapabilities(baseUri(server)))
                .hasMessageContaining("execute_sql_query policy");
    }

    @Test
    void rejectsLegacy11992BeforeDatabaseToolsCanBeUsed() throws Exception {
        HttpServer server = server();
        server.createContext("/info", exchange -> respond(exchange, 200, """
                {"version":"1.199.2","capabilities":{}}
                """));
        server.start();

        assertThatThrownBy(() -> client.requireMyBatisSqlReviewCapabilities(baseUri(server)))
                .hasMessageContaining("1.199.2")
                .hasMessageContaining("incompatible")
                .hasMessageContaining("immutable")
                .hasMessageContaining("preview");
    }

    @Test
    void rejectsVersionOnlyOrPartialAuditCapabilityClaims() throws Exception {
        HttpServer server = server();
        server.createContext("/info", exchange -> respond(exchange, 200, """
                {
                  "version":"1.200.0",
                  "capabilities":{"mybatisSqlReviewAudit":{"contractVersion":1}}
                }
                """));
        server.start();

        assertThatThrownBy(() -> client.requireMyBatisSqlReviewCapabilities(baseUri(server)))
                .hasMessageContaining("capability")
                .hasMessageContaining("untruncatedStructuredToolArguments");
    }

    @Test
    void rejectsPreviewCapabilityWhenServerMaximumIsNotExactlyTwenty() throws Exception {
        HttpServer server = server();
        server.createContext("/info", exchange -> respond(
                exchange, 200, strictAuditInfo().replace(
                        "\"serverEnforcedPreviewMaxRows\":20",
                        "\"serverEnforcedPreviewMaxRows\":21"
                )
        ));
        server.start();

        assertThatThrownBy(() -> client.requireMyBatisSqlReviewCapabilities(baseUri(server)))
                .hasMessageContaining("server-enforced preview max 20");
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
                            "name":"execute_sql_query",
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
            assertThat(tool.name()).isEqualTo("execute_sql_query");
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
                                  "tools":[{"name":"list_database_connections","inputSchema":{"type":"object"}}],
                                  "nextCursor":"page-2"
                                }}
                                """);
                    } else {
                        respondWithSession(exchange, 200, "paged-session", """
                                {"jsonrpc":"2.0","id":3,"result":{
                                  "tools":[{"name":"execute_sql_query","inputSchema":{"type":"object"}}]
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
                .containsExactly("list_database_connections", "execute_sql_query");
        assertThat(requests).extracting(request -> request.path("method").asText())
                .containsExactly("initialize", "notifications/initialized", "tools/list", "tools/list");
        assertThat(requests.get(3).at("/params/cursor").asText()).isEqualTo("page-2");
    }

    @Test
    void readsTypedWebToolCallHistory() throws Exception {
        HttpServer server = server();
        JsonNode fixtureCall = objectMapper.readTree(
                resource("/mybatis-sql-review-fixtures/tool-calls.json")
        ).get(0);
        server.createContext("/tool-calls", exchange -> respond(exchange, 200,
                objectMapper.createObjectNode()
                        .put("complete", true)
                        .put("snapshotToken", "snapshot-typed")
                        .put("total", 1)
                        .set("toolCalls", objectMapper.createArrayNode().add(fixtureCall.deepCopy()))
                        .toString()));
        server.start();

        List<AgentBridgeClient.ToolCallRecord> calls = client.getToolCalls(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort())
        );

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call-17");
            assertThat(call.toolName()).isEqualTo("execute_sql_query");
            assertThat(call.timestamp()).isEqualTo(Instant.parse("2026-07-22T09:15:30Z"));
            assertThat(call.arguments().path("connectionId").asText()).isEqualTo("gauss-readonly");
            assertThat(call.result().path("rows")).hasSize(1);
            assertThat(call.durationMs()).isEqualTo(42L);
            assertThat(call.hooks().path("post").asBoolean()).isTrue();
        });
    }

    @Test
    void followsStableCursorPagesAndDeduplicatesOverlappingRecords() throws Exception {
        List<String> queries = new ArrayList<>();
        JsonNode firstCall = toolCall("call-1", "2026-07-22T09:15:30Z");
        JsonNode secondCall = toolCall("call-2", "2026-07-22T09:16:30Z");
        HttpServer server = server();
        server.createContext("/tool-calls", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            if (exchange.getRequestURI().getRawQuery() == null) {
                respond(exchange, 200, objectMapper.createObjectNode()
                        .put("complete", false)
                        .put("snapshotToken", "snapshot-1")
                        .put("total", 2)
                        .put("nextCursor", "page-2")
                        .set("items", objectMapper.createArrayNode()
                                .add(firstCall.deepCopy())
                                .add(secondCall.deepCopy()))
                        .toString());
            } else {
                respond(exchange, 200, objectMapper.createObjectNode()
                        .put("complete", true)
                        .put("snapshotToken", "snapshot-1")
                        .put("total", 2)
                        .set("items", objectMapper.createArrayNode().add(secondCall.deepCopy()))
                        .toString());
            }
        });
        server.start();

        List<AgentBridgeClient.ToolCallRecord> calls = client.getToolCalls(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort())
        );

        assertThat(calls).extracting(AgentBridgeClient.ToolCallRecord::id)
                .containsExactly("call-1", "call-2");
        assertThat(queries).containsExactly(null, "cursor=page-2");
    }

    @Test
    void followsPageTokenPaginationWithStableBoundaryAndTotal() throws Exception {
        List<String> queries = new ArrayList<>();
        HttpServer server = server();
        server.createContext("/tool-calls", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            boolean first = exchange.getRequestURI().getRawQuery() == null;
            ObjectNode page = objectMapper.createObjectNode()
                    .put("complete", !first)
                    .put("snapshotToken", "snapshot-token")
                    .put("total", 1);
            if (first) {
                page.put("nextPageToken", "token-2");
                page.set("toolCalls", objectMapper.createArrayNode());
            } else {
                page.set("toolCalls", objectMapper.createArrayNode()
                        .add(toolCall("call-token", "2026-07-22T09:17:30Z")));
            }
            respond(exchange, 200, page.toString());
        });
        server.start();

        assertThat(client.getToolCalls(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort())
        )).extracting(AgentBridgeClient.ToolCallRecord::id).containsExactly("call-token");
        assertThat(queries).containsExactly(null, "pageToken=token-2");
    }

    @Test
    void rejectsMissingOrRepeatedPaginationTokenAndPageDrift() throws Exception {
        assertToolCallsRejected(List.of("""
                {"items":[],"total":0}
                """), "boolean complete");
        assertToolCallsRejected(List.of("""
                {"items":[],"complete":false,"snapshotToken":"stable","total":1}
                """), "missing continuation token");
        assertToolCallsRejected(List.of("""
                {"items":[],"complete":true,"snapshotToken":"stable","total":1}
                """), "total does not match");
        assertToolCallsRejected(List.of(
                """
                {"items":[],"complete":false,"snapshotToken":"stable","total":1,"nextCursor":"same"}
                """,
                """
                {"items":[],"complete":false,"snapshotToken":"stable","total":1,"nextCursor":"same"}
                """
        ), "repeated continuation token");
        assertToolCallsRejected(List.of(
                """
                {"items":[],"complete":false,"snapshotToken":"stable","total":1,"nextCursor":"page-2"}
                """,
                """
                {"items":[],"complete":true,"snapshotToken":"changed","total":0}
                """
        ), "page drift");
    }

    @Test
    void rejectsArrayHistoryEvenWhenLegacyHeaderClaimsCompleteness() throws Exception {
        HttpServer server = server();
        server.createContext("/tool-calls", exchange -> respond(exchange, 200, "[]"));
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());

        assertThatThrownBy(() -> client.getToolCalls(base))
                .hasMessageContaining("immutable complete snapshot");
        server.stop(0);

        HttpServer completeServer = server();
        completeServer.createContext("/tool-calls", exchange -> {
            exchange.getResponseHeaders().set("X-AgentBridge-Tool-Calls-Complete", "true");
            respond(exchange, 200, "[]");
        });
        completeServer.start();
        assertThatThrownBy(() -> client.getToolCalls(
                URI.create("http://127.0.0.1:" + completeServer.getAddress().getPort())
        )).hasMessageContaining("immutable complete snapshot");
    }

    @Test
    void abortsOversizedToolCallHistoryAndSqlToolResultBodies() throws Exception {
        HttpServer historyServer = server();
        historyServer.createContext("/tool-calls", exchange -> respond(exchange, 200,
                "{\"complete\":true,\"total\":0,\"items\":[],\"padding\":\""
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
                "execute_sql_query",
                objectMapper.createObjectNode().put("queryText", "SELECT 1")
        )).hasMessageContaining("byte limit");
    }

    private void assertToolCallsRejected(List<String> pages, String message) throws Exception {
        AtomicInteger page = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/tool-calls", exchange -> respond(
                exchange, 200, pages.get(Math.min(page.getAndIncrement(), pages.size() - 1))
        ));
        server.start();
        assertThatThrownBy(() -> client.getToolCalls(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort())
        )).hasMessageContaining(message);
        server.stop(0);
    }

    private ObjectNode toolCall(String id, String timestamp) {
        ObjectNode call = objectMapper.createObjectNode()
                .put("id", id)
                .put("title", "Execute SQL")
                .put("toolName", "execute_sql_query")
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

    private HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(server);
        return server;
    }

    private URI baseUri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private String strictAuditInfo() {
        return strictAuditInfo("instance-a", "nonce-a-0123456789");
    }

    private String strictAuditInfo(String instanceId, String nonce) {
        return """
                {
                  "version":"1.200.0",
                  "identity":{
                    "instanceId":"%s",
                    "projectId":"project-a",
                    "instanceNonce":"%s"
                  },
                  "capabilities":{
                    "mybatisSqlReviewAudit":{
                      "contractVersion":1,
                      "untruncatedStructuredToolArguments":true,
                      "untruncatedStructuredToolResults":true,
                      "immutableToolCallSnapshot":true,
                      "stableToolCallTotal":true,
                      "explicitToolCallHistoryComplete":true,
                      "serverEnforcedPreviewMaxRows":20,
                      "previewMaxRowsRequired":true,
                      "executeSqlQueryPolicy":{
                        "simpleSelectGrammar":true,
                        "safeRelationAllowlist":true,
                        "functionsForbidden":true,
                        "systemSideEffectsForbidden":true,
                        "maxScenarios":3,
                        "maxRows":20,
                        "maxTimeoutSeconds":30,
                        "policyFingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                      }
                    }
                  }
                }
                """.formatted(instanceId, nonce);
    }

    private HttpServer strictBindingServer(String instanceId, String nonce) throws IOException {
        return strictBindingServer(instanceId, nonce, instanceId, nonce);
    }

    private HttpServer strictBindingServer(
            String webInstanceId,
            String webNonce,
            String mcpInstanceId,
            String mcpNonce
    ) throws IOException {
        HttpServer server = server();
        server.createContext("/info", exchange -> respond(
                exchange, 200, strictAuditInfo(webInstanceId, webNonce)
        ));
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            switch (request.path("method").asText()) {
                case "initialize" -> respondWithSession(exchange, 200, "strict-session", """
                        {
                          "jsonrpc":"2.0",
                          "id":1,
                          "result":{
                            "protocolVersion":"2025-11-25",
                            "identity":{
                              "instanceId":"%s",
                              "projectId":"project-a",
                              "instanceNonce":"%s"
                            },
                            "policyFingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                          }
                        }
                        """.formatted(mcpInstanceId, mcpNonce));
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
