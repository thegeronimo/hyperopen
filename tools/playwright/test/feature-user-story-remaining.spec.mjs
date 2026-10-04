import { expect, test } from "../support/guarded_test.mjs";
import { dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

const OWNER_ADDRESS = "0x1234567890abcdef1234567890abcdef12345678";
const AGENT_ADDRESS = "0xabcabcabcabcabcabcabcabcabcabcabcabcabca";
const SUBACCOUNT_ADDRESS = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd";
const WATCHLIST_ADDRESS = "0x4444444444444444444444444444444444444444";
const IMPORTED_WATCHLIST_ADDRESS = "0x5555555555555555555555555555555555555555";
const VALIDATOR_ADDRESS = "0x1111111111111111111111111111111111111111";
const VAULT_ADDRESS = "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303";
const LEADER_ADDRESS = "0x677d00000000000000000000000000000008a4e7";

const T0 = Date.UTC(2025, 4, 1);
const T1 = Date.UTC(2025, 7, 1);
const T2 = Date.UTC(2025, 10, 1);
const T3 = Date.UTC(2026, 1, 18);

function kw(value) {
  return { __hyperopenKeyword: value };
}

async function setAppState(page, updates) {
  await page.evaluate((nextUpdates) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);

    const toClj = (value) => {
      if (value === null || value === undefined) {
        return null;
      }

      if (Array.isArray(value)) {
        return c.PersistentVector.fromArray(value.map(toClj), true);
      }

      if (typeof value === "object") {
        if (Object.hasOwn(value, "__hyperopenKeyword")) {
          return keyword(value.__hyperopenKeyword);
        }

        const pairs = [];
        for (const [key, nested] of Object.entries(value)) {
          pairs.push(keyword(key), toClj(nested));
        }
        return c.PersistentArrayMap.fromArray(pairs, true);
      }

      return value;
    };

    let nextState = c.deref(store);
    for (const [path, value] of nextUpdates) {
      nextState = c.assoc_in(nextState, kwPath(...path), toClj(value));
    }

    c.reset_BANG_(store, nextState);
    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  }, updates);

  await waitForIdle(page, { quietMs: 150, timeoutMs: 5_000, pollMs: 50 });
}

async function seedConnectedOwner(page) {
  await setAppState(page, [
    [["wallet", "connected?"], true],
    [["wallet", "address"], OWNER_ADDRESS],
    [["account", "mode"], kw("classic")]
  ]);
}

async function seedApiWalletRoute(page) {
  await setAppState(page, [
    [["wallet", "connected?"], true],
    [["wallet", "address"], OWNER_ADDRESS],
    [["api-wallets", "extra-agents"], []],
    [["api-wallets", "default-agent-row"], null],
    [["api-wallets", "owner-webdata2"], null],
    [["api-wallets", "server-time-ms"], Date.UTC(2026, 5, 22, 12, 0, 0)],
    [["api-wallets", "loading", "extra-agents?"], false],
    [["api-wallets", "loading", "default-agent?"], false],
    [["api-wallets", "errors", "extra-agents"], null],
    [["api-wallets", "errors", "default-agent"], null],
    [["api-wallets-ui", "form-error"], null]
  ]);
}

async function seedApiWalletRow(page) {
  await setAppState(page, [
    [
      ["api-wallets", "extra-agents"],
      [
        {
          "row-kind": kw("named"),
          name: "Desk API",
          "approval-name": "Desk API",
          address: AGENT_ADDRESS,
          "valid-until-ms": Date.UTC(2026, 6, 22, 12, 0, 0)
        }
      ]
    ]
  ]);
}

