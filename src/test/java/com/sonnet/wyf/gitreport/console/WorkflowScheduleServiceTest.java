package com.sonnet.wyf.gitreport.console;

import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
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
    void keepsTheOriginalFourArgumentConstructorPublicForExternalCallers() throws Exception {
        assertThat(WorkflowScheduleService.class.getConstructor(
                WorkflowScheduleRepository.class,
                WorkflowRunSubmitter.class,
                ChainCatalog.class,
                Clock.class
        )).isNotNull();
    }

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
    void ignoresBrowserUndefinedConfigValuesBeforePersistingAndTriggeringSchedules() {
        Fixture fixture = fixture(Instant.parse("2026-06-30T03:00:00Z"));

        fixture.service.create(new WorkflowScheduleRequest(
                "demo-chain",
                "full",
                null,
                null,
                null,
                Map.of("value", "daily", "paths.repo", "undefined", "paths.out", "null"),
                "daily",
                null,
                LocalTime.of(6, 0),
                null,
                true
        ));

        fixture.service.triggerDueSchedules(Instant.parse("2026-06-30T22:00:00Z"));

        assertThat(fixture.submitter.submissions()).hasSize(1);
        assertThat(fixture.submitter.submissions().get(0).config())
                .containsEntry("value", "daily")
                .doesNotContainKeys("paths.repo", "paths.out");
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
    void refusesToReenableAnAlreadyTriggeredOneTimeSchedule() {
        Fixture fixture = fixture(Instant.parse("2026-06-30T03:00:00Z"));
        long scheduleId = fixture.service.create(new WorkflowScheduleRequest(
                "demo-chain", "full", null, null, null, Map.of("value", "once"),
                "once", null, null, LocalDateTime.of(2026, 6, 30, 6, 0), true
        ));
        fixture.service.triggerDueSchedules(Instant.parse("2026-06-30T03:01:00Z"));

        assertThatThrownBy(() -> fixture.service.setEnabled(scheduleId, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("已执行的一次性计划不能重新启用，请复制后创建新计划");

        assertThat(fixture.repository.findSchedule(scheduleId)).get()
                .satisfies(schedule -> {
                    assertThat(schedule.enabled()).isFalse();
                    assertThat(schedule.nextTriggerAt()).isNull();
                });
    }

    @Test
    void refusesToReenableAnAlreadyTriggeredOneTimeScheduleThroughEdit() {
        Fixture fixture = fixture(Instant.parse("2026-06-30T03:00:00Z"));
        long scheduleId = fixture.service.create(new WorkflowScheduleRequest(
                "demo-chain", "full", null, null, null, Map.of("value", "once"),
                "once", null, null, LocalDateTime.of(2026, 6, 30, 6, 0), true
        ));
        fixture.service.triggerDueSchedules(Instant.parse("2026-06-30T03:01:00Z"));

        assertThatThrownBy(() -> fixture.service.update(scheduleId, new WorkflowScheduleRequest(
                "demo-chain", "full", null, null, null, Map.of("value", "rescheduled"),
                "once", null, null, LocalDateTime.of(2026, 7, 1, 6, 0), true
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("已执行的一次性计划不能重新启用，请复制后创建新计划");
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

    @Test
    void updatesScheduleWithCreateValidationAndRecalculatedNextTrigger() {
        Fixture fixture = fixture(Instant.parse("2026-06-30T03:00:00Z"));
        long scheduleId = fixture.service.create(new WorkflowScheduleRequest(
                "demo-chain", "full", null, null, null, Map.of("value", "before"),
                "daily", null, LocalTime.of(6, 0), null, true
        ));

        WorkflowScheduleRecord updated = fixture.service.update(scheduleId, new WorkflowScheduleRequest(
                "demo-chain", "full", null, null, LocalDate.of(2026, 7, 1),
                Map.of("value", "after", "paths.repo", "undefined"),
                "weekly", DayOfWeek.WEDNESDAY.getValue(), LocalTime.of(7, 30), null, true
        ));

        assertThat(updated.runTime()).isEqualTo(LocalTime.of(7, 30));
        assertThat(updated.frequency()).isEqualTo(ScheduleFrequency.WEEKLY);
        assertThat(updated.config()).containsEntry("value", "after").doesNotContainKey("paths.repo");
        assertThat(updated.nextTriggerAt()).isEqualTo(Instant.parse("2026-06-30T23:30:00Z"));
    }

    @Test
    void updateRejectsInvalidRequestsAndMissingSchedules() {
        Fixture fixture = fixture(Instant.parse("2026-06-30T03:00:00Z"));
        WorkflowScheduleRequest invalid = new WorkflowScheduleRequest(
                "demo-chain", "full", null, null, null, Map.of(),
                "weekly", null, LocalTime.of(6, 0), null, true
        );

        assertThatThrownBy(() -> fixture.service.update(99, invalid))
                .isInstanceOf(java.util.NoSuchElementException.class);

        long scheduleId = fixture.service.create(new WorkflowScheduleRequest(
                "demo-chain", "full", null, null, null, Map.of(),
                "daily", null, LocalTime.of(6, 0), null, true
        ));
        assertThatThrownBy(() -> fixture.service.update(scheduleId, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("星期");
    }

    private Fixture fixture(Instant now) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("console.sqlite"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new WorkflowRunSchema(jdbcTemplate).initialize();
        WorkflowScheduleRepository repository = new WorkflowScheduleRepository(jdbcTemplate);
        CapturingSubmitter submitter = new CapturingSubmitter();
        AgentBridgeRunnerProperties properties = new AgentBridgeRunnerProperties();
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
