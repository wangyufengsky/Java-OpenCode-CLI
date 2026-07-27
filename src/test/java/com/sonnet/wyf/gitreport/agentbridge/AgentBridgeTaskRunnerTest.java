package com.sonnet.wyf.gitreport.agentbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.console.WorkflowEventSink;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentBridgeTaskRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void postsPromptAndReturnsSuccessWhenValidationPasses() throws Exception {
        Path promptFile = writePrompt("PROMPT");
        RecordingClient client = new RecordingClient();
        AgentBridgeTaskRunner runner = runner(client);

        AgentBridgeRunResult result = runner.runUntilValidated(spec(
                promptFile,
                "MESSAGE",
                () -> ValidationCheck.success(),
                0
        ));

        assertThat(client.prompts).containsExactly("/session-clear", "MESSAGE\n\nPROMPT");
        assertThat(result.validationOk()).isTrue();
        assertThat(result.completedByOutput()).isTrue();
        assertThat(Files.readString(tempDir.resolve("run").resolve("agent-status.json")))
                .contains("\"phase\" : \"completed_by_output\"")
                .contains("\"taskId\"")
                .contains("\"agentbridgeWebBaseUrl\"");
    }

    @Test
    void sendsCorrectionPromptWhenValidationFails() throws Exception {
        Path promptFile = writePrompt("PROMPT");
        RecordingClient client = new RecordingClient();
        AtomicInteger validations = new AtomicInteger();
        AgentBridgeTaskRunner runner = runner(client);

        AgentBridgeRunResult result = runner.runUntilValidated(spec(
                promptFile,
                "MESSAGE",
                () -> validations.getAndIncrement() == 0
                        ? ValidationCheck.failed("summary missing")
                        : ValidationCheck.success(),
                1
        ));

        assertThat(result.validationOk()).isTrue();
        assertThat(result.correctionRounds()).isEqualTo(1);
        assertThat(client.prompts).hasSize(4);
        assertThat(client.prompts.get(0)).isEqualTo("/session-clear");
        assertThat(client.prompts.get(2)).isEqualTo("/session-clear");
        assertThat(client.prompts.get(3))
                .contains("summary missing")
                .contains("原 prompt 文件：" + promptFile)
                .contains("AgentBridge 任务")
                .doesNotContain("OpenCode");
        assertThat(tempDir.resolve("run/session-attempts/001/session-failure.json")).content()
                .contains("\"scope\" : \"SESSION\"")
                .contains("\"category\" : \"OUTPUT_VALIDATION\"")
                .contains("summary missing");
    }

    @Test
    void returnsFailedAfterCorrectionBudget() throws Exception {
        Path promptFile = writePrompt("PROMPT");
        RecordingClient client = new RecordingClient();
        AgentBridgeTaskRunner runner = runner(client);

        AgentBridgeRunResult result = runner.runUntilValidated(spec(
                promptFile,
                "MESSAGE",
                () -> ValidationCheck.failed("summary missing"),
                1
        ));

        assertThat(client.prompts).hasSize(4);
        assertThat(client.prompts.get(0)).isEqualTo("/session-clear");
        assertThat(client.prompts.get(2)).isEqualTo("/session-clear");
        assertThat(result.validationOk()).isFalse();
        assertThat(result.validationError()).isEqualTo("summary missing");
        assertThat(result.correctionRounds()).isEqualTo(1);
        assertThat(Files.readString(tempDir.resolve("run").resolve("agent-status.json")))
                .contains("\"phase\" : \"validation_failed_final\"");
    }

    @Test
    void startsNewSessionAfterExecutionException() throws Exception {
        Path promptFile = writePrompt("PROMPT");
        RecordingClient client = new RecordingClient();
        client.failFirstWait = true;
        AgentBridgeTaskRunner runner = runner(client);

        AgentBridgeRunResult result = runner.runUntilValidated(spec(
                promptFile,
                "MESSAGE",
                () -> ValidationCheck.success(),
                1
        ));

        assertThat(result.validationOk()).isTrue();
        assertThat(client.prompts).containsExactly(
                "/session-clear",
                "MESSAGE\n\nPROMPT",
                "/session-clear",
                "MESSAGE\n\nPROMPT"
        );
    }

    private Path writePrompt(String text) throws Exception {
        Path promptFile = tempDir.resolve("worker-prompt.md");
        Files.writeString(promptFile, text);
        return promptFile;
    }

    private ValidatedAgentBridgeTaskSpec spec(
            Path promptFile,
            String message,
            ValidationProbe validationProbe,
            int validationMaxCorrections
    ) {
        return new ValidatedAgentBridgeTaskSpec(
                tempDir,
                "agent-task",
                promptFile,
                message,
                tempDir.resolve("run"),
                validationProbe,
                1,
                1,
                0,
                validationMaxCorrections,
                URI.create("http://127.0.0.1:9642")
        );
    }

    private AgentBridgeTaskRunner runner(RecordingClient client) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        return new AgentBridgeTaskRunner(client, new ScheduledProbeWaiter(scheduler), new WorkflowEventSink(), new ObjectMapper());
    }

    private static final class RecordingClient extends AgentBridgeClient {
        private final List<String> prompts = new ArrayList<>();
        private boolean failFirstWait;
        private int waits;

        RecordingClient() {
            super(new ObjectMapper());
        }

        @Override
        public void postPrompt(URI webBaseUrl, String prompt) {
            prompts.add(prompt);
        }

        @Override
        public void waitUntilIdle(URI webBaseUrl, Duration timeout, Duration pollInterval) {
            if (failFirstWait && ++waits == 1) {
                throw new IllegalStateException("temporary AgentBridge failure");
            }
        }
    }
}
