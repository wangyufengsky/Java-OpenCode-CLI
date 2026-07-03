package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbDailyTransactionPlan;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbRewriteProperties;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbReviewPreparation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmartEsbPreparationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void precreatesDatedReviewWorkspaceAndWritesWindowsTaskContracts() throws Exception {
        SmartEsbRewriteProperties properties = new SmartEsbRewriteProperties();
        properties.setOut("D:\\review-output\\smartesb");
        properties.setLocalOut(tempDir.resolve("mirror"));
        properties.setNewProject("D:\\upfs-nl-json");
        properties.setOld8583Doc("D:\\upfs-nl-json\\doc\\old-8583.md");
        properties.setDocRoot("D:\\upfs-nl-json\\doc\\docment");
        properties.setTransactionPlanDir(tempDir.resolve("plans"));
        SmartEsbDailyTransactionPlan plan = new SmartEsbDailyTransactionPlan(
                LocalDate.of(2026, 6, 16),
                tempDir.resolve("plans/2026-06-16/transactions.yml"),
                List.of(new SmartEsbDailyTransactionPlan.Transaction("CaRolloutRepeal", "转账撤销/冲正"))
        );

        Path localOut = new SmartEsbReviewPreparation(objectMapper).prepare(properties, plan, true);

        Path dayOut = tempDir.resolve("mirror/2026-06-16");
        assertThat(localOut).isEqualTo(dayOut);
        assertThat(dayOut.resolve("index.md")).content()
                .contains("## 交易/模块审查状态", "{{TRANSACTION_ROWS}}")
                .doesNotContain("<!-- OPENCODE_APPEND:index -->");
        assertThat(dayOut.resolve("summary.md")).content()
                .contains("## 交易/模块审查状态", "{{SUMMARY_TRANSACTION_ROWS}}")
                .doesNotContain("<!-- OPENCODE_APPEND:summary -->");
        assertThat(dayOut.resolve("reports/CaRolloutRepeal/review.md")).content()
                .contains("# 交易重构代码审查：CaRolloutRepeal", "{{FINDING_ROWS}}", "{{SUMMARY}}")
                .doesNotContain("<!-- OPENCODE_APPEND:review -->");
        assertThat(dayOut.resolve("reports/CaRolloutRepeal/mapping-matrix.md")).content()
                .contains("8583 字段/来源", "{{MAPPING_ROWS}}")
                .doesNotContain("<!-- OPENCODE_APPEND:mapping-matrix -->");
        assertThat(dayOut.resolve("reports/CaRolloutRepeal/sections/06-code-standard.md")).content()
                .contains("# 代码规范审查", "{{CODE_STANDARD_REVIEW}}")
                .doesNotContain("<!-- OPENCODE_APPEND:06-code-standard -->");
        assertThat(dayOut.resolve("reports/CaRolloutRepeal/summary.json")).content().isEqualTo("{}\n");

        JsonNode task = objectMapper.readTree(dayOut.resolve("tasks/transaction-CaRolloutRepeal.json").toFile());
        assertThat(task.path("task_path").asText()).isEqualTo("D:\\review-output\\smartesb\\2026-06-16\\tasks\\transaction-CaRolloutRepeal.json");
        assertThat(task.at("/output/review_md").asText()).isEqualTo("D:\\review-output\\smartesb\\2026-06-16\\reports\\CaRolloutRepeal\\review.md");
        assertThat(task.at("/output_placeholders/code_standard_md/0").asText()).isEqualTo("{{CODE_STANDARD_REVIEW}}");
        assertThat(task.has("output_markers")).isFalse();
        assertThat(task.at("/skill/preferred_reader").asText()).isEqualTo("intellij-idea");
        assertThat(task.at("/skill/preferred_writer").asText()).isEqualTo("intellij-idea");
        assertThat(task.at("/skill/file_tools").toString()).contains("intellij-idea_replace_text_in_file");
        assertThat(task.at("/rules/reader_preference").asText()).contains("必须使用 `intellij-idea` MCP 文件读取工具");
        assertThat(task.at("/rules/writer_preference").asText()).contains("必须使用 `intellij-idea` MCP 文件编辑工具");
        assertThat(task.at("/rules/writer_preference").asText()).contains("`intellij-idea` MCP 读写工具不可用时返回 BLOCKED");
        assertThat(task.at("/rules/template_contract").asText()).contains("只能替换 output_placeholders");
        assertThat(task.at("/rules/external_skill_policy").asText()).contains("不要在执行前搜索、读取、加载或调用任何外部 skill");
        assertThat(task.at("/rules/external_skill_policy").asText()).contains("brainstorming");
        assertThat(task.at("/rules/scope").asText()).contains("old-8583-doc 老代码详细设计");
        assertThat(task.at("/rules/scope").asText()).contains("不读取或检索 old_project 下的老代码源码");
        assertThat(task.at("/rules/explore_preference").asText()).contains("必须使用 `intellij-index` 和 `intellij-idea` MCP 定位、读取和取证");
        assertThat(task.at("/rules/explore_preference").asText()).doesNotContain("OpenCode explore");
        assertThat(task.has("old_project")).isFalse();
        assertThat(task.at("/documents/mapping_8583_to_json").asText()).isEqualTo("D:\\upfs-nl-json\\doc\\docment\\8583 to json.md");
        assertThat(task.at("/documents/old_8583_doc").asText()).isEqualTo("D:\\upfs-nl-json\\doc\\old-8583.md");
        assertThat(task.at("/documents/legacy_index").isMissingNode()).isTrue();
        assertThat(task.at("/documents/reconstructed_design").asText()).isEqualTo("D:\\upfs-nl-json\\doc\\docment\\重构项目详细设计文档.md");
        assertThat(task.at("/documents/old_8583").isMissingNode()).isTrue();
        assertThat(task.at("/documents/json").isMissingNode()).isTrue();
        assertThat(task.at("/skill/summary_schema").asText()).isEqualTo("D:\\review-output\\smartesb\\2026-06-16\\schemas\\transaction-summary.schema.json");
        assertThat(dayOut.resolve("schemas/transaction-summary.schema.json")).content()
                .contains("\"required\"", "\"finding_counts\"", "\"code_standard_findings\"");

        JsonNode indexInputs = objectMapper.readTree(dayOut.resolve("index_inputs.json").toFile());
        assertThat(indexInputs.at("/tasks/0/transaction").asText()).isEqualTo("CaRolloutRepeal");
        assertThat(indexInputs.at("/output/index_md").asText()).isEqualTo("D:\\review-output\\smartesb\\2026-06-16\\index.md");
        assertThat(indexInputs.at("/schemas/transaction_summary").asText()).isEqualTo("D:\\review-output\\smartesb\\2026-06-16\\schemas\\transaction-summary.schema.json");
        assertThat(indexInputs.at("/output_placeholders/index_md/0").asText()).isEqualTo("{{OVERALL_CONCLUSION}}");
        assertThat(indexInputs.has("output_markers")).isFalse();
    }

    @Test
    void precreatesDatedReviewWorkspaceAndWritesLinuxTaskContracts() throws Exception {
        SmartEsbRewriteProperties properties = new SmartEsbRewriteProperties();
        properties.setOut("/home/wangyufeng/review-output/smartesb");
        properties.setLocalOut(tempDir.resolve("mirror"));
        properties.setNewProject("/home/wangyufeng/upfs-nl-json");
        properties.setDocRoot("/home/wangyufeng/upfs-nl-json/doc/docment");
        SmartEsbDailyTransactionPlan plan = new SmartEsbDailyTransactionPlan(
                LocalDate.of(2026, 6, 16),
                tempDir.resolve("plans/2026-06-16/transactions.yml"),
                List.of(new SmartEsbDailyTransactionPlan.Transaction("CaRolloutRepeal", "转账撤销/冲正"))
        );

        Path localOut = new SmartEsbReviewPreparation(objectMapper).prepare(properties, plan, true);

        Path dayOut = tempDir.resolve("mirror/2026-06-16");
        assertThat(localOut).isEqualTo(dayOut);
        JsonNode task = objectMapper.readTree(dayOut.resolve("tasks/transaction-CaRolloutRepeal.json").toFile());
        assertThat(task.path("task_path").asText()).isEqualTo("/home/wangyufeng/review-output/smartesb/2026-06-16/tasks/transaction-CaRolloutRepeal.json");
        assertThat(task.at("/output/review_md").asText()).isEqualTo("/home/wangyufeng/review-output/smartesb/2026-06-16/reports/CaRolloutRepeal/review.md");
        assertThat(task.at("/documents/mapping_8583_to_json").asText()).isEqualTo("/home/wangyufeng/upfs-nl-json/doc/docment/8583 to json.md");
        assertThat(task.at("/documents/old_8583_doc").asText()).isEqualTo("/home/wangyufeng/upfs-nl-json/doc/docment/old-8583.md");
        assertThat(task.at("/documents/legacy_index").isMissingNode()).isTrue();
        assertThat(task.at("/documents/reconstructed_design").asText()).isEqualTo("/home/wangyufeng/upfs-nl-json/doc/docment/重构项目详细设计文档.md");
        assertThat(task.at("/documents/old_8583").isMissingNode()).isTrue();
        assertThat(task.at("/documents/json").isMissingNode()).isTrue();
        assertThat(task.at("/skill/summary_schema").asText()).isEqualTo("/home/wangyufeng/review-output/smartesb/2026-06-16/schemas/transaction-summary.schema.json");
        assertThat(task.at("/rules/scope").asText()).contains("只审查当前交易的新代码、映射文档、重构详细设计和 old-8583-doc 老代码详细设计");
        assertThat(task.at("/rules/explore_preference").asText()).contains("必须使用 `intellij-index` 和 `intellij-idea` MCP 定位、读取和取证");
        assertThat(task.has("old_project")).isFalse();
        assertThat(task.at("/skill/preferred_writer").asText()).isEqualTo("intellij-idea");
        assertThat(dayOut.resolve("reports/CaRolloutRepeal/review.md")).content()
                .contains(
                        "- 重构项目：`/home/wangyufeng/upfs-nl-json`",
                        "- old-8583-doc：`/home/wangyufeng/upfs-nl-json/doc/docment/old-8583.md`",
                        "- 映射文档：`/home/wangyufeng/upfs-nl-json/doc/docment/8583 to json.md`",
                        "- 详细设计：`/home/wangyufeng/upfs-nl-json/doc/docment/重构项目详细设计文档.md`"
                )
                .doesNotContain(
                        "8583 文档",
                        "JSON 文档",
                        "legacy-index",
                        "/home/wangyufeng/upfs-nl-json/doc/docment/8583.md"
                );

        JsonNode indexInputs = objectMapper.readTree(dayOut.resolve("index_inputs.json").toFile());
        assertThat(indexInputs.at("/output/index_md").asText()).isEqualTo("/home/wangyufeng/review-output/smartesb/2026-06-16/index.md");
        assertThat(indexInputs.at("/schemas/transaction_summary").asText()).isEqualTo("/home/wangyufeng/review-output/smartesb/2026-06-16/schemas/transaction-summary.schema.json");
        assertThat(dayOut.resolve("schemas/transaction-summary.schema.json")).content()
                .contains("\"required\"", "\"finding_counts\"", "\"code_standard_findings\"");
    }

    @Test
    void precreatesModuleReviewWorkspaceWithoutTransactionDocumentContract() throws Exception {
        SmartEsbRewriteProperties properties = new SmartEsbRewriteProperties();
        properties.setOut("/home/wangyufeng/review-output/smartesb");
        properties.setLocalOut(tempDir.resolve("mirror"));
        properties.setNewProject("/home/wangyufeng/upfs-nl-json");
        properties.setDocRoot("/home/wangyufeng/upfs-nl-json/doc/docment");
        SmartEsbDailyTransactionPlan plan = new SmartEsbDailyTransactionPlan(
                LocalDate.of(2026, 6, 24),
                tempDir.resolve("plans/2026-06-24/transactions.yml"),
                List.of(new SmartEsbDailyTransactionPlan.Transaction("CaReturnOfGoods", "")),
                List.of(new SmartEsbDailyTransactionPlan.Module("BaseChnConvReqMsgSop"))
        );

        Path localOut = new SmartEsbReviewPreparation(objectMapper).prepare(properties, plan, true);

        Path dayOut = tempDir.resolve("mirror/2026-06-24");
        assertThat(localOut).isEqualTo(dayOut);
        assertThat(dayOut.resolve("tasks/module-BaseChnConvReqMsgSop.json")).exists();
        assertThat(dayOut.resolve("reports/BaseChnConvReqMsgSop/review.md")).content()
                .contains("# 模块代码审查：BaseChnConvReqMsgSop", "{{FINDING_ROWS}}", "{{SUMMARY}}")
                .doesNotContain("交易重构代码审查");
        assertThat(dayOut.resolve("reports/BaseChnConvReqMsgSop/mapping-matrix.md")).content()
                .contains("模块职责/依赖", "{{MAPPING_ROWS}}")
                .doesNotContain("8583 字段/来源");

        JsonNode task = objectMapper.readTree(dayOut.resolve("tasks/module-BaseChnConvReqMsgSop.json").toFile());
        assertThat(task.path("review_type").asText()).isEqualTo("module");
        assertThat(task.path("module").asText()).isEqualTo("BaseChnConvReqMsgSop");
        assertThat(task.path("transaction").isMissingNode()).isTrue();
        assertThat(task.path("task_path").asText()).isEqualTo("/home/wangyufeng/review-output/smartesb/2026-06-24/tasks/module-BaseChnConvReqMsgSop.json");
        assertThat(task.at("/skill/prompt").asText()).isEqualTo("classpath:smartesb-rewrite-code-review-prompt-pack/prompts/run-module-review.md");
        assertThat(task.at("/rules/scope").asText()).contains("只审查当前模块");
        assertThat(task.at("/rules/scope").asText()).contains("不要求交易名");
        assertThat(task.at("/documents/mapping_8583_to_json").isMissingNode()).isTrue();
        assertThat(task.at("/documents/old_8583_doc").isMissingNode()).isTrue();
        assertThat(task.at("/documents/reconstructed_design").asText()).isEqualTo("/home/wangyufeng/upfs-nl-json/doc/docment/重构项目详细设计文档.md");

        JsonNode indexInputs = objectMapper.readTree(dayOut.resolve("index_inputs.json").toFile());
        assertThat(indexInputs.at("/tasks/1/review_type").asText()).isEqualTo("module");
        assertThat(indexInputs.at("/tasks/1/module").asText()).isEqualTo("BaseChnConvReqMsgSop");
        assertThat(indexInputs.at("/prompts/module_review").asText()).isEqualTo("classpath:smartesb-rewrite-code-review-prompt-pack/prompts/run-module-review.md");
    }

    @Test
    void rejectsRelativeLogicalOutputPath() {
        SmartEsbRewriteProperties properties = new SmartEsbRewriteProperties();
        properties.setOut("relative/smartesb");
        properties.setLocalOut(tempDir.resolve("mirror"));
        SmartEsbDailyTransactionPlan plan = new SmartEsbDailyTransactionPlan(
                LocalDate.of(2026, 6, 16),
                tempDir.resolve("plans/2026-06-16/transactions.yml"),
                List.of(new SmartEsbDailyTransactionPlan.Transaction("CaRolloutRepeal", "转账撤销/冲正"))
        );

        assertThatThrownBy(() -> new SmartEsbReviewPreparation(objectMapper).prepare(properties, plan, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute path");
    }
}
