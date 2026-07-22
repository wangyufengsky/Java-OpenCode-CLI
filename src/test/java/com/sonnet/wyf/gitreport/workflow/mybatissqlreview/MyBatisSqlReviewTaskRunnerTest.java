package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void rejectsAnotherSemanticallyValidBundleSubstitutedAfterCopyBeforeSeal() throws Exception {
        Path attempt = Files.createDirectories(tempDir.resolve("sealed-attempt"));
        Path candidate = Files.createDirectories(attempt.resolve("candidate"));
        Path target = Files.createDirectories(tempDir.resolve("sealed-bundle"));
        for (String artifact : ARTIFACTS) {
            Files.writeString(candidate.resolve(artifact), "validated-set-" + artifact);
            Files.writeString(target.resolve(artifact), "alternate-valid-set-" + artifact);
        }
        MyBatisSqlReviewTaskRunner.CandidateSnapshot validated =
                MyBatisSqlReviewTaskRunner.captureCandidate(candidate);
        MyBatisSqlReviewTaskRunner.CandidateSnapshot substituted =
                MyBatisSqlReviewTaskRunner.captureCandidate(target);
        Map<Path, MyBatisSqlReviewFilesystemGuard.SealedFile> sealedFiles = new LinkedHashMap<>();
        for (String artifact : ARTIFACTS) {
            Path path = target.resolve(artifact).toAbsolutePath().normalize();
            MyBatisSqlReviewTaskRunner.CandidateFile file = substituted.files().get(artifact);
            sealedFiles.put(path, new MyBatisSqlReviewFilesystemGuard.SealedFile(
                    path, file.fileKey(), file.lastModifiedTime(), file.size(), file.sha256()
            ));
        }
        var sealed = new MyBatisSqlReviewFilesystemGuard.SealedWrite<>(target, sealedFiles);

        assertThatThrownBy(() -> MyBatisSqlReviewTaskRunner.verifySealedBundle(
                validated, substituted, target, sealed
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differs from validated candidate");
    }
}
