# PPT Runtime Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 真实运行 `git-code-contribution-report` 与 `project-unit-test-generation`，把运行配置、完整 prompt、Agent task/loop 和最终产物实例补入现有操作手册 PPT。

**Architecture:** 当前仓库启动一个独立端口的任务控制台并顺序提交两条链路。Git 链路直接分析当前仓库；单测链路在 `codex/ppt-unit-test-runtime-sample` 分支的 Git worktree 中写测试，并在独立 IDEA 窗口完成 MCP 验收。运行证据统一归档到 `outputs/ppt-runtime-samples/`，PPT 源码继续使用现有外部 scratch workspace 中的 artifact-tool 构建脚本。

**Tech Stack:** Java 21、Spring Boot、Maven、AgentBridge Web Access、AgentBridge MCP、IntelliJ IDEA、Git worktree、curl、jq、JavaScript ES modules、`@oai/artifact-tool`、PowerPoint。

## Global Constraints

- Git 运行窗口固定为 `2026-07-08` 至 `2026-07-15`，字段使用 `git.since` 与 `git.until`。
- Git 真实产物写入 `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/git/`。
- 单测分支固定为 `codex/ppt-unit-test-runtime-sample`，worktree 固定为 `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/.worktrees/ppt-unit-test-runtime-sample`。
- 单测目标固定为 `src/main/java/com/sonnet/wyf/gitreport/prompt` 下的 `PromptBuilder`，每个 batch 只包含一个顶层类型。
- 单测保持 `test.require-coverage=false`，验收必须使用 IDEA MCP。
- 当前工作树中既有的 `ProjectUnitTestGenerationBatchRunner.java`、`ProjectUnitTestGenerationWorkflowChainTest.java` 和中断验证文档改动不得被覆盖、提交或移入运行分支。
- 不展示凭据、MCP session id、私人主目录截图或 AgentBridge 会话数据库。
- 最终 PPT 路径保持 `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx`。

---

### Task 1: 运行前探针与隔离分支

**Files:**
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/preflight/working-tree-before.txt`
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/preflight/agentbridge-info.json`
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/preflight/mcp-initialize.json`
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/.worktrees/ppt-unit-test-runtime-sample/`

**Interfaces:**
- Consumes: current repository HEAD, AgentBridge Web `https://127.0.0.1:9642`, MCP `http://127.0.0.1:8642/mcp`.
- Produces: a clean unit-test target branch/worktree and health evidence used by Tasks 2–3.

- [ ] **Step 1: Record the current worktree without changing it**

```bash
mkdir -p outputs/ppt-runtime-samples/preflight
git status --short > outputs/ppt-runtime-samples/preflight/working-tree-before.txt
git rev-parse HEAD >> outputs/ppt-runtime-samples/preflight/working-tree-before.txt
```

Expected: the file records the two existing modified Java/test files, two existing untracked interruption-validation documents, and `outputs/`.

- [ ] **Step 2: Probe AgentBridge Web Access**

```bash
curl -ksS https://127.0.0.1:9642/info \
  > outputs/ppt-runtime-samples/preflight/agentbridge-info.json
jq -e 'has("running")' outputs/ppt-runtime-samples/preflight/agentbridge-info.json
```

Expected: `jq` exits `0`; the saved JSON is redacted before PPT use and its session-specific fields are never displayed.

- [ ] **Step 3: Probe the current MCP handshake**

```bash
curl -sS -D /tmp/ppt-mcp-headers.txt \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"ppt-runtime-probe","version":"1.0"}}}' \
  http://127.0.0.1:8642/mcp \
  > outputs/ppt-runtime-samples/preflight/mcp-initialize.json
rg -i '^Mcp-Session-Id:' /tmp/ppt-mcp-headers.txt
jq -e '.result.protocolVersion' outputs/ppt-runtime-samples/preflight/mcp-initialize.json
```

Expected: response headers contain `Mcp-Session-Id`; JSON contains a negotiated protocol version. The session header value is not copied into the PPT.

- [ ] **Step 4: Create the unit-test branch in a Git worktree**

```bash
git worktree add -b codex/ppt-unit-test-runtime-sample \
  .worktrees/ppt-unit-test-runtime-sample HEAD
git -C .worktrees/ppt-unit-test-runtime-sample status --short
```

