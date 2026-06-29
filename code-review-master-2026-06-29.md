# master 代码审查报告

- 审查日期：2026-06-29
- 审查分支：`master`
- 审查提交：`81c1447 feat: add smartesb code reader chain`
- 工作区：`/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI`
- 验证命令：`mvn test`
- 验证结果：通过，`Tests run: 103, Failures: 0, Errors: 0, Skipped: 0`

## 总体结论

`master` 当前可以通过单元测试，但还不建议直接按默认配置作为稳定生产链路运行。主要风险集中在三类：

1. OpenCode 任务超时后不会真正中止远端 session，可能和后续重跑/汇总发生竞争。
2. 默认运行配置仍绑定个人 Linux 路径，在当前 macOS 工作区不可直接启动。
3. Git 统计和 SmartESB 任务物料生成仍有边界数据丢失或覆盖风险，测试没有覆盖这些失败路径。

## 主要发现

### P1：OpenCode 超时不会真正 abort 远端 session

证据：

- `src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerTaskRunner.java:299`
- `src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerTaskRunner.java:385`
- `src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerClient.java:410`
- `src/test/java/com/sonnet/wyf/gitreport/OpenCodeServerTaskRunnerTest.java:221`

`OpenCodeServerTaskRunner` 在超时路径会调用 `client.abortSession(...)`，但 `OpenCodeServerClient.abortSession(...)` 当前直接 `return false`。测试也把这个行为固化为“超时记录成功，但 aborted=false”。

影响：

- Java 侧认为任务已经 timeout，但 OpenCode 远端 session 可能继续运行和写文件。
- 如果后续 correction/rerun 或 completion gate 开始消费输出，可能读到超时 session 的迟到产物。
- 这类问题在并发度为 6、任务耗时较长时更容易暴露。

建议：

- 实现真实 abort API 调用，或在 OpenCode 不支持 abort 时改为明确的隔离策略：超时 session 输出目录不可再被读取，后续重跑必须使用新目录并标记旧 session 为废弃。
- 增加一个 fake server 测试，验证超时后确实发出 abort 请求，且迟到输出不会被汇总链路采纳。

### P1：默认配置在当前工作区不可直接运行

证据：

- `src/main/resources/application.yml:1`
- `src/main/resources/chains/git-code-contribution-report.yml:7`
- `src/main/resources/chains/smartesb-rewrite-code-review.yml:5`
- `src/main/resources/chains/smartesb-rewrite-code-review.yml:14`
- `src/main/resources/chains/smartesb-rewrite-code-review.yml:17`
- `src/main/resources/chains/smartesb-rewrite-code-review.yml:20`
- `src/main/resources/chains/smartesb-code-reader.yml:4`
- `src/main/resources/chains/smartesb-code-reader.yml:11`
- `src/main/resources/chains/smartesb-code-reader.yml:14`
- `src/main/resources/chains/smartesb-code-reader.yml:20`
- `src/main/java/com/sonnet/wyf/gitreport/config/SmartEsbCodeReaderProperties.java:10`
- `src/main/java/com/sonnet/wyf/gitreport/config/SmartEsbRewriteProperties.java:8`

`application.yml` 默认 `git-report.enabled=true`，默认 active chain 是 `git-code-contribution-report`。多个 classpath chain 配置和 Java properties default 仍写死 `/home/wangyufeng/...`。在当前机器上：

- `/home/wangyufeng/workspace/upfs-production` 不存在。
- `/home/wangyufeng/upfs-production` 不存在。
- `/Users/wangyufeng/IdeaProjects/upfs-production` 存在。

影响：

- `master` 的默认 Spring Boot 启动很可能在进入真实业务前就因为 repo/root/source 配置失败。
- 这会让“测试通过”和“默认可运行”之间产生偏差。
- 个人路径进入 classpath 配置后，也会影响其他开发机、CI 或后续交付环境。

建议：

- classpath 默认配置不要绑定个人绝对路径，改为环境变量、profile override 或示例配置。
- 保留本机路径时应放到本地未提交配置，例如 `application-local.yml`。
- 增加一个轻量配置校验测试，至少校验 classpath 默认 profile 不包含不可移植个人路径。

### P2：Git rename-only 提交仍会被统计链路遗漏

证据：

