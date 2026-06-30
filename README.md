# Java OpenCode CLI Runner

这是一个基于 Spring Boot 的 OpenCode 多链路 Runner。主应用只负责选择链路和运行方式，链路自身的业务配置放在独立 YAML 中。

当前支持四条链路，其中 `weekly-engineering-report` 会按配置的 `startday`/`endday` 统计窗口重新统计 Git 代码事实，并对窗口内 changed regions 启动代码审查批次：

- `git-code-contribution-report`：代码提交量统计和个人/总报告生成。
- `smartesb-rewrite-code-review`：SmartESB 8583 到 JSON 改造审查。
- `smartesb-code-reader`：从 serviceIdentify/XML/BIZ/Java 生成 SmartESB 模块和交易阅读索引。
- `weekly-engineering-report`：重新生成统计窗口内 Git 证据和代码审查结果，生成项目经理周会报告、研发负责人团队风险报告、代码维度审查报告和个人证据包。

## 快速开始

1. 修改主配置 `src/main/resources/application.yml`：

```yaml
opencode-runner:
  enabled: true
  active-chain: "git-code-contribution-report"
  mode: "full"
  config-dir: "classpath:chains"
  opencode:
    server-url: "http://127.0.0.1:4096"
    manage-server: true
    opencode-bin: "opencode"
    create-session-timeout-seconds: 10
    request-timeout-seconds: 60
    timeout-minutes: 40
```

2. 修改对应链路配置：

- git-report：`src/main/resources/chains/git-code-contribution-report.yml`
- SmartESB rewrite review：`src/main/resources/chains/smartesb-rewrite-code-review.yml`
- SmartESB code-reader：`src/main/resources/chains/smartesb-code-reader.yml`
- weekly engineering report：`src/main/resources/chains/weekly-engineering-report.yml`

3. 运行：

```bash
mvn spring-boot:run
```

4. 验证：

```bash
mvn test
```

## 主配置

主配置只放运行控制和共享 OpenCode 参数：

```yaml
opencode-runner:
  enabled: true
  active-chain: "git-code-contribution-report"
  mode: "full"
  run-date:
  config-dir: "classpath:chains"
  rerun:
    type:
    id:
  opencode:
    server-url: "http://127.0.0.1:4096"
    manage-server: true
    server-start-timeout-seconds: 30
    create-session-timeout-seconds: 10
    request-timeout-seconds: 60
    opencode-bin: "opencode"
    session-model: ""
    concurrency: 6
    timeout-minutes: 40
    output-wait-seconds: 30
    max-retries: 1
    max-concurrency: 6
    environment:
      OPENCODE_DISABLE_MODELS_FETCH: "true"
```

字段说明：

- `enabled`：是否启动 Runner。
- `active-chain`：要运行的链路 ID。
- `mode`：`full` 或 `rerun`。
- `run-date`：SmartESB 使用，格式 `yyyy-MM-dd`；周报链路未配置 `startday`/`endday` 时才用它推导自然周。
- `config-dir`：链路配置目录，默认 `classpath:chains`。
- `rerun.type`：补跑类型。
- `rerun.id`：补跑目标 ID，例如 authorKey 或 transaction name；多个目标用英文逗号放在同一个字符串中。
- `opencode.*`：OpenCode Server、模型、并发、超时等共享参数。
- `opencode.session-model`：可选，向 OpenCode 提交 prompt 时显式指定模型，格式 `provider/model`。内网自定义 provider 建议配置，例如 `spdb-new-api/minimax-m2.7`。
- `opencode.create-session-timeout-seconds`：创建 session 的 HTTP 上限。Runner 会给 OpenCode session title 追加时间戳，并在 `POST /session` 未返回时并行查询 session 列表；只要发现这个唯一 title 的 session，就立即取得 sessionId 并继续提交 prompt。超时后的恢复查询只是兜底，不是主路径；不要把它当作任务运行时长配置，通常保持 10 秒左右。
- `opencode.request-timeout-seconds`：提交 prompt 等普通 OpenCode Server API 的 HTTP 超时；和整个任务的 `timeout-minutes` 不是同一个超时。
- `opencode.environment`：Java 托管启动 `opencode serve` 时注入的环境变量。内网/离线环境默认设置 `OPENCODE_DISABLE_MODELS_FETCH=true`，避免 OpenCode 创建 session 时联网拉取 `models.dev`。

