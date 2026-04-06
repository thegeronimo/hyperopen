# Streamline `/vaults` Route Entry Without Double Navigation

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Linked live work: `hyperopen-jz8s` ("Streamline /vaults route entry and eliminate double navigation").

## Purpose / Big Picture

After this work, a desktop user who clicks `Vaults` from the main header should see one route-shaped `/vaults` loading surface and then the final vault list. They should not see a centered generic "Loading Route" shell, should not trigger a full browser page reload, and should not restart the vault fetch pipeline halfway through the transition.

The concrete success case is easy to observe. Start on `/trade`, click the desktop `Vaults` header entry, and watch the transition. The URL should change to `/vaults` without a document reload, the first visible loading state should already look like the vault list route, and the final route should settle on `vaults-root` after a single `vaults_route.js` load and a single live vault index request.

## Progress

- [x] (2026-04-06 14:07Z) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/FRONTEND.md`, and `/hyperopen/docs/BROWSER_TESTING.md` before writing this plan.
- [x] (2026-04-06 14:07Z) Analyzed `/Users/barry/Downloads/Trace-20260406T095438.json`, extracted screenshots, and correlated the user-visible transition with current vault-route code paths.
- [x] (2026-04-06 14:07Z) Confirmed that the current trace is a desktop header-entry problem, not only a slow vault API problem: the browser begins an in-app route transition and then unloads the page before the first route-module and vault-index requests can finish.
- [x] (2026-04-06 14:07Z) Created and claimed `hyperopen-jz8s` so this active plan references live tracked work.
- [x] (2026-04-06 14:07Z) Authored this active ExecPlan with trace-backed root-cause evidence and a concrete implementation order.
- [x] (2026-04-06 14:29Z) Replaced desktop header and desktop "More" anchors with explicit in-app controls that retain link role and `href` metadata while removing the native browser navigation path.
- [x] (2026-04-06 14:29Z) Added a main-bundle `/vaults` route shell and wired unresolved `/vaults` happy-path rendering away from the generic centered deferred-route loader.
- [x] (2026-04-06 14:36Z) Added deterministic `/trade` -> `/vaults` browser regression coverage and reran the required targeted/browser/repo validation gates.

## Surprises & Discoveries

- Observation: the trace shows two different navigation paths firing from one desktop click.
  Evidence: `EventDispatch click` occurs at `527750731721`, `RenderFrameHostImpl::DidCommitSameDocumentNavigation` follows at `527750733824`, then `beforeunload` fires at `527750736843`, and the browser later issues a real `Document` request for `http://localhost:8081/vaults` at `527751286178`.

- Observation: the first deferred route-module request and the first vault-index request are abandoned by the unload instead of completing.
  Evidence: `ResourceSendRequest http://localhost:8081/js/vaults_route.js` starts at `527750734604` and `ResourceSendRequest https://stats-data.hyperliquid.xyz/Mainnet/vaults` starts at `527750746947`, but both only record `ResourceFinish` near `527751283xxx` with no preceding `ResourceReceiveResponse`.

- Observation: after the hard reload begins, the browser repeats the same work and extends the time spent in loading states.
  Evidence: the trace later records a second `vaults_route.js` request at `527752446096`, a second live vault request at `527752581197`, and the final populated `/vaults` screenshot only at `527755100500`.

- Observation: the current desktop header route entries are structurally capable of causing native browser navigation because they render anchors with `href` and a separate SPA click action, but there is no explicit default-prevention path in the header view layer.
  Evidence: `/hyperopen/src/hyperopen/views/header/navigation.cljs` renders `nav-link` and `more-menu-link` as `:a` with both `:href` and `:on {:click action}`, while `/hyperopen/src/hyperopen/views/header/vm.cljs` supplies `[:actions/navigate ...]`.

- Observation: even if the hard reload is removed, `/vaults` still has two distinct loading surfaces today.
  Evidence: `/hyperopen/src/hyperopen/views/app_view.cljs` renders the centered generic `deferred-route-loading-shell` until `route-modules/route-ready?` returns true, then `/hyperopen/src/hyperopen/views/vaults/list_view.cljs` renders the vault-specific skeleton when the route view finally mounts with `loading? true`.

