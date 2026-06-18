---
name: git-code-contribution-report
description: Use when generating a Chinese Markdown report of developer code contribution volume from a Git repository for a user-provided start date and end date, including per-developer detail JSON files, per-developer sub-agent reports, changed file counts, comment-filtered line statistics, workload ranking, AI-authored analysis, and final report links.
compatibility: opencode
metadata:
  environment: windows
  python: "3.11"
  domain: git
  report_language: chinese
tools: intellij-idea,intellij-index
---

# Git Code Contribution Report

## Role Boundary

这是主 agent 编排 skill。统计准备脚本负责从 Git 采集事实、生成编排输入并预创建所有输出文件；子 agent 负责按人生成个人报告和质量发现项；主 agent 读取个人报告和质量发现项，调用脚本统一计算质量分，生成总报告、最终评分、排名、综合分析和链接。

脚本生成 `base_workload_score` 和初始 `workload_score`，初始二者相同。Java 证据包生成并归因 `quality-summary.json.findings[]` 后，主 agent 必须调用 `scripts\git_code_contribution_report.py score-quality` 统一计算质量调整，并把质量调整并入最终 `workload_score`。质量调整范围限制在 `[-30, 30]`。最终报告不得把行数、提交数或评分表述为绩效结论。

## MCP Workflow

在读取统计产物、派发子 agent、读取个人报告、质量分析取证或写入 Markdown/JSON 前，必须先读取 [workflows/mcp-tool-contract.md](workflows/mcp-tool-contract.md)。

MCP 工具命名和文件读写约束以该 workflow 为准。

## Detailed Workflows

按任务阶段读取对应 workflow：

- 运行统计脚本、确认统计口径、作者别名或输出文件时，读取 [workflows/statistics-preparation.md](workflows/statistics-preparation.md)。
- 派发子 agent、补跑失败人员或读取个人报告时，读取 [workflows/subagent-contract.md](workflows/subagent-contract.md)。
- 生成或合并质量结论、计算质量调整分时，读取 [workflows/quality-scoring.md](workflows/quality-scoring.md)。
- 写个人报告、总报告、表格、链接和排名分析时，读取 [workflows/report-writing.md](workflows/report-writing.md)。

## Required Workflow

1. 确认用户输入了开始日期和结束日期，格式为 `YYYY-MM-DD`。如果缺少任一日期，先询问用户。
2. 读取 MCP workflow，确认当前运行环境的文件读写和代码索引工具约束。
3. 读取统计准备 workflow，从目标 Git 仓库根目录运行统计准备脚本，不要切到 skill 安装目录作为工作目录。
4. 确认脚本已生成 `summary.json`、`index_inputs.json`、个人 detail、个人报告占位文件和质量摘要占位文件。
5. 按 MCP workflow 读取 `summary.json` 和 `index_inputs.json`。
6. 读取子 agent workflow，按 `index_inputs.json.tasks[]` 每个开发人员派发一个子 agent。
7. 每个子 agent 使用 `prompts\run-author-report.md`，只处理一个 `detail_json`，写一个 `person-report.md`，并在 Java 证据包已归因的基础上补全 `quality-summary.json` 的中文摘要和风险说明。
8. 读取质量评分 workflow，校验每个人的 `quality-summary.json`，并用 `scripts\git_code_contribution_report.py score-quality` 统一计算质量分。
9. 生成主报告之前，若发现某个人的个人报告或质量摘要缺失、仍只包含 marker、质量摘要仍为 `quality_summary_marker`、内容明显为空或写入失败，必须按子 agent workflow 补跑该人员一次；补跑校验仍未完成时，停止生成主报告并报告失败人员。
10. 读取报告写作 workflow，使用 `prompts\synthesize-report.md` 汇总个人报告、质量摘要、最终 `workload_score`、排名、分析和链接。
11. 写入最终 `code-contribution-report.md`，并保留统计口径、偏差和未完成项说明。

## Non-Negotiable Boundaries

- 统计准备脚本负责采集 Git 事实并预创建所有输出文件；agent 只读已生成输入并替换预创建 marker。
- 子 agent 一次只处理一个开发人员，不读取其他人员 detail，不生成总报告。
- 主 agent 不直接读取每个人的 detail 做个人分析；个人分析由子 agent 完成。
- 非开发文件不进入统计、个人质量结论或分数。
- 子 agent 不得写入最终质量分；质量调整必须由主 agent 调用统一评分脚本计算后并入最终 `workload_score`，但最终报告不得把行数、提交数或评分表述为绩效结论。
- 个人报告链接必须使用脚本生成的相对 Markdown 链接字段，不写绝对路径。

## Templates And Prompts

- 子 agent prompt：`prompts\run-author-report.md`
- 主 agent 汇总 prompt：`prompts\synthesize-report.md`
- 个人报告模板：`templates\person-code-contribution-report.md`
- 总报告模板：`templates\code-contribution-report.md`

保留模板标题结构。没有数据的章节写“无”或“未发现”，不要删除章节。
