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
import java.util.Comparator;
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
    private static final String PUBLICATION_STORE = ".published";
    private static final String GENERATIONS_DIRECTORY = "generations";
    private static final String CURRENT_POINTER = "current";
    private static final Set<String> RESERVED_ROOT_NAMES = Set.of(
            "runs", PUBLICATION_MANIFEST, GENERATION_FILE, PUBLISH_LOCK, PUBLICATION_STORE
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
    private final AtomicInteger publicationAttempts = new AtomicInteger();
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
            migrateLegacyPublicationIfNecessary();
            Set<String> previousArtifacts = previousArtifactPaths();
            Map<String, String> newArtifacts = artifacts(bundleRoot);
            validateGenerationArtifactPaths(newArtifacts.keySet());

            Path generationRoot = newGenerationRoot();
            boolean pointerSwitched = false;
            try {
                prepareGeneration(generationRoot, newArtifacts, mainArtifact);
                for (String previous : previousArtifacts.stream().sorted().toList()) {
                    if (!newArtifacts.containsKey(previous)) {
                        publicationFailureInjector.afterDeletion(stableRoot.resolve(previous));
                    }
                }
                publicationFailureInjector.afterGenerationPrepared(generationRoot);

                Set<String> forwardedRoots = new LinkedHashSet<>();
                forwardedRoots.addAll(topLevelArtifactNames(previousArtifacts));
                forwardedRoots.addAll(topLevelArtifactNames(newArtifacts.keySet()));
                forwardedRoots.add(PUBLICATION_MANIFEST);
                for (String rootName : forwardedRoots.stream().sorted().toList()) {
                    ensureManagedForwarder(rootName);
                }

                switchCurrentGeneration(generationRoot);
                pointerSwitched = true;
                cleanupStaleForwarders(topLevelArtifactNames(newArtifacts.keySet()));
                publicationFailureInjector.afterPointerSwitch(generationRoot);
                writeRunManifest("PUBLISHED", "");
                cleanupOrphanGenerations(stableRoot);

                Path publishedMain = stableArtifactPath(mainArtifact);
                if (WorkflowRunContext.currentRunId() != null) {
                    WorkflowRunContext.setCurrentOutputPath(publishedMain.toString());
                }
                return new PublicationResult(true, false, publishedMain);
            } catch (IOException | RuntimeException publicationFailure) {
                if (!pointerSwitched) {
                    try {
                        deleteGenerationWithoutFollowingLinks(generationRoot);
                    } catch (IOException cleanupFailure) {
                        publicationFailure.addSuppressed(cleanupFailure);
                    }
                }
                throw publicationFailure;
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
        Path currentGeneration = currentGenerationRoot(stableRoot);
        if (currentGeneration != null) {
            copyGenerationArtifactsToBundle(currentGeneration);
            return;
        }
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

    private void copyGenerationArtifactsToBundle(Path generationRoot) throws IOException {
        Set<String> publishedArtifacts = artifactPathsFromManifest(
                generationRoot, generationRoot.resolve(PUBLICATION_MANIFEST));
        for (String relative : publishedArtifacts.stream().sorted().toList()) {
            Path source = requireNoSymlinkComponents(
                    generationRoot,
                    inside(generationRoot, generationRoot.resolve(relative)),
                    "published generation artifact"
            );
            Path target = inside(bundleRoot, bundleRoot.resolve(relative));
            copyRecursivelySecure(generationRoot, source, bundleRoot, target);
        }
    }

    private void prepareGeneration(
            Path generationRoot,
            Map<String, String> newArtifacts,
            String mainArtifact
    ) throws IOException {
        Path generationsRoot = generationsRoot(stableRoot);
        createDirectoriesSecure(stableRoot, generationsRoot);
        if (Files.exists(generationRoot, LinkOption.NOFOLLOW_LINKS)) {
            deleteGenerationWithoutFollowingLinks(generationRoot);
        }
        copyRecursivelySecure(bundleRoot, bundleRoot, generationsRoot, generationRoot);
        for (String relative : newArtifacts.keySet()) {
            Path prepared = requireNoSymlinkComponents(
                    generationRoot,
                    inside(generationRoot, generationRoot.resolve(relative)),
                    "prepared generation artifact"
            );
            publicationFailureInjector.afterReplacement(prepared);
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
        atomicWriteJson(generationRoot.resolve(PUBLICATION_MANIFEST), manifest);
        forceGeneration(generationRoot);
    }

    private Path newGenerationRoot() throws IOException {
        Path generationsRoot = generationsRoot(stableRoot);
        createDirectoriesSecure(stableRoot, generationsRoot);
        String generationName = "%020d-%s-%03d".formatted(
                publicationGeneration,
                encodeTaskKey(executionId()),
                publicationAttempts.incrementAndGet()
        );
        return requireNoSymlinkComponents(
                generationsRoot,
                inside(generationsRoot, generationsRoot.resolve(generationName)),
                "publication generation"
        );
    }

    private void validateGenerationArtifactPaths(Set<String> artifacts) throws IOException {
        for (String relative : artifacts) {
            String safe = requireCanonicalRelativeArtifactPath(relative);
            String rootName = Path.of(safe).getName(0).toString();
            if (RESERVED_ROOT_NAMES.contains(rootName)) {
                throw new IllegalStateException("bundle uses a reserved publication path: " + relative);
            }
            requireNoSymlinkComponents(
                    bundleRoot,
                    inside(bundleRoot, bundleRoot.resolve(safe)),
                    "bundle artifact"
            );
        }
    }

    private void migrateLegacyPublicationIfNecessary() throws IOException {
        Path current = currentGenerationRoot(stableRoot);
        if (current != null) {
            return;
        }
        Set<String> legacyArtifacts = previousArtifactPaths();
        boolean legacyManifestExists = Files.isRegularFile(
                stableRoot.resolve(PUBLICATION_MANIFEST), LinkOption.NOFOLLOW_LINKS);
        Map<String, String> legacyArtifactHashes = Map.of();
        if (legacyArtifacts.isEmpty()) {
            legacyArtifactHashes = legacyStableArtifacts();
            legacyArtifacts = legacyArtifactHashes.keySet();
            if (legacyArtifacts.isEmpty()) {
                return;
            }
        }

        Path generationsRoot = generationsRoot(stableRoot);
        createDirectoriesSecure(stableRoot, generationsRoot);
        Path legacyRoot = inside(
                generationsRoot,
                generationsRoot.resolve("legacy-before-%020d".formatted(publicationGeneration))
        );
        deleteGenerationWithoutFollowingLinks(legacyRoot);
        Files.createDirectory(legacyRoot);
        for (String relative : legacyArtifacts.stream().sorted().toList()) {
            Path source = secureStableArtifact(relative, "legacy published artifact");
            Path target = inside(legacyRoot, legacyRoot.resolve(relative));
            copyRecursivelySecure(stableRoot, source, legacyRoot, target);
        }
        if (legacyManifestExists) {
            Path legacyManifest = requireNoSymlinkComponents(
                    stableRoot, stableRoot.resolve(PUBLICATION_MANIFEST), "legacy publication manifest");
            copyRegularFileNoFollow(legacyManifest, legacyRoot.resolve(PUBLICATION_MANIFEST));
        } else {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", "workflow-publication/v1");
            manifest.put("chainId", chainId);
            manifest.put("executionId", "legacy-bootstrap");
            manifest.put("publicationGeneration", Math.max(0L, publicationGeneration - 1));
            manifest.put("mainArtifact", legacyArtifacts.stream().sorted().findFirst().orElseThrow());
            manifest.put("publishedAt", OffsetDateTime.now().toString());
            Map<String, String> hashes = legacyArtifactHashes;
            manifest.put("artifacts", legacyArtifacts.stream().sorted()
                    .map(path -> Map.of("path", path, "sha256", hashes.get(path)))
                    .toList());
            atomicWriteJson(legacyRoot.resolve(PUBLICATION_MANIFEST), manifest);
        }
        forceGeneration(legacyRoot);

        switchCurrentGeneration(legacyRoot);
        Set<String> roots = topLevelArtifactNames(legacyArtifacts);
        roots.add(PUBLICATION_MANIFEST);
        for (String rootName : roots.stream().sorted().toList()) {
            replaceLegacyRootWithForwarder(rootName);
        }
    }

    private Map<String, String> legacyStableArtifacts() throws IOException {
        Map<String, String> legacy = new LinkedHashMap<>();
        try (var paths = Files.walk(stableRoot)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(stableRoot)) {
                    continue;
                }
                Path relativePath = stableRoot.relativize(path);
                if (RESERVED_ROOT_NAMES.contains(relativePath.getName(0).toString())) {
                    continue;
                }
                requireNoSymlinkComponents(stableRoot, path, "legacy stable artifact");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("legacy stable artifact is not a regular file: " + path);
                }
                String relative = normalize(relativePath.toString());
                legacy.put(relative, sha256(path));
            }
        }
        return legacy;
    }

    private void replaceLegacyRootWithForwarder(String rootName) throws IOException {
        Path target = stableRoot.resolve(rootName);
        if (Files.isSymbolicLink(target)) {
            throw new IllegalStateException("legacy publication artifact contains a symlink: " + target);
        }
        Path temporary = forwarderTemporaryPath(rootName);
        Files.deleteIfExists(temporary);
        Files.createSymbolicLink(temporary, managedForwarderTarget(rootName));
        try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                Path migrated = stableRoot.resolve(PUBLICATION_STORE)
                        .resolve("migration-backup")
                        .resolve(rootName);
                createDirectoriesSecure(stableRoot, migrated.getParent());
                deleteGenerationWithoutFollowingLinks(migrated);
                atomicMoveRequired(stableRoot, target, migrated);
                atomicMoveRequired(stableRoot, temporary, target);
                deleteGenerationWithoutFollowingLinks(migrated);
            } else {
                atomicMoveRequired(stableRoot, temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void ensureManagedForwarder(String rootName) throws IOException {
        requireForwardableRootName(rootName);
        Path forwarder = stableRoot.resolve(rootName);
        Path expectedTarget = managedForwarderTarget(rootName);
        if (Files.isSymbolicLink(forwarder)) {
            Path actualTarget = Files.readSymbolicLink(forwarder);
            if (!actualTarget.equals(expectedTarget)) {
                throw new IllegalStateException(
                        "stable publication artifact contains an unmanaged symlink: " + forwarder);
            }
            return;
        }
        if (Files.exists(forwarder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "stable publication artifact is not a managed generation forwarder: " + forwarder);
        }

        Path temporary = forwarderTemporaryPath(rootName);
        Files.deleteIfExists(temporary);
        Files.createSymbolicLink(temporary, expectedTarget);
        try {
            atomicMoveRequired(stableRoot, temporary, forwarder);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path forwarderTemporaryPath(String rootName) throws IOException {
        Path temporary = stableRoot.resolve("." + rootName + ".forward-" + executionId());
        return requireNoSymlinkComponents(stableRoot, temporary, "publication forwarder temporary path");
    }

    private Path managedForwarderTarget(String rootName) {
        return Path.of(PUBLICATION_STORE, CURRENT_POINTER, rootName);
    }

    private void switchCurrentGeneration(Path generationRoot) throws IOException {
        Path generationsRoot = generationsRoot(stableRoot);
        Path safeGeneration = requireNoSymlinkComponents(
                generationsRoot, generationRoot, "publication pointer target");
        if (!Files.isDirectory(safeGeneration, LinkOption.NOFOLLOW_LINKS)
                || !safeGeneration.getParent().equals(generationsRoot)) {
            throw new IllegalStateException("publication pointer target must be one immutable generation: "
                    + safeGeneration);
        }
        Path store = publicationStore(stableRoot);
        Path pointer = store.resolve(CURRENT_POINTER);
        Path temporary = store.resolve(CURRENT_POINTER + ".tmp-" + executionId());
        requireNoSymlinkComponents(stableRoot, temporary, "publication pointer temporary path");
        Files.deleteIfExists(temporary);
        Files.createSymbolicLink(temporary, Path.of(GENERATIONS_DIRECTORY, safeGeneration.getFileName().toString()));
        try {
            atomicMoveRequired(stableRoot, temporary, pointer);
            forceDirectory(store);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path stableArtifactPath(String relative) throws IOException {
        String safe = requireCanonicalRelativeArtifactPath(normalize(relative));
        Path root = Path.of(safe).getName(0);
        Path forwarder = stableRoot.resolve(root);
        if (!Files.isSymbolicLink(forwarder)
                || !Files.readSymbolicLink(forwarder).equals(managedForwarderTarget(root.toString()))) {
            throw new IllegalStateException("published artifact is not routed through the current generation: "
                    + stableRoot.resolve(safe));
        }
        Path result = inside(stableRoot, stableRoot.resolve(safe));
        if (!Files.isRegularFile(result)) {
            throw new IllegalStateException("published main artifact is missing after pointer switch: " + result);
        }
        return result;
    }

    private void cleanupStaleForwarders(Set<String> currentRoots) throws IOException {
        Set<String> retained = new LinkedHashSet<>(currentRoots);
        retained.add(PUBLICATION_MANIFEST);
        try (var entries = Files.list(stableRoot)) {
            for (Path entry : entries.toList()) {
                if (!Files.isSymbolicLink(entry)) {
                    continue;
                }
                String rootName = entry.getFileName().toString();
                if (Files.readSymbolicLink(entry).equals(managedForwarderTarget(rootName))
                        && !retained.contains(rootName)) {
                    Files.delete(entry);
                }
            }
        }
    }

    private static Set<String> topLevelArtifactNames(Set<String> artifacts) {
        Set<String> roots = new LinkedHashSet<>();
        for (String relative : artifacts) {
            roots.add(Path.of(requireCanonicalRelativeArtifactPath(relative)).getName(0).toString());
        }
        return roots;
    }

    private static void requireForwardableRootName(String rootName) {
        if (rootName == null || rootName.isBlank() || rootName.indexOf('/') >= 0 || rootName.indexOf('\\') >= 0
                || (RESERVED_ROOT_NAMES.contains(rootName) && !PUBLICATION_MANIFEST.equals(rootName))) {
            throw new IllegalStateException("invalid publication forwarder root: " + rootName);
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

    private static Path publicationStore(Path stableRoot) throws IOException {
        Path store = inside(stableRoot, stableRoot.resolve(PUBLICATION_STORE));
        createDirectoriesSecure(stableRoot, store);
        return store;
    }

    private static Path generationsRoot(Path stableRoot) throws IOException {
        Path store = publicationStore(stableRoot);
        Path generations = inside(stableRoot, store.resolve(GENERATIONS_DIRECTORY));
        createDirectoriesSecure(stableRoot, generations);
        return generations;
    }

    private static Path currentGenerationRoot(Path stableRoot) throws IOException {
        Path storePath = stableRoot.resolve(PUBLICATION_STORE);
        if (!Files.exists(storePath, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path store = requireNoSymlinkComponents(stableRoot, storePath, "publication store");
        Path pointer = store.resolve(CURRENT_POINTER);
        if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isSymbolicLink(pointer)) {
            throw new IllegalStateException("publication current pointer must be a symlink: " + pointer);
        }
        Path target = Files.readSymbolicLink(pointer);
        if (target.isAbsolute() || target.getNameCount() != 2
                || !GENERATIONS_DIRECTORY.equals(target.getName(0).toString())) {
            throw new IllegalStateException("publication current pointer escapes generations: " + pointer);
        }
        Path generations = generationsRoot(stableRoot);
        Path resolved = inside(generations, store.resolve(target));
        requireNoSymlinkComponents(generations, resolved, "publication current generation");
        if (!resolved.getParent().equals(generations)
                || !Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("publication current generation is missing: " + resolved);
        }
        return resolved;
    }

    private static void cleanupOrphanGenerations(Path stableRoot) throws IOException {
        Path store = stableRoot.resolve(PUBLICATION_STORE);
        if (!Files.exists(store, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path generations = generationsRoot(stableRoot);
        Path current = currentGenerationRoot(stableRoot);
        try (var entries = Files.list(generations)) {
            for (Path entry : entries.toList()) {
                if (current == null || !entry.equals(current)) {
                    deleteGenerationWithoutFollowingLinks(entry);
                }
            }
        }
    }

    private static void deleteGenerationWithoutFollowingLinks(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void atomicMoveRequired(Path root, Path source, Path target) throws IOException {
        inside(root, source);
        inside(root, target);
        requireNoSymlinkComponents(root, source.getParent(), "atomic publication source parent");
        requireNoSymlinkComponents(root, target.getParent(), "atomic publication target parent");
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("filesystem does not support the atomic publication switch", exception);
        }
    }

    private static void forceGeneration(Path generationRoot) throws IOException {
        try (var paths = Files.walk(generationRoot)) {
            List<Path> all = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : all) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    try (FileChannel channel = FileChannel.open(path, WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        channel.force(true);
                    }
                } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    forceDirectory(path);
                } else {
                    throw new IllegalStateException("prepared generation contains a non-regular artifact: " + path);
                }
            }
        }
        forceDirectory(generationRoot.getParent());
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    private Set<String> previousArtifactPaths() throws IOException {
        Path currentGeneration = currentGenerationRoot(stableRoot);
        Path manifestRoot = currentGeneration == null ? stableRoot : currentGeneration;
        Path manifest = requireNoSymlinkComponents(
                manifestRoot, manifestRoot.resolve(PUBLICATION_MANIFEST), "publication manifest");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            return Set.of();
        }
        return artifactPathsFromManifest(manifestRoot, manifest);
    }

    private Set<String> artifactPathsFromManifest(Path manifestRoot, Path manifest) throws IOException {
        requireNoSymlinkComponents(manifestRoot, manifest, "publication manifest");
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

    private static long nextGeneration(Path stableRoot) throws IOException {
        Path lockPath = requireNoSymlinkComponents(
                stableRoot, stableRoot.resolve(PUBLISH_LOCK), "publication lock");
        try (FileChannel channel = FileChannel.open(lockPath, CREATE, READ, WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock ignored = channel.lock()) {
            cleanupOrphanGenerations(stableRoot);
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

    private Path secureStableArtifact(String relative, String label) throws IOException {
        String safeRelative = requireCanonicalRelativeArtifactPath(relative);
        return requireNoSymlinkComponents(
                stableRoot, inside(stableRoot, stableRoot.resolve(safeRelative)), label);
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
        default void afterGenerationPrepared(Path generationRoot) throws IOException {
        }

        default void afterPointerSwitch(Path generationRoot) throws IOException {
        }

        default void afterDeletion(Path path) throws IOException {
        }

        default void afterReplacement(Path path) throws IOException {
        }
    }

}
