# HyperUnit API Lifecycle Parity Audit for Unit Assets

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this audit, a contributor can answer two concrete questions without re-reading external docs: (1) what HyperUnit's API expects for Unit asset deposit/withdraw lifecycle handling, and (2) where Hyperopen currently deviates from those expectations. The user-visible goal is safer Unit-asset funding UX on `/trade`, especially lifecycle transparency (stages, confirmations, failures) instead of one-shot address generation with no lifecycle tracking.

## Progress

- [x] (2026-03-02 17:18Z) Re-read planning requirements in `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`.
- [x] (2026-03-02 17:25Z) Collected HyperUnit primary-source API references from `https://docs.hyperunit.xyz/developers/api` and its linked reference/lifecycle pages.
- [x] (2026-03-02 17:33Z) Audited current Hyperopen Unit-asset deposit implementation in `/hyperopen/src/hyperopen/funding/actions.cljs`, `/hyperopen/src/hyperopen/funding/effects.cljs`, and `/hyperopen/src/hyperopen/views/funding_modal.cljs`.
- [x] (2026-03-02 17:42Z) Built API-contract-to-implementation parity map and deviation severity ranking.
- [x] (2026-03-02 17:45Z) Finalized this execution plan and executed it through completion of the deviation report.

## Surprises & Discoveries

- Observation: HyperUnit lifecycle docs explicitly define many intermediate states for deposits and withdrawals, including discovery/disappearance/re-check states and chain-confirmation states for non-ERC20 chains.
  Evidence: `/developers/api/lifecycle/deposit` and `/developers/api/lifecycle/withdrawal` list granular states such as `SourceTxDiscovered`, `CheckingSourceTxStatus`, `DstTxDisappeared`, `FatalError`, and non-ERC20 confirmation states.

- Observation: The deposit-address response contract includes signature-readiness fields (`signatureOperationId`, `signatureEndpointError`, `isSufficientlySigned`) in addition to address/signatures.
  Evidence: `/developers/api/reference/generate-deposit-address` documents these fields in the response schema.

- Observation: Hyperopen currently treats HyperUnit address generation as terminal success in UI flow and does not hydrate operation lifecycle state after source-chain send.
  Evidence: `/hyperopen/src/hyperopen/funding/effects.cljs` only calls `GET /gen/...` and stores `:deposit-generated-address` + signatures; no `operations` polling path exists.

- Observation: Hyperopen's Unit-asset UX currently displays static estimates (time/fees) instead of API-derived estimates.
  Evidence: `/hyperopen/src/hyperopen/views/funding_modal.cljs` renders fixed copy (`"Depends on source confirmations"`, `"Paid on source chain"`) and no call to `/v2/estimate-fees` exists.

- Observation: HyperUnit minimum-amount guidance is not perfectly consistent across pages, so hardcoding one source can drift.
  Evidence: `/developers/api/reference/generate-deposit-address` and `/developers/api/lifecycle/deposit` show differing BTC/ETH/SOL minima examples.

## Decision Log

- Decision: Scope this audit to Unit-asset funding paths (HyperUnit address deposits and lifecycle visibility), not generic USDC Bridge2 behavior.
  Rationale: User request was specifically to validate Unit-asset support and lifecycle staging parity.
  Date/Author: 2026-03-02 / Codex

- Decision: Treat missing lifecycle polling and stage rendering as the highest-risk deviation.
  Rationale: This is the core expectation from HyperUnit lifecycle docs and directly affects user confidence and issue triage when deposits stall.
  Date/Author: 2026-03-02 / Codex

- Decision: Mark dynamic fee/ETA integration as medium priority after lifecycle tracking.
  Rationale: Static fee/ETA copy is less critical than lifecycle correctness, but still diverges from available API contract (`/v2/estimate-fees`).
  Date/Author: 2026-03-02 / Codex

## Outcomes & Retrospective

Completed: the requested plan was created and executed; this document now includes the HyperUnit API expectation model, direct mapping to current code, and a prioritized deviation list.

