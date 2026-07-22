package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class MyBatisDatabasePreflight {
    private static final String SAFETY_PROBE_CONTRACT_VERSION = "mybatis-sql-review-db-safety-v2";
    private static final String VERIFIED_ENVIRONMENT_SOURCE = "managed-connection-metadata";
    private static final List<String> SAFETY_PROBE_COLUMNS = List.of(
            "probeContractVersion",
            "currentDatabase",
            "currentSchema",
            "currentUser",
            "superuser",
            "systemAdmin",
            "auditAdmin",
            "roleAdmin",
            "roleMembershipCount",
            "databaseOwner",
            "schemaOwner",
            "ownedNonSystemSchemaCount",
            "ownedBaseTableCount",
            "databaseCreate",
            "databaseTemporary",
            "schemaCreate",
            "unsafeNonSystemSchemaCreateCount",
            "dangerousAnyPrivilege",
            "sessionReadOnly",
            "transactionReadOnly",
            "unsafeTablePrivilegeCount",
            "unsafeColumnPrivilegeCount",
            "unsafeSequencePrivilegeCount",
            "rlsEnabledBaseTableCount",
            "forceRlsEnabledBaseTableCount",
            "executableFunctionCount",
            "executablePackageCount",
            "unsafeForeignServerPrivilegeCount",
            "unsafeDirectoryPrivilegeCount",
            "statementTimeoutMs",
            "baseTableNames",
            "baseTableCount",
            "readReplica"
    );
    static final String SAFETY_PROBE_SQL = """
            WITH audited_schemas AS (
                SELECT n.oid, n.nspname, n.nspowner
                FROM pg_catalog.pg_namespace n
                WHERE n.nspname <> 'information_schema'
                  AND n.nspname NOT LIKE 'pg_%'
            ), audited_tables AS (
                SELECT c.oid, c.relname, c.relowner, c.relrowsecurity, c.relforcerowsecurity,
                       n.nspname
                FROM pg_catalog.pg_class c
                JOIN audited_schemas n ON n.oid = c.relnamespace
                WHERE c.relkind = 'r'
            ), target_tables AS (
                SELECT * FROM audited_tables WHERE nspname = pg_catalog.current_schema()
            ), audited_sequences AS (
                SELECT c.oid, n.nspname
                FROM pg_catalog.pg_class c
                JOIN audited_schemas n ON n.oid = c.relnamespace
                WHERE c.relkind = 'S'
            )
            SELECT
                'mybatis-sql-review-db-safety-v2' AS "probeContractVersion",
                pg_catalog.current_database() AS "currentDatabase",
                pg_catalog.current_schema() AS "currentSchema",
                CURRENT_USER AS "currentUser",
                r.rolsuper AS "superuser",
                r.rolsystemadmin AS "systemAdmin",
                r.rolauditadmin AS "auditAdmin",
                (r.rolcreaterole OR r.rolcreatedb OR r.rolreplication OR r.roluseft
                    OR r.rolmonitoradmin OR r.roloperatoradmin OR r.rolpolicyadmin) AS "roleAdmin",
                (SELECT pg_catalog.count(*) FROM pg_catalog.pg_auth_members m WHERE m.member = r.oid)
                    AS "roleMembershipCount",
                (d.datdba = r.oid) AS "databaseOwner",
                (n.nspowner = r.oid) AS "schemaOwner",
                (SELECT pg_catalog.count(*) FROM audited_schemas s WHERE s.nspowner = r.oid)
                    AS "ownedNonSystemSchemaCount",
                (SELECT pg_catalog.count(*) FROM audited_tables t WHERE t.relowner = r.oid)
                    AS "ownedBaseTableCount",
                pg_catalog.has_database_privilege(
                    CURRENT_USER, pg_catalog.current_database(), 'CREATE') AS "databaseCreate",
                pg_catalog.has_database_privilege(
                    CURRENT_USER, pg_catalog.current_database(), 'TEMPORARY') AS "databaseTemporary",
                pg_catalog.has_schema_privilege(
                    CURRENT_USER, pg_catalog.current_schema(), 'CREATE') AS "schemaCreate",
                (SELECT pg_catalog.count(*) FROM audited_schemas s
                    WHERE pg_catalog.has_schema_privilege(CURRENT_USER, s.oid, 'CREATE'))
                    AS "unsafeNonSystemSchemaCreateCount",
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
                    OR pg_catalog.has_any_privilege(CURRENT_USER, 'CREATE ANY TYPE'))
                    AS "dangerousAnyPrivilege",
                (pg_catalog.current_setting('default_transaction_read_only') IN ('on', 'true'))
                    AS "sessionReadOnly",
                (pg_catalog.current_setting('transaction_read_only') IN ('on', 'true'))
                    AS "transactionReadOnly",
                (SELECT pg_catalog.count(*) FROM audited_tables t
                    WHERE pg_catalog.has_table_privilege(
                        CURRENT_USER, t.oid, 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))
                    AS "unsafeTablePrivilegeCount",
                (SELECT pg_catalog.count(*) FROM audited_tables t
                    WHERE pg_catalog.has_any_column_privilege(
                        CURRENT_USER, t.oid, 'INSERT,UPDATE'))
                    AS "unsafeColumnPrivilegeCount",
                (SELECT pg_catalog.count(*) FROM audited_sequences s
                    WHERE pg_catalog.has_sequence_privilege(
                        CURRENT_USER, s.oid, 'USAGE,UPDATE'))
                    AS "unsafeSequencePrivilegeCount",
                (SELECT pg_catalog.count(*) FROM target_tables t WHERE t.relrowsecurity)
                    AS "rlsEnabledBaseTableCount",
                (SELECT pg_catalog.count(*) FROM target_tables t WHERE t.relforcerowsecurity)
                    AS "forceRlsEnabledBaseTableCount",
                (SELECT pg_catalog.count(*)
                    FROM pg_catalog.pg_proc p
                    JOIN pg_catalog.pg_namespace pn ON pn.oid = p.pronamespace
                    WHERE (pn.nspname NOT IN ('pg_catalog', 'information_schema') OR p.prosecdef)
                      AND (pg_catalog.has_function_privilege(CURRENT_USER, p.oid, 'EXECUTE')
                        OR pg_catalog.has_function_privilege('public', p.oid, 'EXECUTE')))
                    AS "executableFunctionCount",
                (SELECT pg_catalog.count(DISTINCT gp.oid)
                    FROM pg_catalog.gs_package gp
                    JOIN pg_catalog.pg_namespace gpn ON gpn.oid = gp.pkgnamespace
                    WHERE gpn.nspname NOT IN ('pg_catalog', 'information_schema')
                      AND (gp.pkgowner = r.oid
                        OR EXISTS (
                            SELECT 1 FROM pg_catalog.role_tab_privs rtp
                            WHERE pg_catalog.lower(rtp.table_name) = pg_catalog.lower(gp.pkgname)
                              AND pg_catalog.lower(rtp.role) IN (
                                  pg_catalog.lower(CURRENT_USER), 'public')
                              AND pg_catalog.upper(rtp.privilege) = 'EXECUTE')
                        OR EXISTS (
                            SELECT 1 FROM pg_catalog.pg_proc pp
                            WHERE pp.propackageid = gp.oid
                              AND (pg_catalog.has_function_privilege(
                                    CURRENT_USER, pp.oid, 'EXECUTE')
                                OR pg_catalog.has_function_privilege(
                                    'public', pp.oid, 'EXECUTE')))))
                    AS "executablePackageCount",
                (SELECT pg_catalog.count(*) FROM pg_catalog.pg_foreign_server fs
                    WHERE pg_catalog.has_server_privilege(
                        CURRENT_USER, fs.oid, 'USAGE,ALTER,DROP,COMMENT'))
                    AS "unsafeForeignServerPrivilegeCount",
                (SELECT pg_catalog.count(*) FROM pg_catalog.pg_directory dir
                    WHERE pg_catalog.has_directory_privilege(
                        CURRENT_USER, dir.dirname, 'READ,WRITE'))
                    AS "unsafeDirectoryPrivilegeCount",
                (EXTRACT(EPOCH FROM
                    pg_catalog.current_setting('statement_timeout')::pg_catalog.interval) * 1000)::bigint
                    AS "statementTimeoutMs",
                (SELECT pg_catalog.string_agg(
                    pg_catalog.lower(t.relname), ',' ORDER BY pg_catalog.lower(t.relname))
                    FROM target_tables t) AS "baseTableNames",
                (SELECT pg_catalog.count(*) FROM target_tables) AS "baseTableCount",
                pg_catalog.pg_is_in_recovery() AS "readReplica"
            FROM pg_catalog.pg_roles r
            JOIN pg_catalog.pg_database d ON d.datname = pg_catalog.current_database()
            JOIN pg_catalog.pg_namespace n ON n.nspname = pg_catalog.current_schema()
            WHERE r.rolname = CURRENT_USER
            """;
    public static final Set<String> REQUIRED_DATABASE_TOOLS = Set.of(
            "list_database_connections",
            "test_database_connection",
            "list_database_schemas",
            "list_schema_object_kinds",
            "list_schema_objects",
            "preview_table_data",
            "get_database_object_description",
            "list_recent_sql_queries",
            "execute_sql_query"
    );

    private final AgentBridgeClient client;

    public MyBatisDatabasePreflight(AgentBridgeClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public Result verify(
            URI mcpUri,
            URI webBaseUri,
            DatabaseContract contract
    ) throws Exception {
        Objects.requireNonNull(mcpUri, "mcpUri");
        Objects.requireNonNull(webBaseUri, "webBaseUri");
        Objects.requireNonNull(contract, "contract");
        verifyCredentialContract(contract);
        AgentBridgeClient.MyBatisAuditBinding bridgeBinding =
                client.bindMyBatisSqlReviewEndpoints(webBaseUri, mcpUri);

        Set<String> availableTools = new LinkedHashSet<>();
        Map<String, AgentBridgeClient.ToolDefinition> toolsByName = new HashMap<>();
        for (AgentBridgeClient.ToolDefinition tool : client.listTools(mcpUri)) {
            if (tool.name() == null || tool.name().isBlank()
                    || toolsByName.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("AgentBridge tool catalog contains a missing or duplicate tool name");
            }
            availableTools.add(tool.name());
        }
        Set<String> missingTools = new LinkedHashSet<>(REQUIRED_DATABASE_TOOLS);
        missingTools.removeAll(availableTools);
        if (!missingTools.isEmpty()) {
            throw new IllegalStateException("AgentBridge database tools unavailable: " + missingTools);
        }
        requireDatabaseToolSchemas(toolsByName);

        JsonNode connections = client.callTool(
                mcpUri,
                "list_database_connections",
                JsonNodeFactory.instance.objectNode()
        ).structured();
        List<JsonNode> matches = connectionMatches(connections, contract.connectionName());
        if (matches.size() != 1) {
            throw new IllegalStateException("expected exactly one connection named '"
                    + contract.connectionName() + "' but found " + matches.size());
        }
        JsonNode connection = matches.getFirst();
        DatabaseFingerprints databaseFingerprints = requireDatabaseIdentity(
                connection, bridgeBinding, null, "connection metadata"
        );
        String connectionId = requiredText(connection, "id", "connection id");
        String databaseSystem = firstText(connection, "databaseSystem", "dbms", "databaseProductName", "databaseType");
        String deployment = firstText(connection, "deployment", "architecture", "mode");
        if (!databaseSystem.toLowerCase(Locale.ROOT).contains("gaussdb")
                || !deployment.equalsIgnoreCase("centralized")) {
            throw new IllegalStateException("connection must identify a centralized GaussDB database");
        }
        Environment verifiedEnvironment = verifiedEnvironment(connection, contract.environment());
        if (!containsText(connection.path("databases"), contract.databaseName())) {
            throw new IllegalStateException("configured database is not available on the selected connection: "
                    + contract.databaseName());
        }

        ObjectNode connectionArguments = JsonNodeFactory.instance.objectNode();
        connectionArguments.put("connectionId", connectionId);
        JsonNode connectionTest = client.callTool(
                mcpUri,
                "test_database_connection",
                connectionArguments
        ).structured();
        if (!connectionTest.path("success").asBoolean(false)) {
            throw new IllegalStateException("configured database connection is unavailable: " + contract.connectionName());
        }

        ObjectNode schemaArguments = JsonNodeFactory.instance.objectNode();
        schemaArguments.put("connectionId", connectionId);
        schemaArguments.put("databaseName", contract.databaseName());
        JsonNode schemas = client.callTool(
                mcpUri,
                "list_database_schemas",
                schemaArguments
        ).structured();
        if (!contract.databaseName().equals(schemas.path("databaseName").asText())
                || !containsText(schemas.path("schemas"), contract.schemaName())) {
            throw new IllegalStateException("configured database/schema was not found: "
                    + contract.databaseName() + "/" + contract.schemaName());
        }

        ObjectNode objectKindArguments = JsonNodeFactory.instance.objectNode();
        objectKindArguments.put("connectionId", connectionId);
        JsonNode objectKinds = client.callTool(
                mcpUri,
                "list_schema_object_kinds",
                objectKindArguments
        ).structured();
        requireMetadataBinding(objectKinds, connectionId, contract);
        JsonNode kindValues = objectKinds.path("objectKinds");
        if (!kindValues.isArray()) {
            throw new IllegalStateException(
                    "list_schema_object_kinds must return the exact tested objectKinds array contract"
            );
        }
        int tableKindCount = 0;
        for (JsonNode kind : kindValues) {
            if (!kind.isObject()
                    || kind.path("code").asText().isBlank()
                    || kind.path("name").asText().isBlank()) {
                throw new IllegalStateException(
                        "schema object kinds must expose non-blank code and name fields"
                );
            }
            if ("TABLE".equals(kind.path("code").asText())) {
                tableKindCount++;
            }
        }
        if (tableKindCount != 1) {
            throw new IllegalStateException(
                    "list_schema_object_kinds must expose exactly one exact TABLE object kind"
            );
        }

        ObjectNode tableArguments = databaseArguments(
                connectionId, contract.databaseName(), contract.schemaName());
        tableArguments.put("kind", "TABLE");
        JsonNode tableObjects = client.callTool(
                mcpUri,
                "list_schema_objects",
                tableArguments
        ).structured();
        Set<String> safeBaseRelations = safeBaseRelations(
                tableObjects, connectionId, contract);

        ObjectNode probeArguments = databaseArguments(
                connectionId, contract.databaseName(), contract.schemaName());
        probeArguments.put("queryText", SAFETY_PROBE_SQL);
        AgentBridgeClient.ToolResponse probeResponse = client.callTool(
                mcpUri,
                "execute_sql_query",
                probeArguments
        );
        if (probeResponse.rawResult().path("isError").asBoolean(false)) {
            throw new IllegalStateException("database safety probe returned an MCP tool error");
        }
        SafetyProbe probe = verifySafetyProbe(
                probeResponse.structured(),
                contract,
                safeBaseRelations,
                bridgeBinding,
                databaseFingerprints
        );

        client.getToolCalls(webBaseUri);
        return new Result(
                connectionId,
                contract.databaseName(),
                contract.schemaName(),
                databaseSystem,
                verifiedEnvironment,
                new StatementTimeoutContract(
                        Duration.ofMillis(probe.statementTimeoutMs()),
                        StatementTimeoutScope.EFFECTIVE_SESSION,
                        true
                ),
                safeBaseRelations,
                bridgeBinding,
                databaseFingerprints
        );
    }

    /**
     * Re-establishes the common-source identity and physical-standby evidence immediately
     * before one SQL review task is submitted. This deliberately uses managed connection
     * metadata rather than an arbitrary SQL function so the server's simple-SELECT policy
     * remains authoritative for reviewer-generated queries.
     */
    public void recheck(
            URI mcpUri,
            URI webBaseUri,
            Result verified
    ) throws Exception {
        Objects.requireNonNull(mcpUri, "mcpUri");
        Objects.requireNonNull(webBaseUri, "webBaseUri");
        Objects.requireNonNull(verified, "verified");
        AgentBridgeClient.MyBatisAuditBinding currentBinding =
                client.bindMyBatisSqlReviewEndpoints(webBaseUri, mcpUri);
        if (!verified.bridgeBinding().equals(currentBinding)) {
            throw new IllegalStateException(
                    "per-task AgentBridge instance identity or SQL policy fingerprint changed after preflight"
            );
        }

        JsonNode response = client.callTool(
                mcpUri,
                "list_database_connections",
                JsonNodeFactory.instance.objectNode()
        ).structured();
        JsonNode connections = response.isArray() ? response : response.path("connections");
        List<JsonNode> matches = new ArrayList<>();
        if (connections.isArray()) {
            for (JsonNode connection : connections) {
                if (verified.connectionId().equals(connection.path("id").asText())) {
                    matches.add(connection);
                }
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "per-task database identity recheck expected exactly one bound connection id but found "
                            + matches.size()
            );
        }
        JsonNode connection = matches.getFirst();
        requireDatabaseIdentity(
                connection,
                verified.bridgeBinding(),
                verified.databaseFingerprints(),
                "per-task connection metadata"
        );
        String databaseSystem = firstText(
                connection, "databaseSystem", "dbms", "databaseProductName", "databaseType");
        String deployment = firstText(connection, "deployment", "architecture", "mode");
        if (!verified.databaseSystem().equals(databaseSystem)
                || !deployment.equalsIgnoreCase("centralized")) {
            throw new IllegalStateException(
                    "per-task database product/deployment changed after preflight"
            );
        }
        verifiedEnvironment(connection, verified.environment());
        if (!containsText(connection.path("databases"), verified.databaseName())) {
            throw new IllegalStateException(
                    "per-task connection metadata no longer exposes the bound database"
            );
        }

        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("connectionId", verified.connectionId());
        JsonNode connectionTest = client.callTool(
                mcpUri,
                "test_database_connection",
                arguments
        ).structured();
        if (!connectionTest.path("success").asBoolean(false)) {
            throw new IllegalStateException("per-task bound database connection is unavailable");
        }
    }

    private void requireDatabaseToolSchemas(
            Map<String, AgentBridgeClient.ToolDefinition> toolsByName
    ) {
        requireInputSchema(
                toolsByName.get("execute_sql_query"),
                Map.of(
                        "connectionId", "string",
                        "databaseName", "string",
                        "schemaName", "string",
                        "queryText", "string"
                )
        );
        AgentBridgeClient.ToolDefinition preview = toolsByName.get("preview_table_data");
        requireInputSchema(
                preview,
                Map.of(
                        "connectionId", "string",
                        "databaseName", "string",
                        "schemaName", "string",
                        "tableName", "string",
                        "maxRowCount", "integer"
                )
        );
        JsonNode maximumRows = preview.inputSchema().path("properties").path("maxRowCount");
        if (!maximumRows.path("minimum").isIntegralNumber()
                || maximumRows.path("minimum").longValue() != 1
                || !maximumRows.path("maximum").isIntegralNumber()
                || maximumRows.path("maximum").longValue() != 20) {
            throw new IllegalStateException(
                    "AgentBridge preview_table_data input schema cannot prove server-enforced maxRowCount 1..20"
            );
        }
        for (String unsupported : List.of("limit", "rowLimit", "pageSize", "maxRows")) {
            if (preview.inputSchema().path("properties").has(unsupported)) {
                throw new IllegalStateException(
                        "AgentBridge preview_table_data input schema exposes multiple limit fields: " + unsupported
                );
            }
        }
    }

    private void requireInputSchema(
            AgentBridgeClient.ToolDefinition tool,
            Map<String, String> requiredProperties
    ) {
        JsonNode schema = tool.inputSchema();
        if (schema == null || !schema.isObject()
                || !"object".equals(schema.path("type").asText())
                || !schema.path("properties").isObject()
                || !schema.path("required").isArray()) {
            throw new IllegalStateException(
                    "AgentBridge " + tool.name() + " input schema is unsupported or incomplete"
            );
        }
        Set<String> required = new HashSet<>();
        for (JsonNode field : schema.path("required")) {
            if (!field.isTextual() || field.asText().isBlank() || !required.add(field.asText())) {
                throw new IllegalStateException(
                        "AgentBridge " + tool.name() + " input schema has invalid required fields"
                );
            }
        }
        for (Map.Entry<String, String> property : requiredProperties.entrySet()) {
            if (!required.contains(property.getKey())
                    || !property.getValue().equals(
                    schema.path("properties").path(property.getKey()).path("type").asText())) {
                throw new IllegalStateException(
                        "AgentBridge " + tool.name() + " input schema cannot prove required "
                                + property.getKey() + " " + property.getValue() + " argument"
                );
            }
        }
    }

    private Environment verifiedEnvironment(JsonNode connection, Environment configured) {
        if (!connection.path("readOnly").isBoolean() || !connection.path("readOnly").booleanValue()
                || !VERIFIED_ENVIRONMENT_SOURCE.equals(connection.path("environmentSource").asText())
                || !"physical-standby".equals(connection.path("topologyRole").asText())
                || !"server-observed".equals(connection.path("topologySource").asText())) {
            throw new IllegalStateException(
                    "connection environment metadata must prove a server-observed physical read-replica target"
            );
        }
        Environment actual = switch (connection.path("environment").asText("").toLowerCase(Locale.ROOT)) {
            case "read-replica" -> Environment.READ_REPLICA;
            default -> throw new IllegalStateException(
                    "connection environment metadata must prove a physical read-replica"
            );
        };
        if (actual != configured) {
            throw new IllegalStateException(
                    "connection environment metadata does not match configured environment"
            );
        }
        return actual;
    }

    private SafetyProbe verifySafetyProbe(
            JsonNode response,
            DatabaseContract contract,
            Set<String> expectedBaseRelations,
            AgentBridgeClient.MyBatisAuditBinding bridgeBinding,
            DatabaseFingerprints databaseFingerprints
    ) {
        requireDatabaseIdentity(response, bridgeBinding, databaseFingerprints, "database safety probe");
        if (!response.isObject()
                || !response.path("columns").isArray()
                || !response.path("rows").isArray()
                || response.path("rows").size() != 1
                || !response.path("rows").get(0).isObject()) {
            throw new IllegalStateException(
                    "database safety probe must return the strict columns/one-row response contract"
            );
        }
        List<String> columns = new ArrayList<>();
        for (JsonNode column : response.path("columns")) {
            if (!column.isTextual()) {
                throw new IllegalStateException("database safety probe columns must be strings");
            }
            columns.add(column.asText());
        }
        if (!SAFETY_PROBE_COLUMNS.equals(columns)) {
            throw new IllegalStateException(
                    "database safety probe columns do not match contract " + SAFETY_PROBE_CONTRACT_VERSION
            );
        }
        JsonNode row = response.path("rows").get(0);
        Set<String> rowFields = new LinkedHashSet<>();
        row.fieldNames().forEachRemaining(rowFields::add);
        Set<String> expectedFields = new LinkedHashSet<>(SAFETY_PROBE_COLUMNS);
        if (!rowFields.equals(expectedFields)) {
            Set<String> missingFields = new LinkedHashSet<>(expectedFields);
            missingFields.removeAll(rowFields);
            Set<String> unexpectedFields = new LinkedHashSet<>(rowFields);
            unexpectedFields.removeAll(expectedFields);
            throw new IllegalStateException(
                    "database safety probe row fields do not match the strict response contract; missing="
                            + missingFields + ", unexpected=" + unexpectedFields
            );
        }
        requireProbeText(row, "probeContractVersion", SAFETY_PROBE_CONTRACT_VERSION);
        requireProbeText(row, "currentDatabase", contract.databaseName());
        requireProbeText(row, "currentSchema", contract.schemaName());
        String currentUser = requiredProbeText(row, "currentUser");
        for (String field : List.of(
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
            if (requiredProbeBoolean(row, field)) {
                throw new IllegalStateException("database safety probe rejected unsafe " + field);
            }
        }
        for (String field : List.of(
                "roleMembershipCount",
                "ownedNonSystemSchemaCount",
                "ownedBaseTableCount",
                "unsafeNonSystemSchemaCreateCount",
                "unsafeTablePrivilegeCount",
                "unsafeColumnPrivilegeCount",
                "unsafeSequencePrivilegeCount",
                "rlsEnabledBaseTableCount",
                "forceRlsEnabledBaseTableCount",
                "executableFunctionCount",
                "executablePackageCount",
                "unsafeForeignServerPrivilegeCount",
                "unsafeDirectoryPrivilegeCount"
        )) {
            if (requiredProbeLong(row, field) != 0) {
                throw new IllegalStateException("database safety probe rejected non-zero " + field);
            }
        }
        if (!requiredProbeBoolean(row, "sessionReadOnly")) {
            throw new IllegalStateException("database safety probe requires sessionReadOnly=true");
        }
        if (!requiredProbeBoolean(row, "transactionReadOnly")) {
            throw new IllegalStateException("database safety probe requires transactionReadOnly=true");
        }
        long timeoutMillis = requiredProbeLong(row, "statementTimeoutMs");
        if (timeoutMillis < 1 || timeoutMillis > Duration.ofSeconds(30).toMillis()
                || timeoutMillis > contract.statementTimeout().maximum().toMillis()) {
            throw new IllegalStateException(
                    "database safety probe statementTimeoutMs must be positive and no more than configured/30000"
            );
        }
        List<String> expectedTableNames = expectedBaseRelations.stream()
                .map(relation -> relation.substring(relation.indexOf('.') + 1))
                .sorted()
                .toList();
        requireProbeText(row, "baseTableNames", String.join(",", expectedTableNames));
        long baseTableCount = requiredProbeLong(row, "baseTableCount");
        if (baseTableCount != expectedBaseRelations.size()) {
            throw new IllegalStateException(
                    "database safety probe baseTableCount does not match the verified inventory"
            );
        }
        boolean readReplica = requiredProbeBoolean(row, "readReplica");
        if (!readReplica) {
            throw new IllegalStateException(
                    "database safety probe could not prove a physical read-replica with pg_is_in_recovery()"
            );
        }
        return new SafetyProbe(currentUser, timeoutMillis, readReplica);
    }

    private DatabaseFingerprints requireDatabaseIdentity(
            JsonNode node,
            AgentBridgeClient.MyBatisAuditBinding bridgeBinding,
            DatabaseFingerprints expected,
            String label
    ) {
        JsonNode identity = node.path("identity");
        AgentBridgeClient.BridgeIdentity actualIdentity = new AgentBridgeClient.BridgeIdentity(
                requiredText(identity, "instanceId", label + " instanceId"),
                requiredText(identity, "projectId", label + " projectId"),
                requiredText(identity, "instanceNonce", label + " instanceNonce")
        );
        if (!bridgeBinding.identity().equals(actualIdentity)
                || !bridgeBinding.policyFingerprint().equals(node.path("policyFingerprint").asText())) {
            throw new IllegalStateException(label + " AgentBridge identity or policy fingerprint mismatch");
        }
        if (!"server-generated".equals(node.path("fingerprintSource").asText())) {
            throw new IllegalStateException(label + " fingerprints must be server-generated");
        }
        DatabaseFingerprints actual = new DatabaseFingerprints(
                requiredSha256(node, "databaseHostFingerprint", label),
                requiredSha256(node, "databaseInstanceFingerprint", label),
                requiredSha256(node, "topologyFingerprint", label)
        );
        if (expected != null && !expected.equals(actual)) {
            throw new IllegalStateException(label + " database host/instance/topology fingerprint mismatch");
        }
        return actual;
    }

    private String requiredSha256(JsonNode node, String field, String label) {
        String value = node.path(field).asText("").strip().toLowerCase(Locale.ROOT);
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalStateException(label + " missing server-generated " + field);
        }
        return value;
    }

    private void requireProbeText(JsonNode row, String field, String expected) {
        String actual = requiredProbeText(row, field);
        if (!expected.equals(actual)) {
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

    private void verifyCredentialContract(DatabaseContract contract) {
        if (contract.environment() != Environment.READ_REPLICA) {
            throw new IllegalStateException("database environment must be a physical read-replica");
        }
        if (!contract.nonOwnerNonAdminReadOnlyAccount()) {
            throw new IllegalStateException(
                    "runtime credentials must use a non-owner/non-admin read-only account; this is an external deployment contract"
            );
        }
        if (!contract.rowLevelSecurityDisabledForSafeBaseTables()) {
            throw new IllegalStateException(
                    "RLS disabled for every safe base table must be explicitly confirmed before preflight"
            );
        }
        if (!contract.userDefinedAndSecurityDefinerFunctionExecutionRevokedIncludingPublic()) {
            throw new IllegalStateException(
                    "user-defined and security-definer function execution must be revoked from the audit account, including PUBLIC"
            );
        }
        StatementTimeoutContract timeout = contract.statementTimeout();
        if (!timeout.confirmed()) {
            throw new IllegalStateException(
                    "database/server/role statement_timeout <= 30 seconds must be confirmed before preflight"
            );
        }
        if (timeout.maximum().isZero() || timeout.maximum().isNegative()
                || timeout.maximum().compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException(
                    "confirmed database/server/role statement_timeout must be positive and <= 30 seconds"
            );
        }
    }

    private ObjectNode databaseArguments(String connectionId, String databaseName, String schemaName) {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("connectionId", connectionId);
        arguments.put("databaseName", databaseName);
        arguments.put("schemaName", schemaName);
        return arguments;
    }

    private Set<String> safeBaseRelations(
            JsonNode response,
            String connectionId,
            DatabaseContract contract
    ) {
        requireMetadataBinding(response, connectionId, contract);
        if (!"TABLE".equals(response.path("kind").asText())) {
            throw new IllegalStateException(
                    "list_schema_objects must explicitly report the exact TABLE object kind"
            );
        }
        JsonNode objects = response.path("objects");
        if (!objects.isArray() || objects.isEmpty()) {
            throw new IllegalStateException(
                    "list_schema_objects must return at least one explicitly classified base TABLE"
            );
        }
        Set<String> relations = new LinkedHashSet<>();
        for (JsonNode object : objects) {
            if (!object.isObject() || !"TABLE".equals(object.path("kind").asText())) {
                throw new IllegalStateException(
                        "every safe relation must explicitly report kind TABLE"
                );
            }
            String name = requiredText(object, "name", "base table name");
            if (!isUnquotedIdentifier(name)) {
                throw new IllegalStateException(
                        "safe base table names must be plain unquoted identifiers"
                );
            }
            String relation = canonicalRelation(contract.schemaName(), name);
            if (!relations.add(relation)) {
                throw new IllegalStateException(
                        "safe base table inventory contains a duplicate normalized relation: " + relation
                );
            }
        }
        return Set.copyOf(relations);
    }

    private void requireMetadataBinding(
            JsonNode response,
            String connectionId,
            DatabaseContract contract
    ) {
        if (!response.isObject()
                || !connectionId.equals(response.path("connectionId").asText())
                || !contract.databaseName().equals(response.path("databaseName").asText())
                || !contract.schemaName().equals(response.path("schemaName").asText())) {
            throw new IllegalStateException(
                    "schema metadata response does not match the configured schema/database target"
            );
        }
    }

    private static boolean isUnquotedIdentifier(String value) {
        return value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    static String canonicalRelation(String schemaName, String relationName) {
        return schemaName.toLowerCase(Locale.ROOT) + "." + relationName.toLowerCase(Locale.ROOT);
    }

    private List<JsonNode> connectionMatches(JsonNode response, String connectionName) {
        List<JsonNode> matches = new ArrayList<>();
        JsonNode connections = response.isArray() ? response : response.path("connections");
        if (connections.isArray()) {
            for (JsonNode connection : connections) {
                if (connectionName.equals(connection.path("name").asText())) {
                    matches.add(connection);
                }
            }
        }
        return matches;
    }

    private boolean containsText(JsonNode values, String expected) {
        if (!values.isArray()) {
            return false;
        }
        for (JsonNode value : values) {
            String actual = value.isTextual() ? value.asText() : firstText(value, "name", "databaseName", "schemaName");
            if (expected.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private String requiredText(JsonNode node, String field, String label) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("missing " + label + " in AgentBridge response");
        }
        return value;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    public enum Environment {
        READ_REPLICA,
        TEST,
        PRODUCTION_PRIMARY
    }

    public enum StatementTimeoutScope {
        DATABASE,
        SERVER,
        ROLE,
        EFFECTIVE_SESSION
    }

    public record StatementTimeoutContract(
            Duration maximum,
            StatementTimeoutScope scope,
            boolean confirmed
    ) {
        public StatementTimeoutContract {
            Objects.requireNonNull(maximum, "maximum");
            Objects.requireNonNull(scope, "scope");
        }
    }

    public record DatabaseContract(
            String connectionName,
            String databaseName,
            String schemaName,
            Environment environment,
            boolean nonOwnerNonAdminReadOnlyAccount,
            boolean rowLevelSecurityDisabledForSafeBaseTables,
            boolean userDefinedAndSecurityDefinerFunctionExecutionRevokedIncludingPublic,
            StatementTimeoutContract statementTimeout
    ) {
        public DatabaseContract {
            Objects.requireNonNull(connectionName, "connectionName");
            Objects.requireNonNull(databaseName, "databaseName");
            Objects.requireNonNull(schemaName, "schemaName");
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(statementTimeout, "statementTimeout");
        }
    }

    private record SafetyProbe(String currentUser, long statementTimeoutMs, boolean readReplica) {
    }

    public record DatabaseFingerprints(
            String hostFingerprint,
            String instanceFingerprint,
            String topologyFingerprint
    ) {
    }

    public static final class Result {
        private final String connectionId;
        private final String databaseName;
        private final String schemaName;
        private final String databaseSystem;
        private final Environment environment;
        private final StatementTimeoutContract statementTimeout;
        private final Set<String> safeBaseRelations;
        private final AgentBridgeClient.MyBatisAuditBinding bridgeBinding;
        private final DatabaseFingerprints databaseFingerprints;

        private Result(
                String connectionId,
                String databaseName,
                String schemaName,
                String databaseSystem,
                Environment environment,
                StatementTimeoutContract statementTimeout,
                Set<String> safeBaseRelations,
                AgentBridgeClient.MyBatisAuditBinding bridgeBinding,
                DatabaseFingerprints databaseFingerprints
        ) {
            this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
            this.databaseName = Objects.requireNonNull(databaseName, "databaseName");
            this.schemaName = Objects.requireNonNull(schemaName, "schemaName");
            this.databaseSystem = Objects.requireNonNull(databaseSystem, "databaseSystem");
            this.environment = Objects.requireNonNull(environment, "environment");
            this.statementTimeout = Objects.requireNonNull(statementTimeout, "statementTimeout");
            this.bridgeBinding = Objects.requireNonNull(bridgeBinding, "bridgeBinding");
            this.databaseFingerprints = Objects.requireNonNull(databaseFingerprints, "databaseFingerprints");
            Objects.requireNonNull(safeBaseRelations, "safeBaseRelations");
            Set<String> normalizedRelations = new LinkedHashSet<>();
            for (String relation : safeBaseRelations) {
                if (relation == null) {
                    throw new IllegalArgumentException("safe base relations must be non-null");
                }
                String[] parts = relation.split("\\.", -1);
                if (parts.length != 2
                        || !isUnquotedIdentifier(parts[0])
                        || !isUnquotedIdentifier(parts[1])
                        || !schemaName.equalsIgnoreCase(parts[0])) {
                    throw new IllegalArgumentException(
                            "safe base relations must be configured-schema qualified plain identifiers"
                    );
                }
                normalizedRelations.add(canonicalRelation(parts[0], parts[1]));
            }
            this.safeBaseRelations = Set.copyOf(normalizedRelations);
        }

        public String connectionId() {
            return connectionId;
        }

        public String databaseName() {
            return databaseName;
        }

        public String schemaName() {
            return schemaName;
        }

        public String databaseSystem() {
            return databaseSystem;
        }

        public Environment environment() {
            return environment;
        }

        public StatementTimeoutContract statementTimeout() {
            return statementTimeout;
        }

        public Set<String> safeBaseRelations() {
            return safeBaseRelations;
        }

        public AgentBridgeClient.MyBatisAuditBinding bridgeBinding() {
            return bridgeBinding;
        }

        public DatabaseFingerprints databaseFingerprints() {
            return databaseFingerprints;
        }
    }
}
