# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence.

- Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-update-company-profile-6f27385cd921`
- Mapper-relative path: `src/test/resources/mapper/CodexReviewProbeMapper.xml`
- Namespace: `com.spdb.upfs.db.CodexReviewProbeMapper`
- Statement id: `updateCompanyProfile`
- Command type: `update`
- selectKey flag: `false`
- Source line range: 59–79

## Statement

The reviewed statement is a dynamic `UPDATE` in the `CodexReviewProbeMapper` namespace. It updates the `COMPANY` table,
assigning only the non-null columns supplied in the caller's `Map` parameter through a `<set>` block of five `<if>`
branches, and targets the row identified by `ID = #{id}`.

- Data source: `deepseek@localhost`
- Catalog: `deepseek`
- Schema: `deepseek`
- Project: `/Users/wangyufeng/IdeaProjects/uasp-qz-json`
- Scope: `ALL`
- Safety mode: `connectivity-only`
- Database safety: `unverified`

Dynamic SQL branches: `set`, `if` (companyName), `if` (companyState), `if` (companyCity), `if` (employee), `if` (
companyCreateYear).

Parameters: `#{companyName,jdbcType=VARCHAR}`, `#{companyState,jdbcType=VARCHAR}`, `#{companyCity,jdbcType=VARCHAR}`,
`#{employee,jdbcType=INTEGER}`, `#{companyCreateYear,jdbcType=INTEGER}`, `#{id,jdbcType=INTEGER}`.

### Raw mapper XML

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

### Normalized SQL

```sql
UPDATE COMPANY
        
            
                COMPANY_NAME = #{companyName,jdbcType=VARCHAR},
            
            
                COMPANY_STATE = #{companyState,jdbcType=VARCHAR},
            
            
                COMPANY_CITY = #{companyCity,jdbcType=VARCHAR},
            
            
                EMPLOYEE = #{employee,jdbcType=INTEGER},
            
            
                COMPANY_CREATE_YEAR = #{companyCreateYear,jdbcType=INTEGER},
            
        
        WHERE ID = #{id,jdbcType=INTEGER}
```

## Static Analysis

- Every value is bound through a parameterized `#{...}` placeholder with an explicit `jdbcType`, and all `<if>` guards
  are `!= null`; no SQL-injection surface is visible from the mapper source.
- Empty-`<set>` edge case: if every `<if>` guard evaluates false, MyBatis emits `UPDATE COMPANY WHERE ID = ?` with no
  `SET` assignments. Whether the target DBMS treats that as a no-op or as a syntax error requires runtime confirmation
  and is not verified here.
- Concurrency: the `WHERE` clause contains only `ID`; there is no version or timestamp predicate, so concurrent updates
  last-write-wins with no lost-update detection.
- Correctness: if the caller passes a null `id`, `ID = NULL` matches no rows under standard SQL semantics and the
  statement silently affects zero rows.
- The `WHERE` predicate references the primary key (`ID`), so row-at-a-time access is expected; cardinality and plan
  behavior cannot be confirmed without runtime evidence.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- F-01 (medium, robustness/dynamic-SQL): the empty-`<set>` path produces `UPDATE COMPANY WHERE ID = ?` when all guards
  are false; behavior is DBMS-dependent. Confirmed? No — static observation only.
- F-02 (low, concurrency): no optimistic-lock guard, so lost updates are undetected. Confirmed? No — static observation
  only.
- F-03 (low, correctness): a null `id` yields a silent no-op (`ID = NULL` matches nothing). Confirmed? No — static
  observation only.
- F-04 (info, maintainability): no `updated_at`/`update_time` column is maintained in the assignment list; hard-coded
  column drift is possible. Confirmed? No — static observation only.

## Recommendations

- Guard the mapper call so at least one updatable column is non-null, or handle the empty-`<set>` case explicitly so the
  emitted SQL always carries a `SET` clause.
- Add an optimistic-lock check (version column or `update_time`) to the `WHERE` clause and surface the affected-row
  count to the caller.
- Validate that `id` is non-null before invocation and treat a zero affected-row result as an error condition.
- Derive the assignment list from the mapped entity/columns rather than a hard-coded list to avoid schema drift.

## Limitations

- No Database MCP calls were made in the Agent session: the statement is an `UPDATE`, which is static-review-only, and
  the query tool is forbidden; the Java preflight connection probe was not repeated.
- `safety_mode` is `connectivity-only` and `database_safety` is `unverified`, so no row-security, function-execution, or
  timeout properties were verified for `deepseek@localhost`.
- All findings are static observations; representative rows, cardinality, selectivity, and plan stability were not
  confirmed against a live database.
