# Trade Route Asset Deep Links

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and executes `bd` issue `hyperopen-5ss6`.

## Purpose / Big Picture

After this change, the trade page URL will reflect the currently selected asset, and loading that URL will restore the same asset. Users will be able to copy a trade URL and share a direct asset view, matching Hyperliquid-style behavior where trade routes include asset identity in the path.

The user-visible proof is straightforward: selecting a new asset on `/trade` updates the browser URL to an asset-specific subroute, and opening that subroute in a fresh tab lands on the same selected asset instead of whatever local storage previously held.

## Progress

- [x] (2026-03-12 19:16Z) Claimed `bd` issue `hyperopen-5ss6` and audited current route + asset-selection + startup-restore ownership boundaries.
- [x] (2026-03-12 19:16Z) Verified Hyperliquid route shape from live bundle (`/trade/:coin?/:spotQuote?`) and confirmed it canonicalizes route params into an active coin selection flow.
- [x] (2026-03-12 19:25Z) Implemented router trade helpers for route detection, asset parsing, and canonical trade-path building.
- [x] (2026-03-12 19:27Z) Implemented startup restore precedence so trade-route asset wins over stored active asset on load.
- [x] (2026-03-12 19:29Z) Implemented trade-route URL synchronization in `:actions/select-asset` (including spectate query preservation) while keeping projection-first ordering.
- [x] (2026-03-12 19:31Z) Added regression tests for router helpers, route-sync select-asset effects, and startup route precedence.
- [x] (2026-03-12 19:42Z) Ran required validation gates successfully: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-03-12 19:43Z) Updated final outcomes and prepared this ExecPlan to move to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: Hyperliquid’s current app bundle registers trade routes as `/trade/:coin?/:spotQuote?`, not only `/trade/:coin`.
  Evidence: Live bundle snippet from `https://app.hyperliquid.xyz/static/js/main.27729ec9.js` contains `path:"/trade/:coin?/:spotQuote?"`.

- Observation: Hyperliquid’s trade-route resolver normalizes multiple coin formats (including slash and hyphen variants) before selecting active coin and redirecting.
  Evidence: Resolver logic in the same bundle reads route params `{coin, spotQuote}`, combines them as `coin/spotQuote` when both exist, then normalizes via conditional branches including `includes("/")` and `includes("-")`.

- Observation: Hyperopen currently restores active asset from local storage before router initialization, so route subpaths cannot currently win on initial load.
  Evidence: `/hyperopen/src/hyperopen/startup/init.cljs` calls `restore-active-asset!` inside `restore-persisted-ui-state!` before `init-router!` is called in `initialize-systems!`.

- Observation: `npm run check` initially failed in this worktree because `node_modules` was absent (`@noble/secp256k1` resolution failure during Shadow CLJS compile).
  Evidence: First `npm run check` run failed with missing dependency trace; `npm install` resolved it and subsequent validation gates passed.

## Decision Log

- Decision: Keep Hyperopen’s canonical deep-link format as `/trade/<coin>` while applying minimal encoding for unsafe characters and preserving `:` and `/` so namespaced and spot-pair paths remain human-readable (`xyz:GOLD`, `MEOW/USDC`).
  Rationale: This aligns with observed Hyperliquid route ergonomics, preserves readable share links, and still allows malformed or percent-encoded inputs to decode safely during parsing.
  Date/Author: 2026-03-12 / Codex

- Decision: Update trade-route URL only when `:actions/select-asset` runs while already on a trade route.
  Rationale: The user request is scoped to trade page asset selection; this avoids surprising route changes when selecting assets from non-trade contexts (for example account tables on `/portfolio`).
  Date/Author: 2026-03-12 / Codex

## Outcomes & Retrospective

Trade asset deep links are now implemented end to end. On trade routes, selecting an asset updates both in-memory route state and browser history to an asset-specific path. Startup active-asset restore now checks the route first, so opening a copied trade URL hydrates the linked asset even when local storage previously held a different asset.

The implementation also preserves spectate-mode query behavior when push-state updates are emitted from `select-asset`, avoiding regressions in shareable spectate links.

Complexity impact is low and net-reducing for route/asset behavior. The new logic centralizes trade-route parsing/building in `router.cljs` and reuses those helpers in restore/action paths instead of ad hoc string handling in multiple places. Residual complexity is limited to small helper functions and targeted tests.

## Context and Orientation

