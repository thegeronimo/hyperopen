# Compute Portfolio Metrics Signature Before Request Derivation

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, repeated portfolio VM renders with unchanged benchmark/strategy source versions avoid rebuilding portfolio metrics request payloads before dedupe checks. Previously, `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` computed request-data (including `daily-compounded-returns`) before request signature dedupe, which wasted CPU on steady-state renders. The pipeline now computes signature first and only derives request-data when needed.

A developer can verify this by running portfolio VM regression tests and the timing note harness that compares pre-change ordering (always derive request-data before signature compare) versus gated ordering (signature compare first, skip request-data when unchanged).

## Progress

- [x] (2026-03-03 22:40Z) Claimed `hyperopen-4uk` and audited current portfolio metrics flow in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` and `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs`.
- [x] (2026-03-03 22:46Z) Authored this active ExecPlan.
- [x] (2026-03-03 22:49Z) Refactored `performance-metrics-model` to compute signature first and lazily build request-data only when required.
- [x] (2026-03-03 22:51Z) Decoupled benchmark column derivation from request payload data so unchanged worker signatures no longer force request-data shaping.
- [x] (2026-03-03 22:54Z) Added regression test coverage for unchanged-signature short-circuit and timing-note harness in `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs`.
- [x] (2026-03-03 23:40Z) Captured before/after timing note for repeated unchanged signatures using direct timing harness invocation.
- [x] (2026-03-03 23:44Z) Ran required gates on final tree: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-03-03 23:45Z) Updated plan outcomes and prepared move to completed.

## Surprises & Discoveries

- Observation: request dedupe existed only inside `request-metrics-computation!`; `performance-metrics-model` still eagerly built `request-data` before dedupe was reached.
  Evidence: Pre-change binding order in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` bound `request-data` before `request-signature`.

- Observation: benchmark columns in `performance-metrics-model` were derived from `benchmark-requests` (request payload shape), even though this display model can be derived directly from selected coins and benchmark context cumulative rows.
  Evidence: Pre-change code mapped benchmark columns over `(:benchmark-requests request-data)`.

- Observation: the CLI `--test=<fqn-var>` path in this environment did not execute the newly added long-form timing var directly, but the var remained callable once the namespace was loaded.
  Evidence: `node out/test.js --test=...timing-note-test` reported `Ran 0 tests`; direct invocation of `hyperopen.views.portfolio.vm.benchmarks_test.portfolio_vm_performance_metrics_signature_gate_timing_note_test()` produced timing output and assertions.

## Decision Log

- Decision: Keep optimization scoped to `performance-metrics-model` orchestration; do not alter worker message shape or `compute-metrics-sync` semantics.
  Rationale: The issue is ordering and eager derivation, not a compute contract mismatch.
  Date/Author: 2026-03-03 / Codex

- Decision: Preserve synchronous fallback behavior (when worker is unavailable) and only skip request-data derivation for worker-backed unchanged signatures.
  Rationale: Sync fallback requires request-data to compute immediate metrics result and should remain behaviorally identical.
  Date/Author: 2026-03-03 / Codex

- Decision: Build benchmark columns directly from selected benchmark coins + benchmark context cumulative rows instead of request payload internals.
  Rationale: This removes request-data as a prerequisite for metrics table projection and enables signature-first short-circuiting.
  Date/Author: 2026-03-03 / Codex

- Decision: Capture timing evidence with a deterministic harness that compares pre-change ordering versus gated ordering under repeated unchanged signatures.
  Rationale: This isolates exactly the cost addressed by the issue (request-data derivation before signature compare).
  Date/Author: 2026-03-03 / Codex

## Outcomes & Retrospective

Completed.

What changed:

- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
  - `performance-metrics-model` now computes `request-signature` before request payload derivation.
  - Added `request-signature-changed?` gate and lazy `request-data` construction.
  - Worker dispatch now occurs only when signature changed and request-data exists.
  - Benchmark columns now derive from `selected-benchmark-coins` and `benchmark-cumulative-rows-by-coin`, not from `request-data` internals.

- `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs`
  - Added `portfolio-vm-performance-metrics-model-skips-request-data-build-when-worker-signature-unchanged-test`.
  - Added `portfolio-vm-performance-metrics-signature-gate-timing-note-test` harness to capture before/after ordering timings and daily-row call counts.

Behavioral result:

- Worker-backed unchanged signatures now skip request-data derivation and therefore skip eager daily-compounded-row derivation in the VM hot path.
- Sync fallback behavior remains unchanged.

## Context and Orientation

