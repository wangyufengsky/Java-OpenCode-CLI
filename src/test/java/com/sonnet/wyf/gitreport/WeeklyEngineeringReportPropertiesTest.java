package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEngineeringReportProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyEngineeringReportPropertiesTest {
    @Test
    void usesConfiguredStartdayAndEnddayForIrregularPeriod() {
        WeeklyEngineeringReportProperties properties = new WeeklyEngineeringReportProperties();
        properties.setStartday(LocalDate.of(2026, 6, 25));
        properties.setEndday(LocalDate.of(2026, 7, 9));

        assertThat(properties.effectiveWeekStart(LocalDate.of(2026, 6, 26))).isEqualTo(LocalDate.of(2026, 6, 25));
        assertThat(properties.effectiveWeekEnd(LocalDate.of(2026, 6, 26))).isEqualTo(LocalDate.of(2026, 7, 9));
        assertThat(properties.effectiveWeekLabel(LocalDate.of(2026, 6, 26))).isEqualTo("2026-06-25_to_2026-07-09");
    }

    @Test
    void derivesNaturalWeekWhenNoExplicitPeriodIsConfigured() {
        WeeklyEngineeringReportProperties properties = new WeeklyEngineeringReportProperties();

        assertThat(properties.effectiveWeekStart(LocalDate.of(2026, 6, 26))).isEqualTo(LocalDate.of(2026, 6, 22));
        assertThat(properties.effectiveWeekEnd(LocalDate.of(2026, 6, 26))).isEqualTo(LocalDate.of(2026, 6, 28));
        assertThat(properties.effectiveWeekLabel(LocalDate.of(2026, 6, 26))).isEqualTo("2026-W26");
    }

    @Test
    void requiresStartdayAndEnddayTogether() {
        WeeklyEngineeringReportProperties properties = new WeeklyEngineeringReportProperties();
        properties.setStartday(LocalDate.of(2026, 6, 25));

        assertThatThrownBy(() -> properties.effectiveWeekEnd(LocalDate.of(2026, 6, 26)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startday and endday must be configured together");
    }

    @Test
    void loadsWeeklyEngineeringReportYamlFromClasspath() throws Exception {
        WeeklyEngineeringReportProperties properties = new ChainConfigLoader(new DefaultResourceLoader())
                .load("classpath:chains", "weekly-engineering-report", WeeklyEngineeringReportProperties.class);

        assertThat(properties.getProject().getId()).isEqualTo("upfs-production");
        assertThat(properties.getProject().getName()).isEqualTo("UPFS Production");
        assertThat(properties.getPaths().getOut()).hasToString("/home/wangyufeng/reports/weekly-engineering/2026-W26");
        assertThat(properties.getStartday()).isEqualTo(LocalDate.of(2026, 6, 19));
        assertThat(properties.getEndday()).isEqualTo(LocalDate.of(2026, 6, 26));
        assertThat(properties.getGit().isIncludeMerges()).isFalse();
        assertThat(properties.getGit().getExclude()).contains("target/**", "*.lock");
        assertThat(properties.getReview().getMaxRegionsPerBatch()).isEqualTo(8);
        assertThat(properties.getReview().getMaxHunkLines()).isEqualTo(24);
        assertThat(properties.getReview().getConcurrency()).isEqualTo(3);
        assertThat(properties.getOpencode().getTimeoutMinutes()).isEqualTo(40);
    }
}
