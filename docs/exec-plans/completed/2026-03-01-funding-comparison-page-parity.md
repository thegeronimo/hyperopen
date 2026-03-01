# Funding Comparison Page Parity (Hyperliquid-Inspired)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, Hyperopen will expose a dedicated funding comparison page that mirrors the core behavior users expect from Hyperliquid’s funding comparison surface: one table that compares Hyperliquid, Binance, and Bybit predicted funding rates; shows Hyperliquid open interest context; supports timeframe scaling and search; and computes cross-exchange funding arbitrage deltas.

A user can verify this by navigating to `/funding-comparison`, searching by coin, changing timeframe (`hour`, `8hour`, `day`, `week`, `year`), and confirming row values/color semantics update deterministically.

## Progress

- [x] (2026-03-01 03:31Z) Reviewed planning/architecture/UI constraints: `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, `/hyperopen/docs/agent-guides/trading-ui-policy.md`.
- [x] (2026-03-01 03:31Z) Reverse-inspected Hyperliquid funding comparison behavior via browser inspection artifacts and production JS chunk analysis.
- [x] (2026-03-01 03:31Z) Verified live `predictedFundings` response shape directly from `POST https://api.hyperliquid.xyz/info` with body `{"type":"predictedFundings"}`.
- [x] (2026-03-01 03:31Z) Authored this ExecPlan with implementation milestones and acceptance criteria.
- [x] (2026-03-01 05:41Z) Implemented API wrappers and projection state for predicted fundings.
- [x] (2026-03-01 05:41Z) Implemented funding comparison action/effect wiring and runtime contracts.
- [x] (2026-03-01 05:41Z) Implemented funding comparison VM/view and route/header integration.
- [x] (2026-03-01 05:41Z) Added/adjusted tests across API/runtime/schema/view layers.
- [x] (2026-03-01 06:58Z) Ran required gates successfully: `npm run check`, `npm test`, `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Hyperliquid funding page data is split across two sources, not one payload.
  Evidence: Funding table comes from `info` `predictedFundings`; Hyperliquid OI comes from live asset contexts (`allDexsAssetCtxs` / `openInterest * oraclePx`), not from `predictedFundings` rows.

- Observation: `predictedFundings` payload is a nested tuple structure.
  Evidence: Live sample shape is `[[coin, [[venue, payload-or-null]...]], ...]` with venues like `HlPerp`, `BinPerp`, `BybitPerp`.

- Observation: Hyperliquid normalizes non-HL venue rates to hourly with fallback coin heuristics when `fundingIntervalHours` is missing.
  Evidence: Funding chunk function `N()` applies venue-specific defaults and coin allowlists (`1h`, `2h`, `4h`, else `8h`).

## Decision Log

- Decision: Implement a dedicated feature module (`funding_comparison`) instead of overloading vault route loaders.
  Rationale: Keeps route ownership explicit and avoids coupling unrelated concerns under `load-vault-route`.
  Date/Author: 2026-03-01 / Codex

- Decision: Use existing Hyperopen market projection (`:asset-selector :market-by-key`) as Hyperliquid OI source for this iteration.
  Rationale: Repository already normalizes perp OI in USD and keeps websocket side effects out views; this preserves current architecture seams while delivering user-visible parity.
  Date/Author: 2026-03-01 / Codex

- Decision: Implement Hyperliquid-compatible timeframe scaling and interval fallback heuristics in pure VM logic.
  Rationale: Deterministic, testable calculations align with reliability and purity guardrails.
  Date/Author: 2026-03-01 / Codex

## Outcomes & Retrospective

Outcome: Implemented a new funding comparison route with Hyperliquid-inspired structure and deterministic VM calculations, including timeframe scaling, search/filtering, sortable columns, and arb deltas against Hyperliquid baseline values.

Validation: All required gates passed after implementation and post-fix cleanup:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Retrospective:

- Initial implementation introduced a bracket mismatch in the new view and a malformed interval-normalization expression in the VM; both were corrected before final validation.
- Test coverage additions caught projection and wiring regressions early; adding end-to-end runtime wiring tests prevented route dispatch omissions.

## Context and Orientation

Current Hyperopen route rendering lives in `/hyperopen/src/hyperopen/views/app_view.cljs` and header navigation in `/hyperopen/src/hyperopen/views/header_view.cljs`. Runtime action/effect registration is explicit and centralized across:

- `/hyperopen/src/hyperopen/registry/runtime.cljs`
- `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`

External data access for this feature should live in market gateway layers:

- Endpoint definition: `/hyperopen/src/hyperopen/api/endpoints/market.cljs`
- Gateway wrapper: `/hyperopen/src/hyperopen/api/gateway/market.cljs`
- Public/default facade: `/hyperopen/src/hyperopen/api/default.cljs`
- Instance builder seam: `/hyperopen/src/hyperopen/api/instance.cljs`

State projection ownership for network results is in `/hyperopen/src/hyperopen/api/projections.cljs`, and app defaults live in `/hyperopen/src/hyperopen/state/app_defaults.cljs`.

Target Hyperliquid behavior (from reverse inspection):

1. On page load, request `predictedFundings` from `/info`.
2. Build row columns: `Coin`, `Hyperliquid OI`, `Hyperliquid`, `Binance`, `Binance-HL Arb`, `Bybit`, `Bybit-HL Arb`.
3. Timeframe selector applies multipliers (`hour=1`, `8hour=8`, `day=24`, `week=168`, `year=8760`) over hourly-normalized rates.
4. Search filters by coin text.
5. Favorites are prioritized in sort ordering.
6. Color semantics use neutral threshold around `1e-4` (8h basis) and green/red for positive/negative values.

## Plan of Work

Milestone 1 adds request/projection plumbing for predicted funding rows. In `/hyperopen/src/hyperopen/api/endpoints/market.cljs`, add `request-predicted-fundings!` posting `{"type":"predictedFundings"}` with high priority defaults. Thread it through `/hyperopen/src/hyperopen/api/gateway/market.cljs`, `/hyperopen/src/hyperopen/api/default.cljs`, and `/hyperopen/src/hyperopen/api/instance.cljs` with matching wrapper names. Add projection helpers in `/hyperopen/src/hyperopen/api/projections.cljs` and funding-comparison defaults in `/hyperopen/src/hyperopen/state/app_defaults.cljs`.

Milestone 2 adds feature orchestration and contracts. Add `/hyperopen/src/hyperopen/funding_comparison/actions.cljs` and `/hyperopen/src/hyperopen/funding_comparison/effects.cljs`. Actions own route matching and UI-state transitions; effects own network side effects. Register new action/effect IDs through runtime registry/composition/collaborators files and include strict argument contracts in `/hyperopen/src/hyperopen/schema/contracts.cljs`. Add effect-order policy for any new heavy action that must project UI state before fetch.

Milestone 3 adds UI and derivation logic. Add `/hyperopen/src/hyperopen/views/funding_comparison/vm.cljs` for all numeric normalization, timeframe conversions, arb math, sort/filter/favorites ordering, and color semantics. Add `/hyperopen/src/hyperopen/views/funding_comparison_view.cljs` for render-only logic (search, timeframe controls, table/cards). Integrate route rendering in `/hyperopen/src/hyperopen/views/app_view.cljs` and navigation entry in `/hyperopen/src/hyperopen/views/header_view.cljs`.

Milestone 4 adds/updates tests. Extend market endpoint/gateway/default/instance tests, projection tests, action/effect tests, schema coverage/runtime wiring tests, startup runtime route-dispatch tests, and view/VM tests for the new surface. Keep calculations pure and covered with deterministic fixtures.

## Concrete Steps

From `/hyperopen`:

1. Implement API + projection + defaults changes.
2. Implement funding-comparison action/effect modules and runtime registrations.
3. Implement VM + view and route/nav integration.
4. Add/adjust tests for every touched seam.
5. Run:
   npm run check
   npm test
   npm run test:websocket

Expected terminal outcomes are zero failing tests/lints and successful shadow-cljs compiles.

## Validation and Acceptance

Acceptance is behavior-based:

1. Navigate to `/funding-comparison` and observe a dedicated funding comparison view with heading, search input, timeframe controls, and a comparison table.
2. Enter a search string (for example `BTC`) and confirm rows filter immediately.
3. Toggle timeframe controls and confirm percentage columns and arb columns update consistently.
4. Confirm loading/error state handling: a loading state appears before network completion, and errors surface as user-visible text if request fails.
5. Confirm runtime validation/contracts pass by running required gates.

Test acceptance:

- `npm run check` passes.
- `npm test` passes with new/updated funding comparison tests included.
- `npm run test:websocket` passes.

## Idempotence and Recovery

All changes are additive and safe to rerun. If implementation fails mid-way, rerun the same test commands after fixes; no destructive migrations are involved. Route wiring and API wrappers are pure code changes with standard git revert/cherry-pick recovery paths.

## Artifacts and Notes

Key reverse-inspection artifacts (local):

- Browser inspection bundle artifacts under `/hyperopen/tmp/browser-inspection/inspect-2026-03-01T03-22-16-116Z-e08f6ddc/`.
- Hyperliquid funding chunk analyzed at `/tmp/hyperliquid-funding-chunk.js`.

Live endpoint probe used for schema confirmation:

- `curl -s https://api.hyperliquid.xyz/info -H 'Content-Type: application/json' --data '{"type":"predictedFundings"}'`

## Interfaces and Dependencies

New interfaces to exist after implementation:

- API market endpoint/gateway wrappers:
  - `hyperopen.api.endpoints.market/request-predicted-fundings!`
  - `hyperopen.api.gateway.market/request-predicted-fundings!`
  - `hyperopen.api.default/request-predicted-fundings!`
  - `hyperopen.api.instance` market ops key `:request-predicted-fundings!`

- Feature actions/effects:
  - action IDs: `:actions/load-funding-comparison-route`, `:actions/load-funding-comparison`, `:actions/set-funding-comparison-query`, `:actions/set-funding-comparison-timeframe`, `:actions/set-funding-comparison-sort`
  - effect ID: `:effects/api-fetch-predicted-fundings`

- View/VM entry points:
  - `hyperopen.views.funding-comparison.vm/funding-comparison-vm`
  - `hyperopen.views.funding-comparison-view/funding-comparison-view`

Revision note (2026-03-01 / Codex): Initial plan authored from live reverse-inspection findings; implementation steps and acceptance criteria are defined before code changes.