Key result: Hyperopen supports Unit-asset address generation for Unit assets but does not yet implement the documented operation lifecycle model (stage progression, confirmation counts, retry windows, fatal-state surfacing) for either deposits or withdrawals.

Remaining work is implementation-oriented, not research-oriented. The largest unknowns are resolved enough to proceed with a concrete engineering milestone plan.

## Context and Orientation

Current Unit-asset funding behavior is split across these surfaces:

- `/hyperopen/src/hyperopen/funding/actions.cljs`
  Owns the deposit asset catalog and submit request generation. Unit assets are tagged as `:flow-kind :hyperunit-address` with `:hyperunit-source-chain` values.

- `/hyperopen/src/hyperopen/funding/effects.cljs`
  Owns side effects for deposit submit actions. HyperUnit path currently calls `GET /gen/{fromChain}/hyperliquid/{asset}/{destinationAddress}` and stores address/signatures in modal state.

- `/hyperopen/src/hyperopen/views/funding_modal.cljs`
  Renders Unit-asset deposit UI after address generation. Current lifecycle messaging is static and not tied to HyperUnit operation stages.

HyperUnit API surfaces relevant to parity:

- `Generate deposit address` (`/developers/api/reference/generate-deposit-address`): address + signatures + signature readiness metadata.
- `Operations` (`/developers/api/reference/operations`): operation objects with state/status/confirmations/tx hashes and next-attempt timestamps.
- `Deposit lifecycle` (`/developers/api/lifecycle/deposit`): expected stage machine for deposit processing.
- `Withdrawal lifecycle` (`/developers/api/lifecycle/withdrawal`): expected stage machine for withdrawal processing.
- `Estimate fees` (`/developers/api/reference/estimate-fees`): dynamic fee and ETA expectations.
- `Generate signatures` (`/developers/api/reference/generate-signatures`): signature generation/recovery pathway.
- `Withdrawal queue` (`/developers/api/reference/withdrawal-queue`): queued withdrawal visibility.

## Plan of Work

First, document the HyperUnit API contract in plain language as an internal reference. This contract must include the API resources above and explicitly spell out that HyperUnit expects lifecycle handling to continue after address generation.

Second, map each contract expectation to current Hyperopen behavior by reading the funding action/effect/view code paths and tests. The mapping should answer "implemented", "partially implemented", or "missing" for each expectation.

Third, convert gaps into prioritized deviations with concrete remediation milestones that can become implementation tickets (for example: lifecycle polling service, stage model in state, UI stage timeline, dynamic fee estimator integration, signature-readiness handling).

## Concrete Steps

Run all commands from repository root `/hyperopen`.

1. Gather primary-source contract pages.

    Open and read:
      https://docs.hyperunit.xyz/developers/api
      https://docs.hyperunit.xyz/developers/api/reference/generate-deposit-address
      https://docs.hyperunit.xyz/developers/api/reference/operations
      https://docs.hyperunit.xyz/developers/api/lifecycle/deposit
      https://docs.hyperunit.xyz/developers/api/lifecycle/withdrawal
      https://docs.hyperunit.xyz/developers/api/reference/estimate-fees
      https://docs.hyperunit.xyz/developers/api/reference/generate-signatures
      https://docs.hyperunit.xyz/developers/api/reference/withdrawal-queue

2. Audit local implementation.

    rg -n "hyperunit|generateDepositAddress|operations|estimate-fees|withdrawal-queue|deposit-generated" src test
    nl -ba src/hyperopen/funding/actions.cljs | sed -n '20,130p'
    nl -ba src/hyperopen/funding/actions.cljs | sed -n '440,525p'
    nl -ba src/hyperopen/funding/effects.cljs | sed -n '458,560p'
    nl -ba src/hyperopen/funding/effects.cljs | sed -n '860,940p'
    nl -ba src/hyperopen/views/funding_modal.cljs | sed -n '248,350p'

