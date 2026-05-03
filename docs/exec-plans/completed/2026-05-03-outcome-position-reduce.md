# Outcome Position Reduce

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan follows `/hyperopen/.agents/PLANS.md`. It is tracked by `bd` issue `hyperopen-agty`.

## Purpose / Big Picture

Outcome-market holdings currently appear in the account Outcomes tab, but a trader cannot directly reduce an existing Yes or No holding from that table. Hyperliquid represents each outcome side as a spot-like token, so reducing an outcome holding means selling some or all of the held side token before expiration. After this change, each outcome row exposes the same Reduce affordance and close-position popover used by perps positions, adapted so an outcome close submits a sell order for the held outcome side coin such as `#10` instead of buying the opposite outcome side.

The user-visible proof is simple: with an Outcomes row for `17 Yes`, clicking `Reduce` opens the close-position popover, choosing 50% and submitting emits an exchange order for the Yes side asset id with `b` set to `false`, size `8.5`, and no accidental No-side buy.

## Progress

- [x] (2026-05-03T10:39:46Z) Inspected the existing perps Reduce flow, account Outcomes tab, outcome projection, and order request builders.
- [x] (2026-05-03T10:39:46Z) Confirmed the protocol model: outcome sides are spot-like assets with `#<encoding>` coins and asset ids `100000000 + encoding`, so reducing a side holding is a sell order for that same side token.
- [x] (2026-05-03T10:44:00Z) Added tests for opening an outcome reduce popover, rendering Reduce in the Outcomes tab, read-only suppression, and submitting an outcome sell order.
- [x] (2026-05-03T10:47:00Z) Implemented the outcome reduce row adapter, Outcomes tab action column, mobile action, and outcome side market resolution.
- [x] (2026-05-03T10:52:14Z) Ran focused/full tests, required repo gates, websocket tests, governed browser QA, and browser cleanup.
- [x] (2026-05-03T10:52:14Z) Recorded validation evidence and moved this plan to completed.

## Surprises & Discoveries

- Observation: The account Outcomes tab was added in `/hyperopen/docs/exec-plans/completed/2026-05-03-account-outcomes-tab.md` and intentionally did not include action columns because the first slice only made outcome holdings visible.
  Evidence: that completed plan says “There are no action columns” in the original Plan of Work, which this plan now extends.

- Observation: The existing perps Reduce popover is mostly reusable, but its current data model assumes signed perps positions. In particular, a perps short reduces by buying, while an outcome `No` holding must reduce by selling the held `No` side token.
  Evidence: `/hyperopen/src/hyperopen/account/history/position_reduce.cljs` computes `reduce-order-side` from `:position-side`, and `/hyperopen/src/hyperopen/views/account_info/projections/outcomes.cljs` models outcome rows as positive spot balances with `:side-coin`, `:side-name`, and `:side-index`.

- Observation: The full test runner ignores `--focus`, so an attempted focused run executed the full suite instead.
  Evidence: `npm test -- --focus hyperopen.account.history.actions-test/position-reduce-opens-and-submits-outcome-side-sell-order-test` printed `Unknown arg: --focus` and still passed the full suite with 3,734 tests and 20,641 assertions.

- Observation: Adding the new action coverage directly to `actions_test.cljs` exceeded the namespace-size exception.
  Evidence: `npm run check` failed with `test/hyperopen/account/history/actions_test.cljs - namespace has 742 lines; exception allows at most 686`, so the coverage was moved into `test/hyperopen/account/history/position_reduce_test.cljs`.

## Decision Log

- Decision: Reuse the existing position reduce action and popover instead of adding a separate outcome-specific modal.
  Rationale: The requested interaction is the same interface. Reusing the existing popover preserves sizing controls, Market/Limit selection, confirmation settings, localized numeric parsing, and mobile sheet behavior.
  Date/Author: 2026-05-03 / Codex

- Decision: Represent outcome reduction as a sell order for the held side token, never as a buy order for the opposite side.
  Rationale: Hyperliquid outcome sides are separate spot-like assets. Buying `No` increases No exposure; it does not reduce an existing Yes token balance. Selling `#10` is the direct way to reduce a `#10` Yes holding.
  Date/Author: 2026-05-03 / Codex

