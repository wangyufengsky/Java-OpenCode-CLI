---
name: smartesb-rewrite-code-review
description: Use when reviewing SmartESB 8583-to-JSON rewrite equivalence for user-specified transactions in an OpenCode Windows IntelliJ environment.
compatibility: opencode
metadata:
  environment: windows
  domain: smartesb
tools: intellij-idea,intellij-index,intellij-db
---

# SmartESB Rewrite Code Review

## Role Boundary

这是主 agent 编排 skill。主 agent 不直接做完整代码审查，只准备用户指定交易的任务、派发每个交易的子 agent、读取子 agent 摘要并生成总索引。每个子 agent 只审查一个交易，按章节分块输出详细报告。

审查重点是老 SmartESB 8583 报文迁移到重构项目 JSON 报文后的字段映射、转换规则、业务处理、响应处理和异常处理是否等价。

## MCP 优先行为矩阵

除任务准备脚本外，审查行为必须走 opencode MCP 工具。对应 MCP 未暴露、调用失败或目标路径不受支持时，禁止使用 shell 或本地脚本；必须停止该步骤，并在可写报告时把原因记录到 `unverified`，无法写报告时直接向用户报告失败。

任务准备脚本的 shell 权限只覆盖生成编排产物和预创建输出文件：`summary.json`、`index_inputs.json`、`index.md`、`summary.md`、`tasks\transaction-*.json`、`reports\<transaction>\review.md`、`reports\<transaction>\mapping-matrix.md`、`reports\<transaction>\sections\*.md` 和 `reports\<transaction>\summary.json`。脚本只能写空壳、追加标记或 `{}` 占位，不得生成正式审查结论、字段矩阵、交易级摘要内容或任何源码证据摘录。

| 行为 | 优先 MCP | 规则 |
| --- | --- | --- |
| 定位重构项目代码 | `intellij-index_ide_find_class`、`intellij-index_ide_find_file`、`intellij-index_ide_find_key_file` | 先在 `new_project` 找入口、Service、Handler、Mapper、DTO、配置和测试。 |
| 读取源码和配置 | `intellij-index_ide_read_file` | 禁止用 `grep`、`cat`、`rg` 读取源码；MCP 不可用时该范围标记未验证。 |
| 定位老项目代码 | `intellij-index_ide_find_class`、`intellij-index_ide_find_file`、`intellij-index_ide_find_key_file` | 先按交易名、交易码、8583 域、服务标识、XML/biz/process 关键字搜索。 |
| 刷新索引 | `intellij-index_ide_sync_files` | 搜索不到刚生成或刚修改的文件时先同步，再重试一次。 |
| 写审查报告 | `intellij-idea_replace_text_undoable`、`intellij-idea_replace_text_in_file` | 只写准备脚本已预创建的文件；Markdown 小块写入；不要一次写完整长报告。 |
| 数据库/SQL 证据 | 当前客户端暴露的 `intellij-db_*` 工具 | 需要表结构、SQL、数据字典证据时优先使用；未暴露时记录未验证，不用 shell 强行连库。 |
| 运行准备脚本 | shell / PowerShell | 仅用于生成编排 JSON 和预创建报告/汇总占位文件；调用 shell 必须带中文 `description`。 |

禁止把源码获取、报告写入、索引同步、数据库证据获取交给 shell。shell 只允许用于任务准备脚本。

## Required Workflow

1. 如果用户没有提供交易名，先询问用户要审查哪些交易，不要自行补默认交易。
2. 从用户项目工作目录用 Windows PowerShell 运行准备脚本，不要切到 skill 安装目录执行。`--out` 必须是 Windows 绝对路径，例如 `D:\review-output\smartesb-20260612`，且该路径必须能被 IDEA MCP 写入。
3. 准备脚本必须预创建顶层 `index.md`、`summary.md`，以及每个交易的 `review.md`、`mapping-matrix.md`、`sections\01-findings.md`、`sections\02-code-chains.md`、`sections\03-protocol-review.md`、`sections\04-behavior-review.md`、`sections\05-verification.md`、`sections\06-code-standard.md` 和 `summary.json`。交易级文件是子 agent 唯一允许写入的文件。
4. 读取脚本生成的 `summary.json`、`index_inputs.json` 和 `tasks\transaction-*.json`。
5. 按 `index_inputs.json.tasks[].task_path` 每个交易派发一个子 agent。
6. 子 agent 必须使用 `prompts\run-transaction-review.md`，只处理一个交易。
7. 等待所有子 agent 写入 `reports\<transaction>\review.md` 和 `reports\<transaction>\summary.json`，并按 `schemas\transaction-summary.schema.json` 校验每个交易摘要。
8. 主 agent 使用 `prompts\synthesize-index.md` 生成顶层 `index.md` 和简要报告。

