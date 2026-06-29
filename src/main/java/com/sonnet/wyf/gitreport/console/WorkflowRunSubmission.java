package com.sonnet.wyf.gitreport.console;

import com.sonnet.wyf.gitreport.runner.OpenCodeSettings;

import java.time.LocalDate;
import java.util.Map;

public record WorkflowRunSubmission(
        String chainId,
        String mode,
        String rerunType,
        String rerunId,
        LocalDate runDate,
        Map<String, Object> config,
        OpenCodeSettings openCode
) {
}
