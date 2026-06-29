package com.sonnet.wyf.gitreport.workflow.weekly;

import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;

import java.nio.file.Path;
import java.util.List;

public interface WeeklyCodeReviewRunner {
    void run(WeeklyEngineeringReportProperties properties, WorkflowRunRequest request, Path evidencePath, List<String> batchIds) throws Exception;
}
