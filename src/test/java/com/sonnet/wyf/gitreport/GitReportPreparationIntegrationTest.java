package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.GitReportConstants;
import com.sonnet.wyf.gitreport.preparation.CommandExecutor;
import com.sonnet.wyf.gitreport.preparation.CommentLineCounter;
import com.sonnet.wyf.gitreport.preparation.GitReportPreparation;
import com.sonnet.wyf.gitreport.preparation.GitStatsCollector;
import com.sonnet.wyf.gitreport.preparation.ReportPreparationWriter;
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
    void javaPreparationCreatesSummaryIndexInputsDetailsAndMarkers() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path out = tempDir.resolve("out");
        Files.createDirectories(repo);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("Demo.java"), "class Demo {\n  // comment\n  int a = 1;\n}\n");
        GitTestSupport.run(repo, "git", "add", "Demo.java");
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

        GitReportPreparation preparation = new GitReportPreparation(
                new GitStatsCollector(new CommandExecutor(), new CommentLineCounter()),
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
        assertThat(out.resolve("code-contribution-report.md")).hasContent(GitReportConstants.REPORT_MARKER + "\n");

        JsonNode task = indexInputs.get("tasks").get(0);
        Path detailJson = Path.of(task.get("detail_json").asText());
        Path reportMd = Path.of(task.get("report_md").asText());
        Path qualitySummaryJson = Path.of(task.get("quality_summary_json").asText());
        assertThat(detailJson).exists();
        assertThat(reportMd).hasContent(GitReportConstants.AUTHOR_REPORT_MARKER + "\n");
        assertThat(qualitySummaryJson).hasContent(GitReportConstants.QUALITY_SUMMARY_MARKER + "\n");
        assertThat(task.get("report_markdown_link").asText()).startsWith("[person-report.md](reports/");
        JsonNode detail = objectMapper.readTree(detailJson.toFile());
        assertThat(detail.get("metadata").get("project_id").asText()).isEqualTo("upfs-production");
        assertThat(detail.get("execution_worklist")).hasSize(10);
    }
}
