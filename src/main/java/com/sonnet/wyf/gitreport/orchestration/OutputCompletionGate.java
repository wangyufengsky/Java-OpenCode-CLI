package com.sonnet.wyf.gitreport.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OutputCompletionGate {
    public static final int DEFAULT_MAX_RERUN_ROUNDS = 5;

    private final ObjectMapper objectMapper;
    private final int maxRerunRounds;

    public OutputCompletionGate(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_MAX_RERUN_ROUNDS);
    }

    public OutputCompletionGate(ObjectMapper objectMapper, int maxRerunRounds) {
        this.objectMapper = objectMapper;
        this.maxRerunRounds = maxRerunRounds;
    }

    public void ensureComplete(
            String workflowName,
            Path statusPath,
            OutputInspector inspector,
            OutputRerunner rerunner
    ) throws Exception {
        List<Map<String, Object>> history = new java.util.ArrayList<>();
        for (int rerunRound = 0; ; rerunRound++) {
            List<IncompleteOutput> incomplete = inspector.incompleteOutputs();
            if (incomplete.isEmpty()) {
                writeStatus(statusPath, "completed", rerunRound, List.of(), history);
                return;
            }
            history.add(round(rerunRound, incomplete));
            if (rerunRound >= maxRerunRounds) {
                writeStatus(statusPath, "failed", rerunRound, incomplete, history);
                throw new IllegalStateException(workflowName + " outputs incomplete after "
                        + maxRerunRounds
                        + " rerun rounds: "
                        + incomplete.stream().map(IncompleteOutput::summary).collect(Collectors.joining("; ")));
            }
            writeStatus(statusPath, "rerunning", rerunRound, incomplete, history);
            rerunner.rerun(incomplete, rerunRound + 1, maxRerunRounds);
        }
    }

    private Map<String, Object> round(int rerunRound, List<IncompleteOutput> incomplete) {
        Map<String, Object> round = new LinkedHashMap<>();
        round.put("rerunRound", rerunRound);
        round.put("recordedAt", OffsetDateTime.now().toString());
        round.put("incomplete", incomplete.stream().map(IncompleteOutput::toMap).toList());
        return round;
    }

    private void writeStatus(
            Path statusPath,
            String state,
            int rerunRounds,
            List<IncompleteOutput> incomplete,
            List<Map<String, Object>> history
    ) throws Exception {
        Files.createDirectories(statusPath.getParent());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", state);
        status.put("rerunRounds", rerunRounds);
        status.put("maxRerunRounds", maxRerunRounds);
        status.put("recordedAt", OffsetDateTime.now().toString());
        status.put("incomplete", incomplete.stream().map(IncompleteOutput::toMap).toList());
        status.put("history", history);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(statusPath.toFile(), status);
    }

    @FunctionalInterface
    public interface OutputInspector {
        List<IncompleteOutput> incompleteOutputs() throws Exception;
    }

    @FunctionalInterface
    public interface OutputRerunner {
        void rerun(List<IncompleteOutput> incomplete, int rerunRound, int maxRerunRounds) throws Exception;
    }

    public record IncompleteOutput(String type, String name, Path artifactPath, String taskPath, String reason) {
        public String summary() {
            return type + " " + name + ": " + reason;
        }

        Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("name", name);
            row.put("artifactPath", artifactPath.toString());
            row.put("taskPath", taskPath);
            row.put("reason", reason);
            return row;
        }
    }
}
