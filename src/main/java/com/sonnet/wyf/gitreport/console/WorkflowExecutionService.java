package com.sonnet.wyf.gitreport.console;

import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.OpenCodeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorkflowExecutionService implements AutoCloseable {
    private final ChainCatalog chainCatalog;
    private final WorkflowRunRepository repository;
    private final WorkflowEventSink eventSink;
    private final RunConfigWriter configWriter;
    private final OpenCodeRunnerProperties runnerProperties;
    private final ExecutorService executorService;

    public WorkflowExecutionService(
            ChainCatalog chainCatalog,
            WorkflowRunRepository repository,
            WorkflowEventSink eventSink,
            RunConfigWriter configWriter,
            OpenCodeRunnerProperties runnerProperties
    ) {
        this(chainCatalog, repository, eventSink, configWriter, runnerProperties,
                Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "workflow-console-runner")));
    }

    WorkflowExecutionService(
            ChainCatalog chainCatalog,
            WorkflowRunRepository repository,
            WorkflowEventSink eventSink,
            RunConfigWriter configWriter,
            OpenCodeRunnerProperties runnerProperties,
            ExecutorService executorService
    ) {
        this.chainCatalog = chainCatalog;
        this.repository = repository;
        this.eventSink = eventSink;
        this.configWriter = configWriter;
        this.runnerProperties = runnerProperties;
        this.executorService = executorService;
    }

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
                    submission.openCode() == null ? copy(runnerProperties.getOpencode()) : submission.openCode(),
                    configDir.toString()
            ));
            repository.markSucceeded(runId);
            eventSink.emit(runId, "SUCCEEDED", "运行已完成");
        } catch (Exception exception) {
            repository.markFailed(runId, exception.getMessage());
            eventSink.emit(runId, "FAILED", exception.getMessage() == null ? exception.toString() : exception.getMessage());
        }
    }

    private static WorkflowRunSubmission normalize(WorkflowRunSubmission submission) {
        return new WorkflowRunSubmission(
                normalizeText(submission.chainId()),
                normalizeText(submission.mode() == null || submission.mode().isBlank() ? "full" : submission.mode()),
                normalizeText(submission.rerunType()),
                submission.rerunId(),
                submission.runDate(),
                submission.config() == null ? Map.of() : Map.copyOf(submission.config()),
                submission.openCode()
        );
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static OpenCodeSettings copy(OpenCodeSettings source) {
        OpenCodeSettings target = new OpenCodeSettings();
        target.setServerUrl(source.getServerUrl());
        target.setManageServer(source.isManageServer());
        target.setServerStartTimeoutSeconds(source.getServerStartTimeoutSeconds());
        target.setCreateSessionTimeoutSeconds(source.getCreateSessionTimeoutSeconds());
        target.setRequestTimeoutSeconds(source.getRequestTimeoutSeconds());
        target.setConcurrency(source.getConcurrency());
        target.setTimeoutMinutes(source.getTimeoutMinutes());
        target.setOutputWaitSeconds(source.getOutputWaitSeconds());
        target.setValidationMaxCorrections(source.getValidationMaxCorrections());
        target.setMaxRetries(source.getMaxRetries());
        target.setMaxConcurrency(source.getMaxConcurrency());
        target.setOpencodeBin(source.getOpencodeBin());
        target.setSessionModel(source.getSessionModel());
        target.setEnvironment(source.getEnvironment());
        return target;
    }

    @Override
    public void close() {
        executorService.shutdownNow();
    }
}
