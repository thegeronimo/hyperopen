import { expect, test } from "@playwright/test";
import { debugCall, dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

const OWNER_ADDRESS = "0x1234567890abcdef1234567890abcdef12345678";
const STALE_SUBACCOUNT_ADDRESS = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd";
const VALIDATOR_ADDRESS = "0x1111111111111111111111111111111111111111";
const OTHER_VALIDATOR_ADDRESS = "0x2222222222222222222222222222222222222222";
const VALIDATOR_NAME = "Nansen x HypurrCollective";
const UNSTAKE_WEI = 10_100_000_000;
const FUTURE_LOCK_TIMESTAMP = Date.UTC(2099, 0, 2, 3, 4, 5);

test.use({ timezoneId: "UTC" });

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

async function stubStakingInfoRefreshes(page, {
  lockedUntilTimestamp = null,
  delegatorSummary = null,
  delegatorHistory = null
} = {}) {
  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    let body;

    switch (payload?.type) {
      case "validatorSummaries":
        body = [
          {
            validator: VALIDATOR_ADDRESS,
            name: VALIDATOR_NAME,
            stake: "101",
            isActive: true,
            commission: "0.05",
            stats: { week: { uptimeFraction: "0.99", predictedApr: "0.08", nSamples: 12 } }
          }
        ];
        break;
      case "delegatorSummary":
        body = delegatorSummary || {
          delegated: "101",
          undelegated: "0",
          totalPendingWithdrawal: "0",
          nPendingWithdrawals: 0
        };
        break;
      case "delegations":
        body = [
          {
            validator: VALIDATOR_ADDRESS,
            amount: "101",
            ...(lockedUntilTimestamp === null ? {} : { lockedUntilTimestamp })
          }
        ];
        break;
      case "delegatorRewards":
        body = [];
        break;
      case "delegatorHistory":
        body = delegatorHistory || [];
        break;
      case "clearinghouseState":
        body = { balances: [] };
        break;
      default:
        body = [];
    }

    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body)
    });
  });
}

async function seedMasterUnstakeState(page, { lockedUntilTimestamp = null } = {}) {
  await setAppState(page, [
    [["wallet", "connected?"], true],
    [["wallet", "address"], OWNER_ADDRESS],
    [["wallet", "chain-id"], "0xa4b1"],
    [["account", "mode"], kw("classic")],
    [["account-context", "subaccounts", "selected-address"], STALE_SUBACCOUNT_ADDRESS],
    [["account-context", "subaccounts", "rows"], []],
    [["account-context", "subaccounts", "status"], kw("loaded")],
    [["account-context", "subaccounts", "loaded-for-owner"], OWNER_ADDRESS],
    [["staking", "account-address"], OWNER_ADDRESS],
    [["staking", "loaded-for", "delegations"], OWNER_ADDRESS],
    [["staking", "loaded-for", "delegator-summary"], OWNER_ADDRESS],
    [
      ["staking", "delegations"],
      [
        {
          validator: VALIDATOR_ADDRESS,
          amount: "101",
          ...(lockedUntilTimestamp === null ? {} : { "locked-until-timestamp": lockedUntilTimestamp })
        }
      ]
    ],
    [
      ["staking", "validator-summaries"],
      [
        {
          validator: VALIDATOR_ADDRESS,
          name: VALIDATOR_NAME,
          stake: "101",
          "is-active?": true,
          commission: "0.05",
          stats: { week: { "uptime-fraction": "0.99", "predicted-apr": "0.08", "sample-count": 12 } }
        }
      ]
    ],
    [
      ["staking", "delegator-summary"],
      { "total-staked": "101", delegated: "101", undelegated: "0", "total-pending-withdrawal": "0" }
    ],
    [["staking", "loading"], {}],
    [["staking", "errors"], {}],
    [["staking-ui", "selected-validator"], OTHER_VALIDATOR_ADDRESS],
    [["staking-ui", "validator-search-query"], ""],
    [["staking-ui", "validator-dropdown-open?"], false],
    [["staking-ui", "undelegate-amount"], ""],
    [["staking-ui", "form-error"], null],
    [["staking-ui", "submitting"], { "undelegate?": false }]
  ]);
}

