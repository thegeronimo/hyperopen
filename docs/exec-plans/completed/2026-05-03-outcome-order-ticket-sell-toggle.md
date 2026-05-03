# Add Sell Toggle To Outcome Order Ticket

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and is linked to live `bd` issue `hyperopen-43ci`.

## Purpose / Big Picture

Outcome markets let a user choose a Yes or No contract, but the current ticket does not expose a separate Buy or Sell choice the way Hyperliquid does. After this change, a user looking at an outcome market can choose `Buy` or `Sell` in a compact tab row, then choose `Yes` or `No` in the existing two-button row. The submitted order must use the selected Buy/Sell side while the selected Yes/No row continues to resolve the correct outcome asset id.

## Progress

- [x] (2026-05-03 11:39Z) Created and claimed `bd` issue `hyperopen-43ci`.
- [x] (2026-05-03 11:40Z) Inspected the order form view, view model, controls, transitions, and order request path.
- [x] (2026-05-03 11:42Z) Wrote failing tests for the missing Buy/Sell outcome-market side toggle and sell request side. `npm test` produced 3 expected failures in `outcome-market-renders-independent-buy-sell-side-tabs-test`.
- [x] (2026-05-03 11:43Z) Implemented the smallest UI change by rendering the existing `side-row` for outcome markets with compact `Buy` and `Sell` labels.
- [x] (2026-05-03 11:45Z) Ran `npm test`: 3732 tests, 20630 assertions, 0 failures.
- [x] (2026-05-03 11:49Z) Ran `npm run check`: completed successfully, including app/portfolio/worker/test compilation.
- [x] (2026-05-03 11:50Z) Ran `npm run test:websocket`: 523 tests, 3041 assertions, 0 failures.
- [x] (2026-05-03 11:51Z) Ran `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --reporter=line`: 32 passed, 2 unrelated failures recorded below.
- [x] (2026-05-03 11:52Z) Ran `npm run qa:design-ui -- --targets trade-route --manage-local-app`: PASS for all six browser-QA passes at widths 375, 768, 1280, and 1440.
- [x] (2026-05-03 11:52Z) Ran `npm run browser:cleanup`: ok, no sessions needed stopping.

## Surprises & Discoveries

- Observation: Outcome markets already resolve the selected Yes/No side into a side-specific asset id before order request construction.
  Evidence: `/hyperopen/src/hyperopen/state/trading.cljs` uses `selected-outcome-side` and `outcome-side-market` inside `trading-context`, and `test/hyperopen/state/trading/order_request_test.cljs` already asserts Yes resolves to asset id `100000000` and No resolves to `100000001`.
- Observation: The current outcome ticket hides the normal `side-row` and only renders `outcome-side-row`.
  Evidence: `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` renders `controls/side-row` only when `outcome?` is false, then renders `controls/outcome-side-row` when `outcome?` is true.

## Decision Log

- Decision: Add a separate Buy/Sell row only for outcome markets, above the existing Yes/No outcome-side selector.
  Rationale: Buy/Sell is the order direction submitted to Hyperliquid (`:b true` for buy, `:b false` for sell), while Yes/No selects the outcome token asset id. Keeping the two controls separate prevents users from confusing trade direction with contract selection.
  Date/Author: 2026-05-03 / Codex
- Decision: Reuse the existing `controls/side-row` and `primitives/side-button` styling instead of creating a new component family.
  Rationale: The normal side row already has the right semantic buttons, keyboard behavior from native buttons, and Hyperliquid-style active buy/sell colors.
  Date/Author: 2026-05-03 / Codex

## Outcomes & Retrospective

Implemented. Outcome-market tickets now render a compact `Buy`/`Sell` side tab row above the `Yes`/`No` outcome selector. The Yes/No button labels follow the selected side (`Buy Yes`, `Buy No`, `Sell Yes`, `Sell No`), and the submit path is covered by a request test proving sell No submits asset id `100000001` with wire buy flag `false`.

This change slightly increases the visible controls on outcome tickets, but reduces behavioral ambiguity because order direction and outcome-token selection are now explicit independent choices.

## Context and Orientation

