package com.sonnet.wyf.gitreport.console;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class WorkflowRunRepository {
    private final JdbcTemplate jdbcTemplate;

    public WorkflowRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long createRun(WorkflowRunSubmission submission, String configPath) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into workflow_runs
                    (chain_id, mode, rerun_type, rerun_id, run_date, state, phase, config_path, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, submission.chainId());
            statement.setString(2, submission.mode());
            statement.setString(3, submission.rerunType());
            statement.setString(4, submission.rerunId());
            statement.setString(5, submission.runDate() == null ? null : submission.runDate().toString());
            statement.setString(6, RunState.QUEUED.name());
            statement.setString(7, "queued");
            statement.setString(8, configPath);
            statement.setString(9, now.toString());
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void updateConfigPath(long id, String configPath) {
        jdbcTemplate.update("update workflow_runs set config_path=? where id=?", configPath, id);
    }

    public void markRunning(long id) {
        jdbcTemplate.update("update workflow_runs set state=?, phase=?, started_at=? where id=?",
                RunState.RUNNING.name(), "running", Instant.now().toString(), id);
    }

    public void markSucceeded(long id) {
        jdbcTemplate.update("update workflow_runs set state=?, phase=?, finished_at=? where id=?",
                RunState.SUCCEEDED.name(), "complete", Instant.now().toString(), id);
    }

    public void markFailed(long id, String message) {
        jdbcTemplate.update("update workflow_runs set state=?, phase=?, failure_message=?, finished_at=? where id=?",
                RunState.FAILED.name(), "failed", message, Instant.now().toString(), id);
    }

    public void appendEvent(long runId, String eventType, String message) {
        jdbcTemplate.update("""
                insert into workflow_run_events (run_id, event_type, message, created_at)
                values (?, ?, ?, ?)
                """, runId, eventType, message, Instant.now().toString());
    }

    public void upsertTaskStatus(WorkflowTaskStatus status) {
        jdbcTemplate.update("""
                insert into workflow_task_status
                (run_id, task_key, task_name, state, phase, status_path, error_message, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(run_id, task_key) do update set
                  task_name=excluded.task_name,
                  state=excluded.state,
                  phase=excluded.phase,
                  status_path=excluded.status_path,
                  error_message=excluded.error_message,
                  updated_at=excluded.updated_at
                """,
                status.runId(), status.taskKey(), status.taskName(), status.state(), status.phase(),
                status.statusPath(), status.errorMessage(), status.updatedAt().toString());
    }

    public List<WorkflowRunRecord> listRuns() {
        return listRuns(WorkflowRunFilter.empty());
    }

    public List<WorkflowRunRecord> listRuns(WorkflowRunFilter filter) {
        WorkflowRunFilter normalized = filter == null ? WorkflowRunFilter.empty() : filter;
        List<String> conditions = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();

        if (normalized.query() != null) {
            conditions.add("(cast(id as text) like ? or chain_id like ? or config_path like ?)");
            String queryPattern = "%" + normalized.query() + "%";
            arguments.add(queryPattern);
            arguments.add(queryPattern);
            arguments.add(queryPattern);
        }
        if (normalized.state() != null) {
            conditions.add("state = ?");
            arguments.add(normalized.state().name());
        }
        if (normalized.chainId() != null) {
            conditions.add("chain_id = ?");
            arguments.add(normalized.chainId());
        }
        if (normalized.createdFrom() != null) {
            conditions.add("created_at >= ?");
            arguments.add(normalized.createdFrom().atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        }
        if (normalized.createdUntil() != null) {
            conditions.add("created_at < ?");
            arguments.add(normalized.createdUntil().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        }

        StringBuilder sql = new StringBuilder("select * from workflow_runs");
        if (!conditions.isEmpty()) {
            sql.append(" where ").append(String.join(" and ", conditions));
        }
        sql.append(" order by id desc");
        return jdbcTemplate.query(sql.toString(), runMapper(), arguments.toArray());
    }

    public Optional<WorkflowRunRecord> findRun(long id) {
        List<WorkflowRunRecord> records = jdbcTemplate.query("select * from workflow_runs where id=?", runMapper(), id);
        return records.stream().findFirst();
    }

    public List<WorkflowRunEvent> listEvents(long runId) {
        return jdbcTemplate.query("select * from workflow_run_events where run_id=? order by id", eventMapper(), runId);
    }

    public List<WorkflowTaskStatus> listTaskStatuses(long runId) {
        return jdbcTemplate.query("select * from workflow_task_status where run_id=? order by task_key", taskMapper(), runId);
    }

    private static RowMapper<WorkflowRunRecord> runMapper() {
        return (rs, rowNum) -> new WorkflowRunRecord(
                rs.getLong("id"),
                rs.getString("chain_id"),
                rs.getString("mode"),
                rs.getString("rerun_type"),
                rs.getString("rerun_id"),
                parseDate(rs.getString("run_date")),
                RunState.valueOf(rs.getString("state")),
                rs.getString("phase"),
                rs.getString("config_path"),
                rs.getString("failure_message"),
                rs.getString("output_path"),
                Instant.parse(rs.getString("created_at")),
                parseInstant(rs.getString("started_at")),
                parseInstant(rs.getString("finished_at"))
        );
    }

    private static RowMapper<WorkflowRunEvent> eventMapper() {
        return (rs, rowNum) -> new WorkflowRunEvent(
                rs.getLong("id"),
                rs.getLong("run_id"),
                rs.getString("event_type"),
                rs.getString("message"),
                Instant.parse(rs.getString("created_at"))
        );
    }

    private static RowMapper<WorkflowTaskStatus> taskMapper() {
        return (rs, rowNum) -> new WorkflowTaskStatus(
                rs.getLong("run_id"),
                rs.getString("task_key"),
                rs.getString("task_name"),
                rs.getString("state"),
                rs.getString("phase"),
                rs.getString("status_path"),
                rs.getString("error_message"),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private static LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
