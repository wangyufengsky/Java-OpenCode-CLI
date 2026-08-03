package com.sonnet.wyf.gitreport.console;

import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.AgentBridgeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.artifact.WorkflowExecutionIds;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorkflowExecutionService implements WorkflowRunSubmitter, AutoCloseable {
    private final ChainCatalog chainCatalog;
    private final WorkflowRunRepository repository;
    private final WorkflowEventSink eventSink;
    private final RunConfigWriter configWriter;
    private final AgentBridgeRunnerProperties runnerProperties;
    private final ExecutorService executorService;

    public WorkflowExecutionService(
            ChainCatalog chainCatalog,
            WorkflowRunRepository repository,
            WorkflowEventSink eventSink,
            RunConfigWriter configWriter,
            AgentBridgeRunnerProperties runnerProperties
    ) {
        this(chainCatalog, repository, eventSink, configWriter, runnerProperties,
                Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "workflow-console-runner")));
    }

    WorkflowExecutionService(
            ChainCatalog chainCatalog,
            WorkflowRunRepository repository,
            WorkflowEventSink eventSink,
            RunConfigWriter configWriter,
            AgentBridgeRunnerProperties runnerProperties,
            ExecutorService executorService
    ) {
        this.chainCatalog = chainCatalog;
        this.repository = repository;
        this.eventSink = eventSink;
        this.configWriter = configWriter;
        this.runnerProperties = runnerProperties;
        this.executorService = executorService;
    }

    @Override
    public long submit(WorkflowRunSubmission submission) throws Exception {
        WorkflowRunSubmission normalized = normalize(submission);
        chainCatalog.chain(normalized.chainId());
        long runId = repository.createRun(normalized, "");
        eventSink.emit(runId, "QUEUED", "运行已进入队列");
        Path configPath;
        try {
            String defaultYaml = normalized.config().isEmpty() ? chainCatalog.defaultYaml(normalized.chainId()) : "";
            configPath = configWriter.writeConfig(
                    runId,
                    normalized.chainId(),
                    normalized.config(),
                    defaultYaml
            );
            repository.updateConfigPath(runId, configPath.toString());
        } catch (Exception exception) {
            repository.markFailed(runId, exception.getMessage());
            eventSink.emit(runId, "FAILED", exception.getMessage() == null ? exception.toString() : exception.getMessage());
            throw exception;
        }
        executorService.submit(() -> run(runId, normalized, configPath.getParent()));
        return runId;
    }

    private void run(long runId, WorkflowRunSubmission submission, Path configDir) {
        try (WorkflowRunContext.Scope ignored = WorkflowRunContext.open(runId)) {
            repository.markRunning(runId);
            eventSink.emit(runId, "STARTED", "运行已开始");
            WorkflowChain chain = chainCatalog.chain(submission.chainId());
            chain.run(new WorkflowRunRequest(
                    submission.mode(),
                    submission.rerunType(),
                    submission.rerunId(),
                    submission.runDate(),
                    submission.agentBridge() == null ? copy(runnerProperties.getAgentbridge()) : submission.agentBridge(),
                    configDir.toString(),
                    WorkflowExecutionIds.newExecutionId(),
                    runId
            ));
            String outputPath = WorkflowRunContext.currentOutputPath();
            if (outputPath != null && !outputPath.isBlank()) {
                repository.updateOutputPath(runId, outputPath);
            }
            repository.markSucceeded(runId);
            eventSink.emit(runId, "SUCCEEDED", "运行已完成");
        } catch (Throwable failure) {
            String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            repository.markFailed(runId, message);
            eventSink.emit(runId, "FAILED", message);
        }
    }

    private static WorkflowRunSubmission normalize(WorkflowRunSubmission submission) {
        String chainId = normalizeText(submission.chainId());
        return new WorkflowRunSubmission(
                chainId,
                normalizeText(submission.mode() == null || submission.mode().isBlank() ? "full" : submission.mode()),
                WorkflowRerunContract.normalizeType(chainId, submission.rerunType()),
                submission.rerunId(),
                submission.runDate(),
                ConsoleConfigNormalizer.normalize(submission.config()),
                submission.agentBridge()
        );
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static AgentBridgeSettings copy(AgentBridgeSettings source) {
        AgentBridgeSettings target = new AgentBridgeSettings();
        target.setWebBaseUrl(source.getWebBaseUrl());
        target.setMcpUrl(source.getMcpUrl());
        target.setConcurrency(source.getConcurrency());
        target.setTimeoutMinutes(source.getTimeoutMinutes());
        target.setPollMillis(source.getPollMillis());
        target.setValidationSettleSeconds(source.getValidationSettleSeconds());
        target.setValidationMaxCorrections(source.getValidationMaxCorrections());
        target.setMaxConcurrency(source.getMaxConcurrency());
        target.setTaskMessage(source.getTaskMessage());
        target.setSynthesisTaskMessage(source.getSynthesisTaskMessage());
        return target;
    }

    @Override
    public void close() {
        executorService.shutdownNow();
    }
}
