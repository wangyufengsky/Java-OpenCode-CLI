package com.sonnet.wyf.gitreport.workflow.smartesb;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

public record SmartEsbDailyTransactionPlan(
        LocalDate date,
        Path source,
        List<Transaction> transactions
) {
    public record Transaction(String name, String description) {
    }
}
