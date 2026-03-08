# Small-Viewport Hyperliquid Parity Implementation

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and must be maintained in accordance with that file.

## Purpose / Big Picture

After this work, HyperOpen should be materially closer to Hyperliquid on phone (`390x844`) and tablet (`1024x1366`) for `/trade`, `/portfolio`, and `/vaults`. A contributor should be able to run the app locally, inspect those routes in the browser, and see that `/trade` is chart-first and less vertically stacked on phone, tablet layouts become multi-column sooner, and `/vaults` carries less hero and card chrome on smaller screens.

The visible proof is a narrower visual gap in browser-inspection compare artifacts against Hyperliquid, plus updated `bd` issues under epic `hyperopen-wrr` and a QA note capturing the final manual comparison.

## Progress

- [x] (2026-03-07 19:48 EST) Re-read `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, and `/hyperopen/docs/agent-guides/trading-ui-policy.md`.
- [x] (2026-03-07 19:49 EST) Confirmed the existing follow-up epic `hyperopen-wrr` from the 2026-03-07 audit and avoided creating a duplicate epic.
- [x] (2026-03-07 19:49 EST) Created and claimed child issues `hyperopen-wrr.1` through `hyperopen-wrr.5` covering trade, portfolio, and vault small-viewport parity work.
- [x] (2026-03-07 21:10 EST) Implemented trade small-viewport layout parity (`hyperopen-wrr.2`) and chrome-density parity (`hyperopen-wrr.3`), including mobile surface tabs, earlier tablet right-rail ticket layout, denser header/active-asset/ticket surfaces, and tighter account-tab navigation.
- [x] (2026-03-07 21:18 EST) Implemented portfolio summary/action-row parity (`hyperopen-wrr.4`) and account-surface parity (`hyperopen-wrr.5`), including phone KPI compaction, earlier tablet multi-column summary layout, shortened action labels, and portfolio-safe `Interest` / `Deposits & Withdrawals` tab affordances.
- [x] (2026-03-07 21:24 EST) Implemented vaults small-viewport parity (`hyperopen-wrr.1`), including lighter hero treatment, smaller controls, denser phone cards, and removing the disabled CTA below `xl`.
- [x] (2026-03-07 21:41 EST) Ran required repository validation: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-03-07 21:59 EST) Ran browser-inspection QA against Hyperliquid for `/trade`, `/portfolio`, and `/vaults` on phone and tablet and wrote `/hyperopen/docs/qa/small-viewport-hyperliquid-parity-implementation-qa-2026-03-08.md`.
- [x] (2026-03-07 22:08 EST) Closed completed child issues, updated epic `hyperopen-wrr`, and moved this plan to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: `bd` writes serialize through Dolt and reject parallel writes with transaction conflicts.
  Evidence: parallel `bd create` attempts returned `serialization failure: this transaction conflicts with a committed transaction from another client`.

- Observation: The current trade layout is explicitly single-column below `lg`, a two-column hybrid at `lg`, and only puts the order ticket in a right rail at `xl`.
  Evidence: `/hyperopen/src/hyperopen/views/trade_view.cljs` uses `grid-cols-1` by default, `lg:grid-cols-[minmax(0,1fr)_280px]` with the ticket below both columns, and `xl:grid-cols-[minmax(0,1fr)_280px_280px]` with the ticket in column three.

- Observation: Local browser QA had to target `http://localhost:8081` instead of `http://localhost:8080`.
  Evidence: the active `shadow-cljs watch` session reported `TCP Port 8080 in use` and served the updated app on `8081`.

- Observation: The March 7 vault compare artifacts are not geometry-compatible with the March 8 QA rerun, so vault ratio deltas are not directly comparable.
  Evidence: the March 7 vault phone screenshots were `3072x4098` and the March 8 phone rerun was `1170x2532`; the March 7 vault tablet screenshots were `780x1688` and the March 8 rerun was `2048x2732`.

## Decision Log

- Decision: Reuse epic `hyperopen-wrr` and create child tasks instead of opening a new top-level epic.
  Rationale: The user explicitly asked for an epic only if one did not already exist, and the audit already created the correct follow-up epic.
  Date/Author: 2026-03-07 / Codex

- Decision: Treat parity as “narrow the visual and functional gap significantly” rather than “clone Hyperliquid exactly.”
  Rationale: HyperOpen has additive features and different data surfaces. The safe target is closer composition, density, and access patterns without inventing unsupported data or breaking existing flows.
  Date/Author: 2026-03-07 / Codex

