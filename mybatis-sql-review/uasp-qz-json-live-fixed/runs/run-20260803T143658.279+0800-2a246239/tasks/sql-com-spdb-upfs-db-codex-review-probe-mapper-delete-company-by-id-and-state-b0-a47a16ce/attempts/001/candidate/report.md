# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database MCP evidence. Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`; mapper-relative path: `src/test/resources/mapper/CodexReviewProbeMapper.xml`; namespace: `com.spdb.upfs.db.CodexReviewProbeMapper`; statement id: `deleteCompanyByIdAndState`; command type: `delete`; selectKey flag: `false`; source line range: `81-85`; runtime binding: data source `deepseek@localhost`, catalog `deepseek`, schema `deepseek`, project `/Users/wangyufeng/IdeaProjects/uasp-qz-json`, scope `ALL`.

## Statement

Mapper: `src/test/resources/mapper/CodexReviewProbeMapper.xml`. Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`. Namespace: `com.spdb.upfs.db.CodexReviewProbeMapper`. Statement id: `deleteCompanyByIdAndState`. Command type: `delete`. selectKey flag: `false`. Source line range: `81-85`. Dynamic SQL branches: none. Parameters: `#{id,jdbcType=INTEGER}`, `#{companyState,jdbcType=VARCHAR}`.

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

The statement uses two bound MyBatis parameters with explicit JDBC types and no dynamic or raw-text substitution nodes, so the mapper text does not expose an interpolation-based SQL-injection path (static-source-001). The compound predicate narrows deletion by `ID` and `COMPANY_STATE`; it can act as a state guard, but the mapper alone cannot establish whether `ID` is unique, whether `COMPANY_STATE` is indexed, or whether zero affected rows are handled correctly by the caller. A conditional delete can return zero rows when the persisted state differs from the supplied state; that outcome needs explicit caller handling when it represents a business concurrency conflict. Runtime confirmation is unavailable because all metadata calls were rejected and this DELETE is static-review-only.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- F-001 — Severity: low; category: concurrency/correctness; supporting evidence: static-source-001, db-meta-003. Confirmed: the delete is conditional on both `ID` and `COMPANY_STATE`. Unverified: uniqueness, affected-row handling, transaction scope, and whether a zero-row result denotes an expected absence or a concurrent state change. A caller that ignores the affected-row count can silently treat an unsuccessful conditional delete as success.
- F-002 — Severity: info; category: performance; supporting evidence: static-source-001, db-meta-003. Confirmed: the predicate filters on `ID` and `COMPANY_STATE`. Unverified: COMPANY table structure and indexes, so index support and data-volume behavior cannot be assessed.

## Recommendations

- Treat the affected-row count as part of the operation contract: distinguish zero rows from a successful deletion and map a state mismatch to the appropriate business/concurrency outcome.
- Verify through approved schema metadata that `COMPANY.ID` has the intended uniqueness constraint and that indexing supports the `ID, COMPANY_STATE` predicate where workload evidence warrants it.
- Keep the bound parameter form and explicit JDBC types; do not replace either placeholder with text substitution.
- Review the surrounding transaction and caller behavior without executing this mapped DELETE.

## Limitations

This review did not execute the original DELETE or any other SQL query, as required for a static-review-only statement. The configured binding is `deepseek@localhost` / `deepseek` / `deepseek` / `/Users/wangyufeng/IdeaProjects/uasp-qz-json` / `ALL`; safety mode is `connectivity-only` and database safety is `unverified`. Database MCP data-source discovery, catalog discovery, and COMPANY schema lookup were each rejected by the host (db-meta-001 through db-meta-003). Therefore there are no representative rows, no schema or index confirmation, and no evidence of production cardinality, selectivity, plan stability, constraints, permissions, timeout enforcement, or transaction behavior.
