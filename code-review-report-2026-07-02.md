# Java-OpenCode-CLI 项目代码审查报告（2026-07-02）

## 审查结论

本次审查基于当前本地 checkout：`master` 分支，与 `origin/master` 同步，工作区在审查前干净。当前提交为 `7ba6afa Tighten console rerun validation and OpenCode state handling`。

项目整体是一个 Spring Boot 4 / Java 21 应用，后端负责 OpenCode 工作流编排、Git 证据构建、SmartESB/weekly 链路和任务控制台；前端不是独立 Node 项目，而是 Thymeleaf 模板加原生静态资源，主要位于 `src/main/resources/templates` 和 `src/main/resources/static`。

整体判断：核心链路测试覆盖继续增强，`mvn test` 当前 150 个测试全通过；6 月 30 日报告中的 weekly OpenCode session 清理问题已在当前代码中修复并有回归测试。但控制台调度、默认配置可移植性、前端配置提交语义、SmartESB 输出目录冲突和 XML 安全解析仍是主要风险。

未发现 P0。发现 1 个 P1、5 个 P2、3 个 P3。

## 审查范围

- 后端 Java：`src/main/java/com/sonnet/wyf/gitreport`
- 控制台后端：`console`、`config`、`runner`
- OpenCode 编排：`opencode`、`orchestration`
- 业务链路：`workflow/weekly`、`workflow/smartesb`、`workflow/smartesbreader`、`workflow/gitreport`
- 前端资源：`src/main/resources/templates`、`src/main/resources/static/app.js`、`styles.css`
- 配置：`src/main/resources/application.yml`、`src/main/resources/chains/*.yml`
- 测试：`src/test/java/com/sonnet/wyf/gitreport`

## 验证结果

- `mvn test`：通过，`Tests run: 150, Failures: 0, Errors: 0, Skipped: 0`
- `git diff --check`：通过，无输出
- `git status --short --branch`：`## master...origin/master`
- 规模快照：后端 Java 99 个文件，测试 Java 39 个文件，前端模板/static 8 个文件

## P1 发现

### P1-1 定时任务触发循环仍会被单个失败任务打停

位置：

- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:50`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:71`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:76`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:90`

问题说明：

`WorkflowScheduleService` 通过 `scheduleWithFixedDelay` 定期调用 `triggerDueSchedules`。该方法把整批 due schedule 放在一个 `try` 中，任何一个 `submitter.submit(...)`、`nextTriggerAfterTrigger(...)` 或 `repository.markTriggered(...)` 抛异常，都会走到外层 `catch` 并重新抛出 `IllegalStateException`。

对 `ScheduledExecutorService.scheduleWithFixedDelay` 来说，周期任务执行体抛出未捕获异常后，后续调度会被抑制。结果是一个坏配置、一次 SQLite 写入失败或一次运行提交失败，都可能让整个定时扫描线程停止；后面的正常定时任务也不再触发。

当前测试覆盖了正常触发、禁用任务、一次性任务和非法配置，但没有覆盖“第一个 due schedule 失败时第二个 due schedule 仍触发”和“调度线程不抛出异常”的场景。

影响：

- 单个失败 schedule 可影响所有 schedule。
- 失败任务未推进 `next_trigger_at`，服务重启后仍可能反复卡住。
- UI 只展示 schedule 启用状态，用户无法直接看到后台触发器已停止。

建议修复：

- `scheduleWithFixedDelay` 的最外层 lambda 必须吞掉并记录异常，避免异常逃出调度线程。
- 对每个 due schedule 单独 `try/catch`，失败后记录失败事件或失败状态，再继续处理后续 schedule。
- 对持续失败的 schedule 引入失败退避、失败计数或自动暂停策略。
- 增加回归测试：一个 due schedule 的 submitter 抛异常时，后续 due schedule 仍被提交，且 `triggerDueSchedules` 不向外抛异常。

## P2 发现

### P2-1 classpath 默认链路配置仍绑定 `/home/wangyufeng/...`，当前 macOS 环境不可直接运行

位置：

- `src/main/resources/chains/git-code-contribution-report.yml:7`
- `src/main/resources/chains/git-code-contribution-report.yml:8`
- `src/main/resources/chains/smartesb-rewrite-code-review.yml:5`
- `src/main/resources/chains/smartesb-rewrite-code-review.yml:14`
- `src/main/resources/chains/smartesb-rewrite-code-review.yml:17`
- `src/main/resources/chains/smartesb-rewrite-code-review.yml:20`
- `src/main/resources/chains/smartesb-code-reader.yml:4`
- `src/main/resources/chains/smartesb-code-reader.yml:11`
- `src/main/resources/chains/smartesb-code-reader.yml:14`
- `src/main/resources/chains/smartesb-code-reader.yml:20`
- `src/main/resources/chains/weekly-engineering-report.yml:6`
- `src/main/resources/chains/weekly-engineering-report.yml:9`

