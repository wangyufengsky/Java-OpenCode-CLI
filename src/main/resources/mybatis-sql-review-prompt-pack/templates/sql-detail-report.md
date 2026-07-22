# SQL Review

This candidate report reviews exactly one inventory statement and distinguishes static observations from database evidence.

## Statement

Identify the mapper, statement key, command type, dynamic SQL branches, parameters, and normalized SQL being reviewed.

## Static Analysis

Describe correctness, maintainability, performance, concurrency, and data-volume risks visible from the mapper source. Mark claims that require runtime confirmation.

## Database Evidence

Describe each metadata observation and representative read-only scenario with its evidence id and audited AgentBridge tool-call id. State when no database query was needed.

## Findings

List each concrete finding with severity, category, supporting evidence ids, and a clear distinction between confirmed and unverified claims. State explicitly when no finding remains.

## Recommendations

Give actionable, scoped recommendations that do not require executing the original DML or selectKey statement.

## Limitations

State the post-hoc nature of the tool-call audit, the limits of representative rows, and every unresolved uncertainty.
