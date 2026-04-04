import { expect, test } from "@playwright/test";
import {
  debugCall,
  dispatch,
  expectOracle,
  oracle,
  sourceRectForLocator,
  visitRoute,
  waitForIdle
} from "../support/hyperopen.mjs";

function buildCachedAssetSelectorMarkets(count = 240) {
  const baseMarkets = [
    {
      key: "perp:BTC",
      coin: "BTC",
      symbol: "BTC",
      base: "BTC",
      "market-type": "perp",
      category: "crypto",
      volume24h: 2_900_000_000,
      openInterest: 1_900_000_000,
      mark: 66_880,
      markRaw: 66_880,
      change24h: -1_528,
      change24hPct: -2.23,
      fundingRate: -0.00001,
      "cache-order": 0
    },
    {
      key: "perp:ETH",
      coin: "ETH",
      symbol: "ETH",
      base: "ETH",
      "market-type": "perp",
      category: "crypto",
      volume24h: 1_800_000_000,
      openInterest: 1_200_000_000,
      mark: 3_410,
      markRaw: 3_410,
      change24h: 84,
      change24hPct: 2.52,
      fundingRate: 0.00012,
      "cache-order": 1
    },
    {
      key: "perp:SOL",
      coin: "SOL",
      symbol: "SOL",
      base: "SOL",
      "market-type": "perp",
      category: "crypto",
      volume24h: 950_000_000,
      openInterest: 620_000_000,
      mark: 168,
      markRaw: 168,
      change24h: 5.4,
      change24hPct: 3.31,
      fundingRate: 0.00009,
      "cache-order": 2
    }
  ];

  const generatedMarkets = Array.from({ length: Math.max(0, count - baseMarkets.length) }, (_, index) => {
    const ordinal = index + 1;
    return {
      key: `perp:TST${ordinal}`,
      coin: `TST${ordinal}`,
      symbol: `TST${ordinal}`,
      base: `TST${ordinal}`,
      "market-type": "perp",
      category: "crypto",
      volume24h: 500_000_000 - ordinal,
      openInterest: 250_000_000 - ordinal,
      mark: 100 + ordinal,
      markRaw: 100 + ordinal,
      change24h: ordinal / 10,
      change24hPct: ordinal / 100,
      fundingRate: 0.00005,
      "cache-order": baseMarkets.length + index
    };
  });

  return [...baseMarkets, ...generatedMarkets];
}

async function seedAssetSelectorMarketsCache(page, count = 240) {
  const rows = buildCachedAssetSelectorMarkets(count);
  await page.addInitScript((cacheRows) => {
    window.localStorage.setItem(
      "asset-selector-markets-cache",
      JSON.stringify({
        id: "asset-selector-markets-cache",
        version: 1,
        "saved-at-ms": Date.now(),
        rows: cacheRows
      })
    );
  }, rows);
}

test("asset selector opens and selects ETH @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "asset-selector", {
    visibleDropdown: "asset-selector",
    desktopPresent: true
  });

  await dispatch(page, [":actions/select-asset", "ETH"]);
  await waitForIdle(page, { quietMs: 300, timeoutMs: 7_000, pollMs: 50 });
  await expectOracle(page, "asset-selector", {
    visibleDropdown: null,
    activeAsset: "ETH"
  });
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/select-asset",
      covered: true,
      projectionBeforeHeavy: true,
      phaseOrderValid: true,
      duplicateHeavyEffectIds: []
    },
    { args: { actionId: ":actions/select-asset" } }
  );
});

