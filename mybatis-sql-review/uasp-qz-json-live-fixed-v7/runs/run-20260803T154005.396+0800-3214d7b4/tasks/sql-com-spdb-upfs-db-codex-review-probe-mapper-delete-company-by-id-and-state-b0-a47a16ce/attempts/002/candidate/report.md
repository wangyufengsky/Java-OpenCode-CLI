# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. The reviewed statement has statement key
`com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`, mapper-relative path
`src/test/resources/mapper/CodexReviewProbeMapper.xml`, namespace `com.spdb.upfs.db.CodexReviewProbeMapper`, statement
id `deleteCompanyByIdAndState`, command type `delete`, selectKey flag `false`, source line range 81-85, and runtime
binding `data_source` `deepseek@localhost`, `catalog` `deepseek`, `schema` `deepseek`, `project`
`/Users/wangyufeng/IdeaProjects/uasp-qz-json`, `scope` `ALL`.

## Statement

The mapper `CodexReviewProbeMapper.xml` defines statement `deleteCompanyByIdAndState` (statement key
`com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`) with command type `delete`,
selectKey flag `false`, and source line range 81-85. It contains no dynamic SQL branches (`dynamic_nodes` is empty) and
binds two parameters: `#{id,jdbcType=INTEGER}` and `#{companyState,jdbcType=VARCHAR}`. The raw mapper XML is:

```xml
<delete id="deleteCompanyByIdAndState" parameterType="java.util.Map">
        DELETE FROM COMPANY
        WHERE ID = #{id,jdbcType=INTEGER}
          AND COMPANY_STATE = #{companyState,jdbcType=VARCHAR}
    </delete>
```

The normalized SQL is:

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

Correctness: the statement deletes rows from `COMPANY` where `ID` equals the bound `id` and `COMPANY_STATE` equals the
bound `companyState`, both with explicit jdbcType hints. If either bound value is null, the predicates become
`ID = NULL` / `COMPANY_STATE = NULL`, which match no rows, so the statement degrades to a silent no-op rather than an
error.

Maintainability: the statement is small and readable, with no `<sql>` fragment reuse and no dynamic branching.

Performance: the predicate on `ID` (likely the primary key) bounds the scan, with `COMPANY_STATE` as an additional
equality filter. The effective access path depends on the index available on `COMPANY.ID` and requires runtime
confirmation.

Concurrency and data-volume: the DELETE acquires locks on matching rows until commit. If `ID` is not unique in
`COMPANY`, multiple rows could be locked and deleted. Physical deletes are non-recoverable once committed.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- F1 — severity `medium`, category `data-integrity`, evidence ids `[]`. Confirmed (static): the mapped SQL is a hard
  physical DELETE with no soft-delete column or audit hook; accidental or buggy invocation permanently removes matching
  rows. Unverified: whether the business requires audit or logical deletion.
- F2 — severity `low`, category `correctness`, evidence ids `[]`. Confirmed (static): a null `id` or `companyState`
  binds to `= NULL` predicates that match nothing, producing a silent no-op. Unverified: whether callers validate
  parameters beforehand.
- F3 — severity `low`, category `data-integrity`, evidence ids `[]`. Confirmed (static): the affected-row scope depends
  on the uniqueness of `COMPANY.ID`, and no limit or guard exists. Unverified: the actual uniqueness constraint on
  `COMPANY.ID` at runtime.

No Database MCP query was executed; no native tool-call evidence exists. All findings above are static observations and
remain unverified against runtime state.

## Recommendations

- Confirm that `ID` is the primary key (or uniquely constrained) of `COMPANY`; if not, verify the affected-row count
  before commit or add an explicit guard.
- Prefer a logical (soft) delete or an audit record if deleted data must be recoverable; otherwise enforce caller-side
  authorization and logging around this statement.
- Validate that `id` and `companyState` are non-null before invoking the mapper to avoid silent no-ops.
- Review the transaction boundary so the DELETE and any compensating audit write commit atomically.

## Limitations

- No scenario queries were run (command type is `delete`; the contract forbids executing DML or repeating the connection
  probe), so `database-evidence.json` carries empty `metadata`, `scenarios`, and `audit.tool_call_ids` arrays and no
  finding has native evidence ids.
- Runtime cardinality, selectivity, index availability, and plan stability are unverified; representative-row evidence
  is not applicable here.
- `database_safety` is `unverified` in `connectivity-only` mode; no claims about row-security, function-execution, or
  timeout enforcement are made.
