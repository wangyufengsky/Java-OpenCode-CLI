package com.sonnet.wyf.gitreport.console;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ConsoleConfigNormalizer {
    private ConsoleConfigNormalizer() {
    }

    static Map<String, Object> normalize(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            Object cleaned = clean(value);
            if (key == null || key.isBlank() || cleaned == null) {
                return;
            }
            normalized.put(key, cleaned);
        });
        return Map.copyOf(normalized);
    }

    private static Object clean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isBlank()
                    || "undefined".equalsIgnoreCase(trimmed)
                    || "null".equalsIgnoreCase(trimmed)) {
                return null;
            }
            return text;
        }
        if (value instanceof List<?> list) {
            List<?> cleaned = list.stream()
                    .map(ConsoleConfigNormalizer::clean)
                    .filter(item -> item != null)
                    .toList();
            return cleaned.isEmpty() ? null : cleaned;
        }
        return value;
    }
}
