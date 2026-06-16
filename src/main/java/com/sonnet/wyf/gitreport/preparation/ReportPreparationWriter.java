package com.sonnet.wyf.gitreport.preparation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.GitReportConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReportPreparationWriter {
    private static final Logger log = LoggerFactory.getLogger(ReportPreparationWriter.class);

    private final ObjectMapper objectMapper;

    public ReportPreparationWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(Path out, Map<String, Object> data) throws IOException {
        Files.createDirectories(out);
        attachOutputPaths(out.toAbsolutePath().normalize(), data);
        log.info("Writing git report preparation files to {}", out.toAbsolutePath().normalize());
        writeAuthorOutputs(out, data);
        writeJson(out.resolve("details.json"), data);
        writeJson(out.resolve("summary.json"), buildSummary(data));
        writeJson(out.resolve("index_inputs.json"), buildIndexInputs(data));
        Files.writeString(out.resolve("index.md"), "# 代码提交量统计数据预览\n\n请查看 `summary.json` 和 `index_inputs.json`。\n");
        Files.writeString(out.resolve("code-contribution-report.md"), GitReportConstants.REPORT_MARKER + "\n");
        log.info("Prepared summary.json, index_inputs.json, details.json, final report marker, and {} author task(s)", authors(data).size());
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
            author.put("person_report_marker", GitReportConstants.AUTHOR_REPORT_MARKER);
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
            task.put("report_marker", GitReportConstants.AUTHOR_REPORT_MARKER);
            task.put("quality_summary_marker", GitReportConstants.QUALITY_SUMMARY_MARKER);
            task.put("execution_worklist", worklist);
            tasks.add(task);
        }
        data.put("tasks", tasks);
    }

    private void writeAuthorOutputs(Path out, Map<String, Object> data) throws IOException {
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
            detail.put("top_files", author.get("top_files"));
            detail.put("extensions", author.get("extensions"));
            detail.put("commits", author.get("commits"));
            detail.put("files", author.get("files"));
            detail.put("execution_worklist", author.get("execution_worklist"));
            detail.put("output", Map.of(
                    "person_report_md", author.get("person_report_md"),
                    "quality_summary_json", author.get("quality_summary_json"),
                    "person_report_relative_path", author.get("person_report_relative_path"),
                    "person_report_markdown_link", author.get("person_report_markdown_link"),
                    "report_marker", GitReportConstants.AUTHOR_REPORT_MARKER,
                    "quality_summary_marker", GitReportConstants.QUALITY_SUMMARY_MARKER
            ));
            writeJson(detailPath, detail);
            Files.writeString(reportPath, GitReportConstants.AUTHOR_REPORT_MARKER + "\n");
            Files.writeString(qualityPath, GitReportConstants.QUALITY_SUMMARY_MARKER + "\n");
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
        indexInputs.put("final_report_marker", GitReportConstants.REPORT_MARKER);
        indexInputs.put("author_report_marker", GitReportConstants.AUTHOR_REPORT_MARKER);
        indexInputs.put("quality_summary_marker", GitReportConstants.QUALITY_SUMMARY_MARKER);
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
        result.put("person_report_marker", author.get("person_report_marker"));
        return result;
    }

    private List<Map<String, Object>> buildExecutionWorklist(Path detailJson, Path reportMd, Path qualitySummaryJson) {
        return List.of(
                step(1, "read_detail_json", detailJson),
                step(2, "read_embedded_person_report_template", null),
                step(3, "inspect_top_files", null),
                step(4, "collect_call_evidence", null),
                step(5, "draft_person_report", reportMd),
                stepWithMarker(6, "write_person_report", reportMd, GitReportConstants.AUTHOR_REPORT_MARKER),
                step(7, "draft_quality_summary", qualitySummaryJson),
                stepWithMarker(8, "write_quality_summary", qualitySummaryJson, GitReportConstants.QUALITY_SUMMARY_MARKER),
                Map.of("step", 9, "action", "verify_outputs", "required", true, "required_paths", List.of(reportMd.toString(), qualitySummaryJson.toString()), "status", "pending"),
                Map.of("step", 10, "action", "final_response", "required", true, "allowed", List.of("DONE person_report_md=<path> quality_summary_json=<path>", "BLOCKED step=<step> action=<action> path=<path> reason=<reason>"), "status", "pending")
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

    private Map<String, Object> stepWithMarker(int step, String action, Path target, String marker) {
        Map<String, Object> map = step(step, action, target);
        map.put("marker", marker);
        return map;
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
