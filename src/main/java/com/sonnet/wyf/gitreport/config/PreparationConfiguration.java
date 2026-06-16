package com.sonnet.wyf.gitreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.preparation.CommandExecutor;
import com.sonnet.wyf.gitreport.preparation.CommentLineCounter;
import com.sonnet.wyf.gitreport.preparation.GitReportPreparation;
import com.sonnet.wyf.gitreport.preparation.GitStatsCollector;
import com.sonnet.wyf.gitreport.preparation.ReportPreparationWriter;
import com.sonnet.wyf.gitreport.scoring.WorkloadScoreCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PreparationConfiguration {
    @Bean
    CommandExecutor commandExecutor() {
        return new CommandExecutor();
    }

    @Bean
    CommentLineCounter commentLineCounter() {
        return new CommentLineCounter();
    }

    @Bean
    GitStatsCollector gitStatsCollector(
            CommandExecutor commandExecutor,
            CommentLineCounter commentLineCounter,
            WorkloadScoreCalculator workloadScoreCalculator,
            ObjectMapper objectMapper
    ) {
        return new GitStatsCollector(commandExecutor, commentLineCounter, workloadScoreCalculator, objectMapper);
    }

    @Bean
    ReportPreparationWriter reportPreparationWriter(ObjectMapper objectMapper) {
        return new ReportPreparationWriter(objectMapper);
    }

    @Bean
    GitReportPreparation gitReportPreparation(GitStatsCollector gitStatsCollector, ReportPreparationWriter reportPreparationWriter) {
        return new GitReportPreparation(gitStatsCollector, reportPreparationWriter);
    }
}
