# Split funding modal view into workflow-focused modules

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`.

This plan executes `bd` issue `hyperopen-j5p6` ("Refactor funding modal view modules").

## Purpose / Big Picture

After this change, `/hyperopen/src/hyperopen/views/funding_modal.cljs` should stop being the place where every funding workflow detail lives. A contributor should be able to open the root funding modal namespace, understand the modal shell and content dispatch quickly, then jump directly to a workflow-specific namespace for deposit, withdraw, send, or transfer rendering. The visible modal behavior must stay the same on `/trade` and the existing tests and browser coverage should continue to prove deposit, withdraw, send, and mobile-sheet behavior.

## Progress

- [x] (2026-03-24 01:55Z) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/BROWSER_TESTING.md`, `/hyperopen/docs/FRONTEND.md`, and `/hyperopen/docs/agent-guides/browser-qa.md`.
- [x] (2026-03-24 01:55Z) Audited `/hyperopen/src/hyperopen/views/funding_modal.cljs`, `/hyperopen/test/hyperopen/views/funding_modal_test.cljs`, `/hyperopen/test/hyperopen/views/workbench_render_seams_test.cljs`, `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`, and prior funding-modal ExecPlans under `/hyperopen/docs/exec-plans/completed/`.
- [x] (2026-03-24 01:55Z) Created and claimed `bd` issue `hyperopen-j5p6` for this refactor.
- [x] (2026-03-24 01:55Z) Authored this active ExecPlan with the current DDD/SOLID audit findings and slice order.
- [x] (2026-03-24 02:02Z) Extracted shared presentation helpers into `/hyperopen/src/hyperopen/views/funding_modal/shared.cljs` and moved deposit, withdraw, send, and transfer rendering into workflow-focused namespaces under `/hyperopen/src/hyperopen/views/funding_modal/`.
- [x] (2026-03-24 02:03Z) Reduced `/hyperopen/src/hyperopen/views/funding_modal.cljs` from `1218` lines to `206` lines so it now reads as a public shell and content-dispatch seam instead of the monolithic workflow file.
- [x] (2026-03-24 02:03Z) Updated `/hyperopen/test/hyperopen/views/funding_modal_test.cljs` so its direct rendering checks now call the extracted deposit, withdraw, and shared lifecycle seams instead of reaching into root-namespace private vars.
- [x] (2026-03-24 02:08Z) Passed the smallest relevant Playwright funding regression: `npx playwright test tools/playwright/test/trade-regressions.spec.mjs -g "funding modal deposit flow selects USDC"`.
- [x] (2026-03-24 02:10Z) Passed `npm run check`.
- [x] (2026-03-24 02:11Z) Ran `npm test`; the suite completed with one unrelated pre-existing failure in `hyperopen.views.asset-selector-view-test/asset-list-item-applies-highlight-class-for-keyboard-navigation-test` and no funding-modal-specific failures.
- [x] (2026-03-24 02:12Z) Passed `npm run test:websocket`.
- [x] (2026-03-24 02:12Z) Ran governed browser QA for the changed UI file. The completed review artifact at `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T02-09-40-280Z-b198d84c/summary.md` reported `BLOCKED` because the styling-consistency pass hit a tooling gap on unsupported computed style units while the other passes were `PASS`.
- [x] (2026-03-24 02:13Z) Closed `bd` issue `hyperopen-j5p6` as completed and prepared this plan for `completed/`.

## Surprises & Discoveries

- Observation: this is already at least the third funding-modal refactor pass in March 2026, which means the remaining problem is not lack of prior cleanup but that the namespace still concentrates too many responsibilities after earlier seam extraction.
  Evidence: `/hyperopen/docs/exec-plans/completed/2026-03-07-funding-modal-workflow-slices.md`, `/hyperopen/docs/exec-plans/completed/2026-03-07-funding-modal-withdraw-lifecycle-crap-reduction.md`, and `/hyperopen/docs/exec-plans/completed/2026-03-12-funding-modal-view-seams.md` each removed a hotspot without changing the fact that `/hyperopen/src/hyperopen/views/funding_modal.cljs` still owns four workflows plus shared UI primitives plus shell rendering.

- Observation: the existing tests already show where the file’s boundaries are weakest because they have to reach into private vars in the root namespace for deposit, withdraw, and lifecycle seams.
  Evidence: `/hyperopen/test/hyperopen/views/funding_modal_test.cljs` currently calls `@#'view/deposit-address-content`, `@#'view/deposit-amount-content`, `@#'view/withdraw-detail-content`, and `@#'view/lifecycle-panel`.

