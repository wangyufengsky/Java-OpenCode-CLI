package com.sonnet.wyf.gitreport.workflow.smartesbreader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.agentbridge.ValidationCheck;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SmartEsbCodeReaderOutputValidator {
    private static final List<String> PLACEHOLDERS = List.of(
            "{{MODULE_ANALYSIS}}",
            "{{TRANSACTION_ANALYSIS}}",
            "{{INDEX_BODY}}"
    );

    private final ObjectMapper objectMapper;

    public SmartEsbCodeReaderOutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationCheck validateTaskOutput(String type, String name, Path markdown, Path summaryJson) {
        try {
            if (!Files.exists(markdown)) {
                return ValidationCheck.failed("analysis markdown missing: " + markdown);
            }
            String markdownContent = Files.readString(markdown);
            if (markdownContent.isBlank()) {
                return ValidationCheck.failed("analysis markdown is blank: " + markdown);
            }
            for (String placeholder : PLACEHOLDERS) {
                if (markdownContent.contains(placeholder)) {
                    return ValidationCheck.failed("analysis markdown still contains placeholder: " + placeholder);
                }
            }
            if (markdownContent.contains("{{")) {
                return ValidationCheck.failed("analysis markdown contains unresolved placeholder: " + markdown);
            }
            if (!Files.exists(summaryJson)) {
                return ValidationCheck.failed("summary json missing: " + summaryJson);
            }
            JsonNode summary = objectMapper.readTree(summaryJson.toFile());
            String idField = "module".equals(type) ? "serviceId" : "transaction_key";
            if (summary.path(idField).asText().isBlank()) {
                return ValidationCheck.failed("summary json missing " + idField + ": " + summaryJson);
            }
            if (!name.equals(summary.path(idField).asText())) {
                return ValidationCheck.failed("summary json " + idField + " mismatch: expected=" + name);
            }
            if (summary.path("risks_or_uncertainties").isMissingNode()) {
                return ValidationCheck.failed("summary json missing risks_or_uncertainties: " + summaryJson);
            }
            return ValidationCheck.success();
        } catch (Exception exception) {
            return ValidationCheck.failed("SmartESB code-reader output validation failed: " + exception.getMessage());
        }
    }

    public ValidationCheck validateIndex(Path indexMd) {
        try {
            if (!Files.exists(indexMd)) {
                return ValidationCheck.failed("index markdown missing: " + indexMd);
            }
            String content = Files.readString(indexMd);
            if (content.isBlank()) {
                return ValidationCheck.failed("index markdown is blank: " + indexMd);
            }
            if (content.contains("{{")) {
                return ValidationCheck.failed("index markdown contains unresolved placeholder: " + indexMd);
            }
            return ValidationCheck.success();
        } catch (Exception exception) {
            return ValidationCheck.failed("SmartESB code-reader index validation failed: " + exception.getMessage());
        }
    }
}
