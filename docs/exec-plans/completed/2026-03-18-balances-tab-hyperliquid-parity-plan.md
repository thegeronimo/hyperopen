# Bring The Balances Tab Closer To Hyperliquid Parity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-46t7`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

After this change, the live balances tab on the Hyperopen trade route should look materially closer to the balances tab on Hyperliquid when both are shown with populated data. A user should be able to open the trade route, switch to `Balances`, and see the full visible row set without clipping, a desktop column structure that includes the missing `Repay` lane, tighter and more compact rows, cell formatting that is closer to the reference, and tab/header presentation that no longer drifts in obvious ways from the reference.

The user explicitly excluded action-semantics work from this pass. That means this plan does not attempt to change Hyperopen from `Send` to `Connect`, or otherwise re-model the behavior behind the row actions. This pass is strictly about visible structure, layout, formatting, and parity cues.

## Progress

- [x] (2026-03-18 18:31Z) Created and claimed `bd` issue `hyperopen-46t7` for this parity work.
- [x] (2026-03-18 18:31Z) Captured live visual evidence from Hyperliquid and the live Hyperopen site on port `8083` after a cache-bypassing reload and ghost-state injection.
- [x] (2026-03-18 18:31Z) Confirmed the Hyperopen desktop balances DOM contains six rows (`USDC (Spot)`, `MEOW`, `VAULT`, `STAR`, `MUNCH`, `WOW`) even though the visible component clips the body.
- [x] (2026-03-18 18:46Z) Implemented the balances-shell height increase, zero-count suppression for `Positions`, `Open Orders`, and `TWAP`, and the first pass of balances header parity controls.
- [x] (2026-03-18 18:49Z) Updated the balances desktop table to a nine-lane grid with an explicit `Repay` column, unit-bearing balance cells, a base-symbol coin label, and non-wrapping PNL output.
- [x] (2026-03-18 18:53Z) Added or updated regression tests for balances structure, header controls, tab-label suppression, and the taller balances panel shell.
- [x] (2026-03-18 18:58Z) Ran `npm test` and `npm run test:websocket` successfully after fixing a numeric-cell contract assertion.
- [x] (2026-03-18 18:59Z) Ran `npm run check` successfully.
- [x] (2026-03-18 19:20Z) Reworked the balances header based on follow-up visual feedback so `Coins...` stays visible inline, removed the now-unused balances filter dropdown state, and widened the `PNL (ROE %)` desktop lane by reclaiming width from `Coin` and `Total Balance`.
- [x] (2026-03-18 19:28Z) Re-ran `npm test`, `npm run test:websocket`, and `npm run check` successfully after the visible-search and PNL-width follow-up patch.
- [x] (2026-03-18 19:33Z) Tightened `Coin`, `Total Balance`, and `Repay` again, widened `PNL (ROE %)` further, and added an explicit `PNL -> Send` gutter via padding and alignment adjustments.
- [x] (2026-03-18 19:33Z) Re-ran `npm test`, `npm run test:websocket`, and `npm run check` successfully after the second balances-spacing pass.
<<<<<<< HEAD
- [x] (2026-03-20 12:11Z) Recorded that the final live-route browser rerun never happened before `hyperopen-46t7` was closed because the `8083` process was still serving a stale balances bundle; moving this plan to `completed` preserves that blocked validation as historical context rather than active work.

## Surprises & Discoveries

- Observation: the visible Hyperopen balances table clips after the top rows, but the DOM still contains all six desktop rows.
  Evidence: live browser inspection against `http://localhost:8083/index.html?tab=balances` reported `rowCount: 6` for `[data-role="account-tab-rows-viewport"]` while the screenshot only showed rows through `STAR`.

- Observation: the clipping is caused by a chain of fixed-height and overflow-constrained containers, not by missing row data.
  Evidence: `/hyperopen/src/hyperopen/views/account_info_view.cljs` wraps the account tables in `h-96` and multiple `overflow-hidden` containers; `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` places the desktop rows inside an `overflow-y-auto` viewport.

- Observation: the current Hyperopen balances desktop table is structurally eight columns wide, while the Hyperliquid reference is nine columns wide because Hyperopen omits `Repay`.
  Evidence: live Hyperliquid reference headers are `Coin`, `Total Balance`, `Available Balance`, `USDC Value`, `PNL (ROE %)`, `Send`, `Transfer`, `Repay`, `Contract`; Hyperopen renders the same set minus `Repay`.

