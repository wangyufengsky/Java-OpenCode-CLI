package com.sonnet.wyf.gitreport.workflow.unittest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

public class ProjectUnitTestGenerationReportRenderer {
    private final ObjectMapper objectMapper;

    public ProjectUnitTestGenerationReportRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path render(Path out) throws Exception {
        Files.createDirectories(out);
        JsonNode batches = read(out.resolve("test-batches.json"));
        JsonNode verification = read(out.resolve("verification.json"));
        StringBuilder report = new StringBuilder();
        report.append("# project-unit-test-generation\n\n");
        report.append("## Batches\n\n");
        int completed = 0;
        int partial = 0;
        int blocked = 0;
        for (JsonNode batch : batches.path("batches")) {
            String batchId = batch.path("batch_id").asText();
            Path summaryPath = Path.of(batch.path("summary_json").asText());
            JsonNode summary = read(summaryPath);
            String status = summary.path("status").asText("missing");
            if ("completed".equals(status)) {
                completed++;
            } else if ("partial".equals(status)) {
                partial++;
            } else if ("blocked".equals(status)) {
                blocked++;
            }
            report.append("- `").append(batchId).append("`: ")
                    .append(status)
                    .append(", tests=")
                    .append(summary.path("test_files").size())
                    .append("\n");
            if (summary.path("notes").isArray()) {
                for (JsonNode note : summary.path("notes")) {
                    if (!note.asText().isBlank()) {
                        report.append("  - note: ").append(note.asText()).append("\n");
                    }
                }
            }
        }
        report.append("\n## Batch Summary\n\n");
        report.append("- completed: `").append(completed).append("`\n");
        report.append("- partial: `").append(partial).append("`\n");
        report.append("- blocked: `").append(blocked).append("`\n");
        report.append("\n## Verification\n\n");
        report.append("- success: `").append(verification.path("success").asBoolean(false)).append("`\n");
        report.append("- exit_code: `").append(verification.path("exit_code").asInt(-1)).append("`\n");
        Path reportPath = out.resolve("unit-test-generation-report.md");
        Files.writeString(reportPath, report.toString());
        return reportPath;
    }

    private JsonNode read(Path path) throws Exception {
        if (!Files.exists(path)) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(path.toFile());
    }
}
