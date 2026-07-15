# Git and Unit Test Operation Manual Deck Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Build and verify a 24-slide Chinese PowerPoint operation manual for the shared workflow platform, git-code-contribution-report, and project-unit-test-generation, with bounded loop behavior as the narrative spine.

**Architecture:** Use a repository-external scratch workspace for all intermediate assets and one plain JavaScript ES module built on @oai/artifact-tool. Collect current product screenshots and source-backed snippets first, then compose the common, Git, and unit-test sections into one 16:9 deck and validate it through render, overflow, and full-size visual QA passes.

**Tech Stack:** JavaScript ES modules, @oai/artifact-tool, bundled presentation render/QA scripts, Spring Boot task-console fixture, local browser screenshots, PowerPoint PPTX.

## Global Constraints

- Final file: /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx.
- All intermediate assets and prose live under a repository-external TMP_DIR.
- Deck has exactly 24 slides in the approved order.
- Use current master source and live product pages as evidence.
- Use current product screenshots for Dashboard, New Run, Run Detail, and History; Figma baselines are visual references only.
- Audience-facing content is Chinese; exact chain IDs, fields, task types, prompts, and artifact names remain English.
- Use 16:9; deck title at least 50pt, slide titles at least 35pt, subheads at least 24pt, body at least 16pt.
- Each loop shows its object, validation condition, feedback path, upper bound, and exit conditions.
- Git author recovery is completion-gate selective rerun, not same-session correction.
- Unit-test batches are serial, one top-level class per batch, with max-attempts defaulting to 5.
- Coverage is disabled by default; only the enabled branch uses Maven and JaCoCo.
- Generate with @oai/artifact-tool, never python-pptx.
- Fix all unintended overlaps, clipping, overflow, unexpected wrapping, and low-resolution screenshots.

---

## File Structure

