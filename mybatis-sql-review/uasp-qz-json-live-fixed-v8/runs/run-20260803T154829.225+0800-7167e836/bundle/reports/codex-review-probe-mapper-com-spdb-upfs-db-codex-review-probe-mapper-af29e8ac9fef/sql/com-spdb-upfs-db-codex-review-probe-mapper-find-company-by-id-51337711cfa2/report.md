# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database MCP evidence. Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-find-company-by-id-51337711cfa2`. Mapper-relative path: `src/test/resources/mapper/CodexReviewProbeMapper.xml`. Namespace: `com.spdb.upfs.db.CodexReviewProbeMapper`. Statement id: `findCompanyById`. Command type: `select`. selectKey flag: `false`. Source line range: 34-40. Runtime binding: `data_source=deepseek@localhost`, `catalog=deepseek`, `schema=deepseek`, `project=/Users/wangyufeng/IdeaProjects/uasp-qz-json`, `scope=ALL`.

## Statement

The statement is a read-only `select` in mapper `src/test/resources/mapper/CodexReviewProbeMapper.xml` (namespace `com.spdb.upfs.db.CodexReviewProbeMapper`, statement id `findCompanyById`, source lines 34-40). It is not a selectKey statement (`select_key=false`). It has no dynamic SQL branches (`dynamic_nodes` is empty); the only parameter placeholder is `#{id,jdbcType=INTEGER}`. The raw mapper XML and normalized SQL are reproduced below.

Raw mapper XML:

```xml
<select id="findCompanyById" parameterType="java.lang.Integer"
            resultMap="CompanyResultMap">
        SELECT
        <include refid="CompanyColumns"/>
        FROM COMPANY
        WHERE ID = #{id,jdbcType=INTEGER}
    </select>
```

Normalized SQL:

```sql
SELECT
        ID,
        COMPANY_NAME,
        COMPANY_STATE,
        COMPANY_CITY,
        EMPLOYEE,
        COMPANY_CREATE_YEAR
    FROM COMPANY
    WHERE ID = #{id,jdbcType=INTEGER}
```

- Data source: `deepseek@localhost`
- Catalog: `deepseek`
- Schema: `deepseek`
- Project: `/Users/wangyufeng/IdeaProjects/uasp-qz-json`
- Scope: `ALL`
- Safety mode: `connectivity-only`
- Database safety: `unverified`

## Static Analysis

The statement is a simple point lookup: `SELECT <columns> FROM COMPANY WHERE ID = #{id,jdbcType=INTEGER}`, returning six columns (ID, COMPANY_NAME, COMPANY_STATE, COMPANY_CITY, EMPLOYEE, COMPANY_CREATE_YEAR) mapped via `CompanyResultMap`. There are no dynamic SQL branches, joins, subqueries, `ORDER BY`, or `LIMIT`.

Correctness: the equality predicate relies on `ID` being unique. If `ID` is not backed by a primary-key or unique constraint, the filter can match more than one row, so the caller may observe an unexpected row count. This is a claim that requires runtime confirmation (index/constraint metadata), which was not executed.

Performance: a single-row equality lookup on a unique/primary key is expected to use an index seek; without a unique index the planner may fall back to a scan. Cardinality, selectivity, and plan stability cannot be confirmed from static analysis alone.

Concurrency: no locking or concurrency-sensitive constructs are present.

Data volume: for a point lookup the data-volume risk is low; the absence of a `LIMIT` is not strictly a defect when `ID` is unique.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

No native Database MCP call was made in this Agent session, so every finding below is a static observation with an empty `evidence_ids` array, and all runtime-confirmation claims remain unverified.

1. Severity: low — Category: correctness/robustness — Title: "Equality lookup without an explicit LIMIT assumes ID uniqueness". Description: `WHERE ID = #{id,jdbcType=INTEGER}` returns every row matching the value; if `ID` is not a primary key or unique column, more than one row can be returned even though the surrounding code may expect a single row. Confirmed: the SQL text lacks any row cap. Unverified: whether a duplicate can actually occur (requires unique-constraint metadata). Recommendation: confirm `ID` has a primary-key/unique constraint, or add `LIMIT 1` (e.g. `FETCH FIRST 1 ROWS ONLY`) if the contract is a single row.

2. Severity: info — Category: performance — Title: "Index usage for the ID predicate is unverified". Description: a point lookup benefits from a unique index on `ID`; without one the plan may degrade to a scan. This is an unverified claim that requires index metadata or an explained plan. Recommendation: verify the unique index on `ID` via database metadata or EXPLAIN tooling outside this review; this recommendation does not require executing the reviewed statement.

## Recommendations

- Confirm that `ID` is the primary key (or uniquely constrained) on `COMPANY`, and add `LIMIT 1` if a single-row result is the intended contract.
- Verify index availability for the `ID` predicate via metadata/EXPLAIN tooling outside this review; do not execute the reviewed statement from this artifact.
- Consider narrowing the returned column list to only the columns actually consumed by the caller (for example, if `EMPLOYEE` is not needed downstream).

## Limitations

- No native Database MCP call was made in the Agent session, so no representative rows are available. Runtime cardinality, selectivity, plan stability, and index usage for the `COMPANY.ID` predicate could not be confirmed.
- `database_safety` is `unverified` under `connectivity-only` safety mode; the connection and the bounded relation inventory were verified only by Java preflight.
- The `CompanyColumns` include fragment was expanded for this review from the runtime normalized SQL; the exact mapper include definition was not re-read.
- The uniqueness of `ID`, the row counts, and the data volumes on `COMPANY` remain unresolved uncertainties.
