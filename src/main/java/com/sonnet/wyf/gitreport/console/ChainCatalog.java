package com.sonnet.wyf.gitreport.console;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ChainCatalog {
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ResourceLoader resourceLoader;
    private final AgentBridgeRunnerProperties properties;
    private final Map<String, WorkflowChain> chains;

    public ChainCatalog(ResourceLoader resourceLoader, AgentBridgeRunnerProperties properties, List<WorkflowChain> chains) {
        this.resourceLoader = resourceLoader;
        this.properties = properties;
        this.chains = chains.stream().collect(Collectors.toMap(WorkflowChain::id, Function.identity()));
    }

    public List<String> chainIds() {
        return chains.keySet().stream().sorted().toList();
    }

    public WorkflowChain chain(String chainId) {
        WorkflowChain chain = chains.get(normalize(chainId));
        if (chain == null) {
            throw new IllegalArgumentException("未知链路: " + chainId);
        }
        return chain;
    }

    public String defaultYaml(String chainId) throws IOException {
        String normalized = normalize(chainId);
        if (!chains.containsKey(normalized)) {
            throw new IllegalArgumentException("未知链路: " + chainId);
        }
        String configDir = properties.getConfigDir();
        String fileName = normalized + ".yml";
        Resource resource;
        if (configDir != null && configDir.startsWith("classpath:")) {
            String base = configDir.endsWith("/") ? configDir : configDir + "/";
            resource = resourceLoader.getResource(base + fileName);
        } else {
            String base = configDir == null || configDir.isBlank() ? "chains" : configDir;
            resource = resourceLoader.getResource("file:" + base + "/" + fileName);
        }
        if (!resource.exists()) {
            throw new IllegalStateException("未找到链路配置: " + fileName);
        }
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    public Map<String, Object> defaultValues(String chainId) throws IOException {
        Map<String, Object> yaml = YAML_MAPPER.readValue(defaultYaml(chainId), MAP_TYPE);
        Map<String, Object> values = new LinkedHashMap<>();
        flatten("", yaml, values);
        return values;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> source, Map<String, Object> target) {
        source.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            String flattenedKey = prefix.isBlank() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?> map) {
                flatten(flattenedKey, (Map<String, Object>) map, target);
            } else {
                target.put(flattenedKey, value);
            }
        });
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
