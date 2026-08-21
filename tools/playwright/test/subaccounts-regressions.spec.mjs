import { expect, test } from "@playwright/test";
import { debugCall, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

const ownerAddress = "0x1234567890abcdef1234567890abcdef12345678";
const subaccountAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd";
const spectateAddress = "0x7777777777777777777777777777777777777777";

async function seedAccountSurface(page, options = {}) {
  await page.evaluate(({ accountMode, spotMeta }) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    const webdata2 = c.js__GT_clj(
      {
        clearinghouseState: {
          marginSummary: {
            accountValue: "300.61686499"
          }
        }
      },
      opts
    );
    const spot = c.js__GT_clj(
      {
        meta: { tokens: spotMeta },
        "clearinghouse-state": {
          balances: [
            {
              coin: "USDC",
              total: "358.56"
            },
            {
              coin: "USDH",
              token: 12,
              total: "8.28"
            },
            {
              coin: "STAR",
              token: 56,
              total: "0"
            }
          ]
        }
      },
      opts
    );
    const spotBalances = c.js__GT_clj(
      [
        {
          coin: "USDC",
          total: "358.56"
        },
        {
          coin: "USDH",
          token: 12,
          total: "8.28"
        },
        {
          coin: "STAR",
          token: 56,
          total: "0"
        }
      ],
      opts
    );

    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, kwPath("account", "mode"), keyword(accountMode));
    nextState = c.assoc_in(nextState, kwPath("webdata2"), webdata2);
    nextState = c.assoc_in(nextState, kwPath("spot"), spot);
    nextState = c.assoc_in(
      nextState,
      kwPath("spot", "clearinghouse-state", "balances"),
      spotBalances
    );
    c.reset_BANG_(store, nextState);
    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  }, { accountMode: options.accountMode ?? "classic", spotMeta: spotMetaTokens });
}

