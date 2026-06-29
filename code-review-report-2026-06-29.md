# Java-OpenCode-CLI 全项目代码审查报告

审查时间：2026-06-29  
审查分支：`codex/weekly-engineering-report`  
审查范围：当前工作区代码、链路 YAML、README、测试，以及未提交的 `weekly-engineering-report` 链路变更。

## 验证结论

- `mvn test`：通过，`Tests run: 108, Failures: 0, Errors: 0, Skipped: 0`。
- 当前工作区不是干净状态：`README.md`、`RunnerConfiguration.java` 有修改，新增了 `workflow/weekly`、`weekly-engineering-report.yml` 和对应测试文件。
- 本机实际项目路径为 `/Users/wangyufeng/IdeaProjects/...`；当前 classpath 链路配置里多处默认写死 `/home/wangyufeng/...`，这些路径在本机不存在。

## 总体判断

项目已经从单一 git-report 逐步演进成多链路 Runner：入口 `WorkflowRunner` 通过 `opencode-runner.active-chain` 分发，git-report、SmartESB rewrite、SmartESB code-reader 和新增 weekly 链路均挂到 Spring Bean。整体结构比早期单入口更清晰，测试覆盖也明显增加，尤其 OpenCode session 创建恢复、并发编排、SmartESB 完整性 gate、weekly 证据生成都有回归测试。

但当前仍有几类会影响真实运行的风险：

1. OpenCode 超时只记录状态，不会真正中止远端 session。
2. 默认 classpath 配置不可移植，在当前 macOS 环境下直接运行会失败。
3. Git rename-only commit 仍会被默认文件过滤误排除，贡献统计存在漏算。
4. SmartESB 输出目录基于 slug，逻辑名不同但 slug 相同的任务会互相覆盖。
5. 新增 weekly 链路报告口径是“Git 事实周报”，不是完整项目交付周报；当前文案和数据质量状态容易被误读为完整健康结论。

## 发现列表

### P1：OpenCode 任务超时后不会真正中止远端 session

证据：

- `src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerTaskRunner.java:299` 和 `:385` 在超时路径调用 `client.abortSession(...)`。
- `src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerClient.java:410-412` 中 `abortSession(...)` 直接 `return false`。
- `src/test/java/com/sonnet/wyf/gitreport/OpenCodeServerTaskRunnerTest.java:221-255` 的测试名和断言也说明当前行为是“recordsTimeoutWithoutAbortWhenUsingOpenCodeV2Api”，即 timeout 后 `aborted=false`。

影响：

- Java runner 认为任务已经超时失败，但 OpenCode 远端 session 可能继续运行、继续消耗模型资源、继续写文件。
- 后续 completion gate 或人工补跑可能与旧 session 并发写同一 report/output，导致状态文件显示失败但产物后来被旧 session 改写。
- 对 SmartESB 和 git-report 这种会按同一目录补跑的链路，问题会放大为输出不可解释：到底是新一轮补跑写的，还是旧 session 延迟写的，日志上很难区分。

建议：

- 如果当前 OpenCode API 没有 abort endpoint，应把这个限制显式升级为运行契约：timeout 后不要立即补跑同一 output，至少记录“远端未中止”并隔离下一轮 runDir/output。
- 如果 API 已支持取消，补上真实 DELETE/abort 调用，并保留失败降级状态。
- 为“timeout 后旧 session 延迟写目标文件”加一个集成测试，防止后续补跑逻辑误判。

### P1：默认链路配置在当前环境不可直接运行

证据：

- `src/main/resources/application.yml:1-4` 默认 `opencode-runner.enabled=true` 且 `active-chain=git-code-contribution-report`。
- `src/main/java/com/sonnet/wyf/gitreport/runner/ChainConfigLoader.java:27-43` 会从 `classpath:chains` 加载对应链路 YAML。
- `src/main/resources/chains/git-code-contribution-report.yml:6-8`、`src/main/resources/chains/weekly-engineering-report.yml:4-10`、`src/main/resources/chains/smartesb-rewrite-code-review.yml:4-20`、`src/main/resources/chains/smartesb-code-reader.yml:3-20` 都写死 `/home/wangyufeng/...`。
- 当前本机 `/home/wangyufeng/workspace/upfs-production` 和 `/home/wangyufeng/upfs-production` 均不存在，而 `/Users/wangyufeng/IdeaProjects/upfs-production` 存在。
- 新增测试 `src/test/java/com/sonnet/wyf/gitreport/WeeklyEngineeringReportPropertiesTest.java:23-34` 还把 `/home/wangyufeng/reports/weekly-engineering/2026-W26` 固化成期望值。

