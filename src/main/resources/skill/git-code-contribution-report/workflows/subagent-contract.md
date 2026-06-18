# Sub-Agent Dispatch Procedure

主 agent 必须使用当前 OpenCode 客户端暴露的子 agent/Task 派发入口。若当前客户端没有可用子 agent 派发能力，停止并向用户报告“无法执行按人员拆分的代码提交量分析”，不要改成单 agent 串行读取所有个人 detail。

派发规则：

1. 以 `index_inputs.json.tasks[]` 为唯一任务列表，不扫描 `details\` 或 `reports\` 目录通配符。
2. 每个子 agent 只接收从一个任务对象中取出的以下输入：

```text
detail_json: <tasks[i].detail_json>
person_report_template: <path-to-this-skill>\templates\person-code-contribution-report.md
```

3. 子 agent prompt 必须是 `prompts\run-author-report.md` 的原文，再追加上面两行输入载荷。
4. 单批最多派发 5 个子 agent。
5. 派发前，主 agent 确认 `tasks[i].detail_json`、`tasks[i].report_md` 和 `tasks[i].quality_summary_json` 均已由脚本预创建；若缺失，重新运行统计准备脚本，不让子 agent 创建文件。
6. 子 agent 完成后，主 agent 按 `tasks[i].report_md` 和 `tasks[i].quality_summary_json` 的确定路径逐个读取个人报告和质量摘要，禁止使用通配符替代 `tasks[]`。
7. 个人报告缺失、仍含分析占位符、质量摘要缺失、内容明显为空或写入失败时，生成主报告之前必须只补跑该人员一次；补跑校验仍未完成时，停止生成主报告。

脚本会在 `tasks[i].execution_worklist` 和对应 `detail.execution_worklist` 中生成固定执行清单。主 agent 派发时不要求子 agent 重新生成 worklist；子 agent 必须按脚本生成的 worklist 执行。

子 agent 写入执行规则：

- 子 agent 读取 `detail.execution_worklist` 后，必须按 `step` 升序逐项执行。
- 不得把 worklist 作为最终响应，不得在生成 worklist 后停止。
- `replace_analysis_placeholders`、`verify_outputs`、`final_response` 是必经步骤。
- 如果 worklist 缺失、目标路径缺失、占位符不存在、写入失败或校验失败，最终只返回 `BLOCKED step=<step> action=<action> path=<path> reason=<reason>`。
- 禁止在写文件前输出进度说明。不得以 `Let me write`、`Now I will write`、`我将写入` 这类文本结束。
- 子 agent 分析完成后必须立即调用 MCP 写入工具，只写 `person-report.md`，不得写入 `quality-summary.json`。
- 不要等待主会话继续提示；OpenCode 子 agent 结束后主会话无法继续提示它。
- 只有确认 `person-report.md` 写入成功且 `quality-summary.json` 已存在后，最终响应只能是 `DONE` 或 `BLOCKED`。

## Sub-Agent Contract

每个子 agent 只接收一个 `detail_json` 路径，不接收全量 `summary.json`、`details.json`、`index_inputs.json` 或其他人员明细。子 agent 读取该 JSON 后，将其内容对象称为 `detail`。子 agent 必须：

- 按 MCP workflow 读取自己的 `detail_json` 和拆分证据文件。
- 分析该人员的提交数、文件修改次数、去重文件数、去注释新增/删除/净变更行、归属变更、扩展名分布、主要提交和扫描证据。
- 非开发文件已由脚本排除；子 agent 不得把 Markdown、Office、普通文档、媒体或归档文件作为质量正向或风险信号依据。
- 写入 `detail.output.person_report_md` 指定的文件。
- `quality-summary.json` 已由统计脚本预生成，不得写入 `detail.output.quality_summary_json`。
- 只替换 `detail.output.report_placeholders` 中列出的分析类占位符。
- 按 `detail.execution_worklist` 的 `step` 升序执行所有必经步骤；不得把 worklist 作为最终响应，不得在生成 worklist 后停止。
- 不创建、重命名、删除或移动任何文件。
- 不读取其他人员 detail 或报告。
- 不生成总报告。
- 分析完成后不得只输出计划或摘要；必须立即调用 MCP 写入工具完成个人报告写入。

如果 `person_report_md` 缺失，子 agent 返回 `BLOCKED` 并列出缺失路径；禁止调用文件创建工具或 shell 创建。

## Main-Agent Contract

主 agent 只能读取：

```text
summary.json
index_inputs.json
index_inputs.tasks[].report_md 指定的个人报告文件
index_inputs.tasks[].quality_summary_json 指定的质量摘要文件
templates\code-contribution-report.md
```

主 agent 必须：

- 读取 `index_inputs.tasks[].report_md` 指定的个人报告和 `index_inputs.tasks[].quality_summary_json` 指定的质量摘要。
- 生成主报告之前必须完成所有个人报告和质量摘要校验。
- 对每个 `index_inputs.tasks[].quality_summary_json` 调用统一评分脚本：`python <path-to-this-skill>\scripts\git_code_contribution_report.py score-quality <quality-summary.json>`。
- 忽略任何手写的 `quality_adjustment_percent` 或 `components[].score`；最终质量分只能来自统一评分脚本输出。
- 对每个人计算最终分：`workload_score = round(base_workload_score * (1 + quality_adjustment_percent / 100), 2)`。
- 先计算每个人的质量调整后 `workload_score`，再按质量调整后的 `workload_score` 降序排序生成最终排名。
- 脚本初始 `rank` 只能作为初始排名展示。
- 统一评分脚本输出的 `quality_adjustment_percent` 限制在 `[-30, 30]`。
- 基于个人报告生成综合分析、链接、未完成个人报告和风险偏差。
- 总报告中的个人报告链接必须使用 `index_inputs.tasks[].report_markdown_link`。
- 写入 `index_inputs.final_report` 指定的文件，并只替换 `index_inputs.final_report_marker`。

主 agent 不创建输出文件，不扫描目录通配符作为主依据，不用 shell 读取报告。
