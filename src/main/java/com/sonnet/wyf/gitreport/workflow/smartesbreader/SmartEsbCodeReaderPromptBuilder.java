package com.sonnet.wyf.gitreport.workflow.smartesbreader;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class SmartEsbCodeReaderPromptBuilder {
    private final ResourceLoader resourceLoader;

    public SmartEsbCodeReaderPromptBuilder(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String buildModulePrompt(String taskJsonPath, boolean rerun) {
        return readResource("smartesb-code-reader-prompt-pack/prompts/run-module-reader.md")
                + "\n\n## 路径载荷\n\n```text\n"
                + "review_type: module\n"
                + "rerun: " + rerun + "\n"
                + "task_json_path: " + taskJsonPath + "\n"
                + "```\n";
    }

    public String buildTransactionPrompt(String taskJsonPath, boolean rerun) {
        return readResource("smartesb-code-reader-prompt-pack/prompts/run-transaction-reader.md")
                + "\n\n## 路径载荷\n\n```text\n"
                + "review_type: transaction\n"
                + "rerun: " + rerun + "\n"
                + "task_json_path: " + taskJsonPath + "\n"
                + "```\n";
    }

    public String buildSynthesisPrompt(Path summaryJson, Path indexInputsJson) {
        return readResource("smartesb-code-reader-prompt-pack/prompts/synthesize-index.md")
                + "\n\n## 路径载荷\n\n```text\n"
                + "summary_json: " + summaryJson + "\n"
                + "index_inputs_json: " + indexInputsJson + "\n"
                + "```\n";
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
