package com.sonnet.wyf.gitreport.console;

import org.springframework.jdbc.core.JdbcTemplate;

public class WorkflowRunSchema {
    private final JdbcTemplate jdbcTemplate;

    public WorkflowRunSchema(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initialize() {
        jdbcTemplate.execute("""
                create table if not exists workflow_runs (
                  id integer primary key autoincrement,
                  chain_id text not null,
                  mode text not null,
                  rerun_type text,
                  rerun_id text,
                  run_date text,
                  state text not null,
                  phase text,
                  config_path text,
                  failure_message text,
                  output_path text,
                  created_at text not null,
                  started_at text,
                  finished_at text
                )
                """);
        ensureColumn("workflow_runs", "config_path", "text");
        jdbcTemplate.execute("""
                create table if not exists workflow_run_events (
                  id integer primary key autoincrement,
                  run_id integer not null,
                  event_type text not null,
                  message text not null,
                  created_at text not null
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists workflow_task_status (
                  run_id integer not null,
                  task_key text not null,
                  task_name text not null,
                  state text not null,
                  phase text,
                  status_path text,
                  error_message text,
                  updated_at text not null,
                  primary key (run_id, task_key)
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists workflow_schedules (
                  id integer primary key autoincrement,
                  chain_id text not null,
                  mode text not null,
                  rerun_type text,
                  rerun_id text,
                  run_date text,
                  config_json text not null,
                  frequency text not null,
                  day_of_week integer,
                  run_time text,
                  run_at text,
                  enabled integer not null,
                  last_triggered_at text,
                  next_trigger_at text,
                  created_at text not null,
                  updated_at text not null
                )
                """);
    }

    private void ensureColumn(String tableName, String columnName, String type) {
        boolean exists = jdbcTemplate.queryForList("pragma table_info(" + tableName + ")")
                .stream()
                .anyMatch(row -> columnName.equals(row.get("name")));
        if (!exists) {
            jdbcTemplate.execute("alter table " + tableName + " add column " + columnName + " " + type);
        }
    }
}
