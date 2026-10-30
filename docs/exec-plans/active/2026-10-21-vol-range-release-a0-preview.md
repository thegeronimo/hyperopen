# Vol Range Release A0: exact planner, Scale ticket mode, chart overlays, read-only review

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It is maintained in accordance with `/hyperopen/docs/PLANS.md` and the detailed template in `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

HyperOpen's Pro ticket has a Scale order type that spreads one side of an order across a fixed price range. Traders who hold spot inventory want something different: place resting buy orders below the market and resting sell orders above it at distances measured in recent daily volatility, while controlling how much inventory they could end up holding. The product spec `/hyperopen/docs/product-specs/vol-range-implementation-spec.md` (HO-SPEC-014 v2.1) calls this Vol Range and ships it in stages.

This plan delivers only Release A0, the preview. After it lands, a user on a USDC spot market can open Pro, pick Scale, switch the new segmented control from Price range to Volatility range, and see a complete, exact ladder: daily volatility measured from the last 14 completed UTC daily candles (the Parkinson high/low estimator), an anchor price (24h VWAP, UTC open, Market price, or a custom anchor), an optional inventory tilt, inventory controls (protected core, target, maximum), an Accumulate buys card and a Distribute sells card with per-rung preview rows and totals, worst-case inventory if one side fills first, fee reserves, and the exact legal prices and sizes that would be signed. The same canonical legs are drawn on the chart as faint volatility bands and dashed draft order lines, and a Review screen shows every leg. The Review footer says "Placement isn't available yet" in place of the commit button. A0 never builds a signable action, never reserves a nonce, never writes an intent, and never calls an order or exchange endpoint (acceptance item VR-24 in the spec).

How to see it working: run the focused test build (Concrete Steps) and watch the seven-order fixture from spec section 13.2 reproduce prices 76.4/74/71.6/69.2 and 86/88.4/90.8 with sizes 7/9/11/13 and 10/10/10; then run the Playwright spec `tools/playwright/test/vol-range.spec.mjs`, which seeds that fixture through the debug bridge, switches the ticket to Volatility range, checks the totals, opens Review, and asserts the footer text and that no order request was made.

## Context References

Public refs:
- Direct user request in this session (2026-10-21): build Release A0 of the Vol Range spec through a workflow, following the approved design canvas (ticket, review, and mobile artboards) and the spec's PR 1-3 sequence.

Repo artifacts:
- Product spec: `/hyperopen/docs/product-specs/vol-range-implementation-spec.md` (HO-SPEC-014 v2.1). Sections in scope: 1.2, 1.5, 4, 5.1-5.5, 6.1-6.7, 7.1-7.2, 11.1, 13.1-13.3, 14.1 (VR-01..VR-05, VR-22..VR-24), 15.1 PRs 1-3.
- Trading boundary rules: `/hyperopen/src/hyperopen/trading/BOUNDARY.md`.
- Planning contract: `/hyperopen/docs/PLANS.md`.

Local scratch refs (non-authoritative):
- None.

## Progress

- [x] (2026-10-21) Read spec sections in scope and the order-form, trading-domain, chart, runtime-registration, and lint seams; wrote this ExecPlan; added the spec to `/hyperopen/docs/product-specs/index.md`.
- [x] (2026-10-21) Milestone 1: shared exact precision (`domain/trading/precision.cljs`), versioned contracts (`vol_range/contracts.cljs`), and the focused `:vol-range-test` build.
- [x] (2026-10-21) Milestone 2: daily volatility (`vol_range/domain/volatility.cljs`) and anchor/tilt geometry plus lot allocation (`vol_range/domain/grid.cljs`).
- [x] (2026-10-21) Milestone 3: fee capability (`vol_range/domain/fees.cljs`), admission (`vol_range/domain/admission.cljs`), planner and remediations (`vol_range/application/planner.cljs`, `remediations.cljs`).
- [x] (2026-10-21) Milestone 4: order-form state, scoped drafts, snapshots, projections, transitions, actions and command registration (`trading/vol_range_draft.cljs`, `vol_range_transitions.cljs`, `vol_range/application/{snapshots,draft_scope,plan_state,ticket_model,review_model,display,projections}.cljs`, `vol_range/actions.cljs`, `schema/vol_range_contracts.cljs`, `runtime/collaborators/vol_range.cljs`, `schema/contracts/vol_range_action_args.cljs`, `views/trade/vol_range/commands.cljs`). Focused build 867 tests / 0 failures, full `test` build 7341 tests / 0 failures; `lint:namespace-sizes`, `lint:input-parsing` and `lint:namespace-boundaries` green.
- [x] (2026-10-21) Milestone 5: ticket view, review screen, VM contracts, memoized state slice (`views/trade/vol_range/{primitives,model_section,inventory_section,side_cards,outcomes,ticket,review}.cljs`; wiring in `order_form_{view,vm,handlers,type_extensions}.cljs`, `schema/order_form_contracts.cljs`, `trade_view.cljs`; tests `views/trade/vol_range/{ticket,review}_test.cljs`, `views/trade_view/vol_range_slice_repaint_test.cljs`, extended `order_form_vm_test.cljs`). Focused build 882 tests / 0 failures, app build 0 warnings; `lint:namespace-sizes`, `lint:hiccup`, `lint:class-attrs`, `lint:style-keys`, `lint:theme-colors`, `lint:input-parsing`, `lint:namespace-boundaries` green.
- [x] (2026-10-21) Milestone 5 verifier fixes: spectate stop affordance now renders in Volatility range mode (view derives it from the ticket's `:read-only?` because the submit reason there is always `:vol-range-preview-only`) and the ticket shows a read-only notice; review shows the market base symbol (`Size (SYN)`, `Inventory bounds (SYN)`) via new review key `:base-symbol`; identity strip derives the market-type label from the snapshot and an ineligible market (e.g. perp) renders only the identity strip and the eligibility reason; the volatility-unavailable message offers "Refresh daily candles" (`commands/refresh-daily-candles`); review label and title pluralize ("Review 1 order"). Focused build 891 tests / 0 failures; the five UI lints green.
- [x] (2026-10-21) Milestone 6: chart overlays, chart VM/runtime wiring, debug fixture, Playwright spec, full gates (see Outcomes & Retrospective and Artifacts). New: `views/trading_chart/utils/vol_range_overlay_model.cljs`, `chart_interop/vol_range_overlays.cljs`, `telemetry/console_preload/vol_range_fixture.cljs`, `tools/playwright/test/vol-range.spec.mjs`, two unit test namespaces; wiring in `chart_interop.cljs`, `runtime.cljs` (495 lines), `vm.cljs`, `core.cljs`, `trade_view.cljs`, `theme_colors.cljs`.
- [ ] Release A remaining work (out of A0 scope): owner review and PR of this branch, journal/nonce coordination, lifecycle, dock tab, Portfolio report, and the optimizer smoke failures noted below if the owner wants them cleared before merge.

## Surprises & Discoveries

- Observation: `normalize-scale-form` in `src/hyperopen/trading/order_form_state.cljs` rebuilds the scale map from only `:start :end :count :skew`, so any new key under `:scale` is silently dropped on every `order-form-draft` read.
  Evidence: the function body returns a literal four-key map.
- Observation: `:order-form-ui` has an exact-key spec (`::order-form-ui-state` in `src/hyperopen/schema/contracts/state.cljs` requires keys equal to `order-form-ui-state-keys`) and a formal ownership conformance suite, so adding a nested `:vol-range` map there would ripple through the formal contracts.
- Observation: the order form is not written to browser storage at all today; `persist-order-form` only strips deprecated keys during in-memory transitions. On asset switch, `asset_selector/actions.cljs` keeps the form and clears only price and size.
- Observation: several files the feature must touch are exactly at their namespace-size exception ceiling: `schema/contracts/action_args.cljs` (754/754), `runtime/effect_order_contract.cljs` (752/752), `state/trading.cljs` (730/730), `asset_selector/actions.cljs` (658/658), `asset_selector/markets.cljs` (1349/1349), `order/actions.cljs` (595/595), `telemetry/console_preload.cljs` (552/552). `views/trading_chart/runtime.cljs` is 497 lines against the 500 default.
- Observation: existing price/size helpers use binary floats (`Math.floor(size*10^d)`, `Math.log`-based significant figures, `.toFixed`), so `0.29` base can floor to `0.28` and `80*(1-0.75*0.06)` may not equal `76.4`. No BigInt or decimal library exists in `domain/trading`.
- Observation: `api/projections/market.cljs` `apply-candle-snapshot-success` merges fetched rows by timestamp into `[:candles coin interval]`, so a 16-bar daily fetch does not destroy the chart's longer daily history.
- Observation: spot market entries built in `asset_selector/markets.cljs` carry `:volume24h` but not base volume; the active spot asset context websocket (`websocket/active_asset_ctx.cljs`, channel `activeSpotAssetCtx`) receives `dayNtlVlm` and `dayBaseVlm` but keeps only a parsed float of the former.
- Observation: account abstraction is normalized in `api/endpoints/account/portfolio.cljs` (`unifiedAccount` and `portfolioMargin` both become `:unified`; unknown becomes `:classic`), and the raw value is not retained.
- Observation: under the new `:vol-range-test` build, `hyperopen.views.trading-chart.core-test` fails 6 assertions (overlay identity reuse and a cancel-order message) even when run alone, while the full `test` build is the intended home for it. It presumably relies on runner-level requires such as `hyperopen.trading-crypto.module`. The build regex excludes it via `views\\.trading-chart\\.(?!core-test$).*`; the focused build baseline is 0 failures, 0 errors.
- Observation: `divide` collides with `cljs.core/divide`, so `precision.cljs` uses `(:refer-clojure :exclude [divide])`. BigInt exponentiation is built from decimal strings (no `**`) to avoid transpilation to `Math.pow`.
- Observation: Milestone 2 brief said the excursion fixture for mid 75.92 is "-1.7", but (75.92/80 - 1)/0.06 = -0.85 (matches spec 5.5 text "-0.85 sigma"); the test asserts -0.85. Sigma distances are computed as one exact division (price - base)/(base*v) rather than two chained roundings.
- Observation: `planner/prepare-plan` must embed remediations while `remediations/offers` must re-run the planner, which is a namespace cycle. The calculation therefore lives in `vol_range/application/plan_core.cljs` (`prepare-plan-core`, no remediations); `remediations` re-runs the core and `planner/prepare-plan` is the thin public entry that adds offers.
- Observation: spec 13.2 totals reproduce exactly (2,888 / 2,652 gross, VWAP 72.2 / 88.4, outcomes 190/120/160, gap 16.2 = 22.4377%, fee 1.1552, headroom 10.8448). Manual 6.00 percent resolves to the 12-place string "0.060000000000" in admission's resolved map.

- Observation: the Playwright interactive config starts `npm run dev:browser-inspection` on port 8080, which another worktree's shadow-cljs was already holding. Verification used a dev `compile app` build served by `tools/playwright/static_server.mjs` on port 4180 (`PLAYWRIGHT_BASE_URL`, `PLAYWRIGHT_WEB_PORT`, `PLAYWRIGHT_WEB_SERVER_COMMAND`); no other checkout was touched.
- Observation: the synthetic fixture wallet triggers real account bootstrap requests, and app startup selects BTC asynchronously, so a late `userFees`/`spotClearinghouseState` response or default-market selection can overwrite the seeded state. The spec routes `**/info` for the fixture address to the seeded balances and fee rates and re-seeds until the fixture market sticks.
- Observation: multi-arity `order-form-vm` (added in Milestone 5) made existing `with-redefs` of the single-arity function throw `arity$1 is not a function` in two `console-preload` tests (`npm test` 2 errors). `order-form-vm` is single-arity again and the clocked variant is `order-form-vm-at`.
- Observation: the review dialog did not receive focus when opened, so Escape (handled on the dialog) did nothing from the page; the dialog now focuses itself on mount (`:replicant/on-render`).

## Decision Log

- Decision: Implement exact arithmetic in a new shared namespace `hyperopen.domain.trading.precision` using `js/BigInt` scaled integers (`{:units BigInt :scale int}`), and leave every existing helper (`canonical-order-price-string`, `base-size-string`, `floor-size-to-decimals`) unchanged.
  Rationale: spec 2.2 and 6.1 forbid epsilon-before-floor; spec 6.2 says to add explicit side-aware behavior rather than silently change existing sell-price semantics. Changing shared helpers would alter ordinary order behavior.
  Date/Author: 2026-10-21, Claude (planning agent).
- Decision: The precision helpers require an explicit `szDecimals`; they never default it. A Vol Range market with no `szDecimals` is unavailable.
  Rationale: existing code defaults missing size decimals to 4 in `market.cljs` and to 8 in `core.cljs`; a silent default would mis-size real orders.
  Date/Author: 2026-10-21, Claude.
- Decision: Order geometry is computed with exact decimal arithmetic. Inventory delta and shifted anchor are rounded half-up at 20 decimal places; each raw rung price is formed from an exact numerator with a single directional division by `K-1` (floor for buys, ceil for sells) at 20 places, then quantized to the legal price set in the same direction.
  Rationale: floor/ceil at 20 places followed by floor/ceil to at most 8 places equals the exact floor/ceil of the rational value, so fixtures such as `80*(1-0.75*0.06) = 76.4` stay exact.
  Date/Author: 2026-10-21, Claude.
- Decision: Lot allocation uses exact rational weights with BigInt numerators: for skew `k` with `s` decimal places, weight numerator `W_i = (K-1)*10^s + (k-1)*10^s*i`; `x_i = N*W_i / sum(W)`; floor, then distribute leftover lots by largest exact remainder, ties to the lower rung index.
  Rationale: reproduces spec 13.2 (13/7 skew gives 7:9:11:13) and 13.3 (0.34/0.33/0.33) without floating error.
  Date/Author: 2026-10-21, Claude.
- Decision: Vol Range UI flags (`:show-bands?`, `:advanced-open?`, `:review-open?`) and the frozen review live under a new top-level `:vol-range` root (`[:vol-range :ui ...]`, `[:vol-range :frozen-review]`), not under `:order-form-ui`.
  Rationale: spec 7.1 puts feature runtime under a new `:vol-range` root; `:order-form-ui` has an exact-key spec and formal conformance suite that would otherwise need changes unrelated to A0.
  Date/Author: 2026-10-21, Claude.
- Decision: `normalize-scale-form` preserves `:mode` and `:vol-range` only when present (normalizing them), and `default-order-form` is unchanged. Readers use `hyperopen.trading.vol-range-draft/scale-mode`, which maps missing or unknown modes to `:price-range`.
  Rationale: a legacy Scale form then normalizes to exactly the same map as before (VR-01, "Legacy ordinary Scale behaves identically") and no existing exact-equality test of the default form changes.
  Date/Author: 2026-10-21, Claude.
- Decision: Scoped drafts are kept in memory in A0: `[:vol-range :draft-binding]` records the scope key the form's draft belongs to, and `[:vol-range :drafts <scope-key>]` stores the account- and market-specific values of other scopes. Reads resolve the effective draft for the current scope; every Vol Range write first rebinds. IndexedDB persistence of scoped drafts is deferred to the Release A storage foundation (spec PR 4). The unscoped browser-export projection is implemented as a pure, tested function.
  Rationale: the order form is not persisted to browser storage today, so there is nothing to leak; IndexedDB work belongs with the journal's transaction-completion primitive. Read-time resolution avoids editing `asset_selector/actions.cljs`, which is at its size ceiling.
  Date/Author: 2026-10-21, Claude.
- Decision: A blank protected core or target resolves at plan time to the current confirmed spot inventory (shown with a "current inventory" hint); maximum has no implicit value.
  Rationale: spec 4.1 says to initialize core and target to current inventory; resolving at plan time avoids writing a value before balances load.
  Date/Author: 2026-10-21, Claude.
- Decision: The fee capability carries an explicit per-side settlement token. The live adapter for Hyperliquid spot uses "fee is charged in the token received" (buy fee in base, sell fee in quote) and marks it `:settlement-verified? false`, which adds the warning `:capability/fee-settlement-unverified`. Tests use the spec's synthetic profile (4 bps in USDC on buys). Percent to fraction conversion happens exactly once, in `hyperopen.vol-range.domain.fees/percent->fraction`. The generic fallback fee quote (`fees/default-fee-quote`) makes the plan unavailable.
  Rationale: spec 6.5 requires explicit conversion and forbids the display fallback for cash admission; the live settlement currency is not asserted by any repo fixture.
  Date/Author: 2026-10-21, Claude.
- Decision: In A0, planner status `:ready` means preview-admissible only. There is no execution flag path; `hyperopen.config` gains `:vol-range {:preview-enabled? true}` and nothing reads an execution flag.
  Rationale: VR-24 must hold regardless of flag combinations, so the safest A0 has no execution code at all.
  Date/Author: 2026-10-21, Claude.
- Decision: Daily candles reuse the existing `:effects/fetch-candle-snapshot` with `:interval :1d :bars 16` and are read from `[:candles coin :1d]`. No new effect is added.
  Rationale: the existing effect is registered, contracted, and merges rows by timestamp.
  Date/Author: 2026-10-21, Claude.
- Decision: 24h VWAP reads raw `dayNtlVlm` and `dayBaseVlm` strings that `websocket/active_asset_ctx.cljs` additionally stores as `:dayNtlVlmRaw` and `:dayBaseVlmRaw`. `asset_selector/markets.cljs` is not changed.
  Rationale: spec 5.3 needs same-market quote and base volume; markets.cljs is at its size ceiling.
  Date/Author: 2026-10-21, Claude.
- Decision: The normalized `[:account :mode]` value `:unified` blocks the plan with `:capability/account-mode-unsupported`; any other value adds the warning `:capability/raw-account-mode-unverified`. Raw-mode retention is deferred to Release A.
  Rationale: the raw abstraction value is discarded by existing normalization (spec 2.2 item 6); A0 places nothing.
  Date/Author: 2026-10-21, Claude.
- Decision: Existing order exposure includes orders that are optimistically pending cancel (read the open-order projection with no pending-cancel filter).
  Rationale: spec 2.2 item 5 says optimistic cancel hiding is not a cancellation barrier.
  Date/Author: 2026-10-21, Claude.
- Decision: Raise namespace-size ceilings only where a one- or two-line registration is unavoidable (`action_args.cljs` +2, `effect_order_contract.cljs` for two policies, `console_preload.cljs` +2), each with an updated reason. Keep `views/trading_chart/runtime.cljs` under 500 by moving its overlay-clear calls into one `chart-interop/clear-chart-overlays!` helper. All new namespaces stay under 500 lines.
  Rationale: the registries are central by design; growing them by a line is cheaper than a parallel registration path.
  Date/Author: 2026-10-21, Claude.
- Decision: Time enters only at boundaries. Domain and planner functions take `now-ms`; actions follow the repository pattern of a public arity calling `(.now js/Date)` and a pure arity taking `now-ms`; view-model reads take `now-ms` floored to 60 000 ms so memoization stays stable.
  Rationale: spec 6.7 requires injected time and identities.
  Date/Author: 2026-10-21, Claude.
- Decision: The shared submit policy (`src/hyperopen/trading/submit_policy.cljs`) gains a first-precedence reason `:vol-range-preview-only` for a Scale form whose mode is `:volatility-range`, and it skips the request builder in that case, so the ordinary `:actions/submit-order` path (including Enter in a field) can never build or send an order from a Vol Range draft.
  Rationale: VR-24 must hold even if a stale ordinary Scale start/end remains in the form; the guard lives in the one shared policy rather than in `order/actions.cljs`, which is at its size ceiling.
  Date/Author: 2026-10-21, Claude.
- Decision: Set maximum (all buys filled) and Use plan total are evaluated jointly when both the maximum and the USDC cap are blank: each is offered if the plan with that value, or with both one-click values applied, is `:ready`. Every other remediation is offered only if the plan with that patch alone is `:ready`.
  Rationale: a fresh draft has both fields blank, so evaluating each alone would never offer either and the user could not use the one-click path the spec requires.
  Date/Author: 2026-10-21, Claude.
- Decision: The A0 Review shows the plan timestamp ("Plan as of HH:MM:SS UTC") instead of the 60-second expiry countdown, and its footer shows "Placement isn't available yet" with a "Back to edit" button and no commit control.
  Rationale: expiry governs placement, which A0 does not have (spec 1.2, 4.4).
  Date/Author: 2026-10-21, Claude.
- Decision: Add a focused shadow-cljs build `:vol-range-test` (`:target :node-test`, `:ns-regexp` over the Vol Range, precision, order-form, and nearby trading test namespaces) and npm script `test:vol-range`.
  Rationale: the full `npm test` compiles every namespace; implementers need a fast loop. `:ws-test` is the precedent.
  Date/Author: 2026-10-21, Claude.
- Decision: Workflow model routing for this plan: financial milestones 1-3 (precision, volatility/geometry/allocation, fees/admission/planner) are implemented and verified on opus; milestones 4-6 are implemented on sonnet with opus adversarial review of VR-24 and the state seams; a sonnet task that fails verification twice is retried on opus.
  Rationale: the user's routing rule sends financial logic and anything that would propagate unverified to opus.
  Date/Author: 2026-10-21, Claude.

- Observation: `:effects/save-many` path vectors must contain only keywords (`::common/state-path`), so scope-key strings and coin strings cannot appear in a path. Scoped drafts are therefore written as the whole `[:vol-range :drafts]` map (keyed by scope-key string), and the daily-fetch record as the whole `[:vol-range :daily-fetch]` map (keyed by coin); reads still use `get-in` with the string key.
- Observation: `trading/order-form-draft` derives the effective order type from the entry mode, so a Scale form is only really `:scale` when the entry mode is `:pro`; Vol Range visibility and the submit-policy guard therefore read the normalized type, and test fixtures set `:order-form-ui :entry-mode :pro`.
- Observation: the two Vol Range effect-order policies and the `:vol-range-preview-only` submit reason are now modeled in the Lean corpus (`spec/lean/Hyperopen/Formal/EffectOrderContract.lean` via `projectionHeavyPolicy`, `TradingSubmitPolicy.lean`), synced with `npm run formal:sync` and confirmed by `npm run formal:verify`; the conformance test compares the full covered-action set again. Frozen review is also invalidated when the scope key changes (`draft-scope/frozen-review-current?`).
- Observation: counting existing open orders exactly as the chart does requires `hyperopen.views.account-info.projections`, a view namespace, so `snapshots.cljs` has an entry in `dev/namespace_boundary_exceptions.edn` (retire by extracting the normalization to a non-view seam).
- Observation: `runtime_registration/trade.cljs` is 182 lines (under the 500 default), so it needs no size-exception entry for the two added policy ids; `action_args.cljs` (+2, ceiling 756) and `effect_order_contract.cljs` (+12, ceiling 764) were raised by exactly the added lines.
- Decision: The frozen review stores the plan unchanged at `[:vol-range :frozen-review]`; the book at plan time and the freeze time live beside it at `[:vol-range :frozen-context]` ({:bbo :frozen-at-ms}) because `::plan-result` carries no BBO or clock.
  Date/Author: 2026-10-21, Claude.
- Decision: `plan-for-state` floors `now-ms` to the minute before building snapshots and memoizes on [draft, scope, market, account, fees, capabilities, minute]; `auto-volatility` is memoized separately on rows identity, coin and UTC day.
  Date/Author: 2026-10-21, Claude.
- Decision: Added one action, `:actions/handle-vol-range-review-keydown` (key-args, Escape closes review, every other key is a no-op), with its command, catalog row and collaborator entry, so Escape on the dialog element works without a global listener or a view-side key filter.
  Rationale: Replicant `:on` handlers are action vectors only; close-review alone would close on any key.
  Date/Author: 2026-10-21, Claude (Milestone 5).
- Decision: Mode-control eligibility is read at the view boundary (`projections/eligibility-for-state`, new public fn in `ticket_model.cljs`) rather than as a VM key, because the ticket model is empty while the ticket is not visible (Price range) yet the control must still disable Volatility range with the reason. The review model is likewise read at the boundary only while review is open. The VM keeps exactly the approved new keys (:scale-mode, :vol-range, controls :show-vol-range?).
  Date/Author: 2026-10-21, Claude (Milestone 5).
- Decision: The nothing-to-sell hint is a `<label for>` of the Protected core input (native focus, no JS); the rung stepper steps through a fixed 1-12 list instead of parsing the count string; Back to edit uses the `autofocus` attribute.
  Date/Author: 2026-10-21, Claude (Milestone 5).
- Decision: `dev/hiccup_lint.clj` parse options gained an `:auto-resolve` placeholder (as `delimiter_preflight.clj` already has) because `::leg` in `vol_range/contracts.cljs` made `lint:hiccup` and `lint:class-attrs` throw.
  Date/Author: 2026-10-21, Claude (Milestone 5).
- Decision: The order-form memo slice adds `:orders`, `:vol-range`, `:portfolio {:user-fees}`, `:perp-dex-fee-config-by-name`, `:ui {:theme :locale}` and `:candles {active-coin {:1d}}` (only in Volatility range mode); the planner reads orders and fee config, so they must reach the slice.
  Date/Author: 2026-10-21, Claude (Milestone 5).

- Decision: The submit-policy reason order is unchanged (vol-range before spectate, pinned by the formal vectors and Lean model); the order-form view instead ORs in the ticket's `:read-only?` (same source, `mutations-blocked-message`) to show the Stop Spectate affordance in Volatility range mode.
  Date/Author: 2026-10-21, Claude (Milestone 5 fixes).
- Decision: On an ineligible market the ticket stays visible in Volatility range mode but renders an explicit ineligible notice instead of the plan sections (rather than silently falling back to Price range, which would contradict the selected mode). Daily candles for a coin switched to while already in Volatility range are fetched via a "Refresh daily candles" button in the unavailable message rather than an asset-change hook (kept out of the shared asset-selection path).
  Date/Author: 2026-10-21, Claude (Milestone 5 fixes).

- Decision: Chart overlays are plain absolutely positioned DOM (no SVG) in a single `pointer-events-none` root with its own `js/WeakMap` sidecar, rebuilt on each repaint (a handful of nodes), repainted through a dynamic rAF hook on visible-range, size and data changes, and skipped when the chart model is `identical?`. Colors come only from `theme-colors/token-rgb` over `--ho-buy`, `--ho-sell`, `--ho-warn` (fallbacks added in the exempt `theme_colors.cljs`); `restyle-chart-theme!` re-renders the overlay so a `data-theme` change recolors it.
  Date/Author: 2026-10-21, Claude (Milestone 6).
- Decision: `trade-chart-view-state` assocs `:vol-range-chart` (`projections/chart-model state (.now js/Date)`, nil outside Volatility range mode); the chart VM forwards it as `:vol-range-overlay` runtime option and the existing decoration pass syncs it, so draft edits update decorations without remount triggers. `unmount-chart!` calls one `ci/clear-chart-overlays!`.
  Date/Author: 2026-10-21, Claude (Milestone 6).

## Outcomes & Retrospective

Status (2026-10-21, final workflow synthesis): Release A0 is built but is NOT ready to merge. Milestone 5 failed verification twice (the open defect is listed below). Nothing is committed.

Delivered: exact precision and legal price quantization (`domain/trading/precision.cljs`), versioned contracts, 14-day daily Parkinson volatility, anchors, the v2.1 inventory tilt, bands, lot allocation, post-only/distance/notional checks with remediations, fee reserves, admission including corrective plans, and the pure planner (M1-M3). Also: Scale-mode Price range / Volatility range with nested scoped drafts, the ticket, the read-only review ("Placement isn't available yet"), chart overlays, the debug fixture and `tools/playwright/test/vol-range.spec.mjs` (M4-M6). After the final review: a 1d-candle freshness rule (success stamp, UTC rollover, 15-minute TTL, `:data/volatility-stale`) and a store watcher that refetches stale series (`vol_range/application/freshness.cljs`, `vol_range/infrastructure/freshness_watcher.cljs`). The preview is now gated by `config/VOL_RANGE_PREVIEW`, a goog-define that is off in release and on in dev, test and Playwright builds. With the flag off, Scale renders exactly as on main (`preview_gate_test.cljs`). VR-24 is covered by `a0_boundary_test` and by the Playwright suite, which asserts zero `/exchange` requests.

Gates: `npm run gates` 34/34 PASS (8177 tests, 47225 assertions, 0 failures). After the review fixes, `npm test` ran 7389 tests, 43748 assertions, 0 failures. The focused `vol-range-test` build and the boundary, size, input-parsing and hiccup lints are green. The Playwright spec is not part of `npm run gates` and was not re-run after the review fixes. `hyperopen.views.trading-chart.core-test` is excluded from the focused build (6 failures under that build composition; the full build passes). Pre-existing smoke failures outside A0 remain: three optimizer tests, plus intermittent `trade-regressions.spec.mjs:3717` (both reproduced on merge base 3e71ffcfc).

Open, blocking: the order-form slice in `views/trade_view.cljs` has no `:account-context`. In the real app, spectate mode does not make the ticket read-only, and a selected subaccount gets the master account's spot balances in inventory, cash admission and the review.

Open, non-blocking: `vol-range-view-state` adds user fees to every order type, which changes the legacy Fees row (VR-01). When both Maximum and the USDC cap are blank, Set maximum and Use plan total are offered as single-field patches (VR-23 deviation). Remediations are recomputed on every BBO tick. Provenance timestamps come from the render clock, so BBO, VWAP and fee-quote staleness is never detected. The scope key can collide across market types and networks (the network is hardcoded to mainnet). Account-mode capability is not an allow-list. Tilt with a blank maximum substitutes q. The 25/50/100% shortcuts ignore the base-fee bound. The starting breach and the lot-size suggestion are not shown. Corrective plans do not surface conflicting existing commitments. The `::leg` and `::plan-result` specs are loose. Accessibility gaps: the track role=img, live-region noise, no aria-invalid, and no focus trap or focus return in the review. `test/test_runner_generated.cljs` must be regenerated before commit.

## Context and Orientation

HyperOpen is a ClojureScript single-page trading client for the Hyperliquid exchange. Source lives under `/hyperopen/src/hyperopen/`, tests under `/hyperopen/test/hyperopen/`. Views are Replicant hiccup (plain vectors such as `[:div {:class ["flex" "gap-2"]} ...]`; class attributes must be vectors of strings, never a space-separated string, and style keys must be keywords). State is one atom; user interactions dispatch action vectors such as `[:actions/update-order-form [:scale :start] [:event.target/value]]`; action handlers are pure functions of state that return effect vectors such as `[[:effects/save-many [[path value] ...]]]`, and effect interpreters do the I/O.

Terms used in this plan:

- Ladder: the set of Vol Range orders. Internally called a grid; never show "grid" to users.
- Rung: one order in a ladder. Each side has 1 to 12 rungs.
- Anchor (`A`): the reference price the ladder is built around.
- Daily volatility (`v`): a daily fraction, for example `0.06` means 6%. Stored as `:daily-fraction`.
- Sigma distance (`z`): a positive multiple of `v`; a buy at `z = 0.75` is priced `r*(1 - 0.75*v)`.
- Shifted anchor (`r`): the anchor after optional inventory tilt.
- Lot: the smallest base-size increment, `10^-szDecimals`.
- Legal price: a price the exchange accepts: positive; at most 5 significant figures unless it is an integer; at most `8 - szDecimals` decimal places for spot (`6 - szDecimals` for perps).
- BBO: best bid and best offer from the order book.
- Scope: the identity a draft belongs to, `[network account-address spot-pair-index]`.
- Snapshot: a plain map of the market, account, or fee facts the planner needs, built from app state by a pure function.

Key existing files and what they do:

- `src/hyperopen/trading/order_form_state.cljs`: order-form defaults and `normalize-scale-form` / `normalize-order-form`.
- `src/hyperopen/state/trading.cljs`: facade. `order-form-draft` composes `:order-form` with UI-owned keys and normalizes; `persist-order-form` strips deprecated keys. At its size ceiling; do not add lines.
- `src/hyperopen/trading/order_form_transitions.cljs`: generic `update-order-form` and the private `localized-numeric-order-form-paths` set. At its size ceiling.
- `src/hyperopen/trading/order_form_ownership.cljs`: `enforce-field-ownership` splits a transition into `:order-form`, `:order-form-ui`, `:order-form-runtime`.
- `src/hyperopen/order/actions.cljs`: action handlers that turn transitions into `[:effects/save-many ...]`. At its size ceiling.
- `src/hyperopen/schema/order_form_command_catalog.cljs`: rows `{:command-id :order-form/x :action-id :actions/x :handler-key :x}` registered into runtime via `schema/runtime_registration/trade.cljs`.
- `src/hyperopen/runtime/collaborators.cljs` and `runtime/collaborators/*.cljs`: map handler keys to functions; `runtime/registry_composition.cljs` flattens them and rejects duplicates.
- `src/hyperopen/schema/contracts/action_args.cljs`: one arg spec per action id; `schema/contracts.cljs` throws at load if registered and contracted action ids differ. Feature maps (for example `hyperevm_action_args.cljs`) are merged at the bottom.
- `src/hyperopen/runtime/effect_order_contract.cljs`: per-action effect ordering policies; ids listed in `trade.cljs` `effect-order-policy-required-action-ids` must have one.
- `src/hyperopen/schema/order_form_contracts.cljs`: exact-key VM contracts for `order_form_vm.cljs`.
- `src/hyperopen/views/trade/order_form_view.cljs`, `order_form_type_extensions.cljs`, `order_form_vm.cljs`, `order_form_handlers.cljs`, `order_form_commands.cljs`: the ticket.
- `src/hyperopen/views/trade_view.cljs`: memoized panels; `order-form-view-state-keys` and `trade-chart-view-state` decide what state each panel sees. A state change outside those slices does not repaint the panel.
- `src/hyperopen/views/trading_chart/vm.cljs`, `core.cljs`, `runtime.cljs`, `utils/chart_interop.cljs`, `utils/chart_interop/open_order_overlays.cljs`: chart view model, canvas component, decoration pass (`apply-chart-decorations!` runs in an animation frame), and DOM overlay pattern.
- `src/hyperopen/views/trading_chart/utils/theme_colors.cljs`: `token` reads `--ho-chart-*` CSS properties.
- `src/hyperopen/domain/trading/fees.cljs`: `quote-fees` returns display percentages (`0.04` means 0.04%).
- `src/hyperopen/state/trading/fee_context.cljs`: `select-fee-context state` gives the inputs for `quote-fees`.
- `src/hyperopen/api/projections/market.cljs`: candle rows stored at `[:candles coin interval]` either as a vector or a map with `:rows`; rows are raw exchange maps with keys `:t :T :s :i :o :c :h :l :v :n` (values may be strings or numbers).
- `src/hyperopen/utils/parse.cljs`: `normalize-localized-decimal-input value locale` returns a canonical decimal string or nil; this is the locale-aware input boundary.
- `src/hyperopen/account/context.cljs`: `active-trading-account-address`, `effective-account-address`, spectate checks.
- Spot balances: `[:spot :clearinghouse-state :balances]` entries `{:coin :token :total :hold}`; for a selected owned subaccount, `state/trading.cljs` private `trading-context` substitutes the subaccount row's spot state. Spot metadata: `[:spot :meta]` with `:universe` (pairs, `:tokens [base quote]`, `:index`) and `:tokens`. Order book: `[:orderbooks coin]` with `:bids`/`:asks` levels carrying `:px` strings. Normalized account mode: `[:account :mode]`. User fees: `[:portfolio :user-fees]`.

New namespaces this plan creates (all under 500 lines):

- `src/hyperopen/domain/trading/precision.cljs`: exact decimals, lots, legal prices.
- `src/hyperopen/vol_range/contracts.cljs`: versions, product policy table, capability matrix, error registry, scope key, plan/leg specs.
- `src/hyperopen/vol_range/domain/volatility.cljs`, `grid.cljs`, `fees.cljs`, `admission.cljs`.
- `src/hyperopen/vol_range/application/planner.cljs`, `remediations.cljs`, `snapshots.cljs`, `draft_scope.cljs`, `projections.cljs`.
- `src/hyperopen/trading/vol_range_draft.cljs`, `src/hyperopen/trading/vol_range_transitions.cljs`.
- `src/hyperopen/vol_range/actions.cljs`, `src/hyperopen/runtime/collaborators/vol_range.cljs`, `src/hyperopen/schema/contracts/vol_range_action_args.cljs`, `src/hyperopen/schema/vol_range_contracts.cljs`.
- `src/hyperopen/views/trade/vol_range/` (ticket sections, side cards, review, commands).
- `src/hyperopen/views/trading_chart/utils/vol_range_overlay_model.cljs`, `src/hyperopen/views/trading_chart/utils/chart_interop/vol_range_overlays.cljs`.
- `src/hyperopen/telemetry/console_preload/vol_range_fixture.cljs` and `tools/playwright/test/vol-range.spec.mjs`.

## Plan of Work

Milestone 1 creates the arithmetic every later step depends on. `hyperopen.domain.trading.precision` represents a decimal as `{:units <BigInt> :scale <non-negative int>}` and provides strict parsing of canonical decimal strings (no exponents, no locale), exact formatting, add/subtract/multiply/compare, quantize and divide with explicit `:floor`, `:ceil`, `:half-up` and `:down` modes, conversion of a finite JS number through its shortest round-trip string (used only at the fee and estimator boundaries), lot conversion that rejects non-multiples with suggested valid quantities, and side-aware legal-price quantization (`:buy` gives the greatest legal price at or below the raw price, `:sell` the least legal price at or above it, rechecking legality after a magnitude change). `hyperopen.vol-range.contracts` records model version 1, precision version 1, estimator `"parkinson-v1"`, draft schema version 1, the product policy table from spec 4.1 and 6.3-6.4, the capability matrix (spot only, USDC quote, preview enabled, no execution), the typed error registry, the scope key, and cljs.spec contracts for legs and plan results. A focused `:vol-range-test` build is added. The milestone ends when the spec 13.3 precision fixtures pass and existing price/size tests still pass.

Milestone 2 adds the model. `volatility.cljs` selects exactly the 14 completed UTC days before the current UTC day, normalizes candle rows into typed decimals, rejects missing, invalid or conflicting days without substitution, computes the Parkinson estimate in floating point, and canonicalizes it to a 12-place decimal fraction rounded half up; it also converts manual percent input exactly once and finds the current UTC day's open. `grid.cljs` computes the inventory delta and shifted anchor (spec 5.4), the reference bands and excursion in sigma (5.5), exact raw rung prices and their legal quantization, the actual sigma distance of each rounded price, and largest-remainder lot allocation (6.1). The milestone ends when spec 13.1 volatility/tilt fixtures, the 13.2 leg vector, and the 13.3 allocation fixtures pass.

Milestone 3 adds admission and the planner. `fees.cljs` is the one place display percentages become fractions and turns a fee quote into a capability with per-side settlement tokens and conservative reserve bounds. `admission.cljs` validates inputs against the policy table, checks every leg against the BBO (post-only), the other side (self-cross), duplicate same-side prices, the 50% product distance guard, and the 10.50 USDC minimum leg notional, computes all-buys-first, all-sells-first and everything-fills inventory outcomes, and applies the spec 6.6 hard constraints including corrective plans. `planner.cljs` exposes `prepare-plan` with the spec 6.7 result contract; `remediations.cljs` evaluates Anchor to market price, Start buys/sells at X sigma, Use fewer rungs, Reduce rung count, Widen range, Set maximum (all buys filled) and Use plan total by re-running the whole planner and offering only those that produce `:ready`. The milestone ends when the 13.2 totals (2,888 and 2,652 USDC gross, VWAPs 72.20 and 88.40, outcomes 190/120/160), the synthetic fee fixture (1.1552, 2,889.1552, 10.8448), the crossing remediation fixture (Start buys at 0.90 sigma), and the maximum-breach fixture pass.

Milestone 4 connects the planner to state. The order form keeps a nested Scale mode and Vol Range draft that survive normalization, ownership enforcement and save-many writes; drafts are bound to a scope and restored per scope; pure snapshot builders read market, account and fee facts from app state; projections produce stable ticket, review and chart models; transitions parse numeric input at the locale boundary; actions are registered through the order-form command catalog, a new collaborator map, action-arg contracts, and effect-order policies. Selecting Volatility range fetches 16 daily candles through the existing effect. The milestone ends with VR-01, VR-23 and VR-24 tests passing.

Milestone 5 renders the ticket and Review per the approved design and extends the order-form VM and its exact-key contracts, and the order-form panel's memoized state slice. Milestone 6 draws the chart overlays through a paired sync/clear interop namespace threaded through the chart VM, decoration pass, theme refresh and unmount path, adds the debug-bridge fixture and Playwright spec, and runs the full gates.

## Concrete Steps

All commands run from the worktree root (`/hyperopen` in repo-root notation).

    npm run setup:worktree
    npx shadow-cljs --force-spawn compile vol-range-test && node out/vol-range-test.js

Expected tail of a passing focused run (counts grow with each milestone):

    Ran N tests containing M assertions.
    0 failures, 0 errors.

A single namespace can be run with shadow's node-test filter, for example:

    node out/vol-range-test.js --test=hyperopen.domain.trading.precision-test

Lints that each milestone must keep green:

    npm run lint:namespace-sizes
    npm run lint:input-parsing
    npm run lint:hiccup
    npm run lint:class-attrs
    npm run lint:style-keys
    npm run lint:theme-colors
    npm run lint:docs

Final milestone:

    npm run test:runner:generate
    npx playwright test tools/playwright/test/vol-range.spec.mjs
    npm run gates

Milestone 6 verification when port 8080 is held by another checkout (build once, serve statically):

    npm run css:build && npx shadow-cljs --force-spawn compile app portfolio-worker portfolio-optimizer-worker vault-detail-worker
    PLAYWRIGHT_BASE_URL=http://127.0.0.1:4180 PLAYWRIGHT_WEB_PORT=4180 \
      PLAYWRIGHT_WEB_SERVER_COMMAND="node tools/playwright/static_server.mjs" \
      npx playwright test tools/playwright/test/vol-range.spec.mjs
    (same environment) npm run test:playwright:smoke
    npm run browser:cleanup

Milestone 6 PASS/FAIL matrix (2026-10-21):

    PASS  npx shadow-cljs --force-spawn compile vol-range-test && node out/vol-range-test.js   900 tests, 0 failures
    PASS  npm run lint:namespace-sizes / lint:theme-colors / lint:class-attrs / lint:style-keys
    PASS  npm run test:runner:generate (1028 namespaces)
    PASS  npm test   7374 tests, 43664 assertions, 0 failures, 0 errors (after the order-form-vm arity fix; 2 errors before it)
    PASS  npm run gates   34/34, 8176 tests, 47220 assertions (includes check, test, test:websocket)
    PASS  npx playwright test tools/playwright/test/vol-range.spec.mjs   5 passed in each of two full runs
    FAIL  npm run test:playwright:smoke   43 passed, 3 failed (final run): the three optimizer smoke tests. Baseline evidence below shows they fail identically on the merge base.
    PASS  baseline comparison (merge base 3e71ffcfc in a detached temporary worktree, same static build and command): optimizer-view-model-routes.smoke.spec.mjs:1167, :1530, :1581 fail 3/3 there too (not caused by this branch); trade-regressions.spec.mjs:3717 is flaky on the merge base as well (3 failures in 8 runs with --repeat-each=8 --workers=1; on this branch 2 in 8, and it passed in the final full smoke run), failing on the exchange simulator seeing no order, so it is pre-existing timing flakiness rather than a Vol Range regression.
    PASS  npm run browser:cleanup   no sessions left

Review-fix follow-up (2026-10-21): overlay root is now sized to the main price pane (paneSize(0)) so labels sit left of the price axis and nothing paints over lower indicator panes; the Draft stage label moved to the pane top-right and labels got a chart-background token fill; `projections/stable-chart-model` returns the previous model when value-equal so unchanged plans skip overlay repaints; the Playwright spec now asserts rung sizes, side totals, VWAPs, outcomes, the quoted VWAP gap and label containment in the pane.

## Validation and Acceptance

Acceptance is behavioral and fixture-driven:

- Precision (spec 13.3): base `0.29` with 2 size decimals is exactly 29 lots; spot raw `0.0001234` with size decimals 1 stays `0.0001234` both ways; perp raw `0.0001234` with size decimals 0 gives buy `0.000123` and sell `0.000124`; `79.254` stays; `79.2546` gives buy `79.254` and sell `79.255`; `81129.4` gives `81129` and `81130`; `123456` stays legal; `9999.96` gives buy `9999.9` and sell `10000`.
- Allocation (13.3): `Q=1`, lot `0.01`, three equal weights gives `0.34, 0.33, 0.33`; `Q=0.02` over three rungs is invalid with a zero-size-rung error.
- Volatility (13.1): fourteen days of high 103 and low 97 give raw `0.03604448820648275` (within 1e-15) and canonical `0.036044488206`; at 2026-10-19 14:00 UTC the window is October 5-18; a missing October 12 makes auto unavailable even if October 4 exists; one invalid high/low string makes auto unavailable.
- Tilt (13.1): `A=80, v=0.06, q=150, target=160, max=190`: tilt off gives `80`; with core 120 and strength 0.45 gives delta `-0.25` and shifted anchor `80.54`; `q=190` gives delta `+1` and `77.84`; `q=170, target=max=170` gives delta 0; `q=171, max=170` gives `+1`.
- Seven-order fixture (13.2): buys `76.4x7, 74x9, 71.6x11, 69.2x13`, sells `86x10, 88.4x10, 90.8x10`; buys 40 base, 2,888 USDC gross, VWAP 72.20; sells 30 base, 2,652 gross, VWAP 88.40; outcomes 190/120/160; Quoted VWAP gap 16.20. With 4 bps USDC buy fees: fee 1.1552, maximum debit 2,889.1552, cap headroom 10.8448.
- Crossing (13.1): the same inputs with BBO 75.89/75.91 are invalid, name "Buy at 76.4 is above the best ask 75.91", and offer Anchor to market price and Start buys at 0.90 sigma (0.85 still crosses at 75.92).
- Breach (13.1): `q=190, max=200`, buys of 50 are rejected for the maximum; asks cannot offset.
- Notional (13.3): a 0.11 rung at 100 passes (11 >= 10.50); at 50 it fails (5.5).
- VR-01: a legacy Scale form normalizes byte-identically; a Vol Range draft survives `order-form-draft`, `enforce-field-ownership`, and the `save-many` path values; scoped restore returns the right draft per scope; the export projection blanks scoped quantities and limits.
- VR-23: Set maximum yields 190 in the 13.2 fixture without a maximum; Use plan total yields `2889.16` (rounded up to the cent); crossing remediations set exactly `[:anchor :mode] :market-price` or `[:buy :start-sigma] "0.90"`; none is offered unless the re-plan is `:ready`.
- VR-24: every Vol Range action, evaluated over fixture states with every flag combination, emits only `:effects/save`, `:effects/save-many`, or `:effects/fetch-candle-snapshot`; no Vol Range source file requires order, signing, nonce, exchange, or IndexedDB namespaces; the Review footer renders "Placement isn't available yet" and no commit control.
- Browser: the Playwright spec seeds the fixture, switches to Volatility range, sees "Review 7 orders", opens Review, sees all seven legs and "Placement isn't available yet", returns to edit, and asserts no request to the exchange endpoint was issued.

## Idempotence and Recovery

Every step is additive. New namespaces and tests can be rewritten safely; reruns of the focused build are idempotent. If a registration edit (catalog row, collaborator map, arg spec, effect-order policy) is incomplete, the app or test build fails at load with an explicit "drift detected" error naming the action id; fix the missing registration and rerun. Do not hand-edit `test/test_runner_generated.cljs`; regenerate it with `npm run test:runner:generate`. If a namespace-size ceiling is exceeded, prefer moving code into the new feature namespaces over raising the ceiling.

## Artifacts and Notes

Spec 6.7 planner result sketch, which `prepare-plan` must satisfy (keys are exact; values here illustrate shapes):

    {:status :ready            ; or :invalid / :unavailable
     :plan-id "vr-plan-..."
     :scope {...}
     :input-fingerprint "vr1-..."
     :model {...}
     :legs [{:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4" :size "7"
             :notional "534.8" :actual-sigma "-0.75"}]
     :totals {...}
     :inventory-outcomes {...}
     :fee-reserves {...}
     :source-versions {...}
     :remediations []
     :errors []
     :warnings []}

## Interfaces and Dependencies

No new third-party libraries. `js/BigInt` is available in all supported browsers and in Node. The interfaces below must exist at the end of the named milestone; later milestones may add arities but not change these contracts.

Milestone 1, `hyperopen.domain.trading.precision`:

    (parse-decimal s) -> dec | nil
    (number->decimal x) -> dec | nil
    (decimal->string d) -> "clean string"
    (add a b) (sub a b) (mul a b) (neg a) (cmp a b) (zero-dec? a) (pos-dec? a) (neg-dec? a)
    (quantize d scale mode) ; mode in #{:floor :ceil :half-up :down}
    (divide a b scale mode)
    (decimal->lots d sz-decimals) -> {:lots BigInt} | {:error :not-lot-multiple :suggested-down "..." :suggested-up "..."}
    (lots->decimal lots sz-decimals) -> dec
    (max-price-decimals market-type sz-decimals) -> int
    (legal-price? d {:market-type :sz-decimals}) -> boolean
    (quantize-price raw side {:market-type :sz-decimals}) -> dec | nil

Milestone 1, `hyperopen.vol-range.contracts`: `model-version`, `precision-version`, `estimator-version`, `draft-schema-version`, `product-policy`, `capability-matrix`, `error-registry`, `(error code data)`, `(status-for-errors errors)`, `(scope-key scope)`, specs `::leg` and `::plan-result`, `(plan-result-valid? plan)`.

Milestone 2: `volatility/completed-window`, `volatility/select-window`, `volatility/parkinson-daily`, `volatility/manual-daily-fraction`, `volatility/resolve-volatility`, `volatility/utc-open-anchor`; `grid/inventory-delta`, `grid/shifted-anchor`, `grid/bands`, `grid/excursion-sigma`, `grid/raw-rung-price`, `grid/side-prices`, `grid/actual-sigma`, `grid/allocate-lots`, `grid/build-side-legs`.

Milestone 3: `fees/percent->fraction`, `fees/fee-capability`, `fees/fee-reserves`; `admission/validate-inputs`, `admission/check-legs`, `admission/inventory-outcomes`, `admission/check-bounds`, `admission/corrective-mode`; `planner/prepare-plan`; `remediations/offers`, `remediations/apply-draft-patch`.

Milestone 4: `vol-range-draft/default-vol-range-draft`, `vol-range-draft/scale-mode`, `vol-range-draft/normalize-vol-range-draft`, `vol-range-draft/unscoped-export-projection`; `draft-scope/current-scope`, `draft-scope/effective-draft`, `draft-scope/rebind-path-values`; `snapshots/market-snapshot`, `snapshots/account-snapshot`, `snapshots/fee-snapshot`; `projections/plan-for-state`, `projections/ticket-model`, `projections/review-model`, `projections/chart-model`; the actions listed in Milestone 4's task.

Revision note (2026-10-21): initial version written from the spec and a read of the current branch; records the size-ceiling, ownership, and persistence discoveries that shaped the decisions above.

- Decision: `:set-maximum-all-buys` is offered when the maximum is blank or below N; `:use-plan-total` is offered when the USDC cap is blank or below the plan's maximum buy debit (not merely "differs"), so a `:ready` plan has no offers. Offered patches are single-field; when both fields are blank the joint plan is the readiness test.
  Rationale: spec 4.3 says "cap differs" but a ready 13.2 plan (cap 2900) must not be offered a lower cap; offering only when the cap is missing or insufficient keeps offers actionable.
  Date/Author: 2026-10-21, Claude.
- Decision: Blank protected core / target resolve to current inventory in the planner and the core <= target <= maximum order is re-checked after resolution, so a blank core with inventory above the explicit maximum reports :input/inventory-limits-order (the user sets core explicitly for corrective sell-only plans). Maximum blank skips the maximum-bound check but still raises :input/maximum-required when buys are enabled.
  Date/Author: 2026-10-21, Claude.
- Decision: `pending-intents` is accepted and fingerprinted but not yet counted in bounds (A0 has no journal); existing exposure comes only from the account snapshot's open orders (including those pending cancel), counted once.
  Date/Author: 2026-10-21, Claude.
- Decision: A negative maker fee yields zero debit bounds and a base credit bound only when settled in base; a base-settled positive buy fee is a reserve amount but not a base debit bound (only the sell-side base fee debits sellable base). The planner's fingerprint excludes plan-id, clock and the remediations flag.
  Date/Author: 2026-10-21, Claude.

## Follow-up: review findings closed (2026-10-27)

All ten A0 review findings were fixed with regression tests, then checked by an independent Opus review whose four major findings were also fixed:

- Source times are real: book venue timestamp, active-asset-context receipt time (kept in `[:market-observations ...]`, outside `:active-assets` so memoized panels stay value-equal), and the fee quote load time. Provenance is excluded from the plan fingerprint and every memo key, and is captured at review freeze; the review shows each source's age and a stale flag against spec 7.3. Placement-time enforcement belongs to Release A admission.
- The remediation search is memoized; the key includes the live book whenever offers depend on it, and an offer is re-checked against the live book when applied (VR-23).
- Admission uses the raw `userAbstraction` value against the allowlist; missing is unavailable, anything else is unsupported.
- Tilt requires a real maximum; sell shortcuts subtract the base fee bound; corrective plans show their starting breach; existing commitments that deepen a breach are surfaced; lot errors carry side, field and a suggested quantity.
- Scope keys include market type and dex. `::leg` requires canonical strings (zero sizes only as diagnostics of an invalid plan); plan status must agree with its errors.
- Accessibility: readable inventory track, one always-mounted polite status instead of per-card alerts, `aria-invalid`/`aria-describedby` on inputs, shared dialog focus trap and focus return for the review.

Known A0 limits kept: the book time is the last visual change, the fee quote is not refreshed on a timer, and the raw account mode is the effective account's (spectate is read-only).

Validation: `npm test` 7404 tests 0 failures; `npm run gates` 34/34 PASS. The Playwright spec could not run because port 8080 was held by another local dev server.
