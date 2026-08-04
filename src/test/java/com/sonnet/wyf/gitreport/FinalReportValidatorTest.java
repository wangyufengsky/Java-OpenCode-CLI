package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.core.GitReportConstants;
import com.sonnet.wyf.gitreport.validation.FinalReportValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FinalReportValidatorTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsFinalReportWithMarkerAbsoluteLinkOrMissingRankingContract() throws Exception {
        Path report = tempDir.resolve("code-contribution-report.md");
        FinalReportValidator validator = new FinalReportValidator();

        Files.writeString(report, validReport().replace("## 3. 人员工作量排名与分析", GitReportConstants.REPORT_MARKER));
        FinalReportValidator.Validation marker = validator.validate(report);
        assertThat(marker.ok()).isFalse();
        assertThat(marker.error()).contains("marker");

        Files.writeString(report, validReport().replace("[person-report.md](reports/author-001/person-report.md)", "[person-report.md](D:/reports/author-001/person-report.md)"));
        FinalReportValidator.Validation absoluteLink = validator.validate(report);
        assertThat(absoluteLink.ok()).isFalse();
        assertThat(absoluteLink.error()).contains("absolute");

        Files.writeString(report, validReport().replace("初始排名", "原始排序"));
        FinalReportValidator.Validation missingRank = validator.validate(report);
        assertThat(missingRank.ok()).isFalse();
        assertThat(missingRank.error()).contains("初始排名");

        Files.writeString(report, validReport().replace("内容", "{{AI_ANALYSIS}}"));
        FinalReportValidator.Validation placeholder = validator.validate(report);
        assertThat(placeholder.ok()).isFalse();
        assertThat(placeholder.error()).contains("unresolved template placeholder");
    }

    @Test
    void acceptsFinalReportWithRequiredSectionsAndRelativeLinks() throws Exception {
        Path report = tempDir.resolve("code-contribution-report.md");
        Files.writeString(report, validReport() + "\n正常模板代码：`{{runtimeValue}}`\n");

        FinalReportValidator.Validation validation = new FinalReportValidator().validate(report);

        assertThat(validation.ok()).isTrue();
    }

    private String validReport() {
        return """
                # 代码提交量统计报告

                ## 1. 统计范围
                内容

                ## 2. 总体汇总
                内容

                ## 3. 人员工作量排名与分析
                | 最终排名 | 初始排名 | 开发人员 |
                | ---: | ---: | --- |
                | 1 | 1 | Alice |

                ## 4. 个人报告链接
                [person-report.md](reports/author-001/person-report.md)

                ## 5. 未完成个人报告
                无

                ## 6. 统计口径
                内容

                ## 7. 风险与偏差
                内容

                ## 8. 典型低质量代码片段
                无
                """;
    }
}
