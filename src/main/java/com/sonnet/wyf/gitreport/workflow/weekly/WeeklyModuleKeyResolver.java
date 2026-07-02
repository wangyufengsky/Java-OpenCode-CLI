package com.sonnet.wyf.gitreport.workflow.weekly;

final class WeeklyModuleKeyResolver {
    String moduleKey(String file) {
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
}
