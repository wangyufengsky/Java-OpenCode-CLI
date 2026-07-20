package com.sonnet.wyf.gitreport.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.console.WorkflowRunContext;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
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
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final Map<String, String> pathMappings = new LinkedHashMap<>();

    private WorkflowArtifactWorkspace(
            ObjectMapper objectMapper,
            String chainId,
            WorkflowRunRequest request,
            Path stableRoot,
            long publicationGeneration
    ) {
        this.objectMapper = objectMapper;
        this.chainId = chainId;
        this.request = request;
        this.stableRoot = stableRoot.toAbsolutePath().normalize();
        this.runRoot = inside(this.stableRoot, this.stableRoot.resolve("runs").resolve(request.executionId()));
        this.preparationRoot = inside(runRoot, runRoot.resolve("preparation"));
        this.bundleRoot = inside(runRoot, runRoot.resolve("bundle"));
        this.publicationGeneration = publicationGeneration;
        this.pathMappings.put(this.stableRoot.toString(), this.bundleRoot.toString());
    }

    public static WorkflowArtifactWorkspace start(
            ObjectMapper objectMapper,
            String chainId,
            WorkflowRunRequest request,
            Path stableRoot,
            boolean seedFromStable
    ) throws IOException {
        if (request.executionId() == null || request.executionId().isBlank()) {
            throw new IllegalArgumentException("workflow executionId is required");
        }
        Path normalizedRoot = stableRoot.toAbsolutePath().normalize();
        Files.createDirectories(normalizedRoot);
        long generation = nextGeneration(normalizedRoot);
        WorkflowArtifactWorkspace workspace = new WorkflowArtifactWorkspace(
                objectMapper,
                chainId,
                request,
                normalizedRoot,
                generation
        );
        if (Files.exists(workspace.runRoot)) {
            throw new IllegalStateException("workflow execution directory already exists: " + workspace.runRoot);
        }
        Files.createDirectories(workspace.preparationRoot);
        Files.createDirectories(workspace.bundleRoot);
        if (seedFromStable) {
            workspace.copyStableToBundle();
            workspace.rebaseBundlePaths(workspace.stableRoot.toString(), workspace.bundleRoot.toString());
        }
        workspace.writeRunManifest("CREATED", "");
        return workspace;
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
        if (!Files.isRegularFile(mainPath)) {
            throw new IllegalStateException("workflow main artifact is missing from bundle: " + mainPath);
        }
        Path lockPath = stableRoot.resolve(PUBLISH_LOCK);
        try (FileChannel channel = FileChannel.open(lockPath, CREATE, READ, WRITE);
             FileLock ignored = channel.lock()) {
            long latestGeneration = readGeneration(stableRoot);
            if (latestGeneration != publicationGeneration) {
                writeRunManifest("SUPERSEDED", "latest publication generation is " + latestGeneration);
                return new PublicationResult(false, true, stableRoot.resolve(mainArtifact));
            }

            Map<String, String> newArtifacts = bundleArtifacts();
            Set<String> previousArtifacts = previousArtifactPaths();
            for (String previous : previousArtifacts) {
                if (!newArtifacts.containsKey(previous)) {
                    Files.deleteIfExists(inside(stableRoot, stableRoot.resolve(previous)));
                }
            }
            for (String relative : newArtifacts.keySet()) {
                publishFile(inside(bundleRoot, bundleRoot.resolve(relative)), inside(stableRoot, stableRoot.resolve(relative)));
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
            Path publishedMain = inside(stableRoot, stableRoot.resolve(mainArtifact));
            if (WorkflowRunContext.currentRunId() != null) {
                WorkflowRunContext.setCurrentOutputPath(publishedMain.toString());
            }
            return new PublicationResult(true, false, publishedMain);
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
                copyRecursively(entry, bundleRoot.resolve(entry.getFileName()));
            }
        }
    }

    private void rebaseBundlePaths(String from, String to) throws IOException {
        if (from.equals(to)) {
            return;
        }
        try (var paths = Files.walk(bundleRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
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

    private Map<String, String> bundleArtifacts() throws IOException {
        Map<String, String> artifacts = new LinkedHashMap<>();
        try (var paths = Files.walk(bundleRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = normalize(bundleRoot.relativize(path).toString());
                artifacts.put(relative, sha256(path));
            }
        }
        return artifacts;
    }

    private Set<String> previousArtifactPaths() throws IOException {
        Path manifest = stableRoot.resolve(PUBLICATION_MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            return Set.of();
        }
        Map<String, Object> root = objectMapper.readValue(manifest.toFile(), new TypeReference<>() {
        });
        Object value = root.get("artifacts");
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("path") != null) {
                paths.add(normalize(map.get("path").toString()));
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
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + executionId());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
        atomicMove(temporary, target);
    }

    private void publishFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + executionId());
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        atomicMove(temporary, target);
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path destination = target.resolve(source.relativize(file));
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static long nextGeneration(Path stableRoot) throws IOException {
        Path lockPath = stableRoot.resolve(PUBLISH_LOCK);
        try (FileChannel channel = FileChannel.open(lockPath, CREATE, READ, WRITE);
             FileLock ignored = channel.lock()) {
            long next = readGeneration(stableRoot) + 1;
            Path target = stableRoot.resolve(GENERATION_FILE);
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, Long.toString(next), StandardCharsets.UTF_8);
            atomicMove(temporary, target);
            return next;
        }
    }

    private static long readGeneration(Path stableRoot) throws IOException {
        Path path = stableRoot.resolve(GENERATION_FILE);
        if (!Files.isRegularFile(path)) {
            return 0L;
        }
        String value = Files.readString(path, StandardCharsets.UTF_8).trim();
        return value.isBlank() ? 0L : Long.parseLong(value);
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

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (Exception exception) {
            throw new IllegalStateException("unable to hash workflow artifact: " + path, exception);
        }
    }

    private static String normalize(String value) {
        return value.replace('\\', '/').replaceAll("^/+", "");
    }

    public record PublicationResult(boolean published, boolean superseded, Path mainArtifact) {
    }
}
