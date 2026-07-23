# Native Database MCP SQL Review Design

## Purpose

The MyBatis SQL review workflow uses database tools registered by AgentBridge Custom MCP. Runtime code, prompts, reports, configuration, tests, and documentation share this native database contract.

## Runtime topology

The application and review agent connect only to the AgentBridge MCP endpoint. AgentBridge routes tools with the `cmcp_db_` prefix to the configured Database MCP server. The workflow never connects directly to the Database MCP endpoint.

The local endpoints are:

- AgentBridge Web Access: `https://127.0.0.1:9642`
- AgentBridge MCP: `http://127.0.0.1:8643/mcp`
- Database MCP remains an AgentBridge Custom MCP implementation detail.

## Native tool contract

The SQL review workflow recognizes these read-oriented tools:

- `cmcp_db_database_list_datasources`
- `cmcp_db_database_list_databases`
- `cmcp_db_database_list_table_schema`
- `cmcp_db_database_execute_sql_query`

The query input is:

```json
{
  "dataSource": "configured data source name",
  "sql": "validated SELECT with LIMIT 1..20",
  "maxRows": 20,
  "project": "absolute IntelliJ project path",
  "scope": "ALL"
}
```

`dataSource` and `sql` are required. `maxRows` is always 20. `project` is the normalized absolute repository path. `scope` is configurable as `GLOBAL`, `PROJECT`, or `ALL`, with `ALL` as the workflow default.

The table-schema input binds the same `dataSource`, `project`, and `scope`, adds the configured `catalog` and `schema`, requests column details, and uses a bounded table count.

The following tools are prohibited in every SQL review task:

- `cmcp_db_database_execute_sql_dml`
- `cmcp_db_database_execute_sql_ddl`
- `cmcp_db_database_execute_nosql_write_delete`
- `cmcp_db_database_execute_nosql_query`

Any observed call to a prohibited or unknown tool fails the statement task and prevents publication.

## Configuration

The database configuration retains the user-facing fields `connection-name`, `database-name`, and `schema-name` because they express review intent independently of a transport. Their Database MCP bindings are:

- `connection-name` to `dataSource`
- `database-name` to `catalog`
- `schema-name` to `schema`

The workflow adds `scope`, defaulting to `ALL`. The absolute project path is derived from `project.repo`; it is not duplicated in configuration.

The AgentBridge MCP URL is `http://127.0.0.1:8643/mcp`. Web Access remains required for prompt execution, task state, and tool-call history.

## Preflight and per-task checks

Preflight performs the following sequence:

1. Negotiate an AgentBridge MCP session and list tools.
2. Require the four native read-oriented tools and validate their input schemas.
3. List data sources and require exactly one configured `dataSource` match.
4. List databases for that data source and require the configured catalog/schema target.
5. Read table metadata for the configured catalog/schema.
6. Execute the fixed GaussDB safety probe through `cmcp_db_database_execute_sql_query` with `maxRows: 20`.
7. Verify the physical standby, current database/schema, account privileges, function permissions, RLS conditions, statement timeout, and safe base-table set from the probe result.

Immediately before each statement task, the workflow repeats the data-source binding and safety probe. A missing tool, changed data source, malformed response, failed safety fact, or unavailable Web Access fails closed before the review prompt is submitted.

## Prompt and task behavior

Each statement prompt presents only the native Database MCP tool names and their actual argument schemas. It instructs the agent to:

- use metadata tools only for the configured data source, catalog, and schema;
- use `cmcp_db_database_execute_sql_query` only for review scenarios based on safe base tables;
- include an integer `LIMIT` from 1 through 20 in every scenario query;
- pass `maxRows: 20` on every query call;
- run at most three scenario queries;
- perform static review only for DML mappers and `selectKey` statements;
- write only the statement's candidate report artifacts.

Prompts and correction messages use the native Database MCP names and argument contract.

## Tool-call evidence

After each statement task, Java reads AgentBridge `/tool-calls`, parses string-encoded arguments and results into JSON, and selects calls belonging to the task boundary. Every selected call is checked for:

- an allowed native tool name;
- the configured `dataSource`, project, and scope;
- the configured catalog/schema where applicable;
- `maxRows: 20` for SQL queries;
- a Java-validated read-only query with an explicit bounded limit;
- at most three query scenarios;
- no query execution for DML mappers or `selectKey` statements;
- successful completion, parseable result data, at most 20 retained rows, and bounded evidence size.

Malformed, ambiguous, or unparseable records fail the task. Reports record the actual native tool names and normalized arguments without describing previous contracts.

## Publication and reports

The existing candidate validation and atomic publication model remains in place. A failed preflight, statement task, tool-call validation, evidence validation, schema validation, or link validation preserves the previous stable report.

Detailed SQL reports, mapper indexes, the project report, traceability data, and data-quality output describe Database MCP evidence with the native tool names and normalized arguments.

## Testing

Implementation follows red-green-refactor cycles. Tests cover:

- the exact native tool catalog and input schemas;
- data-source, catalog, schema, project, and scope binding;
- query arguments with `maxRows: 20`;
- string-encoded AgentBridge tool-call arguments and results;
- allowed metadata and query calls;
- rejection of DML, DDL, NoSQL, unknown tools, wrong targets, excessive rows, excessive scenarios, unsafe SQL, DML mapper execution, and `selectKey` execution;
- GaussDB safety-probe validation and per-task recheck;
- prompt, report, schema, console, README, and configuration contracts;
- repository-wide consistency of native Database MCP identifiers and current-contract wording.

Final verification runs focused tests after each red-green cycle, the complete Maven suite, Node tests, JavaScript syntax checks, `git diff --check`, and a repository-wide residual scan.
