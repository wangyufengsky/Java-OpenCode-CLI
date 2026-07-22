package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

final class VerifiedMyBatisDatabaseFixture {
    private static final URI TEST_URI = URI.create("http://127.0.0.1:1");
    static final AgentBridgeClient.BridgeIdentity BRIDGE_IDENTITY =
            new AgentBridgeClient.BridgeIdentity(
                    "instance-a", "project-a", "nonce-a-0123456789");
    static final String POLICY_FINGERPRINT = "sha256:" + "a".repeat(64);
    static final MyBatisDatabasePreflight.DatabaseFingerprints DATABASE_FINGERPRINTS =
            new MyBatisDatabasePreflight.DatabaseFingerprints(
                    "sha256:" + "b".repeat(64),
                    "sha256:" + "c".repeat(64),
                    "sha256:" + "d".repeat(64));

    private VerifiedMyBatisDatabaseFixture() {
    }

    static MyBatisDatabasePreflight.Result verified(ObjectMapper objectMapper) {
        return verified(
                objectMapper,
                "gauss-readonly",
                "Gauss Review",
                "orders",
                "audit",
                Set.of("orders", "line_items")
        );
    }

    static MyBatisDatabasePreflight.Result verified(
            ObjectMapper objectMapper,
            String connectionId,
            String connectionName,
            String databaseName,
            String schemaName,
            Set<String> baseTables
    ) {
        try {
            AgentBridgeClient client = new VerifiedPreflightClient(
                    objectMapper,
                    connectionId,
                    connectionName,
                    databaseName,
                    schemaName,
                    baseTables
            );
            return new MyBatisDatabasePreflight(client).verify(
                    TEST_URI,
                    TEST_URI,
                    new MyBatisDatabasePreflight.DatabaseContract(
                            connectionName,
                            databaseName,
                            schemaName,
                            MyBatisDatabasePreflight.Environment.READ_REPLICA,
                            true,
                            true,
                            true,
                            new MyBatisDatabasePreflight.StatementTimeoutContract(
                                    Duration.ofSeconds(30),
                                    MyBatisDatabasePreflight.StatementTimeoutScope.ROLE,
                                    true
                            )
                    )
            );
        } catch (Exception exception) {
            throw new AssertionError("test preflight could not mint a verified database capability", exception);
        }
    }

    private static final class VerifiedPreflightClient extends AgentBridgeClient {
        private final ObjectMapper objectMapper;
        private final String connectionId;
        private final String connectionName;
        private final String databaseName;
        private final String schemaName;
        private final Set<String> baseTables;

        private VerifiedPreflightClient(
                ObjectMapper objectMapper,
                String connectionId,
                String connectionName,
                String databaseName,
                String schemaName,
                Set<String> baseTables
        ) {
            super(objectMapper);
            this.objectMapper = objectMapper;
            this.connectionId = connectionId;
            this.connectionName = connectionName;
            this.databaseName = databaseName;
            this.schemaName = schemaName;
            this.baseTables = Set.copyOf(baseTables);
        }

        @Override
        public MyBatisAuditBinding bindMyBatisSqlReviewEndpoints(URI ignoredWeb, URI ignoredMcp) {
            return new MyBatisAuditBinding(BRIDGE_IDENTITY, POLICY_FINGERPRINT);
        }

        @Override
        public List<ToolDefinition> listTools(URI ignored) {
            return MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS.stream()
                    .map(name -> new ToolDefinition(
                            name,
                            name,
                            toolSchema(name)
                    ))
                    .toList();
        }

        @Override
        public ToolResponse callTool(URI ignored, String name, JsonNode arguments) {
            JsonNode structured = switch (name) {
                case "list_database_connections" -> connections();
                case "test_database_connection" -> objectMapper.createObjectNode().put("success", true);
                case "list_database_schemas" -> schemas();
                case "list_schema_object_kinds" -> objectKinds();
                case "list_schema_objects" -> tableObjects();
                case "execute_sql_query" -> safetyProbe();
                default -> objectMapper.createObjectNode();
            };
            return new ToolResponse(structured, "", structured);
        }

        @Override
        public List<ToolCallRecord> getToolCalls(URI ignored) {
            return List.of();
        }

