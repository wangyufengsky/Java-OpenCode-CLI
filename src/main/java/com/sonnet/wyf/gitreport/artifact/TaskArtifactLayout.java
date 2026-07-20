package com.sonnet.wyf.gitreport.artifact;

import java.nio.file.Path;

public record TaskArtifactLayout(
        String taskKey,
        int attempt,
        Path root,
        Path candidate
) {
    public Path taskJson() {
        return root.resolve("task.json");
    }

    public Path workerPrompt() {
        return root.resolve("worker-prompt.md");
    }

    public Path synthesisPrompt() {
        return root.resolve("synthesis-prompt.md");
    }

    public Path validation() {
        return root.resolve("validation.json");
    }

    public Path status() {
        return root.resolve("agent-status.json");
    }
}
