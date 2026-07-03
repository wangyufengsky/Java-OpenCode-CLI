package com.sonnet.wyf.gitreport.workflow.unittest;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ProjectUnitTestGenerationVerifier {
    private static final int OUTPUT_LIMIT = 120_000;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(20);

    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public ProjectUnitTestGenerationVerifier(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_TIMEOUT);
    }

    public ProjectUnitTestGenerationVerifier(ObjectMapper objectMapper, Duration timeout) {
        this.objectMapper = objectMapper;
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
    }

    public boolean verify(Path repo, Path out, List<String> command) throws Exception {
        Files.createDirectories(out);
        List<String> effectiveCommand = command == null || command.isEmpty() ? List.of("./mvnw", "test") : command;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generated_at", OffsetDateTime.now().toString());
        result.put("repo", repo.toString());
        result.put("command", effectiveCommand);
        try {
            Process process = new ProcessBuilder(effectiveCommand)
                    .directory(repo.toFile())
                    .redirectErrorStream(true)
                    .start();
            CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(() -> readProcessOutput(process));
            boolean completed = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!completed) {
                destroyProcessTree(process);
                result.put("exit_code", -1);
                result.put("stdout", truncate(new String(readAvailable(stdoutFuture), StandardCharsets.UTF_8)));
                result.put("stderr", "verification command timed out after " + timeout.toMillis() + " ms");
                result.put("success", false);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("verification.json").toFile(), result);
                return false;
            }
            int exitCode = process.exitValue();
            result.put("exit_code", exitCode);
            result.put("stdout", truncate(new String(readAvailable(stdoutFuture), StandardCharsets.UTF_8)));
            result.put("stderr", "");
            result.put("success", exitCode == 0);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("verification.json").toFile(), result);
            return exitCode == 0;
        } catch (Exception exception) {
            result.put("exit_code", -1);
            result.put("stdout", "");
            result.put("stderr", exception.getClass().getName() + ": " + (exception.getMessage() == null ? "" : exception.getMessage()));
            result.put("success", false);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("verification.json").toFile(), result);
            return false;
        }
    }

    private byte[] readProcessOutput(Process process) {
        try {
            return process.getInputStream().readAllBytes();
        } catch (Exception exception) {
            return ("[failed to read verification output: " + exception.getMessage() + "]").getBytes(StandardCharsets.UTF_8);
        }
    }

    private byte[] readAvailable(CompletableFuture<byte[]> stdoutFuture) {
        try {
            return stdoutFuture.get(2, TimeUnit.SECONDS);
        } catch (Exception exception) {
            return ("[verification output unavailable: " + exception.getMessage() + "]").getBytes(StandardCharsets.UTF_8);
        }
    }

    private void destroyProcessTree(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= OUTPUT_LIMIT) {
            return value == null ? "" : value;
        }
        return value.substring(0, OUTPUT_LIMIT) + "\n...[truncated]";
    }
}
