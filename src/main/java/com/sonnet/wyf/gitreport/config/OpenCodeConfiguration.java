package com.sonnet.wyf.gitreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerClient;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenCodeConfiguration {
    @Bean
    OpenCodeServerClient openCodeServerClient(ObjectMapper objectMapper) {
        return new OpenCodeServerClient(objectMapper);
    }

    @Bean(destroyMethod = "shutdown")
    OpenCodeServerManager openCodeServerManager(OpenCodeServerClient openCodeServerClient, ScheduledProbeWaiter scheduledProbeWaiter) {
        return new OpenCodeServerManager(openCodeServerClient, scheduledProbeWaiter);
    }

    @Bean
    OpenCodeServerTaskRunner openCodeServerTaskRunner(OpenCodeServerClient openCodeServerClient, ScheduledProbeWaiter scheduledProbeWaiter) {
        return new OpenCodeServerTaskRunner(openCodeServerClient, scheduledProbeWaiter);
    }
}
