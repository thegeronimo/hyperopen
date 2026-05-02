# Outcome Market Selected-Header Tooltip

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. This document follows `/hyperopen/docs/PLANS.md` and `/hyperopen/.agents/PLANS.md`.

Tracked issue: `hyperopen-b9dp` ("Outcome market selected-header tooltip").

## Purpose / Big Picture

Outcome markets ask a binary question, such as whether BTC will be above a target price at a settlement time. The selected market header currently exposes outcome details through a plain hover panel. After this change, hovering or focusing the selected outcome market opens the richer screenshot-guided tooltip directly beneath the selected market region. The tooltip explains the outcome rule, settlement condition, payout rule, and USDC payout note with clear Lucide-style iconography. Non-outcome markets keep their existing selected header and do not render this tooltip.

## Progress

- [x] (2026-05-02 21:09Z) Created `bd` issue `hyperopen-b9dp`.
- [x] (2026-05-02 21:10Z) Mapped the selected-market header code and found the existing outcome-only hover panel in `src/hyperopen/views/active_asset/row.cljs`.
- [x] (2026-05-02 21:12Z) Added failing deterministic tests for the richer outcome tooltip and the non-outcome absence case.
- [x] (2026-05-02 21:14Z) Verified RED with `npm test`: 15 failures, all from the missing `:outcome-tooltip` VM field and missing `data-role "outcome-market-tooltip"` rendered structure.
- [x] (2026-05-02 21:24Z) Implemented the outcome-only tooltip model and renderer.
- [x] (2026-05-02 21:25Z) Verified GREEN with `npm test`: 3710 tests, 20488 assertions, 0 failures, 0 errors.
- [x] (2026-05-02 21:31Z) Ran `npm run check`: completed successfully with Shadow builds showing 0 warnings.
- [x] (2026-05-02 21:32Z) Ran `npm run test:websocket`: 523 tests, 3041 assertions, 0 failures, 0 errors.
- [x] (2026-05-02 21:34Z) Ran governed design review: `npm run qa:design-ui -- --targets trade-route --manage-local-app` returned `reviewOutcome: "PASS"` for `/trade` at 375, 768, 1280, and 1440.
- [x] (2026-05-02 21:36Z) Ran `npm run test:playwright:smoke`: 25 passed.
- [x] (2026-05-02 21:37Z) Ran `npm run browser:cleanup`: ok, stopped no remaining sessions.
- [x] (2026-05-02 21:38Z) Updated outcomes and prepared this plan for `docs/exec-plans/completed/`.
- [x] (2026-05-02 21:52Z) After a final fallback hardening edit, reran `npm test`, `npm run check`, `npm run test:websocket`, and governed design review successfully.
- [x] (2026-05-02 21:55Z) Reran Playwright smoke; the full suite had one desktop spectate-modal timeout unrelated to this tooltip path, and the failed desktop spectate-modal test passed on targeted rerun.

## Surprises & Discoveries

- Observation: The active outcome header already has a hover-only details popover, but it renders raw `:outcome-details` text in a plain panel rather than the screenshot's structured content.
  Evidence: `src/hyperopen/views/active_asset/row.cljs` defines `outcome-details-panel`, and `test/hyperopen/views/active_asset/row_test.cljs` asserts the old copy and classes.
- Observation: The repository already imports Lucide icons by direct `lucide/dist/esm/icons/*.js` modules and converts their node structure into Hiccup.
  Evidence: `src/hyperopen/views/asset_selector/icons.cljs` imports `star.js` and defines `lucide-node->hiccup`.
- Observation: This worktree initially had no `node_modules`, causing `npm test` to fail before reaching the RED assertions with `Cannot find module 'lucide/dist/esm/icons/external-link.js'`.
  Evidence: `npm ls lucide --depth=0` returned `(empty)`, then `npm install` added 335 packages and the next `npm test` reached the expected active-asset failures.
- Observation: The final full Playwright smoke rerun had one timeout in the desktop spectate-modal watchlist flow, while the targeted rerun of that same test passed.
  Evidence: `npm run test:playwright:smoke` reported 24 passed and one timeout in `spectate-mode-modal.smoke.spec.mjs:112`; `npx playwright test tools/playwright/test/spectate-mode-modal.smoke.spec.mjs --grep "desktop watchlist flow"` then passed in 16.6s. The failing test path is unrelated to outcome-market selected-header rendering.

## Decision Log

- Decision: Keep this tooltip scoped to `src/hyperopen/views/active_asset/**`.
  Rationale: The user requested the tooltip under the selected market, not a global tooltip replacement or selector-row hover behavior. The active-asset row is already where the selected outcome market header and existing hover panel live.
  Date/Author: 2026-05-02 / Codex
- Decision: Add a focused outcome tooltip namespace instead of expanding the row namespace with icon imports and copy formatting.
  Rationale: The row namespace already handles desktop, mobile, funding, and outcome header layout. A small tooltip namespace keeps the bespoke panel testable and avoids making the selected-row renderer harder to scan.
  Date/Author: 2026-05-02 / Codex
