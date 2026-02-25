# Legacy Order-Form Key Policy (Explicit Support/Deprecation Boundary)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, order-form key handling will have one explicit policy that defines where legacy keys are still supported, where they are deprecated, and where they are forbidden. Contributors will no longer need to infer policy from scattered key sets and ad hoc stripping logic across state, transitions, and schema validation.

A contributor can verify the result by running tests that prove legacy keys are only tolerated at the compatibility read boundary, canonical persisted `:order-form` state rejects them, and transition update paths cannot write them.

## Progress

- [x] (2026-02-25 21:36Z) Reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/ARCHITECTURE.md`, and `/hyperopen/docs/RELIABILITY.md` for boundary and compatibility-policy requirements.
- [x] (2026-02-25 21:36Z) Audited key policy duplication and mixed behavior in `/hyperopen/src/hyperopen/state/trading.cljs`, `/hyperopen/src/hyperopen/schema/contracts.cljs`, and `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`.
- [x] (2026-02-25 21:36Z) Audited current tests for order-form key ownership and legacy behavior in `/hyperopen/test/hyperopen/state/trading/order_form_state_test.cljs`, `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs`, and `/hyperopen/test/hyperopen/schema/contracts_test.cljs`.
- [x] (2026-02-25 21:36Z) Reviewed prior order-form architectural slices in `/hyperopen/docs/exec-plans/completed/2026-02-15-order-form-solid-ddd-slice-7.md` and `/hyperopen/docs/exec-plans/completed/2026-02-15-order-form-solid-ddd-slice-9.md`.
- [x] (2026-02-25 21:36Z) Authored initial ExecPlan with explicit support/deprecation boundary design, migration milestones, and required gates.
- [x] (2026-02-25 21:44Z) Implemented Milestone 1 by adding canonical policy module `/hyperopen/src/hyperopen/state/trading/order_form_key_policy.cljs` and drift guards in `/hyperopen/test/hyperopen/state/trading/order_form_key_policy_test.cljs`.
- [x] (2026-02-25 21:47Z) Implemented Milestone 2 by migrating `/hyperopen/src/hyperopen/state/trading.cljs` normalization, persistence, and UI override boundaries to policy ownership.
- [x] (2026-02-25 21:49Z) Implemented Milestone 3 by migrating `/hyperopen/src/hyperopen/schema/contracts.cljs` and `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs` to shared policy predicates/path guards and updating related tests.
- [x] (2026-02-25 21:53Z) Implemented Milestone 4 by updating `/hyperopen/docs/RELIABILITY.md`, adding ADR `/hyperopen/docs/architecture-decision-records/0021-legacy-order-form-key-policy-boundary.md`, and passing required gates (`npm run check`, `npm test`, `npm run test:websocket`).

## Surprises & Discoveries

- Observation: legacy key classification is currently duplicated in multiple namespaces with no single owner.
  Evidence: `legacy-order-form-ui-flag-keys` and `legacy-order-form-runtime-keys` in `/hyperopen/src/hyperopen/state/trading.cljs`, plus `legacy-ui-and-runtime-order-form-keys` in `/hyperopen/src/hyperopen/schema/contracts.cljs`.

- Observation: policy behavior is mixed and implicit: compatibility reads silently strip legacy keys, while app-state contracts reject those same keys in persisted `:order-form`.
  Evidence: `/hyperopen/src/hyperopen/state/trading.cljs` `normalize-order-form` strips legacy keys, while `/hyperopen/src/hyperopen/schema/contracts.cljs` `::order-form-state` forbids them.

- Observation: transition write-path protection repeats a hand-maintained key list that includes both UI-owned and legacy keys.
  Evidence: `ui-only-form-paths` in `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`.

- Observation: tests already assert “no legacy fallback” in UI state, but this boundary is not represented as a named policy contract.
  Evidence: `/hyperopen/test/hyperopen/state/trading/order_form_state_test.cljs` `order-form-ui-state-defaults-without-legacy-fallback-test`.

- Observation: order-form compatibility intent was previously documented as temporary, but no formal deprecation boundary artifact exists yet.
  Evidence: `/hyperopen/docs/exec-plans/completed/2026-02-15-order-form-solid-ddd-slice-7.md` notes compatibility for transitional callers.

- Observation: canonical persistence previously stripped only UI-owned keys; legacy runtime/UI compatibility keys could pass through this boundary unless earlier normalization removed them.
  Evidence: pre-migration `/hyperopen/src/hyperopen/state/trading.cljs` `persist-order-form` only removed `ui-owned-order-form-keys`.

- Observation: policy-driven blocked path sets were directly reusable in transition tests, eliminating duplicate path fixtures.
  Evidence: `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs` now iterates `canonical-write-blocked-order-form-paths` and passed under `npm test`.

## Decision Log

- Decision: Introduce one canonical order-form key policy namespace to classify canonical keys, compatibility keys, and deprecated legacy keys.
  Rationale: A single source of truth prevents drift and makes support/deprecation boundaries explicit.
  Date/Author: 2026-02-25 / Codex

- Decision: Keep legacy-key support only at compatibility read boundaries (`order-form` ingress normalization), not at persisted canonical state boundaries.
  Rationale: This preserves backward compatibility for transitional callers while enforcing clean canonical state ownership.
  Date/Author: 2026-02-25 / Codex

- Decision: Preserve strict app-state contract rejection for deprecated keys in `:order-form`, but derive those checks from the shared policy module.
  Rationale: Existing strictness is correct; ownership of the rule is currently fragmented.
  Date/Author: 2026-02-25 / Codex

- Decision: Transition update-path blocking for UI-owned/runtime/legacy form keys must also derive from the same policy module.
  Rationale: Write-path ownership and persisted-state ownership should be governed by one contract vocabulary.
  Date/Author: 2026-02-25 / Codex

- Decision: Add architecture documentation for this policy and deprecation boundary in RELIABILITY plus ADR 0021.
  Rationale: Compatibility/deprecation policy is architectural governance and should not live only in code comments.
  Date/Author: 2026-02-25 / Codex

- Decision: Make `persist-order-form` strip all policy-defined deprecated canonical keys (UI-owned plus legacy compatibility keys), not only UI-owned keys.
  Rationale: Canonical write projections must remain clean even when transitional callers bypass normalization call paths.
  Date/Author: 2026-02-25 / Codex

## Outcomes & Retrospective

Implemented the full migration to one explicit legacy order-form key policy with shared ownership across state normalization, persistence, schema contracts, and transition write guards.

Delivered outcomes:

- Added canonical policy module `/hyperopen/src/hyperopen/state/trading/order_form_key_policy.cljs` defining UI-owned keys, legacy compatibility keys, deprecated canonical keys, and blocked transition paths.
- Removed duplicated key ownership lists from `/hyperopen/src/hyperopen/state/trading.cljs`, `/hyperopen/src/hyperopen/schema/contracts.cljs`, and `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`.
- Added and updated tests to enforce drift and boundary behavior:
  - new `/hyperopen/test/hyperopen/state/trading/order_form_key_policy_test.cljs`
  - updated state/schema/transition tests for policy-derived rejection and stripping behavior.
- Added reliability governance section and ADR 0021 for support/deprecation contract ownership.
- Required gates passed: `npm run check`, `npm test`, `npm run test:websocket`.

## Context and Orientation

Order-form state in this repository is intentionally split into three maps:

- `:order-form` for canonical domain draft fields.
- `:order-form-ui` for UI-owned presentation and interaction fields.
- `:order-form-runtime` for runtime workflow flags like submit state/error.

Today, key ownership rules are enforced in several places:

- `/hyperopen/src/hyperopen/state/trading.cljs` strips legacy keys and UI/runtime leakage during normalization and persistence.
- `/hyperopen/src/hyperopen/schema/contracts.cljs` rejects UI-owned and legacy keys inside persisted `:order-form`.
- `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs` blocks updates to UI/runtime/legacy paths through `update-order-form`.

For this plan:

- "Compatibility read boundary" means selector/normalization entrypoints that may accept old shape keys from transitional callers and sanitize them.
- "Canonical write boundary" means any persisted `:order-form` projection and app-state contract where deprecated keys are forbidden.
- "Legacy order-form keys" means historical UI/runtime keys embedded in `:order-form`, including dropdown/focus panel flags and runtime submit/error keys.

## Plan of Work

### Milestone 1: Create Canonical Key Policy Module And Drift Tests

Add a new pure policy namespace (for example `/hyperopen/src/hyperopen/state/trading/order_form_key_policy.cljs`) that defines:

- UI-owned form keys.
- Legacy compatibility keys.
- Deprecated-for-canonical-state keys.
- Helper predicates/functions for transition path blocking and canonical persistence stripping.

Add focused tests that fail when key sets drift across boundaries. The tests should prove uniqueness, intended membership, and path mapping behavior.

### Milestone 2: Move State/Trading Compatibility And Persistence To Policy Ownership

Refactor `/hyperopen/src/hyperopen/state/trading.cljs` to remove local duplicated key sets and use policy helpers for:

- compatibility read sanitization in normalization paths,
- canonical persistence stripping (`persist-order-form`),
- UI override extraction boundaries.

Make compatibility support explicit in docstrings/comments at boundary functions so behavior is intentional and not accidental.

### Milestone 3: Move Schema + Transition Enforcement To Policy Ownership

Refactor `/hyperopen/src/hyperopen/schema/contracts.cljs` and `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs` to consume the shared key policy.

Update tests to enforce full coverage:

- app-state contract rejects deprecated keys in canonical `:order-form`,
- transition `update-order-form` rejects writes to policy-defined UI/runtime/legacy paths,
- compatibility reads still tolerate transitional input shapes where intended.

### Milestone 4: Codify Deprecation Boundary In Docs And Validate

Update `/hyperopen/docs/RELIABILITY.md` with an explicit "order-form legacy key policy" section describing support and deprecation boundaries. Add ADR `/hyperopen/docs/architecture-decision-records/0021-legacy-order-form-key-policy-boundary.md` documenting why support remains at read ingress and where deprecation is enforced.

Run required validation gates and capture evidence in this plan.

## Concrete Steps

From `/hyperopen`:

1. Add policy module and failing drift tests.

   - Create `/hyperopen/src/hyperopen/state/trading/order_form_key_policy.cljs`.
   - Add tests in a new policy test namespace plus updates to contracts/transitions tests.
   - Run:
     - `npm test`

   Expected outcome: new drift tests fail until state/trading, schema, and transitions share policy ownership.

2. Migrate state/trading boundaries.

   - Edit `/hyperopen/src/hyperopen/state/trading.cljs` to replace local key sets with policy helpers.
   - Keep selector and submit behavior unchanged.
   - Run:
     - `npm test`

   Expected outcome: existing order-form behavior stays stable while compatibility boundary ownership becomes explicit.

3. Migrate schema and transitions boundaries.

   - Edit `/hyperopen/src/hyperopen/schema/contracts.cljs` and `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs` to consume policy module keys/path helpers.
   - Run:
     - `npm test`
     - `npm run test:websocket`

   Expected outcome: canonical-state rejection and transition path blocking are policy-driven and drift-safe.

4. Finalize docs and required gates.

   - Update `/hyperopen/docs/RELIABILITY.md`.
   - Add ADR `0021` for legacy order-form key policy boundary.
   - Run required gates:
     - `npm run check`
     - `npm test`
     - `npm run test:websocket`

   Expected outcome: all gates pass and deprecation/support boundaries are explicitly documented.

## Validation and Acceptance

Acceptance is complete when all conditions below are true.

1. A single policy namespace defines legacy/UI-owned order-form key classification and path ownership.
2. `state/trading`, schema contracts, and transitions all derive key ownership rules from that policy module.
3. Legacy key support is explicit and limited to compatibility read boundaries; canonical persisted `:order-form` state rejects deprecated keys.
4. Transition update paths reject policy-defined UI/runtime/legacy keys deterministically.
5. Order-form selectors and submit behavior remain functionally equivalent for existing callers.
6. Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

Implement in additive slices:

- add policy and tests first,
- migrate one boundary at a time,
- remove duplicated key constants only after shared-policy tests pass.

If regressions appear, keep the new policy module and tests, restore the prior boundary wiring in the failing namespace, and reapply migration in smaller edits. Avoid broad simultaneous rewrites across state, schema, and transitions.

## Artifacts and Notes

Primary files expected to change:

- `/hyperopen/src/hyperopen/state/trading/order_form_key_policy.cljs` (new)
- `/hyperopen/src/hyperopen/state/trading.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`
- `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`
- `/hyperopen/docs/RELIABILITY.md`
- `/hyperopen/docs/architecture-decision-records/0021-legacy-order-form-key-policy-boundary.md` (new)

Primary tests expected to change:

- `/hyperopen/test/hyperopen/state/trading/order_form_state_test.cljs`
- `/hyperopen/test/hyperopen/state/trading_test.cljs`
- `/hyperopen/test/hyperopen/schema/contracts_test.cljs`
- `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs`
- `/hyperopen/test/hyperopen/state/trading/order_form_key_policy_test.cljs` (new)

Evidence to capture during implementation:

- Before/after drift test evidence for duplicated key-set mismatch.
- Before/after evidence for transition path rejection driven by policy module.
- Required gate outputs.

Validation evidence captured:

    npm run check
    ...
    [:app] Build completed. (... 0 warnings ...)
    [:test] Build completed. (... 0 warnings ...)

    npm test
    ...
    Ran 1361 tests containing 6655 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    ...
    Ran 148 tests containing 644 assertions.
    0 failures, 0 errors.

## Interfaces and Dependencies

Interfaces to preserve:

- `hyperopen.state.trading` public selectors and submit/policy functions (`order-form-draft`, `order-form-ui-state`, `submit-policy`, `persist-order-form` behavior).
- Existing transition and action IDs for order-form interactions.

Interfaces to add:

- A pure order-form key policy API that exposes key sets and path ownership predicates for compatibility and canonical boundaries.

Dependency direction constraints:

- Key policy module should remain pure and dependency-light.
- State/trading, schema/contracts, and transitions may depend on the policy module.
- Avoid introducing cycles between schema and state modules.

No new external dependencies are required.

Plan revision note: 2026-02-25 21:36Z - Initial ExecPlan created for explicit legacy order-form key support/deprecation boundaries with shared policy ownership across state, schema, and transitions.
Plan revision note: 2026-02-25 21:53Z - Completed milestones 1-4 by adding shared policy module/tests, migrating state+schema+transitions to policy ownership, documenting reliability/ADR boundaries, and capturing successful required gate outputs.
