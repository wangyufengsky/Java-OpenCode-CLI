package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class MyBatisSqlSourceScope {
    private final Path repository;
    private final List<String> configuredPaths;
    private final List<Path> discoveryRoots;

    private MyBatisSqlSourceScope(
            Path repository,
            List<String> configuredPaths,
            List<Path> discoveryRoots) {
        this.repository = repository;
        this.configuredPaths = List.copyOf(configuredPaths);
        this.discoveryRoots = List.copyOf(discoveryRoots);
    }

    static MyBatisSqlSourceScope resolve(Path repository, List<String> configuredPaths) {
        Objects.requireNonNull(repository, "repository");
        Path root = repository.toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            throw new IllegalArgumentException("project.repo must be a readable directory: " + root);
        }
        if (configuredPaths == null || configuredPaths.isEmpty()) {
            throw new IllegalArgumentException(
                    "source.paths must contain at least one project.repo-relative directory");
        }

        Map<String, Path> resolvedByConfiguredPath = new LinkedHashMap<>();
        for (String configuredPath : configuredPaths) {
            String normalized = normalizeConfiguredPath(configuredPath);
            Path configuredRelative;
            try {
                configuredRelative = Path.of(normalized);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException(
                        "invalid source.paths entry '" + configuredPath + "': invalid path syntax",
                        ex
                );
            }
            if (configuredRelative.isAbsolute() || containsParentTraversal(configuredRelative)) {
                throw invalidPath(configuredPath, "must stay relative to project.repo");
            }
            Path relative = configuredRelative.normalize();
            String canonicalPath = relative.toString().replace('\\', '/');
            if (canonicalPath.isEmpty()) {
                canonicalPath = ".";
            }

            Path resolved = root.resolve(relative).normalize();
            if (!resolved.startsWith(root)) {
                throw invalidPath(configuredPath, "escapes project.repo");
            }
            rejectSymbolicLinkSegments(root, relative, configuredPath);
            if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(resolved)) {
                throw invalidPath(configuredPath, "must resolve to a readable directory");
            }
            try {
                if (!resolved.toRealPath().startsWith(root.toRealPath())) {
                    throw invalidPath(configuredPath, "resolves outside project.repo");
                }
            } catch (IOException ex) {
                throw new IllegalArgumentException(
                        "unable to resolve source.paths entry '" + configuredPath + "'", ex);
            }
            resolvedByConfiguredPath.putIfAbsent(canonicalPath, resolved);
        }

        List<Path> candidates = resolvedByConfiguredPath.values().stream()
                .sorted((left, right) -> {
                    int depthComparison = Integer.compare(left.getNameCount(), right.getNameCount());
                    return depthComparison != 0
                            ? depthComparison
                            : left.toString().compareTo(right.toString());
                })
                .toList();
        List<Path> discoveryRoots = new ArrayList<>();
        for (Path candidate : candidates) {
            if (discoveryRoots.stream().noneMatch(candidate::startsWith)) {
                discoveryRoots.add(candidate);
            }
        }
        return new MyBatisSqlSourceScope(
                root,
                List.copyOf(resolvedByConfiguredPath.keySet()),
                discoveryRoots);
    }

    Path repository() {
        return repository;
    }

    List<String> configuredPaths() {
        return configuredPaths;
    }

    List<Path> discoveryRoots() {
        return discoveryRoots;
    }

    private static String normalizeConfiguredPath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw invalidPath(configuredPath, "must not be blank");
        }
        String normalized = configuredPath.trim().replace('\\', '/');
        if (normalized.startsWith("/")
                || normalized.startsWith("//")
                || normalized.matches("^[A-Za-z]:/.*")) {
            throw invalidPath(configuredPath, "must be relative to project.repo");
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.isEmpty() ? "." : normalized;
    }

    private static boolean containsParentTraversal(Path relative) {
        for (Path segment : relative) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static void rejectSymbolicLinkSegments(Path root, Path relative, String configuredPath) {
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw invalidPath(configuredPath, "must not contain symbolic links");
            }
        }
    }

    private static IllegalArgumentException invalidPath(String configuredPath, String reason) {
        return new IllegalArgumentException(
                "invalid source.paths entry '" + String.valueOf(configuredPath) + "': " + reason);
    }
}
