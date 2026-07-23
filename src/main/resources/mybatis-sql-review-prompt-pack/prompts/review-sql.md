# MyBatis SQL Review Agent Contract

Review one inventory statement with static analysis and bounded Database MCP evidence.

## Allowed Database MCP tools

Use only these four read-oriented tools:

- `cmcp_db_database_list_datasources`
- `cmcp_db_database_list_databases`
- `cmcp_db_database_list_table_schema`
- `cmcp_db_database_execute_sql_query`

```json
{"project":"absolute project path","scope":"ALL"}
{"dataSource":"configured data source","project":"absolute project path","scope":"ALL"}
{"dataSource":"configured data source","catalog":"configured catalog","schema":"configured schema","includeColumns":true,"includeIndexes":true,"maxTables":200,"project":"absolute project path","scope":"ALL"}
{"dataSource":"configured data source","sql":"SELECT columns FROM safe_table LIMIT 1","maxRows":20,"project":"absolute project path","scope":"ALL"}
```

For metadata calls, use only the runtime `data_source`, `catalog`, `schema`, `project`, and `scope` values. The table-schema call must request columns and indexes and retain a bounded table count.

For every `cmcp_db_database_execute_sql_query` call, pass `dataSource`, `sql`, `maxRows`, `project`, and `scope`. `maxRows` is always 20. Each scenario SQL is a Java-validated read-only SELECT against a safe base table and includes an integer `LIMIT` from 1 through 20. Run at most three scenario queries.

Do not call DML, DDL, NoSQL, or unknown tools. `<insert>`, `<update>`, `<delete>`, and `<selectKey>` statements are static-review-only: do not execute a query scenario for them. Do not execute the original mapped SQL.

## Evidence and output contract

Treat returned rows as representative evidence, not proof of production cardinality, selectivity, or plan stability. Record actual native tool names, normalized arguments, audited tool-call ids, results, and limitations. The UTF-8 encoded `database-evidence.json` must not exceed 262144 bytes.

Write only the three exact absolute output paths in the runtime task context:

1. `report.md`
2. `summary.json`
3. `database-evidence.json`

Do not write any other file. `report.md` uses the complete embedded template. Its Database Evidence section contains exactly `[database-evidence.json](database-evidence.json)`. Fill the five labeled report lines with the exact runtime `data_source`, `catalog`, `schema`, `project`, and `scope` values. `summary.json` satisfies the complete embedded schema and repeats those same values. All artifacts repeat the exact mapper and statement fields from the runtime context; do not add placeholder content.