test("asset selector focuses search input and keyboard-navigates rows @regression", async ({ page }) => {
  await seedAssetSelectorMarketsCache(page);
  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "asset-selector", {
    visibleDropdown: "asset-selector",
    desktopPresent: true
  });

  const searchInput = page.locator('[aria-label="Search assets"]');
  const assetRows = page.locator('[data-role="asset-selector-row"]');
  const highlightedRow = () =>
    page.locator('[data-role="asset-selector-row"][data-row-state="highlighted"]').first();
  const highlightedSymbol = async () =>
    (await highlightedRow().locator(".truncate").first().textContent())?.trim();
  const firstRowSymbol = async () =>
    (await assetRows.first().locator(".truncate").first().textContent())?.trim();

  await expect(searchInput).toBeFocused();
  await expect.poll(async () => await assetRows.count(), { timeout: 10_000 }).toBeGreaterThan(1);
  const initialRowSymbol = await firstRowSymbol();

  await page.keyboard.press("ArrowDown");
  await expect(highlightedRow()).toBeVisible();
  await expect.poll(highlightedSymbol, { timeout: 5_000 }).not.toBe(initialRowSymbol);
  const firstHighlightedSymbol = await highlightedSymbol();

  await page.keyboard.press("ArrowDown");
  await expect(highlightedRow()).toBeVisible();
  const secondHighlightedSymbol = await highlightedSymbol();
  expect(secondHighlightedSymbol).not.toEqual(firstHighlightedSymbol);

  await page.keyboard.press("ArrowUp");
  await expect
    .poll(highlightedSymbol, {
      timeout: 5_000
    })
    .toBe(firstHighlightedSymbol);

  await page.keyboard.press("Enter");
  await waitForIdle(page, { quietMs: 300, timeoutMs: 7_000, pollMs: 50 });
  await expectOracle(page, "asset-selector", {
    visibleDropdown: null,
    activeAsset: "ETH"
  });
});

test("trade route preserves core accessibility affordances @regression", async ({ page }) => {
  await page.goto("/trade", { waitUntil: "commit" });

  await expect(page.locator('[data-parity-id="trade-root"]')).toBeVisible();
  await expect(page.locator('main[data-parity-id="app-main"]')).toHaveCount(1);
  await expect(page.getByRole("button", { name: "Connect Wallet" })).toBeVisible();

  const midButton = page.getByRole("button", { name: "Set order price to mid" });
  await expect(midButton).toBeVisible();
  const midButtonBox = await midButton.boundingBox();
  expect(midButtonBox?.width ?? 0).toBeGreaterThanOrEqual(24);
  expect(midButtonBox?.height ?? 0).toBeGreaterThanOrEqual(24);

  await expect(
    page.getByRole("slider", { name: "Order size percentage slider" })
  ).toBeVisible();
  await expect(
    page.getByRole("textbox", { name: "Order size percentage input" })
  ).toBeVisible();

  await expect(
    page.locator('button[aria-haspopup="listbox"]').filter({ hasText: "Cross" }).first()
  ).toHaveAttribute("aria-label", "Margin mode: Cross");
  await expect(
    page.locator('button[aria-haspopup="listbox"]').filter({ hasText: "USDC" }).first()
  ).toHaveAttribute("aria-label", "Size unit: USDC");
  await expect(
    page.locator('button[aria-haspopup="listbox"]').filter({ hasText: "GTC" }).first()
  ).toHaveAttribute("aria-label", "Time in force: GTC");
});

test("active asset icon promotes BTC into loaded-icons after probe load @regression", async ({ page }) => {
  await page.route("https://app.hyperliquid.xyz/coins/BTC.svg", async route => {
    await route.fulfill({
      status: 200,
      contentType: "image/svg+xml",
      body: "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\"><rect width=\"16\" height=\"16\" fill=\"#f7931a\"/></svg>"
    });
  });

  await visitRoute(page, "/trade");

  await expect
    .poll(async () => {
      const snapshot = await debugCall(page, "snapshot");
      return snapshot["app-state"]?.["asset-selector"]?.["loaded-icons"] || [];
    }, { timeout: 10_000 })
    .toContain("perp:BTC");

  await expect
    .poll(async () => {
      const snapshot = await debugCall(page, "snapshot");
      return snapshot["app-state"]?.["asset-selector"]?.["missing-icons"] || [];
    }, { timeout: 10_000 })
    .toEqual([]);
});

