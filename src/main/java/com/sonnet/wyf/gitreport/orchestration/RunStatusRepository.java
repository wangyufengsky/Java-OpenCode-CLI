package com.sonnet.wyf.gitreport.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class RunStatusRepository {
    private final ObjectMapper objectMapper;

    public RunStatusRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(Path statusPath, Map<String, Object> status) throws IOException {
        Files.createDirectories(statusPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(statusPath.toFile(), status);
    }
}
