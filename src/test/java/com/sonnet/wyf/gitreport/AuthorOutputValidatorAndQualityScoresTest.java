package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorOutputValidatorAndQualityScoresTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void validatorRejectsMarkersAndHandwrittenScores() throws Exception {
        Path report = tempDir.resolve("person-report.md");
        Path quality = tempDir.resolve("quality-summary.json");
        Files.writeString(report, GitReportConstants.AUTHOR_REPORT_MARKER + "\n");
        Files.writeString(quality, GitReportConstants.QUALITY_SUMMARY_MARKER + "\n");
        AuthorOutputValidator validator = new AuthorOutputValidator(objectMapper);

        AuthorValidationResult markerResult = validator.validate(report, quality);
        assertThat(markerResult.ok()).isFalse();
        assertThat(markerResult.error()).contains("marker");

        Files.writeString(report, "个人报告内容\n");
        Files.writeString(quality, """
                {
                  "author": "Alice <alice@example.com>",
                  "status": "completed",
                  "findings": [],
                  "positive_signals": [],
                  "risk_signals": [],
                  "code_snippets": [],
                  "unverified": [],
                  "summary": "无",
                  "quality_adjustment_percent": 0
                }
                """);

        AuthorValidationResult scoreResult = validator.validate(report, quality);
        assertThat(scoreResult.ok()).isFalse();
        assertThat(scoreResult.error()).contains("quality_adjustment_percent");
    }

    @Test
    void validatorFinalizesReportWhenOnlyTrailingMarkerRemainsAfterContent() throws Exception {
        Path report = tempDir.resolve("person-report.md");
        Path quality = tempDir.resolve("quality-summary.json");
        Files.writeString(report, """
                # 个人代码提交量报告：Alice

                已生成的个人报告内容。

                <!-- AUTHOR_CODE_CONTRIBUTION_REPORT_CONTENT -->
                """);
        Files.writeString(quality, """
                {
                  "author": "Alice <alice@example.com>",
                  "status": "completed",
                  "findings": [],
                  "positive_signals": [],
                  "risk_signals": [],
                  "code_snippets": [],
                  "unverified": [],
                  "summary": "无"
                }
                """);
        AuthorOutputValidator validator = new AuthorOutputValidator(objectMapper);

        AuthorValidationResult result = validator.validate(report, quality);

        assertThat(result.ok()).isTrue();
        assertThat(Files.readString(report)).doesNotContain(GitReportConstants.AUTHOR_REPORT_MARKER);
        assertThat(Files.readString(report)).contains("已生成的个人报告内容。");
    }

    @Test
    void qualityScoresWriterCreatesFinalRankingFromJavaScores() throws Exception {
        Path quality = tempDir.resolve("quality-summary.json");
        Files.writeString(quality, """
                {
                  "author": "Alice <alice@example.com>",
                  "status": "completed",
                  "findings": [
                    {"dimension": "risk_control", "polarity": "negative", "severity": "medium", "rule_id": "missing_boundary_check"}
                  ],
                  "positive_signals": [],
                  "risk_signals": [],
                  "code_snippets": [],
                  "unverified": [],
                  "summary": "存在风险"
                }
                """);
        Map<String, Object> indexInputs = Map.of("tasks", List.of(Map.of(
                "author_key", "author-001-alice",
                "author", "Alice <alice@example.com>",
                "rank", 1,
                "quality_summary_json", quality.toString()
        )));
        Map<String, Object> summary = Map.of("ranking", List.of(Map.of(
                "author_key", "author-001-alice",
                "author", "Alice <alice@example.com>",
                "rank", 1,
                "base_workload_score", 100.0
        )));

        QualityScoresWriter writer = new QualityScoresWriter(objectMapper, new QualityScoreCalculator(), new WorkloadScoreCalculator());
        Path output = writer.write(tempDir.resolve("quality-scores.json"), summary, indexInputs);

        JsonNode root = objectMapper.readTree(output.toFile());
        JsonNode ranking = root.get("rankings").get(0);
        assertThat(ranking.get("author_key").asText()).isEqualTo("author-001-alice");
        assertThat(ranking.get("quality_adjustment_percent").asDouble()).isEqualTo(-5.0);
        assertThat(ranking.get("workload_score").asDouble()).isEqualTo(95.0);
        assertThat(ranking.get("final_rank").asInt()).isEqualTo(1);
    }
}
