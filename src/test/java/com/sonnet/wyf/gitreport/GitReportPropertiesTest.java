package com.sonnet.wyf.gitreport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class GitReportPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void bindsGitReportYamlPropertiesAndDefaults() {
        contextRunner
                .withPropertyValues(
                        "git-report.project.id=upfs-production",
                        "git-report.project.name=UPFS Production",
                        "git-report.project.run-id=manual-run-001",
                        "git-report.paths.repo=D:/workspace/upfs-production",
                        "git-report.paths.out=D:/reports/git-code-contribution/2026-06-15",
                        "git-report.paths.opencode-bin=C:/Users/dev/AppData/Roaming/npm/opencode.cmd",
                        "git-report.git.since=2026-06-01",
                        "git-report.git.until=2026-06-15",
                        "git-report.git.exclude[0]=target/**",
                        "git-report.opencode.server-url=http://127.0.0.1:4097",
                        "git-report.opencode.manage-server=false",
                        "git-report.opencode.server-start-timeout-seconds=12",
                        "git-report.runtime.mode=synthesis-only"
                )
                .run(context -> {
                    GitReportProperties properties = context.getBean(GitReportProperties.class);

                    assertThat(properties.getProject().getId()).isEqualTo("upfs-production");
                    assertThat(properties.getProject().getName()).isEqualTo("UPFS Production");
                    assertThat(properties.getProject().getRunId()).isEqualTo("manual-run-001");
                    assertThat(properties.getPaths().getRepo()).isEqualTo(Path.of("D:/workspace/upfs-production"));
                    assertThat(properties.getPaths().getOut()).isEqualTo(Path.of("D:/reports/git-code-contribution/2026-06-15"));
                    assertThat(properties.getPaths().getOpencodeBin()).isEqualTo("C:/Users/dev/AppData/Roaming/npm/opencode.cmd");
                    assertThat(properties.getGit().getSince()).isEqualTo(LocalDate.of(2026, 6, 1));
                    assertThat(properties.getGit().getUntil()).isEqualTo(LocalDate.of(2026, 6, 15));
                    assertThat(properties.getGit().getRevision()).isEqualTo("HEAD");
                    assertThat(properties.getGit().getExclude()).containsExactly("target/**");
                    assertThat(properties.getOpencode().getServerUrl()).isEqualTo("http://127.0.0.1:4097");
                    assertThat(properties.getOpencode().isManageServer()).isFalse();
                    assertThat(properties.getOpencode().getServerStartTimeoutSeconds()).isEqualTo(12);
                    assertThat(properties.getOpencode().getConcurrency()).isEqualTo(6);
                    assertThat(properties.getOpencode().getTimeoutMinutes()).isEqualTo(40);
                    assertThat(properties.getOpencode().getMaxRetries()).isEqualTo(1);
                    assertThat(properties.getOpencode().getMaxConcurrency()).isEqualTo(6);
                    assertThat(properties.getRuntime().getMode()).isEqualTo("synthesis-only");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GitReportProperties.class)
    static class Config {
    }
}
