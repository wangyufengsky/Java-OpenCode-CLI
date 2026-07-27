package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class MyBatisSqlOutputValidator {
    public static final int MAX_EVIDENCE_BYTES = 262_144;
    private static final Set<String> CANDIDATE_FILES = Set.of(
            "report.md", "summary.json", "database-evidence.json"
    );
    private static final List<String> REPORT_SECTIONS = List.of(
            "# SQL Review",
            "## Statement",
            "## Static Analysis",
            "## Database Evidence",
            "## Findings",
            "## Recommendations",
            "## Limitations"
    );
    private static final Set<String> SUPPORTED_SCHEMA_KEYWORDS = Set.of(
            "$schema", "$id", "title", "type", "additionalProperties", "required", "properties",
            "enum", "minLength", "minimum", "maximum", "minItems", "maxItems", "uniqueItems", "items"
    );
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile(
            "(?is)\\{\\{[^}]+}}|<<[^>]+>>|\\b(?:TODO|TBD|FIXME|PLACEHOLDER)\\b"
    );
    private static final Pattern DATABASE_EVIDENCE_SECTION = Pattern.compile(
            "(?ms)^## Database Evidence[ \\t]*\\R(.*?)(?=^##[ \\t]+|\\z)"
    );
    private static final String DATABASE_EVIDENCE_LINK =
            "[database-evidence.json](database-evidence.json)";
    private static final String SUMMARY_SCHEMA_RESOURCE =
            "/mybatis-sql-review-prompt-pack/schemas/sql-summary.schema.json";

    private final ObjectMapper objectMapper;
    private final JsonNode summarySchema;

    public MyBatisSqlOutputValidator(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    MyBatisSqlOutputValidator(ObjectMapper objectMapper, JsonNode summarySchema) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        JsonNode selectedSchema = summarySchema == null ? readSchema() : summarySchema.deepCopy();
        requireSupportedSchema(selectedSchema, "$schema");
        this.summarySchema = selectedSchema;
    }

    public Result validate(
            Path candidateDirectory,
            ExpectedTaskContext expectedTask,
            MyBatisDatabasePreflight.Result database,
            MyBatisToolCallAudit.Result auditedFacts
    ) throws IOException {
        Objects.requireNonNull(candidateDirectory, "candidateDirectory");
        Objects.requireNonNull(expectedTask, "expectedTask");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(auditedFacts, "auditedFacts");
        requireExactlyThreeFiles(candidateDirectory);

        Path reportPath = candidateDirectory.resolve("report.md");
        Path summaryPath = candidateDirectory.resolve("summary.json");
        Path evidencePath = candidateDirectory.resolve("database-evidence.json");
        byte[] evidenceBytes = Files.readAllBytes(evidencePath);
        if (evidenceBytes.length > MAX_EVIDENCE_BYTES) {
            throw new IllegalStateException("database evidence exceeds 262144 bytes: " + evidenceBytes.length);
        }

        String report = Files.readString(reportPath, StandardCharsets.UTF_8);
        String summaryText = Files.readString(summaryPath, StandardCharsets.UTF_8);
        String evidenceText = new String(evidenceBytes, StandardCharsets.UTF_8);
        requireNoPlaceholders("report.md", report);
        requireNoPlaceholders("summary.json", summaryText);
        requireNoPlaceholders("database-evidence.json", evidenceText);
        requireReportSections(report);
        requireDeterministicDatabaseEvidenceSection(report);

        JsonNode summary = parseObject(summaryText, "summary.json");
        JsonNode evidence = parseObject(evidenceText, "database-evidence.json");
        List<String> schemaErrors = new ArrayList<>();
        validateSchema(summary, summarySchema, "$", schemaErrors);
        if (!schemaErrors.isEmpty()) {
            throw new IllegalStateException("summary schema validation failed: " + String.join("; ", schemaErrors));
        }

        requireAuditedContext(expectedTask, database, auditedFacts);
        requireTaskBinding(summary, expectedTask, "summary expected task");
        requireSummaryDatabaseBinding(summary, database.binding());
        int scenarioCount = validateEvidence(evidence, summary, expectedTask, database, auditedFacts);
        requireReportBinding(report, expectedTask, database.binding());
        requireConnectivitySafetyMarker(report, database);
        return new Result(
                expectedTask.statementKey(),
                scenarioCount,
                evidenceBytes.length,
                auditedFacts.auditedCallIds()
        );
    }

    public Result validatePublishedOffline(
            Path publishedDirectory,
            ExpectedTaskContext expectedTask
    ) throws IOException {
        Objects.requireNonNull(publishedDirectory, "publishedDirectory");
        Objects.requireNonNull(expectedTask, "expectedTask");
        requireExactlyThreeFiles(publishedDirectory);
        Path evidencePath = publishedDirectory.resolve("database-evidence.json");
        byte[] evidenceBytes = Files.readAllBytes(evidencePath);
        if (evidenceBytes.length > MAX_EVIDENCE_BYTES) {
            throw new IllegalStateException("database evidence exceeds 262144 bytes: " + evidenceBytes.length);
        }
        String report = Files.readString(publishedDirectory.resolve("report.md"), StandardCharsets.UTF_8);
        String summaryText = Files.readString(publishedDirectory.resolve("summary.json"), StandardCharsets.UTF_8);
        String evidenceText = new String(evidenceBytes, StandardCharsets.UTF_8);
        requireNoPlaceholders("report.md", report);
        requireNoPlaceholders("summary.json", summaryText);
        requireNoPlaceholders("database-evidence.json", evidenceText);
        requireReportSections(report);
        requireDeterministicDatabaseEvidenceSection(report);
        JsonNode summary = parseObject(summaryText, "summary.json");
        JsonNode evidence = parseObject(evidenceText, "database-evidence.json");
        List<String> schemaErrors = new ArrayList<>();
        validateSchema(summary, summarySchema, "$", schemaErrors);
        if (!schemaErrors.isEmpty()) {
            throw new IllegalStateException("summary schema validation failed: " + String.join("; ", schemaErrors));
        }
        requireTaskBinding(summary, expectedTask, "summary expected task");
        requireSummaryDatabaseBinding(summary, evidence);
        int scenarioCount = validateEvidenceOffline(evidence, summary, expectedTask);
        requireReportBinding(report, expectedTask, evidence);
        return new Result(expectedTask.statementKey(), scenarioCount, evidenceBytes.length, List.of());
    }

    private int validateEvidenceOffline(
            JsonNode evidence,
            JsonNode summary,
            ExpectedTaskContext expectedTask
    ) {
        requireExactText(evidence, "schema_version", "mybatis-sql-review-database-evidence/v1",
                "database evidence");
        requireTaskBinding(evidence, expectedTask, "database evidence expected task");
        for (String field : List.of("data_source", "catalog", "schema", "project", "scope")) {
            requireText(evidence, field, "database evidence");
        }
        JsonNode audit = requireObject(evidence, "audit", "database evidence");
        if (!audit.path("post_hoc").isBoolean() || !audit.path("post_hoc").booleanValue()
                || !audit.path("permission_to_execute_original_dml").isBoolean()
                || audit.path("permission_to_execute_original_dml").booleanValue()) {
            throw new IllegalStateException("database evidence audit safety binding is invalid");
        }
        List<String> declaredCallIds = uniqueTextList(
                requireArray(audit, "tool_call_ids", "database evidence audit"),
                "database evidence audit.tool_call_ids"
        );
        Set<String> representedCallIds = new LinkedHashSet<>();
        Set<String> evidenceIds = new HashSet<>();
        JsonNode metadata = requireArray(evidence, "metadata", "database evidence");
        for (JsonNode item : metadata) {
            requireObjectNode(item, "metadata item");
            requireUnique(evidenceIds, requireText(item, "evidence_id", "metadata item"), "evidence_id");
            String callId = requireText(item, "tool_call_id", "metadata item");
            requireUniqueCallEvidence(representedCallIds, callId);
            String toolName = requireMetadataToolName(item, "metadata item");
            requireText(item, "timestamp", "metadata item");
            requireNonNegativeLong(item, "duration_ms", "metadata item");
            JsonNode arguments = requireObject(item, "arguments", "metadata item");
            requireOfflineDatabaseArguments(toolName, arguments, evidence, callId);
            MyBatisToolCallAudit.validateDeterminableMetadata(toolName, arguments, callId);
            if (item.path("result").isMissingNode() || item.path("result").isNull()) {
                throw new IllegalStateException("metadata item.result is required");
            }
            requireText(item, "observation", "metadata item");
        }
        JsonNode scenarios = requireArray(evidence, "scenarios", "database evidence");
        if (scenarios.size() > MyBatisToolCallAudit.MAX_QUERY_SCENARIOS) {
            throw new IllegalStateException("database evidence may contain at most 3 scenarios");
        }
        if (summary.path("scenario_count").asInt(-1) != scenarios.size()) {
            throw new IllegalStateException("summary scenario_count does not match database evidence scenarios");
        }
        Set<String> scenarioIds = new HashSet<>();
        for (JsonNode scenario : scenarios) {
            requireObjectNode(scenario, "scenario");
            requireUnique(evidenceIds, requireText(scenario, "evidence_id", "scenario"), "evidence_id");
            requireUnique(scenarioIds, requireText(scenario, "scenario_id", "scenario"), "scenario_id");
            requireText(scenario, "purpose", "scenario");
            String callId = requireText(scenario, "tool_call_id", "scenario");
            requireUniqueCallEvidence(representedCallIds, callId);
            requireText(scenario, "timestamp", "scenario");
            String sql = requireText(scenario, "query_text", "scenario");
            JsonNode arguments = requireObject(scenario, "arguments", "scenario");
            requireOfflineDatabaseArguments(DatabaseMcpContract.EXECUTE_QUERY, arguments, evidence, callId);
            requireExactText(arguments, "sql", sql,
                    "scenario arguments");
            long durationMs = requireNonNegativeLong(scenario, "duration_ms", "scenario");
            MyBatisToolCallAudit.validateDeterminableScenario(
                    sql, callId, evidence.path("schema").asText(), durationMs
            );
            JsonNode result = requireObject(scenario, "result", "scenario");
            JsonNode columns = requireArray(scenario, "columns", "scenario");
            uniqueTextList(columns, "scenario.columns");
            JsonNode rows = requireArray(scenario, "rows", "scenario");
            if (rows.size() > MyBatisToolCallAudit.MAX_ROWS_PER_CALL) {
                throw new IllegalStateException("database evidence scenario may retain at most 20 rows");
            }
            for (JsonNode row : rows) {
                if (!row.isObject() && !row.isArray()) {
                    throw new IllegalStateException("scenario row must be an object or array");
                }
            }
            if (!scenario.path("row_count").isIntegralNumber()
                    || scenario.path("row_count").asInt() != rows.size()) {
                throw new IllegalStateException("database evidence scenario row_count must equal rows.size");
            }
            if (!columns.equals(result.path("columns")) || !rows.equals(result.path("rows"))) {
                throw new IllegalStateException("database evidence scenario result must match columns and rows");
            }
        }
        if (!declaredCallIds.equals(List.copyOf(representedCallIds))) {
            throw new IllegalStateException("database evidence items do not exactly bind audit.tool_call_ids");
        }
        JsonNode limitations = requireArray(evidence, "limitations", "database evidence");
        if (limitations.isEmpty()) {
            throw new IllegalStateException("database evidence limitations must not be empty");
        }
        uniqueTextList(limitations, "database evidence limitations");
        for (JsonNode finding : summary.path("findings")) {
            for (JsonNode evidenceId : finding.path("evidence_ids")) {
                if (!evidenceIds.contains(evidenceId.asText())) {
                    throw new IllegalStateException(
                            "summary finding references unknown evidence_id: " + evidenceId.asText()
                    );
                }
            }
        }
        return scenarios.size();
    }

    private void requireOfflineDatabaseArguments(
            String toolName,
            JsonNode arguments,
            JsonNode evidence,
            String callId
    ) {
        Set<String> allowed = switch (toolName) {
            case DatabaseMcpContract.LIST_DATASOURCES -> Set.of("project", "scope");
            case DatabaseMcpContract.LIST_DATABASES -> Set.of("project", "scope", "dataSource");
            case DatabaseMcpContract.LIST_TABLE_SCHEMA -> Set.of("project", "scope", "dataSource", "catalog", "schema", "includeColumns", "includeIndexes", "maxTables");
            case DatabaseMcpContract.EXECUTE_QUERY -> Set.of("project", "scope", "dataSource", "sql", "maxRows");
            default -> throw new IllegalStateException("tool call " + callId + " uses an unapproved tool: " + toolName);
        };
        arguments.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)
                    && !DatabaseMcpContract.isOptionalInvocationMetadata(field)
                    && !DatabaseMcpContract.isOptionalToolArgument(toolName, field)) {
                throw new IllegalStateException("tool call " + callId + " uses an unsupported argument: " + field);
            }
        });
        for (String field : allowed) {
            if (arguments.path(field).isMissingNode()) {
                throw new IllegalStateException("tool call " + callId + " is missing required argument: " + field);
            }
        }
        DatabaseMcpContract.requireToolSpecificArgumentTypes(toolName, arguments, callId);
        requireExactText(arguments, "project", evidence.path("project").asText(), "tool call " + callId);
        requireExactText(arguments, "scope", evidence.path("scope").asText(), "tool call " + callId);
        if (!DatabaseMcpContract.LIST_DATASOURCES.equals(toolName)) {
            requireExactText(arguments, "dataSource", evidence.path("data_source").asText(), "tool call " + callId);
        }
        if (DatabaseMcpContract.LIST_TABLE_SCHEMA.equals(toolName)) {
            requireExactText(arguments, "catalog", evidence.path("catalog").asText(), "tool call " + callId);
            requireExactText(arguments, "schema", evidence.path("schema").asText(), "tool call " + callId);
        }
        if (DatabaseMcpContract.EXECUTE_QUERY.equals(toolName)) {
            JsonNode maxRows = arguments.path("maxRows");
            if (!maxRows.isIntegralNumber() || maxRows.intValue() != DatabaseMcpContract.MAX_ROWS) {
                throw new IllegalStateException("tool call " + callId + " maxRows must equal 20");
            }
        }
    }

    private long requireNonNegativeLong(JsonNode parent, String field, String label) {
        JsonNode value = parent.path(field);
        if (!value.isIntegralNumber() || value.longValue() < 0) {
            throw new IllegalStateException(label + "." + field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private String requireMetadataToolName(JsonNode item, String label) {
        String toolName = requireText(item, "tool_name", label);
        if (DatabaseMcpContract.EXECUTE_QUERY.equals(toolName)) {
            throw new IllegalStateException(
                    "native database query evidence must be represented as a scenario"
            );
        }
        return toolName;
    }

    private void requireAuditedContext(
            ExpectedTaskContext expected,
            MyBatisDatabasePreflight.Result database,
            MyBatisToolCallAudit.Result auditedFacts
    ) {
        MyBatisToolCallAudit.StatementContext statement = auditedFacts.statementContext();
        if (!expected.statementKey().equals(statement.statementKey())) {
            throw new IllegalStateException("audited statement context statement_key does not match expected task");
        }
        if (!expected.commandType().equals(statement.commandType())) {
            throw new IllegalStateException("audited statement context command_type does not match expected task");
        }
        if (expected.selectKey() != statement.selectKey()) {
            throw new IllegalStateException("audited statement context select_key does not match expected task");
        }
        if (!database.equals(auditedFacts.database())) {
            throw new IllegalStateException("audited preflight binding does not match validator preflight result");
        }
    }

    private void requireExactlyThreeFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("candidate directory does not exist: " + directory);
        }
        Set<String> actual = new LinkedHashSet<>();
        try (var paths = Files.list(directory)) {
            paths.forEach(path -> actual.add(path.getFileName().toString()));
        }
        if (!actual.equals(CANDIDATE_FILES)) {
            throw new IllegalStateException(
                    "candidate directory must contain exactly three candidate files "
                            + CANDIDATE_FILES + " but found " + actual
            );
        }
        for (String name : CANDIDATE_FILES) {
            if (!Files.isRegularFile(directory.resolve(name))) {
                throw new IllegalStateException("candidate artifact is not a regular file: " + name);
            }
        }
    }

    private void requireNoPlaceholders(String name, String content) {
        if (UNRESOLVED_PLACEHOLDER.matcher(content).find()) {
            throw new IllegalStateException(name + " contains an unresolved placeholder");
        }
    }

    private void requireReportSections(String report) {
        for (String section : REPORT_SECTIONS) {
            Pattern heading = Pattern.compile("(?m)^" + Pattern.quote(section) + "\\s*$");
            if (!heading.matcher(report).find()) {
                throw new IllegalStateException("report.md is missing required section: " + section);
            }
        }
    }

    private void requireDeterministicDatabaseEvidenceSection(String report) {
        var matcher = DATABASE_EVIDENCE_SECTION.matcher(report);
        if (!matcher.find() || !DATABASE_EVIDENCE_LINK.equals(matcher.group(1).strip())) {
            throw new IllegalStateException(
                    "report.md Database Evidence section must contain only the exact relative link "
                            + DATABASE_EVIDENCE_LINK
            );
        }
        if (matcher.find()) {
            throw new IllegalStateException("report.md must contain exactly one Database Evidence section");
        }
    }

    private void requireSummaryDatabaseBinding(
            JsonNode summary,
            DatabaseMcpContract.Binding binding
    ) {
        requireExactText(summary, "data_source", binding.dataSource(), "summary database binding");
        requireExactText(summary, "catalog", binding.catalog(), "summary database binding");
        requireExactText(summary, "schema", binding.schema(), "summary database binding");
        requireExactText(summary, "project", binding.project().toString(), "summary database binding");
        requireExactText(summary, "scope", binding.scope().name(), "summary database binding");
    }

    private void requireSummaryDatabaseBinding(JsonNode summary, JsonNode evidence) {
        for (String field : List.of("data_source", "catalog", "schema", "project", "scope")) {
            requireExactText(summary, field, requireText(evidence, field, "database evidence"),
                    "summary database binding");
        }
    }

    private void requireReportBinding(
            String report,
            ExpectedTaskContext expected,
            DatabaseMcpContract.Binding binding
    ) {
        requireReportTaskBinding(report, expected);
        requireReportDatabaseBinding(report, Map.of(
                "data_source", binding.dataSource(),
                "catalog", binding.catalog(),
                "schema", binding.schema(),
                "project", binding.project().toString(),
                "scope", binding.scope().name()
        ));
    }

    private void requireReportBinding(String report, ExpectedTaskContext expected, JsonNode evidence) {
        requireReportTaskBinding(report, expected);
        Map<String, String> binding = new java.util.LinkedHashMap<>();
        for (String field : List.of("data_source", "catalog", "schema", "project", "scope")) {
            binding.put(field, requireText(evidence, field, "database evidence"));
        }
        requireReportDatabaseBinding(report, binding);
    }

    private void requireReportTaskBinding(String report, ExpectedTaskContext expected) {
        Map<String, String> expectedValues = Map.of(
                "statement_key", expected.statementKey(),
                "mapper_relative_path", expected.mapperRelativePath(),
                "namespace", expected.namespace(),
                "statement_id", expected.statementId(),
                "command_type", expected.commandType(),
                "select_key", Boolean.toString(expected.selectKey())
        );
        for (Map.Entry<String, String> entry : expectedValues.entrySet()) {
            if (!report.contains(entry.getValue())) {
                throw new IllegalStateException(
                        "report.md does not identify expected task " + entry.getKey() + " " + entry.getValue()
                );
            }
        }
    }

    private void requireReportDatabaseBinding(String report, Map<String, String> binding) {
        Map<String, String> labels = Map.of(
                "data_source", "Data source",
                "catalog", "Catalog",
                "schema", "Schema",
                "project", "Project",
                "scope", "Scope"
        );
        for (String field : List.of("data_source", "catalog", "schema", "project", "scope")) {
            String line = "- " + labels.get(field) + ": `" + binding.get(field) + "`";
            if (!Pattern.compile("(?m)^" + Pattern.quote(line) + "$").matcher(report).find()) {
                throw new IllegalStateException("report.md database binding " + field
                        + " does not match the required labeled line");
            }
        }
    }

    private void requireConnectivitySafetyMarker(
            String report,
            MyBatisDatabasePreflight.Result database
    ) {
        if (database.safetyMode() != MyBatisDatabasePreflight.SafetyMode.CONNECTIVITY_ONLY) {
            return;
        }
        for (String line : List.of(
                "- Safety mode: `connectivity-only`",
                "- Database safety: `unverified`"
        )) {
            if (!Pattern.compile("(?m)^" + Pattern.quote(line) + "$").matcher(report).find()) {
                throw new IllegalStateException(
                        "report.md must mark connectivity-only database safety as unverified");
            }
        }
    }

    private JsonNode parseObject(String content, String name) throws IOException {
        JsonNode value = objectMapper.readTree(content);
        if (value == null || !value.isObject()) {
            throw new IllegalStateException(name + " must contain one JSON object");
        }
        return value;
    }

    private int validateEvidence(
            JsonNode evidence,
            JsonNode summary,
            ExpectedTaskContext expectedTask,
            MyBatisDatabasePreflight.Result database,
            MyBatisToolCallAudit.Result auditedFacts
    ) {
        requireExactText(evidence, "schema_version", "mybatis-sql-review-database-evidence/v1",
                "database evidence");
        requireTaskBinding(evidence, expectedTask, "database evidence expected task");
        requireExactText(evidence, "data_source", database.binding().dataSource(), "preflight database binding");
        requireExactText(evidence, "catalog", database.binding().catalog(), "preflight database binding");
        requireExactText(evidence, "schema", database.binding().schema(), "preflight database binding");
        requireExactText(evidence, "project", database.binding().project().toString(), "preflight database binding");
        requireExactText(evidence, "scope", database.binding().scope().name(), "preflight database binding");

        JsonNode audit = requireObject(evidence, "audit", "database evidence");
        if (!audit.path("post_hoc").isBoolean() || !audit.path("post_hoc").asBoolean()) {
            throw new IllegalStateException("database evidence audit.post_hoc must be true");
        }
        if (!audit.path("permission_to_execute_original_dml").isBoolean()
                || audit.path("permission_to_execute_original_dml").asBoolean()) {
            throw new IllegalStateException(
                    "database evidence audit.permission_to_execute_original_dml must be false"
            );
        }
        List<String> declaredCallIds = uniqueTextList(
                requireArray(audit, "tool_call_ids", "database evidence audit"),
                "database evidence audit.tool_call_ids"
        );
        if (!declaredCallIds.equals(auditedFacts.auditedCallIds())) {
            throw new IllegalStateException("database evidence audit.tool_call_ids do not exactly match audited calls");
        }

        JsonNode metadata = requireArray(evidence, "metadata", "database evidence");
        JsonNode scenarios = requireArray(evidence, "scenarios", "database evidence");
        if (scenarios.size() > MyBatisToolCallAudit.MAX_QUERY_SCENARIOS) {
            throw new IllegalStateException("database evidence may contain at most 3 scenarios");
        }
        if (summary.path("scenario_count").asInt(-1) != scenarios.size()) {
            throw new IllegalStateException("summary scenario_count does not match database evidence scenarios");
        }
        if (auditedFacts.queryScenarioCount() != scenarios.size()) {
            throw new IllegalStateException("database evidence scenarios do not match audited query scenario count");
        }

        Map<String, MyBatisToolCallAudit.AuditedCallFact> factsById = new HashMap<>();
        for (MyBatisToolCallAudit.AuditedCallFact fact : auditedFacts.calls()) {
            if (factsById.put(fact.id(), fact) != null) {
                throw new IllegalStateException("audited facts contain duplicate tool-call id: " + fact.id());
            }
        }
        Set<String> representedCallIds = new LinkedHashSet<>();
        Set<String> evidenceIds = new HashSet<>();
        Set<String> scenarioIds = new HashSet<>();
        for (JsonNode item : metadata) {
            requireObjectNode(item, "metadata item");
            String evidenceId = requireText(item, "evidence_id", "metadata item");
            requireUnique(evidenceIds, evidenceId, "evidence_id");
            String callId = requireText(item, "tool_call_id", "metadata item");
            MyBatisToolCallAudit.AuditedCallFact fact = requireAuditedFact(factsById, callId);
            requireUniqueCallEvidence(representedCallIds, callId);
            requireMetadataToolName(item, "metadata item");
            if (DatabaseMcpContract.EXECUTE_QUERY.equals(fact.toolName())) {
                throw new IllegalStateException("native database query audited call must be represented as a scenario: " + callId);
            }
            requireExactText(item, "tool_name", fact.toolName(), "metadata for audited call " + callId);
            requireExactText(item, "timestamp", fact.timestamp().toString(), "metadata for audited call " + callId);
            requireExactLong(item, "duration_ms", fact.durationMs(), "metadata for audited call " + callId);
            requireExactJson(item, "arguments", fact.arguments(), "metadata for audited call " + callId);
            requireExactJson(item, "result", fact.resultData(), "metadata for audited call " + callId);
            requireText(item, "observation", "metadata item");
        }
        for (JsonNode scenario : scenarios) {
            requireObjectNode(scenario, "scenario");
            String evidenceId = requireText(scenario, "evidence_id", "scenario");
            requireUnique(evidenceIds, evidenceId, "evidence_id");
            String scenarioId = requireText(scenario, "scenario_id", "scenario");
            requireUnique(scenarioIds, scenarioId, "scenario_id");
            requireText(scenario, "purpose", "scenario");
            String callId = requireText(scenario, "tool_call_id", "scenario");
            MyBatisToolCallAudit.AuditedCallFact fact = requireAuditedFact(factsById, callId);
            requireUniqueCallEvidence(representedCallIds, callId);
            if (!DatabaseMcpContract.EXECUTE_QUERY.equals(fact.toolName())) {
                throw new IllegalStateException("scenario must bind to an audited native database query call: " + callId);
            }
            requireExactText(scenario, "timestamp", fact.timestamp().toString(), "scenario for audited call " + callId);
            requireExactLong(scenario, "duration_ms", fact.durationMs(), "scenario for audited call " + callId);
            requireExactText(scenario, "query_text", fact.sql(), "scenario for audited call " + callId);
            requireExactJson(scenario, "arguments", fact.arguments(), "scenario for audited call " + callId);
            requireExactJson(scenario, "result", fact.resultData(), "scenario for audited call " + callId);
            MyBatisToolCallAudit.validateDeterminableScenario(
                    scenario.path("query_text").asText(),
                    callId,
                    evidence.path("schema").asText(),
                    scenario.path("duration_ms").asLong(-1)
            );

            JsonNode columns = requireArray(scenario, "columns", "scenario");
            List<String> actualColumns = uniqueTextList(columns, "scenario.columns");
            if (!actualColumns.equals(fact.columns())) {
                throw new IllegalStateException("scenario columns do not match audited call " + callId);
            }
            JsonNode rows = requireArray(scenario, "rows", "scenario");
            if (rows.size() > MyBatisToolCallAudit.MAX_ROWS_PER_CALL) {
                throw new IllegalStateException("database evidence scenario may retain at most 20 rows");
            }
            for (JsonNode row : rows) {
                if (!row.isObject() && !row.isArray()) {
                    throw new IllegalStateException("scenario row must be an object or array");
                }
            }
            if (!scenario.path("row_count").isIntegralNumber()
                    || scenario.path("row_count").asInt() != rows.size()) {
                throw new IllegalStateException("database evidence scenario row_count must equal rows.size");
            }
            if (!rows.equals(objectMapper.valueToTree(fact.rows()))) {
                throw new IllegalStateException("scenario rows do not match audited call " + callId);
            }
        }

        for (String callId : auditedFacts.auditedCallIds()) {
            if (!representedCallIds.contains(callId)) {
                throw new IllegalStateException("missing evidence for audited call " + callId);
            }
        }

        JsonNode limitations = requireArray(evidence, "limitations", "database evidence");
        if (limitations.isEmpty()) {
            throw new IllegalStateException("database evidence limitations must not be empty");
        }
        uniqueTextList(limitations, "database evidence limitations");
        for (JsonNode finding : summary.path("findings")) {
            for (JsonNode evidenceId : finding.path("evidence_ids")) {
                if (!evidenceIds.contains(evidenceId.asText())) {
                    throw new IllegalStateException(
                            "summary finding references unknown evidence_id: " + evidenceId.asText()
                    );
                }
            }
        }
        return scenarios.size();
    }

    private void requireTaskBinding(JsonNode source, ExpectedTaskContext expected, String label) {
        requireExactText(source, "statement_key", expected.statementKey(), label);
        requireExactText(source, "mapper_relative_path", expected.mapperRelativePath(), label);
        requireExactText(source, "namespace", expected.namespace(), label);
        requireExactText(source, "statement_id", expected.statementId(), label);
        requireExactText(source, "command_type", expected.commandType(), label);
        JsonNode selectKey = source.path("select_key");
        if (!selectKey.isBoolean() || selectKey.asBoolean() != expected.selectKey()) {
            throw new IllegalStateException(label + ".select_key does not match expected task");
        }
    }

    private MyBatisToolCallAudit.AuditedCallFact requireAuditedFact(
            Map<String, MyBatisToolCallAudit.AuditedCallFact> facts,
            String callId
    ) {
        MyBatisToolCallAudit.AuditedCallFact fact = facts.get(callId);
        if (fact == null) {
            throw new IllegalStateException("stale or extra tool_call_id in evidence: " + callId);
        }
        return fact;
    }

    private void requireUniqueCallEvidence(Set<String> represented, String callId) {
        if (!represented.add(callId)) {
            throw new IllegalStateException("duplicate evidence for audited call " + callId);
        }
    }

    private void requireExactLong(JsonNode parent, String field, long expected, String label) {
        JsonNode value = parent.path(field);
        if (!value.isIntegralNumber() || value.asLong() != expected) {
            throw new IllegalStateException(label + "." + field + " does not match audited call facts");
        }
    }

    private void requireExactJson(JsonNode parent, String field, JsonNode expected, String label) {
        JsonNode value = parent.path(field);
        if (value.isMissingNode() || !value.equals(expected)) {
            throw new IllegalStateException(label + "." + field + " does not match audited call facts");
        }
    }

    private void requireUnique(Set<String> values, String value, String label) {
        if (!values.add(value)) {
            throw new IllegalStateException("duplicate " + label + ": " + value);
        }
    }

    private List<String> uniqueTextList(JsonNode array, String label) {
        List<String> values = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalStateException(label + " must contain non-blank strings");
            }
            requireUnique(unique, item.asText(), label);
            values.add(item.asText());
        }
        return List.copyOf(values);
    }

    private JsonNode requireObject(JsonNode parent, String field, String label) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) {
            throw new IllegalStateException(label + "." + field + " must be an object");
        }
        return value;
    }

    private JsonNode requireArray(JsonNode parent, String field, String label) {
        JsonNode value = parent.path(field);
        if (!value.isArray()) {
            throw new IllegalStateException(label + "." + field + " must be an array");
        }
        return value;
    }

    private void requireObjectNode(JsonNode value, String label) {
        if (!value.isObject()) {
            throw new IllegalStateException(label + " must be an object");
        }
    }

    private String requireText(JsonNode parent, String field, String label) {
        String value = parent.path(field).asText("");
        if (!parent.path(field).isTextual() || value.isBlank()) {
            throw new IllegalStateException(label + "." + field + " must be a non-blank string");
        }
        return value;
    }

    private void requireExactText(JsonNode parent, String field, String expected, String label) {
        String value = requireText(parent, field, label);
        if (!expected.equals(value)) {
            throw new IllegalStateException(label + "." + field + " does not match expected value " + expected);
        }
    }

    private JsonNode readSchema() {
        try (InputStream input = MyBatisSqlOutputValidator.class.getResourceAsStream(SUMMARY_SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing SQL summary schema: " + SUMMARY_SCHEMA_RESOURCE);
            }
            return objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read SQL summary schema", exception);
        }
    }

    private void requireSupportedSchema(JsonNode schema, String path) {
        if (!schema.isObject()) {
            throw new IllegalStateException("JSON schema node must be an object at " + path);
        }
        Iterator<Map.Entry<String, JsonNode>> fields = schema.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!SUPPORTED_SCHEMA_KEYWORDS.contains(field.getKey())) {
                throw new IllegalStateException("unknown JSON schema keyword: " + field.getKey() + " at " + path);
            }
            if ("properties".equals(field.getKey())) {
                if (!field.getValue().isObject()) {
                    throw new IllegalStateException("JSON schema properties must be an object at " + path);
                }
                field.getValue().fields().forEachRemaining(
                        property -> requireSupportedSchema(property.getValue(), path + ".properties." + property.getKey())
                );
            } else if ("items".equals(field.getKey())) {
                requireSupportedSchema(field.getValue(), path + ".items");
            }
        }
    }

    private void validateSchema(JsonNode instance, JsonNode schema, String path, List<String> errors) {
        String type = schema.path("type").asText("");
        if (!type.isBlank() && !hasType(instance, type)) {
            errors.add(path + " must be " + type);
            return;
        }
        if (schema.path("enum").isArray()) {
            boolean matched = false;
            for (JsonNode allowed : schema.path("enum")) {
                if (allowed.equals(instance)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(path + " is not an allowed value");
            }
        }
        if (instance.isObject()) {
            validateObjectSchema(instance, schema, path, errors);
        } else if (instance.isArray()) {
            validateArraySchema(instance, schema, path, errors);
        } else if (instance.isTextual()) {
            int minLength = schema.path("minLength").asInt(0);
            if (instance.asText().length() < minLength) {
                errors.add(path + " length must be >= " + minLength);
            }
        } else if (instance.isNumber()) {
            if (schema.has("minimum") && instance.asDouble() < schema.path("minimum").asDouble()) {
                errors.add(path + " must be >= " + schema.path("minimum"));
            }
            if (schema.has("maximum") && instance.asDouble() > schema.path("maximum").asDouble()) {
                errors.add(path + " must be <= " + schema.path("maximum"));
            }
        }
    }

    private void validateObjectSchema(JsonNode instance, JsonNode schema, String path, List<String> errors) {
        for (JsonNode required : schema.path("required")) {
            String field = required.asText();
            if (!instance.has(field)) {
                errors.add(path + " missing required field " + field);
            }
        }
        JsonNode properties = schema.path("properties");
        Iterator<Map.Entry<String, JsonNode>> fields = instance.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (properties.has(field.getKey())) {
                validateSchema(field.getValue(), properties.path(field.getKey()), path + "." + field.getKey(), errors);
            } else if (schema.path("additionalProperties").isBoolean()
                    && !schema.path("additionalProperties").asBoolean()) {
                errors.add(path + " contains unknown field " + field.getKey());
            }
        }
    }

    private void validateArraySchema(JsonNode instance, JsonNode schema, String path, List<String> errors) {
        if (schema.has("minItems") && instance.size() < schema.path("minItems").asInt()) {
            errors.add(path + " must contain at least " + schema.path("minItems").asInt() + " items");
        }
        if (schema.has("maxItems") && instance.size() > schema.path("maxItems").asInt()) {
            errors.add(path + " must contain at most " + schema.path("maxItems").asInt() + " items");
        }
        if (schema.path("uniqueItems").asBoolean(false)) {
            Set<JsonNode> unique = new HashSet<>();
            for (JsonNode item : instance) {
                if (!unique.add(item)) {
                    errors.add(path + " must contain unique items");
                    break;
                }
            }
        }
        if (schema.has("items")) {
            for (int index = 0; index < instance.size(); index++) {
                validateSchema(instance.get(index), schema.path("items"), path + "[" + index + "]", errors);
            }
        }
    }

    private boolean hasType(JsonNode instance, String type) {
        return switch (type) {
            case "object" -> instance.isObject();
            case "array" -> instance.isArray();
            case "string" -> instance.isTextual();
            case "integer" -> instance.isIntegralNumber();
            case "number" -> instance.isNumber();
            case "boolean" -> instance.isBoolean();
            case "null" -> instance.isNull();
            default -> false;
        };
    }

    public record ExpectedTaskContext(
            String statementKey,
            String mapperRelativePath,
            String namespace,
            String statementId,
            String commandType,
            boolean selectKey
    ) {
        public ExpectedTaskContext {
            requireNonBlank(statementKey, "statementKey");
            requireNonBlank(mapperRelativePath, "mapperRelativePath");
            requireNonBlank(namespace, "namespace");
            requireNonBlank(statementId, "statementId");
            requireNonBlank(commandType, "commandType");
        }

        private static void requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must be non-blank");
            }
        }
    }

    public record Result(
            String statementKey,
            int scenarioCount,
            int evidenceBytes,
            List<String> auditedCallIds
    ) {
        public Result {
            auditedCallIds = List.copyOf(auditedCallIds);
        }
    }
}
