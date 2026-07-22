# Task 2 — AgentBridge SQL audit guardrails

STATUS: COMPLETE

## 改动

- `AgentBridgeClient` 以兼容的新增 API 支持 MCP `tools/list` 和 Web Access
  `GET /tool-calls`，返回 typed tool definitions/tool-call records，并继续复用现有
  MCP initialize、`Mcp-Session-Id` 和协议版本会话。
- `MyBatisDatabasePreflight` 将配置连接名精确解析为一个 connection id，检查九个
  JetBrains database tools、连接可用性、database/schema、centralized GaussDB，以及
  Web tool-call history。运行环境仅接受 read replica/test；non-owner、non-admin、
  read-only 凭证被明确建模为外部部署契约，代码不伪造权限证明。
- `MyBatisToolCallAudit` 在任务 timestamp/pre-existing-id 边界后做 post-hoc 审计，
  强制工具 allowlist、单一数据库绑定、最多三个查询场景和每次最多二十行。
  SQL lexer 区分字符串、双引号标识符、行注释和块注释；拒绝多语句、DML/DDL、
  DML CTE、COPY、CALL、SELECT INTO、FOR UPDATE/SHARE、sequence mutation、
  side-effect functions、缺失/表达式化/超过 20 的顶层 LIMIT。
- `MyBatisSqlPromptBuilder` 和完整 prompt/template/schema 明确三文件输出、原始
  DML/selectKey 禁令、只读凭证前提、查询/证据上限，以及 `/tool-calls` 只能事后
  发现违规、不能阻止已执行 SQL。
- `MyBatisSqlOutputValidator` 要求候选目录恰好包含 `report.md`、`summary.json`、
  `database-evidence.json`；校验 summary JSON schema、七个报告章节、审计声明、
  evidence/tool-call 引用、场景和行数、262144-byte 上限及残留占位符。
- 测试使用本地 fake HTTP/MCP server 和实际 JSON/Markdown fixtures；没有连接真实
  AgentBridge 或数据库，也没有执行 SQL。

## RED / GREEN

### AgentBridge client

RED:

```sh
mvn -q -Dtest=AgentBridgeClientTest test
```

测试编译按预期失败：`ToolDefinition`、`ToolCallRecord`、`listTools` 和
`getToolCalls` 尚不存在。

GREEN:

```sh
mvn -q -Dtest=AgentBridgeClientTest test
```

退出码 0；证明 tools/list 经过 MCP 会话协商，并解析完整 Web tool-call 字段。

### Database preflight

RED:

```sh
mvn -q -Dtest=MyBatisDatabasePreflightTest test
```

测试编译按预期失败：`MyBatisDatabasePreflight` 尚不存在。

GREEN:

```sh
mvn -q -Dtest=MyBatisDatabasePreflightTest test
```

退出码 0；覆盖成功预检、连接缺失/歧义、错误 schema、连接不可用、缺少工具、
非 centralized GaussDB、production primary 和未确认只读凭证契约。

### Tool-call audit

RED:

```sh
mvn -q -Dtest=MyBatisToolCallAuditTest test
```

首次测试编译按预期失败：`MyBatisToolCallAudit` 尚不存在。后续新增的顶层 LIMIT
测试按预期失败为 “Expecting code to raise a throwable”；副作用函数和 LIMIT 表达式
加固又产生三个预期失败。

GREEN:

```sh
mvn -q -Dtest=MyBatisToolCallAuditTest test
```

退出码 0，19 tests。覆盖 DML/selectKey sequence mutation、多语句、DML CTE、
FOR UPDATE/SHARE、SELECT INTO、COPY、CALL、DDL、副作用函数、字符串/注释内伪
关键字、缺失/非顶层/超过 20/表达式 LIMIT、超过三场景、超过二十行、错误数据库
绑定、stale call 和 unapproved tool。

### Candidate output and prompt pack

RED:

```sh
mvn -q -Dtest=MyBatisSqlOutputValidatorTest test
```

测试编译按预期失败：`MyBatisSqlOutputValidator` 和 `MyBatisSqlPromptBuilder` 尚不存在。

GREEN:

```sh
mvn -q -Dtest=MyBatisSqlOutputValidatorTest test
```

退出码 0，5 tests。覆盖 schema、报告章节、证据结构/引用、三场景、二十行、
262144 bytes、stale evidence、额外文件、占位符，以及 prompt/template/schema 合同。

Focused 聚合命令：

```sh
mvn -q -Dtest=AgentBridgeClientTest,MyBatisDatabasePreflightTest,MyBatisToolCallAuditTest,MyBatisSqlOutputValidatorTest test
```

结果：退出码 0。

## 全量测试

```sh
mvn -q test
```

结果：退出码 0；Surefire XML 汇总为 288 tests、0 failures、0 errors、0 skipped。
现有测试会输出 Mockito dynamic-agent 警告，以及 visual-qa guard 的预期异常日志；
它们没有导致测试失败。

```sh
git diff --check
```

结果：退出码 0。

## Commit

- Message: `feat: add AgentBridge SQL audit guardrails`
- 本报告包含在同一 Task 2 commit 中；最终 hash 在提交后以 `git rev-parse HEAD`
  获取并回报，避免报告内嵌自引用 commit hash。

## 自审

- 现有 `AgentBridgeClient` public API 未删除或改签；新增 client API 复用同一会话缓存。
- Task 1 inventory、Task 1 report、计划文件、Task 3/4 文件和原 checkout 均未修改；
  `git-report-output/` 未纳入暂存或提交。
- Java 预检只能验证工具响应与声明的运行契约，不能证明数据库用户实际权限。
- SQL audit 在调用之后运行；实现和 prompt 均未声称可以 preempt 或阻止坏 SQL。
- SQL lexer 对可疑语法采取 fail-closed 策略，并由字符串/注释、顶层 LIMIT 和多语句
  回归测试约束。
- 候选 validator 在发布前拒绝不完整 schema、错误证据边界和额外输出文件。

## Concerns

- 上线前仍必须用真实 centralized GaussDB read replica/test 和隔离的 non-owner、
  non-admin、read-only 账号完成 live preflight；fake MCP 测试不能证明凭证权限。
- `/tool-calls` 审计是 post-hoc defense-in-depth。若 AgentBridge 已执行违规 SQL，Java
  只能让工作流失败并拒绝候选产物，无法撤销副作用；只读账号是硬安全边界。
- 当前 typed Web record 按已确认合同解析 ISO-8601 `timestamp`。若插件实际返回其他
  时间格式或不同顶层包装，需要先更新 fixture/client contract 再上线。
- SQL guard 是严格的 GaussDB read-only lexical policy，不是通用 SQL parser；未知或
  复杂 LIMIT/函数语法会被拒绝，需要显式测试后才能放宽。
