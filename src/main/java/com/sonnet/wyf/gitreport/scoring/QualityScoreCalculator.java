package com.sonnet.wyf.gitreport.scoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class QualityScoreCalculator {
    private static final Map<String, Double> DIMENSION_LIMITS = Map.of("code_standard", 8.0, "maintainability", 8.0, "risk_control", 8.0, "reviewability", 6.0);
    private static final Map<String, Map<String, Double>> SCORE_TABLE = Map.of(
            "negative", Map.of("low", -2.0, "medium", -5.0, "high", -8.0),
            "positive", Map.of("low", 1.0, "medium", 3.0, "high", 5.0)
    );

    public Map<String, Object> calculate(Map<String, Object> qualitySummary) {
        Map<String, Double> componentsByDimension = new LinkedHashMap<>();
        DIMENSION_LIMITS.keySet().forEach(dimension -> componentsByDimension.put(dimension, 0.0));
        List<Map<String, Object>> scoredFindings = new ArrayList<>();
        List<String> scoringNotes = new ArrayList<>();
        for (Object item : listValue(qualitySummary.get("findings"))) {
            if (item instanceof Map<?, ?> finding) {
                scoreFinding(castMap(finding), componentsByDimension, scoredFindings, scoringNotes);
            }
        }
        for (Object item : listValue(qualitySummary.get("code_snippets"))) {
            if (item instanceof Map<?, ?> snippet && !hasNegativeFindingForSnippet(castMap(snippet), scoredFindings)) {
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("dimension", snippet.get("dimension"));
                fallback.put("polarity", "negative");
                fallback.put("severity", Objects.toString(snippet.get("severity"), "medium"));
                fallback.put("rule_id", "low_quality_code_snippet");
                fallback.put("file", snippet.get("file"));
                fallback.put("line_start", snippet.get("line_start"));
                fallback.put("line_end", snippet.get("line_end"));
                fallback.put("evidence", Objects.toString(snippet.get("reason"), "低质量代码片段"));
                scoreFinding(fallback, componentsByDimension, scoredFindings, scoringNotes);
                scoringNotes.add("code_snippets item scored through low_quality_code_snippet fallback");
            }
        }
        double qualityAdjustment = componentsByDimension.values().stream().mapToDouble(Double::doubleValue).sum();
        qualityAdjustment = Math.max(-30.0, Math.min(30.0, qualityAdjustment));
        List<Map<String, Object>> components = componentsByDimension.entrySet().stream()
                .map(entry -> Map.<String, Object>of("dimension", entry.getKey(), "score", entry.getValue()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quality_adjustment_percent", qualityAdjustment);
        result.put("components", components);
        result.put("components_by_dimension", componentsByDimension);
        result.put("scored_findings", scoredFindings);
        result.put("scoring_notes", scoringNotes);
        return result;
    }

    private void scoreFinding(Map<String, Object> finding, Map<String, Double> componentsByDimension, List<Map<String, Object>> scoredFindings, List<String> scoringNotes) {
        String dimension = Objects.toString(finding.get("dimension"), "");
        String polarity = Objects.toString(finding.get("polarity"), "").toLowerCase(Locale.ROOT);
        String severity = Objects.toString(finding.get("severity"), "").toLowerCase(Locale.ROOT);
        if (!DIMENSION_LIMITS.containsKey(dimension) || !SCORE_TABLE.containsKey(polarity) || !SCORE_TABLE.get(polarity).containsKey(severity)) {
            scoringNotes.add("ignored invalid finding");
            return;
        }
        double next = componentsByDimension.get(dimension) + SCORE_TABLE.get(polarity).get(severity);
        double limit = DIMENSION_LIMITS.get(dimension);
        componentsByDimension.put(dimension, Math.max(-limit, Math.min(limit, next)));
        Map<String, Object> scored = new LinkedHashMap<>();
        scored.put("dimension", dimension);
        scored.put("polarity", polarity);
        scored.put("severity", severity);
        scored.put("score", SCORE_TABLE.get(polarity).get(severity));
        scored.put("rule_id", Objects.toString(finding.get("rule_id"), ""));
        scored.put("file", Objects.toString(finding.get("file"), ""));
        scoredFindings.add(scored);
    }

    private boolean hasNegativeFindingForSnippet(Map<String, Object> snippet, List<Map<String, Object>> scoredFindings) {
        String dimension = Objects.toString(snippet.get("dimension"), "");
        String file = Objects.toString(snippet.get("file"), "");
        return scoredFindings.stream().anyMatch(finding -> "negative".equals(finding.get("polarity"))
                && dimension.equals(finding.get("dimension"))
                && (file.isBlank() || file.equals(finding.get("file"))));
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
