# 全链路任务产物隔离与最终产物发布设计

## 目标

为当前五条工作流建立统一的执行产物隔离机制，保证：

- 同一链路的多个运行不会覆盖彼此的 prompt、状态、校验记录或候选产物；
- 同一个业务任务被重复执行时，每次执行和每次尝试均可追踪；
- Agent 只写本次执行的候选产物，失败执行不能破坏已发布产物；
- 最终产物继续保持稳定路径，不因重复执行不断产生面向用户的新文件名；
- 较早开始的任务即使较晚完成，也不能反向覆盖较新的执行结果；
- `full` 和 `rerun` 使用同一套隔离、校验和发布语义。

纳入本设计的链路：

1. `git-code-contribution-report`
2. `project-unit-test-generation`
3. `smartesb-code-reader`
4. `smartesb-rewrite-code-review`
5. `weekly-engineering-report`

## 当前问题边界

控制台已经为每次提交生成数据库 `runId`，`workflow_task_status` 也使用
`(run_id, task_key)` 隔离状态；但是业务链路的文件路径没有使用这个运行身份。

当前常见路径形态是：

```text
<out>/runs/<task-key>/worker-prompt.md
<out>/runs/<task-key>/agent-status.json
<out>/reports/<task-key>/...
```

这会产生四类问题：

1. 相同任务的第二次执行覆盖第一次执行的 prompt 和状态。
2. 两个执行同时写相同候选报告时，Java 可能校验到另一个执行写入的内容。
3. 综合任务开始前直接重置稳定最终文件，失败时会破坏上一次成功结果。
4. AgentBridge `taskId` 在 `runDir` 已经确定以后才生成，只写入状态 JSON，
   不能承担目录隔离职责。

控制台当前使用单线程顶层执行器，只能降低控制台内部两个顶层运行同时执行的概率，
不能作为文件安全契约。CLI 启动、多个应用进程、未来并发执行器以及直接调用链路仍可能
访问同一个输出目录。

`project-unit-test-generation` 还有额外边界：Agent 会直接修改目标仓库的
`src/test/**` 和受控的 `pom.xml`，并通过同一个 IDEA/Maven 环境验证。仅隔离
`paths.out` 无法隔离这些写操作。

## 身份模型

### `consoleRunId`

控制台数据库自增 ID，只用于控制台记录、事件和页面 URL。CLI 运行没有这个 ID。

### `executionId`

每次链路执行的全局唯一文件系统身份，由 Java 在进入链路前创建。建议格式：

```text
run-20260720T153012.123+0800-7f3a91c2
```

要求：

- 控制台和 CLI 都必须生成；
- 不能仅使用数据库自增 ID，避免多个数据库或多个进程写同一 `out` 时碰撞；
- 一次 `rerun` 是新的 `executionId`，不能复用原执行目录；
- `executionId` 必须显式放入 `WorkflowRunRequest`，不能只依赖 `ThreadLocal`。

### `taskKey`

业务任务稳定身份，用来表达“这是哪个任务”。同一任务重跑时保持不变，例如：

```text
author:author-001-wang
test-batch:test-batch-003-order-service
module:BaseConvert8583CUPS
transaction:CaConsume
review-batch:review-unit-017
synthesis:index
```

路径段不能直接使用原始名称。统一编码为安全 slug，并附加短 hash 防止不同原始值
归一化后重名：

```text
transaction-ca-consume-a31f892c
```

### `attempt`

同一个 `executionId + taskKey` 下的新 AgentBridge 会话或完成门重试序号，从 `001`
开始递增。一个 AgentBridge 会话内部的 validation correction 仍记录为
`correctionRound`，不另建 attempt。

### `agentTaskId`

AgentBridge 监控和日志身份。它是遥测字段，不参与业务输出目录计算。状态文件同时记录
`executionId`、`taskKey`、`attempt` 和 `agentTaskId`。

## 统一目录模型

用户配置的 `paths.out`、`out` 或 `local-out` 继续表示稳定发布根目录，不要求用户
手工拼接任何 ID。运行时目录由 Java 自动追加：

```text
<out>/
├── runs/
│   └── <executionId>/
│       ├── run-manifest.json
│       ├── preparation/
│       ├── tasks/
│       │   └── <encodedTaskKey>/
│       │       └── attempts/
│       │           └── 001/
│       │               ├── task.json
│       │               ├── worker-prompt.md
│       │               ├── candidate/
│       │               ├── validation.json
│       │               └── agent-status.json
│       ├── synthesis/
│       │   └── attempts/001/
│       │       ├── synthesis-prompt.md
│       │       ├── candidate/
│       │       ├── validation.json
│       │       └── agent-status.json
│       └── bundle/
├── .publication.json
├── .publish.lock
└── <现有稳定最终产物>
```

