# Open Orders Cancel Stale Writer Guard

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan follows `.agents/PLANS.md` from the repository root. Keep it self-contained and update it whenever scope, evidence, or implementation decisions change. The `bd` issue for this work is `hyperopen-b54a`, titled `Investigate stale Open Orders cancel feedback`.

## Purpose / Big Picture

Users can cancel an order from Open Orders, receive no durable visible confirmation, and still see the row until a manual refresh removes it. Investigation showed the normal cancel-success path prunes local open-order sources and shows a toast, but it does not protect the UI from stale writers. A late `openOrders` websocket payload or stale `frontendOpenOrders` response can write the canceled `oid` back into open-order state after `pending-cancel-oids` is cleared. The result is a canceled row that can remain or reappear until a fresher snapshot or page reload arrives.

After this work, a successfully canceled order must be suppressed from all Open Orders sources for the lifetime of the current account runtime, stale open-order writers must not reintroduce it, and a forced post-mutation refresh must not join an older in-flight open-orders request. The user-visible effect is that a successful cancel removes the row and keeps it removed even if late stale websocket or REST data arrives.

## Progress

- [x] (2026-04-21 00:36Z) Confirmed live bug issue `hyperopen-b54a` and copied the investigation conclusion into this plan.
- [x] (2026-04-21 00:36Z) Created this active ExecPlan under `docs/exec-plans/active/`.
- [x] (2026-04-21 RED) Added RED regression coverage for cancel-success suppression, stale websocket open-orders writes, stale REST open-orders projections, and force-refresh single-flight bypass.
- [x] (2026-04-21 RED) Ran `npx shadow-cljs --force-spawn compile test && node out/test.js`; compile completed with `0 warnings`, and the runner exited `1` with `7 failures, 0 errors` on the intended new regression assertions.
- [x] (2026-04-21 00:49Z) Implemented the smallest production change: shared runtime canceled-oid guard, cancel-success recording/pruning, websocket/REST stale filtering, runtime reset/default state, and force-refresh single-flight bypass.
- [x] (2026-04-21 00:49Z GREEN) Ran `npx shadow-cljs --force-spawn compile test && node out/test.js`; compile completed with `0 warnings`, and the runner exited `0` with `3342 tests`, `18242 assertions`, `0 failures`, `0 errors`.
- [x] (2026-04-21 00:51Z) Ran required gates. `npm run check` exited `0`; `npm test` exited `0` with `3342 tests`, `18242 assertions`, `0 failures`, `0 errors`; `npm run test:websocket` exited `0` with `451 tests`, `2708 assertions`, `0 failures`, `0 errors`.
- [x] (2026-04-21 00:51Z) Browser QA accounted for: no Browser MCP or Playwright session was required because the deterministic state-level regression tests cover cancel success, stale websocket writers, stale REST projections, and force-refresh request coalescing; no DOM rendering flow or browser-test tooling changed.
- [x] (2026-04-21 00:58Z) Read-only review found two correctness gaps to address before completion: post-cancel REST refreshes need an active-address guard, and the cancel guard should use richer identity when order rows expose asset/dex dimensions.
- [x] (2026-04-21 01:04Z RED) Ran `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js`; compile completed with `0 warnings`, and the runner exited `1` with `3 failures, 0 errors` on the follow-up stale-refresh and rich cancel-guard assertions.
- [x] (2026-04-21 01:08Z) Implemented the production-only follow-up fix: active-wallet guard for post-cancel open-orders refresh success/error writes, richer internal cancel guard entries with asset/dex-aware matching, writer filtering via the richer guard, and runtime/default reset for the richer guard key.
- [x] (2026-04-21 01:09Z GREEN) Ran `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js`; compile completed with `0 warnings`, and the runner exited `0` with `3344 tests`, `18249 assertions`, `0 failures`, `0 errors`.
- [x] (2026-04-21 01:11Z) Ran repo validation gates after the follow-up fix. `npm test` exited `0` with `3344 tests`, `18249 assertions`, `0 failures`, `0 errors`; `npm run test:websocket` exited `0` with `451 tests`, `2709 assertions`, `0 failures`, `0 errors`; `npm run check` initially exited `1` at `lint:namespace-sizes` because the expanded regression suites needed namespace-size metadata updates.
- [x] (2026-04-21 01:18Z) Added namespace-size metadata for `test/hyperopen/core_bootstrap/order_effects/cancel_test.cljs`, raised the existing `test/hyperopen/startup/runtime_test.cljs` allowance for the cancel-guard reset assertions, and added direct default/reset assertions for `[:orders :recently-canceled-order-keys]`.
- [x] (2026-04-21 01:21Z) Final parent-thread validation passed: `npm run check` exited `0`; `npm test` exited `0` with `3344 tests`, `18251 assertions`, `0 failures`, `0 errors`; `npm run test:websocket` exited `0` with `451 tests`, `2710 assertions`, `0 failures`, `0 errors`.
- [x] (2026-04-21 01:22Z) Final read-only review found no code-level correctness issues in the current diff. The only finding was stale validation wording in this ExecPlan, which this update corrects.

