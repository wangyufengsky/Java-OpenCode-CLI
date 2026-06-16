package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class GitReportConstants {
    static final String REPORT_MARKER = "<!-- CODE_CONTRIBUTION_REPORT_CONTENT -->";
    static final String AUTHOR_REPORT_MARKER = "<!-- AUTHOR_CODE_CONTRIBUTION_REPORT_CONTENT -->";
    static final String QUALITY_SUMMARY_MARKER = "\"__QUALITY_SUMMARY_JSON_CONTENT__\"";
    static final List<String> DEFAULT_INCLUDE_PATTERNS = List.of(
            "*.java", "*.kt", "*.kts", "*.scala", "*.groovy", "*.gradle", "*.py", "*.rb", "*.sh", "*.bash",
            "*.zsh", "*.ps1", "*.bat", "*.cmd", "*.js", "*.jsx", "*.ts", "*.tsx", "*.mjs", "*.cjs",
            "*.vue", "*.svelte", "*.html", "*.htm", "*.xhtml", "*.css", "*.scss", "*.sass", "*.less",
            "*.jsp", "*.jspx", "*.xml", "*.yml", "*.yaml", "*.json", "*.toml", "*.ini", "*.conf",
            "*.properties", "*.sql", "*.c", "*.cc", "*.cpp", "*.cxx", "*.h", "*.hpp", "*.cs", "*.go",
            "*.rs", "*.swift", "*.php", "*.lua", "*.r", "*.pl", "*.proto", "*.graphql", "*.gql",
            "Dockerfile", "Dockerfile.*", "Makefile", "makefile", "GNUmakefile", "Jenkinsfile", "Jenkinsfile.*",
            ".gitignore", ".gitattributes", ".dockerignore", ".editorconfig"
    );
    static final List<String> DEFAULT_EXCLUDE_PATTERNS = List.of(
            "*.md", "*.markdown", "*.mdown", "*.mkd", "*.doc", "*.docx", "*.xls", "*.xlsx", "*.xlsm",
            "*.ppt", "*.pptx", "*.pdf", "*.rtf", "*.txt", "*.csv", "*.png", "*.jpg", "*.jpeg", "*.gif",
            "*.bmp", "*.webp", "*.ico", "*.zip", "*.tar", "*.gz", "*.7z", "*.rar"
    );

    private GitReportConstants() {
    }
}

class CommandExecutor {
    String run(Path cwd, String... command) throws IOException, InterruptedException {
        return run(cwd, Arrays.asList(command));
    }

    String run(Path cwd, List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed with " + exitCode + "\n" + output);
        }
        return output;
    }
}

class FileScopeFilter {
    private final List<String> includes;
    private final List<String> excludes;

    private FileScopeFilter(List<String> includes, List<String> excludes) {
        this.includes = includes;
        this.excludes = excludes;
    }

    static FileScopeFilter withUserPatterns(List<String> userIncludes, List<String> userExcludes) {
        return new FileScopeFilter(merge(GitReportConstants.DEFAULT_INCLUDE_PATTERNS, userIncludes), merge(GitReportConstants.DEFAULT_EXCLUDE_PATTERNS, userExcludes));
    }

    List<String> includes() {
        return includes;
    }

    List<String> excludes() {
        return excludes;
    }

    boolean isCounted(String path) {
        return matchesAny(path, includes) && !matchesAny(path, excludes);
    }

    private static List<String> merge(List<String> defaults, List<String> user) {
        LinkedHashSet<String> values = new LinkedHashSet<>(defaults);
        if (user != null) {
            values.addAll(user);
        }
        return new ArrayList<>(values);
    }

