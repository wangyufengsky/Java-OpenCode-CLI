# Sm@rtESB XML 流程参考

## 准备脚本契约

准备脚本只输出供 agent 编排使用的 JSON 事实，不生成 Markdown 分析结论。所有最终 Markdown 报告必须使用中文书写，代码标识、路径、XML 标签、JSON 字段名、类名、方法名和 serviceId 保持原样。

Markdown 报告之间的链接必须使用相对路径：

- `document_path`：绝对路径，只用于 agent 写入文件。
- `document_link`：相对于 `<out>/index.md` 的报告链接，用于索引文档。
- `module_document_links`：相对于当前交易文档目录的模块文档链接，用于交易文档。
- 源码路径、XML 路径可以作为证据文本保留原样，但不要把绝对路径作为报告互链。

`summary.json`:

```json
{
  "service_identify": null,
  "service_identifies": [".../serviceIdentify-a.xml", ".../serviceIdentify-b.xml"],
  "xml_root": "...",
  "biz_root": "...",
  "java_root": "...",
  "out": "...",
  "mode": "8583",
  "raw_case_count": 3,
  "deduped_transaction_count": 2,
  "unique_module_count": 5,
  "batch_size": 10,
  "module_batch_count": 1,
  "module_batch_paths": [".../tasks/batches/module-batch-001.json"],
  "transaction_task_count": 1,
  "transaction_task_paths": [".../tasks/transaction-CaConsume.json"],
  "missing_references": [],
  "service_identify_structure": {
    "selected_mode": "8583",
    "file_count": 2,
    "channel_count": 2,
    "switch_count": 8,
    "selected_switch_count": 2,
    "selected_case_count": 3,
    "files": [
      {
        "service_identify": ".../serviceIdentify-a.xml",
        "root_tag": "channels",
        "channel_count": 1,
        "switch_count": 4,
        "selected_mode": "8583",
        "selected_switch_count": 1,
        "selected_case_count": 2
      }
    ]
  }
}
```

如果只传入一个入口，`summary.service_identify` 仍保留该路径；如果传入多个入口，`summary.service_identify` 为 `null`，以 `summary.service_identifies` 为准。

`index_inputs.json` 是主agent 生成最终索引的压缩输入。主agent 优先读取它，不读取完整 `transactions.json`、`modules.json`、XML 或 Java：

```json
{
  "summary": {},
  "service_identify_structure": {},
  "batch_size": 10,
  "module_batch_count": 1,
  "module_batch_paths": [".../tasks/batches/module-batch-001.json"],
  "transaction_task_count": 1,
  "transaction_task_paths": [".../tasks/transaction-CaConsume.json"],
  "transactions": [
    {
      "transaction_key": "CaConsume",
      "task_path": ".../tasks/transaction-CaConsume.json",
      "document_link": "transactions/CaConsume/analysis.md",
      "summary_link": "transactions/CaConsume/summary.json",
      "alias_count": 2,
      "module_count": 8,
      "module_service_ids": ["BaseConvert8583CUPS"],
      "flow_node_count": 20
    }
  ],
  "modules": [
    {
      "serviceId": "BaseConvert8583CUPS",
      "document_link": "modules/BaseConvert8583CUPS/analysis.md",
      "summary_link": "modules/BaseConvert8583CUPS/summary.json",
      "used_by_transactions": ["CaConsume"],
      "base_xml_candidate_count": 1,
      "biz_candidate_count": 0,
      "java_candidate_count": 1
    }
  ],
  "missing_references": []
}
```

主agent 查找模块或交易 summary 时，不要使用 `<out>/modules/*/summary.json`、`<out>/transactions/*/summary.json` 这类通配符。部分 agent 工具对绝对路径、Windows 盘符或工作目录下的 glob 支持不一致，可能返回 `No files`。应以 `index_inputs.json` 中的 `modules[].summary_link` 和 `transactions[].summary_link` 为准，按 `<out>/` + `summary_link` 拼出确定路径逐条读取。

`transactions.json`:

