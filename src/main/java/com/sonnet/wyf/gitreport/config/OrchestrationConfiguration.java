package com.sonnet.wyf.gitreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.console.WorkflowEventSink;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.ArtifactCompletenessValidator;
import com.sonnet.wyf.gitreport.orchestration.ConcurrentWorkflowTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.GitReportOrchestrator;
import com.sonnet.wyf.gitreport.orchestration.OutputCompletionGate;
import com.sonnet.wyf.gitreport.orchestration.RunStatusRepository;
import com.sonnet.wyf.gitreport.orchestration.SynthesisInputWriter;
import com.sonnet.wyf.gitreport.preparation.GitReportPreparation;
import com.sonnet.wyf.gitreport.prompt.PromptBuilder;
import com.sonnet.wyf.gitreport.scoring.QualityScoresWriter;
import com.sonnet.wyf.gitreport.validation.AuthorOutputValidator;
import com.sonnet.wyf.gitreport.validation.FinalReportValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;

@Configuration
public class OrchestrationConfiguration {
    @Bean
    AuthorOutputValidator authorOutputValidator(ObjectMapper objectMapper) {
        return new AuthorOutputValidator(objectMapper);
    }

    @Bean
    FinalReportValidator finalReportValidator() {
        return new FinalReportValidator();
    }

    @Bean
    RunStatusRepository runStatusRepository(ObjectMapper objectMapper) {
        return new RunStatusRepository(objectMapper);
    }

    @Bean
    SynthesisInputWriter synthesisInputWriter(ObjectMapper objectMapper) {
        return new SynthesisInputWriter(objectMapper);
    }

    @Bean
    OutputCompletionGate outputCompletionGate(ObjectMapper objectMapper) {
        return new OutputCompletionGate(objectMapper);
    }

    @Bean
    ArtifactCompletenessValidator artifactCompletenessValidator() {
        return new ArtifactCompletenessValidator();
    }

    @Bean
    ConcurrentWorkflowTaskRunner concurrentWorkflowTaskRunner(AsyncTaskExecutor authorTaskExecutor, WorkflowEventSink workflowEventSink) {
        return new ConcurrentWorkflowTaskRunner(authorTaskExecutor, workflowEventSink);
    }

    @Bean
    GitReportOrchestrator gitReportOrchestrator(
            GitReportPreparation preparation,
            ObjectMapper objectMapper,
            PromptBuilder promptBuilder,
            AgentBridgeTaskRunner taskRunner,
            AuthorOutputValidator outputValidator,
            FinalReportValidator finalReportValidator,
            QualityScoresWriter qualityScoresWriter,
            SynthesisInputWriter synthesisInputWriter,
            RunStatusRepository statusRepository,
            OutputCompletionGate outputCompletionGate,
            ConcurrentWorkflowTaskRunner concurrentWorkflowTaskRunner,
            ArtifactCompletenessValidator artifactCompletenessValidator
    ) {
        return new GitReportOrchestrator(
                preparation,
                objectMapper,
                promptBuilder,
                taskRunner,
                outputValidator,
                finalReportValidator,
                qualityScoresWriter,
                synthesisInputWriter,
                statusRepository,
                outputCompletionGate,
                concurrentWorkflowTaskRunner,
                artifactCompletenessValidator
        );
    }
}
