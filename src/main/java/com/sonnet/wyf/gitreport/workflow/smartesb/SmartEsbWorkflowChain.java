package com.sonnet.wyf.gitreport.workflow.smartesb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.opencode.OpenCodeRunResult;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerHandle;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.opencode.ValidationCheck;
import com.sonnet.wyf.gitreport.opencode.ValidatedOpenCodeTaskSpec;
import com.sonnet.wyf.gitreport.orchestration.ArtifactCompletenessValidator;
import com.sonnet.wyf.gitreport.orchestration.ConcurrentWorkflowTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate.IncompleteOutput;
import com.sonnet.wyf.gitreport.orchestration.TaskRunResult;
import com.sonnet.wyf.gitreport.orchestration.WorkflowTaskIndex;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

public class SmartEsbWorkflowChain implements WorkflowChain {
    public static final String ID = "smartesb-rewrite-code-review";
    private static final Logger log = LoggerFactory.getLogger(SmartEsbWorkflowChain.class);
    private static final int OPENCODE_POLL_MILLIS = 10_000;

    private final ChainConfigLoader configLoader;
    private final OpenCodeRunnerProperties runnerProperties;
    private final SmartEsbDailyTransactionPlanLoader planLoader;
    private final SmartEsbReviewPreparation preparation;
    private final SmartEsbPromptBuilder promptBuilder;
    private final SmartEsbSummaryValidator summaryValidator;
    private final OpenCodeServerManager serverManager;
    private final OpenCodeServerTaskRunner taskRunner;
    private final ObjectMapper objectMapper;
    private final OutputCompletionGate completionGate;
    private final ConcurrentWorkflowTaskRunner concurrentTaskRunner;
    private final ArtifactCompletenessValidator artifactCompletenessValidator;

