package com.sonnet.wyf.gitreport.console;

import java.time.Instant;

public record WorkflowTaskStatus(
        long runId,
        String taskKey,
        String taskName,
        String state,
        String phase,
        String statusPath,
        String errorMessage,
        Instant updatedAt
) {
}
