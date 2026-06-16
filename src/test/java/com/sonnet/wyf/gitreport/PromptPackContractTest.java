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
    void promptBuilderLoadsClasspathResourcesAndEmbedsTemplates() throws Exception {
        PromptBuilder builder = new PromptBuilder(new DefaultResourceLoader());

        String prompt = builder.buildWorkerPrompt(Path.of("D:/out/details/author-001.json"));

        assertThat(prompt).contains("detail_json: D:/out/details/author-001.json");
        assertThat(prompt).contains("## 个人报告模板");
        assertThat(prompt).contains("个人代码提交量报告");
        assertThat(prompt).doesNotContain("SKILL.md");
    }

    @Test
    void synthesisPromptUsesBoundedSynthesisInputs() {
        PromptBuilder builder = new PromptBuilder(new DefaultResourceLoader());

        String prompt = builder.buildSynthesisPrompt(Path.of("D:/out/runs/synthesis/synthesis-inputs.json"));

        assertThat(prompt).contains("synthesis_inputs_json: D:/out/runs/synthesis/synthesis-inputs.json");
        assertThat(prompt).doesNotContain("index_inputs_json:", "summary_json:", "quality_scores_json:");
        assertThat(prompt).contains("Java 已将必要摘录和质量摘要压缩进 `synthesis_inputs`");
    }
}
