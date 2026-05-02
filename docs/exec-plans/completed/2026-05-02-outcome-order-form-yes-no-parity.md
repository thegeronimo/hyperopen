# Outcome Order Form Yes/No Parity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. This document follows `.agents/PLANS.md`.

Tracked issue: `hyperopen-c6zw` ("Outcome order form Yes/No parity").

## Purpose / Big Picture

Outcome markets on Hyperliquid are binary token markets. A user buys the `Yes` token when they think the event will happen, or buys the `No` token when they think it will not happen. Hyperopen currently exposes the underlying implementation details too directly by showing the normal perp `Buy / Long` and `Sell / Short` side row along with an extra outcome side selector. After this change, selecting an outcome market shows the Hyperliquid-style outcome ticket: the outcome side selector reads `Buy Yes` and `Buy No` while buying, the standalone `Buy` / `Sell` row is removed for outcomes, and long/short wording and leverage/margin controls are hidden only for outcome markets. Non-outcome markets keep their existing order form.

## Progress

- [x] (2026-05-02 18:33Z) Created tracked issue `hyperopen-c6zw`.
- [x] (2026-05-02 18:34Z) Inspected Hyperliquid's live outcome route and confirmed the visible order form copy: `Buy`, `Sell`, `Market`, `Buy Yes`, `Buy No`, `Available to Trade`, `Size`, `Yes`, `%`, with no long/short wording.
- [x] (2026-05-02 18:35Z) Mapped Hyperopen order form files and found the existing outcome selector is additive, while the standard side row and leverage row still render for outcomes.
- [x] (2026-05-02 18:41Z) Implemented outcome-only order form rendering changes and regression tests. `npm test` now passes.
- [x] (2026-05-02 18:47Z) Ran `npm run check`, `npm test`, `npm run test:websocket`, and browser verification successfully.
- [x] (2026-05-02 18:48Z) Stopped the local dev inspection server with `npm run dev:kill`.
- [x] (2026-05-02 18:49Z) Moved this ExecPlan to `docs/exec-plans/completed/` after acceptance.
- [x] (2026-05-02 19:00Z) Applied follow-up polish: removed the standalone outcome `Buy` / `Sell` row and made the active `Buy No` button use the sell/red color treatment.

## Surprises & Discoveries

- Observation: Hyperliquid keeps top-level `Buy` and `Sell` tabs for the outcome route, but the visible selected-side buttons are `Buy Yes` and `Buy No`.
  Evidence: Live browser inspection of `https://app.hyperliquid.xyz/trade/btc-above-78213-yes-may-03-0600` on 2026-05-02 showed the order ticket text sequence `Buy`, `Sell`, `Market`, `Buy Yes`, `Buy No`.
- Observation: Hyperopen already routes the selected outcome side into submit-time asset id resolution.
  Evidence: `src/hyperopen/state/trading.cljs` selects an outcome side from `:outcome-side`, swaps `:coin` and `:asset-id` into the market, and tests cover `build-order-request-resolves-selected-outcome-side-asset-id-test`.
- Observation: The current UI bug is presentation and intent language, not the lower-level asset id mapping.
  Evidence: `src/hyperopen/views/trade/order_form_view.cljs` renders both `controls/side-row` and `controls/outcome-side-row` when `:outcome?` is true.
- Observation: The side naming mismatch affected both copy and size-unit presentation.
  Evidence: Normalized outcome markets from `src/hyperopen/asset_selector/markets.cljs` store side names as `:side-name`, while the existing order form rendering only read `:side-label` before falling back to `Side 0` / `Side 1`.

## Decision Log

- Decision: Keep the canonical form `:side` as `:buy` by default for outcome buy-side selection and change only the outcome side token via `:outcome-side`.
  Rationale: Hyperliquid outcome buy ticket means "buy the selected Yes or No token"; Hyperopen already maps `:outcome-side` to `#0` or `#1` asset ids at submit time. Changing the meaning of `:side` would risk lower-level order semantics without improving the visible behavior.
  Date/Author: 2026-05-02 / Codex