- Observation: the first-pass balances header matched Hyperliquid more literally by hiding coin search inside a `Filter` dropdown, but that adds needless interaction cost on the wider Hyperopen desktop surface.
  Evidence: user follow-up on 2026-03-18 reported that the live balances header still had ample horizontal space and that search discoverability suffered when it was hidden.

- Observation: after the first PNL width rebalance, the remaining crowding was still concentrated at the `PNL -> Send` boundary rather than in the raw PNL track width.
  Evidence: the live screenshot review on 2026-03-18 still showed `PNL (ROE %)` reading too close to both `USDC Value` and `Send`, even with a wider PNL column.

- Observation: the live browser process on port `8083` is still executing the pre-change account-info bundle even after the current worktree passed compile and test gates.
  Evidence: a fresh browser-inspection session against `http://localhost:8083/trade?tab=balances` still reports `Balances (0)`, `Open Orders (0)`, `TWAP (0)`, no `Repay` header, and a panel class of `... h-96 ...`, while the served `resources/public/js/main.js` and local test/build outputs contained the newer balances-parity markers and `lg:h-[29rem]` at the time of inspection.

- Observation: the user manually inspected the balances tab after implementation and approved closing the work without another recorded agent-run browser sweep.
  Evidence: direct user instruction on 2026-03-20 to "just close out that feed" after manually looking into the remaining browser-signoff item.

## Decision Log

- Decision: keep this pass scoped to structural and visual parity only, and explicitly exclude action-semantics work.
  Rationale: the user requested all items except the earlier item `3`, which was the semantic re-modeling of row actions. Shipping the structural parity fixes separately reduces scope and avoids entangling layout work with business logic.
  Date/Author: 2026-03-18 / Codex

- Decision: treat the body clipping fix as the first implementation milestone.
  Rationale: hidden rows are the largest visible regression and also prevent accurate comparison of the remaining table structure.
  Date/Author: 2026-03-18 / Codex

- Decision: add the missing `Repay` lane for parity even if the cells initially render as inert placeholders or blanks.
  Rationale: the user asked to focus on the columns that appear in the balances tab. The missing lane changes the entire desktop table rhythm even before semantics are considered.
  Date/Author: 2026-03-18 / Codex

- Decision: keep browser signoff blocked rather than claiming live-route validation while port `8083` still serves the stale balances runtime.
  Rationale: the repository quality gates prove the code compiles and tests against the new table structure, but the user explicitly asked for the site itself and the current `8083` process is not reflecting the new bundle. Reporting that as blocked is more accurate than substituting a different route or workbench surface.
  Date/Author: 2026-03-18 / Codex

- Decision: restore the balances coin search as a permanently visible inline control and delete the balances-only filter dropdown state.
  Rationale: the balances header has enough room to show the search input without crowding the other controls, and the dropdown adds an unnecessary click path for the most common filter action.
  Date/Author: 2026-03-18 / Codex

- Decision: solve the residual balances crowding with a boundary-focused gutter, not just another broad width increase.
  Rationale: the user feedback and UI-agent review both pointed to the `USDC Value -> PNL -> Send` pinch point, so the effective fix was to reclaim width from low-value columns and combine a wider PNL track with extra right padding on `PNL` and left padding on `Send`.
  Date/Author: 2026-03-18 / Codex

<<<<<<< HEAD
- Decision: accept the user's manual live-route inspection as the final browser signoff for this issue and close the tracked work.
  Rationale: the remaining incomplete item was acceptance-only, the implementation and required repository gates were already complete, and the user explicitly confirmed they manually reviewed the live result and wanted the work closed out.
=======
- Decision: move this plan to `completed` once `hyperopen-46t7` was closed even though the final `8083` browser rerun remained blocked.
  Rationale: `/hyperopen/docs/PLANS.md` treats `completed` as the home for accepted or otherwise closed historical records. The only remaining gap was live-route confirmation against a stale local process, not additional implementation work in this repo.
>>>>>>> 90109262 (Fix trade layout shell regressions)
  Date/Author: 2026-03-20 / Codex

## Outcomes & Retrospective

