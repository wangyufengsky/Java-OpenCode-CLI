package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyCodeReviewOutputValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyCodeReviewOutputValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final WeeklyCodeReviewOutputValidator validator = new WeeklyCodeReviewOutputValidator(objectMapper);

    @TempDir
    Path tempDir;

    @Test
    void acceptsCompleteBatchOutputWhenFindingsAndSnippetsStayInsideChangedRegions() throws Exception {
        Path output = writeOutput(validOutput());

        var validation = validator.validate(batch(), output);

        assertThat(validation.ok()).isTrue();
    }

    @Test
    void rejectsFindingOutsideChangedRegionLineRange() throws Exception {
        Map<String, Object> output = validOutput();
        ((Map<String, Object>) ((List<?>) output.get("findings")).get(0)).put("line_end", 99);

        var validation = validator.validate(batch(), writeOutput(output));

        assertThat(validation.ok()).isFalse();
        assertThat(validation.error()).contains("outside changed region");
    }

    @Test
    void rejectsFindingWithMismatchedAuthorCommitOrFileForRegion() throws Exception {
        Map<String, Object> output = validOutput();
        ((Map<String, Object>) ((List<?>) output.get("findings")).get(0)).put("author_key", "author-999-bob");

        var validation = validator.validate(batch(), writeOutput(output));

        assertThat(validation.ok()).isFalse();
        assertThat(validation.error()).contains("author_key mismatch");
    }

    @Test
    void rejectsSensitiveSnippetAndSnippetWithoutNegativeFinding() throws Exception {
        Map<String, Object> output = validOutput();
        ((Map<String, Object>) ((List<?>) output.get("code_snippets")).get(0)).put("snippet", "String token = \"abc\";");

        var validation = validator.validate(batch(), writeOutput(output));

        assertThat(validation.ok()).isFalse();
        assertThat(validation.error()).contains("unredacted sensitive");
    }

    @Test
    void rejectsFindingCountsThatDoNotMatchFindings() throws Exception {
        Map<String, Object> output = validOutput();
        ((Map<String, Object>) output.get("finding_counts")).put("P1", 0);

        var validation = validator.validate(batch(), writeOutput(output));

        assertThat(validation.ok()).isFalse();
        assertThat(validation.error()).contains("finding_counts.P1");
    }

    @Test
    void rejectsMissingBatchMarkdownReviewOutput() throws Exception {
        Path output = writeOutput(validOutput(), false);

        var validation = validator.validate(batch(), output);

        assertThat(validation.ok()).isFalse();
        assertThat(validation.error()).contains("code review markdown missing");
    }

    private Map<String, Object> batch() {
        return Map.of(
                "batch_id", "review-batch-001-src-main-java-foo-java",
                "review_md", tempDir.resolve("code-review.md").toString(),
                "changed_regions", List.of(Map.of(
                        "region_id", "region-00001",
                        "author_key", "author-001-alice",
                        "author", "Alice <alice@example.com>",
                        "commit", "abcdef1234567890",
                        "file", "src/main/java/Foo.java",
                        "line_start", 10,
                        "line_end", 12,
                        "hunk", "@@ -10 +10 @@\n+return value.trim();"
                ))
        );
    }

    private Map<String, Object> validOutput() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", "weekly-code-review-output/v1");
        root.put("batch_id", "review-batch-001-src-main-java-foo-java");
        root.put("status", "completed");
        root.put("summary", "发现一个可维护性问题。");
        root.put("reviewed_region_ids", List.of("region-00001"));
        root.put("finding_counts", new LinkedHashMap<>(Map.of("P0", 0, "P1", 1, "P2", 0)));
        root.put("findings", List.of(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("id", "F-001"),
                Map.entry("region_id", "region-00001"),
                Map.entry("author_key", "author-001-alice"),
                Map.entry("commit", "abcdef1234567890"),
                Map.entry("dimension", "maintainability"),
                Map.entry("polarity", "negative"),
                Map.entry("severity", "P1"),
                Map.entry("rule_id", "null-trim-branch"),
                Map.entry("file", "src/main/java/Foo.java"),
                Map.entry("line_start", 10),
                Map.entry("line_end", 12),
                Map.entry("evidence", "提交区域内直接调用 trim，需要确认 null 分支覆盖。"),
                Map.entry("reason", "可维护性风险。"),
                Map.entry("suggestion", "补齐单测并拆分解析逻辑。")
        ))));
        root.put("positive_signals", List.of());
        root.put("risk_signals", List.of());
        root.put("code_snippets", List.of(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("region_id", "region-00001"),
                Map.entry("file", "src/main/java/Foo.java"),
                Map.entry("line_start", 10),
                Map.entry("line_end", 12),
                Map.entry("dimension", "maintainability"),
                Map.entry("severity", "P1"),
                Map.entry("reason", "片段体现风险。"),
                Map.entry("suggestion", "补齐测试。"),
                Map.entry("snippet", "return value.trim();")
        ))));
        root.put("unverified", List.of());
        return root;
    }

    private Path writeOutput(Map<String, Object> output) throws Exception {
        return writeOutput(output, true);
    }

    private Path writeOutput(Map<String, Object> output, boolean writeMarkdown) throws Exception {
        if (writeMarkdown) {
            java.nio.file.Files.writeString(tempDir.resolve("code-review.md"), "# 批次代码审查\n\n已审查。\n");
        }
        Path path = tempDir.resolve("code-review-summary.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), output);
        return path;
    }
}
