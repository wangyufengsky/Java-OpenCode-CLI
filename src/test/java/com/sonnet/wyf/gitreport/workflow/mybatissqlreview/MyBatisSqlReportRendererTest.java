package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
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
        MyBatisSqlInventory inventory = inventory("findCritical", "updateHigh", "insertMedium", "generatedKey", "findClean");
        List<MyBatisSqlStatement> statements = inventory.statements();
        MyBatisMapperInventory mapper = inventory.mappers().getFirst();
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
        assertThat(tempDir.resolve("reports").resolve(mapper.mapperKey()).resolve("index.md")).isRegularFile();

        JsonNode tasks = objectMapper.readTree(tempDir.resolve("sql-tasks.json").toFile());
        JsonNode traceability = objectMapper.readTree(tempDir.resolve("traceability.json").toFile());
        assertThat(tasks.path("totals").path("statements").asInt()).isEqualTo(5);
        assertThat(tasks.path("totals").path("findings").asInt()).isEqualTo(5);
        assertThat(tasks.path("totals").path("severity").toString())
                .isEqualTo("{\"P0\":1,\"P1\":1,\"P2\":1,\"P3\":2}");
        assertThat(traceability.path("entries")).hasSize(5);

        String mapperIndex = Files.readString(tempDir.resolve("reports").resolve(mapper.mapperKey()).resolve("index.md"));
        String projectReport = Files.readString(result.mainReport());
        assertThat(mapperIndex).contains("Statements: `5`", "Findings: `5`", "P0: `1`", "P3: `2`");
        assertThat(projectReport).contains("Mappers: `1`", "Statements: `5`", "Findings: `5`");
        assertAllMarkdownLinksExist(tempDir);
    }

    @Test
    void rejectsMissingTaskArtifactsInsteadOfPublishingAnIncompleteAggregate() {
        MyBatisSqlInventory inventory = inventory("findMissing");
        MyBatisSqlStatement statement = inventory.statements().getFirst();

        assertThatThrownBy(() -> new MyBatisSqlReportRenderer(objectMapper).render(
                tempDir,
                new MyBatisSqlReportRenderer.Project("demo", "Demo", tempDir.resolve("repo")),
                inventory
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing SQL review artifact")
                .hasMessageContaining(statement.statementKey());
        assertThat(tempDir.resolve("mybatis-sql-review-report.md")).doesNotExist();
    }

    @Test
    void rendersAValidZeroSqlProjectWhenMapperDiscoverySucceeded() throws Exception {
        MyBatisSqlInventory inventory = inventory();
        MyBatisMapperInventory mapper = inventory.mappers().getFirst();

        MyBatisSqlReportRenderer.RenderResult result = new MyBatisSqlReportRenderer(objectMapper).render(
                tempDir,
                new MyBatisSqlReportRenderer.Project("empty", "Empty Project", tempDir.resolve("repo")),
                inventory
        );

        assertThat(result.statementCount()).isZero();
        assertThat(result.severityCounts().total()).isZero();
        assertThat(result.mainReport()).content().contains("Statements: `0`", "Findings: `0`");
        assertThat(tempDir.resolve("reports").resolve(mapper.mapperKey()).resolve("index.md"))
                .content().contains("No mapped SQL statements were discovered");
        assertAllMarkdownLinksExist(tempDir);
    }

    @Test
    void rejectsTamperedPublishedInventoryIdentityCountsAndBindings() throws Exception {
        MyBatisSqlInventory inventory = inventory("findOne", "findTwo");
        MyBatisSqlReportRenderer renderer = new MyBatisSqlReportRenderer(objectMapper);
        Path snapshot = tempDir.resolve("snapshot.json");

        assertTamperedSnapshotRejected(renderer, snapshot, inventory,
                root -> ((ObjectNode) root.path("mappers").get(0)).put("mapper_key", "../escape"),
                "mapper_key");
        assertTamperedSnapshotRejected(renderer, snapshot, inventory,
                root -> ((ObjectNode) root.path("mappers").get(0)).put("statement_count", 99),
                "statement_count");
        assertTamperedSnapshotRejected(renderer, snapshot, inventory,
                root -> root.withArray("mappers").add(root.path("mappers").get(0).deepCopy()),
                "duplicate mapper_key");
        assertTamperedSnapshotRejected(renderer, snapshot, inventory,
                root -> root.path("statements").get(1).fields().forEachRemaining(
                        entry -> ((ObjectNode) root.path("statements").get(0)).set(entry.getKey(), entry.getValue())),
                "duplicate");
        assertTamperedSnapshotRejected(renderer, snapshot, inventory,
                root -> ((ObjectNode) root.path("statements").get(0)).put("namespace", "com.example.OtherMapper"),
                "binding");
        assertTamperedSnapshotRejected(renderer, snapshot, inventory,
                root -> ((ObjectNode) root.path("mappers").get(0)).put("mapper_relative_path", "../OrderMapper.xml"),
                "mapper_relative_path");
        assertTamperedSnapshotRejected(renderer, snapshot, inventory,
                root -> ((ObjectNode) root.path("mappers").get(0)).withArray("statement_keys").remove(0),
                "statement_keys");
    }

    @Test
    void validatesInMemoryInventoryBeforeLookingForTaskArtifactsOrWritingAggregates() {
        MyBatisSqlInventory valid = inventory("findOne");
        MyBatisMapperInventory mapper = valid.mappers().getFirst();
        MyBatisMapperInventory tampered = new MyBatisMapperInventory(
                mapper.mapperRelativePath(), mapper.namespace(), mapper.sourceSha256(), "../escape", mapper.statements());

        assertThatThrownBy(() -> new MyBatisSqlReportRenderer(objectMapper).render(
                tempDir,
                new MyBatisSqlReportRenderer.Project("demo", "Demo", tempDir.resolve("repo")),
                new MyBatisSqlInventory(List.of(tampered), valid.statements())
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mapper_key");
        assertThat(tempDir.resolve(MyBatisSqlReportRenderer.MAIN_REPORT)).doesNotExist();
    }

    @Test
    void rejectsInconsistentSummaryStatusRiskAndDuplicateFindingIds() throws Exception {
        MyBatisSqlInventory inventory = inventory("findOne");
        MyBatisSqlStatement statement = inventory.statements().getFirst();
        MyBatisSqlReportRenderer renderer = new MyBatisSqlReportRenderer(objectMapper);

        writeTaskArtifacts(statement, List.of("critical", "low"));
        mutateSummary(statement, summary -> summary.put("risk_level", "low"));
        assertThatThrownBy(() -> renderer.render(tempDir, project(), inventory))
                .hasMessageContaining("risk_level");

        writeTaskArtifacts(statement, List.of("high"));
        mutateSummary(statement, summary -> summary.put("status", "no-findings"));
        assertThatThrownBy(() -> renderer.render(tempDir, project(), inventory))
                .hasMessageContaining("status");

        writeTaskArtifacts(statement, List.of());
        mutateSummary(statement, summary -> summary.put("status", "reviewed"));
        assertThatThrownBy(() -> renderer.render(tempDir, project(), inventory))
                .hasMessageContaining("status");

        writeTaskArtifacts(statement, List.of("medium", "low"));
        mutateSummary(statement, summary -> ((ObjectNode) summary.path("findings").get(1))
                .put("id", summary.path("findings").get(0).path("id").asText()));
        assertThatThrownBy(() -> renderer.render(tempDir, project(), inventory))
                .hasMessageContaining("finding id").hasMessageContaining("unique");
    }

    @Test
    void escapesRepositoryDerivedMarkdownInGeneratedAggregateFiles() throws Exception {
        Path repository = tempDir.resolve("injected-repo");
        Files.createDirectories(repository);
        Files.writeString(repository.resolve("OrderMapper.xml"), """
                <mapper namespace="com.example.[evil](https://evil.example)&lt;img src=x&gt;">
                  <select id="find[bad](https://bad.example)">SELECT 1</select>
                </mapper>
                """);
        MyBatisSqlInventory inventory = new MyBatisSqlInventoryBuilder().build(repository, List.of("**/*.xml"), List.of());
        writeTaskArtifacts(inventory.statements().getFirst(), List.of());

        new MyBatisSqlReportRenderer(objectMapper).render(
                tempDir,
                new MyBatisSqlReportRenderer.Project(
                        "[id](https://id.example)", "Demo <img src=x> [name](https://name.example)", repository),
                inventory
        );

        String projectReport = Files.readString(tempDir.resolve(MyBatisSqlReportRenderer.MAIN_REPORT));
        String mapperReport = Files.readString(tempDir.resolve("reports")
                .resolve(inventory.mappers().getFirst().mapperKey()).resolve("index.md"));
        assertThat(projectReport).doesNotContain("https://evil.example", "https://id.example", "https://name.example", "<img");
        assertThat(mapperReport).doesNotContain("https://evil.example", "https://bad.example", "<img");
        assertThat(projectReport).contains("&lt;img src=x&gt;");
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
                .put("command_type", statement.selectKey()
                        ? "selectKey"
                        : statement.commandType().toLowerCase(Locale.ROOT))
                .put("select_key", statement.selectKey())
                .put("risk_level", highestRisk(severities));
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

    private MyBatisSqlInventory inventory(String... ids) {
        try {
            Path repository = tempDir.resolve("repo");
            Files.createDirectories(repository.resolve("src/main/resources"));
            StringBuilder xml = new StringBuilder("<mapper namespace=\"com.example.OrderMapper\">\n");
            for (String id : ids) {
                xml.append("<select id=\"").append(id).append("\">SELECT 1</select>\n");
            }
            xml.append("</mapper>\n");
            Files.writeString(repository.resolve("src/main/resources/OrderMapper.xml"), xml);
            return new MyBatisSqlInventoryBuilder().build(repository, List.of("**/*.xml"), List.of());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertTamperedSnapshotRejected(
            MyBatisSqlReportRenderer renderer,
            Path snapshot,
            MyBatisSqlInventory inventory,
            Consumer<ObjectNode> mutation,
            String message
    ) throws Exception {
        renderer.writeInventorySnapshot(tempDir, inventory);
        ObjectNode root = (ObjectNode) objectMapper.readTree(tempDir.resolve(MyBatisSqlReportRenderer.INVENTORY).toFile());
        mutation.accept(root);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(snapshot.toFile(), root);
        assertThatThrownBy(() -> renderer.readInventory(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }

    private void mutateSummary(MyBatisSqlStatement statement, Consumer<ObjectNode> mutation) throws Exception {
        Path summaryPath = MyBatisSqlReportRenderer.statementDirectory(tempDir, statement).resolve("summary.json");
        ObjectNode summary = (ObjectNode) objectMapper.readTree(summaryPath.toFile());
        mutation.accept(summary);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(summaryPath.toFile(), summary);
    }

    private MyBatisSqlReportRenderer.Project project() {
        return new MyBatisSqlReportRenderer.Project("demo", "Demo", tempDir.resolve("repo"));
    }

    private String highestRisk(List<String> severities) {
        for (String severity : List.of("critical", "high", "medium", "low", "info")) {
            if (severities.contains(severity)) {
                return "info".equals(severity) ? "low" : severity;
            }
        }
        return "none";
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
