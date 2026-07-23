# Java AgentBridge CLI Runner

基于 Spring Boot 的本地 AgentBridge 工作流运行器，用于代码贡献报告、SmartESB 审查、代码阅读、周报、单元测试生成和 MyBatis XML SQL 审查等链路。

Java 侧负责工作流准备、prompt 提交、空闲轮询、输出校验、修正 prompt 和报告渲染。AgentBridge 是唯一的任务执行入口。

## 配置示例

```yaml
agentbridge-runner:
  enabled: true
  active-chain: git-code-contribution-report
  mode: full
  run-date:
  config-dir: classpath:chains
  rerun:
    type:
    id:
  agentbridge:
    web-base-url: "https://127.0.0.1:9642"
    mcp-url: "http://127.0.0.1:8643/mcp"
    concurrency: 1
    max-concurrency: 1
    timeout-minutes: 40
    poll-millis: 1000
    validation-settle-seconds: 30
    validation-max-corrections: 2

task-console:
  database-path: "data/agentbridge-task-console.sqlite"
  run-config-dir: "data/run-configs"
```

链路配置文件位于 `src/main/resources/chains`。链路本地的 `agentbridge` 配置可以在支持的链路中覆盖共享运行参数。

任务执行消息字段使用 `task-message` 和 `synthesis-task-message`。代码贡献报告链路把它们放在 `agentbridge` 下，SmartESB 链路把它们作为顶层链路字段。

## 配置字段说明

### `agentbridge-runner`

| 字段 | 说明 |
| --- | --- |
| `enabled` | Spring Boot 应用启动时是否自动执行一个工作流。只使用控制台时保持 `false`。 |
| `active-chain` | `enabled=true` 时要执行的链路 ID。可选值见下方链路列表。 |
| `mode` | 运行模式。`full` 表示完整运行，`rerun` 表示局部重跑。 |
| `run-date` | 可选运行日期，格式为 `yyyy-MM-dd`。按日期分目录的链路会使用它；为空时由链路使用默认日期。 |
| `config-dir` | 链路 YAML 配置所在目录或 classpath 位置，通常是 `classpath:chains`。 |
| `rerun.type` | 重跑目标类型。不同链路支持的值不同。 |
| `rerun.id` | 需要指定目标的重跑类型使用该字段。多个目标可用英文逗号分隔。 |

### `agentbridge-runner.agentbridge`

这些是共享的 AgentBridge 运行时参数，不会替代链路本地的任务执行消息。

| 字段 | 说明 |
| --- | --- |
| `web-base-url` | AgentBridge Web Access 基础地址，用于提交 prompt 和轮询运行状态。 |
| `mcp-url` | AgentBridge MCP JSON-RPC 地址，供需要 MCP 校验或工具调用的链路使用。 |
| `concurrency` | 多任务链路请求的并发任务数。 |
| `max-concurrency` | 并发上限。支持并发的链路会把实际并发限制在至少 `1` 且不超过该值。 |
| `timeout-minutes` | 单个 AgentBridge task 的最大等待时间。 |
| `poll-millis` | 等待 AgentBridge task 状态时的轮询间隔。 |
| `validation-settle-seconds` | AgentBridge 报告空闲后，Java 开始校验输出文件前额外等待的秒数。 |
| `validation-max-corrections` | 输出校验失败后，Java 最多发送多少轮同 task 修正 prompt。 |

### `task-console`

| 字段 | 说明 |
| --- | --- |
| `database-path` | 控制台运行记录、事件和定时任务使用的 SQLite 数据库路径。 |
| `run-config-dir` | 控制台提交运行时生成的运行 YAML 文件目录。 |

## 链路列表

- `git-code-contribution-report`
- `smartesb-rewrite-code-review`
- `smartesb-code-reader`
- `weekly-engineering-report`
- `project-unit-test-generation`
- `mybatis-sql-review`

### 重跑类型