## 运行模式

### git-report 全量

```yaml
opencode-runner:
  enabled: true
  active-chain: "git-code-contribution-report"
  mode: "full"
```

执行流程：

1. Java 统计 Git 提交和作者明细。
2. 按作者启动 OpenCode session 生成个人报告和质量摘要。
3. Java 统一计算质量分和最终排名。
4. Java 生成有界 `synthesis-inputs.json`。
5. OpenCode 生成最终中文总报告。

### git-report 补跑一个或多个作者

```yaml
opencode-runner:
  active-chain: "git-code-contribution-report"
  mode: "rerun"
  rerun:
    type: "author"
    id: "author-001-xxx, author-002-yyy"
```

`id` 必须是 `index_inputs.json` 中的 `tasks[].author_key`。多个作者会并发补跑，最后只重建一次总报告。

### git-report 只重跑总报告

```yaml
opencode-runner:
  active-chain: "git-code-contribution-report"
  mode: "rerun"
  rerun:
    type: "synthesis"
```

要求已有完整的个人报告和 `quality-summary.json`。

### SmartESB 全量

```yaml
opencode-runner:
  active-chain: "smartesb-rewrite-code-review"
  mode: "full"
  run-date: "2026-06-16"
```

`full` 运行 `run-date` 对应清单里的交易和模块。

### SmartESB 补跑一个或多个交易

```yaml
opencode-runner:
  active-chain: "smartesb-rewrite-code-review"
  mode: "rerun"
  run-date: "2026-06-16"
  rerun:
    type: "transaction"
    id: "CaCheckAcct, CaConsumeRev, CaTransferOuter"
```

`id` 必须存在于当前 `run-date` 的 `transactions.yml`。多个交易会使用补审 prompt 并发补跑，然后重建索引。

### SmartESB 补跑一个或多个模块

```yaml
opencode-runner:
  active-chain: "smartesb-rewrite-code-review"
  mode: "rerun"
  run-date: "2026-06-24"
  rerun:
    type: "module"
    id: "BaseChnConvReqMsgSop, BaseOthCenterCtrl"
```

`id` 必须存在于当前 `run-date` 的 `transactions.yml` 的 `modules` 列表。多个模块会使用模块补审 prompt 并发补跑，然后重建索引。

### SmartESB 只重建索引

```yaml
opencode-runner:
  active-chain: "smartesb-rewrite-code-review"
  mode: "rerun"
  run-date: "2026-06-16"
  rerun:
    type: "index"
```

只基于当前日期输出目录中已有的交易摘要重新生成 `index.md` 和 `summary.md`。

### SmartESB code-reader 全量

```yaml
opencode-runner:
  active-chain: "smartesb-code-reader"
  mode: "full"
```

从 `serviceIdentify.xml`、交易 XML、BIZ 和 Java 源码生成模块/交易阅读任务，运行 OpenCode 后生成 `summary.json`、`index_inputs.json`、`index.md` 以及模块/交易分析产物。

### SmartESB code-reader 补跑

```yaml
opencode-runner:
  active-chain: "smartesb-code-reader"
  mode: "rerun"
  rerun:
    type: "transaction"
    id: "CaConsume, CaTransferOuter"
```

`rerun.type` 支持 `module`、`transaction`、`index`。补跑模块或交易时，`id` 必须存在于 `index_inputs.json`；多个目标用英文逗号分隔。`index` 只基于已有模块/交易摘要重建 `index.md`。

### weekly-engineering-report 全量

```yaml
opencode-runner:
  active-chain: "weekly-engineering-report"
  mode: "full"
  run-date: "2026-06-26"
```

