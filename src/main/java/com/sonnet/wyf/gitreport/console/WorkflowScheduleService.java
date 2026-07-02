package com.sonnet.wyf.gitreport.console;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WorkflowScheduleService implements AutoCloseable {
    private final WorkflowScheduleRepository repository;
    private final WorkflowRunSubmitter submitter;
    private final ChainCatalog chainCatalog;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean triggering = new AtomicBoolean(false);

    public WorkflowScheduleService(
            WorkflowScheduleRepository repository,
            WorkflowRunSubmitter submitter,
            ChainCatalog chainCatalog,
            Clock clock
    ) {
        this(repository, submitter, chainCatalog, clock, true);
    }

    WorkflowScheduleService(
            WorkflowScheduleRepository repository,
            WorkflowRunSubmitter submitter,
            ChainCatalog chainCatalog,
            Clock clock,
            boolean autoStart
    ) {
        this.repository = repository;
        this.submitter = submitter;
        this.chainCatalog = chainCatalog;
        this.clock = clock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "workflow-schedule-trigger"));
        if (autoStart) {
            scheduler.scheduleWithFixedDelay(() -> triggerDueSchedules(clock.instant()), 10, 30, TimeUnit.SECONDS);
        }
    }

    public long create(WorkflowScheduleRequest request) {
        WorkflowScheduleRequest normalized = normalizeAndValidate(request);
        Instant nextTriggerAt = normalized.enabled() ? nextTrigger(normalized, clock.instant()) : null;
        return repository.createSchedule(normalized, nextTriggerAt);
    }

    public List<WorkflowScheduleRecord> list() {
        return repository.listSchedules();
    }

    public WorkflowScheduleRecord setEnabled(long id, boolean enabled) {
        WorkflowScheduleRecord schedule = repository.findSchedule(id).orElseThrow(NoSuchElementException::new);
        Instant nextTriggerAt = enabled ? nextTrigger(schedule, clock.instant()) : null;
        repository.updateEnabled(id, enabled, nextTriggerAt);
        return repository.findSchedule(id).orElseThrow(NoSuchElementException::new);
    }

    public void triggerDueSchedules(Instant now) {
        if (!triggering.compareAndSet(false, true)) {
            return;
        }
        try {
            for (WorkflowScheduleRecord schedule : repository.listDueSchedules(now)) {
                submitter.submit(new WorkflowRunSubmission(
                        schedule.chainId(),
                        schedule.mode(),
                        schedule.rerunType(),
                        schedule.rerunId(),
                        schedule.runDate(),
                        schedule.config(),
                        null
                ));
                Instant nextTriggerAt = nextTriggerAfterTrigger(schedule, now);
                boolean enabled = schedule.frequency() != ScheduleFrequency.ONCE;
                repository.markTriggered(schedule.id(), now, nextTriggerAt, enabled);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("定时任务触发失败", exception);
        } finally {
            triggering.set(false);
        }
    }

    private WorkflowScheduleRequest normalizeAndValidate(WorkflowScheduleRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("定时任务不能为空");
        }
        String chainId = normalizeText(request.chainId());
        chainCatalog.chain(chainId);
        String mode = normalizeText(request.mode() == null || request.mode().isBlank() ? "full" : request.mode());
        if (!"full".equals(mode) && !"rerun".equals(mode)) {
            throw new IllegalArgumentException("运行模式必须是 full 或 rerun");
        }
        String rerunType = WorkflowRerunContract.normalizeType(chainId, request.rerunType());
        String rerunId = request.rerunId() == null ? null : request.rerunId().trim();
        if ("rerun".equals(mode)) {
            if (rerunType.isBlank()) {
                throw new IllegalArgumentException("重跑模式必须填写重跑类型");
            }
            if (!WorkflowRerunContract.isKnownType(chainId, rerunType)) {
                throw new IllegalArgumentException("不支持的重跑类型: " + request.rerunType());
            }
            if (WorkflowRerunContract.requiresRerunId(chainId, rerunType) && (rerunId == null || rerunId.isBlank())) {
                throw new IllegalArgumentException("重跑模式必须填写重跑 ID");
            }
        }
        ScheduleFrequency frequency = ScheduleFrequency.parse(request.frequency());
        validateScheduleFields(request, frequency);
        return new WorkflowScheduleRequest(
                chainId,
                mode,
                rerunType,
                rerunId,
                request.runDate(),
                ConsoleConfigNormalizer.normalize(request.config()),
                frequency.name().toLowerCase(Locale.ROOT),
                request.dayOfWeek(),
                request.runTime(),
                request.runAt(),
                request.enabled()
        );
    }

    private static void validateScheduleFields(WorkflowScheduleRequest request, ScheduleFrequency frequency) {
        if (frequency == ScheduleFrequency.DAILY && request.runTime() == null) {
            throw new IllegalArgumentException("每天执行必须填写执行时间");
        }
        if (frequency == ScheduleFrequency.WEEKLY) {
            if (request.runTime() == null) {
                throw new IllegalArgumentException("每周执行必须填写执行时间");
            }
            if (request.dayOfWeek() == null || request.dayOfWeek() < 1 || request.dayOfWeek() > 7) {
                throw new IllegalArgumentException("每周执行必须选择星期");
            }
        }
        if (frequency == ScheduleFrequency.ONCE && request.runAt() == null) {
            throw new IllegalArgumentException("一次性执行必须填写执行日期时间");
        }
    }

    private Instant nextTrigger(WorkflowScheduleRequest request, Instant base) {
        ScheduleFrequency frequency = ScheduleFrequency.parse(request.frequency());
        return switch (frequency) {
            case DAILY -> nextDaily(request.runTime(), base);
            case WEEKLY -> nextWeekly(request.dayOfWeek(), request.runTime(), base);
            case ONCE -> request.runAt().atZone(zone()).toInstant();
        };
    }

    private Instant nextTrigger(WorkflowScheduleRecord schedule, Instant base) {
        return switch (schedule.frequency()) {
            case DAILY -> nextDaily(schedule.runTime(), base);
            case WEEKLY -> nextWeekly(schedule.dayOfWeek(), schedule.runTime(), base);
            case ONCE -> schedule.runAt().atZone(zone()).toInstant();
        };
    }

    private Instant nextTriggerAfterTrigger(WorkflowScheduleRecord schedule, Instant triggeredAt) {
        return switch (schedule.frequency()) {
            case DAILY -> nextDaily(schedule.runTime(), triggeredAt);
            case WEEKLY -> nextWeekly(schedule.dayOfWeek(), schedule.runTime(), triggeredAt);
            case ONCE -> null;
        };
    }

    private Instant nextDaily(LocalTime runTime, Instant base) {
        LocalDateTime localBase = LocalDateTime.ofInstant(base, zone());
        LocalDateTime candidate = LocalDate.ofInstant(base, zone()).atTime(runTime);
        if (!candidate.isAfter(localBase)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.atZone(zone()).toInstant();
    }

    private Instant nextWeekly(Integer dayOfWeek, LocalTime runTime, Instant base) {
        LocalDateTime localBase = LocalDateTime.ofInstant(base, zone());
        LocalDate candidateDate = LocalDate.ofInstant(base, zone())
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dayOfWeek)));
        LocalDateTime candidate = candidateDate.atTime(runTime);
        if (!candidate.isAfter(localBase)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate.atZone(zone()).toInstant();
    }

    private ZoneId zone() {
        return clock.getZone();
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
