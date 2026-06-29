package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEngineeringReportProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyEngineeringReportPropertiesTest {
    @Test
    void derivesNaturalWeekByDefault() {
        WeeklyEngineeringReportProperties properties = new WeeklyEngineeringReportProperties();

        assertThat(properties.effectiveWeekStart(LocalDate.of(2026, 6, 26))).isEqualTo(LocalDate.of(2026, 6, 22));
        assertThat(properties.effectiveWeekEnd(LocalDate.of(2026, 6, 26))).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(properties.effectiveWeekLabel(LocalDate.of(2026, 6, 26))).isEqualTo("2026-W26");
    }

    @Test
    void loadsWeeklyEngineeringReportYamlFromClasspath() throws Exception {
        WeeklyEngineeringReportProperties properties = new ChainConfigLoader(new DefaultResourceLoader())
                .load("classpath:chains", "weekly-engineering-report", WeeklyEngineeringReportProperties.class);

        assertThat(properties.getProject().getId()).isEqualTo("upfs-production");
        assertThat(properties.getProject().getName()).isEqualTo("UPFS Production");
        assertThat(properties.getPaths().getOut()).hasToString("/home/wangyufeng/reports/weekly-engineering/2026-W26");
        assertThat(properties.getGit().isIncludeMerges()).isFalse();
        assertThat(properties.getGit().getExclude()).contains("target/**", "*.lock");
        assertThat(properties.getDetailInput().getTopFiles()).isEqualTo(10);
        assertThat(properties.getDetailInput().getChangedRegionLines()).isEqualTo(24);
    }
}