```json
[
  {
    "transaction_key": "CaConsume",
    "primary_case": {"attributes": {}, "text": ""},
    "aliases": [{"attributes": {}, "text": ""}],
    "transaction_ref": "CaConsumeCUPS2ECI",
    "transaction_match_ref": "CaConsume",
    "transaction_xml": ".../CaConsume.xml",
    "transaction_xml_candidates": [".../CaConsume.xml"],
    "rejected_transaction_xml_candidates": [],
    "document_path": ".../transactions/CaConsume/analysis.md",
    "document_link": "transactions/CaConsume/analysis.md",
    "summary_path": ".../transactions/CaConsume/summary.json",
    "summary_link": "transactions/CaConsume/summary.json",
    "task_path": ".../tasks/transaction-CaConsume.json",
    "module_service_ids": ["BaseConvert8583CUPS"],
    "module_document_links": {
      "BaseConvert8583CUPS": "../../modules/BaseConvert8583CUPS/analysis.md"
    },
    "flow_summary": {
      "nodes": [
        {
          "step": 1,
          "tag": "to",
          "xml_path": "/proxyEngine/route/to",
          "id": "ProxyBaseConvert8583CUPS1",
          "serviceId": "BaseConvert8583CUPS",
          "condition": "",
          "attributes": {}
        }
      ],
      "service_ids_in_order": ["BaseConvert8583CUPS"],
      "edges": [
        {"from": 1, "to": 2, "label": ""}
      ],
      "mermaid": "sequenceDiagram\n  participant ENTRY as 交易入口\n  participant FLOW as 交易编排\n  participant M1 as BaseConvert8583CUPS\n  ENTRY->>FLOW: 接收交易请求\n  FLOW->>M1: 调用 base\n  M1-->>FLOW: 返回处理结果",
      "mermaid_quality": "linear_draft",
      "mermaid_note": "脚本生成的是结构化草图；遇到复杂动态路由时，交易子Agent 必须结合节点说明核对。"
    },
    "mermaid_draft": "sequenceDiagram\n  participant ENTRY as 交易入口\n  participant FLOW as 交易编排\n  participant M1 as BaseConvert8583CUPS\n  ENTRY->>FLOW: 接收交易请求\n  FLOW->>M1: 调用 base\n  M1-->>FLOW: 返回处理结果"
  }
]
```

`modules.json`:

```json
[
  {
    "serviceId": "BaseConvert8583CUPS",
    "base_xml_candidates": [".../BaseConvert8583CUPS.xml"],
    "base_xml_summary": [
      {
        "path": ".../BaseConvert8583CUPS.xml",
        "root_tag": "base",
        "root_attributes": {},
        "java_hints": ["BaseConvert8583CUPS"]
      }
    ],
    "biz_candidates": [],
    "biz_summary": [],
    "java_candidates": [".../BaseConvert8583CUPS.java"],
    "document_path": ".../modules/BaseConvert8583CUPS/analysis.md",
    "document_link": "modules/BaseConvert8583CUPS/analysis.md",
    "summary_path": ".../modules/BaseConvert8583CUPS/summary.json",
    "summary_link": "modules/BaseConvert8583CUPS/summary.json",
    "used_by_transactions": ["CaConsume"]
  }
]
```

`tasks/batches/module-batch-*.json` 是模块批次子Agent的输入。默认每个模块批次最多 10 个任务；如果命令行传入 `--batch-size`，以命令行值为准。OpenCode 桌面版中子Agent不能再启动子Agent，因此模块批次子Agent读取 batch JSON 后，必须把其中每个 `task_path` 建成待办事项，并按待办顺序逐个执行模块任务；不要在模块批次子Agent内启动任何子Agent。

交易侧不生成 batch。每个 `tasks/transaction-*.json` 直接分配给一个单任务交易子Agent；一个交易子Agent只能接收一个 `task_json_path`。

模块批次示例：

