# Gate Order-Mutation Per-Dex Clearinghouse Refresh by Websocket Readiness

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, successful order submit and cancel flows will stop issuing redundant named-DEX `clearinghouseState` REST requests when Hyperopen already has a healthy websocket subscription for that `{user, dex}` pair and a ready local snapshot for that dex. The user-visible result is lower `/info` request pressure without changing the safety behavior for cold-start, reconnect, or missing-snapshot cases. A developer can verify the result by running the order-effect and account-surface tests: the post-order path should skip per-dex clearinghouse REST when websocket readiness is present, and still perform the REST backstop when readiness is missing.

## Progress

- [x] (2026-03-11 17:37Z) Created and claimed `bd` task `hyperopen-g1s` for this work, linked to this ExecPlan path.
- [x] (2026-03-11 17:40Z) Audited the current order-mutation refresh path in `/hyperopen/src/hyperopen/order/effects.cljs`, the shared gating logic in `/hyperopen/src/hyperopen/account/surface_service.cljs`, and the websocket subscription/handler ownership in `/hyperopen/src/hyperopen/websocket/user_runtime/**`.
- [x] (2026-03-11 17:42Z) Authored this active ExecPlan before changing production code.
- [x] (2026-03-11 17:43Z) Updated `/hyperopen/src/hyperopen/account/surface_service.cljs` so order-mutation per-dex clearinghouse refresh now requires both websocket health and a seeded local snapshot before suppressing REST.
- [x] (2026-03-11 17:44Z) Updated `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs` and `/hyperopen/test/hyperopen/account/surface_service_test.cljs` with readiness-present and readiness-missing regressions.
- [x] (2026-03-11 17:46Z) Restored declared npm dependencies with `npm ci` after validation was blocked by a missing local `node_modules` install (`shadow-cljs` and `@noble/secp256k1`).
- [x] (2026-03-11 17:46Z) Passed `npm run check`.
- [x] (2026-03-11 17:46Z) Passed `npm test` (`2268` tests, `11821` assertions, `0` failures, `0` errors).
- [x] (2026-03-11 17:46Z) Passed `npm run test:websocket` (`373` tests, `2131` assertions, `0` failures, `0` errors).

## Surprises & Discoveries

- Observation: The current order-mutation path intentionally forces per-dex clearinghouse REST refreshes even when websocket health for the active user is live.
  Evidence: `/hyperopen/src/hyperopen/account/surface_service.cljs` `refresh-after-order-mutation!` currently sets `:gate-perp-dex-by-stream? false` and `:skip-perp-dex-when-subscribed-and-ready? false`.

- Observation: The user-fill path already contains the exact safety rule we want to reuse: suppress per-dex REST when the websocket subscription is present and the local per-dex snapshot is already populated.
  Evidence: `/hyperopen/src/hyperopen/account/surface_service.cljs` `refresh-after-user-fill!` plus `/hyperopen/test/hyperopen/websocket/user_test.cljs` `user-ledger-refresh-skips-per-dex-clearinghouse-when-stream-subscribed-and-snapshot-ready-test`.

- Observation: Websocket and REST both write the same named-DEX snapshot path with whole-value replacement, so overlap is currently last-writer-wins rather than a merge.
  Evidence: `/hyperopen/src/hyperopen/websocket/user_runtime/handlers.cljs` `clearinghouse-state-handler`, `/hyperopen/src/hyperopen/websocket/user_runtime/refresh.cljs` `refresh-perp-dex-clearinghouse-snapshot!`, and `/hyperopen/src/hyperopen/api/projections.cljs` `apply-perp-dex-clearinghouse-success`.

- Observation: The order wrapper currently does not pass `:sync-perp-dex-clearinghouse-subscriptions!` into the shared service, while the user-fill path does.
  Evidence: compare `/hyperopen/src/hyperopen/order/effects.cljs` `refresh-account-surfaces-after-order-mutation!` with `/hyperopen/src/hyperopen/websocket/user_runtime/refresh.cljs` `refresh-account-surfaces-after-user-fill!`.

- Observation: Reusing the user-fill flags exactly would still skip REST when the per-dex stream is usable but the local snapshot is absent, which is weaker than the safety rule requested for order mutations.
  Evidence: `run-post-event-refresh!` originally computed `skip-perp-dex-rest-refresh?` as `perp-dex-stream-usable?` OR `(subscribed AND snapshot-ready)`. A new order-only flag was required so order mutations can demand snapshot readiness even under usable stream health.

- Observation: No order-module wiring change was necessary once the shared service accepted the stricter order-only gating flag.
  Evidence: the final diff only changes `/hyperopen/src/hyperopen/account/surface_service.cljs`, `/hyperopen/test/hyperopen/account/surface_service_test.cljs`, and `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`.

- Observation: The initial validation failures were environmental rather than code regressions.
  Evidence: before `npm ci`, `npm test` and `npm run test:websocket` failed with `sh: shadow-cljs: command not found`, and both manual runners failed on missing module `@noble/secp256k1`.

## Decision Log

