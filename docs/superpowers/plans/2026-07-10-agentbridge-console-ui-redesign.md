# AgentBridge Task Console UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the approved five-page professional operations console in Figma, then implement it with the existing Spring MVC, Thymeleaf, vanilla JavaScript, and CSS stack.

**Architecture:** Keep server-rendered pages as the source of truth and add small presentation-focused services for filtering, summaries, saved-config reads, path preflight, and schedule updates. Use shared Thymeleaf fragments, CSS design tokens, and page-scoped JavaScript files; preserve existing workflow execution, configuration keys, rerun contracts, and SSE event semantics.

**Tech Stack:** Java 22, Spring Boot MVC, Thymeleaf, JdbcTemplate/SQLite, Jackson YAML, vanilla JavaScript, CSS, Figma Design.

## Global Constraints

- Do not overwrite or stage unrelated existing changes in the working tree.
- Keep the existing Spring MVC, Thymeleaf, vanilla JavaScript, and CSS architecture; add no frontend framework, CSS framework, bundler, or package dependency.
- Optimize for desktop widths of 1280 px, 1440 px, and 1600 px; a complete mobile redesign is out of scope.
- Do not change workflow execution semantics, AgentBridge protocols, configuration keys, rerun types, or persisted event meanings.
- Do not add cancel-run, batch operations, bulk delete, or result export.
- Keep all new UI copy in Chinese and align it with `ConsoleText` mappings.
- Complete and visually verify Figma before implementing code.

## File Map

- Create `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowRunFilter.java`: immutable history filter contract.
- Create `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleDashboardSummary.java`: dashboard counts and attention summary.
- Create `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleRunDetailSummary.java`: task counts, duration, failed phase/task, and last error.
- Create `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleViewService.java`: presentation-only dashboard/detail calculations.
- Create `src/main/java/com/sonnet/wyf/gitreport/console/RunConfigReader.java`: read and flatten a persisted YAML run config.
- Create `src/main/java/com/sonnet/wyf/gitreport/console/PathPreflightService.java`: read-only path and Maven-project preflight.
- Modify `ConsolePageController.java`: provide filtered history, dashboard/detail summaries, and copied config source.
- Modify `ConsoleApiController.java`: expose summaries, copied config, path preflight, and schedule update.
- Modify `WorkflowRunRepository.java`: parameterized history queries and incremental event reads.
- Modify `WorkflowScheduleRepository.java` and `WorkflowScheduleService.java`: edit/clone-ready schedule updates.
- Modify `TaskConsoleConfiguration.java`: wire the new reader, preflight, and view services.
- Expand `templates/fragments/layout.html`: shared app shell, sidebar, page header, status badge, and empty state fragments.
- Rewrite the five page templates to use the shared shell and approved information hierarchy.
- Replace `static/styles.css` with tokenized shell/component/page styles.
- Create `static/js/console-common.js`, `run-form.js`, `run-detail.js`, `history.js`, and `schedules.js`; retire page behavior from `static/app.js` after parity is verified.
- Extend `ConsoleMvcTest.java`, `WorkflowRunRepositoryTest.java`, `WorkflowScheduleServiceTest.java`, and `WorkflowScheduleRepositoryTest.java`.

---

### Task 1: Produce the Figma Foundations, Components, and Five Screens

**Files:**
- Reference: `docs/superpowers/specs/2026-07-10-agentbridge-console-ui-redesign.md`
- No repository file changes.

**Interfaces:**
- Consumes: the approved information architecture, professional operations-console direction, and 1440 px desktop baseline.
- Produces: one Figma file key; page IDs for `Foundations`, `Components`, and `Screens`; screen node IDs for dashboard, new run, failed run detail, history, and schedules.

- [ ] **Step 1: Create a new Figma Design file**

Use the `figma-create-new-file` prerequisite and create `AgentBridge Task Console Redesign` with editor type `figma`.

- [ ] **Step 2: Inspect the empty file and available libraries before mutation**

Use the required `figma-use` discovery sequence. Record pages, local components, variables, styles, available fonts, and whether a published library is available. The source font is the CSS stack `Inter, ui-sans-serif, system-ui`; use Inter only after confirming the font is available.

- [ ] **Step 3: Create foundations and component inventory**

Create variables for background, surface, sidebar, primary, text, muted text, border, success, warning, danger, spacing 4/8/12/16/20/24/32, radius 8/12/16, and desktop width 1440. Create auto-layout components for buttons, status badges, inputs, metric cards, filter controls, table rows, alerts, drawer shell, and the five-stage progress indicator.

