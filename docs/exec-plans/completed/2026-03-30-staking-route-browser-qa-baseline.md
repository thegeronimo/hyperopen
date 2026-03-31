# Add `/staking` Browser-QA Target And Baseline Coverage

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The tracked work item for this plan is `hyperopen-yez7`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks implementation.

## Purpose / Big Picture

After this change, `/staking` will stop being invisible to the committed browser-QA system. A contributor will be able to run the governed route-level review and a small deterministic regression path against `/staking`, see the route render at the required widths, and verify the currently testable disconnected and popover-focused states without depending on live staking account data.

This ticket does not solve deterministic loaded staking data. That separate gap remains tracked in `hyperopen-4y37`. The useful outcome here is narrower: `/staking` becomes a first-class browser-QA target with a stable baseline path that works today.

## Progress

- [x] (2026-03-30 23:51Z) Claimed `hyperopen-yez7` in `bd`.
- [x] (2026-03-30 23:52Z) Read `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/BROWSER_TESTING.md`, and `/hyperopen/docs/agent-guides/browser-qa.md`.
- [x] (2026-03-30 23:53Z) Confirmed the current browser-QA inventory gap: `/hyperopen/tools/browser-inspection/config/design-review-routing.json` has no `staking-route` target, `/hyperopen/tools/browser-inspection/scenarios/**` contains no staking scenarios, and `/hyperopen/tools/playwright/test/**` contains no `/staking` coverage.
- [x] (2026-03-30 23:55Z) Confirmed the current deterministic seams available on `/staking`: root parity anchor `staking-root`, disconnected connect button `staking-establish-connection`, toolbar buttons for transfer, stake, and unstake, and popover surfaces under `/hyperopen/src/hyperopen/views/staking_view.cljs` and `/hyperopen/src/hyperopen/views/staking/popovers.cljs`.
- [x] (2026-03-30 23:56Z) Created this active ExecPlan and `/hyperopen/tmp/multi-agent/hyperopen-yez7/spec.json`.
- [x] (2026-03-30 23:59Z) Added `staking-route` to `/hyperopen/tools/browser-inspection/config/design-review-routing.json`, registered changed-file routing rules for `staking_view.cljs`, `src/hyperopen/views/staking/**`, and `src/hyperopen/staking/**`, and updated the loader test coverage in `/hyperopen/tools/browser-inspection/test/design_review_loader.test.mjs`.
- [x] (2026-03-31 00:00Z) Added baseline browser-inspection scenarios at `/hyperopen/tools/browser-inspection/scenarios/staking-route-smoke.json` and `/hyperopen/tools/browser-inspection/scenarios/staking-route-smoke-mobile.json`.
- [x] (2026-03-31 00:01Z) Added committed Playwright coverage in `/hyperopen/tools/playwright/test/routes.smoke.spec.mjs` and `/hyperopen/tools/playwright/test/staking-regressions.spec.mjs` for disconnected route render plus deterministic validator-timeframe menu interaction.
- [x] (2026-03-31 00:01Z) Updated `/hyperopen/docs/BROWSER_TESTING.md` so the documented committed browser-QA surface includes `/staking`.
- [x] (2026-03-31 00:02Z) Ran focused discovery and dry-run validation: browser-inspection scenario list and dry-run succeeded, changed-file design-review selection matched the new `staking-view` rule, and PR UI dry-run selected both new staking scenarios.
- [x] (2026-03-31 00:04Z) Found a real governed-review blocker: `npm run qa:design-ui -- --targets staking-route --manage-local-app` failed `layout-regression` on `review-375` because audited `staking-root` owned the route's vertical scroll range.
- [x] (2026-03-31 00:06Z) Fixed the route-shell contract in `/hyperopen/src/hyperopen/views/staking_view.cljs` so `staking-root` is an overflow-hidden audit shell and an inner child owns vertical scrolling, matching the established audited-route pattern used on `/trade`.
- [x] (2026-03-31 00:08Z) Re-ran focused browser validation successfully: `npx playwright test tools/playwright/test/routes.smoke.spec.mjs tools/playwright/test/staking-regressions.spec.mjs --grep staking` passed 4 tests, and governed design review passed as run `design-review-2026-03-31T00-08-01-718Z-e0746c7d`.
- [x] (2026-03-31 00:11Z) Ran required repository gates: `npm test` and `npm run test:websocket` passed; `npm run check` failed only on the pre-existing unrelated docs lint error for active plan `/hyperopen/docs/exec-plans/active/2026-03-30-vault-detail-tvl-cold-load-fix.md`, which still references closed issue `hyperopen-6w7x`.

