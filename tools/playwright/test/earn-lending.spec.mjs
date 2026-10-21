// Earn (HyperCore borrow/lend), end to end against this build.
//
// Every case runs the real app with:
// - fixture lending reads (`allBorrowLendReserveStates`, `borrowLendUserState`,
//   `userBorrowLendInterest`) shaped like the live API, plus the shared
//   HyperCore account fixtures from hyperevm_fixtures.mjs;
// - the debug wallet simulator and a remembered agent-trading session, because
//   `borrowLend` is an L1 action signed by the agent key;
// - the debug exchange simulator, which records each signed action
//   (`exchangeSimulatorSnapshot`), so a case asserts the exact wire action.
// The worked example is the lending brief's: 100 HYPE at $40 (65% LTV) with
// 2,000 USDC borrowed, a $2,600 limit, 130% health, liquidation at $24.24.
import { expect, test } from "../support/guarded_test.mjs";
import { debugCall, dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";
import {
  clearinghouseState,
  hyperCoreInfoFixture,
  routeHyperCoreAccountStreams,
  routeHyperCoreInfo,
  spotBalance
} from "../support/hyperevm_fixtures.mjs";

const OWNER = "0x1234567890abcdef1234567890abcdef12345678";
const AGENT = "0x9999999999999999999999999999999999999999";
const ARBITRUM = "0xa4b1";
const OK = { status: "ok", response: { type: "default" } };
const UNEXPECTED = { status: "err", response: "Unexpected signed action in this spec." };

const USDC = 0;
const HYPE = 150;

const RESERVES = [
  [USDC, reserve({ utilization: "0.831", borrow: "0.198", supply: "0.1484", px: "1.0", ltv: "0.0",
                   supplied: "323700000", borrowed: "269000000", balance: "54700000" })],
  [HYPE, reserve({ utilization: "0.0", borrow: "0.05", supply: "0.0", px: "40.0", ltv: "0.65",
                   supplied: "1000000", borrowed: "0", balance: "1000000" })],
  [197, reserve({ utilization: "0.0", borrow: "0.05", supply: "0.0", px: "80000.0", ltv: "0.5",
                  supplied: "100", borrowed: "0", balance: "100" })]
];

function reserve({ utilization, borrow, supply, px, ltv, supplied, borrowed, balance }) {
  return {
    borrowYearlyRate: borrow,
    supplyYearlyRate: supply,
    balance,
    utilization,
    oraclePx: px,
    ltv,
    totalSupplied: supplied,
    totalBorrowed: borrowed
  };
}

const POSITIONS = {
  tokenToState: [
    [USDC, {
      supply: { basis: "5000", value: "5000" },
      borrow: { basis: "2000", value: "2000" }
    }],
    [HYPE, { supply: { basis: "100", value: "100" }, borrow: { basis: "0", value: "0" } }]
  ],
  health: "healthy",
  healthFactor: "1.3"
};

const EMPTY_POSITIONS = { tokenToState: [], health: "healthy", healthFactor: null };

const INTEREST = [
  { time: 1790000000000, token: "USDC", borrow: "0.0", supply: "1.25" },
  { time: 1790003600000, token: "USDC", borrow: "0.4", supply: "0.0" }
];

const role = (page, name) => page.locator(`[data-role='${name}']`);

async function routeLendingReads(page, { positions = POSITIONS } = {}) {
  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    let payload = null;
    try {
      payload = route.request().postDataJSON();
    } catch {
      payload = null;
    }
    if (payload?.type !== "allBorrowLendReserveStates") {
      await route.fallback();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(RESERVES)
    });
  });
  return positions;
}

