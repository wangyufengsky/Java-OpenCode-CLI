package com.sonnet.wyf.gitreport.agentbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.console.WorkflowEventSink;
import com.sonnet.wyf.gitreport.console.WorkflowRunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentBridgeRunMonitor {
    private static final Logger log = LoggerFactory.getLogger(AgentBridgeRunMonitor.class);

    private final URI webBaseUrl;
    private final String title;
    private final Path promptFile;
    private final Path runDir;
    private final String taskId;
    private final WorkflowEventSink eventSink;
    private final ObjectMapper objectMapper;
    private final Instant startedAt = Instant.now();
    private final AtomicInteger pollCount = new AtomicInteger();
    private final AtomicReference<String> lastLoggedState = new AtomicReference<>("unknown");
    private final int logEveryPolls;

    AgentBridgeRunMonitor(
            URI webBaseUrl,
            String title,
            Path promptFile,
            Path runDir,
            int pollMillis,
            WorkflowEventSink eventSink,
            ObjectMapper objectMapper
    ) {
        this.webBaseUrl = webBaseUrl;
        this.title = title;
        this.promptFile = promptFile;
        this.runDir = runDir;
        this.taskId = title + "-" + UUID.randomUUID();
        this.eventSink = eventSink;
        this.objectMapper = objectMapper;
        this.logEveryPolls = Math.max(1, 60_000 / Math.max(50, pollMillis));
    }

    String taskId() {
        return taskId;
    }

    int recordPoll(String state) {
        return pollCount.incrementAndGet();
    }

    void logHeartbeat(String state, int currentPollCount) {
        String previous = lastLoggedState.getAndSet(state);
        if (!Objects.equals(previous, state) || currentPollCount % logEveryPolls == 0) {
            log.info("AgentBridge task heartbeat: taskId={}, title={}, agentState={}, pollCount={}, elapsedSeconds={}, runDir={}",
                    taskId, title, state, currentPollCount, elapsedSeconds(), runDir);
        }
    }

    void write(String phase, String agentState, boolean timedOut) {
        write(phase, agentState, timedOut, 0, "");
    }

    void write(String phase, String agentState, boolean timedOut, int correctionRound, String validationError) {
        Path statusPath = runDir.resolve("agent-status.json");
        try {
            Files.createDirectories(runDir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(statusPath.toFile(), status(phase, agentState, timedOut, correctionRound, validationError));
        } catch (IOException exception) {
            log.warn("Unable to write AgentBridge task status: taskId={}, runDir={}, reason={}", taskId, runDir, exception.getMessage());
        }
        WorkflowRunContext.TaskIdentity task = WorkflowRunContext.currentTask();
        eventSink.taskStatusCurrent(
                task == null ? title : task.taskKey(),
                task == null ? title : task.taskName(),
                terminalState(phase, timedOut),
                phase,
                statusPath.toString(),
                validationError
        );
    }

    private Map<String, Object> status(String phase, String agentState, boolean timedOut, int correctionRound, String validationError) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("phase", phase);
        status.put("taskId", taskId);
        status.put("title", title);
        status.put("agentbridgeWebBaseUrl", webBaseUrl.toString());
        status.put("agentState", agentState);
        status.put("pollCount", pollCount.get());
        status.put("correctionRound", Math.max(0, correctionRound));
        status.put("validationError", validationError == null ? "" : validationError);
        status.put("timedOut", timedOut);
        status.put("completedByOutput", "completed_by_output".equals(phase));
        status.put("runDir", runDir.toString());
        status.put("promptFile", promptFile.toString());
        status.put("startedAt", OffsetDateTime.ofInstant(startedAt, ZoneOffset.systemDefault()).toString());
        status.put("finishedAt", terminalState(phase, timedOut).equals("RUNNING") ? "" : OffsetDateTime.now().toString());
        status.put("updatedAt", OffsetDateTime.now().toString());
        status.put("elapsedSeconds", elapsedSeconds());
        return status;
    }

    private long elapsedSeconds() {
        return Duration.between(startedAt, Instant.now()).toSeconds();
    }

    private String terminalState(String phase, boolean timedOut) {
        if (timedOut || phase.toLowerCase(Locale.ROOT).contains("failed")) {
            return "FAILED";
        }
        if (phase.toLowerCase(Locale.ROOT).contains("completed")) {
            return "SUCCEEDED";
        }
        if ("created".equals(phase)) {
            return "QUEUED";
        }
        return "RUNNING";
    }
}
