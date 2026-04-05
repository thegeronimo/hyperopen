# Reset disconnected trade surfaces after stopping Spectate Mode

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/docs/PLANS.md` and `/hyperopen/.agents/PLANS.md`.

Active `bd` issue: `hyperopen-p3u9` (`Reset disconnected trade surfaces when stopping Spectate Mode`).

## Purpose / Big Picture

Today, a disconnected user can spectate a populated account on `/trade`, stop spectating, and still see the old account’s balances, positions, open orders, and account-equity values. After this change, stopping Spectate Mode with no connected wallet must return the trade surfaces to a true no-account state, with blank account data and no stale spectated information. A user will be able to verify this by loading `/trade?spectate=0x162cc7c861ebd0c06b3d72319201150482518185`, stopping spectating, and observing that the account-info panels and equity sidebar are empty instead of showing the spectated address.

## Progress

- [x] (2026-04-04 20:10Z) Reproduced the failure mode from the user report in source analysis and traced the stop-spectate path from `/hyperopen/src/hyperopen/account/spectate_mode_actions.cljs` into the address-watcher and startup-runtime cleanup flow.
- [x] (2026-04-04 20:10Z) Identified the primary stale-data branches that survive the nil-address transition: `:webdata2`, `:orders :open-orders`, `:orders :open-orders-snapshot`, `:orders :fills`, and other account-derived order/TWAP branches.
- [x] (2026-04-04 20:10Z) Identified the current automated-coverage gap: the existing startup-runtime clear-branch test asserts only a partial reset and does not protect `:webdata2` or the full disconnected trade-surface state.
- [x] (2026-04-04 20:36Z) Implemented `reset-account-surface-state` in `/hyperopen/src/hyperopen/startup/runtime.cljs` and wired it into both `bootstrap-account-data!` and the nil-address branch in `install-address-handlers!`, so the cleared account shape is shared instead of hand-maintained in two places.
- [x] (2026-04-04 20:36Z) Expanded deterministic cljs coverage in `/hyperopen/test/hyperopen/startup/runtime_test.cljs`, `/hyperopen/test/hyperopen/views/account_info_view_test.cljs`, and `/hyperopen/test/hyperopen/views/account_equity_view_test.cljs` to prove the disconnected cleared state renders blank account surfaces.
- [x] (2026-04-04 20:36Z) Added committed Playwright regression coverage in `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` for the disconnected stop-spectate flow using deterministic seeded stale state after Spectate Mode activation.
- [x] (2026-04-04 20:36Z) Ran the required validations: targeted Playwright regression, `npm test`, `npm run test:websocket`, and `npm run check`.

## Surprises & Discoveries

- Observation: the codebase already has a nil-address cleanup branch in `/hyperopen/src/hyperopen/startup/runtime.cljs`, so this is not a missing hook; it is an incomplete reset.
  Evidence: `install-address-handlers!` clears `:spot :clearinghouse-state`, `:perp-dex-clearinghouse`, portfolio fields, and order-history state, but leaves `:webdata2` and several other account-derived branches untouched.

- Observation: the stale UI is mostly explained by surviving `:webdata2` and open-order snapshot state, not by Spectate Mode remaining active.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/vm.cljs` always derives balances, positions, open orders, fills, and history rows directly from `:webdata2` and `:orders`, even when `effective-account-address` is `nil`.

- Observation: the disconnected-only symptom is a direct consequence of the connected path immediately overwriting stale state with fresh data for the owner address.
  Evidence: when the effective address changes to a real wallet address, `/hyperopen/src/hyperopen/startup/runtime.cljs` re-runs `bootstrap-account-data!`; when it changes to `nil`, the app depends entirely on the clear branch.

- Observation: the first shared-helper implementation still missed `:orders :order-history` on the bootstrap path, even though the nil-address branch remained correct.
  Evidence: the first `npm test` run failed `bootstrap-account-data-covers-nil-repeat-success-and-error-branches-test` in `/hyperopen/test/hyperopen/startup/runtime_test.cljs` until the helper also cleared `:orders :order-history`.

- Observation: the repo-wide `check` gate also required touching the existing namespace-size exception registry because this fix extended already-excepted oversized owners.
  Evidence: `npm run check` failed `lint:namespace-sizes` for `/hyperopen/src/hyperopen/startup/runtime.cljs`, `/hyperopen/test/hyperopen/startup/runtime_test.cljs`, and `/hyperopen/test/hyperopen/views/account_equity_view_test.cljs` until `/hyperopen/dev/namespace_size_exceptions.edn` was refreshed.

## Decision Log

