# DeCRAP Vault And Funding Hotspots

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-55nd`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

The current top CRAP hotspots are concentrated in five pure or mostly pure seams that shape vault detail, vault list, and Hyperunit funding behavior. The goal is to reduce branch density and raise direct test coverage without changing public behavior, so the next contributor can modify these paths with lower regression risk and clearer local reasoning. After this pass, the same vault list/detail routes and funding flows should behave the same to users, but the implementation should be split into smaller helpers with targeted regressions that prove the intended fallback and normalization rules.

## Progress

- [x] (2026-03-20) Created and claimed `hyperopen-55nd` for this deCRAP slice.
- [x] (2026-03-20) Audited `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/FRONTEND.md`, and `/hyperopen/docs/agent-guides/browser-qa.md`.
- [x] (2026-03-20) Audited the five reported hotspots plus existing test coverage and gathered parallel review recommendations from two helper agents.
- [x] (2026-03-20) Refactored `protocol-address-matches-source-chain?` into protocol-specific validators, split `balance-row-available` into direct-versus-derived helpers, moved hover math into shared `/hyperopen/src/hyperopen/views/chart/hover.cljs`, and replaced `snapshot-range-keys` branching with constant lookup data.
- [x] (2026-03-20) Decomposed `vault-detail-vm` into smaller private builders for source resolution, benchmark/chart shaping, and activity collation while preserving cache-sensitive behavior.
- [x] (2026-03-20) Extended focused CLJS tests for protocol-address validation, transfer-balance fallback rules, vault detail hover behavior, snapshot-range fallback ordering, and vault-detail activity loading/error collation.
- [x] (2026-03-20) Installed missing JS dependencies with `npm ci`, then ran `npm run check`, `npm test`, `npm run test:websocket`, `npm run coverage`, and `npm run crap:report`.
- [x] (2026-03-20) Ran governed browser QA for `vaults-route` and `vault-detail-route`, recorded the evidence under `/hyperopen/tmp/browser-inspection/design-review-2026-03-20T15-42-30-800Z-7c1841eb`, and filed follow-up `hyperopen-ksuk` for the remaining `vault-detail-route` blocker.

## Surprises & Discoveries

- Observation: the repository already permits direct testing of private helper vars from CLJS tests, so the two lowest-coverage helpers can be covered directly instead of only through larger integration paths.
  Evidence: `/hyperopen/test/hyperopen/funding/effects_test.cljs` already reads private vars with `@#'hyperopen.funding.effects/...`.

- Observation: the vault detail hover math is duplicated almost verbatim in both vault and portfolio action namespaces, which means a shared helper reduces CRAP in one target and removes drift risk in the other.
  Evidence: `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs` and `/hyperopen/src/hyperopen/portfolio/actions.cljs` both define `finite-number`, `positive-point-count`, `clamp`, `hover-index-from-pointer`, and `normalize-hover-index`.

- Observation: `vault-detail-vm` already has broad route-level regression coverage, so the safest deCRAP move is extraction around existing values and cache calls rather than logic changes.
  Evidence: `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs` already covers route parsing, benchmark series, activity aggregation, transfer state, and cache reuse on unrelated writes.

- Observation: the local JS toolchain was incomplete before validation, so repo gates were blocked until dependencies were reinstalled.
  Evidence: the first `npm test` attempt failed because `shadow-cljs` was missing, and the next compile attempt failed on missing module `@noble/secp256k1`; `npm ci` resolved both issues and the subsequent validation passes succeeded.

- Observation: the governed browser-QA failure is isolated to `/vaults/detail` rather than the whole vaults surface.
  Evidence: `/hyperopen/tmp/browser-inspection/design-review-2026-03-20T15-42-30-800Z-7c1841eb/summary.json` reports `vaults-route` `PASS` at widths `375`, `768`, `1280`, and `1440`, while `vault-detail-route` fails because `review-375` timed out waiting for `HYPEROPEN_DEBUG` initialization and `review-768`, `review-1280`, and `review-1440` reported no reachable focus targets for the interaction pass.

## Decision Log

- Decision: keep this pass behavior-preserving and local rather than deduplicating every similar helper across funding, staking, and vault domains.
  Rationale: the reported task is explicitly a top-five deCRAP slice, and widening the refactor surface would increase risk without being necessary to retire the current hotspots.
  Date/Author: 2026-03-20 / Codex

- Decision: treat browser QA as required because two touched files live under `/hyperopen/src/hyperopen/views/**`, even though the planned edits are view-model-focused.
  Rationale: `/hyperopen/docs/FRONTEND.md` requires the governed browser-QA flow for UI-facing changes under that tree, so the final result must either pass or explicitly report a blocked condition with evidence.
  Date/Author: 2026-03-20 / Codex

- Decision: extract `vault-detail-vm` into smaller private section builders while keeping cache reads and route parsing at top-level call sites.
  Rationale: the existing cache-reuse regression depends on identity-sensitive inputs, so moving cache boundaries would add risk without helping the CRAP target.
  Date/Author: 2026-03-20 / Codex

## Outcomes & Retrospective