```json
{
  "kind": "module",
  "batch_index": 1,
  "batch_size": 10,
  "task_count": 2,
  "task_paths": [
    ".../tasks/module-BaseConvert8583CUPS.json",
    ".../tasks/module-BaseConsumerTpCheck.json"
  ],
  "tasks": [
    {
      "serviceId": "BaseConvert8583CUPS",
      "task_path": ".../tasks/module-BaseConvert8583CUPS.json",
      "document_path": ".../modules/BaseConvert8583CUPS/analysis.md",
      "document_link": "modules/BaseConvert8583CUPS/analysis.md",
      "summary_path": ".../modules/BaseConvert8583CUPS/summary.json",
      "summary_link": "modules/BaseConvert8583CUPS/summary.json",
      "used_by_transactions": ["CaConsume"],
      "base_xml_candidate_count": 1,
      "biz_candidate_count": 0,
      "java_candidate_count": 1
    }
  ]
}
```

## Agent 执行顺序

主agent 必须先执行模块分析，再执行交易分析：

1. 准备脚本生成 `tasks/module-*.json`、`tasks/transaction-*.json` 和 `tasks/batches/module-batch-*.json`；不生成 `tasks/batches/transaction-batch-*.json`。重新生成时会清理旧的 `tasks/module-*.json`、`tasks/transaction-*.json` 和生成型 batch JSON，避免过期任务误导人工排查。
2. 主agent 为每个 `tasks/batches/module-batch-*.json` 启动一个模块批次子Agent，使用 `{skill_dir}/prompts/run-module-batch.md`。一个模块批次子Agent只接收一个模块 batch。
3. 模块批次子Agent读取自己的 batch JSON，从 `task_paths` 建立待办事项，按待办顺序逐个执行模块任务。每完成一个待办就检查该任务 JSON 的 `document_path` 和 `summary_path`；缺失时在当前批次子Agent内补写一次，不转交给其他子Agent。
4. 等所有模块批次完成，确认 `<out>/modules/<serviceId>/analysis.md` 和 `summary.json` 已生成或明确失败。
5. 主agent 按 `index_inputs.json.transaction_task_paths` 或 `summary.json.transaction_task_paths` 为每个交易任务启动一个单任务交易子Agent，使用 `{skill_dir}/prompts/rerun-single-transaction.md`。一个交易子Agent只接收一个交易 task；交易子Agent可以分窗口并发，建议每批 5-10 个，避免一次启动过多任务压垮调度。
6. 单任务交易子Agent只读取自己的 `task_json_path`，生成该任务 JSON 指定的 `document_path` 和 `summary_path`；缺失时在当前子Agent内补写一次，不转交给其他子Agent。
7. 交易子Agent只能复用已有模块文档，不能启动模块子Agent，也不能把模块 Java 代码分析内联写进交易文档。
8. 所有交易文档完成后，主agent 生成 `index.md`。

主agent 启动子Agent 或补跑子Agent 时必须使用 `{skill_dir}/prompts/` 目录中的固定 prompt。模块批次使用 `{skill_dir}/prompts/run-module-batch.md`，单任务模块补跑使用 `{skill_dir}/prompts/rerun-single-module.md`，单任务交易执行或补跑使用 `{skill_dir}/prompts/rerun-single-transaction.md`。只替换占位符，不要临场改写任务边界；不要给任何子Agent额外追加多个 batch 或多个 task。

只有主agent和模块批次子Agent可以读取 `tasks/batches/module-batch-*.json`。模块批次子Agent只能读取 prompt 指定的 `{batch_json_path}`；单任务交易子Agent和单任务补跑子Agent只能读取 prompt 指定的 `{task_json_path}`。子Agent 不应扫描未分配的 `tasks/batches/`，不应主动执行未分配的任务 JSON，也不能启动任何子Agent。

模板和参考文件路径都以 skill 目录为基准。主agent 启动子Agent 时必须传入 `{skill_dir}`，子Agent 读取模板时使用 `{skill_dir}/templates/module.md` 或 `{skill_dir}/templates/transaction.md`，不要在项目工作目录下查找 `templates/...`。

