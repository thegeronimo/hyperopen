# Implement Dedicated Portfolio Route with Hyperliquid-Style Phase 1 Layout and Basic Functionality

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Hyperopen currently routes `/portfolio` to the trade screen, so users cannot access a portfolio-first workflow. After this change, `/portfolio` will render a dedicated portfolio page with a Hyperliquid-aligned information hierarchy: title and actions, KPI cards, account summary/chart shell, and account detail tables. A user can verify this by opening `/portfolio` and seeing the dedicated layout, while existing account-table interactions continue to work.

## Progress

- [x] (2026-02-19 23:41Z) Reviewed required architecture/frontend/planning docs and extracted current Hyperopen route/view structure.
- [x] (2026-02-19 23:41Z) Inspected Hyperliquid `/portfolio` via desktop/tablet/mobile screenshots and loaded route chunks to capture structure and behavior.
- [x] (2026-02-19 23:41Z) Authored `/hyperopen/docs/product-specs/portfolio-page-parity-prd.md` with full requirements and phase split.
- [x] (2026-02-19 23:41Z) Authored this ExecPlan in `/hyperopen/docs/exec-plans/active/`.
- [x] (2026-02-19 23:48Z) Implemented `/portfolio` view namespace and state-derived KPI helpers in `/hyperopen/src/hyperopen/views/portfolio_view.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`.
- [x] (2026-02-19 23:48Z) Wired app routing and header active-state behavior for trade vs portfolio in `/hyperopen/src/hyperopen/views/app_view.cljs` and `/hyperopen/src/hyperopen/views/header_view.cljs`.
- [x] (2026-02-19 23:48Z) Added/adjusted tests for routing, header active nav classes, and portfolio view rendering in `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs`, `/hyperopen/test/hyperopen/views/header_view_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`, and `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`.
- [x] (2026-02-19 23:48Z) Ran required validation gates successfully: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-19 23:48Z) Updated this plan with implementation outcomes and remaining follow-up scope.

## Surprises & Discoveries

- Observation: Hyperliquid’s portfolio route is modularized into lazy-loaded chunks and composes separate KPI cards, account summary, graph toggles, and wide account table modules.
  Evidence: `/hyperopen/tmp/hyperliquid-185.pretty.js` and `/hyperopen/tmp/hyperliquid-6951.pretty.js`.

- Observation: Hyperliquid mobile layout keeps the same section order but stacks action and KPI cards vertically, preserving controls and key data.
  Evidence: `/hyperopen/tmp/hyperliquid-portfolio-mobile390.png`.

- Observation: Hyperopen already has strong reusable account components (`account-equity-view`, `account-info-view`) that can satisfy Phase 1 without backend changes.
  Evidence: `/hyperopen/src/hyperopen/views/account_equity_view.cljs` and `/hyperopen/src/hyperopen/views/account_info_view.cljs`.

- Observation: Account tab labels in Hyperopen include count suffixes (for example `Open Orders (0)`), so assertions should not assume plain label text.
  Evidence: failing assertion during `npm test` in `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs` before updating the expectation.

- Observation: docs lint rejects machine-specific absolute paths in documentation files.
  Evidence: `npm run check` initially failed with `[machine-specific-path]` on portfolio PRD before converting references to `/hyperopen/...` paths.

## Decision Log

- Decision: Scope implementation to Phase 1 parity (layout and baseline functionality), not full workflow parity.
  Rationale: User requested “at least initial layout and basic functionality”; this minimizes risk and keeps delivery inside existing runtime contracts.
  Date/Author: 2026-02-19 / Codex

- Decision: Reuse existing account detail and equity derivations instead of introducing new API flows.
  Rationale: Preserves determinism and avoids regressions in websocket/runtime boundaries.
  Date/Author: 2026-02-19 / Codex

- Decision: Make header navigation active state route-aware for `/trade` and `/portfolio`.
  Rationale: Required for clear navigation parity and route correctness.
  Date/Author: 2026-02-19 / Codex

- Decision: Use route prefix matching (`str/starts-with?`) for trade/portfolio route classification.
  Rationale: Supports route variants (`/portfolio/...`, `/trade/...`) while preserving current fallback behavior.
  Date/Author: 2026-02-19 / Codex

- Decision: Keep chart panel in Phase 1 as a shell with affordances (`Account Value`, `PNL`) rather than implementing full historical plotting parity.
  Rationale: Meets initial-layout/basic-functionality requirement without introducing new data contracts.
  Date/Author: 2026-02-19 / Codex

## Outcomes & Retrospective

Completed for Phase 1.