影响：

- 在当前 macOS 工作区直接 `mvn spring-boot:run` 会加载 git-report 配置并指向不存在的 repo/out 路径。
- 切到新增 weekly 链路时同样会先访问不存在的 `/home/...` repo，导致周报无法按 README 快速开始跑起来。
- 这类路径既在 YAML 里，也在属性类默认值里，容易出现“改了 YAML 但 fallback 默认仍错”的漂移。

建议：

- 把 classpath YAML 定位成 example，不应作为本机默认运行配置；默认 `enabled=false`，真实运行通过外部 `config-dir` 或 profile 提供。
- 如果保留 classpath 运行能力，改成当前机器可用路径或相对路径，并在 README 明确 macOS/Windows/Linux 覆盖方式。
- 测试不要断言个人机器绝对路径，改成断言字段能被加载或使用临时 config。

### P2：Git rename-only commit 仍可能漏算

证据：

- `src/main/java/com/sonnet/wyf/gitreport/preparation/GitStatsCollector.java:145-158` 解析 `git show --numstat --find-renames`，直接取最后一列作为 path 后交给 `FileScopeFilter`。
- 当前 Git 对纯 rename 输出为：`0  0  src/main/java/com/acme/{OldName.java => NewName.java}`。
- `src/main/java/com/sonnet/wyf/gitreport/preparation/FileScopeFilter.java:53-58` 会用完整路径或 basename 匹配 include；basename 变成 `{OldName.java => NewName.java}`，不匹配默认 `*.java`。
- `src/main/java/com/sonnet/wyf/gitreport/preparation/GitStatsCollector.java:52-55` 在 `numstatRows` 为空时直接跳过 commit，因此 rename-only commit 不计入作者提交。

影响：

- 重命名、迁移、包结构调整这类工作在周报和 git-report 中会漏掉 commit_count。
- 对“本周完成范围”和“人员投入范围”影响较大，因为大型重构常常以 rename/move 为主，正好是管理报告最需要呈现的工作。

建议：

- 对 `--numstat --find-renames` 的 brace rename path 做规范化，至少提取新路径 `NewName.java` 用于过滤和展示。
- 对纯 rename 提供专门测试：同作者 rename-only commit 应计入 commit_count，top_files 应展示新路径或 old->new 结构化字段。

### P2：SmartESB slug 碰撞仍会覆盖任务和报告

证据：

- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbDailyTransactionPlanLoader.java:34-55` 只按原始 name 去重。
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbReviewPreparation.java:205-211` 的 `slugify` 会把非字母数字、非 `-`、非 `_` 的字符都变成 `-`。
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbReviewPreparation.java:132-201` 使用 slug 生成 `reports/<slug>` 和 `tasks/<kind>-<slug>.json`。
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbWorkflowChain.java:161`、`:186`、`:271`、`:424` 后续运行、校验、输出定位也都依赖同一个 slug。

影响：

- `A B` 与 `A-B`、`A/B` 与 `A-B` 这类不同 review item 会落到同一个目录。
- full 模式里 `prepare(..., overwrite=true)` 会重写模板和 summary，占用同一个路径；并发执行时两个 session 也可能同时写同一文件。
- 最终 index 可能把两个逻辑任务都指向同一份 report/summary，导致报告缺项或互相污染。

建议：

- 在 plan loader 阶段同时检查 slug 唯一性，报错信息列出冲突的原始 name。
- 或者输出路径使用稳定序号加 slug，例如 `transaction-001-A-B`，避免只靠 slug 承载唯一性。
- 增加测试覆盖 `A B` 与 `A-B` 这种非 exact duplicate。

### P2：weekly 链路的数据质量状态容易被误读为完整项目健康结论

证据：

