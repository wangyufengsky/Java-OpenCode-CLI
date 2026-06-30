package com.sonnet.wyf.gitreport.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

public class WorkflowEventSink {
    private static final Logger log = LoggerFactory.getLogger(WorkflowEventSink.class);

    private final WorkflowRunRepository repository;
    private final EventStreamService eventStreamService;

    public WorkflowEventSink() {
        this.repository = null;
        this.eventStreamService = null;
    }

    public WorkflowEventSink(WorkflowRunRepository repository, EventStreamService eventStreamService) {
        this.repository = repository;
        this.eventStreamService = eventStreamService;
    }

    public void emit(long runId, String eventType, String message) {
        if (repository == null || eventStreamService == null) {
            return;
        }
        try {
            repository.appendEvent(runId, eventType, message);
            logEvent(runId, eventType, message);
            List<WorkflowRunEvent> events = repository.listEvents(runId);
            if (!events.isEmpty()) {
                eventStreamService.publish(events.get(events.size() - 1));
            }
        } catch (RuntimeException exception) {
            log.warn("Unable to emit workflow event: runId={}, eventType={}, reason={}", runId, eventType, exception.getMessage());
        }
    }

    private static void logEvent(long runId, String eventType, String message) {
        if ("FAILED".equals(eventType) || "TASK_FAILED".equals(eventType)) {
            log.warn("Workflow event: runId={}, eventType={}, message={}", runId, eventType, message);
        } else {
            log.info("Workflow event: runId={}, eventType={}, message={}", runId, eventType, message);
        }
    }

    public void emitCurrent(String eventType, String message) {
        Long runId = WorkflowRunContext.currentRunId();
        if (runId != null) {
            emit(runId, eventType, message);
        }
    }

    public void taskStatusCurrent(String taskKey, String taskName, String state, String phase, String statusPath, String errorMessage) {
        Long runId = WorkflowRunContext.currentRunId();
        if (runId == null || repository == null) {
            return;
        }
        try {
            repository.upsertTaskStatus(new WorkflowTaskStatus(
                    runId,
                    taskKey,
                    taskName,
                    state,
                    phase,
                    statusPath,
                    errorMessage,
                    Instant.now()
            ));
            emit(runId, "TASK_" + state, taskKey + " " + phase);
        } catch (RuntimeException exception) {
            log.warn("Unable to emit workflow task status: runId={}, taskKey={}, reason={}", runId, taskKey, exception.getMessage());
        }
    }
}