问题说明：

当前实际仓库路径在 macOS 的 `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI`，但四个 classpath 默认链路 YAML 仍大量使用 Linux 绝对路径 `/home/wangyufeng/...`。控制台 `/api/chains/{chainId}/defaults` 直接读取这些值，前端再把它们渲染为默认表单值并提交。

这不是文档示例问题，而是控制台“一键运行”和“定时任务默认配置”的真实输入。

影响：

- 用户从控制台默认值直接提交会生成不可执行的运行 YAML。
- weekly/git-report 会统计不存在的仓库或写入不存在的输出目录。
- SmartESB 链路会把 OpenCode session directory 指向不存在的新项目根目录。
- 失败表象容易被误判为 OpenCode、Git 或链路逻辑问题。

建议修复：

- 将 classpath 默认配置改为仓库内可运行的相对路径、空占位或 `${user.home}`/应用配置派生值。
- 示例配置和可执行默认配置分离：`application-example.yml` 保留示例，控制台默认值提供当前机器可运行快照。
- 提交运行前做路径存在性校验，错误信息明确指出字段名和值。
- 在 `ConsoleMvcTest.chainDefaultsComeFromParsedYaml` 中不要固化 `/home/...`，改为断言默认值策略。

### P2-2 recurring schedule 会持久化运行日期和周报窗口，容易长期重复跑旧周期

位置：

- `src/main/resources/templates/schedules.html:68`
- `src/main/resources/static/app.js:65`
- `src/main/resources/static/app.js:66`
- `src/main/resources/static/app.js:333`
- `src/main/resources/static/app.js:334`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleRepository.java:50`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleRepository.java:51`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:82`
- `src/main/resources/chains/weekly-engineering-report.yml:11`
- `src/main/resources/chains/weekly-engineering-report.yml:12`

问题说明：

前端定时任务页面复用了“一次运行”的 `runDate` 和完整链路配置表单。创建 daily/weekly schedule 时，`app.js` 会把 `runDate` 以及 `collectConfig(...)` 的所有字段写入 `workflow_schedules`。后续每次触发时，`WorkflowScheduleService` 又把保存的 `schedule.runDate()` 和 `schedule.config()` 原样传入运行提交。

weekly 链路本身支持动态兜底：未配置 `startday/endday` 时可根据运行日期推导自然周。但当前默认 YAML 带有固定 `2026-06-19` 到 `2026-06-26`，前端又会把这两个字段持久化，所以 weekly recurring schedule 很容易每周重复跑同一个历史窗口。

影响：

- 周报定时任务显示成功，但内容可能一直是旧周。
- SmartESB daily schedule 如果保存了 `runDate`，也会一直读同一天的交易计划。
- 这个问题发生在前端表单、后端 schedule 持久化和链路日期兜底的交界处，单看任一层都不明显。

建议修复：

- 为 recurring schedule 增加日期策略字段，例如 `dynamic-current-day`、`dynamic-current-week`、`fixed`。
- daily/weekly 默认不要持久化 `runDate`、`startday`、`endday`；只有用户显式选择固定窗口才保存。
- weekly classpath 默认 YAML 不应包含过期固定窗口，固定窗口应放到示例配置或一次性运行模板里。
- 增加端到端测试：创建 weekly schedule 且未选择固定窗口时，触发运行传入的 `runDate/startday/endday` 为空或为动态计算结果。

### P2-3 控制台配置提交总是重建 YAML，改变了“使用 classpath 默认 YAML”的语义

位置：

- `src/main/resources/static/app.js:245`
- `src/main/resources/static/app.js:248`
- `src/main/resources/static/app.js:288`
- `src/main/resources/static/app.js:334`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowExecutionService.java:57`
- `src/main/java/com/sonnet/wyf/gitreport/console/RunConfigWriter.java:25`
- `src/main/java/com/sonnet/wyf/gitreport/console/RunConfigWriter.java:29`
- `src/main/java/com/sonnet/wyf/gitreport/console/RunConfigWriter.java:32`

问题说明：

后端设计上，`normalized.config().isEmpty()` 时会复制 classpath 默认 YAML；否则用表单传入的扁平字段重新生成 YAML。前端当前无论用户是否改动配置，都会 `collectConfig(...)` 收集页面上定义的全部字段并提交，因此后端几乎不会走“复制默认 YAML”的路径。

这会导致两个问题：

- 没有在 `chainConfigDefinitions` 中展示的 YAML 字段会从运行配置中消失，只能依赖 Java 类默认值补齐。
- YAML 注释、字段顺序、未来新增字段、示例上下文都不会保留。