async function installUnstakeSimulators(page, exchangeResponse) {
  await debugCall(page, "installWalletSimulator", {
    accounts: [OWNER_ADDRESS],
    requestAccounts: [OWNER_ADDRESS],
    chainId: "0xa4b1",
    typedDataSignature: `0x${"a".repeat(64)}${"b".repeat(64)}1c`
  });

  const exchangeConfig =
    exchangeResponse === undefined
      ? { signedActions: {} }
      : { signedActions: { default: { responses: [exchangeResponse] } } };
  await debugCall(page, "installExchangeSimulator", {
    ...exchangeConfig
  });
}

async function deferExchangeResponse(page, response) {
  let resolveRequest;
  const requestReceived = new Promise((resolve) => {
    resolveRequest = resolve;
  });
  let releaseResponse;
  const responseGate = new Promise((resolve) => {
    releaseResponse = resolve;
  });

  await page.route("https://api.hyperliquid.xyz/exchange", async (route) => {
    resolveRequest(route.request());
    await responseGate;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(response)
    });
  });

  return {
    async waitForRequest() {
      await requestReceived;
    },
    release() {
      releaseResponse();
    }
  };
}

async function captureEthereumRequests(page) {
  await page.evaluate(() => {
    const provider = globalThis.ethereum;
    if (!provider || typeof provider.request !== "function") {
      throw new Error("Wallet simulator provider unavailable");
    }

    const originalRequest = provider.request.bind(provider);
    globalThis.__stakingUnstakeEthereumRequests = [];
    provider.request = (request) => {
      globalThis.__stakingUnstakeEthereumRequests.push({
        method: request?.method,
        params: request?.params
      });
      return originalRequest(request);
    };
  });
}

async function openUnstakeAndSelectValidator(page) {
  await page.locator("[data-role='staking-action-unstake-button']").click();

  const popover = page.locator("[data-role='staking-action-popover']");
  const validatorInput = popover.locator("input").nth(1);
  await expect(popover).toBeVisible();
  await validatorInput.fill(VALIDATOR_NAME);
  await popover.getByRole("button", { name: new RegExp(VALIDATOR_NAME) }).click();
  await expect(validatorInput).toHaveAttribute("placeholder", VALIDATOR_NAME);

  return {
    popover,
    amountInput: popover.locator("#staking-undelegate-amount"),
    submit: popover.getByRole("button", { name: "Unstake", exact: true })
  };
}

async function setupMasterUnstakeScenario(page, options = {}) {
  await stubStakingInfoRefreshes(page, options);
  await visitRoute(page, "/staking");
  await installUnstakeSimulators(page, options.exchangeResponse);
  await seedMasterUnstakeState(page, options);
}

async function unstakeStateSnapshot(page) {
  const snapshot = await debugCall(page, "snapshot");
  const appState = snapshot?.["app-state"] || {};
  const staking = appState.staking || {};
  const stakingUi = appState["staking-ui"] || {};

  return {
    accountAddress: staking["account-address"],
    delegationsLoadedFor: staking["loaded-for"]?.delegations,
    delegations: staking.delegations,
    selectedValidator: stakingUi["selected-validator"],
    amount: stakingUi["undelegate-amount"],
    error: stakingUi["form-error"],
    submitting: stakingUi.submitting?.["undelegate?"]
  };
}

test("staking route defaults to disconnected gating when no wallet is connected @regression", async ({ page }) => {
  await visitRoute(page, "/staking");

  await expect(page.locator("[data-parity-id='staking-root']")).toBeVisible();
  await expect(page.locator("[data-role='staking-establish-connection']")).toBeVisible();
  await expect(page.locator("[data-role='staking-action-transfer-button']")).toHaveCount(0);
  await expect(page.locator("[data-role='staking-action-unstake-button']")).toHaveCount(0);
  await expect(page.locator("[data-role='staking-action-stake-button']")).toHaveCount(0);
});