目录职责：

- `preparation/`：本次运行的输入快照、任务清单和准备阶段证据；
- `tasks/.../attempts/...`：不可与其他运行共享的任务尝试记录；
- `candidate/`：本次 attempt 允许 Agent 写入的唯一目录；
- `bundle/`：已通过任务级校验、准备交给综合阶段和最终发布的完整运行快照；
- 稳定最终产物：只允许 `ArtifactPublisher` 写入。

任务 JSON 和 prompt 只向 Agent 暴露 `candidate` 路径，不暴露稳定最终路径作为写入目标。
任务校验只读取当前 attempt 的 candidate，不能读取稳定产物来判断成功。

## 执行和发布状态机

```text
CREATED
  -> PREPARING
  -> TASK_RUNNING
  -> TASK_VALIDATING
  -> BUNDLE_READY
  -> SYNTHESIZING
  -> READY_TO_PUBLISH
  -> PUBLISHED
```

失败出口：

```text
PREPARATION_FAILED
TASK_FAILED
SYNTHESIS_FAILED
PUBLISH_FAILED
SUPERSEDED
```

处理规则：

1. full 运行从准备阶段生成新的 `bundle`。
2. rerun 先把当前稳定产物和必要输入快照复制到新运行的 `bundle`，再替换选中任务。
3. 每个任务把 candidate 校验通过后，由 Java 写入本运行的 `bundle`。
4. 综合任务只读取本运行的 `bundle`，不能读取会被其他运行更新的稳定目录。
5. 综合候选产物校验通过后，运行进入 `READY_TO_PUBLISH`。
6. 发布器取得输出根目录的发布锁，执行代次检查，然后更新稳定产物。
7. 所有稳定文件完成替换后，最后原子写入 `.publication.json` 作为完整发布标记。

## 发布一致性和旧任务防覆盖

每个稳定输出根目录维护单调递增的 `publicationGeneration`。执行开始时取得自己的
generation，发布时必须满足：

```text
candidateGeneration == latestRequestedGeneration
```

否则该运行标记为 `SUPERSEDED`，保留完整运行产物和校验结果，但不能更新稳定最终产物。

该策略表达“最新发起的执行拥有发布权”，避免：

```text
运行 A 先开始 -> 运行 B 后开始 -> B 先完成并发布 -> A 后完成又覆盖 B
```

如果最新执行失败，已有成功最终产物保持不变；系统不会自动让更旧的在途执行补位发布。
需要使用旧结果时，应通过显式 rerun 或后续提供的人工发布操作完成。

单文件使用同目录临时文件加 `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`；文件系统
不支持 `ATOMIC_MOVE` 时，在持有发布锁的前提下回退到 `REPLACE_EXISTING`，并记录降级。
多文件报告在发布锁内逐个替换，`.publication.json` 最后写入。控制台和程序消费者只把
manifest 对应的版本视为完整版本。

`.publication.json` 至少记录：

```json
{
  "schemaVersion": "workflow-publication/v1",
  "chainId": "weekly-engineering-report",
  "executionId": "run-...",
  "publicationGeneration": 18,
  "mainArtifact": "weekly-report.md",
  "publishedAt": "2026-07-20T15:40:00+08:00",
  "artifacts": [
    {"path": "weekly-report.md", "sha256": "..."}
  ]
}
```

## `local-out` 与逻辑路径

两条 Sm@rtESB 链路支持逻辑 `out` 和本机 `local-out`。统一路径组件必须维护成对路径：

```text
ArtifactPathPair(logicalPath, localPath)
```

运行目录在两侧追加完全相同的相对片段：

```text
<logical-out>/runs/<executionId>/...
<local-out>/runs/<executionId>/...
```

task JSON/prompt 使用 Agent 所在环境可访问的逻辑 candidate 路径；Java 校验和发布使用
对应的本机 candidate 路径。不能只给本机路径追加 ID 而继续让逻辑路径指向稳定产物。

## 各链路设计

### 1. `git-code-contribution-report`

稳定产物继续保留：

```text
details.json
summary.json
index_inputs.json
index.md
details/<authorKey>.json
reports/<authorKey>/person-report.md
reports/<authorKey>/quality-summary.json
quality-scores.json
code-contribution-report.md
```

full：

- 准备器把所有准备结果写到本运行 `preparation/`；
- author task candidate 使用
  `tasks/author-<authorKey>/attempts/<n>/candidate/reports/<authorKey>/...`；
