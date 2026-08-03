# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. The reviewed statement is `updateCompanyProfile` in
`src/test/resources/mapper/CodexReviewProbeMapper.xml` (namespace `com.spdb.upfs.db.CodexReviewProbeMapper`), statement
key `com-spdb-upfs-db-codex-review-probe-mapper-update-company-profile-6f27385cd921`, command type `update`, selectKey
flag `false`, source line range 59-79. Runtime binding: data_source `deepseek@localhost`, catalog `deepseek`, schema
`deepseek`, project `/Users/wangyufeng/IdeaProjects/uasp-qz-json`, scope `ALL`, safety_mode `connectivity-only`,
database_safety `unverified`.

## Statement

- Mapper: `src/test/resources/mapper/CodexReviewProbeMapper.xml`
- Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-update-company-profile-6f27385cd921`
- Namespace: `com.spdb.upfs.db.CodexReviewProbeMapper`
- Statement id: `updateCompanyProfile`
- Command type: `update`
- SelectKey flag: `false`
- Source line range: 59-79
- Dynamic SQL branches: `<set>` wrapper containing five `<if>` guards for `companyName`, `companyState`, `companyCity`,
  `employee`, `companyCreateYear`; `WHERE ID = #{id}` is static
- Parameters: `#{companyName,jdbcType=VARCHAR}`, `#{companyState,jdbcType=VARCHAR}`, `#{companyCity,jdbcType=VARCHAR}`,
  `#{employee,jdbcType=INTEGER}`, `#{companyCreateYear,jdbcType=INTEGER}`, `#{id,jdbcType=INTEGER}`
- Raw mapper XML:

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

- Normalized SQL:

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

Correctness: the `<set>` element strips the trailing comma so a non-empty guard set renders valid assignment clauses.
When every guarded column is null the `<set>` body is empty and the statement degrades to `UPDATE COMPANY WHERE ID = ?`,
which is invalid SQL on typical engines; the failure is safe (no rows are touched) but uncontrolled. `#{id}` is the only
row filter, so the statement is a point update on the primary key.

Maintainability: the guarded-column pattern is idiomatic MyBatis dynamic update and is easy to extend. Using
`parameterType="java.util.Map"` removes compile-time parameter shape validation; a typo in a map key silently leaves the
column unchanged (requires runtime confirmation of call sites).

Performance: filtering solely on `ID` is expected to use the primary key. Re-assigning already-identical values still
emits writes; this is typically negligible and unconfirmed without runtime plans.

Concurrency: no version or optimistic-locking condition is present; two transactions that read the same row and update
different fields can overwrite each other (lost update). This requires runtime confirmation of the actual concurrency
patterns.

Data volume: a single-row `UPDATE ... WHERE ID = ?` is bounded by the key lookup; no cross-row or unbounded scan is
present in the source.

All performance and concurrency claims above are static observations that require runtime confirmation; no Database MCP
query was executed because the reviewed statement is an `<update>` and is static-review-only.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- **F-1 — low / concurrency — No optimistic locking on COMPANY updates.** Confirmed statically: the `WHERE` clause
  filters only on `ID` and the guarded columns are overwritten unconditionally. Concurrent transactions reading then
  updating the same row can lose one another's changes. Unverified at runtime: no execution was permitted, and the
  application's concurrency patterns are not visible from the mapper. `evidence_ids: []`.
- **F-2 — info / correctness — Empty dynamic SET produces invalid SQL.** Confirmed statically: when all five guarded
  columns are null, `<set>` renders an empty body and MyBatis emits `UPDATE COMPANY WHERE ID = ?`, which fails on most
  engines rather than updating all rows. The behavior is safe but uncontrolled. Unverified: the runtime outcome was not
  executed or confirmed. `evidence_ids: []`.

No finding requires executing the original DML, and no Database MCP evidence was gathered for this static-review-only
statement.

## Recommendations

- Add optimistic locking: include a `VERSION` column in the `SET` clause (incremented) and in the `WHERE` clause (
  `AND VERSION = #{expectedVersion}`), and inspect the affected-row count to detect stale updates.
- Guard the empty-update case explicitly: wrap the update in a `<choose>`/`<when>` whose test is the OR of all five null
  checks and otherwise skip the statement, or fail fast with a clear message when the parameter map contains no update
  column.
- Validate the parameter map keys at the call site (typed DTO/record rather than `Map`) to catch key typos that silently
  leave columns unchanged.
- Confirm via runtime data that updates re-writing unchanged values are acceptable, or compare current values before
  writing.

## Limitations

- The statement is static-review-only (`update`, `selectKey=false`); no Database MCP query was run and the original DML
  was not executed, so cardinality, selectivity, index usage, and plan behavior are unconfirmed.
- No bounded scenario SQL was supplied in the runtime task context; `metadata` and `scenarios` are empty and
  `tool_call_ids` is empty.
- `safety_mode` is `connectivity-only` (test environment) and `database_safety` is `unverified`; row-security,
  function-execution, and timeout assertions were not re-verified in this session.
- Representative rows are absent, so the finding severities reflect static analysis only; runtime confirmation could
  raise or lower them.

