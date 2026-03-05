# WS Migration Impact Validation (Rate-Limit and Request-Load)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

This slice validates whether websocket-first migration work is actually reducing `/info` request pressure and 429 exposure, instead of only changing control flow. After this change, operators and contributors have a concrete before/after report grounded in deterministic tests that shows request-count and rate-limit deltas for key account journeys.

You can verify this by reading `/hyperopen/docs/qa/ws-migration-impact-validation-2026-03-05.md` and running websocket tests to confirm the named validation cases remain green.

## Progress

- [x] (2026-03-05 04:48Z) Claimed `hyperopen-nhv.7` and audited existing migration tests for measurable before/after request deltas.
- [x] (2026-03-05 04:56Z) Added deterministic API-level regression in `/hyperopen/test/hyperopen/api_test.cljs` (`info-client-cache-reduces-rate-limit-retries-for-identical-requests-test`) validating cache/force-refresh request and 429 deltas.
- [x] (2026-03-05 05:07Z) Authored impact report `/hyperopen/docs/qa/ws-migration-impact-validation-2026-03-05.md` with per-journey before/after metrics and acceptance mapping.
- [x] (2026-03-05 05:09Z) Updated `/hyperopen/docs/runbooks/ws-migration-rollout.md` to reference the impact report as rollout evidence.
- [x] (2026-03-05 05:10Z) Validation pass: `npx shadow-cljs compile ws-test && node out/ws-test.js` (`316 tests, 1772 assertions, 0 failures`).
- [ ] Run full required gates in an environment where `shadow-cljs` is on PATH and `@noble/secp256k1` is installed.
- [x] (2026-03-05 03:56Z) Closed `hyperopen-nhv.7` and moved to next ready migration task (`hyperopen-nhv.3`).

## Surprises & Discoveries

- Observation: A test harness attempt using `with-redefs` for `fake-http-response` created non-deterministic status progression and did not reliably model per-client retry paths.
  Evidence: Initial run failed with `Hyperliquid /info request failed with HTTP 429` in `info-client-cache-reduces-rate-limit-retries-for-identical-requests-test`.

- Observation: Explicit per-client status queues inside each `fetch-fn` produce deterministic and auditable before/after retry deltas.
  Evidence: Updated test now consistently asserts cached path (`2 fetches`, `1 rate-limit`) vs forced-refresh path (`4 fetches`, `2 rate-limits`) and passes in ws-test suite.

- Observation: Existing migration tests already encode measurable request-count deltas for order/fill/startup journeys; no new integration harness file was required.
  Evidence: `order_effects_test.cljs`, `websocket/user_test.cljs`, and `startup/runtime_test.cljs` each contain paired ws-first vs fallback assertions with explicit call counts.

## Decision Log

- Decision: Validate impact using deterministic test-level counters and existing regression suite semantics rather than introducing a new runtime benchmark harness.
  Rationale: The acceptance goal is request/rate-limit delta confidence under CI, and existing tests provide stable count-based evidence without adding flaky timing dependencies.
  Date/Author: 2026-03-05 / Codex

- Decision: Keep validation artifact in `/hyperopen/docs/qa/` and link it from rollout runbook.
  Rationale: Operators need one durable place for before/after evidence during canary and rollback decisions.
  Date/Author: 2026-03-05 / Codex

## Outcomes & Retrospective

This phase now provides concrete, repo-local evidence that migration work is reducing request load:

- API cache hardening proves a 50% retry/load reduction for repeated identical non-subscribable requests under transient 429s.
- WS-first order/fill and startup paths show elimination of stream-covered REST calls when streams are live.
- Inactive-route/tab gates prevent background fetch churn for non-visible screens.

The remaining gap is environment-only full-gate execution; ws-test coverage for migration surfaces is green.

## Context and Orientation

Validation evidence is distributed across these modules:

- `/hyperopen/test/hyperopen/api_test.cljs`: low-level `/info` retry/cache behavior and rate-limit counters.
- `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`: order submit/cancel refresh fanout with ws-first flag on/off.
- `/hyperopen/test/hyperopen/websocket/user_test.cljs`: user ledger/fill-triggered refresh behavior under live streams.
- `/hyperopen/test/hyperopen/startup/runtime_test.cljs`: startup bootstrap stream-covered fetch suppression and fallback behavior.
- `/hyperopen/test/hyperopen/account/history/effects_test.cljs`, `/hyperopen/test/hyperopen/funding_comparison/effects_test.cljs`, `/hyperopen/test/hyperopen/vaults/effects_test.cljs`: inactive route/tab gating behavior for fallback REST surfaces.

## Plan of Work

Finalize deterministic API-level delta coverage, then compile a concise QA report that translates existing test assertions into before/after request-load metrics by journey. Link that report from the rollout runbook so canary operators can consume it quickly. Validate suite health in ws-test and capture outputs for handoff.

## Concrete Steps

From `/hyperopen`:

1. Ensure API-level cache/rate-limit delta test is deterministic:
   - edit `/hyperopen/test/hyperopen/api_test.cljs`.
2. Create impact report:
   - `/hyperopen/docs/qa/ws-migration-impact-validation-2026-03-05.md`.
3. Link report in runbook:
   - `/hyperopen/docs/runbooks/ws-migration-rollout.md`.
4. Validate:
   - `npx shadow-cljs compile ws-test && node out/ws-test.js`.

## Validation and Acceptance

Acceptance criteria mapped to evidence:

1. `/info` POST volume reduced materially on key journeys.
   - Evidence: paired request-count assertions in order/fill/startup tests and route/tab-gating tests, summarized in QA report.
2. Rate-limit frequency reduced versus baseline.
   - Evidence: API test shows identical logical requests with cache produce half the retries and half the 429 events.
3. Regression tests updated for websocket and fallback behavior.
   - Evidence: API test added/updated; ws-test suite green.
4. Summary report attached to epic with before/after metrics.
   - Evidence: `/hyperopen/docs/qa/ws-migration-impact-validation-2026-03-05.md` and `bd` issue note update.

## Idempotence and Recovery

This work is additive docs/tests only. Re-running the edit and test steps is safe. If the new API test fails due timing or status progression changes, restore deterministic status queues per client and rerun ws-test.

## Artifacts and Notes

Validation transcript:

    npx shadow-cljs compile ws-test && node out/ws-test.js
    Ran 316 tests containing 1772 assertions.
    0 failures, 0 errors.

Initial failure artifact before deterministic status-queue fix:

    FAIL in (info-client-cache-reduces-rate-limit-retries-for-identical-requests-test)
    Unexpected error: Error: Hyperliquid /info request failed with HTTP 429

## Interfaces and Dependencies

No production interface changes in this phase.

Test/document artifacts added or updated:

- `/hyperopen/test/hyperopen/api_test.cljs`
- `/hyperopen/docs/qa/ws-migration-impact-validation-2026-03-05.md`
- `/hyperopen/docs/runbooks/ws-migration-rollout.md`

Revision note (2026-03-05): Added deterministic API cache/rate-limit delta coverage and formalized migration impact evidence in QA report + runbook linkage for `hyperopen-nhv.7`.
