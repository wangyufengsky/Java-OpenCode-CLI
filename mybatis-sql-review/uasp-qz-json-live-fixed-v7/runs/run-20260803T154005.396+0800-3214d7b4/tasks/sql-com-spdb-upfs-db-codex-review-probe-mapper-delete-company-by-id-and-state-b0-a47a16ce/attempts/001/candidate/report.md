# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`;
mapper-relative path: `src/test/resources/mapper/CodexReviewProbeMapper.xml`; namespace:
`com.spdb.upfs.db.CodexReviewProbeMapper`; statement id: `deleteCompanyByIdAndState`; command type: `delete`; selectKey
flag: `false`; source line range: `81-85`; runtime binding: `data_source=deepseek@localhost`, `catalog=deepseek`,
`schema=deepseek`, `project=/Users/wangyufeng/IdeaProjects/uasp-qz-json`, `scope=ALL`.

## Statement

Mapper: `CodexReviewProbeMapper.xml`. Statement key:
`com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`. Command type: `delete`.
selectKey flag: `false`. Source line range: `81-85`. Dynamic SQL branches: none (`dynamic_nodes` is empty). Parameters:
`#{id,jdbcType=INTEGER}`, `#{companyState,jdbcType=VARCHAR}`.

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

- Data source: `deepseek@localhost`
- Catalog: `deepseek`
- Schema: `deepseek`
- Project: `/Users/wangyufeng/IdeaProjects/uasp-qz-json`
- Scope: `ALL`
- Safety mode: `connectivity-only`
- Database safety: `unverified`

## Static Analysis

This is a single-row-targeted DELETE keyed by `ID` (likely primary key) and `COMPANY_STATE`. Correctness: both
predicates are equality filters and both placeholders carry explicit jdbcTypes; there are no dynamic SQL branches.
Maintainability: the mapper does not surface the affected-row count, so callers cannot distinguish a no-match delete
from a successful one. Concurrency/data-volume: each execution removes at most one row, so row-lock scope is one row;
efficient keyed access depends on an index on `ID` or `(ID, COMPANY_STATE)`, which requires runtime confirmation (
unverified). No LIMIT or batch guard exists, but the predicate targets a single row by key, so unbounded-scan risk is
low unless `ID` is unindexed.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- `F-1` (low, performance): Indexed access for the `ID`/`COMPANY_STATE` predicate is unverified. Supporting evidence:
  static source only. Not confirmed — requires index metadata confirmation.
- `F-2` (info, concurrency/transaction): The mapper provides no transaction boundary; atomicity and rollback depend
  entirely on the caller. Supporting evidence: static source only.
- `F-3` (info, maintainability): The affected-row count is not surfaced; no-match versus actual delete is
  indistinguishable to callers. Supporting evidence: static source only.

## Recommendations

- Confirm an index on `COMPANY(ID)` or `(ID, COMPANY_STATE)` before relying on keyed access in production.
- Ensure callers wrap this delete in an explicit transaction and inspect the affected-row count to detect no-match
  deletes.
- Do not add `LIMIT` or batching unless volume analysis shows multi-row deletions; keep single-row keyed semantics.

## Limitations

This is a static-review-only DELETE statement; no Database MCP query was executed. Representative-row evidence was not
collected, and index structure, row counts, and plan stability are unverified. `safety_mode=connectivity-only` and
`database_safety=unverified`; connection properties were not re-probed after the task boundary.
