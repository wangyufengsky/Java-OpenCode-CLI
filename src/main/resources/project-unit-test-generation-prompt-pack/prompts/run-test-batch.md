# project-unit-test-generation 单元测试批次

你正在执行 Java 编排的单元测试生成任务。只处理路径载荷中的一个 `batch_input_json`。一个 task 只包含一个 Java 顶层类型，一个 agent 只写或修复这个类的单元测试；当前 task 完成前不会启动下一个 agent。

## 输入

- 路径载荷会提供 `batch_input_json:`，先读取该 JSON。
- 以 `batch_input_json` 为本批次唯一任务边界，重点使用 `source_files`、`types`、`existing_test_files`、`target_test_files`、`docs`、`coverage`、`allowed_write_globs`。
- 文档缺失时不要扩大任务范围；按源码、已有测试和当前批次信息继续。
- 下面可能包含上一轮 Java 验收失败摘要；如果存在，优先针对失败项修复。

## 任务

- 为当前 `batch_input_json` 的一个顶层类型创建或修复单元测试。
- 只允许修改 `target_test_files` 或 `allowed_write_globs` 内的测试文件。
- 不要修改生产代码、构建脚本、配置文件或当前批次以外的测试文件。
- 不需要写 `summary_json` 或任何额外产物；Java 会在本轮结束后验收测试存在性、编译、运行和覆盖率。
- 最终只回复简短完成信息。
