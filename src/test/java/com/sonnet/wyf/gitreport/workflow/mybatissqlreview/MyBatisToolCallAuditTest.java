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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisToolCallAuditTest {
    private static final Instant STARTED_AT = Instant.parse("2026-07-22T09:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MyBatisToolCallAudit audit = new MyBatisToolCallAudit();
    private final MyBatisDatabasePreflight.Result database = new MyBatisDatabasePreflight.Result(
            "gauss-readonly", "orders", "audit", "GaussDB");

    @Test
    void acceptsBoundMetadataPreviewAndAtMostThreeReadOnlyScenarios() {
        List<AgentBridgeClient.ToolCallRecord> calls = List.of(
                call("call-1", "list_schema_objects", databaseArguments(), objectMapper.createObjectNode()),
                call("call-2", "preview_table_data", databaseArguments(), rows(2)),
                sqlCall("call-3", "WITH recent AS (SELECT id FROM orders) SELECT id FROM recent LIMIT 20", 20),
                sqlCall("call-4", "SELECT id FROM orders WHERE status = 'OPEN' LIMIT 10", 10),
                sqlCall("call-5", "SELECT count(*) FROM orders LIMIT 1", 1)
        );

        MyBatisToolCallAudit.Result result = audit.audit(calls, boundary(), database);

        assertThat(result.auditedCallIds()).containsExactly("call-1", "call-2", "call-3", "call-4", "call-5");
        assertThat(result.queryScenarioCount()).isEqualTo(3);
    }

    @ParameterizedTest(name = "rejects SQL: {0}")
    @MethodSource("forbiddenSql")
    void rejectsOriginalDmlSelectKeyMultipleStatementsAndForbiddenSql(String label, String sql) {
        assertThatThrownBy(() -> audit.audit(List.of(sqlCall("call-unsafe", sql, 1)), boundary(), database))
                .hasMessageContaining(label);
    }

    private static Stream<Arguments> forbiddenSql() {
        return Stream.of(
                Arguments.of("read-only SELECT", "INSERT INTO orders(id) VALUES (1)"),
                Arguments.of("read-only SELECT", "UPDATE orders SET status = 'DONE'"),
                Arguments.of("read-only SELECT", "DELETE FROM orders"),
                Arguments.of("multiple statements", "SELECT 1 LIMIT 1; SELECT 2 LIMIT 1"),
                Arguments.of("FOR UPDATE/SHARE", "SELECT id FROM orders LIMIT 1 FOR UPDATE"),
                Arguments.of("SELECT INTO", "SELECT id INTO archived_orders FROM orders LIMIT 1"),
                Arguments.of("COPY", "COPY orders TO '/tmp/orders.csv'"),
                Arguments.of("CALL", "CALL refresh_orders()"),
                Arguments.of("DDL", "CREATE TABLE unsafe(id int)"),
                Arguments.of("DML CTE", "WITH changed AS (DELETE FROM orders RETURNING id) SELECT id FROM changed LIMIT 1"),
                Arguments.of("sequence mutation/selectKey", "SELECT nextval('orders_seq') LIMIT 1"),
                Arguments.of("side-effect function", "SELECT pg_terminate_backend(42) LIMIT 1"),
                Arguments.of("side-effect function", "SELECT pg_advisory_unlock(42) LIMIT 1"),
                Arguments.of("side-effect function", "SELECT pg_sleep(30) LIMIT 1"),
                Arguments.of("literal top-level LIMIT", "SELECT id FROM orders LIMIT 20 + 100")
        );
    }

    @Test
    void rejectsMissingOrOversizedLimitAndMoreThanThreeScenarios() {
        assertThatThrownBy(() -> audit.audit(
                List.of(sqlCall("call-no-limit", "SELECT id FROM orders", 1)), boundary(), database))
                .hasMessageContaining("LIMIT");
        assertThatThrownBy(() -> audit.audit(
                List.of(sqlCall("call-big-limit", "SELECT id FROM orders LIMIT 21", 1)), boundary(), database))
                .hasMessageContaining("LIMIT <= 20");
        assertThatThrownBy(() -> audit.audit(
                List.of(sqlCall("call-inner-limit-only", """
                        WITH recent AS (SELECT id FROM orders LIMIT 20)
                        SELECT id FROM recent
                        """, 1)), boundary(), database))
                .hasMessageContaining("top-level LIMIT");

        List<AgentBridgeClient.ToolCallRecord> fourScenarios = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            fourScenarios.add(sqlCall("call-" + index, "SELECT " + index + " LIMIT 1", 1));
        }
        assertThatThrownBy(() -> audit.audit(fourScenarios, boundary(), database))
                .hasMessageContaining("at most 3");
    }

    @Test
    void ignoresSemicolonsAndForbiddenKeywordsInsideStringsAndComments() {
        AgentBridgeClient.ToolCallRecord safe = sqlCall("call-quoted", """
                SELECT '; UPDATE orders SET status = ''DONE''' AS example
                FROM orders
                /* DELETE FROM orders; SELECT nextval('unsafe') */
                -- CALL unsafe();
                LIMIT 1
                """, 1);

        assertThat(audit.audit(List.of(safe), boundary(), database).queryScenarioCount()).isEqualTo(1);
    }

    @Test
    void rejectsWrongDatabaseBindingTooManyRowsStaleCallsAndUnapprovedTools() {
        ObjectNode wrongSchema = databaseArguments().put("schemaName", "public");
        assertThatThrownBy(() -> audit.audit(
                List.of(call("call-wrong-schema", "execute_sql_query",
                        wrongSchema.put("queryText", "SELECT 1 LIMIT 1"), rows(1))), boundary(), database))
                .hasMessageContaining("bound database target");

        assertThatThrownBy(() -> audit.audit(
                List.of(sqlCall("call-too-many-rows", "SELECT id FROM orders LIMIT 20", 21)), boundary(), database))
                .hasMessageContaining("at most 20 rows");

        assertThatThrownBy(() -> audit.audit(
                List.of(sqlCall("known-before-start", "SELECT 1 LIMIT 1", 1)),
                new MyBatisToolCallAudit.Boundary(STARTED_AT, Set.of("known-before-start")), database))
                .hasMessageContaining("stale");

        AgentBridgeClient.ToolCallRecord tooOld = new AgentBridgeClient.ToolCallRecord(
                "old-call", "old", "execute_sql_query", "mcp", "completed",
                STARTED_AT.minusSeconds(1),
                databaseArguments().put("queryText", "SELECT 1 LIMIT 1"), rows(1), 1L,
                objectMapper.createObjectNode());
        assertThatThrownBy(() -> audit.audit(List.of(tooOld), boundary(), database))
                .hasMessageContaining("stale");

        assertThatThrownBy(() -> audit.audit(
                List.of(call("call-shell", "run_command", objectMapper.createObjectNode(), objectMapper.createObjectNode())),
                boundary(), database))
                .hasMessageContaining("unapproved tool");
    }

    private MyBatisToolCallAudit.Boundary boundary() {
        return new MyBatisToolCallAudit.Boundary(STARTED_AT, Set.of());
    }

    private AgentBridgeClient.ToolCallRecord sqlCall(String id, String sql, int rowCount) {
        return call(id, "execute_sql_query", databaseArguments().put("queryText", sql), rows(rowCount));
    }

    private AgentBridgeClient.ToolCallRecord call(String id, String name, JsonNode arguments, JsonNode result) {
        return new AgentBridgeClient.ToolCallRecord(
                id,
                name,
                name,
                "mcp",
                "completed",
                STARTED_AT.plusSeconds(30),
                arguments,
                result,
                12L,
                objectMapper.createObjectNode()
        );
    }

    private ObjectNode databaseArguments() {
        return objectMapper.createObjectNode()
                .put("connectionId", "gauss-readonly")
                .put("databaseName", "orders")
                .put("schemaName", "audit");
    }

    private ObjectNode rows(int count) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode rows = result.putArray("rows");
        for (int index = 0; index < count; index++) {
            rows.addObject().put("id", index + 1);
        }
        return result;
    }
}