async function seedSubaccountsState(page, options = {}) {
  const ownerSnapshot = options.ownerSnapshot ?? {
    owner: ownerAddress.toLowerCase(),
    "clearinghouse-state": {
      withdrawable: "300.61686499",
      marginSummary: {
        accountValue: "300.61686499"
      }
    },
    "spot-state": {
      balances: [
        {
          coin: "USDC",
          total: "358.56"
        },
        {
          coin: "USDH",
          token: 12,
          total: "8.28"
        },
        {
          coin: "STAR",
          token: 56,
          total: "0"
        }
      ]
    },
    "loading?": false,
    error: null
  };
  await page.evaluate(({
    ownerAddress: owner,
    subaccountAddress: sub,
    accountMode,
    ownerSnapshot,
    selectedAddress,
    subaccountSpotUsdc,
    spotMeta
  }) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const ownerModeRecord = c.PersistentArrayMap.fromArray(
      [
        keyword("owner"),
        String(owner).toLowerCase(),
        keyword("mode"),
        keyword(accountMode)
      ],
      true
    );
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    const rows = c.js__GT_clj(
      [
        {
          name: "test",
          master: String(owner).toLowerCase(),
          subAccountUser: String(sub).toLowerCase(),
          clearinghouseState: {
            marginSummary: {
              accountValue: "300.61686499"
            }
          },
          spotState: {
            balances: [
              {
                coin: "USDC",
                total: subaccountSpotUsdc
              },
              {
                coin: "MEOW",
                token: 34,
                total: "0.02"
              }
            ]
          }
        }
      ],
      opts
    );
    const webdata2 = c.js__GT_clj(
      {
        clearinghouseState: {
          marginSummary: {
            accountValue: "300.61686499"
          }
        }
      },
      opts
    );
    const spot = c.js__GT_clj(
      {
        meta: { tokens: spotMeta },
        "clearinghouse-state": {
          balances: [
            {
              coin: "USDC",
              total: "358.56"
            },
            {
              coin: "USDH",
              token: 12,
              total: "8.28"
            },
            {
              coin: "STAR",
              token: 56,
              total: "0"
            }
          ]
        }
      },
      opts
    );
    const spotBalances = c.js__GT_clj(
      [
        {
          coin: "USDC",
          total: "358.56"
        },
        {
          coin: "USDH",
          token: 12,
          total: "8.28"
        },
        {
          coin: "STAR",
          token: 56,
          total: "0"
        }
      ],
      opts
    );
    const ownerSnapshotClj = c.js__GT_clj(ownerSnapshot, opts);

    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, kwPath("wallet", "address"), owner);
    nextState = c.assoc_in(nextState, kwPath("account", "mode"), keyword(accountMode));
    nextState = c.assoc_in(
      nextState,
      kwPath("account-context", "subaccounts", "owner-mode"),
      ownerModeRecord
    );
    nextState = c.assoc_in(
      nextState,
      kwPath("account-context", "subaccounts", "owner-snapshot"),
      ownerSnapshotClj
    );
    nextState = c.assoc_in(nextState, kwPath("account-context", "subaccounts", "rows"), rows);
    nextState = c.assoc_in(nextState, kwPath("account-context", "subaccounts", "status"), keyword("loaded"));
    nextState = c.assoc_in(
      nextState,
      kwPath("account-context", "subaccounts", "loaded-for-owner"),
      String(owner).toLowerCase()
    );
    nextState = c.assoc_in(nextState, kwPath("account-context", "subaccounts", "error"), null);
    nextState = c.assoc_in(
      nextState,
      kwPath("account-context", "subaccounts", "selected-address"),
      selectedAddress ? String(selectedAddress).toLowerCase() : null
    );
    nextState = c.assoc_in(nextState, kwPath("account-context", "subaccounts", "selection-loaded?"), true);
    nextState = c.assoc_in(nextState, kwPath("account-context", "subaccounts", "transfer-amount"), "");
    nextState = c.assoc_in(
      nextState,
      kwPath("account-context", "subaccounts", "transfer-direction"),
      keyword("deposit")
    );
    nextState = c.assoc_in(
      nextState,
      kwPath("account-context", "subaccounts", "transfer-account"),
      keyword("trading")
    );
    nextState = c.assoc_in(
      nextState,
      kwPath("account-context", "subaccounts", "transfer-account-menu-open?"),
      false
    );
    nextState = c.assoc_in(nextState, kwPath("account-context", "subaccounts", "transfer-token"), "USDC");
    nextState = c.assoc_in(
      nextState,
      kwPath("account-context", "subaccounts", "transfer-token-menu-open?"),
      false
    );
    nextState = c.assoc_in(nextState, kwPath("webdata2"), webdata2);
    nextState = c.assoc_in(nextState, kwPath("spot"), spot);
    nextState = c.assoc_in(
      nextState,
      kwPath("spot", "clearinghouse-state", "balances"),
      spotBalances
    );

    c.reset_BANG_(store, nextState);
    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  }, {
    ownerAddress,
    subaccountAddress,
    accountMode: options.accountMode ?? "classic",
    ownerSnapshot,
    selectedAddress: options.selectedAddress ?? null,
    subaccountSpotUsdc: options.subaccountSpotUsdc ?? "0",
    spotMeta: spotMetaTokens
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await seedAccountSurface(page, { accountMode: options.accountMode ?? "classic" });
}

// Production `spotMeta` shape: Hyperliquid returns :name, :index, and :tokenId
// separately, and spot balance rows carry only the numeric index. The app joins
// the two to build the `NAME:0xhash` wire token id the exchange requires, so
// these specs must serve a real token table rather than let the live endpoint
// answer (an un-intercepted spotMeta races the seeded state and silently
// un-resolves every fixture token).
const spotMetaTokens = [
  { name: "USDC", index: 0, tokenId: "0x6d1e7cde53ba9467b783cb7c530ce054", szDecimals: 8, weiDecimals: 8 },
  { name: "USDH", index: 12, tokenId: "0xabc", szDecimals: 2, weiDecimals: 6 },
  { name: "MEOW", index: 34, tokenId: "0xdef", szDecimals: 0, weiDecimals: 2 },
  { name: "STAR", index: 56, tokenId: "0x456", szDecimals: 2, weiDecimals: 6 }
];

async function interceptSubaccountsApi(page, options = {}) {
  const masterAddress = options.masterAddress ?? ownerAddress;
  const calls = options.calls;

  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    if (payload?.type === "subAccounts") {
      calls?.push(payload);
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([
          {
            name: "test",
            master: masterAddress.toLowerCase(),
            subAccountUser: subaccountAddress.toLowerCase(),
            clearinghouseState: {
              marginSummary: {
                accountValue: "300.61686499"
              }
            },
            spotState: {
              balances: [
                {
                  coin: "USDC",
                  total: "0"
                },
                {
                  coin: "MEOW",
                  token: 34,
                  total: "0.02"
                }
              ]
            }
          }
        ])
      });
      return;
    }

    if (payload?.type === "spotMeta") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ tokens: spotMetaTokens, universe: [] })
      });
      return;
    }

    await route.continue();
  });
}

