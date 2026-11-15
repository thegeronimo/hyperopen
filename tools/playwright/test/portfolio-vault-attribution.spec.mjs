import { expect, test } from "../support/guarded_test.mjs";
import {
  waitForDebugBridge,
  waitForIdle
} from "../support/hyperopen.mjs";

const ACCOUNT = "0x162cc7c861ebd0c06b3d72319201150482518185";
const OPEN_VAULT = "0xaaaa00000000000000000000000000000000aaaa";
const CLOSED_VAULT = "0xbbbb00000000000000000000000000000000bbbb";

const DAY_MS = 86_400_000;
const T = (month, day) => Date.UTC(2026, month - 1, day);

const EXPECTED_TOTAL_PNL = "+$460.00";
const OPEN_VAULT_EQUITY = "1500.0";

const LEDGER_WITH_VAULTS = [
  {
    time: T(2, 1),
    hash: "0xc10000000000000000000000000000000000000000000000000000000000c1",
    delta: { type: "vaultDeposit", vault: CLOSED_VAULT, usdc: "2000.0" }
  },
  {
    time: T(3, 1),
    hash: "0xa10000000000000000000000000000000000000000000000000000000000a1",
    delta: { type: "vaultDeposit", vault: OPEN_VAULT, usdc: "1000.0" }
  },
  {
    time: T(4, 1),
    hash: "0xa20000000000000000000000000000000000000000000000000000000000a2",
    delta: { type: "vaultDeposit", vault: OPEN_VAULT, usdc: "500.0" }
  },
  {
    time: T(5, 1),
    hash: "0xc20000000000000000000000000000000000000000000000000000000000c2",
    delta: {
      type: "vaultWithdraw",
      vault: CLOSED_VAULT,
      user: ACCOUNT,
      requestedUsd: "2100.0",
      commission: "0.0",
      closingCost: "0.0",
      basis: "2000.0",
      netWithdrawnUsd: "2100.0"
    }
  },
  {
    time: T(6, 1),
    hash: "0xa30000000000000000000000000000000000000000000000000000000000a3",
    delta: {
      type: "vaultWithdraw",
      vault: OPEN_VAULT,
      user: ACCOUNT,
      requestedUsd: "360.0",
      commission: "0.0",
      closingCost: "0.0",
      basis: "300.0",
      netWithdrawnUsd: "360.0"
    }
  },
  {
    time: T(6, 2),
    hash: "0xd10000000000000000000000000000000000000000000000000000000000d1",
    delta: { type: "deposit", usdc: "50.0" }
  }
];

const LEDGER_WITHOUT_VAULTS = [
  {
    time: T(6, 2),
    hash: "0xd10000000000000000000000000000000000000000000000000000000000d1",
    delta: { type: "deposit", usdc: "50.0" }
  }
];

function vaultDetails(vaultAddress, name, points, followerState = null) {
  const history = (field) => points.map(([time, accountValue, pnl]) =>
    [time, field === "value" ? accountValue : pnl]);
  const rangeEntry = {
    accountValueHistory: history("value"),
    pnlHistory: history("pnl"),
    vlm: "0.0"
  };
  return {
    name,
    vaultAddress,
    leader: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
    description: "Deterministic vault attribution fixture",
    tvl: "1000000.0",
    apr: "0.1",
    portfolio: [
      ["day", rangeEntry],
      ["week", rangeEntry],
      ["month", rangeEntry],
      ["allTime", rangeEntry]
    ],
    followers: [],
    relationship: { type: "normal" },
    allowDeposits: true,
    alwaysCloseOnWithdraw: false,
    followerState
  };
}

const VAULT_DETAILS = {
  [OPEN_VAULT]: vaultDetails(OPEN_VAULT, "Alpha Open Vault", [
    [T(3, 1), "1000.0", "0.0"],
    [T(3, 15), "1040.0", "40.0"],
    [T(4, 1), "1580.0", "80.0"],
    [T(5, 1), "1700.0", "200.0"],
    [T(6, 1), "1500.0", "360.0"]
  ], {
    user: ACCOUNT,
    vaultEquity: OPEN_VAULT_EQUITY,
    pnl: "300.0",
    allTimePnl: "360.0",
    daysFollowing: 100,
    vaultEntryTime: T(3, 1),
    lockupUntil: null
  }),
  [CLOSED_VAULT]: vaultDetails(CLOSED_VAULT, "Beta Closed Vault", [
    [T(2, 1), "2000.0", "0.0"],
    [T(3, 1), "2050.0", "50.0"],
    [T(4, 1), "2080.0", "80.0"],
    [T(5, 1), "2100.0", "100.0"]
  ])
};

