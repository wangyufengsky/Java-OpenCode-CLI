package com.sonnet.wyf.gitreport;

import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbDailyTransactionPlan;
import com.sonnet.wyf.gitreport.workflow.smartesb.SmartEsbDailyTransactionPlanLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmartEsbDailyTransactionPlanTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsSingleTransactionsYamlFromRunDateDirectory() throws Exception {
        Path dayDir = tempDir.resolve("2026-06-16");
        Files.createDirectories(dayDir);
        Files.writeString(dayDir.resolve("transactions.yml"), """
                date: "2026-06-16"
                transactions:
                  - name: "CaRolloutRepeal"
                    description: "转账撤销/冲正"
                  - name: "CaAcctInfoCheck"
                    description: "二三类账户信息验证"
                """);

        SmartEsbDailyTransactionPlan plan = new SmartEsbDailyTransactionPlanLoader()
                .load(tempDir, LocalDate.of(2026, 6, 16));

        assertThat(plan.date()).isEqualTo(LocalDate.of(2026, 6, 16));
        assertThat(plan.source()).isEqualTo(dayDir.resolve("transactions.yml"));
        assertThat(plan.transactions()).extracting(SmartEsbDailyTransactionPlan.Transaction::name)
                .containsExactly("CaRolloutRepeal", "CaAcctInfoCheck");
        assertThat(plan.transactions()).extracting(SmartEsbDailyTransactionPlan.Transaction::description)
                .containsExactly("转账撤销/冲正", "二三类账户信息验证");
    }

    @Test
    void failsWithLookedUpPathWhenDailyTransactionsFileIsMissing() {
        assertThatThrownBy(() -> new SmartEsbDailyTransactionPlanLoader()
                .load(tempDir, LocalDate.of(2026, 6, 16)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(tempDir.resolve("2026-06-16").resolve("transactions.yml").toString());
    }

    @Test
    void rejectsBlankOrDuplicateTransactionNames() throws Exception {
        Path dayDir = tempDir.resolve("2026-06-16");
        Files.createDirectories(dayDir);
        Files.writeString(dayDir.resolve("transactions.yml"), """
                date: "2026-06-16"
                transactions:
                  - name: "CaRolloutRepeal"
                    description: "转账撤销/冲正"
                  - name: "CaRolloutRepeal"
                    description: "重复"
                """);

        assertThatThrownBy(() -> new SmartEsbDailyTransactionPlanLoader()
                .load(tempDir, LocalDate.of(2026, 6, 16)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate transaction")
                .hasMessageContaining("CaRolloutRepeal");
    }
}