    private static boolean matchesAny(String path, List<String> patterns) {
        String normalized = path.replace('\\', '/');
        String basename = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        for (String pattern : patterns) {
            Pattern regex = Pattern.compile(globToRegex(pattern.replace('\\', '/')));
            if (regex.matcher(normalized).matches() || regex.matcher(basename).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (".()[]{}+$^|\\".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return regex.append('$').toString();
    }
}

class CommentLineCounter {
    private static final Set<String> BLOCK_COMMENT_EXTS = Set.of(".java", ".kt", ".kts", ".scala", ".groovy", ".js", ".jsx", ".ts", ".tsx", ".c", ".cc", ".cpp", ".cxx", ".h", ".hpp", ".cs", ".go", ".rs", ".swift", ".php", ".css", ".scss", ".sass", ".less", ".proto", ".sql");
    private static final Set<String> HASH_COMMENT_EXTS = Set.of(".py", ".rb", ".sh", ".bash", ".zsh", ".ps1", ".yaml", ".yml", ".toml", ".ini", ".conf", ".r", ".pl", ".graphql", ".gql");
    private static final Set<String> XML_COMMENT_EXTS = Set.of(".xml", ".html", ".htm", ".xhtml", ".vue", ".jsp", ".jspx");
    private static final Set<String> PROPERTIES_EXTS = Set.of(".properties");

    boolean isCountableCodeLine(String path, String line, CommentState state) {
        String suffix = suffix(path);
        String text = line.replaceAll("[\\r\\n]+$", "");
        if (text.trim().isEmpty()) {
            return false;
        }
        if (XML_COMMENT_EXTS.contains(suffix)) {
            text = removeBlockComments(text, state, "<!--", "-->");
        } else if (BLOCK_COMMENT_EXTS.contains(suffix)) {
            text = removeBlockComments(text, state, "/*", "*/");
            text = removeInlineComment(text, suffix.equals(".sql") ? "--" : "//");
        } else if (HASH_COMMENT_EXTS.contains(suffix)) {
            text = removeInlineComment(text, "#");
        } else if (PROPERTIES_EXTS.contains(suffix)) {
            String stripped = text.stripLeading();
            if (stripped.startsWith("#") || stripped.startsWith("!")) {
                return false;
            }
        }
        return !text.trim().isEmpty();
    }

    private String removeBlockComments(String line, CommentState state, String start, String end) {
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (index < line.length()) {
            if (state.inBlock) {
                int closeAt = line.indexOf(state.blockEnd, index);
                if (closeAt == -1) {
                    return result.toString();
                }
                state.inBlock = false;
                index = closeAt + state.blockEnd.length();
                continue;
            }
            int openAt = line.indexOf(start, index);
            if (openAt == -1) {
                result.append(line.substring(index));
                break;
            }
            result.append(line, index, openAt);
            int closeAt = line.indexOf(end, openAt + start.length());
            if (closeAt == -1) {
                state.inBlock = true;
                state.blockEnd = end;
                break;
            }
            index = closeAt + end.length();
        }
        return result.toString();
    }

    private String removeInlineComment(String line, String marker) {
        String scan = stripStringLiterals(line);
        int at = scan.indexOf(marker);
        return at == -1 ? line : line.substring(0, at);
    }

    private String stripStringLiterals(String line) {
        return line.replaceAll("(['\"])(?:\\\\.|(?!\\1).)*\\1", "\"\"");
    }

    private String suffix(String path) {
        String name = path.toLowerCase(Locale.ROOT);
        int at = name.lastIndexOf('.');
        return at == -1 ? "" : name.substring(at);
    }

    static class CommentState {
        private boolean inBlock;
        private String blockEnd = "*/";
    }
}

class WorkloadScoreCalculator {
    double calculate(Map<String, Object> author) {
        double score = number(author, "commit_count") * 3.0
                + number(author, "file_change_count") * 1.5
                + number(author, "non_comment_added") * 1.2
                + number(author, "non_comment_deleted")
                + Math.abs(number(author, "non_comment_net")) * 0.2;
        return round2(score);
    }

    double adjusted(double baseScore, double qualityAdjustmentPercent) {
        double bounded = Math.clamp(qualityAdjustmentPercent, -30.0, 30.0);
        return round2(baseScore * (1 + bounded / 100.0));
    }

    private double number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

class QualityScoreCalculator {
    private static final Map<String, Double> DIMENSION_LIMITS = Map.of("code_standard", 8.0, "maintainability", 8.0, "risk_control", 8.0, "reviewability", 6.0);
    private static final Map<String, Map<String, Double>> SCORE_TABLE = Map.of(
            "negative", Map.of("low", -2.0, "medium", -5.0, "high", -8.0),
            "positive", Map.of("low", 1.0, "medium", 3.0, "high", 5.0)
    );

    Map<String, Object> calculate(Map<String, Object> qualitySummary) {
        Map<String, Double> componentsByDimension = new LinkedHashMap<>();
        DIMENSION_LIMITS.keySet().forEach(dimension -> componentsByDimension.put(dimension, 0.0));
        List<Map<String, Object>> scoredFindings = new ArrayList<>();
        List<String> scoringNotes = new ArrayList<>();
        for (Object item : listValue(qualitySummary.get("findings"))) {
            if (item instanceof Map<?, ?> finding) {
                scoreFinding(castMap(finding), componentsByDimension, scoredFindings, scoringNotes);
            }
        }
        for (Object item : listValue(qualitySummary.get("code_snippets"))) {
            if (item instanceof Map<?, ?> snippet && !hasNegativeFindingForSnippet(castMap(snippet), scoredFindings)) {
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("dimension", snippet.get("dimension"));
                fallback.put("polarity", "negative");
                fallback.put("severity", Objects.toString(snippet.get("severity"), "medium"));
                fallback.put("rule_id", "low_quality_code_snippet");
                fallback.put("file", snippet.get("file"));
                fallback.put("line_start", snippet.get("line_start"));
                fallback.put("line_end", snippet.get("line_end"));
                fallback.put("evidence", Objects.toString(snippet.get("reason"), "低质量代码片段"));
                scoreFinding(fallback, componentsByDimension, scoredFindings, scoringNotes);
                scoringNotes.add("code_snippets item scored through low_quality_code_snippet fallback");
            }
        }
        double qualityAdjustment = componentsByDimension.values().stream().mapToDouble(Double::doubleValue).sum();
        qualityAdjustment = Math.max(-30.0, Math.min(30.0, qualityAdjustment));
        List<Map<String, Object>> components = componentsByDimension.entrySet().stream()
                .map(entry -> Map.<String, Object>of("dimension", entry.getKey(), "score", entry.getValue()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quality_adjustment_percent", qualityAdjustment);
        result.put("components", components);
        result.put("components_by_dimension", componentsByDimension);
        result.put("scored_findings", scoredFindings);
        result.put("scoring_notes", scoringNotes);
        return result;
    }

    private void scoreFinding(Map<String, Object> finding, Map<String, Double> componentsByDimension, List<Map<String, Object>> scoredFindings, List<String> scoringNotes) {
        String dimension = Objects.toString(finding.get("dimension"), "");
        String polarity = Objects.toString(finding.get("polarity"), "").toLowerCase(Locale.ROOT);
        String severity = Objects.toString(finding.get("severity"), "").toLowerCase(Locale.ROOT);
        if (!DIMENSION_LIMITS.containsKey(dimension) || !SCORE_TABLE.containsKey(polarity) || !SCORE_TABLE.get(polarity).containsKey(severity)) {
            scoringNotes.add("ignored invalid finding");
            return;
        }
        double next = componentsByDimension.get(dimension) + SCORE_TABLE.get(polarity).get(severity);
        double limit = DIMENSION_LIMITS.get(dimension);
        componentsByDimension.put(dimension, Math.max(-limit, Math.min(limit, next)));
        Map<String, Object> scored = new LinkedHashMap<>();
        scored.put("dimension", dimension);
        scored.put("polarity", polarity);
        scored.put("severity", severity);
        scored.put("score", SCORE_TABLE.get(polarity).get(severity));
        scored.put("rule_id", Objects.toString(finding.get("rule_id"), ""));
        scored.put("file", Objects.toString(finding.get("file"), ""));
        scoredFindings.add(scored);
    }

    private boolean hasNegativeFindingForSnippet(Map<String, Object> snippet, List<Map<String, Object>> scoredFindings) {
        String dimension = Objects.toString(snippet.get("dimension"), "");
        String file = Objects.toString(snippet.get("file"), "");
        return scoredFindings.stream().anyMatch(finding -> "negative".equals(finding.get("polarity"))
                && dimension.equals(finding.get("dimension"))
                && (file.isBlank() || file.equals(finding.get("file"))));
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}

class GitStatsCollector {
    private final CommandExecutor commandExecutor;
    private final CommentLineCounter commentLineCounter;
    private final WorkloadScoreCalculator scoreCalculator = new WorkloadScoreCalculator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    GitStatsCollector(CommandExecutor commandExecutor, CommentLineCounter commentLineCounter) {
        this.commandExecutor = commandExecutor;
        this.commentLineCounter = commentLineCounter;
    }

    Map<String, Object> collect(GitReportProperties properties) throws Exception {
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
        metadata.put("report_marker", GitReportConstants.REPORT_MARKER);
        metadata.put("author_report_marker", GitReportConstants.AUTHOR_REPORT_MARKER);
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
            String path = parts[parts.length - 1];
            if (filter.isCounted(path)) {
                rows.add(Map.of("added", Integer.parseInt(parts[0]), "deleted", Integer.parseInt(parts[1]), "path", path));
            }
        }
        return rows;
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

class ReportPreparationWriter {
    private static final Logger log = LoggerFactory.getLogger(ReportPreparationWriter.class);

    private final ObjectMapper objectMapper;

    ReportPreparationWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(Path out, Map<String, Object> data) throws IOException {
        Files.createDirectories(out);
        attachOutputPaths(out.toAbsolutePath().normalize(), data);
        log.info("Writing git report preparation files to {}", out.toAbsolutePath().normalize());
        writeAuthorOutputs(out, data);
        writeJson(out.resolve("details.json"), data);
        writeJson(out.resolve("summary.json"), buildSummary(data));
        writeJson(out.resolve("index_inputs.json"), buildIndexInputs(data));
        Files.writeString(out.resolve("index.md"), "# 代码提交量统计数据预览\n\n请查看 `summary.json` 和 `index_inputs.json`。\n");
        Files.writeString(out.resolve("code-contribution-report.md"), GitReportConstants.REPORT_MARKER + "\n");
        log.info("Prepared summary.json, index_inputs.json, details.json, final report marker, and {} author task(s)", authors(data).size());
    }

    private void attachOutputPaths(Path out, Map<String, Object> data) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> author : authors(data)) {
            String authorKey = makeAuthorKey(index++, author.get("author").toString());
            Path detailJson = out.resolve("details").resolve(authorKey + ".json");
            Path reportMd = out.resolve("reports").resolve(authorKey).resolve("person-report.md");
            Path qualitySummaryJson = out.resolve("reports").resolve(authorKey).resolve("quality-summary.json");
            String relativePath = "reports/" + authorKey + "/person-report.md";
            String markdownLink = "[person-report.md](" + relativePath + ")";
            author.put("author_key", authorKey);
            author.put("detail_json", detailJson.toString());
            author.put("person_report_md", reportMd.toString());
            author.put("quality_summary_json", qualitySummaryJson.toString());
            author.put("person_report_relative_path", relativePath);
            author.put("person_report_markdown_link", markdownLink);
            author.put("person_report_marker", GitReportConstants.AUTHOR_REPORT_MARKER);
            List<Map<String, Object>> worklist = buildExecutionWorklist(detailJson, reportMd, qualitySummaryJson);
            author.put("execution_worklist", worklist);
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("rank", author.get("rank"));
            task.put("author", author.get("author"));
            task.put("author_key", authorKey);
            task.put("detail_json", detailJson.toString());
            task.put("report_md", reportMd.toString());
            task.put("quality_summary_json", qualitySummaryJson.toString());
            task.put("report_relative_path", relativePath);
            task.put("report_markdown_link", markdownLink);
            task.put("report_marker", GitReportConstants.AUTHOR_REPORT_MARKER);
            task.put("quality_summary_marker", GitReportConstants.QUALITY_SUMMARY_MARKER);
            task.put("execution_worklist", worklist);
            tasks.add(task);
        }
        data.put("tasks", tasks);
    }

    private void writeAuthorOutputs(Path out, Map<String, Object> data) throws IOException {
        for (Map<String, Object> author : authors(data)) {
            Path detailPath = Path.of(author.get("detail_json").toString());
            Path reportPath = Path.of(author.get("person_report_md").toString());
            Path qualityPath = Path.of(author.get("quality_summary_json").toString());
            Files.createDirectories(detailPath.getParent());
            Files.createDirectories(reportPath.getParent());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("metadata", data.get("metadata"));
            detail.put("author_key", author.get("author_key"));
            detail.put("rank", author.get("rank"));
            detail.put("author", author.get("author"));
            detail.put("summary", summaryFields(author));
            detail.put("top_files", author.get("top_files"));
            detail.put("extensions", author.get("extensions"));
            detail.put("commits", author.get("commits"));
            detail.put("files", author.get("files"));
            detail.put("execution_worklist", author.get("execution_worklist"));
            detail.put("output", Map.of(
                    "person_report_md", author.get("person_report_md"),
                    "quality_summary_json", author.get("quality_summary_json"),
                    "person_report_relative_path", author.get("person_report_relative_path"),
                    "person_report_markdown_link", author.get("person_report_markdown_link"),
                    "report_marker", GitReportConstants.AUTHOR_REPORT_MARKER,
                    "quality_summary_marker", GitReportConstants.QUALITY_SUMMARY_MARKER
            ));
            writeJson(detailPath, detail);
            Files.writeString(reportPath, GitReportConstants.AUTHOR_REPORT_MARKER + "\n");
            Files.writeString(qualityPath, GitReportConstants.QUALITY_SUMMARY_MARKER + "\n");
        }
    }

    private Map<String, Object> buildSummary(Map<String, Object> data) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("metadata", data.get("metadata"));
        summary.put("totals", data.get("totals"));
        summary.put("ranking", authors(data).stream().map(this::rankingFields).toList());
        summary.put("tasks", data.get("tasks"));
        return summary;
    }

    private Map<String, Object> buildIndexInputs(Map<String, Object> data) {
        Map<String, Object> indexInputs = new LinkedHashMap<>();
        indexInputs.put("metadata", data.get("metadata"));
        indexInputs.put("totals", data.get("totals"));
        indexInputs.put("final_report", ((Map<?, ?>) data.get("metadata")).get("final_report"));
        indexInputs.put("final_report_marker", GitReportConstants.REPORT_MARKER);
        indexInputs.put("author_report_marker", GitReportConstants.AUTHOR_REPORT_MARKER);
        indexInputs.put("quality_summary_marker", GitReportConstants.QUALITY_SUMMARY_MARKER);
        indexInputs.put("tasks", data.get("tasks"));
        return indexInputs;
    }

    private Map<String, Object> summaryFields(Map<String, Object> author) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("commit_count", "file_change_count", "unique_file_count", "added", "deleted", "net", "non_comment_added", "non_comment_deleted", "non_comment_net", "non_comment_churn", "base_workload_score", "quality_adjustment_percent", "workload_score")) {
            result.put(key, author.get(key));
        }
        return result;
    }

    private Map<String, Object> rankingFields(Map<String, Object> author) {
        Map<String, Object> result = summaryFields(author);
        result.put("rank", author.get("rank"));
        result.put("author", author.get("author"));
        result.put("author_key", author.get("author_key"));
        result.put("detail_json", author.get("detail_json"));
        result.put("person_report_md", author.get("person_report_md"));
        result.put("quality_summary_json", author.get("quality_summary_json"));
        result.put("person_report_relative_path", author.get("person_report_relative_path"));
        result.put("person_report_markdown_link", author.get("person_report_markdown_link"));
        result.put("person_report_marker", author.get("person_report_marker"));
        return result;
    }

    private List<Map<String, Object>> buildExecutionWorklist(Path detailJson, Path reportMd, Path qualitySummaryJson) {
        return List.of(
                step(1, "read_detail_json", detailJson),
                step(2, "read_embedded_person_report_template", null),
                step(3, "inspect_top_files", null),
                step(4, "collect_call_evidence", null),
                step(5, "draft_person_report", reportMd),
                stepWithMarker(6, "write_person_report", reportMd, GitReportConstants.AUTHOR_REPORT_MARKER),
                step(7, "draft_quality_summary", qualitySummaryJson),
                stepWithMarker(8, "write_quality_summary", qualitySummaryJson, GitReportConstants.QUALITY_SUMMARY_MARKER),
                Map.of("step", 9, "action", "verify_outputs", "required", true, "required_paths", List.of(reportMd.toString(), qualitySummaryJson.toString()), "status", "pending"),
                Map.of("step", 10, "action", "final_response", "required", true, "allowed", List.of("DONE person_report_md=<path> quality_summary_json=<path>", "BLOCKED step=<step> action=<action> path=<path> reason=<reason>"), "status", "pending")
        );
    }

    private Map<String, Object> step(int step, String action, Path target) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("step", step);
        map.put("action", action);
        map.put("required", step != 4);
        if (target != null) {
            map.put("target_path", target.toString());
        }
        map.put("status", "pending");
        return map;
    }

    private Map<String, Object> stepWithMarker(int step, String action, Path target, String marker) {
        Map<String, Object> map = step(step, action, target);
        map.put("marker", marker);
        return map;
    }

    private String makeAuthorKey(int rank, String author) {
        String normalized = author.chars()
                .mapToObj(ch -> Character.isLetterOrDigit(ch) ? String.valueOf((char) Character.toLowerCase(ch)) : "-")
                .reduce("", String::concat)
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        return "author-%03d-%s".formatted(rank, normalized.substring(0, Math.min(60, normalized.length())));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> authors(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("authors");
    }

    private void writeJson(Path path, Object value) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }
}

class GitReportPreparation {
    private static final Logger log = LoggerFactory.getLogger(GitReportPreparation.class);

    private final GitStatsCollector statsCollector;
    private final ReportPreparationWriter writer;

    GitReportPreparation(GitStatsCollector statsCollector, ReportPreparationWriter writer) {
        this.statsCollector = statsCollector;
        this.writer = writer;
    }

    void prepare(GitReportProperties properties) throws Exception {
        validate(properties);
        log.info("Preparing git contribution data: repo={}, out={}, since={}, until={}, revision={}, includeMerges={}",
                properties.getPaths().getRepo().toAbsolutePath().normalize(),
                properties.getPaths().getOut().toAbsolutePath().normalize(),
                properties.getGit().getSince(),
                properties.getGit().getUntil(),
                properties.getGit().getRevision(),
                properties.getGit().isIncludeMerges());
        Map<String, Object> data = statsCollector.collect(properties);
        writer.write(properties.getPaths().getOut().toAbsolutePath().normalize(), data);
    }

    private void validate(GitReportProperties properties) {
        if (properties.getGit().getSince() == null || properties.getGit().getUntil() == null) {
            throw new IllegalArgumentException("git-report.git.since and git-report.git.until are required");
        }
        if (properties.getGit().getSince().isAfter(properties.getGit().getUntil())) {
            throw new IllegalArgumentException("git-report.git.since must be earlier than or equal to git-report.git.until");
        }
    }
}

record OpenCodeSession(String id) {
}

record OpenCodeServerHandle(URI serverUrl, boolean ownedByJava) {
}

class OpenCodeRunResult {
    private final String sessionId;
    private final String serverUrl;
    private final boolean serverOwnedByJava;
    private final boolean timedOut;
    private final boolean completedByOutput;
    private final boolean aborted;
    private final String serverState;

    OpenCodeRunResult(String sessionId, String serverUrl, boolean serverOwnedByJava, boolean timedOut, boolean completedByOutput, boolean aborted, String serverState) {
        this.sessionId = sessionId;
        this.serverUrl = serverUrl;
        this.serverOwnedByJava = serverOwnedByJava;
        this.timedOut = timedOut;
        this.completedByOutput = completedByOutput;
        this.aborted = aborted;
        this.serverState = serverState == null ? "unknown" : serverState;
    }

    String sessionId() {
        return sessionId;
    }

    String serverUrl() {
        return serverUrl;
    }

    boolean serverOwnedByJava() {
        return serverOwnedByJava;
    }

    boolean timedOut() {
        return timedOut;
    }

    boolean completedByOutput() {
        return completedByOutput;
    }

    boolean aborted() {
        return aborted;
    }

    String serverState() {
        return serverState;
    }
}

class OpenCodeServerClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    OpenCodeServerClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    OpenCodeServerClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    boolean isHealthy(URI serverUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(resolve(serverUrl, "/global/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    OpenCodeSession createSession(URI serverUrl, Path repo, String title) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        HttpRequest request = HttpRequest.newBuilder(resolve(serverUrl, "/session", repo))
                .timeout(Duration.ofSeconds(10))
                .header("content-type", "application/json")
                .header("x-opencode-directory", repo.toAbsolutePath().normalize().toString())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        JsonNode json = sendJson(request);
        String id = firstText(json, "id", "sessionID", "sessionId");
        if (id.isBlank()) {
            throw new IllegalStateException("OpenCode Server /session response missing session id: " + json);
        }
        return new OpenCodeSession(id);
    }

    void sendPromptAsync(URI serverUrl, Path repo, String sessionId, String text, String model) throws IOException, InterruptedException {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", text);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parts", List.of(part));
        if (model != null && !model.isBlank()) {
            body.put("model", modelObject(model));
        }
        HttpRequest request = HttpRequest.newBuilder(resolve(serverUrl, "/session/" + pathEncode(sessionId) + "/prompt_async", repo))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        sendJson(request);
    }

    String getSessionStatus(URI serverUrl, Path repo, String sessionId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(resolve(serverUrl, "/session/" + pathEncode(sessionId), repo))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        JsonNode json = sendJson(request);
        String direct = firstText(json, "status", "state");
        if (!direct.isBlank()) {
            return direct;
        }
        JsonNode nested = json.path("session");
        return firstText(nested, "status", "state");
    }

    boolean abortSession(URI serverUrl, Path repo, String sessionId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(resolve(serverUrl, "/session/" + pathEncode(sessionId) + "/abort", repo))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Map<String, Object> modelObject(String model) {
        String trimmed = model.trim();
        int slash = trimmed.indexOf('/');
        if (slash <= 0 || slash == trimmed.length() - 1) {
            throw new IllegalArgumentException("git-report.opencode.model must use provider/model format when set: " + model);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerID", trimmed.substring(0, slash));
        result.put("modelID", trimmed.substring(slash + 1));
        return result;
    }

    private JsonNode sendJson(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenCode Server request failed: " + request.method() + " " + request.uri() + " status=" + response.statusCode() + " body=" + response.body());
        }
        String body = response.body() == null || response.body().isBlank() ? "{}" : response.body();
        return objectMapper.readTree(body);
    }

    private URI resolve(URI serverUrl, String path) {
        String base = serverUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private URI resolve(URI serverUrl, String path, Path directory) {
        return URI.create(resolve(serverUrl, path) + "?directory=" + pathEncode(directory.toAbsolutePath().normalize().toString()));
    }

    private String pathEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String firstText(JsonNode json, String... names) {
        for (String name : names) {
            JsonNode value = json.path(name);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }
}

class OpenCodeServerManager {
    private static final Logger log = LoggerFactory.getLogger(OpenCodeServerManager.class);

    private final OpenCodeServerClient client;
    private Process ownedProcess;
    private URI ownedServerUrl;

    OpenCodeServerManager(OpenCodeServerClient client) {
        this.client = client;
    }

    synchronized OpenCodeServerHandle ensureReady(GitReportProperties properties, Path out) throws IOException, InterruptedException {
        URI serverUrl = URI.create(properties.getOpencode().getServerUrl());
        if (client.isHealthy(serverUrl)) {
            log.info("Reusing healthy OpenCode Server: {}", serverUrl);
            return new OpenCodeServerHandle(serverUrl, false);
        }
        if (!properties.getOpencode().isManageServer()) {
            throw new IllegalStateException("OpenCode Server is not healthy at " + serverUrl + " and git-report.opencode.manage-server=false. Start `opencode serve` or enable server management.");
        }
        if (ownedProcess != null && ownedProcess.isAlive() && serverUrl.equals(ownedServerUrl)) {
            waitForHealth(serverUrl, properties.getOpencode().getServerStartTimeoutSeconds());
            return new OpenCodeServerHandle(serverUrl, true);
        }
        startServer(properties, out, serverUrl);
        waitForHealth(serverUrl, properties.getOpencode().getServerStartTimeoutSeconds());
        return new OpenCodeServerHandle(serverUrl, true);
    }

    synchronized void shutdown() throws InterruptedException {
        if (ownedProcess == null) {
            return;
        }
        if (ownedProcess.isAlive()) {
            log.info("Stopping managed OpenCode Server: {}", ownedServerUrl);
            ownedProcess.destroy();
            if (!ownedProcess.waitFor(5, TimeUnit.SECONDS)) {
                ownedProcess.destroyForcibly();
                ownedProcess.waitFor();
            }
        }
        ownedProcess = null;
        ownedServerUrl = null;
    }

    private void startServer(GitReportProperties properties, Path out, URI serverUrl) throws IOException {
        String opencodeBin = properties.getPaths().getOpencodeBin();
        if (opencodeBin == null || opencodeBin.isBlank()) {
            throw new IllegalArgumentException("git-report.paths.opencode-bin is required when git-report.opencode.manage-server=true");
        }
        int port = serverUrl.getPort();
        if (port <= 0) {
            throw new IllegalArgumentException("git-report.opencode.server-url must include an explicit port when Java manages OpenCode Server: " + serverUrl);
        }
        Path logDir = out.resolve("runs").resolve("opencode-server").toAbsolutePath().normalize();
        Files.createDirectories(logDir);
        List<String> command = List.of(opencodeBin, "serve", "--port", String.valueOf(port));
        log.info("Starting managed OpenCode Server: command={}, logs={}", String.join(" ", command), logDir);
        ownedProcess = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logDir.resolve("stdout.log").toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(logDir.resolve("stderr.log").toFile()))
                .start();
        ownedServerUrl = serverUrl;
    }

    private void waitForHealth(URI serverUrl, int timeoutSeconds) throws InterruptedException, IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        while (System.nanoTime() < deadline) {
            if (client.isHealthy(serverUrl)) {
                log.info("OpenCode Server is healthy: {}", serverUrl);
                return;
            }
            if (ownedProcess != null && !ownedProcess.isAlive()) {
                throw new IllegalStateException("OpenCode Server process exited before becoming healthy: " + serverUrl + ", exitCode=" + ownedProcess.exitValue());
            }
            TimeUnit.MILLISECONDS.sleep(250);
        }
        Process process = ownedProcess;
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            process.waitFor();
            ownedProcess = null;
            ownedServerUrl = null;
        }
        throw new IllegalStateException("OpenCode Server startup timed out after " + timeoutSeconds + " seconds: " + serverUrl);
    }
}

class OpenCodeServerTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(OpenCodeServerTaskRunner.class);

    private final OpenCodeServerClient client;

    OpenCodeServerTaskRunner(OpenCodeServerClient client) {
        this.client = client;
    }

    OpenCodeRunResult runUntil(
            OpenCodeServerHandle server,
            Path repo,
            String title,
            Path promptFile,
            String message,
            String model,
            Path runDir,
            CompletionProbe completionProbe,
            int pollMillis,
            int timeoutMinutes
    ) throws IOException, InterruptedException {
        Files.createDirectories(runDir);
        String prompt = Files.readString(promptFile);
        String text = composeMessage(message, prompt);
        OpenCodeSession session = client.createSession(server.serverUrl(), repo, title);
        log.info("Starting OpenCode Server session: sessionId={}, title={}, runDir={}, timeoutMinutes={}", session.id(), title, runDir, timeoutMinutes);
        client.sendPromptAsync(server.serverUrl(), repo, session.id(), text, model);
        long timeoutNanos = TimeUnit.MINUTES.toNanos(Math.max(0, timeoutMinutes));
        long deadline = System.nanoTime() + timeoutNanos;
        int sleepMillis = Math.max(50, pollMillis);
        String lastState = "unknown";
        while (timeoutNanos == 0 || System.nanoTime() <= deadline) {
            if (isComplete(completionProbe, runDir)) {
                return new OpenCodeRunResult(session.id(), server.serverUrl().toString(), server.ownedByJava(), false, true, false, lastState);
            }
            lastState = readSessionState(server.serverUrl(), repo, session.id(), lastState);
            if (isTerminalFailure(lastState)) {
                return new OpenCodeRunResult(session.id(), server.serverUrl().toString(), server.ownedByJava(), false, false, false, lastState);
            }
            if (isTerminalSuccess(lastState)) {
                return new OpenCodeRunResult(session.id(), server.serverUrl().toString(), server.ownedByJava(), false, false, false, lastState);
            }
            if (timeoutNanos == 0) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(sleepMillis);
        }
        boolean aborted = client.abortSession(server.serverUrl(), repo, session.id());
        return new OpenCodeRunResult(session.id(), server.serverUrl().toString(), server.ownedByJava(), true, false, aborted, lastState);
    }

    private String composeMessage(String message, String prompt) {
        if (message == null || message.isBlank()) {
            return prompt;
        }
        return message + "\n\n" + prompt;
    }

    private boolean isComplete(CompletionProbe completionProbe, Path runDir) throws IOException {
        try {
            return completionProbe.isComplete();
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("completion probe failed for runDir=" + runDir + ": " + exception.getMessage(), exception);
        }
    }

    private String readSessionState(URI serverUrl, Path repo, String sessionId, String fallback) {
        try {
            String status = client.getSessionStatus(serverUrl, repo, sessionId);
            return status == null || status.isBlank() ? fallback : status;
        } catch (Exception exception) {
            log.debug("Unable to read OpenCode session status: sessionId={}, reason={}", sessionId, exception.getMessage());
            return fallback;
        }
    }

    private boolean isTerminalSuccess(String state) {
        String normalized = state == null ? "" : state.toLowerCase(Locale.ROOT);
        return normalized.equals("idle") || normalized.equals("completed") || normalized.equals("complete") || normalized.equals("done");
    }

    private boolean isTerminalFailure(String state) {
        String normalized = state == null ? "" : state.toLowerCase(Locale.ROOT);
        return normalized.equals("failed") || normalized.equals("error") || normalized.equals("aborted") || normalized.equals("canceled") || normalized.equals("cancelled");
    }
}

class PromptBuilder {
    String buildWorkerPrompt(Path detailJson) {
        String prompt = readResource("git-report-prompt-pack/prompts/run-author-report.md");
        String template = readResource("git-report-prompt-pack/templates/person-code-contribution-report.md");
        return prompt + "\n\n## 路径载荷\n\n```text\n"
                + "detail_json: " + detailJson + "\n"
                + "```\n\n## 个人报告模板\n\n```markdown\n"
                + template
                + "\n```\n";
    }

