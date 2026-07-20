package com.sonnet.wyf.gitreport.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.GitReportProperties;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeRunResult;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeTaskRunner;
import com.sonnet.wyf.gitreport.agentbridge.ValidationCheck;
import com.sonnet.wyf.gitreport.agentbridge.ValidatedAgentBridgeTaskSpec;
import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactContext;
import com.sonnet.wyf.gitreport.prompt.PromptBuilder;
import com.sonnet.wyf.gitreport.validation.FinalReportValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

final class GitReportSynthesisWorkflow {
    private static final Logger log = LoggerFactory.getLogger(GitReportSynthesisWorkflow.class);
    private static final String FINAL_REPORT_TEMPLATE = "git-report-prompt-pack/templates/code-contribution-report.md";

    private final ObjectMapper objectMapper;
    private final PromptBuilder promptBuilder;
    private final AgentBridgeTaskRunner taskRunner;
    private final FinalReportValidator finalReportValidator;
    private final SynthesisInputWriter synthesisInputWriter;
    private final RunStatusRepository statusRepository;

    GitReportSynthesisWorkflow(
            ObjectMapper objectMapper,
            PromptBuilder promptBuilder,
            AgentBridgeTaskRunner taskRunner,
            FinalReportValidator finalReportValidator,
            SynthesisInputWriter synthesisInputWriter,
            RunStatusRepository statusRepository
    ) {
        this.objectMapper = objectMapper;
        this.promptBuilder = promptBuilder;
        this.taskRunner = taskRunner;
        this.finalReportValidator = finalReportValidator;
        this.synthesisInputWriter = synthesisInputWriter;
        this.statusRepository = statusRepository;
    }

    void run(GitReportProperties properties, Path out, Path qualityScores) throws Exception {
        Path runDir = WorkflowArtifactContext
                .nextSynthesisAttempt("git-report", out.resolve("runs").resolve("synthesis"))
                .root();
        Files.createDirectories(runDir);
        Path finalReport = out.resolve("code-contribution-report.md");
        Files.writeString(finalReport, readResource(FINAL_REPORT_TEMPLATE));
        Map<String, Object> summary = readMap(out.resolve("summary.json"));
        Map<String, Object> indexInputs = readMap(out.resolve("index_inputs.json"));
        Map<String, Object> qualityScoreInputs = readMap(qualityScores);
        Path synthesisInputs = synthesisInputWriter.write(
                runDir.resolve("synthesis-inputs.json"),
                summary,
                indexInputs,
                qualityScoreInputs,
                properties.getSynthesisInput()
        );
        Path promptFile = runDir.resolve("synthesis-prompt.md");
        String prompt = promptBuilder.buildSynthesisPrompt(synthesisInputs);
        Files.writeString(promptFile, prompt);
        log.info("Starting synthesis task: prompt={}, synthesisInputs={}, qualityScores={}, finalReport={}",
                promptFile,
                synthesisInputs,
                qualityScores,
                out.resolve("code-contribution-report.md"));
        AgentBridgeRunResult result = taskRunner.runUntilValidated(new ValidatedAgentBridgeTaskSpec(
                properties.getPaths().getRepo(),
                "git-report-synthesis",
                promptFile,
                properties.getAgentbridge().getSynthesisTaskMessage(),
                runDir,
                () -> finalReportValidationCheck(finalReport),
                properties.getAgentbridge().getPollMillis(),
                properties.getAgentbridge().getTimeoutMinutes(),
                properties.getAgentbridge().getValidationSettleSeconds(),
                properties.getAgentbridge().getValidationMaxCorrections(),
                java.net.URI.create(properties.getAgentbridge().getWebBaseUrl())
        ));
        FinalReportValidator.Validation finalReportValidation = finalReportValidator.validate(finalReport);
        boolean ok = finalReportValidation.ok();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", ok ? "completed" : "failed");
        status.put("taskId", result.taskId());
        status.put("agentbridgeWebBaseUrl", result.webBaseUrl());
        status.put("timedOut", result.timedOut());
        status.put("completedByOutput", result.completedByOutput());
        status.put("agentState", result.agentState());
        status.put("finalReportOk", ok);
        status.put("finalReportError", finalReportValidation.error());
        status.put("synthesisInputs", synthesisInputs.toString());
        status.put("finishedAt", OffsetDateTime.now().toString());
        statusRepository.write(runDir.resolve("status.json"), status);
        if (!ok) {
            log.error("Synthesis failed: taskId={}, timedOut={}, finalReportOk={}, error=\"{}\", status={}",
                    result.taskId(),
                    result.timedOut(),
                    ok,
                    finalReportValidation.error(),
                    runDir.resolve("status.json"));
            throw new IllegalStateException("synthesis failed: " + finalReportValidation.error());
        }
        log.info("Synthesis completed: finalReport={}", finalReport);
    }

    private ValidationCheck finalReportValidationCheck(Path finalReport) {
        FinalReportValidator.Validation validation = finalReportValidator.validate(finalReport);
        return validation.ok() ? ValidationCheck.success() : ValidationCheck.failed(validation.error());
    }

    private String readResource(String resourcePath) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("resource missing: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private Map<String, Object> readMap(Path path) throws IOException {
        return objectMapper.readValue(path.toFile(), new TypeReference<>() {});
    }
}