## Surprises & Discoveries

- Observation: The failure reproduces without a browser rendering bug. The stale row comes from application state being written back after cancel success.
  Evidence: Browser probe showed the happy cancel path removes the row and shows `Order canceled / Open orders updated`; replaying a stale live `[:orders :open-orders]` payload with the canceled oid reintroduced the row, and clearing that live source made the row disappear.

- Observation: Pending-cancel filtering is not enough because successful cancel clears `[:orders :pending-cancel-oids]` before late stale writers can arrive.
  Evidence: `hyperopen.order.effects/handle-cancel-success` removes pending ids and prunes current sources, while `hyperopen.websocket.user-runtime.handlers/open-orders-handler` and `hyperopen.api.projections.orders/apply-open-orders-success` later overwrite live/snapshot state without checking successful-cancel history.

- Observation: The post-cancel forced refresh can still join an older request because the single-flight key ignores `:force-refresh?`.
  Evidence: `hyperopen.api.info-client/request-info-with-flow!` skips cache when `force-refresh?` is true but still calls `with-single-flight!` with the same flight key as non-forced requests.

- Observation: RED regression tests now fail for the intended stale-writer gaps without syntax or setup errors.
  Evidence: `npx shadow-cljs --force-spawn compile test && node out/test.js` reported `7 failures, 0 errors`: three in `request-info-force-refresh-bypasses-single-flight-without-changing-normal-dedupe-test`, two in `apply-open-orders-success-filters-recently-canceled-oids-for-base-and-dex-snapshots-test`, one in `api-cancel-order-effect-records-runtime-guard-and-prunes-all-open-order-sources-test`, and one in `open-orders-handler-filters-recently-canceled-oids-and-keeps-active-rows-test`.

- Observation: The first implementation still let a post-cancel REST refresh apply to the store after an account switch.
  Evidence: Read-only review pointed out that `refresh-open-orders-snapshot!` used `promise-effects/apply-success-and-return`, which writes via `swap!` without checking the wallet address captured when the refresh began.

- Observation: An `oid`-only guard is unnecessarily broad when both the guard entry and open-order row expose asset or dex identity.
  Evidence: Read-only review showed that rows such as `{:asset-id 0 :oid 22}` and `{:asset-id 1 :oid 22}` would both be removed by an `#{22}` guard, even though only one cancel request asset may have succeeded.

- Observation: The final namespace-size gate required metadata updates because the approved regression tests intentionally expanded existing large lifecycle/effects test owners.
  Evidence: `npm run check` failed at `lint:namespace-sizes` until `dev/namespace_size_exceptions.edn` added the order-cancel effects test exception and raised the startup runtime test allowance to cover the new reset assertions.

