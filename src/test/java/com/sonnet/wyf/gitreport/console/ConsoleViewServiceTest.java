package com.sonnet.wyf.gitreport.console;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsoleViewServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void runDetailSummarizesTasksDurationAndLatestFailure() {
        WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
        WorkflowRunRecord run = new WorkflowRunRecord(
                7,
                "git-code-contribution-report",
                "full",
                null,
                null,
                LocalDate.of(2026, 7, 13),
                RunState.FAILED,
                "execution",
                "run-7.yml",
                "运行失败",
                null,
                NOW.minusSeconds(600),
                NOW.minusSeconds(300),
                NOW
        );
        List<WorkflowTaskStatus> tasks = List.of(
                new WorkflowTaskStatus(7, "done", "Done", "SUCCEEDED", "execution", null, null, NOW.minusSeconds(30)),
                new WorkflowTaskStatus(7, "failed", "Failed", "FAILED", "execution", null, "任务失败", NOW.minusSeconds(10))
        );
        when(repository.findRun(7)).thenReturn(Optional.of(run));
        when(repository.listTaskStatuses(7)).thenReturn(tasks);
        when(repository.listEvents(7)).thenReturn(List.of(
                new WorkflowRunEvent(1, 7, "TASK_FAILED", "最新失败事件", NOW)
        ));

        ConsoleRunDetailSummary summary = new ConsoleViewService(repository, CLOCK).runDetail(7);

        assertThat(summary.totalTasks()).isEqualTo(2);
        assertThat(summary.succeededTasks()).isEqualTo(1);
        assertThat(summary.failedTasks()).isEqualTo(1);
        assertThat(summary.durationSeconds()).isEqualTo(300);
        assertThat(summary.failureMessage()).isEqualTo("运行失败");
        assertThat(summary.failedTaskKey()).isEqualTo("failed");
        assertThat(summary.lastErrorMessage()).isEqualTo("最新失败事件");
    }

    @Test
    void runDetailUsesTheClockForAnActiveRun() {
        WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
        WorkflowRunRecord run = new WorkflowRunRecord(
                8,
                "git-code-contribution-report",
                "full",
                null,
                null,
                LocalDate.of(2026, 7, 13),
                RunState.RUNNING,
                "execution",
                "run-8.yml",
                null,
                null,
                NOW.minusSeconds(120),
                NOW.minusSeconds(90),
                null
        );
        when(repository.findRun(8)).thenReturn(Optional.of(run));
        when(repository.listTaskStatuses(8)).thenReturn(List.of());
        when(repository.listEvents(8)).thenReturn(List.of());

        assertThat(new ConsoleViewService(repository, CLOCK).runDetail(8).durationSeconds()).isEqualTo(90);
    }
}
