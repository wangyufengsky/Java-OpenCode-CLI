package com.sonnet.wyf.gitreport.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.console.WorkflowEventSink;
import com.sonnet.wyf.gitreport.console.WorkflowRunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class OpenCodeRunMonitor {
    private static final Logger log = LoggerFactory.getLogger(OpenCodeRunMonitor.class);

    private final OpenCodeServerHandle server;
    private final String title;
    private final Path promptFile;
    private final Path runDir;
    private final String sessionId;
    private final WorkflowEventSink eventSink;
    private final ObjectMapper objectMapper;
    private final Instant startedAt = Instant.now();
    private final AtomicInteger pollCount = new AtomicInteger();
    private final AtomicReference<String> lastLoggedState = new AtomicReference<>("unknown");
    private final int logEveryPolls;

    OpenCodeRunMonitor(
            OpenCodeServerHandle server,
            String title,
            Path promptFile,
            Path runDir,
            String sessionId,
            int pollMillis,
            WorkflowEventSink eventSink,
            ObjectMapper objectMapper
    ) {
        this.server = server;
        this.title = title;
        this.promptFile = promptFile;
        this.runDir = runDir;
        this.sessionId = sessionId;
        this.eventSink = eventSink;
        this.objectMapper = objectMapper;
        this.logEveryPolls = Math.max(1, 60_000 / Math.max(50, pollMillis));
    }

    int recordPoll(String state) {
        return pollCount.incrementAndGet();
    }

    void logHeartbeat(String state, int currentPollCount) {
        String previous = lastLoggedState.getAndSet(state);
        if (!Objects.equals(previous, state) || currentPollCount % logEveryPolls == 0) {
            log.info("OpenCode session heartbeat: sessionId={}, title={}, serverState={}, pollCount={}, elapsedSeconds={}, runDir={}",
                    sessionId,
                    title,
                    state,
                    currentPollCount,
                    elapsedSeconds(),
                    runDir);
        }
    }

    void write(String phase, String serverState, boolean timedOut, boolean aborted) {
        write(phase, serverState, timedOut, aborted, 0, "");
    }

    void write(String phase, String serverState, boolean timedOut, boolean aborted, int correctionRound, String validationError) {
        try {
            Files.createDirectories(runDir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(runDir.resolve("session-status.json").toFile(), status(phase, serverState, timedOut, aborted, correctionRound, validationError));
        } catch (IOException exception) {
            log.warn("Unable to write OpenCode session status: sessionId={}, runDir={}, reason={}", sessionId, runDir, exception.getMessage());
        }
        WorkflowRunContext.TaskIdentity task = WorkflowRunContext.currentTask();
        eventSink.taskStatusCurrent(
                task == null ? title : task.taskKey(),
                task == null ? title : task.taskName(),
                terminalState(phase, timedOut, aborted),
                phase,
                runDir.resolve("session-status.json").toString(),
                validationError
        );
    }

    void writeQuietly(String phase, String serverState, boolean timedOut, boolean aborted) {
        write(phase, serverState, timedOut, aborted);
    }

    void writeQuietly(String phase, String serverState, boolean timedOut, boolean aborted, int correctionRound, String validationError) {
        write(phase, serverState, timedOut, aborted, correctionRound, validationError);
    }

    private Map<String, Object> status(String phase, String serverState, boolean timedOut, boolean aborted, int correctionRound, String validationError) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("phase", phase);
        status.put("sessionId", sessionId);
        status.put("title", title);
        status.put("serverUrl", server.serverUrl().toString());
        status.put("serverOwnedByJava", server.ownedByJava());
        status.put("serverState", serverState);
        status.put("pollCount", pollCount.get());
        status.put("correctionRound", Math.max(0, correctionRound));
        status.put("validationError", validationError == null ? "" : validationError);
        status.put("timedOut", timedOut);
        status.put("aborted", aborted);
        status.put("runDir", runDir.toString());
        status.put("promptFile", promptFile.toString());
        status.put("startedAt", OffsetDateTime.ofInstant(startedAt, ZoneOffset.systemDefault()).toString());
        status.put("updatedAt", OffsetDateTime.now().toString());
        status.put("elapsedSeconds", elapsedSeconds());
        return status;
    }

    private long elapsedSeconds() {
        return Duration.between(startedAt, Instant.now()).toSeconds();
    }

    private String terminalState(String phase, boolean timedOut, boolean aborted) {
        if (timedOut || aborted || phase.toLowerCase(Locale.ROOT).contains("failed")) {
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