- author candidate 校验后进入本运行 `bundle/reports/<authorKey>/...`；
- synthesis 从本运行 bundle 读取全部作者报告，候选总报告写入 synthesis candidate；
- 全部通过后一次发布完整 bundle。

author rerun：

- 从当前 `.publication.json` 对应稳定版本建立新 bundle；
- 只重新生成选中的 author candidate；
- 重新计算 `quality-scores.json` 并重新 synthesis；
- 校验完整 bundle 后发布，未选中作者保持原版本。

synthesis rerun：

- 从当前稳定版本建立新 bundle；
- 不修改作者产物，只生成新的 synthesis candidate；
- 校验后发布新的 `code-contribution-report.md`。

必须删除 synthesis 开始前直接把稳定 `code-contribution-report.md` 重置为模板的行为。
模板只能写到本次 synthesis candidate。

### 2. `project-unit-test-generation`

稳定工作流产物继续保留：

```text
unit-test-plan.json
test-batches.json
agentbridge-results.json
unit-test-generation-report.md
```

任务运行记录改为：

```text
runs/<executionId>/tasks/test-batch-<batchId>/attempts/<n>/
├── input.json
├── worker-prompt.md
├── precheck.json
├── postcheck.json
├── mcp-results.json
├── changes.patch
└── agent-status.json
```

这条链路的主要业务产物是目标仓库中的测试代码和受控 POM，不在 `paths.out` 下，因此
额外采用仓库级写锁：

```text
lock key = canonical(project.repo)
lock scope = 整条 unit-test-generation 运行
```

锁必须跨进程有效，不能只使用 JVM `synchronized`。锁覆盖：

- Agent 写测试或 POM；
- IDEA 编译和测试；
- Maven/JaCoCo 构建；
- build artifact 清理；
- 最终结果快照。

当前控制台单线程仍可保留，但不能替代仓库锁。另一个 CLI 或应用进程访问同一仓库时，
应排队或明确失败，不能同时执行。

同一 batch 的 correction 和新 attempt 可以在当前任务状态上继续；任务首次写入前保存
允许写文件的基线。最终失败时：

- 生成 `changes.patch` 供恢复；
- 默认恢复到该任务开始前的允许写文件状态；
- 不触碰任务开始前已经存在的用户修改；
- 可另设显式 `keep-failed-work` 调试选项，但默认关闭。

任务成功后保留仓库修改，并把本 task 的 diff、验证结果写入运行 bundle。所有 batch
完成后才发布稳定的 JSON/Markdown 工作流报告。verification rerun 也必须建立新的
`executionId`，但只读校验，不创建 Agent 写入 attempt。

独立 worktree 是后续可选方案；在 AgentBridge/IDEA 能可靠绑定 worktree 项目之前，
仓库级锁是当前实现成本最低且语义可靠的方案。

### 3. `smartesb-code-reader`

稳定产物继续保留：

```text
tasks/*.json
modules/<module>/analysis.md
modules/<module>/summary.json
transactions/<transaction>/analysis.md
transactions/<transaction>/summary.json
summary.json
index_inputs.json
index.md
```

module/transaction task 的 candidate 分别写入：

```text
tasks/module-<name>/attempts/<n>/candidate/modules/<name>/...
tasks/transaction-<name>/attempts/<n>/candidate/transactions/<name>/...
```

完成门重跑同一 task 时递增 attempt，不覆盖前一次 `worker-prompt.md` 和
`agent-status.json`。module 阶段、transaction 阶段和 index 阶段都只读取本运行
bundle。

module、transaction 或 index rerun 均从当前稳定版本创建新 bundle，更新选择项后再
完整校验和发布。最终 `index.md` 仍保持一个稳定文件。

### 4. `smartesb-rewrite-code-review`

日期仍是稳定业务输出范围：

```text
<out>/<date>/
```

同一天多次运行使用：

```text
<out>/<date>/runs/<executionId>/...
```

当前 task key 已包含 `kind:name`，但部分运行目录和报告目录只使用 name slug；如果模块
和交易同名会冲突。新规范必须把 kind 纳入路径：

```text
reports/modules/<slug>/...
reports/transactions/<slug>/...
runs/<executionId>/tasks/module-<slug>-<hash>/...
runs/<executionId>/tasks/transaction-<slug>-<hash>/...
```

这是五条链路中唯一需要调整部分稳定明细目录的链路。迁移期间：

- 新生成的 `index_inputs.json` 和索引链接使用带 kind 的新目录；
- 没有同名冲突时可生成旧路径兼容镜像；
- 存在同名冲突时禁止生成有歧义的旧路径，以新目录为准。

