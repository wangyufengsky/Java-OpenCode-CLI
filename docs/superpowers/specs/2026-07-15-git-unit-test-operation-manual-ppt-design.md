# Git 与单元测试链路操作手册 PPT 设计说明

## 1. 目标与受众

制作一份可脱离现场讲解独立阅读的产品操作手册 PPT，面向两类读者：

- 技术管理者：快速理解产品价值、执行边界、闭环控制、过程可追踪性和最终产物。
- 开发与测试人员：能够按照页面说明完成配置、启动、观察、重跑和排错，并理解程序与 Agent 各自执行的内容。

全册先讲公共能力，再讲 `git-code-contribution-report`，最后讲 `project-unit-test-generation`。最终交付为本地 PowerPoint 文件，正文使用中文，代码、链路 ID、字段名、task 类型、prompt 文件名和产物文件名保留英文原文。

## 2. 核心叙事

本项目不是“一次提交 prompt、等待 Agent 回复”的脚本，而是由 Java 程序控制的有界闭环：

> 程序准备结构化任务 → Agent 执行受约束工作 → 程序检查真实产物 → 将失败原因反馈给下一轮 → 达标后进入下一阶段，或在达到上限时明确失败。

“loop”是全册的视觉和内容主线。每个 loop 都必须回答五个问题：

1. 循环处理的对象是什么；
2. 程序用什么条件验收；
3. 失败信息如何回到下一轮；
4. 最多循环多少次；
5. 成功、超时、越界修改或达到上限时如何退出。

首页后的概览页先建立“程序负责确定性、Agent 负责生成性、产物负责可验证性”的认知。后续两条业务链路沿用相同阅读顺序：

> 何时使用 → 页面怎么填 → 程序做什么 → Agent 做什么 → loop 如何收敛 → 生成什么 → 失败怎么办

## 3. 当前实现口径

PPT 内容以当前 `master` 源码和页面为准，不沿用已被后续重构替代的历史描述。

### 3.1 公共 AgentBridge loop

- Java 生成或读取 prompt，清理旧 session，然后通过 AgentBridge 提交任务。
- Java 轮询 Agent 状态，在 Agent 空闲后等待产物稳定，再运行 validation probe。
- validation probe 通过时，以真实输出达标作为完成依据，而不是仅依赖 Agent 的文字回复。
- 支持同一任务内的纠正 prompt；达到配置的纠正轮次、超时或验证通过时退出。
- 任务状态持续写入 `agent-status.json`，供产品页面展示和排查。

### 3.2 Git 报告的双层 loop

- 程序准备 Git 统计、作者明细输入和 task 索引。
- 每个作者对应一个分析 task，可按配置并发执行。
- 当前作者 task 本身不消耗同 session 纠正轮次；所有作者完成后，由产物完整性门统一检查作者报告和质量摘要。
- 完整性门只选择未达标作者补跑，最多补跑 5 轮；每轮状态与失败原因写入 `runs/incomplete-reports.json`。
- 所有作者产物达标后，程序计算质量评分并启动 synthesis task。
- synthesis 使用公共 AgentBridge 纠正 loop，默认最多纠正 2 轮；最终由 `FinalReportValidator` 验证 `code-contribution-report.md`。
- 手工重跑支持 `author` 和 `synthesis` 两种类型；作者重跑完成后仍会经过全量完整性门和最终综合。

### 3.3 单元测试生成的逐类 attempt loop

- 程序扫描目标源码并生成 `unit-test-plan.json`、`test-batches.json`；一个 batch 对应一个顶层类，batch 按顺序执行。
- 每个 batch 先做 precheck。已有测试且校验通过时直接接受，不启动 Agent。
- 未通过时进入 attempt loop；每轮把上一轮失败摘要写入新的 `attempt-NNN-prompt.md`，清理 session 后让 Agent 继续针对同一类编写或修正测试。
- Agent 返回后先检查受保护文件是否被越界修改；越界时立即失败，不继续尝试。
- Java 再通过 IDEA MCP 执行 `list_tests`、`get_compilation_errors`、`run_tests` 和 `read_run_output`。
- `test.require-coverage=false` 时使用 IDEA 测试运行结果验收；开启覆盖率后使用 Maven/JaCoCo 分支并校验阈值。
- 未通过时将新的失败摘要反馈到下一轮，最多执行 `agentbridge.max-attempts`，当前默认 5 次。
- 当前 batch 接受后才进入下一个类；达到上限后记录失败并生成汇总报告。
- 手工重跑支持 `test-batch` 和 `verification` 两种类型。