- Observation: the current codebase explicitly tests and preserves the generic loader for unresolved `/vaults`, so the extra loading surface is current product behavior rather than an accidental one-off.
  Evidence: `/hyperopen/test/hyperopen/views/app_view_test.cljs` currently contains `app-view-keeps-generic-route-loader-for-unresolved-vault-route-test`.

- Observation: preserving real anchor tags and trying to cancel ordinary clicks at the view layer still leaked a native `/vaults` navigation request in Playwright.
  Evidence: the first implementation rendered `:a` with `:on-click` cancellation logic, but `tools/playwright/test/routes.smoke.spec.mjs` still observed one navigation request to `/vaults` before the route-module gate released. Converting the desktop entries to explicit controls with link semantics removed that request and made the targeted smoke pass.

## Decision Log

- Decision: treat the native document reload as the primary bug to fix first.
  Rationale: it duplicates route bootstrap, abandons in-flight work, and invalidates any attempt to simplify the visible loading sequence.
  Date/Author: 2026-04-06 / Codex

- Decision: scope the navigation fix first to the desktop header row and desktop "More" menu, not the mobile header menu.
  Rationale: the April 6 trace is a desktop click, and `/hyperopen/src/hyperopen/views/header/navigation.cljs` already uses buttons for the mobile menu path.
  Date/Author: 2026-04-06 / Codex

- Decision: keep the generic deferred route shell for route failures and for other deferred routes, but stop using it for the normal unresolved `/vaults` happy path.
  Rationale: the user complaint is about the `/vaults` transition, and current tests already rely on the generic shell for error and non-vault cases.
  Date/Author: 2026-04-06 / Codex

- Decision: unify the unresolved `/vaults` surface by extracting or sharing route-shaped vault loading markup rather than inventing a third loader.
  Rationale: the end-user request is "one thing" before the final page. Reusing the existing vault preview and skeleton geometry is the lowest-risk way to keep the loading handoff visually stable.
  Date/Author: 2026-04-06 / Codex

- Decision: ship the desktop navigation fix as explicit `button` controls with `role="link"` and retained `href` metadata instead of real anchors.
  Rationale: the attempted anchor-plus-cancel approach still emitted a native `/vaults` navigation request in the browser. Explicit controls removed the reload path deterministically while preserving route labels, keyboard/focus behavior, and existing test affordances that assert `href` values.
  Date/Author: 2026-04-06 / Codex

## Outcomes & Retrospective

Implementation and validation are complete. The shipping behavior now removes the native desktop reload path and replaces the unresolved `/vaults` generic loader with a route-shaped vault shell. On the validated `/trade` -> `/vaults` desktop click path, the targeted Playwright regression now sees one `vaults_route.js` request, one live `Mainnet/vaults` request, no `Loading Route` shell, and no native `/vaults` navigation request before the final `vaults-root` render.

Validation that passed during implementation:

- `npx shadow-cljs --force-spawn compile test`
- `npx playwright test tools/playwright/test/routes.smoke.spec.mjs --grep "Vaults|vaults"`
- `npm test`
- `npm run check`
- `npm run test:websocket`
- `npm run qa:design-ui -- --targets vaults-route --manage-local-app`

The governed browser QA run `design-review-2026-04-06T14-37-44-701Z-876ecfb2` finished `PASS` across `375`, `768`, `1280`, and `1440` viewports for the `vaults-route` target. Residual blind spots are the standard browser-QA note that hover, active, disabled, and loading states still require targeted actions when they are not present by default.

## Context and Orientation

This plan concerns the desktop path from `/trade` into the `/vaults` list route.

In this repository, a deferred route is a route whose view code is not loaded in the initial main bundle. `/hyperopen/src/hyperopen/route_modules.cljs` maps `/vaults` to the browser chunk named `vaults_route`. `/hyperopen/src/hyperopen/views/app_view.cljs` checks whether that route module is ready; if not, it currently renders a centered generic loading shell with the text `Loading Route`.

