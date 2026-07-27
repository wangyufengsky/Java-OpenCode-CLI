package com.sonnet.wyf.gitreport.failure;

public enum WorkflowFailureCategory {
    SESSION_EXECUTION,
    SESSION_TIMEOUT,
    OUTPUT_VALIDATION,
    SAFETY_VIOLATION,
    FILE_INTEGRITY_VIOLATION,
    TASK_CONFIGURATION,
    TASK_INTERRUPTED,
    RETRY_EXHAUSTED
}
