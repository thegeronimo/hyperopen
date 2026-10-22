import { expect, test } from "../support/guarded_test.mjs";
import { mobileViewport, oracle, waitForDebugBridge, waitForIdle } from "../support/hyperopen.mjs";

// No live fills exist for this address, so the websocket snapshot adds nothing
// to the fixture rows served over REST.
const SPECTATE_ADDRESS = "0x00000000000000000000000000000000c4a7f111";
const DAY_MS = 86400000;

function dayStart(ms) {
  return Math.floor(ms / DAY_MS) * DAY_MS;
}

/** Daily BTC candles around 60k covering the requested span. */
function candleSnapshot(req, maxDays = 400) {
  const end = Number(req.endTime) || Date.now();
  const start = Math.max(Number(req.startTime) || end - maxDays * DAY_MS, end - maxDays * DAY_MS);
  const out = [];
  for (let t = dayStart(start); t <= end; t += DAY_MS) {
    const price = 60000 + Math.sin(t / DAY_MS / 9) * 1500;
    out.push({
      t,
      T: t + DAY_MS - 1,
      s: req.coin || "BTC",
      i: req.interval || "1d",
      o: String(price),
      c: String(price + 200),
      h: String(price + 600),
      l: String(price - 600),
      v: "1000",
      n: 100
    });
  }
  return out;
}

/**
 * Two buys inside one daily candle five days ago (one group, count 2) and a
 * closing sell two days ago, newest first like `userFills`.
 */
function userFills(now) {
  const buyDay = dayStart(now) - 5 * DAY_MS + 10 * 3600000;
  const sellDay = dayStart(now) - 2 * DAY_MS + 14 * 3600000;
  const fill = (overrides) => ({
    coin: "BTC",
    crossed: true,
    fee: "0.5",
    feeToken: "USDC",
    hash: "0x" + "0".repeat(64),
    closedPnl: "0",
    ...overrides
  });
  return [
    fill({ time: sellDay, px: "61000", sz: "0.3", side: "A", dir: "Close Long",
           startPosition: "0.3", closedPnl: "300", oid: 3, tid: 3003 }),
    fill({ time: buyDay + 60000, px: "60100", sz: "0.2", side: "B", dir: "Open Long",
           startPosition: "0.1", oid: 2, tid: 2002, crossed: false }),
    fill({ time: buyDay, px: "59900", sz: "0.1", side: "B", dir: "Open Long",
           startPosition: "0", oid: 1, tid: 1001 })
  ];
}

async function stubChartTradesInfo(page) {
  const now = Date.now();
  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const json = (body) =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (payload?.type === "candleSnapshot") {
      await json(candleSnapshot(payload.req || {}));
      return;
    }
    if (payload?.type === "userFills") {
      await json(userFills(now));
      return;
    }
    // older chart windows: nothing before the fixture fills
    if (payload?.type === "userFillsByTime") {
      await json([]);
      return;
    }
    await route.continue();
  });
}

async function chartTrades(page) {
  return oracle(page, "chart-trades");
}

async function badgeFor(page, idSuffix) {
  await expect
    .poll(async () => (await chartTrades(page)).badges.length, { timeout: 20_000 })
    .toBe(2);
  const { badges } = await chartTrades(page);
  const badge = badges.find((b) => b.id.endsWith(idSuffix));
  expect(badge, `badge ending in ${idSuffix}`).toBeTruthy();
  return badge;
}