The desktop header navigation is built in three layers. `/hyperopen/src/hyperopen/views/header/nav.cljs` defines the header items, including `:vaults`. `/hyperopen/src/hyperopen/views/header/vm.cljs` gives each desktop item both an `href` and an SPA action such as `[:actions/navigate "/vaults"]`. The shipping fix in `/hyperopen/src/hyperopen/views/header/navigation.cljs` now renders the desktop items as explicit controls with link role and retained `href` metadata so the SPA route action can run without the browser leaving the page.

The SPA navigation action itself is not the bug. `/hyperopen/src/hyperopen/runtime/action_adapters/navigation.cljs` correctly updates `[:router :path]`, emits route-specific projection effects, pushes browser history, and starts the deferred route-module load. The trace proves that this in-app path starts working. The bug is that the browser then leaves the page anyway, which aborts the first in-app route transition and starts a second cold boot.

Once the `/vaults` route view finally mounts, the actual list loading state comes from `/hyperopen/src/hyperopen/vaults/application/list_vm.cljs` and `/hyperopen/src/hyperopen/views/vaults/list_view.cljs`. Those files already know how to show a route-shaped loading experience: either preview-backed stale content with `Refreshing vaults…` or a cold-start skeleton with loading rows. The current problem is that this route-specific loader only becomes available after the unresolved generic route shell has already been shown.

The relevant tests already exist near the affected surfaces. `/hyperopen/test/hyperopen/views/app_view_test.cljs` covers the unresolved route shell behavior. `/hyperopen/test/hyperopen/views/vaults/list_view_test.cljs` covers cold skeleton and preview-backed refreshing states. `/hyperopen/tools/playwright/test/routes.smoke.spec.mjs` already exercises the desktop header and `/vaults` route presence, and `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` already contains vault preview and route transition coverage that can be extended.

## Plan of Work

Milestone 1 fixes the accidental hard reload. Update the desktop header navigation path so a normal in-app click does not leave the page. The safest repository-local route is to stop using anchor elements for the desktop header happy path and render explicit button triggers that dispatch `[:actions/navigate ...]`, matching the button-based mobile header menu that already exists today. Implement this in `/hyperopen/src/hyperopen/views/header/navigation.cljs`, with the shape of the data still coming from `/hyperopen/src/hyperopen/views/header/vm.cljs`. Keep the visual styling unchanged. If implementation later proves that preserving browser new-tab affordances on these entries is a hard requirement, open a follow-up issue instead of widening this bug fix into runtime-level event-default plumbing.

Milestone 2 removes the generic loading shell from normal `/vaults` entry. Add a small main-bundle vault loading shell that can be rendered from `/hyperopen/src/hyperopen/views/app_view.cljs` before `vaults_route.js` resolves. This shell must be route-shaped, not centered generic copy. It should reuse the existing `/vaults` visual structure: title area, TVL card shape, toolbar frame, and the current loading-row geometry. When `[:vaults :startup-preview]` is available, the unresolved shell should show that preview baseline and the existing `Refreshing vaults…` treatment. When there is no preview baseline, the unresolved shell should render the same cold skeleton that the full list route already uses. The implementer should extract shared list-shell pieces out of `/hyperopen/src/hyperopen/views/vaults/list_view.cljs` into a main-bundle-safe helper so the unresolved shell and the resolved list route render the same loading geometry.

Milestone 3 makes the handoff visually invariant. After `vaults_route.js` resolves, the full route view must not replace the unresolved vault shell with a different loading surface. The current list view model already distinguishes between `loading?` and `refreshing?` using live rows and startup preview state. Update `/hyperopen/src/hyperopen/vaults/application/list_vm.cljs` and `/hyperopen/src/hyperopen/views/vaults/list_view.cljs` only as needed so the unresolved shell and the resolved list route share the same visual state contract. A cold start with no preview should remain a single skeleton surface from first paint until live rows appear. A warm start with preview data should remain a single preview-backed refreshing surface until live rows replace it.

