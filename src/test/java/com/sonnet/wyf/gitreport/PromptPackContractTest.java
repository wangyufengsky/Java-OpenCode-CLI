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
                "intellij-idea_read_file",
                "intellij-idea_get_file_text_by_path",
                "intellij-idea_replace_text_in_file",
                "intellij-idea_replace_text_undoable",
                "intellij-index_ide_find_references",
                "intellij-index_ide_call_hierarchy",
                "intellij-index_ide_type_hierarchy",
                "intellij-index_ide_find_implementations"
        );
        assertThat(worker).contains(
                "写入个人报告和质量摘要时，优先使用 OpenCode 原生文件编辑工具",
                "必须先完成质量分析并写入 `quality-summary.json`，再写 `person-report.md`",
                "质量分析只能基于 `detail.changed_regions`",
                "不得打开或通读 `detail.top_files[].path` 对应的完整文件",
                "不得把完整文件中不属于 `detail.changed_regions` 的代码归因给该人员",
                "路径字段只能使用 `filePath`",
                "禁止使用 `pathInProject`、`file_path`、`path`",
                "如 OpenCode 原生文件编辑工具不可用，可使用 IntelliJ MCP 文件编辑工具",
                "两类受控编辑工具都不可用时必须返回 `BLOCKED`",
                "不得使用 shell、PowerShell、Python、`cat`、`type`、`Get-Content`、重定向、`cat >` 或 `sed -i`",
                "代码取证 MCP 不足，或问题需要查看提交区域之外的完整上下文才能确认时，写入 `unverified`",
                "将 `|` 转义为 `\\|`",
                "只能替换模板中已有的 `{{...}}` 占位符"
        );
        assertThat(synthesis).contains(
                "以 `synthesis_inputs.code_snippets` 中 Java 已压缩后的内容为准",
                "写入最终报告时，优先使用 OpenCode 原生文件编辑工具",
                "路径字段只能使用 `filePath`",
                "禁止使用 `pathInProject`、`file_path`、`path`",
                "如 OpenCode 原生文件编辑工具不可用，可使用 IntelliJ MCP 文件编辑工具",
                "两类受控编辑工具都不可用时必须返回 `BLOCKED`",
                "不得使用 shell、PowerShell、Python、`cat`、`type`、`Get-Content`、重定向、`cat >` 或 `sed -i`",
                "将 `|` 转义为 `\\|`",
                "只能替换模板中已有的 `{{...}}` 占位符"
        );
        assertThat(worker).doesNotContain("MCP 写入不可用时必须返回 `BLOCKED`");
        assertThat(worker).doesNotContain("inspect_top_files");
        assertThat(synthesis).doesNotContain("MCP 写入不可用时必须返回 `BLOCKED`");
        assertThat(worker).doesNotContain("替换 `detail.output.report_marker`", "保留 marker", "移除 marker");
        assertThat(synthesis).doesNotContain("final_report_marker", "保留 marker", "移除 marker");
        assertThat(synthesis).doesNotContain("每个开发人员最多 2 个", "总报告最多 10 个", "每个片段最多 12 行");
    }

    @Test
    void smartEsbPromptPackUsesLinuxReadyControlledReadWriteContract() throws Exception {
        Path promptPack = Path.of("src/main/resources/smartesb-rewrite-code-review-prompt-pack");

        String worker = Files.readString(promptPack.resolve("prompts/run-transaction-review.md"));
        String rerun = Files.readString(promptPack.resolve("prompts/rerun-single-transaction.md"));
        String synthesis = Files.readString(promptPack.resolve("prompts/synthesize-index.md"));

        assertThat(worker).contains(
                "读取 task JSON 和准备脚本输出时，优先使用 OpenCode 原生文件读取工具",
                "写入 Markdown 和 JSON 报告时，优先使用 OpenCode 原生文件编辑工具",
                "路径字段只能使用 `filePath`",
                "禁止使用 `pathInProject`、`file_path`、`path`",
                "如 OpenCode 原生文件编辑工具不可用，可使用 IntelliJ MCP 文件编辑工具",
                "两类受控编辑工具都不可用时必须返回 `BLOCKED`",
                "不得使用 shell、PowerShell、Python、`cat`、`type`、`Get-Content`、重定向、`cat >` 或 `sed -i`",
                "只能替换 task JSON 中 `output_placeholders` 列出的占位符",
                "写入完成后，所有 Markdown 报告不得残留 `{{...}}` 占位符"
        );
        assertThat(rerun).contains(
                "写文件优先使用 OpenCode 原生文件编辑工具",
                "如 OpenCode 原生文件编辑工具不可用，可使用 IntelliJ MCP 文件编辑工具",
                "路径字段只能使用 `filePath`",
                "只能替换 `output_placeholders` 中列出的占位符"
        );
        assertThat(synthesis).contains(
                "读取汇总输入和交易摘要时，优先使用 OpenCode 原生文件读取工具",
                "写入 `index.md` 和 `summary.md` 时，优先使用 OpenCode 原生文件编辑工具",
                "路径字段只能使用 `filePath`",
                "两类受控编辑工具都不可用时必须返回 `BLOCKED`",
                "只能替换 `index_inputs.output_placeholders` 中列出的占位符"
        );
        assertThat(worker).doesNotContain("IDEA MCP 写文件不可用");
        assertThat(rerun).doesNotContain("写文件必须使用 IDEA MCP");
        assertThat(synthesis).doesNotContain("IDEA MCP 不可用");
        assertThat(worker).doesNotContain("output_markers", "同一个 marker", "OPENCODE_APPEND");
        assertThat(rerun).doesNotContain("output_markers", "追加标记", "OPENCODE_APPEND");
        assertThat(synthesis).doesNotContain("output_markers", "OPENCODE_APPEND");
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
