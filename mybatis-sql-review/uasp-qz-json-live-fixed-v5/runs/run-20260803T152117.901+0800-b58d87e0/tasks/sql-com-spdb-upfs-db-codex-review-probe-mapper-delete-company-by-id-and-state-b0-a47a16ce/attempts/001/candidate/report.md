# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence.

Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`. Mapper-relative
path: `src/test/resources/mapper/CodexReviewProbeMapper.xml`. Namespace: `com.spdb.upfs.db.CodexReviewProbeMapper`.
Statement id: `deleteCompanyByIdAndState`. Command type: `delete`. selectKey flag: `false`. Source line range: 81-85.

## Statement

The reviewed statement is the `<delete>` element `deleteCompanyByIdAndState` in `CodexReviewProbeMapper.xml` (lines
81-85). It is a parameterized hard DELETE keyed by the company primary key plus a state guard. It has no dynamic SQL
branches (`<if>`/`<choose>`/`<foreach>`/`<trim>`); the SQL is fully static. It accepts a `java.util.Map` with two
placeholders: `#{id,jdbcType=INTEGER}` and `#{companyState,jdbcType=VARCHAR}`.

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
DELETE
FROM COMPANY
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

- Correctness: `ID` is a NOT NULL integer column and, per metadata, the unique PRIMARY KEY of `COMPANY`. An equality
  predicate on the unique key therefore targets at most one row, and the additional `COMPANY_STATE` equality narrows the
  deletion to a single logical record. The jdbcTypes (INTEGER, VARCHAR) match the metadata column types (`ID` INT,
  `COMPANY_STATE` VARCHAR(255)).
- Null handling (requires runtime confirmation): `COMPANY_STATE` is nullable in the schema. If the caller passes a null
  `companyState`, MyBatis binds SQL NULL and `COMPANY_STATE = NULL` never evaluates true, so the statement silently
  deletes zero rows rather than raising an error. This is safe but may hide a caller bug.
- Performance: the `WHERE` is fully satisfied by the PRIMARY index on `ID`; no additional index is needed and the state
  filter adds a residual predicate only. Data volume is bounded to one row.
- Concurrency: a single-row DELETE takes a record lock on the matched row; the `ID`+`COMPANY_STATE` predicate is
  evaluated atomically under that lock, so there is no TOCTOU window where the state could change between a check and
  the delete. No lock-ordering concern for a single statement.
- Data-volume / data-integrity: this is a destructive hard DELETE (not a soft delete / state transition). The only
  protection against deleting the wrong row is the caller-supplied `COMPANY_STATE`; the mapper itself returns only the
  affected-row count. Whether that count is checked lives outside the mapper. Parameter type `java.util.Map` is weakly
  typed and offers no compile-time safety.
- Maintainability: parameterType=Map and unqualified two-part predicate are simple and readable; the hard delete
  semantics should be kept consistent with the service-layer contract.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- F-01 — severity `low`, category `data-integrity` — hard DELETE depends entirely on the caller-supplied `COMPANY_STATE`
  guard; the mapper exposes no mechanism (e.g., LIMIT, row-count assertion) to bound the blast radius beyond the
  PRIMARY-key predicate. Confirmed statically; the unique index on `ID` (evidence: table-schema metadata) bounds the
  delete to one row. Recommendation: at the service layer, verify the affected-row count and treat a `0`-row or
  unexpected result as an error.
- F-02 — severity `info`, category `correctness` — `COMPANY_STATE` is nullable in the schema; a null parameter binds as
  `COMPANY_STATE = NULL`, which matches no rows and silently deletes nothing. Unverified whether callers can pass null;
  flagging as an invariants risk. Recommendation: validate `companyState` non-null in the service layer before invoking
  this mapper.
- F-03 — severity `info`, category `maintainability` — `parameterType="java.util.Map"` is weakly typed; a typo in a map
  key fails only at runtime. Recommendation: replace the Map with a typed parameter object or `@Param` method arguments.

## Recommendations

- Service layer: check the affected-row count from this delete and fail on unexpected (0 or >1) results so a mis-called
  state guard cannot pass silently.
- Service layer: validate `id` and `companyState` are non-null before the call to avoid a silent no-op delete.
- Replace `parameterType="java.util.Map"` with a typed parameter object for compile-time safety.
- If the business intent is archival rather than destruction, consider a soft-delete (UPDATE of a state column) instead
  of DELETE.
- No changes are required to the SQL itself for performance: the PRIMARY index on `ID` covers the predicate.

## Limitations

- This is a `delete` statement; per the review contract no `cmcp_db_database_execute_sql_query` scenario calls were
  made (the query tool is forbidden for DML), so no row-level results were produced (`scenario_count = 0`).
- Database metadata is from a connectivity-only, `unverified` session; `database_safety` is `unverified`. Evidence
  reflects representative metadata (data source, catalogs, and `COMPANY` table shape/indexes) at review time, not a
  guarantee of production cardinality, selectivity, or plan stability.
- Evidence call ids are the native tool-call names because the Database MCP responses expose no other stable
  identifiers.
- Null-value behavior, affected-row expectations, and transaction semantics of the caller are inferred and require
  runtime confirmation.
