package com.sonnet.wyf.gitreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.scoring.QualityScoreCalculator;
import com.sonnet.wyf.gitreport.scoring.QualityScoresWriter;
import com.sonnet.wyf.gitreport.scoring.WorkloadScoreCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScoringConfiguration {
    @Bean
    WorkloadScoreCalculator workloadScoreCalculator() {
        return new WorkloadScoreCalculator();
    }

    @Bean
    QualityScoreCalculator qualityScoreCalculator() {
        return new QualityScoreCalculator();
    }

    @Bean
    QualityScoresWriter qualityScoresWriter(
            ObjectMapper objectMapper,
            QualityScoreCalculator qualityScoreCalculator,
            WorkloadScoreCalculator workloadScoreCalculator
    ) {
        return new QualityScoresWriter(objectMapper, qualityScoreCalculator, workloadScoreCalculator);
    }
}
