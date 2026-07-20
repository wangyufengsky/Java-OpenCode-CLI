package com.sonnet.wyf.gitreport.console;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Test
    void rerunHistorySubmitsANewRunWithTheSavedConfiguration() throws Exception {
        long sourceRunId = 42L;
        WorkflowRunRecord source = run(sourceRunId, RunState.SUCCEEDED, "/tmp/source-run.yml");
        WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
        WorkflowExecutionService executionService = mock(WorkflowExecutionService.class);
        RunConfigReader configReader = mock(RunConfigReader.class);
        when(repository.findRun(sourceRunId)).thenReturn(Optional.of(source));
        when(configReader.readFlat(java.nio.file.Path.of("/tmp/source-run.yml")))
                .thenReturn(Map.of("project.id", "demo"));
        when(executionService.submit(any())).thenReturn(99L);
        ConsoleApiController controller = controller(repository, executionService, configReader);

        Map<String, Long> response = controller.rerunHistory(sourceRunId);

        assertThat(response).containsEntry("id", 99L);
        org.mockito.ArgumentCaptor<WorkflowRunSubmission> submission =
                org.mockito.ArgumentCaptor.forClass(WorkflowRunSubmission.class);
        verify(executionService).submit(submission.capture());
        assertThat(submission.getValue())
                .extracting(
                        WorkflowRunSubmission::chainId,
                        WorkflowRunSubmission::mode,
                        WorkflowRunSubmission::runDate,
                        WorkflowRunSubmission::config
                )
                .containsExactly(
                        "git-code-contribution-report",
                        "full",
                        LocalDate.parse("2026-07-13"),
                        Map.of("project.id", "demo")
                );
    }

    private static ConsoleApiController controller(
            WorkflowRunRepository repository,
            WorkflowExecutionService executionService,
            RunConfigReader configReader
    ) {
        return new ConsoleApiController(
                mock(ChainCatalog.class),
                repository,
                executionService,
                mock(EventStreamService.class),
                mock(WorkflowScheduleService.class),
                configReader,
                mock(PathPreflightService.class),
                mock(ConsoleViewService.class)
        );
    }

    private static WorkflowRunRecord run(long id, RunState state, String configPath) {
        Instant now = Instant.parse("2026-07-13T08:00:00Z");
        return new WorkflowRunRecord(
                id,
                "git-code-contribution-report",
                "full",
                null,
                null,
                LocalDate.parse("2026-07-13"),
                state,
                state.name().toLowerCase(),
                configPath,
                null,
                null,
                now,
                now,
                now
        );
    }
}
