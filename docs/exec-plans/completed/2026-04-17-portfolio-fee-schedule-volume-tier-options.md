# Extend the portfolio fee schedule volume tier options

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/AGENTS.md`, `/hyperopen/docs/FRONTEND.md`, and `/hyperopen/docs/BROWSER_TESTING.md`.

Tracked issue: `hyperopen-g4c3` ("Extend portfolio fee schedule volume tier options").

## Purpose / Big Picture

After this change, the `Volume Tier` market-type dropdown in the portfolio fee schedule popover will contain the full set of market variants shown in the current product reference: spot variants, core perps, and HIP-3 perp variants with growth mode and aligned quote adjustments. A user will be able to open `/portfolio`, click `View Fee Schedule`, open `Market Type`, select each supported option, and see the fee tier table update without guessing whether HIP-3 markets use the same rates as core perps.

This matters because the current implementation intentionally stopped at core perps and spot variants. That is no longer enough for users trading builder-deployed perps: HIP-3 fees depend on a deployer fee scale and can be reduced by growth mode or aligned quote collateral. The observable success case is that selecting `HIP-3 Perps + Growth mode` while the active market is an HIP-3 growth market shows a compact selected row with `Active market: <symbol>`, and the displayed taker/maker rates are derived from the active market's deployer fee scale rather than a hardcoded static table.

## Progress

- [x] (2026-04-17 15:34Z) Inspected the existing portfolio fee schedule model, popover view, action tests, and Playwright regression.
- [x] (2026-04-17 15:36Z) Confirmed that `hyperopen.domain.trading.fees` already contains the core protocol formula for stable pairs, HIP-3 deployer fee scale, growth mode, and aligned quote adjustments.
- [x] (2026-04-17 15:38Z) Cross-checked the fee logic against the official Hyperliquid fee documentation and aligned quote documentation.
- [x] (2026-04-17 15:39Z) Created tracked issue `hyperopen-g4c3` for the implementation work.
- [x] (2026-04-17 15:47Z) Added failing focused CLJS and Playwright expectations for the nine market-type options, the initial disabled-HIP3 assumption, active HIP-3 context, and shared protocol fee helper. This disabled-HIP3 assumption was superseded later by `/Users/barry/.codex/worktrees/09e3/hyperopen/docs/exec-plans/completed/2026-04-17-portfolio-fee-schedule-hip3-default-preview.md`.
- [x] (2026-04-17 15:52Z) Implemented pure fee schedule market-option modeling for the expanded dropdown.
- [x] (2026-04-17 15:53Z) Exposed `hyperopen.domain.trading.fees/adjust-percentage-rates` and refactored `quote-fees` to reuse the shared protocol adjustment formula.
- [x] (2026-04-17 15:57Z) Updated action, view, component, model, and Playwright coverage for selectable HIP-3 variants and disabled unavailable options.
- [x] (2026-04-17 16:10Z) Ran focused tests, deterministic browser regression, governed portfolio design review across the required widths, and required repo gates.

## Surprises & Discoveries

- Observation: the portfolio fee schedule model currently defines five market-type options only: `:perps`, `:spot`, `:spot-stable-pair`, `:spot-aligned-quote`, and `:spot-aligned-stable-pair`.
  Evidence: `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/fee_schedule.cljs` defines `market-type-options` and `apply-market-type` with those five cases.

- Observation: the existing trading fee domain already knows the correct HIP-3 formula, including the two branches for deployer fee scale.
  Evidence: `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/domain/trading/fees.cljs` computes `scaleIfHip3` equivalent behavior as `deployerFeeScale + 1` when scale is below `1`, and `2 * deployerFeeScale` when scale is `1` or above. It also applies `0.1` growth-mode scaling and aligned-quote taker/rebate adjustments.

- Observation: active-market state already carries enough data to know whether the current market is HIP-3, growth mode, and aligned quote, and another state map carries deployer fee scale by DEX name.
  Evidence: `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/state/trading/fee_context.cljs` reads `:active-market :dex`, `:active-market :growth-mode?`, `:active-market :growthMode`, `:active-market :quote`, and `[:perp-dex-fee-config-by-name dex :deployer-fee-scale]`.

- Observation: the current portfolio model applies referral, staking, and maker rebate what-if scenarios directly to displayed table rows, while the shared trading fee formula accepts user fee rates from the `userFees` endpoint.
  Evidence: `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/fee_schedule.cljs` applies `apply-staking-tier`, `apply-maker-rebate-tier`, `apply-market-type`, and `apply-referral-discount` to static percentage rows before formatting.

- Observation: Playwright state injection for this feature must satisfy the app state schema, including `:active-market :symbol`.
  Evidence: the first HIP-3 browser regression run failed during `reset!` with app-state validation because the injected `:active-market` had `:coin` but no `:symbol`. Adding `:symbol "WTIOIL-USDC"` fixed the fixture without bypassing validation.

- Observation: the existing Shadow CLJS watcher in this worktree occupied `127.0.0.1:8080`, so focused Playwright could not use the default config's managed `webServer`.
  Evidence: `lsof -nP -iTCP:8080 -sTCP:LISTEN` reported Java PID `73163` rooted at `/Users/barry/.codex/worktrees/09e3/hyperopen`. The focused regression used an ignored temporary Playwright config that reused the already-running dev server.

- Observation: the governed design-review runner can lose its CDP session when asked to inspect all widths through a reused session, but the failure is isolated to browser-inspection capture rather than the app route.
  Evidence: the combined run `design-review-2026-04-17T16-05-13-660Z-f37109e1` passed `review-375` and `review-1280`, then failed `review-768` and `review-1440` with `Session with given id not found` / `CDP client is not connected`. Fresh one-viewport sessions passed the missing widths.

- Observation: the pure fee schedule namespace crossed the 500-line repository threshold after adding HIP-3 scenario modeling.
  Evidence: `npm run check` stopped at `lint:namespace-sizes` with `src/hyperopen/portfolio/fee_schedule.cljs - namespace has 653 lines`; `dev/namespace_size_exceptions.edn` now records a capped exception with a follow-up split rationale.

## Decision Log

- Decision: implement the full dropdown options in the same `Market Type` selector rather than adding a second HIP-3 selector.
  Rationale: the user's reference shows a single dropdown that owns the full volume-tier scenario. Keeping one selector preserves the compact popover and avoids making users combine separate toggles mentally.
  Date/Author: 2026-04-17 / Codex

- Decision: rename the dropdown label for core validator-operated perps to `Core Perps`, while keeping `:perps` and `"Perps"` as accepted aliases for compatibility.
  Rationale: the screenshot distinguishes `Core Perps` from `HIP-3 Perps`. Existing app state and tests can keep using `:perps`; only the display label needs to become more precise.
  Date/Author: 2026-04-17 / Codex

- Decision: make HIP-3 rows depend on active-market fee context and avoid inventing a generic HIP-3 fee scale.
  Rationale: Hyperliquid's current fee formula requires `deployerFeeScale`, and that value is market-specific. If the portfolio route has no active HIP-3 deployer fee scale, this plan originally marked the HIP-3 options unavailable.
  Superseded: Direct reference inspection later showed the product behavior uses default `deployerFeeScale 1.0` for no-active-context HIP-3 previews. The correction is tracked in `/Users/barry/.codex/worktrees/09e3/hyperopen/docs/exec-plans/completed/2026-04-17-portfolio-fee-schedule-hip3-default-preview.md`.
  Date/Author: 2026-04-17 / Codex

- Decision: extract or expose the protocol row-adjustment algorithm from `hyperopen.domain.trading.fees` instead of duplicating HIP-3 math in the portfolio namespace.
  Rationale: order entry already depends on this formula for fee previews. The portfolio schedule should consume the same pure algorithm so future protocol changes have one obvious owner.
  Date/Author: 2026-04-17 / Codex

## Outcomes & Retrospective

Implementation is complete for the original volume-tier expansion. The portfolio fee schedule exposes all nine market-type scenarios and derives HIP-3 table rows from the active market's deployer fee scale through the same protocol fee adjustment helper used by order fee quotes. A follow-up plan corrects the original no-active-context behavior so HIP-3 variants remain selectable with default `deployerFeeScale 1.0` instead of being disabled.

Validation passed:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.domain.trading.fees-test --test=hyperopen.portfolio.fee-schedule-test --test=hyperopen.views.portfolio.fee-schedule-test --test=hyperopen.views.portfolio-view-fee-schedule-test --test=hyperopen.portfolio.actions-test
    npx shadow-cljs --force-spawn compile app
    npx playwright test -c tmp/playwright/reuse-dev-server.config.mjs tools/playwright/test/portfolio-regressions.spec.mjs --grep "portfolio fee schedule" --workers=1
    npm run qa:design-ui -- --targets portfolio-route --session-id sess-1776441910002-43686c
    npm run qa:design-ui -- --targets portfolio-route --viewports review-768 --session-id sess-1776442004022-9e6ea2
    npm run qa:design-ui -- --targets portfolio-route --viewports review-1440 --session-id sess-1776442041926-80093a
    npm run browser:cleanup
    git diff --check
    npm run check
    npm test
    npm run test:websocket

The combined design-review run completed `review-375` and `review-1280` with every required pass green, then hit a browser-inspection CDP disconnect for `review-768` and `review-1440`. Fresh one-viewport reruns covered those missing widths with `reviewOutcome: "PASS"`. Across the completed width coverage, visual-evidence-captured, native-control, styling-consistency, interaction, layout-regression, and jank-perf are all PASS. The residual blind spots are the standard sampled-state notes that hover, active, disabled, and loading states require targeted route actions when absent by default; this feature's targeted Playwright regression covers the fee schedule open/close, hover-row compactness, and HIP-3 selection states.

The main follow-up is a cleanup split for `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/fee_schedule.cljs`: move market-option context derivation into a narrower pure helper namespace before the namespace-size exception retires.

## Product Specification

The `Volume Tier` dropdown in the fee schedule popover must show these options in this order:

- `Spot`
- `Spot + Aligned Quote`
- `Spot + Stable Pair`
- `Spot + Aligned Quote + Stable Pair`
- `Core Perps`
- `HIP-3 Perps`
- `HIP-3 Perps + Growth mode`
- `HIP-3 Perps + Aligned Quote`
- `HIP-3 Perps + Growth mode + Aligned Quote`

The existing option value `:perps` remains the internal value for core validator-operated perps. The display label changes to `Core Perps`, and `normalize-market-type` must still accept `:perps`, `"perps"`, and `"Perps"` as aliases. New internal option values should be:

- `:spot`
- `:spot-aligned-quote`
- `:spot-stable-pair`
- `:spot-aligned-stable-pair`
- `:perps`
- `:hip3-perps`
- `:hip3-perps-growth-mode`
- `:hip3-perps-aligned-quote`
- `:hip3-perps-growth-mode-aligned-quote`

Each dropdown option is a compact one-line row. The left side shows the option label. The right side shows status only when useful. If the option matches the current active market's inferred fee scenario, show `Active market: <symbol>` in the Hyperopen accent color. The symbol should prefer `:active-market :base`, then `:active-market :coin`, then `:active-asset`, with any namespace prefix removed for display when possible. For example, `testdex:WTIOIL` should display `WTIOIL`.

For non-HIP-3 options, the current static behavior remains: rows are based on the core perps or spot table and then adjusted by selected referral, staking, maker rebate, stable-pair, and aligned-quote scenario flags.

For HIP-3 options, the table uses the perps base table, selected referral, selected staking, selected maker rebate, and the active market's deployer fee scale. The option itself decides whether growth mode and aligned quote are applied:

- `HIP-3 Perps`: `growth-mode? false`, `aligned-quote? false`
- `HIP-3 Perps + Growth mode`: `growth-mode? true`, `aligned-quote? false`
- `HIP-3 Perps + Aligned Quote`: `growth-mode? false`, `aligned-quote? true`
- `HIP-3 Perps + Growth mode + Aligned Quote`: `growth-mode? true`, `aligned-quote? true`

The active market's actual `:growth-mode?` and aligned quote status are used only to mark which option is the active-market row. They must not prevent users from previewing the other HIP-3 scenarios when a deployer fee scale is available.

When no active HIP-3 deployer fee scale is available, later reference inspection showed all four HIP-3 options should remain visible and selectable, using default `deployerFeeScale 1.0` for preview rows. The originally documented disabled behavior is superseded by `/Users/barry/.codex/worktrees/09e3/hyperopen/docs/exec-plans/completed/2026-04-17-portfolio-fee-schedule-hip3-default-preview.md`.

The selected control value should fit on one line. Long labels can truncate inside the control, but the menu option rows should keep the main label and status readable at the current popover width. The dropdown must preserve the existing hover highlight and opaque surface requirements from the current fee schedule popover.

The rate note below the table must change from `HIP-3 deployer adjustments not included` to copy that reflects the new behavior. Use:

`* Rates reflect selected scenarios, market type, and HIP-3 deployer context`

If the selected HIP-3 option is unavailable and the model falls back to `Core Perps`, the table should show `Core Perps` and the disabled option helper should explain why HIP-3 was not applied.

## Fee Logic Specification

All fee schedule rows use percentage units in the portfolio model. For example, `0.045` means `0.045%`.

Start from the existing base tables in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/fee_schedule.cljs`:

