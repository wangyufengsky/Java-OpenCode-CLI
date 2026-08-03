# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. It reviews the mapper statement with key
`com-spdb-upfs-db-codex-review-probe-mapper-insert-company-9f7f553cf938`, mapper-relative path
`src/test/resources/mapper/CodexReviewProbeMapper.xml`, namespace `com.spdb.upfs.db.CodexReviewProbeMapper`, statement
id `insertCompany`, command type `insert`, selectKey flag `false`, source line range 42–57, and runtime binding
data_source `deepseek@localhost`, catalog `deepseek`, schema `deepseek`, project
`/Users/wangyufeng/IdeaProjects/uasp-qz-json`, scope `ALL`.

## Statement

The reviewed statement is `<insert id="insertCompany" parameterType="java.util.Map">` in mapper
`src/test/resources/mapper/CodexReviewProbeMapper.xml` (namespace `com.spdb.upfs.db.CodexReviewProbeMapper`). Command
type is `insert`; selectKey flag is `false`; source lines 42–57. It has no dynamic SQL branches (`dynamic_nodes` is
empty) and binds five parameters: `#{companyName,jdbcType=VARCHAR}`, `#{companyState,jdbcType=VARCHAR}`,
`#{companyCity,jdbcType=VARCHAR}`, `#{employee,jdbcType=INTEGER}`, `#{companyCreateYear,jdbcType=INTEGER}`. It uses
`useGeneratedKeys="true"` with `keyProperty="id"` and `keyColumn="ID"`.

- Data source: `deepseek@localhost`
- Catalog: `deepseek`
- Schema: `deepseek`
- Project: `/Users/wangyufeng/IdeaProjects/uasp-qz-json`
- Scope: `ALL`
- Safety mode: `connectivity-only`
- Database safety: `unverified`

Raw mapper XML:

```xml
<insert id="insertCompany" parameterType="java.util.Map"
            useGeneratedKeys="true" keyProperty="id" keyColumn="ID">
        INSERT INTO COMPANY (
            COMPANY_NAME,
            COMPANY_STATE,
            COMPANY_CITY,
            EMPLOYEE,
            COMPANY_CREATE_YEAR
        ) VALUES (
            #{companyName,jdbcType=VARCHAR},
            #{companyState,jdbcType=VARCHAR},
            #{companyCity,jdbcType=VARCHAR},
            #{employee,jdbcType=INTEGER},
            #{companyCreateYear,jdbcType=INTEGER}
        )
    </insert>
```

Normalized SQL:

```sql
INSERT INTO COMPANY (
            COMPANY_NAME,
            COMPANY_STATE,
            COMPANY_CITY,
            EMPLOYEE,
            COMPANY_CREATE_YEAR
        ) VALUES (
            #{companyName,jdbcType=VARCHAR},
            #{companyState,jdbcType=VARCHAR},
            #{companyCity,jdbcType=VARCHAR},
            #{employee,jdbcType=INTEGER},
            #{companyCreateYear,jdbcType=INTEGER}
        )
```

## Static Analysis

Correctness: The insert targets five columns of COMPANY with five bound placeholders; column/parameter order matches.
`useGeneratedKeys` populates the `id` property only if the JDBC driver implements `getGeneratedKeys` — unconfirmed for
the target engine (requires runtime confirmation).

Maintainability: `parameterType="java.util.Map"` defers key and type validation to runtime; a misspelled placeholder key
would silently bind NULL rather than fail at compile time.

Performance: Each invocation performs one standalone round trip; no batching. Fine for single inserts, suboptimal for
bulk loops.

Concurrency: No optimistic-lock or unique-key conflict handling; concurrent inserts of the same business key can raise
constraint violations (unverified, depends on schema).

Data volume: Insert volume and cardinality are unknown; no indexes, constraints, or table DDL were confirmed at runtime.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

### F-01 (medium, generated-keys)

The statement relies on JDBC `getGeneratedKeys` to populate `id`. On engines/driver combinations without this support (
commonly reported for GaussDB and some PostgreSQL driver versions), `keyProperty="id"` stays null. Unverified: the
actual engine/driver behavior was not confirmed at runtime. No supporting database evidence.

### F-02 (low, type-safety)

`parameterType="java.util.Map"` bypasses compile-time checks; a misspelled key silently binds NULL. No database
evidence.

### F-03 (low, data-integrity)

Required-column completeness and NULL handling for the five bound columns are not guaranteed; schema constraints were
not confirmed. No database evidence.

### F-04 (info, performance)

Single-row insert with no batching; only relevant for bulk loops. No database evidence.

## Recommendations

1. Verify JDBC `getGeneratedKeys` support on the target engine. If unsupported, use
   `<selectKey keyProperty="id" order="AFTER">` selecting the generated key or `INSERT ... RETURNING id`.
2. Replace the Map parameter with a typed DTO and add validation so required columns are never bound as NULL.
3. Inspect the COMPANY DDL to confirm constraints and add conflict/duplicate handling if the business key is unique.
4. Batch inserts for bulk workloads via the MyBatis BATCH executor or `<foreach>` in bounded batches.
5. Before promotion, run an ad-hoc probe on a test schema (not the mapped statement) to confirm generated-key behavior.

## Limitations

- This is a static-review-only `<insert>`; no Database MCP query was executed and no scenario was run (`scenarios`
  empty). All runtime claims about engine support, driver behavior, schema, and constraints are unverified.
- No Database MCP tool call succeeded in this session, so `audit.tool_call_ids`, `metadata`, and `scenarios` are empty
  and every finding uses an empty `evidence_ids` array; static observations are described in prose only.
- `safety_mode=connectivity-only` and `database_safety=unverified`: connection, permissions, row-security, and timeout
  behavior were not verified here.
- Any future returned-row evidence would be representative, not proof of production cardinality, selectivity, or plan
  stability.