    public SmartEsbWorkflowChain(
            ChainConfigLoader configLoader,
            OpenCodeRunnerProperties runnerProperties,
            SmartEsbDailyTransactionPlanLoader planLoader,
            SmartEsbReviewPreparation preparation,
            SmartEsbPromptBuilder promptBuilder,
            SmartEsbSummaryValidator summaryValidator,
            OpenCodeServerManager serverManager,
            OpenCodeServerTaskRunner taskRunner,
            ObjectMapper objectMapper,
            OutputCompletionGate completionGate,
            ConcurrentWorkflowTaskRunner concurrentTaskRunner,
            ArtifactCompletenessValidator artifactCompletenessValidator
    ) {
        this.configLoader = configLoader;
        this.runnerProperties = runnerProperties;
        this.planLoader = planLoader;
        this.preparation = preparation;
        this.promptBuilder = promptBuilder;
        this.summaryValidator = summaryValidator;
        this.serverManager = serverManager;
        this.taskRunner = taskRunner;
        this.objectMapper = objectMapper;
        this.completionGate = completionGate;
        this.concurrentTaskRunner = concurrentTaskRunner;
        this.artifactCompletenessValidator = artifactCompletenessValidator;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void run(WorkflowRunRequest request) throws Exception {
        SmartEsbRewriteProperties properties = configLoader.load(configDir(request), id(), SmartEsbRewriteProperties.class);
        SmartEsbDailyTransactionPlan plan = planLoader.load(properties.getTransactionPlanDir(), request.effectiveRunDate());
        String mode = request.mode() == null || request.mode().isBlank() ? "full" : request.mode();
        if ("full".equals(mode)) {
            Path out = preparation.prepare(properties, plan, true);
            runReviewItems(properties, request, out, plan.reviewItems(), false);
            ensureAllReviewOutputsReady(properties, request, out, plan);
            runIndex(properties, request, out);
            return;
        }
        if (!"rerun".equals(mode)) {
            throw new IllegalArgumentException("SmartESB mode must be one of: full, rerun");
        }
        Path out = datedLocalOut(properties, plan);
        if ("transaction".equals(request.rerunType())) {
            List<SmartEsbDailyTransactionPlan.ReviewItem> items = reviewItemsByName(plan, "transaction", request.rerunIds());
            if (!Files.exists(out.resolve("index_inputs.json"))) {
                preparation.prepare(properties, plan, true);
            }
            runReviewItems(properties, request, out, items, true);
            ensureAllReviewOutputsReady(properties, request, out, plan);
            runIndex(properties, request, out);
        } else if ("module".equals(request.rerunType())) {
            List<SmartEsbDailyTransactionPlan.ReviewItem> items = reviewItemsByName(plan, "module", request.rerunIds());
            if (!Files.exists(out.resolve("index_inputs.json"))) {
                preparation.prepare(properties, plan, true);
            }
            runReviewItems(properties, request, out, items, true);
            ensureAllReviewOutputsReady(properties, request, out, plan);
            runIndex(properties, request, out);
        } else if ("index".equals(request.rerunType())) {
            ensureAllReviewOutputsReady(properties, request, out, plan);
            runIndex(properties, request, out);
        } else {
            throw new IllegalArgumentException("SmartESB rerun.type must be one of: transaction, module, index");
        }
    }

    private String configDir(WorkflowRunRequest request) {
        return request.configDir() == null || request.configDir().isBlank()
                ? runnerProperties.getConfigDir()
                : request.configDir();
    }

    private void runReviewItems(
            SmartEsbRewriteProperties properties,
            WorkflowRunRequest request,
            Path out,
            List<SmartEsbDailyTransactionPlan.ReviewItem> items,
            boolean rerun
    ) throws Exception {
        Map<String, Object> indexInputs = readMap(out.resolve("index_inputs.json"));
        OpenCodeServerHandle server = serverManager.ensureReady(request.openCode(), out);
        int concurrency = Math.max(1, Math.min(request.openCode().getConcurrency(), request.openCode().getMaxConcurrency()));
        List<TaskRunResult> results = concurrentTaskRunner.run(
                "SmartESB review",
                items,
                concurrency,
                item -> item.kind() + ":" + item.name(),
                item -> transactionCallable(properties, request, out, indexInputs, server, item, rerun)
        );
        List<String> failures = results.stream()
                .filter(result -> !result.success())
                .map(result -> result.taskName() + ": " + result.error())
                .toList();
        if (!failures.isEmpty()) {
            log.warn("SmartESB review item sessions finished with incomplete outputs; summary gate will rerun incomplete items: {}",
                    String.join("; ", failures));
        }
    }

    private Callable<TaskRunResult> transactionCallable(
            SmartEsbRewriteProperties properties,
            WorkflowRunRequest request,
            Path out,
            Map<String, Object> indexInputs,
            OpenCodeServerHandle server,
            SmartEsbDailyTransactionPlan.ReviewItem item,
            boolean rerun
    ) {
        return () -> {
            Path runDir = out.resolve("runs").resolve(SmartEsbReviewPreparation.slugify(item.name()));
            try {
                String failure = runReviewItem(properties, request, out, indexInputs, server, item, rerun);
                if (failure == null || failure.isBlank()) {
                    return TaskRunResult.success(item.kind() + ":" + item.name(), item.name(), runDir.resolve("status.json"));
                }
                return TaskRunResult.failed(item.kind() + ":" + item.name(), item.name(), runDir.resolve("status.json"), failure);
            } catch (Exception exception) {
                log.warn("SmartESB review failed: kind={}, name={}, reason={}",
                        item.kind(), item.name(), exception.toString());
                return TaskRunResult.failed(item.kind() + ":" + item.name(), item.name(), runDir.resolve("status.json"), exception.getMessage());
            }
        };
    }

    private String runReviewItem(
            SmartEsbRewriteProperties properties,
            WorkflowRunRequest request,
            Path out,
            Map<String, Object> indexInputs,
            OpenCodeServerHandle server,
            SmartEsbDailyTransactionPlan.ReviewItem item,
            boolean rerun
    ) throws Exception {
        Map<String, Object> task = taskByReviewItem(indexInputs, item);
        Path runDir = out.resolve("runs").resolve(SmartEsbReviewPreparation.slugify(item.name()));
        Files.createDirectories(runDir);
        Path promptFile = runDir.resolve("worker-prompt.md");
        String summarySchema = ((Map<?, ?>) indexInputs.get("schemas")).get("transaction_summary").toString();
        Path summaryJson = localSummaryPath(out, item.name());
        String prompt = buildWorkerPrompt(item, task, summarySchema, rerun);
        Files.writeString(promptFile, prompt);
        OpenCodeRunResult result = taskRunner.runUntilValidated(new ValidatedOpenCodeTaskSpec(
                server,
                Path.of(properties.getNewProject()),
                "smartesb-review-" + item.name(),
                promptFile,
                properties.getWorkerMessage(),
                runDir,
                () -> summaryValidationCheck(summaryJson),
                request.openCode().getSessionModel(),
                request.openCode().getCreateSessionTimeoutSeconds(),
                request.openCode().getRequestTimeoutSeconds(),
                OPENCODE_POLL_MILLIS,
                request.openCode().getTimeoutMinutes(),
                request.openCode().getOutputWaitSeconds(),
                0
        ));
        SmartEsbSummaryValidator.Validation validation = summaryValidator.validate(summaryJson);
        if (!validation.ok()) {
            log.warn("SmartESB review session completed with incomplete output: kind={}, name={}, reason={}, sessionId={}, timedOut={}, correctionRounds={}",
                    item.kind(), item.name(), validation.error(), result.sessionId(), result.timedOut(), result.correctionRounds());
        }
        return validation.ok() ? "" : item.name() + ": " + validation.error();
    }

    private void ensureAllReviewOutputsReady(
            SmartEsbRewriteProperties properties,
            WorkflowRunRequest request,
            Path out,
            SmartEsbDailyTransactionPlan plan
    ) throws Exception {
        completionGate.ensureComplete(
                "SmartESB review",
                out.resolve("runs").resolve("incomplete-reports.json"),
                () -> incompleteReviewItems(out, readMap(out.resolve("index_inputs.json"))),
                (incomplete, rerunRound, maxRerunRounds) -> {
                    List<SmartEsbDailyTransactionPlan.ReviewItem> items = reviewItemsByIncomplete(plan, incomplete);
                    log.warn("SmartESB review outputs incomplete before index synthesis; rerunRound={}/{}, items={}",
                            rerunRound,
                            maxRerunRounds,
                            incomplete.stream().map(IncompleteOutput::summary).collect(Collectors.joining("; ")));
                    runReviewItems(properties, request, out, items, true);
                }
        );
    }

    private List<IncompleteOutput> incompleteReviewItems(Path out, Map<String, Object> indexInputs) throws Exception {
        List<IncompleteOutput> incomplete = new ArrayList<>();
        for (Map<String, Object> task : listOfMaps(indexInputs.get("tasks"))) {
            String kind = task.get("review_type").toString();
            String name = taskName(task);
            Path summaryPath = localSummaryPath(out, name);
            SmartEsbSummaryValidator.Validation validation = summaryValidator.validate(summaryPath);
            if (!validation.ok()) {
                incomplete.add(new IncompleteOutput(kind, name, summaryPath, task.get("task_path").toString(),
                        "summary missing or invalid: " + validation.error()));
                continue;
            }
            String artifactError = artifactValidationError(out, name, task);
            if (!artifactError.isBlank()) {
                incomplete.add(new IncompleteOutput(kind, name, summaryPath, task.get("task_path").toString(), artifactError));
            }
        }
        return incomplete;
    }

    private String artifactValidationError(Path out, String name, Map<String, Object> task) throws Exception {
        Object placeholders = task.get("output_placeholders");
        if (!(placeholders instanceof Map<?, ?> placeholderMap)) {
            return "output_placeholders missing or invalid: " + task.get("task_path");
        }
        ArtifactCompletenessValidator.Validation validation = artifactCompletenessValidator.validateOutputs(
                placeholderMap,
                outputKey -> localOutputPath(out, name, outputKey)
        );
        return validation.ok() ? "" : validation.error();
    }

    private Path localOutputPath(Path out, String name, String outputKey) {
        Path reportDir = out.resolve("reports").resolve(SmartEsbReviewPreparation.slugify(name));
        Path sectionsDir = reportDir.resolve("sections");
        return switch (outputKey) {
            case "review_md" -> reportDir.resolve("review.md");
            case "matrix_md" -> reportDir.resolve("mapping-matrix.md");
            case "findings_md" -> sectionsDir.resolve("01-findings.md");
            case "code_chains_md" -> sectionsDir.resolve("02-code-chains.md");
            case "protocol_review_md" -> sectionsDir.resolve("03-protocol-review.md");
            case "behavior_review_md" -> sectionsDir.resolve("04-behavior-review.md");
            case "verification_md" -> sectionsDir.resolve("05-verification.md");
            case "code_standard_md" -> sectionsDir.resolve("06-code-standard.md");
            default -> reportDir.resolve(outputKey);
        };
    }

    private List<SmartEsbDailyTransactionPlan.ReviewItem> reviewItemsByIncomplete(
            SmartEsbDailyTransactionPlan plan,
            List<IncompleteOutput> incomplete
    ) {
        Map<String, SmartEsbDailyTransactionPlan.ReviewItem> byKey = plan.reviewItems().stream()
                .collect(Collectors.toMap(item -> item.kind() + ":" + item.name(), item -> item));
        return incomplete.stream()
                .map(item -> {
                    SmartEsbDailyTransactionPlan.ReviewItem reviewItem = byKey.get(item.type() + ":" + item.name());
                    if (reviewItem == null) {
                        throw new IllegalArgumentException(item.type() + " not found in " + plan.source() + ": " + item.name());
                    }
                    return reviewItem;
                })
                .distinct()
                .toList();
    }

    private String buildWorkerPrompt(SmartEsbDailyTransactionPlan.ReviewItem item, Map<String, Object> task, String summarySchema, boolean rerun) {
        String taskPath = task.get("task_path").toString();
        String summaryJson = task.get("summary_json").toString();
        if (item.isModule()) {
            return rerun
                    ? promptBuilder.buildRerunModulePrompt(taskPath, summaryJson, summarySchema)
                    : promptBuilder.buildModulePrompt(taskPath, summarySchema);
        }
        return rerun
                ? promptBuilder.buildRerunTransactionPrompt(taskPath, summaryJson, summarySchema)
                : promptBuilder.buildTransactionPrompt(taskPath, summarySchema);
    }

    private void runIndex(SmartEsbRewriteProperties properties, WorkflowRunRequest request, Path out) throws Exception {
        Map<String, Object> indexInputs = readMap(out.resolve("index_inputs.json"));
        List<String> invalid = new ArrayList<>();
        for (Map<String, Object> task : listOfMaps(indexInputs.get("tasks"))) {
            String name = taskName(task);
            Path summaryPath = localSummaryPath(out, name);
            SmartEsbSummaryValidator.Validation validation = summaryValidator.validate(summaryPath);
            if (!validation.ok()) {
                invalid.add(name + ": " + validation.error());
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("SmartESB summaries invalid for index rerun: " + String.join("; ", invalid));
        }
        OpenCodeServerHandle server = serverManager.ensureReady(request.openCode(), out);
        Path runDir = out.resolve("runs").resolve("index");
        Files.createDirectories(runDir);
        Path promptFile = runDir.resolve("synthesis-prompt.md");
        Files.writeString(promptFile, promptBuilder.buildSynthesisPrompt(out.resolve("summary.json"), out.resolve("index_inputs.json")));
        Path indexMd = out.resolve("index.md");
        Path summaryMd = out.resolve("summary.md");
        taskRunner.runUntilValidated(new ValidatedOpenCodeTaskSpec(
                server,
                Path.of(properties.getNewProject()),
                "smartesb-review-index",
                promptFile,
                properties.getSynthesisMessage(),
                runDir,
                () -> topLevelValidation(indexMd, summaryMd),
                request.openCode().getSessionModel(),
                request.openCode().getCreateSessionTimeoutSeconds(),
                request.openCode().getRequestTimeoutSeconds(),
                OPENCODE_POLL_MILLIS,
                request.openCode().getTimeoutMinutes(),
                request.openCode().getOutputWaitSeconds(),
                request.openCode().getValidationMaxCorrections()
        ));
        ValidationCheck validation = topLevelValidation(indexMd, summaryMd);
        if (!validation.ok()) {
            throw new IllegalStateException("SmartESB index synthesis failed: " + indexMd + ", " + summaryMd);
        }
    }

    private ValidationCheck summaryValidationCheck(Path summaryJson) {
        SmartEsbSummaryValidator.Validation validation = summaryValidator.validate(summaryJson);
        return validation.ok() ? ValidationCheck.success() : ValidationCheck.failed(validation.error());
    }

    private ValidationCheck topLevelValidation(Path indexMd, Path summaryMd) throws Exception {
        ArtifactCompletenessValidator.Validation indexValidation = artifactCompletenessValidator.validateFile(
                "SmartESB index",
                indexMd,
                SmartEsbReviewPreparation.TOP_LEVEL_OUTPUT_PLACEHOLDERS.get("index_md")
        );
        if (!indexValidation.ok()) {
            return ValidationCheck.failed(indexValidation.error());
        }
        ArtifactCompletenessValidator.Validation summaryValidation = artifactCompletenessValidator.validateFile(
                "SmartESB summary",
                summaryMd,
                SmartEsbReviewPreparation.TOP_LEVEL_OUTPUT_PLACEHOLDERS.get("summary_md")
        );
        if (!summaryValidation.ok()) {
            return ValidationCheck.failed(summaryValidation.error());
        }
        return ValidationCheck.success();
    }

    private Path datedLocalOut(SmartEsbRewriteProperties properties, SmartEsbDailyTransactionPlan plan) {
        if (properties.getLocalOut() != null) {
            return properties.getLocalOut().resolve(plan.date().toString());
        }
        return Path.of(SmartEsbReviewPreparation.appendLogical(properties.getOut(), plan.date().toString()));
    }

    private Map<String, Object> taskByReviewItem(Map<String, Object> indexInputs, SmartEsbDailyTransactionPlan.ReviewItem item) {
        return WorkflowTaskIndex.fromIndexInputs(indexInputs).smartEsbReviewTask(item.kind(), item.name());
    }

    private List<SmartEsbDailyTransactionPlan.ReviewItem> reviewItemsByName(SmartEsbDailyTransactionPlan plan, String kind, List<String> requestedNames) {
        if (requestedNames == null || requestedNames.isEmpty()) {
            throw new IllegalArgumentException(kind + " id is required for SmartESB " + kind + " rerun");
        }
        Map<String, SmartEsbDailyTransactionPlan.ReviewItem> byName = plan.reviewItems().stream()
                .filter(item -> kind.equals(item.kind()))
                .collect(Collectors.toMap(SmartEsbDailyTransactionPlan.ReviewItem::name, item -> item));
        Set<String> available = byName.keySet();
        return requestedNames.stream()
                .map(name -> {
                    SmartEsbDailyTransactionPlan.ReviewItem item = byName.get(name);
                    if (item == null) {
                        throw new IllegalArgumentException(kind + " not found in " + plan.source() + ": " + name + ", available=" + available);
                    }
                    return item;
                })
                .toList();
    }

    private String taskName(Map<String, Object> task) {
        Object module = task.get("module");
        if (module != null) {
            return module.toString();
        }
        return task.get("transaction").toString();
    }

    private Path localSummaryPath(Path out, String name) {
        return out.resolve("reports").resolve(SmartEsbReviewPreparation.slugify(name)).resolve("summary.json");
    }

    private Map<String, Object> readMap(Path path) throws Exception {
        return objectMapper.readValue(path.toFile(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

}