This pass achieved the intended CRAP reduction without widening the behavior surface:

- the original five hotspots were either reduced directly or replaced by narrower helpers with direct coverage
- targeted regression tests now pin the most failure-prone fallback and normalization seams
- `vault-detail-vm` keeps its route-level contract and cache reuse behavior, but the assembly logic is split into smaller private sections
- required repo gates passed after restoring JS dependencies
- governed browser QA is explicitly accounted for: `vaults-route` passed at all required widths, while `vault-detail-route` remains blocked and is tracked in `hyperopen-ksuk`

Post-change CRAP results for the user-reported targets were:

- `/hyperopen/src/hyperopen/funding/effects.cljs` `protocol-address-matches-source-chain?`: CRAP `4.0`, complexity `4`, coverage `1.0`
- `/hyperopen/src/hyperopen/vaults/detail/transfer.cljs` `balance-row-available`: CRAP `4.0`, complexity `4`, coverage `1.0`
- `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs` `hover-index-from-pointer`: removed from that namespace in favor of shared `/hyperopen/src/hyperopen/views/chart/hover.cljs`
- `/hyperopen/src/hyperopen/views/chart/hover.cljs` `hover-index-from-pointer`: CRAP `7.0`, complexity `7`, coverage `1.0`
- `/hyperopen/src/hyperopen/views/vaults/vm.cljs` `snapshot-range-keys`: CRAP `1.0`, complexity `1`, coverage `1.0`
- `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` `vault-detail-vm`: CRAP `11.0`, complexity `11`, coverage `1.0`

The original five hotspots no longer appear in the repo-level `top_functions` output from `npm run crap:report`.

## Context and Orientation

The five starting hotspots are:

1. `/hyperopen/src/hyperopen/funding/effects.cljs` `protocol-address-matches-source-chain?`
2. `/hyperopen/src/hyperopen/vaults/detail/transfer.cljs` `balance-row-available`
3. `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs` `hover-index-from-pointer`
4. `/hyperopen/src/hyperopen/views/vaults/vm.cljs` `snapshot-range-keys`
5. `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` `vault-detail-vm`

The funding helper validates whether a candidate protocol address shape matches the selected source chain before the Hyperunit flow reuses an existing deposit address. The transfer helper computes the withdrawable or depositable balance from a balance row that may expose direct availability fields or only total-plus-hold fields. The vault detail command helper converts pointer coordinates into a hovered chart point index. The vault list helper chooses the fallback order of snapshot keys for a selected time range. The vault detail view model assembles the route-level model for `/vaults/detail`, including chart data, benchmark context, transfer state, activity tables, and error/loading summaries.

The relevant tests already exist in:

- `/hyperopen/test/hyperopen/funding/effects_test.cljs`
- `/hyperopen/test/hyperopen/vaults/detail/transfer_test.cljs`
- `/hyperopen/test/hyperopen/vaults/actions_test.cljs`
- `/hyperopen/test/hyperopen/views/vaults/vm_test.cljs`
- `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs`
- `/hyperopen/test/hyperopen/portfolio/actions_test.cljs`

## Plan of Work

First, reduce the two lowest-coverage helper hotspots in funding and transfer. In `/hyperopen/src/hyperopen/funding/effects.cljs`, replace the large address-shape branch with small validators for Bitcoin, EVM-style, and Solana addresses, then keep `protocol-address-matches-source-chain?` as the dispatch point. In `/hyperopen/src/hyperopen/vaults/detail/transfer.cljs`, split direct-availability extraction from total-minus-hold derivation and keep one final clamp for the chosen numeric value.

Next, remove duplicated chart-hover math by introducing a shared helper under `/hyperopen/src/hyperopen/views/chart/**` and making both `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs` and `/hyperopen/src/hyperopen/portfolio/actions.cljs` delegate to it. Extend the existing portfolio hover regression and add the matching vault hover regression in `/hyperopen/test/hyperopen/vaults/actions_test.cljs`.

Then, flatten `/hyperopen/src/hyperopen/views/vaults/vm.cljs` `snapshot-range-keys` into constant data keyed by normalized range, and extend `/hyperopen/test/hyperopen/views/vaults/vm_test.cljs` with explicit day, week, and default-order checks.

Finally, decompose `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` by extracting private builders for detail context, benchmark/chart sections, activity sections, and final response shaping. The cache calls must stay in the top-level view-model flow so the existing identity-based cache regression remains valid. Any new test added here should focus on uncovered seams such as child-address activity source precedence or loading/error collation, not on restating already-covered route output.

## Concrete Steps

1. Edit `/hyperopen/src/hyperopen/funding/effects.cljs` and `/hyperopen/test/hyperopen/funding/effects_test.cljs` to split protocol-address validation into narrow validators and add direct helper coverage for supported and unsupported address shapes.

2. Edit `/hyperopen/src/hyperopen/vaults/detail/transfer.cljs` and `/hyperopen/test/hyperopen/vaults/detail/transfer_test.cljs` to separate direct-availability reads from derived availability and add regressions for field precedence, fallback fields, clamping, and invalid rows.

