# SQL Review

This candidate report reviews exactly one inventory statement — `insertCompany` — and distinguishes static observations
from native Database MCP evidence. It is a static-review-only statement, so no Database MCP call was made.

- Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-insert-company-9f7f553cf938`
- Mapper-relative path: `src/test/resources/mapper/CodexReviewProbeMapper.xml`
- Namespace: `com.spdb.upfs.db.CodexReviewProbeMapper`
- Statement id: `insertCompany`
- Command type: `insert`
- SelectKey flag: `false`
- Source line range: 42–57

## Statement

Mapper `com.spdb.upfs.db.CodexReviewProbeMapper`, statement key
`com-spdb-upfs-db-codex-review-probe-mapper-insert-company-9f7f553cf938`, command type `insert`, selectKey flag `false`,
source lines 42–57. No dynamic SQL branches (`dynamic_nodes` is empty). Five parameters are bound via `#{...}`
placeholders against a single `COMPANY` table insert. Raw mapper XML and normalized SQL:

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

- Data source: `deepseek@localhost`
- Catalog: `deepseek`
- Schema: `deepseek`
- Project: `/Users/wangyufeng/IdeaProjects/uasp-qz-json`
- Scope: `ALL`
- Safety mode: `connectivity-only`
- Database safety: `unverified`

## Static Analysis

- Correctness: The five bound columns map one-to-one to parameters, each with an explicit `jdbcType`. Because
  `parameterType="java.util.Map"`, a missing key binds NULL instead of failing at compile time; this is a
  runtime-dependent behavior that requires confirmation.
- Generated keys: `useGeneratedKeys="true"` with `keyProperty="id"` and `keyColumn="ID"` depends on the JDBC
  driver/engine supporting `getGeneratedKeys`. Unverified for this engine.
- Maintainability: No dynamic nodes; a plain, readable single-row insert. All values are inlined; no column defaults are
  used.
- Performance and data volume: Single-row insert of five small columns; negligible intrinsic volume risk. If the mapper
  is called in a loop, batching would be preferable; call-site volume is unverified.
- Concurrency: No explicit locking or conflict handling; depends on the surrounding transaction and any DB unique
  constraints (e.g., on `COMPANY_NAME`). Unverified without schema metadata.
- Schema-dependent claims (column nullability, type compatibility, defaults, triggers) are unverified.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- **F1 — info — database-metadata** — COMPANY table constraints not verified: the nullability, type, default, and
  constraint behavior of the five target columns was not confirmed because no Database MCP call was made for this
  static-review-only insert. Confirmed: the statement is a static insert with no dynamic branches. Unverified: all
  schema-dependent claims.
- **F2 — low — data-integrity** — NULL binding for non-nullable columns possible: absent keys in the parameter `Map`
  bind NULL; if any of `COMPANY_NAME`, `COMPANY_STATE`, `COMPANY_CITY`, `EMPLOYEE`, or `COMPANY_CREATE_YEAR` is NOT
  NULL, the insert fails at runtime. Unverified against schema.
- **F3 — info — generated-keys** — Generated-key retrieval depends on driver support: `getGeneratedKeys` behavior for
  the target engine is unverified.

## Recommendations

- Confirm the COMPANY DDL (NOT NULL, defaults, unique constraints, column types) matches the five bound parameters
  before production use.
- Add explicit presence checks for the required map keys (`companyName`, `companyState`, `companyCity`, `employee`,
  `companyCreateYear`) before invoking the mapper.
- If the engine/driver does not reliably support `getGeneratedKeys`, use an explicit RETURNING/OUTPUT strategy and bind
  the generated id directly.
- Do not execute the original insert as part of review; validate against schema metadata once connectivity is verified.

## Limitations

- Static-review-only statement: no Database MCP query or metadata call was made; `audit.tool_call_ids`, `metadata`, and
  `scenarios` are empty by contract.
- `safety_mode=connectivity-only` and `database_safety=unverified`; connection, row-security, function-execution, and
  timeout properties were not probed in this agent session.
- No representative rows, cardinality, selectivity, or plan stability data were observed.
- Column nullability, constraints, triggers, and type compatibility for COMPANY are unverified.
