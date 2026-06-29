package com.sonnet.wyf.gitreport.workflow.weekly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class WeeklyReportRenderer {
    private final ObjectMapper objectMapper;

    public WeeklyReportRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void render(Path evidencePath) throws IOException {
        Map<String, Object> evidence = objectMapper.readValue(evidencePath.toFile(), new TypeReference<>() {});
        Path out = evidencePath.toAbsolutePath().normalize().getParent();
        Files.createDirectories(out);
        Files.writeString(out.resolve("weekly-report.md"), renderWeeklyReport(evidence));
        Files.writeString(out.resolve("team-risk-assessment.md"), renderTeamRiskAssessment(evidence));
        Files.writeString(out.resolve("action-items.md"), renderActionItems(evidence));
        Files.writeString(out.resolve("risk-register.md"), renderRiskRegister(evidence));
        Files.writeString(out.resolve("data-quality.md"), renderDataQuality(evidence));
        for (Map<String, Object> person : listOfMaps(evidence.get("people"))) {
            String authorKey = firstNonBlank(string(person.get("author_key")), "unknown");
            Path personDir = out.resolve("people").resolve(authorKey);
            Files.createDirectories(personDir);
            Files.writeString(personDir.resolve("weekly-person-report.md"), renderPersonReport(person));
        }
    }

    private String renderWeeklyReport(Map<String, Object> evidence) {
        Map<String, Object> project = mapValue(evidence.get("project"));
        Map<String, Object> week = mapValue(evidence.get("week"));
        Map<String, Object> weekly = mapValue(evidence.get("project_weekly"));
        StringBuilder md = new StringBuilder();
        md.append("# 周度工程项目周报：").append(string(project.get("name"))).append("\n\n");
        md.append("## 项目经理周会重点\n\n");
        md.append("- 周期：").append(string(week.get("label"))).append("（").append(string(week.get("start"))).append(" 至 ").append(string(week.get("end"))).append("）\n");
        md.append("- 项目状态：").append(string(weekly.get("overall_status"))).append("\n");
        md.append("- 摘要：").append(firstNonBlank(string(weekly.get("executive_summary")), "本周暂无项目摘要。")).append("\n\n");
        md.append("## 本周完成范围\n\n");
        appendScopeTable(md, listOfMaps(weekly.get("completed_scope")));
        md.append("\n## 交付风险\n\n");
        appendRiskTable(md, listOfMaps(weekly.get("delivery_risks")));
        md.append("\n## 下周建议\n\n");
        appendBullets(md, listValue(weekly.get("next_week_plan_suggestions")), "本周未发现需要项目经理协调的下周建议。");
        return md.toString();
    }

    private String renderTeamRiskAssessment(Map<String, Object> evidence) {
        Map<String, Object> teamRisk = mapValue(evidence.get("team_risk"));
        StringBuilder md = new StringBuilder();
        md.append("# 团队贡献与风险辅助评估\n\n");
        md.append("## 团队概览\n\n").append(firstNonBlank(string(teamRisk.get("team_summary")), "本周暂无团队风险摘要。")).append("\n\n");
        md.append("## 贡献分布\n\n");
        List<Map<String, Object>> distribution = listOfMaps(teamRisk.get("contribution_distribution"));
        if (distribution.isEmpty()) {
            md.append("本周未发现可汇总的人员贡献证据。\n");
        } else {
            md.append("| 人员 | 主要区域 | 提交数 | 去注释变更量 | 文件数 | 说明 |\n");
            md.append("| --- | --- | ---: | ---: | ---: | --- |\n");
            for (Map<String, Object> row : distribution) {
                Map<String, Object> workload = mapValue(row.get("workload_evidence"));
                md.append("| ").append(cell(row.get("author")))
                        .append(" | ").append(cell(join(listValue(row.get("primary_areas")))))
                        .append(" | ").append(number(workload.get("commit_count")))
                        .append(" | ").append(number(workload.get("non_comment_churn")))
                        .append(" | ").append(number(workload.get("changed_files")))
                        .append(" | ").append(cell(row.get("interpretation")))
                        .append(" |\n");
            }
        }
        md.append("\n## 风险集中\n\n");
        appendRiskConcentration(md, listOfMaps(teamRisk.get("risk_concentration")));
        md.append("\n## Review 建议\n\n");
        appendBullets(md, listValue(teamRisk.get("review_recommendations")), "本周未发现需要升级的 review 建议。");
        return md.toString();
    }

    private String renderActionItems(Map<String, Object> evidence) {
        StringBuilder md = new StringBuilder("# 行动项清单\n\n");
        List<Map<String, Object>> actions = listOfMaps(evidence.get("action_items"));
        if (actions.isEmpty()) {
            md.append("本周未发现需要登记的行动项。\n");
            return md.toString();
        }
        md.append("| ID | 受众 | 优先级 | 标题 | 完成定义 | 证据 |\n");
        md.append("| --- | --- | --- | --- | --- | --- |\n");
        for (Map<String, Object> action : actions) {
            md.append("| ").append(cell(action.get("action_id")))
                    .append(" | ").append(cell(action.get("audience")))
                    .append(" | ").append(cell(action.get("priority")))
                    .append(" | ").append(cell(action.get("title")))
                    .append(" | ").append(cell(action.get("done_definition")))
                    .append(" | ").append(cell(join(listValue(action.get("evidence_refs")))))
                    .append(" |\n");
        }
        return md.toString();
    }

    private String renderRiskRegister(Map<String, Object> evidence) {
        StringBuilder md = new StringBuilder("# 风险登记表\n\n");
        appendRiskTable(md, listOfMaps(evidence.get("risks")));
        return md.toString();
    }

    private String renderDataQuality(Map<String, Object> evidence) {
        Map<String, Object> dataQuality = mapValue(evidence.get("data_quality"));
        StringBuilder md = new StringBuilder("# 数据质量说明\n\n");
        md.append("- 状态：").append(string(dataQuality.get("status"))).append("\n\n");
        md.append("## 问题\n\n");
        List<Map<String, Object>> issues = listOfMaps(dataQuality.get("issues"));
        if (issues.isEmpty()) {
            md.append("本周未发现数据质量问题。\n");
        } else {
            md.append("| 来源 | 级别 | 信息 | 影响 |\n| --- | --- | --- | --- |\n");
            for (Map<String, Object> issue : issues) {
                md.append("| ").append(cell(issue.get("source")))
                        .append(" | ").append(cell(issue.get("severity")))
                        .append(" | ").append(cell(issue.get("message")))
                        .append(" | ").append(cell(issue.get("impact")))
                        .append(" |\n");
            }
        }
        md.append("\n## 已知偏差\n\n");
        appendBullets(md, listValue(dataQuality.get("known_biases")), "本周未登记已知偏差。");
        return md.toString();
    }

    private String renderPersonReport(Map<String, Object> person) {
        StringBuilder md = new StringBuilder();
        md.append("# 个人周报：").append(string(person.get("author"))).append("\n\n");
        md.append("## 本周工作范围\n\n");
        Map<String, Object> scope = mapValue(person.get("work_scope"));
        md.append("- 工作类型：").append(firstNonBlank(join(listValue(scope.get("work_types"))), "本周未发现")).append("\n");
        md.append("- Top 文件：").append(firstNonBlank(joinPaths(listOfMaps(scope.get("top_files"))), "本周未发现")).append("\n");
        md.append("- 提交：").append(firstNonBlank(joinCommitSubjects(listOfMaps(scope.get("commits"))), "本周未发现")).append("\n\n");
        md.append("## 贡献亮点\n\n");
        appendTitledItems(md, listOfMaps(person.get("contribution_highlights")), "本周未发现可汇总的贡献亮点。");
        md.append("\n## 质量信号\n\n");
        Map<String, Object> quality = mapValue(person.get("quality_signals"));
        md.append("正向信号：\n");
        appendBullets(md, listValue(quality.get("positive")), "本周未发现。");
        md.append("\n风险信号：\n");
        appendBullets(md, listValue(quality.get("risks")), "本周未发现。");
        md.append("\n未验证项：\n");
        appendBullets(md, listValue(quality.get("unverified")), "本周未发现。");
        md.append("\n## 下周建议\n\n");
        appendSuggestions(md, listOfMaps(person.get("next_week_suggestions")));
        md.append("\n## 证据边界\n\n");
        md.append(firstNonBlank(string(person.get("assessment_boundary")), "仅作为研发负责人 1:1、辅导和绩效校准的证据包，不直接给出绩效结论。")).append("\n");
        return md.toString();
    }

    private void appendScopeTable(StringBuilder md, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            md.append("本周未发现已完成范围。\n");
            return;
        }
        md.append("| 类型 | 名称 | 说明 | 证据 |\n| --- | --- | --- | --- |\n");
        for (Map<String, Object> row : rows) {
            md.append("| ").append(cell(row.get("type")))
                    .append(" | ").append(cell(row.get("name")))
                    .append(" | ").append(cell(row.get("description")))
                    .append(" | ").append(cell(join(listValue(row.get("evidence_refs")))))
                    .append(" |\n");
        }
    }

    private void appendRiskTable(StringBuilder md, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            md.append("本周未发现需要登记的风险。\n");
            return;
        }
        md.append("| ID | 级别 | 标题 | 影响 | 建议 |\n| --- | --- | --- | --- | --- |\n");
        for (Map<String, Object> row : rows) {
            md.append("| ").append(cell(firstNonBlank(string(row.get("risk_id")), string(row.get("id")))))
                    .append(" | ").append(cell(row.get("severity")))
                    .append(" | ").append(cell(row.get("title")))
                    .append(" | ").append(cell(row.get("impact")))
                    .append(" | ").append(cell(row.get("recommended_action")))
                    .append(" |\n");
        }
    }

    private void appendRiskConcentration(StringBuilder md, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            md.append("本周未发现团队风险集中项。\n");
            return;
        }
        md.append("| 类型 | 目标 | 级别 | 建议 | 证据 |\n| --- | --- | --- | --- | --- |\n");
        for (Map<String, Object> row : rows) {
            md.append("| ").append(cell(row.get("type")))
                    .append(" | ").append(cell(row.get("target")))
                    .append(" | ").append(cell(row.get("severity")))
                    .append(" | ").append(cell(row.get("recommendation")))
                    .append(" | ").append(cell(join(listValue(row.get("evidence_refs")))))
                    .append(" |\n");
        }
    }

    private void appendTitledItems(StringBuilder md, List<Map<String, Object>> rows, String empty) {
        if (rows.isEmpty()) {
            md.append(empty).append("\n");
            return;
        }
        for (Map<String, Object> row : rows) {
            md.append("- ").append(string(row.get("title"))).append("：").append(string(row.get("reason"))).append("\n");
        }
    }

    private void appendSuggestions(StringBuilder md, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            md.append("本周未发现需要单独跟进的个人建议。\n");
            return;
        }
        for (Map<String, Object> row : rows) {
            md.append("- [").append(string(row.get("priority"))).append("] ")
                    .append(string(row.get("suggestion")))
                    .append("：").append(string(row.get("reason"))).append("\n");
        }
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
        return rows.stream().map(row -> string(row.get("path"))).filter(value -> !value.isBlank()).reduce((left, right) -> left + ", " + right).orElse("");
    }

    private String joinCommitSubjects(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> firstNonBlank(string(row.get("short_hash")), string(row.get("hash"))) + " " + string(row.get("subject")))
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    private String join(List<?> values) {
        return values.stream().map(Objects::toString).reduce((left, right) -> left + ", " + right).orElse("");
    }

    private String cell(Object value) {
        return string(value).replace("|", "\\|").replace("\n", "<br>");
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }
}
