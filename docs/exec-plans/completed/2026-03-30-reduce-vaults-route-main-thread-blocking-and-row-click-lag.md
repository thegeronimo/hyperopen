# Reduce Vaults-Route Main-Thread Blocking And Row-Click Lag

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The live `bd` issue for this work is `hyperopen-quct`. This plan also builds on the already-completed route-local render pass in `/hyperopen/docs/exec-plans/completed/2026-03-17-vaults-route-render-performance.md`; that earlier work removed obvious hidden-subtree and pure view-model waste, so this ticket is intentionally focused on the remaining fetch, hydration, interaction, and focus-state problems.

## Purpose / Big Picture

When a user moves from `/trade` to `/vaults`, the route can look loaded but still feel frozen for a moment. In the reported reproduction, the user clicked a vault row and had to click several times before the navigation went through, which is the classic symptom of main-thread work monopolizing input handling after the route appears. The same click also showed a visible focus box around the row, which is not the intended interaction pattern for this route.

After this change, a user should be able to navigate from `/trade` to `/vaults`, click a visible vault row once, and reach `/vaults/:vaultAddress` without a sticky or delayed interaction window. The first click should not paint a mouse-only focus ring, but keyboard navigation must still show visible focus. The route may still perform substantial data work because the vault index is large, but that work must no longer block first interaction or trigger redundant list bootstrap work when the user drills into a detail route immediately.

## Progress

- [x] (2026-03-30 19:02Z) Created and claimed `hyperopen-quct` for the vaults-route freeze, delayed row click, and focus-ring regression.
- [x] (2026-03-30 19:07Z) Re-read `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/BROWSER_TESTING.md`, and `/hyperopen/docs/agent-guides/browser-qa.md` to align this plan with repo workflow and browser-QA requirements.
- [x] (2026-03-30 19:07Z) Confirmed the current failure chain from the trace and code: full IndexedDB cache hydration blocks the list route before validation begins, early detail clicks can request list metadata again, and vault row links apply mouse-visible focus-ring classes.
- [x] (2026-03-30 19:07Z) Wrote the active ExecPlan and emitted the manager-compatible spec artifact at `/hyperopen/tmp/multi-agent/hyperopen-quct/spec.json`.
- [x] (2026-03-30 19:34Z) Worker milestone 1: split vault-index validator metadata from the heavy row cache, started live validation without waiting for the full IndexedDB restore, guarded late cache hydration with `:live-response-status`, and added local single-flight handling for the vault index and summaries bootstrap paths.
- [x] (2026-03-30 19:39Z) Worker milestone 2: suppressed duplicate list bootstrap effects on early detail clicks and removed mouse-triggered focus-ring classes from vault row and mobile-card navigation while preserving `focus-visible` styling.
- [x] (2026-03-30 19:48Z) Worker milestone 3: extended CLJS regression coverage for metadata-first warmup, stale-hydration safety, inflight dedupe, cache metadata persistence, and focus-visible-only row links; extended the committed Playwright vault regression with a row-class assertion for the mouse-focus-ring guard.
- [x] (2026-03-30 19:50Z) Browser verifier milestone: targeted Playwright `vault` regression passed, governed browser QA returned `PASS` for `vaults-route`, and the route-level interaction and jank-perf passes reported no issues across `375`, `768`, `1280`, and `1440`.
- [x] (2026-03-30 19:59Z) Final validation milestone: reran `npm run check`, `npm test`, and `npm run test:websocket` locally after the plan/process cleanup; all three passed, and the ExecPlan is ready to move to `completed/`.

## Surprises & Discoveries

- Observation: the live vault index remains large enough that “cached” still means heavy main-thread work.
  Evidence: a live fetch of `https://stats-data.hyperliquid.xyz/Mainnet/vaults` on 2026-03-30 returned about `9,355` rows and about `14.1 MB` of raw JSON before downstream normalization.

