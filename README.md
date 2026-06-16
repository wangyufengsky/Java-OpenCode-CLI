# Java OpenCode CLI Runner

这是一个基于 Spring Boot 的 OpenCode 多链路 Runner。主应用只负责选择链路和运行方式，链路自身的业务配置放在独立 YAML 中。

当前支持两条链路：

- `git-code-contribution-report`：代码提交量统计和个人/总报告生成。
- `smartesb-rewrite-code-review`：SmartESB 8583 到 JSON 改造审查。

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
    request-timeout-seconds: 60
    timeout-minutes: 40
```

2. 修改对应链路配置：

- git-report：`src/main/resources/chains/git-code-contribution-report.yml`
- SmartESB：`src/main/resources/chains/smartesb-rewrite-code-review.yml`

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
- `run-date`：SmartESB 使用，格式 `yyyy-MM-dd`；为空时使用应用运行当天日期。
- `config-dir`：链路配置目录，默认 `classpath:chains`。
- `rerun.type`：补跑类型。
- `rerun.id`：补跑目标 ID，例如 authorKey 或 transaction name。
- `opencode.*`：OpenCode Server、模型、并发、超时等共享参数。
- `opencode.session-model`：可选，创建 OpenCode session 时显式指定模型，格式 `provider/model`。内网自定义 provider 建议配置，例如 `spdb-new-api/minimax-m2.7`。
- `opencode.request-timeout-seconds`：单次调用 OpenCode Server API 的 HTTP 超时，例如创建 session、提交 prompt；和整个任务的 `timeout-minutes` 不是同一个超时。
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

### git-report 补跑某个人

```yaml
opencode-runner:
  active-chain: "git-code-contribution-report"
  mode: "rerun"
  rerun:
    type: "author"
    id: "author-001-xxx"
```

`id` 必须是 `index_inputs.json` 中的 `tasks[].author_key`。

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

`full` 只运行 `run-date` 对应交易清单里的交易。

### SmartESB 补跑某个交易

```yaml
opencode-runner:
  active-chain: "smartesb-rewrite-code-review"
  mode: "rerun"
  run-date: "2026-06-16"
  rerun:
    type: "transaction"
    id: "CaRolloutRepeal"
```

`id` 必须存在于当前 `run-date` 的 `transactions.yml`。

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

## git-report 链路配置

配置文件：`src/main/resources/chains/git-code-contribution-report.yml`

核心字段：

```yaml
project:
  id: "upfs-production"
  name: "UPFS Production"
  run-id:

paths:
  repo: "D:/workspace/upfs-production"
  out: "D:/reports/git-code-contribution/2026-06-15"

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

## SmartESB 链路配置

配置文件：`src/main/resources/chains/smartesb-rewrite-code-review.yml`

核心字段：

```yaml
out: "D:/review-output/smartesb-rewrite-review"
local-out:
transaction-plan-dir: "src/main/resources/smartesb-transactions"
old-project: "D:/upfs/qianzhi/upfs-cloud-xc"
new-project: "D:/upfs-nl-json"
legacy-index: "D:/upfs-nl-json/doc/index.md"
doc-root: "D:/upfs-nl-json/doc/docment"
old-8583-doc:
json-doc:
mapping-doc:
reconstructed-design:
batch-size: 5
```

输出目录规则：

- 如果 `local-out` 为空，使用 `out/<run-date>`。
- 如果 `local-out` 不为空，使用 `local-out/<run-date>`。

## SmartESB 每日交易清单

SmartESB 按日期读取交易清单。目录结构：

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
    description: "转账撤销/冲正"
  - name: "CaAcctInfoCheck"
    description: "二三类账户信息验证"
```

规则：

- 日期目录必须是 `yyyy-MM-dd`。
- 每天目录下固定读取 `transactions.yml`。
- `transactions[].name` 必填。
- 同一天内交易名不能重复。
- `description` 可为空。
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

OpenCode 1.17 的 session API 使用 `/session?directory=...` 创建会话，并使用 `/session/{id}/prompt_async?directory=...` 异步提交任务。Runner 会在创建 session 时按 `opencode.session-model` 写入模型，并在 prompt 请求中继续带同一个模型；创建 session 的模型字段为 `model.providerID` + `model.id`，prompt 请求的模型字段为 `model.providerID` + `model.modelID`。

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

```powershell
$env:OPENCODE_DISABLE_MODELS_FETCH = "true"
opencode serve --port 4096 --hostname 127.0.0.1 --print-logs --log-level DEBUG
```

如果需要使用固定模型目录文件，也可以配置：

```yaml
opencode-runner:
  opencode:
    environment:
      OPENCODE_DISABLE_MODELS_FETCH: "true"
      OPENCODE_MODELS_PATH: "D:/opencode/models.json"
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

SmartESB 常见产物：

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

## 常用命令

运行测试：

```bash
mvn test
```

启动应用：

```bash
mvn spring-boot:run
```

临时覆盖主配置：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--opencode-runner.enabled=true --opencode-runner.mode=rerun --opencode-runner.rerun.type=synthesis"
```

查看工作区变更：

```bash
git status --short
```
