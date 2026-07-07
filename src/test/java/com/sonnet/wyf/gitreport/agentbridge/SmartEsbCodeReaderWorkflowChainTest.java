package com.sonnet.wyf.gitreport.agentbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeRunResult;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeTaskRunner;
import com.sonnet.wyf.gitreport.agentbridge.ValidatedAgentBridgeTaskSpec;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.orchestration.ConcurrentWorkflowTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.AgentBridgeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.workflow.smartesbreader.SmartEsbCodeReaderOutputValidator;
import com.sonnet.wyf.gitreport.workflow.smartesbreader.SmartEsbCodeReaderPreparation;
import com.sonnet.wyf.gitreport.workflow.smartesbreader.SmartEsbCodeReaderPromptBuilder;
import com.sonnet.wyf.gitreport.workflow.smartesbreader.SmartEsbCodeReaderProperties;
import com.sonnet.wyf.gitreport.workflow.smartesbreader.SmartEsbCodeReaderWorkflowChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SmartEsbCodeReaderWorkflowChainTest {
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
    void fullRunExecutesModulesThenTransactionsThenIndexWithoutBatchTasks() throws Exception {
        SmartEsbCodeReaderProperties properties = codeReaderProperties();
        writeSampleProject(properties);
        TrackingTaskRunner taskRunner = new TrackingTaskRunner();
        SmartEsbCodeReaderWorkflowChain chain = chain(properties, taskRunner);
        AgentBridgeSettings settings = new AgentBridgeSettings();
        settings.setConcurrency(2);
        settings.setMaxConcurrency(2);

        chain.run(new WorkflowRunRequest("full", null, null, null, settings));

        assertThat(taskRunner.titles).containsSubsequence(
                "smartesb-reader-module-BaseConvert8583CUPS",
                "smartesb-reader-transaction-CaConsume",
                "smartesb-reader-index"
        );
        assertThat(taskRunner.prompts).anySatisfy(prompt -> assertThat(prompt)
                .contains("SmartESB code-reader 模块阅读任务")
                .contains("task_json_path: " + properties.getLocalOut().resolve("tasks/module-BaseConvert8583CUPS.json")));
        assertThat(taskRunner.prompts).anySatisfy(prompt -> assertThat(prompt)
                .contains("SmartESB code-reader 交易阅读任务")
                .contains("task_json_path: " + properties.getLocalOut().resolve("tasks/transaction-CaConsume.json")));
        assertThat(properties.getLocalOut().resolve("tasks/batches")).doesNotExist();
        assertThat(properties.getLocalOut().resolve("runs/incomplete-modules.json")).content().contains("\"state\" : \"completed\"");
        assertThat(properties.getLocalOut().resolve("runs/incomplete-transactions.json")).content().contains("\"state\" : \"completed\"");
    }

    @Test
    void transactionRerunOnlyRunsRequestedTransactionsAndIndex() throws Exception {
        SmartEsbCodeReaderProperties properties = codeReaderProperties();
        writeSampleProject(properties);
        new SmartEsbCodeReaderPreparation(objectMapper).prepare(properties, true);
        writeCompletedTaskOutput(properties.getLocalOut().resolve("modules/BaseConvert8583CUPS"), "module", "BaseConvert8583CUPS");
        writeCompletedTaskOutput(properties.getLocalOut().resolve("transactions/CaConsume"), "transaction", "CaConsume");
        TrackingTaskRunner taskRunner = new TrackingTaskRunner();
        SmartEsbCodeReaderWorkflowChain chain = chain(properties, taskRunner);
        AgentBridgeSettings settings = new AgentBridgeSettings();
        settings.setConcurrency(2);
        settings.setMaxConcurrency(2);

        chain.run(new WorkflowRunRequest("rerun", "transaction", "CaConsume", null, settings));

        assertThat(taskRunner.titles).containsExactly("smartesb-reader-transaction-CaConsume", "smartesb-reader-index");
        assertThat(taskRunner.titles).doesNotContain("smartesb-reader-module-BaseConvert8583CUPS");
    }

    private SmartEsbCodeReaderWorkflowChain chain(SmartEsbCodeReaderProperties properties, TrackingTaskRunner taskRunner) {
        return new SmartEsbCodeReaderWorkflowChain(
                new FixedChainConfigLoader(properties),
                new AgentBridgeRunnerProperties(),
                new SmartEsbCodeReaderPreparation(objectMapper),
                new SmartEsbCodeReaderPromptBuilder(new DefaultResourceLoader()),
                new SmartEsbCodeReaderOutputValidator(objectMapper),
                taskRunner,
                objectMapper,
                new OutputCompletionGate(objectMapper),
                new ConcurrentWorkflowTaskRunner(taskExecutor(3))
        );
    }

    private SmartEsbCodeReaderProperties codeReaderProperties() {
        SmartEsbCodeReaderProperties properties = new SmartEsbCodeReaderProperties();
        properties.setOut(tempDir.resolve("logical-out").toString());
        properties.setLocalOut(tempDir.resolve("local-out"));
        properties.setServiceIdentify(List.of(tempDir.resolve("project/serviceIdentify.xml")));
        properties.setXmlRoot(tempDir.resolve("project/xml"));
        properties.setBizRoot(tempDir.resolve("project/biz"));
        properties.setJavaRoot(tempDir.resolve("project/src/main/java"));
        return properties;
    }

    private void writeSampleProject(SmartEsbCodeReaderProperties properties) throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(properties.getXmlRoot());
        Files.createDirectories(properties.getBizRoot());
        Files.createDirectories(properties.getJavaRoot());
        Files.writeString(project.resolve("serviceIdentify.xml"), """
                <channels><switch mode="8583"><case target="CaConsumeCUPS2ECI"/></switch></channels>
                """);
        Files.writeString(properties.getXmlRoot().resolve("CaConsume.xml"), """
                <proxyEngine><route><to serviceId="BaseConvert8583CUPS"/></route></proxyEngine>
                """);
        Files.writeString(properties.getXmlRoot().resolve("BaseConvert8583CUPS.xml"), "<base/>");
        Files.writeString(properties.getJavaRoot().resolve("BaseConvert8583CUPS.java"), "class BaseConvert8583CUPS {}\n");
    }

    private ThreadPoolTaskExecutor taskExecutor(int concurrency) {
        executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("test-reader-task-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.initialize();
        return executor;
    }

    private ScheduledProbeWaiter scheduledProbeWaiter() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("test-reader-poll-");
        scheduler.setPoolSize(2);
        scheduler.initialize();
        return new ScheduledProbeWaiter(scheduler);
    }

    private class TrackingTaskRunner extends AgentBridgeTaskRunner {
        private final CopyOnWriteArrayList<String> titles = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> prompts = new CopyOnWriteArrayList<>();

        TrackingTaskRunner() {
            super(new AgentBridgeClient(objectMapper), scheduledProbeWaiter());
        }

        @Override
        public AgentBridgeRunResult runUntilValidated(ValidatedAgentBridgeTaskSpec spec) throws Exception {
            titles.add(spec.title());
            prompts.add(Files.readString(spec.promptFile()));
            if (spec.title().endsWith("-index")) {
                Files.writeString(spec.runDir().getParent().getParent().resolve("index.md"), "# 索引\n完成\n");
            } else {
                String prompt = Files.readString(spec.promptFile());
                if (prompt.contains("review_type: module")) {
                    writeCompletedTaskOutput(spec.runDir().getParent().getParent().resolve("modules/BaseConvert8583CUPS"), "module", "BaseConvert8583CUPS");
                } else {
                    writeCompletedTaskOutput(spec.runDir().getParent().getParent().resolve("transactions/CaConsume"), "transaction", "CaConsume");
                }
            }
            return new AgentBridgeRunResult("task-" + spec.title(), spec.webBaseUrl().toString(), false, true, "idle", true, "", 0);
        }
    }

    private void writeCompletedTaskOutput(Path dir, String type, String name) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("analysis.md"), "# 完成\n正文\n");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("module".equals(type) ? "serviceId" : "transaction_key", name);
        summary.put("status", "completed");
        summary.put("risks_or_uncertainties", List.of());
        objectMapper.writeValue(dir.resolve("summary.json").toFile(), summary);
    }

    private static class FixedChainConfigLoader extends ChainConfigLoader {
        private final SmartEsbCodeReaderProperties properties;

        FixedChainConfigLoader(SmartEsbCodeReaderProperties properties) {
            super(new DefaultResourceLoader());
            this.properties = properties;
        }

        @Override
        public <T> T load(String configDir, String chainId, Class<T> type) {
            return type.cast(properties);
        }
    }
}
