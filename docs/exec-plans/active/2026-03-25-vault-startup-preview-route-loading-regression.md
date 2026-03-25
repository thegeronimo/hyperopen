# Re-scope Vault Startup Preview So Only `/vaults` Pays For It

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The primary live `bd` issue is `hyperopen-ta4x`. Related route-scope follow-up context also exists in `hyperopen-lgva`, but `bd` remains the status source of truth while this plan is active.

## Purpose / Big Picture

The vault hard-reload preview work was meant to make `/vaults` feel faster on a cold load. Instead, vault-specific loading behavior leaked into routes that should never know about it. Users now see a vault-style skeleton while `/trade`, `/portfolio`, or `/vaults/:address` are loading, and the attached March 25 trade trace shows vault preview code being parsed on the trade startup path. After this fix, only the exact `/vaults` list route should restore or persist the startup preview. The root app shell must go back to the generic deferred loader for unresolved route modules, and non-vault routes must stop paying for vault preview code during startup.

## Progress

- [x] (2026-03-25 22:42Z) Created and claimed `hyperopen-ta4x`, linked the regression to an active ExecPlan, and collected the attached trade trace at `/Users/barry/Downloads/Trace-20260325T183405.json`.
- [x] (2026-03-25 22:42Z) Identified commit `a7d85dd956081c34d27804fe93b57aee4221fd1f` (`Add vaults hard-reload preview cache`) as the main regression commit because it imported `hyperopen.views.vaults.preview-shell` into `/hyperopen/src/hyperopen/views/app_view.cljs` and added preview-cache persistence to the shared vault-index effect.
- [x] (2026-03-25 22:44Z) Identified commit `91f7dec23614ed0d37d6a3f6d6a5923e7dd9724d` (`Optimize vault benchmark loading and fix vault range menu`) as the enabling change that made `/portfolio` and `/vaults/:address` legitimately call vault list support fetches through `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs`.
- [x] (2026-03-25 22:46Z) Confirmed from the attached trade trace that `/trade` startup evaluates `hyperopen.vaults.application.list_vm.js`, `hyperopen.vaults.infrastructure.preview_cache.js`, and `hyperopen.views.vaults.preview_shell.js`, proving the regression is both a UI leak and a startup-bundle leak.
- [x] (2026-03-25 22:48Z) Re-scoped the root app shell in `/hyperopen/src/hyperopen/views/app_view.cljs` by removing the eager vault preview import and unresolved-route preview branch, and deleted the now-unused `/hyperopen/src/hyperopen/views/vaults/preview_shell.cljs` namespace.
- [x] (2026-03-25 22:53Z) Re-scoped startup preview persistence in `/hyperopen/src/hyperopen/vaults/effects.cljs` so successful vault-index fetches only write the preview cache when the active route is the exact vault list route, not `/portfolio` and not `/vaults/:address`.
- [x] (2026-03-25 22:56Z) Rewrote `/hyperopen/src/hyperopen/vaults/infrastructure/preview_cache.cljs` to build the stored preview record with a small local projection instead of depending on the full vault list view-model namespace, removing the heavy list-model edge from the startup preview cache namespace.
- [x] (2026-03-25 22:57Z) Updated route-loading coverage in `/hyperopen/test/hyperopen/views/app_view_test.cljs`, removed the stale unused require from `/hyperopen/test/hyperopen/vaults/infrastructure/preview_cache_test.cljs`, and added `/hyperopen/test/hyperopen/vaults/effects_preview_scope_test.cljs` to prove `/portfolio` and vault detail loads do not persist startup preview data.
- [x] (2026-03-25 23:12Z) Ran the smallest relevant browser smoke first with `npm run test:playwright:smoke -- --grep "main route smoke"` and verified all 10 smoke cases passed.
- [x] (2026-03-25 23:36Z) Ran the first round of required repo gates: `npm run check`, `npm test`, and `npm run test:websocket`; all passed on the patched tree.
- [x] (2026-03-25 23:51Z) Collected the user's follow-up evidence from `/Users/barry/Downloads/Trace-20260325T191051.json` and the attached screenshot, then confirmed the remaining startup leak was now limited to `hyperopen.vaults.infrastructure.preview_cache.js` on `http://localhost:8083`.
- [x] (2026-03-25 23:58Z) Removed the remaining main-bundle `preview_cache` edge by taking vault startup preview restore out of `/hyperopen/src/hyperopen/startup/init.cljs`, replacing the eager preview-cache import in `/hyperopen/src/hyperopen/runtime/effect_adapters/vaults.cljs` with a route-module-owned dynamic hook, adding `/hyperopen/src/hyperopen/views/vaults/startup_preview.cljs`, and wiring that namespace into the `vaults_route` module through `/hyperopen/shadow-cljs.edn` and `/hyperopen/src/hyperopen/route_modules.cljs`.
- [x] (2026-03-26 00:09Z) Added the second-round regression coverage in `/hyperopen/test/hyperopen/route_modules_test.cljs`, `/hyperopen/test/hyperopen/runtime/effect_adapters/vaults_test.cljs`, `/hyperopen/test/hyperopen/startup/init_test.cljs`, and `/hyperopen/test/hyperopen/views/vaults/startup_preview_test.cljs`, then regenerated `/hyperopen/test/test_runner_generated.cljs`.
- [x] (2026-03-26 00:17Z) Captured a fresh post-fix trade trace at `/Users/barry/.codex/worktrees/d5eb/hyperopen/tmp/trade-startup-trace-2026-03-25-postfix.json` against `http://127.0.0.1:4201/trade` and confirmed `rg -n "hyperopen\\.(views\\.vaults\\.preview_shell|vaults\\.infrastructure\\.preview_cache|vaults\\.application\\.list_vm|views\\.vaults\\.list_view|views\\.vaults\\.detail_view)\\.js"` returns no matches.
- [x] (2026-03-26 00:27Z) Re-ran the full required validation set after the route-module ownership refactor: `npm run test:playwright:smoke -- --grep "main route smoke"`, `npm test`, `npm run test:websocket`, and `npm run check`; all passed.
- [ ] Confirm the user-managed `http://localhost:8083` runtime is restarted or rebuilt so follow-up manual traces and screenshots reflect the patched startup graph instead of the stale `preview_cache` import.