| 链路 | `rerun.type` 可选值 | `rerun.id` |
| --- | --- | --- |
| `git-code-contribution-report` | `author`、`synthesis` | `author` 需要一个或多个 author key；`synthesis` 不需要编号。 |
| `smartesb-rewrite-code-review` | `transaction`、`module`、`index` | `transaction` 和 `module` 需要名称；`index` 不需要编号。 |
| `smartesb-code-reader` | `transaction`、`module`、`index` | `transaction` 和 `module` 需要名称；`index` 不需要编号。 |
| `weekly-engineering-report` | `review-batch`、`synthesis` | `review-batch` 需要一个或多个 batch id；`synthesis` 不需要编号。 |
| `project-unit-test-generation` | `test-batch`、`verification` | `test-batch` 需要一个或多个 batch id；`verification` 不需要编号。 |
| `mybatis-sql-review` | `sql`、`xml`、`index` | `sql` 需要一个或多个 statement key，`xml` 需要一个或多个 mapper key；`index` 不需要编号。 |

## 链路配置字段说明

### `git-code-contribution-report`

示例：

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
  author-map:
  include: []
  exclude:
    - "target/**"
    - "*.lock"

agentbridge:
  task-message: "严格执行附件 worker-prompt.md 中的任务，完成后回复简短完成信息即可，Java 会校验输出。"
  synthesis-task-message: "严格执行附件 synthesis-prompt.md 中的任务，生成最终中文总报告。"

detail-input:
  top-files: 10
  commits: 20
  changed-regions: 40
  changed-region-lines: 24

synthesis-input:
  person-report-excerpt-chars: 8192
  snippets-per-author: 5
  snippets-total: 30
  snippet-lines: 20
```

| 字段 | 说明 |
| --- | --- |
| `project.id` | 写入报告元信息的项目 ID。 |
| `project.name` | 报告中展示的项目名称。 |
| `project.run-id` | 可选外部运行 ID 或批次 ID。 |
| `paths.repo` | 要统计的本地 Git 仓库。 |
| `paths.out` | 准备输入、任务状态和报告输出目录。 |
| `git.since` | Git 统计开始日期，格式为 `yyyy-MM-dd`。 |
| `git.until` | Git 统计结束日期，格式为 `yyyy-MM-dd`。 |
| `git.revision` | 要统计的 Git revision，通常为 `HEAD`。 |
| `git.include-merges` | 是否把 merge commit 纳入贡献统计。 |
| `git.author-map` | 可选作者身份映射文件。 |
| `git.include` | 可选 include glob。为空表示不限制包含路径。 |
| `git.exclude` | 排除构建产物、锁文件、生成文件等噪声路径的 glob。 |
| `agentbridge.task-message` | 发送给每个作者明细 task 的附加执行消息。 |
| `agentbridge.synthesis-task-message` | 发送给最终汇总 task 的附加执行消息。 |
| `detail-input.top-files` | 每个作者明细输入保留的高影响文件数量。 |
| `detail-input.commits` | 每个作者明细输入保留的提交数量。 |
| `detail-input.changed-regions` | 每个作者明细输入保留的 changed region 数量。 |
| `detail-input.changed-region-lines` | 每个 changed region 保留的上下文行数。 |
| `synthesis-input.person-report-excerpt-chars` | 汇总阶段读取每份个人报告的最大字符数。 |
| `synthesis-input.snippets-per-author` | 汇总阶段每个作者最多保留的代码片段数。 |
| `synthesis-input.snippets-total` | 汇总阶段最多保留的代码片段总数。 |
| `synthesis-input.snippet-lines` | 每个汇总代码片段最多保留的行数。 |

### `smartesb-rewrite-code-review`

示例：

```yaml
out: "/home/wangyufeng/review-output/smartesb-rewrite-review"
local-out:

transaction-plan-dir: "src/main/resources/smartesb-transactions"