test("subaccounts page loads the spectated master read-only @regression", async ({ page }) => {
  const subaccountRequests = [];
  await interceptSubaccountsApi(page, {
    masterAddress: spectateAddress,
    calls: subaccountRequests
  });

  for (const width of [375, 768, 1280, 1440]) {
    await page.setViewportSize({ width, height: 720 });
    await visitRoute(page, "/subAccounts");
    await debugCall(page, "dispatch", [":actions/start-spectate-mode", spectateAddress]);
    await debugCall(page, "dispatch", [":actions/load-subaccounts-route", "/subAccounts"]);
    await waitForIdle(page, { quietMs: 350, timeoutMs: 8_000, pollMs: 50 });

    await expect
      .poll(() => subaccountRequests.map((request) => String(request.user).toLowerCase()), {
        timeout: 8_000
      })
      .toContain(spectateAddress);

    await expect(page.locator("[data-role='subaccounts-read-only-message']"))
      .toContainText("Spectate Mode is read-only");
    await expect(page.locator("[data-role='subaccounts-copy-master']"))
      .toHaveAttribute("title", "Copy address");
    await expect(page.locator("[data-role='subaccounts-master-row']"))
      .toContainText(spectateAddress);
    await expect(page.locator(`[data-role="subaccounts-row-${subaccountAddress}"]`))
      .toContainText("test");
    await expect(page.locator("[data-role='subaccounts-open-create-popover']")).toBeDisabled();
    await expect(page.locator("[data-role='subaccounts-select-master']")).toBeDisabled();
    await expect(page.locator(`[data-role="subaccounts-select-${subaccountAddress}"]`))
      .toBeDisabled();
    await expect(page.locator(`[data-role="subaccounts-rename-${subaccountAddress}"]`))
      .toHaveCount(0);
    await expect(page.locator(`[data-role="subaccounts-transfer-${subaccountAddress}"]`))
      .toHaveCount(0);
  }
});

test("selected subaccount does not duplicate master balance or enable empty master deposit @regression", async ({
  page
}) => {
  await interceptSubaccountsApi(page);

  await page.setViewportSize({ width: 1280, height: 720 });
  await visitRoute(page, "/subAccounts");
  await seedSubaccountsState(page, {
    selectedAddress: subaccountAddress,
    ownerSnapshot: {
      owner: ownerAddress.toLowerCase(),
      "clearinghouse-state": {
        withdrawable: "0",
        marginSummary: {
          accountValue: "0"
        }
      },
      "spot-state": {
        balances: [
          {
            coin: "USDC",
            total: "0",
            hold: "0"
          }
        ]
      },
      "loading?": false,
      error: null
    }
  });
  await seedAccountSurface(page, { accountMode: "classic" });

  await expect(page.locator("[data-role='subaccounts-master-row']")).toContainText("$0.00");
  await expect(page.locator("[data-role='subaccounts-master-row']")).not.toContainText("$300.62");
  await expect(page.locator(`[data-role="subaccounts-row-${subaccountAddress}"]`))
    .toContainText("$300.62");

  await page.locator(`[data-role="subaccounts-transfer-${subaccountAddress}"]`).click();
  await expect(page.locator(`[data-role="subaccounts-transfer-source-${subaccountAddress}"]`))
    .toContainText("Master Account");
  await expect(page.locator(`[data-role="subaccounts-transfer-max-${subaccountAddress}"]`))
    .toContainText("MAX: 0 USDC");
  await expect(page.locator(`[data-role="subaccounts-transfer-submit-${subaccountAddress}"]`))
    .toBeDisabled();
});

