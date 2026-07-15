# Leadership Simple Deck Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create an 8-slide leadership-friendly PowerPoint explaining what the project does, what a Loop is, what problems the Git and unit-test chains solve, and a clearly qualified 60%–80% efficiency scenario.

**Architecture:** Use the existing 30-slide operation manual as the sole visual source. Inspect and duplicate eight source slides, edit inherited text elements in place with `@oai/artifact-tool`, export a distinct PPTX, then render and inspect every slide.

**Tech Stack:** JavaScript ES modules, `@oai/artifact-tool`, bundled presentation template-following scripts, Poppler/LibreOffice rendering helpers.

## Global Constraints

- Output exactly 8 slides in the order defined by the approved design.
- Preserve the existing blue/white visual system, typography, spacing, footer, and page markers.
- Use plain management language; do not show code, long prompts, API fields, or file trees.
- Explain Loop as “submit, check, return for correction, accept” with a bounded retry/exit condition.
- Display “60%–80%” only as a scenario estimate, never as verified production performance.
- Preserve `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx` unchanged.
- Final output: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-loop-leadership-brief.pptx`.
- Scratch workspace: `/var/folders/dd/rj49_gmj4db8m9vpzws8fkf00000gn/T/codex-presentations/019f6380-11cb-74e1-ba19-110473d01bdd/leadership-simple-deck/tmp`.

---

### Task 1: Build the source-slide map and starter deck

**Files:**
- Create: `/var/folders/dd/rj49_gmj4db8m9vpzws8fkf00000gn/T/codex-presentations/019f6380-11cb-74e1-ba19-110473d01bdd/leadership-simple-deck/tmp/template-audit.txt`
- Create: `/var/folders/dd/rj49_gmj4db8m9vpzws8fkf00000gn/T/codex-presentations/019f6380-11cb-74e1-ba19-110473d01bdd/leadership-simple-deck/tmp/template-frame-map.json`
- Create: `/var/folders/dd/rj49_gmj4db8m9vpzws8fkf00000gn/T/codex-presentations/019f6380-11cb-74e1-ba19-110473d01bdd/leadership-simple-deck/tmp/deviation-log.txt`
- Create: `/var/folders/dd/rj49_gmj4db8m9vpzws8fkf00000gn/T/codex-presentations/019f6380-11cb-74e1-ba19-110473d01bdd/leadership-simple-deck/tmp/template-starter.pptx`

**Interfaces:**
- Consumes: source PPTX and the completed `template-inspect` inventory.
- Produces: an 8-slide starter deck whose output-to-source mapping is `1→1`, `2→2`, `3→3`, `4→7`, `5→10`, `6→21`, `7→10`, `8→2`.

- [ ] **Step 1: Write the template audit**

Record the source inventory and reuse decision exactly:

```text
Source: outputs/git-unit-test-operation-manual.pptx, 30 slides, 1280x720.
Typography: PingFang SC for narrative text, Menlo only for code; leadership deck uses PingFang SC only.
Palette: white canvas, #111827 title, #2563EB blue, #16A34A green, #EA580C orange, #0F172A dark.
Reusable frames: cover 1; two-column 2; four-step flow 3; five-step flow 7; metrics/process 10; metrics/cards 21.
Output mapping: 1→1, 2→2, 3→3, 4→7, 5→10, 6→21, 7→10, 8→2.
All output slides rewrite inherited narrative text only. Existing structural shapes, connectors, background, footer, and page marker remain.
```

- [ ] **Step 2: Create `template-frame-map.json`**

Use these exact source slides and editable inherited element IDs:

```json
{
  "outputSlides": [
    {"outputSlide":1,"sourceSlide":1,"narrativeRole":"opening thesis","reuseMode":"duplicate-slide","editTargets":[{"sourceElementIds":["2","3","4","6","7","9","10","12","13","15","16","17","18"],"action":"rewrite"}]},
    {"outputSlide":2,"sourceSlide":2,"narrativeRole":"problem and stakes","reuseMode":"duplicate-slide","editTargets":[{"sourceElementIds":["1","2","4","6","7","11","12","13","17","18","19","21"],"action":"rewrite"}]},
    {"outputSlide":3,"sourceSlide":3,"narrativeRole":"loop explanation","reuseMode":"duplicate-slide","editTargets":[{"sourceElementIds":["1","2","4","6","7","9","10","14","15","19","20","24","25","27"],"action":"rewrite"}]},
    {"outputSlide":4,"sourceSlide":7,"narrativeRole":"project capabilities","reuseMode":"duplicate-slide","editTargets":[{"sourceElementIds":["1","2","4","6","7","9","10","14","15","19","20","24","25","29","30","32"],"action":"rewrite"}]},
    {"outputSlide":5,"sourceSlide":10,"narrativeRole":"git use case","reuseMode":"duplicate-slide","editTargets":[{"sourceElementIds":["1","2","4","6","7","9","10","12","13","15","16","18","19","21","22","26","27","31","32","36","37"],"action":"rewrite"}]},
    {"outputSlide":6,"sourceSlide":21,"narrativeRole":"unit test use case","reuseMode":"duplicate-slide","editTargets":[{"sourceElementIds":["1","2","4","6","7","9","10","12","13","15","16","18","19","23","24","25","29","30","31","35","36","37"],"action":"rewrite"}]},
    {"outputSlide":7,"sourceSlide":10,"narrativeRole":"efficiency scenario","reuseMode":"duplicate-slide","editTargets":[{"sourceElementIds":["1","2","4","6","7","9","10","12","13","15","16","18","19","21","22","26","27","31","32","36","37"],"action":"rewrite"}]},
    {"outputSlide":8,"sourceSlide":2,"narrativeRole":"management value and pilot","reuseMode":"duplicate-slide","editTargets":[{"sourceElementIds":["1","2","4","6","7","11","12","13","17","18","19","21"],"action":"rewrite"}]}
  ],
  "omittedSourceSlides": [{"sourceSlide":4,"reason":"product screenshots are unnecessary for the leadership brief"}]
}
```

- [ ] **Step 3: Validate the map**

Run:

```bash
node "$SKILL_DIR/template_following_scripts/validate_template_plan.mjs" \
  --workspace "$TMP_DIR" \
  --map "$TMP_DIR/template-frame-map.json"
