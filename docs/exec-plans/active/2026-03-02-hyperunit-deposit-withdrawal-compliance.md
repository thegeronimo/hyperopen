# HyperUnit Deposit and Withdrawal Compliance Implementation

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and references prior audit findings from `/hyperopen/docs/exec-plans/completed/2026-03-02-hyperunit-api-lifecycle-parity-audit.md`.

## Purpose / Big Picture

After this work, Unit-asset funding in Hyperopen will no longer stop at address generation. Users will be able to track deposit and withdrawal lifecycle stages (including confirmations, queueing, broadcast, and terminal/failure outcomes) in the trade-page funding flow, with fee/time estimates sourced from HyperUnit APIs rather than static copy. The user-visible result is operational parity with HyperUnit protocol expectations for deposits and withdrawals.

A contributor can verify success by opening `/trade`, initiating a Unit deposit or withdrawal, and seeing a stage-aware lifecycle panel that updates based on HyperUnit operation responses until the operation reaches a terminal state.

## Progress

- [x] (2026-03-02 17:57Z) Created implementation ExecPlan from the completed parity audit.
- [x] (2026-03-02 18:02Z) Confirmed current baseline integration points in funding actions/effects/view and runtime registration surfaces.
- [x] (2026-03-02 18:44Z) Implemented HyperUnit API boundary (endpoints + gateway + instance wiring) with normalization for generate-address, operations, estimate-fees, and withdrawal-queue, plus milestone tests.
- [ ] Add canonical funding lifecycle state model and deterministic transitions for Unit operations.
- [ ] Implement Unit deposit lifecycle polling and UI stage rendering.
- [ ] Implement Unit withdrawal flow (asset-based) and lifecycle/queue tracking.
- [ ] Integrate `estimate-fees` output into UI and validation messages.
- [ ] Add regression + lifecycle tests across actions/effects/view/runtime contracts.
- [ ] Run required validation gates and move this plan to completed.

## Surprises & Discoveries

- Observation: HyperUnit operation lifecycles are materially richer than the current Hyperopen modal contract (multiple non-terminal states, queue positions, per-chain confirmation semantics).
  Evidence: `/developers/api/operations`, `/developers/api/operations/deposit-lifecycle`, `/developers/api/operations/withdrawal-lifecycle`.

- Observation: HyperUnit uses different finalization thresholds by chain and operation type, and users are expected to monitor this progression via operations metadata.
  Evidence: `/developers/api` required-confirmations table and lifecycle docs.

- Observation: Current Hyperopen funding modal copy contains static fee/time text that is incompatible with dynamic API-provided estimates.
  Evidence: `/hyperopen/src/hyperopen/views/funding_modal.cljs` currently renders fixed strings for estimated time/network fee.

## Decision Log

- Decision: Implement a dedicated HyperUnit API boundary namespace instead of keeping direct `fetch` calls embedded in funding effects.
  Rationale: Clear API boundary improves testability, schema normalization, and lifecycle evolution without coupling view logic to raw payload shape.
  Date/Author: 2026-03-02 / Codex

- Decision: Model lifecycle as explicit normalized state in store (stage, status, confirmations, queue metadata, tx hashes), not as ad hoc response fragments.
  Rationale: UI and tests need deterministic contracts and transition semantics.
  Date/Author: 2026-03-02 / Codex

- Decision: Ship deposit lifecycle first, then withdrawal lifecycle, while keeping both represented in one shared lifecycle model.
  Rationale: Deposit is already partially integrated; this reduces migration risk while preserving a single future-proof model.
  Date/Author: 2026-03-02 / Codex

## Outcomes & Retrospective

Not yet implemented. The expected retrospective at completion is that Unit deposit/withdraw flows are lifecycle-aware, API-aligned, and covered by deterministic tests. Remaining risks to track at completion are edge-case treatment for reverted/non-recoverable operations and cross-chain explorer linkage quality.

## Context and Orientation

Current funding flow touches:

- `/hyperopen/src/hyperopen/funding/actions.cljs`
  Builds modal state and submit requests. Unit assets already map to `:flow-kind :hyperunit-address` for deposit address generation.

- `/hyperopen/src/hyperopen/funding/effects.cljs`
  Executes side effects. HyperUnit support currently calls only `GET /gen/:src_chain/:dst_chain/:asset/:dst_addr` and stores address/signatures; it does not call operations, estimate-fees, or withdrawal queue endpoints.