    String buildSynthesisPrompt(Path summaryJson, Path indexInputsJson, Path qualityScoresJson) {
        String prompt = readResource("git-report-prompt-pack/prompts/synthesize-report.md");
        String template = readResource("git-report-prompt-pack/templates/code-contribution-report.md");
        return prompt + "\n\n## 路径载荷\n\n```text\n"
                + "summary_json: " + summaryJson + "\n"
                + "index_inputs_json: " + indexInputsJson + "\n"
                + "quality_scores_json: " + qualityScoresJson + "\n"
                + "```\n\n## 总报告模板\n\n```markdown\n"
                + template
                + "\n```\n";
    }

    private String readResource(String path) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new IllegalStateException("resource missing: " + path);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

class AuthorValidationResult {
    private final boolean ok;
    private final String error;

    private AuthorValidationResult(boolean ok, String error) {
        this.ok = ok;
        this.error = error;
    }

    static AuthorValidationResult success() {
        return new AuthorValidationResult(true, "");
    }

    static AuthorValidationResult failed(String error) {
        return new AuthorValidationResult(false, error);
    }

    boolean ok() {
        return ok;
    }

    String error() {
        return error;
    }
}

class AuthorOutputValidator {
    private final ObjectMapper objectMapper;

    AuthorOutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AuthorValidationResult validate(Path reportMd, Path qualitySummaryJson) {
        try {
            if (!Files.exists(reportMd)) {
                return AuthorValidationResult.failed("person report missing: " + reportMd);
            }
            String report = Files.readString(reportMd);
            if (report.isBlank()) {
                return AuthorValidationResult.failed("person report still contains marker: " + reportMd);
            }
            if (!Files.exists(qualitySummaryJson)) {
                return AuthorValidationResult.failed("quality summary missing: " + qualitySummaryJson);
            }
            String quality = Files.readString(qualitySummaryJson);
            if (quality.isBlank() || quality.trim().equals(GitReportConstants.QUALITY_SUMMARY_MARKER) || quality.contains(GitReportConstants.QUALITY_SUMMARY_MARKER)) {
                return AuthorValidationResult.failed("quality summary still contains marker: " + qualitySummaryJson);
            }
            Map<String, Object> root = objectMapper.readValue(quality, new TypeReference<>() {});
            for (String key : List.of("findings", "positive_signals", "risk_signals", "code_snippets", "unverified")) {
                if (!(root.get(key) instanceof List<?>)) {
                    return AuthorValidationResult.failed("quality summary field must be array: " + key);
                }
            }
            if (!root.containsKey("summary")) {
                return AuthorValidationResult.failed("quality summary missing summary");
            }
            if (root.containsKey("quality_adjustment_percent")) {
                return AuthorValidationResult.failed("quality summary must not contain quality_adjustment_percent");
            }
            if (root.get("components") instanceof List<?> components) {
                for (Object component : components) {
                    if (component instanceof Map<?, ?> map && map.containsKey("score")) {
                        return AuthorValidationResult.failed("quality summary must not contain components[].score");
                    }
                }
            }
            if (report.contains(GitReportConstants.AUTHOR_REPORT_MARKER)) {
                if (!removeTrailingAuthorMarker(reportMd, report)) {
                    return AuthorValidationResult.failed("person report still contains marker: " + reportMd);
                }
            }
            return AuthorValidationResult.success();
        } catch (Exception exception) {
            return AuthorValidationResult.failed(exception.getMessage());
        }
    }

