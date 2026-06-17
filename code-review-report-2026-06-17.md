# Java-OpenCode-CLI 代码审查报告

审查日期：2026-06-17

审查范围：当前仓库主代码、链路配置、README 和测试用例。重点关注 OpenCode Server 调度、git 贡献统计、SmartESB 多交易链路、配置契约和跨平台路径行为。

验证基线：

- `mvn test` 通过，53 个测试，0 失败，0 错误，0 跳过。
- 当前 `git status --short` 无输出，审查前工作区未见未提交改动。

## 结论

当前代码的模块拆分、Spring Bean 拆分、OpenCode 1.17 `/session` 协议适配和本地测试覆盖已经比较完整，适合作为 CLI runner 继续迭代。

主要风险集中在真实运行边界：OpenCode 超时无法实际中止远端会话、git rename 统计会漏算、SmartESB 暴露了未生效的批次配置，以及交易名 slug 冲突会覆盖输出。这些问题不会被现有 53 个测试暴露，但会直接影响长任务成本、报告准确性和批量审查输出完整性。

## 发现

### P1: 超时路径只记录本地 timeout，不会中止 OpenCode 远端会话

证据：

- `src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerTaskRunner.java:114-117` 超时时调用 `client.abortSession(...)`，并把返回值写入 `OpenCodeRunResult.aborted`。
- `src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerClient.java:320-322` 的 `abortSession(...)` 当前固定返回 `false`，没有发送任何 OpenCode API 请求。
- `src/test/java/com/sonnet/wyf/gitreport/OpenCodeServerTaskRunnerTest.java:96-130` 已把 `timedOut=true` 且 `aborted=false` 固化成测试预期。
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbWorkflowChain.java:197-202` 在交易输出校验失败后会立即 rerun；若首次会话只是本地超时但远端仍在运行，rerun 可能与原会话并行写同一份输出。

影响：

- 长时间卡住的 OpenCode 会话可能继续消耗模型/API 资源。
- SmartESB 单交易 rerun 可能和原会话同时写 `reports/<transaction>/summary.json`、`review.md` 等文件，造成输出互相覆盖或校验结果不稳定。
- Git 报告链路虽然会跳过自动重试，但远端会话仍可能继续运行，本地状态会让使用者误以为任务已经完全停止。

建议：

- 补齐 OpenCode 1.17 可用的 abort/cancel 接口；如果当前版本没有稳定接口，应把状态字段命名成 `abortSupported=false` 或在超时后阻止自动 rerun。
- SmartESB rerun 前至少等待原 session 进入可确认终态，或给输出目录增加 session 隔离，避免两个会话写同一路径。
- 增加测试覆盖：模拟 timeout 后服务端仍继续写文件，再验证 runner 不会启动第二个同路径写入任务。

### P1: git rename + 修改场景会漏算代码贡献

证据：

- `src/main/java/com/sonnet/wyf/gitreport/preparation/GitStatsCollector.java:137-148` 从 `git show --numstat --find-renames` 读取路径，并直接用该路径做 `filter.isCounted(path)`。
- 对于高相似度重命名并修改，Git numstat 会输出类似 `src/{Old.java => New.java}`。该 basename 是 `{Old.java => New.java}`，不会匹配 `*.java`。
- `src/main/java/com/sonnet/wyf/gitreport/preparation/GitStatsCollector.java:153-196` 的非注释 diff 解析使用 `+++ b/src/New.java` 得到新路径，两边路径口径不同，即使 numstat 进入统计也可能取不到对应的非注释行数。
- 窄复现实验输出：

```text
NUMSTAT
1	0	src/{Old.java => New.java}
DIFF PATHS
diff --git a/src/Old.java b/src/New.java
rename from src/Old.java
rename to src/New.java
+++ b/src/New.java
@@ -20,0 +21 @@ line 20
```

影响：

- 重命名文件里的新增/删除行可能被完全排除，导致 `commit_count`、`added`、`non_comment_added`、`workload_score` 偏低。
- 该项目近期正在服务代码贡献统计，重构类提交经常伴随 rename，这会直接影响作者排名和最终报告可信度。

建议：

- 对 numstat 的 rename path 做规范化，优先解析到新路径，例如把 `src/{Old.java => New.java}` 转为 `src/New.java`。
- `parseNumstat` 和 `parseNonCommentDiff` 应共享同一套路径规范化函数。
- 增加一个真实 git 仓库测试：提交 `git mv Old.java New.java` 并追加一行，断言新增行和非注释新增行都被统计到 `New.java`。

### P2: SmartESB `batch-size` 是暴露配置但当前没有生效

证据：

- `README.md:224-236` 和 `src/main/resources/chains/smartesb-rewrite-code-review.yml:12` 都暴露 `batch-size: 5`。
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbRewriteProperties.java:112-118` 提供了 `getBatchSize()` / `setBatchSize(...)`。
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbWorkflowChain.java:108-136` 直接遍历 `transactions` 提交全部任务，实际只受 `opencode-runner.opencode.concurrency/max-concurrency` 限制。
- 全仓搜索只发现测试校验属性可绑定，没有生产代码读取 `getBatchSize()`。

影响：

- 用户按 README 配置 `batch-size` 后不会得到批次隔离效果。
- 大交易清单会一次性把所有任务放入 executor 队列；并发虽然被 semaphore 限制，但失败恢复、批次暂停、分段验收都无法按配置执行。

建议：

- 如果需要批次语义，按 `batchSize` 分批提交和等待，每批完成后再进入下一批。
- 如果暂不支持批次，删除 README/YAML/属性类里的 `batch-size`，避免形成无效契约。

### P2: SmartESB 交易名只校验原文唯一，slug 冲突会覆盖输出

证据：

- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbDailyTransactionPlanLoader.java:32-41` 只校验交易名原文不重复。
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbReviewPreparation.java:187-193` 的 `slugify(...)` 会把非字母数字、`-`、`_` 的字符归一成 `-`。
- `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbReviewPreparation.java:112-115` 和 `src/main/java/com/sonnet/wyf/gitreport/workflow/smartesb/SmartEsbReviewPreparation.java:183` 用 slug 生成 `reports/<slug>` 和 `tasks/transaction-<slug>.json`。

最小冲突样例：

```yaml
transactions:
  - name: A/B
    description: first
  - name: A:B
    description: second
