# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. The reviewed statement has statement key
`com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`, mapper-relative path
`src/test/resources/mapper/CodexReviewProbeMapper.xml`, namespace `com.spdb.upfs.db.CodexReviewProbeMapper`, statement
id `deleteCompanyByIdAndState`, command type `delete`, selectKey flag `false`, source line range lines 81-85, and
runtime binding `data_source`=`deepseek@localhost`, `catalog`=`deepseek`, `schema`=`deepseek`, `project`=
`/Users/wangyufeng/IdeaProjects/uasp-qz-json`, `scope`=`ALL`.

## Statement

The statement under review is `deleteCompanyByIdAndState`, a `delete` command in mapper
`src/test/resources/mapper/CodexReviewProbeMapper.xml` (namespace `com.spdb.upfs.db.CodexReviewProbeMapper`). It is not
a selectKey statement. It is a single literal DELETE with no dynamic SQL branches (`dynamic_nodes` is empty). Parameters
are `#{id,jdbcType=INTEGER}` and `#{companyState,jdbcType=VARCHAR}`, bound from a `java.util.Map`.

- Data source: `deepseek@localhost`
- Catalog: `deepseek`
- Schema: `deepseek`
- Project: `/Users/wangyufeng/IdeaProjects/uasp-qz-json`
- Scope: `ALL`
- Safety mode: `connectivity-only`
- Database safety: `unverified`

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

## Static Analysis

- Correctness: The WHERE clause predicates on `ID` and `COMPANY_STATE`. If `ID` is the primary key of `COMPANY` (
  unverified at runtime) and `COMPANY_STATE` is a required attribute, at most one row can match, so the delete is
  semantically scoped. No SQL-level syntax issue is visible in the mapper source.
- Parameter binding: Both placeholders declare explicit `jdbcType` values (`INTEGER` for `id`, `VARCHAR` for
  `companyState`), which reduces null-binding ambiguity. Note the standard MyBatis semantics: a null `id` or null
  `companyState` becomes `= NULL`, which never matches and therefore deletes zero rows rather than raising an error.
- Maintainability: The statement uses constant table and column names with no dynamic fragments; it is easy to read and
  index. No maintainability risk visible from the source.
- Performance: The predicate anchors on `ID`; even without an index on `COMPANY_STATE`, the database resolves the row by
  primary key and filters the state on that single row. This makes the statement bounded in practice, but actual index
  structure and cardinality require runtime confirmation.
- Concurrency: The DELETE takes a row-level lock on the matched row. Transaction boundaries and timeout enforcement are
  not visible in the mapper and must be provided by the caller; this claim requires runtime confirmation.
- Data-volume risk: A full-table scan risk exists only if the WHERE is not selective (for example, if the caller passes
  null values). Since `ID` is the predicate anchor, the delete targets at most one row; this is a static expectation
  that requires runtime confirmation.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- **F-01 — low — affected-row verification** (category: robustness; evidence_ids: []): The mapper exposes only the
  implicit affected-row count; there is no guard against a 0-row delete (for example, an already-deleted company or a
  mismatched `COMPANY_STATE`). Callers must verify the returned count. Static observation; runtime behavior unverified.
- **F-02 — info — state-conditioned delete guard** (category: data-integrity; evidence_ids: []): Including
  `COMPANY_STATE` in the WHERE clause acts as a guard that prevents deleting a company in an unexpected state. Combined
  with `ID`, this is a reasonable data-safety pattern. Static observation; no runtime evidence collected.
- **F-03 — info — null parameter binding semantics** (category: correctness; evidence_ids: []): A null `id` or
  `companyState` resolves to `= NULL` and deletes zero rows silently instead of failing. If a delete with missing
  parameters must be rejected, validation belongs in the service layer. Static observation; unverified at runtime.

## Recommendations

- In the service layer, check the affected-row count returned by `deleteCompanyByIdAndState` and roll back or raise when
  it is 0.
- Validate that `id` and `companyState` are non-null before invoking the mapper to avoid a silently successful no-op
  delete.
- Confirm that `COMPANY.ID` is the primary key (and whether `COMPANY_STATE` is indexed) so the delete remains a
  single-row operation; this requires runtime confirmation and does not require executing the original DML.
- Wrap the delete in a transaction with an explicit timeout at the caller; no timeout enforcement is visible in the
  mapper.

## Limitations

- The reviewed statement is a `delete` (static-review-only); no `cmcp_db_database_execute_sql_query` call was made and
  no bounded scenario query was supplied, so no representative rows, cardinality, selectivity, or plan evidence was
  collected.
- Runtime `safety_mode` is `connectivity-only` with `database_safety` `unverified`; connection reachability was probed
  by Java preflight only. Row-security, function-execution, and timeout enforcement were not verified in the Agent
  session.
- Index structure, table cardinality, and concurrency behavior of the `COMPANY` table are unconfirmed; all claims above
  are static observations from the mapper source.