## Surprises & Discoveries

- Observation: the browser-QA gap is split into two different problems, not one.
  Evidence: `hyperopen-4y37` accurately describes the missing deterministic staking data simulator, while `/hyperopen/tools/browser-inspection/config/design-review-routing.json` and `/hyperopen/tools/playwright/test/**` show that `/staking` is also missing a route target and baseline committed coverage entirely.

- Observation: `/staking` already exposes enough stable UI anchors for a first deterministic browser-QA slice without solving live staking data.
  Evidence: `/hyperopen/src/hyperopen/views/staking_view.cljs` exports `data-parity-id="staking-root"` and stable `data-role` hooks including `staking-establish-connection`, `staking-action-transfer-button`, `staking-action-unstake-button`, `staking-action-stake-button`, `staking-balance-panel`, and the mounted popover surface.

- Observation: there are no checked-in staking workbench scenes yet.
  Evidence: searching `/hyperopen/portfolio/hyperopen/workbench/scenes/**` for `staking` returned no matches, so this first browser-QA cut must anchor itself to the live route rather than to existing scene references.

- Observation: the existing route-smoke scenario pattern is intentionally small.
  Evidence: `/hyperopen/tools/browser-inspection/scenarios/trade-route-smoke.json` and `/hyperopen/tools/browser-inspection/scenarios/portfolio-route-smoke.json` only reset QA state, navigate, wait for idle, assert a root parity element, capture, and compare.

- Observation: governed design review treats an audited selector that owns vertical scrolling as a layout-regression failure, even when the rendered first fold is visually valid.
  Evidence: the failed run `design-review-2026-03-31T00-03-32-614Z-2d65fe64` reported `selector-overlap` / `vertical-overflow` for `[data-parity-id='staking-root']` at `review-375`, with `/hyperopen/tmp/browser-inspection/design-review-2026-03-31T00-03-32-614Z-2d65fe64/staking-route/review-375/probes/layout-audit.json` showing `overflowY: "auto"` and `scrollHeight > clientHeight`.

- Observation: `/staking` needed the same audited-route-shell separation that `/trade` already uses.
  Evidence: `/hyperopen/docs/exec-plans/completed/2026-03-19-shared-browser-qa-route-cleanup-plan.md` documents the pattern, and switching `/hyperopen/src/hyperopen/views/staking_view.cljs` to an overflow-hidden outer shell plus inner scroll shell turned the governed review from `FAIL` to `PASS`.

## Decision Log

- Decision: keep `hyperopen-yez7` focused on route registration plus deterministic baseline coverage, and explicitly leave loaded validator, rewards, history, delegator, and spot-balance simulation out of scope.
  Rationale: that simulator seam remains genuinely missing and is already tracked separately in `hyperopen-4y37`. Folding both problems into one implementation would make the first `/staking` browser-QA cut harder to land and harder to validate.
  Date/Author: 2026-03-30 / Codex

- Decision: treat disconnected and popover-focused states as the baseline deterministic `/staking` path.
  Rationale: those states are already reachable through stable action dispatch and DOM anchors without needing live staking account data or a new simulator.
  Date/Author: 2026-03-30 / Codex

- Decision: reuse the existing `HYPEROPEN_DEBUG` bridge and current route-smoke conventions instead of inventing a staking-specific browser control layer.
  Rationale: `/hyperopen/docs/BROWSER_TESTING.md` explicitly says the committed suites should reuse the existing debug bridge, simulator helpers, and stable anchors.
  Date/Author: 2026-03-30 / Codex

- Decision: add `/staking` to governed design-review routing rather than relying only on scenarios or only on Playwright.
  Rationale: this bead is about first-class browser-QA coverage. In this repository that means both governed route targeting for design review and at least one deterministic committed regression path.
  Date/Author: 2026-03-30 / Codex

- Decision: keep `[data-parity-id='staking-root']` as an audited selector, but change the route shell so the audited node is not the element that owns vertical scrolling.
  Rationale: removing the root selector would hide a legitimate route-level audit surface. Mirroring the existing trade-route shell pattern preserved browser-QA coverage while resolving the false layout-regression classification.
  Date/Author: 2026-03-31 / Codex

