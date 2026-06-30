package com.sonnet.wyf.gitreport.workflow.weekly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.GitReportProperties;
import com.sonnet.wyf.gitreport.preparation.GitStatsCollector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class WeeklyEvidenceBuilder {
    public static final String SCHEMA_VERSION = "weekly-engineering-report/v1";

    private final ObjectMapper objectMapper;
    private final GitStatsCollector gitStatsCollector;

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
        Map<String, List<Map<String, Object>>> byGroup = new LinkedHashMap<>();
        for (Map<String, Object> author : listOfMaps(weeklyGit.get("authors"))) {
            for (Map<String, Object> region : listOfMaps(author.get("changed_regions"))) {
                String file = string(region.get("file"));
                if (!file.isBlank()) {
                    String module = moduleKey(file);
                    String authorKey = string(region.get("author_key"));
                    byGroup.computeIfAbsent(module + "\u0000" + authorKey, ignored -> new ArrayList<>()).add(region);
                }
            }
        }

        WeeklyEngineeringReportProperties.Grouping grouping = properties.getReview().getGrouping();
        int maxRegions = Math.max(1, grouping.getMaxRegionsPerTask());
        int maxFiles = Math.max(1, grouping.getMaxFilesPerTask());
        int maxHunkChars = Math.max(1, grouping.getMaxHunkCharsPerTask());
        int maxCommits = Math.max(1, grouping.getMaxCommitsPerTask());
        List<Map<String, Object>> batches = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byGroup.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            List<Map<String, Object>> regions = entry.getValue().stream()
                    .sorted(Comparator.comparing(row -> string(row.get("commit")) + ":" + string(row.get("file")) + ":" + number(row.get("line_start"))))
                    .toList();
            String[] groupParts = entry.getKey().split("\u0000", 2);
            String module = groupParts[0];
            String authorKey = groupParts.length > 1 ? groupParts[1] : "";
            UnitAccumulator current = new UnitAccumulator(grouping.getStrategy(), module, authorKey, maxRegions, maxFiles, maxHunkChars, maxCommits);
            for (Map<String, Object> region : regions) {
                if (!current.isEmpty() && !current.canAccept(region)) {
                    batches.add(toBatch(out, batches.size() + 1, current));
                    current = new UnitAccumulator(grouping.getStrategy(), module, authorKey, maxRegions, maxFiles, maxHunkChars, maxCommits);
                }
                current.add(region);
            }
            if (!current.isEmpty()) {
                batches.add(toBatch(out, batches.size() + 1, current));
            }
        }
        return batches;
    }

    private Map<String, Object> toBatch(Path out, int index, UnitAccumulator unit) {
        String batchId = "review-unit-%03d-%s-%s".formatted(index, slug(unit.module), slug(unit.authorKey));
        Path batchDir = out.resolve("review-units").resolve(batchId);
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("batch_id", batchId);
        batch.put("unit_id", batchId);
        batch.put("scope", Map.of("type", "module-author", "path", unit.module));
        batch.put("group", Map.of(
                "strategy", unit.strategy,
                "module", unit.module,
                "author_key", unit.authorKey,
                "region_count", unit.regions.size(),
                "file_count", unit.files.size(),
                "commit_count", unit.commits.size(),
                "hunk_chars", unit.hunkChars
        ));
        batch.put("changed_regions", List.copyOf(unit.regions));
        batch.put("input_json", batchDir.resolve("input.json").toString());
        batch.put("review_md", batchDir.resolve("code-review.md").toString());
        batch.put("summary_json", batchDir.resolve("code-review-summary.json").toString());
        batch.put("status", "pending");
        return batch;
    }

    private String moduleKey(String file) {
        String normalized = file.replace('\\', '/');
        if (normalized.contains("/src/main/java/")) {
            return prefixWithSegments(normalized, "/src/main/java/", 7);
        }
        if (normalized.startsWith("src/main/java/")) {
            return prefixWithSegments(normalized, "src/main/java/", 0);
        }
        if (normalized.contains("/src/test/java/")) {
            return prefixWithSegments(normalized, "/src/test/java/", 3);
        }
        if (normalized.startsWith("src/test/java/")) {
            return prefixWithSegments(normalized, "src/test/java/", 0);
        }
        if (normalized.contains("/src/main/resources/mapper/")) {
            return normalized.substring(0, normalized.indexOf("/src/main/resources/mapper/") + "/src/main/resources/mapper".length());
        }
        if (normalized.contains("/src/main/resources/mybatis/mapper/")) {
            return normalized.substring(0, normalized.indexOf("/src/main/resources/mybatis/mapper/") + "/src/main/resources/mybatis/mapper".length());
        }
        if (normalized.contains("/src/main/resources/")) {
            return normalized.substring(0, normalized.indexOf("/src/main/resources/") + "/src/main/resources".length());
        }
        int slash = normalized.indexOf('/');
        return slash > 0 ? normalized.substring(0, slash) : normalized;
    }

    private String prefixWithSegments(String file, String marker, int segmentCount) {
        int markerIndex = file.indexOf(marker);
        if (markerIndex < 0) {
            return file;
        }
        String prefix = file.substring(0, markerIndex + marker.length()).replaceAll("/$", "");
        if (segmentCount <= 0) {
            return prefix;
        }
        String rest = file.substring(markerIndex + marker.length());
        String[] segments = rest.split("/");
        int count = Math.min(segmentCount, Math.max(0, segments.length - 1));
        StringBuilder module = new StringBuilder(prefix);
        for (int index = 0; index < count; index++) {
            module.append('/').append(segments[index]);
        }
        return module.toString();
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

    private String slug(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return normalized.isBlank() ? "unknown" : normalized.substring(0, Math.min(80, normalized.length()));
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static class UnitAccumulator {
        private final String strategy;
        private final String module;
        private final String authorKey;
        private final int maxRegions;
        private final int maxFiles;
        private final int maxHunkChars;
        private final int maxCommits;
        private final List<Map<String, Object>> regions = new ArrayList<>();
        private final Set<String> files = new HashSet<>();
        private final Set<String> commits = new HashSet<>();
        private int hunkChars;

        private UnitAccumulator(String strategy, String module, String authorKey, int maxRegions, int maxFiles, int maxHunkChars, int maxCommits) {
            this.strategy = strategy;
            this.module = module;
            this.authorKey = authorKey;
            this.maxRegions = maxRegions;
            this.maxFiles = maxFiles;
            this.maxHunkChars = maxHunkChars;
            this.maxCommits = maxCommits;
        }

        private boolean isEmpty() {
            return regions.isEmpty();
        }

        private boolean canAccept(Map<String, Object> region) {
            Set<String> nextFiles = new HashSet<>(files);
            nextFiles.add(Objects.toString(region.get("file"), ""));
            Set<String> nextCommits = new HashSet<>(commits);
            nextCommits.add(Objects.toString(region.get("commit"), ""));
            int nextHunkChars = hunkChars + Objects.toString(region.get("hunk"), "").length();
            return regions.size() + 1 <= maxRegions
                    && nextFiles.size() <= maxFiles
                    && nextCommits.size() <= maxCommits
                    && nextHunkChars <= maxHunkChars;
        }

        private void add(Map<String, Object> region) {
            regions.add(region);
            files.add(Objects.toString(region.get("file"), ""));
            commits.add(Objects.toString(region.get("commit"), ""));
            hunkChars += Objects.toString(region.get("hunk"), "").length();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
