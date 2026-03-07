# Converge Websocket User Into a Thin Runtime Adapter

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, `/hyperopen/src/hyperopen/websocket/user.cljs` will stop acting like a second websocket runtime. Subscription ownership will come from the canonical websocket `runtime-view`, timer ownership will move into a dedicated runtime helper, and user-channel normalization/store-update logic will live behind smaller helper seams. The user-visible result should stay the same: account streams still subscribe and unsubscribe correctly, fill toasts still render, funding and ledger projections still update, and post-fill refresh fallback behavior still works.

A developer can verify the result by reading a small adapter-style `/hyperopen/src/hyperopen/websocket/user.cljs`, by seeing the moved responsibilities in focused `user_runtime` namespaces, by confirming required tests and gates stay green, and by running a browser-inspection QA pass on the local trade page without regressions.

## Progress

- [x] (2026-03-07 03:12Z) Read `/hyperopen/AGENTS.md`, `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/RELIABILITY.md`, and `/hyperopen/docs/tools.md`.
- [x] (2026-03-07 03:12Z) Audited `/hyperopen/src/hyperopen/websocket/user.cljs`, `/hyperopen/src/hyperopen/websocket/client.cljs`, `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`, and existing websocket tests to map current ownership.
- [x] (2026-03-07 03:12Z) Chose the “thin adapter” path for this refactor rather than extending the generic websocket reducer/effect algebra to own store projections in this session.
- [x] (2026-03-07 03:13Z) Authored this active ExecPlan before changing production code.
- [x] (2026-03-07 03:14Z) Created `bd` epic `hyperopen-e98` plus child tasks `hyperopen-b8w`, `hyperopen-m52`, and `hyperopen-wpq`, then claimed `hyperopen-b8w`.
- [x] (2026-03-07 03:21Z) Added `/hyperopen/src/hyperopen/websocket/user_runtime/common.cljs`, `/hyperopen/src/hyperopen/websocket/user_runtime/subscriptions.cljs`, `/hyperopen/src/hyperopen/websocket/user_runtime/refresh.cljs`, `/hyperopen/src/hyperopen/websocket/user_runtime/fills.cljs`, and `/hyperopen/src/hyperopen/websocket/user_runtime/handlers.cljs`.
- [x] (2026-03-07 03:21Z) Removed `user-state` and the namespace-local refresh-timeout atom from `/hyperopen/src/hyperopen/websocket/user.cljs`, replacing them with canonical runtime-view-backed subscription helpers and runtime timeout storage.
- [x] (2026-03-07 03:21Z) Reduced `/hyperopen/src/hyperopen/websocket/user.cljs` to adapter-only entrypoints and handler registration.
- [x] (2026-03-07 03:22Z) Added focused direct tests in `/hyperopen/test/hyperopen/websocket/user_runtime/subscriptions_test.cljs`, `/hyperopen/test/hyperopen/websocket/user_runtime/refresh_test.cljs`, and `/hyperopen/test/hyperopen/websocket/user_runtime/fills_test.cljs`, and updated `/hyperopen/test/hyperopen/websocket/user_test.cljs` for runtime-view-backed subscription state.
- [x] (2026-03-07 03:25Z) Added ADR `/hyperopen/docs/architecture-decision-records/0026-websocket-user-runtime-adapter-boundary.md` and updated `/hyperopen/ARCHITECTURE.md` plus `/hyperopen/docs/RELIABILITY.md`.
- [x] (2026-03-07 03:25Z) Closed extraction tasks `hyperopen-b8w` and `hyperopen-m52`, then claimed validation task `hyperopen-wpq`.
- [x] (2026-03-07 03:24Z) Passed `npm run check`.
- [x] (2026-03-07 03:25Z) Passed `npm test` (`1987 tests`, `10180 assertions`, `0 failures`, `0 errors`).
- [x] (2026-03-07 03:22Z) Passed `npm run test:websocket` (`339 tests`, `1852 assertions`, `0 failures`, `0 errors`) after the extraction.
- [x] (2026-03-07 03:27Z) Completed browser-inspection session `sess-1772853983552-0a819b`, captured snapshot run `inspect-2026-03-07T03-26-58-505Z-b82e9452`, and wrote QA evidence to `/hyperopen/docs/qa/websocket-user-runtime-adapter-validation-2026-03-07.md`.
- [x] (2026-03-07 03:29Z) Browser QA found no verified defects, so no discovered follow-up `bd` issues were created.
- [x] (2026-03-07 03:29Z) Closed validation task `hyperopen-wpq` and epic `hyperopen-e98`.
- [x] (2026-03-07 03:29Z) Moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: `/hyperopen/src/hyperopen/websocket/user.cljs` already delegates some post-fill fallback policy to `/hyperopen/src/hyperopen/account/surface_service.cljs`, but it still owns its own mutable subscription registry, debounce timer, message parsing, toast shaping, and store mutation.
  Evidence: `user-state` at lines 19-20, timeout atom at line 21, refresh wrappers at lines 169-261, fill formatting at lines 300-487, and handlers at lines 496-570.