const LIST_ANSWER_TYPES = new Set([
  "userFills",
  "userFillsByTime",
  "userFunding",
  "historicalOrders",
  "frontendOpenOrders",
  "openOrders",
  "twapHistory",
  "userTwapSliceFills",
  "vaultSummaries",
  "userNonFundingLedgerUpdates",
  "userVaultEquities",
  "candleSnapshot",
  "delegations",
  "userBorrowLendInterest",
  "userRateLimit"
]);

async function stubInfo(page, world) {
  await page.route("**/info", async (route) => {
    const request = route.request();
    if (request.method() !== "POST") {
      await route.fulfill({ status: 200, contentType: "application/json", body: "{}" });
      return;
    }
    let payload = {};
    try {
      payload = request.postDataJSON() || {};
    } catch {
      payload = {};
    }
    world.requests.push(payload);
    const json = (body, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(body)
      });

    switch (payload.type) {
      case "userNonFundingLedgerUpdates":
        if (world.ledgerFailures > 0) {
          world.ledgerFailures -= 1;
          await json({ error: "fixture ledger failure" }, 500);
          return;
        }
        await json(world.ledger);
        return;
      case "vaultDetails": {
        const details = VAULT_DETAILS[String(payload.vaultAddress).toLowerCase()];
        await json(details || {}, details ? 200 : 404);
        return;
      }
      case "userVaultEquities":
        await json(world.equities);
        return;
      default:
        world.fallbackTypes.add(String(payload.type));
        await json(LIST_ANSWER_TYPES.has(payload.type) ? [] : {});
    }
  });
}

function newWorld(overrides = {}) {
  return {
    ledger: LEDGER_WITH_VAULTS,
    ledgerFailures: 0,
    equities: [
      { vaultAddress: OPEN_VAULT, equity: OPEN_VAULT_EQUITY, lockedUntilTimestamp: 0 }
    ],
    requests: [],
    fallbackTypes: new Set(),
    ...overrides
  };
}

async function openPortfolio(page, route = `/portfolio?spectate=${ACCOUNT}`) {
  await page.goto(route);
  await waitForDebugBridge(page);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });
  await expect(page.locator("[data-parity-id='app-route-module-shell']"))
    .toHaveCount(0, { timeout: 15_000 });
  await expect(page.locator("[data-role='spectate-mode-active-banner']")).toBeVisible();
}

function ledgerRequests(world) {
  return world.requests.filter((payload) => payload.type === "userNonFundingLedgerUpdates");
}

function searchParam(page, name) {
  return new URL(page.url()).searchParams.get(name);
}

async function openVaultsTab(page) {
  const tab = page.locator("[data-role='account-info-tab-vaults']");
  await tab.scrollIntoViewIfNeeded();
  await tab.click();
  await expect(tab).toHaveAttribute("aria-pressed", "true");
}

