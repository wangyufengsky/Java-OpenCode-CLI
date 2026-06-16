package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbRewriteProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ChainConfigLoaderTest {
    @Test
    void loadsGitReportChainYamlFromClasspath() throws Exception {
        GitReportProperties properties = new ChainConfigLoader(new DefaultResourceLoader())
                .load("classpath:chains", "git-code-contribution-report", GitReportProperties.class);

        assertThat(properties.getProject().getId()).isEqualTo("upfs-production");
        assertThat(properties.getGit().getSince()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(properties.getGit().getExclude()).contains("target/**", "*.lock");
    }

    @Test
    void loadsSmartEsbChainYamlFromClasspath() throws Exception {
        SmartEsbRewriteProperties properties = new ChainConfigLoader(new DefaultResourceLoader())
                .load("classpath:chains", "smartesb-rewrite-code-review", SmartEsbRewriteProperties.class);

        assertThat(properties.getOut()).isEqualTo("D:/review-output/smartesb-rewrite-review");
        assertThat(properties.getTransactionPlanDir()).hasToString("src/main/resources/smartesb-transactions");
        assertThat(properties.getBatchSize()).isEqualTo(5);
    }
}
