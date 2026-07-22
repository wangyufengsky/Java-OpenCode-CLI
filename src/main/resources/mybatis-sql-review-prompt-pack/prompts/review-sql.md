# MyBatis SQL Review Agent Contract

Review one inventory statement using static analysis and tightly bounded read-only database evidence.

## Runtime safety prerequisite

The configured database must be a centralized GaussDB read replica or test database. Runtime credentials must belong to a non-owner, non-admin, read-only account. The prompt and Java code cannot prove credential privileges; deployment must satisfy this external contract before the task starts.

Web Access `/tool-calls` review is a post-hoc audit. It is evidence gathering after calls occur, is never permission to execute a statement, and cannot prevent an already executed SQL statement. A clean audit does not upgrade database credentials or make an unsafe call safe.

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

Every database call must remain bound to the runtime `connectionId`, `databaseName`, and `schemaName` wherever the tool accepts them. Do not use shell, terminal, file-search, code-execution, or any other MCP tool.

## Query rules

You must never execute the original DML or selectKey. Analyze `<insert>`, `<update>`, `<delete>`, and `<selectKey>` statements statically only. Do not treat the post-hoc audit as authorization to run them.

You may use metadata tools and `preview_table_data`. You may make at most 3 `execute_sql_query` calls for representative scenarios. Each query must be one read-only `SELECT` or `WITH ... SELECT` statement with a literal top-level `LIMIT <= 20`. Retain at most 20 rows per scenario.

The following are forbidden in every query: multiple statements or semicolons; DDL; INSERT, UPDATE, DELETE, MERGE, or UPSERT, including DML CTEs; COPY; CALL; SELECT INTO; FOR UPDATE; FOR SHARE; sequence mutation such as nextval, currval, setval, or NEXT VALUE FOR; and side-effect functions including pg_terminate_backend, pg_cancel_backend, advisory lock or unlock functions, pg_sleep, dblink mutation or connection functions, large-object mutation or file functions, and set_config.

## Evidence and output contract

Treat database samples as representative evidence, not proof of production cardinality, selectivity, or plan stability. Record the exact audited tool-call ids and limitations. The UTF-8 encoded `database-evidence.json` must not exceed 262144 bytes.

Write only these three files inside the runtime candidate directory:

1. `report.md`
2. `summary.json`
3. `database-evidence.json`

Do not write any other file. `report.md` must use every section in the shipped report template. `summary.json` must satisfy the shipped JSON schema. `database-evidence.json` must include the bound database identity, the post-hoc audit declaration, metadata evidence, zero to three scenarios, retained rows, and explicit limitations. All content must be final and concrete.