- `src/main/java/com/sonnet/wyf/gitreport/preparation/GitStatsCollector.java:52`
- `src/main/java/com/sonnet/wyf/gitreport/preparation/GitStatsCollector.java:145`
- `src/main/java/com/sonnet/wyf/gitreport/preparation/GitStatsCollector.java:153`

`GitStatsCollector` 使用 `git show --format= --numstat --find-renames`。rename-only 提交的 numstat 输出形如：

```text
0	0	src/main/java/com/acme/{OldName.java => NewName.java}
```

当前 `parseNumstat` 直接取最后一列作为路径，再交给 include/exclude filter。默认 include `*.java` 时，basename 会变成 `{OldName.java => NewName.java}`，无法匹配 `*.java`，最终 `numstatRows` 为空，commit 被跳过。

影响：

- 纯重命名、移动类、包结构调整等提交不会计入 `commit_count`。
- 项目报告会低估结构性重构工作。
- 后续 changed-region 或作者贡献统计会缺少这类提交证据。

建议：

- 对 Git brace rename path 做规范化，至少拆出新路径 `src/main/java/com/acme/NewName.java` 后再做文件过滤。
- 给 rename-only、rename+edit、目录 rename 三类场景补测试。

### P2：SmartESB rewrite 任务 slug 冲突会覆盖物料

证据：

- `src/main/java/com/sonnet/wyf/gitreport/preparation/SmartEsbDailyTransactionPlanLoader.java:34`
- `src/main/java/com/sonnet/wyf/gitreport/preparation/SmartEsbReviewPreparation.java:132`
- `src/main/java/com/sonnet/wyf/gitreport/preparation/SmartEsbReviewPreparation.java:205`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/SmartEsbWorkflowChain.java:161`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/SmartEsbWorkflowChain.java:186`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/SmartEsbWorkflowChain.java:271`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/SmartEsbWorkflowChain.java:424`

plan loader 只检查原始 transaction/module name 是否完全重复，但落盘和读取都使用 `slugify(name)`。`A B`、`A-B`、`A/B` 会落到相同 slug，后写任务会覆盖先写任务。

影响：

- 明细任务可能被静默覆盖。
- rerun 时按 slug 找目录/文件，会把不同业务交易指到同一份产物。
- 汇总阶段看到的是“完整文件”，但对应的业务对象可能已经错位。

建议：

- 在 preparation 阶段维护 `slug -> originalName` 映射，发现冲突直接失败。
- 如果必须兼容相同 slug，落盘名应追加稳定短 hash，并在 manifest 中保存原始 name 与 slug path 的映射。

### P2：SmartESB code-reader 复用了 slug 覆盖风险

证据：

