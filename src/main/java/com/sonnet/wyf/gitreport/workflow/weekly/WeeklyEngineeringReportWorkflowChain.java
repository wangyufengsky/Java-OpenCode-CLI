package com.sonnet.wyf.gitreport.workflow.weekly;

import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;

import java.nio.file.Path;

public class WeeklyEngineeringReportWorkflowChain implements WorkflowChain {
    public static final String ID = "weekly-engineering-report";

    private final ChainConfigLoader configLoader;
    private final OpenCodeRunnerProperties runnerProperties;
    private final WeeklyEvidenceBuilder evidenceBuilder;
    private final WeeklyEvidenceValidator evidenceValidator;
    private final WeeklyReportRenderer reportRenderer;

    public WeeklyEngineeringReportWorkflowChain(
            ChainConfigLoader configLoader,
            OpenCodeRunnerProperties runnerProperties,
            WeeklyEvidenceBuilder evidenceBuilder,
            WeeklyEvidenceValidator evidenceValidator,
            WeeklyReportRenderer reportRenderer
    ) {
        this.configLoader = configLoader;
        this.runnerProperties = runnerProperties;
        this.evidenceBuilder = evidenceBuilder;
        this.evidenceValidator = evidenceValidator;
        this.reportRenderer = reportRenderer;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void run(WorkflowRunRequest request) throws Exception {
        String mode = request.mode() == null || request.mode().isBlank() ? "full" : request.mode();
        if (!"full".equals(mode)) {
            throw new IllegalArgumentException("weekly-engineering-report mode must be: full");
        }
        WeeklyEngineeringReportProperties properties = configLoader.load(
                runnerProperties.getConfigDir(),
                id(),
                WeeklyEngineeringReportProperties.class
        );
        Path evidence = evidenceBuilder.build(properties, request.effectiveRunDate());
        evidenceValidator.validate(evidence);
        reportRenderer.render(evidence);
    }
}
