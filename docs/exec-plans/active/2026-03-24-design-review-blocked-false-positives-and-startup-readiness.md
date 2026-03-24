# Eliminate Design-Review False BLOCKED Outcomes And Managed Startup Flakes

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-jm4w`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

Governed browser design-review is currently hard to trust for `/trade` even when the route itself is healthy. A warm run can finish `BLOCKED` because the styling-consistency grader treats valid computed CSS keyword values such as `normal` as a tooling gap, and a cold managed-local run can fail the first viewport before the app’s debug bridge is ready. After this change, a contributor should be able to run the governed `/trade` review either against an already running app or with `--manage-local-app`, and the result should reflect real product findings instead of tool-only false positives or startup races.

## Progress

- [x] (2026-03-24 13:07Z) Created and claimed `hyperopen-jm4w` for the governed design-review blocker cleanup.
- [x] (2026-03-24 13:09Z) Audited `/hyperopen/AGENTS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/WORK_TRACKING.md`, and `/hyperopen/.agents/PLANS.md`.
- [x] (2026-03-24 13:09Z) Reproduced the warm `/trade` design-review `BLOCKED` outcome and the cold managed-local first-viewport `FAIL`, and captured the artifact paths that demonstrate both failure classes.
- [x] (2026-03-24 13:09Z) Audited the governing browser-inspection seams in `/hyperopen/tools/browser-inspection/src/design_review/pass_registry.mjs`, `/hyperopen/tools/browser-inspection/src/design_review/models.mjs`, `/hyperopen/tools/browser-inspection/src/session_manager.mjs`, `/hyperopen/tools/browser-inspection/src/local_app_manager.mjs`, and the focused browser-inspection test files.
- [x] (2026-03-24 13:27Z) Implemented property-aware computed-style normalization in `/hyperopen/tools/browser-inspection/src/design_review/pass_registry.mjs` so `letterSpacing: normal` and the audited non-multicol gap-family `normal` values normalize to the approved zero token, while unsupported strings still surface as blind spots.
- [x] (2026-03-24 13:32Z) Hardened same-origin managed-local bootstrap navigation in `/hyperopen/tools/browser-inspection/src/session_manager.mjs` with a bounded `HYPEROPEN_DEBUG` retry policy that preserves the original timeout classification when startup never recovers.
- [x] (2026-03-24 13:53Z) Added focused regressions in `/hyperopen/tools/browser-inspection/test/design_review_pass_registry.test.mjs` and `/hyperopen/tools/browser-inspection/test/session_manager.test.mjs`, then reran the smallest relevant Playwright smoke, the focused Node tests, `npm run test:browser-inspection`, `npm run check`, `npm test`, `npm run test:websocket`, and a warm governed `/trade` design-review rerun.
- [x] (2026-03-24 14:16Z) Validated the cold managed-local startup seam end to end through the same design-review engine on an isolated same-origin fallback port (`8083`) using a temporary config/routing override, covering both `review-375` and the full `/trade` viewport matrix while the stock `8080` CLI path remained occupied by another checkout.
- [x] (2026-03-24 14:31Z) Extended the managed-local scope to eliminate the stock `--manage-local-app` dependency on `localhost:8080`, rebasing design-review, scenario, capture, and session navigation URLs to the actual managed-local origin announced by the spawned local app.
- [x] (2026-03-24 14:36Z) Added occupied-port startup regressions in `/hyperopen/tools/browser-inspection/test/local_app_manager.test.mjs`, plus origin-rebasing coverage in `/hyperopen/tools/browser-inspection/test/design_review_runner.test.mjs` and `/hyperopen/tools/browser-inspection/test/scenario_runner.test.mjs`.
- [x] (2026-03-24 14:43Z) Hardened the managed-local startup selection logic so browser bootstrap keeps the full ordered candidate URL list from Shadow startup logs and can fall back to a later candidate when an earlier candidate never exposes `HYPEROPEN_DEBUG`.
- [x] (2026-03-24 14:47Z) Finished the broadened validation stack for the stock `npm run qa:design-ui -- --targets trade-route --manage-local-app` path while another process already owns `localhost:8080`, confirming governed `PASS` outcomes for both `review-375` and the full viewport matrix.
- [ ] Move this ExecPlan to `/hyperopen/docs/exec-plans/completed/` after the managed-local concurrency hardening is accepted.

## Surprises & Discoveries

- Observation: the current `/trade` warm design-review run is blocked by tool semantics, not by route regressions.
  Evidence: `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T12-46-00-707Z-476a66fe/summary.json` reports `reviewOutcome: "BLOCKED"` with `styling-consistency: "TOOLING_GAP"`, while the same run shows `native-control: "PASS"`, `layout-regression: "PASS"`, and `jank-perf: "PASS"`.

- Observation: the raw computed-style probe contains the literal keyword `normal` for `letterSpacing`, `gap`, `rowGap`, and `columnGap` on the audited `/trade` selectors.
  Evidence: `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T12-46-00-707Z-476a66fe/trade-route/review-1280/probes/computed-styles.json` records `letterSpacing: "normal"`, `gap: "normal"`, `rowGap: "normal"`, and `columnGap: "normal"` for `[data-parity-id='trade-root']`, and parallel entries exist for `chart-panel`, `orderbook-panel`, `order-form`, and `account-equity`.

- Observation: the styling-consistency grader currently treats every non-`px` string as unsupported and promotes any such blind spot to `TOOLING_GAP`.
  Evidence: `/hyperopen/tools/browser-inspection/src/design_review/pass_registry.mjs` `parseComparablePx` returns `null` for any string that is not `"0"` or does not end in `px`, and `gradeStyleConsistency` maps any resulting blind spot to `PASS_STATUS.TOOLING_GAP`.

- Observation: the existing targeted test suite deliberately codifies `normal` as a tooling gap today.
  Evidence: `/hyperopen/tools/browser-inspection/test/design_review_pass_registry.test.mjs` currently asserts that `lineHeight: "normal"` produces `status === "TOOLING_GAP"` and `reasonCode === "unsupported-style-unit"`.

- Observation: the cold managed-local failure is a startup race between the app being reachable over HTTP and the browser document having registered `HYPEROPEN_DEBUG`.
  Evidence: `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T12-43-09-897Z-88d2a217/summary.json` records a `capture-failure` issue for `review-375` with observed behavior `Timed out waiting for HYPEROPEN_DEBUG to initialize.`, while a warm rerun against the already running app clears the same viewport family.

- Observation: the managed-local startup helper only waits for HTTP success, while the browser navigation path separately waits for the debug bridge with a fixed timeout.
  Evidence: `/hyperopen/tools/browser-inspection/src/local_app_manager.mjs` `waitForUrl` stops at any `2xx` response, and `/hyperopen/tools/browser-inspection/src/session_manager.mjs` `navigateAttachedTarget` later calls `waitForDebugBridge(..., 15000, ...)` before dispatching route navigation.

- Observation: after the style-normalization change, the warm governed `/trade` review now finishes `PASS` instead of `BLOCKED`.
  Evidence: `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T13-40-48-003Z-55ad79bc/summary.json` reports `reviewOutcome: "PASS"` and `styling-consistency: "PASS"` across the governed viewport set, leaving only the pre-existing interaction blind-spot note about hover/active/disabled/loading route actions.

- Observation: a true cold managed-local rerun is currently blocked by an unrelated already-running dev server outside this worktree.
  Evidence: `lsof -nP -iTCP:8080 -sTCP:LISTEN` reports `java` PID `67674`, and `ps -fp 67671` shows the owning parent process is `node /Users/barry/projects/hyperopen/node_modules/.bin/shadow-cljs watch app portfolio-worker vault-detail-worker`, so `--manage-local-app` in this worktree would attach to that external server rather than exercising a cold startup.

- Observation: the managed-local startup fix works end to end when the design-review engine is given an uncontended same-origin bootstrap target.
  Evidence: the isolated cold `review-375` run at `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T14-15-12-460Z-fe35de15/summary.json` and the isolated full matrix run at `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T14-15-50-111Z-de03c03e/summary.json` both report `reviewOutcome: "PASS"` with no `capture-failure` issue and no `Timed out waiting for HYPEROPEN_DEBUG to initialize.` observation.

- Observation: a local isolated Shadow watch can be forced away from the occupied default ports, but Shadow still auto-selects the next free HTTP ports rather than honoring the requested `8091` override literally.
  Evidence: a manual smoke of `npx shadow-cljs --force-spawn --config-merge ... watch ...` logged `TCP Port 8080 in use`, `TCP Port 8081 in use`, then `shadow-cljs - HTTP server available at http://localhost:8082` and `http://localhost:8083`, and the isolated managed-local validation succeeded when the temporary design-review bootstrap and target URLs used `http://localhost:8083/...`.

