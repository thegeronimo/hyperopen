import { expect, test } from "../support/guarded_test.mjs";
import { dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

const DAY_MS = 24 * 60 * 60 * 1000;
const NOW = Date.now();
const LEADER = "0x677d00000000000000000000000000000008a4e7";

const VAULTS = [
  { address: "0x1000000000000000000000000000000000000001", name: "Steady Vault", tvl: "900000", ageDays: 400, drift: 0.002, wobble: 0.001 },
  { address: "0x2000000000000000000000000000000000000002", name: "Choppy Vault", tvl: "400000", ageDays: 400, drift: -0.001, wobble: 0.03 },
  { address: "0x3000000000000000000000000000000000000003", name: "Small Vault", tvl: "20000", ageDays: 400, drift: 0.001, wobble: 0.002 },
  { address: "0x4000000000000000000000000000000000000004", name: "Tiny Vault", tvl: "500", ageDays: 400, drift: 0.001, wobble: 0.002 }
];

function history({ drift, wobble }) {
  const days = 220;
  const accountValueHistory = [];
  const pnlHistory = [];
  let value = 100000;
  for (let i = 0; i < days; i += 1) {
    const t = NOW - (days - 1 - i) * DAY_MS;
    if (i > 0) value *= 1 + drift + wobble * Math.sin(i * 1.7);
    accountValueHistory.push([t, String(value)]);
    pnlHistory.push([t, String(value - 100000)]);
  }
  return { accountValueHistory, pnlHistory };
}

async function stubVaults(page, requestLog) {
  await page.route("https://stats-data.hyperliquid.xyz/Mainnet/vaults", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(
        VAULTS.map((vault) => ({
          apr: "0.1",
          pnls: [],
          summary: {
            name: vault.name,
            vaultAddress: vault.address,
            leader: LEADER,
            tvl: vault.tvl,
            isClosed: false,
            relationship: { type: "normal" },
            createTimeMillis: String(NOW - vault.ageDays * DAY_MS)
          }
        }))
      )
    });
  });

  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    if (payload?.type === "vaultSummaries") {
      await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
      return;
    }
    if (payload?.type === "vaultDetails") {
      const vault = VAULTS.find((v) => v.address === String(payload.vaultAddress).toLowerCase());
      if (vault) {
        requestLog.push({ address: vault.address, at: Date.now() });
        const summary = history(vault);
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            name: vault.name,
            vaultAddress: vault.address,
            leader: LEADER,
            portfolio: [["allTime", summary], ["month", summary]],
            followers: [],
            relationship: { type: "normal" }
          })
        });
        return;
      }
    }
    await route.continue();
  });
}

async function openScreener(page) {
  await visitRoute(page, "/vaults");
  await page.locator("[data-role='vaults-view-screener']").click();
  await expect(page.locator("[data-role='vault-screener-builder']")).toBeVisible();
}

test("screener universe count follows the age and TVL controls @regression", async ({ page }) => {
  await stubVaults(page, []);
  await openScreener(page);

  const count = page.locator("[data-role='vault-screener-universe-count']");
  await expect(count).toContainText("3 of 4");

  await page.locator("[data-role='vault-screener-tvl'] button", { hasText: "$100k" }).click();
  await expect(count).toContainText("2 of 4");

  await page.locator("[data-role='vault-screener-age'] button", { hasText: "1 year" }).click();
  await expect(count).toContainText("2 of 4");
  await expect(page.locator("[data-role='vault-screener-eta']")).toContainText("4 s");
});

test("screener run paces requests and groups results @regression", async ({ page }) => {
  test.setTimeout(60_000);
  const requests = [];
  await stubVaults(page, requests);
  await openScreener(page);

  await page.locator("[data-role='vault-screener-run']").click();
  await expect(page.locator("[data-role='vault-screener-run-panel']")).toBeVisible();
  await expect(page.locator("[data-role='vault-screener-headline']")).toContainText("of 3 vaults pass", {
    timeout: 30_000
  });

  expect(requests.map((r) => r.address)).toEqual(VAULTS.slice(0, 3).map((v) => v.address));
  for (let i = 1; i < requests.length; i += 1) {
    expect(requests[i].at - requests[i - 1].at).toBeGreaterThanOrEqual(1_800);
  }

  const tabCounts = await Promise.all(
    ["pass", "near", "fail", "thin"].map((tab) =>
      page.locator(`[data-role='vault-screener-tab-${tab}']`).innerText()
    )
  );
  const total = tabCounts
    .map((text) => Number(text.trim().split(/\s+/).pop()))
    .reduce((a, b) => a + b, 0);
  expect(total).toBe(3);

  await expect(page.locator("[data-role='vault-screener-results']")).toContainText("Steady Vault");

  await page.locator("[data-role='vault-screener-tab-fail']").click();
  await expect(page.locator("[data-role='vault-screener-results']")).toContainText("Choppy Vault");

  // Editing a threshold re-filters without fetching again.
  const before = requests.length;
  await dispatch(page, [":actions/set-vault-screener-criterion-value", "sharpe", "-50"]);
  await dispatch(page, [":actions/set-vault-screener-criterion-value", "max-drawdown", "100"]);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='vault-screener-tab-pass']").click();
  await expect(page.locator("[data-role='vault-screener-result-row']")).toHaveCount(3);
  expect(requests.length).toBe(before);
});