- Observation: a focused CRAP report is not currently available without rebuilding coverage artifacts.
  Evidence: `bb tools/crap_report.clj --module src/hyperopen/views/funding_modal.cljs --format json --top-functions 100` failed with `Missing coverage/lcov.info. Run npm run coverage first.`

- Observation: `npm test -- --runInBand <paths>` is not a real targeted test filter in this repository because `node out/test.js` treats the forwarded arguments as unknown and still executes the full generated suite.
  Evidence: the first targeted run printed `Unknown arg: --runInBand` and `Unknown arg: test/hyperopen/views/funding_modal_test.cljs`, then continued running the full test bundle.

- Observation: the governed design-review runner produced the completed trade-route summary we needed, but the parent process did not return cleanly and had to be terminated manually after the summary files were already written.
  Evidence: `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T02-09-40-280Z-b198d84c/summary.md` shows a completed run ending at `2026-03-24T02:10:12.792Z`, while the parent `npm run qa:design-ui -- --changed-files src/hyperopen/views/funding_modal.cljs --manage-local-app` process remained live and required `pkill -f 'node tools/browser-inspection/src/cli.mjs design-review --changed-files src/hyperopen/views/funding_modal.cljs --manage-local-app'`.

- Observation: the only red assertion in the full JS test suite came from an asset-selector highlight-class test that does not touch funding modal files.
  Evidence: `npm test` failed in `hyperopen.views.asset-selector-view-test/asset-list-item-applies-highlight-class-for-keyboard-navigation-test`, while the suite continued through `hyperopen.views.funding-modal-test` and reported no funding-modal-specific failures.

## Decision Log

- Decision: treat this as a namespace-boundary refactor, not a behavior redesign.
  Rationale: earlier March work already corrected the biggest view-model and positioning defects. The current maintainability issue is that one namespace still owns shell rendering, shared widget primitives, and four workflow renderers. Splitting by workflow and shared presentation gives the biggest clarity gain with the lowest behavioral risk.
  Date/Author: 2026-03-24 / Codex

- Decision: keep `/hyperopen/src/hyperopen/views/funding_modal.cljs` as the only public entry point consumed by the rest of the app.
  Rationale: `/hyperopen/src/hyperopen/views/app_view.cljs` and `/hyperopen/test/hyperopen/views/workbench_render_seams_test.cljs` already rely on the root seam. Preserving `render-funding-modal` and `funding-modal-view` avoids widening the change to app-level wiring.
  Date/Author: 2026-03-24 / Codex

- Decision: move tests away from `@#'private-var` access when the new workflow namespaces provide a clearer public seam.
  Rationale: private-var test reach-through is a symptom of poor module boundaries. Exposing workflow render seams from dedicated namespaces is a better long-term contract for both humans and agents than pinning tests to root-namespace internals.
  Date/Author: 2026-03-24 / Codex

- Decision: keep the extracted deposit and withdraw seam names close to their original helper names, let `send.cljs` and `transfer.cljs` own their workflow rendering directly, and keep only the tiny legacy and unknown fallback branches in the root namespace.
  Rationale: this preserved low-risk migration paths for the root dispatcher and tests without leaving an extra catch-all workflow namespace in the final structure.
  Date/Author: 2026-03-24 / Codex

## Outcomes & Retrospective

Implementation completed. `/hyperopen/src/hyperopen/views/funding_modal.cljs` now contains the public shell and content dispatcher only, while workflow and shared presentation ownership moved into:

- `/hyperopen/src/hyperopen/views/funding_modal/shared.cljs`
- `/hyperopen/src/hyperopen/views/funding_modal/deposit.cljs`
- `/hyperopen/src/hyperopen/views/funding_modal/withdraw.cljs`
- `/hyperopen/src/hyperopen/views/funding_modal/send.cljs`
- `/hyperopen/src/hyperopen/views/funding_modal/transfer.cljs`

The root namespace shrank from `1218` lines to `206` lines. Overall complexity decreased because the original “one file contains everything” shape is gone; a contributor or agent can now load one workflow namespace at a time instead of carrying deposit, withdraw, send, transfer, shared primitives, and shell chrome in one working set.

Validation results were mixed but clear:

- `npx playwright test tools/playwright/test/trade-regressions.spec.mjs -g "funding modal deposit flow selects USDC"`: pass
- `npm run check`: pass
- `npm run test:websocket`: pass
- `npm test`: one unrelated failure in `hyperopen.views.asset-selector-view-test/asset-list-item-applies-highlight-class-for-keyboard-navigation-test`
- Browser QA: `BLOCKED` because the styling-consistency pass reported a tooling gap on unsupported computed style units; all other required passes were `PASS` across `375`, `768`, `1280`, and `1440`

The user-visible funding modal behavior stayed stable enough for the committed Playwright funding regression to pass and for the full JS suite to reach `hyperopen.views.funding-modal-test` without recording a funding-modal-specific failure. Residual risk is low and mainly external to this change: the unrelated asset-selector test failure tracked in `hyperopen-br3d` and the design-review tooling gap tracked in `hyperopen-qnsc` remain outside the refactor itself.

## Context and Orientation

The funding modal render host lives in `/hyperopen/src/hyperopen/views/funding_modal.cljs`. It receives a pre-shaped view model from `hyperopen.funding.actions/funding-modal-view-model` and should therefore behave as a presentation-layer module, not as a place where business rules or workflow interpretation are rediscovered.

Today the namespace still combines three different categories of responsibility.

First, it owns shared presentation primitives such as button class policies, numeric input formatting, summary rows, lifecycle rows, lifecycle notices, action rows, and asset-card rendering. Those helpers are useful, but they are not specific to one workflow.

Second, it owns workflow rendering for deposit, withdraw, send, and transfer in the same file. That means a contributor changing withdraw queue copy or HyperUnit lifecycle rendering still has to scan deposit asset selection, send-token form wiring, and transfer toggles in the same namespace.

Third, it owns modal shell rendering and layout-mode branching in `render-funding-modal`. That function still composes header rendering, close-button styling, feedback visibility, desktop anchored-popover rendering, and mobile-sheet rendering in one place.

From a DDD and SOLID perspective, the problem is not that the file contains helper functions. The problem is that the namespace is not aligned to one bounded presentation context. The root file owns a shell concern, a shared widget concern, and four workflow concerns at once. It violates single responsibility at the namespace level, weakens open/closed behavior because every new workflow or panel change lands in the same file, and makes test seams depend on private implementation details instead of stable module contracts.

## Audit Findings

The audit identified four concrete maintainability issues that this refactor will address.

1. `/hyperopen/src/hyperopen/views/funding_modal.cljs` mixes shell/layout orchestration with workflow rendering and shared widgets. The shared helper cluster starts near the top of the file, deposit rendering begins around `deposit-select-content`, withdraw rendering begins around `withdraw-select-content`, and the shell still ends in the large `render-funding-modal` function. A single namespace should not own all of those responsibilities.

2. Workflow boundaries are implicit rather than explicit. Deposit functions, withdraw functions, and send/transfer functions are grouped only by source-order and private naming. There is no dedicated namespace boundary that lets a contributor or agent load only the workflow they need to change.

3. Tests currently prove sub-workflow behavior by reaching through private vars on the root namespace. That makes refactors mechanically risky and tells future contributors the root namespace is the only place to find those seams.

4. The root `render-funding-modal` seam is still larger than it should be because it couples content dispatch, feedback rendering, and desktop/mobile shell branches in one function. Even after the workflow split, that function should read as shell orchestration only.

## Plan of Work

The refactor will happen in four slices.

First, add a shared presentation namespace under `/hyperopen/src/hyperopen/views/funding_modal/` for reusable UI building blocks that are already shared across workflows. This includes the asset card and icon helpers, grouped amount formatting, summary rows, lifecycle panel rendering, and generic action/input row helpers. The root funding modal namespace should stop owning those primitives directly.

Second, create workflow-focused namespaces under `/hyperopen/src/hyperopen/views/funding_modal/` for deposit, withdraw, send, and transfer. Each namespace should expose small render functions that accept the already-shaped workflow data from the view model and return Hiccup. This keeps DDD boundaries aligned with the modal’s content kinds and makes future edits local to the workflow being changed.

Third, slim `/hyperopen/src/hyperopen/views/funding_modal.cljs` down to modal-shell concerns: content-kind dispatch, feedback rendering, and desktop-versus-mobile surface composition. The root public functions `render-funding-modal` and `funding-modal-view` must remain stable so the rest of the app keeps using the same seam.