        private ObjectNode connections() {
            ObjectNode response = objectMapper.createObjectNode();
            ObjectNode connection = response.putArray("connections").addObject()
                    .put("id", connectionId)
                    .put("name", connectionName)
                    .put("databaseSystem", "GaussDB")
                    .put("deployment", "centralized")
                    .put("environment", "read-replica")
                    .put("environmentSource", "managed-connection-metadata")
                    .put("topologyRole", "physical-standby")
                    .put("topologySource", "server-observed")
                    .put("readOnly", true);
            addDatabaseBinding(connection);
            connection.putArray("databases").add(databaseName);
            return response;
        }

        private ObjectNode schemas() {
            ObjectNode response = objectMapper.createObjectNode().put("databaseName", databaseName);
            response.putArray("schemas").add(schemaName);
            return response;
        }

        private ObjectNode objectKinds() {
            ObjectNode response = boundResponse();
            response.putArray("objectKinds").addObject().put("code", "TABLE").put("name", "Table");
            return response;
        }

        private ObjectNode tableObjects() {
            ObjectNode response = boundResponse().put("kind", "TABLE");
            ArrayNode objects = response.putArray("objects");
            baseTables.forEach(table -> objects.addObject().put("name", table).put("kind", "TABLE"));
            return response;
        }

        private ObjectNode boundResponse() {
            return objectMapper.createObjectNode()
                    .put("connectionId", connectionId)
                    .put("databaseName", databaseName)
                    .put("schemaName", schemaName);
        }

        private JsonNode toolSchema(String name) {
            if (!"execute_sql_query".equals(name) && !"preview_table_data".equals(name)) {
                return objectMapper.createObjectNode().put("type", "object");
            }
            ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
            schema.put("x-agentbridge-policyFingerprint", POLICY_FINGERPRINT);
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
                        .put("minimum", 1).put("maximum", 20);
                schema.putArray("required")
                        .add("connectionId").add("databaseName").add("schemaName")
                        .add("tableName").add("maxRowCount");
            }
            return schema;
        }

        private ObjectNode safetyProbe() {
            ObjectNode row = objectMapper.createObjectNode()
                    .put("probeContractVersion", "mybatis-sql-review-db-safety-v2")
                    .put("currentDatabase", databaseName)
                    .put("currentSchema", schemaName)
                    .put("currentUser", "sql_auditor")
                    .put("superuser", false)
                    .put("systemAdmin", false)
                    .put("auditAdmin", false)
                    .put("roleAdmin", false)
                    .put("roleMembershipCount", 0)
                    .put("databaseOwner", false)
                    .put("schemaOwner", false)
                    .put("ownedNonSystemSchemaCount", 0)
                    .put("ownedBaseTableCount", 0)
                    .put("databaseCreate", false)
                    .put("databaseTemporary", false)
                    .put("schemaCreate", false)
                    .put("unsafeNonSystemSchemaCreateCount", 0)
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
                    .put("unsafeForeignServerPrivilegeCount", 0)
                    .put("unsafeDirectoryPrivilegeCount", 0)
                    .put("statementTimeoutMs", 30_000)
                    .put("baseTableNames", baseTables.stream().map(String::toLowerCase).sorted()
                            .collect(java.util.stream.Collectors.joining(",")))
                    .put("baseTableCount", baseTables.size())
                    .put("readReplica", true);
            ObjectNode result = objectMapper.createObjectNode();
            addDatabaseBinding(result);
            ArrayNode columns = result.putArray("columns");
            row.fieldNames().forEachRemaining(columns::add);
            result.putArray("rows").add(row);
            return result;
        }

        private void addDatabaseBinding(ObjectNode node) {
            node.putObject("identity")
                    .put("instanceId", BRIDGE_IDENTITY.instanceId())
                    .put("projectId", BRIDGE_IDENTITY.projectId())
                    .put("instanceNonce", BRIDGE_IDENTITY.instanceNonce());
            node.put("policyFingerprint", POLICY_FINGERPRINT)
                    .put("fingerprintSource", "server-generated")
                    .put("databaseHostFingerprint", DATABASE_FINGERPRINTS.hostFingerprint())
                    .put("databaseInstanceFingerprint", DATABASE_FINGERPRINTS.instanceFingerprint())
                    .put("topologyFingerprint", DATABASE_FINGERPRINTS.topologyFingerprint());
        }
    }
}
