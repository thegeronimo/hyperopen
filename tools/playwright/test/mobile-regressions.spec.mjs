import { expect, test } from "@playwright/test";
import {
  debugCall,
  dispatch,
  dispatchMany,
  expectOracle,
  mobileViewport,
  visitRoute,
  waitForIdle
} from "../support/hyperopen.mjs";

const spectateRoute =
  "/trade?spectate=0x162cc7c861ebd0c06b3d72319201150482518185";

const connectedOwnerAddress = "0x1234567890abcdef1234567890abcdef12345678";

async function seedTradeMarketStats(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    const market = c.js__GT_clj(
      {
        key: "perp:BTC",
        coin: "BTC",
        symbol: "BTC",
        base: "BTC",
        dex: "",
        "market-type": "perp"
      },
      opts
    );
    const context = c.js__GT_clj(
      {
        coin: "BTC",
        mark: 123456.789,
        markRaw: "123456.789",
        oracle: 123450.123,
        oracleRaw: "123450.123",
        change24h: -12345.678,
        change24hPct: -9.876,
        volume24h: 987654321.12,
        openInterest: 987654.321,
        fundingRate: 0.0001234
      },
      opts
    );

    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, kwPath("active-asset"), "BTC");
    nextState = c.assoc_in(nextState, kwPath("selected-asset"), "BTC");
    nextState = c.assoc_in(nextState, kwPath("active-market"), market);
    nextState = c.assoc_in(
      nextState,
      kwPath("active-assets", "contexts", "BTC"),
      context
    );
    nextState = c.assoc_in(nextState, kwPath("trade-ui", "mobile-asset-details-open?"), false);
    c.reset_BANG_(store, nextState);

    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  });
}

async function connectSimulatedOwner(page) {
  await debugCall(page, "installWalletSimulator", {
    accounts: [connectedOwnerAddress],
    requestAccounts: [connectedOwnerAddress],
    chainId: "0xa4b1"
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");
  await dispatch(page, [":actions/connect-wallet"]);
  await expectOracle(page, "wallet-status", {
    connected: true,
    address: connectedOwnerAddress
  });
}

async function freezeConnectedAccountSync(page, address) {
  await page.evaluate((nextAddress) => {
    const store = globalThis.hyperopen?.system?.store;
    const addressWatcher = globalThis.hyperopen?.wallet?.address_watcher;
    const webdata2 = globalThis.hyperopen?.websocket?.webdata2;
    const userSubscriptions = globalThis.hyperopen?.websocket?.user_runtime?.subscriptions;

    if (!store || !addressWatcher || !webdata2 || !userSubscriptions) {
      throw new Error("Hyperopen account sync runtime unavailable");
    }

    addressWatcher.stop_watching_BANG_(store);
    addressWatcher.remove_handler_BANG_("webdata2-subscription-handler");
    addressWatcher.remove_handler_BANG_("user-ws-subscription-handler");
    addressWatcher.remove_handler_BANG_("startup-account-bootstrap-handler");
    webdata2.unsubscribe_webdata2_BANG_(nextAddress);
    userSubscriptions.unsubscribe_user_BANG_(nextAddress);
  }, address);
}

async function readCellTextOverflow(page, selector) {
  return page.locator(selector).evaluateAll((cells) => {
    const epsilon = 0.5;

    return cells.flatMap((cell) => {
      const cellRect = cell.getBoundingClientRect();
      const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT);
      const overflow = [];
      let node = walker.nextNode();

      while (node) {
        const text = node.textContent?.trim();
        if (text) {
          const range = document.createRange();
          range.selectNodeContents(node);
          for (const rect of range.getClientRects()) {
            if (
              rect.width > 0 &&
              (rect.left < cellRect.left - epsilon || rect.right > cellRect.right + epsilon)
            ) {
              overflow.push({
                text,
                cell: { left: cellRect.left, right: cellRect.right },
                textRect: { left: rect.left, right: rect.right }
              });
            }
          }
        }
        node = walker.nextNode();
      }

      return overflow;
    });
  });
}

async function readAdjacentCellOverlap(page, selector) {
  return page.locator(selector).evaluateAll((cells) => {
    const epsilon = 0.5;
    const rects = cells.map((cell) => {
      const rect = cell.getBoundingClientRect();
      return { left: rect.left, right: rect.right };
    });

    return rects.flatMap((previous, index) => {
      const next = rects[index + 1];
      if (next && previous.right > next.left + epsilon) {
        return [{ index, previous, next }];
      }
      return [];
    });
  });
}

async function seedMobilePositionRows(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    const nextWebdata2 = c.js__GT_clj(
      {
        clearinghouseState: {
          marginSummary: {
            accountValue: "4708974.9",
            totalNtlPos: "6398054.11",
            totalRawUsd: "6392466.23",
            totalMarginUsed: "63387.27"
          },
          crossMarginSummary: {
            accountValue: "4708974.9",
            totalNtlPos: "6398054.11",
            totalRawUsd: "6392466.23",
            totalMarginUsed: "63387.27"
          },
          crossMaintenanceMarginUsed: "63387.27",
          withdrawable: "4234255.18",
          assetPositions: [
            {
              position: {
                coin: "BTC",
                szi: "0.99455",
                positionValue: "67250.5",
                entryPx: "67000",
                markPx: "67251",
                unrealizedPnl: "309",
                returnOnEquity: "0.0046",
                liquidationPx: "4671.46",
                leverage: { value: 3, type: "isolated" },
                marginUsed: "1000",
                cumFunding: { sinceOpen: "0" }
              }
            }
          ]
        }
      },
      opts
    );

    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, kwPath("webdata2"), nextWebdata2);
    nextState = c.assoc_in(nextState, kwPath("perp-dex-clearinghouse"), c.PersistentArrayMap.EMPTY);
    nextState = c.assoc_in(nextState, kwPath("account-info", "selected-tab"), keyword("positions"));
    nextState = c.assoc_in(nextState, kwPath("account-info", "loading"), false);
    nextState = c.assoc_in(nextState, kwPath("account-info", "error"), null);

    c.reset_BANG_(store, nextState);

    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  });
}

