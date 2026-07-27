package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
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

    @Test
    void acceptsOptionalAgentBridgeInvocationMetadataOnDatabaseCalls() {
        ObjectNode arguments = nativeQueryArguments("SELECT id FROM audit.orders LIMIT 2")
                .put("title", "Review bounded SQL")
                .put("keywords", "orders audit");

        MyBatisToolCallAudit.Result result = audit.audit(
                List.of(call("query-with-metadata", DatabaseMcpContract.EXECUTE_QUERY, arguments, rows(2))),
                boundary(), database, selectStatement());

        assertThat(result.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.arguments().path("title").asText()).isEqualTo("Review bounded SQL");
            assertThat(fact.arguments().path("keywords").asText()).isEqualTo("orders audit");
        });
    }

    @Test
    void acceptsAgentBridgeSuccessAndRejectsEveryOtherStatus() {
        assertThat(audit.audit(List.of(call("success", DatabaseMcpContract.EXECUTE_QUERY,
                nativeQueryArguments("SELECT id FROM orders LIMIT 1"), rows(1))), boundary(), database,
                selectStatement()).auditedCallIds()).containsExactly("success");

        assertThatThrownBy(() -> audit.audit(List.of(callWithStatus("failed", "failed", rows(1))),
                boundary(), database, selectStatement())).hasMessageContaining("not successful");
    }

    @Test
    void allowsSuccessfulReportWritesWithoutTreatingThemAsDatabaseEvidence() {
        Path candidateDirectory = Path.of("/workspace/out");
        AgentBridgeClient.ToolCallRecord reportWrite = call(
                "write-report",
                "write_file",
                objectMapper.createObjectNode()
                        .put("path", candidateDirectory.resolve("report.md").toString())
                        .put("content", "review"),
                objectMapper.getNodeFactory().textNode("Created /workspace/out/report.md")
        );

        MyBatisToolCallAudit.Result result = audit.audit(
                List.of(reportWrite), boundary(), database,
                new MyBatisToolCallAudit.StatementContext(
                        "delete-order", "delete", false, candidateDirectory
                )
        );

        assertThat(result.facts()).isEmpty();
        assertThat(result.auditedCallIds()).isEmpty();
    }

    @Test
    void auditsCompleteLongReportWriteAtTheExactCandidatePath() {
        Path candidateDirectory = Path.of("/workspace/out");
        String reportBody = "审".repeat(9_000);
        AgentBridgeClient.ToolCallRecord reportWrite = call(
                "write-long-report",
                "write_file",
                objectMapper.createObjectNode()
                        .put("path", candidateDirectory.resolve("report.md").toString())
                        .put("content", reportBody),
                objectMapper.getNodeFactory().textNode("Created /workspace/out/report.md")
        );

        MyBatisToolCallAudit.Result result = audit.audit(
                List.of(reportWrite), boundary(), database,
                new MyBatisToolCallAudit.StatementContext(
                        "delete-order", "delete", false, candidateDirectory
                )
        );

        assertThat(reportWrite.arguments().path("content").asText()).hasSize(9_000);
        assertThat(result.facts()).isEmpty();
        assertThat(result.auditedCallIds()).isEmpty();
    }

    @Test
    void rejectsCompleteLongReportWriteOutsideTheCandidateDirectory() {
        AgentBridgeClient.ToolCallRecord wrongWrite = call(
                "write-long-elsewhere",
                "write_file",
                objectMapper.createObjectNode()
                        .put("path", "/workspace/elsewhere/report.md")
                        .put("content", "审".repeat(9_000)),
                objectMapper.getNodeFactory().textNode("Created /workspace/elsewhere/report.md")
        );

        assertThatThrownBy(() -> audit.audit(
                List.of(wrongWrite), boundary(), database,
                new MyBatisToolCallAudit.StatementContext(
                        "delete-order", "delete", false, Path.of("/workspace/out")
                )
        )).hasMessageContaining("candidate output path");
    }

    @Test
    void rejectsReportWritesOutsideTheCurrentCandidateDirectory() {
        AgentBridgeClient.ToolCallRecord wrongWrite = call(
                "write-elsewhere",
                "write_file",
                objectMapper.createObjectNode()
                        .put("path", "/workspace/elsewhere/report.md")
                        .put("content", "review"),
                objectMapper.getNodeFactory().textNode("Created /workspace/elsewhere/report.md")
        );

        assertThatThrownBy(() -> audit.audit(
                List.of(wrongWrite), boundary(), database,
                new MyBatisToolCallAudit.StatementContext(
                        "delete-order", "delete", false, Path.of("/workspace/out")
                )
        )).hasMessageContaining("candidate output path");
    }

    @Test
    void rejectsMissingOrNullResultsForMetadataAndQueryCalls() {
        for (JsonNode missing : List.of(objectMapper.getNodeFactory().missingNode(), objectMapper.nullNode())) {
            assertThatThrownBy(() -> audit.audit(List.of(call("metadata-result", DatabaseMcpContract.LIST_DATASOURCES,
                    commonArguments(), missing)), boundary(), database, selectStatement()))
                    .hasMessageContaining("incomplete");
            assertThatThrownBy(() -> audit.audit(List.of(call("query-result", DatabaseMcpContract.EXECUTE_QUERY,
                    nativeQueryArguments("SELECT id FROM orders LIMIT 1"), missing)), boundary(), database,
                    selectStatement())).hasMessageContaining("incomplete");
        }
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

    @Test
    void acceptsRealDatabaseMcpQueryEnvelopeWithoutColumns() {
        ObjectNode actual = objectMapper.createObjectNode()
                .put("mode", "QUERY")
                .put("updateCount", -1)
                .put("hasResultSet", true)
                .put("rowCount", 1)
                .put("dataSource", database.binding().dataSource());
        actual.putArray("rows").addObject().put("id", 7);

        MyBatisToolCallAudit.Result result = audit.audit(
                List.of(call("real-query", DatabaseMcpContract.EXECUTE_QUERY,
                        nativeQueryArguments("SELECT id FROM orders LIMIT 1"), actual)),
                boundary(), database, selectStatement()
        );

        assertThat(result.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.columns()).containsExactly("id");
            assertThat(fact.rows()).singleElement().satisfies(row ->
                    assertThat(row.path("id").asInt()).isEqualTo(7));
            assertThat(fact.resultData().path("columns").get(0).asText()).isEqualTo("id");
        });
    }

    @Test
    void auditsCompleteDatabaseResultAboveLegacySummaryLength() throws Exception {
        ObjectNode actual = objectMapper.createObjectNode()
                .put("mode", "QUERY")
                .put("updateCount", -1)
                .put("hasResultSet", true)
                .put("rowCount", 1)
                .put("dataSource", database.binding().dataSource());
        actual.putArray("columns").add("id").add("evidence");
        actual.putArray("rows").addObject()
                .put("id", 7)
                .put("evidence", "数".repeat(9_000));

        assertThat(objectMapper.writeValueAsBytes(actual).length)
                .isGreaterThan(8_000)
                .isLessThan(MyBatisToolCallAudit.MAX_TOOL_RESULT_BYTES);

        MyBatisToolCallAudit.Result result = audit.audit(
                List.of(call("long-query", DatabaseMcpContract.EXECUTE_QUERY,
                        nativeQueryArguments("SELECT id FROM orders LIMIT 1"), actual)),
                boundary(), database, selectStatement()
        );

        assertThat(result.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.rows()).singleElement().satisfies(row ->
                    assertThat(row.path("evidence").asText()).hasSize(9_000));
            assertThat(fact.resultData().path("rows").get(0).path("evidence").asText())
                    .hasSize(9_000);
        });
    }

    @Test
    void rejectsRealDatabaseMcpQueryEnvelopeForAnotherDataSource() {
        ObjectNode actual = objectMapper.createObjectNode()
                .put("mode", "QUERY")
                .put("updateCount", -1)
                .put("hasResultSet", true)
                .put("rowCount", 1)
                .put("dataSource", "different-source");
        actual.putArray("rows").addObject().put("id", 7);

        assertThatThrownBy(() -> audit.audit(
                List.of(call("wrong-source", DatabaseMcpContract.EXECUTE_QUERY,
                        nativeQueryArguments("SELECT id FROM orders LIMIT 1"), actual)),
                boundary(), database, selectStatement()
        )).hasMessageContaining("envelope");
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

    @Test
    void acceptsEachNativeMetadataArgumentContract() {
        ObjectNode databases = commonArguments().put("dataSource", database.binding().dataSource());
        ObjectNode tableSchema = databases.deepCopy().put("catalog", database.binding().catalog())
                .put("schema", database.binding().schema()).put("includeColumns", true)
                .put("includeIndexes", true).put("maxTables", DatabaseMcpContract.MAX_TABLES);
        assertThat(audit.audit(List.of(
                call("sources", DatabaseMcpContract.LIST_DATASOURCES, commonArguments(), objectMapper.createArrayNode()),
                call("catalogs", DatabaseMcpContract.LIST_DATABASES, databases, objectMapper.createArrayNode()),
                call("tables", DatabaseMcpContract.LIST_TABLE_SCHEMA, tableSchema, objectMapper.createObjectNode())),
                boundary(), database, selectStatement()).auditedCallIds())
                .containsExactly("sources", "catalogs", "tables");
    }

    @Test
    void enforcesBoundaryCompletenessQueryLimitsAndDefensiveAuditFacts() {
        AgentBridgeClient.ToolCallRecord old = new AgentBridgeClient.ToolCallRecord("old", "old",
                DatabaseMcpContract.LIST_DATASOURCES, "mcp", "success", STARTED_AT.minusSeconds(1),
                commonArguments(), objectMapper.createArrayNode(), 0L, objectMapper.createObjectNode());
        assertThat(audit.audit(List.of(old), new MyBatisToolCallAudit.Boundary(STARTED_AT, Set.of("old")),
                database, selectStatement()).facts()).isEmpty();
        assertThatThrownBy(() -> audit.audit(List.of(), new MyBatisToolCallAudit.Boundary(STARTED_AT, Set.of("old")),
                database, selectStatement())).hasMessageContaining("missing preexisting");
        assertThatThrownBy(() -> audit.audit(List.of(call("dup", DatabaseMcpContract.EXECUTE_QUERY,
                nativeQueryArguments("SELECT id FROM audit.orders LIMIT 1"), rows(1)), call("dup",
                DatabaseMcpContract.EXECUTE_QUERY, nativeQueryArguments("SELECT id FROM orders LIMIT 1"), rows(1))),
                boundary(), database, selectStatement())).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> audit.audit(List.of(call("one", DatabaseMcpContract.EXECUTE_QUERY,
                nativeQueryArguments("SELECT id FROM orders LIMIT 1"), rows(1)), call("two",
                DatabaseMcpContract.EXECUTE_QUERY, nativeQueryArguments("SELECT id FROM orders LIMIT 1"), rows(1)),
                call("three", DatabaseMcpContract.EXECUTE_QUERY, nativeQueryArguments("SELECT id FROM orders LIMIT 1"), rows(1)),
                call("four", DatabaseMcpContract.EXECUTE_QUERY, nativeQueryArguments("SELECT id FROM orders LIMIT 1"), rows(1))),
                boundary(), database, selectStatement())).hasMessageContaining("at most 3");
        MyBatisToolCallAudit.Result result = audit.audit(List.of(call("copy", DatabaseMcpContract.EXECUTE_QUERY,
                nativeQueryArguments("SELECT id FROM orders LIMIT 1"), rows(1))), boundary(), database, selectStatement());
        ((ObjectNode) result.facts().getFirst().arguments()).put("sql", "mutated");
        assertThat(result.facts().getFirst().arguments().path("sql").asText()).contains("SELECT");
        assertThat(MyBatisToolCallAudit.AuditedCallFact.class.getDeclaredConstructors())
                .allMatch(constructor -> java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
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
        return new AgentBridgeClient.ToolCallRecord(id, toolName, toolName, "mcp", "success",
                STARTED_AT.plusSeconds(1), arguments, result, 10L, objectMapper.createObjectNode());
    }

    private AgentBridgeClient.ToolCallRecord callWithStatus(String id, String status, JsonNode result) {
        return new AgentBridgeClient.ToolCallRecord(id, DatabaseMcpContract.EXECUTE_QUERY,
                DatabaseMcpContract.EXECUTE_QUERY, "mcp", status, STARTED_AT.plusSeconds(1),
                nativeQueryArguments("SELECT id FROM orders LIMIT 1"), result, 10L, objectMapper.createObjectNode());
    }
}