周报链路不读取 git-report、SmartESB rewrite review 或 code-reader 的历史产物。它会按 `startday`/`endday` 指定的任意统计窗口重新统计 Git，生成窗口内 changed regions，再按模块、作者和容量限制收缩成 review units，最后从 Git 证据和代码审查结果投影完整分卷 Markdown 报告。输出包括：

- `weekly-git-evidence.json`：周报链路本次重新生成的 Git 证据。
- `review-units.json`：按模块、作者和容量限制收缩后的代码审查任务清单。
- `review-batches.json`：兼容旧补跑入口的同内容清单，实际任务 ID 以 `review-unit-*` 命名。
- `review-units/<unit_id>/input.json`：单个审查任务输入，只包含该任务覆盖的 changed regions。
- `review-units/<unit_id>/code-review-summary.json`、`review-units/<unit_id>/code-review.md`：OpenCode worker 写入的任务审查结果。
- `weekly-evidence.json`：统一证据层，引用 Git 证据和审查批次。
- `code-review/overview.md`、`code-review/p0-p1-p2-issues.md`、`code-review/code-standards.md`、`code-review/hotspots.md`、`code-review/full-findings.md`、`code-review/modules/*.md`、`code-review/index.json`：完整代码维度审查报告。
- `quality-scores.json`、`people-ranking.md`：基于工作量和 P0/P1/P2 审查结果生成的人员周度代码报告排名。
- `weekly-report.md`：项目经理周会版。
- `team-risk-assessment.md`：研发负责人团队贡献与风险辅助评估。
- `people/<author_key>/weekly-person-report.md`：个人证据包。
- `traceability.json`：`region_id -> review unit -> finding -> report` 的追溯索引。
- `data-quality.md`：数据质量说明。

所有 Markdown 报告之间使用相对路径链接，便于在本地目录、IDE 或归档包内直接点击跳转。

报告口径固定隔离：

- `weekly-report.md` 面向项目经理周会，不展示个人排名或绩效结论。
- `team-risk-assessment.md` 面向研发负责人，展示团队贡献分布、风险集中和 review 建议。
- `people/<author_key>/weekly-person-report.md` 面向 1:1、辅导和绩效校准证据包，不输出绩效定级。

v1 不接 Jira/禅道/CI/PR review，不做最终绩效判断，也不复用历史代码审查结论作为当前统计窗口证据。代码审查 finding 只能归因到统计窗口内 `changed_regions` 中的 `region_id`、`author_key`、`commit`、`file` 和行号范围。

### weekly-engineering-report 补跑审查批次

```yaml
opencode-runner:
  active-chain: "weekly-engineering-report"
  mode: "rerun"
  rerun:
    type: "review-batch"
    id: "review-unit-001-upfs-cup-src-main-java-com-spdb-upfs-cup-service-esf-author-001-alice"
```

`id` 必须存在于 `weekly-evidence.json` 的 `review_batches[].batch_id`。多个 review units 会并发补跑，然后重建周报、团队风险报告和分卷代码审查报告。

### weekly-engineering-report 只重建报告

```yaml
opencode-runner:
  active-chain: "weekly-engineering-report"
  mode: "rerun"
  rerun:
    type: "synthesis"
```

要求已有完整的 `weekly-evidence.json` 和所有批次的 `code-review-summary.json`、`code-review.md`。

## git-report 链路配置

配置文件：`src/main/resources/chains/git-code-contribution-report.yml`

核心字段：

```yaml
project:
  id: "upfs-production"
  name: "UPFS Production"
  run-id:

paths:
  repo: "/home/wangyufeng/workspace/upfs-production"
  out: "/home/wangyufeng/reports/git-code-contribution/2026-06-15"

git:
  since: "2026-06-01"
  until: "2026-06-15"
  revision: "HEAD"
  include-merges: false
  include: []
  exclude:
    - "target/**"
    - "*.lock"
```

总报告压缩输入配置：

