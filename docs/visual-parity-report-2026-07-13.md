# Task Console visual-parity acceptance report

**Scope:** `codex/figma-console-visual-parity` at `fd40205` (2026-07-14).

This is an evidence report, not a claim that every target has passed visual
comparison. The checked-in PNGs below are Figma reference captures; there are
currently **no rendered browser captures** in `target/visual-regression/`.
Consequently every visual-diff result is deliberately marked **unverified**.

## Acceptance summary

| Area | Inventory | Figma reference | Rendered 1440×1024 capture | Pillow diff / console | Decision |
| --- | ---: | --- | --- | --- | --- |
| Shared components | 11 | 9 directly captured, 2 source blockers | none | unverified | blocked on capture and two missing direct sources |
| Main pages | 5 | 5 directly captured | none | unverified | blocked on visual-qa browser capture |
| Required states | 6 | 1 partial direct source, 5 source blockers | none | unverified | blocked on state sources and capture |
| Functional gates | Maven + Node + static JS | n/a | n/a | Maven postcondition clean; Node/static JS/diff clean | pass |

## Figma-source and component matrix

Viewport is `1440×1024` for every eventual application capture. The Figma
screenshots are source baselines, not browser renderings. Direct raw design
context is available only for Typography node `6:110`; the contract records
the Desktop selection limitation for all other nodes.

| Component | Figma node | Baseline | Source status | Rendered capture path | Diff ratio | Console | Decision |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Navigation Item | `61:2` | `docs/figma-baselines/components/61-2.png` | captured | — | — | unverified | capture pending |
| Button | `10:2` | `docs/figma-baselines/components/10-2.png` | captured | — | — | unverified | capture pending |
| Status Badge | `14:2` | `docs/figma-baselines/components/14-2.png` | captured | — | — | unverified | capture pending |
| Input | `23:2` | `docs/figma-baselines/components/23-2.png` | captured | — | — | unverified | capture pending |
| Metric Card | `28:2` | `docs/figma-baselines/components/28-2.png` | captured | — | — | unverified | capture pending |
| Filter Control | not supplied | none | **source blocker** | — | — | unverified | obtain Figma node/capture |
| Table Row | `40:2` | `docs/figma-baselines/components/40-2.png` | captured | — | — | unverified | capture pending |
| Alert | `44:2` | `docs/figma-baselines/components/44-2.png` | captured | — | — | unverified | capture pending |
| Drawer Shell | `54:2` | `docs/figma-baselines/components/54-2.png` | captured | — | — | unverified | capture pending |
| Progress Indicator | not supplied | none | **source blocker** | — | — | unverified | obtain Figma node/capture |
| App Shell | `73:4` | `docs/figma-baselines/components/73-4.png` | captured | — | — | unverified | capture pending |

Supporting source-only capture files `0-1.png`, `5-2.png`, `5-3.png`,
`5-4.png`, and `5-5.png` are intentionally not counted as component
baselines. The filesystem contains 14 component/reference PNGs and five page
PNGs, matching the documented collection.

## Page matrix

| Page | Figma node | Baseline | Rendered capture path | Diff ratio | Console | Decision |
| --- | --- | --- | --- | --- | --- | --- |
| Dashboard | `83:18` | `docs/figma-baselines/pages/dashboard-83-18.png` | `target/visual-regression/dashboard.png` (missing) | unverified | unverified | capture pending |
| New Run | `92:2` | `docs/figma-baselines/pages/new-run-92-2.png` | `target/visual-regression/new-run.png` (missing) | unverified | unverified | capture pending |
| Run Detail | `98:2` | `docs/figma-baselines/pages/run-detail-98-2.png` | `target/visual-regression/run-detail.png` (missing) | unverified | unverified | capture pending |
| History | `104:2` | `docs/figma-baselines/pages/history-104-2.png` | `target/visual-regression/history.png` (missing) | unverified | unverified | capture pending |
| Schedules | `110:1181` | `docs/figma-baselines/pages/schedules-110-1181.png` | `target/visual-regression/schedules.png` (missing) | unverified | unverified | capture pending |

## State matrix

| State | Direct Figma evidence | Rendered capture path | Diff ratio | Console | Decision |
| --- | --- | --- | --- | --- | --- |
| Loading | no supplied state node | — | unverified | unverified | source and capture pending |
| Empty | no supplied state node | — | unverified | unverified | source and capture pending |
| No Results | no supplied state node | — | unverified | unverified | source and capture pending |
| API Failure | no supplied state node | — | unverified | unverified | source and capture pending |
| SSE Reconnecting | no supplied state node | — | unverified | unverified | source and capture pending |
| Field Validation Error | input error examples in `components/23-2.png` | — | unverified | unverified | browser capture pending |

## Functional acceptance gates

Commands were run from the isolated worktree. A clean Maven run removes stale
`target/visual-regression/` artifacts; the subsequent file check confirmed
there was no rendered screenshot to accidentally treat as evidence.

| Gate | Command | Observed result | Decision |
| --- | --- | --- | --- |
| Java suite | `mvn -q clean test` | completed; 46 fresh `target/surefire-reports/TEST-*.xml` reports, and `rg -l '<failure|<error' target/surefire-reports/TEST-*.xml` produced no paths | pass |
| Node suite | `node --test src/test/js/*.test.js test/js/visual-components.test.js` | **31 pass / 0 fail** after the null-safe status-filter listener regression fix | pass |
| Static JavaScript | `for f in src/main/resources/static/js/*.js; do node --check "$f"; done` | pass | pass |
| Whitespace | `git diff --check` | pass | pass |
| Visual comparator | no baseline/rendered pair exists | not run; no same-image substitute | unverified |

During the first aggregate run, the pre-existing semantic drawer harness did
not supply the new `#schedule-status-filter`, while the schedule controller
installed its listener unconditionally; that yielded 29 pass / 1 fail. The
controller now treats that enhancement as optional and the added regression
test covers the omitted control. The final aggregate rerun above is 31 pass /
0 fail.

## Reproducible visual-QA procedure

1. Start the app under the guarded profile, for example:

   ```sh
   mvn -q spring-boot:run -Dspring-boot.run.profiles=visual-qa
   ```

   The profile uses only `target/visual-qa.sqlite`, fixed UTC clock, disabled
   scheduler/AgentBridge/workflow execution, and deterministic fixtures. Do not
   submit a new run.
2. In a local browser, set the viewport to `1440×1024`, open each page listed
   above, wait for the deterministic fixture state, check browser console for
   errors, and save only the rendered screenshots under
   `target/visual-regression/` at the named paths.
3. Run, once per page (and component/state where an approved baseline exists):

   ```sh
   python3 scripts/visual-regression.py BASELINE.png ACTUAL.png \
     --tolerance 16 --max-diff-ratio 0.02
   ```

   A pass requires `changed_ratio <= 0.02`; the comparator uses a per-channel
   RGB tolerance of 16. Also check `scrollWidth === innerWidth` at 1280, 1440,
   and 1600. Only a table's dedicated scroll container may scroll horizontally;
   the drawer must stay within the viewport.

## Final status and blockers

The branch has functional presentation coverage and Figma reference artifacts,
but **is not yet visually accepted**. The required blockers are:

1. Capture real isolated visual-qa browser images at all five page routes; do
   not compare source images with themselves.
2. Acquire direct Figma evidence for Filter Control, Progress Indicator, and
   five unsupplied state baselines, then add their rendered comparisons.
3. Record each real `changed_ratio`, console result, and responsive check in
   this matrix. Nothing in this report may be changed to `pass` without those
   actual artifacts.
