# Retire Oversized Account-Info Surface Exceptions (`hyperopen-qcuq`)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Active `bd` issue: `hyperopen-qcuq`.

## Purpose / Big Picture

The reusable Account Info surface is one of the highest-churn user paths in Hyperopen. It appears inside the lower panel on `/trade`, in portfolio/trader account views, and in other shells that depend on the same tab strip and tab content renderers. Today that surface still relies on thirteen oversized namespace exceptions across `src/hyperopen/views/account_info/**`, `src/hyperopen/views/account_info_view.cljs`, and the paired tests under `test/hyperopen/views/account_info/**`. The code still works, but the exception debt concentrates unrelated rendering, sorting, mobile overlay, and test-helper responsibilities into a few large owners, which makes reviews weaker and raises regression risk.

After this work, the Account Info UI must behave exactly the same for users, but the biggest owners must be split into smaller namespaces that no longer need temporary size exceptions. The first proof path is behavioral: on `/trade` and `/portfolio/trader/:address`, switching among `Balances`, `Positions`, `Open Orders`, `TWAP`, `Trade History`, `Funding History`, and `Order History` must still render the same controls, mobile cards, and overlays at desktop and mobile widths. The second proof path is structural: the targeted account-info entries disappear from `/hyperopen/dev/namespace_size_exceptions.edn`, and the required validation commands pass without adding replacement exceptions.

## Progress

