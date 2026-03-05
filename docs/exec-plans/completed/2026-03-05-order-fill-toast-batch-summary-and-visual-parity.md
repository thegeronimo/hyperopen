# Order-Fill Toast Batch Summaries and Visual Parity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`.

Tracking epic: `hyperopen-dy4`.

## Purpose / Big Picture

Today, a new fill toast often says only `N orders filled.` and does not communicate what actually executed. The current runtime also supports only one visible toast at a time.

After this change, order-fill toasts will present user-meaningful execution summaries in the style shown in the provided screenshots, and multiple incoming notifications will stack concurrently. Each toast card will include a success icon, primary line (`Bought 8.56 HYPE` / `Sold 0.0473 ETH`), secondary line (`At average price of $31.969`), and a manual dismiss control.

A user can verify the outcome by submitting orders that produce one or more fill batches and confirming:

- headline/subline summarize each execution batch by token amount and weighted average fill price
- multiple toasts remain visible as a stack rather than replacing one another immediately

## Progress

- [x] (2026-03-05 01:17Z) Re-read planning and tracking guardrails in `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and UI policy docs.
- [x] (2026-03-05 01:20Z) Traced current fill-toast pipeline in `/hyperopen/src/hyperopen/websocket/user.cljs`, `/hyperopen/src/hyperopen/order/feedback_runtime.cljs`, and `/hyperopen/src/hyperopen/views/notifications_view.cljs`.
- [x] (2026-03-05 01:26Z) Created `bd` tracking epic `hyperopen-dy4` and child tasks `hyperopen-dy4.1` through `hyperopen-dy4.4`.
- [x] (2026-03-05 01:27Z) Authored this ExecPlan with file-level implementation and validation scope.
- [x] (2026-03-05 01:32Z) Added stacked-toast requirement to scope and created `bd` child task `hyperopen-dy4.5`.
- [x] (2026-03-05 03:28Z) Implemented execution-batch summarization in websocket user fills with grouped token totals and weighted average price payloads.
- [x] (2026-03-05 03:41Z) Implemented stacked toast runtime model with bounded queue, per-toast timeout lifecycle, and legacy `[:ui :toast]` compatibility mirror.
- [x] (2026-03-05 03:47Z) Added dismiss action wiring across order actions, runtime collaborators, runtime registration catalog, contracts, and public/macro compatibility surfaces.
- [x] (2026-03-05 04:11Z) Updated runtime/websocket/view regression tests for stacked structured toasts and dismiss behavior.
- [x] (2026-03-05 04:20Z) Passed required validation gates: `npm run check`, `npm test`, `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Fill toasts are emitted only from websocket incremental `userFills` events after dedupe (`novel-fills`), not from snapshots.
  Evidence: `/hyperopen/src/hyperopen/websocket/user.cljs` `user-fills-handler` only calls `show-user-fill-toast!` in the non-snapshot branch.

- Observation: Current toast state is a single `[:ui :toast]` map with only `:kind` and `:message`, and current order-fill copy is count-based.
  Evidence: `fill-toast-message` in `/hyperopen/src/hyperopen/websocket/user.cljs` emits `"Order filled: <coin>."` or `"<count> orders filled."`; `/hyperopen/src/hyperopen/order/feedback_runtime.cljs` stores only a trimmed string message.

- Observation: There is no dedicated action for user-initiated toast dismissal.
  Evidence: No `:actions/*toast*` clear action exists in `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs` and `/hyperopen/src/hyperopen/order/actions.cljs`.

- Observation: Runtime timeout storage currently tracks only one order-toast timeout id.
  Evidence: `/hyperopen/src/hyperopen/runtime/state.cljs` uses `[:timeouts :order-toast]` as a single scalar slot.

- Observation: REST fill fetches already request time aggregation.
  Evidence: `/hyperopen/src/hyperopen/api/endpoints/orders.cljs` sends `{"type" "userFills", "aggregateByTime" true}`.

- Observation: String toast normalization initially produced malformed maps (`{"text" :message}`) because `hash-map` argument order was reversed in a threaded expression.
  Evidence: Failing assertions in runtime/core-bootstrap tests surfaced missing `:message`; corrected in `/hyperopen/src/hyperopen/order/feedback_runtime.cljs` to construct `{:message text}` directly.

## Decision Log

- Decision: Define an "execution batch" as the `new-rows` set produced by websocket incremental `userFills` handling after dedupe.
  Rationale: This is the exact runtime boundary where the current count-based toast is emitted and where duplicate prevention is already enforced.
  Date/Author: 2026-03-05 / Codex

