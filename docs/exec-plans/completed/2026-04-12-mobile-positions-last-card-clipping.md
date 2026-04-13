# Mobile Positions Last Card Clearance

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

On phone-sized trade layouts, the `Positions` tab currently renders its final mobile position card underneath the fixed bottom navigation, so a trader cannot fully see or interact with the last row even after scrolling the list to the end. After this change, the last mobile position card must remain fully visible above the bottom navigation on the `/trade` route while preserving the existing desktop table and mobile card behaviors.

The user-visible proof is simple: open `/trade` on a mobile viewport, switch to the `Positions` tab in the chart-side account panel, scroll the cards list to the bottom, and confirm the last card clears the fixed mobile nav instead of disappearing behind it.

## Progress

- [x] (2026-04-13 00:54Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/FRONTEND.md`, and `/hyperopen/docs/BROWSER_TESTING.md` to confirm the planning, UI, and validation contract for this bug.
- [x] (2026-04-13 00:54Z) Created and claimed `bd` issue `hyperopen-8ar7` for this bug so the ExecPlan can link to live tracked work.
- [x] (2026-04-13 00:58Z) Reproduced the bug in a live mobile browser session against `http://127.0.0.1:8081/index.html?spectate=0x162cc7c861ebd0c06b3d72319201150482518185` and measured the clipped geometry.
- [x] (2026-04-13 01:02Z) Authored this active ExecPlan with the measured failure mode and the implementation plan.
- [x] (2026-04-13 01:05Z) Updated `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` so the mobile positions cards viewport uses `pt-2` plus `pb-[calc(6rem+env(safe-area-inset-bottom))]`, giving the inner scroll container enough bottom-nav clearance to fully expose the last card.
- [x] (2026-04-13 01:14Z) Added a deterministic Playwright regression in `/hyperopen/tools/playwright/test/mobile-regressions.spec.mjs` that scrolls the mobile positions cards viewport to the bottom and polls until the last card clears the fixed bottom nav.
- [x] (2026-04-13 01:33Z) Ran the smallest relevant Playwright command first, then the required repo gates: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-04-13 01:33Z) Updated this ExecPlan with the final validation evidence and prepared it to move to `/hyperopen/docs/exec-plans/completed/`.
- [x] (2026-04-13 01:09Z) Ran the governed trade-route browser QA flow, recorded `PASS` across `375`, `768`, `1280`, and `1440` for visual, native-control, styling-consistency, interaction, layout-regression, and jank-perf, then cleaned up browser-inspection sessions.

## Surprises & Discoveries

- Observation: The clipping does not happen on the dedicated mobile `Account` surface. It happens on the chart-side mobile layout where the account panel stays embedded above the fixed bottom nav.
  Evidence: Dispatching `:actions/select-trade-mobile-surface :account` renders the equity summary overlay but no `account-tables` panel, while `:actions/select-trade-mobile-surface :chart` plus `:actions/select-account-info-tab :positions` renders `positions-mobile-cards-viewport`.

- Observation: The last mobile position card is present in the DOM and can be scrolled to the bottom of its internal viewport, but it still remains underneath the fixed nav because the viewport reserves only `8px` of bottom padding.
  Evidence: Live browser measurement on the reproduced bug reported `paddingBottom: "8px"` for `positions-mobile-cards-viewport`; after `scrollTop = scrollHeight`, the last card still measured `81px` below `mobile-bottom-nav`'s top edge.

- Observation: The broken behavior is a layout-clearance bug, not a missing-row or data-loading bug.
  Evidence: The reproduction captured `cardCount: 12` in the positions viewport and the last card `data-role` existed before and after scrolling; only its final visible position was wrong.

- Observation: A one-shot browser assertion was flaky even after the layout fix because the card list and geometry were still settling when Playwright sampled the overlap value.
  Evidence: The first regression shape intermittently read `overlapPx` as `89` during startup despite a manual post-fix measurement on the same code showing `paddingBottom: "96px"` and `overlap: -7`; switching the test to `expect.poll` over the scrolled geometry made the browser assertion deterministic.

- Observation: The repo's required `npm run check` gate was blocked by two unrelated pre-existing guardrail failures: one stale active ExecPlan and one namespace-size budget pressure in the already-exceptional positions namespaces.
  Evidence: `dev.check-docs` initially failed because `/hyperopen/docs/exec-plans/active/2026-04-12-mobile-active-asset-funding-tooltip-sheet.md` referenced only a closed `bd` issue and had no unchecked progress items; after moving that file to `completed`, `dev.check-namespace-sizes` still failed until this patch was trimmed back to the exact existing line-budget ceilings for `positions.cljs` and `positions_test.cljs`.

## Decision Log

