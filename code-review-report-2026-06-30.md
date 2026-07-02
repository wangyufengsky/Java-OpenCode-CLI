# Java-OpenCode-CLI 项目代码审查报告（2026-06-30）

## 审查结论

本次审查基于当前本地 checkout：`master` 分支，领先 `origin/master` 1 个提交，工作区干净。当前提交为 `645b6c2 feat: add scheduled workflow console`。

整体判断：核心链路的自动化测试状态良好，近期已修复的 timeout abort、rename-only 统计等风险没有在当前审查中重新出现为开放问题。当前最高风险集中在新增定时任务控制台和仍未消除的默认配置可移植性上。

未发现 P0 级别问题。发现 1 个 P1、4 个 P2、2 个 P3。

## 验证范围

- 分支状态：`master...origin/master [ahead 1]`
- 工作区状态：干净，无未提交代码改动
- 重点审查目录：
  - `src/main/java/com/sonnet/wyf/gitreport/console`
  - `src/main/java/com/sonnet/wyf/gitreport/runner`
  - `src/main/java/com/sonnet/wyf/gitreport/opencode`
  - `src/main/java/com/sonnet/wyf/gitreport/workflow/weekly`
  - `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb`
  - `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesbreader`
  - `src/main/resources/chains`
  - `src/main/resources/static/app.js`
- 验证命令：
  - `mvn test`
  - `git diff --check`

## 验证结果

- `mvn test`：通过，`Tests run: 142, Failures: 0, Errors: 0, Skipped: 0`
- `git diff --check`：通过，无 whitespace/error 输出
- `git status --short --branch`：`## master...origin/master [ahead 1]`

## P1 发现

### P1-1 定时任务触发器会被单个失败任务永久打停

位置：

- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:51`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:72`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:77`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:91`

问题说明：

`WorkflowScheduleService` 使用 `scheduler.scheduleWithFixedDelay(() -> triggerDueSchedules(clock.instant()), 10, 30, TimeUnit.SECONDS)` 启动周期触发。`triggerDueSchedules` 把所有 due schedule 包在同一个 `try` 块中；任意一个 `submitter.submit(...)`、`nextTriggerAfterTrigger(...)` 或 `repository.markTriggered(...)` 抛异常后，方法会在 `catch` 中重新抛出 `IllegalStateException`。

对 `ScheduledExecutorService.scheduleWithFixedDelay` 来说，周期任务执行体只要抛出未捕获异常，后续执行会被抑制。结果是：一个坏配置、一次 SQLite 写入失败、一次 run config 写入失败，都可能让整个定时任务扫描线程停止，之后其他正常 schedule 也不会再触发。

影响：

- 一个失败 schedule 可拖垮全局调度。
- 失败 schedule 未 `markTriggered`，仍保持 due 状态；即便服务重启，也可能反复卡住第一轮扫描。
- 控制台页面只显示 schedule 仍启用，缺少调度器已停止的可见信号。

建议修复：

- 周期任务最外层必须吞掉并记录异常，不能让异常逃出 scheduled task。
- 对每个 schedule 单独 try/catch，失败时记录失败事件或失败状态，继续处理后续 schedule。
- 对无法提交的 schedule，应推进下一次触发时间或引入失败退避字段，避免坏任务每 30 秒卡住队列。
- 增加测试覆盖：一个 due schedule 提交失败时，第二个 due schedule 仍会提交；`triggerDueSchedules` 不向调度线程抛出异常。

## P2 发现

### P2-1 classpath 默认链路配置仍指向 `/home/wangyufeng/...`，当前 macOS 环境不可直接运行

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

当前运行环境是 macOS，仓库路径在 `/Users/wangyufeng/...`。但 classpath 下四个链路默认 YAML 仍大量使用 `/home/wangyufeng/...`。新增控制台通过 `/api/chains/{chainId}/defaults` 读取这些默认值，并由前端展示、提交。因此这不是单纯示例文档问题，而是“一键运行”和“定时任务默认配置”的真实输入。

影响：

- 控制台默认值提交后会直接生成不可用的运行 YAML。
- SmartESB 链路会把 OpenCode session directory 指向不存在的新项目根目录。
- weekly/git-report 链路会统计不存在的仓库或写入不存在/不期望的输出目录。
- 用户容易误判为 OpenCode 或链路逻辑失败，而实际是默认配置不可移植。

建议修复：

