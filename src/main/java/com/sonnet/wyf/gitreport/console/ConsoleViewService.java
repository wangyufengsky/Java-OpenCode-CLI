package com.sonnet.wyf.gitreport.console;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public class ConsoleViewService {
    private final WorkflowRunRepository repository;
    private final Clock clock;

    public ConsoleViewService(WorkflowRunRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
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
