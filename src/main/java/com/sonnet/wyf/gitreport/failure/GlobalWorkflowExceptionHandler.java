package com.sonnet.wyf.gitreport.failure;

public final class GlobalWorkflowExceptionHandler {

    public WorkflowFailureException sessionFailure(
            WorkflowFailureCategory category,
            Throwable throwable
    ) {
        WorkflowFailureException classified = findClassifiedFailure(throwable);
        if (classified != null) {
            return classified;
        }
        if (containsInterruption(throwable)) {
            Thread.currentThread().interrupt();
            return WorkflowFailureException.task(
                    WorkflowFailureCategory.TASK_INTERRUPTED,
                    messageOf(throwable, "Workflow task interrupted"),
                    throwable
            );
        }
        return WorkflowFailureException.session(
                category,
                messageOf(throwable, category.name()),
                throwable
        );
    }

    public WorkflowFailureException retryExhausted(String message, Throwable cause) {
        return WorkflowFailureException.task(
                WorkflowFailureCategory.RETRY_EXHAUSTED,
                message,
                cause
        );
    }

    private WorkflowFailureException findClassifiedFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WorkflowFailureException failure) {
                return failure;
            }
            current = current.getCause();
        }
        return null;
    }

    private boolean containsInterruption(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String messageOf(Throwable throwable, String fallback) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return fallback;
        }
        return throwable.getMessage();
    }
}