Fourth, update `/hyperopen/test/hyperopen/views/funding_modal_test.cljs` so direct workflow tests require the new workflow namespaces instead of calling private vars on the root module. Keep the existing public wrapper tests and workbench seam test intact. Then run the smallest relevant Playwright flow first, the required repo gates, and governed browser QA for the changed UI file.

## Concrete Steps

Run from repository root `/hyperopen`.

1. Add workflow-focused view namespaces:
   - `/hyperopen/src/hyperopen/views/funding_modal/shared.cljs`
   - `/hyperopen/src/hyperopen/views/funding_modal/deposit.cljs`
   - `/hyperopen/src/hyperopen/views/funding_modal/withdraw.cljs`
   - `/hyperopen/src/hyperopen/views/funding_modal/send.cljs`
   - `/hyperopen/src/hyperopen/views/funding_modal/transfer.cljs`
2. Edit `/hyperopen/src/hyperopen/views/funding_modal.cljs` so it becomes a shell-and-dispatch module instead of a namespace monolith.

3. Edit `/hyperopen/test/hyperopen/views/funding_modal_test.cljs` to use the new workflow/module seams where direct rendering coverage is needed.

4. Keep `/hyperopen/test/hyperopen/views/workbench_render_seams_test.cljs` passing to prove the public root seam is unchanged.

5. Run the smallest relevant Playwright regression first:

       npx playwright test tools/playwright/test/trade-regressions.spec.mjs -g "funding modal deposit flow selects USDC"

6. Run required repository gates:

       npm run check
       npm test
       npm run test:websocket

7. Run governed browser QA for the changed UI file:

       npm run qa:design-ui -- --changed-files src/hyperopen/views/funding_modal.cljs --manage-local-app

8. Update this plan with validation results and move it to `/hyperopen/docs/exec-plans/completed/` if fully complete.

## Validation and Acceptance

Acceptance is satisfied when all of the following are true.

1. `/hyperopen/src/hyperopen/views/funding_modal.cljs` still exports `render-funding-modal` and `funding-modal-view`, and the workbench seam test still proves that the wrapper output matches the public render seam.

2. Shared presentation primitives no longer live only in the root funding modal namespace; they are moved into a dedicated shared module that deposit, withdraw, send, and transfer rendering can reuse.

3. Deposit, withdraw, send, and transfer rendering each live in their own workflow-focused namespace under `/hyperopen/src/hyperopen/views/funding_modal/`.

4. Direct workflow tests no longer depend on `@#'private-var` access to the root funding modal namespace for deposit, withdraw, or lifecycle rendering.

5. The smallest relevant Playwright regression passes, `npm run check` passes, and `npm run test:websocket` passes. `npm test` may still fail only if the existing unrelated asset-selector regression remains red.

6. Browser QA explicitly accounts for the required passes and viewports for the changed funding modal surface, or records a concrete `BLOCKED` result with evidence if the full design-review tooling cannot complete in this environment.

## Idempotence and Recovery

This refactor is additive and safe to retry. The safe order is shared helpers first, then workflow modules, then root dispatch/shell cleanup, then tests. If a split introduces regressions, the fastest recovery path is to keep the new namespace files but temporarily delegate back to the previous root implementation until the specific workflow seam passes again. No persisted state, runtime contracts, or browser storage shape should change in this task.

## Artifacts and Notes

Important paths for this refactor:

- `/hyperopen/src/hyperopen/views/funding_modal.cljs`
- `/hyperopen/test/hyperopen/views/funding_modal_test.cljs`
- `/hyperopen/test/hyperopen/views/workbench_render_seams_test.cljs`
- `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`
- `/hyperopen/src/hyperopen/views/ui/funding_modal_positioning.cljs`

Implementation evidence to capture during completion:

- changed source files under `/hyperopen/src/hyperopen/views/funding_modal/`
- exact Playwright command result
- exact `npm run check`, `npm test`, and `npm run test:websocket` results
- browser-QA artifact directory or explicit blocker

Revision note (2026-03-24 01:55Z): Created after auditing the funding modal namespace, prior funding-modal ExecPlans, current view tests, and UI validation requirements, then creating and claiming `hyperopen-j5p6` for the workflow-focused module split.
Revision note (2026-03-24 02:13Z): Updated after extracting the workflow/shared namespaces, shrinking the root shell, rewiring direct seam tests, passing Playwright plus `npm run check` and `npm run test:websocket`, recording the unrelated `npm test` failure, and capturing the browser-QA `BLOCKED` summary plus artifact path.
