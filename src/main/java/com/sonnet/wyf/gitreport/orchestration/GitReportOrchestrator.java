package com.sonnet.wyf.gitreport.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.GitReportProperties;
import com.sonnet.wyf.gitreport.opencode.OpenCodeRunResult;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerHandle;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.opencode.ValidationCheck;
import com.sonnet.wyf.gitreport.opencode.ValidatedOpenCodeTaskSpec;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate.IncompleteOutput;
import com.sonnet.wyf.gitreport.preparation.GitReportPreparation;
import com.sonnet.wyf.gitreport.prompt.PromptBuilder;
import com.sonnet.wyf.gitreport.scoring.QualityScoresWriter;
import com.sonnet.wyf.gitreport.util.JsonMaps;
import com.sonnet.wyf.gitreport.validation.AuthorOutputValidator;
import com.sonnet.wyf.gitreport.validation.AuthorValidationResult;
import com.sonnet.wyf.gitreport.validation.FinalReportValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

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
    private final RunStatusRepository statusRepository;
    private final OutputCompletionGate completionGate;
    private final ConcurrentWorkflowTaskRunner concurrentTaskRunner;
    private final ArtifactCompletenessValidator artifactCompletenessValidator;
    private final GitReportSynthesisWorkflow synthesisWorkflow;

    public GitReportOrchestrator(
            GitReportPreparation preparation,
            ObjectMapper objectMapper,
            PromptBuilder promptBuilder,
            OpenCodeServerManager serverManager,
            OpenCodeServerTaskRunner taskRunner,
            AuthorOutputValidator outputValidator,
            FinalReportValidator finalReportValidator,
            QualityScoresWriter qualityScoresWriter,
            SynthesisInputWriter synthesisInputWriter,
            RunStatusRepository statusRepository,
            OutputCompletionGate completionGate,
            ConcurrentWorkflowTaskRunner concurrentTaskRunner,
            ArtifactCompletenessValidator artifactCompletenessValidator
    ) {
        this.preparation = preparation;
        this.objectMapper = objectMapper;
        this.promptBuilder = promptBuilder;
        this.serverManager = serverManager;
        this.taskRunner = taskRunner;
        this.outputValidator = outputValidator;
        this.qualityScoresWriter = qualityScoresWriter;
        this.statusRepository = statusRepository;
        this.completionGate = completionGate;
        this.concurrentTaskRunner = concurrentTaskRunner;
        this.artifactCompletenessValidator = artifactCompletenessValidator;
        this.synthesisWorkflow = new GitReportSynthesisWorkflow(
                objectMapper,
                promptBuilder,
                taskRunner,
                finalReportValidator,
                synthesisInputWriter,
                statusRepository
        );
    }

    public void run(GitReportProperties properties) throws Exception {
        Path out = properties.getPaths().getOut().toAbsolutePath().normalize();
        validateRepositoryDirectory(properties);
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
        ensureAllAuthorOutputsReady(properties, out, indexInputs, server);
        Path qualityScores = qualityScoresWriter.write(out.resolve("quality-scores.json"), summary, indexInputs);
        synthesisWorkflow.run(properties, out, qualityScores, server);
        log.info("Git report orchestration completed: finalReport={}", out.resolve("code-contribution-report.md"));
    }

    public void runSynthesisOnly(GitReportProperties properties) throws Exception {
        Path out = properties.getPaths().getOut().toAbsolutePath().normalize();
        validateRepositoryDirectory(properties);
        validatePreparedGitReportOutputs(out);
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
        ensureAllAuthorOutputsReady(properties, out, indexInputs, server);
        Path qualityScores = qualityScoresWriter.write(out.resolve("quality-scores.json"), summary, indexInputs);
        synthesisWorkflow.run(properties, out, qualityScores, server);
        log.info("Git report synthesis-only orchestration completed: finalReport={}", out.resolve("code-contribution-report.md"));
    }

    public void runSingleAuthor(GitReportProperties properties, String authorKey) throws Exception {
        runAuthors(properties, authorKey == null ? List.of() : List.of(authorKey));
    }

    public void runAuthors(GitReportProperties properties, List<String> authorKeys) throws Exception {
        List<String> requestedAuthorKeys = normalizeIds(authorKeys, "authorKey is required for git-report author rerun");
        Path out = properties.getPaths().getOut().toAbsolutePath().normalize();
        validateRepositoryDirectory(properties);
        validatePreparedGitReportOutputs(out);
        OpenCodeServerHandle server = serverManager.ensureReady(properties, out);
        Map<String, Object> summary = readMap(out.resolve("summary.json"));
        Map<String, Object> indexInputs = readMap(out.resolve("index_inputs.json"));
        List<Map<String, Object>> targets = requestedAuthorKeys.stream()
                .map(authorKey -> taskByAuthorKey(indexInputs, authorKey))
                .toList();
        log.info("Git report author rerun started: authorKeys={}, out={}, repo={}",
                requestedAuthorKeys,
                out,
                properties.getPaths().getRepo().toAbsolutePath().normalize());
        Map<String, Object> selectedIndexInputs = new LinkedHashMap<>(indexInputs);
        selectedIndexInputs.put("tasks", targets);
        runAuthorTasks(properties, out, selectedIndexInputs, server);
        ensureAllAuthorOutputsReady(properties, out, indexInputs, server);
        Path qualityScores = qualityScoresWriter.write(out.resolve("quality-scores.json"), summary, indexInputs);
        synthesisWorkflow.run(properties, out, qualityScores, server);
        log.info("Git report author rerun completed: authorKeys={}, finalReport={}", requestedAuthorKeys, out.resolve("code-contribution-report.md"));
    }

    private Map<String, Object> taskByAuthorKey(Map<String, Object> indexInputs, String authorKey) {
        return WorkflowTaskIndex.fromIndexInputs(indexInputs).gitAuthorTask(authorKey);
    }

    private List<String> normalizeIds(List<String> values, String blankMessage) {
        List<String> normalized = values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(blankMessage);
        }
        return normalized;
    }

    private void validateRepositoryDirectory(GitReportProperties properties) {
        Path repo = properties.getPaths().getRepo().toAbsolutePath().normalize();
        if (!Files.isDirectory(repo)) {
            throw new IllegalArgumentException("git-report paths.repo must be an existing local directory: " + repo);
        }
    }

    private void validatePreparedGitReportOutputs(Path out) {
        requireFile(out.resolve("summary.json"));
        requireFile(out.resolve("index_inputs.json"));
    }

    private void requireFile(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("git-report rerun requires existing preparation output: " + path);
        }
    }

    private void ensureAllAuthorOutputsReady(
            GitReportProperties properties,
            Path out,
            Map<String, Object> indexInputs,
            OpenCodeServerHandle server
    ) throws Exception {
        completionGate.ensureComplete(
                "git-report author",
                out.resolve("runs").resolve("incomplete-reports.json"),
                () -> incompleteAuthorOutputs(indexInputs),
                (incomplete, rerunRound, maxRerunRounds) -> {
                    Map<String, Map<String, Object>> tasksByAuthorKey = new LinkedHashMap<>();
                    for (Map<String, Object> task : listOfMaps(indexInputs.get("tasks"))) {
                        tasksByAuthorKey.put(task.get("author_key").toString(), task);
                    }
                    List<Map<String, Object>> targets = incomplete.stream()
                            .map(item -> tasksByAuthorKey.get(item.name()))
                            .filter(task -> task != null)
                            .toList();
                    log.warn("Git report author outputs incomplete before synthesis; rerunRound={}/{}, authors={}",
                            rerunRound,
                            maxRerunRounds,
                            incomplete.stream().map(IncompleteOutput::summary).reduce((left, right) -> left + "; " + right).orElse(""));
                    Map<String, Object> selectedIndexInputs = new LinkedHashMap<>(indexInputs);
                    selectedIndexInputs.put("tasks", targets);
                    runAuthorTasks(properties, out, selectedIndexInputs, server);
                }
        );
    }

    private List<IncompleteOutput> incompleteAuthorOutputs(Map<String, Object> indexInputs) {
        List<IncompleteOutput> incomplete = new ArrayList<>();
        for (Map<String, Object> task : listOfMaps(indexInputs.get("tasks"))) {
            Path report = Path.of(task.get("report_md").toString());
            Path qualitySummary = Path.of(task.get("quality_summary_json").toString());
            ArtifactCompletenessValidator.Validation artifactValidation = artifactCompletenessValidator.validateFile(
                    "person report",
                    report,
                    List.of()
            );
            if (!artifactValidation.ok()) {
                incomplete.add(new IncompleteOutput(
                        "author",
                        task.get("author_key").toString(),
                        report,
                        task.getOrDefault("detail_json", "").toString(),
                        task.get("author") + ": " + artifactValidation.error()
                ));
                continue;
            }
            AuthorValidationResult validation = outputValidator.validate(report, qualitySummary);
            if (!validation.ok()) {
                incomplete.add(new IncompleteOutput(
                        "author",
                        task.get("author_key").toString(),
                        report,
                        task.getOrDefault("detail_json", "").toString(),
                        task.get("author") + ": " + validation.error()
                ));
            }
        }
        return incomplete;
    }

    private void runAuthorTasks(GitReportProperties properties, Path out, Map<String, Object> indexInputs, OpenCodeServerHandle server) throws Exception {
        List<Map<String, Object>> tasks = listOfMaps(indexInputs.get("tasks"));
        int concurrency = Math.max(1, Math.min(properties.getOpencode().getConcurrency(), properties.getOpencode().getMaxConcurrency()));
        concurrentTaskRunner.run(
                "git-report author",
                tasks,
                concurrency,
                task -> task.get("author_key").toString(),
                task -> authorCallable(properties, out, task, server)
        );
    }

    private Callable<TaskRunResult> authorCallable(GitReportProperties properties, Path out, Map<String, Object> task, OpenCodeServerHandle server) {
        return () -> {
            String authorKey = task.get("author_key").toString();
            String author = task.get("author").toString();
            Path runDir = out.resolve("runs").resolve(authorKey);
            Path statusPath = runDir.resolve("status.json");
            int attempts = 1;
            int attemptsRun = 0;
            String lastError = "";
            for (int attempt = 1; attempt <= attempts; attempt++) {
                attemptsRun = attempt;
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
                    runResult = taskRunner.runUntilValidated(new ValidatedOpenCodeTaskSpec(
                            server,
                            properties.getPaths().getRepo(),
                            "git-report-" + authorKey,
                            promptFile,
                            properties.getOpencode().getWorkerMessage(),
                            runDir,
                            () -> authorValidationCheck(reportMd, qualitySummaryJson),
                            properties.getOpencode().getSessionModel(),
                            properties.getOpencode().getCreateSessionTimeoutSeconds(),
                            properties.getOpencode().getRequestTimeoutSeconds(),
                            OPENCODE_POLL_MILLIS,
                            properties.getOpencode().getTimeoutMinutes(),
                            properties.getOpencode().getOutputWaitSeconds(),
                            0
                    ));
                    timedOut = runResult.timedOut();
                    AuthorValidationResult validation = outputValidator.validate(reportMd, qualitySummaryJson);
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
                        return TaskRunResult.success(authorKey, author, statusPath);
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
            log.error("AUTHOR_FAILED reason=\"{}\" status={} authorKey={} author={} attempts={}", lastError, statusPath, authorKey, author, attemptsRun);
            return TaskRunResult.failed(authorKey, author, statusPath, lastError);
        };
    }

    private String sanitizeOpenCodeError(Exception exception) {
        return exception.getClass().getName() + ": " + (exception.getMessage() == null ? "" : exception.getMessage());
    }

    private ValidationCheck authorValidationCheck(Path reportMd, Path qualitySummaryJson) {
        AuthorValidationResult validation = outputValidator.validate(reportMd, qualitySummaryJson);
        return validation.ok() ? ValidationCheck.success() : ValidationCheck.failed(validation.error());
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
        return JsonMaps.listOfMaps(value);
    }
}
