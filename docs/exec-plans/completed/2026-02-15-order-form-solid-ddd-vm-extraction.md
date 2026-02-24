# Order Form SOLID/DDD VM Extraction

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The order form view currently mixes rendering, domain inference, submit orchestration, and formatting in one namespace. After this change, the view will render from a dedicated ViewModel and shared trading selectors so UI edits do not require touching domain policy code. A user-visible result is that the order form keeps the same behavior, but symbol inference, read-only market handling (spot/HIP-3), and submit gating come from a single source used by both rendering and submit actions.

## Progress

- [x] (2026-02-15 19:51Z) Created active ExecPlan with milestones, acceptance criteria, and required validation commands.
- [x] (2026-02-15 19:54Z) Added `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs` with `order-form-vm`, order-type config, submit tooltip policy, and derived display state.
- [x] (2026-02-15 19:54Z) Added shared market identity selector (`market-identity`) to domain/state trading layers and wired `/hyperopen/src/hyperopen/order/actions.cljs` submit guard to reuse it.
- [x] (2026-02-15 19:55Z) Refactored `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` to consume VM output and render order-type sections from VM-configured section keys.
- [x] (2026-02-15 19:56Z) Added selector coverage in `/hyperopen/test/hyperopen/state/trading_test.cljs` for symbol/read-only inference.
- [x] (2026-02-15 19:57Z) Ran required gates: `npm run check`, `npm test`, `npm run test:websocket` (all green).

## Surprises & Discoveries

- Observation: The repository already has broad `order_form_view` snapshot-style coverage in `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs`, which reduces refactor risk for UI parity.
  Evidence: The file has dozens of assertions for tab state, field ordering, slider/toggle semantics, submit disabled state, and scale preview output.
- Observation: A slash-delimited `:active-asset` (for example `PURR/USDC`) intentionally infers `spot?` true even if `:market-type` is `:perp`.
  Evidence: Initial new selector test failed in `npm test` until the non-spot case used `:active-asset \"PURR\"`.

## Decision Log

- Decision: Execute the refactor as an additive extraction first (new VM + new selectors), then reduce view logic, instead of a full multi-file component split in one pass.
  Rationale: This keeps behavior stable while still addressing the highest-impact SOLID/DDD issue (single responsibility violation in `order_form_view.cljs`).
  Date/Author: 2026-02-15 / Codex
- Decision: Keep presentational primitives in `order_form_view.cljs` for this pass and extract only policy/orchestration into VM + selectors.
  Rationale: Existing coverage is centered on rendered Hiccup shape; extracting primitives in a second pass avoids broad snapshot churn while still removing domain logic from the view.
  Date/Author: 2026-02-15 / Codex
- Decision: Make toggle input IDs deterministic from label text (with optional override) instead of per-render `gensym`.
  Rationale: Stable IDs improve accessibility consistency and avoid render-to-render label binding drift.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

The order form now has a clean ViewModel boundary. The view consumes one VM map and no longer performs inline market/symbol inference, submit-prep orchestration, or order-type section branching. Shared `market-identity` policy is used by both rendering and submit action guards, which removes duplicated spot/HIP-3 parsing logic and aligns UI/action behavior. Required gates passed after a single test-fixture correction in the new selector coverage.

## Context and Orientation

The current order form render path starts in `/hyperopen/src/hyperopen/views/trade_view.cljs` and renders `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`. That file currently computes domain flags (for example spot/HIP-3 read-only checks), display fallbacks (for example fallback limit price display), and submit gating in the same `let` that builds Hiccup nodes.

Trading domain behavior is exposed through `/hyperopen/src/hyperopen/state/trading.cljs`, which wraps `/hyperopen/src/hyperopen/domain/trading/*.cljs`. Submit action orchestration is in `/hyperopen/src/hyperopen/order/actions.cljs`.

In this plan, a “ViewModel” means a pure data map produced before rendering that contains all derived values the view needs (flags, labels, sections, submit state). The view then focuses on layout and dispatch wiring.

