package com.sonnet.wyf.gitreport.console;

import java.util.List;

public record ConsoleDashboardView(
        List<ConsoleMetricView> metrics,
        List<ConsoleRunListItemView> runs,
        List<ConsoleRunListItemView> attentionRuns
) {
    public ConsoleDashboardView {
        metrics = List.copyOf(metrics);
        runs = List.copyOf(runs);
        attentionRuns = List.copyOf(attentionRuns);
    }
}
