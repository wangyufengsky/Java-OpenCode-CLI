package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.failure.WorkflowFailureCategory;
import com.sonnet.wyf.gitreport.failure.WorkflowFailureException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private final RunProtection runProtection;
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
        this.runProtection = null;
    }

    private MyBatisSqlReviewFilesystemGuard(RunProtection runProtection) {
        this.protectedRoots = List.of();
        this.candidate = null;
        this.backupRoot = runProtection.backupRoot;
        this.snapshot = Map.of();
        this.originalPermissions = Map.of();
        this.guardedPermissions = Map.of();
        this.runProtection = runProtection;
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

    static MyBatisSqlReviewFilesystemGuard protectRun(
            ObjectMapper objectMapper,
            Path repository,
            Path stableRoot,
            Path currentRun,
            List<Path> sourceDirectories,
            List<Path> mapperFiles,
            List<Path> candidates,
            ProtectionObserver observer
    ) throws IOException {
        RunProtection protection = RunProtection.establish(
                objectMapper, repository, stableRoot, currentRun,
                sourceDirectories, mapperFiles, candidates, observer
        );
        return new MyBatisSqlReviewFilesystemGuard(protection);
    }

    static MyBatisSqlReviewFilesystemGuard protectRun(
            ObjectMapper objectMapper,
            Path repository,
            Path stableRoot,
            Path currentRun,
            List<Path> sourceDirectories,
            List<Path> mapperFiles,
            List<Path> candidates
    ) throws IOException {
        return protectRun(
                objectMapper, repository, stableRoot, currentRun,
                sourceDirectories, mapperFiles, candidates, ProtectionObserver.NOOP
        );
    }

    TaskScope protectTask(Path candidate) throws IOException {
        if (runProtection == null) {
            throw new IllegalStateException("task scopes require run-level filesystem protection");
        }
        return runProtection.openTask(candidate);
    }

    <T> T withJavaWrites(List<Path> allowedRoots, CheckedOperation<T> operation) throws Exception {
        return withJavaWritesSealed(allowedRoots, operation).value();
    }

    <T> SealedWrite<T> withJavaWritesSealed(
            List<Path> allowedRoots,
            CheckedOperation<T> operation
    ) throws Exception {
        if (runProtection == null) {
            T value = operation.run();
            Map<Path, SealedFile> files = new LinkedHashMap<>();
            for (Path path : allowedRoots) {
                Path normalized = path.toAbsolutePath().normalize();
                BasicFileAttributes attributes = Files.readAttributes(
                        normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
                );
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    throw new IllegalStateException("Java output disappeared before sealing: " + normalized);
                }
                files.put(normalized, new SealedFile(
                        normalized,
                        attributes.fileKey() == null ? "" : attributes.fileKey().toString(),
                        attributes.lastModifiedTime(),
                        attributes.size(),
                        sha256(normalized)
                ));
            }
            return new SealedWrite<>(value, files);
        }
        return runProtection.withJavaWritesSealed(allowedRoots, operation);
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
        if (runProtection != null) {
            runProtection.close();
            return;
        }
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

    private static void restoreEntry(SnapshotEntry entry) throws IOException {
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
        long totalBytes = 0;
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
                if (attributes.size() > RunProtection.MAX_SNAPSHOT_FILE_BYTES) {
                    throw new IllegalStateException(
                            "protected snapshot file exceeds configured limit: " + path
                    );
                }
                totalBytes += attributes.size();
                if (totalBytes > RunProtection.MAX_SNAPSHOT_TOTAL_BYTES) {
                    throw new IllegalStateException("protected snapshot exceeds configured total limit at " + path);
                }
                backupFile = backupRoot.resolve("files").resolve("%08d.bin".formatted(index[0]++));
                Files.createDirectories(backupFile.getParent());
                RunProtection.StableCopy copy = RunProtection.copyRegularStable(
                        path, backupFile, RunProtection.MAX_SNAPSHOT_FILE_BYTES, false
                );
                sha256 = copy.sha256();
            } else if (kind == Kind.SYMBOLIC_LINK) {
                symbolicLink = Files.readSymbolicLink(path).toString();
            }
            result.put(path, new SnapshotEntry(
                    path,
                    kind,
                    attributes.fileKey() == null ? "" : attributes.fileKey().toString(),
                    attributes.lastModifiedTime(),
                    attributes.size(),
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
            String sha256 = kind == Kind.REGULAR_FILE
                    && attributes.size() <= RunProtection.MAX_SNAPSHOT_FILE_BYTES
                    ? sha256(path) : "";
            String symbolicLink = kind == Kind.SYMBOLIC_LINK
                    ? Files.readSymbolicLink(path).toString()
                    : "";
            result.put(path, new CurrentEntry(
                    kind,
                    attributes.fileKey() == null ? "" : attributes.fileKey().toString(),
                    attributes.lastModifiedTime(),
                    attributes.size(),
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
        List<Map.Entry<Path, Set<PosixFilePermission>>> ordered = permissions.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getKey().getNameCount()))
                .toList();
        List<Map.Entry<Path, Set<PosixFilePermission>>> failed = new ArrayList<>();
        IOException failure = restorePermissionEntries(ordered, failed);
        if (!failed.isEmpty()) {
            IOException traversalFailure = restoreTraversalPermissions(permissions);
            List<Map.Entry<Path, Set<PosixFilePermission>>> retryFailed = new ArrayList<>();
            IOException retryFailure = restorePermissionEntries(failed, retryFailed);
            IOException finalDirectoryFailure = restorePermissionEntries(
                    ordered.stream().filter(entry -> Files.isDirectory(
                            entry.getKey(), LinkOption.NOFOLLOW_LINKS
                    )).sorted(Comparator.comparingInt(
                            (Map.Entry<Path, Set<PosixFilePermission>> entry) -> entry.getKey().getNameCount()
                    ).reversed()).toList(),
                    new ArrayList<>()
            );
            failure = combine(traversalFailure, retryFailure);
            failure = combine(failure, finalDirectoryFailure);
        }
        return failure;
    }

    private static IOException restorePermissionEntries(
            List<Map.Entry<Path, Set<PosixFilePermission>>> entries,
            List<Map.Entry<Path, Set<PosixFilePermission>>> failed
    ) {
        IOException failure = null;
        for (Map.Entry<Path, Set<PosixFilePermission>> entry : entries) {
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
                failed.add(entry);
            }
        }
        return failure;
    }

    private static IOException restoreTraversalPermissions(
            Map<Path, Set<PosixFilePermission>> permissions
    ) {
        return restoreTraversalPermissions(permissions, permissions.keySet());
    }

    private static IOException restoreTraversalPermissions(
            Map<Path, Set<PosixFilePermission>> permissions,
            Iterable<Path> scope
    ) {
        Set<Path> selected = new LinkedHashSet<>();
        scope.forEach(selected::add);
        IOException failure = null;
        for (Map.Entry<Path, Set<PosixFilePermission>> entry : permissions.entrySet().stream()
                .filter(value -> selected.contains(value.getKey()))
                .sorted(Comparator.comparingInt(value -> value.getKey().getNameCount()))
                .toList()) {
            Path path = entry.getKey();
            try {
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(path)) {
                    continue;
                }
                Set<PosixFilePermission> traversal = EnumSet.copyOf(entry.getValue());
                traversal.add(PosixFilePermission.OWNER_READ);
                traversal.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(path, traversal);
            } catch (IOException | RuntimeException exception) {
                failure = combine(failure, exception instanceof IOException io
                        ? io : new IOException("failed to restore traversal permission: " + path, exception));
            }
        }
        return failure;
    }

    private static IOException combine(IOException first, IOException second) {
        if (first == null) {
            return second;
        }
        if (second != null) {
            first.addSuppressed(second);
        }
        return first;
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

    @FunctionalInterface
    interface ProtectionObserver {
        ProtectionObserver NOOP = new ProtectionObserver() {
            @Override
            public void backedUp(Path path) {
            }

            @Override
            public void permissionProtected(Path path) {
            }
        };

        void backedUp(Path path);

        default void permissionProtected(Path path) {
        }

        default void javaWritesSealed(List<Path> paths) {
        }

        default void javaWritesCompletedBeforeSeal(List<Path> paths) {
        }
    }

    @FunctionalInterface
    interface CheckedOperation<T> {
        T run() throws Exception;
    }

    static final class TaskScope implements AutoCloseable {
        private final RunProtection protection;
        private final Path candidate;
        private boolean closed;

        private TaskScope(RunProtection protection, Path candidate) {
            this.protection = protection;
            this.candidate = candidate;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            protection.closeTask(candidate);
        }
    }

    private static final class RunProtection {
        private static final long MAX_SNAPSHOT_FILE_BYTES = positiveLimit(
                "mybatis.sql.review.guard.max-file-bytes", 64L * 1024 * 1024
        );
        private static final long MAX_SNAPSHOT_TOTAL_BYTES = positiveLimit(
                "mybatis.sql.review.guard.max-total-bytes", 512L * 1024 * 1024
        );
        private static final long MAX_CONFLICT_PREFIX_BYTES = positiveLimit(
                "mybatis.sql.review.guard.max-conflict-prefix-bytes", 64L * 1024
        );
        private static final long MAX_CONFLICT_TOTAL_BYTES = positiveLimit(
                "mybatis.sql.review.guard.max-conflict-total-bytes", 16L * 1024 * 1024
        );
        private final Path repository;
        private final Path stableRoot;
        private final Path currentRun;
        private final Set<Path> candidates;
        private final List<TreeScope> protectedTrees;
        private final Path backupRoot;
        private final ProtectionObserver observer;
        private final AtomicInteger backupSequence = new AtomicInteger();
        private final AtomicLong snapshotBytes = new AtomicLong();
        private final AtomicLong conflictBytes = new AtomicLong();
        private final Map<Path, SnapshotEntry> protectedSnapshot = new LinkedHashMap<>();
        private final Map<Path, SnapshotEntry> currentRunSnapshot = new LinkedHashMap<>();
        private final Map<Path, Set<PosixFilePermission>> originalPermissions = new LinkedHashMap<>();
        private final Map<Path, Set<PosixFilePermission>> guardedPermissions = new LinkedHashMap<>();
        private Path activeCandidate;
        private boolean conflictDetected;
        private boolean closed;

        private RunProtection(
                Path repository,
                Path stableRoot,
                Path currentRun,
                Set<Path> candidates,
                List<TreeScope> protectedTrees,
                Path backupRoot,
                ProtectionObserver observer
        ) {
            this.repository = repository;
            this.stableRoot = stableRoot;
            this.currentRun = currentRun;
            this.candidates = Set.copyOf(candidates);
            this.protectedTrees = List.copyOf(protectedTrees);
            this.backupRoot = backupRoot;
            this.observer = observer;
        }

        private static RunProtection establish(
                ObjectMapper objectMapper,
                Path repository,
                Path stableRoot,
                Path currentRun,
                List<Path> sourceDirectories,
                List<Path> mapperFiles,
                List<Path> candidates,
                ProtectionObserver observer
        ) throws IOException {
            Objects.requireNonNull(objectMapper, "objectMapper");
            Objects.requireNonNull(sourceDirectories, "sourceDirectories");
            Objects.requireNonNull(mapperFiles, "mapperFiles");
            Objects.requireNonNull(candidates, "candidates");
            Path normalizedRepository = normalizeDirectory(repository, "repository");
            Path normalizedStable = normalizeDirectory(stableRoot, "stable output");
            Path normalizedCurrentRun = normalizeDirectory(currentRun, "current run");
            requireNoSymlinkPath(normalizedStable, normalizedCurrentRun, "current run");

            Set<Path> normalizedCandidates = new LinkedHashSet<>();
            for (Path candidate : candidates) {
                Path normalizedCandidate = normalizeDirectory(candidate, "candidate directory");
                requireNoSymlinkPath(normalizedCurrentRun, normalizedCandidate, "candidate directory");
                if (!normalizedCandidates.add(normalizedCandidate)) {
                    throw new IllegalArgumentException("candidate directories must be unique: " + normalizedCandidate);
                }
            }

            if (sourceDirectories.isEmpty()) {
                throw new IllegalArgumentException("source directories must not be empty");
            }
            List<Path> normalizedSourceDirectories = new ArrayList<>();
            for (Path sourceDirectory : sourceDirectories) {
                Path normalizedSource = normalizeDirectory(sourceDirectory, "source directory");
                requireNoSymlinkPath(normalizedRepository, normalizedSource, "source directory");
                normalizedSourceDirectories.add(normalizedSource);
            }
            normalizedSourceDirectories = minimalRoots(normalizedSourceDirectories);

            Set<Path> normalizedMapperFiles = new LinkedHashSet<>();
            for (Path mapperFile : mapperFiles) {
                Path normalizedMapper = mapperFile.toAbsolutePath().normalize();
                requireNoSymlinkPath(normalizedRepository, normalizedMapper, "mapper input");
                if (!Files.isRegularFile(normalizedMapper, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("mapper input must be a non-symlink regular file: " + normalizedMapper);
                }
                if (normalizedSourceDirectories.stream().noneMatch(normalizedMapper::startsWith)) {
                    throw new IllegalArgumentException(
                            "mapper input is outside configured source directories: " + normalizedMapper
                    );
                }
                normalizedMapperFiles.add(normalizedMapper);
            }

            List<TreeScope> protectedTrees = new ArrayList<>();
            for (Path sourceDirectory : normalizedSourceDirectories) {
                Set<Path> scopedMapperFiles = normalizedMapperFiles.stream()
                        .filter(path -> path.startsWith(sourceDirectory))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                if (!scopedMapperFiles.isEmpty()) {
                    protectedTrees.add(new TreeScope(
                            sourceDirectory,
                            Set.of(),
                            scopedMapperFiles
                    ));
                }
            }
            protectedTrees.add(new TreeScope(
                    normalizedStable,
                    Set.of(normalizedCurrentRun),
                    Set.of()
            ));
            Set<Path> wholeProtectedPaths = new LinkedHashSet<>();
            for (TreeScope tree : protectedTrees) {
                wholeProtectedPaths.addAll(treePaths(tree));
            }

            Path backup = Files.createTempDirectory("mybatis-sql-review-run-protected-");
            RunProtection result = new RunProtection(
                    normalizedRepository,
                    normalizedStable,
                    normalizedCurrentRun,
                    normalizedCandidates,
                    protectedTrees,
                    backup,
                    observer == null ? ProtectionObserver.NOOP : observer
            );
            try {
                result.capturePaths(wholeProtectedPaths, result.protectedSnapshot, true);
                result.captureTree(normalizedCurrentRun, result.currentRunSnapshot, false);
                result.protectPermissions(wholeProtectedPaths, true);
                result.protectPermissions(result.currentRunSnapshot.keySet(), false);
                return result;
            } catch (Exception setupFailure) {
                IOException restoreFailure = restorePermissionsBestEffort(result.originalPermissions);
                cleanupBestEffort(backup);
                IllegalStateException failure = new IllegalStateException(
                        "failed to establish run-level POSIX filesystem protection: "
                                + concise(setupFailure),
                        setupFailure
                );
                if (restoreFailure != null) {
                    failure.addSuppressed(restoreFailure);
                }
                throw failure;
            }
        }

        private TaskScope openTask(Path requestedCandidate) throws IOException {
            requireOpen();
            if (activeCandidate != null) {
                throw new IllegalStateException("only one MyBatis SQL task may hold filesystem protection");
            }
            Path normalizedCandidate = requestedCandidate.toAbsolutePath().normalize();
            if (!candidates.contains(normalizedCandidate)) {
                throw new IllegalArgumentException("candidate is not registered for the current run: " + normalizedCandidate);
            }
            List<String> protectedStale = protectedChanges(true);
            if (!protectedStale.isEmpty()) {
                conflictDetected = true;
                restoreProtectedPaths();
                throw WorkflowFailureException.session(
                        WorkflowFailureCategory.FILE_INTEGRITY_VIOLATION,
                        "protected repository/stable content changed: " + String.join("; ", protectedStale)
                                + "; recovery backup retained at " + backupRoot
                );
            }
            List<String> stale = currentRunChanges(null, true);
            if (!stale.isEmpty()) {
                conflictDetected = true;
                restoreCurrentRun(null);
                throw WorkflowFailureException.session(
                        WorkflowFailureCategory.FILE_INTEGRITY_VIOLATION,
                        "protected current run content changed: " + String.join("; ", stale)
                                + "; recovery backup retained at " + backupRoot
                );
            }
            activeCandidate = normalizedCandidate;
            makeOwnerWritable(normalizedCandidate);
            return new TaskScope(this, normalizedCandidate);
        }

        private void closeTask(Path candidate) {
            if (!Objects.equals(activeCandidate, candidate)) {
                throw new IllegalStateException("task filesystem scope is not active: " + candidate);
            }
            List<String> violations = new ArrayList<>();
            Throwable failure = null;
            try {
                applyGuardedPermission(candidate);
                violations.addAll(protectedChanges(true));
                violations.addAll(currentRunChanges(candidate, true));
                if (!violations.isEmpty()) {
                    conflictDetected = true;
                    restoreProtectedPaths();
                    restoreCurrentRun(candidate);
                }
                adoptCurrentRunSubtree(candidate);
            } catch (Throwable exception) {
                failure = exception;
                conflictDetected = true;
                try {
                    restoreProtectedPaths();
                    restoreCurrentRun(candidate);
                } catch (Throwable restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            } finally {
                activeCandidate = null;
            }
            if (!violations.isEmpty() || failure != null) {
                String message = (violations.isEmpty()
                        ? "current run filesystem verification failed"
                        : "protected repository/current run content changed: " + String.join("; ", violations))
                        + "; recovery backup retained at " + backupRoot;
                WorkflowFailureException exception = WorkflowFailureException.session(
                        WorkflowFailureCategory.FILE_INTEGRITY_VIOLATION,
                        message
                );
                if (failure != null) {
                    exception.addSuppressed(failure);
                }
                throw exception;
            }
        }

        private <T> SealedWrite<T> withJavaWritesSealed(
                List<Path> allowedPaths,
                CheckedOperation<T> operation
        ) throws Exception {
            requireOpen();
            Objects.requireNonNull(allowedPaths, "allowedPaths");
            Objects.requireNonNull(operation, "operation");
            if (activeCandidate != null) {
                throw new IllegalStateException("Java writes are forbidden while an Agent task is active");
            }
            List<Path> allowed = allowedPaths.stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .distinct()
                    .toList();
            for (Path path : allowed) {
                if (!path.startsWith(currentRun)) {
                    throw new IllegalArgumentException("Java write path escapes current run: " + path);
                }
                requireNoSymlinkPath(currentRun, path, "Java write path");
                if (Files.isSymbolicLink(path)
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException(
                            "Java write path must be a pre-created non-symlink regular file: " + path
                    );
                }
            }
            List<String> protectedStale = protectedChanges(true);
            if (!protectedStale.isEmpty()) {
                conflictDetected = true;
                restoreProtectedPaths();
                throw new IllegalStateException(
                        "protected repository/stable content changed: " + String.join("; ", protectedStale)
                                + "; recovery backup retained at " + backupRoot
                );
            }
            List<String> stale = currentRunChanges(null, true);
            if (!stale.isEmpty()) {
                conflictDetected = true;
                restoreCurrentRun(null);
                throw new IllegalStateException(
                        "protected current run content changed: " + String.join("; ", stale)
                                + "; recovery backup retained at " + backupRoot
                );
            }
            for (Path path : allowed) {
                makeOwnerWritable(path);
            }
            T value = null;
            Throwable operationFailure = null;
            try {
                value = operation.run();
            } catch (Throwable exception) {
                operationFailure = exception;
            }
            Throwable refreshFailure = null;
            Map<Path, CurrentEntry> sealed = new LinkedHashMap<>();
            try {
                observer.javaWritesCompletedBeforeSeal(allowed);
                for (Path path : allowed) {
                    applyGuardedPermission(path);
                }
                for (Path path : allowed) {
                    CurrentEntry entry = currentEntryOrNull(path, true);
                    if (entry == null || entry.kind() != Kind.REGULAR_FILE) {
                        throw new IllegalStateException("Java output disappeared before sealing: " + path);
                    }
                    sealed.put(path, entry);
                }
                observer.javaWritesSealed(allowed);
                for (Path path : allowed) {
                    adoptCurrentRunFile(path, sealed.get(path));
                }
                reapplyGuardedPermissions(currentRunSnapshot.keySet(), null);
                List<String> protectedViolations = protectedChanges(true);
                if (!protectedViolations.isEmpty()) {
                    conflictDetected = true;
                    restoreProtectedPaths();
                    throw new IllegalStateException(
                            "protected repository/stable content changed during Java writes: "
                                    + String.join("; ", protectedViolations)
                                    + "; recovery backup retained at " + backupRoot
                    );
                }
                List<String> violations = currentRunChanges(null, true);
                if (!violations.isEmpty()) {
                    conflictDetected = true;
                    restoreCurrentRun(null);
                    throw new IllegalStateException(
                            "protected current run content changed during Java writes: " + String.join("; ", violations)
                                    + "; recovery backup retained at " + backupRoot
                    );
                }
            } catch (Throwable exception) {
                conflictDetected = true;
                try {
                    restoreProtectedPaths();
                    restoreCurrentRun(null);
                } catch (Throwable restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
                refreshFailure = new IllegalStateException(
                        "Java write sealing/adoption failed: " + concise(exception)
                                + "; recovery backup retained at " + backupRoot,
                        exception
                );
            }
            if (operationFailure != null) {
                if (refreshFailure != null) {
                    operationFailure.addSuppressed(refreshFailure);
                }
                if (operationFailure instanceof Exception exception) {
                    throw exception;
                }
                throw (Error) operationFailure;
            }
            if (refreshFailure != null) {
                if (refreshFailure instanceof Exception exception) {
                    throw exception;
                }
                throw (Error) refreshFailure;
            }
            Map<Path, SealedFile> sealedFiles = new LinkedHashMap<>();
            for (Map.Entry<Path, CurrentEntry> entry : sealed.entrySet()) {
                CurrentEntry file = entry.getValue();
                sealedFiles.put(entry.getKey(), new SealedFile(
                        entry.getKey(), file.fileKey(), file.lastModifiedTime(), file.size(), file.sha256()
                ));
            }
            return new SealedWrite<>(value, sealedFiles);
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            List<String> violations = new ArrayList<>();
            Throwable detectionFailure = null;
            Throwable restorationFailure = null;
            try {
                violations.addAll(protectedChanges(true));
                violations.addAll(currentRunChanges(null, true));
            } catch (Throwable exception) {
                detectionFailure = exception;
                violations.add("filesystem detection failed: " + concise(exception));
            }
            if (!violations.isEmpty() || detectionFailure != null) {
                conflictDetected = true;
                try {
                    restoreProtectedPaths();
                    restoreCurrentRun(null);
                    List<String> remaining = new ArrayList<>();
                    remaining.addAll(protectedChanges(false));
                    remaining.addAll(currentRunChanges(null, false));
                    if (!remaining.isEmpty()) {
                        throw new IOException("filesystem restoration remained incomplete: " + remaining);
                    }
                } catch (Throwable exception) {
                    restorationFailure = exception;
                }
            }
            IOException permissionFailure = restorePermissionsBestEffort(originalPermissions);
            if (!conflictDetected && restorationFailure == null && permissionFailure == null) {
                cleanupBestEffort(backupRoot);
            }
            if (!violations.isEmpty() || restorationFailure != null || permissionFailure != null) {
                String message = violations.isEmpty()
                        ? "run-level filesystem protection restoration failed"
                        : "protected filesystem content changed: " + String.join("; ", violations)
                          + "; recovery backup retained at " + backupRoot;
                IllegalStateException exception = new IllegalStateException(message);
                if (detectionFailure != null) {
                    exception.addSuppressed(detectionFailure);
                }
                if (restorationFailure != null) {
                    exception.addSuppressed(restorationFailure);
                }
                if (permissionFailure != null) {
                    exception.addSuppressed(permissionFailure);
                }
                throw exception;
            }
        }

        private void capturePaths(
                Iterable<Path> paths,
                Map<Path, SnapshotEntry> destination,
                boolean notifyObserver
        ) throws IOException {
            for (Path path : paths) {
                Path normalized = path.toAbsolutePath().normalize();
                if (destination.containsKey(normalized)) {
                    continue;
                }
                SnapshotEntry entry = captureEntry(normalized);
                destination.put(normalized, entry);
                if (notifyObserver && entry.kind() == Kind.REGULAR_FILE) {
                    observer.backedUp(normalized);
                }
            }
        }

        private void captureTree(
                Path root,
                Map<Path, SnapshotEntry> destination,
                boolean notifyObserver
        ) throws IOException {
            try (var paths = Files.walk(root)) {
                capturePaths(paths.sorted().toList(), destination, notifyObserver);
            }
        }

        private SnapshotEntry captureEntry(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            Kind kind = Kind.of(attributes);
            Path backupFile = null;
            String hash = "";
            String symbolicLink = "";
            if (kind == Kind.REGULAR_FILE) {
                long fileBytes = attributes.size();
                if (fileBytes > MAX_SNAPSHOT_FILE_BYTES) {
                    throw new IllegalStateException(
                            "protected snapshot file exceeds configured limit "
                                    + MAX_SNAPSHOT_FILE_BYTES + ": " + path
                    );
                }
                long remaining = MAX_SNAPSHOT_TOTAL_BYTES - snapshotBytes.get();
                if (fileBytes > remaining) {
                    throw new IllegalStateException(
                            "protected snapshot exceeds configured total limit "
                                    + MAX_SNAPSHOT_TOTAL_BYTES + " at " + path
                    );
                }
                backupFile = backupRoot.resolve("files").resolve(
                        "%08d.bin".formatted(backupSequence.getAndIncrement())
                );
                Files.createDirectories(backupFile.getParent());
                StableCopy copy = copyRegularStable(path, backupFile, Math.min(
                        MAX_SNAPSHOT_FILE_BYTES, remaining
                ), false);
                if (copy.truncated()) {
                    throw new IllegalStateException("protected snapshot changed or exceeded limit: " + path);
                }
                snapshotBytes.addAndGet(copy.bytes());
                hash = copy.sha256();
            } else if (kind == Kind.SYMBOLIC_LINK) {
                symbolicLink = Files.readSymbolicLink(path).toString();
            }
            return new SnapshotEntry(
                    path,
                    kind,
                    attributes.fileKey() == null ? "" : attributes.fileKey().toString(),
                    attributes.lastModifiedTime(),
                    attributes.size(),
                    hash,
                    symbolicLink,
                    backupFile
            );
        }

        private void protectPermissions(Iterable<Path> paths, boolean notifyObserver) throws IOException {
            // POSIX modes prevent ordinary writes, but a process running as the same owner can chmod them back.
            // The no-follow hash/identity snapshot plus mandatory per-task restore is the authoritative boundary.
            List<Path> ordered = new ArrayList<>();
            paths.forEach(ordered::add);
            for (Path path : ordered.stream()
                    .distinct()
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList()) {
                if (Files.isSymbolicLink(path)) {
                    continue;
                }
                if (Files.getFileAttributeView(
                        path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS
                ) == null) {
                    throw new IllegalStateException(
                            "POSIX permissions are required for MyBatis SQL review filesystem protection: " + path
                    );
                }
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                        path, LinkOption.NOFOLLOW_LINKS
                );
                originalPermissions.putIfAbsent(path, Set.copyOf(permissions));
                Set<PosixFilePermission> readOnly = withoutWrites(permissions);
                Files.setPosixFilePermissions(path, readOnly);
                guardedPermissions.put(path, Set.copyOf(readOnly));
                if (notifyObserver) {
                    observer.permissionProtected(path);
                }
            }
        }

        private void adoptCurrentRunSubtree(Path root) throws IOException {
            Path normalized = root.toAbsolutePath().normalize();
            currentRunSnapshot.keySet().removeIf(path -> path.startsWith(normalized));
            if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            requireNoSymlinkPath(currentRun, normalized, "current run Java output");
            captureTree(normalized, currentRunSnapshot, false);
            protectPermissions(
                    currentRunSnapshot.keySet().stream().filter(path -> path.startsWith(normalized)).toList(),
                    false
            );
        }

        private void adoptCurrentRunFile(Path path, CurrentEntry sealed) throws IOException {
            Path normalized = path.toAbsolutePath().normalize();
            requireNoSymlinkPath(currentRun, normalized, "current run Java output");
            SnapshotEntry captured = captureEntry(normalized);
            if (!captured.sameContent(sealed, true)) {
                throw new IllegalStateException("Java output changed between sealing and adoption: " + normalized);
            }
            currentRunSnapshot.put(normalized, captured);
            protectPermissions(List.of(normalized), false);
            CurrentEntry after = currentEntryOrNull(normalized, true);
            if (after == null || !captured.sameContent(after, true)) {
                throw new IllegalStateException("Java output changed after adoption: " + normalized);
            }
        }

        private List<String> currentRunChanges(Path excludedRoot, boolean compareIdentity) throws IOException {
            Map<Path, CurrentEntry> actual = currentTree(currentRun, currentRunSnapshot);
            return changes(currentRunSnapshot, actual, excludedRoot, compareIdentity, "current run");
        }

        private List<String> protectedChanges(boolean compareIdentity) throws IOException {
            return changes(
                    protectedSnapshot,
                    currentProtectedTree(),
                    null,
                    compareIdentity,
                    "protected repository/stable"
            );
        }

        private List<String> changes(
                Map<Path, SnapshotEntry> expected,
                Map<Path, CurrentEntry> actual,
                Path excludedRoot,
                boolean compareIdentity,
                String label
        ) throws IOException {
            List<String> changes = new ArrayList<>();
            Set<Path> all = new LinkedHashSet<>(expected.keySet());
            all.addAll(actual.keySet());
            for (Path path : all.stream().sorted().toList()) {
                if (excludedRoot != null && path.startsWith(excludedRoot)) {
                    continue;
                }
                SnapshotEntry expectedEntry = expected.get(path);
                CurrentEntry actualEntry = actual.get(path);
                if (expectedEntry == null) {
                    changes.add(label + " " + display(path) + " created");
                } else if (actualEntry == null) {
                    changes.add(label + " " + display(path) + " deleted");
                } else if (!expectedEntry.sameContent(actualEntry, compareIdentity)) {
                    changes.add(label + " " + display(path) + " changed");
                }
                if (actualEntry != null && guardedPermissions.containsKey(path)
                        && !Files.isSymbolicLink(path)) {
                    Set<PosixFilePermission> actualPermissions = Files.getPosixFilePermissions(
                            path, LinkOption.NOFOLLOW_LINKS
                    );
                    if (!actualPermissions.equals(guardedPermissions.get(path))) {
                        changes.add(label + " " + display(path) + " permissions changed");
                    }
                }
            }
            return changes;
        }

        private void restoreProtectedPaths() throws IOException {
            IOException traversalFailure = restoreTraversalPermissions(
                    originalPermissions, protectedSnapshot.keySet()
            );
            if (traversalFailure != null) {
                throw traversalFailure;
            }
            Map<Path, CurrentEntry> actual = currentProtectedTree();
            preserveConflictingRegularFiles(protectedSnapshot, actual, "protected");
            sanitizeSnapshotAncestors(protectedSnapshot);
            makeRestorationWritable(protectedSnapshot.keySet());
            for (Path path : actual.keySet().stream()
                    .filter(value -> !protectedSnapshot.containsKey(value))
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                deleteNoFollow(path);
            }
            restoreEntries(protectedSnapshot, null);
            refreshRestoredIdentity(protectedSnapshot, null);
            reapplyGuardedPermissions(protectedSnapshot.keySet(), null);
        }

        private Map<Path, CurrentEntry> currentProtectedTree() throws IOException {
            Map<Path, CurrentEntry> result = new LinkedHashMap<>();
            for (TreeScope tree : protectedTrees) {
                for (Path path : treePaths(tree)) {
                    CurrentEntry entry = currentEntryOrNull(
                            path, protectedSnapshot.containsKey(path)
                    );
                    if (entry != null) {
                        result.put(path, entry);
                    }
                }
            }
            return result;
        }

        private void restoreCurrentRun(Path excludedRoot) throws IOException {
            IOException traversalFailure = restoreTraversalPermissions(
                    originalPermissions, currentRunSnapshot.keySet()
            );
            if (traversalFailure != null) {
                throw traversalFailure;
            }
            Map<Path, CurrentEntry> actual = currentTree(currentRun, currentRunSnapshot);
            preserveConflictingRegularFiles(currentRunSnapshot, actual, "current-run");
            sanitizeSnapshotAncestors(currentRunSnapshot);
            makeRestorationWritable(currentRunSnapshot.keySet());
            for (Path path : actual.keySet().stream()
                    .filter(path -> !currentRunSnapshot.containsKey(path))
                    .filter(path -> excludedRoot == null || !path.startsWith(excludedRoot))
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                deleteNoFollow(path);
            }
            restoreEntries(currentRunSnapshot, excludedRoot);
            refreshRestoredIdentity(currentRunSnapshot, excludedRoot);
            reapplyGuardedPermissions(currentRunSnapshot.keySet(), excludedRoot);
        }

        private void preserveConflictingRegularFiles(
                Map<Path, SnapshotEntry> expected,
                Map<Path, CurrentEntry> actual,
                String label
        ) throws IOException {
            Path conflictDirectory = backupRoot.resolve("conflicts");
            Path readme = conflictDirectory.resolve("README.txt");
            for (Map.Entry<Path, CurrentEntry> item : actual.entrySet()) {
                Path path = item.getKey();
                CurrentEntry current = item.getValue();
                SnapshotEntry before = expected.get(path);
                if (current.kind() != Kind.REGULAR_FILE
                        || (before != null && before.sameContent(current, false))) {
                    continue;
                }
                Files.createDirectories(conflictDirectory);
                Path copy = conflictDirectory.resolve(
                        "%08d.bin".formatted(backupSequence.getAndIncrement())
                );
                long remaining = Math.max(0, MAX_CONFLICT_TOTAL_BYTES - conflictBytes.get());
                long limit = Math.min(MAX_CONFLICT_PREFIX_BYTES, remaining);
                StableCopy preserved = null;
                String warning = "";
                if (limit > 0) {
                    try {
                        preserved = copyRegularStable(path, copy, limit, true);
                        conflictBytes.addAndGet(preserved.bytes());
                        if (preserved.truncated()) {
                            warning = " [TRUNCATED prefix; original_size=" + current.size() + "]";
                        }
                    } catch (IOException | RuntimeException exception) {
                        warning = " [COPY_UNSTABLE: " + concise(exception) + "]";
                        Files.deleteIfExists(copy);
                    }
                } else {
                    warning = " [METADATA_ONLY conflict budget exhausted; original_size="
                            + current.size() + "]";
                }
                Files.writeString(
                        readme,
                        label + " " + path + " size=" + current.size()
                                + (preserved == null ? "" : " -> " + copy.getFileName())
                                + warning + System.lineSeparator(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }
        }

        private static StableCopy copyRegularStable(
                Path source,
                Path destination,
                long maxBytes,
                boolean allowPrefix
        ) throws IOException {
            BasicFileAttributes before = Files.readAttributes(
                    source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if (!before.isRegularFile() || before.isSymbolicLink()) {
                throw new IOException("bounded copy source is not a regular file: " + source);
            }
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 unavailable", exception);
            }
            long copied = 0;
            boolean truncated = false;
            try (InputStream input = Files.newInputStream(
                    source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS
            ); var output = Files.newOutputStream(
                    destination,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                byte[] buffer = new byte[8192];
                while (true) {
                    int requested = (int) Math.min(buffer.length, Math.max(1, maxBytes - copied + 1));
                    int read = input.read(buffer, 0, requested);
                    if (read < 0) {
                        break;
                    }
                    if (copied + read > maxBytes) {
                        int accepted = (int) (maxBytes - copied);
                        if (accepted > 0) {
                            output.write(buffer, 0, accepted);
                            digest.update(buffer, 0, accepted);
                            copied += accepted;
                        }
                        truncated = true;
                        break;
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    copied += read;
                }
            }
            BasicFileAttributes after = Files.readAttributes(
                    source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if (!after.isRegularFile()
                    || !Objects.equals(before.fileKey(), after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || before.size() != after.size()) {
                throw new IOException("bounded copy source changed while reading: " + source);
            }
            if (!allowPrefix && (truncated || copied != before.size())) {
                throw new IOException("bounded copy exceeded limit " + maxBytes + ": " + source);
            }
            return new StableCopy(copied, HexFormat.of().formatHex(digest.digest()), truncated);
        }

        private void sanitizeSnapshotAncestors(Map<Path, SnapshotEntry> entries) throws IOException {
            for (SnapshotEntry expected : entries.values().stream()
                    .sorted(Comparator.comparingInt(value -> value.path().getNameCount()))
                    .toList()) {
                Path path = expected.path();
                BasicFileAttributes actual = readAttributesOrNull(path);
                if (actual == null || expected.kind().matches(actual)) {
                    continue;
                }
                Path parent = path.getParent();
                if (parent == null) {
                    throw new IOException("cannot safely restore mismatched filesystem root: " + path);
                }
                makeOwnerWritable(parent);
                deleteNoFollow(path);
                if (expected.kind() == Kind.DIRECTORY) {
                    Files.createDirectory(path);
                    makeOwnerWritable(path);
                } else if (expected.kind() == Kind.SYMBOLIC_LINK) {
                    Files.createSymbolicLink(path, Path.of(expected.symbolicLinkTarget()));
                }
            }
        }

        private void refreshRestoredIdentity(
                Map<Path, SnapshotEntry> entries,
                Path excludedRoot
        ) throws IOException {
            for (Map.Entry<Path, SnapshotEntry> item : new ArrayList<>(entries.entrySet())) {
                Path path = item.getKey();
                if (excludedRoot != null && path.startsWith(excludedRoot)) {
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
                );
                SnapshotEntry previous = item.getValue();
                entries.put(path, new SnapshotEntry(
                        path,
                        previous.kind(),
                        attributes.fileKey() == null ? "" : attributes.fileKey().toString(),
                        attributes.lastModifiedTime(),
                        attributes.size(),
                        previous.sha256(),
                        previous.symbolicLinkTarget(),
                        previous.backupFile()
                ));
            }
        }

        private void restoreEntries(Map<Path, SnapshotEntry> entries, Path excludedRoot) throws IOException {
            for (SnapshotEntry entry : entries.values().stream()
                    .filter(value -> excludedRoot == null || !value.path().startsWith(excludedRoot))
                    .sorted(Comparator.comparingInt(value -> value.path().getNameCount()))
                    .toList()) {
                restoreEntry(entry);
            }
            for (SnapshotEntry entry : entries.values().stream()
                    .filter(value -> value.kind() == Kind.DIRECTORY)
                    .filter(value -> excludedRoot == null || !value.path().startsWith(excludedRoot))
                    .sorted(Comparator.comparingInt((SnapshotEntry value) -> value.path().getNameCount()).reversed())
                    .toList()) {
                Files.setLastModifiedTime(entry.path(), entry.lastModifiedTime());
            }
        }

        private void makeRestorationWritable(Iterable<Path> paths) throws IOException {
            IOException failure = null;
            List<Path> ordered = new ArrayList<>();
            paths.forEach(ordered::add);
            for (Path path : ordered.stream()
                    .distinct()
                    .sorted(Comparator.comparingInt(Path::getNameCount))
                    .toList()) {
                try {
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                        makeOwnerWritable(path);
                    }
                } catch (IOException | RuntimeException exception) {
                    if (failure == null) {
                        failure = exception instanceof IOException io ? io : new IOException(exception);
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private void reapplyGuardedPermissions(Iterable<Path> paths, Path excludedRoot) throws IOException {
            for (Path path : guardedPermissions.keySet().stream()
                    .filter(value -> contains(paths, value))
                    .filter(value -> excludedRoot == null || !value.startsWith(excludedRoot))
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList()) {
                applyGuardedPermission(path);
            }
        }

        private void makeOwnerWritable(Path path) throws IOException {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                return;
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
        }

        private void applyGuardedPermission(Path path) throws IOException {
            Set<PosixFilePermission> permissions = guardedPermissions.get(path);
            if (permissions != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(path)) {
                Files.setPosixFilePermissions(path, permissions);
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("run-level filesystem protection is already closed");
            }
        }

        private static boolean contains(Iterable<Path> paths, Path expected) {
            for (Path path : paths) {
                if (path.equals(expected)) {
                    return true;
                }
            }
            return false;
        }

        private static Map<Path, CurrentEntry> currentTree(
                Path root,
                Map<Path, SnapshotEntry> expected
        ) throws IOException {
            Map<Path, CurrentEntry> result = new LinkedHashMap<>();
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                return result;
            }
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted().toList()) {
                    Path normalized = path.toAbsolutePath().normalize();
                    CurrentEntry entry = currentEntryOrNull(path, expected.containsKey(normalized));
                    if (entry != null) {
                        result.put(normalized, entry);
                    }
                }
            }
            return result;
        }

        private static List<Path> treePaths(TreeScope scope) throws IOException {
            List<Path> result = new ArrayList<>();
            collectTreePaths(scope.root(), scope, result, true);
            return List.copyOf(result);
        }

        private static List<TreeScope> gitCommonTrees(Path repository) throws IOException {
            Path dotGit = repository.resolve(".git");
            if (!Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(dotGit)) {
                return List.of();
            }
            String marker = Files.readString(dotGit).trim();
            if (!marker.startsWith("gitdir:")) {
                throw new IllegalStateException("repository .git file has an unsupported format: " + dotGit);
            }
            Path gitDirectory = Path.of(marker.substring("gitdir:".length()).trim());
            if (!gitDirectory.isAbsolute()) {
                gitDirectory = dotGit.getParent().resolve(gitDirectory);
            }
            gitDirectory = gitDirectory.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(gitDirectory)
                    || !Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("repository git directory is missing or unsafe: " + gitDirectory);
            }
            List<TreeScope> result = new ArrayList<>();
            if (!gitDirectory.startsWith(repository)) {
                result.add(new TreeScope(gitDirectory, Set.of(), Set.of()));
            }
            Path commonMarker = gitDirectory.resolve("commondir");
            if (Files.isRegularFile(commonMarker, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(commonMarker)) {
                Path common = Path.of(Files.readString(commonMarker).trim());
                if (!common.isAbsolute()) {
                    common = gitDirectory.resolve(common);
                }
                common = common.toAbsolutePath().normalize();
                if (Files.isSymbolicLink(common)
                        || !Files.isDirectory(common, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("git common directory is missing or unsafe: " + common);
                }
                if (!common.startsWith(repository) && !common.startsWith(gitDirectory)) {
                    result.add(new TreeScope(common, Set.of(), Set.of()));
                }
            }
            return List.copyOf(result);
        }

        private static long positiveLimit(String property, long defaultValue) {
            long value = Long.getLong(property, defaultValue);
            if (value <= 0) {
                throw new IllegalStateException(property + " must be positive");
            }
            return value;
        }

        private static void collectTreePaths(
                Path path,
                TreeScope scope,
                List<Path> result,
                boolean root
        ) throws IOException {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Path normalized = path.toAbsolutePath().normalize();
            BasicFileAttributes attributes = Files.readAttributes(
                    normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if (!root && attributes.isDirectory() && scope.excludesDirectory(normalized)) {
                return;
            }
            if (scope.restrictsRegularFiles()) {
                if (attributes.isDirectory()) {
                    boolean leadsToIncludedFile = scope.includedRegularFiles().stream()
                            .anyMatch(included -> included.startsWith(normalized));
                    if (!leadsToIncludedFile) {
                        return;
                    }
                } else if (!scope.includedRegularFiles().contains(normalized)) {
                    return;
                }
            }
            result.add(normalized);
            if (!attributes.isDirectory()) {
                return;
            }
            try (var children = Files.list(normalized)) {
                for (Path child : children.sorted().toList()) {
                    collectTreePaths(child, scope, result, false);
                }
            }
        }

        private static CurrentEntry currentEntryOrNull(Path path) throws IOException {
            return currentEntryOrNull(path, true);
        }

        private static CurrentEntry currentEntryOrNull(Path path, boolean hashContent) throws IOException {
            BasicFileAttributes attributes = readAttributesOrNull(path);
            if (attributes == null) {
                return null;
            }
            Kind kind = Kind.of(attributes);
            String hash = kind == Kind.REGULAR_FILE && hashContent
                    && attributes.size() <= MAX_SNAPSHOT_FILE_BYTES
                    ? sha256(path) : "";
            String symbolicLink = kind == Kind.SYMBOLIC_LINK
                    ? Files.readSymbolicLink(path).toString()
                    : "";
            return new CurrentEntry(
                    kind,
                    attributes.fileKey() == null ? "" : attributes.fileKey().toString(),
                    attributes.lastModifiedTime(),
                    attributes.size(),
                    hash,
                    symbolicLink
            );
        }

        private static List<Path> pathChain(Path base, Path target) {
            if (target == null || !target.startsWith(base)) {
                throw new IllegalStateException("protected path escapes its root: " + target);
            }
            List<Path> result = new ArrayList<>();
            for (Path path = base; path != null; path = next(path, target)) {
                result.add(path);
                if (path.equals(target)) {
                    break;
                }
            }
            return result;
        }

        private static void requireNoSymlinkPath(Path base, Path target, String label) throws IOException {
            if (!target.startsWith(base)) {
                throw new IllegalStateException(label + " escapes protected root: " + target);
            }
            for (Path path : pathChain(base, target)) {
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalStateException(label + " contains a symlink component: " + path);
                }
            }
        }

        private static Path nearestExisting(Path path) {
            Path current = path;
            while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                current = current.getParent();
            }
            return current == null ? path : current;
        }

        private record StableCopy(long bytes, String sha256, boolean truncated) {
        }

        private record TreeScope(
                Path root,
                Set<Path> excludedRoots,
                Set<Path> includedRegularFiles
        ) {
            private TreeScope {
                root = root.toAbsolutePath().normalize();
                excludedRoots = Set.copyOf(excludedRoots);
                includedRegularFiles = includedRegularFiles.stream()
                        .map(path -> path.toAbsolutePath().normalize())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            }

            private boolean excludesDirectory(Path directory) {
                Path normalized = directory.toAbsolutePath().normalize();
                return excludedRoots.stream().anyMatch(normalized::startsWith);
            }

            private boolean restrictsRegularFiles() {
                return !includedRegularFiles.isEmpty();
            }
        }
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
            long size,
            String sha256,
            String symbolicLinkTarget,
            Path backupFile
    ) {
        private boolean sameContent(CurrentEntry actual, boolean compareIdentity) {
            return kind == actual.kind()
                    && (!compareIdentity || (fileKey.equals(actual.fileKey())
                    && lastModifiedTime.equals(actual.lastModifiedTime())))
                    && size == actual.size()
                    && sha256.equals(actual.sha256())
                    && symbolicLinkTarget.equals(actual.symbolicLinkTarget());
        }
    }

    private record CurrentEntry(
            Kind kind,
            String fileKey,
            FileTime lastModifiedTime,
            long size,
            String sha256,
            String symbolicLinkTarget
    ) {
    }

    record SealedFile(
            Path path,
            String fileKey,
            FileTime lastModifiedTime,
            long size,
            String sha256
    ) {
    }

    record SealedWrite<T>(T value, Map<Path, SealedFile> files) {
        SealedWrite {
            files = Map.copyOf(files);
        }
    }
}