- Decision: Keep TP/SL, margin, liquidation, and funding controls out of the Outcomes tab.
  Rationale: Outcome holdings are fully collateralized side-token balances, not perps positions. Only the reduce/sell action maps cleanly to the existing close-position workflow.
  Date/Author: 2026-05-03 / Codex

## Outcomes & Retrospective

Implemented. Outcomes rows now expose Reduce in the account Outcomes tab when the surface is writable. Clicking Reduce opens the existing close-position popover using the outcome side label and size. Submitting an outcome reduce order resolves the held side token from the outcome row and market metadata, then submits a sell order for that side asset id. The perps Reduce behavior remains on the existing path.

The governed browser design QA passed. The residual browser-QA blind spots are the tool's standard state-sampling caveat for hover, active, disabled, and loading states on `/trade` across the inspected viewports; no issues were reported.

## Context and Orientation

The account table UI lives under `/hyperopen/src/hyperopen/views/account_info*`. `/hyperopen/src/hyperopen/views/account_info/tabs/positions/desktop.cljs` and `/hyperopen/src/hyperopen/views/account_info/tabs/positions/mobile.cljs` render perps position rows and already wire a `Reduce` button to `:actions/open-position-reduce-popover`. The popover UI itself lives in `/hyperopen/src/hyperopen/views/account_info/position_reduce_popover.cljs`, and the state and submit logic live in `/hyperopen/src/hyperopen/account/history/position_reduce.cljs` plus `/hyperopen/src/hyperopen/account/history/position_overlay_actions.cljs`.

The Outcomes tab lives in `/hyperopen/src/hyperopen/views/account_info/tabs/outcomes.cljs`. Its rows come from `/hyperopen/src/hyperopen/views/account_info/projections/outcomes.cljs`, where `build-outcome-rows` turns spot clearinghouse balances such as `+10` into row maps with fields including `:side-coin "#10"`, `:side-name "Yes"`, `:side-index 0`, `:market-key`, `:size`, `:mark-price`, `:entry-price`, and `:quote`.

Outcome market metadata is normalized in `/hyperopen/src/hyperopen/asset_selector/markets.cljs`. Each outcome market has `:market-type :outcome`, a market key like `outcome:1`, a primary Yes `:asset-id`, and an `:outcome-sides` vector. Each side has its own `:coin` such as `#10`, `:asset-id` such as `100000010`, `:side-index`, `:side-name`, and mark data. The order request builder in `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs` already accepts `:market-type :outcome` when a caller provides the correct side asset id.

## Plan of Work

First, add tests before production changes. Extend `test/hyperopen/account/history/actions_test.cljs` with an outcome-row case that opens the reduce popover from an outcome row and submits a limit close. The expected submitted order must target the held side asset id, sell the held side (`:b false`), size by the chosen percentage, and keep the side label in the popover copy. Extend `test/hyperopen/views/account_info/tabs/outcomes_test.cljs` so desktop and mobile outcome rows render a `Reduce` button when not read-only, wire it to `:actions/open-position-reduce-popover`, and render the active popover for the matching row. Also assert read-only mode suppresses the button.

Second, extend `/hyperopen/src/hyperopen/account/history/position_reduce.cljs`. Add a row adapter for outcome rows that opens the existing popover with `:market-type :outcome`, `:coin` set to the held side coin, `:market-key` set from the row, `:outcome-side-index`, `:outcome-side-name`, `:position-side :outcome`, `:position-side-label` set to `Yes` or `No`, positive `:position-size`, and `:mid-price` from the row mark. Adjust `position-side-label`, `reduce-order-side`, market matching, asset id resolution, and submit form construction so outcome rows always sell the held side token and use the matching outcome side asset id.

Third, extend `/hyperopen/src/hyperopen/views/account_info/tabs/outcomes.cljs`. Add an Actions/Reduce column to the desktop grid and an inline Reduce action on mobile cards. Pass `:reduce-popover` and `:read-only?` through `outcomes-tab-content`. Reuse `/hyperopen/src/hyperopen/views/account_info/position_reduce_popover.cljs` for the active row so desktop behavior mirrors positions and mobile uses the existing bottom sheet when the anchor width is mobile.

