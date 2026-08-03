# MyBatis SQL Review Agent Contract

Review one inventory statement with static analysis and bounded Database MCP evidence.

The runtime context below is complete. Do not read skill files, source files, prior candidates, or any other
workspace file, and do not search the workspace or the internet. The only permitted tool calls are the four
Database MCP tools listed below and exactly three IDE `WriteFile` calls targeting the three candidate paths.
AgentBridge records the IDE `WriteFile` tool under the normalized history name `write_file`; the normalized
history name is not a separate tool you need to locate.
In particular, never call `read_file`, `search`, `run_command`, `run_in_terminal`, Git tools, or skill tools.

## Allowed Database MCP tools

Use only these four read-oriented tools:

- `cmcp_db_database_list_datasources`
- `cmcp_db_database_list_databases`
- `cmcp_db_database_list_table_schema`
- `cmcp_db_database_execute_sql_query`

```json
{"project":"absolute project path","scope":"ALL"}
{"dataSource":"configured data source","project":"absolute project path","scope":"ALL"}
{"dataSource":"configured data source","catalog":"configured catalog","schema":"configured schema","project":"absolute project path","scope":"ALL"}
{"dataSource":"configured data source","sql":"SELECT columns FROM safe_table LIMIT 1","maxRows":20,"project":"absolute project path","scope":"ALL"}
```

For metadata calls, use only the runtime `data_source`, `catalog`, `schema`, `project`, and `scope` values. The table-schema call may use the Database MCP tool's optional `includeColumns`, `includeIndexes`, and `maxTables` controls, but SQL review acceptance does not require them or constrain their values.

For every `cmcp_db_database_execute_sql_query` call, pass `dataSource`, `sql`, `maxRows`, `project`, and `scope`. `maxRows` is always 20. Each scenario SQL is a Java-validated read-only SELECT against a safe base table and includes an integer `LIMIT` from 1 through 20. Run at most three scenario queries.

Prefer zero Agent-session Database MCP calls. Java preflight has already verified the configured connection and
collected the bounded relation inventory. Do not call metadata tools merely to enrich the prose. Unless the runtime
task context supplies a concrete bounded scenario query, make no Database MCP calls and use empty
`audit.tool_call_ids`, `metadata`, and `scenarios` arrays. A zero-scenario SELECT review is valid.

Do not call DML, DDL, NoSQL, or unknown tools. `<insert>`, `<update>`, `<delete>`, and `<selectKey>` statements are static-review-only: do not call `cmcp_db_database_execute_sql_query` at all for them, including `SELECT 1` or any other connection probe. Do not execute the original mapped SQL.

## Required connection safety

Use the configured relational Database MCP data source. The database type is recorded but is not restricted to GaussDB. Runtime `safety_mode=strict` uses the engine-specific safety contract. Runtime `safety_mode=connectivity-only` is restricted to `environment=test`; Java preflight has already run the bounded `SELECT 1` connection probe before the Agent task boundary and exposes `database_safety=unverified`. Do not repeat that probe in the Agent session. For static-review-only statements, Database MCP metadata is optional and the query tool is forbidden. If a metadata call is unavailable or rejected, do not retry it and do not describe the rejected attempt as evidence; only successful calls present in the native tool-call history may appear in `audit.tool_call_ids` or `metadata`. It is valid for a static-review-only statement to use empty `audit.tool_call_ids`, `metadata`, and `scenarios`.

In strict mode, the connection uses a non-owner, non-admin, read-only account with no role inheritance, and non-GaussDB data sources rely on configured operator-confirmed safety assertions for equivalent row-security, function-execution, and timeout controls. Connectivity-only mode does not claim those properties were verified. Query only the bounded safe relation inventory returned by Database MCP.

Database credentials, permissions, and timeout enforcement are the hard safety boundary. The post-hoc audit cannot prevent or undo a database call.

## Evidence and output contract

Treat returned rows as representative evidence, not proof of production cardinality, selectivity, or plan stability. Record actual native tool names, normalized arguments, audited tool-call ids, results, and limitations. The UTF-8 encoded `database-evidence.json` must not exceed 262144 bytes.

Never invent tool calls, rejected-call evidence, tool-call ids, timestamps, durations, arguments, or results.
Every declared tool-call id must be copied from a successful native call made after this task's boundary. If no
Database MCP call succeeds, use the schema-valid empty arrays described above and state the limitation in prose.
When both `metadata` and `scenarios` are empty, every summary finding must also use an empty `evidence_ids` array.
Do not invent static evidence ids such as `static:*`; static support belongs in the finding description and report.

Use the IDE `WriteFile` tool only for the three exact candidate output paths.

`database-evidence.json` must use this exact top-level shape: `schema_version` set to
`mybatis-sql-review-database-evidence/v1`; the six exact statement binding fields; the five exact database binding
fields; `audit` containing `post_hoc: true`, `permission_to_execute_original_dml: false`, and `tool_call_ids`;
then `metadata`, `scenarios`, and non-empty `limitations` arrays. Do not use legacy shapes such as
`generated_at_utc`, nested `task`/`summary`, `tool_calls`, a top-level `tool_call_ids`, or schema version
`database-evidence/v1`.

Write only the three exact absolute output paths in the runtime task context:

1. `report.md`
2. `summary.json`
3. `database-evidence.json`

Do not write any other file. `report.md` uses the complete embedded template. Its Database Evidence section contains exactly `[database-evidence.json](database-evidence.json)`. Fill the seven labeled report lines with the exact runtime `data_source`, `catalog`, `schema`, `project`, `scope`, `safety_mode`, and `database_safety` values. `summary.json` satisfies the complete embedded schema and repeats the database binding values. All artifacts repeat the exact mapper and statement fields from the runtime context; do not add placeholder content.
`summary.json` is an instance of the embedded schema, not a copy of the schema itself. Never add schema-definition
keywords such as `$schema`, `$id`, `title`, `type`, `properties`, or `additionalProperties` to the summary instance.
Set `status` to `no-findings` only when `findings` is empty; otherwise use `reviewed`. Set `risk_level` to the highest
finding severity, mapping `info` to `low`; use `none` only when `findings` is empty. Java deterministically
normalizes these two derived fields before publication.