3. Build parity matrix and deviations (captured in this plan's sections below).

## Validation and Acceptance

This audit is accepted when all of the following are true:

1. HyperUnit API expectation coverage is explicit for endpoint behavior and lifecycle stages.
2. Current Hyperopen behavior is mapped to those expectations with file-level evidence.
3. Deviations are prioritized and actionable, with implementation milestones defined.
4. Source links and local file references are present so a new contributor can continue directly.

## Idempotence and Recovery

All audit steps are read-only and repeatable. Re-running them is safe and should be done when HyperUnit docs change materially or when funding implementation is refactored.

## Artifacts and Notes

Parity summary (executed result):

- Implemented:
  - Unit asset catalog includes Unit assets and source-chain identifiers for HyperUnit address generation.
  - HyperUnit address generation request path exists and stores address/signatures in modal state.

- Partially implemented:
  - Address-generation response handling parses `address` and `signatures`, but does not consume signature readiness lifecycle fields (`signatureOperationId`, `signatureEndpointError`, `isSufficientlySigned`).

- Missing:
  - Operations polling/query pipeline based on HyperUnit operation states.
  - Lifecycle stage model in app state for Unit deposits/withdrawals.
  - UI stage/timeline rendering and state-specific user guidance.
  - Dynamic fee/ETA integration via HyperUnit estimate-fees endpoint.
  - Signature-regeneration/recovery flow via generate-signatures endpoint.
  - Withdrawal queue visibility and lifecycle handling for Unit withdrawals.

Prioritized deviations (execution output):

1. P0: No HyperUnit lifecycle tracking after address generation.
   - Expected: stage progression from lifecycle docs and operation status fields.
   - Actual: submit path stops at "Deposit address generated" and static guidance.

2. P0: No consumption of signature readiness metadata in `/gen` response.
   - Expected: react to insufficient signatures and signature endpoint errors.
   - Actual: only address/signatures count are stored/displayed.

3. P1: No estimate-fees integration for Unit flows.
   - Expected: fee/ETA from HyperUnit API.
   - Actual: hardcoded text in modal.

4. P1: No Unit-withdrawal lifecycle/queue integration.
   - Expected: lifecycle visibility and queued withdrawal handling.
   - Actual: no HyperUnit withdrawal queue API integration.

5. P2: Validation rules for Unit asset limits/minima are not sourced from HyperUnit contract.
   - Expected: protocol-aligned limits with one canonical source.
   - Actual: generic local minima/maxima and mixed static assumptions.

Recommended implementation milestones from this audit:

- Milestone A: Add a HyperUnit operations client (`operations`, `estimate-fees`, `generate-signatures`, `withdrawal-queue`) in funding infrastructure.
- Milestone B: Introduce deterministic Unit lifecycle model in funding state (`stage`, `status`, confirmations, tx hashes, `next-attempt-at`).
- Milestone C: Bind lifecycle polling to modal/session and render stage timeline + recoverable actions.
- Milestone D: Replace static fee/ETA copy with API-derived values and explicit stale/loading/error states.
- Milestone E: Add end-to-end tests for lifecycle transitions and error states (`FatalError`, disappeared/retry states, insufficient signatures).

## Interfaces and Dependencies

Interfaces that should exist after remediation work (not implemented in this audit):

- Funding API wrapper functions for:
  - HyperUnit operations lookup by destination address.
  - HyperUnit fee/ETA estimate for selected asset/chain.
  - HyperUnit signature generation/recovery path.
  - HyperUnit withdrawal queue lookup.

- Funding lifecycle state shape in store:

    {:hyperunit-lifecycle
     {:asset-key :btc
      :operation-id "..."
      :state "SourceTxDiscovered"
      :status "IN_PROGRESS"
      :source-tx-confirmations 1
      :destination-tx-confirmations 0
      :state-next-attempt-at 1730000000
      :fatal-error nil}}

- View-model contract for modal rendering:

    {:deposit-lifecycle-stage "SourceTxDiscovered"
     :deposit-lifecycle-status "IN_PROGRESS"
     :deposit-lifecycle-message "Awaiting source confirmations"
     :deposit-lifecycle-updated-at 1730000000}

Revision note (2026-03-02): Initial plan created and fully executed as a read-only parity audit against HyperUnit API docs and current funding implementation.