new-project: "/home/wangyufeng/upfs-nl-json"
old-8583-doc: "/home/wangyufeng/upfs-nl-json/doc/docment/old-8583.md"
doc-root: "/home/wangyufeng/upfs-nl-json/doc/docment"
mapping-doc:
reconstructed-design:

task-message: "严格执行附件 worker-prompt.md 中的 SmartESB 单项审查任务，完成后回复简短完成信息即可，Java 会校验输出。"
synthesis-task-message: "严格执行附件 synthesis-prompt.md 中的 SmartESB 汇总任务，生成中文 index.md 和 summary.md。"
```

| 字段 | 说明 |
| --- | --- |
| `out` | 逻辑输出目录，链路会在其下按运行日期创建子目录。 |
| `local-out` | 可选本机实际落盘目录。为空时直接使用 `out`。 |
| `transaction-plan-dir` | 包含 `yyyy-MM-dd/transactions.yml` 的交易计划目录。 |
| `new-project` | 新 JSON 项目根目录，也是 AgentBridge task 工作目录。 |
| `old-8583-doc` | 老 8583 设计文档，作为审查参考。 |
| `doc-root` | 文档根目录，用于解析默认映射文档和重构设计文档路径。 |
| `mapping-doc` | 可选 8583 到 JSON 映射文档。为空时默认 `doc-root/8583 to json.md`。 |
| `reconstructed-design` | 可选重构设计文档。为空时默认 `doc-root/重构项目详细设计文档.md`。 |
| `task-message` | 发送给每个交易或模块审查 task 的附加执行消息。 |
| `synthesis-task-message` | 发送给 SmartESB 汇总/index task 的附加执行消息。 |

### `smartesb-code-reader`

示例：

```yaml
out: "/home/wangyufeng/review-output/smartesb-code-reader"
local-out:

service-identify:
  - "/home/wangyufeng/upfs-production/serviceIdentify.xml"

xml-root: "/home/wangyufeng/upfs-production"
biz-root:
java-root: "/home/wangyufeng/upfs-production"
mode: "8583"

task-message: "严格执行附件 worker-prompt.md 中的 SmartESB code-reader 单项阅读任务，完成后回复简短完成信息即可，Java 会校验输出。"
synthesis-task-message: "严格执行附件 synthesis-prompt.md 中的 SmartESB code-reader 索引任务，生成中文 index.md。"
```

| 字段 | 说明 |
| --- | --- |
| `out` | code-reader 产物的逻辑输出目录。 |
| `local-out` | 可选本机实际落盘目录。为空时直接使用 `out`。 |
| `service-identify` | 一个或多个 `serviceIdentify.xml` 路径。 |
| `xml-root` | 交易 XML 和 base XML 根目录。 |
| `biz-root` | 可选 `.biz` 根目录。为空时使用 `xml-root`。 |
| `java-root` | Java 源码根目录，也是 AgentBridge task 工作目录。 |
| `mode` | 从 `serviceIdentify.xml` 中读取的 switch mode 值。 |
| `task-message` | 发送给每个模块或交易阅读 task 的附加执行消息。 |
| `synthesis-task-message` | 发送给索引汇总 task 的附加执行消息。 |

### `weekly-engineering-report`

示例：

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
  max-hunk-lines: 24
  concurrency: 3
  grouping:
    strategy: "module-author-capacity"
    target-task-count: 80
    max-regions-per-task: 80
    max-files-per-task: 25
    max-hunk-chars-per-task: 80000
    max-commits-per-task: 40

agentbridge:
  timeout-minutes: 40
```

