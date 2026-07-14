# Task 5 — Schedules drawer, scheduler switch, and Figma state report

## Outcome

Schedules now composes the Figma toolbar with a client-side enable-status
filter, derives the visible plan title and frequency copy from the existing
schedule record fields, and includes the drawer save notice represented in the
baseline. The existing 480px drawer, focus trap, Escape/overlay close,
focus-return, optimistic state switch, API-failure rollback, and live error
announcement remain intact and now have explicit regression coverage for
Escape/overlay focus return.

No SQLite field, schedule API shape, daily/weekly/once model, dynamic-chain
configuration contract, or scheduler production default changed. The visual-QA
profile remains independently tested with `scheduler-enabled=false` and its
disposable database/executor guard.

## RED evidence

Before implementation, I added the following tests and observed the intended
failures:

```sh
node --test src/test/js/schedules.test.js
mvn -q -Dtest=ConsoleMvcTest#schedulesPageProvidesSearchDrawerCopyEditAndAccessibleSwitches test
```

The Node status-filter test failed with `2 !== 1`, because `filterRows` only
considered the text query. The MVC test failed because
`id="schedule-status-filter"` was absent from the rendered schedules page.

## GREEN evidence

```sh
node --test src/test/js/schedules.test.js
node --check src/main/resources/static/js/schedules.js
mvn -q -Dtest=WorkflowScheduleServiceTest,ConsoleMvcTest,VisualQaFixtureInitializerTest test
git diff --check
```

All commands exited `0`. The Node suite reports 11 passing tests. The visual-QA
test intentionally starts an invalid overridden-profile context to prove the
database guard, so its log contains that rejected startup while the test suite
passes.

## Compatibility and accessibility

- `ConsoleText.scheduleTitle` and `scheduleFrequency` use only
  `WorkflowScheduleRecord` fields. The API still returns the original record.
- The status filter only hides current table rows; it makes no server request.
- Each table row retains `data-schedule-id`, existing edit/copy controls, and
  the `role="switch"` enable endpoint flow. A failed optimistic toggle still
  restores its previous checked/ARIA/visible state and writes the error to the
  live page message.
- The drawer remains `min(480px, calc(100vw - 80px))`: exactly 480px on the
  desktop reference and constrained within narrow viewports. Tab containment,
  Escape and backdrop close, and focus restoration are covered without
  triggering a save or workflow run.

## Visual evidence status

The Figma Schedules baseline is
`docs/figma-baselines/pages/schedules-110-1181.png`. No browser screenshot
harness is connected to an isolated `visual-qa` server in this workspace, so
no rendered `target/visual-regression/schedules.png` was captured and the
Pillow comparator was deliberately not run. A final visual-QA pass must start
the isolated profile, capture the page at 1440×1024 without submitting a run,
then execute:

```sh
python3 scripts/visual-regression.py \
  docs/figma-baselines/pages/schedules-110-1181.png \
  target/visual-regression/schedules.png \
  --tolerance 16 --max-diff-ratio 0.02
```
