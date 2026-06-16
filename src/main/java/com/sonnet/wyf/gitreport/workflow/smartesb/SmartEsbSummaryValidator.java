package com.sonnet.wyf.gitreport.workflow.smartesb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SmartEsbSummaryValidator {
    private static final List<String> REQUIRED = List.of(
            "transaction",
            "description",
            "status",
            "review_md",
            "matrix_md",
            "section_files",
            "code_standard_findings",
            "new_code_paths",
            "old_code_paths",
            "documents_checked",
            "finding_counts",
            "top_findings",
            "unverified"
    );
    private static final Set<String> STATUSES = Set.of("completed", "partial", "failed");

    private final ObjectMapper objectMapper;

    public SmartEsbSummaryValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Validation validate(Path summaryJson) {
        try {
            if (!Files.exists(summaryJson)) {
                return Validation.failed("summary missing: " + summaryJson);
            }
            String text = Files.readString(summaryJson);
            if (text.isBlank() || "{}".equals(text.trim())) {
                return Validation.failed("summary still empty: " + summaryJson);
            }
            Map<String, Object> root = objectMapper.readValue(text, new TypeReference<>() {});
            for (String field : REQUIRED) {
                if (!root.containsKey(field)) {
                    return Validation.failed("summary missing required field: " + field);
                }
            }
            Object status = root.get("status");
            if (!(status instanceof String value) || !STATUSES.contains(value)) {
                return Validation.failed("summary status must be one of completed, partial, failed");
            }
            Object counts = root.get("finding_counts");
            if (!(counts instanceof Map<?, ?> countMap)) {
                return Validation.failed("summary finding_counts must be object");
            }
            for (String severity : List.of("P0", "P1", "P2", "P3")) {
                Object count = countMap.get(severity);
                if (!(count instanceof Number number) || number.intValue() < 0) {
                    return Validation.failed("summary finding_counts." + severity + " must be non-negative integer");
                }
            }
            for (String arrayField : List.of("section_files", "code_standard_findings", "new_code_paths", "old_code_paths", "documents_checked", "top_findings", "unverified")) {
                if (!(root.get(arrayField) instanceof List<?>)) {
                    return Validation.failed("summary field must be array: " + arrayField);
                }
            }
            if ("failed".equals(status)) {
                return Validation.failed("summary status is failed: " + summaryJson);
            }
            return Validation.success();
        } catch (Exception exception) {
            return Validation.failed(exception.getMessage());
        }
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