- Observation: the current cache-backed list effect waits for the full IndexedDB row record before it starts the live validation request.
  Evidence: `/hyperopen/src/hyperopen/vaults/effects.cljs` calls `load-vault-index-cache-record!`, applies `apply-vault-index-cache-hydration`, and only then invokes `request!` inside `api-fetch-vault-index-with-cache!`.

- Observation: the full IndexedDB read path converts the stored JSON-like object to CLJS data before any route-local guard can short-circuit it.
  Evidence: `/hyperopen/src/hyperopen/platform/indexed_db.cljs` performs `js->clj` in `get-json!`, and `/hyperopen/src/hyperopen/vaults/infrastructure/list_cache.cljs` then re-normalizes that full record into vault rows.

- Observation: detail-route support effects currently request list metadata again whenever the clicked vault is not yet present in `:merged-index-rows`, even if the list bootstrap is already in flight.
  Evidence: `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs` uses `current-vault-metadata-effects`, which only checks for the presence of a merged row and not whether `:vaults :loading :index?` or `:vaults :loading :summaries?` is already true.

- Observation: the visible click box is a separate UI bug, not the cause of the freeze.
  Evidence: `/hyperopen/src/hyperopen/views/vaults/list_view.cljs` includes both `focus:*` and `focus-visible:*` ring classes in `focus-visible-ring-classes`, and the row anchor applies that class list directly.

- Observation: the prior `/vaults` route-local render cleanup was necessary but not sufficient.
  Evidence: `/hyperopen/docs/exec-plans/completed/2026-03-17-vaults-route-render-performance.md` already removed hidden desktop/mobile subtree duplication and added list-view-model caching, yet the current trace still shows long tasks dominated by payload hydration and downstream route work rather than by the earlier view-only bottlenecks.

- Observation: the initial implementation accidentally returned `Promise.resolve nil` from `api-fetch-vault-index-with-cache!` after scheduling the single-flight work, which made the cache-path tests report `nil` responses even though requests had started.
  Evidence: the compiled output in `.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen.vaults.effects.js` showed `with_single_flight_BANG_(...)` followed by `return Promise.resolve(null);`, and the failing `hyperopen.vaults.effects-test` cases all observed `nil` responses plus missing persistence side effects until the closing structure was corrected.

- Observation: the repo’s documented “focused” CLJS command does not actually narrow execution in this workspace.
  Evidence: `npm test -- hyperopen.vaults.effects-test ...` still routes through `node out/test.js`, and the generated runner ignores CLI namespace arguments. The reliable path during this ticket was `npx shadow-cljs compile test` plus `node out/test.js`, with the full run used as the authoritative CLJS validation.

## Decision Log

- Decision: keep this ticket scoped to vault-specific loading, dedupe, and row-navigation focus handling before touching the generic render loop.
  Rationale: the trace and code review point to `vaults` fetch/hydration work as the first-order bottleneck. Broadening immediately into `/hyperopen/src/hyperopen/runtime/bootstrap.cljs` would increase risk without first proving the vault-specific fixes are insufficient.
  Date/Author: 2026-03-30 / Codex

- Decision: use one `worker` for all tracked-file edits and keep browser verification in a separate `browser_debugger` pass.
  Rationale: the affected code spans `src/**`, `test/**`, and `tools/playwright/test/**`, and a single implementation owner avoids merge conflicts while still keeping verification context isolated from the worker’s edit context.
  Date/Author: 2026-03-30 / Codex

- Decision: preserve caching and startup preview behavior, but decouple cheap validators from full row hydration and make late hydration safe.
  Rationale: the problem is not that caching exists; the problem is that the current cache shape forces a large deserialize/normalize step onto the hot interaction path. The smallest coherent fix is to keep the same user-visible cache behavior while changing when and how the full row payload is consumed.
  Date/Author: 2026-03-30 / Codex

- Decision: treat no-op or duplicate metadata work as a correctness issue, not merely an optimization.
  Rationale: when a user clicks into detail during the list warmup, re-requesting the same list bootstrap work directly competes with input handling and makes the route feel broken. Preventing that duplicate work is part of the functional fix.
  Date/Author: 2026-03-30 / Codex