- Decision: Derive structured tooltip fields in the active-asset VM from the normalized market fields when possible.
  Rationale: The renderer should receive ready display text and not parse market semantics. The VM already computes outcome-specific header fields such as countdown, chance, and open interest.
  Date/Author: 2026-05-02 / Codex

## Outcomes & Retrospective

The outcome selected-header tooltip is implemented and validated. The old plain raw-copy hover panel has been replaced by a structured outcome-only tooltip with Info, Crosshair, Banknote, and Shield Lucide iconography. The VM now produces a stable `:outcome-tooltip` map with the settlement condition, UTC settlement time, payout labels, and footer note. The row renderer consumes that map through a focused tooltip namespace. Non-outcome markets do not render `data-role "outcome-market-tooltip"`.

This change slightly increases UI code volume by adding one focused renderer namespace and one tooltip model helper. It reduces complexity in `row.cljs` by removing the inline raw `outcome-details-panel` and moving the bespoke tooltip surface out of the already broad row renderer. Remaining risk is limited to the governed design-review blind spot that default-state sampling does not exhaust every possible hover, active, disabled, and loading state, although deterministic Hiccup tests cover the tooltip structure and hover/focus classes.

## Context and Orientation

The selected market strip on the trade route is rendered by `src/hyperopen/views/active_asset_view.cljs`. That file calls `hyperopen.views.active-asset.vm/active-asset-panel-vm` to build a view model and then calls `hyperopen.views.active-asset.row/active-asset-row-from-vm` to render the desktop or mobile row.

Outcome markets are represented by maps with `:market-type :outcome`. The market can include fields such as `:title`, `:symbol`, `:outcome-details`, `:underlying`, `:target-price`, `:expiry-ms`, and `:outcome-sides`. The active-asset VM currently exposes `:is-outcome`, `:outcome-title`, `:outcome-details`, `:outcome-chance-label`, and `:open-interest-tooltip`. The desktop row uses `desktop-outcome-active-asset-row` when `:is-outcome` is true. Inside that row, the first cell has `data-role "outcome-market-name-hover-region"` and the old `outcome-details-panel` appears only for outcome markets.

The project uses Replicant Hiccup: UI is represented as Clojure vectors. Class attributes must be vectors of individual class names, not a single space-separated string. Inline style keys must be keywords. Existing Hiccup tests collect visible text and search nodes by `:data-role`.

Lucide icons are available through the `lucide` package. Existing code imports individual icons from paths such as `["lucide/dist/esm/icons/star.js" :default lucide-star-node]` and converts each node into an SVG child. This plan uses the user-provided icon guidance: `Info` for the title context, `Crosshair` for the settlement condition row, `Banknote` for the payout rule row, and `Shield` for the footer trust note. The selected market button already has a dropdown chevron, so this change does not replace that existing chevron.

## Plan of Work

First, update `test/hyperopen/views/active_asset/row_test.cljs`. Extend the existing `desktop-active-asset-row-renders-outcome-header-and-details-test` so it expects the new tooltip structure: `Outcome Details`, the explanatory sentence, `Settlement Condition`, the condition line, the UTC settlement date line, `Payout Rule`, `YES`, `$1.00`, `NO`, `$0.00`, and `All payouts are in USDC.` It should also assert `data-role "outcome-market-tooltip"` is present and that the non-outcome row test does not find that role.

Second, update `test/hyperopen/views/active_asset/vm_test.cljs`. Extend `active-asset-row-vm-projects-outcome-market-fields-test` to assert an `:outcome-tooltip` map. The expected map should include `:title`, `:summary`, `:settlement-label`, `:settlement-time-label`, `:yes-payout-label`, `:no-payout-label`, and `:footer-label`. The test fixture should use `:underlying "BTC"`, `:target-price 78213`, and `:expiry-ms 1777788000000` so the VM can build deterministic copy.

Third, create `src/hyperopen/views/active_asset/outcome_tooltip.cljs`. This namespace should own Lucide imports and the panel renderer. It should expose `outcome-tooltip-panel` that accepts the VM map and returns the screenshot-guided Hiccup. The panel should have `data-role "outcome-market-tooltip"`, `role "tooltip"`, a dark translucent surface, teal border, a small top arrow, an `Info` title icon, rows with `Crosshair` and `Banknote`, and a footer with `Shield`. The implementation must use class vectors and keyword style keys only.

Fourth, update `src/hyperopen/views/active_asset/vm.cljs`. Add a pure helper that builds the tooltip map only for outcome markets. It should prefer normalized fields for structured text and fall back to existing `:outcome-details` for the summary when required. The settlement condition should read like `BTC mark price is above 78,213`, and the time line should read like `at May 03, 2026 06:00 AM UTC` for `1777788000000`. The payout rule should render `YES -> $1.00 each` and `NO -> $0.00 each`, matching the user screenshot's USDC note. The old raw `:outcome-details` field can remain available for compatibility, but the row should use the new map for the selected-header tooltip.

Fifth, update `src/hyperopen/views/active_asset/row.cljs`. Require the new tooltip namespace, remove the old plain `outcome-details-panel`, and render `outcome-tooltip/outcome-tooltip-panel` inside the existing outcome name hover region. Preserve the existing `group/outcome-name` hover/focus behavior and `data-role "outcome-market-name-hover-region"` anchor so browser tests and CSS behavior remain stable.

