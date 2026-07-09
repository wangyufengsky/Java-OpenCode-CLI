package com.sonnet.wyf.gitreport.preparation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GitDiffParser {
    private static final Logger log = LoggerFactory.getLogger(GitDiffParser.class);
    private static final Pattern HUNK_HEADER = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    private final CommandExecutor commandExecutor;
    private final CommentLineCounter commentLineCounter;

    GitDiffParser(CommandExecutor commandExecutor, CommentLineCounter commentLineCounter) {
        this.commandExecutor = commandExecutor;
        this.commentLineCounter = commentLineCounter;
    }

    List<Map<String, Object>> parseNumstat(Path repo, String commitHash, FileScopeFilter filter) throws Exception {
        long startedNanos = System.nanoTime();
        log.info("Reading git contribution diff: phase=numstat, commit={}", shortHash(commitHash));
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
        log.info("Read git contribution diff: phase=numstat, commit={}, rows={}, elapsedMillis={}",
                shortHash(commitHash),
                rows.size(),
                elapsedMillis(startedNanos));
        return rows;
    }

    List<Map<String, Object>> parseChangedRegions(Path repo, String commitHash, FileScopeFilter filter, int maxHunkLines) throws Exception {
        long startedNanos = System.nanoTime();
        log.info("Reading git contribution diff: phase=changed_regions, commit={}", shortHash(commitHash));
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
        log.info("Read git contribution diff: phase=changed_regions, commit={}, regions={}, elapsedMillis={}",
                shortHash(commitHash),
                regions.size(),
                elapsedMillis(startedNanos));
        return regions;
    }

    Map<String, Map<String, Integer>> parseNonCommentDiff(Path repo, String commitHash, FileScopeFilter filter) throws Exception {
        long startedNanos = System.nanoTime();
        log.info("Reading git contribution diff: phase=non_comment_diff, commit={}", shortHash(commitHash));
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
        log.info("Read git contribution diff: phase=non_comment_diff, commit={}, files={}, elapsedMillis={}",
                shortHash(commitHash),
                result.size(),
                elapsedMillis(startedNanos));
        return result;
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

    private String selectFile(String path, FileScopeFilter filter, Map<String, Map<String, Integer>> result) {
        if (path == null || !filter.isCounted(path)) {
            return null;
        }
        result.computeIfAbsent(path, ignored -> new LinkedHashMap<>(Map.of("added", 0, "deleted", 0)));
        return path;
    }

    private String shortHash(String hash) {
        return hash == null ? "" : hash.substring(0, Math.min(12, hash.length()));
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
