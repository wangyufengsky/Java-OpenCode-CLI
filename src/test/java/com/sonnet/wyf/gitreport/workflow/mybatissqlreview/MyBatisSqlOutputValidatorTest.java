package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisSqlOutputValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MyBatisSqlOutputValidator validator = new MyBatisSqlOutputValidator(objectMapper);
    @TempDir Path tempDir;

    @Test
    void validatesOnlineEvidenceAgainstAuditedNativeFacts() throws Exception {
        Path candidates = candidates();
        assertThat(validate(candidates).scenarioCount()).isEqualTo(1);
    }

    @Test
    void rejectsEverySummaryBindingFieldThatDoesNotMatchOnlineDatabaseOrOfflineEvidence() throws Exception {
        for (String field : bindingFields()) {
            Path onlineCandidates = candidates();
            ObjectNode onlineSummary = summary(onlineCandidates);
            onlineSummary.put(field, mismatchedBindingValue(field));
            writeSummary(onlineCandidates, onlineSummary);
            assertThatThrownBy(() -> validate(onlineCandidates))
                    .hasMessageContaining("summary database binding")
                    .hasMessageContaining(field);

            Path offlineCandidates = candidates();
            ObjectNode offlineSummary = summary(offlineCandidates);
            offlineSummary.put(field, mismatchedBindingValue(field));
            writeSummary(offlineCandidates, offlineSummary);
            assertThatThrownBy(() -> validator.validatePublishedOffline(offlineCandidates, expected()))
                    .hasMessageContaining("summary database binding")
                    .hasMessageContaining(field);
        }
    }

    @Test
    void rejectsEveryMissingOrTamperedLabeledReportBindingOnlineAndOffline() throws Exception {
        for (String field : bindingFields()) {
            Path onlineCandidates = candidates();
            Files.writeString(onlineCandidates.resolve("report.md"), report(onlineCandidates)
                    .replace(reportBindingLine(field, database().binding()), ""));
            assertThatThrownBy(() -> validate(onlineCandidates))
                    .hasMessageContaining("report.md database binding")
                    .hasMessageContaining(field);

            Path offlineCandidates = candidates();
            Files.writeString(offlineCandidates.resolve("report.md"), report(offlineCandidates)
                    .replace(reportBindingLine(field, database().binding()), "- " + reportLabel(field) + ": `wrong`"));
            assertThatThrownBy(() -> validator.validatePublishedOffline(offlineCandidates, expected()))
                    .hasMessageContaining("report.md database binding")
                    .hasMessageContaining(field);
        }
    }

    @Test
    void rejectsUnboundDatabaseEvidenceFields() throws Exception {
        Path candidates = candidates();
        ObjectNode evidence = (ObjectNode) objectMapper.readTree(candidates.resolve("database-evidence.json").toFile());
        evidence.remove("data_source");
        objectMapper.writeValue(candidates.resolve("database-evidence.json").toFile(), evidence);
        assertThatThrownBy(() -> validator.validatePublishedOffline(candidates, expected()))
                .hasMessageContaining("data_source");
    }

    @Test
    void rejectsScenarioWithoutTheExactNativeMaxRowsContract() throws Exception {
        Path candidates = candidates();
        ObjectNode evidence = (ObjectNode) objectMapper.readTree(candidates.resolve("database-evidence.json").toFile());
        ((ObjectNode) evidence.at("/scenarios/0/arguments")).put("maxRows", 2);
        objectMapper.writeValue(candidates.resolve("database-evidence.json").toFile(), evidence);
        assertThatThrownBy(() -> validator.validatePublishedOffline(candidates, expected()))
                .hasMessageContaining("maxRows");
    }

    @Test
    void acceptsOptionalAgentBridgeTitleInPublishedDatabaseEvidence() throws Exception {
        Path candidates = candidates();
        ObjectNode evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/scenarios/0/arguments")).put("title", "Review bounded SQL");
        write(candidates, evidence);

        assertThat(validator.validatePublishedOffline(candidates, expected()).scenarioCount()).isEqualTo(1);
    }

    @Test
    void rejectsMismatchedAuditedArgumentsResultsRowsAndColumns() throws Exception {
        Path candidates = candidates();
        ObjectNode evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/metadata/0/arguments")).put("schema", "other");
        write(candidates, evidence);
        assertInvalid(candidates, "arguments");

        candidates = candidates(); evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/metadata/0/result")).put("catalog", "other");
        write(candidates, evidence);
        assertInvalid(candidates, "result");

        candidates = candidates(); evidence = evidence(candidates);
        ((ArrayNode) evidence.at("/scenarios/0/columns")).add("invented");
        write(candidates, evidence);
        assertInvalid(candidates, "columns");

        candidates = candidates(); evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/scenarios/0")).put("row_count", 99);
        write(candidates, evidence);
        assertInvalid(candidates, "row_count");
    }

    @Test
    void rejectsMissingDuplicateAndStaleCallEvidence() throws Exception {
        Path candidates = candidates();
        ObjectNode evidence = evidence(candidates);
        ((ArrayNode) evidence.path("metadata")).removeAll();
        write(candidates, evidence);
        assertInvalid(candidates, "missing evidence");

        candidates = candidates(); evidence = evidence(candidates);
        ObjectNode duplicate = ((ObjectNode) evidence.at("/metadata/0")).deepCopy();
        duplicate.put("evidence_id", "E-duplicate");
        ((ArrayNode) evidence.path("metadata")).add(duplicate);
        write(candidates, evidence);
        assertInvalid(candidates, "duplicate evidence");

        candidates = candidates(); evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/metadata/0")).put("tool_call_id", "stale");
        write(candidates, evidence);
        assertInvalid(candidates, "stale or extra");
    }

    @Test
    void rejectsTaskPreflightScenarioRowAndEvidenceSizeBounds() throws Exception {
        Path candidates = candidates();
        ObjectNode evidence = evidence(candidates); evidence.put("schema", "other"); write(candidates, evidence);
        assertInvalid(candidates, "preflight database binding");

        candidates = candidates(); evidence = evidence(candidates);
        ArrayNode scenarios = (ArrayNode) evidence.path("scenarios");
        for (int i = 2; i <= 4; i++) { ObjectNode copy = ((ObjectNode) scenarios.get(0)).deepCopy(); copy.put("scenario_id", "S-" + i).put("evidence_id", "E-" + (i + 1)); scenarios.add(copy); }
        write(candidates, evidence);
        assertInvalid(candidates, "at most 3 scenarios");

        candidates = candidates(); evidence = evidence(candidates);
        ((ArrayNode) evidence.path("limitations")).add("x".repeat(263_000)); write(candidates, evidence);
        assertInvalid(candidates, "262144 bytes");
    }

    @Test
    void rejectsAuditedStatementAndPreflightContextsFromAnotherTask() throws Exception {
        MyBatisDatabasePreflight.Result database = database();
        Path candidates = candidates();
        MyBatisSqlOutputValidator.ExpectedTaskContext otherTask = new MyBatisSqlOutputValidator.ExpectedTaskContext(
                "other-statement", "mappers/OrderMapper.xml", "com.example.OrderMapper", "findOpen", "select", false);
        assertThatThrownBy(() -> validator.validate(candidates, otherTask, database, auditedFacts(database)))
                .hasMessageContaining("audited statement context");

        MyBatisDatabasePreflight.Result otherDatabase = VerifiedMyBatisDatabaseFixture.verified(
                objectMapper, "Other ReadOnly", "other_orders", "other_audit", Set.of("orders"));
        assertThatThrownBy(() -> validator.validate(candidates(), expected(), database, auditedFacts(otherDatabase)))
                .hasMessageContaining("audited preflight binding");
    }

    @Test
    void rejectsReportSummarySchemaPlaceholdersAndAdditionalFiles() throws Exception {
        Path candidates = candidates();
        Files.writeString(candidates.resolve("report.md"), "# SQL Review\n{{TOKEN}}\n");
        assertInvalid(candidates, "placeholder");

        candidates = candidates();
        ObjectNode summary = (ObjectNode) objectMapper.readTree(candidates.resolve("summary.json").toFile()); summary.remove("risk_level");
        objectMapper.writeValue(candidates.resolve("summary.json").toFile(), summary);
        assertInvalid(candidates, "summary schema");

        candidates = candidates(); Files.writeString(candidates.resolve("extra.txt"), "extra");
        assertInvalid(candidates, "exactly three candidate files");
    }

    private MyBatisSqlOutputValidator.ExpectedTaskContext expected() {
        return new MyBatisSqlOutputValidator.ExpectedTaskContext("mapper-order-find-open", "mappers/OrderMapper.xml",
                "com.example.OrderMapper", "findOpen", "select", false);
    }

    private MyBatisDatabasePreflight.Result database() { return VerifiedMyBatisDatabaseFixture.verified(objectMapper); }

    private MyBatisSqlOutputValidator.Result validate(Path candidates) throws Exception {
        MyBatisDatabasePreflight.Result database = database();
        return validator.validate(candidates, expected(), database, auditedFacts(database));
    }
    private void assertInvalid(Path candidates, String expectedMessage) {
        assertThatThrownBy(() -> validate(candidates)).hasMessageContaining(expectedMessage);
    }

    private MyBatisToolCallAudit.Result auditedFacts(MyBatisDatabasePreflight.Result database) {
        ObjectNode metadataResult = objectMapper.createObjectNode().put("catalog", "orders").put("schema", "audit");
        metadataResult.putArray("tables").addObject().put("name", "orders").put("type", "TABLE");
        ObjectNode queryResult = objectMapper.createObjectNode(); queryResult.putArray("columns").add("id").add("status");
        ArrayNode queryRows = queryResult.putArray("rows");
        queryRows.addObject().put("id", 101).put("status", "OPEN");
        queryRows.addObject().put("id", 102).put("status", "OPEN");
        List<AgentBridgeClient.ToolCallRecord> calls = List.of(
                call("call-1", DatabaseMcpContract.LIST_TABLE_SCHEMA, tableArguments(database), metadataResult, Instant.parse("2026-07-22T09:05:00Z"), 10L),
                call("call-2", DatabaseMcpContract.EXECUTE_QUERY, queryArguments(database), queryResult, Instant.parse("2026-07-22T09:05:01Z"), 42L));
        return new MyBatisToolCallAudit(objectMapper).audit(calls,
                new MyBatisToolCallAudit.Boundary(Instant.parse("2026-07-22T09:00:00Z"), Set.of()), database,
                new MyBatisToolCallAudit.StatementContext("mapper-order-find-open", "select", false));
    }

    private ObjectNode tableArguments(MyBatisDatabasePreflight.Result database) {
        return commonArguments(database).put("dataSource", database.binding().dataSource()).put("catalog", database.binding().catalog()).put("schema", database.binding().schema()).put("includeColumns", true).put("includeIndexes", true).put("maxTables", 200);
    }
    private ObjectNode queryArguments(MyBatisDatabasePreflight.Result database) { return commonArguments(database).put("dataSource", database.binding().dataSource()).put("sql", "SELECT id, status FROM orders LIMIT 20").put("maxRows", 20); }
    private ObjectNode commonArguments(MyBatisDatabasePreflight.Result database) { return objectMapper.createObjectNode().put("project", database.binding().project().toString()).put("scope", database.binding().scope().name()); }
    private AgentBridgeClient.ToolCallRecord call(String id, String tool, JsonNode arguments, JsonNode result, Instant timestamp, long duration) { return new AgentBridgeClient.ToolCallRecord(id, tool, tool, "mcp", "success", timestamp, arguments, result, duration, objectMapper.createObjectNode()); }
    private ObjectNode evidence(Path candidates) throws IOException { return (ObjectNode) objectMapper.readTree(candidates.resolve("database-evidence.json").toFile()); }
    private void write(Path candidates, JsonNode evidence) throws IOException { objectMapper.writeValue(candidates.resolve("database-evidence.json").toFile(), evidence); }
    private ObjectNode summary(Path candidates) throws IOException { return (ObjectNode) objectMapper.readTree(candidates.resolve("summary.json").toFile()); }
    private void writeSummary(Path candidates, JsonNode summary) throws IOException { objectMapper.writeValue(candidates.resolve("summary.json").toFile(), summary); }
    private String report(Path candidates) throws IOException { return Files.readString(candidates.resolve("report.md")); }
    private List<String> bindingFields() { return List.of("data_source", "catalog", "schema", "project", "scope"); }
    private String mismatchedBindingValue(String field) { return "scope".equals(field) ? "GLOBAL" : "wrong-" + field; }
    private String reportLabel(String field) { return switch (field) { case "data_source" -> "Data source"; case "catalog" -> "Catalog"; case "schema" -> "Schema"; case "project" -> "Project"; case "scope" -> "Scope"; default -> throw new IllegalArgumentException(field); }; }
    private String reportBindingLine(String field, DatabaseMcpContract.Binding binding) { String value = switch (field) { case "data_source" -> binding.dataSource(); case "catalog" -> binding.catalog(); case "schema" -> binding.schema(); case "project" -> binding.project().toString(); case "scope" -> binding.scope().name(); default -> throw new IllegalArgumentException(field); }; return "- " + reportLabel(field) + ": `" + value + "`"; }

    private Path candidates() throws IOException {
        Path directory = Files.createDirectory(tempDir.resolve("candidates-" + System.nanoTime()));
        for (String name : new String[]{"report-valid.md", "sql-summary-valid.json", "database-evidence-valid.json"}) {
            String destination = switch (name) { case "report-valid.md" -> "report.md"; case "sql-summary-valid.json" -> "summary.json"; default -> "database-evidence.json"; };
            try (InputStream input = getClass().getResourceAsStream("/mybatis-sql-review-fixtures/" + name)) {
                if (input == null) throw new IllegalStateException("missing fixture " + name);
                Files.copy(input, directory.resolve(destination));
            }
        }
        return directory;
    }
}
