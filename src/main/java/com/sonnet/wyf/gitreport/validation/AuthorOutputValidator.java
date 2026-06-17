package com.sonnet.wyf.gitreport.validation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.GitReportConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class AuthorOutputValidator {
    private static final Set<String> DIMENSIONS = Set.of("code_standard", "maintainability", "risk_control", "reviewability");
    private static final Set<String> POLARITIES = Set.of("positive", "negative");
    private static final Set<String> SEVERITIES = Set.of("low", "medium", "high");
    private static final Pattern SENSITIVE_SNIPPET_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential|密钥|令牌|密码)"
    );

    private final ObjectMapper objectMapper;

    public AuthorOutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthorValidationResult validate(Path reportMd, Path qualitySummaryJson) {
        try {
            if (!Files.exists(reportMd)) {
                return AuthorValidationResult.failed("person report missing: " + reportMd);
            }
            String report = Files.readString(reportMd);
            if (report.isBlank()) {
                return AuthorValidationResult.failed("person report still contains marker: " + reportMd);
            }
            if (!Files.exists(qualitySummaryJson)) {
                return AuthorValidationResult.failed("quality summary missing: " + qualitySummaryJson);
            }
            String quality = Files.readString(qualitySummaryJson);
            if (quality.isBlank() || quality.trim().equals(GitReportConstants.QUALITY_SUMMARY_MARKER) || quality.contains(GitReportConstants.QUALITY_SUMMARY_MARKER)) {
                return AuthorValidationResult.failed("quality summary still contains marker: " + qualitySummaryJson);
            }
            Map<String, Object> root = objectMapper.readValue(quality, new TypeReference<>() {});
            for (String key : List.of("findings", "positive_signals", "risk_signals", "code_snippets", "unverified")) {
                if (!(root.get(key) instanceof List<?>)) {
                    return AuthorValidationResult.failed("quality summary field must be array: " + key);
                }
            }
            if (!root.containsKey("summary")) {
                return AuthorValidationResult.failed("quality summary missing summary");
            }
            if (root.containsKey("quality_adjustment_percent")) {
                return AuthorValidationResult.failed("quality summary must not contain quality_adjustment_percent");
            }
            if (root.get("components") instanceof List<?> components) {
                for (Object component : components) {
                    if (component instanceof Map<?, ?> map && map.containsKey("score")) {
                        return AuthorValidationResult.failed("quality summary must not contain components[].score");
                    }
                }
            }
            AuthorValidationResult rootValidation = validateRootRequiredFields(root);
            if (!rootValidation.ok()) {
                return rootValidation;
            }
            AuthorValidationResult findingsValidation = validateFindings(listOfMaps(root.get("findings")));
            if (!findingsValidation.ok()) {
                return findingsValidation;
            }
            AuthorValidationResult snippetsValidation = validateSnippets(listOfMaps(root.get("code_snippets")), listOfMaps(root.get("findings")));
            if (!snippetsValidation.ok()) {
                return snippetsValidation;
            }
            if (report.contains(GitReportConstants.AUTHOR_REPORT_MARKER)) {
                if (!removeTrailingAuthorMarker(reportMd, report)) {
                    return AuthorValidationResult.failed("person report still contains marker: " + reportMd);
                }
            }
            return AuthorValidationResult.success();
        } catch (Exception exception) {
            return AuthorValidationResult.failed(exception.getMessage());
        }
    }

    private boolean removeTrailingAuthorMarker(Path reportMd, String report) throws IOException {
        int markerAt = report.indexOf(GitReportConstants.AUTHOR_REPORT_MARKER);
        int lastMarkerAt = report.lastIndexOf(GitReportConstants.AUTHOR_REPORT_MARKER);
        if (markerAt != lastMarkerAt) {
            return false;
        }
        String beforeMarker = report.substring(0, markerAt);
        String afterMarker = report.substring(markerAt + GitReportConstants.AUTHOR_REPORT_MARKER.length());
        if (beforeMarker.isBlank() || !afterMarker.isBlank()) {
            return false;
        }
        Files.writeString(reportMd, beforeMarker.stripTrailing() + "\n");
        return true;
    }

    private AuthorValidationResult validateRootRequiredFields(Map<String, Object> root) {
        for (String field : List.of("author", "status", "summary")) {
            if (blankString(root.get(field))) {
                return AuthorValidationResult.failed("quality summary missing or blank field: " + field);
            }
        }
        return AuthorValidationResult.success();
    }

    private AuthorValidationResult validateFindings(List<Map<String, Object>> findings) {
        for (int index = 0; index < findings.size(); index++) {
            Map<String, Object> finding = findings.get(index);
            AuthorValidationResult enums = validateEnums("findings[" + index + "]", finding, true);
            if (!enums.ok()) {
                return enums;
            }
            for (String field : List.of("id", "rule_id", "file", "evidence", "reason", "suggestion")) {
                if (blankString(finding.get(field))) {
                    return AuthorValidationResult.failed("quality summary findings[" + index + "] missing or blank field: " + field);
                }
            }
            for (String field : List.of("line_start", "line_end")) {
                if (!(finding.get(field) instanceof Number)) {
                    return AuthorValidationResult.failed("quality summary findings[" + index + "] missing numeric field: " + field);
                }
            }
        }
        return AuthorValidationResult.success();
    }

    private AuthorValidationResult validateSnippets(List<Map<String, Object>> snippets, List<Map<String, Object>> findings) {
        for (int index = 0; index < snippets.size(); index++) {
            Map<String, Object> snippet = snippets.get(index);
            AuthorValidationResult enums = validateEnums("code_snippets[" + index + "]", snippet, false);
            if (!enums.ok()) {
                return enums;
            }
            for (String field : List.of("file", "reason", "suggestion", "snippet")) {
                if (blankString(snippet.get(field))) {
                    return AuthorValidationResult.failed("quality summary code_snippets[" + index + "] missing or blank field: " + field);
                }
            }
            for (String field : List.of("line_start", "line_end")) {
                if (!(snippet.get(field) instanceof Number)) {
                    return AuthorValidationResult.failed("quality summary code_snippets[" + index + "] missing numeric field: " + field);
                }
            }
            String snippetText = snippet.get("snippet").toString();
            if (!snippetText.contains("[REDACTED]") && SENSITIVE_SNIPPET_PATTERN.matcher(snippetText).find()) {
                return AuthorValidationResult.failed("quality summary code_snippets[" + index + "] contains unredacted sensitive content");
            }
            if (!hasRelatedNegativeFinding(snippet, findings)) {
                return AuthorValidationResult.failed("quality summary code_snippets[" + index + "] has no related negative finding");
            }
        }
        return AuthorValidationResult.success();
    }

    private AuthorValidationResult validateEnums(String path, Map<String, Object> value, boolean requirePolarity) {
        if (!DIMENSIONS.contains(string(value.get("dimension")))) {
            return AuthorValidationResult.failed("quality summary " + path + " invalid dimension: " + string(value.get("dimension")));
        }
        if (requirePolarity && !POLARITIES.contains(string(value.get("polarity")))) {
            return AuthorValidationResult.failed("quality summary " + path + " invalid polarity: " + string(value.get("polarity")));
        }
        if (!SEVERITIES.contains(string(value.get("severity")))) {
            return AuthorValidationResult.failed("quality summary " + path + " invalid severity: " + string(value.get("severity")));
        }
        return AuthorValidationResult.success();
    }

    private boolean hasRelatedNegativeFinding(Map<String, Object> snippet, List<Map<String, Object>> findings) {
        String snippetFile = string(snippet.get("file"));
        String snippetDimension = string(snippet.get("dimension"));
        for (Map<String, Object> finding : findings) {
            if (!"negative".equals(string(finding.get("polarity")))) {
                continue;
            }
            boolean sameFile = !snippetFile.isBlank() && snippetFile.equals(string(finding.get("file")));
            boolean sameDimension = !snippetDimension.isBlank() && snippetDimension.equals(string(finding.get("dimension")));
            if (sameFile || sameDimension) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private boolean blankString(Object value) {
        return string(value).isBlank();
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
