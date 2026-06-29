package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.preparation.CommandExecutor;
import com.sonnet.wyf.gitreport.preparation.CommentLineCounter;
import com.sonnet.wyf.gitreport.preparation.GitStatsCollector;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.OpenCodeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.scoring.WorkloadScoreCalculator;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEngineeringReportWorkflowChain;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyCodeReviewRunner;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEvidenceBuilder;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEvidenceValidator;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyReportRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyEngineeringReportWorkflowChainTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void fullModeGeneratesFreshGitEvidenceAndMarkdownReports() throws Exception {
        Path repo = writeGitRepo();
        Path out = tempDir.resolve("weekly-out");
        writeChainConfig(repo, out);
        WeeklyEngineeringReportWorkflowChain chain = chain();

        chain.run(new WorkflowRunRequest("full", "", "", LocalDate.of(2026, 6, 26), new OpenCodeSettings()));

        assertThat(out.resolve("weekly-git-evidence.json")).exists();
        assertThat(out.resolve("review-batches.json")).exists();
        assertThat(out.resolve("weekly-evidence.json")).content()
                .contains("\"weekly_git\"")
                .doesNotContain("smartesb_rewrite_review");
        assertThat(out.resolve("weekly-report.md")).content().contains("周度工程项目周报", "src/main/java/Foo.java");
        assertThat(out.resolve("team-risk-assessment.md")).exists();
        assertThat(out.resolve("people/author-001-alice-alice-example-com/weekly-person-report.md")).exists();
    }

    private WeeklyEngineeringReportWorkflowChain chain() {
        OpenCodeRunnerProperties runnerProperties = new OpenCodeRunnerProperties();
        runnerProperties.setConfigDir(tempDir.resolve("chains").toString());
        return new WeeklyEngineeringReportWorkflowChain(
                new ChainConfigLoader(new DefaultResourceLoader()),
                runnerProperties,
                new WeeklyEvidenceBuilder(objectMapper, new GitStatsCollector(new CommandExecutor(), new CommentLineCounter(), new WorkloadScoreCalculator(), objectMapper)),
                new WeeklyEvidenceValidator(objectMapper),
                fakeReviewRunner(),
                new WeeklyReportRenderer(objectMapper)
        );
    }

    private WeeklyCodeReviewRunner fakeReviewRunner() {
        return (properties, request, evidencePath, batchIds) -> {
            Map<String, Object> evidence = objectMapper.readValue(evidencePath.toFile(), Map.class);
            for (Map<String, Object> batch : (List<Map<String, Object>>) evidence.get("review_batches")) {
                Path summary = Path.of(batch.get("summary_json").toString());
                Files.createDirectories(summary.getParent());
                Files.writeString(Path.of(batch.get("review_md").toString()), "# 批次代码审查\n\n本批次未发现 P0/P1/P2 问题。\n");
                List<String> regionIds = ((List<Map<String, Object>>) batch.get("changed_regions")).stream()
                        .map(region -> region.get("region_id").toString())
                        .toList();
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(summary.toFile(), Map.ofEntries(
                        Map.entry("schema_version", "weekly-code-review-output/v1"),
                        Map.entry("batch_id", batch.get("batch_id")),
                        Map.entry("status", "completed"),
                        Map.entry("summary", "本批次未发现 P0/P1/P2 问题。"),
                        Map.entry("reviewed_region_ids", regionIds),
                        Map.entry("finding_counts", Map.of("P0", 0, "P1", 0, "P2", 0)),
                        Map.entry("findings", List.of()),
                        Map.entry("positive_signals", List.of()),
                        Map.entry("risk_signals", List.of()),
                        Map.entry("code_snippets", List.of()),
                        Map.entry("unverified", List.of())
                ));
            }
        };
    }

    private void writeChainConfig(Path repo, Path out) throws Exception {
        Path chains = tempDir.resolve("chains");
        Files.createDirectories(chains);
        Files.writeString(chains.resolve("weekly-engineering-report.yml"), """
                project:
                  id: "upfs-production"
                  name: "UPFS Production"
                  repo: "%s"
                paths:
                  out: "%s"
                week:
                  start: "2000-01-01"
                  end: "2099-12-31"
                  label: "test-week"
                git:
                  exclude:
                    - "target/**"
                    - "*.lock"
                """.formatted(repo, out));
    }

    private Path writeGitRepo() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("src/main/java"));
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("src/main/java/Foo.java"), """
                class Foo {
                    int add(int left, int right) {
                        return left + right;
                    }
                }
                """);
        GitTestSupport.run(repo, "git", "add", ".");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "add calculator");
        return repo;
    }
}
