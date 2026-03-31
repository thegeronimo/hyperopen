# Fix Active Asset Icon Loaded-State Transition

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Linked live work: `hyperopen-f9y7` ("Investigate active asset icon loaded-state not updating after QA refactor").

## Purpose / Big Picture

After this work, the trade header's active-asset icon should stop living forever on the probe/background-image fallback path when the upstream icon loads successfully. A user opening `http://localhost:8080/trade` should still see the icon immediately, but the app-state should also move the market key into `[:asset-selector :loaded-icons]` so the visible DOM can transition from the hidden probe image plus CSS background into the normal loaded-icon rendering path.

The failure is currently observable in a real browser session. On `/trade`, the BTC probe image reaches `complete: true` with a positive `naturalWidth`, yet `[:asset-selector :loaded-icons]` and `[:asset-selector :missing-icons]` remain empty and no icon-status action traces appear. The fix must restore that transition without regressing the earlier broken-image fallback behavior.

## Progress

- [x] (2026-03-31 14:18Z) Installed worktree dependencies with `npm install` so CLJS tests and browser tooling can run locally.
- [x] (2026-03-31 14:26Z) Compiled the CLJS test build with `npx shadow-cljs compile test` and confirmed the current pure test suite passes.
- [x] (2026-03-31 14:36Z) Reproduced `hyperopen-f9y7` in a managed browser-inspection session on `/trade`: BTC rendered through the probe path, the probe image reached `complete: true` and `naturalWidth: 150`, and `loaded-icons` / `missing-icons` stayed empty.
- [x] (2026-03-31 14:38Z) Confirmed the reducer/runtime path is still wired: `/hyperopen/src/hyperopen/asset_selector/actions.cljs` still queues icon statuses and `/hyperopen/src/hyperopen/asset_selector/icon_status_runtime.cljs` still flushes them into app-state.
- [x] (2026-03-31 14:58Z) Identified the actual failure mode: `mark-loaded-asset-icon` and `mark-missing-asset-icon` were already emitting `:icon-status`, but the running browser session had not been rebuilt and still behaved like the stale `:status` era, so the effect failed runtime validation and never reached the flush path.
- [x] (2026-03-31 15:02Z) Hardened the active-asset icon rendering path in `/hyperopen/src/hyperopen/views/active_asset/icon_button.cljs` so native `load` / `error` handlers are registered before `:src`, while preserving the `:replicant/on-render` fallback for already-complete probe images.
- [x] (2026-03-31 15:04Z) Added regression coverage in `/hyperopen/test/hyperopen/views/active_asset/icon_button_test.cljs` and `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`.
- [x] (2026-03-31 15:10Z) Passed `npm run check`, `npm test`, `npm run test:websocket`, the focused Playwright regression, and a rebuilt live browser re-check on `/trade`.

## Surprises & Discoveries

- Observation: the current automated tests do not catch this bug even though the behavior still fails in a live browser.
  Evidence: `node out/test.js` completed with `0 failures, 0 errors`, but the browser-inspection session still showed `loaded-icons: []`, `missing-icons: []`, and a completed BTC probe image.

- Observation: the bug is not in the reducer or queueing runtime.
  Evidence: `/hyperopen/src/hyperopen/asset_selector/actions.cljs` still emits `:effects/queue-asset-icon-status`, and `/hyperopen/src/hyperopen/asset_selector/icon_status_runtime.cljs` still saves the flushed `loaded-icons` and `missing-icons` sets.

- Observation: the live failure was partly masked by an app-build drift during investigation.
  Evidence: a stale browser session still rejected `:effects/queue-asset-icon-status` with the old payload shape expectation, but after `npx shadow-cljs compile app` the rebuilt session showed `loaded-icons = ["perp:BTC"]` and `styleBgMatches = 0`.

- Observation: the probe image should attach `load` / `error` handlers before setting `:src`.
  Evidence: Replicant sets element attributes in map key order before lifecycle hooks run. Keeping `:on` ahead of `:src` in `icon_button.cljs` makes the native image event path available as early as possible.

