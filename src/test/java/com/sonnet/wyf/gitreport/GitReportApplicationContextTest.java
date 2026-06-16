package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.GitReportOrchestrator;
import com.sonnet.wyf.gitreport.preparation.GitReportPreparation;
import com.sonnet.wyf.gitreport.prompt.PromptBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class GitReportApplicationContextTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GitReportApplication.class)
            .withPropertyValues("git-report.enabled=false");

    @Test
    void loadsSplitModuleConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(GitReportProperties.class);
            assertThat(context).hasSingleBean(GitReportPreparation.class);
            assertThat(context).hasSingleBean(PromptBuilder.class);
            assertThat(context).hasSingleBean(OpenCodeServerManager.class);
            assertThat(context).hasSingleBean(OpenCodeServerTaskRunner.class);
            assertThat(context).hasSingleBean(GitReportOrchestrator.class);
            assertThat(context).hasSingleBean(ScheduledProbeWaiter.class);
            assertThat(context).hasBean("authorTaskExecutor");
            assertThat(context).hasBean("openCodeTaskScheduler");
            assertThat(context.getBean("authorTaskExecutor")).isInstanceOf(AsyncTaskExecutor.class);
            assertThat(context.getBean("openCodeTaskScheduler")).isInstanceOf(TaskScheduler.class);
        });
    }
}
