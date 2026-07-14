# Figma Console Visual Parity Design

## Objective

Bring the Spring MVC + Thymeleaf task console to visual parity with the Figma file `7SMWQrlbLmB6yZ1kUbJ26o` without changing the SQLite schema, workflow-run API, schedule API, or dynamic-chain configuration contract.

## Source-of-truth contract

Only Figma MCP `get_design_context` and `get_screenshot` are visual sources. The checked-in PNGs below are the 1440 x 1024 comparison inputs; application screenshots and image diffs remain generated output under `target/visual-regression/`.

| Surface | Figma node | Versioned baseline |
| --- | --- | --- |
| Dashboard | `83:18` | `docs/figma-baselines/pages/dashboard-83-18.png` |
| New Run | `92:2` | `docs/figma-baselines/pages/new-run-92-2.png` |
| Run Detail | `98:2` | `docs/figma-baselines/pages/run-detail-98-2.png` |
| History | `104:2` | `docs/figma-baselines/pages/history-104-2.png` |
| Schedules | `110:1181` | `docs/figma-baselines/pages/schedules-110-1181.png` |

Every implementation task starts by capturing the relevant component/page context and screenshot. The checked-in Figma contract document records the component name, Figma node ID, measured tokens, variants, states, and its Thymeleaf/CSS mapping before that component is changed. It covers: Navigation Item, Button, Status Badge, Input, Metric Card, Filter Control, Table Row, Alert, Drawer Shell, Progress Indicator, and App Shell; and these states: Loading, Empty, No Results, API Failure, SSE Reconnecting, and Field Validation Error.

## Confirmed layout and foundation rules

- Desktop reference size is `1440 x 1024`; the shell is a `224px` sidebar plus a `1216px` body slot.
- The body canvas is `#f5f7fb`; sidebar is `#10233f`; primary text is `#172033`; secondary text is `#607086`; primary is `#315fe9`; success is `#15803d`; warning is `#b65c05`; borders are `#e1e7ef`.
- Standard interactive control height is `44px`; navigation items are `40px`; cards have `12px` radius, controls have `8px` radius, badges use a full pill radius.
- The font stack is locally hosted Inter Latin, then `PingFang SC`, `Microsoft YaHei`, and `sans-serif`. Navigation, search, status, and operation icons are inline SVGs; no external icon library is added.
- Page CSS composes foundations and shared components only. It must not redeclare component colors, dimensions, borders, radii, shadows, focus rings, or state styles.
- At viewport widths 1280, 1440, and 1600, the page document must satisfy `scrollWidth === innerWidth`. Tables may scroll only inside their table container and drawers must stay within the viewport.

## Architecture and boundaries

`styles.css` is split into foundation tokens, App Shell rules, shared component rules, and page composition rules. Thymeleaf fragments provide shared navigation items, icons, metric cards, status badges, alerts, progress indicators, and table-row structure. Existing form IDs, `data-*` attributes, and JavaScript modules remain the compatibility boundary: `console-common.js`, `run-form.js`, `run-detail.js`, `history.js`, and `schedules.js` keep their page-owned behavior.

The controller/templates consume display DTOs rather than raw persistence records where layout needs formatted labels, state colors, elapsed durations, trends, pagination URLs, and accessibility text:

- `ConsoleMetricView`: label, value, trend text, tone, icon, accessible description.
- `ConsoleRunListItemView`: run identity, workflow text, source text, state label/tone, timestamp, duration, failure evidence, action URLs.
- `ConsolePage<T>`: normalized page number, page size, total count, total pages, items, and canonical previous/next URLs.

`WorkflowRunRepository` retains existing methods and adds parameter-bound `countRuns(WorkflowRunFilter)` plus `listRuns(WorkflowRunFilter, int limit, int offset)`. `GET /history` accepts an optional `page`: lower values normalize to one, a page above the last page resolves to the last page, and an empty result remains page one. All page, filter, clear, and copy-config URLs preserve the active filter values.

## Deterministic visual-QA boundary

The `visual-qa` profile uses `target/visual-qa.sqlite`, a fixed clock, deterministic runs/events/tasks/schedules, `task-console.scheduler-enabled=false`, and a disabled real workflow executor. Production keeps scheduler-enabled as `true` by default. The profile never opens or mutates the user's normal SQLite database and the smoke script never clicks a final run-submit control.

Pillow compares equal-sized images with a per-channel tolerance of 16; each component, page, and state screenshot must have at most two percent differing pixels. The complete visual set is 11 components, five main pages, and six state frames. Browser console output must contain zero errors.

## Vertical delivery slices

1. **Shared foundation and visual-QA** — component exhibition, fragments, CSS layers, profile isolation, fixtures, screenshot capture, comparison script, and responsive guardrails.
2. **Dashboard** — derive metrics, trends, state/duration/attention views from existing run records; render the four metric cards, recent-runs table, failure alert, attention region, quick actions, and the Figma empty state. No business data is fabricated when there are no runs.
3. **New Run** — retain dynamic fields, copy configuration, path preflight, and duplicate-submit prevention while composing the Figma three-step flow, workflow cards, grouped configuration, preflight alert, and sticky summary.
4. **Run Detail** — keep SSE snapshot ordering, existing DOM hooks, and safe failed-task rerun; render five derived stages, status evidence, task list, event filters, and reconnecting state.
5. **History** — complete server-side twenty-row pagination and render filter controls, table, no-result state, and canonical paginated links.
6. **Schedules** — retain daily/weekly/once and existing APIs; render derived title/frequency copy, toolbar, in-row state toggle, 480px drawer, overlay, save feedback, API-failure rollback, and keyboard focus containment.

## Failure handling and accessibility

All controls expose visible keyboard focus. Schedules drawer closes with Escape, restores focus to its trigger, traps Tab while open, and reverts a failed optimistic enable/disable change while showing an alert. Run-detail SSE reconnecting and form validation states use the shared alert/status primitives. Loading, empty, no-results, and API-failure states render the corresponding Figma state rather than an unstyled browser fallback.

## Verification gates

- Unit and MVC tests cover DTO derivations, repository filters/count/pagination/bind values, page structure, fragments, empty states, pagination URLs, and preserved DOM hooks.
- Node tests preserve the existing suite and add component states, page parameters, drawer focus cycle/Escape behavior, and failed-toggle rollback.
- Each slice performs RED/GREEN testing, `node --check` for every changed JavaScript file, browser smoke at 1280/1440/1600, visual comparison, browser-console inspection, and `git diff --check`.
- The final branch gate is `mvn -q clean test`, Node tests, JavaScript syntax checks, visual comparisons, responsive checks, browser console zero-error check, and a clean worktree after the final task commit.

## Non-goals

No React, Tailwind, Code Connect, external icon dependency, SQLite schema migration, API request/response change, production scheduler behavior change, master merge, or remote push is included.