- Decision: keep the vault bootstrap dedupe local to `vaults.effects` and `route_loading` instead of widening into a generic app-level fetch abstraction.
  Rationale: the targeted fix solved the observed duplicate bootstrap and interaction lag without touching `/hyperopen/src/hyperopen/runtime/bootstrap.cljs` or introducing cross-route caching complexity.
  Date/Author: 2026-03-30 / Codex

- Decision: treat `npm test` and the explicit `npx shadow-cljs compile test && node out/test.js` run as the effective CLJS validation path for this ticket, while recording that the plan’s “focused” CLI form is stale.
  Rationale: the current node-test runner is full-suite oriented, so forcing misleading “focused” invocations would hide the real validation behavior instead of improving it.
  Date/Author: 2026-03-30 / Codex

## Outcomes & Retrospective

The final fix reduced route-level ambiguity at the cost of a small amount of explicit bootstrap machinery. The new vault-index metadata record makes the “cheap validators first, heavy rows later” contract explicit, and the `:live-response-status` guard clarifies when cache hydration is still allowed to mutate state. That is added complexity, but it is localized to vault bootstrap and is easier to reason about than the earlier implicit ordering dependency.

The remaining complexity is operational rather than architectural. The repo still has a stale “focused CLJS” command in this plan, and the namespace-size checker required exception maintenance once the new regression coverage landed. Neither issue blocks the runtime fix, but both should be cleaned up separately if the team wants a smoother iteration loop on future tickets.

## Context and Orientation

The `/vaults` list route bootstraps through `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs`. For the list route, `load-vault-list-effects` schedules `[:effects/api-fetch-vault-index-with-cache]` and `[:effects/api-fetch-vault-summaries]`, plus user-specific equity loading when an effective viewer address exists. For the detail route, `detail-route-support-effects` can schedule those same list metadata effects again when the selected vault row is missing.

The full-index cache path lives in `/hyperopen/src/hyperopen/vaults/effects.cljs` and `/hyperopen/src/hyperopen/vaults/infrastructure/list_cache.cljs`. In this repository, a “cache hydration” means reading previously persisted browser data, normalizing it into the same shape as a live response, and inserting it into app state before or alongside a fresh network request. The current `api-fetch-vault-index-with-cache!` effect starts the route loading state, reads the full persisted row set from IndexedDB, applies `apply-vault-index-cache-hydration`, and only then kicks off the live index request.

The browser persistence layer is in `/hyperopen/src/hyperopen/platform/indexed_db.cljs`. The current `get-json!` helper opens IndexedDB, reads an object-store record, and converts the full result with `js->clj`. That cost is acceptable for small preference blobs, but it is expensive when the stored payload is the full normalized vault index. Because the store currently keeps validators such as `ETag` and `Last-Modified` inside the same record as the rows, there is no cheap way to start a conditional GET without reading the large record first.

State projections for vault data live in `/hyperopen/src/hyperopen/api/projections.cljs`. `apply-vault-index-cache-hydration` writes hydrated index rows and merged rows into app state, and `apply-vault-index-success` later writes the live response. Those two projections currently assume hydration always happens before the live response, which is why the worker must add an explicit stale-hydration rule before the effect pipeline can safely run cache and live validation in parallel.

The pure list view-model is in `/hyperopen/src/hyperopen/vaults/application/list_vm.cljs`. That file already caches parsed rows and final list models, but the cache keys are identity-based, so repeated no-op row replacement still forces a fresh list derivation. This file is not the first place to edit. It is a bounded follow-up surface only if the post-fix trace still shows vault-specific render churn after the fetch/hydration contract is corrected.

The row-navigation UI is in `/hyperopen/src/hyperopen/views/vaults/list_view.cljs`. The desktop table row link and mobile card link are plain anchors that rely on CSS classes for focus styling. The current class helper includes both `focus:*` and `focus-visible:*`, which means mouse clicks paint the ring immediately. The fix here should preserve keyboard-visible focus and avoid changing navigation semantics unless the later browser pass proves the anchor itself is part of the problem.

