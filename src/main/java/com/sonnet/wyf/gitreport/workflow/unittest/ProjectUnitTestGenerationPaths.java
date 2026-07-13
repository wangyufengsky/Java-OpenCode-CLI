package com.sonnet.wyf.gitreport.workflow.unittest;

import java.nio.file.Path;
import java.util.List;

final class ProjectUnitTestGenerationPaths {
    private ProjectUnitTestGenerationPaths() {
    }

    static boolean isTestSource(Path repo, Path file) {
        Path repoRoot = repo.toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(repoRoot)) {
            return false;
        }
        Path relative = repoRoot.relativize(normalized);
        for (int index = 0; index < relative.getNameCount() - 1; index++) {
            if ("src".equals(relative.getName(index).toString())
                    && "test".equals(relative.getName(index + 1).toString())) {
                return true;
            }
        }
        return false;
    }

    static boolean isBuildArtifact(Path repo, Path file) {
        Path repoRoot = repo.toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(repoRoot)) {
            return false;
        }
        if (isSourceTree(repoRoot, normalized)) {
            return false;
        }
        Path relative = repoRoot.relativize(normalized);
        for (int index = 0; index < relative.getNameCount() - 1; index++) {
            String segment = relative.getName(index).toString();
            if ("target".equals(segment) || "build".equals(segment) || ".gradle".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSourceTree(Path repo, Path file) {
        Path repoRoot = repo.toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(repoRoot)) {
            return false;
        }
        Path relative = repoRoot.relativize(normalized);
        for (int index = 0; index < relative.getNameCount() - 1; index++) {
            if ("src".equals(relative.getName(index).toString())) {
                String next = relative.getName(index + 1).toString();
                if ("main".equals(next) || "test".equals(next)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isAllowedBatchTestWrite(Path repo, Path file, List<String> allowedWriteGlobs, List<String> targetTestFiles) {
        Path repoRoot = repo.toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(repoRoot) || !isTestSource(repoRoot, normalized)) {
            return false;
        }
        String relative = normalize(repoRoot.relativize(normalized).toString());
        for (String targetTestFile : targetTestFiles) {
            if (relative.equals(normalize(targetTestFile))) {
                return true;
            }
        }
        for (String allowedWriteGlob : allowedWriteGlobs) {
            if (matchesGlob(relative, normalize(allowedWriteGlob))) {
                return true;
            }
        }
        return false;
    }

    static boolean isAllowedBatchPomWrite(Path repo, Path file, List<String> allowedWriteGlobs) {
        Path repoRoot = repo.toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(repoRoot) || !"pom.xml".equals(normalized.getFileName().toString())) {
            return false;
        }
        String relative = normalize(repoRoot.relativize(normalized).toString());
        return allowedWriteGlobs.stream()
                .map(ProjectUnitTestGenerationPaths::normalize)
                .anyMatch(relative::equals);
    }

    private static boolean matchesGlob(String relative, String glob) {
        if (glob.endsWith("/**")) {
            return relative.startsWith(glob.substring(0, glob.length() - 2));
        }
        return relative.equals(glob);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/').replaceAll("^/+", "");
    }
}
