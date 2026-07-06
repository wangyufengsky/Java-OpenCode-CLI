package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.opencode.OpenCodeRunResult;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerHandle;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.opencode.ValidatedOpenCodeTaskSpec;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate;
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

        assertThat(taskRunner.titles).containsExactly(
                "project-unit-test-generation-test-batch-001-orderservice",
                "project-unit-test-generation-test-batch-002-userhelper"
        );
        assertThat(taskRunner.prompts.get(0))
                .contains("project-unit-test-generation 单元测试批次")
                .contains("batch_input_json:")
                .contains("一个 task 只包含一个 Java 顶层类型")
                .contains("只允许创建或修改目标项目 src/test/** 下的测试文件")
                .contains("读取 batch_input_json、源码、已有测试和文档时，必须使用 `AgentBridge` MCP 文件读取工具")
                .contains("创建或修改测试文件、写入 summary_json 时，必须使用 `AgentBridge` MCP 文件编辑工具")
                .contains("先查阅当前项目已有单元测试")
                .contains("开始写代码前，先判断本 task 是需要新写测试、补充已有测试，还是已有测试已经满足覆盖率")
                .contains("写完或修改测试文件后，必须调用 `AgentBridge` MCP 诊断工具：`get_compilation_errors`")
                .contains("如果目标类已经存在单元测试，开始写代码前也必须先执行 `get_compilation_errors`")
                .contains("调用 `run_tests` 跑当前批次相关测试")
                .contains("run_tests 失败时，根据失败原因修改测试")
                .contains("调用 `get_coverage` 采集当前类覆盖率")
                .contains("覆盖率未达标时必须新增测试场景")
                .contains("回到 `get_compilation_errors` 继续循环")
                .doesNotContain("不要在批次 worker 内调用 `run_tests`", "并发执行多个批次")
                .contains("如果目标类已经存在单元测试，先用 `get_coverage` 检查该类覆盖率")
                .contains("覆盖率达到 batch_input_json.coverage.threshold_percent 时跳过该类")
                .contains("覆盖率未达标时只补充该类已有测试或目标测试文件")
                .contains("`AgentBridge` MCP 读写工具不可用时必须写 `blocked` 或返回 `BLOCKED`")
                .doesNotContain("OpenCode 原生文件")
                .doesNotContain("intellij-index", "intellij-idea");
        assertThat(properties.getProject().getRepo().resolve("src/test/java/com/acme/order/OrderServiceTest.java")).exists();
        assertThat(properties.getProject().getRepo().resolve("src/test/java/com/acme/user/UserHelperTest.java")).exists();
        assertThat(properties.getPaths().getOut().resolve("verification.json")).exists();
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("project-unit-test-generation", "test-batch-001-orderservice", "test-batch-002-userhelper");
    }

    @Test
    void fullRunAllowsGeneratedTestsUnderModuleSrcTest() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeModuleSource(properties.getProject().getRepo());
        CapturingTaskRunner taskRunner = new CapturingTaskRunner(properties);

        chain(properties, taskRunner).run(request("full", "", ""));

        assertThat(taskRunner.titles).containsExactly("project-unit-test-generation-test-batch-001-cupservice");
        assertThat(properties.getProject().getRepo()
                .resolve("upfs-cup/src/test/java/com/spdb/upfs/cup/CupServiceTest.java")).exists();
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
        Path staleSummary = properties.getPaths().getOut().resolve("test-batches/test-batch-001-orderservice/summary.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(staleSummary.toFile(), Map.of(
                "batch_id", "test-batch-001-orderservice",
                "status", "completed",
                "source_files", List.of("src/main/java/com/acme/order/OrderService.java"),
                "test_files", List.of("src/test/java/com/acme/order/OrderServiceTest.java"),
                "checks", passedChecks(),
                "notes", List.of()
        ));
        Path testFile = properties.getProject().getRepo().resolve("src/test/java/com/acme/order/OrderServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "class OrderServiceTest {}\n");

        assertThatThrownBy(() -> chain(properties, new NoopTaskRunner()).run(request("rerun", "test-batch", "test-batch-001-orderservice")))
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

    @Test
    void blockedBatchRerunsInFreshAgentUntilCompleted() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeSource(properties.getProject().getRepo());
        BlockingThenCompletingTaskRunner taskRunner = new BlockingThenCompletingTaskRunner(properties);

        chain(properties, taskRunner).run(request("full", "", ""));

        assertThat(taskRunner.titles).containsExactly(
                "project-unit-test-generation-test-batch-001-orderservice",
                "project-unit-test-generation-test-batch-001-orderservice",
                "project-unit-test-generation-test-batch-002-userhelper"
        );
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("completed: `2`")
                .doesNotContain("blocked: `1`");
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
        Files.createDirectories(repo.resolve("src/main/java/com/acme/user"));
        Files.writeString(repo.resolve("src/main/java/com/acme/user/UserHelper.java"), """
                package com.acme.user;
                public final class UserHelper {
                    private UserHelper() {
                    }
                    public static String normalize(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """);
    }

    private void writeModuleSource(Path repo) throws Exception {
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.spdb</groupId>
                  <artifactId>upfs-nl-json</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>upfs-cup</module>
                  </modules>
                </project>
                """);
        Files.createDirectories(repo.resolve("upfs-cup/src/main/java/com/spdb/upfs/cup"));
        Files.writeString(repo.resolve("upfs-cup/src/main/java/com/spdb/upfs/cup/CupService.java"), """
                package com.spdb.upfs.cup;
                public class CupService {
                    public String handle(String value) {
                        return value;
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

    private class CapturingTaskRunner extends OpenCodeServerTaskRunner {
        protected final ProjectUnitTestGenerationProperties properties;
        protected final CopyOnWriteArrayList<String> titles = new CopyOnWriteArrayList<>();
        protected final CopyOnWriteArrayList<String> prompts = new CopyOnWriteArrayList<>();

        CapturingTaskRunner(ProjectUnitTestGenerationProperties properties) {
            super(null, null);
            this.properties = properties;
        }

        @Override
        public OpenCodeRunResult runUntilValidated(ValidatedOpenCodeTaskSpec spec) throws Exception {
            titles.add(spec.title());
            prompts.add(Files.readString(spec.promptFile()));
            Map<String, Object> batch = objectMapper.readValue(spec.runDir().resolve("input.json").toFile(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            String batchId = batch.get("batch_id").toString();
            List<String> sourceFiles = ((List<?>) batch.get("source_files")).stream().map(Object::toString).toList();
            String testPath = ((List<?>) batch.get("target_test_files")).get(0).toString();
            Path testFile = properties.getProject().getRepo().resolve(testPath);
            Files.createDirectories(testFile.getParent());
            Files.writeString(testFile, "class " + testFile.getFileName().toString().replace(".java", "") + " {}\n");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(spec.runDir().resolve("summary.json").toFile(), Map.of(
                    "batch_id", batchId,
                    "status", "completed",
                    "source_files", sourceFiles,
                    "test_files", List.of(testPath),
                    "checks", passedChecks(),
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

    private class BlockingThenCompletingTaskRunner extends CapturingTaskRunner {
        private int orderServiceRuns;

        BlockingThenCompletingTaskRunner(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        public OpenCodeRunResult runUntilValidated(ValidatedOpenCodeTaskSpec spec) throws Exception {
            if (!spec.title().endsWith("test-batch-001-orderservice")) {
                return super.runUntilValidated(spec);
            }
            orderServiceRuns++;
            if (orderServiceRuns > 1) {
                return super.runUntilValidated(spec);
            }
            titles.add(spec.title());
            prompts.add(Files.readString(spec.promptFile()));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(spec.runDir().resolve("summary.json").toFile(), Map.of(
                    "batch_id", "test-batch-001-orderservice",
                    "status", "blocked",
                    "source_files", List.of("src/main/java/com/acme/order/OrderService.java"),
                    "test_files", List.of(),
                    "notes", List.of("missing dependencies")
            ));
            return null;
        }
    }

    private Map<String, Object> passedChecks() {
        return Map.of(
                "style_reviewed", true,
                "compilation", Map.of("passed", true),
                "tests", Map.of("passed", true),
                "coverage", Map.of("percent", 80)
        );
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