```

Expected: validation exits `0` with eight mapped output slides and no unresolved edit target.

- [ ] **Step 4: Prepare the starter deck**

Run:

```bash
node "$SKILL_DIR/template_following_scripts/prepare_template_starter_deck.mjs" \
  --workspace "$TMP_DIR" \
  --pptx "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx" \
  --map "$TMP_DIR/template-frame-map.json" \
  --out "$TMP_DIR/template-starter.pptx" \
  --preview-dir "$TMP_DIR/template-starter-preview" \
  --layout-dir "$TMP_DIR/template-starter-layout" \
  --contact-sheet "$TMP_DIR/template-starter-contact-sheet.png"
```

Expected: `template-starter.pptx` contains exactly eight slides in the specified order.

### Task 2: Replace inherited copy with leadership-facing content

**Files:**
- Create: `/var/folders/dd/rj49_gmj4db8m9vpzws8fkf00000gn/T/codex-presentations/019f6380-11cb-74e1-ba19-110473d01bdd/leadership-simple-deck/tmp/content.json`
- Create: `/var/folders/dd/rj49_gmj4db8m9vpzws8fkf00000gn/T/codex-presentations/019f6380-11cb-74e1-ba19-110473d01bdd/leadership-simple-deck/tmp/build-leadership-deck.mjs`
- Create: `/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-loop-leadership-brief.pptx`

**Interfaces:**
- Consumes: `template-starter.pptx`, `template-frame-map.json`, and inherited element IDs.
- Produces: the final eight-slide PPTX without adding parallel overlay content.

- [ ] **Step 1: Create the exact visible copy in `content.json`**

Use this narrative:

```json
{
  "1": {"title":"让 Agent 稳定完成重复的软件工程工作","subtitle":"项目通过可检查、可重试的 Loop，把一次性 AI 回答变成可交付的工作结果。"},
  "2": {"title":"两类重复工作，长期占用研发时间","leftTitle":"Git 贡献整理","leftBody":"人工收集提交、核对作者、归纳贡献、汇总报告。周期长，口径容易不一致。","rightTitle":"单元测试补齐","rightBody":"人工读代码、写测试、运行、定位错误、反复修正。重复度高，容易被业务需求挤压。"},
  "3": {"title":"Loop 就是：做一次、检查、反馈、再做一次","steps":["提交任务","Agent 执行","检查结果","反馈或结束"],"callout":"像员工交作业、主管检查、退回修改、验收通过；达到次数上限仍不通过，就停止并交给人工处理。"},
  "4": {"title":"项目负责把 Agent 的工作管起来","steps":["接收任务","限定范围","调用 Agent","检查产物","重试或退出"],"callout":"程序负责规则和验收，Agent 负责分析和生成；全过程有记录，可回看、可重跑。"},
  "5": {"title":"Git 链路自动把提交记录变成管理报告","metrics":["自动采集","按人分析","缺谁补谁","团队汇总"],"steps":["读取提交","分析个人贡献","检查报告完整性","生成团队报告"],"subtitle":"解决人工整理慢、贡献口径不一、缺失后需要整份重做的问题。"},
  "6": {"title":"单测链路按类生成，失败后根据真实错误继续修正","metrics":["一类一批","自动执行","最多 5 次","失败止损"],"cards":["生成测试：先读取真实代码，再编写测试。","自动验收：检查编译和测试结果。","失败反馈：把真实错误交给下一轮修正。"],"subtitle":"解决测试欠账积累、重复编写耗时、失败定位依赖人工的问题。"},
  "7": {"title":"场景测算：人工投入预计减少 60%–80%","metrics":["4 小时","0.8–1.5 小时","1.5 小时/类","0.3–0.6 小时/类"],"steps":["Git 人工","Git 复核","单测人工","单测复核"],"subtitle":"测算假设：系统负责采集、生成、测试和重试；人工负责抽查与最终确认。实际结果受代码复杂度、历史测试基础和验收标准影响。"},
  "8": {"title":"先小范围试点，用真实数据验证价值","leftTitle":"管理价值","leftBody":"减少重复投入；统一交付口径；过程可追溯；失败有上限；关键结果仍由人工确认。","rightTitle":"建议试点","rightBody":"选择 1 个仓库和 1 个小包，运行 2–4 周，记录人工工时、成功率、重试次数和返工量，再决定扩大范围。"}
}
```

- [ ] **Step 2: Implement inherited-element text replacement**

`build-leadership-deck.mjs` must:

```js
import fs from "node:fs/promises";
import { FileBlob, PresentationFile } from "@oai/artifact-tool";

