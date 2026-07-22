package com.sonnet.wyf.gitreport.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.runner.AgentBridgeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void crashAfterGenerationPrepareLeavesOnlyTheOldGenerationVisible() throws Exception {
        publishInitialArtifactSet();
        WorkflowArtifactWorkspace workspace = workspace(
                "run-crash-after-prepare",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    @Override
                    public void afterGenerationPrepared(Path ignored) throws java.io.IOException {
                        throw new java.io.IOException("simulated crash after generation prepare");
                    }
                }
        );
        writeReplacementBundle(workspace);

        assertThatThrownBy(() -> workspace.publish("weekly-report.md"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("simulated crash after generation prepare");

        assertThat(tempDir.resolve("weekly-report.md")).hasContent("old report");
        assertThat(tempDir.resolve("replace.txt")).hasContent("old replacement");
        assertThat(tempDir.resolve("old-only.txt")).hasContent("old only");
        assertThat(tempDir.resolve("new-only.txt")).doesNotExist();
    }

    @Test
    void failureAfterPointerSwitchStillReportsTheCommittedNewGenerationAsPublished() throws Exception {
        publishInitialArtifactSet();
        WorkflowArtifactWorkspace workspace = workspace(
                "run-crash-after-switch",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    @Override
                    public void afterPointerSwitch(Path ignored) throws java.io.IOException {
                        throw new java.io.IOException("simulated crash after pointer switch");
                    }
                }
        );
        writeReplacementBundle(workspace);

        WorkflowArtifactWorkspace.PublicationResult result = workspace.publish("weekly-report.md");

        assertThat(result.published()).isTrue();
        assertThat(tempDir.resolve("weekly-report.md")).hasContent("new report");
        assertThat(tempDir.resolve("replace.txt")).hasContent("new replacement");
        assertThat(tempDir.resolve("new-only.txt")).hasContent("new only");
        assertThat(tempDir.resolve("old-only.txt")).doesNotExist();
        assertThat(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST))
                .content().contains("\"executionId\" : \"run-crash-after-switch\"");
    }

    @Test
    void firstPublishRollsBackForwardersWhenFailureOccursBeforeTheCommitPoint() throws Exception {
        WorkflowArtifactWorkspace workspace = workspace(
                "run-first-switch-failure",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    @Override
                    public void beforePointerSwitch(Path ignored) throws java.io.IOException {
                        throw new java.io.IOException("failure before first pointer switch");
                    }
                }
        );
        Files.writeString(workspace.bundleRoot().resolve("weekly-report.md"), "new report");
        Files.writeString(workspace.bundleRoot().resolve("nested.txt"), "new nested");

        assertThatThrownBy(() -> workspace.publish("weekly-report.md"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("failure before first pointer switch");

        assertThat(tempDir.resolve("weekly-report.md")).doesNotExist();
        assertThat(tempDir.resolve("nested.txt")).doesNotExist();
        assertThat(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST)).doesNotExist();
        assertThat(tempDir.resolve(".published/current")).doesNotExist();
    }

    @Test
    void legacyMigrationRejectsUnlistedDescendantsBeforeChangingTheStableNamespace() throws Exception {
        Files.writeString(tempDir.resolve("weekly-report.md"), "old report");
        Files.createDirectories(tempDir.resolve("reports"));
        Files.writeString(tempDir.resolve("reports/listed.txt"), "listed");
        Files.writeString(tempDir.resolve("reports/unlisted.txt"), "must survive");
        writeLegacyManifest(List.of("weekly-report.md", "reports/listed.txt"));
        byte[] manifestBefore = Files.readAllBytes(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST));
        WorkflowArtifactWorkspace workspace = workspace("run-legacy-extra", false);
        Files.writeString(workspace.bundleRoot().resolve("weekly-report.md"), "new report");

        assertThatThrownBy(() -> workspace.publish("weekly-report.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unlisted legacy artifact")
                .hasMessageContaining("reports/unlisted.txt");

        assertThat(tempDir.resolve("reports")).isDirectory();
        assertThat(Files.isSymbolicLink(tempDir.resolve("reports"))).isFalse();
        assertThat(tempDir.resolve("reports/listed.txt")).hasContent("listed");
        assertThat(tempDir.resolve("reports/unlisted.txt")).hasContent("must survive");
        assertThat(tempDir.resolve("weekly-report.md")).hasContent("old report");
        assertThat(Files.readAllBytes(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST)))
                .isEqualTo(manifestBefore);
        assertThat(tempDir.resolve(".published/current")).doesNotExist();
    }

    @Test
    void resumesLegacyMigrationAfterCurrentSwitchedBeforeRootForwardersWereInstalled() throws Exception {
        Files.writeString(tempDir.resolve("weekly-report.md"), "old report");
        writeLegacyManifest(List.of("weekly-report.md"));
        WorkflowArtifactWorkspace interrupted = workspace(
                "run-legacy-interrupted",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    @Override
                    public void afterLegacyPointerSwitch(Path ignored) throws java.io.IOException {
                        throw new java.io.IOException("legacy process stopped after pointer switch");
                    }
                }
        );
        Files.writeString(interrupted.bundleRoot().resolve("weekly-report.md"), "first replacement");

        assertThatThrownBy(() -> interrupted.publish("weekly-report.md"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("legacy process stopped after pointer switch");
        assertThat(tempDir.resolve(".published/current")).isSymbolicLink();
        assertThat(tempDir.resolve("weekly-report.md")).isRegularFile();
        assertThat(Files.isSymbolicLink(tempDir.resolve("weekly-report.md"))).isFalse();

        Files.delete(tempDir.resolve("weekly-report.md"));
        WorkflowArtifactWorkspace recovered = workspace("run-legacy-recovered", false);
        Files.writeString(recovered.bundleRoot().resolve("weekly-report.md"), "recovered replacement");

        WorkflowArtifactWorkspace.PublicationResult result = recovered.publish("weekly-report.md");

        assertThat(result.published()).isTrue();
        assertThat(tempDir.resolve("weekly-report.md")).isSymbolicLink().hasContent("recovered replacement");
        assertThat(tempDir.resolve(".published/legacy-migration")).doesNotExist();
    }

    @Test
    void resumesLegacyMigrationAfterOnlySomeRootForwardersWereInstalled() throws Exception {
        Files.writeString(tempDir.resolve("weekly-report.md"), "old report");
        Files.createDirectories(tempDir.resolve("reports"));
        Files.writeString(tempDir.resolve("reports/listed.txt"), "old nested");
        writeLegacyManifest(List.of("weekly-report.md", "reports/listed.txt"));
        WorkflowArtifactWorkspace interrupted = workspace(
                "run-legacy-partial-roots",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    @Override
                    public void afterLegacyRootReplacement(Path stableRootEntry) throws java.io.IOException {
                        throw new java.io.IOException("legacy process stopped after one root replacement");
                    }
                }
        );
        Files.writeString(interrupted.bundleRoot().resolve("weekly-report.md"), "first replacement");

        assertThatThrownBy(() -> interrupted.publish("weekly-report.md"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("stopped after one root replacement");
        assertThat(tempDir.resolve(".published/current")).isSymbolicLink();
        assertThat(tempDir.resolve(".published/legacy-migration")).isRegularFile();
        assertThat(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST)).isSymbolicLink();
        assertThat(tempDir.resolve("reports")).isDirectory();
        assertThat(Files.isSymbolicLink(tempDir.resolve("reports"))).isFalse();

        boolean[] recoveredReportsRoot = {false};
        WorkflowArtifactWorkspace recovered = workspace(
                "run-legacy-partial-recovered",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    @Override
                    public void afterLegacyRootReplacement(Path stableRootEntry) {
                        if (stableRootEntry.getFileName().toString().equals("reports")) {
                            recoveredReportsRoot[0] = true;
                        }
                    }
                }
        );
        Files.writeString(recovered.bundleRoot().resolve("weekly-report.md"), "recovered replacement");

        assertThat(recovered.publish("weekly-report.md").published()).isTrue();
        assertThat(recoveredReportsRoot[0]).isTrue();
        assertThat(tempDir.resolve("weekly-report.md")).isSymbolicLink().hasContent("recovered replacement");
        assertThat(tempDir.resolve("reports")).doesNotExist();
        assertThat(tempDir.resolve(".published/legacy-migration")).doesNotExist();
    }

    @Test
    void rejectsAnUnmanagedLegacyManifestSymlinkBeforeCreatingPublicationNamespace() throws Exception {
        Path external = tempDir.resolve("external-manifest.json");
        Files.writeString(external, "{}");
        Files.writeString(tempDir.resolve("weekly-report.md"), "old report");
        Files.createSymbolicLink(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST), external);
        WorkflowArtifactWorkspace workspace = workspace("run-legacy-manifest-symlink", false);
        Files.writeString(workspace.bundleRoot().resolve("weekly-report.md"), "new report");

        assertThatThrownBy(() -> workspace.publish("weekly-report.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legacy publication manifest")
                .hasMessageContaining("regular non-symlink file");

        assertThat(tempDir.resolve("weekly-report.md")).hasContent("old report");
        assertThat(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST)).isSymbolicLink();
        assertThat(tempDir.resolve(".published")).doesNotExist();
        assertThat(external).hasContent("{}");
    }

    @Test
    void nonMyBatisChainKeepsLegacyPublicationWhenSymbolicLinksAreUnavailable() throws Exception {
        WorkflowArtifactWorkspace workspace = workspace(
                "run-no-symlink-shared-chain",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    @Override
                    public Boolean symbolicLinkSupportOverride() {
                        return false;
                    }
                }
        );
        Files.writeString(workspace.bundleRoot().resolve("weekly-report.md"), "legacy backend");

        WorkflowArtifactWorkspace.PublicationResult result = workspace.publish("weekly-report.md");

        assertThat(result.published()).isTrue();
        assertThat(tempDir.resolve("weekly-report.md")).isRegularFile().hasContent("legacy backend");
        assertThat(Files.isSymbolicLink(tempDir.resolve("weekly-report.md"))).isFalse();
        assertThat(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST))
                .isRegularFile();
        assertThat(Files.isSymbolicLink(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST))).isFalse();
    }

    @Test
    void directFallbackRollsBackFileToDirectoryShapeChange() throws Exception {
        WorkflowArtifactWorkspace initial = noSymlinkWorkspace("run-direct-file-initial");
        Files.writeString(initial.bundleRoot().resolve("artifact"), "old file");
        initial.publish("artifact");
        byte[] manifestBefore = Files.readAllBytes(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST));
        WorkflowArtifactWorkspace replacement = workspace(
                "run-direct-file-to-directory",
                false,
                failingDirectReplacementInjector()
        );
        Files.createDirectories(replacement.bundleRoot().resolve("artifact"));
        Files.writeString(replacement.bundleRoot().resolve("artifact/child.txt"), "new child");

        assertThatThrownBy(() -> replacement.publish("artifact/child.txt"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("direct replacement failure");

        assertThat(tempDir.resolve("artifact")).isRegularFile().hasContent("old file");
        assertThat(Files.readAllBytes(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST)))
                .isEqualTo(manifestBefore);
        assertPublicationScratchRemoved(replacement);
    }

    @Test
    void directFallbackRollsBackDirectoryToFileShapeChange() throws Exception {
        WorkflowArtifactWorkspace initial = noSymlinkWorkspace("run-direct-directory-initial");
        Files.createDirectories(initial.bundleRoot().resolve("artifact"));
        Files.writeString(initial.bundleRoot().resolve("artifact/child.txt"), "old child");
        initial.publish("artifact/child.txt");
        byte[] manifestBefore = Files.readAllBytes(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST));
        WorkflowArtifactWorkspace replacement = workspace(
                "run-direct-directory-to-file",
                false,
                failingDirectReplacementInjector()
        );
        Files.writeString(replacement.bundleRoot().resolve("artifact"), "new file");

        assertThatThrownBy(() -> replacement.publish("artifact"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("direct replacement failure");

        assertThat(tempDir.resolve("artifact")).isDirectory();
        assertThat(tempDir.resolve("artifact/child.txt")).hasContent("old child");
        assertThat(Files.readAllBytes(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST)))
                .isEqualTo(manifestBefore);
        assertPublicationScratchRemoved(replacement);
    }

    @Test
    void directManifestReplaceIsTheCommitPointAndLaterFailureIsBestEffort() throws Exception {
        boolean[] afterCommitCalled = {false};
        WorkflowArtifactWorkspace workspace = workspace(
                "run-direct-after-commit",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    @Override
                    public Boolean symbolicLinkSupportOverride() {
                        return false;
                    }

                    @Override
                    public void afterDirectManifestCommit(Path ignored) throws java.io.IOException {
                        afterCommitCalled[0] = true;
                        throw new java.io.IOException("failure after direct manifest commit");
                    }
                }
        );
        Files.writeString(workspace.bundleRoot().resolve("weekly-report.md"), "committed direct report");

        WorkflowArtifactWorkspace.PublicationResult result = workspace.publish("weekly-report.md");

        assertThat(afterCommitCalled[0]).isTrue();
        assertThat(result.published()).isTrue();
        assertThat(tempDir.resolve("weekly-report.md")).hasContent("committed direct report");
        assertThat(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST))
                .content().contains("run-direct-after-commit");
        assertPublicationScratchRemoved(workspace);
    }

    @Test
    void directScratchCleanupFailureAfterCommitDoesNotTurnPublishIntoFailure() throws Exception {
        boolean[] cleanupAttempted = {false};
        WorkflowArtifactWorkspace workspace = workspace(
                "run-direct-cleanup-failure",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    @Override
                    public Boolean symbolicLinkSupportOverride() {
                        return false;
                    }

                    @Override
                    public void beforeDirectScratchCleanup(Path ignored) throws java.io.IOException {
                        cleanupAttempted[0] = true;
                        throw new java.io.IOException("direct scratch cleanup unavailable");
                    }
                }
        );
        Files.writeString(workspace.bundleRoot().resolve("weekly-report.md"), "committed despite cleanup");

        WorkflowArtifactWorkspace.PublicationResult result = workspace.publish("weekly-report.md");

        assertThat(cleanupAttempted[0]).isTrue();
        assertThat(result.published()).isTrue();
        assertThat(tempDir.resolve("weekly-report.md")).hasContent("committed despite cleanup");
        assertThat(workspace.runRoot().resolve("publication/staged")).exists();
    }

    @Test
    void myBatisChainFailsClosedBeforePublishingWhenSymbolicLinksAreUnavailable() throws Exception {
        Files.writeString(tempDir.resolve(".workflow-generation"), "41");
        Files.createDirectories(tempDir.resolve("runs/old-run"));
        Files.writeString(tempDir.resolve("runs/old-run/run-manifest.json"), "published old run");

        assertThatThrownBy(() -> workspace(
                "mybatis-sql-review",
                "run-no-symlink-mybatis",
                false,
                noSymlinkInjector()
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires crash-atomic generation publication")
                .hasMessageContaining("symbolic links");

        assertThat(tempDir.resolve(".workflow-generation")).hasContent("41");
        assertThat(tempDir.resolve("runs/run-no-symlink-mybatis")).doesNotExist();
        assertThat(tempDir.resolve("runs/old-run/run-manifest.json")).hasContent("published old run");
        assertThat(tempDir.resolve("mybatis-sql-review-report.md")).doesNotExist();
        assertThat(tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST)).doesNotExist();
    }

    @Test
    void capabilityProbeExercisesSymlinkAtomicReplaceAndDirectoryFsyncBeforeGenerationAllocation() throws Exception {
        List<String> steps = new java.util.ArrayList<>();
        WorkflowArtifactWorkspace workspace = workspace(
                "run-capability-probe",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    @Override
                    public void afterCapabilityProbeStep(String step, Path ignored) {
                        steps.add(step);
                        assertThat(tempDir.resolve(".workflow-generation")).doesNotExist();
                    }
                }
        );

        assertThat(steps).containsExactly("symlink-create", "atomic-replace", "directory-fsync");
        assertThat(workspace.publicationGeneration()).isEqualTo(1L);
    }

    @Test
    void myBatisStartFailsBeforeGenerationAllocationWhenAnyCapabilityProbeStepFails() throws Exception {
        for (String failedStep : List.of("symlink-create", "atomic-replace", "directory-fsync")) {
            String executionId = "run-capability-failure-" + failedStep;

            assertThatThrownBy(() -> workspace(
                    "mybatis-sql-review",
                    executionId,
                    false,
                    new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                        @Override
                        public void afterCapabilityProbeStep(String step, Path ignored) throws java.io.IOException {
                            if (step.equals(failedStep)) {
                                throw new java.io.IOException("failed capability step " + failedStep);
                            }
                        }
                    }
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires crash-atomic generation publication");

            assertThat(tempDir.resolve(".publication-generation")).doesNotExist();
            assertThat(tempDir.resolve("runs").resolve(executionId)).doesNotExist();
        }
    }

    @Test
    void startupRemovesStaleForwarderScratchWithoutFollowingIt() throws Exception {
        Path external = Files.createDirectories(tempDir.resolve("external-scratch-target"));
        Files.writeString(external.resolve("sentinel.txt"), "outside");
        Path scratch = Files.createDirectories(tempDir.resolve(".published/scratch"));
        Files.createSymbolicLink(scratch.resolve("stale-forwarder"), external);

        workspace("run-clean-forwarder-scratch", false);

        assertThat(scratch).doesNotExist();
        assertThat(external.resolve("sentinel.txt")).hasContent("outside");
    }

    @Test
    void legacyMigrationRecoversTargetMovedBeforeForwarderInstall() throws Exception {
        Files.writeString(tempDir.resolve("weekly-report.md"), "old report");
        Files.createDirectories(tempDir.resolve("reports"));
        Files.writeString(tempDir.resolve("reports/listed.txt"), "old nested");
        writeLegacyManifest(List.of("weekly-report.md", "reports/listed.txt"));
        boolean[] sawScratchForwarder = {false};
        WorkflowArtifactWorkspace interrupted = workspace(
                "run-legacy-half-step",
                false,
                new WorkflowArtifactWorkspace.PublicationFailureInjector() {
                    @Override
                    public void afterLegacyTargetMovedBeforeForwarderInstall(Path stableEntry, Path ignoredBackup)
                            throws java.io.IOException {
                        if (stableEntry.getFileName().toString().equals("reports")) {
                            Path scratch = tempDir.resolve(".published/scratch");
                            try (var entries = Files.list(scratch)) {
                                sawScratchForwarder[0] = entries.anyMatch(Files::isSymbolicLink);
                            }
                            throw new java.io.IOException("stopped between legacy move and forwarder install");
                        }
                    }
                }
        );
        Files.writeString(interrupted.bundleRoot().resolve("weekly-report.md"), "first replacement");

        assertThatThrownBy(() -> interrupted.publish("weekly-report.md"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("between legacy move and forwarder install");
        assertThat(sawScratchForwarder[0]).isTrue();
        assertThat(tempDir.resolve("reports")).doesNotExist();
        assertThat(tempDir.resolve(".published/migration-backup/reports/listed.txt")).hasContent("old nested");
        assertThat(tempDir.resolve(".published/legacy-migration")).isRegularFile();

        WorkflowArtifactWorkspace recovered = workspace("run-legacy-half-step-recovered", false);
        Files.writeString(recovered.bundleRoot().resolve("weekly-report.md"), "recovered report");
        Files.createDirectories(recovered.bundleRoot().resolve("reports"));
        Files.writeString(recovered.bundleRoot().resolve("reports/new.txt"), "recovered nested");

        assertThat(recovered.publish("weekly-report.md").published()).isTrue();
        assertThat(tempDir.resolve("reports")).isSymbolicLink();
        assertThat(tempDir.resolve("reports/new.txt")).hasContent("recovered nested");
        assertThat(tempDir.resolve(".published/migration-backup")).doesNotExist();
        assertThat(tempDir.resolve(".published/legacy-migration")).doesNotExist();
    }

    @Test
    void committedGenerationIsReadOnlyAndSeedRejectsDigestTampering() throws Exception {
        publishInitialArtifactSet();
        Path currentPointer = tempDir.resolve(".published/current");
        Path generation = currentPointer.getParent().resolve(Files.readSymbolicLink(currentPointer)).normalize();
        assumeTrue(Files.getFileStore(generation).supportsFileAttributeView("posix"));

        assertThat(Files.getPosixFilePermissions(generation))
                .doesNotContain(PosixFilePermission.OWNER_WRITE);
        assertThat(Files.getPosixFilePermissions(generation.resolve("weekly-report.md")))
                .doesNotContain(PosixFilePermission.OWNER_WRITE);

        Files.setPosixFilePermissions(generation, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Files.setPosixFilePermissions(generation.resolve("weekly-report.md"), Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        Files.writeString(generation.resolve("weekly-report.md"), "tampered");

        assertThatThrownBy(() -> workspace("run-seed-tampered", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest mismatch")
                .hasMessageContaining("weekly-report.md");
    }

    @Test
    void concurrentPublishSerializesInJvmAndOnlyNewestExecutionCommits() throws Exception {
        WorkflowArtifactWorkspace older = workspace("run-concurrent-older", false);
        Files.writeString(older.bundleRoot().resolve("weekly-report.md"), "older");
        WorkflowArtifactWorkspace newer = workspace("run-concurrent-newer", false);
        Files.writeString(newer.bundleRoot().resolve("weekly-report.md"), "newer");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<WorkflowArtifactWorkspace.PublicationResult> olderFuture = executor.submit(() -> {
                start.await();
                return older.publish("weekly-report.md");
            });
            Future<WorkflowArtifactWorkspace.PublicationResult> newerFuture = executor.submit(() -> {
                start.await();
                return newer.publish("weekly-report.md");
            });
            start.countDown();

            assertThat(olderFuture.get().superseded()).isTrue();
            assertThat(newerFuture.get().published()).isTrue();
        }
        assertThat(tempDir.resolve("weekly-report.md")).hasContent("newer");
    }

    @Test
    void nextStartupRemovesAnOrphanGenerationWithoutTouchingTheCurrentGeneration() throws Exception {
        publishInitialArtifactSet();
        Path orphan = tempDir.resolve(".published/generations/orphan-from-crashed-prepare");
        Files.createDirectories(orphan);
        Files.writeString(orphan.resolve("partial.txt"), "partial");

        workspace("run-after-orphan", false);

        assertThat(orphan).doesNotExist();
        assertThat(tempDir.resolve("weekly-report.md")).hasContent("old report");
        assertThat(tempDir.resolve("replace.txt")).hasContent("old replacement");
        assertThat(tempDir.resolve("old-only.txt")).hasContent("old only");
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

    private WorkflowArtifactWorkspace noSymlinkWorkspace(String executionId) throws Exception {
        return workspace(executionId, false, noSymlinkInjector());
    }

    private WorkflowArtifactWorkspace.PublicationFailureInjector noSymlinkInjector() {
        return new WorkflowArtifactWorkspace.PublicationFailureInjector() {
            @Override
            public Boolean symbolicLinkSupportOverride() {
                return false;
            }
        };
    }

    private WorkflowArtifactWorkspace.PublicationFailureInjector failingDirectReplacementInjector() {
        return new WorkflowArtifactWorkspace.PublicationFailureInjector() {
            @Override
            public Boolean symbolicLinkSupportOverride() {
                return false;
            }

            @Override
            public void afterReplacement(Path ignored) throws java.io.IOException {
                throw new java.io.IOException("direct replacement failure");
            }
        };
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
        return workspace("test-chain", executionId, seed, injector);
    }

    private WorkflowArtifactWorkspace workspace(
            String chainId,
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
                objectMapper, chainId, request, tempDir, seed, injector
        );
    }

    private void writeLegacyManifest(List<String> artifactPaths) throws Exception {
        List<Map<String, String>> artifacts = artifactPaths.stream()
                .map(path -> {
                    try {
                        return Map.of("path", path, "sha256", sha256(tempDir.resolve(path)));
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .toList();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                tempDir.resolve(WorkflowArtifactWorkspace.PUBLICATION_MANIFEST).toFile(),
                Map.of(
                        "schemaVersion", "workflow-publication/v1",
                        "chainId", "test-chain",
                        "executionId", "legacy-run",
                        "publicationGeneration", 0,
                        "mainArtifact", "weekly-report.md",
                        "artifacts", artifacts
                )
        );
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
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