- Decision: For `/portfolio`, use the closest safe parity path for `Interest` and `Deposits & Withdrawals` instead of fabricating backend data surfaces that do not exist.
  Rationale: The audit found missing visible affordances, but the current codebase does not expose exact account-history equivalents for both surfaces. The implementation should improve tab affordance parity without misleading users about data provenance.
  Date/Author: 2026-03-07 / Codex

## Outcomes & Retrospective

This wave completed the planned small-viewport parity implementation across `/trade`, `/portfolio`, and `/vaults`.

The highest-value structural gaps from the original audit were removed:

- `/trade` phone no longer renders all major surfaces as one long default stack.
- `/trade` tablet keeps the order ticket in a right rail at `1024px`.
- `/portfolio` now promotes the summary region into a multi-column tablet layout at `1024px`.
- `/portfolio` small-view action and account surfaces are denser and closer to Hyperliquid's visible IA.
- `/vaults` carries less small-view hero/CTA chrome and uses tighter controls/cards.

Validation completed successfully:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Browser QA result: the gap narrowed materially overall, with the clearest wins on `/trade` and `/portfolio`.

- `/trade` phone visual diff ratio improved from `0.2388` to `0.1283`.
- `/trade` tablet improved from `0.1263` (`high`) to `0.0743` (`medium`).
- `/portfolio` phone improved from `0.1689` (`high`) to `0.0940` (`medium`).
- `/portfolio` tablet improved from `0.0828` (`medium`) to `0.0325` (`low`).

The QA note is `/hyperopen/docs/qa/small-viewport-hyperliquid-parity-implementation-qa-2026-03-08.md`. The machine-readable compare summary is `/hyperopen/tmp/browser-inspection/mobile-tablet-layout-summaries-2026-03-08.json`.

Residual gap: `/vaults` is improved but still stylistically different from Hyperliquid, and the March 7 vault baselines are not geometry-compatible with the March 8 rerun. Treat any remaining vault work as follow-up polish rather than a blocker on this wave.

## Context and Orientation

The parity audit that drives this work is `/hyperopen/docs/qa/hyperopen-vs-hyperliquid-mobile-tablet-audit-2026-03-07.md`. That document identified three route families that matter here.

`/trade` is composed in `/hyperopen/src/hyperopen/views/trade_view.cljs`. The active-market strip above the chart lives in `/hyperopen/src/hyperopen/views/active_asset_view.cljs`. The order ticket lives in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`. The order book and trades panel lives in `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`. The account-history tabs below the trade stack live in `/hyperopen/src/hyperopen/views/account_info_view.cljs`.

`/portfolio` is composed in `/hyperopen/src/hyperopen/views/portfolio_view.cljs`, with stateful selectors and chart-tab behavior in `/hyperopen/src/hyperopen/portfolio/actions.cljs`. The account tabs reused at the bottom also come from `/hyperopen/src/hyperopen/views/account_info_view.cljs`.

`/vaults` list-page layout lives in `/hyperopen/src/hyperopen/views/vaults/list_view.cljs`.

The global header that the audit identified as too heavy on smaller screens lives in `/hyperopen/src/hyperopen/views/header_view.cljs`. Route selection happens in `/hyperopen/src/hyperopen/views/app_view.cljs`.

If new small-viewport UI state is needed, default application state lives in `/hyperopen/src/hyperopen/state/app_defaults.cljs`. If new UI actions are added, wire them through `/hyperopen/src/hyperopen/runtime/collaborators.cljs`, `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`, and `/hyperopen/src/hyperopen/schema/contracts.cljs`.

“Phone” means `390x844`. “Tablet” means `1024x1366`. “Parity” in this plan means composition, hierarchy, density, and smaller-view functionality feel materially closer to Hyperliquid when visually compared side by side.

## Plan of Work

Start with `/trade` because it has the largest information-architecture gap. Add an explicit smaller-view trade-surface switch so phone does not render chart, order book, ticket, and account tables as one long stack. Rework the `lg` layout so tablet width presents the ticket beside the chart instead of below it. Tighten the active-market strip, ticket spacing, and account-tab chrome in the same pass because those pieces visually compound the stacked feeling.

Then move to `/portfolio`. Promote the summary region into a multi-column tablet layout before `xl`, keep the KPI pair side by side on phone, and shorten action labels for smaller viewports. After that, update the shared account-tab strip so it behaves better on narrow widths and add the closest safe parity affordances for the missing visible portfolio surfaces.

Finish with `/vaults`. Reduce hero weight and hide or defer the disabled CTA on smaller screens, compact the filter controls, and flatten the phone cards so they read more like a dense responsive list. Keep the same underlying data and sorting behavior.

When the UI changes are in place, run the required repository gates. Then use the browser-inspection tooling under `/hyperopen/tools/browser-inspection/` to capture fresh phone and tablet comparisons for HyperOpen versus Hyperliquid on `/trade`, `/portfolio`, and `/vaults`. Write a short QA note under `/hyperopen/docs/qa/` that states whether the gap is materially narrower and cites the artifact paths.

## Concrete Steps

Run all commands from `/hyperopen`.

1. Edit the trade layout and any supporting small-viewport state/action plumbing.
2. Edit the shared header, active-market strip, ticket density, and account-tab navigation.
3. Edit the portfolio summary grid, KPI cards, action labels, and portfolio account-surface affordances.
4. Edit the vaults list-page chrome and mobile/tablet density.
5. Run:

   npm run check
   npm test
   npm run test:websocket

6. Run browser-inspection compare captures for phone and tablet against Hyperliquid for:

   - `http://localhost:8080/trade`
   - `http://localhost:8080/portfolio`
   - `http://localhost:8080/vaults`

   and:

   - `https://app.hyperliquid.xyz/trade`
   - `https://app.hyperliquid.xyz/portfolio`
   - `https://app.hyperliquid.xyz/vaults`