- [ ] **Step 4: Build the five approved screens incrementally**

Build the shell first, then dashboard, new run, failed run detail, history, and schedules. Use component instances and variables rather than detached primitives. Return every created or mutated node ID from each Figma call.

- [ ] **Step 5: Add state frames**

Add compact frames for loading, empty list, no filter results, API failure, SSE reconnecting, and field validation error. State copy must match the design spec.

- [ ] **Step 6: Verify visually and structurally**

Capture screenshots of all five screens and inspect at original detail. Verify auto-layout, 1440 px screen width, no clipped text, consistent component instances, variable bindings, focus/error states, and readable status contrast.

Expected: five approved screens plus state frames, with no leftover placeholder shimmer and no detached duplicate component styles.

---

### Task 2: Introduce the Shared Application Shell and Design Tokens

**Files:**
- Modify: `src/main/resources/templates/fragments/layout.html`
- Modify: `src/main/resources/templates/dashboard.html`
- Modify: `src/main/resources/templates/run-new.html`
- Modify: `src/main/resources/templates/history.html`
- Modify: `src/main/resources/templates/schedules.html`
- Modify: `src/main/resources/templates/run-detail.html`
- Modify: `src/main/resources/static/styles.css`
- Test: `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`

**Interfaces:**
- Consumes: existing `@consoleText` mappings and page model attributes.
- Produces: `layout :: head(title)`, `layout :: sidebar(activeItem)`, `layout :: pageHeader(eyebrow,title,description)`, and common `.status-badge` state classes used by all later page tasks.

- [ ] **Step 1: Write failing shared-shell MVC assertions**

Add assertions to `pagesRenderFromPersistedRuns()`:

```java
mockMvc.perform(get("/"))
        .andExpect(content().string(containsString("class=\"app-shell\"")))
        .andExpect(content().string(containsString("aria-label=\"主导航\"")))
        .andExpect(content().string(containsString("运行概览")))
        .andExpect(content().string(containsString("新建运行")))
        .andExpect(content().string(containsString("运行历史")))
        .andExpect(content().string(containsString("定时任务")));
```

- [ ] **Step 2: Run the focused test and confirm failure**

Run: `mvn -q -Dtest=ConsoleMvcTest#pagesRenderFromPersistedRuns test`

Expected: FAIL because the existing pages do not contain `app-shell` or the shared navigation.

- [ ] **Step 3: Implement the shared fragments**

Define the sidebar fragment with stable URLs and an active marker:

```html
<aside class="app-sidebar" th:fragment="sidebar(activeItem)">
  <a class="brand" href="/" aria-label="AgentBridge 任务控制台首页">AB</a>
  <nav class="side-navigation" aria-label="主导航">
    <a href="/" th:classappend="${activeItem == 'dashboard'} ? ' active'">运行概览</a>
    <a href="/runs/new" th:classappend="${activeItem == 'new-run'} ? ' active'">新建运行</a>
    <a href="/history" th:classappend="${activeItem == 'history'} ? ' active'">运行历史</a>
    <a href="/schedules" th:classappend="${activeItem == 'schedules'} ? ' active'">定时任务</a>
  </nav>
</aside>
```

Wrap each page in `.app-shell`, insert the sidebar, and move existing page content into `.app-main` and `.page-content` without changing behavior yet.

- [ ] **Step 4: Implement the approved design tokens and shell styles**

Start `styles.css` with exact semantic tokens:

```css
:root {
  --color-canvas: #f5f7fb;
  --color-surface: #ffffff;
  --color-sidebar: #10233f;
  --color-primary: #315fe9;
  --color-text: #172033;
  --color-muted: #748398;
  --color-border: #e1e7ef;
  --color-success: #15803d;
  --color-warning: #b65c05;
  --color-danger: #b42318;
  --radius-sm: 8px;
  --radius-md: 12px;
  --radius-lg: 16px;
  --sidebar-width: 224px;
}
```

Implement visible `:focus-visible` outlines, a fixed-width desktop sidebar, a flexible content column, shared cards/buttons/inputs/status badges, and a minimum supported viewport of 1280 px without introducing mobile-only navigation.

- [ ] **Step 5: Run focused tests and inspect all pages**

Run: `mvn -q -Dtest=ConsoleMvcTest#pagesRenderFromPersistedRuns test`

Expected: PASS.

- [ ] **Step 6: Commit the shell**

```bash
git add src/main/resources/templates src/main/resources/static/styles.css src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java
git commit -m "feat: add task console application shell"
```

