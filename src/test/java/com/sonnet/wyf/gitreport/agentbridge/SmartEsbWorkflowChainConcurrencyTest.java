package com.sonnet.wyf.gitreport.agentbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeRunResult;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeTaskRunner;
import com.sonnet.wyf.gitreport.agentbridge.ValidatedAgentBridgeTaskSpec;
import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactContext;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.AgentBridgeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.orchestration.ArtifactCompletenessValidator;
import com.sonnet.wyf.gitreport.orchestration.ConcurrentWorkflowTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void runsTransactionReviewsWithRunnerAgentBridgeConcurrency() throws Exception {
        SmartEsbRewriteProperties properties = smartEsbProperties();
        writeTransactions(properties.getTransactionPlanDir());
        AgentBridgeSettings settings = new AgentBridgeSettings();
        settings.setConcurrency(2);
        settings.setMaxConcurrency(2);
        TrackingTaskRunner taskRunner = new TrackingTaskRunner();
        SmartEsbWorkflowChain chain = new SmartEsbWorkflowChain(
                new FixedChainConfigLoader(properties),
                new AgentBridgeRunnerProperties(),
                new SmartEsbDailyTransactionPlanLoader(),
                new SmartEsbReviewPreparation(objectMapper),
                new SmartEsbPromptBuilder(new DefaultResourceLoader()),
                new SmartEsbSummaryValidator(objectMapper),
                taskRunner,
                objectMapper,
                new OutputCompletionGate(objectMapper),
                new ConcurrentWorkflowTaskRunner(authorTaskExecutor(3)),
                new ArtifactCompletenessValidator()
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
        AgentBridgeSettings settings = new AgentBridgeSettings();
        settings.setConcurrency(2);
        settings.setMaxConcurrency(2);
        TrackingTaskRunner taskRunner = new TrackingTaskRunner();
        SmartEsbWorkflowChain chain = new SmartEsbWorkflowChain(
                new FixedChainConfigLoader(properties),
                new AgentBridgeRunnerProperties(),
                new SmartEsbDailyTransactionPlanLoader(),
                new SmartEsbReviewPreparation(objectMapper),
                new SmartEsbPromptBuilder(new DefaultResourceLoader()),
                new SmartEsbSummaryValidator(objectMapper),
                taskRunner,
                objectMapper,
                new OutputCompletionGate(objectMapper),
                new ConcurrentWorkflowTaskRunner(authorTaskExecutor(3)),
                new ArtifactCompletenessValidator()
        );

        chain.run(new WorkflowRunRequest("rerun", "transaction", "\"Alpha\", \"Beta\"", LocalDate.of(2026, 6, 17), settings));

        assertThat(taskRunner.prompts).filteredOn(prompt -> prompt.contains("task_json_path:")).hasSize(2);
        assertThat(taskRunner.prompts).anySatisfy(prompt -> assertThat(prompt)
                .contains("正在重跑一个失败或未完成的交易审查")
                .contains("/runs/run-")
                .contains("/bundle/tasks/transaction-Alpha.json")
                .contains("/bundle/reports/transactions/Alpha/summary.json"));
        assertThat(taskRunner.prompts).anySatisfy(prompt -> assertThat(prompt)
                .contains("正在重跑一个失败或未完成的交易审查")
                .contains("/bundle/tasks/transaction-Beta.json")
                .contains("/bundle/reports/transactions/Beta/summary.json"));
        assertThat(taskRunner.prompts).noneSatisfy(prompt -> assertThat(prompt).contains("/bundle/tasks/transaction-Gamma.json"));
    }

    @Test
    void runsModulesWithModulePrompt() throws Exception {
        SmartEsbRewriteProperties properties = smartEsbProperties();
        writeTransactionsAndModules(properties.getTransactionPlanDir());
        AgentBridgeSettings settings = new AgentBridgeSettings();
        settings.setConcurrency(2);
        settings.setMaxConcurrency(2);
        TrackingTaskRunner taskRunner = new TrackingTaskRunner();
        SmartEsbWorkflowChain chain = new SmartEsbWorkflowChain(
                new FixedChainConfigLoader(properties),
                new AgentBridgeRunnerProperties(),
                new SmartEsbDailyTransactionPlanLoader(),
                new SmartEsbReviewPreparation(objectMapper),
                new SmartEsbPromptBuilder(new DefaultResourceLoader()),
                new SmartEsbSummaryValidator(objectMapper),
                taskRunner,
                objectMapper,
                new OutputCompletionGate(objectMapper),
                new ConcurrentWorkflowTaskRunner(authorTaskExecutor(3)),
                new ArtifactCompletenessValidator()
        );

        chain.run(new WorkflowRunRequest("full", null, null, LocalDate.of(2026, 6, 24), settings));

        assertThat(taskRunner.prompts).filteredOn(prompt -> prompt.contains("task_json_path:")).hasSize(2);
        assertThat(taskRunner.prompts).anySatisfy(prompt -> assertThat(prompt)
                .contains("SmartESB 重构代码审查模块 session")
                .contains("review_type` 必须是 `module`")
                .contains("/bundle/tasks/module-BaseChnConvReqMsgSop.json")
                .contains("模块审查不要求交易名、映射文档、old-8583-doc"));
        assertThat(taskRunner.prompts).anySatisfy(prompt -> assertThat(prompt)
                .contains("SmartESB 重构代码审查交易 session")
                .contains("/bundle/tasks/transaction-CaReturnOfGoods.json"));
    }

    @Test
    void rerunsIncompleteReviewOutputsBeforeIndexWithoutSameSessionCorrections() throws Exception {
        SmartEsbRewriteProperties properties = smartEsbProperties();
        writeTransactions(properties.getTransactionPlanDir());
        AgentBridgeSettings settings = new AgentBridgeSettings();
        settings.setConcurrency(2);
        settings.setMaxConcurrency(2);
        settings.setValidationMaxCorrections(3);
        TrackingTaskRunner taskRunner = new TrackingTaskRunner();
        taskRunner.completeAfterAttempt("smartesb-review-Beta", 2);
        SmartEsbWorkflowChain chain = new SmartEsbWorkflowChain(
                new FixedChainConfigLoader(properties),
                new AgentBridgeRunnerProperties(),
                new SmartEsbDailyTransactionPlanLoader(),
                new SmartEsbReviewPreparation(objectMapper),
                new SmartEsbPromptBuilder(new DefaultResourceLoader()),
                new SmartEsbSummaryValidator(objectMapper),
                taskRunner,
                objectMapper,
                new OutputCompletionGate(objectMapper),
                new ConcurrentWorkflowTaskRunner(authorTaskExecutor(3)),
                new ArtifactCompletenessValidator()
        );

        chain.run(new WorkflowRunRequest("full", null, null, LocalDate.of(2026, 6, 17), settings));

        assertThat(taskRunner.titles).filteredOn("smartesb-review-Beta"::equals).hasSize(2);
        assertThat(taskRunner.prompts).anySatisfy(prompt -> assertThat(prompt)
                .contains("正在重跑一个失败或未完成的交易审查")
                .contains("/bundle/tasks/transaction-Beta.json"));
        assertThat(taskRunner.validationMaxCorrectionsByTitle)
                .containsEntry("smartesb-review-Alpha", 0)
                .containsEntry("smartesb-review-Beta", 0)
                .containsEntry("smartesb-review-Gamma", 0)
                .containsEntry("smartesb-review-index", 3);
        assertThat(Files.readString(findDiagnostic(properties.getLocalOut().resolve("2026-06-17"), "incomplete-reports.json")))
                .contains("\"state\" : \"completed\"")
                .contains("\"rerunRounds\" : 1");
    }

    @Test
    void stopsAfterFiveIncompleteRerunRoundsAndRecordsUnfinishedReports() throws Exception {
        SmartEsbRewriteProperties properties = smartEsbProperties();
        writeSingleTransaction(properties.getTransactionPlanDir());
        AgentBridgeSettings settings = new AgentBridgeSettings();
        settings.setConcurrency(2);
        settings.setMaxConcurrency(2);
        TrackingTaskRunner taskRunner = new TrackingTaskRunner();
        taskRunner.neverComplete("smartesb-review-Alpha");
        SmartEsbWorkflowChain chain = new SmartEsbWorkflowChain(
                new FixedChainConfigLoader(properties),
                new AgentBridgeRunnerProperties(),
                new SmartEsbDailyTransactionPlanLoader(),
                new SmartEsbReviewPreparation(objectMapper),
                new SmartEsbPromptBuilder(new DefaultResourceLoader()),
                new SmartEsbSummaryValidator(objectMapper),
                taskRunner,
                objectMapper,
                new OutputCompletionGate(objectMapper),
                new ConcurrentWorkflowTaskRunner(authorTaskExecutor(3)),
                new ArtifactCompletenessValidator()
        );

        assertThatThrownBy(() -> chain.run(new WorkflowRunRequest("full", null, null, LocalDate.of(2026, 6, 17), settings)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SmartESB review outputs incomplete after 5 rerun rounds")
                .hasMessageContaining("Alpha");

        assertThat(taskRunner.titles).filteredOn("smartesb-review-Alpha"::equals).hasSize(6);
        assertThat(taskRunner.titles).doesNotContain("smartesb-review-index");
        assertThat(Files.readString(findDiagnostic(properties.getLocalOut().resolve("2026-06-17"), "incomplete-reports.json")))
                .contains("\"state\" : \"failed\"")
                .contains("\"rerunRounds\" : 5")
                .contains("\"name\" : \"Alpha\"")
                .contains("summary missing");
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

    private void writeSingleTransaction(Path planDir) throws IOException {
        Path day = planDir.resolve("2026-06-17");
        Files.createDirectories(day);
        Files.writeString(day.resolve("transactions.yml"), """
                date: 2026-06-17
                transactions:
                  - name: Alpha
                    description: first
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

    private class TrackingTaskRunner extends AgentBridgeTaskRunner {
        private final AtomicInteger activeTransactions = new AtomicInteger();
        private final AtomicInteger maxActiveTransactions = new AtomicInteger();
        private final CopyOnWriteArrayList<String> prompts = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> titles = new CopyOnWriteArrayList<>();
        private final Map<String, Integer> validationMaxCorrectionsByTitle = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> attemptsByTitle = new ConcurrentHashMap<>();
        private final Map<String, Integer> completeAfterAttemptByTitle = new ConcurrentHashMap<>();
        private final java.util.Set<String> neverCompleteTitles = ConcurrentHashMap.newKeySet();

        TrackingTaskRunner() {
            super(new AgentBridgeClient(objectMapper), scheduledProbeWaiter());
        }

        @Override
        public AgentBridgeRunResult runUntilValidated(ValidatedAgentBridgeTaskSpec spec) throws Exception {
            prompts.add(Files.readString(spec.promptFile()));
            titles.add(spec.title());
            validationMaxCorrectionsByTitle.put(spec.title(), spec.validationMaxCorrections());
            return runTrackedTask(spec);
        }

        void completeAfterAttempt(String title, int attempt) {
            completeAfterAttemptByTitle.put(title, attempt);
        }

        void neverComplete(String title) {
            neverCompleteTitles.add(title);
        }

        private AgentBridgeRunResult runTrackedTask(ValidatedAgentBridgeTaskSpec spec) throws Exception {
            String title = spec.title();
            Path promptFile = spec.promptFile();
            Path runDir = spec.runDir();
            if ("smartesb-review-index".equals(title)) {
                writeIndexOutputs();
                return new AgentBridgeRunResult("index-task", spec.webBaseUrl().toString(), false, true, "idle", true, "", 0);
            }
            int attempt = attemptsByTitle.computeIfAbsent(title, ignored -> new AtomicInteger()).incrementAndGet();
            int active = activeTransactions.incrementAndGet();
            maxActiveTransactions.accumulateAndGet(active, Math::max);
            try {
                Thread.sleep(150);
                if (!neverCompleteTitles.contains(title)
                        && attempt >= completeAfterAttemptByTitle.getOrDefault(title, 1)) {
                    writeReviewOutputs(promptFile, title);
                }
                return new AgentBridgeRunResult("task-" + runDir.getFileName(), spec.webBaseUrl().toString(), false, true, "idle", true, "", 0);
            } finally {
                activeTransactions.decrementAndGet();
            }
        }

        private void writeReviewOutputs(Path promptFile, String title) throws IOException {
            String prompt = Files.readString(promptFile);
            String name = title.substring("smartesb-review-".length());
            boolean module = prompt.contains("tasks/module-");
            Path out = WorkflowArtifactContext.current().bundleRoot();
            Path reportDir = out.resolve("reports").resolve(module ? "modules" : "transactions").resolve(name);
            Files.createDirectories(reportDir);
            writeCompletedReportArtifacts(reportDir);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put(module ? "module" : "transaction", name);
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

        private void writeIndexOutputs() throws IOException {
            Path out = WorkflowArtifactContext.current().bundleRoot();
            Files.writeString(out.resolve("index.md"), "# index\n");
            Files.writeString(out.resolve("summary.md"), "# summary\n");
        }
    }

    private void writeCompletedSummary(Path out, String transaction) throws IOException {
        Path reportDir = out.resolve("reports").resolve("transactions").resolve(transaction);
        Files.createDirectories(reportDir);
        writeCompletedReportArtifacts(reportDir);
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

    private Path findDiagnostic(Path root, String filename) throws IOException {
        try (var paths = Files.walk(root.resolve("runs"))) {
            return paths.filter(path -> path.getFileName().toString().equals(filename)).findFirst().orElseThrow();
        }
    }

    private void writeCompletedReportArtifacts(Path reportDir) throws IOException {
        Files.writeString(reportDir.resolve("review.md"), "# review\n完成\n");
        Files.writeString(reportDir.resolve("mapping-matrix.md"), "# matrix\n完成\n");
        Path sections = reportDir.resolve("sections");
        Files.createDirectories(sections);
        Files.writeString(sections.resolve("01-findings.md"), "# findings\n完成\n");
        Files.writeString(sections.resolve("02-code-chains.md"), "# code chains\n完成\n");
        Files.writeString(sections.resolve("03-protocol-review.md"), "# protocol\n完成\n");
        Files.writeString(sections.resolve("04-behavior-review.md"), "# behavior\n完成\n");
        Files.writeString(sections.resolve("05-verification.md"), "# verification\n完成\n");
        Files.writeString(sections.resolve("06-code-standard.md"), "# code standard\n完成\n");
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
