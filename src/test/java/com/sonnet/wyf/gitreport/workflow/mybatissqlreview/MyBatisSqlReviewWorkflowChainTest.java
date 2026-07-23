package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import com.sonnet.wyf.gitreport.console.WorkflowEventSink;
import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.AgentBridgeSettings;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisSqlReviewWorkflowChainTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path repository;
    private Path stableOut;
    private Path configDir;
    private RecordingAgentBridgeClient client;
    private MyBatisSqlReviewWorkflowChain chain;

    @BeforeEach
    void setUp() throws Exception {
        repository = tempDir.resolve("repo");
        stableOut = tempDir.resolve("out");
        configDir = tempDir.resolve("config");
        Files.createDirectories(repository.resolve("src/main/resources/mappers"));
        Files.createDirectories(configDir);
        writeMapper("OrderMapper.xml", """
                <mapper namespace="com.example.OrderMapper">
                  <select id="find">SELECT id FROM orders</select>
                  <insert id="create">INSERT INTO orders(id) VALUES (#{id})
                    <selectKey keyProperty="id" resultType="long" order="AFTER">SELECT id FROM orders LIMIT 1</selectKey>
                  </insert>
                </mapper>
                """);
        writeConfig();
        client = new RecordingAgentBridgeClient(objectMapper);
        MyBatisDatabasePreflight preflight = new MyBatisDatabasePreflight(client);
        MyBatisSqlReviewTaskRunner taskRunner = new MyBatisSqlReviewTaskRunner(
                client,
                new MyBatisSqlPromptBuilder(objectMapper),
                new MyBatisToolCallAudit(objectMapper),
                new MyBatisSqlOutputValidator(objectMapper),
                new WorkflowEventSink(),
                objectMapper
        );
        AgentBridgeRunnerProperties runnerProperties = new AgentBridgeRunnerProperties();
        runnerProperties.setConfigDir(configDir.toString());
        chain = new MyBatisSqlReviewWorkflowChain(
                new ChainConfigLoader(new DefaultResourceLoader()),
                runnerProperties,
                new MyBatisSqlInventoryBuilder(),
                preflight,
                taskRunner,
                new MyBatisSqlReportRenderer(objectMapper),
                objectMapper
        );
    }

    @Test
    void runsExactlyOneSerialAttemptPerMappedStatementAndSelectKeyThenPublishesTheCompleteTree() throws Exception {
        chain.run(request("full", "", "", "run-full"));

        JsonNode inventory = objectMapper.readTree(stableOut.resolve("sql-inventory.json").toFile());
        assertThat(inventory.path("statements")).hasSize(3);
        assertThat(client.postedStatementKeys).containsExactlyElementsOf(
                inventory.path("statements").findValuesAsText("statement_key")
        );
        assertThat(client.maximumActiveTasks).isEqualTo(1);
        assertThat(client.candidateDirectoryCountsAtPost).containsExactly(3, 3, 3);
        assertThat(client.events).containsSubsequence(
                "clear", "wait-for-clear", "history", "post", "wait-for-task", "history"
        );
        assertThat(stableOut.resolve("mybatis-sql-review-report.md")).isRegularFile();
        assertThat(stableOut.resolve("sql-tasks.json")).isRegularFile();
        assertThat(stableOut.resolve("traceability.json")).isRegularFile();
        assertThat(stableOut.resolve("data-quality.md")).isRegularFile();
        assertThat(stableOut.resolve("reports")).isDirectory();
        for (JsonNode statement : inventory.path("statements")) {
            Path statementDirectory = stableOut.resolve(statement.path("report_directory").asText());
            assertThat(statementDirectory.resolve("report.md")).isRegularFile();
            assertThat(statementDirectory.resolve("summary.json")).isRegularFile();
            assertThat(statementDirectory.resolve("database-evidence.json")).isRegularFile();
        }

        Path runRoot = stableOut.resolve("runs/run-full");
        assertThat(runRoot.resolve("tasks")).isDirectory();
        try (var paths = Files.walk(runRoot.resolve("tasks"))) {
            assertThat(paths.filter(path -> path.getFileName().toString().equals("candidate")).toList())
                    .hasSize(3)
                    .allSatisfy(candidate -> assertThat(candidate).isDirectoryContaining(path ->
                            Set.of("report.md", "summary.json", "database-evidence.json")
                                    .contains(path.getFileName().toString())));
        }
        try (var paths = Files.walk(runRoot.resolve("tasks"))) {
            assertThat(paths.filter(path -> path.getFileName().toString().equals("tool-call-boundary.json")).toList())
                    .hasSize(3);
        }
        try (var paths = Files.walk(runRoot.resolve("tasks"))) {
            assertThat(paths.filter(path -> path.getFileName().toString().equals("task.json")).toList())
                    .hasSize(3)
                    .allSatisfy(taskJson -> assertThat(taskJson)
                            .content().contains("\"data_source\"", "\"catalog\"", "\"schema\"",
                                    "\"project\"", "\"scope\" : \"ALL\"")
                            .doesNotContain("connection_id", "database_name", "schema_name"));
        }
    }

    @Test
    void defaultsDatabaseScopeToAllAndBindsItToNativePreflightCalls() throws Exception {
        Path config = configDir.resolve("mybatis-sql-review.yml");
        Files.writeString(config, Files.readString(config).replace("                  scope: ALL\n", ""));

        chain.run(request("full", "", "", "run-default-scope"));

        assertThat(client.toolArguments)
                .isNotEmpty()
                .allSatisfy(arguments -> assertThat(arguments.path("scope").asText()).isEqualTo("ALL"));
    }

    @Test
    void preservesPreviousStableReportWhenCandidateValidationFails() throws Exception {
        chain.run(request("full", "", "", "run-good"));
        String previousReport = Files.readString(stableOut.resolve("mybatis-sql-review-report.md"));
        String previousManifest = Files.readString(stableOut.resolve(".publication.json"));
        client.omitEvidence = true;

        assertThatThrownBy(() -> chain.run(request("full", "", "", "run-bad")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidate must contain exactly three non-symlink regular files");

        assertThat(stableOut.resolve("mybatis-sql-review-report.md")).hasContent(previousReport);
        assertThat(stableOut.resolve(".publication.json")).hasContent(previousManifest);
        assertThat(stableOut.resolve("runs/run-bad/run-manifest.json"))
                .content().contains("\"state\" : \"FAILED\"");
        assertThat(stableOut.resolve("runs/run-bad/bundle/sql-inventory.json")).isRegularFile();
    }

    @Test
    void failsClosedOnMissingPreflightToolsWithoutReplacingStableOutput() throws Exception {
        chain.run(request("full", "", "", "run-good"));
        String previousReport = Files.readString(stableOut.resolve("mybatis-sql-review-report.md"));
        client.missingRequiredTool = true;

        assertThatThrownBy(() -> chain.run(request("full", "", "", "run-missing-tool")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database MCP tools unavailable");
        assertThat(stableOut.resolve("mybatis-sql-review-report.md")).hasContent(previousReport);
        assertThat(client.postedStatementKeys).hasSize(3);
    }

    @Test
    void perTaskRecheckDataSourceMismatchPreventsPromptSubmission() {
        client.mismatchDataSourceOnRecheck = true;

        assertThatThrownBy(() -> chain.run(request("full", "", "", "run-recheck-drift")))
                .hasMessageContaining("data-source environment must identify a physical read-replica target");
        assertThat(client.postedStatementKeys).isEmpty();
    }

    @Test
    void targetedRerunsRejectMapperAddDeleteModifyAndSourceConfigurationDrift() throws Exception {
        chain.run(request("full", "", "", "run-original"));
        Path publishedInventoryPath = stableOut.resolve("sql-inventory.json");
        String publishedInventoryText = Files.readString(publishedInventoryPath);
        JsonNode publishedInventory = objectMapper.readTree(publishedInventoryPath.toFile());
        String sqlKey = publishedInventory.at("/statements/0/statement_key").asText();
        String mapperKey = publishedInventory.at("/mappers/0/mapper_key").asText();
        int originalTaskCount = client.postedStatementKeys.size();
        Path mapper = repository.resolve("src/main/resources/mappers/OrderMapper.xml");
        String originalMapper = Files.readString(mapper);

        writeMapper("OrderMapper.xml", """
                <mapper namespace="com.example.OrderMapper">
                  <select id="newSourceStatement">SELECT id FROM orders</select>
                </mapper>
                """);
        assertFullRerunRequired(request("rerun", "sql", sqlKey, "run-modified"));

        Files.writeString(mapper, originalMapper);
        writeMapper("AddedMapper.xml", """
                <mapper namespace="com.example.AddedMapper">
                  <select id="findAdded">SELECT id FROM added_orders</select>
                </mapper>
                """);
        assertFullRerunRequired(request("rerun", "sql", sqlKey, "run-added"));

        Files.delete(repository.resolve("src/main/resources/mappers/AddedMapper.xml"));
        Files.delete(mapper);
        assertFullRerunRequired(request("rerun", "xml", mapperKey, "run-deleted"));

        Files.writeString(mapper, originalMapper);
        Path config = configDir.resolve("mybatis-sql-review.yml");
        Files.writeString(config, Files.readString(config).replace(
                "    - \"**/*Mapper.xml\"",
                "    - \"src/main/resources/mappers/*Mapper.xml\""
        ));
        assertFullRerunRequired(request("rerun", "sql", sqlKey, "run-include-drift"));

        writeConfig();
        Files.writeString(config, Files.readString(config).replace(
                "  exclude: []",
                "  exclude:\n    - \"**/NeverMatched.xml\""
        ));
        assertFullRerunRequired(request("rerun", "xml", mapperKey, "run-exclude-drift"));

        writeConfig();
        Path otherRepository = tempDir.resolve("other-repo");
        Path otherMappers = Files.createDirectories(otherRepository.resolve("src/main/resources/mappers"));
        Files.copy(mapper, otherMappers.resolve("OrderMapper.xml"));
        Files.writeString(config, Files.readString(config).replace(
                repository.toString(), otherRepository.toString()
        ));
        assertFullRerunRequired(request("rerun", "xml", mapperKey, "run-root-drift"));

        assertThat(client.postedStatementKeys).hasSize(originalTaskCount);
        assertThat(stableOut.resolve("sql-inventory.json")).hasContent(publishedInventoryText);
    }

    @Test
    void indexRerunSkipsDatabasePreflightWhileSqlAndXmlStillRequireDatabaseTools() throws Exception {
        chain.run(request("full", "", "", "run-original"));
        JsonNode publishedInventory = objectMapper.readTree(stableOut.resolve("sql-inventory.json").toFile());
        String sqlKey = publishedInventory.at("/statements/0/statement_key").asText();
        String mapperKey = publishedInventory.at("/mappers/0/mapper_key").asText();
        int beforeIndexTasks = client.postedStatementKeys.size();
        int beforeIndexToolChecks = client.listToolsUris.size();
        client.missingRequiredTool = true;

        chain.run(request("rerun", "index", "", "run-index"));

        assertThat(client.postedStatementKeys).hasSize(beforeIndexTasks);
        assertThat(client.listToolsUris).hasSize(beforeIndexToolChecks);
        assertThat(stableOut.resolve("mybatis-sql-review-report.md")).isRegularFile();

        assertThatThrownBy(() -> chain.run(request("rerun", "sql", sqlKey, "run-sql-no-db")))
                .hasMessageContaining("Database MCP tools unavailable");
        assertThatThrownBy(() -> chain.run(request("rerun", "xml", mapperKey, "run-xml-no-db")))
                .hasMessageContaining("Database MCP tools unavailable");
    }

    @Test
    void indexRerunRejectsDamagedPublishedMapperArtifactBeforeAggregation() throws Exception {
        chain.run(request("full", "", "", "run-original"));
        JsonNode publishedInventory = objectMapper.readTree(stableOut.resolve("sql-inventory.json").toFile());
        String mapperKey = publishedInventory.at("/mappers/0/mapper_key").asText();
        Path mapperIndex = stableOut.resolve("reports").resolve(mapperKey).resolve("index.md");
        assertThat(mapperIndex.toFile().setWritable(true, true)).isTrue();
        Files.writeString(mapperIndex, "[escape](../../../../outside.md)\n");
        int beforeToolChecks = client.listToolsUris.size();
        client.missingRequiredTool = true;

        assertThatThrownBy(() -> chain.run(request("rerun", "index", "", "run-index-damaged")))
                .hasMessageContaining("digest mismatch");
        assertThat(client.listToolsUris).hasSize(beforeToolChecks);
    }

    @Test
    void indexRerunRejectsDamagedPublishedDetailEvidenceWithoutDatabaseTools() throws Exception {
        chain.run(request("full", "", "", "run-original"));
        JsonNode publishedInventory = objectMapper.readTree(stableOut.resolve("sql-inventory.json").toFile());
        String reportDirectory = publishedInventory.at("/statements/0/report_directory").asText();
        Path evidence = stableOut.resolve(reportDirectory).resolve("database-evidence.json");
        assertThat(evidence.toFile().setWritable(true, true)).isTrue();
        Files.writeString(evidence, "{}");
        int beforeToolChecks = client.listToolsUris.size();
        client.missingRequiredTool = true;

        assertThatThrownBy(() -> chain.run(request("rerun", "index", "", "run-index-evidence-damaged")))
                .hasMessageContaining("digest mismatch");
        assertThat(client.listToolsUris).hasSize(beforeToolChecks);
    }

    @Test
    void fullRunRechecksInventoryAfterPreflightBeforeStartingAnyAgent() throws Exception {
        client.onListTools = () -> {
            try {
                writeMapper("OrderMapper.xml", """
                        <mapper namespace="com.example.OrderMapper">
                          <select id="changedDuringPreflight">SELECT id FROM changed_orders</select>
                        </mapper>
                        """);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };

        assertThatThrownBy(() -> chain.run(request("full", "", "", "run-toctou")))
                .hasMessageContaining("inventory changed")
                .hasMessageContaining("full rerun");
        assertThat(client.postedStatementKeys).isEmpty();
    }

    @Test
    void rejectsUnknownTargetedRerunIdsAndKeepsPublishedSnapshot() throws Exception {
        chain.run(request("full", "", "", "run-original"));
        String previous = Files.readString(stableOut.resolve("sql-inventory.json"));

        assertThatThrownBy(() -> chain.run(request("rerun", "sql", "missing-statement", "run-unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown SQL statement key");
        assertThat(stableOut.resolve("sql-inventory.json")).hasContent(previous);
    }

    @Test
    void zeroSqlProjectPublishesAnEmptyAggregateWithoutStartingAgentTasks() throws Exception {
        writeMapper("OrderMapper.xml", """
                <mapper namespace="com.example.OrderMapper">
                  <sql id="columns">id, status</sql>
                </mapper>
                """);

        chain.run(request("full", "", "", "run-empty"));

        assertThat(client.postedStatementKeys).isEmpty();
        assertThat(stableOut.resolve("mybatis-sql-review-report.md"))
                .content().contains("Statements: `0`", "Findings: `0`");
        assertThat(objectMapper.readTree(stableOut.resolve("sql-inventory.json").toFile()).path("statements"))
                .isEmpty();
    }

    @Test
    void rejectsNonSerialAgentBridgeConfigurationBeforeAnyTaskIsPosted() {
        AgentBridgeSettings settings = settings();
        settings.setConcurrency(2);

        assertThatThrownBy(() -> chain.run(request("full", "", "", "run-parallel", settings)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("concurrency must be 1");
        assertThat(client.postedStatementKeys).isEmpty();
    }

    @Test
    void executionYamlAgentBridgeFieldsOverrideGlobalSettingsWhileMissingFieldsFallBack() throws Exception {
        Path config = configDir.resolve("mybatis-sql-review.yml");
        Files.writeString(config, Files.readString(config) + """

                agentbridge:
                  web-base-url: http://yaml-agentbridge.test
                  timeout-minutes: 2
                  task-message: YAML TASK MESSAGE
                """);
        AgentBridgeSettings global = settings();
        global.setWebBaseUrl("http://global-agentbridge.test");
        global.setMcpUrl("http://global-agentbridge.test/mcp");
        global.setTimeoutMinutes(9);
        global.setPollMillis(170);
        global.setTaskMessage("GLOBAL TASK MESSAGE");

        chain.run(request("full", "", "", "run-yaml-agentbridge", global));

        assertThat(client.listToolsUris).containsOnly(URI.create("http://global-agentbridge.test/mcp"));
        assertThat(client.postedPromptUris).containsOnly(URI.create("http://yaml-agentbridge.test"));
        assertThat(client.waitTimeouts).containsOnly(Duration.ofMinutes(2));
        assertThat(client.waitPollIntervals).containsOnly(Duration.ofMillis(170));
        assertThat(client.postedPrompts)
                .allSatisfy(prompt -> assertThat(prompt)
                        .contains("YAML TASK MESSAGE")
                        .doesNotContain("GLOBAL TASK MESSAGE"));
    }

    @Test
    void blankYamlUrlsFallBackToGlobalWhileTaskMessageCanRemainExplicitlyBlank() throws Exception {
        Path config = configDir.resolve("mybatis-sql-review.yml");
        Files.writeString(config, Files.readString(config) + """

                agentbridge:
                  web-base-url: "   "
                  mcp-url: ""
                  task-message: "   "
                """);
        AgentBridgeSettings global = settings();
        global.setWebBaseUrl("http://global-agentbridge.test");
        global.setMcpUrl("http://global-agentbridge.test/mcp");
        global.setTaskMessage("GLOBAL TASK MESSAGE");

        assertThatCode(() -> chain.run(request(
                "full", "", "", "run-blank-agentbridge-urls", global
        ))).doesNotThrowAnyException();

        assertThat(client.listToolsUris).containsOnly(URI.create("http://global-agentbridge.test/mcp"));
        assertThat(client.postedPromptUris).containsOnly(URI.create("http://global-agentbridge.test"));
        assertThat(client.postedPrompts)
                .allSatisfy(prompt -> assertThat(prompt).doesNotContain("GLOBAL TASK MESSAGE"));
    }

    @Test
    void recordsFailedRunManifestWhenConfigurationValidationFailsBeforeWorkspaceStart() throws Exception {
        Path config = configDir.resolve("mybatis-sql-review.yml");
        Files.writeString(config, Files.readString(config)
                .replace("statement-timeout-seconds: 30", "statement-timeout-seconds: 31"));

        assertThatThrownBy(() -> chain.run(request("full", "", "", "run-invalid-config")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statement-timeout-seconds");

        assertThat(stableOut.resolve("runs/run-invalid-config/run-manifest.json"))
                .content().contains("\"state\" : \"FAILED\"")
                .contains("statement-timeout-seconds");
        assertThat(client.postedStatementKeys).isEmpty();
    }

    @Test
    void markdownValidationIgnoresFencedSqlButRejectsExternalLinksAutolinksAndRawHtml() throws Exception {
        Path root = tempDir.resolve("markdown");
        Files.createDirectories(root);
        Files.writeString(root.resolve("local.md"), "# Local\n");
        Files.writeString(root.resolve("report.md"), """
                # Safe

                [local](local.md)
                [reference][local-reference]
                [multiline-reference][local-multiline-reference]

                [local-reference]: local.md
                [local-multiline-reference]:
                  local.md

                ```sql
                SELECT '[not-a-link](https://sql.example)' AS value;
                SELECT '<https://sql.example>' AS value;
                SELECT '<table>' AS value;
                [external-reference]: https://sql.example
                [external-multiline-reference]:
                  https://sql.example
                ```
                """);
        assertThatCode(() -> MyBatisSqlReviewWorkflowChain.validateMarkdownLinks(root))
                .doesNotThrowAnyException();

        Files.writeString(root.resolve("report.md"), "[external](https://evil.example)\n");
        assertThatThrownBy(() -> MyBatisSqlReviewWorkflowChain.validateMarkdownLinks(root))
                .hasMessageContaining("external");

        Files.writeString(root.resolve("report.md"), "[external][remote]\n\n[remote]: https://evil.example\n");
        assertThatThrownBy(() -> MyBatisSqlReviewWorkflowChain.validateMarkdownLinks(root))
                .hasMessageContaining("external reference definition");

        Files.writeString(root.resolve("report.md"), "[remote]:\n  https://evil.example\n");
        assertThatThrownBy(() -> MyBatisSqlReviewWorkflowChain.validateMarkdownLinks(root))
                .hasMessageContaining("external reference definition");

        Files.writeString(root.resolve("report.md"), "<https://evil.example>\n");
        assertThatThrownBy(() -> MyBatisSqlReviewWorkflowChain.validateMarkdownLinks(root))
                .hasMessageContaining("autolink");

        Files.writeString(root.resolve("report.md"), "<img src=x>\n");
        assertThatThrownBy(() -> MyBatisSqlReviewWorkflowChain.validateMarkdownLinks(root))
                .hasMessageContaining("raw HTML");
    }

    private WorkflowRunRequest request(String mode, String rerunType, String rerunId, String executionId) {
        return request(mode, rerunType, rerunId, executionId, settings());
    }

    private WorkflowRunRequest request(
            String mode,
            String rerunType,
            String rerunId,
            String executionId,
            AgentBridgeSettings settings
    ) {
        return new WorkflowRunRequest(
                mode,
                rerunType,
                rerunId,
                LocalDate.of(2026, 7, 22),
                settings,
                configDir.toString(),
                executionId,
                null
        );
    }

    private AgentBridgeSettings settings() {
        AgentBridgeSettings settings = new AgentBridgeSettings();
        settings.setWebBaseUrl("http://agentbridge.test");
        settings.setMcpUrl("http://agentbridge.test/mcp");
        settings.setConcurrency(1);
        settings.setMaxConcurrency(1);
        settings.setPollMillis(1);
        settings.setTimeoutMinutes(1);
        settings.setValidationSettleSeconds(0);
        settings.setValidationMaxCorrections(0);
        return settings;
    }

    private void writeMapper(String name, String content) throws Exception {
        Files.writeString(repository.resolve("src/main/resources/mappers").resolve(name), content);
    }

    private void writeConfig() throws Exception {
        Files.writeString(configDir.resolve("mybatis-sql-review.yml"), """
                project:
                  id: demo
                  name: Demo Project
                  repo: %s
                paths:
                  out: %s
                source:
                  include:
                    - "**/*Mapper.xml"
                  exclude: []
                database:
                  connection-name: Gauss Review
                  database-name: orders
                  schema-name: audit
                  scope: ALL
                  environment: read-replica
                  non-owner-non-admin-read-only-account: true
                  row-level-security-disabled-for-safe-base-tables: true
                  user-defined-and-security-definer-function-execution-revoked-including-public: true
                  statement-timeout-seconds: 30
                  statement-timeout-scope: role
                  max-rows: 20
                  max-scenarios-per-sql: 3
                  max-evidence-bytes: 262144
                  retain-raw-rows: true
                  allow-agent-select: true
                """.formatted(repository, stableOut));
    }

    private void assertFullRerunRequired(WorkflowRunRequest rerun) {
        assertThatThrownBy(() -> chain.run(rerun))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("full rerun");
    }

    private static final class RecordingAgentBridgeClient extends AgentBridgeClient {
        private final ObjectMapper objectMapper;
        private final List<String> events = new ArrayList<>();
        private final List<String> postedStatementKeys = new ArrayList<>();
        private final List<Integer> candidateDirectoryCountsAtPost = new ArrayList<>();
        private final List<ToolCallRecord> history = new ArrayList<>();
        private final List<URI> listToolsUris = new ArrayList<>();
        private final List<URI> postedPromptUris = new ArrayList<>();
        private final List<Duration> waitTimeouts = new ArrayList<>();
        private final List<Duration> waitPollIntervals = new ArrayList<>();
        private final List<String> postedPrompts = new ArrayList<>();
        private final List<JsonNode> toolArguments = new ArrayList<>();
        private int activeTasks;
        private int maximumActiveTasks;
        private boolean clearPending;
        private boolean omitEvidence;
        private boolean missingRequiredTool;
        private boolean mismatchDataSourceOnRecheck;
        private int dataSourceCalls;
        private Runnable onListTools;

        private RecordingAgentBridgeClient(ObjectMapper objectMapper) {
            super(objectMapper);
            this.objectMapper = objectMapper;
        }

        @Override
        public void requireDatabaseMcpSupport(URI ignored) {
            // The fixture exposes the strict capability/tool contract directly.
        }

        @Override
        public void clearSession(URI ignored) {
            events.add("clear");
            clearPending = true;
        }

        @Override
        public void postPrompt(URI uri, String prompt) {
            events.add("post");
            postedPromptUris.add(uri);
            postedPrompts.add(prompt);
            activeTasks++;
            maximumActiveTasks = Math.max(maximumActiveTasks, activeTasks);
            try {
                JsonNode runtime = runtimeContext(prompt);
                postedStatementKeys.add(runtime.path("statement_key").asText());
                Path candidate = Path.of(runtime.path("candidate_directory").asText());
                Path tasksRoot = candidate.getParent().getParent().getParent().getParent();
                try (var paths = Files.walk(tasksRoot)) {
                    candidateDirectoryCountsAtPost.add((int) paths
                            .filter(path -> path.getFileName().toString().equals("candidate"))
                            .count());
                }
                writeCandidate(runtime);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public void waitUntilIdle(URI ignored, Duration timeout, Duration pollInterval) {
            waitTimeouts.add(timeout);
            waitPollIntervals.add(pollInterval);
            if (clearPending) {
                events.add("wait-for-clear");
                clearPending = false;
            } else {
                events.add("wait-for-task");
                activeTasks--;
            }
        }

        @Override
        public List<ToolCallRecord> getToolCalls(URI ignored) {
            events.add("history");
            return List.copyOf(history);
        }

        @Override
        public List<ToolDefinition> listTools(URI uri) {
            listToolsUris.add(uri);
            Runnable action = onListTools;
            onListTools = null;
            if (action != null) {
                action.run();
            }
            LinkedHashSet<String> names = new LinkedHashSet<>(MyBatisDatabasePreflight.REQUIRED_DATABASE_TOOLS);
            if (missingRequiredTool) {
                names.remove(DatabaseMcpContract.EXECUTE_QUERY);
            }
            return names.stream()
                    .map(name -> new ToolDefinition(name, name, toolSchema(name)))
                    .toList();
        }

        @Override
        public ToolResponse callTool(URI ignored, String name, JsonNode arguments) {
            toolArguments.add(arguments.deepCopy());
            JsonNode structured = switch (name) {
                case DatabaseMcpContract.LIST_DATASOURCES -> dataSources();
                case DatabaseMcpContract.LIST_DATABASES -> databases();
                case DatabaseMcpContract.LIST_TABLE_SCHEMA -> tableSchema();
                case DatabaseMcpContract.EXECUTE_QUERY -> safetyProbe();
                default -> throw new IllegalArgumentException("unexpected native tool: " + name);
            };
            return new ToolResponse(structured, "", structured);
        }

        private ArrayNode dataSources() {
            dataSourceCalls++;
            ArrayNode response = objectMapper.createArrayNode();
            ObjectNode source = response.addObject()
                    .put("name", "Gauss Review")
                    .put("databaseSystem", "GaussDB")
                    .put("environment", mismatchDataSourceOnRecheck && dataSourceCalls > 1
                            ? "primary" : "read-replica");
            return response;
        }

        private ArrayNode databases() {
            ArrayNode response = objectMapper.createArrayNode();
            response.addObject().put("name", "orders");
            return response;
        }

        private ObjectNode tableSchema() {
            ObjectNode response = objectMapper.createObjectNode()
                    .put("catalog", "orders").put("schema", "audit");
            response.putArray("tables").addObject().put("name", "orders").put("type", "TABLE");
            return response;
        }

        private JsonNode toolSchema(String name) {
            ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");
            List<String> fields = switch (name) {
                case DatabaseMcpContract.LIST_DATASOURCES -> List.of("project", "scope");
                case DatabaseMcpContract.LIST_DATABASES -> List.of("project", "scope", "dataSource");
                case DatabaseMcpContract.LIST_TABLE_SCHEMA -> List.of(
                        "project", "scope", "dataSource", "catalog", "schema", "includeColumns", "includeIndexes", "maxTables");
                case DatabaseMcpContract.EXECUTE_QUERY -> List.of("project", "scope", "dataSource", "sql", "maxRows");
                default -> throw new IllegalArgumentException("unexpected native tool: " + name);
            };
            for (String field : fields) {
                properties.putObject(field).put("type", switch (field) {
                    case "includeColumns", "includeIndexes" -> "boolean";
                    case "maxRows", "maxTables" -> "integer";
                    default -> "string";
                });
                required.add(field);
            }
            ((ObjectNode) properties.get("scope")).putArray("enum")
                    .add("GLOBAL").add("PROJECT").add("ALL");
            return schema;
        }

        private ObjectNode safetyProbe() {
            ObjectNode row = objectMapper.createObjectNode()
                    .put("probeContractVersion", "mybatis-sql-review-db-safety-v2")
                    .put("currentDatabase", "orders")
                    .put("currentSchema", "audit")
                    .put("currentUser", "sql_auditor")
                    .put("superuser", false)
                    .put("rolbypassrls", false)
                    .put("systemAdmin", false)
                    .put("auditAdmin", false)
                    .put("roleAdmin", false)
                    .put("roleMembershipCount", 0)
                    .put("databaseOwner", false)
                    .put("schemaOwner", false)
                    .put("ownedNonSystemSchemaCount", 0)
                    .put("ownedBaseTableCount", 0)
                    .put("databaseCreate", false)
                    .put("databaseTemporary", false)
                    .put("schemaCreate", false)
                    .put("unsafeNonSystemSchemaCreateCount", 0)
                    .put("dangerousAnyPrivilege", false)
                    .put("sessionReadOnly", true)
                    .put("transactionReadOnly", true)
                    .put("unsafeTablePrivilegeCount", 0)
                    .put("unsafeColumnPrivilegeCount", 0)
                    .put("unsafeSequencePrivilegeCount", 0)
                    .put("rlsEnabledBaseTableCount", 0)
                    .put("forceRlsEnabledBaseTableCount", 0)
                    .put("executableFunctionCount", 0)
                    .put("executablePackageCount", 0)
                    .put("unsafeForeignServerPrivilegeCount", 0)
                    .put("unsafeDirectoryPrivilegeCount", 0)
                    .put("statementTimeoutMs", 12_000)
                    .put("baseTableNames", "orders")
                    .put("baseTableCount", 1)
                    .put("readReplica", true);
            ObjectNode result = objectMapper.createObjectNode();
            row.fieldNames().forEachRemaining(result.putArray("columns")::add);
            result.putArray("rows").add(row);
            return result;
        }


        private JsonNode runtimeContext(String prompt) throws Exception {
            String marker = "## Runtime task context\n\n```json\n";
            int start = prompt.lastIndexOf(marker);
            int end = prompt.indexOf("\n```", start + marker.length());
            return objectMapper.readTree(prompt.substring(start + marker.length(), end));
        }

        private void writeCandidate(JsonNode runtime) throws Exception {
            Path candidate = Path.of(runtime.path("candidate_directory").asText());
            Files.createDirectories(candidate);
            String statementKey = runtime.path("statement_key").asText();
            String mapperPath = runtime.path("mapper_relative_path").asText();
            String namespace = runtime.path("namespace").asText();
            String statementId = runtime.path("statement_id").asText();
            String commandType = runtime.path("command_type").asText();
            boolean selectKey = runtime.path("select_key").asBoolean();
            Files.writeString(candidate.resolve("report.md"), """
                    # SQL Review

                    Statement `%s` from `%s`, namespace `%s`, id `%s`, command `%s`, selectKey `%s`.

                    - Data source: `%s`
                    - Catalog: `%s`
                    - Schema: `%s`
                    - Project: `%s`
                    - Scope: `%s`

                    ## Statement

                    Reviewed statically.

                    ## Static Analysis

                    No technical validation failure.

                    ## Database Evidence

                    [database-evidence.json](database-evidence.json)

                    ## Findings

                    No findings.

                    ## Recommendations

                    Retain normal regression coverage.

                    ## Limitations

                    No database calls were required.
                    """.formatted(
                    statementKey, mapperPath, namespace, statementId, commandType, selectKey,
                    runtime.path("data_source").asText(), runtime.path("catalog").asText(),
                    runtime.path("schema").asText(), runtime.path("project").asText(),
                    runtime.path("scope").asText()
            ));
            ObjectNode summary = objectMapper.createObjectNode()
                    .put("schema_version", "mybatis-sql-review-summary/v1")
                    .put("statement_key", statementKey)
                    .put("mapper_relative_path", mapperPath)
                    .put("namespace", namespace)
                    .put("statement_id", statementId)
                    .put("status", "no-findings")
                    .put("command_type", commandType)
                    .put("select_key", selectKey)
                    .put("risk_level", "none")
                    .put("scenario_count", 0)
                    .put("data_source", runtime.path("data_source").asText())
                    .put("catalog", runtime.path("catalog").asText())
                    .put("schema", runtime.path("schema").asText())
                    .put("project", runtime.path("project").asText())
                    .put("scope", runtime.path("scope").asText())
                    .put("evidence_file", "database-evidence.json")
                    .put("report_file", "report.md");
            summary.putArray("findings");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(candidate.resolve("summary.json").toFile(), summary);
            if (!omitEvidence) {
                ObjectNode evidence = objectMapper.createObjectNode()
                        .put("schema_version", "mybatis-sql-review-database-evidence/v1")
                        .put("statement_key", statementKey)
                        .put("mapper_relative_path", mapperPath)
                        .put("namespace", namespace)
                        .put("statement_id", statementId)
                        .put("command_type", commandType)
                        .put("select_key", selectKey)
                        .put("data_source", runtime.path("data_source").asText())
                        .put("catalog", runtime.path("catalog").asText())
                        .put("schema", runtime.path("schema").asText())
                        .put("project", runtime.path("project").asText())
                        .put("scope", runtime.path("scope").asText());
                evidence.putObject("audit")
                        .put("post_hoc", true)
                        .put("permission_to_execute_original_dml", false)
                        .putArray("tool_call_ids");
                evidence.putArray("metadata");
                evidence.putArray("scenarios");
                evidence.putArray("limitations").add("No database calls were required for this static review.");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                        candidate.resolve("database-evidence.json").toFile(), evidence);
            }
        }
    }
}