Portfolio performance metrics live in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`.

The key orchestration path is:

- `benchmark-computation-context`: derives strategy/benchmark cumulative rows plus lightweight source version counters.
- `metrics-request-signature`: derives dedupe signature from summary time range, selected benchmark coins, and source versions.
- `build-metrics-request-data`: builds request payloads and computes daily compounded rows.
- `performance-metrics-model`: assembles display model, requests worker computation, and projects benchmark/portfolio metric columns.

This change reordered `performance-metrics-model` so unchanged worker signatures avoid running `build-metrics-request-data`.

## Plan of Work

Milestone 1 updated orchestration order in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`: compute request signature first, determine signature-change status, and only derive request-data when required.

Milestone 2 removed request-data coupling from benchmark table projection by deriving benchmark columns from selected coins and benchmark context cumulative rows.

Milestone 3 added regression and timing harness coverage in `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs`.

Milestone 4 ran required gates and recorded timing evidence.

## Concrete Steps

1. Edited `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`:
   - reordered `performance-metrics-model` bindings to signature-first flow;
   - gated `build-metrics-request-data` behind `(or (nil? worker) request-signature-changed?)`;
   - built benchmark columns from selected coin ids and benchmark context rows.
2. Edited `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs`:
   - added unchanged-signature short-circuit regression;
   - added timing note harness for pre-vs-post ordering.
3. Ran timing evidence command from `/hyperopen`:

       npx shadow-cljs compile test && node <<'NODE'
       process.exit = () => {};
       process.argv = ["node", "out/test.js", "--test=hyperopen.views.portfolio.vm.benchmarks-test/portfolio-vm-builds-returns-benchmark-options-from-asset-selector-markets-test"];
       require("./out/test.js");
       const ns = global.hyperopen.views.portfolio.vm.benchmarks_test;
       ns.portfolio_vm_performance_metrics_signature_gate_timing_note_test();
       NODE

4. Ran required validation gates from `/hyperopen`:

       npm run check
       npm test
       npm run test:websocket

5. Updated this plan’s living sections and moved it to `/hyperopen/docs/exec-plans/completed/`.

## Validation and Acceptance

Acceptance criteria status:

1. `performance-metrics-model` computes request signature before request-data construction. Passed.
2. Worker-backed repeated renders with unchanged signatures skip `build-metrics-request-data`. Passed (regression test + timing harness call counts).
3. Synchronous fallback behavior remains unchanged. Passed (full test suite).
4. Regression tests cover unchanged-signature short-circuit. Passed.
5. Timing note captured before/after ordering results. Passed.
6. Required gates pass. Passed.

Validation outputs:

- `npm run check` passed.
- `npm test` passed (`Ran 1831 tests containing 9503 assertions. 0 failures, 0 errors.`).
- `npm run test:websocket` passed (`Ran 290 tests containing 1662 assertions. 0 failures, 0 errors.`).

Timing evidence (repeated unchanged signatures, 250 iterations, synthetic 2400-row series):

- `[portfolio-metrics-signature-gate] baseline-ms=103.629 gated-ms=2.254 baseline-daily-calls=250 gated-daily-calls=0 iterations=250`

This demonstrates the ordering fix: unchanged signatures eliminate daily-row derivation work and substantially reduce the targeted ordering path time.

## Idempotence and Recovery

This change is idempotent and safe to re-run. It is confined to VM orchestration and tests.

If regressions occur:

- Keep the new regression/timing tests.
- Revert only `performance-metrics-model` ordering changes and reintroduce in small steps (signature-first, then lazy request-data, then benchmark-column decoupling).

No migrations or destructive operations are involved.

## Artifacts and Notes

Evidence captured:

- Required gate outputs from `npm run check`, `npm test`, and `npm run test:websocket`.
- Timing note output from direct harness invocation:
  - `baseline-ms=103.629`
  - `gated-ms=2.254`
  - `baseline-daily-calls=250`
  - `gated-daily-calls=0`

Implementation targets:

- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
- `/hyperopen/test/hyperopen/views/portfolio/vm/benchmarks_test.cljs`

## Interfaces and Dependencies

Interfaces kept stable:

- `hyperopen.views.portfolio.vm/portfolio-vm` output shape consumed by views.
- Worker message contract (`"compute-metrics"`, `"metrics-result"`) and payload format.
- `build-metrics-request-data` and `compute-metrics-sync` request semantics.

No new third-party dependencies were introduced.

Plan revision note: 2026-03-03 22:46Z - Initial ExecPlan created for `hyperopen-4uk` to move metrics signature gating ahead of request-data derivation and capture timing evidence.
Plan revision note: 2026-03-03 23:45Z - Marked implementation complete, recorded timing evidence and required-gate outcomes, and prepared move to completed.
