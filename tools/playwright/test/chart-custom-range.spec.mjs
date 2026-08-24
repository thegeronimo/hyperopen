import { expect, test } from "@playwright/test";
import { visitRoute, waitForDebugBridge, waitForIdle } from "../support/hyperopen.mjs";

const SPECTATE_ADDRESS = "0x162cc7c861ebd0c06b3d72319201150482518185";

/**
 * Two years of daily account-value history under the all-time bucket, which is
 * the series the context strip plots. Seeded directly into the store so the test
 * never depends on live market data.
 */
async function seedPortfolioHistory(page) {
  await page.evaluate((spectateAddress) => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map(kw), true);
    const row = (timeMs, value) => c.PersistentVector.fromArray([timeMs, value], true);
    const day = 86400000;
    const end = Date.UTC(2026, 7, 24);
    const accountRows = [];
    const pnlRows = [];
    let value = 48250;
    for (let i = 730; i >= 0; i -= 1) {
      value = value * (1 + Math.sin(i / 17) * 0.012 + 0.0009);
      const timeMs = end - i * day;
      accountRows.push(row(timeMs, Math.round(value)));
      pnlRows.push(row(timeMs, Math.round(value - 48250)));
    }
    const rows = (items) => c.PersistentVector.fromArray(items, true);
    const summary = c.PersistentArrayMap.fromArray(
      [kw("accountValueHistory"), rows(accountRows), kw("pnlHistory"), rows(pnlRows)],
      true
    );
    const summaryByKey = c.PersistentArrayMap.fromArray([kw("all-time"), summary], true);
    const store = globalThis.hyperopen.system.store;
    let next = c.deref(store);
    // An effective account must exist before account-derived surfaces may be
    // held, or the app-state lifecycle invariant rejects the write.
    next = c.assoc_in(next, path("account-context", "spectate-mode", "active?"), true);
    next = c.assoc_in(next, path("account-context", "spectate-mode", "address"), spectateAddress);
    next = c.assoc_in(next, path("portfolio", "summary-by-key"), summaryByKey);
    next = c.assoc_in(next, path("portfolio-ui", "summary-time-range"), kw("month"));
    next = c.assoc_in(next, path("portfolio-ui", "chart-tab"), kw("account-value"));
    c.reset_BANG_(store, next);
  }, SPECTATE_ADDRESS);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 6_000, pollMs: 50 });
}

/**
 * Load a route WITH its query string applied.
 *
 * `visitRoute` deliberately navigates by pathname only, so it cannot exercise a
 * shared link — the query has to arrive on the initial load for
 * `apply-route-query-state` to see it.
 */
async function visitSharedUrl(page, url) {
  await page.goto(url, { waitUntil: "commit" });
  await waitForDebugBridge(page);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });
}

/**
 * Sweep a window on the strip. The bounding box is re-captured immediately
 * before every drag: an intervening click or render can scroll the page, and a
 * drag against a stale rect silently misses the element and looks green.
 */
async function dragStrip(page, trackRole, fromFraction, toFraction) {
  const track = page.locator(`[data-role='${trackRole}']`);
  await track.scrollIntoViewIfNeeded();
  const box = await track.boundingBox();
  const y = box.y + box.height / 2;
  await page.mouse.move(box.x + box.width * fromFraction, y);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * toFraction, y, { steps: 10 });
  await page.mouse.up();
  await waitForIdle(page);
  return box;
}

