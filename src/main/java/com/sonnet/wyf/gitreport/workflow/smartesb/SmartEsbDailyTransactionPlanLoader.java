package com.sonnet.wyf.gitreport.workflow.smartesb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SmartEsbDailyTransactionPlanLoader {
    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    public SmartEsbDailyTransactionPlan load(Path transactionPlanDir, LocalDate runDate) throws IOException {
        LocalDate date = runDate == null ? LocalDate.now() : runDate;
        Path source = transactionPlanDir.resolve(date.toString()).resolve("transactions.yml");
        if (!Files.exists(source)) {
            throw new IllegalStateException("SmartESB transactions.yml not found: " + source);
        }
        DailyPlanYaml yaml = objectMapper.readValue(source.toFile(), DailyPlanYaml.class);
        LocalDate declaredDate = yaml.date == null || yaml.date.isBlank() ? date : LocalDate.parse(yaml.date);
        if (!declaredDate.equals(date)) {
            throw new IllegalArgumentException("transactions.yml date " + declaredDate + " does not match run date " + date + ": " + source);
        }
        if (yaml.transactions == null || yaml.transactions.isEmpty()) {
            throw new IllegalArgumentException("transactions.yml must contain at least one transaction: " + source);
        }
        Set<String> names = new LinkedHashSet<>();
        List<SmartEsbDailyTransactionPlan.Transaction> transactions = new ArrayList<>();
        for (TransactionYaml transaction : yaml.transactions) {
            String name = transaction.name == null ? "" : transaction.name.trim();
            if (name.isBlank()) {
                throw new IllegalArgumentException("transaction name cannot be blank: " + source);
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate transaction in " + source + ": " + name);
            }
            String description = transaction.description == null ? "" : transaction.description.trim();
            transactions.add(new SmartEsbDailyTransactionPlan.Transaction(name, description));
        }
        return new SmartEsbDailyTransactionPlan(date, source, List.copyOf(transactions));
    }

    static class DailyPlanYaml {
        public String date;
        public List<TransactionYaml> transactions;
    }

    static class TransactionYaml {
        public String name;
        public String description;
    }
}
