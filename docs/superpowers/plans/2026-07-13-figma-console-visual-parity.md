# Figma Console Visual Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a Figma-accurate Spring MVC task console while preserving every existing runtime, API, SQLite-schema, and browser-DOM contract.

**Architecture:** Retain server-rendered Thymeleaf pages and existing page-owned JavaScript modules. Add a presentation layer for formatted console data, split CSS/fragments into true shared primitives, and use a deterministic Spring profile plus Pillow comparison script for visual regression. Each vertical slice consumes those primitives and has its own test, visual baseline, implementation, and two-reviewer gate.

**Tech Stack:** Java 21, Spring Boot MVC, Thymeleaf, JdbcTemplate + SQLite, vanilla JavaScript, Node test runner, Pillow, Figma MCP screenshots.

## Global Constraints

- Visual source: only Figma MCP `get_design_context` and `get_screenshot` from file `7SMWQrlbLmB6yZ1kUbJ26o`.
- Desktop reference: 1440 x 1024, 224px sidebar, 1216px body slot, 44px controls, `#607086` secondary text.
- Preserve existing SQLite schema, workflow-run/schedule APIs, dynamic-chain config, existing form IDs, `data-*`, and page JS DOM hooks.
- Do not add React, Tailwind, Code Connect, or external icon libraries.
- `target/visual-regression/` contains generated screenshots/diffs only; versioned Figma baselines belong under `docs/figma-baselines/`.
- Visual QA disables scheduler and real execution, uses `target/visual-qa.sqlite`, and never clicks final run submission.
- Every task follows RED → GREEN → refactor, runs its focused checks, receives specification and quality review, and makes one commit.

---

### Task 1: Figma component contract, foundations, shared fragments, and visual-QA profile

**Files:**
- Create: `docs/figma-baselines/components/*.png`, `docs/figma-contract.md`, `src/main/resources/application-visual-qa.yml`, `src/test/resources/application-visual-qa.yml`, `scripts/visual-regression.py`, `test/js/visual-components.test.js`
- Modify: `src/main/resources/application.yml`, `src/main/java/com/sonnet/wyf/gitreport/console/TaskConsoleProperties.java`, `src/main/java/com/sonnet/wyf/gitreport/config/TaskConsoleConfiguration.java`, `src/main/resources/templates/fragments/layout.html`, `src/main/resources/static/styles.css`, `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`

**Interfaces:**
- Consumes: five checked-in page baselines and existing sidebar/template/JS contracts.
- Produces: `task-console.scheduler-enabled`, `visual-qa` deterministic clock/fixtures, component contract table, layered CSS, fragment API, and `scripts/visual-regression.py baseline actual --tolerance 16 --max-diff-ratio 0.02`.

- [ ] **Step 1: Read all 11 component nodes and six Figma state nodes, save their screenshots, and write the contract before CSS work.**

  The contract table must use this exact schema and one row per component/state; copy each ID returned by Figma MCP verbatim into its row:

  ```markdown
  | Component | Figma node | Variants/states | Tokens | Thymeleaf fragment/CSS selector | Screenshot |
  | --- | --- | --- | --- | --- | --- |
  | Component name | Figma MCP node ID | all observed variants and states | measured dimensions, colors, radius, borders, shadow, font | fragment and CSS selector | versioned PNG path |
  ```

- [ ] **Step 2: Write failing MVC and Node tests.**

  ```java
  @Test
  void pagesUseSharedAppShellAndNoLegacyVisualClassOverrides() throws Exception {
      mockMvc.perform(get("/"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("class=\"app-shell")))
              .andExpect(content().string(containsString("class=\"c-metric-card")));
  }
  ```

  ```js
  test('drawer closes on Escape and restores focus to its trigger', () => {
    const { drawer, trigger } = createDrawerFixture();
    drawer.open(trigger);
    drawer.handleKeydown({ key: 'Escape', preventDefault() {} });
    assert.equal(drawer.isOpen(), false);
    assert.equal(trigger.focused, true);
  });
  ```

- [ ] **Step 3: Run the new focused tests and confirm they fail because shared classes/profile behavior do not exist.**

  Run: `mvn -q -Dtest=ConsoleMvcTest#pagesUseSharedAppShellAndNoLegacyVisualClassOverrides test && node --test test/js/visual-components.test.js`

