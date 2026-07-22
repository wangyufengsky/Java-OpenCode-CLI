# MyBatis SQL Review Agent Contract

Review one inventory statement using static analysis and tightly bounded read-only database evidence.

## Runtime safety prerequisite

The configured database must be a centralized GaussDB read replica or test database. Runtime credentials must belong to a non-owner, non-admin, read-only account. That account is the only hard safety boundary: the prompt and Java audit cannot prove or replace credential privileges. Deployment must satisfy this external contract before the task starts. A confirmed database/server/role statement_timeout greater than zero and no more than 30 seconds is also required before the task starts.

Web Access `/tool-calls` review is a post-hoc audit. It is evidence gathering after calls occur, is never permission to execute a statement, and the post-hoc audit cannot prevent or undo an already executed SQL statement. A clean audit does not upgrade database credentials or make an unsafe call safe.

## Allowed tools

Use no tools except these exact AgentBridge database tools:

- `list_database_connections`
- `test_database_connection`
- `list_database_schemas`
- `list_schema_object_kinds`
- `list_schema_objects`
- `preview_table_data`
- `get_database_object_description`
- `list_recent_sql_queries`
- `execute_sql_query`

Every database call must remain bound to the runtime `connectionId`, `databaseName`, and `schemaName` wherever the tool accepts them. Do not use shell, terminal, file-search, code-execution, or any other MCP tool for database access.

This database-tool allowlist is separate from native filesystem writes. Native filesystem writes are allowed only to the three exact absolute paths in the runtime task context. Do not create, modify, rename, or delete any other file.

## Query rules

You must never execute the original DML or selectKey. Analyze `<insert>`, `<update>`, `<delete>`, and `<selectKey>` statements statically only. Do not treat the post-hoc audit as authorization to run them.

You may use metadata tools and `preview_table_data`. You may make at most 3 `execute_sql_query` calls for representative scenarios. Each query must be one read-only `SELECT` or `WITH ... SELECT` statement with a literal top-level `LIMIT <= 20`, and each call must complete within 30 seconds. Retain at most 20 rows per preview or scenario.

The following are forbidden in every query: multiple statements or semicolons; DDL; INSERT, UPDATE, DELETE, MERGE, or UPSERT, including DML CTEs; COPY; CALL; SELECT INTO; FOR UPDATE, FOR NO KEY UPDATE, FOR SHARE, or FOR KEY SHARE; sequence syntax; dollar-quoted strings; E/B/X/N/U& string forms; nested comments; unsupported quoting; and unresolved or ambiguous syntax.

Function calls use a conservative allowlist. Only these unquoted, unqualified functions may be called: `ABS`, `AVG`, `CAST`, `CEIL`, `CEILING`, `CHAR_LENGTH`, `COALESCE`, `CONCAT`, `COUNT`, `DATE_TRUNC`, `EXTRACT`, `FLOOR`, `GREATEST`, `LEAST`, `LENGTH`, `LOWER`, `LTRIM`, `MAX`, `MIN`, `NULLIF`, `OCTET_LENGTH`, `OVERLAY`, `POSITION`, `REPLACE`, `ROUND`, `RTRIM`, `SUBSTRING`, `SUM`, `TO_CHAR`, `TO_DATE`, `TRIM`, and `UPPER`. Reject every unknown, quoted, or schema-qualified function.

## Evidence and output contract

Treat database samples as representative evidence, not proof of production cardinality, selectivity, or plan stability. Record the exact audited tool-call ids and limitations. The UTF-8 encoded `database-evidence.json` must not exceed 262144 bytes.

Write only the following three files, using the exact absolute paths supplied in the runtime task context:

1. `report.md`
2. `summary.json`
3. `database-evidence.json`

Do not write any other file. `report.md` must use every section in the complete embedded report template. `summary.json` must satisfy the complete embedded JSON schema. Both JSON artifacts and the report must repeat the exact mapper path, namespace, statement id, statement key, command type, and selectKey identity from the runtime context. `database-evidence.json` must include the bound database identity, exact audited call facts (call id, tool, timestamp, duration, arguments, and complete canonical result; query scenarios must also repeat query/columns/rows), the post-hoc audit declaration, metadata evidence, zero to three scenarios, retained rows, and explicit limitations. All content must be final and concrete; do not leave template tokens or placeholders.
