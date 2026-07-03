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
        return validateBatchResult(repo, batchId, summaryJson).check();
    }

    public BatchValidation validateBatchResult(Path repo, String batchId, Path summaryJson) {
        try {
            if (!Files.exists(summaryJson)) {
                return retriable("unit-test batch summary missing: " + summaryJson);
            }
            JsonNode summary = objectMapper.readTree(summaryJson.toFile());
            if (!batchId.equals(summary.path("batch_id").asText())) {
                return retriable("unit-test batch_id mismatch: expected=" + batchId);
            }
            String status = summary.path("status").asText();
            if (!summary.path("source_files").isArray()) {
                return retriable("unit-test batch summary missing source_files: " + summaryJson);
            }
            if (!summary.path("test_files").isArray()) {
                return retriable("unit-test batch summary missing test_files: " + summaryJson);
            }
            if (!summary.path("notes").isArray()) {
                return retriable("unit-test batch summary missing notes: " + summaryJson);
            }
            if ("completed".equals(status)) {
                if (summary.path("test_files").isEmpty()) {
                    return retriable("completed unit-test batch must contain at least one test file: " + batchId);
                }
                ValidationCheck files = validateTestFiles(repo, summary);
                return files.ok() ? completed(status) : retriable(files.error());
            }
            if ("partial".equals(status)) {
                if (summary.path("test_files").isEmpty()) {
                    return retriable("partial unit-test batch must contain at least one test file: " + batchId);
                }
                if (summary.path("notes").isEmpty()) {
                    return retriable("partial unit-test batch must explain remaining gaps in notes: " + batchId);
                }
                ValidationCheck files = validateTestFiles(repo, summary);
                return files.ok() ? terminal(status) : retriable(files.error());
            }
            if ("blocked".equals(status)) {
                if (summary.path("notes").isEmpty()) {
                    return retriable("blocked unit-test batch must explain blocker in notes: " + batchId);
                }
                return terminal(status);
            }
            return retriable("unit-test batch has unsupported status: status=" + status + ", summary=" + summaryJson);
        } catch (Exception exception) {
            return retriable("unit-test batch validation failed: " + exception.getMessage());
        }
    }

    private ValidationCheck validateTestFiles(Path repo, JsonNode summary) throws Exception {
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
    }

    private BatchValidation completed(String status) {
        return new BatchValidation(ValidationCheck.success(), false, true, status);
    }

    private BatchValidation terminal(String status) {
        return new BatchValidation(ValidationCheck.success(), false, false, status);
    }

    private BatchValidation retriable(String error) {
        return new BatchValidation(ValidationCheck.failed(error), true, false, "");
    }

    public record BatchValidation(ValidationCheck check, boolean retriable, boolean completed, String status) {
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/').replaceAll("^/+", "");
    }
}
