package com.sonnet.wyf.gitreport.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowTaskIndexTest {
    @Test
    void selectsGitAuthorTaskByAuthorKey() {
        WorkflowTaskIndex index = WorkflowTaskIndex.fromIndexInputs(Map.of(
                "tasks",
                List.of(
                        Map.of("author_key", "author-001", "author", "Alice"),
                        Map.of("author_key", "author-002", "author", "Bob")
                )
        ));

        assertThat(index.gitAuthorTask("author-002").get("author")).isEqualTo("Bob");
    }

    @Test
    void selectsSmartEsbTaskByReviewTypeAndName() {
        WorkflowTaskIndex index = WorkflowTaskIndex.fromIndexInputs(Map.of(
                "tasks",
                List.of(
                        Map.of("review_type", "transaction", "transaction", "Alpha"),
                        Map.of("review_type", "module", "module", "BaseModule")
                )
        ));

        assertThat(index.smartEsbReviewTask("module", "BaseModule").get("module")).isEqualTo("BaseModule");
    }

    @Test
    void keepsExplicitGitMissingAuthorMessage() {
        WorkflowTaskIndex index = WorkflowTaskIndex.fromIndexInputs(Map.of("tasks", List.of()));

        assertThatThrownBy(() -> index.gitAuthorTask("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("author task not found in index_inputs.json: missing");
    }

    @Test
    void keepsExplicitSmartEsbMissingTaskMessage() {
        WorkflowTaskIndex index = WorkflowTaskIndex.fromIndexInputs(Map.of("tasks", List.of()));

        assertThatThrownBy(() -> index.smartEsbReviewTask("transaction", "MissingTx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("transaction task missing from index_inputs.json: MissingTx");
    }
}
