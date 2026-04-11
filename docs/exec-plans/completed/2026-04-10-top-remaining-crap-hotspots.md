# Reduce The Top Remaining CRAP Hotspots For `hyperopen-do7z`

This ExecPlan is the completed implementation record for `hyperopen-do7z`. It is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. `bd` remained the lifecycle source of truth while this file tracked the implementation and validation story.

## Purpose / Big Picture

This pass targeted the five remaining CRAP offenders the user identified in account, staking, trading, and market-normalization code:

- `/hyperopen/src/hyperopen/views/account_equity_view.cljs` `token-price-usd`
- `/hyperopen/src/hyperopen/views/staking/vm.cljs` `staking-vm`
- `/hyperopen/src/hyperopen/views/account_info_view.cljs` `format-pnl-percentage`
- `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs` `update-order-form`
- `/hyperopen/src/hyperopen/asset_selector/markets.cljs` `build-spot-markets`

The goal was to reduce real local complexity without changing public APIs, DOM anchors, or route-visible behavior on `/trade` and `/staking`.

## Final Status

- Result: complete for the five requested hotspots
- `bd` issue: `hyperopen-do7z`
- Final browser-QA state: `PASS`
- Remaining unrelated hotspot discovered during verification: `/hyperopen/src/hyperopen/asset_selector/markets.cljs` `build-perp-markets` still reports CRAP `110.0`, but it was not part of the requested scope and was not changed in this pass

## Progress

