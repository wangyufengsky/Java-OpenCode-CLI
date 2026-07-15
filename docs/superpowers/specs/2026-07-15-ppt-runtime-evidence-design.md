# PPT 真实运行证据增补设计

## 目标

在现有 `outputs/git-unit-test-operation-manual.pptx` 基础上，补充一次真实的
`git-code-contribution-report` 与 `project-unit-test-generation` 链路运行，展示运行配置、
程序生成的 prompt、Agent task、loop 反馈和最终产物实例，使操作手册不仅解释契约，也能提供可复核的执行证据。

## 执行边界

### Git 链路

- 分析对象：当前 `Java-OpenCode-CLI` 仓库。
- 时间窗口：执行时向前 7 天，使用当前链路字段 `git.since` 与 `git.until`。
- 写入位置：`outputs/ppt-runtime-samples/git/`。
- 源码边界：Git 链路只读取仓库历史，不修改业务源码。

### 单元测试链路

- 分析对象：从当前提交创建的专用分支 `codex/ppt-unit-test-runtime-sample`。
- 工作区：通过 Git worktree 检出该分支，不做手工目录复制，也不切换当前脏工作树的分支。
- 执行范围：选择只包含一个适合演示的顶层类型的最小 package path。
- 覆盖率：保持当前默认 `test.require-coverage=false`，使用 IDEA MCP 验收。
- 写入边界：Agent 只能修改专用分支 worktree 中的测试文件和链路产物；当前工作树中的源码、测试和 IDE 状态不得被链路修改。
- 产物归档：运行结束后，将 prompt、task 结果、生成测试和报告复制到
  `outputs/ppt-runtime-samples/unit-test/`。

## 运行前检查

1. 检查 AgentBridge `/info`、session clear 和 task 状态接口。
2. 检查 IDEA MCP 握手与 `list_tests`，确认当前插件支持 `Mcp-Session-Id`。
3. 记录当前 Git 工作树状态，并确认专用分支 worktree 从干净提交创建，避免把既有未提交改动误认为链路产物。
4. 检查输出目录为空，防止链路的非空目录保护阻止运行。

## PPT 增补内容

在公共说明之后保留 Git → 单测的主顺序，并分别在两条链路中加入真实运行证据页：

- 实际运行配置和运行状态。
- 程序生成的完整 prompt 文件路径与可读节选。
- Agent task 标题、状态、执行轮次和 validator/feedback 信息。
- Git 作者报告、质量摘要、质量评分和最终综合报告实例。
- 单测 batch 输入、各 attempt 的 failureSummary、生成测试代码与最终报告实例。
- 产物目录树，区分程序产物、Agent 产物和验收产物。

实例中保留当前仓库的真实类型名和真实文件名，但不展示凭据、会话令牌、私人主目录或无关个人信息。

## 失败处理

- AgentBridge 或 IDEA MCP 不可用时，先诊断并修复运行条件；不伪造成功产物。
- Git 链路失败时，保留失败阶段、prompt 和已产生文件作为证据，修复后重新创建干净输出目录运行。
- 单测链路失败时，保留每轮真实 `failureSummary`；若属于受保护文件变更则立即停止，不扩大写入权限。
- 只有两条链路的产物与 PPT 渲染检查通过后，才覆盖最终 PPT。

## 验收标准

- 两条链路至少各有一次真实运行记录。
- PPT 能看到实际 prompt、task/Agent 执行、loop 反馈和最终产物实例。
- Git 示例路径与当前实际目录结构一致。
- 单测示例与当前串行单类型 batch、默认最多 5 次 attempt、默认 IDEA MCP 验收契约一致。
- 当前工作树的既有源码和测试改动保持不变。
- 最终 PPT 可打开、页数符合设计、逐页渲染无溢出或遮挡。
