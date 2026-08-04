package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.GitReportConstants;
import com.sonnet.wyf.gitreport.scoring.QualityScoreCalculator;
import com.sonnet.wyf.gitreport.scoring.QualityScoresWriter;
import com.sonnet.wyf.gitreport.scoring.WorkloadScoreCalculator;
import com.sonnet.wyf.gitreport.validation.AuthorOutputValidator;
import com.sonnet.wyf.gitreport.validation.AuthorValidationResult;
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
    void validatorRejectsUnresolvedTemplatePlaceholdersAndPendingQualitySummary() throws Exception {
        Path report = tempDir.resolve("person-report.md");
        Path quality = tempDir.resolve("quality-summary.json");
        Files.writeString(report, """
                # 个人代码提交量报告：Alice

                {{WORKLOAD_STRUCTURE_ANALYSIS}}
                """);
        Files.writeString(quality, """
                {
                  "author": "Alice <alice@example.com>",
                  "status": "pending",
                  "findings": [],
                  "positive_signals": [],
                  "risk_signals": [],
                  "code_snippets": [],
                  "unverified": [],
                  "summary": "{{QUALITY_SUMMARY}}"
                }
                """);
        AuthorOutputValidator validator = new AuthorOutputValidator(objectMapper);

        AuthorValidationResult result = validator.validate(report, quality);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("unresolved template placeholder");
    }

    @Test
    void validatorRejectsInvalidFindingEnumAndMissingSnippetFields() throws Exception {
        Path report = tempDir.resolve("person-report.md");
        Path quality = tempDir.resolve("quality-summary.json");
        Files.writeString(report, "个人报告内容\n");
        Files.writeString(quality, """
                {
                  "author": "Alice <alice@example.com>",
                  "status": "completed",
                  "findings": [
                    {"dimension": "tests", "polarity": "negative", "severity": "medium", "rule_id": "bad", "file": "A.java", "line_start": 1, "line_end": 1, "evidence": "证据", "reason": "原因", "suggestion": "建议"}
                  ],
                  "positive_signals": [],
                  "risk_signals": [],
                  "code_snippets": [],
                  "unverified": [],
                  "summary": "无"
                }
                """);
        AuthorOutputValidator validator = new AuthorOutputValidator(objectMapper);

        AuthorValidationResult enumResult = validator.validate(report, quality);

        assertThat(enumResult.ok()).isFalse();
        assertThat(enumResult.error()).contains("dimension");

        Files.writeString(quality, """
                {
                  "author": "Alice <alice@example.com>",
                  "status": "completed",
                  "findings": [],
                  "positive_signals": [],
                  "risk_signals": [],
                  "code_snippets": [
                    {"file": "A.java", "dimension": "risk_control", "severity": "medium", "reason": "原因", "suggestion": "建议", "snippet": "code"}
                  ],
                  "unverified": [],
                  "summary": "无"
                }
                """);

        AuthorValidationResult snippetResult = validator.validate(report, quality);

        assertThat(snippetResult.ok()).isFalse();
        assertThat(snippetResult.error()).contains("line_start");
    }

    @Test
    void validatorAcceptsUnlistedDoubleBracesInCompletedReport() throws Exception {
        Path report = tempDir.resolve("person-report.md");
        Path quality = tempDir.resolve("quality-summary.json");
        Files.writeString(report, "个人报告内容，模板示例：`{{customerName}}`\n");
        Files.writeString(quality, """
                {
                  "author": "Alice <alice@example.com>",
                  "status": "completed",
                  "findings": [],
                  "positive_signals": [],
                  "risk_signals": [],
                  "code_snippets": [],
                  "unverified": [],
                  "summary": "正常模板代码 {{customerName}}"
                }
                """);

        AuthorValidationResult result = new AuthorOutputValidator(objectMapper).validate(report, quality);

        assertThat(result.ok()).isTrue();
    }

    @Test
    void validatorRejectsUnredactedSensitiveSnippetAndSnippetWithoutNegativeFinding() throws Exception {
        Path report = tempDir.resolve("person-report.md");
        Path quality = tempDir.resolve("quality-summary.json");
        Files.writeString(report, "个人报告内容\n");
        AuthorOutputValidator validator = new AuthorOutputValidator(objectMapper);

        Files.writeString(quality, """
                {
                  "author": "Alice <alice@example.com>",
                  "status": "completed",
                  "findings": [
                    {"id": "F1", "dimension": "risk_control", "polarity": "negative", "severity": "medium", "rule_id": "secret", "file": "A.java", "line_start": 1, "line_end": 1, "evidence": "证据", "reason": "原因", "suggestion": "建议"}
                  ],
                  "positive_signals": [],
                  "risk_signals": [],
                  "code_snippets": [
                    {"file": "A.java", "line_start": 1, "line_end": 1, "dimension": "risk_control", "severity": "medium", "reason": "原因", "suggestion": "建议", "snippet": "password = \\"plain-secret\\""}
                  ],
                  "unverified": [],
                  "summary": "无"
                }
                """);

        AuthorValidationResult sensitiveResult = validator.validate(report, quality);

        assertThat(sensitiveResult.ok()).isFalse();
        assertThat(sensitiveResult.error()).contains("sensitive");

        Files.writeString(quality, """
                {
                  "author": "Alice <alice@example.com>",
                  "status": "completed",
                  "findings": [
                    {"id": "F1", "dimension": "risk_control", "polarity": "positive", "severity": "medium", "rule_id": "good", "file": "A.java", "line_start": 1, "line_end": 1, "evidence": "证据", "reason": "原因", "suggestion": "建议"}
                  ],
                  "positive_signals": [],
                  "risk_signals": [],
                  "code_snippets": [
                    {"file": "A.java", "line_start": 1, "line_end": 1, "dimension": "risk_control", "severity": "medium", "reason": "原因", "suggestion": "建议", "snippet": "[REDACTED]"}
                  ],
                  "unverified": [],
                  "summary": "无"
                }
                """);

        AuthorValidationResult findingResult = validator.validate(report, quality);

        assertThat(findingResult.ok()).isFalse();
        assertThat(findingResult.error()).contains("negative finding");
    }

    @Test
    void validatorAcceptsValidQualitySummaryWithSnippetEvidence() throws Exception {
        Path report = tempDir.resolve("person-report.md");
        Path quality = tempDir.resolve("quality-summary.json");
        Files.writeString(report, "个人报告内容\n");
        Files.writeString(quality, """
                {
                  "author": "Alice <alice@example.com>",
                  "status": "completed",
                  "findings": [
                    {"id": "F1", "dimension": "risk_control", "polarity": "negative", "severity": "medium", "rule_id": "missing_boundary_check", "file": "A.java", "line_start": 1, "line_end": 3, "evidence": "证据", "reason": "原因", "suggestion": "建议"}
                  ],
                  "positive_signals": [],
                  "risk_signals": [],
                  "code_snippets": [
                    {"file": "A.java", "line_start": 1, "line_end": 3, "dimension": "risk_control", "severity": "medium", "reason": "原因", "suggestion": "建议", "snippet": "if (input == null) return;"}
                  ],
                  "unverified": [],
                  "summary": "无"
                }
                """);
        AuthorOutputValidator validator = new AuthorOutputValidator(objectMapper);

        AuthorValidationResult result = validator.validate(report, quality);

        assertThat(result.ok()).isTrue();
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
