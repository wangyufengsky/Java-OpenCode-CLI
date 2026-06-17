package com.sonnet.wyf.gitreport.workflow.smartesb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.opencode.OpenCodeRunResult;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerHandle;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.opencode.ValidationCheck;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

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
    private final AsyncTaskExecutor transactionTaskExecutor;

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
            AsyncTaskExecutor transactionTaskExecutor
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
        this.transactionTaskExecutor = transactionTaskExecutor;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void run(WorkflowRunRequest request) throws Exception {
        SmartEsbRewriteProperties properties = configLoader.load(runnerProperties.getConfigDir(), id(), SmartEsbRewriteProperties.class);
        SmartEsbDailyTransactionPlan plan = planLoader.load(properties.getTransactionPlanDir(), request.effectiveRunDate());
        String mode = request.mode() == null || request.mode().isBlank() ? "full" : request.mode();
        if ("full".equals(mode)) {
            Path out = preparation.prepare(properties, plan, true);
            runTransactions(properties, request, out, plan.transactions());
            runIndex(properties, request, out);
            return;
        }
        if (!"rerun".equals(mode)) {
            throw new IllegalArgumentException("SmartESB mode must be one of: full, rerun");
        }
        Path out = datedLocalOut(properties, plan);
        if ("transaction".equals(request.rerunType())) {
            SmartEsbDailyTransactionPlan.Transaction transaction = plan.transactions().stream()
                    .filter(item -> item.name().equals(request.rerunId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("transaction not found in " + plan.source() + ": " + request.rerunId()));
            if (!Files.exists(out.resolve("index_inputs.json"))) {
                preparation.prepare(properties, plan, true);
            }
            runTransactions(properties, request, out, List.of(transaction));
            runIndex(properties, request, out);
        } else if ("index".equals(request.rerunType())) {
            runIndex(properties, request, out);
        } else {
            throw new IllegalArgumentException("SmartESB rerun.type must be one of: transaction, index");
        }
    }

    private void runTransactions(
            SmartEsbRewriteProperties properties,
            WorkflowRunRequest request,
            Path out,
            List<SmartEsbDailyTransactionPlan.Transaction> transactions
    ) throws Exception {
        Map<String, Object> indexInputs = readMap(out.resolve("index_inputs.json"));
        OpenCodeServerHandle server = serverManager.ensureReady(request.openCode(), out);
        int concurrency = Math.max(1, Math.min(request.openCode().getConcurrency(), request.openCode().getMaxConcurrency()));
        log.info("Starting SmartESB transaction reviews: taskCount={}, concurrency={}", transactions.size(), concurrency);
        Semaphore transactionSlots = new Semaphore(concurrency);
        List<Future<String>> futures = new ArrayList<>();
        for (SmartEsbDailyTransactionPlan.Transaction transaction : transactions) {
            futures.add(transactionTaskExecutor.submit(limitedTransactionCallable(
                    transactionSlots,
                    transactionCallable(properties, request, out, indexInputs, server, transaction)
            )));
        }
        List<String> failures = new ArrayList<>();
        for (Future<String> future : futures) {
            String failure = future.get();
            if (failure != null && !failure.isBlank()) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("SmartESB transaction review failed: " + String.join("; ", failures));
        }
    }

    private Callable<String> limitedTransactionCallable(Semaphore transactionSlots, Callable<String> delegate) {
        return () -> {
            transactionSlots.acquire();
            try {
                return delegate.call();
            } finally {
                transactionSlots.release();
            }
        };
    }

    private Callable<String> transactionCallable(
            SmartEsbRewriteProperties properties,
            WorkflowRunRequest request,
            Path out,
            Map<String, Object> indexInputs,
            OpenCodeServerHandle server,
            SmartEsbDailyTransactionPlan.Transaction transaction
    ) {
        return () -> {
            try {
                return runTransaction(properties, request, out, indexInputs, server, transaction);
            } catch (Exception exception) {
                log.warn("SmartESB transaction review failed: transaction={}, reason={}",
                        transaction.name(), exception.toString());
                return transaction.name() + ": " + exception.getMessage();
            }
        };
    }

    private String runTransaction(
            SmartEsbRewriteProperties properties,
            WorkflowRunRequest request,
            Path out,
            Map<String, Object> indexInputs,
            OpenCodeServerHandle server,
            SmartEsbDailyTransactionPlan.Transaction transaction
    ) throws Exception {
        Map<String, Object> task = taskByTransaction(indexInputs, transaction.name());
        Path runDir = out.resolve("runs").resolve(SmartEsbReviewPreparation.slugify(transaction.name()));
        Files.createDirectories(runDir);
        Path promptFile = runDir.resolve("worker-prompt.md");
        String summarySchema = ((Map<?, ?>) indexInputs.get("schemas")).get("transaction_summary").toString();
        Files.writeString(promptFile, promptBuilder.buildTransactionPrompt(task.get("task_path").toString(), summarySchema));
        Path summaryJson = localSummaryPath(out, transaction.name());
        OpenCodeRunResult result = taskRunner.runUntilValidated(
                server,
                Path.of(properties.getNewProject()),
                "smartesb-review-" + transaction.name(),
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
                request.openCode().getValidationMaxCorrections()
        );
        SmartEsbSummaryValidator.Validation validation = summaryValidator.validate(summaryJson);
        if (!validation.ok()) {
            log.warn("SmartESB transaction review failed after same-session correction attempts: transaction={}, reason={}, sessionId={}, timedOut={}, correctionRounds={}",
                    transaction.name(), validation.error(), result.sessionId(), result.timedOut(), result.correctionRounds());
        }
        return validation.ok() ? "" : transaction.name() + ": " + validation.error();
    }

    private void runIndex(SmartEsbRewriteProperties properties, WorkflowRunRequest request, Path out) throws Exception {
        Map<String, Object> indexInputs = readMap(out.resolve("index_inputs.json"));
        List<String> invalid = new ArrayList<>();
        for (Map<String, Object> task : listOfMaps(indexInputs.get("tasks"))) {
            Path summaryPath = localSummaryPath(out, task.get("transaction").toString());
            SmartEsbSummaryValidator.Validation validation = summaryValidator.validate(summaryPath);
            if (!validation.ok()) {
                invalid.add(task.get("transaction") + ": " + validation.error());
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
        taskRunner.runUntilValidated(
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
        );
        ValidationCheck validation = topLevelValidation(indexMd, summaryMd);
        if (!validation.ok()) {
            throw new IllegalStateException("SmartESB index synthesis failed: " + indexMd + ", " + summaryMd);
        }
    }

    private boolean topLevelReady(Path indexMd, Path summaryMd) throws Exception {
        return Files.exists(indexMd)
                && Files.exists(summaryMd)
                && placeholdersReplaced(indexMd, SmartEsbReviewPreparation.TOP_LEVEL_OUTPUT_PLACEHOLDERS.get("index_md"))
                && placeholdersReplaced(summaryMd, SmartEsbReviewPreparation.TOP_LEVEL_OUTPUT_PLACEHOLDERS.get("summary_md"));
    }

    private ValidationCheck summaryValidationCheck(Path summaryJson) {
        SmartEsbSummaryValidator.Validation validation = summaryValidator.validate(summaryJson);
        return validation.ok() ? ValidationCheck.success() : ValidationCheck.failed(validation.error());
    }

    private ValidationCheck topLevelValidation(Path indexMd, Path summaryMd) throws Exception {
        if (!Files.exists(indexMd)) {
            return ValidationCheck.failed("SmartESB index missing: " + indexMd);
        }
        if (!Files.exists(summaryMd)) {
            return ValidationCheck.failed("SmartESB summary missing: " + summaryMd);
        }
        if (!placeholdersReplaced(indexMd, SmartEsbReviewPreparation.TOP_LEVEL_OUTPUT_PLACEHOLDERS.get("index_md"))) {
            return ValidationCheck.failed("SmartESB index still contains template placeholder: " + indexMd);
        }
        if (!placeholdersReplaced(summaryMd, SmartEsbReviewPreparation.TOP_LEVEL_OUTPUT_PLACEHOLDERS.get("summary_md"))) {
            return ValidationCheck.failed("SmartESB summary still contains template placeholder: " + summaryMd);
        }
        return ValidationCheck.success();
    }

    private boolean placeholdersReplaced(Path path, List<String> placeholders) throws Exception {
        String content = Files.readString(path);
        return placeholders.stream().noneMatch(content::contains);
    }

    private Path datedLocalOut(SmartEsbRewriteProperties properties, SmartEsbDailyTransactionPlan plan) {
        if (properties.getLocalOut() != null) {
            return properties.getLocalOut().resolve(plan.date().toString());
        }
        return Path.of(SmartEsbReviewPreparation.appendLogical(properties.getOut(), plan.date().toString()));
    }

    private Map<String, Object> taskByTransaction(Map<String, Object> indexInputs, String transaction) {
        return listOfMaps(indexInputs.get("tasks")).stream()
                .filter(task -> transaction.equals(task.get("transaction")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("transaction task missing from index_inputs.json: " + transaction));
    }

    private Path localSummaryPath(Path out, String transaction) {
        return out.resolve("reports").resolve(SmartEsbReviewPreparation.slugify(transaction)).resolve("summary.json");
    }

    private Map<String, Object> readMap(Path path) throws Exception {
        return objectMapper.readValue(path.toFile(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
