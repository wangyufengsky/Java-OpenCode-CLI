package com.sonnet.wyf.gitreport.workflow.smartesbreader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeTaskRunner;
import com.sonnet.wyf.gitreport.agentbridge.ValidatedAgentBridgeTaskSpec;
import com.sonnet.wyf.gitreport.orchestration.ConcurrentWorkflowTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate.IncompleteOutput;
import com.sonnet.wyf.gitreport.orchestration.TaskRunResult;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SmartEsbCodeReaderWorkflowChain implements WorkflowChain {
    public static final String ID = "smartesb-code-reader";
    private static final Logger log = LoggerFactory.getLogger(SmartEsbCodeReaderWorkflowChain.class);

    private final ChainConfigLoader configLoader;
    private final AgentBridgeRunnerProperties runnerProperties;
    private final SmartEsbCodeReaderPreparation preparation;
    private final SmartEsbCodeReaderPromptBuilder promptBuilder;
    private final SmartEsbCodeReaderOutputValidator outputValidator;
    private final AgentBridgeTaskRunner taskRunner;
    private final ObjectMapper objectMapper;
    private final OutputCompletionGate completionGate;
    private final ConcurrentWorkflowTaskRunner concurrentTaskRunner;

    public SmartEsbCodeReaderWorkflowChain(
            ChainConfigLoader configLoader,
            AgentBridgeRunnerProperties runnerProperties,
            SmartEsbCodeReaderPreparation preparation,
            SmartEsbCodeReaderPromptBuilder promptBuilder,
            SmartEsbCodeReaderOutputValidator outputValidator,
            AgentBridgeTaskRunner taskRunner,
            ObjectMapper objectMapper,
            OutputCompletionGate completionGate,
            ConcurrentWorkflowTaskRunner concurrentTaskRunner
    ) {
        this.configLoader = configLoader;
        this.runnerProperties = runnerProperties;
        this.preparation = preparation;
        this.promptBuilder = promptBuilder;
        this.outputValidator = outputValidator;
        this.taskRunner = taskRunner;
        this.objectMapper = objectMapper;
        this.completionGate = completionGate;
        this.concurrentTaskRunner = concurrentTaskRunner;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void run(WorkflowRunRequest request) throws Exception {
        SmartEsbCodeReaderProperties properties = configLoader.load(configDir(request), id(), SmartEsbCodeReaderProperties.class);
        String mode = request.mode() == null || request.mode().isBlank() ? "full" : request.mode();
        if ("full".equals(mode)) {
            Path out = preparation.prepare(properties, true);
            runTasks(properties, request, out, loadTasks(out, "module"), false);
            ensureOutputsReady(properties, request, out, "module");
            runTasks(properties, request, out, loadTasks(out, "transaction"), false);
            ensureOutputsReady(properties, request, out, "transaction");
            runIndex(properties, request, out);
            return;
        }
        if (!"rerun".equals(mode)) {
            throw new IllegalArgumentException("smartesb-code-reader mode must be one of: full, rerun");
        }
        Path out = outputPath(properties);
        if (!Files.exists(out.resolve("index_inputs.json"))) {
            out = preparation.prepare(properties, true);
        }
        if ("module".equals(request.rerunType())) {
            runTasks(properties, request, out, tasksByName(loadTasks(out, "module"), "module", request.rerunIds()), true);
            ensureOutputsReady(properties, request, out, "module");
            runIndex(properties, request, out);
        } else if ("transaction".equals(request.rerunType())) {
            runTasks(properties, request, out, tasksByName(loadTasks(out, "transaction"), "transaction", request.rerunIds()), true);
            ensureOutputsReady(properties, request, out, "transaction");
            runIndex(properties, request, out);
        } else if ("index".equals(request.rerunType())) {
            runIndex(properties, request, out);
        } else {
            throw new IllegalArgumentException("smartesb-code-reader rerun.type must be one of: module, transaction, index");
        }
    }

    private String configDir(WorkflowRunRequest request) {
        return request.configDir() == null || request.configDir().isBlank()
                ? runnerProperties.getConfigDir()
                : request.configDir();
    }

    private void runTasks(
            SmartEsbCodeReaderProperties properties,
            WorkflowRunRequest request,
            Path out,
            List<Map<String, Object>> tasks,
            boolean rerun
    ) throws Exception {
        int concurrency = Math.max(1, Math.min(request.agentBridge().getConcurrency(), request.agentBridge().getMaxConcurrency()));
        List<TaskRunResult> results = concurrentTaskRunner.run(
                "SmartESB code-reader",
                tasks,
                concurrency,
                this::taskKey,
                task -> taskCallable(properties, request, out, task, rerun)
        );
        List<String> failures = results.stream()
                .filter(result -> !result.success())
                .map(result -> result.taskName() + ": " + result.error())
                .toList();
        if (!failures.isEmpty()) {
            log.warn("SmartESB code-reader sessions finished with incomplete outputs; completion gate will rerun: {}",
                    String.join("; ", failures));
        }
    }

    private Callable<TaskRunResult> taskCallable(
            SmartEsbCodeReaderProperties properties,
            WorkflowRunRequest request,
            Path out,
            Map<String, Object> task,
            boolean rerun
    ) {
        return () -> {
            String type = task.get("review_type").toString();
            String name = taskName(task);
            Path runDir = out.resolve("runs").resolve(type + "-" + SmartEsbCodeReaderPreparation.slugify(name));
            try {
                Files.createDirectories(runDir);
                Path promptFile = runDir.resolve("worker-prompt.md");
                Files.writeString(promptFile, buildPrompt(task, rerun));
                taskRunner.runUntilValidated(new ValidatedAgentBridgeTaskSpec(
                        properties.getJavaRoot(),
                        "smartesb-reader-" + type + "-" + name,
                        promptFile,
                        properties.getTaskMessage(),
                        runDir,
                        () -> outputValidator.validateTaskOutput(type, name, localDocumentPath(out, task), localSummaryPath(out, task)),
                        request.agentBridge().getPollMillis(),
                        request.agentBridge().getTimeoutMinutes(),
                        request.agentBridge().getValidationSettleSeconds(),
                        0,
                        java.net.URI.create(request.agentBridge().getWebBaseUrl())
                ));
                var validation = outputValidator.validateTaskOutput(type, name, localDocumentPath(out, task), localSummaryPath(out, task));
                if (validation.ok()) {
                    return TaskRunResult.success(type + ":" + name, name, runDir.resolve("agent-status.json"));
                }
                return TaskRunResult.failed(type + ":" + name, name, runDir.resolve("agent-status.json"), validation.error());
            } catch (Exception exception) {
                return TaskRunResult.failed(type + ":" + name, name, runDir.resolve("agent-status.json"), exception.getMessage());
            }
        };
    }

    private void ensureOutputsReady(
            SmartEsbCodeReaderProperties properties,
            WorkflowRunRequest request,
            Path out,
            String type
    ) throws Exception {
        completionGate.ensureComplete(
                "SmartESB code-reader " + type,
                out.resolve("runs").resolve("incomplete-" + type + "s.json"),
                () -> incompleteOutputs(out, type),
                (incomplete, rerunRound, maxRerunRounds) -> runTasks(properties, request, out, tasksByIncomplete(out, type, incomplete), true)
        );
    }

    private List<IncompleteOutput> incompleteOutputs(Path out, String type) throws Exception {
        List<IncompleteOutput> incomplete = new ArrayList<>();
        for (Map<String, Object> task : loadTasks(out, type)) {
            String name = taskName(task);
            var validation = outputValidator.validateTaskOutput(type, name, localDocumentPath(out, task), localSummaryPath(out, task));
            if (!validation.ok()) {
                incomplete.add(new IncompleteOutput(type, name, localSummaryPath(out, task), task.get("task_path").toString(), validation.error()));
            }
        }
        return incomplete;
    }

    private List<Map<String, Object>> tasksByIncomplete(Path out, String type, List<IncompleteOutput> incomplete) throws Exception {
        Set<String> names = incomplete.stream().map(IncompleteOutput::name).collect(Collectors.toSet());
        return loadTasks(out, type).stream()
                .filter(task -> names.contains(taskName(task)))
                .toList();
    }

    private void runIndex(SmartEsbCodeReaderProperties properties, WorkflowRunRequest request, Path out) throws Exception {
        Path runDir = out.resolve("runs").resolve("index");
        Files.createDirectories(runDir);
        Path promptFile = runDir.resolve("synthesis-prompt.md");
        Files.writeString(promptFile, promptBuilder.buildSynthesisPrompt(out.resolve("summary.json"), out.resolve("index_inputs.json")));
        taskRunner.runUntilValidated(new ValidatedAgentBridgeTaskSpec(
                properties.getJavaRoot(),
                "smartesb-reader-index",
                promptFile,
                properties.getSynthesisTaskMessage(),
                runDir,
                () -> outputValidator.validateIndex(out.resolve("index.md")),
                request.agentBridge().getPollMillis(),
                request.agentBridge().getTimeoutMinutes(),
                request.agentBridge().getValidationSettleSeconds(),
                request.agentBridge().getValidationMaxCorrections(),
                java.net.URI.create(request.agentBridge().getWebBaseUrl())
        ));
        var validation = outputValidator.validateIndex(out.resolve("index.md"));
        if (!validation.ok()) {
            throw new IllegalStateException("SmartESB code-reader index synthesis failed: " + validation.error());
        }
    }

    private String buildPrompt(Map<String, Object> task, boolean rerun) {
        if ("module".equals(task.get("review_type"))) {
            return promptBuilder.buildModulePrompt(task.get("task_path").toString(), rerun);
        }
        return promptBuilder.buildTransactionPrompt(task.get("task_path").toString(), rerun);
    }

    private List<Map<String, Object>> loadTasks(Path out, String type) throws Exception {
        Path tasksDir = out.resolve("tasks");
        if (!Files.exists(tasksDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(tasksDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().startsWith(type + "-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> {
                        try {
                            return objectMapper.<Map<String, Object>>readValue(path.toFile(), new TypeReference<>() {});
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();
        }
    }

    private List<Map<String, Object>> tasksByName(List<Map<String, Object>> tasks, String type, List<String> requestedNames) {
        if (requestedNames == null || requestedNames.isEmpty()) {
            throw new IllegalArgumentException(type + " id is required for smartesb-code-reader rerun");
        }
        Map<String, Map<String, Object>> byName = tasks.stream().collect(Collectors.toMap(this::taskName, task -> task));
        return requestedNames.stream()
                .map(name -> {
                    Map<String, Object> task = byName.get(name);
                    if (task == null) {
                        throw new IllegalArgumentException(type + " not found for smartesb-code-reader rerun: " + name + ", available=" + byName.keySet());
                    }
                    return task;
                })
                .toList();
    }

    private String taskKey(Map<String, Object> task) {
        return task.get("review_type") + ":" + taskName(task);
    }

    private String taskName(Map<String, Object> task) {
        return "module".equals(task.get("review_type")) ? task.get("serviceId").toString() : task.get("transaction_key").toString();
    }

    private Path localDocumentPath(Path out, Map<String, Object> task) {
        String type = task.get("review_type").toString();
        String name = SmartEsbCodeReaderPreparation.slugify(taskName(task));
        return out.resolve("module".equals(type) ? "modules" : "transactions").resolve(name).resolve("analysis.md");
    }

    private Path localSummaryPath(Path out, Map<String, Object> task) {
        String type = task.get("review_type").toString();
        String name = SmartEsbCodeReaderPreparation.slugify(taskName(task));
        return out.resolve("module".equals(type) ? "modules" : "transactions").resolve(name).resolve("summary.json");
    }

    private Path outputPath(SmartEsbCodeReaderProperties properties) {
        return properties.getLocalOut() == null ? Path.of(properties.getOut()) : properties.getLocalOut();
    }
}
