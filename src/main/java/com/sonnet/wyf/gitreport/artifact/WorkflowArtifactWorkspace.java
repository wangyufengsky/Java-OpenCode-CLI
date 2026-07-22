package com.sonnet.wyf.gitreport.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.console.WorkflowRunContext;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

public final class WorkflowArtifactWorkspace {
    public static final String PUBLICATION_MANIFEST = ".publication.json";
    private static final String GENERATION_FILE = ".publication-generation";
    private static final String PUBLISH_LOCK = ".publish.lock";
    private static final Set<String> RESERVED_ROOT_NAMES = Set.of(
            "runs", PUBLICATION_MANIFEST, GENERATION_FILE, PUBLISH_LOCK
    );

    private final ObjectMapper objectMapper;
    private final String chainId;
    private final WorkflowRunRequest request;
    private final Path stableRoot;
    private final Path runRoot;
    private final Path preparationRoot;
    private final Path bundleRoot;
    private final long publicationGeneration;
    private final PublicationFailureInjector publicationFailureInjector;
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final Map<String, String> pathMappings = new LinkedHashMap<>();

    private WorkflowArtifactWorkspace(
            ObjectMapper objectMapper,
            String chainId,
            WorkflowRunRequest request,
            Path stableRoot,
            long publicationGeneration,
            PublicationFailureInjector publicationFailureInjector
    ) {
        this.objectMapper = objectMapper;
        this.chainId = chainId;
        this.request = request;
        this.stableRoot = stableRoot.toAbsolutePath().normalize();
        this.runRoot = inside(this.stableRoot, this.stableRoot.resolve("runs").resolve(request.executionId()));
        this.preparationRoot = inside(runRoot, runRoot.resolve("preparation"));
        this.bundleRoot = inside(runRoot, runRoot.resolve("bundle"));
        this.publicationGeneration = publicationGeneration;
        this.publicationFailureInjector = publicationFailureInjector;
        this.pathMappings.put(this.stableRoot.toString(), this.bundleRoot.toString());
    }

    public static WorkflowArtifactWorkspace start(
            ObjectMapper objectMapper,
            String chainId,
            WorkflowRunRequest request,
            Path stableRoot,
            boolean seedFromStable
    ) throws IOException {
        return start(objectMapper, chainId, request, stableRoot, seedFromStable, new PublicationFailureInjector() {
        });
    }

