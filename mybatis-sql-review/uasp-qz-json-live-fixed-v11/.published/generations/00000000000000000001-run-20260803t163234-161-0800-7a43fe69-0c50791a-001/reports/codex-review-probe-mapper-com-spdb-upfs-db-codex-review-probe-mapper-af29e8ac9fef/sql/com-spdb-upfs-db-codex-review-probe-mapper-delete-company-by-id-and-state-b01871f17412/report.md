# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. The statement key is
`com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`, mapper-relative path
`src/test/resources/mapper/CodexReviewProbeMapper.xml`, namespace `com.spdb.upfs.db.CodexReviewProbeMapper`, statement
id `deleteCompanyByIdAndState`, command type `delete`, selectKey flag `false`, source line range 81-85, and runtime
binding data_source `deepseek@localhost`, catalog `deepseek`, schema `deepseek`, project
`/Users/wangyufeng/IdeaProjects/uasp-qz-json`, scope `ALL`.

## Statement

The mapper is `CodexReviewProbeMapper.xml` (relative path `src/test/resources/mapper/CodexReviewProbeMapper.xml`). The
statement under review is the delete statement `deleteCompanyByIdAndState`. It is a plain, non-dynamic DELETE with no
dynamic SQL branches, no selectKey, and no result mapping. Its parameters are `#{id,jdbcType=INTEGER}` and
`#{companyState,jdbcType=VARCHAR}` passed as a `java.util.Map`.

- Data source: `deepseek@localhost`
- Catalog: `deepseek`
- Schema: `deepseek`
- Project: `/Users/wangyufeng/IdeaProjects/uasp-qz-json`
- Scope: `ALL`
- Safety mode: `connectivity-only`
- Database safety: `unverified`

Raw mapper XML (lines 81-85):

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

- Correctness: the statement is a hard `DELETE FROM COMPANY` filtered by `ID` and `COMPANY_STATE`. Both values are bound
  through `#{}` prepared-statement placeholders, so no literal injection is possible. The predicate assumes `ID`
  uniquely identifies a single target row; if `ID` is not a unique/primary key, a single invocation can remove multiple
  rows. This uniqueness claim requires runtime confirmation.
- Data safety: deleted rows are permanently removed with no audit record, tombstone, or soft-delete marker produced by
  this statement. Whether hard delete is the intended business behavior (versus soft delete / state transition) requires
  confirmation.
- Maintainability: the statement is simple and self-contained with an explicit `jdbcType` on each placeholder; no
  maintainability risk is visible from source.
- Performance / concurrency: performance depends on an index covering `ID` (and `COMPANY_STATE`). Because the statement
  is a delete and the Database MCP query tool is forbidden for it, plan shape, row counts, and lock behavior were not
  observed at runtime. Under concurrent access, delete/lock contention is possible but unverified.
- Data volume: no volume-sensitive construct (no unanchored scan implied beyond the predicate) is visible, but the
  number of rows matched by `(ID, COMPANY_STATE)` could not be measured at runtime.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

1. **F-1 — low — data-safety — Irreversible hard DELETE without audit or soft-delete safeguard.**
   `deleteCompanyByIdAndState` permanently removes rows from `COMPANY`. There is no audit record, tombstone, or
   soft-delete column written by this statement, so a mis-issued call is unrecoverable through the mapper. This is a
   confirmed static observation; the intended business semantics (hard vs soft delete) are unverified. Evidence ids:
   none (static-review-only delete; the Database MCP query tool is forbidden for it).

2. **F-2 — info — correctness — Predicate assumes `ID` uniquely identifies a single target row.** The `WHERE` clause
   filters only on `ID` and `COMPANY_STATE`. If `ID` is not unique in `COMPANY`, one call can delete multiple rows. `ID`
   uniqueness and the delete selectivity of `COMPANY_STATE` could not be verified against the database because this
   statement is static-review-only. Evidence ids: none.

No finding remains beyond the two above.

## Recommendations

- Confirm with the owning team that an irreversible hard delete is the intended behavior for
  `deleteCompanyByIdAndState`. If historical retention or compliance is required, replace the hard DELETE with a
  soft-delete UPDATE (e.g., set a state/valid-to column) or log the affected identifiers inside the enclosing
  transaction before deleting.
- Verify that `COMPANY.ID` is a unique or primary-key column (index/constraint on the table). If multi-row deletes are
  possible, add explicit safeguards: a pre-delete row-count check inside the transaction, or a `LIMIT 1` where the
  target database supports `DELETE ... LIMIT`, guarding against accidental bulk removal.
- Ensure an index on `COMPANY.ID` (and, if available, `(ID, COMPANY_STATE)`) exists to keep the lookup anchored; confirm
  via execution plan at runtime.
- Do not execute the original DML in any non-test environment; all runtime verification must use read-only, bounded
  queries or test data.

## Limitations

- This statement is a `delete`, which is static-review-only: the Database MCP query tool is forbidden for it, so no
  connection probe, metadata call, or scenario query was run in the Agent session. `audit.tool_call_ids`, `metadata`,
  and `scenarios` in `database-evidence.json` are therefore empty.
- `safety_mode` is `connectivity-only` and `database_safety` is `unverified`: no row-security, function-execution, or
  timeout enforcement assertions are claimed for the connection.
- The database binding (data_source `deepseek@localhost`, catalog `deepseek`, schema `deepseek`, project
  `/Users/wangyufeng/IdeaProjects/uasp-qz-json`, scope `ALL`) is recorded from the runtime context and was not
  re-verified by the Agent.
- Representative-row caveats do not apply because no query executed; however, `ID` uniqueness, `COMPANY_STATE` delete
  selectivity, index coverage, and actual row counts are unresolved uncertainties that require runtime confirmation.

