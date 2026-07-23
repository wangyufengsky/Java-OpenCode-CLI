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
                .doesNotContain("bridgeBinding", "databaseFingerprints", join("connection", "Id"),
                        join("database", "Name"), join("schema", "Name"));
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
    void acceptsLiveOptionalSchemaButRejectsMissingPassedPropertiesAndCoreRequirements() throws Exception {
        NativeDatabaseBridge bridge = new NativeDatabaseBridge(objectMapper);
        bridge.installNativeDatabaseMcpTools();
        new MyBatisDatabasePreflight(bridge).verify(
                bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "ALL");

        NativeDatabaseBridge withoutMaxRows = nativeBridge();
        withoutMaxRows.removeProperty(DatabaseMcpContract.EXECUTE_QUERY, "maxRows");
        assertThatThrownBy(() -> new MyBatisDatabasePreflight(withoutMaxRows).verify(
                withoutMaxRows.mcpUri(), withoutMaxRows.webUri(), contract(), Path.of("/workspace/example"), "ALL"))
                .hasMessageContaining("input schema")
                .hasMessageContaining("maxRows");

        NativeDatabaseBridge withoutSqlRequirement = nativeBridge();
        withoutSqlRequirement.removeRequiredField(DatabaseMcpContract.EXECUTE_QUERY, "sql");
        assertThatThrownBy(() -> new MyBatisDatabasePreflight(withoutSqlRequirement).verify(
                withoutSqlRequirement.mcpUri(), withoutSqlRequirement.webUri(), contract(), Path.of("/workspace/example"), "ALL"))
                .hasMessageContaining("must require sql");
    }

    @Test
    void rejectsSchemaTypesScopeAndBoundsThatCannotAcceptCurrentArguments() {
        NativeDatabaseBridge bridge = nativeBridge();
        bridge.setPropertyType(DatabaseMcpContract.LIST_TABLE_SCHEMA, "includeColumns", "string");
        assertRejected(bridge, contract(), "includeColumns boolean");

        bridge = nativeBridge();
        bridge.setScopeValues(DatabaseMcpContract.EXECUTE_QUERY, "PROJECT", "ALL");
        assertRejected(bridge, contract(), "scope enum");

        bridge = nativeBridge();
        bridge.removeScopeEnum(DatabaseMcpContract.LIST_DATASOURCES);
        assertRejected(bridge, contract(), "scope enum");

        bridge = nativeBridge();
        bridge.setScopeValues(DatabaseMcpContract.LIST_DATABASES, "GLOBAL", "PROJECT", "ALL", "EXTRA");
        assertRejected(bridge, contract(), "scope enum");

        bridge = nativeBridge();
        bridge.setMaximum(DatabaseMcpContract.EXECUTE_QUERY, "maxRows", 19);
        assertRejected(bridge, contract(), "maxRows maximum does not allow 20");

        bridge = nativeBridge();
        bridge.setMaximum(DatabaseMcpContract.LIST_TABLE_SCHEMA, "maxTables", 199);
        assertRejected(bridge, contract(), "maxTables maximum does not allow 200");
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
        assertThat(bridge.capabilityChecks()).isEqualTo(2);
    }

    @Test
    void rejectsUnsupportedAgentBridgeBeforeListingToolsInVerifyAndRecheck() throws Exception {
        NativeDatabaseBridge bridge = new NativeDatabaseBridge(objectMapper);
        bridge.installNativeDatabaseMcpTools();
        bridge.setDatabaseMcpSupport(false);
        MyBatisDatabasePreflight preflight = new MyBatisDatabasePreflight(bridge);

        assertThatThrownBy(() -> preflight.verify(
                bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "ALL"))
                .hasMessageContaining("requires AgentBridge >= 1.202.0");
        assertThat(bridge.calledTools()).isEmpty();

        bridge.setDatabaseMcpSupport(true);
        MyBatisDatabasePreflight.Result verified = preflight.verify(
                bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "ALL");
        bridge.clearCalls();
        bridge.setDatabaseMcpSupport(false);

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

    private static String join(String first, String second) {
        return first + second;
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
        private boolean databaseMcpSupported = true;
        private int capabilityChecks;

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

        void removeProperty(String toolName, String field) {
            schemas.get(toolName).withObject("properties").remove(field);
        }

        void setPropertyType(String toolName, String field, String type) {
            ((ObjectNode) schemas.get(toolName).path("properties").path(field)).put("type", type);
        }

        void setScopeValues(String toolName, String... values) {
            ArrayNode enumValues = ((ObjectNode) schemas.get(toolName).path("properties").path("scope"))
                    .putArray("enum");
            for (String value : values) {
                enumValues.add(value);
            }
        }

        void removeScopeEnum(String toolName) {
            ((ObjectNode) schemas.get(toolName).path("properties").path("scope")).remove("enum");
        }

        void setMaximum(String toolName, String field, int maximum) {
            ((ObjectNode) schemas.get(toolName).path("properties").path(field)).put("maximum", maximum);
        }

        @Override
        public List<ToolDefinition> listTools(URI ignored) {
            return tools.stream().map(name -> new ToolDefinition(name, name, schemas.get(name))).toList();
        }

        @Override
        public void requireDatabaseMcpSupport(URI ignoredWeb) {
            capabilityChecks++;
            if (!databaseMcpSupported) {
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

        void setDatabaseMcpSupport(boolean databaseMcpSupported) { this.databaseMcpSupported = databaseMcpSupported; }
        int capabilityChecks() { return capabilityChecks; }
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
            for (String field : List.of("project", "scope")) {
                properties.putObject(field).put("type", "string");
            }
            ((ObjectNode) properties.get("scope")).putArray("enum").add("GLOBAL").add("PROJECT").add("ALL");
            if (!DatabaseMcpContract.LIST_DATASOURCES.equals(toolName)) {
                properties.putObject("dataSource").put("type", "string");
                schema.putArray("required").add("dataSource");
            }
            if (DatabaseMcpContract.LIST_TABLE_SCHEMA.equals(toolName)) {
                for (String field : List.of("catalog", "schema")) {
                    properties.putObject(field).put("type", "string");
                }
                properties.putObject("includeColumns").put("type", "boolean");
                properties.putObject("includeIndexes").put("type", "boolean");
                properties.putObject("maxTables").put("type", "integer").put("maximum", 200);
            }
            if (DatabaseMcpContract.EXECUTE_QUERY.equals(toolName)) {
                properties.putObject("sql").put("type", "string");
                properties.putObject("maxRows").put("type", "integer").put("maximum", 10_000);
                schema.withArray("required").add("sql");
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
