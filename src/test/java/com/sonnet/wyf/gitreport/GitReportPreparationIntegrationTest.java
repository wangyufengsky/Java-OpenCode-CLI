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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class GitReportPreparationIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

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
        properties.getDetailInput().setHunksPerAuthor(7);
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
        assertThat(detail.has("top_files")).isFalse();
        assertThat(detail.get("owned_hunks")).isNotNull();
        assertThat(detail.get("attributed_findings")).isNotNull();
        assertThat(detail.get("context_findings")).isNotNull();
        assertThat(detail.get("scanner_status")).isNotNull();
        assertThat(detail.get("commits")).hasSizeLessThanOrEqualTo(3);
        assertThat(detail.get("execution_worklist")).hasSize(8);
        assertThat(detail.at("/output/report_placeholders").isArray()).isTrue();
        assertThat(detail.at("/output/quality_summary_status_required").asText()).isEqualTo("completed");
    }
}