单项 review candidate 包括：

```text
review.md
summary.json
mapping-matrix.md
sections/*.md
```

全部校验通过后进入本运行 bundle。完成门重跑递增 attempt。index synthesis 只读取
本运行 bundle，候选 `index.md` 和 `summary.md` 通过校验后随完整 bundle 发布。

transaction/module/index rerun 都必须先复制当前稳定 bundle，不能直接在稳定目录上
原地修改。

### 5. `weekly-engineering-report`

稳定产物继续保留：

```text
weekly-git-evidence.json
review-batches.json
review-units.json
weekly-evidence.json
review-units/<batchId>/code-review.md
review-units/<batchId>/code-review-summary.json
code-review/**
people/**
quality-scores.json
people-ranking.md
weekly-report.md
team-risk-assessment.md
traceability.json
data-quality.md
```

full：

- Git evidence、review unit 划分和 input JSON 写入本运行 preparation；
- 每个 review unit 只写自己的 attempt candidate；
- 通过校验后进入本运行 bundle；
- `WeeklyReportRenderer` 改为渲染本运行 bundle，而不是直接渲染稳定 out；
- 全部报告生成完成后一次发布。

review-batch rerun：

- 复制当前稳定 evidence 和未选中的 review unit 到新 bundle；
- 只重新执行选中 batch；
- 重新运行确定性 renderer，生成所有聚合报告；
- 完整校验后发布。

synthesis rerun：

- 不调用 Agent；
- 从当前稳定 evidence 和 review unit 建立新 bundle；
- 重新运行 renderer 并发布。

review unit 的 `summary_json` 和 `review_md` 必须指向 attempt candidate，不能继续指向
稳定 `review-units/<batchId>`。

## 公共组件

### `WorkflowExecutionIdentity`

负责创建和验证：

- `executionId`
- `consoleRunId`
- `chainId`
- `publicationGeneration`
- 创建时间

### `WorkflowArtifactLayout`

唯一负责路径计算。链路不得再自行拼接 `out.resolve("runs").resolve(taskKey)`。

建议接口：

```java
Path runRoot();
Path preparationRoot();
TaskArtifactLayout task(String taskKey);
Path synthesisAttempt(int attempt);
Path bundleRoot();
Path stableRoot();
```

路径解析后必须验证仍位于所属根目录下，拒绝 `..`、绝对 task key 和路径穿越。

### `TaskArtifactLayout`

负责：

```java
Path attemptRoot(int attempt);
Path taskJson(int attempt);
Path prompt(int attempt);
Path candidateRoot(int attempt);
Path validation(int attempt);
Path status(int attempt);
```

### `ArtifactPublisher`

职责：

- 取得跨进程发布锁；
- 检查 publication generation；
- 校验 bundle manifest 和文件 hash；
- 使用临时文件和原子替换更新稳定产物；
- 最后更新 `.publication.json`；
- 返回主产物路径。

### `WorkflowOutputLocator`

每条链路声明主产物：

| 链路 | 主产物 |
| --- | --- |
| Git 贡献报告 | `code-contribution-report.md` |
| 单元测试生成 | `unit-test-generation-report.md` |
| Sm@rtESB 代码阅读 | `index.md` |
| Sm@rtESB 改造审查 | `<date>/index.md` |
| 周度工程报告 | `weekly-report.md` |

发布成功后把主产物写入现有的 `workflow_runs.output_path`。失败、未发布或
`SUPERSEDED` 的运行不更新稳定 output path。

### `RepositoryExecutionLock`

仅 `project-unit-test-generation` 强制使用，按规范化仓库根目录加跨进程独占锁。
锁状态写入 run manifest，包含持有者 executionId、进程信息和获得时间。

## 数据和状态字段

`run-manifest.json` 至少包含：

```json
{
  "schemaVersion": "workflow-run/v1",
  "executionId": "run-...",
  "consoleRunId": 42,
  "chainId": "git-code-contribution-report",
  "mode": "rerun",
  "rerunType": "author",
  "rerunIds": ["author-001-wang"],
  "publicationGeneration": 18,
  "state": "TASK_RUNNING",
  "stableRoot": "/reports/git",
  "createdAt": "..."
}
```

任务状态至少增加：

```text
executionId
taskKey
attempt
agentTaskId
candidateRoot
validationResult
publishedToBundle
```

`workflow_task_status` 继续保存每个 run/task 的最新摘要；完整 attempt 历史保存在运行
目录。后续如需页面查询 attempt，再增加 `workflow_task_attempts` 表，不把所有历史塞入
现有状态表。

## 配置兼容

