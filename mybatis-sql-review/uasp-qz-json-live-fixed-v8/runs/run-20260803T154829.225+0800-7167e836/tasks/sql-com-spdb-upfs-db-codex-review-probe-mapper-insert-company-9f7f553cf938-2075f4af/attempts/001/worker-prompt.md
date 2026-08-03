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


## Complete report template

```markdown
# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database MCP evidence. State its exact statement key, mapper-relative path, namespace, statement id, command type, selectKey flag, source line range, and runtime `data_source`, `catalog`, `schema`, `project`, and `scope` binding.

## Statement

Identify the mapper, statement key, command type, selectKey flag, source line range, dynamic SQL branches, parameters, raw mapper XML, and normalized SQL being reviewed.

- Data source: `exact runtime data_source value`
- Catalog: `exact runtime catalog value`
- Schema: `exact runtime schema value`
- Project: `exact runtime project value`
- Scope: `exact runtime scope value`
- Safety mode: `exact runtime safety_mode value`
- Database safety: `exact runtime database_safety value`

## Static Analysis

Describe correctness, maintainability, performance, concurrency, and data-volume risks visible from the mapper source. Mark claims that require runtime confirmation.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

List each concrete finding with severity, category, supporting evidence ids, and a clear distinction between confirmed and unverified claims. State explicitly when no finding remains.

## Recommendations

Give actionable, scoped recommendations that do not require executing the original DML or selectKey statement.

## Limitations

State the limits of representative rows and every unresolved uncertainty.
```