async function readTransferGeometry(page) {
  return page.evaluate((subaccountAddress) => {
    const row = document.querySelector(`[data-role="subaccounts-row-${subaccountAddress}"]`);
    const popover = document.querySelector(
      `[data-role="subaccounts-transfer-popover-${subaccountAddress}"]`
    );
    const amount = document.querySelector(
      `[data-role="subaccounts-transfer-amount-${subaccountAddress}"]`
    );

    if (!row || !popover || !amount) {
      throw new Error("Subaccount transfer geometry unavailable");
    }

    const rowRect = row.getBoundingClientRect();
    const popoverRect = popover.getBoundingClientRect();
    const amountRect = amount.getBoundingClientRect();
    const consoleNode = document.querySelector(`[data-role="subaccounts-console"]`);
    const direction = document.querySelector(
      `[data-role="subaccounts-transfer-direction-${subaccountAddress}"]`
    );
    const token = document.querySelector(
      `[data-role="subaccounts-transfer-token-${subaccountAddress}"]`
    );
    const readBorderWidths = (node) => {
      const styles = window.getComputedStyle(node);
      return {
        top: styles.borderTopWidth,
        right: styles.borderRightWidth,
        bottom: styles.borderBottomWidth,
        left: styles.borderLeftWidth
      };
    };
    const viewportWidth = window.innerWidth;

    return {
      popoverInsideRow: row.contains(popover),
      popoverInsideConsole: consoleNode ? consoleNode.contains(popover) : null,
      rowHeight: rowRect.height,
      rowBottom: rowRect.bottom,
      popoverWidth: popoverRect.width,
      popoverLeft: popoverRect.left,
      popoverRight: popoverRect.right,
      popoverTop: popoverRect.top,
      popoverBottom: popoverRect.bottom,
      amountHeight: amountRect.height,
      amountWidth: amountRect.width,
      directionBorders: readBorderWidths(direction),
      tokenBorders: readBorderWidths(token),
      amountBorders: readBorderWidths(amount),
      viewportWidth
    };
  }, subaccountAddress);
}

test("subaccounts transfer opens a compact send tokens popover @regression", async ({ page }) => {
  await interceptSubaccountsApi(page);

  for (const width of [375, 768, 1280, 1440]) {
    await page.setViewportSize({ width, height: 720 });
    await visitRoute(page, "/subAccounts");
    await seedSubaccountsState(page);

    const trigger = page.locator(`[data-role="subaccounts-transfer-${subaccountAddress}"]`);
    await expect(trigger).toBeVisible();
    await trigger.click();

    const popover = page.locator(
      `[data-role="subaccounts-transfer-popover-${subaccountAddress}"]`
    );
    await expect(popover).toBeVisible();
    await expect(popover).toContainText("Send Tokens");
    await expect(popover).toContainText("Transfer tokens between sub-account and master account.");
    await expect(page.locator(`[data-role="subaccounts-transfer-source-${subaccountAddress}"]`))
      .toContainText("Master Account");
    await expect(page.locator(`[data-role="subaccounts-transfer-destination-${subaccountAddress}"]`))
      .toContainText("test");
    await expect(page.locator(`[data-role="subaccounts-transfer-flow-arrow-${subaccountAddress}"]`))
      .toHaveText("->");
    await expect(page.locator(`[data-role="subaccounts-transfer-token-${subaccountAddress}"]`))
      .toContainText("USDC");
    await expect(page.locator(`[data-role="subaccounts-transfer-max-${subaccountAddress}"]`))
      .toContainText(/^MAX: [0-9,.]+(?:\.\d+)? USDC$/);

    const geometry = await readTransferGeometry(page);
    expect(geometry.popoverInsideRow).toBe(false);
    expect(geometry.popoverInsideConsole).toBe(false);
    expect(geometry.rowHeight).toBeLessThanOrEqual(96);
    expect(geometry.popoverWidth).toBeLessThanOrEqual(Math.min(width * 0.92, 520) + 2);
    expect(geometry.popoverLeft).toBeGreaterThanOrEqual(-1);
    expect(geometry.popoverRight).toBeLessThanOrEqual(geometry.viewportWidth + 1);
    expect(geometry.popoverTop).toBeGreaterThanOrEqual(-1);
    expect(geometry.popoverTop).toBeGreaterThanOrEqual(geometry.rowBottom);
    expect(geometry.amountHeight).toBeGreaterThanOrEqual(20);
    expect(geometry.amountWidth).toBeGreaterThan(80);
    expect(Object.values(geometry.directionBorders)).toEqual(["0px", "0px", "0px", "0px"]);
    expect(Object.values(geometry.tokenBorders)).toEqual(["0px", "0px", "0px", "0px"]);
    expect(Object.values(geometry.amountBorders)).toEqual(["0px", "0px", "0px", "0px"]);

    await page.locator(`[data-role="subaccounts-transfer-toggle-direction-${subaccountAddress}"]`).click();
    await expect(page.locator(`[data-role="subaccounts-transfer-source-${subaccountAddress}"]`))
      .toContainText("test");
    await expect(page.locator(`[data-role="subaccounts-transfer-destination-${subaccountAddress}"]`))
      .toContainText("Master Account");
    await expect(page.locator(`[data-role="subaccounts-transfer-flow-arrow-${subaccountAddress}"]`))
      .toHaveText("->");

    const accountTrigger = page.locator(`[data-role="subaccounts-transfer-direction-${subaccountAddress}"]`);
    await expect(accountTrigger).toHaveAttribute("aria-haspopup", "listbox");
    await accountTrigger.click();
    const accountMenu = page.locator(`[data-role="subaccounts-transfer-account-menu-${subaccountAddress}"]`);
    await expect(accountMenu).toBeVisible();
    await expect(accountMenu).toContainText("Trading Account");
    await expect(accountMenu).toContainText("Spot Account");
    await page.locator(`[data-role="subaccounts-transfer-account-option-${subaccountAddress}-spot"]`).click();
    await expect(accountTrigger).toContainText("Spot Account");
    await expect(accountTrigger).toHaveAttribute("aria-expanded", "false");

    await page.locator(`[data-role="subaccounts-transfer-token-${subaccountAddress}"]`).click();
    const tokenMenu = page.locator(`[data-role="subaccounts-transfer-token-menu-${subaccountAddress}"]`);
    await expect(tokenMenu).toBeVisible();
    await expect(tokenMenu).toContainText("MEOW");
    await expect(tokenMenu).toContainText("0.02");
    await page.locator(`[data-role="subaccounts-transfer-token-option-${subaccountAddress}-MEOW:0xdef"]`).click();
    await expect(page.locator(`[data-role="subaccounts-transfer-token-${subaccountAddress}"]`))
      .toContainText("MEOW");
    await expect(page.locator(`[data-role="subaccounts-transfer-max-${subaccountAddress}"]`))
      .toContainText(/^MAX: [0-9,.]+(?:\.\d+)? MEOW$/);

    await page.locator(`[data-role="subaccounts-transfer-close-${subaccountAddress}"]`).click();
    await expect(popover).toHaveCount(0);
  }
});

