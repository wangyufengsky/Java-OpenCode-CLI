package com.sonnet.wyf.gitreport.runner;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.file.Path;

public class ChainConfigLoader {
    private final ResourceLoader resourceLoader;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .registerModule(pathModule())
            .findAndRegisterModules();

    public ChainConfigLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public <T> T load(String configDir, String chainId, Class<T> type) throws IOException {
        String fileName = chainId + ".yml";
        if (configDir != null && configDir.startsWith("classpath:")) {
            String base = configDir.endsWith("/") ? configDir : configDir + "/";
            Resource resource = resourceLoader.getResource(base + fileName);
            if (!resource.exists()) {
                throw new IllegalStateException("chain config not found: " + base + fileName);
            }
            try (var inputStream = resource.getInputStream()) {
                return yamlMapper.readValue(inputStream, type);
            }
        }
        Path path = Path.of(configDir == null || configDir.isBlank() ? "chains" : configDir).resolve(fileName);
        if (!java.nio.file.Files.exists(path)) {
            throw new IllegalStateException("chain config not found: " + path);
        }
        return yamlMapper.readValue(path.toFile(), type);
    }

    private static SimpleModule pathModule() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Path.class, new JsonDeserializer<>() {
            @Override
            public Path deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                String value = parser.getValueAsString();
                return value == null || value.isBlank() ? null : Path.of(value);
            }
        });
        return module;
    }
}