- Observation: the real managed-local Shadow startup path can announce more than one viable HTTP server URL during a single launch.
  Evidence: the local `shadow-cljs --force-spawn ... watch app` probe logged both `shadow-cljs - HTTP server available at http://localhost:8082` and `shadow-cljs - HTTP server available at http://localhost:8083` before the browser-inspection run completed.

- Observation: preserving the full announced URL list and letting bootstrap fall back on the browser-side debug-bridge check removes the remaining nondeterminism from the managed-local port choice.
  Evidence: `/hyperopen/tools/browser-inspection/test/local_app_manager.test.mjs` now pins ordered multi-announcement discovery, and `/hyperopen/tools/browser-inspection/test/session_manager.test.mjs` now pins fallback from `http://127.0.0.1:8082/index.html` to `http://127.0.0.1:8083/index.html` when the first candidate never exposes `HYPEROPEN_DEBUG`.

- Observation: the required `npm test` gate still fails, but the failure appears unrelated to the browser-inspection changes in this plan.
  Evidence: `npm test` fails in `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs` at `asset-list-item-applies-highlight-class-for-keyboard-navigation-test`, where the expected keyboard highlight class `bg-base-200/70` is absent.

- Observation: the stock managed-local governed design-review command is no longer pinned to the external `localhost:8080` listener in this shell.
  Evidence: with `lsof -iTCP:8080 -sTCP:LISTEN -n -P` still reporting an unrelated `java` listener, `npm run qa:design-ui -- --targets trade-route --manage-local-app --viewports review-375` produced `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T14-46-13-702Z-10195c84/` and `npm run qa:design-ui -- --targets trade-route --manage-local-app` produced `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T14-46-36-467Z-c8ba8ded/`, with both summaries reporting `reviewOutcome: "PASS"` and the target URL rebased to `http://localhost:8082/trade`.

