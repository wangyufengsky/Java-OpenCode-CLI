package com.sonnet.wyf.gitreport.console;

import java.util.List;

public record ConsoleDashboardSummary(
        int todayRuns,
        int running,
        int queued,
        int succeeded,
        int failed,
        int successRatePercent,
        List<WorkflowRunRecord> attentionRuns
) {
    public ConsoleDashboardSummary {
        attentionRuns = List.copyOf(attentionRuns);
    }
}