const starter = `${process.env.TMP_DIR}/template-starter.pptx`;
const out = "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-loop-leadership-brief.pptx";
const deck = await PresentationFile.importPptx(await FileBlob.load(starter));
// Resolve each inherited element from the final starter layout and replace only
// the sourceElementIds classified as rewrite in template-frame-map.json.
// Keep every unlisted shape, connector, footer, page rule, and background.
// Set page markers to 01..08 and footer copy to “Git 与单元测试 Loop 项目领导简版”.
await (await PresentationFile.exportPptx(deck)).save(out);
```

Use `artifact_tool/API_QUICK_START.md` and `artifact_tool/api/API_DOCS.md` to resolve the imported slide and shape APIs. Do not use `python-pptx`, direct OOXML mutation, or visual overlays.

- [ ] **Step 3: Export the deck**

Run:

```bash
TMP_DIR="$TMP_DIR" NODE_PATH="$NODE_MODULES" "$NODE_BIN" "$TMP_DIR/build-leadership-deck.mjs"
```

Expected: the final path exists and contains exactly eight slide XML parts.

### Task 3: Render, inspect, and verify the deliverable

**Files:**
- Create: `/var/folders/dd/rj49_gmj4db8m9vpzws8fkf00000gn/T/codex-presentations/019f6380-11cb-74e1-ba19-110473d01bdd/leadership-simple-deck/tmp/final-preview/slide-1.png` through `slide-8.png`
- Create: `/var/folders/dd/rj49_gmj4db8m9vpzws8fkf00000gn/T/codex-presentations/019f6380-11cb-74e1-ba19-110473d01bdd/leadership-simple-deck/tmp/qa-ledger.txt`

**Interfaces:**
- Consumes: final PPTX and template fidelity artifacts.
- Produces: verified leadership deck and a scratch QA record.

- [ ] **Step 1: Render all slides**

Run:

```bash
"$PYTHON_BIN" "$SKILL_DIR/container_tools/render_slides.py" \
  "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-loop-leadership-brief.pptx" \
  --output_dir "$TMP_DIR/final-preview"
```

Expected: eight PNG files.

- [ ] **Step 2: Perform slide-scoped visual QA**

Inspect slides 1–8 individually at full size and record:

```text
Slide 1: minimal opening; title and subtitle fit.
Slide 2: two problems are understandable without technical context.
Slide 3: Loop analogy and bounded exit are visible.
Slide 4: project capability sequence reads left to right.
Slide 5: Git result and problem solved are explicit.
Slide 6: unit-test result and failure feedback are explicit.
Slide 7: 60%–80% is labeled scenario estimate and assumptions are readable.
Slide 8: management value and 2–4 week pilot recommendation close the story.
```

- [ ] **Step 3: Run overflow and template fidelity checks**

Run:

```bash
"$PYTHON_BIN" "$SKILL_DIR/container_tools/slides_test.py" \
  "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-loop-leadership-brief.pptx"

node "$SKILL_DIR/template_following_scripts/check_template_fidelity.mjs" \
  --workspace "$TMP_DIR" \
  --starter-pptx "$TMP_DIR/template-starter.pptx" \
  --final-pptx "/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-loop-leadership-brief.pptx" \
  --map "$TMP_DIR/template-frame-map.json" \
  --starter-layout-dir "$TMP_DIR/template-starter-layout" \
  --final-layout-dir "$TMP_DIR/final-layout" \
  --edit-dir "$TMP_DIR"
```

Expected: no canvas overflow, no unresolved placeholders, and no unplanned template deviations.

- [ ] **Step 4: Final content assertions**

Run:

```bash
test "$(unzip -l outputs/git-unit-test-loop-leadership-brief.pptx 'ppt/slides/slide*.xml' | awk '/ppt\/slides\/slide[0-9]+\.xml$/ {n++} END{print n+0}')" = "8"
unzip -p outputs/git-unit-test-loop-leadership-brief.pptx ppt/slides/slide7.xml | rg '60%–80%|场景测算|实际结果'
! unzip -p outputs/git-unit-test-loop-leadership-brief.pptx ppt/slides/slide\*.xml | rg 'worker-prompt|sourceElementId|TBD|TODO|待补充'
```

Expected: eight slides, the efficiency qualifier exists, and no authoring or technical placeholder language is visible.