Existing regression coverage already maps to the expected edit surfaces. `/hyperopen/test/hyperopen/vaults/effects_test.cljs` covers the cache-backed index effect, `/hyperopen/test/hyperopen/vaults/application/route_loading_test.cljs` covers list/detail effect ordering, `/hyperopen/test/hyperopen/api/projections_test.cljs` covers index hydration and merge behavior, `/hyperopen/test/hyperopen/views/vaults/list_view_test.cljs` covers route link rendering and focus classes, and `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` already contains vault-route regressions that can host the new click-responsiveness/dedupe scenario.

## Execution Model

The parent thread remains the orchestrator and keeps this ExecPlan plus `/hyperopen/tmp/multi-agent/hyperopen-quct/spec.json` current. One `worker` owns every tracked-file edit for this ticket, including the active ExecPlan updates that describe implementation progress. No second worker should touch `/hyperopen/src/**`, `/hyperopen/test/**`, or `/hyperopen/tools/playwright/test/**` for this ticket.

After the worker finishes a coherent pass, a separate `browser_debugger` owns verification. That verifier must first run the smallest committed Playwright command for the changed flow, then run governed browser QA for `vaults-route`, and then reproduce the original user flow from `/trade` to `/vaults` with a first-row click. If the verifier still sees delayed first-click handling, duplicate metadata requests, or a mouse-visible focus ring, the verifier returns concrete findings plus artifact paths and the parent thread reopens the worker on only those findings. The loop ends only when deterministic tests pass, browser QA is explicitly accounted for, and the manual reproduction no longer needs repeated clicks.

After browser verification, a read-only `reviewer` should scan for correctness risks such as stale-cache overwrites, broken `304 Not Modified` behavior, or missing regression coverage before the parent thread runs the full repository gates.

## Plan of Work

Start in `/hyperopen/src/hyperopen/vaults/infrastructure/list_cache.cljs`. Add a lightweight metadata record for the vault index cache that contains only the fields needed to send validators quickly: the cache key, version, saved timestamp, `etag`, and `last-modified`. Keep the full row cache record so warm route content can still be restored, but stop forcing the list route to read the full row record merely to recover validators. Use the existing `vault-index-cache` IndexedDB object store and a second logical key so no schema migration is required. The read path must stay backward-compatible with already-persisted row records that do not yet have the new metadata companion.

Then update `/hyperopen/src/hyperopen/vaults/effects.cljs`. Change `api-fetch-vault-index-with-cache!` so it can start the live validation request as soon as cheap validators are available from either in-memory state or the new metadata record. If the list already has live rows, it should keep the current fast path and just validate. If the list is cold, it should launch full row-cache hydration in parallel instead of serially blocking the request on `load-vault-index-cache-record!`. The effect must also become single-flight for the vault index warmup path so a list-route load and an immediate detail click reuse the same index work instead of starting another full GET. Apply the same single-flight principle to summaries if route-level guards are not sufficient on their own.

Update `/hyperopen/src/hyperopen/api/projections.cljs` next so the new parallel pipeline is safe. `apply-vault-index-cache-hydration` must become a guarded hydration rather than an unconditional overwrite. A late cache result must not replace rows that were already filled by a fresher live `:ok` response, but a late cache result must still be allowed to populate rows after a `304 Not Modified` response when the cache rows were not available yet. Keep the loading flags and `:vaults-ui :list-loading?` behavior accurate across all three cases: cache-first, live-first, and error-after-cache.

After the effect contract is safe, tighten `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs`. `current-vault-metadata-effects` should stop scheduling `:effects/api-fetch-vault-index-with-cache` or `:effects/api-fetch-vault-summaries` when the corresponding load is already active, and it must still request the missing support data on a true cold detail route with no inflight work. The detail-route ordering should remain projection-first, and benchmark-related support effects must continue to avoid duplicate summary work.

