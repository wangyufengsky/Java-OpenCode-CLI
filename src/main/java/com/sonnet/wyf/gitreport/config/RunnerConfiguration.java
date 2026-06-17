package com.sonnet.wyf.gitreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.GitReportOrchestrator;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunner;
import com.sonnet.wyf.gitreport.workflow.gitreport.GitReportWorkflowChain;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbDailyTransactionPlanLoader;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbPromptBuilder;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbReviewPreparation;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbSummaryValidator;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbWorkflowChain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.io.ResourceLoader;

import java.util.List;

@Configuration
public class RunnerConfiguration {
    @Bean
    ChainConfigLoader chainConfigLoader(ResourceLoader resourceLoader) {
        return new ChainConfigLoader(resourceLoader);
    }

    @Bean
    SmartEsbDailyTransactionPlanLoader smartEsbDailyTransactionPlanLoader() {
        return new SmartEsbDailyTransactionPlanLoader();
    }

    @Bean
    SmartEsbReviewPreparation smartEsbReviewPreparation(ObjectMapper objectMapper) {
        return new SmartEsbReviewPreparation(objectMapper);
    }

    @Bean
    SmartEsbPromptBuilder smartEsbPromptBuilder(ResourceLoader resourceLoader) {
        return new SmartEsbPromptBuilder(resourceLoader);
    }

    @Bean
    SmartEsbSummaryValidator smartEsbSummaryValidator(ObjectMapper objectMapper) {
        return new SmartEsbSummaryValidator(objectMapper);
    }

    @Bean
    WorkflowChain gitReportWorkflowChain(ChainConfigLoader chainConfigLoader, OpenCodeRunnerProperties runnerProperties, GitReportOrchestrator orchestrator) {
        return new GitReportWorkflowChain(chainConfigLoader, runnerProperties, orchestrator);
    }

    @Bean
    WorkflowChain smartEsbWorkflowChain(
            ChainConfigLoader chainConfigLoader,
            OpenCodeRunnerProperties runnerProperties,
            SmartEsbDailyTransactionPlanLoader planLoader,
            SmartEsbReviewPreparation preparation,
            SmartEsbPromptBuilder promptBuilder,
            SmartEsbSummaryValidator summaryValidator,
            OpenCodeServerManager serverManager,
            OpenCodeServerTaskRunner taskRunner,
            ScheduledProbeWaiter scheduledProbeWaiter,
            ObjectMapper objectMapper,
            AsyncTaskExecutor authorTaskExecutor
    ) {
        return new SmartEsbWorkflowChain(chainConfigLoader, runnerProperties, planLoader, preparation, promptBuilder, summaryValidator, serverManager, taskRunner, scheduledProbeWaiter, objectMapper, authorTaskExecutor);
    }

    @Bean
    WorkflowRunner workflowRunner(OpenCodeRunnerProperties properties, List<WorkflowChain> chains) {
        return new WorkflowRunner(properties, chains);
    }
}
