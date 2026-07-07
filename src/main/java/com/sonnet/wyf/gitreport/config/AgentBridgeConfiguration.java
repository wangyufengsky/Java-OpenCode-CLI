package com.sonnet.wyf.gitreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeTaskRunner;
import com.sonnet.wyf.gitreport.console.WorkflowEventSink;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentBridgeConfiguration {
    @Bean
    AgentBridgeClient agentBridgeClient(ObjectMapper objectMapper) {
        return new AgentBridgeClient(objectMapper);
    }

    @Bean
    AgentBridgeTaskRunner agentBridgeTaskRunner(
            AgentBridgeClient agentBridgeClient,
            ScheduledProbeWaiter scheduledProbeWaiter,
            WorkflowEventSink workflowEventSink,
            ObjectMapper objectMapper
    ) {
        return new AgentBridgeTaskRunner(agentBridgeClient, scheduledProbeWaiter, workflowEventSink, objectMapper);
    }
}