## Outcomes & Retrospective

`/staking` is no longer a browser-QA blind spot. The repo now has a governed design-review target, checked-in browser-inspection smoke scenarios, committed Playwright smoke coverage, and deterministic staking regression tests for the disconnected baseline plus validator-timeframe menu interaction. The governing route target also survives real managed-local design review across `review-375`, `review-768`, `review-1280`, and `review-1440`.

The most important mid-implementation discovery was that this bead uncovered a real route-shell contract problem, not just missing registration. The first implementation made `/staking` reviewable enough to surface a layout-regression failure at mobile width because `staking-root` was both the audited route selector and the vertical scroll owner. Moving scroll ownership to an inner shell fixed the design-review failure without weakening the target.

Validation evidence:

- `node tools/browser-inspection/src/cli.mjs scenario list --ids staking-route-smoke,staking-route-smoke-mobile`
- `node tools/browser-inspection/src/cli.mjs scenario run --ids staking-route-smoke,staking-route-smoke-mobile --dry-run`
- `npm run qa:design-ui -- --changed-files src/hyperopen/views/staking_view.cljs --dry-run`
- `npm run qa:pr-ui -- --changed-files src/hyperopen/views/staking_view.cljs --dry-run`
- `node --test tools/browser-inspection/test/design_review_loader.test.mjs tools/browser-inspection/test/cli_contract.test.mjs`
- `npx playwright test tools/playwright/test/routes.smoke.spec.mjs tools/playwright/test/staking-regressions.spec.mjs --grep staking`
- `npm run qa:design-ui -- --targets staking-route --manage-local-app`
- `npm test`
- `npm run test:websocket`

Residual gap: deterministic loaded staking account data remains out of scope and is still correctly tracked by `hyperopen-4y37`. Governed design review still reports honest interaction blind spots for hover, active, disabled, and loading states that are not present by default, but those are residual blind spots rather than blocking failures.

## Context and Orientation

In this repository, “browser QA” means two related systems. The first is governed design review, driven by `npm run qa:design-ui`, which uses `/hyperopen/tools/browser-inspection/config/design-review-routing.json` to decide which routes exist, which selectors define the reviewed surface, and which changed files map to which review targets. The second is committed deterministic browser regression coverage under `/hyperopen/tools/playwright/test/**`, which runs in Playwright and uses the dev-only `HYPEROPEN_DEBUG` bridge from `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`.

Right now `/staking` is implemented as a real route, but it is not a first-class browser-QA target. The route view lives in `/hyperopen/src/hyperopen/views/staking_view.cljs`. That file exports `data-parity-id="staking-root"` on the route container and stable `data-role` hooks for the connect button, action buttons, balance panel, and error banner. The validator table and popover internals live under `/hyperopen/src/hyperopen/views/staking/validators.cljs` and `/hyperopen/src/hyperopen/views/staking/popovers.cljs`.

The current design-review target registry lives in `/hyperopen/tools/browser-inspection/config/design-review-routing.json`. It includes targets for `/trade`, `/portfolio`, `/leaderboard`, `/vaults`, `/vaults/detail`, and `/api-wallets`, but not `/staking`. The current checked-in browser-inspection scenarios live under `/hyperopen/tools/browser-inspection/scenarios/`. They include route-smoke and interaction-state coverage for trade, portfolio, and vaults, but nothing for staking. The current committed Playwright tests live under `/hyperopen/tools/playwright/test/`. They likewise cover trade, portfolio, vaults, and mobile trade flows, but no `/staking` route.

The missing deterministic staking account-data seam must stay out of scope here. `hyperopen-4y37` tracks that the current debug simulator only wires into trading exchange requests, while staking loaded-state reads still use account `/info` request paths and spot-clearinghouse requests from `/hyperopen/src/hyperopen/api/default.cljs`. This plan should not try to fake validator summaries, delegator summaries, rewards, or history data before the simulator issue is implemented.

## Plan of Work

