package com.sonnet.wyf.gitreport.workflow.weekly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.util.JsonMaps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class WeeklyReportRenderer {
    private final ObjectMapper objectMapper;
    private final WeeklyCodeReviewOutputValidator reviewOutputValidator;

    public WeeklyReportRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.reviewOutputValidator = new WeeklyCodeReviewOutputValidator(objectMapper);
    }

    public void render(Path evidencePath) throws IOException {
        Map<String, Object> evidence = objectMapper.readValue(evidencePath.toFile(), new TypeReference<>() {});
        Path out = evidencePath.toAbsolutePath().normalize().getParent();
        Files.createDirectories(out);
        Aggregation aggregation = aggregate(evidence);
        writeCodeReviewReports(out, evidence, aggregation);
        writePeopleReports(out, evidence, aggregation);
        writeFinalReports(out, evidence, aggregation);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("traceability.json").toFile(), Map.of(
                "schema_version", "weekly-traceability/v1",
                "regions", aggregation.traceability
        ));
        Files.writeString(out.resolve("data-quality.md"), renderDataQuality(evidence));
    }

    private Aggregation aggregate(Map<String, Object> evidence) throws IOException {
        Aggregation aggregation = new Aggregation();
        for (Map<String, Object> batch : listOfMaps(evidence.get("review_batches"))) {
            Path summaryPath = Path.of(string(batch.get("summary_json")));
            var validation = reviewOutputValidator.validate(batch, summaryPath);
            if (!validation.ok()) {
                throw new IllegalStateException("review batch output incomplete: " + string(batch.get("batch_id")) + ": " + validation.error());
            }
            Map<String, Object> summary = objectMapper.readValue(summaryPath.toFile(), new TypeReference<>() {});
            aggregation.batchSummaries.add(summary);
            String batchId = string(batch.get("batch_id"));
            String reviewMd = string(batch.get("review_md"));
            String module = moduleOf(batch);
            for (Map<String, Object> finding : listOfMaps(summary.get("findings"))) {
                finding.put("_batch_id", batchId);
                finding.put("_review_md", reviewMd);
                finding.put("_module", module);
                aggregation.findings.add(finding);
                aggregation.findingsByModule.computeIfAbsent(module, ignored -> new ArrayList<>()).add(finding);
            }
            for (Map<String, Object> region : listOfMaps(batch.get("changed_regions"))) {
                aggregation.regionsById.put(string(region.get("region_id")), region);
                aggregation.traceability.add(Map.of(
                        "region_id", string(region.get("region_id")),
                        "batch_id", batchId,
                        "unit_id", firstNonBlank(string(batch.get("unit_id")), batchId),
                        "module", module,
                        "author_key", string(region.get("author_key")),
                        "commit", string(region.get("commit")),
                        "file", string(region.get("file")),
                        "findings", listOfMaps(summary.get("findings")).stream()
                                .filter(finding -> string(region.get("region_id")).equals(string(finding.get("region_id"))))
                                .map(finding -> string(finding.get("id")))
                                .toList()
                ));
            }
        }
        for (Map<String, Object> author : listOfMaps(mapValue(evidence.get("weekly_git")).get("authors"))) {
            aggregation.people.put(string(author.get("author_key")), new PersonAggregate(author));
        }
        for (Map<String, Object> finding : aggregation.findings) {
            String authorKey = string(finding.get("author_key"));
            aggregation.people.computeIfAbsent(authorKey, ignored -> new PersonAggregate(Map.of("author_key", authorKey, "author", authorKey)))
                    .findings.add(finding);
        }
        aggregation.rankPeople();
        return aggregation;
    }

    private void writeCodeReviewReports(Path out, Map<String, Object> evidence, Aggregation aggregation) throws IOException {
        Path dir = out.resolve("code-review");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("overview.md"), renderCodeReviewOverview(evidence, aggregation));
        Files.writeString(dir.resolve("p0-p1-p2-issues.md"), renderP0P1P2Issues(aggregation));
        Files.writeString(dir.resolve("code-standards.md"), renderCodeStandards(aggregation));
        Files.writeString(dir.resolve("hotspots.md"), renderHotspots(aggregation));
        Files.writeString(dir.resolve("full-findings.md"), renderFullFindings(dir, aggregation));
        Path modulesDir = dir.resolve("modules");
        Files.createDirectories(modulesDir);
        for (Map.Entry<String, List<Map<String, Object>>> entry : aggregation.findingsByModule.entrySet()) {
            Files.writeString(modulesDir.resolve(slug(entry.getKey()) + ".md"), renderModuleReport(modulesDir, entry.getKey(), entry.getValue()));
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("index.json").toFile(), Map.of(
                "schema_version", "weekly-code-review-index/v1",
                "finding_count", aggregation.findings.size(),
                "findings", aggregation.findings,
                "batch_summaries", aggregation.batchSummaries
        ));
    }

    private void writePeopleReports(Path out, Map<String, Object> evidence, Aggregation aggregation) throws IOException {
        List<Map<String, Object>> rankings = aggregation.people.values().stream().map(PersonAggregate::rankingRow).toList();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("quality-scores.json").toFile(), Map.of(
                "schema_version", "weekly-quality-scores/v1",
                "rankings", rankings
        ));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("code-review/author-summaries.json").toFile(), Map.of(
                "schema_version", "weekly-author-summaries/v1",
                "authors", rankings
        ));
        Files.writeString(out.resolve("people-ranking.md"), renderPeopleRanking(rankings));
        for (PersonAggregate person : aggregation.people.values()) {
            Path personDir = out.resolve("people").resolve(firstNonBlank(person.authorKey(), "unknown"));
            Files.createDirectories(personDir);
            Files.writeString(personDir.resolve("weekly-person-report.md"), renderPersonReport(person, personDir, out));
        }
    }

    private void writeFinalReports(Path out, Map<String, Object> evidence, Aggregation aggregation) throws IOException {
        Files.writeString(out.resolve("weekly-report.md"), renderWeeklyReport(evidence, aggregation));
        Files.writeString(out.resolve("team-risk-assessment.md"), renderTeamRiskAssessment(evidence, aggregation));
    }

    private String renderCodeReviewOverview(Map<String, Object> evidence, Aggregation aggregation) {
        Map<String, Object> project = mapValue(evidence.get("project"));
        Map<String, Object> week = mapValue(evidence.get("week"));
        StringBuilder md = new StringBuilder("# 代码维度审查总览：").append(string(project.get("name"))).append("\n\n");
        md.append("- 周期：").append(string(week.get("label"))).append("（").append(string(week.get("start"))).append(" 至 ").append(string(week.get("end"))).append("）\n");
        md.append("- 审查批次：").append(listOfMaps(evidence.get("review_batches")).size()).append("\n");
        md.append("- 问题总数：").append(aggregation.findings.size()).append("\n\n");
        appendFindingTable(md, aggregation.findings);
        return md.toString();
    }

    private String renderP0P1P2Issues(Aggregation aggregation) {
        StringBuilder md = new StringBuilder("# P0/P1/P2 级别问题报告\n\n");
        appendFindingTable(md, aggregation.findings.stream()
                .sorted(Comparator.comparingInt(row -> severityOrder(string(row.get("severity")))))
                .toList());
        return md.toString();
    }

    private String renderCodeStandards(Aggregation aggregation) {
        StringBuilder md = new StringBuilder("# 代码规范报告\n\n");
        appendFindingTable(md, aggregation.findings.stream()
                .filter(row -> "code_standard".equals(string(row.get("dimension"))))
                .toList());
        return md.toString();
    }

    private String renderHotspots(Aggregation aggregation) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> finding : aggregation.findings) {
            counts.put(string(finding.get("file")), counts.getOrDefault(string(finding.get("file")), 0) + 1);
        }
        StringBuilder md = new StringBuilder("# 代码审查热点\n\n");
        if (counts.isEmpty()) {
            md.append("统计窗口内未发现代码审查热点。\n");
            return md.toString();
        }
        md.append("| 文件 | 问题数 |\n| --- | ---: |\n");
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> md.append("| ").append(cell(entry.getKey())).append(" | ").append(entry.getValue()).append(" |\n"));
        return md.toString();
    }

    private String renderPeopleRanking(List<Map<String, Object>> rankings) {
        StringBuilder md = new StringBuilder("# 人员周度代码报告排名\n\n");
        md.append("| 最终排名 | 初始排名 | 人员 | 工作量分 | 质量调整 | 最终分 | P0 | P1 | P2 |\n");
        md.append("| ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (Map<String, Object> row : rankings) {
            md.append("| ").append(number(row.get("final_rank")))
                    .append(" | ").append(number(row.get("base_rank")))
                    .append(" | ").append(cell(row.get("author")))
                    .append(" | ").append(decimal(row.get("base_workload_score")))
                    .append(" | ").append(decimal(row.get("quality_adjustment_percent")))
                    .append(" | ").append(decimal(row.get("workload_score")))
                    .append(" | ").append(number(row.get("p0_count")))
                    .append(" | ").append(number(row.get("p1_count")))
                    .append(" | ").append(number(row.get("p2_count")))
                    .append(" |\n");
        }
        return md.toString();
    }

    private String renderFullFindings(Path reportDir, Aggregation aggregation) {
        StringBuilder md = new StringBuilder("# 全量代码审查问题\n\n");
        md.append("- [P0/P1/P2 级别问题](p0-p1-p2-issues.md)\n");
        md.append("- [代码审查热点](hotspots.md)\n\n");
        appendFindingTable(md, aggregation.findings, reportDir);
        return md.toString();
    }

    private String renderModuleReport(Path moduleDir, String module, List<Map<String, Object>> findings) {
        StringBuilder md = new StringBuilder("# 模块代码审查报告：").append(module).append("\n\n");
        md.append("- [返回全量代码审查问题](").append(relativeLink(moduleDir, moduleDir.getParent().resolve("full-findings.md"))).append(")\n\n");
        appendFindingTable(md, findings, moduleDir);
        return md.toString();
    }

    private String renderPersonReport(PersonAggregate person, Path personDir, Path out) {
        StringBuilder md = new StringBuilder("# 个人周报：").append(person.author()).append("\n\n");
        md.append("- [全量代码审查问题](").append(relativeLink(personDir, out.resolve("code-review/full-findings.md"))).append(")\n");
        md.append("- [团队作者工作排名](").append(relativeLink(personDir, out.resolve("people-ranking.md"))).append(")\n\n");
        md.append("## 统计窗口工作范围\n\n");
        md.append("- 提交数：").append(number(person.authorFacts.get("commit_count"))).append("\n");
        md.append("- 去注释变更量：").append(number(person.authorFacts.get("non_comment_churn"))).append("\n");
        md.append("- Top 文件：").append(joinPaths(listOfMaps(person.authorFacts.get("top_files")))).append("\n\n");
        md.append("## 代码审查问题\n\n");
        appendFindingTable(md, person.findings);
        md.append("\n## 下周建议\n\n");
        if (person.findings.isEmpty()) {
            md.append("统计窗口内未发现需要单独跟进的代码审查问题。\n");
        } else {
            md.append("- 优先处理 P0/P1 问题，并为关联 changed regions 补齐回归验证。\n");
        }
        md.append("\n## 证据边界\n\n");
        md.append("仅作为研发负责人 1:1、辅导和绩效校准的证据包，不直接给出绩效结论。\n");
        return md.toString();
    }

    private String renderWeeklyReport(Map<String, Object> evidence, Aggregation aggregation) {
        Map<String, Object> project = mapValue(evidence.get("project"));
        Map<String, Object> week = mapValue(evidence.get("week"));
        StringBuilder md = new StringBuilder("# 周度工程项目周报：").append(string(project.get("name"))).append("\n\n");
        md.append("## 项目经理周会重点\n\n");
        md.append("- 周期：").append(string(week.get("label"))).append("（").append(string(week.get("start"))).append(" 至 ").append(string(week.get("end"))).append("）\n");
        md.append("- 统计窗口审查批次：").append(listOfMaps(evidence.get("review_batches")).size()).append("\n");
        md.append("- 项目状态：").append(aggregation.hasP0OrP1() ? "at_risk" : "normal").append("\n\n");
        md.append("## 报告索引\n\n");
        md.append("- [全量代码审查问题](code-review/full-findings.md)\n");
        md.append("- [代码审查总览](code-review/overview.md)\n");
        md.append("- [作者工作排名](people-ranking.md)\n");
        md.append("- [团队贡献与风险辅助评估](team-risk-assessment.md)\n\n");
        md.append("## 统计窗口完成范围\n\n");
        appendHotFiles(md, evidence);
        md.append("\n## 主要风险\n\n");
        appendFindingTable(md, aggregation.findings.stream().filter(row -> List.of("P0", "P1").contains(string(row.get("severity")))).toList());
        md.append("\n## 下周展望\n\n");
        md.append("- 优先关闭 P0/P1 代码审查问题。\n");
        md.append("- 对统计窗口热点文件安排回归验证和风险复核。\n");
        return md.toString();
    }

    private String renderTeamRiskAssessment(Map<String, Object> evidence, Aggregation aggregation) {
        StringBuilder md = new StringBuilder("# 团队贡献与风险辅助评估\n\n");
        md.append("## 团队概览\n\n");
        md.append("本报告聚合统计窗口内 Git 改动与本链路即时代码审查结果，用于团队风险识别和辅导，不作为绩效定级。\n\n");
        md.append("- [项目周报](weekly-report.md)\n");
        md.append("- [作者工作排名](people-ranking.md)\n");
        md.append("- [全量代码审查问题](code-review/full-findings.md)\n\n");
        md.append("## 人员风险分布\n\n");
        md.append(renderPeopleRanking(aggregation.people.values().stream().map(PersonAggregate::rankingRow).toList()));
        md.append("\n## 高优先级问题\n\n");
        appendFindingTable(md, aggregation.findings.stream().filter(row -> List.of("P0", "P1").contains(string(row.get("severity")))).toList());
        return md.toString();
    }

    private String renderDataQuality(Map<String, Object> evidence) {
        Map<String, Object> dataQuality = mapValue(evidence.get("data_quality"));
        StringBuilder md = new StringBuilder("# 数据质量说明\n\n");
        md.append("- 状态：").append(string(dataQuality.get("status"))).append("\n\n");
        md.append("## 已知偏差\n\n");
        appendBullets(md, listValue(dataQuality.get("known_biases")), "统计窗口内未登记已知偏差。");
        return md.toString();
    }

    private void appendFindingTable(StringBuilder md, List<Map<String, Object>> findings) {
        appendFindingTable(md, findings, null);
    }

    private void appendFindingTable(StringBuilder md, List<Map<String, Object>> findings, Path linkBaseDir) {
        if (findings.isEmpty()) {
            md.append("统计窗口内未发现匹配条件的问题。\n");
            return;
        }
        md.append("| 级别 | 维度 | 文件 | 行号 | 人员 | 规则 | 建议 | 明细 |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map<String, Object> finding : findings) {
            String detail = "";
            if (linkBaseDir != null && !string(finding.get("_review_md")).isBlank()) {
                detail = "[review](" + relativeLink(linkBaseDir, Path.of(string(finding.get("_review_md")))) + ")";
            }
            md.append("| ").append(cell(finding.get("severity")))
                    .append(" | ").append(cell(finding.get("dimension")))
                    .append(" | ").append(cell(finding.get("file")))
                    .append(" | ").append(number(finding.get("line_start"))).append("-").append(number(finding.get("line_end")))
                    .append(" | ").append(cell(finding.get("author_key")))
                    .append(" | ").append(cell(finding.get("rule_id")))
                    .append(" | ").append(cell(finding.get("suggestion")))
                    .append(" | ").append(detail)
                    .append(" |\n");
        }
    }

    private void appendHotFiles(StringBuilder md, Map<String, Object> evidence) {
        List<Map<String, Object>> authors = listOfMaps(mapValue(evidence.get("weekly_git")).get("authors"));
        List<Map<String, Object>> files = authors.stream().flatMap(author -> listOfMaps(author.get("top_files")).stream()).toList();
        if (files.isEmpty()) {
            md.append("统计窗口内未发现可汇总的代码改动文件。\n");
            return;
        }
        md.append("| 文件 | 去注释变更量 |\n| --- | ---: |\n");
        files.stream().limit(10).forEach(file -> md.append("| ").append(cell(file.get("path"))).append(" | ").append(number(file.get("non_comment_churn"))).append(" |\n"));
    }

    private void appendBullets(StringBuilder md, List<?> values, String empty) {
        if (values.isEmpty()) {
            md.append(empty).append("\n");
            return;
        }
        for (Object value : values) {
            md.append("- ").append(string(value)).append("\n");
        }
    }

    private String joinPaths(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> string(row.get("path"))).filter(value -> !value.isBlank()).reduce((left, right) -> left + ", " + right).orElse("统计窗口内未发现");
    }

    private int severityOrder(String severity) {
        return switch (severity) {
            case "P0" -> 0;
            case "P1" -> 1;
            default -> 2;
        };
    }

    private String cell(Object value) {
        return string(value).replace("|", "\\|").replace("\n", "<br>");
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String moduleOf(Map<String, Object> batch) {
        Map<String, Object> group = mapValue(batch.get("group"));
        if (!string(group.get("module")).isBlank()) {
            return string(group.get("module"));
        }
        Map<String, Object> scope = mapValue(batch.get("scope"));
        return firstNonBlank(string(scope.get("path")), "unknown");
    }

    private String slug(String value) {
        String normalized = value.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return normalized.isBlank() ? "unknown" : normalized.substring(0, Math.min(80, normalized.length()));
    }

    private String relativeLink(Path fromDir, Path target) {
        return fromDir.toAbsolutePath().normalize()
                .relativize(target.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private String string(Object value) {
        return JsonMaps.string(value);
    }

    private int number(Object value) {
        return JsonMaps.number(value);
    }

    private double decimal(Object value) {
        return Math.round(JsonMaps.decimal(value) * 10.0) / 10.0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return JsonMaps.mapValue(value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return JsonMaps.listOfMaps(value);
    }

    private List<?> listValue(Object value) {
        return JsonMaps.listValue(value);
    }

    private static class Aggregation {
        private final List<Map<String, Object>> batchSummaries = new ArrayList<>();
        private final List<Map<String, Object>> findings = new ArrayList<>();
        private final List<Map<String, Object>> traceability = new ArrayList<>();
        private final Map<String, Map<String, Object>> regionsById = new LinkedHashMap<>();
        private final Map<String, List<Map<String, Object>>> findingsByModule = new LinkedHashMap<>();
        private final Map<String, PersonAggregate> people = new LinkedHashMap<>();

        private void rankPeople() {
            List<PersonAggregate> ranked = people.values().stream()
                    .sorted(Comparator.comparingDouble(PersonAggregate::adjustedScore).reversed())
                    .toList();
            for (int index = 0; index < ranked.size(); index++) {
                ranked.get(index).finalRank = index + 1;
            }
        }

        private boolean hasP0OrP1() {
            return findings.stream().anyMatch(finding -> List.of("P0", "P1").contains(Objects.toString(finding.get("severity"), "")));
        }
    }

    private static class PersonAggregate {
        private final Map<String, Object> authorFacts;
        private final List<Map<String, Object>> findings = new ArrayList<>();
        private int finalRank;

        private PersonAggregate(Map<String, Object> authorFacts) {
            this.authorFacts = authorFacts;
        }

        private String authorKey() {
            return Objects.toString(authorFacts.get("author_key"), "");
        }

        private String author() {
            return Objects.toString(authorFacts.get("author"), authorKey());
        }

        private double baseScore() {
            Object value = authorFacts.get("workload_score");
            return value instanceof Number number ? number.doubleValue() : 0.0;
        }

        private double qualityAdjustment() {
            int p0 = count("P0");
            int p1 = count("P1");
            int p2 = count("P2");
            return -(p0 * 20 + p1 * 10 + p2 * 3);
        }

        private double adjustedScore() {
            return Math.max(0, baseScore() + qualityAdjustment());
        }

        private int count(String severity) {
            return (int) findings.stream().filter(finding -> severity.equals(Objects.toString(finding.get("severity"), ""))).count();
        }

        private Map<String, Object> rankingRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("author_key", authorKey());
            row.put("author", author());
            row.put("base_rank", authorFacts.getOrDefault("rank", 0));
            row.put("final_rank", finalRank);
            row.put("base_workload_score", baseScore());
            row.put("quality_adjustment_percent", qualityAdjustment());
            row.put("workload_score", adjustedScore());
            row.put("p0_count", count("P0"));
            row.put("p1_count", count("P1"));
            row.put("p2_count", count("P2"));
            return row;
        }
    }
}
