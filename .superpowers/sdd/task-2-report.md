# Task 2 — Dashboard display DTOs and Figma composition report

## Outcome

The Dashboard now consumes presentation-only DTOs for metric cards and persisted
run rows. `ConsoleViewService` formats card labels, values, details, state tones,
duration and seven-day success trend; `dashboard.html` no longer reads a run's
raw state, mode, chain ID or timestamp. It renders four metric tones, a real
failure alert, a bounded recent-run table, failure attention, quick actions and
an empty-state composition without inventing run records.

## RED evidence

Command run before the production DTO/mapping implementation:

```sh
mvn -q -Dtest=ConsoleViewServiceTest test
```

Result: non-zero, as expected. The new test could not compile because
`ConsoleViewService.dashboardMetrics()`, `dashboardRuns()`,
`ConsoleMetricView`, and `ConsoleRunListItemView` did not yet exist. This
covered empty metrics/rows, running and failed state mapping, formatted duration,
and the seven-day success-rate trend.

## GREEN evidence

```sh
mvn -q -Dtest=ConsoleViewServiceTest,ConsoleMvcTest test && \
node --check src/main/resources/static/js/console-common.js && \
git diff --check
```

Result: exit 0. The focused view-service tests cover empty data, running/failed
state tone, duration and seven-day trend. MVC checks cover the semantic metric
cards, four tone data attributes, alert, table rows and duration column. The
unchanged JavaScript passed syntax validation and whitespace validation passed.

The focused MVC suite emits its pre-existing Mockito dynamic-agent warnings and
executes its own legacy workflow scenario against `target/test-console`; this
Task 2 change did not submit a final run and did not use a real user SQLite file.

## Changed files

- `ConsoleMetricView.java`, `ConsoleRunListItemView.java`
- `ConsoleViewService.java`, `ConsolePageController.java`
- `dashboard.html`, `fragments/layout.html`, `styles.css`
- `ConsoleViewServiceTest.java`, `ConsoleMvcTest.java`

## Compatibility and self-review

- No SQLite schema, run/schedule API, route, dynamic-chain configuration, form
  ID, or JavaScript DOM hook was changed.
- The Dashboard reads formatted DTO properties for persisted-run presentation;
  raw timestamp/state mapping is confined to `ConsoleViewService`.
- Recent runs are sorted and capped at eight in the service; table overflow stays
  inside the existing panel and the existing responsive page rules retain no
  document-level minimum width.
- No React, Tailwind, Code Connect, external icon library, or final-submit
  browser action was added.

## Spec-review remediation

The Task 2 specification review identified two gaps and this follow-up fixes
only those gaps.

### RED evidence

```sh
mvn -q -Dtest=ConsoleViewServiceTest,ConsoleMvcTest test
```

Result: non-zero as expected. The new view-service assertions could not compile
because `ConsoleMetricView.trendTone()` did not exist. The RED tests cover a
positive seven-day delta, a negative delta (`-100 个百分点`), and an initial
comparison with no history.

### GREEN evidence

```sh
mvn -q -Dtest=ConsoleViewServiceTest,ConsoleMvcTest test
node --check src/main/resources/static/js/console-common.js
git diff --check
```

Result: all commands exit 0. The focused Maven suite verifies all four
Dashboard card tones (`primary`, `info`, `success`, `danger`), the rendered
neutral trend attribute, and the dedicated Dashboard table wrapper. The view
tests verify `positive`, `danger`, and `neutral` trend tones.

### Changes and compatibility

- `ConsoleMetricView` now carries a presentation-only `trendTone`; the service
  derives `positive`, `neutral`, or `danger` from the same seven-day comparison
  that derives the formatted trend text. No-history and flat comparisons remain
  neutral.
- The metric fragment renders the tone as a Thymeleaf data attribute and CSS
  styles every allowed tone explicitly. The template does not derive display
  state.
- The recent-run table is now inside `.table-scroll`; panel containers no
  longer receive `overflow-x: auto`. Responsive table minimum width is scoped
  to that wrapper, preserving attention and quick-action panel behavior.
- No SQLite schema, API, route, dynamic-chain configuration, form ID, `data-*`
  hook, or JavaScript contract changed. No Figma evidence was added or inferred.

## Visual evidence blocker

The Figma baseline exists at
`docs/figma-baselines/pages/dashboard-83-18.png`, but no real application
capture exists at `target/visual-regression/dashboard.png`. This workspace has
no available browser screenshot harness or browser connector for a local
`visual-qa` server, so the required command below was deliberately **not** run:

```sh
python3 scripts/visual-regression.py \
  docs/figma-baselines/pages/dashboard-83-18.png \
  target/visual-regression/dashboard.png \
  --tolerance 16 --max-diff-ratio 0.02
```

No same-image substitute was used. A later QA run must start the `visual-qa`
profile, capture `/` at 1440x1024 without submitting a run, save that rendered
image under `target/visual-regression/dashboard.png`, and run the comparator.

## Consistent dashboard snapshot follow-up

The Dashboard controller now obtains one `ConsoleDashboardView` aggregate per
request. Its metrics, recent rows and attention rows are all derived from the
same `WorkflowRunRepository.listRuns()` snapshot; the existing public
section-specific view-service methods remain available for compatible callers.

RED verification:

```sh
mvn -q -Dtest=ConsoleViewServiceTest test
```

Result: non-zero as expected. `ConsoleDashboardView` and
`ConsoleViewService.dashboardView()` did not exist, so the new focused test
could not compile. That test specifies the three rendered data sections and
verifies exactly one repository snapshot query.

GREEN verification:

```sh
mvn -q -Dtest=ConsoleViewServiceTest test && \
mvn -q -Dtest=ConsoleMvcTest test && \
git diff --check
```

Result: exit 0. The focused service test verifies that the aggregate's metrics,
recent rows and failure attention are produced from its single mocked snapshot;
the MVC suite confirms the Dashboard still renders through the existing DTO and
template attributes. The existing MVC test suite emits its known Mockito
dynamic-agent warnings and starts a disposable workflow scenario under
`target/test-console`; this follow-up does not submit a final run or access a
user SQLite database.

## Alert conditional follow-up

The initial Dashboard failure alert put `th:if` and `th:replace` on the same
element. The focused regression below was added before the template fix and
failed as expected: with one queued run and no failed runs, Thymeleaf rendered
`<div class="alert c-alert danger">0 项失败运行需要处理</div>` because replacement
discarded the condition.

```sh
mvn -q -Dtest=ConsoleMvcTest#dashboardDoesNotRenderFailureAlertWhenPersistedRunsHaveNoFailures test
```

The condition now belongs to a wrapping `th:block`; the alert fragment is only
replaced inside that wrapper. A scan of the Task 2 Dashboard/fragment edits
found no other same-element `th:if` + `th:replace` pair (the metric and row
iterations already use wrapping `th:block` elements).

GREEN verification:

```sh
mvn -q -Dtest=ConsoleMvcTest#dashboardDoesNotRenderFailureAlertWhenPersistedRunsHaveNoFailures test && \
mvn -q -Dtest=ConsoleViewServiceTest,ConsoleMvcTest test && \
node --check src/main/resources/static/js/console-common.js && \
git diff --check
```

Result: exit 0. The isolated MVC test removes data only from the disposable
`target/test-console/console-mvc.sqlite` database before creating one queued
run; it does not read or mutate a user SQLite database.
