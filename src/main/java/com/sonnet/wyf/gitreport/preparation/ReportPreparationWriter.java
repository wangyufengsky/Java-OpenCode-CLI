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
            "{{OWNED_CHANGE_ROWS}}",
            "{{EXTENSION_ROWS}}",
            "{{COMMIT_ROWS}}",
            "{{BIAS_NOTES}}",
            "{{QUALITY_FINDING_ROWS}}",
            "{{POSITIVE_SIGNALS}}",
            "{{RISK_SIGNALS}}",
            "{{LOW_QUALITY_SNIPPETS}}",
            "{{UNVERIFIED_ITEMS}}"
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
            Path reportMd = out.resolve("reports").resolve(authorKey).resolve("person-report.md");
            Path qualitySummaryJson = out.resolve("reports").resolve(authorKey).resolve("quality-summary.json");
            String relativePath = "reports/" + authorKey + "/person-report.md";
            String markdownLink = "[person-report.md](" + relativePath + ")";
            author.put("author_key", authorKey);
            author.put("detail_json", detailJson.toString());
            author.put("person_report_md", reportMd.toString());
            author.put("quality_summary_json", qualitySummaryJson.toString());
            author.put("person_report_relative_path", relativePath);
            author.put("person_report_markdown_link", markdownLink);
            author.put("person_report_placeholders", PERSON_REPORT_PLACEHOLDERS);
            List<Map<String, Object>> worklist = buildExecutionWorklist(detailJson, reportMd, qualitySummaryJson);
            author.put("execution_worklist", worklist);
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("rank", author.get("rank"));
            task.put("author", author.get("author"));
            task.put("author_key", authorKey);
            task.put("detail_json", detailJson.toString());
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
            Path reportPath = Path.of(author.get("person_report_md").toString());
            Path qualityPath = Path.of(author.get("quality_summary_json").toString());
            Files.createDirectories(detailPath.getParent());
            Files.createDirectories(reportPath.getParent());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("metadata", data.get("metadata"));
            detail.put("author_key", author.get("author_key"));
            detail.put("rank", author.get("rank"));
            detail.put("author", author.get("author"));
            detail.put("summary", summaryFields(author));
            detail.put("owned_hunks", limitedList(author.get("owned_hunks"), limits.getHunksPerAuthor()));
            detail.put("attributed_findings", limitedList(author.get("attributed_findings"), limits.getFindingsPerAuthor()));
            detail.put("context_findings", limitedList(author.get("context_findings"), limits.getFindingsPerAuthor()));
            detail.put("scanner_status", author.getOrDefault("scanner_status", Map.of("enabled", false)));
            detail.put("extensions", author.get("extensions"));
            detail.put("commits", limitedList(author.get("commits"), limits.getCommits()));
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
            Files.writeString(reportPath, renderPersonReportTemplate(author));
            writeJson(qualityPath, initialQualitySummary(author, limits));
        }
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
        result.put("person_report_md", author.get("person_report_md"));
        result.put("quality_summary_json", author.get("quality_summary_json"));
        result.put("person_report_relative_path", author.get("person_report_relative_path"));
        result.put("person_report_markdown_link", author.get("person_report_markdown_link"));
        result.put("person_report_placeholders", author.get("person_report_placeholders"));
        return result;
    }

    private List<Map<String, Object>> buildExecutionWorklist(Path detailJson, Path reportMd, Path qualitySummaryJson) {
        return List.of(
                step(1, "read_detail_json", detailJson),
                step(2, "read_embedded_person_report_template", null),
                step(3, "draft_person_report", reportMd),
                stepWithPlaceholders(4, "replace_person_report_placeholders", reportMd, PERSON_REPORT_PLACEHOLDERS),
                step(5, "complete_quality_summary_text_fields", qualitySummaryJson),
                step(6, "replace_quality_summary_json_fields", qualitySummaryJson),
                Map.of("step", 7, "action", "verify_outputs", "required", true, "required_paths", List.of(reportMd.toString(), qualitySummaryJson.toString()), "status", "pending"),
                Map.of("step", 8, "action", "final_response", "required", true, "allowed", List.of("DONE person_report_md=<path> quality_summary_json=<path>", "BLOCKED step=<step> action=<action> path=<path> reason=<reason>"), "status", "pending")
        );
    }

    private Map<String, Object> step(int step, String action, Path target) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("step", step);
        map.put("action", action);
        map.put("required", step != 4);
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

    private String renderPersonReportTemplate(Map<String, Object> author) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("author", string(author.get("author")));
        values.put("rank", string(author.get("rank")));
        for (String key : List.of("commit_count", "file_change_count", "unique_file_count", "added", "deleted", "net", "non_comment_added", "non_comment_deleted", "non_comment_net", "non_comment_churn", "base_workload_score")) {
            values.put(key, string(author.get(key)));
        }
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
        quality.put("status", "pending");
        quality.put("findings", limitedList(author.get("attributed_findings"), limits.getFindingsPerAuthor()));
        quality.put("positive_signals", List.of());
        quality.put("risk_signals", List.of());
        quality.put("code_snippets", limitedList(author.get("code_snippets"), limits.getFindingsPerAuthor()));
        quality.put("unverified", List.of());
        quality.put("summary", "{{QUALITY_SUMMARY}}");
        return quality;
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

    private List<?> limitedList(Object value, int limit) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().limit(Math.max(0, limit)).toList();
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
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