Milestone 4 locks the regression with deterministic coverage. Update `/hyperopen/test/hyperopen/views/app_view_test.cljs` so unresolved `/vaults` happy-path rendering expects the vault-shaped shell instead of the generic centered loader, while route-error coverage keeps expecting the retry-capable generic shell. Add or update view tests around `/hyperopen/src/hyperopen/views/header/navigation.cljs` so desktop vault entries no longer render as plain anchors. Extend Playwright in `/hyperopen/tools/playwright/test/routes.smoke.spec.mjs` or `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` so a desktop click on the header `Vaults` entry proves the browser does not issue a fresh `Document` request to `/vaults`, that `vaults_route.js` is fetched only once, that the live vault index request is not duplicated, and that the final route settles on `vaults-root`.

Milestone 5 revalidates the user experience with browser evidence. Capture a fresh Chrome trace or equivalent request log for the desktop `/trade` -> `/vaults` click path after the code change. The new evidence should show one in-app route transition, no `beforeunload`, no `Document` request for `/vaults`, one vault route chunk request, one live vault list request, and a single route-shaped loading surface before the final route render.

## Concrete Steps

From `/Users/barry/.codex/worktrees/f6ee/hyperopen`:

1. Implement Milestone 1 in `/hyperopen/src/hyperopen/views/header/navigation.cljs` and any small helper file needed by the desktop header view-model path.
2. Extract the shared vault unresolved shell into a main-bundle-safe helper under `/hyperopen/src/hyperopen/views/vaults/**`, then update `/hyperopen/src/hyperopen/views/app_view.cljs` and `/hyperopen/src/hyperopen/views/vaults/list_view.cljs` to consume it.
3. Update the affected ClojureScript tests:
   - `/hyperopen/test/hyperopen/views/app_view_test.cljs`
   - any new or existing header view tests under `/hyperopen/test/hyperopen/views/header/**`
   - `/hyperopen/test/hyperopen/views/vaults/list_view_test.cljs` if shared-shell extraction changes current expectations
4. Update or add Playwright coverage in:
   - `/hyperopen/tools/playwright/test/routes.smoke.spec.mjs`
   - or `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`
5. Run the smallest relevant browser test first:
   - `npx playwright test tools/playwright/test/routes.smoke.spec.mjs --grep "Vaults|vaults"`
6. If that passes, run the required repo gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
7. Run governed browser QA for the changed route:
   - `npm run qa:design-ui -- --targets vaults-route --manage-local-app`
8. Capture a new desktop click trace for `/trade` -> `/vaults` and compare it to `/Users/barry/Downloads/Trace-20260406T095438.json`.

## Validation and Acceptance

Acceptance starts with navigation correctness. On desktop, clicking the header `Vaults` entry from `/trade` must keep the page alive. A fresh performance trace or Playwright request log must not show `beforeunload` or a new `Document` request for `/vaults` after the click. The browser should perform one same-page route transition only.

Acceptance also requires a single visible loading treatment. The normal unresolved `/vaults` path must not show the centered generic `Loading Route` shell anymore. Instead, the first visible loading surface after the click must already look like the `/vaults` route. On a cold path with no preview baseline, that means one skeleton surface. On a warm path with preview data, that means one preview-backed refreshing surface. In both cases, the final list should appear without first regressing through a second, different loader.

Acceptance also requires no duplicate work. The `/trade` -> `/vaults` click path should issue exactly one `vaults_route.js` request and exactly one live vault list request in the happy path. The request duplication visible in the April 6 trace must disappear.

Acceptance also requires that the existing generic route shell behavior remains intact where it still belongs. If `vaults_route.js` fails to load or another deferred route is unresolved, `/hyperopen/src/hyperopen/views/app_view.cljs` should still show the existing generic retry-capable shell instead of a blank page.

The required validation commands are:

