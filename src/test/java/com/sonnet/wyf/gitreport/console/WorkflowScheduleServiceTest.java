package com.sonnet.wyf.gitreport.console;

import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowScheduleServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path tempDir;

    @Test
    void createsWeeklyScheduleWithNextTriggerInLocalTime() {
        Fixture fixture = fixture(Instant.parse("2026-06-30T03:00:00Z"));

        long scheduleId = fixture.service.create(new WorkflowScheduleRequest(
                "demo-chain",
                "full",
                null,
                null,
                LocalDate.of(2026, 6, 30),
                Map.of("value", "weekly"),
                "weekly",
                DayOfWeek.FRIDAY.getValue(),
                LocalTime.of(6, 0),
                null,
                true
        ));

        assertThat(fixture.repository.findSchedule(scheduleId)).get()
                .extracting(WorkflowScheduleRecord::nextTriggerAt)
                .isEqualTo(Instant.parse("2026-07-02T22:00:00Z"));
    }

    @Test
    void triggersDueEnabledSchedulesThroughWorkflowSubmitter() {
        Fixture fixture = fixture(Instant.parse("2026-06-30T03:00:00Z"));
        long scheduleId = fixture.service.create(new WorkflowScheduleRequest(
                "demo-chain",
                "full",
                null,
                null,
                null,
                Map.of("value", "daily"),
                "daily",
                null,
                LocalTime.of(6, 0),
                null,
                true
        ));

        fixture.service.triggerDueSchedules(Instant.parse("2026-06-30T22:00:00Z"));

        assertThat(fixture.submitter.submissions()).hasSize(1);
        assertThat(fixture.submitter.submissions().get(0).chainId()).isEqualTo("demo-chain");
        assertThat(fixture.submitter.submissions().get(0).config()).containsEntry("value", "daily");
        assertThat(fixture.repository.findSchedule(scheduleId)).get()
                .satisfies(schedule -> {
                    assertThat(schedule.lastTriggeredAt()).isEqualTo(Instant.parse("2026-06-30T22:00:00Z"));
                    assertThat(schedule.nextTriggerAt()).isEqualTo(Instant.parse("2026-07-01T22:00:00Z"));
                    assertThat(schedule.enabled()).isTrue();
                });
    }

    @Test
    void ignoresDisabledDueSchedules() {
        Fixture fixture = fixture(Instant.parse("2026-06-30T03:00:00Z"));
        long scheduleId = fixture.service.create(new WorkflowScheduleRequest(
                "demo-chain",
                "full",
                null,
                null,
                null,
                Map.of("value", "daily"),
                "daily",
                null,
                LocalTime.of(6, 0),
                null,
                false
        ));
        fixture.repository.updateEnabled(scheduleId, false, null);

        fixture.service.triggerDueSchedules(Instant.parse("2026-06-30T22:00:00Z"));

        assertThat(fixture.submitter.submissions()).isEmpty();
    }

    @Test
    void disablesOneTimeScheduleAfterTrigger() {
        Fixture fixture = fixture(Instant.parse("2026-06-30T03:00:00Z"));
        long scheduleId = fixture.service.create(new WorkflowScheduleRequest(
                "demo-chain",
                "full",
                null,
                null,
                null,
                Map.of("value", "once"),
                "once",
                null,
                null,
                LocalDateTime.of(2026, 6, 30, 6, 0),
                true
        ));

        fixture.service.triggerDueSchedules(Instant.parse("2026-06-30T03:01:00Z"));

        assertThat(fixture.submitter.submissions()).hasSize(1);
        assertThat(fixture.repository.findSchedule(scheduleId)).get()
                .satisfies(schedule -> {
                    assertThat(schedule.enabled()).isFalse();
                    assertThat(schedule.nextTriggerAt()).isNull();
                });
    }

    @Test
    void rejectsUnknownChainAndInvalidFrequency() {
        Fixture fixture = fixture(Instant.parse("2026-06-30T03:00:00Z"));

        assertThatThrownBy(() -> fixture.service.create(new WorkflowScheduleRequest(
                "missing",
                "full",
                null,
                null,
                null,
                Map.of(),
                "daily",
                null,
                LocalTime.of(6, 0),
                null,
                true
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("未知链路");

        assertThatThrownBy(() -> fixture.service.create(new WorkflowScheduleRequest(
                "demo-chain",
                "full",
                null,
                null,
                null,
                Map.of(),
                "hourly",
                null,
                LocalTime.of(6, 0),
                null,
                true
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("频率");
    }

    private Fixture fixture(Instant now) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("console.sqlite"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new WorkflowRunSchema(jdbcTemplate).initialize();
        WorkflowScheduleRepository repository = new WorkflowScheduleRepository(jdbcTemplate);
        CapturingSubmitter submitter = new CapturingSubmitter();
        OpenCodeRunnerProperties properties = new OpenCodeRunnerProperties();
        properties.setConfigDir("classpath:chains");
        ChainCatalog chainCatalog = new ChainCatalog(new DefaultResourceLoader(), properties, List.of(new DemoChain()));
        WorkflowScheduleService service = new WorkflowScheduleService(
                repository,
                submitter,
                chainCatalog,
                Clock.fixed(now, ZONE),
                false
        );
        return new Fixture(repository, submitter, service);
    }

    private record Fixture(
            WorkflowScheduleRepository repository,
            CapturingSubmitter submitter,
            WorkflowScheduleService service
    ) {
    }

    private static class CapturingSubmitter implements WorkflowRunSubmitter {
        private final List<WorkflowRunSubmission> submissions = new ArrayList<>();

        @Override
        public long submit(WorkflowRunSubmission submission) {
            submissions.add(submission);
            return submissions.size();
        }

        List<WorkflowRunSubmission> submissions() {
            return submissions;
        }
    }

    private static class DemoChain implements WorkflowChain {
        @Override
        public String id() {
            return "demo-chain";
        }

        @Override
        public void run(WorkflowRunRequest request) {
        }
    }
}
