package com.sonnet.wyf.gitreport.orchestration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactCompletenessValidatorTest {
    private final ArtifactCompletenessValidator validator = new ArtifactCompletenessValidator();

    @TempDir
    Path tempDir;

    @Test
    void reportsMissingFile() {
        ArtifactCompletenessValidator.Validation validation = validator.validateFile(
                "person report",
                tempDir.resolve("missing.md"),
                List.of("{{BODY}}")
        );

        assertThat(validation.ok()).isFalse();
        assertThat(validation.error()).contains("person report missing");
    }

    @Test
    void reportsBlankFile() throws Exception {
        Path report = tempDir.resolve("blank.md");
        Files.writeString(report, " \n");

        ArtifactCompletenessValidator.Validation validation = validator.validateFile(
                "person report",
                report,
                List.of("{{BODY}}")
        );

        assertThat(validation.ok()).isFalse();
        assertThat(validation.error()).contains("person report is blank");
    }

    @Test
    void reportsConfiguredPlaceholderResidue() throws Exception {
        Path report = tempDir.resolve("placeholder.md");
        Files.writeString(report, "# report\n{{BODY}}\n");

        ArtifactCompletenessValidator.Validation validation = validator.validateFile(
                "person report",
                report,
                List.of("{{BODY}}")
        );

        assertThat(validation.ok()).isFalse();
        assertThat(validation.error()).contains("person report still contains template placeholder");
    }

    @Test
    void reportsGenericTemplatePlaceholderResidue() throws Exception {
        Path report = tempDir.resolve("generic.md");
        Files.writeString(report, "# report\n{{UNLISTED}}\n");

        ArtifactCompletenessValidator.Validation validation = validator.validateFile(
                "person report",
                report,
                List.of("{{BODY}}")
        );

        assertThat(validation.ok()).isFalse();
        assertThat(validation.error()).contains("unresolved template placeholder");
    }

    @Test
    void validatesPlaceholderMappedOutputs() throws Exception {
        Path report = tempDir.resolve("review.md");
        Files.writeString(report, "# review\n完成\n");

        ArtifactCompletenessValidator.Validation validation = validator.validateOutputs(
                Map.of("review_md", List.of("{{REVIEW}}")),
                key -> report
        );

        assertThat(validation.ok()).isTrue();
        assertThat(validation.error()).isBlank();
    }
}
