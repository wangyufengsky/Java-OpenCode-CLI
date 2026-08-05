package com.sonnet.wyf.gitreport.agentbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.console.WorkflowEventSink;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.failure.GlobalWorkflowExceptionHandler;
import com.sonnet.wyf.gitreport.failure.WorkflowFailureCategory;
import com.sonnet.wyf.gitreport.failure.WorkflowFailureException;
import com.sonnet.wyf.gitreport.failure.WorkflowFailureScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

public class AgentBridgeTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentBridgeTaskRunner.class);

    private final AgentBridgeClient client;
    private final ScheduledProbeWaiter scheduledProbeWaiter;
    private final WorkflowEventSink eventSink;
    private final ObjectMapper objectMapper;
    private final GlobalWorkflowExceptionHandler failureHandler = new GlobalWorkflowExceptionHandler();

    public AgentBridgeTaskRunner(AgentBridgeClient client, ScheduledProbeWaiter scheduledProbeWaiter) {
        this(client, scheduledProbeWaiter, new WorkflowEventSink(), new ObjectMapper());
    }

    public AgentBridgeTaskRunner(
            AgentBridgeClient client,
            ScheduledProbeWaiter scheduledProbeWaiter,
            WorkflowEventSink eventSink,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.scheduledProbeWaiter = scheduledProbeWaiter;
        this.eventSink = eventSink;
        this.objectMapper = objectMapper;
    }

    public AgentBridgeRunResult runUntilValidated(ValidatedAgentBridgeTaskSpec spec) throws Exception {
        Files.createDirectories(spec.runDir());
        String prompt = Files.readString(spec.promptFile());
        String text = composeMessage(spec.message(), prompt);
        AgentBridgeRunMonitor monitor = new AgentBridgeRunMonitor(
                spec.webBaseUrl(),
                spec.title(),
                spec.promptFile(),
                spec.runDir(),
                Math.max(50, spec.pollMillis()),
                eventSink,
                objectMapper
        );
        log.info("Starting AgentBridge task: taskId={}, title={}, runDir={}, timeoutMinutes={}, validationSettleSeconds={}, validationMaxCorrections={}",
                monitor.taskId(), spec.title(), spec.runDir(), spec.timeoutMinutes(), spec.validationSettleSeconds(), spec.validationMaxCorrections());
        monitor.write("created", "created", false);
        int maxCorrections = Math.max(0, spec.validationMaxCorrections());
        int correctionRound = 0;
        String sessionMessage = text;
        while (true) {
            try {
                client.clearSession(spec.webBaseUrl());
                client.postPrompt(spec.webBaseUrl(), sessionMessage);
                monitor.write("running", "submitted", false, correctionRound, "");

                AgentBridgeRunResult result = waitForValidationRound(spec, monitor, correctionRound);
                if (result.validationOk() || correctionRound >= maxCorrections) {
                    if (!result.validationOk()) {
                        recordSessionFailure(
                                spec,
                                correctionRound + 1,
                                WorkflowFailureCategory.OUTPUT_VALIDATION,
                                result.validationError()
                        );
                        monitor.write("validation_failed_final", result.agentState(), result.timedOut(), correctionRound, result.validationError());
                    }
                    return result;
                }

                recordSessionFailure(
                        spec,
                        correctionRound + 1,
                        WorkflowFailureCategory.OUTPUT_VALIDATION,
                        result.validationError()
                );
                correctionRound++;
                sessionMessage = correctionMessage(
                        spec.promptFile(),
                        text,
                        result.validationError(),
                        correctionRound,
                        maxCorrections
                );
                monitor.write("session_failed_retrying", result.agentState(), false, correctionRound, result.validationError());
                log.warn("AgentBridge session validation failed; starting a fresh session: taskId={}, title={}, correctionRound={}/{}, reason=\"{}\"",
                        monitor.taskId(), spec.title(), correctionRound, maxCorrections, result.validationError());
            } catch (Exception exception) {
                WorkflowFailureException failure = failureHandler.sessionFailure(
                        sessionFailureCategory(exception),
                        exception
                );
                if (failure.scope() == WorkflowFailureScope.TASK) {
                    throw failure;
                }
                boolean timedOut = failure.category() == WorkflowFailureCategory.SESSION_TIMEOUT;
                String failedState = timedOut ? "timeout" : "failed";
                recordSessionFailure(spec, correctionRound + 1, failure.category(), failure.getMessage());
                monitor.write(
                        timedOut ? "timeout" : "session_failed",
                        failedState,
                        timedOut,
                        correctionRound,
                        failure.getMessage()
                );
                if (correctionRound >= maxCorrections) {
                    monitor.write(
                            timedOut ? "timeout" : "validation_failed_final",
                            failedState,
                            timedOut,
                            correctionRound,
                            failure.getMessage()
                    );
                    return result(
                            spec,
                            monitor,
                            timedOut,
                            false,
                            failedState,
                            false,
                            failure.getMessage(),
                            correctionRound
                    );
                }
                correctionRound++;
                sessionMessage = text;
                monitor.write("session_failed_retrying", "failed", false, correctionRound, failure.getMessage());
                log.warn("AgentBridge session failed; starting a fresh session: taskId={}, title={}, retry={}/{}, reason=\"{}\"",
                        monitor.taskId(), spec.title(), correctionRound, maxCorrections, failure.getMessage());
            }
        }
    }

    private WorkflowFailureCategory sessionFailureCategory(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AgentBridgeTimeoutException) {
                return WorkflowFailureCategory.SESSION_TIMEOUT;
            }
            current = current.getCause();
        }
        return WorkflowFailureCategory.SESSION_EXECUTION;
    }

    private AgentBridgeRunResult waitForValidationRound(
            ValidatedAgentBridgeTaskSpec spec,
            AgentBridgeRunMonitor monitor,
            int correctionRound
    ) throws Exception {
        Duration timeout = Duration.ofMinutes(spec.timeoutMinutes());
        Duration pollInterval = Duration.ofMillis(Math.max(50, spec.pollMillis()));
        while (true) {
            client.waitUntilIdle(spec.webBaseUrl(), timeout, pollInterval);
            settle(spec.validationSettleSeconds());
            if (!client.isRunning(spec.webBaseUrl())) {
                break;
            }
            log.info("AgentBridge resumed during validation settle window; waiting again: taskId={}, title={}",
                    monitor.taskId(), spec.title());
        }

        int pollCount = monitor.recordPoll("idle");
        monitor.logHeartbeat("idle", pollCount);
        ValidationCheck validation = validate(spec.validationProbe(), spec.runDir());
        if (validation.ok()) {
            monitor.write("completed_by_output", "idle", false, correctionRound, "");
            return result(spec, monitor, false, true, "idle", true, "", correctionRound);
        }
        monitor.write("validation_failed_waiting_for_correction", "idle", false, correctionRound, validation.error());
        return result(spec, monitor, false, false, "idle", false, validation.error(), correctionRound);
    }

    private String composeMessage(String message, String prompt) {
        if (message == null || message.isBlank()) {
            return prompt;
        }
        return message + "\n\n" + prompt;
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

    private void settle(int validationSettleSeconds) throws InterruptedException {
        long millis = validationSettleSeconds <= 0 ? 0 : Duration.ofSeconds(validationSettleSeconds).toMillis();
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private String correctionMessage(
            Path promptFile,
            String originalTask,
            String validationError,
            int correctionRound,
            int maxCorrections
    ) {
        return """
                以下已重新附上首轮会话的完整原任务内容。不要读取原 prompt 文件，直接基于本消息中的完整内容继续。

                ===== ORIGINAL TASK BEGIN =====
                %s
                ===== ORIGINAL TASK END =====

                Java 产物校验失败，请继续完成同一个 AgentBridge 任务，不要只回复说明。

                要求：
                - 只修正原任务要求的目标文件。
                - 保留原 prompt 的任务边界、路径载荷和输出结构。
                - 完成后回复简短完成信息即可，Java 会重新验收。

                原 prompt 文件：%s
                纠正轮次：%d/%d
                校验错误：%s
                """.formatted(
                        originalTask,
                        promptFile,
                        correctionRound,
                        maxCorrections,
                        validationError == null || validationError.isBlank()
                                ? "unknown validation failure"
                                : validationError
                );
    }

    private void recordSessionFailure(
            ValidatedAgentBridgeTaskSpec spec,
            int sessionAttempt,
            WorkflowFailureCategory category,
            String error
    ) throws IOException {
        Path directory = spec.runDir()
                .resolve("session-attempts")
                .resolve("%03d".formatted(sessionAttempt));
        Files.createDirectories(directory);
        var record = objectMapper.createObjectNode();
        record.put("schema_version", "workflow-session-failure/v1");
        record.put("scope", WorkflowFailureScope.SESSION.name());
        record.put("category", category.name());
        record.put("session_attempt", sessionAttempt);
        record.put("recorded_at", Instant.now().toString());
        record.put("error", error == null || error.isBlank() ? "unknown session failure" : error);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve("session-failure.json").toFile(), record);
    }

    private AgentBridgeRunResult result(
            ValidatedAgentBridgeTaskSpec spec,
            AgentBridgeRunMonitor monitor,
            boolean timedOut,
            boolean completedByOutput,
            String agentState,
            boolean validationOk,
            String validationError,
            int correctionRound
    ) {
        return new AgentBridgeRunResult(
                monitor.taskId(),
                spec.webBaseUrl().toString(),
                timedOut,
                completedByOutput,
                agentState,
                validationOk,
                validationError,
                correctionRound
        );
    }
}
