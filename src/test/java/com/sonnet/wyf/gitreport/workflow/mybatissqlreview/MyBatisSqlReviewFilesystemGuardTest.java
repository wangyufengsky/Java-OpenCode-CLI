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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisSqlReviewFilesystemGuardTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    @TempDir
    Path tempDir;

    @Test
    void makesProtectedTreesReadOnlyButCandidateWritableAndRestoresExactPermissions() throws Exception {
        assumePosix(tempDir);
        Layout layout = layout(false);
        Set<PosixFilePermission> repoPermissions = permissions("rwxr-x---");
        Set<PosixFilePermission> sourcePermissions = permissions("rw-r-----");
        Set<PosixFilePermission> stablePermissions = permissions("rw-rw----");
        Files.setPosixFilePermissions(layout.repository(), repoPermissions);
        Files.setPosixFilePermissions(layout.source(), sourcePermissions);
        Files.setPosixFilePermissions(layout.stableFile(), stablePermissions);

        try (MyBatisSqlReviewFilesystemGuard ignored = MyBatisSqlReviewFilesystemGuard.protect(
                layout.repository(), layout.stableRoot(), layout.attemptRoot(), layout.candidate()
        )) {
            assertThat(Files.getPosixFilePermissions(layout.repository()))
                    .doesNotContain(PosixFilePermission.OWNER_WRITE);
            assertThat(Files.getPosixFilePermissions(layout.source()))
                    .doesNotContain(PosixFilePermission.OWNER_WRITE);
            assertThat(Files.getPosixFilePermissions(layout.stableFile()))
                    .doesNotContain(PosixFilePermission.OWNER_WRITE);
            assertThat(Files.getPosixFilePermissions(layout.candidate()))
                    .contains(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        }

        assertThat(Files.getPosixFilePermissions(layout.repository())).isEqualTo(repoPermissions);
        assertThat(Files.getPosixFilePermissions(layout.source())).isEqualTo(sourcePermissions);
        assertThat(Files.getPosixFilePermissions(layout.stableFile())).isEqualTo(stablePermissions);
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

        makeWritable(layout.repository());
        makeWritable(layout.source());
        makeWritable(layout.stableRoot());
        makeWritable(layout.attemptRoot());
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
    void restoresTamperedContentEvenWhenOriginalProtectedDirectoriesWereReadOnly() throws Exception {
        assumePosix(tempDir);
        Layout layout = layout(false);
        Set<PosixFilePermission> repositoryPermissions = permissions("r-xr-x---");
        Set<PosixFilePermission> stablePermissions = permissions("r-xr-x---");
        Files.setPosixFilePermissions(layout.repository(), repositoryPermissions);
        Files.setPosixFilePermissions(layout.stableRoot(), stablePermissions);
        byte[] sourceBefore = Files.readAllBytes(layout.source());
        byte[] stableBefore = Files.readAllBytes(layout.stableFile());
        MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protect(
                layout.repository(), layout.stableRoot(), layout.attemptRoot(), layout.candidate()
        );

        makeWritable(layout.repository());
        makeWritable(layout.source());
        makeWritable(layout.stableRoot());
        Files.writeString(layout.source(), "tampered source");
        Files.delete(layout.stableFile());

        assertThatThrownBy(guard::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protected filesystem content changed")
                .satisfies(exception -> assertThat(exception.getSuppressed()).isEmpty());
        assertThat(Files.readAllBytes(layout.source())).isEqualTo(sourceBefore);
        assertThat(Files.readAllBytes(layout.stableFile())).isEqualTo(stableBefore);
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
    void rejectsASymlinkedCandidateParentBeforeChangingPermissions() throws Exception {
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
    void failsClosedWhenTheFilesystemDoesNotExposePosixPermissions() throws Exception {
        Path zip = tempDir.resolve("non-posix.zip");
        URI uri = URI.create("jar:" + zip.toUri());
        try (var fileSystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path repository = Files.createDirectories(fileSystem.getPath("/repo"));
            Path stable = Files.createDirectories(fileSystem.getPath("/stable"));
            Path attempt = Files.createDirectories(stable.resolve("runs/run/tasks/task/attempts/001"));
            Path candidate = Files.createDirectories(attempt.resolve("candidate"));

            assertThatThrownBy(() -> MyBatisSqlReviewFilesystemGuard.protect(
                    repository, stable, attempt, candidate
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("POSIX permissions are required");
        }
    }

    @Test
    void cachesRunProtectionOnceAcrossMultipleSqlTasksWithoutTraversingExcludedTrees() throws Exception {
        assumePosix(tempDir);
        RunLayout layout = runLayout();
        Files.setPosixFilePermissions(layout.gitDirectory(), permissions("---------"));
        Files.setPosixFilePermissions(layout.buildDirectory(), permissions("---------"));
        Files.setPosixFilePermissions(layout.historicalRun(), permissions("---------"));
        RecordingProtectionObserver observer = new RecordingProtectionObserver();
        Set<PosixFilePermission> mapperOnePermissions = Files.getPosixFilePermissions(layout.mapperOne());
        Set<PosixFilePermission> mapperTwoPermissions = Files.getPosixFilePermissions(layout.mapperTwo());
        Set<PosixFilePermission> stableArtifactPermissions = Files.getPosixFilePermissions(layout.stableArtifact());

        try (MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protectRun(
                objectMapper,
                layout.repository(),
                layout.stableRoot(),
                layout.currentRun(),
                List.of(layout.mapperOne(), layout.mapperTwo()),
                List.of(layout.candidateOne(), layout.candidateTwo()),
                observer
        )) {
            try (MyBatisSqlReviewFilesystemGuard.TaskScope ignored = guard.protectTask(layout.candidateOne())) {
                Files.writeString(layout.candidateOne().resolve("report.md"), "first");
            }
            try (MyBatisSqlReviewFilesystemGuard.TaskScope ignored = guard.protectTask(layout.candidateTwo())) {
                Files.writeString(layout.candidateTwo().resolve("report.md"), "second");
            }
        }

        assertThat(observer.backups()).containsEntry(layout.mapperOne(), 1)
                .containsEntry(layout.mapperTwo(), 1)
                .containsEntry(layout.stableArtifact(), 1);
        assertThat(observer.permissionChanges()).containsEntry(layout.mapperOne(), 1)
                .containsEntry(layout.mapperTwo(), 1)
                .containsEntry(layout.stableArtifact(), 1);
        assertThat(observer.observedPaths()).noneMatch(path -> path.startsWith(layout.gitDirectory()))
                .noneMatch(path -> path.startsWith(layout.buildDirectory()))
                .noneMatch(path -> path.startsWith(layout.historicalRun()));
        assertThat(Files.getPosixFilePermissions(layout.mapperOne())).isEqualTo(mapperOnePermissions);
        assertThat(Files.getPosixFilePermissions(layout.mapperTwo())).isEqualTo(mapperTwoPermissions);
        assertThat(Files.getPosixFilePermissions(layout.stableArtifact())).isEqualTo(stableArtifactPermissions);
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
                repository, mapperOne, mapperTwo, gitDirectory, buildDirectory,
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
        private final Map<Path, Integer> permissionChanges = new LinkedHashMap<>();

        @Override
        public void backedUp(Path path) {
            backups.merge(path, 1, Integer::sum);
        }

        @Override
        public void permissionProtected(Path path) {
            permissionChanges.merge(path, 1, Integer::sum);
        }

        Map<Path, Integer> backups() {
            return backups;
        }

        Map<Path, Integer> permissionChanges() {
            return permissionChanges;
        }

        Set<Path> observedPaths() {
            Set<Path> paths = new java.util.LinkedHashSet<>(backups.keySet());
            paths.addAll(permissionChanges.keySet());
            return paths;
        }
    }
}
