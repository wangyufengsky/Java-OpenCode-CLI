package com.sonnet.wyf.gitreport.opencode;

import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class OpenCodeServerTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(OpenCodeServerTaskRunner.class);

    private final OpenCodeServerClient client;
    private final ScheduledProbeWaiter scheduledProbeWaiter;

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
            String model,
            Path runDir,
            CompletionProbe completionProbe,
            int pollMillis,
            int timeoutMinutes
    ) throws Exception {
        Files.createDirectories(runDir);
        String prompt = Files.readString(promptFile);
        String text = composeMessage(message, prompt);
        OpenCodeSession session = client.createSession(server.serverUrl(), repo, title);
        log.info("Starting OpenCode Server session: sessionId={}, title={}, runDir={}, timeoutMinutes={}", session.id(), title, runDir, timeoutMinutes);
        client.sendPromptAsync(server.serverUrl(), repo, session.id(), text, model);
        AtomicReference<String> lastState = new AtomicReference<>("unknown");
        return scheduledProbeWaiter.waitFor(
                () -> pollOnce(server, repo, runDir, completionProbe, session, lastState),
                result -> result != null,
                () -> abortTimedOut(server, repo, session, lastState.get()),
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
            AtomicReference<String> lastState
    ) throws IOException {
        if (isComplete(completionProbe, runDir)) {
            return new OpenCodeRunResult(session.id(), server.serverUrl().toString(), server.ownedByJava(), false, true, false, lastState.get());
        }
        String state = readSessionState(server.serverUrl(), repo, session.id(), lastState.get());
        lastState.set(state);
        if (isTerminalFailure(state)) {
            return new OpenCodeRunResult(session.id(), server.serverUrl().toString(), server.ownedByJava(), false, false, false, state);
        }
        if (isTerminalSuccess(state)) {
            return new OpenCodeRunResult(session.id(), server.serverUrl().toString(), server.ownedByJava(), false, false, false, state);
        }
        return null;
    }

    private OpenCodeRunResult abortTimedOut(OpenCodeServerHandle server, Path repo, OpenCodeSession session, String lastState) {
        boolean aborted = client.abortSession(server.serverUrl(), repo, session.id());
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
}
