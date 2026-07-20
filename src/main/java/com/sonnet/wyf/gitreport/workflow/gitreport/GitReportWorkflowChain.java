package com.sonnet.wyf.gitreport.workflow.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.GitReportProperties;
import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactContext;
import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactWorkspace;
import com.sonnet.wyf.gitreport.orchestration.GitReportOrchestrator;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.AgentBridgeSettingsApplier;
import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;

public class GitReportWorkflowChain implements WorkflowChain {
    public static final String ID = "git-code-contribution-report";

    private final ChainConfigLoader configLoader;
    private final AgentBridgeRunnerProperties runnerProperties;
    private final GitReportOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public GitReportWorkflowChain(
            ChainConfigLoader configLoader,
            AgentBridgeRunnerProperties runnerProperties,
            GitReportOrchestrator orchestrator,
            ObjectMapper objectMapper
    ) {
        this.configLoader = configLoader;
        this.runnerProperties = runnerProperties;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void run(WorkflowRunRequest request) throws Exception {
        GitReportProperties properties = configLoader.load(configDir(request), id(), GitReportProperties.class);
        AgentBridgeSettingsApplier.apply(request.agentBridge(), properties);
        String mode = request.mode() == null || request.mode().isBlank() ? "full" : request.mode();
        var workspace = WorkflowArtifactWorkspace.start(
                objectMapper,
                id(),
                request,
                properties.getPaths().getOut(),
                "rerun".equals(mode)
        );
        properties.getPaths().setOut(workspace.bundleRoot());
        try (var ignored = WorkflowArtifactContext.open(workspace)) {
            if ("full".equals(mode)) {
                orchestrator.run(properties);
            } else if (!"rerun".equals(mode)) {
                throw new IllegalArgumentException("git-report mode must be one of: full, rerun");
            } else if ("synthesis".equals(request.rerunType())) {
                orchestrator.runSynthesisOnly(properties);
            } else if ("author".equals(request.rerunType())) {
                orchestrator.runAuthors(properties, request.rerunIds());
            } else {
                throw new IllegalArgumentException("git-report rerun.type must be one of: author, synthesis");
            }
            workspace.publish("code-contribution-report.md");
        } catch (Exception exception) {
            workspace.markFailed(exception.getMessage());
            throw exception;
        }
    }

    private String configDir(WorkflowRunRequest request) {
        return request.configDir() == null || request.configDir().isBlank()
                ? runnerProperties.getConfigDir()
                : request.configDir();
    }
}