## 4. 幻灯片结构

全册 24 页，按“开场与公共能力 9 页 + Git 代码贡献报告 8 页 + 单元测试生成 7 页”组织；末页同时承担两条链路的速查对照。

### 4.1 开场与公共能力

1. **封面**：Git 与单元测试链路操作手册；副标题强调“程序控制、Agent 执行、产物验收、有界收敛”。
2. **如何阅读**：管理者与执行人员的阅读路径；三篇导航和关键术语。
3. **产品定位**：页面不是简单启动器，而是可观察、可纠偏、可重跑的 Agent 工作流控制台。
4. **产品页面地图**：Dashboard、New Run、Run Detail、History、Schedules 的职责与跳转关系。
5. **创建一次运行**：选择 chain、模式、运行日期、rerun 类型/ID、配置快照；解释配置快照不会直接改写仓库默认配置。
6. **在运行详情页观察过程**：run 状态、事件、task、Agent 状态、失败原因和产物入口；用真实页面截图标注。
7. **公共有界闭环**：准备、提交、轮询、验收、纠偏/补跑、收敛六步闭环；明确成功、失败和超时出口。
8. **task、prompt 与产物**：用一组真实文件展示三者关系，并区分 Java 与 Agent 的职责。
9. **三层 loop 与重跑边界**：任务内纠正、业务级选择性补跑、用户发起 rerun；强调它们不是无限重试。

### 4.2 Git 代码贡献报告

10. **何时使用 Git 报告**：统计范围、适用角色、输入和最终输出；说明报告强调证据而非只统计行数。
11. **页面与配置填写**：`project`、`paths`、`git`、`detail-input`、`synthesis-input` 的最小可运行示例。
12. **程序准备阶段**：采集提交、diff、changed regions，生成 `summary.json`、`index_inputs.json` 和作者 task。
13. **作者 task 与 worker prompt**：一个作者一个 task；展示 `worker-prompt.md` 摘要、输入文件和期望输出。
14. **Git 外层 completion loop**：并发作者 task → 全量完整性检查 → 只补跑未达标作者 → 最多 5 轮 → 达标后继续。
15. **综合报告内层 correction loop**：质量评分、`synthesis-inputs.json`、`synthesis-prompt.md`、最多 2 轮纠偏和最终报告校验。
16. **Git 产物示例**：作者报告、质量摘要、状态文件、完整性门记录、质量评分和最终 `code-contribution-report.md` 的目录树与内容截取。
17. **Git 重跑与排错**：`author`、`synthesis` 的选择方法；仓库路径错误、准备阶段耗时、作者产物缺失、综合报告不合格的处理方式。

### 4.3 项目单元测试生成

18. **何时使用单元测试生成**：目标、输入范围、默认写入边界、IDEA/AgentBridge 前置条件。
19. **页面与范围配置**：`project.repo`、`paths.out`、`source.package-paths`、include/exclude、覆盖率开关和最大尝试次数。
20. **程序拆分 task**：扫描 Maven 单/多模块项目，一个顶层类生成一个 batch，串行推进；展示 `unit-test-plan.json` 和 `test-batches.json`。
21. **Agent prompt 与写入边界**：展示 `attempt-NNN-prompt.md`，说明允许修改当前模块 `pom.xml` 与当前 batch 的 `src/test/**`，生产代码和其他模块受保护。
22. **逐类 attempt loop**：precheck → Agent 写测试 → 受保护文件检查 → IDEA MCP 验收 → 失败摘要进入下一轮 → 最多 5 次。
23. **IDEA MCP 验收与覆盖率分支**：`list_tests`、`get_compilation_errors`、`run_tests`、`read_run_output`；覆盖率开启时切换 Maven/JaCoCo。
24. **产物、重跑和速查表**：`agentbridge-results.json`、attempt 记录、最终报告；`test-batch` 与 `verification` 重跑；与 Git 链路的入口、loop、退出条件和核心产物对照。