| 字段 | 说明 |
| --- | --- |
| `project.id` | 写入周报元信息的项目 ID。 |
| `project.name` | 周报中展示的项目名称。 |
| `project.repo` | 周报分析使用的本地 Git 仓库。 |
| `paths.out` | 周报产物输出目录。 |
| `startday` | 周报统计窗口开始日期，格式为 `yyyy-MM-dd`。 |
| `endday` | 周报统计窗口结束日期，格式为 `yyyy-MM-dd`。 |
| `git.exclude` | 排除构建产物、锁文件、生成文件等噪声路径的 glob。 |
| `review.max-hunk-lines` | 审查输入中每个 hunk 保留的最大行数。 |
| `review.concurrency` | 周报链路内部代码审查任务并发数。 |
| `review.grouping.strategy` | review unit 分组策略，默认 `module-author-capacity`。 |
| `review.grouping.target-task-count` | 期望分组后的 review task 数量。 |
| `review.grouping.max-regions-per-task` | 单个 review task 最多包含的 changed region 数量。 |
| `review.grouping.max-files-per-task` | 单个 review task 最多覆盖的文件数量。 |
| `review.grouping.max-hunk-chars-per-task` | 单个 review task 最多包含的 hunk 字符数。 |
| `review.grouping.max-commits-per-task` | 单个 review task 最多覆盖的 commit 数量。 |
| `agentbridge.timeout-minutes` | 周报审查 task 的 AgentBridge 超时时间。需要时也可配置其它通用 `agentbridge` 运行参数。 |

### `project-unit-test-generation`

示例：

```yaml
project:
  id: "example-project"
  name: "Example Project"
  repo: "CHANGE_ME_PROJECT_REPO"

paths:
  out: "project-unit-tests/example-project"

docs:
  agents: "AGENTS.md"
  project-map: "project-map.md"
  reconstructed-design: "重构项目详细设计文档.md"

source:
  package-paths: []
  include: []
  exclude:
    - "target/**"
    - "build/**"
    - "generated/**"
    - "**/target/**"
    - "**/build/**"
    - "**/generated/**"

test:
  require-coverage: false
  coverage-threshold-percent: 90
  jacoco-version: "0.8.15"
  jacoco-jvm-arg-property: "sqlite.native.access.argument"
  jacoco-jvm-arg-base: "--enable-native-access=ALL-UNNAMED"

agentbridge:
  web-base-url: "https://127.0.0.1:9642"
  mcp-url: "http://127.0.0.1:8643/mcp"
  timeout-minutes: 40
  max-attempts: 5
```

| 字段 | 说明 |
| --- | --- |
| `project.id` | 写入任务和报告元信息的项目 ID。 |
| `project.name` | 任务和报告标题中展示的项目名称。 |
| `project.repo` | 要生成单元测试的本地项目仓库。 |
| `paths.out` | 测试任务、运行状态和验收报告输出目录。 |
| `docs.agents` | 可选 `AGENTS.md` 路径，相对于 `project.repo`。 |
| `docs.project-map` | 可选 `project-map.md` 路径，相对于 `project.repo`。 |
| `docs.reconstructed-design` | 可选重构设计文档路径，相对于 `project.repo`。 |
| `source.package-paths` | 要处理的包名或源码路径。为空时扫描全部 `src/main/java`。 |
| `source.include` | 可选 include glob。 |
| `source.exclude` | 排除构建输出、生成代码等不应处理路径的 glob。 |
| `test.require-coverage` | 是否要求覆盖率达标。默认 `false`；开启后才执行 JaCoCo 覆盖率验收。 |
| `test.coverage-threshold-percent` | `test.require-coverage=true` 时，当前源码类达到该覆盖率百分比后，当前 batch 才算达标。 |
| `test.jacoco-version` | `test.require-coverage=true` 时，验收命令使用的 JaCoCo 版本。 |
| `test.jacoco-jvm-arg-property` | `test.require-coverage=true` 时，目标项目 Maven/Surefire 实际读取的 JVM 参数属性；普通项目通常是 `argLine`。 |
| `test.jacoco-jvm-arg-base` | `test.require-coverage=true` 时，可选 JVM 基础参数，会追加在 JaCoCo agent 参数前。 |
| `agentbridge.web-base-url` | AgentBridge Web Access 地址，用于提交 prompt 和轮询状态。 |
| `agentbridge.mcp-url` | AgentBridge MCP JSON-RPC 地址，用于 Java 侧验收测试。 |
| `agentbridge.timeout-minutes` | 单个单元测试生成 task 的最大等待时间；JaCoCo/Maven 验收命令的 MCP 请求等待时间也会按该值放大。 |
| `agentbridge.max-attempts` | 当前 batch 被判定失败前最多启动 agent 的次数。 |

