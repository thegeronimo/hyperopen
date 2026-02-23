# Gate Account Info Freshness Cues Behind the Diagnostics Toggle

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Today, the freshness cue in the right-hand header of the Account Info tabs (`Positions` and `Open Orders`) renders even when the diagnostics option `Show freshness cues` is turned off. After this change, those header cues appear only when that toggle is enabled, and are hidden when disabled. This restores expected user control and aligns account-info behavior with other surfaces (chart and orderbook) that already gate freshness cues behind the same toggle.

A user can verify the fix by opening Trade view, switching between `Positions` and `Open Orders`, toggling `Show freshness cues` in diagnostics, and observing that the `Last update ...`/`Stale ...` header cue appears only when enabled.

## Progress

- [x] (2026-02-23 14:45Z) Re-read `/hyperopen/.agents/PLANS.md` and UI policy docs (`/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, `/hyperopen/docs/agent-guides/trading-ui-policy.md`) before touching account-info UI code.
- [x] (2026-02-23 14:45Z) Located the bug path: `/hyperopen/src/hyperopen/views/account_info/vm.cljs` always computes `:freshness-cues`, and `/hyperopen/src/hyperopen/views/account_info_view.cljs` renders them for `:positions` / `:open-orders` without checking `:websocket-ui :show-surface-freshness-cues?`.
- [x] (2026-02-23 14:50Z) Implemented toggle-gated freshness cue projection in `/hyperopen/src/hyperopen/views/account_info/vm.cljs` by deriving cues only when `:show-surface-freshness-cues?` is true.
- [x] (2026-02-23 14:53Z) Added tests that prove hidden-when-off and shown-when-on behavior in `/hyperopen/test/hyperopen/views/account_info/navigation_test.cljs` and `/hyperopen/test/hyperopen/views/account_info/vm_test.cljs`.
- [x] (2026-02-23 14:58Z) Ran required validation gates (`npm run check`, `npm test`, `npm run test:websocket`) with all suites passing.
- [x] (2026-02-23 14:59Z) Moved this ExecPlan from `/hyperopen/docs/exec-plans/active/` to `/hyperopen/docs/exec-plans/completed/` after completion.

## Surprises & Discoveries

- Observation: the current bug is not a CSS visibility issue; it is data/render logic.
  Evidence: `account-info-vm` always emits a map for `:freshness-cues`, and `tab-navigation` renders cue nodes whenever a cue map exists for the selected tab.

- Observation: no rendering-layer signature changes were needed to enforce toggle behavior.
  Evidence: `tab-navigation` already calls `freshness-cue-node` with cue-map lookups; after VM returns `nil` cues when disabled, cue nodes stop rendering without changing view function arities.

## Decision Log

- Decision: gate cue generation in the account-info view model rather than only hiding cue nodes at the tab-navigation render layer.
  Rationale: this keeps behavior explicit in projection data, reduces unnecessary cue derivation work when disabled, and matches existing patterns used by chart/orderbook surfaces that gate cue creation by the same toggle.
  Date/Author: 2026-02-23 / Codex

- Decision: preserve existing `tab-navigation` call shape and rely on `freshness-cue-node` returning `nil` for absent cues.
  Rationale: this keeps API churn small and avoids broad changes to callers/tests while still enforcing toggle semantics.
  Date/Author: 2026-02-23 / Codex

## Outcomes & Retrospective

Implementation completed and validated.

The account-info VM now respects the global diagnostics freshness toggle by only computing freshness cues when `[:websocket-ui :show-surface-freshness-cues?]` is true. As a result, the right-hand header cue in `Positions` and `Open Orders` is hidden when the toggle is off and visible when the toggle is on, matching expected behavior.

Test coverage now includes explicit off/on behavior assertions at both VM and rendered panel levels. All required repository gates passed after the change:

- `npm run check`
- `npm test`
- `npm run test:websocket`

## Context and Orientation

The account-info panel is rendered by `/hyperopen/src/hyperopen/views/account_info_view.cljs`. It asks `/hyperopen/src/hyperopen/views/account_info/vm.cljs` for a view model and then uses `tab-navigation` for tab buttons and right-hand header controls. For `:positions` and `:open-orders`, that header control is a freshness cue node (`:data-role "account-tab-freshness-cue"`).

The global diagnostics toggle state lives at `[:websocket-ui :show-surface-freshness-cues?]` and defaults to `false` in `/hyperopen/src/hyperopen/state/app_defaults.cljs`. Other surfaces already respect this flag by guarding cue computation with `when`, such as `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` and `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`.

The current account-info path computes cues unconditionally and therefore displays them whenever the selected tab is `:positions` or `:open-orders`, even when the diagnostics toggle is disabled.

## Plan of Work

First, update `/hyperopen/src/hyperopen/views/account_info/vm.cljs` so `account-info-vm` reads `[:websocket-ui :show-surface-freshness-cues?]` and only computes `:freshness-cues` when true. When false, project `nil` cues for both tabs (or omit map entries) so render code receives no cue map and produces no cue node.

Second, update tests in `/hyperopen/test/hyperopen/views/account_info/navigation_test.cljs` and `/hyperopen/test/hyperopen/views/account_info/vm_test.cljs`. Add explicit coverage for:

1. Account info panel does not render `account-tab-freshness-cue` when toggle is off.
2. Account info panel renders expected cue text when toggle is on.
3. VM returns no cue map values when toggle is off and still derives cues when on.

Finally, run required repository gates and record exact pass/fail outcomes in this plan, then move the plan to completed.

## Concrete Steps

1. Edit `/hyperopen/src/hyperopen/views/account_info/vm.cljs` to gate freshness cue derivation by `:show-surface-freshness-cues?`.
2. Edit `/hyperopen/test/hyperopen/views/account_info/vm_test.cljs` to assert both off/on behaviors.
3. Edit `/hyperopen/test/hyperopen/views/account_info/navigation_test.cljs` to assert panel cue hidden when toggle is off and shown when toggle is on.
4. Run from `/Users//projects/hyperopen`:

       npm run check
       npm test
       npm run test:websocket

5. Update this plan’s living sections with implementation evidence and final outcomes.

## Validation and Acceptance

The change is accepted when all of the following are true:

1. With `[:websocket-ui :show-surface-freshness-cues?]` unset or `false`, `account-info-panel` renders no node with `:data-role "account-tab-freshness-cue"` for selected tabs `:positions` and `:open-orders`.
2. With `[:websocket-ui :show-surface-freshness-cues?] true`, `account-info-panel` renders the expected freshness cue text (for example `Last update ...` or `Stale ...`) for those tabs when websocket health data is available.
3. `/hyperopen/test/hyperopen/views/account_info/vm_test.cljs` contains explicit assertions for both toggle states.
4. `/hyperopen/test/hyperopen/views/account_info/navigation_test.cljs` contains explicit assertions for both toggle states.
5. Required gates pass:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Idempotence and Recovery

These edits are source-only and idempotent. Re-running them should not create state drift. If a test fails during intermediate edits, revert only the affected local hunk and re-run the targeted account-info tests before running full gates again.

## Artifacts and Notes

Initial bug evidence (before code changes):

- `/hyperopen/src/hyperopen/views/account_info/vm.cljs` always assigns `freshness-cues` using `ws-freshness/surface-cue` with no toggle check.
- `/hyperopen/src/hyperopen/views/account_info_view.cljs` renders `freshness-cue-node` for `:positions` and `:open-orders` based on that VM output.
- `/hyperopen/test/hyperopen/views/account_info/navigation_test.cljs` currently validates cue rendering but does not validate toggle-off hiding.

Final implementation evidence:

- Production diff:
  - `/hyperopen/src/hyperopen/views/account_info/vm.cljs` now binds `show-surface-freshness-cues?` from `[:websocket-ui :show-surface-freshness-cues?]` and wraps `freshness-cues` derivation in `when`.
- Regression tests added/updated:
  - `/hyperopen/test/hyperopen/views/account_info/vm_test.cljs`
    - Updated `account-info-vm-derives-freshness-cues-from-websocket-health-test` to enable the toggle explicitly.
    - Added `account-info-vm-hides-freshness-cues-when-toggle-disabled-test`.
  - `/hyperopen/test/hyperopen/views/account_info/navigation_test.cljs`
    - Updated cue-render tests to enable the toggle explicitly.
    - Added `account-info-panel-hides-freshness-cues-when-toggle-disabled-test` covering both `:positions` and `:open-orders`.
- Validation transcripts:
  - `npm run check`: pass (lint and compile succeeded).
  - `npm test`: pass (`Ran 1184 tests containing 5500 assertions. 0 failures, 0 errors.`).
  - `npm run test:websocket`: pass (`Ran 141 tests containing 617 assertions. 0 failures, 0 errors.`).

## Interfaces and Dependencies

No public API changes are intended.

Interfaces touched:

- `hyperopen.views.account-info.vm/account-info-vm` in `/hyperopen/src/hyperopen/views/account_info/vm.cljs`:
  continues returning `:freshness-cues`, but entries should be absent or `nil` when `:show-surface-freshness-cues?` is false.
- `hyperopen.views.account-info-view/account-info-panel` and `tab-navigation` in `/hyperopen/src/hyperopen/views/account_info_view.cljs`:
  unchanged signatures; behavior changes through VM-provided data.

Dependencies used by this behavior:

- `hyperopen.views.websocket-freshness/surface-cue` for cue text/tone derivation.
- UI diagnostics setting at state path `[:websocket-ui :show-surface-freshness-cues?]`.

## Revision Notes

- 2026-02-23 / Codex: Created initial ExecPlan with bug context, implementation path, acceptance criteria, and required validation gates.
- 2026-02-23 / Codex: Updated plan after implementation with completed progress entries, validation evidence, discoveries, and retrospective, then moved the file to completed per workflow.