async function seedFundingComparisonRows(page) {
  await setAppState(page, [
    [
      ["funding-comparison", "predicted-fundings"],
      [
        [
          "BTC",
          [
            ["HlPerp", { fundingRate: "0.0001" }],
            ["BinPerp", { fundingRate: "0.0004", fundingIntervalHours: "8" }],
            ["BybitPerp", { fundingRate: "0.0002", fundingIntervalHours: "8" }]
          ]
        ],
        [
          "ETH",
          [
            ["HlPerp", { fundingRate: "-0.00005" }],
            ["BinPerp", { fundingRate: "0.0001", fundingIntervalHours: "8" }],
            ["BybitPerp", { fundingRate: "0.0003", fundingIntervalHours: "8" }]
          ]
        ]
      ]
    ],
    [["funding-comparison", "error"], null],
    [["funding-comparison", "loaded-at-ms"], Date.UTC(2026, 5, 22, 12, 10, 0)],
    [["funding-comparison-ui", "loading?"], false],
    [["funding-comparison-ui", "query"], ""],
    [["funding-comparison-ui", "timeframe"], kw("8hour")],
    [["funding-comparison-ui", "sort"], { column: kw("coin"), direction: kw("asc") }]
  ]);
}

async function seedNotificationToast(page) {
  await setAppState(page, [
    [
      ["ui", "toasts"],
      [
        {
          id: "notice-1",
          kind: kw("success"),
          headline: "Order submitted",
          subline: "BTC buy",
          detail: "Accepted by exchange"
        }
      ]
    ]
  ]);
}

async function seedStakingConnectedState(page) {
  await setAppState(page, [
    [["wallet", "connected?"], true],
    [["wallet", "address"], OWNER_ADDRESS],
    [
      ["staking", "validator-summaries"],
      [
        {
          validator: VALIDATOR_ADDRESS,
          name: "Alpha Validator",
          description: "Deterministic validator fixture",
          stake: "1000",
          "is-active?": true,
          commission: "0.05",
          stats: {
            week: {
              "uptime-fraction": "0.99",
              "predicted-apr": "0.08",
              "sample-count": "12"
            }
          }
        }
      ]
    ],
    [
      ["staking", "delegator-summary"],
      {
        "total-staked": "1000",
        delegated: "25",
        undelegated: "10",
        "total-pending-withdrawal": "2"
      }
    ],
    [["staking", "delegations"], [{ validator: VALIDATOR_ADDRESS, amount: "25" }]],
    [["staking", "loading"], {}],
    [["staking", "errors"], {}],
    [
      ["spot", "clearinghouse-state", "balances"],
      [{ coin: "HYPE", total: "12", hold: "1" }]
    ],
    [["staking-ui", "active-tab"], kw("validator-performance")],
    [["staking-ui", "validator-timeframe"], kw("week")],
    [["staking-ui", "validator-sort"], { column: kw("stake"), direction: kw("desc") }]
  ]);
}

async function seedSubaccountsState(page) {
  await setAppState(page, [
    [["wallet", "connected?"], true],
    [["wallet", "address"], OWNER_ADDRESS],
    [["account", "mode"], kw("classic")],
    [
      ["account-context", "subaccounts", "owner-mode"],
      { owner: OWNER_ADDRESS.toLowerCase(), mode: kw("classic") }
    ],
    [
      ["account-context", "subaccounts", "owner-snapshot"],
      {
        owner: OWNER_ADDRESS.toLowerCase(),
        "clearinghouse-state": {
          withdrawable: "300.61686499",
          marginSummary: { accountValue: "300.61686499" }
        },
        "spot-state": { balances: [{ coin: "USDC", total: "358.56" }] },
        "loading?": false,
        error: null
      }
    ],
    [
      ["account-context", "subaccounts", "rows"],
      [
        {
          name: "test",
          master: OWNER_ADDRESS.toLowerCase(),
          subAccountUser: SUBACCOUNT_ADDRESS.toLowerCase(),
          clearinghouseState: { marginSummary: { accountValue: "300.61686499" } },
          spotState: { balances: [{ coin: "USDC", total: "0" }] }
        }
      ]
    ],
    [["account-context", "subaccounts", "status"], kw("loaded")],
    [["account-context", "subaccounts", "loaded-for-owner"], OWNER_ADDRESS.toLowerCase()],
    [["account-context", "subaccounts", "error"], null],
    [["account-context", "subaccounts", "create-popover-open?"], false],
    [["account-context", "subaccounts", "create-name"], ""],
    [["account-context", "subaccounts", "renaming-address"], null],
    [["account-context", "subaccounts", "rename-name"], ""]
  ]);
}

