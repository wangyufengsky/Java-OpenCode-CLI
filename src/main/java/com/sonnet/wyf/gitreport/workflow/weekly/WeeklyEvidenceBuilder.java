package com.sonnet.wyf.gitreport.workflow.weekly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.GitReportProperties;
import com.sonnet.wyf.gitreport.preparation.GitStatsCollector;
import com.sonnet.wyf.gitreport.util.JsonMaps;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WeeklyEvidenceBuilder {
    public static final String SCHEMA_VERSION = "weekly-engineering-report/v1";

    private final ObjectMapper objectMapper;
    private final GitStatsCollector gitStatsCollector;
    private final WeeklyReviewUnitGrouper reviewUnitGrouper = new WeeklyReviewUnitGrouper();

    public WeeklyEvidenceBuilder(ObjectMapper objectMapper, GitStatsCollector gitStatsCollector) {
        this.objectMapper = objectMapper;
        this.gitStatsCollector = gitStatsCollector;
    }

    public Path build(WeeklyEngineeringReportProperties properties, LocalDate runDate) throws Exception {
        Path out = properties.getPaths().getOut().toAbsolutePath().normalize();
        Files.createDirectories(out);
        Map<String, Object> weeklyGit = collectWeeklyGit(properties, runDate, out);
        List<Map<String, Object>> batches = buildReviewBatches(properties, out, weeklyGit);

        Path weeklyGitEvidence = out.resolve("weekly-git-evidence.json");
        Path reviewBatches = out.resolve("review-batches.json");
        Path reviewUnits = out.resolve("review-units.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(weeklyGitEvidence.toFile(), weeklyGit);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reviewBatches.toFile(), Map.of("batches", batches));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reviewUnits.toFile(), Map.of("units", batches));
        writeBatchInputs(out, batches);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schema_version", SCHEMA_VERSION);
        evidence.put("generated_at", OffsetDateTime.now().toString());
        evidence.put("week", week(properties, runDate));
        evidence.put("project", project(properties, out));
        evidence.put("source_runs", Map.of("weekly_git", Map.of(
                "status", "generated",
                "weekly_git_evidence_json", weeklyGitEvidence.toString(),
                "review_batches_json", reviewBatches.toString(),
                "review_units_json", reviewUnits.toString()
        )));
        evidence.put("weekly_git", weeklyGit);
        evidence.put("review_batches", batches);
        evidence.put("data_quality", dataQuality());

        Path evidencePath = out.resolve("weekly-evidence.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(evidencePath.toFile(), evidence);
        return evidencePath;
    }

    private Map<String, Object> collectWeeklyGit(WeeklyEngineeringReportProperties properties, LocalDate runDate, Path out) throws Exception {
        GitReportProperties gitProperties = new GitReportProperties();
        gitProperties.getProject().setId(properties.getProject().getId());
        gitProperties.getProject().setName(properties.getProject().getName());
        gitProperties.getProject().setRunId(properties.effectiveWeekLabel(runDate));
        gitProperties.getPaths().setRepo(properties.getProject().getRepo());
        gitProperties.getPaths().setOut(out);
        gitProperties.getGit().setSince(properties.effectiveWeekStart(runDate));
        gitProperties.getGit().setUntil(properties.effectiveWeekEnd(runDate));
        gitProperties.getGit().setRevision(properties.getProject().getRevision());
        gitProperties.getGit().setIncludeMerges(properties.getGit().isIncludeMerges());
        gitProperties.getGit().setAuthorMap(properties.getGit().getAuthorMap());
        gitProperties.getGit().setInclude(properties.getGit().getInclude());
        gitProperties.getGit().setExclude(properties.getGit().getExclude());
        gitProperties.getDetailInput().setChangedRegionLines(properties.getReview().getMaxHunkLines());

        Map<String, Object> collected = gitStatsCollector.collect(gitProperties);
        attachAuthorKeys(collected);
        return collected;
    }

    private void attachAuthorKeys(Map<String, Object> weeklyGit) {
        int index = 1;
        int regionIndex = 1;
        for (Map<String, Object> author : listOfMaps(weeklyGit.get("authors"))) {
            String authorKey = makeAuthorKey(index++, string(author.get("author")));
            author.put("author_key", authorKey);
            for (Map<String, Object> region : listOfMaps(author.get("changed_regions"))) {
                region.put("region_id", "region-%05d".formatted(regionIndex++));
                region.put("author_key", authorKey);
                region.put("author", author.get("author"));
            }
        }
    }

    private List<Map<String, Object>> buildReviewBatches(WeeklyEngineeringReportProperties properties, Path out, Map<String, Object> weeklyGit) {
        return reviewUnitGrouper.buildReviewBatches(properties, out, weeklyGit);
    }

    private void writeBatchInputs(Path out, List<Map<String, Object>> batches) throws Exception {
        for (Map<String, Object> batch : batches) {
            Path input = Path.of(string(batch.get("input_json")));
            Files.createDirectories(input.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(input.toFile(), batch);
        }
    }

    private Map<String, Object> week(WeeklyEngineeringReportProperties properties, LocalDate runDate) {
        return Map.of(
                "start", properties.effectiveWeekStart(runDate).toString(),
                "end", properties.effectiveWeekEnd(runDate).toString(),
                "label", properties.effectiveWeekLabel(runDate)
        );
    }

    private Map<String, Object> project(WeeklyEngineeringReportProperties properties, Path out) {
        return Map.of(
                "id", string(properties.getProject().getId()),
                "name", string(properties.getProject().getName()),
                "repo", properties.getProject().getRepo().toString(),
                "revision", string(properties.getProject().getRevision()),
                "out", out.toString()
        );
    }

    private Map<String, Object> dataQuality() {
        return Map.of(
                "status", "clean",
                "issues", List.of(),
                "known_biases", List.of(
                        "提交量不等于业务价值",
                        "Git 无法覆盖沟通、排障、设计和评审投入",
                        "代码审查 finding 只允许归因到统计窗口内 changed regions"
                )
        );
    }

    private String makeAuthorKey(int rank, String author) {
        String normalized = author.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        return "author-%03d-%s".formatted(rank, normalized.substring(0, Math.min(60, normalized.length())));
    }

    private String string(Object value) {
        return JsonMaps.string(value);
    }

    private int number(Object value) {
        return JsonMaps.number(value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return JsonMaps.listOfMaps(value);
    }
}
