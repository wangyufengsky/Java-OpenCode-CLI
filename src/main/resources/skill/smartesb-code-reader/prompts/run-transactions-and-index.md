你是 Sm@rtESB 交易文档生成和索引生成的主agent。准备脚本已经跑完，模块批次子Agent也已经完成模块文档；你现在只启动单任务交易子Agent，并在交易完成后生成最终 index.md。

工作目录：{workspace}
Skill目录：{skill_dir}
输出根目录：{out_dir}

必须执行：
1. 读取 {out_dir}/summary.json 和 {out_dir}/index_inputs.json，确认 transaction_task_paths、module_batch_paths、transactions、modules 等摘要存在。
2. 不要使用 `{out_dir}/modules/*/summary.json` 这类通配符 glob。必须从 index_inputs.json 的 `modules[].summary_link` 逐条拼出确定路径：`{out_dir}/` + `summary_link`，再检查模块 summary 是否存在；模块 summary 缺失时只记录风险，不启动模块子Agent，不重新分析模块。
3. 按 {out_dir}/index_inputs.json 或 {out_dir}/summary.json 中的 transaction_task_paths，为每个交易任务启动一个单任务交易子Agent。
4. 每个交易子Agent必须使用 {skill_dir}/prompts/rerun-single-transaction.md，并且一个交易子Agent只处理一个 task_json_path。
5. 交易子Agent之间可以分窗口并发运行；建议每批启动 5-10 个交易子Agent，等待当前窗口完成后再启动下一批。不要把多个 transaction task 合并给同一个子Agent，也不要给交易子Agent分配任何 batch。
6. 等待所有单任务交易子Agent完成。
7. 检查每个交易任务 JSON 对应的 document_path 和 summary_path 是否已生成。
8. 如果某个交易任务的 document_path 或 summary_path 缺失，针对该 task_path 启动一个单任务交易子Agent补跑一次，使用 {skill_dir}/prompts/rerun-single-transaction.md。
9. 单任务补跑后仍失败的交易，不要向用户提问；记录 task_path、缺失的输出文件、失败原因和风险，稍后写入 index.md 的缺失或不确定引用部分。
10. 所有可完成交易处理完后，生成 {out_dir}/index.md。

生成 index.md 的输入：
- {out_dir}/summary.json
- {out_dir}/index_inputs.json
- index_inputs.json 中 `modules[].summary_link` 对应的模块 summary JSON；路径按 `{out_dir}/` + `summary_link` 精确读取，不使用 glob。
- index_inputs.json 中 `transactions[].summary_link` 对应的交易 summary JSON；路径按 `{out_dir}/` + `summary_link` 精确读取，不使用 glob。
- {skill_dir}/templates/index.md

index.md 生成规则：
- 用中文书写。
- 不要先在上下文里准备完整 index.md 后一次性写入；必须先写标题和总体摘要，再按章节组织配置结构、模块批次和交易任务摘要、重复交易归并表、交易摘要表、缺失或不确定引用。
- 禁止使用内置 write 工具、Python 写入脚本、单条 shell 命令、cat、printf、Bash heredoc、一次性 patch 或临时 chunk 文件写 index.md。
- 必须使用 MCP writer 写 index.md：先调用 `smartesb_begin_markdown(path="{out_dir}/index.md", text=标题+简短总体摘要, overwrite=true, seq="init")`，再按章节调用 `smartesb_append_markdown(path="{out_dir}/index.md", text=章节内容, seq=稳定序号)`。
- index 章节 seq 固定使用：`config-001`、`module-summary-001`、`duplicates-001`、`transactions-001`、`missing-001`。同一章节拆分时递增后缀，例如 `transactions-002`。
- `begin_markdown.text` 目标控制在 2KB 以下；`append_markdown.text` 目标控制在 5KB 以下，MCP writer 硬限制为 8KB；大表格按 30-60 行拆分。写完后调用 `smartesb_finish_document(document_path="{out_dir}/index.md")` 确认文件存在。
- 如果接近上下文或写入截断风险，立即停止扩展正文，在 index.md 中记录已汇总到的位置和剩余风险，不要阻塞已完成内容落盘。
- 主agent 不读取交易 XML、base XML、biz 文件或 Java 源码。
- 主agent 不读取完整 analysis.md，只读取小型 summary.json。
- 交易文档链接使用 index_inputs.json 或交易 summary.json 中相对于 {out_dir}/index.md 的 document_link。
- 模块文档链接使用 index_inputs.json 或模块 summary.json 中相对于 {out_dir}/index.md 的 document_link。
- 必须保留 serviceIdentify.xml 配置结构摘要、mode 筛选摘要、原始 case 数、去重后交易数、重复交易归并表、交易摘要表、缺失或不确定引用。
- 如果某个交易 summary.json 仍缺失，在 index.md 中标记为“交易文档未生成或补跑失败”，不要阻塞 index.md 生成。
- 如果某个模块 summary.json 缺失，在 index.md 中标记为“模块摘要缺失”，不要启动模块子Agent补跑。

边界：
- 不要运行准备脚本。
- 不要启动模块子Agent。
- 不要读取交易 XML、base XML、biz 文件或 Java 源码。
- 不要读取、扫描或分配 `tasks/batches/transaction-batch-*.json`。
- 不要让任何交易子Agent读取其他 task_json_path 或任何 batch_json_path。
- 不要让任何交易子Agent启动子Agent。
- 不要向用户提问，不要等待用户输入。
- 不要删除或覆盖模块文档。
- 只允许写交易任务自己的 document_path/summary_path，以及最终 {out_dir}/index.md。
- Markdown 中跨文档链接必须使用相对路径。
