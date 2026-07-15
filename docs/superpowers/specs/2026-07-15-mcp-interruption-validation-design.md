# MCP interruption validation design

## Goal

Keep transient IDEA/AgentBridge MCP interruptions from being reported as missing tests or Java compilation failures during `project-unit-test-generation` verification.

## Scope

- Retry only read-only validation calls: `list_tests`, `get_compilation_errors`, and `read_run_output`.
- Retry only explicit transient interruption/cancellation responses, with three total attempts and a one-second delay between attempts.
- Do not retry `run_tests` or coverage `run_command`, because executing a test or command twice can have observable side effects.
- If all attempts fail, return a diagnostic that identifies the interrupted MCP tool and preserves its output.

## Design

`ProjectUnitTestGenerationBatchRunner` will own a small retry helper because this policy belongs to unit-test acceptance, not to every AgentBridge MCP caller. The helper receives the tool name and an invocation supplier, immediately returns a normal result, and retries only when `ToolResponse.rawResult().isError` is true and the response text names interruption or cancellation.

Validation will handle a failed invocation before semantic checks. A failed `list_tests` call produces an MCP-availability failure rather than being interpreted as a discovered test. A failed compilation check produces the same availability failure rather than a compilation-error failure. `read_run_output` follows the same retry policy, while `run_tests` remains single-shot.

## Verification

Tests will prove: an interrupted read-only tool call retries and can recover; persistent interruption reports an MCP failure; an interrupted `list_tests` response is not accepted as a valid test; and existing successful validation behavior remains unchanged.
