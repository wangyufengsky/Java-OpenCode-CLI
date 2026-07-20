package com.sonnet.wyf.gitreport.workflow.unittest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.artifact.RepositoryExecutionLock;
import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactContext;
import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactWorkspace;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.util.JsonMaps;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationBatchRunner.BatchResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ProjectUnitTestGenerationWorkflowChain implements WorkflowChain {
    public static final String ID = "project-unit-test-generation";

    private final ChainConfigLoader configLoader;
    private final AgentBridgeRunnerProperties runnerProperties;
    private final ProjectUnitTestGenerationPreparation preparation;
    private final ProjectUnitTestGenerationBatchRunner batchRunner;
    private final ProjectUnitTestGenerationReportRenderer reportRenderer;
    private final ObjectMapper objectMapper;

    public ProjectUnitTestGenerationWorkflowChain(
            ChainConfigLoader configLoader,
            AgentBridgeRunnerProperties runnerProperties,
            ProjectUnitTestGenerationPreparation preparation,
            ProjectUnitTestGenerationBatchRunner batchRunner,
            ProjectUnitTestGenerationReportRenderer reportRenderer,
            ObjectMapper objectMapper
    ) {
        this.configLoader = configLoader;
        this.runnerProperties = runnerProperties;
        this.preparation = preparation;
        this.batchRunner = batchRunner;
        this.reportRenderer = reportRenderer;
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
        var workspace = WorkflowArtifactWorkspace.start(
                objectMapper, id(), request, properties.getPaths().getOut(), "rerun".equals(mode)
        );
        Path out = workspace.bundleRoot();
        properties.getPaths().setOut(out);
        try (var ignored = WorkflowArtifactContext.open(workspace);
             var repositoryLock = RepositoryExecutionLock.acquire(properties.getProject().getRepo())) {
            if ("full".equals(mode)) {
                preparation.prepare(properties, true);
                Files.deleteIfExists(out.resolve(ProjectUnitTestGenerationBatchRunner.RESULTS_JSON));
                finish(batchRunner.runBatches(properties, out, loadBatches(out)), out);
            } else if (!"rerun".equals(mode)) {
                throw new IllegalArgumentException("project-unit-test-generation mode must be one of: full, rerun");
            } else {
                if (!Files.exists(out.resolve("test-batches.json"))) {
                    preparation.prepare(properties, true);
                }
                if ("test-batch".equals(request.rerunType())) {
                    finish(batchRunner.runBatches(properties, out, batchesByIds(out, request.rerunIds())), out);
                } else if ("verification".equals(request.rerunType())) {
                    finish(batchRunner.verifyBatches(properties, out, loadBatches(out)), out);
                } else {
                    throw new IllegalArgumentException("project-unit-test-generation rerun.type must be one of: test-batch, verification");
                }
            }
            workspace.publish("unit-test-generation-report.md");
        } catch (Exception exception) {
            workspace.markFailed(exception.getMessage());
            throw exception;
        }
    }

    private void finish(List<BatchResult> results, Path out) throws Exception {
        reportRenderer.render(out);
        List<String> failures = results.stream()
                .filter(result -> !result.accepted())
                .map(result -> result.batchId() + ": " + result.failureSummary())
                .toList();
        if (!failures.isEmpty()) {
            throw new IllegalStateException("project-unit-test-generation failed: " + String.join("; ", failures));
        }
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

    private List<Map<String, Object>> loadBatches(Path out) throws Exception {
        Path path = out.resolve("test-batches.json");
        if (!Files.exists(path)) {
            return List.of();
        }
        Map<String, Object> root = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
        return JsonMaps.listOfMaps(root.get("batches"));
    }

    private String batchId(Map<String, Object> batch) {
        Object value = batch.get("batch_id");
        return value == null ? "" : value.toString();
    }

    private String configDir(WorkflowRunRequest request) {
        return request.configDir() == null || request.configDir().isBlank()
                ? runnerProperties.getConfigDir()
                : request.configDir();
    }
}