## Surprises & Discoveries

- Observation: the root startup restoration path was already narrower than the user-visible regression suggested.
  Evidence: `/hyperopen/src/hyperopen/startup/init.cljs` only restores the startup preview on a hard load when `vaults-list-route?` is true, so the cross-route leak was not caused by unconditional restoration at app boot.

- Observation: the real cross-route leak came from persistence, not just restoration.
  Evidence: commit `a7d85dd956081c34d27804fe93b57aee4221fd1f` added `persist-vault-startup-preview-from-store!` to `/hyperopen/src/hyperopen/vaults/effects.cljs`, and that helper ran after every successful shared vault-index fetch without checking whether the current route was the vault list route.

- Observation: `/portfolio` and vault detail started sharing the vault list support fetch path before the preview-cache work landed.
  Evidence: commit `91f7dec23614ed0d37d6a3f6d6a5923e7dd9724d` changed `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs` so `/portfolio` uses `portfolio-route-support-effects` and vault detail uses `detail-route-support-effects`, both of which can trigger the shared vault-index loading path.

- Observation: the trade trace proves the performance regression was broader than the visible skeleton leak.
  Evidence: `rg -n "hyperopen\\.(views\\.vaults\\.preview_shell|vaults\\.infrastructure\\.preview_cache|vaults\\.application\\.list_vm)\\.js" /Users/barry/Downloads/Trace-20260325T183405.json` matches all three namespaces inside the trade capture, including a CPU profile node for `hyperopen.vaults.application.list_vm.js` under `http://localhost:8081/js/main.js`.

- Observation: fixing only the root shell would stop the wrong skeleton, but it would not fully restore the original startup graph contract.
  Evidence: before the current patch, `/hyperopen/src/hyperopen/vaults/infrastructure/preview_cache.cljs` depended on the vault list-model builder namespace, so any path that loaded preview-cache utilities also kept a heavy vault list edge reachable from startup code.

- Observation: the namespace-size guard forced the new route-scope effect tests into their own file.
  Evidence: the first `npm run check` run failed on `/hyperopen/test/hyperopen/vaults/effects_test.cljs` exceeding the namespace-size exception threshold, so the new coverage was moved into `/hyperopen/test/hyperopen/vaults/effects_preview_scope_test.cljs` and the rerun passed.

