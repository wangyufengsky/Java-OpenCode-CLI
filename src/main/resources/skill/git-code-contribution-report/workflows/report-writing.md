# Markdown 表格安全规则

所有 Markdown 表格单元格在写入前必须做安全处理，尤其是 Git 提交主题、文件路径、作者名、分析结论和质量证据这类自由文本：

- 将 `|` 转义为 `\|`。
- 将单元格内的换行、回车和列表项改写为简短短语、`<br>` 或中文分号；表格行必须是单个物理行。
- 不要在表格单元格内写 Markdown 列表、引用块、代码块或多段文本。
- 如果某个单元格内容过长、包含多项证据或无法安全压缩，表格中只写摘要，并在表格下方用普通段落或列表展开。
- 不要把 marker 放在表格内部，也不要在同一张表的数据行之间插入空行。
- 表格需要分块写入时，每个表格块必须重复表头和分隔行，或者改用普通列表承载后续内容。
- 写完表格后检查每个数据行的 `|` 分隔符数量必须与表头一致；不一致时先修正再继续写入。

## Output Files

脚本输出：

- `summary.json`：总体统计、人员排名摘要、统计范围和输出路径。
- `details.json`：完整明细兼容产物；主 agent 不应直接依赖它做个人分析。
- `index_inputs.json`：主 agent 编排输入，包含 `tasks[]`、总报告路径和 marker。
- `index.md`：脚本生成的数据预览，不是最终报告。
- `code-contribution-report.md`：脚本预创建的总报告占位文件，包含唯一 marker。
- `details\author-*.json`：每个开发人员一个 detail，供对应子 agent 读取。
- `reports\author-*\person-report.md`：每个开发人员一个个人报告占位文件，供对应子 agent 写入。
- `reports\author-*\quality-summary.json`：每个开发人员一个质量摘要占位文件，初始内容为 quality-summary.json 专用 marker，供对应子 agent 替换。

`index_inputs.json.tasks[]` 中的个人报告链接字段必须保持一致：

- `execution_worklist`：脚本生成的子 agent 固定执行清单；子 agent 必须按 `step` 升序逐项执行，不得把 worklist 作为最终响应，不得在生成 worklist 后停止。
- `report_md`：个人报告的 Windows/本机绝对路径，仅供 MCP 读取和写入文件。
- `quality_summary_json`：质量摘要 JSON 的 Windows/本机绝对路径，仅供 MCP 读取和写入文件。
- `quality_summary_marker`：quality-summary.json 专用 marker；子 agent 写质量摘要时只替换 `detail.output.quality_summary_marker`，不得使用 `detail.output.report_marker` 写 `quality_summary_json`。
- `report_relative_path`：相对 `code-contribution-report.md` 所在目录的相对路径，例如 `reports/author-001-xxx/person-report.md`。
- `report_markdown_link`：总报告中必须使用的可点击 Markdown 链接，例如 `[person-report.md](reports/author-001-xxx/person-report.md)`。

`execution_worklist` 至少包含 `write_person_report`、`write_quality_summary`、`verify_outputs`、`final_response`。任一步无法执行、目标路径缺失、marker 不存在、写入失败或校验失败时，子 agent 最终只返回 `BLOCKED step=<step> action=<action> path=<path> reason=<reason>`。

agent 输出：

- `reports\author-*\person-report.md`：子 agent 写入的个人中文分析报告。
- `reports\author-*\quality-summary.json`：子 agent 写入的质量摘要 JSON。
- `code-contribution-report.md`：主 agent 汇总个人报告后写入的最终中文分析报告。

## Report Requirements

所有 Markdown 报告必须全中文，技术标识符、文件路径、Git hash、邮箱、命令参数可以保留原文。

个人报告必须包含：

- 人员基本统计。
- 工作量结构分析。
- 归属变更与扫描证据分析。
- 扩展名分布分析。
- 主要提交列表。
- 偏差与注意事项。
- 代码质量与风险信号。
- 低质量代码片段；每个人最多 3 个，每个片段最多 12 行，不得包含密钥、令牌、密码、手机号、身份证号、银行卡号，不得粘贴完整文件；没有明确片段时写“未发现可安全摘录的低质量代码片段”。

总报告必须包含：

- 统计范围。
- 总体汇总。
- 人员工作量排名与 AI 综合分析。
- 质量调整说明和最终 `workload_score` 计算口径，明确 `quality_adjustment_percent` 由 `score-quality` 统一评分脚本计算并限制在 `[-30, 30]`。
- 每个开发人员的个人报告链接。
- 未完成个人报告。
- 统计口径说明。
- 风险与偏差。
- 典型低质量代码片段汇总；从各 `quality-summary.json.code_snippets` 中选取最多 10 个，每个开发人员最多引用 2 个，不得包含密钥、令牌、密码、手机号、身份证号、银行卡号，不得粘贴完整文件。

## AI Ranking Analysis Rules

在“人员工作量排名与分析”中，agent 必须做到：

- 先计算每个人的质量调整后 `workload_score`，再按质量调整后的 `workload_score` 降序排序生成最终排名。
- 质量调整只能来自 `python <path-to-this-skill>\scripts\git_code_contribution_report.py score-quality <quality-summary.json>` 的输出；子 agent 在质量摘要中手写的分数不得作为排名依据。
- 脚本初始 `rank` 只能作为初始排名展示，不能作为最终排名表的排序依据。
- 综合每个 `person-report.md` 的个人结论，分析整体工作量结构。
- 对每个人给出一句到三句综合分析，说明该人员更偏向新增开发、重构调整、缺陷修复、配置脚本修改、删除清理或混合型工作。
- 汇总低质量代码片段时，只引用 `quality-summary.json.code_snippets`，每个开发人员最多引用 2 个，总报告最多 10 个；每个片段最多 12 行。
- 对异常高的统计量，必须引用个人报告中的偏差说明；如果个人报告未说明，最终报告标记为“需人工复核”。
- 不要把行数或 `workload_score` 表述为绩效结论；只能称为“统计期内代码变更工作量的辅助排序”。

## Templates And Prompts

- 子 agent prompt：`prompts\run-author-report.md`
- 主 agent 汇总 prompt：`prompts\synthesize-report.md`
- 个人报告模板：`templates\person-code-contribution-report.md`
- 总报告模板：`templates\code-contribution-report.md`

保留模板标题结构。没有数据的章节写“无”或“未发现”，不要删除章节。