    private boolean removeTrailingAuthorMarker(Path reportMd, String report) throws IOException {
        int markerAt = report.indexOf(GitReportConstants.AUTHOR_REPORT_MARKER);
        int lastMarkerAt = report.lastIndexOf(GitReportConstants.AUTHOR_REPORT_MARKER);
        if (markerAt != lastMarkerAt) {
            return false;
        }
        String beforeMarker = report.substring(0, markerAt);
        String afterMarker = report.substring(markerAt + GitReportConstants.AUTHOR_REPORT_MARKER.length());
        if (beforeMarker.isBlank() || !afterMarker.isBlank()) {
            return false;
        }
        Files.writeString(reportMd, beforeMarker.stripTrailing() + "\n");
        return true;
    }
}

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

@FunctionalInterface
interface CompletionProbe {
    boolean isComplete() throws Exception;
}

class AuthorTaskResult {
    private final String authorKey;
    private final String author;
    private final Path statusPath;
    private final boolean success;
    private final String error;

    private AuthorTaskResult(String authorKey, String author, Path statusPath, boolean success, String error) {
        this.authorKey = authorKey;
        this.author = author;
        this.statusPath = statusPath;
        this.success = success;
        this.error = error == null ? "" : error;
    }

    static AuthorTaskResult success(String authorKey, String author, Path statusPath) {
        return new AuthorTaskResult(authorKey, author, statusPath, true, "");
    }