Fix the visible click box in `/hyperopen/src/hyperopen/views/vaults/list_view.cljs` after the loading work is in place. Split the current ring helper into a keyboard-visible-only variant for navigational anchors and a broader variant for controls that still need regular focus styling. Apply the keyboard-only variant to the vault row link and the mobile vault card. Do not remove keyboard focus indication from menus, buttons, or the wallet-connect control.

Only after the main loading contract is fixed should the worker inspect `/hyperopen/src/hyperopen/vaults/application/list_vm.cljs` for follow-up churn. If the fresh trace still shows vault-specific rerender cost after the fetch/hydration changes, preserve row identity on no-op hydration or summary merges and extend the list-view-model cache tests accordingly. If the fresh trace no longer shows route-local render churn as a dominant cost, record that result here and keep `list_vm.cljs` untouched.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/00a4/hyperopen`.

During implementation, keep this plan current after each meaningful milestone and write any new design choice into `Decision Log`.

Run focused CLJS tests while iterating:

    npm test -- hyperopen.vaults.effects-test hyperopen.vaults.application.route-loading-test hyperopen.api.projections-test hyperopen.views.vaults.list-view-test

Implementation note from this ticket: the current node-test runner ignores CLI namespace args, so the practical local loop here was `npx shadow-cljs compile test` followed by `node out/test.js`, with the full suite acting as the authoritative CLJS pass.

If the worker touches `/hyperopen/src/hyperopen/vaults/application/list_vm.cljs` or its cache rules, extend the focused loop to include:

    npm test -- hyperopen.vaults.effects-test hyperopen.vaults.application.route-loading-test hyperopen.api.projections-test hyperopen.views.vaults.list-view-test hyperopen.vaults.application.list-vm-test

Run the smallest relevant committed browser regression before broader QA:

    npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "vault"

Run governed browser QA for the route after the targeted Playwright pass succeeds:

    npm run qa:design-ui -- --targets vaults-route --manage-local-app

Then run the required repository gates:

    npm run check
    npm test
    npm run test:websocket

If a browser verification pass still shows delayed first-click handling, duplicate metadata requests, or a row focus-ring regression, keep `hyperopen-quct` open, add the new evidence to `Surprises & Discoveries`, and re-enter the worker/browser loop instead of broadening scope blindly.

## Validation and Acceptance

Acceptance is behavioral first.

1. Starting from `/trade`, navigating to `/vaults`, and clicking a visible vault row once must move to `/vaults/:vaultAddress` without needing repeated clicks.
2. A mouse click on a vault row or mobile vault card must not paint the row-level focus ring, but keyboard tab navigation must still expose a visible focus ring on those same links.
3. A quick `/vaults` list load followed immediately by a detail navigation must not issue redundant vault-index or vault-summary bootstrap requests once the first list bootstrap is already in flight.

Acceptance is also structural.

1. Focused CLJS tests must prove that the cache-backed index request can begin without waiting for full row-cache hydration, that stale cache hydration cannot overwrite a fresher live response, and that a `304 Not Modified` path can still adopt cached rows safely.
2. Route-loading tests must prove that early detail clicks stop scheduling duplicate list metadata effects while true cold detail routes still request the needed support data.
3. View tests must prove that vault row navigation keeps `focus-visible` styling and drops mouse-triggered `focus:ring-*` styling.
4. The targeted Playwright regression must cover the list-to-detail route interaction with network stubs and assert that one click reaches the detail route without redundant list bootstrap requests.
5. Governed browser QA for `vaults-route` must explicitly account for all required passes and widths, and the `interaction` plus `jank-perf` passes must not report the original sticky-click behavior.

The final required commands are:

    npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "vault"
    npm run qa:design-ui -- --targets vaults-route --manage-local-app
    npm run check
    npm test
    npm run test:websocket

Observed final results for this ticket:

1. `npx shadow-cljs compile test && node out/test.js` passed with `Ran 2937 tests containing 15737 assertions. 0 failures, 0 errors.`
2. `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "vault"` passed with `4 passed`.
3. `npm run qa:design-ui -- --targets vaults-route --manage-local-app` returned `reviewOutcome: "PASS"` and `state: "PASS"` for `vaults-route`.
4. `npm run check` passed after updating namespace-size exceptions for the touched oversized namespaces.
5. `npm test` passed with `Ran 2937 tests containing 15737 assertions. 0 failures, 0 errors.`
6. `npm run test:websocket` passed with `Ran 430 tests containing 2441 assertions. 0 failures, 0 errors.`

## Idempotence and Recovery

This work is safe to re-run because it is additive and localized to the vault loading and view path. The new cache metadata record should be written in parallel with the existing full row record, so existing caches remain readable during rollout. If the metadata record is missing or stale, the effect should fall back to current in-memory validators or an unconditional request rather than failing the route.

If the new parallel hydration path produces an ordering bug, temporarily disable only the late-hydration apply step, re-run the focused CLJS tests, and restore the safest previous behavior before widening the fix. Do not revert to destructive cache clearing as the default recovery path because the main objective is to keep warm navigation fast and interactive.

If the browser verifier still sees render-cost spikes after the fetch/dedupe fixes, document the exact post-fix trace evidence before touching `/hyperopen/src/hyperopen/runtime/bootstrap.cljs` or introducing broader render-loop changes. That follow-up should happen only with fresh proof that vault-specific loading changes were not enough.

## Artifacts and Notes

Initial planning artifacts for this ticket:

    bd issue: hyperopen-quct
    ExecPlan: /hyperopen/docs/exec-plans/completed/2026-03-30-reduce-vaults-route-main-thread-blocking-and-row-click-lag.md
    Spec artifact: /hyperopen/tmp/multi-agent/hyperopen-quct/spec.json
    User trace: /Users/barry/Downloads/Trace-20260330T144241.json

Key baseline evidence gathered before implementation:

    Live vault index fetch on 2026-03-30:
      rows: 9355
      raw bytes: 14108131

    Trace findings from /Users/barry/Downloads/Trace-20260330T144241.json:
      main-thread long task around +1.20s while entering /vaults
      later long tasks around +1.23s and +1.33s while vault state settles
      first click delay observed before route navigation, including a retry with much larger queued delay

Plan revision note: 2026-03-30 19:07Z - Created the active ExecPlan and manager-compatible spec artifact for `hyperopen-quct` after trace analysis confirmed the remaining `/vaults` regression is in the cache hydration and interaction path rather than in the earlier route-local render surfaces alone.

Implementation artifacts gathered during validation:

    Browser QA run: /Users/barry/.codex/worktrees/00a4/hyperopen/tmp/browser-inspection/design-review-2026-03-30T19-55-58-848Z-21eeee72

## Interfaces and Dependencies

No public route contract should change. The main implementation surfaces are:

- `/hyperopen/src/hyperopen/vaults/infrastructure/list_cache.cljs`
- `/hyperopen/src/hyperopen/vaults/effects.cljs`
- `/hyperopen/src/hyperopen/api/projections.cljs`
- `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs`
- `/hyperopen/src/hyperopen/views/vaults/list_view.cljs`
- `/hyperopen/test/hyperopen/vaults/effects_test.cljs`
- `/hyperopen/test/hyperopen/vaults/application/route_loading_test.cljs`
- `/hyperopen/test/hyperopen/api/projections_test.cljs`
- `/hyperopen/test/hyperopen/views/vaults/list_view_test.cljs`
- `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`

Possible follow-up surfaces, only if fresh trace evidence justifies them, are:

- `/hyperopen/src/hyperopen/vaults/application/list_vm.cljs`
- `/hyperopen/test/hyperopen/vaults/application/list_vm_test.cljs`

Reuse existing internal facilities where they already exist. For POST info calls, prefer the current request-policy and single-flight patterns already used in `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs`. For the GET vault index path, keep any new dedupe local to the vault bootstrap path unless a broader fetch abstraction change becomes necessary and is documented here first. For browser verification, follow `/hyperopen/docs/BROWSER_TESTING.md` and `/hyperopen/docs/agent-guides/browser-qa.md`, which require Playwright for committed regression coverage and governed browser QA for the final UI-facing pass.
