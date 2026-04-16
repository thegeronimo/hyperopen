import { expect, test } from "@playwright/test";
import {
  debugCall,
  dispatch,
  visitRoute,
  waitForDebugBridge,
  waitForIdle
} from "../support/hyperopen.mjs";

const TRADER_ADDRESS = "0x3333333333333333333333333333333333333333";
const VAULT_ADDRESS = "0x1234567890abcdef1234567890abcdef12345678";
const LEADER_ADDRESS = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd";
const SPECTATE_ADDRESS = "0x162cc7c861ebd0c06b3d72319201150482518185";

function vaultDetailsFixture() {
  return {
    name: "Shareable URL Vault",
    vaultAddress: VAULT_ADDRESS,
    leader: LEADER_ADDRESS,
    description: "Deterministic shareable URL fixture",
    tvl: "321.5",
    apr: "0.12",
    portfolio: [
      [
        "allTime",
        {
          accountValueHistory: [[Date.UTC(2025, 0, 1), 100]],
          pnlHistory: [[Date.UTC(2025, 0, 1), 0]]
        }
      ]
    ],
    followers: [],
    relationship: { type: "normal" },
    allowDeposits: false,
    alwaysCloseOnWithdraw: false
  };
}

async function stubVaultRequests(page) {
  await page.route("https://stats-data.hyperliquid.xyz/Mainnet/vaults", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          apr: "0.12",
          summary: {
            name: "Shareable URL Vault",
            vaultAddress: VAULT_ADDRESS,
            leader: LEADER_ADDRESS,
            tvl: "321.5",
            isClosed: false,
            relationship: { type: "normal" },
            createTimeMillis: String(Date.UTC(2025, 0, 1))
          }
        }
      ])
    });
  });

  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestType = payload?.type;

    if (requestType === "vaultSummaries") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([])
      });
      return;
    }

    if (requestType === "userVaultEquities") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([])
      });
      return;
    }

    if (requestType === "vaultDetails") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(vaultDetailsFixture())
      });
      return;
    }

    if (
      requestType === "webData2" ||
      requestType === "userFills" ||
      requestType === "userFunding" ||
      requestType === "historicalOrders" ||
      requestType === "userNonFundingLedgerUpdates"
    ) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([])
      });
      return;
    }

    if (requestType === "candleSnapshot") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([
          { t: Date.UTC(2025, 0, 1), c: 100 },
          { t: Date.UTC(2025, 1, 1), c: 110 }
        ])
      });
      return;
    }

    await route.continue();
  });
}

function routeFromPageUrl(page) {
  const url = new URL(page.url());
  return `${url.pathname}${url.search}`;
}

function expectQuery(urlText, expected) {
  const url = new URL(urlText);

  for (const [key, value] of Object.entries(expected)) {
    if (Array.isArray(value)) {
      expect(url.searchParams.getAll(key)).toEqual(value);
    } else {
      expect(url.searchParams.get(key)).toBe(value);
    }
  }
}

async function appStatePath(page, path) {
  const snapshot = await debugCall(page, "snapshot");
  let cursor = snapshot["app-state"];
  for (const key of path) {
    cursor = cursor?.[key];
  }
  return cursor;
}

async function expectAppState(page, path, expected) {
  await expect
    .poll(() => appStatePath(page, path), { timeout: 10_000 })
    .toEqual(expected);
}

async function expectPortfolioBenchmarkChips(page, coins) {
  for (const coin of coins) {
    await expect(page.locator(`[data-role='portfolio-returns-benchmark-chip-${coin}']`))
      .toContainText(coin);
  }
}

async function openFreshPageAt(browser, referencePage, route, { stubVaults = false } = {}) {
  const context = await browser.newContext();
  const page = await context.newPage();
  if (stubVaults) {
    await stubVaultRequests(page);
  }
  await page.goto(new URL(route, referencePage.url()).toString(), { waitUntil: "commit" });
  await waitForDebugBridge(page);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });
  return { context, page };
}

test("portfolio view changes replace the URL and restore from a fresh shared link @regression", async ({ page, browser }) => {
  await visitRoute(page, "/portfolio");

  await dispatch(page, [":actions/start-spectate-mode", SPECTATE_ADDRESS]);
  await dispatch(page, [":actions/select-portfolio-summary-time-range", ":three-month"]);
  await dispatch(page, [":actions/select-portfolio-returns-benchmark", "ETH"]);
  await dispatch(page, [":actions/set-portfolio-account-info-tab", ":positions"]);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

  expect(new URL(page.url()).pathname).toBe("/portfolio");
  expectQuery(page.url(), {
    spectate: SPECTATE_ADDRESS,
    range: "3m",
    scope: "all",
    chart: "returns",
    bench: ["BTC", "ETH"],
    tab: "positions"
  });

  const { context, page: freshPage } = await openFreshPageAt(
    browser,
    page,
    routeFromPageUrl(page)
  );
  try {
    await expect(freshPage.locator("[data-role='portfolio-summary-time-range-selector-trigger']"))
      .toContainText("3M");
    await expect(freshPage.locator("[data-role='portfolio-chart-tab-returns']"))
      .toHaveAttribute("aria-pressed", "true");
    await expectPortfolioBenchmarkChips(freshPage, ["BTC", "ETH"]);
    await expect(freshPage.locator("[data-role='account-info-tab-positions']"))
      .toHaveAttribute("aria-pressed", "true");
  } finally {
    await context.close();
  }
});

