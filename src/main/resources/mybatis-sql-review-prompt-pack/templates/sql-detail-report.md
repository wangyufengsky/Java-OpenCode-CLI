# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from native Database MCP evidence. State its exact statement key, mapper-relative path, namespace, statement id, command type, selectKey flag, source line range, and runtime `data_source`, `catalog`, `schema`, `project`, and `scope` binding.

## Statement

Identify the mapper, statement key, command type, selectKey flag, source line range, dynamic SQL branches, parameters, raw mapper XML, and normalized SQL being reviewed.

- Data source: `exact runtime data_source value`
- Catalog: `exact runtime catalog value`
- Schema: `exact runtime schema value`
- Project: `exact runtime project value`
- Scope: `exact runtime scope value`
- Safety mode: `exact runtime safety_mode value`
- Database safety: `exact runtime database_safety value`

## Static Analysis

Describe correctness, maintainability, performance, concurrency, and data-volume risks visible from the mapper source. Mark claims that require runtime confirmation.

## Database Evidence

[database-evidence.json](database-evidence.json)

## Findings

List each concrete finding with severity, category, supporting evidence ids, and a clear distinction between confirmed and unverified claims. State explicitly when no finding remains.

## Recommendations

Give actionable, scoped recommendations that do not require executing the original DML or selectKey statement.

## Limitations

State the limits of representative rows and every unresolved uncertainty.
