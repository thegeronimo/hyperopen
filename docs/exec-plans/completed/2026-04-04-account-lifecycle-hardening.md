# Harden account lifecycle invariants after disconnected spectate reset

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/docs/PLANS.md` and `/hyperopen/.agents/PLANS.md`.

Active `bd` issue: `hyperopen-p3u9` (`Reset disconnected trade surfaces when stopping Spectate Mode`).

## Purpose / Big Picture

The disconnected spectate reset bug is fixed, but the codebase still needs stronger lifecycle guardrails so the same class of bug cannot return through a different state branch. After this change, the app should reject stale account-derived state whenever the effective account is `nil`, the watcher tests should prove effective-address transitions across connected, spectate, trader-route, and disconnected modes, and the runtime tests should verify repeated address transitions keep the nil-account store cleared. A developer should be able to see this working by running the new invariant and transition tests plus the existing browser regression.

## Progress

- [x] (2026-04-04 20:39Z) Audited the existing lifecycle seams in `/hyperopen/src/hyperopen/runtime/validation.cljs`, `/hyperopen/src/hyperopen/startup/runtime.cljs`, `/hyperopen/src/hyperopen/wallet/address_watcher.cljs`, and the related test suites to identify the lowest-friction invariant hook.
- [x] (2026-04-04 20:40Z) Created this active ExecPlan and froze the hardening scope around lifecycle invariants, transition-matrix coverage, and sequence/property-style tests.
- [x] (2026-04-04 21:31Z) Added `/hyperopen/src/hyperopen/account/lifecycle_invariants.cljs` and wired it into `/hyperopen/src/hyperopen/schema/contracts/assertions.cljs` so `assert-app-state!` rejects disconnected stale account surfaces with structured failure paths.
- [x] (2026-04-04 21:31Z) Added focused lifecycle fixtures and coverage in `/hyperopen/test/hyperopen/account/lifecycle_invariants_test.cljs`, `/hyperopen/test/hyperopen/account/lifecycle_transitions_test.cljs`, `/hyperopen/test/hyperopen/runtime/account_lifecycle_validation_test.cljs`, `/hyperopen/test/hyperopen/startup/account_lifecycle_test.cljs`, and `/hyperopen/test/hyperopen/wallet/address_watcher_lifecycle_test.cljs`.
- [x] (2026-04-04 21:31Z) Hardened the disconnected stop-spectate path itself by adding `:effects/clear-disconnected-account-lifecycle` plus `/hyperopen/src/hyperopen/startup/runtime.cljs` `clear-disconnected-account-state!`, so the no-account reset does not depend solely on downstream watcher timing.
- [x] (2026-04-04 21:31Z) Fixed `/hyperopen/src/hyperopen/startup/watchers.cljs` to synchronize already-connected websocket runtime state on install and added `/hyperopen/test/hyperopen/startup/watchers_test.cljs` coverage for that edge case.
- [x] (2026-04-04 21:31Z) Re-ran the targeted Playwright regression and the required repo gates, then updated this plan with the final evidence before moving it out of `/hyperopen/docs/exec-plans/active/`.

## Surprises & Discoveries

- Observation: the existing app-state contract in `/hyperopen/src/hyperopen/schema/contracts/state.cljs` is intentionally shallow and mostly checks shape, not semantic account lifecycle rules.
  Evidence: `::app-state` validates key presence and a few sub-shapes, but nothing today says “`effective-account-address` is `nil` implies account-derived surfaces are empty.”

- Observation: `/hyperopen/src/hyperopen/runtime/validation.cljs` already owns the store-watch seam for validation-enabled builds, so adding a second watcher or ad hoc debug-only check would duplicate the current validation entrypoint.
  Evidence: `install-store-state-validation!` asserts the app-state contract on bootstrap and every transition through one watch function.

- Observation: the watcher test suite already exercises special cases for spectate and custom watched values, and the repo already uses test.check sequence-style model tests elsewhere.
  Evidence: `/hyperopen/test/hyperopen/wallet/address_watcher_test.cljs` covers spectate toggles and custom watched values; `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs` demonstrates the preferred property-style `tc/quick-check` pattern.

- Observation: validation-only hardening was not enough to make the disconnected stop-spectate flow deterministic, because the action already knows when it is about to leave the app with no effective account.
  Evidence: the final implementation adds `:effects/clear-disconnected-account-lifecycle` in `/hyperopen/src/hyperopen/account/spectate_mode_actions.cljs`, routed through `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`, and the new action test proves that the disconnected clear is emitted immediately when no owner or trader-route account remains.

- Observation: websocket startup had a separate lifecycle blind spot where an already-connected runtime view could be installed without replaying the connected callback.
  Evidence: `/hyperopen/src/hyperopen/startup/watchers.cljs` now calls `on-websocket-connected!` immediately when the current runtime-view status is already `:connected`, and `/hyperopen/test/hyperopen/startup/watchers_test.cljs` locks that behavior down.

## Decision Log

- Decision: implement the new lifecycle rule as a pure invariant helper with explicit violation reporting, then call it from the existing runtime validation seam after the structural app-state contract check.
  Rationale: this keeps semantic lifecycle validation centralized and debuggable without forcing the entire rule into a spec predicate that would produce poorer failure diagnostics.
  Date/Author: 2026-04-04 / Codex

- Decision: keep the transition matrix centered on effective-account identity rather than duplicating full UI flows.
  Rationale: the bug class is about account identity changes and state ownership. The browser regression already covers the user-visible symptom, so the new hardening should focus on the lower-level lifecycle transitions.
  Date/Author: 2026-04-04 / Codex

- Decision: use one explicit matrix test and one sequence/property-style test instead of many near-duplicate scenario tests.
  Rationale: the matrix test documents the named transitions a maintainer cares about, while the generated sequence test gives better regression breadth for repeated address changes.
  Date/Author: 2026-04-04 / Codex

## Outcomes & Retrospective

Implementation landed. The final outcome is a stricter lifecycle contract and broader transition coverage around effective-account ownership. `/hyperopen/src/hyperopen/account/lifecycle_invariants.cljs` now defines the disconnected invariant, `/hyperopen/src/hyperopen/schema/contracts/assertions.cljs` enforces it through the existing app-state assertion seam, and the new lifecycle tests cover both named mode transitions and generated mixed-mode sequences. The disconnected stop-spectate path is also hardened directly through `:effects/clear-disconnected-account-lifecycle`, which keeps the no-account reset deterministic instead of relying only on downstream watcher propagation.

The work also uncovered and fixed a second lifecycle edge case in websocket startup. `/hyperopen/src/hyperopen/startup/watchers.cljs` now synchronizes already-connected runtime state at installation time, which removes one more source of state/view skew during startup.

Validation evidence on 2026-04-04:

- `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "disconnected stop spectate clears stale account surfaces"`
- `npm test`
- `npm run test:websocket`
- `npm run check`

## Context and Orientation

The effective account is the address that account-derived trade surfaces should represent. In this repository it is resolved by `/hyperopen/src/hyperopen/account/context.cljs` `effective-account-address`, which chooses, in order, the trader-portfolio route address, the active spectate address, then the connected owner wallet address. When no such address exists, the effective account is `nil`, which means the trade/account surfaces should show a true no-account state.

Address transitions are observed by `/hyperopen/src/hyperopen/wallet/address_watcher.cljs`. That module keeps a small watcher runtime with handlers and computes whether the watched value changed between two app-store states. Startup installs its handler in `/hyperopen/src/hyperopen/startup/runtime.cljs` `install-address-handlers!`. That handler decides whether to bootstrap account data for a real address or clear account-derived state when the effective address becomes `nil`.

Validation-enabled store watches are installed from `/hyperopen/src/hyperopen/runtime/validation.cljs`. Today that seam already checks the app-state structural contract by calling `/hyperopen/src/hyperopen/schema/contracts.cljs` `assert-app-state!`. The structural contract lives in `/hyperopen/src/hyperopen/schema/contracts/state.cljs`, but it currently validates shape, not lifecycle semantics.

The regression gap is that lifecycle semantics still rely mostly on ordinary tests and developer discipline. The bug fix added broad reset coverage in `/hyperopen/test/hyperopen/startup/runtime_test.cljs`, view placeholder coverage in `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` and `/hyperopen/test/hyperopen/views/account_equity_view_test.cljs`, and a browser regression in `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`. What is still missing is a canonical semantic assertion that stale account surfaces are illegal when `effective-account-address` is `nil`, plus broader transition coverage around connected/spectate/trader-route/disconnected changes.

## Plan of Work

First, add a small pure lifecycle invariant module under `/hyperopen/src/hyperopen/account/` that inspects only account lifecycle inputs and account-derived store branches. It should define the disconnected invariant in plain terms: when `effective-account-address` is `nil`, user-scoped trade/account branches such as `:webdata2`, open orders, open-order snapshots, fills, TWAP rows, spot clearinghouse state, perp clearinghouse state, and account-equity inputs must be empty or absent. The module should return structured violations so failures explain which branch is stale.

Next, wire that invariant into `/hyperopen/src/hyperopen/schema/contracts/assertions.cljs` `assert-app-state!` after the existing structural `::app-state` check passes. This keeps lifecycle validation inside the current app-state contract seam, and `/hyperopen/src/hyperopen/runtime/validation.cljs` will pick it up automatically in validation-enabled builds because it already calls `assert-app-state!` at bootstrap and on every store transition. Add focused tests proving that stale disconnected account surfaces now fail app-state validation while valid connected or cleared disconnected states still pass.

Then extend `/hyperopen/test/hyperopen/wallet/address_watcher_test.cljs` with a transition-matrix test that names the critical identity changes directly: connected -> spectate, spectate -> connected, spectate -> disconnected, connected -> disconnected, and trader-route -> disconnected. Each case should verify that the watcher treats the effective account as the watched value, not just the wallet address. Follow that with a sequence/property-style test using test.check, modeled after `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs`, that generates short mode sequences and proves the watcher notifies exactly when the effective account changes.

Finally, extend `/hyperopen/test/hyperopen/startup/runtime_test.cljs` with a repeated-address-transition test that drives the installed address handler through alternating real addresses and `nil`. Before each nil transition, seed stale account-derived branches into the store, then assert that the shared reset path restores the disconnected invariant. This keeps the startup/runtime layer honest under repeated transitions rather than a single stop-spectate scenario.

## Concrete Steps

Work from `/hyperopen`.

1. Create a pure lifecycle invariant helper namespace under `src/hyperopen/account/` and add assertion coverage in:

   `src/hyperopen/schema/contracts/assertions.cljs`

   `test/hyperopen/schema/contracts/state_test.cljs`

   `test/hyperopen/runtime/validation_test.cljs`

2. Add watcher transition hardening in:

   `test/hyperopen/wallet/address_watcher_test.cljs`

3. Add repeated-transition startup/runtime coverage in:

   `test/hyperopen/startup/runtime_test.cljs`

4. Re-run the existing disconnected stop-spectate browser regression after the lifecycle work to confirm the user-visible fix remains intact:

   `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "disconnected stop spectate clears stale account surfaces"`

5. Run the required repo gates:

   `npm test`

   `npm run test:websocket`

   `npm run check`

## Validation and Acceptance

Acceptance requires three kinds of proof.

At the invariant level, a disconnected state that still carries stale account-derived data must now fail `assert-app-state!`, and the same cleared disconnected state used by the current fix must pass.

At the transition level, watcher tests must prove the effective-account identity changes for the named matrix of modes, and the sequence/property test must prove that repeated mixed-mode transitions notify only when the effective account actually changes.

At the startup/runtime level, a repeated sequence of alternating addresses and `nil` must show that every nil transition restores the cleared disconnected invariant even after stale account data is re-seeded between transitions. The existing Playwright stop-spectate regression must still pass afterward so the user-visible behavior remains protected.

Validation result: satisfied on 2026-04-04. The invariant is covered directly in `/hyperopen/test/hyperopen/account/lifecycle_invariants_test.cljs` and `/hyperopen/test/hyperopen/schema/contracts/assertions_test.cljs`; runtime validation behavior is covered in `/hyperopen/test/hyperopen/runtime/account_lifecycle_validation_test.cljs`; transition breadth is covered in `/hyperopen/test/hyperopen/account/lifecycle_transitions_test.cljs`, `/hyperopen/test/hyperopen/startup/account_lifecycle_test.cljs`, and `/hyperopen/test/hyperopen/wallet/address_watcher_lifecycle_test.cljs`; and the user-visible symptom remains protected by the existing Playwright regression in `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`.

## Idempotence and Recovery

These changes are source-only and test-only. Re-running the tests is safe. The lifecycle invariant must be written as a pure function of the store state so repeated calls do not mutate anything. If a new invariant is too strict and starts failing existing valid disconnected states, relax the invariant by allowing the default empty branch shape used in `/hyperopen/src/hyperopen/state/app_defaults.cljs` rather than weakening the startup reset behavior itself.

## Artifacts and Notes

Useful reference points during implementation:

`/hyperopen/src/hyperopen/account/context.cljs`

  Defines the canonical `effective-account-address` priority order.

`/hyperopen/src/hyperopen/runtime/validation.cljs`

  Already owns the validation-enabled store watch and is the natural hook for lifecycle assertions via `assert-app-state!`.

`/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs`

  Shows the established local test.check pattern for sequence/property-style state-machine checks.

`/hyperopen/test/hyperopen/startup/runtime_test.cljs`

  Already contains the nil-address reset coverage that should be generalized into repeated transition assertions.

## Interfaces and Dependencies

The new invariant helper should stay pure and accept the full app-state map. It should expose a boolean-friendly surface plus structured violations that can be turned into a useful thrown error from the assertion layer.

No new external dependencies are needed. Reuse the existing `clojure.test.check` setup already present in the repo for the sequence/property test, and keep the lifecycle assertion routed through the existing `contracts/assert-app-state!` surface so validation-enabled runtime behavior stays centralized.

Plan revision note (2026-04-04 20:40Z): Created as a follow-up hardening plan after the disconnected spectate reset fix landed and passed its initial gates. The new scope is prevention: semantic lifecycle invariants plus broader transition coverage for effective-account changes.

Plan revision note (2026-04-04 21:31Z): Expanded the implementation beyond validation-only hardening to include an explicit disconnected clear effect from `stop-spectate-mode` and an already-connected websocket startup sync fix, then completed the required validation gates before moving this plan to `/hyperopen/docs/exec-plans/completed/`.
