# Split State Trading Tests into Domain-Focused Namespaces

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

`/hyperopen/test/hyperopen/state/trading_test.cljs` previously mixed validation rules, scale math invariants, order-request signing shape, UI-normalization behavior, market-summary calculations, identity inference, and submit-policy gating in one 835-line namespace. That made targeted maintenance expensive for humans and coding agents because changes required loading broad unrelated context.

After this refactor, trading state tests are split into focused namespaces under `/hyperopen/test/hyperopen/state/trading/`, with shared fixtures/helpers extracted into one support namespace. The top-level trading state test file is now a thin facade slice. Behavior coverage is preserved and expanded for critical submit-policy branches.

A contributor can now open one file for one concern (for example `identity_and_submit_policy_test.cljs`), run required gates, and reason about changes with lower context load.

## Progress

- [x] (2026-02-21 11:24Z) Re-read `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md` requirements for ExecPlan structure and living sections.
- [x] (2026-02-21 11:24Z) Audited `/hyperopen/test/hyperopen/state/trading_test.cljs` and confirmed mixed concerns: 835 lines and 43 `deftest` forms.
- [x] (2026-02-21 11:24Z) Audited adapter/domain boundaries in `/hyperopen/src/hyperopen/state/trading.cljs`, `/hyperopen/src/hyperopen/domain/trading.cljs`, `/hyperopen/src/hyperopen/domain/trading/validation.cljs`, and `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`.
- [x] (2026-02-21 11:24Z) Confirmed explicit runner wiring in `/hyperopen/test/test_runner.cljs`; split implementation must update both `:require` and `run-tests` lists.
- [x] (2026-02-21 11:24Z) Authored this active ExecPlan before implementation.
- [x] (2026-02-21 11:29Z) Extracted shared trading-state test support into `/hyperopen/test/hyperopen/state/trading/test_support.cljs`.
- [x] (2026-02-21 11:29Z) Created focused trading-state test namespaces under `/hyperopen/test/hyperopen/state/trading/` and migrated existing tests by concern.
- [x] (2026-02-21 11:29Z) Reduced `/hyperopen/test/hyperopen/state/trading_test.cljs` to a thin facade/composition slice.
- [x] (2026-02-21 11:29Z) Strengthened validation assertions to use explicit error-code checks for size/price/TWAP behavior.
- [x] (2026-02-21 11:29Z) Added submit-policy branch coverage for `:spot-read-only`, `:market-price-missing`, `:request-unavailable`, and `:submitting`.
- [x] (2026-02-21 11:29Z) Updated `/hyperopen/test/test_runner.cljs` with all new namespaces in both `:require` and `run-tests` lists.
- [x] (2026-02-21 11:29Z) Ran required validation gates successfully: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-02-21 11:29Z) Updated this plan with final outcomes and moved it to completed.

## Surprises & Discoveries

- Observation: The default order form produced both size and price validation errors, not only size.
  Evidence: First `npm test` run failed at `hyperopen/state/trading/validation_and_scale_test.cljs` with actual codes `#{:order/size-invalid :order/price-required}` for default `:limit` form.

- Observation: Test topology refactor alone improved discoverability, but maintainability goals were better met by adding branch coverage during the split.
  Evidence: submit-policy coverage now includes previously missing branches (`:spot-read-only`, `:market-price-missing`, `:request-unavailable`, `:submitting`) in one focused file.

## Decision Log

- Decision: perform a behavior-preserving topology refactor first and avoid production code changes.
  Rationale: user request targeted test maintainability/reasoning; production behavior changes would add avoidable risk.
  Date/Author: 2026-02-21 / Codex

- Decision: keep a thin top-level `hyperopen.state.trading-test` namespace after the split.
  Rationale: preserves a historical entry seam and a small integration-level contract.
  Date/Author: 2026-02-21 / Codex

- Decision: strengthen weak validation assertions with explicit code checks, but use inclusion for default-form size requirement.
  Rationale: default `:limit` form legitimately returns both size and price errors; inclusion preserves semantic intent while remaining deterministic.
  Date/Author: 2026-02-21 / Codex

- Decision: add submit-policy missing-branch coverage in this same change.
  Rationale: branch gaps were a direct maintainability/reasoning risk identified in audit and were low-risk to add while reorganizing tests.
  Date/Author: 2026-02-21 / Codex

## Outcomes & Retrospective

Implementation completed as a behavior-preserving test-topology refactor with targeted coverage hardening.

Created shared support namespace:

- `/hyperopen/test/hyperopen/state/trading/test_support.cljs`

Created focused test namespaces:

- `/hyperopen/test/hyperopen/state/trading/validation_and_scale_test.cljs`
- `/hyperopen/test/hyperopen/state/trading/order_request_test.cljs`
- `/hyperopen/test/hyperopen/state/trading/order_form_state_test.cljs`
- `/hyperopen/test/hyperopen/state/trading/market_summary_test.cljs`
- `/hyperopen/test/hyperopen/state/trading/identity_and_submit_policy_test.cljs`

Reduced top-level facade file:

- `/hyperopen/test/hyperopen/state/trading_test.cljs` from 835 lines to 36 lines.

Net test coverage shape:

- before: 43 `deftest` forms in one namespace.
- after: 48 `deftest` forms across focused namespaces plus thin facade.
- added coverage: explicit validation-code assertions and four missing submit-policy branches.

