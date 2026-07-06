package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationOutputValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectUnitTestGenerationOutputValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void acceptsCompletedBatchWithTestsUnderSrcTest() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path summary = tempDir.resolve("summary.json");
        Path testFile = repo.resolve("src/test/java/com/acme/FooTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "class FooTest {}\n");
        objectMapper.writeValue(summary.toFile(), Map.of(
                "batch_id", "test-batch-001-com-acme",
                "status", "completed",
                "source_files", List.of("src/main/java/com/acme/Foo.java"),
                "test_files", List.of("src/test/java/com/acme/FooTest.java"),
                "checks", passedChecks(80),
                "notes", List.of()
        ));

        assertThat(new ProjectUnitTestGenerationOutputValidator(objectMapper)
                .validateBatchOutput(repo, "test-batch-001-com-acme", summary, 80).ok()).isTrue();
    }

    @Test
    void acceptsCompletedBatchWithTestsUnderModuleSrcTest() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path summary = tempDir.resolve("summary.json");
        Path testFile = repo.resolve("upfs-cup/src/test/java/com/spdb/upfs/cup/CupServiceTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "class CupServiceTest {}\n");
        objectMapper.writeValue(summary.toFile(), Map.of(
                "batch_id", "test-batch-001-cupservice",
                "status", "completed",
                "source_files", List.of("upfs-cup/src/main/java/com/spdb/upfs/cup/CupService.java"),
                "test_files", List.of("upfs-cup/src/test/java/com/spdb/upfs/cup/CupServiceTest.java"),
                "checks", passedChecks(80),
                "notes", List.of()
        ));

        assertThat(new ProjectUnitTestGenerationOutputValidator(objectMapper)
                .validateBatchOutput(repo, "test-batch-001-cupservice", summary, 80).ok()).isTrue();
    }

    @Test
    void rejectsProductionFileWrites() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path summary = tempDir.resolve("summary.json");
        Path productionFile = repo.resolve("src/main/java/com/acme/FooTest.java");
        Files.createDirectories(productionFile.getParent());
        Files.writeString(productionFile, "class FooTest {}\n");
        objectMapper.writeValue(summary.toFile(), Map.of(
                "batch_id", "test-batch-001-com-acme",
                "status", "completed",
                "source_files", List.of("src/main/java/com/acme/Foo.java"),
                "test_files", List.of("src/main/java/com/acme/FooTest.java"),
                "notes", List.of()
        ));

        assertThat(new ProjectUnitTestGenerationOutputValidator(objectMapper)
                .validateBatchOutput(repo, "test-batch-001-com-acme", summary, 80).error())
                .contains("outside src/test");
    }

    @Test
    void rejectsPathTraversalThatStartsWithSrcTest() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path summary = tempDir.resolve("summary.json");
        Path productionFile = repo.resolve("src/main/java/Foo.java");
        Files.createDirectories(productionFile.getParent());
        Files.writeString(productionFile, "class Foo {}\n");
        objectMapper.writeValue(summary.toFile(), Map.of(
                "batch_id", "test-batch-001-com-acme",
                "status", "completed",
                "source_files", List.of("src/main/java/Foo.java"),
                "test_files", List.of("src/test/../../src/main/java/Foo.java"),
                "notes", List.of()
        ));

        assertThat(new ProjectUnitTestGenerationOutputValidator(objectMapper)
                .validateBatchOutput(repo, "test-batch-001-com-acme", summary, 80).error())
                .contains("outside src/test");
    }

    @Test
    void rejectsCompletedBatchWithoutChecks() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path summary = tempDir.resolve("summary.json");
        Path testFile = repo.resolve("src/test/java/com/acme/FooTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "class FooTest {}\n");
        objectMapper.writeValue(summary.toFile(), Map.of(
                "batch_id", "test-batch-001-com-acme",
                "status", "completed",
                "source_files", List.of("src/main/java/com/acme/Foo.java"),
                "test_files", List.of("src/test/java/com/acme/FooTest.java"),
                "notes", List.of()
        ));

        assertThat(new ProjectUnitTestGenerationOutputValidator(objectMapper)
                .validateBatchOutput(repo, "test-batch-001-com-acme", summary, 80).error())
                .contains("completed unit-test batch must record checks");
    }

    @Test
    void rejectsCompletedBatchWhenCompilationTestsOrCoverageAreNotPassing() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path summary = tempDir.resolve("summary.json");
        Path testFile = repo.resolve("src/test/java/com/acme/FooTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "class FooTest {}\n");

        objectMapper.writeValue(summary.toFile(), completedSummary(Map.of(
                "style_reviewed", true,
                "compilation", Map.of("passed", false),
                "tests", Map.of("passed", true),
                "coverage", Map.of("percent", 80)
        )));
        assertThat(new ProjectUnitTestGenerationOutputValidator(objectMapper)
                .validateBatchOutput(repo, "test-batch-001-com-acme", summary, 80).error())
                .contains("compilation.passed=true");

        objectMapper.writeValue(summary.toFile(), completedSummary(Map.of(
                "style_reviewed", true,
                "compilation", Map.of("passed", true),
                "tests", Map.of("passed", false),
                "coverage", Map.of("percent", 80)
        )));
        assertThat(new ProjectUnitTestGenerationOutputValidator(objectMapper)
                .validateBatchOutput(repo, "test-batch-001-com-acme", summary, 80).error())
                .contains("tests.passed=true");

        objectMapper.writeValue(summary.toFile(), completedSummary(Map.of(
                "style_reviewed", true,
                "compilation", Map.of("passed", true),
                "tests", Map.of("passed", true),
                "coverage", Map.of("percent", 79)
        )));
        assertThat(new ProjectUnitTestGenerationOutputValidator(objectMapper)
                .validateBatchOutput(repo, "test-batch-001-com-acme", summary, 80).error())
                .contains("coverage.percent >= 80");
    }

    @Test
    void acceptsBlockedBatchAsTerminalWithoutRerun() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path summary = tempDir.resolve("summary.json");
        objectMapper.writeValue(summary.toFile(), Map.of(
                "batch_id", "test-batch-001-com-acme",
                "status", "blocked",
                "source_files", List.of("src/main/java/com/acme/Foo.java"),
                "test_files", List.of(),
                "notes", List.of("missing dependencies")
        ));

        ProjectUnitTestGenerationOutputValidator.BatchValidation validation =
                new ProjectUnitTestGenerationOutputValidator(objectMapper)
                        .validateBatchResult(repo, "test-batch-001-com-acme", summary);

        assertThat(validation.check().ok()).isTrue();
        assertThat(validation.retriable()).isFalse();
        assertThat(validation.completed()).isFalse();
        assertThat(validation.status()).isEqualTo("blocked");
    }

    @Test
    void acceptsPartialBatchWhenGeneratedFilesAreValidAndNotesExplainGap() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path summary = tempDir.resolve("summary.json");
        Path testFile = repo.resolve("src/test/java/com/acme/FooTest.java");
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "class FooTest {}\n");
        objectMapper.writeValue(summary.toFile(), Map.of(
                "batch_id", "test-batch-001-com-acme",
                "status", "partial",
                "source_files", List.of("src/main/java/com/acme/Foo.java"),
                "test_files", List.of("src/test/java/com/acme/FooTest.java"),
                "notes", List.of("FooService needs external dependency wiring")
        ));

        ProjectUnitTestGenerationOutputValidator.BatchValidation validation =
                new ProjectUnitTestGenerationOutputValidator(objectMapper)
                        .validateBatchResult(repo, "test-batch-001-com-acme", summary);

        assertThat(validation.check().ok()).isTrue();
        assertThat(validation.retriable()).isFalse();
        assertThat(validation.completed()).isFalse();
        assertThat(validation.status()).isEqualTo("partial");
    }

    private Map<String, Object> completedSummary(Map<String, Object> checks) {
        return Map.of(
                "batch_id", "test-batch-001-com-acme",
                "status", "completed",
                "source_files", List.of("src/main/java/com/acme/Foo.java"),
                "test_files", List.of("src/test/java/com/acme/FooTest.java"),
                "checks", checks,
                "notes", List.of()
        );
    }

    private Map<String, Object> passedChecks(int percent) {
        return Map.of(
                "style_reviewed", true,
                "compilation", Map.of("passed", true),
                "tests", Map.of("passed", true),
                "coverage", Map.of("percent", percent)
        );
    }
}
