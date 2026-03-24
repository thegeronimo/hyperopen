# Suppress Trader Portfolio User-Stream Notifications Without Breaking Spectate Mode

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-xejc`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

After this change, opening `/portfolio/trader/<address>` should still show the inspected trader’s portfolio data, but it should stop subscribing to the live user-fill stream that currently produces “Bought” and “Sold” order-feedback toasts for that foreign account. The user-visible result should be simple to prove: open a trader portfolio route, observe that it stays read-only and does not show live fill toasts, then enter Spectate Mode and confirm live user streams still work for the spectated account.

## Progress

- [x] (2026-03-24 17:30Z) Created and claimed `bd` issue `hyperopen-xejc` for trader-portfolio user-stream suppression.
- [x] (2026-03-24 17:34Z) Traced the current seam: user websocket subscriptions are driven by `effective-account-address`, so trader portfolio routes subscribe as if they were a real viewing context for live user streams.
- [x] (2026-03-24 17:44Z) Added a route-aware live-user-stream policy and watcher path that reacts to both address changes and policy changes.
- [x] (2026-03-24 17:44Z) Suppressed trader-portfolio live user stream subscriptions while leaving REST/bootstrap account data intact and Spectate Mode behavior unchanged.
- [x] (2026-03-24 17:58Z) Added focused regression coverage, ran the websocket suite, and passed the required repo gates.

## Surprises & Discoveries

- Observation: the order-confirmation toast is not a portfolio-view artifact; it is emitted from incremental `userFills` websocket messages.
  Evidence: `/hyperopen/src/hyperopen/websocket/user_runtime/handlers.cljs` calls `fill-runtime/show-user-fill-toast!` inside `user-fills-handler` only for non-snapshot `userFills` messages.

- Observation: changing only the address-driven watcher would miss an important correctness case.
  Evidence: the existing watcher in `/hyperopen/src/hyperopen/wallet/address_watcher.cljs` only fires when `effective-account-address` changes. Switching from `/portfolio/trader/<address>` to Spectate Mode on the same `<address>` would keep the same effective address, so no resubscribe would happen even though Spectate Mode should enable live streams.

- Observation: trader portfolio pages do not need live user streams to render the current portfolio surface.
  Evidence: `/hyperopen/src/hyperopen/account/surface_service.cljs` already bootstraps account surfaces with REST fetches like `fetch-user-fills!`, `fetch-frontend-open-orders!`, `fetch-portfolio!`, and `fetch-user-fees!`, so suppressing live user subscriptions does not remove the baseline data path.

- Observation: the existing address watcher can stay generic if handlers can provide their own watched value.
  Evidence: the final implementation leaves `webData2` on the plain effective-address path while the user-stream handler watches a derived address-or-nil value, which lets same-address policy flips unsubscribe and resubscribe correctly without a second watcher subsystem.

## Decision Log

- Decision: add a separate route-aware user-stream policy instead of overloading `effective-account-address`.
  Rationale: `effective-account-address` already powers bootstrap fetches, portfolio rendering, and route-scoped inspection. Changing it would break trader portfolio data loading, while user streams need a narrower policy about whether live updates should be attached to that address.
  Date/Author: 2026-03-24 / Codex

- Decision: treat Spectate Mode as live-user-streams-enabled, but treat `/portfolio/trader/<address>` as live-user-streams-disabled.
  Rationale: Spectate Mode is an intentional live account-inspection mode. Trader portfolio inspection is a read-only route that should not inherit live user notifications or unnecessary subscription churn.
  Date/Author: 2026-03-24 / Codex

- Decision: introduce a sync path that observes both address and policy, rather than only subscribing/unsubscribing inside the existing address watcher.
  Rationale: policy can change while the address stays the same, so the runtime must handle “same address, different live-stream policy” explicitly to preserve Spectate Mode correctness.
  Date/Author: 2026-03-24 / Codex

## Outcomes & Retrospective

The implementation achieved the intended runtime behavior. Trader portfolio routes now resolve to `nil` for the live user-stream target, so they stop subscribing to the user websocket topics that drive bought/sold fill toasts and other incremental activity updates. Spectate Mode still resolves to a real live-stream address and keeps its current live-follow behavior.

Validation passed for the affected slices and the repo gates. Focused tests covering the account-context policy, the generalized address watcher, the user-stream subscription orchestration, handler gating, and account-surface bootstrap behavior all passed. `npm run test:websocket` passed, `npm test` passed, and `npm run check` passed. No new browser automation was added in this follow-up because the change was confined to runtime subscription policy rather than view structure or browser-tooling seams; the remaining blind spot is end-to-end browser assertion that no toast appears on the trader route.

The final design slightly increased watcher flexibility by adding an optional handler-specific watched-value protocol, but it reduced product/runtime mismatch overall. `webData2` remains the public account-data path, while live user streams are now explicitly opt-in by viewing mode instead of being an accidental side effect of route-scoped inspection.

## Context and Orientation

User account identity lives in `/hyperopen/src/hyperopen/account/context.cljs`. Right now `effective-account-address` returns the trader route address first, then the Spectate Mode address, then the wallet owner address. That is correct for read-only portfolio rendering, but it also means the address watcher sees trader portfolio routes as if they should receive all user websocket topics.

Address-driven subscription wiring lives in `/hyperopen/src/hyperopen/wallet/address_watcher.cljs`. Startup still registers a WebData2 handler and a user websocket handler there, but the existing watcher needed to become slightly more general so a handler can react to a state-derived watched value instead of only the plain effective address.

Live fill toasts originate in `/hyperopen/src/hyperopen/websocket/user_runtime/handlers.cljs` and `/hyperopen/src/hyperopen/websocket/user_runtime/fills.cljs`. Incremental `userFills` messages update `[:orders :fills]`, show order-feedback toasts, and schedule account refresh work. Snapshot and REST fetch paths do not show those toasts.

Account surface bootstrap lives in `/hyperopen/src/hyperopen/account/surface_service.cljs` and `/hyperopen/src/hyperopen/startup/collaborators.cljs`. Those code paths can still fetch the inspected account’s fills, open orders, clearinghouse state, and portfolio summary by REST even if the live user websocket policy is disabled.

## Plan of Work

First, extend `/hyperopen/src/hyperopen/account/context.cljs` with a small, explicit helper that answers whether live user websocket streams are allowed for the current state. The helper should return false for the trader portfolio route and true for the wallet owner and Spectate Mode cases. Keep the existing `effective-account-address` logic intact so portfolio rendering still follows the inspected trader.

Second, update `/hyperopen/src/hyperopen/websocket/user_runtime/subscriptions.cljs` so the user websocket subscription logic can compute a desired live-stream address from full app state, not just from the plain effective address. The user-stream handler should watch an address-or-nil value derived from route policy so same-address transitions can still unsubscribe and resubscribe correctly.

Third, tighten `/hyperopen/src/hyperopen/websocket/user_runtime/handlers.cljs` and `/hyperopen/src/hyperopen/account/surface_service.cljs` so trader portfolio routes neither consume addressless live user messages nor initiate unnecessary live clearinghouse subscriptions. Bootstrap REST fetches should remain intact so the inspected portfolio still renders.

Fourth, add focused tests for the new policy and for the tricky transitions: wallet owner -> trader portfolio, trader portfolio -> owner, trader portfolio -> Spectate Mode on the same address, and Spectate Mode -> trader portfolio on the same address. Add handler tests that prove trader portfolio routes do not subscribe user streams, while Spectate Mode still does.

## Concrete Steps

Work from `/hyperopen`.

1. Add the live-user-stream policy helper in:

    `/hyperopen/src/hyperopen/account/context.cljs`
    `/hyperopen/test/hyperopen/account/context_test.cljs`

2. Generalize watched-value orchestration and update user-stream subscriptions in:

    `/hyperopen/src/hyperopen/wallet/address_watcher.cljs`
    `/hyperopen/src/hyperopen/websocket/user_runtime/subscriptions.cljs`
    `/hyperopen/test/hyperopen/wallet/address_watcher_test.cljs`
    `/hyperopen/test/hyperopen/websocket/user_runtime/subscriptions_test.cljs`

3. Gate live user handlers and clearinghouse subscriptions in:

    `/hyperopen/src/hyperopen/websocket/user_runtime/common.cljs`
    `/hyperopen/src/hyperopen/websocket/user_runtime/handlers.cljs`
    `/hyperopen/src/hyperopen/account/surface_service.cljs`
    `/hyperopen/test/hyperopen/account/surface_service_test.cljs`

4. Add runtime/handler regression coverage in:

    `/hyperopen/test/hyperopen/websocket/user_test.cljs`

5. Run validation commands:

    cd /hyperopen
    node out/test.js --test=hyperopen.account.context-test,hyperopen.account.surface-service-test,hyperopen.websocket.user-runtime.subscriptions-test,hyperopen.websocket.user-test
    npm run test:websocket
    npm test
    npm run check

If the focused namespace runner requires a fresh compile first, run:

    cd /hyperopen
    npm run test:runner:generate
    npm exec shadow-cljs -- compile test

## Validation and Acceptance

Acceptance is behavior, not just code edits.

When the app is on `/portfolio/trader/<address>`, the inspected trader portfolio should continue to populate from the existing bootstrap data path, but live `userFills` notifications should no longer surface “Bought” or “Sold” toasts for that foreign account. When the user enters Spectate Mode, live user websocket behavior should still work for the spectated address, including the existing fill-driven refresh semantics.

The minimum automated acceptance is:

    cd /hyperopen
    npm run test:websocket
    npm test
    npm run check

The focused regression acceptance is:

- trader portfolio route returns `false` from the new live-user-stream policy helper
- the user-stream watched-value helper subscribes nothing for trader portfolio routes
- the same-address transition from trader portfolio to Spectate Mode resubscribes the address because policy flipped from disabled to enabled
- the reverse same-address transition unsubscribes because policy flipped from enabled to disabled
- incremental `userFills` handling still works when live streams are enabled and is ignored when the live-stream policy is disabled

## Idempotence and Recovery

This work is additive and can be retried safely. If the new policy-aware sync path misbehaves, the safest rollback is to revert the new policy/watch helper and fall back to the previous address-only subscription behavior. Do not change `effective-account-address` as part of rollback; that would re-open the trader-portfolio data-loading bug that this repository has already resolved.

## Artifacts and Notes

The screenshot symptom in this issue is explained by the current runtime path:

    trader portfolio route -> effective-account-address becomes trader address
    address watcher sees a new effective address
    create-user-handler subscribes live user topics for that address
    user-fills-handler receives incremental fills
    fill-runtime/show-user-fill-toast! emits the Bought/Sold order feedback toast

The intended fixed path is:

    trader portfolio route -> effective-account-address still becomes trader address
    live-user-streams-enabled? returns false
    user-stream sync computes no desired live-stream address
    REST/bootstrap still hydrates fills/open orders/portfolio surfaces
    no live fill toast is emitted for the trader route

## Interfaces and Dependencies

The new public helper in `/hyperopen/src/hyperopen/account/context.cljs` should be a stable predicate such as `live-user-streams-enabled?`. It must be pure and must depend only on app state.

`/hyperopen/src/hyperopen/websocket/user_runtime/subscriptions.cljs` should end with a policy-aware sync seam that accepts full app state and low-level subscribe/unsubscribe functions. The low-level topic senders (`subscribe-user!`, `unsubscribe-user!`, and `sync-perp-dex-clearinghouse-subscriptions!`) should remain available and continue to be address-only primitives.

`/hyperopen/src/hyperopen/startup/runtime.cljs` should remain the place where address-driven startup handlers are registered. Any extra watch or sync logic added here must be limited to the user-stream family so WebData2 and the existing portfolio bootstrap path keep their current behavior.

Revision note: updated on 2026-03-24 for `hyperopen-xejc` after implementation and validation completed so the final watcher design, validation evidence, and residual browser blind spot are recorded accurately.