test("subaccounts transfer offers every spot token for unified accounts @regression", async ({ page }) => {
  await interceptSubaccountsApi(page);

  await page.setViewportSize({ width: 768, height: 720 });
  await visitRoute(page, "/subAccounts");
  await seedSubaccountsState(page, { accountMode: "unified" });

  await page.locator(`[data-role="subaccounts-transfer-${subaccountAddress}"]`).click();
  await seedAccountSurface(page, { accountMode: "unified" });

  const accountTrigger = page.locator(`[data-role="subaccounts-transfer-direction-${subaccountAddress}"]`);
  await expect(accountTrigger).toBeVisible();
  await expect(accountTrigger).toContainText("Spot Account");
  await expect(page.locator(`[data-role="subaccounts-transfer-max-${subaccountAddress}"]`))
    .toContainText("MAX: 358.56 USDC");

  await accountTrigger.click();

  // A unified master pools spot and perps collateral, so it offers one funding
  // source — and that source is the spot one, which is what lets it send any
  // spot token rather than USDC alone.
  const accountMenu = page.locator(`[data-role="subaccounts-transfer-account-menu-${subaccountAddress}"]`);
  await expect(accountMenu).toBeVisible();
  await expect(accountMenu).toContainText("Spot Account");
  await expect(page.locator(`[data-role="subaccounts-transfer-account-option-${subaccountAddress}-spot"]`))
    .toHaveCount(1);
  await expect(page.locator(`[data-role="subaccounts-transfer-account-option-${subaccountAddress}-trading"]`))
    .toHaveCount(0);

  await page.locator(`[data-role="subaccounts-transfer-token-${subaccountAddress}"]`).click();
  const tokenMenu = page.locator(`[data-role="subaccounts-transfer-token-menu-${subaccountAddress}"]`);
  await expect(tokenMenu).toBeVisible();
  await expect(tokenMenu).toContainText("USDC");
  await expect(tokenMenu).toContainText("USDH");

  // The option carries the `NAME:0xhash` wire token id Hyperliquid needs, built
  // by joining the balance row's numeric token index against spotMeta.
  const usdhOption = page.locator(
    `[data-role="subaccounts-transfer-token-option-${subaccountAddress}-USDH:0xabc"]`
  );
  await expect(usdhOption).toHaveCount(1);
  await usdhOption.click();
  await expect(page.locator(`[data-role="subaccounts-transfer-token-${subaccountAddress}"]`))
    .toContainText("USDH");
  await expect(page.locator(`[data-role="subaccounts-transfer-max-${subaccountAddress}"]`))
    .toContainText("MAX: 8.28 USDH");
});

