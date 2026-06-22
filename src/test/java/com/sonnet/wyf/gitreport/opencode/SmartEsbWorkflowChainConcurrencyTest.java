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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
