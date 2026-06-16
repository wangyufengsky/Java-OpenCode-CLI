package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerClient;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.GitReportOrchestrator;
import com.sonnet.wyf.gitreport.orchestration.RunStatusRepository;
import com.sonnet.wyf.gitreport.preparation.CommandExecutor;
import com.sonnet.wyf.gitreport.preparation.CommentLineCounter;
import com.sonnet.wyf.gitreport.preparation.GitReportPreparation;
import com.sonnet.wyf.gitreport.preparation.GitStatsCollector;
import com.sonnet.wyf.gitreport.preparation.ReportPreparationWriter;
import com.sonnet.wyf.gitreport.prompt.PromptBuilder;
import com.sonnet.wyf.gitreport.scoring.QualityScoreCalculator;
import com.sonnet.wyf.gitreport.scoring.QualityScoresWriter;
import com.sonnet.wyf.gitreport.scoring.WorkloadScoreCalculator;
import com.sonnet.wyf.gitreport.validation.AuthorOutputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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
    OpenCodeServerClient openCodeServerClient(ObjectMapper objectMapper) {
        return new OpenCodeServerClient(objectMapper);
    }

    @Bean(destroyMethod = "shutdown")
    OpenCodeServerManager openCodeServerManager(OpenCodeServerClient openCodeServerClient) {
        return new OpenCodeServerManager(openCodeServerClient);
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler openCodeTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("opencode-session-poll-");
        scheduler.setPoolSize(4);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    OpenCodeServerTaskRunner openCodeServerTaskRunner(OpenCodeServerClient openCodeServerClient, TaskScheduler openCodeTaskScheduler) {
        return new OpenCodeServerTaskRunner(openCodeServerClient, openCodeTaskScheduler);
    }

    @Bean
    GitReportOrchestrator gitReportOrchestrator(
            GitReportPreparation preparation,
            ObjectMapper objectMapper,
            OpenCodeServerManager serverManager,
            OpenCodeServerTaskRunner taskRunner
    ) {
        return new GitReportOrchestrator(
                preparation,
                objectMapper,
                new PromptBuilder(),
                serverManager,
                taskRunner,
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
