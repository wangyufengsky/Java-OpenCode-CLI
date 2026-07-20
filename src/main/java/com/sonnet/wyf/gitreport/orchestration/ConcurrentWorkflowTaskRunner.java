package com.sonnet.wyf.gitreport.orchestration;

import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactContext;
import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactWorkspace;
import com.sonnet.wyf.gitreport.console.WorkflowEventSink;
import com.sonnet.wyf.gitreport.console.WorkflowRunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

public class ConcurrentWorkflowTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(ConcurrentWorkflowTaskRunner.class);

    private final AsyncTaskExecutor taskExecutor;
    private final WorkflowEventSink eventSink;

    public ConcurrentWorkflowTaskRunner(AsyncTaskExecutor taskExecutor) {
        this(taskExecutor, new WorkflowEventSink());
    }

    public ConcurrentWorkflowTaskRunner(AsyncTaskExecutor taskExecutor, WorkflowEventSink eventSink) {
        this.taskExecutor = taskExecutor;
        this.eventSink = eventSink;
    }

    public <T> List<TaskRunResult> run(
            String workflowName,
            List<T> tasks,
            int concurrency,
            Function<T, String> taskKey,
            Function<T, Callable<TaskRunResult>> taskFactory
    ) throws Exception {
        int slots = Math.max(1, concurrency);
        log.info("Starting workflow tasks: workflow={}, taskCount={}, concurrency={}", workflowName, tasks.size(), slots);
        eventSink.emitCurrent("TASK_GROUP_STARTED", workflowName + "，任务数=" + tasks.size());
        Semaphore semaphore = new Semaphore(slots);
        List<Future<TaskRunResult>> futures = new ArrayList<>();
        Long runId = WorkflowRunContext.currentRunId();
        WorkflowArtifactWorkspace artifactWorkspace = WorkflowArtifactContext.currentOrNull();
        for (T task : tasks) {
            futures.add(taskExecutor.submit(limitedCallable(semaphore, task, taskKey, taskFactory, runId, artifactWorkspace)));
        }
        List<TaskRunResult> results = new ArrayList<>();
        List<TaskRunResult> failures = new ArrayList<>();
        for (Future<TaskRunResult> future : futures) {
            TaskRunResult result = future.get();
            results.add(result);
            eventSink.taskStatusCurrent(
                    result.taskKey(),
                    result.taskName(),
                    result.success() ? "SUCCEEDED" : "FAILED",
                    workflowName,
                    result.statusPath() == null ? "" : result.statusPath().toString(),
                    result.error()
            );
            if (!result.success()) {
                failures.add(result);
            }
        }
        if (!failures.isEmpty()) {
            String summary = failures.stream()
                    .map(result -> result.taskKey()
                            + " ("
                            + result.taskName()
                            + "), status="
                            + (result.statusPath() == null ? "" : result.statusPath())
                            + ", error="
                            + result.error())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
            log.warn("{} task failure summary: firstReason=\"{}\" failedCount={} failures={}",
                    workflowName,
                    failures.get(0).error(),
                    failures.size(),
                    summary);
            return results;
        }
        log.info("Workflow tasks completed successfully: workflow={}", workflowName);
        eventSink.emitCurrent("TASK_GROUP_SUCCEEDED", workflowName);
        return results;
    }

    private <T> Callable<TaskRunResult> limitedCallable(
            Semaphore semaphore,
            T task,
            Function<T, String> taskKey,
            Function<T, Callable<TaskRunResult>> taskFactory,
            Long runId,
            WorkflowArtifactWorkspace artifactWorkspace
    ) {
        return () -> {
            boolean acquired = false;
            try (WorkflowRunContext.Scope ignored = WorkflowRunContext.open(runId);
                 WorkflowArtifactContext.Scope ignoredArtifacts = WorkflowArtifactContext.open(artifactWorkspace)) {
                semaphore.acquire();
                acquired = true;
                String key = taskKey.apply(task);
                try (WorkflowRunContext.Scope ignoredTask = WorkflowRunContext.openTask(key, key)) {
                    eventSink.taskStatusCurrent(key, key, "RUNNING", "已开始", "", "");
                    return taskFactory.apply(task).call();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                String key = taskKey.apply(task);
                return TaskRunResult.failed(key, key, null, sanitize(exception));
            } catch (Exception exception) {
                String key = taskKey.apply(task);
                return TaskRunResult.failed(key, key, null, sanitize(exception));
            } finally {
                if (acquired) {
                    semaphore.release();
                }
            }
        };
    }

    private String sanitize(Exception exception) {
        return exception.getClass().getName() + ": " + (exception.getMessage() == null ? "" : exception.getMessage());
    }
}
