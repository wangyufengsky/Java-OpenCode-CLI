package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.preparation.CommandExecutor;
import com.sonnet.wyf.gitreport.preparation.CommentLineCounter;
import com.sonnet.wyf.gitreport.preparation.GitReportPreparation;
import com.sonnet.wyf.gitreport.preparation.GitStatsCollector;
import com.sonnet.wyf.gitreport.preparation.ReportPreparationWriter;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.OpenCodeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.scoring.WorkloadScoreCalculator;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEngineeringReportWorkflowChain;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEvidenceBuilder;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEvidenceValidator;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyReportRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

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

        assertThat(out.resolve("sources/weekly-git/summary.json")).exists();
        assertThat(out.resolve("sources/weekly-git/index_inputs.json")).exists();
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
                new WeeklyEvidenceBuilder(objectMapper, new GitReportPreparation(
                        new GitStatsCollector(new CommandExecutor(), new CommentLineCounter(), new WorkloadScoreCalculator(), objectMapper),
                        new ReportPreparationWriter(objectMapper)
                )),
                new WeeklyEvidenceValidator(objectMapper),
                new WeeklyReportRenderer(objectMapper)
        );
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
