# Build the portfolio fee schedule popover

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/FRONTEND.md`, and `/hyperopen/docs/BROWSER_TESTING.md`.

Tracked issue: `hyperopen-giou` ("Implement portfolio fee schedule popover").

## Purpose / Big Picture

After this change, a user on `/portfolio` can click the existing `View Fee Schedule` button in the Fees card and see a popover that explains their fee context and the tiered fee schedule instead of encountering an inert control. The component should follow the attached product reference: a dark anchored popover with a clear `Fee Schedule` title, close control, discount status rows, a market-type selector, a tier table, a short rates note, and a documentation link.

The feature matters because the portfolio route already displays the user's current taker and maker fee rates, but it gives no path to understand how those rates are derived or what volume tiers are available. The observable success case is: open `/portfolio`, click `View Fee Schedule`, confirm a dialog opens with the fee tier table, change the market type selector, dismiss the dialog with the close button, backdrop, or `Escape`, and confirm focus returns to the opener.

## Progress

- [x] (2026-04-17 12:26Z) Read the repo planning, multi-agent, work-tracking, frontend, and browser-QA contracts that govern this UI feature.
- [x] (2026-04-17 12:27Z) Created and claimed local tracker `hyperopen-giou` for the portfolio fee schedule popover.
- [x] (2026-04-17 12:28Z) Inspected the existing portfolio fee card, portfolio VM fee derivation, action registration path, dialog/focus helper patterns, tests, and Playwright portfolio regressions.
- [x] (2026-04-17 12:48Z) Implemented the pure schedule model, route-local portfolio actions/defaults/registrations, popover view, summary-card trigger wiring, and deterministic CLJS/Playwright coverage.
- [x] (2026-04-17 13:07Z) Ran focused tests, deterministic Playwright coverage, governed browser QA for `portfolio-route`, and the required repo gates.
- [x] (2026-04-17 13:09Z) Updated this ExecPlan with implementation discoveries, validation evidence, and outcome notes before moving it out of `active`.
- [x] (2026-04-17 15:16Z) Converted the fee schedule surface from a centered dialog presentation to an anchored popover near the `View Fee Schedule` trigger, cleaned feature docs to remove third-party brand references, and reran focused browser plus repo gates.

## Surprises & Discoveries

- Observation: the current fee card is intentionally presentational and the `View Fee Schedule` button has no `:on` handler or stable data role.
  Evidence: `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/summary_cards.cljs` renders the button text directly inside `metric-cards`.

- Observation: the portfolio VM already derives current user taker and maker fee rates from `:portfolio :user-fees`, with a fallback to `hyperopen.domain.trading/default-fees`.
  Evidence: `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/vm.cljs` builds `:fees` from `vm-volume/fees-from-user-fees`; `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/vm/volume.cljs` parses `:userCrossRate`, `:userAddRate`, and `:activeReferralDiscount`.

- Observation: there is already a shared dialog focus helper that can trap focus and restore the opener when a dialog unmounts.
  Evidence: `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/ui/dialog_focus.cljs` exposes `dialog-focus-on-render`, and `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/funding_modal.cljs` uses it for route-local modal focus behavior.

- Observation: the portfolio route caches its main sections while chart hover is active, so the popover should be rendered outside the cached section map or it can miss open/close state changes during hover.
  Evidence: `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio_view.cljs` only rebuilds `build-portfolio-view-sections` when the route changes or the portfolio chart hover surface is inactive.

- Observation: the reference panel's `Spot + Aligned Quote + Stable Pair` tier values match the official Hyperliquid spot table after applying the documented stable-pair and aligned-quote multipliers.
  Evidence: Hyperliquid docs state that spot stable pairs have 80% lower fees and aligned quote assets have 20% lower taker fees; tier 0 spot base taker `0.070%` becomes `0.070 * 0.2 * 0.8 = 0.0112%`, matching the screenshot.

- Observation: Replicant omits false boolean ARIA attributes, so closed `aria-expanded` states must be rendered as explicit `"false"` strings when browser-visible accessibility state matters.
  Evidence: the first focused Playwright run resolved `[data-role='portfolio-fee-schedule-trigger']` without an `aria-expanded` attribute while the Hiccup test saw boolean false; changing the fee schedule trigger and market selector to string values made the browser regression pass.

- Observation: the checked-in interactive Playwright config only waits for main app assets, so a focused route-module test can observe stale route output immediately after source edits unless the app is explicitly compiled first.
  Evidence: the focused fee-schedule browser test continued to see the old trigger after CLJS tests passed, then passed after `npx shadow-cljs --force-spawn compile app`.

- Observation: `127.0.0.1:8080` became occupied by an unrelated watcher from `/Users/barry/.codex/worktrees/88d3/hyperopen` during final verification.
  Evidence: `lsof -nP -iTCP:8080 -sTCP:LISTEN` reported Java PID `29043`; `ps` showed its parent command rooted at `/Users/barry/.codex/worktrees/88d3/hyperopen`, so the final focused Playwright rerun used this worktree's compiled app through the repo static server on `4174` instead of killing the other process.

- Observation: the pure portfolio fee model must not import view-layer account-info projection helpers.
  Evidence: `npm run check` failed at `lint:namespace-boundaries` for `src/hyperopen/portfolio/fee_schedule.cljs` importing `hyperopen.views.account-info.projections`; replacing that with a local finite numeric parser cleared the boundary gate.

- Observation: real click dispatches may not persist usable fee-schedule anchor bounds, so the popover needs a DOM fallback from the stable trigger selector.
  Evidence: a 552px Playwright probe showed `[:portfolio-ui :fee-schedule-anchor]` as `nil` after clicking `View Fee Schedule`; resolving the trigger bounds in the view placed the panel at `left: 12px`, with the trigger inside the panel's horizontal and vertical reach.

## Decision Log

- Decision: implement the popover as route-local portfolio UI state under `:portfolio-ui`, not as a global app popover.
  Rationale: the trigger, content, and acceptance path all belong to `/portfolio`; route-local state keeps the feature close to the existing summary-card and portfolio action patterns.
  Date/Author: 2026-04-17 / Codex

- Decision: render the popover outside `build-portfolio-view-sections` in `portfolio-view`.
  Rationale: the existing chart-hover cache can otherwise keep a stale section tree and block the popover from opening or closing immediately while the user is hovering the chart.
  Date/Author: 2026-04-17 / Codex

- Decision: adapt the attached reference's visual structure but use Hyperopen/Hyperliquid-neutral copy and links.
  Rationale: the user wants behavior and presentation similar to the attached product reference, but Hyperopen should not send users to a third-party brand's documentation unless product explicitly approves that brand dependency. The fee schedule source is the Hyperliquid protocol documentation.
  Date/Author: 2026-04-17 / Codex

- Decision: include a market-type selector in v1 with deterministic schedule data for `Perps`, `Spot`, `Spot + Stable Pair`, `Spot + Aligned Quote`, and `Spot + Aligned Quote + Stable Pair`.
  Rationale: the reference panel includes a market-type selector, and supporting the common protocol variants makes the component useful without broadening into HIP-3 deployer-specific fee math.
  Date/Author: 2026-04-17 / Codex

- Decision: treat HIP-3 deployer fee scale, growth-mode variants, live maker-share calculation, referral enrollment actions, and staking-link actions as non-goals for this feature.
  Rationale: those features require market-specific context or mutation flows that do not exist in the portfolio fee card. The popover should explain the current static schedule and user status, not become a fee-management workflow.
  Date/Author: 2026-04-17 / Codex

## Outcomes & Retrospective

Implementation is complete. The change adds one focused pure model namespace and one focused popover view namespace, with route-local state and actions rather than a new global popover subsystem. UI complexity increased modestly because the portfolio fee card now has a real disclosure flow, but the popover stays outside the chart-hover cached sections and uses the existing dialog focus helper, so the interaction remains localized.

Validation passed:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.fee-schedule-test,hyperopen.portfolio.actions-test,hyperopen.views.portfolio.summary-cards-test,hyperopen.views.portfolio.fee-schedule-test,hyperopen.views.portfolio-view-fee-schedule-test,hyperopen.runtime.collaborators.action-maps-test
    npx shadow-cljs --force-spawn compile app && npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "fee schedule"
    npm run qa:design-ui -- --targets portfolio-route --manage-local-app
    npm run browser:cleanup
    npm run check
    npm test
    npm run test:websocket
    PLAYWRIGHT_BASE_URL=http://127.0.0.1:4174 npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --config tmp/playwright/portfolio-fee-schedule-static.config.mjs --grep "fee schedule"
    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.actions-test --test=hyperopen.views.portfolio.summary-cards-test --test=hyperopen.views.portfolio.fee-schedule-test --test=hyperopen.views.portfolio-view-fee-schedule-test
    npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "portfolio fee schedule" --workers=1
    npm run qa:design-ui -- --targets portfolio-route --manage-local-app
    npm run browser:cleanup
    npm run check
    npm test
    npm run test:websocket

The governed `portfolio-route` design review run `design-review-2026-04-17T12-58-56-886Z-87cdbfda` ended with `reviewOutcome: "PASS"` and all six required passes green for `review-375`, `review-768`, `review-1280`, and `review-1440`: visual-evidence-captured, native-control, styling-consistency, interaction, layout-regression, and jank-perf.

Remaining product gaps are intentional non-goals from this plan: HIP-3 deployer-specific fee variants, live maker-share tiering, referral enrollment, staking-link actions, and user-specific table-rate adjustment are not implemented. The v1 table is explicitly labeled as base protocol rates before user-specific referral, staking, maker rebate, and HIP-3 deployer adjustments.

## Product Specification

The `View Fee Schedule` button in the `portfolio-fees-card` must become an interactive trigger. It must keep the existing Fees card visual hierarchy from the current portfolio route, but add `type "button"`, `data-role "portfolio-fee-schedule-trigger"`, `aria-haspopup "dialog"`, `aria-expanded` reflecting the popover open state, and a click action that opens the popover.

The popover must be anchored near the fee trigger with a transparent click-away backdrop on desktop and mobile. At desktop widths it should visually track the attached reference: about `32rem` maximum width, dark panel, subtle border, small close icon in the top-right, content padding around `1.25rem` to `1.5rem`, Hyperopen accent section labels, compact status rows, and a dense tier table. At `375px` width it must fit inside the viewport with horizontal padding, keep the close icon reachable, and make the tier table scroll horizontally only if required; page-level horizontal overflow is not acceptable.

The popover title is `Fee Schedule`. The user context sections are:

Referral Discount. The field label is `Referral Status`. When no wallet address is connected, the value is `No referral discount` and the helper copy is `Wallet not connected`. When the wallet is connected and `:activeReferralDiscount` is positive, display the normalized percentage such as `10% referral discount`; otherwise display `No referral discount` and helper copy `No active referral discount`.

Staking Discount. The field label is `Staking Tier`. When no wallet address is connected, the value is `No stake` and helper copy is `Wallet not connected`. When `:portfolio :user-fees :activeStakingDiscount :discount` is positive, display the percentage discount and, if the payload exposes a tier-like label, include it in the value. Otherwise display `No stake` and helper copy `No active staking discount`.

Maker Rebate. The field label is `Maker Rebate Tier`. The value is `No rebate` unless `:portfolio :user-fees` exposes a positive maker-rebate tier or a negative maker rate that clearly represents a rebate. When a rebate is known, display `Tier N rebate` or the normalized rebate percentage. This row is informational in v1 and must not imply the user can enroll from this popover.

Volume Tier. The section contains the market-type selector and the tier table. The selector label is `Market Type`. The default market type when opening the popover is `Perps` because the portfolio fee card currently labels its displayed fees as `Perps`. The selector options are `Perps`, `Spot`, `Spot + Stable Pair`, `Spot + Aligned Quote`, and `Spot + Aligned Quote + Stable Pair`. Selecting an option closes the selector and updates the table without closing the popover.

The tier table columns are `Tier`, `14 Day Volume`, `Taker*`, and `Maker*`. Tier rows are:

- Tier 0: `<= $5M`
- Tier 1: `> $5M`
- Tier 2: `> $25M`
- Tier 3: `> $100M`
- Tier 4: `> $500M`
- Tier 5: `> $2B`
- Tier 6: `> $7B`

For `Perps`, display base taker/maker rates `0.045% / 0.015%`, `0.040% / 0.012%`, `0.035% / 0.008%`, `0.030% / 0.004%`, `0.028% / 0%`, `0.026% / 0%`, and `0.024% / 0%`.

For `Spot`, display base taker/maker rates `0.070% / 0.040%`, `0.060% / 0.030%`, `0.050% / 0.020%`, `0.040% / 0.010%`, `0.035% / 0%`, `0.030% / 0%`, and `0.025% / 0%`.

For `Spot + Stable Pair`, apply the stable-pair multiplier to the spot table: taker and positive maker values are multiplied by `0.2`; zero maker values remain `0%`.

For `Spot + Aligned Quote`, apply the aligned quote taker multiplier to the spot table: taker values are multiplied by `0.8`; positive maker fees remain the base spot maker values because the aligned-quote rebate improvement only applies once the maker rate is a rebate. Zero maker values remain `0%`.

For `Spot + Aligned Quote + Stable Pair`, apply both multipliers: taker values are multiplied by `0.16`, positive maker values are multiplied by `0.2`, and zero maker values remain `0%`. This yields the screenshot's tier 0 `0.0112% / 0.008%`, tier 1 `0.0096% / 0.006%`, tier 2 `0.008% / 0.004%`, tier 3 `0.0064% / 0.002%`, tier 4 `0.0056% / 0%`, tier 5 `0.0048% / 0%`, and tier 6 `0.004% / 0%`.

Below the table, display `* Rates given after referral, staking and maker rebate` only if the implementation can correctly apply those user-specific discounts to the selected schedule. If the v1 table remains a base schedule with user status shown above, use `* Base protocol rates before user-specific referral, staking, maker rebate, and HIP-3 deployer adjustments`. The executor must choose one of these two notes based on the implemented data semantics and update tests to pin the exact copy.

The documentation footer must read `You can read more about fees in Hyperliquid documentation`, with the link opening `https://hyperliquid.gitbook.io/hyperliquid-docs/trading/fees` in a new tab and including `rel "noreferrer"`. If product explicitly wants the third-party reference copy later, that should be a separate product decision.

