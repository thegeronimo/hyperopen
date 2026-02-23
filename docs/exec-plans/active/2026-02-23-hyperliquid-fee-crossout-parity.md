# Hyperliquid Fee Crossout Parity in Trade Order Summary

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Hyperopen currently renders a single static fee string (`0.045% / 0.015%`) in the trade order summary, while Hyperliquid conditionally shows a lower effective fee and, when applicable, a strikethrough baseline fee under it. After this change, Hyperopen will compute fee output from live account and market context and render fee parity behavior: effective taker/maker on the first line, optional strikethrough baseline on the second line.

A user can verify this by opening `/trade`, selecting markets that trigger fee modifiers (for example a stable-pair spot market or a named perp DEX market with fee scaling), and observing that the `Fees` row switches from one static line to a parity-style two-line display when effective taker fee is lower than baseline.

## Progress

- [x] (2026-02-23 15:53Z) Reconstructed Hyperliquid fee computation logic from frontend bundle artifacts and validated constants/branches needed for parity.
- [x] (2026-02-23 15:53Z) Audited current Hyperopen trade-summary fee pipeline and identified exact insertion points from API projection through domain summary to presenter/view.
- [x] (2026-02-23 15:53Z) Authored this ExecPlan.
- [x] (2026-02-23 16:09Z) Implemented `quote-fees` domain model in `/hyperopen/src/hyperopen/domain/trading/fees.cljs` with parity math for referral, staking baseline reconstruction, stable-pair multiplier, perp deployer scaling, growth mode, and optional adjustment branch.
- [x] (2026-02-23 16:10Z) Threaded fee context inputs through market/state/API layers, including `:perp-dex-fee-config-by-name`, normalized market `:growth-mode?`, and spot `:stable-pair?`.
- [x] (2026-02-23 16:11Z) Replaced static order-summary fee output with structured fee quote fallback behavior in `/hyperopen/src/hyperopen/domain/trading/market.cljs`.
- [x] (2026-02-23 16:12Z) Updated presenter/view/schema contract path so `Fees` renders effective line plus optional strikethrough baseline line.
- [x] (2026-02-23 16:14Z) Added/updated tests across domain math, API normalization/projections, state summary outputs, presenter formatting, and fee-row rendering.
- [x] (2026-02-23 16:15Z) Ran required validation gates successfully: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-23 16:15Z) Recorded outcomes, discoveries, and final implementation notes in this plan.

## Surprises & Discoveries

- Observation: Hyperliquid applies a 0.2 multiplier for spot stable pairs before formatting fees.
  Evidence: `/hyperopen/tmp/module-36714.js` contains `const sr=4,ar=.2` and `const s="spot"===n.type&&n.isStablePair?ar:1` in fee function.

- Observation: Hyperliquid computes both effective and baseline rates and only shows baseline when effective taker is lower.
  Evidence: `/hyperopen/tmp/module-36714.js` returns `baseFee: d<h ? {takerFee: f, makerFee: m} : null` where `d` is effective taker and `h` is baseline taker.

- Observation: Referral discount applies to taker and positive maker fees, but not to negative maker rebates.
  Evidence: `/hyperopen/tmp/module-36714.js` in helper `gr` applies discount to `addRate` only when `addRate > 0`.

- Observation: `growthMode` is available per perp market in `metaAndAssetCtxs` universe entries and is not currently persisted in Hyperopen market records.
  Evidence: live API payload inspection (`{"type":"metaAndAssetCtxs","dex":"xyz"}`) includes `growthMode` key in universe entries.

- Observation: `deployerFeeScale` is available in `perpDexs` payload but Hyperopen currently drops it and stores only DEX names.
  Evidence: `/hyperopen/src/hyperopen/api/endpoints/market.cljs` `request-perp-dexs!` maps response to names only.

- Observation: Returning structured perp DEX payloads (`{:dex-names ... :fee-config-by-name ...}`) required normalizing names in every consumer that previously assumed plain vectors.
  Evidence: first green run after implementation surfaced `hyperopen.api.facade-runtime-test` failure expecting `["dex-a"]` vectors from `:request-perp-dexs!`; fix was to normalize names at fetch/loader/order-effects boundaries.

- Observation: Hyperliquid also renders a green blob icon when a baseline crossout is present, but parity acceptance in this plan only required fee-line crossout behavior.
  Evidence: `/hyperopen/tmp/hyperliquid-main-split.js` shows `src:"/images/blob_green.svg"` gated by `e.baseFee`.

## Decision Log