    static WorkflowArtifactWorkspace start(
            ObjectMapper objectMapper,
            String chainId,
            WorkflowRunRequest request,
            Path stableRoot,
            boolean seedFromStable,
            PublicationFailureInjector publicationFailureInjector
    ) throws IOException {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(stableRoot, "stableRoot");
        Objects.requireNonNull(publicationFailureInjector, "publicationFailureInjector");
        if (request.executionId() == null || request.executionId().isBlank()) {
            throw new IllegalArgumentException("workflow executionId is required");
        }
        Path normalizedRoot = stableRoot.toAbsolutePath().normalize();
        requireOrCreateSecureRoot(normalizedRoot);
        Path requestedRunRoot = inside(normalizedRoot, normalizedRoot.resolve("runs").resolve(request.executionId()));
        requireNoSymlinkComponents(normalizedRoot, requestedRunRoot, "workflow execution directory");
        if (Files.exists(requestedRunRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("workflow execution directory already exists: " + requestedRunRoot);
        }
        long generation = nextGeneration(normalizedRoot);
        WorkflowArtifactWorkspace workspace = new WorkflowArtifactWorkspace(
                objectMapper,
                chainId,
                request,
                normalizedRoot,
                generation,
                publicationFailureInjector
        );
        boolean ownsRunRoot = false;
        try {
            createDirectoriesSecure(normalizedRoot, workspace.runRoot.getParent());
            Files.createDirectory(workspace.runRoot);
            ownsRunRoot = true;
            Files.createDirectories(workspace.preparationRoot);
            Files.createDirectories(workspace.bundleRoot);
            if (seedFromStable) {
                workspace.copyStableToBundle();
                workspace.rebaseBundlePaths(workspace.stableRoot.toString(), workspace.bundleRoot.toString());
            }
            workspace.writeRunManifest("CREATED", "");
            return workspace;
        } catch (IOException | RuntimeException startFailure) {
            if (ownsRunRoot) {
                workspace.markFailed(startFailure.getMessage());
            }
            throw startFailure;
        }
    }

    public static void markStartFailedBestEffort(
            ObjectMapper objectMapper,
            String chainId,
            WorkflowRunRequest request,
            Path stableRoot,
            String reason
    ) {
        if (objectMapper == null || chainId == null || request == null || stableRoot == null) {
            return;
        }
        Path normalizedRoot = stableRoot.toAbsolutePath().normalize();
        try {
            requireOrCreateSecureRoot(normalizedRoot);
            Path runsRoot = inside(normalizedRoot, normalizedRoot.resolve("runs"));
            createDirectoriesSecure(normalizedRoot, runsRoot);
            Path runRoot = inside(normalizedRoot, runsRoot.resolve(request.executionId()));
            try {
                Files.createDirectory(runRoot);
            } catch (FileAlreadyExistsException existingExecution) {
                return;
            }
            WorkflowArtifactWorkspace workspace = new WorkflowArtifactWorkspace(
                    objectMapper, chainId, request, normalizedRoot, readGeneration(normalizedRoot),
                    new PublicationFailureInjector() {
                    }
            );
            workspace.writeRunManifest("FAILED", reason == null ? "" : reason);
        } catch (IOException | RuntimeException ignored) {
            // Best effort only: never replace the failure that prevented workspace startup.
        }
    }

    public String executionId() {
        return request.executionId();
    }

    public Path stableRoot() {
        return stableRoot;
    }

    public Path runRoot() {
        return runRoot;
    }

    public Path preparationRoot() {
        return preparationRoot;
    }

    public Path bundleRoot() {
        return bundleRoot;
    }

    public long publicationGeneration() {
        return publicationGeneration;
    }

    public void registerPathMapping(String stablePath, String executionPath) throws IOException {
        if (stablePath == null || stablePath.isBlank() || executionPath == null || executionPath.isBlank()) {
            return;
        }
        pathMappings.put(stablePath, executionPath);
        rebaseBundlePaths(stablePath, executionPath);
    }

    public TaskArtifactLayout nextTaskAttempt(String taskKey) throws IOException {
        String encoded = encodeTaskKey(taskKey);
        int attempt = attempts.computeIfAbsent(taskKey, ignored -> new AtomicInteger()).incrementAndGet();
        Path root = inside(
                runRoot,
                runRoot.resolve("tasks").resolve(encoded).resolve("attempts").resolve("%03d".formatted(attempt))
        );
        Path candidate = inside(root, root.resolve("candidate"));
        Files.createDirectories(candidate);
        return new TaskArtifactLayout(taskKey, attempt, root, candidate);
    }

    public TaskArtifactLayout nextSynthesisAttempt(String taskKey) throws IOException {
        int attempt = attempts.computeIfAbsent("synthesis:" + taskKey, ignored -> new AtomicInteger()).incrementAndGet();
        Path root = inside(runRoot, runRoot.resolve("synthesis").resolve("attempts").resolve("%03d".formatted(attempt)));
        Path candidate = inside(root, root.resolve("candidate"));
        Files.createDirectories(candidate);
        return new TaskArtifactLayout(taskKey, attempt, root, candidate);
    }

    public PublicationResult publish(String mainArtifact) throws IOException {
        for (Map.Entry<String, String> mapping : pathMappings.entrySet()) {
            rebaseBundlePaths(mapping.getValue(), mapping.getKey());
        }
        Path mainPath = inside(bundleRoot, bundleRoot.resolve(mainArtifact));
        requireNoSymlinkComponents(bundleRoot, mainPath, "workflow main artifact");
        if (!Files.isRegularFile(mainPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("workflow main artifact is missing from bundle: " + mainPath);
        }
        Path lockPath = requireNoSymlinkComponents(
                stableRoot, stableRoot.resolve(PUBLISH_LOCK), "publication lock");
        try (FileChannel channel = FileChannel.open(lockPath, CREATE, READ, WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock ignored = channel.lock()) {
            long latestGeneration = readGeneration(stableRoot);
            if (latestGeneration != publicationGeneration) {
                writeRunManifest("SUPERSEDED", "latest publication generation is " + latestGeneration);
                return new PublicationResult(false, true, stableRoot.resolve(mainArtifact));
            }

            Path publicationRoot = inside(runRoot, runRoot.resolve("publication"));
            Path stagedRoot = inside(publicationRoot, publicationRoot.resolve("staged"));
            Path backupRoot = inside(publicationRoot, publicationRoot.resolve("backup"));
            try {
                createDirectoriesSecure(runRoot, publicationRoot);
                deleteRecursivelySecure(runRoot, stagedRoot);
                deleteRecursivelySecure(runRoot, backupRoot);
                copyRecursivelySecure(bundleRoot, bundleRoot, runRoot, stagedRoot);
                Map<String, String> newArtifacts = artifacts(stagedRoot);
                Set<String> previousArtifacts = previousArtifactPaths();
                Path publicationManifest = requireNoSymlinkComponents(
                        stableRoot, stableRoot.resolve(PUBLICATION_MANIFEST), "publication manifest");
                boolean previousManifestExists = Files.isRegularFile(
                        publicationManifest, LinkOption.NOFOLLOW_LINKS);
                validatePublicationPaths(newArtifacts.keySet(), previousArtifacts, stagedRoot, backupRoot);
                backupPublishedState(previousArtifacts, previousManifestExists, backupRoot);
                try {
                    for (String previous : previousArtifacts.stream().sorted().toList()) {
                        if (!newArtifacts.containsKey(previous)) {
                            Path target = secureStableArtifact(previous, "publication deletion target");
                            if (Files.deleteIfExists(target)) {
                                publicationFailureInjector.afterDeletion(target);
                            }
                        }
                    }
                    for (String relative : newArtifacts.keySet()) {
                        Path target = secureStableArtifact(relative, "publication replacement target");
                        publishFile(stagedRoot, inside(stagedRoot, stagedRoot.resolve(relative)), stableRoot, target);
                        publicationFailureInjector.afterReplacement(target);
                    }

                    Map<String, Object> manifest = new LinkedHashMap<>();
                    manifest.put("schemaVersion", "workflow-publication/v1");
                    manifest.put("chainId", chainId);
                    manifest.put("executionId", executionId());
                    manifest.put("publicationGeneration", publicationGeneration);
                    manifest.put("mainArtifact", normalize(mainArtifact));
                    manifest.put("publishedAt", OffsetDateTime.now().toString());
                    manifest.put("artifacts", newArtifacts.entrySet().stream()
                            .map(entry -> Map.of("path", entry.getKey(), "sha256", entry.getValue()))
                            .toList());
                    atomicWriteJson(stableRoot.resolve(PUBLICATION_MANIFEST), manifest);
                    writeRunManifest("PUBLISHED", "");
                } catch (IOException | RuntimeException publicationFailure) {
                    // Complete rollback is for in-process failures, not crash-atomic directory switching.
                    try {
                        rollbackPublishedState(
                                newArtifacts.keySet(), previousArtifacts, previousManifestExists, backupRoot
                        );
                    } catch (IOException rollbackFailure) {
                        publicationFailure.addSuppressed(rollbackFailure);
                    }
                    throw publicationFailure;
                }
                Path publishedMain = secureStableArtifact(mainArtifact, "published main artifact");
                if (WorkflowRunContext.currentRunId() != null) {
                    WorkflowRunContext.setCurrentOutputPath(publishedMain.toString());
                }
                return new PublicationResult(true, false, publishedMain);
            } finally {
                deleteRecursivelySecure(runRoot, stagedRoot);
                deleteRecursivelySecure(runRoot, backupRoot);
            }
        }
    }

    public void markFailed(String reason) {
        try {
            writeRunManifest("FAILED", reason == null ? "" : reason);
        } catch (IOException ignored) {
            // Preserve the original workflow failure.
        }
    }

    private void copyStableToBundle() throws IOException {
        try (var entries = Files.list(stableRoot)) {
            for (Path entry : entries.toList()) {
                if (RESERVED_ROOT_NAMES.contains(entry.getFileName().toString())) {
                    continue;
                }
                requireNoSymlinkComponents(stableRoot, entry, "stable seed artifact");
                copyRecursivelySecure(stableRoot, entry, bundleRoot, bundleRoot.resolve(entry.getFileName()));
            }
        }
    }

    private void rebaseBundlePaths(String from, String to) throws IOException {
        if (from.equals(to)) {
            return;
        }
        try (var paths = Files.walk(bundleRoot)) {
            for (Path path : paths.toList()) {
                requireNoSymlinkComponents(bundleRoot, path, "bundle path");
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String filename = path.getFileName().toString().toLowerCase();
                if (!(filename.endsWith(".json")
                        || filename.endsWith(".md")
                        || filename.endsWith(".txt")
                        || filename.endsWith(".yml")
                        || filename.endsWith(".yaml"))) {
                    continue;
                }
                String content = Files.readString(path, StandardCharsets.UTF_8);
                if (content.contains(from)) {
                    Files.writeString(path, content.replace(from, to), StandardCharsets.UTF_8);
                }
            }
        }
    }

    private Map<String, String> artifacts(Path root) throws IOException {
        Map<String, String> artifacts = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                requireNoSymlinkComponents(root, path, "staged artifact");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("staged bundle contains a non-regular artifact: " + path);
                }
                String relative = normalize(root.relativize(path).toString());
                artifacts.put(relative, sha256(path));
            }
        }
        return artifacts;
    }

