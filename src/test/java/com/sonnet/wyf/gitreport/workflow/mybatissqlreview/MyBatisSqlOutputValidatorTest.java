package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisSqlOutputValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MyBatisSqlOutputValidator validator = new MyBatisSqlOutputValidator(objectMapper);

    @TempDir
    Path tempDir;

    @Test
    void validatesExactlyThreeCompleteCandidateArtifactsAgainstSchemaAndEvidenceContract() throws Exception {
        Path candidates = validCandidates();

        MyBatisSqlOutputValidator.Result result = validator.validate(candidates);

        assertThat(result.statementKey()).isEqualTo("mapper-order-find-open");
        assertThat(result.scenarioCount()).isEqualTo(1);
        assertThat(result.evidenceBytes()).isPositive().isLessThanOrEqualTo(262_144);
    }

    @Test
    void rejectsSummarySchemaViolationsMissingReportSectionsAndPlaceholders() throws Exception {
        Path candidates = validCandidates();
        ObjectNode summary = (ObjectNode) objectMapper.readTree(candidates.resolve("summary.json").toFile());
        summary.remove("risk_level");
        writeJson(candidates.resolve("summary.json"), summary);
        Path invalidSummaryCandidates = candidates;
        assertThatThrownBy(() -> validator.validate(invalidSummaryCandidates))
                .hasMessageContaining("summary schema").hasMessageContaining("risk_level");

        candidates = validCandidates();
        Files.writeString(candidates.resolve("report.md"), """
                # SQL Review
                ## Statement
                ## Static Analysis
                ## Database Evidence
                ## Findings
                ## Recommendations
                """, StandardCharsets.UTF_8);
        Path missingSectionCandidates = candidates;
        assertThatThrownBy(() -> validator.validate(missingSectionCandidates))
                .hasMessageContaining("## Limitations");

        candidates = validCandidates();
        Files.writeString(candidates.resolve("report.md"),
                Files.readString(candidates.resolve("report.md")) + "\n{{REPLACE_ME}}\n", StandardCharsets.UTF_8);
        Path placeholderCandidates = candidates;
        assertThatThrownBy(() -> validator.validate(placeholderCandidates))
                .hasMessageContaining("placeholder");
    }

    @Test
    void rejectsMoreThanThreeScenariosMoreThanTwentyRowsAndOversizedEvidence() throws Exception {
        Path candidates = validCandidates();
        ObjectNode evidence = evidence(candidates);
        ArrayNode scenarios = (ArrayNode) evidence.path("scenarios");
        JsonNode original = scenarios.get(0);
        for (int index = 2; index <= 4; index++) {
            ObjectNode copy = original.deepCopy();
            copy.put("scenario_id", "S-" + index);
            copy.put("evidence_id", "E-" + (index + 1));
            copy.put("tool_call_id", "call-" + (index + 1));
            scenarios.add(copy);
            ((ArrayNode) evidence.at("/audit/tool_call_ids")).add("call-" + (index + 1));
        }
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path fourScenarioCandidates = candidates;
        assertThatThrownBy(() -> validator.validate(fourScenarioCandidates))
                .hasMessageContaining("at most 3 scenarios");

        candidates = validCandidates();
        evidence = evidence(candidates);
        ArrayNode rows = (ArrayNode) evidence.at("/scenarios/0/rows");
        while (rows.size() < 21) {
            rows.addObject().put("id", rows.size() + 1).put("status", "OPEN");
        }
        ((ObjectNode) evidence.at("/scenarios/0")).put("row_count", rows.size());
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path tooManyRowsCandidates = candidates;
        assertThatThrownBy(() -> validator.validate(tooManyRowsCandidates))
                .hasMessageContaining("at most 20 rows");

        candidates = validCandidates();
        evidence = evidence(candidates);
        ((ArrayNode) evidence.path("limitations")).add("x".repeat(263_000));
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path oversizedCandidates = candidates;
        assertThatThrownBy(() -> validator.validate(oversizedCandidates))
                .hasMessageContaining("262144 bytes");
    }

    @Test
    void rejectsBrokenEvidenceReferencesCountsAndUnexpectedCandidateFiles() throws Exception {
        Path candidates = validCandidates();
        ObjectNode evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/scenarios/0")).put("row_count", 19);
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path countMismatchCandidates = candidates;
        assertThatThrownBy(() -> validator.validate(countMismatchCandidates))
                .hasMessageContaining("row_count");

        candidates = validCandidates();
        evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/scenarios/0")).put("tool_call_id", "not-audited");
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path staleEvidenceCandidates = candidates;
        assertThatThrownBy(() -> validator.validate(staleEvidenceCandidates))
                .hasMessageContaining("audited tool_call_id");

        candidates = validCandidates();
        Files.writeString(candidates.resolve("extra.txt"), "must not be written", StandardCharsets.UTF_8);
        Path extraFileCandidates = candidates;
        assertThatThrownBy(() -> validator.validate(extraFileCandidates))
                .hasMessageContaining("exactly three candidate files");
    }

    @Test
    void buildsCompletePostHocPromptAndShipsCompleteTemplateAndSchema() throws Exception {
        MyBatisSqlPromptBuilder builder = new MyBatisSqlPromptBuilder(objectMapper);
        MyBatisSqlPromptBuilder.Context context = new MyBatisSqlPromptBuilder.Context(
                "mapper-order-find-open",
                "select",
                false,
                "SELECT id FROM orders WHERE status = #{status}",
                List.of("if", "where"),
                List.of("#{status}"),
                "gauss-readonly",
                "orders",
                "audit",
                tempDir.resolve("candidates")
        );

        String prompt = builder.build(context);

        assertThat(prompt)
                .contains("post-hoc")
                .contains("cannot prevent an already executed SQL statement")
                .contains("never execute the original DML or selectKey")
                .contains("execute_sql_query")
                .contains("at most 3")
                .contains("LIMIT <= 20")
                .contains("262144 bytes")
                .contains("report.md", "summary.json", "database-evidence.json")
                .contains("mapper-order-find-open", "gauss-readonly", "#{status}")
                .doesNotContain("{{", "TODO", "TBD");

        String template = resource("/mybatis-sql-review-prompt-pack/templates/sql-detail-report.md");
        JsonNode schema = objectMapper.readTree(resource(
                "/mybatis-sql-review-prompt-pack/schemas/sql-summary.schema.json"));
        assertThat(template).contains(
                "# SQL Review", "## Statement", "## Static Analysis", "## Database Evidence",
                "## Findings", "## Recommendations", "## Limitations");
        assertThat(template).doesNotContain("{{", "TODO", "TBD");
        assertThat(schema.path("$schema").asText()).contains("json-schema.org");
        assertThat(schema.path("required")).isNotEmpty();
        assertThat(schema.path("properties").path("findings").path("items").path("required")).isNotEmpty();
    }

    private Path validCandidates() throws IOException {
        Path candidates = Files.createTempDirectory(tempDir, "candidates-");
        copyFixture("report-valid.md", candidates.resolve("report.md"));
        copyFixture("sql-summary-valid.json", candidates.resolve("summary.json"));
        copyFixture("database-evidence-valid.json", candidates.resolve("database-evidence.json"));
        return candidates;
    }

    private ObjectNode evidence(Path candidates) throws IOException {
        return (ObjectNode) objectMapper.readTree(candidates.resolve("database-evidence.json").toFile());
    }

    private void copyFixture(String name, Path target) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/mybatis-sql-review-fixtures/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            Files.copy(input, target);
        }
    }

    private void writeJson(Path target, JsonNode value) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), value);
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
