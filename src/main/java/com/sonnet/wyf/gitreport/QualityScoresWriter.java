package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class QualityScoresWriter {
    private static final Logger log = LoggerFactory.getLogger(QualityScoresWriter.class);

    private final ObjectMapper objectMapper;
    private final QualityScoreCalculator qualityScoreCalculator;
    private final WorkloadScoreCalculator workloadScoreCalculator;

    QualityScoresWriter(ObjectMapper objectMapper, QualityScoreCalculator qualityScoreCalculator, WorkloadScoreCalculator workloadScoreCalculator) {
        this.objectMapper = objectMapper;
        this.qualityScoreCalculator = qualityScoreCalculator;
        this.workloadScoreCalculator = workloadScoreCalculator;
    }

    Path write(Path output, Map<String, Object> summary, Map<String, Object> indexInputs) throws IOException {
        List<Map<String, Object>> rankings = new ArrayList<>();
        Map<String, Map<String, Object>> summaryByAuthorKey = new LinkedHashMap<>();
        for (Map<String, Object> row : listOfMaps(summary.get("ranking"))) {
            summaryByAuthorKey.put(row.get("author_key").toString(), row);
        }
        for (Map<String, Object> task : listOfMaps(indexInputs.get("tasks"))) {
            String authorKey = task.get("author_key").toString();
            Map<String, Object> summaryRow = summaryByAuthorKey.get(authorKey);
            if (summaryRow == null) {
                throw new IllegalStateException("summary ranking missing author_key: " + authorKey);
            }
            Path qualityPath = Path.of(task.get("quality_summary_json").toString());
            Map<String, Object> qualitySummary = objectMapper.readValue(qualityPath.toFile(), new TypeReference<>() {});
            Map<String, Object> score = qualityScoreCalculator.calculate(qualitySummary);
            double baseScore = number(summaryRow.get("base_workload_score"));
            double adjustment = number(score.get("quality_adjustment_percent"));
            Map<String, Object> ranking = new LinkedHashMap<>();
            ranking.put("author_key", authorKey);
            ranking.put("author", task.get("author"));
            ranking.put("base_rank", summaryRow.get("rank"));
            ranking.put("base_workload_score", baseScore);
            ranking.put("quality_adjustment_percent", adjustment);
            ranking.put("workload_score", workloadScoreCalculator.adjusted(baseScore, adjustment));
            ranking.put("quality_score", score);
            ranking.put("quality_summary_json", qualityPath.toString());
            rankings.add(ranking);
        }
        rankings.sort(Comparator.<Map<String, Object>>comparingDouble(row -> number(row.get("workload_score"))).reversed());
        for (int index = 0; index < rankings.size(); index++) {
            rankings.get(index).put("final_rank", index + 1);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generated_at", OffsetDateTime.now().toString());
        root.put("rankings", rankings);
        Files.createDirectories(output.toAbsolutePath().getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), root);
        log.info("Wrote quality scores: output={}, rankingCount={}", output, rankings.size());
        return output;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}
