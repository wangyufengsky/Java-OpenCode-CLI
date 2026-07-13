# Java-OpenCode-CLI 全仓代码审查报告

> 审查日期：2026-07-10
>
> 审查对象：`master` 当前工作树（`master...origin/master`，包含 5 个未提交修改）
>
> 审查范围：生产 Java、测试、YAML、prompt/template/skill 资产、Python 辅助脚本、Web 控制台、构建与仓库卫生
>
> 结论性质：静态审查 + 本地构建/测试复验；未修改业务代码

## 1. 执行摘要

项目的核心工作流边界已经比较清楚：Java 负责准备事实、编排 AgentBridge、校验产物和持久化状态，AgentBridge 负责生成或审查内容。当前测试覆盖也不弱，清理测试生成的缓存后 Maven 测试可全部通过，3 套 Python 资源测试也全部通过。

但仓库目前仍存在几类会直接影响安全性和结果可信度的问题：

1. 仓库历史中已提交 IDE 生成的 VAPID 私钥；当前 `origin/master` 同步状态下，该秘密也已进入远端历史。
2. Web 控制台没有认证、授权和 CSRF 防护，配置又允许指定本地仓库、输出目录和 AgentBridge 地址；若未由外部配置限制监听地址，局域网访问者可触发高权限本地工作流。
3. AgentBridge HTTP 客户端无条件信任所有 TLS 证书并关闭主机名校验，且不限于 loopback 地址。
4. 多任务并发共享同一个 AgentBridge 会话：每个任务都会先执行 `/session-clear`，完成状态却只读取全局 `/info.running`。一旦并发大于 1，任务之间会互相清会话、混淆状态和输出归属。
5. 定时任务线程的顶层回调会让异常逃出；`scheduleWithFixedDelay` 的周期任务一旦抛异常，后续调度会被永久抑制。
6. 最终贡献报告的 Java 校验只检查标题、链接和占位符，不核对作者、排名、分数与 `synthesis-inputs.json` / `quality-scores.json`；结构正确但数据错误的报告会被接受。
7. 超过 50,000 行的提交会把原始增删行直接写成“去注释增删行”，同时跳过全部 changed regions；产物中没有标记这是估算，周报审查也会因此缺失代码审查输入。

综合判断：当前版本适合“可信本机、串行、人工关注日志”的开发使用，不宜在未加固前作为可被其他机器访问的长期服务，也不宜把 AgentBridge 并发配置调到 1 以上。

## 2. 审查基线与方法

### 2.1 仓库基线

- Git：`master...origin/master`。
- 当前未提交修改：5 个文件，37 行新增、1 行删除；审查期间原样保留。
- 生产 Java：110 个文件。
- 测试类：43 个 `*Test.java` 文件。
- 受版本控制文件：243 个。
- 主要源码/配置/文档总量：约 30,515 行。
- Spring Boot：4.0.5。
- `pom.xml` 目标 Java：21。
- 本次 Maven 实际运行 JDK：25.0.2。
- IntelliJ 项目 SDK：26（`.idea/misc.xml:11`）。
- 当前机器未安装 JDK 21，因此没有在目标运行时上执行测试。

### 2.2 已执行验证

| 验证 | 结果 |
| --- | --- |
| `mvn -q test`（清理 Python 缓存后） | 通过，Surefire XML 汇总 206 tests / 0 failures / 0 errors / 0 skipped |
| git contribution skill Python tests | 28/28 通过 |
| SmartESB rewrite skill Python tests | 7/7 通过 |
| SmartESB code-reader skill Python tests | 1/1 通过 |
| `git diff --check` | 通过 |
| Maven runtime dependency tree | 成功生成 |
| 定向秘密检索 | 命中 `.idea/chatWebServer.xml` 的 VAPID 私钥 |

### 2.3 限制

- GitNexus MCP 服务握手失败，因此架构与调用链以当前源码、Git 历史和测试为事实源。
- 未启动真实 AgentBridge 做端到端写入验证，以免审查任务修改目标仓库或当前 IDE 会话。
- 当前环境没有 `gitleaks` / `trufflehog`，秘密检查是定向规则扫描，不等价于完整历史熵扫描。
- 未进行联网 CVE 数据库扫描；本报告只确认依赖结构和构建配置，不声明依赖“无漏洞”。

