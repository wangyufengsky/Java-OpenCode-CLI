package com.sonnet.wyf.gitreport.workflow.weekly;

import com.sonnet.wyf.gitreport.util.JsonMaps;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class WeeklyReviewUnitGrouper {
    private final WeeklyModuleKeyResolver moduleKeyResolver = new WeeklyModuleKeyResolver();

    List<Map<String, Object>> buildReviewBatches(WeeklyEngineeringReportProperties properties, Path out, Map<String, Object> weeklyGit) {
        Map<String, List<Map<String, Object>>> byGroup = new LinkedHashMap<>();
        for (Map<String, Object> author : JsonMaps.listOfMaps(weeklyGit.get("authors"))) {
            for (Map<String, Object> region : JsonMaps.listOfMaps(author.get("changed_regions"))) {
                String file = JsonMaps.string(region.get("file"));
                if (!file.isBlank()) {
                    String module = moduleKeyResolver.moduleKey(file);
                    String authorKey = JsonMaps.string(region.get("author_key"));
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
                    .sorted(Comparator.comparing(row -> JsonMaps.string(row.get("commit")) + ":" + JsonMaps.string(row.get("file")) + ":" + JsonMaps.number(row.get("line_start"))))
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

    private String slug(String value) {
        String normalized = value.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return normalized.isBlank() ? "unknown" : normalized.substring(0, Math.min(80, normalized.length()));
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
}
