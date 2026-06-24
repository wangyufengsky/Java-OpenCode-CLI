package com.sonnet.wyf.gitreport.runner;

import com.sonnet.wyf.gitreport.GitReportProperties;

public final class OpenCodeSettingsApplier {
    private OpenCodeSettingsApplier() {
    }

    public static void apply(OpenCodeSettings settings, GitReportProperties properties) {
        properties.getOpencode().setServerUrl(settings.getServerUrl());
        properties.getOpencode().setManageServer(settings.isManageServer());
        properties.getOpencode().setServerStartTimeoutSeconds(settings.getServerStartTimeoutSeconds());
        properties.getOpencode().setCreateSessionTimeoutSeconds(settings.getCreateSessionTimeoutSeconds());
        properties.getOpencode().setRequestTimeoutSeconds(settings.getRequestTimeoutSeconds());
        properties.getOpencode().setConcurrency(settings.getConcurrency());
        properties.getOpencode().setTimeoutMinutes(settings.getTimeoutMinutes());
        properties.getOpencode().setOutputWaitSeconds(settings.getOutputWaitSeconds());
        properties.getOpencode().setValidationMaxCorrections(settings.getValidationMaxCorrections());
        properties.getOpencode().setMaxRetries(settings.getMaxRetries());
        properties.getOpencode().setMaxConcurrency(settings.getMaxConcurrency());
        properties.getOpencode().setSessionModel(settings.getSessionModel());
        properties.getOpencode().setEnvironment(settings.getEnvironment());
        properties.getPaths().setOpencodeBin(settings.getOpencodeBin());
    }
}