- 不要求用户修改现有 `paths.out`、`out` 或 `local-out`。
- 不允许用户在 YAML 中自行提供 `executionId`，避免复用和路径穿越。
- `project.run-id`、week label、SmartESB date 都是业务范围标识，不能替代 executionId。
- 原有最终主文件名保持不变。
- Sm@rtESB 改造审查的明细报告目录按 kind 分层，按前述规则提供有限兼容。
- 旧的 `runs/<taskKey>` 目录只读保留，不再写入；不自动迁移为新执行历史。

## 保留与清理

最终产物只保留当前发布版本；运行目录用于审计和排错，可配置：

```text
artifact-retention.success-days
artifact-retention.failed-days
artifact-retention.max-runs-per-output
```

清理规则：

- 只清理终态运行；
- 不能清理 `.publication.json` 当前引用的 executionId；
- 不能清理仍被控制台 RUNNING/QUEUED 记录引用的目录；
- 清理失败只记录告警，不能影响新运行发布。

## 实施顺序

### 第一阶段：公共身份和目录

1. 给 `WorkflowRunRequest` 增加显式 `executionId` 和可选 `consoleRunId`。
2. 控制台和 CLI 统一创建 execution identity。
3. 实现 `WorkflowArtifactLayout`、task key 编码和 run manifest。
4. `AgentBridgeRunMonitor` 接收既有执行身份，不再把自己生成的 `agentTaskId` 当作路径身份。

### 第二阶段：发布器

1. 实现 publication generation、跨进程发布锁和 `.publication.json`。
2. 实现 candidate -> bundle -> stable 的校验发布。
3. 启用 `workflow_runs.output_path` 更新。
4. 增加失败不覆盖旧产物和 stale run 不能发布的测试。

### 第三阶段：报告型链路迁移

按以下顺序迁移：

1. `git-code-contribution-report`
2. `weekly-engineering-report`
3. `smartesb-code-reader`
4. `smartesb-rewrite-code-review`

先迁移 Git/weekly 是因为它们都具有“明细 task + 聚合报告”结构；再迁移两条同时支持
logical/local out 的 Sm@rtESB 链路。

### 第四阶段：单测链路

1. 增加仓库级跨进程锁。
2. 按 execution/task/attempt 记录 prompt、MCP 结果和 diff。
3. 实现最终失败恢复和 `keep-failed-work` 显式选项。
4. 最后迁移稳定 JSON/Markdown 报告发布。

### 第五阶段：控制台和清理

1. 详情页显示 executionId、attempt、candidate、publication 状态。
2. 主产物入口读取 `workflow_runs.output_path`。
3. 显示 `SUPERSEDED`，但不把它误报为执行失败。
4. 增加终态运行目录保留和清理。

## 验证矩阵

公共测试必须覆盖：

- 同一 taskKey、不同 executionId 的所有路径均不重合；
- 同一 taskKey 的 attempt 1/2 不覆盖 prompt、状态和 candidate；
- task key 中的 `/`、`\`、`..`、冒号和非 ASCII 名称不能造成路径逃逸或碰撞；
- 失败任务不能改变稳定最终产物及 `.publication.json`；
- 较旧 execution 在较新 execution 发布后只能进入 `SUPERSEDED`；
- 发布锁在两个 JVM/进程之间有效；
- rerun 未选中任务从稳定版本进入新 bundle，选中任务被替换；
- synthesis 只读取本运行 bundle；
- logical out 和 local out 的相对路径完全一致；
- `workflow_runs.output_path` 只在发布成功后更新。

逐链路测试必须覆盖：

- Git：author/full/synthesis rerun 均不在开始时覆盖稳定总报告；
- 单测：同一仓库不能并发执行，失败 batch 的改动可恢复，已有用户改动不丢失；
- code-reader：module/transaction 同名不冲突，完成门重跑生成新 attempt；
- rewrite-review：module/transaction 同名时稳定明细目录不冲突；
- weekly：review-batch rerun 后所有聚合报告来自同一 bundle；
- 五条链路的主产物路径在多次成功运行后保持不变。

## 验收标准

设计实现完成后应满足：

1. `paths.out` 表示稳定发布根目录，不再同时充当运行工作区。
2. 五条链路所有 Agent 写入路径都位于当前 execution/attempt candidate。
3. 五条链路所有综合阶段都只读取当前运行 bundle。
4. 最终产物只有校验和代次检查通过后才更新。
5. 相同任务重复运行可追踪，但最终产物路径不随执行次数变化。
6. 任意失败、超时或被更新执行取代的运行都不会破坏现有成功产物。
7. 单测链路对目标仓库的共享写入受到跨进程锁保护。
