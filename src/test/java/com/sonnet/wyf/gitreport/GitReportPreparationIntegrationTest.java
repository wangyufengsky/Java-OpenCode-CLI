package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.GitReportConstants;
import com.sonnet.wyf.gitreport.preparation.CommandExecutor;
import com.sonnet.wyf.gitreport.preparation.CommentLineCounter;
import com.sonnet.wyf.gitreport.preparation.GitReportPreparation;
import com.sonnet.wyf.gitreport.preparation.GitStatsCollector;
import com.sonnet.wyf.gitreport.preparation.ReportPreparationWriter;
import com.sonnet.wyf.gitreport.scoring.WorkloadScoreCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GitReportPreparationIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void preparationLogsCommitCollectionProgress(CapturedOutput output) throws Exception {
        Path repo = tempDir.resolve("repo-logging");
        Path out = tempDir.resolve("out-logging");
        Files.createDirectories(repo);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("First.java"), "class First {}\n");
        GitTestSupport.run(repo, "git", "add", ".");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "first change");
        Files.writeString(repo.resolve("Second.java"), "class Second {}\n");
        GitTestSupport.run(repo, "git", "add", ".");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "second change");

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));

        GitReportPreparation preparation = new GitReportPreparation(
                new GitStatsCollector(new CommandExecutor(), new CommentLineCounter(), new WorkloadScoreCalculator(), objectMapper),
                new ReportPreparationWriter(objectMapper)
        );
        preparation.prepare(properties);

        assertThat(output).contains("Collected git commits for contribution data: repo=")
                .contains("commitCount=2")
                .contains("Preparing git contribution commit 1/2")
                .contains("Preparing git contribution commit 2/2")
                .contains("Prepared git contribution commit 1/2")
                .contains("Prepared git contribution data: repo=");
    }

    @Test
    void javaPreparationCreatesSummaryIndexInputsDetailsAndTemplateSkeletons() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path out = tempDir.resolve("out");
        Files.createDirectories(repo);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("Demo.java"), "class Demo {\n  // comment\n  int a = 1;\n}\n");
        for (int index = 0; index < 12; index++) {
            Files.writeString(repo.resolve("Feature%02d.java".formatted(index)), "class Feature%02d {\n  int value = %d;\n}\n".formatted(index, index));
        }
        GitTestSupport.run(repo, "git", "add", "Demo.java");
        GitTestSupport.run(repo, "git", "add", ".");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "add demo");

        GitTestSupport.run(repo, "git", "config", "user.name", "Bob");
        GitTestSupport.run(repo, "git", "config", "user.email", "bob@example.com");
        Files.writeString(repo.resolve("README.md"), "# Documentation\nOnly docs.\n");
        GitTestSupport.run(repo, "git", "add", "README.md");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "docs only");

        GitReportProperties properties = new GitReportProperties();
        properties.getProject().setId("upfs-production");
        properties.getProject().setName("UPFS Production");
        properties.getProject().setRunId("manual-run-001");
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));
        properties.getDetailInput().setTopFiles(7);
        properties.getDetailInput().setCommits(3);

        GitReportPreparation preparation = new GitReportPreparation(
                new GitStatsCollector(new CommandExecutor(), new CommentLineCounter(), new WorkloadScoreCalculator(), objectMapper),
                new ReportPreparationWriter(objectMapper)
        );
        preparation.prepare(properties);

        JsonNode indexInputs = objectMapper.readTree(out.resolve("index_inputs.json").toFile());
        JsonNode summary = objectMapper.readTree(out.resolve("summary.json").toFile());

        assertThat(summary.get("metadata").get("project_id").asText()).isEqualTo("upfs-production");
        assertThat(summary.get("metadata").get("project_name").asText()).isEqualTo("UPFS Production");
        assertThat(summary.get("metadata").get("run_id").asText()).isEqualTo("manual-run-001");
        assertThat(indexInputs.get("metadata").get("project_id").asText()).isEqualTo("upfs-production");
        assertThat(indexInputs.get("tasks")).hasSize(1);
        assertThat(summary.get("ranking")).hasSize(1);
        assertThat(summary.get("ranking").get(0).get("author").asText()).isEqualTo("Alice <alice@example.com>");
        String finalReport = Files.readString(out.resolve("code-contribution-report.md"));
        assertThat(finalReport).contains("# 代码提交量统计报告", "{{RANKING_ROWS}}");
        assertThat(finalReport).doesNotContain(GitReportConstants.REPORT_MARKER);

        JsonNode task = indexInputs.get("tasks").get(0);
        Path detailJson = Path.of(task.get("detail_json").asText());
        Path reportMd = Path.of(task.get("report_md").asText());
        Path qualitySummaryJson = Path.of(task.get("quality_summary_json").asText());
        assertThat(detailJson).exists();
        String personReport = Files.readString(reportMd);
        assertThat(personReport).contains("# 个人代码提交量报告：Alice <alice@example.com>", "{{WORKLOAD_STRUCTURE_ANALYSIS}}");
        assertThat(personReport).doesNotContain(GitReportConstants.AUTHOR_REPORT_MARKER);
        JsonNode qualitySummary = objectMapper.readTree(qualitySummaryJson.toFile());
        assertThat(qualitySummary.get("author").asText()).isEqualTo("Alice <alice@example.com>");
        assertThat(qualitySummary.get("status").asText()).isEqualTo("pending");
        assertThat(qualitySummary.get("summary").asText()).isEqualTo("{{QUALITY_SUMMARY}}");
        assertThat(task.get("report_markdown_link").asText()).startsWith("[person-report.md](reports/");
        assertThat(task.has("report_marker")).isFalse();
        assertThat(task.has("quality_summary_marker")).isFalse();
        JsonNode detail = objectMapper.readTree(detailJson.toFile());
        assertThat(detail.get("metadata").get("project_id").asText()).isEqualTo("upfs-production");
        assertThat(detail.has("files")).isFalse();
        assertThat(detail.get("top_files")).hasSizeLessThanOrEqualTo(7);
        assertThat(detail.get("changed_regions")).hasSizeGreaterThan(0);
        assertThat(detail.get("changed_regions").get(0).has("hunk")).isTrue();
        assertThat(detail.get("execution_worklist").toString()).contains("inspect_changed_regions");
        assertThat(detail.get("execution_worklist").toString()).doesNotContain("inspect_top_files");
        assertThat(detail.get("commits")).hasSizeLessThanOrEqualTo(3);
        assertThat(detail.get("execution_worklist")).hasSize(10);
        assertThat(detail.get("execution_worklist").get(4).get("action").asText()).isEqualTo("draft_quality_summary");
        assertThat(detail.get("execution_worklist").get(5).get("action").asText()).isEqualTo("replace_quality_summary_json_fields");
        assertThat(detail.get("execution_worklist").get(6).get("action").asText()).isEqualTo("draft_person_report");
        assertThat(detail.get("execution_worklist").get(7).get("action").asText()).isEqualTo("replace_person_report_placeholders");
        assertThat(detail.at("/output/report_placeholders").isArray()).isTrue();
        assertThat(detail.at("/output/quality_summary_status_required").asText()).isEqualTo("completed");
    }

    @Test
    void detailChangedRegionsContainOnlyAuthorCommittedHunks() throws Exception {
        Path repo = tempDir.resolve("repo-regions");
        Path out = tempDir.resolve("out-regions");
        Files.createDirectories(repo);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("Demo.java"), """
                class Demo {
                  String stable = "base";
                  String bobOwned = "base";
                }
                """);
        GitTestSupport.run(repo, "git", "add", "Demo.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "base");

        GitTestSupport.run(repo, "git", "config", "user.name", "Bob");
        GitTestSupport.run(repo, "git", "config", "user.email", "bob@example.com");
        Files.writeString(repo.resolve("Demo.java"), """
                class Demo {
                  String stable = "base";
                  String bobOwned = "changed-by-bob";
                }
                """);
        GitTestSupport.run(repo, "git", "add", "Demo.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "bob changes own line");

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));
        properties.getDetailInput().setChangedRegions(10);
        properties.getDetailInput().setChangedRegionLines(20);

        GitReportPreparation preparation = new GitReportPreparation(
                new GitStatsCollector(new CommandExecutor(), new CommentLineCounter(), new WorkloadScoreCalculator(), objectMapper),
                new ReportPreparationWriter(objectMapper)
        );
        preparation.prepare(properties);

        JsonNode indexInputs = objectMapper.readTree(out.resolve("index_inputs.json").toFile());
        JsonNode bobTask = null;
        for (JsonNode task : indexInputs.get("tasks")) {
            if ("Bob <bob@example.com>".equals(task.get("author").asText())) {
                bobTask = task;
                break;
            }
        }
        assertThat(bobTask).isNotNull();
        JsonNode detail = objectMapper.readTree(Path.of(bobTask.get("detail_json").asText()).toFile());

        assertThat(detail.get("author").asText()).isEqualTo("Bob <bob@example.com>");
        assertThat(detail.get("top_files").get(0).get("path").asText()).isEqualTo("Demo.java");
        JsonNode changedRegion = detail.get("changed_regions").get(0);
        assertThat(changedRegion.get("file").asText()).isEqualTo("Demo.java");
        assertThat(changedRegion.get("line_start").asInt()).isEqualTo(3);
        assertThat(changedRegion.get("line_end").asInt()).isEqualTo(3);
        assertThat(changedRegion.get("hunk").asText()).contains("changed-by-bob");
        assertThat(changedRegion.get("hunk").asText()).doesNotContain("String stable = \"base\"");
    }

    @Test
    void javaRenameOnlyCommitIsCountedAgainstNewPath() throws Exception {
        Path repo = tempDir.resolve("repo-rename-only");
        Path out = tempDir.resolve("out-rename-only");
        Path sourceDir = repo.resolve("src/main/java/com/acme");
        Files.createDirectories(sourceDir);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(sourceDir.resolve("OldName.java"), """
                package com.acme;

                class OldName {
                  int value = 1;
                }
                """);
        GitTestSupport.run(repo, "git", "add", ".");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "base");

        GitTestSupport.run(repo, "git", "config", "user.name", "Bob");
        GitTestSupport.run(repo, "git", "config", "user.email", "bob@example.com");
        GitTestSupport.run(repo, "git", "mv", "src/main/java/com/acme/OldName.java", "src/main/java/com/acme/NewName.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "rename java class");

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));
        properties.getGit().setInclude(List.of("*.java"));

        GitReportPreparation preparation = new GitReportPreparation(
                new GitStatsCollector(new CommandExecutor(), new CommentLineCounter(), new WorkloadScoreCalculator(), objectMapper),
                new ReportPreparationWriter(objectMapper)
        );
        preparation.prepare(properties);

        JsonNode indexInputs = objectMapper.readTree(out.resolve("index_inputs.json").toFile());
        JsonNode bobTask = null;
        for (JsonNode task : indexInputs.get("tasks")) {
            if ("Bob <bob@example.com>".equals(task.get("author").asText())) {
                bobTask = task;
                break;
            }
        }
        assertThat(bobTask).isNotNull();
        JsonNode detail = objectMapper.readTree(Path.of(bobTask.get("detail_json").asText()).toFile());

        assertThat(detail.get("summary").get("commit_count").asInt()).isEqualTo(1);
        assertThat(detail.get("summary").get("file_change_count").asInt()).isEqualTo(1);
        assertThat(detail.get("top_files").get(0).get("path").asText()).isEqualTo("src/main/java/com/acme/NewName.java");
        assertThat(detail.get("changed_regions")).isEmpty();
    }

    @Test
    void detailChangedRegionsAreSortedByTopFilePriorityBeforeLimit() throws Exception {
        Path repo = tempDir.resolve("repo-priority");
        Path out = tempDir.resolve("out-priority");
        Files.createDirectories(repo);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Bob");
        GitTestSupport.run(repo, "git", "config", "user.email", "bob@example.com");
        Files.writeString(repo.resolve("High.java"), """
                class High {
                  int a = 1;
                  int b = 2;
                  int c = 3;
                  int d = 4;
                }
                """);
        Files.writeString(repo.resolve("Low.java"), """
                class Low {
                  int value = 1;
                }
                """);
        GitTestSupport.run(repo, "git", "add", ".");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "base");

        Files.writeString(repo.resolve("High.java"), """
                class High {
                  int a = 10;
                  int b = 20;
                  int c = 30;
                  int d = 40;
                }
                """);
        GitTestSupport.run(repo, "git", "add", "High.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "large high change");

        Files.writeString(repo.resolve("Low.java"), """
                class Low {
                  int value = 2;
                }
                """);
        GitTestSupport.run(repo, "git", "add", "Low.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "small low change");

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));
        properties.getDetailInput().setChangedRegions(1);

        GitReportPreparation preparation = new GitReportPreparation(
                new GitStatsCollector(new CommandExecutor(), new CommentLineCounter(), new WorkloadScoreCalculator(), objectMapper),
                new ReportPreparationWriter(objectMapper)
        );
        preparation.prepare(properties);

        JsonNode indexInputs = objectMapper.readTree(out.resolve("index_inputs.json").toFile());
        JsonNode detail = objectMapper.readTree(Path.of(indexInputs.get("tasks").get(0).get("detail_json").asText()).toFile());

        assertThat(detail.get("top_files").get(0).get("path").asText()).isEqualTo("High.java");
        assertThat(detail.get("changed_regions")).hasSize(1);
        assertThat(detail.get("changed_regions").get(0).get("file").asText()).isEqualTo("High.java");
    }
}
