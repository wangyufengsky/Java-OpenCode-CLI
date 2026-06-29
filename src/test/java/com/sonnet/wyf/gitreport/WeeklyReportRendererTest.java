package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyReportRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyReportRendererTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void rendersAudienceSpecificMarkdownFromWeeklyEvidenceOnly() throws Exception {
        Path evidencePath = tempDir.resolve("weekly-evidence.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(evidencePath.toFile(), minimalEvidence());

        new WeeklyReportRenderer(objectMapper).render(evidencePath);

        Path weeklyReport = tempDir.resolve("weekly-report.md");
        Path teamRisk = tempDir.resolve("team-risk-assessment.md");
        Path actionItems = tempDir.resolve("action-items.md");
        Path riskRegister = tempDir.resolve("risk-register.md");
        Path dataQuality = tempDir.resolve("data-quality.md");
        Path personReport = tempDir.resolve("people/author-001-alice/weekly-person-report.md");
        assertThat(weeklyReport).exists();
        assertThat(teamRisk).exists();
        assertThat(actionItems).exists();
        assertThat(riskRegister).exists();
        assertThat(dataQuality).exists();
        assertThat(personReport).exists();

        assertThat(weeklyReport).content()
                .contains("# 周度工程项目周报：UPFS Production", "## 项目经理周会重点", "src/Foo.java")
                .doesNotContain("final_rank", "绩效结论", "Alice <alice@example.com>");
        assertThat(teamRisk).content()
                .contains("# 团队贡献与风险辅助评估", "Alice <alice@example.com>", "本周 Git changed regions");
        assertThat(personReport).content()
                .contains("# 个人周报：Alice <alice@example.com>")
                .contains("仅作为研发负责人 1:1、辅导和绩效校准的证据包，不直接给出绩效结论。")
                .doesNotContain("优秀", "不合格");
        assertThat(Files.readString(weeklyReport)).doesNotContain("{{");
        assertThat(Files.readString(personReport)).doesNotContain("{{");
    }

    private Map<String, Object> minimalEvidence() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", "weekly-engineering-report/v1");
        root.put("generated_at", "2026-06-26T10:00:00+08:00");
        root.put("week", Map.of("start", "2026-06-22", "end", "2026-06-28", "label", "2026-W26"));
        root.put("project", Map.of("id", "upfs-production", "name", "UPFS Production", "repo", "/repo", "revision", "HEAD", "out", tempDir.toString()));
        root.put("source_runs", Map.of(
                        "weekly_git", Map.of("status", "generated", "summary_json", "/weekly/sources/weekly-git/summary.json")
                ));
        root.put("project_weekly", Map.of(
                        "overall_status", "at_risk",
                        "executive_summary", "本周完成消费交易审查并发现一个高优先级验证风险。",
                        "completed_scope", List.of(Map.of("type", "code_change", "name", "src/Foo.java", "description", "Alice 本周改动", "evidence_refs", List.of("git:author-001-alice:abcdef123456"))),
                        "scope_changes", List.of(),
                        "delivery_risks", List.of(),
                        "decisions_needed", List.of(),
                        "next_week_plan_suggestions", List.of("优先补齐 CaConsume 验证证据")
                ));
        root.put("team_risk", Map.of(
                        "team_summary", "团队风险集中在验证闭环。",
                        "contribution_distribution", List.of(Map.of("author_key", "author-001-alice", "author", "Alice <alice@example.com>", "primary_areas", List.of("src/Foo.java"), "workload_evidence", Map.of("commit_count", 2, "non_comment_churn", 42, "changed_files", 3), "interpretation", "用于识别本周投入范围和协作风险，不作为绩效定级。")),
                        "risk_concentration", List.of(),
                        "quality_aggregate", Map.of("dimensions", Map.of(), "top_recurring_rules", List.of()),
                        "review_recommendations", List.of("如需代码质量结论，应基于本周 Git changed regions 另行触发当周代码审查")
                ));
        root.put("people", List.of(Map.of(
                        "author_key", "author-001-alice",
                        "author", "Alice <alice@example.com>",
                        "work_scope", Map.of("commits", List.of(Map.of("short_hash", "abcdef123456", "subject", "Add parser")), "top_files", List.of(Map.of("path", "src/Foo.java")), "transactions", List.of(), "modules", List.of(), "work_types", List.of("component")),
                        "contribution_highlights", List.of(Map.of("title", "本周主要贡献", "reason", "完成解析链路", "evidence_refs", List.of("git-report:author-001-alice:quality-summary"))),
                        "quality_signals", Map.of("positive", List.of("拆分公共解析逻辑"), "risks", List.of("缺少回归测试"), "unverified", List.of("未看到端到端验证记录"), "low_quality_snippets", List.of()),
                        "collaboration_and_impact", Map.of("shared_modules_touched", List.of(), "hotspot_files_touched", List.of("src/Foo.java"), "cross_author_areas", List.of()),
                        "next_week_suggestions", List.of(Map.of("suggestion", "补齐验证证据", "reason", "存在 unverified 项", "priority", "high")),
                        "assessment_boundary", "仅作为研发负责人 1:1、辅导和绩效校准的证据包，不直接给出绩效结论。"
                )));
        root.put("risks", List.of());
        root.put("action_items", List.of());
        root.put("data_quality", Map.of("status", "clean", "issues", List.of(), "known_biases", List.of("提交量不等于业务价值")));
        return root;
    }
}