    private void backupPublishedState(
            Set<String> previousArtifacts,
            boolean previousManifestExists,
            Path backupRoot
    ) throws IOException {
        createDirectoriesSecure(runRoot, backupRoot);
        for (String relative : previousArtifacts.stream().sorted().toList()) {
            Path source = secureStableArtifact(relative, "published artifact backup source");
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("published artifact listed in manifest is missing: " + source);
            }
            Path target = inside(backupRoot, backupRoot.resolve("artifacts").resolve(relative));
            createDirectoriesSecure(backupRoot, target.getParent());
            copyRegularFileNoFollow(source, target);
        }
        if (previousManifestExists) {
            Path source = requireNoSymlinkComponents(
                    stableRoot, stableRoot.resolve(PUBLICATION_MANIFEST), "publication manifest backup source");
            Path target = requireNoSymlinkComponents(
                    backupRoot, backupRoot.resolve(PUBLICATION_MANIFEST), "publication manifest backup target");
            copyRegularFileNoFollow(source, target);
        }
    }

    private void rollbackPublishedState(
            Set<String> newArtifacts,
            Set<String> previousArtifacts,
            boolean previousManifestExists,
            Path backupRoot
    ) throws IOException {
        IOException failure = null;
        for (String relative : newArtifacts.stream().sorted().toList()) {
            failure = attemptRollback(
                    () -> deleteRollbackPath(relative), failure
            );
            failure = attemptRollback(
                    () -> deleteRollbackPath(relative + ".tmp-" + executionId()), failure
            );
        }
        for (String relative : previousArtifacts.stream().sorted().toList()) {
            Path source = requireNoSymlinkComponents(
                    backupRoot,
                    inside(backupRoot, backupRoot.resolve("artifacts").resolve(relative)),
                    "rollback backup source"
            );
            failure = attemptRollback(() -> {
                Path target = secureStableArtifact(relative, "rollback restore target");
                publishFile(backupRoot, source, stableRoot, target);
            }, failure);
        }
        failure = attemptRollback(
                () -> deleteRollbackPath(PUBLICATION_MANIFEST + ".tmp-" + executionId()),
                failure
        );
        if (previousManifestExists) {
            failure = attemptRollback(
                    () -> {
                        Path source = requireNoSymlinkComponents(
                                backupRoot, backupRoot.resolve(PUBLICATION_MANIFEST), "rollback manifest source");
                        Path manifest = requireNoSymlinkComponents(
                                stableRoot, stableRoot.resolve(PUBLICATION_MANIFEST), "rollback manifest target");
                        publishFile(backupRoot, source, stableRoot, manifest);
                    }, failure
            );
        } else {
            failure = attemptRollback(() -> deleteRollbackPath(PUBLICATION_MANIFEST), failure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private IOException attemptRollback(IoOperation operation, IOException previous) {
        try {
            operation.run();
            return previous;
        } catch (IOException | RuntimeException exception) {
            IOException current = exception instanceof IOException io
                    ? io
                    : new IOException("workflow publication rollback failed", exception);
            if (previous == null) {
                return current;
            }
            previous.addSuppressed(current);
            return previous;
        }
    }

    private Set<String> previousArtifactPaths() throws IOException {
        Path manifest = requireNoSymlinkComponents(
                stableRoot, stableRoot.resolve(PUBLICATION_MANIFEST), "publication manifest");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            return Set.of();
        }
        Map<String, Object> root;
        try (InputStream input = Files.newInputStream(manifest, READ, LinkOption.NOFOLLOW_LINKS)) {
            root = objectMapper.readValue(input, new TypeReference<>() {
            });
        }
        Object value = root.get("artifacts");
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("path") != null) {
                String relative = requireCanonicalRelativeArtifactPath(map.get("path").toString());
                if (!paths.add(relative)) {
                    throw new IllegalStateException("publication manifest contains duplicate artifact path: " + relative);
                }
            }
        }
        return paths;
    }

    private void writeRunManifest(String state, String message) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", "workflow-run/v1");
        manifest.put("executionId", executionId());
        manifest.put("consoleRunId", request.consoleRunId());
        manifest.put("chainId", chainId);
        manifest.put("mode", request.mode());
        manifest.put("rerunType", request.rerunType());
        manifest.put("rerunIds", request.rerunIds());
        manifest.put("publicationGeneration", publicationGeneration);
        manifest.put("state", state);
        manifest.put("message", message == null ? "" : message);
        manifest.put("stableRoot", stableRoot.toString());
        manifest.put("updatedAt", OffsetDateTime.now().toString());
        atomicWriteJson(runRoot.resolve("run-manifest.json"), manifest);
    }

    private void atomicWriteJson(Path target, Object value) throws IOException {
        Path root = target.toAbsolutePath().normalize().startsWith(runRoot) ? runRoot : stableRoot;
        createDirectoriesSecure(root, target.getParent());
        requireNoSymlinkComponents(root, target, "JSON target");
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + executionId());
        requireNoSymlinkComponents(root, temporary, "JSON temporary target");
        try (var output = Files.newOutputStream(
                temporary, CREATE, WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS
        )) {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, value);
        }
        atomicMoveSecure(root, temporary, target);
    }

    private void publishFile(Path sourceRoot, Path source, Path targetRoot, Path target) throws IOException {
        requireNoSymlinkComponents(sourceRoot, source, "publication copy source");
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("publication copy source must be a non-symlink regular file: " + source);
        }
        createDirectoriesSecure(targetRoot, target.getParent());
        requireNoSymlinkComponents(targetRoot, target, "publication copy target");
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + executionId());
        requireNoSymlinkComponents(targetRoot, temporary, "publication temporary target");
        copyRegularFileNoFollow(source, temporary);
        atomicMoveSecure(targetRoot, temporary, target);
    }

    private static void atomicMoveSecure(Path root, Path source, Path target) throws IOException {
        requireNoSymlinkComponents(root, source, "atomic move source");
        requireNoSymlinkComponents(root, target, "atomic move target");
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            requireNoSymlinkComponents(root, source, "atomic move source");
            requireNoSymlinkComponents(root, target, "atomic move target");
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyRecursivelySecure(
            Path sourceRoot,
            Path source,
            Path targetRoot,
            Path target
    ) throws IOException {
        requireNoSymlinkComponents(sourceRoot, source, "recursive copy source");
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) {
                        throw new IllegalStateException("recursive copy source contains a symlink: " + dir);
                    }
                    requireNoSymlinkComponents(sourceRoot, dir, "recursive copy source directory");
                    createDirectoriesSecure(targetRoot, target.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                        throw new IllegalStateException("recursive copy source contains a non-regular file: " + file);
                    }
                    requireNoSymlinkComponents(sourceRoot, file, "recursive copy source file");
                    Path destination = target.resolve(source.relativize(file));
                    createDirectoriesSecure(targetRoot, destination.getParent());
                    requireNoSymlinkComponents(targetRoot, destination, "recursive copy target file");
                    copyRegularFileNoFollow(file, destination);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("recursive copy source must be a non-symlink regular file: " + source);
            }
            createDirectoriesSecure(targetRoot, target.getParent());
            requireNoSymlinkComponents(targetRoot, target, "recursive copy target");
            copyRegularFileNoFollow(source, target);
        }
    }

    private static void deleteRecursivelySecure(Path containmentRoot, Path target) throws IOException {
        requireNoSymlinkComponents(containmentRoot, target, "recursive delete target");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) {
                    throw new IllegalStateException("recursive delete refuses a symlink: " + file);
                }
                requireNoSymlinkComponents(containmentRoot, file, "recursive delete file");
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                requireNoSymlinkComponents(containmentRoot, directory, "recursive delete directory");
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static long nextGeneration(Path stableRoot) throws IOException {
        Path lockPath = requireNoSymlinkComponents(
                stableRoot, stableRoot.resolve(PUBLISH_LOCK), "publication lock");
        try (FileChannel channel = FileChannel.open(lockPath, CREATE, READ, WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock ignored = channel.lock()) {
            long next = readGeneration(stableRoot) + 1;
            Path target = requireNoSymlinkComponents(
                    stableRoot, stableRoot.resolve(GENERATION_FILE), "publication generation target");
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            requireNoSymlinkComponents(stableRoot, temporary, "publication generation temporary target");
            Files.writeString(
                    temporary, Long.toString(next), StandardCharsets.UTF_8,
                    CREATE, WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS
            );
            atomicMoveSecure(stableRoot, temporary, target);
            return next;
        }
    }

    private static long readGeneration(Path stableRoot) throws IOException {
        Path path = requireNoSymlinkComponents(
                stableRoot, stableRoot.resolve(GENERATION_FILE), "publication generation file");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        String value;
        try (InputStream input = Files.newInputStream(path, READ, LinkOption.NOFOLLOW_LINKS)) {
            value = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        return value.isBlank() ? 0L : Long.parseLong(value);
    }

    private void validatePublicationPaths(
            Set<String> newArtifacts,
            Set<String> previousArtifacts,
            Path stagedRoot,
            Path backupRoot
    ) throws IOException {
        requireNoSymlinkComponents(runRoot, stagedRoot, "publication staging root");
        requireNoSymlinkComponents(runRoot, backupRoot, "publication backup root");
        for (String relative : previousArtifacts) {
            secureStableArtifact(relative, "previous published artifact");
            requireNoSymlinkComponents(
                    runRoot,
                    inside(backupRoot, backupRoot.resolve("artifacts").resolve(relative)),
                    "publication backup artifact"
            );
        }
        for (String relative : newArtifacts) {
            requireNoSymlinkComponents(
                    stagedRoot, inside(stagedRoot, stagedRoot.resolve(relative)), "staged publication artifact");
            Path target = secureStableArtifact(relative, "new publication artifact");
            requireNoSymlinkComponents(
                    stableRoot,
                    target.resolveSibling(target.getFileName() + ".tmp-" + executionId()),
                    "publication temporary artifact"
            );
        }
        Path manifest = requireNoSymlinkComponents(
                stableRoot, stableRoot.resolve(PUBLICATION_MANIFEST), "publication manifest");
        requireNoSymlinkComponents(
                stableRoot,
                manifest.resolveSibling(manifest.getFileName() + ".tmp-" + executionId()),
                "publication manifest temporary target"
        );
    }

    private Path secureStableArtifact(String relative, String label) throws IOException {
        String safeRelative = requireCanonicalRelativeArtifactPath(relative);
        return requireNoSymlinkComponents(
                stableRoot, inside(stableRoot, stableRoot.resolve(safeRelative)), label);
    }

    private void deleteRollbackPath(String relative) throws IOException {
        String safeRelative = requireCanonicalRelativeArtifactPath(relative);
        Path current = stableRoot;
        Path relativePath = Path.of(safeRelative);
        for (int index = 0; index < relativePath.getNameCount(); index++) {
            requireNoSymlinkComponents(stableRoot, current, "rollback parent");
            Path next = current.resolve(relativePath.getName(index)).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(next)) {
                Files.delete(next);
                return;
            }
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            boolean last = index == relativePath.getNameCount() - 1;
            if (!last && !Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("rollback path has a non-directory component: " + next);
            }
            current = next;
        }
        if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursivelySecure(stableRoot, current);
        } else {
            Files.deleteIfExists(current);
        }
    }

    private static String requireCanonicalRelativeArtifactPath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0) {
            throw new IllegalStateException("publication artifact path must be canonical and relative: " + value);
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("publication artifact path is invalid: " + value, exception);
        }
        String normalized = normalize(path.normalize().toString());
        if (path.isAbsolute() || !normalized.equals(value) || value.equals(".")
                || value.equals("..") || value.startsWith("../") || value.contains("/../")) {
            throw new IllegalStateException("publication artifact path must be canonical and relative: " + value);
        }
        return value;
    }

    private static void requireOrCreateSecureRoot(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            throw new IllegalStateException("workflow stable root must not be a symlink: " + root);
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(root);
        }
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("workflow stable root must be a non-symlink directory: " + root);
        }
    }

    private static Path requireNoSymlinkComponents(Path root, Path path, String label) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = inside(normalizedRoot, path);
        if (Files.isSymbolicLink(normalizedRoot)
                || !Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(label + " root must be a non-symlink directory: " + normalizedRoot);
        }
        Path realRoot = normalizedRoot.toRealPath();
        Path current = normalizedRoot;
        Path relative = normalizedRoot.relativize(normalized);
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException(label + " contains a symlink path component: " + current);
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
            Path real = current.toRealPath();
            if (!real.startsWith(realRoot)) {
                throw new IllegalStateException(label + " escapes its real root: " + current);
            }
        }
        Path existingParent = Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                ? normalized
                : normalized.getParent();
        while (existingParent != null && !Files.exists(existingParent, LinkOption.NOFOLLOW_LINKS)) {
            existingParent = existingParent.getParent();
        }
        if (existingParent == null || Files.isSymbolicLink(existingParent)
                || !existingParent.toRealPath().startsWith(realRoot)) {
            throw new IllegalStateException(label + " parent escapes its real root: " + normalized);
        }
        return normalized;
    }

    private static void createDirectoriesSecure(Path root, Path directory) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = requireNoSymlinkComponents(normalizedRoot, directory, "directory creation target");
        Path current = normalizedRoot;
        for (Path component : normalizedRoot.relativize(normalized)) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("directory path contains a non-directory or symlink: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
            requireNoSymlinkComponents(normalizedRoot, current, "created directory");
        }
    }

    private static void copyRegularFileNoFollow(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)) {
            throw new IllegalStateException("refusing symlink or non-regular publication copy: " + source);
        }
        try (InputStream input = Files.newInputStream(source, READ, LinkOption.NOFOLLOW_LINKS);
             var output = Files.newOutputStream(
                     target, CREATE, WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                     LinkOption.NOFOLLOW_LINKS
             )) {
            input.transferTo(output);
        }
    }

    private static Path inside(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("workflow artifact path escapes root: " + path);
        }
        return normalized;
    }

    static String encodeTaskKey(String taskKey) {
        String raw = taskKey == null ? "" : taskKey.trim();
        String slug = raw.toLowerCase()
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            slug = "task";
        }
        if (slug.length() > 80) {
            slug = slug.substring(0, 80);
        }
        return slug + "-" + shortHash(raw);
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path, READ, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IllegalStateException("unable to hash workflow artifact: " + path, exception);
        }
    }

    private static String normalize(String value) {
        return value.replace('\\', '/').replaceAll("^/+", "");
    }

    public record PublicationResult(boolean published, boolean superseded, Path mainArtifact) {
    }

    interface PublicationFailureInjector {
        default void afterDeletion(Path path) throws IOException {
        }

        default void afterReplacement(Path path) throws IOException {
        }
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws IOException;
    }
}