- Core perps tier 0 through tier 6: `0.045/0.015`, `0.040/0.012`, `0.035/0.008`, `0.030/0.004`, `0.028/0`, `0.026/0`, `0.024/0`.
- Spot tier 0 through tier 6: `0.070/0.040`, `0.060/0.030`, `0.050/0.020`, `0.040/0.010`, `0.035/0`, `0.030/0`, `0.025/0`.

Apply selected staking tier as a discount to positive taker and positive maker rates. Negative maker rebates are not reduced by staking.

Apply selected maker rebate tier by replacing the maker rate with the selected negative maker value, for example `:tier-2` becomes `-0.002`.

Apply market-type adjustments using the shared protocol formula described below.

Apply selected referral discount as a discount to positive taker and positive maker rates. Negative maker rebates are not reduced by referral.

For spot stable pairs, multiply taker and maker by `0.2`. This represents the documented 80% reduction for spot pairs between two spot quote assets.

For aligned quote assets, multiply taker by `0.8`. For maker rebates, aligned quote improves negative maker rebates by 50%, so multiply negative maker rates by `1.5` when there is no HIP-3 deployer share. Positive maker fees are not improved by aligned quote.

For HIP-3 perps, use the active market's `deployerFeeScale`. Define:

    hip3-positive-scale = if deployerFeeScale < 1 then deployerFeeScale + 1 else deployerFeeScale * 2
    deployer-share = if deployerFeeScale < 1 then deployerFeeScale / (1 + deployerFeeScale) else 0.5
    growth-scale = if growth-mode? then 0.1 else 1
    aligned-taker-scale = (1 - deployer-share) * 0.8 + deployer-share
    aligned-maker-rebate-scale = (1 - deployer-share) * 1.5 + deployer-share

