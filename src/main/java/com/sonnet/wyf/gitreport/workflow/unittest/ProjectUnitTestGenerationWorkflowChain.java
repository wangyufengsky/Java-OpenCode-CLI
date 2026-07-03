package com.sonnet.wyf.gitreport.workflow.unittest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerHandle;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.opencode.ValidatedOpenCodeTaskSpec;
import com.sonnet.wyf.gitreport.opencode.ValidationCheck;
import com.sonnet.wyf.gitreport.orchestration.ConcurrentWorkflowTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate.IncompleteOutput;
import com.sonnet.wyf.gitreport.orchestration.TaskRunResult;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.util.JsonMaps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

public class ProjectUnitTestGenerationWorkflowChain implements WorkflowChain {
    public static final String ID = "project-unit-test-generation";
    private static final Logger log = LoggerFactory.getLogger(ProjectUnitTestGenerationWorkflowChain.class);
    private static final int OPENCODE_POLL_MILLIS = 10_000;

    private final ChainConfigLoader configLoader;
    private final OpenCodeRunnerProperties runnerProperties;
    private final ProjectUnitTestGenerationPreparation preparation;
    private final ProjectUnitTestGenerationPromptBuilder promptBuilder;
    private final ProjectUnitTestGenerationOutputValidator outputValidator;
    private final ProjectUnitTestGenerationVerifier verifier;
    private final ProjectUnitTestGenerationReportRenderer reportRenderer;
    private final OpenCodeServerManager serverManager;
    private final OpenCodeServerTaskRunner taskRunner;
    private final ConcurrentWorkflowTaskRunner concurrentTaskRunner;
    private final OutputCompletionGate completionGate;
    private final ObjectMapper objectMapper;