## 3. 优先级总览

| 编号 | 优先级 | 类型 | 问题 | 确定性 |
| --- | --- | --- | --- | --- |
| SEC-01 | P1 | 安全 | 版本库跟踪 VAPID 私钥 | 已证实 |
| SEC-02 | P1 | 安全 | 控制台无认证/授权/CSRF，且未显式绑定 loopback | 已证实；网络可达性取决于外部启动配置 |
| SEC-03 | P1 | 安全 | AgentBridge 客户端信任任意证书并关闭主机名校验 | 已证实 |
| RUN-01 | P1 | 正确性 | AgentBridge 并发任务共享全局会话与全局运行状态 | 已证实的竞争设计；真实后果需 E2E 复验 |
| RUN-02 | P1 | 可用性 | 一次定时触发异常可永久停止所有后续调度 | 已证实 |
| DATA-01 | P1 | 数据正确性 | 最终报告只做结构校验，不校验排名/分数/作者事实 | 已证实 |
| DATA-02 | P1 | 数据正确性 | 超大提交把原始行数冒充去注释行数并静默丢失审查区域 | 已证实 |
| OPS-01 | P1 | 可用性 | Git 子进程无超时、无输出上限、无取消传播 | 已证实 |
| TEST-01 | P2 | 测试 | Python 测试生成缓存后会让 Maven 架构测试失败 | 已复现 |
| RUN-03 | P2 | 配置 | 运行时并发配置与启动时线程池容量不一致 | 已证实 |
| CFG-01 | P2 | 配置 | 周报部分配置字段无行为效果或仅写入元数据 | 已证实 |
| CFG-02 | P2 | 配置 | 配置缺少统一边界校验，非法数值延迟到运行期失败 | 已证实 |
| PERF-01 | P2 | 性能 | 每次事件发布都重新读取完整事件历史 | 已证实 |
| DEP-01 | P2 | 依赖 | Spring Boot 4 默认 Jackson 3 与显式 Jackson 2 双栈共存 | 已证实 |
| ASSET-01 | P2 | 维护性 | runtime prompt-pack 与内置 skill 同名资产长期分叉 | 已证实；是否合并需先确定产品边界 |
| IO-01 | P2 | 可靠性 | 关键 JSON/Markdown/状态文件均为非原子覆盖写 | 已证实 |
| SSE-01 | P3 | 控制台 | SSE 订阅存在历史/实时竞态与空列表键残留 | 代码路径可证实 |
| BUILD-01 | P3 | 工程化 | 无 Maven Wrapper、CI、覆盖率门禁、静态检查和依赖扫描 | 已证实 |
| UI-01 | P3 | 可访问性 | 缺少 focus-visible、aria-live、日期本地化和提交中状态 | 已证实 |
| MAINT-01 | P3 | 维护性 | 多个核心类超过 400 行，职责持续聚合 | 已证实 |
| DEAD-01 | P3 | 维护性 | `ScheduledProbeWaiter` 注入后未使用，`output_path` 字段从未写入 | 已证实 |

## 4. 详细发现

### SEC-01 [P1] 版本库已提交 VAPID 私钥

证据：

- `.idea/chatWebServer.xml:4` 包含 `vapidPrivateKey`，本报告不复述其值。
- `.idea/chatWebServer.xml:5` 同时包含对应公钥。
- 该文件由提交 `ffd525e` 引入；当前本地 `master` 与 `origin/master` 同步。
- `.idea/.gitignore:12-13` 只忽略 `mcpServer.xml`，没有忽略 `chatWebServer.xml`。

影响：

- 私钥已经进入 Git 对象和远端历史，单纯在下一个提交删除文件不能撤销泄露。
- 如果远端仓库、备份、镜像或日志曾被其他人读取，必须按已泄露处理。

建议：

1. 立即轮换这对 VAPID 凭据。
2. 从当前索引移除 `.idea/chatWebServer.xml`，并加入忽略规则。
3. 根据仓库可见性决定是否用 `git filter-repo` 清理全部历史；清理历史需要团队协调和强制更新远端，不应在普通修复提交中直接执行。
4. 在 CI 增加秘密扫描，并对高熵私钥、IDE 凭据文件做阻断。