For HIP-3 taker rows:

    taker = base-taker * hip3-positive-scale * growth-scale
    if aligned-quote? then taker = taker * aligned-taker-scale

For HIP-3 positive maker rows:

    maker = base-maker * hip3-positive-scale * growth-scale

For HIP-3 zero maker rows:

    maker = 0

For HIP-3 negative maker rebate rows:

    maker = selected-negative-maker-rate * growth-scale
    if aligned-quote? then maker = maker * aligned-maker-rebate-scale

This matches the official Hyperliquid fee formula while preserving the existing portfolio popover's what-if controls for referral, staking, and maker rebate.

Use the existing `format-rate` behavior: `0` renders as `0%`; non-zero values render with up to four decimal places and trailing zeros trimmed. With `deployerFeeScale 0.5`, tier 0 should produce these representative HIP-3 results before referral/staking discounts:

- `HIP-3 Perps`: taker `0.0675%`, maker `0.0225%`
- `HIP-3 Perps + Growth mode`: taker `0.0068%`, maker `0.0023%`
- `HIP-3 Perps + Aligned Quote`: taker `0.0585%`, maker `0.0225%`
- `HIP-3 Perps + Growth mode + Aligned Quote`: taker `0.0059%`, maker `0.0023%`

## Context and Orientation