test("send tokens hides zero-balance tokens behind an opt-in checkbox @regression", async ({
  page
}) => {
  await interceptSubaccountsApi(page);
  await visitRoute(page, "/subAccounts");
  await seedSubaccountsState(page, { accountMode: "unified" });

  await page.locator(`[data-role="subaccounts-transfer-${subaccountAddress}"]`).click();
  await seedAccountSurface(page, { accountMode: "unified" });
  await page.locator(`[data-role="subaccounts-transfer-token-${subaccountAddress}"]`).click();

  const tokenMenu = page.locator(`[data-role="subaccounts-transfer-token-menu-${subaccountAddress}"]`);
  await expect(tokenMenu).toBeVisible();

  // The seeded master holds USDC and USDH with real balances and STAR at zero.
  // A zero balance can never be sent -- Send is disabled for it -- so it is
  // noise until the user opts in.
  await expect(tokenMenu).toContainText("USDC");
  await expect(tokenMenu).toContainText("USDH");
  await expect(tokenMenu).not.toContainText("STAR");

  const toggle = page.locator(`[data-role="subaccounts-transfer-show-zero-${subaccountAddress}"]`);
  await expect(toggle).toBeVisible();
  await expect(toggle).not.toBeChecked();
  await expect(
    page.locator(`[data-role="subaccounts-transfer-show-zero-label-${subaccountAddress}"]`)
  ).toContainText("Show 1 zero-balance token");

  // The toggle sits in the dialog body, so it is reachable whether the dropdown
  // is open or not -- and toggling it must not close an open dropdown, which is
  // why the field leaves both menu flags alone.
  await toggle.click();
  await expect(tokenMenu).toBeVisible();
  await expect(toggle).toBeChecked();
  await expect(tokenMenu).toContainText("STAR");

  await toggle.click();
  await expect(toggle).not.toBeChecked();
  await expect(tokenMenu).toBeVisible();
  await expect(tokenMenu).not.toContainText("STAR");

  // The surviving rows are still fully selectable, so filtering has not
  // disturbed the wire token id the transfer actually signs.
  const usdhOption = page.locator(
    `[data-role="subaccounts-transfer-token-option-${subaccountAddress}-USDH:0xabc"]`
  );
  await expect(usdhOption).toHaveCount(1);
  await usdhOption.click();
  await expect(page.locator(`[data-role="subaccounts-transfer-max-${subaccountAddress}"]`))
    .toContainText("MAX: 8.28 USDH");
});

test("unified subaccounts transfer submits sendAsset instead of subAccountTransfer @regression", async ({
  page
}) => {
  const signature = `0x${"a".repeat(64)}${"b".repeat(64)}1c`;

  await interceptSubaccountsApi(page);
  await visitRoute(page, "/subAccounts");
  await debugCall(page, "installWalletSimulator", {
    accounts: [ownerAddress],
    requestAccounts: [ownerAddress],
    chainId: "0xa4b1",
    typedDataSignature: signature
  });
  await debugCall(page, "installExchangeSimulator", {
    signedActions: {
      sendAsset: {
        responses: [{ status: "ok", response: { type: "default" } }]
      },
      subAccountTransfer: {
        responses: [
          { status: "err", response: "Action disabled when unified account is active" }
        ]
      }
    },
    info: {
      subAccounts: {
        responses: [
          [
            {
              name: "test",
              master: ownerAddress.toLowerCase(),
              subAccountUser: subaccountAddress.toLowerCase(),
              clearinghouseState: { marginSummary: { accountValue: "0" } },
              spotState: { balances: [] }
            }
          ]
        ]
      }
    }
  });
  await seedSubaccountsState(page, { accountMode: "unified" });

  await page.locator(`[data-role="subaccounts-transfer-${subaccountAddress}"]`).click();
  await seedAccountSurface(page, { accountMode: "unified" });
  await page.locator(`[data-role="subaccounts-transfer-amount-${subaccountAddress}"]`).fill("1.23");
  await page.locator(`[data-role="subaccounts-transfer-submit-${subaccountAddress}"]`).click();
  await waitForIdle(page, { quietMs: 350, timeoutMs: 8_000, pollMs: 50 });

  await expect
    .poll(
      async () => {
        const snapshot = await debugCall(page, "exchangeSimulatorSnapshot");
        return (snapshot?.calls ?? [])
          .map((call) => call?.request)
          .filter((request) => request?.action?.type);
      },
      { timeout: 10_000 }
    )
    .toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          action: expect.objectContaining({
            type: "sendAsset",
            destination: subaccountAddress,
            sourceDex: "spot",
            destinationDex: "spot",
            token: "USDC:0x6d1e7cde53ba9467b783cb7c530ce054",
            amount: "1.23",
            fromSubAccount: ""
          })
        })
      ])
    );

  const snapshot = await debugCall(page, "exchangeSimulatorSnapshot");
  const submittedRequests = (snapshot?.calls ?? [])
    .map((call) => call?.request)
    .filter((request) => request?.action?.type);
  const submittedTypes = submittedRequests.map((request) => request.action.type);
  expect(submittedTypes).not.toContain("subAccountTransfer");
  expect(submittedRequests.some((request) => Object.hasOwn(request, "vaultAddress"))).toBe(false);
});