```

两个交易都会落到 slug `A-B`，后写入的任务和报告会覆盖先写入的文件。

影响：

- `index_inputs.json` 中可能有两个不同交易，但它们指向同一输出目录。
- 单交易 rerun、汇总校验和最终 index 都会混入错误交易的产物。

建议：

- 在 plan loader 或 preparation 阶段增加 slug 唯一性校验，并在错误信息里同时输出原交易名和冲突 slug。
- 或者生成稳定前缀，例如 `001-A-B`、`002-A-B`，同时保留原交易名用于业务展示。

### P2: 仓库默认配置会真实启动 runner，且指向硬编码外部路径

证据：

- `src/main/resources/application.yml:1-16` 默认 `opencode-runner.enabled: true`、`active-chain: git-code-contribution-report`、`manage-server: true`、`opencode-bin: opencode`。
- `src/main/resources/chains/git-code-contribution-report.yml:6-8` 默认 repo/out 指向 `/home/wangyufeng/...`。
- `src/main/resources/chains/smartesb-rewrite-code-review.yml:1-7` 默认 SmartESB 路径也指向 `/home/wangyufeng/...`。
- `src/main/java/com/sonnet/wyf/gitreport/GitReportApplication.java:12-15` 启动后会执行 `ApplicationRunner` 并通过 `SpringApplication.exit(context)` 退出，适合 CLI，但也意味着默认配置不是空启动。

影响：

- 在当前 macOS 工作区直接运行 `mvn spring-boot:run`，会尝试使用硬编码 Linux 路径和本机 `opencode`，失败信息容易被误判为 OpenCode 或 Git 问题。
- 新环境或 CI 如果没有显式覆盖配置，可能误启动外部进程。

建议：

- 将仓库内 `application.yml` 默认改为 `opencode-runner.enabled: false`，把可运行示例放在 `application-example.yml` 或 profile 文件中。
- 如果必须默认可运行，启动前增加 preflight：校验 repo/out/opencode-bin/server-url，并在任何外部进程启动前输出明确错误。

## 已确认的正向点

- `GitReportApplication` 已保持为入口类，Bean 组装已下沉到 `config` 包，符合当前模块拆分方向。
- OpenCode client 使用 `/session`、`/prompt_async`、`/message` 和 `X-OpenCode-Directory`，没有回退到旧 `/api/session` 口径。
- `createSession` 对响应悬挂做了 session 列表恢复，能缓解 OpenCode 已创建 session 但响应不结束的问题。
- Git 报告作者任务在 prompt 已提交后不自动重试，避免最常见的重复提交 prompt。
- SmartESB 准备器会预创建输出结构，并保留 Windows/logical path 与 local mirror 的分离。

## 建议修复顺序

1. 修复或显式禁用 timeout 后 rerun：先解决远端会话继续运行和同路径并发写入问题。
2. 修复 git rename 路径规范化：这是贡献统计准确性的核心缺口。
3. 明确 `batch-size` 契约：实现分批，或删除无效配置。
4. 增加 SmartESB slug 唯一性校验。
5. 调整默认启动配置或补齐 preflight，降低误运行成本。

## 验证记录

```text
$ mvn test
Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-06-17T11:16:23+08:00
```