The fee schedule pure model lives in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/fee_schedule.cljs`. It owns market option normalization, static base tables, scenario dropdown options, row formatting, and the `fee-schedule-model` consumed by the view.

The popover Hiccup view lives in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/fee_schedule.cljs`. It renders the prepared model, including selector rows, the tier table, close controls, and the documentation link. This plan should not move fee math into this view.

The trading fee formula lives in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/domain/trading/fees.cljs`. `quote-fees` currently consumes raw `userFees` endpoint rates, but its internal algorithm is the source of truth for stable-pair, HIP-3, growth-mode, and aligned-quote adjustments. This plan should extract a reusable helper from that namespace so portfolio schedule rows and order summary fee previews share the same protocol math.

Active market fee context is derived in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/state/trading/fee_context.cljs`. The same information is present in app state on the portfolio route: `:active-market` contains the market type, DEX name, quote, growth-mode flags, and display symbol; `:perp-dex-fee-config-by-name` contains deployer fee scale keyed by DEX.

Portfolio actions live in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/actions.cljs`. The market selector action already normalizes and stores `[:portfolio-ui :fee-schedule-market-type]`; the implementation should extend normalization rather than add a new action.

The main deterministic tests to update are `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/portfolio/fee_schedule_test.cljs`, `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/domain/trading/fees_test.cljs`, `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/views/portfolio/fee_schedule_test.cljs`, `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/views/portfolio_view_fee_schedule_test.cljs`, and `/Users/barry/.codex/worktrees/09e3/hyperopen/tools/playwright/test/portfolio-regressions.spec.mjs`.

## Plan of Work

First, add a reusable protocol adjustment helper in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/domain/trading/fees.cljs`. The helper should accept percentage-unit rates and market adjustment flags, then return percentage-unit rates. A concrete public signature is:

    (adjust-percentage-rates
      {:taker 0.045 :maker 0.015}
      {:market-type :perp
       :stable-pair? false
       :deployer-fee-scale 0.5
       :growth-mode? true
       :extra-adjustment? false})