- `/hyperopen/src/hyperopen/views/funding_modal.cljs`
  Renders deposit amount step and generated address; it has no operation lifecycle timeline.

- Runtime and contracts:
  - `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
  - `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
  - `/hyperopen/src/hyperopen/registry/runtime.cljs`
  - `/hyperopen/src/hyperopen/schema/contracts.cljs`
  - `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`

External HyperUnit API expectations to satisfy:

- `GET /gen/:src_chain/:dst_chain/:asset/:dst_addr` for protocol address generation.
- `GET /operations/:address` for operation objects and stage metadata.
- `GET /v2/estimate-fees` for chain/asset fee and ETA guidance.
- `GET /withdrawal-queue` for queue-based withdrawal visibility.
- Lifecycle semantics from deposit and withdrawal lifecycle docs, including queue-related states and confirmation progression.

## Plan of Work

Milestone 1 introduces a HyperUnit API boundary and normalized models. Add a new funding API namespace that wraps all required HyperUnit endpoints and normalizes raw payloads into deterministic maps used by actions/effects. Keep this layer pure and testable.

Milestone 2 introduces a canonical lifecycle state model in funding modal state and action/view-model outputs. Add explicit fields for operation id, state, status, confirmation counts, queue position, destination hash, and last-updated timestamp. Keep defaults stable and schema-backed.

Milestone 3 wires deposit lifecycle progression. After generating a Unit deposit address, schedule polling of `operations/:address`, map operation entries to selected asset + direction, and surface stage transitions in modal UI. Add retry/backoff semantics using `stateNextAttemptAt` when present, while preserving deterministic effect ordering.

Milestone 4 adds Unit withdrawal flow parity. Introduce asset-aware withdrawal mode for Unit assets (destination address + amount, using generated Hyperliquid protocol withdrawal address model), then track resulting operation stages and queue metadata through the same lifecycle UI model.

Milestone 5 integrates fee/ETA estimates from `v2/estimate-fees`, replacing static strings and aligning min/max guidance messaging with API-provided context where available.

Milestone 6 hardens tests and contracts. Add unit tests for endpoint normalization, action transitions, effect orchestration, and lifecycle rendering; extend schema contracts and runtime mappings for any new actions/effects. Validate with required repo gates.

## Concrete Steps

Run all commands from `/hyperopen`.

1. Create API boundary + normalization layer.

    Create files:
      src/hyperopen/api/endpoints/funding_hyperunit.cljs
      src/hyperopen/api/funding_hyperunit.cljs

    Add functions:
      request-hyperunit-generate-address!
      request-hyperunit-operations!
      request-hyperunit-estimate-fees!
      request-hyperunit-withdrawal-queue!

2. Extend funding state model and view-model contract.

    Edit:
      src/hyperopen/funding/actions.cljs
      src/hyperopen/state/app_defaults.cljs
      src/hyperopen/schema/contracts.cljs

    Add normalized lifecycle fields (example target shape):
      :funding-ui {:modal {:hyperunit-lifecycle
                           {:direction :deposit|:withdraw
                            :asset-key :btc
                            :operation-id nil
                            :state nil
                            :status nil
                            :source-tx-confirmations nil
                            :destination-tx-confirmations nil
                            :position-in-withdraw-queue nil
                            :destination-tx-hash nil
                            :state-next-at nil
                            :last-updated-ms nil
                            :error nil}}}

3. Add lifecycle polling effects and runtime wiring.

    Edit:
      src/hyperopen/funding/effects.cljs
      src/hyperopen/runtime/effect_adapters.cljs
      src/hyperopen/runtime/registry_composition.cljs
      src/hyperopen/registry/runtime.cljs
      src/hyperopen/app/effects.cljs
      src/hyperopen/runtime/effect_order_contract.cljs
      src/hyperopen/schema/contracts.cljs

    Add effect IDs as needed (example):
      :effects/api-fetch-hyperunit-operations
      :effects/api-fetch-hyperunit-estimate-fees
      :effects/api-fetch-hyperunit-withdrawal-queue
      :effects/schedule-hyperunit-lifecycle-poll

