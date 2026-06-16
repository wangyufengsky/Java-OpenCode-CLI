package com.sonnet.wyf.gitreport.runner;

import java.time.LocalDate;

public record WorkflowRunRequest(
        String mode,
        String rerunType,
        String rerunId,
        LocalDate runDate,
        OpenCodeSettings openCode
) {
    public LocalDate effectiveRunDate() {
        return runDate == null ? LocalDate.now() : runDate;
    }
}
