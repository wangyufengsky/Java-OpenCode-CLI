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

    @Test
    void filtersRunsByQueryStateAndChainWithBoundValues() {
        WorkflowRunRepository repository = repository();
        long matchingRunId = repository.createRun(new WorkflowRunSubmission(
                "weekly-engineering-report",
                "full",
                null,
                null,
                LocalDate.of(2026, 7, 13),
                Map.of(),
                null
        ), tempDir.resolve("weekly-config.yml").toString());
        repository.markFailed(matchingRunId, "review failed");
        repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report",
                "full",
                null,
                null,
                LocalDate.of(2026, 7, 13),
                Map.of(),
                null
        ), tempDir.resolve("other-config.yml").toString());

        WorkflowRunFilter filter = new WorkflowRunFilter(
                " weekly ",
                RunState.FAILED,
                " weekly-engineering-report ",
                null,
                null
        );

        assertThat(repository.listRuns(filter))
                .extracting(WorkflowRunRecord::id, WorkflowRunRecord::chainId, WorkflowRunRecord::state)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        matchingRunId,
                        "weekly-engineering-report",
                        RunState.FAILED
                ));
    }

    @Test
    void textQueryCanMatchRunIdAndConfigPath() {
        WorkflowRunRepository repository = repository();
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), tempDir.resolve("unique-history-config.yml").toString());

        assertThat(repository.listRuns(new WorkflowRunFilter(
                String.valueOf(runId), null, null, null, null
        ))).extracting(WorkflowRunRecord::id).contains(runId);
        assertThat(repository.listRuns(new WorkflowRunFilter(
                "unique-history", null, null, null, null
        ))).extracting(WorkflowRunRecord::id).containsExactly(runId);
    }

    private WorkflowRunRepository repository() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("console.sqlite"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new WorkflowRunSchema(jdbcTemplate).initialize();
        return new WorkflowRunRepository(jdbcTemplate);
    }
}