- Create: /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx — final deliverable.
- Create: TMP_DIR/build-deck.mjs — complete deck builder and exporter.
- Create: TMP_DIR/source-notes.txt — source paths and screenshot provenance.
- Create: TMP_DIR/slide-plan.txt — 24-slide content ledger.
- Create: TMP_DIR/evidence-snippets.txt — sanitized source-backed examples.
- Create: TMP_DIR/qa-ledger.txt — per-slide visual QA findings.
- Create: TMP_DIR/assets/*.png — current product screenshots.
- Create: TMP_DIR/preview/slide-*.png — rendered slide images.
- Read only: docs/superpowers/specs/2026-07-15-git-unit-test-operation-manual-ppt-design.md.
- Read only: README.md, src/main/java/**, src/main/resources/chains/**, and prompt packs.

### Task 1: Initialize the presentation workspace and ledgers

**Files:**
- Create: TMP_DIR/source-notes.txt
- Create: TMP_DIR/slide-plan.txt
- Create: TMP_DIR/build-deck.mjs
- Create: /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/

**Interfaces:**
- Consumes: approved design spec and presentation runtime.
- Produces: initialized artifact-tool workspace and stable paths for every later task.

- [ ] **Step 1: Resolve workspace variables outside the repository**

    SKILL_DIR=/Users/wangyufeng/.codex/plugins/cache/openai-primary-runtime/presentations/26.709.11516/skills/presentations
    SCRATCH_ROOT="$(node -p "require('node:os').tmpdir()")"
    THREAD_ID="${CODEX_THREAD_ID:-manual-20260715}"
    WORKSPACE="$SCRATCH_ROOT/codex-presentations/$THREAD_ID/git-unit-test-operation-manual"
    TMP_DIR="$WORKSPACE/tmp"
    mkdir -p "$TMP_DIR/assets" "$TMP_DIR/preview" "$TMP_DIR/layout" "$TMP_DIR/qa"
    mkdir -p /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs

Expected: scratch is outside the repository and the final output directory exists.

- [ ] **Step 2: Initialize artifact-tool resolution**

    node "$SKILL_DIR/container_tools/setup_artifact_tool_workspace.mjs" --workspace "$TMP_DIR"

Expected: Node resolves @oai/artifact-tool from TMP_DIR.

- [ ] **Step 3: Create the evidence and slide ledgers**

Use apply_patch to write source-notes.txt with exact source paths for all current loop claims. Write slide-plan.txt with 24 numbered records and fields for title, audience takeaway, visual, source, and loop contract.

Expected: the plan includes slides 1 through 24 and both ledgers contain complete, reviewable entries.

- [ ] **Step 4: Create the plain JavaScript entrypoint**

Use apply_patch to create build-deck.mjs with artifact-tool imports, a 13.333 × 7.5 inch presentation, shared colors/type tokens, helper functions, and final export to the required PPTX path.

Expected:

    node --check "$TMP_DIR/build-deck.mjs"

exits 0.

### Task 2: Capture current product-page evidence

**Files:**
- Create: TMP_DIR/assets/dashboard.png
- Create: TMP_DIR/assets/new-run-git.png
- Create: TMP_DIR/assets/new-run-unit-test.png
- Create: TMP_DIR/assets/run-detail.png
- Create: TMP_DIR/assets/history.png
- Modify: TMP_DIR/source-notes.txt

**Interfaces:**
- Consumes: local task-console fixture.
- Produces: consistent, current-state screenshots without triggering real workflow side effects.

- [ ] **Step 1: Start the safe visual-QA fixture**

Read docs/visual-parity-report-2026-07-13.md and the current fixture configuration. Start the supported Spring Boot fixture in a PTY and confirm the port.

Expected: Dashboard returns HTTP 200 and no production workflow starts.

- [ ] **Step 2: Capture Dashboard and History**

Use the in-app browser at 1440×1024 after fonts and dynamic content settle.

Expected: navigation, metrics, run table, filters, and status labels are legible with no browser chrome.

- [ ] **Step 3: Capture New Run for both chains**

Open /runs/new?chainId=git-code-contribution-report and /runs/new?chainId=project-unit-test-generation. Do not click final submit.

Expected: both screenshots show chain, mode, rerun controls, and configuration snapshot.

- [ ] **Step 4: Capture a fixture Run Detail**

Open an existing fixture run that shows tasks, events, status, and artifact information. Do not invoke stop or rerun.

Expected: loop-relevant states are visible.

- [ ] **Step 5: Record provenance and verify image dimensions**

    file "$TMP_DIR/assets/"*.png

Expected: five high-resolution PNGs exist. Record URL, fixture, viewport, and date in source-notes.txt.

### Task 3: Prepare source-backed examples

**Files:**
- Create: TMP_DIR/evidence-snippets.txt
- Modify: TMP_DIR/source-notes.txt

**Interfaces:**
- Consumes: current Java workflow classes, YAML, prompt packs, README, and safe fixture outputs.
- Produces: short sanitized YAML, JSON, prompt, artifact, and directory-tree excerpts.

- [ ] **Step 1: Extract shared loop evidence**

Record clear-session, submission, idle polling, validation, correction, upper bound, timeout, and agent-status.json facts from AgentBridgeTaskRunner and AgentBridgeClient with source line references.

- [ ] **Step 2: Extract Git loop evidence**

Record GitReportOrchestrator flow, OutputCompletionGate maximum 5 rounds, author validationMaxCorrections=0, and synthesis corrections defaulting to 2.

- [ ] **Step 3: Extract unit-test loop evidence**

Record precheck, one-class batch, failureSummary prompt feedback, protected snapshot, maxAttempts, IDEA MCP calls, and optional coverage branch.

- [ ] **Step 4: Sanitize visible examples**

Use /workspace/example-project and /reports/example-run. Remove personal paths, credentials, private author names, and secrets.

Expected:

    rg -n '/Users/|/home/wangyufeng|token|password|secret' "$TMP_DIR/evidence-snippets.txt"

returns no visible sensitive value.

### Task 4: Compose slides 1–9, common platform

**Files:**
- Modify: TMP_DIR/build-deck.mjs

**Interfaces:**
- Consumes: screenshots, slide plan, evidence snippets, and Codex Grid references.
- Produces: cover, reading guide, page map, operating steps, bounded loop, and task/prompt/artifact explanation.

- [ ] **Step 1: Build slides 1–3**

Create a minimal cover, role-based reading guide, and product-positioning page. Keep the cover title one line and at least 50pt.

- [ ] **Step 2: Build slides 4–6**

Use current Dashboard, History, New Run, and Run Detail screenshots. Add numbered annotations tied to visible controls.

- [ ] **Step 3: Build slide 7, common bounded loop**

Create connectors first, then six native PowerPoint nodes: prepare, submit, observe, validate, correct/rerun, converge. Add success, timeout, and upper-bound exits.

- [ ] **Step 4: Build slides 8–9**

Show a real task/prompt/artifact relationship and compare same-task correction, business selective rerun, and user rerun.

Expected: every loop is explicitly bounded and has an owner.

### Task 5: Compose slides 10–17, Git report

**Files:**
- Modify: TMP_DIR/build-deck.mjs

**Interfaces:**
- Consumes: Git UI capture, YAML, prompt/artifact examples, and live-source evidence.
- Produces: complete Git usage and double-loop walkthrough.

- [ ] **Step 1: Build slides 10–11**

Explain use cases, inputs, outputs, and minimal configuration with the current New Run screenshot.

- [ ] **Step 2: Build slides 12–13**

Show Java preparation into summary.json and index_inputs.json, then one-author-one-task Agent execution using worker-prompt.md.

- [ ] **Step 3: Build slide 14, completion loop**

Create connectors first, then author tasks, full inspection, incomplete-author selection, maximum five reruns, incomplete-reports.json, and synthesis gate.

Expected: validationMaxCorrections=0 is visible for author tasks.

- [ ] **Step 4: Build slide 15, synthesis correction loop**

Show quality scoring, synthesis-inputs.json, synthesis-prompt.md, Agent synthesis, FinalReportValidator, and default maximum two corrections.

- [ ] **Step 5: Build slides 16–17**

Show output tree and excerpts, plus author/synthesis rerun choice and common failure handling.

### Task 6: Compose slides 18–24, unit-test generation

**Files:**
- Modify: TMP_DIR/build-deck.mjs

**Interfaces:**
- Consumes: unit-test UI capture, YAML, task/prompt examples, IDEA MCP evidence, and attempt results.
- Produces: complete unit-test operation, attempt loop, verification branch, artifacts, rerun, and quick reference.

- [ ] **Step 1: Build slides 18–19**

Explain prerequisites, source range, write boundary, package-paths, coverage toggle, endpoints, timeout, and max attempts.

- [ ] **Step 2: Build slides 20–21**

Show one top-level class per serial batch, unit-test-plan.json, test-batches.json, attempt prompt, failure summary, and protected paths.

- [ ] **Step 3: Build slide 22, per-class attempt loop**

Create connectors first, then precheck, clear session, Agent test edit, protected snapshot, IDEA MCP validation, feedback summary, acceptance, immediate protected-file failure, and five-attempt cap.

Expected: the next class is reachable only after current-batch acceptance.

- [ ] **Step 4: Build slide 23, validation branches**

Make default IDEA run_tests plus read_run_output visually primary. Show Maven/JaCoCo only as the require-coverage=true branch.

- [ ] **Step 5: Build slide 24**

Show attempt records, agentbridge-results.json, final report, test-batch/verification rerun, and a compact Git-versus-unit-test lookup table.

### Task 7: Export, render, and run structural QA

**Files:**
- Create: final PPTX
- Create: TMP_DIR/preview/slide-*.png
- Create: TMP_DIR/qa/montage.png
- Create: TMP_DIR/qa-ledger.txt

**Interfaces:**
- Consumes: completed build-deck.mjs.
- Produces: final candidate, 24 rendered slides, montage, and structural QA results.

- [ ] **Step 1: Export**

    node "$TMP_DIR/build-deck.mjs"

Expected: the final PPTX exists and build exits 0.

- [ ] **Step 2: Check boundaries and slide count**

    python "$SKILL_DIR/container_tools/slides_test.py" /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx
    unzip -l /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx | rg 'ppt/slides/slide[0-9]+[.]xml$' | wc -l

Expected: no overflow errors and count is 24.

- [ ] **Step 3: Render and create montage**

    python "$SKILL_DIR/container_tools/render_slides.py" /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx --output_dir "$TMP_DIR/preview"
    python "$SKILL_DIR/container_tools/create_montage.py" --input_dir "$TMP_DIR/preview" --output_file "$TMP_DIR/qa/montage.png"

Expected: 24 PNGs and one montage.

- [ ] **Step 4: Record deck-level findings**

Inspect the montage for rhythm, screenshot balance, loop emphasis, type hierarchy, and color semantics. Record slide-specific fixes in qa-ledger.txt.

### Task 8: Full-size visual QA and delivery verification

**Files:**
- Modify: TMP_DIR/build-deck.mjs
- Modify: TMP_DIR/qa-ledger.txt
- Replace: final PPTX

**Interfaces:**
- Consumes: rendered slides and structural QA.
- Produces: verified deck with no unresolved visual or factual defects.

- [ ] **Step 1: Inspect all 24 slides individually**

Check title wrapping, body readability, screenshot legibility, connector routing, footer consistency, contrast, clipping, and accidental overlaps.

Expected: qa-ledger.txt contains PASS or a concrete fix for slides 1–24.

- [ ] **Step 2: Apply every fix with apply_patch**

Shorten text before reducing type, preserve minimum font sizes, and reroute connectors before moving loop labels.

- [ ] **Step 3: Rebuild and rerun checks**

    node "$TMP_DIR/build-deck.mjs"
    python "$SKILL_DIR/container_tools/slides_test.py" /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx

Expected: exit 0 with no overflow.

- [ ] **Step 4: Re-render and inspect changed slides plus montage**

Expected: all 24 slides are present, loop arrows and labels are readable, screenshots are crisp, and no overlap or clipping remains.

- [ ] **Step 5: Verify handoff state**

    test -s /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/outputs/git-unit-test-operation-manual.pptx
    git status --short

Expected: final PPTX is non-empty and repository state contains only intended documentation and output artifacts.
