package com.sonnet.wyf.gitreport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

class PromptBuilder {
    String buildWorkerPrompt(Path detailJson) {
        String prompt = readResource("git-report-prompt-pack/prompts/run-author-report.md");
        String template = readResource("git-report-prompt-pack/templates/person-code-contribution-report.md");
        return prompt + "\n\n## 路径载荷\n\n```text\n"
                + "detail_json: " + detailJson + "\n"
                + "```\n\n## 个人报告模板\n\n```markdown\n"
                + template
                + "\n```\n";
    }

    String buildSynthesisPrompt(Path summaryJson, Path indexInputsJson, Path qualityScoresJson) {
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
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new IllegalStateException("resource missing: " + path);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
