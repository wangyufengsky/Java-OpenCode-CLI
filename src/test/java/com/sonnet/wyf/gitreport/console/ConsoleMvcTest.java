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
import java.util.regex.Pattern;

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
    void pagesRenderFromPersistedRuns() throws Exception {
        long succeededRunId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report",
                "full",
                null,
                null,
                LocalDate.of(2026, 6, 29),
                Map.of("project.id", "demo"),
                null
        ), "run-config.yml");
        repository.markRunning(succeededRunId);
        repository.markSucceeded(succeededRunId);

        long failedRunId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report",
                "full",
                null,
                null,
                LocalDate.of(2026, 6, 29),
                Map.of("project.id", "failed-demo"),
                null
        ), "failed-run-config.yml");
        repository.markRunning(failedRunId);
        repository.markFailed(failedRunId, "运行失败，请检查失败任务");
        repository.upsertTaskStatus(new WorkflowTaskStatus(
                failedRunId,
                "failed-task",
                "failed-task",
                "FAILED",
                "execution",
                null,
                "failed-task 执行失败",
                Instant.now()
        ));
        repository.appendEvent(failedRunId, "TASK_FAILED", "failed-task 执行失败");

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("AgentBridge 任务控制台")))
                .andExpect(content().string(containsString("最近运行")))
                .andExpect(content().string(containsString("代码贡献报告")))
                .andExpect(content().string(containsString("需要关注")))
                .andExpect(content().string(containsString("成功率")));
        mockMvc.perform(get("/"))
                .andExpect(content().string(containsString("class=\"app-shell\"")))
                .andExpect(content().string(containsString("class=\"c-metric-card\"")))
                .andExpect(content().string(containsString("data-tone=\"primary\"")))
                .andExpect(content().string(containsString("data-tone=\"info\"")))
                .andExpect(content().string(containsString("data-tone=\"success\"")))
                .andExpect(content().string(containsString("data-tone=\"danger\"")))
                .andExpect(content().string(containsString("data-trend-tone=\"neutral\"")))
                .andExpect(content().string(containsString("class=\"c-table-row\"")))
                .andExpect(content().string(containsString("class=\"table-scroll\"")))
                .andExpect(content().string(containsString("<th>耗时</th>")))
                .andExpect(content().string(containsString("class=\"alert c-alert danger\"")))
                .andExpect(content().string(containsString("aria-label=\"主导航\"")))
                .andExpect(content().string(containsString("运行概览")))
                .andExpect(content().string(containsString("新建运行")))
                .andExpect(content().string(containsString("运行历史")))
                .andExpect(content().string(containsString("定时任务")));
        mockMvc.perform(get("/history"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("运行历史")))
                .andExpect(content().string(containsString("排队中")));
        mockMvc.perform(get("/schedules"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("定时任务")))
                .andExpect(content().string(containsString("每天")))
                .andExpect(content().string(containsString("每周")))
                .andExpect(content().string(containsString("一次性")))
                .andExpect(content().string(containsString("代码贡献报告")));
        mockMvc.perform(get("/runs/new").param("chainId", "git-code-contribution-report"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("链路配置")))
                .andExpect(content().string(containsString("<option value=\"author\">作者</option>")))
                .andExpect(content().string(containsString("<option value=\"synthesis\">总报告</option>")))
                .andExpect(content().string(not(containsString("配置快照"))))
                .andExpect(content().string(not(containsString("configYaml"))))
                .andExpect(content().string(containsString("确认并运行")));
        mockMvc.perform(get("/schedules").param("chainId", "weekly-engineering-report"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<option value=\"review-batch\">审查批次</option>")))
                .andExpect(content().string(containsString("<option value=\"synthesis\">总报告</option>")));
        mockMvc.perform(get("/runs/new").param("chainId", "project-unit-test-generation"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("单元测试生成")))
                .andExpect(content().string(containsString("<option value=\"test-batch\">测试批次</option>")))
                .andExpect(content().string(containsString("<option value=\"verification\">验证</option>")));
        String commonJs = mockMvc.perform(get("/js/console-common.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(commonJs)
                .contains("项目标识")
                .contains("transaction-plan-dir")
                .contains("review.grouping.max-regions-per-task")
                .contains("project-unit-test-generation")
                .contains("source.package-paths")
                .contains("每任务一个类")
                .contains("agentbridge.task-message")
                .contains("agentbridge.synthesis-task-message")
                .contains("task-message")
                .contains("synthesis-task-message")
                .contains("agentbridge.web-base-url")
                .contains("agentbridge.mcp-url")
                .contains("agentbridge.max-attempts")
                .contains("git-code-contribution-report")
                .contains("synthesis")
                .contains("总报告重跑不需要编号")
                .doesNotContain("review.max-regions-per-batch")
                .doesNotContain("test.concurrency")
                .doesNotContain("test.max-types-per-task")
                .doesNotContain("每批最大类型数")
                .doesNotContain("worker-message")
                .doesNotContain("synthesis-message")
                .doesNotContain("AgentBridge session")
                .doesNotContain("AgentBridge 设置会复制应用默认值")
                .doesNotContain("src/main/resources/smartesb-transactions");
        String styles = mockMvc.perform(get("/styles.css"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(styles)
                .contains("@layer foundations, shell, components, pages;")
                .contains("@layer foundations {")
                .contains("@layer shell {")
                .contains("@layer components {")
                .contains("@layer pages {")
                .doesNotContain("html {\n  min-width: 1280px;")
                .doesNotContain("body {\n  min-width: 1280px;")
                .doesNotContain(".app-main {\n    max-width: none;")
                .contains(".app-main {\n    max-width: 1216px;\n    margin: 0 auto;")
                .contains(":focus-visible {\n  outline: 3px solid var(--color-primary);")
                .contains("@media (max-width: 1439px)")
                .contains("grid-template-columns: minmax(0, 1fr) 300px;")
                .contains(".dashboard-grid > article.panel > .table-scroll > table {\n    min-width: 620px;")
                .contains("  .dashboard-grid > aside.panel table {\n    width: 100%;\n    min-width: 0;")
                .doesNotContain(".dashboard-grid table {\n    min-width: 620px;")
                .doesNotContain(".panel {\n  padding: 20px;\n  overflow-x: auto;")
                .contains(".table-scroll {\n  overflow-x: auto;")
                .contains("  .dashboard-grid > aside.panel .event-list li {\n    grid-template-columns: minmax(0, 1fr);")
                .contains("grid-template-columns: repeat(2, minmax(140px, 1fr));");
        String failedRunDetail = mockMvc.perform(get("/runs/" + failedRunId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("运行 " + failedRunId)))
                .andExpect(content().string(containsString("事件流")))
                .andExpect(content().string(containsString("失败摘要")))
                .andExpect(content().string(containsString("failed-task")))
                .andExpect(content().string(containsString("重跑失败任务")))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(failedRunDetail).contains(
                "href=\"/runs/new?chainId=git-code-contribution-report&amp;mode=rerun&amp;rerunType=author&amp;rerunId=failed-task\""
        );

        String rerunForm = mockMvc.perform(get("/runs/new")
                        .param("chainId", "git-code-contribution-report")
                        .param("mode", "rerun")
                        .param("rerunType", "author")
                        .param("rerunId", "failed-task"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(rerunForm)
                .containsPattern("<option value=\"rerun\" selected(?:=\"selected\")?>重跑</option>")
                .containsPattern("<option value=\"author\" selected(?:=\"selected\")?>作者</option>")
                .contains("<input id=\"rerunId\" name=\"rerunId\" placeholder=\"多个编号用英文逗号分隔\" value=\"failed-task\">");
    }

    @Test
    void myBatisSqlReviewIsWiredIntoTheCatalogConsoleAndRerunContract() throws Exception {
        assertThat(webApplicationContext.getBeansOfType(MyBatisSqlInventoryBuilder.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisDatabasePreflight.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisSqlPromptBuilder.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisToolCallAudit.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisSqlOutputValidator.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisSqlReviewTaskRunner.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisSqlReportRenderer.class)).hasSize(1);
        assertThat(webApplicationContext.getBeansOfType(MyBatisSqlReviewWorkflowChain.class)).hasSize(1);

        String page = mockMvc.perform(get("/runs/new").param("chainId", "mybatis-sql-review"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MyBatis SQL 审查")))
                .andExpect(content().string(containsString("mybatis-sql-review")))
                .andExpect(content().string(containsString(
                        "src=\"/js/console-common.js?v=20260722-mybatis-sql-review\"")))
                .andExpect(content().string(containsString("<option value=\"sql\">SQL 语句</option>")))
                .andExpect(content().string(containsString("<option value=\"xml\">Mapper XML</option>")))
                .andExpect(content().string(containsString("<option value=\"index\">总报告</option>")))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("id=\"summary-output\"");

        String commonJs = mockMvc.perform(get("/js/console-common.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(commonJs).contains(
                "'mybatis-sql-review'",
                "project.id",
                "project.name",
                "project.repo",
                "paths.out",
                "source.include",
                "source.exclude",
                "database.connection-name",
                "AgentBridge Custom MCP 注册的 Database MCP 数据源名称",
                "database.database-name",
                "database.schema-name",
                "database.scope",
                "GLOBAL、PROJECT 或 ALL",
                "database.environment",
                "database.non-owner-non-admin-read-only-account",
                "database.row-level-security-disabled-for-safe-base-tables",
                "database.user-defined-and-security-definer-function-execution-revoked-including-public",
                "database.statement-timeout-seconds",
                "database.statement-timeout-scope",
                "database.max-rows",
                "database.max-scenarios-per-sql",
                "database.max-evidence-bytes",
                "database.retain-raw-rows",
                "database.allow-agent-select",
                "agentbridge.web-base-url",
                "agentbridge.mcp-url",
                "AgentBridge Custom MCP 注册的数据库工具",
                "http://127.0.0.1:8642/mcp",
                "agentbridge.concurrency",
                "agentbridge.max-concurrency",
                "agentbridge.timeout-minutes",
                "agentbridge.poll-millis",
                "agentbridge.validation-settle-seconds",
                "agentbridge.validation-max-corrections",
                "agentbridge.task-message",
                "{ value: 'sql', label: 'SQL 语句', requiresId: true",
                "{ value: 'xml', label: 'Mapper XML', requiresId: true",
                "{ value: 'index', label: '总报告', requiresId: false"
        ).doesNotContain("AgentBridge Database Tools");
        String runFormJs = mockMvc.perform(get("/js/run-form.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(runFormJs)
                .contains("document.querySelector('#summary-output')")
                .contains("'paths.out', 'local-out', 'out'");

        assertThat(WorkflowRerunContract.requiresRerunId("mybatis-sql-review", "sql")).isTrue();
        assertThat(WorkflowRerunContract.requiresRerunId("mybatis-sql-review", "xml")).isTrue();
        assertThat(WorkflowRerunContract.requiresRerunId("mybatis-sql-review", "index")).isFalse();
        assertThat(WorkflowRerunContract.isKnownType("mybatis-sql-review", "sql")).isTrue();
        assertThat(WorkflowRerunContract.isKnownType("mybatis-sql-review", "xml")).isTrue();
        assertThat(WorkflowRerunContract.isKnownType("mybatis-sql-review", "index")).isTrue();
    }

    @Test
    void dashboardRendersAtMostEightRecentTableRows() throws Exception {
        for (int index = 0; index < 9; index++) {
            repository.createRun(new WorkflowRunSubmission(
                    "git-code-contribution-report", "full", null, null, LocalDate.of(2026, 7, 13),
                    Map.of("project.id", "dashboard-limit-" + index), null
            ), "dashboard-limit-" + index + ".yml");
        }

        String dashboard = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(Pattern.compile("<tr class=\\\"c-table-row\\\">").matcher(dashboard).results().count())
                .isEqualTo(8);
    }

    @Test
    void dashboardDoesNotRenderFailureAlertWhenPersistedRunsHaveNoFailures() throws Exception {
        jdbcTemplate.update("delete from workflow_run_events");
        jdbcTemplate.update("delete from workflow_task_status");
        jdbcTemplate.update("delete from workflow_runs");
        repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, LocalDate.of(2026, 7, 13), Map.of(), null
        ), "healthy-dashboard.yml");

        String dashboard = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(dashboard)
                .doesNotContain("class=\"alert c-alert danger\"")
                .doesNotContain("项失败运行需要处理");
    }

    @Test
    void taskStageUsesTaskStatesAndKeepsMixedSucceededAndRunningTasksInProgress() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report",
                "full",
                null,
                null,
                LocalDate.of(2026, 7, 13),
                Map.of("project.id", "mixed-task-states"),
                null
        ), "mixed-task-config.yml");
        repository.markRunning(runId);
        repository.upsertTaskStatus(new WorkflowTaskStatus(
                runId,
                "finished-task",
                "finished-task",
                "SUCCEEDED",
                "complete",
                null,
                null,
                Instant.now()
        ));
        repository.upsertTaskStatus(new WorkflowTaskStatus(
                runId,
                "active-task",
                "active-task",
                "RUNNING",
                "complete",
                null,
                null,
                Instant.now()
        ));
        repository.appendEvent(runId, "TASK_SUCCEEDED", "finished-task complete");
        repository.appendEvent(runId, "TASK_GROUP_SUCCEEDED", "旧的任务组完成事件");

        String detail = mockMvc.perform(get("/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail).containsPattern("(?s)<strong>任务</strong>\\s*<span>进行中</span>");
    }

    @Test
    void failedTaskWithoutSafeRerunTypeDoesNotExposeFabricatedRerunLink() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "smartesb-rewrite-code-review",
                "full",
                null,
                null,
                LocalDate.of(2026, 7, 13),
                Map.of("project.id", "ambiguous-rerun"),
                null
        ), "ambiguous-rerun-config.yml");
        repository.markRunning(runId);
        repository.markFailed(runId, "任务失败");
        repository.upsertTaskStatus(new WorkflowTaskStatus(
                runId,
                "ambiguous-task",
                "ambiguous-task",
                "FAILED",
                "execution",
                null,
                "任务失败",
                Instant.now()
        ));

        String detail = mockMvc.perform(get("/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail)
                .containsPattern("(?s)<a id=\"rerun-failed-task\"[^>]*hidden=\"hidden\"[^>]*>重跑失败任务</a>")
                .doesNotContain("mode=rerun")
                .contains("无法安全确定重跑类型")
                .contains("href=\"/runs/new?chainId=smartesb-rewrite-code-review\">配置新运行</a>");
    }

    @Test
    void eventFilterIsPreservedForSseAndEmptyStateCanBeRemoved() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report",
                "full",
                null,
                null,
                LocalDate.of(2026, 7, 13),
                Map.of("project.id", "filtered-events"),
                null
        ), "filtered-events-config.yml");
        repository.appendEvent(runId, "STARTED", "运行开始");

        String detail = mockMvc.perform(get("/runs/" + runId).param("eventFilter", "failed"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail)
                .contains("<ol id=\"events\" class=\"event-list\" data-event-filter=\"failed\">")
                .contains("<li data-empty-state=\"true\"><span class=\"empty\">当前筛选下暂无事件。</span></li>");

        String runDetailJs = mockMvc.perform(get("/js/run-detail.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(runDetailJs)
                .contains("events.dataset.eventFilter")
                .contains("function matchesEventFilter(event, filter)")
                .contains("const emptyState = events.querySelector('[data-empty-state]')")
                .containsSubsequence(
                        "seenIds.add(eventId);",
                        "if (!matchesEventFilter(event, eventFilter)) return;"
                );

        assertThat(detail)
                .contains("/js/run-detail.js")
                .doesNotContain("/app.js");
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
    void runSnapshotProvidesTheSameSafeFailedTaskActionAsTheInitialPage() throws Exception {
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

        mockMvc.perform(get("/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"failure-actions\"")))
                .andExpect(content().string(containsString("id=\"rerun-failed-task\"")))
                .andExpect(content().string(containsString("rerunId=author-a")));
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
    void runDetailScriptOwnsReconnectPollingDeduplicationAndClientFilters() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "detail-assets-config.yml");

        String detail = mockMvc.perform(get("/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail)
                .contains("id=\"event-filter\"")
                .contains("id=\"task-filter\"")
                .contains("id=\"run-summary\"")
                .contains("id=\"task-rows\"")
                .contains("/js/run-detail.js")
                .doesNotContain("/app.js");

        String script = mockMvc.perform(get("/js/run-detail.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(script)
                .contains("new EventSource")
                .contains("lastEventId")
                .contains("seenIds")
                .contains("consecutiveErrors")
                .contains("5000")
                .contains("/snapshot?afterEventId=")
                .contains("stopSnapshotPolling")
                .contains("textContent")
                .doesNotContain("innerHTML");
    }

    @Test
    void newRunAndRunDetailRenderTheSharedFigmaCompositionWithoutChangingDomHooks() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "visual-composition.yml");

        String newRun = mockMvc.perform(get("/runs/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(newRun)
                .contains("c-step-navigation")
                .contains("data-step=\"1\"")
                .contains("id=\"run-form\"")
                .contains("id=\"chain-config-fields\"")
                .contains("id=\"preflight-alert\"")
                .contains("data-field-error=\"true\"")
                .contains("c-run-summary")
                .contains("id=\"submit-run\"");

        String detail = mockMvc.perform(get("/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail)
                .contains("c-progress-indicator")
                .contains("data-progress-steps=\"5\"")
                .contains("data-connection-state=\"connecting\"")
                .contains("id=\"events\"")
                .contains("id=\"task-rows\"")
                .contains("id=\"rerun-failed-task\"");
    }

    @Test
    void pagesLoadOnlyTheirScopedScriptsAndLegacyBundleIsRetired() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "asset-contract-config.yml");

        assertThat(mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("class=\"brand-copy\"")
                .contains("<strong>AgentBridge</strong>")
                .contains("本地任务控制台")
                .contains("class=\"metrics\" aria-label=\"运行指标\"")
                .contains("<span>需要关注</span>")
                .doesNotContain("已连接")
                .doesNotContain("<article><span>排队中</span>")
                .doesNotContain("<article><span>已成功</span>")
                .doesNotContain("<article><span>已失败</span>")
                .doesNotContain("<script src=");
        assertThat(mockMvc.perform(get("/runs/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("<title>新建运行</title>")
                .contains("<h1>新建运行</h1>")
                .contains("<script src=\"/js/console-common.js?v=20260722-mybatis-sql-review\"></script>")
                .contains("<script src=\"/js/run-form.js?v=20260720-figma-summary\"></script>")
                .doesNotContain("/js/run-detail.js", "/js/history.js", "/js/schedules.js", "/app.js");
        assertThat(mockMvc.perform(get("/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("<script src=\"/js/run-detail.js\"></script>")
                .doesNotContain("/js/console-common.js", "/js/run-form.js", "/js/history.js", "/js/schedules.js", "/app.js");
        assertThat(mockMvc.perform(get("/history"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("<script src=\"/js/history.js\"></script>")
                .doesNotContain("/js/console-common.js", "/js/run-form.js", "/js/run-detail.js", "/js/schedules.js", "/app.js");
        assertThat(mockMvc.perform(get("/schedules"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("<script src=\"/js/console-common.js\"></script>")
                .contains("<script src=\"/js/schedules.js\"></script>")
                .doesNotContain("/js/run-form.js", "/js/run-detail.js", "/js/history.js", "/app.js");
        mockMvc.perform(get("/app.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    void runFormKeepsSubmitDisabledWhileRequestOrConfigurationIsInFlight() throws Exception {
        String script = mockMvc.perform(get("/js/run-form.js"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(script)
                .contains("let submitInFlight = false")
                .contains("if (submitInFlight) return;")
                .contains("submitButton.disabled = submitInFlight || configLoading || !configReady")
                .contains("submitInFlight = true")
                .contains("submitInFlight = false")
                .doesNotContain("finally {\n      submitButton.disabled = false;");
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
                .andExpect(jsonPath("$.defaults['source.include'][0]").value("**/*.xml"))
                .andExpect(jsonPath("$.defaults['source.exclude'][0]").value("target/**"))
                .andExpect(jsonPath("$.defaults['database.connection-name']")
                        .value("CHANGE_ME_AGENTBRIDGE_CONNECTION_NAME"))
                .andExpect(jsonPath("$.defaults['database.database-name']").value("CHANGE_ME_DATABASE"))
                .andExpect(jsonPath("$.defaults['database.schema-name']").value("CHANGE_ME_SCHEMA"))
                .andExpect(jsonPath("$.defaults['database.scope']").value("ALL"))
                .andExpect(jsonPath("$.defaults['database.environment']").value("read-replica"))
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
    void copiedRunPageShowsTheSourceRunBanner(@TempDir Path tempDir) throws Exception {
        long runId = createCopiedRun(tempDir);
        mockMvc.perform(get("/runs/new").param("copyFrom", String.valueOf(runId)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("复制自运行 #" + runId)))
                .andExpect(content().string(containsString("运行摘要")));
    }

    @Test
    void historyFiltersPersistAndExposeHistoryManagementActions() throws Exception {
        long failedRunId = repository.createRun(new WorkflowRunSubmission(
                "weekly-engineering-report", "full", null, null, null, Map.of(), null
        ), "weekly-history-config.yml");
        repository.markFailed(failedRunId, "历史筛选测试失败");
        long otherRunId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "other-history-config.yml");

        String history = mockMvc.perform(get("/history")
                        .param("q", "weekly")
                        .param("state", "FAILED")
                        .param("chainId", "weekly-engineering-report")
                        .param("from", "2026-07-01")
                        .param("until", "2026-07-31"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(history)
                .contains("name=\"q\"")
                .contains("value=\"weekly\"")
                .containsPattern("(?s)<option value=\"FAILED\"\\s+selected(?:=\"selected\")?>已失败</option>")
                .containsPattern("(?s)<option value=\"weekly-engineering-report\"\\s+selected(?:=\"selected\")?>研发周报</option>")
                .contains("name=\"from\"")
                .contains("value=\"2026-07-01\"")
                .contains("name=\"until\"")
                .contains("value=\"2026-07-31\"")
                .contains("href=\"/runs/" + failedRunId + "?q=weekly&amp;state=FAILED&amp;chainId=weekly-engineering-report&amp;from=2026-07-01&amp;until=2026-07-31\">查看详情</a>")
                .contains("href=\"/runs/new?copyFrom=" + failedRunId + "&amp;q=weekly&amp;state=FAILED&amp;chainId=weekly-engineering-report&amp;from=2026-07-01&amp;until=2026-07-31\">复制配置</a>")
                .contains("data-history-action=\"rerun\"")
                .contains("data-run-id=\"" + failedRunId + "\"")
                .contains("data-history-action=\"delete\"")
                .contains("data-history-action=\"clear-all\"")
                .doesNotContain("href=\"/runs/" + otherRunId + "\">查看详情</a>");
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

    @Test
    void historyShowsClearFiltersForNoResultsAndIgnoresInvalidState() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "invalid-state-history-config.yml");

        mockMvc.perform(get("/history").param("q", "definitely-no-history-result"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("没有符合当前筛选条件的运行")))
                .andExpect(content().string(containsString("href=\"/history\">清除筛选</a>")));

        mockMvc.perform(get("/history").param("state", "not-a-state"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/runs/" + runId + "\">查看详情</a>")))
                .andExpect(content().string(not(containsString("value=\"not-a-state\" selected"))));
    }

    @Test
    void historyClampsPagesAndKeepsAllActiveFiltersInCanonicalLinks() throws Exception {
        for (int index = 0; index < 41; index++) {
            repository.createRun(new WorkflowRunSubmission(
                    "weekly-engineering-report", "full", null, null, null, Map.of(), null
            ), "weekly-page-" + index + ".yml");
        }

        String history = mockMvc.perform(get("/history")
                        .param("q", "weekly-page")
                        .param("state", "QUEUED")
                        .param("chainId", "weekly-engineering-report")
                        .param("from", "2026-07-01")
                        .param("until", "2026-07-31")
                        .param("page", "999"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(history)
                .contains("当前第 3 / 3 页")
                .contains("href=\"/history?q=weekly-page&amp;state=QUEUED&amp;chainId=weekly-engineering-report&amp;from=2026-07-01&amp;until=2026-07-31&amp;page=2\"")
                .contains("href=\"/runs/new?copyFrom=")
                .contains("q=weekly-page&amp;state=QUEUED&amp;chainId=weekly-engineering-report&amp;from=2026-07-01&amp;until=2026-07-31&amp;page=3\"");

        String empty = mockMvc.perform(get("/history").param("q", "no-page-result").param("page", "999"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(empty).contains("当前第 1 / 1 页");

        String first = mockMvc.perform(get("/history")
                        .param("q", "weekly-page")
                        .param("state", "QUEUED")
                        .param("chainId", "weekly-engineering-report")
                        .param("page", "-8"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(first).contains("当前第 1 / 3 页")
                .contains("href=\"/history?q=weekly-page&amp;state=QUEUED&amp;chainId=weekly-engineering-report&amp;page=2\"");
    }

    @Test
    void historyNormalizesNonIntegerPageRequestsWithoutOverflow() throws Exception {
        for (int index = 0; index < 41; index++) {
            repository.createRun(new WorkflowRunSubmission(
                    "weekly-engineering-report", "full", null, null, null, Map.of(), null
            ), "overflow-page-" + index + ".yml");
        }

        String aboveIntegerMaximum = mockMvc.perform(get("/history")
                        .param("q", "overflow-page")
                        .param("page", "2147483648"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String giantNumeric = mockMvc.perform(get("/history")
                        .param("q", "overflow-page")
                        .param("page", "999999999999999999999999999999999999999999"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String nonNumeric = mockMvc.perform(get("/history")
                        .param("q", "overflow-page")
                        .param("page", "not-a-page"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(aboveIntegerMaximum).contains("当前第 3 / 3 页");
        assertThat(giantNumeric).contains("当前第 3 / 3 页");
        assertThat(nonNumeric).contains("当前第 1 / 3 页");
    }

    @Test
    void newRunAssetsExposeGroupedDefaultsPreflightAndResilientSubmission() throws Exception {
        String page = mockMvc.perform(get("/runs/new").param("chainId", "project-unit-test-generation"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page)
                .contains("选择工作流")
                .contains("填写配置")
                .contains("确认并运行")
                .contains("workflow-cards")
                .contains("id=\"copy-load-status\"")
                .contains("id=\"rerunId-validation\"")
                .doesNotContain("id=\"rerun-validation\"")
                .contains("console-common.js")
                .contains("run-form.js");

        String commonJs = mockMvc.perform(get("/js/console-common.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(commonJs)
                .contains("function field(key, label, description, type = 'text', group = 'advanced', required = false, summary = false)")
                .contains("group: 'project'")
                .contains("group: 'scope'")
                .contains("group: 'validation'")
                .contains("group: 'agentbridge'")
                .contains("agentbridge.web-base-url")
                .doesNotContain("test.concurrency")
                .doesNotContain("worker-message");

        String runFormJs = mockMvc.perform(get("/js/run-form.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(runFormJs)
                .contains("400")
                .contains("AbortController")
                .contains("endsWith('.repo')")
                .contains("endsWith('-root')")
                .contains("恢复默认值")
                .contains("firstInvalid.focus()")
                .contains("if (submitInFlight) return;")
                .contains("submitButton.disabled = submitInFlight || configLoading || !configReady")
                .contains("window.location.href = `/runs/${body.id}`")
                .contains("/api/path-preflight")
                .contains("/config")
                .contains("Object.prototype.hasOwnProperty.call(values, definitionField.key)")
                .contains("let nextDefaults")
                .contains("const copySnapshot")
                .contains("sequence !== renderSequence")
                .contains("复制配置读取失败，已保留链路默认值。")
                .contains("let configLoading = false")
                .contains("if (configLoading)")
                .contains("validation.textContent = message")
                .doesNotContain("if (validation && message) validation.textContent = message");
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
    void schedulesPageProvidesSearchDrawerCopyEditAndAccessibleSwitches() throws Exception {
        mockMvc.perform(post("/api/schedules")
                        .contentType("application/json")
                        .content("""
                                {"chainId":"git-code-contribution-report","mode":"full",
                                 "config":{"project.id":"schedule-ui"},"frequency":"daily",
                                 "runTime":"06:00","enabled":true}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/schedules"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"schedule-search\"")))
                .andExpect(content().string(containsString("id=\"schedule-status-filter\"")))
                .andExpect(content().string(containsString("id=\"schedule-drawer\"")))
                .andExpect(content().string(containsString("id=\"schedule-save-notice\"")))
                .andExpect(content().string(containsString("代码贡献报告 · 全量 #")))
                .andExpect(content().string(containsString("data-action=\"edit\"")))
                .andExpect(content().string(containsString("data-action=\"copy\"")))
                .andExpect(content().string(containsString("role=\"switch\"")))
                .andExpect(content().string(containsString("aria-checked=\"true\"")))
                .andExpect(content().string(containsString("/js/schedules.js")));
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