- Decision: treat the root cause as asymmetric account-surface lifecycle management rather than a Spectate Mode routing bug.
  Rationale: `stop-spectate-mode` correctly clears Spectate Mode state and removes the `spectate` URL parameter; the remaining bug is that downstream account data survives after the effective address becomes `nil`.
  Date/Author: 2026-04-04 / Codex

- Decision: fix this with a shared reset helper instead of patching each stale branch inline in one more place.
  Rationale: `/hyperopen/src/hyperopen/startup/runtime.cljs` already has two hand-maintained reset sequences (`bootstrap-account-data!` and the nil-address clear branch). The bug exists because those lists drifted. A single helper reduces future omission risk.
  Date/Author: 2026-04-04 / Codex

- Decision: add both state-level and browser-level regression coverage.
  Rationale: the current gap is partly structural (missing reset fields) and partly user-visible (trade surfaces still render old data). One test layer alone will not prove both.
  Date/Author: 2026-04-04 / Codex

- Decision: keep the Playwright regression deterministic by starting Spectate Mode locally and seeding stale account data through the existing debug/store seam instead of depending on live remote account contents.
  Rationale: the user-provided address was useful for diagnosis, but CI-safe regression coverage should not depend on a real account continuing to hold positions or open orders in the future.
  Date/Author: 2026-04-04 / Codex

## Outcomes & Retrospective

Implementation landed. The outcome is lower lifecycle complexity: one canonical account-surface reset path in `/hyperopen/src/hyperopen/startup/runtime.cljs`, deterministic blank rendering when the effective account becomes `nil`, and committed regression coverage for the disconnected spectate-stop flow across cljs tests and Playwright. The follow-up cost was limited to refreshing existing namespace-size exception ceilings for the same oversized owners touched by the change.

## Context and Orientation

The stop-spectate action lives in `/hyperopen/src/hyperopen/account/spectate_mode_actions.cljs`. It only clears `:account-context` and replaces the browser route. The effective account identity is derived in `/hyperopen/src/hyperopen/account/context.cljs` by `effective-account-address`, which returns the trader-route address, then the active spectate address, then the connected owner wallet address.

Address transitions are observed by `/hyperopen/src/hyperopen/wallet/address_watcher.cljs`. Startup wiring registers an address-change handler in `/hyperopen/src/hyperopen/startup/runtime.cljs`. That handler is responsible for either bootstrapping account data when a new address exists or clearing account surfaces when the effective address becomes `nil`.

The trade account UI does not compute emptiness from the account identity alone. `/hyperopen/src/hyperopen/views/account_info/vm.cljs` and `/hyperopen/src/hyperopen/views/account_equity_view.cljs` derive visible balances, positions, open orders, fills, history, and equity metrics from state branches such as `:webdata2`, `:spot`, `:orders`, and `:perp-dex-clearinghouse`. If those branches keep the old spectated data, the UI keeps rendering it even though Spectate Mode is no longer active.

The current regression gap is in `/hyperopen/test/hyperopen/startup/runtime_test.cljs`. The existing clear-branch test proves only a partial reset. It does not assert that `:webdata2`, `:orders :open-orders`, `:orders :open-orders-snapshot`, `:orders :fills`, `:orders :twap-states`, `:orders :twap-history`, or `:orders :twap-slice-fills` are cleared. View tests under `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` and `/hyperopen/test/hyperopen/views/account_equity_view_test.cljs` exercise populated and read-only states, but not the transition from stale disconnected data to a no-account rendering.

## Plan of Work

First, extract a single helper in `/hyperopen/src/hyperopen/startup/runtime.cljs` or a nearby lifecycle-focused namespace that resets every account-derived state branch used by the trade/account surfaces. That helper must cover at least `:webdata2`, `:spot :clearinghouse-state`, `:perp-dex-clearinghouse`, `:account`, `:orders :open-orders-hydrated?`, `:orders :open-orders`, `:orders :open-orders-snapshot`, `:orders :open-orders-snapshot-by-dex`, `:orders :fills`, `:orders :fundings-raw`, `:orders :fundings`, `:orders :order-history`, `:orders :twap-states`, `:orders :twap-history`, `:orders :twap-slice-fills`, and the related portfolio/user-fees branches that are already being reset. Use the same helper from both the “new address bootstrapping” path and the “nil address” path so the baseline shape is explicit and shared.

Next, tighten the consumers. After the shared reset lands, verify that `/hyperopen/src/hyperopen/views/account_info/vm.cljs` and `/hyperopen/src/hyperopen/views/account_equity_view.cljs` render blank/empty account surfaces from the cleared shape without needing special-case UI logic. If a residual stale path still leaks through because the rendering code falls back to an unexpected source, correct that source selection rather than adding ad hoc disconnected conditionals in the view.