### `mybatis-sql-review`

该链路只发现 MyBatis XML 中的顶层 `select`、`insert`、`update`、`delete` 和独立 `selectKey`。Java 负责 XML 清单、串行任务、工具调用事后审计、强产物校验和原子发布；DML 与 `selectKey` 只做静态审查，不会执行原语句。稳定输出包含 `sql-inventory.json`、`sql-tasks.json`、`traceability.json`、`data-quality.md`、mapper 索引、逐 SQL 三文件和 `mybatis-sql-review-report.md`，控制台继续在运行记录的 `output_path` 中展示发布目录。

示例：

```yaml
project:
  id: "example-project"
  name: "Example Project"
  repo: "CHANGE_ME_PROJECT_REPO"

paths:
  out: "mybatis-sql-review/example-project"

source:
  include:
    - "**/*Mapper.xml"
  exclude:
    - "target/**"
    - "build/**"
    - "**/target/**"
    - "**/build/**"

database:
  connection-name: "CHANGE_ME_AGENTBRIDGE_CONNECTION_NAME"
  database-name: "CHANGE_ME_DATABASE"
  schema-name: "CHANGE_ME_SCHEMA"
  environment: "read-replica"
  non-owner-non-admin-read-only-account: false
  row-level-security-disabled-for-safe-base-tables: false
  user-defined-and-security-definer-function-execution-revoked-including-public: false
  statement-timeout-seconds: 30
  statement-timeout-scope: "role"
  max-rows: 20
  max-scenarios-per-sql: 3
  max-evidence-bytes: 262144
  retain-raw-rows: true
  allow-agent-select: true

agentbridge:
  web-base-url: "https://127.0.0.1:9642"
  mcp-url: "http://127.0.0.1:8643/mcp"
  concurrency: 1
  max-concurrency: 1
  timeout-minutes: 40
  poll-millis: 1000
  validation-settle-seconds: 30
  validation-max-corrections: 0
  task-message: "严格执行完整 MyBatis SQL review prompt，只写指定 candidate 三文件；完成后回复简短完成信息，Java 将进行工具历史和产物强校验。"
```