test("vault detail 429 retries stop after returning to trade @regression", async ({ page }) => {
  const vaultAddress = "0x61b1cf5c2d7c4bf6d5db14f36651b2242e7cba0a";
  let vaultDetailsRequests = 0;
  let vaultWebDataRequests = 0;

  await page.route("https://api.hyperliquid.xyz/info", async route => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestType = payload?.type;
    const requestVaultAddress = String(
      payload?.vaultAddress || payload?.user || ""
    ).toLowerCase();

    if (
      requestType === "vaultDetails" &&
      requestVaultAddress === vaultAddress
    ) {
      vaultDetailsRequests += 1;
      await route.fulfill({
        status: 429,
        contentType: "application/json",
        body: JSON.stringify({ error: "rate-limited" })
      });
      return;
    }

    if (
      requestType === "webData2" &&
      requestVaultAddress === vaultAddress
    ) {
      vaultWebDataRequests += 1;
      await route.fulfill({
        status: 429,
        contentType: "application/json",
        body: JSON.stringify({ error: "rate-limited" })
      });
      return;
    }

    await route.continue();
  });

  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/navigate", "/vaults", { "replace?": true }]);
  await waitForIdle(page);
  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "vaults-root" } }
  );

  await dispatch(page, [
    ":actions/navigate",
    `/vaults/${vaultAddress}`,
    { "replace?": true }
  ]);

  await expect
    .poll(() => vaultDetailsRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);
  await expect
    .poll(() => vaultWebDataRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);

  await dispatch(page, [":actions/navigate", "/trade", { "replace?": true }]);
  await waitForIdle(page);
  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "trade-root" } }
  );

  const detailsRequestsAfterLeave = vaultDetailsRequests;
  const webDataRequestsAfterLeave = vaultWebDataRequests;

  await page.waitForTimeout(1200);

  await expect(vaultDetailsRequests).toBe(detailsRequestsAfterLeave);
  await expect(vaultWebDataRequests).toBe(webDataRequestsAfterLeave);
});

test("vault detail hero TVL bootstraps from list metadata when vaultDetails omits tvl @regression", async ({ page }) => {
  const vaultAddress = "0x61b1cf5c2d7c4bf6d5db14f36651b2242e7cba0a";
  let vaultIndexRequests = 0;
  let vaultSummariesRequests = 0;

  await page.route("https://stats-data.hyperliquid.xyz/Mainnet/vaults", async route => {
    vaultIndexRequests += 1;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          apr: "0.12",
          summary: {
            name: "OnlyShorts",
            vaultAddress,
            leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            tvl: "321.5",
            isClosed: false,
            relationship: { type: "normal" },
            createTimeMillis: "1700"
          }
        }
      ])
    });
  });

  await page.route("https://api.hyperliquid.xyz/info", async route => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestType = payload?.type;
    const requestVaultAddress = String(
      payload?.vaultAddress || payload?.user || ""
    ).toLowerCase();

    if (requestType === "vaultSummaries") {
      vaultSummariesRequests += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([])
      });
      return;
    }

    if (
      requestType === "vaultDetails" &&
      requestVaultAddress === vaultAddress
    ) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          name: "OnlyShorts",
          vaultAddress,
          leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
          description: "Regression fixture",
          apr: "0.12",
          portfolio: [
            [
              "month",
              {
                accountValueHistory: [
                  [1, 100],
                  [2, 110]
                ],
                pnlHistory: [
                  [1, 0],
                  [2, 10]
                ]
              }
            ]
          ],
          followers: [],
          relationship: { type: "normal" },
          allowDeposits: false,
          alwaysCloseOnWithdraw: false
        })
      });
      return;
    }

    if (
      requestType === "webData2" &&
      requestVaultAddress === vaultAddress
    ) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({})
      });
      return;
    }

    await route.continue();
  });

  await visitRoute(page, `/vaults/${vaultAddress}`);
  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "vault-detail-root" } }
  );

  await expect
    .poll(() => vaultIndexRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);
  await expect
    .poll(() => vaultSummariesRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);
  await expect(page.getByRole("heading", { name: "OnlyShorts" })).toBeVisible();
  const tvlCard = page
    .getByText("TVL", { exact: true })
    .locator("xpath=ancestor::div[contains(@class, 'rounded-xl')][1]");
  await expect(tvlCard).toContainText("$321.50");
  await expect(tvlCard).not.toContainText("$0.00");
});

