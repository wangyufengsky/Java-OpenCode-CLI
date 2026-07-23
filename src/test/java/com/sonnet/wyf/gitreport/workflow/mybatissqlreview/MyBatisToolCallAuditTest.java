package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisToolCallAuditTest {
    private static final Instant STARTED_AT = Instant.parse("2026-07-23T09:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MyBatisToolCallAudit audit = new MyBatisToolCallAudit(objectMapper);
    private final MyBatisDatabasePreflight.Result database = VerifiedMyBatisDatabaseFixture.verified(objectMapper);

    @Test
    void acceptsBoundedNativeQueryCall() {
        MyBatisToolCallAudit.Result result = audit.audit(
                List.of(call("query-1", DatabaseMcpContract.EXECUTE_QUERY,
                        nativeQueryArguments("SELECT id FROM audit.orders LIMIT 2"), rows(2))),
                boundary(), database, selectStatement());

        assertThat(result.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.toolName()).isEqualTo(DatabaseMcpContract.EXECUTE_QUERY);
            assertThat(fact.dataSource()).isEqualTo("GaussDB-ReadOnly");
            assertThat(fact.catalog()).isEqualTo("orders");
            assertThat(fact.schema()).isEqualTo("audit");
            assertThat(fact.project()).isEqualTo(database.binding().project().toString());
            assertThat(fact.scope()).isEqualTo(database.binding().scope().name());
            assertThat(fact.sql()).isEqualTo("SELECT id FROM audit.orders LIMIT 2");
            assertThat(fact.maxRows()).isEqualTo(20);
            assertThat(fact.rows()).hasSize(2);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            DatabaseMcpContract.EXECUTE_DML,
            DatabaseMcpContract.EXECUTE_DDL,
            DatabaseMcpContract.EXECUTE_NOSQL_WRITE_DELETE,
            DatabaseMcpContract.EXECUTE_NOSQL_QUERY,
            "unknown_database_tool"
    })
    void rejectsEveryWriteOrNosqlTool(String toolName) {
        assertThatThrownBy(() -> audit.audit(
                List.of(call("unsafe", toolName, objectMapper.createObjectNode(), rows(0))),
                boundary(), database, selectStatement()))
                .hasMessageContaining("unapproved tool");
    }

    @Test
    void requiresExactNativeQueryArgumentsAndReadOnlySelectContext() {
        ObjectNode wrongMaxRows = nativeQueryArguments("SELECT id FROM orders LIMIT 2").put("maxRows", 2);
        assertThatThrownBy(() -> audit.audit(List.of(call("wrong-limit", DatabaseMcpContract.EXECUTE_QUERY,
                wrongMaxRows, rows(1))), boundary(), database, selectStatement()))
                .hasMessageContaining("maxRows");

        ObjectNode extra = nativeQueryArguments("SELECT id FROM orders LIMIT 2").put("catalog", "orders");
        assertThatThrownBy(() -> audit.audit(List.of(call("extra", DatabaseMcpContract.EXECUTE_QUERY,
                extra, rows(1))), boundary(), database, selectStatement()))
                .hasMessageContaining("unsupported argument");

        assertThatThrownBy(() -> audit.audit(List.of(call("dml-context", DatabaseMcpContract.EXECUTE_QUERY,
                nativeQueryArguments("SELECT id FROM orders LIMIT 2"), rows(1))), boundary(), database,
                new MyBatisToolCallAudit.StatementContext("update-orders", "update", false)))
                .hasMessageContaining("DML tasks");
        assertThatThrownBy(() -> audit.audit(List.of(call("select-key", DatabaseMcpContract.EXECUTE_QUERY,
                nativeQueryArguments("SELECT id FROM orders LIMIT 2"), rows(1))), boundary(), database,
                new MyBatisToolCallAudit.StatementContext("select-key", "select", true)))
                .hasMessageContaining("selectKey");
    }

    @Test
    void acceptsArrayResultAndRejectsRowsBeyondTheBound() {
        ArrayNode array = objectMapper.createArrayNode();
        array.addObject().put("id", 1);
        assertThat(audit.audit(List.of(call("array", DatabaseMcpContract.EXECUTE_QUERY,
                nativeQueryArguments("SELECT id FROM orders LIMIT 1"), array)), boundary(), database,
                selectStatement()).facts().getFirst().rows()).hasSize(1);

        assertThatThrownBy(() -> audit.audit(List.of(call("too-many", DatabaseMcpContract.EXECUTE_QUERY,
                nativeQueryArguments("SELECT id FROM orders LIMIT 20"), rows(21))), boundary(), database,
                selectStatement())).hasMessageContaining("at most 20 rows");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO orders(id) VALUES (1)",
            "UPDATE orders SET status = 'DONE'",
            "DELETE FROM orders",
            "SELECT id FROM orders LIMIT 1 FOR UPDATE",
            "SELECT id INTO archived_orders FROM orders LIMIT 1",
            "COPY orders TO '/tmp/orders.csv'",
            "CALL refresh_orders()",
            "CREATE TABLE unsafe(id int)",
            "WITH changed AS (DELETE FROM orders RETURNING id) SELECT id FROM changed LIMIT 1",
            "SELECT id FROM public.orders LIMIT 1",
            "SELECT id FROM orders LIMIT 21",
            "SELECT id FROM orders"
    })
    void rejectsDmlDdlLocksAndUnsafeOrUnboundedRelations(String sql) {
        assertThatThrownBy(() -> audit.audit(List.of(call("unsafe-sql", DatabaseMcpContract.EXECUTE_QUERY,
                nativeQueryArguments(sql), rows(1))), boundary(), database, selectStatement()))
                .hasMessageContaining("rejected");
    }

    @Test
    void acceptsOnlyStrictlyBoundMetadataArguments() {
        ObjectNode metadata = commonArguments();
        assertThat(audit.audit(List.of(call("sources", DatabaseMcpContract.LIST_DATASOURCES, metadata,
                objectMapper.createArrayNode())), boundary(), database, selectStatement()).facts())
                .singleElement().extracting(MyBatisToolCallAudit.AuditedCallFact::toolName)
                .isEqualTo(DatabaseMcpContract.LIST_DATASOURCES);

        ObjectNode badMetadata = commonArguments().put("dataSource", database.binding().dataSource());
        assertThatThrownBy(() -> audit.audit(List.of(call("bad-sources", DatabaseMcpContract.LIST_DATASOURCES,
                badMetadata, objectMapper.createArrayNode())), boundary(), database, selectStatement()))
                .hasMessageContaining("unsupported argument");
    }

    private MyBatisToolCallAudit.Boundary boundary() {
        return new MyBatisToolCallAudit.Boundary(STARTED_AT, Set.of());
    }

    private MyBatisToolCallAudit.StatementContext selectStatement() {
        return new MyBatisToolCallAudit.StatementContext("mapper-order-find", "select", false);
    }

    private ObjectNode commonArguments() {
        return objectMapper.createObjectNode()
                .put("project", database.binding().project().toString())
                .put("scope", database.binding().scope().name());
    }

    private ObjectNode nativeQueryArguments(String sql) {
        return commonArguments().put("dataSource", database.binding().dataSource())
                .put("sql", sql).put("maxRows", DatabaseMcpContract.MAX_ROWS);
    }

    private ObjectNode rows(int count) {
        ObjectNode result = objectMapper.createObjectNode();
        result.putArray("columns").add("id");
        ArrayNode rows = result.putArray("rows");
        for (int index = 1; index <= count; index++) rows.addObject().put("id", index);
        return result;
    }

    private AgentBridgeClient.ToolCallRecord call(String id, String toolName, JsonNode arguments, JsonNode result) {
        return new AgentBridgeClient.ToolCallRecord(id, toolName, toolName, "mcp", "completed",
                STARTED_AT.plusSeconds(1), arguments, result, 10L, objectMapper.createObjectNode());
    }
}