Expected: the second command prints nothing; the current worktree remains on its original branch with its original uncommitted changes.

- [ ] **Step 5: Verify the target production type exists in the branch worktree**

```bash
test -f .worktrees/ppt-unit-test-runtime-sample/src/main/java/com/sonnet/wyf/gitreport/prompt/PromptBuilder.java
test ! -f .worktrees/ppt-unit-test-runtime-sample/src/test/java/com/sonnet/wyf/gitreport/prompt/PromptBuilderTest.java
```

Expected: both commands exit `0`, ensuring the chain must create a real test instead of skipping on precheck.

---

### Task 2: 真实运行 Git 贡献报告链路

**Files:**
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/requests/git-run.json`
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/git/**`
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/git-run-snapshot.json`
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/console.sqlite`

**Interfaces:**
- Consumes: healthy AgentBridge from Task 1 and the current Git repository.
- Produces: real author prompts, author task states, author reports, quality evidence, synthesis prompt, and final report for Task 4.

- [ ] **Step 1: Create the exact Git run request**

Create `outputs/ppt-runtime-samples/requests/git-run.json` with:

```json
{
  "chainId": "git-code-contribution-report",
  "mode": "full",
  "runDate": "2026-07-15",
  "config": {
    "project": {"id": "java-opencode-cli", "name": "Java OpenCode CLI"},
    "paths": {
      "repo": "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI",
      "out": "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/git"
    },
    "git": {
      "since": "2026-07-08",
      "until": "2026-07-15",
      "revision": "HEAD",
      "include-merges": false,
      "include": [],
      "exclude": ["target/**", "*.lock", "outputs/**"]
    },
    "agentbridge": {
      "task-message": "严格执行附件 worker-prompt.md 中的任务，完成后回复简短完成信息即可，Java 会校验输出。",
      "synthesis-task-message": "严格执行附件 synthesis-prompt.md 中的任务，生成最终中文总报告。"
    },
    "detail-input": {"top-files": 10, "commits": 20, "changed-regions": 40, "changed-region-lines": 24},
    "synthesis-input": {"person-report-excerpt-chars": 8192, "snippets-per-author": 5, "snippets-total": 30, "snippet-lines": 20}
  }
}
```

- [ ] **Step 2: Start a dedicated console process**

```bash
mvn -q spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=18081 --agentbridge-runner.enabled=false --task-console.scheduler-enabled=false --task-console.database-path=/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/console.sqlite --task-console.run-config-dir=/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/run-configs"
```

Expected: `GET http://127.0.0.1:18081/api/chains` returns the chain catalog. Keep this process in a PTY session for Tasks 2–3.

- [ ] **Step 3: Submit the Git run and record its id**

```bash
curl -sS -H 'Content-Type: application/json' \
  --data @outputs/ppt-runtime-samples/requests/git-run.json \
  http://127.0.0.1:18081/api/runs \
  | tee outputs/ppt-runtime-samples/git-run-submit.json
```

Expected: JSON contains a numeric `id`.

- [ ] **Step 4: Monitor until a terminal state**

```bash
GIT_RUN_ID=$(jq -r '.id' outputs/ppt-runtime-samples/git-run-submit.json)
curl -sS "http://127.0.0.1:18081/api/runs/${GIT_RUN_ID}/snapshot" \
  > outputs/ppt-runtime-samples/git-run-snapshot.json
jq -r '.run.state' outputs/ppt-runtime-samples/git-run-snapshot.json
```

Repeat the snapshot call through non-blocking PTY polling until the state is `SUCCEEDED` or `FAILED`; save every materially changed snapshot under `outputs/ppt-runtime-samples/git/snapshots/`.

- [ ] **Step 5: Verify real Git prompts and final outputs**

```bash
test -s outputs/ppt-runtime-samples/git/code-contribution-report.md
test -s outputs/ppt-runtime-samples/git/quality-scores.json
find outputs/ppt-runtime-samples/git/reports -name person-report.md -size +0c
find outputs/ppt-runtime-samples/git/reports -name quality-summary.json -size +0c
find outputs/ppt-runtime-samples/git/runs -name worker-prompt.md -size +0c
test -s outputs/ppt-runtime-samples/git/runs/synthesis/synthesis-prompt.md
```