For this example it should return taker near `0.00675` and maker near `0.00225`. Refactor `quote-fees` to call this helper after converting raw endpoint rates to percentage units, preserving the existing public `quote-fees` behavior and tests.

Second, extend `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/domain/trading/fees_test.cljs` before implementation. Add tests that prove `adjust-percentage-rates` handles `deployer-fee-scale 0.5`, growth mode, aligned quote, and negative maker rebates. Include one test with `deployer-fee-scale 1.2` so the `>= 1` branch remains covered.

Third, extend `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/fee_schedule.cljs`. Update `market-type-options`, aliases, and `normalize-market-type` for all nine options. Add helpers that derive an active fee context from app state without importing view namespaces. It is acceptable to import `hyperopen.state.trading.fee-context` if the namespace boundary check allows it; if it fails, copy only the small context derivation needed here and record that surprise in this plan. The model should expose each market option with `:disabled?`, `:selected?`, `:current?`, `:current-label`, and optional `:helper` so the existing selector renderer can stay simple.

Fourth, update `fee-schedule-rows` to accept market context. Keep the one-argument arity for tests and compatibility, defaulting to no HIP-3 context. The two-argument arity should accept selected scenario and active-market context:

    (fee-schedule-rows selected-market-type
                       {:referral-discount :none
                        :staking-tier :none
                        :maker-rebate-tier :none
                        :active-fee-context {:deployer-fee-scale 0.5
                                             :active-market-symbol "WTIOIL"}})