test("portfolio vaults tab reports vault PNL, per-vault rows and the value chart @regression", async ({ page }) => {
  const world = newWorld();
  await stubInfo(page, world);
  await openPortfolio(page);

  await openVaultsTab(page);

  await expect.poll(() => ledgerRequests(world).length, { timeout: 10_000 }).toBeGreaterThan(0);
  expect(ledgerRequests(world)[0]).toMatchObject({
    type: "userNonFundingLedgerUpdates",
    user: ACCOUNT,
    startTime: 0
  });
  await expect.poll(
    () => world.requests
      .filter((payload) => payload.type === "vaultDetails")
      .map((payload) => payload.vaultAddress)
      .sort(),
    { timeout: 10_000 }
  ).toEqual([OPEN_VAULT, CLOSED_VAULT].sort());
  for (const payload of world.requests.filter((request) => request.type === "vaultDetails")) {
    expect(payload.user).toBe(ACCOUNT);
  }

  const panel = page.locator("[data-role='portfolio-vaults-tab']");
  await expect(panel.locator("[data-role='portfolio-vaults-ready']")).toBeVisible({ timeout: 15_000 });
  await expect.poll(() => searchParam(page, "tab")).toBe("vaults");
  await expect(page.locator("[data-role='account-info-tab-vaults']")).toContainText("Vaults (1)");

  const cards = panel.locator("[data-role='portfolio-vaults-cards']");
  await expect(cards).toContainText("Vault PNL");
  await expect(cards).toContainText("Money-weighted return");
  await expect(cards).toContainText("Time-weighted return");
  await expect(cards).toContainText("Vault equity");
  await expect(panel.locator("[data-role='portfolio-vaults-card-pnl-value']"))
    .toHaveText(EXPECTED_TOTAL_PNL);
  await expect(panel.locator("[data-role='portfolio-vaults-card-equity-value']"))
    .toHaveText("$1,500.00");
  await expect(panel.locator("[data-role='portfolio-vaults-card-pnl']"))
    .toContainText("Realized");

  const table = panel.locator("[data-role='portfolio-vaults-table']");
  for (const heading of [
    "Vault",
    "Status",
    "Deposited",
    "Withdrawn",
    "Value",
    "PNL",
    "Money-weighted",
    "Vault return while held",
    "Held"
  ]) {
    await expect(table.locator("th", { hasText: new RegExp(`^${heading}$`) })).toHaveCount(1);
  }
  const rows = table.locator("[data-role='portfolio-vaults-row']");
  await expect(rows).toHaveCount(2);
  const openRow = rows.filter({ hasText: "Alpha Open Vault" });
  const closedRow = rows.filter({ hasText: "Beta Closed Vault" });
  await expect(openRow.locator("[data-role='portfolio-vaults-status']")).toHaveText("Open");
  await expect(closedRow.locator("[data-role='portfolio-vaults-status']")).toHaveText("Closed");
  await expect(openRow).toContainText("+$360.00");
  await expect(closedRow).toContainText("+$100.00");
  await expect(openRow).toContainText("$1,500.00");
  await expect(openRow.locator("[data-role='portfolio-vaults-vault-link']"))
    .toHaveAttribute("href", new RegExp(`/vaults/${OPEN_VAULT}`));

  const totalRow = table.locator("[data-role='portfolio-vaults-total-row']");
  await expect(totalRow).toBeVisible();
  await expect(totalRow).toContainText("All vaults");
  await expect(totalRow).toContainText("$3,500.00");
  await expect(totalRow).toContainText("$2,460.00");
  await expect(totalRow).toContainText(EXPECTED_TOTAL_PNL);

  const chart = panel.locator("[data-role='portfolio-vaults-chart']");
  await expect(chart).toBeVisible();
  await expect(chart.locator("svg")).toHaveCount(1);
  await expect(chart.locator("svg polyline").first()).toBeAttached();
  expect(await chart.locator("svg polyline").count()).toBeGreaterThanOrEqual(2);
  await expect(chart.locator("[data-role='portfolio-vaults-chart-deposit']").first()).toBeVisible();
  await expect(chart.locator("[data-role='portfolio-vaults-chart-withdrawal']").first()).toBeVisible();
  await expect(chart.locator("[data-role='portfolio-vaults-chart-legend']")).toContainText("Net deposited");

  expect(world.fallbackTypes.has("vaultDetails")).toBe(false);
});

