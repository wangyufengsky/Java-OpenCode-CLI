package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class MyBatisToolCallAudit {
    public static final int MAX_QUERY_SCENARIOS = 3;
    public static final int MAX_ROWS_PER_CALL = 20;
    public static final long MAX_QUERY_DURATION_MS = 30_000L;
    public static final int MAX_TOOL_RESULT_BYTES = 262_144;

    private static final Set<String> ALLOWED_TOOLS = MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS;
    private static final Set<String> SCHEMA_BOUND_TOOLS = Set.of(
            "list_schema_object_kinds",
            "list_schema_objects",
            "preview_table_data",
            "get_database_object_description",
            "execute_sql_query"
    );
    private static final Set<String> PREVIEW_ARGUMENTS = Set.of(
            "connectionId",
            "databaseName",
            "schemaName",
            "tableName",
            "maxRowCount"
    );

    private final ObjectMapper objectMapper;
    private final ReadOnlySqlPolicy sqlPolicy = new ReadOnlySqlPolicy(MAX_ROWS_PER_CALL);

    public MyBatisToolCallAudit(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Result audit(
            List<AgentBridgeClient.ToolCallRecord> history,
            Boundary boundary,
            MyBatisDatabasePreflight.Result database,
            StatementContext statement
    ) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(statement, "statement");

        Set<String> historyIds = new HashSet<>();
        Set<String> observedPreexistingIds = new HashSet<>();
        List<AgentBridgeClient.ToolCallRecord> newCalls = new ArrayList<>();
        for (AgentBridgeClient.ToolCallRecord call : history) {
            requireHistoryIdentity(call, database);
            if (!historyIds.add(call.id())) {
                throw violation(call, "duplicate tool-call id");
            }
            if (boundary.preexistingCallIds().contains(call.id())) {
                if (call.timestamp().isAfter(boundary.startedAt())) {
                    throw violation(call, "preexisting id was reused after the task boundary");
                }
                observedPreexistingIds.add(call.id());
                continue;
            }
            if (call.timestamp().isBefore(boundary.startedAt())) {
                throw violation(call, "incomplete pre-task id snapshot contains an unknown old call");
            }
            newCalls.add(call);
        }
        if (!observedPreexistingIds.containsAll(boundary.preexistingCallIds())) {
            Set<String> missing = new LinkedHashSet<>(boundary.preexistingCallIds());
            missing.removeAll(observedPreexistingIds);
            throw new IllegalStateException("incomplete tool-call history; missing preexisting call ids: " + missing);
        }

        List<AuditedCallFact> facts = new ArrayList<>();
        int queryScenarios = 0;
        for (AgentBridgeClient.ToolCallRecord call : newCalls) {
            requireCompleteNewCall(call);
            if (!ALLOWED_TOOLS.contains(call.toolName())) {
                throw violation(call, "unapproved tool: " + call.toolName());
            }
            requireDatabaseBinding(call, database);
            String queryText = "";
            if ("execute_sql_query".equals(call.toolName())) {
                if (statement.selectKey()) {
                    throw violation(call, "selectKey tasks must not call execute_sql_query");
                }
                queryScenarios++;
                if (queryScenarios > MAX_QUERY_SCENARIOS) {
                    throw violation(call, "execute_sql_query is limited to at most 3 scenarios");
                }
                if (call.durationMs() > MAX_QUERY_DURATION_MS) {
                    throw violation(call, "execute_sql_query duration exceeds 30000 ms");
                }
                queryText = call.arguments().path("queryText").asText("");
                sqlPolicy.validate(
                        queryText,
                        call.id(),
                        database.schemaName(),
                        database.safeBaseRelations()
                );
            } else if ("preview_table_data".equals(call.toolName())) {
                requireSafePreviewRelation(call, database);
            }

            JsonNode resultData = call.result().deepCopy();
            List<String> columns = List.of();
            List<JsonNode> rows = List.of();
            if ("execute_sql_query".equals(call.toolName()) || "preview_table_data".equals(call.toolName())) {
                requireBoundedResultEvidence(call);
                RowData rowData = parseRowData(call);
                resultData = rowData.payload();
                columns = rowData.columns();
                rows = rowData.rows();
                if (rows.size() > MAX_ROWS_PER_CALL) {
                    throw violation(call, "tool result may retain at most 20 rows but found " + rows.size());
                }
            }
            facts.add(new AuditedCallFact(
                    call.id(),
                    call.toolName(),
                    call.timestamp(),
                    call.durationMs(),
                    call.arguments().path("connectionId").asText(""),
                    call.arguments().path("databaseName").asText(""),
                    call.arguments().path("schemaName").asText(""),
                    queryText,
                    call.arguments(),
                    resultData,
                    columns,
                    rows
            ));
        }
        return new Result(facts, statement, database);
    }

    private void requireBoundedResultEvidence(AgentBridgeClient.ToolCallRecord call) {
        try {
            int serializedBytes = objectMapper.writeValueAsBytes(call.result()).length;
            if (serializedBytes > MAX_TOOL_RESULT_BYTES) {
                throw violation(call, "SQL tool result evidence exceeds 262144 bytes");
            }
        } catch (IOException exception) {
            throw violation(call, "SQL tool result evidence could not be serialized safely");
        }
    }

    private void requireSafePreviewRelation(
            AgentBridgeClient.ToolCallRecord call,
            MyBatisDatabasePreflight.Result database
    ) {
        String tableName = call.arguments().path("tableName").asText("");
        if (tableName.isBlank()) {
            throw violation(call, "preview_table_data requires tableName");
        }
        if (!tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw violation(
                    call,
                    "preview tableName must be a plain relation name in the configured schema"
            );
        }
        String canonicalRelation = MyBatisDatabasePreflight.canonicalRelation(
                database.schemaName(), tableName);
        if (!database.safeBaseRelations().contains(canonicalRelation)) {
            throw violation(
                    call,
                    "relation is not an explicitly verified safe base relation: " + canonicalRelation
            );
        }
        call.arguments().fieldNames().forEachRemaining(field -> {
            if (!PREVIEW_ARGUMENTS.contains(field)) {
                throw violation(call, "unsupported preview argument: " + field);
            }
        });
        JsonNode maximumRows = call.arguments().path("maxRowCount");
        if (!maximumRows.isIntegralNumber()
                || !maximumRows.canConvertToInt()
                || maximumRows.intValue() < 1
                || maximumRows.intValue() > MAX_ROWS_PER_CALL) {
            throw violation(call, "preview maxRowCount must be an integer in 1..20");
        }
    }

    private void requireHistoryIdentity(
            AgentBridgeClient.ToolCallRecord call,
            MyBatisDatabasePreflight.Result database
    ) {
        if (call == null || call.id() == null || call.id().isBlank() || call.timestamp() == null) {
            throw new IllegalStateException("tool-call history contains a call with missing id or timestamp");
        }
        AgentBridgeClient.MyBatisAuditBinding binding = database.bridgeBinding();
        MyBatisDatabasePreflight.DatabaseFingerprints fingerprints = database.databaseFingerprints();
        AgentBridgeClient.BridgeIdentity identity = binding.identity();
        if (!identity.instanceId().equals(call.bridgeInstanceId())
                || !identity.projectId().equals(call.bridgeProjectId())
                || !identity.instanceNonce().equals(call.bridgeInstanceNonce())
                || !binding.policyFingerprint().equals(call.policyFingerprint())) {
            throw violation(call, "tool-call history AgentBridge identity or policy fingerprint mismatch");
        }
        if (!fingerprints.hostFingerprint().equals(call.databaseHostFingerprint())
                || !fingerprints.instanceFingerprint().equals(call.databaseInstanceFingerprint())
                || !fingerprints.topologyFingerprint().equals(call.topologyFingerprint())) {
            throw violation(call, "tool-call history database host/instance/topology fingerprint mismatch");
        }
    }

    private void requireCompleteNewCall(AgentBridgeClient.ToolCallRecord call) {
        if (call.toolName() == null || call.toolName().isBlank()
                || call.title() == null || call.title().isBlank()
                || call.kind() == null || call.kind().isBlank()
                || call.status() == null || call.status().isBlank()) {
            throw violation(call, "new tool call has incomplete identity/status fields");
        }
        if (call.durationMs() == null || call.durationMs() < 0) {
            throw violation(call, "new tool call requires a non-negative durationMs");
        }
        if (call.arguments() == null || !call.arguments().isObject()) {
            throw violation(call, "new tool call arguments must be an object");
        }
        if (call.result() == null || call.result().isMissingNode() || call.result().isNull()) {
            throw violation(call, "new tool call result is missing");
        }
        if (!"completed".equalsIgnoreCase(call.status())) {
            throw violation(call, "tool call did not complete successfully: " + call.status());
        }
    }

    private void requireDatabaseBinding(
            AgentBridgeClient.ToolCallRecord call,
            MyBatisDatabasePreflight.Result database
    ) {
        JsonNode arguments = call.arguments();
        String tool = call.toolName();
        if ("list_database_connections".equals(tool)) {
            return;
        }
        requireArgument(call, arguments, "connectionId", database.connectionId());
        if (!"test_database_connection".equals(tool) && !"list_recent_sql_queries".equals(tool)) {
            requireArgument(call, arguments, "databaseName", database.databaseName());
        }
        if (SCHEMA_BOUND_TOOLS.contains(tool)) {
            requireArgument(call, arguments, "schemaName", database.schemaName());
        }
    }

    private void requireArgument(
            AgentBridgeClient.ToolCallRecord call,
            JsonNode arguments,
            String field,
            String expected
    ) {
        if (!expected.equals(arguments.path(field).asText())) {
            throw violation(call, "arguments do not match the bound database target: " + field);
        }
    }

    private RowData parseRowData(AgentBridgeClient.ToolCallRecord call) {
        JsonNode result = call.result();
        JsonNode payload;
        try {
            if (result.isTextual()) {
                payload = objectMapper.readTree(result.asText());
            } else if (hasDirectRows(result)) {
                payload = result;
            } else if (result.isObject() && hasDirectRows(result.path("structuredContent"))) {
                payload = result.path("structuredContent");
            } else if (result.isObject() && result.path("content").isArray()
                    && result.path("content").size() == 1
                    && "text".equals(result.at("/content/0/type").asText())
                    && result.at("/content/0/text").isTextual()) {
                payload = objectMapper.readTree(result.at("/content/0/text").asText());
            } else {
                throw violation(call, "unsupported result wrapper for row-producing tool");
            }
        } catch (IOException exception) {
            throw violation(call, "unsupported result wrapper contains invalid JSON");
        }
        if (!hasDirectRows(payload)) {
            throw violation(call, "unsupported result wrapper for row-producing tool");
        }

        List<String> columns = new ArrayList<>();
        Set<String> uniqueColumns = new HashSet<>();
        for (JsonNode column : payload.path("columns")) {
            if (!column.isTextual() || column.asText().isBlank() || !uniqueColumns.add(column.asText())) {
                throw violation(call, "result columns must be unique non-blank strings");
            }
            columns.add(column.asText());
        }
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : payload.path("rows")) {
            if (!row.isObject() && !row.isArray()) {
                throw violation(call, "result rows must contain objects or arrays");
            }
            rows.add(row.deepCopy());
        }
        return new RowData(payload.deepCopy(), columns, rows);
    }

    private boolean hasDirectRows(JsonNode value) {
        return value != null && value.isObject()
                && value.path("columns").isArray()
                && value.path("rows").isArray();
    }

    private IllegalStateException violation(AgentBridgeClient.ToolCallRecord call, String message) {
        return new IllegalStateException("tool call " + call.id() + " rejected: " + message);
    }

    public record Boundary(Instant startedAt, Set<String> preexistingCallIds) {
        public Boundary {
            Objects.requireNonNull(startedAt, "startedAt");
            preexistingCallIds = Set.copyOf(preexistingCallIds == null ? Set.of() : preexistingCallIds);
            if (preexistingCallIds.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("preexisting call ids must be non-blank");
            }
        }
    }

    public record StatementContext(String statementKey, String commandType, boolean selectKey) {
        public StatementContext {
            Objects.requireNonNull(statementKey, "statementKey");
            Objects.requireNonNull(commandType, "commandType");
            if (statementKey.isBlank() || commandType.isBlank()) {
                throw new IllegalArgumentException("statement context fields must be non-blank");
            }
        }
    }

    public static final class AuditedCallFact {
        private final String id;
        private final String toolName;
        private final Instant timestamp;
        private final long durationMs;
        private final String connectionId;
        private final String databaseName;
        private final String schemaName;
        private final String queryText;
        private final JsonNode arguments;
        private final JsonNode resultData;
        private final List<String> columns;
        private final List<JsonNode> rows;

        private AuditedCallFact(
                String id,
                String toolName,
                Instant timestamp,
                long durationMs,
                String connectionId,
                String databaseName,
                String schemaName,
                String queryText,
                JsonNode arguments,
                JsonNode resultData,
                List<String> columns,
                List<JsonNode> rows
        ) {
            this.id = Objects.requireNonNull(id, "id");
            this.toolName = Objects.requireNonNull(toolName, "toolName");
            this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
            this.durationMs = durationMs;
            this.connectionId = connectionId == null ? "" : connectionId;
            this.databaseName = databaseName == null ? "" : databaseName;
            this.schemaName = schemaName == null ? "" : schemaName;
            this.queryText = queryText == null ? "" : queryText;
            this.arguments = Objects.requireNonNull(arguments, "arguments").deepCopy();
            this.resultData = Objects.requireNonNull(resultData, "resultData").deepCopy();
            this.columns = List.copyOf(columns);
            this.rows = copyRows(rows);
        }

        public String id() {
            return id;
        }

        public String toolName() {
            return toolName;
        }

        public Instant timestamp() {
            return timestamp;
        }

        public long durationMs() {
            return durationMs;
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

        public String queryText() {
            return queryText;
        }

        public JsonNode arguments() {
            return arguments.deepCopy();
        }

        public JsonNode resultData() {
            return resultData.deepCopy();
        }

        public List<String> columns() {
            return columns;
        }

        public List<JsonNode> rows() {
            return copyRows(rows);
        }

        private static List<JsonNode> copyRows(List<JsonNode> source) {
            List<JsonNode> copies = new ArrayList<>();
            for (JsonNode row : Objects.requireNonNull(source, "rows")) {
                copies.add(Objects.requireNonNull(row, "row").deepCopy());
            }
            return List.copyOf(copies);
        }
    }

    public static final class Result {
        private final List<AuditedCallFact> calls;
        private final StatementContext statementContext;
        private final MyBatisDatabasePreflight.Result database;

        private Result(
                List<AuditedCallFact> calls,
                StatementContext statementContext,
                MyBatisDatabasePreflight.Result database
        ) {
            this.calls = List.copyOf(calls);
            this.statementContext = Objects.requireNonNull(statementContext, "statementContext");
            this.database = Objects.requireNonNull(database, "database");
        }

        public List<AuditedCallFact> calls() {
            return calls;
        }

        public int queryScenarioCount() {
            return Math.toIntExact(calls.stream()
                    .filter(call -> "execute_sql_query".equals(call.toolName()))
                    .count());
        }

        public StatementContext statementContext() {
            return statementContext;
        }

        public MyBatisDatabasePreflight.Result database() {
            return database;
        }

        public List<String> auditedCallIds() {
            return calls.stream().map(AuditedCallFact::id).toList();
        }
    }

    private record RowData(JsonNode payload, List<String> columns, List<JsonNode> rows) {
        private RowData {
            payload = payload.deepCopy();
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }
}
