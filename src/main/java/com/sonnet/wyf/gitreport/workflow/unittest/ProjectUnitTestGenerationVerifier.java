package com.sonnet.wyf.gitreport.workflow.unittest;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ProjectUnitTestGenerationVerifier {
    private static final int OUTPUT_LIMIT = 120_000;

    private final ObjectMapper objectMapper;

    public ProjectUnitTestGenerationVerifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
            byte[] stdout = process.getInputStream().readAllBytes();
            boolean completed = process.waitFor(20, TimeUnit.MINUTES);
            if (!completed) {
                process.destroyForcibly();
                result.put("exit_code", -1);
                result.put("stdout", truncate(new String(stdout, StandardCharsets.UTF_8)));
                result.put("stderr", "verification command timed out after 20 minutes");
                result.put("success", false);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("verification.json").toFile(), result);
                return false;
            }
            int exitCode = process.exitValue();
            result.put("exit_code", exitCode);
            result.put("stdout", truncate(new String(stdout, StandardCharsets.UTF_8)));
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

    private String truncate(String value) {
        if (value == null || value.length() <= OUTPUT_LIMIT) {
            return value == null ? "" : value;
        }
        return value.substring(0, OUTPUT_LIMIT) + "\n...[truncated]";
    }
}
