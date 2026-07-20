package com.sonnet.wyf.gitreport.runner;

import com.sonnet.wyf.gitreport.artifact.WorkflowExecutionIds;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

public record WorkflowRunRequest(
        String mode,
        String rerunType,
        String rerunId,
        LocalDate runDate,
        AgentBridgeSettings agentBridge,
        String configDir,
        String executionId,
        Long consoleRunId
) {
    public WorkflowRunRequest {
        executionId = executionId == null || executionId.isBlank()
                ? WorkflowExecutionIds.newExecutionId()
                : executionId.trim();
    }

    public WorkflowRunRequest(String mode, String rerunType, String rerunId, LocalDate runDate, AgentBridgeSettings agentBridge) {
        this(mode, rerunType, rerunId, runDate, agentBridge, "", "", null);
    }

    public WorkflowRunRequest(
            String mode,
            String rerunType,
            String rerunId,
            LocalDate runDate,
            AgentBridgeSettings agentBridge,
            String configDir
    ) {
        this(mode, rerunType, rerunId, runDate, agentBridge, configDir, "", null);
    }

    public LocalDate effectiveRunDate() {
        return runDate == null ? LocalDate.now() : runDate;
    }

    public List<String> rerunIds() {
        if (rerunId == null || rerunId.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rerunId.split(","))
                .map(String::trim)
                .map(WorkflowRunRequest::stripQuotes)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
