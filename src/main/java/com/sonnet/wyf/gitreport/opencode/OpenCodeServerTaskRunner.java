package com.sonnet.wyf.gitreport.opencode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

public class OpenCodeServerTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(OpenCodeServerTaskRunner.class);

    private final OpenCodeServerClient client;
    private final TaskScheduler taskScheduler;

    public OpenCodeServerTaskRunner(OpenCodeServerClient client, TaskScheduler taskScheduler) {
        this.client = client;
        this.taskScheduler = taskScheduler;
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
    ) throws IOException, InterruptedException {
        Files.createDirectories(runDir);
        String prompt = Files.readString(promptFile);
        String text = composeMessage(message, prompt);
        OpenCodeSession session = client.createSession(server.serverUrl(), repo, title);
        log.info("Starting OpenCode Server session: sessionId={}, title={}, runDir={}, timeoutMinutes={}", session.id(), title, runDir, timeoutMinutes);
        client.sendPromptAsync(server.serverUrl(), repo, session.id(), text, model);
        AtomicReference<String> lastState = new AtomicReference<>("unknown");
        OpenCodeRunResult initialResult = pollOnce(server, repo, runDir, completionProbe, session, lastState);
        if (initialResult != null) {
            return initialResult;
        }
        if (timeoutMinutes <= 0) {
            return abortTimedOut(server, repo, session, lastState.get());
        }
        return awaitScheduledCompletion(server, repo, runDir, completionProbe, pollMillis, timeoutMinutes, session, lastState);
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

    private OpenCodeRunResult awaitScheduledCompletion(
            OpenCodeServerHandle server,
            Path repo,
            Path runDir,
            CompletionProbe completionProbe,
            int pollMillis,
            int timeoutMinutes,
            OpenCodeSession session,
            AtomicReference<String> lastState
    ) throws IOException, InterruptedException {
        CompletableFuture<OpenCodeRunResult> completion = new CompletableFuture<>();
        int intervalMillis = Math.max(50, pollMillis);
        ScheduledFuture<?> pollFuture = taskScheduler.scheduleAtFixedRate(
                () -> completeFromPoll(server, repo, runDir, completionProbe, session, lastState, completion),
                Duration.ofMillis(intervalMillis)
        );
        ScheduledFuture<?> timeoutFuture = taskScheduler.schedule(
                () -> completion.complete(abortTimedOut(server, repo, session, lastState.get())),
                Instant.now().plus(Duration.ofMinutes(timeoutMinutes))
        );
        try {
            return completion.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("scheduled OpenCode session polling failed", cause);
        } finally {
            pollFuture.cancel(false);
            timeoutFuture.cancel(false);
        }
    }

    private void completeFromPoll(
            OpenCodeServerHandle server,
            Path repo,
            Path runDir,
            CompletionProbe completionProbe,
            OpenCodeSession session,
            AtomicReference<String> lastState,
            CompletableFuture<OpenCodeRunResult> completion
    ) {
        if (completion.isDone()) {
            return;
        }
        try {
            OpenCodeRunResult result = pollOnce(server, repo, runDir, completionProbe, session, lastState);
            if (result != null) {
                completion.complete(result);
            }
        } catch (Exception exception) {
            completion.completeExceptionally(exception);
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