```yaml
synthesis-input:
  person-report-excerpt-chars: 8192
  snippets-per-author: 5
  snippets-total: 30
  snippet-lines: 20
```

说明：

- `person-report-excerpt-chars`：每个人个人报告带入总报告生成器的最大字符数。
- `snippets-per-author`：每个人最多带入多少个代码片段。
- `snippets-total`：总报告最多带入多少个代码片段。
- `snippet-lines`：每个代码片段最多带入多少行。

## SmartESB rewrite review 链路配置

配置文件：`src/main/resources/chains/smartesb-rewrite-code-review.yml`

核心字段：

```yaml
out: "/home/wangyufeng/review-output/smartesb-rewrite-review"
local-out:
transaction-plan-dir: "src/main/resources/smartesb-transactions"
new-project: "/home/wangyufeng/upfs-nl-json"
old-8583-doc: "/home/wangyufeng/upfs-nl-json/doc/docment/old-8583.md"
doc-root: "/home/wangyufeng/upfs-nl-json/doc/docment"
mapping-doc:
reconstructed-design:
worker-message: "严格执行附件 worker-prompt.md 中的 SmartESB 单项审查任务，只输出 DONE 或 BLOCKED。"
synthesis-message: "严格执行附件 synthesis-prompt.md 中的 SmartESB 汇总任务，生成中文 index.md 和 summary.md。"
```

输出目录规则：

- 如果 `local-out` 为空，使用 `out/<run-date>`。
- 如果 `local-out` 不为空，使用 `local-out/<run-date>`。

## SmartESB code-reader 链路配置

配置文件：`src/main/resources/chains/smartesb-code-reader.yml`

核心字段：

```yaml
out: "/home/wangyufeng/review-output/smartesb-code-reader"
local-out:
service-identify:
  - "/home/wangyufeng/upfs-production/serviceIdentify.xml"
xml-root: "/home/wangyufeng/upfs-production"
biz-root:
java-root: "/home/wangyufeng/upfs-production"
mode: "8583"
worker-message: "严格执行附件 worker-prompt.md 中的 SmartESB code-reader 单项阅读任务，只输出 DONE 或 BLOCKED。"
synthesis-message: "严格执行附件 synthesis-prompt.md 中的 SmartESB code-reader 索引任务，生成中文 index.md。"
```

说明：

- `service-identify` 支持一个或多个 `serviceIdentify.xml`。
- `xml-root` 用于定位交易 XML 和 base XML。
- `biz-root` 为空时等于 `xml-root`。
- `java-root` 是 Java 源码根目录，也是 OpenCode session directory。
- `mode` 对应 `serviceIdentify.xml` 中要读取的 switch mode。

## weekly-engineering-report 链路配置

配置文件：`src/main/resources/chains/weekly-engineering-report.yml`

核心字段：

```yaml
project:
  id: "upfs-production"
  name: "UPFS Production"
  repo: "/home/wangyufeng/workspace/upfs-production"

paths:
  out: "/home/wangyufeng/reports/weekly-engineering/2026-W26"

startday: "2026-06-19"
endday: "2026-06-26"

git:
  exclude:
    - "target/**"
    - "*.lock"

review:
  max-regions-per-batch: 8
  max-hunk-lines: 24
  concurrency: 3

opencode:
  timeout-minutes: 40
```

说明：