- [ ] **Step 4: Implement the smallest shared layer.**

  Add the property and conditional scheduler construction:

  ```java
  private boolean schedulerEnabled = true;
  public boolean isSchedulerEnabled() { return schedulerEnabled; }
  public void setSchedulerEnabled(boolean schedulerEnabled) { this.schedulerEnabled = schedulerEnabled; }
  ```

  ```yaml
  task-console:
    scheduler-enabled: false
    database-path: target/visual-qa.sqlite
  agentbridge-runner:
    enabled: false
  ```

  Move all token declarations to `@layer foundations`, shell rules to `@layer shell`, reusable `c-*` rules to `@layer components`, and page-only grid placement to `@layer pages`. Add semantic fragments for SVG icon, navigation item, button, status badge, alert, metric card, table row, and progress indicator; preserve every existing ID and `data-*` value at call sites.

- [ ] **Step 5: Add deterministic fixtures and image comparison implementation.**

  ```python
  diff = ImageChops.difference(baseline.convert('RGBA'), actual.convert('RGBA'))
  changed = sum(any(channel > tolerance for channel in pixel[:3]) for pixel in diff.getdata())
  ratio = changed / (baseline.width * baseline.height)
  raise SystemExit(0 if ratio <= max_diff_ratio else 1)
  ```

- [ ] **Step 6: Run focused verification and visual component capture.**

  Run: `mvn -q -Dtest=ConsoleMvcTest test && node --test test/js/visual-components.test.js && python3 scripts/visual-regression.py --help && git diff --check`

- [ ] **Step 7: Review and commit.**

  Specification reviewer verifies every component and state has an exact Figma ID, screenshot, token mapping, and preserved DOM hook. Quality reviewer runs the Step 6 command plus a visual-qa browser smoke without final submit. Commit: `feat: add console visual foundation`.

### Task 2: Dashboard display DTOs and Figma composition

**Files:**
- Create: `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleMetricView.java`, `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleRunListItemView.java`, `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleViewServiceTest.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleViewService.java`, `src/main/java/com/sonnet/wyf/gitreport/console/ConsolePageController.java`, `src/main/resources/templates/dashboard.html`, `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`

**Interfaces:**
- Consumes: shared metric/status/alert/table fragments and `WorkflowRunRecord`.
- Produces: `ConsoleViewService.dashboardMetrics()` and `dashboardRuns()` containing formatted color/tone/duration/trend values.

- [ ] **Step 1: Add failing view tests for empty, running, failed, and seven-day trend cases.**

  ```java
  @Test
  void dashboardReturnsEmptyMetricCardsWithoutInventingRunData() {
      assertThat(service.dashboardMetrics()).allMatch(metric -> metric.value().equals("0") || metric.value().equals("—"));
      assertThat(service.dashboardRuns()).isEmpty();
  }
  ```

- [ ] **Step 2: Run the test and observe the missing presentation methods.**

  Run: `mvn -q -Dtest=ConsoleViewServiceTest#dashboardReturnsEmptyMetricCardsWithoutInventingRunData test`

- [ ] **Step 3: Implement mapping, then compose metric cards/recent runs/attention/empty state from fragments.**

  ```java
  public record ConsoleMetricView(String label, String value, String detail, String tone, String ariaLabel) { }
  ```

  The controller adds `metrics`, `recentRuns`, and `attentionRuns`; the template must not access raw run timestamps/states for presentation.

- [ ] **Step 4: Verify Dashboard at all responsive widths and Figma baseline.**

  Run: `mvn -q -Dtest=ConsoleViewServiceTest,ConsoleMvcTest test && node --check src/main/resources/static/js/console-common.js && python3 scripts/visual-regression.py docs/figma-baselines/pages/dashboard-83-18.png target/visual-regression/dashboard.png --tolerance 16 --max-diff-ratio 0.02 && git diff --check`

- [ ] **Step 5: Review and commit.**

  Reviewers verify no fabricated empty data, all four Figma tones, table-only overflow, 1280/1440/1600 no page overflow, and zero browser console errors. Commit: `feat: align dashboard with figma`.

### Task 3: New Run and Run Detail vertical slices

**Files:**
- Modify: `src/main/resources/templates/run-new.html`, `src/main/resources/templates/run-detail.html`, `src/main/java/com/sonnet/wyf/gitreport/console/ConsolePageController.java`, `src/main/java/com/sonnet/wyf/gitreport/console/ConsoleViewService.java`, `src/main/resources/static/js/run-form.js`, `src/main/resources/static/js/run-detail.js`, `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`, `test/js/run-form.test.js`, `test/js/run-detail.test.js`