补跑类 prompt 也集中在 `{skill_dir}/prompts/` 目录中：

- `{skill_dir}/prompts/rerun-failed-batch.md`
- `{skill_dir}/prompts/run-module-batch.md`
- `{skill_dir}/prompts/rerun-single-transaction.md`
- `{skill_dir}/prompts/rerun-single-module.md`

子Agent 不能向用户提问，也不能等待用户输入。遇到信息不足、文件缺失、候选歧义、上下文过大、输出被截断或无法继续精确判断时，必须把问题写入对应 Markdown 文档和 `summary.json.risks_or_uncertainties`，然后基于现有证据继续完成当前任务。

## 子Agent 小结论契约

模块批次子Agent必须为 batch 内每个模块任务写各自任务输出目录下的 `analysis.md` 和同目录的 `summary.json`。单任务交易子Agent和单任务补跑子Agent也必须写对应任务的同样输出。主agent 最终只读取这些小型 `summary.json`，不读取完整 Markdown。

交易 `summary.json`：

```json
{
  "transaction_key": "CaConsume",
  "document_link": "transactions/CaConsume/analysis.md",
  "summary": "交易作用的中文一句话摘要",
  "primary_case": {},
  "alias_count": 1,
  "module_service_ids": ["BaseConvert8583CUPS"],
  "important_steps": ["中文步骤摘要"],
  "missing_modules": [],
  "risks_or_uncertainties": []
}
```

模块 `summary.json`：

```json
{
  "serviceId": "BaseConvert8583CUPS",
  "document_link": "modules/BaseConvert8583CUPS/analysis.md",
  "summary": "模块作用的中文一句话摘要",
  "inputs": ["中文入参摘要"],
  "variables": ["中文变量摘要"],
  "main_steps": ["中文主流程摘要"],
  "outputs": ["中文输出和副作用摘要"],
  "external_calls": ["中文外部依赖摘要"],
  "error_handling": ["中文异常、返回码和降级摘要"],
  "used_by_transactions": ["CaConsume"],
  "risks_or_uncertainties": []
}
```

## Markdown 输出契约

### 写入防截断规则

写入规则：

- 按章节、表格或 Mermaid 代码块组织 Markdown；不要在上下文中先组织完整长文再一次性写入文件。
- 禁止使用内置 write 工具、Python 写入脚本、单条 shell 命令、`cat > file`、`printf`、Bash heredoc、一次性 patch 或临时 chunk 文件写 Markdown。
- 子Agent只能使用 MCP writer 工具写文件：`smartesb_begin_markdown`、`smartesb_append_markdown`、`smartesb_write_summary_json`、`smartesb_finish_document`。如果客户端显示命名空间形式，使用 `mcp__smartesb-writer__<tool>`。
- 首次写入调用 `smartesb_begin_markdown(path=document_path, text=标题+简短概述+输入证据摘要, overwrite=true, seq="init")`，保证目标 Markdown 有效；`begin_markdown.text` 目标控制在 2KB 以下，禁止放完整文档或完整章节。
- 后续按章节调用 `smartesb_append_markdown(path=document_path, text=章节内容, seq=稳定序号)`。每个 `seq` 在同一文档内唯一且稳定；重试时同一 `seq` 会被跳过，避免重复追加。
- 常用 `seq`：`overview-001`、`locate-001`、`base-xml-001`、`biz-flow-001`、`inputs-001`、`variables-001`、`java-flow-001`、`detail-001`、`external-001`、`error-handling-001`、`outputs-001`、`mermaid-001`、`used-by-001`、`risks-001`、`summary-section-001`。同一章节拆分时递增后缀，例如 `java-flow-002`。
- 单次 Markdown chunk 目标控制在 5KB 以下，MCP writer 硬限制为 8KB；大表格按 30-60 行拆分；长流程、长节点说明、长 Java 分析按主题拆分。不要把完整文档一次性传给 MCP。
- Mermaid 流程图作为完整代码块写入一个 chunk；如果超过 5KB，缩短 participant 和消息标签后再写。MCP writer 会清理 activation 语法，但生成时仍必须使用普通 `->>` 和 `-->>`。
- 长章节开头写“本节较长，以下按内容块展开”，再分多个稳定 `seq` 追加。
- 接近上下文、工具输出或写入截断风险时，停止扩展正文，写入“已分析到的位置”和 `risks_or_uncertainties`，再生成字段完整的 `summary.json`。
- `summary.json` 保持小型化，只写结论摘要和关键列表，并使用 `smartesb_write_summary_json(path=summary_path, data={...}, kind="module"|"transaction"|"generic")` 写入。
- 每完成一个任务，调用 `smartesb_finish_document(document_path=document_path, summary_path=summary_path)`，确认 `document_path` 和 `summary_path` 存在，再处理下一个任务。
- `{skill_dir}/scripts/write_markdown_chunk.py` 和 `{skill_dir}/scripts/write_json_file.py` 只作为 MCP writer 不可用时的主agent应急 fallback；子Agent 不使用脚本 fallback。