---

### Task 3: Add Dashboard and Run-Detail Presentation Models

**Files:**
- Create: `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleDashboardSummary.java`
- Create: `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleRunDetailSummary.java`
- Create: `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleViewService.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/console/ConsolePageController.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/config/TaskConsoleConfiguration.java`
- Modify: `src/main/resources/templates/dashboard.html`
- Modify: `src/main/resources/templates/run-detail.html`
- Test: `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`

**Interfaces:**
- Produces: `ConsoleDashboardSummary dashboard()` and `ConsoleRunDetailSummary runDetail(long runId)`.
- `ConsoleDashboardSummary`: `todayRuns`, `running`, `queued`, `succeeded`, `failed`, `successRatePercent`, `attentionRuns`.
- `ConsoleRunDetailSummary`: `totalTasks`, `succeededTasks`, `failedTasks`, `durationSeconds`, `failureMessage`, `failedTaskKey`, `lastErrorMessage`.

- [ ] **Step 1: Write failing MVC tests for summary content**

Persist one succeeded and one failed run plus a failed task, then assert:

```java
mockMvc.perform(get("/"))
        .andExpect(content().string(containsString("需要关注")))
        .andExpect(content().string(containsString("成功率")));
mockMvc.perform(get("/runs/" + failedRunId))
        .andExpect(content().string(containsString("失败摘要")))
        .andExpect(content().string(containsString("failed-task")))
        .andExpect(content().string(containsString("重跑失败任务")));
```

- [ ] **Step 2: Verify the tests fail**

Run: `mvn -q -Dtest=ConsoleMvcTest#pagesRenderFromPersistedRuns test`

Expected: FAIL because the new summary labels and failed-task presentation are absent.

- [ ] **Step 3: Implement immutable presentation records and service calculations**

Use the injected `Clock` and its zone for today boundaries and `Duration.between(startedAt, finishedAtOrNow)` for duration. Define the seven-day success rate as `SUCCEEDED / (SUCCEEDED + FAILED)`, excluding queued/running records and returning `0` when no terminal run exists. Limit `attentionRuns` to the latest five failed runs. Derive failure content only from `failureMessage`, failed `WorkflowTaskStatus`, and persisted events whose type contains `FAILED`; never infer missing causes.

```java
public record ConsoleRunDetailSummary(
        int totalTasks,
        int succeededTasks,
        int failedTasks,
        long durationSeconds,
        String failureMessage,
        String failedTaskKey,
        String lastErrorMessage
) {}
```

- [ ] **Step 4: Render the approved dashboard and detail hierarchy**

Render dashboard metric cards, attention list, recent runs, and quick-start links. Render detail status summary, five-stage progress, failure summary, failed-first task list, and event filters. The stage classifier may only advance from persisted run state, event types, and recognized task phases; an unobserved stage remains `未开始` or `状态未知` instead of being inferred. Keep links based on existing rerun contracts; do not add cancel controls.

- [ ] **Step 5: Run focused tests**

Run: `mvn -q -Dtest=ConsoleMvcTest test`

Expected: PASS.

- [ ] **Step 6: Commit presentation models**

```bash
git add src/main/java/com/sonnet/wyf/gitreport/console src/main/java/com/sonnet/wyf/gitreport/config/TaskConsoleConfiguration.java src/main/resources/templates/dashboard.html src/main/resources/templates/run-detail.html src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java
git commit -m "feat: add console run summaries"
```

---

### Task 4: Rebuild New-Run Configuration, Copy, and Path Preflight

**Files:**
- Create: `src/main/java/com/sonnet/wyf/gitreport/console/RunConfigReader.java`
- Create: `src/main/java/com/sonnet/wyf/gitreport/console/PathPreflightService.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleApiController.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/console/ConsolePageController.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/config/TaskConsoleConfiguration.java`
- Modify: `src/main/resources/templates/run-new.html`
- Create: `src/main/resources/static/js/console-common.js`
- Create: `src/main/resources/static/js/run-form.js`
- Test: `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`

**Interfaces:**
- Produces: `GET /api/runs/{id}/config` returning `{sourceRunId, chainId, mode, rerunType, rerunId, runDate, config}`.
- Produces: `GET /api/path-preflight?path=...` returning `{accessible, directory, mavenProject, message}`.
- `RunConfigReader.readFlat(Path configPath): Map<String,Object>` reads YAML and recursively flattens nested keys with dot notation.

- [ ] **Step 1: Write failing API and page tests**

