package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeRunnerPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void bindsRunnerControlPropertiesAndSharedOpenCodeSettings() {
        contextRunner
                .withPropertyValues(
                        "opencode-runner.enabled=true",
                        "opencode-runner.active-chain=smartesb-rewrite-code-review",
                        "opencode-runner.mode=rerun",
                        "opencode-runner.rerun.type=transaction",
                        "opencode-runner.rerun.id=CaRolloutRepeal",
                        "opencode-runner.run-date=2026-06-16",
                        "opencode-runner.config-dir=classpath:chains",
                        "opencode-runner.opencode.server-url=http://127.0.0.1:4097",
                        "opencode-runner.opencode.manage-server=false",
                        "opencode-runner.opencode.opencode-bin=C:/Users/dev/AppData/Roaming/npm/opencode.cmd",
                        "opencode-runner.opencode.request-timeout-seconds=90",
                        "opencode-runner.opencode.concurrency=3",
                        "opencode-runner.opencode.max-concurrency=5",
                        "opencode-runner.opencode.timeout-minutes=25",
                        "opencode-runner.opencode.output-wait-seconds=9"
                )
                .run(context -> {
                    OpenCodeRunnerProperties properties = context.getBean(OpenCodeRunnerProperties.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getActiveChain()).isEqualTo("smartesb-rewrite-code-review");
                    assertThat(properties.getMode()).isEqualTo("rerun");
                    assertThat(properties.getRerun().getType()).isEqualTo("transaction");
                    assertThat(properties.getRerun().getId()).isEqualTo("CaRolloutRepeal");
                    assertThat(properties.getRunDate()).isEqualTo(LocalDate.of(2026, 6, 16));
                    assertThat(properties.getConfigDir()).isEqualTo("classpath:chains");
                    assertThat(properties.getOpencode().getServerUrl()).isEqualTo("http://127.0.0.1:4097");
                    assertThat(properties.getOpencode().isManageServer()).isFalse();
                    assertThat(properties.getOpencode().getOpencodeBin()).isEqualTo("C:/Users/dev/AppData/Roaming/npm/opencode.cmd");
                    assertThat(properties.getOpencode().getRequestTimeoutSeconds()).isEqualTo(90);
                    assertThat(properties.getOpencode().getConcurrency()).isEqualTo(3);
                    assertThat(properties.getOpencode().getMaxConcurrency()).isEqualTo(5);
                    assertThat(properties.getOpencode().getTimeoutMinutes()).isEqualTo(25);
                    assertThat(properties.getOpencode().getOutputWaitSeconds()).isEqualTo(9);
                });
    }

    @Test
    void keepsMainConfigurationFreeOfLegacyGitReportDetails() throws Exception {
        String application = java.nio.file.Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(application).contains("opencode-runner:");
        assertThat(application).doesNotContain("git-report:");
        assertThat(application).doesNotContain("smartesb:");
        assertThat(application).doesNotContain("D:/workspace/upfs-production");
        assertThat(application).doesNotContain("D:\\upfs");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OpenCodeRunnerProperties.class)
    static class Config {
    }
}