async function seedMobilePositionsUntilVisible(page) {
  const firstCard = page.locator("[data-role^='mobile-position-card-']").first();

  for (let attempt = 0; attempt < 4; attempt += 1) {
    await seedMobilePositionRows(page);
    await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });

    if ((await firstCard.count()) > 0 && await firstCard.isVisible()) {
      return firstCard;
    }
  }

  throw new Error("Unable to render a seeded isolated position card for the connected owner");
}

test.describe("mobile browser regressions @mobile", () => {
  test.use(mobileViewport);

  test("account surface positions tab stays reachable on mobile @regression", async ({ page }) => {
    await visitRoute(page, spectateRoute);

    await dispatchMany(page, [
      [":actions/select-trade-mobile-surface", ":account"],
      [":actions/select-account-info-tab", ":positions"]
    ]);
    await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });
    await expectOracle(page, "account-surface", {
      mobileSurface: "account",
      selectedTab: "positions",
      mobileAccountPanelPresent: true
    });
  });

  test("Spectate positions expose no margin edit control or mobile sheet @regression", async ({ page }) => {
    await visitRoute(page, spectateRoute);

    await dispatchMany(page, [
      [":actions/select-trade-mobile-surface", ":chart"],
      [":actions/select-account-info-tab", ":positions"]
    ]);
    const firstCard = await seedMobilePositionsUntilVisible(page);
    await expectOracle(
      page,
      "first-position",
      { present: true },
      { timeoutMs: 8_000 }
    );
    await expect(firstCard).toBeVisible();
    const cardToggle = firstCard.getByRole("button").first();
    await cardToggle.click();
    await expect(cardToggle).toHaveAttribute("aria-expanded", "true");
    await expect(page.getByRole("button", { name: "Edit Margin", exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Margin", exact: true })).toHaveCount(0);
    await expect(page.locator("[data-role='position-margin-mobile-sheet-layer']")).toHaveCount(0);
    await expectOracle(
      page,
      "position-overlay",
      { open: false },
      { args: { surface: "margin" } }
    );
  });

  test("a connected owner opens position margin as a mobile sheet @regression", async ({ page }) => {
    await visitRoute(page, "/trade");
    await freezeConnectedAccountSync(page, connectedOwnerAddress);
    await connectSimulatedOwner(page);
    await dispatchMany(page, [
      [":actions/select-trade-mobile-surface", ":chart"],
      [":actions/select-account-info-tab", ":positions"]
    ]);
    const firstCard = await seedMobilePositionsUntilVisible(page);

    await expectOracle(
      page,
      "first-position",
      { present: true },
      { timeoutMs: 8_000 }
    );
    await expect(firstCard).toBeVisible();
    await firstCard.getByRole("button").first().click();
    const editMargin = firstCard.getByRole("button", { name: "Edit Margin", exact: true });
    await expect(editMargin).toBeVisible();
    await editMargin.click();
    await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });

    await expectOracle(
      page,
      "position-overlay",
      {
        open: true,
        presentationMode: "mobile-sheet"
      },
      { args: { surface: "margin" } }
    );
  });

  test("mobile positions list clears the fixed bottom nav @regression", async ({ page }) => {
    await visitRoute(page, spectateRoute);

    await dispatchMany(page, [
      [":actions/select-trade-mobile-surface", ":chart"],
      [":actions/select-account-info-tab", ":positions"]
    ]);
    await seedMobilePositionRows(page);
    await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });

    const viewport = page.locator("[data-role='positions-mobile-cards-viewport']");
    const lastCard = page.locator(
      "[data-role='positions-mobile-cards-viewport'] [data-role^='mobile-position-card-']"
    ).last();
    const bottomNav = page.locator("[data-role='mobile-bottom-nav']");

    await expect(lastCard).toBeVisible({ timeout: 15_000 });
    const cardCount = await page.evaluate(() =>
      document.querySelectorAll(
        "[data-role='positions-mobile-cards-viewport'] [data-role^='mobile-position-card-']"
      ).length
    );

    expect(cardCount).toBeGreaterThan(0);
    await expect
      .poll(
        () =>
          page.evaluate(() => {
            const viewportNode = document.querySelector(
              "[data-role='positions-mobile-cards-viewport']"
            );
            const cards = Array.from(
              document.querySelectorAll(
                "[data-role='positions-mobile-cards-viewport'] [data-role^='mobile-position-card-']"
              )
            );
            const lastCardNode = cards.at(-1);
            const bottomNavNode = document.querySelector("[data-role='mobile-bottom-nav']");

            if (!viewportNode || !lastCardNode || !bottomNavNode) {
              throw new Error("required mobile positions nodes missing");
            }

            viewportNode.scrollTop = viewportNode.scrollHeight;
            return (
              lastCardNode.getBoundingClientRect().bottom -
              bottomNavNode.getBoundingClientRect().top
            );
          }),
        { timeout: 5_000 }
      )
      .toBeLessThanOrEqual(0);
    await expect(viewport).toBeVisible();
    await expect(bottomNav).toBeVisible();
  });

  test("mobile balances list clears the fixed bottom nav @regression", async ({ page }) => {
    await visitRoute(page, spectateRoute);

    await dispatchMany(page, [
      [":actions/select-trade-mobile-surface", ":chart"],
      [":actions/select-account-info-tab", ":balances"]
    ]);
    await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });

    const viewport = page.locator("[data-role='balances-mobile-cards-viewport']");
    const lastCard = page.locator(
      "[data-role='balances-mobile-cards-viewport'] [data-role^='mobile-balance-card-']"
    ).last();
    const bottomNav = page.locator("[data-role='mobile-bottom-nav']");

    await expect(lastCard).toBeVisible({ timeout: 15_000 });
    const cardCount = await page.evaluate(() =>
      document.querySelectorAll(
        "[data-role='balances-mobile-cards-viewport'] [data-role^='mobile-balance-card-']"
      ).length
    );

    expect(cardCount).toBeGreaterThan(0);
    await expect
      .poll(
        () =>
          page.evaluate(() => {
            const viewportNode = document.querySelector(
              "[data-role='balances-mobile-cards-viewport']"
            );
            const cards = Array.from(
              document.querySelectorAll(
                "[data-role='balances-mobile-cards-viewport'] [data-role^='mobile-balance-card-']"
              )
            );
            const lastCardNode = cards.at(-1);
            const bottomNavNode = document.querySelector("[data-role='mobile-bottom-nav']");

            if (!viewportNode || !lastCardNode || !bottomNavNode) {
              throw new Error("required mobile balances nodes missing");
            }

            viewportNode.scrollTop = viewportNode.scrollHeight;
            return (
              lastCardNode.getBoundingClientRect().bottom -
              bottomNavNode.getBoundingClientRect().top
            );
          }),
        { timeout: 5_000 }
      )
      .toBeLessThanOrEqual(0);
    await expect(viewport).toBeVisible();
    await expect(bottomNav).toBeVisible();
  });
});

