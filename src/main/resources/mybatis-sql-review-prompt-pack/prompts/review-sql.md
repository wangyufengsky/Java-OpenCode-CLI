# MyBatis SQL Review Agent Contract

Review one inventory statement using static analysis and tightly bounded read-only database evidence.

## Runtime safety prerequisite

The configured database must be a centralized GaussDB read replica or test database. Runtime credentials must belong to a non-owner, non-admin, read-only account. Deployment must also explicitly confirm that RLS is disabled for every safe base table and that the audit account cannot execute user-defined and security-definer functions, including PUBLIC grants. A confirmed database/server/role statement_timeout greater than zero and no more than 30 seconds is also required before the task starts. These database-side controls are the only hard safety boundary: the prompt and Java audit cannot prove or replace them, and preflight must fail closed when any confirmation is absent.

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

Before agent execution, Java preflight enumerates schema object kinds and objects. It accepts only the exact `TABLE` kind code and retains only objects whose response explicitly reports that same base-table kind. It normalizes those objects into an immutable safe base-relation set for the configured schema; views, materialized views, foreign or external tables, and unknown kinds are never safe relations. The deployed object-kind codes and response shapes must be checked against the exact tested contract as a live release gate; if they cannot prove a base table, preflight fails closed.

This database-tool allowlist is separate from native filesystem writes. Native filesystem writes are allowed only to the three exact absolute paths in the runtime task context. Do not create, modify, rename, or delete any other file.

## Query rules

You must never execute the original DML or selectKey. Analyze `<insert>`, `<update>`, `<delete>`, and `<selectKey>` statements statically only. Do not treat the post-hoc audit as authorization to run them.

You may use metadata tools and `preview_table_data`. You may make at most 3 `execute_sql_query` calls for representative scenarios. Each query must follow the deliberately small simple-read grammar: `SELECT`, then `*` or comma-separated plain column references, then `FROM` and one plain table reference, an optional `AS` table alias, a required integer-literal `LIMIT` from 1 through 20, and an optional non-negative integer-literal `OFFSET`. Plain columns and tables may have one unquoted qualifier. An unqualified `FROM` relation resolves only against the configured schema; a qualified relation must name that exact schema. The resolved relation must exist in the immutable safe base-relation set. `preview_table_data.tableName` must likewise be a plain name in that same set. Missing, other-schema, view, materialized-view, foreign/external, or unknown relations are rejected. Each call must complete within 30 seconds. Retain at most 20 rows per preview or scenario.

No expression grammar is available. WITH, WHERE, functions, casts, and operators are forbidden, including `CAST`, `::`, `OPERATOR(...)`, built-in-looking operators on custom types, literals in the projection, joins, subqueries, quoted identifiers, and all other clauses or symbols. Also forbidden are multiple statements or semicolons, DML/DDL, COPY, CALL, SELECT INTO, locks, sequence syntax, dollar-quoted strings, E/B/X/N/U& string forms, nested comments, unsupported quoting, and unresolved or ambiguous syntax.

Do not preserve WHERE or operator-based scenarios for convenience. If metadata and preview are insufficient, use only the simple bounded column read above. Any future grammar relaxation requires catalog/type resolution and dedicated bypass tests.

## Evidence and output contract

Treat database samples as representative evidence, not proof of production cardinality, selectivity, or plan stability. Record the exact audited tool-call ids and limitations. The UTF-8 encoded `database-evidence.json` must not exceed 262144 bytes.

Write only the following three files, using the exact absolute paths supplied in the runtime task context:

1. `report.md`
2. `summary.json`
3. `database-evidence.json`

Do not write any other file. `report.md` must use every section in the complete embedded report template. Its Database Evidence section must contain exactly `[database-evidence.json](database-evidence.json)` with no additional claim or text. `summary.json` must satisfy the complete embedded JSON schema. Both JSON artifacts and the report must repeat the exact mapper path, namespace, statement id, statement key, command type, and selectKey identity from the runtime context. `database-evidence.json` must include the bound database identity, exact audited call facts (call id, tool, timestamp, duration, arguments, and complete canonical result; query scenarios must also repeat query/columns/rows), the post-hoc audit declaration, metadata evidence, zero to three scenarios, retained rows, and explicit limitations. All content must be final and concrete; do not leave template tokens or placeholders.
