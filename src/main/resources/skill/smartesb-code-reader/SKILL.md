---
name: smartesb-code-reader
description: 从 serviceIdentify.xml、交易 XML、base XML、.biz 和 Java 源码生成 Sm@rtESB 8583 交易分层中文文档；主agent按一个模块batch或一个交易task对应一个子Agent分配任务。
compatibility: agentbridge
metadata:
  environment: cross-platform
  domain: smartesb
---

# Sm@rtESB 代码阅读

## 触发条件

请求包含从 `serviceIdentify.xml` 分析 Sm@rtESB 项目、生成 8583 交易流程文档或读取 `mode="8583"` 交易时，启用本 skill。

## 固定原则

- 使用两层编排：`主agent` 启动 `模块批次子Agent` 和 `单任务交易子Agent`。
- 主agent 分配任务的唯一单位是一个模块 batch 或一个交易 task；同一个子Agent不得接收多个模块 batch、多个交易 task，或 batch/task 混合任务。
- AgentBridge 桌面版中子Agent不能再启动子Agent；模块批次子Agent必须用待办事项顺序处理 batch 内任务，禁止 `子Agent -> 子Agent` 的嵌套派发。
- 交易侧取消 batch；交易子Agent一次只执行一个 `tasks/transaction-*.json`。
- 不要把多 agent 工作流降级为单次本地分析。
- 准备脚本只做事实收集和任务 JSON 生成；不要让准备脚本生成分析结论。
- 执行前读取 `{skill_dir}/references/xml-workflow.md`；数据契约、schema、提取规则、候选匹配、summary 字段和 Markdown 输出契约均以该文件为准。
- Markdown 报告使用中文。代码标识、路径、XML 标签、JSON 字段名、类名、方法名、serviceId 保持原样。
- Markdown 跨文档链接只使用相对路径。`document_path` 只用于写文件；报告链接使用 `document_link`、`summary_link`、`module_document_links`。

## 必要输入

- 一个或多个 `serviceIdentify.xml`。
- `--xml-root`：交易 XML 和 base XML 根目录。
- `--biz-root`：`.biz` 根目录；缺省时等于 `--xml-root`。
- `--java-root`：Java 源码根目录。
- `--out`：任务 JSON 和 Markdown 输出目录。

## 主agent 流程

所有命令从项目工作目录执行。`{skill_dir}` 指当前 skill 目录。

1. 读取 `{skill_dir}/references/xml-workflow.md`。

2. 运行准备脚本：

```bash
python <skill_dir>/scripts/prepare_smartesb_tasks.py \
  --service-identify <serviceIdentify-a.xml> <serviceIdentify-b.xml> \
  --xml-root <xml-root> \
  --biz-root <biz-root> \
  --java-root <java-root> \
  --out <out-dir> \
  --mode 8583 \
  --batch-size 10
```

等效重复传参：

```bash
python <skill_dir>/scripts/prepare_smartesb_tasks.py \
  --service-identify <serviceIdentify-a.xml> \
  --service-identify <serviceIdentify-b.xml> \
  --xml-root <xml-root> \
  --java-root <java-root> \
  --out <out-dir> \
  --mode 8583
```

`--service-identify a.xml b.xml` 与 `--service-identify a.xml --service-identify b.xml` 等效。不要把多个路径拼成逗号分隔的单个参数。

3. 启动并允许 Sm@rtESB writer MCP。推荐 server 名为 `smartesb-writer`，allowed root 使用 `<out-dir>`：

```bash
python <skill_dir>/scripts/smartesb_writer_mcp.py --root <out-dir>
```

MCP writer 暴露的写入工具是 `smartesb_begin_markdown`、`smartesb_append_markdown`、`smartesb_write_summary_json`、`smartesb_finish_document`。如果客户端显示命名空间形式，使用 `mcp__smartesb-writer__<tool>`。子Agent写文件必须使用这些 MCP 工具。

4. 只读取编排输入和子Agent摘要：

```text
summary.json
index_inputs.json
tasks/module-*.json
tasks/transaction-*.json
tasks/batches/module-batch-*.json
<out>/modules/<serviceId>/summary.json
<out>/transactions/<transaction-key>/summary.json
```

5. 不读取交易 XML、base XML、.biz 或 Java 源码。读取 summary 时按 `index_inputs.json[*].summary_link` 精确拼路径，不使用 glob。

6. 为每个 `tasks/batches/module-batch-*.json` 启动一个模块批次子Agent，使用 `{skill_dir}/prompts/run-module-batch.md`。一个模块批次子Agent只接收这一个 batch JSON，不额外分配其他 batch 或 task。模块批次子Agent必须把 batch 内每个 `task_path` 建成待办事项，并按待办顺序逐个执行；它不能再启动任何子Agent。

7. 等待所有模块批次子Agent完成。每个模块任务必须生成任务 JSON 指定的 `document_path` 和 `summary_path`。缺失时由主agent 使用 `{skill_dir}/prompts/rerun-single-module.md` 对该 `task_path` 补跑一次。

8. 模块批次全部完成后，按 `index_inputs.json.transaction_task_paths` 或 `summary.json.transaction_task_paths` 为每个 `tasks/transaction-*.json` 启动一个单任务交易子Agent，使用 `{skill_dir}/prompts/rerun-single-transaction.md`。一个交易子Agent只接收这一个 `task_json_path`，不额外分配其他交易 task 或任何 batch。交易子Agent可以分窗口并发，建议每批 5-10 个。交易任务已跨所有 `serviceIdentify.xml` 去重，不要为重复 case 重复执行。