    static AuthorTaskResult failed(String authorKey, String author, Path statusPath, String error) {
        return new AuthorTaskResult(authorKey, author, statusPath, false, error);
    }

    String authorKey() {
        return authorKey;
    }

    String author() {
        return author;
    }

    Path statusPath() {
        return statusPath;
    }

    boolean success() {
        return success;
    }

    String error() {
        return error;
    }
}

class RunStatusRepository {
    private final ObjectMapper objectMapper;

    RunStatusRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(Path statusPath, Map<String, Object> status) throws IOException {
        Files.createDirectories(statusPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(statusPath.toFile(), status);
    }
}

class GitReportOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(GitReportOrchestrator.class);

    private final GitReportPreparation preparation;
    private final ObjectMapper objectMapper;
    private final PromptBuilder promptBuilder;
    private final OpenCodeServerManager serverManager;
    private final OpenCodeServerTaskRunner taskRunner;
    private final AuthorOutputValidator outputValidator;
    private final QualityScoresWriter qualityScoresWriter;
    private final RunStatusRepository statusRepository;

    GitReportOrchestrator(
            GitReportPreparation preparation,
            ObjectMapper objectMapper,
            PromptBuilder promptBuilder,
            OpenCodeServerManager serverManager,
            OpenCodeServerTaskRunner taskRunner,
            AuthorOutputValidator outputValidator,
            QualityScoresWriter qualityScoresWriter,
            RunStatusRepository statusRepository
    ) {
        this.preparation = preparation;
        this.objectMapper = objectMapper;
        this.promptBuilder = promptBuilder;
        this.serverManager = serverManager;
        this.taskRunner = taskRunner;
        this.outputValidator = outputValidator;
        this.qualityScoresWriter = qualityScoresWriter;
        this.statusRepository = statusRepository;
    }