## Decision Log

- Decision: Add a runtime-only canceled-oid guard under `[:orders :recently-canceled-oids]`.
  Rationale: The UI needs a local tombstone-like guard after the exchange accepts a cancel, but the guard should not be persisted across a new runtime/account reset. This keeps stale local writers from resurrecting rows without changing external API contracts.
  Date/Author: 2026-04-21 / Codex

- Decision: Filter at write boundaries, not only in the Open Orders view model.
  Rationale: The stale row is state corruption from late websocket/REST writers. Filtering where data enters `[:orders :open-orders]`, `[:orders :open-orders-snapshot]`, and `[:orders :open-orders-snapshot-by-dex]` keeps all consumers consistent, including portfolio/vault surfaces that read these stores.
  Date/Author: 2026-04-21 / Codex

- Decision: Make forced `/info` requests bypass single-flight.
  Rationale: Force refresh already means "do not use cached data"; joining a pre-existing request defeats the post-mutation freshness intent. Non-forced requests should retain the existing single-flight behavior.
  Date/Author: 2026-04-21 / Codex

- Decision: Keep `[:orders :recently-canceled-oids]` as the observable oid summary, and add richer internal cancel guard entries when cancel data contains asset or dex dimensions.
  Rationale: Existing regressions and view-model filters work naturally with oid sets, but stale writer filtering can be more precise when enough identity data is available. Falling back to oid-only matching remains necessary for wire shapes that expose only an oid.
  Date/Author: 2026-04-21 / Codex

## Outcomes & Retrospective

Implemented the production fix with a small shared runtime canceled-oid guard in `hyperopen.order.cancel-guard`. Successful cancel entries are now recorded in `[:orders :recently-canceled-oids]`, current open-order sources are pruned with the same guard, websocket and REST open-order writers filter guarded oids before writing state, account/runtime reset and app defaults clear/initialize the runtime-only guard, and forced `/info` requests bypass both cache and single-flight while non-forced requests keep existing dedupe behavior.

Follow-up implementation after review keeps `[:orders :recently-canceled-oids]` as the observable oid summary and records richer internal guard entries under `[:orders :recently-canceled-order-keys]`. Open-order pruning now compares `oid` plus any shared `asset-id`/`dex` identity, falling back to oid-only pruning when either side lacks richer identity. The post-cancel open-orders refresh now applies success and error projections only while the captured wallet address is still active, so old-address REST completions cannot write into the new account session.

Validation passed on 2026-04-21:

- `npx shadow-cljs --force-spawn compile test && node out/test.js` exited `0`; compile completed with `0 warnings`; runner reported `3342 tests`, `18242 assertions`, `0 failures`, `0 errors`.
- `npm run check` exited `0`; repo tooling/lints passed; `app`, `portfolio`, `portfolio-worker`, `vault-detail-worker`, and `test` Shadow builds completed with `0 warnings`.
- `npm test` exited `0`; compile completed with `0 warnings`; runner reported `3342 tests`, `18242 assertions`, `0 failures`, `0 errors`.
- `npm run test:websocket` exited `0`; compile completed with `0 warnings`; runner reported `451 tests`, `2708 assertions`, `0 failures`, `0 errors`.

No browser-inspection sessions were opened for this implementation. The bug is covered at the state-writer boundaries that caused the stale Open Orders row, so Browser MCP/Playwright QA was not required for this code change.

Follow-up validation on 2026-04-21:

