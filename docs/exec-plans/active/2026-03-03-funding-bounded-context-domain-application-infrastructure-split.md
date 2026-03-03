# Funding Bounded-Context Split (Domain/Application/Infrastructure)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this refactor, users should see the same funding modal behavior for deposit, transfer, and withdraw flows, including HyperUnit lifecycle polling and status messaging, but the implementation will be separated into explicit domain, application, and infrastructure modules. Today, `/hyperopen/src/hyperopen/funding/actions.cljs` and `/hyperopen/src/hyperopen/funding/effects.cljs` mix normalization policy, view-model shaping, runtime orchestration, wallet RPC, and network protocol calls in the same files.

The change will make the funding surface safer to extend: domain rules become pure and deterministic, application modules own orchestration/effect ordering, and infrastructure modules own input/output with external systems (wallet provider, HyperUnit, LiFi, Across). A contributor can verify success by running the funding-focused tests and required gates and confirming no runtime action/effect contract IDs or funding modal call sites changed.

## Progress

- [x] (2026-03-03 20:41Z) Re-read planning and governance requirements in `/hyperopen/AGENTS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/.agents/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`.
- [x] (2026-03-03 20:41Z) Audited funding hotspots and seams in `/hyperopen/src/hyperopen/funding/actions.cljs` (1503 LOC), `/hyperopen/src/hyperopen/funding/effects.cljs` (1957 LOC), `/hyperopen/src/hyperopen/runtime/collaborators.cljs`, and `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`.
- [x] (2026-03-03 20:42Z) Created and claimed tracking issue `hyperopen-8sn` in `bd` for this refactor.
- [x] (2026-03-03 20:44Z) Authored this active ExecPlan.
- [x] (2026-03-03 21:08Z) Milestone 1 baseline completed by running compile/test gates before and after extraction; existing tests remained green.
- [x] (2026-03-03 21:08Z) Milestone 2 partial completion: introduced `/hyperopen/src/hyperopen/funding/domain/lifecycle.cljs` as domain ownership for lifecycle/fee/queue normalization and converted `/hyperopen/src/hyperopen/funding/actions.cljs` into a compatibility facade backed by `/hyperopen/src/hyperopen/funding/application/modal_actions.cljs`.
- [x] (2026-03-03 21:08Z) Milestone 4 partial completion: extracted HyperUnit transport/fallback request concerns to `/hyperopen/src/hyperopen/funding/infrastructure/hyperunit_client.cljs` and updated `/hyperopen/src/hyperopen/funding/effects.cljs` to consume domain/infrastructure modules.
- [x] (2026-03-03 21:08Z) Milestone 5 validation completed for current slice: `npx shadow-cljs compile app`, `npx shadow-cljs compile test`, `npm run check`, `npm test`, and `npm run test:websocket` all passed.
- [x] (2026-03-03 21:01Z) Milestone 2 follow-up: removed duplicated lifecycle/fee/withdraw-queue normalization logic from `/hyperopen/src/hyperopen/funding/application/modal_actions.cljs` and delegated to `/hyperopen/src/hyperopen/funding/domain/lifecycle.cljs` through compatibility aliases.
- [x] (2026-03-03 21:06Z) Milestone 4 follow-up: extracted LiFi/Across route request and payload parsing concerns into `/hyperopen/src/hyperopen/funding/infrastructure/route_clients.cljs`, and rewired `/hyperopen/src/hyperopen/funding/effects.cljs` through compatibility wrappers.
- [x] (2026-03-03 21:06Z) Milestone 5 validation completed for route-client slice: `npm run check`, `npm test`, and `npm run test:websocket` all passed after extraction.
- [x] (2026-03-03 21:09Z) Milestone 4 follow-up: extracted wallet RPC chain-switch, provider request, receipt polling, and transaction-send helpers into `/hyperopen/src/hyperopen/funding/infrastructure/wallet_rpc.cljs`, with `/hyperopen/src/hyperopen/funding/effects.cljs` retaining private compatibility aliases.
- [x] (2026-03-03 21:09Z) Milestone 5 validation completed for wallet-RPC slice: `npm run check`, `npm test`, and `npm run test:websocket` all passed after extraction.
- [x] (2026-03-03 21:13Z) Milestone 4 follow-up: moved funding submit orchestration entrypoints to `/hyperopen/src/hyperopen/funding/application/submit_effects.cljs`, with `/hyperopen/src/hyperopen/funding/effects.cljs` now acting as a compatibility wrapper that injects defaults and existing seams.
- [x] (2026-03-03 21:13Z) Milestone 5 validation completed for submit-orchestration slice: `npm run check`, `npm test`, and `npm run test:websocket` all passed after extraction.
- [x] (2026-03-03 21:16Z) Milestone 4 follow-up: moved HyperUnit lifecycle polling orchestration into `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`, while `/hyperopen/src/hyperopen/funding/effects.cljs` retains wrapper seams and existing helper ownership.
- [x] (2026-03-03 21:16Z) Milestone 5 validation completed for lifecycle-polling slice: `npm run check`, `npm test`, and `npm run test:websocket` all passed after extraction.
- [x] (2026-03-03 21:19Z) Milestone 3 follow-up: moved `funding-modal-view-model` implementation to `/hyperopen/src/hyperopen/funding/application/modal_vm.cljs`; `/hyperopen/src/hyperopen/funding/application/modal_actions.cljs` now exposes a compatibility wrapper that injects existing helper seams.
- [x] (2026-03-03 21:19Z) Milestone 5 validation completed for modal-VM slice: `npm run check`, `npm test`, and `npm run test:websocket` all passed after extraction.
- [x] (2026-03-03 21:29Z) Milestone 3 follow-up: moved modal command orchestration into `/hyperopen/src/hyperopen/funding/application/modal_commands.cljs` and converted modal action entrypoints to compatibility wrappers in `/hyperopen/src/hyperopen/funding/application/modal_actions.cljs`.
- [x] (2026-03-03 21:29Z) Milestone 4 follow-up: extracted HyperUnit address request/fallback client concerns into `/hyperopen/src/hyperopen/funding/infrastructure/hyperunit_address_client.cljs` and rewired `/hyperopen/src/hyperopen/funding/effects.cljs` through compatibility wrappers.
- [x] (2026-03-03 21:29Z) Milestone 5 validation completed for command+HyperUnit address slices: `npm run check`, `npm test`, and `npm run test:websocket` all passed after extraction.
- [x] (2026-03-03 21:32Z) Milestone 4 follow-up: extracted ERC20 encoding/balance/allowance RPC helpers into `/hyperopen/src/hyperopen/funding/infrastructure/erc20_rpc.cljs` and rewired `/hyperopen/src/hyperopen/funding/effects.cljs` through compatibility aliases/wrappers.
- [x] (2026-03-03 21:32Z) Milestone 5 validation completed for ERC20 RPC slice: `npm run check`, `npm test`, and `npm run test:websocket` all passed after extraction.
- [ ] Milestone 3 remaining: split `funding-modal-view-model` and modal command orchestration into dedicated application modules to reduce `/hyperopen/src/hyperopen/funding/application/modal_actions.cljs` size.
- [ ] Milestone 4 remaining: split submit/lifecycle polling orchestration from `/hyperopen/src/hyperopen/funding/effects.cljs` into explicit funding application modules while preserving current test seams.
- [ ] Milestone 6: Land tracking and governance updates (`bd`, optional ADR) and complete handoff.

