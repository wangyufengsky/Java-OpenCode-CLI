package com.sonnet.wyf.gitreport.console;

import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ChainCatalog {
    private final ResourceLoader resourceLoader;
    private final OpenCodeRunnerProperties properties;
    private final Map<String, WorkflowChain> chains;

    public ChainCatalog(ResourceLoader resourceLoader, OpenCodeRunnerProperties properties, List<WorkflowChain> chains) {
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

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
