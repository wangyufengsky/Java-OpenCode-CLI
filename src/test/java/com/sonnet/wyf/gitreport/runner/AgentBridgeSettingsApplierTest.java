package com.sonnet.wyf.gitreport.runner;

import com.sonnet.wyf.gitreport.GitReportProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentBridgeSettingsApplierTest {
    @Test
    void copiesSharedRuntimeSettingsAndKeepsChainMessages() {
        AgentBridgeSettings settings = new AgentBridgeSettings();
        settings.setWebBaseUrl("https://127.0.0.1:7777");
        settings.setMcpUrl("http://127.0.0.1:7778/mcp");
        settings.setConcurrency(3);
        settings.setTimeoutMinutes(14);
        settings.setPollMillis(1000);
        settings.setValidationSettleSeconds(15);
        settings.setValidationMaxCorrections(4);
        settings.setMaxConcurrency(6);
        settings.setTaskMessage("task msg");
        settings.setSynthesisTaskMessage("synthesis msg");
        GitReportProperties properties = new GitReportProperties();
        properties.getAgentbridge().setTaskMessage("chain task msg");
        properties.getAgentbridge().setSynthesisTaskMessage("chain synthesis msg");

        AgentBridgeSettingsApplier.apply(settings, properties);

        assertThat(properties.getAgentbridge().getWebBaseUrl()).isEqualTo("https://127.0.0.1:7777");
        assertThat(properties.getAgentbridge().getMcpUrl()).isEqualTo("http://127.0.0.1:7778/mcp");
        assertThat(properties.getAgentbridge().getConcurrency()).isEqualTo(3);
        assertThat(properties.getAgentbridge().getTimeoutMinutes()).isEqualTo(14);
        assertThat(properties.getAgentbridge().getPollMillis()).isEqualTo(1000);
        assertThat(properties.getAgentbridge().getValidationSettleSeconds()).isEqualTo(15);
        assertThat(properties.getAgentbridge().getValidationMaxCorrections()).isEqualTo(4);
        assertThat(properties.getAgentbridge().getMaxConcurrency()).isEqualTo(6);
        assertThat(properties.getAgentbridge().getTaskMessage()).isEqualTo("chain task msg");
        assertThat(properties.getAgentbridge().getSynthesisTaskMessage()).isEqualTo("chain synthesis msg");
    }
}
