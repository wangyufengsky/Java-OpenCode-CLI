package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.scoring.QualityScoreCalculator;
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
                        Map.of("dimension", "code_standard", "polarity", "negative", "severity", "high", "rule_id", "unsafe_format", "source", "scanner", "attribution", "owned_hunk", "owned_hunk_id", "h1"),
                        Map.of("dimension", "risk_control", "polarity", "negative", "severity", "medium", "rule_id", "missing_boundary_check", "source", "scanner", "attribution", "owned_hunk", "owned_hunk_id", "h2"),
                        Map.of("dimension", "reviewability", "polarity", "negative", "severity", "low", "rule_id", "minor_style", "source", "scanner", "attribution", "owned_hunk", "owned_hunk_id", "h3"),
                        Map.of("dimension", "maintainability", "polarity", "positive", "severity", "high", "rule_id", "clear_reuse_boundary")
                ),
                "code_snippets", List.of()
        ));

        assertThat(result.get("quality_adjustment_percent")).isEqualTo(2.0);
        Map<?, ?> components = (Map<?, ?>) result.get("components_by_dimension");
        assertThat(components.get("code_standard")).isEqualTo(-2.0);
        assertThat(components.get("risk_control")).isEqualTo(-1.0);
        assertThat(components.get("maintainability")).isEqualTo(5.0);
        assertThat(components.get("reviewability")).isEqualTo(0.0);
    }

    @Test
    void lowQualitySnippetWithoutAttributedFindingDoesNotCreateFallbackScore() {
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

        assertThat(result.get("quality_adjustment_percent")).isEqualTo(0.0);
    }

    @Test
    void duplicateNegativeScannerRuleOnlyDeductsOnce() {
        QualityScoreCalculator calculator = new QualityScoreCalculator();

        Map<String, Object> result = calculator.calculate(Map.of(
                "findings", List.of(
                        Map.of("dimension", "maintainability", "polarity", "negative", "severity", "medium", "rule_id", "DataClass", "source", "scanner", "attribution", "owned_hunk", "owned_hunk_id", "h1", "file", "A.java"),
                        Map.of("dimension", "maintainability", "polarity", "negative", "severity", "medium", "rule_id", "DataClass", "source", "scanner", "attribution", "owned_hunk", "owned_hunk_id", "h2", "file", "B.java"),
                        Map.of("dimension", "maintainability", "polarity", "negative", "severity", "medium", "rule_id", "DataClass", "source", "scanner", "attribution", "owned_hunk", "owned_hunk_id", "h3", "file", "C.java")
                )
        ));

        assertThat(result.get("quality_adjustment_percent")).isEqualTo(-1.0);
        assertThat((List<?>) result.get("scored_findings")).hasSize(1);
        assertThat(result.get("scoring_notes").toString()).contains("ignored duplicate negative scanner rule: DataClass");
    }
}
