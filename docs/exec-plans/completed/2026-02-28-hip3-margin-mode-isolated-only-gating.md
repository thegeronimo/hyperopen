# HIP-3 Margin Mode Eligibility Gating in Order Form

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan follows `/hyperopen/.agents/PLANS.md` and must be maintained in accordance with that file.

## Purpose / Big Picture

Today, the order ticket always exposes both `Cross` and `Isolated` margin mode options, even when the selected asset is configured on Hyperliquid as isolated-only (`marginMode: noCross` or `strictIsolated`, or `onlyIsolated: true`). This creates a misleading interaction: the user can select `Cross` and only discover it is invalid when submitting.

After this change, the order ticket will deterministically respect per-asset eligibility. If an asset does not allow cross margin, the margin mode control will only present isolated behavior and the submit request path will be forced to isolated mode. Users can verify this by switching between a cross-eligible and isolated-only HIP-3 asset and observing that the margin selector behavior and outgoing `updateLeverage` pre-action mode match market metadata.

## Progress

- [x] (2026-02-28 17:05Z) Confirmed protocol and SDK behavior: user `updateLeverage` cannot override isolated-only assets; eligibility is driven by market metadata (`marginMode` / `onlyIsolated`).
- [x] (2026-02-28 17:05Z) Audited Hyperopen order-form runtime and found margin dropdown currently hardcoded to render both options in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`.
- [x] (2026-02-28 17:02Z) Implemented metadata propagation of margin eligibility fields (`marginMode`, `onlyIsolated`) in selector market builders/cache normalization.
- [x] (2026-02-28 17:02Z) Implemented canonical trading-state helpers for cross-margin eligibility and effective margin-mode clamping, and applied clamping in order-form normalization.
- [x] (2026-02-28 17:02Z) Updated order-form transitions and view rendering so isolated-only assets show isolated-only control behavior and cannot retain `:cross` draft state.
- [x] (2026-02-28 17:02Z) Added and updated tests for market metadata parsing, state clamping, submit pre-action enforcement, and view-level margin control behavior.
- [x] (2026-02-28 17:02Z) Ran required gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-28 17:02Z) Moved this plan to `/hyperopen/docs/exec-plans/completed/` after acceptance criteria passed.

## Surprises & Discoveries

- Observation: The live HIP-3 `xyz:NATGAS` market is isolated-only today.
  Evidence: `POST https://api.hyperliquid.xyz/info` with `{"type":"metaAndAssetCtxs","dex":"xyz"}` returns universe entry `{"name":"xyz:NATGAS","onlyIsolated":true,"marginMode":"noCross",...}`.

- Observation: Hyperopen already ingests HIP-3 eligibility hints (`:hip3?`, `:hip3-eligible?`) but does not currently persist or consume margin eligibility hints from the same market metadata.
  Evidence: `/hyperopen/src/hyperopen/asset_selector/markets.cljs` and `/hyperopen/src/hyperopen/asset_selector/markets_cache.cljs`.

- Observation: The submit request path can include a pre-action `updateLeverage` with `:isCross true` whenever form margin mode is `:cross`, independent of market eligibility.
  Evidence: `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs` `build-update-leverage-action`.

- Observation: Fresh workspace environments can fail `npm test` before dependency install because the script invokes `shadow-cljs` directly (not `npx shadow-cljs`).
  Evidence: Initial run returned `sh: shadow-cljs: command not found`; resolved by running `npm install` once.

## Decision Log

- Decision: Treat `marginMode: "noCross"` and `marginMode: "strictIsolated"` and `onlyIsolated: true` as isolated-only eligibility, and default unknown values to cross-eligible.
  Rationale: This preserves compatibility with current/default-perp behavior while enforcing protocol-declared restrictions where explicit metadata is present.
  Date/Author: 2026-02-28 / Codex

- Decision: Enforce margin-mode eligibility in state normalization (not only in UI rendering).
  Rationale: UI-only hiding can still leak invalid `:cross` mode into submit requests; state-level clamping guarantees deterministic request construction.
  Date/Author: 2026-02-28 / Codex

- Decision: Keep order-form VM contract unchanged and derive rendering behavior in view/state helpers.
  Rationale: VM schema is strict exact-key; avoiding VM surface expansion minimizes contract churn while still enabling deterministic UI behavior.
  Date/Author: 2026-02-28 / Codex

## Outcomes & Retrospective

Implemented end-to-end margin eligibility gating for HIP-3 assets in the order form:

- Selector market metadata now preserves margin policy fields (`:margin-mode`, `:only-isolated?`) from perp universe payloads and keeps those fields through cache normalization paths.
- Trading state now exposes `cross-margin-allowed?` and `effective-margin-mode`, clamps order-form normalization to isolated when the active market disallows cross, and closes margin-mode dropdown state in that scenario.
- Order-form margin control now renders a static isolated chip when cross is unavailable, while preserving prior dropdown behavior for cross-eligible assets.
- Exchange pre-action generation now hardens `updateLeverage` against invalid cross usage by forcing isolated mode whenever market metadata indicates isolated-only behavior.
- Regression coverage was added across metadata builders, cache normalization, state helpers, transition behavior, view behavior, and order command pre-action construction.

