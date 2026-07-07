package com.sonnet.wyf.gitreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.ArtifactCompletenessValidator;
import com.sonnet.wyf.gitreport.orchestration.ConcurrentWorkflowTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.GitReportOrchestrator;
import com.sonnet.wyf.gitreport.preparation.GitStatsCollector;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate;
import com.sonnet.wyf.gitreport.preparation.GitReportPreparation;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
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
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEngineeringReportWorkflowChain;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEvidenceBuilder;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEvidenceValidator;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyCodeReviewOutputValidator;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyCodeReviewRunner;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyOpenCodeReviewRunner;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyReportRenderer;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationAgentBridgeClient;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationBatchRunner;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationPreparation;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationPromptBuilder;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationReportRenderer;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationWorkflowChain;
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
    WeeklyEvidenceBuilder weeklyEvidenceBuilder(ObjectMapper objectMapper, GitStatsCollector gitStatsCollector) {
        return new WeeklyEvidenceBuilder(objectMapper, gitStatsCollector);
    }

    @Bean
    WeeklyEvidenceValidator weeklyEvidenceValidator(ObjectMapper objectMapper) {
        return new WeeklyEvidenceValidator(objectMapper);
    }

    @Bean
    WeeklyCodeReviewOutputValidator weeklyCodeReviewOutputValidator(ObjectMapper objectMapper) {
        return new WeeklyCodeReviewOutputValidator(objectMapper);
    }

    @Bean
    WeeklyCodeReviewRunner weeklyCodeReviewRunner(
            ObjectMapper objectMapper,
            OpenCodeServerManager serverManager,
            OpenCodeServerTaskRunner taskRunner,
            ConcurrentWorkflowTaskRunner concurrentTaskRunner,
            WeeklyCodeReviewOutputValidator outputValidator
    ) {
        return new WeeklyOpenCodeReviewRunner(objectMapper, serverManager, taskRunner, concurrentTaskRunner, outputValidator);
    }

    @Bean
    WeeklyReportRenderer weeklyReportRenderer(ObjectMapper objectMapper) {
        return new WeeklyReportRenderer(objectMapper);
    }

    @Bean
    ProjectUnitTestGenerationPreparation projectUnitTestGenerationPreparation(ObjectMapper objectMapper) {
        return new ProjectUnitTestGenerationPreparation(objectMapper);
    }

    @Bean
    ProjectUnitTestGenerationPromptBuilder projectUnitTestGenerationPromptBuilder(ResourceLoader resourceLoader) {
        return new ProjectUnitTestGenerationPromptBuilder(resourceLoader);
    }

    @Bean
    ProjectUnitTestGenerationReportRenderer projectUnitTestGenerationReportRenderer(ObjectMapper objectMapper) {
        return new ProjectUnitTestGenerationReportRenderer(objectMapper);
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
    WorkflowChain weeklyEngineeringReportWorkflowChain(
            ChainConfigLoader chainConfigLoader,
            OpenCodeRunnerProperties runnerProperties,
            WeeklyEvidenceBuilder evidenceBuilder,
            WeeklyEvidenceValidator evidenceValidator,
            WeeklyCodeReviewRunner codeReviewRunner,
            WeeklyReportRenderer reportRenderer
    ) {
        return new WeeklyEngineeringReportWorkflowChain(
                chainConfigLoader,
                runnerProperties,
                evidenceBuilder,
                evidenceValidator,
                codeReviewRunner,
                reportRenderer
        );
    }

    @Bean
    WorkflowChain projectUnitTestGenerationWorkflowChain(
            ChainConfigLoader chainConfigLoader,
            OpenCodeRunnerProperties runnerProperties,
            ProjectUnitTestGenerationPreparation preparation,
            ProjectUnitTestGenerationBatchRunner batchRunner,
            ProjectUnitTestGenerationReportRenderer reportRenderer,
            ObjectMapper objectMapper
    ) {
        return new ProjectUnitTestGenerationWorkflowChain(
                chainConfigLoader,
                runnerProperties,
                preparation,
                batchRunner,
                reportRenderer,
                objectMapper
        );
    }

    @Bean
    ProjectUnitTestGenerationAgentBridgeClient projectUnitTestGenerationAgentBridgeClient(ObjectMapper objectMapper) {
        return new ProjectUnitTestGenerationAgentBridgeClient(objectMapper);
    }

    @Bean
    ProjectUnitTestGenerationBatchRunner projectUnitTestGenerationBatchRunner(
            ProjectUnitTestGenerationAgentBridgeClient client,
            ProjectUnitTestGenerationPromptBuilder promptBuilder,
            ObjectMapper objectMapper
    ) {
        return new ProjectUnitTestGenerationBatchRunner(client, promptBuilder, objectMapper);
    }

}