- Observation: The canonical websocket runtime already tracks desired subscription intent and per-stream subscription state in `runtime-view`.
  Evidence: `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` updates `:desired-subscriptions` and `:streams` in `:cmd/send-message`, and `/hyperopen/src/hyperopen/websocket/application/runtime/subscriptions.cljs` marks `:subscribed?` directly on stream entries.

- Observation: The current user module’s duplicate subscription suppression is local bookkeeping, not a transport requirement.
  Evidence: `subscribe-user!` and `sync-perp-dex-clearinghouse-subscriptions!` use `user-state`, while websocket reconnect replay already comes from reducer-owned `:desired-subscriptions`.

- Observation: The repository already uses small runtime helper namespaces to remove ad hoc mutable UI/runtime ownership from feature modules.
  Evidence: `/hyperopen/src/hyperopen/order/feedback_runtime.cljs`, `/hyperopen/src/hyperopen/websocket/subscriptions_runtime.cljs`, and `/hyperopen/src/hyperopen/websocket/health_runtime.cljs`.

- Observation: `npm run test:websocket` was initially blocked because the worktree did not have local npm dependencies installed.
  Evidence: the first run failed with `sh: shadow-cljs: command not found`; `npm install --no-fund --no-audit` restored the declared toolchain and the rerun passed.

- Observation: Runtime-view-backed subscription diffing required the tests to model reducer-produced stream entries directly instead of resetting a custom `user-state` atom.
  Evidence: `/hyperopen/test/hyperopen/websocket/user_test.cljs` now resets `ws-client/runtime-view` and injects `:subscribed?` stream descriptors to emulate canonical websocket subscription state.

## Decision Log

- Decision: This refactor will turn `/hyperopen/src/hyperopen/websocket/user.cljs` into a thin adapter over focused `user_runtime` helper namespaces instead of teaching the generic websocket reducer/effect algebra to mutate the application store.
  Rationale: The identified issue is module ownership and architecture drift inside `user.cljs`. Moving all user-topic store projections into the generic websocket runtime would require a new application-store effect algebra and a broader cross-cutting architecture change. A thin-adapter refactor fixes the current ownership problem while staying scoped and testable.
  Date/Author: 2026-03-07 / Codex

- Decision: Subscription truth for user and per-dex clearinghouse streams will come from `/hyperopen/src/hyperopen/websocket/client.cljs` `runtime-view` instead of a second mutable `user-state` atom.
  Rationale: ADR 0017 and `/hyperopen/docs/RELIABILITY.md` already establish reducer/engine state plus `runtime-view` as canonical websocket authority. Reusing that state removes parallel subscription bookkeeping.
  Date/Author: 2026-03-07 / Codex

- Decision: The debounced post-fill refresh timer will move into a dedicated runtime helper that stores timer ids under app runtime timeout state.
  Rationale: A named runtime helper makes timer ownership explicit, keeps `user.cljs` smaller, and matches existing project patterns such as order-feedback toast runtime ownership.
  Date/Author: 2026-03-07 / Codex

- Decision: The new helper namespaces will depend directly on existing websocket client, runtime-state, and API seams rather than introducing a second dependency-injection facade just for this refactor.
  Rationale: The current repository already treats these namespaces as stable seams, tests can use `with-redefs` against them directly, and adding another indirection layer here would enlarge the refactor without improving ownership clarity.
  Date/Author: 2026-03-07 / Codex

## Outcomes & Retrospective

