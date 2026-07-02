package com.sonnet.wyf.gitreport.opencode;

public record OpenCodeSessionState(
        String state,
        boolean terminal,
        boolean success,
        String source,
        String finalText
) {
    public OpenCodeSessionState {
        state = state == null ? "" : state;
        source = source == null ? "" : source;
        finalText = finalText == null ? "" : finalText;
    }
}