7. Write the QA summary in `/hyperopen/docs/qa/`.
8. Close the completed `bd` child issues and move this plan to `/hyperopen/docs/exec-plans/completed/`.

## Validation and Acceptance

Acceptance is behavior and evidence based.

On `/trade`, phone should no longer be a single long stack of all primary panels, and tablet should keep order entry visible beside the chart. On `/portfolio`, `1024px` width should present the summary region in multiple columns, and the phone KPI/action band should be visibly denser. On `/vaults`, small viewports should show less hero/CTA chrome and denser list rows.

Validation requires all three repository gates to pass:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Validation also requires fresh browser-inspection evidence showing the updated HyperOpen layouts beside Hyperliquid on the six audited route/viewport combinations.

## Idempotence and Recovery

The view edits in this plan are additive and local to UI composition. Re-running the validation gates is safe. Re-running browser-inspection compares creates new timestamped artifact directories under `/hyperopen/tmp/browser-inspection/` and does not overwrite prior evidence.

If a new action is added and the UI fails to dispatch it, check the registration path in `/hyperopen/src/hyperopen/runtime/collaborators.cljs`, `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`, and `/hyperopen/src/hyperopen/schema/contracts.cljs`. If a browser-inspection session fails, stop it, restart the local app, and rerun the compare command to produce a fresh artifact directory.

## Artifacts and Notes

Expected artifacts for this plan:

- Updated UI files under `/hyperopen/src/hyperopen/views/`
- Possibly updated UI state/action plumbing under `/hyperopen/src/hyperopen/state/`, `/hyperopen/src/hyperopen/runtime/`, and `/hyperopen/src/hyperopen/schema/`
- Fresh browser-inspection evidence under `/hyperopen/tmp/browser-inspection/`
- A QA note under `/hyperopen/docs/qa/`
- Closed child issues `hyperopen-wrr.1` through `hyperopen-wrr.5`

## Interfaces and Dependencies

This work depends on existing view and action interfaces rather than backend schema changes.

If a new small-viewport trade state is introduced, it should live in `/hyperopen/src/hyperopen/state/app_defaults.cljs` and be mutated only by registered UI actions that emit projection effects first. If account-tab labels or ordering need context-specific overrides, that behavior should be implemented in `/hyperopen/src/hyperopen/views/account_info_view.cljs` without changing the underlying account-history fetch ownership or reducer semantics.

Browser QA depends on the browser-inspection CLI in `/hyperopen/tools/browser-inspection/src/cli.mjs`.

Plan revision note: 2026-03-07 19:49 EST - Created the implementation plan after the audit, added child `bd` tasks under `hyperopen-wrr`, and recorded the intended parity strategy before any UI edits.
Plan revision note: 2026-03-07 22:08 EST - Completed implementation, validation, and browser QA; moved plan to `completed` with the final QA note and artifact references.
