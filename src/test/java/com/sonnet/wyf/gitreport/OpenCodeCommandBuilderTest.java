package com.sonnet.wyf.gitreport;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeCommandBuilderTest {
    @Test
    void buildsRunCommandWithPromptFileAsAttachmentAndExplicitMessage() {
        OpenCodeCommandBuilder builder = new OpenCodeCommandBuilder();

        java.util.List<String> command = builder.buildRunCommand(
                Path.of("D:/repo"),
                "opencode",
                "git-report-author-001",
                Path.of("D:/out/runs/author-001/worker-prompt.md"),
                "json",
                "openai/gpt-4.1",
                "严格执行附件 worker-prompt.md 中的任务，只输出 DONE 或 BLOCKED。"
        );

        assertThat(command).containsExactly(
                "opencode",
                "run",
                "--dir",
                "D:/repo",
                "--format",
                "json",
                "--title",
                "git-report-author-001",
                "--file=D:/out/runs/author-001/worker-prompt.md",
                "--model",
                "openai/gpt-4.1",
                "严格执行附件 worker-prompt.md 中的任务，只输出 DONE 或 BLOCKED。"
        );
    }
}