test("staking timeframe menu opens and selects a deterministic option via debug actions @regression", async ({ page }) => {
  await visitRoute(page, "/staking");

  const trigger = page.locator("[data-role='staking-timeframe-menu-trigger']");
  const menu = page.locator("[data-role='staking-timeframe-menu']");
  const dayOption = page.locator("[data-role='staking-timeframe-option-day']");

  await expect(trigger).toContainText("7D");
  await expect(trigger).not.toHaveAttribute("aria-expanded", "true");

  await dispatch(page, [":actions/toggle-staking-validator-timeframe-menu"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(trigger).toHaveAttribute("aria-expanded", "true");
  await expect(menu).toBeVisible();
  await expect(dayOption).toBeVisible();

  await dispatch(page, [":actions/set-staking-validator-timeframe", ":day"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(trigger).toContainText("1D");
  await expect(trigger).not.toHaveAttribute("aria-expanded", "true");
  await expect(menu).not.toHaveClass(/opacity-100/);
});

test("unstake trigger routes real Escape through the open popover and closes it @regression", async ({ page }) => {
  await setupMasterUnstakeScenario(page);

  await page.locator("[data-role='staking-action-unstake-button']").click();

  const popover = page.locator("[data-role='staking-action-popover']");
  await expect(popover).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(popover).toHaveCount(0);
});

test("master 101 unstake submits the canonical owner-signed tokenDelegate request @regression", async ({ page }) => {
  await setupMasterUnstakeScenario(page);
  await captureEthereumRequests(page);
  const exchange = await deferExchangeResponse(page, { status: "ok" });

  const { popover, amountInput, submit } = await openUnstakeAndSelectValidator(page);
  await amountInput.fill("101");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 5_000, pollMs: 50 });
  await expect(submit).toBeEnabled();

  const beforeClick = await unstakeStateSnapshot(page);
  expect(beforeClick).toMatchObject({
    accountAddress: OWNER_ADDRESS,
    delegationsLoadedFor: OWNER_ADDRESS,
    selectedValidator: VALIDATOR_ADDRESS,
    amount: "101",
    error: null,
    submitting: false
  });
  expect(beforeClick.delegations).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ validator: VALIDATOR_ADDRESS, amount: 101 })
    ])
  );

  try {
    await submit.click();
    await exchange.waitForRequest();
    const submitting = popover.getByRole("button", { name: "Submitting...", exact: true });
    await expect(submitting).toBeDisabled();
  } finally {
    exchange.release();
  }

  await expect
    .poll(
      async () => {
        const snapshot = await debugCall(page, "snapshot");
        const stakingUi = snapshot?.["app-state"]?.["staking-ui"] || {};
        return {
          amount: stakingUi["undelegate-amount"],
          error: stakingUi["form-error"],
          submitting: stakingUi.submitting?.["undelegate?"]
        };
      },
      { timeout: 8_000 }
    )
    .toEqual({ amount: "", error: null, submitting: false });
  await expect(amountInput).toHaveValue("");
  await expect(popover).toBeVisible();

  const exchangeSnapshot = await debugCall(page, "exchangeSimulatorSnapshot");
  const tokenDelegateRequests = (exchangeSnapshot?.calls ?? [])
    .map((call) => call?.request)
    .filter((request) => request?.action?.type === "tokenDelegate");
  expect(tokenDelegateRequests).toHaveLength(1);
  const [submittedRequest] = tokenDelegateRequests;
  expect(submittedRequest).toEqual(
    expect.objectContaining({
      action: expect.objectContaining({
        type: "tokenDelegate",
        validator: VALIDATOR_ADDRESS,
        wei: UNSTAKE_WEI,
        isUndelegate: true
      })
    })
  );
  expect(submittedRequest).not.toHaveProperty("vaultAddress");

  const typedDataRequests = await page.evaluate(() =>
    globalThis.__stakingUnstakeEthereumRequests.filter((request) =>
      ["eth_signTypedData_v4", "eth_signTypedData"].includes(request.method)
    )
  );
  expect(typedDataRequests).toHaveLength(1);
  expect(typedDataRequests[0].params[0]).toBe(OWNER_ADDRESS);
  expect(typedDataRequests[0].params[0]).not.toBe(STALE_SUBACCOUNT_ADDRESS);
});