- `src/main/java/com/sonnet/wyf/gitreport/preparation/SmartEsbCodeReaderPreparation.java:182`
- `src/main/java/com/sonnet/wyf/gitreport/preparation/SmartEsbCodeReaderPreparation.java:207`
- `src/main/java/com/sonnet/wyf/gitreport/preparation/SmartEsbCodeReaderPreparation.java:523`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/SmartEsbCodeReaderWorkflowChain.java:148`
- `src/main/java/com/sonnet/wyf/gitreport/workflow/SmartEsbCodeReaderWorkflowChain.java:293`

新加的 code-reader chain 在 module、transaction 物料落盘和 run directory 命名上也使用 `slugify`，但没有 slug 冲突检测。

影响：

- 多个模块或交易名在 slug 后相同，会覆盖 `task.yml`、`prompt.md`、`context.md`。
- workflow 后续按 slug 回读输出，可能把 A 交易的代码阅读结论归到 B 交易。

建议：

- 和 rewrite 链路使用同一套 slug collision guard。
- 给 module slug collision、transaction slug collision 分别加测试。

### P2：SmartESB code-reader XML 解析没有关闭外部实体

证据：

- `src/main/java/com/sonnet/wyf/gitreport/preparation/SmartEsbCodeReaderPreparation.java:443`

`parseXml` 使用 `DocumentBuilderFactory.newInstance()` 后只设置了 `setNamespaceAware(false)`，没有设置 secure processing，也没有禁用 DOCTYPE、external general entities、external parameter entities。

影响：

- 如果 code-reader 读取的 XML 来自不完全可信的项目内容，存在 XXE/本地文件读取/外部请求风险。
- 即使当前输入来自本地仓库，CI 或批量审查外部分支时也容易扩大输入信任边界。

建议：

- 开启 `XMLConstants.FEATURE_SECURE_PROCESSING`。
- 禁用 DOCTYPE 和外部实体。
- 增加一个包含 DOCTYPE 外部实体的单元测试，期望解析失败或实体不展开。

### P3：SmartESB code-reader 对空输入/缺失根目录的失败语义不够硬

证据：

- `src/main/java/com/sonnet/wyf/gitreport/preparation/SmartEsbCodeReaderPreparation.java:451`
- `src/main/java/com/sonnet/wyf/gitreport/config/SmartEsbCodeReaderProperties.java:12`
- `src/test/java/com/sonnet/wyf/gitreport/SmartEsbCodeReaderWorkflowChainTest.java:43`

`collectFiles` 在 root 不存在时直接返回空列表。properties 默认的 `serviceIdentify` 也是空列表。当前 classpath YAML 会提供一个 `/home/.../service_identify.xml`，所以默认 YAML 在本机更可能直接失败；但外部配置如果把 service identify 留空，prepare 阶段可以生成空任务集，workflow 仍可能进入 index 阶段。

影响：

- 配置错误可能被表现为“无任务可读”而不是启动失败。
- 汇总报告可能看起来完整，但真实输入为空或严重缺失。

建议：

- 明确区分“允许为空”和“配置错误”。
- 对 source root、service identify、xml/java root 增加必填校验。
- 增加缺失 root、空 serviceIdentify、无交易任务三类测试。

### P3：code-reader Java 运行时和 legacy skill 文档存在漂移

证据：

- `src/main/java/com/sonnet/wyf/gitreport/preparation/SmartEsbCodeReaderPreparation.java:83`
- `src/main/java/com/sonnet/wyf/gitreport/preparation/SmartEsbCodeReaderPreparation.java:91`
- `src/test/java/com/sonnet/wyf/gitreport/SmartEsbCodeReaderPreparationTest.java:80`
- `src/main/resources/skill/smartesb-code-reader`

Java runtime 当前把 `batch_size`、`module_batch_count` 写为 0，测试也断言不会生成 `tasks/batches`。但 legacy skill 资源里仍有 `--batch-size`、`tasks/batches`、subagent batch 相关描述。

影响：

- 如果有人按 resource skill 文档手工执行，会得到和 Java 链路不同的任务组织方式。
- 后续 prompt/schema 调整容易改到非运行时资源，造成“文档看起来改了，Java 链路没变”的漂移。

建议：

- 标记 `src/main/resources/skill/smartesb-code-reader` 为 legacy/static prompt 资源，或删除不再被 Java 链路使用的 batch 说明。
- 如果 batch 语义未来要恢复，应由 Java properties 驱动并补齐测试。

## 已观察到的改善

- `master` 已有 `OutputCompletionGate` 相关测试，SmartESB 明细输出完整性不再只依赖单个 detail session 自行保证。
- managed OpenCode session cleanup 已经有目录过滤测试，避免误删非当前项目 session。
- 新增 SmartESB code-reader chain，并覆盖了 module、transaction、index、rerun requested transaction 等主路径。
- SmartESB rewrite 旧的 live chain batch-size 问题已不再出现在当前 classpath rewrite 配置里。

## 测试覆盖情况

已执行：

```text
mvn test
Tests run: 103, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

建议补充：

1. OpenCode timeout 后真实 abort 请求测试。
2. 超时 session 迟到输出不被 completion gate/rerun 消费的测试。
3. Git rename-only、rename+edit、brace rename path 过滤测试。
4. SmartESB rewrite/code-reader slug collision 测试。
5. SmartESB code-reader XML XXE 防护测试。
6. SmartESB code-reader 缺失 root、空 serviceIdentify、无任务输入的失败语义测试。
7. classpath 默认配置不可移植路径检测。

## 建议修复顺序

1. 先修 OpenCode timeout abort 或输出隔离，这是并发运行下最容易污染结果的问题。
2. 再清理默认配置里的个人绝对路径，确保 `master` 默认 profile 能被他人理解和安全覆盖。
3. 统一修复 SmartESB rewrite/code-reader 的 slug collision guard。
4. 修 Git rename path normalization，补上 rename-only 统计。
5. 加固 code-reader XML parser 和空输入校验。
6. 最后清理 legacy skill 文档漂移。
