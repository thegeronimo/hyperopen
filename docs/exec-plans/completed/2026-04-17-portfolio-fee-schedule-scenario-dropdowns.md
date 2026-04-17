# Add fee schedule scenario dropdowns

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/AGENTS.md`, `/hyperopen/docs/FRONTEND.md`, and `/hyperopen/docs/BROWSER_TESTING.md`.

Tracked issue: `hyperopen-qk3q` ("Add fee schedule scenario dropdowns").

## Purpose / Big Picture

After this change, the portfolio fee schedule popover is not just a static table plus market selector. A user can open `/portfolio`, click `View Fee Schedule`, and change the referral discount, staking discount, maker rebate, and market-type scenario controls to see the resulting taker and maker rates in the tier table. Each selector defaults to the current wallet-derived status when the popover opens, but it can be changed locally as a what-if preview without mutating account state or making network requests.

This matters because the current popover shows referral, staking, and maker rebate as read-only rows, while the attached reference behavior exposes them as dropdowns. The finished popover must remain compact enough to fit in one view and must use Hyperopen theme tokens rather than third-party reference accents.

## Progress

- [x] (2026-04-17 13:47Z) Created and claimed `hyperopen-qk3q`.
- [x] (2026-04-17 13:48Z) Inspected the existing fee schedule model, actions, popover view, route integration, and regression tests.
- [x] (2026-04-17 13:49Z) Checked official Hyperliquid fee documentation for referral discount, staking tiers, maker rebate tiers, and fee formula semantics.
- [x] (2026-04-17 14:00Z) Wrote RED tests for scenario normalization, dropdown actions, popover controls, and browser behavior; focused CLJS run failed for the expected missing APIs and read-only UI.
- [x] (2026-04-17 14:08Z) Implemented pure fee scenario model, route-local portfolio actions, defaults, runtime registrations, and public action exports.
- [x] (2026-04-17 14:11Z) Replaced read-only status rows with compact dropdown controls and updated deterministic Playwright coverage for referral, staking, maker rebate, and market scenario changes.
- [x] (2026-04-17 14:12Z) Focused CLJS tests passed and focused Playwright fee schedule regression passed against the local app server.
- [x] (2026-04-17 14:23Z) Ran focused tests, Playwright coverage, governed browser QA, browser cleanup, full `npm test`, `npm run test:websocket`, and rerun `npm run check`.
- [x] (2026-04-17 15:16Z) Preserved the scenario dropdown behavior while converting the fee schedule surface to an anchored popover near the opener, and reran focused browser coverage plus required repo gates.

## Surprises & Discoveries

- Observation: the current popover model only lets `:fee-schedule-market-type` change.
  Evidence: `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/fee_schedule.cljs` computes `:referral`, `:staking`, and `:maker-rebate` as status maps but only exposes `:market-dropdown-open?` and market options as selector state.

- Observation: the official Hyperliquid docs list the same staking tiers shown in the reference screenshot: Wood, Bronze, Silver, Gold, Platinum, and Diamond.
  Evidence: `https://hyperliquid.gitbook.io/hyperliquid-docs/trading/fees` documents staking discounts from 5% through 40%.

- Observation: maker rebate tiers are static and can be modeled without live account data.
  Evidence: the same docs list maker rebate tier rates of `-0.001%`, `-0.002%`, and `-0.003%`.

- Observation: referral discount is a single 4% what-if option.
  Evidence: `https://hyperliquid.gitbook.io/hyperliquid-docs/referrals` states that using a referral code gives a 4% fee discount for the first $25M in volume.

- Observation: local port `8080` was already occupied by this worktree's `shadow-cljs` app watcher.
  Evidence: `lsof -nP -iTCP:8080 -sTCP:LISTEN` reported Java PID `77572`, and `ps` showed `shadow.cljs.devtools.cli --npm watch app portfolio-worker vault-detail-worker` from this worktree.

- Observation: governed design review defaults to starting a managed local app unless a session id is supplied, which conflicts when this worktree already has a Shadow watcher.
  Evidence: `npm run qa:design-ui -- --targets portfolio-route --manage-local-app` and `design-review --local-url ...` both tried to spawn `npm run dev:browser-inspection` and failed on the existing Shadow instance; starting a session first and passing `--session-id sess-1776434463659-139813` made the review run against the existing app.

- Observation: the scenario selector additions pushed `src/hyperopen/portfolio/actions.cljs` and `test/hyperopen/portfolio/actions_test.cljs` past the namespace-size threshold.
  Evidence: the first `npm run check` after implementation failed with missing-size-exception messages for those two files. Adding bounded exceptions in `/Users/barry/.codex/worktrees/09e3/hyperopen/dev/namespace_size_exceptions.edn` allowed the rerun to pass while documenting the future split.

## Decision Log

- Decision: implement dropdowns as local scenario controls, not wallet/account mutation controls.
  Rationale: the popover explains fees and previews schedule outcomes; referral setup, staking link setup, and maker rebate eligibility workflows belong elsewhere.
  Date/Author: 2026-04-17 / Codex

- Decision: reset scenario overrides on popover open so the selectors default to current wallet status.
  Rationale: this matches the inspected reference behavior and prevents stale what-if selections from masquerading as account status on the next open.
  Date/Author: 2026-04-17 / Codex

