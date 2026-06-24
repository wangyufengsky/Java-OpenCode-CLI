package com.sonnet.wyf.gitreport.workflow.smartesb;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public record SmartEsbDailyTransactionPlan(
        LocalDate date,
        Path source,
        List<Transaction> transactions,
        List<Module> modules
) {
    public SmartEsbDailyTransactionPlan(LocalDate date, Path source, List<Transaction> transactions) {
        this(date, source, transactions, List.of());
    }

    public SmartEsbDailyTransactionPlan {
        transactions = transactions == null ? List.of() : List.copyOf(transactions);
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    public List<ReviewItem> reviewItems() {
        List<ReviewItem> items = new ArrayList<>(transactions.size() + modules.size());
        transactions.forEach(transaction -> items.add(transaction.asReviewItem()));
        modules.forEach(module -> items.add(module.asReviewItem()));
        return List.copyOf(items);
    }

    public record Transaction(String name, String description) {
        public ReviewItem asReviewItem() {
            return new ReviewItem("transaction", name, description == null ? "" : description);
        }
    }

    public record Module(String name) {
        public ReviewItem asReviewItem() {
            return new ReviewItem("module", name, "");
        }
    }

    public record ReviewItem(String kind, String name, String description) {
        public boolean isModule() {
            return "module".equals(kind);
        }

        public boolean isTransaction() {
            return "transaction".equals(kind);
        }
    }
}
