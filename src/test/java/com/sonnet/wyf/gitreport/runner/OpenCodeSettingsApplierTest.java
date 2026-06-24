package com.sonnet.wyf.gitreport.runner;

import com.sonnet.wyf.gitreport.GitReportProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeSettingsApplierTest {
    @Test
    void copiesSharedOpenCodeSettingsIntoGitReportProperties() {
        OpenCodeSettings settings = new OpenCodeSettings();
        settings.setServerUrl("http://127.0.0.1:7777");
        settings.setManageServer(false);
        settings.setServerStartTimeoutSeconds(11);
        settings.setCreateSessionTimeoutSeconds(12);
        settings.setRequestTimeoutSeconds(13);
        settings.setConcurrency(3);
        settings.setTimeoutMinutes(14);
        settings.setOutputWaitSeconds(15);
        settings.setValidationMaxCorrections(4);
        settings.setMaxRetries(5);
        settings.setMaxConcurrency(6);
        settings.setSessionModel("anthropic/claude-test");
        settings.setEnvironment(Map.of("A", "B"));
        settings.setOpencodeBin("/opt/opencode");
        GitReportProperties properties = new GitReportProperties();

        OpenCodeSettingsApplier.apply(settings, properties);

        assertThat(properties.getOpencode().getServerUrl()).isEqualTo("http://127.0.0.1:7777");
        assertThat(properties.getOpencode().isManageServer()).isFalse();
        assertThat(properties.getOpencode().getServerStartTimeoutSeconds()).isEqualTo(11);
        assertThat(properties.getOpencode().getCreateSessionTimeoutSeconds()).isEqualTo(12);
        assertThat(properties.getOpencode().getRequestTimeoutSeconds()).isEqualTo(13);
        assertThat(properties.getOpencode().getConcurrency()).isEqualTo(3);
        assertThat(properties.getOpencode().getTimeoutMinutes()).isEqualTo(14);
        assertThat(properties.getOpencode().getOutputWaitSeconds()).isEqualTo(15);
        assertThat(properties.getOpencode().getValidationMaxCorrections()).isEqualTo(4);
        assertThat(properties.getOpencode().getMaxRetries()).isEqualTo(5);
        assertThat(properties.getOpencode().getMaxConcurrency()).isEqualTo(6);
        assertThat(properties.getOpencode().getSessionModel()).isEqualTo("anthropic/claude-test");
        assertThat(properties.getOpencode().getEnvironment()).containsEntry("A", "B");
        assertThat(properties.getPaths().getOpencodeBin()).isEqualTo("/opt/opencode");
    }
}
