package com.sonnet.wyf.gitreport.opencode;

public class OpenCodeRunResult {
    private final String sessionId;
    private final String serverUrl;
    private final boolean serverOwnedByJava;
    private final boolean timedOut;
    private final boolean completedByOutput;
    private final boolean aborted;
    private final String serverState;
    private final boolean validationOk;
    private final String validationError;
    private final int correctionRounds;

    OpenCodeRunResult(String sessionId, String serverUrl, boolean serverOwnedByJava, boolean timedOut, boolean completedByOutput, boolean aborted, String serverState) {
        this(sessionId, serverUrl, serverOwnedByJava, timedOut, completedByOutput, aborted, serverState, completedByOutput, "", 0);
    }

    OpenCodeRunResult(
            String sessionId,
            String serverUrl,
            boolean serverOwnedByJava,
            boolean timedOut,
            boolean completedByOutput,
            boolean aborted,
            String serverState,
            boolean validationOk,
            String validationError,
            int correctionRounds
    ) {
        this.sessionId = sessionId;
        this.serverUrl = serverUrl;
        this.serverOwnedByJava = serverOwnedByJava;
        this.timedOut = timedOut;
        this.completedByOutput = completedByOutput;
        this.aborted = aborted;
        this.serverState = serverState == null ? "unknown" : serverState;
        this.validationOk = validationOk;
        this.validationError = validationError == null ? "" : validationError;
        this.correctionRounds = Math.max(0, correctionRounds);
    }

    public String sessionId() {
        return sessionId;
    }

    public String serverUrl() {
        return serverUrl;
    }

    public boolean serverOwnedByJava() {
        return serverOwnedByJava;
    }

    public boolean timedOut() {
        return timedOut;
    }

    public boolean completedByOutput() {
        return completedByOutput;
    }

    public boolean aborted() {
        return aborted;
    }

    public String serverState() {
        return serverState;
    }

    public boolean validationOk() {
        return validationOk;
    }

    public String validationError() {
        return validationError;
    }

    public int correctionRounds() {
        return correctionRounds;
    }
}
