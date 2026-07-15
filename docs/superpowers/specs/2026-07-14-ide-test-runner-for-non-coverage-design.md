# 非覆盖率单元测试使用 IDEA 运行器

## 目标

项目单元测试生成链路在未启用覆盖率时，使用 AgentBridge MCP 的 `run_tests` 执行目标测试，以复用 IntelliJ IDEA 已导入的项目模型、模块 classpath 和测试运行配置。

## 分支行为

`test.require-coverage=false` 时，Java 侧验收依次执行：

1. `list_tests`，确认生成的测试已被 IDEA 识别；
2. `get_compilation_errors`，确认目标测试文件没有编译错误；
3. `run_tests`，以测试类全限定名作为 `target`，并传入批次模块名和现有超时值；
4. `read_run_output`，读取刚刚启动的 IDEA 测试 Run 标签页，解析测试汇总。

`test.require-coverage=true` 时，保留现有 `run_command` 的 Maven、JaCoCo agent 和报告解析流程，因为 IDEA `run_tests` 不生成该链路需要的 JaCoCo XML。

## 成功判定与错误处理

非覆盖率的测试执行成功，要求 `run_tests` 的 MCP 响应未标记 `isError`，且 `read_run_output` 的测试汇总显示 `0 failed` 和至少一个 `passed`。失败响应保留在 batch 验收摘要中，供下一次 AgentBridge 修正 prompt 使用。

## 回归测试

测试覆盖两个互斥分支：

- 默认非覆盖率链路调用 `run_tests`，不调用 `run_command`，且请求包含正确的测试目标和模块名；
- 覆盖率链路继续调用 `run_command`，并保留现有 Maven/JaCoCo 参数断言。
