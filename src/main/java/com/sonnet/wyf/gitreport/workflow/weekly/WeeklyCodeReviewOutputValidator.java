package com.sonnet.wyf.gitreport.workflow.weekly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class WeeklyCodeReviewOutputValidator {
    public static final String SCHEMA_VERSION = "weekly-code-review-output/v1";
    private static final Set<String> STATUSES = Set.of("completed", "partial", "failed");
    private static final Set<String> DIMENSIONS = Set.of("code_standard", "maintainability", "risk_control", "reviewability");
    private static final Set<String> POLARITIES = Set.of("positive", "negative");
    private static final Set<String> SEVERITIES = Set.of("P0", "P1", "P2");
    private static final Pattern SENSITIVE_SNIPPET_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential|密钥|令牌|密码)"
    );

    private final ObjectMapper objectMapper;

    public WeeklyCodeReviewOutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Validation validate(Map<String, Object> batch, Path summaryJson) {
        try {
            Validation markdownValidation = validateMarkdown(batch);
            if (!markdownValidation.ok()) {
                return markdownValidation;
            }
            if (!Files.exists(summaryJson)) {
                return Validation.failed("code review summary missing: " + summaryJson);
            }
            Map<String, Object> root = objectMapper.readValue(summaryJson.toFile(), new TypeReference<>() {});
            Validation rootValidation = validateRoot(batch, root);
            if (!rootValidation.ok()) {
                return rootValidation;
            }
            Map<String, Map<String, Object>> regions = regionsById(batch);
            Validation reviewed = validateReviewedRegions(regions, listValue(root.get("reviewed_region_ids")));
            if (!reviewed.ok()) {
                return reviewed;
            }
            List<Map<String, Object>> findings = listOfMaps(root.get("findings"));
            for (int index = 0; index < findings.size(); index++) {
                Validation validation = validateFinding(regions, findings.get(index), index);
                if (!validation.ok()) {
                    return validation;
                }
            }
            List<Map<String, Object>> snippets = listOfMaps(root.get("code_snippets"));
            for (int index = 0; index < snippets.size(); index++) {
                Validation validation = validateSnippet(regions, findings, snippets.get(index), index);
                if (!validation.ok()) {
                    return validation;
                }
            }
            return validateFindingCounts(mapValue(root.get("finding_counts")), findings);
        } catch (Exception exception) {
            return Validation.failed(exception.getMessage());
        }
    }

    private Validation validateMarkdown(Map<String, Object> batch) throws Exception {
        String reviewPath = string(batch.get("review_md"));
        if (reviewPath.isBlank()) {
            return Validation.failed("code review markdown path missing in batch");
        }
        Path reviewMd = Path.of(reviewPath);
        if (!Files.exists(reviewMd)) {
            return Validation.failed("code review markdown missing: " + reviewMd);
        }
        String markdown = Files.readString(reviewMd);
        if (markdown.isBlank()) {
            return Validation.failed("code review markdown empty: " + reviewMd);
        }
        if (markdown.contains("{{") || markdown.contains("__")) {
            return Validation.failed("code review markdown contains unresolved placeholder: " + reviewMd);
        }
        return Validation.success();
    }

    private Validation validateRoot(Map<String, Object> batch, Map<String, Object> root) {
        for (String field : List.of("schema_version", "batch_id", "status", "summary", "reviewed_region_ids", "finding_counts", "findings", "positive_signals", "risk_signals", "code_snippets", "unverified")) {
            if (!root.containsKey(field)) {
                return Validation.failed("code review summary missing required field: " + field);
            }
        }
        if (!SCHEMA_VERSION.equals(string(root.get("schema_version")))) {
            return Validation.failed("code review summary schema_version mismatch: " + root.get("schema_version"));
        }
        if (!string(batch.get("batch_id")).equals(string(root.get("batch_id")))) {
            return Validation.failed("code review summary batch_id mismatch");
        }
        String status = string(root.get("status"));
        if (!STATUSES.contains(status)) {
            return Validation.failed("code review summary status must be one of completed, partial, failed");
        }
        if ("failed".equals(status)) {
            return Validation.failed("code review summary status is failed");
        }
        if (string(root.get("summary")).isBlank()) {
            return Validation.failed("code review summary missing or blank field: summary");
        }
        for (String field : List.of("reviewed_region_ids", "findings", "positive_signals", "risk_signals", "code_snippets", "unverified")) {
            if (!(root.get(field) instanceof List<?>)) {
                return Validation.failed("code review summary field must be array: " + field);
            }
        }
        if (!(root.get("finding_counts") instanceof Map<?, ?>)) {
            return Validation.failed("code review summary finding_counts must be object");
        }
        return Validation.success();
    }

    private Validation validateReviewedRegions(Map<String, Map<String, Object>> regions, List<?> reviewed) {
        if (reviewed.isEmpty()) {
            return Validation.failed("code review summary reviewed_region_ids must not be empty");
        }
        for (Object value : reviewed) {
            String regionId = string(value);
            if (!regions.containsKey(regionId)) {
                return Validation.failed("code review summary reviewed_region_ids contains unknown region_id: " + regionId);
            }
        }
        if (reviewed.size() != regions.size()) {
            return Validation.failed("code review summary reviewed_region_ids must include every input region for completed output");
        }
        return Validation.success();
    }

    private Validation validateFinding(Map<String, Map<String, Object>> regions, Map<String, Object> finding, int index) {
        for (String field : List.of("id", "region_id", "author_key", "commit", "dimension", "polarity", "severity", "rule_id", "file", "evidence", "reason", "suggestion")) {
            if (string(finding.get(field)).isBlank()) {
                return Validation.failed("code review summary findings[" + index + "] missing or blank field: " + field);
            }
        }
        Validation enums = validateEnums("findings[" + index + "]", finding, true);
        if (!enums.ok()) {
            return enums;
        }
        return validateRegionBoundedItem("findings[" + index + "]", regions, finding, true);
    }

    private Validation validateSnippet(Map<String, Map<String, Object>> regions, List<Map<String, Object>> findings, Map<String, Object> snippet, int index) {
        for (String field : List.of("region_id", "file", "dimension", "severity", "reason", "suggestion", "snippet")) {
            if (string(snippet.get(field)).isBlank()) {
                return Validation.failed("code review summary code_snippets[" + index + "] missing or blank field: " + field);
            }
        }
        Validation enums = validateEnums("code_snippets[" + index + "]", snippet, false);
        if (!enums.ok()) {
            return enums;
        }
        String snippetText = string(snippet.get("snippet"));
        if (!snippetText.contains("[REDACTED]") && SENSITIVE_SNIPPET_PATTERN.matcher(snippetText).find()) {
            return Validation.failed("code review summary code_snippets[" + index + "] contains unredacted sensitive content");
        }
        Validation bounded = validateRegionBoundedItem("code_snippets[" + index + "]", regions, snippet, false);
        if (!bounded.ok()) {
            return bounded;
        }
        if (!hasRelatedNegativeFinding(snippet, findings)) {
            return Validation.failed("code review summary code_snippets[" + index + "] has no related negative finding");
        }
        return Validation.success();
    }

    private Validation validateRegionBoundedItem(String path, Map<String, Map<String, Object>> regions, Map<String, Object> item, boolean requireAttribution) {
        String regionId = string(item.get("region_id"));
        Map<String, Object> region = regions.get(regionId);
        if (region == null) {
            return Validation.failed("code review summary " + path + " references unknown region_id: " + regionId);
        }
        if (requireAttribution && !string(region.get("author_key")).equals(string(item.get("author_key")))) {
            return Validation.failed("code review summary " + path + " author_key mismatch for region_id: " + regionId);
        }
        if (requireAttribution && !string(region.get("commit")).equals(string(item.get("commit")))) {
            return Validation.failed("code review summary " + path + " commit mismatch for region_id: " + regionId);
        }
        if (!string(region.get("file")).equals(string(item.get("file")))) {
            return Validation.failed("code review summary " + path + " file mismatch for region_id: " + regionId);
        }
        if (!(item.get("line_start") instanceof Number start) || !(item.get("line_end") instanceof Number end)) {
            return Validation.failed("code review summary " + path + " missing numeric line range");
        }
        int lineStart = start.intValue();
        int lineEnd = end.intValue();
        int regionStart = number(region.get("line_start"));
        int regionEnd = number(region.get("line_end"));
        if (lineStart <= 0 || lineEnd <= 0 || lineStart > lineEnd) {
            return Validation.failed("code review summary " + path + " invalid line range");
        }
        if (lineStart < regionStart || lineEnd > regionEnd) {
            return Validation.failed("code review summary " + path + " outside changed region line range");
        }
        return Validation.success();
    }

    private Validation validateEnums(String path, Map<String, Object> value, boolean requirePolarity) {
        if (!DIMENSIONS.contains(string(value.get("dimension")))) {
            return Validation.failed("code review summary " + path + " invalid dimension: " + string(value.get("dimension")));
        }
        if (requirePolarity && !POLARITIES.contains(string(value.get("polarity")))) {
            return Validation.failed("code review summary " + path + " invalid polarity: " + string(value.get("polarity")));
        }
        if (!SEVERITIES.contains(string(value.get("severity")))) {
            return Validation.failed("code review summary " + path + " invalid severity: " + string(value.get("severity")));
        }
        return Validation.success();
    }

    private Validation validateFindingCounts(Map<String, Object> counts, List<Map<String, Object>> findings) {
        Map<String, Integer> actual = new LinkedHashMap<>(Map.of("P0", 0, "P1", 0, "P2", 0));
        for (Map<String, Object> finding : findings) {
            String severity = string(finding.get("severity"));
            actual.put(severity, actual.getOrDefault(severity, 0) + 1);
        }
        for (String severity : List.of("P0", "P1", "P2")) {
            if (!(counts.get(severity) instanceof Number number) || number.intValue() != actual.get(severity)) {
                return Validation.failed("code review summary finding_counts." + severity + " must equal actual findings count");
            }
        }
        return Validation.success();
    }

    private boolean hasRelatedNegativeFinding(Map<String, Object> snippet, List<Map<String, Object>> findings) {
        String regionId = string(snippet.get("region_id"));
        String file = string(snippet.get("file"));
        String dimension = string(snippet.get("dimension"));
        for (Map<String, Object> finding : findings) {
            if (!"negative".equals(string(finding.get("polarity")))) {
                continue;
            }
            if (regionId.equals(string(finding.get("region_id")))) {
                return true;
            }
            if (file.equals(string(finding.get("file"))) && dimension.equals(string(finding.get("dimension")))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Map<String, Object>> regionsById(Map<String, Object> batch) {
        Map<String, Map<String, Object>> regions = new LinkedHashMap<>();
        for (Map<String, Object> region : listOfMaps(batch.get("changed_regions"))) {
            regions.put(string(region.get("region_id")), region);
        }
        return regions;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    public record Validation(boolean ok, String error) {
        public static Validation success() {
            return new Validation(true, "");
        }

        public static Validation failed(String error) {
            return new Validation(false, error == null ? "" : error);
        }
    }
}
