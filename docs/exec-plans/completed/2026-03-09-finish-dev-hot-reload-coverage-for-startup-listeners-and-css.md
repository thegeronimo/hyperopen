# Finish Dev Hot-Reload Coverage For Startup Listeners And CSS

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The live `bd` issue for this work is `hyperopen-xw1` ("Finish dev hot-reload coverage for startup listeners and CSS"), and `bd` remains the lifecycle source of truth until this plan is moved out of `active`.

## Purpose / Big Picture

When a developer runs `npm run dev`, Shadow CLJS recompiles browser code and then calls `hyperopen.core/reload`. That already re-registers some runtime handlers, but several startup-installed listeners still survive across reloads in stale or duplicated form, which means certain edits still require a full page refresh before the app behaves correctly again. After this change, editing router, wallet-provider, or address-watcher code in local development should no longer require a hard refresh just to recover from duplicated or stale startup listeners.

The beat also covers CSS behavior during local development. Hyperopen currently serves Tailwind output as a static `/css/main.css` file, so CSS-only edits do not live-swap into the browser. This plan intentionally keeps that half narrow: either add a very small dev-only reload seam if one is already latent, or document the current manual-refresh limitation in the main developer tooling surface and stop there.

## Progress

- [x] (2026-04-03 10:01 EDT) Re-audited `hyperopen-xw1`, the current dev reload path, and the affected listener seams in `/hyperopen/src/hyperopen/router.cljs`, `/hyperopen/src/hyperopen/wallet/core.cljs`, `/hyperopen/src/hyperopen/wallet/address_watcher.cljs`, `/hyperopen/src/hyperopen/startup/runtime.cljs`, `/hyperopen/src/hyperopen/app/startup.cljs`, `/hyperopen/resources/public/index.html`, and the related tests.
- [x] (2026-04-03 10:01 EDT) Wrote the machine-readable spec artifact at `/hyperopen/tmp/multi-agent/hyperopen-xw1/spec.json`.
- [x] (2026-04-03 10:35 EDT) Recreated the active ExecPlan inside the current worktree so implementation progress can be tracked locally.
- [x] (2026-04-03 10:42 EDT) Installed local JS dependencies with `npm ci` so the repo test and tooling commands are available in this worktree.
- [x] (2026-04-03 11:06 EDT) Made router, wallet-provider, and startup-owned address-watcher listener ownership reload-safe without replaying heavy startup work.
- [x] (2026-04-03 11:08 EDT) Added deterministic regression coverage for repeated init and reload cycles in the router, wallet, startup-runtime, address-watcher, and app startup seams.
- [x] (2026-04-03 11:19 EDT) Resolved the CSS half of the beat by documenting the current `css:watch` manual-refresh limitation in `/hyperopen/docs/tools.md`, ran the required repo gates, and verified that `npm run dev` boots the local watch stack cleanly.

## Surprises & Discoveries

- Observation: the existing reload fix already established the preferred pattern for some global listeners.
  Evidence: `/hyperopen/src/hyperopen/startup/runtime.cljs` stores cleanup functions in `asset-selector-shortcuts-cleanup` and `position-tpsl-clickaway-cleanup`, removes any prior listener before re-registering, and `/hyperopen/src/hyperopen/app/startup.cljs` re-runs those installers from `reload-runtime-bindings!`.

- Observation: router reload ownership still lives outside that pattern.
  Evidence: `/hyperopen/src/hyperopen/router.cljs` `init!` adds `window.popstate` directly and never stores or removes the callback before a later init or reload cycle.

- Observation: wallet provider listener installation is still guarded only by a boolean.
  Evidence: `/hyperopen/src/hyperopen/wallet/core.cljs` keeps `listeners-installed?` as a `defonce` atom and calls provider `.on` for `accountsChanged` and `chainChanged`, but does not retain callback references or remove them on reload or provider replacement.

- Observation: address-watcher already exposes enough primitives to support reload-safe rebinding, but the startup path does not use them that way.
  Evidence: `/hyperopen/src/hyperopen/wallet/address_watcher.cljs` exposes `start-watching!`, `stop-watching!`, and `remove-handler!`, yet `/hyperopen/src/hyperopen/startup/runtime.cljs` `install-address-handlers!` always adds handlers and syncs current state without first clearing the startup-owned set.

- Observation: true CSS live reload is not latent in the current dev stack.
  Evidence: `/hyperopen/package.json` runs `css:watch` and `cljs:watch` concurrently, `/hyperopen/resources/public/index.html` hard-links `/css/main.css`, and `/hyperopen/shadow-cljs.edn` only wires `:after-load hyperopen.core/reload` for ClojureScript reloads, not standalone CSS changes.