The order ticket UI lives in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`. It gets display state from `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs` and event handlers from `/hyperopen/src/hyperopen/views/trade/order_form_handlers.cljs`. The reusable controls are in `/hyperopen/src/hyperopen/views/trade/order_form_controls.cljs`.

An outcome market is a market where the user trades one of multiple outcome tokens, usually displayed as `Yes` and `No`. The selected outcome token is represented by `:outcome-side` in the order form. The submitted Buy/Sell direction is represented separately by `:side`, where `:buy` becomes wire value `:b true` and `:sell` becomes wire value `:b false` in `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`.

## Plan of Work

First, add view tests showing the current missing behavior: an outcome-market ticket should show top-level `Buy` and `Sell` side buttons, clicking `Sell` should dispatch `[:actions/update-order-form [:side] :sell]`, and the Yes/No row labels should update from `Buy Yes`/`Buy No` to `Sell Yes`/`Sell No` when `:side` is `:sell`.

Second, add an order request assertion showing an outcome-market form with `:side :sell` and `:outcome-side 1` submits asset id `100000001` with wire buy flag `false`.

Third, update `order_form_view.cljs` to render `controls/side-row` for outcome markets with compact labels `Buy` and `Sell`, then keep `controls/outcome-side-row` below it with the side-aware action prefix. No new action is needed because `side-row` already dispatches `:actions/update-order-form` through `set-order-side`.

## Concrete Steps

Run commands from `/Users/barry/.codex/worktrees/9f3a/hyperopen`.

1. Add failing tests in `test/hyperopen/views/trade/order_form_vm_test.cljs`, `test/hyperopen/views/trade/order_form_view/entry_mode_test.cljs`, and `test/hyperopen/state/trading/order_request_test.cljs`.
2. Run `npm test` and confirm the new tests fail because `Buy`/`Sell` buttons are absent from outcome markets.
3. Patch `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` to render the outcome Buy/Sell row.
4. Re-run `npm test`, then required gates: `npm run check`, `npm run test:websocket`.
5. Because this touches UI, account for browser QA from `/hyperopen/docs/FRONTEND.md`, including the six browser-QA passes and widths 375, 768, 1280, and 1440. Use the smallest deterministic Playwright command first for the stable trade route.

## Validation and Acceptance

Acceptance is met when an outcome-market ticket presents separate Buy/Sell and Yes/No controls. In Buy mode, the outcome buttons read `Buy Yes` and `Buy No`; in Sell mode, they read `Sell Yes` and `Sell No`. Submitting a sell order for the No side must build an order request with asset id `100000001` and `:b false`.

The red-green evidence must include a failing test before implementation and a passing test after implementation. Required repository gates after code changes are `npm run check`, `npm test`, and `npm run test:websocket`.

## Idempotence and Recovery

The changes are additive to existing pure view rendering and order-request tests. Re-running the test commands is safe. If the UI change creates unexpected layout issues, revert only the local edits in `order_form_view.cljs` and keep the tests as the desired contract until a better layout is chosen.

## Artifacts and Notes

Red test transcript, shortened:

    npm test
    Ran 3732 tests containing 20628 assertions.
    3 failures, 0 errors.
    FAIL in (outcome-market-renders-independent-buy-sell-side-tabs-test)
    expected: (some? buy-tab)
    actual: (not (some? nil))
    expected: (some? sell-tab)
    actual: (not (some? nil))
    expected: (= [[:actions/update-order-form [:side] :sell]] sell-click)
    actual: (not (= [[:actions/update-order-form [:side] :sell]] nil))

Green and gate transcripts, shortened:

    npm test
    Ran 3732 tests containing 20630 assertions.
    0 failures, 0 errors.

    npm run check
    [:app] Build completed. (1005 files, 980 compiled, 0 warnings, 16.17s)
    [:portfolio] Build completed. (740 files, 739 compiled, 0 warnings, 10.99s)
    [:portfolio-worker] Build completed. (62 files, 61 compiled, 0 warnings, 4.60s)
    [:portfolio-optimizer-worker] Build completed. (77 files, 76 compiled, 0 warnings, 5.59s)
    [:vault-detail-worker] Build completed. (63 files, 62 compiled, 0 warnings, 4.31s)
    [:test] Build completed. (1585 files, 4 compiled, 0 warnings, 6.87s)

    npm run test:websocket
    Ran 523 tests containing 3041 assertions.
    0 failures, 0 errors.

    npx playwright test tools/playwright/test/trade-regressions.spec.mjs --reporter=line
    2 failed
    tools/playwright/test/trade-regressions.spec.mjs:922:1 asset selector outcome rows use full-width question copy without duplicate chip
    tools/playwright/test/trade-regressions.spec.mjs:1230:1 named-dex close-position popover loads full market metadata before submit
    32 passed (5.7m)

The first Playwright failure expected `BTC above 78213 on May 3 at 2:00 AM?` but received `BTC above 78213 on May 4 at 2:00 AM?`, which is unrelated to this outcome-ticket side toggle. The second Playwright failure expected action trace coverage for `:actions/open-position-reduce-popover`; the trace had `phaseOrderValid: true` and `projectionBeforeHeavy: true` but `covered: false`, also outside this patch.

Design review transcript, shortened:

    npm run qa:design-ui -- --targets trade-route --manage-local-app
    reviewOutcome: PASS
    inspected widths: 375, 768, 1280, 1440
    visual-evidence-captured: PASS
    native-control: PASS
    styling-consistency: PASS
    interaction: PASS
    layout-regression: PASS
    jank-perf: PASS

Cleanup transcript:

    npm run browser:cleanup
    {"ok":true,"stopped":[],"results":[]}

## Interfaces and Dependencies

No new runtime dependency is required. The implementation depends on existing functions:

- `hyperopen.views.trade.order-form-controls/side-row`, which renders the Buy/Sell segmented row and dispatches side changes.
- `hyperopen.views.trade.order-form-controls/outcome-side-row`, which renders outcome token buttons.
- `hyperopen.state.trading/build-order-request`, which consumes `:side` for Buy/Sell and `:outcome-side` for asset resolution.

Revision note 2026-05-03 / Codex: Completed implementation, recorded red-green evidence, gate results, browser-QA results, and the unrelated Playwright failures encountered during the trade regression run.