- Decision: apply referral and staking discounts only to positive fees, and let selected maker rebate tiers override positive maker fees with documented negative maker rates.
  Rationale: this follows the documented fee formula: referral discounts scale positive taker and maker fees; maker rebates are already negative maker rates.
  Date/Author: 2026-04-17 / Codex

- Decision: keep the selector action additions inside the existing portfolio action namespace and add a namespace-size exception instead of splitting action ownership mid-feature.
  Rationale: the existing feature was already registered through `hyperopen.portfolio.actions`, and a split would be broader than the product fix. The exception explicitly records that a later portfolio action-boundary cleanup should split selector-specific helpers.
  Date/Author: 2026-04-17 / Codex

## Outcomes & Retrospective

Implementation is complete. The popover now exposes dropdowns for referral discount, staking discount, and maker rebate, while retaining the existing market-type dropdown in the Volume Tier section. Opening the popover resets the local what-if selections to wallet-current defaults; selecting an option updates the tier table immediately and closes every fee-schedule dropdown except the one being opened. The table applies documented referral, staking, maker-rebate, stable-pair, and aligned-quote adjustments without adding network requests or mutating account state.

Validation passed:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.fee-schedule-test,hyperopen.portfolio.actions-test,hyperopen.views.portfolio.fee-schedule-test,hyperopen.views.portfolio-view-fee-schedule-test,hyperopen.runtime.collaborators.action-maps-test
    npx shadow-cljs --force-spawn compile app && PLAYWRIGHT_BASE_URL=http://127.0.0.1:8080 npx playwright test portfolio-regressions.spec.mjs --config tmp/playwright/portfolio-fee-schedule-existing-server.config.mjs --grep "fee schedule"
    node tools/browser-inspection/src/cli.mjs session start --local-url http://127.0.0.1:8080
    node tools/browser-inspection/src/cli.mjs design-review --targets portfolio-route --session-id sess-1776434463659-139813 --local-url http://127.0.0.1:8080
    npm run browser:cleanup
    npm test
    npm run test:websocket
    npm run check

The governed browser review run `design-review-2026-04-17T14-01-07-707Z-1dfda1fd` produced `reviewOutcome: "PASS"` for `portfolio-route` across `review-375`, `review-768`, `review-1280`, and `review-1440`. All six required passes were `PASS`: visual-evidence-captured, native-control, styling-consistency, interaction, layout-regression, and jank-perf. The only residual blind spots were the standard sampled-state notes for hover, active, disabled, and loading states not present by default.

The full test suites also passed: `npm test` ran 3216 tests with 17204 assertions and zero failures or errors; `npm run test:websocket` ran 432 tests with 2479 assertions and zero failures or errors. The final `npm run check` completed successfully after documenting the namespace-size exceptions introduced by this feature.

Follow-up validation for the anchored-popover conversion also passed: focused CLJS tests ran 23 tests with 165 assertions; the focused Playwright fee schedule regression passed with a fresh managed server; the governed `portfolio-route` design review passed at 375, 768, 1280, and 1440; `npm run check`, `npm test`, and `npm run test:websocket` all exited `0`.

## Product Specification

The popover keeps its existing centered dialog, close controls, documentation link, and market-type selector. The read-only `Referral Discount`, `Staking Discount`, and `Maker Rebate` rows become custom dropdown controls with stable `data-role` hooks. The existing `Volume Tier` section keeps the market-type dropdown and the full seven-row table.

Each scenario selector has a left label, a right selected value, and a compact popover menu. The currently wallet-derived option should be marked in the menu with short text such as `Current wallet status` while still allowing the user to choose another option. Selecting any option closes that selector, closes other fee-schedule selectors, keeps the popover open, and updates the table immediately.

Referral options are `No referral discount` and `4%`. Staking options are `No stake`, `Wood`, `Bronze`, `Silver`, `Gold`, `Platinum`, and `Diamond`, with descriptions matching the documented HYPE thresholds and discounts. Maker rebate options are `No rebate`, `Tier 1`, `Tier 2`, and `Tier 3`, with descriptions matching the documented maker-volume cutoffs and negative maker fees. Market options remain `Perps`, `Spot`, `Spot + Stable Pair`, `Spot + Aligned Quote`, and `Spot + Aligned Quote + Stable Pair`.

The table rates reflect the selected scenario controls. Staking and referral discounts reduce positive taker and maker fees. Maker rebate tiers replace positive maker fees with their documented negative maker fee; referral and staking discounts do not reduce that rebate. Spot stable-pair scaling and aligned-quote scaling continue to apply according to the existing market-type semantics. The rate note must make clear that displayed rates are scenario rates and that HIP-3 deployer adjustments are not included.

## Validation Plan

Run focused CLJS tests after RED tests are written and after implementation:

    npm run test:runner:generate
    npx shadow-cljs --force-spawn compile test
    node out/test.js --test=hyperopen.portfolio.fee-schedule-test,hyperopen.portfolio.actions-test,hyperopen.views.portfolio.fee-schedule-test,hyperopen.views.portfolio-view-fee-schedule-test,hyperopen.runtime.collaborators.action-maps-test

Run the deterministic browser regression:

    npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "fee schedule"

Run governed UI review:

    npm run qa:design-ui -- --targets portfolio-route --manage-local-app

Run required repo gates:

    npm run check
    npm test
    npm run test:websocket

Browser inspection sessions must be cleaned with `npm run browser:cleanup` when used.
