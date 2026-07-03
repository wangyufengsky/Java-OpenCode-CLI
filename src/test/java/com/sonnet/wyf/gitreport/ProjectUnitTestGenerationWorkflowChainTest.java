package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.opencode.OpenCodeRunResult;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerHandle;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.opencode.ValidatedOpenCodeTaskSpec;
import com.sonnet.wyf.gitreport.orchestration.ConcurrentWorkflowTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate;
import com.sonnet.wyf.gitreport.orchestration.TaskRunResult;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.OpenCodeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationOutputValidator;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationPreparation;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationPromptBuilder;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationProperties;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationReportRenderer;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationVerifier;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationWorkflowChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectUnitTestGenerationWorkflowChainTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void fullRunGeneratesBatchesRunsOpenCodeAndVerifies() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeSource(properties.getProject().getRepo());
        CapturingTaskRunner taskRunner = new CapturingTaskRunner(properties);

        chain(properties, taskRunner).run(request("full", "", ""));

        assertThat(taskRunner.titles).containsExactly("project-unit-test-generation-test-batch-001-com-acme-order");
        assertThat(taskRunner.prompts.get(0))
                .contains("project-unit-test-generation 单元测试批次")
                .contains("batch_input_json:")
                .contains("只允许创建或修改目标项目 src/test/** 下的测试文件");
        assertThat(properties.getProject().getRepo().resolve("src/test/java/com/acme/order/OrderServiceTest.java")).exists();
        assertThat(properties.getPaths().getOut().resolve("verification.json")).exists();
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("project-unit-test-generation", "test-batch-001-com-acme-order");
    }

    @Test
    void rerunRejectsUnknownBatchId() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeSource(properties.getProject().getRepo());
        ProjectUnitTestGenerationWorkflowChain chain = chain(properties, new CapturingTaskRunner(properties));
        chain.run(request("full", "", ""));

        assertThatThrownBy(() -> chain.run(request("rerun", "test-batch", "missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown unit-test batch id");
    }

    @Test
    void deletesStaleSummaryBeforeRunningBatch() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeSource(properties.getProject().getRepo());
        ProjectUnitTestGenerationPreparation preparation = new ProjectUnitTestGenerationPreparation(objectMapper);
        preparation.prepare(properties, true);
        Path staleSummary = properties.getPaths().getOut().resolve("test-batches/test-batch-001-com-acme-order/summary.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(staleSummary.toFile(), Map.of(
                "batch_id", "test-batch-001-com-acme-order",
                "status", "completed",
                "source_files", List.of("src/main/java/com/acme/order/OrderService.java"),
                "test_files", List.of("src/test/java/com/acme/order/OrderServiceTest.java"),
                "notes", List.of()
        ));
        Path testFile = properties.getProject().getRepo().resolve("src/test/java/com/acme/order/OrderServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "class OrderServiceTest {}\n");

        assertThatThrownBy(() -> chain(properties, new NoopTaskRunner()).run(request("rerun", "test-batch", "test-batch-001-com-acme-order")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outputs incomplete");
    }

    @Test
    void failsWhenWorkerModifiesProductionFiles() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeSource(properties.getProject().getRepo());

        assertThatThrownBy(() -> chain(properties, new ProductionWritingTaskRunner(properties)).run(request("full", "", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protected file");
    }

    private ProjectUnitTestGenerationWorkflowChain chain(ProjectUnitTestGenerationProperties properties, OpenCodeServerTaskRunner taskRunner) {
        return new ProjectUnitTestGenerationWorkflowChain(
                new FixedChainConfigLoader(properties),
                new OpenCodeRunnerProperties(),
                new ProjectUnitTestGenerationPreparation(objectMapper),
                new ProjectUnitTestGenerationPromptBuilder(new DefaultResourceLoader()),
                new ProjectUnitTestGenerationOutputValidator(objectMapper),
                new ProjectUnitTestGenerationVerifier(objectMapper),
                new ProjectUnitTestGenerationReportRenderer(objectMapper),
                fakeServerManager(),
                taskRunner,
                directTaskRunner(),
                new OutputCompletionGate(objectMapper, 1),
                objectMapper
        );
    }

    private ProjectUnitTestGenerationProperties properties() {
        ProjectUnitTestGenerationProperties properties = new ProjectUnitTestGenerationProperties();
        properties.getProject().setId("demo");
        properties.getProject().setName("Demo");
        properties.getProject().setRepo(tempDir.resolve("repo"));
        properties.getPaths().setOut(tempDir.resolve("out"));
        properties.getTest().setVerifyCommand(List.of("sh", "-c", "printf ok"));
        properties.getTest().setMaxTypesPerTask(10);
        return properties;
    }

    private WorkflowRunRequest request(String mode, String rerunType, String rerunId) {
        OpenCodeSettings settings = new OpenCodeSettings();
        settings.setConcurrency(1);
        settings.setMaxConcurrency(1);
        settings.setTimeoutMinutes(3);
        return new WorkflowRunRequest(mode, rerunType, rerunId, LocalDate.of(2026, 7, 3), settings);
    }

    private void writeSource(Path repo) throws Exception {
        Files.createDirectories(repo.resolve("src/main/java/com/acme/order"));
        Files.writeString(repo.resolve("src/main/java/com/acme/order/OrderService.java"), """
                package com.acme.order;
                public class OrderService {
                    public String place(String sku) {
                        return sku;
                    }
                }
                """);
    }

    private OpenCodeServerManager fakeServerManager() {
        return new OpenCodeServerManager(null, null) {
            @Override
            public synchronized OpenCodeServerHandle ensureReady(OpenCodeSettings settings, Path out) {
                return new OpenCodeServerHandle(URI.create("http://127.0.0.1:1"), false);
            }
        };
    }

    private ConcurrentWorkflowTaskRunner directTaskRunner() {
        return new ConcurrentWorkflowTaskRunner(Runnable::run) {
            @Override
            public <T> List<TaskRunResult> run(String workflowName, List<T> tasks, int concurrency, java.util.function.Function<T, String> taskKey, java.util.function.Function<T, java.util.concurrent.Callable<TaskRunResult>> taskFactory) throws Exception {
                java.util.ArrayList<TaskRunResult> results = new java.util.ArrayList<>();
                for (T task : tasks) {
                    results.add(taskFactory.apply(task).call());
                }
                return results;
            }
        };
    }

    private class CapturingTaskRunner extends OpenCodeServerTaskRunner {
        protected final ProjectUnitTestGenerationProperties properties;
        private final CopyOnWriteArrayList<String> titles = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> prompts = new CopyOnWriteArrayList<>();

        CapturingTaskRunner(ProjectUnitTestGenerationProperties properties) {
            super(null, null);
            this.properties = properties;
        }

        @Override
        public OpenCodeRunResult runUntilValidated(ValidatedOpenCodeTaskSpec spec) throws Exception {
            titles.add(spec.title());
            prompts.add(Files.readString(spec.promptFile()));
            Path testFile = properties.getProject().getRepo().resolve("src/test/java/com/acme/order/OrderServiceTest.java");
            Files.createDirectories(testFile.getParent());
            Files.writeString(testFile, "package com.acme.order; class OrderServiceTest {}\n");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(spec.runDir().resolve("summary.json").toFile(), Map.of(
                    "batch_id", "test-batch-001-com-acme-order",
                    "status", "completed",
                    "source_files", List.of("src/main/java/com/acme/order/OrderService.java"),
                    "test_files", List.of("src/test/java/com/acme/order/OrderServiceTest.java"),
                    "notes", List.of()
            ));
            return null;
        }
    }

    private static class NoopTaskRunner extends OpenCodeServerTaskRunner {
        NoopTaskRunner() {
            super(null, null);
        }

        @Override
        public OpenCodeRunResult runUntilValidated(ValidatedOpenCodeTaskSpec spec) {
            return null;
        }
    }

    private class ProductionWritingTaskRunner extends CapturingTaskRunner {
        ProductionWritingTaskRunner(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        public OpenCodeRunResult runUntilValidated(ValidatedOpenCodeTaskSpec spec) throws Exception {
            Files.writeString(properties.getProject().getRepo().resolve("pom.xml"), "<project/>\n");
            return super.runUntilValidated(spec);
        }
    }

    private static class FixedChainConfigLoader extends ChainConfigLoader {
        private final ProjectUnitTestGenerationProperties properties;

        FixedChainConfigLoader(ProjectUnitTestGenerationProperties properties) {
            super(new DefaultResourceLoader());
            this.properties = properties;
        }

        @Override
        public <T> T load(String configDir, String chainId, Class<T> type) {
            return type.cast(properties);
        }
    }
}
