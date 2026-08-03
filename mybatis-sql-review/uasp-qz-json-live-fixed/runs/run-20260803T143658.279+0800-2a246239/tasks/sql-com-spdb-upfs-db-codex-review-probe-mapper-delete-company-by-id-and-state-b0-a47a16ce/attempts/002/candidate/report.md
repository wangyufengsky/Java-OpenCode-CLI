# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database MCP evidence. It reviews statement key `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`, mapper-relative path `src/test/resources/mapper/CodexReviewProbeMapper.xml`, namespace `com.spdb.upfs.db.CodexReviewProbeMapper`, statement id `deleteCompanyByIdAndState`, command type `delete`, selectKey flag `false`, source lines 81-85, and the runtime `data_source`, `catalog`, `schema`, `project`, and `scope` binding.

## Statement

- Mapper-relative path: `src/test/resources/mapper/CodexReviewProbeMapper.xml`
- Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`
- Namespace: `com.spdb.upfs.db.CodexReviewProbeMapper`
- Statement id: `deleteCompanyByIdAndState`
- Command type: `delete`
- selectKey: `false`
- Source line range: `81-85`
- Dynamic SQL branches: none
- Parameters: `#{id,jdbcType=INTEGER}`, `#{companyState,jdbcType=VARCHAR}`
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

The statement is a fixed-shape parameterized DELETE. Both predicates use MyBatis bind placeholders with declared JDBC types; no literal interpolation or dynamic branch is present. The two-predicate filter narrows the intended row set to the requested integer ID and company-state value.

Correctness and concurrency outcome remain dependent on the database schema and caller behavior. Static XML does not prove that `COMPANY.ID` is unique, that `COMPANY_STATE` is the intended optimistic/state guard, or that the caller checks the affected-row count. If state changes concurrently, this predicate can yield zero affected rows; whether that is handled correctly requires runtime/service-layer confirmation. Performance and data-volume behavior require index and cardinality metadata, which was not available because the metadata request was rejected. The statement is DML and has no selectKey, so no Database MCP scenario query was executed.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- `F-001` — low, concurrency/correctness, static finding. The mapper encodes a state predicate but does not itself establish how a zero affected-row result is interpreted. This is a concrete service-contract risk, not confirmation of a defect. Supporting evidence: `static-xml-001`. Verify that the caller treats zero rows as a controlled state-conflict/not-found outcome where required.
- `F-002` — info, performance, unverified. Index coverage and uniqueness for `COMPANY(ID, COMPANY_STATE)` could not be confirmed because the allowed table-schema metadata call was rejected. Supporting evidence: `db-meta-001`. Inspect the schema/index definition through the bounded Database MCP metadata tool in a subsequent approved run.

## Recommendations

Check the affected-row count at the service boundary and define the intended result for a stale or absent state. Confirm that the primary/unique key and indexes support the `ID` plus `COMPANY_STATE` predicate. Do not execute this DELETE or a substitute connection probe as part of this review.

## Limitations

No original mapped SQL was executed. As required for a DELETE statement, no Database MCP query tool was called and scenario count is zero. The single allowed table-schema metadata request was rejected by the host, so no native schema, index, representative-row, cardinality, selectivity, plan, or production-concurrency evidence is available. Runtime safety mode is `connectivity-only` in `environment=test`; `database_safety=unverified` does not verify row-security, function-execution, timeout, or permission controls. Static source cannot prove schema constraints, affected-row handling, data distribution, lock behavior, or operational correctness.