- Decision: Compute toast summary metrics as:
  - total token amount = sum of absolute parsed fill sizes in the batch
  - average price = weighted mean `sum(abs(size) * price) / sum(abs(size))`
  Rationale: Weighted average matches user intent for multi-order execution quality and avoids simple arithmetic mean distortion.
  Date/Author: 2026-03-05 / Codex

- Decision: Initial scope assumed single-toast runtime semantics (no stacked queue refactor), then was superseded by explicit user requirement for stacking.
  Rationale: This captures the scope change history so later implementation notes explain why runtime-state changes became in-scope after plan creation.
  Date/Author: 2026-03-05 / Codex

- Decision: Add an explicit dismiss action path rather than mutating toast state directly from the view.
  Rationale: Keeps behavior aligned with the action/effect architecture and preserves runtime registration and contract validation guarantees.
  Date/Author: 2026-03-05 / Codex

- Decision: Replace single-toast runtime behavior with a bounded stacked model and per-toast timeouts.
  Rationale: User clarified that concurrent notifications must stack. A bounded stack keeps layout stable while preserving visibility for bursts.
  Date/Author: 2026-03-05 / Codex

- Decision: Introduce canonical `:ui :toasts` vector state while keeping compatibility with legacy `:ui :toast` readers during migration.
  Rationale: This enables stacked rendering without breaking existing non-fill toast call paths and tests in one step.
  Date/Author: 2026-03-05 / Codex

## Outcomes & Retrospective

Implementation outcome:

- Fill incrementals now emit batch summaries (`Bought/Sold <amount> <token>`) with weighted average execution subline copy.
- Global order-feedback toasts now stack, are bounded, auto-dismiss independently, and support manual per-toast dismiss.
- Structured toast payloads render with screenshot-aligned hierarchy while legacy toast call sites continue to work via mirrored `[:ui :toast]`.
- Validation gates all passed on 2026-03-05:
  - `npm run check`
  - `npm test`
  - `npm run test:websocket`

## Context and Orientation

Key terms in this plan:

- Execution batch: the deduped vector of new fill rows from one websocket incremental `userFills` message.
- Weighted average fill price: total quote value divided by total filled base size across rows in that execution batch.
- Structured toast payload: a toast map that may include headline/subline metadata in addition to legacy `:message`.

Relevant files:

- `/hyperopen/src/hyperopen/websocket/user.cljs`
  - Current fill dedupe, toast message construction, and toast emission.
- `/hyperopen/src/hyperopen/order/feedback_runtime.cljs`
  - Current toast state write/clear and timeout lifecycle.
- `/hyperopen/src/hyperopen/views/notifications_view.cljs`
  - Current global toast rendering (single-line message, no dismiss button).
- `/hyperopen/src/hyperopen/order/actions.cljs`
  - Place to add a pure action for manual toast dismissal.
- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
  - Runtime action dependency map (`order-action-deps`) that must expose new handler keys.
- `/hyperopen/src/hyperopen/runtime/state.cljs`
  - Runtime timeout state shape; must expand from single order-toast timeout to per-toast timeout tracking.
- `/hyperopen/src/hyperopen/wallet/connection_runtime.cljs`
  - Wallet disconnect cleanup path that currently assumes single-toast timeout clearing.
- `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`
  - Canonical action-id to handler-key registration row list.
- `/hyperopen/src/hyperopen/schema/contracts.cljs`
  - Action argument contract map; must remain in sync with catalog.
- `/hyperopen/src/hyperopen/core/public_actions.cljs` and `/hyperopen/src/hyperopen/core/macros.clj`
  - Compatibility aliases for public action surface.

Primary test anchors:

- `/hyperopen/test/hyperopen/websocket/user_test.cljs`
- `/hyperopen/test/hyperopen/order/feedback_runtime_test.cljs`
- `/hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs`
- `/hyperopen/test/hyperopen/schema/contracts_coverage_test.cljs` (indirectly via full suites)

## Plan of Work

### Milestone 1: Replace count-based fill copy with execution-batch summary metrics

Implement fill-row summarization helpers in `/hyperopen/src/hyperopen/websocket/user.cljs` so `show-user-fill-toast!` emits a summary derived from the batch rows instead of `N orders filled.`. Parse side, coin, size, and price from existing row keys with defensive numeric handling. Compute total size and weighted average price, then build user-facing text:

- headline: `Bought <amount> <TOKEN>` or `Sold <amount> <TOKEN>`
- subline: `At average price of $<price>`

Fallback behavior (when parsing is incomplete) must stay deterministic and safe, preferring token-aware copy over silent failure.

### Milestone 2: Introduce stacked toast runtime state and lifecycle

Refactor `/hyperopen/src/hyperopen/order/feedback_runtime.cljs` and `/hyperopen/src/hyperopen/runtime/state.cljs` so toast runtime supports multiple active toasts at once with deterministic ordering and bounded stack depth.

Required behavior in this milestone:

- Canonical state uses `[:ui :toasts]` vector of toast maps, each with a stable toast id.
- New toasts are appended in display order that keeps the newest toast closest to the bottom-right anchor.
- Stack depth is bounded (drop oldest overflow item).
- Each toast schedules and clears its own timeout id.
- Disconnect cleanup clears all order-toast timeouts and visible toasts.

Compatibility requirement:

- Continue mirroring a legacy `[:ui :toast]` value (for existing read paths/tests) until all consumers migrate.

### Milestone 3: Support structured fill-toast payloads while preserving legacy toast callers

Update `/hyperopen/src/hyperopen/order/feedback_runtime.cljs` so toast writes can normalize both legacy string messages and structured payload maps. Existing order/funding/vault callers that pass strings must continue producing the same state shape they currently assert in tests.

Introduce one new action in `/hyperopen/src/hyperopen/order/actions.cljs` to clear the active toast (`[:effects/save [:ui :toast] nil]`). Wire this action through:

- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`
- `/hyperopen/src/hyperopen/core/public_actions.cljs`
- `/hyperopen/src/hyperopen/core/macros.clj`

### Milestone 4: Apply screenshot-parity stacked toast rendering and dismiss UX

Refactor `/hyperopen/src/hyperopen/views/notifications_view.cljs` to render a two-line toast card with:

- left success icon treatment aligned with screenshot style
- primary headline text and secondary subline text hierarchy
- right-side dismiss button (keyboard focusable, clear `aria-label`)
- stacked responsive layout anchored bottom-right

Non-fill toasts must remain readable and should degrade gracefully when only a single-line message is present.

### Milestone 5: Add regression tests and prove behavior end-to-end

Update/add tests that verify:

- fill batches now produce summary headline/subline content and not order-count copy
- weighted average math is correct for multi-row batches
- multiple toast notifications stack concurrently
- manual dismiss removes only the targeted toast
- timeout expiration removes only the corresponding toast
- app view renders redesigned stacked toasts and dismiss affordance

Then run all required gates and record outputs in this plan.

## Concrete Steps

Run all commands from repository root:

    cd /Users/barry/.codex/worktrees/21ba/hyperopen

1. Implement Milestone 1 logic changes.

    rg -n "fill-toast-message|show-user-fill-toast|user-fills-handler" /hyperopen/src/hyperopen/websocket/user.cljs

2. Implement Milestone 2 runtime and action registration changes.

    rg -n "order-action-deps|action-binding-rows-data|action-args-spec-by-id|order-toast" \
      /hyperopen/src/hyperopen/runtime/collaborators.cljs \
      /hyperopen/src/hyperopen/runtime/state.cljs \
      /hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs \
      /hyperopen/src/hyperopen/schema/contracts.cljs \
      /hyperopen/src/hyperopen/wallet/connection_runtime.cljs

3. Implement Milestone 4 UI changes.

    rg -n "global-toast|notifications-view|toast-region" /hyperopen/src/hyperopen/views/notifications_view.cljs

4. Update regression tests.

    rg -n "Order filled:|orders filled|global-toast|toast" \
      /hyperopen/test/hyperopen/websocket/user_test.cljs \
      /hyperopen/test/hyperopen/views/app_shell_spacing_test.cljs \
      /hyperopen/test/hyperopen/order/feedback_runtime_test.cljs \
      /hyperopen/test/hyperopen/wallet/connection_runtime_test.cljs

5. Run required gates.

    npm run check
    npm test
    npm run test:websocket

6. Update tracking.

    bd close hyperopen-dy4.1 --reason "Completed" --json
    bd close hyperopen-dy4.2 --reason "Completed" --json
    bd close hyperopen-dy4.3 --reason "Completed" --json
    bd close hyperopen-dy4.4 --reason "Completed" --json
    bd close hyperopen-dy4.5 --reason "Completed" --json
    bd close hyperopen-dy4 --reason "Completed" --json

If any task is partial, keep issue open and document exact blockers in both this ExecPlan and the `bd` issue notes.

## Validation and Acceptance

Acceptance criteria:

1. A new incremental fill batch no longer renders count-based wording like `N orders filled.`.
2. Fill toast headline states action + amount + token (for example `Bought 8.56 HYPE`, `Sold 0.0473 ETH`).
3. Fill toast subline states weighted average execution price in USD (`At average price of $...`).
4. Multiple incoming notifications render as a stack instead of replacing the currently visible toast.
5. Toast visual structure matches screenshot intent: dark card, left success icon, typographic hierarchy, dismiss `X` on right.
6. Manual dismiss removes the clicked toast immediately without clearing unrelated stacked toasts.
7. Auto-dismiss timers expire toasts independently.
8. Existing non-fill toast callers still function.
9. Required gates pass:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Idempotence and Recovery

Implementation steps are additive and idempotent when re-run.

If regressions occur:

- Revert only affected files and re-run suites; avoid destructive workspace resets.
- If action registration drift errors occur, reconcile all three authorities together:
  - runtime registration catalog
  - runtime collaborators handler map
  - action arg contracts
- If stacked toasts linger or disappear incorrectly, inspect timeout cleanup symmetry in:
  - `/hyperopen/src/hyperopen/order/feedback_runtime.cljs`
  - `/hyperopen/src/hyperopen/runtime/state.cljs`
  - `/hyperopen/src/hyperopen/wallet/connection_runtime.cljs`
- If copy/formatting mismatches appear in tests, prefer updating formatter helpers in one place rather than patching string literals across multiple call sites.

## Artifacts and Notes

`bd` tracking artifacts created during planning:

- Epic: `hyperopen-dy4`
- Child tasks:
  - `hyperopen-dy4.1` Compute execution-batch token totals and weighted average fill price
  - `hyperopen-dy4.2` Add explicit dismiss action for global order-feedback toast
  - `hyperopen-dy4.3` Expand websocket and view regression coverage for fill toasts
  - `hyperopen-dy4.4` Redesign order-fill toast layout to match provided visual style
  - `hyperopen-dy4.5` Support stacked global toasts for concurrent notifications
- Closure status: all above issues closed as `Completed` on 2026-03-05 after validation gates passed.

Weighted average formula to enforce in implementation/tests:

    avg_price = sum(abs(size_i) * price_i) / sum(abs(size_i))

Representative expected copy shape:

    Sold 12.5 IP
    At average price of $0.88508

## Interfaces and Dependencies

Interface updates implemented:

- In `/hyperopen/src/hyperopen/order/actions.cljs`, `dismiss-order-feedback-toast` now removes a targeted toast id from `[:ui :toasts]` and mirrors the latest remaining toast into legacy `[:ui :toast]`.

- In `/hyperopen/src/hyperopen/websocket/user.cljs`, replace the legacy string-only fill message builder with a summary payload builder that produces deterministic headline/subline values from batch rows.

- In `/hyperopen/src/hyperopen/order/feedback_runtime.cljs`, keep the existing public function names (`set-order-feedback-toast!`, `show-order-feedback-toast!`) but normalize inputs so legacy string callers remain stable while structured fill payloads can carry headline/subline metadata.

- In `/hyperopen/src/hyperopen/views/notifications_view.cljs`, render a stacked toast list from canonical `:ui :toasts`, render structured fill payloads first-class, and fall back to legacy single-line message rendering for other toast callers.

- In `/hyperopen/src/hyperopen/runtime/state.cljs`, expand timeout schema from a single `:order-toast` slot to per-toast timeout tracking for stacked toasts.

Dependency and contract authorities that must stay synchronized:

- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`

Plan revision note: 2026-03-05 01:27Z - Initial ExecPlan authored after repository inspection and `bd` scoping (`hyperopen-dy4` epic + child tasks) to capture implementation sequence and acceptance criteria.
Plan revision note: 2026-03-05 01:33Z - Updated scope to require stacked concurrent toasts and added `hyperopen-dy4.5`; expanded milestones, acceptance criteria, and runtime-state considerations for per-toast timeout lifecycle.
Plan revision note: 2026-03-05 04:21Z - Completed implementation and validation; documented runtime bug discovered during tests (message map normalization), recorded passing required gates, and prepared plan for completion handoff.