async function setupAccount(page, {
  route = "/earn",
  abstraction = "unifiedAccount",
  positions = POSITIONS,
  exchange = [OK],
  connect = true
} = {}) {
  const info = hyperCoreInfoFixture();
  info.spotStates[OWNER] = { balances: [spotBalance("USDC", "12000")] };
  info.clearinghouseStates[OWNER] = clearinghouseState("5000.0");
  info.userRoles[OWNER] = { role: "user" };
  info.userAbstractions[OWNER] = abstraction;
  info.userReads.borrowLendUserState = positions;
  info.userReads.userBorrowLendInterest = INTEREST;
  await routeHyperCoreInfo(page, info);
  await routeLendingReads(page);
  await routeHyperCoreAccountStreams(page);
  await visitRoute(page, route);
  if (!connect) {
    return info;
  }
  await debugCall(page, "installWalletSimulator", {
    accounts: [OWNER],
    requestAccounts: [OWNER],
    chainId: ARBITRUM
  });
  await debugCall(page, "installExchangeSimulator", {
    // Answers are queued per action type: the app signs its own
    // `scheduleCancel` around connect, which must not use up `borrowLend`'s.
    // Anything else signed reaches the refusing default queue.
    signedActions: {
      scheduleCancel: { responses: Array.from({ length: 8 }, () => OK) },
      borrowLend: { responses: [...exchange, ...Array.from({ length: 6 }, () => UNEXPECTED)] },
      default: { responses: Array.from({ length: 8 }, () => UNEXPECTED) }
    }
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");
  await dispatch(page, [":actions/connect-wallet"]);
  await seedReadyAgentSession(page);
  await dispatch(page, [":actions/load-borrow-lend"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });
  return info;
}

// A remembered, unlocked agent session (as after "Enable trading"), so
// `borrowLend` can sign without a wallet prompt.
async function seedReadyAgentSession(page) {
  await page.evaluate(
    ({ walletAddress, agentAddress }) => {
      const c = globalThis.cljs.core;
      const store = globalThis.hyperopen.system.store;
      const keyword = c.keyword;
      const kwPath = (...segments) =>
        c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
      const lastApprovedAt = 1700000000000;
      localStorage.setItem("hyperopen:agent-storage-mode:v1", "local");
      localStorage.setItem("hyperopen:agent-local-protection-mode:v1", "plain");
      localStorage.setItem(
        `hyperopen:agent-session:v1:${walletAddress.toLowerCase()}`,
        JSON.stringify({
          "agent-address": agentAddress,
          // Any valid 32-byte secp256k1 key: signing runs for real, the
          // exchange simulator only records the result.
          "private-key": `0x${"11".repeat(32)}`,
          "last-approved-at": lastApprovedAt,
          "nonce-cursor": lastApprovedAt
        })
      );
      let next = c.deref(store);
      const set = (path, value) => {
        next = c.assoc_in(next, kwPath(...path), value);
      };
      set(["wallet", "agent", "status"], keyword("ready"));
      set(["wallet", "agent", "storage-mode"], keyword("local"));
      set(["wallet", "agent", "local-protection-mode"], keyword("plain"));
      set(["wallet", "agent", "passkey-supported?"], false);
      set(["wallet", "agent", "agent-address"], agentAddress);
      set(["wallet", "agent", "last-approved-at"], lastApprovedAt);
      set(["wallet", "agent", "nonce-cursor"], lastApprovedAt);
      set(["wallet", "agent", "error"], null);
      set(["wallet", "agent", "recovery-modal-open?"], false);
      c.reset_BANG_(store, next);
    },
    { walletAddress: OWNER, agentAddress: AGENT }
  );
}

async function signedBorrowLendActions(page) {
  const snapshot = await debugCall(page, "exchangeSimulatorSnapshot");
  return (snapshot?.calls ?? [])
    .map((call) => call?.request)
    .filter((request) => request?.action?.type === "borrowLend");
}

async function openModal(page, kind, token) {
  await dispatch(page, [":actions/open-borrow-lend-modal", `:${kind}`, token]);
  await expect(role(page, "earn-modal")).toBeVisible();
}

async function expectNoHorizontalOverflow(page) {
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)
  ).toBe(true);
}

test("earn page lists live-shaped pools and collateral without a wallet @regression", async ({ page }) => {
  await setupAccount(page, { connect: false });

  await expect(page.locator("[data-parity-id='earn-root']")).toBeVisible();
  await expect(role(page, "earn-pool-row-0")).toContainText("USDC");
  await expect(role(page, "earn-pool-row-0")).toContainText("14.84%");
  await expect(role(page, "earn-pool-row-0")).toContainText("19.80%");
  await expect(role(page, "earn-pool-row-0")).toContainText("83.1%");
  await expect(role(page, "earn-collateral-row-150")).toContainText("HYPE");
  await expect(role(page, "earn-collateral-row-150")).toContainText("65%");
  await expect(role(page, "earn-collateral-row-150")).toContainText("82.5%");
  // Without an account, nothing is supplied and the page invites connecting.
  await expect(role(page, "earn-summary-supplied")).toContainText("$0.00");
  await expectNoHorizontalOverflow(page);
});

test("earn page shows the account's position and the brief's health maths @regression", async ({ page }) => {
  await setupAccount(page);

  await expect(role(page, "earn-summary-supplied")).toContainText("$5,000.00");
  await expect(role(page, "earn-summary-borrowed")).toContainText("$2,000.00");
  await expect(role(page, "earn-summary-health")).toContainText("130%");
  await expect(role(page, "earn-summary-health")).toContainText("$24.24");
  const health = role(page, "earn-health");
  await expect(health).toContainText("$30.77");
  await expect(health).toContainText("$600.00");

  await role(page, "earn-tab-positions").click();
  await expect(role(page, "earn-position-0-supply")).toContainText("5,000.00");
  await expect(role(page, "earn-position-0-borrow")).toContainText("2,000.00");
  await expect(role(page, "earn-position-150-supply")).toContainText("100.00");
});