Completed outcome:

- `/hyperopen/src/hyperopen/websocket/user.cljs` is now a small adapter namespace that only exposes stable entrypoints and handler registration.
- Subscription duplicate suppression for user and per-dex clearinghouse topics now reads canonical websocket `runtime-view` state instead of a parallel `user-state` atom.
- Post-fill debounce ownership now lives under app runtime timeout state through `/hyperopen/src/hyperopen/websocket/user_runtime/refresh.cljs`.
- Fill shaping, topic parsing, and handler assembly now live in focused helper seams under `/hyperopen/src/hyperopen/websocket/user_runtime/`.
- Direct helper tests and legacy websocket user regression tests pass together.
- Required repository gates and browser QA both passed without finding regressions.

Lessons learned:

- This repository already had the right architectural primitives; the main problem was leaving a legacy adapter namespace large enough to grow its own runtime ownership.
- Browser-runtime smokes were valuable for confirming the new runtime-view-backed subscription logic behaved correctly in the real compiled app, not just in Node-based tests.

## Context and Orientation

The canonical websocket transport runtime already exists in these files:

- `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs`
- `/hyperopen/src/hyperopen/websocket/application/runtime.cljs`
- `/hyperopen/src/hyperopen/websocket/application/runtime_engine.cljs`
- `/hyperopen/src/hyperopen/websocket/infrastructure/runtime_effects.cljs`
- `/hyperopen/src/hyperopen/websocket/client.cljs`

That runtime owns connection state, reconnect logic, input normalization, stream health tracking, desired subscriptions, and the single `runtime-view` projection. `user.cljs` sits on top of that router, but today it still behaves like a second architecture layer:

- it keeps a second mutable subscription registry in `user-state`,
- it keeps its own debounce timer atom for post-fill refresh,
- it owns user fill normalization and toast shaping,
- it owns per-topic websocket message parsing and direct store mutation,
- it wraps REST fallback requests and projections inline.

For this plan, “thin adapter” means `/hyperopen/src/hyperopen/websocket/user.cljs` should expose stable public entrypoints (`subscribe-user!`, `unsubscribe-user!`, `sync-perp-dex-clearinghouse-subscriptions!`, `create-user-handler`, `init!`) but delegate the real work to smaller helper namespaces with explicit ownership.

The relevant current collaborators are:

- `/hyperopen/src/hyperopen/account/context.cljs` for effective-address identity.
- `/hyperopen/src/hyperopen/account/surface_service.cljs` for post-fill fallback orchestration.
- `/hyperopen/src/hyperopen/order/feedback_runtime.cljs` for toast-stack runtime behavior.
- `/hyperopen/src/hyperopen/runtime/state.cljs` for app-scoped runtime timeout storage.
- `/hyperopen/src/hyperopen/domain/funding_history.cljs` for websocket funding normalization and filtering.
- `/hyperopen/src/hyperopen/api/projections.cljs` and `/hyperopen/src/hyperopen/api/default.cljs` for REST fallback projection wrappers.

The key invariant for this refactor is architectural, not product-visible: websocket user flow must stop maintaining parallel runtime truth outside the canonical websocket runtime unless the remaining state is explicitly feature-local and isolated behind a helper seam.

## Plan of Work

### Milestone 1: Remove duplicate mutable runtime ownership

Create `/hyperopen/src/hyperopen/websocket/user_runtime/subscriptions.cljs` and move the address normalization, subscription-key normalization, runtime-view inspection, duplicate suppression, and clearinghouse diff logic there. This new module should inspect `ws-client/runtime-view` (or narrow accessors added to `ws-client`) to determine whether a subscription is already desired/subscribed before sending a new subscribe or unsubscribe message. The goal is to delete `user-state` from `/hyperopen/src/hyperopen/websocket/user.cljs` entirely.

Create `/hyperopen/src/hyperopen/websocket/user_runtime/refresh.cljs` and move the post-fill refresh wrappers plus the debounce timer ownership there. The timer id should live in app runtime timeout state rather than a namespace-local atom. The runtime helper should still guard stale addresses before applying REST results and still delegate refresh policy to `/hyperopen/src/hyperopen/account/surface_service.cljs`.