Create the run config beneath JUnit `@TempDir` and point the stored run record at that real YAML file before calling the API:

```java
Path configPath = tempDir.resolve("copied-run.yml");
Files.writeString(configPath, "project:\n  id: demo\n");
repository.updateConfigPath(runId, configPath.toString());
```

```java
mockMvc.perform(get("/api/runs/" + runId + "/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceRunId").value(runId))
        .andExpect(jsonPath("$.config['project.id']").value("demo"));
mockMvc.perform(get("/api/path-preflight").param("path", tempProject.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessible").value(true))
        .andExpect(jsonPath("$.mavenProject").value(true));
mockMvc.perform(get("/runs/new").param("copyFrom", String.valueOf(runId)))
        .andExpect(content().string(containsString("复制自运行 #" + runId)))
        .andExpect(content().string(containsString("运行摘要")));
```

- [ ] **Step 2: Run the focused tests and confirm 404/failure**

Run: `mvn -q -Dtest=ConsoleMvcTest test`

Expected: FAIL because both APIs and the copied-config banner are absent.

- [ ] **Step 3: Implement safe config reading and path preflight**

Reject config reads when the run has no config path or the normalized path is not a regular file. Path preflight performs only `Files.exists`, `Files.isDirectory`, `Files.isReadable`, and `Files.exists(path.resolve("pom.xml"))`; it never creates, writes, or resolves network resources.

- [ ] **Step 4: Add field grouping metadata and rebuild the page**

Extend the field factory in `run-form.js` to accept exact metadata:

```javascript
function field(key, label, description, type = 'text', group = 'advanced', required = false, summary = false) {
  return { key, label, description, type, group, required, summary };
}
```

Render groups `project`, `scope`, `validation`, and `agentbridge`; collapse `validation` and `agentbridge` by default when they contain only advanced fields. Keep field keys unchanged. Render workflow cards, the three-stage header, default badges, inline validation, restore-default action, copied-config banner, and sticky run summary.

- [ ] **Step 5: Add debounced preflight and resilient submission**

Use a 400 ms debounce for keys ending in `.repo`, `-root`, or path fields explicitly marked for preflight. Abort the previous fetch with `AbortController`. Preserve form values on API errors, disable the submit button while pending, focus the first invalid field, and redirect only on success.

- [ ] **Step 6: Run focused tests and browser smoke**

Run: `mvn -q -Dtest=ConsoleMvcTest test`

Expected: PASS. Browser expectation: switching chain reloads grouped defaults; copied configuration is visible; unreachable paths show a non-blocking warning; successful submit redirects to `/runs/{id}`.

- [ ] **Step 7: Commit the new-run flow**

```bash
git add src/main/java/com/sonnet/wyf/gitreport/console src/main/java/com/sonnet/wyf/gitreport/config/TaskConsoleConfiguration.java src/main/resources/templates/run-new.html src/main/resources/static/js src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java
git commit -m "feat: improve workflow run creation"
```

---

### Task 5: Add Server-Side History Filters and Configuration Reuse

**Files:**
- Create: `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowRunFilter.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowRunRepository.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/console/ConsolePageController.java`
- Modify: `src/main/resources/templates/history.html`
- Create: `src/main/resources/static/js/history.js`
- Test: `src/test/java/com/sonnet/wyf/gitreport/console/WorkflowRunRepositoryTest.java`
- Test: `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`

**Interfaces:**
- Produces: `WorkflowRunRepository.listRuns(WorkflowRunFilter filter)`.
- `WorkflowRunFilter`: `query`, `state`, `chainId`, `createdFrom`, `createdUntil` with `empty()` and normalized accessors.

- [ ] **Step 1: Write repository and MVC filter tests**

```java
WorkflowRunFilter filter = new WorkflowRunFilter("weekly", RunState.FAILED, "weekly-engineering-report", null, null);
assertThat(repository.listRuns(filter)).extracting(WorkflowRunRecord::chainId)
        .containsExactly("weekly-engineering-report");
mockMvc.perform(get("/history").param("state", "FAILED").param("chainId", "weekly-engineering-report"))
        .andExpect(content().string(containsString("value=\"FAILED\" selected")))
        .andExpect(content().string(containsString("复制配置")));
```

- [ ] **Step 2: Verify focused tests fail**

Run: `mvn -q -Dtest=WorkflowRunRepositoryTest,ConsoleMvcTest test`

Expected: FAIL because the filter contract and controls do not exist.

- [ ] **Step 3: Implement parameterized SQL filtering**

