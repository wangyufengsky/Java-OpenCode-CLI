package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.opencode.ValidationCheck;
import com.sonnet.wyf.gitreport.workflow.smartesbreader.SmartEsbCodeReaderOutputValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SmartEsbCodeReaderOutputValidatorTest {
    private final SmartEsbCodeReaderOutputValidator validator = new SmartEsbCodeReaderOutputValidator(new ObjectMapper());

    @TempDir
    Path tempDir;

    @Test
    void acceptsCompleteModuleOutput() throws Exception {
        Path markdown = tempDir.resolve("analysis.md");
        Path summary = tempDir.resolve("summary.json");
        Files.writeString(markdown, "# 模块阅读\n完成\n");
        new ObjectMapper().writeValue(summary.toFile(), Map.of(
                "serviceId", "BaseConvert8583CUPS",
                "status", "completed",
                "risks_or_uncertainties", java.util.List.of()
        ));

        ValidationCheck check = validator.validateTaskOutput("module", "BaseConvert8583CUPS", markdown, summary);

        assertThat(check.ok()).isTrue();
    }

    @Test
    void rejectsPlaceholderMarkdownAndIncompleteSummary() throws Exception {
        Path markdown = tempDir.resolve("analysis.md");
        Path summary = tempDir.resolve("summary.json");
        Files.writeString(markdown, "# 模块阅读\n{{MODULE_ANALYSIS}}\n");
        Files.writeString(summary, "{\"serviceId\":\"BaseConvert8583CUPS\"}");

        ValidationCheck check = validator.validateTaskOutput("module", "BaseConvert8583CUPS", markdown, summary);

        assertThat(check.ok()).isFalse();
        assertThat(check.error()).contains("placeholder");
    }

    @Test
    void rejectsSummaryMissingTargetAndRisks() throws Exception {
        Path markdown = tempDir.resolve("analysis.md");
        Path summary = tempDir.resolve("summary.json");
        Files.writeString(markdown, "# 交易阅读\n完成\n");
        Files.writeString(summary, "{\"status\":\"completed\"}");

        ValidationCheck check = validator.validateTaskOutput("transaction", "CaConsume", markdown, summary);

        assertThat(check.ok()).isFalse();
        assertThat(check.error()).contains("transaction_key");
    }
}
