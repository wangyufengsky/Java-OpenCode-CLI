package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class MyBatisSqlReviewFilesystemGuard implements AutoCloseable {
    private static final Set<String> CANDIDATE_FILES = Set.of(
            "report.md", "summary.json", "database-evidence.json"
    );

    private final List<Path> protectedRoots;
    private final Path candidate;
    private final Path backupRoot;
    private final Map<Path, SnapshotEntry> snapshot;
    private final Map<Path, Set<PosixFilePermission>> originalPermissions;
    private final Map<Path, Set<PosixFilePermission>> guardedPermissions;
    private boolean closed;

    private MyBatisSqlReviewFilesystemGuard(
            List<Path> protectedRoots,
            Path candidate,
            Path backupRoot,
            Map<Path, SnapshotEntry> snapshot,
            Map<Path, Set<PosixFilePermission>> originalPermissions,
            Map<Path, Set<PosixFilePermission>> guardedPermissions
    ) {
        this.protectedRoots = List.copyOf(protectedRoots);
        this.candidate = candidate;
        this.backupRoot = backupRoot;
        this.snapshot = Map.copyOf(snapshot);
        this.originalPermissions = Map.copyOf(originalPermissions);
        this.guardedPermissions = Map.copyOf(guardedPermissions);
    }

    static MyBatisSqlReviewFilesystemGuard protect(
            Path repository,
            Path stableRoot,
            Path attemptRoot,
            Path candidate
    ) throws IOException {
        Path normalizedRepository = normalizeDirectory(repository, "repository");
        Path normalizedStable = normalizeDirectory(stableRoot, "stable output");
        Path normalizedAttempt = normalizeDirectory(attemptRoot, "attempt root");
        if (Files.isSymbolicLink(candidate.toAbsolutePath().normalize())) {
            throw new IllegalStateException("symlinked candidate path is forbidden: " + candidate);
        }
        Path normalizedCandidate = normalizeDirectory(candidate, "candidate directory");
        requireCandidateContainment(normalizedStable, normalizedAttempt, normalizedCandidate);
        List<Path> roots = minimalRoots(List.of(normalizedRepository, normalizedStable));
        requirePosix(roots, normalizedCandidate);

        Path backup = Files.createTempDirectory("mybatis-sql-review-protected-");
        Map<Path, SnapshotEntry> before;
        Map<Path, Set<PosixFilePermission>> original = new LinkedHashMap<>();
        Map<Path, Set<PosixFilePermission>> guarded = new LinkedHashMap<>();
        try {
            before = snapshot(roots, normalizedCandidate, backup);
            for (Path path : before.keySet()) {
                if (Files.isSymbolicLink(path)) {
                    continue;
                }
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                        path, LinkOption.NOFOLLOW_LINKS
                );
                original.put(path, Set.copyOf(permissions));
                Set<PosixFilePermission> readOnly = withoutWrites(permissions);
                Files.setPosixFilePermissions(path, readOnly);
                guarded.put(path, Set.copyOf(readOnly));
            }
            Set<PosixFilePermission> candidatePermissions = Files.getPosixFilePermissions(
                    normalizedCandidate, LinkOption.NOFOLLOW_LINKS
            );
            original.put(normalizedCandidate, Set.copyOf(candidatePermissions));
            Set<PosixFilePermission> candidateWritable = EnumSet.copyOf(candidatePermissions);
            candidateWritable.add(PosixFilePermission.OWNER_WRITE);
            candidateWritable.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(normalizedCandidate, candidateWritable);
            guarded.put(normalizedCandidate, Set.copyOf(candidateWritable));
        } catch (Exception setupFailure) {
            IOException restoreFailure = restorePermissionsBestEffort(original);
            cleanupBestEffort(backup);
            IllegalStateException failure = new IllegalStateException(
                    "failed to establish POSIX filesystem protection", setupFailure
            );
            if (restoreFailure != null) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
        return new MyBatisSqlReviewFilesystemGuard(
                roots, normalizedCandidate, backup, before, original, guarded
        );
    }

    static void requireSafeCandidate(Path attemptRoot, Path candidate) throws IOException {
        Path normalizedAttempt = normalizeDirectory(attemptRoot, "attempt root");
        if (Files.isSymbolicLink(candidate.toAbsolutePath().normalize())) {
            throw new IllegalStateException("symlinked candidate path is forbidden: " + candidate);
        }
        Path normalizedCandidate = normalizeDirectory(candidate, "candidate directory");
        requireCandidateContainment(
                normalizedAttempt.getParent() == null ? normalizedAttempt : normalizedAttempt.getParent(),
                normalizedAttempt,
                normalizedCandidate
        );
        Set<String> actual = new LinkedHashSet<>();
        try (var entries = Files.list(normalizedCandidate)) {
            for (Path entry : entries.toList()) {
                actual.add(entry.getFileName().toString());
            }
        }
        if (!actual.equals(CANDIDATE_FILES)) {
            throw new IllegalStateException(
                    "candidate must contain exactly three non-symlink regular files "
                            + CANDIDATE_FILES + " but found " + actual
            );
        }
        Path candidateReal = normalizedCandidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        for (String name : CANDIDATE_FILES) {
            Path file = normalizedCandidate.resolve(name);
            if (Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(
                        "candidate must contain exactly three non-symlink regular files: " + name
                );
            }
            Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!candidateReal.equals(real.getParent()) || !real.startsWith(candidateReal)) {
                throw new IllegalStateException("candidate artifact escapes candidate directory: " + name);
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<String> violations = new ArrayList<>();
        Throwable detectionFailure = null;
        try {
            violations.addAll(detectChanges());
        } catch (Throwable exception) {
            detectionFailure = exception;
            violations.add("protected snapshot verification failed: " + concise(exception));
        }

        Throwable contentRestoreFailure = null;
        if (!violations.isEmpty()) {
            IOException writableFailure = makeRestorationWritable();
            if (writableFailure != null) {
                contentRestoreFailure = writableFailure;
            }
            try {
                restoreSnapshot();
                List<String> remaining = detectContentChanges(false);
                if (!remaining.isEmpty()) {
                    throw new IOException("protected content restoration remained incomplete: " + remaining);
                }
            } catch (Throwable exception) {
                if (contentRestoreFailure == null) {
                    contentRestoreFailure = exception;
                } else {
                    contentRestoreFailure.addSuppressed(exception);
                }
            }
        }
        IOException permissionFailure = restorePermissionsBestEffort(originalPermissions);
        cleanupBestEffort(backupRoot);

        if (!violations.isEmpty() || permissionFailure != null || contentRestoreFailure != null) {
            String message = violations.isEmpty()
                    ? "filesystem protection restoration failed"
                    : "protected filesystem content changed: " + String.join("; ", violations);
            IllegalStateException failure = new IllegalStateException(message);
            if (detectionFailure != null) {
                failure.addSuppressed(detectionFailure);
            }
            if (permissionFailure != null) {
                failure.addSuppressed(permissionFailure);
            }
            if (contentRestoreFailure != null) {
                failure.addSuppressed(contentRestoreFailure);
            }
            throw failure;
        }
    }

    private List<String> detectChanges() throws IOException {
        List<String> changes = detectContentChanges(true);
        for (Map.Entry<Path, Set<PosixFilePermission>> entry : guardedPermissions.entrySet()) {
            Path path = entry.getKey();
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                continue;
            }
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(
                    path, LinkOption.NOFOLLOW_LINKS
            );
            if (!actual.equals(entry.getValue())) {
                changes.add(display(path) + " permissions changed");
            }
        }
        return changes;
    }

    private List<String> detectContentChanges(boolean compareIdentity) throws IOException {
        Map<Path, CurrentEntry> current = currentEntries(protectedRoots, candidate);
        List<String> changes = new ArrayList<>();
        Set<Path> all = new LinkedHashSet<>(snapshot.keySet());
        all.addAll(current.keySet());
        for (Path path : all.stream().sorted().toList()) {
            SnapshotEntry expected = snapshot.get(path);
            CurrentEntry actual = current.get(path);
            if (expected == null) {
                changes.add(display(path) + " created");
            } else if (actual == null) {
                changes.add(display(path) + " deleted");
            } else if (!expected.sameContent(actual, compareIdentity)) {
                changes.add(display(path) + " changed");
            }
        }
        return changes;
    }

    private void restoreSnapshot() throws IOException {
        Map<Path, CurrentEntry> current = currentEntries(protectedRoots, candidate);
        List<Path> newPaths = current.keySet().stream()
                .filter(path -> !snapshot.containsKey(path))
                .sorted(Comparator.reverseOrder())
                .toList();
        for (Path path : newPaths) {
            deleteNoFollow(path);
        }
        for (SnapshotEntry entry : snapshot.values().stream()
                .sorted(Comparator.comparingInt(value -> value.path().getNameCount()))
                .toList()) {
            restoreEntry(entry);
        }
        for (SnapshotEntry entry : snapshot.values().stream()
                .filter(value -> value.kind() == Kind.DIRECTORY)
                .sorted(Comparator.comparingInt((SnapshotEntry value) -> value.path().getNameCount()).reversed())
                .toList()) {
            Files.setLastModifiedTime(entry.path(), entry.lastModifiedTime());
        }
    }

    private IOException makeRestorationWritable() {
        IOException failure = null;
        for (Path path : originalPermissions.keySet().stream()
                .sorted(Comparator.comparingInt(Path::getNameCount))
                .toList()) {
            try {
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                    continue;
                }
                Set<PosixFilePermission> permissions = EnumSet.copyOf(
                        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                );
                permissions.add(PosixFilePermission.OWNER_READ);
                permissions.add(PosixFilePermission.OWNER_WRITE);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    permissions.add(PosixFilePermission.OWNER_EXECUTE);
                }
                Files.setPosixFilePermissions(path, permissions);
            } catch (IOException | RuntimeException exception) {
                IOException current = exception instanceof IOException io
                        ? io
                        : new IOException("failed to prepare protected path for restoration: " + path, exception);
                if (failure == null) {
                    failure = current;
                } else {
                    failure.addSuppressed(current);
                }
            }
        }
        return failure;
    }

    private void restoreEntry(SnapshotEntry entry) throws IOException {
        Path path = entry.path();
        BasicFileAttributes attributes = readAttributesOrNull(path);
        if (attributes != null && !entry.kind().matches(attributes)) {
            deleteNoFollow(path);
            attributes = null;
        }
        switch (entry.kind()) {
            case DIRECTORY -> Files.createDirectories(path);
            case REGULAR_FILE -> {
                if (attributes == null) {
                    Files.createDirectories(path.getParent());
                }
                Files.copy(entry.backupFile(), path, StandardCopyOption.REPLACE_EXISTING);
            }
            case SYMBOLIC_LINK -> {
                if (attributes == null) {
                    Files.createDirectories(path.getParent());
                    Files.createSymbolicLink(path, Path.of(entry.symbolicLinkTarget()));
                } else if (!Files.readSymbolicLink(path).toString().equals(entry.symbolicLinkTarget())) {
                    Files.delete(path);
                    Files.createSymbolicLink(path, Path.of(entry.symbolicLinkTarget()));
                }
            }
        }
        if (entry.kind() == Kind.REGULAR_FILE) {
            Files.setLastModifiedTime(path, entry.lastModifiedTime());
        }
    }

    private static Map<Path, SnapshotEntry> snapshot(
            List<Path> roots,
            Path candidate,
            Path backupRoot
    ) throws IOException {
        Map<Path, SnapshotEntry> result = new LinkedHashMap<>();
        int[] index = {0};
        for (Path path : protectedPaths(roots, candidate)) {
            if (result.containsKey(path)) {
                continue;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            Kind kind = Kind.of(attributes);
            Path backupFile = null;
            String sha256 = "";
            String symbolicLink = "";
            if (kind == Kind.REGULAR_FILE) {
                backupFile = backupRoot.resolve("files").resolve("%08d.bin".formatted(index[0]++));
                Files.createDirectories(backupFile.getParent());
                try (InputStream input = Files.newInputStream(
                        path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS
                )) {
                    Files.copy(input, backupFile, StandardCopyOption.REPLACE_EXISTING);
                }
                sha256 = sha256(backupFile);
            } else if (kind == Kind.SYMBOLIC_LINK) {
                symbolicLink = Files.readSymbolicLink(path).toString();
            }
            result.put(path, new SnapshotEntry(
                    path,
                    kind,
                    attributes.fileKey() == null ? "" : attributes.fileKey().toString(),
                    attributes.lastModifiedTime(),
                    sha256,
                    symbolicLink,
                    backupFile
            ));
        }
        return result;
    }

    private static Map<Path, CurrentEntry> currentEntries(
            List<Path> roots,
            Path candidate
    ) throws IOException {
        Map<Path, CurrentEntry> result = new LinkedHashMap<>();
        for (Path path : protectedPaths(roots, candidate)) {
            if (result.containsKey(path)) {
                continue;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            Kind kind = Kind.of(attributes);
            String sha256 = kind == Kind.REGULAR_FILE ? sha256(path) : "";
            String symbolicLink = kind == Kind.SYMBOLIC_LINK
                    ? Files.readSymbolicLink(path).toString()
                    : "";
            result.put(path, new CurrentEntry(
                    kind,
                    attributes.fileKey() == null ? "" : attributes.fileKey().toString(),
                    attributes.lastModifiedTime(),
                    sha256,
                    symbolicLink
            ));
        }
        return result;
    }

    private static List<Path> protectedPaths(List<Path> roots, Path candidate) throws IOException {
        Set<Path> result = new LinkedHashSet<>();
        for (Path root : roots) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted().toList()) {
                    Path normalized = path.toAbsolutePath().normalize();
                    if (normalized.equals(candidate) || normalized.startsWith(candidate)) {
                        continue;
                    }
                    result.add(normalized);
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Path> minimalRoots(List<Path> paths) {
        List<Path> sorted = paths.stream().distinct()
                .sorted(Comparator.comparingInt(Path::getNameCount))
                .toList();
        List<Path> roots = new ArrayList<>();
        for (Path path : sorted) {
            if (roots.stream().noneMatch(path::startsWith)) {
                roots.add(path);
            }
        }
        return List.copyOf(roots);
    }

    private static void requirePosix(List<Path> roots, Path candidate) {
        List<Path> paths = new ArrayList<>(roots);
        paths.add(candidate);
        for (Path path : paths) {
            if (Files.getFileAttributeView(
                    path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS
            ) == null) {
                throw new IllegalStateException(
                        "POSIX permissions are required for MyBatis SQL review filesystem protection: " + path
                );
            }
        }
    }

    private static Path normalizeDirectory(Path path, String label) {
        Objects.requireNonNull(path, label);
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(label + " must be a non-symlink directory: " + normalized);
        }
        return normalized;
    }

    private static void requireCandidateContainment(
            Path stableRoot,
            Path attemptRoot,
            Path candidate
    ) throws IOException {
        if (!candidate.getParent().equals(attemptRoot)
                || !attemptRoot.startsWith(stableRoot)) {
            throw new IllegalStateException("candidate must be the direct child of its stable run attempt");
        }
        for (Path path = stableRoot; path != null && path.startsWith(stableRoot); path = next(path, candidate)) {
            if (Files.isSymbolicLink(path)) {
                throw new IllegalStateException("symlinked candidate path is forbidden: " + path);
            }
            if (path.equals(candidate)) {
                break;
            }
        }
        Path stableReal = stableRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path attemptReal = attemptRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path candidateReal = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!attemptReal.startsWith(stableReal)
                || !candidateReal.startsWith(attemptReal)
                || !candidateReal.getParent().equals(attemptReal)) {
            throw new IllegalStateException("candidate real path escapes its stable run attempt");
        }
    }

    private static Path next(Path current, Path target) {
        if (current.equals(target)) {
            return null;
        }
        int nextCount = current.getNameCount() + 1;
        return target.getRoot() == null
                ? target.subpath(0, nextCount)
                : target.getRoot().resolve(target.subpath(0, nextCount));
    }

    private static Set<PosixFilePermission> withoutWrites(Set<PosixFilePermission> permissions) {
        Set<PosixFilePermission> result = EnumSet.copyOf(permissions);
        result.remove(PosixFilePermission.OWNER_WRITE);
        result.remove(PosixFilePermission.GROUP_WRITE);
        result.remove(PosixFilePermission.OTHERS_WRITE);
        return result;
    }

    private static IOException restorePermissionsBestEffort(
            Map<Path, Set<PosixFilePermission>> permissions
    ) {
        IOException failure = null;
        for (Map.Entry<Path, Set<PosixFilePermission>> entry : permissions.entrySet().stream()
                .sorted(Map.Entry.<Path, Set<PosixFilePermission>>comparingByKey().reversed())
                .toList()) {
            try {
                if (Files.exists(entry.getKey(), LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(entry.getKey())) {
                    Files.setPosixFilePermissions(entry.getKey(), entry.getValue());
                }
            } catch (IOException | RuntimeException exception) {
                IOException current = exception instanceof IOException io
                        ? io
                        : new IOException("failed to restore POSIX permissions: " + entry.getKey(), exception);
                if (failure == null) {
                    failure = current;
                } else {
                    failure.addSuppressed(current);
                }
            }
        }
        return failure;
    }

    private static void deleteNoFollow(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            try (var paths = Files.walk(path)) {
                for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(item);
                }
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    private static void cleanupBestEffort(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // The external temporary backup is best-effort cleanup after restoration.
        }
    }

    private static BasicFileAttributes readAttributesOrNull(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(
                    path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS
            )) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String display(Path path) {
        return path.toString();
    }

    private static String concise(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    private enum Kind {
        DIRECTORY,
        REGULAR_FILE,
        SYMBOLIC_LINK;

        private static Kind of(BasicFileAttributes attributes) {
            if (attributes.isDirectory()) {
                return DIRECTORY;
            }
            if (attributes.isRegularFile()) {
                return REGULAR_FILE;
            }
            if (attributes.isSymbolicLink()) {
                return SYMBOLIC_LINK;
            }
            throw new IllegalStateException("unsupported protected filesystem object type");
        }

        private boolean matches(BasicFileAttributes attributes) {
            return this == of(attributes);
        }
    }

    private record SnapshotEntry(
            Path path,
            Kind kind,
            String fileKey,
            FileTime lastModifiedTime,
            String sha256,
            String symbolicLinkTarget,
            Path backupFile
    ) {
        private boolean sameContent(CurrentEntry actual, boolean compareIdentity) {
            return kind == actual.kind()
                    && (!compareIdentity || (fileKey.equals(actual.fileKey())
                    && lastModifiedTime.equals(actual.lastModifiedTime())))
                    && sha256.equals(actual.sha256())
                    && symbolicLinkTarget.equals(actual.symbolicLinkTarget());
        }
    }

    private record CurrentEntry(
            Kind kind,
            String fileKey,
            FileTime lastModifiedTime,
            String sha256,
            String symbolicLinkTarget
    ) {
    }
}
