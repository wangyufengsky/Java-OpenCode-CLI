package com.sonnet.wyf.gitreport.preparation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.GitReportProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportPreparationWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void rendersOneRepresentativeSnippetForSimilarScannerFindings() throws Exception {
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("rank", 1);
        author.put("author", "Alice <alice@example.com>");
        author.put("code_snippets", List.of(
                snippet("Demo.java", 10, "BrokenNullCheck", "Possible broken null check.", "if (value == null) {"),
                snippet("Demo.java", 28, "BrokenNullCheck", "Another broken null check.", "if (other == null) {")
        ));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("metadata", Map.of("project_id", "demo", "project_name", "Demo", "run_id", "run-001"));
        data.put("authors", List.of(author));

        new ReportPreparationWriter(new ObjectMapper()).write(tempDir, data, new GitReportProperties.DetailInput());

        String report = Files.readString(Path.of(author.get("person_report_md").toString()));
        assertThat(report).contains("- 类似片段：2 个，本处展示 1 个代表片段");
        assertThat(report.split("#### 片段", -1)).hasSize(2);
        assertThat(report).contains("Demo.java:10-10");
        assertThat(report).doesNotContain("Demo.java:28-28");
    }

    @Test
    void rendersOneQualityFindingRowForSameScannerRule() throws Exception {
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("rank", 1);
        author.put("author", "Alice <alice@example.com>");
        author.put("attributed_findings", List.of(
                finding("DataClass", "A.java", 13, "The class 'A' is suspected to be a Data Class."),
                finding("DataClass", "B.java", 13, "The class 'B' is suspected to be a Data Class."),
                finding("GuardLogStatement", "C.java", 20, "Logger calls should be surrounded by log level guards.")
        ));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("metadata", Map.of("project_id", "demo", "project_name", "Demo", "run_id", "run-001"));
        data.put("authors", List.of(author));

        new ReportPreparationWriter(new ObjectMapper()).write(tempDir, data, new GitReportProperties.DetailInput());

        String report = Files.readString(Path.of(author.get("person_report_md").toString()));
        assertThat(report).contains("同类 2 个，仅展示代表项");
        assertThat(report.split("DataClass", -1)).hasSize(2);
        assertThat(report).doesNotContain("B.java:13-13");
        assertThat(report).contains("GuardLogStatement");
    }

    private Map<String, Object> snippet(String file, int line, String rule, String reason, String text) {
        Map<String, Object> snippet = new LinkedHashMap<>();
        snippet.put("file", file);
        snippet.put("line_start", line);
        snippet.put("line_end", line);
        snippet.put("dimension", "risk_control");
        snippet.put("severity", "high");
        snippet.put("scanner", "pmd");
        snippet.put("scanner_rule", rule);
        snippet.put("reason", reason);
        snippet.put("suggestion", "请按扫描规则修复或补充人工说明。");
        snippet.put("snippet", text);
        return snippet;
    }

    private Map<String, Object> finding(String rule, String file, int line, String evidence) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("dimension", "maintainability");
        finding.put("polarity", "negative");
        finding.put("severity", "medium");
        finding.put("rule_id", rule);
        finding.put("scanner", "pmd");
        finding.put("scanner_rule", rule);
        finding.put("file", file);
        finding.put("line_start", line);
        finding.put("line_end", line);
        finding.put("evidence", evidence);
        return finding;
    }
}