**Interfaces:**
- Consumes: existing `copyFrom`, defaults/preflight endpoints, submit IDs/data attributes, SSE snapshot ordering, `stages`, and failed-task rerun action.
- Produces: grouped Figma form composition, sticky summary, five-stage display data, reconnecting state, and shared alert/progress usage.

- [ ] **Step 1: Write failing MVC/Node tests for retained contracts and new states.**

  ```java
  @Test
  void runDetailRendersAllFiveDerivedStagesAndReconnectAlert() throws Exception {
      mockMvc.perform(get("/runs/{id}", 1L))
              .andExpect(content().string(containsString("data-stage=\"提交\"")))
              .andExpect(content().string(containsString("data-sse-state=\"reconnecting\"")));
  }
  ```

  ```js
  test('submit failure restores the disabled run button', async () => {
    const fixture = createRunFormFixture({ submitRejects: true });
    await fixture.submit();
    assert.equal(fixture.submitButton.disabled, false);
  });
  ```

- [ ] **Step 2: Run each targeted test red.**

  Run: `mvn -q -Dtest=ConsoleMvcTest#runDetailRendersAllFiveDerivedStagesAndReconnectAlert test && node --test test/js/run-form.test.js test/js/run-detail.test.js`

- [ ] **Step 3: Implement only presentation/composition changes.**

  Keep `run-form.js` exports and selectors. Use `aria-busy`, shared field-error alert, and existing `AbortController`; create no new submit endpoint. Keep SSE event ordering logic and derive reconnect UI from its existing connection state instead of reordering snapshots.

- [ ] **Step 4: Verify both page baselines without submitting a run.**

  Run: `mvn -q -Dtest=ConsoleMvcTest test && node --test test/js/run-form.test.js test/js/run-detail.test.js && node --check src/main/resources/static/js/run-form.js && node --check src/main/resources/static/js/run-detail.js && git diff --check`

- [ ] **Step 5: Review and commit.**

  Reviewers confirm copy config, dynamic fields, preflight, duplicate prevention, failed-task rerun, and all original DOM hooks remain present; browser smoke stops before final submit. Commit: `feat: align run console pages with figma`.

### Task 4: Server-side History pagination and Figma page composition