## Surprises & Discoveries

- Observation: The most recent HyperUnit parity audit plan from 2026-03-02 recorded missing lifecycle polling, but current implementation already includes polling, queue refresh, and lifecycle transitions.
  Evidence: `/hyperopen/src/hyperopen/funding/effects.cljs` functions `start-hyperunit-lifecycle-polling!`, `fetch-hyperunit-withdrawal-queue!`, `operation->lifecycle`, and submit handlers around `api-submit-funding-withdraw!`/`api-submit-funding-deposit!`.

- Observation: `funding-actions` currently defines normalization helpers that are directly reused by `funding-effects`, creating cross-layer coupling and making boundary extraction non-trivial.
  Evidence: `/hyperopen/src/hyperopen/funding/effects.cljs` calls `funding-actions/normalize-hyperunit-lifecycle`, `funding-actions/default-hyperunit-lifecycle-state`, `funding-actions/normalize-hyperunit-fee-estimate`, and `funding-actions/normalize-hyperunit-withdrawal-queue`.

- Observation: Funding effects tests currently reach into several private vars in `/hyperopen/src/hyperopen/funding/effects.cljs`, which creates tight coupling to file-local implementation details.
  Evidence: `/hyperopen/test/hyperopen/funding/effects_test.cljs` references vars like `@#'hyperopen.funding.effects/select-existing-hyperunit-deposit-address` and `@#'hyperopen.funding.effects/with-hyperunit-base-url-fallbacks!`.

