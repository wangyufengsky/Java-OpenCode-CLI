package com.sonnet.wyf.gitreport.workflow.unittest;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class ProjectUnitTestGenerationPromptBuilder {
    private final ResourceLoader resourceLoader;

    public ProjectUnitTestGenerationPromptBuilder(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String buildBatchPrompt(Path repo, Path batchInputJson) {
        return buildBatchPrompt(repo, batchInputJson, "");
    }

    public String buildBatchPrompt(Path repo, Path batchInputJson, String previousFailureSummary) {
        return readResource("project-unit-test-generation-prompt-pack/prompts/run-test-batch.md")
                + "\n\n## 路径载荷\n\n```text\n"
                + "repo: " + repo + "\n"
                + "batch_input_json: " + batchInputJson + "\n"
                + "```\n"
                + "\n## 上一轮 Java 验收失败摘要\n\n"
                + (previousFailureSummary == null || previousFailureSummary.isBlank()
                ? "无。"
                : previousFailureSummary.strip())
                + "\n";
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
