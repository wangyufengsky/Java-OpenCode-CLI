package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Arrays;
import java.util.stream.Collectors;

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
        assertThat(properties.getTest().getCoverageThresholdPercent()).isEqualTo(90);
        assertThat(properties.getTest().getJacocoVersion()).isEqualTo("0.8.15");
        assertThat(properties.getTest().getJacocoJvmArgProperty()).isEqualTo("sqlite.native.access.argument");
        assertThat(properties.getTest().getJacocoJvmArgBase()).isEqualTo("--enable-native-access=ALL-UNNAMED");
        assertThat(properties.getAgentbridge().getWebBaseUrl()).isEqualTo("https://127.0.0.1:9642");
        assertThat(properties.getAgentbridge().getMcpUrl()).isEqualTo("http://127.0.0.1:8642/mcp");
        assertThat(properties.getAgentbridge().getTimeoutMinutes()).isEqualTo(40);
        assertThat(properties.getAgentbridge().getMaxAttempts()).isEqualTo(5);
    }

    @Test
    void testPropertiesDoNotExposeDeadBatchControls() {
        assertThat(Arrays.stream(ProjectUnitTestGenerationProperties.Test.class.getMethods())
                .map(method -> method.getName())
                .collect(Collectors.toSet()))
                .doesNotContain(
                        "getVerifyCommand",
                        "setVerifyCommand",
                        "getConcurrency",
                        "setConcurrency",
                        "getMaxTypesPerTask",
                        "setMaxTypesPerTask",
                        "getMaxMethodsPerTask",
                        "setMaxMethodsPerTask",
                        "getMaxSourceCharsPerTask",
                        "setMaxSourceCharsPerTask"
                );
    }

    @Test
    void defaultJacocoJvmArgPropertyUsesStandardSurefireArgLine() {
        ProjectUnitTestGenerationProperties properties = new ProjectUnitTestGenerationProperties();

        assertThat(properties.getTest().getJacocoJvmArgProperty()).isEqualTo("argLine");
        assertThat(properties.getTest().getJacocoJvmArgBase()).isEmpty();
    }
}
