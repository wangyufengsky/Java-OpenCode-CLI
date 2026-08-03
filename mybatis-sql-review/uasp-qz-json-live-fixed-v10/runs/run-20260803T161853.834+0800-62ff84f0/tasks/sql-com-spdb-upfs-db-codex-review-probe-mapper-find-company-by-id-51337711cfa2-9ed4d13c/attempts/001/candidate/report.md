# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. State its exact statement key, mapper-relative path, namespace, statement id, command type, selectKey
flag, source line range, and runtime `data_source`, `catalog`, `schema`, `project`, and `scope` binding.

The reviewed statement is `findCompanyById` (statement key
`com-spdb-upfs-db-codex-review-probe-mapper-find-company-by-id-51337711cfa2`) of mapper
`com.spdb.upfs.db.CodexReviewProbeMapper` at mapper-relative path
`src/test/resources/mapper/CodexReviewProbeMapper.xml`. Command type is `select`, selectKey flag is `false`, and source
line range is 34–40. Runtime database binding: `data_source=deepseek@localhost`, `catalog=deepseek`, `schema=deepseek`,
`project=/Users/wangyufeng/IdeaProjects/uasp-qz-json`, `scope=ALL`.

## Statement

Identify the mapper, statement key, command type, selectKey flag, source line range, dynamic SQL branches, parameters,
raw mapper XML, and normalized SQL being reviewed.

- Mapper: `com.spdb.upfs.db.CodexReviewProbeMapper` (`src/test/resources/mapper/CodexReviewProbeMapper.xml`)
- Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-find-company-by-id-51337711cfa2`
- Statement id: `findCompanyById`
- Command type: `select`
- SelectKey flag: `false`
- Source line range: 34–40
- Dynamic SQL branches: none (`dynamic_nodes` is empty)
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
SELECT ID,
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

The statement is a single-table point lookup by `ID` with a bound parameter; there is no dynamic SQL, no string
concatenation, and no user-controlled fragment, so no SQL-injection risk is visible. The projection is centralized in a
reusable `<include refid="CompanyColumns"/>`, which keeps the column list consistent across the mapper.

Query quality depends on `COMPANY.ID` being a primary key (or at least unique-indexed). The equality predicate and the
`CompanyResultMap` mapping are consistent with a one-row result, so no `LIMIT` is required in that case. Whether `ID` is
actually unique/PK and whether the planner uses an index on `ID` are runtime facts that cannot be confirmed statically (
`safety_mode=connectivity-only`, `database_safety=unverified`). If `ID` were unindexed and the table large, the equality
scan could degrade to a full-table scan, but this remains an unverified risk here. No concurrency concern is visible for
this read-only point lookup.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

No finding remains for this statement. Static analysis surfaced only unverified runtime assumptions (primary-key
uniqueness and index usage on `COMPANY.ID`), which are recorded as limitations rather than confirmed defects. Because no
Database MCP call was made in this session, `database-evidence.json` uses empty `audit.tool_call_ids`, `metadata`, and
`scenarios` arrays, and no finding carries an evidence id.

## Recommendations

- Confirm `COMPANY.ID` is a primary key or unique-indexed column; if not, index it so the point lookup retains
  constant-time behavior at production volume.
- Keep the bound-parameter style; it is already injection-safe.
- No action requires executing the original SELECT or any DML/selectKey statement.

## Limitations

- Zero Agent-session Database MCP calls were made; no representative rows, cardinality, selectivity, or plan evidence
  was collected, so all runtime behavior remains unverified.
- `safety_mode=connectivity-only` and `database_safety=unverified` per Java preflight; the Agent session did not re-run
  any connection probe.
- The primary-key and row-uniqueness assumptions on `COMPANY.ID` are static observations only and were not validated
  against runtime schema metadata.