test("vault startup preview row click reuses inflight list bootstrap @regression", async ({ page }) => {
  const vaultAddress = "0x61b1cf5c2d7c4bf6d5db14f36651b2242e7cba0a";
  let releaseIndex;
  let releaseSummaries;
  let vaultIndexRequests = 0;
  let vaultSummariesRequests = 0;
  let vaultDetailsRequests = 0;
  let vaultWebDataRequests = 0;
  const indexGate = new Promise(resolve => {
    releaseIndex = resolve;
  });
  const summariesGate = new Promise(resolve => {
    releaseSummaries = resolve;
  });

  await page.route("https://stats-data.hyperliquid.xyz/Mainnet/vaults", async route => {
    vaultIndexRequests += 1;
    await indexGate;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          apr: "0.12",
          summary: {
            name: "Preview Vault",
            vaultAddress,
            leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            tvl: "321.5",
            isClosed: false,
            relationship: { type: "normal" },
            createTimeMillis: "1700"
          }
        }
      ])
    });
  });

  await page.route("https://api.hyperliquid.xyz/info", async route => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestType = payload?.type;
    const requestVaultAddress = String(
      payload?.vaultAddress || payload?.user || ""
    ).toLowerCase();

    if (requestType === "vaultSummaries") {
      vaultSummariesRequests += 1;
      await summariesGate;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([])
      });
      return;
    }

    if (
      requestType === "vaultDetails" &&
      requestVaultAddress === vaultAddress
    ) {
      vaultDetailsRequests += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          name: "Preview Vault",
          vaultAddress,
          leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
          description: "Preview regression fixture",
          apr: "0.12",
          portfolio: [],
          followers: [],
          relationship: { type: "normal" },
          allowDeposits: false,
          alwaysCloseOnWithdraw: false
        })
      });
      return;
    }

    if (
      requestType === "webData2" &&
      requestVaultAddress === vaultAddress
    ) {
      vaultWebDataRequests += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({})
      });
      return;
    }

    await route.continue();
  });

  await visitRoute(page, "/trade");
  await page.evaluate(previewRecord => {
    localStorage.setItem("vault-startup-preview:v1", JSON.stringify(previewRecord));
  }, {
    id: "vault-startup-preview:v1",
    version: 1,
    "saved-at-ms": Date.now(),
    "snapshot-range": "month",
    "wallet-address": null,
    "total-visible-tvl": 321.5,
    "protocol-rows": [
      {
        name: "Preview Vault",
        "vault-address": vaultAddress,
        leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
        apr: 12,
        tvl: 321.5,
        "your-deposit": 0,
        "age-days": 2,
        "snapshot-series": [10, 12],
        "is-closed?": false
      }
    ],
    "user-rows": [],
    "stale?": false
  });

  await dispatch(page, [":actions/navigate", "/vaults", { "replace?": true }]);

  const previewRow = page.locator("[data-role='vault-row-link']").first();
  await expect(previewRow).toBeVisible();
  await expect(previewRow).toContainText("Preview Vault");
  await expect(previewRow).not.toHaveClass(/focus:ring-2/);
  await expect
    .poll(() => vaultIndexRequests, { timeout: 10_000 })
    .toBe(1);
  await expect
    .poll(() => vaultSummariesRequests, { timeout: 10_000 })
    .toBe(1);

  await previewRow.click();
  await page.waitForTimeout(150);

  await expect(vaultIndexRequests).toBe(1);
  await expect(vaultSummariesRequests).toBe(1);

  releaseIndex();
  releaseSummaries();

  await expect
    .poll(async () => {
      const snapshot = await debugCall(page, "qaSnapshot");
      return snapshot.route;
    }, { timeout: 10_000 })
    .toBe(`/vaults/${vaultAddress}`);

  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "vault-detail-root" } }
  );
  await expect
    .poll(() => vaultDetailsRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);
  await expect
    .poll(() => vaultWebDataRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);
});