最终索引必须使用 `{skill_dir}/templates/index.md`，并包含：

- `serviceIdentify.xml` 配置结构摘要。
- `mode="8583"` 筛选摘要。
- 原始 case 数和去重后交易数。
- 重复交易归并表。
- 交易摘要表，并链接到每个交易文档。
- 缺失或不确定引用。

配置结构摘要来自 `index_inputs.json.service_identify_structure`。索引中链接交易文档时使用交易任务中的 `document_link`，该链接相对于 `<out>/index.md` 所在目录。

交易文档必须使用 `{skill_dir}/templates/transaction.md`，并包含：

- 中文概述。
- 中文详细流程。
- Mermaid 流程图。
- 中文节点说明。
- 中文总结。
- 模块文档链接。

流程图必须面向阅读，而不是转储 XML 细节：

- Mermaid 源码必须是完整图定义，第一行必须是 `sequenceDiagram`。不要只写 participant、message 或节点片段。
- 交易流程图采用时序图风格：用 participant 表示交易入口、交易编排和关键 `serviceId`/base 模块；调用使用普通 `->>`，返回使用普通 `-->>`；条件分支使用 `alt`/`else`/`end`。
- participant 和消息标签必须短小，例如 `participant M1 as BaseConvert8583CUPS`、`FLOW->>M1: 调用 base`。
- 禁止使用 Mermaid activation 语法，包括 `->>+`、`-->>-`、`activate`、`deactivate`。分支内 activation 状态很容易不配对，导致 `Trying to inactivate an inactive participant` 等渲染错误。
- Mermaid 标签禁止写完整 XML 路径、完整 Java 包名、完整类路径、完整条件表达式或长 `id`。
- 完整 XML 路径、条件、`id`、`serviceId`、目标类名等细节放入“节点说明”表。
- `mermaid_draft` 只能作为拓扑草图使用，不能不加整理地原样粘贴到交易文档；如果草图标签过长，交易子Agent 必须根据 `flow_summary.nodes` 和 `flow_summary.edges` 重写短标签时序图。
- 交易模板中的 `{mermaid_source}` 对应完整 Mermaid 源码，优先取 `flow_summary.mermaid`。如果旧任务 JSON 中的 `mermaid_draft` 或 `flow_summary.mermaid` 不以 `sequenceDiagram` 开头，交易子Agent 必须按时序图重写完整 Mermaid。
- 如果 Markdown 查看器不渲染 Mermaid，也必须保证代码块本身短小可读。

交易文档中的模块链接必须使用任务 JSON 里的 `module_document_links[serviceId]`，该链接相对于当前交易文档所在目录。如果同一个 `serviceId` 在交易中出现多次，每次都链接同一个模块文档；如果模块文档缺失，在交易文档中标记缺失，不要内联分析模块 Java 代码。

模块文档必须使用 `{skill_dir}/templates/module.md`，并用中文说明：