- Observation: Runtime action/effect wiring depends on stable public function names in `funding.actions` and `funding.effects`.
  Evidence: `/hyperopen/src/hyperopen/runtime/collaborators.cljs` binds funding action/effect handlers by var reference, and `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs` locks runtime IDs that must not change.

- Observation: `npm test -- <namespace>` is not a supported filter path in this repository test harness; passing a namespace argument still executes the full suite.
  Evidence: test runner output includes `Unknown arg: hyperopen.funding.actions-test` before executing all test namespaces.

## Decision Log

- Decision: Preserve public namespace surfaces of `/hyperopen/src/hyperopen/funding/actions.cljs` and `/hyperopen/src/hyperopen/funding/effects.cljs` as compatibility facades during and after extraction.
  Rationale: Runtime collaborators, app defaults, and tests already import these namespaces directly; compatibility avoids broad call-site churn.
  Date/Author: 2026-03-03 / Codex

- Decision: Extract in two layers per bounded context: domain first (pure rules/state), then application orchestration, then infrastructure input/output.
  Rationale: This sequencing reduces risk by keeping behavior locked with tests before moving side-effecting code.
  Date/Author: 2026-03-03 / Codex

- Decision: Keep runtime action/effect IDs and effect-order policy unchanged.
  Rationale: Refactor scope is architectural decomposition, not contract evolution; changing IDs would create unrelated runtime risk.
  Date/Author: 2026-03-03 / Codex

- Decision: Track work in `bd` issue `hyperopen-8sn` and treat this ExecPlan as implementation guidance, not issue status.
  Rationale: `/hyperopen/docs/WORK_TRACKING.md` requires `bd` as the issue lifecycle source of truth.
  Date/Author: 2026-03-03 / Codex

- Decision: Add an ADR only if final module boundaries introduce permanent new architectural ownership rules beyond funding internals.
  Rationale: Internal extraction behind stable facades may not require an ADR, but permanent layer ownership changes might.
  Date/Author: 2026-03-03 / Codex

## Outcomes & Retrospective

Implemented first execution slice with behavior-preserving boundary extraction:

- `funding.actions` is now a compatibility facade.
- Funding modal implementation moved to `funding.application.modal-actions`.
- Lifecycle normalization ownership extracted to `funding.domain.lifecycle`.
- HyperUnit transport fallback/request ownership extracted to `funding.infrastructure.hyperunit-client`.
- `funding.effects` now depends on explicit domain and infrastructure modules for those concerns.
- `funding.application.modal-actions` no longer carries its own duplicated lifecycle normalization implementation and now consumes lifecycle normalization from `funding.domain.lifecycle`.
- `funding.effects` now delegates route-provider infrastructure concerns (LiFi and Across HTTP request construction and response parsing) to `funding.infrastructure.route-clients`.
- `funding.effects` now delegates wallet RPC transport concerns (provider request, chain switching, receipt polling, and generic send-and-confirm helpers) to `funding.infrastructure.wallet-rpc`.
- Funding submit entrypoints are now implemented in `funding.application.submit-effects`, while `funding.effects` preserves public runtime seams and default dependency wiring.
- HyperUnit lifecycle polling orchestration now runs through `funding.application.lifecycle-polling`, with `funding.effects` preserving existing state helpers and compatibility wrapper behavior.
- Funding modal view-model composition now lives in `funding.application.modal-vm`, reducing `modal_actions` boundary overlap while preserving the existing public facade contract.
- Funding modal command orchestration now lives in `funding.application.modal-commands`, with `modal_actions` preserving the facade API and delegating command logic through injected seams.
- HyperUnit address transport/fallback and request error shaping now live in `funding.infrastructure.hyperunit-address-client`, reducing mixed transport logic inside `funding.effects`.
- ERC20 calldata and eth_call read helpers now live in `funding.infrastructure.erc20-rpc`, reducing wallet transport implementation overlap inside `funding.effects`.

