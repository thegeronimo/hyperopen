# Vol Range stage 5: order lifecycle (placement, reconciliation, fills, cancel, repair, replacement)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It is maintained in accordance with `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

HO-SPEC-014 v2.1 section 15.1, PR 5: the grid lifecycle on top of the stage 4 foundations. After it lands, the application layer can place a reviewed grid, interpret every per-leg outcome, reconcile unknown outcomes by original ids, ingest fills exactly once, cancel a ladder's full membership, repair rejected legs and prepare a replacement from refreshed remaining quantities, and rebuild all of that from the journal after a reload.

Nothing here is reachable from the UI: no action, view or effect adapter calls the executor yet (stage 6 adds the commit action and the dock, stage 7 enables execution). The A0 boundary test keeps proving that the preview cannot reach any of it (VR-24).

Exit condition (spec 15.1): simulator scenarios prove partial and unknown outcomes, reload recovery, and cancel/fill race behavior.

## Context References

- Direct user request in this session (2026-10-27): "Go ahead with stage 5 (order lifecycle) using the same approach: build, fresh Opus review, fix, gates, commit. Don't push."
- Parent ExecPlan: `/hyperopen/docs/exec-plans/active/2026-10-21-vol-range-release-a0-preview.md`.
- Foundations: `/hyperopen/docs/exec-plans/completed/2026-10-27-vol-range-execution-foundations.md` and `/hyperopen/docs/design-docs/vol-range-execution-coordination.md`.
- Product spec: `/hyperopen/docs/product-specs/vol-range-implementation-spec.md` (HO-SPEC-014 v2.1), sections 7.4-7.6, 8, 9, 10, 13.4 and VR-06, VR-09, VR-11 to VR-18.

## Plan

1. Pure lifecycle (`vol_range/application/lifecycle*.cljs`): grid model with explicit logical membership across revisions and repairs, per-leg model (spec 8.5), evidence application (placement results, orders snapshots, order statuses, fills, cancel results), grid status projection with counts, cancel targets, repair candidates, replacement quantities, reservation release.
2. Pure wire mapping and final-guard revalidation (`vol_range/application/wire.cljs`, `revalidation.cljs`): ALO `order` action in the signing key order, cancel actions, action digest, the fixed-vector revalidation with pending reservations.
3. Journal records for grids, cancel attempts and reservation releases; pending reservations exclude released operations.
4. Reads: paginated `userFillsByTime` window read with overlap dedupe, truncation and no-progress guards.
5. Executor (`vol_range/infrastructure/executor*.cljs`): place, reconcile, cancel, repair, recover, with all collaborators injected.
6. Simulator tests for every scenario in the exit condition and spec 13.4.

## Progress

- [x] (2026-10-27) Pure lifecycle: `lifecycle.cljs` (grid, legs, status, cancel targets, repair, replacement basis, reservation release), `lifecycle_evidence.cljs` (placement results, snapshots, statuses, fills, cancel results), `lifecycle_recovery.cljs` (reload recovery, recovered grids, monotonic merge).
- [x] (2026-10-27) Wire mapping (`wire.cljs`) and fixed-vector and repair revalidation (`revalidation.cljs`).
- [x] (2026-10-27) Journal records for grids, cancel attempts and reservation releases.
- [x] (2026-10-27) Paginated `userFillsByTime` window read.
- [x] (2026-10-27) Executor: place, repair, reconcile, cancel, recover.
- [x] (2026-10-27) Simulator scenarios for spec 13.4 and the exit condition.
- [x] (2026-10-27) First independent Opus review (1 blocker, 7 major) and fixes.
- [x] (2026-10-27) Second Opus review of the fixes (4 major, 6 minor) and fixes.
- [x] (2026-10-27) Gates and commit.

## Surprises & Discoveries

- Whole-record grid writes from flows holding older copies could erase a sent repair and allow a second one (review 1, blocker). Grid writes are now load + monotonic merge + write under a per-grid lock, and refuse to write when the journal cannot be read.
- A send-attempt record can land AFTER the delivery path already proved the send never started (pre-send timeout racing the attempt commit). A not-sent outcome now records whether delivery proved it; only such proof, or evidence-based resolution, settles a committed attempt.
- A cancel acknowledgment or missing-order reply followed by an "open" status is not proof either way until the cancel's deadline passes; after it, the cancel is recorded as failed and the order can be canceled again.

## Decision Log

- The WebSocket fill tap (feeding raw `userFills` rows to the lifecycle before display truncation) is deferred to stage 6, where the account runtime first has live grids to feed. Stage 5 ingests fills from REST windows; ingestion is idempotent either way.
- One active ladder per market: a new grid is blocked while any grid in the same scope is not closed, including recovered orders.
- `unknownOid` for a CLOID never resolves a leg, even after the action deadline (spec 8.6: only a protocol-proven rule may). Such a grid stays Checking and blocks the market until the user cancels by CLOID or a later status settles it. Proving "expired and unobserved" against Hyperliquid is a stage 7 protocol-fixture item.
- Repair admission checks rejected quantity, distinct members and prices, product distance from mid, post-only, legal price and lot, minimum notional and funds. The inventory bounds of spec 6.6 are re-checked by the repair review that stage 6 adds.
- Cancel never takes the admission section, never needs the execution flag and never needs a journal commit; it does need the same account and market as the grid, mutation permission and a ready signer.

## Outcomes & Retrospective

Two independent reviews found real defects, all fixed with regression tests. Known limits:

- The simulator replaces the delivery path, so VR-11's "cancel works through the shared coordinator while storage fails" is covered by stage 4's delivery and coordinator tests plus this stage's executor tests, not end to end.
- The simulator applies holds and balance changes atomically with order and fill changes, so "order visible before hold" and "fill before debit" are covered only through the lost-response ordering and the release rule's request-time barrier.
- Cross-tab locking runs with the in-tab fallback in Node; Web Locks behaviour needs the stage 7 browser test.

## Validation

`npm test`, `npm run gates`, and the lints listed in AGENTS.md.
