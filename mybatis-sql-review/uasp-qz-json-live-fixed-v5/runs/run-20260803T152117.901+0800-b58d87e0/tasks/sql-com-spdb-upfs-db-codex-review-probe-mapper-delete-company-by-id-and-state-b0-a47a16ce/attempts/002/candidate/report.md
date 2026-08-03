# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. The reviewed statement is `deleteCompanyByIdAndState`, mapper-relative path
`src/test/resources/mapper/CodexReviewProbeMapper.xml`, namespace `com.spdb.upfs.db.CodexReviewProbeMapper`, statement
id `deleteCompanyByIdAndState`, command type `delete`, selectKey flag `false`, source line range 81–85, with runtime
binding `data_source=deepseek@localhost`, `catalog=deepseek`, `schema=deepseek`,
`project=/Users/wangyufeng/IdeaProjects/uasp-qz-json`, and `scope=ALL`.

## Statement

The mapper `CodexReviewProbeMapper.xml` declares one `<delete>` statement (statement key
`com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`) that removes a single company
row filtered by its primary key and lifecycle state.

- Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`
- Mapper: `CodexReviewProbeMapper.xml`
- Command type: `delete`
- SelectKey flag: `false`
- Source line range: 81–85
- Dynamic SQL branches: none (`dynamic_nodes` is empty)
- Parameters: `#{id,jdbcType=INTEGER}`, `#{companyState,jdbcType=VARCHAR}` (map parameter,
  `parameterType="java.util.Map"`)
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

Correctness: the statement targets the `COMPANY` table and filters on `ID` and `COMPANY_STATE`. Both columns exist in
the target catalog (confirmed via metadata); `ID` is `INT NOT NULL` with a primary index, matching `jdbcType=INTEGER`,
and `COMPANY_STATE` is `VARCHAR(255) NULL`, matching `jdbcType=VARCHAR`. No dynamic SQL is used, so no injected
fragments are possible.

Null-handling risk: because `COMPANY_STATE` is nullable, passing a null `companyState` value would produce
`WHERE COMPANY_STATE = NULL`, which per SQL three-valued logic matches no rows — the delete would silently remove
nothing. This is safe (no unintended deletion) but can mask caller bugs. Conversely, a missing `id` map key would raise
an exception at execution, which is the safer failure mode. Both behaviors require runtime confirmation.

Performance: the equality predicate on `ID` resolves through the primary key index, so the delete is row-targeted; the
additional `COMPANY_STATE` predicate filters within the same row. Runtime cardinality of the statement was not
measured (no query executed).

Concurrency: the statement has no optimistic-locking or version guard; concurrent deletes/updates on the same row are
last-writer-wins at the row level. Whether the mapper executes within a transaction (and whether the caller expects a
logical delete via state transition instead) is not visible from this statement in isolation.

Data-volume risk: the mapper is a code-review probe fixture under `src/test/resources`; production impact is low unless
the same SQL shape is reused.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

| ID    | Severity | Category      | Status              | Evidence                                              |
|-------|----------|---------------|---------------------|-------------------------------------------------------|
| F-001 | info     | metadata      | confirmed           | meta-datasources, meta-databases, meta-company-schema |
| F-002 | low      | null-handling | unverified (static) | meta-company-schema                                   |
| F-003 | info     | index         | confirmed           | meta-company-schema-columns                           |

- **F-001 (info, metadata, confirmed):** Data source `deepseek@localhost` (MySQL 9.2.0,
  `jdbc:mysql://localhost:3306/deepseek`) is configured; catalog `deepseek` exists; `COMPANY` table exists with 6
  columns.
- **F-002 (low, null-handling, unverified):** `COMPANY_STATE` is nullable; a null `companyState` parameter would match
  zero rows (`COMPANY_STATE = NULL` is never true). Callers should pass a non-null state value or the mapper should
  reject nulls. Behavior at runtime was not executed.
- **F-003 (info, index, confirmed):** `COMPANY` has a `PRIMARY` unique index on `ID`, so the `ID` equality predicate is
  index-resolved; the delete is row-targeted.

No finding of medium or higher severity remains for this statement.

## Recommendations

1. Pass a non-null `companyState` from callers, or add a guard (e.g., `AND COMPANY_STATE IS NOT NULL` semantics
   documented / a validation before invocation) so a null parameter cannot silently no-op the delete.
2. Confirm the intended semantics: if the requirement is a logical delete, prefer an
   `UPDATE ... SET COMPANY_STATE = ...` transition rather than a physical `DELETE`; if physical delete is intended, keep
   it scoped to `ID` + state as written.
3. Verify execution runs inside a transaction with the expected isolation so the delete is atomic with any dependent
   state change.
4. No changes to the SQL text itself are required on the basis of the review; the two-predicate form is well-indexed.

## Limitations

- `safety_mode=connectivity-only` and `database_safety=unverified`: no row-security, function-execution, or timeout
  properties were asserted or verified.
- This is a `delete` statement, so per the contract no query was executed; no scenario evidence exists and runtime
  cardinality, selectivity, and plan stability are unverified.
- Metadata rows describe schema shape only (representative of the catalog at review time), not production data volume or
  live plan behavior.