验收：当前树和全 Git 历史的秘密扫描均不再命中旧私钥；新私钥已轮换；IDE 本地文件不再被跟踪。

### SEC-02 [P1] 控制台可触发高权限工作流，但没有访问控制

证据：

- `pom.xml` 没有 Spring Security 依赖。
- `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleApiController.java:48-71` 暴露运行提交、创建定时任务和启停定时任务的写接口。
- `src/main/resources/application.yml` 未配置 `server.address`，也没有认证、授权或 CSRF 配置。
- `src/main/resources/static/app.js:305-408` 直接向写接口提交 JSON，没有 CSRF token。
- 配置允许提供仓库路径、输出路径以及 AgentBridge Web/MCP 地址；服务器进程会据此读 Git 仓库、写文件并向本机 AgentBridge 发送 prompt。

影响：

- 若未被启动参数、容器或防火墙覆盖，Spring Boot 默认监听所有本机接口；同网段访问者可能创建任务、读取运行历史或改变定时任务状态。
- 该控制台不是普通只读仪表盘，它可间接驱动本机 AgentBridge 修改代码和生成文件，影响级别接近本机自动化控制面。

建议：

1. 最低限度在默认配置中设置 `server.address: 127.0.0.1`。
2. 如确需远程访问，加入认证、角色授权、CSRF、防暴力请求和审计日志；优先置于受控反向代理/VPN 后。
3. 对可访问的仓库根目录、输出根目录和 AgentBridge 地址建立 allowlist，拒绝任意绝对路径和非 loopback 地址。
4. 默认禁用定时任务写接口或提供独立开关。

验收：未认证请求无法读取运行记录或提交/启停任务；默认端口只监听 loopback；越界路径和外部 AgentBridge URL 被拒绝。

### SEC-03 [P1] AgentBridge HTTP 客户端关闭全部 TLS 身份验证

证据：

- `src/main/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClient.java:165-178` 的 `X509TrustManager` 接受任意服务端证书。
- `AgentBridgeClient.java:179-184` 把 endpoint identification algorithm 设为空，关闭主机名校验。
- `AgentBridgeSettings.java:19-28` 和控制台提交对象没有限制 URL 必须是 `127.0.0.1` / `localhost`。

影响：任何能劫持网络或 DNS 的实体都能伪装成配置的 AgentBridge 服务，读取 prompt、伪造状态或工具结果。当前实现把“本地自签名证书的便利”扩大成了“所有目标都不校验证书”。

建议：

- 默认使用系统 trust store 和主机名校验。
- 只有在 URI 明确为 loopback 时，才允许显式的 `insecure-local-tls=true` 兼容模式。
- 更稳妥的本地方案是固定 AgentBridge 证书指纹或使用本机 HTTP/Unix socket，并在应用层验证来源。

验收：外部 HTTPS 地址的自签名/主机名不匹配证书被拒绝；仅显式允许的 loopback 开发模式可绕过。

### RUN-01 [P1] AgentBridge 并发任务没有会话隔离

证据：

- `AgentBridgeTaskRunner.java:54-55` 每个任务都先发送 `/session-clear`，随后发送任务 prompt。
- `AgentBridgeClient.java:65-72` 只读取全局 `/info.running`，没有 task/session id。
- `AgentBridgeRunMonitor.java:53` 生成的 `taskId` 只是 Java 本地 UUID，没有发给 AgentBridge，不能用于关联远端任务。
- `GitReportOrchestrator.java:249-258`、`WeeklyAgentBridgeReviewRunner.java:60-68`、`SmartEsbWorkflowChain.java:125-140` 和 code-reader 链路都支持并发调用同一个 `AgentBridgeTaskRunner`。

并发交错示例：

```text
任务 A: /session-clear
任务 B: /session-clear
任务 A: prompt A
任务 B: prompt B
任务 A/B: 同时轮询同一个 /info.running
```

影响：会话上下文、运行完成判定、修正 prompt 和文件归属可能互相污染。即使 AgentBridge 内部把 prompt 排队，Java 侧也无法知道当前 `running` 属于哪个任务。

建议：

