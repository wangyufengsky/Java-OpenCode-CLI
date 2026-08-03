# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. The reviewed statement is an UPDATE: key
`com-spdb-upfs-db-codex-review-probe-mapper-update-company-profile-6f27385cd921`, mapper-relative path
`src/test/resources/mapper/CodexReviewProbeMapper.xml`, namespace `com.spdb.upfs.db.CodexReviewProbeMapper`, statement
id `updateCompanyProfile`, command type `update`, selectKey flag `false`, source line range 59-79. Because the command
type is `update`, this is a static-review-only statement; no Database MCP query calls were made.

## Statement

The mapper is `CodexReviewProbeMapper.xml` (relative path `src/test/resources/mapper/CodexReviewProbeMapper.xml`).
Statement key `com-spdb-upfs-db-codex-review-probe-mapper-update-company-profile-6f27385cd921`; statement id
`updateCompanyProfile`; command type `update`; selectKey flag `false`; source line range 59-79. Dynamic SQL branches:
`<set>` wrapper containing five conditional `<if>` nodes (one per nullable column). Parameters (six `#{}` placeholders):
`#{companyName,jdbcType=VARCHAR}`, `#{companyState,jdbcType=VARCHAR}`, `#{companyCity,jdbcType=VARCHAR}`,
`#{employee,jdbcType=INTEGER}`, `#{companyCreateYear,jdbcType=INTEGER}`, and the `WHERE` key `#{id,jdbcType=INTEGER}`.

Raw mapper XML:

```xml
<update id="updateCompanyProfile" parameterType="java.util.Map">
        UPDATE COMPANY
        <set>
            <if test="companyName != null">
                COMPANY_NAME = #{companyName,jdbcType=VARCHAR},
            </if>
            <if test="companyState != null">
                COMPANY_STATE = #{companyState,jdbcType=VARCHAR},
            </if>
            <if test="companyCity != null">
                COMPANY_CITY = #{companyCity,jdbcType=VARCHAR},
            </if>
            <if test="employee != null">
                EMPLOYEE = #{employee,jdbcType=INTEGER},
            </if>
            <if test="companyCreateYear != null">
                COMPANY_CREATE_YEAR = #{companyCreateYear,jdbcType=INTEGER},
            </if>
        </set>
        WHERE ID = #{id,jdbcType=INTEGER}
    </update>
```

Normalized SQL (dynamic branches retained as placeholders):

```sql
UPDATE COMPANY
        
            
                COMPANY_NAME = #{companyName,jdbcType=VARCHAR},
            
            
                COMPANY_STATE = #{companyState,jdbcType=VARCHAR},
            
            
                COMPANY_CITY = #{companyCity,jdbcType=VARCHAR},
            
            
                EMPLOYEE = #{employee,jdbcType=INTEGER},
            
            
                COMPANY_CREATE_YEAR = #{companyCreateYear,jdbcType=INTEGER},
            
        
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

Correctness: All bind values use `#{}` prepared parameters, so there is no SQL-injection vector from mapper-side string
concatenation. Maintainability: the column list is explicit and readable; no `ON DUPLICATE KEY`/dialect-specific syntax.
Performance: the `WHERE ID = #{id}` predicate is a primary-key lookup, so the row-update path is expected to be narrow (
requires runtime confirmation). Concurrency and data-volume risks visible from source:

- If all five `<if>` conditions evaluate false, the `<set>` element contributes no assignments and MyBatis trims the
  `SET` keyword, producing the invalid SQL `UPDATE COMPANY WHERE ID = #{id}`; this fails at runtime with a syntax error
  rather than being a harmless no-op. Requires runtime confirmation.
- The statement performs no optimistic-locking (no version column in the `WHERE` clause) and does not maintain an
  `updated_at`/audit timestamp, so concurrent writers can silently overwrite each other (last-write-wins). Requires
  runtime confirmation.
- `employee` and `companyCreateYear` are declared `jdbcType=INTEGER` but are sourced from a `java.util.Map`; non-numeric
  caller values would trigger type-handler conversion errors at runtime. Requires runtime confirmation.
- `id` is not guarded; a null `id` binds `WHERE ID = NULL`, which matches no rows and returns success with zero affected
  rows. Requires runtime confirmation.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- F-1 — severity `medium`, category `dynamic-sql`, title "Empty SET clause when all conditional columns are null". When
  every `<if>` test is false, the generated statement is `UPDATE COMPANY WHERE ID = #{id}` with no `SET` clause, which
  is invalid SQL and throws at execution time. This is a static observation; unverified without runtime execution.
  Evidence ids: none (no Database MCP call was permitted for an update statement).
- F-2 — severity `low`, category `concurrency`, title "No optimistic locking or audit timestamp". Concurrent updates can
  silently overwrite each other because the `WHERE` clause carries only the primary key and no version guard, and no
  `updated_at` column is maintained. Static observation; unverified.
- F-3 — severity `low`, category `type-handling`, title "Untyped Map values for INTEGER columns". `employee` and
  `companyCreateYear` map to INTEGER columns from `java.util.Map` values; non-numeric input causes runtime conversion
  failure. Static observation; unverified.
- F-4 — severity `info`, category `null-handling`, title "Null id silently matches zero rows". `WHERE ID = #{id}` with a
  null id evaluates to `ID = NULL`, matching nothing and reporting success with 0 rows. Static observation; unverified.

All findings are static-review-only claims; none reference Database MCP evidence.

## Recommendations

- Guard the update so the `SET` clause is never empty: validate in the service layer that at least one attribute is
  present, or assert a minimum non-null `<if>` assignment before building the statement. Do not rely on MyBatis `<set>`
  trimming for an all-null payload.
- Add a `VERSION` (or `updated_at`) column and include it in the `WHERE` clause for optimistic concurrency control, and
  maintain an audit timestamp within the `SET` clause.
- Coerce and validate `employee`/`companyCreateYear` to numeric types in the caller before invoking the mapper so
  INTEGER type-handling never receives arbitrary strings.
- Validate that `id` is non-null before executing the update, and treat a zero-row result as an explicit error where
  appropriate.
- None of these recommendations require executing the original update statement.

## Limitations

- `command_type=update` is static-review-only under `connectivity-only` safety mode; the Database MCP query tool is
  forbidden for update statements, so no scenario queries were executed and no native Database MCP tool-call ids exist.
  `audit.tool_call_ids`, `metadata`, and `scenarios` are empty; all findings use empty `evidence_ids`.
- `database_safety=unverified`: connection properties were not re-probed in the Agent session and no relation metadata
  was collected, so table shape, indexes, and the existence of the `COMPANY` table are unconfirmed.
- Without representative rows, cardinality, selectivity, and execution-plan stability of the underlying lookup cannot be
  confirmed; rows returned by any future evidence run would be representative evidence, not proof of production
  behavior.
- Dynamic-SQL branches and the exact SQL text at runtime depend on caller-supplied parameter values, which are not
  observable from static analysis.