## Complete summary JSON schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://local.invalid/mybatis-sql-review/sql-summary.schema.json",
  "title": "MyBatis SQL review summary",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "schema_version",
    "statement_key",
    "mapper_relative_path",
    "namespace",
    "statement_id",
    "status",
    "command_type",
    "select_key",
    "risk_level",
    "scenario_count",
    "data_source",
    "catalog",
    "schema",
    "project",
    "scope",
    "evidence_file",
    "report_file",
    "findings"
  ],
  "properties": {
    "schema_version": {
      "type": "string",
      "enum": ["mybatis-sql-review-summary/v1"]
    },
    "statement_key": {
      "type": "string",
      "minLength": 1
    },
    "mapper_relative_path": {
      "type": "string",
      "minLength": 1
    },
    "namespace": {
      "type": "string",
      "minLength": 1
    },
    "statement_id": {
      "type": "string",
      "minLength": 1
    },
    "status": {
      "type": "string",
      "enum": ["reviewed", "no-findings"]
    },
    "command_type": {
      "type": "string",
      "enum": ["select", "insert", "update", "delete", "selectKey"]
    },
    "select_key": {
      "type": "boolean"
    },
    "risk_level": {
      "type": "string",
      "enum": ["none", "low", "medium", "high", "critical"]
    },
    "scenario_count": {
      "type": "integer",
      "minimum": 0,
      "maximum": 3
    },
    "data_source": {"type": "string", "minLength": 1},
    "catalog": {"type": "string", "minLength": 1},
    "schema": {"type": "string", "minLength": 1},
    "project": {"type": "string", "minLength": 1},
    "scope": {"type": "string", "enum": ["GLOBAL", "PROJECT", "ALL"]},
    "evidence_file": {
      "type": "string",
      "enum": ["database-evidence.json"]
    },
    "report_file": {
      "type": "string",
      "enum": ["report.md"]
    },
    "findings": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": [
          "id",
          "severity",
          "category",
          "title",
          "description",
          "evidence_ids",
          "recommendation"
        ],
        "properties": {
          "id": {"type": "string", "minLength": 1},
          "severity": {
            "type": "string",
            "enum": ["info", "low", "medium", "high", "critical"]
          },
          "category": {"type": "string", "minLength": 1},
          "title": {"type": "string", "minLength": 1},
          "description": {"type": "string", "minLength": 1},
          "evidence_ids": {
            "type": "array",
            "uniqueItems": true,
            "items": {"type": "string", "minLength": 1}
          },
          "recommendation": {"type": "string", "minLength": 1}
        }
      }
    }
  }
}
```

## Runtime task context

```json
{
  "statement_key" : "com-spdb-upfs-db-codex-review-probe-mapper-insert-company-9f7f553cf938",
  "mapper_relative_path" : "src/test/resources/mapper/CodexReviewProbeMapper.xml",
  "namespace" : "com.spdb.upfs.db.CodexReviewProbeMapper",
  "statement_id" : "insertCompany",
  "command_type" : "insert",
  "select_key" : false,
  "source_start_line" : 42,
  "source_end_line" : 57,
  "raw_mapper_xml" : "<insert id=\"insertCompany\" parameterType=\"java.util.Map\"\n            useGeneratedKeys=\"true\" keyProperty=\"id\" keyColumn=\"ID\">\n        INSERT INTO COMPANY (\n            COMPANY_NAME,\n            COMPANY_STATE,\n            COMPANY_CITY,\n            EMPLOYEE,\n            COMPANY_CREATE_YEAR\n        ) VALUES (\n            #{companyName,jdbcType=VARCHAR},\n            #{companyState,jdbcType=VARCHAR},\n            #{companyCity,jdbcType=VARCHAR},\n            #{employee,jdbcType=INTEGER},\n            #{companyCreateYear,jdbcType=INTEGER}\n        )\n    </insert>",
  "normalized_sql" : "INSERT INTO COMPANY (\n            COMPANY_NAME,\n            COMPANY_STATE,\n            COMPANY_CITY,\n            EMPLOYEE,\n            COMPANY_CREATE_YEAR\n        ) VALUES (\n            #{companyName,jdbcType=VARCHAR},\n            #{companyState,jdbcType=VARCHAR},\n            #{companyCity,jdbcType=VARCHAR},\n            #{employee,jdbcType=INTEGER},\n            #{companyCreateYear,jdbcType=INTEGER}\n        )",
  "dynamic_nodes" : [ ],
  "parameter_placeholders" : [ "#{companyName,jdbcType=VARCHAR}", "#{companyState,jdbcType=VARCHAR}", "#{companyCity,jdbcType=VARCHAR}", "#{employee,jdbcType=INTEGER}", "#{companyCreateYear,jdbcType=INTEGER}" ],
  "data_source" : "deepseek@localhost",
  "catalog" : "deepseek",
  "schema" : "deepseek",
  "project" : "/Users/wangyufeng/IdeaProjects/uasp-qz-json",
  "scope" : "ALL",
  "safety_mode" : "connectivity-only",
  "database_safety" : "unverified",
  "candidate_directory" : "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/mybatis-sql-review/uasp-qz-json-live-fixed-v8/runs/run-20260803T154829.225+0800-7167e836/tasks/sql-com-spdb-upfs-db-codex-review-probe-mapper-insert-company-9f7f553cf938-2075f4af/attempts/001/candidate",
  "report_path" : "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/mybatis-sql-review/uasp-qz-json-live-fixed-v8/runs/run-20260803T154829.225+0800-7167e836/tasks/sql-com-spdb-upfs-db-codex-review-probe-mapper-insert-company-9f7f553cf938-2075f4af/attempts/001/candidate/report.md",
  "summary_path" : "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/mybatis-sql-review/uasp-qz-json-live-fixed-v8/runs/run-20260803T154829.225+0800-7167e836/tasks/sql-com-spdb-upfs-db-codex-review-probe-mapper-insert-company-9f7f553cf938-2075f4af/attempts/001/candidate/summary.json",
  "database_evidence_path" : "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/mybatis-sql-review/uasp-qz-json-live-fixed-v8/runs/run-20260803T154829.225+0800-7167e836/tasks/sql-com-spdb-upfs-db-codex-review-probe-mapper-insert-company-9f7f553cf938-2075f4af/attempts/001/candidate/database-evidence.json"
}
```