test.describe("custom chart range (design 1c)", () => {
  test("portfolio: Custom… reveals a strip whose drag drives the chip, stats and URL", async ({
    page
  }) => {
    await visitRoute(page, "/portfolio");
    await seedPortfolioHistory(page);

    const chip = page.locator("[data-role='portfolio-summary-time-range-selector']");
    const trigger = chip.locator("[data-role='portfolio-summary-time-range-selector-trigger']");
    const strip = page.locator("[data-role='portfolio-chart-range-strip']");

    await expect(trigger).toHaveText("30D");
    // The strip is always in the DOM and hides itself — never a `when`, which
    // would leave a nil hole between the chart's nil-able trailing siblings.
    await expect(strip).toHaveClass(/hidden/);

    await trigger.click();
    await waitForIdle(page);
    await page.locator("[data-role='portfolio-summary-time-range-selector-option-custom']").click();
    await waitForIdle(page);

    await expect(strip).not.toHaveClass(/hidden/);
    await expect(page.locator("[data-role='portfolio-chart-range-strip-track'] polyline").first())
      .toHaveCount(1);

    // Sweeping left-to-right across the middle of the all-time strip.
    await dragStrip(page, "portfolio-chart-range-strip-track", 0.2, 0.65);

    // The chip now reads the chosen span rather than a preset name.
    const chipText = await trigger.innerText();
    expect(chipText).toMatch(/–/);
    expect(chipText).not.toBe("30D");
    await expect(page.locator("[data-role='portfolio-chart-range-strip-draft']"))
      .toContainText("D");

    // ...and the window is shareable.
    expect(page.url()).toMatch(/from=\d{4}-\d{2}-\d{2}/);
    expect(page.url()).toMatch(/to=\d{4}-\d{2}-\d{2}/);

    // Done collapses the strip but keeps the window applied.
    await page.locator("[data-role='portfolio-chart-range-strip-done']").click();
    await waitForIdle(page);
    await expect(strip).toHaveClass(/hidden/);
    expect(await trigger.innerText()).toBe(chipText);

    // Clearing restores the preset that was never overwritten, and drops the
    // bounds from the URL.
    await page.locator("[data-role='portfolio-summary-time-range-selector-clear']").click();
    await waitForIdle(page);
    await expect(trigger).toHaveText("30D");
    expect(page.url()).not.toMatch(/from=\d/);
  });

  test("portfolio: a shared custom-range URL restores the window without opening the editor", async ({
    page
  }) => {
    await visitSharedUrl(page, "/portfolio?range=30d&from=2026-03-03&to=2026-06-12");
    await seedPortfolioHistory(page);

    const trigger = page.locator("[data-role='portfolio-summary-time-range-selector-trigger']");
    const strip = page.locator("[data-role='portfolio-chart-range-strip']");

    await expect(trigger).toContainText("Mar 3");
    await expect(trigger).toContainText("Jun 12");
    await expect(strip).toHaveClass(/hidden/);
  });

  test("portfolio: dragging a strip handle nudges one edge and leaves the other alone", async ({
    page
  }) => {
    await visitSharedUrl(page, "/portfolio?range=30d&from=2026-03-03&to=2026-06-12");
    await seedPortfolioHistory(page);

    const trigger = page.locator("[data-role='portfolio-summary-time-range-selector-trigger']");
    await trigger.click();
    await waitForIdle(page);
    await page.locator("[data-role='portfolio-summary-time-range-selector-option-custom']").click();
    await waitForIdle(page);

    const before = await trigger.innerText();
    expect(before).toMatch(/Jun 12/);

    // The handles must advertise themselves as draggable edges.
    const cursorAt = (x, y) =>
      page.evaluate(
        ({ px, py }) => {
          const el = document.elementFromPoint(px, py);
          return el ? getComputedStyle(el).cursor : null;
        },
        { px: x, py: y }
      );
    const track0 = await page.locator("[data-role='portfolio-chart-range-strip-track']").boundingBox();
    const startHandle0 = await page
      .locator("[data-role='portfolio-chart-range-strip'] .ho-range-strip__handle")
      .first()
      .boundingBox();
    const midY = track0.y + track0.height / 2;
    expect(await cursorAt(startHandle0.x + startHandle0.width / 2, midY)).toBe("ew-resize");
    // ...including the invisible margin where a grab still works.
    expect(await cursorAt(startHandle0.x - 4, midY)).toBe("ew-resize");
    // Open track still reads as "sweep a new range", not "resize an edge".
    expect(await cursorAt(track0.x + track0.width * 0.5, midY)).toBe("crosshair");

    // Grab the END handle where it sits and drag it further right.
    const handles = page.locator("[data-role='portfolio-chart-range-strip'] .ho-range-strip__handle");
    const endHandle = handles.nth(1);
    const handleBox = await endHandle.boundingBox();
    const track = page.locator("[data-role='portfolio-chart-range-strip-track']");
    const trackBox = await track.boundingBox();
    await page.mouse.move(handleBox.x + handleBox.width / 2, trackBox.y + trackBox.height / 2);
    await page.mouse.down();
    await page.mouse.move(trackBox.x + trackBox.width * 0.95, trackBox.y + trackBox.height / 2, {
      steps: 10
    });
    await page.mouse.up();
    await waitForIdle(page);

    const after = await trigger.innerText();
    expect(after).not.toBe(before);
    // The start edge is untouched by an end-handle drag.
    expect(after).toMatch(/Mar 3/);
  });
});