- `npx playwright test tools/playwright/test/routes.smoke.spec.mjs --grep "Vaults|vaults"`
- `npm run check`
- `npm test`
- `npm run test:websocket`
- `npm run qa:design-ui -- --targets vaults-route --manage-local-app`

## Idempotence and Recovery

This work is safe to repeat because it is confined to view composition, navigation wiring, and tests. If the new vault-shaped unresolved shell causes regressions, temporarily fall back to the existing generic loader in `/hyperopen/src/hyperopen/views/app_view.cljs` while keeping the header hard-reload fix in place; those two changes are intentionally separable.

If shared-shell extraction from `/hyperopen/src/hyperopen/views/vaults/list_view.cljs` becomes too invasive, stop after landing the desktop navigation fix and record the blocked shell-extraction details here before continuing. The hard-reload fix alone should still improve the user experience and remove the duplicate network/bootstrap churn.

If the revised Playwright test proves flaky because route-module timing varies, move the assertion down one level: assert the absence of a `/vaults` `Document` request and the single-count vault fetches rather than timing a specific animation frame.

## Artifacts and Notes

Primary trace analyzed:

    /Users/barry/Downloads/Trace-20260406T095438.json

Screenshots extracted during diagnosis:

    /Users/barry/.codex/worktrees/f6ee/hyperopen/tmp/trace_shots/storyboard.jpg

Key timeline points from the April 6, 2026 trace:

    527750731721  EventDispatch click
    527750733824  DidCommitSameDocumentNavigation
    527750734604  ResourceSendRequest http://localhost:8081/js/vaults_route.js
    527750746947  ResourceSendRequest https://stats-data.hyperliquid.xyz/Mainnet/vaults
    527750736843  beforeunload
    527751286178  ResourceSendRequest http://localhost:8081/vaults (Document)
    527752446096  second ResourceSendRequest http://localhost:8081/js/vaults_route.js
    527752581197  second ResourceSendRequest https://stats-data.hyperliquid.xyz/Mainnet/vaults
    527755100500  final screenshot with populated /vaults list

Relevant code surfaces:

    /hyperopen/src/hyperopen/views/header/navigation.cljs
    /hyperopen/src/hyperopen/views/header/vm.cljs
    /hyperopen/src/hyperopen/runtime/action_adapters/navigation.cljs
    /hyperopen/src/hyperopen/views/app_view.cljs
    /hyperopen/src/hyperopen/route_modules.cljs
    /hyperopen/src/hyperopen/vaults/application/list_vm.cljs
    /hyperopen/src/hyperopen/views/vaults/list_view.cljs

Relevant test surfaces:

    /hyperopen/test/hyperopen/views/app_view_test.cljs
    /hyperopen/test/hyperopen/views/vaults/list_view_test.cljs
    /hyperopen/tools/playwright/test/routes.smoke.spec.mjs
    /hyperopen/tools/playwright/test/trade-regressions.spec.mjs

Plan revision note: 2026-04-06 14:07Z - Created this active ExecPlan after diagnosing the April 6 desktop `/trade` -> `/vaults` trace, linking the bug to a double-navigation header path plus the existing generic unresolved-route shell.

## Interfaces and Dependencies

The finished change should keep the existing route action contract intact: desktop vault entry should still dispatch `:actions/navigate` and should still reach `/hyperopen/src/hyperopen/runtime/action_adapters/navigation.cljs` for the actual route transition. The bug fix should live in the view layer that currently causes the browser to leave the page.

The shared unresolved `/vaults` shell should live in code that is available before `vaults_route.js` resolves. That means the extracted loading-shell helper must not require the deferred route module itself. Keep the dependency direction one-way: `/hyperopen/src/hyperopen/views/app_view.cljs` may depend on the new main-bundle vault shell helper, while the full `/vaults` route view may also reuse that helper later.

Keep the existing startup-preview state contract under `[:vaults :startup-preview]`. The unresolved `/vaults` shell should read that state only to decide whether it can show a preview-backed refreshing surface. It must not introduce a second preview state path or a second loading vocabulary.