test("vault position coin jumps to the trade route market @regression", async ({ page }) => {
  const vaultAddress = "0x61b1cf5c2d7c4bf6d5db14f36651b2242e7cba0a";
  let vaultWebDataRequests = 0;

  await page.route("https://api.hyperliquid.xyz/info", async route => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestType = payload?.type;
    const requestVaultAddress = String(
      payload?.vaultAddress || payload?.user || ""
    ).toLowerCase();

    if (
      requestType === "vaultDetails" &&
      requestVaultAddress === vaultAddress
    ) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          name: "OnlyShorts",
          vaultAddress,
          leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
          description: "Regression fixture",
          apr: "0.12",
          portfolio: [
            [
              "month",
              {
                accountValueHistory: [
                  [1, 100],
                  [2, 110]
                ],
                pnlHistory: [
                  [1, 0],
                  [2, 10]
                ]
              }
            ]
          ],
          followers: [],
          relationship: { type: "normal" },
          allowDeposits: false,
          alwaysCloseOnWithdraw: false
        })
      });
      return;
    }

    if (
      requestType === "webData2" &&
      requestVaultAddress === vaultAddress
    ) {
      vaultWebDataRequests += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          clearinghouseState: {
            assetPositions: [
              {
                coin: "BTC",
                szi: "1.25",
                positionValue: "2500",
                entryPx: "50000",
                markPx: "50125",
                unrealizedPnl: "125",
                returnOnEquity: "0.05",
                liquidationPx: "42000",
                marginUsed: "500",
                leverage: { value: 3 },
                cumFunding: { sinceOpen: "-4.2" }
              }
            ]
          }
        })
      });
      return;
    }

    await route.continue();
  });

  await visitRoute(page, `/vaults/${vaultAddress}`);
  await expect
    .poll(() => vaultWebDataRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);

  await dispatch(page, [":actions/set-vault-detail-activity-tab", ":positions"]);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

  const coinButton = page.locator("[data-role='vault-detail-position-coin-select']").first();
  await expect(coinButton).toBeVisible();
  await coinButton.click();
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

  await expect.poll(async () => {
    const snapshot = await debugCall(page, "qaSnapshot");
    return {
      route: snapshot.route,
      activeAsset: snapshot.activeAsset
    };
  }).toMatchObject({
    route: "/trade/BTC",
    activeAsset: "BTC"
  });
});

test("asset selector favorite toggle keeps dropdown open @regression", async ({ page }) => {
  await seedAssetSelectorMarketsCache(page);
  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "asset-selector", {
    visibleDropdown: "asset-selector",
    desktopPresent: true
  });

  const selectorState = await oracle(page, "asset-selector");
  const favoriteButton = page.locator('[data-role="asset-selector-row"] [data-role="asset-selector-favorite-button"]').first();

  await expect(favoriteButton).toHaveAttribute("aria-pressed", "false");
  await favoriteButton.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(favoriteButton).toHaveAttribute("aria-pressed", "true");
  await expectOracle(page, "asset-selector", {
    visibleDropdown: "asset-selector",
    activeAsset: selectorState.activeAsset
  });
});

