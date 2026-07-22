package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisSqlReviewFilesystemGuardTest {
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
}