- Observation: the user's follow-up trace proved the first patch fixed most of the leak, but not all of it.
  Evidence: `rg -n "hyperopen\\.(views\\.vaults\\.preview_shell|vaults\\.infrastructure\\.preview_cache|vaults\\.application\\.list_vm|views\\.vaults\\.list_view|views\\.vaults\\.detail_view)\\.js" /Users/barry/Downloads/Trace-20260325T191051.json` only matches `hyperopen.vaults.infrastructure.preview_cache.js` on `http://localhost:8083`; it does not match `preview_shell`, `list_vm`, `list_view`, or `detail_view`.

- Observation: the remaining `preview_cache` leak came from two always-loaded startup edges, not from the root route view anymore.
  Evidence: `/hyperopen/src/hyperopen/startup/init.cljs` still required `hyperopen.vaults.infrastructure.preview-cache` for the hard-load restore path, and `/hyperopen/src/hyperopen/runtime/effect_adapters/vaults.cljs` still required the same namespace to provide `:persist-vault-startup-preview-record!`, which kept `preview_cache` reachable from `main.js` even on `/trade`.

- Observation: the clean local post-fix trade trace and the user's failing trace came from different runtimes.
  Evidence: the failing follow-up trace references `http://localhost:8083/js/cljs-runtime/hyperopen.vaults.infrastructure.preview_cache.js`, while the clean post-fix trace at `/Users/barry/.codex/worktrees/d5eb/hyperopen/tmp/trade-startup-trace-2026-03-25-postfix.json` records `navigationStart` for `http://127.0.0.1:4201/trade` and the vault namespace grep returns exit code `1`.

## Decision Log

- Decision: treat commit `a7d85dd956081c34d27804fe93b57aee4221fd1f` as the primary regression commit and `91f7dec23614ed0d37d6a3f6d6a5923e7dd9724d` as the enabling change.
  Rationale: the March 18 route-loading work made vault index support effects legitimate on `/portfolio` and vault detail, but the March 21 preview work is where vault preview persistence and root-shell preview rendering were attached to those shared paths.
  Date/Author: 2026-03-25 / Codex

- Decision: fix the regression in three slices rather than only reverting the visible loader branch.
  Rationale: removing the root preview shell stops the wrong UI, but the user also reported a performance regression. To address both, the patch also gates preview persistence to the exact vault list route and removes the preview-cache dependency on the full list-model builder.
  Date/Author: 2026-03-25 / Codex

- Decision: keep startup preview ownership inside vault-only code paths and make the root shell generic again.
  Rationale: `/hyperopen/src/hyperopen/views/app_view.cljs` is part of the always-loaded startup graph. Route-specific preview behavior belongs either after the deferred route module is ready or behind route-scoped infrastructure, not in the top-level route switch.
  Date/Author: 2026-03-25 / Codex

- Decision: replace the preview-cache builder dependency with a small local projection instead of trying to lazy-load the full list view model.
  Rationale: the preview cache only needs a bounded record for storage. Reusing the full list-model namespace preserved an unnecessary startup edge and made the cache layer depend on presentation-oriented logic that is irrelevant to non-vault routes.
  Date/Author: 2026-03-25 / Codex

- Decision: keep fresh profiling as optional follow-up evidence instead of blocking the code fix on another browser capture.
  Rationale: the behavioral regression is covered by unit tests and smoke tests, and all required repo gates passed. A fresh trade trace would strengthen the performance proof, but the fix should not wait on another manual profiling session unless the user wants the extra artifact.
  Date/Author: 2026-03-25 / Codex

- Decision: move vault startup preview restore and persist behind the `vaults_route` deferred module instead of leaving any preview-cache hooks in `main`.
  Rationale: the user's second trace showed that route-gating the effect and restoring the generic loader fixed the visible leak but still left `preview_cache` reachable from always-loaded startup code. Treating startup preview as a vault route module concern restores the original bundle-splitting contract and keeps `/trade` from parsing any vault preview namespaces.
  Date/Author: 2026-03-25 / Codex

