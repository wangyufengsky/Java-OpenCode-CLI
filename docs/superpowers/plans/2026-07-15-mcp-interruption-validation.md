# MCP interruption validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make transient IDEA/AgentBridge interruptions during unit-test verification retry safely and report a transport failure instead of a test or compilation failure.

**Architecture:** Keep the retry policy inside `ProjectUnitTestGenerationBatchRunner`, where the idempotence of each validation call is known. Retry only `list_tests`, `get_compilation_errors`, and `read_run_output`; preserve the existing one-shot behavior of `run_tests` and coverage commands.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Jackson.

## Global Constraints

- Preserve the existing serial validation order.
- Use three total attempts and one-second retry delay for explicit interruption/cancellation responses only.
- Never retry `run_tests` or `run_command`.
- Preserve the existing dirty worktree; stage or commit nothing unless separately requested.

---

### Task 1: Cover MCP interruption classification and retry

**Files:**

- Modify: `src/test/java/com/sonnet/wyf/gitreport/ProjectUnitTestGenerationWorkflowChainTest.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/unittest/ProjectUnitTestGenerationBatchRunner.java`

**Interfaces:**

- Consumes: `AgentBridgeClient.ToolResponse`, whose `rawResult().isError()` and `text()` describe a tool response.
- Produces: validation failures that distinguish MCP interruption from test discovery and compilation failures.

- [ ] **Step 1: Write the failing tests**

Add focused tests using a fake client that returns `isError=true` with `java.lang.InterruptedException` for `list_tests` or `get_compilation_errors`, then either succeeds on a later invocation or remains interrupted. Assert three behaviors: recovery retries the read-only call; persistent interruption contains `IDE/MCP` and the tool name; interrupted `list_tests` is not accepted as a discovered test.

- [ ] **Step 2: Run the focused test class and verify RED**

Run: `mvn -q '-Dtest=ProjectUnitTestGenerationWorkflowChainTest' test`

Expected: the new assertions fail because the current runner accepts an interrupted `list_tests` response and does not retry tool calls.

- [ ] **Step 3: Implement the smallest retry boundary**

Add a private helper in `ProjectUnitTestGenerationBatchRunner` that invokes a named read-only tool up to three times, sleeping one second only after responses whose result has `isError=true` and whose text contains `interrupted` or `cancelled`. Return the final response without retrying any other error.

Use the helper only for `list_tests`, `get_compilation_errors`, and `read_run_output`. Before `hasRecognizedTest` or `hasNoCompilationErrors`, turn an error response into `IDE/MCP 调用中断或失败: <tool> <output>`.

- [ ] **Step 4: Run the focused test class and verify GREEN**

Run: `mvn -q '-Dtest=ProjectUnitTestGenerationWorkflowChainTest' test`

Expected: exit code 0 and all tests in the class pass.

- [ ] **Step 5: Run the full verification suite**

Run: `mvn -q test && git diff --check`

Expected: exit code 0 and no whitespace errors.