- Decision: Preserve existing `:perp-dexs` (name vector) contract for market loading and add a separate state map for fee metadata.
  Rationale: This avoids broad regressions in asset-selector loading while still providing fee inputs required for parity.
  Date/Author: 2026-02-23 / Codex

- Decision: Introduce a structured fee quote in domain and presenter layers instead of overloading a single formatted string.
  Rationale: Crossout rendering requires both effective and optional baseline values and cannot be represented safely as one immutable display string.
  Date/Author: 2026-02-23 / Codex

- Decision: Implement the Hyperliquid fee formula in a dedicated domain namespace and call it from order summary.
  Rationale: Isolating this logic prevents duplication between portfolio/trade displays and makes parity math testable in one place.
  Date/Author: 2026-02-23 / Codex

- Decision: Treat the additional Hyperliquid boolean fee-adjustment branch as an explicit context input with a safe default of `false` unless sourced from known state.
  Rationale: The formula branch exists in bundle logic; carrying it as an explicit parameter allows exact parity when source signal is confirmed without blocking current rollout.
  Date/Author: 2026-02-23 / Codex

- Decision: Change perp DEX API/service payloads to structured maps while keeping existing name-vector behavior at call boundaries via local normalization helpers.
  Rationale: Fee metadata (`deployerFeeScale`) must be preserved for parity, but market loading and order-refresh loops remain stable by consuming derived name vectors.
  Date/Author: 2026-02-23 / Codex

- Decision: Normalize `:growth-mode?` and `:stable-pair?` onto market records during market construction.
  Rationale: Fee computation should be pure and context-driven, without requiring raw API payload introspection at summary time.
  Date/Author: 2026-02-23 / Codex

- Decision: Format order-summary fee display with four decimals in presenter output.
  Rationale: Hyperliquid fee strings are formatted to four decimals in the reconstructed bundle logic, so this improves parity and reduces rounding ambiguity for small discounted fees.
  Date/Author: 2026-02-23 / Codex

## Outcomes & Retrospective

Implementation is complete end-to-end. The trade order summary now computes fee quotes from live user-fee and market context and renders a two-line parity display: effective taker/maker on the primary line and optional strikethrough baseline on the secondary line.

Completed deliverables:

- Added `/hyperopen/src/hyperopen/domain/trading/fees.cljs` and integrated it into `/hyperopen/src/hyperopen/domain/trading/market.cljs` `order-summary`.
- Preserved existing `:perp-dexs` usage while adding `:perp-dex-fee-config-by-name` through endpoint/projection/default-state wiring.
- Added market normalization for `:growth-mode?` (perp) and `:stable-pair?` (spot).
- Updated `/hyperopen/src/hyperopen/views/trade/order_form_presenter.cljs`, `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`, and `/hyperopen/src/hyperopen/schema/order_form_contracts.cljs` for structured fee display and crossout rendering.
- Added regression coverage in:
  `/hyperopen/test/hyperopen/domain/trading/fees_test.cljs`,
  `/hyperopen/test/hyperopen/state/trading/market_summary_test.cljs`,
  `/hyperopen/test/hyperopen/views/trade/order_form_presenter_test.cljs`,
  `/hyperopen/test/hyperopen/views/trade/order_form_view/metrics_and_submit_test.cljs`,
  plus API/asset-selector/default-state/schema tests impacted by payload and contract changes.

Validation outcome:

- `npm run check` passed.
- `npm test` passed.
- `npm run test:websocket` passed.

No open follow-up tasks remain for this plan’s defined acceptance scope.

## Context and Orientation

The pre-change trade-summary fee path was static. This implementation replaces that path with a structured fee quote (`:effective` plus optional `:baseline`) computed in `/hyperopen/src/hyperopen/domain/trading/fees.cljs` and rendered by the presenter/view pair in `/hyperopen/src/hyperopen/views/trade/order_form_presenter.cljs` and `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`.

The term """baseline fee""" in this plan means the strikethrough line shown by Hyperliquid when effective taker fee is discounted below the user""s pre-discount baseline. In Hyperliquid bundle logic this baseline is reconstructed from user fee payload before staking discount and compared to computed effective taker.

The term """effective fee""" means the fee used for current trade context after applying referral discount, spot stable-pair multiplier, perp deployer-fee scaling, and growth-mode scaling. Hyperliquid uses the following data inputs:

- user fee payload fields: `userCrossRate`, `userAddRate`, `userSpotCrossRate`, `userSpotAddRate`, `activeReferralDiscount`, `activeStakingDiscount.discount`
- spot context: `isStablePair`
- perp context: `deployerFeeScale`, `growthMode`