test("trader portfolio keeps the address path while serializing portfolio view state @regression", async ({ page, browser }) => {
  await visitRoute(page, `/portfolio/trader/${TRADER_ADDRESS}`);

  await dispatch(page, [":actions/select-portfolio-summary-scope", ":perps"]);
  await dispatch(page, [":actions/select-portfolio-summary-time-range", ":one-year"]);
  await dispatch(page, [":actions/select-portfolio-returns-benchmark", "ETH"]);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

  expect(new URL(page.url()).pathname).toBe(`/portfolio/trader/${TRADER_ADDRESS}`);
  expectQuery(page.url(), {
    range: "1y",
    scope: "perps",
    chart: "returns",
    bench: ["BTC", "ETH"],
    tab: "performance-metrics"
  });
  expect(new URL(page.url()).searchParams.get("spectate")).toBeNull();

  const { context, page: freshPage } = await openFreshPageAt(
    browser,
    page,
    routeFromPageUrl(page)
  );
  try {
    await expectAppState(freshPage, ["portfolio-ui", "summary-scope"], "perps");
    await expectAppState(freshPage, ["portfolio-ui", "summary-time-range"], "one-year");
    await expectAppState(freshPage, ["portfolio-ui", "returns-benchmark-coins"], ["BTC", "ETH"]);
  } finally {
    await context.close();
  }
});

test("vault list filters and pagination replace the URL and restore from a fresh shared link @regression", async ({ page, browser }) => {
  await stubVaultRequests(page);
  await visitRoute(page, "/vaults");

  await dispatch(page, [":actions/set-vaults-snapshot-range", ":three-month"]);
  await dispatch(page, [":actions/set-vaults-search-query", "lp"]);
  await dispatch(page, [":actions/toggle-vaults-filter", ":leading"]);
  await dispatch(page, [":actions/toggle-vaults-filter", ":others"]);
  await dispatch(page, [":actions/toggle-vaults-filter", ":closed"]);
  await dispatch(page, [":actions/set-vaults-sort", ":apr"]);
  await dispatch(page, [":actions/set-vaults-user-page-size", 25]);
  await dispatch(page, [":actions/set-vaults-user-page", 2, 5]);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

  expect(new URL(page.url()).pathname).toBe("/vaults");
  expectQuery(page.url(), {
    range: "3m",
    q: "lp",
    roles: "deposited",
    closed: "1",
    sort: "apr:desc",
    page: "2",
    pageSize: "25"
  });

  const { context, page: freshPage } = await openFreshPageAt(
    browser,
    page,
    routeFromPageUrl(page),
    { stubVaults: true }
  );
  try {
    await expectAppState(freshPage, ["vaults-ui", "snapshot-range"], "three-month");
    await expectAppState(freshPage, ["vaults-ui", "search-query"], "lp");
    await expectAppState(freshPage, ["vaults-ui", "filter-leading?"], false);
    await expectAppState(freshPage, ["vaults-ui", "filter-deposited?"], true);
    await expectAppState(freshPage, ["vaults-ui", "filter-others?"], false);
    await expectAppState(freshPage, ["vaults-ui", "filter-closed?"], true);
    await expectAppState(freshPage, ["vaults-ui", "sort"], {
      column: "apr",
      direction: "desc"
    });
    await expectAppState(freshPage, ["vaults-ui", "user-vaults-page"], 2);
    await expectAppState(freshPage, ["vaults-ui", "user-vaults-page-size"], 25);
  } finally {
    await context.close();
  }
});

test("vault detail chart and activity state replace the URL and restore from a fresh shared link @regression", async ({ page, browser }) => {
  await stubVaultRequests(page);
  await visitRoute(page, `/vaults/${VAULT_ADDRESS}`);

  await dispatch(page, [":actions/set-vaults-snapshot-range", ":six-month"]);
  await dispatch(page, [":actions/set-vault-detail-chart-series", ":returns"]);
  await dispatch(page, [":actions/select-vault-detail-returns-benchmark", "ETH"]);
  await dispatch(page, [":actions/set-vault-detail-tab", ":vault-performance"]);
  await dispatch(page, [":actions/set-vault-detail-activity-tab", ":trade-history"]);
  await dispatch(page, [":actions/set-vault-detail-activity-direction-filter", ":long"]);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });

  expect(new URL(page.url()).pathname).toBe(`/vaults/${VAULT_ADDRESS}`);
  expectQuery(page.url(), {
    range: "6m",
    chart: "returns",
    bench: ["BTC", "ETH"],
    tab: "vault-performance",
    activity: "trade-history",
    side: "long"
  });

  const { context, page: freshPage } = await openFreshPageAt(
    browser,
    page,
    routeFromPageUrl(page),
    { stubVaults: true }
  );
  try {
    await expectAppState(freshPage, ["vaults-ui", "snapshot-range"], "six-month");
    await expectAppState(freshPage, ["vaults-ui", "detail-chart-series"], "returns");
    await expectAppState(freshPage, ["vaults-ui", "detail-returns-benchmark-coins"], ["BTC", "ETH"]);
    await expectAppState(freshPage, ["vaults-ui", "detail-tab"], "vault-performance");
    await expectAppState(freshPage, ["vaults-ui", "detail-activity-tab"], "trade-history");
    await expectAppState(freshPage, ["vaults-ui", "detail-activity-direction-filter"], "long");
  } finally {
    await context.close();
  }
});
