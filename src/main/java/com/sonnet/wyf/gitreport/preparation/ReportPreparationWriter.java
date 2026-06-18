package com.sonnet.wyf.gitreport.preparation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.GitReportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReportPreparationWriter {
    private static final Logger log = LoggerFactory.getLogger(ReportPreparationWriter.class);
    private static final String PERSON_REPORT_TEMPLATE = "git-report-prompt-pack/templates/person-code-contribution-report.md";
    private static final String FINAL_REPORT_TEMPLATE = "git-report-prompt-pack/templates/code-contribution-report.md";
    private static final List<String> PERSON_REPORT_PLACEHOLDERS = List.of(
            "{{WORKLOAD_STRUCTURE_ANALYSIS}}",
            "{{BIAS_NOTES}}",
            "{{POSITIVE_SIGNALS}}",
            "{{RISK_SIGNALS}}",
            "{{OVERALL_EVALUATION}}"
    );
    private static final List<String> FINAL_REPORT_PLACEHOLDERS = List.of(
            "{{RANKING_ROWS}}",
            "{{AI_ANALYSIS}}",
            "{{PERSON_REPORT_LINK_ROWS}}",
            "{{INCOMPLETE_REPORT_ROWS}}",
            "{{RISK_AND_BIAS}}",
            "{{LOW_QUALITY_SNIPPETS}}"
    );

    private final ObjectMapper objectMapper;

    public ReportPreparationWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(Path out, Map<String, Object> data, GitReportProperties.DetailInput detailInput) throws IOException {
        Files.createDirectories(out);
        GitReportProperties.DetailInput limits = detailInput == null ? new GitReportProperties.DetailInput() : detailInput;
        attachOutputPaths(out.toAbsolutePath().normalize(), data);
        log.info("Writing git report preparation files to {}", out.toAbsolutePath().normalize());
        writeAuthorOutputs(out, data, limits);
        writeJson(out.resolve("details.json"), data);
        writeJson(out.resolve("summary.json"), buildSummary(data));
        writeJson(out.resolve("index_inputs.json"), buildIndexInputs(data));
        Files.writeString(out.resolve("index.md"), "# 代码提交量统计数据预览\n\n请查看 `summary.json` 和 `index_inputs.json`。\n");
        Files.writeString(out.resolve("code-contribution-report.md"), renderFinalReportTemplate(data));
        log.info("Prepared summary.json, index_inputs.json, details.json, final report template, and {} author task(s)", authors(data).size());
    }

    private void attachOutputPaths(Path out, Map<String, Object> data) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> author : authors(data)) {
            String authorKey = makeAuthorKey(index++, author.get("author").toString());
            Path detailJson = out.resolve("details").resolve(authorKey + ".json");
            Path detailDir = out.resolve("details").resolve(authorKey);
            Path gitJson = detailDir.resolve("git.json");
            Path pmdJson = detailDir.resolve("pmd.json");
            Path reportMd = out.resolve("reports").resolve(authorKey).resolve("person-report.md");
            Path qualitySummaryJson = out.resolve("reports").resolve(authorKey).resolve("quality-summary.json");
            String relativePath = "reports/" + authorKey + "/person-report.md";
            String markdownLink = "[person-report.md](" + relativePath + ")";
            author.put("author_key", authorKey);
            author.put("detail_json", detailJson.toString());
            author.put("git_json", gitJson.toString());
            author.put("pmd_json", pmdJson.toString());
            author.put("person_report_md", reportMd.toString());
            author.put("quality_summary_json", qualitySummaryJson.toString());
            author.put("person_report_relative_path", relativePath);
            author.put("person_report_markdown_link", markdownLink);
            author.put("person_report_placeholders", PERSON_REPORT_PLACEHOLDERS);
            List<Map<String, Object>> worklist = buildExecutionWorklist(detailJson, gitJson, pmdJson, reportMd, qualitySummaryJson);
            author.put("execution_worklist", worklist);
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("rank", author.get("rank"));
            task.put("author", author.get("author"));
            task.put("author_key", authorKey);
            task.put("detail_json", detailJson.toString());
            task.put("git_json", gitJson.toString());
            task.put("pmd_json", pmdJson.toString());
            task.put("report_md", reportMd.toString());
            task.put("quality_summary_json", qualitySummaryJson.toString());
            task.put("report_relative_path", relativePath);
            task.put("report_markdown_link", markdownLink);
            task.put("report_placeholders", PERSON_REPORT_PLACEHOLDERS);
            task.put("quality_summary_required_status", "completed");
            task.put("execution_worklist", worklist);
            tasks.add(task);
        }
        data.put("tasks", tasks);
    }

    private void writeAuthorOutputs(Path out, Map<String, Object> data, GitReportProperties.DetailInput limits) throws IOException {
        for (Map<String, Object> author : authors(data)) {
            Path detailPath = Path.of(author.get("detail_json").toString());
            Path gitPath = Path.of(author.get("git_json").toString());
            Path pmdPath = Path.of(author.get("pmd_json").toString());
            Path reportPath = Path.of(author.get("person_report_md").toString());
            Path qualityPath = Path.of(author.get("quality_summary_json").toString());
            Files.createDirectories(detailPath.getParent());
            Files.createDirectories(gitPath.getParent());
            Files.createDirectories(reportPath.getParent());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("metadata", data.get("metadata"));
            detail.put("author_key", author.get("author_key"));
            detail.put("rank", author.get("rank"));
            detail.put("author", author.get("author"));
            detail.put("summary", summaryFields(author));
            detail.put("inputs", Map.of(
                    "git_json", author.get("git_json"),
                    "pmd_json", author.get("pmd_json")
            ));
            detail.put("execution_worklist", author.get("execution_worklist"));
            detail.put("output", Map.of(
                    "person_report_md", author.get("person_report_md"),
                    "quality_summary_json", author.get("quality_summary_json"),
                    "person_report_relative_path", author.get("person_report_relative_path"),
                    "person_report_markdown_link", author.get("person_report_markdown_link"),
                    "report_placeholders", PERSON_REPORT_PLACEHOLDERS,
                    "quality_summary_status_required", "completed"
            ));
            writeJson(detailPath, detail);
            writeJson(gitPath, gitDetail(data, author, limits));
            writeJson(pmdPath, scannerDetail(data, author, "pmd", limits));
            Files.writeString(reportPath, renderPersonReportTemplate(author, limits));
            writeJson(qualityPath, initialQualitySummary(author, limits));
        }
    }

    private Map<String, Object> gitDetail(Map<String, Object> data, Map<String, Object> author, GitReportProperties.DetailInput limits) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("metadata", data.get("metadata"));
        detail.put("author_key", author.get("author_key"));
        detail.put("rank", author.get("rank"));
        detail.put("author", author.get("author"));
        detail.put("summary", summaryFields(author));
        detail.put("owned_hunks", limitedList(author.get("owned_hunks"), limits.getHunksPerAuthor()));
        detail.put("extensions", author.get("extensions"));
        detail.put("commits", limitedList(author.get("commits"), limits.getCommits()));
        return detail;
    }

    private Map<String, Object> scannerDetail(Map<String, Object> data, Map<String, Object> author, String scanner, GitReportProperties.DetailInput limits) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("metadata", data.get("metadata"));
        detail.put("author_key", author.get("author_key"));
        detail.put("rank", author.get("rank"));
        detail.put("author", author.get("author"));
        detail.put("scanner", scanner);
        detail.put("scanner_status", scannerStatus(author, scanner));
        detail.put("attributed_findings", limitedList(filterByScanner(author.get("attributed_findings"), scanner), limits.getFindingsPerAuthor()));
        detail.put("context_findings", limitedList(filterByScanner(author.get("context_findings"), scanner), limits.getFindingsPerAuthor()));
        detail.put("code_snippets", limitedList(filterByScanner(author.get("code_snippets"), scanner), limits.getFindingsPerAuthor()));
        return detail;
    }

    private Object scannerStatus(Map<String, Object> author, String scanner) {
        Map<String, Object> status = map(author.get("scanner_status"));
        Object scannerStatus = status.get(scanner);
        if (scannerStatus instanceof Map<?, ?>) {
            return scannerStatus;
        }
        if (Boolean.FALSE.equals(status.get("enabled"))) {
            return Map.of("enabled", false);
        }
        return Map.of("status", "not_run");
    }

    private Map<String, Object> buildSummary(Map<String, Object> data) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("metadata", data.get("metadata"));
        summary.put("totals", data.get("totals"));
        summary.put("ranking", authors(data).stream().map(this::rankingFields).toList());
        summary.put("tasks", data.get("tasks"));
        return summary;
    }

    private Map<String, Object> buildIndexInputs(Map<String, Object> data) {
        Map<String, Object> indexInputs = new LinkedHashMap<>();
        indexInputs.put("metadata", data.get("metadata"));
        indexInputs.put("totals", data.get("totals"));
        indexInputs.put("final_report", ((Map<?, ?>) data.get("metadata")).get("final_report"));
        indexInputs.put("final_report_placeholders", FINAL_REPORT_PLACEHOLDERS);
        indexInputs.put("tasks", data.get("tasks"));
        return indexInputs;
    }

    private Map<String, Object> summaryFields(Map<String, Object> author) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("commit_count", "file_change_count", "unique_file_count", "added", "deleted", "net", "non_comment_added", "non_comment_deleted", "non_comment_net", "non_comment_churn", "base_workload_score", "quality_adjustment_percent", "workload_score")) {
            result.put(key, author.get(key));
        }
        return result;
    }

    private Map<String, Object> rankingFields(Map<String, Object> author) {
        Map<String, Object> result = summaryFields(author);
        result.put("rank", author.get("rank"));
        result.put("author", author.get("author"));
        result.put("author_key", author.get("author_key"));
        result.put("detail_json", author.get("detail_json"));
        result.put("git_json", author.get("git_json"));
        result.put("pmd_json", author.get("pmd_json"));
        result.put("person_report_md", author.get("person_report_md"));
        result.put("quality_summary_json", author.get("quality_summary_json"));
        result.put("person_report_relative_path", author.get("person_report_relative_path"));
        result.put("person_report_markdown_link", author.get("person_report_markdown_link"));
        result.put("person_report_placeholders", author.get("person_report_placeholders"));
        return result;
    }

    private List<Map<String, Object>> buildExecutionWorklist(Path detailJson, Path gitJson, Path pmdJson, Path reportMd, Path qualitySummaryJson) {
        return List.of(
                step(1, "read_detail_json", detailJson),
                step(2, "read_git_json", gitJson),
                step(3, "read_pmd_json", pmdJson),
                step(4, "draft_analysis_and_evaluation", reportMd),
                stepWithPlaceholders(5, "replace_analysis_placeholders", reportMd, PERSON_REPORT_PLACEHOLDERS),
                Map.of("step", 6, "action", "verify_outputs", "required", true, "required_paths", List.of(reportMd.toString(), qualitySummaryJson.toString()), "status", "pending"),
                Map.of("step", 7, "action", "final_response", "required", true, "allowed", List.of("DONE person_report_md=<path> quality_summary_json=<path>", "BLOCKED step=<step> action=<action> path=<path> reason=<reason>"), "status", "pending")
        );
    }

    private Map<String, Object> step(int step, String action, Path target) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("step", step);
        map.put("action", action);
        map.put("required", true);
        if (target != null) {
            map.put("target_path", target.toString());
        }
        map.put("status", "pending");
        return map;
    }

    private Map<String, Object> stepWithPlaceholders(int step, String action, Path target, List<String> placeholders) {
        Map<String, Object> map = step(step, action, target);
        map.put("placeholders", placeholders);
        return map;
    }

    private String renderPersonReportTemplate(Map<String, Object> author, GitReportProperties.DetailInput limits) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("author", string(author.get("author")));
        values.put("rank", string(author.get("rank")));
        for (String key : List.of("commit_count", "file_change_count", "unique_file_count", "added", "deleted", "net", "non_comment_added", "non_comment_deleted", "non_comment_net", "non_comment_churn", "base_workload_score")) {
            values.put(key, string(author.get(key)));
        }
        values.put("OWNED_CHANGE_ROWS", ownedChangeRows(author, limits));
        values.put("EXTENSION_ROWS", extensionRows(author));
        values.put("COMMIT_ROWS", commitRows(author, limits));
        values.put("QUALITY_FINDING_ROWS", qualityFindingRows(author, limits));
        values.put("LOW_QUALITY_SNIPPETS", lowQualitySnippets(author, limits));
        values.put("UNVERIFIED_ITEMS", unverifiedItems(author, limits));
        return renderTemplate(PERSON_REPORT_TEMPLATE, values);
    }

    private String renderFinalReportTemplate(Map<String, Object> data) {
        Map<String, Object> metadata = map(data.get("metadata"));
        Map<String, Object> totals = map(data.get("totals"));
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : List.of("repo", "revision", "since", "until", "include_merges", "generated_at")) {
            values.put(key, string(metadata.get(key)));
        }
        values.put("default_include_rules", string(metadata.get("default_include")));
        values.put("user_include_rules", string(metadata.get("user_include")));
        values.put("default_exclude_rules", string(metadata.get("default_exclude")));
        values.put("user_exclude_rules", string(metadata.get("user_exclude")));
        for (String key : List.of("commit_count", "file_change_count", "unique_file_count", "added", "deleted", "net", "non_comment_added", "non_comment_deleted", "non_comment_net", "non_comment_churn")) {
            values.put(key, string(totals.get(key)));
        }
        return renderTemplate(FINAL_REPORT_TEMPLATE, values);
    }

    private Map<String, Object> initialQualitySummary(Map<String, Object> author, GitReportProperties.DetailInput limits) {
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("author", author.get("author"));
        quality.put("status", "completed");
        quality.put("findings", limitedList(author.get("attributed_findings"), limits.getFindingsPerAuthor()));
        quality.put("positive_signals", List.of());
        quality.put("risk_signals", List.of());
        quality.put("code_snippets", limitedList(author.get("code_snippets"), limits.getFindingsPerAuthor()));
        quality.put("unverified", qualityUnverified(author, limits));
        quality.put("summary", qualitySummaryText(author, limits));
        return quality;
    }

    private String ownedChangeRows(Map<String, Object> author, GitReportProperties.DetailInput limits) {
        List<?> hunks = limitedList(author.get("owned_hunks"), limits.getHunksPerAuthor());
        if (hunks.isEmpty()) {
            return "| 无 | 无 | 无 | 0 | 无扫描规则归因 | Java 未生成可展示的归属 hunk。 |";
        }
        List<String> rows = new ArrayList<>();
        for (Object item : hunks) {
            Map<String, Object> hunk = map(item);
            String hunkId = string(hunk.get("hunk_id"));
            String rules = scannerRulesForHunk(author, hunkId);
            rows.add("| %s | %s | %s-%s | %s | %s | Java 已归属到该人员的变更片段，供 agent 结合上下文评价。 |".formatted(
                    cell(hunk.get("file")),
                    cell(hunk.get("short_hash")),
                    cell(hunk.get("line_start")),
                    cell(hunk.get("line_end")),
                    cell(hunk.get("non_comment_added")),
                    cell(rules.isBlank() ? "无扫描规则归因" : rules)
            ));
        }
        return String.join("\n", rows);
    }

    private String extensionRows(Map<String, Object> author) {
        Map<String, Object> extensions = map(author.get("extensions"));
        if (extensions.isEmpty()) {
            return "| 无 | 0 | 0 | 0 | Java 未生成扩展名统计。 |";
        }
        List<String> rows = new ArrayList<>();
        extensions.entrySet().stream()
                .sorted((left, right) -> Integer.compare(number(map(right.getValue()).get("non_comment_added")), number(map(left.getValue()).get("non_comment_added"))))
                .forEach(entry -> {
                    Map<String, Object> stats = map(entry.getValue());
                    rows.add("| %s | %s | %s | %s | Java 统计的扩展名维度变更，供 agent 判断工作类型。 |".formatted(
                            cell(entry.getKey()),
                            cell(stats.get("file_change_count")),
                            cell(stats.get("non_comment_added")),
                            cell(stats.get("non_comment_deleted"))
                    ));
                });
        return String.join("\n", rows);
    }

    private String commitRows(Map<String, Object> author, GitReportProperties.DetailInput limits) {
        List<?> commits = limitedList(author.get("commits"), limits.getCommits());
        if (commits.isEmpty()) {
            return "| 无 | 无 | 无 | Java 未生成主要提交。 |";
        }
        List<String> rows = new ArrayList<>();
        for (Object item : commits) {
            Map<String, Object> commit = map(item);
            rows.add("| %s | %s | %s | Java 已裁剪的主要提交，供 agent 归纳工作内容。 |".formatted(
                    cell(commit.get("date")),
                    cell(commit.get("short_hash")),
                    cell(commit.get("subject"))
            ));
        }
        return String.join("\n", rows);
    }

    private String qualityFindingRows(Map<String, Object> author, GitReportProperties.DetailInput limits) {
        List<Map<String, Object>> findings = groupedFindings(author.get("attributed_findings"), limits.getFindingsPerAuthor());
        if (findings.isEmpty()) {
            return "| 无 | 无 | 无 | 无 | Java 未归因到 PMD 负向质量发现。 |";
        }
        List<String> rows = new ArrayList<>();
        for (Map<String, Object> finding : findings) {
            rows.add("| %s | %s | %s | %s | %s |".formatted(
                    cell(finding.get("dimension")),
                    cell(finding.get("polarity")),
                    cell(finding.get("severity")),
                    cell(finding.get("rule_id")),
                    cell("%s:%s-%s %s%s".formatted(
                            string(finding.get("file")),
                            string(finding.get("line_start")),
                            string(finding.get("line_end")),
                            string(finding.get("evidence")),
                            similarFindingSuffix(finding)
                    ))
            ));
        }
        return String.join("\n", rows);
    }

    private List<Map<String, Object>> groupedFindings(Object value, int limit) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        Map<String, Map<String, Object>> grouped = new java.util.LinkedHashMap<>();
        for (Object item : list) {
            Map<String, Object> finding = map(item);
            if (finding.isEmpty()) {
                continue;
            }
            String key = scannerRuleKey(finding);
            Map<String, Object> representative = grouped.computeIfAbsent(key, ignored -> {
                Map<String, Object> copy = new java.util.LinkedHashMap<>(finding);
                copy.put("similar_count", 0);
                return copy;
            });
            representative.put("similar_count", number(representative.get("similar_count")) + 1);
        }
        return grouped.values().stream().limit(Math.max(0, limit)).toList();
    }

    private String similarFindingSuffix(Map<String, Object> finding) {
        int count = number(finding.get("similar_count"));
        return count <= 1 ? "" : "（同类 %d 个，仅展示代表项）".formatted(count);
    }

    private String lowQualitySnippets(Map<String, Object> author, GitReportProperties.DetailInput limits) {
        List<Map<String, Object>> snippets = groupedSnippets(author.get("code_snippets"), limits.getFindingsPerAuthor());
        if (snippets.isEmpty()) {
            return "未发现可安全摘录的低质量代码片段。";
        }
        List<String> blocks = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> snippet : snippets) {
            blocks.add("""
                    #### 片段 %d：`%s:%s-%s`

                    - 维度：%s
                    - 严重程度：%s
                    - 规则：%s
                    - 类似片段：%s
                    - 原因：%s
                    - 建议：%s

                    ```text
                    %s
                    ```
                    """.formatted(
                    index++,
                    inlineCode(snippet.get("file")),
                    inlineCode(snippet.get("line_start")),
                    inlineCode(snippet.get("line_end")),
                    inlineText(snippet.get("dimension")),
                    inlineText(snippet.get("severity")),
                    inlineText(snippet.get("scanner_rule")),
                    inlineText(similarSnippetText(snippet)),
                    inlineText(snippet.get("reason")),
                    inlineText(snippet.get("suggestion")),
                    fenceText(snippet.get("snippet"))
            ).strip());
        }
        return String.join("\n\n", blocks);
    }

    private List<Map<String, Object>> groupedSnippets(Object value, int limit) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        Map<String, Map<String, Object>> grouped = new java.util.LinkedHashMap<>();
        for (Object item : list) {
            Map<String, Object> snippet = map(item);
            if (snippet.isEmpty()) {
                continue;
            }
            String key = snippetGroupKey(snippet);
            Map<String, Object> representative = grouped.computeIfAbsent(key, ignored -> {
                Map<String, Object> copy = new java.util.LinkedHashMap<>(snippet);
                copy.put("similar_count", 0);
                copy.put("similar_locations", new ArrayList<String>());
                return copy;
            });
            representative.put("similar_count", number(representative.get("similar_count")) + 1);
            @SuppressWarnings("unchecked")
            List<String> locations = (List<String>) representative.get("similar_locations");
            locations.add("%s:%s-%s".formatted(
                    string(snippet.get("file")),
                    string(snippet.get("line_start")),
                    string(snippet.get("line_end"))
            ));
        }
        return grouped.values().stream().limit(Math.max(0, limit)).toList();
    }

    private String snippetGroupKey(Map<String, Object> snippet) {
        return scannerRuleKey(snippet);
    }

    private String scannerRuleKey(Map<String, Object> item) {
        String rule = firstNonBlank(
                string(item.get("scanner_rule")),
                string(item.get("rule_id"))
        );
        if (rule.isBlank()) {
            rule = string(item.get("reason"));
        }
        return String.join("\u0001",
                string(item.get("scanner")),
                rule,
                string(item.get("dimension")),
                string(item.get("severity"))
        );
    }

    private String similarSnippetText(Map<String, Object> snippet) {
        int count = number(snippet.get("similar_count"));
        if (count <= 1) {
            return "1 个";
        }
        return "%d 个，本处展示 1 个代表片段".formatted(count);
    }

    private String unverifiedItems(Map<String, Object> author, GitReportProperties.DetailInput limits) {
        List<Map<String, Object>> items = qualityUnverified(author, limits);
        if (items.isEmpty()) {
            return "- 无。";
        }
        List<String> rows = new ArrayList<>();
        for (Map<String, Object> item : items) {
            rows.add("- " + inlineText(item.get("evidence")));
        }
        return String.join("\n", rows);
    }

    private List<Map<String, Object>> qualityUnverified(Map<String, Object> author, GitReportProperties.DetailInput limits) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : limitedList(author.get("context_findings"), limits.getFindingsPerAuthor())) {
            Map<String, Object> finding = map(item);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", "scanner_context");
            result.put("scanner", finding.get("scanner"));
            result.put("rule_id", finding.get("rule_id"));
            result.put("file", finding.get("file"));
            result.put("line_start", finding.get("line_start"));
            result.put("line_end", finding.get("line_end"));
            result.put("evidence", "%s 未落在该人员 owned hunk 内，仅作为上下文风险：%s:%s-%s %s".formatted(
                    string(finding.get("scanner")),
                    string(finding.get("file")),
                    string(finding.get("line_start")),
                    string(finding.get("line_end")),
                    string(finding.get("evidence"))
            ));
            items.add(result);
        }
        Map<String, Object> statuses = map(author.get("scanner_status"));
        Map<String, Object> status = map(statuses.get("pmd"));
        String state = string(status.get("status"));
        if (!state.isBlank() && !"completed".equals(state) && !"success".equals(state)) {
            items.add(Map.of(
                    "source", "scanner_status",
                    "scanner", "pmd",
                    "evidence", "pmd 扫描状态为 " + state
            ));
        }
        return items;
    }

    private String qualitySummaryText(Map<String, Object> author, GitReportProperties.DetailInput limits) {
        int findings = limitedList(author.get("attributed_findings"), limits.getFindingsPerAuthor()).size();
        int snippets = limitedList(author.get("code_snippets"), limits.getFindingsPerAuthor()).size();
        int unverified = qualityUnverified(author, limits).size();
        return "Java 已根据静态扫描归因生成质量摘要：负向发现 %d 个，低质量代码片段 %d 个，未验证项 %d 个。".formatted(findings, snippets, unverified);
    }

    private String scannerRulesForHunk(Map<String, Object> author, String hunkId) {
        if (hunkId.isBlank()) {
            return "";
        }
        List<String> rules = new ArrayList<>();
        for (Object item : listValue(author.get("attributed_findings"))) {
            Map<String, Object> finding = map(item);
            if (hunkId.equals(string(finding.get("owned_hunk_id")))) {
                rules.add(string(finding.get("scanner")) + ":" + string(finding.get("rule_id")));
            }
        }
        return String.join("<br>", rules);
    }

    private String renderTemplate(String resourcePath, Map<String, String> values) {
        String template = readResource(resourcePath);
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered;
    }

    private String readResource(String resourcePath) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("resource missing: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private List<?> limitedList(Object value, int limit) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().limit(Math.max(0, limit)).toList();
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private List<?> filterByScanner(Object value, String scanner) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map<?, ?> map && scanner.equals(string(map.get("scanner"))))
                .toList();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String cell(Object value) {
        String text = string(value).replace("\r", " ").replace("\n", "<br>");
        return text.replace("|", "\\|");
    }

    private String inlineText(Object value) {
        return string(value).replace("\r", " ").replace("\n", " ").replace("|", "\\|");
    }

    private String inlineCode(Object value) {
        return string(value).replace("`", "'");
    }

    private String fenceText(Object value) {
        return string(value).replace("```", "'''");
    }

    private String makeAuthorKey(int rank, String author) {
        String normalized = author.chars()
                .mapToObj(ch -> Character.isLetterOrDigit(ch) ? String.valueOf((char) Character.toLowerCase(ch)) : "-")
                .reduce("", String::concat)
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        return "author-%03d-%s".formatted(rank, normalized.substring(0, Math.min(60, normalized.length())));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> authors(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("authors");
    }

    private void writeJson(Path path, Object value) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }
}
