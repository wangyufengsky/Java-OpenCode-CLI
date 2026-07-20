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
    void listsOnlyEventsAfterTheBoundEventIdInAscendingOrder() {
        WorkflowRunRepository repository = repository();
        long runId = repository.createRun(new WorkflowRunSubmission(
                "demo-chain", "full", null, null, null, Map.of(), null
        ), tempDir.resolve("incremental-events.yml").toString());
        repository.appendEvent(runId, "QUEUED", "first");
        repository.appendEvent(runId, "STARTED", "second");
        repository.appendEvent(runId, "TASK_RUNNING", "third");
        var events = repository.listEvents(runId);

        assertThat(repository.listEventsAfter(runId, events.getFirst().id()))
                .extracting(WorkflowRunEvent::id)
                .containsExactly(events.get(1).id(), events.get(2).id());
        assertThat(repository.listEventsAfter(runId, -10))
                .extracting(WorkflowRunEvent::id)
                .containsExactlyElementsOf(events.stream().map(WorkflowRunEvent::id).toList());
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

    @Test
    void dateFiltersUseUtcDayBoundariesForFractionalSecondTimestamps() {
        WorkflowRunRepository repository = repository();
        long startDayRunId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "start-day.yml");
        long nextDayRunId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "next-day.yml");
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update("update workflow_runs set created_at=? where id=?",
                "2026-07-13T00:00:00.123Z", startDayRunId);
        jdbcTemplate.update("update workflow_runs set created_at=? where id=?",
                "2026-07-14T00:00:00.123Z", nextDayRunId);

        WorkflowRunFilter filter = new WorkflowRunFilter(
                null, null, null, LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 13)
        );

        assertThat(repository.listRuns(filter))
                .extracting(WorkflowRunRecord::id)
                .containsExactly(startDayRunId);
        assertThat(repository.countRuns(filter)).isEqualTo(1);
        assertThat(repository.listRuns(filter, 20, 0))
                .extracting(WorkflowRunRecord::id)
                .containsExactly(startDayRunId);
    }

    @Test
    void textQueryTreatsPercentAndUnderscoreAsLiteralCharacters() {
        WorkflowRunRepository repository = repository();
        long percentRunId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "percent%marker.yml");
        repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "percentXmarker.yml");
        long underscoreRunId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "under_score.yml");
        repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "underXscore.yml");

        assertThat(repository.listRuns(new WorkflowRunFilter(
                "%", null, null, null, null
        ))).extracting(WorkflowRunRecord::id).containsExactly(percentRunId);
        assertThat(repository.listRuns(new WorkflowRunFilter(
                "_", null, null, null, null
        ))).extracting(WorkflowRunRecord::id).containsExactly(underscoreRunId);
    }

    @Test
    void countsAndPagesRunsAfterAllBoundFilterArguments() {
        WorkflowRunRepository repository = repository();
        WorkflowRunFilter filter = new WorkflowRunFilter(
                "paged-history", RunState.FAILED, "weekly-engineering-report", null, null
        );
        for (int index = 0; index < 41; index++) {
            long runId = repository.createRun(new WorkflowRunSubmission(
                    "weekly-engineering-report", "full", null, null, null, Map.of(), null
            ), "paged-history-" + index + ".yml");
            repository.markFailed(runId, "paged history failure");
        }
        repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "paged-history-other-chain.yml");

        assertThat(repository.countRuns(filter)).isEqualTo(41);
        assertThat(repository.listRuns(filter, 20, 0)).hasSize(20)
                .allSatisfy(run -> assertThat(run.chainId()).isEqualTo("weekly-engineering-report"));
        assertThat(repository.listRuns(filter, 20, 20)).hasSize(20);
        assertThat(repository.listRuns(filter, 20, 40)).hasSize(1);
    }

    @Test
    void deletesOneTerminalRunWithItsEventsAndTaskStatuses() {
        WorkflowRunRepository repository = repository();
        long runId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "delete-one-history.yml");
        repository.appendEvent(runId, "QUEUED", "accepted");
        repository.upsertTaskStatus(new WorkflowTaskStatus(
                runId, "task-a", "Task A", "FAILED", "execution", null, "failed", Instant.now()
        ));
        repository.markFailed(runId, "failed");

        assertThat(repository.deleteTerminalRun(runId)).isTrue();

        assertThat(repository.findRun(runId)).isEmpty();
        assertThat(repository.listEvents(runId)).isEmpty();
        assertThat(repository.listTaskStatuses(runId)).isEmpty();
    }

    @Test
    void clearingHistoryDeletesOnlyTerminalRunsAndPreservesActiveRuns() {
        WorkflowRunRepository repository = repository();
        long succeededRunId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "clear-succeeded-history.yml");
        repository.markSucceeded(succeededRunId);
        long failedRunId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "clear-failed-history.yml");
        repository.markFailed(failedRunId, "failed");
        long queuedRunId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "keep-queued-history.yml");
        long runningRunId = repository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, Map.of(), null
        ), "keep-running-history.yml");
        repository.markRunning(runningRunId);

        assertThat(repository.clearTerminalRuns()).isEqualTo(2);

        assertThat(repository.listRuns()).extracting(WorkflowRunRecord::id)
                .containsExactly(runningRunId, queuedRunId);
    }

    private WorkflowRunRepository repository() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        new WorkflowRunSchema(jdbcTemplate).initialize();
        return new WorkflowRunRepository(jdbcTemplate);
    }

    private JdbcTemplate jdbcTemplate() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("console.sqlite"));
        return new JdbcTemplate(dataSource);
    }
}