- `src/main/java/com/sonnet/wyf/gitreport/workflow/weekly/WeeklyEvidenceBuilder.java:80-106` 只调用 `GitReportPreparation` 重新生成 Git 证据。
- `src/main/java/com/sonnet/wyf/gitreport/workflow/weekly/WeeklyEvidenceBuilder.java:138-142` 明确把质量信号设为未验证。
- `src/main/java/com/sonnet/wyf/gitreport/workflow/weekly/WeeklyEvidenceBuilder.java:200-208` 项目状态只看 `risks`，但当前构建流程没有实际调用 `addRisk(...)`。
- `src/main/java/com/sonnet/wyf/gitreport/workflow/weekly/WeeklyEvidenceBuilder.java:230-240` 在没有 `dataQualityIssues` 时直接输出 `status=clean`，而 `dataQualityIssues` 当前没有入口写入。
- README `README.md:224-239` 已说明 v1 不接 Jira/CI/PR review，也不做最终绩效判断；这和输出中的 `normal` / `clean` 容易形成认知冲突。

影响：

- `weekly-report.md` 面向项目经理周会，如果只看到 `overall_status=normal` 和 `data_quality=clean`，容易误认为交付、质量、风险都已验证。
- 实际上当前链路只是“本周 Git 代码事实投影”，无法覆盖需求进度、缺陷、CI、线上事故、评审质量、SmartESB 审查结论。

建议：

- 在 v1 中把状态字段改成更保守的语义，例如 `evidence_status=git_only`，不要用 `clean` 表达缺少外部系统证据的情况。
- 将“未接 Jira/CI/PR review”从 `known_biases` 升级到 `data_quality.issues` 或 `coverage` 字段，让 PM 版报告能看到证据边界。
- 如果要输出项目状态，至少区分 `delivery_status` 和 `evidence_completeness`。

### P3：新增 weekly 链路文档和 example 配置不同步

证据：

- README `README.md:5-10` 说当前支持四条链路，包含 `weekly-engineering-report`。
- `src/main/resources/application-example.yml:3` 的可选值注释仍只列出 `git-code-contribution-report, smartesb-rewrite-code-review, smartesb-code-reader`。

影响：

- 使用 `application-example.yml` 做运行配置的人会看不到 weekly 链路。
- 这属于文档/示例漂移，不影响编译和测试，但会影响链路发现和使用。

建议：

- 同步补上 `weekly-engineering-report`。
- 考虑增加一个轻量测试，断言 README/example 中列出的链路 ID 至少覆盖所有 `WorkflowChain` bean 的 `id()`。

## 已改善或验证正常的点

- 多链路入口清晰：`WorkflowRunner` 只负责 normalize、选择 chain、构造 `WorkflowRunRequest`，业务差异保留在各自链路。
- SmartESB detail 修复已移到 batch completion gate：`SmartEsbWorkflowChain` 在 index 前统一扫描 summary 和 Markdown artifacts，并通过 `OutputCompletionGate` 做有界补跑。
- git-report author 产物也已有 pre-synthesis completion gate，缺少 `person-report.md` 或 `quality-summary.json` 时会 fresh rerun author task。
- OpenCode session 创建超时恢复和 managed-session cleanup 已有测试覆盖，尤其 directory query/header 双路径已回归。
- 新增 weekly 链路没有启动 OpenCode worker，按 Git 事实生成 evidence 和 Markdown，测试覆盖了 builder、renderer、workflow chain 和 properties。

## 测试覆盖观察

当前 108 个测试覆盖了主要编排路径，但仍有明显空白：

- 缺少 Git rename-only commit 的统计测试。
- 缺少 SmartESB slug 碰撞测试。
- 缺少默认 classpath 配置在当前环境可运行性的测试；现有测试反而固化了 `/home/...`。
- 缺少 timeout 后远端 session 延迟写文件的冲突测试。
- weekly 链路缺少“没有 Git 提交 / 只有外部工作 / 缺少外部系统证据”时的数据质量语义测试。

## 建议修复顺序

1. 先处理 P1：明确 timeout 远端 session 契约，以及默认运行配置不可用问题。
2. 再修 P2 数据准确性：rename-only commit 统计、SmartESB slug 碰撞。
3. 最后修 P2/P3 报告口径和文档：weekly 数据质量语义、application-example 链路枚举。

## 附：审查命令

```bash
mvn test
rg --files -g '!target' -g '!runs' -g '!*.class'
git status --short
git diff --stat
rg -n "TODO|FIXME|/home/wangyufeng|/Users/wangyufeng|batch-size|validation-max|TODO" src/main README.md pom.xml
```
