# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database MCP evidence. It reviews statement key `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`, mapper-relative path `src/test/resources/mapper/CodexReviewProbeMapper.xml`, namespace `com.spdb.upfs.db.CodexReviewProbeMapper`, statement id `deleteCompanyByIdAndState`, command type `delete`, selectKey flag `false`, source line range 81–85, and runtime binding `data_source=deepseek@localhost`, `catalog=deepseek`, `schema=deepseek`, `project=/Users/wangyufeng/IdeaProjects/uasp-qz-json`, `scope=ALL`.

## Statement

The statement is a single-table `delete` command with no `<selectKey>`, defined over source lines 81–85 of `src/test/resources/mapper/CodexReviewProbeMapper.xml`. It is fully static: `dynamic_nodes` is empty, so there are no `<if>`, `<foreach>`, or other dynamic SQL branches. It binds exactly two parameters, `#{id,jdbcType=INTEGER}` and `#{companyState,jdbcType=VARCHAR}`, from a `java.util.Map` parameter object.

- Mapper: `src/test/resources/mapper/CodexReviewProbeMapper.xml` (namespace `com.spdb.upfs.db.CodexReviewProbeMapper`)
- Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`
- Statement id: `deleteCompanyByIdAndState`
- Command type: `delete`
- selectKey: `false`
- Source line range: 81–85
- Data source: `deepseek@localhost`
- Catalog: `deepseek`
- Schema: `deepseek`
- Project: `/Users/wangyufeng/IdeaProjects/uasp-qz-json`
- Scope: `ALL`
- Safety mode: `connectivity-only`
- Database safety: `unverified`

### Raw mapper XML

```xml
<delete id="deleteCompanyByIdAndState" parameterType="java.util.Map">
        DELETE FROM COMPANY
        WHERE ID = #{id,jdbcType=INTEGER}
          AND COMPANY_STATE = #{companyState,jdbcType=VARCHAR}
    </delete>
```

### Normalized SQL

```sql
DELETE FROM COMPANY
        WHERE ID = #{id,jdbcType=INTEGER}
          AND COMPANY_STATE = #{companyState,jdbcType=VARCHAR}
```

### Parameters

- `#{id,jdbcType=INTEGER}` — company identifier taken from the `Map` parameter.
- `#{companyState,jdbcType=VARCHAR}` — expected current company state guarding the delete.

## Static Analysis

- **Correctness / safety guard**: the `COMPANY_STATE` predicate acts as an optimistic concurrency guard, preventing deletion of a company whose state differs from the caller's expectation. This is a sound pattern for state-conditional deletion.
- **Affected-set bound**: the delete is keyed by `ID`. Assuming `COMPANY.ID` is the primary key, at most one row can match on `ID` regardless of state, so the operation is inherently bounded. This assumption requires runtime confirmation (unverified in this session).
- **Missing `LIMIT`**: the statement omits an explicit `LIMIT` clause. The primary-key predicate bounds the row set today, but an explicit limit would be defense-in-depth against multi-row deletion if the key semantics or the predicate are later refactored.
- **Concurrency**: between the caller reading the company state and the delete executing, a concurrent transaction may change `COMPANY_STATE`; the statement then affects zero rows. This is safe but silent — a race is only detected if the caller checks the affected-row count.
- **Data-volume risk**: low. The predicate set is bounded by the primary key; there is no unbounded scan or full-table sweep in the normalized SQL.
- **Performance**: the primary-key predicate can use the primary-key index; the additional `COMPANY_STATE` filter is applied per candidate row. Index existence and the execution plan could not be confirmed at runtime in this session.
- **Maintainability**: clean, single-purpose mapper statement with explicit `jdbcType` hints on both parameters; no duplication or dynamic branching.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- **F1 (low, data-volume/safety)**: DELETE without an explicit `LIMIT` bound. Confirmed statically that the SQL contains no `LIMIT`; the practical bound relies on the unconfirmed assumption that `COMPANY.ID` is unique. Evidence: none — this is a static-review-only `<delete>` statement and no Database MCP query was executed. Recommendation: add `LIMIT 1` or assert the affected-row count equals one.
- **F2 (info, concurrency)**: the state-guard race is silent without an affected-row check. The `COMPANY_STATE` WHERE predicate is confirmed statically; runtime behavior under concurrent state changes is unverified. Recommendation: surface zero affected rows to the caller as a conflict.
- **F3 (info, performance)**: index availability and the execution plan for the `ID + COMPANY_STATE` predicate cannot be confirmed under `safety_mode=connectivity-only` with `database_safety=unverified`. Recommendation: confirm `COMPANY.ID` is the primary key at runtime; add a composite index only if the filtered plan regresses.

## Recommendations

1. Add `LIMIT 1` to the single-table DELETE (supported on MySQL-compatible engines) to defensively bound the affected set, or verify `COMPANY.ID` is the primary key and rely on that invariant together with an affected-row assertion.
2. Check the affected-row count after execution and treat zero affected rows as a concurrent-state conflict rather than silently succeeding.
3. Verify at runtime (in a future privileged session) that `COMPANY.ID` is the primary key and that the execution plan uses an index access path; consider a `(ID, COMPANY_STATE)` composite index only if the filtered plan regresses.

## Limitations

- This is a static-review-only `<delete>` statement: the Database MCP query tool was not called (including connection probes), so `audit.tool_call_ids`, `metadata`, and `scenarios` are empty and no finding carries evidence ids.
- `safety_mode=connectivity-only` and `database_safety=unverified`: connection properties, permissions, row security, and timeout enforcement were not verified in this session; no runtime cardinality, selectivity, or plan evidence exists.
- Representative rows, had any been queried, would not prove production cardinality or plan stability; here no rows were queried at all.
- The primary-key uniqueness of `COMPANY.ID` and the index covering the predicate are assumptions that remain unresolved without runtime confirmation.
