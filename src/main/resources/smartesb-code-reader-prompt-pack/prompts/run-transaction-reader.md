# SmartESB code-reader 交易阅读任务

你正在执行 Java 编排的 Sm@rtESB 交易阅读任务。只处理路径载荷中的一个 `task_json_path`。

## 输入边界

- `review_type: transaction`
- 读取 task JSON 中的 `transaction_key`、`transaction_xml`、`module_service_ids`、`module_document_links`、`flow_summary`、`document_path`、`summary_path`。
- 交易文档可以复用已有模块文档和模块 summary。
- 读取任务输入、XML、.biz、Java 候选文件和摘要时，必须使用 `intellij-idea` MCP 文件读取工具：`intellij-idea_read_file` 或 `intellij-idea_get_file_text_by_path`。
- 不启动额外任务，不读取或执行任何外部 skill、SKILL.md、旧脚本或批处理任务。

## 写入契约

- 写入 Markdown 和 JSON 报告时，必须使用 `intellij-idea` MCP 文件编辑工具：`intellij-idea_replace_text_in_file` 或 `intellij-idea_replace_text_undoable`。
- `intellij-idea` MCP 读写工具不可用时必须返回 `BLOCKED`。
- 不得使用 shell、PowerShell、Python、`cat`、`type`、`Get-Content`、重定向、`cat >` 或 `sed -i`。
- 只替换预创建 `analysis.md` 中的 `{{TRANSACTION_ANALYSIS}}`，不要删除标题。

## 输出

`summary.json` 必须至少包含：

```json
{
  "transaction_key": "<transaction_key>",
  "status": "completed|partial",
  "risks_or_uncertainties": []
}
```

证据不足时写 `partial` 和 `risks_or_uncertainties`，不要跳过输出。

不要在执行前搜索、读取、加载或调用任何外部 skill、SKILL.md、超能力规则或通用规划能力。
