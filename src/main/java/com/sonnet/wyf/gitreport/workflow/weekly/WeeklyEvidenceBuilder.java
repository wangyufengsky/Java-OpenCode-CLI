package com.sonnet.wyf.gitreport.workflow.weekly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.GitReportProperties;
import com.sonnet.wyf.gitreport.preparation.GitReportPreparation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WeeklyEvidenceBuilder {
    public static final String SCHEMA_VERSION = "weekly-engineering-report/v1";

    private final ObjectMapper objectMapper;
    private final GitReportPreparation gitReportPreparation;

    public WeeklyEvidenceBuilder(ObjectMapper objectMapper, GitReportPreparation gitReportPreparation) {
        this.objectMapper = objectMapper;
        this.gitReportPreparation = gitReportPreparation;
    }

    public Path build(WeeklyEngineeringReportProperties properties, LocalDate runDate) throws Exception {
        Path out = properties.getPaths().getOut().toAbsolutePath().normalize();
        Files.createDirectories(out);
        BuildContext context = new BuildContext(properties, runDate, out);
        Map<String, Object> evidence = context.build();
        Path evidencePath = out.resolve("weekly-evidence.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(evidencePath.toFile(), evidence);
        return evidencePath;
    }

    private class BuildContext {
        private final WeeklyEngineeringReportProperties properties;
        private final LocalDate runDate;
        private final Path out;
        private final Map<String, Object> sourceRuns = new LinkedHashMap<>();
        private final List<Map<String, Object>> dataQualityIssues = new ArrayList<>();
        private final List<Map<String, Object>> people = new ArrayList<>();
        private final List<Map<String, Object>> completedScope = new ArrayList<>();
        private final List<Map<String, Object>> risks = new ArrayList<>();
        private final List<Map<String, Object>> actionItems = new ArrayList<>();
        private final List<Map<String, Object>> contributionDistribution = new ArrayList<>();
        private final List<Map<String, Object>> riskConcentration = new ArrayList<>();
        private int riskSequence = 1;
        private int actionSequence = 1;

        private BuildContext(WeeklyEngineeringReportProperties properties, LocalDate runDate, Path out) {
            this.properties = properties;
            this.runDate = runDate;
            this.out = out;
        }

        private Map<String, Object> build() throws Exception {
            Path weeklyGitOut = prepareWeeklyGitEvidence();
            readWeeklyGitEvidence(weeklyGitOut);

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schema_version", SCHEMA_VERSION);
            root.put("generated_at", OffsetDateTime.now().toString());
            root.put("week", week());
            root.put("project", project());
            root.put("source_runs", sourceRuns);
            root.put("project_weekly", projectWeekly());
            root.put("team_risk", teamRisk());
            root.put("people", people);
            root.put("risks", risks);
            root.put("action_items", actionItems);
            root.put("data_quality", dataQuality());
            return root;
        }

        private Path prepareWeeklyGitEvidence() throws Exception {
            Path weeklyGitOut = out.resolve("sources").resolve("weekly-git");
            GitReportProperties gitProperties = new GitReportProperties();
            gitProperties.getProject().setId(properties.getProject().getId());
            gitProperties.getProject().setName(properties.getProject().getName());
            gitProperties.getProject().setRunId(properties.effectiveWeekLabel(runDate));
            gitProperties.getPaths().setRepo(properties.getProject().getRepo());
            gitProperties.getPaths().setOut(weeklyGitOut);
            gitProperties.getGit().setSince(properties.effectiveWeekStart(runDate));
            gitProperties.getGit().setUntil(properties.effectiveWeekEnd(runDate));
            gitProperties.getGit().setRevision(properties.getProject().getRevision());
            gitProperties.getGit().setIncludeMerges(properties.getGit().isIncludeMerges());
            gitProperties.getGit().setAuthorMap(properties.getGit().getAuthorMap());
            gitProperties.getGit().setInclude(properties.getGit().getInclude());
            gitProperties.getGit().setExclude(properties.getGit().getExclude());
            gitProperties.getDetailInput().setTopFiles(properties.getDetailInput().getTopFiles());
            gitProperties.getDetailInput().setCommits(properties.getDetailInput().getCommits());
            gitProperties.getDetailInput().setChangedRegions(properties.getDetailInput().getChangedRegions());
            gitProperties.getDetailInput().setChangedRegionLines(properties.getDetailInput().getChangedRegionLines());
            gitReportPreparation.prepare(gitProperties);
            sourceRuns.put("weekly_git", Map.of(
                    "status", "generated",
                    "summary_json", weeklyGitOut.resolve("summary.json").toString(),
                    "index_inputs_json", weeklyGitOut.resolve("index_inputs.json").toString(),
                    "details_json", weeklyGitOut.resolve("details.json").toString()
            ));
            return weeklyGitOut;
        }

        private void readWeeklyGitEvidence(Path weeklyGitOut) throws IOException {
            Map<String, Object> summary = readMap(weeklyGitOut.resolve("summary.json"));
            Map<String, Object> indexInputs = readMap(weeklyGitOut.resolve("index_inputs.json"));

            for (Map<String, Object> task : listOfMaps(indexInputs.get("tasks"))) {
                String authorKey = string(task.get("author_key"));
                String author = string(task.get("author"));
                Map<String, Object> detail = readOptionalMap(Path.of(string(task.get("detail_json"))));
                Map<String, Object> baseSummary = rankingByAuthor(summary, authorKey);

                List<Map<String, Object>> commits = listOfMaps(detail.get("commits"));
                List<Map<String, Object>> topFiles = listOfMaps(detail.get("top_files"));
                List<String> evidenceRefs = commitEvidenceRefs(authorKey, commits);
                Map<String, Object> workScope = new LinkedHashMap<>();
                workScope.put("commits", commits);
                workScope.put("top_files", topFiles);
                workScope.put("transactions", List.of());
                workScope.put("modules", List.of());
                workScope.put("work_types", inferWorkTypes(commits, topFiles));

                List<Map<String, Object>> highlights = new ArrayList<>();
                if (!commits.isEmpty() || !topFiles.isEmpty()) {
                    highlights.add(Map.of(
                            "title", "本周代码提交",
                            "reason", "本周提交 %d 次，主要涉及 %s。".formatted(number(baseSummary.get("commit_count")), firstNonBlank(joinPaths(topFiles), "未识别到主要文件")),
                            "evidence_refs", evidenceRefs
                    ));
                }

                Map<String, Object> qualitySignals = new LinkedHashMap<>();
                qualitySignals.put("positive", List.of());
                qualitySignals.put("risks", List.of());
                qualitySignals.put("unverified", List.of("weekly-engineering-report 只重新生成本周 Git 代码事实，不消费历史代码审查结论。"));
                qualitySignals.put("low_quality_snippets", List.of());

                Map<String, Object> person = new LinkedHashMap<>();
                person.put("author_key", authorKey);
                person.put("author", author);
                person.put("work_scope", workScope);
                person.put("contribution_highlights", highlights);
                person.put("quality_signals", qualitySignals);
                person.put("collaboration_and_impact", Map.of(
                        "shared_modules_touched", List.of(),
                        "hotspot_files_touched", topFiles.stream().map(row -> string(row.get("path"))).filter(value -> !value.isBlank()).limit(5).toList(),
                        "cross_author_areas", List.of()
                ));
                person.put("next_week_suggestions", personSuggestions(commits, topFiles));
                person.put("assessment_boundary", "仅作为研发负责人 1:1、辅导和绩效校准的证据包，不直接给出绩效结论。");
                people.add(person);

                Map<String, Object> contribution = new LinkedHashMap<>();
                contribution.put("author_key", authorKey);
                contribution.put("author", author);
                contribution.put("primary_areas", topFiles.stream().map(row -> string(row.get("path"))).filter(value -> !value.isBlank()).limit(3).toList());
                contribution.put("workload_evidence", Map.of(
                        "commit_count", number(baseSummary.get("commit_count")),
                        "non_comment_churn", number(baseSummary.get("non_comment_churn")),
                        "changed_files", number(baseSummary.get("unique_file_count"))
                ));
                contribution.put("interpretation", "用于识别本周投入范围和协作风险，不作为绩效定级。");
                contribution.put("quality_ranking", Map.of("status", "not_evaluated", "reason", "周报链路不执行代码审查或质量打分"));
                contributionDistribution.add(contribution);
                for (Map<String, Object> topFile : topFiles.stream().limit(5).toList()) {
                    completedScope.add(Map.of(
                            "type", "code_change",
                            "name", string(topFile.get("path")),
                            "description", author + " 本周改动，non_comment_churn=" + number(topFile.get("non_comment_churn")),
                            "evidence_refs", evidenceRefs
                    ));
                }
            }
        }

        private Map<String, Object> week() {
            return Map.of(
                    "start", properties.effectiveWeekStart(runDate).toString(),
                    "end", properties.effectiveWeekEnd(runDate).toString(),
                    "label", properties.effectiveWeekLabel(runDate)
            );
        }

        private Map<String, Object> project() {
            return Map.of(
                    "id", string(properties.getProject().getId()),
                    "name", string(properties.getProject().getName()),
                    "repo", pathString(properties.getProject().getRepo()),
                    "revision", string(properties.getProject().getRevision()),
                    "out", out.toString()
            );
        }

        private Map<String, Object> projectWeekly() {
            return Map.of(
                    "overall_status", risks.stream().anyMatch(risk -> List.of("P0", "P1").contains(risk.get("severity"))) ? "at_risk" : "normal",
                    "executive_summary", "本周周报由 weekly-engineering-report 按周窗口重新统计 Git 提交生成，不消费历史代码审查产物。",
                    "completed_scope", completedScope,
                    "scope_changes", List.of(),
                    "delivery_risks", risks.stream().filter(risk -> Boolean.TRUE.equals(risk.get("carry_to_next_week"))).toList(),
                    "decisions_needed", List.of(),
                    "next_week_plan_suggestions", List.of("围绕本周热点文件补齐验证记录", "对本周 changed regions 安排当周 review")
            );
        }

        private Map<String, Object> teamRisk() {
            return Map.of(
                    "team_summary", "团队风险评估聚合本周 Git 贡献范围和待补充质量验证边界。",
                    "contribution_distribution", contributionDistribution,
                    "risk_concentration", riskConcentration,
                    "quality_aggregate", Map.of(
                            "dimensions", Map.of(
                                    "code_standard", Map.of(),
                                    "maintainability", Map.of(),
                                    "risk_control", Map.of(),
                                    "reviewability", Map.of()
                            ),
                            "top_recurring_rules", List.of()
                    ),
                    "review_recommendations", List.of("如需代码质量结论，应基于本周 Git changed regions 另行触发当周代码审查，不复用历史审查报告")
            );
        }

        private Map<String, Object> dataQuality() {
            return Map.of(
                    "status", dataQualityIssues.isEmpty() ? "clean" : "partial",
                    "issues", dataQualityIssues,
                    "known_biases", List.of(
                            "提交量不等于业务价值",
                            "Git 无法覆盖沟通、排障、设计和评审投入",
                            "未接入需求/缺陷系统时无法判断计划内外工作",
                            "当前周报不输出自动代码质量结论，只记录本周 Git 事实和待复核边界"
                    )
            );
        }

        private void addRisk(String source, String severity, String category, String title, String description, String impact, String owner, List<String> evidenceRefs, String recommendedAction, boolean carry) {
            Map<String, Object> risk = new LinkedHashMap<>();
            String riskId = "RISK-" + properties.effectiveWeekLabel(runDate).replace("-", "") + "-%03d".formatted(riskSequence++);
            risk.put("risk_id", riskId);
            risk.put("source", source);
            risk.put("severity", severity);
            risk.put("category", category);
            risk.put("title", title);
            risk.put("description", description);
            risk.put("impact", impact);
            risk.put("owner", owner);
            risk.put("status", "new");
            risk.put("evidence_refs", evidenceRefs);
            risk.put("recommended_action", recommendedAction);
            risk.put("carry_to_next_week", carry);
            risks.add(risk);
        }

        private Map<String, Object> action(String audience, String owner, String priority, String title, String description, String doneDefinition, List<String> evidenceRefs) {
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("action_id", "ACT-" + properties.effectiveWeekLabel(runDate).replace("-", "") + "-%03d".formatted(actionSequence++));
            action.put("source_risk_id", "");
            action.put("audience", audience);
            action.put("owner", owner);
            action.put("priority", priority);
            action.put("title", title);
            action.put("description", description);
            action.put("due_date", "");
            action.put("done_definition", doneDefinition);
            action.put("evidence_refs", evidenceRefs);
            return action;
        }
    }

    private Map<String, Object> readOptionalMap(Path path) throws IOException {
        if (path == null || path.toString().isBlank() || !Files.exists(path)) {
            return Map.of();
        }
        return readMap(path);
    }

    private Map<String, Object> readMap(Path path) throws IOException {
        return objectMapper.readValue(path.toFile(), new TypeReference<>() {});
    }

    private Map<String, Object> rankingByAuthor(Map<String, Object> summary, String authorKey) {
        for (Map<String, Object> row : listOfMaps(summary.get("ranking"))) {
            if (authorKey.equals(string(row.get("author_key")))) {
                return row;
            }
        }
        return Map.of();
    }

    private List<Map<String, Object>> personSuggestions(List<Map<String, Object>> commits, List<Map<String, Object>> topFiles) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        if (!commits.isEmpty()) {
            suggestions.add(Map.of("suggestion", "为本周主要改动补齐验证记录", "reason", "周报只确认 Git 事实，质量结论需要当周验证证据支撑", "priority", "high"));
        }
        if (topFiles.size() >= 3) {
            suggestions.add(Map.of("suggestion", "对改动集中的文件安排 review", "reason", "本周主要改动集中在多个热点文件", "priority", "medium"));
        }
        return suggestions;
    }

    private List<String> commitEvidenceRefs(String authorKey, List<Map<String, Object>> commits) {
        return commits.stream()
                .map(commit -> firstNonBlank(string(commit.get("hash")), string(commit.get("short_hash"))))
                .filter(hash -> !hash.isBlank())
                .map(hash -> "git:" + authorKey + ":" + hash)
                .toList();
    }

    private String joinPaths(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> string(row.get("path")))
                .filter(value -> !value.isBlank())
                .limit(5)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private List<String> inferWorkTypes(List<Map<String, Object>> commits, List<Map<String, Object>> topFiles) {
        List<String> types = new ArrayList<>();
        for (Map<String, Object> commit : commits) {
            String subject = string(commit.get("subject")).toLowerCase(Locale.ROOT);
            if (subject.contains("fix") || subject.contains("修复")) {
                types.add("defect_fix");
            } else if (subject.contains("refactor") || subject.contains("重构")) {
                types.add("refactor");
            } else if (subject.contains("test") || subject.contains("测试")) {
                types.add("test");
            }
        }
        for (Map<String, Object> file : topFiles) {
            String path = string(file.get("path"));
            if (path.endsWith(".md")) {
                types.add("docs");
            } else if (path.endsWith(".yml") || path.endsWith(".yaml") || path.endsWith(".properties")) {
                types.add("config");
            }
        }
        if (types.isEmpty()) {
            types.add("component");
        }
        return types.stream().distinct().toList();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String pathString(Path path) {
        return path == null ? "" : path.toString();
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