test("master 101 unstake keeps the popover open for a future validator lock without tokenDelegate @regression", async ({ page }) => {
  await setupMasterUnstakeScenario(page, { lockedUntilTimestamp: FUTURE_LOCK_TIMESTAMP });

  const { popover, amountInput, submit } = await openUnstakeAndSelectValidator(page);
  await amountInput.fill("101");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 5_000, pollMs: 50 });
  await submit.click();
  await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });

  const lockError = popover.locator("[data-role='staking-unstake-error']");
  await expect(popover).toBeVisible();
  await expect(amountInput).toHaveValue("101");
  await expect(lockError).toHaveText("This delegation is locked until 1/2/2099 - 03:04:05.");
  await expect(submit).toBeEnabled();

  const exchangeSnapshot = await debugCall(page, "exchangeSimulatorSnapshot");
  const tokenDelegateRequests = (exchangeSnapshot?.calls ?? []).filter(
    (call) => call?.request?.action?.type === "tokenDelegate"
  );
  expect(tokenDelegateRequests).toHaveLength(0);
});

test("master 101 unstake reports an exchange rejection inside the open popover and restores the CTA @regression", async ({ page }) => {
  await setupMasterUnstakeScenario(page, {
    exchangeResponse: { status: "err", response: "validator busy" }
  });

  const { popover, amountInput, submit } = await openUnstakeAndSelectValidator(page);
  await amountInput.fill("101");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 5_000, pollMs: 50 });
  await submit.click();
  await waitForIdle(page, { quietMs: 200, timeoutMs: 8_000, pollMs: 50 });

  await expect(popover).toBeVisible();
  await expect(amountInput).toHaveValue("101");
  await expect(popover.locator("[data-role='staking-unstake-error']")).toHaveText(
    "Unstake failed: validator busy"
  );
  await expect(submit).toBeEnabled();

  const exchangeSnapshot = await debugCall(page, "exchangeSimulatorSnapshot");
  expect(
    (exchangeSnapshot?.calls ?? []).filter(
      (call) => call?.request?.action?.type === "tokenDelegate"
    )
  ).toHaveLength(1);
});

const DAY_MS = 86_400_000;
const QUEUE_MS = 7 * DAY_MS;

function formatLocalStamp(ms) {
  // Mirrors hyperopen.utils.formatting/format-local-date-time. The spec pins
  // timezoneId "UTC", so UTC parts are the local parts the app renders.
  const d = new Date(ms);
  const pad2 = (n) => String(n).padStart(2, "0");
  return (
    `${d.getUTCMonth() + 1}/${d.getUTCDate()}/${d.getUTCFullYear()}` +
    ` - ${pad2(d.getUTCHours())}:${pad2(d.getUTCMinutes())}:${pad2(d.getUTCSeconds())}`
  );
}

// Served through the stubbed /info route rather than written straight into
// app-db, so the wire shapes go through the real normalizers and the route's own
// post-visit refresh cannot overwrite the scenario.
function withdrawWireRow(amount, startedAtMs) {
  // The live delegatorHistory shape for a queue entry still in flight. cWithdraw
  // is what starts the queue but never appears in this endpoint's responses.
  return {
    time: startedAtMs,
    hash: `0x${String(startedAtMs)}`,
    delta: { withdrawal: { amount: String(amount), phase: "initiated" } }
  };
}

async function seedUnstakingQueue(page, { pending, count, history }) {
  await stubStakingInfoRefreshes(page, {
    delegatorSummary: {
      delegated: "101",
      undelegated: "0",
      totalPendingWithdrawal: String(pending),
      nPendingWithdrawals: count
    },
    delegatorHistory: history
  });
  await visitRoute(page, "/staking");
  await setAppState(page, [
    [["wallet", "connected?"], true],
    [["wallet", "address"], OWNER_ADDRESS]
  ]);
  await dispatch(page, [":actions/load-staking"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });
}

