# Locale Input Parsing for Low-Traffic Integer Boundaries

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and must be maintained in accordance with that file.

## Purpose / Big Picture

After locale-aware decimal parsing rollout, a few lower-traffic user-entered integer paths still relied on plain integer parsing. This tranche hardens those paths so localized numeric text is interpreted consistently in account-history pagination and chart indicator period inputs.

A user can verify this by setting `[:ui :locale]` to `"fr-FR"`, entering localized integer/grouped values in those controls, and observing deterministic numeric behavior.

## Progress

- [x] (2026-03-02 21:34Z) Audited remaining user-input parsing boundaries and selected integer parsing targets in account-history pagination and chart indicator period actions.
- [x] (2026-03-02 21:36Z) Added shared helper `parse-localized-int-value` in `/hyperopen/src/hyperopen/utils/parse.cljs`.
- [x] (2026-03-02 21:38Z) Migrated account-history pagination normalization paths in `/hyperopen/src/hyperopen/account/history/actions.cljs` to locale-aware integer parsing using `[:ui :locale]`.
- [x] (2026-03-02 21:39Z) Migrated chart indicator period parsing in `/hyperopen/src/hyperopen/chart/settings.cljs` to locale-aware integer parsing.
- [x] (2026-03-02 21:40Z) Added regression tests in:
  - `/hyperopen/test/hyperopen/utils/parse_test.cljs`
  - `/hyperopen/test/hyperopen/account/history/actions_test.cljs`
  - `/hyperopen/test/hyperopen/chart/settings_test.cljs`
- [x] (2026-03-02 21:41Z) Added explicit policy guardrails in `/hyperopen/docs/agent-guides/trading-ui-policy.md` to require locale-aware parse utilities for user numeric input boundaries.
- [x] (2026-03-02 21:44Z) Ran required validation gates successfully (`npm test`, `npm run check`, `npm run test:websocket`).

## Surprises & Discoveries

- Observation: Account-history pagination already had pure normalization helpers, so adding locale support only required threading locale at action boundaries, not UI rework.
  Evidence: Existing `normalize-order-history-page-size` and `normalize-order-history-page` accepted additive arity extension without behavior regressions in existing tests.

## Decision Log

- Decision: Keep integer parsing compatibility behavior by flooring parsed numeric values instead of rejecting decimal-like text outright.
  Rationale: Existing `parse-int-value` behavior floors numeric values; locale support should preserve user-visible behavior while adding localized input acceptance.
  Date/Author: 2026-03-02 / Codex

## Outcomes & Retrospective

This tranche closes identified low-traffic integer parsing gaps and extends policy guardrails for future numeric input work. Locale-aware parsing now covers both high-impact decimal paths and audited integer input boundaries.

## Context and Orientation

Files touched:

- `/hyperopen/src/hyperopen/utils/parse.cljs`
- `/hyperopen/src/hyperopen/account/history/actions.cljs`
- `/hyperopen/src/hyperopen/chart/settings.cljs`
- `/hyperopen/test/hyperopen/utils/parse_test.cljs`
- `/hyperopen/test/hyperopen/account/history/actions_test.cljs`
- `/hyperopen/test/hyperopen/chart/settings_test.cljs`
- `/hyperopen/docs/agent-guides/trading-ui-policy.md`

## Plan of Work

Add a shared locale-aware integer parser. Use it in remaining user-entered integer boundaries. Add focused tests and update policy to prevent regressions in future input features.

## Concrete Steps

From `/hyperopen`:

1. Add `parse-localized-int-value` to parse utilities.
2. Route account-history page normalization through locale-aware integer parse.
3. Route chart indicator period updates through locale-aware integer parse.
4. Add targeted tests.
5. Run required gates:
   - `npm test`
   - `npm run check`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance criteria:

1. Account-history pagination apply/set paths parse localized integer text deterministically.
2. Chart indicator period updates parse localized integer text and retain fallback behavior for invalid input.
3. Shared parse utility has direct regression tests for localized integer parsing.
4. Required repository validation gates pass.

## Idempotence and Recovery

Changes are additive and idempotent. If regressions appear, recovery is to keep helper-level parser tests and narrow boundary migrations one target at a time.

## Artifacts and Notes

Validation commands run:

    npm test
    npm run check
    npm run test:websocket

## Interfaces and Dependencies

No external dependencies were added. This work extends existing parse utilities and reuses established action contracts.
