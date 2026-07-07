package com.sonnet.wyf.gitreport.workflow.smartesb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.util.LogicalPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SmartEsbReviewPreparation {
    private static final String INDEX_TEMPLATE = "smartesb-rewrite-code-review-prompt-pack/templates/index.md";
    private static final String TRANSACTION_REVIEW_TEMPLATE = "smartesb-rewrite-code-review-prompt-pack/templates/transaction-review.md";
    private static final String MODULE_REVIEW_TEMPLATE = "smartesb-rewrite-code-review-prompt-pack/templates/module-review.md";
    private static final String TRANSACTION_SUMMARY_SCHEMA = "smartesb-rewrite-code-review-prompt-pack/schemas/transaction-summary.schema.json";
    public static final Map<String, List<String>> TOP_LEVEL_OUTPUT_PLACEHOLDERS = Map.of(
            "index_md", List.of(
                    "{{OVERALL_CONCLUSION}}",
                    "{{TRANSACTION_ROWS}}",
                    "{{TOP_FINDING_ROWS}}",
                    "{{CODE_STANDARD_ROWS}}",
                    "{{INCOMPLETE_ROWS}}",
                    "{{NEXT_STEPS}}"
            ),
            "summary_md", List.of(
                    "{{SUMMARY_OVERALL}}",
                    "{{SUMMARY_TRANSACTION_ROWS}}",
                    "{{SUMMARY_FINDING_COUNTS}}",
                    "{{SUMMARY_TOP_FINDINGS}}",
                    "{{SUMMARY_CODE_STANDARD}}",
                    "{{SUMMARY_INCOMPLETE}}",
                    "{{SUMMARY_NEXT_STEPS}}"
            )
    );
    public static final Map<String, List<String>> TRANSACTION_OUTPUT_PLACEHOLDERS = new LinkedHashMap<>(Map.of(
            "review_md", List.of(
                    "{{FINDING_ROWS}}",
                    "{{UNVERIFIED_SUMMARY}}",
                    "{{VERIFICATION_TESTS_SUMMARY}}",
                    "{{SUMMARY}}"
            ),
            "matrix_md", List.of("{{MAPPING_ROWS}}"),
            "findings_md", List.of("{{FINDINGS_DETAIL}}"),
            "code_chains_md", List.of("{{CODE_CHAINS}}"),
            "protocol_review_md", List.of("{{PROTOCOL_REVIEW}}"),
            "behavior_review_md", List.of("{{BEHAVIOR_REVIEW}}"),
            "verification_md", List.of("{{VERIFICATION_TESTS}}"),
            "code_standard_md", List.of("{{CODE_STANDARD_REVIEW}}")
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
        String schemaLogicalPath = appendLogical(logicalOut, "schemas", "transaction-summary.schema.json");
        writeTextIfMissing(out.resolve("schemas").resolve("transaction-summary.schema.json"), readResource(TRANSACTION_SUMMARY_SCHEMA), overwrite);
        Map<String, String> topValues = topLevelTemplateValues(properties, logicalOut, plan, plan.reviewItems().size());
        writeTextIfMissing(out.resolve("index.md"), renderTemplate(INDEX_TEMPLATE, topValues), overwrite);
        writeTextIfMissing(out.resolve("summary.md"), renderSummaryTemplate(topValues), overwrite);

        List<Map<String, Object>> tasks = plan.reviewItems().stream()
                .map(item -> {
                    try {
                        return writeReviewItem(properties, logicalOut, schemaLogicalPath, out, item, overwrite);
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
        summary.put("new_project", normalizeLogical(properties.getNewProject()));
        summary.put("transaction_count", tasks.size());
        summary.put("transactions", tasks.stream().map(this::summaryTaskEntry).toList());
        Map<String, Object> indexInputs = new LinkedHashMap<>();
        indexInputs.put("date", plan.date().toString());
        indexInputs.put("out", logicalOut);
        indexInputs.put("local_out", properties.getLocalOut() == null ? null : out.toString());
        indexInputs.put("schemas", Map.of("transaction_summary", schemaLogicalPath));
        indexInputs.put("templates", Map.of(
                "index", "classpath:smartesb-rewrite-code-review-prompt-pack/templates/index.md",
                "transaction_review", "classpath:smartesb-rewrite-code-review-prompt-pack/templates/transaction-review.md",
                "module_review", "classpath:smartesb-rewrite-code-review-prompt-pack/templates/module-review.md"
        ));
        indexInputs.put("output", Map.of(
                "index_md", appendLogical(logicalOut, "index.md"),
                "summary_md", appendLogical(logicalOut, "summary.md")
        ));
        indexInputs.put("output_placeholders", TOP_LEVEL_OUTPUT_PLACEHOLDERS);
        indexInputs.put("prompts", Map.of(
                "transaction_review", "classpath:smartesb-rewrite-code-review-prompt-pack/prompts/run-transaction-review.md",
                "rerun_transaction", "classpath:smartesb-rewrite-code-review-prompt-pack/prompts/rerun-single-transaction.md",
                "module_review", "classpath:smartesb-rewrite-code-review-prompt-pack/prompts/run-module-review.md",
                "rerun_module", "classpath:smartesb-rewrite-code-review-prompt-pack/prompts/rerun-single-module.md",
                "synthesize_index", "classpath:smartesb-rewrite-code-review-prompt-pack/prompts/synthesize-index.md"
        ));
        indexInputs.put("tasks", tasks.stream().map(this::indexTaskEntry).toList());
        writeJson(out.resolve("summary.json"), summary);
        writeJson(out.resolve("index_inputs.json"), indexInputs);
        return out;
    }

    private Map<String, Object> writeReviewItem(
            SmartEsbRewriteProperties properties,
            String logicalOut,
            String schemaLogicalPath,
            Path localOut,
            SmartEsbDailyTransactionPlan.ReviewItem item,
            boolean overwrite
    ) throws IOException {
        String slug = slugify(item.name());
        String logicalReportDir = appendLogical(logicalOut, "reports", slug);
        String logicalSectionsDir = appendLogical(logicalReportDir, "sections");
        String logicalTaskPath = appendLogical(logicalOut, "tasks", item.kind() + "-" + slug + ".json");
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
        Map<String, String> values = reviewItemTemplateValues(properties, item);
        writeTextIfMissing(files.get("review_md"), renderTemplate(item.isModule() ? MODULE_REVIEW_TEMPLATE : TRANSACTION_REVIEW_TEMPLATE, values), overwrite);
        writeTextIfMissing(files.get("matrix_md"), item.isModule() ? moduleMatrixTemplate() : mappingTemplate(), overwrite);
        writeTextIfMissing(files.get("findings_md"), sectionTemplate("详细问题", "{{FINDINGS_DETAIL}}"), overwrite);
        writeTextIfMissing(files.get("code_chains_md"), sectionTemplate("新代码调用链", "{{CODE_CHAINS}}"), overwrite);
        writeTextIfMissing(files.get("protocol_review_md"), sectionTemplate(item.isModule() ? "模块职责与依赖审查" : "8583 到 JSON 协议审查", "{{PROTOCOL_REVIEW}}"), overwrite);
        writeTextIfMissing(files.get("behavior_review_md"), sectionTemplate(item.isModule() ? "模块行为与风险审查" : "行为等价性审查", "{{BEHAVIOR_REVIEW}}"), overwrite);
        writeTextIfMissing(files.get("verification_md"), sectionTemplate("最小验证测试", "{{VERIFICATION_TESTS}}"), overwrite);
        writeTextIfMissing(files.get("code_standard_md"), sectionTemplate("代码规范审查", "{{CODE_STANDARD_REVIEW}}"), overwrite);
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
        task.put("review_type", item.kind());
        task.put(item.kind(), item.name());
        task.put("description", item.description());
        task.put("new_project", normalizeLogical(properties.getNewProject()));
        task.put("documents", item.isModule() ? moduleDocuments(properties) : transactionDocuments(properties));
        task.put("skill", Map.of(
                "prompt", item.isModule()
                        ? "classpath:smartesb-rewrite-code-review-prompt-pack/prompts/run-module-review.md"
                        : "classpath:smartesb-rewrite-code-review-prompt-pack/prompts/run-transaction-review.md",
                "review_template", item.isModule()
                        ? "classpath:smartesb-rewrite-code-review-prompt-pack/templates/module-review.md"
                        : "classpath:smartesb-rewrite-code-review-prompt-pack/templates/transaction-review.md",
                "summary_schema", schemaLogicalPath,
                "reader_hint", "使用当前 AgentBridge 环境可用能力读取任务输入、代码、文档和准备器输出。",
                "writer_hint", "将 Markdown 和 JSON 写入路径载荷指定的目标文件。",
                "db_mcp_tool_prefix", "intellij-db_*"
        ));
        task.put("output", output);
        task.put("output_placeholders", TRANSACTION_OUTPUT_PLACEHOLDERS);
        task.put("rules", reviewItemRules(item));
        task.put("task_path", logicalTaskPath);
        writeJson(localOut.resolve("tasks").resolve(item.kind() + "-" + slug + ".json"), task);
        return task;
    }

    static String slugify(String value) {
        return LogicalPaths.slug(value, "transaction");
    }

    static boolean isAbsoluteLogicalPath(String path) {
        return LogicalPaths.isAbsolute(path);
    }

    static String appendLogical(String base, String... segments) {
        return LogicalPaths.append(base, segments);
    }

    private static String normalizeLogical(String value) {
        return LogicalPaths.normalize(value);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summaryTaskEntry(Map<String, Object> task) {
        Map<String, Object> output = (Map<String, Object>) task.get("output");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("review_type", task.get("review_type"));
        entry.put(targetField(task), targetName(task));
        entry.put("description", task.get("description"));
        entry.put("task_path", task.get("task_path"));
        entry.put("review_md", output.get("review_md"));
        entry.put("summary_json", output.get("summary_json"));
        return entry;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> indexTaskEntry(Map<String, Object> task) {
        Map<String, Object> output = (Map<String, Object>) task.get("output");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("review_type", task.get("review_type"));
        entry.put(targetField(task), targetName(task));
        entry.put("description", task.get("description"));
        entry.put("task_path", task.get("task_path"));
        entry.put("report_dir", output.get("dir"));
        entry.put("review_md", output.get("review_md"));
        entry.put("summary_json", output.get("summary_json"));
        entry.put("output_placeholders", task.get("output_placeholders"));
        return entry;
    }

    private String targetField(Map<String, Object> task) {
        return "module".equals(task.get("review_type")) ? "module" : "transaction";
    }

    private Object targetName(Map<String, Object> task) {
        return task.get(targetField(task));
    }

    private Map<String, String> transactionDocuments(SmartEsbRewriteProperties properties) {
        return Map.of(
                "mapping_8583_to_json", normalizeLogical(firstNonBlank(properties.getMappingDoc(), appendLogical(properties.getDocRoot(), "8583 to json.md"))),
                "reconstructed_design", normalizeLogical(firstNonBlank(properties.getReconstructedDesign(), appendLogical(properties.getDocRoot(), "重构项目详细设计文档.md"))),
                "old_8583_doc", normalizeLogical(firstNonBlank(properties.getOld8583Doc(), appendLogical(properties.getDocRoot(), "old-8583.md")))
        );
    }

    private Map<String, String> moduleDocuments(SmartEsbRewriteProperties properties) {
        return Map.of(
                "reconstructed_design", normalizeLogical(firstNonBlank(properties.getReconstructedDesign(), appendLogical(properties.getDocRoot(), "重构项目详细设计文档.md")))
        );
    }

    private Map<String, Object> reviewItemRules(SmartEsbDailyTransactionPlan.ReviewItem item) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("scope", item.isModule()
                ? "只审查当前模块的新代码、调用方、配置、SQL、异常处理、日志、公共转换逻辑和重构详细设计；不要求交易名、8583 映射文档或 old-8583-doc 中存在对应交易。"
                : "只审查当前交易的新代码、映射文档、重构详细设计和 old-8583-doc 老代码详细设计；不读取或检索 old_project 下的老代码源码。");
        rules.put("precreated_outputs", "准备器已预创建包含完整模板和占位符的 review.md、mapping-matrix.md、sections/*.md，以及初始 summary.json；只能替换这些已存在文件中的 output_placeholders，占位符之外的标题结构不得删除、重命名或重排。");
        rules.put("template_contract", "只能替换 output_placeholders 中列出的占位符；写入完成后所有 Markdown 报告不得残留 {{...}} 占位符。");
        rules.put("external_skill_policy", "本任务 prompt 已包含完整执行规则；不要在执行前搜索、读取、加载或调用任何外部 skill、SKILL.md、超能力规则或通用规划能力，包括 brainstorming、superpowers、context-engineering、gitnexus。task JSON 中的 skill 只是本链路配置字段，不表示可以加载外部技能。");
        rules.put("reader_hint", "使用当前 AgentBridge 环境可用能力读取 task JSON、准备器输出、代码、文档和摘要。");
        rules.put("explore_preference", item.isModule()
                ? "使用当前 AgentBridge 环境可用能力定位、读取和取证模块代码；只读取当前模块相关片段。"
                : "使用当前 AgentBridge 环境可用能力定位、读取和取证代码和文档；只读取当前交易相关片段。");
        rules.put("writer_hint", "写入路径载荷指定的 Markdown 和 JSON 文件；不要扩大任务边界。");
        rules.put("completion_policy", "证据不足时写 partial summary_json 和 unverified；完成后回复简短完成信息即可，Java 会检查目标文件、占位符、schema 和完整性。");
        rules.put("markdown_max_chars_per_write", 6000);
        rules.put("markdown_max_lines_per_write", 120);
        return rules;
    }

    private boolean anyChild(Path path) throws IOException {
        try (var stream = Files.list(path)) {
            return stream.findAny().isPresent();
        }
    }

    private Map<String, String> topLevelTemplateValues(SmartEsbRewriteProperties properties, String logicalOut, SmartEsbDailyTransactionPlan plan, int transactionCount) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("new_project", normalizeLogical(properties.getNewProject()));
        values.put("old_8583_doc", normalizeLogical(firstNonBlank(properties.getOld8583Doc(), appendLogical(properties.getDocRoot(), "old-8583.md"))));
        values.put("transaction_count", String.valueOf(transactionCount));
        values.put("out", logicalOut);
        values.put("date", plan.date().toString());
        return values;
    }

    private Map<String, String> reviewItemTemplateValues(SmartEsbRewriteProperties properties, SmartEsbDailyTransactionPlan.ReviewItem item) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("transaction", item.name());
        values.put("module", item.name());
        values.put("description", item.description());
        values.put("new_project", normalizeLogical(properties.getNewProject()));
        values.put("old_8583_doc", normalizeLogical(firstNonBlank(properties.getOld8583Doc(), appendLogical(properties.getDocRoot(), "old-8583.md"))));
        values.put("reconstructed_design", normalizeLogical(firstNonBlank(properties.getReconstructedDesign(), appendLogical(properties.getDocRoot(), "重构项目详细设计文档.md"))));
        values.put("mapping_doc", normalizeLogical(firstNonBlank(properties.getMappingDoc(), appendLogical(properties.getDocRoot(), "8583 to json.md"))));
        return values;
    }

    private String renderSummaryTemplate(Map<String, String> values) {
        return """
                # SmartESB 重构代码审查摘要

                ## 总体结论

                {{SUMMARY_OVERALL}}

                ## 审查范围

                | 项目 | 内容 |
                | --- | --- |
                | 日期 | {{date}} |
                | 重构项目 | `{{new_project}}` |
                | old-8583-doc | `{{old_8583_doc}}` |
                | 审查项数 | {{transaction_count}} |
                | 输出目录 | `{{out}}` |

                ## 交易/模块审查状态

                | 审查项 | 说明 | 状态 | P0 | P1 | P2 | P3 | 详细报告 |
                | --- | --- | --- | ---: | ---: | ---: | ---: | --- |
                {{SUMMARY_TRANSACTION_ROWS}}

                ## P0/P1/P2/P3 汇总

                {{SUMMARY_FINDING_COUNTS}}

                ## 每个审查项的重点问题

                {{SUMMARY_TOP_FINDINGS}}

                ## 代码规范问题汇总

                {{SUMMARY_CODE_STANDARD}}

                ## 失败或未完成任务

                {{SUMMARY_INCOMPLETE}}

                ## 下一步建议

                {{SUMMARY_NEXT_STEPS}}
                """.replace("{{date}}", values.getOrDefault("date", ""))
                .replace("{{new_project}}", values.getOrDefault("new_project", ""))
                .replace("{{old_8583_doc}}", values.getOrDefault("old_8583_doc", ""))
                .replace("{{transaction_count}}", values.getOrDefault("transaction_count", ""))
                .replace("{{out}}", values.getOrDefault("out", ""));
    }

    private String mappingTemplate() {
        return """
                # 字段映射矩阵

                | 8583 字段/来源 | 映射文档依据 | 老代码详细设计依据 | JSON 路径/目标 | 转换规则 | 新代码依据 | 重构详细设计依据 | 状态 | 验证方式 |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                {{MAPPING_ROWS}}
                """;
    }

    private String moduleMatrixTemplate() {
        return """
                # 模块职责与依赖矩阵

                | 模块职责/依赖 | 代码依据 | 调用方/被调用方 | 配置或 SQL 依据 | 风险点 | 状态 | 验证方式 |
                | --- | --- | --- | --- | --- | --- | --- |
                {{MAPPING_ROWS}}
                """;
    }

    private String sectionTemplate(String title, String placeholder) {
        return "# " + title + "\n\n" + placeholder + "\n";
    }

    private String renderTemplate(String resourcePath, Map<String, String> values) {
        String rendered = readResource(resourcePath);
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