Acceptance for this milestone is that duplicate user/per-dex subscription suppression still works, reconnect replay still uses the canonical websocket runtime, and no global mutable subscription or refresh-timer atom remains in `user.cljs`.

### Milestone 2: Extract user-message domain helpers and handler assembly

Create `/hyperopen/src/hyperopen/websocket/user_runtime/fills.cljs` for fill identity detection, dedupe, normalization, grouping, and toast payload shaping. Keep these helpers pure except for formatting calls that are already deterministic.

Create `/hyperopen/src/hyperopen/websocket/user_runtime/handlers.cljs` for:

- active-address gating,
- nested channel-row extraction,
- open-orders, fills, fundings, ledger, and clearinghouse handler factories,
- wiring to refresh and toast helpers through injected functions.

The handler module may still mutate the store because the current router contract delivers imperative handlers, but the store updates should be localized there rather than spread across the top-level adapter namespace.

Acceptance for this milestone is that `user.cljs` no longer contains topic parsing, fill formatting, or store projection internals.

### Milestone 3: Thin the adapter, document the boundary, and validate end to end

Update `/hyperopen/src/hyperopen/websocket/user.cljs` so it becomes a stable adapter namespace that:

- delegates subscription operations to `user_runtime.subscriptions`,
- delegates refresh helpers to `user_runtime.refresh`,
- delegates handler creation to `user_runtime.handlers`,
- exposes the same public entrypoints used by startup collaborators and tests.

If the refactor changes canonical architecture ownership beyond what current ADRs already describe, add `/hyperopen/docs/architecture-decision-records/0026-websocket-user-runtime-adapter-boundary.md` and update `/hyperopen/ARCHITECTURE.md` plus `/hyperopen/docs/RELIABILITY.md`.

After code changes, run the required repository gates and a browser-inspection QA session on the local trade page. Capture the QA evidence in `/hyperopen/docs/qa/`. If QA or test review uncovers regressions, create linked `bd` issues with `discovered-from:<active-task-id>`, fix them, and repeat the relevant validation until the run is clean.

Acceptance for this milestone is a small adapter-style `user.cljs`, green required gates, and a documented QA pass with no untracked follow-up work.

## Concrete Steps

Run all commands from the `/hyperopen` repository root.

1. Create the tracker work linked to this spec:

    bd create "Converge websocket user into a thin runtime adapter" \
      -t epic \
      -p 1 \
      --description "Refactor websocket/user.cljs so it stops acting like a second websocket architecture. Replace parallel subscription and timer ownership with canonical runtime-backed helpers, extract message/fill logic into focused user_runtime namespaces, preserve public adapter entrypoints, document the boundary, and finish with full validation plus browser QA." \
      --spec-id "docs/exec-plans/active/2026-03-07-websocket-user-runtime-adapter-convergence.md" \
      --json

    bd create "Move websocket user subscription and timer ownership behind runtime helpers" \
      -t task \
      -p 1 \
      --parent <epic-id> \
      --description "Replace websocket/user.cljs local subscription and timer atoms with focused runtime helpers backed by canonical websocket runtime-view and app runtime timeout state." \
      --spec-id "docs/exec-plans/active/2026-03-07-websocket-user-runtime-adapter-convergence.md" \
      --json

    bd create "Extract user channel handlers and fill shaping into focused runtime helpers" \
      -t task \
      -p 1 \
      --parent <epic-id> \
      --description "Move websocket user message normalization, store projection handlers, fill dedupe, and toast payload shaping out of websocket/user.cljs while preserving behavior and tests." \
      --spec-id "docs/exec-plans/active/2026-03-07-websocket-user-runtime-adapter-convergence.md" \
      --json

    bd create "Document and validate websocket user runtime adapter convergence" \
      -t task \
      -p 1 \
      --parent <epic-id> \
      --description "Update architecture docs or ADRs as needed, run required gates, perform browser QA, and close the epic only after the refactor is fully validated." \
      --spec-id "docs/exec-plans/active/2026-03-07-websocket-user-runtime-adapter-convergence.md" \
      --json

2. Claim the first task before code changes:

    bd update <task-id> --claim --json

3. Implement Milestone 1 and run targeted websocket tests after each extraction.

4. Claim the second task, implement Milestone 2, and rerun websocket tests plus any new direct helper tests.

