# Mobile Balances Last Card Clearance

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

On phone-sized `/trade` layouts, the `Balances` tab currently lets the fixed bottom nav cover the last mobile balance card, so the final coin appears cut off even after the list is scrolled to the end. After this change, the balances cards viewport must reserve enough bottom clearance that the final card stays fully visible above the fixed nav.

The user-visible proof is to open `/trade` on a mobile viewport, switch to the chart-side `Balances` tab, scroll to the bottom of the mobile balances list, and confirm the last balance card clears the nav instead of disappearing underneath it.

## Progress

- [x] (2026-04-13 15:21Z) Reviewed the mobile balances renderer and confirmed it uses the same `py-2` mobile cards viewport pattern that caused the earlier positions clipping bug.
- [x] (2026-04-13 15:21Z) Created and claimed `bd` issue `hyperopen-ts1m` for this bug.
- [x] (2026-04-13 15:23Z) Reproduced the bug in a live mobile browser session and measured the last balance card sitting `81px` behind the fixed mobile nav after the viewport reached max scroll.
- [x] (2026-04-13 15:24Z) Authored this active ExecPlan with the measured failure mode and the implementation plan.
- [x] (2026-04-13 15:25Z) Updated `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` so `balances-mobile-cards-viewport` uses `pt-2` plus `pb-[calc(6rem+env(safe-area-inset-bottom))]` instead of symmetric `py-2`.
- [x] (2026-04-13 15:25Z) Extended `/hyperopen/tools/playwright/test/mobile-regressions.spec.mjs` with a deterministic regression that scrolls the balances cards viewport to the bottom and asserts the last card clears the fixed nav.
- [x] (2026-04-13 15:26Z) Ran the smallest relevant Playwright command first, then `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-04-13 15:26Z) Ran the governed trade-route browser QA flow, cleaned up browser sessions, and updated this ExecPlan with the final evidence so it can move to `completed`.

## Surprises & Discoveries

- Observation: The balances clipping bug is mechanically identical to the positions bug; the last card exists in the DOM and the failure is entirely inside the inner scroll container.
  Evidence: Live measurement on `balances-mobile-cards-viewport` reported `paddingBottom: "8px"`, `cardCount: 22`, and `overlap: 81` after `scrollTop = scrollHeight`.

- Observation: The chart-side mobile trade layout is still the correct reproduction surface for account tabs; the full-screen `Account` mobile surface is a different render path.
  Evidence: The embedded account panel with `Balances` cards renders only when the route stays on the chart-side mobile surface and `:actions/select-account-info-tab :balances` is dispatched there.

## Decision Log

- Decision: Apply the clearance fix inside `balances-mobile-cards-viewport` rather than changing global trade layout padding or the fixed nav.
  Rationale: The clipping happens only after the inner balances viewport reaches its own scroll limit, so only extra scrollable bottom clearance inside that viewport can expose the final card.
  Date/Author: 2026-04-13 / Codex

- Decision: Use a committed Playwright regression as the primary durability seam.
  Rationale: This is a real browser geometry bug involving the fixed nav overlay, so browser-based measurement is the correct regression surface.
  Date/Author: 2026-04-13 / Codex

## Outcomes & Retrospective

The fix is complete. The balances mobile cards viewport now reserves bottom clearance inside its own scroll container, which is the only place where additional scroll range can expose the final balance card above the fixed mobile nav. The rest of the balances cards rendering path was left unchanged.

Validation completed successfully:

- `npx playwright test tools/playwright/test/mobile-regressions.spec.mjs --grep "mobile balances"`
- `npm run check`
- `npm test`
- `npm run test:websocket`
- `npm run qa:design-ui -- --targets trade-route --manage-local-app`
- `npm run browser:cleanup`

The governed browser QA pass finished `PASS` for `/trade` at `375`, `768`, `1280`, and `1440` across visual, native-control, styling-consistency, interaction, layout-regression, and jank-perf. Residual blind spots were the standard ones for non-default hover, active, disabled, and loading states when they are not present by default.

## Context and Orientation

`/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` renders the balances table on desktop and the balances mobile cards on phone widths. The mobile cards viewport is `balances-mobile-cards-viewport`. Today that viewport uses `overflow-y-auto`, `space-y-2.5`, `px-2.5`, and `py-2`, which leaves only `8px` of bottom padding after layout.

`/hyperopen/src/hyperopen/views/footer/mobile_nav.cljs` renders the fixed bottom navigation that stays visible on mobile trade layouts. Because the nav is fixed to the bottom of the viewport, any mobile account-tab list that does not reserve enough internal bottom padding will clip its last row behind that nav.

`/hyperopen/tools/playwright/test/mobile-regressions.spec.mjs` already holds mobile trade regressions using the `HYPEROPEN_DEBUG` bridge. That is the right place to add a deterministic balances regression that scrolls the list and checks the final card against the nav geometry.

The tracked work item for this plan is `hyperopen-ts1m`.

## Plan of Work

First, update the balances mobile viewport class list in `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`. Replace the symmetric `py-2` padding with `pt-2` plus a larger bottom padding that reserves space for the fixed nav and any safe-area inset. Keep the rest of the balances cards rendering path unchanged.

Next, add a Playwright regression in `/hyperopen/tools/playwright/test/mobile-regressions.spec.mjs`. Reuse the spectate route and the existing mobile helpers to switch to the chart-side `Balances` tab, wait for mobile balance cards, scroll the viewport to the bottom, and poll until the last card's bottom edge is at or above the top edge of `mobile-bottom-nav`.

Finally, run the focused Playwright command first, then the repo-required validation gates and the governed trade-route browser QA flow. Record the browser measurement and QA outcome here, move this plan to `completed`, and close `hyperopen-ts1m`.

## Concrete Steps

Working directory for every command below: `/Users/barry/.codex/worktrees/ffb7/hyperopen`

Reproduction command used during investigation:

    timeout 45s node --input-type=module <<'NODE'
    import { chromium } from '@playwright/test';
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 3 });
    await page.goto('http://127.0.0.1:8081/index.html?spectate=0x162cc7c861ebd0c06b3d72319201150482518185', { waitUntil: 'domcontentloaded', timeout: 20000 });
    await page.waitForFunction(() => Boolean(globalThis.HYPEROPEN_DEBUG?.dispatchMany && globalThis.HYPEROPEN_DEBUG?.waitForIdle), { timeout: 20000 });
    await page.evaluate(() => globalThis.HYPEROPEN_DEBUG.dispatchMany([
      [':actions/select-trade-mobile-surface', ':chart'],
      [':actions/select-account-info-tab', ':balances']
    ]));
    await page.evaluate(() => globalThis.HYPEROPEN_DEBUG.waitForIdle({ quietMs: 300, timeoutMs: 10000, pollMs: 50 }));
    await page.waitForSelector('[data-role="balances-mobile-cards-viewport"] [data-role^="mobile-balance-card-"]', { timeout: 15000 });
    const data = await page.evaluate(() => {
      const viewport = document.querySelector('[data-role="balances-mobile-cards-viewport"]');
      const cards = Array.from(document.querySelectorAll('[data-role="balances-mobile-cards-viewport"] [data-role^="mobile-balance-card-"]'));
      const lastCard = cards.at(-1);
      const bottomNav = document.querySelector('[data-role="mobile-bottom-nav"]');
      viewport.scrollTop = viewport.scrollHeight;
      return {
        paddingBottom: getComputedStyle(viewport).paddingBottom,
        cardCount: cards.length,
        overlap: lastCard.getBoundingClientRect().bottom - bottomNav.getBoundingClientRect().top
      };
    });
    console.log(JSON.stringify(data, null, 2));
    await browser.close();
    NODE

Observed failure before the fix:

    {
      "paddingBottom": "8px",
      "cardCount": 22,
      "overlap": 81
    }

Validation commands to run after the code changes:

    npx playwright test tools/playwright/test/mobile-regressions.spec.mjs --grep "mobile balances"
    npm run check
    npm test
    npm run test:websocket
    npm run qa:design-ui -- --targets trade-route --manage-local-app
    npm run browser:cleanup

## Validation and Acceptance

Acceptance requires that on a phone-sized browser session against the spectate `/trade` route, the chart-side `Balances` tab renders mobile cards and the last balance card stays fully above the fixed mobile nav after the cards viewport is scrolled to the bottom.

The new Playwright regression must pass, and `npm run check`, `npm test`, `npm run test:websocket`, the governed trade-route browser QA flow, and browser cleanup must all complete successfully.

## Idempotence and Recovery

This change is localized to the balances mobile cards viewport and a browser regression. Re-running the tests is safe. If the padding value proves too small in the browser regression, increase only the balances viewport bottom clearance in `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` and rerun the same validation commands.

## Artifacts and Notes

Measured browser geometry before the fix:

    {
      "paddingBottom": "8px",
      "cardCount": 22,
      "overlap": 81,
      "navTop": 796,
      "lastBottom": 877
    }

This proves the final balance card exists but remains hidden by the fixed nav after the inner viewport reaches its maximum scroll offset.

## Interfaces and Dependencies

Use the existing balances renderer in `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`; do not change the public API of `balances-tab-content`.

Use the existing Playwright helpers from `/hyperopen/tools/playwright/support/hyperopen.mjs`, especially `visitRoute`, `dispatchMany`, and `waitForIdle`, so the new regression remains consistent with the rest of the mobile suite.

Revision note (2026-04-13 / Codex): Created the active ExecPlan after reproducing the balances clipping bug in a live mobile browser session and measuring that the final balance card remained 81px behind the fixed mobile nav because the balances viewport reserved only 8px of bottom padding.

Revision note (2026-04-13 / Codex): Completed the fix by increasing the balances mobile viewport bottom clearance to `6rem + env(safe-area-inset-bottom)`, adding a deterministic Playwright regression for the chart-side balances panel, passing `npm run check`, `npm test`, `npm run test:websocket`, and the governed trade-route browser QA flow, then cleaning up browser-inspection sessions.