Fourth, update `/hyperopen/src/hyperopen/views/account_info_view.cljs` so the Outcomes tab renderer passes `:position-reduce-popover` and `:read-only?` from the view model. No new action registration should be required because the existing `open-position-reduce-popover`, field setters, close handler, and submit action are already registered.

Finally, run focused tests and then the repository validation gates. Because this is UI-facing work, run governed browser QA on the trade route and cleanup any browser-inspection sessions before signoff.

## Concrete Steps

Run commands from `/Users/barry/.codex/worktrees/8320/hyperopen`.

1. Add RED tests:

   `npm test -- --focus hyperopen.account.history.actions-test/position-reduce-opens-and-submits-outcome-side-sell-order-test`

   Expected before implementation: the new test fails because the existing reduce adapter does not understand outcome rows or cannot resolve the outcome side asset id.

   `npm test -- --focus hyperopen.views.account-info.tabs.outcomes-test/outcomes-tab-renders-reduce-action-and-active-popover-test`

   Expected before implementation: the new test fails because outcome rows have no Reduce action.

2. Implement the minimal code in the files named in Plan of Work.

3. Re-run focused tests:

   `npm test -- --focus hyperopen.account.history.actions-test`

   `npm test -- --focus hyperopen.views.account-info.tabs.outcomes-test`

   Expected after implementation: both focused suites pass.

4. Run required validation:

   `npm run check`

   `npm test`

   `npm run test:websocket`

5. Run UI QA and cleanup:

   `npm run qa:design-ui -- --targets trade-route --manage-local-app`

   `npm run browser:cleanup`

## Validation and Acceptance

Acceptance is met when an outcome row with `:side-coin "#10"`, `:side-name "Yes"`, `:size 17`, and matching market metadata can open the existing Close Position popover from a row-level `Reduce` button. With the popover set to limit close at `0.59` and 50%, submitting emits an order for the `#10` asset id, size `8.5`, side sell (`:b false`), and does not select or buy the `No` side. A `No` holding must behave the same way: reduce sells `#11`, not buys `#10`.

The Outcomes tab must remain dense and table-like. Desktop columns should be Outcome, Size, Position Value, Entry Price, Mark Price, PNL (ROE %), and Actions. Mobile cards should show Reduce as an action only when the account surface is not read-only. In spectate/read-only mode, no outcome reduce action should be visible.

## Idempotence and Recovery

All edits are local source, tests, and this plan file. Re-running tests and browser QA is safe. If the outcome reduce submit path cannot resolve the side asset id, it should fail closed with the existing popover error message instead of submitting an ambiguous order. If a UI regression appears, the Outcomes tab action column can be temporarily disabled without affecting existing perps Reduce behavior because the perps path remains covered by existing tests.

## Artifacts and Notes

Validation completed on 2026-05-03:

- `npm run check`: passed, including namespace sizes, namespace boundaries, release assets, styles, dev-server cleanup, and Shadow compiles for app, portfolio, workers, and test with zero warnings.
- `npm test`: passed, 3,734 tests and 20,641 assertions.
- `npm run test:websocket`: passed, 523 tests and 3,041 assertions.
- `npm run qa:design-ui -- --targets trade-route --manage-local-app`: passed with run id `design-review-2026-05-03T10-51-39-966Z-c53fc424`.
- `npm run browser:cleanup`: passed with `stopped: []`.

The implementation restored missing local `node_modules` via `npm install`; no tracked dependency files changed.

## Interfaces and Dependencies

No new dependency is required. The final implementation should preserve the existing action interface:

    [:actions/open-position-reduce-popover row-data :event.currentTarget/bounds]
    [:actions/set-position-reduce-popover-field [:size-percent-input] value]
    [:actions/set-position-reduce-popover-field [:close-type] value]
    [:actions/submit-position-reduce-close]

The position reduce popover state may gain outcome-specific fields, but existing perps callers must continue to work:

    :market-type :outcome
    :market-key "outcome:1"
    :outcome-side-index 0
    :outcome-side-name "Yes"
    :position-side :outcome
    :position-side-label "Yes"

Revision note: 2026-05-03T10:39:46Z - Created the initial plan after inspecting the perps reduce flow, outcome projection, and order builder.