Repository areas to modify:

- Domain fee model and order summary: `/hyperopen/src/hyperopen/domain/trading/core.cljs`, `/hyperopen/src/hyperopen/domain/trading/market.cljs`, new fee module under `/hyperopen/src/hyperopen/domain/trading/`
- Trading state context wiring: `/hyperopen/src/hyperopen/state/trading.cljs`
- Perp DEX metadata capture and projection: `/hyperopen/src/hyperopen/api/endpoints/market.cljs`, `/hyperopen/src/hyperopen/api/projections.cljs`, `/hyperopen/src/hyperopen/state/app_defaults.cljs`
- Market model enrichment (growth mode flags): `/hyperopen/src/hyperopen/asset_selector/markets.cljs`
- Presenter/view rendering: `/hyperopen/src/hyperopen/views/trade/order_form_presenter.cljs`, `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`, and potentially `/hyperopen/src/hyperopen/views/trade/order_form_component_primitives.cljs`
- Contracts and tests: `/hyperopen/src/hyperopen/schema/order_form_contracts.cljs` and corresponding tests under `/hyperopen/test/hyperopen/`

## Plan of Work

### Milestone 1: Define a Fee Quote Domain Model

Add a dedicated domain namespace (for example `/hyperopen/src/hyperopen/domain/trading/fees.cljs`) that computes a fee quote from user fee payload and market context. The function should return effective taker/maker values and optional baseline values in percentage units, plus any explanatory text hooks if needed later.

The implementation should mirror Hyperliquid math exactly:

- Select spot or perp user rates.
- Apply referral discount to taker and positive maker only.
- Reconstruct baseline using `activeStakingDiscount.discount` before comparison.
- Apply spot stable-pair factor `0.2` when `isStablePair` is true.
- For perps, apply `deployerFeeScale` and `growthMode` transforms exactly as observed.
- Emit baseline only when effective taker is lower than baseline taker.

This milestone is complete when the function is pure, deterministic, and covered by targeted unit tests for each branch.

### Milestone 2: Make Required Fee Inputs Available in Trading Context

Capture perp DEX fee metadata from `perpDexs` without breaking existing market-loader behavior. Keep `:perp-dexs` as names and add `:perp-dex-fee-config-by-name` (for example `{ "hyna" {:deployer-fee-scale 0.1111} }`).

Add growth-mode information to active perp market data by preserving universe-level `growthMode` when building market entries in `/hyperopen/src/hyperopen/asset_selector/markets.cljs`.

Thread `:portfolio :user-fees`, active market growth mode, active market DEX, and `:perp-dex-fee-config-by-name` into the trading context in `/hyperopen/src/hyperopen/state/trading.cljs` so order summary has all required inputs.

This milestone is complete when state contains deterministic fee inputs and existing asset-selector flows remain unchanged.

### Milestone 3: Replace Static Order Summary Fees with Computed Quote

Update `/hyperopen/src/hyperopen/domain/trading/market.cljs` `order-summary` to call the new fee-domain function instead of returning `core/default-fees` unconditionally. Preserve safe fallbacks so missing payloads still produce defaults.

At the summary boundary, return a structured `:fees` payload that includes current values and optional baseline values, not only raw numbers.

This milestone is complete when summary behavior remains stable for missing data and returns parity-ready fee structure when data is present.

### Milestone 4: Render Hyperliquid-Style Fee Rows

Update presenter and view layers so `Fees` can render two lines:

- top line: effective `taker / maker`
- bottom line (optional): baseline `taker / maker` with line-through styling

