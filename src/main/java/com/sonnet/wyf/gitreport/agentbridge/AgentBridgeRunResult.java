package com.sonnet.wyf.gitreport.agentbridge;

public record AgentBridgeRunResult(
        String taskId,
        String webBaseUrl,
        boolean timedOut,
        boolean completedByOutput,
        String agentState,
        boolean validationOk,
        String validationError,
        int correctionRounds
) {
    public AgentBridgeRunResult {
        agentState = agentState == null ? "unknown" : agentState;
        validationError = validationError == null ? "" : validationError;
        correctionRounds = Math.max(0, correctionRounds);
    }
}
