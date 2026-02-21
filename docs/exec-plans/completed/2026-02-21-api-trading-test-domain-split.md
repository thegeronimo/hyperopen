# Split API Trading Tests into Domain-Focused Namespaces

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

`/hyperopen/test/hyperopen/api/trading_test.cljs` currently contains 20 tests across 793 lines and mixes multiple concerns: cancel payload normalization, exchange approve-agent posting, signing and nonce retry policy, key/session reconciliation, and missing API wallet invalidation behavior. That broad scope makes local reasoning expensive for humans and coding agents because unrelated setup and mocks must be loaded to change one behavior.

After this refactor, API trading tests will be split into focused namespaces under `/hyperopen/test/hyperopen/api/trading/` with one shared support namespace for reusable fixtures and helper conversions. The top-level `/hyperopen/test/hyperopen/api/trading_test.cljs` will become a thin facade-level seam with minimal integration-oriented checks.

A contributor will be able to open one concern-specific file, understand one invariant family, run tests, and evolve behavior without scanning a monolithic fixture-heavy namespace.

## Progress

- [x] (2026-02-21 12:02Z) Re-read `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md` and confirmed ExecPlan and storage requirements.
- [x] (2026-02-21 12:02Z) Audited `/hyperopen/test/hyperopen/api/trading_test.cljs` and captured baseline shape: 793 lines and 20 `deftest` forms.
- [x] (2026-02-21 12:02Z) Audited production seams in `/hyperopen/src/hyperopen/api/trading.cljs` to align split boundaries with domain/application concerns.
- [x] (2026-02-21 12:02Z) Authored this active ExecPlan before implementation.
- [x] (2026-02-21 12:06Z) Created shared support namespace `/hyperopen/test/hyperopen/api/trading/test_support.cljs` for stable store fixtures, fetch stubbing, and JSON payload parsing.
- [x] (2026-02-21 12:06Z) Split monolithic tests into focused namespaces under `/hyperopen/test/hyperopen/api/trading/` by concern.
- [x] (2026-02-21 12:06Z) Reduced `/hyperopen/test/hyperopen/api/trading_test.cljs` to a thin facade-oriented namespace.
- [x] (2026-02-21 12:06Z) Added safe-integer boundary coverage for signing-critical fields (`oid`, nonce payload) without production behavior changes.
- [x] (2026-02-21 12:06Z) Ran required validation gates successfully: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-02-21 12:06Z) Updated this plan with concrete outcomes and evidence before moving it to completed.

## Surprises & Discoveries

- Observation: No test-runner wiring updates were required for this split.
  Evidence: `/hyperopen/shadow-cljs.edn` test build uses `:ns-regexp`, and `npm test` discovered all new `hyperopen.api.trading.*-test` namespaces automatically.

## Decision Log

- Decision: perform a test-topology refactor first and keep production code untouched unless tests prove an existing behavior bug.
  Rationale: the user requested an audit-driven organization refactor; changing production code would add unnecessary risk and scope.
  Date/Author: 2026-02-21 / Codex

- Decision: keep a thin top-level `hyperopen.api.trading-test` namespace after splitting.
  Rationale: preserving a top-level seam keeps historical discoverability and allows a minimal integration contract in one place.
  Date/Author: 2026-02-21 / Codex

- Decision: keep a small, explicit private-seam test namespace (`internal-seams-test`) instead of removing all private-var tests.
  Rationale: optional `vault-address` and `expires-after` behavior is implemented behind private helpers and is not reachable through current public entry points; retaining focused internal contract tests preserves coverage while minimizing coupling in other files.
  Date/Author: 2026-02-21 / Codex

## Outcomes & Retrospective

Implementation completed as a behavior-preserving test-topology refactor with targeted boundary hardening.

Created shared support namespace:

- `/hyperopen/test/hyperopen/api/trading/test_support.cljs`

Created focused domain-oriented test namespaces:

- `/hyperopen/test/hyperopen/api/trading/cancel_request_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/approve_agent_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/sign_and_submit_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/session_invalidation_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/internal_seams_test.cljs`

Reduced top-level facade namespace:

- `/hyperopen/test/hyperopen/api/trading_test.cljs` from 793 lines to 37 lines.

Coverage shape:

- before: 20 `deftest` forms in one namespace.
- after: 25 `deftest` forms across focused namespaces plus thin facade.
- added boundary coverage: safe-integer vectors for cancel-order OID normalization and signed payload nonce serialization.

Validation outcomes:

- `npm run check`: pass.
- `npm test`: pass (`Ran 1176 tests containing 5467 assertions. 0 failures, 0 errors.`).
- `npm run test:websocket`: pass (`Ran 135 tests containing 587 assertions. 0 failures, 0 errors.`).

Residual risk is low. Remaining coupling risk is intentionally isolated in `internal_seams_test` for private helpers whose optional branches are not exposed through current public API seams.

## Context and Orientation

Production behavior lives in `/hyperopen/src/hyperopen/api/trading.cljs`. This namespace handles exchange payload shaping, signing submission, nonce retry policy, and missing API wallet invalidation decisions by coordinating infrastructure collaborators (`js/fetch`, signing utilities, and agent-session persistence).

The current test file `/hyperopen/test/hyperopen/api/trading_test.cljs` validates both pure normalization decisions and effectful boundary orchestration in one namespace. This refactor will reorganize tests only. No public production API changes are planned.

Test discovery for `npm test` is driven by namespace regex in `/hyperopen/shadow-cljs.edn` and includes `hyperopen.*-test` namespaces. Any new namespace under `hyperopen.api.trading.*-test` will be automatically included.

