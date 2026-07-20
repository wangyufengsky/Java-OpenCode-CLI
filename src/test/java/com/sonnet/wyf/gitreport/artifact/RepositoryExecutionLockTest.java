package com.sonnet.wyf.gitreport.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryExecutionLockTest {
    @TempDir
    Path tempDir;

    @Test
    void preventsTwoUnitTestWorkflowsFromUsingTheSameRepository() throws Exception {
        Files.createDirectories(tempDir.resolve(".git"));

        try (RepositoryExecutionLock first = RepositoryExecutionLock.acquire(tempDir)) {
            assertThat(first.path()).isEqualTo(tempDir.resolve(".git/java-agentbridge-workflow.lock"));
            assertThatThrownBy(() -> RepositoryExecutionLock.acquire(tempDir))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already used");
        }

        try (RepositoryExecutionLock reacquired = RepositoryExecutionLock.acquire(tempDir)) {
            assertThat(reacquired.path()).exists();
        }
    }
}
