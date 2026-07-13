package com.sonnet.wyf.gitreport.console;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
    WebApplicationContext webApplicationContext;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void pagesRenderFromPersistedRuns() throws Exception {
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report",
                "full",
                null,
                null,
                LocalDate.of(2026, 6, 29),
                Map.of("project.id", "demo"),
                null
        ), "run-config.yml");
        repository.appendEvent(runId, "QUEUED", "运行已进入队列");

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("AgentBridge 任务控制台")))
                .andExpect(content().string(containsString("最近运行")))
                .andExpect(content().string(containsString("代码贡献报告")));
        mockMvc.perform(get("/"))
                .andExpect(content().string(containsString("class=\"app-shell\"")))
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
                .andExpect(content().string(containsString("提交运行")));
        mockMvc.perform(get("/schedules").param("chainId", "weekly-engineering-report"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<option value=\"review-batch\">审查批次</option>")))
                .andExpect(content().string(containsString("<option value=\"synthesis\">总报告</option>")));
        mockMvc.perform(get("/runs/new").param("chainId", "project-unit-test-generation"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("单元测试生成")))
                .andExpect(content().string(containsString("<option value=\"test-batch\">测试批次</option>")))
                .andExpect(content().string(containsString("<option value=\"verification\">验证</option>")));
        String appJs = mockMvc.perform(get("/app.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(appJs)
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
                .contains("/api/chains/")
                .doesNotContain("review.max-regions-per-batch")
                .doesNotContain("test.concurrency")
                .doesNotContain("test.max-types-per-task")
                .doesNotContain("每批最大类型数")
                .doesNotContain("worker-message")
                .doesNotContain("synthesis-message")
                .doesNotContain("AgentBridge session")
                .doesNotContain("AgentBridge 设置会复制应用默认值")
                .doesNotContain("rerunTypeSelect.disabled = !rerunMode")
                .doesNotContain("src/main/resources/smartesb-transactions");
        mockMvc.perform(get("/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("运行 " + runId)))
                .andExpect(content().string(containsString("事件流")));
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
