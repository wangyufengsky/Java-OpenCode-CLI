# IDEA 测试运行器用于非覆盖率验收 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让项目单元测试生成链路在未启用覆盖率时，经 AgentBridge MCP 的 IDEA 测试运行器验证生成的测试。

**Architecture:** `ProjectUnitTestGenerationBatchRunner.validate` 保留测试发现和编译检查。默认分支调用 `run_tests` 后读取 IDEA Run 输出中的测试汇总；覆盖率分支继续构造 Maven/JaCoCo `run_command` 并解析 XML 覆盖率报告。

**Tech Stack:** Java 21、Spring Boot、JUnit 5、AssertJ、AgentBridge MCP。

## Global Constraints

- `test.require-coverage=false` 时不得调用 `run_command` 或 Maven。
- `test.require-coverage=true` 时保留 Maven/JaCoCo 参数和 JaCoCo XML 覆盖率阈值校验。
- `run_tests` 请求使用测试类全限定名、批次模块名和 `agentbridge.timeout-minutes` 换算的秒数，并以 `read_run_output` 的 `0 failed` 汇总作为通过条件。

---

### Task 1: 锁定非覆盖率 IDEA 测试执行契约

**Files:**
- Modify: `src/test/java/com/sonnet/wyf/gitreport/ProjectUnitTestGenerationWorkflowChainTest.java:38-105,421-475`

**Interfaces:**
- Consumes: `AgentBridgeClient.callTool(URI, String, JsonNode)`。
- Produces: Fake AgentBridge 对 `run_tests` 的成功响应，及默认分支工具调用断言。

- [ ] **Step 1: 写入失败断言**

```java
assertThat(client.toolNames).containsSubsequence(
        "list_tests", "get_compilation_errors", "run_tests"
);
assertThat(client.toolNames).doesNotContain("run_command", "get_coverage");
assertThat(client.runTestTargets).containsExactly(
        "com.acme.order.OrderServiceTest", "com.acme.user.UserHelperTest"
);
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=ProjectUnitTestGenerationWorkflowChainTest#fullRunUsesAgentBridgeSerialBatchesAndJavaSideValidation test`

Expected: FAIL，因为现有实现调用 `run_command`，尚未调用 `run_tests`。

- [ ] **Step 3: 扩展 Fake AgentBridge**

```java
case "run_tests" -> runTests(arguments);

protected ToolResponse runTests(JsonNode arguments) {
    runTestTargets.add(arguments.path("target").asText());
    runTestModules.add(arguments.path("module").asText());
    return json(Map.of("success", true, "passed", 1, "failed", 0), "Tests passed");
}
```

- [ ] **Step 4: 保持覆盖率夹具不变**

覆盖率测试继续断言 `client.commands` 中的 Maven dependency plugin、JaCoCo agent 和 report 参数，不为覆盖率分支提供 `run_tests` 成功路径。

### Task 2: 在批处理验收中选择 IDEA 运行器

**Files:**
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/unittest/ProjectUnitTestGenerationBatchRunner.java:143-184`
- Test: `src/test/java/com/sonnet/wyf/gitreport/ProjectUnitTestGenerationWorkflowChainTest.java:38-105,421-475`

**Interfaces:**
- Consumes: `ProjectUnitTestGenerationProperties.Test.requireCoverage`、`AgentBridgeClient.ToolResponse`。
- Produces: 非覆盖率分支的 `run_tests` 请求及统一的测试执行失败摘要。

- [ ] **Step 1: 写最小分支实现**

```java
AgentBridgeClient.ToolResponse run = requireCoverage
        ? client.callTool(mcpUrl, "run_command", runCommandArguments(...))
        : client.callTool(mcpUrl, "run_tests", runTestsArguments(testClass, module, properties));
if (!commandSucceeded(run)) {
    failures.add("目标测试类运行失败: " + testClass + " " + run.text());
}
```

- [ ] **Step 2: 组装 IDEA 请求参数**

```java
private ObjectNode runTestsArguments(String testClass, String module, ProjectUnitTestGenerationProperties properties) {
    return objectMapper.createObjectNode()
            .put("target", testClass)
            .put("module", module)
            .put("timeout", Math.max(60, properties.getAgentbridge().getTimeoutMinutes() * 60))
            .put("title", "Run unit test in IDEA");
}
```

- [ ] **Step 3: 运行聚焦回归测试**

Run: `mvn -q -Dtest=ProjectUnitTestGenerationWorkflowChainTest test`

Expected: PASS，默认分支只使用 `run_tests`，覆盖率分支继续使用 `run_command`。

### Task 3: 完整验证

**Files:**
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/unittest/ProjectUnitTestGenerationBatchRunner.java`
- Modify: `src/test/java/com/sonnet/wyf/gitreport/ProjectUnitTestGenerationWorkflowChainTest.java`

**Interfaces:**
- Consumes: Task 1 与 Task 2 的 MCP 分支契约。
- Produces: 可在当前 IDEA 插件环境中执行的非覆盖率 `run_tests` 调用。

- [ ] **Step 1: 运行全量测试与差异检查**

Run: `mvn -q test && git diff --check`

Expected: Maven 测试套件退出码为 0，且差异无空白错误。

- [ ] **Step 2: 对当前 IDEA MCP 作只读测试执行验证**

使用 `run_tests(target="com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClientTest", module="")`，确认返回的结构化结果表示测试通过。

- [ ] **Step 3: 检查变更范围**

Run: `git status --short && git diff --stat`

Expected: 只包含批处理验收逻辑、其回归测试以及本次设计/计划文档。