- Observation: provider replacement without a listener-removal API needs stale-callback fencing even after the boolean guard is removed.
  Evidence: when the provider object changes and the old provider only supports `.on`, the old callbacks remain attached; using a listener identity token inside `/hyperopen/src/hyperopen/wallet/core.cljs` prevents those stale callbacks from mutating the latest store after reload.

- Observation: `remove-handler!` in the address watcher was mutating the handler collection shape as well as its contents.
  Evidence: `/hyperopen/src/hyperopen/wallet/address_watcher.cljs` previously used `remove`, which returns a seq; after a remove-and-readd reload cycle, `conj` would prepend instead of append and silently reorder handlers.

- Observation: the repo namespace-size guard needs explicit bookkeeping when targeted lifecycle fixes land inside already-dense startup and wallet test owners.
  Evidence: `npm run check` failed only on namespace-size thresholds for `/hyperopen/src/hyperopen/startup/runtime.cljs`, `/hyperopen/test/hyperopen/startup/runtime_test.cljs`, and `/hyperopen/test/hyperopen/wallet/core_test.cljs`, so `/hyperopen/dev/namespace_size_exceptions.edn` was updated with ticket-specific temporary exceptions instead of forcing unrelated refactors.

## Decision Log

- Decision: keep `hyperopen.core/start!`, `hyperopen.core/init`, and `hyperopen.core/reload` stable while fixing the remaining listener seams behind them.
  Rationale: the issue is about finishing hot-reload ownership, not redesigning app startup or entrypoint APIs.
  Date/Author: 2026-04-03 / Codex

- Decision: converge the remaining JavaScript listener seams toward the same explicit install-and-cleanup pattern already used by the reload-safe startup runtime listeners.
  Rationale: idempotent cleanup ownership is easier to reason about than stacking more boolean guards on top of old startup-only code.
  Date/Author: 2026-04-03 / Codex

- Decision: treat governed browser QA as unnecessary for this beat unless the implementation unexpectedly broadens into shipped UI behavior.
  Rationale: this work is development-loop correctness plus developer documentation, not a shipped route interaction or styling change.
  Date/Author: 2026-04-03 / Codex

- Decision: guard wallet provider callbacks with explicit listener identity instead of trying to emulate detach on providers that only expose `.on`.
  Rationale: the real bug is stale callbacks mutating the latest store after provider replacement; listener identity solves that without widening the public wallet API or assuming unsupported provider methods.
  Date/Author: 2026-04-03 / Codex

- Decision: close the CSS scope with one canonical tooling note in `/hyperopen/docs/tools.md` and stop there.
  Rationale: the current dev stack recompiles CSS to disk, but there is no latent browser stylesheet swap mechanism; documenting that limitation in the main developer-tool surface is accurate and lower risk than inventing a partial second reload path.
  Date/Author: 2026-04-03 / Codex

- Decision: satisfy the namespace-size policy with explicit temporary exceptions rather than splitting startup/runtime or wallet test namespaces during this beat.
  Rationale: `hyperopen-xw1` is a targeted listener-ownership fix; a structural namespace split would add unrelated review surface and dilute the regression work that actually closes the issue.
  Date/Author: 2026-04-03 / Codex

## Outcomes & Retrospective

Implemented. `reload-runtime-bindings!` now rebinds router, wallet, and startup-owned address handlers through explicit lifecycle seams instead of relying on one-time startup side effects. The router `popstate` listener is reinstalled idempotently, wallet listener state tracks the active provider plus a listener identity token so stale providers cannot mutate the latest store after reload, and startup-owned address handlers are replaced without replaying current-address bootstrap work. The address watcher also preserves vector handler ordering across remove-and-readd cycles, which keeps reload behavior deterministic.

The regression suite now covers repeated router init, repeated wallet listener attach, provider swap without detach support, address-watcher handler ordering, runtime reload replacement of startup-owned handlers, and app-level reload behavior that skips current-address bootstrap until a new event arrives. `/hyperopen/docs/tools.md` documents the actual CSS behavior: local watch commands rebuild `/css/main.css`, but CSS-only edits still require a manual browser refresh because Shadow's `:after-load` hook applies only to ClojureScript.

