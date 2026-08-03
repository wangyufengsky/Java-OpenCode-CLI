# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. The statement key is `com-spdb-upfs-db-codex-review-probe-mapper-find-company-by-id-51337711cfa2`; the
mapper-relative path is `src/test/resources/mapper/CodexReviewProbeMapper.xml`; the namespace is
`com.spdb.upfs.db.CodexReviewProbeMapper`; the statement id is `findCompanyById`; the command type is `select`; the
selectKey flag is `false`; the source line range is 34–40; and the runtime binding is data source `deepseek@localhost`,
catalog `deepseek`, schema `deepseek`, project `/Users/wangyufeng/IdeaProjects/uasp-qz-json`, scope `ALL`. No Database
MCP calls were made during this Agent session because the runtime task context supplied no concrete bounded scenario
query; this is a zero-scenario static SELECT review.

## Statement

The statement is a parameterized single-row lookup against the COMPANY table. It has no dynamic SQL branches (the
`dynamic_nodes` list is empty) and exactly one bind parameter, `#{id,jdbcType=INTEGER}`. The column list is sourced from
the `<include refid="CompanyColumns"/>` fragment.

- Data source: `deepseek@localhost`
- Catalog: `deepseek`
- Schema: `deepseek`
- Project: `/Users/wangyufeng/IdeaProjects/uasp-qz-json`
- Scope: `ALL`
- Safety mode: `connectivity-only`
- Database safety: `unverified`

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

## Static Analysis

- Correctness: The predicate uses a single bound `#{id}` placeholder; there is no string concatenation, so SQL injection
  is not present. Static-confirmed.
- Performance: The WHERE clause filters on the ID column. Whether ID is a primary key or uniquely indexed cannot be
  confirmed statically; if it is not, the lookup would not use a unique-index access path and could match more than one
  row. Requires runtime confirmation.
- Data volume: No LIMIT clause is present. If ID is not unique, multiple rows would be returned and mapped through
  `CompanyResultMap`, which would raise MyBatis `TooManyResultsException`. Requires runtime confirmation.
- Maintainability: The column list is defined once in the `CompanyColumns` include fragment. Drift between that fragment
  and the `CompanyResultMap` columns would surface as mapping errors at runtime. Static observation.
- Concurrency: No shared mutable state or locking is involved; no concurrency risk is visible statically.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- F-01 — severity `low`, category `performance` — No `LIMIT` on the ID lookup; duplicate-ID risk. The statement has no
  row-count bound and ID uniqueness is unverified (`database_safety=unverified`, zero-scenario review). If the ID column
  is not backed by a unique constraint, the resultMap mapping would fail with `TooManyResultsException`. Evidence ids:
  `static-mapper-source`. Status: unverified.
- F-02 — severity `info`, category `maintainability` — Column list sourced from the shared `CompanyColumns` include
  fragment. Drift between the fragment and `CompanyResultMap` would break column mapping. Evidence ids:
  `static-mapper-source`. Status: static observation.

No other findings remain.

## Recommendations

- Confirm that the `COMPANY.ID` column is the primary key or has a unique index, and that a query for a given ID returns
  exactly zero or one row. This is a schema verification outside the static review and does not require executing the
  mapped statement.
- Optionally add `LIMIT 1` to the normalized SQL to harden the query against duplicate IDs.
- Keep the `CompanyColumns` include fragment and `CompanyResultMap` in sync; add a mapping smoke test that asserts all
  mapped columns resolve.
- No change to the mapped SQL is required for injection safety; the bound parameter is already used correctly.

## Limitations

- This is a zero-scenario review: no native Database MCP call was made because the runtime task context supplied no
  concrete bounded scenario query, so `audit.tool_call_ids`, `metadata`, and `scenarios` are empty.
- `safety_mode` is `connectivity-only` and `database_safety` is `unverified`; the connection-level row-security,
  function-execution, and timeout assertions were not verified for this data source.
- No representative rows are available, so column presence, column types, ID uniqueness, and access-path/plan behavior
  for the COMPANY table could not be confirmed.
- Static findings about index usage and uniqueness remain unverified until schema evidence is obtained.