test("unified subaccounts transfer submits a non-USDC spot token with its wire token id @regression", async ({
  page
}) => {
  // The reported bug: a unified (portfolio-margin) master had no way to send
  // spot HYPE — or any non-USDC spot token — to its own sub-account, because
  // the token list was hard-coded to USDC. This is the end-to-end proof that
  // the selected token now reaches the exchange, carrying the `NAME:0xhash`
  // wire token id built from the balance row's numeric index plus spotMeta.
  const signature = `0x${"a".repeat(64)}${"b".repeat(64)}1c`;

  await interceptSubaccountsApi(page);
  await visitRoute(page, "/subAccounts");
  await debugCall(page, "installWalletSimulator", {
    accounts: [ownerAddress],
    requestAccounts: [ownerAddress],
    chainId: "0xa4b1",
    typedDataSignature: signature
  });
  await debugCall(page, "installExchangeSimulator", {
    signedActions: {
      sendAsset: {
        responses: [{ status: "ok", response: { type: "default" } }]
      }
    },
    info: {
      subAccounts: {
        responses: [
          [
            {
              name: "test",
              master: ownerAddress.toLowerCase(),
              subAccountUser: subaccountAddress.toLowerCase(),
              clearinghouseState: { marginSummary: { accountValue: "0" } },
              spotState: { balances: [] }
            }
          ]
        ]
      }
    }
  });
  await seedSubaccountsState(page, { accountMode: "unified" });

  await page.locator(`[data-role="subaccounts-transfer-${subaccountAddress}"]`).click();
  await seedAccountSurface(page, { accountMode: "unified" });

  await page.locator(`[data-role="subaccounts-transfer-token-${subaccountAddress}"]`).click();
  await page
    .locator(`[data-role="subaccounts-transfer-token-option-${subaccountAddress}-USDH:0xabc"]`)
    .click();
  await expect(page.locator(`[data-role="subaccounts-transfer-token-${subaccountAddress}"]`))
    .toContainText("USDH");

  await page.locator(`[data-role="subaccounts-transfer-amount-${subaccountAddress}"]`).fill("2.5");
  await page.locator(`[data-role="subaccounts-transfer-submit-${subaccountAddress}"]`).click();
  await waitForIdle(page, { quietMs: 350, timeoutMs: 8_000, pollMs: 50 });

  await expect
    .poll(
      async () => {
        const snapshot = await debugCall(page, "exchangeSimulatorSnapshot");
        return (snapshot?.calls ?? [])
          .map((call) => call?.request)
          .filter((request) => request?.action?.type);
      },
      { timeout: 10_000 }
    )
    .toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          action: expect.objectContaining({
            type: "sendAsset",
            destination: subaccountAddress,
            sourceDex: "spot",
            destinationDex: "spot",
            token: "USDH:0xabc",
            amount: "2.5",
            fromSubAccount: ""
          })
        })
      ])
    );

  const snapshot = await debugCall(page, "exchangeSimulatorSnapshot");
  const submittedTypes = (snapshot?.calls ?? [])
    .map((call) => call?.request?.action?.type)
    .filter(Boolean);
  expect(submittedTypes).not.toContain("subAccountTransfer");
  expect(submittedTypes).not.toContain("subAccountSpotTransfer");
});

