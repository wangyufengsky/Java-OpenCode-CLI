package com.sonnet.wyf.gitreport.preparation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.GitReportProperties;
import com.sonnet.wyf.gitreport.core.GitReportConstants;
import com.sonnet.wyf.gitreport.scoring.WorkloadScoreCalculator;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitStatsCollector {
    private static final Pattern HUNK_HEADER = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    private final CommandExecutor commandExecutor;
    private final CommentLineCounter commentLineCounter;
    private final WorkloadScoreCalculator scoreCalculator;
    private final ObjectMapper objectMapper;

    public GitStatsCollector(
            CommandExecutor commandExecutor,
            CommentLineCounter commentLineCounter,
            WorkloadScoreCalculator scoreCalculator,
            ObjectMapper objectMapper
    ) {
        this.commandExecutor = commandExecutor;
        this.commentLineCounter = commentLineCounter;
        this.scoreCalculator = scoreCalculator;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> collect(GitReportProperties properties) throws Exception {
        Path repo = properties.getPaths().getRepo().toAbsolutePath().normalize();
        commandExecutor.run(repo, "git", "rev-parse", "--is-inside-work-tree");
        FileScopeFilter filter = FileScopeFilter.withUserPatterns(properties.getGit().getInclude(), properties.getGit().getExclude());
        Map<String, String> authorMap = loadAuthorMap(properties.getGit().getAuthorMap());
        List<Map<String, String>> commits = parseCommits(repo, properties.getGit().getRevision(), properties.getGit().getSince(), properties.getGit().getUntil(), properties.getGit().isIncludeMerges());
        Map<String, Map<String, Object>> authors = new LinkedHashMap<>();
        for (Map<String, String> commit : commits) {
            List<Map<String, Object>> numstatRows = parseNumstat(repo, commit.get("hash"), filter);
            if (numstatRows.isEmpty()) {
                continue;
            }
            String author = normalizeAuthor(commit.get("name"), commit.get("email"), authorMap);
            Map<String, Object> stats = authors.computeIfAbsent(author, this::emptyAuthor);
            increment(stats, "commit_count", 1);
            list(stats, "commits").add(Map.of(
                    "hash", commit.get("hash"),
                    "short_hash", commit.get("hash").substring(0, Math.min(12, commit.get("hash").length())),
                    "date", commit.get("date"),
                    "subject", commit.get("subject")
            ));
            Map<String, Map<String, Integer>> nonComment = parseNonCommentDiff(repo, commit.get("hash"), filter);
            list(stats, "changed_regions").addAll(parseChangedRegions(
                    repo,
                    commit.get("hash"),
                    filter,
                    properties.getDetailInput().getChangedRegionLines()
            ));
            for (Map<String, Object> row : numstatRows) {
                String path = row.get("path").toString();
                Map<String, Integer> nc = nonComment.getOrDefault(path, Map.of("added", 0, "deleted", 0));
                int added = ((Number) row.get("added")).intValue();
                int deleted = ((Number) row.get("deleted")).intValue();
                int ncAdded = nc.get("added");
                int ncDeleted = nc.get("deleted");
                increment(stats, "file_change_count", 1);
                increment(stats, "added", added);
                increment(stats, "deleted", deleted);
                increment(stats, "non_comment_added", ncAdded);
                increment(stats, "non_comment_deleted", ncDeleted);
                addFileStats(stats, path, added, deleted, ncAdded, ncDeleted);
            }
        }
        List<Map<String, Object>> ranked = finalizeAuthors(authors.values());
        Map<String, Object> metadata = new LinkedHashMap<>();
        Path out = properties.getPaths().getOut().toAbsolutePath().normalize();
        String generatedAt = OffsetDateTime.now().toString();
        String projectId = firstNonBlank(properties.getProject().getId(), repo.getFileName() == null ? "unknown-project" : repo.getFileName().toString());
        String projectName = firstNonBlank(properties.getProject().getName(), projectId);
        String runId = firstNonBlank(properties.getProject().getRunId(), "git-report-" + generatedAt.replaceAll("[^0-9A-Za-z]+", "-").replaceAll("^-|-$", ""));
        metadata.put("project_id", projectId);
        metadata.put("project_name", projectName);
        metadata.put("run_id", runId);
        metadata.put("repo", repo.toString());
        metadata.put("revision", properties.getGit().getRevision());
        metadata.put("since", properties.getGit().getSince().toString());
        metadata.put("until", properties.getGit().getUntil().toString());
        metadata.put("include_merges", properties.getGit().isIncludeMerges());
        metadata.put("default_include", GitReportConstants.DEFAULT_INCLUDE_PATTERNS);
        metadata.put("user_include", properties.getGit().getInclude());
        metadata.put("include", filter.includes());
        metadata.put("default_exclude", GitReportConstants.DEFAULT_EXCLUDE_PATTERNS);
        metadata.put("user_exclude", properties.getGit().getExclude());
        metadata.put("exclude", filter.excludes());
        metadata.put("author_map", properties.getGit().getAuthorMap() == null ? null : properties.getGit().getAuthorMap().toAbsolutePath().normalize().toString());
        metadata.put("generated_at", generatedAt);
        metadata.put("final_report", out.resolve("code-contribution-report.md").toString());
        metadata.put("index_inputs", out.resolve("index_inputs.json").toString());
        metadata.put("details_dir", out.resolve("details").toString());
        metadata.put("reports_dir", out.resolve("reports").toString());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metadata", metadata);
        result.put("totals", totals(ranked));
        result.put("authors", ranked);
        return result;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private List<Map<String, String>> parseCommits(Path repo, String revision, LocalDate since, LocalDate until, boolean includeMerges) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "log", revision));
        if (!includeMerges) {
            command.add("--no-merges");
        }
        command.add("--since=" + since + " 00:00:00");
        command.add("--until=" + until + " 23:59:59");
        command.add("--date=iso-strict");
        command.add("--format=%H%x1f%an%x1f%ae%x1f%ad%x1f%s");
        String output = commandExecutor.run(repo, command);
        List<Map<String, String>> commits = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String[] parts = line.split("\\u001f", 5);
            if (parts.length == 5) {
                commits.add(Map.of("hash", parts[0], "name", parts[1], "email", parts[2], "date", parts[3], "subject", parts[4]));
            }
        }
        return commits;
    }

    private List<Map<String, Object>> parseNumstat(Path repo, String commitHash, FileScopeFilter filter) throws Exception {
        String output = commandExecutor.run(repo, "git", "show", "--format=", "--numstat", "--find-renames", commitHash);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String[] parts = line.split("\\t");
            if (parts.length < 3 || "-".equals(parts[0]) || "-".equals(parts[1])) {
                continue;
            }
            String path = normalizeNumstatPath(parts[parts.length - 1]);
            if (filter.isCounted(path)) {
                rows.add(Map.of("added", Integer.parseInt(parts[0]), "deleted", Integer.parseInt(parts[1]), "path", path));
            }
        }
        return rows;
    }

    private String normalizeNumstatPath(String path) {
        if (path == null || !path.contains(" => ")) {
            return path;
        }
        int openBrace = path.indexOf('{');
        int closeBrace = path.indexOf('}', openBrace + 1);
        if (openBrace >= 0 && closeBrace > openBrace) {
            String prefix = path.substring(0, openBrace);
            String suffix = path.substring(closeBrace + 1);
            String[] renameParts = path.substring(openBrace + 1, closeBrace).split(" => ", 2);
            if (renameParts.length == 2) {
                return prefix + renameParts[1] + suffix;
            }
        }
        String[] renameParts = path.split(" => ", 2);
        return renameParts.length == 2 ? renameParts[1] : path;
    }

    private List<Map<String, Object>> parseChangedRegions(Path repo, String commitHash, FileScopeFilter filter, int maxHunkLines) throws Exception {
        String output = commandExecutor.run(repo, "git", "show", "--format=", "--unified=0", "--no-ext-diff", "--find-renames", commitHash);
        List<Map<String, Object>> regions = new ArrayList<>();
        String currentFile = null;
        String oldFile = null;
        String changeType = "modified";
        Map<String, Object> currentRegion = null;
        List<String> currentHunk = null;
        int currentChangedLines = 0;
        boolean currentTruncated = false;
        int hunkLineLimit = Math.max(1, maxHunkLines);
        for (String line : output.split("\\R")) {
            if (line.startsWith("diff ")) {
                finishRegion(currentRegion, currentHunk, currentTruncated);
                currentRegion = null;
                currentHunk = null;
                currentFile = null;
                oldFile = null;
                changeType = "modified";
                continue;
            }
            if (line.startsWith("--- a/")) {
                oldFile = line.substring(6);
                continue;
            }
            if (line.startsWith("--- /dev/null")) {
                oldFile = null;
                changeType = "added";
                continue;
            }
            if (line.startsWith("+++ b/")) {
                String file = line.substring(6);
                currentFile = filter.isCounted(file) ? file : null;
                continue;
            }
            if (line.startsWith("+++ /dev/null")) {
                changeType = "deleted";
                currentFile = oldFile != null && filter.isCounted(oldFile) ? oldFile : null;
                continue;
            }
            Matcher matcher = HUNK_HEADER.matcher(line);
            if (matcher.matches()) {
                finishRegion(currentRegion, currentHunk, currentTruncated);
                currentRegion = null;
                currentHunk = null;
                currentChangedLines = 0;
                currentTruncated = false;
                if (currentFile != null) {
                    currentRegion = changedRegion(commitHash, currentFile, changeType, matcher);
                    currentHunk = new ArrayList<>();
                    currentHunk.add(line);
                    regions.add(currentRegion);
                }
                continue;
            }
            if (currentRegion == null || currentHunk == null || line.startsWith("\\ No newline")) {
                continue;
            }
            if (line.startsWith("+") || line.startsWith("-")) {
                if (currentChangedLines < hunkLineLimit) {
                    currentHunk.add(line);
                } else {
                    currentTruncated = true;
                }
                currentChangedLines++;
            }
        }
        finishRegion(currentRegion, currentHunk, currentTruncated);
        return regions;
    }

    private Map<String, Object> changedRegion(String commitHash, String file, String changeType, Matcher header) {
        int oldStart = Integer.parseInt(header.group(1));
        int oldCount = countValue(header.group(2));
        int newStart = Integer.parseInt(header.group(3));
        int newCount = countValue(header.group(4));
        Map<String, Object> region = new LinkedHashMap<>();
        region.put("commit", commitHash);
        region.put("short_hash", commitHash.substring(0, Math.min(12, commitHash.length())));
        region.put("file", file);
        region.put("change_type", changeType);
        region.put("line_start", newCount == 0 ? oldStart : newStart);
        region.put("line_end", newCount == 0 ? oldStart + Math.max(0, oldCount - 1) : newStart + Math.max(0, newCount - 1));
        region.put("old_line_start", oldStart);
        region.put("old_line_end", oldStart + Math.max(0, oldCount - 1));
        return region;
    }

    private void finishRegion(Map<String, Object> region, List<String> hunk, boolean truncated) {
        if (region != null && hunk != null) {
            region.put("hunk", String.join("\n", hunk));
            region.put("truncated", truncated);
        }
    }

    private int countValue(String value) {
        return value == null || value.isBlank() ? 1 : Integer.parseInt(value);
    }

    private Map<String, Map<String, Integer>> parseNonCommentDiff(Path repo, String commitHash, FileScopeFilter filter) throws Exception {
        String output = commandExecutor.run(repo, "git", "show", "--format=", "--unified=0", "--no-ext-diff", "--find-renames", commitHash);
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        Map<String, CommentLineCounter.CommentState> states = new LinkedHashMap<>();
        String currentFile = null;
        String oldFile = null;
        for (String line : output.split("\\R")) {
            if (line.startsWith("diff ")) {
                currentFile = null;
                oldFile = null;
                continue;
            }
            if (line.startsWith("--- a/")) {
                oldFile = line.substring(6);
                continue;
            }
            if (line.startsWith("--- /dev/null")) {
                oldFile = null;
                continue;
            }
            if (line.startsWith("+++ b/")) {
                currentFile = selectFile(line.substring(6), filter, result);
                oldFile = null;
                continue;
            }
            if (line.startsWith("+++ /dev/null")) {
                currentFile = selectFile(oldFile, filter, result);
                oldFile = null;
                continue;
            }
            if (line.startsWith("--- ") || line.startsWith("@@") || currentFile == null) {
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                if (commentLineCounter.isCountableCodeLine(currentFile, line.substring(1), states.computeIfAbsent(currentFile + "+", ignored -> new CommentLineCounter.CommentState()))) {
                    result.get(currentFile).put("added", result.get(currentFile).get("added") + 1);
                }
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                if (commentLineCounter.isCountableCodeLine(currentFile, line.substring(1), states.computeIfAbsent(currentFile + "-", ignored -> new CommentLineCounter.CommentState()))) {
                    result.get(currentFile).put("deleted", result.get(currentFile).get("deleted") + 1);
                }
            }
        }
        return result;
    }

    private String selectFile(String path, FileScopeFilter filter, Map<String, Map<String, Integer>> result) {
        if (path == null || !filter.isCounted(path)) {
            return null;
        }
        result.computeIfAbsent(path, ignored -> new LinkedHashMap<>(Map.of("added", 0, "deleted", 0)));
        return path;
    }

    private Map<String, String> loadAuthorMap(Path path) throws IOException {
        if (path == null) {
            return Map.of();
        }
        Map<String, Object> data = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
        Map<String, String> mapping = new LinkedHashMap<>();
        Object aliases = data.get("aliases");
        if (aliases instanceof Map<?, ?> aliasMap) {
            for (Map.Entry<?, ?> entry : aliasMap.entrySet()) {
                String canonical = entry.getKey().toString();
                mapping.put(canonical.toLowerCase(Locale.ROOT), canonical);
                if (entry.getValue() instanceof List<?> values) {
                    values.forEach(value -> mapping.put(value.toString().toLowerCase(Locale.ROOT), canonical));
                }
            }
        } else {
            data.forEach((source, target) -> mapping.put(source.toLowerCase(Locale.ROOT), target.toString()));
        }
        return mapping;
    }

    private String normalizeAuthor(String name, String email, Map<String, String> authorMap) {
        for (String candidate : List.of((name + " <" + email + ">").toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT), email.toLowerCase(Locale.ROOT))) {
            if (authorMap.containsKey(candidate)) {
                return authorMap.get(candidate);
            }
        }
        return name + " <" + email + ">";
    }

    private Map<String, Object> emptyAuthor(String author) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("author", author);
        for (String key : List.of("commit_count", "file_change_count", "added", "deleted", "net", "non_comment_added", "non_comment_deleted", "non_comment_net", "non_comment_churn")) {
            map.put(key, 0);
        }
        map.put("workload_score", 0.0);
        map.put("commits", new ArrayList<>());
        map.put("changed_regions", new ArrayList<>());
        map.put("files", new LinkedHashMap<String, Map<String, Object>>());
        map.put("extensions", new LinkedHashMap<String, Map<String, Object>>());
        return map;
    }

    private void addFileStats(Map<String, Object> target, String path, int added, int deleted, int ncAdded, int ncDeleted) {
        Map<String, Map<String, Object>> files = map(target, "files");
        Map<String, Object> file = files.computeIfAbsent(path, ignored -> zeroStats());
        addStats(file, added, deleted, ncAdded, ncDeleted);
        Map<String, Map<String, Object>> extensions = map(target, "extensions");
        String ext = extension(path);
        Map<String, Object> extension = extensions.computeIfAbsent(ext, ignored -> zeroStats());
        addStats(extension, added, deleted, ncAdded, ncDeleted);
    }

    private Map<String, Object> zeroStats() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("file_change_count", 0);
        map.put("added", 0);
        map.put("deleted", 0);
        map.put("non_comment_added", 0);
        map.put("non_comment_deleted", 0);
        return map;
    }

    private void addStats(Map<String, Object> stats, int added, int deleted, int ncAdded, int ncDeleted) {
        increment(stats, "file_change_count", 1);
        increment(stats, "added", added);
        increment(stats, "deleted", deleted);
        increment(stats, "non_comment_added", ncAdded);
        increment(stats, "non_comment_deleted", ncDeleted);
    }

    private List<Map<String, Object>> finalizeAuthors(Iterable<Map<String, Object>> authors) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> stats : authors) {
            Map<String, Map<String, Object>> files = map(stats, "files");
            List<String> uniqueFiles = files.keySet().stream().sorted().toList();
            stats.put("unique_files", uniqueFiles);
            stats.put("unique_file_count", uniqueFiles.size());
            stats.put("net", intValue(stats, "added") - intValue(stats, "deleted"));
            stats.put("non_comment_net", intValue(stats, "non_comment_added") - intValue(stats, "non_comment_deleted"));
            stats.put("non_comment_churn", intValue(stats, "non_comment_added") + intValue(stats, "non_comment_deleted"));
            stats.put("workload_score", scoreCalculator.calculate(stats));
            stats.put("base_workload_score", stats.get("workload_score"));
            stats.put("quality_adjustment_percent", 0);
            stats.put("top_files", files.entrySet().stream()
                    .map(entry -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("path", entry.getKey());
                        row.putAll(entry.getValue());
                        row.put("non_comment_churn", intValue(row, "non_comment_added") + intValue(row, "non_comment_deleted"));
                        return row;
                    })
                    .sorted(Comparator.<Map<String, Object>>comparingInt(row -> intValue(row, "non_comment_churn"))
                            .thenComparingInt(row -> intValue(row, "file_change_count"))
                            .thenComparing(row -> row.get("path").toString())
                            .reversed())
                    .limit(20)
                    .toList());
            stats.put("extensions", map(stats, "extensions"));
            result.add(stats);
        }
        result.sort(Comparator.<Map<String, Object>>comparingDouble(row -> ((Number) row.get("workload_score")).doubleValue())
                .thenComparingInt(row -> intValue(row, "non_comment_churn"))
                .thenComparingInt(row -> intValue(row, "commit_count"))
                .reversed());
        for (int index = 0; index < result.size(); index++) {
            result.get(index).put("rank", index + 1);
        }
        return result;
    }

    private Map<String, Object> totals(List<Map<String, Object>> ranked) {
        Map<String, Object> totals = new LinkedHashMap<>();
        for (String key : List.of("commit_count", "file_change_count", "added", "deleted", "non_comment_added", "non_comment_deleted")) {
            totals.put(key, ranked.stream().mapToInt(row -> intValue(row, key)).sum());
        }
        Set<String> unique = new LinkedHashSet<>();
        ranked.forEach(row -> unique.addAll(list(row, "unique_files").stream().map(Object::toString).toList()));
        totals.put("unique_file_count", unique.size());
        totals.put("net", intValue(totals, "added") - intValue(totals, "deleted"));
        totals.put("non_comment_net", intValue(totals, "non_comment_added") - intValue(totals, "non_comment_deleted"));
        totals.put("non_comment_churn", intValue(totals, "non_comment_added") + intValue(totals, "non_comment_deleted"));
        return totals;
    }

    private void increment(Map<String, Object> map, String key, int amount) {
        map.put(key, intValue(map, key) + amount);
    }

    private int intValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Map<String, Object> map, String key) {
        return (List<Object>) map.computeIfAbsent(key, ignored -> new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> map(Map<String, Object> source, String key) {
        return (Map<String, Map<String, Object>>) source.computeIfAbsent(key, ignored -> new LinkedHashMap<String, Map<String, Object>>());
    }

    private String extension(String path) {
        String name = path.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot == -1 ? "[no-ext]" : name.substring(dot);
    }
}
