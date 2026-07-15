# IDEA Test Exit-Code Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Accept successful IDEA JUnit runs that expose a zero terminal exit code instead of the AgentBridge summary line.

**Architecture:** Keep the current fail-closed MCP checks. Extend only the local `ideaTestSucceeded` signal parser so it recognizes the known English and Chinese IDEA terminal success messages in addition to the existing passed-test summary.

**Tech Stack:** Java 21, JUnit 5, AssertJ, Spring Boot test suite.

## Global Constraints

- Do not change Maven/JaCoCo coverage-mode execution.
- `isError=true` from either MCP call remains a failure.
- A non-zero exit code or no recognizable terminal signal remains a failure.

---

### Task 1: Cover IDEA terminal exit-code output

**Files:**
- Modify: `src/test/java/com/sonnet/wyf/gitreport/ProjectUnitTestGenerationWorkflowChainTest.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/unittest/ProjectUnitTestGenerationBatchRunner.java`

**Interfaces:**
- Consumes: `AgentBridgeClient.ToolResponse` returned by `read_run_output`.
- Produces: a true acceptance result for an error-free IDEA run output ending with zero.

- [x] **Step 1: Write the failing tests**

Add a fake-client variant whose `read_run_output` result is `进程已结束，退出代码为 0`, then assert the workflow report contains `accepted: \`2\`` and `failed: \`0\``. Add a second variant whose result is `进程已结束，退出代码为 1`, then assert the batch remains rejected.

- [x] **Step 2: Run the focused test to verify it fails**

Run: `mvn -q '-Dtest=ProjectUnitTestGenerationWorkflowChainTest' test`

Expected before the production change: the zero-exit-code case fails because `ideaTestSucceeded` accepts only `=== Summary:`.

- [x] **Step 3: Write the minimal parser change**

Add static patterns for `Process finished with exit code 0` and `进程已结束，退出代码为 0`. Change `ideaTestSucceeded` to require no MCP errors and match any one of the three success patterns.

- [x] **Step 4: Run the focused test to verify it passes**

Run: `mvn -q '-Dtest=ProjectUnitTestGenerationWorkflowChainTest' test`

Expected: exit code 0, including the new positive and negative terminal-output cases.

- [x] **Step 5: Run regression verification**

Run: `mvn -q test && git diff --check`

Expected: Maven exits 0 and `git diff --check` produces no output.