    public ProjectUnitTestGenerationWorkflowChain(
            ChainConfigLoader configLoader,
            OpenCodeRunnerProperties runnerProperties,
            ProjectUnitTestGenerationPreparation preparation,
            ProjectUnitTestGenerationPromptBuilder promptBuilder,
            ProjectUnitTestGenerationOutputValidator outputValidator,
            ProjectUnitTestGenerationVerifier verifier,
            ProjectUnitTestGenerationReportRenderer reportRenderer,
            OpenCodeServerManager serverManager,
            OpenCodeServerTaskRunner taskRunner,
            ConcurrentWorkflowTaskRunner concurrentTaskRunner,
            OutputCompletionGate completionGate,
            ObjectMapper objectMapper
    ) {
        this.configLoader = configLoader;
        this.runnerProperties = runnerProperties;
        this.preparation = preparation;
        this.promptBuilder = promptBuilder;
        this.outputValidator = outputValidator;
        this.verifier = verifier;
        this.reportRenderer = reportRenderer;
        this.serverManager = serverManager;
        this.taskRunner = taskRunner;
        this.concurrentTaskRunner = concurrentTaskRunner;
        this.completionGate = completionGate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void run(WorkflowRunRequest request) throws Exception {
        ProjectUnitTestGenerationProperties properties = configLoader.load(configDir(request), id(), ProjectUnitTestGenerationProperties.class);
        String mode = request.mode() == null || request.mode().isBlank() ? "full" : request.mode();
        Path out = properties.getPaths().getOut().toAbsolutePath().normalize();
        if ("full".equals(mode)) {
            preparation.prepare(properties, true);
            List<Map<String, Object>> batches = loadBatches(out);
            runBatches(properties, request, out, batches);
            ensureOutputsReady(properties, request, out);
            verifyAndRender(properties, out);
            return;
        }
        if (!"rerun".equals(mode)) {
            throw new IllegalArgumentException("project-unit-test-generation mode must be one of: full, rerun");
        }
        if (!Files.exists(out.resolve("test-batches.json"))) {
            preparation.prepare(properties, true);
        }
        if ("test-batch".equals(request.rerunType())) {
            List<Map<String, Object>> batches = batchesByIds(out, request.rerunIds());
            runBatches(properties, request, out, batches);
            ensureOutputsReady(properties, request, out);
            verifyAndRender(properties, out);
        } else if ("verification".equals(request.rerunType())) {
            verifyAndRender(properties, out);
        } else {
            throw new IllegalArgumentException("project-unit-test-generation rerun.type must be one of: test-batch, verification");
        }
    }

    private void runBatches(
            ProjectUnitTestGenerationProperties properties,
            WorkflowRunRequest request,
            Path out,
            List<Map<String, Object>> batches
    ) throws Exception {
        if (batches.isEmpty()) {
            return;
        }
        OpenCodeServerHandle server = serverManager.ensureReady(request.openCode(), out);
        int concurrency = Math.max(1, Math.min(properties.getTest().getConcurrency(), request.openCode().getMaxConcurrency()));
        List<TaskRunResult> results = concurrentTaskRunner.run(
                "project unit-test generation",
                batches,
                concurrency,
                this::batchId,
                batch -> batchCallable(properties, request, out, server, batch)
        );
        List<String> failures = results.stream()
                .filter(result -> !result.success())
                .map(result -> result.taskName() + ": " + result.error())
                .toList();
        if (!failures.isEmpty()) {
            log.warn("unit-test generation batches finished with incomplete outputs; completion gate will rerun: {}",
                    String.join("; ", failures));
            failures.stream()
                    .filter(failure -> failure.contains("protected file"))
                    .findFirst()
                    .ifPresent(failure -> {
                        throw new IllegalStateException("project-unit-test-generation protected file violation: " + failure);
                    });
        }
    }

    private Callable<TaskRunResult> batchCallable(
            ProjectUnitTestGenerationProperties properties,
            WorkflowRunRequest request,
            Path out,
            OpenCodeServerHandle server,
            Map<String, Object> batch
    ) {
        return () -> {
            String batchId = batchId(batch);
            Path runDir = batchRunDir(out, batchId);
            try {
                Files.createDirectories(runDir);
                Path summaryJson = Path.of(string(batch.get("summary_json")));
                Files.deleteIfExists(summaryJson);
                ProtectedSnapshot protectedSnapshot = ProtectedSnapshot.capture(properties.getProject().getRepo(), out);
                Path promptFile = runDir.resolve("worker-prompt.md");
                Files.writeString(promptFile, promptBuilder.buildBatchPrompt(properties.getProject().getRepo(), Path.of(string(batch.get("input_json")))));
                taskRunner.runUntilValidated(new ValidatedOpenCodeTaskSpec(
                        server,
                        properties.getProject().getRepo(),
                        "project-unit-test-generation-" + batchId,
                        promptFile,
                        "严格执行附件 worker-prompt.md 中的单元测试生成任务，只输出 DONE 或 BLOCKED。",
                        runDir,
                        () -> validateBatch(properties, batch, protectedSnapshot),
                        request.openCode().getSessionModel(),
                        request.openCode().getCreateSessionTimeoutSeconds(),
                        request.openCode().getRequestTimeoutSeconds(),
                        OPENCODE_POLL_MILLIS,
                        Math.max(1, properties.getOpencode().getTimeoutMinutes()),
                        request.openCode().getOutputWaitSeconds(),
                        0
                ));
                ValidationCheck validation = validateBatch(properties, batch, protectedSnapshot);
                if (validation.ok()) {
                    return TaskRunResult.success(batchId, batchId, runDir.resolve("status.json"));
                }
                return TaskRunResult.failed(batchId, batchId, runDir.resolve("status.json"), validation.error());
            } catch (Exception exception) {
                return TaskRunResult.failed(batchId, batchId, runDir.resolve("status.json"), exception.getMessage());
            }
        };
    }

    private void ensureOutputsReady(ProjectUnitTestGenerationProperties properties, WorkflowRunRequest request, Path out) throws Exception {
        completionGate.ensureComplete(
                "project unit-test generation",
                out.resolve("runs").resolve("incomplete-test-batches.json"),
                () -> incompleteBatches(properties, out),
                (incomplete, rerunRound, maxRerunRounds) -> runBatches(properties, request, out, batchesByIncomplete(out, incomplete))
        );
    }

    private List<IncompleteOutput> incompleteBatches(ProjectUnitTestGenerationProperties properties, Path out) throws Exception {
        List<IncompleteOutput> incomplete = new ArrayList<>();
        for (Map<String, Object> batch : loadBatches(out)) {
            ValidationCheck validation = outputValidator.validateBatchOutput(
                    properties.getProject().getRepo().toAbsolutePath().normalize(),
                    batchId(batch),
                    Path.of(string(batch.get("summary_json")))
            );
            if (!validation.ok()) {
                incomplete.add(new IncompleteOutput(
                        "test-batch",
                        batchId(batch),
                        Path.of(string(batch.get("summary_json"))),
                        string(batch.get("input_json")),
                        validation.error()
                ));
            }
        }
        return incomplete;
    }

    private List<Map<String, Object>> batchesByIncomplete(Path out, List<IncompleteOutput> incomplete) throws Exception {
        Set<String> ids = incomplete.stream().map(IncompleteOutput::name).collect(Collectors.toSet());
        return loadBatches(out).stream().filter(batch -> ids.contains(batchId(batch))).toList();
    }

    private List<Map<String, Object>> batchesByIds(Path out, List<String> ids) throws Exception {
        List<Map<String, Object>> all = loadBatches(out);
        Set<String> requested = Set.copyOf(ids);
        List<Map<String, Object>> selected = all.stream()
                .filter(batch -> requested.contains(batchId(batch)))
                .toList();
        if (selected.size() != requested.size()) {
            Set<String> known = all.stream().map(this::batchId).collect(Collectors.toSet());
            List<String> missing = requested.stream().filter(id -> !known.contains(id)).toList();
            throw new IllegalArgumentException("unknown unit-test batch id: " + String.join(", ", missing));
        }
        return selected;
    }

    private ValidationCheck validateBatch(ProjectUnitTestGenerationProperties properties, Map<String, Object> batch, ProtectedSnapshot protectedSnapshot) {
        ValidationCheck output = outputValidator.validateBatchOutput(
                properties.getProject().getRepo().toAbsolutePath().normalize(),
                batchId(batch),
                Path.of(string(batch.get("summary_json")))
        );
        if (!output.ok()) {
            return output;
        }
        return protectedSnapshot.validate();
    }

    private void verifyAndRender(ProjectUnitTestGenerationProperties properties, Path out) throws Exception {
        boolean verified = verifier.verify(
                properties.getProject().getRepo().toAbsolutePath().normalize(),
                out,
                properties.getTest().getVerifyCommand()
        );
        reportRenderer.render(out);
        if (!verified) {
            throw new IllegalStateException("project-unit-test-generation verification failed: " + out.resolve("verification.json"));
        }
    }

    private List<Map<String, Object>> loadBatches(Path out) throws Exception {
        Path path = out.resolve("test-batches.json");
        if (!Files.exists(path)) {
            return List.of();
        }
        Map<String, Object> root = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
        return JsonMaps.listOfMaps(root.get("batches"));
    }

    private Path batchRunDir(Path out, String batchId) {
        return out.resolve("test-batches").resolve(batchId);
    }

    private String batchId(Map<String, Object> batch) {
        return string(batch.get("batch_id"));
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private String configDir(WorkflowRunRequest request) {
        return request.configDir() == null || request.configDir().isBlank()
                ? runnerProperties.getConfigDir()
                : request.configDir();
    }

    private static class ProtectedSnapshot {
        private final Path repo;
        private final Path out;
        private final Map<String, String> before;

        private ProtectedSnapshot(Path repo, Path out, Map<String, String> before) {
            this.repo = repo.toAbsolutePath().normalize();
            this.out = out.toAbsolutePath().normalize();
            this.before = before;
        }

        static ProtectedSnapshot capture(Path repo, Path out) throws Exception {
            Path normalizedRepo = repo.toAbsolutePath().normalize();
            Path normalizedOut = out.toAbsolutePath().normalize();
            return new ProtectedSnapshot(normalizedRepo, normalizedOut, fingerprint(normalizedRepo, normalizedOut));
        }

        ValidationCheck validate() {
            try {
                Map<String, String> after = fingerprint(repo, out);
                for (String path : before.keySet()) {
                    if (!after.containsKey(path)) {
                        return ValidationCheck.failed("deleted protected file: " + path);
                    }
                    if (!before.get(path).equals(after.get(path))) {
                        return ValidationCheck.failed("modified protected file: " + path);
                    }
                }
                for (String path : after.keySet()) {
                    if (!before.containsKey(path)) {
                        return ValidationCheck.failed("created protected file: " + path);
                    }
                }
                return ValidationCheck.success();
            } catch (Exception exception) {
                return ValidationCheck.failed("protected file validation failed: " + exception.getMessage());
            }
        }

        private static Map<String, String> fingerprint(Path repo, Path out) throws Exception {
            if (!Files.exists(repo)) {
                return Map.of();
            }
            Map<String, String> hashes = new LinkedHashMap<>();
            try (var stream = Files.walk(repo)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .filter(file -> !isAllowedWrite(repo, out, file))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList()) {
                    hashes.put(normalize(repo.relativize(file.toAbsolutePath().normalize()).toString()), sha256(file));
                }
            }
            return hashes;
        }

        private static boolean isAllowedWrite(Path repo, Path out, Path file) {
            Path normalized = file.toAbsolutePath().normalize();
            Path testRoot = repo.resolve("src/test").normalize();
            Path gitRoot = repo.resolve(".git").normalize();
            return normalized.startsWith(testRoot)
                    || normalized.startsWith(gitRoot)
                    || (out.startsWith(repo) && normalized.startsWith(out));
        }

        private static String sha256(Path file) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        }

        private static String normalize(String path) {
            return path.replace('\\', '/');
        }
    }
}
