package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile(
            "(?is)\\{\\{[^}]+}}|<<[^>]+>>|\\b(?:TODO|TBD|FIXME|PLACEHOLDER)\\b"
    );
    private static final String SUMMARY_SCHEMA_RESOURCE =
            "/mybatis-sql-review-prompt-pack/schemas/sql-summary.schema.json";

    private final ObjectMapper objectMapper;
    private final JsonNode summarySchema;

    public MyBatisSqlOutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.summarySchema = readSchema();
    }

    public Result validate(Path candidateDirectory) throws IOException {
        Objects.requireNonNull(candidateDirectory, "candidateDirectory");
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

        JsonNode summary = parseObject(summaryText, "summary.json");
        JsonNode evidence = parseObject(evidenceText, "database-evidence.json");
        List<String> schemaErrors = new ArrayList<>();
        validateSchema(summary, summarySchema, "$", schemaErrors);
        if (!schemaErrors.isEmpty()) {
            throw new IllegalStateException("summary schema validation failed: " + String.join("; ", schemaErrors));
        }

        int scenarioCount = validateEvidence(evidence, summary);
        String statementKey = summary.path("statement_key").asText();
        if (!report.contains(statementKey)) {
            throw new IllegalStateException("report.md does not identify statement_key " + statementKey);
        }
        return new Result(statementKey, scenarioCount, evidenceBytes.length);
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

    private JsonNode parseObject(String content, String name) throws IOException {
        JsonNode value = objectMapper.readTree(content);
        if (value == null || !value.isObject()) {
            throw new IllegalStateException(name + " must contain one JSON object");
        }
        return value;
    }

    private int validateEvidence(JsonNode evidence, JsonNode summary) {
        requireExactText(evidence, "schema_version", "mybatis-sql-review-database-evidence/v1");
        String statementKey = requireText(evidence, "statement_key", "database evidence");
        if (!summary.path("statement_key").asText().equals(statementKey)) {
            throw new IllegalStateException("summary and database evidence statement_key values differ");
        }
        requireText(evidence, "connection_id", "database evidence");
        requireText(evidence, "database_name", "database evidence");
        requireText(evidence, "schema_name", "database evidence");

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
        Set<String> auditedCallIds = uniqueTextValues(
                requireArray(audit, "tool_call_ids", "database evidence audit"),
                "database evidence audit.tool_call_ids"
        );

        JsonNode metadata = requireArray(evidence, "metadata", "database evidence");
        JsonNode scenarios = requireArray(evidence, "scenarios", "database evidence");
        if (scenarios.size() > MyBatisToolCallAudit.MAX_QUERY_SCENARIOS) {
            throw new IllegalStateException("database evidence may contain at most 3 scenarios");
        }
        if (summary.path("scenario_count").asInt(-1) != scenarios.size()) {
            throw new IllegalStateException("summary scenario_count does not match database evidence scenarios");
        }

        Set<String> evidenceIds = new HashSet<>();
        Set<String> scenarioIds = new HashSet<>();
        for (JsonNode item : metadata) {
            requireObjectNode(item, "metadata item");
            String evidenceId = requireText(item, "evidence_id", "metadata item");
            requireUnique(evidenceIds, evidenceId, "evidence_id");
            String toolCallId = requireText(item, "tool_call_id", "metadata item");
            requireAuditedCall(auditedCallIds, toolCallId);
            String toolName = requireText(item, "tool_name", "metadata item");
            if (!MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS.contains(toolName)) {
                throw new IllegalStateException("metadata item uses an unapproved tool_name: " + toolName);
            }
            requireText(item, "observation", "metadata item");
        }
        for (JsonNode scenario : scenarios) {
            requireObjectNode(scenario, "scenario");
            String evidenceId = requireText(scenario, "evidence_id", "scenario");
            requireUnique(evidenceIds, evidenceId, "evidence_id");
            String scenarioId = requireText(scenario, "scenario_id", "scenario");
            requireUnique(scenarioIds, scenarioId, "scenario_id");
            requireText(scenario, "purpose", "scenario");
            requireText(scenario, "query_text", "scenario");
            String toolCallId = requireText(scenario, "tool_call_id", "scenario");
            requireAuditedCall(auditedCallIds, toolCallId);
            JsonNode columns = requireArray(scenario, "columns", "scenario");
            uniqueTextValues(columns, "scenario.columns");
            JsonNode rows = requireArray(scenario, "rows", "scenario");
            if (rows.size() > MyBatisToolCallAudit.MAX_ROWS_PER_CALL) {
                throw new IllegalStateException("database evidence scenario may retain at most 20 rows");
            }
            if (!scenario.path("row_count").isIntegralNumber()
                    || scenario.path("row_count").asInt() != rows.size()) {
                throw new IllegalStateException("database evidence scenario row_count must equal rows.size");
            }
            for (JsonNode row : rows) {
                requireObjectNode(row, "scenario row");
            }
        }

        JsonNode limitations = requireArray(evidence, "limitations", "database evidence");
        if (limitations.isEmpty()) {
            throw new IllegalStateException("database evidence limitations must not be empty");
        }
        uniqueTextValues(limitations, "database evidence limitations");

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

    private void requireAuditedCall(Set<String> auditedCallIds, String toolCallId) {
        if (!auditedCallIds.contains(toolCallId)) {
            throw new IllegalStateException("evidence references a stale or unaudited tool_call_id: " + toolCallId);
        }
    }

    private void requireUnique(Set<String> values, String value, String label) {
        if (!values.add(value)) {
            throw new IllegalStateException("duplicate " + label + ": " + value);
        }
    }

    private Set<String> uniqueTextValues(JsonNode array, String label) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalStateException(label + " must contain non-blank strings");
            }
            requireUnique(values, item.asText(), label);
        }
        return values;
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

    private void requireExactText(JsonNode parent, String field, String expected) {
        String value = requireText(parent, field, "database evidence");
        if (!expected.equals(value)) {
            throw new IllegalStateException("database evidence." + field + " must equal " + expected);
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

    public record Result(String statementKey, int scenarioCount, int evidenceBytes) {
    }
}
