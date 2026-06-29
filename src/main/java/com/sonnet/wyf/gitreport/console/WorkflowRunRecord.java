package com.sonnet.wyf.gitreport.console;

import java.time.Instant;
import java.time.LocalDate;

public record WorkflowRunRecord(
        long id,
        String chainId,
        String mode,
        String rerunType,
        String rerunId,
        LocalDate runDate,
        RunState state,
        String phase,
        String configPath,
        String failureMessage,
        String outputPath,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
