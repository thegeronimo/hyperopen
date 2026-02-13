---
owner: platform
status: canonical
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Quality Scorecard

## Purpose
Track quality posture by product domain and architectural layer, including known gaps.

## Current Score Snapshot
- Websocket runtime architecture: B+
  - Strengths: reducer/effect separation, Anti-Corruption Layer seams, websocket-focused test suite.
  - Gaps: continue expanding replay and burst-coalescing regressions for new flows.
- Signing and exchange Application Programming Interface safety: B
  - Strengths: signing utilities and parity test baseline.
  - Gaps: maintain high-signal vector coverage when action serialization changes.
- Trading UI interaction reliability: B
  - Strengths: targeted interaction regressions and lint checks for Hiccup class/style attrs.
  - Gaps: keep strict effect-order and no-duplicate-effects coverage for every new selection path.
- Documentation governance: C -> target A-
  - Strengths: docs-as-source-of-truth architecture and indexed knowledge layout.
  - Gaps: sustain freshness reviews and index coverage discipline.

## Testing Requirements (MUST)
- MUST include reducer determinism tests (same state + same msg => same state/effects).
- MUST include single-writer invariant tests for runtime ownership.
- MUST include FIFO and replay correctness tests under reconnect.
- MUST include market coalescing correctness tests under burst traffic.
- MUST include lossless ordering tests for `openOrders`, `userFills`, `userFundings`, and `userNonFundingLedgerUpdates`.
- MUST include lifecycle and watchdog behavior tests.
- MUST include address-watcher compatibility tests for websocket status transitions.
- MUST include effect-order tests for user-interaction actions where responsiveness is critical (UI-close/save projection effects must precede heavy subscription/fetch effects).
- MUST include no-duplicate-effects tests for selection flows (no repeated network/subscription effects caused by one action dispatch).
- MUST include view fallback tests ensuring active symbol/icon text renders from canonical identity when market projection is partial.
- MUST include regression tests for selection transitions from asset A -> B covering both timing and render correctness.
- MUST include signing parity tests with known vectors for each signed exchange action touched by a change, including large-integer boundary cases.
- MUST include regression tests that guard signer/session reconciliation behavior when persisted `agent-address` and derived key address diverge.
- MUST include regression tests for missing Application Programming Interface wallet handling that verify credential preservation vs invalidation decisions.
- MUST validate new/changed signing vectors against at least one listed reference SDK; for high-risk or ambiguous cases, use at least two references.
- MUST pass compile gates: `npx shadow-cljs compile app` and `npx shadow-cljs compile test`.
- MUST keep websocket-focused tests independently runnable.

## TDD Workflow (MUST)
- MUST follow Red -> Green -> Refactor for behavior changes.
- MUST add a failing regression test first for bug fixes before implementing code changes.
- MUST test pure decision logic directly without input/output dependencies.
- MUST test input/output boundaries with fakes/stubs and deterministic assertions.
- MUST keep tests deterministic (no uncontrolled wall-clock, randomness, or network).

## Tests As Documentation (MUST)
- MUST use behavior-oriented test names that describe invariants and expected outcomes.
- MUST encode fallback behavior explicitly in tests when projections can be partial.
- MUST encode ordering guarantees in tests for interaction-critical and websocket flows.
- MUST prefer focused tests that document one invariant each instead of broad opaque fixtures.

## Definition Of Done (MUST)
- MUST pass `npm run check`.
- MUST pass `npm test`.
- MUST pass `npm run test:websocket`.
- MUST keep `/hyperopen/.github/workflows/tests.yml` required in branch protection.

## Required Gates
- `npm run check`
- `npm test`
- `npm run test:websocket`
