package com.sonnet.wyf.gitreport.failure;

import java.util.Objects;

public final class WorkflowFailureException extends IllegalStateException {

    private final WorkflowFailureScope scope;
    private final WorkflowFailureCategory category;

    private WorkflowFailureException(
            WorkflowFailureScope scope,
            WorkflowFailureCategory category,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.scope = Objects.requireNonNull(scope, "scope");
        this.category = Objects.requireNonNull(category, "category");
    }

    public static WorkflowFailureException session(
            WorkflowFailureCategory category,
            String message
    ) {
        return session(category, message, null);
    }

    public static WorkflowFailureException session(
            WorkflowFailureCategory category,
            String message,
            Throwable cause
    ) {
        return new WorkflowFailureException(
                WorkflowFailureScope.SESSION,
                category,
                message,
                cause
        );
    }

    public static WorkflowFailureException task(
            WorkflowFailureCategory category,
            String message
    ) {
        return task(category, message, null);
    }

    public static WorkflowFailureException task(
            WorkflowFailureCategory category,
            String message,
            Throwable cause
    ) {
        return new WorkflowFailureException(
                WorkflowFailureScope.TASK,
                category,
                message,
                cause
        );
    }

    public WorkflowFailureScope scope() {
        return scope;
    }

    public WorkflowFailureCategory category() {
        return category;
    }
}