Expected: every command exits `0`; author and synthesis prompts are real files generated by the chain.

---

### Task 3: 在专用分支真实运行单元测试生成链路

**Files:**
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/requests/unit-test-run.json`
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/unit-test/**`
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/.worktrees/ppt-unit-test-runtime-sample/src/test/java/com/sonnet/wyf/gitreport/prompt/PromptBuilderTest.java`

**Interfaces:**
- Consumes: unit-test worktree from Task 1, the existing console process, and IDEA MCP.
- Produces: a real batch prompt, attempt result, generated test source, IDEA test output, and unit-test generation report for Task 4.

- [ ] **Step 1: Open the worktree as an IDEA project**

```bash
open -na "/Applications/IntelliJ IDEA.app" --args \
  "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/.worktrees/ppt-unit-test-runtime-sample"
```

Expected: a separate IDEA window indexes the worktree. Do not close or change the user's original IDEA window.

- [ ] **Step 2: Verify MCP now sees the target project**

Perform the MCP initialize sequence from Task 1, send `notifications/initialized`, then call `list_tests` for `PromptBuilderTest.java` with the returned session id and protocol version.

Expected pre-run result: the call succeeds at the protocol layer and returns no recognized test class. If it still targets the original project, stop before submission and refocus the worktree IDEA window.

- [ ] **Step 3: Create the exact unit-test run request**

Create `outputs/ppt-runtime-samples/requests/unit-test-run.json` with:

```json
{
  "chainId": "project-unit-test-generation",
  "mode": "full",
  "runDate": "2026-07-15",
  "config": {
    "project": {
      "id": "java-opencode-cli-runtime-sample",
      "name": "Java OpenCode CLI Runtime Sample",
      "repo": "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/.worktrees/ppt-unit-test-runtime-sample"
    },
    "paths": {
      "out": "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/unit-test"
    },
    "docs": {"agents": "AGENTS.md", "project-map": "project-map.md", "reconstructed-design": "重构项目详细设计文档.md"},
    "source": {
      "package-paths": ["src/main/java/com/sonnet/wyf/gitreport/prompt"],
      "include": [],
      "exclude": ["target/**", "build/**", "generated/**", "**/target/**", "**/build/**", "**/generated/**"]
    },
    "test": {
      "require-coverage": false,
      "coverage-threshold-percent": 90,
      "jacoco-version": "0.8.15",
      "jacoco-jvm-arg-property": "sqlite.native.access.argument",
      "jacoco-jvm-arg-base": "--enable-native-access=ALL-UNNAMED"
    },
    "agentbridge": {
      "web-base-url": "https://127.0.0.1:9642",
      "mcp-url": "http://127.0.0.1:8642/mcp",
      "timeout-minutes": 40,
      "max-attempts": 5
    }
  }
}
```

- [ ] **Step 4: Submit and monitor the unit-test run**

```bash
curl -sS -H 'Content-Type: application/json' \
  --data @outputs/ppt-runtime-samples/requests/unit-test-run.json \
  http://127.0.0.1:18081/api/runs \
  | tee outputs/ppt-runtime-samples/unit-test-run-submit.json
UNIT_RUN_ID=$(jq -r '.id' outputs/ppt-runtime-samples/unit-test-run-submit.json)
curl -sS "http://127.0.0.1:18081/api/runs/${UNIT_RUN_ID}/snapshot" \
  > outputs/ppt-runtime-samples/unit-test-run-snapshot.json