Current gates are green (`npm run check`, `npm test`, `npm run test:websocket`), and runtime contracts remained stable. Remaining work is decomposition depth: the large `modal_actions.cljs` and `effects.cljs` orchestration bodies still need additional internal splits to fully satisfy the bounded-context end state.

## Context and Orientation

In this repository, a "domain module" means pure deterministic rules and state normalization with no direct transport, wallet, timers, or store mutation side effects. An "application module" means orchestration that composes domain logic into action/effect pipelines and preserves ordering guarantees. An "infrastructure module" means boundary code that performs external input/output (HTTP, wallet RPC, timers, JS provider calls).

Current funding behavior is concentrated in two large namespaces:

- `/hyperopen/src/hyperopen/funding/actions.cljs` (1503 LOC): modal state defaults, normalization helpers, asset catalogs, preview validation/request builders, view-model assembly (`funding-modal-view-model`), and action creators that emit runtime effects.
- `/hyperopen/src/hyperopen/funding/effects.cljs` (1957 LOC): HyperUnit request wrappers, polling orchestration, wallet provider RPC encoding/submission, LiFi/Across route calls, and API submit effects for transfer/withdraw/deposit.

Critical boundaries and contracts that must remain stable:

- Runtime action/effect IDs in `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`.
- Runtime handler wiring in `/hyperopen/src/hyperopen/runtime/collaborators.cljs`, `/hyperopen/src/hyperopen/runtime/action_adapters.cljs`, and `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`.
- Effect-order policy for funding submit actions in `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`.
- Existing funding tests in `/hyperopen/test/hyperopen/funding/actions_test.cljs` and `/hyperopen/test/hyperopen/funding/effects_test.cljs`.

## Plan of Work

Milestone 1 establishes a safety net. Add characterization tests that pin current funding behavior before moving code. This includes preview/request-shape behavior for transfer/withdraw/deposit, modal field transition behavior (including lifecycle reset rules), and lifecycle polling progression/state updates. Keep current test files intact initially and add targeted test namespaces only where boundary contracts are clearer than current private-var assertions.

Milestone 2 extracts pure domain ownership from `funding/actions.cljs`. Create focused modules under `/hyperopen/src/hyperopen/funding/domain/` for modal state and lifecycle normalization, asset catalog and selection policy, and transfer/withdraw/deposit validation-preview policy. Keep each module below 500 LOC and each function below 80 LOC unless justified. Convert `/hyperopen/src/hyperopen/funding/actions.cljs` into a facade that re-exports stable public functions and delegates implementation.

Milestone 3 extracts application orchestration from `funding/actions.cljs` into `/hyperopen/src/hyperopen/funding/application/`. Create one module for modal view-model composition and one for modal action orchestration (open/close/set-field/submit). Ensure emitted effect order stays projection-first for submit actions and that no duplicate heavy effects are introduced.

Milestone 4 extracts infrastructure and application orchestration from `funding/effects.cljs`. Create infrastructure modules under `/hyperopen/src/hyperopen/funding/infrastructure/` for HyperUnit transport wrappers/base-url fallback, wallet RPC and EVM transaction helpers, and route provider clients (LiFi/Across). Create application modules under `/hyperopen/src/hyperopen/funding/application/` for submit orchestration and lifecycle polling state transitions. Convert `/hyperopen/src/hyperopen/funding/effects.cljs` to a compatibility facade that preserves callable vars used by runtime wiring and tests.

Milestone 5 hardens contracts and verification. Update tests to assert boundary behavior through public interfaces where possible, minimize private-var coupling, and keep deterministic fakes for timers/network. Re-run focused funding tests, compile gates, and repository required gates.

Milestone 6 closes the work item. Update this ExecPlan with outcomes/confidence, close `hyperopen-8sn` in `bd`, and complete delivery workflow steps required by `/hyperopen/AGENTS.md` when implementation is landed.