**Files:**
- Create: `src/main/java/com/sonnet/wyf/gitreport/console/ConsolePage.java`, `src/test/js/history.test.js`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/console/WorkflowRunRepository.java`, `src/main/java/com/sonnet/wyf/gitreport/console/ConsolePageController.java`, `src/main/resources/templates/history.html`, `src/main/resources/static/js/history.js`, `src/test/java/com/sonnet/wyf/gitreport/console/WorkflowRunRepositoryTest.java`, `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`

**Interfaces:**
- Consumes: `WorkflowRunFilter` and parameter-bound SQL filtering.
- Produces: `long countRuns(WorkflowRunFilter)`, `List<WorkflowRunRecord> listRuns(WorkflowRunFilter, int, int)`, and `ConsolePage<ConsoleRunListItemView>` with page size 20.

- [ ] **Step 1: Add failing repository/controller tests.**

  ```java
  @Test
  void listRunsUsesLimitAndOffsetAfterBoundFilterArguments() {
      assertThat(repository.listRuns(filter, 20, 20)).hasSize(20);
      assertThat(repository.countRuns(filter)).isEqualTo(41);
  }

  @Test
  void historyClampsInvalidAndPastLastPagesWhilePreservingFilters() throws Exception {
      mockMvc.perform(get("/history").param("q", "weekly").param("page", "999"))
              .andExpect(content().string(containsString("page=3")))
              .andExpect(content().string(containsString("q=weekly")));
  }
  ```

- [ ] **Step 2: Run the focused tests red.**

  Run: `mvn -q -Dtest=WorkflowRunRepositoryTest,ConsoleMvcTest test`

- [ ] **Step 3: Implement count/list helpers and canonical page normalization.**

  ```java
  public record ConsolePage<T>(int page, int pageSize, long total, int totalPages, List<T> items) { }
  ```

  `page < 1` becomes one; empty data has page/totalPages one; a page above totalPages becomes totalPages. Build every filter/clear/copy/previous/next URL through one method accepting `WorkflowRunFilter` plus `page`.

- [ ] **Step 4: Compose History and verify no-result/pagination behavior.**

  Run: `mvn -q -Dtest=WorkflowRunRepositoryTest,ConsoleMvcTest test && node --test test/js/history.test.js && node --check src/main/resources/static/js/history.js && python3 scripts/visual-regression.py docs/figma-baselines/pages/history-104-2.png target/visual-regression/history.png --tolerance 16 --max-diff-ratio 0.02 && git diff --check`

- [ ] **Step 5: Review and commit.**

  Reviewers inspect SQL bind order, all boundary pages, 20-row limit, preserved filter URLs, copy-config links, no-results page one, and table-container-only scrolling. Commit: `feat: add paged figma history view`.

### Task 5: Schedules drawer, scheduler switch, and Figma state handling

**Files:**
- Modify: `src/main/resources/templates/schedules.html`, `src/main/resources/static/js/schedules.js`, `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`, `src/test/java/com/sonnet/wyf/gitreport/console/WorkflowScheduleServiceTest.java`, `test/js/schedules.test.js`

**Interfaces:**
- Consumes: daily/weekly/once records and existing create/edit/copy/enable APIs.
- Produces: derived title/frequency copy, 480px focus-managed drawer, API-failure alert, and optimistic-toggle rollback.

- [ ] **Step 1: Add failing scheduler/UI tests.**

  ```java
  @Test
  void visualQaProfileDisablesScheduler() {
      assertThat(context.getBean(TaskConsoleProperties.class).isSchedulerEnabled()).isFalse();
  }
  ```

  ```js
  test('failed enable toggle restores prior checked value and announces the error', async () => {
    const fixture = createSchedulesFixture({ toggleRejects: true });
    await fixture.toggleFirstSchedule();
    assert.equal(fixture.firstToggle.checked, false);
    assert.match(fixture.alert.textContent, /保存失败/);
  });
  ```

- [ ] **Step 2: Run tests red, then implement the minimal conditional scheduler behavior and drawer controller.**

  Run: `mvn -q -Dtest=WorkflowScheduleServiceTest,ConsoleMvcTest test && node --test test/js/schedules.test.js`

  The drawer is exactly `480px` wide, owns the active focus cycle while open, closes on Escape/overlay, returns focus to the invoking element, and does not add a database field. Frequency/title text is derived from existing record fields.

- [ ] **Step 3: Verify Schedules baseline and error rollback.**

  Run: `mvn -q -Dtest=WorkflowScheduleServiceTest,ConsoleMvcTest test && node --test test/js/schedules.test.js && node --check src/main/resources/static/js/schedules.js && python3 scripts/visual-regression.py docs/figma-baselines/pages/schedules-110-1181.png target/visual-regression/schedules.png --tolerance 16 --max-diff-ratio 0.02 && git diff --check`

- [ ] **Step 4: Review and commit.**

  Reviewers verify API request/response compatibility, no new schema field, focus/Escape/Tab flow, failed-toggle rollback, disabled visual-QA scheduler, and zero console errors. Commit: `feat: align schedules with figma`.

### Task 6: Complete visual matrix and final acceptance report

**Files:**
- Create: `docs/visual-parity-report-2026-07-13.md`
- Modify: `scripts/visual-regression.py`, `docs/figma-contract.md`, component/page/state Figma baselines as needed

**Interfaces:**
- Consumes: all preceding implementation commits and Figma baselines.
- Produces: a checked report listing each node, viewport, actual path, diff ratio, console result, and acceptance decision.

- [ ] **Step 1: Add a failing visual-manifest test that requires 11 components, five pages, and six states.**

  ```python
  assert len(manifest['components']) == 11
  assert len(manifest['pages']) == 5
  assert len(manifest['states']) == 6
  ```

- [ ] **Step 2: Capture deterministic visual-qa screenshots and run the comparison matrix.**

  Run: `python3 scripts/visual-regression.py --manifest docs/figma-contract.md --output target/visual-regression`

- [ ] **Step 3: Run the complete final gate with fresh evidence.**

  Run: `mvn -q clean test && node --test test/js/*.test.js && find src/main/resources/static/js -name '*.js' -print0 | xargs -0 -n1 node --check && python3 scripts/visual-regression.py --manifest docs/figma-contract.md --output target/visual-regression && git diff --check && git status --short`

- [ ] **Step 4: Review and commit.**

  Specification reviewer checks one-to-one Figma node coverage and no non-Figma visual source. Quality reviewer independently repeats Step 3, verifies all three viewports have no page-level overflow, confirms smoke tests did not submit a run or touch the normal SQLite file, and checks browser console output. Commit: `test: add visual parity acceptance report`.