    void run(GitReportProperties properties) throws Exception {
        Path out = properties.getPaths().getOut().toAbsolutePath().normalize();
        OpenCodeServerHandle server = serverManager.ensureReady(properties, out);
        log.info("Git report orchestration started: projectId={}, projectName={}, runId={}, repo={}, out={}, serverUrl={}, serverOwnedByJava={}, concurrency={}, timeoutMinutes={}, outputWaitSeconds={}, maxRetries={}",
                properties.getProject().getId(),
                properties.getProject().getName(),
                properties.getProject().getRunId(),
                properties.getPaths().getRepo().toAbsolutePath().normalize(),
                out,
                server.serverUrl(),
                server.ownedByJava(),
                properties.getOpencode().getConcurrency(),
                properties.getOpencode().getTimeoutMinutes(),
                properties.getOpencode().getOutputWaitSeconds(),
                properties.getOpencode().getMaxRetries());
        preparation.prepare(properties);
        Map<String, Object> summary = readMap(out.resolve("summary.json"));
        Map<String, Object> indexInputs = readMap(out.resolve("index_inputs.json"));
        log.info("Loaded preparation outputs: summary={}, indexInputs={}, authorTaskCount={}",
                out.resolve("summary.json"),
                out.resolve("index_inputs.json"),
                listOfMaps(indexInputs.get("tasks")).size());
        runAuthorTasks(properties, out, indexInputs, server);
        Path qualityScores = qualityScoresWriter.write(out.resolve("quality-scores.json"), summary, indexInputs);
        runSynthesis(properties, out, qualityScores, server);
        log.info("Git report orchestration completed: finalReport={}", out.resolve("code-contribution-report.md"));
    }