当前 weekly 的 `review.grouping.strategy` 还能依赖 Java 默认值兜住，但这种模式对新增配置不稳。

建议修复：

- 前端追踪 dirty fields，只提交用户实际修改的字段；未改动时提交空 config 或 `configMode=default`。
- 后端以默认 YAML 为 base 做 patch merge，而不是从表单字段重建完整配置。
- 增加测试：不修改表单直接提交时，生成配置应保留 default YAML 中未展示字段的语义。

### P2-4 SmartESB rewrite 输出目录仍由 slug 唯一决定，交易/模块重名会互相覆盖

位置：

- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbReviewPreparation.java:78`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbReviewPreparation.java:132`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbReviewPreparation.java:133`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbReviewPreparation.java:135`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbReviewPreparation.java:136`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbReviewPreparation.java:201`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbReviewPreparation.java:205`

问题说明：

SmartESB rewrite 准备器对每个 review item 使用 `slugify(item.name())` 生成目录、任务 JSON 和报告路径。slug 只来自名称，不包含 `kind`、原始名称 hash 或序号。`slugify` 会把非字母数字、`-`、`_` 的字符折叠为 `-`，因此不同输入可能变成同一个 slug。

如果同一天计划中存在 `module Foo` 和 `transaction Foo`，或 `A/B`、`A B`、`A--B` 这类归一化后相同的名称，后写入的任务和报告会覆盖先写入的输出。

影响：

- 交易/模块审查任务丢失。
- 汇总输入中的任务列表看似完整，但多个任务可能指向同一物料路径。
- rerun 时更难定位，因为路径和名称不再一一对应。

建议修复：

- 目录和任务文件名至少包含 `kind + slug`；更稳妥是 `kind + ordinal + slug + shortHash(originalName)`。
- 准备阶段检测同一天计划内的输出路径冲突，发现冲突直接失败。
- 增加测试：交易和模块同名、不同名称归一化为同一 slug 时，不会覆盖或会给出明确错误。

### P2-5 SmartESB code-reader 也存在 slug 输出冲突风险

位置：

- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:182`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:183`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:189`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:203`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:214`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:215`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:220`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:241`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:523`

问题说明：

code-reader 的模块和交易任务同样使用 `slugify(...)` 生成目录和任务路径。虽然模块目录在 `modules/`、交易目录在 `transactions/` 下分开，任务 JSON 也有 `module-` / `transaction-` 前缀，但同一类型内部仍可能因 slug 归一化冲突覆盖。

例如两个 serviceId 或交易 key 只在特殊字符、大小写以外的符号组合上不同，就可能落到同一个 `modules/<slug>`、`transactions/<slug>` 或 `tasks/module-<slug>.json`。

影响：

- code-reader 任务数、索引和实际报告目录不一致。
- 某些模块/交易的分析模板被后一个对象覆盖。
- 汇总阶段可能错误链接到另一个对象的报告。

建议修复：

- slug 生成加入稳定短 hash 或原始 key 编号。
- 在写入前维护 `Set<Path>` 检测输出路径唯一性。
- 为 `SmartEsbCodeReaderPreparationTest` 增加 slug 冲突样例。

## P3 发现

### P3-1 code-reader XML 解析未显式关闭 DTD 和外部实体

位置：

- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:109`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:377`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:401`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:443`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:444`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader/SmartEsbCodeReaderPreparation.java:446`

问题说明：

`parseXml` 使用默认 `DocumentBuilderFactory.newInstance()`，只设置了 `setNamespaceAware(false)`，没有禁用 DOCTYPE、外部实体或 XInclude。当前输入多来自本地 SmartESB XML，不是公网入口，所以不是 P1/P2 级别安全事故；但作为可被用户配置路径指向的本地解析器，仍应硬化。

建议修复：

- 设置 `disallow-doctype-decl`、`external-general-entities=false`、`external-parameter-entities=false`、`load-external-dtd=false`。
- `factory.setXIncludeAware(false)`，`factory.setExpandEntityReferences(false)`。
- 增加测试：包含外部实体的 XML 不会读取本地文件，也不会解析外部 DTD。

### P3-2 SSE 连接无限超时且无心跳，长期运行控制台可能积累失效连接

位置：

- `src/main/java/com/sonnet/wyf/gitreport/console/EventStreamService.java:12`
- `src/main/java/com/sonnet/wyf/gitreport/console/EventStreamService.java:15`
- `src/main/java/com/sonnet/wyf/gitreport/console/EventStreamService.java:16`
- `src/main/java/com/sonnet/wyf/gitreport/console/EventStreamService.java:17`
- `src/main/java/com/sonnet/wyf/gitreport/console/EventStreamService.java:26`
- `src/main/java/com/sonnet/wyf/gitreport/console/EventStreamService.java:30`
- `src/main/resources/templates/run-detail.html:21`

