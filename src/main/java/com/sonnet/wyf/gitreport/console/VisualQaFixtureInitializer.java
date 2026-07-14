package com.sonnet.wyf.gitreport.console;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

/** Seeds only the disposable visual-QA database with stable data for local screenshots. */
@Component
@Profile("visual-qa")
@Order(0)
public class VisualQaFixtureInitializer implements ApplicationRunner {
    private static final Instant FIXTURE_INSTANT = Instant.parse("2026-07-13T00:00:00Z");

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowRunRepository runRepository;
    private final WorkflowScheduleRepository scheduleRepository;

    public VisualQaFixtureInitializer(
            JdbcTemplate jdbcTemplate,
            WorkflowRunRepository runRepository,
            WorkflowScheduleRepository scheduleRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.runRepository = runRepository;
        this.scheduleRepository = scheduleRepository;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        jdbcTemplate.update("delete from workflow_run_events");
        jdbcTemplate.update("delete from workflow_task_status");
        jdbcTemplate.update("delete from workflow_schedules");
        jdbcTemplate.update("delete from workflow_runs");
        resetAutoincrementCounters();

        long succeeded = createRun("visual-succeeded.yml");
        runRepository.markRunning(succeeded);
        runRepository.markSucceeded(succeeded);
        runRepository.appendEvent(succeeded, "RUN_SUCCEEDED", "视觉基线：运行完成");

        long running = createRun("visual-running.yml");
        runRepository.markRunning(running);
        runRepository.upsertTaskStatus(new WorkflowTaskStatus(
                running, "generate", "生成报告", "RUNNING", "execution", null, null, FIXTURE_INSTANT
        ));
        runRepository.appendEvent(running, "TASK_RUNNING", "视觉基线：生成报告");

        long failed = createRun("visual-failed.yml");
        runRepository.markRunning(failed);
        runRepository.markFailed(failed, "视觉基线：路径预检失败");
        runRepository.upsertTaskStatus(new WorkflowTaskStatus(
                failed, "preflight", "路径预检", "FAILED", "preflight", null, "视觉基线：路径预检失败", FIXTURE_INSTANT
        ));
        runRepository.appendEvent(failed, "TASK_FAILED", "视觉基线：路径预检失败");

        scheduleRepository.createSchedule(new WorkflowScheduleRequest(
                "git-code-contribution-report", "full", null, null, LocalDate.of(2026, 7, 13),
                Map.of("project.id", "visual-demo"), "daily", null, LocalTime.of(9, 0), null, true
        ), Instant.parse("2026-07-14T01:00:00Z"));
        scheduleRepository.createSchedule(new WorkflowScheduleRequest(
                "git-code-contribution-report", "full", null, null, LocalDate.of(2026, 7, 13),
                Map.of("project.id", "visual-demo"), "once", null, null,
                LocalDateTime.of(2026, 7, 14, 9, 0), false
        ), null);

        jdbcTemplate.update("update workflow_runs set created_at=?, started_at=?, finished_at=?",
                FIXTURE_INSTANT.toString(), FIXTURE_INSTANT.toString(), FIXTURE_INSTANT.toString());
        jdbcTemplate.update("update workflow_run_events set created_at=?", FIXTURE_INSTANT.toString());
        jdbcTemplate.update("update workflow_task_status set updated_at=?", FIXTURE_INSTANT.toString());
        jdbcTemplate.update("update workflow_schedules set created_at=?, updated_at=?", FIXTURE_INSTANT.toString(), FIXTURE_INSTANT.toString());
    }

    private long createRun(String configPath) {
        return runRepository.createRun(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, LocalDate.of(2026, 7, 13),
                Map.of("project.id", "visual-demo"), null
        ), configPath);
    }

    private void resetAutoincrementCounters() {
        Integer sequenceTableCount = jdbcTemplate.queryForObject(
                "select count(*) from sqlite_master where type='table' and name='sqlite_sequence'",
                Integer.class
        );
        if (sequenceTableCount != null && sequenceTableCount > 0) {
            jdbcTemplate.update(
                    "delete from sqlite_sequence where name in ('workflow_runs', 'workflow_schedules', 'workflow_run_events')"
            );
        }
    }
}
