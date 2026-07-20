package com.sonnet.wyf.gitreport.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.runner.AgentBridgeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowArtifactWorkspaceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void isolatesRunsTasksAndAttempts() throws Exception {
        WorkflowArtifactWorkspace first = workspace("run-first", false);
        WorkflowArtifactWorkspace second = workspace("run-second", false);

        TaskArtifactLayout firstAttempt = first.nextTaskAttempt("transaction:../../Same Name");
        TaskArtifactLayout secondAttempt = first.nextTaskAttempt("transaction:../../Same Name");
        TaskArtifactLayout otherRun = second.nextTaskAttempt("transaction:../../Same Name");

        assertThat(first.runRoot()).isNotEqualTo(second.runRoot());
        assertThat(firstAttempt.root()).isNotEqualTo(secondAttempt.root());
        assertThat(firstAttempt.root()).isNotEqualTo(otherRun.root());
        assertThat(firstAttempt.root()).startsWith(first.runRoot());
        assertThat(firstAttempt.root().toString()).doesNotContain("..");
        assertThat(firstAttempt.attempt()).isEqualTo(1);
        assertThat(secondAttempt.attempt()).isEqualTo(2);
    }

    @Test
    void rerunSeedsStableArtifactsButNotPriorRunDirectories() throws Exception {
        Files.writeString(tempDir.resolve("weekly-report.md"), "published");
        Files.createDirectories(tempDir.resolve("runs/old"));
        Files.writeString(tempDir.resolve("runs/old/status.json"), "old");

        WorkflowArtifactWorkspace workspace = workspace("run-rerun", true);

        assertThat(workspace.bundleRoot().resolve("weekly-report.md")).hasContent("published");
        assertThat(workspace.bundleRoot().resolve("runs")).doesNotExist();
    }

    @Test
    void newerExecutionPublishesAndOlderExecutionBecomesSuperseded() throws Exception {
        WorkflowArtifactWorkspace older = workspace("run-older", false);
        Files.writeString(older.bundleRoot().resolve("weekly-report.md"), "older");
        WorkflowArtifactWorkspace newer = workspace("run-newer", false);
        Files.writeString(newer.bundleRoot().resolve("weekly-report.md"), "newer");

        WorkflowArtifactWorkspace.PublicationResult newerResult = newer.publish("weekly-report.md");
        WorkflowArtifactWorkspace.PublicationResult olderResult = older.publish("weekly-report.md");

        assertThat(newerResult.published()).isTrue();
        assertThat(olderResult.superseded()).isTrue();
        assertThat(tempDir.resolve("weekly-report.md")).hasContent("newer");
        assertThat(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST))
                .content().contains("\"executionId\" : \"run-newer\"");
        assertThat(older.runRoot().resolve("run-manifest.json"))
                .content().contains("\"state\" : \"SUPERSEDED\"");
    }

    @Test
    void rerunRebindsEmbeddedPathsAndPublicationRestoresStablePaths() throws Exception {
        Files.writeString(tempDir.resolve("task.json"), """
                {"output":"%s/report.md"}
                """.formatted(tempDir));

        WorkflowArtifactWorkspace workspace = workspace("run-rebase", true);

        assertThat(workspace.bundleRoot().resolve("task.json"))
                .content().contains(workspace.bundleRoot().toString()).doesNotContain(tempDir + "/report.md");
        Files.writeString(workspace.bundleRoot().resolve("report.md"), "# published\n");
        workspace.publish("report.md");

        assertThat(tempDir.resolve("task.json"))
                .content().contains(tempDir + "/report.md").doesNotContain(workspace.bundleRoot().toString());
    }

    @Test
    void doesNotPublishWhenMainArtifactIsMissing() throws Exception {
        WorkflowArtifactWorkspace workspace = workspace("run-missing", false);

        assertThatThrownBy(() -> workspace.publish("index.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("main artifact is missing");
        assertThat(tempDir.resolve("index.md")).doesNotExist();
    }

    private WorkflowArtifactWorkspace workspace(String executionId, boolean seed) throws Exception {
        WorkflowRunRequest request = new WorkflowRunRequest(
                seed ? "rerun" : "full",
                "",
                "",
                LocalDate.of(2026, 7, 20),
                new AgentBridgeSettings(),
                "",
                executionId,
                null
        );
        return WorkflowArtifactWorkspace.start(objectMapper, "test-chain", request, tempDir, seed);
    }
}