Then, add tests. Expand `/hyperopen/test/hyperopen/startup/runtime_test.cljs` so the nil-address clear branch explicitly proves the stale branches are emptied. Add a view-model or view test in `/hyperopen/test/hyperopen/views/account_info_view_test.cljs`, `/hyperopen/test/hyperopen/views/account_info/vm_test.cljs`, or a new focused test file that starts from a disconnected state containing stale `:webdata2` and `:orders` data, simulates the cleared state, and proves the rendered output no longer includes balances, positions, open orders, or other account-specific rows. Add a matching account-equity test under `/hyperopen/test/hyperopen/views/account_equity_view_test.cljs` so the right sidebar does not keep stale equity after the reset.

Finally, add stable browser coverage under `/hyperopen/tools/playwright/**` for the user-visible flow: load the spectated trade route in a disconnected browser, confirm populated account surfaces appear, stop spectating, and assert the account surfaces become empty/blank. Keep the assertion scope narrow to deterministic anchors already used in the repo, and run the smallest relevant Playwright command before broader gates.

## Concrete Steps

Work from `/hyperopen`.

1. Update the runtime clear path and shared helper.
2. Add targeted cljs tests for the nil-address reset and blank rendering.
3. Add targeted Playwright coverage for the disconnected stop-spectate flow.
4. Run:

   `npm test`

   `npm run test:websocket`

   `npx playwright test <new-or-updated-spectate-reset-spec>`

   `npm run check`

If the new Playwright file is added under `/hyperopen/tools/playwright/`, replace `<new-or-updated-spectate-reset-spec>` with its exact path so the first validation pass stays narrow.

## Validation and Acceptance

Acceptance requires both state-level and user-visible proof.

At the state level, the startup-runtime clear-branch test must fail before the fix and pass after it by asserting that stopping Spectate Mode into a disconnected state clears every trade-surface branch that can carry account data.

At the rendering level, the updated account-info and account-equity tests must prove that a cleared disconnected state shows no stale balances, positions, open orders, or equity values.

At the browser level, Playwright must demonstrate the exact user flow on `/trade`: a disconnected session spectates `0x162cc7c861ebd0c06b3d72319201150482518185`, the trade surfaces populate, `Stop Spectate Mode` is activated, and the account surfaces become empty/blank while the route no longer includes `spectate=`.

Validation result: satisfied on 2026-04-04. The committed Playwright regression now starts Spectate Mode, seeds stale account state deterministically, stops spectating, and asserts that the account-equity panel plus balances/positions/open-orders tabs all return to blank disconnected output.

## Idempotence and Recovery

The reset helper must be safe to call repeatedly. Re-entering Spectate Mode or connecting a wallet after the cleared state should simply bootstrap fresh data again. If a partial implementation leaves the app blank even when a real wallet address is active, retry by verifying that the bootstrap path repopulates the same branches the reset helper initializes.

Because this change touches only deterministic in-memory state transitions and committed tests, there is no destructive migration. The browser-cleanup step after any exploratory Browser MCP work remains `npm run browser:cleanup`.

## Artifacts and Notes

Key evidence from the investigation:

`/hyperopen/src/hyperopen/account/spectate_mode_actions.cljs`

  `stop-spectate-mode` clears only `:account-context` and updates the route.

`/hyperopen/src/hyperopen/startup/runtime.cljs`

  `install-address-handlers!` has a nil-address clear branch, but it does not reset `:webdata2` or the full `:orders` account surface.

`/hyperopen/src/hyperopen/views/account_info/vm.cljs`

  The account-info VM always reads balances, positions, open orders, fills, and history from `:webdata2` and `:orders`, so stale state remains user-visible after Spectate Mode ends.

`/hyperopen/test/hyperopen/startup/runtime_test.cljs`

  The existing clear-branch test passes today while still allowing the bug, which proves the regression hole is in the asserted reset shape.

## Interfaces and Dependencies

The fix should preserve the public action surface. `:actions/stop-spectate-mode` should keep dispatching the same effect sequence from `/hyperopen/src/hyperopen/account/spectate_mode_actions.cljs`. The implementation work belongs in the runtime/state lifecycle behind that action, not in a changed public API.

The central helper should accept and return the app state map, following the existing `swap! store` projection style. The new or refactored helper must be called from the startup-runtime address-change handling path and from any bootstrap path that needs the same clean baseline before refetching account surfaces.

Plan update note: created on 2026-04-04 after diagnosis of the disconnected Spectate Mode reset bug so implementation can proceed under a tracked `bd` issue with explicit acceptance criteria.

Validation notes:

- `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "disconnected stop spectate clears stale account surfaces"` passed on 2026-04-04.
- `npm test` passed on 2026-04-04.
- `npm run test:websocket` passed on 2026-04-04.
- `npm run check` passed on 2026-04-04 after refreshing the existing namespace-size exceptions for the touched oversized owners.
