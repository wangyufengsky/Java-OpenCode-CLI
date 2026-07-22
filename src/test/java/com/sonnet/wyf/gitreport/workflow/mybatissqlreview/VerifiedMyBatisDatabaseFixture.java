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
    private static final URI TEST_URI = URI.create("http://verified-preflight.invalid");

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
                            MyBatisDatabasePreflight.Environment.TEST,
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
        public List<ToolDefinition> listTools(URI ignored) {
            return MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS.stream()
                    .map(name -> new ToolDefinition(
                            name,
                            name,
                            objectMapper.createObjectNode().put("type", "object")
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
            response.putArray("connections").addObject()
                    .put("id", connectionId)
                    .put("name", connectionName)
                    .put("databaseSystem", "GaussDB")
                    .put("deployment", "centralized")
                    .putArray("databases").add(databaseName);
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
    }
}