## Decision Log

- Decision: treat the styling-consistency blocker and the cold managed-local first-viewport failure as two separate tool defects that happen to surface in the same governed command.
  Rationale: the warm run proves the route is currently healthy on the passes that inspect actual layout, controls, and perf, while the cold run proves the startup race is orthogonal to style grading. Fixing them independently keeps the implementation scoped and makes the post-change validation more honest.
  Date/Author: 2026-03-24 / Codex

- Decision: replace the current raw `px` parser with property-aware normalization instead of globally allowing any non-`px` string.
  Rationale: `normal` is valid and equivalent to the approved zero token for some audited properties, but values such as `calc(...)`, `inherit`, or future unsupported keywords should remain explicit blind spots so the tool does not silently over-claim what it can grade.
  Date/Author: 2026-03-24 / Codex

- Decision: fix the managed-local startup race at the browser navigation boundary, where `HYPEROPEN_DEBUG` absence is directly observable, rather than relying only on server-side HTTP readiness.
  Rationale: the local app manager cannot prove browser-document bridge readiness from HTTP alone. The browser session can, so the bounded retry or longer readiness policy belongs near `navigateAttachedTarget` and its callers.
  Date/Author: 2026-03-24 / Codex

- Decision: require both warm-app and `--manage-local-app` governed `/trade` reruns before closing the work.
  Rationale: the user reported frequent browser-QA blockage, and the investigation reproduced two distinct failure modes. The fix is not complete unless both the warm `BLOCKED` false positive and the cold managed-local startup failure are gone.
  Date/Author: 2026-03-24 / Codex

- Decision: retain the full ordered list of announced managed-local HTTP origins and let browser bootstrap fall back across that list when the first candidate never exposes `HYPEROPEN_DEBUG`.
  Rationale: Shadow can announce multiple HTTP servers in one startup, and the combined stdout/stderr ordering is not a stable source of truth for which candidate will host the working app shell. The browser can verify the correct candidate directly by checking the debug bridge, so the final selection logic belongs at bootstrap time rather than in log parsing alone.
  Date/Author: 2026-03-24 / Codex