Validation completed with `npm run lint:delimiters -- --changed`, focused `compile test` plus targeted node tests, focused `compile ws-test` plus `node out/ws-test.js`, `npm run check`, `npm test`, `npm run test:websocket`, and a local `npm run dev` startup sanity pass. Remaining caveat: I verified that the local watch stack boots cleanly, but I did not run an interactive browser edit/reload session that manually proves route back/forward or wallet behavior live in a tab.

## Context and Orientation

In this repository, "hot reload" means the local development path where Shadow CLJS recompiles browser code and then runs `hyperopen.core/reload` without a full page refresh. That entrypoint lives in `/hyperopen/src/hyperopen/core.cljs`, which delegates to `/hyperopen/src/hyperopen/app/bootstrap.cljs` `reload!`. The current `reload!` function already re-runs runtime bootstrap, rebinds some startup handlers through `/hyperopen/src/hyperopen/app/startup.cljs` `reload-runtime-bindings!`, resets the wallet connected handler, and re-renders the app.

The problem is that not every startup-installed listener is owned by that reload-safe path. `/hyperopen/src/hyperopen/startup/runtime.cljs` already does the right thing for two window listeners: asset-selector shortcuts and the position TP/SL click-away listener. Both store cleanup callbacks in `defonce` atoms and remove prior listeners before adding new ones. By contrast, `/hyperopen/src/hyperopen/router.cljs` `init!` adds `window.popstate` directly, `/hyperopen/src/hyperopen/wallet/core.cljs` installs provider listeners only once through `listeners-installed?`, and `/hyperopen/src/hyperopen/startup/runtime.cljs` `install-address-handlers!` installs startup-owned address handlers and the store watch without a dedicated reload path.

The CSS half is simpler. Tailwind input lives in `/hyperopen/src/styles/main.css`, the built stylesheet is written to `/hyperopen/resources/public/css/main.css`, and the browser loads it through `/hyperopen/resources/public/index.html`. The local dev scripts in `/hyperopen/package.json` run `tailwindcss --watch` beside `shadow-cljs watch`, but there is no live browser stylesheet swap mechanism. CSS-only edits are therefore compiled to disk, yet the browser continues using the current stylesheet until the page is manually refreshed.

The main implementation surfaces are:

- `/hyperopen/src/hyperopen/router.cljs`
- `/hyperopen/src/hyperopen/wallet/core.cljs`
- `/hyperopen/src/hyperopen/wallet/address_watcher.cljs`
- `/hyperopen/src/hyperopen/startup/runtime.cljs`
- `/hyperopen/src/hyperopen/app/startup.cljs`
- `/hyperopen/docs/tools.md`
- `/hyperopen/test/hyperopen/router_test.cljs`
- `/hyperopen/test/hyperopen/wallet/core_test.cljs`
- `/hyperopen/test/hyperopen/wallet/address_watcher_test.cljs`
- `/hyperopen/test/hyperopen/startup/runtime_test.cljs`
- `/hyperopen/test/hyperopen/app/startup_test.cljs`

## Plan of Work

First, restore the missing local toolchain by running `npm ci` so the repo tests and schema/tooling modules are available in this worktree. Do not change dependencies; just install the versions already locked in `package-lock.json`.

Then fix the remaining JavaScript listener seams in the smallest order that reduces reload risk fastest. In `/hyperopen/src/hyperopen/router.cljs`, make repeated `init!` calls idempotent by giving the `popstate` listener explicit cleanup ownership. The startup path should still set the current route exactly once, but reload should be able to reinstall the browser listener without duplicating old callbacks or replaying route bootstrap work.

In `/hyperopen/src/hyperopen/wallet/core.cljs`, replace the current boolean-only provider listener guard with actual callback ownership. The code should retain the active provider plus the installed callbacks so reload can detach the old listeners before binding new ones. Keep the existing wallet public API stable, and guard listener removal so providers without `removeListener` do not break the flow.

In `/hyperopen/src/hyperopen/startup/runtime.cljs`, make the startup-owned address-watcher installation explicitly replaceable. The startup path should keep its current full bootstrap behavior, including current-address sync, but reload should be able to stop watching, remove the startup-owned handlers by name, reinstall the current watch/listener functions, and avoid replaying the full account bootstrap on every reload. Wire that lightweight rebind path into `/hyperopen/src/hyperopen/app/startup.cljs` `reload-runtime-bindings!`.

After the source seams are fixed, add deterministic regression coverage. The router tests should prove repeated `init!` replaces the old `popstate` callback and uses the latest route-change closure. The wallet tests should prove repeated `attach-listeners!` or provider replacement does not duplicate callbacks and continues to route `accountsChanged` and `chainChanged` through the latest handlers. The startup runtime tests should prove repeated address-handler installation or reload rebinding does not accumulate duplicate handlers or store watches, and the startup/app orchestration tests should prove the reload path invokes the new listener rebind seams.

