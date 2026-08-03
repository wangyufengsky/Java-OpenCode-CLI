# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-find-company-by-id-51337711cfa2`;
mapper-relative path: `src/test/resources/mapper/CodexReviewProbeMapper.xml`; namespace:
`com.spdb.upfs.db.CodexReviewProbeMapper`; statement id: `findCompanyById`; command type: `select`; selectKey flag:
`false`; source line range: 34-40; runtime binding: `data_source=deepseek@localhost`, `catalog=deepseek`,
`schema=deepseek`, `project=/Users/wangyufeng/IdeaProjects/uasp-qz-json`, `scope=ALL`.

## Statement

Mapper: `CodexReviewProbeMapper.xml` (`src/test/resources/mapper/CodexReviewProbeMapper.xml`)

- Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-find-company-by-id-51337711cfa2`
- Namespace: `com.spdb.upfs.db.CodexReviewProbeMapper`
- Statement id: `findCompanyById`
- Command type: `select`
- SelectKey flag: `false`
- Source line range: 34-40
- Dynamic SQL branches: none
- Parameters: `#{id,jdbcType=INTEGER}`

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

The statement is a single-table point lookup on `COMPANY` filtered by `ID` using a bound placeholder (
`#{id,jdbcType=INTEGER}`), so the predicate value is always parameterized and there is no literal SQL-injection surface.
The projection is inlined from the reusable `<include refid="CompanyColumns"/>` fragment (ID, COMPANY_NAME,
COMPANY_STATE, COMPANY_CITY, EMPLOYEE, COMPANY_CREATE_YEAR), which keeps column lists consistent across statements in
the mapper.

Correctness: the SQL is well-formed and applies an equality predicate on `ID`. Whether `ID` is the primary key or
uniquely indexed requires runtime metadata confirmation (unverified). Column-to-property alignment with
`CompanyResultMap` cannot be fully confirmed from this snippet alone.

Performance: a point lookup filtered on a single equality predicate is expected to be index-friendly, but index
existence and row cardinality could not be confirmed without runtime evidence (unverified).

Concurrency and data volume: no locking, aggregation, or unbounded scans are present; for a low-volume point lookup
there is no material concurrency or data-volume risk visible statically.

All claims that depend on actual table structure or optimizer behavior are marked unverified and would require runtime
confirmation.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

No concrete finding remains. The statement is a parameterized single-row lookup with no dynamic SQL, no injection
surface, and no statically visible correctness, performance, or concurrency defect. Because no bounded scenario query or
Database MCP evidence was collected in this session, every runtime-dependent claim above is unverified rather than
confirmed.

## Recommendations

- Confirm at runtime (outside this review, without executing the original mapped statement) that `COMPANY.ID` is
  indexed (ideally the primary key or a unique index) and that its data type matches the bound `Integer` parameter.
- Verify that the `<include refid="CompanyColumns"/>` fragment column list matches both the `CompanyResultMap` mapping
  and the actual `COMPANY` table columns.
- No code change is required for the reviewed statement itself.

## Limitations

- No Agent-session Database MCP calls were made: the runtime context supplied no concrete bounded scenario query, so
  `audit.tool_call_ids`, `metadata`, and `scenarios` are empty and this zero-scenario SELECT review is valid.
- `safety_mode=connectivity-only` and `database_safety=unverified`; connection properties, permissions, and
  row/function-security controls were not verified in this session.
- Representative-row evidence, index plans, and cardinality are unavailable; all runtime-dependent statements are
  unverified.
- Static review covers only the mapper fragment supplied; the `<include>` fragment content is inferred from
  `normalized_sql` and not independently read.
