# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database
MCP evidence. Statement key `com-spdb-upfs-db-codex-review-probe-mapper-insert-company-9f7f553cf938`, mapper-relative
path `src/test/resources/mapper/CodexReviewProbeMapper.xml`, namespace `com.spdb.upfs.db.CodexReviewProbeMapper`,
statement id `insertCompany`, command type `insert`, selectKey flag `false`, source line range 42-57, bound to
data_source `deepseek@localhost`, catalog `deepseek`, schema `deepseek`, project
`/Users/wangyufeng/IdeaProjects/uasp-qz-json`, scope `ALL`.

## Statement

The reviewed statement is the `<insert>` `insertCompany` in mapper `com.spdb.upfs.db.CodexReviewProbeMapper`. It is a
plain insert (not a `<selectKey>` statement), declares no dynamic SQL branches, and defines five `#{}` parameter
placeholders. No Database MCP query was executed for this static-review-only statement.

- Data source: `deepseek@localhost`
- Catalog: `deepseek`
- Schema: `deepseek`
- Project: `/Users/wangyufeng/IdeaProjects/uasp-qz-json`
- Scope: `ALL`
- Safety mode: `connectivity-only`
- Database safety: `unverified`

Raw mapper XML:

```xml
<insert id="insertCompany" parameterType="java.util.Map"
            useGeneratedKeys="true" keyProperty="id" keyColumn="ID">
        INSERT INTO COMPANY (
            COMPANY_NAME,
            COMPANY_STATE,
            COMPANY_CITY,
            EMPLOYEE,
            COMPANY_CREATE_YEAR
        ) VALUES (
            #{companyName,jdbcType=VARCHAR},
            #{companyState,jdbcType=VARCHAR},
            #{companyCity,jdbcType=VARCHAR},
            #{employee,jdbcType=INTEGER},
            #{companyCreateYear,jdbcType=INTEGER}
        )
    </insert>
```

Normalized SQL:

```sql
INSERT INTO COMPANY (
    COMPANY_NAME,
    COMPANY_STATE,
    COMPANY_CITY,
    EMPLOYEE,
    COMPANY_CREATE_YEAR
) VALUES (
    #{companyName,jdbcType=VARCHAR},
    #{companyState,jdbcType=VARCHAR},
    #{companyCity,jdbcType=VARCHAR},
    #{employee,jdbcType=INTEGER},
    #{companyCreateYear,jdbcType=INTEGER}
)
```

Dynamic SQL branches: none. Parameters: `companyName`, `companyState`, `companyCity`, `employee`, `companyCreateYear`.

## Static Analysis

Correctness: the column list and the five placeholders align 1:1 in order, so the binding is structurally consistent.
`useGeneratedKeys="true"` with `keyProperty="id"` and `keyColumn="ID"` depends on the JDBC driver's `getGeneratedKeys`
support and on `COMPANY.ID` being an auto-increment/identity column; this requires runtime confirmation and is
unverified here. Because `parameterType="java.util.Map"` is untyped, a missing map key would raise a `BindingException`
at runtime.

Maintainability: the map parameter contract is typo-prone and offers no compile-time safety; a typed DTO or `@Param`
method arguments would be safer. Identifier casing (uppercase, unquoted) may not match the catalog's identifier folding;
this also requires runtime confirmation.

Performance and concurrency: a simple single-row insert with no dynamic SQL; no query is executed by the Agent.
Generated-key behavior under concurrency is handled by the driver/database and is not reviewable from mapper source
alone.

Data volume: the statement inserts a single row per invocation; batching behavior is outside the mapper and not
reviewed.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

- F1 (low, portability): generated-key retrieval depends on driver support. `useGeneratedKeys`/`keyProperty`/`keyColumn`
  work only if the driver returns generated keys for an identity `COMPANY.ID`. Unverified — needs runtime confirmation.
  Evidence: none (static).
- F2 (low, maintainability): unchecked `java.util.Map` parameter contract; missing keys fail at runtime, nulls may hit
  NOT NULL constraints. Unverified against the actual schema. Evidence: none (static).
- F3 (info, portability): uppercase unquoted identifiers may not match catalog identifier folding; unverified. Evidence:
  none (static).
- F4 (info, maintainability): no dynamic SQL branches; nothing injection-prone. Evidence: none (static).

No database-backed finding remains; all claims above are static observations pending runtime confirmation.

## Recommendations

- Confirm `COMPANY.ID` is an identity/auto-increment column and that the target JDBC driver supports `getGeneratedKeys`;
  otherwise read the generated id with a follow-up SELECT or an explicit `<selectKey>`.
- Replace the raw `java.util.Map` parameter with a typed parameter object (or `@Param` arguments) and validate required
  fields before insert.
- Align identifier casing with the target catalog's actual `COMPANY` column definitions.
- None of these require executing the original DML.

## Limitations

- The statement is static-review-only; no Database MCP query (including any connection probe) was executed, so
  `audit.tool_call_ids`, `metadata`, and `scenarios` are empty and no native tool-call evidence exists.
- `database_safety=unverified` under connectivity-only mode: row-security, function-execution, and timeout controls were
  not verified.
- Representative rows, production cardinality, selectivity, and plan stability are not applicable because no rows were
  retrieved.
- All constraint and driver-support claims remain unverified against the live catalog.
