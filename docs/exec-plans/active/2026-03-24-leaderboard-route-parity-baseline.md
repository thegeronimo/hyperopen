# Implement The Leaderboard Route And Parity Baseline

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-nz5v`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

After this change, a user who clicks `Leaderboard` in Hyperopen should land on a dedicated `/leaderboard` route instead of being dropped back into the trade screen. The page should feel like the Hyperliquid reference: a dense ranking surface with search, time-window selection, desktop table rendering, mobile card rendering, current-user pinning, and explicit loading, empty, and error states.

The initial delivery is intentionally read-only. It does not need to ship every Hyperliquid mutation affordance to be useful. The user-visible win is route activation and a stable parity baseline that can be tested and iterated.

## Progress

- [x] (2026-03-24 13:31Z) Created and claimed `bd` issue `hyperopen-nz5v` for the leaderboard route work.
- [x] (2026-03-24 13:33Z) Confirmed the current repo state: `Leaderboard` exists in header navigation, but `/leaderboard` has no route module and falls back to the trade screen.
- [x] (2026-03-24 13:34Z) Captured the live Hyperliquid leaderboard reference behavior and production data contract from the live route bundle and `stats-data.hyperliquid.xyz`.
- [x] (2026-03-24 18:12Z) Implemented the deferred `/leaderboard` route module, direct leaderboard API fetch path, exclusion-aware data derivation, and dedicated desktop/mobile leaderboard view baseline.
- [x] (2026-03-24 18:31Z) Added focused regression coverage for leaderboard actions, view-model ranking/pagination, deferred route wiring, startup dispatch, app-shell rendering, and route smoke coverage.
- [x] (2026-03-24 18:47Z) Ran the smallest relevant Playwright smoke successfully for `/leaderboard`, then completed `lint:docs`, `test:websocket`, targeted leaderboard namespaces, and `check`.
- [x] (2026-03-24 18:50Z) Recorded the remaining repo-wide `npm test` failure as unrelated existing asset-selector coverage and recorded governed browser-design-review QA as `BLOCKED` because leaderboard is not yet present in the permanent browser-inspection routing config.

## Surprises & Discoveries

- Observation: Hyperliquid’s leaderboard route is structurally simpler than a typical dashboard and does not need hero cards or extra summary panels.
  Evidence: the live route bundle shows only a title, search, time-range selector, ranking surface, and methodology note.

- Observation: the live leaderboard feed is large enough to matter for client-side behavior.
  Evidence: `https://stats-data.hyperliquid.xyz/Mainnet/leaderboard` returned `32,967` rows on 2026-03-24.

- Observation: the live route excludes vault and treasury-like addresses before display.
  Evidence: the route bundle fetches both `leaderboard` and `vaults`, then removes vault addresses and additional system-like addresses before building visible rows.

- Observation: the repository test runner does not support Jest-style file filtering flags.
  Evidence: `node out/test.js --runTestsByPath ...` was treated as unknown args; targeted runs must use `--test=<namespace,...>`.

- Observation: governed browser design review is not yet routable for leaderboard without temporary local wiring.
  Evidence: `/hyperopen/tools/browser-inspection/config/design-review-routing.json` has no leaderboard target, and the browser QA agent had to create a temporary routing file under `/hyperopen/tmp/browser-inspection/`.

## Decision Log

- Decision: implement Phase 1 as a dedicated read-only route with current-user pinning, but defer display-name mutation parity.
  Rationale: route activation and ranking usefulness are the highest-value gaps, while display-name mutation requires additional account-signing behavior that is not necessary to make the route real.
  Date/Author: 2026-03-24 / Codex

- Decision: keep the page intentionally compact and avoid inventing extra portfolio-style summary modules.
  Rationale: the Hyperliquid reference is a dense leaderboard surface, and matching that information architecture reduces product/design drift.
  Date/Author: 2026-03-24 / Codex

## Outcomes & Retrospective

The implementation succeeded: `/leaderboard` is now a real deferred route with a dedicated leaderboard page, live leaderboard feed fetch, vault/system-address exclusion, desktop/mobile rendering, search, timeframe switching, sort, pagination, and current-user pinning.

Validation is strong on the changed surface. Focused leaderboard namespaces pass, the leaderboard Playwright smoke passes on desktop and mobile, `npm run test:websocket` passes, `npm run lint:docs` passes, and `npm run check` passes. The remaining repo-wide `npm test` failure is a pre-existing unrelated assertion in `test/hyperopen/views/asset_selector_view_test.cljs` about `bg-base-200/70`.

The only leaderboard-specific incomplete area is governed browser design review. That work is honestly `BLOCKED`, not failed or skipped, because the permanent browser-inspection routing config still lacks a leaderboard target. Temporary artifacts were captured under `/hyperopen/tmp/browser-inspection/`, but no durable `PASS`/`FAIL` design-review report exists yet.

## Context and Orientation

Route-level lazy loading in this repository is owned by `/hyperopen/src/hyperopen/route_modules.cljs` and `/hyperopen/src/hyperopen/views/app_view.cljs`. A route module is a separately loaded browser bundle declared in `shadow-cljs.edn` and exported via a `route_view` symbol. Existing examples include `/portfolio`, `/staking`, `/api-wallets`, and `/vaults`.

