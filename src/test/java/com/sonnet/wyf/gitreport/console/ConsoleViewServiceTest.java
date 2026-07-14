package com.sonnet.wyf.gitreport.console;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsoleViewServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void dashboardUsesZeroMetricsAndNoRowsWhenThereAreNoPersistedRuns() {
        WorkflowRunRepository repository = repositoryWith(List.of());
        ConsoleViewService service = new ConsoleViewService(repository, CLOCK);

        assertThat(service.dashboardMetrics())
                .extracting(ConsoleMetricView::value)
                .containsExactly("0", "0", "0%", "0");
        assertThat(service.dashboardRuns()).isEmpty();
        assertThat(service.dashboardMetrics().get(0).detail()).isEqualTo("暂无运行记录");
    }

    @Test
    void dashboardMapsRunningAndFailedRunsToFormattedPresentationValues() {
        WorkflowRunRepository repository = repositoryWith(List.of(
                run(5, RunState.FAILED, "2026-07-13T11:45:00Z", "2026-07-13T11:00:00Z", "2026-07-13T11:40:00Z", "采集失败"),
                run(4, RunState.RUNNING, "2026-07-13T10:00:00Z", "2026-07-13T10:00:00Z", null, null)
        ));
        ConsoleViewService service = new ConsoleViewService(repository, CLOCK);

        assertThat(service.dashboardRuns()).extracting(
                        ConsoleRunListItemView::id,
                        ConsoleRunListItemView::stateLabel,
                        ConsoleRunListItemView::stateTone,
                        ConsoleRunListItemView::durationLabel,
                        ConsoleRunListItemView::failureMessage
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(5L, "已失败", "danger", "40 分钟", "采集失败"),
                        org.assertj.core.groups.Tuple.tuple(4L, "运行中", "running", "2 小时", "")
                );
    }

    @Test
    void dashboardDerivesSevenDaySuccessTrendFromTerminalRuns() {
        WorkflowRunRepository repository = repositoryWith(List.of(
                run(4, RunState.SUCCEEDED, "2026-07-12T08:00:00Z", "2026-07-12T08:00:00Z", "2026-07-12T08:15:00Z", null),
                run(3, RunState.FAILED, "2026-07-08T08:00:00Z", "2026-07-08T08:00:00Z", "2026-07-08T08:10:00Z", "失败"),
                run(2, RunState.FAILED, "2026-07-05T08:00:00Z", "2026-07-05T08:00:00Z", "2026-07-05T08:10:00Z", "失败"),
                run(1, RunState.FAILED, "2026-07-02T08:00:00Z", "2026-07-02T08:00:00Z", "2026-07-02T08:10:00Z", "失败")
        ));
        ConsoleViewService service = new ConsoleViewService(repository, CLOCK);

        ConsoleMetricView successRate = service.dashboardMetrics().get(2);
        assertThat(successRate.value()).isEqualTo("50%");
        assertThat(successRate.detail()).isEqualTo("近 7 天 1/2 成功");
        assertThat(successRate.trend()).isEqualTo("+50 个百分点");
        assertThat(successRate.trendTone()).isEqualTo("positive");
    }

    @Test
    void dashboardMarksADecliningSuccessRateAsDanger() {
        WorkflowRunRepository repository = repositoryWith(List.of(
                run(4, RunState.FAILED, "2026-07-12T08:00:00Z", "2026-07-12T08:00:00Z", "2026-07-12T08:10:00Z", "失败"),
                run(3, RunState.SUCCEEDED, "2026-07-05T08:00:00Z", "2026-07-05T08:00:00Z", "2026-07-05T08:10:00Z", null)
        ));
        ConsoleViewService service = new ConsoleViewService(repository, CLOCK);

        ConsoleMetricView successRate = service.dashboardMetrics().get(2);
        assertThat(successRate.value()).isEqualTo("0%");
        assertThat(successRate.trend()).isEqualTo("-100 个百分点");
        assertThat(successRate.trendTone()).isEqualTo("danger");
    }

    @Test
    void dashboardMarksAnInitialSuccessRateTrendAsNeutral() {
        WorkflowRunRepository repository = repositoryWith(List.of(
                run(4, RunState.SUCCEEDED, "2026-07-12T08:00:00Z", "2026-07-12T08:00:00Z", "2026-07-12T08:15:00Z", null)
        ));
        ConsoleViewService service = new ConsoleViewService(repository, CLOCK);

        assertThat(service.dashboardMetrics().get(2).trendTone()).isEqualTo("neutral");
    }

    @Test
    void dashboardViewDerivesEverySectionFromOneRepositorySnapshot() {
        WorkflowRunRecord failed = run(5, RunState.FAILED,
                "2026-07-13T11:45:00Z", "2026-07-13T11:00:00Z", "2026-07-13T11:40:00Z", "采集失败");
        WorkflowRunRecord running = run(4, RunState.RUNNING,
                "2026-07-13T10:00:00Z", "2026-07-13T10:00:00Z", null, null);
        WorkflowRunRepository repository = repositoryWith(List.of(failed, running));
        ConsoleViewService service = new ConsoleViewService(repository, CLOCK);

        ConsoleDashboardView dashboard = service.dashboardView();

        assertThat(dashboard.metrics()).extracting(ConsoleMetricView::value)
                .containsExactly("2", "1", "0%", "1");
        assertThat(dashboard.runs()).extracting(ConsoleRunListItemView::id)
                .containsExactly(5L, 4L);
        assertThat(dashboard.attentionRuns()).extracting(ConsoleRunListItemView::id)
                .containsExactly(5L);
        verify(repository, times(1)).listRuns();
    }

    private static WorkflowRunRepository repositoryWith(List<WorkflowRunRecord> runs) {
        WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
        when(repository.listRuns()).thenReturn(runs);
        return repository;
    }

    private static WorkflowRunRecord run(
            long id,
            RunState state,
            String createdAt,
            String startedAt,
            String finishedAt,
            String failureMessage
    ) {
        return new WorkflowRunRecord(
                id,
                "git-code-contribution-report",
                "full",
                null,
                null,
                LocalDate.of(2026, 7, 13),
                state,
                state.name().toLowerCase(),
                "run-" + id + ".yml",
                failureMessage,
                null,
                Instant.parse(createdAt),
                startedAt == null ? null : Instant.parse(startedAt),
                finishedAt == null ? null : Instant.parse(finishedAt)
        );
    }
}