    void runSynthesisOnly(GitReportProperties properties) throws Exception {
        Path out = properties.getPaths().getOut().toAbsolutePath().normalize();
        OpenCodeServerHandle server = serverManager.ensureReady(properties, out);
        log.info("Git report synthesis-only orchestration started: projectId={}, projectName={}, runId={}, repo={}, out={}, serverUrl={}, serverOwnedByJava={}",
                properties.getProject().getId(),
                properties.getProject().getName(),
                properties.getProject().getRunId(),
                properties.getPaths().getRepo().toAbsolutePath().normalize(),
                out,
                server.serverUrl(),
                server.ownedByJava());
        Map<String, Object> summary = readMap(out.resolve("summary.json"));
        Map<String, Object> indexInputs = readMap(out.resolve("index_inputs.json"));
        validateExistingAuthorOutputs(indexInputs);
        Path qualityScores = qualityScoresWriter.write(out.resolve("quality-scores.json"), summary, indexInputs);
        runSynthesis(properties, out, qualityScores, server);
        log.info("Git report synthesis-only orchestration completed: finalReport={}", out.resolve("code-contribution-report.md"));
    }

    private void validateExistingAuthorOutputs(Map<String, Object> indexInputs) {
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> task : listOfMaps(indexInputs.get("tasks"))) {
            Path report = Path.of(task.get("report_md").toString());
            Path qualitySummary = Path.of(task.get("quality_summary_json").toString());
            AuthorValidationResult validation = outputValidator.validate(report, qualitySummary);
            if (!validation.ok()) {
                failures.add(task.get("author_key") + " (" + task.get("author") + "): " + validation.error());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("existing author outputs are incomplete: " + String.join("; ", failures));
        }
    }

    private void runAuthorTasks(GitReportProperties properties, Path out, Map<String, Object> indexInputs, OpenCodeServerHandle server) throws Exception {
        List<Map<String, Object>> tasks = listOfMaps(indexInputs.get("tasks"));
        int concurrency = Math.max(1, Math.min(properties.getOpencode().getConcurrency(), properties.getOpencode().getMaxConcurrency()));
        log.info("Starting author tasks: taskCount={}, concurrency={}", tasks.size(), concurrency);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<AuthorTaskResult>> futures = new ArrayList<>();
            for (Map<String, Object> task : tasks) {
                futures.add(executor.submit(authorCallable(properties, out, task, server)));
            }
            List<AuthorTaskResult> failures = new ArrayList<>();
            for (Future<AuthorTaskResult> future : futures) {
                AuthorTaskResult result = future.get();
                if (!result.success()) {
                    failures.add(result);
                }
            }
            if (!failures.isEmpty()) {
                String summary = failures.stream()
                        .map(result -> result.authorKey() + " (" + result.author() + "), status=" + result.statusPath() + ", error=" + result.error())
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("");
                String firstReason = failures.get(0).error();
                log.error("AUTHOR_FAILURE_SUMMARY firstReason=\"{}\" failedCount={} failures={}", firstReason, failures.size(), summary);
                throw new IllegalStateException("author task failed: " + summary);
            }
            log.info("All author tasks completed successfully");
        } finally {
            executor.shutdown();
        }
    }