- Decision: keep the ExecPlan active until the user-managed `localhost:8083` runtime is restarted or rebuilt.
  Rationale: the patched worktree is validated and the local post-fix trade trace is clean, but the latest user-supplied trace still comes from a stale app runtime. Keeping one explicit unchecked follow-up item makes that external validation gap visible without pretending the stale trace is still evidence against the patched tree.
  Date/Author: 2026-03-26 / Codex

## Outcomes & Retrospective

The implemented fix reduced overall complexity. The root app shell no longer owns a vault-only loading branch, the shared vault-index effect no longer writes vault preview data on routes that only need support data, the preview-cache namespace now owns a small storage projection instead of importing the full vault list model, and the remaining startup-preview restore or persist hooks now live behind the deferred `vaults_route` module instead of inside `main`. That is a cleaner match for the original intent: `/vaults` gets an optimization, while `/trade`, `/portfolio`, and `/vaults/:address` stay on their normal loading paths and their startup bundle no longer pulls in vault preview code.

The performance evidence is now closed on the patched worktree. Route smoke, the required repository gates, and the fresh trade capture at `/Users/barry/.codex/worktrees/d5eb/hyperopen/tmp/trade-startup-trace-2026-03-25-postfix.json` all passed, and the vault namespace grep returns no matches for that trace. The remaining gap is environmental, not code-level: the user's long-running `localhost:8083` app instance needs to be restarted or rebuilt so its manual traces reflect the patched startup graph.

## Context and Orientation

`/hyperopen/src/hyperopen/views/app_view.cljs` is the top-level application shell. It is part of the default startup path for `/trade`, so every namespace imported there contributes directly to the initial JavaScript that trade users parse and evaluate.

`/hyperopen/src/hyperopen/route_modules.cljs` owns deferred route modules such as `/portfolio`, `/leaderboard`, and `/vaults`. Before March 21, unresolved deferred routes used the generic `deferred-route-loading-shell` from the root app shell.

Commit `91f7dec23614ed0d37d6a3f6d6a5923e7dd9724d` changed `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs` so `/portfolio` and `/vaults/:address` can fetch vault list support data for benchmark and detail contexts. That change by itself was not the regression, but it widened the number of routes that now exercise the shared vault-index effect.

Commit `a7d85dd956081c34d27804fe93b57aee4221fd1f` added three new behaviors that together created the current regression. First, it imported a vault-only preview shell into `/hyperopen/src/hyperopen/views/app_view.cljs`, which made the unresolved `/vaults` root loader route-specific. Second, it added startup-preview persistence to `/hyperopen/src/hyperopen/vaults/effects.cljs`, attaching vault preview writes to a shared vault-index success path. Third, it added `/hyperopen/src/hyperopen/vaults/infrastructure/preview_cache.cljs`, which in the present-day codebase still depended on the heavy vault list-model builder namespace before this patch.

Before the second fix in this plan, `/hyperopen/src/hyperopen/startup/init.cljs` still restored a saved preview only on hard loads to the exact vault list route, and `/hyperopen/src/hyperopen/runtime/effect_adapters/vaults.cljs` still imported the preview-cache namespace to provide the persistence seam. That meant the wrong skeleton on `/trade` and `/portfolio` was no longer caused by unconditional boot-time restoration, but the extra startup cost still came from `preview_cache` remaining reachable from code that lives on the trade startup path. After the second fix, startup init no longer knows about vault startup preview at all; `/hyperopen/src/hyperopen/route_modules.cljs` restores the preview only after the deferred `vaults_route` module resolves and only for the exact vault list route, while `/hyperopen/src/hyperopen/runtime/effect_adapters/vaults.cljs` resolves the persist hook dynamically only if the vault route module has been loaded.

The user-supplied trace is `/Users/barry/Downloads/Trace-20260325T183405.json`. It is a trade-route capture. In that file, `ScriptCatchup` entries show `hyperopen.vaults.application.list_vm.js`, `hyperopen.vaults.infrastructure.preview_cache.js`, and `hyperopen.views.vaults.preview_shell.js` participating in the trade startup session, which should never happen if the vault preview remains properly scoped.