## Concrete Steps

All commands run from `/hyperopen`.

1. Baseline and characterization setup.

   Commands:

       bd update hyperopen-8sn --claim --json
       wc -l src/hyperopen/funding/actions.cljs src/hyperopen/funding/effects.cljs
       npm test -- hyperopen.funding.actions-test
       npm test -- hyperopen.funding.effects-test
       npm test -- hyperopen.runtime.collaborators-test

   Expected result: baseline tests pass before extraction, and LOC baseline is captured.

2. Extract domain modules and facade `funding.actions`.

   Create/modify:
   - `/hyperopen/src/hyperopen/funding/domain/modal_state.cljs`
   - `/hyperopen/src/hyperopen/funding/domain/lifecycle.cljs`
   - `/hyperopen/src/hyperopen/funding/domain/assets.cljs`
   - `/hyperopen/src/hyperopen/funding/domain/policy.cljs`
   - `/hyperopen/src/hyperopen/funding/application/modal_vm.cljs`
   - `/hyperopen/src/hyperopen/funding/application/modal_actions.cljs`
   - `/hyperopen/src/hyperopen/funding/actions.cljs`

   Commands:

       npm test -- hyperopen.funding.actions-test

   Expected result: funding actions tests remain green with no public API regressions.

3. Extract effects-side infrastructure and application orchestration.

   Create/modify:
   - `/hyperopen/src/hyperopen/funding/infrastructure/hyperunit_client.cljs`
   - `/hyperopen/src/hyperopen/funding/infrastructure/wallet_rpc.cljs`
   - `/hyperopen/src/hyperopen/funding/infrastructure/route_clients.cljs`
   - `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`
   - `/hyperopen/src/hyperopen/funding/application/submit_effects.cljs`
   - `/hyperopen/src/hyperopen/funding/effects.cljs`

   Commands:

       npm test -- hyperopen.funding.effects-test

   Expected result: funding effects tests remain green and lifecycle behavior is unchanged.

4. Contract and integration hardening.

   Modify as needed:
   - `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
   - `/hyperopen/src/hyperopen/runtime/action_adapters.cljs`
   - `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
   - `/hyperopen/test/hyperopen/runtime/collaborators_test.cljs`
   - `/hyperopen/test/test_runner_generated.cljs` (only if new test namespaces are added)

   Commands:

       npm run check
       npm test
       npm run test:websocket

   Expected result: all required gates pass with unchanged runtime registration contracts.

5. Completion tracking and handoff.

   Commands:

       bd close hyperopen-8sn --reason "Completed" --json
       git pull --rebase
       bd sync
       git push
       git status

   Expected result: tracker status closed, remote synchronized, working tree clean and up to date.

## Validation and Acceptance

Acceptance is met when all of the following are true:

1. Funding runtime behavior for deposit/transfer/withdraw remains unchanged from user perspective, including HyperUnit lifecycle flows and submit-state messaging.
2. `/hyperopen/src/hyperopen/funding/actions.cljs` and `/hyperopen/src/hyperopen/funding/effects.cljs` remain stable compatibility facades with existing public vars callable by current runtime wiring.
3. New funding modules enforce clear layering:
   - Domain modules are pure and deterministic.
   - Application modules orchestrate transitions/effects and preserve ordering.
   - Infrastructure modules own HTTP/wallet/timer input and output.
4. Effect-order invariants for `:actions/submit-funding-transfer`, `:actions/submit-funding-withdraw`, and `:actions/submit-funding-deposit` remain valid.
5. Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.
6. Refactor completion confidence is documented and >= 84.7% using required weighting (Testing 40%, Code review 30%, Logical inspection 30%).

## Idempotence and Recovery

The refactor is additive and facade-first, so it can be executed in repeatable slices. Each milestone is reversible by re-pointing facade vars to legacy implementations while keeping new tests in place.

If a split introduces regressions:

- First restore facade delegation for the failing function(s) to the last known good implementation.
- Keep new tests and module scaffolding, then reintroduce extraction incrementally.
- Avoid destructive git operations; use normal commits/reverts to preserve traceability.