- `project.revision` 不配时默认 `HEAD`。
- `startday` 和 `endday` 是周报统计窗口，格式 `yyyy-MM-dd`；可以配置周四到周四、两周一次或任意不固定周期。
- `startday` 和 `endday` 必须成对配置，且 `endday` 不能早于 `startday`。
- `startday`/`endday` 都不配时，才使用 `opencode-runner.run-date` 所在自然周的周一到周日作为兜底。
- `git.exclude` 建议保留，用于排除构建产物、锁文件、生成物等噪声。
- `git.include`、`git.include-merges` 和 `git.author-map` 都是可选高级配置；不配时使用默认统计口径。
- `review.max-hunk-lines` 控制每个 changed region 带入审查输入的最大 hunk 行数。
- `review.concurrency` 控制 weekly code-review worker 并发数，实际并发还会受共享 `opencode.max-concurrency` 限制。
- `review.grouping.strategy` 当前支持 `module-author-capacity`，先按模块/路径域和作者收缩任务，再按容量切分。
- `review.grouping.target-task-count` 是期望任务数量，用于配置阅读和后续调优；实际数量仍由模块、作者和容量边界决定。
- `review.grouping.max-regions-per-task`、`max-files-per-task`、`max-hunk-chars-per-task`、`max-commits-per-task` 控制单个 review unit 的容量上限，避免上下文过大。
- `opencode.*` 可覆盖周报链路自己的 OpenCode 参数；未覆盖的模型和 server 配置继续使用主配置。
- 周报链路不会触发其他链路，不会读取历史审查产物；代码质量结论只来自本链路本次生成的 weekly code-review 输出。

`weekly-evidence.json` 使用固定 schema：

```text
schema_version = weekly-engineering-report/v1
top-level keys = schema_version, generated_at, week, project, source_runs, weekly_git, review_batches, data_quality
```

证据引用格式：

```text
git:<author_key>:<commit_hash>
file:<path>
```

## SmartESB 每日审查清单

SmartESB 按日期读取审查清单。目录结构：

```text
<transaction-plan-dir>/
  2026-06-16/
    transactions.yml
```

`transactions.yml` 示例：

```yaml
date: "2026-06-16"
transactions:
  - name: "CaRolloutRepeal"
  - name: "CaAcctInfoCheck"
modules:
  - name: "BaseChnConvReqMsgSop"
  - name: "BaseOthCenterCtrl"
```

规则：

- 日期目录必须是 `yyyy-MM-dd`。
- 每天目录下固定读取 `transactions.yml`。
- `transactions[].name` 必填。
- `modules[].name` 可选；模块项用于审查基础类、公共处理类、路由类、转换类等非交易入口。
- 同一天内交易名和模块名不能重复。
- `description` 已不需要；如果旧清单里存在，仍会兼容读取。
- 找不到日期目录或 `transactions.yml` 会直接失败，并提示实际查找路径。

## OpenCode Server

如果 `manage-server: true`，应用会先检查 `server-url`。不可用时自动执行：

```bash
opencode serve --port <server-url中的端口>
```

如果 `manage-server: false`，需要提前手动启动：

```bash
opencode serve --port 4096
```

OpenCode 1.17 的 session API 使用 `POST /session` 创建会话，并通过请求头 `X-OpenCode-Directory: <repoPath>` 指定工作目录；异步提交任务使用 `POST /session/{id}/prompt_async`，同样携带该目录头。Runner 创建 session 时会把业务 title 扩展成带时间戳的唯一 title，并在 `/session` 响应未结束时并行用 `GET /session?search=<uniqueTitle>&limit=100` 查询 session 列表；这样服务端已创建 session 但 HTTP 连接未返回时，Java 也能拿到 sessionId 并提交 prompt。配置 `opencode.session-model` 后，Runner 会在创建 session 时写入 create-session 形态的模型字段 `model.providerID` + `model.id`，避免 OpenCode 在创建阶段解析默认模型；提交 prompt 时仍按 prompt 形态写入 `model.providerID` + `model.modelID`。

内网自定义 provider 推荐显式配置：

```yaml
opencode-runner:
  opencode:
    session-model: "spdb-new-api/minimax-m2.7"
```

### 内网/离线模型目录

OpenCode 1.17 会维护模型目录缓存，`opencode models --refresh` 会从 `models.dev` 拉取模型元数据。内网或离线环境无法访问 `models.dev` 时，`/session` 可能在创建 session 时卡住并在 `runs/opencode-server/stderr.log` 或 OpenCode 自身日志中出现：

```text
service=models.dev error=Unable to connect ... Failed to fetch models.dev
```

Java 托管启动时默认注入：