Keep the rest of order-summary rows unchanged and deterministic. If `metric-row` cannot support mixed typography cleanly, add a small fee-specific row renderer in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` rather than overloading unrelated primitives.

This milestone is complete when visual output matches parity intent for both single-line and two-line fee states.

### Milestone 5: Validation and Regression Coverage

Update tests in all impacted layers:

- API normalization/projection tests for perp DEX metadata map population.
- Domain tests for fee formula branches (referral, stable pair, deployer scale, growth mode, baseline visibility).
- State/order-summary tests for fallback and computed fee outputs.
- Presenter/view tests for new fee rendering, including strikethrough line.
- Schema contract tests for any changed `:display :fees` shape.

Complete this milestone by running required repo gates and recording pass/fail evidence in this plan.

## Concrete Steps

1. Implement fee-domain module and tests.

    cd /Users//projects/hyperopen
   npm test

   Expected indicator: tests covering new fee function pass and exercise all fee branches.
   Result: completed (2026-02-23 16:14Z), including new `hyperopen.domain.trading.fees-test`.

2. Implement perp DEX metadata + growth mode threading, then update order-summary integration.

    cd /Users//projects/hyperopen
   npm test

   Expected indicator: existing market-loader and trading-summary tests remain green after state-shape additions.
   Result: completed (2026-02-23 16:14Z), including API/projection/market-loader regression updates.

3. Implement presenter/view crossout rendering and update contracts.

    cd /Users//projects/hyperopen
   npm test

   Expected indicator: order-form view tests and contract tests pass with new fee display shape.
   Result: completed (2026-02-23 16:14Z), including presenter and view rendering tests.

4. Run required validation gates.

    cd /Users//projects/hyperopen
    npm run check
    npm test
   npm run test:websocket

   Expected indicator: all three commands exit successfully.
   Result: completed (2026-02-23 16:15Z), all three commands passed.

## Validation and Acceptance

The change is accepted when all criteria below are true:

1. Order summary no longer relies on static default fees when user-fee and market fee inputs are present.
2. `Fees` row shows effective taker/maker on one line and shows baseline line only when effective taker is lower.
3. Baseline values are rendered with strikethrough styling and match expected reconstructed rates.
4. Spot stable-pair market shows reduced effective fees consistent with the `0.2` multiplier.
5. Named perp DEX markets apply deployer fee scale and growth-mode adjustments in fee output.
6. Missing user-fee or dex metadata data falls back deterministically to existing defaults.
7. `npm run check`, `npm test`, and `npm run test:websocket` all pass.

## Idempotence and Recovery

This work is source-level and additive. Re-running any step is safe.

If regressions appear after adding fee metadata state, recovery should proceed by keeping newly added state keys but temporarily routing order-summary back to default fees until failing branch logic is fixed. This avoids destructive rollback of unrelated startup/market loading changes.

If UI regressions occur, revert only fee-row rendering in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` while retaining domain fee computation and tests, so parity math remains validated independently of presentation.

## Artifacts and Notes

Primary parity evidence used for this plan:

- `/hyperopen/tmp/module-36714.js` (Hyperliquid helper exporting fee computation)
- `/hyperopen/tmp/hyperliquid-185.pretty.js` (usage example calling fee helper)
- Live API probes for payload shape:
  - `POST /info {"type":"perpDexs"}` shows `deployerFeeScale`
  - `POST /info {"type":"metaAndAssetCtxs","dex":"<name>"}` universe entries include `growthMode`

Relevant extracted logic snippet (formatted from bundle):

    const sr=4, ar=.2
    const s = (spot && isStablePair) ? ar : 1
    if (perp) {
      a = deployerFeeScale < 1 ? deployerFeeScale + 1 : 2 * deployerFeeScale
      c = deployerFeeScale < 1 ? deployerFeeScale / (1 + deployerFeeScale) : 0.5
      l = growthMode ? 0.1 : 1
    }
    baseFee = effectiveTaker < baselineTaker ? baseline : null

## Interfaces and Dependencies

Expected interfaces at completion:

- New fee-domain function in `/hyperopen/src/hyperopen/domain/trading/fees.cljs`:

    (defn quote-fees
      [user-fees {:keys [market-type
                         stable-pair?
                         deployer-fee-scale
                         growth-mode?
                         extra-adjustment?]}]
      ;; returns {:effective {:taker number :maker number}
      ;;          :baseline  {:taker number :maker number} | nil}
      ...)

- `order-summary` in `/hyperopen/src/hyperopen/domain/trading/market.cljs` should consume this interface and emit structured fee output.

- Perp DEX projections in `/hyperopen/src/hyperopen/api/projections.cljs` should maintain both:

    :perp-dexs                     ;; vector of dex names for existing loaders
    :perp-dex-fee-config-by-name  ;; map keyed by dex name with fee-scale metadata

- Active perp market entries from `/hyperopen/src/hyperopen/asset_selector/markets.cljs` should expose growth-mode availability in a normalized boolean field.

- Order-form presenter/view contracts must accept and render structured fee display, including optional baseline line.

Plan revision note: 2026-02-23 15:53Z - Initial plan created to implement Hyperliquid fee crossout parity in trade order summary with validated formula and data-source wiring.
Plan revision note: 2026-02-23 16:15Z - Updated after implementation to record completed milestones, final design decisions, discovered integration impacts, and passing validation gate evidence.
