package com.sonnet.wyf.gitreport.opencode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class OpenCodeUris {
    private OpenCodeUris() {
    }

    static URI resolve(URI serverUrl, String path) {
        String base = serverUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    static URI resolveWithQuery(URI serverUrl, String path, String query) {
        if (query != null && !query.isBlank()) {
            return resolve(serverUrl, path + "?" + query);
        }
        return resolve(serverUrl, path);
    }

    static String queryEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String pathEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