Build the WHERE clauses and argument list in Java, but bind all user values through `JdbcTemplate`; never concatenate query text into SQL. Search `cast(id as text)`, `chain_id`, and `config_path`. Treat blank and invalid enum values as no filter at the controller boundary.

- [ ] **Step 4: Render persistent GET filters and row actions**

Use a GET form with `q`, `state`, `chainId`, `from`, and `until`. Render an explicit no-results state with “清除筛选”. Row actions must include `查看详情` and `/runs/new?copyFrom={id}` only.

- [ ] **Step 5: Run tests and commit**

Run: `mvn -q -Dtest=WorkflowRunRepositoryTest,ConsoleMvcTest test`

Expected: PASS.

```bash
git add src/main/java/com/sonnet/wyf/gitreport/console src/main/resources/templates/history.html src/main/resources/static/js/history.js src/test/java/com/sonnet/wyf/gitreport/console
git commit -m "feat: add run history filters and reuse"
```

---

### Task 6: Add SSE Recovery, Incremental Events, and Task Filtering

**Files:**
- Modify: `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowRunRepository.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleApiController.java`
- Modify: `src/main/resources/templates/run-detail.html`
- Create: `src/main/resources/static/js/run-detail.js`
- Test: `src/test/java/com/sonnet/wyf/gitreport/console/WorkflowRunRepositoryTest.java`
- Test: `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`

**Interfaces:**
- Produces: `WorkflowRunRepository.listEventsAfter(long runId, long afterId)`.
- Produces: `GET /api/runs/{id}/snapshot?afterEventId=N` returning `{run, summary, tasks, events}`.

- [ ] **Step 1: Write failing incremental-event and snapshot tests**

```java
assertThat(repository.listEventsAfter(runId, firstEvent.id()))
        .extracting(WorkflowRunEvent::id)
        .containsExactly(secondEvent.id());
mockMvc.perform(get("/api/runs/" + runId + "/snapshot").param("afterEventId", String.valueOf(firstEvent.id())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events[0].id").value(secondEvent.id()));
```

- [ ] **Step 2: Verify the tests fail**

Run: `mvn -q -Dtest=WorkflowRunRepositoryTest,ConsoleMvcTest test`

Expected: FAIL because the incremental repository method and snapshot endpoint are absent.

- [ ] **Step 3: Implement snapshot reads**

Query events with `where run_id=? and id>? order by id`. Reuse `ConsoleViewService.runDetail(id)` for summary data so HTML and fallback JSON stay consistent.

- [ ] **Step 4: Implement client recovery behavior**

Track `lastEventId` and a `Set` of seen IDs. On `EventSource.onerror`, show `正在重连`; after two consecutive errors start a 5-second snapshot poll. Stop polling on the next SSE `open`. Update state, summary, tasks, and incremental events from snapshots. Add client-only filters for event category and task state without changing server truth.

- [ ] **Step 5: Run tests and commit**

Run: `mvn -q -Dtest=WorkflowRunRepositoryTest,ConsoleMvcTest test`

Expected: PASS.

```bash
git add src/main/java/com/sonnet/wyf/gitreport/console src/main/resources/templates/run-detail.html src/main/resources/static/js/run-detail.js src/test/java/com/sonnet/wyf/gitreport/console
git commit -m "feat: make run monitoring resilient"
```

---

### Task 7: Add Schedule Edit, Copy, and Reliable Toggle Feedback

**Files:**
- Modify: `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleRepository.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleService.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleApiController.java`
- Modify: `src/main/resources/templates/schedules.html`
- Create: `src/main/resources/static/js/schedules.js`
- Test: `src/test/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleRepositoryTest.java`
- Test: `src/test/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleServiceTest.java`
- Test: `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`

**Interfaces:**
- Produces: `WorkflowScheduleService.update(long id, WorkflowScheduleRequest request)`.
- Produces: `POST /api/schedules/{id}` returning the updated `WorkflowScheduleRecord`.
- Existing `nextTriggerAt` is the source for “下次执行”; once schedules with `nextTriggerAt == null && lastTriggeredAt != null` render “已执行”.

- [ ] **Step 1: Write failing update and page tests**

```java
WorkflowScheduleRecord updated = service.update(id, changedRequest);
assertThat(updated.runTime()).isEqualTo(LocalTime.of(7, 30));
assertThat(updated.nextTriggerAt()).isNotNull();
mockMvc.perform(post("/api/schedules/" + id)
        .contentType("application/json")
        .content(objectMapper.writeValueAsString(changedRequest)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
```

