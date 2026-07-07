package com.sonnet.wyf.gitreport.runner;

import com.sonnet.wyf.gitreport.GitReportProperties;

public final class AgentBridgeSettingsApplier {
    private AgentBridgeSettingsApplier() {
    }

    public static void apply(AgentBridgeSettings settings, GitReportProperties properties) {
        properties.getAgentbridge().setWebBaseUrl(settings.getWebBaseUrl());
        properties.getAgentbridge().setMcpUrl(settings.getMcpUrl());
        properties.getAgentbridge().setConcurrency(settings.getConcurrency());
        properties.getAgentbridge().setTimeoutMinutes(settings.getTimeoutMinutes());
        properties.getAgentbridge().setPollMillis(settings.getPollMillis());
        properties.getAgentbridge().setValidationSettleSeconds(settings.getValidationSettleSeconds());
        properties.getAgentbridge().setValidationMaxCorrections(settings.getValidationMaxCorrections());
        properties.getAgentbridge().setMaxConcurrency(settings.getMaxConcurrency());
    }
}