4. Implement Unit withdrawal flow support and UI stage rendering.

    Edit:
      src/hyperopen/funding/actions.cljs
      src/hyperopen/views/funding_modal.cljs

    Required behavior:
      - user can select Unit asset for withdrawal.
      - destination + amount submit path uses HyperUnit address model.
      - lifecycle panel displays stage label, confirmations, queue position, and tx hash when available.

5. Add tests.

    Edit/add:
      test/hyperopen/funding/actions_test.cljs
      test/hyperopen/funding/effects_test.cljs
      test/hyperopen/views/funding_modal_test.cljs (or existing view test namespace)
      test/hyperopen/runtime/effect_adapters_test.cljs (if affected)
      test/hyperopen/schema/contracts_test.cljs (if affected)

    Cover:
      - state transitions for deposit/withdraw lifecycle polling.
      - lifecycle stage rendering contract in modal view.
      - queue position + confirmation field propagation.
      - error/retry behavior for failed endpoint calls.

6. Run required gates and capture pass results in this plan.

    npm run check
    npm test
    npm run test:websocket

## Validation and Acceptance

Global acceptance criteria:

1. Unit deposit flow shows lifecycle progression after address generation (not just static address display).
2. Unit withdrawal flow exists for supported Unit assets and shows lifecycle progression.
3. Lifecycle UI includes, when available: current stage, status, source/destination confirmations, queue position, destination hash.
4. `estimate-fees` API data appears in funding UI with clear loading/error states.
5. Funding behavior remains deterministic (state updates precede heavy work) and contracts pass.
6. Required validation gates pass (`npm run check`, `npm test`, `npm run test:websocket`).

Manual acceptance scenario template (repeat per representative asset set):

- Deposit BTC/ETH/SOL path: generate address, observe operation transition through source confirmations toward done.
- Withdraw BTC and one ERC20 Unit asset path: submit, observe queue-related states and destination hash surfacing.
- Negative path: simulate endpoint failure; UI must preserve form state and show readable error.

## Idempotence and Recovery

- API boundary additions are additive and safe to re-run.
- Lifecycle polling should be idempotent by keying updates on `(address, asset-key, direction, operation-id)` and replacing stale polls when modal closes or asset changes.
- If lifecycle polling causes regressions, disable new polling effect IDs while retaining generate-address baseline flow.

## Artifacts and Notes

HyperUnit sources used for this implementation plan:

- `https://docs.hyperunit.xyz/developers/api`
- `https://docs.hyperunit.xyz/developers/api/generate-address`
- `https://docs.hyperunit.xyz/developers/api/operations`
- `https://docs.hyperunit.xyz/developers/api/operations/deposit-lifecycle`
- `https://docs.hyperunit.xyz/developers/api/operations/withdrawal-lifecycle`
- `https://docs.hyperunit.xyz/developers/api/estimate-fees`
- `https://docs.hyperunit.xyz/developers/api/withdraw-queue`
- `https://docs.hyperunit.xyz/developers/api/generate-address/guardian-signatures`

Current known deltas from prior audit (to be resolved by this plan):

- No operations polling/lifecycle model in store.
- No withdrawal queue integration.
- No fee estimate integration.
- No Unit-asset withdrawal lifecycle support in UI.

## Interfaces and Dependencies

Expected interfaces after completion:

- `hyperopen.api.funding-hyperunit`

    (defn request-generate-address! [opts] -> Promise<normalized-address-response>)
    (defn request-operations! [opts] -> Promise<normalized-operations-response>)
    (defn request-estimate-fees! [opts] -> Promise<normalized-fee-response>)
    (defn request-withdrawal-queue! [opts] -> Promise<normalized-queue-response>)

- `hyperopen.funding.actions` lifecycle update actions

    :actions/set-hyperunit-lifecycle
    :actions/clear-hyperunit-lifecycle
    :actions/set-hyperunit-lifecycle-error

- `hyperopen.funding.effects` lifecycle orchestration

    :effects/api-fetch-hyperunit-operations
    :effects/api-fetch-hyperunit-estimate-fees
    :effects/api-fetch-hyperunit-withdrawal-queue
    :effects/schedule-hyperunit-lifecycle-poll

Use existing collaborators/runtime registration patterns and keep side effects isolated in effect layer.

Revision note (2026-03-02): Initial implementation plan created based on completed HyperUnit parity audit to drive full compliance work for Unit deposit/withdrawal lifecycle requirements.