async function stubVaultDetailFixture(page) {
  const summary = {
    accountValueHistory: [
      [T0, 100],
      [T1, 125],
      [T2, 120],
      [T3, 140]
    ],
    pnlHistory: [
      [T0, 0],
      [T1, 25],
      [T2, 20],
      [T3, 40]
    ]
  };

  await page.route("https://stats-data.hyperliquid.xyz/Mainnet/vaults", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          apr: "0.12",
          summary: {
            name: "Transfer Vault",
            vaultAddress: VAULT_ADDRESS,
            leader: LEADER_ADDRESS,
            tvl: "321.5",
            isClosed: false,
            relationship: { type: "normal" },
            createTimeMillis: String(T0)
          }
        }
      ])
    });
  });

  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestType = payload?.type;
    const requestVaultAddress = String(
      payload?.vaultAddress || payload?.user || ""
    ).toLowerCase();

    if (requestType === "vaultSummaries") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([])
      });
      return;
    }

    if (requestType === "vaultDetails" && requestVaultAddress === VAULT_ADDRESS) {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          name: "Transfer Vault",
          vaultAddress: VAULT_ADDRESS,
          leader: LEADER_ADDRESS,
          description: "Deterministic vault transfer fixture",
          apr: "0.12",
          portfolio: [
            ["allTime", summary],
            ["oneYear", summary]
          ],
          followers: [],
          relationship: { type: "normal" },
          allowDeposits: true,
          alwaysCloseOnWithdraw: false
        })
      });
      return;
    }

    if (requestType === "webData2") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          clearinghouseState: {
            withdrawable: "500.00",
            marginSummary: { accountValue: "500.00", totalMarginUsed: "0" }
          }
        })
      });
      return;
    }

    await route.continue();
  });
}

test("api wallet route supports generate, authorize, and remove confirmation @regression", async ({ page }) => {
  await visitRoute(page, "/api");
  await seedApiWalletRoute(page);

  await expect(page.locator("[data-parity-id='api-wallets-root']")).toBeVisible();
  await expect(page.locator("[data-role='api-wallets-authorize-button']")).toBeDisabled();

  await page.locator("#api-wallet-name").fill("Desk API");
  await page.locator("[data-role='api-wallets-generate-button']").click();

  await expect(page.locator("#api-wallet-address")).toHaveValue(/^0x[0-9a-f]{40}$/i, {
    timeout: 10_000
  });
  await expect(page.locator("[data-role='api-wallets-authorize-button']")).toBeEnabled();
  await page.locator("[data-role='api-wallets-authorize-button']").click();

  const authorizeModal = page.locator("[data-role='api-wallets-modal']");
  await expect(authorizeModal).toBeVisible();
  await expect(authorizeModal).toContainText("Authorize API Wallet");
  await expect(authorizeModal).toContainText("Generated Private Key");
  await expect(authorizeModal.locator("[data-role='api-wallets-modal-confirm']")).toBeEnabled();
  await authorizeModal.getByRole("button", { name: "Cancel" }).click();
  await expect(authorizeModal).toHaveCount(0);

  await seedApiWalletRow(page);
  const row = page.locator("[data-role='api-wallets-table-row']").filter({ hasText: "Desk API" });
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "Remove" }).click();

  const removeModal = page.locator("[data-role='api-wallets-modal']");
  await expect(removeModal).toBeVisible();
  await expect(removeModal).toContainText("Remove API Wallet");
  await expect(removeModal).toContainText(AGENT_ADDRESS);
  await expect(removeModal.locator("[data-role='api-wallets-modal-confirm']")).toBeEnabled();
});

