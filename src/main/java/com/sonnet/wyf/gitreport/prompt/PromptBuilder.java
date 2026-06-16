package com.sonnet.wyf.gitreport.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

public class PromptBuilder {
    private final ResourceLoader resourceLoader;

    public PromptBuilder(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String buildWorkerPrompt(Path detailJson) {
        String prompt = readResource("git-report-prompt-pack/prompts/run-author-report.md");
        String template = readResource("git-report-prompt-pack/templates/person-code-contribution-report.md");
        return prompt + "\n\n## 路径载荷\n\n```text\n"
                + "detail_json: " + detailJson + "\n"
                + "```\n\n## 个人报告模板\n\n```markdown\n"
                + template
                + "\n```\n";
    }

    public String buildSynthesisPrompt(Path summaryJson, Path indexInputsJson, Path qualityScoresJson) {
        String prompt = readResource("git-report-prompt-pack/prompts/synthesize-report.md");
        String template = readResource("git-report-prompt-pack/templates/code-contribution-report.md");
        return prompt + "\n\n## 路径载荷\n\n```text\n"
                + "summary_json: " + summaryJson + "\n"
                + "index_inputs_json: " + indexInputsJson + "\n"
                + "quality_scores_json: " + qualityScoresJson + "\n"
                + "```\n\n## 总报告模板\n\n```markdown\n"
                + template
                + "\n```\n";
    }

    private String readResource(String path) {
        Resource resource = resourceLoader.getResource("classpath:" + path);
        try {
            if (!resource.exists()) {
                throw new IllegalStateException("resource missing: " + path);
            }
            try (InputStream inputStream = resource.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