- Decision: Hide margin/leverage controls and the normal `Buy / Long` / `Sell / Short` row when `:outcome?` is true.
  Rationale: Outcomes are spot-like token purchases in Hyperliquid's UI. Showing perp leverage, long, and short language is misleading for outcome markets, while non-outcome markets still need those controls.
  Date/Author: 2026-05-02 / Codex
- Decision: Reuse the existing outcome side control function but pass a context label so it renders `Buy Yes` and `Buy No`.
  Rationale: This keeps the change small and preserves the existing command path `set-order-outcome-side`, while aligning the visible copy with Hyperliquid.
  Date/Author: 2026-05-02 / Codex
- Decision: Remove the standalone `Buy` / `Sell` row from outcome markets after adding `Buy Yes` and `Buy No`.
  Rationale: The binary side buttons now carry the action and side-token intent directly, so the extra row is redundant and made the ticket look unlike the user's latest target.
  Date/Author: 2026-05-02 / Codex
- Decision: Color active `Buy No` as sell/red.
  Rationale: Even though the order action is a buy of the No token, the product semantics are the negative outcome side. The user explicitly asked for the No button to match the Sell button's red treatment.
  Date/Author: 2026-05-02 / Codex

## Outcomes & Retrospective

2026-05-02 update: The code now renders outcome markets with outcome side buttons labeled with the current action prefix (`Buy Yes`, `Buy No`, or sell equivalents), and the selected outcome side name as the size unit. `Buy No` uses the sell/red active color. The standalone `Buy` / `Sell` side row, perp-only leverage/margin, reduce-only, TP/SL, and post-only controls are hidden for outcomes. The change reduces user-facing conceptual complexity for outcome markets by hiding unrelated perp controls, while adding a small amount of view branching isolated to the order form UI.

## Context and Orientation

The order form view model is built in `src/hyperopen/views/trade/order_form_vm.cljs`. It exposes `:outcome?`, `:outcome-sides`, and `:outcome-side-index` from market metadata. The form view is rendered in `src/hyperopen/views/trade/order_form_view.cljs`. That view currently always renders `controls/leverage-row`, `sections/entry-mode-tabs`, and `controls/side-row`, then renders `controls/outcome-side-row` when `:outcome?` is true. The outcome side selector lives in `src/hyperopen/views/trade/order_form_controls.cljs`; it currently labels the section `Outcome` and button labels are just `Yes` and `No`.

The lower-level submit path already knows how to submit the selected side. `src/hyperopen/state/trading.cljs` reads `:outcome-side` from the form, finds the matching entry in `:outcome-sides`, and replaces the selected market `:coin` and `:asset-id` before building the order request. The current task should not change that submit mapping unless tests prove it is wrong.

## Plan of Work

First, add regression tests around the existing order form Hiccup rendering. The outcome order form test should assert that an outcome market renders `Buy Yes` and `Buy No`, does not render `Buy / Long`, does not render `Sell / Short`, and does not render margin/leverage controls such as `Cross`, `20x`, or `Classic`. The existing non-outcome parity test should remain unchanged and continue to assert the normal perp controls.

Second, update `src/hyperopen/views/trade/order_form_view.cljs` so `controls/leverage-row` and `controls/side-row` are skipped when `outcome?` is true. Keep the existing order type tabs visible for outcomes. Render the outcome side row where the standard side row would normally appear so the user chooses the binary token without seeing long/short language.

Third, update `src/hyperopen/views/trade/order_form_controls.cljs` so `outcome-side-row` accepts an optional action prefix. For this ticket, pass `"Buy "` from the order form view. Each button should render `(str action-prefix side-label)`, producing `Buy Yes` and `Buy No`. The function should keep its fallback for unknown side names, so an unexpected side still renders a readable label like `Buy Side 2`.

