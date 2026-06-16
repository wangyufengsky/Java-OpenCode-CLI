package com.sonnet.wyf.gitreport.validation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.GitReportConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class AuthorOutputValidator {
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
}
