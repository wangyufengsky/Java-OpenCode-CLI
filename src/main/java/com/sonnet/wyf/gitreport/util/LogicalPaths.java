package com.sonnet.wyf.gitreport.util;

public final class LogicalPaths {
    private LogicalPaths() {
    }

    public static boolean isAbsolute(String path) {
        return path != null && (path.startsWith("/") || path.matches("^[A-Za-z]:[\\\\/].*"));
    }

    public static String append(String base, String... segments) {
        String result = normalize(base);
        boolean windows = usesWindowsSeparators(result);
        String separator = windows ? "\\" : "/";
        for (String segment : segments) {
            String normalized = normalize(segment);
            normalized = windows ? normalized.replace('/', '\\') : normalized.replace('\\', '/');
            while (normalized.startsWith("/") || normalized.startsWith("\\")) {
                normalized = normalized.substring(1);
            }
            if (!result.endsWith(separator)) {
                result += separator;
            }
            result += normalized;
        }
        return result;
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return usesWindowsSeparators(value) ? value.replace('/', '\\') : value.replace('\\', '/');
    }

    public static String slug(String value, String fallback) {
        String slug = (value == null ? "" : value).chars()
                .mapToObj(ch -> Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' ? String.valueOf((char) ch) : "-")
                .reduce("", String::concat)
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return slug.isBlank() ? fallback : slug;
    }

    private static boolean usesWindowsSeparators(String value) {
        return value != null && value.matches("^[A-Za-z]:[\\\\/].*");
    }
}