Validation outcomes:

- `npm run check`: pass.
- `npm test`: pass (`Ran 1171 tests containing 5461 assertions. 0 failures, 0 errors.`).
- `npm run test:websocket`: pass (`Ran 135 tests containing 587 assertions. 0 failures, 0 errors.`).

Residual risk is low. The main ongoing risk is test-runner drift if future `hyperopen.state.trading.*` test namespaces are added without updating `/hyperopen/test/test_runner.cljs`.

## Context and Orientation

Production trading state behavior is exposed through `/hyperopen/src/hyperopen/state/trading.cljs`, which is primarily an adapter/facade over domain policy modules (`/hyperopen/src/hyperopen/domain/trading*.cljs`) and order command builders (`/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`).

This change reorganizes only tests and runner wiring. No production namespaces or public interfaces were modified.

Test runner wiring remains explicit in `/hyperopen/test/test_runner.cljs`. New namespaces were added to both `:require` and `run-tests` sections.

## Plan of Work

Milestone 1 extracted shared helper fixtures and utility assertions to a dedicated support namespace.

Milestone 2 split mixed concerns into focused test files aligned to ownership boundaries: validation/scale, order-request building, order-form state normalization, market-summary behavior, and identity/submit policy.

Milestone 3 reduced the historical top-level trading state test namespace into thin facade integration tests.

Milestone 4 strengthened assertion quality for previously weak spots (generic validation assertions and missing submit-policy branches).

Milestone 5 updated test runner wiring and executed required gates.

## Concrete Steps

1. Added `/hyperopen/test/hyperopen/state/trading/test_support.cljs` with `base-state`, `approx=`, `js-object-keys`, and `validation-codes`.
2. Added focused namespaces under `/hyperopen/test/hyperopen/state/trading/` and migrated tests by concern.
3. Rewrote `/hyperopen/test/hyperopen/state/trading_test.cljs` to two facade-level integration tests.
4. Updated `/hyperopen/test/test_runner.cljs` requires and `run-tests` entries for new namespaces.
5. Ran required commands from `/hyperopen`:

      npm run check
      npm test
      npm run test:websocket

6. Finalized this plan and moved it to completed.

## Validation and Acceptance

Acceptance criteria status:

1. `/hyperopen/test/hyperopen/state/trading_test.cljs` reduced to facade/integration scope: met.
2. Focused trading test files exist under `/hyperopen/test/hyperopen/state/trading/`: met.
3. Shared helper logic centralized in `/hyperopen/test/hyperopen/state/trading/test_support.cljs`: met.
4. Validation tests assert explicit codes for size/price/TWAP behavior: met.
5. Submit-policy coverage includes `:spot-read-only`, `:market-price-missing`, `:request-unavailable`, `:submitting`: met.
6. Runner wiring updated in `/hyperopen/test/test_runner.cljs`: met.
7. Required gates pass: met.

## Idempotence and Recovery

This refactor is additive and repeatable. If a newly split namespace fails in future edits, tests can be temporarily moved back to the facade namespace while fixing the focused file, then re-split once green. Recovery requires only source edits and rerunning gates; no destructive operations are needed.

## Artifacts and Notes

Line-count artifacts:

- `/hyperopen/test/hyperopen/state/trading_test.cljs`: 36 lines.
- `/hyperopen/test/hyperopen/state/trading/test_support.cljs`: 30 lines.
- `/hyperopen/test/hyperopen/state/trading/validation_and_scale_test.cljs`: 213 lines.
- `/hyperopen/test/hyperopen/state/trading/order_request_test.cljs`: 85 lines.
- `/hyperopen/test/hyperopen/state/trading/order_form_state_test.cljs`: 140 lines.
- `/hyperopen/test/hyperopen/state/trading/market_summary_test.cljs`: 284 lines.
- `/hyperopen/test/hyperopen/state/trading/identity_and_submit_policy_test.cljs`: 184 lines.

Validation command evidence:

- `npm run check` -> pass (`0 warnings`).
- `npm test` -> pass (`1171 tests`, `5461 assertions`, `0 failures`).
- `npm run test:websocket` -> pass (`135 tests`, `587 assertions`, `0 failures`).

## Interfaces and Dependencies

No production interface changes were made.

New/retained test namespaces:

- `hyperopen.state.trading.test-support`
- `hyperopen.state.trading.validation-and-scale-test`
- `hyperopen.state.trading.order-request-test`
- `hyperopen.state.trading.order-form-state-test`
- `hyperopen.state.trading.market-summary-test`
- `hyperopen.state.trading.identity-and-submit-policy-test`
- `hyperopen.state.trading-test` (thin facade)

Dependencies preserved:

- `cljs.test` semantics.
- explicit namespace registration in `/hyperopen/test/test_runner.cljs`.
- existing production boundaries in `/hyperopen/src/hyperopen/state/trading.cljs` and related domain/gateway modules.

Plan revision note: 2026-02-21 11:24Z - Initial plan created from `trading_test.cljs` maintainability audit with domain-aligned split, validation hardening, submit-policy branch coverage additions, and required validation gates.
Plan revision note: 2026-02-21 11:29Z - Completed implementation: extracted shared support, split monolithic state trading tests into focused namespaces, added missing validation/submit-policy coverage, updated test runner wiring, and passed required validation gates.