No data migrations are involved. Runtime state keys under `[:funding-ui :modal]` must remain shape-compatible throughout.

## Artifacts and Notes

Primary files to create:

- `/hyperopen/src/hyperopen/funding/domain/modal_state.cljs`
- `/hyperopen/src/hyperopen/funding/domain/lifecycle.cljs`
- `/hyperopen/src/hyperopen/funding/domain/assets.cljs`
- `/hyperopen/src/hyperopen/funding/domain/policy.cljs`
- `/hyperopen/src/hyperopen/funding/application/modal_vm.cljs`
- `/hyperopen/src/hyperopen/funding/application/modal_actions.cljs`
- `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`
- `/hyperopen/src/hyperopen/funding/application/submit_effects.cljs`
- `/hyperopen/src/hyperopen/funding/infrastructure/hyperunit_client.cljs`
- `/hyperopen/src/hyperopen/funding/infrastructure/wallet_rpc.cljs`
- `/hyperopen/src/hyperopen/funding/infrastructure/route_clients.cljs`

Primary files to modify:

- `/hyperopen/src/hyperopen/funding/actions.cljs`
- `/hyperopen/src/hyperopen/funding/effects.cljs`
- `/hyperopen/src/hyperopen/runtime/collaborators.cljs` (only if dependency wiring requires seam updates)
- `/hyperopen/test/hyperopen/funding/actions_test.cljs`
- `/hyperopen/test/hyperopen/funding/effects_test.cljs`
- `/hyperopen/test/hyperopen/runtime/collaborators_test.cljs`

Optional governance artifact:

- `/hyperopen/docs/architecture-decision-records/0024-funding-bounded-context-layer-split.md` (only if boundary ownership changes are permanent architecture policy, not internal refactor mechanics)

## Interfaces and Dependencies

Stable action-surface interface that must remain available from `/hyperopen/src/hyperopen/funding/actions.cljs`:

- `default-funding-modal-state`
- `modal-open?`
- `default-hyperunit-lifecycle-state`
- `normalize-hyperunit-lifecycle`
- `hyperunit-lifecycle-terminal?`
- `default-hyperunit-fee-estimate-state`
- `normalize-hyperunit-fee-estimate`
- `default-hyperunit-withdrawal-queue-state`
- `normalize-hyperunit-withdrawal-queue`
- `funding-modal-view-model`
- `open-funding-deposit-modal`
- `open-funding-transfer-modal`
- `open-funding-withdraw-modal`
- `close-funding-modal`
- `handle-funding-modal-keydown`
- `set-funding-modal-field`
- `set-hyperunit-lifecycle`
- `clear-hyperunit-lifecycle`
- `set-hyperunit-lifecycle-error`
- `set-funding-transfer-direction`
- `set-funding-amount-to-max`
- `submit-funding-transfer`
- `submit-funding-withdraw`
- `submit-funding-deposit`
- `set-funding-modal-compat`

Stable effects-surface interface that must remain available from `/hyperopen/src/hyperopen/funding/effects.cljs`:

- `request-hyperunit-operations!`
- `request-hyperunit-estimate-fees!`
- `request-hyperunit-withdrawal-queue!`
- `request-hyperunit-generate-address!`
- `api-fetch-hyperunit-fee-estimate!`
- `api-fetch-hyperunit-withdrawal-queue!`
- `api-submit-funding-transfer!`
- `api-submit-funding-withdraw!`
- `api-submit-funding-deposit!`

Dependency-direction target after refactor:

- `funding.domain.*` -> no dependency on infrastructure or runtime store mutation.
- `funding.application.*` -> depends on `funding.domain.*` abstractions and injected collaborators.
- `funding.infrastructure.*` -> depends on gateway/wallet/browser APIs only; no business rule ownership.
- `funding.actions` and `funding.effects` facades -> thin delegation only.

Plan revision note: 2026-03-03 20:44Z - Initial plan created from AGENTS/WORK_TRACKING requirements and current funding hotspot audit; linked to `bd` issue `hyperopen-8sn`.
Plan revision note: 2026-03-03 21:08Z - Updated after first implementation slice with completed extraction/validation evidence and narrowed remaining decomposition work.