## Decision Log

- Decision: preserve the existing queued icon-status runtime and fix the event-to-effect seam instead of redesigning the state flow.
  Rationale: once the app build was refreshed, the existing action and runtime path correctly promoted BTC into `loaded-icons`; the defect was in getting the right event path and build state to that runtime reliably.
  Date/Author: 2026-03-31 / Codex

- Decision: keep the existing loaded-icons / missing-icons state contract instead of removing it.
  Rationale: that state still controls the transition from probe fallback to the normal image path and still protects the broken-image fallback behavior that earlier regressions restored.
  Date/Author: 2026-03-31 / Codex

- Decision: keep both the hidden probe image's native `:on` handlers and the `:replicant/on-render` mounted-image fallback.
  Rationale: the native handlers are the primary browser path, while `attach-asset-icon-probe` remains the backstop for already-complete nodes and update-time state reconciliation.
  Date/Author: 2026-03-31 / Codex

- Decision: add committed regression coverage at both the view-runtime seam and the browser level if the browser assertion can be made deterministic cheaply.
  Rationale: the existing pure tests already pass, so the fix must add coverage that fails for the real timing problem instead of only reasserting the current implementation shape.
  Date/Author: 2026-03-31 / Codex

## Outcomes & Retrospective

The final result preserves the existing active-asset icon rendering model while restoring the live browser transition into `loaded-icons`. After rebuilding the app and rerunning `/trade`, the debug snapshot showed `loaded-icons = ["perp:BTC"]`, `missing-icons = []`, and no remaining BTC background-image probe layer. The new CLJS regression assertions lock in the event-handler ordering and probe fallback shape, and the new Playwright regression proves the real browser/store path now works.

This reduced complexity slightly. The fix stayed inside the existing active-asset seam and did not introduce a second persistence mechanism or a new public API; it only made the existing event path explicit and added the missing regression coverage that would have caught the bug earlier.

## Context and Orientation

The active asset strip is rendered through `/hyperopen/src/hyperopen/views/active_asset/icon_button.cljs`. The key function is `asset-button`, which decides whether to show a loaded image, a monogram fallback, or a probe path made of a background-image layer plus a hidden `<img>` element. The hidden `<img>` exists so the UI can wait for a successful load before promoting the visible DOM into the normal image path.

The app-state bookkeeping for that promotion lives under `[:asset-selector :loaded-icons]` and `[:asset-selector :missing-icons]`. The pure transition logic is in `/hyperopen/src/hyperopen/asset_selector/actions.cljs`, where `mark-loaded-asset-icon` and `mark-missing-asset-icon` enqueue icon statuses. The queue flush is in `/hyperopen/src/hyperopen/asset_selector/icon_status_runtime.cljs`, which stores the final sets back into app-state on the next animation frame.

The current failure was a combination of runtime timing and build-state confusion. The probe image in `icon_button.cljs` uses `attach-asset-icon-probe`, a `:replicant/on-render` callback that checks the mounted node once for `complete` plus `naturalWidth`, while the hidden probe image also relies on native `load` / `error` handlers to dispatch the normal icon-status actions. Replicant applies element attributes before lifecycle hooks, so the order of `:on` versus `:src` matters for fast image loads. During this fix, rebuilding the app also mattered because a stale browser session still behaved like the older payload contract and hid the source-level correction.

## Plan of Work

Update `/hyperopen/src/hyperopen/views/active_asset/icon_button.cljs` so the probe image does not depend on a late lifecycle-only path. The implementation keeps the immediate probe/background-image rendering behavior but registers native `load` / `error` handlers before `:src` on both the visible loaded image and the hidden probe image, while retaining `attach-asset-icon-probe` as the mounted-state fallback for already-complete nodes.

Add or update focused tests in `/hyperopen/test/hyperopen/views/active_asset/icon_button_test.cljs` so the probe path is covered for the timing branch that currently escapes coverage. If the browser failure can be asserted cheaply using the existing Playwright or debug-bridge helpers under `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`, add the narrowest stable regression there as well; otherwise, document why the focused CLJS runtime seam is the accepted committed guardrail.

