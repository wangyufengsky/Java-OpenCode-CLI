package com.sonnet.wyf.gitreport.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.OpenCodeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbDailyTransactionPlanLoader;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbPromptBuilder;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbRewriteProperties;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbReviewPreparation;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbSummaryValidator;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbWorkflowChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SmartEsbWorkflowChainConcurrencyTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private ThreadPoolTaskScheduler scheduler;
    private ThreadPoolTaskExecutor executor;

    @TempDir
    Path tempDir;

    @AfterEach
    void shutdownExecutors() {
        if (executor != null) {
            executor.shutdown();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void runsTransactionReviewsWithRunnerOpenCodeConcurrency() throws Exception {
        SmartEsbRewriteProperties properties = smartEsbProperties();
        writeTransactions(properties.getTransactionPlanDir());
        OpenCodeSettings settings = new OpenCodeSettings();
        settings.setConcurrency(2);
        settings.setMaxConcurrency(2);
        TrackingTaskRunner taskRunner = new TrackingTaskRunner();
        SmartEsbWorkflowChain chain = new SmartEsbWorkflowChain(
                new FixedChainConfigLoader(properties),
                new OpenCodeRunnerProperties(),
                new SmartEsbDailyTransactionPlanLoader(),
                new SmartEsbReviewPreparation(objectMapper),
                new SmartEsbPromptBuilder(new DefaultResourceLoader()),
                new SmartEsbSummaryValidator(objectMapper),
                new HealthyServerManager(),
                taskRunner,
                objectMapper,
                authorTaskExecutor(3)
        );

        chain.run(new WorkflowRunRequest("full", null, null, LocalDate.of(2026, 6, 17), settings));

        assertThat(taskRunner.maxActiveTransactions.get()).isEqualTo(2);
    }

    @Test
    void rerunsMultipleTransactionsWithRerunPrompt() throws Exception {
        SmartEsbRewriteProperties properties = smartEsbProperties();
        writeTransactions(properties.getTransactionPlanDir());
        Path out = new SmartEsbReviewPreparation(objectMapper).prepare(
                properties,
                new SmartEsbDailyTransactionPlanLoader().load(properties.getTransactionPlanDir(), LocalDate.of(2026, 6, 17)),
                true
        );
        writeCompletedSummary(out, "Gamma");
        Path logicalOut = Path.of(properties.getOut()).resolve("2026-06-17");
        OpenCodeSettings settings = new OpenCodeSettings();
        settings.setConcurrency(2);
        settings.setMaxConcurrency(2);
        TrackingTaskRunner taskRunner = new TrackingTaskRunner();
        SmartEsbWorkflowChain chain = new SmartEsbWorkflowChain(
                new FixedChainConfigLoader(properties),
                new OpenCodeRunnerProperties(),
                new SmartEsbDailyTransactionPlanLoader(),
                new SmartEsbReviewPreparation(objectMapper),
                new SmartEsbPromptBuilder(new DefaultResourceLoader()),
                new SmartEsbSummaryValidator(objectMapper),
                new HealthyServerManager(),
                taskRunner,
                objectMapper,
                authorTaskExecutor(3)
        );

        chain.run(new WorkflowRunRequest("rerun", "transaction", "\"Alpha\", \"Beta\"", LocalDate.of(2026, 6, 17), settings));

        assertThat(taskRunner.prompts).filteredOn(prompt -> prompt.contains("task_json_path:")).hasSize(2);
        assertThat(taskRunner.prompts).anySatisfy(prompt -> assertThat(prompt)
                .contains("正在重跑一个失败或未完成的交易审查")
                .contains("task_json_path: " + logicalOut.resolve("tasks/transaction-Alpha.json"))
                .contains("previous_output: " + logicalOut.resolve("reports/Alpha/summary.json")));
        assertThat(taskRunner.prompts).anySatisfy(prompt -> assertThat(prompt)
                .contains("正在重跑一个失败或未完成的交易审查")
                .contains("task_json_path: " + logicalOut.resolve("tasks/transaction-Beta.json"))
                .contains("previous_output: " + logicalOut.resolve("reports/Beta/summary.json")));
        assertThat(taskRunner.prompts).noneSatisfy(prompt -> assertThat(prompt).contains("task_json_path: " + logicalOut.resolve("tasks/transaction-Gamma.json")));
    }

    @Test
    void runsModulesWithModulePrompt() throws Exception {
        SmartEsbRewriteProperties properties = smartEsbProperties();
        writeTransactionsAndModules(properties.getTransactionPlanDir());
        Path logicalOut = Path.of(properties.getOut()).resolve("2026-06-24");
        OpenCodeSettings settings = new OpenCodeSettings();
        settings.setConcurrency(2);
        settings.setMaxConcurrency(2);
        TrackingTaskRunner taskRunner = new TrackingTaskRunner();
        SmartEsbWorkflowChain chain = new SmartEsbWorkflowChain(
                new FixedChainConfigLoader(properties),
                new OpenCodeRunnerProperties(),
                new SmartEsbDailyTransactionPlanLoader(),
                new SmartEsbReviewPreparation(objectMapper),
                new SmartEsbPromptBuilder(new DefaultResourceLoader()),
                new SmartEsbSummaryValidator(objectMapper),
                new HealthyServerManager(),
                taskRunner,
                objectMapper,
                authorTaskExecutor(3)
        );

        chain.run(new WorkflowRunRequest("full", null, null, LocalDate.of(2026, 6, 24), settings));

        assertThat(taskRunner.prompts).filteredOn(prompt -> prompt.contains("task_json_path:")).hasSize(2);
        assertThat(taskRunner.prompts).anySatisfy(prompt -> assertThat(prompt)
                .contains("SmartESB 重构代码审查模块 session")
                .contains("review_type` 必须是 `module`")
                .contains("task_json_path: " + logicalOut.resolve("tasks/module-BaseChnConvReqMsgSop.json"))
                .contains("模块审查不要求交易名、映射文档、old-8583-doc"));
        assertThat(taskRunner.prompts).anySatisfy(prompt -> assertThat(prompt)
                .contains("SmartESB 重构代码审查交易 session")
                .contains("task_json_path: " + logicalOut.resolve("tasks/transaction-CaReturnOfGoods.json")));
    }

    private SmartEsbRewriteProperties smartEsbProperties() {
        SmartEsbRewriteProperties properties = new SmartEsbRewriteProperties();
        properties.setOut(tempDir.resolve("logical-out").toString());
        properties.setLocalOut(tempDir.resolve("local-out"));
        properties.setTransactionPlanDir(tempDir.resolve("plans"));
        properties.setNewProject(tempDir.resolve("new-project").toString());
        properties.setDocRoot(tempDir.resolve("docs").toString());
        return properties;
    }

    private void writeTransactions(Path planDir) throws IOException {
        Path day = planDir.resolve("2026-06-17");
        Files.createDirectories(day);
        Files.writeString(day.resolve("transactions.yml"), """
                date: 2026-06-17
                transactions:
                  - name: Alpha
                    description: first
                  - name: Beta
                    description: second
                  - name: Gamma
                    description: third
                """);
    }

    private void writeTransactionsAndModules(Path planDir) throws IOException {
        Path day = planDir.resolve("2026-06-24");
        Files.createDirectories(day);
        Files.writeString(day.resolve("transactions.yml"), """
                date: 2026-06-24
                transactions:
                  - name: CaReturnOfGoods
                modules:
                  - name: BaseChnConvReqMsgSop
                """);
    }

    private ScheduledProbeWaiter scheduledProbeWaiter() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("test-smartesb-poll-");
        scheduler.setPoolSize(2);
        scheduler.initialize();
        return new ScheduledProbeWaiter(scheduler);
    }

    private ThreadPoolTaskExecutor authorTaskExecutor(int concurrency) {
        executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("test-smartesb-transaction-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.initialize();
        return executor;
    }

    private class TrackingTaskRunner extends OpenCodeServerTaskRunner {
        private final AtomicInteger activeTransactions = new AtomicInteger();
        private final AtomicInteger maxActiveTransactions = new AtomicInteger();
        private final CopyOnWriteArrayList<String> prompts = new CopyOnWriteArrayList<>();

        TrackingTaskRunner() {
            super(new OpenCodeServerClient(objectMapper), scheduledProbeWaiter());
        }

        @Override
        public OpenCodeRunResult runUntil(
                OpenCodeServerHandle server,
                Path repo,
                String title,
                Path promptFile,
                String message,
                Path runDir,
                CompletionProbe completionProbe,
                String sessionModel,
                int createSessionTimeoutSeconds,
                int requestTimeoutSeconds,
                int pollMillis,
                int timeoutMinutes
        ) throws Exception {
            prompts.add(Files.readString(promptFile));
            return runTrackedTask(server, runDir);
        }

        @Override
        public OpenCodeRunResult runUntilValidated(
                OpenCodeServerHandle server,
                Path repo,
                String title,
                Path promptFile,
                String message,
                Path runDir,
                ValidationProbe validationProbe,
                String sessionModel,
                int createSessionTimeoutSeconds,
                int requestTimeoutSeconds,
                int pollMillis,
                int timeoutMinutes,
                int validationSettleSeconds,
                int validationMaxCorrections
        ) throws Exception {
            prompts.add(Files.readString(promptFile));
            return runTrackedTask(server, runDir);
        }

        private OpenCodeRunResult runTrackedTask(OpenCodeServerHandle server, Path runDir) throws Exception {
            if ("index".equals(runDir.getFileName().toString())) {
                writeIndexOutputs(runDir);
                return new OpenCodeRunResult("index-session", server.serverUrl().toString(), false, false, true, false, "idle");
            }
            int active = activeTransactions.incrementAndGet();
            maxActiveTransactions.accumulateAndGet(active, Math::max);
            try {
                Thread.sleep(150);
                writeTransactionSummary(runDir);
                return new OpenCodeRunResult("session-" + runDir.getFileName(), server.serverUrl().toString(), false, false, true, false, "idle");
            } finally {
                activeTransactions.decrementAndGet();
            }
        }

        private void writeTransactionSummary(Path runDir) throws IOException {
            String transaction = runDir.getFileName().toString();
            Path out = runDir.getParent().getParent();
            Path reportDir = out.resolve("reports").resolve(transaction);
            Files.createDirectories(reportDir);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("transaction", transaction);
            summary.put("description", "done");
            summary.put("status", "completed");
            summary.put("review_md", reportDir.resolve("review.md").toString());
            summary.put("matrix_md", reportDir.resolve("mapping-matrix.md").toString());
            summary.put("section_files", java.util.List.of());
            summary.put("code_standard_findings", java.util.List.of());
            summary.put("new_code_paths", java.util.List.of());
            summary.put("old_code_paths", java.util.List.of());
            summary.put("documents_checked", java.util.List.of());
            summary.put("finding_counts", Map.of("P0", 0, "P1", 0, "P2", 0, "P3", 0));
            summary.put("top_findings", java.util.List.of());
            summary.put("unverified", java.util.List.of());
            objectMapper.writeValue(reportDir.resolve("summary.json").toFile(), summary);
        }

        private void writeIndexOutputs(Path runDir) throws IOException {
            Path out = runDir.getParent().getParent();
            Files.writeString(out.resolve("index.md"), "# index\n");
            Files.writeString(out.resolve("summary.md"), "# summary\n");
        }
    }

    private void writeCompletedSummary(Path out, String transaction) throws IOException {
        Path reportDir = out.resolve("reports").resolve(transaction);
        Files.createDirectories(reportDir);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("transaction", transaction);
        summary.put("description", "done");
        summary.put("status", "completed");
        summary.put("review_md", reportDir.resolve("review.md").toString());
        summary.put("matrix_md", reportDir.resolve("mapping-matrix.md").toString());
        summary.put("section_files", List.of());
        summary.put("code_standard_findings", List.of());
        summary.put("new_code_paths", List.of());
        summary.put("old_code_paths", List.of());
        summary.put("documents_checked", List.of());
        summary.put("finding_counts", Map.of("P0", 0, "P1", 0, "P2", 0, "P3", 0));
        summary.put("top_findings", List.of());
        summary.put("unverified", List.of());
        objectMapper.writeValue(reportDir.resolve("summary.json").toFile(), summary);
    }

    private class HealthyServerManager extends OpenCodeServerManager {
        HealthyServerManager() {
            super(new OpenCodeServerClient(objectMapper), scheduledProbeWaiter());
        }

        @Override
        public synchronized OpenCodeServerHandle ensureReady(OpenCodeSettings settings, Path out) {
            return new OpenCodeServerHandle(URI.create(settings.getServerUrl()), false);
        }
    }

    private static class FixedChainConfigLoader extends ChainConfigLoader {
        private final SmartEsbRewriteProperties properties;

        FixedChainConfigLoader(SmartEsbRewriteProperties properties) {
            super(new DefaultResourceLoader());
            this.properties = properties;
        }

        @Override
        public <T> T load(String configDir, String chainId, Class<T> type) {
            return type.cast(properties);
        }
    }
}