## Plan of Work

Milestone 1 introduces shared market identity selectors in the trading domain/state boundary. Add functions that derive base symbol, quote symbol, and read-only flags (`spot?`, `hip3?`, `read-only?`) from active asset and active market data. Update submit action logic to use this selector instead of local string parsing.

Milestone 2 adds `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs` with one primary function `order-form-vm`. This function will normalize the form, compute summary and submit-prep values, compute UI flags (entry mode, limit-like behavior, slider display percent, tooltip text), and define order-type configuration for labels/dropdown order/section visibility.

Milestone 3 refactors `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` to render from the VM output. The view keeps presentational helpers and action vectors, but domain derivation and branching logic move into VM-backed data. Replace repeated order-type conditionals with a config-driven section renderer.

Milestone 4 updates tests in `/hyperopen/test/hyperopen/state/trading_test.cljs` and, where required, `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` for selector and VM-driven behavior parity.

## Concrete Steps

From `/hyperopen`:

1. Add market identity helper functions in `/hyperopen/src/hyperopen/domain/trading/market.cljs`, re-export through `/hyperopen/src/hyperopen/domain/trading.cljs`, and wrap through `/hyperopen/src/hyperopen/state/trading.cljs`.
2. Update `/hyperopen/src/hyperopen/order/actions.cljs` to use shared market identity in `submit-order`.
3. Create `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs` with `order-form-vm`, order-type config, and submit-tooltip builder.
4. Refactor `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` to consume VM maps, remove inline market/symbol inference, and render order-type sections by config.
5. Add tests for market identity selectors and maintain existing view behavior tests.
6. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

Expected transcript shape:

  - `npm run check` completes with no warnings or errors.
  - `npm test` completes with 0 failures and 0 errors.
  - `npm run test:websocket` completes with 0 failures and 0 errors.

## Validation and Acceptance

Acceptance is met when the following are true:

- `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` no longer computes market read-only inference, symbol parsing, submit-prep orchestration, or price fallback display policy inline.
- `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs` exists and exposes one ViewModel function used by the view.
- Submit action read-only checks and view read-only banners use a shared selector source (no duplicated string parsing logic).
- Existing order form view tests continue passing, and new selector tests cover spot/HIP-3/symbol fallback derivation.
- Required validation gates pass.

## Idempotence and Recovery

The implementation is additive-first. If a regression appears, the safe rollback is to keep new selector/VM files in place and temporarily switch the view/action call sites back to their previous local derivation logic, then re-run tests. No destructive migration or data operation is involved.

## Artifacts and Notes

Changed files:

- `/hyperopen/src/hyperopen/domain/trading/market.cljs`
- `/hyperopen/src/hyperopen/domain/trading.cljs`
- `/hyperopen/src/hyperopen/state/trading.cljs`
- `/hyperopen/src/hyperopen/order/actions.cljs`
- `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs`
- `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`
- `/hyperopen/test/hyperopen/state/trading_test.cljs`

Validation summary:

- `npm run check`: pass (`shadow-cljs compile app` and `shadow-cljs compile test` completed with 0 warnings).
- `npm test`: pass (795 tests, 3090 assertions, 0 failures, 0 errors).
- `npm run test:websocket`: pass (86 tests, 267 assertions, 0 failures, 0 errors).

## Interfaces and Dependencies

At completion, these interfaces must exist and be used:

- `/hyperopen/src/hyperopen/state/trading.cljs`: `market-identity` returning at least `{:spot? :hip3? :read-only? :base-symbol :quote-symbol}`.
- `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs`: `order-form-vm` returning normalized form data, control flags, submit state, and order-type config metadata.
- `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`: view rendering consumes VM output and does not perform domain parsing heuristics.

Plan revision note: 2026-02-15 19:51Z - Initial plan created for SOLID/DDD extraction of order form view into shared selectors + VM.
Plan revision note: 2026-02-15 19:57Z - Updated living sections after completing implementation, selector tests, and all required validation gates.