The user's follow-up trace is `/Users/barry/Downloads/Trace-20260325T191051.json`. It still shows `hyperopen.vaults.infrastructure.preview_cache.js`, but only that namespace, and it points at `http://localhost:8083`. The fresh local post-fix trace captured in this session is `/Users/barry/.codex/worktrees/d5eb/hyperopen/tmp/trade-startup-trace-2026-03-25-postfix.json`; it targets `http://127.0.0.1:4201/trade` and contains none of the vault namespaces listed above.

## Plan of Work

The first edit restores the root shell contract. In `/hyperopen/src/hyperopen/views/app_view.cljs`, remove the vault preview import and the special unresolved `/vaults` branch so every unresolved deferred route falls back to the generic route loader again. Once that branch is gone, `/hyperopen/src/hyperopen/views/vaults/preview_shell.cljs` has no callers and should be deleted.

The second edit restores route scoping for preview persistence. In `/hyperopen/src/hyperopen/vaults/effects.cljs`, treat startup preview persistence as a vault list concern, not a generic vault-index concern. The helper should check the current route and only write preview data when the active route parses as the vault list route. This prevents `/portfolio` support loads and vault-detail support loads from overwriting preview state.

The third edit restores a lightweight startup dependency graph. In `/hyperopen/src/hyperopen/vaults/infrastructure/preview_cache.cljs`, keep the storage normalization and restore logic, but stop depending on the full vault list-model namespace to build the persisted record. Build the bounded preview record locally using the same default sort, snapshot-range, and row-shaping rules needed for the stored preview payload.

The fourth edit updates tests and validations. `/hyperopen/test/hyperopen/views/app_view_test.cljs` must prove unresolved vault loads use the generic loader again. `/hyperopen/test/hyperopen/vaults/effects_preview_scope_test.cljs` must prove `/portfolio` and `/vaults/:address` do not persist startup preview state after vault-index fetch success. Existing preview-cache tests must still pass so the stored record shape remains stable.

The fifth edit completes the startup graph separation. `/hyperopen/src/hyperopen/startup/init.cljs` should stop restoring vault startup preview from `main`, `/hyperopen/src/hyperopen/runtime/effect_adapters/vaults.cljs` should stop eagerly importing the preview-cache seam, `/hyperopen/src/hyperopen/views/vaults/startup_preview.cljs` should own the restore and persist helpers inside the deferred vault module, and `/hyperopen/src/hyperopen/route_modules.cljs` plus `/hyperopen/shadow-cljs.edn` should ensure those hooks only exist once `vaults_route` is loaded.

## Concrete Steps

From `/Users/barry/.codex/worktrees/d5eb/hyperopen`:

1. Diagnose the regression seam and culprit commits.

       git show --stat --summary --format=fuller a7d85dd956081c34d27804fe93b57aee4221fd1f
       git show --stat --summary --format=fuller 91f7dec23614ed0d37d6a3f6d6a5923e7dd9724d
       rg -n "hyperopen\\.(views\\.vaults\\.preview_shell|vaults\\.infrastructure\\.preview_cache|vaults\\.application\\.list_vm)\\.js" /Users/barry/Downloads/Trace-20260325T183405.json

   Expect to see the March 21 preview-cache commit touching the root app shell and vault preview cache, the March 18 route-loading commit widening vault support fetch usage, and the trade trace containing the three vault namespaces listed above.

2. Re-scope the root loader and remove the dead shell namespace.

   Edit `/hyperopen/src/hyperopen/views/app_view.cljs` to delete the root-level vault preview import and unresolved `/vaults` preview branch. Delete `/hyperopen/src/hyperopen/views/vaults/preview_shell.cljs` after confirming no callers remain.

3. Re-scope preview persistence and trim the preview-cache dependency graph.

   Edit `/hyperopen/src/hyperopen/vaults/effects.cljs` to gate preview persistence on the exact vault list route. Edit `/hyperopen/src/hyperopen/vaults/infrastructure/preview_cache.cljs` so the stored preview record is built locally without requiring the heavy vault list-model namespace.

4. Update tests.

   Edit `/hyperopen/test/hyperopen/views/app_view_test.cljs` so unresolved vault routes assert the generic route shell. Keep `/hyperopen/test/hyperopen/vaults/infrastructure/preview_cache_test.cljs` aligned with the lightweight cache builder. Add `/hyperopen/test/hyperopen/vaults/effects_preview_scope_test.cljs` for `/portfolio` and vault-detail route persistence coverage.

