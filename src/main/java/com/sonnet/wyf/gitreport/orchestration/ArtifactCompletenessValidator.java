package com.sonnet.wyf.gitreport.orchestration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class ArtifactCompletenessValidator {
    public Validation validateFile(String label, Path path, List<String> placeholders) {
        try {
            if (!Files.exists(path)) {
                return Validation.failed(label + " missing: " + path);
            }
            String content = Files.readString(path);
            if (content.isBlank()) {
                return Validation.failed(label + " is blank: " + path);
            }
            for (String placeholder : placeholders == null ? List.<String>of() : placeholders) {
                if (content.contains(placeholder)) {
                    return Validation.failed(label + " still contains template placeholder: " + path);
                }
            }
            if (content.contains("{{")) {
                return Validation.failed(label + " contains unresolved template placeholder: " + path);
            }
            return Validation.success();
        } catch (Exception exception) {
            return Validation.failed(label + " validation failed: " + exception.getMessage());
        }
    }

    public Validation validateOutputs(Map<?, ?> outputPlaceholders, Function<String, Path> outputPathResolver) {
        if (outputPlaceholders == null || outputPlaceholders.isEmpty()) {
            return Validation.failed("output_placeholders missing or invalid");
        }
        for (Map.Entry<?, ?> entry : outputPlaceholders.entrySet()) {
            String outputKey = entry.getKey().toString();
            List<String> placeholders = entry.getValue() instanceof List<?> values
                    ? values.stream().map(Object::toString).toList()
                    : List.of();
            Validation validation = validateFile(outputKey, outputPathResolver.apply(outputKey), placeholders);
            if (!validation.ok()) {
                return validation;
            }
        }
        return Validation.success();
    }

    public record Validation(boolean ok, String error) {
        public static Validation success() {
            return new Validation(true, "");
        }

        public static Validation failed(String error) {
            return new Validation(false, error == null ? "" : error);
        }
    }
}
