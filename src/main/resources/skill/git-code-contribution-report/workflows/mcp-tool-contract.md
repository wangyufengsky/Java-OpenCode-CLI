# AgentBridge MCP 工具命名规范

本 workflow 规定 `git-code-contribution-report` 在 Windows/AgentBridge 运行环境中的 AgentBridge MCP 工具命名和文件读写约束。

AgentBridge 配置中的 MCP server 名必须包含：

```text
AgentBridge
intellij-db
```

在 prompt、子 agent 指令和报告执行规则中，文件读写、搜索定位和代码取证统一使用 AgentBridge 工具名，不再引用旧 IntelliJ namespace。

## 工具清单

- AgentBridge 文件读取：当前可用读取能力。
- AgentBridge 文件写入：当前可用写入能力、当前可用写入能力。
- AgentBridge 搜索/定位：`当前可用搜索能力`、`当前可用搜索能力`、`当前可用项目文件列表能力`、`当前可用目录浏览能力`。
- 数据库证据：当前客户端暴露的 `intellij-db_*` 工具。

只能使用上表列出的 MCP server 名和工具名；禁止使用旧 IntelliJ namespace，也不要把 `AgentBridge` 简写成 `idea-index`、`index` 或 `intellij_index`。

## 强制行为矩阵

除统计准备脚本外，读取文件和写报告必须走 AgentBridge 的 AgentBridge MCP 工具。对应 MCP 未暴露、调用失败或目标路径不受支持时，停止该步骤并向用户报告失败；不要扩大任务边界、PowerShell、Python 临时脚本或重定向读写报告。

统计准备脚本的 shell/PowerShell 权限只覆盖生成统计产物和预创建输出文件：

```text
summary.json
details.json
index_inputs.json
index.md
code-contribution-report.md
details\author-*.json
reports\author-*\person-report.md
reports\author-*\quality-summary.json
```

| 行为 | 强制 MCP | 规则 |
| --- | --- | --- |
| 读取准备脚本输出、模板和 prompt | 当前可用读取能力 | 主 agent 只读 `summary.json`、`index_inputs.json`、`index_inputs.tasks[].report_md` 指定的个人报告和 `index_inputs.tasks[].quality_summary_json` 指定的质量摘要；子 agent 只读输入的 `detail_json` 和个人报告模板，质量分析只用 `detail.changed_regions` 中的 hunk。禁止用 `cat`、`type`、`Get-Content` 或 Python 读取这些文件作为报告依据。 |
| 上下文探索 | AgentBridge `explore` | 优先使用 AgentBridge `explore` 做上下文探索，当前作者 session 只消费 `explore` 返回的短证据摘要、文件路径、符号名或调用点位置；`explore` 不得返回完整文件、大段源码或未压缩搜索结果。不得把完整文件中不属于 `detail.changed_regions` 的代码归因给该人员。 |
| 定位公共代码调用证据 | `当前可用搜索能力`、`当前可用搜索能力`、`当前可用项目文件列表能力`；候选调用点文件内容用 当前可用读取能力 读取 | 仅当判断公共代码、工具类代码复用价值时使用；优先用 AgentBridge 搜索/符号工具确认调用点，最多读取 5 个候选调用点文件。MCP 不可用或证据不足时写入 `unverified`，禁止改用 shell、grep、rg 或 Python 扫描全项目。 |
| 写个人报告和总报告 | 当前可用写入能力、当前可用写入能力 | 只替换脚本预创建文件中的 marker。 |
| 搜索已生成文件 | `当前可用项目文件列表能力`、`当前可用目录浏览能力`、`当前可用搜索能力` | 优先按 `index_inputs.json` 中的确定路径直接读取；只有路径异常或需要定位预创建文件时才搜索。 |
| 运行统计准备脚本 | shell / PowerShell | 仅用于执行 `scripts\git_code_contribution_report.py` 生成统计产物；调用 shell 必须带中文 `description`。 |

禁止创建未在准备脚本中预创建的输出文件。脚本已经预创建所有 JSON 和 Markdown 输出。

## Markdown 写入规则

个人报告和总报告都不要一次写入太多内容。所有 Markdown 写入必须分块执行：

- 单次写入不超过 6000 字符。
- 单次写入不超过 120 行。
- 大表格按人员、文件、扩展名或提交记录分批写入。
- 写中间块时，用 当前可用写入能力 将 exact marker 替换为“本次内容 + 原 marker”，保留同一个 marker 供下一块继续追加。
- 写最后一块时，再将 marker 替换为最后内容或移除 marker。
- 如果 MCP 写入失败、marker 不存在或目标文件不可写，立即停止并报告失败；禁止改用 shell、PowerShell、Python 临时脚本或重定向写文件。

## 子 agent 写入完成规则

- 禁止在写文件前输出进度说明。不得以 `Let me write`、`Now I will write`、`我将写入` 这类文本结束。
- 子 agent 分析完成后必须立即调用 MCP 写入工具；文本回复、计划、摘要或“准备写入”不算写入。
- 子 agent 必须先写 `quality-summary.json`，再写 `person-report.md`；个人报告中的质量与风险内容必须来自已写入的质量摘要证据。
- 子 agent 不要等待主会话继续提示；AgentBridge 子 agent 结束后主会话无法继续提示它。
- 只有确认 `quality-summary.json` 和 `person-report.md` 都写入成功后，最终响应只能是 `完成` 或 `无法完成`。
