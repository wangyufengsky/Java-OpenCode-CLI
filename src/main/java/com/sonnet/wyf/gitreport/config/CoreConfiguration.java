package com.sonnet.wyf.gitreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class CoreConfiguration {
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler agentBridgeTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("agentbridge-task-poll-");
        scheduler.setPoolSize(4);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    ScheduledProbeWaiter scheduledProbeWaiter(TaskScheduler agentBridgeTaskScheduler) {
        return new ScheduledProbeWaiter(agentBridgeTaskScheduler);
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor authorTaskExecutor(AgentBridgeRunnerProperties properties) {
        int concurrency = Math.max(1, Math.min(
                properties.getAgentbridge().getConcurrency(),
                properties.getAgentbridge().getMaxConcurrency()
        ));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("git-report-author-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.initialize();
        return executor;
    }
}
