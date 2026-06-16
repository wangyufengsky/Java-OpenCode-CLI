package com.sonnet.wyf.gitreport.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.GitReportProperties.SynthesisInput;
import com.sonnet.wyf.gitreport.core.GitReportConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SynthesisInputWriter {
    private final ObjectMapper objectMapper;

    public SynthesisInputWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path write(
            Path output,
            Map<String, Object> summary,
            Map<String, Object> indexInputs,
            Map<String, Object> qualityScores,
            SynthesisInput options
    ) throws IOException {
        SynthesisInput limits = options == null ? new SynthesisInput() : options;
        Map<String, Map<String, Object>> summaryByAuthor = mapByAuthorKey(listOfMaps(summary.get("ranking")));
        Map<String, Map<String, Object>> scoreByAuthor = mapByAuthorKey(listOfMaps(qualityScores.get("rankings")));
        List<Map<String, Object>> authors = new ArrayList<>();
        List<Map<String, Object>> allSnippets = new ArrayList<>();
        int remainingSnippets = positive(limits.getSnippetsTotal());

        for (Map<String, Object> task : listOfMaps(indexInputs.get("tasks"))) {
            String authorKey = string(task.get("author_key"));
            Map<String, Object> qualitySummary = readMap(Path.of(string(task.get("quality_summary_json"))));
            String report = Files.readString(Path.of(string(task.get("report_md"))));
            Map<String, Object> author = new LinkedHashMap<>();
            author.put("author_key", authorKey);
            author.put("author", task.get("author"));
            author.put("report_markdown_link", task.get("report_markdown_link"));
            author.put("base_summary", summaryByAuthor.getOrDefault(authorKey, Map.of()));
            author.put("quality_ranking", scoreByAuthor.getOrDefault(authorKey, Map.of()));
            author.put("quality_summary", compactQualitySummary(qualitySummary));
            author.put("person_report_excerpt", excerpt(report, positive(limits.getPersonReportExcerptChars())));
            List<Map<String, Object>> authorSnippets = compactSnippets(qualitySummary, positive(limits.getSnippetsPerAuthor()), positive(limits.getSnippetLines()));
            author.put("code_snippets", authorSnippets);
            authors.add(author);
            for (Map<String, Object> snippet : authorSnippets) {
                if (remainingSnippets-- <= 0) {
                    break;
                }
                Map<String, Object> withAuthor = new LinkedHashMap<>(snippet);
                withAuthor.put("author_key", authorKey);
                withAuthor.put("author", task.get("author"));
                withAuthor.put("report_markdown_link", task.get("report_markdown_link"));
                allSnippets.add(withAuthor);
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generated_at", OffsetDateTime.now().toString());
        root.put("metadata", summary.get("metadata"));
        root.put("totals", summary.get("totals"));
        root.put("final_report", indexInputs.get("final_report"));
        root.put("final_report_marker", indexInputs.getOrDefault("final_report_marker", GitReportConstants.REPORT_MARKER));
        root.put("quality_scores", qualityScores);
        root.put("authors", authors);
        root.put("code_snippets", allSnippets);
        Files.createDirectories(output.toAbsolutePath().getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), root);
        return output;
    }

    private Map<String, Object> compactQualitySummary(Map<String, Object> qualitySummary) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("author", "status", "summary", "findings", "positive_signals", "risk_signals", "unverified")) {
            if (qualitySummary.containsKey(key)) {
                result.put(key, qualitySummary.get(key));
            }
        }
        return result;
    }

    private List<Map<String, Object>> compactSnippets(Map<String, Object> qualitySummary, int limit, int maxLines) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> snippet : listOfMaps(qualitySummary.get("code_snippets"))) {
            if (result.size() >= limit) {
                break;
            }
            Map<String, Object> compact = new LinkedHashMap<>(snippet);
            compact.put("snippet", firstLines(string(snippet.get("snippet")), maxLines));
            result.add(compact);
        }
        return result;
    }

    private Map<String, Map<String, Object>> mapByAuthorKey(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = string(row.get("author_key"));
            if (!key.isBlank()) {
                result.put(key, row);
            }
        }
        return result;
    }

    private String excerpt(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "\n...[truncated]";
    }

    private String firstLines(String value, int maxLines) {
        return value.lines().limit(maxLines).reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private Map<String, Object> readMap(Path path) throws IOException {
        return objectMapper.readValue(path.toFile(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private int positive(int value) {
        return Math.max(0, value);
    }
}
