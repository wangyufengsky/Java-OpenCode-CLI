package com.sonnet.wyf.gitreport.console;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ConsoleApiControllerTest {

    @Test
    void runSnapshotCapturesEventWatermarkBeforeReadingState() {
        long runId = 42L;
        Instant now = Instant.parse("2026-07-13T08:00:00Z");
        WorkflowRunRecord run = new WorkflowRunRecord(
                runId,
                "git-code-contribution-report",
                "full",
                null,
                null,
                LocalDate.parse("2026-07-13"),
                RunState.RUNNING,
                "execution",
                "run.yml",
                null,
                null,
                now,
                now,
                null
        );
        ConsoleRunDetailSummary summary = new ConsoleRunDetailSummary(
                1, 0, 0, 12L, null, null, null
        );
        WorkflowTaskStatus task = new WorkflowTaskStatus(
                runId, "task-a", "Task A", "RUNNING", "execution", null, null, now
        );
        WorkflowRunEvent event = new WorkflowRunEvent(
                9L, runId, "TASK_RUNNING", "task-a execution", now
        );

        WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
        ConsoleViewService viewService = mock(ConsoleViewService.class);
        when(repository.listEventsAfter(runId, 0L)).thenReturn(List.of(event));
        when(repository.findRun(runId)).thenReturn(Optional.of(run));
        when(viewService.runDetail(runId)).thenReturn(summary);
        when(repository.listTaskStatuses(runId)).thenReturn(List.of(task));
        ConsoleApiController controller = new ConsoleApiController(
                mock(ChainCatalog.class),
                repository,
                mock(WorkflowExecutionService.class),
                mock(EventStreamService.class),
                mock(WorkflowScheduleService.class),
                mock(RunConfigReader.class),
                mock(PathPreflightService.class),
                viewService
        );

        ConsoleApiController.RunSnapshotResponse response = controller.runSnapshot(runId, -99L);

        assertThat(response.run()).isSameAs(run);
        assertThat(response.summary()).isSameAs(summary);
        assertThat(response.tasks()).containsExactly(task);
        assertThat(response.events()).containsExactly(event);
        assertThat(response.rerunAction()).isEqualTo(FailedTaskRerunAction.hidden());

        InOrder order = inOrder(repository, viewService);
        order.verify(repository).listEventsAfter(runId, 0L);
        order.verify(repository).findRun(runId);
        order.verify(viewService).runDetail(runId);
        order.verify(repository).listTaskStatuses(runId);
        verifyNoMoreInteractions(repository, viewService);
    }
}
