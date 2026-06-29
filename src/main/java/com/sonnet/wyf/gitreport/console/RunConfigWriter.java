package com.sonnet.wyf.gitreport.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class RunConfigWriter {
    private final TaskConsoleProperties properties;
    private final ObjectMapper yamlMapper = new ObjectMapper(
            YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .build()
    );

    public RunConfigWriter(TaskConsoleProperties properties) {
        this.properties = properties;
    }

    public Path writeConfig(long runId, String chainId, Map<String, Object> config, String defaultYaml) throws IOException {
        Path runDir = properties.getRunConfigDir().resolve("run-" + runId);
        Files.createDirectories(runDir);
        Path configPath = runDir.resolve(chainId + ".yml");
        if (config == null || config.isEmpty()) {
            Files.writeString(configPath, defaultYaml == null ? "" : defaultYaml);
        } else {
            yamlMapper.writeValue(configPath.toFile(), nested(config));
        }
        return configPath;
    }

    private static Map<String, Object> nested(Map<String, Object> flatConfig) {
        Map<String, Object> root = new LinkedHashMap<>();
        flatConfig.forEach((key, value) -> put(root, key, value));
        return root;
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> root, String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        String[] parts = key.split("\\.");
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = cursor.computeIfAbsent(parts[i], ignored -> new LinkedHashMap<String, Object>());
            if (!(child instanceof Map<?, ?>)) {
                child = new LinkedHashMap<String, Object>();
                cursor.put(parts[i], child);
            }
            cursor = (Map<String, Object>) child;
        }
        cursor.put(parts[parts.length - 1], normalize(value));
    }

    private static Object normalize(Object value) {
        if (value instanceof String text && text.isBlank()) {
            return null;
        }
        return value;
    }
}
