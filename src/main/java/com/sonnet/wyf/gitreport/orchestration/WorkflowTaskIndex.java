package com.sonnet.wyf.gitreport.orchestration;

import java.util.List;
import java.util.Map;

public class WorkflowTaskIndex {
    private final List<Map<String, Object>> tasks;

    private WorkflowTaskIndex(List<Map<String, Object>> tasks) {
        this.tasks = tasks;
    }

    public static WorkflowTaskIndex fromIndexInputs(Map<String, Object> indexInputs) {
        return new WorkflowTaskIndex(listOfMaps(indexInputs.get("tasks")));
    }

    public Map<String, Object> gitAuthorTask(String authorKey) {
        return tasks.stream()
                .filter(task -> authorKey.equals(task.get("author_key")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("author task not found in index_inputs.json: " + authorKey));
    }

    public Map<String, Object> smartEsbReviewTask(String reviewType, String name) {
        return tasks.stream()
                .filter(task -> reviewType.equals(task.get("review_type")))
                .filter(task -> name.equals(taskName(task)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(reviewType + " task missing from index_inputs.json: " + name));
    }

    private String taskName(Map<String, Object> task) {
        Object module = task.get("module");
        if (module != null) {
            return module.toString();
        }
        return task.get("transaction").toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