```

Repeat the snapshot call through non-blocking PTY polling until `SUCCEEDED` or `FAILED`; retain snapshots showing attempt transitions or failure feedback.

- [ ] **Step 5: Verify the generated test and chain outputs**

```bash
test -s .worktrees/ppt-unit-test-runtime-sample/src/test/java/com/sonnet/wyf/gitreport/prompt/PromptBuilderTest.java
test -s outputs/ppt-runtime-samples/unit-test/unit-test-plan.json
test -s outputs/ppt-runtime-samples/unit-test/test-batches.json
test -s outputs/ppt-runtime-samples/unit-test/agentbridge-results.json
test -s outputs/ppt-runtime-samples/unit-test/unit-test-generation-report.md
find outputs/ppt-runtime-samples/unit-test -name '*.md' -o -name '*.json'
```

Expected: the branch worktree contains the new test and all four chain artifacts exist.

- [ ] **Step 6: Verify branch-only mutation**

```bash
git -C .worktrees/ppt-unit-test-runtime-sample status --short
git status --short
```

Expected: the generated test and permitted local IDEA state appear only in the worktree; the original worktree still contains exactly its pre-existing changes plus `outputs/` and the PPT planning documents.

---

### Task 4: 建立可展示的真实证据包

**Files:**
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/evidence-manifest.json`
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples/evidence-notes.txt`
- Create: `/var/folders/dd/rj49_gmj4db8m9vpzws8fkf00000gn/T/codex-presentations/019f6380-11cb-74e1-ba19-110473d01bdd/git-unit-test-operation-manual/tmp/assets/git-live-run.png`
- Create: `/var/folders/dd/rj49_gmj4db8m9vpzws8fkf00000gn/T/codex-presentations/019f6380-11cb-74e1-ba19-110473d01bdd/git-unit-test-operation-manual/tmp/assets/unit-live-run.png`

**Interfaces:**
- Consumes: terminal snapshots and artifacts from Tasks 2–3.
- Produces: redacted, traceable evidence inputs for the PPT builder.

- [ ] **Step 1: Generate a manifest from actual files**

Resolve the actual author and batch prompt paths from the produced directories, then write the manifest:

```bash
ROOT=/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/ppt-runtime-samples
AUTHOR_PROMPT=$(find "$ROOT/git/runs" -path '*/worker-prompt.md' -type f | sort | head -1)
AUTHOR_KEY=$(basename "$(dirname "$AUTHOR_PROMPT")")
AUTHOR_REPORT="$ROOT/git/reports/$AUTHOR_KEY/person-report.md"
QUALITY_SUMMARY="$ROOT/git/reports/$AUTHOR_KEY/quality-summary.json"
BATCH_PROMPT=$(find "$ROOT/unit-test" -iname '*prompt*.md' -type f | sort | head -1)
GENERATED_TEST=/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/.worktrees/ppt-unit-test-runtime-sample/src/test/java/com/sonnet/wyf/gitreport/prompt/PromptBuilderTest.java

jq -n \
  --arg gitRequest "$ROOT/requests/git-run.json" \
  --arg authorPrompt "$AUTHOR_PROMPT" \
  --arg authorReport "$AUTHOR_REPORT" \
  --arg qualitySummary "$QUALITY_SUMMARY" \
  --arg synthesisPrompt "$ROOT/git/runs/synthesis/synthesis-prompt.md" \
  --arg finalReport "$ROOT/git/code-contribution-report.md" \
  --arg unitRequest "$ROOT/requests/unit-test-run.json" \
  --arg batchPrompt "$BATCH_PROMPT" \
  --arg attempts "$ROOT/unit-test/agentbridge-results.json" \
  --arg generatedTest "$GENERATED_TEST" \
  --arg unitReport "$ROOT/unit-test/unit-test-generation-report.md" \
  '{git:{request:$gitRequest,authorPrompt:$authorPrompt,authorReport:$authorReport,qualitySummary:$qualitySummary,synthesisPrompt:$synthesisPrompt,finalReport:$finalReport},unitTest:{request:$unitRequest,batchPrompt:$batchPrompt,attempts:$attempts,generatedTest:$generatedTest,finalReport:$unitReport}}' \
  > "$ROOT/evidence-manifest.json"
