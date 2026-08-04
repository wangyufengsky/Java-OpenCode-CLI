package com.sonnet.wyf.gitreport.validation;

import com.sonnet.wyf.gitreport.core.GitReportConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FinalReportValidator {
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "# 代码提交量统计报告",
            "## 1. 统计范围",
            "## 2. 总体汇总",
            "## 3. 人员工作量排名与分析",
            "## 4. 个人报告链接",
            "## 5. 未完成个人报告",
            "## 6. 统计口径",
            "## 7. 风险与偏差",
            "## 8. 典型低质量代码片段"
    );
    private static final Pattern MARKDOWN_LINK_TARGET = Pattern.compile("\\[[^]]*]\\(([^)]+)\\)");
    private static final Pattern ABSOLUTE_LINK_TARGET = Pattern.compile("(?i)^(?:[a-z]:[\\\\/]|/|file:|https?://)");
    private static final Pattern RELATIVE_PERSON_REPORT_LINK = Pattern.compile("\\]\\(reports/[^)]+/person-report\\.md\\)");

    public Validation validate(Path finalReport) {
        try {
            if (!Files.exists(finalReport)) {
                return Validation.failed("final report missing: " + finalReport);
            }
            String report = Files.readString(finalReport);
            if (report.isBlank()) {
                return Validation.failed("final report is blank: " + finalReport);
            }
            if (report.contains(GitReportConstants.REPORT_MARKER)) {
                return Validation.failed("final report still contains marker: " + finalReport);
            }
            String placeholder = GitReportConstants.FINAL_REPORT_PLACEHOLDERS.stream()
                    .filter(report::contains)
                    .findFirst()
                    .orElse("");
            if (!placeholder.isBlank()) {
                return Validation.failed("final report contains unresolved template placeholder "
                        + placeholder + ": " + finalReport);
            }
            for (String section : REQUIRED_SECTIONS) {
                if (!report.contains(section)) {
                    return Validation.failed("final report missing section: " + section);
                }
            }
            for (String rankingField : List.of("最终排名", "初始排名")) {
                if (!report.contains(rankingField)) {
                    return Validation.failed("final report missing ranking field: " + rankingField);
                }
            }
            Matcher matcher = MARKDOWN_LINK_TARGET.matcher(report);
            while (matcher.find()) {
                String target = matcher.group(1).trim();
                if (ABSOLUTE_LINK_TARGET.matcher(target).find()) {
                    return Validation.failed("final report contains absolute link: " + target);
                }
            }
            if (!RELATIVE_PERSON_REPORT_LINK.matcher(report).find()) {
                return Validation.failed("final report missing relative reports/.../person-report.md link");
            }
            return Validation.success();
        } catch (IOException exception) {
            return Validation.failed(exception.getMessage());
        }
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