## Outcomes & Retrospective

Implementation is complete for the two targeted tooling seams, and the resulting behavior is measurably better:

- The warm governed `/trade` review no longer reports `styling-consistency: TOOLING_GAP` for the previously observed `normal` computed values. The post-change artifact bundle is `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T13-40-48-003Z-55ad79bc/`, whose summary reports overall `PASS`.
- The style grader is now narrower and more explicit. It normalizes only the known-safe keyword cases (`letterSpacing: normal` and default non-multicol gap-family `normal`) and still reports unsupported computed-style strings as explicit blind spots.
- Same-origin bootstrap navigation now retries the `/index.html` bootstrap once when the specific `HYPEROPEN_DEBUG` startup timeout occurs, which covers the reproduced cold-start race without masking a permanently missing bridge.
- Managed-local startup no longer depends on a single parsed port. The startup helper records all announced candidate URLs in order, and the browser bootstrap path can fall back to a later candidate if an earlier candidate never yields `HYPEROPEN_DEBUG`.
- The cold managed-local startup seam is now validated both through the earlier isolated same-origin path and through the stock CLI path while an unrelated external process still owns `localhost:8080`. The focused stock run artifact is `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T14-46-13-702Z-10195c84/`, and the stock full matrix artifact is `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T14-46-36-467Z-c8ba8ded/`; both end in `PASS`.
- The focused validation stack passes: `npx playwright test tools/playwright/test/routes.smoke.spec.mjs --grep "trade desktop root renders"`, `node --test tools/browser-inspection/test/local_app_manager.test.mjs tools/browser-inspection/test/session_manager.test.mjs tools/browser-inspection/test/design_review_runner.test.mjs tools/browser-inspection/test/scenario_runner.test.mjs tools/browser-inspection/test/preflight.test.mjs tools/browser-inspection/test/config.test.mjs`, `npm run test:browser-inspection`, `npm run check`, and `npm run test:websocket`.

One closure condition remains outside the code change itself:

- `npm test` is still red due to the unrelated existing failure in `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs` (`asset-list-item-applies-highlight-class-for-keyboard-navigation-test`).

The stock `npm run qa:design-ui -- --targets trade-route --manage-local-app` command in this shell is now resilient to another checkout or process owning `localhost:8080`. The intended end state still reduces overall complexity: the grader now documents the small set of keyword defaults it can interpret honestly, and the managed-local startup policy now lives in the browser-facing seam that actually observes bridge readiness instead of being inferred from HTTP success or a single startup log line.

The tracked code changes that produced this state are:

- `/hyperopen/tools/browser-inspection/src/design_review/pass_registry.mjs`
- `/hyperopen/tools/browser-inspection/config/design-review-defaults.json`
- `/hyperopen/tools/browser-inspection/test/design_review_pass_registry.test.mjs`
- `/hyperopen/tools/browser-inspection/src/session_manager.mjs`
- `/hyperopen/tools/browser-inspection/test/session_manager.test.mjs`

## Context and Orientation

In this repository, “design review” means the governed six-pass browser-inspection run exposed through `npm run qa:design-ui`. The pass definitions live in `/hyperopen/tools/browser-inspection/src/design_review/pass_registry.mjs`, the overall summary state is aggregated in `/hyperopen/tools/browser-inspection/src/design_review/models.mjs`, and the local-route browser startup and navigation behavior lives in `/hyperopen/tools/browser-inspection/src/session_manager.mjs` and `/hyperopen/tools/browser-inspection/src/local_app_manager.mjs`.

The current warm blocker is concentrated in the styling-consistency pass. That pass iterates over the computed-style probe output for the configured selectors, compares each property to approved design-system numeric scales, and converts any non-comparable value into a blind spot. Today, that blind spot is treated as a tooling gap, which then escalates the overall review outcome to `BLOCKED`. The currently relevant files are:

- `/hyperopen/tools/browser-inspection/src/design_review/pass_registry.mjs`
- `/hyperopen/tools/browser-inspection/src/design_review/models.mjs`
- `/hyperopen/tools/browser-inspection/config/design-review-defaults.json`
- `/hyperopen/tools/browser-inspection/test/design_review_pass_registry.test.mjs`
- `/hyperopen/tools/browser-inspection/test/design_review_runner.test.mjs`

