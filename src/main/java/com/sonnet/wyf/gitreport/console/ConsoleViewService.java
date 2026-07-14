package com.sonnet.wyf.gitreport.console;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ConsoleViewService {
    private static final ConsoleText TEXT = new ConsoleText();
    private static final DateTimeFormatter DASHBOARD_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA);
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

    public List<ConsoleMetricView> dashboardMetrics() {
        List<WorkflowRunRecord> runs = repository.listRuns();
        return dashboardMetrics(runs);
    }

    public ConsoleDashboardView dashboardView() {
        List<WorkflowRunRecord> runs = repository.listRuns();
        return new ConsoleDashboardView(
                dashboardMetrics(runs),
                dashboardRuns(runs),
                dashboardAttentionRuns(runs)
        );
    }

    private List<ConsoleMetricView> dashboardMetrics(List<WorkflowRunRecord> runs) {
        LocalDate today = LocalDate.now(clock);
        Instant todayStart = today.atStartOfDay(clock.getZone()).toInstant();
        Instant tomorrowStart = today.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        Instant sevenDayStart = today.minusDays(6).atStartOfDay(clock.getZone()).toInstant();
        Instant previousSevenDayStart = sevenDayStart.minus(Duration.ofDays(7));

        int todayRuns = count(runs, run -> isWithin(run.createdAt(), todayStart, tomorrowStart));
        int running = count(runs, run -> run.state() == RunState.RUNNING);
        int queued = count(runs, run -> run.state() == RunState.QUEUED);
        int failed = count(runs, run -> run.state() == RunState.FAILED);
        List<WorkflowRunRecord> terminalRuns = terminalRunsWithin(runs, sevenDayStart, tomorrowStart);
        List<WorkflowRunRecord> previousTerminalRuns = terminalRunsWithin(runs, previousSevenDayStart, sevenDayStart);
        int successRate = successRatePercent(terminalRuns);
        int previousSuccessRate = successRatePercent(previousTerminalRuns);

        return List.of(
                new ConsoleMetricView(
                        "今日运行", String.valueOf(todayRuns),
                        runs.isEmpty() ? "暂无运行记录" : "今天创建的任务", "primary", "", "neutral"
                ),
                new ConsoleMetricView(
                        "执行中", String.valueOf(running),
                        queued == 0 ? "暂无排队任务" : "另有 " + queued + " 项排队", "info", "", "neutral"
                ),
                new ConsoleMetricView(
                        "近 7 天成功率", successRate + "%",
                        "近 7 天 " + countSucceeded(terminalRuns) + "/" + terminalRuns.size() + " 成功",
                        "success", successTrend(previousTerminalRuns, successRate, previousSuccessRate),
                        successTrendTone(previousTerminalRuns, successRate, previousSuccessRate)
                ),
                new ConsoleMetricView(
                        "需要关注", String.valueOf(failed),
                        failed == 0 ? "暂无失败运行" : "失败运行需要处理", "danger", "", "neutral"
                )
        );
    }

    public List<ConsoleRunListItemView> dashboardRuns() {
        return dashboardRuns(repository.listRuns());
    }

    private List<ConsoleRunListItemView> dashboardRuns(List<WorkflowRunRecord> runs) {
        return runs.stream()
                .sorted(Comparator.comparing(WorkflowRunRecord::createdAt).reversed()
                        .thenComparing(Comparator.comparingLong(WorkflowRunRecord::id).reversed()))
                .limit(8)
                .map(this::toDashboardRun)
                .toList();
    }

    public List<ConsoleRunListItemView> dashboardAttentionRuns() {
        return dashboardAttentionRuns(repository.listRuns());
    }

    private List<ConsoleRunListItemView> dashboardAttentionRuns(List<WorkflowRunRecord> runs) {
        return runs.stream()
                .filter(run -> run.state() == RunState.FAILED)
                .sorted(Comparator.comparing(WorkflowRunRecord::createdAt).reversed()
                        .thenComparing(Comparator.comparingLong(WorkflowRunRecord::id).reversed()))
                .limit(5)
                .map(this::toDashboardRun)
                .toList();
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

    private ConsoleRunListItemView toDashboardRun(WorkflowRunRecord run) {
        return new ConsoleRunListItemView(
                run.id(),
                TEXT.chain(run.chainId()),
                TEXT.mode(run.mode()),
                TEXT.state(run.state()),
                stateTone(run.state()),
                DASHBOARD_TIME.withZone(clock.getZone()).format(run.createdAt()),
                formatDuration(durationSeconds(run), run.startedAt() != null),
                run.failureMessage() == null ? "" : run.failureMessage()
        );
    }

    private static List<WorkflowRunRecord> terminalRunsWithin(
            List<WorkflowRunRecord> runs,
            Instant startInclusive,
            Instant endExclusive
    ) {
        return runs.stream()
                .filter(run -> isWithin(run.createdAt(), startInclusive, endExclusive))
                .filter(run -> run.state() == RunState.SUCCEEDED || run.state() == RunState.FAILED)
                .toList();
    }

    private static boolean isWithin(Instant value, Instant startInclusive, Instant endExclusive) {
        return !value.isBefore(startInclusive) && value.isBefore(endExclusive);
    }

    private static int successRatePercent(List<WorkflowRunRecord> terminalRuns) {
        return terminalRuns.isEmpty() ? 0 : (int) Math.round(countSucceeded(terminalRuns) * 100.0 / terminalRuns.size());
    }

    private static long countSucceeded(List<WorkflowRunRecord> terminalRuns) {
        return terminalRuns.stream().filter(run -> run.state() == RunState.SUCCEEDED).count();
    }

    private static String successTrend(List<WorkflowRunRecord> previousRuns, int successRate, int previousSuccessRate) {
        if (previousRuns.isEmpty()) {
            return "无历史对比";
        }
        int difference = successRate - previousSuccessRate;
        if (difference == 0) {
            return "较前 7 天持平";
        }
        return (difference > 0 ? "+" : "") + difference + " 个百分点";
    }

    private static String successTrendTone(List<WorkflowRunRecord> previousRuns, int successRate, int previousSuccessRate) {
        if (previousRuns.isEmpty()) {
            return "neutral";
        }
        int difference = successRate - previousSuccessRate;
        if (difference > 0) {
            return "positive";
        }
        return difference < 0 ? "danger" : "neutral";
    }

    private static String stateTone(RunState state) {
        return switch (state) {
            case QUEUED -> "warning";
            case RUNNING -> "running";
            case SUCCEEDED -> "success";
            case FAILED -> "danger";
        };
    }

    private static String formatDuration(long seconds, boolean started) {
        if (!started) {
            return "未开始";
        }
        if (seconds < 60) {
            return "少于 1 分钟";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        if (hours < 24) {
            return remainingMinutes == 0 ? hours + " 小时" : hours + " 小时 " + remainingMinutes + " 分钟";
        }
        long days = hours / 24;
        long remainingHours = hours % 24;
        return remainingHours == 0 ? days + " 天" : days + " 天 " + remainingHours + " 小时";
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