test("funding comparison route filters rows, switches timeframe, and exposes errors @regression", async ({
  page
}) => {
  // Stub the live predicted-fundings fetch so the seeded rows below stay
  // authoritative. With real network access the live fetch resolves after the
  // seed and overwrites it with the full ~212-coin universe, which made the
  // row-count assertion environment-dependent. Returning an empty success
  // (error stays nil, so the "N coins shown" summary still renders) lets the
  // seed be the single source of funding rows regardless of network.
  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const payload = route.request().postDataJSON();
    if (payload?.type === "predictedFundings") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: "[]"
      });
      return;
    }
    await route.continue();
  });

  await visitRoute(page, "/funding-comparison");
  await seedFundingComparisonRows(page);

  await expect(page.locator("[data-parity-id='funding-comparison-root']")).toBeVisible();
  await expect(page.locator("[data-role='funding-comparison-row']")).toHaveCount(2);
  await expect(page.locator("[data-role='funding-comparison-summary']")).toContainText("2 coins shown");

  await page.locator("#funding-comparison-search").fill("ETH");
  await expect(page.locator("[data-role='funding-comparison-row']")).toHaveCount(1);
  await expect(page.locator("[data-role='funding-comparison-row']").first()).toContainText("ETH");

  await page.getByRole("button", { name: "Day" }).click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(page.locator("[data-role='funding-comparison-row']")).toHaveCount(1);

  await setAppState(page, [[["funding-comparison", "error"], "Provider unavailable"]]);
  await expect(page.locator("[data-role='funding-comparison-error']")).toContainText(
    "Provider unavailable"
  );
});

test("global notifications render and dismiss in app @regression", async ({ page }) => {
  await visitRoute(page, "/trade");
  await seedNotificationToast(page);

  const region = page.locator("[data-role='global-toast-region']");
  await expect(region).toBeVisible();
  await expect(region.locator("[data-role='global-toast-headline']")).toHaveText(
    "Order submitted"
  );
  await expect(region.locator("[data-role='global-toast-subline']")).toHaveText("BTC buy");
  await expect(region.locator("[data-role='global-toast-detail']")).toContainText(
    "Accepted by exchange"
  );

  await region.locator("[data-role='global-toast-dismiss']").click();
  await expect(page.locator("[data-role='global-toast-region']")).toHaveCount(0);
});

test("trade account tabs switch across outcomes and activity surfaces @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");

  for (const tab of ["outcomes", "open-orders", "twap", "trade-history", "funding-history"]) {
    const tabButton = page.locator(`[data-role='account-info-tab-${tab}']`);
    await expect(tabButton).toBeVisible();
    await tabButton.click();
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
    await expect(tabButton).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator("[data-parity-id='account-tables']")).toBeVisible();
  }
});

test("staking connected state exposes validators and action popovers @regression", async ({ page }) => {
  await visitRoute(page, "/staking");
  await seedStakingConnectedState(page);

  await expect(page.locator("[data-parity-id='staking-root']")).toBeVisible();
  await expect(page.locator("[data-role='staking-action-transfer-button']")).toBeVisible();
  await expect(page.locator("[data-role='staking-action-unstake-button']")).toBeVisible();
  await expect(page.locator("[data-role='staking-action-stake-button']")).toBeVisible();
  await expect(page.locator("[data-role='staking-balance-panel']")).toContainText(
    "Available to Stake"
  );

  const validatorRow = page.locator("[data-role='staking-validator-row']").first();
  await expect(validatorRow).toBeVisible();
  await validatorRow.click();

  await page.locator("[data-role='staking-action-transfer-button']").click();
  await expect(page.locator("[data-role='staking-action-popover']")).toContainText("Transfer HYPE");
  await expect(page.locator("#staking-transfer-amount")).toBeVisible();
  await page.locator("[data-role='staking-transfer-direction-toggle']").click();
  await expect(page.locator("[data-role='staking-transfer-direction-toggle']")).toContainText(
    "Staking Balance"
  );
  await page.getByLabel("Close staking action popover").last().click();
  await expect(page.locator("[data-role='staking-action-popover']")).toHaveCount(0);

  await page.locator("[data-role='staking-action-stake-button']").click();
  await expect(page.locator("[data-role='staking-action-popover']")).toContainText("Stake");
  await expect(page.locator("#staking-delegate-amount")).toBeVisible();
  await page.locator("#staking-delegate-amount").fill("1.25");
  await expect(page.locator("#staking-delegate-amount")).toHaveValue("1.25");
});

