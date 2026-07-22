package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;

import java.net.URI;
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

        client.getToolCalls(webBaseUri);
        return new Result(connectionId, contract.databaseName(), contract.schemaName(), databaseSystem);
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

    public record DatabaseContract(
            String connectionName,
            String databaseName,
            String schemaName,
            Environment environment,
            boolean nonOwnerNonAdminReadOnlyAccount
    ) {
        public DatabaseContract {
            Objects.requireNonNull(connectionName, "connectionName");
            Objects.requireNonNull(databaseName, "databaseName");
            Objects.requireNonNull(schemaName, "schemaName");
            Objects.requireNonNull(environment, "environment");
        }
    }

    public record Result(
            String connectionId,
            String databaseName,
            String schemaName,
            String databaseSystem
    ) {
    }
}
