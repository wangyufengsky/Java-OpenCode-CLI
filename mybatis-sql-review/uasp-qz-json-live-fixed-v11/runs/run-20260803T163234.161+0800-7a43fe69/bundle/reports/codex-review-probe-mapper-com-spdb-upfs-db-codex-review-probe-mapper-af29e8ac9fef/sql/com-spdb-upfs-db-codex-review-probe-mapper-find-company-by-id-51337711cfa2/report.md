# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. Statement: `findCompanyById` in `CodexReviewProbeMapper.xml`; mapper-relative path
`src/test/resources/mapper/CodexReviewProbeMapper.xml`; namespace `com.spdb.upfs.db.CodexReviewProbeMapper`; statement
id `findCompanyById`; command type `select`; selectKey flag `false`; source lines 34–40; runtime binding
`data_source=deepseek@localhost`, `catalog=deepseek`, `schema=deepseek`,
`project=/Users/wangyufeng/IdeaProjects/uasp-qz-json`, `scope=ALL`.

## Statement

Mapper: `CodexReviewProbeMapper.xml`; namespace `com.spdb.upfs.db.CodexReviewProbeMapper`; statement key
`com-spdb-upfs-db-codex-review-probe-mapper-find-company-by-id-51337711cfa2`; command type `select`; selectKey flag
`false`; source lines 34–40; dynamic SQL branches: none (`dynamic_nodes` empty); parameters: `#{id,jdbcType=INTEGER}`.
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

The statement is a parameterized equality lookup on `COMPANY.ID` using the placeholder `#{id,jdbcType=INTEGER}`, so
there is no string-concatenation injection surface. The `SELECT` list is pulled from the
`<include refid="CompanyColumns"/>` fragment and mapped through `resultMap="CompanyResultMap"`; neither definition is
visible in this candidate's raw XML, so column-to-property mapping and fragment resolution cannot be confirmed
statically (requires runtime confirmation). Filtering on the primary key `ID` with a bound parameter is expected to be
index-friendly; no pagination/`LIMIT` or ordering is present, which is acceptable for a single-row lookup by unique key.
Concurrency and data-volume risks are minimal for this shape. `database_safety=unverified` — no connection or query was
executed in the Agent session.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

| ID     | Severity | Category      | Summary                                                                                                                                                                               | Evidence |
|--------|----------|---------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| FIND-1 | info     | static-review | `<include refid="CompanyColumns"/>` and `resultMap="CompanyResultMap"` are referenced but not defined in the reviewed source; column selection and mapping are unverified statically. | —        |

No correctness, performance, or safety finding remains for the parameterized PK lookup itself; the only note is the
unverified include/resultMap dependency (FIND-1, unverified statically).

## Recommendations

1. Confirm that `CompanyColumns` and `CompanyResultMap` are defined elsewhere in the mapper file (or a shared `sql`/
   `resultMap` import) and that their columns match `COMPANY`; no code change is required if they resolve.
2. Keep the parameterized `WHERE ID = #{id,...}` predicate unchanged; do not substitute string concatenation.
3. No index, partitioning, or `LIMIT` change is warranted from this static review; verify the unique index on
   `COMPANY.ID` if large-table latency is later observed.

## Limitations

No scenario query was supplied in the runtime context, so zero Database MCP calls were made; `audit.tool_call_ids`,
`metadata`, and `scenarios` are empty. `safety_mode=connectivity-only` and `database_safety=unverified` mean connection
and row-security properties were not re-verified. No representative rows or plan/cardinality evidence exist.
Include-fragment and resultMap definitions are outside this candidate's raw XML.