- Decision: Fix the clearance inside the mobile positions cards viewport instead of changing the bottom nav or the outer trade page padding.
  Rationale: The last-card clipping happens inside the positions list's own scroll container. Outer page padding does not help once the inner viewport has reached its own scroll limit, so the fix must reserve clearance where the scrolling happens.
  Date/Author: 2026-04-13 / Codex

- Decision: Add a committed Playwright regression for the chart-side mobile positions panel, then add a smaller unit assertion for the viewport classes.
  Rationale: The user-visible bug depends on real browser geometry and a fixed nav overlay, so Playwright is the correct primary regression surface. A unit assertion looked reasonable initially, but the existing namespace-size budgets made it a bad trade once the browser regression was in place.
  Date/Author: 2026-04-13 / Codex

- Decision: Reserve `6rem` plus the safe-area inset inside `positions-mobile-cards-viewport` instead of trying to infer a smaller exact number.
  Rationale: The measured failure was `81px` behind the nav. `6rem` gives a small safety margin over the fixed mobile nav height and keeps the final card visibly clear in browser measurements without changing the surrounding layout structure.
  Date/Author: 2026-04-13 / Codex

- Decision: Move the unrelated funding-tooltip ExecPlan out of `active` during validation instead of weakening `lint:docs`.
  Rationale: The file already described completed work and referenced a closed issue, so moving it to `completed` restored the documented repo contract without introducing a rules exception.
  Date/Author: 2026-04-13 / Codex

## Outcomes & Retrospective

The fix is complete. The mobile positions tab now reserves bottom clearance inside its own cards viewport, which is the only place where extra scroll range can actually expose the last card above the fixed phone nav. The desktop positions table and the rest of the mobile positions card structure were left unchanged.

Validation completed successfully:

- `npx playwright test tools/playwright/test/mobile-regressions.spec.mjs --grep "mobile positions"`
- `npm run check`
- `npm test`
- `npm run test:websocket`
- `npm run qa:design-ui -- --targets trade-route --manage-local-app`
- `npm run browser:cleanup`

Post-fix browser measurement on the rebuilt app reported:

- `paddingBottom: "96px"`
- `cardCount: 12`
- `overlap: -7`

That means the last card now clears the mobile nav by `7px` after the list is scrolled to the bottom. This change reduced overall complexity because the implementation stayed entirely inside the existing positions viewport and reused the current mobile rendering path instead of introducing viewport-wide layout changes or nav-specific special cases.

## Context and Orientation

`/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` renders both the desktop positions table and the mobile positions cards. The desktop path uses `account-tab-rows-viewport`, while the phone/tablet card path uses `positions-mobile-cards-viewport`. The bug lives in the mobile path near `positions-tab-content-from-rows`, where the cards viewport currently uses `py-2`, `px-2.5`, and `overflow-y-auto` but no explicit bottom-nav clearance.

`/hyperopen/src/hyperopen/views/footer/mobile_nav.cljs` renders the fixed bottom navigation visible on phone layouts. That nav is visually persistent at the bottom of the viewport and includes its own safe-area padding.

`/hyperopen/src/hyperopen/views/trade_view.cljs` and `/hyperopen/src/hyperopen/views/trade_view/layout_state.cljs` explain why the reproduction must use the chart-side mobile surface instead of the full-screen account surface. On phones, the embedded account panel remains visible only on the market-oriented surfaces such as `:chart`; the `:account` surface swaps in a different full-screen summary.

`/hyperopen/tools/playwright/test/mobile-regressions.spec.mjs` already contains mobile trade regressions that use the in-app `HYPEROPEN_DEBUG` bridge. This is the right place to assert that the final position card can scroll fully above the fixed mobile nav on a deterministic spectate route.

`/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs` provides pure rendering coverage for the positions tab Hiccup tree. It is the right place to assert that the mobile viewport now includes the intended bottom-clearance classes when rendered on a phone-sized viewport.

The tracked work item for this plan is `hyperopen-8ar7`.

## Plan of Work