1. 在远端提供可返回 session/task id 的提交接口，后续状态、取消和清理都按 id 操作。
2. 在完成该协议前，强制同一个 `webBaseUrl` 的并发为 1，并在配置加载时拒绝更大的值。
3. 增加真实 AgentBridge E2E：两个并发任务写不同哨兵文件，验证会话、状态、修正和输出不会串线。

验收：每个任务拥有远端可观察 id；并发清理不会影响其他任务；全局 `/info.running` 不再作为唯一完成依据。

### RUN-02 [P1] 一次定时任务异常会终止整个周期调度

证据：

- `WorkflowScheduleService.java:50` 使用 `scheduleWithFixedDelay(() -> triggerDueSchedules(...))`。
- `WorkflowScheduleService.java:75-94` 在任一 schedule 触发异常时，把异常包装成 `IllegalStateException` 继续抛出。
- Java 的周期调度任务在一次执行抛出异常后会抑制后续执行。
- 当前测试 `WorkflowScheduleServiceTest` 只覆盖成功、禁用和字段校验，没有覆盖 submitter 抛异常后下一轮仍可运行。

影响：某个路径错误、SQLite 临时失败或提交异常，就可能让所有定时任务静默停止，直到进程重启。

建议：

- 顶层 scheduler runnable 必须捕获所有异常并记录，不能让异常逃出。
- 每个 schedule 单独 try/catch；单个失败不应阻断同批其他 schedule。
- 为失败任务记录 `last_error` / `failure_count` / `next_retry_at`，并设置退避与熔断策略。

验收：构造一个失败 schedule 和一个成功 schedule；失败不会阻止成功任务，也不会阻止 30 秒后的下一轮扫描。

### DATA-01 [P1] 最终贡献报告的校验不验证业务事实

证据：

- `FinalReportValidator.java:28-63` 只检查文件存在、非空、无占位符、固定标题、排名字段名称和相对链接。
- `FinalReportValidator.java` 不接收 `synthesis-inputs.json` 或 `quality-scores.json`，因此无法核对任何值。
- `FinalReportValidatorTest.java:53-84` 的“有效报告”用“内容”和硬编码 Alice 排名即可通过。
- 当前未提交修改加强了 prompt 对 `quality_ranking.quality_adjustment_percent` 的说明，但仍属于语言约束，不是程序校验。

影响：Agent 可以输出错误作者、错排名、错质量调整或错工作量分，只要结构完整，Java 就会写 `state=completed`。

建议：

- Java 直接渲染所有事实表格和排名字段；Agent 只生成分析段落。
- 或把报告解析成结构化模型，逐作者核对 `author_key`、`final_rank`、`base_rank`、`quality_adjustment_percent`、`workload_score` 和报告链接。
- 增加反例测试：交换两个人排名、把质量调整改成 0、删除一个作者行，都必须校验失败。

验收：结构完整但任一业务值与 `quality-scores.json` 不一致时，最终报告不能进入 completed。

### DATA-02 [P1] 超大提交的“去注释”指标和审查覆盖失真

证据：

- `GitStatsCollector.java:26-29` 把阈值固定为系统属性默认 50,000 行。
- `GitStatsCollector.java:105-119` 超阈值时跳过 `parseNonCommentDiff` 和 `parseChangedRegions`。
- `GitStatsCollector.java:224-230` 的估算函数直接把 raw `added/deleted` 复制为 `non_comment_added/deleted`。
- 日志会提示 skipped phases，但 `summary.json`/author 数据没有 `estimated`、`skipped_commit` 或 coverage warning 字段。
- `GitStatsCollectorTest.java:50-56` 明确把 60,001 行提交作为该行为的固定测试输入。

影响：

- 报告把未经注释过滤的原始行数展示成“去注释行数”，基础工作量分和排序可能被放大。
- `changed_regions` 为空会让个人质量分析和 weekly review batches 缺失；最终报告仍可能看起来完整。

建议：

1. 产物明确记录每个提交的 `measurement_mode=exact|estimated|skipped` 和原因。
2. 估算值不要复用 `non_comment_*` 字段；可以保留 `raw_*`，把精确指标置空并在评分时降权/排除。
3. 对超大提交采用流式解析、分文件解析或抽样 changed regions，而不是全部丢弃。
4. `data-quality.md` 和最终贡献报告必须展示覆盖缺口。

