package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Fail-closed audit for the native Database MCP evidence generated during one task. */
public final class MyBatisToolCallAudit {
    public static final int MAX_QUERY_SCENARIOS = 3;
    public static final int MAX_ROWS_PER_CALL = DatabaseMcpContract.MAX_ROWS;
    public static final long MAX_QUERY_DURATION_MS = 30_000L;
    public static final int MAX_TOOL_RESULT_BYTES = 262_144;

    private final ObjectMapper objectMapper;
    private final ReadOnlySqlPolicy sqlPolicy = new ReadOnlySqlPolicy(MAX_ROWS_PER_CALL);

    public MyBatisToolCallAudit(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    static void validateDeterminableScenario(String sql, String callId, String schema, long durationMs) {
        if (durationMs < 0 || durationMs > MAX_QUERY_DURATION_MS) {
            throw new IllegalStateException("tool call " + callId + " violates SQL review policy: query duration must be in 0..30000 ms");
        }
        new ReadOnlySqlPolicy(MAX_ROWS_PER_CALL).validateDeterminable(sql, callId, schema);
    }

    static void validateDeterminableMetadata(String toolName, JsonNode arguments, String callId) {
        requireReadTool(toolName, callId);
        if (DatabaseMcpContract.EXECUTE_QUERY.equals(toolName)) {
            throw new IllegalStateException("tool call " + callId + " query evidence must be represented as a scenario");
        }
        requireArguments(toolName, arguments, null, callId);
    }

    static void validateDatabaseBinding(String toolName, JsonNode arguments, DatabaseMcpContract.Binding binding, String callId) {
        requireReadTool(toolName, callId);
        requireArguments(toolName, arguments, binding, callId);
    }

    /** Compatibility entry point for the offline validator; online audit always supplies its verified binding. */
    static void validateDatabaseBinding(String toolName, JsonNode arguments, String dataSource, String catalog,
                                        String schema, String callId) {
        requireReadTool(toolName, callId);
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalStateException("tool call " + callId + " arguments must be an object");
        }
        if (!DatabaseMcpContract.LIST_DATASOURCES.equals(toolName)) exact(arguments, "dataSource", dataSource, callId);
        if (DatabaseMcpContract.LIST_TABLE_SCHEMA.equals(toolName)) {
            exact(arguments, "catalog", catalog, callId);
            exact(arguments, "schema", schema, callId);
        }
    }

