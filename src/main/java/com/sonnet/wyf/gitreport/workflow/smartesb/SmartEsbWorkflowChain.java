package com.sonnet.wyf.gitreport.workflow.smartesb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.opencode.OpenCodeRunResult;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerHandle;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

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
    private final ScheduledProbeWaiter outputWaiter;
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
            ScheduledProbeWaiter outputWaiter,
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
        this.outputWaiter = outputWaiter;
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
        List<Future<String>> futures = new ArrayList<>();
        for (SmartEsbDailyTransactionPlan.Transaction transaction : transactions) {
            futures.add(transactionTaskExecutor.submit(transactionCallable(properties, request, out, indexInputs, server, transaction)));
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
        OpenCodeRunResult result = taskRunner.runUntil(
                server,
                Path.of(properties.getNewProject()),
                "smartesb-review-" + transaction.name(),
                promptFile,
                properties.getWorkerMessage(),
                runDir,
                () -> summaryValidator.validate(summaryJson).ok(),
                request.openCode().getSessionModel(),
                request.openCode().getCreateSessionTimeoutSeconds(),
                request.openCode().getRequestTimeoutSeconds(),
                OPENCODE_POLL_MILLIS,
                request.openCode().getTimeoutMinutes()
        );
        SmartEsbSummaryValidator.Validation validation = waitForSummary(summaryJson, request.openCode().getOutputWaitSeconds());
        if (!validation.ok()) {
            log.warn("SmartESB transaction review failed once, rerunning: transaction={}, reason={}, sessionId={}, timedOut={}",
                    transaction.name(), validation.error(), result.sessionId(), result.timedOut());
            rerunTransaction(properties, request, server, out, transaction, task, summarySchema, summaryJson);
            validation = waitForSummary(summaryJson, request.openCode().getOutputWaitSeconds());
        }
        return validation.ok() ? "" : transaction.name() + ": " + validation.error();
    }

    private void rerunTransaction(
            SmartEsbRewriteProperties properties,
            WorkflowRunRequest request,
            OpenCodeServerHandle server,
            Path out,
            SmartEsbDailyTransactionPlan.Transaction transaction,
            Map<String, Object> task,
            String summarySchema,
            Path summaryJson
    ) throws Exception {
        Path runDir = out.resolve("runs").resolve(SmartEsbReviewPreparation.slugify(transaction.name()) + "-rerun");
        Files.createDirectories(runDir);
        Path promptFile = runDir.resolve("worker-prompt.md");
        Files.writeString(promptFile, promptBuilder.buildRerunTransactionPrompt(
                task.get("task_path").toString(),
                Files.exists(summaryJson) ? summaryJson.toString() : task.get("review_md").toString(),
                summarySchema
        ));
        taskRunner.runUntil(
                server,
                Path.of(properties.getNewProject()),
                "smartesb-review-rerun-" + transaction.name(),
                promptFile,
                properties.getWorkerMessage(),
                runDir,
                () -> summaryValidator.validate(summaryJson).ok(),
                request.openCode().getSessionModel(),
                request.openCode().getCreateSessionTimeoutSeconds(),
                request.openCode().getRequestTimeoutSeconds(),
                OPENCODE_POLL_MILLIS,
                request.openCode().getTimeoutMinutes()
        );
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
        taskRunner.runUntil(
                server,
                Path.of(properties.getNewProject()),
                "smartesb-review-index",
                promptFile,
                properties.getSynthesisMessage(),
                runDir,
                () -> topLevelReady(indexMd, summaryMd),
                request.openCode().getSessionModel(),
                request.openCode().getCreateSessionTimeoutSeconds(),
                request.openCode().getRequestTimeoutSeconds(),
                OPENCODE_POLL_MILLIS,
                request.openCode().getTimeoutMinutes()
        );
        boolean ok = outputWaiter.waitFor(
                () -> topLevelReady(indexMd, summaryMd),
                Boolean::booleanValue,
                () -> false,
                Duration.ofSeconds(Math.max(0, request.openCode().getOutputWaitSeconds())),
                Duration.ofSeconds(2)
        );
        if (!ok) {
            throw new IllegalStateException("SmartESB index synthesis failed: " + indexMd + ", " + summaryMd);
        }
    }

    private SmartEsbSummaryValidator.Validation waitForSummary(Path summaryJson, int outputWaitSeconds) throws Exception {
        return outputWaiter.waitFor(
                () -> summaryValidator.validate(summaryJson),
                SmartEsbSummaryValidator.Validation::ok,
                () -> summaryValidator.validate(summaryJson),
                Duration.ofSeconds(Math.max(0, outputWaitSeconds)),
                Duration.ofSeconds(2)
        );
    }

    private boolean topLevelReady(Path indexMd, Path summaryMd) throws Exception {
        return Files.exists(indexMd)
                && Files.exists(summaryMd)
                && !Files.readString(indexMd).contains(SmartEsbReviewPreparation.TOP_LEVEL_OUTPUT_MARKERS.get("index_md"))
                && !Files.readString(summaryMd).contains(SmartEsbReviewPreparation.TOP_LEVEL_OUTPUT_MARKERS.get("summary_md"));
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