The cold managed-local failure is concentrated in the local browser navigation path. “Managed-local” means the browser-inspection tool starts the Hyperopen dev server itself with `npm run dev`, starts a temporary Chrome session, loads `/index.html`, waits for the repo’s browser debug bridge `HYPEROPEN_DEBUG`, and then dispatches `[:actions/navigate <route>]` inside the page. The currently relevant files are:

- `/hyperopen/tools/browser-inspection/src/session_manager.mjs`
- `/hyperopen/tools/browser-inspection/src/local_app_manager.mjs`
- `/hyperopen/tools/browser-inspection/config/defaults.json`
- `/hyperopen/tools/browser-inspection/test/session_manager.test.mjs`
- `/hyperopen/tools/browser-inspection/test/preflight.test.mjs`

The artifact bundle that proves the warm blocker is `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T12-46-00-707Z-476a66fe/`. The artifact bundle that proves the cold managed-local first-viewport failure is `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T12-43-09-897Z-88d2a217/`. A novice implementing this plan should keep those two runs open while working so they can compare the post-change behavior directly against the recorded failures.

## Plan of Work

Start by fixing the style grader because that is the deterministic warm-run blocker. Replace the current `parseComparablePx`-only flow in `/hyperopen/tools/browser-inspection/src/design_review/pass_registry.mjs` with a property-aware normalization helper that can interpret the exact values the tool currently misclassifies. `letterSpacing: normal` should normalize to the approved zero token. The gap-family properties `gap`, `rowGap`, and `columnGap` should stop producing blind spots for the current audited `/trade` selectors when the browser reports the default `normal` keyword and no real authored spacing is present. The helper must stay narrow: keep truly unsupported strings as blind spots, and make the resulting tests prove both the new allowed cases and the still-blocked cases.

Once the style grader is honest, harden the managed-local startup sequence. The current race is not a deep-link `404` problem; the local app is already bootstrapped through `/index.html`. The remaining defect is that the browser session can reach a loaded document before the page has installed `HYPEROPEN_DEBUG`. The fix should live in `/hyperopen/tools/browser-inspection/src/session_manager.mjs`, where the code already owns the bootstrap navigation and debug-bridge wait. Add a bounded retry or a longer, explicit bridge-startup policy for same-origin managed-local bootstrap navigations, keep the failure classification specific to bridge startup when the retry budget is exhausted, and test that behavior in `/hyperopen/tools/browser-inspection/test/session_manager.test.mjs`.

After both seams are fixed, expand the focused regression coverage. The tests should prove that warm style grading no longer blocks on the known `/trade` values, that unsupported strings still produce `unsupported-style-unit`, that aggregate summary state still maps real tooling gaps to `BLOCKED`, and that the session manager no longer treats a cold bridge startup as an immediate viewport capture failure when the route becomes ready within the bounded retry window.

Finish by validating the smallest deterministic browser surface first, then the governed toolchain. Because this work touches browser-test tooling, run the smallest relevant Playwright route smoke command before broadening to the repo gates and the governed design-review reruns. Then run the required repo validation commands and capture the final warm and managed-local design-review artifact paths back into this plan.

## Concrete Steps

1. Edit `/hyperopen/tools/browser-inspection/src/design_review/pass_registry.mjs` so style comparison no longer relies on a raw `px` parser alone. Introduce a helper that receives the property name, the computed value, and the surrounding style match data, then:

   - normalizes `letterSpacing: "normal"` to `0`
   - normalizes default gap-family `normal` values for the currently audited non-multicol route shells instead of treating them as unsupported
   - continues to return an explicit unsupported result for strings the tool still cannot grade honestly

2. If the normalization logic needs extra context from the computed-style probe, extend `/hyperopen/tools/browser-inspection/config/design-review-defaults.json` and the related probe usage so the grader has that context explicitly instead of inferring it from selector names.

3. Update `/hyperopen/tools/browser-inspection/test/design_review_pass_registry.test.mjs` to replace the current `normal => TOOLING_GAP` expectation with positive regressions for the valid `normal` cases and a preserved negative regression for a genuinely unsupported value such as `calc(1px + 1em)` or `inherit`.

4. Update `/hyperopen/tools/browser-inspection/test/design_review_runner.test.mjs` only if the aggregate behavior or summary wording needs to reflect the new normalization rules. Do not change the global rule that real tooling gaps still aggregate to `BLOCKED`.

