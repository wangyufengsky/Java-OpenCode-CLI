package com.sonnet.wyf.gitreport.console;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class RunConfigReader {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public Map<String, Object> readFlat(Path configPath) throws IOException {
        if (configPath == null) {
            throw new IllegalArgumentException("运行配置路径不能为空");
        }
        Path normalized = configPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("运行配置文件不存在或不可读取");
        }
        Map<String, Object> source = yamlMapper.readValue(normalized.toFile(), MAP_TYPE);
        Map<String, Object> flattened = new LinkedHashMap<>();
        flatten("", source == null ? Map.of() : source, flattened);
        return flattened;
    }

    private static void flatten(String prefix, Map<?, ?> source, Map<String, Object> target) {
        source.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            String segment = String.valueOf(key);
            String flattenedKey = prefix.isBlank() ? segment : prefix + "." + segment;
            if (value instanceof Map<?, ?> nested) {
                flatten(flattenedKey, nested, target);
            } else {
                target.put(flattenedKey, value);
            }
        });
    }
}