The popover must support close button, backdrop click, and `Escape`. It must set `role "dialog"`, `aria-modal false`, and `aria-labelledby` pointing at the title. Focus must move into the dialog on open and return to `portfolio-fee-schedule-trigger` on close. The component must not introduce browser-native `select` styling; the market selector should reuse the project's button plus popover pattern.

## Context and Orientation

The portfolio route view lives at `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio_view.cljs`. It computes `view-model` through `hyperopen.views.portfolio.vm/portfolio-vm`, then renders header actions, a summary grid, and the account table. The summary grid uses `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/summary_cards.cljs`. That file owns the `portfolio-fees-card` where this trigger currently appears.

The portfolio VM in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/vm.cljs` returns a map with `:fees`, `:volume-14d-usd`, `:summary`, `:chart`, `:performance-metrics`, and `:selectors`. The current fees card only consumes `:fees`. The fee schedule popover needs a new model that also reads `:portfolio-ui` open/dropdown state, `:wallet :address`, and `:portfolio :user-fees`.

Portfolio interaction actions live in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/actions.cljs`. Existing selector actions use `[:effects/save-many ...]` to batch state writes. Runtime action handlers for portfolio controls are registered through `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/runtime/collaborators/chart.cljs` and `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/schema/runtime_registration/portfolio.cljs`. Action argument specs live in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/schema/contracts/action_args.cljs`.

Default portfolio UI state lives in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/state/app_defaults.cljs`. The new open state, selected market type, and market selector dropdown open state should be initialized there so reload and tests start from a deterministic closed state.