Fourth, run the focused Hiccup tests that cover order form rendering. Then run the full required gates: `npm run check`, `npm test`, and `npm run test:websocket`. Because this is UI behavior, start the local dev browser-inspection server, select the BTC outcome market, and verify the order ticket shows `Buy Yes` and `Buy No` without long/short or leverage text. Stop the dev server afterward.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/3c7b/hyperopen`.

1. Edit `test/hyperopen/views/trade/order_form_view_test.cljs` and extend `order-form-renders-outcome-side-selector-test` with negative assertions for `Buy / Long`, `Sell / Short`, `Cross`, `20x`, and `Classic`, plus positive assertions for `Buy Yes` and `Buy No`.

2. Edit `src/hyperopen/views/trade/order_form_controls.cljs`. Change `outcome-side-row` to accept a fourth optional argument map with `:action-prefix`. Compute `display-label` from the side label and prefix, and render it inside the button.

3. Edit `src/hyperopen/views/trade/order_form_view.cljs`. Wrap `controls/leverage-row` and `controls/side-row` in `when-not outcome?`. For outcomes, call `controls/outcome-side-row` with an action prefix derived from the selected side, such as `{:action-prefix "Buy "}`, and with a side-intent mapping that treats side index `1` as sell/red.

4. Run `npm test`. The final run passed: `Ran 3706 tests containing 20451 assertions. 0 failures, 0 errors.`

5. Run `npm run check`. The final run passed, including namespace guards, CSS build checks, and ClojureScript builds.

6. Run `npm run test:websocket`. The final run passed: `Ran 523 tests containing 3040 assertions. 0 failures, 0 errors.`

7. Start browser verification with `npm run dev:browser-inspection`, open `/trade`, select the outcome market from the asset selector if needed, and verify the order ticket text contains `Buy Yes` and `Buy No` and does not contain standalone `Buy`, standalone `Sell`, `Buy / Long`, `Sell / Short`, `Cross`, `20x`, or `Classic` within the order form panel. The final browser check used `http://localhost:8081/trade?market=%230&tab=balances` and passed; after selecting `Buy No`, the button had class `bg-[#ED7088]`. Stop the server with `npm run dev:kill`.

## Validation and Acceptance

The change is accepted when an outcome market displays the order form as a binary outcome ticket. On the BTC outcome route, the order form must show side-token buttons labeled `Buy Yes` and `Buy No`, with active `Buy No` using the sell/red treatment. The form must not show standalone `Buy` / `Sell`, long/short wording, or leverage/margin controls for outcomes. Perp markets must still show `Buy / Long`, `Sell / Short`, `Cross`, leverage, and `Classic`.

Automated validation must include `npm test`, `npm run check`, and `npm run test:websocket` passing from the repository root. Browser validation must exercise the actual trade route and confirm the visible order form copy.

## Idempotence and Recovery

The edits are local and additive to the outcome-only branch of the order form. Re-running the tests is safe. If browser verification starts a dev server, use `npm run dev:kill` to stop it before finishing. If a test fails because expected text moved, inspect the Hiccup tree rather than weakening the assertion; this ticket is specifically about visible copy and control presence.

## Artifacts and Notes

Hyperliquid live inspection on 2026-05-02 produced this relevant order form text:

    Buy
    Sell
    Market
    Buy Yes
    Buy No
    Available to Trade
    0 USDH
    Size
    Yes
    %

## Interfaces and Dependencies

The public view function `hyperopen.views.trade.order-form-view/order-form-view` remains unchanged. The internal control function `hyperopen.views.trade.order-form-controls/side-row` gains an optional third argument for label overrides, though outcomes no longer use that row. The internal control function `hyperopen.views.trade.order-form-controls/outcome-side-row` gains an optional fourth argument and remains compatible with existing tests that call it with three arguments. The order command interface `hyperopen.views.trade.order-form-commands/set-order-outcome-side` remains unchanged.

## Revision Notes

- 2026-05-02: Created this active ExecPlan after live Hyperliquid inspection and repository mapping. The plan is scoped to outcome order form presentation and preserves existing submit-time asset id mapping.
- 2026-05-02: Updated progress after implementing the outcome-only rendering branch and passing `npm test`.
- 2026-05-02: Updated validation evidence, completed the plan, and moved it to completed after browser verification and cleanup.
- 2026-05-02: Corrected plan prose after completion as the outcome side-row design evolved.
- 2026-05-02: Updated the completed plan for the final UI polish that removes the outcome `Buy` / `Sell` row and colors active `Buy No` red.