5. Remove the remaining startup `preview_cache` edges from `main`.

   Edit `/hyperopen/src/hyperopen/startup/init.cljs` to remove the hard-load preview restore from app startup. Add `/hyperopen/src/hyperopen/views/vaults/startup_preview.cljs` with the restore and persist helpers. Edit `/hyperopen/src/hyperopen/route_modules.cljs` to call the restore helper only after `vaults_route` is ready for the exact `/vaults` list route, edit `/hyperopen/src/hyperopen/runtime/effect_adapters/vaults.cljs` so persistence resolves through a dynamic route-module hook, and update `/hyperopen/shadow-cljs.edn` so the new startup preview namespace ships in `vaults_route`.

6. Run the required validations and capture the clean trade trace.

       npm run test:playwright:smoke -- --grep "main route smoke"
       npm test
       npm run test:websocket
       npm run check
       rg -n "hyperopen\\.(views\\.vaults\\.preview_shell|vaults\\.infrastructure\\.preview_cache|vaults\\.application\\.list_vm|views\\.vaults\\.list_view|views\\.vaults\\.detail_view)\\.js" /Users/barry/Downloads/Trace-20260325T191051.json
       rg -n "hyperopen\\.(views\\.vaults\\.preview_shell|vaults\\.infrastructure\\.preview_cache|vaults\\.application\\.list_vm|views\\.vaults\\.list_view|views\\.vaults\\.detail_view)\\.js" /Users/barry/.codex/worktrees/d5eb/hyperopen/tmp/trade-startup-trace-2026-03-25-postfix.json

   Expect the smoke suite to pass all 10 route cases, the required repository gates to exit with code `0`, the user trace grep to match only `preview_cache.js` on the stale `localhost:8083` runtime, and the post-fix trace grep to return no matches.

## Validation and Acceptance

Acceptance is both behavioral and performance-scoped.

On `/trade`, unresolved route loading must never render vault preview content, and the startup graph should no longer depend on vault-only preview namespaces through either the root shell or any startup/runtime adapter that lives in `main`. The attached March 25 trace is the failing baseline. The follow-up March 25 user trace should still show only `preview_cache.js` when captured from the stale `localhost:8083` app, while the fresh post-fix trade trace from the patched tree must not contain any of the vault preview namespaces that appear in the baseline trace.

On `/portfolio` and `/vaults/:address`, successful vault-index support loads must not persist startup preview data. The new effect tests in `/hyperopen/test/hyperopen/vaults/effects_preview_scope_test.cljs` are the regression lock for that route scoping.

On `/vaults`, unresolved module loading must now use the generic route shell from the root app view. Once the vault route module is ready, vault-owned code may still show its existing stale or startup preview behavior inside the vault route itself. The app-view tests in `/hyperopen/test/hyperopen/views/app_view_test.cljs` prove the restored root-shell contract.

Required validation commands for this task are:

    npm run test:playwright:smoke -- --grep "main route smoke"
    npm test
    npm run test:websocket
    npm run check

## Idempotence and Recovery

These edits are safe to re-run because they only re-scope route-loading behavior, cache persistence conditions, and tests. If a future edit accidentally breaks vault list startup preview on the real `/vaults` route, restore the failing worktree state with a targeted patch to the in-progress files and rerun the route smoke suite plus the relevant vault tests. Do not reset unrelated user changes and do not widen the root app shell again as a quick fix.

## Artifacts and Notes

Important pre-fix evidence:

    git show --stat --summary --format=fuller a7d85dd956081c34d27804fe93b57aee4221fd1f

shows the March 21 regression commit touching:

    src/hyperopen/views/app_view.cljs
    src/hyperopen/vaults/effects.cljs
    src/hyperopen/vaults/infrastructure/preview_cache.cljs
    src/hyperopen/views/vaults/preview_shell.cljs
    src/hyperopen/startup/init.cljs

and

    rg -n "hyperopen\\.(views\\.vaults\\.preview_shell|vaults\\.infrastructure\\.preview_cache|vaults\\.application\\.list_vm)\\.js" /Users/barry/Downloads/Trace-20260325T183405.json

