package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisToolCallAuditTest {
    private static final Instant STARTED_AT = Instant.parse("2026-07-22T09:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MyBatisToolCallAudit audit = new MyBatisToolCallAudit(objectMapper);
    private final MyBatisDatabasePreflight.Result database = new MyBatisDatabasePreflight.Result(
            "gauss-readonly", "orders", "audit", "GaussDB");

    @Test
    void auditsOnlyCompletePostTaskHistoryDifferenceAndReturnsImmutableFacts() {
        AgentBridgeClient.ToolCallRecord oldCall = callAt(
                "old-call", "list_database_connections", objectMapper.createObjectNode(),
                objectMapper.createObjectNode().put("count", 1), STARTED_AT.minusSeconds(5), 5L);
        AgentBridgeClient.ToolCallRecord newCall = sqlCall(
                "new-call", "SELECT id FROM orders LIMIT 2", 2);

        MyBatisToolCallAudit.Result result = audit.audit(
                List.of(oldCall, newCall),
                new MyBatisToolCallAudit.Boundary(STARTED_AT, Set.of("old-call")),
                database,
                selectStatement()
        );

        assertThat(result.auditedCallIds()).containsExactly("new-call");
        assertThat(result.queryScenarioCount()).isEqualTo(1);
        assertThat(result.calls()).singleElement().satisfies(fact -> {
            assertThat(fact.toolName()).isEqualTo("execute_sql_query");
            assertThat(fact.connectionId()).isEqualTo("gauss-readonly");
            assertThat(fact.queryText()).isEqualTo("SELECT id FROM orders LIMIT 2");
            assertThat(fact.columns()).containsExactly("id");
            assertThat(fact.rows()).hasSize(2);
        });
    }

    @Test
    void hidesFactAndResultConstructionAndDerivesScenarioCountFromCalls() {
        assertThat(MyBatisToolCallAudit.AuditedCallFact.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(MyBatisToolCallAudit.Result.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()))
                .allMatch(constructor -> Arrays.stream(constructor.getParameterTypes())
                        .noneMatch(type -> type == int.class || type == Integer.class));

        MyBatisToolCallAudit.Result result = audit.audit(
                List.of(
                        call("metadata", "list_schema_objects", databaseArguments(),
                                objectMapper.createObjectNode()),
                        sqlCall("query", "SELECT id FROM orders LIMIT 1", 1)
                ),
                boundary(), database, selectStatement()
        );

        assertThat(result.queryScenarioCount()).isEqualTo(1);
    }

    @Test
    void returnsDefensiveCopiesOfEveryMutableAuditNodeAndRow() {
        ObjectNode arguments = databaseArguments().put("queryText", "SELECT id FROM orders LIMIT 1");
        ObjectNode toolResult = rows(1);
        MyBatisToolCallAudit.Result result = audit.audit(
                List.of(call("immutable", "execute_sql_query", arguments, toolResult)),
                boundary(), database, selectStatement()
        );
        MyBatisToolCallAudit.AuditedCallFact fact = result.calls().getFirst();

        arguments.put("schemaName", "mutated-original");
        ((ObjectNode) toolResult.at("/rows/0")).put("id", 700);
        ((ObjectNode) fact.arguments()).put("schemaName", "mutated-accessor");
        ((ObjectNode) fact.resultData()).put("invented", true);
        ((ObjectNode) fact.rows().getFirst()).put("id", 900);

        assertThat(fact.arguments().path("schemaName").asText()).isEqualTo("audit");
        assertThat(fact.resultData().has("invented")).isFalse();
        assertThat(fact.resultData().at("/rows/0/id").asInt()).isEqualTo(1);
        assertThat(fact.rows().getFirst().path("id").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> result.calls().add(fact)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsIncompleteHistoryDuplicateNewIdsAndMissingIdentityFields() {
        assertThatThrownBy(() -> audit.audit(
                List.of(sqlCall("new-call", "SELECT id FROM orders LIMIT 1", 1)),
                new MyBatisToolCallAudit.Boundary(STARTED_AT, Set.of("missing-old-call")),
                database,
                selectStatement()
        )).hasMessageContaining("incomplete tool-call history");

        AgentBridgeClient.ToolCallRecord duplicate = sqlCall("duplicate", "SELECT id FROM orders LIMIT 1", 1);
        assertThatThrownBy(() -> audit.audit(
                List.of(duplicate, duplicate), boundary(), database, selectStatement()))
                .hasMessageContaining("duplicate tool-call id");

        AgentBridgeClient.ToolCallRecord missingTimestamp = new AgentBridgeClient.ToolCallRecord(
                "missing-time", "SQL", "execute_sql_query", "mcp", "completed", null,
                databaseArguments().put("queryText", "SELECT id FROM orders LIMIT 1"), rows(1), 1L,
                objectMapper.createObjectNode());
        assertThatThrownBy(() -> audit.audit(
                List.of(missingTimestamp), boundary(), database, selectStatement()))
                .hasMessageContaining("missing id or timestamp");
    }

    @Test
    void acceptsBoundMetadataPreviewAndThreeReadOnlyScenarios() {
        List<AgentBridgeClient.ToolCallRecord> calls = List.of(
                call("call-1", "list_schema_objects", databaseArguments(), objectMapper.createObjectNode()),
                call("call-2", "preview_table_data", databaseArguments(), rows(2)),
                sqlCall("call-3", "SELECT id FROM orders LIMIT 20", 20),
                sqlCall("call-4", "SELECT id, status FROM orders LIMIT 10", 10),
                sqlCall("call-5", "SELECT * FROM audit.orders AS orders LIMIT 1", 1)
        );

        MyBatisToolCallAudit.Result result = audit.audit(calls, boundary(), database, selectStatement());

        assertThat(result.auditedCallIds()).containsExactly("call-1", "call-2", "call-3", "call-4", "call-5");
        assertThat(result.queryScenarioCount()).isEqualTo(3);
    }

    @ParameterizedTest(name = "rejects SQL: {0}")
    @MethodSource("forbiddenSql")
    void rejectsUnsafeOrAmbiguousPostgresqlGaussSql(String label, String sql) {
        assertThatThrownBy(() -> audit.audit(
                List.of(sqlCall("call-unsafe", sql, 1)), boundary(), database, selectStatement()))
                .hasMessageContaining("rejected");
    }

    private static Stream<Arguments> forbiddenSql() {
        return Stream.of(
                Arguments.of("read-only SELECT", "INSERT INTO orders(id) VALUES (1)"),
                Arguments.of("read-only SELECT", "UPDATE orders SET status = 'DONE'"),
                Arguments.of("read-only SELECT", "DELETE FROM orders"),
                Arguments.of("multiple statements", "SELECT 1 LIMIT 1; SELECT 2 LIMIT 1"),
                Arguments.of("locking clause", "SELECT id FROM orders LIMIT 1 FOR UPDATE"),
                Arguments.of("locking clause", "SELECT id FROM orders LIMIT 1 FOR NO KEY UPDATE"),
                Arguments.of("locking clause", "SELECT id FROM orders LIMIT 1 FOR KEY SHARE"),
                Arguments.of("SELECT INTO", "SELECT id INTO archived_orders FROM orders LIMIT 1"),
                Arguments.of("COPY", "COPY orders TO '/tmp/orders.csv'"),
                Arguments.of("CALL", "CALL refresh_orders()"),
                Arguments.of("DDL", "CREATE TABLE unsafe(id int)"),
                Arguments.of("DML CTE", "WITH changed AS (DELETE FROM orders RETURNING id) SELECT id FROM changed LIMIT 1"),
                Arguments.of("main statement", "WITH found AS (SELECT id FROM orders) UPDATE orders SET status='X'"),
                Arguments.of("dollar-quoted", "SELECT $$x; DELETE FROM orders$$ LIMIT 1"),
                Arguments.of("dollar-quoted", "SELECT $tag$x$tag$ LIMIT 1"),
                Arguments.of("unsupported string prefix", "SELECT E'\\x41' LIMIT 1"),
                Arguments.of("unsupported string prefix", "SELECT B'1010' LIMIT 1"),
                Arguments.of("unsupported string prefix", "SELECT X'FF' LIMIT 1"),
                Arguments.of("unsupported string prefix", "SELECT U&'d\\0061t' LIMIT 1"),
                Arguments.of("nested block comment", "SELECT 1 /* outer /* inner */ end */ LIMIT 1"),
                Arguments.of("quoted function", "SELECT \"count\"(*) FROM orders LIMIT 1"),
                Arguments.of("schema-qualified function", "SELECT pg_catalog.count(*) FROM orders LIMIT 1"),
                Arguments.of("schema-qualified function", "SELECT \"pg_catalog\".\"count\"(*) FROM orders LIMIT 1"),
                Arguments.of("schema-qualified function", "SELECT pg_catalog./*review*/pg_sleep(1) LIMIT 1"),
                Arguments.of("function is not allowlisted", "SELECT mystery(id) FROM orders LIMIT 1"),
                Arguments.of("function is not allowlisted", "SELECT nextval('orders_seq') LIMIT 1"),
                Arguments.of("function is not allowlisted", "SELECT pg_terminate_backend(42) LIMIT 1"),
                Arguments.of("literal top-level LIMIT", "SELECT id FROM orders LIMIT 20 + 100")
        );
    }

    @ParameterizedTest(name = "rejects catalog-resolved expression: {0}")
    @MethodSource("catalogResolvedExpressions")
    void rejectsCastsOperatorsFunctionsAndWhereInsteadOfPreservingConvenience(String label, String sql) {
        assertThatThrownBy(() -> audit.audit(
                List.of(sqlCall("catalog-expression", sql, 1)), boundary(), database, selectStatement()))
                .hasMessageContaining("simple-read grammar").hasMessageContaining(label);
    }

    private static Stream<Arguments> catalogResolvedExpressions() {
        return Stream.of(
                Arguments.of("CAST", "SELECT CAST('x' AS public.evil_type) FROM orders LIMIT 1"),
                Arguments.of("cast", "SELECT payload::public.evil_type FROM orders LIMIT 1"),
                Arguments.of("operator", "SELECT 1 !! 2 FROM orders LIMIT 1"),
                Arguments.of("operator", "SELECT left_value + right_value FROM custom_values LIMIT 1"),
                Arguments.of("operator", "SELECT left_value = right_value FROM custom_values LIMIT 1"),
                Arguments.of("operator", "SELECT left_value || right_value FROM custom_values LIMIT 1"),
                Arguments.of("function", "SELECT COUNT(*) FROM orders LIMIT 1"),
                Arguments.of("WHERE", "SELECT id FROM orders WHERE id = 1 LIMIT 1")
        );
    }

    @Test
    void allowsOnlyPlainColumnsFromAndLiteralLimitWhileIgnoringComments() {
        AgentBridgeClient.ToolCallRecord safe = sqlCall("call-simple-read", """
                SELECT orders.id, orders.status
                FROM audit.orders AS orders
                /* DELETE FROM orders; SELECT nextval('unsafe') */
                -- CALL unsafe();
                LIMIT 1
                """, 1);

        assertThat(audit.audit(List.of(safe), boundary(), database, selectStatement()).queryScenarioCount())
                .isEqualTo(1);
    }

    @Test
    void rejectsMissingOrOversizedLimitMoreThanThreeScenariosAndWrongBinding() {
        assertThatThrownBy(() -> audit.audit(
                List.of(sqlCall("call-no-limit", "SELECT id FROM orders", 1)),
                boundary(), database, selectStatement())).hasMessageContaining("top-level LIMIT");
        assertThatThrownBy(() -> audit.audit(
                List.of(sqlCall("call-big-limit", "SELECT id FROM orders LIMIT 21", 1)),
                boundary(), database, selectStatement())).hasMessageContaining("LIMIT <= 20");
        assertThatThrownBy(() -> audit.audit(
                List.of(sqlCall("call-inner-only", """
                        WITH recent AS (SELECT id FROM orders LIMIT 20)
                        SELECT id FROM recent
                        """, 1)), boundary(), database, selectStatement()))
                .hasMessageContaining("simple-read grammar");

        List<AgentBridgeClient.ToolCallRecord> fourScenarios = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            fourScenarios.add(sqlCall("call-" + index, "SELECT id FROM orders LIMIT 1", 1));
        }
        assertThatThrownBy(() -> audit.audit(fourScenarios, boundary(), database, selectStatement()))
                .hasMessageContaining("at most 3");

        ObjectNode wrongSchema = databaseArguments().put("schemaName", "public");
        assertThatThrownBy(() -> audit.audit(List.of(call(
                "call-wrong-schema", "execute_sql_query",
                wrongSchema.put("queryText", "SELECT 1 LIMIT 1"), rows(1))),
                boundary(), database, selectStatement()))
                .hasMessageContaining("bound database target");
    }

    @Test
    void enforcesThirtySecondActualDurationAndRejectsAllSelectKeyQueries() {
        AgentBridgeClient.ToolCallRecord slow = callAt(
                "slow", "execute_sql_query",
                databaseArguments().put("queryText", "SELECT id FROM orders LIMIT 1"),
                rows(1), STARTED_AT.plusSeconds(1), 30_001L);
        assertThatThrownBy(() -> audit.audit(
                List.of(slow), boundary(), database, selectStatement()))
                .hasMessageContaining("30000 ms");

        assertThatThrownBy(() -> audit.audit(
                List.of(sqlCall("select-key", "SELECT id FROM orders LIMIT 1", 1)),
                boundary(), database,
                new MyBatisToolCallAudit.StatementContext("key-generator", "select", true)))
                .hasMessageContaining("selectKey");

        MyBatisToolCallAudit.Result dmlAuxiliarySelect = audit.audit(
                List.of(sqlCall("dml-evidence", "SELECT id FROM orders LIMIT 1", 1)),
                boundary(), database,
                new MyBatisToolCallAudit.StatementContext("update-order", "update", false));
        assertThat(dmlAuxiliarySelect.queryScenarioCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("supportedResultWrappers")
    void parsesOnlyExplicitlySupportedExecuteAndPreviewResultWrappers(String fixture) throws Exception {
        JsonNode result = fixture(fixture);
        AgentBridgeClient.ToolCallRecord call = call(
                "wrapped", "execute_sql_query",
                databaseArguments().put("queryText", "SELECT id FROM orders LIMIT 2"), result);

        MyBatisToolCallAudit.AuditedCallFact fact = audit.audit(
                List.of(call), boundary(), database, selectStatement()).calls().getFirst();

        assertThat(fact.columns()).containsExactly("id");
        assertThat(fact.rows()).hasSize(2);
    }

    private static Stream<String> supportedResultWrappers() {
        return Stream.of(
                "tool-result-direct.json",
                "tool-result-structured.json",
                "tool-result-content-text.json",
                "tool-result-json-string.json"
        );
    }

    @Test
    void rejectsUnknownResultWrappersAndMoreThanTwentyActualRows() {
        ObjectNode unknown = objectMapper.createObjectNode();
        unknown.putObject("data").set("items", rows(1).path("rows"));
        assertThatThrownBy(() -> audit.audit(List.of(call(
                "unknown", "execute_sql_query",
                databaseArguments().put("queryText", "SELECT id FROM orders LIMIT 1"), unknown)),
                boundary(), database, selectStatement()))
                .hasMessageContaining("unsupported result wrapper");

        assertThatThrownBy(() -> audit.audit(
                List.of(sqlCall("too-many", "SELECT id FROM orders LIMIT 20", 21)),
                boundary(), database, selectStatement()))
                .hasMessageContaining("at most 20 rows");
    }

    @Test
    void rejectsUnapprovedToolsAndNonCompletedOrIncompleteNewCalls() {
        assertThatThrownBy(() -> audit.audit(List.of(call(
                "shell", "run_command", objectMapper.createObjectNode(), objectMapper.createObjectNode())),
                boundary(), database, selectStatement()))
                .hasMessageContaining("unapproved tool");

        AgentBridgeClient.ToolCallRecord missingDuration = callAt(
                "no-duration", "list_database_connections", objectMapper.createObjectNode(),
                objectMapper.createObjectNode(), STARTED_AT.plusSeconds(1), null);
        assertThatThrownBy(() -> audit.audit(
                List.of(missingDuration), boundary(), database, selectStatement()))
                .hasMessageContaining("durationMs");
    }

    private MyBatisToolCallAudit.StatementContext selectStatement() {
        return new MyBatisToolCallAudit.StatementContext("mapper-order-find", "select", false);
    }

    private MyBatisToolCallAudit.Boundary boundary() {
        return new MyBatisToolCallAudit.Boundary(STARTED_AT, Set.of());
    }

    private AgentBridgeClient.ToolCallRecord sqlCall(String id, String sql, int rowCount) {
        return call(id, "execute_sql_query", databaseArguments().put("queryText", sql), rows(rowCount));
    }

    private AgentBridgeClient.ToolCallRecord call(String id, String name, JsonNode arguments, JsonNode result) {
        return callAt(id, name, arguments, result, STARTED_AT.plusSeconds(30), 12L);
    }

    private AgentBridgeClient.ToolCallRecord callAt(
            String id,
            String name,
            JsonNode arguments,
            JsonNode result,
            Instant timestamp,
            Long durationMs
    ) {
        return new AgentBridgeClient.ToolCallRecord(
                id, name, name, "mcp", "completed", timestamp,
                arguments, result, durationMs, objectMapper.createObjectNode());
    }

    private ObjectNode databaseArguments() {
        return objectMapper.createObjectNode()
                .put("connectionId", "gauss-readonly")
                .put("databaseName", "orders")
                .put("schemaName", "audit");
    }

    private ObjectNode rows(int count) {
        ObjectNode result = objectMapper.createObjectNode();
        result.putArray("columns").add("id");
        ArrayNode rows = result.putArray("rows");
        for (int index = 0; index < count; index++) {
            rows.addObject().put("id", index + 1);
        }
        return result;
    }

    private JsonNode fixture(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/mybatis-sql-review-fixtures/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return objectMapper.readTree(input);
        }
    }
}
