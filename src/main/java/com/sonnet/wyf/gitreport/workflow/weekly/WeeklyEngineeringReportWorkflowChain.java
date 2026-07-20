package com.sonnet.wyf.gitreport.workflow.weekly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactContext;
import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactWorkspace;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class WeeklyEngineeringReportWorkflowChain implements WorkflowChain {
    public static final String ID = "weekly-engineering-report";

    private final ChainConfigLoader configLoader;
    private final AgentBridgeRunnerProperties runnerProperties;
    private final WeeklyEvidenceBuilder evidenceBuilder;
    private final WeeklyEvidenceValidator evidenceValidator;
    private final WeeklyCodeReviewRunner codeReviewRunner;
    private final WeeklyReportRenderer reportRenderer;
    private final ObjectMapper objectMapper;

    public WeeklyEngineeringReportWorkflowChain(
            ChainConfigLoader configLoader,
            AgentBridgeRunnerProperties runnerProperties,
            WeeklyEvidenceBuilder evidenceBuilder,
            WeeklyEvidenceValidator evidenceValidator,
            WeeklyCodeReviewRunner codeReviewRunner,
            WeeklyReportRenderer reportRenderer,
            ObjectMapper objectMapper
    ) {
        this.configLoader = configLoader;
        this.runnerProperties = runnerProperties;
        this.evidenceBuilder = evidenceBuilder;
        this.evidenceValidator = evidenceValidator;
        this.codeReviewRunner = codeReviewRunner;
        this.reportRenderer = reportRenderer;
        this.objectMapper = objectMapper;
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
        var workspace = WorkflowArtifactWorkspace.start(
                objectMapper, id(), request, properties.getPaths().getOut(), "rerun".equals(mode)
        );
        properties.getPaths().setOut(workspace.bundleRoot());
        try (var ignored = WorkflowArtifactContext.open(workspace)) {
            if ("full".equals(mode)) {
                Path evidence = evidenceBuilder.build(properties, request.effectiveRunDate());
                evidenceValidator.validate(evidence);
                codeReviewRunner.run(properties, request, evidence, List.of());
                reportRenderer.render(evidence);
            } else if (!"rerun".equals(mode)) {
                throw new IllegalArgumentException("weekly-engineering-report mode must be one of: full, rerun");
            } else {
                Path evidence = workspace.bundleRoot().resolve("weekly-evidence.json");
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
            workspace.publish("weekly-report.md");
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
