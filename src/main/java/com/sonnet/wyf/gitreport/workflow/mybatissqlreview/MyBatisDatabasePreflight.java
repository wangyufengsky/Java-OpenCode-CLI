package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class MyBatisDatabasePreflight {
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

        Set<String> availableTools = new LinkedHashSet<>();
        for (AgentBridgeClient.ToolDefinition tool : client.listTools(mcpUri)) {
            availableTools.add(tool.name());
        }
        Set<String> missingTools = new LinkedHashSet<>(REQUIRED_DATABASE_TOOLS);
        missingTools.removeAll(availableTools);
        if (!missingTools.isEmpty()) {
            throw new IllegalStateException("AgentBridge database tools unavailable: " + missingTools);
        }

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
        String connectionId = requiredText(connection, "id", "connection id");
        String databaseSystem = firstText(connection, "databaseSystem", "dbms", "databaseProductName", "databaseType");
        String deployment = firstText(connection, "deployment", "architecture", "mode");
        if (!databaseSystem.toLowerCase(Locale.ROOT).contains("gaussdb")
                || !deployment.equalsIgnoreCase("centralized")) {
            throw new IllegalStateException("connection must identify a centralized GaussDB database");
        }
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

        client.getToolCalls(webBaseUri);
        return new Result(
                connectionId,
                contract.databaseName(),
                contract.schemaName(),
                databaseSystem,
                contract.environment(),
                contract.statementTimeout(),
                safeBaseRelations
        );
    }

    private void verifyCredentialContract(DatabaseContract contract) {
        if (contract.environment() != Environment.READ_REPLICA && contract.environment() != Environment.TEST) {
            throw new IllegalStateException("database environment must be read-replica or test");
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
        ROLE
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

    public static final class Result {
        private final String connectionId;
        private final String databaseName;
        private final String schemaName;
        private final String databaseSystem;
        private final Environment environment;
        private final StatementTimeoutContract statementTimeout;
        private final Set<String> safeBaseRelations;

        private Result(
                String connectionId,
                String databaseName,
                String schemaName,
                String databaseSystem,
                Environment environment,
                StatementTimeoutContract statementTimeout,
                Set<String> safeBaseRelations
        ) {
            this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
            this.databaseName = Objects.requireNonNull(databaseName, "databaseName");
            this.schemaName = Objects.requireNonNull(schemaName, "schemaName");
            this.databaseSystem = Objects.requireNonNull(databaseSystem, "databaseSystem");
            this.environment = Objects.requireNonNull(environment, "environment");
            this.statementTimeout = Objects.requireNonNull(statementTimeout, "statementTimeout");
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
    }
}
