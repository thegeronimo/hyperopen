# Funding Modal Withdraw And Lifecycle CRAP Reduction

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, the funding modal withdraw path and lifecycle panel remain visually identical to today, but their rendering logic is split into smaller deterministic helpers and covered by direct regression tests. A developer working on withdraw and HyperUnit lifecycle states will be able to change the modal with much lower risk because the previously uncovered branches for queue status, explorer links, protocol address display, and terminal lifecycle notices are exercised by tests.

The observable proof is straightforward: run the funding modal view tests and confirm they now cover both HyperUnit and standard withdraw states, including a rendered lifecycle panel with terminal failure details. The CRAP hotspot functions in `/hyperopen/src/hyperopen/views/funding_modal.cljs` should no longer depend on one large branchy helper with near-zero coverage.

## Progress

- [x] (2026-03-07 19:10Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, and `/hyperopen/docs/agent-guides/trading-ui-policy.md` for repo, planning, and UI constraints.
- [x] (2026-03-07 19:10Z) Checked `bd ready --json`, selected the highest-priority ready Reduce CRAP issue, and claimed `hyperopen-1qj`.
- [x] (2026-03-07 19:10Z) Audited the hotspot functions in `/hyperopen/src/hyperopen/views/funding_modal.cljs` and the current test gaps in `/hyperopen/test/hyperopen/views/funding_modal_test.cljs`.
- [x] (2026-03-07 19:10Z) Authored this ExecPlan.
- [x] (2026-03-07 19:15Z) Refactored `lifecycle-panel` into smaller render helpers while preserving the same copy, data-role contract, and optional notice blocks.
- [x] (2026-03-07 19:15Z) Refactored `withdraw-content` into focused helpers for the asset selector, destination field, queue rows, fee and queue status messages, summary section, and protocol address panel.
- [x] (2026-03-07 19:17Z) Added focused funding modal tests covering the public HyperUnit withdraw render, the standard withdraw render, a direct queue-error branch in `withdraw-content`, and the plain-text lifecycle tx-hash fallback.
- [x] (2026-03-07 19:20Z) Ran `npm test`, `npm run check`, and `npm run test:websocket` successfully.
- [x] (2026-03-07 19:21Z) Ran `npm run coverage` and `npm run crap:report -- --module src/hyperopen/views/funding_modal.cljs --format json` to verify the module no longer contains CRAP hotspots above threshold.
- [ ] Update `bd` issue `hyperopen-1qj`, move this ExecPlan to `/hyperopen/docs/exec-plans/completed/`, and record final handoff state.

## Surprises & Discoveries

- Observation: The existing public view test file only covers deposit rendering and anchor fallback behavior, so the largest withdraw-specific helper is effectively untested at the view layer.
  Evidence: `/hyperopen/test/hyperopen/views/funding_modal_test.cljs` currently contains three tests, none of which render `:withdraw/form`.

- Observation: `lifecycle-panel` is above the repository function-length target because one helper owns both base rows and every optional notice block.
  Evidence: `/hyperopen/src/hyperopen/views/funding_modal.cljs` lines 161-231 currently keep stage, status, outcome, confirmations, queue position, explorer rendering, error messaging, and recovery hint rendering in one function.

- Observation: `withdraw-content` exceeds the architecture guardrail for function size and mixes two different concerns: static form layout and optional HyperUnit-only detail rendering.
  Evidence: `/hyperopen/src/hyperopen/views/funding_modal.cljs` lines 619-753 contain asset selection, address input, amount input, summary rows, queue messaging, protocol address display, lifecycle rendering, and the action row in one function.

- Observation: The withdraw queue normalizer defaults a missing queue length to `0`, which means a public `funding-modal-view` render cannot naturally show the `:error` queue-state branch while also preserving last-operation metadata.
  Evidence: `/hyperopen/src/hyperopen/funding/domain/lifecycle.cljs` `normalize-withdraw-queue-entry` maps absent `:withdrawal-queue-length` to `0`, and `/hyperopen/src/hyperopen/funding/application/modal_vm.cljs` `withdrawal-queue-state` treats any numeric queue length as `:ready`.

- Observation: After the refactor and test additions, the module-level CRAP report no longer contains any functions above the threshold.
  Evidence: `npm run crap:report -- --module src/hyperopen/views/funding_modal.cljs --format json` reported `"crappy-functions" : 0`, `"project-crapload" : 0.0`, `lifecycle-panel` at `CRAP 5.0`, and module `max-crap` at `12.0`.

## Decision Log

- Decision: Keep this issue scoped to `/hyperopen/src/hyperopen/views/funding_modal.cljs` and `/hyperopen/test/hyperopen/views/funding_modal_test.cljs` instead of also reducing CRAP in the much larger modal view-model namespace.
  Rationale: `hyperopen-1qj` was filed specifically for the funding modal view hotspots. The giant view-model hotspot already has its own follow-up issue, and combining both would turn a contained remediation into a broad refactor.
  Date/Author: 2026-03-07 / Codex

- Decision: Preserve the exact user-visible copy, data roles, actions, and DOM ordering while splitting helpers.
  Rationale: This is a maintainability and regression-hardening task, not a UI redesign. Keeping the rendered contract stable minimizes risk and makes tests precise.
  Date/Author: 2026-03-07 / Codex

- Decision: Add behavior-focused view tests and only use direct helper coverage where the public view path would make assertions unnecessarily brittle.
  Rationale: The user-visible contract here is the rendered modal tree. Covering most branches through `funding-modal-view` gives better long-term protection than testing internal helper return values in isolation.
  Date/Author: 2026-03-07 / Codex

- Decision: Cover the queue-error copy path through `@#'view/withdraw-content` instead of forcing it through `funding-modal-view`.
  Rationale: The real modal view-model normalizes a missing queue length to `0`, which collapses that public path to `:ready`. Directly exercising the render helper was the smallest way to cover the supported branch without changing production semantics.
  Date/Author: 2026-03-07 / Codex

## Outcomes & Retrospective

Implementation is complete pending final issue closure and file move. `/hyperopen/src/hyperopen/views/funding_modal.cljs` now breaks the old hotspot logic into smaller helpers and `/hyperopen/test/hyperopen/views/funding_modal_test.cljs` covers the previously untested withdraw and lifecycle branches.

The measured outcome is stronger than the issue target. The original issue called out `withdraw-content` at `CRAP 342.00` and `lifecycle-panel` at `CRAP 117.30`. After the change, the refreshed module report showed:

- `lifecycle-panel` => `CRAP 5.0`, `complexity 5`, `coverage 1.0`
- no remaining `withdraw-content` hotspot entry above threshold because the logic now lives in smaller helpers
- module summary => `crappy-functions 0`, `project-crapload 0.0`, `max-crap 12.0`

Validation also passed:

- `npm test`
- `npm run check`
- `npm run test:websocket`
- `npm run coverage`

No new follow-up issue was required from this remediation. Residual low-coverage helper functions remain below the CRAP threshold and are not blocking this issue.

## Context and Orientation

The funding modal view lives in `/hyperopen/src/hyperopen/views/funding_modal.cljs`. It is a pure rendering namespace that receives normalized modal state from `hyperopen.funding.actions/funding-modal-view-model` and returns Hiccup-like data for the UI runtime. The two functions called out by `hyperopen-1qj` are:

- `lifecycle-panel`, which renders optional lifecycle rows such as outcome, confirmations, queue position, destination transaction hash, next-check copy, error copy, and recovery guidance.
- `withdraw-content`, which renders the withdraw form, including the asset selector, destination field, amount field, HyperUnit summary details, lifecycle panel, and submit/cancel row.

Current view tests live in `/hyperopen/test/hyperopen/views/funding_modal_test.cljs`. They already include utilities that walk the rendered tree (`find-first-node`, `collect-strings`). Those helpers should be reused instead of creating a second tree-inspection style.

The normalized data shape for the withdraw flow comes from `/hyperopen/src/hyperopen/funding/application/modal_vm.cljs`. That namespace already has tests proving the view-model supplies fields such as:

- `[:withdraw :flow :kind]` to distinguish HyperUnit vs standard withdraws
- `[:withdraw :flow :withdrawal-queue]` for queue state, queue length, and last queue operation metadata
- `[:withdraw :flow :protocol-address]` for HyperUnit protocol address display
- `[:withdraw :lifecycle]` for lifecycle stage, status, outcome, transaction hash, error copy, and recovery hint

This issue does not change how those values are derived. It only changes how the view composes them into smaller, easier-to-cover render helpers.

## Plan of Work

Milestone 1 refactors the lifecycle panel. In `/hyperopen/src/hyperopen/views/funding_modal.cljs`, split `lifecycle-panel` into small helpers with single responsibilities: one helper for simple label/value rows, one helper for the optional outcome row, one helper for the optional confirmations and queue-position rows, one helper for the destination transaction block, and one helper for error and recovery notices. Keep the wrapper node and `data-role` untouched so current consumers and tests still find the same panel.

Milestone 2 refactors the withdraw form. In the same file, split `withdraw-content` into focused helpers: asset selector card, destination label/input block, queue-and-fee summary details, last-queue-transaction link/text rendering, and protocol-address card. The top-level `withdraw-content` should become a short coordinator that assembles the existing layout rather than owning all conditional logic itself.

Milestone 3 adds focused tests in `/hyperopen/test/hyperopen/views/funding_modal_test.cljs`. Add one HyperUnit withdraw render that exercises the queue row, explorer link, queue-error copy, fee-error copy, protocol address card, and lifecycle panel outcome/error/recovery states. Add one standard withdraw render that proves the generic destination label and `0x...` placeholder remain intact while HyperUnit-only sections stay absent. Add one lifecycle-specific rendering assertion that covers the non-link transaction-hash fallback path if it is not naturally covered by the public withdraw renders.

Milestone 4 validates and lands the work. Run the targeted funding modal tests while iterating, then run the required repository gates. Update this plan with what changed, what surprised us, and what commands passed. If the issue is fully complete, close `hyperopen-1qj` and move this plan to `/hyperopen/docs/exec-plans/completed/`.

## Concrete Steps

From `/hyperopen`:

1. Refactor the render helpers in `/hyperopen/src/hyperopen/views/funding_modal.cljs`.

2. Update `/hyperopen/test/hyperopen/views/funding_modal_test.cljs` with new withdraw and lifecycle assertions.

3. Run the focused test file during iteration:

       npm test -- --runInBand test/hyperopen/views/funding_modal_test.cljs

   Expected result: all funding modal view tests pass, including new HyperUnit and standard withdraw coverage.

4. Run the required repository gates:

       npm run check
       npm test
       npm run test:websocket

   Expected result: all three commands exit successfully without changing public funding modal behavior.

5. Refresh coverage and rerun the CRAP report for the target module:

       npm run coverage
       npm run crap:report -- --module src/hyperopen/views/funding_modal.cljs --format json

   Expected result: the funding modal module reports zero functions above the CRAP threshold.

6. Update `bd` and this ExecPlan with the implementation outcome.

## Validation and Acceptance

This issue is complete when all of the following are true:

1. `/hyperopen/src/hyperopen/views/funding_modal.cljs` no longer contains a monolithic `withdraw-content` or `lifecycle-panel`; the rendering logic is split into smaller named helpers with stable behavior.
2. A HyperUnit withdraw render shows the same user-visible details as before: withdraw queue, last queue transaction, fee/queue fallback messages, protocol address, lifecycle panel, and action row.
3. A standard withdraw render still shows the generic destination label and placeholder and does not render HyperUnit-only detail sections.
4. The lifecycle panel still supports both linked and plain-text destination transaction hashes.
5. `npm run check`, `npm test`, and `npm run test:websocket` all pass.

## Idempotence and Recovery

These edits are source-only refactors plus tests. Reapplying the steps is safe. If a helper extraction accidentally changes a render detail, compare the old and new Hiccup structure in the failing test and inline the smallest amount of logic back into the coordinator helper until the DOM contract matches again. If a view test becomes too brittle because it depends on incidental class ordering, narrow the assertion to the stable text, data role, or action payload instead of snapshotting the full tree.

## Artifacts and Notes

Relevant hotspot locations at the start of this plan:

- `/hyperopen/src/hyperopen/views/funding_modal.cljs:161` `lifecycle-panel`
- `/hyperopen/src/hyperopen/views/funding_modal.cljs:619` `withdraw-content`

Current test coverage baseline:

- `/hyperopen/test/hyperopen/views/funding_modal_test.cljs` covers deposit and anchored-popover behavior, but not the withdraw render path.

Parent issue context:

- `bd` issue `hyperopen-1qj`: "Reduce CRAP in funding modal interaction paths"
- discovered-from `hyperopen-5fq`, the CRAP tooling/reporting epic

## Interfaces and Dependencies

No new dependency is required.

The implementation should leave the existing public entry point unchanged:

    (hyperopen.views.funding-modal/funding-modal-view state)

Internal helper boundaries that should exist after the refactor are equivalent to:

    (defn- lifecycle-detail-row [label value] ...)
    (defn- lifecycle-panel-details [panel] ...)
    (defn- lifecycle-panel-notices [panel] ...)
    (defn- withdraw-asset-selector [assets selected-asset submitting?] ...)
    (defn- withdraw-destination-field [selected-asset flow destination submitting?] ...)
    (defn- withdraw-hyperunit-details [flow] ...)

Exact helper names may vary, but each helper should own one narrow rendering concern and remain easy to cover with the new tests.

Plan revision note: 2026-03-07 19:10Z - Initial issue-specific plan authored after claiming `hyperopen-1qj` and auditing the withdraw/lifecycle hotspots plus current view tests.
Plan revision note: 2026-03-07 19:21Z - Recorded the implemented helper split, queue-normalization testing discovery, passing validation gates, and the post-change CRAP report showing zero remaining hotspots in `funding_modal.cljs`.