First, extend the governed route registry so `/staking` can participate in design review like the other first-class routes. In `/hyperopen/tools/browser-inspection/config/design-review-routing.json`, add a new `staking-route` target with route `/staking`, URL `http://localhost:8080/staking`, and selectors rooted in the existing `staking-root` parity anchor. The selectors should include the route root and at least one inner stable surface such as the balance panel and validator panel or validator table. Add changed-file routing rules for `/hyperopen/src/hyperopen/views/staking_view.cljs` and `/hyperopen/src/hyperopen/views/staking/**`, and extend the shared app-shell or header rules if those files can affect the staking route.

Second, add checked-in browser-inspection scenario coverage for the smallest currently deterministic `/staking` path. Follow the existing route-smoke pattern by adding desktop and mobile `/staking` smoke scenarios that reset QA state, navigate to `/staking`, wait for idle, assert `staking-root`, capture, and compare. If the mobile path needs slightly longer settle time than trade or portfolio, record that in the scenario itself. Keep these scenarios limited to route render proof rather than loaded staking data proof.

Third, add a committed Playwright regression that exercises the deterministic `/staking` baseline path. Reuse `/hyperopen/tools/playwright/support/hyperopen.mjs` helpers such as `visitRoute`, `dispatch`, and `waitForIdle`. The most stable baseline today is to visit `/staking`, assert the root and connect button render in the disconnected state, then use stable toolbar actions or action dispatch to open one popover and assert the popover surface becomes visible. If the disconnected state makes popovers unreachable through the visible UI, keep the Playwright test to disconnected route render plus whichever deterministic connected or action-driven baseline can be reached without new simulators. The important rule is to avoid false determinism that secretly depends on live account data.

Fourth, update browser-QA docs only where they explicitly enumerate the committed route surface. `/hyperopen/docs/BROWSER_TESTING.md` currently lists the initial Playwright coverage. If `/staking` becomes part of that committed surface, amend the list so the docs match the code.

Finally, validate in layers. Start with focused browser-inspection scenario runs and the smallest Playwright invocation that covers only the new staking test. Broaden to the full relevant Playwright suite or design-review command only after the focused checks pass. If the governed design-review route target introduces unexpected blind spots or `BLOCKED` passes because `/staking` lacks some references, capture that honestly in the plan and in any follow-up bead instead of weakening the route target silently.

## Concrete Steps

Run these commands from `/Users/barry/.codex/worktrees/5004/hyperopen`.

1. Inspect the existing route-target and scenario patterns:

   - `sed -n '1,260p' tools/browser-inspection/config/design-review-routing.json`
   - `sed -n '1,260p' tools/browser-inspection/scenarios/trade-route-smoke.json`
   - `sed -n '1,260p' tools/browser-inspection/scenarios/portfolio-route-smoke.json`
   - `sed -n '1,260p' tools/playwright/test/portfolio-regressions.spec.mjs`

   Expected result: the current files show the route-target schema, the small route-smoke scenario shape, and the Playwright helper usage pattern.

2. Implement the `/staking` design-review target and changed-file routing rules:

   - edit `tools/browser-inspection/config/design-review-routing.json`

   Expected result: the file contains a `staking-route` target and matching file-glob rules for staking view code.

3. Add browser-inspection baseline scenarios:

   - create `tools/browser-inspection/scenarios/staking-route-smoke.json`
   - create `tools/browser-inspection/scenarios/staking-route-smoke-mobile.json`

   Expected result: `node tools/browser-inspection/src/cli.mjs scenario list --ids staking-route-smoke,staking-route-smoke-mobile` reports both scenarios.

4. Add committed Playwright coverage:

   - edit or create the relevant file under `tools/playwright/test/`

   Expected result: the new test uses `visitRoute`, `dispatch`, `waitForIdle`, and stable `/staking` selectors or parity anchors rather than brittle timing-only DOM interactions.

5. Update docs if the committed coverage list changed:

   - edit `docs/BROWSER_TESTING.md` only if the new Playwright route coverage should be documented.

6. Run focused validation in this order:

   - `node tools/browser-inspection/src/cli.mjs scenario list --ids staking-route-smoke,staking-route-smoke-mobile`
   - `node tools/browser-inspection/src/cli.mjs scenario run --ids staking-route-smoke,staking-route-smoke-mobile --dry-run`
   - `npx playwright test tools/playwright/test/<staking-test-file> --grep staking`
   - `npm run qa:design-ui -- --targets staking-route --manage-local-app`

7. If tracked code changed beyond the new focused surfaces or the repo contract requires it, finish with:

   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

