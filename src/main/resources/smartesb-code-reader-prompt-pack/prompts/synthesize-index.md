# SmartESB code-reader 索引生成任务

你正在执行 Java 编排的 Sm@rtESB 代码阅读索引任务。

## 输入边界

- 只读取路径载荷中的 `summary_json` 和 `index_inputs_json`。
- 按 `index_inputs_json` 中的模块和交易 summary 路径逐条读取摘要。
- 不重新扫描 XML、.biz 或 Java 源码。
- 不读取或执行任何外部 skill、SKILL.md、旧脚本或批处理任务。

## 写入契约

- 写入 `index.md` 时，优先使用 OpenCode 原生文件编辑工具。
- 路径字段只能使用 `filePath`。
- 禁止使用 `pathInProject`、`file_path`、`path`。
- 如 OpenCode 原生文件编辑工具不可用，可使用 IntelliJ MCP 文件编辑工具。
- 两类受控编辑工具都不可用时必须返回 `BLOCKED`。
- 不得使用 shell、PowerShell、Python、`cat`、`type`、`Get-Content`、重定向、`cat >` 或 `sed -i`。
- 只替换预创建 `index.md` 中的 `{{INDEX_BODY}}`。

不要在执行前搜索、读取、加载或调用任何外部 skill、SKILL.md、超能力规则或通用规划能力。
