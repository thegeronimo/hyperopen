# Fix portfolio fee schedule HIP-3 default previews

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/AGENTS.md`, `/hyperopen/docs/FRONTEND.md`, and `/hyperopen/docs/BROWSER_TESTING.md`.

Tracked issue: `hyperopen-jn38` ("Fix fee schedule HIP-3 scenario previews without active market context").

## Purpose / Big Picture

The portfolio fee schedule popover currently lists HIP-3 volume-tier scenarios but disables them unless the active market is an HIP-3 market with a known deployer fee scale. That makes the dropdown feel broken: users can see the rows but cannot select them from the portfolio route. After this change, a user can open `/portfolio`, click `View Fee Schedule`, open `Market Type`, and select every HIP-3 row even when the active market is not HIP-3.

The visible behavior must match the inspected product reference: when there is no active HIP-3 deployer context, HIP-3 previews use a default `deployerFeeScale` of `1.0`. When the active market is HIP-3 and supplies a real deployer fee scale, the table continues to use that real market-specific scale and marks the active scenario row as `Active market: <symbol>`.

## Progress

- [x] (2026-04-17 16:22Z) Reproduced the behavior on the user-provided external reference route: all HIP-3 market-type rows are enabled with no `aria-disabled` attribute.
- [x] (2026-04-17 16:29Z) Captured reference table outputs showing no-active-market HIP-3 previews use `deployerFeeScale 1.0`, for example Diamond wallet `HIP-3 Perps` tier 0 is `0.054% / 0.018%`.
- [x] (2026-04-17 16:30Z) Created tracked issue `hyperopen-jn38`.
- [x] (2026-04-17 16:33Z) Added failing model, view, and Playwright expectations for selectable default HIP-3 previews; the RED run failed on selected market fallback, disabled option metadata, default HIP-3 rates, and rate-note copy.
- [x] (2026-04-17 16:35Z) Implemented the minimal fee schedule model change: default HIP-3 deployer scale, no HIP-3-to-core fallback, no disabled HIP-3 option decoration, and updated rate note.
- [x] (2026-04-17 16:43Z) Ran focused tests, targeted Playwright regression, governed portfolio design review, `git diff --check`, `npm run check`, `npm test`, `npm run test:websocket`, and `npm run browser:cleanup`.
- [x] (2026-04-17 16:44Z) Closed `hyperopen-jn38` and prepared this plan for the completed directory.

## Surprises & Discoveries

- Observation: the previous completed plan deliberately disabled HIP-3 rows without active deployer context.
  Evidence: `/Users/barry/.codex/worktrees/09e3/hyperopen/docs/exec-plans/completed/2026-04-17-portfolio-fee-schedule-volume-tier-options.md` records the decision "avoid inventing a generic HIP-3 fee scale" and the current model enforces it in `available-market-type` and `decorate-market-options`.

- Observation: the inspected external reference does not disable HIP-3 rows in the no-active-HIP3 portfolio case.
  Evidence: the browser probe against the user-provided ghost portfolio route found the `HIP-3 Perps`, `HIP-3 Perps + Growth mode`, `HIP-3 Perps + Aligned Quote`, and `HIP-3 Perps + Growth mode + Aligned Quote` buttons all had `disabled: false` and no `aria-disabled` attribute.

- Observation: the reference's no-active-HIP3 HIP-3 rates imply a default deployer fee scale of `1.0`.
  Evidence: with the inspected Diamond staking tier, `Core Perps` tier 0 is `0.027% / 0.009%`. `HIP-3 Perps` tier 0 is `0.054% / 0.018%`, exactly double the core perps row after the 40% Diamond discount. The shared protocol formula doubles positive HIP-3 fees when `deployerFeeScale` is `1.0`.

## Decision Log

- Decision: default no-active-context HIP-3 previews to `deployerFeeScale 1.0` instead of disabling the options.
  Rationale: this matches the inspected reference behavior and lets the portfolio schedule function as a scenario preview rather than requiring navigation to a specific HIP-3 market first. The table remains clear that active HIP-3 markets override the default by using their actual deployer fee scale.
  Date/Author: 2026-04-17 / Codex

- Decision: do not add a second selector for deployer fee scale in this slice.
  Rationale: the user asked why the existing options could not be selected, and the reference dropdown does not expose a deployer-scale control. Adding that control would widen the interaction model and introduce new product choices.
  Date/Author: 2026-04-17 / Codex

## Outcomes & Retrospective

Implementation is complete. The UI behavior is now simpler because all listed market-type options are selectable; the pure model gains a single default scale constant while retaining the existing active-market override. This increases the fee-schedule model by one small fallback rule, but removes the confusing disabled-state branch for no-active-context HIP-3 options.

## Context and Orientation

The pure fee schedule model lives in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/fee_schedule.cljs`. It owns the market-type options, dropdown normalization, active market fee context, fee row calculations, and the final `fee-schedule-model` consumed by the view.