## Concrete Steps

Run all commands from `/Users/barry/.codex/worktrees/7ecd/hyperopen`.

1. Install dependencies if the worktree does not already have them.

       npm install

2. Compile the CLJS tests so `out/test.js` exists.

       npx shadow-cljs compile test

3. Implement the view fix and the regression tests.

4. Run the required repository gates.

       npm run check
       npm test
       npm run test:websocket

5. Re-run a browser session and confirm the live `/trade` page moves BTC into `loaded-icons` after the icon finishes loading.

       node tools/browser-inspection/src/cli.mjs session start --manage-local-app
       node tools/browser-inspection/src/cli.mjs navigate --session-id <id> --url http://localhost:8080/trade
       node tools/browser-inspection/src/cli.mjs eval --session-id <id> --expression "(() => { const snap = globalThis.HYPEROPEN_DEBUG.snapshot(); return snap['app-state']?.['asset-selector']?.['loaded-icons']; })()"
       node tools/browser-inspection/src/cli.mjs session stop --session-id <id>

Expected success after the fix: the eval result contains the BTC market key instead of `[]`, and the DOM no longer remains stranded on only the background-image probe path after the image is fully loaded.

Completed validation on 2026-03-31:

    npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "active asset icon promotes BTC"

Result:

    1 passed (6.6s)

Rebuilt browser check after `npx shadow-cljs compile app`:

    loaded-icons = ["perp:BTC"]
    missing-icons = []
    styleBgMatches = 0

## Validation and Acceptance

Acceptance is both behavioral and automated.

Behaviorally, after starting the managed local app and navigating to `/trade`, the active BTC icon should still appear immediately, but the debug snapshot must then show a non-empty `[:asset-selector :loaded-icons]` containing the BTC market key once the icon image is complete. The runtime should no longer show an idle empty icon-status queue while the probe image is already complete.

Automated validation must include `npm run check`, `npm test`, and `npm run test:websocket`. Any new regression test should fail before the fix and pass after it. If a browser-level regression is added, it must use the existing deterministic debug bridge and be narrow enough to avoid flaky dependence on external network timing.

## Idempotence and Recovery

The browser-inspection session commands are safe to repeat; each session can be stopped explicitly with `session stop`. `npm install` and `npx shadow-cljs compile test` are also safe to repeat. If a validation command fails partway through, fix the failing step and rerun the same command; no destructive cleanup should be necessary.

## Artifacts and Notes

The live repro prior to the fix produced these key facts:

    loaded-icons: []
    missing-icons: []
    runtime asset-icons pending: {}
    BTC probe img: complete=true, naturalWidth=150
    recentActionEffectTraces matching icon-status actions: []

These observations are the baseline that the implementation eliminated.

Post-fix validation evidence:

    npm run check
      passed

    npm test
      Ran 2927 tests containing 15679 assertions.
      0 failures, 0 errors.

    npm run test:websocket
      Ran 431 tests containing 2447 assertions.
      0 failures, 0 errors.

    Focused Playwright
      1 passed (6.6s)

## Interfaces and Dependencies

The final implementation must preserve these interfaces:

- `/hyperopen/src/hyperopen/views/active_asset/icon_button.cljs`
  `attach-asset-icon-probe` remains the probe lifecycle seam for the active asset button, or is replaced in-place with an equivalent helper that still receives `market-key` and `icon-src`.

- `/hyperopen/src/hyperopen/asset_selector/actions.cljs`
  `mark-loaded-asset-icon` and `mark-missing-asset-icon` must remain the public action IDs that transition icon state.

- `/hyperopen/src/hyperopen/asset_selector/icon_status_runtime.cljs`
  The queued animation-frame flush remains the state persistence path for icon statuses.

Revision note (2026-03-31 / Codex): created the active ExecPlan after reproducing the bug locally, then updated it after implementation to record the final event-ordering fix, the rebuilt browser confirmation, and the full validation results.
