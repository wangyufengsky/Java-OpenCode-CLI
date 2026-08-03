# SQL Review

This candidate report reviews exactly one inventory statement: `deleteCompanyByIdAndState`, a `delete` command (selectKey: false) with no dynamic SQL branches, located at lines 81–85 of `src/test/resources/mapper/CodexReviewProbeMapper.xml` (namespace `com.spdb.upfs.db.CodexReviewProbeMapper`). The runtime Database MCP binding is data source `deepseek@localhost`, catalog `deepseek`, schema `deepseek`, project `/Users/wangyufeng/IdeaProjects/uasp-qz-json`, scope `ALL`, safety mode `connectivity-only`, database safety `unverified`. This is a static-review-only statement: no Database MCP call was executed for it.

## Statement

The reviewed statement is a parameterized DELETE keyed by `ID` with a `COMPANY_STATE` guard.

- Mapper: `src/test/resources/mapper/CodexReviewProbeMapper.xml`
- Statement key: `com-spdb-upfs-db-codex-review-probe-mapper-delete-company-by-id-and-state-b01871f17412`
- Namespace: `com.spdb.upfs.db.CodexReviewProbeMapper`
- Statement id: `deleteCompanyByIdAndState`
- Command type: `delete`
- SelectKey flag: `false`
- Source lines: 81–85
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

Correctness: Both predicates are bound via `#{...}` placeholders, so the WHERE clause is always present; there is no unguarded full-table DELETE. Explicit `jdbcType` hints avoid type-inference ambiguity between the driver and the database.

Maintainability: The statement declares `parameterType="java.util.Map"`; the caller must supply both map keys `id` and `companyState`, otherwise the bind fails at runtime. No dynamic SQL branches reduce conditional complexity.

Performance: The leading predicate `ID = ...` is expected to use a primary-key index, making the additional `COMPANY_STATE` filter a post-filter on a bounded candidate set. This claim requires runtime confirmation that `ID` is the primary key.

Concurrency: There is no optimistic-locking guard (no version column or last-updated-timestamp predicate). A caller acting on a stale snapshot can delete a row that has since been modified by another transaction. Whether this is a real risk depends on the application's concurrency model — unverified.

Data volume: The DELETE is not bounded by `LIMIT`. With a unique `ID` it targets at most one row; if `ID` is not unique, multiple rows could be removed. The affected-row count is not validated inside the mapper.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

**F-01 (low, concurrency)** No optimistic-locking guard on the delete. The statement deletes purely on `ID` and `COMPANY_STATE`; a stale caller can remove a row that changed after it was read. Confirmed statically from the mapper source; the surrounding application's concurrency behavior is unverified. Evidence: none — `evidence_ids` is empty because no Database MCP scenario ran.

**F-02 (info, data-integrity)** Affected-row count is not enforced. The mapper returns the native affected-row count, but the statement itself cannot guarantee exactly one row was deleted. Confirmed statically; no runtime evidence.

## Recommendations

- Consider adding an optimistic-locking guard (e.g., a `VERSION` or last-updated-timestamp predicate) to the WHERE clause if concurrent modification of COMPANY rows is possible.
- Verify that callers check the affected-row count returned by the mapper and treat any value other than 1 as a failure.
- Confirm `ID` is the unique/primary key of COMPANY so the delete is bounded to a single row.
- These recommendations are static and scoped; they do not require executing the original DML or any selectKey statement.

## Limitations

- Static-review-only `delete` statement: per the contract, `cmcp_db_database_execute_sql_query` was not called for this statement, including no `SELECT 1` or other connection probe. `audit.tool_call_ids`, `metadata`, and `scenarios` are empty.
- `database_safety=unverified` (connectivity-only mode): no row-security, function-execution, timeout, or actual row-count behavior was verified against the live database.
- No Database MCP metadata was collected; primary-key uniqueness, index coverage, and COMPANY cardinality are unconfirmed.
- Representative runtime rows were not collected; all findings are static and carry empty `evidence_ids`.
