package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisDatabasePreflightTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void verifiesCentralizedGaussDatabaseSchemaToolsAndWebHistory() throws Exception {
        FakeDatabaseBridge bridge = startBridge(
                fixture("connections-centralized.json"),
                fixture("schemas-orders.json"),
                true,
                MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS
        );

        MyBatisDatabasePreflight.Result result = new MyBatisDatabasePreflight(new AgentBridgeClient(objectMapper))
                .verify(bridge.mcpUri(), bridge.webUri(), contract());

        assertThat(result.connectionId()).isEqualTo("gauss-readonly");
        assertThat(result.databaseName()).isEqualTo("orders");
        assertThat(result.schemaName()).isEqualTo("audit");
        assertThat(result.databaseSystem()).isEqualTo("GaussDB");
        assertThat(result.environment()).isEqualTo(MyBatisDatabasePreflight.Environment.TEST);
        assertThat(result.statementTimeout()).isEqualTo(timeoutContract());
        assertThat(result.safeBaseRelations()).containsExactlyInAnyOrder("audit.orders", "audit.line_items");
        assertThatThrownBy(() -> result.safeBaseRelations().add("audit.injected"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(bridge.calledTools).containsExactly(
                "list_database_connections",
                "test_database_connection",
                "list_database_schemas",
                "list_schema_object_kinds",
                "list_schema_objects"
        );
        assertThat(bridge.argumentsFor("list_schema_object_kinds"))
                .isEqualTo(objectMapper.createObjectNode().put("connectionId", "gauss-readonly"));
        assertThat(bridge.argumentsFor("list_schema_objects"))
                .isEqualTo(databaseArguments().put("kind", "TABLE"));
        assertThat(bridge.toolCallHistoryRequested).isTrue();
    }

    @Test
    void exposesOnlyAValidateMintedFinalCapabilityWithoutPublicConstructionOrRawSafeSetAuditInput() {
        assertThat(MyBatisDatabasePreflight.Result.class.isRecord()).isFalse();
        assertThat(Modifier.isFinal(MyBatisDatabasePreflight.Result.class.getModifiers())).isTrue();
        assertThat(MyBatisDatabasePreflight.Result.class.getConstructors()).isEmpty();
        assertThat(MyBatisDatabasePreflight.Result.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        List<Class<?>> auditParameterTypes = Arrays.stream(MyBatisToolCallAudit.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("audit"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .toList();
        assertThat(auditParameterTypes)
                .contains(MyBatisDatabasePreflight.Result.class)
                .doesNotContain(Set.class);
    }

    @Test
    void failsClosedUnlessEverySafeRelationIsExplicitlyReportedAsConfiguredSchemaBaseTable() throws Exception {
        ObjectNode missingKind = fixture("schema-objects-table-audit.json").deepCopy();
        ((ObjectNode) missingKind.at("/objects/0")).remove("kind");
        assertRejectedMetadata(fixture("schema-object-kinds-audit.json"), missingKind,
                "explicitly report kind TABLE");

        ObjectNode wrongSchema = fixture("schema-objects-table-audit.json").deepCopy();
        wrongSchema.put("schemaName", "public");
        assertRejectedMetadata(fixture("schema-object-kinds-audit.json"), wrongSchema,
                "configured schema");

        ObjectNode missingTableKind = fixture("schema-object-kinds-audit.json").deepCopy();
        ((ArrayNode) missingTableKind.path("objectKinds")).removeAll()
                .addObject().put("code", "VIEW").put("name", "View");
        assertRejectedMetadata(missingTableKind, fixture("schema-objects-table-audit.json"),
                "exact TABLE object kind");
    }

    @Test
    void rejectsMissingOrAmbiguousConnectionAndWrongSchema() throws Exception {
        JsonNode baseConnections = fixture("connections-centralized.json");
        assertRejected(baseConnections, fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Missing", "orders", "audit", MyBatisDatabasePreflight.Environment.TEST, true, true, true,
                        timeoutContract()),
                "exactly one connection");

        ObjectNode duplicateConnections = baseConnections.deepCopy();
        ((ArrayNode) duplicateConnections.path("connections")).add(baseConnections.path("connections").get(0).deepCopy());
        assertRejected(duplicateConnections, fixture("schemas-orders.json"), true, contract(), "exactly one connection");

        assertRejected(baseConnections, fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "missing", MyBatisDatabasePreflight.Environment.TEST, true, true, true,
                        timeoutContract()),
                "schema");
    }

    @Test
    void rejectsUnavailableDatabaseMissingToolsAndNonCentralizedGaussDb() throws Exception {
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), false,
                contract(), "unavailable");

        Set<String> missingExecute = new LinkedHashSet<>(MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS);
        missingExecute.remove("execute_sql_query");
        FakeDatabaseBridge missingToolBridge = startBridge(
                fixture("connections-centralized.json"), fixture("schemas-orders.json"), true, missingExecute);
        assertThatThrownBy(() -> new MyBatisDatabasePreflight(new AgentBridgeClient(objectMapper))
                .verify(missingToolBridge.mcpUri(), missingToolBridge.webUri(), contract()))
                .hasMessageContaining("execute_sql_query");
        stopServer();

        ObjectNode distributed = fixture("connections-centralized.json").deepCopy();
        ((ObjectNode) distributed.at("/connections/0")).put("deployment", "distributed");
        assertRejected(distributed, fixture("schemas-orders.json"), true, contract(), "centralized GaussDB");

        ObjectNode postgresql = fixture("connections-centralized.json").deepCopy();
        ((ObjectNode) postgresql.at("/connections/0")).put("databaseSystem", "PostgreSQL");
        assertRejected(postgresql, fixture("schemas-orders.json"), true, contract(), "centralized GaussDB");
    }

    @Test
    void requiresReadReplicaOrTestAndExplicitReadOnlyCredentialContract() throws Exception {
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "audit", MyBatisDatabasePreflight.Environment.PRODUCTION_PRIMARY,
                        true, true, true,
                        timeoutContract()),
                "read-replica or test");
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "audit", MyBatisDatabasePreflight.Environment.TEST,
                        false, true, true,
                        timeoutContract()),
                "non-owner/non-admin read-only account");
    }

    @Test
    void requiresConfirmedRlsDisabledAndFunctionExecuteRevokedIncludingPublic() throws Exception {
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "audit", MyBatisDatabasePreflight.Environment.TEST,
                        true, false, true, timeoutContract()),
                "RLS disabled");
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "audit", MyBatisDatabasePreflight.Environment.TEST,
                        true, true, false, timeoutContract()),
                "including PUBLIC");
    }

    @Test
    void requiresConfirmedDatabaseServerOrRoleStatementTimeoutAtMostThirtySeconds() throws Exception {
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "audit", MyBatisDatabasePreflight.Environment.TEST,
                        true, true, true,
                        new MyBatisDatabasePreflight.StatementTimeoutContract(
                                Duration.ofSeconds(31), MyBatisDatabasePreflight.StatementTimeoutScope.ROLE, true)),
                "30 seconds");
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "audit", MyBatisDatabasePreflight.Environment.TEST,
                        true, true, true,
                        new MyBatisDatabasePreflight.StatementTimeoutContract(
                                Duration.ofSeconds(30), MyBatisDatabasePreflight.StatementTimeoutScope.DATABASE, false)),
                "confirmed");
    }

    private void assertRejected(
            JsonNode connections,
            JsonNode schemas,
            boolean connectionAvailable,
            MyBatisDatabasePreflight.DatabaseContract contract,
            String message
    ) throws Exception {
        FakeDatabaseBridge bridge = startBridge(
                connections, schemas, connectionAvailable, MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS);
        assertThatThrownBy(() -> new MyBatisDatabasePreflight(new AgentBridgeClient(objectMapper))
                .verify(bridge.mcpUri(), bridge.webUri(), contract))
                .hasMessageContaining(message);
        stopServer();
    }

    private void assertRejectedMetadata(JsonNode objectKinds, JsonNode schemaObjects, String message) throws Exception {
        FakeDatabaseBridge bridge = startBridge(
                fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS, objectKinds, schemaObjects);
        assertThatThrownBy(() -> new MyBatisDatabasePreflight(new AgentBridgeClient(objectMapper))
                .verify(bridge.mcpUri(), bridge.webUri(), contract()))
                .hasMessageContaining(message);
        stopServer();
    }

    private MyBatisDatabasePreflight.DatabaseContract contract() {
        return new MyBatisDatabasePreflight.DatabaseContract(
                "Gauss Review",
                "orders",
                "audit",
                MyBatisDatabasePreflight.Environment.TEST,
                true,
                true,
                true,
                timeoutContract()
        );
    }

    private MyBatisDatabasePreflight.StatementTimeoutContract timeoutContract() {
        return new MyBatisDatabasePreflight.StatementTimeoutContract(
                Duration.ofSeconds(30),
                MyBatisDatabasePreflight.StatementTimeoutScope.ROLE,
                true
        );
    }

    private FakeDatabaseBridge startBridge(
            JsonNode connections,
            JsonNode schemas,
            boolean connectionAvailable,
            Set<String> tools
    ) throws IOException {
        return startBridge(
                connections,
                schemas,
                connectionAvailable,
                tools,
                fixture("schema-object-kinds-audit.json"),
                fixture("schema-objects-table-audit.json")
        );
    }

    private FakeDatabaseBridge startBridge(
            JsonNode connections,
            JsonNode schemas,
            boolean connectionAvailable,
            Set<String> tools,
            JsonNode objectKinds,
            JsonNode schemaObjects
    ) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeDatabaseBridge bridge = new FakeDatabaseBridge(
                server, connections, schemas, connectionAvailable, tools, objectKinds, schemaObjects);
        bridge.install();
        server.start();
        return bridge;
    }

    private JsonNode fixture(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/mybatis-sql-review-fixtures/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return objectMapper.readTree(input);
        }
    }

    private final class FakeDatabaseBridge {
        private final HttpServer httpServer;
        private final JsonNode connections;
        private final JsonNode schemas;
        private final boolean connectionAvailable;
        private final Set<String> tools;
        private final JsonNode objectKinds;
        private final JsonNode schemaObjects;
        private final Set<String> calledTools = new LinkedHashSet<>();
        private final List<ToolInvocation> invocations = new ArrayList<>();
        private boolean toolCallHistoryRequested;

        private FakeDatabaseBridge(
                HttpServer httpServer,
                JsonNode connections,
                JsonNode schemas,
                boolean connectionAvailable,
                Set<String> tools,
                JsonNode objectKinds,
                JsonNode schemaObjects
        ) {
            this.httpServer = httpServer;
            this.connections = connections;
            this.schemas = schemas;
            this.connectionAvailable = connectionAvailable;
            this.tools = tools;
            this.objectKinds = objectKinds;
            this.schemaObjects = schemaObjects;
        }

        private void install() {
            httpServer.createContext("/mcp", exchange -> {
                JsonNode request = objectMapper.readTree(readBody(exchange));
                switch (request.path("method").asText()) {
                    case "initialize" -> respondWithSession(exchange, 200, "db-session", """
                            {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{}}}
                            """);
                    case "notifications/initialized" -> respondWithSession(exchange, 202, "db-session", "");
                    case "tools/list" -> respondToolsList(exchange);
                    case "tools/call" -> {
                        String toolName = request.at("/params/name").asText();
                        calledTools.add(toolName);
                        invocations.add(new ToolInvocation(
                                toolName, request.at("/params/arguments").deepCopy()));
                        JsonNode result = switch (toolName) {
                            case "list_database_connections" -> connections;
                            case "test_database_connection" -> objectMapper.createObjectNode()
                                    .put("success", connectionAvailable);
                            case "list_database_schemas" -> schemas;
                            case "list_schema_object_kinds" -> objectKinds;
                            case "list_schema_objects" -> schemaObjects;
                            default -> objectMapper.createObjectNode();
                        };
                        respondMcp(exchange, result);
                    }
                    default -> respond(exchange, 400, "unknown MCP method");
                }
            });
            httpServer.createContext("/tool-calls", exchange -> {
                toolCallHistoryRequested = true;
                respond(exchange, 200, "[]");
            });
        }

        private JsonNode toolsResponse() {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode array = root.putArray("tools");
            tools.forEach(name -> array.addObject()
                    .put("name", name)
                    .put("description", name)
                    .set("inputSchema", objectMapper.createObjectNode().put("type", "object")));
            return root;
        }

        private void respondToolsList(HttpExchange exchange) throws IOException {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("jsonrpc", "2.0");
            root.put("id", 2);
            root.set("result", toolsResponse());
            respondWithSession(exchange, 200, "db-session", objectMapper.writeValueAsString(root));
        }

        private void respondMcp(HttpExchange exchange, JsonNode structured) throws IOException {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("jsonrpc", "2.0");
            root.put("id", 2);
            ObjectNode result = root.putObject("result");
            result.putArray("content").addObject()
                    .put("type", "text")
                    .put("text", objectMapper.writeValueAsString(structured));
            respondWithSession(exchange, 200, "db-session", objectMapper.writeValueAsString(root));
        }

        private URI mcpUri() {
            return URI.create(webUri() + "/mcp");
        }

        private URI webUri() {
            return URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort());
        }

        private JsonNode argumentsFor(String toolName) {
            return invocations.stream()
                    .filter(invocation -> toolName.equals(invocation.toolName()))
                    .map(ToolInvocation::arguments)
                    .findFirst()
                    .orElseThrow();
        }
    }

    private ObjectNode databaseArguments() {
        return objectMapper.createObjectNode()
                .put("connectionId", "gauss-readonly")
                .put("databaseName", "orders")
                .put("schemaName", "audit");
    }

    private record ToolInvocation(String toolName, JsonNode arguments) {
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void respondWithSession(HttpExchange exchange, int status, String sessionId, String body) throws IOException {
        exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
        respond(exchange, status, body);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange; var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
