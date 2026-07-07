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
                        "git-report.git.since=2026-06-01",
                        "git-report.git.until=2026-06-15",
                        "git-report.git.exclude[0]=target/**",
                        "git-report.agentbridge.web-base-url=https://127.0.0.1:9642",
                        "git-report.agentbridge.mcp-url=http://127.0.0.1:8642/mcp",
                        "git-report.agentbridge.validation-settle-seconds=12",
                        "git-report.agentbridge.validation-max-corrections=3",
                        "git-report.detail-input.top-files=7",
                        "git-report.detail-input.commits=12",
                        "git-report.synthesis-input.person-report-excerpt-chars=8192",
                        "git-report.synthesis-input.snippets-per-author=5",
                        "git-report.synthesis-input.snippets-total=30",
                        "git-report.synthesis-input.snippet-lines=20"
                )
                .run(context -> {
                    GitReportProperties properties = context.getBean(GitReportProperties.class);

                    assertThat(properties.getProject().getId()).isEqualTo("upfs-production");
                    assertThat(properties.getProject().getName()).isEqualTo("UPFS Production");
                    assertThat(properties.getProject().getRunId()).isEqualTo("manual-run-001");
                    assertThat(properties.getPaths().getRepo()).isEqualTo(Path.of("D:/workspace/upfs-production"));
                    assertThat(properties.getPaths().getOut()).isEqualTo(Path.of("D:/reports/git-code-contribution/2026-06-15"));
                    assertThat(properties.getGit().getSince()).isEqualTo(LocalDate.of(2026, 6, 1));
                    assertThat(properties.getGit().getUntil()).isEqualTo(LocalDate.of(2026, 6, 15));
                    assertThat(properties.getGit().getRevision()).isEqualTo("HEAD");
                    assertThat(properties.getGit().getExclude()).containsExactly("target/**");
                    assertThat(properties.getAgentbridge().getWebBaseUrl()).isEqualTo("https://127.0.0.1:9642");
                    assertThat(properties.getAgentbridge().getMcpUrl()).isEqualTo("http://127.0.0.1:8642/mcp");
                    assertThat(properties.getAgentbridge().getValidationSettleSeconds()).isEqualTo(12);
                    assertThat(properties.getAgentbridge().getValidationMaxCorrections()).isEqualTo(3);
                    assertThat(properties.getAgentbridge().getConcurrency()).isEqualTo(1);
                    assertThat(properties.getAgentbridge().getTimeoutMinutes()).isEqualTo(40);
                    assertThat(properties.getAgentbridge().getMaxConcurrency()).isEqualTo(1);
                    assertThat(properties.getDetailInput().getTopFiles()).isEqualTo(7);
                    assertThat(properties.getDetailInput().getCommits()).isEqualTo(12);
                    assertThat(properties.getDetailInput().getChangedRegions()).isEqualTo(40);
                    assertThat(properties.getDetailInput().getChangedRegionLines()).isEqualTo(24);
                    assertThat(properties.getSynthesisInput().getPersonReportExcerptChars()).isEqualTo(8192);
                    assertThat(properties.getSynthesisInput().getSnippetsPerAuthor()).isEqualTo(5);
                    assertThat(properties.getSynthesisInput().getSnippetsTotal()).isEqualTo(30);
                    assertThat(properties.getSynthesisInput().getSnippetLines()).isEqualTo(20);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GitReportProperties.class)
    static class Config {
    }
}
