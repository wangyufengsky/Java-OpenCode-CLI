package com.sonnet.wyf.gitreport.console;

public record ConsoleRunDetailSummary(
        int totalTasks,
        int succeededTasks,
        int failedTasks,
        long durationSeconds,
        String failureMessage,
        String failedTaskKey,
        String lastErrorMessage
) {
}
