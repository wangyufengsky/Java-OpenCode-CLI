package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerClient;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.GitReportOrchestrator;
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
import java.util.concurrent.atomic.AtomicReference;

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
    void orchestratesPreparationAuthorWorkerQualityScoresAndSynthesisWithFakeOpenCodeServer() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path out = tempDir.resolve("out");
        Files.createDirectories(repo);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("Demo.java"), "class Demo {\n  int a = 1;\n}\n");
        GitTestSupport.run(repo, "git", "add", "Demo.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "add demo");
        startFakeOpenCodeServer();

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getOpencode().setServerUrl(serverUrl());
        properties.getOpencode().setManageServer(false);
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));

        orchestrator().run(properties);

        assertThat(out.resolve("quality-scores.json")).exists();
        assertThat(Files.readString(out.resolve("code-contribution-report.md"))).contains("# 代码提交量统计报告");
        JsonNode qualityScores = objectMapper.readTree(out.resolve("quality-scores.json").toFile());
        assertThat(qualityScores.get("rankings")).hasSize(1);
        JsonNode authorStatus = objectMapper.readTree(out.resolve("runs/author-001-alice-alice-example-com/status.json").toFile());
        assertThat(authorStatus.path("sessionId").asText()).isNotBlank();
        assertThat(authorStatus.path("serverUrl").asText()).isEqualTo(serverUrl());
        assertThat(authorStatus.path("serverOwnedByJava").asBoolean()).isFalse();
        JsonNode synthesisStatus = objectMapper.readTree(out.resolve("runs/synthesis/status.json").toFile());
        assertThat(synthesisStatus.path("sessionId").asText()).isNotBlank();
        assertThat(prompts).anySatisfy(prompt -> assertThat(prompt).contains("detail_json:"));
        assertThat(prompts).anySatisfy(prompt -> assertThat(prompt).contains("synthesis_inputs_json:"));
    }

    @Test
    void doesNotCreateAnotherAuthorSessionAfterPromptWasSubmitted() throws Exception {
        Path repo = tempDir.resolve("repo-no-auto-retry-after-submit");
        Path out = tempDir.resolve("out-no-auto-retry-after-submit");
        Files.createDirectories(repo);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("Demo.java"), "class Demo {\n  int a = 1;\n}\n");
        GitTestSupport.run(repo, "git", "add", "Demo.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "add demo");

        AtomicInteger sessions = startFakeOpenCodeServerWithoutOutputs();

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getOpencode().setServerUrl(serverUrl());
        properties.getOpencode().setManageServer(false);
        properties.getOpencode().setMaxRetries(2);
        properties.getOpencode().setTimeoutMinutes(0);
        properties.getOpencode().setOutputWaitSeconds(0);
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));

        assertThatThrownBy(() -> orchestrator().run(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("author task failed");

        assertThat(sessions).hasValue(1);
        assertThat(prompts).hasSize(1);
    }

    @Test
    void correctsInvalidAuthorOutputInSameSessionWithoutOuterRetry() throws Exception {
        Path repo = tempDir.resolve("repo-same-session-correction");
        Path out = tempDir.resolve("out-same-session-correction");
        Files.createDirectories(repo);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("Demo.java"), "class Demo {\n  int a = 1;\n}\n");
        GitTestSupport.run(repo, "git", "add", "Demo.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "add demo");

        AtomicInteger sessions = startFakeOpenCodeServerWithInvalidAuthorThenCorrection();

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getOpencode().setServerUrl(serverUrl());
        properties.getOpencode().setManageServer(false);
        properties.getOpencode().setMaxRetries(2);
        properties.getOpencode().setOutputWaitSeconds(0);
        properties.getOpencode().setValidationMaxCorrections(2);
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));

        orchestrator().run(properties);

        assertThat(sessions).hasValue(2);
        assertThat(prompts).filteredOn(prompt -> prompt.contains("detail_json:")).hasSize(1);
        assertThat(prompts).filteredOn(prompt -> prompt.contains("Java 产物校验失败")).hasSize(1);
        JsonNode authorStatus = objectMapper.readTree(out.resolve("runs/author-001-alice-alice-example-com/status.json").toFile());
        assertThat(authorStatus.path("attempt").asInt()).isEqualTo(1);
        assertThat(authorStatus.path("sessionId").asText()).isEqualTo("session-1");
    }

    @Test
    void authorConcurrencyLimitsActiveOpenCodeSessionsUntilOutputsComplete() throws Exception {
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

        AtomicInteger maxActiveOpenCodeTasks = startFakeOpenCodeServerWithDelayedOutputs(250);

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getOpencode().setServerUrl(serverUrl());
        properties.getOpencode().setManageServer(false);
        properties.getOpencode().setConcurrency(1);
        properties.getOpencode().setMaxConcurrency(1);
        properties.getOpencode().setTimeoutMinutes(1);
        properties.getOpencode().setOutputWaitSeconds(2);
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));

        orchestrator().run(properties);

        assertThat(prompts).filteredOn(prompt -> prompt.contains("detail_json:")).hasSize(2);
        assertThat(maxActiveOpenCodeTasks).hasValue(1);
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
        startFakeOpenCodeServer();

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getOpencode().setServerUrl(serverUrl());
        properties.getOpencode().setManageServer(false);
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
        startFakeOpenCodeServer();

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getOpencode().setServerUrl(serverUrl());
        properties.getOpencode().setManageServer(false);
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

    private GitReportOrchestrator orchestrator() {
        return orchestrator(new GitReportPreparation(
                new GitStatsCollector(new CommandExecutor(), new CommentLineCounter(), new WorkloadScoreCalculator(), objectMapper),
                new ReportPreparationWriter(objectMapper)
        ));
    }

    private GitReportOrchestrator orchestrator(GitReportPreparation preparation) {
        OpenCodeServerClient client = new OpenCodeServerClient(objectMapper);
        ScheduledProbeWaiter scheduledProbeWaiter = scheduledProbeWaiter();
        return new GitReportOrchestrator(
                preparation,
                objectMapper,
                new PromptBuilder(new DefaultResourceLoader()),
                new OpenCodeServerManager(client, scheduledProbeWaiter),
                new OpenCodeServerTaskRunner(client, scheduledProbeWaiter),
                new AuthorOutputValidator(objectMapper),
                new FinalReportValidator(),
                new QualityScoresWriter(objectMapper, new QualityScoreCalculator(), new WorkloadScoreCalculator()),
                new SynthesisInputWriter(objectMapper),
                new RunStatusRepository(objectMapper),
                authorTaskExecutor()
        );
    }

    private ThreadPoolTaskScheduler taskScheduler() {
        if (taskScheduler != null) {
            return taskScheduler;
        }
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setThreadNamePrefix("test-opencode-session-poll-");
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

    private void startFakeOpenCodeServer() throws IOException {
        AtomicInteger ids = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/global/health", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/session", exchange -> respond(exchange, 200, "{\"id\":\"session-" + ids.incrementAndGet() + "\"}"));
        server.createContext("/session/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/prompt_async")) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String prompt = objectMapper.readTree(body).at("/parts/0/text").asText();
                    prompts.add(prompt);
                    writeFakeOpenCodeOutput(prompt);
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

    private AtomicInteger startFakeOpenCodeServerWithoutOutputs() throws IOException {
        AtomicInteger ids = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/global/health", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/session", exchange -> respond(exchange, 200, "{\"id\":\"session-" + ids.incrementAndGet() + "\"}"));
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

    private AtomicInteger startFakeOpenCodeServerWithDelayedOutputs(long delayMillis) throws IOException {
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger activeOpenCodeTasks = new AtomicInteger();
        AtomicInteger maxActiveOpenCodeTasks = new AtomicInteger();
        delayedOutputExecutor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/global/health", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/session", exchange -> respond(exchange, 200, "{\"id\":\"session-" + ids.incrementAndGet() + "\"}"));
        server.createContext("/session/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/prompt_async")) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String prompt = objectMapper.readTree(body).at("/parts/0/text").asText();
                    prompts.add(prompt);
                    int active = activeOpenCodeTasks.incrementAndGet();
                    maxActiveOpenCodeTasks.accumulateAndGet(active, Math::max);
                    delayedOutputExecutor.submit(() -> {
                        try {
                            Thread.sleep(delayMillis);
                            writeFakeOpenCodeOutput(prompt);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        } finally {
                            activeOpenCodeTasks.decrementAndGet();
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
        return maxActiveOpenCodeTasks;
    }

    private AtomicInteger startFakeOpenCodeServerWithInvalidAuthorThenCorrection() throws IOException {
        AtomicInteger ids = new AtomicInteger();
        AtomicReference<Path> authorDetail = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/global/health", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/session", exchange -> respond(exchange, 200, "{\"id\":\"session-" + ids.incrementAndGet() + "\"}"));
        server.createContext("/session/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/prompt_async")) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String prompt = objectMapper.readTree(body).at("/parts/0/text").asText();
                    prompts.add(prompt);
                    if (prompt.contains("detail_json:")) {
                        Path detailPath = Path.of(extractPath(prompt, "detail_json:"));
                        authorDetail.set(detailPath);
                        writeInvalidAuthorOutput(detailPath);
                    } else if (prompt.contains("Java 产物校验失败") && authorDetail.get() != null) {
                        writeValidAuthorOutput(authorDetail.get());
                    } else if (prompt.contains("synthesis_inputs_json:")) {
                        writeFakeOpenCodeOutput(prompt);
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

    private void writeFakeOpenCodeOutput(String prompt) throws IOException {
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

    private String serverUrl() {
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