- [x] (2026-04-14 00:14Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/BROWSER_TESTING.md`, and `/hyperopen/docs/agent-guides/browser-qa.md`.
- [x] (2026-04-14 00:14Z) Inventoried the account-info exception family from `/hyperopen/dev/namespace_size_exceptions.edn` and confirmed the current oversized owners: `src/hyperopen/views/account_info_view.cljs` (866), `src/hyperopen/views/account_info/tabs/positions.cljs` (832), `test/hyperopen/views/account_info/tabs/positions_test.cljs` (971), plus the remaining balances/open-orders/trade-history/projections/modal entries.
- [x] (2026-04-14 00:14Z) Confirmed the first high-value slice is `Positions` by inspecting `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` and `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`.
- [x] (2026-04-14 00:14Z) Created and claimed `bd` issue `hyperopen-qcuq` for this refactor wave.
- [x] (2026-04-14 00:14Z) Authored this active ExecPlan.
- [x] (2026-04-14 00:50Z) Milestone 1: split `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` into the new internal owners `/hyperopen/src/hyperopen/views/account_info/tabs/positions/shared.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/positions/desktop.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/positions/mobile.cljs`, and `/hyperopen/src/hyperopen/views/account_info/tabs/positions/layout.cljs`; the public facade is now `140` lines and its size exception was removed.
- [x] (2026-04-14 00:50Z) Milestone 2: replaced `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs` with focused suites under `/hyperopen/test/hyperopen/views/account_info/tabs/positions/`, moved those suites to direct `positions` imports, deleted the oversized legacy test file, and removed its exception entry.
- [x] (2026-04-14 00:50Z) Milestone 3: extracted shared tab metadata and tab-strip/header-action ownership into `/hyperopen/src/hyperopen/views/account_info/tab_registry.cljs` and `/hyperopen/src/hyperopen/views/account_info/tab_actions.cljs`, shrank `/hyperopen/src/hyperopen/views/account_info_view.cljs` to `356` lines, and removed the root view exception. Compatibility forwards that are still consumed by non-positions tests remain intentionally in place for now.
- [x] (2026-04-14 01:35Z) Milestone 4a: split `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` into focused `shared`, `desktop`, and `mobile` owners, moved the positions-oriented projection helpers into `/hyperopen/src/hyperopen/views/account_info/projections/positions.cljs`, replaced `/hyperopen/test/hyperopen/views/account_info/tabs/balances_test.cljs` with focused suites under `/hyperopen/test/hyperopen/views/account_info/tabs/balances/`, and removed the resolved `balances`, `projections/balances`, and `balances_test` exceptions.
- [x] (2026-04-14 01:35Z) Milestone 4b: split `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` by extracting sort/filter/cache ownership into `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders/sorting.cljs`, moved order-history normalization into `/hyperopen/src/hyperopen/views/account_info/projections/order_history.cljs`, replaced the oversized open-orders test with focused content/sorting/projection suites under `/hyperopen/test/hyperopen/views/account_info/tabs/open_orders/`, and removed the resolved `open_orders`, `projections/orders`, and `open_orders_test` exceptions.
- [ ] Milestone 4 remaining: retire the remaining oversized account-info exceptions in priority order: `tabs/trade_history`, `position_tpsl_modal`, then the remaining oversized tests (`order_history_test`, `trade_history_test`).
- [ ] Milestone 5: run the required repo gates plus governed browser validation, remove stale exception entries, and update the retrospective with the final complexity outcome.
- [x] (2026-04-14 00:50Z) Validation for the completed first slice passed: `npm run test:playwright:smoke`, `npm run qa:design-ui -- --targets trade-route --manage-local-app`, `npm run browser:cleanup`, `npm run check`, `npm test`, `npm run test:websocket`, and `npm run lint:namespace-sizes`. `npm ci` was required first because this worktree did not yet have `node_modules/`.
- [x] (2026-04-14 01:35Z) Validation for the balances + open-orders slice passed: `npm run lint:namespace-sizes`, `npm test`, `npm run test:websocket`, `npm run check`, `npm run test:playwright:smoke`, `npm run qa:design-ui -- --targets trade-route --manage-local-app`, and `npm run browser:cleanup`.

## Surprises & Discoveries

- Observation: `/hyperopen/src/hyperopen/views/account_info_view.cljs` is not only the route-facing shell. Its lower half is also a compatibility export hub that forwards many tab helpers and test reset hooks from the individual tab namespaces.
  Evidence: lines `581` through `668` of `/hyperopen/src/hyperopen/views/account_info_view.cljs` are mostly `def` forwards such as `positions-tab-content`, `position-row`, `open-orders-tab-content`, `trade-history-tab-content`, and `reset-*-cache!`.

- Observation: the current `Positions` owner already has natural split seams. Desktop row rendering, mobile card rendering, overlay-anchor logic, and high-level content orchestration are mostly grouped into contiguous blocks rather than interleaved line by line.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` groups desktop row rendering around `position-row-from-vm`, mobile card rendering around `mobile-position-card-from-vm`, overlay outlet logic around `mobile-position-overlay-outlet`, and final orchestration in `positions-tab-content-from-rows`.

- Observation: the oversized tab tests are coupled to the root shell through the compatibility exports in `account_info_view.cljs`. Shrinking the root shell requires moving those tests to direct imports of the tab namespaces rather than leaving the root view as a permanent test API hub.
  Evidence: `rg` across `test/hyperopen/views/account_info/**` shows many tab suites calling `view/positions-tab-content`, `view/position-row`, `view/open-orders-tab-content`, `view/trade-history-tab-content`, and other helpers via `hyperopen.views.account-info-view`.

- Observation: only the `Positions` source and test are both oversized and already cleanly separable from the rest of the shell, which makes them the safest first slice before touching the shared root owner.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` is `832` lines, `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs` is `971` lines, and both expose internally coherent clusters that can move behind a stable facade.

- Observation: one source-shape contract test for typography was implicitly using `/hyperopen/src/hyperopen/views/account_info_view.cljs` as the owner of tab-strip font measurement and the balances toggle label, so the extraction needed a matching test-owner update even though the runtime behavior was unchanged.
  Evidence: `/hyperopen/test/hyperopen/views/typography_scale_test.cljs` initially failed after the split until its source assertions were updated to read `/hyperopen/src/hyperopen/views/account_info/tab_actions.cljs`.

- Observation: the worktree initially had no `node_modules/`, which caused misleading `lucide` import failures for `compile app` and `npm test` until the locked dependencies were installed.
  Evidence: initial validation failed on missing modules like `lucide/dist/esm/icons/star.js` and `lucide/dist/esm/icons/external-link.js`; `npm ci` restored the expected package tree and the same gates then passed.

- Observation: the cleanest way to retire the open-orders exceptions was not a large render rewrite. The high-value seam was the sort/filter/search cache block, while the order-history normalization code was inflating `projections/orders.cljs` even though it is logically a different owner.
  Evidence: extracting `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders/sorting.cljs` dropped `tabs/open_orders.cljs` to `403` lines, and moving order-history normalization into `/hyperopen/src/hyperopen/views/account_info/projections/order_history.cljs` dropped `projections/orders.cljs` to `255` lines without changing the `projections` facade API.

- Observation: the balances mobile split initially regressed expansion-state identity because row ids were no longer trimmed before comparing against persisted `:mobile-expanded-card` state.
  Evidence: the reviewer found the mismatch between `/hyperopen/src/hyperopen/views/account_info/tabs/balances/mobile.cljs` and `/hyperopen/src/hyperopen/account/history/surface_actions.cljs`; restoring `str/trim` and adding a direct mobile-card test fixed the issue before the full gate stack.

## Decision Log

- Decision: start with `Positions`, then tighten `account_info_view`, then retire the remaining tab and projection exceptions.
  Rationale: `Positions` is the highest-value slice because it combines a large source exception, a large paired test exception, and direct user-path importance. Finishing that split first creates the stable direct-import pattern the root shell cleanup depends on.
  Date/Author: 2026-04-14 / Codex

- Decision: preserve current public entry points and action ids while using internal helper namespaces as the main shrinking mechanism.
  Rationale: this repository’s contract says to preserve public APIs unless explicitly requested otherwise. A thin facade keeps `hyperopen.views.account-info-view/account-info-panel`, `hyperopen.views.account-info-view/account-info-view`, and the current tab namespace entry points stable while moving mixed responsibilities behind them.
  Date/Author: 2026-04-14 / Codex

- Decision: remove root-view compatibility re-exports only after the relevant tests have been moved to direct imports of the real owner namespaces.
  Rationale: deleting those exports first would turn the refactor into a broad breaking-change sweep. Moving tests first keeps the transition additive and makes the root shell shrink safely measurable.
  Date/Author: 2026-04-14 / Codex

- Decision: treat exception retirement as complete only when the exact account-info paths are removed from `/hyperopen/dev/namespace_size_exceptions.edn`, not when the code merely “looks smaller”.
  Rationale: the registry is the enforced source of truth. Leaving stale entries behind would keep the debt hidden even if the code was already small enough.
  Date/Author: 2026-04-14 / Codex

- Decision: retire the root `account_info_view` exception after extracting `tab_registry.cljs` and `tab_actions.cljs`, but keep `tab-renderers` and the still-live compatibility forwards in the root file until the remaining oversized tab suites migrate.
  Rationale: the exception was retired once the route shell fell well below the limit, and forcing the remaining compat-forward cleanup into this first slice would have widened the blast radius into unrelated oversized tab families.
  Date/Author: 2026-04-14 / Codex

## Outcomes & Retrospective

The first execution slice landed successfully. The `Positions` owner is now a thin facade over focused internal desktop/mobile/shared/layout namespaces, the oversized `positions` test owner was replaced by smaller direct-import suites, and the route-facing `account_info_view` shell dropped under the repository threshold after the tab-registry and tab-action extraction. The resolved exception entries were removed from `/hyperopen/dev/namespace_size_exceptions.edn`.

Actual complexity outcome so far: reduced. The mixed shell and positions responsibilities that previously lived in three oversized owners now sit in nine smaller namespaces, all below the `500` line limit, while preserving the public entry points and action ids.

Main remaining risk: the root compatibility forwards are still needed by `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs` and `/hyperopen/test/hyperopen/views/account_info/tabs/balances_test.cljs`, so the next cleanup wave must migrate those tests before deleting the forwards. The remaining oversized account-info family (`balances`, `open_orders`, `trade_history`, projections, and modal/test follow-ons) is still active and should be handled as Milestone 4 rather than folded silently into this completed slice.

## Context and Orientation

In this plan, a “size exception” means one entry in `/hyperopen/dev/namespace_size_exceptions.edn` that temporarily allows a namespace to stay above the repository’s `500` line limit. A “facade” means a thin public namespace that keeps the old function names and require path stable while delegating implementation to smaller internal namespaces.

The Account Info surface is the lower account-history panel used across trade and portfolio-style routes. The route-facing shell lives in `/hyperopen/src/hyperopen/views/account_info_view.cljs`. It renders the tab strip, header actions, panel sizing, loading/error handling, and dispatch to each tab renderer. The main oversized tab owners live under `/hyperopen/src/hyperopen/views/account_info/tabs/**`, and the data-preparation owners live under `/hyperopen/src/hyperopen/views/account_info/projections/**`.

Remaining oversized account-info entries from `/hyperopen/dev/namespace_size_exceptions.edn` after the first slice are:

- `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` at `646` lines.
- `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` at `531` lines.
- `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs` at `643` lines.
- `/hyperopen/src/hyperopen/views/account_info/projections/balances.cljs` at `534` lines.
- `/hyperopen/src/hyperopen/views/account_info/projections/orders.cljs` at `581` lines.
- `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs` at `618` lines.
- `/hyperopen/test/hyperopen/views/account_info/tabs/balances_test.cljs` at `742` lines.
- `/hyperopen/test/hyperopen/views/account_info/tabs/open_orders_test.cljs` at `663` lines.
- `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs` at `729` lines.
- `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs` at `772` lines.

The first slice centers on these files:

- `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`: currently mixes filter normalization, desktop row rendering, mobile card rendering, overlay-trigger geometry, table header rendering, and content orchestration.
- `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`: currently mixes content filtering/sorting, desktop row presentation, mobile-card rendering, overlay behavior, and read-only behavior.
- `/hyperopen/src/hyperopen/views/account_info_view.cljs`: currently mixes real shell behavior with a large bank of test-facing `def` forwards to tab/helper namespaces.
- `/hyperopen/test/hyperopen/views/account_info_view_test.cljs`: composition-level tests for shared shell behavior. This file is not oversized and should stay focused on whole-panel behavior, not absorb tab-unit coverage.

The key invariant for every milestone is simple: end users must not see a changed Account Info surface. The work is purely about ownership boundaries, smaller namespaces, and stronger regression coverage routing.

## Plan of Work

### Milestone 1: Split the `Positions` source owner without changing its public API

Create a small internal namespace group under `/hyperopen/src/hyperopen/views/account_info/tabs/positions/` and keep `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` as the stable public facade. Use these concrete internal files:

- `/hyperopen/src/hyperopen/views/account_info/tabs/positions/shared.cljs` for cross-layout helpers such as direction-filter normalization, numeric formatting helpers, icon helpers, and layout constants that are currently shared by desktop and mobile rendering.
- `/hyperopen/src/hyperopen/views/account_info/tabs/positions/desktop.cljs` for desktop-only row/header behavior, specifically `position-row-from-vm`, `position-row`, `sortable-header`, and `position-table-header`.
- `/hyperopen/src/hyperopen/views/account_info/tabs/positions/mobile.cljs` for mobile card rendering, overlay-trigger geometry, and `mobile-position-overlay-outlet`.

After these moves, `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` should keep only the stable public entry points, their delegation glue, and the high-level `positions-tab-content` orchestration. Do not rename the existing action ids such as `:actions/open-position-margin-modal`, `:actions/open-position-tpsl-modal`, or `:actions/open-position-reduce-popover`. Do not move the modal or popover owners themselves. If `positions.cljs` still remains slightly above `500` lines after the first split, continue moving orchestration-adjacent helpers into the new internal owners before touching the exception registry.

### Milestone 2: Split the `Positions` regression suite and move tab tests off the root shell compatibility exports

Replace `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs` with focused suites under `/hyperopen/test/hyperopen/views/account_info/tabs/positions/`. Use these concrete test files:

- `/hyperopen/test/hyperopen/views/account_info/tabs/positions/content_test.cljs` for sort/filter/empty-state/content orchestration assertions.
- `/hyperopen/test/hyperopen/views/account_info/tabs/positions/row_test.cljs` for desktop row/header/cell rendering and formatting assertions.
- `/hyperopen/test/hyperopen/views/account_info/tabs/positions/mobile_test.cljs` for mobile cards, overlay outlet behavior, and read-only mobile affordance assertions.

Each new suite should require `hyperopen.views.account-info.tabs.positions` directly, not `hyperopen.views.account-info-view`. Keep `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` as the home for whole-panel shell assertions such as default height, extra-tab panel sizing, and route-level read-only behavior. Once the new focused suites exist and pass, delete the old oversized `positions_test.cljs` file and remove its exception entry.

### Milestone 3: Shrink the route-facing `account_info_view` shell into a true shell

Split `/hyperopen/src/hyperopen/views/account_info_view.cljs` into smaller internal shell owners while preserving the route-facing entry points. Use these concrete internal files:

- `/hyperopen/src/hyperopen/views/account_info/tab_registry.cljs` for base tab definitions, extra-tab normalization, label overrides, tab ordering, and tab-label helpers.
- `/hyperopen/src/hyperopen/views/account_info/tab_actions.cljs` for tab-strip measurement helpers, shared search/filter control pieces, and the per-tab header-action renderers.
- `/hyperopen/src/hyperopen/views/account_info/tab_renderers.cljs` for the `tab-renderers` map, extra-tab renderer adaptation, and `tab-content`.

Then reduce `/hyperopen/src/hyperopen/views/account_info_view.cljs` to the stable shell concerns: `format-pnl-percentage`, `account-info-panel`, `account-info-view`, and only the minimum helper functions that are still genuinely part of the shell contract. During this milestone, update every affected tab test under `/hyperopen/test/hyperopen/views/account_info/tabs/**` to import its real owner namespace or the new internal helper namespace instead of relying on `account_info_view.cljs` as a compatibility export hub. Only after `rg` confirms those old `view/...` forwards are no longer needed should they be deleted from the root view.

### Milestone 4: Retire the rest of the account-info exception family in descending value order

Once the positions pattern and root-shell cleanup are stable, repeat the same extraction shape for the remaining oversized account-info files.

Do this in this exact order:

First, tighten `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` together with `/hyperopen/src/hyperopen/views/account_info/projections/balances.cljs` and `/hyperopen/test/hyperopen/views/account_info/tabs/balances_test.cljs`. The balances tab and its projection owner should separate row building and filtering from view rendering, while the test suite should stop using the root-view compatibility exports.

Second, tighten `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` together with `/hyperopen/src/hyperopen/views/account_info/projections/orders.cljs` and `/hyperopen/test/hyperopen/views/account_info/tabs/open_orders_test.cljs`. Preserve the existing sort-cache and action behavior while moving normalization/filtering logic into smaller owners.

Third, tighten `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs` and `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs` using the same “facade plus focused render/content helpers” pattern.

Fourth, split `/hyperopen/src/hyperopen/views/account_info/position_tpsl_modal.cljs` along view-layout versus option/formatting responsibilities, then retire its exception entry.

Fifth, split `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs` if it is still oversized after the direct-import cleanup. The source owner for order history is already under the line limit, so this is a test-boundary cleanup, not a route-shell change.

After each slice, immediately remove any resolved path from `/hyperopen/dev/namespace_size_exceptions.edn`. Never wait for the end of the full wave to clean up resolved entries.

### Milestone 5: Final validation, browser QA, and exception retirement audit

The final milestone is not complete until both code and registry state prove the refactor worked. Run the full repo gates, run the smallest relevant Playwright suite first, then perform governed browser QA for the route geometry that depends on the Account Info panel. Close the milestone only when the account-info entries targeted by this plan are gone from `/hyperopen/dev/namespace_size_exceptions.edn`, the shell behavior still matches current expectations, and no browser-inspection session remains open.

## Concrete Steps

From `/hyperopen`, use these commands as the implementation and validation spine.

Start each slice by confirming the current line counts and live exception entries:

    wc -l src/hyperopen/views/account_info_view.cljs \
      src/hyperopen/views/account_info/tabs/positions.cljs \
      test/hyperopen/views/account_info/tabs/positions_test.cljs

    rg -n "account_info_view|views/account_info" dev/namespace_size_exceptions.edn

Implement Milestones 1 and 2, then rerun the source/test inventory so the line-count reduction is visible before editing the registry:

    wc -l src/hyperopen/views/account_info/tabs/positions.cljs \
      src/hyperopen/views/account_info/tabs/positions/*.cljs \
      test/hyperopen/views/account_info/tabs/positions/*.cljs

After the `Positions` source and tests are below the threshold, update `/hyperopen/dev/namespace_size_exceptions.edn` to remove the resolved `positions` entries. Then implement Milestone 3 and repeat the same inventory check for the root shell:

    wc -l src/hyperopen/views/account_info_view.cljs \
      src/hyperopen/views/account_info/tab_registry.cljs \
      src/hyperopen/views/account_info/tab_actions.cljs \
      src/hyperopen/views/account_info/tab_renderers.cljs

Use `rg` before deleting compatibility exports from the root view:

    rg -n "hyperopen.views.account-info-view :as view" test/hyperopen/views/account_info test/hyperopen/views/account_info_view_test.cljs

If any tab suite still imports the root view only for tab-unit helpers, move that suite first. Keep `account_info_view_test.cljs` focused on whole-panel behavior.

After each remaining milestone, rerun the line-count check for the affected files and remove only the exact registry entries that are now resolved.

When the code changes are stable, run validation in this order:

    npm run test:playwright:smoke
    npm run qa:design-ui -- --targets trade-route --manage-local-app
    npm run browser:cleanup
    npm run check
    npm test
    npm run test:websocket

Expected outcome: the Playwright smoke and design review complete without Account Info regressions, the browser cleanup command stops any inspection sessions, and the three required repo gates pass.

## Validation and Acceptance

This plan is accepted only when all of the following are true.

The structural acceptance criteria are:

- `/hyperopen/dev/namespace_size_exceptions.edn` no longer contains the account-info paths resolved by this plan.
- `/hyperopen/src/hyperopen/views/account_info_view.cljs` is a real shell owner rather than a large test-compatibility export hub.
- `/hyperopen/test/hyperopen/views/account_info/tabs/*.cljs` no longer depend on root-view compatibility exports for tab-unit coverage.

The behavioral acceptance criteria are:

- On `/trade`, the Account Info panel keeps the same desktop geometry when switching among `Balances`, `Positions`, `Open Orders`, `TWAP`, `Trade History`, `Funding History`, and `Order History`.
- On the `Positions` tab, desktop sorting, filtering, `Close`, `Margin`, and `TP/SL` affordances still behave the same in normal and read-only modes.
- On mobile widths, the `Positions` cards still expand inline, hoist only one active overlay at a time, and keep the same bottom-sheet style for margin, reduce, and TP/SL overlays.
- Whole-panel composition behavior still passes the existing assertions in `/hyperopen/test/hyperopen/views/account_info_view_test.cljs`.

The command acceptance criteria are:

- `npm run test:playwright:smoke` passes before broader browser QA.
- `npm run qa:design-ui -- --targets trade-route --manage-local-app` accounts for the required UI passes for the trade-route geometry contract.
- `npm run check`, `npm test`, and `npm run test:websocket` all pass.

If a slice lands but one of these conditions is still false, the work is not complete.

## Idempotence and Recovery

Every split in this plan must be additive first and subtractive second. Create the new internal namespace, route the old facade through it, move the tests to the direct owner, and only then delete the old in-file implementation or the old compatibility export. This makes the work safe to re-run and keeps failures local.

Do not edit `/hyperopen/dev/namespace_size_exceptions.edn` speculatively. Remove an entry only after `wc -l` proves the owner is below the limit. If a new internal namespace accidentally exceeds `500` lines, split it again by behavior before removing the old exception.

If browser QA leaves any Browser MCP or browser-inspection session behind, run `npm run browser:cleanup` before continuing. If a source split proves too risky mid-slice, keep the stable facade, leave the existing exception entry in place, and narrow the extraction instead of forcing a larger rewrite.

## Artifacts and Notes

Initial line-count snapshot for the first slice:

    865 src/hyperopen/views/account_info_view.cljs
    832 src/hyperopen/views/account_info/tabs/positions.cljs
    971 test/hyperopen/views/account_info/tabs/positions_test.cljs
    237 test/hyperopen/views/account_info_view_test.cljs

Initial account-info exception inventory snapshot:

    866 src/hyperopen/views/account_info_view.cljs
    832 src/hyperopen/views/account_info/tabs/positions.cljs
    646 src/hyperopen/views/account_info/tabs/balances.cljs
    643 src/hyperopen/views/account_info/tabs/trade_history.cljs
    618 src/hyperopen/views/account_info/position_tpsl_modal.cljs
    581 src/hyperopen/views/account_info/projections/orders.cljs
    534 src/hyperopen/views/account_info/projections/balances.cljs
    531 src/hyperopen/views/account_info/tabs/open_orders.cljs
    971 test/hyperopen/views/account_info/tabs/positions_test.cljs
    772 test/hyperopen/views/account_info/tabs/trade_history_test.cljs
    742 test/hyperopen/views/account_info/tabs/balances_test.cljs
    729 test/hyperopen/views/account_info/tabs/order_history_test.cljs
    663 test/hyperopen/views/account_info/tabs/open_orders_test.cljs

Post-split line-count snapshot after Milestones 1-3:

    356 src/hyperopen/views/account_info_view.cljs
    118 src/hyperopen/views/account_info/tab_registry.cljs
    437 src/hyperopen/views/account_info/tab_actions.cljs
    140 src/hyperopen/views/account_info/tabs/positions.cljs
    234 src/hyperopen/views/account_info/tabs/positions/desktop.cljs
     79 src/hyperopen/views/account_info/tabs/positions/layout.cljs
    251 src/hyperopen/views/account_info/tabs/positions/mobile.cljs
    183 src/hyperopen/views/account_info/tabs/positions/shared.cljs
    209 test/hyperopen/views/account_info/tabs/positions/content_test.cljs
    213 test/hyperopen/views/account_info/tabs/positions/desktop_actions_test.cljs
    308 test/hyperopen/views/account_info/tabs/positions/desktop_render_test.cljs
    276 test/hyperopen/views/account_info/tabs/positions/mobile_test.cljs
     43 test/hyperopen/views/account_info/tabs/positions/test_support.cljs

Registry entries retired in this slice:

    src/hyperopen/views/account_info_view.cljs
    src/hyperopen/views/account_info/tabs/positions.cljs
    test/hyperopen/views/account_info/tabs/positions_test.cljs

These updated counts and the registry diff are the completion evidence for the first execution slice. Milestone 4 remains open for the rest of the oversized account-info family.

## Interfaces and Dependencies

Do not add new external libraries. This is a namespace-boundary cleanup only.

The stable public interfaces that must still exist after the refactor are:

- `hyperopen.views.account-info-view/account-info-panel`
- `hyperopen.views.account-info-view/account-info-view`
- the current public entry points in `hyperopen.views.account-info.tabs.positions`
- the existing action ids already dispatched by the Account Info UI

The new internal namespace boundaries introduced by this plan must stay under the `hyperopen.views.account-info.*` tree so ownership remains obvious. Prefer these exact stable names:

- `hyperopen.views.account-info.tab-registry`
- `hyperopen.views.account-info.tab-actions`
- `hyperopen.views.account-info.tab-renderers`
- `hyperopen.views.account-info.tabs.positions.shared`
- `hyperopen.views.account-info.tabs.positions.desktop`
- `hyperopen.views.account-info.tabs.positions.mobile`

If one of these internal owners still grows too large, split by behavior again, not by arbitrary line counts. For example, a mobile overlay-specific owner is acceptable; a vague `misc` owner is not.

Plan revision note: 2026-04-14 00:14Z - Initial plan created for `hyperopen-qcuq` after inventorying the account-info exception family, confirming the `Positions` source/test slice as the first milestone, and identifying the root shell’s compatibility re-export debt.
