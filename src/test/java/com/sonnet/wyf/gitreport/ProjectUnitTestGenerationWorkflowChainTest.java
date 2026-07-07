package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.OpenCodeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationAgentBridgeClient;
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
        assertThat(client.prompts.get(0))
                .contains("batch_input_json:", "上一轮 Java 验收失败摘要", "测试类不存在")
                .contains("不需要写 `summary_json`")
                .doesNotContain("get_compilation_errors", "run_tests", "get_coverage", "list_tests", "MCP");
        assertThat(client.toolNames).containsSubsequence(
                "list_tests", "get_compilation_errors", "run_tests", "get_coverage"
        );
        assertThat(properties.getProject().getRepo().resolve("src/test/java/com/acme/order/OrderServiceTest.java")).exists();
        assertThat(properties.getProject().getRepo().resolve("src/test/java/com/acme/user/UserHelperTest.java")).exists();
        assertThat(properties.getPaths().getOut().resolve("verification.json")).doesNotExist();
        assertThat(properties.getPaths().getOut().resolve("agentbridge-results.json")).exists();
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("accepted: `2`", "failed: `0`");
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
        assertThat(properties.getPaths().getOut().resolve("unit-test-generation-report.md")).content()
                .contains("failed: `1`");
    }

    @Test
    void failsWhenAgentCreatesProductionFile() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("com.acme.order"));
        writeSource(properties.getProject().getRepo());

        assertThatThrownBy(() -> chain(properties, new ProductionWritingClient(properties)).run(request("full", "", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("created protected file")
                .hasMessageContaining("src/main/java/com/acme/build/BuildInfo.java");
    }

    @Test
    void failsWhenAgentCreatesTestOutsideCurrentBatchModule() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeModuleSource(properties.getProject().getRepo());

        assertThatThrownBy(() -> chain(properties, new CrossModuleTestWritingClient(properties)).run(request("full", "", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("created protected file")
                .hasMessageContaining("upfs-common/src/test/java/com/spdb/upfs/common/CommonServiceTest.java");
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

    private ProjectUnitTestGenerationWorkflowChain chain(ProjectUnitTestGenerationProperties properties, ProjectUnitTestGenerationAgentBridgeClient client) {
        ProjectUnitTestGenerationPromptBuilder promptBuilder = new ProjectUnitTestGenerationPromptBuilder(new DefaultResourceLoader());
        return new ProjectUnitTestGenerationWorkflowChain(
                new FixedChainConfigLoader(properties),
                new OpenCodeRunnerProperties(),
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
        return properties;
    }

    private WorkflowRunRequest request(String mode, String rerunType, String rerunId) {
        OpenCodeSettings settings = new OpenCodeSettings();
        return new WorkflowRunRequest(mode, rerunType, rerunId, LocalDate.of(2026, 7, 7), settings);
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

    private class FakeAgentBridgeClient extends ProjectUnitTestGenerationAgentBridgeClient {
        protected final ProjectUnitTestGenerationProperties properties;
        protected final CopyOnWriteArrayList<String> prompts = new CopyOnWriteArrayList<>();
        protected final CopyOnWriteArrayList<String> toolNames = new CopyOnWriteArrayList<>();

        FakeAgentBridgeClient(ProjectUnitTestGenerationProperties properties) {
            super(objectMapper);
            this.properties = properties;
        }

        @Override
        public void postPrompt(URI webBaseUrl, String prompt) {
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
                case "run_tests" -> json(Map.of("success", testExists(arguments.path("target").asText())), "passed");
                case "get_coverage" -> json(Map.of("percent", coverage(arguments.path("file").asText())), "coverage " + coverage(arguments.path("file").asText()) + "%");
                default -> json(Map.of(), "");
            };
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
