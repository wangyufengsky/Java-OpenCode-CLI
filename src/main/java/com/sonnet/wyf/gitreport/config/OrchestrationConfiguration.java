package com.sonnet.wyf.gitreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.GitReportOrchestrator;
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
    GitReportOrchestrator gitReportOrchestrator(
            GitReportPreparation preparation,
            ObjectMapper objectMapper,
            PromptBuilder promptBuilder,
            OpenCodeServerManager serverManager,
            OpenCodeServerTaskRunner taskRunner,
            AuthorOutputValidator outputValidator,
            FinalReportValidator finalReportValidator,
            QualityScoresWriter qualityScoresWriter,
            SynthesisInputWriter synthesisInputWriter,
            RunStatusRepository statusRepository,
            ScheduledProbeWaiter outputWaiter,
            AsyncTaskExecutor authorTaskExecutor
    ) {
        return new GitReportOrchestrator(
                preparation,
                objectMapper,
                promptBuilder,
                serverManager,
                taskRunner,
                outputValidator,
                finalReportValidator,
                qualityScoresWriter,
                synthesisInputWriter,
                statusRepository,
                outputWaiter,
                authorTaskExecutor
        );
    }
}
