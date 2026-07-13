package com.sonnet.wyf.gitreport.console;

import java.time.LocalDate;

public record WorkflowRunFilter(
        String query,
        RunState state,
        String chainId,
        LocalDate createdFrom,
        LocalDate createdUntil
) {
    public WorkflowRunFilter {
        query = normalize(query);
        chainId = normalize(chainId);
    }

    public static WorkflowRunFilter empty() {
        return new WorkflowRunFilter(null, null, null, null, null);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
