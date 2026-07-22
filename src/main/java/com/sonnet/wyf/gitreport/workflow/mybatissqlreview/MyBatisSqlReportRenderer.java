package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class MyBatisSqlReportRenderer {
    public static final String MAIN_REPORT = "mybatis-sql-review-report.md";
    public static final String INVENTORY = "sql-inventory.json";
    public static final String TASKS = "sql-tasks.json";
    public static final String TRACEABILITY = "traceability.json";
    public static final String DATA_QUALITY = "data-quality.md";
    private static final List<String> TASK_ARTIFACTS = List.of(
            "report.md", "summary.json", "database-evidence.json"
    );

    private final ObjectMapper objectMapper;

    public MyBatisSqlReportRenderer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public RenderResult render(Path bundleRoot, Project project, MyBatisSqlInventory inventory) throws IOException {
        Path root = Objects.requireNonNull(bundleRoot, "bundleRoot").toAbsolutePath().normalize();
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(inventory, "inventory");
        List<StatementSummary> summaries = validateAndReadSummaries(root, inventory);
        SeverityCounts severity = severityCounts(summaries);
        Files.createDirectories(root);
        writeInventorySnapshot(root, inventory);
        writeJson(root.resolve(TASKS), tasksJson(inventory, summaries, severity));
        writeJson(root.resolve(TRACEABILITY), traceabilityJson(inventory, summaries));
        Files.writeString(root.resolve(DATA_QUALITY), renderDataQuality(inventory, summaries), StandardCharsets.UTF_8);
        writeMapperIndexes(root, inventory, summaries);
        Path mainReport = root.resolve(MAIN_REPORT);
        Files.writeString(mainReport, renderProjectReport(project, inventory, severity), StandardCharsets.UTF_8);
        return new RenderResult(mainReport, inventory.mappers().size(), inventory.statements().size(), severity);
    }

    public Path writeInventorySnapshot(Path bundleRoot, MyBatisSqlInventory inventory) throws IOException {
        Path target = Objects.requireNonNull(bundleRoot, "bundleRoot")
                .toAbsolutePath().normalize().resolve(INVENTORY);
        writeJson(target, inventoryJson(Objects.requireNonNull(inventory, "inventory")));
        return target;
    }

    public MyBatisSqlInventory readInventory(Path inventoryPath) throws IOException {
        JsonNode root = objectMapper.readTree(inventoryPath.toFile());
        if (!"mybatis-sql-review-inventory/v1".equals(root.path("schema_version").asText())) {
            throw new IllegalStateException("published SQL inventory has an unsupported schema_version");
        }
        Map<String, List<MyBatisSqlStatement>> byMapper = new LinkedHashMap<>();
        List<MyBatisSqlStatement> statements = new ArrayList<>();
        JsonNode statementNodes = root.path("statements");
        if (!statementNodes.isArray()) {
            throw new IllegalStateException("published SQL inventory is missing statements array");
        }
        for (JsonNode node : statementNodes) {
            MyBatisSqlStatement statement = readStatement(node);
            statements.add(statement);
            byMapper.computeIfAbsent(statement.mapperKey(), ignored -> new ArrayList<>()).add(statement);
        }
        List<MyBatisMapperInventory> mappers = new ArrayList<>();
        JsonNode mapperNodes = root.path("mappers");
        if (!mapperNodes.isArray()) {
            throw new IllegalStateException("published SQL inventory is missing mappers array");
        }
        for (JsonNode node : mapperNodes) {
            String mapperKey = requiredText(node, "mapper_key", "published mapper inventory");
            mappers.add(new MyBatisMapperInventory(
                    requiredText(node, "mapper_relative_path", "published mapper inventory"),
                    requiredText(node, "namespace", "published mapper inventory"),
                    requiredText(node, "source_sha256", "published mapper inventory"),
                    mapperKey,
                    byMapper.getOrDefault(mapperKey, List.of())
            ));
        }
        if (mappers.stream().mapToInt(mapper -> mapper.statements().size()).sum() != statements.size()) {
            throw new IllegalStateException("published SQL inventory contains statements for an unknown mapper");
        }
        return new MyBatisSqlInventory(mappers, statements);
    }

    static Path statementDirectory(Path root, MyBatisSqlStatement statement) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path directory = normalizedRoot.resolve("reports")
                .resolve(statement.mapperKey())
                .resolve("sql")
                .resolve(statement.statementKey())
                .normalize();
        if (!directory.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("SQL report path escapes bundle root: " + statement.statementKey());
        }
        return directory;
    }

    private List<StatementSummary> validateAndReadSummaries(
            Path root,
            MyBatisSqlInventory inventory
    ) throws IOException {
        List<StatementSummary> summaries = new ArrayList<>();
        for (MyBatisSqlStatement statement : inventory.statements()) {
            Path directory = statementDirectory(root, statement);
            for (String artifact : TASK_ARTIFACTS) {
                if (!Files.isRegularFile(directory.resolve(artifact))) {
                    throw new IllegalStateException(
                            "missing SQL review artifact for " + statement.statementKey() + ": " + artifact
                    );
                }
            }
            JsonNode summary = objectMapper.readTree(directory.resolve("summary.json").toFile());
            requireSummaryBinding(summary, statement);
            JsonNode findings = summary.path("findings");
            if (!findings.isArray()) {
                throw new IllegalStateException("SQL summary findings must be an array: " + statement.statementKey());
            }
            List<Finding> parsedFindings = new ArrayList<>();
            for (JsonNode finding : findings) {
                String id = requiredText(finding, "id", "SQL summary finding");
                String sourceSeverity = requiredText(finding, "severity", "SQL summary finding");
                parsedFindings.add(new Finding(id, toProjectSeverity(sourceSeverity)));
            }
            summaries.add(new StatementSummary(statement, List.copyOf(parsedFindings)));
        }
        return List.copyOf(summaries);
    }

    private void requireSummaryBinding(JsonNode summary, MyBatisSqlStatement statement) {
        if (!summary.isObject()) {
            throw new IllegalStateException("SQL summary must be an object: " + statement.statementKey());
        }
        requireExactText(summary, "schema_version", "mybatis-sql-review-summary/v1", statement.statementKey());
        requireExactText(summary, "statement_key", statement.statementKey(), statement.statementKey());
        requireExactText(summary, "mapper_relative_path", statement.mapperRelativePath(), statement.statementKey());
        requireExactText(summary, "namespace", statement.namespace(), statement.statementKey());
        requireExactText(summary, "statement_id", statement.id(), statement.statementKey());
        requireExactText(summary, "command_type", commandType(statement), statement.statementKey());
        JsonNode selectKey = summary.path("select_key");
        if (!selectKey.isBoolean() || selectKey.asBoolean() != statement.selectKey()) {
            throw new IllegalStateException("SQL summary select_key does not match " + statement.statementKey());
        }
    }

    private ObjectNode inventoryJson(MyBatisSqlInventory inventory) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", "mybatis-sql-review-inventory/v1");
        ArrayNode mappers = root.putArray("mappers");
        for (MyBatisMapperInventory mapper : inventory.mappers()) {
            ObjectNode node = mappers.addObject();
            node.put("mapper_relative_path", mapper.mapperRelativePath());
            node.put("namespace", mapper.namespace());
            node.put("source_sha256", mapper.sourceSha256());
            node.put("mapper_key", mapper.mapperKey());
            node.put("statement_count", mapper.statements().size());
            node.put("report_index", relative(statementIndex(mapper.mapperKey())));
        }
        ArrayNode statements = root.putArray("statements");
        for (MyBatisSqlStatement statement : inventory.statements()) {
            ObjectNode node = statements.addObject();
            node.put("mapper_relative_path", statement.mapperRelativePath());
            node.put("namespace", statement.namespace());
            node.put("statement_id", statement.id());
            node.put("command_type", statement.commandType());
            node.put("select_key", statement.selectKey());
            node.put("select_key_ordinal", statement.selectKeyOrdinal());
            node.put("start_line", statement.startLine());
            node.put("end_line", statement.endLine());
            node.put("raw_xml", statement.rawXml());
            node.put("normalized_sql", statement.normalizedSql());
            node.putPOJO("dynamic_node_names", statement.dynamicNodeNames());
            node.putPOJO("parameter_placeholders", statement.parameterPlaceholders());
            node.putPOJO("resolved_fragment_ids", statement.resolvedFragmentIds());
            node.put("source_sha256", statement.sourceSha256());
            node.put("mapper_key", statement.mapperKey());
            node.put("statement_key", statement.statementKey());
            node.put("report_directory", relativeStatementDirectory(statement));
        }
        return root;
    }

    private ObjectNode tasksJson(
            MyBatisSqlInventory inventory,
            List<StatementSummary> summaries,
            SeverityCounts severity
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", "mybatis-sql-review-tasks/v1");
        ObjectNode totals = root.putObject("totals");
        totals.put("mappers", inventory.mappers().size());
        totals.put("statements", inventory.statements().size());
        totals.put("findings", severity.total());
        writeSeverity(totals.putObject("severity"), severity);
        ArrayNode tasks = root.putArray("tasks");
        for (StatementSummary summary : summaries) {
            MyBatisSqlStatement statement = summary.statement();
            ObjectNode task = tasks.addObject();
            task.put("task_key", statement.statementKey());
            task.put("mapper_key", statement.mapperKey());
            task.put("mapper_relative_path", statement.mapperRelativePath());
            task.put("namespace", statement.namespace());
            task.put("statement_id", statement.id());
            task.put("command_type", commandType(statement));
            task.put("select_key", statement.selectKey());
            task.put("status", "reviewed");
            task.put("report", relativeStatementDirectory(statement) + "/report.md");
            task.put("summary", relativeStatementDirectory(statement) + "/summary.json");
            task.put("database_evidence", relativeStatementDirectory(statement) + "/database-evidence.json");
            SeverityCounts counts = severityCounts(List.of(summary));
            task.put("finding_count", counts.total());
            writeSeverity(task.putObject("severity"), counts);
        }
        return root;
    }

    private ObjectNode traceabilityJson(
            MyBatisSqlInventory inventory,
            List<StatementSummary> summaries
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", "mybatis-sql-review-traceability/v1");
        root.put("statement_count", inventory.statements().size());
        ArrayNode entries = root.putArray("entries");
        for (StatementSummary summary : summaries) {
            MyBatisSqlStatement statement = summary.statement();
            ObjectNode entry = entries.addObject();
            entry.put("statement_key", statement.statementKey());
            entry.put("mapper_key", statement.mapperKey());
            entry.put("mapper_relative_path", statement.mapperRelativePath());
            entry.put("source_start_line", statement.startLine());
            entry.put("source_end_line", statement.endLine());
            entry.put("source_sha256", statement.sourceSha256());
            entry.put("report", relativeStatementDirectory(statement) + "/report.md");
            entry.put("summary", relativeStatementDirectory(statement) + "/summary.json");
            entry.put("database_evidence", relativeStatementDirectory(statement) + "/database-evidence.json");
            ArrayNode findingIds = entry.putArray("finding_ids");
            summary.findings().forEach(finding -> findingIds.add(finding.id()));
        }
        return root;
    }

    private void writeMapperIndexes(
            Path root,
            MyBatisSqlInventory inventory,
            List<StatementSummary> summaries
    ) throws IOException {
        Map<String, StatementSummary> byKey = new LinkedHashMap<>();
        summaries.forEach(summary -> byKey.put(summary.statement().statementKey(), summary));
        for (MyBatisMapperInventory mapper : inventory.mappers()) {
            Path index = root.resolve(statementIndex(mapper.mapperKey()));
            Files.createDirectories(index.getParent());
            List<StatementSummary> mapperSummaries = mapper.statements().stream()
                    .map(statement -> byKey.get(statement.statementKey()))
                    .filter(Objects::nonNull)
                    .toList();
            SeverityCounts counts = severityCounts(mapperSummaries);
            StringBuilder md = new StringBuilder("# Mapper SQL Review: ")
                    .append(mapper.namespace()).append("\n\n")
                    .append("- Mapper: `").append(mapper.mapperRelativePath()).append("`\n")
                    .append("- Statements: `").append(mapper.statements().size()).append("`\n")
                    .append("- Findings: `").append(counts.total()).append("`\n")
                    .append("- P0: `").append(counts.p0()).append("`\n")
                    .append("- P1: `").append(counts.p1()).append("`\n")
                    .append("- P2: `").append(counts.p2()).append("`\n")
                    .append("- P3: `").append(counts.p3()).append("`\n\n")
                    .append("- [Project report](../../").append(MAIN_REPORT).append(")\n\n")
                    .append("## Statements\n\n");
            if (mapper.statements().isEmpty()) {
                md.append("No mapped SQL statements were discovered in this mapper.\n");
            } else {
                for (MyBatisSqlStatement statement : mapper.statements()) {
                    md.append("- [`").append(statement.statementKey()).append("`](sql/")
                            .append(statement.statementKey()).append("/report.md) — `")
                            .append(commandType(statement)).append("`, findings `")
                            .append(byKey.get(statement.statementKey()).findings().size()).append("`\n");
                }
            }
            Files.writeString(index, md.toString(), StandardCharsets.UTF_8);
        }
    }

    private String renderProjectReport(
            Project project,
            MyBatisSqlInventory inventory,
            SeverityCounts severity
    ) {
        StringBuilder md = new StringBuilder("# MyBatis SQL Review: ")
                .append(project.name()).append("\n\n")
                .append("- Project id: `").append(project.id()).append("`\n")
                .append("- Repository: `").append(project.repository()).append("`\n")
                .append("- Mappers: `").append(inventory.mappers().size()).append("`\n")
                .append("- Statements: `").append(inventory.statements().size()).append("`\n")
                .append("- Findings: `").append(severity.total()).append("`\n")
                .append("- P0: `").append(severity.p0()).append("`\n")
                .append("- P1: `").append(severity.p1()).append("`\n")
                .append("- P2: `").append(severity.p2()).append("`\n")
                .append("- P3: `").append(severity.p3()).append("`\n\n")
                .append("## Project artifacts\n\n")
                .append("- [SQL inventory](").append(INVENTORY).append(")\n")
                .append("- [SQL tasks](").append(TASKS).append(")\n")
                .append("- [Traceability](").append(TRACEABILITY).append(")\n")
                .append("- [Data quality](").append(DATA_QUALITY).append(")\n\n")
                .append("## Mapper reports\n\n");
        if (inventory.mappers().isEmpty()) {
            md.append("No mapper XML files were discovered.\n");
        } else {
            for (MyBatisMapperInventory mapper : inventory.mappers()) {
                md.append("- [").append(mapper.namespace()).append("](")
                        .append(relative(statementIndex(mapper.mapperKey()))).append(") — statements `")
                        .append(mapper.statements().size()).append("`\n");
            }
        }
        return md.toString();
    }

    private String renderDataQuality(MyBatisSqlInventory inventory, List<StatementSummary> summaries) {
        return """
                # MyBatis SQL Review Data Quality

                - Status: `complete`
                - Mapper inventory entries: `%d`
                - SQL task entries: `%d`
                - Validated SQL summaries: `%d`
                - Publication gate: every mapped statement has report.md, summary.json, and database-evidence.json

                ## Evidence boundary

                Findings are review results and do not cause a technical workflow failure. Missing discovery, database, tool-call audit, candidate artifacts, schema validation, or aggregate link targets fail the run before stable publication.
                """.formatted(inventory.mappers().size(), inventory.statements().size(), summaries.size());
    }

    private SeverityCounts severityCounts(List<StatementSummary> summaries) {
        int p0 = 0;
        int p1 = 0;
        int p2 = 0;
        int p3 = 0;
        for (StatementSummary summary : summaries) {
            for (Finding finding : summary.findings()) {
                switch (finding.severity()) {
                    case "P0" -> p0++;
                    case "P1" -> p1++;
                    case "P2" -> p2++;
                    case "P3" -> p3++;
                    default -> throw new IllegalStateException("unsupported project severity: " + finding.severity());
                }
            }
        }
        return new SeverityCounts(p0, p1, p2, p3, p0 + p1 + p2 + p3);
    }

    private String toProjectSeverity(String severity) {
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> "P0";
            case "high" -> "P1";
            case "medium" -> "P2";
            case "low", "info" -> "P3";
            default -> throw new IllegalStateException("unsupported SQL finding severity: " + severity);
        };
    }

    private void writeSeverity(ObjectNode node, SeverityCounts counts) {
        node.put("P0", counts.p0());
        node.put("P1", counts.p1());
        node.put("P2", counts.p2());
        node.put("P3", counts.p3());
    }

    private void writeJson(Path path, JsonNode value) throws IOException {
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private MyBatisSqlStatement readStatement(JsonNode node) {
        return new MyBatisSqlStatement(
                requiredText(node, "mapper_relative_path", "published SQL inventory statement"),
                requiredText(node, "namespace", "published SQL inventory statement"),
                requiredText(node, "statement_id", "published SQL inventory statement"),
                requiredText(node, "command_type", "published SQL inventory statement"),
                requiredBoolean(node, "select_key", "published SQL inventory statement"),
                requiredInt(node, "select_key_ordinal", "published SQL inventory statement"),
                requiredInt(node, "start_line", "published SQL inventory statement"),
                requiredInt(node, "end_line", "published SQL inventory statement"),
                requiredText(node, "raw_xml", "published SQL inventory statement"),
                requiredText(node, "normalized_sql", "published SQL inventory statement"),
                textList(node, "dynamic_node_names"),
                textList(node, "parameter_placeholders"),
                textList(node, "resolved_fragment_ids"),
                requiredText(node, "source_sha256", "published SQL inventory statement"),
                requiredText(node, "mapper_key", "published SQL inventory statement"),
                requiredText(node, "statement_key", "published SQL inventory statement")
        );
    }

    private List<String> textList(JsonNode node, String field) {
        JsonNode array = node.path(field);
        if (!array.isArray()) {
            throw new IllegalStateException("published SQL inventory statement." + field + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                throw new IllegalStateException("published SQL inventory statement." + field + " must contain strings");
            }
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private String requiredText(JsonNode node, String field, String label) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(label + "." + field + " must be a non-blank string");
        }
        return value.asText();
    }

    private void requireExactText(JsonNode node, String field, String expected, String statementKey) {
        String actual = requiredText(node, field, "SQL summary " + statementKey);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("SQL summary " + field + " does not match " + statementKey);
        }
    }

    private boolean requiredBoolean(JsonNode node, String field, String label) {
        if (!node.path(field).isBoolean()) {
            throw new IllegalStateException(label + "." + field + " must be boolean");
        }
        return node.path(field).asBoolean();
    }

    private int requiredInt(JsonNode node, String field, String label) {
        if (!node.path(field).isIntegralNumber()) {
            throw new IllegalStateException(label + "." + field + " must be integer");
        }
        return node.path(field).asInt();
    }

    private static String commandType(MyBatisSqlStatement statement) {
        return statement.selectKey() ? "selectKey" : statement.commandType().toLowerCase(Locale.ROOT);
    }

    private static Path statementIndex(String mapperKey) {
        return Path.of("reports").resolve(mapperKey).resolve("index.md");
    }

    private static String relativeStatementDirectory(MyBatisSqlStatement statement) {
        return "reports/" + statement.mapperKey() + "/sql/" + statement.statementKey();
    }

    private static String relative(Path path) {
        return path.toString().replace('\\', '/');
    }

    public record Project(String id, String name, Path repository) {
        public Project {
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("project id and name must be non-blank");
            }
            Objects.requireNonNull(repository, "repository");
        }
    }

    public record SeverityCounts(int p0, int p1, int p2, int p3, int total) {
    }

    public record RenderResult(
            Path mainReport,
            int mapperCount,
            int statementCount,
            SeverityCounts severityCounts
    ) {
    }

    private record StatementSummary(MyBatisSqlStatement statement, List<Finding> findings) {
    }

    private record Finding(String id, String severity) {
    }
}