3. Add a shared chart-hover helper under `/hyperopen/src/hyperopen/views/chart/` and update both `/hyperopen/src/hyperopen/portfolio/actions.cljs` and `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs` to use it. Extend `/hyperopen/test/hyperopen/portfolio/actions_test.cljs` only if behavior needs to stay pinned there, and add missing vault hover coverage in `/hyperopen/test/hyperopen/vaults/actions_test.cljs`.

4. Edit `/hyperopen/src/hyperopen/views/vaults/vm.cljs` and `/hyperopen/test/hyperopen/views/vaults/vm_test.cljs` to replace the `case` with constant lookup data and prove the fallback order for `:day`, `:week`, and normalized unknown input.

5. Edit `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` and, if needed, `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs` to split the view-model assembly into smaller private sections while preserving the current return map and cache-sensitive execution order.

6. Run validation from `/hyperopen`:

   npm run check
   npm test
   npm run test:websocket
   npm run qa:design-ui -- --targets vaults-route,vault-detail-route --manage-local-app

7. Record the final changed files, validation results, browser-QA pass matrix, and any residual risks back into this plan.

## Validation and Acceptance

Acceptance is behavior-based:

- existing Hyperunit deposit-address selection still accepts Bitcoin, EVM-style, and Solana protocol addresses only when they match the chosen source chain
- vault transfer deposit-max and availability calculations still prefer the same direct fields and fallback derivations as before
- vault detail chart hover still resolves the same nearest-point indices and nil-pointer fallback behavior as the portfolio chart
- vault list snapshot selection still falls back in the same order for short and extended time ranges
- `vault-detail-vm` still returns the same route-level sections, keeps benchmark/chart data intact, and continues to skip cache invalidation on unrelated state changes
- `npm run check`, `npm test`, and `npm run test:websocket` pass
- governed browser QA explicitly accounts for all six passes at widths `375`, `768`, `1280`, and `1440` for both `vaults-route` and `vault-detail-route`

Validation results recorded on 2026-03-20:

- `npm ci`: PASS
- `npm run check`: PASS
- `npm test`: PASS (`2553` tests, `13591` assertions, `0` failures, `0` errors)
- `npm run test:websocket`: PASS (`398` tests, `2271` assertions, `0` failures, `0` errors)
- `npm run coverage`: PASS (`Statements 91.02%`, `Branches 68.79%`, `Functions 85.51%`, `Lines 91.02%`)
- `npm run crap:report`: PASS; none of the original five targets remain in repo-level `top_functions`
- governed browser QA: mixed result
  `vaults-route`: `PASS` for visual, native-control, styling-consistency, interaction, layout-regression, and jank-perf at `375`, `768`, `1280`, and `1440`
  `vault-detail-route`: `FAIL` overall because `review-375` timed out waiting for `HYPEROPEN_DEBUG` initialization and `review-768`/`1280`/`1440` were `BLOCKED` on the interaction pass due to no reachable focus targets

## Idempotence and Recovery

These edits are source-only refactors and focused tests, so they are safe to re-run and reapply as long as the target functions still exist. If a refactor causes a regression, restore the affected helper to the last known passing structure, rerun the targeted test surface first, and only then rerun the full repo gates. Browser QA should be rerun after any change to the touched view-model files because those files feed the inspected routes.

## Artifacts and Notes

The tracked issue is `hyperopen-55nd`. The machine-readable spec artifact for this pass lives at `/hyperopen/tmp/multi-agent/hyperopen-55nd/spec.json`.

Governed browser-QA artifacts for this pass live under `/hyperopen/tmp/browser-inspection/design-review-2026-03-20T15-42-30-800Z-7c1841eb`, including `summary.json`, `summary.md`, and `browser-report.json`. Follow-up `hyperopen-ksuk` tracks the remaining `/vaults/detail` QA blocker discovered during this pass.

The starting CRAP candidates reported by the user were:

- `/hyperopen/src/hyperopen/funding/effects.cljs` line 277, CRAP `182.00`, coverage `0.00`, complexity `13`
- `/hyperopen/src/hyperopen/vaults/detail/transfer.cljs` line 43, CRAP `57.37`, coverage `0.16`, complexity `9`
- `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs` line 267, CRAP `56.00`, coverage `0.00`, complexity `7`
- `/hyperopen/src/hyperopen/views/vaults/vm.cljs` line 91, CRAP `55.88`, coverage `0.17`, complexity `9`
- `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` line 376, CRAP `52.01`, coverage `0.98`, complexity `52`

## Interfaces and Dependencies

The resulting source tree should expose the same public functions and route contracts as before. No public API changes are planned. New helper functions may be private to their namespace, except for a shared chart-hover helper namespace that both portfolio and vault action namespaces can call. That helper must accept the same raw pointer inputs already used by the action handlers: `client-x`, a bounds map with `:left` and `:width`, and a positive point count.

Plan update note (2026-03-20): created the initial ExecPlan after auditing the reported hotspots, current tests, repo planning rules, and UI QA contract. The plan intentionally narrows the work to behavior-preserving extraction plus focused regressions so the CRAP reduction does not widen into a broader architecture refactor.
