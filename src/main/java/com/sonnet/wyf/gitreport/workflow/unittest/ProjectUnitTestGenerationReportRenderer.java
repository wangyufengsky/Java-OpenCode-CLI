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
        JsonNode results = read(out.resolve(ProjectUnitTestGenerationBatchRunner.RESULTS_JSON));
        StringBuilder report = new StringBuilder();
        report.append("# project-unit-test-generation\n\n");
        report.append("## Batches\n\n");
        int accepted = 0;
        int failed = 0;
        for (JsonNode batch : batches.path("batches")) {
            String batchId = batch.path("batch_id").asText();
            JsonNode result = result(results, batchId);
            boolean ok = result.path("accepted").asBoolean(false);
            if (ok) {
                accepted++;
            } else {
                failed++;
            }
            report.append("- `").append(batchId).append("`: ")
                    .append(ok ? "accepted" : "failed")
                    .append(", attempts=")
                    .append(result.path("attempts").size())
                    .append("\n");
            String summary = result.path("failure_summary").asText("");
            if (!summary.isBlank()) {
                report.append("  - summary: ").append(summary).append("\n");
            }
        }
        report.append("\n## Batch Summary\n\n");
        report.append("- accepted: `").append(accepted).append("`\n");
        report.append("- failed: `").append(failed).append("`\n");
        Path reportPath = out.resolve("unit-test-generation-report.md");
        Files.writeString(reportPath, report.toString());
        return reportPath;
    }

    private JsonNode result(JsonNode results, String batchId) {
        for (JsonNode result : results.path("batches")) {
            if (batchId.equals(result.path("batch_id").asText())) {
                return result;
            }
        }
        return objectMapper.createObjectNode();
    }

    private JsonNode read(Path path) throws Exception {
        if (!Files.exists(path)) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(path.toFile());
    }
}
