package com.sonnet.wyf.gitreport.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.GitReportProperties;
import com.sonnet.wyf.gitreport.core.GitReportConstants;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.opencode.OpenCodeRunResult;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerHandle;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.preparation.GitReportPreparation;
import com.sonnet.wyf.gitreport.prompt.PromptBuilder;
import com.sonnet.wyf.gitreport.scoring.QualityScoresWriter;
import com.sonnet.wyf.gitreport.validation.AuthorOutputValidator;
import com.sonnet.wyf.gitreport.validation.AuthorValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public class GitReportOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(GitReportOrchestrator.class);
    private static final int OPENCODE_POLL_MILLIS = 10_000;

    private final GitReportPreparation preparation;
    private final ObjectMapper objectMapper;
    private final PromptBuilder promptBuilder;
    private final OpenCodeServerManager serverManager;
    private final OpenCodeServerTaskRunner taskRunner;
    private final AuthorOutputValidator outputValidator;
    private final QualityScoresWriter qualityScoresWriter;
    private final SynthesisInputWriter synthesisInputWriter;
    private final RunStatusRepository statusRepository;
    private final ScheduledProbeWaiter outputWaiter;
    private final AsyncTaskExecutor authorTaskExecutor;

    public GitReportOrchestrator(
            GitReportPreparation preparation,
            ObjectMapper objectMapper,
            PromptBuilder promptBuilder,
            OpenCodeServerManager serverManager,
            OpenCodeServerTaskRunner taskRunner,
            AuthorOutputValidator outputValidator,
            QualityScoresWriter qualityScoresWriter,
            SynthesisInputWriter synthesisInputWriter,
            RunStatusRepository statusRepository,
            ScheduledProbeWaiter outputWaiter,
            AsyncTaskExecutor authorTaskExecutor
    ) {
        this.preparation = preparation;
        this.objectMapper = objectMapper;
        this.promptBuilder = promptBuilder;
        this.serverManager = serverManager;
        this.taskRunner = taskRunner;
        this.outputValidator = outputValidator;
        this.qualityScoresWriter = qualityScoresWriter;
        this.synthesisInputWriter = synthesisInputWriter;
        this.statusRepository = statusRepository;
        this.outputWaiter = outputWaiter;
        this.authorTaskExecutor = authorTaskExecutor;
    }

    public void run(GitReportProperties properties) throws Exception {
        Path out = properties.getPaths().getOut().toAbsolutePath().normalize();
        OpenCodeServerHandle server = serverManager.ensureReady(properties, out);
        log.info("Git report orchestration started: projectId={}, projectName={}, runId={}, repo={}, out={}, serverUrl={}, serverOwnedByJava={}, concurrency={}, timeoutMinutes={}, outputWaitSeconds={}, maxRetries={}",
                properties.getProject().getId(),
                properties.getProject().getName(),
                properties.getProject().getRunId(),
                properties.getPaths().getRepo().toAbsolutePath().normalize(),
                out,
                server.serverUrl(),
                server.ownedByJava(),
                properties.getOpencode().getConcurrency(),
                properties.getOpencode().getTimeoutMinutes(),
                properties.getOpencode().getOutputWaitSeconds(),
                properties.getOpencode().getMaxRetries());
        preparation.prepare(properties);
        Map<String, Object> summary = readMap(out.resolve("summary.json"));
        Map<String, Object> indexInputs = readMap(out.resolve("index_inputs.json"));
        log.info("Loaded preparation outputs: summary={}, indexInputs={}, authorTaskCount={}",
                out.resolve("summary.json"),
                out.resolve("index_inputs.json"),
                listOfMaps(indexInputs.get("tasks")).size());
        runAuthorTasks(properties, out, indexInputs, server);
        Path qualityScores = qualityScoresWriter.write(out.resolve("quality-scores.json"), summary, indexInputs);
        runSynthesis(properties, out, qualityScores, server);
        log.info("Git report orchestration completed: finalReport={}", out.resolve("code-contribution-report.md"));
    }

    public void runSynthesisOnly(GitReportProperties properties) throws Exception {
        Path out = properties.getPaths().getOut().toAbsolutePath().normalize();
        OpenCodeServerHandle server = serverManager.ensureReady(properties, out);
        log.info("Git report synthesis-only orchestration started: projectId={}, projectName={}, runId={}, repo={}, out={}, serverUrl={}, serverOwnedByJava={}",
                properties.getProject().getId(),
                properties.getProject().getName(),
                properties.getProject().getRunId(),
                properties.getPaths().getRepo().toAbsolutePath().normalize(),
                out,
                server.serverUrl(),
                server.ownedByJava());
        Map<String, Object> summary = readMap(out.resolve("summary.json"));
        Map<String, Object> indexInputs = readMap(out.resolve("index_inputs.json"));
        validateExistingAuthorOutputs(indexInputs);
        Path qualityScores = qualityScoresWriter.write(out.resolve("quality-scores.json"), summary, indexInputs);
        runSynthesis(properties, out, qualityScores, server);
        log.info("Git report synthesis-only orchestration completed: finalReport={}", out.resolve("code-contribution-report.md"));
    }

    public void runSingleAuthor(GitReportProperties properties, String authorKey) throws Exception {
        if (authorKey == null || authorKey.isBlank()) {
            throw new IllegalArgumentException("authorKey is required for git-report author rerun");
        }
        Path out = properties.getPaths().getOut().toAbsolutePath().normalize();
        OpenCodeServerHandle server = serverManager.ensureReady(properties, out);
        Map<String, Object> summary = readMap(out.resolve("summary.json"));
        Map<String, Object> indexInputs = readMap(out.resolve("index_inputs.json"));
        Map<String, Object> target = listOfMaps(indexInputs.get("tasks")).stream()
                .filter(task -> authorKey.equals(task.get("author_key")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("author task not found in index_inputs.json: " + authorKey));
        log.info("Git report single-author rerun started: authorKey={}, author={}, out={}, repo={}",
                target.get("author_key"),
                target.get("author"),
                out,
                properties.getPaths().getRepo().toAbsolutePath().normalize());
        AuthorTaskResult result = authorCallable(properties, out, target, server).call();
        if (!result.success()) {
            throw new IllegalStateException("author task failed: " + result.authorKey() + " (" + result.author() + "), status=" + result.statusPath() + ", error=" + result.error());
        }
        validateExistingAuthorOutputs(indexInputs);
        Path qualityScores = qualityScoresWriter.write(out.resolve("quality-scores.json"), summary, indexInputs);
        runSynthesis(properties, out, qualityScores, server);
        log.info("Git report single-author rerun completed: authorKey={}, finalReport={}", authorKey, out.resolve("code-contribution-report.md"));
    }

    private void validateExistingAuthorOutputs(Map<String, Object> indexInputs) {
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> task : listOfMaps(indexInputs.get("tasks"))) {
            Path report = Path.of(task.get("report_md").toString());
            Path qualitySummary = Path.of(task.get("quality_summary_json").toString());
            AuthorValidationResult validation = outputValidator.validate(report, qualitySummary);
            if (!validation.ok()) {
                failures.add(task.get("author_key") + " (" + task.get("author") + "): " + validation.error());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("existing author outputs are incomplete: " + String.join("; ", failures));
        }
    }

    private void runAuthorTasks(GitReportProperties properties, Path out, Map<String, Object> indexInputs, OpenCodeServerHandle server) throws Exception {
        List<Map<String, Object>> tasks = listOfMaps(indexInputs.get("tasks"));
        int concurrency = Math.max(1, Math.min(properties.getOpencode().getConcurrency(), properties.getOpencode().getMaxConcurrency()));
        log.info("Starting author tasks: taskCount={}, concurrency={}", tasks.size(), concurrency);
        List<Future<AuthorTaskResult>> futures = new ArrayList<>();
        for (Map<String, Object> task : tasks) {
            futures.add(authorTaskExecutor.submit(authorCallable(properties, out, task, server)));
        }
        List<AuthorTaskResult> failures = new ArrayList<>();
        for (Future<AuthorTaskResult> future : futures) {
            AuthorTaskResult result = future.get();
            if (!result.success()) {
                failures.add(result);
            }
        }
        if (!failures.isEmpty()) {
            String summary = failures.stream()
                    .map(result -> result.authorKey() + " (" + result.author() + "), status=" + result.statusPath() + ", error=" + result.error())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
            String firstReason = failures.get(0).error();
            log.error("AUTHOR_FAILURE_SUMMARY firstReason=\"{}\" failedCount={} failures={}", firstReason, failures.size(), summary);
            throw new IllegalStateException("author task failed: " + summary);
        }
        log.info("All author tasks completed successfully");
    }

    private Callable<AuthorTaskResult> authorCallable(GitReportProperties properties, Path out, Map<String, Object> task, OpenCodeServerHandle server) {
        return () -> {
            String authorKey = task.get("author_key").toString();
            String author = task.get("author").toString();
            Path runDir = out.resolve("runs").resolve(authorKey);
            Path statusPath = runDir.resolve("status.json");
            int attempts = Math.max(1, properties.getOpencode().getMaxRetries() + 1);
            String lastError = "";
            for (int attempt = 1; attempt <= attempts; attempt++) {
                log.info("Author task started: authorKey={}, author={}, attempt={}/{}", authorKey, author, attempt, attempts);
                String state = "failed";
                String error = "";
                boolean timedOut = false;
                OpenCodeRunResult runResult = null;
                try {
                    Files.createDirectories(runDir);
                    Path promptFile = runDir.resolve("worker-prompt.md");
                    String prompt = promptBuilder.buildWorkerPrompt(Path.of(task.get("detail_json").toString()));
                    Files.writeString(promptFile, prompt);
                    Path reportMd = Path.of(task.get("report_md").toString());
                    Path qualitySummaryJson = Path.of(task.get("quality_summary_json").toString());
                    runResult = taskRunner.runUntil(
                            server,
                            properties.getPaths().getRepo(),
                            "git-report-" + authorKey,
                            promptFile,
                            properties.getOpencode().getWorkerMessage(),
                            runDir,
                            () -> outputValidator.validate(reportMd, qualitySummaryJson).ok(),
                            properties.getOpencode().getSessionModel(),
                            properties.getOpencode().getRequestTimeoutSeconds(),
                            OPENCODE_POLL_MILLIS,
                            properties.getOpencode().getTimeoutMinutes()
                    );
                    timedOut = runResult.timedOut();
                    AuthorValidationResult validation = waitForAuthorOutputs(
                            reportMd,
                            qualitySummaryJson,
                            properties.getOpencode().getOutputWaitSeconds()
                    );
                    if (validation.ok()) {
                        state = "completed";
                        writeAuthorStatus(statusPath, task, attempt, state, timedOut, runResult, "");
                        log.info("Author task completed: authorKey={}, author={}, attempt={}, sessionId={}, timedOut={}, completedByOutput={}, serverState={}, report={}, qualitySummary={}",
                                authorKey,
                                author,
                                attempt,
                                runResult.sessionId(),
                                timedOut,
                                runResult.completedByOutput(),
                                runResult.serverState(),
                                task.get("report_md"),
                                task.get("quality_summary_json"));
                        return AuthorTaskResult.success(authorKey, author, statusPath);
                    }
                    state = timedOut ? "timeout" : "failed";
                    error = validation.error();
                    if (!error.isBlank()) {
                        log.warn("AUTHOR_VALIDATION_FAILED reason=\"{}\" authorKey={} author={} attempt={}", error, authorKey, author, attempt);
                    }
                } catch (Exception exception) {
                    error = sanitizeOpenCodeError(exception);
                    log.warn("AUTHOR_ATTEMPT_EXCEPTION reason=\"{}\" authorKey={} author={} attempt={}",
                            error, authorKey, author, attempt, exception);
                }
                lastError = error;
                writeAuthorStatus(statusPath, task, attempt, state, timedOut, runResult, error);
                log.warn("AUTHOR_ATTEMPT_FAILED reason=\"{}\" state={} timedOut={} authorKey={} author={} attempt={}",
                        error, state, timedOut, authorKey, author, attempt);
            }
            log.error("AUTHOR_FAILED reason=\"{}\" status={} authorKey={} author={} attempts={}", lastError, statusPath, authorKey, author, attempts);
            return AuthorTaskResult.failed(authorKey, author, statusPath, lastError);
        };
    }

    private AuthorValidationResult waitForAuthorOutputs(Path reportMd, Path qualitySummaryJson, int outputWaitSeconds) throws Exception {
        return outputWaiter.waitFor(
                () -> outputValidator.validate(reportMd, qualitySummaryJson),
                AuthorValidationResult::ok,
                () -> outputValidator.validate(reportMd, qualitySummaryJson),
                Duration.ofSeconds(Math.max(0, outputWaitSeconds)),
                Duration.ofSeconds(2)
        );
    }

    private String sanitizeOpenCodeError(Exception exception) {
        return exception.getClass().getName() + ": " + (exception.getMessage() == null ? "" : exception.getMessage());
    }

    private void runSynthesis(GitReportProperties properties, Path out, Path qualityScores, OpenCodeServerHandle server) throws Exception {
        Path runDir = out.resolve("runs").resolve("synthesis");
        Files.createDirectories(runDir);
        Path finalReport = out.resolve("code-contribution-report.md");
        Files.writeString(finalReport, GitReportConstants.REPORT_MARKER + "\n");
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
        OpenCodeRunResult result = taskRunner.runUntil(
                server,
                properties.getPaths().getRepo(),
                "git-report-synthesis",
                promptFile,
                properties.getOpencode().getSynthesisMessage(),
                runDir,
                () -> finalReportReady(finalReport),
                properties.getOpencode().getSessionModel(),
                properties.getOpencode().getRequestTimeoutSeconds(),
                OPENCODE_POLL_MILLIS,
                properties.getOpencode().getTimeoutMinutes()
        );
        boolean ok = waitForFinalReport(finalReport, properties.getOpencode().getOutputWaitSeconds());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", ok ? "completed" : "failed");
        status.put("sessionId", result.sessionId());
        status.put("serverUrl", result.serverUrl());
        status.put("serverOwnedByJava", result.serverOwnedByJava());
        status.put("timedOut", result.timedOut());
        status.put("completedByOutput", result.completedByOutput());
        status.put("aborted", result.aborted());
        status.put("serverState", result.serverState());
        status.put("finalReportOk", ok);
        status.put("synthesisInputs", synthesisInputs.toString());
        status.put("finishedAt", OffsetDateTime.now().toString());
        statusRepository.write(runDir.resolve("status.json"), status);
        if (!ok) {
            log.error("Synthesis failed: sessionId={}, timedOut={}, finalReportOk={}, status={}",
                    result.sessionId(),
                    result.timedOut(),
                    ok,
                    runDir.resolve("status.json"));
            throw new IllegalStateException("synthesis failed");
        }
        log.info("Synthesis completed: finalReport={}", finalReport);
    }

    private boolean waitForFinalReport(Path finalReport, int outputWaitSeconds) throws Exception {
        return outputWaiter.waitFor(
                () -> finalReportReady(finalReport),
                Boolean::booleanValue,
                () -> false,
                Duration.ofSeconds(Math.max(0, outputWaitSeconds)),
                Duration.ofSeconds(2)
        );
    }

    private boolean finalReportReady(Path finalReport) throws IOException {
        String report = Files.exists(finalReport) ? Files.readString(finalReport) : "";
        return !report.isBlank() && !report.contains(GitReportConstants.REPORT_MARKER);
    }

    private void writeAuthorStatus(Path statusPath, Map<String, Object> task, int attempt, String state, boolean timedOut, OpenCodeRunResult runResult, String error) throws IOException {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("authorKey", task.get("author_key"));
        status.put("author", task.get("author"));
        status.put("attempt", attempt);
        status.put("state", state);
        status.put("sessionId", runResult == null ? "" : runResult.sessionId());
        status.put("serverUrl", runResult == null ? "" : runResult.serverUrl());
        status.put("serverOwnedByJava", runResult != null && runResult.serverOwnedByJava());
        status.put("timedOut", timedOut);
        status.put("completedByOutput", runResult != null && runResult.completedByOutput());
        status.put("aborted", runResult != null && runResult.aborted());
        status.put("serverState", runResult == null ? "unknown" : runResult.serverState());
        status.put("finishedAt", OffsetDateTime.now().toString());
        status.put("error", error == null ? "" : error);
        statusRepository.write(statusPath, status);
    }

    private Map<String, Object> readMap(Path path) throws IOException {
        return objectMapper.readValue(path.toFile(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
