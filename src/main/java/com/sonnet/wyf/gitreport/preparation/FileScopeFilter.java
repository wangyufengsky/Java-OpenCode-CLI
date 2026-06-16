package com.sonnet.wyf.gitreport.preparation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import com.sonnet.wyf.gitreport.core.GitReportConstants;

public class FileScopeFilter {
    private final List<String> includes;
    private final List<String> excludes;
    private final List<Pattern> includePatterns;
    private final List<Pattern> excludePatterns;

    private FileScopeFilter(List<String> includes, List<String> excludes) {
        this.includes = includes;
        this.excludes = excludes;
        this.includePatterns = compilePatterns(includes);
        this.excludePatterns = compilePatterns(excludes);
    }

    public static FileScopeFilter withUserPatterns(List<String> userIncludes, List<String> userExcludes) {
        return new FileScopeFilter(merge(GitReportConstants.DEFAULT_INCLUDE_PATTERNS, userIncludes), merge(GitReportConstants.DEFAULT_EXCLUDE_PATTERNS, userExcludes));
    }

    public List<String> includes() {
        return includes;
    }

    public List<String> excludes() {
        return excludes;
    }

    public boolean isCounted(String path) {
        return matchesAny(path, includePatterns) && !matchesAny(path, excludePatterns);
    }

    private static List<String> merge(List<String> defaults, List<String> user) {
        LinkedHashSet<String> values = new LinkedHashSet<>(defaults);
        if (user != null) {
            values.addAll(user);
        }
        return new ArrayList<>(values);
    }

    private static List<Pattern> compilePatterns(List<String> patterns) {
        return patterns.stream()
                .map(pattern -> Pattern.compile(globToRegex(pattern.replace('\\', '/'))))
                .toList();
    }

    private static boolean matchesAny(String path, List<Pattern> patterns) {
        String normalized = path.replace('\\', '/');
        String basename = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        for (Pattern regex : patterns) {
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
