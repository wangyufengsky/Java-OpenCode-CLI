package com.sonnet.wyf.gitreport;

class OpenCodeRunResult {
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

    String sessionId() {
        return sessionId;
    }

    String serverUrl() {
        return serverUrl;
    }

    boolean serverOwnedByJava() {
        return serverOwnedByJava;
    }

    boolean timedOut() {
        return timedOut;
    }

    boolean completedByOutput() {
        return completedByOutput;
    }

    boolean aborted() {
        return aborted;
    }

    String serverState() {
        return serverState;
    }
}
