# Candle Snapshot Migration to WebSocket `candle` Subscriptions

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

This slice removes high-churn chart candle refresh dependence on repeated `/info` `candleSnapshot` POST calls by introducing websocket `candle` subscriptions for the active trading chart context. After this change, active asset/timeframe candle data is kept live by websocket subscription updates, while REST snapshot requests remain bounded one-shot backfill for cold cache and fallback conditions.

You can verify this by selecting chart timeframes and active assets in tests: candle subscription sync effects are emitted and REST fetches only occur when `should-fetch-candle-snapshot?` allows backfill.

## Progress

- [x] (2026-03-05 05:18Z) Claimed `hyperopen-nhv.3` and audited current candle flow (REST snapshot + trades patching; no dedicated candle subscription module).
- [x] (2026-03-05 05:30Z) Added websocket candle module at `/hyperopen/src/hyperopen/websocket/candles.cljs` (subscription ownership/sync + payload handler).
- [x] (2026-03-05 05:36Z) Wired active-asset/timeframe lifecycle to candle sync in `/hyperopen/src/hyperopen/websocket/subscriptions_runtime.cljs` and websocket effect adapters.
- [x] (2026-03-05 05:39Z) Added runtime effect registration and contracts for `:effects/sync-active-candle-subscription`.
- [x] (2026-03-05 05:42Z) Updated startup websocket module initialization to include candle handler registration.
- [x] (2026-03-05 05:47Z) Updated websocket domain policy/health matching so `candle` topic is tracked as market-data stream and descriptor matching includes `{coin, interval}`.
- [x] (2026-03-05 05:52Z) Added/updated regression coverage across chart actions, subscriptions runtime, websocket adapters, contracts, startup runtime, domain policy/model/health, plus new candle module tests.
- [x] (2026-03-05 05:55Z) Validation pass: `npx shadow-cljs compile ws-test && node out/ws-test.js` (`323 tests, 1788 assertions, 0 failures`).
- [ ] Run full required quality gates in an environment with npm-script `shadow-cljs` PATH and `@noble/secp256k1` available.
- [ ] Close `hyperopen-nhv.3`, then continue remaining epic tasks (`hyperopen-nhv.1`, `hyperopen-nhv.2`, epic closeout).

## Surprises & Discoveries

- Observation: Existing migration work only suppressed redundant REST fetches when candle cache existed; there was still no websocket candle subscription lifecycle.
  Evidence: `/hyperopen/src/hyperopen/websocket/subscriptions_runtime.cljs` previously only decided whether to call `fetch-candle-snapshot!`.

- Observation: Chart timeframe changes need a dedicated effect to sync subscription target even when REST fetch is skipped.
  Evidence: Without a separate effect, `select-chart-timeframe` cache-hit path emitted no heavy network effect and could not switch websocket interval subscription.

- Observation: Setting a fixed stale threshold for `candle` can cause false delayed status for high intervals if provider cadence is sparse.
  Evidence: Health model uses one threshold per topic, not per interval.

## Decision Log

- Decision: Add `:effects/sync-active-candle-subscription` instead of overloading `:effects/fetch-candle-snapshot`.
  Rationale: Subscription lifecycle must run even when fetch is intentionally skipped (warm cache), and keeping concerns separate preserves deterministic effect ordering semantics.
  Date/Author: 2026-03-05 / Codex

- Decision: Keep REST snapshot backfill via existing `should-fetch-candle-snapshot?` gate.
  Rationale: Provides bounded cold-start/recovery fallback while websocket migration remains flag-gated.
  Date/Author: 2026-03-05 / Codex

- Decision: Track `candle` as `:market_data` group and matcher descriptor `{coin, interval}`, but keep stale-threshold unset by default.
  Rationale: Avoid interval-cadence false positives in health while preserving descriptor-level stream accounting.
  Date/Author: 2026-03-05 / Codex

## Outcomes & Retrospective

The active chart candle flow is now websocket-first behind `:candle-subscriptions?`:

- Candle subscription target is synchronized to active asset + selected timeframe.
- Websocket candle payloads are merged into `[:candles coin interval]` with deterministic timestamp upsert behavior.
- REST `candleSnapshot` remains bounded fallback only when cache/backfill policy permits.
- Startup now initializes candle websocket handling alongside existing market/user modules.

This completes the implementation goals of `hyperopen-nhv.3`; remaining gap is environment-only full-gate execution.

## Context and Orientation

Relevant files:

