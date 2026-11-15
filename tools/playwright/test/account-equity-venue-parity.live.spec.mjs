import { expect, test } from "../support/guarded_test.mjs";
import { dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

const RUN = process.env.RUN_VENUE_PARITY === "1";
const INFO_URL = "https://api.hyperliquid.xyz/info";
const EQUITY_PANEL = "[data-parity-id='account-equity']";

const ANCHORS = [
  {
    address: "0x68bd85dc5c94a5511e959d299617eb19cd26c298",
    note: "classic, base dex only, one cross short"
  },
  {
    address: "0xb9aebb46919bccbf210537a1f2173690d9ee7af7",
    note: "classic, empty base dex, whole book on xyz"
  }
];

async function info(request, body) {
  const response = await request.post(INFO_URL, {
    data: body,
    headers: { "Content-Type": "application/json" }
  });
  expect(response.ok(), `POST /info ${JSON.stringify(body)} -> ${response.status()}`).toBeTruthy();
  return response.json();
}

const num = (value) => {
  const parsed = Number.parseFloat(value);
  return Number.isFinite(parsed) ? parsed : 0;
};

function tokenNameByIndex(spotMeta, index) {
  const token = (spotMeta?.tokens ?? []).find((entry) => entry.index === index);
  return token?.name ?? null;
}

function tokenUsdPrice(tokenName, spotMeta, spotCtxs) {
  if (!tokenName) return null;
  if (tokenName.toUpperCase().startsWith("USD")) return 1;

  const tokens = spotMeta?.tokens ?? [];
  const tokenIndex = tokens.find((entry) => entry.name === tokenName)?.index;
  const usdcIndex = tokens.find((entry) => entry.name === "USDC")?.index;
  if (tokenIndex == null || usdcIndex == null) return null;

  const pair = (spotMeta?.universe ?? []).find(
    (entry) => entry.tokens?.[0] === tokenIndex && entry.tokens?.[1] === usdcIndex
  );
  if (!pair) return null;

  const ctx = (spotCtxs ?? []).find((entry) => entry.coin === pair.name);
  const mark = num(ctx?.markPx);
  return mark > 0 ? mark : null;
}

function venueRows(perDex, spotUsd) {
  let accountValue = 0;
  let crossAccountValue = 0;
  let crossTotalNtlPos = 0;
  let maintenance = 0;
  let unrealizedPnl = 0;

  for (const { state, price } of perDex) {
    accountValue += num(state?.marginSummary?.accountValue) * price;
    crossAccountValue += num(state?.crossMarginSummary?.accountValue) * price;
    crossTotalNtlPos += num(state?.crossMarginSummary?.totalNtlPos) * price;
    maintenance += num(state?.crossMaintenanceMarginUsed) * price;
    for (const row of state?.assetPositions ?? []) {
      unrealizedPnl += num(row?.position?.unrealizedPnl);
    }
  }

  return {
    accountValue: spotUsd + accountValue,
    spot: spotUsd,
    perps: accountValue,
    balance: accountValue - unrealizedPnl,
    unrealizedPnl,
    crossMarginRatio: maintenance / (crossAccountValue + 1e-8),
    maintenance,
    crossAccountLeverage: crossTotalNtlPos / (crossAccountValue + 1e-8),
    markedExposure: Math.abs(crossTotalNtlPos) + Math.abs(spotUsd)
  };
}

async function accountMode(request, address) {
  const abstraction = await info(request, { type: "userAbstraction", user: address });
  return typeof abstraction === "string" ? abstraction : abstraction?.type;
}

async function snapshotVenue(request, address) {
  const [perpDexs, [spotMeta, spotCtxs], spotState, base, vaultEquities] = await Promise.all([
    info(request, { type: "perpDexs" }),
    info(request, { type: "spotMetaAndAssetCtxs" }),
    info(request, { type: "spotClearinghouseState", user: address }),
    info(request, { type: "clearinghouseState", user: address }),
    info(request, { type: "userVaultEquities", user: address })
  ]);

  const dexNames = (perpDexs ?? []).map((dex) => dex?.name).filter(Boolean);
  const namedStates = await Promise.all(
    dexNames.map((name) => info(request, { type: "clearinghouseState", user: address, dex: name }))
  );

  const active = dexNames
    .map((name, index) => ({ name, state: namedStates[index] }))
    .filter(({ state }) =>
      num(state?.marginSummary?.accountValue) !== 0 || (state?.assetPositions ?? []).length > 0
    );
  const metas = await Promise.all(
    active.map(({ name }) => info(request, { type: "meta", dex: name }))
  );

  const perDex = [{ dex: "", state: base, price: 1 }];
  active.forEach(({ name, state }, index) => {
    const tokenName = tokenNameByIndex(spotMeta, metas[index]?.collateralToken);
    const price = tokenUsdPrice(tokenName, spotMeta, spotCtxs);
    expect(price, `no USD price for ${name}'s collateral token ${tokenName}`).toBeTruthy();
    perDex.push({ dex: name, state, price });
  });

  const spotUsd = (spotState?.balances ?? []).reduce((total, balance) => {
    const price = tokenUsdPrice(balance?.coin, spotMeta, spotCtxs) ?? 0;
    return total + num(balance?.total) * price;
  }, 0);

  return {
    ...venueRows(perDex, spotUsd),
    vaults: (vaultEquities ?? []).reduce((total, row) => total + num(row?.equity), 0)
  };
}

async function readPanelRows(page, selector) {
  return page.evaluate((panelSelector) => {
    const panel = document.querySelector(panelSelector);
    if (!panel) throw new Error("account equity panel not rendered");
    const rows = {};
    for (const value of panel.querySelectorAll("span.num")) {
      const row = value.parentElement;
      const label = row?.querySelector("span")?.textContent?.trim();
      if (label) rows[label] = value.textContent.trim();
    }
    return rows;
  }, selector);
}

const money = (value) => Number.parseFloat(String(value).replace(/[$,+]/g, ""));
const percent = (value) => Number.parseFloat(String(value).replace(/[%,]/g, ""));
const leverage = (value) => Number.parseFloat(String(value).replace(/[x,]/g, ""));

function near(actual, expected, exposure, label) {
  const tolerance = Math.max(0.05, Math.abs(exposure) * 2e-4);
  expect(
    Math.abs(actual - expected),
    `${label}: panel ${actual}, venue ${expected} (tolerance ${tolerance})`
  ).toBeLessThanOrEqual(tolerance);
}

function nearRatio(actual, expected, label) {
  const tolerance = Math.max(0.01, Math.abs(expected) * 1e-3);
  expect(
    Math.abs(actual - expected),
    `${label}: panel ${actual}, venue ${expected} (tolerance ${tolerance})`
  ).toBeLessThanOrEqual(tolerance);
}

test.describe("classic account equity against the live venue", () => {
  test.skip(!RUN, "set RUN_VENUE_PARITY=1 to run this against the public API");

  for (const anchor of ANCHORS) {
    test(`matches the venue for ${anchor.address} (${anchor.note})`, async ({ page, request }) => {
      const mode = await accountMode(request, anchor.address);
      test.skip(
        mode === "unifiedAccount" || mode === "portfolioMargin",
        `${anchor.address} is now ${mode}; it no longer exercises the classic panel`
      );

      await page.setViewportSize({ width: 1600, height: 900 });
      await visitRoute(page, "/trade");
      await dispatch(page, [":actions/start-spectate-mode", anchor.address]);
      await waitForIdle(page, { quietMs: 1_500, timeoutMs: 30_000, pollMs: 100 });

      const panel = page.locator(EQUITY_PANEL).first();
      await expect(panel).toBeVisible();
      const rendered = await readPanelRows(page, EQUITY_PANEL);
      const text = await panel.innerText();

      const rows = await snapshotVenue(request, anchor.address);

      const drift = rows.markedExposure;
      near(money(rendered["Perps"]), rows.perps, drift, "Perps");
      near(money(rendered["Balance"]), rows.balance, drift, "Balance");
      near(money(rendered["Spot"]), rows.spot, drift, "Spot");
      near(money(rendered["Vaults"]), rows.vaults, 0, "Vaults");
      near(
        money(rendered["Total Account Value"]) - money(rendered["Vaults"]),
        rows.accountValue,
        drift,
        "Total Account Value - Vaults = venue Spot + Perps"
      );
      near(money(rendered["Maintenance Margin"]), rows.maintenance, drift, "Maintenance Margin");
      nearRatio(
        percent(rendered["Cross Margin Ratio"]),
        rows.crossMarginRatio * 100,
        "Cross Margin Ratio"
      );
      nearRatio(
        leverage(rendered["Cross Account Leverage"]),
        rows.crossAccountLeverage,
        "Cross Account Leverage"
      );

      near(
        money(rendered["Balance"]),
        money(rendered["Perps"]) - money(rendered["Unrealized PNL"]),
        0,
        "Balance = Perps - Unrealized PNL"
      );
      near(
        money(rendered["Total Account Value"]),
        money(rendered["Spot"]) + money(rendered["Perps"]) + money(rendered["Vaults"]),
        0,
        "Total Account Value = Spot + Perps + Vaults"
      );

      if (Math.abs(rows.perps) > 1) {
        expect(text).not.toContain("$0.00");
        expect(text).not.toContain("0.00x");
      }
    });
  }
});
