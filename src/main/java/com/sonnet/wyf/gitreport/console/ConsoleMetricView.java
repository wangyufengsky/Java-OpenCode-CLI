package com.sonnet.wyf.gitreport.console;

/**
 * Fully formatted Dashboard metric content. Templates must not derive values
 * from persisted workflow state or timestamps.
 */
public record ConsoleMetricView(
        String label,
        String value,
        String detail,
        String tone,
        String trend,
        String trendTone
) {
}