returns trade-trace matches for:

    http://localhost:8081/js/cljs-runtime/hyperopen.vaults.application.list_vm.js
    http://localhost:8081/js/cljs-runtime/hyperopen.vaults.infrastructure.preview_cache.js
    http://localhost:8081/js/cljs-runtime/hyperopen.views.vaults.preview_shell.js

Important post-fix validation captured in this session:

    npm run test:playwright:smoke -- --grep "main route smoke"

passed all 10 smoke cases, and

    npm test
    npm run test:websocket
    npm run check

all completed successfully on the patched tree.

Important second-round evidence captured in this session:

    rg -n "hyperopen\\.(views\\.vaults\\.preview_shell|vaults\\.infrastructure\\.preview_cache|vaults\\.application\\.list_vm|views\\.vaults\\.list_view|views\\.vaults\\.detail_view)\\.js" /Users/barry/Downloads/Trace-20260325T191051.json

returns only:

    http://localhost:8083/js/cljs-runtime/hyperopen.vaults.infrastructure.preview_cache.js

while

    rg -n "hyperopen\\.(views\\.vaults\\.preview_shell|vaults\\.infrastructure\\.preview_cache|vaults\\.application\\.list_vm|views\\.vaults\\.list_view|views\\.vaults\\.detail_view)\\.js" /Users/barry/.codex/worktrees/d5eb/hyperopen/tmp/trade-startup-trace-2026-03-25-postfix.json

returns no matches.

## Interfaces and Dependencies

No public API changes are planned. The important interfaces after this fix are:

- `hyperopen.views.app-view/app-view` in `/hyperopen/src/hyperopen/views/app_view.cljs`, which must remain the generic top-level route switch rather than a vault-specific preview owner.
- `hyperopen.vaults.effects/api-fetch-vault-index!` in `/hyperopen/src/hyperopen/vaults/effects.cljs`, which may still serve multiple routes but must only persist startup preview data when the active route is the vault list route.
- `hyperopen.vaults.infrastructure.preview-cache/build-vault-startup-preview-record` in `/hyperopen/src/hyperopen/vaults/infrastructure/preview_cache.cljs`, which should produce a bounded storage record without depending on the full vault list presentation namespace.
- `hyperopen.views.vaults.startup-preview/restore-startup-preview!` and `hyperopen.views.vaults.startup-preview/persist-startup-preview-record!` in `/hyperopen/src/hyperopen/views/vaults/startup_preview.cljs`, which now own the vault-only startup preview behavior inside the deferred `vaults_route` module.
- `hyperopen.route-modules/route-ready?` and `hyperopen.route-modules/render-route-view` in `/hyperopen/src/hyperopen/route_modules.cljs`, which continue to own deferred route readiness and rendering once the route module is available.
- `hyperopen.route-modules/load-route-module!` in `/hyperopen/src/hyperopen/route_modules.cljs`, which now also restores startup preview only after the vault module resolves for the exact `/vaults` list route.
- `hyperopen.runtime.effect-adapters.vaults/persist-vault-startup-preview-record!` in `/hyperopen/src/hyperopen/runtime/effect_adapters/vaults.cljs`, which now resolves the vault-only persist hook dynamically so `main` no longer imports the preview-cache namespace.

Plan revision note: 2026-03-25 22:42Z - Created the active ExecPlan for `hyperopen-ta4x`, linked the user-reported regression to the March 25 trade trace, and anchored the first fix on the root app shell import leak from the March 21 vault preview commit.
Plan revision note: 2026-03-25 23:36Z - Expanded the plan to include the full regression chain through the March 18 route-loading change, recorded the preview-persistence leak and startup dependency leak, and updated the implementation record to reflect the root-shell fix, route-gated persistence, lightweight preview-cache builder, new tests, and passing validations.
Plan revision note: 2026-03-26 00:27Z - Updated the plan after the user's follow-up trace showed a remaining `preview_cache` import on a stale `localhost:8083` runtime, recorded the second-round fix that moved startup preview restore and persist behind `vaults_route`, and captured the clean post-fix trade trace from `http://127.0.0.1:4201/trade`.