test("asset selector rapid scroll keeps rows visible @regression", async ({ page }) => {
  await seedAssetSelectorMarketsCache(page, 320);
  const nestedRenderWarnings = [];
  const pageErrors = [];
  page.on("console", (message) => {
    const text = message.text();
    if (
      text.includes("Triggered a render while rendering") ||
      text.includes("replicant.dom/render was called while working on a previous render")
    ) {
      nestedRenderWarnings.push(text);
    }
  });
  page.on("pageerror", (error) => {
    pageErrors.push(String(error));
  });

  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(page.locator('[data-role="asset-selector-scroll-container"]')).toBeVisible();
  await expect(page.locator('[data-role="asset-selector-row"]').first()).toBeVisible();

  const coverageSamples = await page.evaluate(async () => {
    const container = document.querySelector('[data-role="asset-selector-scroll-container"]');
    if (!container) {
      throw new Error("asset selector scroll container not found");
    }

    const rowCoverage = () => {
      const containerRect = container.getBoundingClientRect();
      const intervals = Array.from(document.querySelectorAll('[data-role="asset-selector-row"]'))
        .map((row) => row.getBoundingClientRect())
        .map((rect) => [Math.max(containerRect.top, rect.top), Math.min(containerRect.bottom, rect.bottom)])
        .filter(([top, bottom]) => bottom > top)
        .sort((a, b) => a[0] - b[0]);

      let covered = 0;
      let cursor = containerRect.top;
      for (const [top, bottom] of intervals) {
        const start = Math.max(top, cursor);
        if (bottom > start) {
          covered += bottom - start;
          cursor = bottom;
        }
      }

      return {
        covered,
        height: containerRect.height,
        blank: Math.max(0, containerRect.height - covered)
      };
    };

    const maxScrollTop = Math.max(0, container.scrollHeight - container.clientHeight);
    const targets = [0.15, 0.3, 0.45, 0.6, 0.75, 0.9]
      .map((fraction) => Math.max(0, Math.min(maxScrollTop, Math.floor(maxScrollTop * fraction))))
      .filter((target, index, allTargets) => index === 0 || target !== allTargets[index - 1]);

    const sampleTarget = async (target) => {
      container.scrollTop = target;
      const immediateCoverage = rowCoverage();
      await new Promise((resolve) => requestAnimationFrame(() => resolve()));
      const nextFrameCoverage = rowCoverage();
      await new Promise((resolve) => requestAnimationFrame(() => resolve()));
      return {
        target,
        immediateCoverage,
        nextFrameCoverage,
        settledCoverage: rowCoverage()
      };
    };

    const samples = [];
    for (const target of targets) {
      samples.push(await sampleTarget(target));
    }

    return samples;
  });

  for (const sample of coverageSamples) {
    expect(sample.immediateCoverage.blank).toBeLessThanOrEqual(1);
    expect(sample.nextFrameCoverage.blank).toBeLessThanOrEqual(1);
    expect(sample.settledCoverage.blank).toBeLessThanOrEqual(1);
  }
  expect(nestedRenderWarnings).toEqual([]);
  expect(pageErrors).toEqual([]);
});

test("funding modal deposit flow selects USDC @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/open-funding-deposit-modal", null]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expectOracle(page, "funding-modal", {
    open: true,
    title: "Deposit",
    contentKind: ":deposit/select"
  });

  await dispatch(page, [":actions/select-funding-deposit-asset", "usdc"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expectOracle(page, "funding-modal", {
    open: true,
    title: "Deposit USDC",
    contentKind: ":deposit/amount",
    selectedDepositAssetKey: "usdc"
  });
});

test("funding modal accessibility keeps focus in dialog, restores opener focus, and exposes labels @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  const openButton = page.locator('[data-role="funding-action-deposit"]');
  await expect(openButton).toBeVisible();
  await openButton.focus();
  await dispatch(page, [
    ":actions/open-funding-deposit-modal",
    await sourceRectForLocator(page, openButton),
    await openButton.getAttribute("data-role")
  ]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  const dialog = page.locator('[data-role="funding-modal"]');
  const closeButton = page.locator('[data-role="funding-modal-close"]');

  await expect(dialog).toHaveAttribute("aria-labelledby", "funding-modal-title");
  await expect(page.locator("#funding-modal-title")).toHaveText("Deposit");
  await expect(page.getByLabel("Search deposit assets")).toBeVisible();
  await expect(closeButton).toBeFocused();

  await page.keyboard.press("Shift+Tab");
  await expect
    .poll(async () =>
      dialog.evaluate((element) => element.contains(document.activeElement))
    )
    .toBe(true);

  await dispatch(page, [":actions/select-funding-deposit-asset", "usdc"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(page.getByLabel("Amount")).toBeVisible();
  await expect(dialog).toHaveAttribute("aria-labelledby", "funding-modal-title");

  await closeButton.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });

  await expect(dialog).toBeHidden();
  await expect(openButton).toBeFocused();
});

