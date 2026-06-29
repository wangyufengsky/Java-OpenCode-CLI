package com.sonnet.wyf.gitreport.workflow.weekly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class WeeklyEvidenceValidator {
    private static final List<String> REQUIRED_TOP_LEVEL = List.of(
            "schema_version",
            "week",
            "project",
            "source_runs",
            "project_weekly",
            "team_risk",
            "people",
            "risks",
            "action_items",
            "data_quality"
    );

    private final ObjectMapper objectMapper;

    public WeeklyEvidenceValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(Path evidencePath) throws IOException {
        if (!Files.exists(evidencePath)) {
            throw new IllegalStateException("weekly evidence missing: " + evidencePath);
        }
        Map<String, Object> root = objectMapper.readValue(evidencePath.toFile(), new TypeReference<>() {});
        for (String field : REQUIRED_TOP_LEVEL) {
            if (!root.containsKey(field)) {
                throw new IllegalStateException("weekly evidence missing required field: " + field);
            }
        }
        if (!WeeklyEvidenceBuilder.SCHEMA_VERSION.equals(root.get("schema_version"))) {
            throw new IllegalStateException("weekly evidence schema_version mismatch: " + root.get("schema_version"));
        }
        for (String field : List.of("people", "risks", "action_items")) {
            if (!(root.get(field) instanceof List<?>)) {
                throw new IllegalStateException("weekly evidence field must be array: " + field);
            }
        }
        for (String field : List.of("week", "project", "source_runs", "project_weekly", "team_risk", "data_quality")) {
            if (!(root.get(field) instanceof Map<?, ?>)) {
                throw new IllegalStateException("weekly evidence field must be object: " + field);
            }
        }
    }
}