5. Edit `/hyperopen/tools/browser-inspection/src/session_manager.mjs` so same-origin managed-local bootstrap navigations use an explicit bridge-startup policy. The implementation should:

   - keep bootstrapping through `/index.html`
   - wait for `HYPEROPEN_DEBUG` with a bounded startup budget that is suitable for a cold `npm run dev` launch
   - retry the bootstrap navigation or bridge wait in a controlled way when the specific bridge-startup timeout occurs
   - preserve a clear error message if the bridge still never appears

6. Update `/hyperopen/tools/browser-inspection/config/defaults.json` only if the bridge-startup timeout or retry budget should be configurable. Prefer one named config surface over scattering new magic numbers.

7. Extend `/hyperopen/tools/browser-inspection/test/session_manager.test.mjs` with regressions that prove:

   - same-origin bootstrap navigation still routes through `/index.html`
   - a delayed bridge can recover within the configured retry or wait budget
   - a permanently missing bridge still fails with the same actionable error classification

8. Extend `/hyperopen/tools/browser-inspection/test/preflight.test.mjs` or nearby focused tests only if the startup hardening changes what “ready” means for the browser-inspection preflight contract.

9. Run validation from `/hyperopen` in this order:

   - `npx playwright test tools/playwright/test/routes.smoke.spec.mjs --grep "trade desktop root renders"`
   - `node --test tools/browser-inspection/test/design_review_pass_registry.test.mjs tools/browser-inspection/test/design_review_runner.test.mjs tools/browser-inspection/test/session_manager.test.mjs tools/browser-inspection/test/preflight.test.mjs`
   - `npm run test:browser-inspection`
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

10. Run governed browser validation from `/hyperopen` and record the resulting artifact paths:

   - with an already running app: `npm run qa:design-ui -- --targets trade-route`
   - from a cold managed-local start: `npm run qa:design-ui -- --targets trade-route --manage-local-app --viewports review-375`
   - full managed-local matrix: `npm run qa:design-ui -- --targets trade-route --manage-local-app`

11. Update this plan’s `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` with the actual commands, results, changed files, and any remaining blockers before closing `hyperopen-jm4w`.

## Validation and Acceptance

Acceptance is behavior-based:

- The focused style-grader tests pass and prove that valid `normal` values no longer produce `TOOLING_GAP`, while a deliberately unsupported computed-style string still does.
- The focused session-manager tests pass and prove that a delayed debug bridge can recover during a cold managed-local startup without masking a permanently broken startup.
- The smallest relevant committed Playwright smoke command passes before the broader validation suite is run.
- `npm run test:browser-inspection`, `npm run check`, `npm test`, and `npm run test:websocket` all pass, or any unrelated existing failure is explicitly recorded here with file and test names.
- The warm `/trade` governed design-review run no longer reports `styling-consistency: TOOLING_GAP` for the previously observed `normal` values.
- The cold managed-local `/trade` governed design-review run no longer records a `capture-failure` issue whose observed behavior is `Timed out waiting for HYPEROPEN_DEBUG to initialize.`
- On the current product tree, the full `/trade` managed-local review should end in `PASS`. If a real product issue appears during implementation, the final state may be `FAIL`, but it must not be `BLOCKED` or `FAIL` for the two tool defects tracked by this plan.

## Idempotence and Recovery

This work is limited to browser-inspection tooling, focused tests, and this ExecPlan. The implementation should be safe to re-run because the validation steps are read-only browser checks or deterministic tests. If a startup-hardening attempt makes the managed-local flow less reliable, revert only the browser-inspection files touched by this plan, rerun the focused Node tests, and confirm the pre-change warm and cold artifact behavior still reproduces before trying a narrower retry policy. If the style-normalization change begins hiding a legitimate unsupported value, restore the previous blind-spot behavior for that property and add a narrower normalization rule rather than widening the parser globally.

## Artifacts and Notes

The tracked issue is `hyperopen-jm4w`. The active plan file is `/hyperopen/docs/exec-plans/active/2026-03-24-design-review-blocked-false-positives-and-startup-readiness.md`.

The two artifact bundles that motivated this plan are:

- warm blocked run: `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T12-46-00-707Z-476a66fe/`
- cold managed-local first-viewport failure: `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T12-43-09-897Z-88d2a217/`

The post-change validation artifacts recorded so far are:

- warm pass run: `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T13-40-48-003Z-55ad79bc/`
- isolated cold managed-local `review-375` pass run: `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T14-15-12-460Z-fe35de15/`
- isolated cold managed-local full matrix pass run: `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T14-15-50-111Z-de03c03e/`
- stock managed-local occupied-`8080` `review-375` pass run: `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T14-46-13-702Z-10195c84/`
- stock managed-local occupied-`8080` full matrix pass run: `/hyperopen/tmp/browser-inspection/design-review-2026-03-24T14-46-36-467Z-c8ba8ded/`

The most important implementation and test files are:

- `/hyperopen/tools/browser-inspection/src/design_review/pass_registry.mjs`
- `/hyperopen/tools/browser-inspection/src/design_review/models.mjs`
- `/hyperopen/tools/browser-inspection/src/session_manager.mjs`
- `/hyperopen/tools/browser-inspection/src/local_app_manager.mjs`
- `/hyperopen/tools/browser-inspection/config/design-review-defaults.json`
- `/hyperopen/tools/browser-inspection/config/defaults.json`
- `/hyperopen/tools/browser-inspection/test/design_review_pass_registry.test.mjs`
- `/hyperopen/tools/browser-inspection/test/design_review_runner.test.mjs`
- `/hyperopen/tools/browser-inspection/test/local_app_manager.test.mjs`
- `/hyperopen/tools/browser-inspection/test/session_manager.test.mjs`
- `/hyperopen/tools/browser-inspection/test/preflight.test.mjs`
- `/hyperopen/tools/playwright/test/routes.smoke.spec.mjs`

## Interfaces and Dependencies

At the end of this work, `/hyperopen/tools/browser-inspection/src/design_review/pass_registry.mjs` must still expose the existing pass-registry interface used by the runner, but it should do so through a normalization helper that can distinguish between:

- numeric scale values the tool can compare directly
- valid default keyword values the tool can safely normalize to an approved token
- unsupported computed-style values that must remain blind spots

`/hyperopen/tools/browser-inspection/src/session_manager.mjs` must still export `navigateAttachedTarget(attached, session, url, options)` and preserve the current same-origin bootstrap-through-`/index.html` behavior. If the implementation adds retry or timeout configuration, the public call shape should remain compatible with the current tests and callers in `/hyperopen/tools/browser-inspection/src/capture_pipeline.mjs`, `/hyperopen/tools/browser-inspection/src/design_review/runtime.mjs`, and `/hyperopen/tools/browser-inspection/src/scenario_runner.mjs`.

If `/hyperopen/tools/browser-inspection/config/defaults.json` gains new browser-startup policy fields, they must be optional, self-describing, and wired through the existing service/session configuration path rather than being read ad hoc from process environment or hard-coded inside one helper.

Plan update note (2026-03-24 13:09Z): created the initial ExecPlan after reproducing both the warm `BLOCKED` style-grader failure and the cold managed-local `HYPEROPEN_DEBUG` startup race, claiming `hyperopen-jm4w`, and auditing the exact browser-inspection seams and tests that will need to change.

Plan update note (2026-03-24 14:04Z): implemented the style-normalization and bootstrap-retry changes, added focused regressions, passed the focused browser-inspection suite plus `npm run check`, `npm run test:websocket`, and the smallest relevant Playwright smoke, confirmed a warm governed `/trade` review now ends in `PASS`, and recorded the remaining blockers as the unrelated red `npm test` failure plus the external `localhost:8080` listener that prevents an honest cold `--manage-local-app` rerun in this worktree.

Plan update note (2026-03-24 14:09Z): implemented the style-normalization and bootstrap-retry fixes, added focused regressions, verified the warm governed `/trade` rerun now passes, and recorded the remaining environment limitation that prevents a truthful cold `--manage-local-app` rerun while another checkout owns `localhost:8080`.

Plan update note (2026-03-24 14:16Z): completed isolated end-to-end managed-local validation by running the same design-review runner against a temporary same-origin fallback port (`8083`) while the stock `8080` CLI path was occupied by another checkout, producing `PASS` artifacts for both the first cold viewport and the full `/trade` viewport matrix.
