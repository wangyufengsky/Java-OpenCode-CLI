package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

final class VerifiedMyBatisDatabaseFixture {
    private static final URI TEST_URI = URI.create("http://127.0.0.1:1");
    private VerifiedMyBatisDatabaseFixture() {
    }

    static MyBatisDatabasePreflight.Result verified(ObjectMapper objectMapper) {
        return verified(objectMapper, "GaussDB-ReadOnly", "orders", "audit", Set.of("orders", "line_items"));
    }

    static MyBatisDatabasePreflight.Result verified(
            ObjectMapper objectMapper,
            String dataSource,
            String catalog,
            String schema,
            Set<String> baseTables
    ) {
        try {
            return new MyBatisDatabasePreflight(new NativeFixtureClient(
                    objectMapper, dataSource, catalog, schema, baseTables)).verify(
                    TEST_URI, TEST_URI,
                    new MyBatisDatabasePreflight.DatabaseContract(
                            dataSource, catalog, schema, MyBatisDatabasePreflight.Environment.READ_REPLICA,
                            true, true, true,
                            new MyBatisDatabasePreflight.StatementTimeoutContract(
                                    Duration.ofSeconds(30), MyBatisDatabasePreflight.StatementTimeoutScope.ROLE, true)),
                    Path.of("/workspace/example"), "ALL");
        } catch (Exception exception) {
            throw new AssertionError("test preflight could not mint a verified database capability", exception);
        }
    }

    private static final class NativeFixtureClient extends AgentBridgeClient {
        private final ObjectMapper objectMapper;
        private final String dataSource;
        private final String catalog;
        private final String schema;
        private final Set<String> baseTables;

        private NativeFixtureClient(ObjectMapper objectMapper, String dataSource, String catalog, String schema,
                                    Set<String> baseTables) {
            super(objectMapper);
            this.objectMapper = objectMapper;
            this.dataSource = dataSource;
            this.catalog = catalog;
            this.schema = schema;
            this.baseTables = Set.copyOf(baseTables);
        }

        @Override
        public List<ToolDefinition> listTools(URI ignored) {
            return List.of(
                    definition(DatabaseMcpContract.LIST_DATASOURCES, "project", "scope"),
                    definition(DatabaseMcpContract.LIST_DATABASES, "project", "scope", "dataSource"),
                    definition(DatabaseMcpContract.LIST_TABLE_SCHEMA, "project", "scope", "dataSource", "catalog", "schema",
                            "includeColumns", "includeIndexes", "maxTables"),
                    definition(DatabaseMcpContract.EXECUTE_QUERY, "project", "scope", "dataSource", "sql", "maxRows")
            );
        }

        @Override
        public void requireMyBatisSqlReviewCapabilities(URI ignoredWeb) {
        }

        @Override
        public ToolResponse callTool(URI ignored, String name, JsonNode arguments) {
            JsonNode response = switch (name) {
                case DatabaseMcpContract.LIST_DATASOURCES -> dataSources();
                case DatabaseMcpContract.LIST_DATABASES -> catalogs();
                case DatabaseMcpContract.LIST_TABLE_SCHEMA -> tableSchema();
                case DatabaseMcpContract.EXECUTE_QUERY -> safetyProbe();
                default -> throw new IllegalArgumentException("unexpected native tool " + name);
            };
            return new ToolResponse(response, "", response);
        }

        private ToolDefinition definition(String name, String... fields) {
            ObjectNode inputSchema = objectMapper.createObjectNode().put("type", "object");
            ObjectNode properties = inputSchema.putObject("properties");
            ArrayNode required = inputSchema.putArray("required");
            for (String field : fields) {
                properties.putObject(field).put("type", switch (field) {
                    case "includeColumns", "includeIndexes" -> "boolean";
                    case "maxRows", "maxTables" -> "integer";
                    default -> "string";
                });
                required.add(field);
            }
            return new ToolDefinition(name, name, inputSchema);
        }

        private ObjectNode tableSchema() {
            ObjectNode response = objectMapper.createObjectNode().put("catalog", catalog).put("schema", schema);
            ArrayNode tables = response.putArray("tables");
            baseTables.forEach(table -> tables.addObject().put("name", table).put("type", "TABLE"));
            return response;
        }

        private ArrayNode dataSources() {
            ArrayNode result = objectMapper.createArrayNode();
            result.addObject().put("name", dataSource).put("databaseSystem", "GaussDB")
                    .put("environment", "read-replica");
            return result;
        }

        private ArrayNode catalogs() {
            ArrayNode result = objectMapper.createArrayNode();
            result.addObject().put("name", catalog);
            return result;
        }

        private ObjectNode safetyProbe() {
            ObjectNode row = objectMapper.createObjectNode()
                    .put("probeContractVersion", "mybatis-sql-review-db-safety-v2")
                    .put("currentDatabase", catalog).put("currentSchema", schema).put("currentUser", "sql_auditor")
                    .put("superuser", false).put("rolbypassrls", false).put("systemAdmin", false)
                    .put("auditAdmin", false).put("roleAdmin", false)
                    .put("roleMembershipCount", 0).put("databaseOwner", false).put("schemaOwner", false)
                    .put("ownedNonSystemSchemaCount", 0).put("ownedBaseTableCount", 0).put("databaseCreate", false)
                    .put("databaseTemporary", false).put("schemaCreate", false).put("unsafeNonSystemSchemaCreateCount", 0)
                    .put("dangerousAnyPrivilege", false).put("sessionReadOnly", true).put("transactionReadOnly", true)
                    .put("unsafeTablePrivilegeCount", 0).put("unsafeColumnPrivilegeCount", 0).put("unsafeSequencePrivilegeCount", 0)
                    .put("rlsEnabledBaseTableCount", 0).put("forceRlsEnabledBaseTableCount", 0)
                    .put("executableFunctionCount", 0).put("executablePackageCount", 0)
                    .put("unsafeForeignServerPrivilegeCount", 0).put("unsafeDirectoryPrivilegeCount", 0)
                    .put("statementTimeoutMs", 30_000)
                    .put("baseTableNames", baseTables.stream().map(String::toLowerCase).sorted()
                            .collect(java.util.stream.Collectors.joining(",")))
                    .put("baseTableCount", baseTables.size()).put("readReplica", true);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode columns = response.putArray("columns");
            row.fieldNames().forEachRemaining(columns::add);
            response.putArray("rows").add(row);
            return response;
        }
    }
}