- [ ] **Step 2: Verify focused tests fail**

Run: `mvn -q -Dtest=WorkflowScheduleRepositoryTest,WorkflowScheduleServiceTest,ConsoleMvcTest test`

Expected: FAIL because schedule update is not implemented.

- [ ] **Step 3: Implement repository and service update**

Normalize and validate the same request shape used by create. Recompute `nextTriggerAt` from the updated schedule and the injected clock. Update all editable columns and `updated_at` in one SQL statement; preserve `created_at` and `last_triggered_at`.

- [ ] **Step 4: Rebuild schedules UI**

Render the searchable list with frequency, next trigger, and accessible switch buttons. Use one right-side drawer for create and edit. “复制” populates a new unsaved draft with no ID; “编辑” posts to `/api/schedules/{id}`. On toggle failure, restore `aria-checked`, label, and visual state and show the API error.

- [ ] **Step 5: Run tests and commit**

Run: `mvn -q -Dtest=WorkflowScheduleRepositoryTest,WorkflowScheduleServiceTest,ConsoleMvcTest test`

Expected: PASS.

```bash
git add src/main/java/com/sonnet/wyf/gitreport/console src/main/resources/templates/schedules.html src/main/resources/static/js/schedules.js src/test/java/com/sonnet/wyf/gitreport/console
git commit -m "feat: improve schedule management"
```

---

### Task 8: Remove Legacy Script Paths and Complete Visual/Regression Verification

**Files:**
- Modify or delete after parity: `src/main/resources/static/app.js`
- Modify: `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`
- Verify: all files changed by Tasks 2–7.

**Interfaces:**
- Consumes: all page-scoped scripts and final Figma node IDs.
- Produces: one verified, dependency-free console implementation matching the approved Figma file.

- [ ] **Step 1: Add final asset-contract assertions**

Assert every page loads only its required scripts and no page references the legacy all-in-one behavior:

```java
mockMvc.perform(get("/runs/new"))
        .andExpect(content().string(containsString("/js/console-common.js")))
        .andExpect(content().string(containsString("/js/run-form.js")))
        .andExpect(content().string(not(containsString("/app.js"))));
mockMvc.perform(get("/runs/" + runId))
        .andExpect(content().string(containsString("/js/run-detail.js")));
```

- [ ] **Step 2: Run focused and full automated verification**

Run:

```bash
mvn -q -Dtest=ConsoleMvcTest,WorkflowRunRepositoryTest,WorkflowScheduleRepositoryTest,WorkflowScheduleServiceTest test
mvn -q test
git diff --check
```

Expected: all tests PASS and `git diff --check` prints no errors.

- [ ] **Step 3: Run the application and verify five primary flows**

Start the application with a test-safe console database/config directory. Verify dashboard, grouped run form, successful submit redirect, failed-detail summary plus SSE reconnect fallback, persistent history filters plus config copy, and schedule create/edit/copy/toggle.

- [ ] **Step 4: Verify desktop widths against Figma**

Capture dashboard, new run, failed detail, history, and schedules at 1280, 1440, and 1600 px. Compare typography, colors, 224 px sidebar, spacing, card radii, status labels, overflow, drawer bounds, focus states, empty states, and error states with the Figma screens. Fix all visible clipping, overlap, or semantic mismatches before continuing.

- [ ] **Step 5: Search for stale UI wording and dead assets**

Run:

```bash
rg -n "配置运行|控制台</a>|class=\"topbar\"|/app.js|暂无运行记录。" src/main/resources/templates src/main/resources/static
```

Expected: no stale page-shell markup or legacy `/app.js` references; any remaining Chinese copy is intentionally used in the redesigned states.

- [ ] **Step 6: Commit final cleanup**

```bash
git add src/main/resources/static src/main/resources/templates src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java
git commit -m "test: verify redesigned task console"
```

## Final Acceptance

- Figma includes Foundations, Components, five 1440 px screens, and all specified state frames.
- All five pages share the approved professional operations-console shell.
- New-run configuration is grouped, summarized, validated, copyable, and path-preflight aware.
- Failed runs surface persisted failure evidence and actionable rerun links before raw events.
- History filters persist through the URL and configuration copy creates a new draft.
- Schedules show next execution and support create, edit, copy, and reliable enable/disable feedback.
- SSE reconnects and falls back to incremental snapshot polling without duplicate events.
- Full Maven tests pass, `git diff --check` is clean, and browser captures at 1280/1440/1600 match Figma without clipping or overlap.