- `/hyperopen/src/hyperopen/websocket/candles.cljs`: new candle module (subscription ownership + message handler).
- `/hyperopen/src/hyperopen/websocket/subscriptions_runtime.cljs`: active-asset/timeframe sync orchestration.
- `/hyperopen/src/hyperopen/chart/actions.cljs`: timeframe action now emits candle-sync effect before optional backfill fetch.
- `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs` and `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`: runtime effect wiring.
- `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs` and `/hyperopen/src/hyperopen/schema/contracts.cljs`: new effect registration/argument contract.
- `/hyperopen/src/hyperopen/startup/runtime.cljs` and `/hyperopen/src/hyperopen/startup/collaborators.cljs`: startup init wiring.
- `/hyperopen/src/hyperopen/websocket/domain/policy.cljs`, `/hyperopen/src/hyperopen/websocket/domain/model.cljs`, `/hyperopen/src/hyperopen/websocket/health.cljs`: topic tier/group and descriptor matching.

## Plan of Work

Implement dedicated candle websocket subscription state and channel handler, wire lifecycle synchronization through active-asset/timeframe effects, and preserve REST backfill as bounded fallback. Then extend contracts/registrations and health matching, and add focused regression coverage that proves sync/backfill behavior and message ingestion semantics.

## Concrete Steps

From `/hyperopen`:

1. Add candle module and handler:
   - `/hyperopen/src/hyperopen/websocket/candles.cljs`
2. Wire lifecycle sync and runtime effect:
   - `/hyperopen/src/hyperopen/websocket/subscriptions_runtime.cljs`
   - `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs`
   - `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
   - `/hyperopen/src/hyperopen/app/effects.cljs`
   - `/hyperopen/src/hyperopen/chart/actions.cljs`
3. Register/contract new effect:
   - `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`
   - `/hyperopen/src/hyperopen/schema/contracts.cljs`
4. Extend startup + websocket domain/health maps:
   - `/hyperopen/src/hyperopen/startup/runtime.cljs`
   - `/hyperopen/src/hyperopen/startup/collaborators.cljs`
   - `/hyperopen/src/hyperopen/websocket/domain/policy.cljs`
   - `/hyperopen/src/hyperopen/websocket/domain/model.cljs`
   - `/hyperopen/src/hyperopen/websocket/health.cljs`
5. Add/adjust tests:
   - `/hyperopen/test/hyperopen/websocket/candles_test.cljs`
   - `/hyperopen/test/hyperopen/websocket/subscriptions_runtime_test.cljs`
   - `/hyperopen/test/hyperopen/core_bootstrap/chart_menu_and_storage_test.cljs`
   - `/hyperopen/test/hyperopen/runtime/effect_adapters/websocket_test.cljs`
   - `/hyperopen/test/hyperopen/runtime/effect_adapters/facade_contract_test.cljs`
   - `/hyperopen/test/hyperopen/runtime/validation_test.cljs`
   - `/hyperopen/test/hyperopen/schema/contracts_test.cljs`
   - `/hyperopen/test/hyperopen/startup/runtime_test.cljs`
   - `/hyperopen/test/hyperopen/websocket/domain/policy_test.cljs`
   - `/hyperopen/test/hyperopen/websocket/domain/model_test.cljs`
   - `/hyperopen/test/hyperopen/websocket/health_test.cljs`

## Validation and Acceptance

Acceptance checks:

1. Active chart candle subscription follows active asset/timeframe.
2. Timeframe selection can sync subscription even when REST backfill is skipped.
3. Bounded REST backfill still occurs on cold cache/fallback paths.
4. Candle websocket payloads are merged into candle store deterministically.
5. Regression suite remains green.

Validation run:

    npx shadow-cljs compile ws-test && node out/ws-test.js
    Ran 323 tests containing 1788 assertions.
    0 failures, 0 errors.

## Idempotence and Recovery

Changes are additive and idempotent. Re-running subscription sync is safe because ownership logic deduplicates wire subscribe calls. If websocket candle migration needs rollback, set `:candle-subscriptions? false` (runtime override or config) and the sync effect clears candle subscription owner while REST fetch behavior remains available.

## Artifacts and Notes

Key artifact:

    Testing hyperopen.websocket.candles-test
    Ran 323 tests containing 1788 assertions.
    0 failures, 0 errors.

## Interfaces and Dependencies

New internal module:

- `/hyperopen/src/hyperopen/websocket/candles.cljs`
  - `sync-candle-subscription!`
  - `clear-owner-subscription!`
  - `create-candles-handler`
  - `init!`

New runtime effect id:

- `:effects/sync-active-candle-subscription` (args contract uses `::fetch-candle-snapshot-args` shape).

Revision note (2026-03-05): Added websocket candle subscription lifecycle + ingestion path, runtime effect wiring, startup initialization, and regression coverage for `hyperopen-nhv.3`.