    private Callable<AuthorTaskResult> authorCallable(GitReportProperties properties, Path out, Map<String, Object> task, OpenCodeServerHandle server) {
        return () -> {
            String authorKey = task.get("author_key").toString();
            String author = task.get("author").toString();
            Path runDir = out.resolve("runs").resolve(authorKey);
            Path statusPath = runDir.resolve("status.json");
            int attempts = Math.max(1, properties.getOpencode().getMaxRetries() + 1);
            String lastError = "";
            for (int attempt = 1; attempt <= attempts; attempt++) {
                log.info("Author task started: authorKey={}, author={}, attempt={}/{}", authorKey, author, attempt, attempts);
                String state = "failed";
                String error = "";
                boolean timedOut = false;
                OpenCodeRunResult runResult = null;
                try {
                    Files.createDirectories(runDir);
                    Path promptFile = runDir.resolve("worker-prompt.md");
                    String prompt = promptBuilder.buildWorkerPrompt(Path.of(task.get("detail_json").toString()));
                    Files.writeString(promptFile, prompt);
                    Path reportMd = Path.of(task.get("report_md").toString());
                    Path qualitySummaryJson = Path.of(task.get("quality_summary_json").toString());
                    runResult = taskRunner.runUntil(
                            server,
                            properties.getPaths().getRepo(),
                            "git-report-" + authorKey,
                            promptFile,
                            properties.getOpencode().getWorkerMessage(),
                            properties.getOpencode().getModel(),
                            runDir,
                            () -> outputValidator.validate(reportMd, qualitySummaryJson).ok(),
                            2_000,
                            properties.getOpencode().getTimeoutMinutes()
                    );
                    timedOut = runResult.timedOut();
                    AuthorValidationResult validation = waitForAuthorOutputs(
                            reportMd,
                            qualitySummaryJson,
                            properties.getOpencode().getOutputWaitSeconds()
                    );
                    if (validation.ok()) {
                        state = "completed";
                        writeAuthorStatus(statusPath, task, attempt, state, timedOut, runResult, "");
                        log.info("Author task completed: authorKey={}, author={}, attempt={}, sessionId={}, timedOut={}, completedByOutput={}, serverState={}, report={}, qualitySummary={}",
                                authorKey,
                                author,
                                attempt,
                                runResult.sessionId(),
                                timedOut,
                                runResult.completedByOutput(),
                                runResult.serverState(),
                                task.get("report_md"),
                                task.get("quality_summary_json"));
                        return AuthorTaskResult.success(authorKey, author, statusPath);
                    }
                    state = timedOut ? "timeout" : "failed";
                    error = validation.error();
                    if (!error.isBlank()) {
                        log.warn("AUTHOR_VALIDATION_FAILED reason=\"{}\" authorKey={} author={} attempt={}", error, authorKey, author, attempt);
                    }
                } catch (Exception exception) {
                    error = sanitizeOpenCodeError(exception);
                    log.warn("AUTHOR_ATTEMPT_EXCEPTION reason=\"{}\" authorKey={} author={} attempt={}",
                            error, authorKey, author, attempt, exception);
                }
                lastError = error;
                writeAuthorStatus(statusPath, task, attempt, state, timedOut, runResult, error);
                log.warn("AUTHOR_ATTEMPT_FAILED reason=\"{}\" state={} timedOut={} authorKey={} author={} attempt={}",
                        error, state, timedOut, authorKey, author, attempt);
            }
            log.error("AUTHOR_FAILED reason=\"{}\" status={} authorKey={} author={} attempts={}", lastError, statusPath, authorKey, author, attempts);
            return AuthorTaskResult.failed(authorKey, author, statusPath, lastError);
        };
    }

    private AuthorValidationResult waitForAuthorOutputs(Path reportMd, Path qualitySummaryJson, int outputWaitSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(0, outputWaitSeconds));
        AuthorValidationResult last = outputValidator.validate(reportMd, qualitySummaryJson);
        while (!last.ok() && System.nanoTime() < deadline) {
            TimeUnit.SECONDS.sleep(2);
            last = outputValidator.validate(reportMd, qualitySummaryJson);
        }
        return last;
    }

    private String sanitizeOpenCodeError(Exception exception) {
        return exception.getClass().getName() + ": " + (exception.getMessage() == null ? "" : exception.getMessage());
    }

    private void runSynthesis(GitReportProperties properties, Path out, Path qualityScores, OpenCodeServerHandle server) throws Exception {
        Path runDir = out.resolve("runs").resolve("synthesis");
        Files.createDirectories(runDir);
        Path finalReport = out.resolve("code-contribution-report.md");
        Files.writeString(finalReport, GitReportConstants.REPORT_MARKER + "\n");
        Path promptFile = runDir.resolve("synthesis-prompt.md");
        String prompt = promptBuilder.buildSynthesisPrompt(
                out.resolve("summary.json"),
                out.resolve("index_inputs.json"),
                qualityScores
        );
        Files.writeString(promptFile, prompt);
        log.info("Starting synthesis task: prompt={}, qualityScores={}, finalReport={}",
                promptFile,
                qualityScores,
                out.resolve("code-contribution-report.md"));
        OpenCodeRunResult result = taskRunner.runUntil(
                server,
                properties.getPaths().getRepo(),
                "git-report-synthesis",
                promptFile,
                properties.getOpencode().getSynthesisMessage(),
                properties.getOpencode().getModel(),
                runDir,
                () -> finalReportReady(finalReport),
                2_000,
                properties.getOpencode().getTimeoutMinutes()
        );
        boolean ok = waitForFinalReport(finalReport, properties.getOpencode().getOutputWaitSeconds());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", ok ? "completed" : "failed");
        status.put("sessionId", result.sessionId());
        status.put("serverUrl", result.serverUrl());
        status.put("serverOwnedByJava", result.serverOwnedByJava());
        status.put("timedOut", result.timedOut());
        status.put("completedByOutput", result.completedByOutput());
        status.put("aborted", result.aborted());
        status.put("serverState", result.serverState());
        status.put("finalReportOk", ok);
        status.put("finishedAt", OffsetDateTime.now().toString());
        statusRepository.write(runDir.resolve("status.json"), status);
        if (!ok) {
            log.error("Synthesis failed: sessionId={}, timedOut={}, finalReportOk={}, status={}",
                    result.sessionId(),
                    result.timedOut(),
                    ok,
                    runDir.resolve("status.json"));
            throw new IllegalStateException("synthesis failed");
        }
        log.info("Synthesis completed: finalReport={}", finalReport);
    }

    private boolean waitForFinalReport(Path finalReport, int outputWaitSeconds) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(0, outputWaitSeconds));
        boolean ok = finalReportReady(finalReport);
        while (!ok && System.nanoTime() < deadline) {
            TimeUnit.SECONDS.sleep(2);
            ok = finalReportReady(finalReport);
        }
        return ok;
    }

    private boolean finalReportReady(Path finalReport) throws IOException {
        String report = Files.exists(finalReport) ? Files.readString(finalReport) : "";
        return !report.isBlank() && !report.contains(GitReportConstants.REPORT_MARKER);
    }

    private void writeAuthorStatus(Path statusPath, Map<String, Object> task, int attempt, String state, boolean timedOut, OpenCodeRunResult runResult, String error) throws IOException {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("authorKey", task.get("author_key"));
        status.put("author", task.get("author"));
        status.put("attempt", attempt);
        status.put("state", state);
        status.put("sessionId", runResult == null ? "" : runResult.sessionId());
        status.put("serverUrl", runResult == null ? "" : runResult.serverUrl());
        status.put("serverOwnedByJava", runResult != null && runResult.serverOwnedByJava());
        status.put("timedOut", timedOut);
        status.put("completedByOutput", runResult != null && runResult.completedByOutput());
        status.put("aborted", runResult != null && runResult.aborted());
        status.put("serverState", runResult == null ? "unknown" : runResult.serverState());
        status.put("finishedAt", OffsetDateTime.now().toString());
        status.put("error", error == null ? "" : error);
        statusRepository.write(statusPath, status);
    }

    private Map<String, Object> readMap(Path path) throws IOException {
        return objectMapper.readValue(path.toFile(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
