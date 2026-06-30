package com.sonnet.wyf.gitreport.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowScheduleRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void storesDailyScheduleWithRunConfiguration() {
        WorkflowScheduleRepository repository = repository();
        WorkflowScheduleRequest request = new WorkflowScheduleRequest(
                "git-code-contribution-report",
                "full",
                null,
                null,
                LocalDate.of(2026, 6, 30),
                Map.of("project.id", "demo", "detail-input.top-files", 5),
                "daily",
                null,
                LocalTime.of(6, 0),
                null,
                true
        );

        long scheduleId = repository.createSchedule(request, Instant.parse("2026-06-29T22:00:00Z"));

        assertThat(repository.findSchedule(scheduleId)).get()
                .satisfies(schedule -> {
                    assertThat(schedule.chainId()).isEqualTo("git-code-contribution-report");
                    assertThat(schedule.frequency()).isEqualTo(ScheduleFrequency.DAILY);
                    assertThat(schedule.runTime()).isEqualTo(LocalTime.of(6, 0));
                    assertThat(schedule.config()).containsEntry("project.id", "demo");
                    assertThat(schedule.nextTriggerAt()).isEqualTo(Instant.parse("2026-06-29T22:00:00Z"));
                    assertThat(schedule.enabled()).isTrue();
                });
    }

    @Test
    void updatesWeeklyScheduleAfterTrigger() {
        WorkflowScheduleRepository repository = repository();
        long scheduleId = repository.createSchedule(new WorkflowScheduleRequest(
                "weekly-engineering-report",
                "full",
                null,
                null,
                null,
                Map.of("project.id", "weekly"),
                "weekly",
                DayOfWeek.FRIDAY.getValue(),
                LocalTime.of(6, 0),
                null,
                true
        ), Instant.parse("2026-07-02T22:00:00Z"));

        repository.markTriggered(scheduleId, Instant.parse("2026-07-02T22:00:01Z"), Instant.parse("2026-07-09T22:00:00Z"), true);

        assertThat(repository.findSchedule(scheduleId)).get()
                .satisfies(schedule -> {
                    assertThat(schedule.lastTriggeredAt()).isEqualTo(Instant.parse("2026-07-02T22:00:01Z"));
                    assertThat(schedule.nextTriggerAt()).isEqualTo(Instant.parse("2026-07-09T22:00:00Z"));
                    assertThat(schedule.enabled()).isTrue();
                });
    }

    @Test
    void oneTimeScheduleCanBeDisabledAfterTrigger() {
        WorkflowScheduleRepository repository = repository();
        long scheduleId = repository.createSchedule(new WorkflowScheduleRequest(
                "smartesb-code-reader",
                "full",
                null,
                null,
                null,
                Map.of("mode", "8583"),
                "once",
                null,
                null,
                LocalDateTime.of(2026, 6, 30, 6, 0),
                true
        ), Instant.parse("2026-06-29T22:00:00Z"));

        repository.markTriggered(scheduleId, Instant.parse("2026-06-29T22:00:00Z"), null, false);

        assertThat(repository.findSchedule(scheduleId)).get()
                .satisfies(schedule -> {
                    assertThat(schedule.frequency()).isEqualTo(ScheduleFrequency.ONCE);
                    assertThat(schedule.enabled()).isFalse();
                    assertThat(schedule.nextTriggerAt()).isNull();
                });
    }

    private WorkflowScheduleRepository repository() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("console.sqlite"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new WorkflowRunSchema(jdbcTemplate).initialize();
        return new WorkflowScheduleRepository(jdbcTemplate);
    }
}
