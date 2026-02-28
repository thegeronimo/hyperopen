# Asset Selector Live Market Columns via Existing WebSocket Stream

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, the Asset Selector table (Last Price, 24H Change, 8H Funding, Volume, Open Interest) will update continuously while the selector is open, using the already-open websocket connection instead of waiting for a manual/full REST refresh. A user will be able to open the selector, leave it open, and observe market values changing in-place as websocket messages arrive.

You will verify this by opening the selector in a running app, watching values change without closing/reopening, and running the required test gates (`npm run check`, `npm test`, `npm run test:websocket`).

## Progress

- [x] (2026-02-28 16:05Z) Reviewed `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md` for ExecPlan requirements and required section structure.
- [x] (2026-02-28 16:09Z) Audited selector data ownership in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`, `/hyperopen/src/hyperopen/views/active_asset_view.cljs`, and `/hyperopen/src/hyperopen/api/projections.cljs`.
- [x] (2026-02-28 16:13Z) Audited websocket feeds and payload fields in `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs`, `/hyperopen/src/hyperopen/websocket/webdata2.cljs`, and `/hyperopen/src/hyperopen/websocket/market_projection_runtime.cljs`.
- [x] (2026-02-28 16:18Z) Chosen implementation direction: selector-live updates sourced from `activeAssetCtx` on the existing websocket connection, with scoped selector-specific subscriptions and projection batching.
- [x] (2026-02-28 16:24Z) Authored this ExecPlan with milestones, acceptance criteria, idempotence/recovery guidance, and required validation gates.
- [x] (2026-02-28 15:55Z) Added shared selector query derivation module at `/hyperopen/src/hyperopen/asset_selector/query.cljs` and refactored `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` to consume shared filter/sort/window logic.
- [x] (2026-02-28 16:01Z) Implemented owner-aware active-ctx subscription semantics in `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs` (owner-scoped subscribe/unsubscribe with last-owner transport teardown).
- [x] (2026-02-28 16:03Z) Added selector subscription reconciliation effect `:effects/sync-asset-selector-active-ctx-subscriptions`, wired runtime registration/contracts, and dispatched it from selector visibility/window-changing actions.
- [x] (2026-02-28 16:05Z) Implemented live selector row patching from `activeAssetCtx` via `/hyperopen/src/hyperopen/asset_selector/market_live_projection.cljs`, invoked in frame-coalesced active-ctx projection updates.
- [x] (2026-02-28 16:09Z) Added regression coverage for selector live projection, owner-scoped subscriptions, and sync effect wiring; required gates passed (`npm run check`, `npm test`, `npm run test:websocket`).

## Surprises & Discoveries

- Observation: Selector rows are only updated through full market fetch projections, not websocket patching.
  Evidence: `/hyperopen/src/hyperopen/api/projections.cljs` writes `[:asset-selector :markets]` and `[:asset-selector :market-by-key]` in `apply-asset-selector-success`; websocket modules do not write these paths.

- Observation: `activeAssetCtx` payload already contains all fields needed for selector columns (`markPx`, `prevDayPx`, `funding`, `dayNtlVlm`, `openInterest`).
  Evidence: `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs` transforms those fields into `:mark`, `:change24hPct`, `:fundingRate`, `:volume24h`, and `:openInterest`.

- Observation: Existing `activeAssetCtx` subscription state is a plain set per coin, without ownership semantics, so selector-driven subscribe/unsubscribe would conflict with active-asset ownership.
  Evidence: `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs` keeps `{:subscriptions #{...}}` and unconditionally sends unsubscribe when asked.

- Observation: Selector opening does not force refresh once `:phase` is `:full`, so stale values can persist for long sessions.
  Evidence: `/hyperopen/src/hyperopen/asset_selector/actions.cljs` `toggle-asset-dropdown` only fetches when markets are empty or phase is not `:full`.

- Observation: Active-asset context projection stores funding as percentage points, but selector rows store funding as decimal fraction before view formatting.
  Evidence: `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs` writes `:fundingRate (* 100 funding)` for active asset contexts, while `/hyperopen/src/hyperopen/asset_selector/markets.cljs` writes selector `:fundingRate funding`; selector live patching must preserve selector decimal semantics.

## Decision Log

- Decision: Use the existing websocket connection with `activeAssetCtx` as the live source for selector columns.
  Rationale: This stream already carries all required selector metrics and avoids introducing a second transport connection or polling loop.
  Date/Author: 2026-02-28 / Codex

- Decision: Add selector-scoped market subscriptions (bounded to currently rendered selector rows), not global subscriptions for every market.
  Rationale: Subscribing every market continuously is unnecessary and likely high-cost; selector-visible scope keeps network and reducer load bounded while fixing visible staleness.
  Date/Author: 2026-02-28 / Codex

- Decision: Introduce owner-aware `activeAssetCtx` subscription tracking.
  Rationale: Active asset and selector live-updates must coexist without one unsubscribing the other’s stream.
  Date/Author: 2026-02-28 / Codex

- Decision: Apply selector row patches through market projection coalescing (frame-batched), not immediate raw `swap!` writes on every websocket message.
  Rationale: This preserves deterministic responsiveness and avoids excessive store writes during burst traffic.
  Date/Author: 2026-02-28 / Codex

## Outcomes & Retrospective

Implemented and validated.

Delivered outcomes:

- Selector-visible rows now receive continuous websocket-driven updates from existing `activeAssetCtx` messages while the selector is open.
- Selector and active-asset subscriptions are owner-scoped and coexist safely; transport unsubscribe is emitted only after the last owner releases a coin.
- Selector query/filter/sort/window derivation is shared between view rendering and subscription reconciliation, reducing drift risk.
- Selector market live-patching is pure and deterministic in `/hyperopen/src/hyperopen/asset_selector/market_live_projection.cljs`, and is applied inside frame-coalesced market projection writes.
- Required gates passed on this branch:
  - `npm run check`
  - `npm test`
  - `npm run test:websocket`

## Context and Orientation

The selector UI table is rendered in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`, and the source list is `[:asset-selector :markets]` from app state (via `/hyperopen/src/hyperopen/views/active_asset_view.cljs`). The current full list is built from `metaAndAssetCtxs` + `webData2` snapshots and projected by `apply-asset-selector-success` in `/hyperopen/src/hyperopen/api/projections.cljs`.

The websocket client is a single runtime connection in `/hyperopen/src/hyperopen/websocket/client.cljs`. `activeAssetCtx` currently updates only `[:active-assets :contexts <coin>]` through `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs`. It does not patch selector rows.

In this plan, “selector-visible rows” means markets currently rendered by selector virtualization (the list portion users can see, plus overscan window), not all known markets in memory. “Owner-aware subscription” means each coin subscription tracks who asked for it (for example, `:active-asset` vs `:asset-selector`) so unsubscribing one owner does not tear down the stream for another owner.

## Plan of Work

### Milestone 1: Extract Shared Selector Window Derivation

Create a pure selector-derivation helper module that both the view and websocket subscription reconciler can use. Move (or duplicate safely, then converge) the filtering/sorting/windowing logic currently embedded in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` into a reusable namespace under `/hyperopen/src/hyperopen/asset_selector/`.

At the end of this milestone, there is one deterministic function that returns the currently rendered selector market keys from state inputs (markets, search, sort, favorites, tab, render-limit, scroll-top), and the view still renders exactly as before.

### Milestone 2: Add Owner-Aware ActiveAssetCtx Subscription Runtime

Refactor `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs` subscription bookkeeping from a plain set to per-coin ownership (for example `{:owners-by-coin {"BTC" #{:active-asset :asset-selector}}}`). Keep public call sites backward-compatible by defaulting existing `subscribe-active-asset-ctx!` and `unsubscribe-active-asset-ctx!` behavior to owner `:active-asset`, then add explicit owner variants for selector sync.

At the end of this milestone, subscribe/unsubscribe calls from different owners are reference-safe: the transport unsubscribe is sent only when the last owner releases a coin.

### Milestone 3: Add Selector Subscription Reconciler Effect

Add a new effect path (for example `:effects/sync-asset-selector-active-ctx-subscriptions`) that computes desired selector-visible coin subscriptions using the shared selector-derivation helper, diffs against the currently selector-owned set, and issues owner-scoped subscribe/unsubscribe operations through the active-asset-ctx module.

Wire this effect into selector state transitions that can change the visible set:

- open/close selector,
- search/strict/favorites/tab/sort changes,
- render-limit and scroll-window changes.

Ensure projection effects remain first; subscription reconciliation runs after state writes.

At the end of this milestone, selector-visible coins have active websocket subscriptions while the selector is visible, and selector-owned subscriptions are cleaned up on close.

### Milestone 4: Patch Selector Markets on ActiveAssetCtx Messages

Extend active-asset-ctx websocket handling so incoming `activeAssetCtx` updates also patch matching entries in `[:asset-selector :markets]` and `[:asset-selector :market-by-key]` when present. Keep this patching pure and deterministic in a dedicated helper module (for example `/hyperopen/src/hyperopen/asset_selector/market_live_projection.cljs`).

Use `queue-market-projection!` to batch these writes by frame. Preserve existing active-asset context updates.

At the end of this milestone, selector rows for subscribed markets update in place as websocket payloads arrive.

### Milestone 5: Regression Tests and Required Gates

Add focused tests for:

- owner-aware subscribe/unsubscribe semantics in `/hyperopen/test/hyperopen/websocket/active_asset_ctx_test.cljs`,
- selector subscription reconciliation diff behavior (including open/close and scroll/limit changes),
- selector market patch projection correctness from activeAssetCtx payload fields,
- no regression for active asset panel updates.

Run required gates and capture green evidence.

At the end of this milestone, websocket-driven selector updates are behaviorally protected and validated by required commands.

## Concrete Steps

From `/hyperopen`:

1. Add shared selector-derivation helpers.

   Edit/create:

   - `/hyperopen/src/hyperopen/asset_selector/<new-shared-query-module>.cljs`
   - `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` (consume shared logic)

   Run:

       npm test -- test/hyperopen/views/asset_selector_view_test.cljs

   Expected result: selector view tests stay green with unchanged rendering behavior.

2. Add owner-aware activeAssetCtx subscription APIs.

   Edit:

   - `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs`
   - `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
   - `/hyperopen/src/hyperopen/websocket/subscriptions_runtime.cljs`

   Run:

       npm run test:websocket -- --focus active_asset_ctx

   Expected result: new ownership tests pass; existing active-asset subscribe/unsubscribe tests remain green.

3. Implement selector subscription reconciliation effect.

   Edit:

   - `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
   - `/hyperopen/src/hyperopen/asset_selector/actions.cljs`
   - optional helper under `/hyperopen/src/hyperopen/asset_selector/`

   Run:

       npm test -- test/hyperopen/asset_selector/actions_test.cljs

   Expected result: selector action tests validate sync effect dispatch and close cleanup behavior.

4. Implement selector market live patch projection from activeAssetCtx.

   Edit/create:

   - `/hyperopen/src/hyperopen/asset_selector/market_live_projection.cljs` (or equivalent)
   - `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs`

   Run:

       npm run test:websocket

   Expected result: websocket tests include selector-patch assertions; no regressions in existing market projection tests.

5. Run required validation gates.

   Run:

       npm run check
       npm test
       npm run test:websocket

   Expected result: all required gates pass.

## Validation and Acceptance

Acceptance is complete when all of the following are true:

1. Opening the selector and leaving it open shows live movement in table values without reopening or manual refresh action.
2. Last Price, 24H Change, Funding, Volume, and Open Interest columns for selector-visible rows update from websocket payloads.
3. Active asset stream behavior is unchanged (active strip still updates and does not lose data when selector opens/closes).
4. Selector open/close and scroll/filter changes correctly subscribe/unsubscribe selector-owned market streams without tearing down active-asset ownership.
5. `[:asset-selector :markets]` and `[:asset-selector :market-by-key]` remain consistent after live patches (same keys, updated numeric fields).
6. Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

This plan is designed for safe incremental delivery.

- Shared query and projection helpers are additive and can land before runtime wiring.
- Owner-aware subscription tracking should preserve legacy behavior by default owner fallback.
- If selector subscription reconciliation causes instability, disable only selector-owned reconciliation effects while keeping active-asset owner behavior intact.
- If live patching causes regressions, keep subscriptions but gate selector patch application behind a temporary feature flag in app state until tests are corrected.

Recovery path: revert milestone-by-milestone to the last passing commit while retaining newly added regression tests where possible.

## Artifacts and Notes

Primary files expected to change:

- `/hyperopen/src/hyperopen/asset_selector/actions.cljs`
- `/hyperopen/src/hyperopen/asset_selector/query.cljs`
- `/hyperopen/src/hyperopen/asset_selector/market_live_projection.cljs`
- `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`
- `/hyperopen/src/hyperopen/websocket/active_asset_ctx.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
- `/hyperopen/src/hyperopen/runtime/api_effects.cljs`
- `/hyperopen/src/hyperopen/app/effects.cljs`
- `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
- `/hyperopen/src/hyperopen/registry/runtime.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`

Primary tests expected to change:

- `/hyperopen/test/hyperopen/websocket/active_asset_ctx_test.cljs`
- `/hyperopen/test/hyperopen/asset_selector/market_live_projection_test.cljs`
- `/hyperopen/test/hyperopen/asset_selector/actions_test.cljs`
- `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters_test.cljs`
- `/hyperopen/test/hyperopen/app/effects_test.cljs`
- `/hyperopen/test/hyperopen/runtime/wiring_test.cljs`

Capture evidence during implementation:

- Test output snippets showing owner-aware subscription tests and selector live-patch tests passing.
- A short runtime verification note (manual): selector stays open and values change in-place.
- Final gate outputs for required commands.

## Interfaces and Dependencies

Modules/interfaces to preserve:

- Keep websocket connection ownership in `/hyperopen/src/hyperopen/websocket/client.cljs`; do not add a second websocket transport.
- Keep action/effect contracts registry-compatible through `/hyperopen/src/hyperopen/registry/runtime.cljs` and `/hyperopen/src/hyperopen/runtime/registry_composition.cljs` when adding new effect ids.
- Keep selector rendering deterministic and pure at the view boundary; move shared derivation logic into pure helper namespaces.

New interfaces to add (names may vary but responsibilities must exist):

- A pure selector-visible-keys derivation function under `/hyperopen/src/hyperopen/asset_selector/` used by both view and selector-subscription reconciler.
- Owner-aware `activeAssetCtx` subscription API (subscribe/unsubscribe with owner token) with backward-compatible default behavior.
- A pure selector market live-patch function that maps one normalized activeAssetCtx payload into selector market row updates.

No external libraries are required; use existing repository utilities (`hyperopen.utils.formatting`, projection runtime, and websocket modules).
