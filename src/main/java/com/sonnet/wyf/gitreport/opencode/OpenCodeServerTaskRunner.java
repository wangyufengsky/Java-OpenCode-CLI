package com.sonnet.wyf.gitreport.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.console.WorkflowEventSink;
import com.sonnet.wyf.gitreport.console.WorkflowRunContext;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class OpenCodeServerTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(OpenCodeServerTaskRunner.class);

    private final OpenCodeServerClient client;
    private final ScheduledProbeWaiter scheduledProbeWaiter;
    private final WorkflowEventSink eventSink;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> cleanedManagedSessionScopes = ConcurrentHashMap.newKeySet();

    public OpenCodeServerTaskRunner(OpenCodeServerClient client, ScheduledProbeWaiter scheduledProbeWaiter) {
        this(client, scheduledProbeWaiter, new WorkflowEventSink());
    }

    public OpenCodeServerTaskRunner(OpenCodeServerClient client, ScheduledProbeWaiter scheduledProbeWaiter, WorkflowEventSink eventSink) {
        this.client = client;
        this.scheduledProbeWaiter = scheduledProbeWaiter;
        this.eventSink = eventSink;
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
        clearPriorManagedSessionsIfNeeded(server, repo, title, createSessionTimeoutSeconds);
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

    public OpenCodeRunResult runUntilValidated(
            ValidatedOpenCodeTaskSpec spec
    ) throws Exception {
        return runUntilValidated(
                spec.server(),
                spec.repo(),
                spec.title(),
                spec.promptFile(),
                spec.message(),
                spec.runDir(),
                spec.validationProbe(),
                spec.sessionModel(),
                spec.createSessionTimeoutSeconds(),
                spec.requestTimeoutSeconds(),
                spec.pollMillis(),
                spec.timeoutMinutes(),
                spec.validationSettleSeconds(),
                spec.validationMaxCorrections()
        );
    }

    public OpenCodeRunResult runUntilValidated(
            OpenCodeServerHandle server,
            Path repo,
            String title,
            Path promptFile,
            String message,
            Path runDir,
            ValidationProbe validationProbe,
            String sessionModel,
            int createSessionTimeoutSeconds,
            int requestTimeoutSeconds,
            int pollMillis,
            int timeoutMinutes,
            int validationSettleSeconds,
            int validationMaxCorrections
    ) throws Exception {
        Files.createDirectories(runDir);
        String prompt = Files.readString(promptFile);
        String text = composeMessage(message, prompt);
        clearPriorManagedSessionsIfNeeded(server, repo, title, createSessionTimeoutSeconds);
        OpenCodeSession session = client.createSession(server.serverUrl(), repo, title, sessionModel, createSessionTimeoutSeconds);
        RunMonitor monitor = new RunMonitor(server, title, promptFile, runDir, session.id(), Math.max(50, pollMillis));
        log.info("Starting validated OpenCode Server session: sessionId={}, title={}, runDir={}, timeoutMinutes={}, validationSettleSeconds={}, validationMaxCorrections={}",
                session.id(), title, runDir, timeoutMinutes, validationSettleSeconds, validationMaxCorrections);
        monitor.write("created", "unknown", false, false);
        client.sendPromptAsync(server.serverUrl(), repo, session.id(), text, sessionModel, requestTimeoutSeconds);
        AtomicReference<String> lastState = new AtomicReference<>("submitted");
        monitor.write("running", lastState.get(), false, false);

        int maxCorrections = Math.max(0, validationMaxCorrections);
        int correctionRound = 0;
        while (true) {
            WaitOutcome outcome = waitForValidationRound(
                    server,
                    repo,
                    runDir,
                    validationProbe,
                    session,
                    lastState,
                    monitor,
                    pollMillis,
                    timeoutMinutes,
                    validationSettleSeconds,
                    correctionRound
            );
            OpenCodeRunResult result = outcome.result();
            if (result.validationOk() || result.timedOut() || !outcome.correctable() || correctionRound >= maxCorrections) {
                if (!result.validationOk() && outcome.correctable()) {
                    monitor.write("validation_failed_final", result.serverState(), result.timedOut(), result.aborted(), correctionRound, result.validationError());
                }
                return result;
            }
            correctionRound++;
            String correction = correctionMessage(promptFile, result.validationError(), correctionRound, maxCorrections);
            monitor.write("validation_failed_correction_sent", result.serverState(), false, false, correctionRound, result.validationError());
            log.warn("OpenCode validation failed; sending correction prompt to same session: sessionId={}, title={}, correctionRound={}/{}, reason=\"{}\"",
                    session.id(), title, correctionRound, maxCorrections, result.validationError());
            client.sendPromptAsync(server.serverUrl(), repo, session.id(), correction, sessionModel, requestTimeoutSeconds);
            lastState.set("submitted");
            monitor.write("running", lastState.get(), false, false, correctionRound, result.validationError());
        }
    }

    private String composeMessage(String message, String prompt) {
        if (message == null || message.isBlank()) {
            return prompt;
        }
        return message + "\n\n" + prompt;
    }

    private void clearPriorManagedSessionsIfNeeded(OpenCodeServerHandle server, Path repo, String title, int requestTimeoutSeconds) throws InterruptedException {
        List<String> prefixes = managedSessionTitlePrefixes(title);
        if (prefixes.isEmpty()) {
            return;
        }
        String key = server.serverUrl() + "|" + repo.toAbsolutePath().normalize() + "|" + String.join(",", prefixes);
        if (!cleanedManagedSessionScopes.add(key)) {
            return;
        }
        try {
            int deleted = client.deleteSessionsByTitlePrefixes(server.serverUrl(), repo, prefixes, requestTimeoutSeconds);
            log.info("OpenCode managed session cleanup completed before task startup: title={}, deletedCount={}", title, deleted);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            log.warn("OpenCode managed session cleanup failed before task startup; continuing: title={}, reason={}",
                    title, exception.toString());
        }
    }

    private List<String> managedSessionTitlePrefixes(String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        if (title.startsWith("smartesb-review-")) {
            return List.of("smartesb-review-");
        }
        if (title.startsWith("smartesb-reader-")) {
            return List.of("smartesb-reader-");
        }
        if (title.startsWith("git-report-")) {
            return List.of("git-report-");
        }
        if (title.startsWith("weekly-code-review-")) {
            return List.of("weekly-code-review-");
        }
        return List.of();
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

    private ValidationCheck validate(ValidationProbe validationProbe, Path runDir) throws IOException {
        try {
            ValidationCheck validation = validationProbe.validate();
            return validation == null ? ValidationCheck.failed("validation probe returned null for runDir=" + runDir) : validation;
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            return ValidationCheck.failed("validation probe failed for runDir=" + runDir + ": " + exception.getMessage());
        }
    }

    private WaitOutcome waitForValidationRound(
            OpenCodeServerHandle server,
            Path repo,
            Path runDir,
            ValidationProbe validationProbe,
            OpenCodeSession session,
            AtomicReference<String> lastState,
            RunMonitor monitor,
            int pollMillis,
            int timeoutMinutes,
            int validationSettleSeconds,
            int correctionRound
    ) throws Exception {
        AtomicReference<Instant> terminalSuccessSince = new AtomicReference<>();
        long validationSettleMillis = validationSettleMillis(validationSettleSeconds);
        return scheduledProbeWaiter.waitFor(
                () -> pollValidationOnce(server, repo, runDir, validationProbe, session, lastState, monitor, validationSettleMillis, correctionRound, terminalSuccessSince),
                result -> result != null,
                () -> validationTimedOut(server, repo, runDir, validationProbe, session, lastState.get(), monitor, correctionRound),
                Duration.ofMinutes(timeoutMinutes),
                Duration.ofMillis(Math.max(50, pollMillis))
        );
    }

    private WaitOutcome pollValidationOnce(
            OpenCodeServerHandle server,
            Path repo,
            Path runDir,
            ValidationProbe validationProbe,
            OpenCodeSession session,
            AtomicReference<String> lastState,
            RunMonitor monitor,
            long validationSettleMillis,
            int correctionRound,
            AtomicReference<Instant> terminalSuccessSince
    ) throws IOException {
        ValidationCheck validation = validate(validationProbe, runDir);
        if (validation.ok()) {
            monitor.write("completed_by_output", lastState.get(), false, false, correctionRound, "");
            return new WaitOutcome(result(server, session, false, true, false, lastState.get(), true, "", correctionRound), false);
        }

        OpenCodeSessionState sessionState = readSessionState(server.serverUrl(), repo, session.id(), lastState.get());
        String state = sessionState.state();
        lastState.set(state);
        int pollCount = monitor.recordPoll(state);
        monitor.write("running", state, false, false, correctionRound, validation.error());
        monitor.logHeartbeat(state, pollCount);
        if ((sessionState.terminal() && !sessionState.success()) || isTerminalFailure(state)) {
            monitor.write("server_terminal_failure", state, false, false, correctionRound, validation.error());
            return new WaitOutcome(result(server, session, false, false, false, state, false, validation.error(), correctionRound), false);
        }
        if ((sessionState.terminal() && sessionState.success()) || isTerminalSuccess(state)) {
            terminalSuccessSince.compareAndSet(null, Instant.now());
            monitor.write("server_terminal_success_waiting_for_output", state, false, false, correctionRound, validation.error());
            if (settleElapsed(terminalSuccessSince.get(), validationSettleMillis)) {
                return new WaitOutcome(result(server, session, false, false, false, state, false, validation.error(), correctionRound), true);
            }
        }
        return null;
    }

    private WaitOutcome validationTimedOut(
            OpenCodeServerHandle server,
            Path repo,
            Path runDir,
            ValidationProbe validationProbe,
            OpenCodeSession session,
            String lastState,
            RunMonitor monitor,
            int correctionRound
    ) {
        ValidationCheck validation;
        try {
            validation = validate(validationProbe, runDir);
        } catch (IOException exception) {
            validation = ValidationCheck.failed(exception.getMessage());
        }
        boolean aborted = client.abortSession(server.serverUrl(), repo, session.id());
        monitor.writeQuietly("timeout", lastState, true, aborted, correctionRound, validation.error());
        return new WaitOutcome(result(server, session, true, false, aborted, lastState, validation.ok(), validation.error(), correctionRound), false);
    }

    private long validationSettleMillis(int validationSettleSeconds) {
        if (validationSettleSeconds <= 0) {
            return 250;
        }
        return Duration.ofSeconds(validationSettleSeconds).toMillis();
    }

    private boolean settleElapsed(Instant terminalSuccessSince, long validationSettleMillis) {
        return terminalSuccessSince != null && Duration.between(terminalSuccessSince, Instant.now()).toMillis() >= validationSettleMillis;
    }

    private String correctionMessage(Path promptFile, String validationError, int correctionRound, int maxCorrections) {
        return """
                Java 产物校验失败，请在当前 OpenCode session 继续完成原任务，不要创建新 session，不要只回复说明。

                要求：
                - 只修正原任务要求的目标文件；不要创建、移动、重命名或删除无关文件。
                - 如果文件未写完，继续写完；如果格式或字段不符合要求，直接修改目标文件内容。
                - 保留原 prompt 的边界、模板标题结构、路径载荷和受控读写规则。
                - 完成后按原 prompt 的要求只输出 DONE 或 BLOCKED。

                原 prompt 文件：%s
                纠正轮次：%d/%d
                校验错误：%s
                """.formatted(promptFile, correctionRound, maxCorrections, validationError == null || validationError.isBlank() ? "unknown validation failure" : validationError);
    }

    private OpenCodeRunResult result(
            OpenCodeServerHandle server,
            OpenCodeSession session,
            boolean timedOut,
            boolean completedByOutput,
            boolean aborted,
            String serverState,
            boolean validationOk,
            String validationError,
            int correctionRound
    ) {
        return new OpenCodeRunResult(
                session.id(),
                server.serverUrl().toString(),
                server.ownedByJava(),
                timedOut,
                completedByOutput,
                aborted,
                serverState,
                validationOk,
                validationError,
                correctionRound
        );
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
        String state = readSessionState(server.serverUrl(), repo, session.id(), lastState.get()).state();
        lastState.set(state);
        int pollCount = monitor.recordPoll(state);
        monitor.write("running", state, false, false);
        monitor.logHeartbeat(state, pollCount);
        if (isTerminalFailure(state)) {
            monitor.write("server_terminal_failure", state, false, false);
            return new OpenCodeRunResult(session.id(), server.serverUrl().toString(), server.ownedByJava(), false, false, false, state);
        }
        if (isTerminalSuccess(state)) {
            monitor.write("server_terminal_success_waiting_for_output", state, false, false);
        }
        return null;
    }

    private OpenCodeRunResult abortTimedOut(OpenCodeServerHandle server, Path repo, OpenCodeSession session, String lastState, RunMonitor monitor) {
        boolean aborted = client.abortSession(server.serverUrl(), repo, session.id());
        monitor.writeQuietly("timeout", lastState, true, aborted);
        return new OpenCodeRunResult(session.id(), server.serverUrl().toString(), server.ownedByJava(), true, false, aborted, lastState);
    }

    private OpenCodeSessionState readSessionState(URI serverUrl, Path repo, String sessionId, String fallback) {
        try {
            OpenCodeSessionState status = client.getSessionState(serverUrl, repo, sessionId);
            if (status.state().isBlank()) {
                return new OpenCodeSessionState(fallback, false, false, status.source(), status.finalText());
            }
            return status;
        } catch (Exception exception) {
            log.debug("Unable to read OpenCode session status: sessionId={}, reason={}", sessionId, exception.getMessage());
            return new OpenCodeSessionState(fallback, false, false, "fallback", "");
        }
    }

    private boolean isTerminalSuccess(String state) {
        String normalized = state == null ? "" : state.toLowerCase(Locale.ROOT);
        return normalized.equals("idle") || normalized.equals("completed") || normalized.equals("complete") || normalized.equals("done");
    }

    private boolean isTerminalFailure(String state) {
        String normalized = state == null ? "" : state.toLowerCase(Locale.ROOT);
        return normalized.equals("failed") || normalized.equals("error") || normalized.equals("aborted") || normalized.equals("blocked") || normalized.equals("canceled") || normalized.equals("cancelled");
    }

    private record WaitOutcome(OpenCodeRunResult result, boolean correctable) {
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
            write(phase, serverState, timedOut, aborted, 0, "");
        }

        private void write(String phase, String serverState, boolean timedOut, boolean aborted, int correctionRound, String validationError) {
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

        private void writeQuietly(String phase, String serverState, boolean timedOut, boolean aborted) {
            write(phase, serverState, timedOut, aborted);
        }

        private void writeQuietly(String phase, String serverState, boolean timedOut, boolean aborted, int correctionRound, String validationError) {
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
}
