package com.sonnet.wyf.gitreport.workflow.unittest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.opencode.ValidationCheck;

import java.nio.file.Files;
import java.nio.file.Path;
public class ProjectUnitTestGenerationOutputValidator {
    private final ObjectMapper objectMapper;

    public ProjectUnitTestGenerationOutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationCheck validateBatchOutput(Path repo, String batchId, Path summaryJson) {
        try {
            if (!Files.exists(summaryJson)) {
                return ValidationCheck.failed("unit-test batch summary missing: " + summaryJson);
            }
            JsonNode summary = objectMapper.readTree(summaryJson.toFile());
            if (!batchId.equals(summary.path("batch_id").asText())) {
                return ValidationCheck.failed("unit-test batch_id mismatch: expected=" + batchId);
            }
            String status = summary.path("status").asText();
            if (!"completed".equals(status)) {
                return ValidationCheck.failed("unit-test batch not completed: status=" + status + ", summary=" + summaryJson);
            }
            if (!summary.path("source_files").isArray()) {
                return ValidationCheck.failed("unit-test batch summary missing source_files: " + summaryJson);
            }
            if (!summary.path("test_files").isArray()) {
                return ValidationCheck.failed("unit-test batch summary missing test_files: " + summaryJson);
            }
            if (!summary.path("notes").isArray()) {
                return ValidationCheck.failed("unit-test batch summary missing notes: " + summaryJson);
            }
            if (summary.path("test_files").isEmpty()) {
                return ValidationCheck.failed("completed unit-test batch must contain at least one test file: " + batchId);
            }
            Path repoRoot = repo.toAbsolutePath().normalize();
            Path testRoot = repoRoot.resolve("src/test").normalize();
            for (JsonNode testFileNode : summary.path("test_files")) {
                String testFile = normalize(testFileNode.asText());
                Path resolved = repoRoot.resolve(testFile).normalize();
                if (!resolved.startsWith(repoRoot)) {
                    return ValidationCheck.failed("unit-test output escapes repo: " + testFile);
                }
                if (!resolved.startsWith(testRoot)) {
                    return ValidationCheck.failed("unit-test output outside src/test: " + testFile);
                }
                if (!Files.exists(resolved)) {
                    return ValidationCheck.failed("unit-test file missing: " + resolved);
                }
                String content = Files.readString(resolved);
                if (content.isBlank()) {
                    return ValidationCheck.failed("unit-test file is blank: " + resolved);
                }
                if (content.contains("{{")) {
                    return ValidationCheck.failed("unit-test file contains unresolved placeholder: " + resolved);
                }
            }
            return ValidationCheck.success();
        } catch (Exception exception) {
            return ValidationCheck.failed("unit-test batch validation failed: " + exception.getMessage());
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/').replaceAll("^/+", "");
    }
}