验收：超大提交不会以精确去注释值出现；缺失的审查范围在结构化数据和 Markdown 中都可见。

### OPS-01 [P1] Git 子进程可能无限挂起并吃满内存

证据：

- `CommandExecutor.java:23-28` 启动进程后对 stdout 执行 `readAllBytes()`，再无限期 `waitFor()`。
- 没有命令超时、输出字节上限、取消 token 或 `destroyForcibly()`。
- 慢命令日志只在命令结束后写出（`CommandExecutor.java:37-49`），无法对挂死提供心跳。

影响：损坏仓库、网络文件系统、credential helper、超大 diff 或异常 Git 行为可让单线程工作流永久卡住；超大输出还可能导致堆内存耗尽。

建议：

- 每类 Git 命令设置可配置超时，超时后先 `destroy()`、再 `destroyForcibly()`。
- 流式读取并设置输出上限，超限时保留头尾摘要和临时文件路径。
- 将 workflow 取消/进程关闭传播到子进程。
- 在运行期间输出带 commit/phase 的心跳。

验收：模拟永不退出和持续输出的子进程，应用能在规定时间内失败并释放进程与流。

### TEST-01 [P2] Java/Python 测试顺序会污染并破坏构建

复现：

1. 并行执行 3 套 Python 测试和 `mvn -q test`。
2. Python 在 `src/main/resources/skill/**` 下生成 `__pycache__/*.pyc`。
3. `ArchitectureConventionsTest.java:38-71` 对整个 `src/main/resources/skill` 做 `Files.walk`，不按扩展名过滤，然后 `Files.readString`。
4. Maven 报 `MalformedInputException`。
5. 删除本次生成的 `__pycache__` 后，Maven 测试通过。

附加问题：`.gitignore` 没有 `__pycache__/` 和 `*.pyc`。

建议：

- Python 测试统一使用 `PYTHONDONTWRITEBYTECODE=1`，并在 `.gitignore` 忽略缓存。
- 架构测试只扫描 Git 跟踪的文本文件，或限定 `.java/.md/.yml/.yaml/.json/.py`。
- 在 CI 按“Python -> Maven”和并行顺序都跑一次，保证无顺序依赖。

验收：任何顺序运行 Python/Maven 测试都通过，工作树不产生新文件。

### RUN-03 [P2] 声明的并发与实际线程池容量可能不一致

证据：

- `CoreConfiguration.java:33-44` 在 Spring 启动时用全局 `agentbridge-runner.agentbridge` 创建固定大小线程池。
- weekly、SmartESB 和 console request 在运行时又可使用链路/请求级并发值。
- `ConcurrentWorkflowTaskRunner` 的 semaphore 只能限制并发，不能把底层固定 1 线程的 executor 扩到 3。
- 当前 `weekly-engineering-report.yml:21` 写 `review.concurrency: 3`，但 `application.yml:13-14` 的全局 executor 默认仍为 1。
- 并发测试手工创建自定义 executor，没有覆盖真实 Spring wiring。

影响：用户看到并发 3，实际仍可能串行；若把全局线程池调大，又会触发 RUN-01 的共享会话竞争。

建议：在 AgentBridge 支持 session id 前明确只支持串行并移除误导配置；协议升级后，再采用按 endpoint 分组的受控 executor 或运行时可调整的线程池。

### CFG-01 [P2] 周报配置包含无效或名不副实字段

证据：

- `WeeklyEngineeringReportProperties.Review.maxRegionsPerBatch` 只有 getter/setter，没有生产使用点。
- `Grouping.targetTaskCount` 在 README 和 YAML 中描述为“期望任务数”，但 `WeeklyReviewUnitGrouper.java:31-58` 完全不读取它。
- `Grouping.strategy` 只写入输出 metadata；分组算法无论填什么都始终按 module + author 执行。

影响：运维人员调整配置后行为不变，却难以察觉；报告 metadata 还可能声称使用了一个实际未执行的 strategy。

建议：删除死字段，或实现并测试对应算法；对 strategy 使用 enum 并拒绝未知值；生成 effective-config 快照，显示最终生效值。

