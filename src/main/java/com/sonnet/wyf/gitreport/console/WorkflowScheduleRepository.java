package com.sonnet.wyf.gitreport.console;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WorkflowScheduleRepository {
    private static final TypeReference<Map<String, Object>> CONFIG_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WorkflowScheduleRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ObjectMapper());
    }

    WorkflowScheduleRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public long createSchedule(WorkflowScheduleRequest request, Instant nextTriggerAt) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into workflow_schedules
                    (chain_id, mode, rerun_type, rerun_id, run_date, config_json, frequency, day_of_week,
                     run_time, run_at, enabled, next_trigger_at, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, request.chainId());
            statement.setString(2, request.mode());
            statement.setString(3, request.rerunType());
            statement.setString(4, request.rerunId());
            statement.setString(5, request.runDate() == null ? null : request.runDate().toString());
            statement.setString(6, writeConfig(request.config()));
            statement.setString(7, ScheduleFrequency.parse(request.frequency()).name());
            if (request.dayOfWeek() == null) {
                statement.setObject(8, null);
            } else {
                statement.setInt(8, request.dayOfWeek());
            }
            statement.setString(9, request.runTime() == null ? null : request.runTime().toString());
            statement.setString(10, request.runAt() == null ? null : request.runAt().toString());
            statement.setInt(11, request.enabled() ? 1 : 0);
            statement.setString(12, nextTriggerAt == null ? null : nextTriggerAt.toString());
            statement.setString(13, now.toString());
            statement.setString(14, now.toString());
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<WorkflowScheduleRecord> findSchedule(long id) {
        List<WorkflowScheduleRecord> records = jdbcTemplate.query("select * from workflow_schedules where id=?", scheduleMapper(), id);
        return records.stream().findFirst();
    }

    public List<WorkflowScheduleRecord> listSchedules() {
        return jdbcTemplate.query("select * from workflow_schedules order by id desc", scheduleMapper());
    }

    public List<WorkflowScheduleRecord> listDueSchedules(Instant now) {
        return jdbcTemplate.query("""
                select * from workflow_schedules
                where enabled=1 and next_trigger_at is not null and next_trigger_at <= ?
                order by next_trigger_at, id
                """, scheduleMapper(), now.toString());
    }

    public void markTriggered(long id, Instant triggeredAt, Instant nextTriggerAt, boolean enabled) {
        jdbcTemplate.update("""
                update workflow_schedules
                set last_triggered_at=?, next_trigger_at=?, enabled=?, updated_at=?
                where id=?
                """, triggeredAt.toString(), nextTriggerAt == null ? null : nextTriggerAt.toString(),
                enabled ? 1 : 0, Instant.now().toString(), id);
    }

    public void updateEnabled(long id, boolean enabled, Instant nextTriggerAt) {
        jdbcTemplate.update("""
                update workflow_schedules
                set enabled=?, next_trigger_at=?, updated_at=?
                where id=?
                """, enabled ? 1 : 0, nextTriggerAt == null ? null : nextTriggerAt.toString(),
                Instant.now().toString(), id);
    }

    private RowMapper<WorkflowScheduleRecord> scheduleMapper() {
        return (rs, rowNum) -> new WorkflowScheduleRecord(
                rs.getLong("id"),
                rs.getString("chain_id"),
                rs.getString("mode"),
                rs.getString("rerun_type"),
                rs.getString("rerun_id"),
                parseDate(rs.getString("run_date")),
                readConfig(rs.getString("config_json")),
                ScheduleFrequency.valueOf(rs.getString("frequency")),
                (Integer) rs.getObject("day_of_week"),
                parseTime(rs.getString("run_time")),
                parseDateTime(rs.getString("run_at")),
                rs.getInt("enabled") == 1,
                parseInstant(rs.getString("last_triggered_at")),
                parseInstant(rs.getString("next_trigger_at")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private String writeConfig(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config == null ? Map.of() : config);
        } catch (Exception exception) {
            throw new IllegalArgumentException("定时任务配置无法序列化", exception);
        }
    }

    private Map<String, Object> readConfig(String value) {
        try {
            return value == null || value.isBlank() ? Map.of() : objectMapper.readValue(value, CONFIG_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("定时任务配置无法读取", exception);
        }
    }

    private static LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private static LocalTime parseTime(String value) {
        return value == null || value.isBlank() ? null : LocalTime.parse(value);
    }

    private static LocalDateTime parseDateTime(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value);
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
