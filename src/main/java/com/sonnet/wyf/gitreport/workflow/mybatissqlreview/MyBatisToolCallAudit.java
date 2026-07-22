package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MyBatisToolCallAudit {
    public static final int MAX_QUERY_SCENARIOS = 3;
    public static final int MAX_ROWS_PER_CALL = 20;

    private static final Set<String> ALLOWED_TOOLS = MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS;
    private static final Set<String> SCHEMA_BOUND_TOOLS = Set.of(
            "list_schema_object_kinds",
            "list_schema_objects",
            "preview_table_data",
            "get_database_object_description",
            "execute_sql_query"
    );
    private static final Pattern DML = Pattern.compile("\\b(INSERT|UPDATE|DELETE|MERGE|UPSERT)\\b");
    private static final Pattern DDL = Pattern.compile("\\b(CREATE|ALTER|DROP|TRUNCATE|COMMENT|GRANT|REVOKE)\\b");
    private static final Pattern LIMIT = Pattern.compile("\\bLIMIT\\s+(\\d+)\\b");
    private static final Pattern FOR_LOCK = Pattern.compile("\\bFOR\\s+(UPDATE|SHARE)\\b");
    private static final Pattern SELECT_INTO = Pattern.compile("\\bSELECT\\b[\\s\\S]*\\bINTO\\b");
    private static final Pattern SEQUENCE_MUTATION = Pattern.compile(
            "\\b(NEXTVAL|CURRVAL|SETVAL)\\s*\\(|\\bNEXT\\s+VALUE\\s+FOR\\b"
    );
    private static final Pattern SIDE_EFFECT_FUNCTION = Pattern.compile(
            "\\b(PG_TERMINATE_BACKEND|PG_CANCEL_BACKEND|PG_ADVISORY_LOCK|PG_ADVISORY_LOCK_SHARED|"
                    + "PG_TRY_ADVISORY_LOCK|PG_TRY_ADVISORY_LOCK_SHARED|PG_ADVISORY_UNLOCK|"
                    + "PG_ADVISORY_UNLOCK_SHARED|PG_ADVISORY_UNLOCK_ALL|PG_SLEEP|PG_SLEEP_FOR|"
                    + "PG_SLEEP_UNTIL|DBLINK_EXEC|DBLINK_CONNECT|DBLINK_DISCONNECT|LO_CREATE|"
                    + "LO_UNLINK|LO_IMPORT|LO_EXPORT|SET_CONFIG)\\s*\\("
    );

    public Result audit(
            List<AgentBridgeClient.ToolCallRecord> calls,
            Boundary boundary,
            MyBatisDatabasePreflight.Result database
    ) {
        Objects.requireNonNull(calls, "calls");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(database, "database");
        List<String> auditedIds = new ArrayList<>();
        Set<String> uniqueIds = new HashSet<>();
        int queryScenarios = 0;

        for (AgentBridgeClient.ToolCallRecord call : calls) {
            requireFresh(call, boundary);
            if (!uniqueIds.add(call.id())) {
                throw violation(call, "duplicate tool-call id");
            }
            if (!ALLOWED_TOOLS.contains(call.toolName())) {
                throw violation(call, "unapproved tool: " + call.toolName());
            }
            requireDatabaseBinding(call, database);
            if ("execute_sql_query".equals(call.toolName())) {
                queryScenarios++;
                if (queryScenarios > MAX_QUERY_SCENARIOS) {
                    throw violation(call, "execute_sql_query is limited to at most 3 scenarios");
                }
                validateQuery(call.arguments().path("queryText").asText(""), call);
            }
            int rowCount = rowCount(call.result());
            if (rowCount > MAX_ROWS_PER_CALL) {
                throw violation(call, "tool result may retain at most 20 rows but found " + rowCount);
            }
            if (!"completed".equalsIgnoreCase(call.status())) {
                throw violation(call, "tool call did not complete successfully: " + call.status());
            }
            auditedIds.add(call.id());
        }
        return new Result(auditedIds, queryScenarios);
    }

    private void requireFresh(AgentBridgeClient.ToolCallRecord call, Boundary boundary) {
        if (call == null || call.id() == null || call.id().isBlank() || call.timestamp() == null
                || call.timestamp().isBefore(boundary.startedAt())
                || boundary.preexistingCallIds().contains(call.id())) {
            String id = call == null ? "<null>" : call.id();
            throw new IllegalStateException("stale or unidentifiable tool call is outside the task boundary: " + id);
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

    private void validateQuery(String sql, AgentBridgeClient.ToolCallRecord call) {
        if (sql.isBlank()) {
            throw violation(call, "queryText is required");
        }
        String lexical = lexicalSql(sql);
        if (lexical.indexOf(';') >= 0) {
            throw violation(call, "multiple statements and semicolons are forbidden");
        }
        String upper = lexical.strip().toUpperCase(Locale.ROOT);
        if (upper.startsWith("COPY ") || upper.equals("COPY")) {
            throw violation(call, "COPY is forbidden");
        }
        if (upper.startsWith("CALL ") || upper.equals("CALL")) {
            throw violation(call, "CALL is forbidden");
        }
        if (DDL.matcher(upper).find()) {
            throw violation(call, "DDL is forbidden");
        }
        if (upper.startsWith("WITH") && DML.matcher(upper).find()) {
            throw violation(call, "DML CTE is forbidden");
        }
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw violation(call, "query must be a read-only SELECT or WITH...SELECT");
        }
        if (FOR_LOCK.matcher(upper).find()) {
            throw violation(call, "FOR UPDATE/SHARE is forbidden");
        }
        if (DML.matcher(upper).find()) {
            throw violation(call, "query must be a read-only SELECT or WITH...SELECT");
        }
        if (SELECT_INTO.matcher(upper).find()) {
            throw violation(call, "SELECT INTO is forbidden");
        }
        if (SEQUENCE_MUTATION.matcher(upper).find()) {
            throw violation(call, "sequence mutation/selectKey execution is forbidden");
        }
        if (SIDE_EFFECT_FUNCTION.matcher(upper).find()) {
            throw violation(call, "side-effect function is forbidden");
        }

        Matcher limits = LIMIT.matcher(upper);
        boolean foundTopLevelLimit = false;
        while (limits.find()) {
            boolean topLevel = nestingDepth(upper, limits.start()) == 0;
            if (topLevel) {
                foundTopLevelLimit = true;
                String suffix = upper.substring(limits.end()).strip();
                if (!suffix.isEmpty() && !suffix.matches("OFFSET\\s+\\d+")) {
                    throw violation(call, "query requires a literal top-level LIMIT <= 20");
                }
            }
            int value;
            try {
                value = Integer.parseInt(limits.group(1));
            } catch (NumberFormatException exception) {
                throw violation(call, "LIMIT must be a literal integer <= 20");
            }
            if (value < 1 || value > MAX_ROWS_PER_CALL) {
                throw violation(call, "every query requires LIMIT <= 20");
            }
        }
        if (!foundTopLevelLimit) {
            throw violation(call, "every query requires a literal top-level LIMIT <= 20");
        }
    }

    private int nestingDepth(String sql, int exclusiveEnd) {
        int depth = 0;
        for (int index = 0; index < exclusiveEnd; index++) {
            char current = sql.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth < 0) {
                    throw new IllegalStateException("unbalanced SQL parentheses");
                }
            }
        }
        return depth;
    }

    private String lexicalSql(String sql) {
        StringBuilder output = new StringBuilder(sql.length());
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    output.append(' ');
                } else {
                    output.append(' ');
                }
                continue;
            }
            if (blockComment) {
                output.append(' ');
                if (current == '*' && next == '/') {
                    output.append(' ');
                    index++;
                    blockComment = false;
                }
                continue;
            }
            if (singleQuote) {
                output.append(' ');
                if (current == '\'' && next == '\'') {
                    output.append(' ');
                    index++;
                } else if (current == '\'') {
                    singleQuote = false;
                }
                continue;
            }
            if (doubleQuote) {
                output.append(' ');
                if (current == '"' && next == '"') {
                    output.append(' ');
                    index++;
                } else if (current == '"') {
                    doubleQuote = false;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                output.append("  ");
                index++;
                lineComment = true;
            } else if (current == '/' && next == '*') {
                output.append("  ");
                index++;
                blockComment = true;
            } else if (current == '\'') {
                output.append(' ');
                singleQuote = true;
            } else if (current == '"') {
                output.append(' ');
                doubleQuote = true;
            } else {
                output.append(current);
            }
        }
        if (singleQuote || doubleQuote || blockComment) {
            throw new IllegalStateException("unterminated SQL quote or comment");
        }
        return output.toString();
    }

    private int rowCount(JsonNode result) {
        if (result == null || result.isMissingNode() || result.isNull()) {
            return 0;
        }
        JsonNode rows = result.path("rows");
        if (rows.isArray()) {
            return rows.size();
        }
        JsonNode nestedRows = result.at("/result/rows");
        return nestedRows.isArray() ? nestedRows.size() : 0;
    }

    private IllegalStateException violation(AgentBridgeClient.ToolCallRecord call, String message) {
        return new IllegalStateException("tool call " + call.id() + " rejected: " + message);
    }

    public record Boundary(Instant startedAt, Set<String> preexistingCallIds) {
        public Boundary {
            Objects.requireNonNull(startedAt, "startedAt");
            preexistingCallIds = Set.copyOf(preexistingCallIds == null ? Set.of() : preexistingCallIds);
        }
    }

    public record Result(List<String> auditedCallIds, int queryScenarioCount) {
        public Result {
            auditedCallIds = List.copyOf(auditedCallIds);
        }
    }
}
