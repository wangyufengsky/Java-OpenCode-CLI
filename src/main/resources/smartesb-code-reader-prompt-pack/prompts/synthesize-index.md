# SmartESB code-reader 索引生成任务

你正在执行 Java 编排的 Sm@rtESB 代码阅读索引任务。

## 输入边界

- 只读取路径载荷中的 `summary_json` 和 `index_inputs_json`。
- 按 `index_inputs_json` 中的模块和交易 summary 路径逐条读取摘要。
- 读取任务输入、XML、.biz、Java 候选文件和摘要时，必须使用 `intellij-idea` MCP 文件读取工具：`intellij-idea_read_file` 或 `intellij-idea_get_file_text_by_path`。
- 不重新扫描 XML、.biz 或 Java 源码。
- 不读取或执行任何外部 skill、SKILL.md、旧脚本或批处理任务。

## 写入契约

- 写入 `index.md` 时，必须使用 `intellij-idea` MCP 文件编辑工具：`intellij-idea_replace_text_in_file` 或 `intellij-idea_replace_text_undoable`。
- `intellij-idea` MCP 读写工具不可用时必须返回 `BLOCKED`。
- 不得使用 shell、PowerShell、Python、`cat`、`type`、`Get-Content`、重定向、`cat >` 或 `sed -i`。
- 只替换预创建 `index.md` 中的 `{{INDEX_BODY}}`。

不要在执行前搜索、读取、加载或调用任何外部 skill、SKILL.md、超能力规则或通用规划能力。