### CFG-02 [P2] 缺少统一配置校验

证据：

- `AgentBridgeRunnerProperties`、`AgentBridgeSettings`、`TaskConsoleProperties` 没有 Bean Validation 注解或集中 validator。
- UI 动态 number input 没有 `min`/`max`。
- 部分链路临时 `Math.max`，部分直接 `Duration.ofMinutes(spec.timeoutMinutes())`，非法值的处理不一致。

影响：负 timeout、0 poll interval、超大并发、越界路径等可能在任务已经入库/排队后才失败，形成难诊断的半成品运行记录。

建议：建立统一 `validateAndNormalize`，在写数据库和生成运行目录之前一次性校验 URL、路径、日期、并发、超时、阈值和链路必填字段。

### PERF-01 [P2] 事件发布随事件数平方增长

证据：

- `WorkflowEventSink.java:29-35` 先插入一条事件，再调用 `repository.listEvents(runId)` 读取该运行全部事件，只为取最后一条发布。
- `WorkflowRunRepository.java:97-99` 的查询没有 limit。

影响：一个运行有 N 条事件时，累计读取约 1+2+...+N 条记录。长流程、多 task 和频繁状态更新会造成 SQLite I/O、对象分配和 SSE 延迟持续上升。

建议：插入时返回 generated id，直接构造并 publish 当前事件；历史页面和 API 增加分页/limit。

### DEP-01 [P2] Jackson 2 与 Jackson 3 双栈共存

证据：

- Spring Boot 4 WebMVC 依赖树带入 `tools.jackson.core:jackson-databind:3.1.0`。
- `pom.xml` 又显式加入 `com.fasterxml.jackson.*:2.21.x`。
- 全部业务代码和自定义 `ObjectMapper` bean 使用 Jackson 2 包名。

影响：MVC 序列化与内部文件/YAML 序列化可能由不同 major 版本处理；模块注册、日期格式、未知字段、异常类型和未来升级路径容易分叉，同时增加包体和维护成本。

建议：依据 Spring Boot 4 的官方迁移路径选定一个主栈，列出必须保留的 YAML/JSR310 能力并做契约测试；不要长期让两套 ObjectMapper 隐式并存。

### ASSET-01 [P2] 同名 runtime/skill 资产没有同步契约

证据：

- `git-report-prompt-pack/**` 与 `skill/git-code-contribution-report/**` 都包含同名 prompt/template，但输入字段、marker 模式、读写工具和评分职责显著不同。
- SmartESB runtime schema 支持 transaction 或 module；内置 skill schema 只支持 transaction。
- 当前未提交修复只更新 runtime prompt/template，没有更新同名 skill 资产。

影响：维护者很难判断哪个是权威版本；复制、安装或切换执行入口时可能得到不同产物契约。

建议：先明确两者是“两个产品”还是“同一契约的两种包装”。如果独立，重命名并写兼容矩阵；如果同源，用单一模板生成两套资产并在测试中比较关键 schema/字段。

### IO-01 [P2] 关键产物不是原子写入

证据：

- `AgentBridgeRunMonitor`、`RunStatusRepository`、`QualityScoresWriter`、各 preparation/renderer 直接覆盖目标 JSON/Markdown。
- 全仓没有临时文件 + `ATOMIC_MOVE`，也没有 fsync/校验后替换。

影响：进程退出、磁盘满、AgentBridge 与 Java 同时读写时可能留下截断 JSON；后续 rerun 会把损坏文件误判为业务失败。

建议：对程序拥有的 JSON/状态文件统一使用“同目录临时文件 -> flush -> 原子替换”；对 AgentBridge 写入文件保留 settle，但校验时记录文件大小/mtime 稳定窗口。

### SSE-01 [P3] SSE 订阅有竞态和长期键残留

证据：

- Controller 在调用 `subscribe` 前先执行 `repository.listEvents(id)`；事件可能在查询后、emitter 注册前发生，从而丢失。
- `EventStreamService.java:14-23` 先注册 emitter 再发送旧事件，注册后的新事件可能先于旧事件到达。
- `EventStreamService.java:36-40` 只删除 list 中的 emitter，不在 list 为空时删除 runId key。
- `new SseEmitter(0L)` 永不超时，也没有 heartbeat。

