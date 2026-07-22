package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisSqlReportRendererTest {
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^]]*]\\(([^)]+)\\)");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void rendersDeterministicProjectMapperAndTraceabilityArtifactsWithLiveRelativeLinks() throws Exception {
        List<MyBatisSqlStatement> statements = List.of(
                statement("findCritical", "select", false, "find-critical"),
                statement("updateHigh", "update", false, "update-high"),
                statement("insertMedium", "insert", false, "insert-medium"),
                statement("generatedKey", "select", true, "generated-key"),
                statement("findClean", "select", false, "find-clean")
        );
        MyBatisMapperInventory mapper = new MyBatisMapperInventory(
                "src/main/resources/OrderMapper.xml",
                "com.example.OrderMapper",
                "source-sha",
                "order-mapper",
                statements
        );
        MyBatisSqlInventory inventory = new MyBatisSqlInventory(List.of(mapper), statements);
        writeTaskArtifacts(statements.get(0), List.of("critical"));
        writeTaskArtifacts(statements.get(1), List.of("high"));
        writeTaskArtifacts(statements.get(2), List.of("medium"));
        writeTaskArtifacts(statements.get(3), List.of("low", "info"));
        writeTaskArtifacts(statements.get(4), List.of());

        MyBatisSqlReportRenderer.RenderResult result = new MyBatisSqlReportRenderer(objectMapper).render(
                tempDir,
                new MyBatisSqlReportRenderer.Project("demo", "Demo Project", tempDir.resolve("repo")),
                inventory
        );

        assertThat(result.mainReport()).isEqualTo(tempDir.resolve("mybatis-sql-review-report.md"));
        assertThat(result.mapperCount()).isEqualTo(1);
        assertThat(result.statementCount()).isEqualTo(5);
        assertThat(result.severityCounts()).isEqualTo(
                new MyBatisSqlReportRenderer.SeverityCounts(1, 1, 1, 2, 5)
        );
        assertThat(tempDir.resolve("sql-inventory.json")).isRegularFile();
        assertThat(tempDir.resolve("sql-tasks.json")).isRegularFile();
        assertThat(tempDir.resolve("traceability.json")).isRegularFile();
        assertThat(tempDir.resolve("data-quality.md")).isRegularFile();
        assertThat(tempDir.resolve("reports/order-mapper/index.md")).isRegularFile();

        JsonNode tasks = objectMapper.readTree(tempDir.resolve("sql-tasks.json").toFile());
        JsonNode traceability = objectMapper.readTree(tempDir.resolve("traceability.json").toFile());
        assertThat(tasks.path("totals").path("statements").asInt()).isEqualTo(5);
        assertThat(tasks.path("totals").path("findings").asInt()).isEqualTo(5);
        assertThat(tasks.path("totals").path("severity").toString())
                .isEqualTo("{\"P0\":1,\"P1\":1,\"P2\":1,\"P3\":2}");
        assertThat(traceability.path("entries")).hasSize(5);

        String mapperIndex = Files.readString(tempDir.resolve("reports/order-mapper/index.md"));
        String projectReport = Files.readString(result.mainReport());
        assertThat(mapperIndex).contains("Statements: `5`", "Findings: `5`", "P0: `1`", "P3: `2`");
        assertThat(projectReport).contains("Mappers: `1`", "Statements: `5`", "Findings: `5`");
        assertAllMarkdownLinksExist(tempDir);
    }

    @Test
    void rejectsMissingTaskArtifactsInsteadOfPublishingAnIncompleteAggregate() {
        MyBatisSqlStatement statement = statement("findMissing", "select", false, "find-missing");
        MyBatisMapperInventory mapper = new MyBatisMapperInventory(
                statement.mapperRelativePath(), statement.namespace(), statement.sourceSha256(),
                statement.mapperKey(), List.of(statement)
        );

        assertThatThrownBy(() -> new MyBatisSqlReportRenderer(objectMapper).render(
                tempDir,
                new MyBatisSqlReportRenderer.Project("demo", "Demo", tempDir.resolve("repo")),
                new MyBatisSqlInventory(List.of(mapper), List.of(statement))
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing SQL review artifact")
                .hasMessageContaining(statement.statementKey());
        assertThat(tempDir.resolve("mybatis-sql-review-report.md")).doesNotExist();
    }

    @Test
    void rendersAValidZeroSqlProjectWhenMapperDiscoverySucceeded() throws Exception {
        MyBatisMapperInventory mapper = new MyBatisMapperInventory(
                "src/main/resources/EmptyMapper.xml",
                "com.example.EmptyMapper",
                "empty-sha",
                "empty-mapper",
                List.of()
        );

        MyBatisSqlReportRenderer.RenderResult result = new MyBatisSqlReportRenderer(objectMapper).render(
                tempDir,
                new MyBatisSqlReportRenderer.Project("empty", "Empty Project", tempDir.resolve("repo")),
                new MyBatisSqlInventory(List.of(mapper), List.of())
        );

        assertThat(result.statementCount()).isZero();
        assertThat(result.severityCounts().total()).isZero();
        assertThat(result.mainReport()).content().contains("Statements: `0`", "Findings: `0`");
        assertThat(tempDir.resolve("reports/empty-mapper/index.md"))
                .content().contains("No mapped SQL statements were discovered");
        assertAllMarkdownLinksExist(tempDir);
    }

    private void writeTaskArtifacts(MyBatisSqlStatement statement, List<String> severities) throws Exception {
        Path directory = MyBatisSqlReportRenderer.statementDirectory(tempDir, statement);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("report.md"), "# SQL Review " + statement.statementKey() + "\n");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                directory.resolve("database-evidence.json").toFile(),
                objectMapper.createObjectNode().put("statement_key", statement.statementKey())
        );
        var summary = objectMapper.createObjectNode()
                .put("schema_version", "mybatis-sql-review-summary/v1")
                .put("statement_key", statement.statementKey())
                .put("mapper_relative_path", statement.mapperRelativePath())
                .put("namespace", statement.namespace())
                .put("statement_id", statement.id())
                .put("status", severities.isEmpty() ? "no-findings" : "reviewed")
                .put("command_type", statement.selectKey() ? "selectKey" : statement.commandType())
                .put("select_key", statement.selectKey())
                .put("risk_level", severities.isEmpty() ? "none" : severities.getFirst());
        var findings = summary.putArray("findings");
        for (int index = 0; index < severities.size(); index++) {
            findings.addObject()
                    .put("id", "F-" + statement.statementKey() + "-" + index)
                    .put("severity", severities.get(index))
                    .put("category", "test")
                    .put("title", "Finding " + index);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("summary.json").toFile(), summary);
    }

    private MyBatisSqlStatement statement(String id, String command, boolean selectKey, String statementKey) {
        return new MyBatisSqlStatement(
                "src/main/resources/OrderMapper.xml",
                "com.example.OrderMapper",
                id,
                command,
                selectKey,
                selectKey ? 1 : 0,
                2,
                4,
                "<" + command + " id=\"" + id + "\">SELECT 1</" + command + ">",
                "SELECT 1",
                List.of(),
                List.of(),
                List.of(),
                "source-sha",
                "order-mapper",
                statementKey
        );
    }

    private void assertAllMarkdownLinksExist(Path root) throws Exception {
        List<String> missing = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path markdown : paths.filter(path -> path.toString().endsWith(".md")).toList()) {
                Matcher matcher = MARKDOWN_LINK.matcher(Files.readString(markdown));
                while (matcher.find()) {
                    String target = matcher.group(1);
                    if (!target.contains("://")
                            && !Files.exists(markdown.getParent().resolve(target).normalize())) {
                        missing.add(markdown + " -> " + target);
                    }
                }
            }
        }
        assertThat(missing).isEmpty();
    }
}
