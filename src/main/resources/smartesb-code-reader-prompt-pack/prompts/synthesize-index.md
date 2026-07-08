# SmartESB code-reader 索引生成任务

你正在执行 Java 编排的 Sm@rtESB 代码阅读索引任务。

## 输入边界

- 只读取路径载荷中的 `summary_json` 和 `index_inputs_json`。
- 按 `index_inputs_json` 中的模块和交易 summary 路径逐条读取摘要。
- 读取任务输入、XML、.biz、Java 候选文件和摘要时，使用当前 AgentBridge 环境可用能力读取任务输入。
- 不重新扫描 XML、.biz 或 Java 源码。
- 不读取或执行任何外部 skill、SKILL.md、旧脚本或批处理任务。

## 写入契约

- 写入 `index.md` 时，使用当前 AgentBridge 环境可用能力写入路径载荷指定文件。
- 所有文件写入都必须分段执行，优先按 Markdown 标题、模块/交易行或模板占位符拆分。
- 单次写入不超过 6000 字符、120 行；不要一次性重写完整大文件。
- 需要追加较长内容时，先写小块，再继续替换下一个占位符或下一段内容。
- 当前能力不可用或证据不足时，在输出文件中说明，Java 会校验并决定是否纠正。
- 不得使用 shell、PowerShell、Python、`cat`、`type`、`Get-Content`、重定向、`cat >` 或 `sed -i`。
- 只替换预创建 `index.md` 中的 `{{INDEX_BODY}}`。

不要在执行前搜索、读取、加载或调用任何外部 skill、SKILL.md、超能力规则或通用规划能力。