test("subaccounts create and rename controls expose editable form state @regression", async ({
  page
}) => {
  await visitRoute(page, "/subAccounts");
  await seedSubaccountsState(page);

  await expect(page.locator("[data-role='subaccounts-open-create-popover']")).toBeEnabled();
  await page.locator("[data-role='subaccounts-open-create-popover']").click();
  await expect(page.locator("[data-role='subaccounts-create-popover']")).toBeVisible();
  await page.locator("[data-role='subaccounts-create-name']").fill("Research desk");
  await expect(page.locator("[data-role='subaccounts-create-name']")).toHaveValue(
    "Research desk"
  );
  await expect(page.locator("[data-role='subaccounts-create-submit']")).toBeEnabled();
  await page.locator("[data-role='subaccounts-create-cancel']").click();
  await expect(page.locator("[data-role='subaccounts-create-popover']")).toHaveCount(0);

  const renameButton = page.locator(`[data-role='subaccounts-rename-${SUBACCOUNT_ADDRESS}']`);
  await expect(renameButton).toBeVisible();
  await renameButton.click();
  await page.locator(`[data-role='subaccounts-rename-name-${SUBACCOUNT_ADDRESS}']`).fill(
    "Renamed desk"
  );
  await expect(page.locator(`[data-role='subaccounts-rename-name-${SUBACCOUNT_ADDRESS}']`))
    .toHaveValue("Renamed desk");
  await expect(page.locator(`[data-role='subaccounts-rename-submit-${SUBACCOUNT_ADDRESS}']`))
    .toBeVisible();
  await page.locator(`[data-role='subaccounts-rename-cancel-${SUBACCOUNT_ADDRESS}']`).click();
  await expect(page.locator(`[data-role='subaccounts-rename-name-${SUBACCOUNT_ADDRESS}']`))
    .toHaveCount(0);
});

test("spectate watchlist import and export controls update saved rows @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");
  await page.locator("[data-role='spectate-mode-open-button']").click();

  const modal = page.locator("[data-role='spectate-mode-modal']");
  await expect(modal).toBeVisible();
  await expect(modal.locator("[data-role='spectate-mode-watchlist-toolbar']")).toBeVisible();
  await expect(modal.locator("[data-role='spectate-mode-watchlist-import']")).toBeEnabled();
  await expect(modal.locator("[data-role='spectate-mode-watchlist-export']")).toBeDisabled();

  await dispatch(page, [
    ":actions/apply-imported-spectate-watchlist",
    {
      type: "hyperopen-spectate-watchlist",
      version: 1,
      entries: [
        { address: WATCHLIST_ADDRESS, label: "Existing watch" },
        { address: IMPORTED_WATCHLIST_ADDRESS, label: "Imported watch" }
      ]
    }
  ]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 5_000, pollMs: 50 });

  await expect(modal.locator("[data-role='spectate-mode-watchlist-row']")).toHaveCount(2);
  await expect(modal.locator("[data-role='spectate-mode-watchlist-row']", {
    hasText: "Imported watch"
  })).toBeVisible();
  await expect(modal.locator("[data-role='spectate-mode-watchlist-export']")).toBeEnabled();

  await modal.locator("[data-role='spectate-mode-watchlist-export']").click();
  await expect(modal.locator("[data-role='spectate-mode-copy-feedback-message']")).toContainText(
    "Exported 2 addresses."
  );
});

