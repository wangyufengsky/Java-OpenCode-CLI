package com.sonnet.wyf.gitreport.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.runner.AgentBridgeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

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

    @Test
    void rollsBackEveryPublishedByteWhenApplyFailsAfterADeletion() throws Exception {
        Map<String, byte[]> previous = publishInitialArtifactSet();
        WorkflowArtifactWorkspace workspace = workspace(
                "run-delete-failure",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    @Override
                    public void afterDeletion(Path ignored) throws java.io.IOException {
                        throw new java.io.IOException("injected failure after deletion");
                    }
                }
        );
        writeReplacementBundle(workspace);

        assertThatThrownBy(() -> workspace.publish("weekly-report.md"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("injected failure after deletion");

        assertPublishedBytes(previous);
        assertThat(tempDir.resolve("new-only.txt")).doesNotExist();
        assertPublicationScratchRemoved(workspace);
    }

    @Test
    void rollsBackEveryPublishedByteAndRemovesNewArtifactsWhenApplyFailsAfterAReplacement() throws Exception {
        Map<String, byte[]> previous = publishInitialArtifactSet();
        WorkflowArtifactWorkspace workspace = workspace(
                "run-replacement-failure",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    @Override
                    public void afterReplacement(Path ignored) throws java.io.IOException {
                        throw new java.io.IOException("injected failure after replacement");
                    }
                }
        );
        writeReplacementBundle(workspace);

        assertThatThrownBy(() -> workspace.publish("weekly-report.md"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("injected failure after replacement");

        assertPublishedBytes(previous);
        assertThat(tempDir.resolve("new-only.txt")).doesNotExist();
        assertPublicationScratchRemoved(workspace);
    }

    @Test
    void seedCopyFailureRecordsFailedRunManifestWithoutPublishingAnything() throws Exception {
        Files.createSymbolicLink(tempDir.resolve("broken.txt"), tempDir.resolve("missing-target.txt"));

        assertThatThrownBy(() -> workspace("run-seed-failure", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symlink");

        assertThat(tempDir.resolve("runs/run-seed-failure/run-manifest.json"))
                .content().contains("\"state\" : \"FAILED\"");
        assertThat(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST)).doesNotExist();
    }

    @Test
    void bestEffortStartFailureDoesNotOverwriteAnExistingExecutionDirectory() throws Exception {
        Path existing = tempDir.resolve("runs/run-existing");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("run-manifest.json"), "unrelated");
        WorkflowRunRequest request = request("run-existing", false);

        WorkflowArtifactWorkspace.markStartFailedBestEffort(
                objectMapper, "test-chain", request, tempDir, "invalid configuration");

        assertThat(existing.resolve("run-manifest.json")).hasContent("unrelated");
    }

    @Test
    void repeatedPublishRestagesTheExactCurrentBundleWithoutStaleFiles() throws Exception {
        WorkflowArtifactWorkspace workspace = workspace("run-repeat", false);
        Files.writeString(workspace.bundleRoot().resolve("weekly-report.md"), "first");
        Files.writeString(workspace.bundleRoot().resolve("stale.txt"), "remove me");
        workspace.publish("weekly-report.md");

        Files.writeString(workspace.bundleRoot().resolve("weekly-report.md"), "second");
        Files.delete(workspace.bundleRoot().resolve("stale.txt"));
        workspace.publish("weekly-report.md");

        assertThat(tempDir.resolve("weekly-report.md")).hasContent("second");
        assertThat(tempDir.resolve("stale.txt")).doesNotExist();
        assertPublicationScratchRemoved(workspace);
    }

    @Test
    void rejectsASymlinkStableRootWithoutWritingItsTarget() throws Exception {
        Path external = Files.createDirectories(tempDir.resolve("external-stable"));
        Files.writeString(external.resolve("sentinel.txt"), "outside");
        Path linkedStable = tempDir.resolve("linked-stable");
        Files.createSymbolicLink(linkedStable, external);

        assertThatThrownBy(() -> WorkflowArtifactWorkspace.start(
                objectMapper, "test-chain", request("run-linked-root", false), linkedStable, false
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stable root").hasMessageContaining("symlink");

        assertThat(external.resolve("sentinel.txt")).hasContent("outside");
        assertThat(external.resolve("runs")).doesNotExist();
    }

    @Test
    void rejectsSymlinkedStableArtifactParentBeforeReadingOrWritingExternalFiles() throws Exception {
        WorkflowArtifactWorkspace initial = workspace("run-symlink-initial", false);
        Files.writeString(initial.bundleRoot().resolve("weekly-report.md"), "old report");
        Files.createDirectories(initial.bundleRoot().resolve("reports"));
        Files.writeString(initial.bundleRoot().resolve("reports/old.txt"), "old nested");
        initial.publish("weekly-report.md");
        byte[] manifestBefore = Files.readAllBytes(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST));

        Path external = Files.createDirectories(tempDir.resolve("external-parent"));
        Files.writeString(external.resolve("old.txt"), "outside old");
        Files.delete(tempDir.resolve("reports/old.txt"));
        Files.delete(tempDir.resolve("reports"));
        Files.createSymbolicLink(tempDir.resolve("reports"), external);
        WorkflowArtifactWorkspace replacement = workspace("run-symlink-replacement", false);
        Files.writeString(replacement.bundleRoot().resolve("weekly-report.md"), "new report");
        Files.createDirectories(replacement.bundleRoot().resolve("reports"));
        Files.writeString(replacement.bundleRoot().resolve("reports/new.txt"), "new nested");

        assertThatThrownBy(() -> replacement.publish("weekly-report.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symlink");

        assertThat(tempDir.resolve("weekly-report.md")).hasContent("old report");
        assertThat(Files.readAllBytes(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST)))
                .isEqualTo(manifestBefore);
        assertThat(tempDir.resolve("reports")).isSymbolicLink();
        assertThat(external.resolve("old.txt")).hasContent("outside old");
        assertThat(external.resolve("new.txt")).doesNotExist();
        assertPublicationScratchRemoved(replacement);
    }

    @Test
    void rollbackRemovesInjectedSymlinkParentWithoutTouchingItsExternalTarget() throws Exception {
        Map<String, byte[]> previous = publishInitialArtifactSet();
        Path external = Files.createDirectories(tempDir.resolve("external-rollback"));
        Files.writeString(external.resolve("sentinel.txt"), "outside");
        WorkflowArtifactWorkspace workspace = workspace(
                "run-symlink-rollback",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    private boolean injected;

                    @Override
                    public void afterReplacement(Path path) throws java.io.IOException {
                        if (!injected && path.endsWith("reports/new.txt")) {
                            injected = true;
                            Files.delete(path);
                            Files.delete(path.getParent());
                            Files.createSymbolicLink(path.getParent(), external);
                            throw new java.io.IOException("injected symlink during rollback");
                        }
                    }
                }
        );
        writeReplacementBundle(workspace);
        Files.createDirectories(workspace.bundleRoot().resolve("reports"));
        Files.writeString(workspace.bundleRoot().resolve("reports/new.txt"), "new nested");

        assertThatThrownBy(() -> workspace.publish("weekly-report.md"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("injected symlink during rollback");

        assertPublishedBytes(previous);
        assertThat(tempDir.resolve("reports")).doesNotExist();
        assertThat(external.resolve("sentinel.txt")).hasContent("outside");
        assertThat(external.resolve("new.txt")).doesNotExist();
        assertPublicationScratchRemoved(workspace);
    }

    private WorkflowArtifactWorkspace workspace(String executionId, boolean seed) throws Exception {
        WorkflowRunRequest request = request(executionId, seed);
        return WorkflowArtifactWorkspace.start(objectMapper, "test-chain", request, tempDir, seed);
    }

    private WorkflowRunRequest request(String executionId, boolean seed) {
        return new WorkflowRunRequest(
                seed ? "rerun" : "full",
                "",
                "",
                LocalDate.of(2026, 7, 20),
                new AgentBridgeSettings(),
                "",
                executionId,
                null
        );
    }

    private WorkflowArtifactWorkspace workspace(
            String executionId,
            boolean seed,
            WorkflowArtifactWorkspace.PublicationFailureInjector injector
    ) throws Exception {
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
        return WorkflowArtifactWorkspace.start(
                objectMapper, "test-chain", request, tempDir, seed, injector
        );
    }

    private Map<String, byte[]> publishInitialArtifactSet() throws Exception {
        WorkflowArtifactWorkspace initial = workspace("run-initial", false);
        Files.writeString(initial.bundleRoot().resolve("weekly-report.md"), "old report");
        Files.writeString(initial.bundleRoot().resolve("replace.txt"), "old replacement");
        Files.writeString(initial.bundleRoot().resolve("old-only.txt"), "old only");
        initial.publish("weekly-report.md");
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (String relative : java.util.List.of(
                "weekly-report.md", "replace.txt", "old-only.txt",
                WorkflowArtifactWorkspace.PUBLICATION_MANIFEST
        )) {
            bytes.put(relative, Files.readAllBytes(tempDir.resolve(relative)));
        }
        return bytes;
    }

    private void writeReplacementBundle(WorkflowArtifactWorkspace workspace) throws Exception {
        Files.writeString(workspace.bundleRoot().resolve("weekly-report.md"), "new report");
        Files.writeString(workspace.bundleRoot().resolve("replace.txt"), "new replacement");
        Files.writeString(workspace.bundleRoot().resolve("new-only.txt"), "new only");
    }

    private void assertPublishedBytes(Map<String, byte[]> expected) throws Exception {
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            assertThat(Files.readAllBytes(tempDir.resolve(entry.getKey())))
                    .as(entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    private void assertPublicationScratchRemoved(WorkflowArtifactWorkspace workspace) {
        assertThat(workspace.runRoot().resolve("publication/staged")).doesNotExist();
        assertThat(workspace.runRoot().resolve("publication/backup")).doesNotExist();
    }
}
