package com.sonnet.wyf.gitreport.workflow.unittest;

import java.nio.file.Path;

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
}
