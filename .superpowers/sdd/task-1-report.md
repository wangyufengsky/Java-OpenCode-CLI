# Task 1 — Figma console foundations report

## Outcome

Implemented the Task Console visual foundation: checked-in Figma PNG baselines and contract, component semantic fragments and classes, layered CSS, deterministic `visual-qa` profile, scheduler/execution guards, and a Pillow visual comparator. Production defaults remain scheduler/execution enabled.

## Figma evidence and limitation

- Figma file: `7SMWQrlbLmB6yZ1kUbJ26o`.
- Saved the 14 unique supplied node screenshots under `docs/figma-baselines/components/`; the two duplicate supplied IDs were not duplicated.
- `get_screenshot` succeeded for every supplied node. Structural `get_metadata` supplied actual component names and variants used in `docs/figma-contract.md`.
- The Desktop MCP `get_design_context` endpoint remained selection-scoped: it returned complete context for selected node `6:110` (Typography), including the documented Inter/Roboto Mono typography and `#607086`; calls targeted to other supplied IDs returned `You currently have nothing selected.` This was recorded in the contract rather than replaced with fabricated token or variant data. Screenshot measurements and parent-authorized metadata are explicitly distinguished there.

## RED evidence

1. `mvn -q -Dtest=ConsoleMvcTest test`
   - Exit: non-zero.
   - Expected failure: Dashboard response did not contain `class="c-metric-card"` at `ConsoleMvcTest.pagesRenderFromPersistedRuns`.
2. `node --test test/js/visual-components.test.js`
   - Exit: non-zero.
   - Expected failure: `schedules.html` did not match `class="schedule-drawer c-drawer"`.

The Node test then executes the real schedules controller in a small DOM harness, opens the drawer, dispatches Escape, and verifies both `hidden` state and restored trigger focus.

## GREEN evidence

Focused acceptance command (rerun after final implementation):

```sh
mvn -q -Dtest=ConsoleMvcTest test && node --test test/js/visual-components.test.js && python3 scripts/visual-regression.py docs/figma-baselines/components/0-1.png docs/figma-baselines/components/0-1.png --tolerance 16 --max-diff-ratio 0.02 && python3 scripts/visual-regression.py --help && git diff --check
```

- Exit: 0.
- MVC visual contract passed.
- Node drawer contract: 1/1 passed.
- Same-image visual comparison: `changed_ratio=0.000000 (0 pixels), tolerance=16`.
- Comparator help confirms `--tolerance` defaults to 16 and `--max-diff-ratio` defaults to 0.02.
- `git diff --check` passed.

Full relevant regression commands:

```sh
mvn -q test
node --test src/test/js/*.test.js test/js/visual-components.test.js
```

- Both commands exited 0.
- Node suite: 21/21 passed.

## Changed files

- `docs/figma-baselines/components/*.png`, `docs/figma-contract.md`
- `src/main/resources/application-visual-qa.yml`, `src/test/resources/application-visual-qa.yml`, `src/main/resources/application.yml`
- `TaskConsoleProperties`, `TaskConsoleConfiguration`, `WorkflowScheduleServiceFactory`
- `styles.css`, `dashboard.html`, `schedules.html`, `fragments/layout.html`
- `ConsoleMvcTest`, `test/js/visual-components.test.js`
- `scripts/visual-regression.py`

## Self-review

- No React, Tailwind, Code Connect, external icon library, SQLite schema change, schedule API shape change, or dynamic-chain configuration change.
- Scheduler execution is conditional; the default remains `true` and `visual-qa` disables it.
- `visual-qa` uses only `target/visual-qa.sqlite`, a fixed UTC clock, disabled AgentBridge runner, and a no-execution `WorkflowExecutionService` guard.
- Generated comparison output is restricted to `target/visual-regression/`.
- Existing page IDs and `data-*` attributes remain unchanged.

## Concern

Figma Desktop context retrieval is limited by the selected layer. The checked-in screenshots and allowed structural metadata still provide durable visual/variant evidence, but a later Figma Desktop session with each component explicitly selected would be needed to replace every `design-context unavailable` annotation with raw context output.

## Commit

This report is included in the Task 1 commit with subject `feat: add console visual foundation`.

## Review-fix follow-up (uncommitted)

The review identified a source-contract and visual-QA gap. `docs/figma-contract.md`
now cites only `get_screenshot` and the selected `get_design_context` result; all
other raw component values are explicitly unavailable rather than metadata-derived.
It inventories all 11 intended components and six required states, retaining direct
source blockers for Filter Control, Progress Indicator, and unsupplied state nodes.

`VisualQaFixtureInitializer` is active only under `visual-qa`. It clears and seeds
only `target/visual-qa.sqlite` with three deterministic runs, events/tasks and two
schedules. Both visual-QA profile files disable AgentBridge, scheduling and workflow
execution; the focused integration test verifies the fixed clock, database path,
fixtures and no-execution guard. The original public four-argument
`WorkflowScheduleService` constructor is restored. All CSS rules are now inside
the declared cascade layers, and the rendered shell/control values are 1216px and
44px. Dashboard now consumes shared navigation, metric-card and status-badge
fragments. Table and alert fragments remain declared but are not inserted into the
pre-overhaul Dashboard because their dynamic cell/conditional-alert markup must be
preserved; their existing selectors/hooks remain intact for the later vertical page
slices.

### Review-fix RED/GREEN evidence

1. RED: `mvn -q -Dtest=VisualQaFixtureInitializerTest test`
   - Exit non-zero before `VisualQaFixtureInitializer` existed: expected 3 runs,
     observed 0.
2. GREEN: `mvn -q -Dtest=VisualQaFixtureInitializerTest test`
   - Exit 0 after initializer, fixed fixture profile and no-execution assertions.
3. GREEN: `mvn -q -Dtest=ConsoleMvcTest,VisualQaFixtureInitializerTest,WorkflowScheduleServiceTest test && node --test test/js/visual-components.test.js && python3 scripts/visual-regression.py docs/figma-baselines/components/0-1.png docs/figma-baselines/components/0-1.png --tolerance 16 --max-diff-ratio 0.02 && git diff --check`
   - Exit 0. MVC rendering, visual-QA fixture isolation, public constructor,
     drawer Escape/focus behavior, same-image visual comparison
     (`changed_ratio=0.000000`) and whitespace validation passed.
