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
                "notes", List.of()
        ));

        assertThat(new ProjectUnitTestGenerationOutputValidator(objectMapper)
                .validateBatchOutput(repo, "test-batch-001-com-acme", summary).ok()).isTrue();
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
                .validateBatchOutput(repo, "test-batch-001-com-acme", summary).error())
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
                .validateBatchOutput(repo, "test-batch-001-com-acme", summary).error())
                .contains("outside src/test");
    }

    @Test
    void treatsBlockedBatchAsIncomplete() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path summary = tempDir.resolve("summary.json");
        objectMapper.writeValue(summary.toFile(), Map.of(
                "batch_id", "test-batch-001-com-acme",
                "status", "blocked",
                "source_files", List.of("src/main/java/com/acme/Foo.java"),
                "test_files", List.of(),
                "notes", List.of("missing dependencies")
        ));

        assertThat(new ProjectUnitTestGenerationOutputValidator(objectMapper)
                .validateBatchOutput(repo, "test-batch-001-com-acme", summary).error())
                .contains("not completed");
    }
}
