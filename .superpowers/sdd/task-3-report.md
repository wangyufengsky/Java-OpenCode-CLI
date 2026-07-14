# Task 3 — New Run and Run Detail visual slices report

## Outcome

New Run now uses a three-step Figma-oriented composition, grouped workflow and
configuration sections, a sticky run summary, a shared validation alert and a
live path-preflight alert. Run Detail now presents the existing five derived
stages as a shared progress indicator, uses metric/status surfaces, and exposes
the existing SSE connection state as a semantic visual state without changing
snapshot ordering.

## RED evidence

The first focused command in the task brief named `test/js/*.test.js`, but this
repository stores Node tests under `src/test/js/`; Node reported that the former
paths did not exist. The equivalent real test paths were then used.

```sh
node --test src/test/js/run-form.test.js
node --test src/test/js/run-detail.test.js
```

Both commands failed as intended before implementation:

- the failed-submit test expected `#run-form` to become `aria-busy="true"`,
  but the attribute was absent;
- the reconnect test expected `#stream-state[data-connection-state]` to become
  `reconnecting`, but it was absent.

The composition MVC test also failed before presentation implementation because
the New Run page did not render `.c-step-navigation`. A follow-up RED assertion
for `#preflight-alert` failed before the alert was added.

## GREEN evidence

```sh
mvn -q -Dtest=ConsoleMvcTest test
node --test src/test/js/run-form.test.js
node --test src/test/js/run-detail.test.js
node --check src/main/resources/static/js/run-form.js
node --check src/main/resources/static/js/run-detail.js
git diff --check
```

All commands exited 0. The MVC suite verifies the retained IDs together with
the three-step navigation, preflight and field-error alerts, sticky summary,
five-stage progress indicator, connection-state hook, event list, task rows and
safe rerun link. Node tests verify failed submission restores the enabled
button/`aria-busy=false`; reconnect state is exposed while the event IDs retain
their append order; pre-existing snapshot/rerun/AbortController safeguards also
remain green.

The existing full MVC suite emits its known Mockito dynamic-agent warnings and
executes a disposable `target/test-console` runner scenario. This task did not
open a browser, submit a final run, or access a user SQLite file.

## Changed files and compatibility

- `run-new.html`, `run-detail.html`, and `styles.css`: presentation composition
  only, built from the existing shared classes and Figma foundation tokens.
- `run-form.js`: adds form `aria-busy` around the unchanged duplicate-safe
  submit flow, and mirrors existing path-preflight status into the shared alert.
- `run-detail.js`: maps the existing stream text to a connection-state data
  attribute only; it does not alter `seenIds`, `lastEventId`, event append order,
  polling generation, or failed-task rerun construction.
- `ConsoleMvcTest`, `src/test/js/run-form.test.js`, and
  `src/test/js/run-detail.test.js`: cover the visual contracts and retained
  behavior.

No SQLite schema, route, API request/response shape, dynamic chain definition,
form ID, existing `data-*` selector, copy configuration behavior, path
preflight endpoint, duplicate prevention, or AbortController behavior changed.
No React, Tailwind, Code Connect, external icon library, or real final-submit
verification was added.

## Visual source status

The checked-in Figma page baselines for New Run (`92:2`) and Run Detail (`98:2`)
remain the visual source. This workspace still has no browser screenshot
harness connected to a running `visual-qa` profile, so no rendered 1440×1024
capture or Pillow comparison was claimed. In particular, no baseline was
compared with itself. A later visual-QA pass must capture `/runs/new` and a
fixed `/runs/{id}` under `visual-qa`, save those images beneath
`target/visual-regression/`, then run the documented comparator.
