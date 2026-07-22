package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisSqlOutputValidatorTest {
    private static final Instant STARTED_AT = Instant.parse("2026-07-22T09:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MyBatisSqlOutputValidator validator = new MyBatisSqlOutputValidator(objectMapper);
    private final MyBatisDatabasePreflight.Result database =
            VerifiedMyBatisDatabaseFixture.verified(objectMapper);

    @TempDir
    Path tempDir;

    @Test
    void validatesCandidateArtifactsOnlyAgainstExpectedTaskDatabaseAndAuditedFacts() throws Exception {
        MyBatisSqlOutputValidator.Result result = validateCandidates(validCandidates());

        assertThat(result.statementKey()).isEqualTo("mapper-order-find-open");
        assertThat(result.scenarioCount()).isEqualTo(1);
        assertThat(result.evidenceBytes()).isPositive().isLessThanOrEqualTo(262_144);
        assertThat(result.auditedCallIds()).containsExactly("call-1", "call-2");
    }

    @Test
    void exposesNoWeakPathOnlyValidationApi() {
        assertThat(Arrays.stream(MyBatisSqlOutputValidator.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("validate"))
                .map(Method::getParameterTypes)
                .noneMatch(parameters -> parameters.length == 1 && parameters[0] == Path.class))
                .isTrue();
    }

    @Test
    void rejectsExpectedTaskAndPreflightBindingMismatches() throws Exception {
        Path candidates = validCandidates();
        ObjectNode summary = summary(candidates);
        summary.put("command_type", "update");
        writeJson(candidates.resolve("summary.json"), summary);
        Path wrongCommandType = candidates;
        assertThatThrownBy(() -> validateCandidates(wrongCommandType))
                .hasMessageContaining("expected task").hasMessageContaining("command_type");

        Path wrongDatabase = validCandidates();
        ObjectNode evidence = evidence(wrongDatabase);
        evidence.put("schema_name", "public");
        writeJson(wrongDatabase.resolve("database-evidence.json"), evidence);
        assertThatThrownBy(() -> validateCandidates(wrongDatabase))
                .hasMessageContaining("preflight database binding").hasMessageContaining("schema_name");

        Path wrongSelectKey = validCandidates();
        summary = summary(wrongSelectKey);
        summary.put("select_key", true);
        writeJson(wrongSelectKey.resolve("summary.json"), summary);
        assertThatThrownBy(() -> validateCandidates(wrongSelectKey))
                .hasMessageContaining("expected task").hasMessageContaining("select_key");
    }

    @Test
    void rejectsAuditedStatementOrDatabaseContextFromAnotherTask() throws Exception {
        MyBatisToolCallAudit.Result wrongStatement = auditedFacts(
                new MyBatisToolCallAudit.StatementContext("another-statement", "select", false),
                database
        );
        Path wrongStatementCandidates = validCandidates();
        assertThatThrownBy(() -> validator.validate(
                wrongStatementCandidates, expectedTask(), database, wrongStatement))
                .hasMessageContaining("audited statement context").hasMessageContaining("statement_key");

        MyBatisDatabasePreflight.Result otherDatabase = VerifiedMyBatisDatabaseFixture.verified(
                objectMapper,
                "other-connection",
                "Other Review",
                "other-db",
                "other_schema",
                Set.of("orders")
        );
        MyBatisToolCallAudit.Result wrongDatabase = auditedFacts(
                new MyBatisToolCallAudit.StatementContext("mapper-order-find-open", "select", false),
                otherDatabase
        );
        Path wrongDatabaseCandidates = validCandidates();
        assertThatThrownBy(() -> validator.validate(
                wrongDatabaseCandidates, expectedTask(), database, wrongDatabase))
                .hasMessageContaining("audited preflight binding");
    }

    @Test
    void mutationAttemptsOnAuditAccessorsCannotChangeValidatorInputs() throws Exception {
        MyBatisToolCallAudit.Result facts = auditedFacts();
        MyBatisToolCallAudit.AuditedCallFact metadata = facts.calls().get(0);
        MyBatisToolCallAudit.AuditedCallFact scenario = facts.calls().get(1);

        ((ObjectNode) metadata.arguments()).put("schemaName", "mutated");
        ((ObjectNode) metadata.resultData()).put("name", "mutated");
        ((ObjectNode) scenario.rows().getFirst()).put("status", "MUTATED");
        ((ObjectNode) scenario.resultData().at("/rows/0")).put("status", "MUTATED");

        MyBatisSqlOutputValidator.Result validated = validator.validate(
                validCandidates(), expectedTask(), database, facts);
        assertThat(validated.auditedCallIds()).containsExactly("call-1", "call-2");
    }

    @Test
    void rejectsMismatchedQueryRowsColumnsArgumentsResultAndToolIdentity() throws Exception {
        Path candidates = validCandidates();
        ObjectNode evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/scenarios/0")).put("query_text", "SELECT id FROM orders LIMIT 1");
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path wrongQuery = candidates;
        assertThatThrownBy(() -> validateCandidates(wrongQuery))
                .hasMessageContaining("query_text").hasMessageContaining("audited call call-2");

        candidates = validCandidates();
        evidence = evidence(candidates);
        ((ArrayNode) evidence.at("/scenarios/0/columns")).add("invented");
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path wrongColumns = candidates;
        assertThatThrownBy(() -> validateCandidates(wrongColumns))
                .hasMessageContaining("columns").hasMessageContaining("audited call call-2");

        candidates = validCandidates();
        evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/metadata/0/arguments")).put("schemaName", "public");
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path wrongArguments = candidates;
        assertThatThrownBy(() -> validateCandidates(wrongArguments))
                .hasMessageContaining("arguments").hasMessageContaining("audited call call-1");

        candidates = validCandidates();
        evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/metadata/0/result")).put("name", "invented");
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path wrongResult = candidates;
        assertThatThrownBy(() -> validateCandidates(wrongResult))
                .hasMessageContaining("result").hasMessageContaining("audited call call-1");

        candidates = validCandidates();
        evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/scenarios/0/result/rows/0")).put("status", "INVENTED");
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path wrongScenarioResult = candidates;
        assertThatThrownBy(() -> validateCandidates(wrongScenarioResult))
                .hasMessageContaining("result").hasMessageContaining("audited call call-2");

        candidates = validCandidates();
        evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/metadata/0")).put("tool_name", "preview_table_data");
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path wrongTool = candidates;
        assertThatThrownBy(() -> validateCandidates(wrongTool))
                .hasMessageContaining("tool_name").hasMessageContaining("audited call call-1");
    }

    @Test
    void rejectsMissingExtraStaleOrDuplicateEvidenceForAuditedCalls() throws Exception {
        Path candidates = validCandidates();
        ObjectNode evidence = evidence(candidates);
        ((ArrayNode) evidence.path("metadata")).removeAll();
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path missingEvidence = candidates;
        assertThatThrownBy(() -> validateCandidates(missingEvidence))
                .hasMessageContaining("missing evidence for audited call").hasMessageContaining("call-1");

        candidates = validCandidates();
        evidence = evidence(candidates);
        ObjectNode extra = ((ObjectNode) evidence.at("/metadata/0")).deepCopy();
        extra.put("evidence_id", "E-extra");
        extra.put("tool_call_id", "stale-call");
        ((ArrayNode) evidence.path("metadata")).add(extra);
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path extraEvidence = candidates;
        assertThatThrownBy(() -> validateCandidates(extraEvidence))
                .hasMessageContaining("stale or extra tool_call_id");

        candidates = validCandidates();
        evidence = evidence(candidates);
        ObjectNode duplicate = ((ObjectNode) evidence.at("/metadata/0")).deepCopy();
        duplicate.put("evidence_id", "E-duplicate");
        ((ArrayNode) evidence.path("metadata")).add(duplicate);
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path duplicateEvidence = candidates;
        assertThatThrownBy(() -> validateCandidates(duplicateEvidence))
                .hasMessageContaining("duplicate evidence for audited call").hasMessageContaining("call-1");
    }

    @Test
    void rejectsSummarySchemaViolationsMissingReportSectionsAndPlaceholders() throws Exception {
        Path candidates = validCandidates();
        ObjectNode summary = summary(candidates);
        summary.remove("risk_level");
        writeJson(candidates.resolve("summary.json"), summary);
        Path invalidSummary = candidates;
        assertThatThrownBy(() -> validateCandidates(invalidSummary))
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
        Path missingSection = candidates;
        assertThatThrownBy(() -> validateCandidates(missingSection)).hasMessageContaining("## Limitations");

        candidates = validCandidates();
        Files.writeString(candidates.resolve("report.md"),
                Files.readString(candidates.resolve("report.md")) + "\n{{REPLACE_ME}}\n", StandardCharsets.UTF_8);
        Path placeholder = candidates;
        assertThatThrownBy(() -> validateCandidates(placeholder)).hasMessageContaining("placeholder");
    }

    @Test
    void requiresDatabaseEvidenceSectionToBeOnlyTheExactRelativeJsonLink() throws Exception {
        Path extraClaim = validCandidates();
        String report = Files.readString(extraClaim.resolve("report.md"));
        Files.writeString(
                extraClaim.resolve("report.md"),
                report.replace(
                        "[database-evidence.json](database-evidence.json)",
                        "[database-evidence.json](database-evidence.json)\n\nThe sample confirms the index is safe."),
                StandardCharsets.UTF_8
        );
        assertThatThrownBy(() -> validateCandidates(extraClaim))
                .hasMessageContaining("Database Evidence section")
                .hasMessageContaining("exact relative link");

        Path absoluteLink = validCandidates();
        report = Files.readString(absoluteLink.resolve("report.md"));
        Files.writeString(
                absoluteLink.resolve("report.md"),
                report.replace(
                        "[database-evidence.json](database-evidence.json)",
                        "[database-evidence.json](/tmp/database-evidence.json)"),
                StandardCharsets.UTF_8
        );
        assertThatThrownBy(() -> validateCandidates(absoluteLink))
                .hasMessageContaining("Database Evidence section")
                .hasMessageContaining("exact relative link");

        Path duplicateSection = validCandidates();
        Files.writeString(
                duplicateSection.resolve("report.md"),
                Files.readString(duplicateSection.resolve("report.md"))
                        + "\n## Database Evidence\n\nA second section invents a claim.\n",
                StandardCharsets.UTF_8
        );
        assertThatThrownBy(() -> validateCandidates(duplicateSection))
                .hasMessageContaining("exactly one Database Evidence section");
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
        }
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path tooManyScenarios = candidates;
        assertThatThrownBy(() -> validateCandidates(tooManyScenarios))
                .hasMessageContaining("at most 3 scenarios");

        candidates = validCandidates();
        evidence = evidence(candidates);
        ArrayNode rows = (ArrayNode) evidence.at("/scenarios/0/rows");
        while (rows.size() < 21) {
            rows.addObject().put("id", rows.size() + 1).put("status", "OPEN");
        }
        ((ObjectNode) evidence.at("/scenarios/0")).put("row_count", rows.size());
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path tooManyRows = candidates;
        assertThatThrownBy(() -> validateCandidates(tooManyRows)).hasMessageContaining("at most 20 rows");

        candidates = validCandidates();
        evidence = evidence(candidates);
        ((ArrayNode) evidence.path("limitations")).add("x".repeat(263_000));
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path oversized = candidates;
        assertThatThrownBy(() -> validateCandidates(oversized)).hasMessageContaining("262144 bytes");
    }

    @Test
    void rejectsRowCountMismatchAndUnexpectedCandidateFiles() throws Exception {
        Path candidates = validCandidates();
        ObjectNode evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/scenarios/0")).put("row_count", 19);
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        Path mismatchedRowCount = candidates;
        assertThatThrownBy(() -> validateCandidates(mismatchedRowCount)).hasMessageContaining("row_count");

        candidates = validCandidates();
        Files.writeString(candidates.resolve("extra.txt"), "must not be written", StandardCharsets.UTF_8);
        Path extraFile = candidates;
        assertThatThrownBy(() -> validateCandidates(extraFile))
                .hasMessageContaining("exactly three candidate files");
    }

    @Test
    void rejectsUnknownJsonSchemaKeywordsFailClosed() throws Exception {
        JsonNode unsupportedSchema = objectMapper.readTree("""
                {"type":"object","patternProperties":{"^x":{"type":"string"}}}
                """);

        assertThatThrownBy(() -> new MyBatisSqlOutputValidator(objectMapper, unsupportedSchema))
                .hasMessageContaining("unknown JSON schema keyword").hasMessageContaining("patternProperties");
    }

    @Test
    void offlineValidationRejectsReportSummaryAndEvidenceSemanticDamage() throws Exception {
        Path missingSection = validCandidates();
        Files.writeString(missingSection.resolve("report.md"), "# SQL Review\n");
        assertThatThrownBy(() -> validator.validatePublishedOffline(missingSection, expectedTask()))
                .hasMessageContaining("required section");

        Path invalidSummary = validCandidates();
        ObjectNode summary = summary(invalidSummary);
        summary.remove("risk_level");
        writeJson(invalidSummary.resolve("summary.json"), summary);
        assertThatThrownBy(() -> validator.validatePublishedOffline(invalidSummary, expectedTask()))
                .hasMessageContaining("summary schema");

        Path mismatchedEvidence = validCandidates();
        ObjectNode evidence = evidence(mismatchedEvidence);
        ((ObjectNode) evidence.at("/scenarios/0")).put("row_count", 99);
        writeJson(mismatchedEvidence.resolve("database-evidence.json"), evidence);
        assertThatThrownBy(() -> validator.validatePublishedOffline(mismatchedEvidence, expectedTask()))
                .hasMessageContaining("row_count");
    }

    @Test
    void offlineAndOnlineEvidencePoliciesRejectTheSameDeterminableSqlViolation() throws Exception {
        String unsafe = "SELECT id FROM public.orders LIMIT 20";
        assertThatThrownBy(() -> MyBatisToolCallAudit.validateDeterminableScenario(
                unsafe, "call-2", "audit", 42
        )).hasMessageContaining("outside the configured schema");

        Path candidates = validCandidates();
        ObjectNode evidence = evidence(candidates);
        ((ObjectNode) evidence.at("/scenarios/0")).put("query_text", unsafe);
        ((ObjectNode) evidence.at("/scenarios/0/arguments")).put("queryText", unsafe);
        writeJson(candidates.resolve("database-evidence.json"), evidence);
        assertThatThrownBy(() -> validator.validatePublishedOffline(candidates, expectedTask()))
                .hasMessageContaining("outside the configured schema");

        Path metadataQuery = validCandidates();
        evidence = evidence(metadataQuery);
        ((ObjectNode) evidence.at("/metadata/0")).put("tool_name", "execute_sql_query");
        writeJson(metadataQuery.resolve("database-evidence.json"), evidence);
        assertThatThrownBy(() -> validator.validatePublishedOffline(metadataQuery, expectedTask()))
                .hasMessageContaining("must be represented as a scenario");

        Path unsafePreview = validCandidates();
        evidence = evidence(unsafePreview);
        ObjectNode metadata = (ObjectNode) evidence.at("/metadata/0");
        metadata.put("tool_name", "preview_table_data");
        ObjectNode previewArguments = (ObjectNode) metadata.path("arguments");
        previewArguments.put("tableName", "orders").put("maxRowCount", 21);
        writeJson(unsafePreview.resolve("database-evidence.json"), evidence);
        assertThatThrownBy(() -> validator.validatePublishedOffline(unsafePreview, expectedTask()))
                .hasMessageContaining("maxRowCount").hasMessageContaining("1..20");
    }

    @ParameterizedTest(name = "database binding parity for {0}")
    @MethodSource("metadataBindingCases")
    void onlineAndOfflineApplyTheSameBindingTierForEveryMetadataTool(
            String toolName,
            String deepestRequiredField
    ) throws Exception {
        ObjectNode validArguments = metadataArguments(toolName);
        ObjectNode result = objectMapper.createObjectNode();
        if ("preview_table_data".equals(toolName)) {
            result.putArray("columns").add("id");
            result.putArray("rows");
        } else {
            result.put("ok", true);
        }
        AgentBridgeClient.ToolCallRecord validCall = toolCall(
                "metadata-call", toolName, validArguments, result,
                Instant.parse("2026-07-22T09:05:00Z"), 10L
        );
        assertThat(new MyBatisToolCallAudit(objectMapper).audit(
                List.of(validCall),
                new MyBatisToolCallAudit.Boundary(STARTED_AT, Set.of()),
                database,
                new MyBatisToolCallAudit.StatementContext("mapper-order-find-open", "select", false)
        ).auditedCallIds()).containsExactly("metadata-call");

        Path validOffline = validCandidates();
        ObjectNode validEvidence = evidence(validOffline);
        ObjectNode metadata = (ObjectNode) validEvidence.at("/metadata/0");
        metadata.put("tool_name", toolName).set("arguments", validArguments);
        writeJson(validOffline.resolve("database-evidence.json"), validEvidence);
        assertThat(validator.validatePublishedOffline(validOffline, expectedTask()).scenarioCount())
                .isEqualTo(1);

        if (deepestRequiredField.isEmpty()) {
            return;
        }
        ObjectNode wrongOnlineArguments = metadataArguments(toolName)
                .put(deepestRequiredField, "wrong-target");
        AgentBridgeClient.ToolCallRecord wrongCall = toolCall(
                "metadata-call", toolName, wrongOnlineArguments, result,
                Instant.parse("2026-07-22T09:05:00Z"), 10L
        );
        assertThatThrownBy(() -> new MyBatisToolCallAudit(objectMapper).audit(
                List.of(wrongCall),
                new MyBatisToolCallAudit.Boundary(STARTED_AT, Set.of()),
                database,
                new MyBatisToolCallAudit.StatementContext("mapper-order-find-open", "select", false)
        )).hasMessageContaining(deepestRequiredField);

        Path wrongOffline = validCandidates();
        ObjectNode wrongEvidence = evidence(wrongOffline);
        metadata = (ObjectNode) wrongEvidence.at("/metadata/0");
        metadata.put("tool_name", toolName)
                .set("arguments", metadataArguments(toolName).put(deepestRequiredField, "wrong-target"));
        writeJson(wrongOffline.resolve("database-evidence.json"), wrongEvidence);
        assertThatThrownBy(() -> validator.validatePublishedOffline(wrongOffline, expectedTask()))
                .hasMessageContaining(deepestRequiredField);
    }

    private static Stream<Arguments> metadataBindingCases() {
        return Stream.of(
                Arguments.of("list_database_connections", ""),
                Arguments.of("test_database_connection", "connectionId"),
                Arguments.of("list_recent_sql_queries", "connectionId"),
                Arguments.of("list_database_schemas", "databaseName"),
                Arguments.of("list_schema_object_kinds", "schemaName"),
                Arguments.of("list_schema_objects", "schemaName"),
                Arguments.of("preview_table_data", "schemaName"),
                Arguments.of("get_database_object_description", "schemaName")
        );
    }

    private ObjectNode metadataArguments(String toolName) {
        ObjectNode arguments = objectMapper.createObjectNode();
        if ("list_database_connections".equals(toolName)) {
            return arguments;
        }
        arguments.put("connectionId", database.connectionId());
        if ("test_database_connection".equals(toolName)
                || "list_recent_sql_queries".equals(toolName)) {
            return arguments;
        }
        arguments.put("databaseName", database.databaseName());
        if (!"list_database_schemas".equals(toolName)) {
            arguments.put("schemaName", database.schemaName());
        }
        if ("preview_table_data".equals(toolName)) {
            arguments.put("tableName", "orders").put("maxRowCount", 20);
        }
        return arguments;
    }

    private MyBatisSqlOutputValidator.Result validateCandidates(Path candidates) throws Exception {
        return validator.validate(candidates, expectedTask(), database, auditedFacts());
    }

    private MyBatisSqlOutputValidator.ExpectedTaskContext expectedTask() {
        return new MyBatisSqlOutputValidator.ExpectedTaskContext(
                "mapper-order-find-open",
                "mappers/OrderMapper.xml",
                "com.example.OrderMapper",
                "findOpen",
                "select",
                false
        );
    }

    private MyBatisToolCallAudit.Result auditedFacts() {
        return auditedFacts(
                new MyBatisToolCallAudit.StatementContext("mapper-order-find-open", "select", false),
                database
        );
    }

    private MyBatisToolCallAudit.Result auditedFacts(
            MyBatisToolCallAudit.StatementContext statement,
            MyBatisDatabasePreflight.Result auditedDatabase
    ) {
        ObjectNode metadataResult = objectMapper.createObjectNode();
        metadataResult.put("objectType", "TABLE");
        metadataResult.put("name", "orders");
        metadataResult.putArray("columns").add("id").add("status");
        ObjectNode queryResult = objectMapper.createObjectNode();
        queryResult.putArray("columns").add("id").add("status");
        ArrayNode auditedRows = queryResult.putArray("rows");
        auditedRows.addObject().put("id", 101).put("status", "OPEN");
        auditedRows.addObject().put("id", 102).put("status", "OPEN");
        List<AgentBridgeClient.ToolCallRecord> calls = List.of(
                toolCall(
                        "call-1", "get_database_object_description", databaseArguments(auditedDatabase), metadataResult,
                        Instant.parse("2026-07-22T09:05:00Z"), 10L),
                toolCall(
                        "call-2", "execute_sql_query",
                        databaseArguments(auditedDatabase).put(
                                "queryText", "SELECT id, status FROM orders LIMIT 20"),
                        queryResult, Instant.parse("2026-07-22T09:05:01Z"), 42L)
        );
        return new MyBatisToolCallAudit(objectMapper).audit(
                calls,
                new MyBatisToolCallAudit.Boundary(STARTED_AT, Set.of()),
                auditedDatabase,
                statement
        );
    }

    private AgentBridgeClient.ToolCallRecord toolCall(
            String id,
            String toolName,
            JsonNode arguments,
            JsonNode result,
            Instant timestamp,
            Long duration
    ) {
        return new AgentBridgeClient.ToolCallRecord(
                id, toolName, toolName, "mcp", "completed", timestamp,
                arguments, result, duration, objectMapper.createObjectNode(),
                VerifiedMyBatisDatabaseFixture.BRIDGE_IDENTITY.instanceId(),
                VerifiedMyBatisDatabaseFixture.BRIDGE_IDENTITY.projectId(),
                VerifiedMyBatisDatabaseFixture.BRIDGE_IDENTITY.instanceNonce(),
                VerifiedMyBatisDatabaseFixture.POLICY_FINGERPRINT,
                VerifiedMyBatisDatabaseFixture.DATABASE_FINGERPRINTS.hostFingerprint(),
                VerifiedMyBatisDatabaseFixture.DATABASE_FINGERPRINTS.instanceFingerprint(),
                VerifiedMyBatisDatabaseFixture.DATABASE_FINGERPRINTS.topologyFingerprint());
    }

    private ObjectNode databaseArguments(MyBatisDatabasePreflight.Result auditedDatabase) {
        return objectMapper.createObjectNode()
                .put("connectionId", auditedDatabase.connectionId())
                .put("databaseName", auditedDatabase.databaseName())
                .put("schemaName", auditedDatabase.schemaName());
    }

    private Path validCandidates() throws IOException {
        Path candidates = Files.createTempDirectory(tempDir, "candidates-");
        copyFixture("report-valid.md", candidates.resolve("report.md"));
        copyFixture("sql-summary-valid.json", candidates.resolve("summary.json"));
        copyFixture("database-evidence-valid.json", candidates.resolve("database-evidence.json"));
        return candidates;
    }

    private ObjectNode summary(Path candidates) throws IOException {
        return (ObjectNode) objectMapper.readTree(candidates.resolve("summary.json").toFile());
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
}