test("portfolio vaults summary scope shows vault figures and restores the account scope @regression", async ({ page }) => {
  const world = newWorld();
  await stubInfo(page, world);
  await openPortfolio(page);

  const card = page.locator("[data-role='portfolio-account-summary-card']");
  const trigger = page.locator("[data-role='portfolio-summary-scope-selector-trigger']");
  const chartTab = (value) => page.locator(`[data-role='portfolio-chart-tab-${value}']`);

  await expect(card).toContainText("Total Equity");
  await expect(chartTab("account-value")).toHaveText("Account Value");
  const allScopeLabel = ((await trigger.textContent()) || "").trim();
  expect(allScopeLabel).toContain("Perps + Spot + Vaults");

  await trigger.click();
  await page.locator("[data-role='portfolio-summary-scope-selector-option-vaults']").click();

  await expect.poll(() => searchParam(page, "scope")).toBe("vaults");
  await expect.poll(() => ledgerRequests(world).length, { timeout: 10_000 }).toBeGreaterThan(0);

  const summary = card.locator("[data-role='portfolio-vaults-summary']");
  await expect(summary).toBeVisible();
  await expect(summary.locator("[data-role='portfolio-vaults-pnl']")).toContainText("Vault PNL", { timeout: 15_000 });
  await expect(summary.locator("[data-role='portfolio-vaults-pnl']")).toContainText(EXPECTED_TOTAL_PNL);
  for (const label of [
    "PNL",
    "Max Drawdown",
    "All-time",
    "Realized",
    "Unrealized",
    "Money-weighted Return",
    "Vault Equity",
    "Net Deposited",
    "Vaults Held"
  ]) {
    await expect(summary).toContainText(label);
  }
  await expect(summary.locator("[data-role='portfolio-vaults-held']")).toContainText("1 open · 1 closed");
  await expect(summary.locator("[data-role='portfolio-vaults-equity']")).toContainText("$1,500.00");
  await expect(card).not.toContainText("Total Equity");
  await expect(card).not.toContainText("Volume");

  await expect(chartTab("account-value")).toHaveText("Vault Value");

  await trigger.click();
  await page.locator("[data-role='portfolio-summary-scope-selector-option-all']").click();
  await expect(card).toContainText("Total Equity");
  await expect(summary).toHaveCount(0);
  await expect(chartTab("account-value")).toHaveText("Account Value");
  await expect.poll(() => searchParam(page, "scope")).not.toBe("vaults");
});

test("portfolio vaults tab shows an error with Retry and recovers @regression", async ({ page }) => {
  const world = newWorld({ ledgerFailures: Number.MAX_SAFE_INTEGER });
  await stubInfo(page, world);
  await openPortfolio(page);

  await openVaultsTab(page);

  const panel = page.locator("[data-role='portfolio-vaults-tab']");
  const error = panel.locator("[data-role='portfolio-vaults-error']");
  await expect(error).toBeVisible({ timeout: 20_000 });
  await expect(error).toContainText("Vault history could not be loaded.");
  const retry = error.locator("[data-role='portfolio-vaults-retry']");
  await expect(retry).toBeVisible();
  await expect(retry).toHaveText("Retry");
  await expect(panel.locator("[data-role='portfolio-vaults-table']")).toHaveCount(0);

  world.ledgerFailures = 0;
  const before = ledgerRequests(world).length;
  await retry.click();

  await expect.poll(() => ledgerRequests(world).length, { timeout: 10_000 }).toBeGreaterThan(before);
  await expect(panel.locator("[data-role='portfolio-vaults-ready']")).toBeVisible({ timeout: 15_000 });
  await expect(panel.locator("[data-role='portfolio-vaults-error']")).toHaveCount(0);
  await expect(panel.locator("[data-role='portfolio-vaults-row']")).toHaveCount(2);
  await expect(panel.locator("[data-role='portfolio-vaults-total-row']")).toContainText("All vaults");
});

test("portfolio vaults tab shows the empty state when the ledger has no vault rows @regression", async ({ page }) => {
  const world = newWorld({ ledger: LEDGER_WITHOUT_VAULTS, equities: [] });
  await stubInfo(page, world);
  await openPortfolio(page);

  await openVaultsTab(page);

  const panel = page.locator("[data-role='portfolio-vaults-tab']");
  await expect(panel.locator("[data-role='portfolio-vaults-empty']"))
    .toContainText("No vault deposits found for this account.", { timeout: 15_000 });
  await expect(panel.locator("[data-role='portfolio-vaults-table']")).toHaveCount(0);
  await expect(panel.locator("[data-role='portfolio-vaults-error']")).toHaveCount(0);
  expect(world.requests.filter((payload) => payload.type === "vaultDetails")).toHaveLength(0);
});
