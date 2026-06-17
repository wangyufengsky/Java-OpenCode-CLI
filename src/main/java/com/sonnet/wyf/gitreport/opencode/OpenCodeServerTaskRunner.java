package com.sonnet.wyf.gitreport.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class OpenCodeServerTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(OpenCodeServerTaskRunner.class);

    private final OpenCodeServerClient client;
    private final ScheduledProbeWaiter scheduledProbeWaiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenCodeServerTaskRunner(OpenCodeServerClient client, ScheduledProbeWaiter scheduledProbeWaiter) {
        this.client = client;
        this.scheduledProbeWaiter = scheduledProbeWaiter;
    }

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
        Files.createDirectories(runDir);
        String prompt = Files.readString(promptFile);
        String text = composeMessage(message, prompt);
        OpenCodeSession session = client.createSession(server.serverUrl(), repo, title, sessionModel, createSessionTimeoutSeconds);
        RunMonitor monitor = new RunMonitor(server, title, promptFile, runDir, session.id(), Math.max(50, pollMillis));
        log.info("Starting OpenCode Server session: sessionId={}, title={}, runDir={}, timeoutMinutes={}", session.id(), title, runDir, timeoutMinutes);
        monitor.write("created", "unknown", false, false);
        client.sendPromptAsync(server.serverUrl(), repo, session.id(), text, sessionModel, requestTimeoutSeconds);
        AtomicReference<String> lastState = new AtomicReference<>("submitted");
        monitor.write("running", lastState.get(), false, false);
        return scheduledProbeWaiter.waitFor(
                () -> pollOnce(server, repo, runDir, completionProbe, session, lastState, monitor),
                result -> result != null,
                () -> abortTimedOut(server, repo, session, lastState.get(), monitor),
                Duration.ofMinutes(timeoutMinutes),
                Duration.ofMillis(Math.max(50, pollMillis))
        );
    }

    private String composeMessage(String message, String prompt) {
        if (message == null || message.isBlank()) {
            return prompt;
        }
        return message + "\n\n" + prompt;
    }

    private boolean isComplete(CompletionProbe completionProbe, Path runDir) throws IOException {
        try {
            return completionProbe.isComplete();
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("completion probe failed for runDir=" + runDir + ": " + exception.getMessage(), exception);
        }
    }

    private OpenCodeRunResult pollOnce(
            OpenCodeServerHandle server,
            Path repo,
            Path runDir,
            CompletionProbe completionProbe,
            OpenCodeSession session,
            AtomicReference<String> lastState,
            RunMonitor monitor
    ) throws IOException {
        if (isComplete(completionProbe, runDir)) {
            monitor.write("completed_by_output", lastState.get(), false, false);
            return new OpenCodeRunResult(session.id(), server.serverUrl().toString(), server.ownedByJava(), false, true, false, lastState.get());
        }
        String state = readSessionState(server.serverUrl(), repo, session.id(), lastState.get());
        lastState.set(state);
        int pollCount = monitor.recordPoll(state);
        monitor.write("running", state, false, false);
        monitor.logHeartbeat(state, pollCount);
        if (isTerminalFailure(state)) {
            monitor.write("server_terminal_failure", state, false, false);
            return new OpenCodeRunResult(session.id(), server.serverUrl().toString(), server.ownedByJava(), false, false, false, state);
        }
        if (isTerminalSuccess(state)) {
            monitor.write("server_terminal_success", state, false, false);
            return new OpenCodeRunResult(session.id(), server.serverUrl().toString(), server.ownedByJava(), false, false, false, state);
        }
        return null;
    }

    private OpenCodeRunResult abortTimedOut(OpenCodeServerHandle server, Path repo, OpenCodeSession session, String lastState, RunMonitor monitor) {
        boolean aborted = client.abortSession(server.serverUrl(), repo, session.id());
        monitor.writeQuietly("timeout", lastState, true, aborted);
        return new OpenCodeRunResult(session.id(), server.serverUrl().toString(), server.ownedByJava(), true, false, aborted, lastState);
    }

    private String readSessionState(URI serverUrl, Path repo, String sessionId, String fallback) {
        try {
            String status = client.getSessionStatus(serverUrl, repo, sessionId);
            return status == null || status.isBlank() ? fallback : status;
        } catch (Exception exception) {
            log.debug("Unable to read OpenCode session status: sessionId={}, reason={}", sessionId, exception.getMessage());
            return fallback;
        }
    }

    private boolean isTerminalSuccess(String state) {
        String normalized = state == null ? "" : state.toLowerCase(Locale.ROOT);
        return normalized.equals("idle") || normalized.equals("completed") || normalized.equals("complete") || normalized.equals("done");
    }

    private boolean isTerminalFailure(String state) {
        String normalized = state == null ? "" : state.toLowerCase(Locale.ROOT);
        return normalized.equals("failed") || normalized.equals("error") || normalized.equals("aborted") || normalized.equals("canceled") || normalized.equals("cancelled");
    }

    private class RunMonitor {
        private final OpenCodeServerHandle server;
        private final String title;
        private final Path promptFile;
        private final Path runDir;
        private final String sessionId;
        private final Instant startedAt = Instant.now();
        private final AtomicInteger pollCount = new AtomicInteger();
        private final AtomicReference<String> lastLoggedState = new AtomicReference<>("unknown");
        private final int logEveryPolls;

        private RunMonitor(OpenCodeServerHandle server, String title, Path promptFile, Path runDir, String sessionId, int pollMillis) {
            this.server = server;
            this.title = title;
            this.promptFile = promptFile;
            this.runDir = runDir;
            this.sessionId = sessionId;
            this.logEveryPolls = Math.max(1, 60_000 / Math.max(50, pollMillis));
        }

        private int recordPoll(String state) {
            return pollCount.incrementAndGet();
        }

        private void logHeartbeat(String state, int currentPollCount) {
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

        private void write(String phase, String serverState, boolean timedOut, boolean aborted) {
            try {
                Files.createDirectories(runDir);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(runDir.resolve("session-status.json").toFile(), status(phase, serverState, timedOut, aborted));
            } catch (IOException exception) {
                log.warn("Unable to write OpenCode session status: sessionId={}, runDir={}, reason={}", sessionId, runDir, exception.getMessage());
            }
        }

        private void writeQuietly(String phase, String serverState, boolean timedOut, boolean aborted) {
            write(phase, serverState, timedOut, aborted);
        }

        private Map<String, Object> status(String phase, String serverState, boolean timedOut, boolean aborted) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("phase", phase);
            status.put("sessionId", sessionId);
            status.put("title", title);
            status.put("serverUrl", server.serverUrl().toString());
            status.put("serverOwnedByJava", server.ownedByJava());
            status.put("serverState", serverState);
            status.put("pollCount", pollCount.get());
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
    }
}
