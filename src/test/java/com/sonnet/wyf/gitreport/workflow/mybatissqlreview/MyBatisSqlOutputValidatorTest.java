package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisSqlOutputValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MyBatisSqlOutputValidator validator = new MyBatisSqlOutputValidator(objectMapper);
    @TempDir Path tempDir;

    @Test
    void acceptsCurrentNativeDatabaseEvidenceFields() throws Exception {
        Path candidates = candidates();
        assertThat(validator.validatePublishedOffline(candidates, expected()).scenarioCount()).isEqualTo(1);
    }

    @Test
    void rejectsLegacyOrUnboundDatabaseEvidenceFields() throws Exception {
        Path candidates = candidates();
        ObjectNode evidence = (ObjectNode) objectMapper.readTree(candidates.resolve("database-evidence.json").toFile());
        evidence.remove("data_source");
        evidence.put("connection_id", "legacy-source");
        objectMapper.writeValue(candidates.resolve("database-evidence.json").toFile(), evidence);
        assertThatThrownBy(() -> validator.validatePublishedOffline(candidates, expected()))
                .hasMessageContaining("data_source");
    }

    @Test
    void rejectsScenarioWithoutTheExactNativeMaxRowsContract() throws Exception {
        Path candidates = candidates();
        ObjectNode evidence = (ObjectNode) objectMapper.readTree(candidates.resolve("database-evidence.json").toFile());
        ((ObjectNode) evidence.at("/scenarios/0/arguments")).put("maxRows", 2);
        objectMapper.writeValue(candidates.resolve("database-evidence.json").toFile(), evidence);
        assertThatThrownBy(() -> validator.validatePublishedOffline(candidates, expected()))
                .hasMessageContaining("maxRows");
    }

    private MyBatisSqlOutputValidator.ExpectedTaskContext expected() {
        return new MyBatisSqlOutputValidator.ExpectedTaskContext("mapper-order-find-open", "mappers/OrderMapper.xml",
                "com.example.OrderMapper", "findOpen", "select", false);
    }

    private Path candidates() throws IOException {
        Path directory = Files.createDirectory(tempDir.resolve("candidates-" + System.nanoTime()));
        for (String name : new String[]{"report-valid.md", "sql-summary-valid.json", "database-evidence-valid.json"}) {
            String destination = switch (name) { case "report-valid.md" -> "report.md"; case "sql-summary-valid.json" -> "summary.json"; default -> "database-evidence.json"; };
            try (InputStream input = getClass().getResourceAsStream("/mybatis-sql-review-fixtures/" + name)) {
                if (input == null) throw new IllegalStateException("missing fixture " + name);
                Files.copy(input, directory.resolve(destination));
            }
        }
        return directory;
    }
}
