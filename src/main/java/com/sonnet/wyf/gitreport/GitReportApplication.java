package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Locale;

@SpringBootApplication
@EnableConfigurationProperties(GitReportProperties.class)
public class GitReportApplication {
    public static void main(String[] args) {
        SpringApplication.run(GitReportApplication.class, args);
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    GitReportPreparation gitReportPreparation(ObjectMapper objectMapper) {
        return new GitReportPreparation(
                new GitStatsCollector(new CommandExecutor(), new CommentLineCounter()),
                new ReportPreparationWriter(objectMapper)
        );
    }

    @Bean
    GitReportOrchestrator gitReportOrchestrator(GitReportPreparation preparation, ObjectMapper objectMapper) {
        return new GitReportOrchestrator(
                preparation,
                objectMapper,
                new PromptBuilder(),
                new OpenCodeCommandBuilder(),
                new OpenCodeProcessRunner(),
                new AuthorOutputValidator(objectMapper),
                new QualityScoresWriter(objectMapper, new QualityScoreCalculator(), new WorkloadScoreCalculator()),
                new RunStatusRepository(objectMapper)
        );
    }

    @Bean
    ApplicationRunner gitReportRunner(GitReportProperties properties, GitReportOrchestrator orchestrator) {
        return new GitReportRunner(properties, orchestrator);
    }

    static class GitReportRunner implements ApplicationRunner {
        private static final Logger log = LoggerFactory.getLogger(GitReportRunner.class);

        private final GitReportProperties properties;
        private final GitReportOrchestrator orchestrator;

        GitReportRunner(GitReportProperties properties, GitReportOrchestrator orchestrator) {
            this.properties = properties;
            this.orchestrator = orchestrator;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            if (properties.isEnabled()) {
                String mode = properties.getRuntime().getMode() == null ? "full" : properties.getRuntime().getMode().toLowerCase(Locale.ROOT);
                if ("full".equals(mode)) {
                    orchestrator.run(properties);
                } else if ("synthesis-only".equals(mode)) {
                    orchestrator.runSynthesisOnly(properties);
                } else {
                    throw new IllegalArgumentException("git-report.runtime.mode must be one of: full, synthesis-only");
                }
                return;
            }
            log.info("git-report.enabled=false, skipped git report orchestration. Enable it in application.yml, or run with --spring.profiles.active=example / --git-report.enabled=true.");
        }
    }
}
