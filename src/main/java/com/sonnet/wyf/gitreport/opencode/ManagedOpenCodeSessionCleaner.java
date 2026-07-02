package com.sonnet.wyf.gitreport.opencode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ManagedOpenCodeSessionCleaner {
    private static final Logger log = LoggerFactory.getLogger(ManagedOpenCodeSessionCleaner.class);

    private final OpenCodeServerClient client;
    private final Set<String> cleanedManagedSessionScopes = ConcurrentHashMap.newKeySet();

    ManagedOpenCodeSessionCleaner(OpenCodeServerClient client) {
        this.client = client;
    }

    void clearPriorManagedSessionsIfNeeded(OpenCodeServerHandle server, Path repo, String title, int requestTimeoutSeconds) throws InterruptedException {
        List<String> prefixes = managedSessionTitlePrefixes(title);
        if (prefixes.isEmpty()) {
            return;
        }
        String key = server.serverUrl() + "|" + repo.toAbsolutePath().normalize() + "|" + String.join(",", prefixes);
        if (!cleanedManagedSessionScopes.add(key)) {
            return;
        }
        try {
            int deleted = client.deleteSessionsByTitlePrefixes(server.serverUrl(), repo, prefixes, requestTimeoutSeconds);
            log.info("OpenCode managed session cleanup completed before task startup: title={}, deletedCount={}", title, deleted);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            log.warn("OpenCode managed session cleanup failed before task startup; continuing: title={}, reason={}",
                    title, exception.toString());
        }
    }

    private List<String> managedSessionTitlePrefixes(String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        if (title.startsWith("smartesb-review-")) {
            return List.of("smartesb-review-");
        }
        if (title.startsWith("smartesb-reader-")) {
            return List.of("smartesb-reader-");
        }
        if (title.startsWith("git-report-")) {
            return List.of("git-report-");
        }
        if (title.startsWith("weekly-code-review-")) {
            return List.of("weekly-code-review-");
        }
        return List.of();
    }
}
