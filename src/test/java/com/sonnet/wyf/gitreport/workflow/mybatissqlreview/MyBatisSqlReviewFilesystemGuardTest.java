package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisSqlReviewFilesystemGuardTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    @TempDir
    Path tempDir;

    @Test
    void leavesRepositoryStableAndCandidatePermissionsUnchanged() throws Exception {
        assumePosix(tempDir);
        Layout layout = layout(false);
        Set<PosixFilePermission> repoPermissions = permissions("rwxr-x---");
        Set<PosixFilePermission> sourcePermissions = permissions("rw-r-----");
        Set<PosixFilePermission> stablePermissions = permissions("rw-rw----");
        Set<PosixFilePermission> candidatePermissions = permissions("rwxr-x---");
        Files.setPosixFilePermissions(layout.repository(), repoPermissions);
        Files.setPosixFilePermissions(layout.source(), sourcePermissions);
        Files.setPosixFilePermissions(layout.stableFile(), stablePermissions);
        Files.setPosixFilePermissions(layout.candidate(), candidatePermissions);

        try (MyBatisSqlReviewFilesystemGuard ignored = MyBatisSqlReviewFilesystemGuard.protect(
                layout.repository(), layout.stableRoot(), layout.attemptRoot(), layout.candidate()
        )) {
            assertThat(Files.getPosixFilePermissions(layout.repository())).isEqualTo(repoPermissions);
            assertThat(Files.getPosixFilePermissions(layout.source())).isEqualTo(sourcePermissions);
            assertThat(Files.getPosixFilePermissions(layout.stableFile())).isEqualTo(stablePermissions);
            assertThat(Files.getPosixFilePermissions(layout.candidate())).isEqualTo(candidatePermissions);
        }

        assertThat(Files.getPosixFilePermissions(layout.repository())).isEqualTo(repoPermissions);
        assertThat(Files.getPosixFilePermissions(layout.source())).isEqualTo(sourcePermissions);
        assertThat(Files.getPosixFilePermissions(layout.stableFile())).isEqualTo(stablePermissions);
        assertThat(Files.getPosixFilePermissions(layout.candidate())).isEqualTo(candidatePermissions);
    }

    @Test
    void detectsAndRestoresSourceStableAndCandidateSiblingChangesBeforeFailing() throws Exception {
        assumePosix(tempDir);
        Layout layout = layout(true);
        byte[] sourceBefore = Files.readAllBytes(layout.source());
        byte[] stableBefore = Files.readAllBytes(layout.stableFile());
        Set<PosixFilePermission> sourcePermissions = Files.getPosixFilePermissions(layout.source());
        Set<PosixFilePermission> stablePermissions = Files.getPosixFilePermissions(layout.stableFile());
        MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protect(
                layout.repository(), layout.stableRoot(), layout.attemptRoot(), layout.candidate()
        );

        Files.writeString(layout.source(), "tampered source");
        Files.delete(layout.stableFile());
        Path sibling = layout.attemptRoot().resolve("agent-sibling.txt");
        Files.writeString(sibling, "unauthorized");

        assertThatThrownBy(guard::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protected filesystem content changed")
                .hasMessageContaining("agent-sibling.txt");
        assertThat(Files.readAllBytes(layout.source())).isEqualTo(sourceBefore);
        assertThat(Files.readAllBytes(layout.stableFile())).isEqualTo(stableBefore);
        assertThat(sibling).doesNotExist();
        assertThat(Files.getPosixFilePermissions(layout.source())).isEqualTo(sourcePermissions);
        assertThat(Files.getPosixFilePermissions(layout.stableFile())).isEqualTo(stablePermissions);
    }

    @Test
    void leavesPreexistingReadOnlyPermissionsUntouched() throws Exception {
        assumePosix(tempDir);
        Layout layout = layout(false);
        Set<PosixFilePermission> repositoryPermissions = permissions("r-xr-x---");
        Set<PosixFilePermission> stablePermissions = permissions("r-xr-x---");
        Files.setPosixFilePermissions(layout.repository(), repositoryPermissions);
        Files.setPosixFilePermissions(layout.stableRoot(), stablePermissions);
        try (MyBatisSqlReviewFilesystemGuard ignored = MyBatisSqlReviewFilesystemGuard.protect(
                layout.repository(), layout.stableRoot(), layout.attemptRoot(), layout.candidate()
        )) {
            assertThat(Files.getPosixFilePermissions(layout.repository())).isEqualTo(repositoryPermissions);
            assertThat(Files.getPosixFilePermissions(layout.stableRoot())).isEqualTo(stablePermissions);
        }
        assertThat(Files.getPosixFilePermissions(layout.repository())).isEqualTo(repositoryPermissions);
        assertThat(Files.getPosixFilePermissions(layout.stableRoot())).isEqualTo(stablePermissions);
    }

    @Test
    void rejectsExternalSymlinkAndNonRegularCandidateArtifacts() throws Exception {
        assumePosix(tempDir);
        Layout layout = layout(false);
        Path external = tempDir.resolve("external.md");
        Files.writeString(external, "outside");
        Files.createSymbolicLink(layout.candidate().resolve("report.md"), external);
        Files.writeString(layout.candidate().resolve("summary.json"), "{}");
        Files.createDirectory(layout.candidate().resolve("database-evidence.json"));

        assertThatThrownBy(() -> MyBatisSqlReviewFilesystemGuard.requireSafeCandidate(
                layout.attemptRoot(), layout.candidate()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-symlink regular files");
    }

    @Test
    void rejectsASymlinkedCandidateParentWithoutChangingPermissions() throws Exception {
        assumePosix(tempDir);
        Path repository = Files.createDirectories(tempDir.resolve("repo-symlink"));
        Path stable = Files.createDirectories(tempDir.resolve("stable-symlink"));
        Path attempt = Files.createDirectories(stable.resolve("runs/run/tasks/task/attempts/001"));
        Path externalCandidate = Files.createDirectories(tempDir.resolve("outside-candidate"));
        Path candidate = attempt.resolve("candidate");
        Files.createSymbolicLink(candidate, externalCandidate);
        Set<PosixFilePermission> before = Files.getPosixFilePermissions(stable);

        assertThatThrownBy(() -> MyBatisSqlReviewFilesystemGuard.protect(
                repository, stable, attempt, candidate
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symlinked candidate path");
        assertThat(Files.getPosixFilePermissions(stable)).isEqualTo(before);
    }

    @Test
    void supportsFilesystemsWithoutPosixPermissions() throws Exception {
        Path zip = tempDir.resolve("non-posix.zip");
        URI uri = URI.create("jar:" + zip.toUri());
        try (var fileSystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path repository = Files.createDirectories(fileSystem.getPath("/repo"));
            Path stable = Files.createDirectories(fileSystem.getPath("/stable"));
            Path attempt = Files.createDirectories(stable.resolve("runs/run/tasks/task/attempts/001"));
            Path candidate = Files.createDirectories(attempt.resolve("candidate"));

            assertThatCode(() -> {
                try (MyBatisSqlReviewFilesystemGuard ignored = MyBatisSqlReviewFilesystemGuard.protect(
                        repository, stable, attempt, candidate
                )) {
                    // Snapshot-only change detection must not depend on chmod support.
                }
            }).doesNotThrowAnyException();
        }
    }

    @Test
    void protectsOnlyMapperFilesAndStableArtifactsWithoutSnapshottingGitOrBuildFiles() throws Exception {
        assumePosix(tempDir);
        RunLayout layout = runLayout();
        Path hugeBuildFile = layout.buildDirectory().resolve("huge.bin");
        try (var channel = Files.newByteChannel(
                hugeBuildFile,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(65L * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        RecordingProtectionObserver observer = new RecordingProtectionObserver();
        Set<PosixFilePermission> mapperOnePermissions = Files.getPosixFilePermissions(layout.mapperOne());
        Set<PosixFilePermission> mapperTwoPermissions = Files.getPosixFilePermissions(layout.mapperTwo());
        Set<PosixFilePermission> stableArtifactPermissions = Files.getPosixFilePermissions(layout.stableArtifact());
        Set<PosixFilePermission> candidateOnePermissions = Files.getPosixFilePermissions(layout.candidateOne());

        try (MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protectRun(
                objectMapper,
                layout.repository(),
                layout.stableRoot(),
                layout.currentRun(),
                List.of(layout.mapperOne().getParent()),
                List.of(layout.mapperOne(), layout.mapperTwo()),
                List.of(layout.candidateOne(), layout.candidateTwo()),
                observer
        )) {
            try (MyBatisSqlReviewFilesystemGuard.TaskScope ignored = guard.protectTask(layout.candidateOne())) {
                assertThat(Files.getPosixFilePermissions(layout.candidateOne()))
                        .isEqualTo(candidateOnePermissions);
                Files.writeString(layout.candidateOne().resolve("report.md"), "first");
            }
            try (MyBatisSqlReviewFilesystemGuard.TaskScope ignored = guard.protectTask(layout.candidateTwo())) {
                Files.writeString(layout.candidateTwo().resolve("report.md"), "second");
            }
        }

        assertThat(observer.backups()).containsEntry(layout.mapperOne(), 1)
                .containsEntry(layout.mapperTwo(), 1)
                .containsEntry(layout.stableArtifact(), 1);
        assertThat(observer.observedPaths())
                .noneMatch(path -> path.startsWith(layout.gitDirectory()))
                .noneMatch(path -> path.startsWith(layout.buildDirectory()))
                .anyMatch(path -> path.startsWith(layout.historicalRun()));
        assertThat(Files.getPosixFilePermissions(layout.mapperOne())).isEqualTo(mapperOnePermissions);
        assertThat(Files.getPosixFilePermissions(layout.mapperTwo())).isEqualTo(mapperTwoPermissions);
        assertThat(Files.getPosixFilePermissions(layout.stableArtifact())).isEqualTo(stableArtifactPermissions);
        assertThat(Files.getPosixFilePermissions(layout.candidateOne())).isEqualTo(candidateOnePermissions);
    }

    @Test
    void laterTaskIgnoresDelayedWritesInsideDiscardedCandidate() throws Exception {
        assumePosix(tempDir);
        RunLayout layout = runLayout();

        try (MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protectRun(
                objectMapper,
                layout.repository(),
                layout.stableRoot(),
                layout.currentRun(),
                List.of(layout.mapperOne().getParent()),
                List.of(layout.mapperOne(), layout.mapperTwo()),
                List.of(layout.candidateOne(), layout.candidateTwo())
        )) {
            try (MyBatisSqlReviewFilesystemGuard.TaskScope ignored = guard.protectTask(layout.candidateOne())) {
                Files.writeString(layout.candidateOne().resolve("report.md"), "first write");
            }

            // IDE inspections and formatters can finish saving a rejected candidate after the
            // fresh-session retry has already started. Rejected candidates are never published.
            Files.writeString(layout.candidateOne().resolve("report.md"), "delayed IDE save");

            try (MyBatisSqlReviewFilesystemGuard.TaskScope ignored = guard.protectTask(layout.candidateTwo())) {
                Files.writeString(layout.candidateTwo().resolve("report.md"), "second write");
            }
        }

        assertThat(layout.candidateOne().resolve("report.md")).hasContent("delayed IDE save");
        assertThat(layout.candidateTwo().resolve("report.md")).hasContent("second write");
    }

    @Test
    void lightweightTaskScopeDetectsAndRestoresCandidateSiblingWritesBeforeJavaDiagnostics() throws Exception {
        assumePosix(tempDir);
        RunLayout layout = runLayout();
        byte[] diagnosticBefore = Files.readAllBytes(layout.diagnosticOne());
        Set<PosixFilePermission> attemptPermissions = Files.getPosixFilePermissions(
                layout.candidateOne().getParent());
        MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protectRun(
                objectMapper,
                layout.repository(),
                layout.stableRoot(),
                layout.currentRun(),
                List.of(layout.mapperOne().getParent()),
                List.of(layout.mapperOne(), layout.mapperTwo()),
                List.of(layout.candidateOne(), layout.candidateTwo())
        );

        Path sibling = layout.candidateOne().getParent().resolve("agent-sibling.txt");
        assertThatThrownBy(() -> {
            try (MyBatisSqlReviewFilesystemGuard.TaskScope ignored = guard.protectTask(layout.candidateOne())) {
                makeWritable(layout.candidateOne().getParent());
                makeWritable(layout.diagnosticOne());
                Files.writeString(layout.diagnosticOne(), "tampered diagnostic");
                Files.writeString(sibling, "unauthorized");
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current run").hasMessageContaining("agent-sibling.txt");
        guard.close();

        assertThat(layout.diagnosticOne()).hasBinaryContent(diagnosticBefore);
        assertThat(sibling).doesNotExist();
        assertThat(Files.getPosixFilePermissions(layout.candidateOne().getParent()))
                .isEqualTo(attemptPermissions);
    }

    @Test
    void taskScopeRestoresMapperStableAndCurrentRunWhileIgnoringUnrelatedRepositoryFiles() throws Exception {
        assumePosix(tempDir);
        RunLayout layout = runLayout();
        byte[] mapperBefore = Files.readAllBytes(layout.mapperTwo());
        byte[] stableBefore = Files.readAllBytes(layout.stableArtifact());
        Set<PosixFilePermission> mapperPermissions = Files.getPosixFilePermissions(layout.mapperTwo());
        Path injectedSource = layout.javaSource().getParent().resolve("Injected.java");
        Path external = tempDir.resolve("external.txt");
        Files.writeString(external, "external remains untouched");
        Path repositorySymlink = layout.javaSource().getParent().resolve("Escape.java");
        Path candidateSibling = layout.candidateOne().getParent().resolve("agent-sibling.txt");

        MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protectRun(
                objectMapper,
                layout.repository(),
                layout.stableRoot(),
                layout.currentRun(),
                List.of(layout.mapperOne().getParent()),
                List.of(layout.mapperOne(), layout.mapperTwo()),
                List.of(layout.candidateOne(), layout.candidateTwo())
        );

        Throwable violation = org.assertj.core.api.Assertions.catchThrowable(() -> {
            try (MyBatisSqlReviewFilesystemGuard.TaskScope ignored = guard.protectTask(layout.candidateOne())) {
                makeWritable(layout.repository());
                makeWritable(layout.pom());
                makeWritable(layout.javaSource().getParent());
                makeWritable(layout.javaSource());
                makeWritable(layout.userFile());
                makeWritable(layout.stableRoot());
                makeWritable(layout.stableArtifact());
                makeWritable(layout.candidateOne().getParent());
                Files.writeString(layout.pom(), "<project>tampered</project>");
                Files.writeString(layout.javaSource(), "class Application { int tampered; }");
                Files.writeString(injectedSource, "class Injected {}");
                Files.delete(layout.userFile());
                Files.writeString(layout.stableArtifact(), "tampered stable publication");
                Files.createSymbolicLink(repositorySymlink, external);
                makeWritable(layout.mapperTwo());
                Files.writeString(layout.mapperTwo(), "<mapper namespace=\"tampered\"/>");
                Files.writeString(candidateSibling, "outside current candidate");
                Files.writeString(layout.candidateOne().resolve("report.md"), "report");
                Files.writeString(layout.candidateOne().resolve("summary.json"), "{}");
                Files.writeString(layout.candidateOne().resolve("database-evidence.json"), "{}");
            }
        });
        assertThat(violation).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protected repository")
                .hasMessageContaining("TwoMapper.xml")
                .hasMessageContaining("agent-sibling.txt");
        assertThat(violation.getMessage())
                .doesNotContain("pom.xml", "Injected.java", "Escape.java");
        guard.close();

        assertThat(layout.pom()).hasContent("<project>tampered</project>");
        assertThat(layout.javaSource()).hasContent("class Application { int tampered; }");
        assertThat(layout.userFile()).doesNotExist();
        assertThat(layout.mapperTwo()).hasBinaryContent(mapperBefore);
        assertThat(layout.stableArtifact()).hasBinaryContent(stableBefore);
        assertThat(injectedSource).isRegularFile();
        assertThat(repositorySymlink).isSymbolicLink();
        assertThat(candidateSibling).doesNotExist();
        assertThat(external).hasContent("external remains untouched");
        assertThat(Files.getPosixFilePermissions(layout.mapperTwo())).isEqualTo(mapperPermissions);
        assertThatCode(() -> MyBatisSqlReviewFilesystemGuard.requireSafeCandidate(
                layout.candidateOne().getParent(), layout.candidateOne()
        )).doesNotThrowAnyException();
    }

    @Test
    void restoresAnIntermediateDirectorySymlinkWithoutTouchingExternalPermissionsOrContent() throws Exception {
        assumePosix(tempDir);
        RunLayout layout = runLayout();
        Path external = Files.createDirectories(tempDir.resolve("external-tree"));
        Path externalFile = external.resolve("outside.txt");
        Files.writeString(externalFile, "outside remains unchanged");
        Set<PosixFilePermission> externalPermissions = permissions("r-x------");
        Set<PosixFilePermission> externalFilePermissions = permissions("r--------");
        Files.setPosixFilePermissions(external, externalPermissions);
        Files.setPosixFilePermissions(externalFile, externalFilePermissions);
        Path mapperRoot = layout.mapperOne().getParent();
        byte[] mapperOneBefore = Files.readAllBytes(layout.mapperOne());
        byte[] mapperTwoBefore = Files.readAllBytes(layout.mapperTwo());

        MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protectRun(
                objectMapper,
                layout.repository(),
                layout.stableRoot(),
                layout.currentRun(),
                List.of(layout.mapperOne().getParent()),
                List.of(layout.mapperOne(), layout.mapperTwo()),
                List.of(layout.candidateOne(), layout.candidateTwo())
        );

        assertThatThrownBy(() -> {
            try (MyBatisSqlReviewFilesystemGuard.TaskScope ignored = guard.protectTask(layout.candidateOne())) {
                makeWritable(mapperRoot.getParent());
                makeWritable(mapperRoot);
                makeWritable(layout.mapperOne());
                makeWritable(layout.mapperTwo());
                Files.delete(layout.mapperOne());
                Files.delete(layout.mapperTwo());
                Files.delete(mapperRoot);
                Files.createSymbolicLink(mapperRoot, external);
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protected repository");
        guard.close();

        assertThat(mapperRoot).isDirectory();
        assertThat(layout.mapperOne()).hasBinaryContent(mapperOneBefore);
        assertThat(layout.mapperTwo()).hasBinaryContent(mapperTwoBefore);
        assertThat(externalFile).hasContent("outside remains unchanged");
        assertThat(Files.getPosixFilePermissions(external)).isEqualTo(externalPermissions);
        assertThat(Files.getPosixFilePermissions(externalFile)).isEqualTo(externalFilePermissions);
    }

    @Test
    void doesNotRewritePermissionsChangedByAnotherProcess() throws Exception {
        assumePosix(tempDir);
        RunLayout layout = runLayout();
        Path mapperDirectory = layout.mapperOne().getParent();
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(mapperDirectory);
        MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protectRun(
                objectMapper, layout.repository(), layout.stableRoot(), layout.currentRun(),
                List.of(layout.mapperOne().getParent()),
                List.of(layout.mapperOne(), layout.mapperTwo()),
                List.of(layout.candidateOne(), layout.candidateTwo())
        );
        Files.setPosixFilePermissions(mapperDirectory, permissions("---------"));

        assertThatThrownBy(guard::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filesystem detection failed");
        assertThat(Files.getPosixFilePermissions(mapperDirectory)).isEmpty();
        Files.setPosixFilePermissions(mapperDirectory, original);
    }

    @Test
    void javaWriteViolationRetainsTheReportedRecoveryBackup() throws Exception {
        assumePosix(tempDir);
        RunLayout layout = runLayout();
        MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protectRun(
                objectMapper, layout.repository(), layout.stableRoot(), layout.currentRun(),
                List.of(layout.mapperOne().getParent()),
                List.of(layout.mapperOne(), layout.mapperTwo()),
                List.of(layout.candidateOne(), layout.candidateTwo())
        );

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> guard.withJavaWrites(
                List.of(layout.diagnosticOne()),
                () -> {
                    Files.writeString(layout.diagnosticOne(), "java output");
                    makeWritable(layout.mapperOne());
                    Files.writeString(layout.mapperOne(), "tampered");
                    return null;
                }
        ));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recovery backup retained at");
        Path backup = Path.of(thrown.getMessage().substring(
                thrown.getMessage().lastIndexOf("recovery backup retained at")
                        + "recovery backup retained at".length()).trim());
        guard.close();
        assertThat(backup).isDirectory();
    }

    @Test
    void rejectsJavaOutputChangedAfterItWasSealedButBeforeAdoption() throws Exception {
        assumePosix(tempDir);
        RunLayout layout = runLayout();
        RecordingProtectionObserver observer = new RecordingProtectionObserver();
        observer.onJavaWritesSealed = () -> {
            try {
                makeWritable(layout.diagnosticOne());
                Files.writeString(layout.diagnosticOne(), "changed in validation-to-adoption window");
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
        MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protectRun(
                objectMapper, layout.repository(), layout.stableRoot(), layout.currentRun(),
                List.of(layout.mapperOne().getParent()),
                List.of(layout.mapperOne(), layout.mapperTwo()),
                List.of(layout.candidateOne(), layout.candidateTwo()), observer
        );

        assertThatThrownBy(() -> guard.withJavaWrites(
                List.of(layout.diagnosticOne()),
                () -> {
                    Files.writeString(layout.diagnosticOne(), "validated Java output");
                    return null;
                }
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("between sealing and adoption")
                .hasMessageContaining("recovery backup retained at");
        assertThatCode(guard::close).doesNotThrowAnyException();
    }

    @Test
    void runnerRejectsValidSetSubstitutedAfterCopyButBeforeFilesystemSeal() throws Exception {
        assumePosix(tempDir);
        RunLayout layout = runLayout();
        List<String> artifacts = List.of("report.md", "summary.json", "database-evidence.json");
        Path target = Files.createDirectories(layout.currentRun().resolve("bundle/task"));
        Path alternate = Files.createDirectories(tempDir.resolve("alternate-valid-bundle"));
        Map<String, String> fixtures = Map.of(
                "report.md", "report-valid.md",
                "summary.json", "sql-summary-valid.json",
                "database-evidence.json", "database-evidence-valid.json"
        );
        for (String artifact : artifacts) {
            try (var input = getClass().getResourceAsStream(
                    "/mybatis-sql-review-fixtures/" + fixtures.get(artifact)
            )) {
                Files.copy(java.util.Objects.requireNonNull(input), layout.candidateOne().resolve(artifact));
            }
            Files.copy(layout.candidateOne().resolve(artifact), alternate.resolve(artifact));
            Files.createFile(target.resolve(artifact));
        }
        Files.writeString(
                alternate.resolve("report.md"),
                Files.readString(alternate.resolve("report.md")) + System.lineSeparator()
        );
        var expectedTask = new MyBatisSqlOutputValidator.ExpectedTaskContext(
                "mapper-order-find-open", "mappers/OrderMapper.xml", "com.example.OrderMapper",
                "findOpen", "select", false
        );
        MyBatisSqlOutputValidator validator = new MyBatisSqlOutputValidator(objectMapper);
        assertThat(validator.validatePublishedOffline(layout.candidateOne(), expectedTask))
                .isEqualTo(validator.validatePublishedOffline(alternate, expectedTask));
        MyBatisSqlReviewTaskRunner.CandidateSnapshot validated =
                MyBatisSqlReviewTaskRunner.captureCandidate(layout.candidateOne());
        RecordingProtectionObserver observer = new RecordingProtectionObserver();
        observer.onJavaWritesCompletedBeforeSeal = () -> {
            try {
                for (String artifact : artifacts) {
                    Files.write(target.resolve(artifact), Files.readAllBytes(alternate.resolve(artifact)));
                }
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
        try (MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protectRun(
                objectMapper, layout.repository(), layout.stableRoot(), layout.currentRun(),
                List.of(layout.mapperOne().getParent()),
                List.of(layout.mapperOne(), layout.mapperTwo()),
                List.of(layout.candidateOne(), layout.candidateTwo()), observer
        )) {
            MyBatisSqlReviewFilesystemGuard.SealedWrite<Path> sealed = guard.withJavaWritesSealed(
                    artifacts.stream().map(target::resolve).toList(),
                    () -> {
                        MyBatisSqlReviewTaskRunner.copyCandidateSnapshot(
                                layout.candidateOne(), target, validated
                        );
                        return target;
                    }
            );
            MyBatisSqlReviewTaskRunner.CandidateSnapshot published =
                    MyBatisSqlReviewTaskRunner.captureCandidate(target);

            assertThatThrownBy(() -> MyBatisSqlReviewTaskRunner.verifySealedBundle(
                    validated, published, target, sealed
            )).hasMessageContaining("differs from validated candidate");
        }
    }

    @Test
    void leavesLargeUnrelatedRepositoryFilesOutsideRunProtection() throws Exception {
        assumePosix(tempDir);
        RunLayout layout = runLayout();
        MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protectRun(
                objectMapper, layout.repository(), layout.stableRoot(), layout.currentRun(),
                List.of(layout.mapperOne().getParent()),
                List.of(layout.mapperOne(), layout.mapperTwo()),
                List.of(layout.candidateOne(), layout.candidateTwo())
        );
        Path unexpected = layout.repository().resolve("unexpected-large.bin");
        try (guard;
             MyBatisSqlReviewFilesystemGuard.TaskScope ignored =
                     guard.protectTask(layout.candidateOne());
             var channel = Files.newByteChannel(
                     unexpected,
                     java.nio.file.StandardOpenOption.CREATE,
                     java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(65L * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }

        assertThat(unexpected).isRegularFile();
        assertThat(Files.size(unexpected)).isEqualTo(65L * 1024 * 1024 + 1);
    }

    private Layout layout(boolean outputInsideRepository) throws Exception {
        Path repository = Files.createDirectories(tempDir.resolve(
                outputInsideRepository ? "repo-overlap" : "repo"
        ));
        Path source = repository.resolve("Mapper.xml");
        Files.writeString(source, "source before");
        Path stable = outputInsideRepository
                ? Files.createDirectories(repository.resolve("review-output"))
                : Files.createDirectories(tempDir.resolve("stable"));
        Path stableFile = stable.resolve("published.md");
        Files.writeString(stableFile, "stable before");
        Path attempt = Files.createDirectories(stable.resolve("runs/run/tasks/task/attempts/001"));
        Path candidate = Files.createDirectories(attempt.resolve("candidate"));
        return new Layout(repository, source, stable, stableFile, attempt, candidate);
    }

    private RunLayout runLayout() throws Exception {
        Path repository = Files.createDirectories(tempDir.resolve("run-repo"));
        Path mapperDirectory = Files.createDirectories(repository.resolve("src/main/resources/mappers"));
        Path mapperOne = mapperDirectory.resolve("OneMapper.xml");
        Path mapperTwo = mapperDirectory.resolve("TwoMapper.xml");
        Files.writeString(mapperOne, "<mapper namespace=\"one\"/>");
        Files.writeString(mapperTwo, "<mapper namespace=\"two\"/>");
        Path pom = repository.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        Path javaDirectory = Files.createDirectories(repository.resolve("src/main/java/example"));
        Path javaSource = javaDirectory.resolve("Application.java");
        Files.writeString(javaSource, "class Application {}");
        Path userFile = repository.resolve("README-user.md");
        Files.writeString(userFile, "keep me");
        Path gitDirectory = Files.createDirectories(repository.resolve(".git/objects"));
        Files.writeString(gitDirectory.resolve("secret"), "git");
        Path buildDirectory = Files.createDirectories(repository.resolve("target/generated"));
        Files.writeString(buildDirectory.resolve("large.bin"), "build");

        Path stableRoot = Files.createDirectories(tempDir.resolve("run-stable"));
        Path stableArtifact = stableRoot.resolve("published.md");
        Files.writeString(stableArtifact, "published");
        var publication = objectMapper.createObjectNode();
        publication.put("schemaVersion", "workflow-publication/v1");
        publication.putArray("artifacts").addObject()
                .put("path", "published.md").put("sha256", "unused-by-guard");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                stableRoot.resolve(".publication.json").toFile(), publication);

        Path historicalRun = Files.createDirectories(stableRoot.resolve("runs/historical/tasks"));
        Files.writeString(historicalRun.resolve("large.log"), "history");
        Path currentRun = Files.createDirectories(stableRoot.resolve("runs/current"));
        Path attemptOne = Files.createDirectories(currentRun.resolve("tasks/one/attempts/001"));
        Path attemptTwo = Files.createDirectories(currentRun.resolve("tasks/two/attempts/001"));
        Path candidateOne = Files.createDirectories(attemptOne.resolve("candidate"));
        Path candidateTwo = Files.createDirectories(attemptTwo.resolve("candidate"));
        Path diagnosticOne = attemptOne.resolve("agent-status.json");
        Path diagnosticTwo = attemptTwo.resolve("agent-status.json");
        Files.writeString(diagnosticOne, "queued one");
        Files.writeString(diagnosticTwo, "queued two");
        return new RunLayout(
                repository, mapperOne, mapperTwo, pom, javaSource, userFile, gitDirectory, buildDirectory,
                stableRoot, stableArtifact, historicalRun, currentRun,
                candidateOne, candidateTwo, diagnosticOne, diagnosticTwo
        );
    }

    private void makeWritable(Path path) throws Exception {
        Set<PosixFilePermission> permissions = EnumSet.copyOf(Files.getPosixFilePermissions(path));
        permissions.add(PosixFilePermission.OWNER_WRITE);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
        }
        Files.setPosixFilePermissions(path, permissions);
    }

    private Set<PosixFilePermission> permissions(String value) {
        return java.nio.file.attribute.PosixFilePermissions.fromString(value);
    }

    private void assumePosix(Path path) {
        Assumptions.assumeTrue(Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS
        ) != null);
    }

    private record Layout(
            Path repository,
            Path source,
            Path stableRoot,
            Path stableFile,
            Path attemptRoot,
            Path candidate
    ) {
    }

    private record RunLayout(
            Path repository,
            Path mapperOne,
            Path mapperTwo,
            Path pom,
            Path javaSource,
            Path userFile,
            Path gitDirectory,
            Path buildDirectory,
            Path stableRoot,
            Path stableArtifact,
            Path historicalRun,
            Path currentRun,
            Path candidateOne,
            Path candidateTwo,
            Path diagnosticOne,
            Path diagnosticTwo
    ) {
    }

    private static final class RecordingProtectionObserver
            implements MyBatisSqlReviewFilesystemGuard.ProtectionObserver {
        private final Map<Path, Integer> backups = new LinkedHashMap<>();
        private Runnable onJavaWritesSealed;
        private Runnable onJavaWritesCompletedBeforeSeal;

        @Override
        public void backedUp(Path path) {
            backups.merge(path, 1, Integer::sum);
        }

        @Override
        public void javaWritesSealed(List<Path> paths) {
            if (onJavaWritesSealed != null) {
                onJavaWritesSealed.run();
            }
        }

        @Override
        public void javaWritesCompletedBeforeSeal(List<Path> paths) {
            if (onJavaWritesCompletedBeforeSeal != null) {
                onJavaWritesCompletedBeforeSeal.run();
            }
        }

        Map<Path, Integer> backups() {
            return backups;
        }

        Set<Path> observedPaths() {
            return new java.util.LinkedHashSet<>(backups.keySet());
        }
    }
}
