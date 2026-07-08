package com.sonnet.wyf.gitreport.agentbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.console.WorkflowEventSink;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class AgentBridgeTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentBridgeTaskRunner.class);

    private final AgentBridgeClient client;
    private final ScheduledProbeWaiter scheduledProbeWaiter;
    private final WorkflowEventSink eventSink;
    private final ObjectMapper objectMapper;

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
        client.clearSession(spec.webBaseUrl());
        client.postPrompt(spec.webBaseUrl(), text);
        monitor.write("running", "submitted", false);

        int maxCorrections = Math.max(0, spec.validationMaxCorrections());
        int correctionRound = 0;
        while (true) {
            AgentBridgeRunResult result = waitForValidationRound(spec, monitor, correctionRound);
            if (result.validationOk() || result.timedOut() || correctionRound >= maxCorrections) {
                if (!result.validationOk()) {
                    monitor.write("validation_failed_final", result.agentState(), result.timedOut(), correctionRound, result.validationError());
                }
                return result;
            }
            correctionRound++;
            String correction = correctionMessage(spec.promptFile(), result.validationError(), correctionRound, maxCorrections);
            monitor.write("validation_failed_correction_sent", result.agentState(), false, correctionRound, result.validationError());
            log.warn("AgentBridge validation failed; sending correction prompt: taskId={}, title={}, correctionRound={}/{}, reason=\"{}\"",
                    monitor.taskId(), spec.title(), correctionRound, maxCorrections, result.validationError());
            client.postPrompt(spec.webBaseUrl(), correction);
            monitor.write("running", "submitted", false, correctionRound, result.validationError());
        }
    }

    private AgentBridgeRunResult waitForValidationRound(
            ValidatedAgentBridgeTaskSpec spec,
            AgentBridgeRunMonitor monitor,
            int correctionRound
    ) throws Exception {
        try {
            client.waitUntilIdle(
                    spec.webBaseUrl(),
                    Duration.ofMinutes(spec.timeoutMinutes()),
                    Duration.ofMillis(Math.max(50, spec.pollMillis()))
            );
        } catch (Exception exception) {
            ValidationCheck validation = validate(spec.validationProbe(), spec.runDir());
            monitor.write("timeout", "timeout", true, correctionRound, validation.error());
            return result(spec, monitor, true, false, "timeout", validation.ok(), validation.error(), correctionRound);
        }

        int pollCount = monitor.recordPoll("idle");
        monitor.logHeartbeat("idle", pollCount);
        settle(spec.validationSettleSeconds());
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

    private String correctionMessage(Path promptFile, String validationError, int correctionRound, int maxCorrections) {
        return """
                Java 产物校验失败，请继续完成同一个 AgentBridge 任务，不要只回复说明。

                要求：
                - 只修正原任务要求的目标文件。
                - 保留原 prompt 的任务边界、路径载荷和输出结构。
                - 完成后回复简短完成信息即可，Java 会重新验收。

                原 prompt 文件：%s
                纠正轮次：%d/%d
                校验错误：%s
                """.formatted(promptFile, correctionRound, maxCorrections, validationError == null || validationError.isBlank() ? "unknown validation failure" : validationError);
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
