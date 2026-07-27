package com.sonnet.wyf.gitreport.failure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalWorkflowExceptionHandlerTest {
    private final GlobalWorkflowExceptionHandler handler = new GlobalWorkflowExceptionHandler();

    @Test
    void classifiesSafetyViolationAsSessionFailure() {
        WorkflowFailureException failure = handler.sessionFailure(
                WorkflowFailureCategory.SAFETY_VIOLATION,
                new IllegalStateException("unapproved database tool")
        );

        assertThat(failure.scope()).isEqualTo(WorkflowFailureScope.SESSION);
        assertThat(failure.category()).isEqualTo(WorkflowFailureCategory.SAFETY_VIOLATION);
        assertThat(failure).hasMessage("unapproved database tool");
    }

    @Test
    void classifiesFileIntegrityViolationAsSessionFailure() {
        WorkflowFailureException failure = handler.sessionFailure(
                WorkflowFailureCategory.FILE_INTEGRITY_VIOLATION,
                new IllegalStateException("modified protected file")
        );

        assertThat(failure.scope()).isEqualTo(WorkflowFailureScope.SESSION);
        assertThat(failure.category()).isEqualTo(WorkflowFailureCategory.FILE_INTEGRITY_VIOLATION);
        assertThat(failure).hasMessage("modified protected file");
    }

    @Test
    void preservesExplicitTaskFailure() {
        WorkflowFailureException expected = WorkflowFailureException.task(
                WorkflowFailureCategory.TASK_CONFIGURATION,
                "invalid workflow configuration"
        );

        assertThat(handler.sessionFailure(WorkflowFailureCategory.SESSION_EXECUTION, expected))
                .isSameAs(expected);
    }
}