## 5. 视觉设计

未提供外部模板，使用 Codex Grid 版式库作为构图参考，但不做密集仪表盘风格。

- 画布：16:9，浅色纸面为主，深墨色标题，绿色表示“通过/收敛”，橙色表示“反馈/重试”，红色仅表示终止失败。
- 字号：封面标题不小于 50pt，页标题不小于 35pt，小标题不小于 24pt，正文不小于 16pt。
- 主视觉：真实产品页面截图、真实 prompt/JSON/Markdown 产物截取、少量原生 PowerPoint 闭环图。
- loop 视觉语法：主流程从左到右，反馈箭头用橙色回到验收前节点；每个 loop 的右下角固定显示“验收条件 / 上限 / 退出条件”。
- 双层阅读：页面左上角提供一句管理视角结论，正文给出操作和实现证据；不使用“管理者版/开发者版”两套重复页面。
- 技术锚点：涉及实现的页面在页脚提供关键 Java 类或配置文件名，便于开发与测试人员追溯，但不展示长源码。
- 截图：优先从当前本地产品运行态采集 Dashboard、New Run、Run Detail 和 History；Figma baseline 仅用于核对页面视觉，不冒充真实运行截图。
- 示例：配置、prompt 和产物必须来自当前仓库或可复现的本地 fixture，敏感本机路径在可见内容中替换为中性示例路径。

## 6. 证据与素材

主要事实来源：

- 当前控制台页面与 `docs/figma-baselines/pages/**` 的设计基线；
- `README.md` 的链路配置和运行说明；
- `GitReportWorkflowChain`、`GitReportOrchestrator`、`OutputCompletionGate`、`GitReportSynthesisWorkflow`；
- `ProjectUnitTestGenerationWorkflowChain`、`ProjectUnitTestGenerationPreparation`、`ProjectUnitTestGenerationBatchRunner`；
- `AgentBridgeTaskRunner`、`AgentBridgeClient` 和相关 prompt pack；
- 当前链路 YAML、测试 fixture 及可安全生成的本地产物示例。

PPT 不依赖互联网资料，不添加无法由仓库或本地运行验证的产品能力。

## 7. 错误处理与边界表达

- 不把 Agent 回复“已完成”视为成功；成功必须由程序验收真实产物。
- 不把所有重试混写成一个 loop；区分任务内纠正、业务级补跑和用户手工 rerun。
- 不宣称 Git 作者 task 当前会在同 session 自动纠正；当前恢复策略是完成门选择性补跑。
- 不宣称单元测试默认强制覆盖率；默认关闭，开启后才走 JaCoCo 分支。
- 不把并发用于单元测试 batch；当前按类串行执行。
- 明确受保护文件越界修改属于立即终止条件，而不是继续试到最大次数。
- 截图中的路径、作者和项目名使用可公开的本地演示数据，避免展示隐私或凭据。

## 8. 验收标准

内容验收：

- 覆盖产品页面、使用方法、程序执行、Agent 执行、task、prompt 和产物示例。
- 先公共能力，再 Git 报告，再单元测试生成。
- 三类 loop 均清楚展示输入、校验、反馈、上限和退出条件。
- Git 和单元测试页面中的模式、task 类型、字段和产物名与当前代码一致。
- PPT 可独立阅读，不依赖讲者补充关键步骤。

视觉与文件验收：

- 使用 `@oai/artifact-tool` 生成 PPTX。
- 每页渲染并逐页检查，修复文字溢出、意外换行、重叠、裁剪和低清截图。
- 运行幻灯片边界检查，所有非预期 overlap 和 overflow 均清零。
- 最终文件保存到仓库约定的 `outputs/` 目录，临时素材保留在仓库外的演示文稿工作区。