- 把 classpath 默认配置改成本仓库可运行的相对路径、占位路径，或移出为明确的 `application-example` 示例。
- 控制台默认配置应区分“示例值”和“当前机器可执行值”；不可执行路径需要在 UI/API 层标记。
- 对必填路径增加启动前校验，错误信息直接指出不存在的路径和配置字段。

### P2-2 recurring schedule 会固化运行日期和统计窗口，容易长期重复跑旧周期

位置：

- `src/main/resources/static/app.js:176`
- `src/main/resources/static/app.js:219`
- `src/main/resources/static/app.js:265`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:78`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:83`
- `src/main/resources/chains/weekly-engineering-report.yml:11`
- `src/main/resources/chains/weekly-engineering-report.yml:12`

问题说明：

前端 `collectConfig` 会收集当前链路表单中的全部字段并作为 `config` 提交。定时任务创建时，这些字段被保存到 `workflow_schedules.config_json`，后续每次触发都原样传入 `WorkflowRunSubmission`。

weekly 默认 YAML 当前包含固定 `startday: 2026-06-19`、`endday: 2026-06-26`。如果用户创建“每周”定时任务但没有手工清空/改写这些字段，每周都会继续跑 2026-06-19 到 2026-06-26 这一个窗口。类似地，表单上的 `runDate` 也会被持久化；如果填写了运行日期，日/周 recurring schedule 每次触发都会使用同一天。

影响：

- 周报定时任务最容易产出“任务成功但内容是旧周”的结果。
- SmartESB 按日期读取 `transactions.yml`，固定 `runDate` 会让 recurring schedule 一直读同一天的交易计划。
- 由于链路层本身支持 `runDate == null` 时取当前日期，问题主要来自控制台持久化了本应动态计算的日期/窗口。

建议修复：

- 对 `daily` / `weekly` schedule，不要默认持久化 `runDate`、`startday`、`endday`；应支持“触发时计算当前运行日期/当前统计窗口”。
- UI 上把一次性运行日期和 recurring 的动态日期策略分开，避免同一个 `runDate` 字段用于两种语义。
- weekly 链路的默认 classpath YAML 不应带固定过期窗口；固定窗口适合一次性示例，不适合作为控制台默认值。

### P2-3 weekly OpenCode session 没有纳入托管 session 清理前缀

位置：

- `src/main/java/com/sonnet/wyf/gitreport/workflow/weekly/WeeklyOpenCodeReviewRunner.java:101`
- `src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerTaskRunner.java:172`
- `src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerTaskRunner.java:193`
- `src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerTaskRunner.java:197`
- `src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerTaskRunner.java:200`
- `src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerTaskRunner.java:203`

问题说明：

weekly 代码审查创建的 OpenCode session title 是 `weekly-code-review-` 加 batch id。但 `OpenCodeServerTaskRunner.managedSessionTitlePrefixes(...)` 只识别：

- `smartesb-review-`
- `smartesb-reader-`
- `git-report-`

因此 weekly review sessions 不会触发 `deleteSessionsByTitlePrefixes(...)` 的托管 session 清理逻辑。这个行为和现有 SmartESB/git-report 的运行边界不一致。

影响：

- weekly review 批次重跑或反复运行时，旧 session 会持续留在 OpenCode server 侧。
- 如果 OpenCode UI/API 按目录和 title prefix 展示/恢复 session，weekly 会更容易受到旧会话干扰。
- 当前测试只覆盖了 `smartesb-review-` 等前缀，没有覆盖 `weekly-code-review-`。

建议修复：

- 在 `managedSessionTitlePrefixes` 中加入 `weekly-code-review-`。
- 增加测试：weekly title 会触发 prefix cleanup，且同一个 server/repo/prefix 范围只清理一次。
- 如果后续 weekly 增加 synthesis session，也应同步纳入前缀规则。

### P2-4 控制台配置提交总是写新 YAML，改变了“使用 classpath 默认 YAML”的语义

位置：

