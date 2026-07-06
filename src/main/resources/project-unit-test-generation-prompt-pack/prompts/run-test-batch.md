# project-unit-test-generation 单元测试批次

你正在执行 Java 编排的单元测试生成任务。只处理路径载荷中的一个 `batch_input_json`。

## 输入边界

- 读取 `batch_input_json` 中的 `source_files`、`types`、`existing_test_files`、`target_test_files`、`summary_json`。
- 可以读取目标仓库内的源码、已有测试，以及 `batch_input_json.docs` 指定的 `agents`、`project_map`、`reconstructed_design` 文档。
- 文档缺失时按源码和已有测试继续，不要阻塞。
- 读取 batch_input_json、源码、已有测试和文档时，必须使用 `AgentBridge` MCP 文件读取工具：`read_file`。

## 写入边界

- 只允许创建或修改目标项目 src/test/** 下的测试文件。
- 禁止修改生产代码、`pom.xml`、Gradle 文件、脚本、配置文件和 `src/main/**`。
- 创建或修改测试文件、写入 summary_json 时，必须使用 `AgentBridge` MCP 文件编辑工具：`edit_text` 或 `write_file`。
- `AgentBridge` MCP 读写工具不可用时必须写 `blocked` 或返回 `BLOCKED`，不要改用 shell、Python、重定向或批量替换来写文件。

## 诊断与测试反馈

- 写完或修改测试文件后，必须调用 `AgentBridge` MCP 诊断工具：`get_compilation_errors`。
- `get_compilation_errors` 返回测试代码编译错误时，继续用 `AgentBridge` MCP 文件编辑工具修正测试文件，然后再次调用 `get_compilation_errors`。
- 用 `list_tests` 查已有测试，用 `get_coverage` 可选读取已有覆盖率。
- 不要在批次 worker 内调用 `run_tests` 或 `build_project`；本链路会并发执行多个批次，运行测试或构建会互相影响。
- `run_tests` 和 `build_project` 只作为工具能力记录在 batch_input_json.skill.test_tools 中，不作为批次 worker 的执行步骤。最终验证由 Java 链路串行执行 `test.verify-command`。
- 如果目标类已经存在单元测试，先用 `get_coverage` 检查该类覆盖率。
- 覆盖率达到 batch_input_json.coverage.threshold_percent 时跳过该类，不要为了重写风格而修改已有测试。
- 覆盖率未达标时只补充该类已有测试或目标测试文件，优先补缺失分支、异常路径和边界值。
- 诊断工具不可用时，在 `summary_json.notes` 中说明；如果因此无法确认测试代码是否可编译，状态写 `partial` 或 `blocked`。

## 输出

完成后写入 `summary_json`，至少包含：

```json
{
  "batch_id": "<batch_id>",
  "status": "completed|partial|blocked",
  "source_files": [],
  "test_files": [],
  "notes": []
}
```

依赖不足或无法安全生成时写 `blocked` 或 `partial`，并在 `notes` 说明原因。只要生成了测试文件，`test_files` 必须使用相对仓库路径并位于 `src/test/**`。
