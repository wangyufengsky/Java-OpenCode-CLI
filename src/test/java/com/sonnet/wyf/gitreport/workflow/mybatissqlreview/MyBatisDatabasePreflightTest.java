package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisDatabasePreflightTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesOnlyTheVerifiedNativeBindingAndNeverSynthesizesBridgeOrFingerprintFacts() {
        assertThat(MyBatisDatabasePreflight.Result.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("bridgeBinding", "databaseFingerprints", "connectionId", "databaseName", "schemaName");
        assertThat(MyBatisDatabasePreflight.class.getDeclaredClasses())
                .extracting(Class::getSimpleName)
                .doesNotContain("DatabaseFingerprints");
    }

    @Test
    void verifiesConfiguredDataSourceAndUsesNativeQueryArguments() throws Exception {
        NativeDatabaseBridge bridge = new NativeDatabaseBridge(objectMapper);
        bridge.installNativeDatabaseMcpTools();
        MyBatisDatabasePreflight preflight = new MyBatisDatabasePreflight(bridge);

        MyBatisDatabasePreflight.Result result = preflight.verify(
                bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "ALL"
        );

        assertThat(result.binding().dataSource()).isEqualTo("GaussDB-ReadOnly");
        assertThat(bridge.calledTools()).containsExactly(
                DatabaseMcpContract.LIST_DATASOURCES,
                DatabaseMcpContract.LIST_DATABASES,
                DatabaseMcpContract.LIST_TABLE_SCHEMA,
                DatabaseMcpContract.EXECUTE_QUERY
        );
        assertThat(bridge.argumentsFor(DatabaseMcpContract.EXECUTE_QUERY).path("maxRows").intValue())
                .isEqualTo(DatabaseMcpContract.MAX_ROWS);
        assertThat(bridge.argumentsFor(DatabaseMcpContract.EXECUTE_QUERY).path("project").asText())
                .isEqualTo(Path.of("/workspace/example").toAbsolutePath().normalize().toString());
        assertThat(bridge.argumentsFor(DatabaseMcpContract.EXECUTE_QUERY).path("scope").asText())
                .isEqualTo("ALL");
    }

    @Test
    void rejectsDatabaseMcpCatalogWithoutTheNativeTools() {
        NativeDatabaseBridge bridge = new NativeDatabaseBridge(objectMapper);
        bridge.installTools(DatabaseMcpContract.LIST_DATASOURCES);
        MyBatisDatabasePreflight preflight = new MyBatisDatabasePreflight(bridge);

        assertThatThrownBy(() -> preflight.verify(
                bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "ALL"))
                .hasMessageContaining("Database MCP tools unavailable");
    }

    @Test
    void rejectsNativeToolSchemasThatDoNotRequireTheBoundQueryArguments() {
        NativeDatabaseBridge bridge = new NativeDatabaseBridge(objectMapper);
        bridge.installNativeDatabaseMcpTools();
        bridge.removeRequiredField(DatabaseMcpContract.EXECUTE_QUERY, "maxRows");

        assertThatThrownBy(() -> new MyBatisDatabasePreflight(bridge).verify(
                bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "ALL"))
                .hasMessageContaining("input schema")
                .hasMessageContaining("maxRows");
    }

    @Test
    void recheckResolvesTheVerifiedDataSourceAndRepeatsTheSafetyProbe() throws Exception {
        NativeDatabaseBridge bridge = new NativeDatabaseBridge(objectMapper);
        bridge.installNativeDatabaseMcpTools();
        MyBatisDatabasePreflight preflight = new MyBatisDatabasePreflight(bridge);
        MyBatisDatabasePreflight.Result result = preflight.verify(
                bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "PROJECT"
        );
        bridge.clearCalls();

        preflight.recheck(bridge.mcpUri(), bridge.webUri(), result);

        assertThat(bridge.calledTools()).containsExactly(
                DatabaseMcpContract.LIST_DATASOURCES,
                DatabaseMcpContract.EXECUTE_QUERY
        );
        assertThat(bridge.argumentsFor(DatabaseMcpContract.EXECUTE_QUERY).path("dataSource").asText())
                .isEqualTo(result.binding().dataSource());
        assertThat(bridge.compatibilityChecks()).isEqualTo(2);
    }

    @Test
    void rejectsUnsupportedAgentBridgeBeforeListingToolsInVerifyAndRecheck() throws Exception {
        NativeDatabaseBridge bridge = new NativeDatabaseBridge(objectMapper);
        bridge.installNativeDatabaseMcpTools();
        bridge.setCompatible(false);
        MyBatisDatabasePreflight preflight = new MyBatisDatabasePreflight(bridge);

        assertThatThrownBy(() -> preflight.verify(
                bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "ALL"))
                .hasMessageContaining("requires AgentBridge >= 1.202.0");
        assertThat(bridge.calledTools()).isEmpty();

        bridge.setCompatible(true);
        MyBatisDatabasePreflight.Result verified = preflight.verify(
                bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "ALL");
        bridge.clearCalls();
        bridge.setCompatible(false);

        assertThatThrownBy(() -> preflight.recheck(bridge.mcpUri(), bridge.webUri(), verified))
                .hasMessageContaining("requires AgentBridge >= 1.202.0");
        assertThat(bridge.calledTools()).isEmpty();
    }

    @Test
    void rejectsDuplicateAndMissingNativeDataSources() {
        NativeDatabaseBridge bridge = nativeBridge();
        bridge.dataSources().addObject().put("name", "GaussDB-ReadOnly").put("databaseSystem", "GaussDB")
                .put("environment", "read-replica");
        assertRejected(bridge, contract(), "exactly one data source");

        bridge = nativeBridge();
        bridge.dataSources().removeAll();
        assertRejected(bridge, contract(), "exactly one data source");
    }

    @Test
    void rejectsCatalogSchemaAndTableInventoryMismatch() {
        NativeDatabaseBridge bridge = nativeBridge();
        bridge.catalogs().removeAll();
        assertRejected(bridge, contract(), "catalog");

        bridge = nativeBridge();
        bridge.tableSchema().put("schema", "public");
        assertRejected(bridge, contract(), "catalog/schema");

        bridge = nativeBridge();
        ((ObjectNode) bridge.tableSchema().withArray("tables").get(0)).put("type", "VIEW");
        assertRejected(bridge, contract(), "type TABLE");

        bridge = nativeBridge();
        bridge.tableSchema().withArray("tables").addObject().put("name", "orders").put("type", "TABLE");
        assertRejected(bridge, contract(), "invalid or duplicate");
    }

    @Test
    void rejectsUnsafePrivilegesRlsAndFunctionExecution() {
        for (String field : List.of("superuser", "systemAdmin", "auditAdmin", "roleAdmin", "databaseOwner",
                "schemaOwner", "databaseCreate", "databaseTemporary", "schemaCreate", "dangerousAnyPrivilege",
                "rolbypassrls")) {
            NativeDatabaseBridge bridge = nativeBridge();
            bridge.probeRow().put(field, true);
            assertRejected(bridge, contract(), field);
        }
        for (String field : List.of("roleMembershipCount", "ownedNonSystemSchemaCount", "ownedBaseTableCount",
                "unsafeNonSystemSchemaCreateCount", "unsafeTablePrivilegeCount", "unsafeColumnPrivilegeCount",
                "unsafeSequencePrivilegeCount", "rlsEnabledBaseTableCount", "forceRlsEnabledBaseTableCount",
                "executableFunctionCount", "executablePackageCount", "unsafeForeignServerPrivilegeCount",
                "unsafeDirectoryPrivilegeCount")) {
            NativeDatabaseBridge bridge = nativeBridge();
            bridge.probeRow().put(field, 1);
            assertRejected(bridge, contract(), field);
        }
    }

    @Test
    void rejectsNonPhysicalReadOnlyTargetAndInvalidTimeoutContracts() {
        NativeDatabaseBridge bridge = nativeBridge();
        ((ObjectNode) bridge.dataSources().get(0)).put("environment", "production-primary");
        assertRejected(bridge, contract(), "read-replica");

        bridge = nativeBridge();
        bridge.probeRow().put("readReplica", false);
        assertRejected(bridge, contract(), "read-replica");

        bridge = nativeBridge();
        bridge.probeRow().put("statementTimeoutMs", 30_001);
        assertRejected(bridge, contract(), "statementTimeoutMs");

        assertRejected(nativeBridge(), new MyBatisDatabasePreflight.DatabaseContract(
                "GaussDB-ReadOnly", "orders", "audit", MyBatisDatabasePreflight.Environment.READ_REPLICA,
                true, true, true, new MyBatisDatabasePreflight.StatementTimeoutContract(
                        Duration.ofSeconds(30), MyBatisDatabasePreflight.StatementTimeoutScope.EFFECTIVE_SESSION, true)),
                "statement_timeout scope");
    }

    @Test
    void acceptsArrayAndPositionalColumnsRowsSafetyProbeResults() throws Exception {
        NativeDatabaseBridge bridge = nativeBridge();
        ArrayNode array = objectMapper.createArrayNode().add(bridge.probeRow().deepCopy());
        bridge.setSafetyProbe(array);
        new MyBatisDatabasePreflight(bridge).verify(
                bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "ALL");

        bridge = nativeBridge();
        ObjectNode positional = bridge.safetyProbe().deepCopy();
        ArrayNode values = positional.putArray("rows").addArray();
        ObjectNode row = bridge.probeRow();
        positional.withArray("columns").forEach(column -> values.add(row.path(column.asText())));
        bridge.setSafetyProbe(positional);
        new MyBatisDatabasePreflight(bridge).verify(
                bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "ALL");
    }

    @Test
    void resultIsImmutableAndHasNoPublicConstructor() throws Exception {
        NativeDatabaseBridge bridge = nativeBridge();
        MyBatisDatabasePreflight.Result result = new MyBatisDatabasePreflight(bridge).verify(
                bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "ALL");

        assertThatThrownBy(() -> result.safeBaseRelations().add("audit.other"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(MyBatisDatabasePreflight.Result.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
    }

    private NativeDatabaseBridge nativeBridge() {
        NativeDatabaseBridge bridge = new NativeDatabaseBridge(objectMapper);
        bridge.installNativeDatabaseMcpTools();
        return bridge;
    }

    private void assertRejected(NativeDatabaseBridge bridge, MyBatisDatabasePreflight.DatabaseContract contract,
                                String message) {
        assertThatThrownBy(() -> new MyBatisDatabasePreflight(bridge).verify(
                bridge.mcpUri(), bridge.webUri(), contract, Path.of("/workspace/example"), "ALL"))
                .hasMessageContaining(message);
    }

    private MyBatisDatabasePreflight.DatabaseContract contract() {
        return new MyBatisDatabasePreflight.DatabaseContract(
                "GaussDB-ReadOnly", "orders", "audit",
                MyBatisDatabasePreflight.Environment.READ_REPLICA,
                true, true, true,
                new MyBatisDatabasePreflight.StatementTimeoutContract(
                        Duration.ofSeconds(30), MyBatisDatabasePreflight.StatementTimeoutScope.ROLE, true
                )
        );
    }

    private static final class NativeDatabaseBridge extends AgentBridgeClient {
        private final ObjectMapper objectMapper;
        private final List<String> tools = new ArrayList<>();
        private final List<String> calledTools = new ArrayList<>();
        private final Map<String, JsonNode> arguments = new LinkedHashMap<>();
        private final Map<String, ObjectNode> schemas = new LinkedHashMap<>();
        private ArrayNode dataSources;
        private ArrayNode catalogs;
        private ObjectNode tableSchema;
        private JsonNode safetyProbe;
        private boolean compatible = true;
        private int compatibilityChecks;

        private NativeDatabaseBridge(ObjectMapper objectMapper) {
            super(objectMapper);
            this.objectMapper = objectMapper;
            dataSources = createDataSources();
            catalogs = createCatalogs();
            tableSchema = createTableSchema();
            safetyProbe = createSafetyProbe();
        }

        void installNativeDatabaseMcpTools() {
            installTools(
                    DatabaseMcpContract.LIST_DATASOURCES,
                    DatabaseMcpContract.LIST_DATABASES,
                    DatabaseMcpContract.LIST_TABLE_SCHEMA,
                    DatabaseMcpContract.EXECUTE_QUERY
            );
        }

        void installTools(String... names) {
            tools.clear();
            schemas.clear();
            for (String name : names) {
                tools.add(name);
                schemas.put(name, schema(name));
            }
        }

        void removeRequiredField(String toolName, String field) {
            ArrayNode required = (ArrayNode) schemas.get(toolName).path("required");
            for (int index = 0; index < required.size(); index++) {
                if (field.equals(required.get(index).asText())) {
                    required.remove(index);
                    return;
                }
            }
        }

        @Override
        public List<ToolDefinition> listTools(URI ignored) {
            return tools.stream().map(name -> new ToolDefinition(name, name, schemas.get(name))).toList();
        }

        @Override
        public void requireMyBatisSqlReviewCapabilities(URI ignoredWeb) {
            compatibilityChecks++;
            if (!compatible) {
                throw new IllegalStateException("requires AgentBridge >= 1.202.0");
            }
        }

        @Override
        public ToolResponse callTool(URI ignored, String name, JsonNode requestArguments) {
            calledTools.add(name);
            arguments.put(name, requestArguments.deepCopy());
            JsonNode response = switch (name) {
                case DatabaseMcpContract.LIST_DATASOURCES -> dataSources;
                case DatabaseMcpContract.LIST_DATABASES -> catalogs;
                case DatabaseMcpContract.LIST_TABLE_SCHEMA -> tableSchema;
                case DatabaseMcpContract.EXECUTE_QUERY -> safetyProbe;
                default -> throw new IllegalArgumentException("unexpected tool " + name);
            };
            return new ToolResponse(response, "", response);
        }

        List<String> calledTools() {
            return List.copyOf(calledTools);
        }

        JsonNode argumentsFor(String toolName) {
            return arguments.get(toolName);
        }

        void clearCalls() {
            calledTools.clear();
            arguments.clear();
        }

        void setCompatible(boolean compatible) { this.compatible = compatible; }
        int compatibilityChecks() { return compatibilityChecks; }
        ArrayNode dataSources() { return dataSources; }
        ArrayNode catalogs() { return catalogs; }
        ObjectNode tableSchema() { return tableSchema; }
        ObjectNode safetyProbe() { return (ObjectNode) safetyProbe; }
        ObjectNode probeRow() { return (ObjectNode) safetyProbe().at("/rows/0"); }
        void setSafetyProbe(JsonNode safetyProbe) { this.safetyProbe = safetyProbe; }

        URI mcpUri() {
            return URI.create("http://127.0.0.1:1/mcp");
        }

        URI webUri() {
            return URI.create("http://127.0.0.1:1");
        }

        private ObjectNode schema(String toolName) {
            ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");
            for (String field : List.of("project", "scope")) {
                properties.putObject(field).put("type", "string");
                required.add(field);
            }
            if (!DatabaseMcpContract.LIST_DATASOURCES.equals(toolName)) {
                properties.putObject("dataSource").put("type", "string");
                required.add("dataSource");
            }
            if (DatabaseMcpContract.LIST_TABLE_SCHEMA.equals(toolName)) {
                for (String field : List.of("catalog", "schema")) {
                    properties.putObject(field).put("type", "string");
                    required.add(field);
                }
                properties.putObject("includeColumns").put("type", "boolean");
                properties.putObject("includeIndexes").put("type", "boolean");
                properties.putObject("maxTables").put("type", "integer");
                required.add("includeColumns").add("includeIndexes").add("maxTables");
            }
            if (DatabaseMcpContract.EXECUTE_QUERY.equals(toolName)) {
                properties.putObject("sql").put("type", "string");
                properties.putObject("maxRows").put("type", "integer");
                required.add("sql").add("maxRows");
            }
            return schema;
        }

        private ObjectNode createTableSchema() {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("catalog", "orders").put("schema", "audit");
            ArrayNode tables = result.putArray("tables");
            tables.addObject().put("name", "orders").put("type", "TABLE");
            tables.addObject().put("name", "line_items").put("type", "TABLE");
            return result;
        }

        private ArrayNode createDataSources() {
            ArrayNode result = objectMapper.createArrayNode();
            result.addObject().put("name", "GaussDB-ReadOnly").put("databaseSystem", "GaussDB")
                    .put("environment", "read-replica");
            return result;
        }

        private ArrayNode createCatalogs() {
            ArrayNode result = objectMapper.createArrayNode();
            result.addObject().put("name", "orders");
            return result;
        }

        private ObjectNode createSafetyProbe() {
            ObjectNode row = objectMapper.createObjectNode()
                    .put("probeContractVersion", "mybatis-sql-review-db-safety-v2")
                    .put("currentDatabase", "orders").put("currentSchema", "audit")
                    .put("currentUser", "sql_auditor")
                    .put("superuser", false).put("rolbypassrls", false).put("systemAdmin", false).put("auditAdmin", false)
                    .put("roleAdmin", false).put("roleMembershipCount", 0)
                    .put("databaseOwner", false).put("schemaOwner", false)
                    .put("ownedNonSystemSchemaCount", 0).put("ownedBaseTableCount", 0)
                    .put("databaseCreate", false).put("databaseTemporary", false).put("schemaCreate", false)
                    .put("unsafeNonSystemSchemaCreateCount", 0).put("dangerousAnyPrivilege", false)
                    .put("sessionReadOnly", true).put("transactionReadOnly", true)
                    .put("unsafeTablePrivilegeCount", 0).put("unsafeColumnPrivilegeCount", 0)
                    .put("unsafeSequencePrivilegeCount", 0).put("rlsEnabledBaseTableCount", 0)
                    .put("forceRlsEnabledBaseTableCount", 0).put("executableFunctionCount", 0)
                    .put("executablePackageCount", 0).put("unsafeForeignServerPrivilegeCount", 0)
                    .put("unsafeDirectoryPrivilegeCount", 0).put("statementTimeoutMs", 12_000)
                    .put("baseTableNames", "line_items,orders").put("baseTableCount", 2)
                    .put("readReplica", true);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode columns = result.putArray("columns");
            row.fieldNames().forEachRemaining(columns::add);
            result.putArray("rows").add(row);
            return result;
        }
    }
}
