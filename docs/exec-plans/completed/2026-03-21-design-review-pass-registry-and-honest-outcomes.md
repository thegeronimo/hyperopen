# Design Review Pass Registry And Honest Outcomes

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-ks45`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

The browser-inspection design-review runner currently overstates what its visual pass proves, duplicates pass definitions across config, code, and tests, and stores audit failures as if the runner itself failed. The goal of this change is to make the audit semantics honest and easier to extend: the runner should execute from one pass registry, record explicit review outcome versus execution status, and produce summaries whose names and fields match the underlying evidence. After this work, a contributor should be able to add or adjust a pass in one place, trust that a completed run with findings is not misclassified as a crashed run, and inspect a summary that carries stable issue identifiers plus structured viewport and blind-spot data.

## Progress

- [x] (2026-03-21 20:02Z) Created and claimed `hyperopen-ks45` for the design-review refactor.
- [x] (2026-03-21 20:02Z) Audited `/hyperopen/AGENTS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/WORK_TRACKING.md`, and `/hyperopen/.agents/PLANS.md`.
- [x] (2026-03-21 20:02Z) Audited `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs`, its contracts, config, CLI wiring, and current runner tests.
- [x] (2026-03-21 20:39Z) Added `/hyperopen/tools/browser-inspection/src/design_review/` modules for models, pass registry, runtime ports, artifact writes, and markdown rendering; reduced `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs` to orchestration around those seams.
- [x] (2026-03-21 20:39Z) Renamed the visual pass to `visual-evidence-captured`, expanded pass-status taxonomy, added structured blind spots and viewport specs, and split `runStatus` from `reviewOutcome` while preserving `summary.state`.
- [x] (2026-03-21 20:39Z) Added focused regression coverage for registry/config parity, honest visual semantics, style-policy gaps, aggregate blocked-reason precedence, failed-viewport retention, and artifact-store finalization semantics.
- [x] (2026-03-21 20:40Z) Ran focused Node tests for browser-inspection design-review surfaces; all targeted tests passed.
- [x] (2026-03-21 20:47Z) Ran `npm run check`; the full check suite passed.
- [x] (2026-03-21 20:42Z) Ran `npm run test:websocket`; the websocket suite passed (`401` tests, `2297` assertions, `0` failures).
- [ ] Investigate or explicitly disposition the unrelated `npm test` blocker in `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs`, then close `hyperopen-ks45` if the repo gate is cleared or waived.

## Surprises & Discoveries

- Observation: the current runner has three separate pass-definition surfaces: `PASS_ORDER` in the runner, `design-review-defaults.json`, and multiple hard-coded expectations in tests and evaluation helpers.
  Evidence: `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs`, `/hyperopen/tools/browser-inspection/src/design_review_contracts.mjs`, `/hyperopen/tools/browser-inspection/test/design_review_runner.test.mjs`, and `/hyperopen/tools/browser-inspection/src/browser_qa_eval.mjs` each carry the pass list independently.

- Observation: a design-review run with product findings is persisted as a failed manifest even though the runner completed normally.
  Evidence: `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs` currently calls `failRun(run.runDir, summary.state)` whenever `summary.state !== "PASS"`.

- Observation: `inspectedViewports` currently stores only widths, which leaks a lossy reporting contract into evaluation helpers.
  Evidence: `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs` writes `viewports.map(([, viewport]) => viewport.width)`, and `/hyperopen/tools/browser-inspection/src/browser_qa_eval.mjs` assumes that array is numeric widths.

- Observation: the working tree contained a second partial refactor of the same runner seam, so the first implementation pass had to normalize competing interfaces before tests could run.
  Evidence: `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs`, `/hyperopen/tools/browser-inspection/src/design_review/runtime.mjs`, and `/hyperopen/tools/browser-inspection/src/design_review/artifact_store.mjs` briefly disagreed about whether runtime ports were per-probe or batch-style, and whether summary/pass writes were batched or per-pass.

- Observation: `npm test` currently fails in an unrelated CLJS UI contract area that this change does not touch.
  Evidence: `npm test` fails two assertions in `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs` expecting `gap-x-3` while the rendered classes include `gap-x-4`.

## Decision Log

- Decision: keep `runDesignReview(service, options)` as the public entrypoint while moving grading, artifact writes, rendering, and finalization into dedicated modules under `/hyperopen/tools/browser-inspection/src/design_review/`.
  Rationale: the user asked for architectural cleanup, not a public API rewrite. Preserving the entrypoint limits blast radius while still separating application, domain, infrastructure, and presentation concerns.
  Date/Author: 2026-03-21 / Codex

- Decision: rename the current visual pass to an evidence-oriented name instead of pretending it performs visual comparison.
  Rationale: the repository does not currently have governed reference-image comparison or agent judgment embedded in this pass. Renaming the pass is the honest short-term fix that matches the available evidence.
  Date/Author: 2026-03-21 / Codex

- Decision: add explicit `runStatus` and `reviewOutcome` fields while preserving the existing top-level `state` field as a compatibility alias of `reviewOutcome`.
  Rationale: nightly and CLI consumers currently branch on `summary.state`. The new fields fix the semantic leak without forcing every consumer to change in the same refactor.
  Date/Author: 2026-03-21 / Codex

- Decision: keep summary pass entries on the legacy top-level shape (`pass`, `status`, `issueCount`, `blockedReason`, `evidencePaths`) while expanding issue and blind-spot records underneath them.
  Rationale: downstream CLI, QA, and evaluation helpers can continue to read the roll-up list while the richer machine-facing data lands in `issues`, `blindSpots`, and `targetResults`.
  Date/Author: 2026-03-21 / Codex

## Outcomes & Retrospective

The core refactor succeeded. The design-review runner now executes from one registry, records honest visual-evidence semantics, stores structured blind spots and issue fingerprints, and finalizes completed audits separately from execution failures. Complexity decreased in the design-review toolchain because grading logic, artifact writes, markdown rendering, and run-finalization concerns now live in separate modules under `/hyperopen/tools/browser-inspection/src/design_review/` instead of being interleaved in one large script.

The remaining gap is repository validation rather than design-review behavior. `npm run check` and `npm run test:websocket` pass, and the focused browser-inspection tests pass. `npm test` still fails in `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs` on compact-density class expectations unrelated to this toolchain refactor, so `hyperopen-ks45` should stay open until that blocker is resolved or explicitly waived.

## Context and Orientation

The current implementation lives in `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs`. That file currently does four jobs at once. It orchestrates the run lifecycle, grades design-review passes, writes probe and summary artifacts to disk, and renders markdown output. The same file also exports a small CLI helper that does not belong in the review use case.

The browser-inspection toolchain already has reusable infrastructure nearby. `/hyperopen/tools/browser-inspection/src/artifact_store.mjs` owns persisted run manifests. `/hyperopen/tools/browser-inspection/src/service.mjs` owns snapshot persistence helpers. `/hyperopen/tools/browser-inspection/src/design_review_loader.mjs` resolves review config and routing. `/hyperopen/tools/browser-inspection/src/design_review_contracts.mjs` validates config, routing, and summary payloads.

In this plan, a “pass registry” means one ordered list of pass definitions that names each pass, describes the probes it needs, and supplies the grading function to call. A “review outcome” means the audit result from a completed run: `PASS`, `FAIL`, or `BLOCKED`. A “run status” means whether the runner itself completed or crashed: `completed` or `failed`. A “blind spot” means evidence the audit could not interpret honestly; it must not be silently reported as success.

The key compatibility constraints are:

First, `/hyperopen/tools/browser-inspection/src/cli.mjs`, `/hyperopen/tools/browser-inspection/src/qa_pr_ui.mjs`, and `/hyperopen/tools/browser-inspection/src/nightly_ui_qa.mjs` must still be able to reason about the overall result through `summary.state` while the new fields are introduced.

Second, `/hyperopen/tools/browser-inspection/src/browser_qa_eval.mjs` and `/hyperopen/tools/browser-inspection/evals/browser_qa_cases.mjs` will need coordinated updates if pass names or viewport shape change.

Third, the design-review config contract in `/hyperopen/tools/browser-inspection/src/design_review_contracts.mjs` must stop carrying its own independent pass order and instead derive parity from the registry.

## Plan of Work

Start by creating a new `/hyperopen/tools/browser-inspection/src/design_review/` module boundary. Add a domain module for explicit models and constructors, a pass-registry module that owns the ordered pass definitions and grading functions, an infrastructure module that owns review artifact paths and writes, a run-repository module that wraps the generic artifact store with `runRef` and `finalizeRun`, and a presentation module for markdown rendering. Then reduce `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs` to the application use case that coordinates those narrow ports.

The pass registry must become the single source of truth for pass names and order. `/hyperopen/tools/browser-inspection/src/design_review_contracts.mjs`, `/hyperopen/tools/browser-inspection/src/browser_qa_eval.mjs`, the design-review defaults, and dry-run outputs should all derive from that registry or validate against it instead of duplicating literal arrays. The current `visual` pass should be renamed to an honest evidence-oriented name, and the grade function must report the pass as a configuration gap when no design references exist rather than as a passing visual review.

Next, formalize the review data model. Issues should carry `targetId`, `ruleCode`, `fingerprint`, and `evidenceRefs`, while keeping `artifactPath` only as a compatibility alias for the primary evidence path. Blind spots should become structured records with a `reasonCode` and message, and the summary should carry explicit viewport objects instead of only widths. The overall summary should add `runStatus` and `reviewOutcome`, with `state` remaining an alias of `reviewOutcome` until downstream consumers are fully migrated.

Then fix the grading semantics called out in the feedback. Capture failures must create `capture-failure` issues instead of fake layout-overflow issues, and failed or blocked viewports must still appear under `targetResults`. Aggregated passes must not emit a blocked reason when the aggregate status is `FAIL`. Style grading must explicitly treat empty allowlists as configuration gaps and non-pixel values as unsupported-evaluation blind spots instead of silently allowing them. Remove probe collection that is not used for grading.

Finish by extending tests around the new seams and compatibility boundaries. The highest-value tests are the pass-registry/config parity contract, the honest visual pass naming and semantics, capture-failure viewport retention, style-policy explicitness, aggregate blocked-reason precedence, and run-finalization metadata for completed-but-failing reviews versus crashed runs.

## Concrete Steps

1. Create the new domain, infrastructure, and presentation modules under `/hyperopen/tools/browser-inspection/src/design_review/` and update `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs` to consume them.

2. Update `/hyperopen/tools/browser-inspection/src/design_review_contracts.mjs`, `/hyperopen/tools/browser-inspection/config/design-review-defaults.json`, `/hyperopen/tools/browser-inspection/src/browser_qa_eval.mjs`, and any evaluation fixtures so pass names and viewport shape stay consistent with the registry.

3. Extend `/hyperopen/tools/browser-inspection/test/design_review_runner.test.mjs` and add any new targeted test files needed for the new pure modules and artifact-store finalization semantics.

4. Run validation from `/hyperopen`:

   npm run check
   npm test
   npm run test:websocket

5. Record validation outcomes, changed files, remaining risks, and the final `bd` state back into this plan.

## Validation and Acceptance

Acceptance is behavior-based:

- `runDesignReview(..., { dryRun: true })` reports pass names from one registry, and the config contract agrees with that registry.
- A completed audit with findings records `runStatus: "completed"` and `reviewOutcome: "FAIL"` instead of persisting the whole run as a crash.
- A runtime exception still records `runStatus: "failed"`.
- The renamed visual pass reports that it captured evidence or that configuration is missing; it no longer claims visual correctness from screenshot existence alone.
- A viewport whose capture fails still appears in `summary.targetResults[*].viewports`.
- Aggregated pass output omits `blockedReason` when the aggregate status is `FAIL`.
- Style grading reports empty allowlists as configuration gaps and unsupported units as explicit blind spots rather than silent success.
- `npm run check`, `npm test`, and `npm run test:websocket` pass.

Validation results recorded on 2026-03-21:

- `node --test tools/browser-inspection/test/design_review_runner.test.mjs tools/browser-inspection/test/design_review_pass_registry.test.mjs tools/browser-inspection/test/design_review_loader.test.mjs tools/browser-inspection/test/artifact_store.test.mjs tools/browser-inspection/test/cli_contract.test.mjs`: PASS (`23` tests, `0` failures)
- `npm run check`: PASS
- `npm run test:websocket`: PASS (`401` tests, `2297` assertions, `0` failures)
- `npm test`: FAIL, but only in `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs`
  `account-info-table-rows-use-compact-density-classes-test` expected `gap-x-3`, got `gap-x-4`
  `account-info-table-headers-use-compact-density-classes-test` expected `gap-x-3`, got `gap-x-4`

## Idempotence and Recovery

This work is source-only and test-only. It is safe to re-run the commands and to reapply the refactor as long as the browser-inspection module layout has not been changed by someone else mid-session. If a new module split causes integration regressions, revert only the affected design-review files in the working tree, restore the previous runner behavior from the last passing state, and rerun the targeted tests before attempting the full validation set again.

## Artifacts and Notes

The tracked issue is `hyperopen-ks45`. The active plan file is `/hyperopen/docs/exec-plans/active/2026-03-21-design-review-pass-registry-and-honest-outcomes.md`.

The current implementation changed these areas:

- `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs`
- `/hyperopen/tools/browser-inspection/src/design_review_contracts.mjs`
- `/hyperopen/tools/browser-inspection/src/design_review/**`
- `/hyperopen/tools/browser-inspection/src/artifact_store.mjs`
- `/hyperopen/tools/browser-inspection/src/browser_qa_eval.mjs`
- `/hyperopen/tools/browser-inspection/config/design-review-defaults.json`
- `/hyperopen/tools/browser-inspection/evals/browser_qa_cases.mjs`
- `/hyperopen/tools/browser-inspection/test/design_review_*.test.mjs`
- `/hyperopen/tools/browser-inspection/test/artifact_store.test.mjs`
- `/hyperopen/tools/browser-inspection/test/cli_contract.test.mjs`
- `/hyperopen/tools/browser-inspection/test/design_review_loader.test.mjs`

The most important starting files are:

- `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs`
- `/hyperopen/tools/browser-inspection/src/design_review_contracts.mjs`
- `/hyperopen/tools/browser-inspection/src/design_review_loader.mjs`
- `/hyperopen/tools/browser-inspection/src/browser_qa_eval.mjs`
- `/hyperopen/tools/browser-inspection/src/artifact_store.mjs`
- `/hyperopen/tools/browser-inspection/src/service.mjs`
- `/hyperopen/tools/browser-inspection/test/design_review_runner.test.mjs`
- `/hyperopen/tools/browser-inspection/test/cli_contract.test.mjs`

## Interfaces and Dependencies

At the end of this work, `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs` should still export `runDesignReview(service, options)` and `aggregateSummaryState(passEntries)` for compatibility. The new internal modules should expose stable helpers for:

- a pass registry that returns ordered pass definitions
- constructors for issues, pass results, blind spots, target viewport results, and summaries
- a review artifact store that accepts a `runRef` instead of raw filesystem path concatenation in the use case
- a run repository that finalizes runs with both `runStatus` and `reviewOutcome`
- a markdown renderer dedicated to design-review summaries

Plan update note (2026-03-21): created the initial ExecPlan after auditing the current runner, config contract, downstream evaluation helpers, and required planning rules. The plan deliberately preserves the top-level design-review entrypoint while moving semantics and persistence details into explicit modules so the refactor stays additive and testable.

Plan update note (2026-03-21 20:47Z): implementation is complete and the focused browser-inspection validations plus `npm run check` and `npm run test:websocket` passed. The only remaining blocker is an unrelated `npm test` failure in the `account_info` compact-density contract tests, so the plan stays active until that suite is green or explicitly waived.
