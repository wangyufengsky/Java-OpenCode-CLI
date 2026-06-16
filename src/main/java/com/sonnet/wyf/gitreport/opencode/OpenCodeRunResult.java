package com.sonnet.wyf.gitreport.opencode;

public class OpenCodeRunResult {
    private final String sessionId;
    private final String serverUrl;
    private final boolean serverOwnedByJava;
    private final boolean timedOut;
    private final boolean completedByOutput;
    private final boolean aborted;
    private final String serverState;

    OpenCodeRunResult(String sessionId, String serverUrl, boolean serverOwnedByJava, boolean timedOut, boolean completedByOutput, boolean aborted, String serverState) {
        this.sessionId = sessionId;
        this.serverUrl = serverUrl;
        this.serverOwnedByJava = serverOwnedByJava;
        this.timedOut = timedOut;
        this.completedByOutput = completedByOutput;
        this.aborted = aborted;
        this.serverState = serverState == null ? "unknown" : serverState;
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
}
