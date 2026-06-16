package com.sonnet.wyf.gitreport;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QualityScoreCalculatorTest {
    @Test
    void scoresFindingsWithCentralRulesAndClampsDimensions() {
        QualityScoreCalculator calculator = new QualityScoreCalculator();

        Map<String, Object> result = calculator.calculate(Map.of(
                "findings", List.of(
                        Map.of("dimension", "code_standard", "polarity", "negative", "severity", "high", "rule_id", "unsafe_format"),
                        Map.of("dimension", "risk_control", "polarity", "negative", "severity", "medium", "rule_id", "missing_boundary_check"),
                        Map.of("dimension", "maintainability", "polarity", "positive", "severity", "high", "rule_id", "clear_reuse_boundary")
                ),
                "code_snippets", List.of()
        ));

        assertThat(result.get("quality_adjustment_percent")).isEqualTo(-8.0);
        Map<?, ?> components = (Map<?, ?>) result.get("components_by_dimension");
        assertThat(components.get("code_standard")).isEqualTo(-8.0);
        assertThat(components.get("risk_control")).isEqualTo(-5.0);
        assertThat(components.get("maintainability")).isEqualTo(5.0);
        assertThat(components.get("reviewability")).isEqualTo(0.0);
    }

    @Test
    void lowQualitySnippetCreatesNegativeFallbackScore() {
        QualityScoreCalculator calculator = new QualityScoreCalculator();

        Map<String, Object> result = calculator.calculate(Map.of(
                "findings", List.of(),
                "code_snippets", List.of(Map.of(
                        "file", "src/Demo.java",
                        "dimension", "risk_control",
                        "severity", "medium",
                        "reason", "缺少边界保护"
                ))
        ));

        assertThat(result.get("quality_adjustment_percent")).isEqualTo(-5.0);
    }
}