```

Expected: every selected path exists; no author key or batch prompt filename is invented.

- [ ] **Step 2: Redact only secrets and session identifiers**

Write `evidence-notes.txt` with the exact redactions made. Preserve real repository-relative paths, task titles, type names, attempt counts, validator messages, report headings, and result totals.

- [ ] **Step 3: Capture the two actual run-detail pages**

Use the console run ids from Tasks 2–3:

```bash
GIT_RUN_ID=$(jq -r '.id' outputs/ppt-runtime-samples/git-run-submit.json)
UNIT_RUN_ID=$(jq -r '.id' outputs/ppt-runtime-samples/unit-test-run-submit.json)
printf 'http://127.0.0.1:18081/runs/%s\n' "$GIT_RUN_ID"
printf 'http://127.0.0.1:18081/runs/%s\n' "$UNIT_RUN_ID"
```

Save 1440×1024 PNGs as `git-live-run.png` and `unit-live-run.png`. Do not click rerun, stop, submit, or schedule controls during capture.

- [ ] **Step 4: Validate provenance**

```bash
jq -e '.git.authorPrompt and .git.finalReport and .unitTest.batchPrompt and .unitTest.generatedTest' \
  outputs/ppt-runtime-samples/evidence-manifest.json
```

Expected: exit `0`; every manifest entry resolves to a real file.

---

### Task 5: 把真实 prompt、task 和产物增补到 PPT

**Files:**
- Modify: `/var/folders/dd/rj49_gmj4db8m9vpzws8fkf00000gn/T/codex-presentations/019f6380-11cb-74e1-ba19-110473d01bdd/git-unit-test-operation-manual/tmp/build-deck.mjs`
- Modify: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx`
- Create: `/var/folders/dd/rj49_gmj4db8m9vpzws8fkf00000gn/T/codex-presentations/019f6380-11cb-74e1-ba19-110473d01bdd/git-unit-test-operation-manual/tmp/qa/runtime-evidence-montage.png`

**Interfaces:**
- Consumes: `evidence-manifest.json`, actual artifacts, and live run screenshots from Task 4.
- Produces: a self-contained 30-slide operation manual with six new actual-run evidence slides.

- [ ] **Step 1: Add three Git actual-run slides**

Insert into the existing Git section:

1. `Git 实跑：运行配置与 task 状态` — live run screenshot + actual run id/state/duration.
2. `Git 实跑：程序生成的作者 prompt 与两个作者产物` — real `worker-prompt.md`, `person-report.md`, and `quality-summary.json` excerpts.
3. `Git 实跑：综合 prompt 如何收敛为最终报告` — real `synthesis-prompt.md`, `quality-scores.json`, and `code-contribution-report.md` excerpts.

Visible text must use actual file content loaded at build time; do not hardcode invented examples.

- [ ] **Step 2: Add three unit-test actual-run slides**

Insert into the existing unit-test section:

1. `单测实跑：一个类型、一个 batch、最多 5 次` — live run screenshot + actual task/attempt state.
2. `单测实跑：完整 prompt 与 failureSummary` — actual batch prompt and real attempt feedback; if the first attempt passes, explicitly state `failureSummary: none`.
3. `单测实跑：生成测试、IDEA 验收与最终报告` — actual `PromptBuilderTest.java`, `agentbridge-results.json`, and `unit-test-generation-report.md` excerpts.

- [ ] **Step 3: Export the updated PPTX**

```bash
node build-deck.mjs
```

Expected: output reports `30 slides` and overwrites only the requested final PPTX.

- [ ] **Step 4: Render and inspect all 30 slides**

```bash
rm -rf qa/rendered
/Users/wangyufeng/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 \
  /Users/wangyufeng/.codex/plugins/cache/openai-primary-runtime/presentations/26.709.11516/skills/presentations/container_tools/render_slides.py \
  /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx \
  --output_dir qa/rendered
```

Inspect every rendered slide individually at full size. Fix all unintended wrapping, overlap, clipping, broken arrows, unreadable prompt excerpts, and inconsistent page numbers.

- [ ] **Step 5: Run final structural QA**

```bash
/Users/wangyufeng/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 \
  /Users/wangyufeng/.codex/plugins/cache/openai-primary-runtime/presentations/26.709.11516/skills/presentations/container_tools/slides_test.py \
  /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx
test "$(unzip -l /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx | rg 'ppt/slides/slide[0-9]+\.xml$' | wc -l | tr -d ' ')" = "30"
```

Expected: `Test passed. No overflow detected.` and slide count `30`.

- [ ] **Step 6: Verify original worktree preservation**

```bash
git status --short
git diff --check
```

Expected: no existing source/test diff is changed by this work; only the approved PPT runtime evidence outputs and planning documents are new.