<<<<<<< HEAD
The implementation work completed successfully. The balances header now keeps `Coins...` visible inline next to `Hide Small Balances`, the desktop shell can opt into a taller balances panel, zero counts are suppressed for the tabs that should stay unlabeled at zero, and the balances desktop grid now exposes the missing `Repay` lane while tightening formatting around coin labels, units, and PNL density. Two follow-up spacing passes reclaimed width from `Coin`, `Total Balance`, and the empty `Repay` lane, then used that room to enlarge `PNL (ROE %)` and create a more deliberate gutter between `PNL` and `Send`. Automated validation passed through `npm test`, `npm run test:websocket`, and `npm run check` again after the latest patch, and the user subsequently performed the final live-route manual review and approved closing the issue.
=======
The implementation work completed successfully. The balances header now keeps `Coins...` visible inline next to `Hide Small Balances`, the desktop shell can opt into a taller balances panel, zero counts are suppressed for the tabs that should stay unlabeled at zero, and the balances desktop grid now exposes the missing `Repay` lane while tightening formatting around coin labels, units, and PNL density. Two follow-up spacing passes reclaimed width from `Coin`, `Total Balance`, and the empty `Repay` lane, then used that room to enlarge `PNL (ROE %)` and create a more deliberate gutter between `PNL` and `Send`. Automated validation passed through `npm test`, `npm run test:websocket`, and `npm run check` again after the latest patch. Because `hyperopen-46t7` is now closed, this plan moves to `completed` as a historical record even though the final live-route browser rerun on `8083` never landed.
>>>>>>> 90109262 (Fix trade layout shell regressions)

The earlier `8083` stale-bundle observation remains useful historical context for why agent-run browser evidence stopped short of final signoff during implementation, but it is no longer an open blocker for this plan. The tracked work is accepted and ready to move into `/hyperopen/docs/exec-plans/completed/`.

## Context and Orientation

The balances tab is rendered through `/hyperopen/src/hyperopen/views/account_info_view.cljs`, which owns the outer account panel shell, the tab strip, and the header controls for the active tab. The balances table itself lives in `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`. The raw row data is shaped in `/hyperopen/src/hyperopen/views/account_info/projections/balances.cljs`, and the tab counts come from `/hyperopen/src/hyperopen/views/account_info/vm.cljs`.

In this repository, “account info” means the lower trade-route surface that contains tabs such as `Balances`, `Positions`, `Open Orders`, `TWAP`, `Trade History`, `Funding History`, and `Order History`. A “desktop viewport” means the `lg` and above layout where the balances content renders as a grid table, not the mobile card stack. A “control strip” means the row at the top-right of the active tab surface that currently contains the `Hide Small Balances` checkbox and the coin search input.

The current visual evidence for this plan lives under `/hyperopen/tmp/`:

- `/hyperopen/tmp/hyperliquid-balances-component.png`
- `/hyperopen/tmp/hyperopen-site-8083-balances-component-live-hardrefresh.png`

Those images show the main gaps this plan addresses: clipped lower rows, a missing `Repay` header, looser density, cell-formatting drift, and control/tab-strip drift.

## Plan of Work

First, update `/hyperopen/src/hyperopen/views/account_info_view.cljs` so the account tables shell does not hard-cap the balances surface to a fixed desktop height that hides valid rows. The goal is to preserve the desktop panel layout while allowing the balances viewport to reveal its complete row set. This likely means relaxing the outer `h-96` and nested `overflow-hidden` behavior for the balances path while keeping other tabs stable.

Next, update `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` so the desktop header and row grids become nine columns wide and explicitly include a `Repay` lane between `Transfer` and `Contract`. Keep the existing action semantics unchanged. If Hyperopen does not yet support a true repay action here, render a parity-safe placeholder presentation that preserves the lane and alignment without implying unsupported behavior.

Then tighten the desktop table density in `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`. Keep `PNL (ROE %)` on one line when possible, reduce extra row height, and make the desktop table read like a compact ledger. Revisit balance-cell formatting so the visible output trends closer to Hyperliquid by including unit suffixes where appropriate and removing unnecessary label drift such as `USDC (Spot)` when parity is the goal.

After that, adjust `/hyperopen/src/hyperopen/views/account_info_view.cljs` and `/hyperopen/src/hyperopen/views/account_info/vm.cljs` so the balances control strip and tab strip better match the reference. This includes changing the balances header controls away from the current search-field-first presentation and correcting count presentation drift such as `TWAP (0)` when the reference suppresses the zero count. The plan should prefer narrowly scoped label/count logic over broader tab-system rewrites.

Finally, add or update focused tests around the tab-label/count logic and any balances rendering helpers that can be validated outside browser-only snapshots. Then run the required repo quality gates and complete browser validation at the four design-review widths.

