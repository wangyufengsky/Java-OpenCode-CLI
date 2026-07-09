package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.prompt.PromptBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PromptPackContractTest {
    @Test
    void javaPromptPackDoesNotRequireSkillDiscoveryOrPythonScoring() throws Exception {
        Path promptPack = Path.of("src/main/resources/git-report-prompt-pack");

        String worker = Files.readString(promptPack.resolve("prompts/run-author-report.md"));
        String synthesis = Files.readString(promptPack.resolve("prompts/synthesize-report.md"));

        assertThat(worker).doesNotContain("SKILL.md", "执行前必须先读取以下 workflow", "python <path-to-this-skill>");
        assertThat(synthesis).doesNotContain("SKILL.md", "执行前必须先读取以下 workflow", "score-quality");
        assertThat(synthesis).contains("synthesis_inputs_json");
        assertThat(synthesis).doesNotContain("各 `index_inputs.tasks[].report_md`", "等待并读取所有");
        assertThat(synthesis).contains("不运行 Python、Shell 或其他脚本计算质量分");
    }

    @Test
    void javaPromptPackEmbedsControlledEditingAndMarkdownSafetyContract() throws Exception {
        Path promptPack = Path.of("src/main/resources/git-report-prompt-pack");

        String worker = Files.readString(promptPack.resolve("prompts/run-author-report.md"));
        String synthesis = Files.readString(promptPack.resolve("prompts/synthesize-report.md"));
        String combined = worker + "\n" + synthesis;

        assertThat(combined).contains(
                "AgentBridge",
                "当前 AgentBridge 环境可用能力",
                "路径载荷指定文件"
        );
        assertThat(worker).contains(
                "读取 `detail_json` 时，使用当前 AgentBridge 环境可用能力读取任务输入",
                "写入个人报告和质量摘要时，使用当前 AgentBridge 环境可用能力写入路径载荷指定文件",
                "所有文件写入都必须分段执行",
                "单次写入不超过 6000 字符、120 行",
                "不要一次性重写完整大文件",
                "必须先完成质量分析并写入 `quality-summary.json`，再写 `person-report.md`",
                "质量分析只能基于 `detail.changed_regions`",
                "不得打开或通读 `detail.top_files[].path` 对应的完整文件",
                "不得把完整文件中不属于 `detail.changed_regions` 的代码归因给该人员",
                "`AgentBridge` MCP 读写工具不可用时必须写入失败说明",
                "不得使用 shell、PowerShell、Python、`cat`、`type`、`Get-Content`、重定向、`cat >` 或 `sed -i`",
                "代码取证 MCP 不足，或问题需要查看提交区域之外的完整上下文才能确认时，写入 `unverified`",
                "将 `|` 转义为 `\\|`",
                "只能替换模板中已有的 `{{...}}` 占位符"
        );
        assertThat(synthesis).contains(
                "以 `synthesis_inputs.code_snippets` 中 Java 已压缩后的内容为准",
                "读取 `synthesis_inputs_json` 时，使用当前 AgentBridge 环境可用能力读取任务输入",
                "写入最终报告时，使用当前 AgentBridge 环境可用能力写入路径载荷指定文件",
                "所有文件写入都必须分段执行",
                "单次写入不超过 6000 字符、120 行",
                "不要一次性重写完整大文件",
                "`AgentBridge` MCP 读写工具不可用时必须写入失败说明",
                "不得使用 shell、PowerShell、Python、`cat`、`type`、`Get-Content`、重定向、`cat >` 或 `sed -i`",
                "将 `|` 转义为 `\\|`",
                "只能替换模板中已有的 `{{...}}` 占位符"
        );
        assertThat(combined).doesNotContain("intellij-index", "intellij-idea");
        assertThat(combined).doesNotContain("可用的文件读取/写入工具", "可用定位工具");
        assertThat(combined).doesNotContain("OpenCode 原生文件", "OpenCode `explore`", "pathInProject", "file_path");
        assertThat(worker).doesNotContain("inspect_top_files");
        assertThat(worker).doesNotContain("替换 `detail.output.report_marker`", "保留 marker", "移除 marker");
        assertThat(synthesis).doesNotContain("final_report_marker", "保留 marker", "移除 marker");
        assertThat(synthesis).doesNotContain("每个开发人员最多 2 个", "总报告最多 10 个", "每个片段最多 12 行");
    }

    @Test
    void smartEsbPromptPackUsesLinuxReadyControlledReadWriteContract() throws Exception {
        Path promptPack = Path.of("src/main/resources/smartesb-rewrite-code-review-prompt-pack");

        String worker = Files.readString(promptPack.resolve("prompts/run-transaction-review.md"));
        String rerun = Files.readString(promptPack.resolve("prompts/rerun-single-transaction.md"));
        String module = Files.readString(promptPack.resolve("prompts/run-module-review.md"));
        String rerunModule = Files.readString(promptPack.resolve("prompts/rerun-single-module.md"));
        String synthesis = Files.readString(promptPack.resolve("prompts/synthesize-index.md"));

        assertThat(worker + "\n" + module).contains(
                "读取 task JSON 和准备脚本输出时，使用当前 AgentBridge 环境可用能力读取任务输入",
                "写入 Markdown 和 JSON 报告时，使用当前 AgentBridge 环境可用能力写入路径载荷指定文件",
                "所有文件写入都必须分段执行",
                "单次写入不超过 6000 字符、120 行",
                "不要一次性重写完整大文件",
                "`AgentBridge` MCP 读写工具不可用时必须写入失败说明",
                "不得使用 shell、PowerShell、Python、`cat`、`type`、`Get-Content`、重定向、`cat >` 或 `sed -i`",
                "只能替换 task JSON 中 `output_placeholders` 列出的占位符",
                "写入完成后，所有 Markdown 报告不得残留 `{{...}}` 占位符"
        );
        assertThat(rerun + "\n" + rerunModule).contains(
                "写文件使用当前 AgentBridge 环境可用能力",
                "只能替换 `output_placeholders` 中列出的占位符"
        );
        assertThat(worker + "\n" + rerun + "\n" + module + "\n" + rerunModule + "\n" + synthesis).contains(
                "不要在执行前搜索、读取、加载或调用任何外部 skill、SKILL.md、超能力规则或通用规划能力",
                "包括但不限于 `brainstorming`、`superpowers`、`context-engineering`、`gitnexus`"
        );
        assertThat(worker + "\n" + rerun + "\n" + module + "\n" + rerunModule).contains(
                "task JSON 中的 `skill` 只是本链路的配置字段，不表示可以加载外部技能"
        );
        assertThat(module).contains(
                "模块审查不要求交易名、映射文档、old-8583-doc 或 8583 到 JSON 映射关系存在",
                "任务复杂、搜索结果少、需要更多分析时间",
                "证据不足时必须写 `summary_json`，`status` 设为 `partial`"
        );
        assertThat(synthesis).contains(
                "读取汇总输入和审查项摘要时，使用当前 AgentBridge 环境可用能力读取任务输入",
                "写入 `index.md` 和 `summary.md` 时，使用当前 AgentBridge 环境可用能力写入路径载荷指定文件",
                "所有文件写入都必须分段执行",
                "单次写入不超过 6000 字符、120 行",
                "不要一次性重写完整大文件",
                "`AgentBridge` MCP 读写工具不可用时必须写入失败说明",
                "只能替换 `index_inputs.output_placeholders` 中列出的占位符"
        );
        assertThat(worker).contains("`AgentBridge` MCP 读写工具不可用时必须写入失败说明");
        assertThat(rerun).contains("写文件使用当前 AgentBridge 环境可用能力");
        assertThat(synthesis).contains("`AgentBridge` MCP 读写工具不可用时必须写入失败说明");
        assertThat(worker).doesNotContain("output_markers", "同一个 marker", "OPENCODE_APPEND");
        assertThat(rerun).doesNotContain("output_markers", "追加标记", "OPENCODE_APPEND");
        assertThat(synthesis).doesNotContain("output_markers", "OPENCODE_APPEND");

        String combined = worker + "\n" + rerun + "\n" + module + "\n" + rerunModule + "\n" + synthesis;
        assertThat(combined).contains(
                "可以读取 old-8583-doc 中当前交易相关的老代码详细设计片段",
                "交易审查只使用 task JSON、准备器输出、new_project 新代码、映射文档、old-8583-doc 老代码详细设计、重构详细设计、配置、SQL 和数据库证据",
                "只读取当前交易相关的映射文档、old-8583-doc 和重构详细设计片段",
                "代码和文档定位、读取、取证使用当前 AgentBridge 环境可用能力",
                "`old_code_paths` 必须写空数组"
        );
        assertThat(combined).doesNotContain("intellij-index", "intellij-idea");
        assertThat(combined).doesNotContain("文件创建工具", "受控文件编辑工具", "projectPath");
        assertThat(combined).doesNotContain(
                "OpenCode 原生文件",
                "OpenCode `explore`",
                "OpenCode explore",
                "pathInProject",
                "file_path",
                "legacy-index",
                "允许通过 OpenCode `explore` 分析 old_project 老代码",
                "交易审查只使用 task JSON、准备器输出、old_project 老代码",
                "在 `old_project` 中定位",
                "新老项目代码",
                "新老代码",
                "本链路不读取业务文档、协议文档或重构设计文档",
                "`documents` 必须写空数组"
        );
    }

    @Test
    void smartEsbCodeReaderPromptPackUsesJavaRuntimeContractAndNoLegacyScriptsOrMcp() throws Exception {
        Path promptPack = Path.of("src/main/resources/smartesb-code-reader-prompt-pack");

        String module = Files.readString(promptPack.resolve("prompts/run-module-reader.md"));
        String transaction = Files.readString(promptPack.resolve("prompts/run-transaction-reader.md"));
        String synthesis = Files.readString(promptPack.resolve("prompts/synthesize-index.md"));
        String combined = module + "\n" + transaction + "\n" + synthesis;

        assertThat(combined).contains(
                "读取任务输入、XML、.biz、Java 候选文件和摘要时，使用当前 AgentBridge 环境可用能力读取任务输入",
                "写入 Markdown 和 JSON 报告时，使用当前 AgentBridge 环境可用能力写入路径载荷指定文件",
                "所有文件写入都必须分段执行",
                "单次写入不超过 6000 字符、120 行",
                "不要一次性重写完整大文件",
                "当前能力不可用或证据不足时，在输出文件中说明",
                "不得使用 shell、PowerShell、Python、`cat`、`type`、`Get-Content`、重定向、`cat >` 或 `sed -i`",
                "不要在执行前搜索、读取、加载或调用任何外部 skill、SKILL.md"
        );
        assertThat(combined).doesNotContain("intellij-index", "intellij-idea");
        assertThat(module).contains("SmartESB code-reader 模块阅读任务", "review_type: module");
        assertThat(transaction).contains("SmartESB code-reader 交易阅读任务", "review_type: transaction");
        assertThat(synthesis).contains("index_inputs_json");
        assertThat(combined).doesNotContain(
                "smartesb_writer_mcp",
                "write_markdown_chunk.py",
                "write_json_file.py",
                "prepare_smartesb_tasks.py",
                "tasks/batches",
                "module-batch",
                "transaction-batch",
                "smartesb_begin_markdown",
                "smartesb_append_markdown"
        );
        assertThat(combined).doesNotContain("OpenCode 原生文件", "pathInProject", "file_path");
    }

    @Test
    void projectUnitTestPromptOnlyDescribesGoalAndJavaValidationFeedback() throws Exception {
        Path promptPack = Path.of("src/main/resources/project-unit-test-generation-prompt-pack");

        String worker = Files.readString(promptPack.resolve("prompts/run-test-batch.md"));

        assertThat(worker).contains(
                "batch_input_json:",
                "上一轮 Java 验收失败摘要",
                "只允许修改 `target_test_files` 或 `allowed_write_globs` 内的测试文件",
                "所有文件写入都必须分段执行",
                "单次写入不超过 6000 字符、120 行",
                "不要一次性重写完整大文件",
                "不要修改生产代码、构建脚本、配置文件",
                "只需修改当前批次允许范围内的测试文件",
                "只有 `batch_input_json.coverage.required` 为 `true` 时才验收覆盖率",
                "最终只回复简短完成信息"
        );
        assertThat(worker).doesNotContain(
                "MCP",
                "read_file",
                "write_file",
                "edit_text",
                "get_compilation_errors",
                "run_tests",
                "get_coverage",
                "list_tests",
                "tools/call",
                "\"status\"",
                legacySummaryJsonField(),
                legacySummaryJsonFile(),
                oldChinesePhrase("中间", "产物"),
                oldChinesePhrase("额外", "产物"),
                "DO" + "NE",
                "BLO" + "CKED",
                "严格执行 `batch_input_json.rules`",
                "只允许创建或修改目标项目 src/test/** 下的测试文件",
                "读取 batch_input_json、源码、已有测试和文档时，必须使用 `AgentBridge` MCP 文件读取工具",
                "创建或修改测试文件、写入 " + legacySummaryJsonField() + " 时，必须使用 `AgentBridge` MCP 文件编辑工具",
                "开始写代码前，先判断本 task 是需要新写测试、补充已有测试，还是已有测试已经满足覆盖率",
                "写完或修改测试文件后，必须调用 `AgentBridge` MCP 诊断工具：`get_compilation_errors`",
                "run_tests 失败时，根据失败原因修改测试",
                "覆盖率未达标时必须新增测试场景",
                "Java 编排会把该 task",
                "未完成任务补跑"
        );
        assertThat(worker).doesNotContain("intellij-index", "intellij-idea", "OpenCode 原生文件");
    }

    private static String legacySummaryJsonField() {
        return "summary" + "_json";
    }

    private static String legacySummaryJsonFile() {
        return "summary" + ".json";
    }

    private static String oldChinesePhrase(String first, String second) {
        return first + second;
    }

    @Test
    void promptBuilderLoadsClasspathResourcesAndEmbedsTemplates() throws Exception {
        PromptBuilder builder = new PromptBuilder(new DefaultResourceLoader());

        String prompt = builder.buildWorkerPrompt(Path.of("D:/out/details/author-001.json"));

        assertThat(prompt).contains("detail_json: D:/out/details/author-001.json");
        assertThat(prompt).contains("## 个人报告模板");
        assertThat(prompt).contains("个人代码提交量报告");
        assertThat(prompt).contains("{{WORKLOAD_STRUCTURE_ANALYSIS}}");
        assertThat(prompt).doesNotContain("SKILL.md");
    }

    @Test
    void synthesisPromptUsesBoundedSynthesisInputs() {
        PromptBuilder builder = new PromptBuilder(new DefaultResourceLoader());

        String prompt = builder.buildSynthesisPrompt(Path.of("D:/out/runs/synthesis/synthesis-inputs.json"));

        assertThat(prompt).contains("synthesis_inputs_json: D:/out/runs/synthesis/synthesis-inputs.json");
        assertThat(prompt).doesNotContain("index_inputs_json:", "summary_json:", "quality_scores_json:");
        assertThat(prompt).contains("Java 已将必要摘录和质量摘要压缩进 `synthesis_inputs`");
        assertThat(prompt).contains("{{RANKING_ROWS}}");
    }
}
