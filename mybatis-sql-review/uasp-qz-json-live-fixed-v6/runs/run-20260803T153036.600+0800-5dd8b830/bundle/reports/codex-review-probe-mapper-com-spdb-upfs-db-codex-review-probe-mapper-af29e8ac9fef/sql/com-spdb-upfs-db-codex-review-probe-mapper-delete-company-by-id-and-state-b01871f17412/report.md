# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. The statement key is
`com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`, mapper-relative path
`src/test/resources/mapper/CodexReviewProbeMapper.xml`, namespace `com.spdb.upfs.db.CodexReviewProbeMapper`, statement
id `deleteCompanyByIdAndState`, command type `delete`, selectKey flag `false`, source line range 81–85. Runtime binding:
data_source `deepseek@localhost`, catalog `deepseek`, schema `deepseek`, project
`/Users/wangyufeng/IdeaProjects/uasp-qz-json`, scope `ALL`.

## Statement

The mapper is `CodexReviewProbeMapper`. The reviewed statement is `deleteCompanyByIdAndState`, command type `delete`,
selectKey flag `false`, source line range 81–85, with no dynamic SQL branches. Parameters are `#{id,jdbcType=INTEGER}`
and `#{companyState,jdbcType=VARCHAR}`. The raw mapper XML and normalized SQL are reproduced below.

- Data source: `deepseek@localhost`
- Catalog: `deepseek`
- Schema: `deepseek`
- Project: `/Users/wangyufeng/IdeaProjects/uasp-qz-json`
- Scope: `ALL`
- Safety mode: `connectivity-only`
- Database safety: `unverified`

Raw mapper XML:

```xml
<delete id="deleteCompanyByIdAndState" parameterType="java.util.Map">
        DELETE FROM COMPANY
        WHERE ID = #{id,jdbcType=INTEGER}
          AND COMPANY_STATE = #{companyState,jdbcType=VARCHAR}
    </delete>
```

Normalized SQL:

```sql
DELETE FROM COMPANY
        WHERE ID = #{id,jdbcType=INTEGER}
          AND COMPANY_STATE = #{companyState,jdbcType=VARCHAR}
```

## Static Analysis

- Single-table `DELETE FROM COMPANY` filtered by `ID` and `COMPANY_STATE`.
- Both predicates use prepared-statement placeholders (`#{...}`), so there is no SQL-injection surface and no string
  concatenation of caller input.
- The two-column predicate narrows the deletion set; the state guard reduces the risk of deleting an unexpected row
  compared with an ID-only or state-only delete, but effectiveness depends on the concrete `companyState` value supplied
  by the caller (requires runtime confirmation).
- No `LIMIT` clause: a single-row-by-key intent is not enforced by the SQL itself; if the bound `ID` is non-unique or
  repeated, multiple rows could be deleted (requires runtime confirmation of column uniqueness).
- `parameterType="java.util.Map"` keeps the binding loose; a missing map key would surface only as an execution-time
  binding error, not a compile-time failure.
- No optimistic-locking or version guard exists; in a check-then-act flow a row read earlier could already be deleted by
  the time this statement runs (requires confirmation of application-level locking).
- Data-volume risk is low for a key-based predicate, but without an index on `ID` or `(ID, COMPANY_STATE)` the delete
  could degrade to a scan; index coverage is unverified.

Claims marked "requires runtime confirmation" are static observations only and were not verified against live data.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

No finding remains for this statement. It is a `delete` command, static-review-only under this contract; no Database MCP
query tool call was permitted or made, so no runtime evidence was collected and no evidence-backed defect can be
asserted.

## Recommendations

- Verify in the schema that `ID` is a primary/unique key and that an index supports `(ID, COMPANY_STATE)` so the delete
  resolves as an index lookup rather than a scan.
- Confirm the caller always supplies a concrete `companyState` so the guard predicate is effective; consider an
  optimistic-locking or audit-condition column if this delete participates in a check-then-act workflow.
- Prefer a strongly typed parameter object over `java.util.Map` to fail fast on missing keys.
- These recommendations do not require executing the original delete statement.

## Limitations

- This is a `delete` statement; per the review contract the Database MCP query tool is forbidden for DML, so no scenario
  query was run, `audit.tool_call_ids` is empty, and row cardinality, selectivity, and plan stability of the statement
  are unverified.
- Runtime `safety_mode` is `connectivity-only` with `database_safety=unverified`; no row-security, function-execution,
  or timeout enforcement was confirmed for the connection.
- Representative-row evidence, column uniqueness, and index coverage could not be confirmed from the database.
