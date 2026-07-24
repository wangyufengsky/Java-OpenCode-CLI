package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MyBatisDatabasePreflight {
    private static final String SAFETY_PROBE_CONTRACT_VERSION = "mybatis-sql-review-db-safety-v2";
    private static final String GENERIC_CONNECTION_PROBE_SQL = "SELECT 1 AS contract_probe";
    private static final List<String> SAFETY_PROBE_COLUMNS = List.of(
            "probeContractVersion", "currentDatabase", "currentSchema", "currentUser",
            "superuser", "rolbypassrls", "systemAdmin", "auditAdmin", "roleAdmin", "roleMembershipCount",
            "databaseOwner", "schemaOwner", "ownedNonSystemSchemaCount", "ownedBaseTableCount",
            "databaseCreate", "databaseTemporary", "schemaCreate", "unsafeNonSystemSchemaCreateCount",
            "dangerousAnyPrivilege", "sessionReadOnly", "transactionReadOnly", "unsafeTablePrivilegeCount",
            "unsafeColumnPrivilegeCount", "unsafeSequencePrivilegeCount", "rlsEnabledBaseTableCount",
            "forceRlsEnabledBaseTableCount", "executableFunctionCount", "executablePackageCount",
            "unsafeForeignServerPrivilegeCount", "unsafeDirectoryPrivilegeCount", "statementTimeoutMs",
            "baseTableNames", "baseTableCount", "readReplica"
    );
    static final String SAFETY_PROBE_SQL = """
            WITH audited_schemas AS (
                SELECT n.oid, n.nspname, n.nspowner FROM pg_catalog.pg_namespace n
                WHERE n.nspname <> 'information_schema' AND n.nspname NOT LIKE 'pg_%'
            ), audited_tables AS (
                SELECT c.oid, c.relname, c.relowner, c.relrowsecurity, c.relforcerowsecurity, n.nspname
                FROM pg_catalog.pg_class c JOIN audited_schemas n ON n.oid = c.relnamespace
                WHERE c.relkind = 'r'
            ), target_tables AS (
                SELECT * FROM audited_tables WHERE nspname = pg_catalog.current_schema()
            ), audited_sequences AS (
                SELECT c.oid, n.nspname FROM pg_catalog.pg_class c
                JOIN audited_schemas n ON n.oid = c.relnamespace WHERE c.relkind = 'S'
            )
            SELECT 'mybatis-sql-review-db-safety-v2' AS "probeContractVersion",
                pg_catalog.current_database() AS "currentDatabase", pg_catalog.current_schema() AS "currentSchema",
                CURRENT_USER AS "currentUser", r.rolsuper AS "superuser", r.rolbypassrls AS "rolbypassrls",
                r.rolsystemadmin AS "systemAdmin",
                r.rolauditadmin AS "auditAdmin",
                (r.rolcreaterole OR r.rolcreatedb OR r.rolreplication OR r.roluseft
                    OR r.rolmonitoradmin OR r.roloperatoradmin OR r.rolpolicyadmin) AS "roleAdmin",
                (SELECT pg_catalog.count(*) FROM pg_catalog.pg_auth_members m WHERE m.member = r.oid) AS "roleMembershipCount",
                (d.datdba = r.oid) AS "databaseOwner", (n.nspowner = r.oid) AS "schemaOwner",
                (SELECT pg_catalog.count(*) FROM audited_schemas s WHERE s.nspowner = r.oid) AS "ownedNonSystemSchemaCount",
                (SELECT pg_catalog.count(*) FROM audited_tables t WHERE t.relowner = r.oid) AS "ownedBaseTableCount",
                pg_catalog.has_database_privilege(CURRENT_USER, pg_catalog.current_database(), 'CREATE') AS "databaseCreate",
                pg_catalog.has_database_privilege(CURRENT_USER, pg_catalog.current_database(), 'TEMPORARY') AS "databaseTemporary",
                pg_catalog.has_schema_privilege(CURRENT_USER, pg_catalog.current_schema(), 'CREATE') AS "schemaCreate",
                (SELECT pg_catalog.count(*) FROM audited_schemas s
                    WHERE pg_catalog.has_schema_privilege(CURRENT_USER, s.oid, 'CREATE')) AS "unsafeNonSystemSchemaCreateCount",
                (pg_catalog.has_any_privilege(CURRENT_USER, 'CREATE ANY TABLE')
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'ALTER ANY TABLE')
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'DROP ANY TABLE')
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'SELECT ANY TABLE')
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'INSERT ANY TABLE')
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'UPDATE ANY TABLE')
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'DELETE ANY TABLE')
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'CREATE ANY SEQUENCE')
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'CREATE ANY INDEX')
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'CREATE ANY FUNCTION')
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'EXECUTE ANY FUNCTION')
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'CREATE ANY PACKAGE')
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'EXECUTE ANY PACKAGE')
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'CREATE ANY TYPE')) AS "dangerousAnyPrivilege",
                (pg_catalog.current_setting('default_transaction_read_only') IN ('on', 'true')) AS "sessionReadOnly",
                (pg_catalog.current_setting('transaction_read_only') IN ('on', 'true')) AS "transactionReadOnly",
                (SELECT pg_catalog.count(*) FROM audited_tables t WHERE pg_catalog.has_table_privilege(
                    CURRENT_USER, t.oid, 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')) AS "unsafeTablePrivilegeCount",
                (SELECT pg_catalog.count(*) FROM audited_tables t WHERE pg_catalog.has_any_column_privilege(
                    CURRENT_USER, t.oid, 'INSERT,UPDATE')) AS "unsafeColumnPrivilegeCount",
                (SELECT pg_catalog.count(*) FROM audited_sequences s WHERE pg_catalog.has_sequence_privilege(
                    CURRENT_USER, s.oid, 'USAGE,UPDATE')) AS "unsafeSequencePrivilegeCount",
                (SELECT pg_catalog.count(*) FROM target_tables t WHERE t.relrowsecurity) AS "rlsEnabledBaseTableCount",
                (SELECT pg_catalog.count(*) FROM target_tables t WHERE t.relforcerowsecurity) AS "forceRlsEnabledBaseTableCount",
                (SELECT pg_catalog.count(*) FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace pn ON pn.oid = p.pronamespace
                    WHERE (pn.nspname NOT IN ('pg_catalog', 'information_schema') OR p.prosecdef)
                      AND (pg_catalog.has_function_privilege(CURRENT_USER, p.oid, 'EXECUTE')
                        OR pg_catalog.has_function_privilege('public', p.oid, 'EXECUTE'))) AS "executableFunctionCount",
                (SELECT pg_catalog.count(DISTINCT gp.oid) FROM pg_catalog.gs_package gp
                    JOIN pg_catalog.pg_namespace gpn ON gpn.oid = gp.pkgnamespace
                    WHERE gpn.nspname NOT IN ('pg_catalog', 'information_schema')
                      AND (gp.pkgowner = r.oid OR EXISTS (SELECT 1 FROM pg_catalog.role_tab_privs rtp
                            WHERE pg_catalog.lower(rtp.table_name) = pg_catalog.lower(gp.pkgname)
                              AND pg_catalog.lower(rtp.role) IN (pg_catalog.lower(CURRENT_USER), 'public')
                              AND pg_catalog.upper(rtp.privilege) = 'EXECUTE') OR EXISTS (SELECT 1 FROM pg_catalog.pg_proc pp
                            WHERE pp.propackageid = gp.oid AND (pg_catalog.has_function_privilege(CURRENT_USER, pp.oid, 'EXECUTE')
                              OR pg_catalog.has_function_privilege('public', pp.oid, 'EXECUTE'))))) AS "executablePackageCount",
                (SELECT pg_catalog.count(*) FROM pg_catalog.pg_foreign_server fs WHERE pg_catalog.has_server_privilege(
                    CURRENT_USER, fs.oid, 'USAGE,ALTER,DROP,COMMENT')) AS "unsafeForeignServerPrivilegeCount",
                (SELECT pg_catalog.count(*) FROM pg_catalog.pg_directory dir WHERE pg_catalog.has_directory_privilege(
                    CURRENT_USER, dir.dirname, 'READ,WRITE')) AS "unsafeDirectoryPrivilegeCount",
                (EXTRACT(EPOCH FROM pg_catalog.current_setting('statement_timeout')::pg_catalog.interval) * 1000)::bigint AS "statementTimeoutMs",
                (SELECT pg_catalog.string_agg(pg_catalog.lower(t.relname), ',' ORDER BY pg_catalog.lower(t.relname))
                    FROM target_tables t) AS "baseTableNames",
                (SELECT pg_catalog.count(*) FROM target_tables) AS "baseTableCount",
                pg_catalog.pg_is_in_recovery() AS "readReplica"
            FROM pg_catalog.pg_roles r JOIN pg_catalog.pg_database d ON d.datname = pg_catalog.current_database()
            JOIN pg_catalog.pg_namespace n ON n.nspname = pg_catalog.current_schema() WHERE r.rolname = CURRENT_USER
            """;

    public static final Set<String> REQUIRED_DATABASE_TOOLS = Set.of(
            DatabaseMcpContract.LIST_DATASOURCES, DatabaseMcpContract.LIST_DATABASES,
            DatabaseMcpContract.LIST_TABLE_SCHEMA, DatabaseMcpContract.EXECUTE_QUERY);

    private final AgentBridgeClient client;

    public MyBatisDatabasePreflight(AgentBridgeClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public Result verify(URI mcpUri, URI webBaseUri, DatabaseContract contract, Path projectPath, String scope)
            throws Exception {
        Objects.requireNonNull(mcpUri, "mcpUri");
        Objects.requireNonNull(webBaseUri, "webBaseUri");
        Objects.requireNonNull(contract, "contract");
        client.requireDatabaseMcpSupport(webBaseUri);
        verifyCredentialContract(contract);
        DatabaseMcpContract.Binding binding = new DatabaseMcpContract.Binding(
                contract.connectionName(), contract.databaseName(), contract.schemaName(), projectPath,
                DatabaseMcpContract.Scope.parse(scope));
        DatabaseMcpContract nativeContract = new DatabaseMcpContract(new com.fasterxml.jackson.databind.ObjectMapper(), binding);
        Map<String, AgentBridgeClient.ToolDefinition> tools = requireNativeTools(mcpUri);
        requireNativeToolSchemas(tools);
        JsonNode dataSource = resolveDataSource(mcpUri, nativeContract, binding.dataSource());
        DataSourceIdentity dataSourceIdentity = dataSourceIdentity(dataSource);
        String databaseSystem = dataSourceIdentity.databaseSystem();
        Environment environment = verifiedEnvironment(dataSource, contract.environment(), contract.safetyMode());
        requireCatalog(mcpUri, nativeContract, binding.catalog());
        AgentBridgeClient.ToolResponse tableSchemaResponse = client.callTool(
                mcpUri, DatabaseMcpContract.LIST_TABLE_SCHEMA, nativeContract.tableInventoryArguments());
        if (tableSchemaResponse.text().contains("[...truncated]")) {
            throw new IllegalStateException(
                    "Database MCP table-schema response was truncated; request a bounded table inventory without columns/indexes");
        }
        TableInventory tableInventory = safeBaseRelations(
                tableSchemaResponse.structured(), binding);
        StatementTimeoutContract verifiedTimeout;
        if (contract.safetyMode() == SafetyMode.STRICT && isGaussDb(databaseSystem)) {
            SafetyProbe probe = verifySafetyProbe(
                    callProbe(mcpUri, nativeContract, SAFETY_PROBE_SQL), binding, contract, tableInventory);
            verifiedTimeout = new StatementTimeoutContract(Duration.ofMillis(probe.statementTimeoutMs()),
                    StatementTimeoutScope.EFFECTIVE_SESSION, true);
        } else {
            verifyGenericConnectionProbe(
                    callProbe(mcpUri, nativeContract, GENERIC_CONNECTION_PROBE_SQL), binding);
            verifiedTimeout = contract.statementTimeout();
        }
        return new Result(binding, dataSourceIdentity, environment, verifiedTimeout, tableInventory,
                contract.safetyMode());
    }

    public Result verify(URI mcpUri, URI webBaseUri, DatabaseContract contract) throws Exception {
        return verify(mcpUri, webBaseUri, contract, Path.of("."), DatabaseMcpContract.Scope.ALL.name());
    }

    public void recheck(URI mcpUri, URI webBaseUri, Result verified) throws Exception {
        Objects.requireNonNull(mcpUri, "mcpUri");
        Objects.requireNonNull(webBaseUri, "webBaseUri");
        Objects.requireNonNull(verified, "verified");
        client.requireDatabaseMcpSupport(webBaseUri);
        DatabaseMcpContract nativeContract = new DatabaseMcpContract(
                new com.fasterxml.jackson.databind.ObjectMapper(), verified.binding());
        Map<String, AgentBridgeClient.ToolDefinition> tools = requireNativeTools(mcpUri);
        requireNativeToolSchemas(tools);
        JsonNode dataSource = resolveDataSource(mcpUri, nativeContract, verified.binding().dataSource());
        DataSourceIdentity currentIdentity = dataSourceIdentity(dataSource);
        if (!verified.dataSourceIdentity.equals(currentIdentity)) {
            throw new IllegalStateException("per-task data-source identity changed after preflight");
        }
        String currentSystem = currentIdentity.databaseSystem();
        if (verifiedEnvironment(dataSource, verified.environment(), verified.safetyMode()) != verified.environment()) {
            throw new IllegalStateException("per-task data-source environment changed after preflight");
        }
        if (verified.safetyMode() == SafetyMode.STRICT && isGaussDb(currentSystem)) {
            verifySafetyProbe(callProbe(mcpUri, nativeContract, SAFETY_PROBE_SQL), verified.binding(),
                    new DatabaseContract(verified.binding().dataSource(), verified.binding().catalog(), verified.binding().schema(),
                            verified.environment(), true, true, true, verified.statementTimeout(),
                            verified.safetyMode()), verified.tableInventory);
        } else {
            verifyGenericConnectionProbe(
                    callProbe(mcpUri, nativeContract, GENERIC_CONNECTION_PROBE_SQL), verified.binding());
        }
    }

    private Map<String, AgentBridgeClient.ToolDefinition> requireNativeTools(URI mcpUri) throws Exception {
        Map<String, AgentBridgeClient.ToolDefinition> tools = new LinkedHashMap<>();
        for (AgentBridgeClient.ToolDefinition tool : client.listTools(mcpUri)) {
            if (tool.name() == null || tool.name().isBlank() || tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("Database MCP tool catalog contains a missing or duplicate tool name");
            }
        }
        Set<String> missing = new LinkedHashSet<>(REQUIRED_DATABASE_TOOLS);
        missing.removeAll(tools.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Database MCP tools unavailable: " + missing);
        }
        return tools;
    }

    private void requireNativeToolSchemas(Map<String, AgentBridgeClient.ToolDefinition> tools) {
        requireInputSchema(tools.get(DatabaseMcpContract.LIST_DATASOURCES),
                Map.of("project", "string", "scope", "string"), Set.of(), null);
        requireInputSchema(tools.get(DatabaseMcpContract.LIST_DATABASES), Map.of(
                "project", "string", "scope", "string", "dataSource", "string"), Set.of("dataSource"), null);
        requireInputSchema(tools.get(DatabaseMcpContract.LIST_TABLE_SCHEMA), Map.of(
                "project", "string", "scope", "string", "dataSource", "string", "catalog", "string", "schema", "string",
                "includeColumns", "boolean", "includeIndexes", "boolean", "maxTables", "integer"),
                Set.of("dataSource"), new NumericArgument("maxTables", DatabaseMcpContract.MAX_TABLES));
        requireInputSchema(tools.get(DatabaseMcpContract.EXECUTE_QUERY), Map.of(
                "project", "string", "scope", "string", "dataSource", "string", "sql", "string", "maxRows", "integer"),
                Set.of("dataSource", "sql"), new NumericArgument("maxRows", DatabaseMcpContract.MAX_ROWS));
    }

    private void requireInputSchema(
            AgentBridgeClient.ToolDefinition tool,
            Map<String, String> passedProperties,
            Set<String> coreRequired,
            NumericArgument constrainedArgument
    ) {
        JsonNode schema = tool == null ? null : tool.inputSchema();
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())
                || !schema.path("properties").isObject()
                || (schema.has("required") && !schema.path("required").isArray())) {
            throw new IllegalStateException("Database MCP " + (tool == null ? "tool" : tool.name())
                    + " input schema is unsupported or incomplete");
        }
        Set<String> required = new LinkedHashSet<>();
        for (JsonNode field : schema.path("required")) {
            if (!field.isTextual() || field.asText().isBlank() || !required.add(field.asText())) {
                throw new IllegalStateException("Database MCP " + tool.name() + " input schema has invalid required fields");
            }
        }
        for (String requiredField : coreRequired) {
            if (!required.contains(requiredField)) {
                throw new IllegalStateException("Database MCP " + tool.name()
                        + " input schema must require " + requiredField);
            }
        }
        for (Map.Entry<String, String> property : passedProperties.entrySet()) {
            if (!property.getValue().equals(schema.path("properties").path(property.getKey()).path("type").asText())) {
                throw new IllegalStateException("Database MCP " + tool.name() + " input schema cannot accept "
                        + property.getKey() + " " + property.getValue() + " argument");
            }
        }
        requireScopeValues(tool.name(), schema.path("properties").path("scope"));
        if (constrainedArgument != null) {
            requireNumericArgumentRange(tool.name(), schema.path("properties").path(constrainedArgument.name()),
                    constrainedArgument);
        }
    }

    private void requireScopeValues(String toolName, JsonNode scope) {
        if (!scope.isObject() || !scope.path("enum").isArray()) {
            throw new IllegalStateException("Database MCP " + toolName + " scope enum is invalid");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode value : scope.path("enum")) {
            if (!value.isTextual() || !values.add(value.textValue())) {
                throw new IllegalStateException("Database MCP " + toolName + " scope enum is invalid");
            }
        }
        if (!values.equals(Set.of("GLOBAL", "PROJECT", "ALL"))) {
            throw new IllegalStateException("Database MCP " + toolName
                    + " scope enum must be exactly GLOBAL, PROJECT, and ALL");
        }
    }

    private void requireNumericArgumentRange(String toolName, JsonNode schema, NumericArgument argument) {
        if (schema.has("minimum") && (!schema.path("minimum").isNumber()
                || schema.path("minimum").decimalValue().compareTo(java.math.BigDecimal.valueOf(argument.value())) > 0)) {
            throw new IllegalStateException("Database MCP " + toolName + " " + argument.name()
                    + " minimum does not allow " + argument.value());
        }
        if (schema.has("maximum") && (!schema.path("maximum").isNumber()
                || schema.path("maximum").decimalValue().compareTo(java.math.BigDecimal.valueOf(argument.value())) < 0)) {
            throw new IllegalStateException("Database MCP " + toolName + " " + argument.name()
                    + " maximum does not allow " + argument.value());
        }
        if (schema.has("enum")) {
            boolean allowed = false;
            for (JsonNode value : schema.path("enum")) {
                allowed |= value.isIntegralNumber() && value.intValue() == argument.value();
            }
            if (!allowed) {
                throw new IllegalStateException("Database MCP " + toolName + " " + argument.name()
                        + " enum does not allow " + argument.value());
            }
        }
    }

    private record NumericArgument(String name, int value) {
    }

    private JsonNode resolveDataSource(URI mcpUri, DatabaseMcpContract contract, String expected) throws Exception {
        List<JsonNode> matches = namedValues(client.callTool(mcpUri, DatabaseMcpContract.LIST_DATASOURCES,
                contract.dataSourceArguments()).structured(), "dataSources", "dataSource", "name").stream()
                .filter(value -> expected.equals(valueName(value))).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("expected exactly one data source named '" + expected + "' but found " + matches.size());
        }
        if (!matches.getFirst().isObject()) {
            throw new IllegalStateException("selected data source must expose database metadata");
        }
        return matches.getFirst();
    }

    private void requireCatalog(URI mcpUri, DatabaseMcpContract contract, String catalog) throws Exception {
        boolean found = namedValues(client.callTool(mcpUri, DatabaseMcpContract.LIST_DATABASES,
                contract.databaseArguments()).structured(), "databases", "catalog", "name", "databaseName").stream()
                .anyMatch(value -> catalog.equals(valueName(value)));
        if (!found) {
            throw new IllegalStateException("configured catalog was not found on the selected data source: " + catalog);
        }
    }

    private List<JsonNode> namedValues(JsonNode response, String collectionField, String... nameFields) {
        JsonNode values = response.isArray() ? response : response.path(collectionField);
        if (!values.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (value.isTextual()) {
                ObjectNode named = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                named.put("name", value.asText());
                result.add(named);
            } else if (value.isObject()) {
                for (String field : nameFields) {
                    if (value.path(field).isTextual() && !value.path(field).asText().isBlank()) {
                        result.add(value);
                        break;
                    }
                }
            }
        }
        return result;
    }

    private String valueName(JsonNode value) {
        for (String field : List.of("dataSource", "name", "catalog", "databaseName")) {
            if (value.path(field).isTextual() && !value.path(field).asText().isBlank()) {
                return value.path(field).asText();
            }
        }
        return "";
    }

    private Environment verifiedEnvironment(JsonNode source, Environment expected, SafetyMode safetyMode) {
        if (safetyMode == SafetyMode.CONNECTIVITY_ONLY) {
            if (expected != Environment.TEST) {
                throw new IllegalStateException(
                        "connectivity-only database safety mode is restricted to the test environment");
            }
            String reported = source.path("environment").asText("").strip();
            String normalized = reported.toLowerCase(Locale.ROOT);
            if (Set.of("production", "prod", "production-primary", "primary").contains(normalized)) {
                throw new IllegalStateException(
                        "connectivity-only database safety mode cannot target a non-test data source");
            }
            return Environment.TEST;
        }
        if (expected != Environment.READ_REPLICA) {
            throw new IllegalStateException("data-source environment must identify a physical read-replica target");
        }
        String reported = source.path("environment").asText("").strip();
        if (!reported.isEmpty() && !"read-replica".equalsIgnoreCase(reported)) {
            throw new IllegalStateException("data-source environment must identify a physical read-replica target");
        }
        return Environment.READ_REPLICA;
    }

    private TableInventory safeBaseRelations(JsonNode response, DatabaseMcpContract.Binding binding) {
        if (!response.isObject()) {
            throw new IllegalStateException("table-schema metadata does not match the configured catalog/schema target");
        }
        boolean mismatchedCatalog = response.has("catalog")
                && !binding.catalog().equals(response.path("catalog").asText());
        boolean mismatchedSchema = response.has("schema")
                && !binding.schema().equals(response.path("schema").asText());
        if (mismatchedCatalog || mismatchedSchema) {
            throw new IllegalStateException("table-schema metadata does not match the configured catalog/schema target");
        }
        JsonNode tables = response.path("tables");
        if (!tables.isArray() || tables.isEmpty()) {
            throw new IllegalStateException("Database MCP table-schema inspection must return at least one base TABLE");
        }
        int totalTablesFound = tables.size();
        if (response.has("totalTablesFound") || response.has("sampledCount")) {
            JsonNode total = response.path("totalTablesFound");
            JsonNode sampled = response.path("sampledCount");
            if (!total.isIntegralNumber() || !sampled.isIntegralNumber()
                    || total.intValue() < sampled.intValue()
                    || sampled.intValue() != tables.size()) {
                throw new IllegalStateException("Database MCP table-schema inspection returned an incomplete table inventory");
            }
            totalTablesFound = total.intValue();
        }
        Set<String> relations = new LinkedHashSet<>();
        for (JsonNode table : tables) {
            if (!table.isObject()) {
                throw new IllegalStateException("every safe relation must explicitly report type TABLE");
            }
            String reportedType = table.path("type").asText("").strip();
            if (!reportedType.isEmpty() && !"TABLE".equalsIgnoreCase(reportedType)) {
                throw new IllegalStateException("every safe relation must explicitly report type TABLE");
            }
            String name = firstRequiredText(table, List.of("tableName", "name"), "base table name");
            if (!isUnquotedIdentifier(name) || !relations.add(canonicalRelation(binding.schema(), name))) {
                throw new IllegalStateException("safe base table inventory contains an invalid or duplicate relation");
            }
        }
        return new TableInventory(relations, totalTablesFound);
    }

    private JsonNode callProbe(URI mcpUri, DatabaseMcpContract contract, String sql) throws Exception {
        AgentBridgeClient.ToolResponse response = client.callTool(mcpUri, DatabaseMcpContract.EXECUTE_QUERY,
                contract.queryArguments(sql));
        if (response.rawResult().path("isError").asBoolean(false)) {
            throw new IllegalStateException("database safety probe returned an MCP tool error");
        }
        return response.structured();
    }

    private SafetyProbe verifySafetyProbe(JsonNode response, DatabaseMcpContract.Binding binding,
                                          DatabaseContract contract, TableInventory tableInventory) {
        verifyQueryEnvelope(response, binding);
        JsonNode row = probeRow(response);
        Set<String> fields = new LinkedHashSet<>();
        row.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(new LinkedHashSet<>(SAFETY_PROBE_COLUMNS))) {
            throw new IllegalStateException("database safety probe row fields do not match the strict response contract");
        }
        requireProbeText(row, "probeContractVersion", SAFETY_PROBE_CONTRACT_VERSION);
        requireProbeText(row, "currentDatabase", binding.catalog());
        requireProbeText(row, "currentSchema", binding.schema());
        requiredProbeText(row, "currentUser");
        for (String field : List.of("superuser", "rolbypassrls", "systemAdmin", "auditAdmin", "roleAdmin", "databaseOwner", "schemaOwner",
                "databaseCreate", "databaseTemporary", "schemaCreate", "dangerousAnyPrivilege")) {
            if (requiredProbeBoolean(row, field)) {
                throw new IllegalStateException("database safety probe rejected unsafe " + field);
            }
        }
        for (String field : List.of("roleMembershipCount", "ownedNonSystemSchemaCount", "ownedBaseTableCount",
                "unsafeNonSystemSchemaCreateCount", "unsafeTablePrivilegeCount", "unsafeColumnPrivilegeCount",
                "unsafeSequencePrivilegeCount", "rlsEnabledBaseTableCount", "forceRlsEnabledBaseTableCount",
                "executableFunctionCount", "executablePackageCount", "unsafeForeignServerPrivilegeCount",
                "unsafeDirectoryPrivilegeCount")) {
            if (requiredProbeLong(row, field) != 0) {
                throw new IllegalStateException("database safety probe rejected non-zero " + field);
            }
        }
        if (!requiredProbeBoolean(row, "sessionReadOnly") || !requiredProbeBoolean(row, "transactionReadOnly")) {
            throw new IllegalStateException("database safety probe requires sessionReadOnly=true and transactionReadOnly=true");
        }
        long timeoutMillis = requiredProbeLong(row, "statementTimeoutMs");
        if (timeoutMillis < 1 || timeoutMillis > Duration.ofSeconds(30).toMillis()
                || timeoutMillis > contract.statementTimeout().maximum().toMillis()) {
            throw new IllegalStateException("database safety probe statementTimeoutMs must be positive and no more than configured/30000");
        }
        List<String> sampledTables = tableInventory.safeBaseRelations().stream()
                .map(relation -> relation.substring(relation.indexOf('.') + 1)).sorted().toList();
        String reportedNames = requiredProbeText(row, "baseTableNames");
        Set<String> verifiedTables = new LinkedHashSet<>();
        for (String table : reportedNames.split(",", -1)) {
            String name = table.strip().toLowerCase(Locale.ROOT);
            if (!isUnquotedIdentifier(name) || !verifiedTables.add(name)) {
                throw new IllegalStateException("database safety probe baseTableNames is invalid");
            }
        }
        long verifiedTableCount = requiredProbeLong(row, "baseTableCount");
        if (verifiedTableCount != tableInventory.totalTablesFound()
                || verifiedTableCount != verifiedTables.size()
                || !verifiedTables.containsAll(sampledTables)) {
            throw new IllegalStateException("database safety probe baseTableCount does not match the verified inventory");
        }
        if (!requiredProbeBoolean(row, "readReplica")) {
            throw new IllegalStateException("database safety probe could not prove a physical read-replica with pg_is_in_recovery()");
        }
        return new SafetyProbe(timeoutMillis);
    }

    private void verifyGenericConnectionProbe(JsonNode response, DatabaseMcpContract.Binding binding) {
        verifyQueryEnvelope(response, binding);
        JsonNode row = probeRow(response);
        if (row.size() != 1) {
            throw new IllegalStateException("generic database connection probe must return contract_probe=1");
        }
        Map.Entry<String, JsonNode> probe = row.fields().next();
        String normalizedName = probe.getKey().replace("_", "").toLowerCase(Locale.ROOT);
        if (!"contractprobe".equals(normalizedName) || !probe.getValue().isNumber()
                || probe.getValue().decimalValue().compareTo(java.math.BigDecimal.ONE) != 0) {
            throw new IllegalStateException("generic database connection probe must return contract_probe=1");
        }
    }

    private void verifyQueryEnvelope(JsonNode response, DatabaseMcpContract.Binding binding) {
        if (!response.isObject()) {
            return;
        }
        boolean mismatchedMode = response.has("mode")
                && !"QUERY".equalsIgnoreCase(response.path("mode").asText());
        boolean missingResultSet = response.has("hasResultSet")
                && !response.path("hasResultSet").asBoolean(false);
        boolean mismatchedUpdateCount = response.has("updateCount")
                && response.path("updateCount").asLong() != -1;
        boolean mismatchedDataSource = response.has("dataSource")
                && !binding.dataSource().equals(response.path("dataSource").asText());
        boolean mismatchedRowCount = response.has("rowCount")
                && (!response.path("rowCount").isIntegralNumber()
                || response.path("rowCount").intValue() != response.path("rows").size());
        if (mismatchedMode || missingResultSet || mismatchedUpdateCount
                || mismatchedDataSource || mismatchedRowCount) {
            throw new IllegalStateException("database query response envelope does not match the bound read query");
        }
    }

    private JsonNode probeRow(JsonNode response) {
        if (response.isArray()) {
            if (response.size() == 1 && response.get(0).isObject()) {
                return response.get(0);
            }
            throw new IllegalStateException("database safety probe must return exactly one row");
        }
        if (!response.isObject() || !response.path("rows").isArray() || response.path("rows").size() != 1) {
            throw new IllegalStateException("database probe must return an array or an object with exactly one row");
        }
        JsonNode encodedRow = response.path("rows").get(0);
        if (!response.has("columns") && encodedRow.isObject()) {
            return encodedRow;
        }
        if (!response.path("columns").isArray()) {
            throw new IllegalStateException("database safety probe columns must be an array when rows are positional");
        }
        List<String> columns = new ArrayList<>();
        for (JsonNode column : response.path("columns")) {
            if (!column.isTextual()) {
                throw new IllegalStateException("database safety probe columns must be strings");
            }
            columns.add(column.asText());
        }
        if (!SAFETY_PROBE_COLUMNS.equals(columns)) {
            throw new IllegalStateException("database safety probe columns do not match contract " + SAFETY_PROBE_CONTRACT_VERSION);
        }
        if (encodedRow.isObject()) {
            return encodedRow;
        }
        if (!encodedRow.isArray() || encodedRow.size() != columns.size()) {
            throw new IllegalStateException("database safety probe row does not match the columns contract");
        }
        ObjectNode row = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        for (int index = 0; index < columns.size(); index++) {
            row.set(columns.get(index), encodedRow.get(index));
        }
        return row;
    }

    private void verifyCredentialContract(DatabaseContract contract) {
        StatementTimeoutContract timeout = contract.statementTimeout();
        if (!timeout.confirmed() || timeout.maximum().isZero() || timeout.maximum().isNegative()
                || timeout.maximum().compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException("confirmed database/server/role statement_timeout must be positive and <= 30 seconds");
        }
        if (timeout.scope() != StatementTimeoutScope.DATABASE
                && timeout.scope() != StatementTimeoutScope.SERVER
                && timeout.scope() != StatementTimeoutScope.ROLE) {
            throw new IllegalStateException("statement_timeout scope must be DATABASE, SERVER, or ROLE");
        }
        if (contract.safetyMode() == SafetyMode.CONNECTIVITY_ONLY) {
            if (contract.environment() != Environment.TEST) {
                throw new IllegalStateException(
                        "connectivity-only database safety mode is restricted to the test environment");
            }
            return;
        }
        if (contract.environment() != Environment.READ_REPLICA) {
            throw new IllegalStateException("database environment must be a physical read-replica");
        }
        if (!contract.nonOwnerNonAdminReadOnlyAccount()) {
            throw new IllegalStateException("runtime credentials must use a non-owner/non-admin read-only account");
        }
        if (!contract.rowLevelSecurityDisabledForSafeBaseTables()) {
            throw new IllegalStateException("RLS disabled for every safe base table must be explicitly confirmed before preflight");
        }
        if (!contract.userDefinedAndSecurityDefinerFunctionExecutionRevokedIncludingPublic()) {
            throw new IllegalStateException("user-defined and security-definer function execution must be revoked from the audit account, including PUBLIC");
        }
    }

    private static boolean isUnquotedIdentifier(String value) {
        return value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    static String canonicalRelation(String schemaName, String relationName) {
        return schemaName.toLowerCase(Locale.ROOT) + "." + relationName.toLowerCase(Locale.ROOT);
    }

    private String requiredText(JsonNode node, String field, String label) {
        String value = node.path(field).asText("").strip();
        if (value.isBlank()) {
            throw new IllegalStateException("missing " + label + " in Database MCP response");
        }
        return value;
    }

    private String firstRequiredText(JsonNode node, List<String> fields, String label) {
        for (String field : fields) {
            String value = node.path(field).asText("").strip();
            if (!value.isEmpty()) {
                return value;
            }
        }
        throw new IllegalStateException("missing " + label + " in Database MCP response");
    }

    private String databaseSystem(JsonNode dataSource) {
        return firstRequiredText(dataSource,
                List.of("type", "databaseSystem", "dbType", "databaseType"), "data-source database type");
    }

    private DataSourceIdentity dataSourceIdentity(JsonNode dataSource) {
        return new DataSourceIdentity(
                firstRequiredText(dataSource, List.of("dataSource", "name"), "data-source name"),
                databaseSystem(dataSource),
                optionalText(dataSource, "driverClass"),
                optionalText(dataSource, "url"),
                optionalText(dataSource, "version"),
                optionalText(dataSource, "scope"),
                optionalText(dataSource, "project"),
                optionalText(dataSource, "projectPath")
        );
    }

    private String optionalText(JsonNode node, String field) {
        return node.path(field).asText("").strip();
    }

    private boolean isGaussDb(String databaseSystem) {
        return databaseSystem.toLowerCase(Locale.ROOT).contains("gaussdb");
    }

    private void requireProbeText(JsonNode row, String field, String expected) {
        if (!expected.equals(requiredProbeText(row, field))) {
            throw new IllegalStateException("database safety probe " + field + " does not match target");
        }
    }

    private String requiredProbeText(JsonNode row, String field) {
        if (!row.path(field).isTextual() || row.path(field).asText().isBlank()) {
            throw new IllegalStateException("database safety probe missing textual " + field);
        }
        return row.path(field).asText();
    }

    private boolean requiredProbeBoolean(JsonNode row, String field) {
        if (!row.path(field).isBoolean()) {
            throw new IllegalStateException("database safety probe missing boolean " + field);
        }
        return row.path(field).booleanValue();
    }

    private long requiredProbeLong(JsonNode row, String field) {
        if (!row.path(field).isIntegralNumber() || !row.path(field).canConvertToLong()) {
            throw new IllegalStateException("database safety probe missing integral " + field);
        }
        return row.path(field).longValue();
    }

    public enum Environment { READ_REPLICA, TEST, PRODUCTION_PRIMARY }
    public enum SafetyMode {
        STRICT("strict"),
        CONNECTIVITY_ONLY("connectivity-only");

        private final String configValue;

        SafetyMode(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }
    public enum StatementTimeoutScope { DATABASE, SERVER, ROLE, EFFECTIVE_SESSION }

    public record StatementTimeoutContract(Duration maximum, StatementTimeoutScope scope, boolean confirmed) {
        public StatementTimeoutContract {
            Objects.requireNonNull(maximum, "maximum");
            Objects.requireNonNull(scope, "scope");
        }
    }

    public record DatabaseContract(String connectionName, String databaseName, String schemaName, Environment environment,
                                   boolean nonOwnerNonAdminReadOnlyAccount,
                                   boolean rowLevelSecurityDisabledForSafeBaseTables,
                                   boolean userDefinedAndSecurityDefinerFunctionExecutionRevokedIncludingPublic,
                                   StatementTimeoutContract statementTimeout,
                                   SafetyMode safetyMode) {
        public DatabaseContract(String connectionName, String databaseName, String schemaName, Environment environment,
                                boolean nonOwnerNonAdminReadOnlyAccount,
                                boolean rowLevelSecurityDisabledForSafeBaseTables,
                                boolean userDefinedAndSecurityDefinerFunctionExecutionRevokedIncludingPublic,
                                StatementTimeoutContract statementTimeout) {
            this(connectionName, databaseName, schemaName, environment, nonOwnerNonAdminReadOnlyAccount,
                    rowLevelSecurityDisabledForSafeBaseTables,
                    userDefinedAndSecurityDefinerFunctionExecutionRevokedIncludingPublic,
                    statementTimeout, SafetyMode.STRICT);
        }

        public DatabaseContract {
            Objects.requireNonNull(connectionName, "connectionName");
            Objects.requireNonNull(databaseName, "databaseName");
            Objects.requireNonNull(schemaName, "schemaName");
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(statementTimeout, "statementTimeout");
            Objects.requireNonNull(safetyMode, "safetyMode");
        }
    }

    private record SafetyProbe(long statementTimeoutMs) { }
    private record TableInventory(Set<String> safeBaseRelations, int totalTablesFound) {
        private TableInventory {
            safeBaseRelations = Set.copyOf(safeBaseRelations);
            if (safeBaseRelations.isEmpty() || totalTablesFound < safeBaseRelations.size()) {
                throw new IllegalArgumentException("table inventory must contain a valid bounded sample");
            }
        }
    }
    private record DataSourceIdentity(
            String name,
            String databaseSystem,
            String driverClass,
            String url,
            String version,
            String scope,
            String project,
            String projectPath
    ) { }

    public static final class Result {
        private final DatabaseMcpContract.Binding binding;
        private final DataSourceIdentity dataSourceIdentity;
        private final Environment environment;
        private final StatementTimeoutContract statementTimeout;
        private final TableInventory tableInventory;
        private final Set<String> safeBaseRelations;
        private final SafetyMode safetyMode;

        private Result(DatabaseMcpContract.Binding binding, DataSourceIdentity dataSourceIdentity, Environment environment,
                       StatementTimeoutContract statementTimeout, TableInventory tableInventory,
                       SafetyMode safetyMode) {
            this.binding = Objects.requireNonNull(binding, "binding");
            this.dataSourceIdentity = Objects.requireNonNull(dataSourceIdentity, "dataSourceIdentity");
            this.environment = Objects.requireNonNull(environment, "environment");
            this.statementTimeout = Objects.requireNonNull(statementTimeout, "statementTimeout");
            this.tableInventory = Objects.requireNonNull(tableInventory, "tableInventory");
            this.safeBaseRelations = tableInventory.safeBaseRelations();
            this.safetyMode = Objects.requireNonNull(safetyMode, "safetyMode");
        }

        public DatabaseMcpContract.Binding binding() { return binding; }
        public String databaseSystem() { return dataSourceIdentity.databaseSystem(); }
        public Environment environment() { return environment; }
        public StatementTimeoutContract statementTimeout() { return statementTimeout; }
        public Set<String> safeBaseRelations() { return safeBaseRelations; }
        public SafetyMode safetyMode() { return safetyMode; }
        public String databaseSafety() {
            return safetyMode == SafetyMode.CONNECTIVITY_ONLY ? "unverified" : "verified";
        }

    }
}
