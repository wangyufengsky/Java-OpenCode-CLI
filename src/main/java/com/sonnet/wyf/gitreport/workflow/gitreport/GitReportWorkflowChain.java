package com.sonnet.wyf.gitreport.workflow.gitreport;

import com.sonnet.wyf.gitreport.GitReportProperties;
import com.sonnet.wyf.gitreport.orchestration.GitReportOrchestrator;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.OpenCodeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;

public class GitReportWorkflowChain implements WorkflowChain {
    public static final String ID = "git-code-contribution-report";

    private final ChainConfigLoader configLoader;
    private final OpenCodeRunnerProperties runnerProperties;
    private final GitReportOrchestrator orchestrator;

    public GitReportWorkflowChain(ChainConfigLoader configLoader, OpenCodeRunnerProperties runnerProperties, GitReportOrchestrator orchestrator) {
        this.configLoader = configLoader;
        this.runnerProperties = runnerProperties;
        this.orchestrator = orchestrator;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void run(WorkflowRunRequest request) throws Exception {
        GitReportProperties properties = configLoader.load(runnerProperties.getConfigDir(), id(), GitReportProperties.class);
        applySharedOpenCode(properties, request.openCode());
        String mode = request.mode() == null || request.mode().isBlank() ? "full" : request.mode();
        if ("full".equals(mode)) {
            orchestrator.run(properties);
            return;
        }
        if (!"rerun".equals(mode)) {
            throw new IllegalArgumentException("git-report mode must be one of: full, rerun");
        }
        if ("synthesis".equals(request.rerunType())) {
            orchestrator.runSynthesisOnly(properties);
        } else if ("author".equals(request.rerunType())) {
            orchestrator.runSingleAuthor(properties, request.rerunId());
        } else {
            throw new IllegalArgumentException("git-report rerun.type must be one of: author, synthesis");
        }
    }

    private void applySharedOpenCode(GitReportProperties properties, OpenCodeSettings settings) {
        properties.getOpencode().setServerUrl(settings.getServerUrl());
        properties.getOpencode().setManageServer(settings.isManageServer());
        properties.getOpencode().setServerStartTimeoutSeconds(settings.getServerStartTimeoutSeconds());
        properties.getOpencode().setConcurrency(settings.getConcurrency());
        properties.getOpencode().setTimeoutMinutes(settings.getTimeoutMinutes());
        properties.getOpencode().setOutputWaitSeconds(settings.getOutputWaitSeconds());
        properties.getOpencode().setMaxRetries(settings.getMaxRetries());
        properties.getOpencode().setMaxConcurrency(settings.getMaxConcurrency());
        properties.getOpencode().setModel(settings.getModel());
        properties.getPaths().setOpencodeBin(settings.getOpencodeBin());
    }
}