The existing shared dialog focus helper lives in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/ui/dialog_focus.cljs`. Use this helper for opener focus restoration instead of writing a popover-specific focus loop.

The current component tests for this area are `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/views/portfolio/summary_cards_test.cljs`, `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`, `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`, and `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/portfolio/actions_test.cljs`. Deterministic browser coverage for portfolio interactions lives in `/Users/barry/.codex/worktrees/09e3/hyperopen/tools/playwright/test/portfolio-regressions.spec.mjs`.

## Interfaces and Dependencies

Create `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/fee_schedule.cljs` for pure fee schedule data and view-model helpers. This namespace should expose:

    default-market-type
    market-type-options
    normalize-market-type
    fee-schedule-rows
    fee-schedule-model

`default-market-type` must be `:perps`. `market-type-options` must be a vector of maps with `:value` and `:label`. `normalize-market-type` must accept keywords and strings such as `:spot-aligned-stable-pair`, `"spotAlignedStablePair"`, and the option label, and fall back to `default-market-type`. `fee-schedule-rows` must return the seven row maps for a market type with keys `:tier`, `:volume`, `:taker`, and `:maker`, where `:taker` and `:maker` are already formatted strings for display. `fee-schedule-model` must return the popover content model from the full app state.

Create `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/fee_schedule.cljs` for the Hiccup component. This namespace should expose:

    fee-schedule-popover

`fee-schedule-popover` accepts the model returned by `hyperopen.portfolio.fee-schedule/fee-schedule-model`. When `:open?` is false it returns nil. When open, it returns the overlay and dialog Hiccup with all `data-role` hooks specified in this plan.

Add these action functions to `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/actions.cljs`:

    open-portfolio-fee-schedule
    close-portfolio-fee-schedule
    toggle-portfolio-fee-schedule-market-dropdown
    select-portfolio-fee-schedule-market-type
    handle-portfolio-fee-schedule-keydown

`open-portfolio-fee-schedule` must batch writes that set `[:portfolio-ui :fee-schedule-open?]` to true, close the fee schedule market dropdown, and close the existing summary/performance dropdowns. `close-portfolio-fee-schedule` must batch writes that set `:fee-schedule-open?` and `:fee-schedule-market-dropdown-open?` to false. `toggle-portfolio-fee-schedule-market-dropdown` must only toggle the fee schedule market dropdown while keeping the popover open. `select-portfolio-fee-schedule-market-type` must normalize the requested market type, write it to `[:portfolio-ui :fee-schedule-market-type]`, and close the dropdown. `handle-portfolio-fee-schedule-keydown` must close only when the key is `Escape`; other keys return no effects.

Add runtime bindings for these actions in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/runtime/collaborators/chart.cljs`, `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/schema/runtime_registration/portfolio.cljs`, and `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/schema/contracts/action_args.cljs`. No heavy I/O effects are introduced, so `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs` should not need a new entry unless the executor broadens the action to fetch data.

