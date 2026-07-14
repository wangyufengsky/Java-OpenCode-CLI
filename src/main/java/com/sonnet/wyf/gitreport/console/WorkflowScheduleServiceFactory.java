package com.sonnet.wyf.gitreport.console;

import org.springframework.stereotype.Component;

import java.time.Clock;

/** Creates schedule services without exposing their package-scoped test constructor. */
@Component
public class WorkflowScheduleServiceFactory {
    public WorkflowScheduleService create(
            WorkflowScheduleRepository repository,
            WorkflowRunSubmitter submitter,
            ChainCatalog chainCatalog,
            Clock clock,
            boolean schedulerEnabled
    ) {
        return new WorkflowScheduleService(repository, submitter, chainCatalog, clock, schedulerEnabled);
    }
}