async function seedMasterOwnerMode(page, { ownerMode, selectedAddress, masterAddress = ownerAddress }) {
  await page.evaluate(({ ownerMode: mode, selectedAddress: selected, masterAddress: owner }) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const ownerModeRecord = c.PersistentArrayMap.fromArray(
      [
        keyword("owner"),
        String(owner).toLowerCase(),
        keyword("mode"),
        keyword(mode)
      ],
      true
    );

    let nextState = c.deref(store);
    nextState = c.assoc_in(
      nextState,
      kwPath("account-context", "subaccounts", "owner-mode"),
      ownerModeRecord
    );
    nextState = c.assoc_in(
      nextState,
      kwPath("account-context", "subaccounts", "selected-address"),
      selected ? String(selected).toLowerCase() : null
    );
    c.reset_BANG_(store, nextState);
    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  }, { ownerMode, selectedAddress, masterAddress });
}

// Reviewer regression: the master/owner is unified, but a classic sub-account
// is the active trading account (so [:account :mode] is :classic). Withdrawing
// back to the master must still route through sendAsset, driven by the MASTER's
// mode — not the active account's mode, which previously fell through to the
// legacy subAccountTransfer and failed for a unified master.
test("unified master withdraw uses sendAsset even when a classic sub-account is active @regression", async ({
  page
}) => {
  const signature = `0x${"a".repeat(64)}${"b".repeat(64)}1c`;

  await interceptSubaccountsApi(page);
  await visitRoute(page, "/subAccounts");
  await debugCall(page, "installWalletSimulator", {
    accounts: [ownerAddress],
    requestAccounts: [ownerAddress],
    chainId: "0xa4b1",
    typedDataSignature: signature
  });
  await debugCall(page, "installExchangeSimulator", {
    signedActions: {
      sendAsset: {
        responses: [{ status: "ok", response: { type: "default" } }]
      },
      subAccountTransfer: {
        responses: [
          { status: "err", response: "Action disabled when unified account is active" }
        ]
      }
    },
    info: {
      subAccounts: {
        responses: [
          [
            {
              name: "test",
              master: ownerAddress.toLowerCase(),
              subAccountUser: subaccountAddress.toLowerCase(),
              clearinghouseState: { marginSummary: { accountValue: "300.61686499" } },
              spotState: { balances: [] }
            }
          ]
        ]
      }
    }
  });
  // The active trading account is a classic sub-account.
  await seedSubaccountsState(page, {
    accountMode: "classic",
    subaccountSpotUsdc: "300.61686499"
  });

  await page.locator(`[data-role="subaccounts-transfer-${subaccountAddress}"]`).click();
  await seedAccountSurface(page, { accountMode: "classic" });
  // The master/owner itself is unified, and the sub-account is the active account.
  await seedMasterOwnerMode(page, { ownerMode: "unified", selectedAddress: subaccountAddress });

  // Withdraw: sub-account -> master.
  await page
    .locator(`[data-role="subaccounts-transfer-toggle-direction-${subaccountAddress}"]`)
    .click();
  await page.locator(`[data-role="subaccounts-transfer-amount-${subaccountAddress}"]`).fill("1.23");
  await page.locator(`[data-role="subaccounts-transfer-submit-${subaccountAddress}"]`).click();
  await waitForIdle(page, { quietMs: 350, timeoutMs: 8_000, pollMs: 50 });

  await expect
    .poll(
      async () => {
        const snapshot = await debugCall(page, "exchangeSimulatorSnapshot");
        return (snapshot?.calls ?? [])
          .map((call) => call?.request)
          .filter((request) => request?.action?.type);
      },
      { timeout: 10_000 }
    )
    .toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          action: expect.objectContaining({
            type: "sendAsset",
            destination: ownerAddress,
            sourceDex: "spot",
            destinationDex: "spot",
            token: "USDC:0x6d1e7cde53ba9467b783cb7c530ce054",
            amount: "1.23",
            fromSubAccount: subaccountAddress
          })
        })
      ])
    );

  const snapshot = await debugCall(page, "exchangeSimulatorSnapshot");
  const submittedTypes = (snapshot?.calls ?? [])
    .map((call) => call?.request)
    .filter((request) => request?.action?.type)
    .map((request) => request.action.type);
  expect(submittedTypes).not.toContain("subAccountTransfer");
});
