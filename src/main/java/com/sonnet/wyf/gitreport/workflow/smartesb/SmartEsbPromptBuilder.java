package com.sonnet.wyf.gitreport.workflow.smartesb;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class SmartEsbPromptBuilder {
    private final ResourceLoader resourceLoader;

    public SmartEsbPromptBuilder(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String buildTransactionPrompt(String taskJsonPath, String summarySchema) {
        return readResource("smartesb-rewrite-code-review-prompt-pack/prompts/run-transaction-review.md")
                + "\n\n## 路径载荷\n\n```text\n"
                + "task_json_path: " + taskJsonPath + "\n"
                + "summary_schema: " + summarySchema + "\n"
                + "```\n";
    }

    public String buildRerunTransactionPrompt(String taskJsonPath, String previousOutputPath, String summarySchema) {
        return readResource("smartesb-rewrite-code-review-prompt-pack/prompts/rerun-single-transaction.md")
                + "\n\n## 路径载荷\n\n```text\n"
                + "task_json_path: " + taskJsonPath + "\n"
                + "previous_output: " + previousOutputPath + "\n"
                + "summary_schema: " + summarySchema + "\n"
                + "```\n";
    }

    public String buildModulePrompt(String taskJsonPath, String summarySchema) {
        return readResource("smartesb-rewrite-code-review-prompt-pack/prompts/run-module-review.md")
                + "\n\n## 路径载荷\n\n```text\n"
                + "task_json_path: " + taskJsonPath + "\n"
                + "summary_schema: " + summarySchema + "\n"
                + "```\n";
    }

    public String buildRerunModulePrompt(String taskJsonPath, String previousOutputPath, String summarySchema) {
        return readResource("smartesb-rewrite-code-review-prompt-pack/prompts/rerun-single-module.md")
                + "\n\n## 路径载荷\n\n```text\n"
                + "task_json_path: " + taskJsonPath + "\n"
                + "previous_output: " + previousOutputPath + "\n"
                + "summary_schema: " + summarySchema + "\n"
                + "```\n";
    }

    public String buildSynthesisPrompt(Path summaryJson, Path indexInputsJson) {
        String prompt = readResource("smartesb-rewrite-code-review-prompt-pack/prompts/synthesize-index.md");
        String template = readResource("smartesb-rewrite-code-review-prompt-pack/templates/index.md");
        return prompt + "\n\n## 路径载荷\n\n```text\n"
                + "summary_json: " + summaryJson + "\n"
                + "index_inputs_json: " + indexInputsJson + "\n"
                + "```\n\n## 顶层索引模板\n\n```markdown\n"
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
