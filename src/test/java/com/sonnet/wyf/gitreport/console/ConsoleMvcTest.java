package com.sonnet.wyf.gitreport.console;

import com.sonnet.wyf.gitreport.workflow.mybatissqlreview.MyBatisDatabasePreflight;
import com.sonnet.wyf.gitreport.workflow.mybatissqlreview.MyBatisSqlInventoryBuilder;
import com.sonnet.wyf.gitreport.workflow.mybatissqlreview.MyBatisSqlOutputValidator;
import com.sonnet.wyf.gitreport.workflow.mybatissqlreview.MyBatisSqlPromptBuilder;
import com.sonnet.wyf.gitreport.workflow.mybatissqlreview.MyBatisSqlReportRenderer;
import com.sonnet.wyf.gitreport.workflow.mybatissqlreview.MyBatisSqlReviewTaskRunner;
import com.sonnet.wyf.gitreport.workflow.mybatissqlreview.MyBatisSqlReviewWorkflowChain;
import com.sonnet.wyf.gitreport.workflow.mybatissqlreview.MyBatisToolCallAudit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "task-console.database-path=target/test-console/console-mvc.sqlite",
        "task-console.run-config-dir=target/test-console/run-configs"
})
class ConsoleMvcTest {
    MockMvc mockMvc;

    @Autowired
    WorkflowRunRepository repository;

