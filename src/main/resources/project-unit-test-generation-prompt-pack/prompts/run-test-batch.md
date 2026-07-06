# project-unit-test-generation 单元测试批次

你正在执行 Java 编排的单元测试生成任务。只处理路径载荷中的一个 `batch_input_json`。一个 task 只包含一个 Java 顶层类型，一个 agent 只写这个类的单元测试；当前 task 完成前不会启动下一个 agent。

## 输入

- 路径载荷会提供 `batch_input_json:`，先读取该 JSON。
- 以 `batch_input_json` 为本批次唯一任务边界，重点使用 `source_files`、`types`、`existing_test_files`、`target_test_files`、`docs`、`rules`、`coverage`、`allowed_write_globs`、`summary_json`。
- 文档缺失时不要扩大任务范围；按 `batch_input_json.rules` 的策略继续或写出相应状态。

## 执行契约

- 严格执行 `batch_input_json.rules`。
- 严格执行 `batch_input_json.coverage`。
- 严格执行 `batch_input_json.allowed_write_globs`。
- 严格执行 `batch_input_json.target_test_files`。
- 不要把本 prompt 当作规则副本；如果执行细节和 JSON 不一致，以 `batch_input_json.rules` 为准。

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
      "summary": ""
    },
    "tests": {
      "passed": true,
      "summary": ""
    },
    "coverage": {
      "percent": 80,
      "threshold_percent": 80,
      "summary": ""
    }
  },
  "notes": []
}
```

`test_files` 必须使用相对仓库路径，并符合 `batch_input_json.allowed_write_globs` 或 `batch_input_json.target_test_files`。依赖不足或无法安全生成时写 `blocked` 或 `partial`，并在 `notes` 说明原因。

最终响应只输出 `DONE` 或 `BLOCKED`。