## Prepare Command

交易必须由用户输入。每个 `--transaction` 生成一个子 agent 任务；多个交易就重复传多次。

```powershell
python <path-to-this-skill>\scripts\prepare_rewrite_review_tasks.py `
  --out D:\review-output\<review-name> `
  --transaction <transaction-name>=<description>
```

示例：

```powershell
python <path-to-this-skill>\scripts\prepare_rewrite_review_tasks.py `
  --out D:\review-output\smartesb-rewrite-review `
  --transaction CaRolloutRepeal=转账撤销/冲正 `
  --transaction CaAcctInfoCheck=二三类账户信息验证
```

`--out` 是写入 task JSON 的真实报告路径，必须是 Windows 绝对路径，禁止传 `/tmp/...`、`./review`、`..\review` 这类 POSIX 或相对路径。只有在非 Windows 环境做脚本测试时才允许额外传 `--local-out <local-dir>`；此时脚本把 JSON 文件写入本地镜像目录，但 JSON 内的 `task_path`、`output.review_md`、`output.summary_json` 仍然保持 `--out` 指定的 Windows 路径。真实 opencode 审查不得依赖 `--local-out`。

默认项目链路：

```text
old project: D:\upfs\qianzhi\upfs-cloud-xc
new project: D:\upfs-nl-json
legacy index: D:\upfs-nl-json\doc\index.md
8583 doc: D:\upfs-nl-json\doc\docment\8583.md
json doc: D:\upfs-nl-json\doc\docment\json.md
mapping doc: D:\upfs-nl-json\doc\docment\8583 to json.md
reconstructed design: D:\upfs-nl-json\doc\docment\重构项目详细设计文档.md
```

## Sub-Agent Dispatch Procedure

主 agent 必须使用当前 opencode 客户端暴露的子 agent/Task 派发入口。若当前客户端没有可用的子 agent 派发能力，停止并向用户报告“无法执行多子 agent 审查”，不要改成单 agent 串行审查，也不要用 shell、本地脚本或后台进程模拟并发。

派发规则：

1. 以 `index_inputs.json.tasks` 为唯一任务列表，不扫描 `tasks\` 目录通配符。
2. 每个子 agent 只接收一个 `task_path`，不得接收完整 `index_inputs.json`、其他交易任务或其他交易报告。
3. 子 agent prompt 必须是 `prompts\run-transaction-review.md` 的原文，再追加下面两行输入载荷：

```text
task_json_path: <index_inputs.tasks[i].task_path>
summary_schema: <index_inputs.schemas.transaction_summary>
```

4. 一批最多派发 3-5 个交易子 agent；交易很多时分批等待，避免压垮 IntelliJ MCP 或上下文调度。
5. 派发前，主 agent 确认 `index_inputs.tasks[i]` 中的 `review_md`、`summary_json` 以及 task JSON 的 `output.*_md` 目标均已由准备脚本预创建；若缺失，重新运行准备脚本，不让子 agent 创建文件。
6. 子 agent 完成后，主 agent 按 `index_inputs.tasks[i].summary_json` 的确定路径逐个读取摘要，不使用 `reports\*\summary.json` 通配符作为主依据。
7. 摘要缺失、JSON 格式错误、schema 校验失败或 `status=failed` 时，只对该交易补跑一次，补跑 prompt 使用 `prompts\rerun-single-transaction.md`，输入同一个 `task_json_path` 和上一轮可读的 `summary.json` 或 `review.md` 路径。
8. 补跑后仍缺失或校验失败的交易，在顶层 `summary.md` 和 `index.md` 中标记为失败或未完成，不要静默忽略。

## Sub-Agent Contract

每个子 agent 只接收一个 task JSON，不接收全量任务列表。子 agent 必须：

- 先通过 `intellij-index_ide_find_class`、`intellij-index_ide_find_file`、`intellij-index_ide_find_key_file` 找重构项目交易代码。
- 再根据 `重构项目详细设计文档.md` 理解重构架构。
- 再通过 `intellij-index_ide_find_class`、`intellij-index_ide_find_file`、`intellij-index_ide_find_key_file` 找老 SmartESB 交易代码。
- 最后根据 `8583.md`、`json.md`、`8583 to json.md` 审查字段映射和处理逻辑。
- 写交易入口到 `reports\<transaction>\review.md`，详细报告写入 `sections\*.md` 和 `mapping-matrix.md`。
- 写机器可读摘要到 `reports\<transaction>\summary.json`。

子 agent 不生成顶层 `index.md`，不审查其他交易，不读取其他交易的报告。

## Main-Agent Contract

主 agent 只能读取：

```text
summary.json
index_inputs.json
tasks\transaction-*.json
schemas\transaction-summary.schema.json
reports\*\summary.json
```

主 agent 必须先按 `schemas\transaction-summary.schema.json` 校验每个交易摘要；只有某个子 agent 失败、摘要缺失、摘要格式错误或 schema 校验失败时，主 agent 才读取该交易的 `review.md`。主 agent 生成：

```text
index.md
summary.md
```

`index.md` 使用 `templates\index.md` 的结构。子 agent 的详细报告必须可从 `index.md` 跳转。

## Markdown Writing Rules

生成的 Markdown 不要一次写太多。子 agent 必须先写轻量 `review.md` 入口，再把详细内容拆到 `sections\*.md` 和 `mapping-matrix.md`。

每次写入 Markdown 必须使用小块：

- 单次写入不超过 6000 字符。
- 单次写入不超过 120 行。
- 大表格按 8583 域号、JSON 对象、finding 分组追加。
- 大段代码只引用文件和行号，不复制完整方法或完整类。

准备脚本已经创建所有交易级输出文件。子 agent 禁止创建、重命名、删除或移动输出文件，只能替换已存在文件里的占位内容：

- Markdown 文件已包含唯一追加标记；具体值以 task JSON 的 `output_markers` 为准。
- 子 agent 必须读取 task JSON 的 `output_markers`，使用对应文件的 exact marker；禁止按示例自行猜 marker。
- 用 `intellij-idea_replace_text_undoable` 把对应 marker 替换为“小块内容 + 原 marker”，实现分块追加。
- 每次追加仍然不超过 6000 字符和 120 行。
- `summary.json` 初始内容为 `{}`；子 agent 用 `intellij-idea_replace_text_in_file` 把整个 `{}` 替换为合法 JSON。

子 agent 不得调用 `intellij-idea_create_new_file`。如果任一目标文件缺失，子 agent 返回 `BLOCKED` 并列出缺失路径；主 agent 重新运行准备脚本后再派发。只有 IDEA MCP 替换文件内容不可用、输出目录不在 IDEA 项目内或 MCP 返回无法写入时，停止写入并报告失败；禁止使用 shell、本地脚本或临时重定向写报告。

只有运行 `prepare_rewrite_review_tasks.py` 准备任务时允许调用 Shell/Bash/PowerShell。调用时必须同时提供中文 `description` 和实际 `command`；禁止只传命令，否则会报 `SchemaError: Missing key: description`。审查过程、报告写入和源码读取不得使用 shell。

## 输出语言

所有 Markdown 报告必须使用中文，包括顶层 `index.md`、顶层 `summary.md`、交易入口 `review.md`、`sections\*.md` 和 `mapping-matrix.md`。

允许保留原文的内容仅限代码标识符、类名、方法名、文件路径、SQL 片段、8583 域号、JSON path、枚举值、协议字段名、工具名和原始错误码。除此之外，标题、表格列名、结论、影响、建议、验证方案都必须中文。

## Context Budget Rules

- 禁止一次性读取完整 `8583.md`、`json.md`、`8583 to json.md`、重构设计或全量源码。
- 文档只能按交易名、字段名、JSON path、8583 域号、关键词定向检索。
- MCP 搜索结果必须限制到当前交易，保留关键类、关键方法、关键调用链。
- 单个子 agent 超出上下文时，应先写已确认矩阵和未完成项到报告，再由主 agent 重新派发更小范围任务。

## Templates And Prompts

- 子 agent prompt: `prompts\run-transaction-review.md`
- 主 agent 汇总 prompt: `prompts\synthesize-index.md`
- 交易详细报告模板: `templates\transaction-review.md`
- 顶层索引模板: `templates\index.md`
- 交易摘要 schema: `schemas\transaction-summary.schema.json`

## Output Quality

问题发现必须放在报告最前面，按 `P0`、`P1`、`P2`、`P3` 排序。每条问题必须同时包含新代码位置、老代码位置、重构设计依据、协议依据、业务影响、建议修复和最小验证测试。

每个交易报告必须包含独立代码规范模块：`sections\06-code-standard.md`。该模块审查重构项目代码的分层职责、命名、方法复杂度、重复逻辑、常量/枚举管理、转换逻辑聚合、日志、异常、SQL、事务和注释质量。代码规范问题也要纳入问题发现；不改变行为的规范问题通常为 `P3`，可能影响交易正确性、映射完整性或排查效率时按影响升级。