建议：支持 `Last-Event-ID`，用数据库 id 增量补发；订阅后按 id 去重；空 list 删除 map key；配置合理超时和 heartbeat。

### BUILD-01 [P3] 构建可复现与质量门禁不足

证据：

- 没有 `mvnw`、`.mvn/wrapper`、GitHub Actions/其他 CI 配置。
- POM 没有 JaCoCo、Checkstyle/PMD/SpotBugs、依赖漏洞扫描或秘密扫描门禁。
- 目标 Java 21、当前 Maven JDK 25、IDE JDK 26，机器又未安装 JDK 21。

建议：加入 Maven Wrapper；CI 至少固定 JDK 21 跑 Java/Python 测试、`git diff --check`、秘密扫描和依赖扫描；覆盖率先做可见报告，再逐步设门槛。

### UI-01 [P3] 控制台可访问性与反馈不足

按 2026-07-10 获取的 [Vercel Web Interface Guidelines](https://github.com/vercel-labs/web-interface-guidelines/blob/main/command.md) 复核，主要问题：

- `styles.css` 没有 `:focus-visible`，键盘用户看不到清晰焦点。
- 所有页面缺少 skip link。
- `run-new.html:67`、`schedules.html:115` 的异步消息容器没有 `aria-live="polite"`。
- `app.js:305-380` 提交期间不禁用按钮、无 loading 状态、无 fetch/JSON 异常兜底，重复点击可重复提交。
- `dashboard.html`、`history.html`、`run-detail.html`、`schedules.html` 直接输出 ISO 时间，没有本地化格式化。
- 动态 number input 没有 min/max/inputmode；非认证字段没有明确 autocomplete 策略。

建议：补 focus-visible、skip link、aria-live、提交中禁用与错误恢复；时间用统一 formatter；从 CFG-02 的配置 schema 生成前端约束，避免 UI 与后端分叉。

### MAINT-01 [P3] 核心类职责过度聚合

高风险大文件：

- `ProjectUnitTestGenerationBatchRunner.java`：542 行，混合 session、MCP、Maven/JaCoCo、快照保护、hash 和结果持久化。
- `ProjectUnitTestGenerationPreparation.java`：517 行，混合模块发现、源码解析、路径匹配、文档和 batch 生成。
- `WeeklyReportRenderer.java`：469 行，混合聚合、质量评分、多个报告渲染和 traceability。
- `SmartEsbWorkflowChain.java`：427 行。
- `GitReportOrchestratorIntegrationTest.java`：952 行，包含多种 fake server 和场景。

建议按“协议客户端 / 状态机 / 验证器 / 持久化 / renderer”拆分，优先拆安全和正确性边界，不以单纯行数为目标。

### DEAD-01 [P3] 存在失效基础设施和未完成字段

- `AgentBridgeTaskRunner.java:18,33` 保存 `ScheduledProbeWaiter`，但之后从未调用；真正等待使用 `AgentBridgeClient.waitUntilIdle` 的 `Thread.sleep`。对应 4 线程 scheduler bean 因此没有生产价值。
- `workflow_runs.output_path` 在 schema 和 record 中存在，但全仓没有更新方法；控制台也不展示最终产物入口。

建议：删除死 waiter/scheduler，或真正把轮询迁移到它；实现每条 chain 的 output locator 并持久化 `output_path`，否则删除字段避免误导。

## 5. 其他观察与优化点

1. `AgentBridgeTaskRunner.waitForValidationRound` 把任意等待异常都记录成 timeout；HTTP 500、TLS、JSON 解析、线程中断应分别分类，线程中断必须恢复 interrupt 标志。
2. `AgentBridgeClient.requireSuccess` 把完整 HTTP body 拼进异常；如果服务端错误体包含 prompt 或工具输出，日志可能泄漏敏感内容，应限长并脱敏。
3. `WorkflowRunRepository.listRuns()`、事件列表和 schedule 列表都无分页/保留策略，长期运行会让控制台和 SQLite 无限增长。
4. SQLite 数据源没有显式 busy timeout/WAL 配置；未来真正启用多线程事件写入后，应做锁竞争压测再决定连接/事务策略。
5. `FinalReportValidator` 的 Markdown 链接正则不能可靠处理带括号或转义的 URL；如果继续扩展，建议使用 Markdown parser 或让 Java 直接生成链接表。
6. `WeeklyCodeReviewOutputValidator` 的敏感词检测会把普通变量名 `token`/`password` 也视为秘密；应结合赋值内容/熵/已知格式，避免过度脱敏导致代码证据不可读。
7. 仓库跟踪部分 `.idea` 文件，其中项目 SDK 已与 `pom.xml` 不一致；除团队确需共享的格式/编码配置外，建议减少 IDE 状态入库。

## 6. 建议整改路线

### 0-24 小时：先控制安全面

1. 轮换并移除 VAPID 私钥，评估 Git 历史清理。
2. 默认绑定 `127.0.0.1`；未完成认证前禁止远程暴露控制台。
3. 将 AgentBridge TLS 绕过限制到显式 loopback 开发模式。
4. 强制 AgentBridge endpoint 并发为 1。

### 1-3 天：修复可用性与数据可信度

1. 定时器逐任务隔离异常，保证周期任务不死亡。
2. 让 Java 生成/校验最终排名事实表。
3. 为超大提交增加 measurement mode、coverage gap 和降权策略。
4. 为 Git 子进程增加超时、输出上限和取消。
5. 修复 Python cache 与架构扫描的测试顺序问题。

### 1-2 周：收敛契约和工程化

1. 统一并发配置模型，等 AgentBridge 支持 task/session id 后再开放并发。
2. 删除或实现周报死配置，增加统一配置校验。
3. 对齐 Jackson major 版本。
4. 建立 runtime prompt-pack 与 skill 资产的版本/生成/兼容策略。
5. 加 Maven Wrapper 和固定 JDK 21 的 CI，串起 Java/Python/秘密/依赖检查。
6. 对关键 JSON 使用原子写，事件和历史查询增加分页/保留策略。

### 后续：降低维护成本

1. 拆分 unit-test batch runner、preparation 和 weekly renderer。
2. 完成 SSE 增量补发与 output path 展示。
3. 修复控制台键盘焦点、异步反馈和本地化时间。

## 7. 推荐新增的回归测试

| 测试 | 关键断言 |
| --- | --- |
| `AgentBridgeConcurrentSessionIsolationTest` | 两个并发任务不会互相清 session，完成状态按远端 task id 关联 |
| `WorkflowScheduleServiceSurvivalTest` | 第一个 schedule 抛异常后，第二个任务和下一轮扫描仍执行 |
| `FinalReportSemanticValidatorTest` | 错作者、错排名、错质量调整、缺作者行全部失败 |
| `LargeCommitDataQualityTest` | 超阈值提交标为 estimated/skipped，不伪装成精确去注释值 |
| `CommandExecutorTimeoutTest` | 永不退出和无限输出进程被终止，输出受限 |
| `ArchitectureConventionsTextFileFilterTest` | `pyc`、SQLite、图片等二进制文件不会被 `readString` |
| `SpringConcurrencyWiringTest` | 页面/链路显示的 effective concurrency 与 executor 实际容量一致 |
| `ConsoleSecurityTest` | 未认证写请求、CSRF 缺失、越界路径和外部 URL 被拒绝 |
| `EventStreamReplayRaceTest` | 历史查询与 emitter 注册之间的事件不会丢失/乱序 |
| `AtomicStatusWriteTest` | 中断写入不会覆盖最后一个合法状态文件 |

## 8. 完成定义

以下条件满足后，可认为本轮高风险问题关闭：

- 远端和历史中不再使用已泄露 VAPID 私钥，且完成轮换。
- 控制台默认仅本机可达；远程场景有认证、授权、CSRF 与路径/URL allowlist。
- AgentBridge TLS 有明确安全策略，并发任务拥有远端 session/task id；此前强制串行。
- 定时调度在单任务失败后持续存活。
- 最终报告的事实值由 Java 生成或逐字段核验。
- 大提交的估算/缺失范围在结构化产物和最终报告中可见。
- Git 命令有超时和输出上限。
- Java/Python 测试任意顺序均通过且不污染工作树；固定 JDK 21 的 CI 持续通过。