The popover view lives in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/fee_schedule.cljs`. It renders whatever option metadata the model provides. If the model stops marking HIP-3 rows disabled, the existing renderer should automatically give those rows click handlers.

The deterministic browser regression lives in `/Users/barry/.codex/worktrees/09e3/hyperopen/tools/playwright/test/portfolio-regressions.spec.mjs`. It currently asserts that HIP-3 is disabled without active context; that assertion must become an enabled selection scenario that proves the table changes.

The shared protocol math lives in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/domain/trading/fees.cljs`. It already implements the deployer-fee formula. With `deployerFeeScale 1.0`, HIP-3 positive taker and maker rates use a scale factor of `2.0`, growth mode applies a `0.1` multiplier, and aligned quote scales taker by `0.9` for the default deployer-share branch.

## Plan of Work

First, update tests before production code. In `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/portfolio/fee_schedule_test.cljs`, replace the unavailable-HIP3 fallback assertions with default-preview assertions: selected type remains the requested HIP-3 value, label stays HIP-3, tier 0 shows the reference-derived default-scale output, and HIP-3 options are not disabled.

Second, update `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/views/portfolio/fee_schedule_test.cljs` so the sample HIP-3 row is enabled, has a select action, and no longer renders the disabled helper copy.

Third, update `/Users/barry/.codex/worktrees/09e3/hyperopen/tools/playwright/test/portfolio-regressions.spec.mjs`. The no-active-HIP3 branch should open the market dropdown, assert HIP-3 rows are visible and enabled, click `HIP-3 Perps`, and assert tier 0 changes from core perps `0.045% / 0.015%` to default-scale HIP-3 `0.090% / 0.030%` for the disconnected no-discount local app state. The later injected active-HIP3 branch must continue to assert the active deployer scale `0.5` output `0.0068% / 0.0023%`.

Fourth, update `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/fee_schedule.cljs`. Add a private default HIP-3 deployer scale constant of `1.0`. Make active fee context expose a `:deployer-fee-scale` that is either the real active HIP-3 scale or the default. Active-market labeling should continue to come from the inferred active market scenario and symbol, not from option availability. Remove the fallback from HIP-3 to `:perps` in `available-market-type`, and remove disabled helper decoration for no-active-context HIP-3 options.

Fifth, update the rate note to say the rows use selected scenarios and default HIP-3 preview context. Keep the copy short enough to fit in the current popover.

## Concrete Steps

Run these focused tests after adding expectations and before production code; they should fail for the intended reason:

    cd /Users/barry/.codex/worktrees/09e3/hyperopen
    npm run test:runner:generate
    npx shadow-cljs --force-spawn compile test
    node out/test.js --test=hyperopen.portfolio.fee-schedule-test --test=hyperopen.views.portfolio.fee-schedule-test

After implementation, rerun the focused tests and the focused browser regression:

    cd /Users/barry/.codex/worktrees/09e3/hyperopen
    node out/test.js --test=hyperopen.portfolio.fee-schedule-test --test=hyperopen.views.portfolio.fee-schedule-test --test=hyperopen.portfolio.actions-test
    npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "portfolio fee schedule" --workers=1