- 模块职责概述。
- 输入证据、base XML、biz 小流程和 Java 候选证据。
- 定位与消歧结论。
- base XML 配置摘要。
- biz 小流程 adapter 顺序、类型、自引用占位和 Java 线索。
- 入参、上下文变量、map/header/body/request/response/DTO/BO/VO/entity 读写。
- Java 主流程表：步骤、方法/位置、操作、关键读写、分支/条件、证据。
- 详细流程说明。
- 外部依赖：Mapper/DAO、数据库、远程服务、ESB/base 调用、缓存、文件、消息、工具类、配置读取。
- 异常、返回码与降级：catch/throw、错误码、空值处理、默认值、重试/跳过、forceExecute 或类似语义。
- 输出与副作用：返回对象、上下文写回、报文域写入、数据库写入、日志/审计/文件/消息副作用。
- Mermaid 流程图。
- 被交易使用。
- 候选缺失、候选歧义和其他风险。

模块 Mermaid 流程图规则：

- 模块子Agent 必须基于 base XML、biz 小流程和 IntelliJ-index MCP 返回的 Java 主流程证据生成模块级 Mermaid 时序图。
- Mermaid 源码必须是完整图定义，第一行必须是 `sequenceDiagram`，不能只写 participant 或 message 片段。
- Mermaid 必须采用“生命线 + 调用箭头 + alt/else 条件块”的形式；优先展示入口类/方法、上下文/变量读取、核心处理器、外部调用、异常/返回码处理、输出写回。
- participant 和消息标签使用短类名、短对象名、短方法名、短变量名、短调用目标或短条件。
- 调用只使用普通 `->>`，返回只使用普通 `-->>`；禁止使用 `->>+`、`-->>-`、`activate`、`deactivate` 等 activation 语法。
- 禁止把完整 Java 包名、完整方法体、完整路径、长条件表达式或大段代码塞进 Mermaid；完整细节写入“Java 主流程”和“详细说明”。
- 如果无法确认真实 Java 主流程，仍要生成最小 Mermaid，说明“候选缺失/流程未确认/需人工核对”，并把原因写入 `risks_or_uncertainties`。

模块文档必须用中文书写。Java 标识符和 XML/配置值保持原样。

## 提取规则

### serviceIdentify.xml

准备脚本支持一个或多个 `serviceIdentify.xml`。查找每个入口中 `mode` 等于请求模式的 `switch` 节点下所有 `case` 节点，默认模式为 `8583`。所有入口的 case 合并后再按交易去重，最终只为唯一交易生成一个交易任务。

每个 case 快照都会带上来源入口：

```json
{
  "ordinal": 1,
  "service_identify": ".../serviceIdentify-a.xml",
  "attributes": {},
  "text": ""
}
```

交易引用从 `case` 节点提取，优先检查以下明确引用属性：

- `target`
- `ref`
- `route`
- `service`
- `serviceId`

如果没有明确引用属性，则使用 `case` 文本。这里不能优先使用 `value`，因为 `value` 通常是 8583 报文匹配条件，而交易名经常写在 `case` 文本中。只有明确引用属性和文本都为空时，才依次回退到 `id`、`name`、`value`。

匹配交易 XML 前，需要先按项目命名规则归一化交易引用。如果引用以 `CUPS2ECI` 结尾，先去掉该后缀，再用剩余前缀匹配。例如 `CaInquiryBalanceCUPS2ECI` 匹配 `CaInquiryBalance.xml`。原始引用保存在 `transaction_ref`，匹配用值写入 `transaction_match_ref`。

重要字段区分：

- `transaction_ref`：来自 `serviceIdentify.xml` 的原始证据；子Agent 不能用它重新查找交易 XML。
- `transaction_match_ref`：准备脚本使用的归一化查找键。
- `transaction_xml`：准备脚本最终选定的权威交易流程文件。

交易子Agent 必须直接解析 `transaction_xml`。不要用 `transaction_ref` 重新做文件名发现，否则会反复查找 `CaInquiryBalanceCUPS2ECI.xml` 这类本来就不应该存在的后缀文件。