- Decision: Reuse the shared account-surface service rule instead of adding a new order-only websocket readiness helper.
  Rationale: The repository already made `/hyperopen/src/hyperopen/account/surface_service.cljs` the single owner of post-event account-surface fallback policy. Reusing that seam keeps the stream-versus-REST rule in one place.
  Date/Author: 2026-03-11 / Codex

- Decision: Keep the change narrowly scoped to order-mutation per-dex clearinghouse gating and do not alter startup or spot-clearinghouse behavior.
  Rationale: The user request is specifically about named-DEX `clearinghouseState` fanout after exchange post actions. Startup and spot have different hydration responsibilities and should remain unchanged in this task.
  Date/Author: 2026-03-11 / Codex

- Decision: Make order mutation stricter than the existing user-fill implementation by requiring snapshot readiness even when the per-dex stream is otherwise usable.
  Rationale: The user explicitly asked for the safer rule: trust websocket only when the subscription is healthy and a snapshot is already present. A usable stream alone does not prove that the post-order snapshot has already reached local state.
  Date/Author: 2026-03-11 / Codex

- Decision: Do not add subscription-sync wiring to `/hyperopen/src/hyperopen/order/effects.cljs` in this task.
  Rationale: The stricter shared-service gating already preserves the REST backstop whenever the subscription is absent or the snapshot is missing. Adding websocket sync wiring would widen the change surface without being necessary for the requested behavior.
  Date/Author: 2026-03-11 / Codex

## Outcomes & Retrospective

Completed outcome:

- Successful order submit and cancel flows now skip named-DEX `clearinghouseState` REST only when two conditions are both true: the websocket stream for `{user, dex}` is healthy, and `[:perp-dex-clearinghouse dex]` is already populated.
- When the stream is healthy but the snapshot is still missing, the order path keeps the REST backstop.
- Dex-scoped open-order refresh behavior remains unchanged.
- Required validation gates pass after restoring the declared npm dependencies.

This reduced overall complexity slightly. The change did add one order-only gating flag inside the shared service, but it removed a product-level inconsistency between the intended safety rule and the actual order behavior without introducing a new order-specific refresh path or new public API.

## Context and Orientation

The relevant state in this repository is split between a “default account surface” and named-DEX snapshots. The default account surface is stored under `[:webdata2 :clearinghouseState]` and is primarily refreshed from the `webData2` websocket topic. Named-DEX account snapshots are stored under `[:perp-dex-clearinghouse <dex>]` and may be populated by websocket `clearinghouseState` messages or by REST `/info` calls with body `{"type":"clearinghouseState","user", "dex"}`.

The canonical owner for deciding when to trust websocket coverage and when to fall back to REST is `/hyperopen/src/hyperopen/account/surface_service.cljs`. That file accepts injected collaborators for network refreshes and reads websocket health from store state via `/hyperopen/src/hyperopen/account/surface_policy.cljs`.

The order submit and cancel flows live in `/hyperopen/src/hyperopen/order/effects.cljs`. After a successful submit or successful cancel count greater than zero, that file calls `refresh-account-surfaces-after-order-mutation!`, which delegates into the shared account-surface service.

The user-fill path already uses a more selective rule. In `/hyperopen/src/hyperopen/account/surface_service.cljs`, `refresh-after-user-fill!` allows websocket coverage to suppress per-dex clearinghouse REST when the stream is usable or at least subscribed with a ready local snapshot. The final implementation for order mutation is intentionally stricter: it requires the ready local snapshot even when the stream is usable.

The main product surfaces that read named-DEX clearinghouse snapshots are:

- `/hyperopen/src/hyperopen/state/trading.cljs`, which feeds the active market trading context.
- `/hyperopen/src/hyperopen/account/history/position_margin.cljs`, which derives position-margin availability.
- `/hyperopen/src/hyperopen/views/account_equity_view.cljs` and `/hyperopen/src/hyperopen/views/account_info/vm.cljs`, which derive account and portfolio-style rows from `:perp-dex-clearinghouse`.

This task must preserve the recovery behavior for missing or stale snapshots. The goal is not “websocket only no matter what.” The goal is “no overlapping REST request when websocket readiness is already sufficient.”

## Plan of Work

First, update `/hyperopen/src/hyperopen/account/surface_service.cljs` so `refresh-after-order-mutation!` keeps the order-specific open-order behavior but uses a stricter per-dex clearinghouse rule: do not suppress REST unless websocket health is good and the local per-dex snapshot is already seeded. Implement that stricter rule inside `run-post-event-refresh!` as an order-only option so the user-fill path can keep its existing behavior.

Second, keep `/hyperopen/src/hyperopen/order/effects.cljs` unchanged unless the stricter service gating proves insufficient. Because the order path already falls back to REST whenever the subscription is absent or the snapshot is missing, additional subscription-sync wiring is unnecessary for this task.