test("funding tooltip transitions from live position to hypothetical estimate @regression", async ({ page }) => {
  const livePosition = {
    coin: "BTC",
    szi: "9.2807",
    positionValue: "1000",
    entryPx: "107.7426",
    markPx: "107.7426",
    unrealizedPnl: "0",
    returnOnEquity: "0",
    liquidationPx: "80",
    marginUsed: "250",
    leverage: { value: 4 },
    cumFunding: { sinceOpen: "0" }
  };
  const livePositionValue = Number(livePosition.positionValue).toFixed(2);

  await visitRoute(page, "/trade/BTC");
  await debugCall(page, "seedFundingTooltipFixture", {
    coin: "BTC",
    mark: 107.7426,
    oracle: 107.61,
    fundingRate: 0.00015
  });
  await page.evaluate((position) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    const nextWebdata2 = c.js__GT_clj(
      {
        clearinghouseState: {
          assetPositions: [position]
        }
      },
      opts
    );
    const nextState = c.assoc_in(
      c.deref(store),
      c.PersistentVector.fromArray([keyword("webdata2")], true),
      nextWebdata2
    );

    c.reset_BANG_(store, nextState);
  }, livePosition);
  await dispatch(page, [":actions/reset-funding-hypothetical-position", "BTC"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 6_000, pollMs: 50 });

  const tooltipTrigger = page.locator('[data-role="active-asset-funding-trigger"]');
  await expect(tooltipTrigger).toHaveCount(1);
  await tooltipTrigger.click({ force: true });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  const tooltip = page.locator('[data-role="active-asset-funding-tooltip"]');
  const positionSection = tooltip.locator('[data-role="active-asset-funding-position-section"]');
  await expect(positionSection).toHaveAttribute("data-position-mode", "live");
  await expect(tooltip.getByRole("heading", { name: "Your Position" })).toBeVisible();
  await expect(tooltip.getByRole("button", { name: "Edit estimate" })).toBeVisible();

  await tooltip.getByRole("button", { name: "Edit estimate" }).click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(positionSection).toHaveAttribute("data-position-mode", "hypothetical");
  await expect(tooltip.getByRole("heading", { name: "Hypothetical Position" })).toBeVisible();
  await expect(tooltip.getByRole("button", { name: "Use live" })).toBeVisible();

  const sizeInput = tooltip.getByLabel("Hypothetical position size");
  const valueInput = tooltip.getByLabel("Hypothetical position value");
  await expect(sizeInput).toHaveValue("9.2807");
  await expect(valueInput).toHaveValue(livePositionValue);

  const next24hPayment = tooltip
    .locator('div.contents')
    .filter({ hasText: "Next 24h" })
    .locator("span")
    .nth(2);
  const next24hBefore = (await next24hPayment.textContent())?.trim() ?? "";

  await sizeInput.fill("10.0000");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect.poll(
    async () => (await next24hPayment.textContent())?.trim() ?? "",
    { timeout: 5_000 }
  ).not.toBe(next24hBefore);
});

test("wallet connect and enable trading stays deterministic @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await debugCall(page, "installWalletSimulator", {
    accounts: ["0x1111111111111111111111111111111111111111"],
    requestAccounts: ["0x1111111111111111111111111111111111111111"],
    chainId: "0xa4b1"
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");
  await debugCall(page, "installExchangeSimulator", {
    approveAgent: { responses: [{ status: "ok" }] }
  });

  await dispatch(page, [":actions/connect-wallet"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    connected: true,
    address: "0x1111111111111111111111111111111111111111",
    agentStatus: "not-ready"
  });

  await dispatch(page, [":actions/enable-agent-trading"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 5_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    connected: true,
    agentStatus: "ready",
    agentError: null
  });
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/enable-agent-trading",
      projectionBeforeHeavy: true,
      heavyEffectCount: 1
    },
    { args: { actionId: ":actions/enable-agent-trading" } }
  );
});

test("order submit and cancel gating uses simulator-backed assertions @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await debugCall(page, "installWalletSimulator", {
    accounts: ["0x1111111111111111111111111111111111111111"],
    requestAccounts: ["0x1111111111111111111111111111111111111111"],
    chainId: "0xa4b1"
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");

  await dispatch(page, [":actions/connect-wallet"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    connected: true,
    address: "0x1111111111111111111111111111111111111111",
    agentStatus: "not-ready"
  });

  await dispatch(page, [":actions/select-order-entry-mode", ":limit"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-input-mode", ":base"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/update-order-form", [":price"], "100"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-display", "1"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "order-form", {
    sizeDisplay: "1",
    submitDisabled: false,
    submitReason: null
  });

  await dispatch(page, [":actions/submit-order"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    agentError: "Enable trading before submitting orders."
  });
  await expectOracle(page, "agent-trading-recovery", {
    open: true,
    message: "Enable trading before submitting orders."
  });
  await expectOracle(page, "order-form", {
    runtimeError: null
  });
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/submit-order",
      covered: true,
      heavyEffectCount: 0,
      projectionBeforeHeavy: true,
      phaseOrderValid: true
    },
    { args: { actionId: ":actions/submit-order" } }
  );

  await dispatch(page, [":actions/cancel-order", { coin: "BTC", oid: 101 }]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "order-form", {
    cancelError: "Enable trading before cancelling orders."
  });
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/cancel-order",
      covered: true,
      heavyEffectCount: 0,
      projectionBeforeHeavy: true,
      phaseOrderValid: true
    },
    { args: { actionId: ":actions/cancel-order" } }
  );
});