The current `Leaderboard` nav item is already declared in `/hyperopen/src/hyperopen/views/header/nav.cljs`, and existing header tests already assert that clicking it navigates to `/leaderboard`. The missing implementation is the route itself: no module id, no compiled module entry, no exported route view, no leaderboard-specific state derivation, and no tests asserting dedicated content.

The product contract for this work is documented in `/hyperopen/docs/product-specs/leaderboard-page-parity-prd.md`. That PRD captures the Hyperliquid reference route, the production route chunk, and the production leaderboard/vaults feeds that define the row shape and time windows.

## Plan of Work

First, add the route-level plumbing so `/leaderboard` resolves as a deferred route module instead of falling through to the trade page. This requires updating `/hyperopen/src/hyperopen/route_modules.cljs`, `shadow-cljs.edn`, and a new route view namespace that exports `hyperopen.views.leaderboard_view.route_view`.

Next, implement a small leaderboard view-model layer that derives visible rows from route state and raw leaderboard data. The derivation needs to handle the selected time range, search filtering, rank recomputation, connected-user pinning, desktop sorting, pagination, and safe fallbacks. Keep the logic deterministic and view-facing rather than scattering ranking behavior across the app.

Then, build the UI itself under `/hyperopen/src/hyperopen/views/**` using existing repo patterns for page shells, table/list rendering, selectors, and explicit data-role anchors. Desktop should render a sortable paginated table. Mobile should render stacked cards with pagination controls. Both should expose clear loading, empty, and error states.

After that, add focused tests at the view-model and route/view levels so the route no longer silently regresses back into trade content. Add the smallest relevant browser regression if the path is stable enough.

Finally, run the required repo validation with the smallest relevant browser command first, broaden to the standard gates, and record any residual blockers honestly.

## Concrete Steps

Work from `/hyperopen`.

1. Update route-module registration in:

    `/hyperopen/src/hyperopen/route_modules.cljs`
    `/hyperopen/shadow-cljs.edn`

2. Add leaderboard state derivation and view namespaces under:

    `/hyperopen/src/hyperopen/views/leaderboard/**`
    `/hyperopen/src/hyperopen/views/leaderboard_view.cljs`

3. Add or update focused tests in:

    `/hyperopen/test/hyperopen/views/**`
    `/hyperopen/test/hyperopen/app/**` if route behavior needs coverage there

4. Run validation commands:

    cd /hyperopen
    npm run lint:docs
    npm test
    npm run test:websocket
    npm run check

5. For UI-specific validation, run the smallest relevant browser command first, then broaden:

    cd /hyperopen
    <smallest relevant Playwright command for the new route>
    npm run qa:design-ui -- --changed-files <leaderboard-related-paths>

## Validation and Acceptance

Acceptance is behavior, not just code edits.

On a running local Hyperopen app, navigating to `/leaderboard` must render a dedicated leaderboard page with a visible title, search input, time-range selector, ranking surface, and methodology note. Desktop must show a sortable paginated table. Mobile must show cards with pagination. If the connected address appears in the data, it must be pinned and marked `YOU`. Search, time-range changes, loading, empty, and error states must all behave deterministically.

The minimum automated acceptance is:

    cd /hyperopen
    npm test
    npm run test:websocket
    npm run check

The minimum browser acceptance is:

- a stable route-level browser assertion for `/leaderboard`
- a governed browser QA result that explicitly accounts for all required passes and widths, or an honest `BLOCKED` report if environment/tooling prevents completion

## Idempotence and Recovery

All planned changes are tracked-file edits and are safe to repeat. Route-module registration and leaderboard view-model derivations should remain additive. If the route bundle fails to load, the safe recovery path is to revert the new module registration and isolate the failure to the leaderboard namespace rather than destabilizing other deferred routes. If browser tooling is blocked by environment issues, preserve the route and test work and report the browser pass as blocked rather than faking a pass.

## Artifacts and Notes

Key external reference artifacts for this plan:

- `/hyperopen/docs/product-specs/leaderboard-page-parity-prd.md`
- `https://app.hyperliquid.xyz/leaderboard`
- `https://app.hyperliquid.xyz/static/js/4047.d6c5d5f1.chunk.js`
- `https://stats-data.hyperliquid.xyz/Mainnet/leaderboard`
- `https://stats-data.hyperliquid.xyz/Mainnet/vaults`

## Interfaces and Dependencies

No new third-party libraries should be required for this implementation. The key interfaces are:

- route-module registration in `/hyperopen/src/hyperopen/route_modules.cljs`
- route-view export in the new leaderboard view namespace
- deterministic leaderboard derivation helpers for filtering, sorting, ranking, pinning, and pagination

The implementation should preserve existing header navigation APIs and route-module behavior for other screens.

Revision note: created on 2026-03-24 when implementing `hyperopen-nz5v` so the leaderboard route work has an active self-contained plan tied to a live `bd` issue.
