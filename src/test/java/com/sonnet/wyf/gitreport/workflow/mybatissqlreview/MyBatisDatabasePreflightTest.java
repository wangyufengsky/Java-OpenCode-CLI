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
        assertThat(result.environment()).isEqualTo(MyBatisDatabasePreflight.Environment.READ_REPLICA);
        assertThat(result.statementTimeout().maximum()).isEqualTo(Duration.ofSeconds(12));
        assertThat(result.statementTimeout().confirmed()).isTrue();
        assertThat(result.safeBaseRelations()).containsExactlyInAnyOrder("audit.orders", "audit.line_items");
        assertThatThrownBy(() -> result.safeBaseRelations().add("audit.injected"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(bridge.calledTools).containsExactly(
                "list_database_connections",
                "test_database_connection",
                "list_database_schemas",
                "list_schema_object_kinds",
                "list_schema_objects",
                "execute_sql_query"
        );
        assertThat(bridge.argumentsFor("list_schema_object_kinds"))
                .isEqualTo(objectMapper.createObjectNode().put("connectionId", "gauss-readonly"));
        assertThat(bridge.argumentsFor("list_schema_objects"))
                .isEqualTo(databaseArguments().put("kind", "TABLE"));
        JsonNode probeArguments = bridge.argumentsFor("execute_sql_query");
        assertThat(probeArguments.path("connectionId").asText()).isEqualTo("gauss-readonly");
        assertThat(probeArguments.path("databaseName").asText()).isEqualTo("orders");
        assertThat(probeArguments.path("schemaName").asText()).isEqualTo("audit");
        assertThat(probeArguments.path("queryText").asText())
                .contains("mybatis-sql-review-db-safety-v1")
                .contains("pg_is_in_recovery()")
                .contains("has_database_privilege")
                .contains("has_schema_privilege")
                .contains("has_any_privilege")
                .contains("has_any_column_privilege")
                .contains("has_sequence_privilege")
                .contains("role_tab_privs")
                .contains("gs_package")
                .doesNotContain("Gauss Review");
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
                        "Missing", "orders", "audit", MyBatisDatabasePreflight.Environment.READ_REPLICA, true, true, true,
                        timeoutContract()),
                "exactly one connection");

        ObjectNode duplicateConnections = baseConnections.deepCopy();
        ((ArrayNode) duplicateConnections.path("connections")).add(baseConnections.path("connections").get(0).deepCopy());
        assertRejected(duplicateConnections, fixture("schemas-orders.json"), true, contract(), "exactly one connection");

        assertRejected(baseConnections, fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "missing", MyBatisDatabasePreflight.Environment.READ_REPLICA, true, true, true,
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
    void requiresPhysicalReadReplicaAndRejectsTestSelfAttestation() throws Exception {
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "audit", MyBatisDatabasePreflight.Environment.PRODUCTION_PRIMARY,
                        true, true, true,
                        timeoutContract()),
                "physical read-replica");
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "audit", MyBatisDatabasePreflight.Environment.TEST,
                        true, true, true,
                        timeoutContract()),
                "physical read-replica");
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "audit", MyBatisDatabasePreflight.Environment.READ_REPLICA,
                        false, true, true,
                        timeoutContract()),
                "non-owner/non-admin read-only account");
    }

    @Test
    void rejectsLegacyAgentBridgeBeforeListingOrCallingDatabaseTools() throws Exception {
        FakeDatabaseBridge bridge = startBridge(
                fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS,
                fixture("schema-object-kinds-audit.json"), fixture("schema-objects-table-audit.json"),
                validSafetyProbe(), true, true, false
        );

        assertThatThrownBy(() -> new MyBatisDatabasePreflight(new AgentBridgeClient(objectMapper))
                .verify(bridge.mcpUri(), bridge.webUri(), contract()))
                .hasMessageContaining("1.199.2")
                .hasMessageContaining("incompatible");
        assertThat(bridge.calledTools).isEmpty();
    }

    @Test
    void requiresConfirmedRlsDisabledAndFunctionExecuteRevokedIncludingPublic() throws Exception {
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "audit", MyBatisDatabasePreflight.Environment.READ_REPLICA,
                        true, false, true, timeoutContract()),
                "RLS disabled");
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "audit", MyBatisDatabasePreflight.Environment.READ_REPLICA,
                        true, true, false, timeoutContract()),
                "including PUBLIC");
    }

    @Test
    void requiresConfirmedDatabaseServerOrRoleStatementTimeoutAtMostThirtySeconds() throws Exception {
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "audit", MyBatisDatabasePreflight.Environment.READ_REPLICA,
                        true, true, true,
                        new MyBatisDatabasePreflight.StatementTimeoutContract(
                                Duration.ofSeconds(31), MyBatisDatabasePreflight.StatementTimeoutScope.ROLE, true)),
                "30 seconds");
        assertRejected(fixture("connections-centralized.json"), fixture("schemas-orders.json"), true,
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review", "orders", "audit", MyBatisDatabasePreflight.Environment.READ_REPLICA,
                        true, true, true,
                        new MyBatisDatabasePreflight.StatementTimeoutContract(
                                Duration.ofSeconds(30), MyBatisDatabasePreflight.StatementTimeoutScope.DATABASE, false)),
                "confirmed");
    }

    @Test
    void rejectsYamlSelfAttestationWhenRuntimeProbeCannotProveDatabaseSafety() throws Exception {
        ObjectNode valid = validSafetyProbe();
        for (String unsafeBoolean : List.of(
                "superuser",
                "systemAdmin",
                "auditAdmin",
                "roleAdmin",
                "databaseOwner",
                "schemaOwner",
                "databaseCreate",
                "databaseTemporary",
                "schemaCreate",
                "dangerousAnyPrivilege"
        )) {
            ObjectNode unsafe = valid.deepCopy();
            probeRow(unsafe).put(unsafeBoolean, true);
            assertRejectedProbe(unsafe, unsafeBoolean);
        }
        for (String unsafeCount : List.of(
                "roleMembershipCount",
                "ownedBaseTableCount",
                "unsafeTablePrivilegeCount",
                "unsafeColumnPrivilegeCount",
                "unsafeSequencePrivilegeCount",
                "rlsEnabledBaseTableCount",
                "forceRlsEnabledBaseTableCount",
                "executableFunctionCount",
                "executablePackageCount"
        )) {
            ObjectNode unsafe = valid.deepCopy();
            probeRow(unsafe).put(unsafeCount, 1);
            assertRejectedProbe(unsafe, unsafeCount);
        }
        assertRejectedProbe(probeWith("sessionReadOnly", false), "sessionReadOnly");
        assertRejectedProbe(probeWith("transactionReadOnly", false), "transactionReadOnly");
        assertRejectedProbe(probeWith("statementTimeoutMs", 0), "statementTimeoutMs");
        assertRejectedProbe(probeWith("statementTimeoutMs", 30_001), "statementTimeoutMs");
        ObjectNode missing = valid.deepCopy();
        probeRow(missing).remove("executableFunctionCount");
        assertRejectedProbe(missing, "executableFunctionCount");
    }

    @Test
    void rejectsProbeTargetOrInventoryMismatchAndUnprovedConnectionEnvironment() throws Exception {
        assertRejectedProbe(probeWith("currentDatabase", "other"), "currentDatabase");
        assertRejectedProbe(probeWith("currentSchema", "public"), "currentSchema");
        assertRejectedProbe(probeWith("baseTableNames", "orders,substituted"), "baseTableNames");
        assertRejectedProbe(probeWith("baseTableCount", 1), "baseTableCount");

        ObjectNode connectionsWithoutEnvironmentEvidence = fixture("connections-centralized.json").deepCopy();
        FakeDatabaseBridge bridge = startBridge(
                connectionsWithoutEnvironmentEvidence,
                fixture("schemas-orders.json"),
                true,
                MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS,
                fixture("schema-object-kinds-audit.json"),
                fixture("schema-objects-table-audit.json"),
                validSafetyProbe(),
                false,
                true
        );
        assertThatThrownBy(() -> new MyBatisDatabasePreflight(new AgentBridgeClient(objectMapper))
                .verify(bridge.mcpUri(), bridge.webUri(), contract()))
                .hasMessageContaining("environment metadata");
        stopServer();

        ObjectNode untrustedTopology = withEnvironmentEvidence(fixture("connections-centralized.json"));
        ((ObjectNode) untrustedTopology.at("/connections/0")).put("topologySource", "yaml-self-attested");
        FakeDatabaseBridge topologyBridge = startBridge(
                untrustedTopology, fixture("schemas-orders.json"), true,
                MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS,
                fixture("schema-object-kinds-audit.json"), fixture("schema-objects-table-audit.json"),
                validSafetyProbe(), false, true
        );
        assertThatThrownBy(() -> new MyBatisDatabasePreflight(new AgentBridgeClient(objectMapper))
                .verify(topologyBridge.mcpUri(), topologyBridge.webUri(), contract()))
                .hasMessageContaining("server-observed physical read-replica");
    }

    @Test
    void requiresDatabaseProbeToConfirmReadReplicaWhenMetadataSelectsReadReplica() throws Exception {
        ObjectNode connections = fixture("connections-centralized.json").deepCopy();
        ((ObjectNode) connections.at("/connections/0"))
                .put("environment", "read-replica")
                .put("environmentSource", "managed-connection-metadata")
                .put("topologyRole", "physical-standby")
                .put("topologySource", "server-observed")
                .put("readOnly", true);
        FakeDatabaseBridge bridge = startBridge(
                connections,
                fixture("schemas-orders.json"),
                true,
                MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS,
                fixture("schema-object-kinds-audit.json"),
                fixture("schema-objects-table-audit.json"),
                probeWith("readReplica", false),
                false,
                true
        );
        MyBatisDatabasePreflight.DatabaseContract replicaContract =
                new MyBatisDatabasePreflight.DatabaseContract(
                        "Gauss Review",
                        "orders",
                        "audit",
                        MyBatisDatabasePreflight.Environment.READ_REPLICA,
                        true,
                        true,
                        true,
                        timeoutContract()
                );

        assertThatThrownBy(() -> new MyBatisDatabasePreflight(new AgentBridgeClient(objectMapper))
                .verify(bridge.mcpUri(), bridge.webUri(), replicaContract))
                .hasMessageContaining("read-replica");
    }

    @Test
    void rejectsLegacyOptionalPreviewLimitSchemaBeforeIssuingSafetyProbe() throws Exception {
        FakeDatabaseBridge bridge = startBridge(
                fixture("connections-centralized.json"),
                fixture("schemas-orders.json"),
                true,
                MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS,
                fixture("schema-object-kinds-audit.json"),
                fixture("schema-objects-table-audit.json"),
                validSafetyProbe(),
                true,
                false
        );

        assertThatThrownBy(() -> new MyBatisDatabasePreflight(new AgentBridgeClient(objectMapper))
                .verify(bridge.mcpUri(), bridge.webUri(), contract()))
                .hasMessageContaining("input schema")
                .hasMessageContaining("preview_table_data")
                .hasMessageContaining("maxRowCount");
        assertThat(bridge.calledTools).doesNotContain("execute_sql_query");
    }

    private void assertRejectedProbe(ObjectNode probe, String message) throws Exception {
        FakeDatabaseBridge bridge = startBridge(
                fixture("connections-centralized.json"),
                fixture("schemas-orders.json"),
                true,
                MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS,
                fixture("schema-object-kinds-audit.json"),
                fixture("schema-objects-table-audit.json"),
                probe,
                true,
                true
        );
        assertThatThrownBy(() -> new MyBatisDatabasePreflight(new AgentBridgeClient(objectMapper))
                .verify(bridge.mcpUri(), bridge.webUri(), contract()))
                .hasMessageContaining(message);
        stopServer();
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
                MyBatisDatabasePreflight.Environment.READ_REPLICA,
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
                fixture("schema-objects-table-audit.json"),
                validSafetyProbe(),
                true,
                true
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
        return startBridge(
                connections,
                schemas,
                connectionAvailable,
                tools,
                objectKinds,
                schemaObjects,
                validSafetyProbe(),
                true,
                true
        );
    }

    private FakeDatabaseBridge startBridge(
            JsonNode connections,
            JsonNode schemas,
            boolean connectionAvailable,
            Set<String> tools,
            JsonNode objectKinds,
            JsonNode schemaObjects,
            JsonNode safetyProbe,
            boolean addEnvironmentEvidence,
            boolean strictToolSchemas
    ) throws IOException {
        return startBridge(
                connections, schemas, connectionAvailable, tools, objectKinds, schemaObjects,
                safetyProbe, addEnvironmentEvidence, strictToolSchemas, true
        );
    }

    private FakeDatabaseBridge startBridge(
            JsonNode connections,
            JsonNode schemas,
            boolean connectionAvailable,
            Set<String> tools,
            JsonNode objectKinds,
            JsonNode schemaObjects,
            JsonNode safetyProbe,
            boolean addEnvironmentEvidence,
            boolean strictToolSchemas,
            boolean strictAgentBridgeProtocol
    ) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeDatabaseBridge bridge = new FakeDatabaseBridge(
                server,
                addEnvironmentEvidence ? withEnvironmentEvidence(connections) : connections,
                schemas,
                connectionAvailable,
                tools,
                objectKinds,
                schemaObjects,
                safetyProbe,
                strictToolSchemas,
                strictAgentBridgeProtocol
        );
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
        private final JsonNode safetyProbe;
        private final boolean strictToolSchemas;
        private final boolean strictAgentBridgeProtocol;
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
                JsonNode schemaObjects,
                JsonNode safetyProbe,
                boolean strictToolSchemas,
                boolean strictAgentBridgeProtocol
        ) {
            this.httpServer = httpServer;
            this.connections = connections;
            this.schemas = schemas;
            this.connectionAvailable = connectionAvailable;
            this.tools = tools;
            this.objectKinds = objectKinds;
            this.schemaObjects = schemaObjects;
            this.safetyProbe = safetyProbe;
            this.strictToolSchemas = strictToolSchemas;
            this.strictAgentBridgeProtocol = strictAgentBridgeProtocol;
        }

        private void install() {
            httpServer.createContext("/info", exchange -> respond(exchange, 200,
                    strictAgentBridgeProtocol ? strictAuditInfo() : """
                            {"version":"1.199.2","capabilities":{}}
                            """));
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
                            case "execute_sql_query" -> safetyProbe;
                            default -> objectMapper.createObjectNode();
                        };
                        respondMcp(exchange, result);
                    }
                    default -> respond(exchange, 400, "unknown MCP method");
                }
            });
            httpServer.createContext("/tool-calls", exchange -> {
                toolCallHistoryRequested = true;
                respond(exchange, 200,
                        "{\"complete\":true,\"snapshotToken\":\"preflight-snapshot\",\"total\":0,\"items\":[]}");
            });
        }

        private JsonNode toolsResponse() {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode array = root.putArray("tools");
            tools.forEach(name -> array.addObject()
                    .put("name", name)
                    .put("description", name)
                    .set("inputSchema", toolSchema(name)));
            return root;
        }

        private JsonNode toolSchema(String name) {
            if (!"execute_sql_query".equals(name) && !"preview_table_data".equals(name)) {
                return objectMapper.createObjectNode().put("type", "object");
            }
            ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            for (String field : List.of("connectionId", "databaseName", "schemaName")) {
                properties.putObject(field).put("type", "string");
            }
            if ("execute_sql_query".equals(name)) {
                properties.putObject("queryText").put("type", "string");
                schema.putArray("required")
                        .add("connectionId").add("databaseName").add("schemaName").add("queryText");
            } else {
                properties.putObject("tableName").put("type", "string");
                properties.putObject("maxRowCount").put("type", "integer")
                        .put("minimum", 1).put("maximum", strictToolSchemas ? 20 : 200);
                schema.putArray("required")
                        .add("connectionId").add("databaseName").add("schemaName")
                        .add("tableName");
                if (strictToolSchemas) {
                    ((ArrayNode) schema.path("required")).add("maxRowCount");
                }
            }
            return schema;
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

    private ObjectNode withEnvironmentEvidence(JsonNode source) {
        ObjectNode copy = source.deepCopy();
        ((ObjectNode) copy.at("/connections/0"))
                .put("environment", "read-replica")
                .put("environmentSource", "managed-connection-metadata")
                .put("topologyRole", "physical-standby")
                .put("topologySource", "server-observed")
                .put("readOnly", true);
        return copy;
    }

    private String strictAuditInfo() {
        return """
                {
                  "version":"1.200.0",
                  "capabilities":{
                    "mybatisSqlReviewAudit":{
                      "contractVersion":1,
                      "untruncatedStructuredToolArguments":true,
                      "untruncatedStructuredToolResults":true,
                      "immutableToolCallSnapshot":true,
                      "stableToolCallTotal":true,
                      "explicitToolCallHistoryComplete":true,
                      "serverEnforcedPreviewMaxRows":20,
                      "previewMaxRowsRequired":true
                    }
                  }
                }
                """;
    }

    private ObjectNode validSafetyProbe() {
        ObjectNode row = objectMapper.createObjectNode()
                .put("probeContractVersion", "mybatis-sql-review-db-safety-v1")
                .put("currentDatabase", "orders")
                .put("currentSchema", "audit")
                .put("currentUser", "sql_auditor")
                .put("superuser", false)
                .put("systemAdmin", false)
                .put("auditAdmin", false)
                .put("roleAdmin", false)
                .put("roleMembershipCount", 0)
                .put("databaseOwner", false)
                .put("schemaOwner", false)
                .put("ownedBaseTableCount", 0)
                .put("databaseCreate", false)
                .put("databaseTemporary", false)
                .put("schemaCreate", false)
                .put("dangerousAnyPrivilege", false)
                .put("sessionReadOnly", true)
                .put("transactionReadOnly", true)
                .put("unsafeTablePrivilegeCount", 0)
                .put("unsafeColumnPrivilegeCount", 0)
                .put("unsafeSequencePrivilegeCount", 0)
                .put("rlsEnabledBaseTableCount", 0)
                .put("forceRlsEnabledBaseTableCount", 0)
                .put("executableFunctionCount", 0)
                .put("executablePackageCount", 0)
                .put("statementTimeoutMs", 12_000)
                .put("baseTableNames", "line_items,orders")
                .put("baseTableCount", 2)
                .put("readReplica", true);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode columns = result.putArray("columns");
        row.fieldNames().forEachRemaining(columns::add);
        result.putArray("rows").add(row);
        return result;
    }

    private ObjectNode probeWith(String field, boolean value) {
        ObjectNode result = validSafetyProbe();
        probeRow(result).put(field, value);
        return result;
    }

    private ObjectNode probeWith(String field, long value) {
        ObjectNode result = validSafetyProbe();
        probeRow(result).put(field, value);
        return result;
    }

    private ObjectNode probeWith(String field, String value) {
        ObjectNode result = validSafetyProbe();
        probeRow(result).put(field, value);
        return result;
    }

    private ObjectNode probeRow(ObjectNode result) {
        return (ObjectNode) result.at("/rows/0");
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
