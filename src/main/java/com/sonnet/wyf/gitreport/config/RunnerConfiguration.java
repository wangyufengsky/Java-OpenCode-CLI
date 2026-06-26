package com.sonnet.wyf.gitreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.ArtifactCompletenessValidator;
import com.sonnet.wyf.gitreport.orchestration.ConcurrentWorkflowTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.GitReportOrchestrator;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate;
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
import com.sonnet.wyf.gitreport.workflow.smartesbreader.SmartEsbCodeReaderOutputValidator;
import com.sonnet.wyf.gitreport.workflow.smartesbreader.SmartEsbCodeReaderPreparation;
import com.sonnet.wyf.gitreport.workflow.smartesbreader.SmartEsbCodeReaderPromptBuilder;
import com.sonnet.wyf.gitreport.workflow.smartesbreader.SmartEsbCodeReaderWorkflowChain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    SmartEsbCodeReaderPreparation smartEsbCodeReaderPreparation(ObjectMapper objectMapper) {
        return new SmartEsbCodeReaderPreparation(objectMapper);
    }

    @Bean
    SmartEsbCodeReaderPromptBuilder smartEsbCodeReaderPromptBuilder(ResourceLoader resourceLoader) {
        return new SmartEsbCodeReaderPromptBuilder(resourceLoader);
    }

    @Bean
    SmartEsbCodeReaderOutputValidator smartEsbCodeReaderOutputValidator(ObjectMapper objectMapper) {
        return new SmartEsbCodeReaderOutputValidator(objectMapper);
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
            ObjectMapper objectMapper,
            OutputCompletionGate outputCompletionGate,
            ConcurrentWorkflowTaskRunner concurrentWorkflowTaskRunner,
            ArtifactCompletenessValidator artifactCompletenessValidator
    ) {
        return new SmartEsbWorkflowChain(
                chainConfigLoader,
                runnerProperties,
                planLoader,
                preparation,
                promptBuilder,
                summaryValidator,
                serverManager,
                taskRunner,
                objectMapper,
                outputCompletionGate,
                concurrentWorkflowTaskRunner,
                artifactCompletenessValidator
        );
    }

    @Bean
    WorkflowChain smartEsbCodeReaderWorkflowChain(
            ChainConfigLoader chainConfigLoader,
            OpenCodeRunnerProperties runnerProperties,
            SmartEsbCodeReaderPreparation preparation,
            SmartEsbCodeReaderPromptBuilder promptBuilder,
            SmartEsbCodeReaderOutputValidator outputValidator,
            OpenCodeServerManager serverManager,
            OpenCodeServerTaskRunner taskRunner,
            ObjectMapper objectMapper,
            OutputCompletionGate outputCompletionGate,
            ConcurrentWorkflowTaskRunner concurrentWorkflowTaskRunner
    ) {
        return new SmartEsbCodeReaderWorkflowChain(
                chainConfigLoader,
                runnerProperties,
                preparation,
                promptBuilder,
                outputValidator,
                serverManager,
                taskRunner,
                objectMapper,
                outputCompletionGate,
                concurrentWorkflowTaskRunner
        );
    }

    @Bean
    WorkflowRunner workflowRunner(OpenCodeRunnerProperties properties, List<WorkflowChain> chains) {
        return new WorkflowRunner(properties, chains);
    }
}
