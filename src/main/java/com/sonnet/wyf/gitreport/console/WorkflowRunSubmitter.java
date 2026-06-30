package com.sonnet.wyf.gitreport.console;

public interface WorkflowRunSubmitter {
    long submit(WorkflowRunSubmission submission) throws Exception;
}