What was achieved:
- Added a dedicated portfolio route/view composition:
  - `/hyperopen/src/hyperopen/views/portfolio_view.cljs`
  - `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
- Added reusable equity metric exposure for the new VM:
  - `/hyperopen/src/hyperopen/views/account_equity_view.cljs`
- Wired app routing and route-aware nav highlighting:
  - `/hyperopen/src/hyperopen/views/app_view.cljs`
  - `/hyperopen/src/hyperopen/views/header_view.cljs`
- Added test coverage for portfolio rendering, VM derivation, and route integration:
  - `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`
  - `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`
  - `/hyperopen/test/hyperopen/views/header_view_test.cljs`
  - `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs`
  - `/hyperopen/test/test_runner.cljs`
- Added product artifacts:
  - `/hyperopen/docs/product-specs/portfolio-page-parity-prd.md`
  - `/hyperopen/docs/exec-plans/completed/2026-02-19-portfolio-page-phase1-layout-and-basics.md`
  - `/hyperopen/docs/product-specs/index.md` updated with the new PRD entry.

Validation evidence:
- `npm run check`: pass.
- `npm test`: pass (`1146` tests, `5318` assertions, `0` failures, `0` errors).
- `npm run test:websocket`: pass (`135` tests, `587` assertions, `0` failures, `0` errors).

Remaining follow-up scope:
- full action-modal parity for each top-row button workflow,
- full charting parity with selectable timeframe/domain data,
- native Hyperopen tabs for `Interest` and `Deposits and Withdrawals` instead of relying on existing trade-oriented tab set.

## Context and Orientation

Routing entrypoint is `/hyperopen/src/hyperopen/views/app_view.cljs`. Today it only renders `trade-view` for all routes. Header nav is in `/hyperopen/src/hyperopen/views/header_view.cljs` and currently hard-codes Trade active/Portfolio inactive. Trade screen composition is in `/hyperopen/src/hyperopen/views/trade_view.cljs` and already contains reusable account components.

Reusable data surfaces for portfolio:
- `/hyperopen/src/hyperopen/views/account_equity_view.cljs`: account-level metrics and summaries.
- `/hyperopen/src/hyperopen/views/account_info_view.cljs`: tabbed account tables and controls.
- `/hyperopen/src/hyperopen/views/account_info/projections.cljs`: balance and portfolio value helpers.
- `/hyperopen/src/hyperopen/domain/trading/core.cljs`: default fee constants.

Test anchors to update:
- `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs`
- `/hyperopen/test/hyperopen/views/header_view_test.cljs`
- new portfolio view test namespace in `/hyperopen/test/hyperopen/views/`
- `/hyperopen/test/test_runner.cljs`

## Plan of Work

Milestone 1 creates a dedicated portfolio view namespace that assembles the Hyperliquid-like section order: heading/actions, KPI cards, account summary/chart shell, and account tables. This milestone also adds a small pure view-model helper to derive Phase 1 KPI values from existing state and provide deterministic fallbacks.

Milestone 2 wires routing and header active state. `/portfolio` should render the new view while `/trade` remains unchanged. Header nav links should compute active classes from current route.

Milestone 3 adds or adjusts tests to confirm route rendering and active nav behavior, and to verify core portfolio layout nodes/metrics render safely with sparse state.

Milestone 4 runs required repository gates and records outcomes in this plan.

## Concrete Steps

1. Add portfolio view modules.

   Create:
   - `/hyperopen/src/hyperopen/views/portfolio_view.cljs`
   - `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`

   Implement a Phase 1 layout that includes:
   - page title,
   - action button row,
   - KPI summary cards (volume, fees, equity summary, chart shell),
   - embedded account info panel via existing account-info view.

2. Wire route handling.

   Edit `/hyperopen/src/hyperopen/views/app_view.cljs` to render portfolio view for `/portfolio`.

3. Make header active state route-aware.

   Edit `/hyperopen/src/hyperopen/views/header_view.cljs` so active classes are derived from current route for `Trade` and `Portfolio` links.

4. Add/adjust tests.

   Create and/or edit:
   - `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`
   - `/hyperopen/test/hyperopen/views/header_view_test.cljs`
   - `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs`
   - `/hyperopen/test/test_runner.cljs`

5. Run required gates from `/hyperopen`:

       npm run check
       npm test
       npm run test:websocket

## Validation and Acceptance

Acceptance for this plan iteration is satisfied when:

1. `/portfolio` renders a dedicated portfolio layout with title, action row, KPI cards, and account table panel.
2. `Trade` and `Portfolio` nav active classes switch by route.
3. Portfolio KPI helpers produce deterministic values/fallbacks without runtime errors when data is absent.
4. Existing account-table interactions continue to render under the portfolio page.
5. `npm run check`, `npm test`, and `npm run test:websocket` pass.

## Idempotence and Recovery

All changes are source-only and repeatable. If a step fails:

1. Re-run commands safely; no schema or persisted data migration is involved.
2. For route regressions, temporarily route `/portfolio` back to trade view while preserving tests and portfolio namespace for iterative fixes.
3. For style/layout regressions, keep behavior tests and refine classes without changing action contracts.

## Artifacts and Notes

Research artifacts used for parity extraction:
- `/hyperopen/tmp/hyperliquid-portfolio-full.png`
- `/hyperopen/tmp/hyperliquid-portfolio-1024.png`
- `/hyperopen/tmp/hyperliquid-portfolio-mobile390.png`
- `/hyperopen/tmp/hyperliquid-185.pretty.js`
- `/hyperopen/tmp/hyperliquid-6951.pretty.js`

Requirement artifact:
- `/hyperopen/docs/product-specs/portfolio-page-parity-prd.md`

## Interfaces and Dependencies

Public interfaces to preserve:
- router path contract in `/hyperopen/src/hyperopen/router.cljs`
- navigation action contract `:actions/navigate`
- account panel APIs in `/hyperopen/src/hyperopen/views/account_info_view.cljs`

New additive interfaces expected:
- `hyperopen.views.portfolio.vm/portfolio-vm`
- `hyperopen.views.portfolio-view/portfolio-view`

No websocket client contract changes are planned.

Plan revision note: 2026-02-19 23:41Z - Initial plan created after Hyperliquid portfolio inspection and scoped to Phase 1 dedicated route/layout with baseline data wiring.
Plan revision note: 2026-02-19 23:48Z - Updated after implementation with completed progress entries, discovered test/docs-lint adjustments, validation outcomes, and remaining parity follow-ups.