// --- vault detail -----------------------------------------------------------------------------

const VAULT_ADDRESS = "0x1234567890abcdef1234567890abcdef12345678";
const LEADER_ADDRESS = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd";

function vaultHistory() {
  const day = 86400000;
  const end = Date.UTC(2026, 7, 24);
  const accountValueHistory = [];
  const pnlHistory = [];
  let value = 48250;
  for (let i = 730; i >= 0; i -= 1) {
    value = value * (1 + Math.sin(i / 17) * 0.012 + 0.0009);
    const timeMs = end - i * day;
    accountValueHistory.push([timeMs, String(Math.round(value))]);
    pnlHistory.push([timeMs, String(Math.round(value - 48250))]);
  }
  return { accountValueHistory, pnlHistory };
}

function vaultDetailsFixture() {
  const history = vaultHistory();
  return {
    name: "Custom Range Vault",
    vaultAddress: VAULT_ADDRESS,
    leader: LEADER_ADDRESS,
    description: "Deterministic custom-range fixture",
    tvl: "48250",
    apr: "0.12",
    portfolio: [["allTime", history], ["month", history]],
    followers: [],
    relationship: { type: "normal" },
    allowDeposits: false,
    alwaysCloseOnWithdraw: false
  };
}

function candleSnapshot(coin, interval) {
  // Daily candles spanning the same two years as the vault history, so the
  // benchmark actually resolves and the metrics panel's loading overlay clears.
  const day = 86400000;
  const end = Date.UTC(2026, 7, 24);
  const out = [];
  let price = 60000;
  for (let i = 730; i >= 0; i -= 1) {
    price = price * (1 + Math.sin(i / 23) * 0.01 + 0.0004);
    const t = end - i * day;
    out.push({
      t,
      T: t + day - 1,
      s: coin,
      i: interval || "1d",
      o: String(price),
      c: String(price),
      h: String(price * 1.01),
      l: String(price * 0.99),
      v: "1000",
      n: 100
    });
  }
  return out;
}

async function stubVaultDetail(page) {
  await page.route("https://stats-data.hyperliquid.xyz/Mainnet/vaults", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          apr: "0.12",
          summary: {
            name: "Custom Range Vault",
            vaultAddress: VAULT_ADDRESS,
            leader: LEADER_ADDRESS,
            tvl: "48250",
            isClosed: false,
            relationship: { type: "normal" },
            createTimeMillis: String(Date.UTC(2024, 7, 24))
          }
        }
      ])
    });
  });
  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    if (payload?.type === "vaultDetails") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(vaultDetailsFixture())
      });
      return;
    }
    if (payload?.type === "candleSnapshot") {
      const req = payload?.req || {};
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(candleSnapshot(req.coin || "BTC", req.interval))
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([])
    });
  });
}

