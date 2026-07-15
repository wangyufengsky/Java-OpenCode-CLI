# IDEA Test Exit-Code Acceptance Design

## Goal

Prevent the unit-test workflow from rejecting an IDEA JUnit run that completed successfully but whose output does not contain the plugin-specific `=== Summary:` line.

## Scope

Only the non-coverage acceptance path in `ProjectUnitTestGenerationBatchRunner` changes. Coverage-mode Maven and JaCoCo validation remain unchanged.

## Decision

An IDEA test run is accepted only when both MCP tool calls are not errors and its run output contains one of these positive terminal signals:

1. `=== Summary: N passed, 0 failed` for one or more passed tests;
2. `Process finished with exit code 0`;
3. `进程已结束，退出代码为 0`.

The acceptance logic will not treat a non-zero exit code, an MCP error, or a missing terminal signal as success. This preserves fail-closed behavior while supporting the actual IDEA output observed in the affected project.

## Testing

The existing workflow-chain test double will be extended to emit a successful IDEA terminal line without the summary line. The regression test will prove that this output is accepted. A second test will prove that a non-zero terminal exit code is still rejected.

