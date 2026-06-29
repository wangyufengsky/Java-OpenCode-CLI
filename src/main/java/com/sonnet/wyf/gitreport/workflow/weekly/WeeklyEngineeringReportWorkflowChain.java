package com.sonnet.wyf.gitreport.workflow.weekly;

import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class WeeklyEngineeringReportWorkflowChain implements WorkflowChain {
    public static final String ID = "weekly-engineering-report";

    private final ChainConfigLoader configLoader;
    private final OpenCodeRunnerProperties runnerProperties;
    private final WeeklyEvidenceBuilder evidenceBuilder;
    private final WeeklyEvidenceValidator evidenceValidator;
    private final WeeklyCodeReviewRunner codeReviewRunner;
    private final WeeklyReportRenderer reportRenderer;

    public WeeklyEngineeringReportWorkflowChain(
            ChainConfigLoader configLoader,
            OpenCodeRunnerProperties runnerProperties,
            WeeklyEvidenceBuilder evidenceBuilder,
            WeeklyEvidenceValidator evidenceValidator,
            WeeklyCodeReviewRunner codeReviewRunner,
            WeeklyReportRenderer reportRenderer
    ) {
        this.configLoader = configLoader;
        this.runnerProperties = runnerProperties;
        this.evidenceBuilder = evidenceBuilder;
        this.evidenceValidator = evidenceValidator;
        this.codeReviewRunner = codeReviewRunner;
        this.reportRenderer = reportRenderer;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void run(WorkflowRunRequest request) throws Exception {
        String mode = request.mode() == null || request.mode().isBlank() ? "full" : request.mode();
        WeeklyEngineeringReportProperties properties = configLoader.load(
                configDir(request),
                id(),
                WeeklyEngineeringReportProperties.class
        );
        if ("full".equals(mode)) {
            Path evidence = evidenceBuilder.build(properties, request.effectiveRunDate());
            evidenceValidator.validate(evidence);
            codeReviewRunner.run(properties, request, evidence, List.of());
            reportRenderer.render(evidence);
            return;
        }
        if (!"rerun".equals(mode)) {
            throw new IllegalArgumentException("weekly-engineering-report mode must be one of: full, rerun");
        }
        Path evidence = properties.getPaths().getOut().toAbsolutePath().normalize().resolve("weekly-evidence.json");
        if (!Files.exists(evidence)) {
            evidence = evidenceBuilder.build(properties, request.effectiveRunDate());
        }
        evidenceValidator.validate(evidence);
        if ("review-batch".equals(request.rerunType())) {
            codeReviewRunner.run(properties, request, evidence, request.rerunIds());
            reportRenderer.render(evidence);
        } else if ("synthesis".equals(request.rerunType())) {
            reportRenderer.render(evidence);
        } else {
            throw new IllegalArgumentException("weekly-engineering-report rerun.type must be one of: review-batch, synthesis");
        }
    }

    private String configDir(WorkflowRunRequest request) {
        return request.configDir() == null || request.configDir().isBlank()
                ? runnerProperties.getConfigDir()
                : request.configDir();
    }
}
