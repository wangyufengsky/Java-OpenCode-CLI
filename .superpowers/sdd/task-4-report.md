# Task 4 — History pagination and Figma composition report

## Outcome

`GET /history` now supports an optional `page` parameter with a fixed page
size of 20. Filtering remains SQL-backed: the repository builds one ordered
predicate/argument list used by the original unbounded query, `countRuns`, and
the new `listRuns(filter, limit, offset)`. The History page uses a
`ConsolePage<ConsoleRunListItemView>` and a single URL builder for pagination,
filtered detail/copy links, and the intentionally empty clear-filter URL.

The rendered composition now has the Figma-aligned filter toolbar, result
summary, no-results state, pagination controls, and a table-local horizontal
scroll container. No database schema, run/schedule API, dynamic-chain contract
or final-submit behaviour changed.

## RED evidence

Before production changes, I added repository, MVC, and Node coverage, then
ran:

```sh
mvn -q -Dtest=WorkflowRunRepositoryTest,ConsoleMvcTest test
node --test src/test/js/history.test.js
```

The Maven test compilation failed as expected because
`WorkflowRunRepository.countRuns(WorkflowRunFilter)` and
`listRuns(WorkflowRunFilter, int, int)` did not exist. The Node test also
failed as expected because changing a filter left the hidden `page` value at
`3` instead of restarting at `1`.

## GREEN evidence

```sh
mvn -q -Dtest=WorkflowRunRepositoryTest,ConsoleMvcTest test
node --test src/test/js/history.test.js
node --check src/main/resources/static/js/history.js
git diff --check
```

All commands exited 0. The repository test creates 41 matching failed runs and
proves the SQL count and 20/20/1 paged slices after combined query/state/chain
arguments. MVC coverage proves a negative page normalizes to one, an excessive
page clamps to page 3, all active filters remain in previous and copy URLs,
and an empty result remains page 1/1. Node coverage proves date bounds are
retained and a changed filter resets the submitted page to one.

## Compatibility assessment

- `listRuns()` and `listRuns(WorkflowRunFilter)` are retained with their
  original ordering and result semantics.
- The filter predicate order and values remain parameter-bound; `limit` and
  `offset` are also SQL placeholders, appended after the filter values.
- Existing `/history` calls without `page` still produce page one. Negative
  pages normalize to one; past-last pages clamp to the final page; empty
  result sets use page/totalPages one.
- Detail and copy links retain all active filter values and the resolved page;
  no existing route or API request/response shape was changed.
- No React, Tailwind, Code Connect, external icon library, SQLite schema,
  dynamic-chain configuration, or user database access was introduced.

## Visual evidence status

The checked-in Figma History baseline is
`docs/figma-baselines/pages/history-104-2.png`. This workspace has no browser
screenshot harness connected to a running `visual-qa` server (and no local
Playwright/Selenium dependency), so no rendered 1440×1024 image exists at
`target/visual-regression/history.png`. The Pillow comparator was deliberately
not run and no same-image substitute was used. A later visual-QA pass must
start the isolated profile, capture `/history` at 1440×1024 into that target
path without submitting a run, then run:

```sh
python3 scripts/visual-regression.py \
  docs/figma-baselines/pages/history-104-2.png \
  target/visual-regression/history.png \
  --tolerance 16 --max-diff-ratio 0.02
```