Finally, run validation from `/Users/barry/.codex/worktrees/449e/hyperopen`. Use TDD order: run the focused test command after the test edits and confirm the new assertions fail, implement the production change, rerun the same tests and confirm they pass, then run `npm run check`, `npm test`, and `npm run test:websocket`. Because this is UI-facing work, also run the governed design review command `npm run qa:design-ui -- --targets trade-route --manage-local-app` or record a clear blocker if the browser-inspection environment cannot complete it. After any browser-inspection work, run `npm run browser:cleanup`.

## Concrete Steps

1. Edit tests in `test/hyperopen/views/active_asset/row_test.cljs` and `test/hyperopen/views/active_asset/vm_test.cljs`.

2. Run:

    cd /Users/barry/.codex/worktrees/449e/hyperopen
    npm test

   Expected RED result before production edits: the active-asset row or VM tests fail because `outcome-market-tooltip` and `:outcome-tooltip` do not exist yet.

3. Add `src/hyperopen/views/active_asset/outcome_tooltip.cljs`, update `src/hyperopen/views/active_asset/vm.cljs`, and update `src/hyperopen/views/active_asset/row.cljs`.

4. Run:

    cd /Users/barry/.codex/worktrees/449e/hyperopen
    npm test

   Expected GREEN result: the active-asset tests and the full generated CLJS suite pass.

5. Run:

    cd /Users/barry/.codex/worktrees/449e/hyperopen
    npm run check
    npm run test:websocket
    npm run qa:design-ui -- --targets trade-route --manage-local-app
    npm run browser:cleanup

   Expected result: required gates exit 0. The design review either exits 0 with `reviewOutcome: "PASS"` or is documented as blocked with the exact failing command and reason.

Actual validation results:

    npm test
    Ran 3710 tests containing 20488 assertions.
    0 failures, 0 errors.

    npm run check
    Completed successfully. Shadow builds for app, portfolio, portfolio-worker, portfolio-optimizer-worker, vault-detail-worker, and test reported 0 warnings.

    npm run test:websocket
    Ran 523 tests containing 3041 assertions.
    0 failures, 0 errors.

    npm run qa:design-ui -- --targets trade-route --manage-local-app
    reviewOutcome: "PASS"
    inspected widths: 375, 768, 1280, 1440
    visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf passes: PASS

    npm run test:playwright:smoke
    25 passed

    final rerun after fallback hardening:
    npm run test:playwright:smoke
    24 passed, 1 timeout in desktop spectate-mode modal smoke

    npx playwright test tools/playwright/test/spectate-mode-modal.smoke.spec.mjs --grep "desktop watchlist flow"
    1 passed

    npm run browser:cleanup
    {"ok":true,"stopped":[],"results":[]}

## Validation and Acceptance

The feature is accepted when an outcome market selected in the trade route opens the screenshot-guided tooltip on hover or keyboard focus of the selected-market header region. The tooltip must show `Outcome Details`, the explanatory copy, a settlement condition row, a payout rule row, and a USDC footer note. It must use Info, Crosshair, Banknote, and Shield iconography and a teal-on-dark treatment. Non-outcome markets must not render `data-role "outcome-market-tooltip"`.

The deterministic test evidence must include a RED run before production edits and a GREEN run after implementation. Final validation must include `npm run check`, `npm test`, and `npm run test:websocket`. Browser QA must explicitly account for visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf passes at widths 375, 768, 1280, and 1440, or report why any pass is blocked.

## Idempotence and Recovery

The edits are additive and localized. Re-running `npm test`, `npm run check`, and `npm run test:websocket` is safe. If the design review starts a local app or browser-inspection session, run `npm run browser:cleanup` before finishing. If tests fail after the renderer change, inspect the generated Hiccup text and `data-role` anchors before changing assertions; this feature is specifically about visible copy and structure.

## Artifacts and Notes

The user supplied two screenshots on 2026-05-02. The first shows the selected outcome market tooltip under `BTC above 78213 on May 3 at 2:00 AM?`. The second names the icon guidance: Lucide `Info`, `Crosshair`, `Banknote`, and `Shield`, with `CircleHelp` and `ShieldCheck` as alternatives.

Plan revision note: 2026-05-02 21:10Z - Initial active ExecPlan created for `hyperopen-b9dp` after mapping the existing active outcome header and old hover panel.

Plan revision note: 2026-05-02 21:14Z - Recorded RED test coverage, the initial missing-dependency test blocker, and the expected failing assertions before production edits.

Plan revision note: 2026-05-02 21:25Z - Recorded implementation and GREEN `npm test` evidence before broader validation gates.

Plan revision note: 2026-05-02 21:38Z - Recorded required gate, browser QA, Playwright smoke, and cleanup evidence; marked the implementation complete.

Plan revision note: 2026-05-02 21:56Z - Recorded final post-hardening validation evidence and the isolated Playwright smoke timeout with targeted rerun evidence.