test("trade chart indicators menu filters and toggles indicators @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await page.getByRole("button", { name: "Indicators" }).click();
  await expect(page.getByRole("heading", { name: "Indicators" })).toBeVisible();
  await expect(page.locator("#chart-indicators-search")).toBeVisible();

  const volumeButton = page.getByRole("button", { name: "Remove built-in volume indicator" });
  await expect(volumeButton).toHaveAttribute("aria-pressed", "true");
  await volumeButton.click();
  const addVolumeButton = page.getByRole("button", { name: "Add built-in volume indicator" });
  await expect(addVolumeButton).toBeVisible();
  await addVolumeButton.click();
  await expect(page.getByRole("button", { name: "Remove built-in volume indicator" }))
    .toHaveAttribute("aria-pressed", "true");

  await page.locator("#chart-indicators-search").fill("average price");
  const averagePriceButton = page.getByRole("button", { name: "Add Average Price indicator" });
  await expect(averagePriceButton).toBeVisible();
  await averagePriceButton.click();
  await expect(page.getByText("Active Indicators")).toBeVisible();
  await expect(page.getByText("Average Price")).toBeVisible();
});

test("vault detail transfer modal and Monte Carlo tab are reachable @regression", async ({ page }) => {
  await stubVaultDetailFixture(page);
  await visitRoute(page, `/vaults/${VAULT_ADDRESS}`);
  await seedConnectedOwner(page);
  await setAppState(page, [
    [["spot", "clearinghouse-state", "balances"], [{ coin: "USDC", total: "250", hold: "0" }]]
  ]);

  await expect(page.locator("[data-parity-id='vault-detail-root']")).toBeVisible();

  const depositButton = page.getByRole("button", { name: "Deposit" }).first();
  await expect(depositButton).toBeEnabled();
  await depositButton.click();
  await expect(page.locator("[data-role='vault-transfer-modal']")).toContainText("Deposit");
  await expect(page.locator("[data-role='vault-transfer-deposit-lockup-copy']")).toContainText(
    "lock-up period"
  );
  await page.locator("[data-role='vault-transfer-amount-input']").fill("10");
  // Classic accounts fund vault deposits from perps, so spot-only USDC is not spendable.
  await expect(page.locator("[data-role='vault-transfer-status']")).toContainText(
    "Transfer USDC from spot to perps first"
  );
  await expect(page.locator("[data-role='vault-transfer-submit']")).toBeDisabled();
  await setAppState(page, [[["webdata2", "clearinghouseState", "withdrawable"], "100"]]);
  await expect(page.locator("[data-role='vault-transfer-deposit-max']")).toContainText("MAX: 100.00 USDC");
  await expect(page.locator("[data-role='vault-transfer-submit']")).toBeEnabled();
  await page.getByRole("button", { name: "Cancel" }).click();
  await expect(page.locator("[data-role='vault-transfer-modal']")).toHaveCount(0);

  const withdrawButton = page.getByRole("button", { name: "Withdraw" }).first();
  await expect(withdrawButton).toBeEnabled();
  await withdrawButton.click();
  await expect(page.locator("[data-role='vault-transfer-modal']")).toContainText("Withdraw");
  await expect(page.getByLabel("Withdraw All")).toBeVisible();
  await page.locator("[data-role='vault-transfer-amount-input']").fill("5");
  await expect(page.locator("[data-role='vault-transfer-submit']")).toBeEnabled();
  await page.getByRole("button", { name: "Cancel" }).click();

  await page.getByRole("button", { name: /Monte Carlo/ }).click();
  await expect(page.locator("[data-role='vault-monte-carlo']")).toBeVisible();
  await expect(page.locator("[data-role='vault-monte-carlo-controls']")).toBeVisible();
  await expect(page.locator("[data-role='vault-monte-carlo-notice']")).toBeVisible();
});
