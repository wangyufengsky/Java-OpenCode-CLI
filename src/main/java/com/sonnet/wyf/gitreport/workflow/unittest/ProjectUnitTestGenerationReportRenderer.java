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
        for (JsonNode batch : batches.path("batches")) {
            String batchId = batch.path("batch_id").asText();
            Path summaryPath = Path.of(batch.path("summary_json").asText());
            JsonNode summary = read(summaryPath);
            report.append("- `").append(batchId).append("`: ")
                    .append(summary.path("status").asText("missing"))
                    .append(", tests=")
                    .append(summary.path("test_files").size())
                    .append("\n");
        }
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