Validation outcomes:

- `npm run check`: pass
- `npm test`: pass
- `npm run test:websocket`: pass

## Context and Orientation

Order-form rendering lives in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`. The margin mode selector is implemented by `margin-mode-chip` and currently always renders `Cross` and `Isolated` options.

Order-form draft and UI normalization lives in `/hyperopen/src/hyperopen/state/trading.cljs` and `/hyperopen/src/hyperopen/trading/order_form_state.cljs`. Transition handlers in `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs` persist selected margin mode into `:order-form` / `:order-form-ui`.

Market metadata is built in `/hyperopen/src/hyperopen/asset_selector/markets.cljs` from `metaAndAssetCtxs`, then cached/normalized in `/hyperopen/src/hyperopen/asset_selector/markets_cache.cljs` and optionally in `/hyperopen/src/hyperopen/asset_selector/active_market_cache.cljs`.

In this plan, “isolated-only” means a selected market where cross margin must not be offered or sent in `updateLeverage`.

## Plan of Work

Milestone 1 updates market metadata propagation. I will extend the perp market builder to preserve `marginMode` and `onlyIsolated` fields from universe metadata, and normalize these fields in selector cache and active-market cache normalization so warm/cached market records preserve eligibility hints.

Milestone 2 adds trading-state eligibility helpers and enforcement. I will add a canonical function in trading state to compute cross-margin eligibility from active market metadata, and another function to clamp requested margin mode to isolated when required. I will apply this clamping in order-form normalization and in `set-order-margin-mode` transitions so the form cannot retain invalid `:cross` state.

Milestone 3 updates order-form rendering behavior. I will change the margin control to render a single non-dropdown isolated chip when cross margin is not allowed, and retain existing dropdown behavior for cross-eligible assets. This avoids presenting unavailable controls while preserving keyboard/focus handling where relevant.

Milestone 4 updates tests and runs required validation gates, then finalizes this plan and moves it to completed.

## Concrete Steps

From repository root `/hyperopen`:

1. Edit market metadata builders and cache normalizers:
   - `/hyperopen/src/hyperopen/asset_selector/markets.cljs`
   - `/hyperopen/src/hyperopen/asset_selector/markets_cache.cljs`
   - `/hyperopen/src/hyperopen/asset_selector/active_market_cache.cljs` (if needed for normalization parity)
2. Edit trading state and transitions:
   - `/hyperopen/src/hyperopen/state/trading.cljs`
   - `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`
3. Edit order-form margin control rendering:
   - `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`
4. Update/add tests:
   - `/hyperopen/test/hyperopen/asset_selector/markets_test.cljs`
   - `/hyperopen/test/hyperopen/asset_selector/markets_cache_test.cljs`
   - `/hyperopen/test/hyperopen/asset_selector/active_market_cache_test.cljs` (if cache normalization updated)
   - `/hyperopen/test/hyperopen/state/trading/identity_and_submit_policy_test.cljs`
   - `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs`
   - `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs`
   - `/hyperopen/test/hyperopen/views/trade/order_form_view/size_and_slider_test.cljs`
5. Run required gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance is met when all conditions hold:

1. Selecting an isolated-only HIP-3 asset yields isolated-only margin control UI (no cross option offered).
2. Selecting a cross-eligible asset still offers both `Cross` and `Isolated` options.
3. Attempting to set margin mode to cross in transitions while active market is isolated-only is clamped to isolated.
4. Order submit pre-actions for isolated-only markets never send `updateLeverage` with `isCross: true`.
5. All required validation gates pass.

## Idempotence and Recovery

The implementation is additive and localized to market metadata normalization, trading-state normalization, order-form transitions, and order-form view rendering. Re-running edits and tests is safe. If a partial change causes transient test failures, recovery is to finish aligning all three layers together: metadata ingestion, state clamping, and view gating.

## Artifacts and Notes

Primary runtime/protocol evidence used during planning:

- Hyperliquid live API `metaAndAssetCtxs` for `dex=xyz` showing `xyz:NATGAS` as isolated-only.
- Hyperliquid Python SDK `update_leverage` and `perp_deploy_register_asset` (`onlyIsolated`) semantics.
- Hyperopen current `build-update-leverage-action` behavior in `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`.

## Interfaces and Dependencies

Planned public function additions in `/hyperopen/src/hyperopen/state/trading.cljs`:

- `cross-margin-allowed? [state] -> boolean`
- `effective-margin-mode [state mode] -> :cross | :isolated`

These functions will be consumed by:

- `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`
- `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`
- `normalize-order-form` / UI-state normalization paths in trading state.

Plan revision note: 2026-02-28 17:05Z - Initial plan created after protocol/SDK verification and local codepath audit for margin mode rendering and submit pre-actions.
Plan revision note: 2026-02-28 17:02Z - Updated progress/outcomes after implementation completion and validation gate passes.
