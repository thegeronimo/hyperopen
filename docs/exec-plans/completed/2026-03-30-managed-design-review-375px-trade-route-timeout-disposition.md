# Disposition Managed Design-Review 375px Trade-Route Timeout

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The tracked work item for this plan is `hyperopen-x83o`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the investigation and any implementation follow-up.

## Purpose / Big Picture

This bead does not currently read like an implementation ticket. It reads like a stale or intermittent browser-tooling follow-up that was opened from one failing managed-local design-review run. The useful outcome here is not “change the trade page.” The useful outcome is: a contributor can tell, with current evidence, whether the March 30 `review-375` timeout still exists, and then either close the bead honestly or land the smallest browser-inspection fix that makes the managed `/trade` review trustworthy again.

After this work, a contributor should be able to run the governed review commands for `/trade` under `--manage-local-app`, observe either a clean `PASS` at `375`, `768`, `1280`, and `1440` or a reproducible tooling failure with a precise cause, and know whether `hyperopen-x83o` should be closed, retitled, or implemented as a narrow browser-inspection hardening patch.

## Progress

- [x] (2026-03-30 23:06Z) Claimed `hyperopen-x83o` in `bd` so the active plan and the live work item are aligned.
- [x] (2026-03-30 23:06Z) Read `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/WORK_TRACKING.md`, and `/hyperopen/docs/BROWSER_TESTING.md`.
- [x] (2026-03-30 23:07Z) Audited the current managed-local startup and capture path in `/hyperopen/tools/browser-inspection/src/session_manager.mjs`, `/hyperopen/tools/browser-inspection/src/local_app_manager.mjs`, `/hyperopen/tools/browser-inspection/src/capture_pipeline.mjs`, `/hyperopen/tools/browser-inspection/src/design_review/runtime.mjs`, and `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs`.
- [x] (2026-03-30 23:07Z) Confirmed via read-only subagent audit that the reported timeout occurs before any screenshot or probe capture and therefore points at managed-local bootstrap readiness, not a trade-route UI regression.
- [x] (2026-03-30 23:08Z) Restored missing local JavaScript dependencies with `npm ci` after the first repro attempt failed before startup because `pixelmatch` was not installed in this worktree.
- [x] (2026-03-30 23:08Z) Re-ran the exact bead repro command `npm run qa:design-ui -- --targets trade-route --manage-local-app`; the current worktree passed at `review-375`, `review-768`, `review-1280`, and `review-1440` with artifact root `/Users/barry/.codex/worktrees/5004/hyperopen/tmp/browser-inspection/design-review-2026-03-30T23-07-47-546Z-29f633c1`.
- [x] (2026-03-30 23:10Z) Re-ran the narrower first-viewport command `npm run qa:design-ui -- --targets trade-route --viewports review-375 --manage-local-app`; the current worktree again passed with artifact root `/Users/barry/.codex/worktrees/5004/hyperopen/tmp/browser-inspection/design-review-2026-03-30T23-09-49-715Z-f4b2121e`.
- [x] (2026-03-30 23:24Z) Closed `hyperopen-x83o` in `bd` as completed because the original artifact was unavailable and two fresh managed-local reruns passed on the current tree.
- [x] (2026-03-30 23:25Z) Confirmed that no browser-inspection or product code changes are justified without a fresh failing artifact, so the correct close-out is disposition rather than implementation.
- [x] (2026-03-30 23:25Z) Finalized this plan for `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: the current issue description points at a failing run id from another worktree, but that artifact bundle is no longer present in the accessible `/Users/barry/.codex/worktrees/**` paths.
  Evidence: searching for `design-review-2026-03-30T13-45-00-469Z-571312b7` in the current worktree and sibling worktrees returned no artifact directory.

- Observation: the first attempt to reproduce the bead in this worktree failed before any browser work because local `node_modules` state was incomplete.
  Evidence: `npm run qa:design-ui -- --targets trade-route --manage-local-app` initially exited with `Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'pixelmatch' imported from /hyperopen/tools/browser-inspection/src/parity_compare.mjs`, while `/hyperopen/package.json` and `/hyperopen/package-lock.json` both already declare `pixelmatch`.

- Observation: on the current tree, the exact full-matrix managed-local repro passes, so the bead is not an immediately reproducible trade-route bug.
  Evidence: `/Users/barry/.codex/worktrees/5004/hyperopen/tmp/browser-inspection/design-review-2026-03-30T23-07-47-546Z-29f633c1/summary.json` reports `reviewOutcome: "PASS"` with successful capture and probe artifacts for `review-375`, `review-768`, `review-1280`, and `review-1440`.

- Observation: the narrower first-viewport-only run also passes, which weakens the case for an active cold-start race in the current tree.
  Evidence: `/Users/barry/.codex/worktrees/5004/hyperopen/tmp/browser-inspection/design-review-2026-03-30T23-09-49-715Z-f4b2121e/summary.json` reports `reviewOutcome: "PASS"` for `review-375`.

- Observation: a `Timed out waiting for HYPEROPEN_DEBUG to initialize.` failure would happen before any design-review pass grading and would therefore be a managed-local startup issue, not a visual review finding.
  Evidence: `/hyperopen/tools/browser-inspection/src/capture_pipeline.mjs` calls `navigateAttachedTarget(...)` before network-idle wait, snapshot extraction, or screenshot capture, and `/hyperopen/tools/browser-inspection/src/session_manager.mjs` only dispatches `[:actions/navigate ...]` after `waitForDebugBridge(...)` succeeds.

- Observation: the current failure classifier does not recognize the specific `HYPEROPEN_DEBUG` startup timeout string as an automation-gap signature.
  Evidence: `/hyperopen/tools/browser-inspection/src/failure_classification.mjs` classifies loopback bind, unreachable app, attach endpoint, and Chrome startup failures, but not `Timed out waiting for HYPEROPEN_DEBUG to initialize.`.

## Decision Log

- Decision: treat `hyperopen-x83o` as a disposition ticket first, not as a presumed trade-route regression.
  Rationale: both fresh managed-local repro commands now pass on the current tree, so speculative product edits would add churn without evidence.
  Date/Author: 2026-03-30 / Codex

- Decision: keep any possible implementation follow-up inside browser-inspection startup, capture, or failure-classification seams instead of `/hyperopen/src/**` unless a fresh failing artifact proves a product regression.
  Rationale: the reported timeout happens before route capture and before any governed pass inspects `/trade`.
  Date/Author: 2026-03-30 / Codex

- Decision: require a fresh failing artifact before changing retry budgets, startup policy, or failure classification.
  Rationale: the prior March 24 startup-readiness work already hardened this seam once, and the current tree passes the reported repro. A new code change should be justified by a current failure, not by an expired artifact reference.
  Date/Author: 2026-03-30 / Codex

- Decision: if the only remaining problem is outcome labeling for a reproducible startup timeout, prefer fixing browser-inspection classification and tests over modifying the trade route.
  Rationale: the honest outcome for an infrastructure startup timeout is a tooling/automation report, not a fake product failure and not a UI patch.
  Date/Author: 2026-03-30 / Codex

- Decision: close `hyperopen-x83o` without code changes.
  Rationale: the current worktree reproduces neither the reported `review-375` timeout nor any broader managed-local `trade-route` startup failure, so keeping the bead open would imply work that current evidence does not justify.
  Date/Author: 2026-03-30 / Codex

## Outcomes & Retrospective

Final outcome: `hyperopen-x83o` was closed without code changes. The current evidence does not say “the 375px trade route is broken.” It says “a prior managed-local audit once failed at the debug-bridge bootstrap seam, but the same commands currently pass on the current tree.” The original failing artifact is unavailable, and both fresh reruns passed, so the honest result is disposition rather than speculative implementation.

Complexity decreased. Closing a stale bead with fresh proof removes an unnecessary open issue and avoids churn in `tools/browser-inspection` or `/hyperopen/src/**`. If the timeout reappears in the future, the remaining likely target is still narrow: session bootstrap, failure classification, and focused tests, not a broad route or UI rewrite.

## Context and Orientation

In this repository, “design review” means the governed browser-inspection audit run exposed through `npm run qa:design-ui`. A “managed-local” run means the tool starts the local Hyperopen dev app itself, opens a temporary Chrome session, loads `/index.html`, waits for the dev-only browser debug bridge `globalThis.HYPEROPEN_DEBUG`, and only then dispatches a route navigation such as `[:actions/navigate "/trade"]`.

The current issue description for `hyperopen-x83o` says that a managed-local review for `trade-route` passed at `768`, `1280`, and `1440` but failed at `375` because `/trade` timed out waiting for `HYPEROPEN_DEBUG` to initialize. In code, that seam lives in these files:

- `/hyperopen/tools/browser-inspection/src/session_manager.mjs`
  This file owns `waitForDebugBridge(...)`, `navigateAttachedTarget(...)`, bootstrap retries, and candidate-origin fallback.

- `/hyperopen/tools/browser-inspection/src/local_app_manager.mjs`
  This file owns managed-local process startup, HTTP-server URL discovery from Shadow logs, and the first “local app is reachable” readiness check.

- `/hyperopen/tools/browser-inspection/src/capture_pipeline.mjs`
  This file owns snapshot capture. Its `captureSnapshot(...)` function navigates first and captures screenshot, DOM snapshot, and debug snapshot only after navigation and idle waits succeed.

- `/hyperopen/tools/browser-inspection/src/design_review/runtime.mjs`
  This file maps capture failures into design-review pass status. It currently only marks known infrastructure signatures as `TOOLING_GAP`.

- `/hyperopen/tools/browser-inspection/src/design_review_runner.mjs`
  This file owns target and viewport orchestration. If capture fails, it records a `capture-failure` issue for that viewport.

- `/hyperopen/tools/browser-inspection/src/failure_classification.mjs`
  This file owns the shared automation-gap signature list. It currently does not classify the `HYPEROPEN_DEBUG` startup timeout message.

The most relevant focused tests today are:

- `/hyperopen/tools/browser-inspection/test/session_manager.test.mjs`
- `/hyperopen/tools/browser-inspection/test/local_app_manager.test.mjs`
- `/hyperopen/tools/browser-inspection/test/design_review_runner.test.mjs`
- `/hyperopen/tools/browser-inspection/test/failure_classification.test.mjs`
- `/hyperopen/tools/browser-inspection/test/smoke.test.mjs`

The current fresh evidence shows two passing managed-local runs in this worktree:

- full matrix: `/Users/barry/.codex/worktrees/5004/hyperopen/tmp/browser-inspection/design-review-2026-03-30T23-07-47-546Z-29f633c1`
- focused `review-375`: `/Users/barry/.codex/worktrees/5004/hyperopen/tmp/browser-inspection/design-review-2026-03-30T23-09-49-715Z-f4b2121e`

Because both now pass, the next contributor must treat the issue as “prove whether work remains” rather than “assume code must change.”

## Plan of Work

Start from the current pass evidence, not from the old failing run id. The first task is to decide whether `hyperopen-x83o` is stale or intermittent. Re-run the exact full-matrix command and the focused `review-375` command from `/hyperopen` after confirming local dependencies are installed with `npm ci`. Keep the new run directories. If both runs still pass, do not change browser-inspection code yet. Instead, update this plan and the `bd` issue notes so they explain that the current tree no longer reproduces the timeout and that the old artifact is unavailable. Then close the bead as completed or retitle it only if there is a concrete remaining gap such as classification semantics.

Only continue into implementation if a fresh managed-local run reproduces the timeout. When that happens, inspect the failing summary and determine which of two narrow problems you actually have. If the route never captures because `waitForDebugBridge(...)` exhausts its budget, work should stay in `/hyperopen/tools/browser-inspection/src/session_manager.mjs`, `/hyperopen/tools/browser-inspection/src/local_app_manager.mjs`, and their focused tests. If capture fails only because the timeout is misreported as a product failure instead of a tooling gap, work should stay in `/hyperopen/tools/browser-inspection/src/failure_classification.mjs`, `/hyperopen/tools/browser-inspection/src/design_review/runtime.mjs`, and their tests.

If a code change is necessary, keep it minimal and observable. Do not touch `/hyperopen/src/hyperopen/views/**` or other trade UI files unless a fresh artifact proves the route itself regressed. For startup hardening, prefer adjustments that are already consistent with the existing architecture: bounded bootstrap retries, candidate-origin fallback, or a clearly named debug-bridge timeout policy in config. For classification hardening, teach the shared failure classifier to recognize the `HYPEROPEN_DEBUG` startup timeout as an automation-gap signature and add a test that proves design-review maps the resulting capture failure to `TOOLING_GAP` or `BLOCKED` semantics rather than a fake product `FAIL`.

Finish by running the smallest focused test surface first, then the managed-local design-review command again, then the required repository gates only if tracked code changed. Record the final artifact paths and close or narrow the `bd` issue accordingly.

## Concrete Steps

Run these commands from `/Users/barry/.codex/worktrees/5004/hyperopen`.

1. Ensure the local JavaScript toolchain is present:

   npm ci

   Expected outcome: `node_modules/pixelmatch` exists, and `npm run qa:design-ui ...` no longer fails with `ERR_MODULE_NOT_FOUND`.

2. Reproduce the full managed-local review:

   npm run qa:design-ui -- --targets trade-route --manage-local-app

   Current known-good result from this worktree:

   runId: `design-review-2026-03-30T23-07-47-546Z-29f633c1`
   reviewOutcome: `PASS`
   viewports: `review-375`, `review-768`, `review-1280`, `review-1440`

3. Reproduce the narrow first-viewport seam:

   npm run qa:design-ui -- --targets trade-route --viewports review-375 --manage-local-app

   Current known-good result from this worktree:

   runId: `design-review-2026-03-30T23-09-49-715Z-f4b2121e`
   reviewOutcome: `PASS`

4. Branch on the result:

   If both runs pass, update this plan with the fresh evidence and close or narrow `hyperopen-x83o` without changing code.

   If either run fails with `Timed out waiting for HYPEROPEN_DEBUG to initialize.`, inspect:

   - `/hyperopen/tools/browser-inspection/src/session_manager.mjs`
   - `/hyperopen/tools/browser-inspection/src/local_app_manager.mjs`
   - `/hyperopen/tools/browser-inspection/src/failure_classification.mjs`
   - the fresh `summary.json` and any `capture-failure` issue entry in the new run directory

5. If code changes are required, run the focused tests first:

   node --test tools/browser-inspection/test/session_manager.test.mjs tools/browser-inspection/test/local_app_manager.test.mjs tools/browser-inspection/test/design_review_runner.test.mjs tools/browser-inspection/test/failure_classification.test.mjs

6. Re-run the smallest relevant browser validation after the fix:

   npm run qa:design-ui -- --targets trade-route --viewports review-375 --manage-local-app

   Then broaden back to:

   npm run qa:design-ui -- --targets trade-route --manage-local-app

7. When tracked code changed, finish with the required repo gates:

   npm run check
   npm test
   npm run test:websocket

## Validation and Acceptance

Acceptance for the current bead is one of two honest outcomes.

The first acceptable outcome is disposition without code changes. That requires:

- `npm ci` succeeds in the current worktree.
- `npm run qa:design-ui -- --targets trade-route --manage-local-app` returns `reviewOutcome: "PASS"` with successful `review-375`, `review-768`, `review-1280`, and `review-1440` captures.
- `npm run qa:design-ui -- --targets trade-route --viewports review-375 --manage-local-app` returns `reviewOutcome: "PASS"`.
- This plan and the `bd` bead reflect that the timeout is not reproducible on the current tree.

The second acceptable outcome is a narrow tooling fix. That requires:

- a fresh failing artifact that reproduces the timeout on the current tree
- a targeted change inside browser-inspection startup or failure-classification seams
- focused Node tests covering the changed seam
- a passing rerun of the focused `review-375` managed-local command
- a passing rerun of the full managed-local `trade-route` review
- `npm run check`, `npm test`, and `npm run test:websocket` if tracked code changed

Non-acceptance cases:

- changing trade UI code without a fresh product-facing failure artifact
- closing the bead based only on the old inaccessible run id
- changing timeout budgets or classification semantics without adding focused regression coverage

## Idempotence and Recovery

`npm ci` is safe to rerun. The managed-local browser-review commands create timestamped artifact directories under `/hyperopen/tmp/browser-inspection/`, so repeating them does not overwrite prior evidence. If a managed-local run attaches to the wrong environment or fails for unrelated local-process reasons, stop the spawned local app, keep the artifact directory, and rerun the same command rather than deleting evidence.

If a code change is started and the timeout cannot be reproduced again, stop the implementation, revert only the browser-inspection files touched for this bead, and return to the disposition path. This bead should not accumulate speculative hardening that lacks a current failing proof.

## Artifacts and Notes

Fresh current-tree evidence:

- full matrix pass: `/Users/barry/.codex/worktrees/5004/hyperopen/tmp/browser-inspection/design-review-2026-03-30T23-07-47-546Z-29f633c1`
- focused `review-375` pass: `/Users/barry/.codex/worktrees/5004/hyperopen/tmp/browser-inspection/design-review-2026-03-30T23-09-49-715Z-f4b2121e`

Disposition result:

- `bd` issue `hyperopen-x83o` closed on 2026-03-30 23:24Z with reason `Completed`
- no tracked source or test files changed for this bead
- `npm run lint:docs` still fails in this worktree, but only because unrelated active plan `/hyperopen/docs/exec-plans/active/2026-03-30-vault-detail-tvl-cold-load-fix.md` references only closed issue `hyperopen-6w7x`

Original bead reference from `bd`:

- failing run id: `design-review-2026-03-30T13-45-00-469Z-571312b7`
- current discovery: that run directory is no longer available in accessible local worktrees, so it cannot serve as the only implementation input

The most relevant existing prior plan is `/hyperopen/docs/exec-plans/completed/2026-03-24-design-review-blocked-false-positives-and-startup-readiness.md`. Read it before changing startup policy again. That plan already introduced bounded bootstrap retry and candidate-origin fallback, so this bead should only reopen those seams with fresh contradictory evidence.

## Interfaces and Dependencies

No public application interfaces should change to resolve this bead. If implementation is needed, the stable internal seams that may change are:

- `waitForDebugBridge(attached, timeoutMs, pollMs)` in `/hyperopen/tools/browser-inspection/src/session_manager.mjs`
- `navigateAttachedTarget(attached, session, url, options)` in `/hyperopen/tools/browser-inspection/src/session_manager.mjs`
- `maybeStartLocalApp(config, options)` in `/hyperopen/tools/browser-inspection/src/local_app_manager.mjs`
- `classifyErrorMessage(message)` in `/hyperopen/tools/browser-inspection/src/failure_classification.mjs`
- `classifyCaptureFailure(error)` in `/hyperopen/tools/browser-inspection/src/design_review/runtime.mjs`

If new configuration is required, prefer one named browser-inspection config field in `/hyperopen/tools/browser-inspection/config/defaults.json` rather than scattering new timeout literals through the code.

Plan update note (2026-03-30 23:10Z): created this active ExecPlan after auditing the current browser-inspection startup and capture path, consulting subagent analysis, restoring missing local dependencies, and reproducing both the full managed-local trade-route review and the focused `review-375` run as clean `PASS` results. The plan therefore frames `hyperopen-x83o` as a disposition-or-narrow-tooling-fix ticket rather than as a presumed trade-route regression.

Plan update note (2026-03-30 23:25Z): finalized the plan as a completed record after closing `hyperopen-x83o` in `bd`. No implementation followed because the original artifact bundle was unavailable and both fresh managed-local reruns passed on the current tree.