## Concrete Steps

Work from `/hyperopen`.

1. Keep this plan current while implementing. After each milestone, update the `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` sections before stopping.

2. Edit the desktop account-table shell in:

    `/hyperopen/src/hyperopen/views/account_info_view.cljs`

   Focus on the container rooted at `:data-parity-id "account-tables"` and the immediate content wrapper below it.

3. Edit the balances desktop table in:

    `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`

   Update the header grid, row grid, text formatting, and density classes. Preserve the mobile-card path unless a desktop-only fix is impossible.

4. Edit tab-count and tab-label behavior in:

    `/hyperopen/src/hyperopen/views/account_info_view.cljs`
    `/hyperopen/src/hyperopen/views/account_info/vm.cljs`

   Keep the change narrowly focused on parity for the existing tabs.

5. Add or update tests in the best existing account-info test namespace once the relevant explorer findings come back. Prefer extending existing `vm` or view helper tests over creating a disconnected new test harness.

6. Run validation commands:

    cd /hyperopen
    npm run check
    npm test
    npm run test:websocket

7. Run browser verification against the live route and reference at `375`, `768`, `1280`, and `1440`. Record each pass as `PASS`, `FAIL`, or `BLOCKED`, and keep evidence under `/hyperopen/tmp/browser-inspection/`.

## Validation and Acceptance

Acceptance is behavior, not just code edits.

On the live Hyperopen route at `http://localhost:8083/index.html?tab=balances`, with the same ghost-state dataset used during investigation, the desktop balances component must show the full six-row set without hiding `MUNCH` and `WOW`. The desktop header must include `Repay`, producing a nine-lane table. The rows should read more densely, and the obvious cell-formatting drift from the Hyperliquid reference should be reduced. The balances header controls and tab-strip counts should also move closer to the reference without breaking keyboard access or mobile rendering.

The minimum automated acceptance is:

    cd /hyperopen
    npm run check
    npm test
    npm run test:websocket

The minimum browser acceptance is:

- `visual`: PASS only if the balances table shows all desktop rows and the missing `Repay` lane is restored.
- `native-control`: PASS only if no unexpected browser-native control styling is introduced by the control-strip changes.
- `styling-consistency`: PASS only if the new classes remain token-aligned and avoid one-off drift.
- `interaction`: PASS only if the balances header controls still work with keyboard focus and the table remains readable after resize.
- `layout-regression`: PASS only if there is no clipping at `375`, `768`, `1280`, or `1440`.
- `jank-perf`: PASS only if the changes do not introduce obvious layout thrash during resize or tab switching.

If any pass cannot be completed because the browser fixture cannot be recreated, mark it `BLOCKED` with the missing prerequisite.

## Idempotence and Recovery

All planned edits are ordinary tracked-file changes and are safe to repeat. Re-running the quality gates should be idempotent. If a browser session loses the ghost-state fixture after a reload, restore it through the same live-site injection workflow used during investigation before capturing fresh evidence. If a density or count change creates regressions in non-balances tabs, revert that sub-change first and keep the clipping and column fixes isolated.

## Artifacts and Notes

Key evidence captured before implementation:

- Hyperliquid component screenshot:

    `/hyperopen/tmp/hyperliquid-balances-component.png`

- Hyperopen live-site component screenshot after hard refresh:

    `/hyperopen/tmp/hyperopen-site-8083-balances-component-live-hardrefresh.png`

- Live DOM evidence that six rows exist even while only the upper rows are visible:

    rowCount: 6
    rows:
      USDC (Spot) ...
      MEOW ...
      VAULT ...
      STAR ...
      MUNCH ...
      WOW ...

## Interfaces and Dependencies

No new libraries are required. This work should stay inside the existing view and projection modules:

- `/hyperopen/src/hyperopen/views/account_info_view.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`
- `/hyperopen/src/hyperopen/views/account_info/vm.cljs`
- existing account-info test namespaces under `/hyperopen/test/`

The implementation must preserve the existing public surface of `account-info-view` and the existing action identifiers already wired into the row actions. The only intended interface expansion inside this scope is the balances desktop table structure itself, which must expose a visible `Repay` lane.

Revision note: created this ExecPlan on 2026-03-18 to execute user-requested balances-tab parity work from live browser evidence while explicitly excluding action-semantics changes. Updated on 2026-03-20 to record `hyperopen-46t7` closure, preserve the blocked `8083` browser rerun as historical context, and move the plan to `completed`.