Before completion, run the repository-required gates:

    cd /Users/barry/.codex/worktrees/09e3/hyperopen
    git diff --check
    npm run check
    npm test
    npm run test:websocket
    npm run browser:cleanup

Observed validation on 2026-04-17:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.fee-schedule-test --test=hyperopen.views.portfolio.fee-schedule-test
    Result: 8 tests, 144 assertions, 0 failures.

    node out/test.js --test=hyperopen.portfolio.actions-test
    Result: 17 tests, 79 assertions, 0 failures.

    npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.fee-schedule-test --test=hyperopen.views.portfolio.fee-schedule-test --test=hyperopen.portfolio.actions-test
    Result: 25 tests, 223 assertions, 0 failures.

    npx playwright test -c tmp/playwright/reuse-dev-server.config.mjs tools/playwright/test/portfolio-regressions.spec.mjs --grep "portfolio fee schedule" --workers=1
    Result: 1 passed.

    npm run qa:design-ui -- --targets portfolio-route --session-id sess-1776444047280-6c535d --local-url http://127.0.0.1:8080
    Result: PASS across review-375, review-768, review-1280, and review-1440. Residual blind spot was the standard sampled-state note for hover, active, disabled, and loading states; the targeted Playwright regression covers this dropdown interaction.

    git diff --check
    Result: passed with no output.

    npm run check
    Result: passed.

    npm test
    Result: 3220 tests, 17271 assertions, 0 failures.

    npm run test:websocket
    Result: 432 tests, 2479 assertions, 0 failures.

    npm run browser:cleanup
    Result: stopped sess-1776444047280-6c535d.

## Validation and Acceptance

The change is accepted when all criteria below are true:

1. With no active HIP-3 market context, the fee schedule model preserves selected HIP-3 values instead of normalizing them to `:perps`.
2. With no active HIP-3 market context, HIP-3 dropdown rows are clickable and do not expose `aria-disabled`.
3. With no active HIP-3 market context and no wallet discounts, selecting `HIP-3 Perps` shows tier 0 `0.090% / 0.030%`; selecting `HIP-3 Perps + Growth mode` shows tier 0 `0.009% / 0.003%`.
4. With an injected active HIP-3 market using deployer fee scale `0.5`, selecting `HIP-3 Perps + Growth mode` still shows tier 0 `0.0068% / 0.0023%` and marks `Active market: WTIOIL`.
5. Focused model, view, action, and Playwright regressions pass.
6. `npm run check`, `npm test`, and `npm run test:websocket` pass.

## Idempotence and Recovery

This work is source-level and safe to rerun. If the default-scale implementation breaks the active-market scenario, keep the tests that distinguish default scale from real scale and revert only the active-context fallback logic in `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/fee_schedule.cljs`. Do not revert unrelated fee schedule visual work from previous commits.

## Artifacts and Notes

Reference capture from the inspected external route:

    Core Perps tier 0: 0.027% / 0.009% for a Diamond wallet.
    HIP-3 Perps tier 0: 0.054% / 0.018% for the same wallet.
    HIP-3 Perps + Growth mode tier 0: 0.0054% / 0.0018%.
    HIP-3 Perps + Aligned Quote tier 0: 0.0486% / 0.018%.
    HIP-3 Perps + Growth mode + Aligned Quote tier 0: 0.00486% / 0.0018%.

Plan revision note: 2026-04-17 16:30Z - Initial plan created after user review feedback showed that the previous disabled-HIP3 assumption did not match the inspected reference behavior.

## Interfaces and Dependencies

At completion, `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/portfolio/fee_schedule.cljs` should still expose the same public functions: `normalize-market-type`, `market-type-options`, `fee-schedule-rows`, and `fee-schedule-model`. No public action signatures should change. The default HIP-3 deployer scale should remain a private implementation detail of the fee schedule model.