交易子Agent 优先使用任务 JSON 中的 `flow_summary`、`module_service_ids` 和 `module_document_links`。`mermaid_draft` 只作为拓扑草图参考，不能不加整理地原样粘贴。只有这些摘要缺失或明显错误时，才读取 `transaction_xml` 做最小范围补充。

`flow_summary.edges` 是脚本从 XML 层级推导出的边；`choice/when/otherwise` 会尽量生成分支边。`mermaid_draft` 使用 Mermaid `sequenceDiagram` 语法生成 Markdown 可渲染时序图，且必须是以 `sequenceDiagram` 开头的完整 Mermaid 源码：交易入口、交易编排和关键 `serviceId`/base 模块使用 participant 表示；base 调用使用普通 `->>`/`-->>` 往返箭头，不使用 activation 语法；`choice/when/otherwise` 尽量转换为 `alt`/`else`/`end` 条件块。`mermaid_draft` 仍然只是线性草图，不是运行时路径证明。交易子Agent 必须查看 `flow_summary.mermaid_quality` 和 `flow_summary.mermaid_note`：如果质量为 `linear_draft`、`missing` 或 `parse_error`，或节点中存在动态表达式/复杂分支，要在交易文档中说明流程图限制。流程图使用短 participant 和短消息标签；完整 XML 路径、完整条件和完整目标类名必须写入节点说明表，不要塞进 Mermaid。

准备脚本不依赖第三方 Mermaid Python 包。公开生态里有 `py2mermaid`、`mermaid-py`、`mergram` 等包可以用 Python 生成 Mermaid，但本 skill 为了减少安装和环境风险，直接用标准库生成 Mermaid 文本。

`flow_summary` 的所有分支都必须保持同一 schema。即使交易 XML 缺失或解析失败，也会包含 `nodes`、`edges`、`mermaid`、`mermaid_quality`、`mermaid_note`、`service_ids_in_order`。当前质量值包括：`linear_draft`、`missing`、`parse_error`。

交易去重唯一键优先级：

1. 已解析的交易 XML 路径。
2. 去掉 `CUPS2ECI` 等项目后缀后的交易配置名。
3. case 属性或文本中的明确交易标识。
4. 根据 case 属性和值生成的稳定兜底键。

重复 case 仍必须作为唯一交易的 `aliases` 写入 `transactions.json`，并在 `index.md` 中体现为复用同一个交易文档。重复 case 可能来自同一个入口，也可能来自不同 `serviceIdentify.xml`。

交易 XML 候选通过以下方式匹配：

- 如果引用本身以 `.xml` 结尾，先尝试精确路径。
- `<reference>.xml`。
- 文件名大小写不敏感匹配。
- 文件 basename 与引用按非字母数字归一化后相等。

只有包含 `<proxyEngine>` 元素的匹配 XML 才能视为交易流程配置。如果匹配到多个 XML，只把包含 `<proxyEngine>` 的文件放入 `transaction_xml_candidates`，其余放入 `rejected_transaction_xml_candidates`。如果所有匹配 XML 都没有 `<proxyEngine>`，则该交易 XML 视为未解析，并在 `missing_references` 中报告被拒绝的候选。

base 模块从交易 XML 的流程节点中提取：凡是 `flow_summary.nodes` 里带 `serviceId` 的节点都视为模块引用。常见来源是 `to` 节点，但 `process` 等流程节点上的 `serviceId` 也会被保留。同一个模块可以在流程中出现多次；`modules.json` 只保存唯一模块，交易文档中仍要保留重复出现的流程节点。

Java 候选通过以下方式匹配：

- 匹配 `<serviceId>.java`。
- 读取 base XML 中的 `class`、`clazz`、`className`、`impl`、`implementation`、`ref`、`service`、`bean`、`target` 等属性，再匹配简单类名。
- 如果没有直接 Java，读取同名或同 id 的 `.biz` 小流程文件；`.biz` 中每个 `adapter` 的 `id` 会作为 Java 类名线索继续匹配。
- 搜索包含 `serviceId` 字符串的 Java 文件。

