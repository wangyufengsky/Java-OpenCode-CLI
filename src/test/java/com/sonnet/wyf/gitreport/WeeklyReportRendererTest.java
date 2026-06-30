package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyReportRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyReportRendererTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void rendersCodePeopleAndFinalReportsOnlyAfterBatchReviewOutputsAreComplete() throws Exception {
        Path evidencePath = tempDir.resolve("weekly-evidence.json");
        Map<String, Object> evidence = evidence();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(evidencePath.toFile(), evidence);
        writeBatchSummary();

        new WeeklyReportRenderer(objectMapper).render(evidencePath);

        assertThat(tempDir.resolve("code-review/overview.md")).content().contains("代码维度审查总览", "src/Foo.java");
        assertThat(tempDir.resolve("code-review/p0-p1-p2-issues.md")).content().contains("P0/P1/P2", "P1");
        assertThat(tempDir.resolve("code-review/code-standards.md")).content().contains("代码规范");
        assertThat(tempDir.resolve("code-review/hotspots.md")).content().contains("热点");
        assertThat(tempDir.resolve("code-review/full-findings.md")).content()
                .contains("全量代码审查问题", "src/Foo.java", "review-batches/review-batch-001-src-foo-java/code-review.md")
                .doesNotContain(tempDir.toString());
        assertThat(tempDir.resolve("code-review/modules/src-foo-java.md")).content()
                .contains("模块代码审查报告", "src/Foo.java", "../full-findings.md");
        assertThat(tempDir.resolve("code-review/index.json")).exists();
        assertThat(tempDir.resolve("code-review/author-summaries.json")).exists();
        assertThat(tempDir.resolve("traceability.json")).content()
                .contains("region-00001", "review-batch-001-src-foo-java", "F-001");
        assertThat(tempDir.resolve("quality-scores.json")).content().contains("author-001-alice");
        assertThat(tempDir.resolve("people-ranking.md")).content().contains("最终排名", "初始排名", "Alice <alice@example.com>");
        assertThat(tempDir.resolve("people/author-001-alice/weekly-person-report.md")).content()
                .contains("个人周报", "P1", "src/Foo.java", "../../code-review/full-findings.md")
                .doesNotContain("优秀", "不合格");
        assertThat(tempDir.resolve("weekly-report.md")).content()
                .contains("周度工程项目周报", "项目经理周会重点", "[全量代码审查问题](code-review/full-findings.md)", "[作者工作排名](people-ranking.md)")
                .doesNotContain(tempDir.toString())
                .doesNotContain("Alice <alice@example.com>");
        assertThat(tempDir.resolve("team-risk-assessment.md")).content()
                .contains("团队贡献与风险辅助评估", "Alice <alice@example.com>", "[作者工作排名](people-ranking.md)");
    }

    @Test
    void refusesFinalReportsWhenBatchReviewOutputIsMissing() throws Exception {
        Path evidencePath = tempDir.resolve("weekly-evidence.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(evidencePath.toFile(), evidence());

        assertThatThrownBy(() -> new WeeklyReportRenderer(objectMapper).render(evidencePath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("review batch output incomplete");
        assertThat(tempDir.resolve("weekly-report.md")).doesNotExist();
        assertThat(tempDir.resolve("team-risk-assessment.md")).doesNotExist();
    }

    private Map<String, Object> evidence() {
        String summaryJson = tempDir.resolve("review-batches/review-batch-001-src-foo-java/code-review-summary.json").toString();
        Map<String, Object> region = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("region_id", "region-00001"),
                Map.entry("author_key", "author-001-alice"),
                Map.entry("author", "Alice <alice@example.com>"),
                Map.entry("commit", "abcdef1234567890"),
                Map.entry("short_hash", "abcdef123456"),
                Map.entry("file", "src/Foo.java"),
                Map.entry("line_start", 10),
                Map.entry("line_end", 12),
                Map.entry("hunk", "@@ -10 +10 @@\n+return value.trim();")
        ));
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("rank", 1);
        author.put("author_key", "author-001-alice");
        author.put("author", "Alice <alice@example.com>");
        author.put("commit_count", 2);
        author.put("non_comment_churn", 42);
        author.put("workload_score", 100.0);
        author.put("top_files", List.of(Map.of("path", "src/Foo.java", "non_comment_churn", 42)));
        author.put("commits", List.of(Map.of("short_hash", "abcdef123456", "subject", "Add parser")));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", "weekly-engineering-report/v1");
        root.put("generated_at", "2026-06-26T10:00:00+08:00");
        root.put("week", Map.of("start", "2026-06-22", "end", "2026-06-28", "label", "2026-W26"));
        root.put("project", Map.of("id", "upfs-production", "name", "UPFS Production", "repo", "/repo", "revision", "HEAD", "out", tempDir.toString()));
        root.put("source_runs", Map.of("weekly_git", Map.of("status", "generated")));
        root.put("weekly_git", Map.of("authors", List.of(author), "totals", Map.of("commit_count", 2)));
        root.put("review_batches", List.of(Map.of(
                "batch_id", "review-batch-001-src-foo-java",
                "scope", Map.of("type", "file", "path", "src/Foo.java"),
                "changed_regions", List.of(region),
                "summary_json", summaryJson,
                "review_md", tempDir.resolve("review-batches/review-batch-001-src-foo-java/code-review.md").toString()
        )));
        root.put("data_quality", Map.of("status", "clean", "issues", List.of(), "known_biases", List.of("提交量不等于业务价值")));
        return root;
    }

    private void writeBatchSummary() throws Exception {
        Path summary = tempDir.resolve("review-batches/review-batch-001-src-foo-java/code-review-summary.json");
        Files.createDirectories(summary.getParent());
        Files.writeString(tempDir.resolve("review-batches/review-batch-001-src-foo-java/code-review.md"), "# 批次代码审查\n\n发现一个 P1 可维护性问题。\n");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", "weekly-code-review-output/v1");
        root.put("batch_id", "review-batch-001-src-foo-java");
        root.put("status", "completed");
        root.put("summary", "发现一个 P1 可维护性问题。");
        root.put("reviewed_region_ids", List.of("region-00001"));
        root.put("finding_counts", Map.of("P0", 0, "P1", 1, "P2", 0));
        root.put("findings", List.of(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("id", "F-001"),
                Map.entry("region_id", "region-00001"),
                Map.entry("author_key", "author-001-alice"),
                Map.entry("commit", "abcdef1234567890"),
                Map.entry("dimension", "maintainability"),
                Map.entry("polarity", "negative"),
                Map.entry("severity", "P1"),
                Map.entry("rule_id", "null-trim-branch"),
                Map.entry("file", "src/Foo.java"),
                Map.entry("line_start", 10),
                Map.entry("line_end", 12),
                Map.entry("evidence", "提交区域内直接调用 trim，需要确认 null 分支覆盖。"),
                Map.entry("reason", "可维护性风险。"),
                Map.entry("suggestion", "补齐单测并拆分解析逻辑。")
        ))));
        root.put("positive_signals", List.of());
        root.put("risk_signals", List.of());
        root.put("code_snippets", List.of());
        root.put("unverified", List.of());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(summary.toFile(), root);
    }
}