Third, update tests. In `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`, replace the existing expectation that a live-stream order mutation always performs a per-dex clearinghouse REST call. Add one regression that proves the call is skipped when a matching per-dex clearinghouse subscription and snapshot already exist, while dex-scoped open-order refresh still happens. Add a second regression in `/hyperopen/test/hyperopen/account/surface_service_test.cljs` proving that the REST backstop still runs when the per-dex snapshot is missing even if websocket transport is otherwise healthy.

Finally, run the required repository validation gates and update this plan with the observed results. The final validation commands were `npm run check`, `npm test`, and `npm run test:websocket`, all of which passed after `npm ci` restored the declared dependencies. The final administrative steps are to close `hyperopen-g1s` and move this plan to `/hyperopen/docs/exec-plans/completed/`.

## Concrete Steps

Run all commands from `/Users/barry/.codex/worktrees/588a/hyperopen`.

1. Update the active ExecPlan and keep it in sync while implementing:

       sed -n '1,240p' docs/exec-plans/active/2026-03-11-order-mutation-per-dex-clearinghouse-ws-gating.md

2. Edit the shared service and tests:

       src/hyperopen/account/surface_service.cljs

3. Edit regression tests:

       test/hyperopen/core_bootstrap/order_effects_test.cljs
       test/hyperopen/account/surface_service_test.cljs

4. Restore the declared npm dependencies if the local workspace is missing them:

       npm ci

5. Run required gates:

       npm run check
       npm test
       npm run test:websocket

6. Update this ExecPlan with actual results, move it to completed if green, and close the `bd` task:

       bd close hyperopen-g1s --reason "Completed" --json

Expected evidence after implementation:

- The focused order-mutation regression proves no per-dex `clearinghouseState` REST call when websocket readiness and snapshot presence are both true.
- A complementary regression proves the REST backstop still runs when readiness is incomplete.
- Full repository gates pass.

Observed results:

    npm run check
    -> pass

    npm test
    -> Ran 2268 tests containing 11821 assertions.
    -> 0 failures, 0 errors.

    npm run test:websocket
    -> Ran 373 tests containing 2131 assertions.
    -> 0 failures, 0 errors.

## Validation and Acceptance

Acceptance is behavior, not just code shape.

The new behavior is correct when all of the following are true:

1. In a test state with successful order submission, live transport, a matching per-dex `clearinghouseState` subscription, and an existing `[:perp-dex-clearinghouse dex]` snapshot, the order flow still refreshes dex-scoped open orders but does not call per-dex `request-clearinghouse-state!`.
2. In a test state with successful order submission but no ready per-dex snapshot, the order flow still performs the per-dex `request-clearinghouse-state!` backstop.
3. The existing default-surface behavior remains unchanged: generic `webData2` coverage still suppresses the default clearinghouse REST refresh.
4. `npm run check`, `npm test`, and `npm run test:websocket` all succeed.

If manual verification is desired after tests pass, open the local app, submit or cancel an order on a named DEX with request stats reset, and confirm that steady-state healthy sessions stop emitting the extra per-dex `clearinghouseState` POST after the order mutation while cold or degraded sessions still can.

## Idempotence and Recovery

The planned edits are safe to re-run. The shared service already centralizes the gating logic, so retrying this work means reapplying a small configuration change plus tests. If the websocket-readiness rule turns out to be too aggressive, the safe rollback is to restore the previous order-mutation gating values in `/hyperopen/src/hyperopen/account/surface_service.cljs` and revert the changed test expectations. No data migration or persistent state rewrite is involved.

## Artifacts and Notes

Relevant existing evidence to preserve while implementing:

    - `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs` currently documents that default clearinghouse REST is skipped under live `webData2`, but per-dex clearinghouse REST remains.
    - `/hyperopen/test/hyperopen/websocket/user_test.cljs` already proves that the user-fill path suppresses per-dex REST when the stream is subscribed and a snapshot is present.

Final changed files:

    - `/hyperopen/src/hyperopen/account/surface_service.cljs`
    - `/hyperopen/test/hyperopen/account/surface_service_test.cljs`
    - `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`

## Interfaces and Dependencies

The implementation should continue to use the existing shared seams:

- `/hyperopen/src/hyperopen/account/surface_service.cljs`
  - `refresh-after-order-mutation!`
  - `run-post-event-refresh!`
- `/hyperopen/src/hyperopen/account/surface_policy.cljs`
  - `topic-usable-for-address-and-dex?`
  - `topic-subscribed-for-address-and-dex?`
- `/hyperopen/src/hyperopen/order/effects.cljs`
  - `refresh-account-surfaces-after-order-mutation!`
- `/hyperopen/src/hyperopen/websocket/user.cljs` or `/hyperopen/src/hyperopen/websocket/user_runtime/subscriptions.cljs`
  - only if subscription sync wiring is required for parity with the user-fill path

No new public API should be introduced. The stable observable contract is the changed refresh behavior plus the new regression tests.

Revision note (2026-03-11 17:46Z): Updated after implementation and validation to record the stricter order-only snapshot requirement, the decision not to touch `/hyperopen/src/hyperopen/order/effects.cljs`, the temporary `npm ci` environment repair, and the final passing gate results.