test("order submit confirmation renders in-app instead of opening a browser dialog @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");

  let browserDialogSeen = false;
  page.on("dialog", async (dialog) => {
    browserDialogSeen = true;
    await dialog.dismiss();
  });

  await debugCall(page, "installWalletSimulator", {
    accounts: ["0x1111111111111111111111111111111111111111"],
    requestAccounts: ["0x1111111111111111111111111111111111111111"],
    chainId: "0xa4b1"
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");
  await debugCall(page, "installExchangeSimulator", {
    approveAgent: { responses: [{ status: "ok" }] }
  });

  await dispatch(page, [":actions/connect-wallet"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await dispatch(page, [":actions/enable-agent-trading"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 5_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    connected: true,
    agentStatus: "ready",
    agentError: null
  });

  await dispatch(page, [":actions/select-order-entry-mode", ":limit"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-input-mode", ":base"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/update-order-form", [":price"], "100"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-display", "1"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await dispatch(page, [":actions/submit-order"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await expect(page.locator('[data-role="order-submit-confirmation-dialog"]')).toBeVisible();
  await expect(page.locator('[data-role="order-submit-confirmation-title"]')).toHaveText(
    "Submit Order?"
  );
  await expect(browserDialogSeen).toBe(false);
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/submit-order",
      covered: true,
      heavyEffectCount: 0,
      projectionBeforeHeavy: true,
      phaseOrderValid: true
    },
    { args: { actionId: ":actions/submit-order" } }
  );

  await page.locator('[data-role="order-submit-confirmation-cancel"]').click();
  await expect(page.locator('[data-role="order-submit-confirmation-dialog"]')).toHaveCount(0);
});

test("trading settings confirmation toggles respond to visible switch clicks @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");

  await page.locator('[data-role="header-settings-button"]').click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  const openToggleLabel = page
    .locator(
      '[data-role="trading-settings-panel"] [data-role="trading-settings-confirm-open-orders-row"] label'
    )
    .first();
  const openToggleInput = page
    .locator(
      '[data-role="trading-settings-panel"] [data-role="trading-settings-confirm-open-orders-row"] input[type="checkbox"]'
    )
    .first();
  const closeToggleLabel = page
    .locator(
      '[data-role="trading-settings-panel"] [data-role="trading-settings-confirm-close-position-row"] label'
    )
    .first();
  const closeToggleInput = page
    .locator(
      '[data-role="trading-settings-panel"] [data-role="trading-settings-confirm-close-position-row"] input[type="checkbox"]'
    )
    .first();

  await expect(openToggleInput).toBeChecked();
  await expect(closeToggleInput).toBeChecked();

  await openToggleLabel.click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await expect(openToggleInput).not.toBeChecked();
  await expect
    .poll(
      async () =>
        (await debugCall(page, "snapshot"))["app-state"]["trading-settings"]["confirm-open-orders?"],
      { timeout: 4_000 }
    )
    .toBe(false);

  await closeToggleLabel.click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await expect(closeToggleInput).not.toBeChecked();
  await expect
    .poll(
      async () =>
        (await debugCall(page, "snapshot"))["app-state"]["trading-settings"]["confirm-close-position?"],
      { timeout: 4_000 }
    )
    .toBe(false);

  await expect
    .poll(
      () =>
        page.evaluate(() =>
          JSON.parse(localStorage.getItem("hyperopen:trading-settings:v1") || "{}")
        ),
      { timeout: 4_000 }
    )
    .toMatchObject({
      "confirm-open-orders?": false,
      "confirm-close-position?": false
    });
});