| 字段 | 说明 |
| --- | --- |
| `project.id` / `project.name` / `project.repo` | 项目标识、展示名称和 MyBatis mapper 所在的本地仓库。 |
| `paths.out` | 隔离运行、稳定报告和控制台 `output_path` 对应的输出根目录。 |
| `source.include` / `source.exclude` | Mapper XML 的 include/exclude glob；include 至少包含一项。 |
| `database.connection-name` | AgentBridge Custom MCP 注册的 Database MCP 数据源名，必须唯一匹配。 |
| `database.database-name` / `database.schema-name` | 每次工具调用都必须绑定的数据库与 schema。 |
| `database.environment` | 固定为 `read-replica`；必须由 AgentBridge 连接元数据标记为 server-observed physical standby，并由数据库探针的 `pg_is_in_recovery()` 再次证明。每个 SQL task 提交前还会重验同一 AgentBridge、数据库主机/实例/拓扑指纹、physical standby 和连接可用性。测试库或其它自证环境标签均不接受。 |
| `database.non-owner-non-admin-read-only-account` | 必须为 `true`，确认凭证属于非 owner、非管理员的专用只读账号。 |
| `database.row-level-security-disabled-for-safe-base-tables` | 必须为 `true`，确认所有可取证基础表均禁用 RLS。 |
| `database.user-defined-and-security-definer-function-execution-revoked-including-public` | 必须为 `true`，确认审计账号无法执行用户定义函数和 `SECURITY DEFINER` 函数，并已撤销 `PUBLIC` 的 `EXECUTE`。 |
| `database.statement-timeout-seconds` / `statement-timeout-scope` | 数据库、server 或 role 级硬超时；必须大于 0 且不超过 30 秒。 |
| `database.max-rows` / `max-scenarios-per-sql` | 固定为每次 20 行、每条 SQL 最多 3 个代表性 SELECT 场景。 |
| `database.max-evidence-bytes` | 单 task 数据库证据固定最多 262144 字节。 |
| `database.retain-raw-rows` / `allow-agent-select` | 固定为 `true`；保留可复核证据，并仅允许受 Java 策略约束的简单 SELECT。 |
| `agentbridge.web-base-url` / `mcp-url` | AgentBridge Web Access 与 AgentBridge MCP 地址；默认 MCP 地址为 `http://127.0.0.1:8643/mcp`。 |
| `agentbridge.concurrency` / `max-concurrency` | 两者固定为 `1`，所有 statement task 严格串行。 |
| `agentbridge.timeout-minutes` / `poll-millis` / `validation-settle-seconds` | AgentBridge task 超时、轮询和工具历史/产物沉降等待。 |
| `agentbridge.validation-max-corrections` | 固定为 `0`；每个 SQL task 只有一个完整 prompt 和一次候选产物尝试。 |
| `agentbridge.task-message` | 附加到完整 SQL 审查 prompt 前的执行说明。 |

#### Database MCP 与安全边界

Database MCP 作为 AgentBridge Custom MCP 注册工具运行。应用预检与 review agent 都通过 AgentBridge MCP 调用它，默认地址为 `http://127.0.0.1:8643/mcp`。MyBatis SQL review 使用以下四个 native read tools：

- `cmcp_db_database_list_datasources`
- `cmcp_db_database_list_databases`
- `cmcp_db_database_list_table_schema`
- `cmcp_db_database_execute_sql_query`

每次调用都携带 `project` 和 `scope`；`scope` 取 `GLOBAL`、`PROJECT` 或 `ALL`，默认 `ALL`。`cmcp_db_database_execute_sql_query` 的参数精确为 `dataSource`、`sql`、`maxRows: 20`、`project` 和 `scope`。DML、DDL、NoSQL 与未知工具均禁止调用；`insert`、`update`、`delete` 与 `selectKey` 仅进行静态审查。

数据库连接指向集中式 GaussDB 物理只读副本，使用非 owner、非管理员、无角色继承的独立只读账号。安全预检确认可取证基础表的 RLS 状态、函数执行权限、有效 `statement_timeout` 以及账号没有写入或危险对象权限；任一安全事实无法证明时停止运行。数据库凭证隔离、权限与超时构成调用前的安全边界。

AgentBridge 版本门槛为 `>=1.202.0`。完成 MCP session negotiation 后，Java 通过 MCP `tools/list` 与 `/tool-calls` 的 `items` 获取工具和调用证据。Java 审计 SQL grammar、safe relations 和 scenario count；`cmcp_db_database_execute_sql_query` 的 query 传 `maxRows: 20`。数据库 `statement_timeout` 提供执行超时。

## 运行时行为

每个 task 会向 AgentBridge `/prompt` 提交 prompt，等待 `/info.running=false`，然后校验预期输出文件。校验失败且仍有修正轮次时，Java 会发送修正 prompt 并再次校验。

任务状态写入 `agent-status.json`，包含 `taskId`、`agentbridgeWebBaseUrl`、`state`、`timedOut`、`completedByOutput`、`agentState`、`finishedAt` 和 `error` 等字段。

## 运行方式

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--agentbridge-runner.enabled=true --agentbridge-runner.active-chain=mybatis-sql-review"
```

重跑示例：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--agentbridge-runner.enabled=true --agentbridge-runner.mode=rerun --agentbridge-runner.rerun.type=synthesis"
```
