package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisSqlReviewTaskRunnerTest {
    private static final List<String> ARTIFACTS = List.of(
            "report.md", "summary.json", "database-evidence.json"
    );

    @TempDir
    Path tempDir;

    @Test
    void rejectsCandidateMutationBetweenValidatedCaptureAndBundleCopy() throws Exception {
        Path attempt = Files.createDirectories(tempDir.resolve("attempt"));
        Path candidate = Files.createDirectories(attempt.resolve("candidate"));
        Path target = Files.createDirectories(tempDir.resolve("bundle"));
        for (String artifact : ARTIFACTS) {
            Files.writeString(candidate.resolve(artifact), "validated-" + artifact);
            Files.createFile(target.resolve(artifact));
        }
        MyBatisSqlReviewTaskRunner.CandidateSnapshot snapshot =
                MyBatisSqlReviewTaskRunner.captureCandidate(candidate);
        Files.writeString(candidate.resolve("summary.json"), "changed after validation");

        assertThatThrownBy(() -> MyBatisSqlReviewTaskRunner.copyCandidateSnapshot(
                candidate, target, snapshot
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidate changed");
    }

    @Test
    void rejectsCandidateArtifactThatExceedsThePerFileLimit() throws Exception {
        Path attempt = Files.createDirectories(tempDir.resolve("large-attempt"));
        Path candidate = Files.createDirectories(attempt.resolve("candidate"));
        Files.write(candidate.resolve("report.md"),
                new byte[(int) MyBatisSqlReviewTaskRunner.MAX_CANDIDATE_FILE_BYTES + 1]);
        Files.writeString(candidate.resolve("summary.json"), "{}");
        Files.writeString(candidate.resolve("database-evidence.json"), "{}");

        assertThatThrownBy(() -> MyBatisSqlReviewTaskRunner.captureCandidate(candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safe regular-file limit");
    }
}
