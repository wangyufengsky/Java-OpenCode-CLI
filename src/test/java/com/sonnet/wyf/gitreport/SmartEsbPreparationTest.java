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
        properties.setOldProject("D:\\upfs\\qianzhi\\upfs-cloud-xc");
        properties.setNewProject("D:\\upfs-nl-json");
        properties.setTransactionPlanDir(tempDir.resolve("plans"));
        SmartEsbDailyTransactionPlan plan = new SmartEsbDailyTransactionPlan(
                LocalDate.of(2026, 6, 16),
                tempDir.resolve("plans/2026-06-16/transactions.yml"),
                List.of(new SmartEsbDailyTransactionPlan.Transaction("CaRolloutRepeal", "转账撤销/冲正"))
        );

        Path localOut = new SmartEsbReviewPreparation(objectMapper).prepare(properties, plan, true);

        Path dayOut = tempDir.resolve("mirror/2026-06-16");
        assertThat(localOut).isEqualTo(dayOut);
        assertThat(dayOut.resolve("index.md")).content().contains("<!-- OPENCODE_APPEND:index -->");
        assertThat(dayOut.resolve("summary.md")).content().contains("<!-- OPENCODE_APPEND:summary -->");
        assertThat(dayOut.resolve("reports/CaRolloutRepeal/review.md")).content().contains("<!-- OPENCODE_APPEND:review -->");
        assertThat(dayOut.resolve("reports/CaRolloutRepeal/mapping-matrix.md")).content().contains("<!-- OPENCODE_APPEND:mapping-matrix -->");
        assertThat(dayOut.resolve("reports/CaRolloutRepeal/sections/06-code-standard.md")).content().contains("<!-- OPENCODE_APPEND:06-code-standard -->");
        assertThat(dayOut.resolve("reports/CaRolloutRepeal/summary.json")).content().isEqualTo("{}\n");

        JsonNode task = objectMapper.readTree(dayOut.resolve("tasks/transaction-CaRolloutRepeal.json").toFile());
        assertThat(task.path("task_path").asText()).isEqualTo("D:\\review-output\\smartesb\\2026-06-16\\tasks\\transaction-CaRolloutRepeal.json");
        assertThat(task.at("/output/review_md").asText()).isEqualTo("D:\\review-output\\smartesb\\2026-06-16\\reports\\CaRolloutRepeal\\review.md");
        assertThat(task.at("/output_markers/code_standard_md").asText()).isEqualTo("<!-- OPENCODE_APPEND:06-code-standard -->");
        assertThat(task.at("/skill/preferred_writer").asText()).isEqualTo("idea_mcp");

        JsonNode indexInputs = objectMapper.readTree(dayOut.resolve("index_inputs.json").toFile());
        assertThat(indexInputs.at("/tasks/0/transaction").asText()).isEqualTo("CaRolloutRepeal");
        assertThat(indexInputs.at("/output/index_md").asText()).isEqualTo("D:\\review-output\\smartesb\\2026-06-16\\index.md");
    }

    @Test
    void rejectsNonWindowsLogicalOutputPath() {
        SmartEsbRewriteProperties properties = new SmartEsbRewriteProperties();
        properties.setOut(tempDir.resolve("not-windows").toString());
        properties.setLocalOut(tempDir.resolve("mirror"));
        SmartEsbDailyTransactionPlan plan = new SmartEsbDailyTransactionPlan(
                LocalDate.of(2026, 6, 16),
                tempDir.resolve("plans/2026-06-16/transactions.yml"),
                List.of(new SmartEsbDailyTransactionPlan.Transaction("CaRolloutRepeal", "转账撤销/冲正"))
        );

        assertThatThrownBy(() -> new SmartEsbReviewPreparation(objectMapper).prepare(properties, plan, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Windows absolute path");
    }
}