test.describe("trades on chart", () => {
  test.beforeEach(async ({ page }) => {
    await stubChartTradesInfo(page);
    await page.goto(`/trade/BTC?spectate=${SPECTATE_ADDRESS}`, { waitUntil: "commit" });
    await waitForDebugBridge(page);
    await waitForIdle(page, { quietMs: 300, timeoutMs: 10_000, pollMs: 50 });
  });

  test("hovering a badge shows the fill card and moving away hides it @regression", async ({ page }) => {
    const card = page.locator("[data-role='chart-trade-card']");
    const buy = await badgeFor(page, "-buy");

    await page.mouse.move(buy.x, buy.y);
    await expect(card).toBeVisible();
    await expect(card).toContainText("Bought 0.3 BTC");
    await expect(card).toContainText("Maker");
    await expect(card).toContainText("Taker");
    await expect(card).toContainText("Long 0.3 BTC");
    await expect(card).toContainText("Click the marker to pin");

    await page.mouse.move(buy.x, buy.y - 200);
    await expect(card).toBeHidden();
  });

  test("clicking a badge pins the card until Escape, and its link opens Trade History @regression", async ({ page }) => {
    const card = page.locator("[data-role='chart-trade-card']");
    const sell = await badgeFor(page, "-sell");

    await page.mouse.click(sell.x, sell.y);
    await expect(card).toBeVisible();
    await page.mouse.move(sell.x, sell.y + 200);
    await expect(card).toBeVisible();
    await expect(card).toContainText("Sold 0.3 BTC");
    await expect(card).toContainText("Realized PnL");
    await expect(card).toContainText("+$300.00");
    await expect(card).toContainText("Flat");
    await expect(card).not.toContainText("Click the marker to pin");
    expect((await chartTrades(page)).pinnedId).toBe(sell.id);

    await page.keyboard.press("Escape");
    await expect(card).toBeHidden();

    // lightweight-charts drops the second click of a double-click, so clicking
    // the same badge again needs to fall outside its double-click window
    await page.waitForTimeout(600);
    await page.mouse.click(sell.x, sell.y);
    await expect(card).toBeVisible();
    await card.locator("[data-role='chart-trade-card-history-link']").click();
    await waitForIdle(page);
    const tab = await page.evaluate(() => {
      const c = globalThis.cljs.core;
      const path = c.PersistentVector.fromArray(
        [c.keyword("account-info"), c.keyword("selected-tab")],
        true
      );
      return String(c.get_in(c.deref(globalThis.hyperopen.system.store), path));
    });
    expect(tab).toBe(":trade-history");
  });

  test("the My trades toggle removes and restores the markers @regression", async ({ page }) => {
    await badgeFor(page, "-buy");
    const toggle = page.locator("[data-role='chart-toolbar-trades-toggle']");
    await expect(toggle).toHaveAttribute("aria-pressed", "true");

    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-pressed", "false");
    await expect.poll(async () => (await chartTrades(page)).present).toBe(false);
    await expect(page.locator("[data-role='chart-trade-card']")).toHaveCount(0);

    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-pressed", "true");
    await expect.poll(async () => (await chartTrades(page)).badges.length).toBe(2);
  });
});

test.describe("trades on chart at phone width", () => {
  test.use(mobileViewport);

  test("a tapped badge opens a card that fits the visible chart and closes with its button @regression", async ({ page }) => {
    await stubChartTradesInfo(page);
    await page.goto(`/trade/BTC?spectate=${SPECTATE_ADDRESS}`, { waitUntil: "commit" });
    await waitForDebugBridge(page);
    await waitForIdle(page, { quietMs: 300, timeoutMs: 10_000, pollMs: 50 });
    const card = page.locator("[data-role='chart-trade-card']");
    const sell = await badgeFor(page, "-sell");

    // touch taps carry no hovered object id; the layer hit-tests them itself
    await page.touchscreen.tap(sell.x, sell.y);
    await expect(card).toBeVisible();
    await expect(card).toContainText("Sold 0.3 BTC");

    // the phone layout clips the chart container: the card must fit what is
    // on screen so its lower rows stay reachable
    const fit = await card.evaluate((node) => {
      let clipBottom = Infinity;
      for (let el = node.parentElement; el; el = el.parentElement) {
        if (getComputedStyle(el).overflowY !== "visible") {
          clipBottom = Math.min(clipBottom, el.getBoundingClientRect().bottom);
        }
      }
      return { cardBottom: node.getBoundingClientRect().bottom, clipBottom };
    });
    expect(fit.cardBottom).toBeLessThanOrEqual(fit.clipBottom + 1);

    await card.locator("[data-role='chart-trade-card-close']").click();
    await expect(card).toBeHidden();
  });
});

