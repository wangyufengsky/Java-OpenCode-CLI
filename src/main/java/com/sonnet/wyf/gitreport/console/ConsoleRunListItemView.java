package com.sonnet.wyf.gitreport.console;

/**
 * Fully formatted Dashboard run-row content. Raw state and timestamp values
 * remain inside the view service rather than leaking into Thymeleaf.
 */
public record ConsoleRunListItemView(
        long id,
        String chainLabel,
        String modeLabel,
        String stateLabel,
        String stateTone,
        String createdAtLabel,
        String durationLabel,
        String failureMessage
) {
}