test.describe("custom chart range (design 1c) — vault detail", () => {
  test("vault: Custom… reveals a strip whose drag drives the chip and URL", async ({ page }) => {
    await stubVaultDetail(page);
    await visitRoute(page, `/vaults/${VAULT_ADDRESS}`);
    await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });

    const trigger = page.locator("[data-role='vault-detail-chart-timeframe-trigger']");
    const strip = page.locator("[data-role='vault-detail-chart-range-strip']");

    await expect(trigger).toBeVisible();
    await expect(strip).toHaveClass(/hidden/);

    await trigger.click();
    await waitForIdle(page);
    await page.locator("[data-role='vault-detail-chart-timeframe-option-custom']").click();
    await waitForIdle(page);
    await expect(strip).not.toHaveClass(/hidden/);

    const before = await trigger.innerText();
    await dragStrip(page, "vault-detail-chart-range-strip-track", 0.15, 0.7);

    const after = await trigger.innerText();
    expect(after).not.toBe(before);
    expect(after).toMatch(/–/);
    expect(page.url()).toMatch(/from=\d{4}-\d{2}-\d{2}/);
    expect(page.url()).toMatch(/to=\d{4}-\d{2}-\d{2}/);

    // Done collapses the strip and keeps the window.
    await page.locator("[data-role='vault-detail-chart-range-strip-done']").click();
    await waitForIdle(page);
    await expect(strip).toHaveClass(/hidden/);
    expect(await trigger.innerText()).toBe(after);

    // Clearing restores the preset and drops the bounds from the URL.
    await page.locator("[data-role='vault-detail-chart-timeframe-clear']").click();
    await waitForIdle(page);
    expect(await trigger.innerText()).not.toMatch(/–/);
    expect(page.url()).not.toMatch(/from=\d/);
  });

  test("vault: the tearsheet Range chip follows the custom window and can set one itself", async ({
    page
  }) => {
    await stubVaultDetail(page);
    await visitRoute(page, `/vaults/${VAULT_ADDRESS}`);
    await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });

    const chartTrigger = page.locator("[data-role='vault-detail-chart-timeframe-trigger']");
    const metricsTrigger = page.locator(
      "[data-role='vault-detail-performance-metrics-timeframe-trigger']"
    );
    const chartStrip = page.locator("[data-role='vault-detail-chart-range-strip']");
    const metricsStrip = page.locator("[data-role='vault-detail-performance-metrics-range-strip']");

    await expect(metricsTrigger).toBeVisible();
    const presetLabel = await metricsTrigger.innerText();
    expect(presetLabel).not.toMatch(/–/);

    // Set a custom window from the CHART.
    await chartTrigger.click();
    await waitForIdle(page);
    await page.locator("[data-role='vault-detail-chart-timeframe-option-custom']").click();
    await waitForIdle(page);
    await dragStrip(page, "vault-detail-chart-range-strip-track", 0.15, 0.7);

    // The tearsheet must report the SAME window, not the preset underneath it.
    const chartLabel = await chartTrigger.innerText();
    const metricsLabel = await metricsTrigger.innerText();
    expect(metricsLabel).toMatch(/–/);
    expect(metricsLabel.replace(/^Range\s*/, "")).toBe(chartLabel.replace(/^Range\s*/, ""));

    // The chip grew from "Range 30D" to a full span, so its column has to grow
    // with it — otherwise the chip and its clear control sit on top of the first
    // benchmark column header.
    const chipBox = await page
      .locator("[data-role='vault-detail-performance-metrics-timeframe-menu']")
      .boundingBox();
    const clearBox = await page
      .locator("[data-role='vault-detail-performance-metrics-timeframe-clear']")
      .boundingBox();
    const benchBox = await page
      .locator("[data-role='vault-detail-performance-metrics-benchmark-label']")
      .boundingBox();
    expect(chipBox.x + chipBox.width).toBeLessThan(benchBox.x);
    expect(clearBox.x + clearBox.width).toBeLessThan(benchBox.x);
    // ...and the label stays on one line rather than wrapping inside the chip.
    expect(chipBox.height).toBeLessThan(60);

    // The tearsheet can set one itself, and its strip opens in place rather than
    // on the chart card the trader may have scrolled past.
    await page.locator("[data-role='vault-detail-chart-range-strip-done']").click();
    await waitForIdle(page);
    await metricsTrigger.click();
    await waitForIdle(page);
    const metricsCustomRow = page.locator(
      "[data-role='vault-detail-performance-metrics-timeframe-option-custom']"
    );
    await expect(metricsCustomRow).toHaveCount(1);
    await metricsCustomRow.click();
    await waitForIdle(page);
    await expect(metricsStrip).not.toHaveClass(/hidden/);
    await expect(chartStrip).toHaveClass(/hidden/);

    // Dragging the tearsheet's own strip moves both chips together.
    await dragStrip(page, "vault-detail-performance-metrics-range-strip-track", 0.3, 0.85);
    const afterMetrics = await metricsTrigger.innerText();
    const afterChart = await chartTrigger.innerText();
    expect(afterMetrics).not.toBe(metricsLabel);
    expect(afterMetrics.replace(/^Range\s*/, "")).toBe(afterChart.replace(/^Range\s*/, ""));

    // Clearing from the tearsheet restores the preset on both.
    await page.locator("[data-role='vault-detail-performance-metrics-timeframe-clear']").click();
    await waitForIdle(page);
    expect(await metricsTrigger.innerText()).not.toMatch(/–/);
    expect(await chartTrigger.innerText()).not.toMatch(/–/);
  });
});