test("trade market statistics stay readable across governed viewport widths @regression", async ({ page }) => {
  for (const viewport of [
    { width: 375, height: 844, layout: "mobile" },
    { width: 768, height: 900, layout: "mobile" },
    { width: 1280, height: 900, layout: "desktop" },
    { width: 1440, height: 900, layout: "desktop" }
  ]) {
    await test.step(`${viewport.width}px ${viewport.layout}`, async () => {
      await page.setViewportSize(viewport);
      await visitRoute(page, "/trade");
      await seedTradeMarketStats(page);
      await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });

      if (viewport.layout === "mobile") {
        const toggle = page.locator("[data-role='trade-mobile-asset-details-toggle']");
        await expect(toggle).toBeVisible();
        await toggle.click();

        const details = page.locator("[data-role='trade-mobile-asset-details-panel']");
        await expect(details).toBeVisible();
        await expect(details).toContainText("Mark / Oracle");
        await expect(details).toContainText("24h Volume");
        await expect(details).toContainText("Open Interest");
        await expect(details).toContainText("Funding / Countdown");
        expect(
          await readCellTextOverflow(
            page,
            "[data-role='trade-mobile-asset-details-panel'] > div"
          )
        ).toEqual([]);
      } else {
        const legacyStats = page.locator(".asset-strip-row .asset-stat-cell");
        await expect(legacyStats).toHaveCount(6);
        expect(await readCellTextOverflow(page, ".asset-strip-row .asset-stat-cell")).toEqual([]);

        const statsScroll = page.locator("[data-role='active-asset-statistics-scroll']");
        const stats = statsScroll.locator("[data-role='active-asset-stat-cell']");
        await expect(statsScroll).toBeVisible();
        await expect(statsScroll).toHaveAttribute("tabindex", "0");
        await statsScroll.focus();
        await expect(statsScroll).toBeFocused();
        await expect(stats).toHaveCount(6);
        await expect(stats.nth(0)).toContainText("Mark");
        await expect(stats.nth(1)).toContainText("Oracle");
        await expect(stats.nth(2)).toContainText("24h Change");
        await expect(stats.nth(3)).toContainText("24h Volume");
        await expect(stats.nth(4)).toContainText("Open Interest");
        await expect(stats.nth(5)).toContainText("Funding / Countdown");
        expect(
          await readCellTextOverflow(page, "[data-role='active-asset-stat-cell']")
        ).toEqual([]);
        expect(
          await readAdjacentCellOverlap(page, "[data-role='active-asset-stat-cell']")
        ).toEqual([]);

        if (viewport.width === 1280) {
          const geometry = await statsScroll.evaluate((scroll) => {
            scroll.scrollLeft = scroll.scrollWidth;
            return {
              clientWidth: scroll.clientWidth,
              scrollWidth: scroll.scrollWidth
            };
          });
          const [scrollBox, fundingBox] = await Promise.all([
            statsScroll.boundingBox(),
            stats.nth(5).boundingBox()
          ]);

          expect(geometry.scrollWidth).toBeGreaterThan(geometry.clientWidth);
          expect(scrollBox).not.toBeNull();
          expect(fundingBox).not.toBeNull();
          expect(fundingBox.x + fundingBox.width).toBeLessThanOrEqual(scrollBox.x + scrollBox.width + 1);
        }
      }
    });
  }
});
