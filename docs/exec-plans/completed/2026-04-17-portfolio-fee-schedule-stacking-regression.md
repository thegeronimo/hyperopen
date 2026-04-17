# Fix portfolio fee schedule stacking regression

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

Tracked issue: `hyperopen-qfo7` ("Fix fee schedule popover stacking regression").

## Purpose / Big Picture

The portfolio fee schedule is an anchored popover opened from the portfolio summary card. It must render as an opaque interaction surface above the account tabs and account panels. A regression lets the lower account navigation paint through the fee schedule and receive clicks while the fee schedule is open.

After this change, a user can click `View Fee Schedule` on `/portfolio` and the fee schedule surface will cover any content under its bounds. The account tabs, deposits and withdrawals controls, performance metrics, balances, and other portfolio panel controls must not appear through the fee schedule and must not be clickable until the fee schedule is closed.

## Progress

- [x] (2026-04-17 18:25Z) Created tracker `hyperopen-qfo7`.
- [x] (2026-04-17 18:27Z) Inspected the fee schedule popover, account panel layers, and existing Playwright regression.
- [x] (2026-04-17 18:29Z) Wrote this active ExecPlan.
- [x] (2026-04-17 18:31Z) Added failing unit/browser regression coverage for popover stacking and pointer interception.
- [x] (2026-04-17 18:31Z) Confirmed focused RED CLJS failed only on the old layer contract.
- [x] (2026-04-17 18:32Z) Implemented the minimal popover layer fix.
- [x] (2026-04-17 18:33Z) Focused CLJS view test passed.
- [x] (2026-04-17 18:36Z) Focused Playwright fee schedule regression passed on alternate port 4174.
- [x] (2026-04-17 18:34Z) `npm run check` passed.
- [x] (2026-04-17 18:34Z) `npm test` passed with 3220 tests, 17277 assertions, 0 failures, and 0 errors.
- [x] (2026-04-17 18:35Z) `npm run test:websocket` passed with 432 tests, 2479 assertions, 0 failures, and 0 errors.
- [x] (2026-04-17 18:35Z) Governed browser QA passed for portfolio-route at review widths 375, 768, 1280, and 1440.
- [x] (2026-04-17 18:35Z) Ran `npm run browser:cleanup`; browser inspection session `sess-1776450832443-a89ec0` was stopped.
- [x] (2026-04-17 18:35Z) Closed tracker `hyperopen-qfo7` as fixed.

## Surprises & Discoveries

- Observation: The fee schedule overlay currently renders at `z-[310]` with `pointer-events-none`, while its backdrop and dialog opt back into pointer events individually.
  Evidence: `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/fee_schedule.cljs`.

- Observation: The visible account tabs in the screenshot line up inside the fee schedule's visual bounds, which points to a stacking order problem rather than a merely transparent backdrop outside the popover.
  Evidence: The user screenshot shows account tab labels crossing the `MAKER REBATE` and `VOLUME TIER` sections.

- Observation: The RED focused CLJS test failed only on the old overlay/dialog/menu layer expectations.
  Evidence: `node out/test.js --test=hyperopen.views.portfolio.fee-schedule-test` reported five failures for `z-[310]`, `pointer-events-none`, `z-[311]`, and `z-[315]`.

- Observation: The default Playwright port was occupied by an existing Shadow CLJS process, so the focused browser regression used a temporary static-server config on port 4174.
  Evidence: `npx playwright test ...` first reported `http://127.0.0.1:8080/ is already used`; `lsof` showed Java PID 24499 listening on ports 8080 and 9630.

- Observation: The governed design-review command also tried to start a managed local app by default and hit the same Shadow CLJS already-running condition.
  Evidence: `npm run qa:design-ui -- --targets portfolio-route --manage-local-app` and the same command without the flag both failed at local app startup. Starting a browser-inspection session against the already-running app and then passing `--session-id` allowed the governed review to run without disrupting the dev server.