```yaml
opencode-runner:
  opencode:
    environment:
      OPENCODE_DISABLE_MODELS_FETCH: "true"
```

如果手工启动 OpenCode Server，需要在启动前设置同一个环境变量：

```bash
export OPENCODE_DISABLE_MODELS_FETCH=true
opencode serve --port 4096 --hostname 127.0.0.1 --print-logs --log-level DEBUG
```

如果需要使用固定模型目录文件，也可以配置：

```yaml
opencode-runner:
  opencode:
    environment:
      OPENCODE_DISABLE_MODELS_FETCH: "true"
      OPENCODE_MODELS_PATH: "/home/wangyufeng/opencode/models.json"
```

## Session 监控

每个 OpenCode session 都会在自己的 run 目录写入运行状态：

```text
runs/<task>/session-status.json
```

常见路径：

```text
runs/author-001-xxx/session-status.json
runs/synthesis/session-status.json
runs/<transaction>/session-status.json
runs/index/session-status.json
```

状态文件包含：

- `phase`
- `sessionId`
- `title`
- `serverState`
- `pollCount`
- `elapsedSeconds`
- `timedOut`
- `aborted`
- `promptFile`

OpenCode session 状态轮询间隔为 10 秒。日志会在状态变化时输出 heartbeat；状态不变时约每 60 秒输出一次 heartbeat。

## 产物定位

git-report 常见产物：

```text
summary.json
details.json
index_inputs.json
quality-scores.json
reports/<author_key>/person-report.md
reports/<author_key>/quality-summary.json
runs/<author_key>/worker-prompt.md
runs/<author_key>/session-status.json
runs/synthesis/synthesis-inputs.json
runs/synthesis/synthesis-prompt.md
runs/synthesis/session-status.json
code-contribution-report.md
```

SmartESB rewrite review 常见产物：

```text
summary.json
index_inputs.json
tasks/*.json
reports/<transaction>/review.md
reports/<transaction>/mapping-matrix.md
reports/<transaction>/summary.json
runs/<transaction>/worker-prompt.md
runs/<transaction>/session-status.json
runs/index/synthesis-prompt.md
runs/index/session-status.json
index.md
summary.md
```

SmartESB code-reader 常见产物：

```text
summary.json
index_inputs.json
tasks/module-*.json
tasks/transaction-*.json
modules/<module>/analysis.md
modules/<module>/summary.json
transactions/<transaction>/analysis.md
transactions/<transaction>/summary.json
runs/module-*/worker-prompt.md
runs/transaction-*/worker-prompt.md
runs/index/synthesis-prompt.md
index.md
```

weekly-engineering-report 常见产物：

```text
weekly-evidence.json
weekly-git-evidence.json
review-units.json
review-batches.json
review-units/<unit_id>/input.json
review-units/<unit_id>/code-review-summary.json
review-units/<unit_id>/code-review.md
code-review/overview.md
code-review/p0-p1-p2-issues.md
code-review/code-standards.md
code-review/hotspots.md
code-review/full-findings.md
code-review/modules/<module>.md
code-review/index.json
quality-scores.json
people-ranking.md
traceability.json
weekly-report.md
team-risk-assessment.md
data-quality.md
people/<author_key>/weekly-person-report.md
runs/<batch_id>/worker-prompt.md
runs/<batch_id>/session-status.json
```

## 常用命令

运行测试：

```bash
mvn test
```

启动应用：

```bash
mvn spring-boot:run
```

项目使用 SQLite JDBC 保存任务控制台历史。`mvn spring-boot:run` 已在 Maven 插件中配置 SQLite native library 所需的 JVM 参数；如果直接运行打包后的 jar 或在 IDE 中启动，需要在 VM options 中加入：

```bash
--enable-native-access=ALL-UNNAMED
```

临时覆盖主配置：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--opencode-runner.enabled=true --opencode-runner.mode=rerun --opencode-runner.rerun.type=synthesis"
```

查看工作区变更：

```bash
git status --short
```
