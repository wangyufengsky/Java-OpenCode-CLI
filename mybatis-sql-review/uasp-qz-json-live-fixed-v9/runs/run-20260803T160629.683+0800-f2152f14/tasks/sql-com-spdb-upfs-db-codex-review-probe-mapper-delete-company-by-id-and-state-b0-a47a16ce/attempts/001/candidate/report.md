# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence.

- Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`
- Mapper-relative path: `src/test/resources/mapper/CodexReviewProbeMapper.xml`
- Namespace: `com.spdb.upfs.db.CodexReviewProbeMapper`
- Statement id: `deleteCompanyByIdAndState`
- Command type: `delete`
- SelectKey flag: `false`
- Source line range: 81-85
- Runtime binding: data_source `deepseek@localhost`, catalog `deepseek`, schema `deepseek`, project
  `/Users/wangyufeng/IdeaProjects/uasp-qz-json`, scope `ALL`

## Statement

- Mapper: `com.spdb.upfs.db.CodexReviewProbeMapper`
- Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`
- Command type: `delete`
- SelectKey flag: `false`
- Source line range: 81-85
- Dynamic SQL branches: none (`dynamic_nodes` is empty)
- Parameters: `#{id,jdbcType=INTEGER}`, `#{companyState,jdbcType=VARCHAR}`
- Raw mapper XML:

```xml
<delete id="deleteCompanyByIdAndState" parameterType="java.util.Map">
        DELETE FROM COMPANY
        WHERE ID = #{id,jdbcType=INTEGER}
          AND COMPANY_STATE = #{companyState,jdbcType=VARCHAR}
    </delete>
```

- Normalized SQL:

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

The statement is a single-row-oriented delete keyed by a primary-key-style column `ID` plus a business-state filter
`COMPANY_STATE`.

- Correctness: If `ID` is a unique primary key, the additional `AND COMPANY_STATE = ...` predicate is logically
  redundant for row identity but deliberately restricts deletion to a specific state (optimistic/guarded delete). A
  consequence is that a caller passing a stale `companyState` will not receive an error — the delete silently affects
  zero rows. Whether this behavior is intended depends on business rules; this requires runtime confirmation (
  unverified).
- Typing: `parameterType="java.util.Map"` means parameter keys are untyped strings. A typo such as `companyStat`
  produces a MyBatis binding error only at execution time, not at compile time. JDBC types are declared explicitly (
  `INTEGER`, `VARCHAR`), which is good practice.
- Performance / concurrency: The `ID` equality predicate (if indexed) targets a single row, so concurrency impact and
  row-lock scope are minimal. Under READ COMMITTED with unique `ID`, concurrent deletes of the same row are serialized
  at the row level. These claims require runtime confirmation.
- Data volume: Deleting by an exact `ID` value bounds the affected row count to one (assuming uniqueness); no unbounded
  full-table scan is present in the predicate. `COMPANY_STATE` without an index could imply a scan in any query that
  filters on it alone, but here it is combined with `ID`.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

1. **F-1 (info / redundant-predicate)**: `DELETE` combines a unique-key filter (`ID`) with a state filter (
   `COMPANY_STATE`). If `ID` is the primary key, the state predicate is redundant for identity and can silently no-op
   the delete when the state is stale. *Unverified* — requires schema confirmation of `ID` uniqueness and of intended
   business semantics.
2. **F-2 (info / typing)**: `parameterType="java.util.Map"` leaves parameter keys untyped, so a misspelled key fails
   only at runtime. Using a typed DTO or `@Param` annotations would surface such errors earlier.

No Database MCP evidence was collected (static-review-only statement); therefore all finding `evidence_ids` arrays are
empty and the observations above remain static claims pending runtime confirmation. No additional findings remain.

## Recommendations

- Confirm whether the `COMPANY_STATE` guard is intentional. If not, drop it so the delete is driven solely by `ID` and
  returns the affected-row count as an unambiguous signal.
- If the state guard must stay, have the caller compare the affected-row count against expectations so a zero-row delete
  is surfaced instead of silently ignored.
- Replace `java.util.Map` with a typed parameter object (or `@Param("id")`, `@Param("companyState")`) to make parameter
  keys compile-time-checkable.
- Verify an index exists on `COMPANY_STATE` only if any statement filters on it alone; the reviewed statement itself is
  served by the `ID` index.
- No recommendation requires executing the original DML or a selectKey statement.

## Limitations

- This is a `delete` (static-review-only) statement: no Database MCP query was executed, `tool_call_ids`, `metadata`,
  and `scenarios` are empty, and no runtime rows were observed.
- `database_safety` is `unverified` and `safety_mode` is `connectivity-only`; no database-level assertions (row
  security, function execution, timeout controls) were verified in this session.
- `ID` uniqueness, the existence of indexes, actual table cardinality, and whether `COMPANY_STATE` values match callers'
  expectations were not confirmed against live metadata.
- Representative row counts, selectivity, and plan stability cannot be assessed without executed queries.