5. Claim the third task, update docs/ADR if needed, and then run:

    npm run check
    npm test
    npm run test:websocket

6. Start the local app if no dev server is already running:

    npm run dev

7. Run browser-inspection QA against the local trade route and capture artifacts:

    node tools/browser-inspection/src/cli.mjs inspect \
      --url http://localhost:8080/trade \
      --target local

    Optionally start a managed session for follow-up eval:

    node tools/browser-inspection/src/cli.mjs session start \
      --headed \
      --manage-local-app

8. If QA finds a real defect, create a linked issue:

    bd create "Short defect title" \
      -t bug \
      -p 1 \
      --description "Observed during websocket user adapter QA. Include repro, expected behavior, actual behavior, and validation scope." \
      --deps discovered-from:<active-task-id> \
      --json

## Validation and Acceptance

The work is complete only when all of the following are true:

1. `/hyperopen/src/hyperopen/websocket/user.cljs` no longer owns a second mutable websocket subscription registry or a second namespace-local refresh timer registry.
2. Subscription duplicate suppression for user and per-dex clearinghouse topics is derived from canonical websocket runtime state instead of `user-state`.
3. User fill dedupe, fill toast shaping, and per-topic websocket message normalization live in focused helper namespaces and remain behaviorally unchanged.
4. Public entrypoints used by startup and collaborators stay stable:
   - `subscribe-user!`
   - `unsubscribe-user!`
   - `sync-perp-dex-clearinghouse-subscriptions!`
   - `create-user-handler`
   - `init!`
5. Required regression tests cover:
   - runtime-view-backed subscription diffing,
   - post-fill refresh debounce ownership,
   - fill dedupe and toast grouping,
   - active-address gating,
   - existing websocket user handler behavior.
6. Required repository gates pass:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
7. Browser QA confirms the trade page still loads, websocket user handlers do not cause obvious regressions, and no discovered defects remain untracked.
8. All `bd` issues created by this plan are closed before the epic is closed.

## Idempotence and Recovery

These refactor steps are additive and safe to repeat. If a helper extraction breaks tests, revert only the current local edit with a forward fix rather than restoring old duplicate ownership wholesale. If browser QA requires synthetic runtime fixtures, ensure they match production keys exactly, especially `:subscribed?` and keyword health statuses, before concluding the new helper logic is broken.

## Artifacts and Notes

Collected evidence during implementation:

- `bd` epic and child-task ids linked to this ExecPlan.
- Focused websocket test output after each milestone.
- Final gate output for `npm run check`, `npm test`, and `npm run test:websocket`.
- Browser-inspection artifact directory under `/hyperopen/tmp/browser-inspection/`.
- QA summary note under `/hyperopen/docs/qa/`.

## Interfaces and Dependencies

The refactor should preserve these stable interfaces:

- `/hyperopen/src/hyperopen/websocket/user.cljs`
  - `sync-perp-dex-clearinghouse-subscriptions! [address dex-names]`
  - `subscribe-user! [address]`
  - `unsubscribe-user! [address]`
  - `create-user-handler [subscribe-fn unsubscribe-fn]`
  - `init! [store]`

Introduce focused helper seams under `/hyperopen/src/hyperopen/websocket/user_runtime/`:

- `subscriptions.cljs`
  - inspect canonical websocket runtime projection state,
  - normalize address and dex keys,
  - sync user and per-dex clearinghouse subscriptions.
- `refresh.cljs`
  - wrap post-fill REST refresh helpers,
  - own debounce timeout storage via app runtime state.
- `fills.cljs`
  - compute fill identity, dedupe incoming fills, and build toast payloads.
- `handlers.cljs`
  - build topic handler functions for `openOrders`, `userFills`, `userFundings`, `userNonFundingLedgerUpdates`, and `clearinghouseState`.

Plan revision note: 2026-03-07 03:13Z - Initial plan authored after repository audit. The selected scope is a thin-adapter convergence of `websocket/user.cljs` onto focused runtime helpers rather than a broader store-effect migration into the generic websocket reducer.

Plan revision note: 2026-03-07 03:29Z - Updated after implementation, ADR/doc updates, required gate passes, browser QA evidence capture, and `bd` issue closure confirmed the adapter no longer maintains a parallel websocket runtime.
