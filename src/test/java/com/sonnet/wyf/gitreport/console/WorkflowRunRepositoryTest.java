package com.sonnet.wyf.gitreport.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRunRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void storesRunsEventsAndTaskStatusesInSqlite() {
        WorkflowRunRepository repository = repository();

        long runId = repository.createRun(new WorkflowRunSubmission(
                "demo-chain",
                "rerun",
                "transaction",
                "A,B",
                LocalDate.of(2026, 6, 29),
                Map.of("demo", true),
                null
        ), tempDir.resolve("run-config.yml").toString());
        repository.appendEvent(runId, "QUEUED", "accepted");
        repository.upsertTaskStatus(new WorkflowTaskStatus(
                runId,
                "transaction:A",
                "A",
                "RUNNING",
                "worker",
                "/tmp/status.json",
                null,
                Instant.now()
        ));

        assertThat(repository.findRun(runId)).get()
                .extracting(WorkflowRunRecord::state, WorkflowRunRecord::chainId, WorkflowRunRecord::runDate)
                .containsExactly(RunState.QUEUED, "demo-chain", LocalDate.of(2026, 6, 29));
        assertThat(repository.listEvents(runId)).extracting(WorkflowRunEvent::eventType).containsExactly("QUEUED");
        assertThat(repository.listTaskStatuses(runId)).extracting(WorkflowTaskStatus::taskKey).containsExactly("transaction:A");
    }

    private WorkflowRunRepository repository() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("console.sqlite"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new WorkflowRunSchema(jdbcTemplate).initialize();
        return new WorkflowRunRepository(jdbcTemplate);
    }
}
