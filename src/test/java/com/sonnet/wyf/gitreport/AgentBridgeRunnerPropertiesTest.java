package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AgentBridgeRunnerPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void bindsRunnerControlPropertiesAndSharedAgentBridgeSettings() {
        contextRunner
                .withPropertyValues(
                        "agentbridge-runner.enabled=true",
                        "agentbridge-runner.active-chain=smartesb-rewrite-code-review",
                        "agentbridge-runner.mode=rerun",
                        "agentbridge-runner.rerun.type=transaction",
                        "agentbridge-runner.rerun.id=CaRolloutRepeal",
                        "agentbridge-runner.run-date=2026-06-16",
                        "agentbridge-runner.config-dir=classpath:chains",
                        "agentbridge-runner.agentbridge.web-base-url=https://127.0.0.1:9642",
                        "agentbridge-runner.agentbridge.mcp-url=http://127.0.0.1:8642/mcp",
                        "agentbridge-runner.agentbridge.concurrency=3",
                        "agentbridge-runner.agentbridge.max-concurrency=5",
                        "agentbridge-runner.agentbridge.timeout-minutes=25",
                        "agentbridge-runner.agentbridge.poll-millis=1000",
                        "agentbridge-runner.agentbridge.validation-settle-seconds=9",
                        "agentbridge-runner.agentbridge.validation-max-corrections=4",
                        "agentbridge-runner.agentbridge.task-message=task msg",
                        "agentbridge-runner.agentbridge.synthesis-task-message=synthesis msg"
                )
                .run(context -> {
                    AgentBridgeRunnerProperties properties = context.getBean(AgentBridgeRunnerProperties.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getActiveChain()).isEqualTo("smartesb-rewrite-code-review");
                    assertThat(properties.getMode()).isEqualTo("rerun");
                    assertThat(properties.getRerun().getType()).isEqualTo("transaction");
                    assertThat(properties.getRerun().getId()).isEqualTo("CaRolloutRepeal");
                    assertThat(properties.getRunDate()).isEqualTo(LocalDate.of(2026, 6, 16));
                    assertThat(properties.getConfigDir()).isEqualTo("classpath:chains");
                    assertThat(properties.getAgentbridge().getWebBaseUrl()).isEqualTo("https://127.0.0.1:9642");
                    assertThat(properties.getAgentbridge().getMcpUrl()).isEqualTo("http://127.0.0.1:8642/mcp");
                    assertThat(properties.getAgentbridge().getConcurrency()).isEqualTo(3);
                    assertThat(properties.getAgentbridge().getMaxConcurrency()).isEqualTo(5);
                    assertThat(properties.getAgentbridge().getTimeoutMinutes()).isEqualTo(25);
                    assertThat(properties.getAgentbridge().getPollMillis()).isEqualTo(1000);
                    assertThat(properties.getAgentbridge().getValidationSettleSeconds()).isEqualTo(9);
                    assertThat(properties.getAgentbridge().getValidationMaxCorrections()).isEqualTo(4);
                    assertThat(properties.getAgentbridge().getTaskMessage()).isEqualTo("task msg");
                    assertThat(properties.getAgentbridge().getSynthesisTaskMessage()).isEqualTo("synthesis msg");
                });
    }

    @Test
    void keepsMainConfigurationFreeOfLegacyGitReportDetails() throws Exception {
        String application = java.nio.file.Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(application).contains("agentbridge-runner:");
        assertThat(application).doesNotContain("git-report:");
        assertThat(application).doesNotContain("smartesb:");
        assertThat(application).doesNotContain("D:/workspace/upfs-production");
        assertThat(application).doesNotContain("D:\\upfs");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentBridgeRunnerProperties.class)
    static class Config {
    }
}
