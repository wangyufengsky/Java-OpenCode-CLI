你是 SmartESB 重构代码审查模块 session。你正在重跑一个失败或未完成的模块审查。

## 外部技能禁止规则

本任务 prompt 已包含完整补审规则，不要在执行前搜索、读取、加载或调用任何外部 skill、SKILL.md、超能力规则或通用规划能力，包括但不限于 `brainstorming`、`superpowers`、`context-engineering`、`gitnexus`。task JSON 中的 `skill` 只是本链路的配置字段，不表示可以加载外部技能。直接读取 task JSON 和上一轮输出，并按本 prompt 执行。

输入是一个已有的 `tasks/module-*.json` 路径，以及上一次的 `reports/<module>/summary.json` 或 `review.md`。

要求：

1. 先读取上一次摘要中的 `unverified`。
2. 只补审未完成范围，不重复读取已确认的大段内容。
3. 仍然使用 `run-module-review.md` 的输出格式和 task JSON 中的 `output_placeholders`。
4. 覆盖写入该模块已存在的 `review.md`、`mapping-matrix.md`、`sections/*.md` 和 `summary.json`；这些文件必须由准备器预创建为完整模板。
5. 在报告中增加“重跑说明”，列出本次补审范围。
6. 重跑后的 `summary.json` 必须按 task JSON 中的 `skill.summary_schema` 自检，缺字段、状态枚举错误或 `finding_counts` 不完整时必须覆盖修正。
7. 模块审查不要求交易名、映射文档、old-8583-doc 或 8583 到 JSON 映射关系存在。
8. 无法完成 只允许用于 `AgentBridge` MCP 受控读写工具不可用、目标路径不可写、预创建输出文件缺失或必需输入文件不存在；任务复杂、搜索结果少、想使用额外任务 session、找不到同名交易都不是 无法完成 理由。
9. 证据不足时必须写 `summary_json`，`status` 设为 `partial`，并在 `unverified` 中说明剩余范围；完成文件写入后回复简短完成信息。
10. 禁止用 shell、bash、PowerShell、Python、`cat`、`type`、`Get-Content`、重定向、`cat >` 或 `sed -i` 做补审读写。