Route normalization and browser path ownership live in `/hyperopen/src/hyperopen/router.cljs`. Action-level navigation policy lives in `/hyperopen/src/hyperopen/runtime/action_adapters.cljs`, while trade asset selection behavior lives in `/hyperopen/src/hyperopen/asset_selector/actions.cljs` (`select-asset`). Startup restoration of `:active-asset` currently lives in `/hyperopen/src/hyperopen/startup/restore.cljs` and is invoked through `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs`.

Today, selecting an asset updates in-memory state and websocket subscriptions but does not update `:router :path` or browser history. Also, deep links like `/trade/xyz:GOLD` do not drive startup active-asset selection because restore uses local storage only.

Relevant tests already exist in:

- `/hyperopen/test/hyperopen/router_test.cljs`
- `/hyperopen/test/hyperopen/asset_selector/actions_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/asset_selector_actions_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/asset_cache_persistence_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/websocket_test.cljs`

## Plan of Work

First, add router helpers that can build and parse trade asset routes deterministically. Building applies minimal encoding while preserving readable `:` and `/` route tokens. Parsing decodes trade-route asset text and gracefully handles malformed encodings by returning nil rather than throwing.

Second, wire startup restore to consult the current route path and prefer the parsed trade-route asset over local storage when present. This preserves deep-link intent on initial page load and keeps existing local storage fallback behavior when no route asset exists.

Third, extend `select-asset` to append route projection + push-state effects when the current route is a trade route and the target asset route differs from the current one. Keep projection effects ahead of heavy websocket unsubscribe/subscribe effects to preserve the interaction ordering constraints in `/hyperopen/docs/FRONTEND.md`.

Finally, update targeted tests for all new behavior and run full required gates.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/router.cljs` to add:
   - trade-route detection helper.
   - trade-route asset parse helper.
   - trade-route path builder helper with readable `:` and `/` preservation.
2. Edit `/hyperopen/src/hyperopen/startup/restore.cljs` so `restore-active-asset!` prefers route asset when available.
3. Edit `/hyperopen/src/hyperopen/asset_selector/actions.cljs` so `select-asset` emits route save/push effects for trade-route selection changes.
4. Update/add tests in:
   - `/hyperopen/test/hyperopen/router_test.cljs`
   - `/hyperopen/test/hyperopen/asset_selector/actions_test.cljs`
   - `/hyperopen/test/hyperopen/core_bootstrap/asset_selector_actions_test.cljs`
   - `/hyperopen/test/hyperopen/core_bootstrap/asset_cache_persistence_test.cljs`
5. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
6. Move this file to `/hyperopen/docs/exec-plans/completed/` and add final retrospective details.

## Validation and Acceptance

Acceptance requires all of the following:

1. On `/trade`, choosing a different asset updates browser URL to an asset-specific trade subroute.
2. Reopening that copied URL in a fresh session restores the same active asset, even if local storage has a different prior asset.
3. Existing non-trade routes are not forcibly navigated by `:actions/select-asset`.
4. Effect-order invariants remain valid (`projection` before heavy websocket I/O for `:actions/select-asset`).
5. Required validation gates pass.

## Idempotence and Recovery

All changes are source-level and additive. If route parsing introduces regressions, rollback can be scoped to helper functions in `router.cljs` and restore logic in `startup/restore.cljs` without data migrations. Because browser history entries are non-destructive, a bad route-sync effect can be corrected by reverting the action change and rerunning tests.

## Artifacts and Notes

Hyperliquid route evidence captured during planning:

- Live route registration string in bundle: `path:"/trade/:coin?/:spotQuote?"`.
- Trade resolver combines route params as `coin/spotQuote` when both are present and normalizes before selecting active coin.

This behavior informs Hyperopen compatibility goals but does not require reproducing every Hyperliquid normalization branch in this change.

## Interfaces and Dependencies

No external dependencies are required.

Expected internal interface additions/updates:

- Router helpers to build/parse trade asset paths in `/hyperopen/src/hyperopen/router.cljs`.
- `restore-active-asset!` in `/hyperopen/src/hyperopen/startup/restore.cljs` gains route-aware resolution behavior.
- `select-asset` in `/hyperopen/src/hyperopen/asset_selector/actions.cljs` gains optional trade-route URL sync effects while preserving existing side-effect ordering constraints.

Plan revision note: 2026-03-12 19:16Z - Initial ExecPlan created after auditing current Hyperopen flow and extracting current Hyperliquid trade-route behavior from live bundle.
Plan revision note: 2026-03-12 19:43Z - Marked implementation complete, recorded validation evidence, and updated outcomes/decisions to match the shipped route/restore/sync behavior.