    @Autowired
    WorkflowScheduleRepository scheduleRepository;

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void pagesRenderOnlyTheVueShellAndLegacyAssetsAreGone() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "vue-shell-config.yml");

        Map<String, String> pages = Map.of(
                "/", "AgentBridge 任务控制台",
                "/runs/new", "新建运行",
                "/history", "运行历史",
                "/runs/" + runId, "运行详情",
                "/schedules", "定时任务"
        );
        for (Map.Entry<String, String> page : pages.entrySet()) {
            String html = mockMvc.perform(get(page.getKey()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(html)
                    .contains("<title>" + page.getValue() + "</title>")
                    .contains("<div id=\"app\"></div>")
                    .contains("<link rel=\"stylesheet\" href=\"/assets/console-app.css\">")
                    .contains("<script type=\"module\" src=\"/assets/console-app.js\"></script>")
                    .doesNotContain("legacy-fallback", "app-shell", "/js/", "/styles.css");
        }

        for (String legacyAsset : new String[]{
                "/styles.css",
                "/js/console-common.js",
                "/js/history.js",
                "/js/run-detail.js",
                "/js/run-form.js",
                "/js/schedules.js"
        }) {
            mockMvc.perform(get(legacyAsset)).andExpect(status().isNotFound());
        }
    }

    @Test
    void myBatisSqlReviewIsWiredIntoTheCatalogAndRerunContract() throws Exception {
        assertThat(webApplicationContext.getBeansOfType(MyBatisSqlInventoryBuilder.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisDatabasePreflight.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisSqlPromptBuilder.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisToolCallAudit.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisSqlOutputValidator.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisSqlReviewTaskRunner.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisSqlReportRenderer.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisSqlReviewWorkflowChain.class)).hasSize(1);

        mockMvc.perform(get("/api/chains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chains").isArray())
                .andExpect(content().string(containsString("mybatis-sql-review")));
        mockMvc.perform(get("/api/chains/mybatis-sql-review/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaults['project.id']").exists())
                .andExpect(jsonPath("$.defaults['source.paths']").isArray())
                .andExpect(jsonPath("$.defaults['database.connection-name']").exists());

        assertThat(WorkflowRerunContract.requiresRerunId("mybatis-sql-review", "sql")).isTrue();
        assertThat(WorkflowRerunContract.requiresRerunId("mybatis-sql-review", "xml")).isTrue();
        assertThat(WorkflowRerunContract.requiresRerunId("mybatis-sql-review", "index")).isFalse();
        assertThat(WorkflowRerunContract.isKnownType("mybatis-sql-review", "sql")).isTrue();
        assertThat(WorkflowRerunContract.isKnownType("mybatis-sql-review", "xml")).isTrue();
        assertThat(WorkflowRerunContract.isKnownType("mybatis-sql-review", "index")).isTrue();
    }






    @Test
    void runSnapshotReturnsConsistentSummaryTasksAndIncrementalEvents() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "snapshot-config.yml");
        repository.markRunning(runId);
        repository.appendEvent(runId, "QUEUED", "first event");
        WorkflowRunEvent firstEvent = repository.listEvents(runId).getFirst();
        repository.appendEvent(runId, "TASK_RUNNING", "second event");
        repository.upsertTaskStatus(new WorkflowTaskStatus(
                runId, "task-a", "Task A", "RUNNING", "execution", null, null, Instant.now()
        ));

        mockMvc.perform(get("/api/runs/" + runId + "/snapshot")
                        .param("afterEventId", String.valueOf(firstEvent.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.id").value(runId))
                .andExpect(jsonPath("$.run.state").value("RUNNING"))
                .andExpect(jsonPath("$.summary.totalTasks").value(1))
                .andExpect(jsonPath("$.tasks[0].taskKey").value("task-a"))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].eventType").value("TASK_RUNNING"));
    }

    @Test
    void runSnapshotNormalizesNegativeCursorAndReturnsStableNotFound() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "negative-snapshot-config.yml");
        repository.appendEvent(runId, "QUEUED", "visible from zero");

        mockMvc.perform(get("/api/runs/" + runId + "/snapshot").param("afterEventId", "-99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].message").value("visible from zero"));
        mockMvc.perform(get("/api/runs/9223372036854775807/snapshot"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("未找到"));
    }

    @Test
    void runSnapshotProvidesTheSafeFailedTaskAction() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "snapshot-rerun-action.yml");
        repository.markRunning(runId);
        repository.markFailed(runId, "作者任务失败");
        repository.upsertTaskStatus(new WorkflowTaskStatus(
                runId, "author-a", "Author A", "FAILED", "author", null, "failed", Instant.now()
        ));
        repository.appendEvent(runId, "TASK_FAILED", "author-a failed");

        mockMvc.perform(get("/api/runs/" + runId + "/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rerunAction.visible").value(true))
                .andExpect(jsonPath("$.rerunAction.available").value(true))
                .andExpect(jsonPath("$.rerunAction.rerunType").value("author"))
                .andExpect(jsonPath("$.rerunAction.rerunId").value("author-a"))
                .andExpect(jsonPath("$.rerunAction.reason").isEmpty());
    }

    @Test
    void runSnapshotRejectsAmbiguousFailedTaskRerunsWithoutInventingAType() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "smartesb-rewrite-code-review", "full", null, null, null, Map.of(), null
        ), "snapshot-ambiguous-rerun.yml");
        repository.markRunning(runId);
        repository.markFailed(runId, "无法定位任务种类");
        repository.upsertTaskStatus(new WorkflowTaskStatus(
                runId, "unknown-task", "Unknown task", "FAILED", "execution", null, "failed", Instant.now()
        ));

        mockMvc.perform(get("/api/runs/" + runId + "/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rerunAction.visible").value(true))
                .andExpect(jsonPath("$.rerunAction.available").value(false))
                .andExpect(jsonPath("$.rerunAction.rerunType").isEmpty())
                .andExpect(jsonPath("$.rerunAction.rerunId").value("unknown-task"))
                .andExpect(jsonPath("$.rerunAction.reason").value("无法安全确定重跑类型"));
    }

    @Test
    void myBatisFinalValidationFailureMapsSpecificallyToSqlRerun() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "mybatis-sql-review", "full", null, null, null, Map.of(), null
        ), "mybatis-failed-sql.yml");
        repository.markRunning(runId);
        repository.markFailed(runId, "SQL candidate validation failed");
        repository.upsertTaskStatus(new WorkflowTaskStatus(
                runId,
                "mapper-order-find",
                "OrderMapper.find",
                "FAILED",
                "validation_failed_final",
                null,
                "failed",
                Instant.now()
        ));

        assertThat(WorkflowRerunContract.failedTaskRerunType(
                "mybatis-sql-review", "validation_failed_final"
        )).contains("sql");
        assertThat(WorkflowRerunContract.failedTaskRerunType(
                "smartesb-rewrite-code-review", "validation_failed_final"
        )).isEmpty();
        mockMvc.perform(get("/api/runs/" + runId + "/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rerunAction.visible").value(true))
                .andExpect(jsonPath("$.rerunAction.available").value(true))
                .andExpect(jsonPath("$.rerunAction.rerunType").value("sql"))
                .andExpect(jsonPath("$.rerunAction.rerunId").value("mapper-order-find"))
                .andExpect(jsonPath("$.rerunAction.reason").isEmpty());
    }





    @Test
    void chainDefaultsComeFromParsedYaml() throws Exception {
        mockMvc.perform(get("/api/chains/git-code-contribution-report/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaults['project.id']").value("upfs-production"))
                .andExpect(jsonPath("$.defaults['paths.repo']").value("/home/wangyufeng/workspace/upfs-production"))
                .andExpect(jsonPath("$.defaults['git.include-merges']").value(false))
                .andExpect(jsonPath("$.defaults['git.exclude'][0]").value("target/**"))
                .andExpect(jsonPath("$.defaults['detail-input.top-files']").value(10));

        mockMvc.perform(get("/api/chains/smartesb-rewrite-code-review/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaults['transaction-plan-dir']").value("src/main/resources/smartesb-transactions"))
                .andExpect(jsonPath("$.defaults['local-out']").doesNotExist());

        mockMvc.perform(get("/api/chains/project-unit-test-generation/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaults['project.id']").value("example-project"))
                .andExpect(jsonPath("$.defaults['project.repo']").value("CHANGE_ME_PROJECT_REPO"))
                .andExpect(jsonPath("$.defaults['paths.out']").value("project-unit-tests/example-project"))
                .andExpect(jsonPath("$.defaults['source.package-paths']").isArray())
                .andExpect(jsonPath("$.defaults['test.concurrency']").doesNotExist())
                .andExpect(jsonPath("$.defaults['test.max-types-per-task']").doesNotExist())
                .andExpect(jsonPath("$.defaults['test.require-coverage']").value(false))
                .andExpect(jsonPath("$.defaults['test.coverage-threshold-percent']").value(90))
                .andExpect(jsonPath("$.defaults['test.jacoco-version']").value("0.8.15"))
                .andExpect(jsonPath("$.defaults['test.jacoco-jvm-arg-property']").value("sqlite.native.access.argument"))
                .andExpect(jsonPath("$.defaults['test.jacoco-jvm-arg-base']").value("--enable-native-access=ALL-UNNAMED"))
                .andExpect(jsonPath("$.defaults['test." + String.join("-", "verify", "command") + "']").doesNotExist())
                .andExpect(jsonPath("$.defaults['agentbridge.web-base-url']").value("https://127.0.0.1:9642"))
                .andExpect(jsonPath("$.defaults['agentbridge.mcp-url']").value("http://127.0.0.1:8642/mcp"))
                .andExpect(jsonPath("$.defaults['agentbridge.max-attempts']").value(5));

        mockMvc.perform(get("/api/chains/mybatis-sql-review/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaults['project.id']").value("example-project"))
                .andExpect(jsonPath("$.defaults['project.name']").value("Example Project"))
                .andExpect(jsonPath("$.defaults['project.repo']").value("CHANGE_ME_PROJECT_REPO"))
                .andExpect(jsonPath("$.defaults['paths.out']").value("mybatis-sql-review/example-project"))
                .andExpect(jsonPath("$.defaults['source.paths'][0]").value("src/main/resources/mapper"))
                .andExpect(jsonPath("$.defaults['source.include'][0]").value("**/*.xml"))
                .andExpect(jsonPath("$.defaults['source.exclude'][0]").value("target/**"))
                .andExpect(jsonPath("$.defaults['database.connection-name']")
                        .value("CHANGE_ME_AGENTBRIDGE_CONNECTION_NAME"))
                .andExpect(jsonPath("$.defaults['database.database-name']").value("CHANGE_ME_DATABASE"))
                .andExpect(jsonPath("$.defaults['database.schema-name']").value("CHANGE_ME_SCHEMA"))
                .andExpect(jsonPath("$.defaults['database.scope']").value("ALL"))
                .andExpect(jsonPath("$.defaults['database.safety-mode']").value("connectivity-only"))
                .andExpect(jsonPath("$.defaults['database.environment']").value("test"))
                .andExpect(jsonPath("$.defaults['database.non-owner-non-admin-read-only-account']").value(false))
                .andExpect(jsonPath("$.defaults['database.row-level-security-disabled-for-safe-base-tables']")
                        .value(false))
                .andExpect(jsonPath("$.defaults['database." + String.join("-",
                        "user", "defined", "and", "security", "definer", "function", "execution",
                        "revoked", "including", "public") + "']").value(false))
                .andExpect(jsonPath("$.defaults['database.statement-timeout-seconds']").value(30))
                .andExpect(jsonPath("$.defaults['database.statement-timeout-scope']").value("role"))
                .andExpect(jsonPath("$.defaults['database.max-rows']").value(20))
                .andExpect(jsonPath("$.defaults['database.max-scenarios-per-sql']").value(3))
                .andExpect(jsonPath("$.defaults['database.max-evidence-bytes']").value(262144))
                .andExpect(jsonPath("$.defaults['database.retain-raw-rows']").value(true))
                .andExpect(jsonPath("$.defaults['database.allow-agent-select']").value(true))
                .andExpect(jsonPath("$.defaults['agentbridge.web-base-url']").value("https://127.0.0.1:9642"))
                .andExpect(jsonPath("$.defaults['agentbridge.mcp-url']").value("http://127.0.0.1:8642/mcp"))
                .andExpect(jsonPath("$.defaults['agentbridge.concurrency']").value(1))
                .andExpect(jsonPath("$.defaults['agentbridge.max-concurrency']").value(1))
                .andExpect(jsonPath("$.defaults['agentbridge.timeout-minutes']").value(40))
                .andExpect(jsonPath("$.defaults['agentbridge.poll-millis']").value(1000))
                .andExpect(jsonPath("$.defaults['agentbridge.validation-settle-seconds']").value(30))
                .andExpect(jsonPath("$.defaults['agentbridge.validation-max-corrections']").value(0))
                .andExpect(jsonPath("$.defaults['agentbridge.task-message']").isNotEmpty());
    }

    @Test
    void runConfigApiReadsAndFlattensTheStoredYaml(@TempDir Path tempDir) throws Exception {
        long runId = createCopiedRun(tempDir);
        mockMvc.perform(get("/api/runs/" + runId + "/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceRunId").value(runId))
                .andExpect(jsonPath("$.chainId").value("project-unit-test-generation"))
                .andExpect(jsonPath("$.mode").value("rerun"))
                .andExpect(jsonPath("$.rerunType").value("test-batch"))
                .andExpect(jsonPath("$.rerunId").value("batch-7"))
                .andExpect(jsonPath("$.runDate").value("2026-07-13"))
                .andExpect(jsonPath("$.config['project.id']").value("demo"))
                .andExpect(jsonPath("$.config['source.package-paths'][0]").value("com.example.service"))
                .andExpect(jsonPath("$.config['test.require-coverage']").value(false));
    }

    @Test
    void runConfigApiPreservesExplicitNullValues(@TempDir Path tempDir) throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "project-unit-test-generation", "full", null, null, null, Map.of(), null
        ), "missing-config.yml");
        Path configPath = tempDir.resolve("null-config.yml");
        Files.writeString(configPath, "project:\n  id: null\n");
        repository.updateConfigPath(runId, configPath.toString());

        String body = mockMvc.perform(get("/api/runs/" + runId + "/config"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"project.id\":null");
    }

    @Test
    void runConfigApiMapsMalformedYamlToStableBadRequest(@TempDir Path tempDir) throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "project-unit-test-generation", "full", null, null, null, Map.of(), null
        ), "missing-config.yml");
        Path configPath = tempDir.resolve("malformed-secret-config.yml");
        Files.writeString(configPath, "project: [unterminated\n");
        repository.updateConfigPath(runId, configPath.toString());

        mockMvc.perform(get("/api/runs/" + runId + "/config"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("运行配置文件无法读取或解析"))
                .andExpect(content().string(not(containsString(configPath.toString()))))
                .andExpect(content().string(not(containsString("JsonParseException"))));
    }

    @Test
    void pathPreflightRecognizesReadableMavenDirectory(@TempDir Path tempDir) throws Exception {
        Path tempProject = Files.createDirectory(tempDir.resolve("maven-project"));
        Files.writeString(tempProject.resolve("pom.xml"), "<project/>");
        mockMvc.perform(get("/api/path-preflight").param("path", tempProject.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessible").value(true))
                .andExpect(jsonPath("$.directory").value(true))
                .andExpect(jsonPath("$.mavenProject").value(true));
    }

    @Test
    void runConfigApiRejectsMissingAndNonFileConfigPaths(@TempDir Path tempDir) throws Exception {
        long missingPathRun = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), null);
        long directoryPathRun = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), tempDir.toString());

        mockMvc.perform(get("/api/runs/" + missingPathRun + "/config"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("运行没有配置文件")));
        mockMvc.perform(get("/api/runs/" + directoryPathRun + "/config"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("运行配置文件不存在或不可读取")));
    }

    @Test
    void pathPreflightReportsMissingPathsWithoutMutatingThem(@TempDir Path tempDir) throws Exception {
        Path missing = tempDir.resolve("not-created");

        mockMvc.perform(get("/api/path-preflight").param("path", missing.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessible").value(false))
                .andExpect(jsonPath("$.directory").value(false))
                .andExpect(jsonPath("$.mavenProject").value(false))
                .andExpect(jsonPath("$.message").value("路径不存在"));
        assertThat(missing).doesNotExist();
    }



    @Test
    void historyDeleteEndpointsRemoveTerminalRunsAndPreserveActiveRuns() throws Exception {
        long failedRunId = repository.createRun(new WorkflowRunSubmission(
                "weekly-engineering-report", "full", null, null, null, Map.of(), null
        ), "delete-api-failed.yml");
        repository.appendEvent(failedRunId, "FAILED", "failed");
        repository.markFailed(failedRunId, "failed");
        long succeededRunId = repository.createRun(new WorkflowRunSubmission(
                "weekly-engineering-report", "full", null, null, null, Map.of(), null
        ), "delete-api-succeeded.yml");
        repository.markSucceeded(succeededRunId);
        long queuedRunId = repository.createRun(new WorkflowRunSubmission(
                "weekly-engineering-report", "full", null, null, null, Map.of(), null
        ), "delete-api-queued.yml");

        mockMvc.perform(delete("/api/runs/" + failedRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));
        assertThat(repository.findRun(failedRunId)).isEmpty();

        mockMvc.perform(delete("/api/runs/" + queuedRunId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("运行中或排队中的记录不能清理")));

        mockMvc.perform(delete("/api/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        assertThat(repository.findRun(succeededRunId)).isEmpty();
        assertThat(repository.findRun(queuedRunId)).isPresent();
    }





    private long createCopiedRun(Path tempDir) throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "project-unit-test-generation",
                "rerun",
                "test-batch",
                "batch-7",
                LocalDate.of(2026, 7, 13),
                Map.of("project.id", "placeholder"),
                null
        ), "missing-config.yml");
        Path configPath = tempDir.resolve("copied-run.yml");
        Files.writeString(configPath, """
                project:
                  id: demo
                source:
                  package-paths:
                    - com.example.service
                test:
                  require-coverage: false
                """);
        repository.updateConfigPath(runId, configPath.toString());
        return runId;
    }

    @Test
    void postRunsValidatesUnknownChainAndRerunFields() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("""
                                {"chainId":"missing","mode":"full","config":{"project.id":"demo"}}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("未知链路")));

        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("""
                                {"chainId":"git-code-contribution-report","mode":"rerun","config":{"project.id":"demo"}}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("重跑模式必须填写重跑类型")));

        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("""
                                {"chainId":"git-code-contribution-report","mode":"rerun","rerunType":"author","config":{"project.id":"demo"}}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("重跑模式必须填写重跑 ID")));

        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("""
                                {"chainId":"git-code-contribution-report","mode":"rerun","rerunType":"missing","config":{"project.id":"demo"}}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("不支持的重跑类型")));

        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("""
                                {"chainId":"git-code-contribution-report","mode":"rerun","rerunType":"总报告","config":{"project.id":"demo"}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    void runApiRejectsMyBatisIndexRerunWithAnId() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("""
                                {"chainId":"mybatis-sql-review","mode":"rerun",
                                 "rerunType":"index","rerunId":"stale-statement-key","config":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("该重跑类型不能填写重跑 ID"));
    }

    @Test
    void runApiNormalizesMixedCaseChainBeforeApplyingRerunIdContract() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("""
                                {"chainId":"MyBatis-SQL-Review","mode":"rerun",
                                 "rerunType":"index","rerunId":"stale-statement-key","config":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("该重跑类型不能填写重跑 ID"));

        mockMvc.perform(post("/api/runs")
                        .contentType("application/json")
                        .content("""
                                {"chainId":"MyBatis-SQL-Review","mode":"rerun",
                                 "rerunType":"sql","config":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("重跑模式必须填写重跑 ID"));
    }

    @Test
    void scheduleApiRejectsMyBatisIndexRerunWithAnId() throws Exception {
        mockMvc.perform(post("/api/schedules")
                        .contentType("application/json")
                        .content("""
                                {"chainId":"mybatis-sql-review","mode":"rerun",
                                 "rerunType":"index","rerunId":"stale-statement-key","config":{},
                                 "frequency":"daily","runTime":"06:00","enabled":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("该重跑类型不能填写重跑 ID"));
    }

    @Test
    void scheduleApiCreatesAndTogglesSchedules() throws Exception {
        String createResponse = mockMvc.perform(post("/api/schedules")
                        .contentType("application/json")
                        .content("""
                                {
                                  "chainId":"git-code-contribution-report",
                                  "mode":"full",
                                  "runDate":"2026-06-30",
                                  "config":{"project.id":"demo"},
                                  "frequency":"weekly",
                                  "dayOfWeek":5,
                                  "runTime":"06:00",
                                  "enabled":true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(createResponse).contains("id");

        mockMvc.perform(get("/api/schedules"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("git-code-contribution-report")))
                .andExpect(content().string(containsString("WEEKLY")));

        mockMvc.perform(post("/api/schedules/1/enabled")
                        .contentType("application/json")
                        .content("""
                                {"enabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void scheduleApiRejectsReenablingAnAlreadyTriggeredOneTimeSchedule() throws Exception {
        String createResponse = mockMvc.perform(post("/api/schedules")
                        .contentType("application/json")
                        .content("""
                                {"chainId":"git-code-contribution-report","mode":"full",
                                 "config":{"project.id":"once"},"frequency":"once",
                                 "runAt":"2026-06-30T06:00","enabled":true}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(createResponse).get("id").asLong();
        scheduleRepository.markTriggered(id, Instant.parse("2026-06-29T22:00:00Z"), null, false);

        mockMvc.perform(post("/api/schedules/" + id + "/enabled")
                        .contentType("application/json")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("已执行的一次性计划不能重新启用，请复制后创建新计划"));
    }

    @Test
    void scheduleApiUpdatesSchedulesAndReturnsStableNotFound() throws Exception {
        String createResponse = mockMvc.perform(post("/api/schedules")
                        .contentType("application/json")
                        .content("""
                                {"chainId":"git-code-contribution-report","mode":"full",
                                 "config":{"project.id":"before"},"frequency":"daily",
                                 "runTime":"06:00","enabled":true}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(createResponse).get("id").asLong();

        mockMvc.perform(post("/api/schedules/" + id)
                        .contentType("application/json")
                        .content("""
                                {"chainId":"git-code-contribution-report","mode":"full",
                                 "config":{"project.id":"after"},"frequency":"weekly",
                                 "dayOfWeek":5,"runTime":"07:30","enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.runTime").value("07:30:00"))
                .andExpect(jsonPath("$.config['project.id']").value("after"));

        mockMvc.perform(post("/api/schedules/999999")
                        .contentType("application/json")
                        .content("""
                                {"chainId":"git-code-contribution-report","mode":"full",
                                 "config":{},"frequency":"daily","runTime":"06:00","enabled":true}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("未找到"));
    }


    @Test
    void sseEndpointStartsStreamWithExistingEvents() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report",
                "full",
                null,
                null,
                null,
                Map.of("project.id", "demo"),
                null
        ), "run-config.yml");
        repository.appendEvent(runId, "QUEUED", "运行已进入队列");

        mockMvc.perform(get("/api/runs/" + runId + "/events"))
                .andExpect(request().asyncStarted());
    }
}