- `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js` first reproduced the approved RED contract with `3 failures, 0 errors`, then exited `0` after the production fix; compile completed with `0 warnings`; runner reported `3344 tests`, `18249 assertions`, `0 failures`, `0 errors`.
- `npm test` exited `0`; compile completed with `0 warnings`; runner reported `3344 tests`, `18249 assertions`, `0 failures`, `0 errors`.
- `npm run test:websocket` exited `0`; compile completed with `0 warnings`; runner reported `451 tests`, `2710 assertions`, `0 failures`, `0 errors`.
- `npm run check` initially exited `1` at `lint:namespace-sizes`: `test/hyperopen/core_bootstrap/order_effects/cancel_test.cljs` needed an exception entry, and `test/hyperopen/startup/runtime_test.cljs` exceeded its existing exception. After updating `dev/namespace_size_exceptions.edn`, final validation passed.
- `npm run check` exited `0`; repo tooling/lints passed; `app`, `portfolio`, `portfolio-worker`, `vault-detail-worker`, and `test` Shadow builds completed with `0 warnings`.
- `npm test` exited `0`; compile completed with `0 warnings`; runner reported `3344 tests`, `18251 assertions`, `0 failures`, `0 errors`.
- `npm run test:websocket` exited `0`; compile completed with `0 warnings`; runner reported `451 tests`, `2709 assertions`, `0 failures`, `0 errors`.
- Final read-only review found no code-level correctness issues. The prior stale-refresh, rich-identity, force-refresh, reset/default, namespace-size, and namespace-boundary concerns are accounted for.

## Context and Orientation

The repository root for this worktree is `/Users/barry/.codex/worktrees/0370/hyperopen`. Run all commands from that directory. `bd` issue `hyperopen-b54a` tracks this bug.

The cancel row action starts in `src/hyperopen/views/account_info/tabs/open_orders.cljs`, which dispatches cancel actions with the row's order data. `src/hyperopen/order/actions.cljs` records `[:orders :pending-cancel-oids]` before the API call. `src/hyperopen/order/effects.cljs` handles cancel success by pruning current open-order state, clearing pending ids, showing a toast, and scheduling a refresh. That success path is the right place to record successfully canceled oids.

Open Orders state has three relevant sources:

- `[:orders :open-orders]` is live websocket data written by `src/hyperopen/websocket/user_runtime/handlers.cljs`.
- `[:orders :open-orders-snapshot]` is the base REST snapshot written by `src/hyperopen/api/projections/orders.cljs`.
- `[:orders :open-orders-snapshot-by-dex]` stores per-dex REST snapshots written by the same projection namespace.

`src/hyperopen/views/account_info/projections/orders.cljs` merges those sources for the Open Orders table and already filters `pending-cancel-oids`. The fix should not rely only on this view projection because other surfaces also read open-order state.

The post-mutation refresh path uses `src/hyperopen/account/surface_service.cljs` and `src/hyperopen/api/info_client.cljs`. `request-info-with-flow!` currently bypasses cache for forced requests but still uses single-flight with the same key as non-forced requests.

## Plan of Work

First, add tests before production source changes. The cancel-effects regression should prove that a successful cancel records the canceled oid in runtime state and prunes all current open-order sources. The websocket regression should seed `[:orders :recently-canceled-oids]`, send an `openOrders` message containing both canceled and active oids, and assert only active rows are written. The REST projection regression should do the same for base and per-dex `apply-open-orders-success`. The info-client regression should start a normal in-flight request and then issue an identical `:force-refresh? true` request, proving the forced call invokes a second fetch instead of sharing the old flight.

Second, introduce a small cancel-guard helper namespace under `src/hyperopen/order/` so oid normalization and payload pruning are shared by cancel effects, websocket handlers, and API projections. Use existing oid normalization behavior where available, including `hyperopen.api.trading/resolve-cancel-order-oid`.

Third, update the cancel-success path to add successful cancel oids to `[:orders :recently-canceled-oids]` before pruning. For partial success, record only the successful cancel entries. For total failure, keep the existing behavior of clearing pending ids and showing failure feedback without recording a guard.

Fourth, update websocket and REST open-order writers to filter payloads through the guard before storing them. Preserve current payload shapes where possible, including plain vectors and wrapped map shapes with `:orders`, `:openOrders`, or `:data`.

