# Consolidate Account-Surface Bootstrap and Refresh Ownership

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, Hyperopen will have one canonical owner for account-surface bootstrap and fallback refresh decisions instead of separate copies in startup bootstrap, websocket user handlers, and order mutation effects. This matters because account views should either trust websocket streams or perform one deliberate REST backstop, and that invariant needs one place to change when stream policy evolves.

A developer can verify the result by reading one service namespace for the stream-coverage and fallback rules, by seeing thin callers in `/hyperopen/src/hyperopen/startup/runtime.cljs`, `/hyperopen/src/hyperopen/websocket/user.cljs`, and `/hyperopen/src/hyperopen/order/effects.cljs`, and by running the required tests plus a browser-inspection QA pass that exercises account startup and post-fill refresh behavior without redundant `/info` fanout.

## Progress

- [x] (2026-03-07 02:06Z) Audited repository planning, architecture, reliability, and work-tracking rules before implementation.
- [x] (2026-03-07 02:06Z) Audited duplicated account-surface logic in `/hyperopen/src/hyperopen/startup/runtime.cljs` and `/hyperopen/src/hyperopen/websocket/user.cljs`.
- [x] (2026-03-07 02:06Z) Discovered a third duplicate post-mutation refresh owner in `/hyperopen/src/hyperopen/order/effects.cljs`; this plan now absorbs that path so the invariant has one real owner.
- [x] (2026-03-07 02:06Z) Authored this active ExecPlan before changing production code.
- [x] (2026-03-07 02:09Z) Created `bd` epic `hyperopen-qnv` and claimed child task `hyperopen-qnv.1`, both linked to this ExecPlan.
- [x] (2026-03-07 02:15Z) Added `/hyperopen/src/hyperopen/account/surface_policy.cljs` and `/hyperopen/src/hyperopen/account/surface_service.cljs`, then migrated startup/runtime, websocket/user, and order/effects to delegate to that boundary.
- [x] (2026-03-07 02:16Z) Ran `npx shadow-cljs compile ws-test && node out/ws-test.js` after the extraction; result: `333 tests`, `1840 assertions`, `0 failures`, `0 errors`.
- [x] (2026-03-07 02:19Z) Added direct seam tests in `/hyperopen/test/hyperopen/account/surface_policy_test.cljs` and `/hyperopen/test/hyperopen/account/surface_service_test.cljs`.
- [x] (2026-03-07 02:21Z) Restored missing local JS dependencies with `npm install --no-fund --no-audit` after initial test execution failed on missing `shadow-cljs`/`@noble/secp256k1`.
- [x] (2026-03-07 02:22Z) Added ADR `/hyperopen/docs/architecture-decision-records/0025-account-surface-bootstrap-refresh-ownership.md` and updated canonical invariant docs in `/hyperopen/ARCHITECTURE.md` and `/hyperopen/docs/RELIABILITY.md`.
- [x] (2026-03-07 02:25Z) Passed `npm run check`.
- [x] (2026-03-07 02:26Z) Passed `npm test` (`1987 tests`, `10180 assertions`, `0 failures`, `0 errors`).
- [x] (2026-03-07 02:27Z) Passed `npm run test:websocket` (`333 tests`, `1840 assertions`, `0 failures`, `0 errors`).
- [x] (2026-03-07 02:30Z) Completed browser-inspection QA session `sess-1772850198211-ddab19`, captured desktop snapshot run `inspect-2026-03-07T02-30-43-105Z-be10984e`, and wrote evidence to `/hyperopen/docs/qa/account-surface-bootstrap-refresh-validation-2026-03-07.md`.
- [x] (2026-03-07 02:31Z) Browser QA found no verified product defects, so no discovered follow-up `bd` issues were created.
- [x] (2026-03-07 02:32Z) Moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/` and prepared tracker closure for `hyperopen-qnv.1` and `hyperopen-qnv`.

## Surprises & Discoveries

- Observation: Startup bootstrap and websocket user refresh currently duplicate the same stream-coverage selectors using separate local helpers.
  Evidence: `/hyperopen/src/hyperopen/startup/runtime.cljs` defines `topic-usable-for-address?` and `topic-usable-for-address-and-dex?`; `/hyperopen/src/hyperopen/websocket/user.cljs` defines the same helper names plus `topic-subscribed-for-address-and-dex?`.

- Observation: Order mutation refresh contains a third copy of the same fallback orchestration shape and therefore prevents startup or websocket code from being the true invariant owner.
  Evidence: `/hyperopen/src/hyperopen/order/effects.cljs` duplicates `refresh-open-orders-snapshot!`, `refresh-default-clearinghouse-snapshot!`, `refresh-perp-dex-clearinghouse-snapshot!`, `topic-usable-for-address?`, and `refresh-account-surfaces-after-order-mutation!`.

- Observation: Startup and websocket user flows differ mainly in trigger source and a few options, not in their core stream-coverage rule.
  Evidence: both decide whether to fetch `openOrders` and default `clearinghouseState` based on websocket health, both backfill per-dex clearinghouse state, and both guard stale address callbacks before applying results.

- Observation: The spot clearinghouse refresh rule is intentionally UI-surface-sensitive and exists only on the websocket fill/ledger path today.
  Evidence: `/hyperopen/src/hyperopen/websocket/user.cljs` uses `spot-clearinghouse-refresh-surface-active?` before calling `request-spot-clearinghouse-state!`; startup bootstrap always fetches spot state immediately because it seeds account balances.

- Observation: The repository already has direct regression coverage around these flows, so the refactor can preserve behavior while moving ownership.
  Evidence: `/hyperopen/test/hyperopen/startup/runtime_test.cljs`, `/hyperopen/test/hyperopen/websocket/user_test.cljs`, and `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs` all assert current gating and fallback behavior.

- Observation: Browser-side synthetic websocket health fixtures must match production shape exactly, especially `:subscribed?` and keyword status values like `:n-a`.
  Evidence: an initial browser smoke used `subscribed` and string status values, which made the policy report unusable streams; after correcting the fixture to `subscribed?` plus keyword statuses, the same smoke showed `openOrders`/`webData2` usable and suppressed the REST fallbacks.

- Observation: The local workspace did not have installed npm dependencies even though `package.json` declared them, so wrapper scripts failed until dependencies were restored.
  Evidence: `npm run test:websocket` first returned `sh: shadow-cljs: command not found`, and `node out/test.js` then failed with `Cannot find module '@noble/secp256k1'` before `npm install --no-fund --no-audit`.

## Decision Log

- Decision: The new canonical owner will be an account-focused service and policy seam under `/hyperopen/src/hyperopen/account/` rather than new helpers inside startup or websocket namespaces.
  Rationale: The invariant belongs to account-surface orchestration, not to startup lifecycle or websocket transport handling. This aligns with Single Responsibility Principle and avoids making startup or websocket modules the permanent owner of cross-cutting refresh policy.
  Date/Author: 2026-03-07 / Codex

- Decision: The implementation will absorb `/hyperopen/src/hyperopen/order/effects.cljs` into the same service during this change instead of leaving it for later.
  Rationale: Leaving order mutation refresh outside the seam would preserve a second source of truth for post-event account-surface fallback behavior and would not satisfy the architectural goal of one invariant owner.
  Date/Author: 2026-03-07 / Codex

- Decision: The service will separate pure policy from effectful orchestration.
  Rationale: Pure websocket-health and refresh-decision functions are easier to test and better aligned with the repository’s Domain-Driven Design rule that business decisions remain deterministic, while the service layer can own API calls, logging, timers, and store writes through injected collaborators.
  Date/Author: 2026-03-07 / Codex

- Decision: Public entrypoints in startup/runtime, websocket/user, and order/effects will stay in place and become thin wrappers or delegates.
  Rationale: This preserves existing call sites and test surfaces while relocating the policy and orchestration internals to the new owner.
  Date/Author: 2026-03-07 / Codex

## Outcomes & Retrospective

Completed outcome:

- One canonical policy/service boundary now owns account-surface stream-coverage and fallback refresh behavior:
  - `/hyperopen/src/hyperopen/account/surface_policy.cljs`
  - `/hyperopen/src/hyperopen/account/surface_service.cljs`
- Startup bootstrap, websocket post-fill refresh, and order-mutation refresh now call that owner through thin wrappers.
- Direct seam tests and legacy caller regression suites pass together.
- Required gates pass in full.
- Browser QA confirmed the local trade page renders normally, startup bootstrap still hydrates account surfaces for a public address, and the shared service suppresses redundant post-fill fallbacks when websocket coverage is present.

## Context and Orientation

In this repository, an “account surface” is one of the state slices that make the account experience usable after an address change or after account-changing events. For this refactor, the relevant surfaces are:

- default open orders snapshot in `[:orders :open-orders]` and `[:orders :open-orders-snapshot-by-dex]`,
- default account clearinghouse data projected under `[:webdata2 :clearinghouseState]`,
- spot clearinghouse balances in `[:spot :clearinghouse-state]`,
- per-dex clearinghouse snapshots in `[:perp-dex-clearinghouse <dex>]`.

A “stream-covered surface” means websocket health says the app is already subscribed to a topic for the active address and the stream status is usable (`:live` or `:n-a`) under a live transport. The current implementation decides this in multiple places using `/hyperopen/src/hyperopen/websocket/health_projection.cljs`.

A “fallback refresh” means a REST `/info` request is still made because websocket coverage is missing, degraded, or intentionally disabled by migration flags. Startup bootstrap uses delayed fallback for some stream-covered surfaces. Websocket fill refresh and order mutation refresh use immediate fallback when stream coverage is missing.

Today the logic is spread across these files:

- `/hyperopen/src/hyperopen/startup/runtime.cljs`
  - `schedule-stream-backed-startup-fallback!`
  - `stage-b-account-bootstrap!`
  - `bootstrap-account-data!`
- `/hyperopen/src/hyperopen/websocket/user.cljs`
  - `refresh-account-surfaces-after-user-fill!`
  - `schedule-account-surface-refresh-after-fill!`
  - local stream helper functions
- `/hyperopen/src/hyperopen/order/effects.cljs`
  - `refresh-account-surfaces-after-order-mutation!`
  - local snapshot refresh helper functions

Existing dependencies that must remain consistent:

- `/hyperopen/src/hyperopen/account/context.cljs` owns effective account identity.
- `/hyperopen/src/hyperopen/websocket/migration_flags.cljs` owns whether WS-first behavior is enabled for startup and order/fill flows.
- `/hyperopen/src/hyperopen/websocket/health_projection.cljs` owns stream-health lookup from projected state.
- `/hyperopen/src/hyperopen/api/projections.cljs` owns state projection functions for successful REST payloads.
- `/hyperopen/src/hyperopen/api/market_metadata/facade.cljs` owns the ensured perp-dex metadata fetch/apply flow used before per-dex refresh decisions.

The repository architecture requires one invariant owner, no delegation-only wrappers, and boundary tests for new seams. This plan therefore introduces a reusable account-surface policy and service boundary and documents it with an ADR.

## Plan of Work

### Milestone 1: Establish the canonical boundary

Add two namespaces under `/hyperopen/src/hyperopen/account/`:

- `/hyperopen/src/hyperopen/account/surface_policy.cljs`
- `/hyperopen/src/hyperopen/account/surface_service.cljs`

The policy namespace is pure. It will accept plain state maps and explicit inputs and return deterministic answers about:

- whether a stream is usable for an address or address+dex,
- whether a stream is subscribed for an address+dex,
- whether spot-clearinghouse refresh is needed for the current UI surface,
- which default and per-dex fallback refreshes are needed for a given trigger type.

The service namespace is effectful. It will accept injected collaborators for API requests, metadata ensuring, projections, logging, timers, and active-address guards. It will execute startup bootstrap backfills and post-event refreshes according to the policy results.

Acceptance for this milestone is a compilable seam with service-policy unit tests proving the decision logic independent of startup or websocket callers.

### Milestone 2: Migrate startup bootstrap to the service

Refactor `/hyperopen/src/hyperopen/startup/runtime.cljs` so its account bootstrap entrypoints delegate to the service. The startup namespace should retain lifecycle ownership, state reset, and address-handler installation, but it should no longer embed duplicate stream-coverage helpers or detailed per-dex fallback decisions.

Concrete behaviors that must remain unchanged:

- stage-A stream-covered fetches still use delayed fallback when startup WS-first is enabled,
- non-stream-covered bootstrap fetches still run immediately,
- per-dex open-orders fallback still skips when `openOrders` is websocket-covered,
- per-dex clearinghouse backfill still runs when not websocket-covered,
- stale address callbacks remain guarded by effective account address.

Acceptance for this milestone is that startup/runtime tests continue to pass with the new service as the owner of fallback policy.

### Milestone 3: Migrate websocket user and order mutation refreshes to the same service

Refactor `/hyperopen/src/hyperopen/websocket/user.cljs` and `/hyperopen/src/hyperopen/order/effects.cljs` so they delegate to the new service for post-event refresh behavior. The websocket namespace should keep websocket subscription management, handler registration, toast behavior, and debounce timer ownership. The order-effects namespace should keep optimistic state mutation and trading side effects. Neither should keep their own copy of account-surface stream-policy or refresh orchestration.

Required preserved behaviors:

- websocket fill/ledger refresh still debounces and respects effective account address,
- websocket fill/ledger refresh still refreshes spot clearinghouse only when the relevant UI surface is active,
- websocket fill/ledger refresh still suppresses per-dex REST backfill when a clearinghouse stream is already subscribed and the local snapshot is ready,
- order mutation refresh still skips default open-orders/default clearinghouse fallbacks when websocket coverage is usable, but still performs its intended per-dex backstop refreshes.

Acceptance for this milestone is that websocket/user and order-effects regression suites pass and caller namespaces become thin.

### Milestone 4: Document, validate, and QA the new ownership model

Add an ADR describing the permanent owner of account-surface bootstrap and refresh orchestration. Update any governed docs that need the new owner path called out. Run required repository gates sequentially, then perform a browser-inspection QA session against the local app to confirm the refactor preserved runtime behavior. If QA finds defects or architectural leftovers, create `bd` issues linked to the epic, fix them, rerun the relevant tests, and repeat until the QA pass is clean.

Acceptance for this milestone is a completed ADR, green required gates, a QA evidence note under `/hyperopen/docs/qa/`, and closed or linked `bd` issues for any discovered follow-up.

## Concrete Steps

Run all commands from `/Users/barry/.codex/worktrees/dc8d/hyperopen`.

1. Create this plan-backed `bd` work:

    bd create "Consolidate account-surface bootstrap and refresh ownership" \
      --type epic \
      --priority 1 \
      --description "Create one canonical owner for account-surface stream policy and fallback orchestration. Migrate startup/runtime, websocket/user, and order/effects to thin callers, add boundary tests, add an ADR, and finish with browser QA." \
      --spec-id "docs/exec-plans/active/2026-03-07-account-surface-bootstrap-refresh-service.md" \
      --json

    bd create "Extract account-surface bootstrap/refresh service" \
      --type task \
      --priority 1 \
      --parent <epic-id> \
      --description "Implement the account-surface policy/service seam, migrate startup/runtime, websocket/user, and order/effects, add tests, run required gates, and perform browser QA." \
      --spec-id "docs/exec-plans/active/2026-03-07-account-surface-bootstrap-refresh-service.md" \
      --json

    bd update <task-id> --claim --json

2. Add the new policy and service namespaces.

3. Add the ADR describing invariant ownership.

4. Migrate startup/runtime to the service and run milestone tests:

    npx shadow-cljs compile ws-test && node out/ws-test.js --namespace hyperopen.startup.runtime-test

    If namespace-only execution is not available in the generated runner, run:

    npm run test:websocket

5. Migrate websocket/user and order/effects to the service and rerun the affected tests:

    npm run test:websocket

    npm test

6. Run required full gates sequentially:

    npm run check
    npm test
    npm run test:websocket

7. Start the local app for browser QA. If no watcher is already running, use:

    npm run dev

8. Run a browser-inspection session against the local trade page. Prefer a read-only session with managed local app if needed:

    npm run browser:inspect -- --url http://localhost:8080/trade --target local

    Then run session-level evaluation or compare commands to inspect request telemetry and account-surface state for the active address flow.

9. If QA reveals a defect, create a `bd` issue linked to the epic with `discovered-from:<task-id>`, fix it, rerun the relevant tests, update this plan, and repeat step 8.

## Validation and Acceptance

The change is accepted only when all of the following are true:

1. There is one canonical account-surface policy/service owner under `/hyperopen/src/hyperopen/account/`, and the previous duplicated helper logic is removed from startup/runtime, websocket/user, and order/effects.
2. Startup bootstrap behavior is unchanged from a user perspective:
   - stream-covered startup fetches use delayed fallback,
   - non-stream-covered bootstrap fetches still execute,
   - per-dex backfills remain guarded by active address.
3. Websocket fill and ledger update behavior is unchanged from a user perspective:
   - debounced refresh still runs,
   - spot refresh still depends on visible surfaces,
   - per-dex clearinghouse backfill still skips when websocket subscription plus ready snapshot already cover the surface.
4. Order mutation refresh behavior is unchanged from a user perspective:
   - open-orders/default clearinghouse fallbacks still skip when websocket coverage is usable,
   - per-dex backstop refresh remains intact.
5. Boundary and regression tests pass for:
   - `/hyperopen/test/hyperopen/startup/runtime_test.cljs`
   - `/hyperopen/test/hyperopen/websocket/user_test.cljs`
   - `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`
   - any new service/policy test namespace added by this plan
6. Required gates pass sequentially:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
7. Browser QA confirms the local trade route still bootstraps account surfaces and does not regress the post-fill refresh flow.
8. Any discovered issues are tracked in `bd` and either fixed in this session or explicitly handed off with IDs.

## Idempotence and Recovery

This refactor is source-only and safe to reapply. The plan intentionally preserves existing public entrypoints while moving ownership inward, so retrying a partially completed edit should not change external behavior.

If a milestone introduces regressions, recovery is to keep the new account-surface service namespaces in place but temporarily restore thin callers to their previous collaborator wiring one by one until tests are green again. Do not delete the ADR or tracker issues during rollback; instead record the failed assumption in `Decision Log` and `Surprises & Discoveries`.

Browser QA is also safe to repeat because it is read-only inspection against a local dev app. If a local watcher or browser-inspection session gets stuck, stop the session, restart `npm run dev`, and rerun the inspection command.

## Artifacts and Notes

Important artifacts from this plan:

- Completed plan:
  `/hyperopen/docs/exec-plans/completed/2026-03-07-account-surface-bootstrap-refresh-service.md`
- New ADR:
  `/hyperopen/docs/architecture-decision-records/0025-account-surface-bootstrap-refresh-ownership.md`
- QA evidence note:
  `/hyperopen/docs/qa/account-surface-bootstrap-refresh-validation-2026-03-07.md`

Expected repository code touchpoints:

- `/hyperopen/src/hyperopen/account/surface_policy.cljs`
- `/hyperopen/src/hyperopen/account/surface_service.cljs`
- `/hyperopen/src/hyperopen/startup/runtime.cljs`
- `/hyperopen/src/hyperopen/websocket/user.cljs`
- `/hyperopen/src/hyperopen/order/effects.cljs`
- `/hyperopen/test/hyperopen/startup/runtime_test.cljs`
- `/hyperopen/test/hyperopen/websocket/user_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`

## Interfaces and Dependencies

The following interfaces must exist by the end of the plan.

In `/hyperopen/src/hyperopen/account/surface_policy.cljs`, define pure helpers with stable names equivalent to:

    topic-usable-for-address? [state topic address]
    topic-usable-for-address-and-dex? [state topic address dex]
    topic-subscribed-for-address-and-dex? [state topic address dex]
    spot-refresh-surface-active? [state]
    startup-stage-a-surface-plan [state address]
    startup-stage-b-surface-plan [state address dex-names]
    post-event-surface-plan [state {:keys [address trigger dex-names ready-dexs]}]

The exact shape of the plan return maps may evolve during implementation, but they must be plain immutable data and must not perform side effects.

In `/hyperopen/src/hyperopen/account/surface_service.cljs`, define effectful entrypoints with injected collaborators. The service must not hard-code browser timers or transport primitives. It should provide stable public functions equivalent to:

    schedule-startup-fallback! [{:keys [store address topic delay-ms fetch-fn opts active-address-fn]}]
    run-startup-bootstrap! [{:keys [store address ...collaborators]}]
    run-post-event-refresh! [{:keys [store address trigger ...collaborators]}]

The service must depend on injected fetch/projection/logging functions or existing repository facades, and it must preserve active-address stale guards before applying asynchronous results.

The startup/runtime, websocket/user, and order/effects namespaces must depend on these new public functions rather than reproducing the logic inline.

Revision note: 2026-03-07 02:06Z - Initial ExecPlan created after auditing duplicate ownership across startup bootstrap, websocket fill refresh, and order mutation refresh.
Revision note: 2026-03-07 02:31Z - Updated progress, discoveries, outcomes, and validation after implementation, dependency restore, full gates, and browser QA.
Revision note: 2026-03-07 02:32Z - Moved the completed ExecPlan to `/hyperopen/docs/exec-plans/completed/` and marked the final handoff step ready.
