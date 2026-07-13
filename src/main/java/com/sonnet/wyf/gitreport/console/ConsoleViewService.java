package com.sonnet.wyf.gitreport.console;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public class ConsoleViewService {
    private final WorkflowRunRepository repository;
    private final Clock clock;

    public ConsoleViewService(WorkflowRunRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public ConsoleDashboardSummary dashboard() {
        List<WorkflowRunRecord> runs = repository.listRuns();
        LocalDate today = LocalDate.now(clock);
        Instant todayStart = today.atStartOfDay(clock.getZone()).toInstant();
        Instant tomorrowStart = today.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        Instant sevenDayStart = today.minusDays(6).atStartOfDay(clock.getZone()).toInstant();

        int todayRuns = count(runs, run -> !run.createdAt().isBefore(todayStart)
                && run.createdAt().isBefore(tomorrowStart));
        int running = count(runs, run -> run.state() == RunState.RUNNING);
        int queued = count(runs, run -> run.state() == RunState.QUEUED);
        int succeeded = count(runs, run -> run.state() == RunState.SUCCEEDED);
        int failed = count(runs, run -> run.state() == RunState.FAILED);

        List<WorkflowRunRecord> terminalRuns = runs.stream()
                .filter(run -> !run.createdAt().isBefore(sevenDayStart))
                .filter(run -> run.createdAt().isBefore(tomorrowStart))
                .filter(run -> run.state() == RunState.SUCCEEDED || run.state() == RunState.FAILED)
                .toList();
        long sevenDaySucceeded = terminalRuns.stream()
                .filter(run -> run.state() == RunState.SUCCEEDED)
                .count();
        int successRatePercent = terminalRuns.isEmpty()
                ? 0
                : (int) Math.round(sevenDaySucceeded * 100.0 / terminalRuns.size());

        List<WorkflowRunRecord> attentionRuns = runs.stream()
                .filter(run -> run.state() == RunState.FAILED)
                .sorted(Comparator.comparing(WorkflowRunRecord::createdAt).reversed()
                        .thenComparing(Comparator.comparingLong(WorkflowRunRecord::id).reversed()))
                .limit(5)
                .toList();
        return new ConsoleDashboardSummary(
                todayRuns,
                running,
                queued,
                succeeded,
                failed,
                successRatePercent,
                attentionRuns
        );
    }

    public ConsoleRunDetailSummary runDetail(long runId) {
        WorkflowRunRecord run = repository.findRun(runId).orElseThrow();
        List<WorkflowTaskStatus> tasks = repository.listTaskStatuses(runId);
        List<WorkflowTaskStatus> failedTasks = tasks.stream()
                .filter(task -> "FAILED".equalsIgnoreCase(task.state()))
                .sorted(Comparator.comparing(WorkflowTaskStatus::updatedAt).reversed()
                        .thenComparing(WorkflowTaskStatus::taskKey))
                .toList();

        WorkflowTaskStatus latestFailedTask = failedTasks.stream().findFirst().orElse(null);
        String lastFailedEventMessage = repository.listEvents(runId).stream()
                .filter(event -> containsFailed(event.eventType()))
                .max(Comparator.comparingLong(WorkflowRunEvent::id))
                .map(WorkflowRunEvent::message)
                .orElse(null);
        String lastErrorMessage = hasText(lastFailedEventMessage)
                ? lastFailedEventMessage
                : latestFailedTask == null ? null : latestFailedTask.errorMessage();

        return new ConsoleRunDetailSummary(
                tasks.size(),
                countTasks(tasks, "SUCCEEDED"),
                failedTasks.size(),
                durationSeconds(run),
                run.failureMessage(),
                latestFailedTask == null ? null : latestFailedTask.taskKey(),
                lastErrorMessage
        );
    }

    private long durationSeconds(WorkflowRunRecord run) {
        if (run.startedAt() == null) {
            return 0;
        }
        Instant finishedAtOrNow = run.finishedAt() == null ? clock.instant() : run.finishedAt();
        return Math.max(0L, Duration.between(run.startedAt(), finishedAtOrNow).getSeconds());
    }

    private static int count(List<WorkflowRunRecord> runs, java.util.function.Predicate<WorkflowRunRecord> predicate) {
        return Math.toIntExact(runs.stream().filter(predicate).count());
    }

    private static int countTasks(List<WorkflowTaskStatus> tasks, String state) {
        return Math.toIntExact(tasks.stream().filter(task -> state.equalsIgnoreCase(task.state())).count());
    }

    private static boolean containsFailed(String eventType) {
        return eventType != null && eventType.toUpperCase(java.util.Locale.ROOT).contains("FAILED");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