## Plan of Work

Milestone 1 introduces a shared support namespace at `/hyperopen/test/hyperopen/api/trading/test_support.cljs`. This file will contain small stable helpers for repeated fixtures and fetch payload parsing so tests become shorter and less repetitive.

Milestone 2 splits the monolith into concern-scoped files under `/hyperopen/test/hyperopen/api/trading/`:

- cancel request normalization and asset-id fallback invariants,
- approve-agent boundary posting,
- sign/submit nonce and session reconciliation behavior,
- missing API wallet invalidation vs preservation behavior,
- private seam contract checks that cannot be expressed through public functions.

Milestone 3 reduces `/hyperopen/test/hyperopen/api/trading_test.cljs` to a thin facade integration seam while keeping coverage continuity.

Milestone 4 adds explicit large safe-integer boundary tests for consensus-critical values in request normalization and signed payload serialization paths.

Milestone 5 runs required repository gates and records outcomes in this plan, then moves the plan to completed.

## Concrete Steps

1. Create `/hyperopen/test/hyperopen/api/trading/test_support.cljs` with reusable helpers (`ready-agent-store`, fetch payload parsing, JSON response builders).
2. Create focused test namespaces under `/hyperopen/test/hyperopen/api/trading/` and migrate tests from `/hyperopen/test/hyperopen/api/trading_test.cljs` without behavior changes.
3. Rewrite `/hyperopen/test/hyperopen/api/trading_test.cljs` to a thin facade-level namespace with a small integration slice.
4. Add safe-integer boundary vectors for `resolve-cancel-order-oid` and signed exchange nonce payload serialization.
5. Run commands from `/hyperopen`:

   npm run check
   npm test
   npm run test:websocket

6. Update this document sections (`Progress`, `Surprises & Discoveries`, `Decision Log`, `Outcomes & Retrospective`, `Artifacts and Notes`) with actual evidence.
7. Move this plan file from `/hyperopen/docs/exec-plans/active/` to `/hyperopen/docs/exec-plans/completed/`.

## Validation and Acceptance

Acceptance criteria:

1. `/hyperopen/test/hyperopen/api/trading_test.cljs` is reduced to thin facade-level coverage.
2. Focused domain-oriented test namespaces exist under `/hyperopen/test/hyperopen/api/trading/`.
3. Shared helpers exist in `/hyperopen/test/hyperopen/api/trading/test_support.cljs` and reduce duplicated setup/parsing logic.
4. Large safe-integer boundary coverage exists for signing-critical fields touched in this suite.
5. Required gates pass:

   npm run check
   npm test
   npm run test:websocket

## Idempotence and Recovery

This refactor is additive and safe to repeat. If a split namespace fails, tests can be temporarily moved back into `/hyperopen/test/hyperopen/api/trading_test.cljs`, then re-split after restoring green results. Recovery is source-only; no migrations or destructive operations are required.

## Artifacts and Notes

Initial baseline evidence:

- `wc -l /hyperopen/test/hyperopen/api/trading_test.cljs` -> `793` lines.
- `rg "^\\(deftest " /hyperopen/test/hyperopen/api/trading_test.cljs | wc -l` -> `20` tests.

Post-refactor evidence:

- `wc -l /hyperopen/test/hyperopen/api/trading_test.cljs` -> `37` lines.
- `wc -l` new files:
  - `/hyperopen/test/hyperopen/api/trading/test_support.cljs` -> `37`
  - `/hyperopen/test/hyperopen/api/trading/cancel_request_test.cljs` -> `79`
  - `/hyperopen/test/hyperopen/api/trading/approve_agent_test.cljs` -> `47`
  - `/hyperopen/test/hyperopen/api/trading/sign_and_submit_test.cljs` -> `310`
  - `/hyperopen/test/hyperopen/api/trading/session_invalidation_test.cljs` -> `265`
  - `/hyperopen/test/hyperopen/api/trading/internal_seams_test.cljs` -> `104`
- `rg "^\\(deftest " /hyperopen/test/hyperopen/api/trading_test.cljs /hyperopen/test/hyperopen/api/trading/*.cljs | wc -l` -> `25` tests.
- `npm run check` -> pass.
- `npm test` -> pass (`1176 tests`, `5467 assertions`, `0 failures`).
- `npm run test:websocket` -> pass (`135 tests`, `587 assertions`, `0 failures`).

## Interfaces and Dependencies

No production interface changes are planned.

Test namespace dependencies in scope:

- `/hyperopen/src/hyperopen/api/trading.cljs`
- `/hyperopen/src/hyperopen/wallet/agent_session.cljs`
- `/hyperopen/src/hyperopen/utils/hl_signing.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`
- `/hyperopen/src/hyperopen/platform.cljs`

New test namespaces (target end state):

- `hyperopen.api.trading.test-support`
- `hyperopen.api.trading.cancel-request-test`
- `hyperopen.api.trading.approve-agent-test`
- `hyperopen.api.trading.sign-and-submit-test`
- `hyperopen.api.trading.session-invalidation-test`
- `hyperopen.api.trading.internal-seams-test`
- `hyperopen.api.trading-test` (thin facade)

Plan revision note: 2026-02-21 12:02Z - Initial plan created to execute a domain-oriented split of `trading_test.cljs`, add shared helpers, harden large-integer coverage, and run required validation gates.
Plan revision note: 2026-02-21 12:06Z - Completed implementation and validation: created support helpers, split API trading tests by concern, reduced top-level facade, added safe-integer boundary vectors, and recorded passing gate evidence.