test.describe("older chart fills follow the visible range", () => {
  test("a daily chart pages back only near what is on screen, and further on scroll @regression", async ({ page }) => {
    const now = Date.now();
    // 1200 fills over the last 12 days: a REST page too full to be complete,
    // so older history may exist; four years of daily candles behind them
    const recentFills = Array.from({ length: 1200 }, (_, i) => ({
      coin: "BTC",
      time: now - i * 864000,
      px: "60000",
      sz: "0.01",
      side: i % 2 ? "A" : "B",
      dir: i % 2 ? "Close Long" : "Open Long",
      startPosition: i % 2 ? "0.01" : "0",
      closedPnl: "0",
      crossed: true,
      fee: "0.1",
      feeToken: "USDC",
      hash: "0x" + "0".repeat(64),
      oid: i,
      tid: 10_000 + i
    }));
    const olderRequests = [];
    await page.route("https://api.hyperliquid.xyz/info", async (route) => {
      const payload = JSON.parse(route.request().postData() || "{}");
      const json = (body) =>
        route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
      if (payload?.type === "candleSnapshot") {
        const req = payload.req || {};
        // four years of candles whatever the request asks for, as after the
        // trader scrolled far back once and returned to the present
        await json(candleSnapshot({ ...req, startTime: now - 1500 * DAY_MS, endTime: now }, 1500));
        return;
      }
      if (payload?.type === "userFills") {
        await json(recentFills);
        return;
      }
      if (payload?.type === "userFillsByTime") {
        olderRequests.push(payload);
        await json([]);
        return;
      }
      await route.continue();
    });
    await page.addInitScript(() => localStorage.setItem("chart-timeframe", "1d"));
    await page.goto(`/trade/BTC?spectate=${SPECTATE_ADDRESS}`, { waitUntil: "commit" });
    await waitForDebugBridge(page);
    await expect.poll(async () => (await chartTrades(page)).groupCount, { timeout: 20_000 }).toBeGreaterThan(0);
    // let the page-by-page requests settle: the count stops changing
    const settled = async () => {
      let last = -1;
      while (last !== olderRequests.length) {
        last = olderRequests.length;
        await page.waitForTimeout(1_500);
      }
    };
    await settled();

    const coveredFrom = () =>
      page.evaluate(() => {
        const c = globalThis.cljs.core;
        const path = c.PersistentVector.fromArray(
          [c.keyword("chart-trades"), c.keyword("covered-from-ms")],
          true
        );
        return c.get_in(c.deref(globalThis.hyperopen.system.store), path);
      });
    const firstCount = olderRequests.length;
    const firstCovered = await coveredFrom();
    // the candles reach 1500 days back; paging stops about one screen past
    // the visible range (eager paging went to the 2023 floor: 18 requests)
    expect(firstCount).toBeGreaterThan(0);
    expect(firstCovered).toBeGreaterThan(now - 1000 * DAY_MS);

    // drag the chart to the right a few times to look further back
    const pane = page.locator("[data-role='trading-chart-canvas']").first();
    const box = await pane.boundingBox();
    for (let i = 0; i < 4; i += 1) {
      await page.mouse.move(box.x + box.width * 0.2, box.y + box.height * 0.4);
      await page.mouse.down();
      await page.mouse.move(box.x + box.width * 0.8, box.y + box.height * 0.4, { steps: 8 });
      await page.mouse.up();
    }
    await expect.poll(async () => olderRequests.length, { timeout: 10_000 }).toBeGreaterThan(firstCount);
    await settled();
    expect(await coveredFrom()).toBeLessThan(firstCovered);
  });
});
