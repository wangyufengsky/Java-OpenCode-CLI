package com.sonnet.wyf.gitreport.console;

import java.time.Instant;

public record WorkflowRunEvent(
        long id,
        long runId,
        String eventType,
        String message,
        Instant createdAt
) {
}
