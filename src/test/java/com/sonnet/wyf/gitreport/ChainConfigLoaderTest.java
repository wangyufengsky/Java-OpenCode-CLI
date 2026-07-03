package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbRewriteProperties;
import com.sonnet.wyf.gitreport.workflow.smartesbreader.SmartEsbCodeReaderProperties;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationProperties;
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

        assertThat(properties.getOut()).isEqualTo("/home/wangyufeng/review-output/smartesb-rewrite-review");
        assertThat(properties.getTransactionPlanDir()).hasToString("src/main/resources/smartesb-transactions");
        assertThat(properties.getOld8583Doc()).isEqualTo("/home/wangyufeng/upfs-nl-json/doc/docment/old-8583.md");
    }

    @Test
    void loadsSmartEsbCodeReaderChainYamlFromClasspath() throws Exception {
        SmartEsbCodeReaderProperties properties = new ChainConfigLoader(new DefaultResourceLoader())
                .load("classpath:chains", "smartesb-code-reader", SmartEsbCodeReaderProperties.class);

        assertThat(properties.getOut()).isEqualTo("/home/wangyufeng/review-output/smartesb-code-reader");
        assertThat(properties.getMode()).isEqualTo("8583");
        assertThat(properties.getServiceIdentify()).isNotEmpty();
        assertThat(properties.getXmlRoot()).hasToString("/home/wangyufeng/upfs-production");
        assertThat(properties.getWorkerMessage()).contains("SmartESB code-reader");
    }

    @Test
    void loadsProjectUnitTestGenerationYamlFromClasspath() throws Exception {
        ProjectUnitTestGenerationProperties properties = new ChainConfigLoader(new DefaultResourceLoader())
                .load("classpath:chains", "project-unit-test-generation", ProjectUnitTestGenerationProperties.class);

        assertThat(properties.getProject().getId()).isEqualTo("upfs-production");
        assertThat(properties.getSource().getPackagePaths()).isEmpty();
        assertThat(properties.getTest().getVerifyCommand()).containsExactly("./mvnw", "test");
    }
}