test("staking balance panel reports in-flight unstaking as locked with an arrival time @regression", async ({ page }) => {
  // Offset by 30 minutes so the rendered hour bucket cannot straddle a boundary
  // between the seed and the render.
  const startedAtMs = Date.now() - 2 * DAY_MS - 30 * 60_000;
  await seedUnstakingQueue(page, {
    pending: 25,
    count: 1,
    history: [withdrawWireRow(25, startedAtMs)]
  });

  const block = page.locator("[data-role='staking-unstaking-block']");
  await expect(block).toBeVisible();
  await expect(block).toContainText("Unstaking to Spot Balance");
  await expect(block.locator("[data-role='staking-unstaking-locked-pill']")).toHaveText("Locked");
  await expect(block.locator("[data-role='staking-unstaking-amount']")).toHaveText("25.00000000 HYPE");
  await expect(block).toContainText("Not tradable or transferable until it arrives.");
  await expect(block.locator("[data-role='staking-unstaking-arrival']")).toContainText(
    `Arrives ${formatLocalStamp(startedAtMs + QUEUE_MS)}`
  );
  await expect(block.locator("[data-role='staking-unstaking-arrival']")).toContainText(/about \d+d \d+h left/);
  await expect(block).toContainText("1 transfer in the queue");

  const bar = block.locator("[data-role='staking-unstaking-progress']");
  await expect(bar).toHaveAttribute("role", "progressbar");
  await expect(bar).toHaveAttribute("aria-valuemax", "100");
});

test("staking balance panel keeps the unstaking slot present and quiet when nothing is queued @regression", async ({ page }) => {
  await seedUnstakingQueue(page, { pending: 0, count: 0, history: [] });

  const block = page.locator("[data-role='staking-unstaking-block']");
  await expect(block).toBeVisible();
  await expect(block).toContainText("Unstaking to Spot Balance");
  await expect(block).toContainText("None");
  await expect(block.locator("[data-role='staking-unstaking-locked-pill']")).toHaveCount(0);
});

test("staking balance panel refuses to invent an arrival time it cannot attribute @regression", async ({ page }) => {
  await seedUnstakingQueue(page, { pending: 25, count: 2, history: [] });

  const block = page.locator("[data-role='staking-unstaking-block']");
  await expect(block.locator("[data-role='staking-unstaking-amount']")).toHaveText("25.00000000 HYPE");
  await expect(block).toContainText("2 transfers in the queue");
  await expect(block).toContainText("The exact arrival time is not available right now.");
  await expect(block.locator("[data-role='staking-unstaking-progress']")).toHaveCount(0);
});

test("unstake popover explains the second step and warns about a live lock before submit @regression", async ({ page }) => {
  const unlockMs = Date.now() + 6 * 3_600_000;
  await setupMasterUnstakeScenario(page, { lockedUntilTimestamp: unlockMs });
  const { popover } = await openUnstakeAndSelectValidator(page);

  await expect(popover.locator("[data-role='staking-unstake-guidance']")).toContainText(
    "Unstaking returns HYPE to your Staking Balance right away."
  );
  await expect(popover.locator("[data-role='staking-unstake-guidance']")).toContainText(
    "separate transfer that then takes 7 days"
  );

  const notice = popover.locator("[data-role='staking-unstake-lock-notice']");
  await expect(notice).toBeVisible();
  await expect(notice).toContainText(`This delegation is locked until ${formatLocalStamp(unlockMs)}`);
  await expect(notice).toContainText("You cannot unstake from this validator yet.");

  const exchangeSnapshot = await debugCall(page, "exchangeSimulatorSnapshot");
  expect(
    (exchangeSnapshot?.calls ?? []).filter((call) => call?.request?.action?.type === "tokenDelegate")
  ).toHaveLength(0);
});
