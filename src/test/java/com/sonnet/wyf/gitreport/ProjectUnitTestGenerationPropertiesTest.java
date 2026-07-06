package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectUnitTestGenerationPropertiesTest {
    @Test
    void loadsDefaultYamlWithFullScanAndPackageFilters() throws Exception {
        ProjectUnitTestGenerationProperties properties = new ChainConfigLoader(new DefaultResourceLoader())
                .load("classpath:chains", "project-unit-test-generation", ProjectUnitTestGenerationProperties.class);

        assertThat(properties.getProject().getId()).isEqualTo("example-project");
        assertThat(properties.getProject().getRepo()).hasToString("CHANGE_ME_PROJECT_REPO");
        assertThat(properties.getPaths().getOut()).hasToString("project-unit-tests/example-project");
        assertThat(properties.getDocs().getAgents()).hasToString("AGENTS.md");
        assertThat(properties.getDocs().getProjectMap()).hasToString("project-map.md");
        assertThat(properties.getDocs().getReconstructedDesign()).hasToString("重构项目详细设计文档.md");
        assertThat(properties.getSource().getPackagePaths()).isEmpty();
        assertThat(properties.getSource().getExclude()).contains("target/**", "build/**", "generated/**");
        assertThat(properties.getTest().getVerifyCommand()).containsExactly("./mvnw", "test");
        assertThat(properties.getTest().getCoverageThresholdPercent()).isEqualTo(80);
        assertThat(properties.getOpencode().getTimeoutMinutes()).isEqualTo(40);
    }
}
