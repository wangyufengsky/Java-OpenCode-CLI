package com.sonnet.wyf.gitreport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeProcessRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void stopsRunningProcessWhenCompletionProbeSucceeds() throws Exception {
        Path runDir = tempDir.resolve("run");
        Path done = tempDir.resolve("done.txt");
        Path fakeOpenCode = tempDir.resolve("fake-opencode.sh");
        Files.writeString(fakeOpenCode, """
                #!/bin/sh
                printf ready > "$1"
                sleep 30
                """);
        fakeOpenCode.toFile().setExecutable(true);

        long startedAt = System.nanoTime();
        ProcessRunResult result = new OpenCodeProcessRunner().runUntil(
                List.of(fakeOpenCode.toString(), done.toString()),
                runDir,
                1,
                () -> Files.exists(done),
                100
        );
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(result.timedOut()).isFalse();
        assertThat(result.completedByOutput()).isTrue();
        assertThat(elapsedMillis).isLessThan(5_000);
    }
}