First, update the mobile positions viewport class list in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`. Replace the symmetric `py-2` padding with `pt-2` plus a larger bottom padding that explicitly reserves space for the fixed mobile nav and any safe-area inset. Keep the existing desktop renderer and the rest of the mobile card structure unchanged.

Next, add a browser regression in `/hyperopen/tools/playwright/test/mobile-regressions.spec.mjs`. Use the existing spectate route and `HYPEROPEN_DEBUG` dispatch helpers to switch to the chart-side mobile surface and the `Positions` tab, wait for cards to render, scroll the mobile positions viewport to its maximum, and poll until the last card's bottom edge is at or above the top edge of `mobile-bottom-nav`.

Finally, run the smallest relevant Playwright command first to verify the new regression, then run the repo-required validation commands. Record exact results back into this plan, then move the plan to `completed` and close `hyperopen-8ar7`.

## Concrete Steps

Working directory for every command below: `/Users/barry/.codex/worktrees/ffb7/hyperopen`

Reproduction command used during investigation:

    timeout 45s node --input-type=module <<'NODE'
    import { chromium } from '@playwright/test';
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 3 });
    await page.goto('http://127.0.0.1:8081/index.html?spectate=0x162cc7c861ebd0c06b3d72319201150482518185', { waitUntil: 'domcontentloaded', timeout: 20000 });
    await page.waitForFunction(() => Boolean(globalThis.HYPEROPEN_DEBUG?.dispatchMany && globalThis.HYPEROPEN_DEBUG?.oracle), { timeout: 20000 });
    await page.evaluate(() => globalThis.HYPEROPEN_DEBUG.dispatchMany([
      [':actions/select-trade-mobile-surface', ':chart'],
      [':actions/select-account-info-tab', ':positions']
    ]));
    await page.waitForTimeout(7000);
    const data = await page.evaluate(() => {
      const viewport = document.querySelector('[data-role="positions-mobile-cards-viewport"]');
      const cards = Array.from(document.querySelectorAll('[data-role="positions-mobile-cards-viewport"] [data-role^="mobile-position-card-"]'));
      const lastCard = cards.at(-1);
      const bottomNav = document.querySelector('[data-role="mobile-bottom-nav"]');
      viewport.scrollTop = viewport.scrollHeight;
      return {
        paddingBottom: getComputedStyle(viewport).paddingBottom,
        cardCount: cards.length,
        hiddenBehindBottomNav: lastCard.getBoundingClientRect().bottom - bottomNav.getBoundingClientRect().top
      };
    });
    console.log(JSON.stringify(data, null, 2));
    await browser.close();
    NODE

Observed failure before the fix:

    {
      "paddingBottom": "8px",
      "cardCount": 12,
      "hiddenBehindBottomNav": 81
    }

Validation commands run after the code changes:

    npx playwright test tools/playwright/test/mobile-regressions.spec.mjs --grep "mobile positions"
    npm run check
    npm test
    npm run test:websocket

## Validation and Acceptance

Acceptance requires all of the following:

On a phone-sized browser session against the spectate `/trade` route, the chart-side `Positions` tab must render mobile cards, scrolling that cards viewport to its maximum must keep the last card fully above the fixed mobile nav, and the new Playwright regression must pass.

The repo gates must all pass: `npm run check`, `npm test`, and `npm run test:websocket`.

## Idempotence and Recovery

The code changes are additive and localized. Re-running the tests and Playwright command is safe. If the padding value proves too small in the browser regression, adjust the viewport bottom-clearance class in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` and rerun the same validation commands. No database or remote state changes are involved.

## Artifacts and Notes

Measured browser geometry before the fix:

    {
      "viewportRect": { "top": 588, "bottom": 885, "height": 297 },
      "bottomNavRect": { "top": 796, "bottom": 844, "height": 48 },
      "lastCardRectAfterScroll": { "top": 811, "bottom": 877, "height": 66 },
      "hiddenBehindBottomNav": 81
    }

This proves the last row exists but remains obscured by the nav overlay after the inner viewport reaches its maximum scroll offset.

## Interfaces and Dependencies

Use the existing `positions-tab-content-from-rows` renderer in `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`; do not change the public API of `positions-tab-content`.

Use the existing Playwright helpers from `/hyperopen/tools/playwright/support/hyperopen.mjs`, especially `visitRoute`, `dispatchMany`, and `waitForIdle`, so the new regression remains consistent with the rest of the suite.

Use the existing test support helpers from `/hyperopen/test/hyperopen/views/account_info/test_support/hiccup.cljs` to inspect the rendered viewport node and its classes.

Revision note (2026-04-13 / Codex): Created the active ExecPlan after reproducing the bug in a live mobile browser session and measuring that the final card remained 81px behind the fixed mobile nav because the positions viewport only reserved 8px of bottom padding.

Revision note (2026-04-13 / Codex): Completed the fix by increasing the mobile positions viewport bottom clearance to `6rem + env(safe-area-inset-bottom)`, adding a deterministic Playwright regression for the chart-side mobile positions panel, passing `npm run check`, `npm test`, and `npm run test:websocket`, and recording the post-fix browser measurement of `overlap: -7`.

Revision note (2026-04-13 / Codex): Added the governed trade-route browser QA result after completion: review outcome `PASS` with all six passes green at `375`, `768`, `1280`, and `1440`, plus the standard residual blind spot that non-default hover, active, disabled, and loading states still need targeted route actions when not present by default.
