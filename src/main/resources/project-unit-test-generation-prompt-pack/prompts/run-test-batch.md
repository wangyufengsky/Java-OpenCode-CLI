# project-unit-test-generation 单元测试批次

你正在执行 Java 编排的单元测试生成任务。只处理路径载荷中的一个 `batch_input_json`。

## 输入边界

- 读取 `batch_input_json` 中的 `source_files`、`types`、`existing_test_files`、`target_test_files`、`summary_json`。
- 可以读取目标仓库内的源码、已有测试，以及 `batch_input_json.docs` 指定的 `agents`、`project_map`、`reconstructed_design` 文档。
- 文档缺失时按源码和已有测试继续，不要阻塞。

## 写入边界

- 只允许创建或修改目标项目 src/test/** 下的测试文件。
- 禁止修改生产代码、`pom.xml`、Gradle 文件、脚本、配置文件和 `src/main/**`。
- 不要使用 shell、Python、重定向或批量替换来写文件；优先使用 OpenCode 原生文件编辑工具。

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
