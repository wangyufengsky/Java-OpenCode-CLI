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
        List<TransactionYaml> transactionYamlList = yaml.transactions == null ? List.of() : yaml.transactions;
        List<ModuleYaml> moduleYamlList = yaml.modules == null ? List.of() : yaml.modules;
        if (transactionYamlList.isEmpty() && moduleYamlList.isEmpty()) {
            throw new IllegalArgumentException("transactions.yml must contain at least one transaction or module: " + source);
        }
        Set<String> names = new LinkedHashSet<>();
        List<SmartEsbDailyTransactionPlan.Transaction> transactions = new ArrayList<>();
        for (TransactionYaml transaction : transactionYamlList) {
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
        List<SmartEsbDailyTransactionPlan.Module> modules = new ArrayList<>();
        for (ModuleYaml module : moduleYamlList) {
            String name = module.name == null ? "" : module.name.trim();
            if (name.isBlank()) {
                throw new IllegalArgumentException("module name cannot be blank: " + source);
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate review item in " + source + ": " + name);
            }
            modules.add(new SmartEsbDailyTransactionPlan.Module(name));
        }
        return new SmartEsbDailyTransactionPlan(date, source, List.copyOf(transactions), List.copyOf(modules));
    }

    static class DailyPlanYaml {
        public String date;
        public List<TransactionYaml> transactions;
        public List<ModuleYaml> modules;
    }

    static class TransactionYaml {
        public String name;
        public String description;
    }

    static class ModuleYaml {
        public String name;
    }
}