## Decision Log

- Decision: Keep the fee schedule as an anchored popover, not a centered modal or dimmed page overlay.
  Rationale: The user requested the popover interaction to reduce pointer travel. This fix should preserve that behavior while making the popover layer opaque and non-porous.
  Date/Author: 2026-04-17 / Codex

- Decision: Raise the fee schedule layer above account and app chrome popovers and make the overlay itself pointer-interactive.
  Rationale: The bug is that lower UI can paint through and receive clicks. A higher isolated layer plus a full-screen hit target fixes both symptoms without changing fee schedule content or fee math.
  Date/Author: 2026-04-17 / Codex

## Outcomes & Retrospective

Implemented and validated. The portfolio fee schedule layer now owns pointer events, renders above account panels, and keeps dropdown menus above the dialog surface. The focused browser regression now hit-tests inside the open fee schedule and over the trigger so future regressions where account tabs paint or click through the popover should fail deterministically.

The remaining blind spot is the governed design-review harness's standard sampled-state note: hover, active, disabled, and loading states require targeted route actions when not present by default. The focused Playwright regression covers the fee schedule interaction state relevant to this bug.

## Context and Orientation

The fee schedule view is `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/fee_schedule.cljs`. It renders:

- `portfolio-fee-schedule-overlay`, a fixed full-screen layer.
- `portfolio-fee-schedule-backdrop`, a full-screen transparent close target.
- `portfolio-fee-schedule-dialog`, the anchored opaque fee schedule surface.
- dropdown menus inside selector controls.

The focused view contract lives in `/Users/barry/.codex/worktrees/09e3/hyperopen/test/hyperopen/views/portfolio/fee_schedule_test.cljs`.

The deterministic browser regression lives in `/Users/barry/.codex/worktrees/09e3/hyperopen/tools/playwright/test/portfolio-regressions.spec.mjs`.

## Implementation Plan

First, update the view test to require the overlay to own pointer events, require a higher overlay/dialog/menu z-index, and forbid the old `pointer-events-none` overlay contract.

Second, update the portfolio Playwright regression to detect when the topmost element at the fee schedule dialog's center or left edge is not the fee schedule layer. Also check that the overlay receives pointer events and that the backdrop is still transparent.

Third, update `/Users/barry/.codex/worktrees/09e3/hyperopen/src/hyperopen/views/portfolio/fee_schedule.cljs` with the smallest layer change:

- Use a high fee schedule overlay z-index.
- Use a higher dialog z-index inside that layer.
- Use a higher dropdown-menu z-index inside the dialog.
- Change the overlay from `pointer-events-none` to `pointer-events-auto`.
- Keep the backdrop transparent so the component remains a popover instead of a dimmed modal.

Fourth, run focused tests, then required validation gates:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.views.portfolio.fee-schedule-test
    npx shadow-cljs --force-spawn compile app portfolio-worker vault-detail-worker
    npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "portfolio fee schedule" --workers=1
    npm run check
    npm test
    npm run test:websocket
    npm run browser:cleanup
    npm run qa:design-ui -- --targets portfolio-route --manage-local-app
    npm run browser:cleanup

## Acceptance Criteria

1. The fee schedule dialog renders above account tabs and account panel controls.
2. The fee schedule dialog has an opaque background and no account text paints through its bounds.
3. The overlay intercepts clicks while the fee schedule is open, so account tabs and account panel actions cannot be clicked through the fee schedule.
4. The fee schedule remains anchored near the trigger, fits in the viewport, and keeps its transparent outside backdrop.
5. Focused CLJS, focused Playwright, required repo gates, and governed browser QA pass.

## Idempotence and Recovery

The fix is a source-level CSS class contract change. If Playwright sees a stale bundle, force a fresh app compile before rerunning. If browser QA starts local app or browser sessions, run `npm run browser:cleanup` before ending the task.
