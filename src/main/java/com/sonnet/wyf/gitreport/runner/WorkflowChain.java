package com.sonnet.wyf.gitreport.runner;

public interface WorkflowChain {
    String id();

    void run(WorkflowRunRequest request) throws Exception;
}