## Plan of Work

First, implement pure schedule helpers and user-status normalization in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/fee_schedule.cljs`. Keep the data static, small, and close to the formula comments. Use existing numeric parsing helpers when needed, but do not add a network request. Add direct tests in `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/portfolio/fee_schedule_test.cljs` for market type normalization, each schedule variant's tier 0 and tier 6 values, disconnected wallet status copy, connected referral/staking labels, and fallback behavior for missing `:portfolio :user-fees`.

Second, implement the portfolio actions and defaults. Update `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/state/app_defaults.cljs` with `:fee-schedule-open? false`, `:fee-schedule-market-type :perps`, and `:fee-schedule-market-dropdown-open? false`. Update `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/actions.cljs` with the action functions described above. Extend `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/portfolio/actions_test.cljs` with exact expected effects for open, close, toggle, select, and Escape handling.

Third, register action handlers. Update `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/runtime/collaborators/chart.cljs`, `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/schema/runtime_registration/portfolio.cljs`, and `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/schema/contracts/action_args.cljs`. Extend `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/runtime/collaborators/action_maps_test.cljs` to prove the runtime deps include the new portfolio handlers. Existing schema coverage should catch missing argument contracts; add a direct assertion only if the existing coverage fails unclearly.

Fourth, implement the view. Update `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/summary_cards.cljs` so `metric-cards` receives enough popover state or an `open?` boolean to set `aria-expanded` and wire the trigger action. Create `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/fee_schedule.cljs` for the popover Hiccup. Update `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio_view.cljs` to require both the pure model namespace and the popover view namespace, render the popover after `(:account-table sections)`, and keep it outside the cached `sections` map.

Fifth, strengthen component tests. Update `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/views/portfolio/summary_cards_test.cljs` to assert the trigger data role, `aria-haspopup`, `aria-expanded`, and click action. Add `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/views/portfolio/fee_schedule_test.cljs` for the popover structure, close/backdrop/Escape actions, status rows, market selector actions, table text, documentation link attributes, and absence of native `select`. Update `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/views/portfolio_view_test.cljs` to assert the closed popover is absent by default and the open popover is present when `[:portfolio-ui :fee-schedule-open?]` is true, including a regression where chart hover cache is active if that test can be written deterministically.

Sixth, add deterministic browser coverage. Extend `/Users/barry/.codex/worktrees/09e3/hyperopen/tools/playwright/test/portfolio-regressions.spec.mjs` with a test named similar to `portfolio fee schedule opens, switches market type, and restores focus @regression`. The test should visit `/portfolio`, click `[data-role='portfolio-fee-schedule-trigger']`, assert `[data-role='portfolio-fee-schedule-dialog']` is visible, assert the default table includes `0.045%` and `0.015%`, select `Spot + Aligned Quote + Stable Pair`, assert tier 0 includes `0.0112%` and `0.008%`, close with the close button and assert focus returns to the trigger, reopen and close with `Escape`, and finally assert the dialog is gone.

Seventh, run browser QA. Because this is UI-facing work under `/hyperopen/src/hyperopen/views/**`, the executor must run the smallest relevant Playwright command first, then the governed design review for `portfolio-route` across widths `375`, `768`, `1280`, and `1440`. The final report must account for visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf passes as `PASS`, `FAIL`, or `BLOCKED`, and must stop browser-inspection sessions with `npm run browser:cleanup` if any Browser MCP or browser-inspection sessions were created.

## Concrete Steps

All commands should run from `/Users/barry/.codex/worktrees/09e3/hyperopen`.

Start by running the focused tests that will be changed after the first failing assertions are written:

    npm run test:runner:generate
    npx shadow-cljs --force-spawn compile test
    node out/test.js --test=hyperopen.portfolio.fee-schedule-test,hyperopen.portfolio.actions-test,hyperopen.views.portfolio.summary-cards-test,hyperopen.views.portfolio.fee-schedule-test,hyperopen.views.portfolio-view-test,hyperopen.runtime.collaborators.action-maps-test

Before implementation, new tests should fail because the new namespaces, data roles, actions, and popover are missing. After implementation, the same focused test command should pass.

Run the deterministic browser regression once the focused CLJS tests pass:

    npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "fee schedule"

Expected result: the new fee schedule regression passes and Playwright exits cleanly.

Run the governed design review:

    npm run qa:design-ui -- --targets portfolio-route --manage-local-app

Expected result: the summary reports `reviewOutcome: "PASS"` for `portfolio-route` at `review-375`, `review-768`, `review-1280`, and `review-1440`. If any pass is `FAIL` or `BLOCKED`, keep this ExecPlan active, record the artifact path in `Surprises & Discoveries`, and fix or explicitly scope the blocker before signoff.

Clean browser-inspection sessions if the design review or exploratory browser tools were used:

    npm run browser:cleanup

Finish with the repo-required gates for code changes:

    npm run check
    npm test
    npm run test:websocket

Expected result: all three commands exit `0`. If one fails for an unrelated pre-existing issue, record the exact failure and evidence in this ExecPlan and in the final handoff.

Commands already run while creating this spec:

    bd ready --json
    bd create "Implement portfolio fee schedule popover" --description="Add the missing portfolio View Fee Schedule interaction. Opening the fee card button should display a Hyperopen-themed fee schedule popover with referral discount, staking discount, maker rebate, volume tier selector, a fee tier table, documentation link, keyboard dismissal, and deterministic tests plus browser QA." -t feature -p 2 --json
    bd update hyperopen-giou --claim --json

## Validation and Acceptance

Acceptance is behavioral, visual, and test-backed.

On `/portfolio`, the Fees card must still show `Fees (Taker / Maker)`, the `Perps` pill, and the current taker/maker percentage. The `View Fee Schedule` control must open a popover by real click. The popover must show the user context rows, default to the `Perps` fee schedule, support market-type switching, link to fee documentation, and close by close button, backdrop, and `Escape`.

Keyboard users must be able to tab through the popover without focus escaping. Closing the popover must restore focus to `[data-role='portfolio-fee-schedule-trigger']`. The popover must have correct dialog semantics and must not use native select controls.

The static table data must match the Product Specification section. At minimum, tests must prove `Perps` tier 0 is `0.045% / 0.015%`, `Perps` tier 6 is `0.024% / 0%`, `Spot` tier 0 is `0.070% / 0.040%`, and `Spot + Aligned Quote + Stable Pair` tier 0 is `0.0112% / 0.008%`.

The feature is not accepted until the focused CLJS tests, the focused Playwright regression, the governed `portfolio-route` design review, `npm run check`, `npm test`, and `npm run test:websocket` are all accounted for.

## Idempotence and Recovery

The planned edits are additive and safe to rerun. If a state or action test fails, inspect the exact emitted effect vector before broadening to browser testing. If the popover does not open in live browser while CLJS tests pass, first check runtime registration in `schema/runtime_registration/portfolio.cljs`, `runtime/collaborators/chart.cljs`, and `schema/contracts/action_args.cljs`.

If focus restore fails, reuse the existing dialog focus helper rather than creating a parallel focus implementation. If the popover fails to render only while chart hover is active, move or keep the popover outside the cached `sections` map in `portfolio_view.cljs`.

If the governed browser review fails for mobile overflow, constrain the dialog width and make only the table container scroll horizontally. Do not allow the app root or body to gain horizontal overflow. If browser-inspection sessions remain after a failed run, run `npm run browser:cleanup` before retrying.

## Artifacts and Notes

Reference screenshots supplied by the user show:

    Fees card:
      - label: Fees (Taker / Maker)
      - pill: Perps
      - rates: 0.045% / 0.015%
      - action: View Fee Schedule

    Fee schedule popover:
      - title: Fee Schedule
      - sections: Referral Discount, Staking Discount, Maker Rebate, Volume Tier
      - disconnected helper copy: Wallet not connected
      - market type selector: Spot + Aligned Quote + Stable Pair
      - table columns: Tier, 14 Day Volume, Taker*, Maker*
      - table rows: tiers 0 through 6 with thresholds <= $5M through > $7B
      - footer note and documentation link

Official protocol context reviewed during planning:

    Hyperliquid fees are based on rolling 14 day volume.
    Perps and spot have separate fee schedules.
    Perps and spot volume count together for tiering, with spot volume counting double.
    Spot stable pairs have lower fees.
    Aligned quote assets reduce taker fees and improve maker rebates.

Revision note: 2026-04-17 12:28Z. Created this active ExecPlan after the user requested a full product specification and execution plan for the missing portfolio fee schedule component, then linked it to tracker `hyperopen-giou`.

Revision note: 2026-04-17 13:09Z. Updated after implementation and validation. The fee schedule popover, state/actions, runtime registration, focused CLJS tests, deterministic Playwright regression, governed browser QA, and required repo gates are complete; tracker `hyperopen-giou` is ready to close and this plan is ready to move to `completed/`.

Revision note: 2026-04-17 15:16Z. Updated after converting the interaction model from centered dialog presentation to trigger-anchored popover placement. The feature docs no longer reference third-party branding, and validation was rerun with focused CLJS tests, focused Playwright coverage, governed portfolio design review, `npm run check`, `npm test`, and `npm run test:websocket`.