- `src/main/resources/static/app.js:176`
- `src/main/resources/static/app.js:179`
- `src/main/resources/static/app.js:219`
- `src/main/resources/static/app.js:265`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowExecutionService.java:56`
- `src/main/java/com/sonnet/wyf/gitreport/console/RunConfigWriter.java:23`
- `src/main/java/com/sonnet/wyf/gitreport/console/RunConfigWriter.java:27`

问题说明：

`WorkflowExecutionService.submit` 只有在 `normalized.config().isEmpty()` 时才写入原始 default YAML。前端当前无论用户是否改过配置，都会通过 `collectConfig(...)` 提交所有表单字段，因此后端几乎总是走“写入表单生成 YAML”的路径，而不是复制 classpath 默认 YAML。

这会带来两个语义偏差：

- 未展示在前端字段定义里的 YAML 字段不会被复制到运行配置中，只能依赖 Java 属性默认值补齐。
- 注释、字段顺序、示例上下文、未来新增 YAML 字段都不会自动保留。

当前 weekly 的 `review.grouping.strategy` 恰好有 Java 默认值 `module-author-capacity`，所以没有立即改变行为；但这个模式对后续新增配置不稳。

建议修复：

- 前端只提交用户实际修改过的字段，或提交 `configMode=default` 让后端复制原始 YAML。
- 后端可以以 default YAML 为基础做 patch merge，而不是用表单字段重建完整配置。
- 给控制台增加回归测试：不修改表单时，`RunConfigWriter` 生成的配置应和 default YAML 语义一致，并保留未展示字段。

## P3 发现

### P3-1 schedule API 没有防止过去时间的一次性任务

位置：

- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:137`
- `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java:151`

问题说明：

`once` 类型只校验 `runAt != null`，没有校验 `runAt` 是否晚于当前时间。如果用户创建一个过去时间的一次性任务，`nextTriggerAt` 会直接落在过去，10 秒后的扫描会立即触发。

这个行为可能可以接受，但 UI 文案是“执行日期时间”，更符合直觉的行为是拒绝过去时间或明确展示“保存后将立即执行”。

建议修复：

- 如果产品语义是预约执行，拒绝过去时间。
- 如果产品语义允许补触发，UI 和 API 返回中明确提示会立即执行。

### P3-2 ConsoleMvcTest 使用固定 SQLite 文件路径，长期重复运行存在状态污染风险

位置：

- `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java:20`
- `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java:151`

问题说明：

`ConsoleMvcTest` 使用固定路径 `target/test-console/console-mvc.sqlite`，并且 `scheduleApiCreatesAndTogglesSchedules` 对 `/api/schedules/1/enabled` 做固定 ID 假设。当前本次 `mvn test` 通过，但这种测试依赖 target 目录的清洁程度和测试执行顺序。

影响：

- 本地重复运行、IDE 单测重跑或 CI 缓存 target 时，schedule id 可能不再是 1。
- 测试失败会表现为 404 或切换了错误 schedule，定位成本偏高。

建议修复：

- 使用 `@TempDir` 或每次测试前删除固定 SQLite 文件。
- 从创建接口响应中解析真实 id，再调用 `/api/schedules/{id}/enabled`。

## 已验证的正向信号

- OpenCode timeout abort 路径已有测试覆盖，本次测试日志显示 abort request 能成功发出。
- git rename-only 统计相关测试仍在全量测试中通过。
- SmartESB completion gate 相关用例覆盖了 incomplete output 多轮 rerun。
- weekly 输出校验已要求 `code-review.md` 和 `code-review-summary.json` 一起完整，避免只有 JSON 通过而 Markdown 缺失。
- 新增控制台的 repository/service/MVC 测试覆盖了基本提交流程、schedule 创建和启停。

## 建议修复顺序

1. 先修 P1：定时任务触发循环必须隔离单个 schedule 失败，并保证 scheduled task 不会因异常退出。
2. 再修 P2-1 / P2-2：拆分“示例配置”和“当前机器可执行配置”，同时避免 recurring schedule 固化旧日期窗口。
3. 再修 P2-3：把 weekly OpenCode session 纳入已有托管 session 清理合同。
4. 最后修 P2-4 / P3：完善控制台配置 merge 语义和测试隔离。

## 回归测试建议

- `WorkflowScheduleServiceTest`
  - 一个 due schedule 的 submit 抛异常时，另一个 due schedule 仍会被提交。
  - `triggerDueSchedules` 不把异常抛给 scheduled executor。
  - recurring schedule 未显式配置日期时，每次触发使用触发日计算 `runDate` 或保留 null 交给链路计算。
- `OpenCodeServerTaskRunnerTest`
  - `weekly-code-review-` title 会触发 managed session cleanup。
- `ConsoleMvcTest` / 前端集成测试
  - 未修改默认表单提交时，生成配置保留 default YAML 的未展示字段。
  - weekly recurring schedule 默认不固化过期 `startday/endday`。
- 配置 contract 测试
  - classpath 默认配置不得包含当前机器不存在的绝对路径，或必须标记为 example-only。
