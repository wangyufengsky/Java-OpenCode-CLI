package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.AgentBridgeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationBatchRunner;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationPreparation;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationPromptBuilder;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationProperties;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationReportRenderer;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationWorkflowChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    void fullRunUsesAgentBridgeSerialBatchesAndJavaSideValidation() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeSource(properties.getProject().getRepo());
        FakeAgentBridgeClient client = new FakeAgentBridgeClient(properties);

        chain(properties, client).run(request("full", "", ""));

        assertThat(client.prompts).hasSize(2);
        assertThat(client.allPrompts).containsSubsequence(
                "/session-clear",
                client.prompts.get(0),
                "/session-clear",
                client.prompts.get(1)
        );
        assertThat(client.prompts.get(0))
                .contains("batch_input_json:", "上一轮 Java 验收失败摘要", "测试类不存在")
                .contains("只需修改当前批次允许范围内的测试文件")
                .doesNotContain(
                        "get_compilation_errors",
                        "run_command",
                        "list_tests",
                        "MCP",
                        legacySummaryJsonField(),
                        legacySummaryJsonFile(),
                        oldChinesePhrase("中间", "产物"),
                        oldChinesePhrase("额外", "产物")
                );
        assertThat(client.toolNames).containsSubsequence(
                "list_tests", "get_compilation_errors", "run_tests", "read_run_output"
        );
        assertThat(client.toolNames).doesNotContain("run_command", "get_coverage");
        assertThat(client.runTestTargets).containsExactly(
                "com.acme.order.OrderServiceTest", "com.acme.user.UserHelperTest"
        );
        assertThat(client.runTestModules).containsExactly("", "");
        assertThat(properties.getProject().getRepo().resolve("src/test/java/com/acme/order/OrderServiceTest.java")).exists();
        assertThat(properties.getProject().getRepo().resolve("src/test/java/com/acme/user/UserHelperTest.java")).exists();
        assertThat(properties.getPaths().getOut().resolve("verification.json")).doesNotExist();
        assertThat(properties.getPaths().getOut().resolve("agentbridge-results.json")).exists();
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `2`", "failed: `0`");
    }

    @Test
    void allowsWorkflowArtifactsWhenOutputDirectoryIsInsideRepository() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        properties.getPaths().setOut(properties.getProject().getRepo().resolve("project-unit-tests"));
        writeSource(properties.getProject().getRepo());
        FakeAgentBridgeClient client = new FakeAgentBridgeClient(properties);

        chain(properties, client).run(request("full", "", ""));

        assertThat(client.prompts).hasSize(1);
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `1`", "failed: `0`");
    }

    @Test
    void allowsIdeaCompilerOutput() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());

        chain(properties, new RepositoryArtifactWritingClient(
                properties, List.of("out/test/demo/OrderServiceTest.class")
        )).run(request("full", "", ""));

        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `1`", "failed: `0`");
    }

    @Test
    void allowsMavenGeneratedPomArtifacts() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());

        chain(properties, new RepositoryArtifactWritingClient(
                properties, List.of(".flattened-pom.xml", "dependency-reduced-pom.xml")
        )).run(request("full", "", ""));

        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `1`", "failed: `0`");
    }

    @Test
    void allowsIdeaModuleMetadata() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());

        chain(properties, new RepositoryArtifactWritingClient(
                properties, List.of("modules/demo.iml")
        )).run(request("full", "", ""));

        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `1`", "failed: `0`");
    }

    @Test
    void allowsExplicitAdditionalBuildArtifactGlobs() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        objectMapper.readerForUpdating(properties.getTest()).readValue("""
                {
                  "additional-build-artifact-globs": [
                    "generated/**",
                    "**/generated/**"
                  ]
                }
                """);
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());

        chain(properties, new RepositoryArtifactWritingClient(
                properties, List.of("modules/demo/generated/reports/result.json")
        )).run(request("full", "", ""));

        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `1`", "failed: `0`");
    }

    @Test
    void coverageValidationIsOptIn() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getTest().setRequireCoverage(true);
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());
        FakeAgentBridgeClient client = new FakeAgentBridgeClient(properties);

        chain(properties, client).run(request("full", "", ""));

        assertThat(client.commands).allSatisfy(command -> assertThat(command)
                .contains("org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get")
                .contains("org.jacoco:org.jacoco.agent:0.8.15:jar:runtime")
                .contains("-Dsqlite.native.access.argument=--enable-native-access=ALL-UNNAMED -javaagent:")
                .contains("target/jacoco.exec")
                .contains("test")
                .contains("org.jacoco:jacoco-maven-plugin:0.8.15:report"));
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("coverage=95.0%");
    }

    @Test
    void acceptsIdeaRunOutputWithChineseZeroExitCode() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());

        chain(properties, new ZeroExitCodeIdeaClient(properties)).run(request("full", "", ""));

        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `1`", "failed: `0`");
    }

    @Test
    void acceptsIdeaRunOutputWithEnglishZeroExitCode() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());

        chain(properties, new EnglishZeroExitCodeIdeaClient(properties)).run(request("full", "", ""));

        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `1`", "failed: `0`");
    }

    @Test
    void rejectsIdeaRunOutputWithNonZeroExitCode() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getAgentbridge().setMaxAttempts(1);
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());

        assertThatThrownBy(() -> chain(properties, new NonZeroExitCodeIdeaClient(properties)).run(request("full", "", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("目标测试类运行失败");
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `0`", "failed: `1`");
    }

    @Test
    void retriesInterruptedListTestsBeforeStartingAgent() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());
        InterruptedListThenRecoveryClient client = new InterruptedListThenRecoveryClient(properties);

        chain(properties, client).run(request("full", "", ""));

        assertThat(client.listTestCalls).isGreaterThanOrEqualTo(3);
        assertThat(client.prompts).hasSize(1);
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `1`", "failed: `0`");
    }

    @Test
    void reportsPersistentInterruptedListTestsAsMcpFailure() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getAgentbridge().setMaxAttempts(1);
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());
        PersistentInterruptedListClient client = new PersistentInterruptedListClient(properties);

        assertThatThrownBy(() -> chain(properties, client).run(request("full", "", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDE/MCP 调用中断或失败")
                .hasMessageContaining("list_tests");
        assertThat(client.listTestCalls).isGreaterThanOrEqualTo(6);
    }

    @Test
    void retriesInterruptedCompilationCheckBeforeStartingAgent() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());
        InterruptedCompilationThenRecoveryClient client = new InterruptedCompilationThenRecoveryClient(properties);

        chain(properties, client).run(request("full", "", ""));

        assertThat(client.compilationCheckCalls).isGreaterThanOrEqualTo(3);
        assertThat(client.prompts).hasSize(1);
    }

    @Test
    void precheckPassSkipsPrompt() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeSource(properties.getProject().getRepo());
        Path existing = properties.getProject().getRepo().resolve("src/test/java/com/acme/order/OrderServiceTest.java");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "class OrderServiceTest {}\n");
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        FakeAgentBridgeClient client = new FakeAgentBridgeClient(properties);

        chain(properties, client).run(request("full", "", ""));

        assertThat(client.prompts).isEmpty();
        assertThat(client.allPrompts).isEmpty();
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("test-batch-001-orderservice", "accepted: `1`");
    }

    @Test
    void postcheckFailureRetriesUntilAccepted() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeSource(properties.getProject().getRepo());
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        RetrySecondPromptClient client = new RetrySecondPromptClient(properties);

        chain(properties, client).run(request("full", "", ""));

        assertThat(client.prompts).hasSize(2);
        assertThat(client.allPrompts).containsSubsequence(
                "/session-clear",
                client.prompts.get(0),
                "/session-clear",
                client.prompts.get(1)
        );
        assertThat(client.prompts.get(1)).contains("测试类不存在");
    }

    @Test
    void exceedingMaxAttemptsFailsWithLastReason() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getAgentbridge().setMaxAttempts(2);
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());
        NeverFixingClient client = new NeverFixingClient(properties);

        assertThatThrownBy(() -> chain(properties, client).run(request("full", "", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeded agentbridge.max-attempts")
                .hasMessageContaining("测试类不存在");

        assertThat(client.prompts).hasSize(2);
        assertThat(client.allPrompts).containsSubsequence(
                "/session-clear",
                client.prompts.get(0),
                "/session-clear",
                client.prompts.get(1)
        );
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("failed: `1`");
    }

    @Test
    void recordsProtectedFileChangeAndContinuesRemainingBatches() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getPaths().setOut(properties.getProject().getRepo().resolve("project-unit-tests"));
        writeSource(properties.getProject().getRepo());
        ProductionWritingClient client = new ProductionWritingClient(properties);

        chain(properties, client).run(request("full", "", ""));

        assertThat(client.prompts).hasSize(2);
        assertThat(properties.getPaths().getOut().resolve("agentbridge-results.json")).content()
                .contains("\"issues\"", "created protected file: src/main/java/com/acme/build/BuildInfo.java");
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `2`", "failed: `0`")
                .contains("created protected file: src/main/java/com/acme/build/BuildInfo.java");
    }

    @Test
    void allowsAgentToModifyCurrentModulePomAndExistingTest() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());
        writeRootPom(properties.getProject().getRepo());
        Path existingTest = properties.getProject().getRepo().resolve("src/test/java/com/acme/order/OrderServiceLegacyTest.java");
        Files.createDirectories(existingTest.getParent());
        Files.writeString(existingTest, "class OrderServiceLegacyTest {}\n");

        chain(properties, new CurrentModulePomAndExistingTestWritingClient(properties)).run(request("full", "", ""));

        assertThat(properties.getProject().getRepo().resolve("pom.xml")).content()
                .contains("test dependency repair");
        assertThat(existingTest).content().contains("updated for generated test");
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `1`", "failed: `0`");
    }

    @Test
    void ignoresAgentBridgeLocalStateWhenProtectingProductionFiles() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());

        chain(properties, new AgentBridgeStateWritingClient(properties)).run(request("full", "", ""));

        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `1`", "failed: `0`");
    }

    @Test
    void ignoresIdeaLocalStateWhenProtectingProductionFiles() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());

        chain(properties, new IdeaStateWritingClient(properties)).run(request("full", "", ""));

        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `1`", "failed: `0`");
    }

    @Test
    void recordsWhenAgentCreatesTestOutsideCurrentBatchModule() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeModuleSource(properties.getProject().getRepo());

        chain(properties, new CrossModuleTestWritingClient(properties)).run(request("full", "", ""));

        assertThat(properties.getPaths().getOut().resolve("agentbridge-results.json")).content()
                .contains("\"accepted\" : true", "\"issues\"")
                .contains(
                        "created protected file",
                        "upfs-common/src/test/java/com/spdb/upfs/common/CommonServiceTest.java"
                );
    }

    @Test
    void recordsWhenAgentModifiesPomOutsideCurrentBatchModule() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("upfs-cup/src/main/java/com/spdb/upfs/cup"));
        writeModuleSource(properties.getProject().getRepo());

        chain(properties, new CrossModulePomWritingClient(properties)).run(request("full", "", ""));

        assertThat(properties.getPaths().getOut().resolve("agentbridge-results.json")).content()
                .contains("\"accepted\" : true", "\"issues\"")
                .contains("modified protected file", "upfs-common/pom.xml");
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `1`", "failed: `0`", "recorded issues: `1`")
                .contains("modified protected file: upfs-common/pom.xml");
    }

    @Test
    void rerunRejectsUnknownBatchId() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeSource(properties.getProject().getRepo());
        ProjectUnitTestGenerationWorkflowChain chain = chain(properties, new FakeAgentBridgeClient(properties));
        chain.run(request("full", "", ""));

        assertThatThrownBy(() -> chain.run(request("rerun", "test-batch", "missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown unit-test batch id");
    }

    @Test
    void rerunSingleBatchPreservesOtherBatchResults() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeSource(properties.getProject().getRepo());
        ProjectUnitTestGenerationWorkflowChain chain = chain(properties, new FakeAgentBridgeClient(properties));
        chain.run(request("full", "", ""));

        chain.run(request("rerun", "test-batch", "test-batch-001-orderservice"));

        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("test-batch-001-orderservice", "test-batch-002-userhelper")
                .contains("accepted: `2`", "failed: `0`");
    }

    @Test
    void verificationRerunRefreshesAgentBridgeValidation() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeSource(properties.getProject().getRepo());
        FakeAgentBridgeClient client = new FakeAgentBridgeClient(properties);
        ProjectUnitTestGenerationWorkflowChain chain = chain(properties, client);
        chain.run(request("full", "", ""));
        Files.delete(properties.getProject().getRepo().resolve("src/test/java/com/acme/order/OrderServiceTest.java"));
        int callsAfterFullRun = client.toolNames.size();

        assertThatThrownBy(() -> chain.run(request("rerun", "verification", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("测试类不存在");

        assertThat(client.toolNames.size()).isGreaterThan(callsAfterFullRun);
    }

    private ProjectUnitTestGenerationWorkflowChain chain(ProjectUnitTestGenerationProperties properties, AgentBridgeClient client) {
        ProjectUnitTestGenerationPromptBuilder promptBuilder = new ProjectUnitTestGenerationPromptBuilder(new DefaultResourceLoader());
        return new ProjectUnitTestGenerationWorkflowChain(
                new FixedChainConfigLoader(properties),
                new AgentBridgeRunnerProperties(),
                new ProjectUnitTestGenerationPreparation(objectMapper),
                new ProjectUnitTestGenerationBatchRunner(client, promptBuilder, objectMapper),
                new ProjectUnitTestGenerationReportRenderer(objectMapper),
                objectMapper
        );
    }

    private ProjectUnitTestGenerationProperties properties() {
        ProjectUnitTestGenerationProperties properties = new ProjectUnitTestGenerationProperties();
        properties.getProject().setId("demo");
        properties.getProject().setName("Demo");
        properties.getProject().setRepo(tempDir.resolve("repo"));
        properties.getPaths().setOut(tempDir.resolve("out"));
        properties.getAgentbridge().setWebBaseUrl("http://127.0.0.1:9642");
        properties.getAgentbridge().setMcpUrl("http://127.0.0.1:8642/mcp");
        properties.getAgentbridge().setTimeoutMinutes(1);
        properties.getTest().setJacocoJvmArgProperty("sqlite.native.access.argument");
        properties.getTest().setJacocoJvmArgBase("--enable-native-access=ALL-UNNAMED");
        return properties;
    }

    private WorkflowRunRequest request(String mode, String rerunType, String rerunId) {
        AgentBridgeSettings settings = new AgentBridgeSettings();
        return new WorkflowRunRequest(mode, rerunType, rerunId, LocalDate.of(2026, 7, 7), settings);
    }

    private static String legacySummaryJsonField() {
        return "summary" + "_json";
    }

    private static String legacySummaryJsonFile() {
        return "summary" + ".json";
    }

    private static String oldChinesePhrase(String first, String second) {
        return first + second;
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

    private void writeRootPom(Path repo) throws Exception {
        Files.writeString(repo.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>\n");
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
        Files.createDirectories(repo.resolve("upfs-common"));
        Files.writeString(repo.resolve("upfs-common/pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>\n");
        Files.createDirectories(repo.resolve("upfs-cup"));
        Files.writeString(repo.resolve("upfs-cup/pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>\n");
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

    private class FakeAgentBridgeClient extends AgentBridgeClient {
        protected final ProjectUnitTestGenerationProperties properties;
        protected final CopyOnWriteArrayList<String> allPrompts = new CopyOnWriteArrayList<>();
        protected final CopyOnWriteArrayList<String> prompts = new CopyOnWriteArrayList<>();
        protected final CopyOnWriteArrayList<String> toolNames = new CopyOnWriteArrayList<>();
        protected final CopyOnWriteArrayList<String> commands = new CopyOnWriteArrayList<>();
        protected final CopyOnWriteArrayList<String> runTestTargets = new CopyOnWriteArrayList<>();
        protected final CopyOnWriteArrayList<String> runTestModules = new CopyOnWriteArrayList<>();

        FakeAgentBridgeClient(ProjectUnitTestGenerationProperties properties) {
            super(objectMapper);
            this.properties = properties;
        }

        @Override
        public void postPrompt(URI webBaseUrl, String prompt) {
            allPrompts.add(prompt);
            if ("/session-clear".equals(prompt)) {
                return;
            }
            prompts.add(prompt);
            try {
                createTargetTest(prompt);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public void waitUntilIdle(URI webBaseUrl, Duration timeout, Duration pollInterval) {
        }

        @Override
        public ToolResponse callTool(URI mcpUrl, String name, JsonNode arguments) {
            toolNames.add(name);
            return switch (name) {
                case "list_tests" -> listTests(arguments);
                case "get_compilation_errors" -> json(Map.of("errors", List.of()), "No compilation errors");
                case "run_command" -> runCommand(arguments);
                case "run_tests" -> runTests(arguments);
                case "read_run_output" -> json(Map.of(), readRunOutput());
                default -> json(Map.of(), "");
            };
        }

        protected ToolResponse runTests(JsonNode arguments) {
            runTestTargets.add(arguments.path("target").asText());
            runTestModules.add(arguments.path("module").asText());
            ObjectNode raw = objectMapper.createObjectNode();
            raw.put("isError", false);
            raw.putArray("content").addObject()
                    .put("type", "text")
                    .put("text", "Test Results: (See detailed results in the IDE's Run panel)");
            return new ToolResponse(raw, "Test Results: (See detailed results in the IDE's Run panel)", objectMapper.createObjectNode());
        }

        protected String readRunOutput() {
            return "=== Summary: 1 passed, 0 failed, 0 ignored (1 total) ===";
        }

        protected ToolResponse runCommand(JsonNode arguments) {
            String command = arguments.path("command").asText();
            commands.add(command);
            String testClass = command.contains("OrderServiceTest") ? "com.acme.order.OrderService" : "com.acme.user.UserHelper";
            try {
                writeJacocoReport(testClass, 95);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            return json(Map.of("success", true), "Command succeeded");
        }

        protected ToolResponse listTests(JsonNode arguments) {
            String fileName = arguments.path("file_pattern").asText();
            boolean exists = findTest(fileName) != null;
            return json(Map.of("tests", exists ? List.of(fileName) : List.of()), exists ? fileName : "no tests");
        }

        protected double coverage(String sourceClass) {
            String testName = sourceClass.substring(sourceClass.lastIndexOf('.') + 1) + "Test.java";
            return findTest(testName) == null ? -1 : 95;
        }

        protected void writeJacocoReport(String sourceClass, int percent) throws Exception {
            Path report = properties.getProject().getRepo().resolve("target/site/jacoco/jacoco.xml");
            Files.createDirectories(report.getParent());
            int covered = percent;
            int missed = 100 - percent;
            String classPath = sourceClass.replace('.', '/');
            int slash = classPath.lastIndexOf('/');
            String packageName = slash < 0 ? "" : classPath.substring(0, slash);
            Files.writeString(report, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                    <report name="demo">
                      <package name="%s">
                        <class name="%s">
                          <counter type="LINE" missed="%d" covered="%d"/>
                        </class>
                      </package>
                    </report>
                    """.formatted(packageName, classPath, missed, covered));
        }

        protected boolean testExists(String target) {
            String fileName = target.substring(target.lastIndexOf('.') + 1) + ".java";
            return findTest(fileName) != null;
        }

        protected Path findTest(String fileName) {
            try (var stream = Files.walk(properties.getProject().getRepo())) {
                return stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals(fileName))
                        .findFirst()
                        .orElse(null);
            } catch (Exception exception) {
                return null;
            }
        }

        protected void createTargetTest(String prompt) throws Exception {
            Path input = batchInput(prompt);
            JsonNode batch = objectMapper.readTree(input.toFile());
            String testPath = batch.path("target_test_files").get(0).asText();
            Path testFile = properties.getProject().getRepo().resolve(testPath);
            Files.createDirectories(testFile.getParent());
            Files.writeString(testFile, "class " + testFile.getFileName().toString().replace(".java", "") + " {}\n");
        }

        protected Path batchInput(String prompt) {
            for (String line : prompt.split("\\R")) {
                if (line.startsWith("batch_input_json:")) {
                    return Path.of(line.substring("batch_input_json:".length()).trim());
                }
            }
            throw new IllegalArgumentException("missing batch_input_json in prompt");
        }

        protected ToolResponse json(Map<String, ?> value, String text) {
            JsonNode structured = objectMapper.valueToTree(value);
            ObjectNode raw = objectMapper.createObjectNode();
            raw.set("structuredContent", structured);
            return new ToolResponse(raw, text, structured);
        }

        protected ToolResponse interrupted(String tool) {
            ObjectNode raw = objectMapper.createObjectNode();
            raw.put("isError", true);
            raw.putArray("content").addObject()
                    .put("type", "text")
                    .put("text", "Error [INTERNAL_ERROR]: java.lang.InterruptedException: " + tool);
            return new ToolResponse(raw, "Error [INTERNAL_ERROR]: java.lang.InterruptedException: " + tool,
                    objectMapper.createObjectNode());
        }
    }

    private class InterruptedListThenRecoveryClient extends FakeAgentBridgeClient {
        private int listTestCalls;

        InterruptedListThenRecoveryClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        public ToolResponse callTool(URI mcpUrl, String name, JsonNode arguments) {
            if ("list_tests".equals(name) && ++listTestCalls == 1) {
                return interrupted(name);
            }
            return super.callTool(mcpUrl, name, arguments);
        }
    }

    private class PersistentInterruptedListClient extends FakeAgentBridgeClient {
        private int listTestCalls;

        PersistentInterruptedListClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        public ToolResponse callTool(URI mcpUrl, String name, JsonNode arguments) {
            if ("list_tests".equals(name)) {
                listTestCalls++;
                return interrupted(name);
            }
            return super.callTool(mcpUrl, name, arguments);
        }
    }

    private class InterruptedCompilationThenRecoveryClient extends FakeAgentBridgeClient {
        private int compilationCheckCalls;

        InterruptedCompilationThenRecoveryClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        public ToolResponse callTool(URI mcpUrl, String name, JsonNode arguments) {
            if ("get_compilation_errors".equals(name) && ++compilationCheckCalls == 1) {
                return interrupted(name);
            }
            return super.callTool(mcpUrl, name, arguments);
        }
    }

    private class RetrySecondPromptClient extends FakeAgentBridgeClient {
        RetrySecondPromptClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        protected void createTargetTest(String prompt) throws Exception {
            if (prompts.size() >= 2) {
                super.createTargetTest(prompt);
            }
        }
    }

    private class NeverFixingClient extends FakeAgentBridgeClient {
        NeverFixingClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        protected void createTargetTest(String prompt) {
        }
    }

    private class ZeroExitCodeIdeaClient extends FakeAgentBridgeClient {
        ZeroExitCodeIdeaClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        protected String readRunOutput() {
            return "进程已结束，退出代码为 0";
        }
    }

    private class NonZeroExitCodeIdeaClient extends FakeAgentBridgeClient {
        NonZeroExitCodeIdeaClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        protected String readRunOutput() {
            return "进程已结束，退出代码为 1";
        }
    }

    private class EnglishZeroExitCodeIdeaClient extends FakeAgentBridgeClient {
        EnglishZeroExitCodeIdeaClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        protected String readRunOutput() {
            return "Process finished with exit code 0";
        }
    }

    private class ProductionWritingClient extends FakeAgentBridgeClient {
        ProductionWritingClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        protected void createTargetTest(String prompt) throws Exception {
            Path productionFile = properties.getProject().getRepo().resolve("src/main/java/com/acme/build/BuildInfo.java");
            Files.createDirectories(productionFile.getParent());
            Files.writeString(productionFile, "package com.acme.build; class BuildInfo {}\n");
            super.createTargetTest(prompt);
        }
    }

    private class RepositoryArtifactWritingClient extends FakeAgentBridgeClient {
        private final List<String> relativePaths;

        RepositoryArtifactWritingClient(ProjectUnitTestGenerationProperties properties, List<String> relativePaths) {
            super(properties);
            this.relativePaths = relativePaths;
        }

        @Override
        protected void createTargetTest(String prompt) throws Exception {
            for (String relativePath : relativePaths) {
                Path artifact = properties.getProject().getRepo().resolve(relativePath);
                Files.createDirectories(artifact.getParent());
                Files.writeString(artifact, "generated\n");
            }
            super.createTargetTest(prompt);
        }
    }

    private class CurrentModulePomAndExistingTestWritingClient extends FakeAgentBridgeClient {
        CurrentModulePomAndExistingTestWritingClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        protected void createTargetTest(String prompt) throws Exception {
            Path pom = properties.getProject().getRepo().resolve("pom.xml");
            Files.writeString(pom, Files.readString(pom) + "<!-- test dependency repair -->\n");
            Path existingTest = properties.getProject().getRepo().resolve("src/test/java/com/acme/order/OrderServiceLegacyTest.java");
            Files.writeString(existingTest, Files.readString(existingTest) + "// updated for generated test\n");
            super.createTargetTest(prompt);
        }
    }

    private class AgentBridgeStateWritingClient extends FakeAgentBridgeClient {
        AgentBridgeStateWritingClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        protected void createTargetTest(String prompt) throws Exception {
            Path stateFile = properties.getProject().getRepo().resolve(".agentbridge/conversation.db-shm");
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, "agentbridge local state\n");
            super.createTargetTest(prompt);
        }
    }

    private class IdeaStateWritingClient extends FakeAgentBridgeClient {
        IdeaStateWritingClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        protected void createTargetTest(String prompt) throws Exception {
            Path stateFile = properties.getProject().getRepo().resolve(".idea/workspace.xml");
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, "<project version=\"4\" />\n");
            super.createTargetTest(prompt);
        }
    }

    private class CrossModuleTestWritingClient extends FakeAgentBridgeClient {
        CrossModuleTestWritingClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        protected void createTargetTest(String prompt) throws Exception {
            Path unrelatedTest = properties.getProject().getRepo()
                    .resolve("upfs-common/src/test/java/com/spdb/upfs/common/CommonServiceTest.java");
            Files.createDirectories(unrelatedTest.getParent());
            Files.writeString(unrelatedTest, "class CommonServiceTest {}\n");
            super.createTargetTest(prompt);
        }
    }

    private class CrossModulePomWritingClient extends FakeAgentBridgeClient {
        CrossModulePomWritingClient(ProjectUnitTestGenerationProperties properties) {
            super(properties);
        }

        @Override
        protected void createTargetTest(String prompt) throws Exception {
            Path pom = properties.getProject().getRepo().resolve("upfs-common/pom.xml");
            Files.writeString(pom, Files.readString(pom) + "<!-- forbidden -->\n");
            super.createTargetTest(prompt);
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