Finally, resolve the CSS half of the beat with the smallest acceptable outcome. Unless implementation evidence uncovers an already-present low-risk live-reload hook, update `/hyperopen/docs/tools.md` near the local development command surface to state that `tailwindcss --watch` rewrites `/hyperopen/resources/public/css/main.css`, but CSS-only edits still require a manual browser refresh under the current dev loop.

## Concrete Steps

Run all commands from `/Users/barry/.codex/worktrees/3c63/hyperopen`.

1. Install dependencies for this worktree:

    npm ci

2. Reconfirm the current listener owners before editing:

    sed -n '184,208p' src/hyperopen/router.cljs
    sed -n '130,190p' src/hyperopen/wallet/core.cljs
    sed -n '339,399p' src/hyperopen/startup/runtime.cljs
    sed -n '1,25p' resources/public/index.html

3. Implement the listener-ownership fixes in the touched source files and the CSS note in `/hyperopen/docs/tools.md`.

4. Run the focused test passes while iterating:

    npm run test:runner:generate
    npx shadow-cljs --force-spawn compile test
    node out/test.js --test=hyperopen.router-test,hyperopen.wallet.core-test,hyperopen.startup.runtime-test,hyperopen.app.startup-test
    npx shadow-cljs --force-spawn compile ws-test
    node out/ws-test.js

5. Run the required repository gates:

    npm run check
    npm test
    npm run test:websocket

6. Do one manual local dev-loop spot-check:

    npm run dev

   Verify that a CLJS edit on one of the listener owners reloads without needing a hard refresh for route back/forward or wallet listener behavior, and verify that a CSS-only edit still requires a manual browser refresh unless the implementation intentionally added a very small live-reload seam and recorded that choice here.

## Validation and Acceptance

The work is complete only when all of the following are true:

1. Repeated init or reload cycles do not duplicate the router `popstate` listener.
2. Repeated init or reload cycles do not leave stale or duplicated wallet provider listeners behind the current store.
3. Repeated init or reload cycles do not accumulate duplicate startup-owned address-watcher handlers or store watches.
4. The reload path refreshes listener ownership without replaying heavy startup work such as remote bootstrap fetches or service-worker registration.
5. Focused tests in the router, wallet, startup runtime, and app startup seams prove the lifecycle ownership deterministically.
6. `npm run check`, `npm test`, and `npm run test:websocket` all pass.
7. The CSS outcome is explicit in `/hyperopen/docs/tools.md`: either styles now live-reload in dev, or the manual-refresh limitation is clearly documented.

## Idempotence and Recovery

This work is safe to execute incrementally. The listener-ownership changes are source-only and test-backed, and the documentation update is additive. Re-running the focused tests, `npm ci`, or the repo gates is safe. If a listener refactor causes reload regressions, revert to the last stable explicit cleanup seam rather than stacking more boolean guards on top of the broken path.

The CSS half must stay narrow. Do not partially add a second dev server, a polling script, or a stylesheet swapper unless the implementation finds an already-supported low-risk path and records that decision here first. If the documentation note proves inaccurate after manual verification, fix the wording rather than widening runtime behavior by accident.

## Artifacts and Notes

- `bd` issue: `hyperopen-xw1`
- Spec artifact: `/hyperopen/tmp/multi-agent/hyperopen-xw1/spec.json`

## Interfaces and Dependencies

The implementation must preserve these public or semi-public interfaces:

- `hyperopen.core/start!`, `hyperopen.core/init`, and `hyperopen.core/reload`
- `hyperopen.router/init!`
- `hyperopen.wallet.core/init-wallet!`
- `hyperopen.wallet.address-watcher/start-watching!`, `stop-watching!`, `add-handler!`, and `remove-handler!`
- the existing Shadow dev hook in `/hyperopen/shadow-cljs.edn`:

    :devtools {:after-load hyperopen.core/reload}

The implementation may add private cleanup state or helper functions, but it should not change the release asset model or the developer command surface in `/hyperopen/package.json`. If wallet-provider listener removal depends on EIP-1193 `removeListener`, guard that call and keep behavior safe when a provider does not expose it.

Plan revision note: 2026-04-03 10:35 EDT - Recreated the active ExecPlan in the current worktree before implementation so progress and validation evidence remain attached to the same checkout as the code changes.
