package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeTaskRunner;
import com.sonnet.wyf.gitreport.console.WorkflowEventSink;
import com.sonnet.wyf.gitreport.orchestration.ArtifactCompletenessValidator;
import com.sonnet.wyf.gitreport.orchestration.ConcurrentWorkflowTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.GitReportOrchestrator;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate;
import com.sonnet.wyf.gitreport.orchestration.RunStatusRepository;
import com.sonnet.wyf.gitreport.orchestration.SynthesisInputWriter;
import com.sonnet.wyf.gitreport.preparation.CommandExecutor;
import com.sonnet.wyf.gitreport.preparation.CommentLineCounter;
import com.sonnet.wyf.gitreport.preparation.GitReportPreparation;
import com.sonnet.wyf.gitreport.preparation.GitStatsCollector;
import com.sonnet.wyf.gitreport.preparation.ReportPreparationWriter;
import com.sonnet.wyf.gitreport.prompt.PromptBuilder;
import com.sonnet.wyf.gitreport.scoring.QualityScoreCalculator;
import com.sonnet.wyf.gitreport.scoring.QualityScoresWriter;
import com.sonnet.wyf.gitreport.scoring.WorkloadScoreCalculator;
import com.sonnet.wyf.gitreport.validation.AuthorOutputValidator;
import com.sonnet.wyf.gitreport.validation.FinalReportValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitReportOrchestratorIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> prompts = new ArrayList<>();
    private HttpServer server;
    private ThreadPoolTaskScheduler taskScheduler;
    private ThreadPoolTaskExecutor authorTaskExecutor;
    private ExecutorService delayedOutputExecutor;

    @TempDir
    Path tempDir;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
        if (authorTaskExecutor != null) {
            authorTaskExecutor.shutdown();
        }
        if (delayedOutputExecutor != null) {
            delayedOutputExecutor.shutdownNow();
        }
    }

    @Test
    void orchestratesPreparationAuthorWorkerQualityScoresAndSynthesisWithFakeAgentBridgeServer() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path out = tempDir.resolve("out");
        Files.createDirectories(repo);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("Demo.java"), "class Demo {\n  int a = 1;\n}\n");
        GitTestSupport.run(repo, "git", "add", "Demo.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "add demo");
        startFakeAgentBridgeServer();

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getAgentbridge().setWebBaseUrl(agentbridgeWebBaseUrl());
        properties.getAgentbridge().setValidationSettleSeconds(0);
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));

        orchestrator().run(properties);

        assertThat(out.resolve("quality-scores.json")).exists();
        assertThat(Files.readString(out.resolve("code-contribution-report.md"))).contains("# 代码提交量统计报告");
        JsonNode qualityScores = objectMapper.readTree(out.resolve("quality-scores.json").toFile());
        assertThat(qualityScores.get("rankings")).hasSize(1);
        JsonNode authorStatus = objectMapper.readTree(out.resolve("runs/author-001-alice-alice-example-com/agent-status.json").toFile());
        assertThat(authorStatus.path("taskId").asText()).isNotBlank();
        assertThat(authorStatus.path("agentbridgeWebBaseUrl").asText()).isEqualTo(agentbridgeWebBaseUrl());
        JsonNode synthesisStatus = objectMapper.readTree(out.resolve("runs/synthesis/status.json").toFile());
        assertThat(synthesisStatus.path("taskId").asText()).isNotBlank();
        assertThat(prompts).anySatisfy(prompt -> assertThat(prompt).contains("detail_json:"));
        assertThat(prompts).anySatisfy(prompt -> assertThat(prompt).contains("synthesis_inputs_json:"));
    }

    @Test
    void synthesisRerunRejectsMissingRepositoryBeforeAgentBridgeTaskStart() {
        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(tempDir.resolve("missing-repo"));
        properties.getPaths().setOut(tempDir.resolve("out-missing-repo"));

        assertThatThrownBy(() -> orchestrator().runSynthesisOnly(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paths.repo must be an existing local directory")
                .hasMessageContaining(tempDir.resolve("missing-repo").toAbsolutePath().normalize().toString());
    }

    @Test
    void synthesisRerunRequiresExistingPreparationOutputsBeforeAgentBridgeTaskStart() throws Exception {
        Path repo = tempDir.resolve("repo-missing-preparation");
        Path out = tempDir.resolve("out-missing-preparation");
        Files.createDirectories(repo);
        Files.createDirectories(out);
        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);

        assertThatThrownBy(() -> orchestrator().runSynthesisOnly(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("git-report rerun requires existing preparation output")
                .hasMessageContaining(out.resolve("summary.json").toString());
    }

    @Test
    void rerunsIncompleteAuthorOutputsBeforeSynthesis() throws Exception {
        Path repo = tempDir.resolve("repo-no-auto-retry-after-submit");
        Path out = tempDir.resolve("out-no-auto-retry-after-submit");
        Files.createDirectories(repo);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("Demo.java"), "class Demo {\n  int a = 1;\n}\n");
        GitTestSupport.run(repo, "git", "add", "Demo.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "add demo");

        AtomicInteger sessions = startFakeAgentBridgeServerWithoutOutputs();

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getAgentbridge().setWebBaseUrl(agentbridgeWebBaseUrl());
        properties.getAgentbridge().setValidationSettleSeconds(0);
        properties.getAgentbridge().setMaxRetries(2);
        properties.getAgentbridge().setTimeoutMinutes(0);
        properties.getAgentbridge().setValidationSettleSeconds(0);
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));

        assertThatThrownBy(() -> orchestrator().run(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("git-report author outputs incomplete after 5 rerun rounds")
                .hasMessageContaining("author-001-alice-alice-example-com");

        assertThat(sessions).hasValue(6);
        assertThat(prompts).filteredOn(prompt -> prompt.contains("detail_json:")).hasSize(6);
        assertThat(prompts).noneSatisfy(prompt -> assertThat(prompt).contains("Java 产物校验失败"));
        assertThat(prompts).noneSatisfy(prompt -> assertThat(prompt).contains("synthesis_inputs_json:"));
        assertThat(Files.readString(out.resolve("runs/incomplete-reports.json")))
                .contains("\"state\" : \"failed\"")
                .contains("\"rerunRounds\" : 5")
                .contains("author-001-alice-alice-example-com")
                .contains("person report contains unresolved template placeholder");
    }

    @Test
    void rerunsInvalidAuthorOutputInFreshSessionWithoutSameSessionCorrection() throws Exception {
        Path repo = tempDir.resolve("repo-fresh-rerun-correction");
        Path out = tempDir.resolve("out-fresh-rerun-correction");
        Files.createDirectories(repo);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("Demo.java"), "class Demo {\n  int a = 1;\n}\n");
        GitTestSupport.run(repo, "git", "add", "Demo.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "add demo");

        AtomicInteger sessions = startFakeAgentBridgeServerWithInvalidAuthorThenFreshRerun();

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getAgentbridge().setWebBaseUrl(agentbridgeWebBaseUrl());
        properties.getAgentbridge().setValidationSettleSeconds(0);
        properties.getAgentbridge().setMaxRetries(2);
        properties.getAgentbridge().setValidationSettleSeconds(0);
        properties.getAgentbridge().setValidationMaxCorrections(2);
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));

        orchestrator().run(properties);

        assertThat(sessions).hasValue(3);
        assertThat(prompts).filteredOn(prompt -> prompt.contains("detail_json:")).hasSize(2);
        assertThat(prompts).filteredOn(prompt -> prompt.contains("Java 产物校验失败")).isEmpty();
        assertThat(prompts).filteredOn(prompt -> prompt.contains("synthesis_inputs_json:")).hasSize(1);
        assertThat(Files.readString(out.resolve("runs/incomplete-reports.json")))
                .contains("\"state\" : \"completed\"")
                .contains("\"rerunRounds\" : 1");
        JsonNode authorStatus = objectMapper.readTree(out.resolve("runs/author-001-alice-alice-example-com/agent-status.json").toFile());
        assertThat(authorStatus.path("attempt").asInt()).isEqualTo(1);
        assertThat(authorStatus.path("taskId").asText()).startsWith("git-report-author-001-alice-alice-example-com-");
    }

    @Test
    void authorConcurrencyLimitsActiveAgentBridgeTasksUntilOutputsComplete() throws Exception {
        Path repo = tempDir.resolve("repo-concurrency");
        Path out = tempDir.resolve("out-concurrency");
        Files.createDirectories(repo);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("Alice.java"), "class Alice {}\n");
        GitTestSupport.run(repo, "git", "add", "Alice.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "add alice");
        GitTestSupport.run(repo, "git", "config", "user.name", "Bob");
        GitTestSupport.run(repo, "git", "config", "user.email", "bob@example.com");
        Files.writeString(repo.resolve("Bob.java"), "class Bob {}\n");
        GitTestSupport.run(repo, "git", "add", "Bob.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "add bob");

        AtomicInteger maxActiveAgentBridgeTasks = startFakeAgentBridgeServerWithDelayedOutputs(250);

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getAgentbridge().setWebBaseUrl(agentbridgeWebBaseUrl());
        properties.getAgentbridge().setValidationSettleSeconds(0);
        properties.getAgentbridge().setConcurrency(1);
        properties.getAgentbridge().setMaxConcurrency(1);
        properties.getAgentbridge().setTimeoutMinutes(1);
        properties.getAgentbridge().setValidationSettleSeconds(2);
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));

        orchestrator().run(properties);

        assertThat(prompts).filteredOn(prompt -> prompt.contains("detail_json:")).hasSize(2);
        assertThat(maxActiveAgentBridgeTasks).hasValue(1);
    }

    @Test
    void synthesisOnlyUsesExistingAuthorOutputsWithoutPreparationOrAuthorWorkers() throws Exception {
        Path repo = tempDir.resolve("repo-synthesis-only");
        Path out = tempDir.resolve("out-synthesis-only");
        Files.createDirectories(repo);
        Files.createDirectories(out.resolve("reports/author-001-alice"));
        Path personReport = out.resolve("reports/author-001-alice/person-report.md");
        Path qualitySummary = out.resolve("reports/author-001-alice/quality-summary.json");
        Files.writeString(personReport, "个人报告内容\n");
        Files.writeString(qualitySummary, """
                {
                  "author": "Alice <alice@example.com>",
                  "status": "completed",
                  "findings": [],
                  "positive_signals": [],
                  "risk_signals": [],
                  "code_snippets": [],
                  "unverified": [],
                  "summary": "无"
                }
                """);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("summary.json").toFile(), Map.of(
                "ranking", List.of(Map.of(
                        "author_key", "author-001-alice",
                        "author", "Alice <alice@example.com>",
                        "rank", 1,
                        "base_workload_score", 100.0
                ))
        ));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("index_inputs.json").toFile(), Map.of(
                "final_report", out.resolve("code-contribution-report.md").toString(),
                "tasks", List.of(Map.of(
                        "author_key", "author-001-alice",
                        "author", "Alice <alice@example.com>",
                        "rank", 1,
                        "report_md", personReport.toString(),
                        "quality_summary_json", qualitySummary.toString()
                ))
        ));
        Files.writeString(out.resolve("code-contribution-report.md"), "# 代码提交量统计报告\n\n{{RANKING_ROWS}}\n");
        startFakeAgentBridgeServer();

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getAgentbridge().setWebBaseUrl(agentbridgeWebBaseUrl());
        properties.getAgentbridge().setValidationSettleSeconds(0);
        GitReportPreparation preparation = new GitReportPreparation(null, null) {
            @Override
            public void prepare(GitReportProperties ignored) {
                throw new AssertionError("preparation must not run in synthesis-only mode");
            }
        };

        orchestrator(preparation).runSynthesisOnly(properties);

        assertThat(prompts).noneSatisfy(prompt -> assertThat(prompt).contains("detail_json:"));
        assertThat(prompts).anySatisfy(prompt -> assertThat(prompt).contains("synthesis_inputs_json:"));
        assertThat(out.resolve("quality-scores.json")).exists();
        assertThat(Files.readString(out.resolve("code-contribution-report.md"))).contains("# 代码提交量统计报告");
    }

    @Test
    void rerunSingleAuthorUsesExistingPreparationAndRunsSynthesisAfterTargetAuthorOnly() throws Exception {
        Path repo = tempDir.resolve("repo-rerun-author");
        Path out = tempDir.resolve("out-rerun-author");
        Files.createDirectories(repo);
        Files.createDirectories(out.resolve("details"));
        Files.createDirectories(out.resolve("reports/author-001-alice"));
        Files.createDirectories(out.resolve("reports/author-002-bob"));
        Path aliceDetail = out.resolve("details/author-001-alice.json");
        Path bobDetail = out.resolve("details/author-002-bob.json");
        Path aliceReport = out.resolve("reports/author-001-alice/person-report.md");
        Path aliceQuality = out.resolve("reports/author-001-alice/quality-summary.json");
        Path bobReport = out.resolve("reports/author-002-bob/person-report.md");
        Path bobQuality = out.resolve("reports/author-002-bob/quality-summary.json");
        Files.writeString(aliceReport, "# 个人代码提交量报告：Alice <alice@example.com>\n\n{{WORKLOAD_STRUCTURE_ANALYSIS}}\n");
        objectMapper.writeValue(aliceQuality.toFile(), Map.of(
                "author", "Alice <alice@example.com>",
                "status", "pending",
                "findings", List.of(),
                "positive_signals", List.of(),
                "risk_signals", List.of(),
                "code_snippets", List.of(),
                "unverified", List.of(),
                "summary", "{{QUALITY_SUMMARY}}"
        ));
        Files.writeString(bobReport, "Bob 个人报告\n");
        objectMapper.writeValue(bobQuality.toFile(), Map.of(
                "author", "Bob <bob@example.com>",
                "status", "completed",
                "findings", List.of(),
                "positive_signals", List.of(),
                "risk_signals", List.of(),
                "code_snippets", List.of(),
                "unverified", List.of(),
                "summary", "无"
        ));
        writeAuthorDetail(aliceDetail, "Alice <alice@example.com>", aliceReport, aliceQuality);
        writeAuthorDetail(bobDetail, "Bob <bob@example.com>", bobReport, bobQuality);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("summary.json").toFile(), Map.of(
                "ranking", List.of(
                        Map.of("author_key", "author-001-alice", "author", "Alice <alice@example.com>", "rank", 1, "base_workload_score", 100.0),
                        Map.of("author_key", "author-002-bob", "author", "Bob <bob@example.com>", "rank", 2, "base_workload_score", 80.0)
                )
        ));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("index_inputs.json").toFile(), Map.of(
                "final_report", out.resolve("code-contribution-report.md").toString(),
                "tasks", List.of(
                        Map.of("author_key", "author-001-alice", "author", "Alice <alice@example.com>", "rank", 1, "detail_json", aliceDetail.toString(), "report_md", aliceReport.toString(), "quality_summary_json", aliceQuality.toString()),
                        Map.of("author_key", "author-002-bob", "author", "Bob <bob@example.com>", "rank", 2, "detail_json", bobDetail.toString(), "report_md", bobReport.toString(), "quality_summary_json", bobQuality.toString())
                )
        ));
        Files.writeString(out.resolve("code-contribution-report.md"), "# 代码提交量统计报告\n\n{{RANKING_ROWS}}\n");
        startFakeAgentBridgeServer();

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getAgentbridge().setWebBaseUrl(agentbridgeWebBaseUrl());
        properties.getAgentbridge().setValidationSettleSeconds(0);
        GitReportPreparation preparation = new GitReportPreparation(null, null) {
            @Override
            public void prepare(GitReportProperties ignored) {
                throw new AssertionError("preparation must not run for single-author rerun");
            }
        };

        orchestrator(preparation).runSingleAuthor(properties, "author-001-alice");

        assertThat(prompts).anySatisfy(prompt -> assertThat(prompt).contains("detail_json: " + aliceDetail));
        assertThat(prompts).noneSatisfy(prompt -> assertThat(prompt).contains("detail_json: " + bobDetail));
        assertThat(prompts).anySatisfy(prompt -> assertThat(prompt).contains("synthesis_inputs_json:"));
        assertThat(out.resolve("quality-scores.json")).exists();
        assertThat(Files.readString(out.resolve("code-contribution-report.md"))).contains("# 代码提交量统计报告");
        assertThat(bobReport).hasContent("Bob 个人报告\n");
    }

    @Test
    void rerunMultipleAuthorsUsesExistingPreparationAndRunsSynthesisOnce() throws Exception {
        Path repo = tempDir.resolve("repo-rerun-authors");
        Path out = tempDir.resolve("out-rerun-authors");
        Files.createDirectories(repo);
        Files.createDirectories(out.resolve("details"));
        Files.createDirectories(out.resolve("reports/author-001-alice"));
        Files.createDirectories(out.resolve("reports/author-002-bob"));
        Files.createDirectories(out.resolve("reports/author-003-carol"));
        Path aliceDetail = out.resolve("details/author-001-alice.json");
        Path bobDetail = out.resolve("details/author-002-bob.json");
        Path carolDetail = out.resolve("details/author-003-carol.json");
        Path aliceReport = out.resolve("reports/author-001-alice/person-report.md");
        Path aliceQuality = out.resolve("reports/author-001-alice/quality-summary.json");
        Path bobReport = out.resolve("reports/author-002-bob/person-report.md");
        Path bobQuality = out.resolve("reports/author-002-bob/quality-summary.json");
        Path carolReport = out.resolve("reports/author-003-carol/person-report.md");
        Path carolQuality = out.resolve("reports/author-003-carol/quality-summary.json");
        Files.writeString(aliceReport, "# 个人代码提交量报告：Alice <alice@example.com>\n\n{{WORKLOAD_STRUCTURE_ANALYSIS}}\n");
        Files.writeString(bobReport, "# 个人代码提交量报告：Bob <bob@example.com>\n\n{{WORKLOAD_STRUCTURE_ANALYSIS}}\n");
        Files.writeString(carolReport, "Carol 个人报告\n");
        objectMapper.writeValue(aliceQuality.toFile(), pendingQuality("Alice <alice@example.com>"));
        objectMapper.writeValue(bobQuality.toFile(), pendingQuality("Bob <bob@example.com>"));
        objectMapper.writeValue(carolQuality.toFile(), completedQuality("Carol <carol@example.com>"));
        writeAuthorDetail(aliceDetail, "Alice <alice@example.com>", aliceReport, aliceQuality);
        writeAuthorDetail(bobDetail, "Bob <bob@example.com>", bobReport, bobQuality);
        writeAuthorDetail(carolDetail, "Carol <carol@example.com>", carolReport, carolQuality);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("summary.json").toFile(), Map.of(
                "ranking", List.of(
                        Map.of("author_key", "author-001-alice", "author", "Alice <alice@example.com>", "rank", 1, "base_workload_score", 100.0),
                        Map.of("author_key", "author-002-bob", "author", "Bob <bob@example.com>", "rank", 2, "base_workload_score", 80.0),
                        Map.of("author_key", "author-003-carol", "author", "Carol <carol@example.com>", "rank", 3, "base_workload_score", 60.0)
                )
        ));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("index_inputs.json").toFile(), Map.of(
                "final_report", out.resolve("code-contribution-report.md").toString(),
                "tasks", List.of(
                        Map.of("author_key", "author-001-alice", "author", "Alice <alice@example.com>", "rank", 1, "detail_json", aliceDetail.toString(), "report_md", aliceReport.toString(), "quality_summary_json", aliceQuality.toString()),
                        Map.of("author_key", "author-002-bob", "author", "Bob <bob@example.com>", "rank", 2, "detail_json", bobDetail.toString(), "report_md", bobReport.toString(), "quality_summary_json", bobQuality.toString()),
                        Map.of("author_key", "author-003-carol", "author", "Carol <carol@example.com>", "rank", 3, "detail_json", carolDetail.toString(), "report_md", carolReport.toString(), "quality_summary_json", carolQuality.toString())
                )
        ));
        Files.writeString(out.resolve("code-contribution-report.md"), "# 代码提交量统计报告\n\n{{RANKING_ROWS}}\n");
        startFakeAgentBridgeServer();

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getAgentbridge().setWebBaseUrl(agentbridgeWebBaseUrl());
        properties.getAgentbridge().setValidationSettleSeconds(0);
        GitReportPreparation preparation = new GitReportPreparation(null, null) {
            @Override
            public void prepare(GitReportProperties ignored) {
                throw new AssertionError("preparation must not run for multi-author rerun");
            }
        };

        orchestrator(preparation).runAuthors(properties, List.of("author-001-alice", "author-002-bob"));

        assertThat(prompts).anySatisfy(prompt -> assertThat(prompt).contains("detail_json: " + aliceDetail));
        assertThat(prompts).anySatisfy(prompt -> assertThat(prompt).contains("detail_json: " + bobDetail));
        assertThat(prompts).noneSatisfy(prompt -> assertThat(prompt).contains("detail_json: " + carolDetail));
        assertThat(prompts.stream().filter(prompt -> prompt.contains("synthesis_inputs_json:")).count()).isEqualTo(1);
        assertThat(carolReport).hasContent("Carol 个人报告\n");
    }

    private GitReportOrchestrator orchestrator() {
        return orchestrator(new GitReportPreparation(
                new GitStatsCollector(new CommandExecutor(), new CommentLineCounter(), new WorkloadScoreCalculator(), objectMapper),
                new ReportPreparationWriter(objectMapper)
        ));
    }

    private GitReportOrchestrator orchestrator(GitReportPreparation preparation) {
        AgentBridgeClient client = new AgentBridgeClient(objectMapper);
        ScheduledProbeWaiter scheduledProbeWaiter = scheduledProbeWaiter();
        return new GitReportOrchestrator(
                preparation,
                objectMapper,
                new PromptBuilder(new DefaultResourceLoader()),
                new AgentBridgeTaskRunner(client, scheduledProbeWaiter, new WorkflowEventSink(), objectMapper),
                new AuthorOutputValidator(objectMapper),
                new FinalReportValidator(),
                new QualityScoresWriter(objectMapper, new QualityScoreCalculator(), new WorkloadScoreCalculator()),
                new SynthesisInputWriter(objectMapper),
                new RunStatusRepository(objectMapper),
                new OutputCompletionGate(objectMapper),
                new ConcurrentWorkflowTaskRunner(authorTaskExecutor()),
                new ArtifactCompletenessValidator()
        );
    }

    private ThreadPoolTaskScheduler taskScheduler() {
        if (taskScheduler != null) {
            return taskScheduler;
        }
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setThreadNamePrefix("test-agentbridge-task-poll-");
        taskScheduler.setPoolSize(2);
        taskScheduler.initialize();
        return taskScheduler;
    }

    private ScheduledProbeWaiter scheduledProbeWaiter() {
        return new ScheduledProbeWaiter(taskScheduler());
    }

    private ThreadPoolTaskExecutor authorTaskExecutor() {
        if (authorTaskExecutor != null) {
            return authorTaskExecutor;
        }
        authorTaskExecutor = new ThreadPoolTaskExecutor();
        authorTaskExecutor.setThreadNamePrefix("test-author-task-");
        authorTaskExecutor.setCorePoolSize(2);
        authorTaskExecutor.setMaxPoolSize(2);
        authorTaskExecutor.initialize();
        return authorTaskExecutor;
    }

    private void startFakeAgentBridgeServer() throws IOException {
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger runningPolls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/global/health", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/info", exchange -> respond(exchange, 200, "{\"running\":" + consumeRunningPoll(runningPolls) + "}"));
        server.createContext("/prompt", exchange -> {
            try {
                String prompt = promptFromBody(exchange);
                ids.incrementAndGet();
                runningPolls.set(1);
                prompts.add(prompt);
                writeFakeAgentBridgeOutput(prompt);
                respond(exchange, 200, "{}");
            } catch (Exception exception) {
                respond(exchange, 500, "{\"error\":\"" + exception.getClass().getName() + ": " + exception.getMessage().replace("\"", "'") + "\"}");
            }
        });
        server.createContext("/session", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "[]");
                return;
            }
            respond(exchange, 200, "{\"id\":\"task-" + ids.incrementAndGet() + "\"}");
        });
        server.createContext("/session/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/prompt_async")) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String prompt = objectMapper.readTree(body).at("/parts/0/text").asText();
                    prompts.add(prompt);
                    writeFakeAgentBridgeOutput(prompt);
                    respond(exchange, 204, "");
                    return;
                }
                if (path.matches("/session/[^/]+/message")) {
                    respond(exchange, 200, """
                            [
                                {"id":"msg_1","type":"user","text":"ok","time":{"created":1}},
                                {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test","id":"model"},"content":[],"finish":"stop","time":{"created":2,"completed":3}}
                            ]
                            """);
                    return;
                }
                respond(exchange, 404, "{}");
            } catch (Exception exception) {
                respond(exchange, 500, "{\"error\":\"" + exception.getClass().getName() + ": " + exception.getMessage().replace("\"", "'") + "\"}");
            }
        });
        server.start();
    }

    private AtomicInteger startFakeAgentBridgeServerWithoutOutputs() throws IOException {
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger runningPolls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/global/health", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/info", exchange -> respond(exchange, 200, "{\"running\":" + consumeRunningPoll(runningPolls) + "}"));
        server.createContext("/prompt", exchange -> {
            try {
                prompts.add(promptFromBody(exchange));
                ids.incrementAndGet();
                runningPolls.set(1);
                respond(exchange, 200, "{}");
            } catch (Exception exception) {
                respond(exchange, 500, "{\"error\":\"" + exception.getClass().getName() + ": " + exception.getMessage().replace("\"", "'") + "\"}");
            }
        });
        server.createContext("/session", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "[]");
                return;
            }
            respond(exchange, 200, "{\"id\":\"task-" + ids.incrementAndGet() + "\"}");
        });
        server.createContext("/session/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/prompt_async")) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    prompts.add(objectMapper.readTree(body).at("/parts/0/text").asText());
                    respond(exchange, 204, "");
                    return;
                }
                if (path.matches("/session/[^/]+/message")) {
                    respond(exchange, 200, """
                            [
                                {"id":"msg_1","type":"user","text":"ok","time":{"created":1}}
                            ]
                            """);
                    return;
                }
                respond(exchange, 404, "{}");
            } catch (Exception exception) {
                respond(exchange, 500, "{\"error\":\"" + exception.getClass().getName() + ": " + exception.getMessage().replace("\"", "'") + "\"}");
            }
        });
        server.start();
        return ids;
    }

    private AtomicInteger startFakeAgentBridgeServerWithDelayedOutputs(long delayMillis) throws IOException {
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger activeAgentBridgeTasks = new AtomicInteger();
        AtomicInteger maxActiveAgentBridgeTasks = new AtomicInteger();
        AtomicInteger runningPolls = new AtomicInteger();
        delayedOutputExecutor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/global/health", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/info", exchange -> respond(exchange, 200, "{\"running\":" + consumeRunningPoll(runningPolls) + "}"));
        server.createContext("/prompt", exchange -> {
            try {
                String prompt = promptFromBody(exchange);
                ids.incrementAndGet();
                runningPolls.set(1);
                prompts.add(prompt);
                int active = activeAgentBridgeTasks.incrementAndGet();
                maxActiveAgentBridgeTasks.accumulateAndGet(active, Math::max);
                delayedOutputExecutor.submit(() -> {
                    try {
                        Thread.sleep(delayMillis);
                        writeFakeAgentBridgeOutput(prompt);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    } finally {
                        activeAgentBridgeTasks.decrementAndGet();
                    }
                });
                respond(exchange, 200, "{}");
            } catch (Exception exception) {
                respond(exchange, 500, "{\"error\":\"" + exception.getClass().getName() + ": " + exception.getMessage().replace("\"", "'") + "\"}");
            }
        });
        server.createContext("/session", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "[]");
                return;
            }
            respond(exchange, 200, "{\"id\":\"task-" + ids.incrementAndGet() + "\"}");
        });
        server.createContext("/session/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/prompt_async")) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String prompt = objectMapper.readTree(body).at("/parts/0/text").asText();
                    prompts.add(prompt);
                    int active = activeAgentBridgeTasks.incrementAndGet();
                    maxActiveAgentBridgeTasks.accumulateAndGet(active, Math::max);
                    delayedOutputExecutor.submit(() -> {
                        try {
                            Thread.sleep(delayMillis);
                            writeFakeAgentBridgeOutput(prompt);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        } finally {
                            activeAgentBridgeTasks.decrementAndGet();
                        }
                    });
                    respond(exchange, 204, "");
                    return;
                }
                if (path.matches("/session/[^/]+/message")) {
                    respond(exchange, 200, """
                            [
                                {"id":"msg_1","type":"user","text":"ok","time":{"created":1}}
                            ]
                            """);
                    return;
                }
                respond(exchange, 404, "{}");
            } catch (Exception exception) {
                respond(exchange, 500, "{\"error\":\"" + exception.getClass().getName() + ": " + exception.getMessage().replace("\"", "'") + "\"}");
            }
        });
        server.start();
        return maxActiveAgentBridgeTasks;
    }

    private AtomicInteger startFakeAgentBridgeServerWithInvalidAuthorThenFreshRerun() throws IOException {
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger authorRuns = new AtomicInteger();
        AtomicInteger runningPolls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/global/health", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/info", exchange -> respond(exchange, 200, "{\"running\":" + consumeRunningPoll(runningPolls) + "}"));
        server.createContext("/prompt", exchange -> {
            try {
                String prompt = promptFromBody(exchange);
                ids.incrementAndGet();
                runningPolls.set(1);
                prompts.add(prompt);
                if (prompt.contains("detail_json:")) {
                    Path detailPath = Path.of(extractPath(prompt, "detail_json:"));
                    if (authorRuns.incrementAndGet() == 1) {
                        writeInvalidAuthorOutput(detailPath);
                    } else {
                        writeValidAuthorOutput(detailPath);
                    }
                } else if (prompt.contains("synthesis_inputs_json:")) {
                    writeFakeAgentBridgeOutput(prompt);
                }
                respond(exchange, 200, "{}");
            } catch (Exception exception) {
                respond(exchange, 500, "{\"error\":\"" + exception.getClass().getName() + ": " + exception.getMessage().replace("\"", "'") + "\"}");
            }
        });
        server.createContext("/session", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "[]");
                return;
            }
            respond(exchange, 200, "{\"id\":\"task-" + ids.incrementAndGet() + "\"}");
        });
        server.createContext("/session/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/prompt_async")) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String prompt = objectMapper.readTree(body).at("/parts/0/text").asText();
                    prompts.add(prompt);
                    if (prompt.contains("detail_json:")) {
                        Path detailPath = Path.of(extractPath(prompt, "detail_json:"));
                        if (authorRuns.incrementAndGet() == 1) {
                            writeInvalidAuthorOutput(detailPath);
                        } else {
                            writeValidAuthorOutput(detailPath);
                        }
                    } else if (prompt.contains("synthesis_inputs_json:")) {
                        writeFakeAgentBridgeOutput(prompt);
                    }
                    respond(exchange, 204, "");
                    return;
                }
                if (path.matches("/session/[^/]+/message")) {
                    respond(exchange, 200, """
                            [
                                {"id":"msg_1","type":"user","text":"ok","time":{"created":1}},
                                {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test","id":"model"},"content":[],"finish":"stop","time":{"created":2,"completed":3}}
                            ]
                            """);
                    return;
                }
                respond(exchange, 404, "{}");
            } catch (Exception exception) {
                respond(exchange, 500, "{\"error\":\"" + exception.getClass().getName() + ": " + exception.getMessage().replace("\"", "'") + "\"}");
            }
        });
        server.start();
        return ids;
    }

    private void writeFakeAgentBridgeOutput(String prompt) throws IOException {
        if (prompt.contains("detail_json:")) {
            Path detailPath = Path.of(extractPath(prompt, "detail_json:"));
            writeValidAuthorOutput(detailPath);
            return;
        }
        Path synthesisInputsPath = Path.of(extractPath(prompt, "synthesis_inputs_json:"));
        JsonNode synthesisInputs = objectMapper.readTree(synthesisInputsPath.toFile());
        Files.writeString(Path.of(synthesisInputs.path("final_report").asText()), validFinalReport());
    }

    private void writeInvalidAuthorOutput(Path detailPath) throws IOException {
        JsonNode detail = objectMapper.readTree(detailPath.toFile());
        Files.writeString(Path.of(detail.at("/output/person_report_md").asText()), "个人报告内容 {{UNFINISHED}}\n");
        objectMapper.writeValue(Path.of(detail.at("/output/quality_summary_json").asText()).toFile(), Map.of(
                "author", detail.path("author").asText(),
                "status", "completed",
                "findings", List.of(),
                "positive_signals", List.of(),
                "risk_signals", List.of(),
                "code_snippets", List.of(),
                "unverified", List.of(),
                "summary", "无"
        ));
    }

    private void writeValidAuthorOutput(Path detailPath) throws IOException {
        JsonNode detail = objectMapper.readTree(detailPath.toFile());
        Files.writeString(Path.of(detail.at("/output/person_report_md").asText()), "个人报告内容\n");
        objectMapper.writeValue(Path.of(detail.at("/output/quality_summary_json").asText()).toFile(), Map.of(
                "author", detail.path("author").asText(),
                "status", "completed",
                "findings", List.of(),
                "positive_signals", List.of(),
                "risk_signals", List.of(),
                "code_snippets", List.of(),
                "unverified", List.of(),
                "summary", "无"
        ));
    }

    private String validFinalReport() {
        return """
                # 代码提交量统计报告

                ## 1. 统计范围
                内容

                ## 2. 总体汇总
                内容

                ## 3. 人员工作量排名与分析
                | 最终排名 | 初始排名 | 开发人员 |
                | ---: | ---: | --- |
                | 1 | 1 | Alice |

                ## 4. 个人报告链接
                [person-report.md](reports/author-001-alice/person-report.md)

                ## 5. 未完成个人报告
                无

                ## 6. 统计口径
                内容

                ## 7. 风险与偏差
                内容

                ## 8. 典型低质量代码片段
                无
                """;
    }

    private Map<String, Object> pendingQuality(String author) {
        return Map.of(
                "author", author,
                "status", "pending",
                "findings", List.of(),
                "positive_signals", List.of(),
                "risk_signals", List.of(),
                "code_snippets", List.of(),
                "unverified", List.of(),
                "summary", "{{QUALITY_SUMMARY}}"
        );
    }

    private Map<String, Object> completedQuality(String author) {
        return Map.of(
                "author", author,
                "status", "completed",
                "findings", List.of(),
                "positive_signals", List.of(),
                "risk_signals", List.of(),
                "code_snippets", List.of(),
                "unverified", List.of(),
                "summary", "无"
        );
    }

    private void writeAuthorDetail(Path detailPath, String author, Path personReport, Path qualitySummary) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(detailPath.toFile(), Map.of(
                "author", author,
                "output", Map.of(
                        "person_report_md", personReport.toString(),
                        "quality_summary_json", qualitySummary.toString(),
                        "report_placeholders", List.of("{{WORKLOAD_STRUCTURE_ANALYSIS}}"),
                        "quality_summary_status_required", "completed"
                ),
                "execution_worklist", List.of()
        ));
    }

    private String extractPath(String prompt, String prefix) {
        List<String> matches = prompt.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).trim())
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException("path payload missing: " + prefix);
        }
        return matches.get(matches.size() - 1);
    }

    private String promptFromBody(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("text").asText();
    }

    private boolean consumeRunningPoll(AtomicInteger runningPolls) {
        return runningPolls.getAndUpdate(value -> Math.max(0, value - 1)) > 0;
    }

    private String agentbridgeWebBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
