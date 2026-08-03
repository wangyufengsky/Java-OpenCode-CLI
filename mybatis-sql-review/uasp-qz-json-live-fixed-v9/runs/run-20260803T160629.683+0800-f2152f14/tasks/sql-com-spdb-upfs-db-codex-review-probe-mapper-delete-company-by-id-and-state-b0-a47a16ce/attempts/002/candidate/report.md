# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database MCP evidence. It states the exact statement key `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`, mapper-relative path `src/test/resources/mapper/CodexReviewProbeMapper.xml`, namespace `com.spdb.upfs.db.CodexReviewProbeMapper`, statement id `deleteCompanyByIdAndState`, command type `delete`, selectKey flag `false`, source line range 81-85, and runtime `data_source` `deepseek@localhost`, `catalog` `deepseek`, `schema` `deepseek`, `project` `/Users/wangyufeng/IdeaProjects/uasp-qz-json`, and `scope` `ALL`.

## Statement

The reviewed statement is a static-review-only DELETE. It has no dynamic SQL branches, binds two parameters, and targets the `COMPANY` table.

- Mapper: `src/test/resources/mapper/CodexReviewProbeMapper.xml` (namespace `com.spdb.upfs.db.CodexReviewProbeMapper`)
- Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`
- Statement id: `deleteCompanyByIdAndState`
- Command type: `delete`
- selectKey flag: `false`
- Source line range: 81-85
- Dynamic SQL branches: none (`dynamic_nodes` is empty)
- Parameters: `#{id,jdbcType=INTEGER}`, `#{companyState,jdbcType=VARCHAR}`

Raw mapper XML:
```xml
<delete id="deleteCompanyByIdAndState" parameterType="java.util.Map">
        DELETE FROM COMPANY
        WHERE ID = #{id,jdbcType=INTEGER}
          AND COMPANY_STATE = #{companyState,jdbcType=VARCHAR}
    </delete>
```

Normalized SQL:
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

Correctness: The DELETE is guarded by the primary-key predicate `ID` plus a `COMPANY_STATE` equality predicate, so it can affect at most one row. Both bind parameters use explicit `jdbcType`, avoiding JDBC type-inference ambiguity. The `parameterType="java.util.Map"` binding means key names (`id`, `companyState`) must match the mapper call exactly; a key typo is not caught at compile time and would produce a binding error only at runtime.

Maintainability: The `java.util.Map` parameter leaves the statement contract implicit. A typed POJO or `@Param`-annotated method signature would surface key names at compile time. No SQL comments or index hints are present; none are required for this simple statement.

Performance: The `ID` primary-key lookup should resolve via the primary-key index and delete at most one row; no full-table scan is expected. This claim requires runtime confirmation and is unverified here.

Concurrency: The statement has no optimistic-lock column (no `VERSION`/`UPDATE_TIME` predicate) in the `WHERE` clause. Between a prior read and this delete, a concurrent update can modify the row while leaving `COMPANY_STATE` unchanged, so the delete removes a row whose post-update contents were never observed by the caller (lost-update window). Conversely, if `COMPANY_STATE` changed concurrently, the delete affects zero rows, which the caller silently misses unless it checks the affected-row count. These are static observations and unverified at runtime.

Data volume: Bounded to at most one row by the `ID` primary-key predicate. No batch delete is performed, so there is no large-deletion or lock-contention concern from this statement alone.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- **F1** — severity `low`, category `concurrency`. No optimistic concurrency guard on the state-scoped delete: the row can be removed based on a stale snapshot, or the delete can affect zero rows when `COMPANY_STATE` changed concurrently. Static observation only; **unverified** at runtime. Supporting evidence ids: none (empty `evidence_ids`; no native Database MCP call was made).
- **F2** — severity `info`, category `maintainability`. Transaction and affected-row handling are delegated to the caller: the mapper XML declares no transaction semantics, and a caller ignoring the returned count can silently assume deletion succeeded even when the state guard prevented it. Static observation only; **unverified** at runtime. Supporting evidence ids: none (empty `evidence_ids`).

No finding remains that requires executing the original DML; both findings above are actionable without executing the statement.

## Recommendations

- Have the mapper method return the affected-row count (`int`) and treat `rows == 0` as a "row not found or state changed" signal so callers do not silently assume deletion succeeded.
- Ensure the caller owns a single transaction boundary around this delete and any related writes.
- If concurrent state changes are possible, add an optimistic-lock predicate (e.g., `AND UPDATE_TIME = #{updateTime,...}` or a `VERSION` column) to the `WHERE` clause.
- Replace `parameterType="java.util.Map"` with a typed parameter object or `@Param`-annotated method arguments so binding keys are verified at compile time.
- None of these recommendations require executing the original DELETE or selectKey statement.

## Limitations

- This is a static-review-only DELETE statement; the Database MCP query tool is forbidden for it, so no native Database MCP call was made and no runtime evidence, `tool_call_ids`, `metadata`, or `scenarios` exist. Representative-row evidence is therefore unavailable.
- `safety_mode=connectivity-only` and `database_safety=unverified`: connection, row-security, function-execution, and timeout assertions were not re-verified in this Agent session; the Java preflight `SELECT 1` probe was not repeated.
- Schema semantics are unverified at runtime: whether `ID` is the primary key of `COMPANY`, the allowed values of `COMPANY_STATE`, and actual row cardinality/selectivity are not confirmed.
- Cardinality, plan stability, and concurrency behavior are unverified and require runtime confirmation outside this static review.
