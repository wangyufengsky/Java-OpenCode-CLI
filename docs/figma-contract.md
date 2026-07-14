# AgentBridge Task Console Figma Contract

The sole Figma visual sources for this implementation are `get_screenshot` and
`get_design_context` against file `7SMWQrlbLmB6yZ1kUbJ26o`. Screenshot PNGs are
checked in so visual review is reproducible. During collection, Desktop MCP
returned raw design context only for the explicitly selected Typography frame
`6:110`; requests for the supplied component IDs returned `desktop selection
required`. This document never substitutes inferred raw properties for that
missing response.

## Evidence rules and foundations

| Item | Direct evidence | Implementation / limitation |
| --- | --- | --- |
| Typography context `6:110` | `get_design_context` response | Inter 32/40 bold, 24/32 and 20/28 semibold, 16/24 and 14/20 regular; label 14/20 medium, 12/16 semibold; Roboto Mono 13/20. |
| Colors from `6:110` | `get_design_context` response | Canvas `#f5f7fb`, text `#172033`, secondary text `#607086`, border `#e1e7ef`. |
| Shell and control geometry | screenshot-visible and task brief | 1440×1024 reference, 224px sidebar / 1216px body, and 44px controls are implemented. Per-node raw geometry is unverified until each node is selected in Figma. |
| Radius, stroke, shadow and font on components | not returned for unselected nodes | Implemented from the visual baseline; exact per-node numeric values remain unverified, not fabricated. |

## Shared component inventory

Rows marked **captured** have a direct checked-in screenshot. The screenshot is
evidence of the rendered board and any state labels visibly shown inside it; it
is not a substitute for unavailable raw design context. Rows marked **blocker**
are intentionally retained so all intended shared components are tracked rather
than silently dropped.

| Intended component | Figma source / evidence status | Screenshot-visible states or dimensions | Code mapping and raw-context limitation |
| --- | --- | --- | --- |
| Navigation Item | `61:2`, captured: `components/61-2.png` | state labels visible in board | `navigationItem`, `.c-navigation-item`; raw properties unavailable. |
| Button | `10:2`, captured: `components/10-2.png` | state/style labels visible; 44px control contract | `button`, `.c-button`; exact variants unavailable. |
| Status Badge | `14:2`, captured: `components/14-2.png` | status examples visible | `statusBadge`, `.c-status-badge`; exact colors/radius unavailable. |
| Input | `23:2`, captured: `components/23-2.png` | field-state examples visible | semantic form controls; raw dimensions unavailable. |
| Metric Card | `28:2`, captured: `components/28-2.png` | tone examples visible | `metricCard`, `.c-metric-card`; raw values unavailable. |
| Filter Control | no supplied component screenshot | **blocker:** no direct node supplied | shared filter styling remains unverified until a Figma screenshot/context is supplied. |
| Table Row | `40:2`, captured: `components/40-2.png` | row-state examples visible | `tableRow(run)`, `.c-table-row`, consumed by the Dashboard five-column run table; raw values unavailable. |
| Alert | `44:2`, captured: `components/44-2.png` | tone examples visible | `alert`, `.c-alert`; raw values unavailable. |
| Drawer Shell | `54:2`, captured: `components/54-2.png` | create/edit/loading examples visible | `.c-drawer` and schedule drawer behavior; exact 480px target remains a later screen validation. |
| Progress Indicator | no supplied component screenshot | **blocker:** no direct node supplied | `progress`, `.c-progress`; not asserted as Figma parity yet. |
| App Shell | `73:4`, captured: `components/73-4.png` | navigation arrangements visible | `.app-shell`, shell layer; per-node raw context unavailable. |

Supporting supplied screenshots are kept as collection references, not asserted
as component evidence: `0:1`, `5:2`, `5:3`, `5:4`, and `5:5`. All available
capture files live under `docs/figma-baselines/components/`.

## State baseline inventory

| Required state baseline | Evidence status | Implementation status |
| --- | --- | --- |
| Loading | blocker: no supplied state-node screenshot/context | represented by component loading attributes where applicable; visual parity pending source. |
| Empty | blocker: no supplied state-node screenshot/context | existing `.empty` semantic state retained; visual parity pending source. |
| No Results | blocker: no supplied state-node screenshot/context | history slice will map it once source is supplied. |
| API Failure | blocker: no supplied state-node screenshot/context | `.c-alert` supports the state; exact visual values unverified. |
| SSE Reconnecting | blocker: no supplied state-node screenshot/context | existing DOM behavior is preserved; visual values unverified. |
| Field Validation Error | screenshot-visible input error examples in `23:2` | existing validation hooks are preserved; raw context unavailable. |

## Visual QA profile

Activate `visual-qa` only for local screenshots. It uses
`target/visual-qa.sqlite`, fixed UTC clock `2026-07-13T00:00:00Z`, disabled
scheduler, disabled AgentBridge runner and disabled workflow execution.
`VisualQaFixtureInitializer` deletes and reseeds only that disposable database
with three fixed runs, events/tasks and two schedules at application startup.
It never opens the normal SQLite path and does not invoke final run submission.

Compare a baseline and rendered image with:

```sh
python3 scripts/visual-regression.py baseline.png actual.png --tolerance 16 --max-diff-ratio 0.02
```

The comparator evaluates RGB per-channel tolerance and fails over a 2% changed
pixel ratio. Its generated comparison artifacts are restricted to
`target/visual-regression/`.