    public Result audit(List<AgentBridgeClient.ToolCallRecord> history, Boundary boundary,
                        MyBatisDatabasePreflight.Result database, StatementContext statement) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(statement, "statement");
        Set<String> ids = new HashSet<>();
        Set<String> old = new LinkedHashSet<>();
        List<AuditedCallFact> facts = new ArrayList<>();
        int scenarioCount = 0;
        for (AgentBridgeClient.ToolCallRecord call : history) {
            requireHistoryShape(call);
            if (!ids.add(call.id())) throw violation(call, "duplicate tool-call id");
            if (boundary.preexistingCallIds().contains(call.id())) {
                if (call.timestamp().isAfter(boundary.startedAt())) throw violation(call, "preexisting id was reused after the task boundary");
                old.add(call.id());
                continue;
            }
            if (call.timestamp().isBefore(boundary.startedAt())) throw violation(call, "incomplete pre-task id snapshot contains an unknown old call");
            requireCompleted(call);
            if (!new DatabaseMcpContract(objectMapper, database.binding()).readTools().contains(call.toolName())) {
                throw violation(call, "unapproved tool: " + call.toolName());
            }
            validateDatabaseBinding(call.toolName(), call.arguments(), database.binding(), call.id());
            requireBoundedResultEvidence(call);
            String sql = "";
            RowData rows = RowData.empty(call.result());
            if (DatabaseMcpContract.EXECUTE_QUERY.equals(call.toolName())) {
                if (statement.selectKey()) throw violation(call, "selectKey tasks must not call the database query tool");
                if (!"select".equalsIgnoreCase(statement.commandType())) throw violation(call, "DML tasks must not call the database query tool");
                if (++scenarioCount > MAX_QUERY_SCENARIOS) throw violation(call, "database query is limited to at most 3 scenarios");
                sql = call.arguments().path("sql").asText();
                validateDeterminableScenario(sql, call.id(), database.binding().schema(), call.durationMs());
                sqlPolicy.validate(sql, call.id(), database.binding().schema(), database.safeBaseRelations());
                rows = parseRows(call);
            }
            facts.add(new AuditedCallFact(call.id(), call.toolName(), call.timestamp(), call.durationMs(),
                    database.binding().dataSource(), database.binding().catalog(), database.binding().schema(),
                    database.binding().project().toString(), database.binding().scope().name(), sql,
                    DatabaseMcpContract.EXECUTE_QUERY.equals(call.toolName()) ? DatabaseMcpContract.MAX_ROWS : null,
                    call.arguments(), rows.payload(), rows.columns(), rows.rows()));
        }
        if (!old.containsAll(boundary.preexistingCallIds())) {
            Set<String> missing = new LinkedHashSet<>(boundary.preexistingCallIds()); missing.removeAll(old);
            throw new IllegalStateException("incomplete tool-call history; missing preexisting call ids: " + missing);
        }
        return new Result(facts, statement, database);
    }

    private static void requireReadTool(String toolName, String callId) {
        if (!Set.of(DatabaseMcpContract.LIST_DATASOURCES, DatabaseMcpContract.LIST_DATABASES,
                DatabaseMcpContract.LIST_TABLE_SCHEMA, DatabaseMcpContract.EXECUTE_QUERY).contains(toolName)) {
            throw new IllegalStateException("tool call " + callId + " uses an unapproved tool: " + toolName);
        }
    }

    private static void requireArguments(String toolName, JsonNode arguments, DatabaseMcpContract.Binding binding, String callId) {
        if (arguments == null || !arguments.isObject()) throw new IllegalStateException("tool call " + callId + " arguments must be an object");
        Set<String> allowed = switch (toolName) {
            case DatabaseMcpContract.LIST_DATASOURCES -> Set.of("project", "scope");
            case DatabaseMcpContract.LIST_DATABASES -> Set.of("project", "scope", "dataSource");
            case DatabaseMcpContract.LIST_TABLE_SCHEMA -> Set.of("project", "scope", "dataSource", "catalog", "schema", "includeColumns", "includeIndexes", "maxTables");
            case DatabaseMcpContract.EXECUTE_QUERY -> Set.of("project", "scope", "dataSource", "sql", "maxRows");
            default -> throw new IllegalStateException("tool call " + callId + " uses an unapproved tool: " + toolName);
        };
        arguments.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw new IllegalStateException("tool call " + callId + " uses an unsupported argument: " + field); });
        for (String field : allowed) if (arguments.path(field).isMissingNode()) throw new IllegalStateException("tool call " + callId + " is missing required argument: " + field);
        if (binding == null) return;
        exact(arguments, "project", binding.project().toString(), callId);
        exact(arguments, "scope", binding.scope().name(), callId);
        if (!DatabaseMcpContract.LIST_DATASOURCES.equals(toolName)) exact(arguments, "dataSource", binding.dataSource(), callId);
        if (DatabaseMcpContract.LIST_TABLE_SCHEMA.equals(toolName)) {
            exact(arguments, "catalog", binding.catalog(), callId); exact(arguments, "schema", binding.schema(), callId);
            if (!arguments.path("includeColumns").isBoolean() || !arguments.path("includeColumns").booleanValue()
                    || !arguments.path("includeIndexes").isBoolean() || !arguments.path("includeIndexes").booleanValue()
                    || !arguments.path("maxTables").isIntegralNumber() || arguments.path("maxTables").intValue() != DatabaseMcpContract.MAX_TABLES) {
                throw new IllegalStateException("tool call " + callId + " table-schema metadata arguments are not the approved bounded values");
            }
        }
        if (DatabaseMcpContract.EXECUTE_QUERY.equals(toolName)) {
            if (!arguments.path("sql").isTextual() || arguments.path("sql").asText().isBlank()) throw new IllegalStateException("tool call " + callId + " sql must be non-blank");
            if (!arguments.path("maxRows").isIntegralNumber() || arguments.path("maxRows").intValue() != DatabaseMcpContract.MAX_ROWS) throw new IllegalStateException("tool call " + callId + " maxRows must equal 20");
        }
    }

    private static void exact(JsonNode args, String field, String expected, String callId) {
        if (!args.path(field).isTextual() || !expected.equals(args.path(field).asText())) throw new IllegalStateException("tool call " + callId + " arguments do not match the bound database target: " + field);
    }

    private void requireBoundedResultEvidence(AgentBridgeClient.ToolCallRecord call) {
        try { if (objectMapper.writeValueAsBytes(call.result()).length > MAX_TOOL_RESULT_BYTES) throw violation(call, "tool result evidence exceeds 262144 bytes"); }
        catch (IOException e) { throw violation(call, "tool result evidence could not be serialized safely"); }
    }

    private RowData parseRows(AgentBridgeClient.ToolCallRecord call) {
        JsonNode value = call.result();
        if (value.isTextual()) try { value = objectMapper.readTree(value.asText()); } catch (IOException e) { throw violation(call, "query result is not valid JSON"); }
        if (value.isObject() && value.path("structuredContent").isContainerNode()) value = value.path("structuredContent");
        List<String> columns = new ArrayList<>(); List<JsonNode> rows = new ArrayList<>();
        if (value.isArray()) {
            for (JsonNode row : value) { requireRow(call, row); rows.add(row.deepCopy()); }
            if (!rows.isEmpty() && rows.getFirst().isObject()) rows.getFirst().fieldNames().forEachRemaining(columns::add);
        } else if (value.isObject() && value.path("columns").isArray() && value.path("rows").isArray()) {
            Set<String> unique = new LinkedHashSet<>();
            for (JsonNode column : value.path("columns")) if (!column.isTextual() || column.asText().isBlank() || !unique.add(column.asText())) throw violation(call, "result columns must be unique non-blank strings"); else columns.add(column.asText());
            for (JsonNode row : value.path("rows")) { requireRow(call, row); rows.add(row.deepCopy()); }
        } else throw violation(call, "query result must be an array or an object with columns and rows");
        if (rows.size() > MAX_ROWS_PER_CALL) throw violation(call, "query result may retain at most 20 rows");
        ObjectNode payload = objectMapper.createObjectNode(); payload.set("columns", objectMapper.valueToTree(columns)); payload.set("rows", objectMapper.valueToTree(rows));
        return new RowData(payload, columns, rows);
    }

    private static void requireRow(AgentBridgeClient.ToolCallRecord call, JsonNode row) { if (!row.isObject() && !row.isArray()) throw violation(call, "query result rows must contain objects or arrays"); }
    private static void requireHistoryShape(AgentBridgeClient.ToolCallRecord call) { if (call == null || call.id() == null || call.id().isBlank() || call.timestamp() == null) throw new IllegalStateException("tool-call history contains a call with missing id or timestamp"); }
    private static void requireCompleted(AgentBridgeClient.ToolCallRecord call) {
        if (call.toolName() == null || call.toolName().isBlank() || call.status() == null || !"completed".equalsIgnoreCase(call.status()) || call.durationMs() == null || call.durationMs() < 0 || call.result() == null || call.result().isNull()) throw violation(call, "new tool call is incomplete or not completed successfully");
    }
    private static IllegalStateException violation(AgentBridgeClient.ToolCallRecord call, String message) { return new IllegalStateException("tool call " + call.id() + " rejected: " + message); }

    public record Boundary(Instant startedAt, Set<String> preexistingCallIds) { public Boundary { Objects.requireNonNull(startedAt, "startedAt"); preexistingCallIds = Set.copyOf(preexistingCallIds == null ? Set.of() : preexistingCallIds); } }
    public record StatementContext(String statementKey, String commandType, boolean selectKey) { public StatementContext { if (statementKey == null || statementKey.isBlank() || commandType == null || commandType.isBlank()) throw new IllegalArgumentException("statement context fields must be non-blank"); } }
    public static final class AuditedCallFact {
        private final String id, toolName, dataSource, catalog, schema, project, scope, sql; private final Instant timestamp; private final long durationMs; private final Integer maxRows; private final JsonNode arguments, resultData; private final List<String> columns; private final List<JsonNode> rows;
        private AuditedCallFact(String id, String toolName, Instant timestamp, long durationMs, String dataSource, String catalog, String schema, String project, String scope, String sql, Integer maxRows, JsonNode arguments, JsonNode resultData, List<String> columns, List<JsonNode> rows) { this.id=id; this.toolName=toolName; this.timestamp=timestamp; this.durationMs=durationMs; this.dataSource=dataSource; this.catalog=catalog; this.schema=schema; this.project=project; this.scope=scope; this.sql=sql; this.maxRows=maxRows; this.arguments=arguments.deepCopy(); this.resultData=resultData.deepCopy(); this.columns=List.copyOf(columns); List<JsonNode> copy=new ArrayList<>(); for(JsonNode row: rows) copy.add(row.deepCopy()); this.rows=List.copyOf(copy); }
        public String id(){return id;} public String toolName(){return toolName;} public Instant timestamp(){return timestamp;} public long durationMs(){return durationMs;} public String dataSource(){return dataSource;} public String catalog(){return catalog;} public String schema(){return schema;} public String project(){return project;} public String scope(){return scope;} public String sql(){return sql;} public Integer maxRows(){return maxRows;} public JsonNode arguments(){return arguments.deepCopy();} public JsonNode resultData(){return resultData.deepCopy();} public List<String> columns(){return columns;} public List<JsonNode> rows(){List<JsonNode> copy=new ArrayList<>();for(JsonNode row:rows)copy.add(row.deepCopy());return List.copyOf(copy);}
    }
    public static final class Result { private final List<AuditedCallFact> facts; private final StatementContext statementContext; private final MyBatisDatabasePreflight.Result database; private Result(List<AuditedCallFact> facts, StatementContext statementContext, MyBatisDatabasePreflight.Result database){this.facts=List.copyOf(facts);this.statementContext=statementContext;this.database=database;} public List<AuditedCallFact> facts(){return facts;} public List<AuditedCallFact> calls(){return facts;} public int queryScenarioCount(){return (int)facts.stream().filter(f->DatabaseMcpContract.EXECUTE_QUERY.equals(f.toolName())).count();} public StatementContext statementContext(){return statementContext;} public MyBatisDatabasePreflight.Result database(){return database;} public List<String> auditedCallIds(){return facts.stream().map(AuditedCallFact::id).toList();} }
    private record RowData(JsonNode payload, List<String> columns, List<JsonNode> rows) { static RowData empty(JsonNode result){return new RowData(result.deepCopy(), List.of(), List.of());} }
}
