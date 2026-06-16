package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.GitReportProperties.SynthesisInput;
import com.sonnet.wyf.gitreport.orchestration.SynthesisInputWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SynthesisInputWriterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesBoundedSynthesisInputWithoutEmbeddingFullAuthorReports() throws Exception {
        Path out = tempDir.resolve("out");
        Files.createDirectories(out.resolve("reports/author-001-alice"));
        Path personReport = out.resolve("reports/author-001-alice/person-report.md");
        Path qualitySummary = out.resolve("reports/author-001-alice/quality-summary.json");
        String longReport = "个人报告开头\n" + "很长内容".repeat(2_000) + "\n个人报告结尾";
        Files.writeString(personReport, longReport);
        objectMapper.writeValue(qualitySummary.toFile(), Map.of(
                "author", "Alice <alice@example.com>",
                "status", "completed",
                "findings", List.of(Map.of("severity", "medium", "reason", "重复代码")),
                "positive_signals", List.of("结构清晰"),
                "risk_signals", List.of("批量变更"),
                "unverified", List.of("未验证运行时"),
                "summary", "负责核心交易改造",
                "code_snippets", List.of(
                        Map.of("file", "A.java", "line_start", 1, "line_end", 20, "dimension", "duplication", "severity", "medium", "reason", "重复", "suggestion", "抽取方法", "snippet", "line\n".repeat(40)),
                        Map.of("file", "B.java", "line_start", 2, "line_end", 30, "dimension", "complexity", "severity", "low", "reason", "复杂", "suggestion", "拆分", "snippet", "x\n".repeat(40)),
                        Map.of("file", "C.java", "line_start", 3, "line_end", 40, "dimension", "style", "severity", "low", "reason", "格式", "suggestion", "清理", "snippet", "y\n".repeat(40))
                )
        ));

        SynthesisInput options = new SynthesisInput();
        options.setPersonReportExcerptChars(1_200);
        options.setSnippetsPerAuthor(1);
        options.setSnippetsTotal(1);
        options.setSnippetLines(3);

        Path output = new SynthesisInputWriter(objectMapper).write(
                out.resolve("runs/synthesis/synthesis-inputs.json"),
                Map.of(
                        "metadata", Map.of("project_id", "demo", "project_name", "Demo"),
                        "totals", Map.of("commit_count", 1),
                        "ranking", List.of(Map.of(
                                "author_key", "author-001-alice",
                                "author", "Alice <alice@example.com>",
                                "rank", 1,
                                "commit_count", 1,
                                "file_change_count", 2,
                                "unique_file_count", 2,
                                "non_comment_added", 10,
                                "non_comment_deleted", 1,
                                "base_workload_score", 100.0
                        ))
                ),
                Map.of("tasks", List.of(Map.of(
                        "author_key", "author-001-alice",
                        "author", "Alice <alice@example.com>",
                        "rank", 1,
                        "report_md", personReport.toString(),
                        "quality_summary_json", qualitySummary.toString(),
                        "report_markdown_link", "[person-report.md](reports/author-001-alice/person-report.md)"
                ))),
                Map.of("rankings", List.of(Map.of(
                        "author_key", "author-001-alice",
                        "author", "Alice <alice@example.com>",
                        "base_rank", 1,
                        "final_rank", 1,
                        "base_workload_score", 100.0,
                        "quality_adjustment_percent", -5.0,
                        "workload_score", 95.0
                ))),
                options
        );

        JsonNode root = objectMapper.readTree(output.toFile());
        JsonNode author = root.path("authors").get(0);
        assertThat(author.path("person_report_excerpt").asText()).contains("个人报告开头");
        assertThat(author.path("person_report_excerpt").asText()).doesNotContain("个人报告结尾");
        assertThat(author.path("person_report_excerpt").asText().length()).isLessThan(1_300);
        assertThat(author.path("code_snippets")).hasSize(1);
        assertThat(root.path("code_snippets")).hasSize(1);
        assertThat(author.path("code_snippets").get(0).path("snippet").asText().lines().count()).isLessThanOrEqualTo(3);
    }
}