问题说明：

`EventStreamService` 使用 `new SseEmitter(0L)`，表示无限超时。连接只在 completion、timeout、error 或下一次发送失败时移除。如果浏览器断线后该 run 不再产生事件，失效 emitter 可能长期留在 `emitters` map 中；空 list 也不会从 map 中移除。

影响有限，但长期使用控制台、频繁打开运行详情页时会积累内存对象。

建议修复：

- 使用有限超时并允许前端自动重连。
- 周期性发送 heartbeat comment，及时触发断线检测。
- `remove` 后如果 list 为空，从 `emitters` map 中删除 runId。
- 增加单元测试覆盖 emitter 清理。

### P3-3 once schedule 允许过去时间，语义不够明确

位置：

- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:137`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:149`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:154`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:159`
- `src/main/resources/templates/schedules.html:107`

问题说明：

`once` 任务只要求 `runAt != null`，不校验是否晚于当前时间。过去时间会让 `nextTriggerAt` 落在过去，下一次扫描时立即触发。

这可能是可接受的“补触发”语义，但 UI 文案是“执行日期时间”，用户更容易理解为预约未来执行。

建议修复：

- 如果产品定位是预约执行，拒绝过去时间。
- 如果允许补触发，前端和 API 错误/成功提示中明确说明“过去时间会保存后立即执行”。
- 增加测试锁定最终语义。

## 已复核的历史问题

- timeout 后不 abort OpenCode session：当前 `OpenCodeServerClient.abortSession(...)` 已有测试覆盖，未作为开放问题记录。
- git rename-only 统计遗漏：当前 `GitReportPreparationIntegrationTest` 包含 rename-only 场景，未作为开放问题记录。
- weekly OpenCode session 未纳入托管清理前缀：当前 `OpenCodeServerTaskRunner.managedSessionTitlePrefixes(...)` 已包含 `weekly-code-review-`，`OpenCodeServerTaskRunnerTest.clearsPriorWeeklyReviewSessionsBeforeCreatingFirstWeeklySession` 覆盖该行为，未作为开放问题记录。

## 优化建议

### 优先级 1：先稳住控制台调度边界

建议先修 P1-1。定时任务是后台入口，一旦调度线程停止，UI 很难直接暴露真实问题。修复时把 schedule 级失败隔离、失败状态记录和测试一起补上。

### 优先级 2：拆清“一次运行”和“周期运行”的配置语义

前端现在把同一个表单同时用于 one-shot run 和 recurring schedule，导致日期和窗口被错误固化。建议新增配置模式：

- 一次运行：允许固定 `runDate`、`startday/endday`。
- 周期运行：默认动态日期策略，只有显式选择固定窗口才保存固定日期。
- 默认配置：保留原始 YAML 或做 patch merge，不要无条件重建。

### 优先级 3：让默认配置可执行、示例配置可复制

将 classpath `chains/*.yml` 定位为“控制台可执行默认值”，把历史 `/home/...` 示例移到 README 或 `application-example.yml`。这样控制台的默认体验和 CLI 文档不会互相污染。

### 优先级 4：统一 SmartESB 输出标识契约

rewrite 和 code-reader 都应使用统一的 `safeOutputId(kind, originalName, ordinal)` 规则。该规则应明确：

- 人类可读 slug；
- 短 hash 防冲突；
- 同批次路径唯一性检测；
- task JSON、报告目录、rerun id 的对应关系。

### 优先级 5：补齐低成本安全和资源清理

XML 解析硬化、SSE 心跳/清理、once schedule 过去时间语义都属于低成本高确定性的质量提升，适合作为控制台稳定性专项的尾项一起做。

## 建议回归测试清单

- `WorkflowScheduleServiceTest`：单个 due schedule submit 失败不影响其他 due schedule。
- `WorkflowScheduleServiceTest`：失败 schedule 记录失败状态或推进退避后的 `next_trigger_at`。
- `ConsoleMvcTest` 或前端测试：未修改配置提交时后端复制默认 YAML 或保留默认 YAML 语义。
- `ConsoleMvcTest`：weekly recurring schedule 默认不持久化固定 `startday/endday`。
- `SmartEsbPreparationTest`：transaction/module 同名或 slug 冲突时不会覆盖输出。
- `SmartEsbCodeReaderPreparationTest`：同类型 slug 冲突时不会覆盖输出。
- `SmartEsbCodeReaderPreparationTest`：XML 外部实体不可解析。
- `EventStreamServiceTest`：SSE emitter 完成、失败、空列表后的 map 清理。
