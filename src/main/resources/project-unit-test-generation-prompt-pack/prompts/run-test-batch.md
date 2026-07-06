# project-unit-test-generation 单元测试批次

你正在执行 Java 编排的单元测试生成任务。只处理路径载荷中的一个 `batch_input_json`。一个 task 只包含一个 Java 顶层类型，一个 agent 只写这个类的单元测试；当前 task 完成前不会启动下一个 agent。

## 输入边界

- 读取 `batch_input_json` 中的 `source_files`、`types`、`existing_test_files`、`target_test_files`、`summary_json`。
- 可以读取目标仓库内的源码、已有测试，以及 `batch_input_json.docs` 指定的 `agents`、`project_map`、`reconstructed_design` 文档。
- 文档缺失时按源码和已有测试继续，不要阻塞。
- 读取 batch_input_json、源码、已有测试和文档时，必须使用 `AgentBridge` MCP 文件读取工具：`read_file`。
- 写测试前必须先查阅当前项目已有单元测试，理解断言库、命名、Mock、Spring/JUnit 用法，并仿照现有代码风格。
- 开始写代码前，先判断本 task 是需要新写测试、补充已有测试，还是已有测试已经满足覆盖率；不要跳过已有测试的编译、测试和覆盖率确认。

## 写入边界

- 只允许创建或修改目标项目 src/test/** 下的测试文件。
- 禁止修改生产代码、`pom.xml`、Gradle 文件、脚本、配置文件和 `src/main/**`。
- 创建或修改测试文件、写入 summary_json 时，必须使用 `AgentBridge` MCP 文件编辑工具：`edit_text` 或 `write_file`。
- `AgentBridge` MCP 读写工具不可用时必须写 `blocked` 或返回 `BLOCKED`，不要改用 shell、Python、重定向或批量替换来写文件。

## 诊断与测试反馈

- 写完或修改测试文件后，必须调用 `AgentBridge` MCP 诊断工具：`get_compilation_errors`。
- 如果目标类已经存在单元测试，开始写代码前也必须先执行 `get_compilation_errors`，确认当前测试代码没有编译错误。
- `get_compilation_errors` 返回测试代码编译错误时，继续用 `AgentBridge` MCP 文件编辑工具修正测试文件，然后再次调用 `get_compilation_errors`。
- 用 `list_tests` 查已有测试。
- 调用 `run_tests` 跑当前批次相关测试；run_tests 失败时，根据失败原因修改测试，修改结束后回到 `get_compilation_errors`，再执行 `run_tests`，再次循环直到测试通过。
- 调用 `get_coverage` 采集当前类覆盖率；覆盖率未达标时必须新增测试场景，优先补缺失分支、异常路径和边界值，然后回到 `get_compilation_errors` -> `run_tests` -> `get_coverage` 继续循环。
- 任一步失败或覆盖率不足，修改后都必须回到 `get_compilation_errors` 继续循环。
- 如果目标类已经存在单元测试，先用 `get_coverage` 检查该类覆盖率。
- 覆盖率达到 batch_input_json.coverage.threshold_percent 时跳过该类，不要为了重写风格而修改已有测试。
- 覆盖率未达标时只补充该类已有测试或目标测试文件，优先补缺失分支、异常路径和边界值。
- 诊断工具不可用或 agent 中途无法继续时，在 `summary_json.notes` 中说明；如果因此无法确认测试代码是否可编译、测试是否通过或覆盖率是否达标，状态写 `partial` 或 `blocked`，Java 编排会把该 task 作为未完成任务补跑。
- `completed` 只允许在以下条件全部满足时写入：已查阅现有测试风格、`get_compilation_errors` 无当前测试编译错误、`run_tests` 当前批次相关测试通过、`get_coverage` 当前类覆盖率达到阈值。

## 输出

完成后写入 `summary_json`，至少包含：

```json
{
  "batch_id": "<batch_id>",
  "status": "completed|partial|blocked",
  "source_files": [],
  "test_files": [],
  "checks": {
    "style_reviewed": true,
    "compilation": {
      "passed": true,
      "summary": "get_compilation_errors 无当前测试编译错误"
    },
    "tests": {
      "passed": true,
      "summary": "run_tests 当前批次相关测试通过"
    },
    "coverage": {
      "percent": 80,
      "threshold_percent": 80,
      "summary": "get_coverage 当前类覆盖率达到阈值"
    }
  },
  "notes": []
}
```

依赖不足或无法安全生成时写 `blocked` 或 `partial`，并在 `notes` 说明原因。只要生成了测试文件，`test_files` 必须使用相对仓库路径并位于 `src/test/**`。
