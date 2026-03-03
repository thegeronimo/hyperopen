# HyperUnit Terminal Failure UX and Explorer Links

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and follows prior HyperUnit lifecycle milestones in `/hyperopen/docs/exec-plans/completed/2026-03-02-hyperunit-terminal-lifecycle-refresh.md`.

## Purpose / Big Picture

After this milestone, HyperUnit lifecycle panels in the funding modal expose explicit terminal outcomes (`Completed` or `Needs Attention`), actionable recovery guidance for terminal failures, and clickable explorer links for destination lifecycle transactions and withdraw queue operation transaction IDs where supported. Users can immediately inspect on-chain status and decide how to recover when a deposit or withdrawal ends in failure.

A contributor verifies behavior by opening `/trade`, entering a HyperUnit deposit/withdraw flow, and observing lifecycle rows with outcome labels, failure recovery copy, and explorer links in lifecycle/queue sections.

## Progress

- [x] (2026-03-02 23:42Z) Authored this milestone ExecPlan and scoped actions/effects/view/test surfaces.
- [x] (2026-03-02 23:42Z) Added normalized lifecycle terminal classification in funding actions and reused it in funding effects polling stop logic.
- [x] (2026-03-02 23:42Z) Added funding modal view-model derived fields for lifecycle outcome, recovery hint, destination explorer URL, and queue tx explorer URL.
- [x] (2026-03-02 23:42Z) Updated funding modal lifecycle rendering for deposit and withdraw to show outcome rows, failure recovery copy, and clickable explorer links.
- [x] (2026-03-02 23:42Z) Added regression coverage in funding action tests for terminal outcome and explorer URL derivation.
- [x] (2026-03-02 23:42Z) Ran required validation gates (`npm run check`, `npm test`, `npm run test:websocket`) successfully.

## Surprises & Discoveries

- Observation: Funding effects and funding actions previously had separate terminal-state heuristics.
  Evidence: `/hyperopen/src/hyperopen/funding/effects.cljs` had private lifecycle terminal detection logic independent of action/view-model code.

- Observation: Lifecycle destination transaction hashes can exist before terminal completion, so explorer links should not be terminal-only.
  Evidence: Existing normalized lifecycle test fixture includes `:destination-tx-hash` with non-terminal state and status.

## Decision Log

- Decision: Centralize terminal lifecycle detection in `hyperopen.funding.actions/hyperunit-lifecycle-terminal?` and call it from effects polling.
  Rationale: Keeps terminal semantics deterministic across polling stop behavior and modal rendering.
  Date/Author: 2026-03-02 / Codex

- Decision: Derive outcome/recovery/explorer fields in funding view-model instead of app state schema.
  Rationale: Avoids schema/default-state churn while preserving stable persisted modal state shape.
  Date/Author: 2026-03-02 / Codex

- Decision: Scope explorer URLs to chains with known stable endpoints (`arbitrum`, `bitcoin`, `ethereum`, `solana`) plus Hyperliquid explorer for deposit destination lifecycle tx hashes.
  Rationale: Prevents broken links for unsupported/unknown chains while still delivering high-value coverage for currently supported routes.
  Date/Author: 2026-03-02 / Codex

## Outcomes & Retrospective

Milestone complete.

Delivered behavior:

- Funding lifecycle panels now render explicit terminal outcomes.
- Terminal failures render recovery guidance text for user next actions.
- Lifecycle destination tx hashes render as explorer links when URL derivation is available.
- Withdraw queue “Last queue tx” now links to explorer for supported chains.
- Effects polling terminal stop logic now reuses the same terminal classifier used by view-model derivations.

Validation:

- `npm run check` passed (2026-03-02).
- `npm test` passed (2026-03-02).
- `npm run test:websocket` passed (2026-03-02).

## Context and Orientation

Funding modal state derivation and preview logic live in `/hyperopen/src/hyperopen/funding/actions.cljs`. Funding lifecycle polling runtime and terminal stop conditions live in `/hyperopen/src/hyperopen/funding/effects.cljs`. Funding modal rendering is in `/hyperopen/src/hyperopen/views/funding_modal.cljs`. This milestone intentionally avoids changing funding modal persisted schema keys and keeps new behavior as derived view-model-only fields.

## Plan of Work

Implement a shared lifecycle terminal helper in funding actions. Extend funding modal view-model with derived lifecycle metadata: terminal outcome kind/label, recovery hint text, destination explorer URL, and withdraw queue explorer URL. Rewire effects polling to consume the shared helper for terminal stop behavior. Update funding modal lifecycle sections for deposit/withdraw to render outcome rows, recovery warning boxes on failures, and hyperlink tx hashes when explorer URLs exist. Add tests covering new derivations and rerun required validation gates.

## Concrete Steps

Run from `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/funding/actions.cljs`:
   - Add lifecycle terminal fragment helpers and `hyperunit-lifecycle-terminal?`.
   - Add derived lifecycle outcome/explorer/recovery view-model fields.
2. Edit `/hyperopen/src/hyperopen/funding/effects.cljs`:
   - Replace local terminal check with `funding-actions/hyperunit-lifecycle-terminal?`.
3. Edit `/hyperopen/src/hyperopen/views/funding_modal.cljs`:
   - Render outcome/recovery rows and explorer anchors in lifecycle and queue sections.
4. Edit `/hyperopen/test/hyperopen/funding/actions_test.cljs`:
   - Add assertions/tests for lifecycle outcome and explorer URL derivation.
5. Run required gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance criteria:

1. HyperUnit lifecycle panel shows `Outcome` when lifecycle is terminal.
2. Terminal failure shows recovery guidance text.
3. Destination tx hash and queue tx rows render links when explorer URL is known.
4. Funding action/effect tests pass with new view-model fields.
5. Required gates pass.

## Idempotence and Recovery

Changes are additive and safe to re-run. If link rendering regresses style/behavior, recovery path is to keep view-model derivations and temporarily switch view anchors back to plain text while preserving tests for derivation keys.

## Artifacts and Notes

Touched files:

- `/hyperopen/src/hyperopen/funding/actions.cljs`
- `/hyperopen/src/hyperopen/funding/effects.cljs`
- `/hyperopen/src/hyperopen/views/funding_modal.cljs`
- `/hyperopen/test/hyperopen/funding/actions_test.cljs`

## Interfaces and Dependencies

Updated/introduced action-side interface:

- `hyperopen.funding.actions/hyperunit-lifecycle-terminal?` (pure helper used by effects and modal view-model derivation).

New funding modal view-model keys:

- `:hyperunit-lifecycle-terminal?`
- `:hyperunit-lifecycle-outcome`
- `:hyperunit-lifecycle-outcome-label`
- `:hyperunit-lifecycle-recovery-hint`
- `:hyperunit-lifecycle-destination-explorer-url`
- `:withdraw-queue-last-operation-explorer-url`

Revision note (2026-03-02): Created and completed this milestone plan to record terminal failure UX + explorer link implementation and validation evidence.