Fifth, clear the runtime-only guard in the account/runtime reset path and add a default value in app defaults. Then update `request-info-with-flow!` so `:force-refresh? true` bypasses cache and single-flight while non-forced requests retain current cache and single-flight semantics.

## Concrete Steps

Use this exact working directory:

    cd /Users/barry/.codex/worktrees/0370/hyperopen

Confirm issue and local state:

    bd show hyperopen-b54a --json
    git status --short

Add RED tests:

    test/hyperopen/core_bootstrap/order_effects/cancel_test.cljs
    test/hyperopen/websocket/user_runtime/handlers_test.cljs
    test/hyperopen/api/projections/orders_test.cljs
    test/hyperopen/api/info_client_test.cljs

Run the focused or full ClojureScript test command available in the repo and record the RED failures. If a new assertion passes immediately because current behavior already satisfies it, record that as a discovery and continue.

Implement source changes in:

    src/hyperopen/order/cancel_guard.cljs
    src/hyperopen/order/effects.cljs
    src/hyperopen/websocket/user_runtime/handlers.cljs
    src/hyperopen/api/projections/orders.cljs
    src/hyperopen/api/info_client.cljs
    src/hyperopen/startup/runtime.cljs
    src/hyperopen/state/app_defaults.cljs

Run the smallest relevant test command after each implementation slice, then run the required gates:

    npm run check
    npm test
    npm run test:websocket

Because this is UI-facing state behavior, account for browser QA. If deterministic unit/regression coverage fully exercises the stale writer path, record why Browser MCP/Playwright is not required for the code change. If a browser flow is exercised, clean up sessions with `npm run browser:cleanup`.

## Validation and Acceptance

Acceptance is observable through state-level tests and required repo gates.

Cancel-success acceptance: after the mocked exchange returns success for an open-order cancel, `[:orders :recently-canceled-oids]` contains the successful oid, current live/snapshot/per-dex open-order stores no longer contain that oid, pending cancel ids are cleared for that oid, and the existing success feedback remains intact.

Stale writer acceptance: if `[:orders :recently-canceled-oids]` contains an oid, websocket `openOrders` messages and REST open-orders projection successes containing that oid must not write it back into open-order state. Non-canceled rows in the same payload must still be stored.

Force-refresh acceptance: `:force-refresh? true` requests bypass cache and single-flight. Identical non-forced requests still share a single flight, preserving the previous dedupe behavior.

Reset acceptance: account/runtime reset clears the runtime-only canceled-oid guard so a new session is not polluted by an old local guard.

Final validation must include `npm run check`, `npm test`, and `npm run test:websocket` all exiting `0`. Record exact outputs in this plan before completion.

## Idempotence and Recovery

The new guard is local runtime state and is safe to recompute. Reapplying cancel success for the same oid should leave the guard as a set containing that oid and should keep open-order stores without duplicate or stale rows. Replaying stale websocket/REST payloads should be harmless because filtering is idempotent. If implementation needs to be backed out, remove the guard helper and its call sites; the old behavior returns but persisted user state is unaffected because the new guard is not written to browser storage.

## Artifacts and Notes

Investigation artifacts from Browser MCP remain in:

    tmp/browser-inspection/open-orders-cancel-investigation/browser-report.json
    tmp/browser-inspection/open-orders-cancel-investigation/final-stale-open-orders-state.png
    tmp/browser-inspection/open-orders-cancel-investigation/final-stale-open-orders-state-dom.json

No new browser artifact is required unless implementation verification opens a browser.

## Interfaces and Dependencies

New internal helper namespace: `hyperopen.order.cancel-guard`.

Expected public helper responsibilities:

- Normalize open-order and cancel request oids.
- Extract successful cancel oids from cancel request entries.
- Record successful oids in `[:orders :recently-canceled-oids]`.
- Remove guarded oids from open-order payloads while preserving existing container shapes.

No external API, websocket schema, or route contract should change. The new app-state key is internal, runtime-only state.