This ticket is accepted when all of the following are true.

First, `/staking` is a governed design-review target. A run of `npm run qa:design-ui -- --targets staking-route --manage-local-app` must produce a design-review artifact bundle for `/staking` at the required `review-375`, `review-768`, `review-1280`, and `review-1440` widths. The result may still contain honest `BLOCKED` interaction blind spots if those are real and documented, but the route itself must be reviewable and no longer absent from the target registry.

Second, the checked-in browser-inspection scenario bundle recognizes `/staking`. Running `node tools/browser-inspection/src/cli.mjs scenario list --ids staking-route-smoke,staking-route-smoke-mobile` must return both new scenario ids, and a dry run must show they parse correctly.

Third, the committed Playwright suite contains at least one deterministic `/staking` baseline regression path that passes without relying on the missing account-request simulator from `hyperopen-4y37`.

Fourth, the plan and any updated docs explicitly say that deterministic loaded staking data remains out of scope here and is still tracked by `hyperopen-4y37`.

## Idempotence and Recovery

The route-target and scenario additions are additive and safe to rerun. Browser-inspection scenario runs and design-review runs create timestamped artifact directories under `/hyperopen/tmp/browser-inspection/`, so repeated runs do not overwrite prior evidence. If a new `/staking` design-review target immediately fails because the route lacks a stable selector or because interaction passes are honestly unreachable, keep the artifact bundle, update this plan with the exact failure mode, and narrow the implementation rather than deleting the target.

If the Playwright baseline turns out to depend on live staking account data after all, revert only the fragile assertion or action sequence, keep the route-render baseline intact, and record the discovery here. Do not paper over a missing simulator by hardcoding live-account assumptions into committed tests.

## Artifacts and Notes

Known current-state evidence before implementation:

- `/hyperopen/tools/browser-inspection/config/design-review-routing.json` has no `staking-route` target.
- `rg --files tools/browser-inspection/scenarios | rg staking` returns no files.
- `rg -n 'visitRoute\\(page, \"/staking|/staking' tools/playwright/test` returns no matches.
- `/hyperopen/src/hyperopen/views/staking_view.cljs` already exposes `data-parity-id="staking-root"` and stable action-button selectors.

Expected touched files for the first implementation cut:

- `/hyperopen/tools/browser-inspection/config/design-review-routing.json`
- `/hyperopen/tools/browser-inspection/scenarios/staking-route-smoke.json`
- `/hyperopen/tools/browser-inspection/scenarios/staking-route-smoke-mobile.json`
- `/hyperopen/tools/playwright/test/<staking regression file or existing file>`
- `/hyperopen/docs/BROWSER_TESTING.md` only if the committed coverage list changes
- `/hyperopen/docs/exec-plans/active/2026-03-30-staking-route-browser-qa-baseline.md`
- `/hyperopen/tmp/multi-agent/hyperopen-yez7/spec.json`

## Interfaces and Dependencies

No public product API should change for this bead. The internal interfaces that must exist at the end of this milestone are:

- a `staking-route` target object in `/hyperopen/tools/browser-inspection/config/design-review-routing.json`
- changed-file routing rules that map staking view files to that target
- at least one checked-in desktop scenario id and one mobile scenario id for `/staking`
- at least one committed Playwright test that proves the deterministic `/staking` baseline path

This work must continue using the existing browser-QA interfaces already established in the repository:

- `HYPEROPEN_DEBUG` from `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`
- Playwright helpers from `/hyperopen/tools/playwright/support/hyperopen.mjs`
- browser-inspection scenario execution through `/hyperopen/tools/browser-inspection/src/cli.mjs`

Plan revision note: 2026-03-30 23:56Z - Initial active ExecPlan created after claiming `hyperopen-yez7`, confirming the current `/staking` browser-QA blind spots, and separating this route-target/baseline work from the still-open staking data-simulator bead `hyperopen-4y37`.
Plan revision note: 2026-03-31 00:11Z - Implementation complete: `/staking` now has governed route registration, browser-inspection scenarios, committed Playwright coverage, and a route-shell fix that makes managed-local design review pass. The only remaining validation failure is the unrelated pre-existing `lint:docs` issue for `/hyperopen/docs/exec-plans/active/2026-03-30-vault-detail-tvl-cold-load-fix.md`.