test("supply previews the pool rate, then signs the exact borrowLend action @regression", async ({ page }) => {
  await setupAccount(page, { exchange: [OK] });
  await openModal(page, "supply", USDC);

  const amount = role(page, "earn-modal-amount");
  const preview = role(page, "earn-modal-preview");
  await amount.fill("10000000");
  // 269.0M borrowed over 333.7M supplied is 80.6% utilization.
  await expect(preview).toContainText("80.6%");
  await expect(preview).toContainText("83.1%");
  await expect(role(page, "earn-modal-submit")).toHaveText(/Supply 10,000,000\.00 USDC/);

  await amount.fill("1000");
  await role(page, "earn-modal-submit").click();
  await expect(role(page, "earn-modal")).toHaveCount(0);

  const sent = await signedBorrowLendActions(page);
  expect(sent).toHaveLength(1);
  expect(sent[0].action).toEqual({ type: "borrowLend", operation: "supply", token: 0, amount: "1000" });
  expect(sent[0]).not.toHaveProperty("vaultAddress");
});

test("borrow above the limit is rejected before anything is signed @regression", async ({ page }) => {
  await setupAccount(page);
  await openModal(page, "borrow", USDC);

  await role(page, "earn-modal-amount").fill("601");
  await expect(role(page, "earn-modal-error")).toContainText("more than you can borrow");
  await expect(role(page, "earn-modal-submit")).toBeDisabled();
  expect(await signedBorrowLendActions(page)).toHaveLength(0);

  // 300 more keeps the account inside its limit and previews the new health.
  await role(page, "earn-modal-amount").fill("300");
  const preview = role(page, "earn-modal-preview");
  await expect(preview).toContainText("113%");
  await expect(preview).toContainText("$2,300.00");
  await expect(role(page, "earn-modal-submit")).toBeEnabled();
});

test("repay Max sends a null amount so interest dust is not left behind @regression", async ({ page }) => {
  await setupAccount(page, { exchange: [OK] });
  await openModal(page, "repay", USDC);

  await role(page, "earn-modal-preset-Max").click();
  await expect(role(page, "earn-modal-submit")).toHaveText(/Repay all USDC/);
  await role(page, "earn-modal-submit").click();
  await expect(role(page, "earn-modal")).toHaveCount(0);

  const sent = await signedBorrowLendActions(page);
  expect(sent).toHaveLength(1);
  expect(sent[0].action).toEqual({ type: "borrowLend", operation: "repay", token: 0, amount: null });
});

test("an exchange refusal keeps the modal open with the reason @regression", async ({ page }) => {
  await setupAccount(page, { exchange: [{ status: "err", response: "Insufficient liquidity" }] });
  await openModal(page, "withdraw", USDC);

  await role(page, "earn-modal-amount").fill("100");
  await role(page, "earn-modal-submit").click();
  await expect(role(page, "earn-modal-error")).toContainText("Withdraw failed: Insufficient liquidity");
  await expect(role(page, "earn-modal")).toBeVisible();
  await expect(role(page, "earn-modal-submit")).toBeEnabled();
});

test("portfolio margin accounts cannot manual-borrow @regression", async ({ page }) => {
  await setupAccount(page, { abstraction: "portfolioMargin" });

  await expect(role(page, "earn-pm-notice")).toBeVisible();
  await expect(role(page, "earn-borrow-0")).toBeDisabled();
  await expect(role(page, "earn-supply-0")).toBeEnabled();

  // Opening a borrow lands on repay, the one debt action PM accounts keep.
  await dispatch(page, [":actions/open-borrow-lend-modal", ":borrow", USDC]);
  await expect(role(page, "earn-modal")).toContainText("Repay USDC");
  await expect(role(page, "earn-modal-tab-borrow")).toBeDisabled();
});

test("account panel Earn tab lists positions and its Repay opens the modal on /earn @regression", async ({ page }) => {
  await setupAccount(page, { route: "/trade" });

  await dispatch(page, [":actions/select-account-info-tab", ":earn"]);
  await expect(role(page, "account-earn-tab")).toBeVisible();
  await expect(role(page, "account-earn-summary")).toContainText("130%");
  await expect(role(page, "account-earn-row-0-borrow")).toContainText("2,000.00");

  await role(page, "account-earn-repay-0").click();
  await expect(page).toHaveURL(/\/earn/);
  await expect(role(page, "earn-modal")).toContainText("Repay USDC");
});

test("earn page has no horizontal overflow at phone width @regression", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await setupAccount(page);

  await expect(role(page, "earn-pool-row-0")).toBeAttached();
  await expectNoHorizontalOverflow(page);

  // Wide tables scroll inside their own cards; the positions tab too.
  await role(page, "earn-tab-positions").click();
  await expect(role(page, "earn-position-0-supply")).toBeAttached();
  await expectNoHorizontalOverflow(page);
  await role(page, "earn-tab-markets").click();

  await openModal(page, "supply", USDC);
  const box = await role(page, "earn-modal").boundingBox();
  expect(box.x).toBeGreaterThanOrEqual(0);
  expect(box.x + box.width).toBeLessThanOrEqual(375);
});
