package com.sonnet.wyf.gitreport.workflow.smartesb;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SmartEsbReviewPreparation {
    public static final Map<String, String> TOP_LEVEL_OUTPUT_MARKERS = Map.of(
            "index_md", "<!-- OPENCODE_APPEND:index -->",
            "summary_md", "<!-- OPENCODE_APPEND:summary -->"
    );
    public static final Map<String, String> TRANSACTION_OUTPUT_MARKERS = new LinkedHashMap<>(Map.of(
            "review_md", "<!-- OPENCODE_APPEND:review -->",
            "matrix_md", "<!-- OPENCODE_APPEND:mapping-matrix -->",
            "findings_md", "<!-- OPENCODE_APPEND:01-findings -->",
            "code_chains_md", "<!-- OPENCODE_APPEND:02-code-chains -->",
            "protocol_review_md", "<!-- OPENCODE_APPEND:03-protocol-review -->",
            "behavior_review_md", "<!-- OPENCODE_APPEND:04-behavior-review -->",
            "verification_md", "<!-- OPENCODE_APPEND:05-verification -->",
            "code_standard_md", "<!-- OPENCODE_APPEND:06-code-standard -->"
    ));

    private final ObjectMapper objectMapper;

    public SmartEsbReviewPreparation(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path prepare(SmartEsbRewriteProperties properties, SmartEsbDailyTransactionPlan plan, boolean overwrite) throws IOException {
        if (!isAbsoluteLogicalPath(properties.getOut())) {
            throw new IllegalArgumentException("SmartESB out must be an absolute path: " + properties.getOut());
        }
        String logicalOut = appendLogical(properties.getOut(), plan.date().toString());
        Path out = properties.getLocalOut() == null ? Path.of(logicalOut) : properties.getLocalOut().resolve(plan.date().toString());
        if (Files.exists(out) && anyChild(out) && !overwrite) {
            throw new IllegalStateException("SmartESB output already exists and is not empty: " + out);
        }
        Files.createDirectories(out);
        writeTextIfMissing(out.resolve("index.md"), "# SmartESB 8583 到 JSON 重构代码审查索引\n\n" + TOP_LEVEL_OUTPUT_MARKERS.get("index_md") + "\n", overwrite);
        writeTextIfMissing(out.resolve("summary.md"), "# SmartESB 重构代码审查摘要\n\n" + TOP_LEVEL_OUTPUT_MARKERS.get("summary_md") + "\n", overwrite);

        List<Map<String, Object>> tasks = plan.transactions().stream()
                .map(transaction -> {
                    try {
                        return writeTransaction(properties, logicalOut, out, transaction, overwrite);
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generated_at", OffsetDateTime.now().toString());
        summary.put("date", plan.date().toString());
        summary.put("transaction_plan", plan.source().toString());
        summary.put("out", logicalOut);
        summary.put("local_out", properties.getLocalOut() == null ? null : out.toString());
        summary.put("old_project", normalizeLogical(properties.getOldProject()));
        summary.put("new_project", normalizeLogical(properties.getNewProject()));
        summary.put("transaction_count", tasks.size());
        summary.put("transactions", tasks.stream().map(task -> Map.of(
                "transaction", task.get("transaction"),
                "description", task.get("description"),
                "task_path", task.get("task_path"),
                "review_md", ((Map<?, ?>) task.get("output")).get("review_md"),
                "summary_json", ((Map<?, ?>) task.get("output")).get("summary_json")
        )).toList());
        Map<String, Object> indexInputs = new LinkedHashMap<>();
        indexInputs.put("date", plan.date().toString());
        indexInputs.put("out", logicalOut);
        indexInputs.put("local_out", properties.getLocalOut() == null ? null : out.toString());
        indexInputs.put("schemas", Map.of("transaction_summary", "classpath:smartesb-rewrite-code-review-prompt-pack/schemas/transaction-summary.schema.json"));
        indexInputs.put("templates", Map.of(
                "index", "classpath:smartesb-rewrite-code-review-prompt-pack/templates/index.md",
                "transaction_review", "classpath:smartesb-rewrite-code-review-prompt-pack/templates/transaction-review.md"
        ));
        indexInputs.put("output", Map.of(
                "index_md", appendLogical(logicalOut, "index.md"),
                "summary_md", appendLogical(logicalOut, "summary.md")
        ));
        indexInputs.put("output_markers", TOP_LEVEL_OUTPUT_MARKERS);
        indexInputs.put("prompts", Map.of(
                "transaction_review", "classpath:smartesb-rewrite-code-review-prompt-pack/prompts/run-transaction-review.md",
                "rerun_transaction", "classpath:smartesb-rewrite-code-review-prompt-pack/prompts/rerun-single-transaction.md",
                "synthesize_index", "classpath:smartesb-rewrite-code-review-prompt-pack/prompts/synthesize-index.md"
        ));
        indexInputs.put("tasks", tasks.stream().map(task -> Map.of(
                "transaction", task.get("transaction"),
                "description", task.get("description"),
                "task_path", task.get("task_path"),
                "report_dir", ((Map<?, ?>) task.get("output")).get("dir"),
                "review_md", ((Map<?, ?>) task.get("output")).get("review_md"),
                "summary_json", ((Map<?, ?>) task.get("output")).get("summary_json")
        )).toList());
        writeJson(out.resolve("summary.json"), summary);
        writeJson(out.resolve("index_inputs.json"), indexInputs);
        return out;
    }

    private Map<String, Object> writeTransaction(
            SmartEsbRewriteProperties properties,
            String logicalOut,
            Path localOut,
            SmartEsbDailyTransactionPlan.Transaction transaction,
            boolean overwrite
    ) throws IOException {
        String slug = slugify(transaction.name());
        String logicalReportDir = appendLogical(logicalOut, "reports", slug);
        String logicalSectionsDir = appendLogical(logicalReportDir, "sections");
        String logicalTaskPath = appendLogical(logicalOut, "tasks", "transaction-" + slug + ".json");
        Path reportDir = localOut.resolve("reports").resolve(slug);
        Path sectionsDir = reportDir.resolve("sections");
        Files.createDirectories(sectionsDir);
        Map<String, Path> files = new LinkedHashMap<>();
        files.put("review_md", reportDir.resolve("review.md"));
        files.put("summary_json", reportDir.resolve("summary.json"));
        files.put("matrix_md", reportDir.resolve("mapping-matrix.md"));
        files.put("findings_md", sectionsDir.resolve("01-findings.md"));
        files.put("code_chains_md", sectionsDir.resolve("02-code-chains.md"));
        files.put("protocol_review_md", sectionsDir.resolve("03-protocol-review.md"));
        files.put("behavior_review_md", sectionsDir.resolve("04-behavior-review.md"));
        files.put("verification_md", sectionsDir.resolve("05-verification.md"));
        files.put("code_standard_md", sectionsDir.resolve("06-code-standard.md"));
        writeTextIfMissing(files.get("review_md"), "# 审查报告\n\n" + TRANSACTION_OUTPUT_MARKERS.get("review_md") + "\n", overwrite);
        writeTextIfMissing(files.get("matrix_md"), "# 字段映射矩阵\n\n" + TRANSACTION_OUTPUT_MARKERS.get("matrix_md") + "\n", overwrite);
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            String key = entry.getKey();
            if (!key.endsWith("_md") || "review_md".equals(key) || "matrix_md".equals(key)) {
                continue;
            }
            writeTextIfMissing(entry.getValue(), "# " + entry.getValue().getFileName().toString().replace(".md", "") + "\n\n" + TRANSACTION_OUTPUT_MARKERS.get(key) + "\n", overwrite);
        }
        writeTextIfMissing(files.get("summary_json"), "{}\n", overwrite);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("dir", logicalReportDir);
        output.put("review_md", appendLogical(logicalReportDir, "review.md"));
        output.put("summary_json", appendLogical(logicalReportDir, "summary.json"));
        output.put("matrix_md", appendLogical(logicalReportDir, "mapping-matrix.md"));
        output.put("sections_dir", logicalSectionsDir);
        output.put("findings_md", appendLogical(logicalSectionsDir, "01-findings.md"));
        output.put("code_chains_md", appendLogical(logicalSectionsDir, "02-code-chains.md"));
        output.put("protocol_review_md", appendLogical(logicalSectionsDir, "03-protocol-review.md"));
        output.put("behavior_review_md", appendLogical(logicalSectionsDir, "04-behavior-review.md"));
        output.put("verification_md", appendLogical(logicalSectionsDir, "05-verification.md"));
        output.put("code_standard_md", appendLogical(logicalSectionsDir, "06-code-standard.md"));
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("transaction", transaction.name());
        task.put("description", transaction.description());
        task.put("old_project", normalizeLogical(properties.getOldProject()));
        task.put("new_project", normalizeLogical(properties.getNewProject()));
        task.put("documents", Map.of(
                "legacy_index", normalizeLogical(properties.getLegacyIndex()),
                "old_8583", normalizeLogical(firstNonBlank(properties.getOld8583Doc(), appendLogical(properties.getDocRoot(), "8583.md"))),
                "json", normalizeLogical(firstNonBlank(properties.getJsonDoc(), appendLogical(properties.getDocRoot(), "json.md"))),
                "mapping_8583_to_json", normalizeLogical(firstNonBlank(properties.getMappingDoc(), appendLogical(properties.getDocRoot(), "8583 to json.md"))),
                "reconstructed_design", normalizeLogical(firstNonBlank(properties.getReconstructedDesign(), appendLogical(properties.getDocRoot(), "重构项目详细设计文档.md")))
        ));
        task.put("skill", Map.of(
                "prompt", "classpath:smartesb-rewrite-code-review-prompt-pack/prompts/run-transaction-review.md",
                "transaction_template", "classpath:smartesb-rewrite-code-review-prompt-pack/templates/transaction-review.md",
                "summary_schema", "classpath:smartesb-rewrite-code-review-prompt-pack/schemas/transaction-summary.schema.json",
                "preferred_writer", "idea_mcp",
                "idea_mcp_write_tools", List.of("intellij-idea_replace_text_undoable", "intellij-idea_replace_text_in_file"),
                "index_mcp_code_tools", List.of("intellij-index_ide_find_class", "intellij-index_ide_find_file", "intellij-index_ide_find_key_file", "intellij-index_ide_read_file"),
                "index_mcp_sync_tools", List.of("intellij-index_ide_sync_files"),
                "db_mcp_tool_prefix", "intellij-db_*"
        ));
        task.put("output", output);
        task.put("output_markers", TRANSACTION_OUTPUT_MARKERS);
        task.put("rules", Map.of(
                "scope", "只审查当前交易。",
                "precreated_outputs", "准备器已预创建 review.md、mapping-matrix.md、sections/*.md 和 summary.json；子 agent 只能替换这些已存在文件的内容，禁止创建新文件。",
                "writer_preference", "必须使用 intellij-idea_replace_text_undoable、intellij-idea_replace_text_in_file 写入已存在文件；禁止调用 intellij-idea_create_new_file。",
                "markdown_max_chars_per_write", 6000,
                "markdown_max_lines_per_write", 120
        ));
        task.put("task_path", logicalTaskPath);
        writeJson(localOut.resolve("tasks").resolve("transaction-" + slug + ".json"), task);
        return task;
    }

    static String slugify(String value) {
        String slug = value.chars()
                .mapToObj(ch -> Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' ? String.valueOf((char) ch) : "-")
                .reduce("", String::concat)
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return slug.isBlank() ? "transaction" : slug;
    }

    static boolean isAbsoluteLogicalPath(String path) {
        return path != null && (path.startsWith("/") || path.matches("^[A-Za-z]:[\\\\/].*"));
    }

    static String appendLogical(String base, String... segments) {
        String result = normalizeLogical(base);
        String separator = usesWindowsSeparators(result) ? "\\" : "/";
        for (String segment : segments) {
            String normalized = normalizeLogical(segment);
            while (normalized.startsWith("/") || normalized.startsWith("\\")) {
                normalized = normalized.substring(1);
            }
            if (!result.endsWith(separator)) {
                result += separator;
            }
            result += normalized;
        }
        return result;
    }

    private static String normalizeLogical(String value) {
        if (value == null) {
            return "";
        }
        return usesWindowsSeparators(value) ? value.replace('/', '\\') : value.replace('\\', '/');
    }

    private static boolean usesWindowsSeparators(String value) {
        return value != null && value.matches("^[A-Za-z]:[\\\\/].*");
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private boolean anyChild(Path path) throws IOException {
        try (var stream = Files.list(path)) {
            return stream.findAny().isPresent();
        }
    }

    private void writeTextIfMissing(Path path, String content, boolean overwrite) throws IOException {
        Files.createDirectories(path.getParent());
        if (overwrite || !Files.exists(path)) {
            Files.writeString(path, content);
        }
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }
}
