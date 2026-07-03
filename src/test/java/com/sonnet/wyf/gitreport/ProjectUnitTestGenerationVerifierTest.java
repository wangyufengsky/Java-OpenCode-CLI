package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectUnitTestGenerationVerifierTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void recordsSuccessfulVerification() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path out = tempDir.resolve("out");
        Files.createDirectories(repo);

        boolean success = new ProjectUnitTestGenerationVerifier(objectMapper)
                .verify(repo, out, shell("printf verified"));

        JsonNode verification = objectMapper.readTree(out.resolve("verification.json").toFile());
        assertThat(success).isTrue();
        assertThat(verification.path("exit_code").asInt()).isEqualTo(0);
        assertThat(verification.path("stdout").asText()).contains("verified");
    }

    @Test
    void recordsFailedVerificationWithoutLosingOutput() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path out = tempDir.resolve("out");
        Files.createDirectories(repo);

        boolean success = new ProjectUnitTestGenerationVerifier(objectMapper)
                .verify(repo, out, shell("printf broken && exit 7"));

        JsonNode verification = objectMapper.readTree(out.resolve("verification.json").toFile());
        assertThat(success).isFalse();
        assertThat(verification.path("exit_code").asInt()).isEqualTo(7);
        assertThat(verification.path("stdout").asText()).contains("broken");
    }

    @Test
    void capturesLargeStderrWithoutDeadlock() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path out = tempDir.resolve("out");
        Files.createDirectories(repo);

        boolean success = new ProjectUnitTestGenerationVerifier(objectMapper)
                .verify(repo, out, shell("python3 - <<'PY'\nimport sys\nsys.stderr.write('x' * 200000)\nPY"));

        JsonNode verification = objectMapper.readTree(out.resolve("verification.json").toFile());
        assertThat(success).isTrue();
        assertThat(verification.path("exit_code").asInt()).isEqualTo(0);
        assertThat(verification.path("stdout").asText()).contains("x");
    }

    private List<String> shell(String command) {
        return List.of("sh", "-c", command);
    }
}
