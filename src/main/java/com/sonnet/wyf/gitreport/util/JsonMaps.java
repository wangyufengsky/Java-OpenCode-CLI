package com.sonnet.wyf.gitreport.util;

import java.util.List;
import java.util.Map;

public final class JsonMaps {
    private JsonMaps() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    public static List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    public static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    public static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    public static double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}