If selected type is HIP-3 and `:deployer-fee-scale` is absent, the function should return core perps rows and the model should normalize the selected label back to `Core Perps`.

Fifth, update `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/fee_schedule.cljs` so selector rows support disabled options and one-line helper/status text. Disabled rows must be focus-safe and click-safe: no selection action should dispatch for disabled options. Hover highlighting remains only for enabled options. The selected control should display the selected label and preserve existing chevron behavior.

Sixth, update actions and tests. `select-portfolio-fee-schedule-market-type` in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/actions.cljs` can keep the same signature, but tests should prove it accepts new labels such as `"HIP-3 Perps + Growth mode"` and persists the normalized keyword. A later follow-up removed the unavailable-HIP3 fallback from the model and uses default `deployerFeeScale 1.0` for no-active-context previews.

Seventh, update deterministic browser coverage in `/Users/barry/.codex/worktrees/09e3/hyperopen/tools/playwright/test/portfolio-regressions.spec.mjs`. The existing fee schedule regression should open the market dropdown and assert all nine option labels are present. Add a state setup step through the debug bridge that injects an active HIP-3 market and deployer fee scale, select `HIP-3 Perps + Growth mode`, and assert the tier 0 row contains the expected growth-mode HIP-3 values for the injected scale. Also assert the selected option row includes `Active market: WTIOIL`.

Eighth, update documentation notes in `/Users/barry/.codex/worktrees/09e3/hyperopen/docs/exec-plans/completed/2026-04-17-portfolio-fee-schedule-popover.md` or add a short revision note if implementation changes earlier non-goals. The old plan explicitly listed HIP-3 variants as a non-goal; after this plan is implemented, that historical gap should point to this new plan and outcome.

## Concrete Steps

All commands should run from `/Users/barry/.codex/worktrees/09e3/hyperopen`.

Begin with the focused tests that should fail after writing the new expectations:

    npm run test:runner:generate
    npx shadow-cljs --force-spawn compile test
    node out/test.js --test=hyperopen.domain.trading.fees-test --test=hyperopen.portfolio.fee-schedule-test --test=hyperopen.views.portfolio.fee-schedule-test --test=hyperopen.views.portfolio-view-fee-schedule-test --test=hyperopen.portfolio.actions-test

Expected before implementation: new assertions fail because the new market options and `adjust-percentage-rates` helper do not exist yet.

After the focused CLJS tests pass, compile the app and run the focused browser regression:

    npx shadow-cljs --force-spawn compile app
    npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "portfolio fee schedule" --workers=1

Expected after implementation: the fee schedule regression passes, the dropdown shows all nine labels, and the HIP-3 growth-mode scenario updates tier 0 using the injected deployer scale.

Run the governed design review because this changes a UI flow under `/hyperopen/src/hyperopen/views/**`:

    npm run qa:design-ui -- --targets portfolio-route --manage-local-app
    npm run browser:cleanup

Expected after implementation: the design review reports `reviewOutcome: "PASS"` or any issues are fixed before completion. If it reports residual sampled-state blind spots only, record them in this plan and final handoff.

Run the required repository gates:

    npm run check
    npm test
    npm run test:websocket
    git diff --check

Expected after implementation: all commands exit `0`. `npm test` may log the existing `storage-unavailable` preview-cache message while still reporting `0 failures, 0 errors`; that log alone is not a failure.

## Validation and Acceptance

A user on `/portfolio` can open the fee schedule popover, open the `Market Type` dropdown, and see exactly the nine option labels listed in the Product Specification section.

With no active HIP-3 deployer fee scale in state, the four HIP-3 options remain visible but disabled. They do not dispatch selection, they expose `aria-disabled "true"`, and they show helper copy explaining that an HIP-3 market is required for deployer-fee previews.

With active-market state representing an HIP-3 market named `WTIOIL` and `deployerFeeScale 0.5`, selecting `HIP-3 Perps + Growth mode` updates tier 0 to `0.0068% / 0.0023%` before referral/staking adjustments. The selected menu row displays `Active market: WTIOIL`.

With the same active HIP-3 context, selecting `HIP-3 Perps + Growth mode + Aligned Quote` updates tier 0 taker to `0.0059%` before referral/staking adjustments. If a maker rebate tier is selected, aligned quote improves the negative maker rebate according to the `aligned-maker-rebate-scale` formula.

Existing behavior remains intact: `Spot + Aligned Quote + Stable Pair` tier 0 still renders `0.0112% / 0.008%`; the popover remains anchored near the trigger; the panel and dropdown surfaces remain opaque; close button, backdrop click, `Escape`, and focus restoration continue to work.

## Idempotence and Recovery

All planned source changes are additive or local refactors. Re-running the focused tests and browser regression is safe. If the shared fee helper refactor causes order-summary tests to fail, revert only the `quote-fees` refactor and keep the new helper isolated until its behavior exactly matches existing `quote-fees` tests. Do not change the public shape of `quote-fees`.

If importing `hyperopen.state.trading.fee-context` from `hyperopen.portfolio.fee-schedule` violates namespace boundaries, do not add a boundary exception as the first move. Instead, keep a tiny local active-market context reader in the portfolio namespace and add a note in `Surprises & Discoveries`. A later cleanup can move shared context normalization to a neutral namespace if duplication becomes meaningful.

If Playwright starts against a stale watcher on port `8080`, run `lsof -nP -iTCP:8080 -sTCP:LISTEN` to identify whether the listener belongs to this worktree. Do not kill unrelated worktree processes. Prefer the managed Playwright server or a temporary static-server config only when necessary, and delete temporary config files before committing.

## Interfaces and Dependencies

The following public functions must exist after implementation:

    hyperopen.domain.trading.fees/adjust-percentage-rates
    hyperopen.portfolio.fee-schedule/market-type-options
    hyperopen.portfolio.fee-schedule/normalize-market-type
    hyperopen.portfolio.fee-schedule/fee-schedule-rows
    hyperopen.portfolio.fee-schedule/fee-schedule-model

The popover view must keep these stable data roles:

    portfolio-fee-schedule-dialog
    portfolio-fee-schedule-market-trigger
    portfolio-fee-schedule-market-menu
    portfolio-fee-schedule-market-option-spot
    portfolio-fee-schedule-market-option-spot-aligned-quote
    portfolio-fee-schedule-market-option-spot-stable-pair
    portfolio-fee-schedule-market-option-spot-aligned-stable-pair
    portfolio-fee-schedule-market-option-perps
    portfolio-fee-schedule-market-option-hip3-perps
    portfolio-fee-schedule-market-option-hip3-perps-growth-mode
    portfolio-fee-schedule-market-option-hip3-perps-aligned-quote
    portfolio-fee-schedule-market-option-hip3-perps-growth-mode-aligned-quote
    portfolio-fee-schedule-tier-0

No new network requests are required. The feature uses market metadata already present in app state.

## Source Notes

The protocol facts embedded in this plan come from the official Hyperliquid fee documentation and aligned quote documentation as checked on 2026-04-17. The fee documentation states that perps and spot have separate schedules, spot volume counts double for tiering, HIP-3 growth mode reduces protocol fees and rebates by 90%, HIP-3 deployers configure an additional fee share, spot stable pairs have 80% lower fees, and aligned quote assets have lower taker fees and better maker rebates. The developer formula on that page gives the deployer fee scale, growth mode, stable pair, referral, and aligned quote calculations used above.

Revision note: 2026-04-17 15:40Z. Created this active ExecPlan after the user asked whether the screenshot's expanded volume-tier options and logic could be implemented. The answer is yes, with the important constraint that HIP-3 rates must use active-market deployer fee scale instead of a generic static table.

Revision note: 2026-04-17 16:11Z. Implemented the plan, validated the behavior, and recorded the namespace-size cleanup follow-up.