- [x] (2026-04-11 02:07Z) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/BROWSER_TESTING.md`, `/hyperopen/docs/FRONTEND.md`, and `/hyperopen/docs/agent-guides/browser-qa.md`.
- [x] (2026-04-11 02:07Z) Confirmed `hyperopen-do7z` existed in `bd` and created the active ExecPlan record.
- [x] (2026-04-11 02:08Z) Spawned `spec_writer`, `acceptance_test_writer`, and `edge_case_test_writer` subagents to sharpen scope, validation width, and focused regression ideas.
- [x] (2026-04-11 02:10Z) Audited the five hotspots and the existing direct, integration, ownership, and browser coverage already in repo.
- [x] (2026-04-11 02:16Z) Refactored the five hotspots into smaller helpers while preserving current public contracts and route-visible behavior.
- [x] (2026-04-11 02:20Z) Added focused regression coverage for the low-coverage helper seams and the extraction-sensitive transition/view-model paths.
- [x] (2026-04-11 02:21Z) Resolved missing local JS dependencies with `npm ci` so repo gates and browser tooling could run in this worktree.
- [x] (2026-04-11 02:24Z) Fixed namespace-size lint regressions by moving new focused tests into dedicated small namespaces instead of widening exceptions.
- [x] (2026-04-11 02:31Z) Ran compile, gates, coverage, module-scoped CRAP reports, focused Playwright checks, governed design review for `trade-route` and `staking-route`, and browser-inspection cleanup.
- [x] (2026-04-11 02:31Z) Moved the finished ExecPlan into `/hyperopen/docs/exec-plans/completed/`.

## Implementation Summary

### Source refactors

- `/hyperopen/src/hyperopen/views/account_equity_view.cljs`
  - Split token USD resolution into smaller helpers so `token-price-usd` only orchestrates precedence between row price, spot-market price, stable-token fallback, and `nil`.

- `/hyperopen/src/hyperopen/views/staking/vm.cljs`
  - Extracted normalized staking UI state, validator paging, delegator summary, loading/error selection, validator fallback, and action-popover shaping helpers so `staking-vm` is now a composition root instead of one branch-heavy map build.

- `/hyperopen/src/hyperopen/views/account_info_view.cljs`
  - Reduced `format-pnl-percentage` to a compact parse/round/sign/style pipeline.
  - Normalized rounded signed zero to `0` so the formatter does not emit a `-0.00%` style artifact.

- `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`
  - Split `update-order-form` into explicit stages for path/value normalization, canonical write application, path-specific reconciliation, and follow-up UI state handling.

- `/hyperopen/src/hyperopen/asset_selector/markets.cljs`
  - Split `build-spot-markets` into token-lookup, field-resolution, stats, and row-construction helpers.

### Focused test additions

- `/hyperopen/test/hyperopen/views/account_equity_view_token_price_test.cljs`
  - Added direct private-var coverage for `token-price-usd` precedence, inverse `USDC` pricing, and stable-token fallback.

- `/hyperopen/test/hyperopen/views/account_info_view_test.cljs`
  - Added direct formatter coverage for positive, negative, rounded-zero, invalid, `N/A`, and `nil` inputs.

- `/hyperopen/test/hyperopen/views/staking_view_test.cljs`
  - Added focused `staking-vm` coverage for page clamping, selected-validator fallback, and transfer balance fallback.

- `/hyperopen/test/hyperopen/trading/order_form_transitions_update_test.cljs`
  - Added a focused regression around side changes preserving TP/SL trigger values and offset inputs.

- `/hyperopen/test/hyperopen/asset_selector/markets_test.cljs`
  - Added direct spot-market coverage for token-id lookup.

## Validation

### Commands run

```bash
npm ci
npx shadow-cljs compile test
npm test
npm run test:websocket
npm run check
npm run coverage
bb tools/crap_report.clj --module src/hyperopen/views/account_equity_view.cljs --format json
bb tools/crap_report.clj --module src/hyperopen/views/staking/vm.cljs --format json
bb tools/crap_report.clj --module src/hyperopen/views/account_info_view.cljs --format json
bb tools/crap_report.clj --module src/hyperopen/trading/order_form_transitions.cljs --format json
bb tools/crap_report.clj --module src/hyperopen/asset_selector/markets.cljs --format json
npx playwright test tools/playwright/test/staking-regressions.spec.mjs tools/playwright/test/trade-regressions.spec.mjs --grep "staking route defaults to disconnected gating when no wallet is connected|staking timeframe menu opens and selects a deterministic option via debug actions|asset selector opens and selects ETH|order submit and cancel gating uses simulator-backed assertions"
npm run qa:design-ui -- --targets trade-route --manage-local-app
npm run qa:design-ui -- --targets staking-route --manage-local-app
npm run browser:cleanup
```

### Gate results

- `npx shadow-cljs compile test`: passed
- `npm test`: passed, `3105` tests and `16555` assertions
- `npm run test:websocket`: passed, `433` tests and `2482` assertions
- `npm run check`: passed
- `npm run coverage`: passed
  - Statements: `91.63%`
  - Branches: `69.66%`
  - Functions: `85.82%`
  - Lines: `91.63%`

### Module-scoped CRAP results

- `/hyperopen/src/hyperopen/views/account_equity_view.cljs`
  - `token-price-usd`: CRAP `5.0`
  - module `crappy-functions`: `0`

- `/hyperopen/src/hyperopen/views/staking/vm.cljs`
  - `staking-vm`: CRAP `13.0`
  - module `crappy-functions`: `0`

- `/hyperopen/src/hyperopen/views/account_info_view.cljs`
  - `format-pnl-percentage`: CRAP `8.0`
  - module `crappy-functions`: `0`

- `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`
  - `update-order-form`: CRAP `3.0`
  - module `crappy-functions`: `0`

- `/hyperopen/src/hyperopen/asset_selector/markets.cljs`
  - `build-spot-markets`: CRAP `3.0`
  - module `crappy-functions`: `1`
  - remaining unrelated offender: `build-perp-markets` CRAP `110.0`

## Browser QA Record

### Focused Playwright verification

The smallest existing committed checks that touched the changed trade and staking surfaces passed:

- `staking route defaults to disconnected gating when no wallet is connected @regression`
- `staking timeframe menu opens and selects a deterministic option via debug actions @regression`
- `asset selector opens and selects ETH @regression`
- `order submit and cancel gating uses simulator-backed assertions @regression`

Result: `4 passed (17.1s)`

### Governed design review

Trade review artifact:

- run dir: `/hyperopen/tmp/browser-inspection/design-review-2026-04-11T02-28-10-677Z-7197a57b`
- state: `PASS`
- widths covered: `375`, `768`, `1280`, `1440`
- passes covered: visual, native-control, styling-consistency, interaction, layout-regression, jank/perf

Staking review artifact:

- run dir: `/hyperopen/tmp/browser-inspection/design-review-2026-04-11T02-30-29-196Z-b2955a8a`
- state: `PASS`
- widths covered: `375`, `768`, `1280`, `1440`
- passes covered: visual, native-control, styling-consistency, interaction, layout-regression, jank/perf

Browser-inspection cleanup:

- `npm run browser:cleanup` stopped session `sess-1775874534200-f7de66`

Residual blind spots recorded by the design-review tool:

- The runner notes that hover, active, disabled, and loading states still require targeted route actions when those states are not present by default.
- This did not block signoff because the governed review still returned `PASS`, and the focused Playwright regressions exercised concrete interaction paths on both touched routes.

## Surprises & Discoveries

- Observation: `node_modules` was absent in this worktree at the start of implementation.
  Evidence: the first `npm run check` attempt failed on missing `smol-toml` and `zod`; `npm ci` resolved the local dependency state.

- Observation: namespace-size lint became the first real non-behavior regression after adding focused tests.
  Evidence: new direct helper tests initially pushed large existing test namespaces over their configured limits, so the fix was to move focused tests into dedicated small namespaces instead of widening repo guardrails.

- Observation: the browser-inspection CLI expects a comma-separated `--targets` value.
  Evidence: `npm run qa:design-ui -- --targets trade-route staking-route --manage-local-app` only selected `trade-route`; rerunning with the correct syntax or one target at a time produced the expected route coverage.

- Observation: design-review local-app startup collides with an already-running same-worktree watcher.
  Evidence: one rerun failed because a prior managed local app had left a current-worktree `shadow-cljs` server alive; explicitly stopping the leftover watcher and rerunning fixed the issue.

## Decision Log

- Decision: keep the five hotspot reductions in one coordinated pass under `hyperopen-do7z`.
  Rationale: compile, gate, coverage, CRAP, and browser-QA overhead were shared across all five functions.
  Date/Author: 2026-04-11 / Codex

- Decision: preserve public APIs, route anchors, and visible `/trade` and `/staking` behavior.
  Rationale: this was a deCRAP and focused-coverage pass, not product-surface redesign.
  Date/Author: 2026-04-11 / Codex

- Decision: prefer helper extraction plus focused regression tests over widening existing route suites.
  Rationale: the requested hotspots were mostly local complexity problems, and smaller direct tests preserved signal without making already-large namespaces exceed repo limits.
  Date/Author: 2026-04-11 / Codex

- Decision: accept the unrelated `build-perp-markets` offender as follow-up work rather than expand this ticket.
  Rationale: the user requested five named hotspots, and all five were successfully reduced below threshold.
  Date/Author: 2026-04-11 / Codex

## Outcomes & Retrospective

This pass met its requested goal. The five named hotspots all dropped below threshold, the repo gates and coverage run passed, and both `/trade` and `/staking` cleared deterministic browser checks plus governed design review.

The main implementation tradeoff was test placement rather than extraction strategy. The code refactors themselves were straightforward once the helper seams were chosen, but keeping new direct coverage inside existing large namespaces would have violated repo size rules. Splitting the new focused tests into narrow namespaces preserved both clarity and the existing lint policy.

The only notable remaining CRAP problem in the touched files is outside the requested scope: `/hyperopen/src/hyperopen/asset_selector/markets.cljs` still contains `build-perp-markets` at CRAP `110.0`. That should be handled as a separate follow-up slice instead of being bundled into this completed pass retroactively.

## Artifacts and Notes

- `bd` issue: `hyperopen-do7z`
- Trade design-review artifact: `/Users/barry/.codex/worktrees/03e6/hyperopen/tmp/browser-inspection/design-review-2026-04-11T02-28-10-677Z-7197a57b`
- Staking design-review artifact: `/Users/barry/.codex/worktrees/03e6/hyperopen/tmp/browser-inspection/design-review-2026-04-11T02-30-29-196Z-b2955a8a`