`.biz` 文件可能不在 XML 根目录下。准备脚本支持 `--biz-root <path/to/biz-root>` 单独指定 biz 根目录；如果不传，默认使用 `--xml-root`。`summary.json.biz_root` 会记录实际使用的 biz 根目录。

`.biz` 小流程文件会写入 `biz_candidates` 和 `biz_summary`。`biz_summary.adapters[].id` 是 adapter 级 Java 候选的重要证据，模块子Agent 必须在模块文档中说明该模块是直接 Java 实现，还是由 biz 小流程串联多个 adapter Java 实现。

如果 `.biz` 中某个 `adapter/id` 等于该 `.biz` 自身的 `id`、`name`、`nickName` 或当前 `serviceId`，该 adapter 是自引用占位或默认节点，不进入 `adapter_java_hints`，也不进入 `java_candidates`。`biz_summary.adapters[].self_reference` 会标记这种节点，模块子Agent 不要据此递归分析同一个 biz。

模块子Agent 使用任务 JSON 中的 `base_xml_summary`、`base_xml_candidates`、`biz_summary`、`biz_candidates` 和 `java_candidates` 定位模块，但 Java 代码流程必须由模块子Agent 通过 IntelliJ-index MCP 工具取证后分析。准备脚本不生成 Java 源码切片，避免切片遗漏真实逻辑。

Java 分析工具规则：

- 调用 IntelliJ-index MCP 工具时，把用户项目工作目录作为 `project_path`，避免多项目打开时查错项目。
- 工具名统一使用 `intellij-index_...` 格式。
- 优先使用 `intellij-index_ide_find_class` 按类名定位 Java 类，例如 Controller、Service、Mapper、DAO、Task、Listener、Entity、DTO、BO、VO。
- 使用 `intellij-index_ide_find_file` 按文件名、通配符或 XML 文件定位候选，例如 `Application.java`、`application*.yml`、`*.xml`。
- 使用 `intellij-index_ide_read_file` 读取候选文件内容，用于分析 Java 主流程、配置、XML、mapper XML 或其他证据文件。
- `intellij-index_ide_read_file.file` 必须使用 IntelliJ-index 查找工具返回的 `file` 相对路径原文，不要删掉最前面的模块目录。若 `project_path` 指向父级工作区，例如 `D:/upfs/qianzhi`，而返回路径是 `upfs-cloud-xc/ECIS/src/.../BaseX.java`，读取时必须完整传 `upfs-cloud-xc/ECIS/src/.../BaseX.java`；传 `ECIS/src/.../BaseX.java` 会找不到文件。
- 如果 `ide_read_file` 报 `File not found`，先用相同 `project_path` 重新 `ide_find_file`，再把返回的 `file` 字段原样传给 `ide_read_file`；不要自己裁剪、拼接或归一化掉首段目录。
- 需要一次查找多种关键文件时使用 `intellij-index_ide_find_key_file`，例如同时查找 `Application.java`、`application-*.yml`、关键 mapper XML。
- 按命名风格递进查找，不同项目命名可能不同：先查更贴近 `serviceId` 的类，再查常见后缀如 Controller、Service、Mapper、DAO、Task、Listener、Entity、DTO、BO、VO。
- 不要优先用 shell/grep/rg 读取 Java 源码。只有 IntelliJ-index MCP 工具不可用、候选缺失、返回内容不足或需要核对非 Java 文件时，才做最小范围文件读取，并把原因写入 `risks_or_uncertainties`。

若 `java_candidates` 有多个候选，模块子Agent 必须结合 base XML、biz 小流程、类名、包名和 IntelliJ-index MCP 返回的源码证据判断；无法唯一确定时，在中文报告中说明歧义。

## 歧义处理策略

不要在多个合理候选之间静默选择。生成的任务 JSON 必须列出所有候选。分析 agent 只有在 XML 或 Java 代码提供明确证据时，才可以选择其中一个候选；否则必须在中文报告中说明不确定性。
