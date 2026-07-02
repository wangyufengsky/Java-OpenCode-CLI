package com.sonnet.wyf.gitreport.opencode;

import java.util.LinkedHashMap;
import java.util.Map;

final class OpenCodeModelMapper {
    private OpenCodeModelMapper() {
    }

    static Map<String, Object> sessionModelObject(String model) {
        ModelRef ref = parseModelRef(model);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerID", ref.providerId());
        result.put("id", ref.modelId());
        return result;
    }

    static Map<String, Object> promptModelObject(String model) {
        ModelRef ref = parseModelRef(model);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerID", ref.providerId());
        result.put("modelID", ref.modelId());
        return result;
    }

    private static ModelRef parseModelRef(String model) {
        String trimmed = model.trim();
        int slash = trimmed.indexOf('/');
        if (slash <= 0 || slash == trimmed.length() - 1) {
            throw new IllegalArgumentException("opencode session-model must use provider/model format when set: " + model);
        }
        return new ModelRef(trimmed.substring(0, slash), trimmed.substring(slash + 1));
    }

    private record ModelRef(String providerId, String modelId) {
    }
}