9. 等待所有单任务交易子Agent完成。每个交易任务必须生成任务 JSON 指定的 `document_path` 和 `summary_path`。缺失时由主agent 使用 `{skill_dir}/prompts/rerun-single-transaction.md` 对该 `task_path` 补跑一次，补跑子Agent仍只接收这一个交易 task。

10. 使用 `index_inputs.json`、模块 `summary.json`、交易 `summary.json` 和 `{skill_dir}/templates/index.md` 生成 `<out>/index.md`。

## 模块批次子Agent 规则

- 只读取分配的一个模块批次 JSON。
- 从 `task_paths` 建立待办事项，每个待办对应一个模块任务 JSON。
- 按待办顺序逐个执行模块任务；不要并发处理同一批次内多个模块任务。
- 不启动任何子Agent。
- 每个模块任务都必须按 `{skill_dir}/templates/module.md` 输出完整模块文档，覆盖证据、定位、配置、Java 主流程、变量读写、外部依赖、异常/返回码、输出副作用、Mermaid 和风险。
- 每完成一个待办，立即检查该任务 JSON 指定的 `document_path` 和 `summary_path` 是否存在；缺失时在当前批次子Agent内补写一次，不把任务转交给其他子Agent。

全部模块批次必须先于交易任务运行。模块侧不读取、不写入交易文档。

## 单任务交易子Agent 规则

- 只读取分配的一个交易任务 JSON。
- 只执行这个 `task_json_path` 对应的交易任务；不要读取其他 `tasks/transaction-*.json` 或任何 `tasks/batches/*.json`。
- 不启动任何子Agent。
- 只能复用已有模块文档和模块 summary；不启动模块子Agent，不分析模块 Java 源码。
- 完成后立即检查该任务 JSON 指定的 `document_path` 和 `summary_path` 是否存在；缺失时在当前子Agent内补写一次，不把任务转交给其他子Agent。

## Prompt 路由

主agent 启动子Agent或补跑时，读取对应 prompt 文件，只替换花括号占位符；不要追加开放式要求。

| 使用场景 | Prompt 文件 |
| --- | --- |
| 模块批次子Agent按待办顺序处理一个模块批次 JSON | `{skill_dir}/prompts/run-module-batch.md` |
| 模块已生成后，主agent生成交易文档并生成 `index.md` | `{skill_dir}/prompts/run-transactions-and-index.md` |
| 交易子Agent执行或补跑一个交易任务 JSON | `{skill_dir}/prompts/rerun-single-transaction.md` |
| 模块子Agent补跑一个模块任务 JSON | `{skill_dir}/prompts/rerun-single-module.md` |
| 失败模块批次补跑子Agent按待办顺序处理一个失败模块批次 JSON | `{skill_dir}/prompts/rerun-failed-batch.md` |
| 只重新生成顶层 `index.md` | `{skill_dir}/prompts/regenerate-index.md` |

## 通用约束

- 子Agent 不提问，不等待输入。
- 信息不足、文件缺失、候选歧义、上下文不足或无法精确判断时，写入对应文档和 `summary.json.risks_or_uncertainties`，然后基于现有证据完成最小输出。
- 不因分析不完整跳过输出；每个任务至少写最小 `analysis.md` 和字段完整的 `summary.json`。
- 写 Markdown 时按 `{skill_dir}/references/xml-workflow.md` 的 MCP writer 规则执行：先调用 `smartesb_begin_markdown` 建立有效文件，再按章节调用 `smartesb_append_markdown`，每次使用稳定 `seq`。
- 写 `summary.json` 时必须调用 `smartesb_write_summary_json`；完成后调用 `smartesb_finish_document` 检查输出。子Agent 禁止使用内置 write 工具、Python 写入脚本、临时 chunk 文件、cat、printf、Bash heredoc 或一次性 patch 落盘。
- `{skill_dir}/scripts/write_markdown_chunk.py` 和 `{skill_dir}/scripts/write_json_file.py` 只作为 MCP writer 不可用时的主agent应急 fallback；子Agent 不使用这些脚本。
- Mermaid 时序图只使用普通 `->>` 和 `-->>` 箭头；禁止 `->>+`、`-->>-`、`activate`、`deactivate`，避免 activation 状态不配对导致流程图无法渲染。
- 只有主agent和模块批次子Agent可以读取 `tasks/batches/module-batch-*.json`；交易子Agent和单任务补跑子Agent不能读取 batch JSON。
- 子Agent 禁止启动任何子Agent。
- 模块批次子Agent必须用待办事项顺序执行 batch 内任务，完成一个待办后再进入下一个。
- 模块批次子Agent 只执行 prompt 指定的一个模块批次 JSON。
- 单任务模块子Agent 只执行 prompt 指定的一个模块任务 JSON。
- 单任务交易子Agent 只执行 prompt 指定的一个交易任务 JSON。
- 失败模块批次补跑子Agent 只执行 prompt 指定的一个失败模块批次 JSON，并用待办事项顺序处理其中的模块任务。
- 不生成、不读取、不分配 `tasks/batches/transaction-batch-*.json`。
- 不删除或覆盖其他 agent 的输出。
- 所有跨文档 Markdown 链接使用相对路径。

## 参考文件

- `{skill_dir}/references/xml-workflow.md`
- `{skill_dir}/templates/index.md`
- `{skill_dir}/templates/transaction.md`
- `{skill_dir}/templates/module.md`
