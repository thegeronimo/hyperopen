// HyperCore <-> HyperEVM Transfer, end to end against this build.
//
// Every case runs the real app with:
// - the debug wallet simulator (EIP-1193), which signs and sends and logs
//   every request in order (`walletSimulatorSnapshot`);
// - the debug exchange simulator, which records each signed Hyperliquid
//   action (`exchangeSimulatorSnapshot`);
// - a mocked HyperEVM RPC (`routeHyperEvmRpc`), the only source of HyperEVM
//   balances, gas, estimates and receipts, keyed by the wallet simulator's
//   deterministic hashes;
// - fixture HyperCore account reads (`routeHyperCoreInfo`, strict: every
//   user-carrying read the spec depends on is answered, and afterEach fails
//   on any that reached the live API) with the live balance streams
//   unsubscribed (`routeHyperCoreAccountStreams`).
// The shared guard (`guarded_test.mjs`) also fails any case in which a
// request reached the HyperEVM RPC host without the mock answering it.
// Every fixture value is production-shaped (see hyperevm_fixtures.mjs).
import { expect, test } from "../support/guarded_test.mjs";
import {
  debugCall,
  dispatch,
  expectOracle,
  visitRoute
} from "../support/hyperopen.mjs";
import {
  HYPE_SYSTEM_ADDRESS,
  PROBE_GAS_PRICE_WEI,
  SPOT_TOKENS,
  USDC_CORE_DEPOSIT_WALLET,
  USDC_TOKEN_ADDRESS,
  approveOnReceipt,
  clearinghouseState,
  encodeApprove,
  encodeCoreDeposit,
  hyperCoreInfoFixture,
  hyperEvmFixture,
  hyperEvmRpcController,
  quantityHex,
  routeHyperCoreAccountStreams,
  routeHyperCoreInfo,
  routeHyperEvmRpc,
  setHolding,
  simulatedTxHash,
  spotBalance
} from "../support/hyperevm_fixtures.mjs";

const OWNER = "0x1234567890abcdef1234567890abcdef12345678";
const SUBACCOUNT = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd";
const SPECTATED = "0x5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e";
const ARBITRUM = "0xa4b1";
const HYPEREVM = "0x3e7";
const PURR = SPOT_TOKENS.PURR;
const HYPE = SPOT_TOKENS.HYPE;
const PURR_SYSTEM_ADDRESS = "0x2000000000000000000000000000000000000001";
const PURR_ADDRESS = PURR.evmContract.address;
const MASTER_ONLY = "HyperEVM transfers are available for the master account only.";
const SPECTATE_READ_ONLY = "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds.";
const SWITCH_UNSUPPORTED =
  "Your wallet couldn't switch to HyperEVM (chain 999), so it can't send this transfer. " +
  "Switch networks in your wallet, or try again.";
const CHAIN_LEFT =
  "Your wallet left HyperEVM (chain 999) before the next transaction, so nothing more was sent. " +
  "Switch back to HyperEVM and try again.";
const IN_FLIGHT_PENDING =
  "Your last HyperEVM transfer hasn't confirmed yet. If your wallet shows it finished, was replaced or was cancelled, reload the page to start another.";

// Gas every HyperEVM transaction must carry for the fixture's live gas price
// (0.107 gwei, below the 0.2 gwei floor): maxFeePerGas = 2 x 0.2 gwei, and
// gas = the live estimate x 1.3, rounded up.
const GAS_PRICE_FLOOR_WEI = 200000000n;
const MAX_FEE_PER_GAS = quantityHex(
  2n * (BigInt(PROBE_GAS_PRICE_WEI) > GAS_PRICE_FLOOR_WEI ? BigInt(PROBE_GAS_PRICE_WEI) : GAS_PRICE_FLOOR_WEI)
);
const gasFor = (estimate) => quantityHex((estimate * 13n + 9n) / 10n);

const DEFAULT_SPOT = [
  spotBalance("USDC", "2105.4"),
  spotBalance("PURR", "9800"),
  spotBalance("HYPE", "3.2"),
  spotBalance("SIX", "12.5")
];

const OK = { status: "ok", response: { type: "default" } };
const UNEXPECTED = { status: "err", response: "Unexpected signed action in this spec." };

const modal = (page) => page.locator("[data-role='funding-modal']");
const role = (page, name) => page.locator(`[data-role='${name}']`);

// The HyperCore info fixture of each page, so afterEach can check that no
// account read reached the live API.
const coreInfoFixtures = new WeakMap();

test.afterEach(async ({ page }) => {
  // Every wallet method the app used is one the simulator implements: an
  // unknown one rejects with 4200 and is logged here, so a read routed
  // through the wallet instead of the mocked RPC fails the case.
  const snapshot = await page
    .evaluate(() => globalThis.HYPEROPEN_DEBUG?.walletSimulatorSnapshot?.() ?? null)
    .catch(() => null);
  if (snapshot?.installed) {
    expect(snapshot.unknownRequests, "wallet methods the simulator does not implement").toEqual([]);
  }
  const info = coreInfoFixtures.get(page);
  if (info) {
    const live = [...new Set(info.unhandledUserRequests.map((payload) => payload.type))].sort();
    expect(live, "account reads that reached the live info API").toEqual([]);
  }
});

async function routeCoreInfo(page, info) {
  coreInfoFixtures.set(page, info);
  await routeHyperCoreInfo(page, info, { strict: true });
}

async function storeValue(page, path) {
  return page.evaluate((segments) => {
    const c = globalThis.cljs.core;
    const state = c.deref(globalThis.hyperopen.system.store);
    const key = (segment) =>
      typeof segment === "number" || (typeof segment === "string" && segment.startsWith("0x"))
        ? segment
        : c.keyword(segment);
    const value = c.get_in(state, c.PersistentVector.fromArray(segments.map(key), true));
    return c.clj__GT_js(value);
  }, path);
}

// Info responses by type and status, printed when a case can't get going
// (the live info API rate-limits bursts of test runs).
const infoLogs = new WeakMap();

function trackInfoResponses(page) {
  const log = [];
  infoLogs.set(page, log);
  const typeOf = (request) => {
    try {
      return request.postDataJSON()?.type ?? null;
    } catch {
      return null;
    }
  };
  page.on("response", (response) => {
    if (response.url().includes("/info")) log.push(`${typeOf(response.request())}:${response.status()}`);
  });
  page.on("requestfailed", (request) => {
    if (request.url().includes("/info")) log.push(`${typeOf(request)}:failed`);
  });
}

async function waitForAccountReads(page, address) {
  try {
    await expect
      .poll(async () => ({
        evm: await storeValue(page, ["hyperevm", "balances", "by-address", address, "status"]),
        spot: Boolean(await storeValue(page, ["spot", "clearinghouse-state"]))
      }), { timeout: 15_000 })
      .toEqual({ evm: "ready", spot: true });
  } catch (error) {
    const hyperevm = await storeValue(page, ["hyperevm"]);
    const route = await storeValue(page, ["router", "path"]);
    const metaTokens = (await storeValue(page, ["spot", "meta", "tokens"]))?.length ?? null;
    const rpc = hyperEvmRpcController(page);
    throw new Error(
      `${error.message}\nhyperevm=${JSON.stringify(hyperevm).slice(0, 1500)}\nroute=${route}` +
        `\nrpc=${JSON.stringify(rpc?.entries().map((entry) => entry.method))}` +
        `\nspotMetaTokens=${metaTokens}\ninfo=${JSON.stringify(infoLogs.get(page) ?? [])}`
    );
  }
}

// Route everything, open `route`, and connect the fixture owner.
async function setupAccount(page, {
  route = "/portfolio",
  evm = { HYPE: "12.5" },
  spot = DEFAULT_SPOT,
  perpsUsdc = "5000.0",
  role: userRole = "user",
  wallet = {},
  exchange = [],
  subAccounts = null,
  rpcOverrides = {},
  waitForEvm = true
} = {}) {
  const rpc = await routeHyperEvmRpc(page, hyperEvmFixture({ owner: OWNER, holdings: evm, overrides: rpcOverrides }));
  trackInfoResponses(page);
  const info = hyperCoreInfoFixture();
  info.spotStates[OWNER] = { balances: spot };
  info.clearinghouseStates[OWNER] = clearinghouseState(perpsUsdc);
  info.userRoles[OWNER] = { role: userRole };
  if (subAccounts) info.subAccounts[OWNER] = subAccounts;
  await routeCoreInfo(page, info);
  await routeHyperCoreAccountStreams(page);
  await visitRoute(page, route);
  await debugCall(page, "installWalletSimulator", {
    accounts: [OWNER],
    requestAccounts: [OWNER],
    chainId: ARBITRUM,
    ...wallet
  });
  // The simulator matches only its `default` queue (action types arrive
  // keywordized), so each case queues its answers in order, followed by
  // refusals so nothing unexpected ever reaches the real exchange.
  await debugCall(page, "installExchangeSimulator", {
    signedActions: {
      default: { responses: [...exchange, ...Array.from({ length: 8 }, () => UNEXPECTED)] }
    }
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");
  await dispatch(page, [":actions/connect-wallet"]);
  await expectOracle(page, "wallet-status", { connected: true, address: OWNER });
  if (waitForEvm) await waitForAccountReads(page, OWNER);
  return { rpc, info };
}

async function openBalances(page) {
  const tab = role(page, "account-info-tab-balances");
  await tab.click();
  await expect(tab).toHaveAttribute("aria-pressed", "true");
}

async function walletLog(page) {
  const snapshot = await debugCall(page, "walletSimulatorSnapshot");
  return snapshot.requests;
}

async function signedActions(page, type) {
  const snapshot = await debugCall(page, "exchangeSimulatorSnapshot");
  return (snapshot?.calls ?? [])
    .map((call) => call?.request)
    .filter((request) => request?.action?.type === type);
}

async function expectPlaces(page, from, to) {
  await expect(role(page, `funding-transfer-from-${from}`)).toHaveAttribute("aria-pressed", "true");
  await expect(role(page, `funding-transfer-to-${to}`)).toHaveAttribute("aria-pressed", "true");
}

async function enterAmount(page, amount) {
  const input = page.locator("#funding-transfer-amount-input-field");
  await input.fill(amount);
  return input;
}

async function noHorizontalOverflow(page) {
  return page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);
}

function inViewport(box, viewport) {
  return Boolean(box) && box.y >= 0 && box.y + box.height <= viewport.height && box.x >= 0 &&
    box.x + box.width <= viewport.width;
}

// Whether the control is what a pointer at its centre would hit (nothing,
// such as the app's fixed footer, painted over it).
async function hitTestable(locator) {
  return locator.evaluate((node) => {
    const rect = node.getBoundingClientRect();
    const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
    return Boolean(hit) && (hit === node || node.contains(hit));
  });
}

// Scroll so `locator`'s bottom sits as low as a pointer can still reach it:
// 24 px above the viewport's bottom, or just above a fixed layer (the app
// footer) covering that edge. Scrolls the nearest scrolling ancestor (the
// /trade rows viewport) or the page. Returns its box and the viewport height.
async function placeNearViewportBottom(locator) {
  return locator.evaluate((node) => {
    node.scrollIntoView({ block: "end" });
    const fixedLayer = (element) => {
      for (let current = element; current; current = current.parentElement) {
        if (getComputedStyle(current).position === "fixed") return true;
      }
      return false;
    };
    const x = node.getBoundingClientRect().left + 4;
    let coveredFrom = window.innerHeight;
    for (let y = window.innerHeight - 1; y > window.innerHeight / 2; y -= 2) {
      const hit = document.elementFromPoint(x, y);
      if (hit && fixedLayer(hit) && !node.contains(hit)) coveredFrom = y;
      else break;
    }
    const target = Math.min(window.innerHeight - 24, coveredFrom - 4);
    const scroller = (() => {
      for (let current = node.parentElement; current; current = current.parentElement) {
        const style = getComputedStyle(current);
        if (/(auto|scroll)/.test(style.overflowY) && current.scrollHeight > current.clientHeight) return current;
      }
      return document.scrollingElement;
    })();
    scroller.scrollTop += node.getBoundingClientRect().bottom - target;
    const rect = node.getBoundingClientRect();
    return { top: rect.top, bottom: rect.bottom, viewportHeight: window.innerHeight, coveredFrom };
  });
}

function usd(text) {
  const match = String(text).match(/\$([0-9,]+\.[0-9]{2})/);
  return match ? Number(match[1].replace(/,/g, "")) : NaN;
}

// --- guard -------------------------------------------------------------------------

test("the shared guard mocks the HyperEVM RPC for every page and context and catches an escape @regression", async ({ page, browser, hyperEvmRpcGuard }) => {
  // 1. visitRoute's page-level empty default answers the poller of a
  //    connected wallet. (The HyperCore catalog is routed only so spotMeta
  //    lands even when the live info API rate-limits.)
  await routeHyperCoreInfo(page);
  await routeHyperCoreAccountStreams(page);
  await visitRoute(page, "/trade");
  const controller = hyperEvmRpcController(page);
  expect(controller).not.toBeNull();
  await debugCall(page, "installWalletSimulator", {
    accounts: [OWNER],
    requestAccounts: [OWNER],
    chainId: ARBITRUM
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");
  await dispatch(page, [":actions/connect-wallet"]);
  await expectOracle(page, "wallet-status", { connected: true, address: OWNER });
  await expect
    .poll(() => storeValue(page, ["hyperevm", "balances", "by-address", OWNER, "native-wei"]), { timeout: 15_000 })
    .toBe("0");
  expect(controller.entries().map((entry) => entry.method)).toEqual(
    expect.arrayContaining(["eth_getBalance", "eth_gasPrice", "eth_call"])
  );

  // 2. A page in a context the test opens itself, loaded with page.goto and
  //    no visitRoute (as shareable-view-url's fresh-link cases do), is
  //    answered by the guard's context-level mock.
  const context = await browser.newContext();
  try {
    const fresh = await context.newPage();
    await routeHyperCoreInfo(fresh);
    await routeHyperCoreAccountStreams(fresh);
    await fresh.goto(`/portfolio?spectate=${SPECTATED}`, { waitUntil: "commit" });
    await expect
      .poll(() => hyperEvmRpcGuard.contextEntries(context)
        .some((entry) => entry.method === "eth_getBalance" && entry.params[0] === SPECTATED), { timeout: 20_000 })
      .toBe(true);
    expect(hyperEvmRpcController(fresh)).toBeNull();
  } finally {
    await context.close();
  }
  expect(await hyperEvmRpcGuard.violations()).toEqual({ unmocked: [], unknown: [], oversizedBatches: [] });

  // 3. The guard watches requests, not routes: one that a later page handler
  //    lets past the mock (abort here; continue() would go live) is reported
  //    although this page has the mock installed.
  const escape = "https://rpc.hyperliquid.xyz/evm?guard-probe";
  await page.route((url) => url.href === escape, (route) => route.abort());
  await page.evaluate((url) =>
    fetch(url, { method: "POST", body: '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' })
      .then(() => "answered", () => "failed"), escape);
  expect((await hyperEvmRpcGuard.violations()).unmocked).toEqual([`POST ${escape}`]);
  await expect(hyperEvmRpcGuard.assertClean()).rejects.toThrow(/without the mock answering them/);
  // Acknowledged so this case's own teardown check passes; every other case
  // fails on such a request.
  hyperEvmRpcGuard.acknowledge();
});

// --- (a) ------------------------------------------------------------------------------

test("(a) HyperEVM rows, chips and the location filter at desktop, /trade 1280 and 375 px @regression", async ({ page }) => {
  await setupAccount(page, {
    evm: { HYPE: "12.5", PURR: "40", USDC: "1000" },
    spot: [...DEFAULT_SPOT, spotBalance("HOPE", "5000")]
  });
  await openBalances(page);

  // HOPE's on-chain decimals() disagrees with spotMeta (live): its row has no
  // move at all.
  await expect(page.locator("[data-parity-id='account-tables']")).toContainText("5,000.00000 HOPE");
  await expect(page.locator("[data-role^='balances-move-spot-122-']")).toHaveCount(0);

  const hypeEvmRow = role(page, "balances-moves-hyperevm-150");
  await expect(hypeEvmRow).toBeVisible();
  await expect(role(page, "location-chip-hyperevm").first()).toHaveText(/EVM/i);
  await expect(role(page, "location-chip-spot").first()).toBeVisible();
  await expect(role(page, "location-chip-perps").first()).toBeVisible();
  await expect(role(page, "balance-row-gas-reserve-note")).toContainText("0.001 kept for gas");

  // The filter narrows the rows and keeps its state in aria-pressed.
  const table = page.locator("[data-parity-id='account-tables']");
  await role(page, "balances-location-filter-hyperevm").click();
  await expect(role(page, "balances-location-filter-hyperevm")).toHaveAttribute("aria-pressed", "true");
  await expect(role(page, "balances-location-filter-all")).toHaveAttribute("aria-pressed", "false");
  await expect(role(page, "balances-moves-spot-1")).toHaveCount(0);
  await expect(role(page, "balances-moves-hyperevm-1")).toBeVisible();
  await role(page, "balances-location-filter-hypercore").click();
  await expect(role(page, "balances-moves-hyperevm-150")).toHaveCount(0);
  await expect(role(page, "balances-moves-spot-1")).toBeVisible();
  await role(page, "balances-location-filter-all").click();
  await expect(table).not.toContainText("HyperEVM balances are read on chain 999.");
  await expect(role(page, "balances-hyperevm-note")).toBeHidden();
  expect(await noHorizontalOverflow(page)).toBe(true);

  // A disabled move keeps focus and shows its reason only while focused:
  // SIX's HyperEVM bridge is empty (live: 0 at its system address). The
  // tooltip is opacity-0 until its group has focus (toBeVisible ignores
  // opacity, so the computed style is what is asserted).
  const sixMove = role(page, "balances-move-spot-6-hyperevm");
  const sixReason = role(page, "balances-move-spot-6-hyperevm-reason");
  await expect(sixMove).toHaveAttribute("aria-disabled", "true");
  await expect(sixMove).toHaveAttribute("aria-describedby", "balances-move-spot-6-hyperevm-reason");
  await page.mouse.move(0, 0);
  await expect(sixReason).toHaveCSS("opacity", "0");
  await sixMove.focus();
  await expect(sixReason).toHaveCSS("opacity", "1");
  await expect(sixReason).toContainText("bridge is empty");
  // Escape hides it without moving focus (WCAG 1.4.13).
  await page.keyboard.press("Escape");
  await expect(sixReason).toHaveCSS("visibility", "hidden");
  await expect(sixMove).toBeFocused();

  // /trade at 1280x800: the Spot USDC row's "To HyperEVM" stays inside the panel.
  await page.setViewportSize({ width: 1280, height: 800 });
  await dispatch(page, [":actions/navigate", "/trade"]);
  await openBalances(page);
  const usdcToEvm = role(page, "balances-move-spot-0-hyperevm");
  await expect(usdcToEvm).toBeVisible();
  const moveBox = await usdcToEvm.boundingBox();
  const panelBox = await page.locator("[data-parity-id='account-tables']").boundingBox();
  expect(moveBox.x + moveBox.width).toBeLessThanOrEqual(panelBox.x + panelBox.width);
  expect(await noHorizontalOverflow(page)).toBe(true);

  // 375 px: the toolbar wraps onto a second row (search and filter, then Hide
  // Small Balances) with every control on screen, the HyperEVM card carries
  // its chip, the filter narrows the cards, and a disabled move's reason
  // shows under its card.
  await page.setViewportSize({ width: 375, height: 812 });
  await dispatch(page, [":actions/navigate", "/portfolio"]);
  await openBalances(page);
  const filter = role(page, "balances-location-filter");
  await expect(filter).toBeVisible();
  const toolbar = page.locator("[data-role='account-info-tab-actions-shell']:visible");
  const searchBox = await toolbar.locator("input[aria-label='Search coins']").boundingBox();
  const filterBox = await filter.boundingBox();
  const hideSmallBox = await toolbar.locator("#hide-small-balances").boundingBox();
  expect(hideSmallBox.y, "Hide Small Balances wraps under the search row").toBeGreaterThanOrEqual(searchBox.y + searchBox.height);
  for (const [name, box] of [["search", searchBox], ["filter", filterBox], ["hide small", hideSmallBox]]) {
    expect(box.x, `${name} left edge`).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width, `${name} right edge`).toBeLessThanOrEqual(375);
  }
  expect(await noHorizontalOverflow(page)).toBe(true);
  const hypeEvmCard = role(page, "mobile-balance-card-hyperevm-150");
  await expect(hypeEvmCard.locator("[data-role='location-chip-hyperevm']")).toBeVisible();
  await expect(hypeEvmCard.locator("[data-role='location-chip-hyperevm']")).toHaveText(/EVM/i);
  const visibleCards = () => page.locator("[data-role^='mobile-balance-card-']:visible")
    .evaluateAll((nodes) => nodes.map((node) => node.dataset.role));
  await role(page, "balances-location-filter-hyperevm").click();
  await expect(role(page, "balances-location-filter-hyperevm")).toHaveAttribute("aria-pressed", "true");
  await expect.poll(async () => (await visibleCards()).filter((name) => !name.startsWith("mobile-balance-card-hyperevm-")))
    .toEqual([]);
  expect(await visibleCards()).toEqual(expect.arrayContaining(["mobile-balance-card-hyperevm-150", "mobile-balance-card-hyperevm-1"]));
  expect(await noHorizontalOverflow(page)).toBe(true);
  await role(page, "balances-location-filter-all").click();
  await expect(role(page, "mobile-balance-card-spot-6")).toBeVisible();
  await role(page, "mobile-balance-card-spot-6").locator("button").first().click();
  const mobileSix = role(page, "balances-move-mobile-spot-6-hyperevm");
  await expect(mobileSix).toBeVisible();
  await expect(mobileSix).toHaveAttribute("aria-disabled", "true");
  await expect(role(page, "balances-move-mobile-spot-6-hyperevm-reason")).toBeVisible();
  await expect(role(page, "balances-move-mobile-spot-6-hyperevm-reason")).toContainText("bridge is empty");
  expect(await noHorizontalOverflow(page)).toBe(true);
});

// --- (b) ------------------------------------------------------------------------------

test("(b) Spot -> HyperEVM PURR signs the exact sendAsset, arrives, and adds the token to the wallet @regression", async ({ page }) => {
  const { rpc } = await setupAccount(page, {
    evm: { HYPE: "12.5" },
    exchange: [OK]
  });
  await openBalances(page);

  await role(page, "balances-move-spot-1-hyperevm").click();
  await expect(modal(page)).toBeVisible();
  await expectPlaces(page, "spot", "hyperevm");
  await expect(role(page, "funding-transfer-asset-option-1").locator("input")).toBeChecked();
  await enterAmount(page, "100");
  const submit = role(page, "funding-transfer-submit");
  await expect(submit).toHaveText("Move 100 PURR to HyperEVM");
  await expect(submit).toBeEnabled();
  await submit.click();

  // Exactly one signed action, the plan's exact sendAsset.
  await expect.poll(async () => (await signedActions(page, "sendAsset")).length).toBe(1);
  const [request] = await signedActions(page, "sendAsset");
  const { signatureChainId, hyperliquidChain, nonce, ...action } = request.action;
  expect(action).toEqual({
    type: "sendAsset",
    destination: PURR_SYSTEM_ADDRESS,
    sourceDex: "spot",
    destinationDex: "spot",
    token: "PURR:0xc1fb593aeffbeb02f85e0308e9956a90",
    amount: "100",
    fromSubAccount: ""
  });
  expect({ signatureChainId, hyperliquidChain }).toEqual({ signatureChainId: ARBITRUM, hyperliquidChain: "Mainnet" });
  expect(nonce).toBe(request.nonce);
  expect((await debugCall(page, "exchangeSimulatorSnapshot")).calls).toHaveLength(1);

  // Arriving until the mocked HyperEVM balance rises by 100, then arrived.
  const success = role(page, "funding-transfer-success");
  await expect(success).toBeVisible();
  await expect(success).toHaveAttribute("data-arrival", "arriving");
  await expect(role(page, "funding-transfer-success-heading")).toHaveText("Sent 100 PURR to HyperEVM");
  await expect(role(page, "funding-transfer-arrival")).toHaveText("Arriving on HyperEVM… usually a few seconds.");
  // A rise short of 100 (an unrelated 50 PURR) is not the move arriving:
  // still arriving after two more balance reads.
  rpc.update((fixture) => setHolding(fixture, OWNER, { PURR: "50" }));
  const balanceReads = () => rpc.entries().filter((entry) => entry.method === "eth_getBalance").length;
  const readsAt50 = balanceReads();
  await expect.poll(balanceReads, { timeout: 15_000 }).toBeGreaterThanOrEqual(readsAt50 + 2);
  await expect.poll(() => storeValue(page, ["hyperevm", "balances", "by-address", OWNER, "token-units", PURR.index]))
    .toBe((50n * 10n ** 18n).toString());
  await expect(success).toHaveAttribute("data-arrival", "arriving");
  rpc.update((fixture) => setHolding(fixture, OWNER, { PURR: "100" }));
  await expect(success).toHaveAttribute("data-arrival", "arrived", { timeout: 15_000 });
  await expect(role(page, "funding-transfer-success-heading")).toHaveText("100 PURR is on HyperEVM");
  await expect(role(page, "funding-transfer-arrival")).toContainText(/^Arrived in \d+ seconds? at your wallet/);

  // Add to wallet: switch to 0x3e7, then watch PURR at its 18 EVM decimals.
  const before = (await walletLog(page)).length;
  await role(page, "funding-transfer-add-to-wallet").click();
  await expect.poll(async () => (await walletLog(page)).slice(before).map((entry) => entry.method))
    .toContain("wallet_watchAsset");
  const log = (await walletLog(page)).slice(before);
  const switchIndex = log.findIndex((entry) => entry.method === "wallet_switchEthereumChain");
  const watchIndex = log.findIndex((entry) => entry.method === "wallet_watchAsset");
  expect(switchIndex).toBeGreaterThanOrEqual(0);
  expect(switchIndex).toBeLessThan(watchIndex);
  expect(log[switchIndex].params).toEqual([{ chainId: HYPEREVM }]);
  expect(log[watchIndex].params).toEqual({
    type: "ERC20",
    options: { address: PURR_ADDRESS, symbol: "PURR", decimals: 18 }
  });
  expect(log.filter((entry) => entry.method === "eth_sendTransaction")).toEqual([]);
});

// --- HyperEVM -> Core helpers ----------------------------------------------------------

// Open HyperEVM -> Spot for `rowKey` ("hyperevm-150" is HYPE) from its
// Balances row, with `amount` typed in.
async function openEvmToSpot(page, rowKey, amount) {
  await openBalances(page);
  await role(page, `balances-move-${rowKey}-spot`).click();
  await expect(modal(page)).toBeVisible();
  await expectPlaces(page, "hyperevm", "spot");
  await enterAmount(page, amount);
}

// Credit Spot with `amount` of `name` once the RPC answers `hash`'s
// successful receipt, as HyperCore does when the bridge lands.
function creditSpotOnReceipt(rpc, info, { name, amount, hash }) {
  rpc.update((fixture) => {
    let credited = false;
    fixture.receiptHooks.push((receiptHash, receipt) => {
      if (credited || receiptHash !== hash || receipt.status !== "0x1") return;
      credited = true;
      const balances = info.spotStates[OWNER].balances.map((row) =>
        row.coin === name ? { ...row, total: String(Number(row.total) + Number(amount)) } : row
      );
      info.spotStates[OWNER] = { balances };
    });
  });
}

function receiptRequests(rpc, hash) {
  return rpc.entries().filter((entry) =>
    entry.method === "eth_getTransactionReceipt" && entry.params[0] === hash
  );
}

// --- (c) ------------------------------------------------------------------------------

test("(c) HyperEVM -> Spot HYPE switches, re-reads the chain and sends one pinned transaction @regression", async ({ page }) => {
  const { rpc, info } = await setupAccount(page, { evm: { HYPE: "12.5" } });
  // Two pending receipt reads keep the run on its progress view long enough
  // to see the announcement.
  rpc.update((fixture) => {
    fixture.receipts[simulatedTxHash(1)] = ["pending", "pending"];
  });
  creditSpotOnReceipt(rpc, info, { name: "HYPE", amount: "10", hash: simulatedTxHash(1) });
  await openEvmToSpot(page, "hyperevm-150", "10");
  const submit = role(page, "funding-transfer-submit");
  await expect(submit).toHaveText("Switch network & move 10 HYPE to Spot");

  const before = (await walletLog(page)).length;
  await submit.click();

  await expect(role(page, "funding-transfer-progress")).toBeVisible();
  await expect(role(page, "funding-transfer-step-announcement")).toHaveText(
    "Step 2 of 2: Send 10 HYPE to Spot. Confirming on HyperEVM…"
  );
  await expect(role(page, "funding-transfer-step-switch-network")).toHaveAttribute("data-step-status", "done");

  const success = role(page, "funding-transfer-success");
  await expect(success).toBeVisible({ timeout: 10_000 });
  await expect(role(page, "funding-transfer-success-heading")).toHaveText(/10 HYPE/);

  const log = (await walletLog(page)).slice(before);
  expect(log).toEqual([
    { method: "eth_chainId" },
    { method: "wallet_switchEthereumChain", params: [{ chainId: HYPEREVM }] },
    { method: "eth_chainId" },
    {
      method: "eth_sendTransaction",
      params: [{
        from: OWNER,
        to: HYPE_SYSTEM_ADDRESS,
        value: "0x8ac7230489e80000",
        chainId: HYPEREVM,
        gas: gasFor(22800n),
        maxFeePerGas: MAX_FEE_PER_GAS,
        maxPriorityFeePerGas: "0x0"
      }]
    }
  ]);

  // Arrival is read from HyperCore Spot, which the fixture credits on receipt.
  await expect(success).toHaveAttribute("data-arrival", "arrived", { timeout: 15_000 });
  await expect(role(page, "funding-transfer-success-heading")).toHaveText("10 HYPE is on Spot");
  expect(receiptRequests(rpc, simulatedTxHash(1)).length).toBe(3);
  expect(await storeValue(page, ["hyperevm", "in-flight", OWNER])).toBeNull();
});

// --- (d) ------------------------------------------------------------------------------

const USDC_UNITS = 1000000000n; // 1,000 USDC at 6 decimals

function usdcApproveTx(hash) {
  return {
    from: OWNER,
    to: USDC_TOKEN_ADDRESS,
    data: encodeApprove(USDC_CORE_DEPOSIT_WALLET, USDC_UNITS),
    chainId: HYPEREVM,
    gas: gasFor(56200n),
    maxFeePerGas: MAX_FEE_PER_GAS,
    maxPriorityFeePerGas: "0x0",
    hash,
    walletChainId: HYPEREVM
  };
}

function usdcDepositTx(hash) {
  return {
    from: OWNER,
    to: USDC_CORE_DEPOSIT_WALLET,
    data: encodeCoreDeposit(USDC_UNITS),
    chainId: HYPEREVM,
    gas: gasFor(63000n),
    maxFeePerGas: MAX_FEE_PER_GAS,
    maxPriorityFeePerGas: "0x0",
    hash,
    walletChainId: HYPEREVM
  };
}

function methodIndex(entries, predicate) {
  return entries.findIndex(predicate);
}

// The wallet's `eth_sendTransaction` params for a recorded transaction.
function sentParams({ hash, walletChainId, ...params }) {
  return params;
}

test("(d) HyperEVM -> Spot USDC approves then deposits through the CoreDepositWallet @regression", async ({ page }) => {
  const { rpc, info } = await setupAccount(page, { evm: { HYPE: "12.5", USDC: "1000" } });
  // The approve stays pending for two reads; its allowance exists only once
  // its receipt is answered (the mock's deposit estimate reverts like native
  // USDC's transferFrom until then). HyperCore credits the deposit.
  rpc.update((fixture) => {
    fixture.receipts[simulatedTxHash(1)] = ["pending", "pending"];
    approveOnReceipt(fixture, { hash: simulatedTxHash(1), owner: OWNER, units: USDC_UNITS });
  });
  creditSpotOnReceipt(rpc, info, { name: "USDC", amount: "1000", hash: simulatedTxHash(2) });
  await openEvmToSpot(page, "hyperevm-0", "1000");
  await expect(role(page, "funding-transfer-submit")).toHaveText("Switch network & move 1,000 USDC to Spot");
  const before = (await walletLog(page)).length;
  await role(page, "funding-transfer-submit").click();

  await expect(role(page, "funding-transfer-success")).toBeVisible({ timeout: 15_000 });
  const { transactions } = await debugCall(page, "walletSimulatorSnapshot");
  expect(transactions).toEqual([usdcApproveTx(simulatedTxHash(1)), usdcDepositTx(simulatedTxHash(2))]);

  // The whole wallet log: one switch, then a chain read right before each send.
  const log = (await walletLog(page)).slice(before);
  expect(log).toEqual([
    { method: "eth_chainId" },
    { method: "wallet_switchEthereumChain", params: [{ chainId: HYPEREVM }] },
    { method: "eth_chainId" },
    { method: "eth_sendTransaction", params: [sentParams(usdcApproveTx(simulatedTxHash(1)))] },
    { method: "eth_chainId" },
    { method: "eth_sendTransaction", params: [sentParams(usdcDepositTx(simulatedTxHash(2)))] }
  ]);

  // The deposit is estimated only after the read that returned the approve's
  // receipt (the third; the first two were pending).
  const entries = rpc.entries();
  const approveReads = entries
    .map((entry, index) => ({ entry, index }))
    .filter(({ entry }) => entry.method === "eth_getTransactionReceipt" && entry.params[0] === simulatedTxHash(1));
  expect(approveReads.length).toBe(3);
  const depositEstimates = entries
    .map((entry, index) => ({ entry, index }))
    .filter(({ entry }) => entry.method === "eth_estimateGas" && String(entry.params[0]?.data).startsWith("0x2b2dfd2c"));
  expect(depositEstimates.length).toBe(1);
  expect(depositEstimates[0].index).toBeGreaterThan(approveReads[2].index);
  await expect(role(page, "funding-transfer-success")).toHaveAttribute("data-arrival", "arrived", { timeout: 15_000 });
});

test("(p) a wallet that leaves HyperEVM after the approve sends no deposit and fails with the switch-network message @regression", async ({ page }) => {
  // Acceptance 7: the wallet moves to Arbitrum right after its first send.
  const { rpc } = await setupAccount(page, {
    evm: { HYPE: "12.5", USDC: "1000" },
    wallet: { chainAfterSends: { 1: ARBITRUM } }
  });
  rpc.update((fixture) => approveOnReceipt(fixture, { hash: simulatedTxHash(1), owner: OWNER, units: USDC_UNITS }));
  await openEvmToSpot(page, "hyperevm-0", "1000");
  const before = (await walletLog(page)).length;
  await role(page, "funding-transfer-submit").click();

  await expect(role(page, "funding-transfer-failed")).toBeVisible({ timeout: 15_000 });
  await expect(role(page, "funding-transfer-error")).toHaveText(CHAIN_LEFT);
  await expect(role(page, "funding-transfer-step-approve")).toHaveAttribute("data-step-status", "done");
  await expect(role(page, "funding-transfer-step-deposit")).toHaveAttribute("data-step-status", "failed");
  const { transactions } = await debugCall(page, "walletSimulatorSnapshot");
  expect(transactions).toEqual([usdcApproveTx(simulatedTxHash(1))]);
  // The chain read before the deposit found Arbitrum, and nothing followed it.
  expect((await walletLog(page)).slice(before).map((entry) => entry.method)).toEqual([
    "eth_chainId",
    "wallet_switchEthereumChain",
    "eth_chainId",
    "eth_sendTransaction",
    "eth_chainId"
  ]);
  expect(await storeValue(page, ["hyperevm", "in-flight", OWNER])).toBeNull();
});

test("(d) HyperEVM -> Spot USDC skips the approve when the allowance covers the amount @regression", async ({ page }) => {
  const { rpc } = await setupAccount(page, { evm: { HYPE: "12.5", USDC: "1000" } });
  rpc.update((fixture) => {
    fixture.allowances[USDC_TOKEN_ADDRESS] = { [OWNER]: { [USDC_CORE_DEPOSIT_WALLET]: USDC_UNITS.toString() } };
  });
  await openEvmToSpot(page, "hyperevm-0", "1000");
  await role(page, "funding-transfer-submit").click();

  await expect(role(page, "funding-transfer-success")).toBeVisible({ timeout: 10_000 });
  const { transactions } = await debugCall(page, "walletSimulatorSnapshot");
  expect(transactions).toEqual([usdcDepositTx(simulatedTxHash(1))]);
  expect(rpc.entries().some((entry) =>
    entry.method === "eth_estimateGas" && String(entry.params[0]?.data).startsWith("0x095ea7b3"))).toBe(false);
});

// --- (h) ------------------------------------------------------------------------------

test("(h) a wallet that rejects the deposit fails the run and Back to edit keeps the draft @regression", async ({ page }) => {
  const { rpc } = await setupAccount(page, {
    evm: { HYPE: "12.5", USDC: "1000" },
    wallet: { sendTransactionErrors: [null, 4001] }
  });
  rpc.update((fixture) => approveOnReceipt(fixture, { hash: simulatedTxHash(1), owner: OWNER, units: USDC_UNITS }));
  await openEvmToSpot(page, "hyperevm-0", "1000");
  await role(page, "funding-transfer-submit").click();

  const failed = role(page, "funding-transfer-failed");
  await expect(failed).toBeVisible({ timeout: 10_000 });
  await expect(role(page, "funding-transfer-error")).toHaveText("Transfer rejected in wallet.");
  await expect(role(page, "funding-transfer-step-approve")).toHaveAttribute("data-step-status", "done");
  await expect(role(page, "funding-transfer-step-deposit")).toHaveAttribute("data-step-status", "failed");
  const { transactions } = await debugCall(page, "walletSimulatorSnapshot");
  expect(transactions.map((tx) => tx.to)).toEqual([USDC_TOKEN_ADDRESS]);

  await role(page, "funding-transfer-back-to-edit").click();
  await expect(role(page, "funding-transfer-form")).toBeVisible();
  await expectPlaces(page, "hyperevm", "spot");
  await expect(role(page, "funding-transfer-asset-option-0").locator("input")).toBeChecked();
  await expect(page.locator("#funding-transfer-amount-input-field")).toHaveValue("1,000");
  await expect(page.locator("#funding-transfer-amount-input-field")).toBeFocused();
});

// --- (i) ------------------------------------------------------------------------------

test("(i) a wallet without HyperEVM (4902) adds the chain with HYPE as native currency, then switches @regression", async ({ page }) => {
  await setupAccount(page, { evm: { HYPE: "12.5" }, wallet: { switchChainErrorCode: 4902 } });
  await openEvmToSpot(page, "hyperevm-150", "10");
  const before = (await walletLog(page)).length;
  await role(page, "funding-transfer-submit").click();

  await expect(role(page, "funding-transfer-success")).toBeVisible({ timeout: 10_000 });
  const log = (await walletLog(page)).slice(before);
  expect(log.map((entry) => entry.method)).toEqual([
    "eth_chainId",
    "wallet_switchEthereumChain",
    "wallet_addEthereumChain",
    "wallet_switchEthereumChain",
    "eth_chainId",
    "eth_sendTransaction"
  ]);
  expect(log[2].params).toEqual([{
    chainId: HYPEREVM,
    chainName: "HyperEVM",
    nativeCurrency: { name: "HYPE", symbol: "HYPE", decimals: 18 },
    rpcUrls: ["https://rpc.hyperliquid.xyz/evm"],
    blockExplorerUrls: ["https://hyperevmscan.io"]
  }]);
  expect(log[5].params[0]).toMatchObject({ chainId: HYPEREVM, to: HYPE_SYSTEM_ADDRESS });
});

// --- (j) ------------------------------------------------------------------------------

test("(j) a wallet that can't switch (4200) explains it and offers Try again @regression", async ({ page }) => {
  await setupAccount(page, { evm: { HYPE: "12.5" }, wallet: { switchChainErrorCode: 4200 } });
  await openEvmToSpot(page, "hyperevm-150", "10");
  await role(page, "funding-transfer-submit").click();

  await expect(role(page, "funding-transfer-failed")).toBeVisible({ timeout: 10_000 });
  await expect(role(page, "funding-transfer-error")).toHaveText(SWITCH_UNSUPPORTED);
  await expect(role(page, "funding-transfer-step-switch-network")).toHaveAttribute("data-step-status", "failed");
  const tryAgain = role(page, "funding-transfer-try-again");
  await expect(tryAgain).toBeVisible();
  // It re-enables the form rather than resubmitting, so it says so.
  await expect(tryAgain).toHaveText("Try switching again");
  expect((await walletLog(page)).filter((entry) => entry.method === "eth_sendTransaction")).toEqual([]);

  // It forgets the cached refusal and returns to the kept draft.
  await tryAgain.click();
  await expect(role(page, "funding-transfer-form")).toBeVisible();
  await expect(page.locator("#funding-transfer-amount-input-field")).toHaveValue("10");
  await expect(role(page, "funding-transfer-blocked")).toHaveCount(0);
  await expect(role(page, "funding-transfer-submit")).toBeEnabled();
});

// --- (k) ------------------------------------------------------------------------------

// While a sent move's receipt is outstanding the user is never handed back an
// editable form: the progress view shows, and its submit stays disabled.
async function expectNoEditableForm(page) {
  await expect(role(page, "funding-transfer-progress")).toBeVisible();
  await expect(role(page, "funding-transfer-form")).toHaveCount(0);
  await expect(page.locator("#funding-transfer-amount-input-field")).toHaveCount(0);
  await expect(role(page, "funding-transfer-submit")).toBeDisabled();
}

test("(k) a rate-limited receipt read is retried, never reopens the form, and the move still succeeds @regression", async ({ page }) => {
  const { rpc } = await setupAccount(page, { evm: { HYPE: "12.5" } });
  const hash = simulatedTxHash(1);
  rpc.update((fixture) => {
    fixture.receipts[hash] = ["rate-limited", "pending", "pending"];
  });
  await openEvmToSpot(page, "hyperevm-150", "10");
  await role(page, "funding-transfer-submit").click();

  // After the rate-limited read and through both pending reads.
  await expect.poll(() => receiptRequests(rpc, hash).length).toBeGreaterThanOrEqual(1);
  await expectNoEditableForm(page);
  await expect.poll(() => receiptRequests(rpc, hash).length).toBeGreaterThanOrEqual(2);
  await expectNoEditableForm(page);
  await expect.poll(() => receiptRequests(rpc, hash).length).toBeGreaterThanOrEqual(3);
  await expectNoEditableForm(page);

  await expect(role(page, "funding-transfer-success")).toBeVisible({ timeout: 10_000 });
  expect(rpc.fixture.receipts[hash]).toEqual([]);
  expect(receiptRequests(rpc, hash).length).toBe(4);
  await expect(role(page, "funding-transfer-failed")).toHaveCount(0);
  expect(await storeValue(page, ["hyperevm", "in-flight", OWNER])).toBeNull();
});

test("(k) a receipt that never arrives ends pending with the explorer link and blocks another HyperEVM move @regression", async ({ page }) => {
  // The foreground wait gives up after 3 minutes
  // (rpc/default-receipt-timeout-ms); the page clock jumps past it.
  await page.clock.install();
  const { rpc } = await setupAccount(page, { evm: { HYPE: "12.5" } });
  const hash = simulatedTxHash(1);
  rpc.update((fixture) => {
    fixture.receipts[hash] = Array.from({ length: 1000 }, () => "pending");
  });
  await openEvmToSpot(page, "hyperevm-150", "10");
  await role(page, "funding-transfer-submit").click();
  await expect.poll(() => receiptRequests(rpc, hash).length).toBeGreaterThanOrEqual(2);
  await expectNoEditableForm(page);
  // The disabled submit lost focus; the progress heading has it, not Close.
  await expect(role(page, "funding-transfer-progress-heading")).toBeFocused();

  await page.clock.fastForward("03:05");
  const pending = role(page, "funding-transfer-pending");
  await expect(pending).toBeVisible({ timeout: 10_000 });
  await expect(role(page, "funding-transfer-pending-heading")).toHaveText("Submitted — confirmation pending");
  await expect(role(page, "funding-transfer-pending-heading")).toBeFocused();
  await expect(role(page, "funding-transfer-pending-detail")).not.toContainText("finish on its own");
  await expect(role(page, "funding-transfer-explorer-link")).toHaveAttribute("href", `https://hyperevmscan.io/tx/${hash}`);
  await expect(role(page, "funding-transfer-form")).toHaveCount(0);
  await expect(role(page, "funding-transfer-submit")).toHaveCount(0);
  expect(await storeValue(page, ["hyperevm", "in-flight", OWNER, "hashes"])).toEqual([hash]);

  // Closing and starting another HyperEVM -> Spot move: it is blocked until
  // the first one resolves, and nothing more reaches the wallet.
  await role(page, "funding-transfer-done").click();
  await expect(modal(page)).toHaveCount(0);
  await openEvmToSpot(page, "hyperevm-150", "1");
  await expect(role(page, "funding-transfer-blocked")).toHaveAttribute("data-blocked-code", "in-flight");
  await expect(role(page, "funding-transfer-blocked-status")).toContainText(IN_FLIGHT_PENDING);
  await expect(role(page, "funding-transfer-submit")).toBeDisabled();
  expect((await walletLog(page)).filter((entry) => entry.method === "eth_sendTransaction")).toHaveLength(1);
});

// --- (e) ------------------------------------------------------------------------------

test("(e) the gas fix sends one top-up per click, keeps the draft, and an exchange error re-enables it @regression", async ({ page }) => {
  // The refusal is held for 1.5 s, so the double click's second click (and a
  // dispatched submit) are sure to arrive while the first is submitting.
  const exchangeError = { status: "err", response: "Insufficient balance for token transfer.", delayMs: 1500 };
  const { rpc } = await setupAccount(page, {
    evm: { HYPE: "0", PURR: "40" },
    exchange: [exchangeError, OK]
  });
  await openBalances(page);
  await role(page, "balances-move-hyperevm-1-spot").click();
  await expectPlaces(page, "hyperevm", "spot");

  // Blocked before any amount is typed, with the fix.
  const blocked = role(page, "funding-transfer-blocked");
  await expect(blocked).toHaveAttribute("data-blocked-code", "no-evm-gas");
  await expect(role(page, "funding-transfer-blocked-status")).toContainText("You need HYPE on HyperEVM to pay gas");
  const fix = role(page, "funding-transfer-gas-fix");
  await expect(fix).toHaveText("Send 0.05 HYPE from Spot to HyperEVM");
  await expect(fix).toBeEnabled();
  await enterAmount(page, "25");

  // A double click sends exactly one top-up, and so does a submit dispatched
  // straight to the action while it is submitting; the exchange refuses it.
  await fix.dblclick();
  await expect(fix).toHaveAttribute("data-fix-status", "submitting");
  await dispatch(page, [":actions/submit-funding-transfer-gas-topup"]);
  expect(await signedActions(page, "sendAsset")).toHaveLength(1);
  await expect(fix).toHaveAttribute("data-fix-status", "failed");
  expect(await signedActions(page, "sendAsset")).toHaveLength(1);
  const [topUp] = await signedActions(page, "sendAsset");
  const { signatureChainId, hyperliquidChain, nonce, ...action } = topUp.action;
  expect(action).toEqual({
    type: "sendAsset",
    destination: HYPE_SYSTEM_ADDRESS,
    sourceDex: "spot",
    destinationDex: "spot",
    token: `HYPE:${HYPE.tokenId}`,
    amount: "0.05",
    fromSubAccount: ""
  });
  await expect(role(page, "funding-transfer-fix-status")).toContainText("Insufficient balance for token transfer.");
  await expect(fix).toBeEnabled();

  // The draft is untouched.
  await expect(page.locator("#funding-transfer-amount-input-field")).toHaveValue("25");
  await expect(role(page, "funding-transfer-asset-option-1").locator("input")).toBeChecked();
  await expectPlaces(page, "hyperevm", "spot");

  // Retrying sends the second (and last) top-up.
  await fix.click();
  await expect(fix).toHaveAttribute("data-fix-status", "sent");
  await expect(role(page, "funding-transfer-fix-status")).toHaveText("Sent 0.05 HYPE. Waiting for it to arrive…");
  // Busy, but still focusable (aria-disabled, no native disabled), so a
  // keyboard user who pressed it keeps focus on it.
  await expect(fix).toHaveAttribute("aria-disabled", "true");
  expect(await fix.evaluate((el) => el.disabled)).toBe(false);
  expect(await signedActions(page, "sendAsset")).toHaveLength(2);
  // Refused at the action too while sent, not only by the disabled button.
  await dispatch(page, [":actions/submit-funding-transfer-gas-topup"]);
  await expect(fix).toHaveAttribute("data-fix-status", "sent");
  expect(await signedActions(page, "sendAsset")).toHaveLength(2);

  // Once the 0.05 HYPE lands on HyperEVM, the block clears and the kept
  // draft can be submitted.
  rpc.update((fixture) => setHolding(fixture, OWNER, { HYPE: "0.05" }));
  await expect(role(page, "funding-transfer-blocked")).toHaveCount(0, { timeout: 15_000 });
  await expect(role(page, "funding-transfer-submit")).toHaveText("Switch network & move 25 PURR to Spot");
  await expect(role(page, "funding-transfer-submit")).toBeEnabled();
});

// --- (f) ------------------------------------------------------------------------------

test("(f) a selected subaccount sees why HyperEVM moves are master-only @regression", async ({ page }) => {
  const { info } = await setupAccount(page, {
    evm: { HYPE: "12.5" },
    subAccounts: [{
      name: "Desk",
      subAccountUser: SUBACCOUNT,
      master: OWNER,
      clearinghouseState: clearinghouseState("1000.0"),
      spotState: { balances: [spotBalance("PURR", "100")] }
    }]
  });
  info.clearinghouseStates[SUBACCOUNT] = clearinghouseState("1000.0");
  info.spotStates[SUBACCOUNT] = { balances: [spotBalance("PURR", "100")] };
  await expect.poll(() => storeValue(page, ["account-context", "subaccounts", "status"])).toBe("loaded");
  await dispatch(page, [":actions/select-subaccount", SUBACCOUNT]);
  await expect.poll(() => storeValue(page, ["account-context", "subaccounts", "selected-address"])).toBe(SUBACCOUNT);
  await waitForAccountReads(page, SUBACCOUNT);

  // The Balances note and the PURR Spot row's disabled move both say why.
  // (The Perps row has no HyperEVM move: Perps -> HyperEVM goes through Spot.)
  await openBalances(page);
  await expect(role(page, "balances-hyperevm-moves-blocked")).toHaveText(MASTER_ONLY);
  await expect(role(page, "balances-move-perps-usdc-hyperevm")).toHaveCount(0);
  const purrMove = role(page, "balances-move-spot-1-hyperevm");
  const purrReason = role(page, "balances-move-spot-1-hyperevm-reason");
  await expect(purrMove).toHaveAttribute("aria-disabled", "true");
  await page.mouse.move(0, 0);
  await expect(purrReason).toHaveCSS("opacity", "0");
  await purrMove.focus();
  await expect(purrReason).toHaveCSS("opacity", "1");
  await expect(purrReason).toHaveText(MASTER_ONLY);
  // The pointer can move from the action onto its reason (across the gap)
  // without closing it. The target is the reason's text just past the gap,
  // straight below or above the action: on a short table the rows viewport
  // clips the far part of the tooltip (a recorded remaining risk).
  await purrMove.blur();
  await purrMove.hover();
  const moveBox = await purrMove.boundingBox();
  const reasonBox = await purrReason.boundingBox();
  const below = reasonBox.y >= moveBox.y;
  const targetY = below ? reasonBox.y + 10 : reasonBox.y + reasonBox.height - 10;
  await page.mouse.move(moveBox.x + moveBox.width / 2, targetY, { steps: 5 });
  await expect(purrReason).toHaveCSS("opacity", "1");
  expect(await page.evaluate(([x, y]) => {
    const hit = document.elementFromPoint(x, y);
    return Boolean(hit && hit.closest("[data-role='balances-move-spot-1-hyperevm-reason']"));
  }, [moveBox.x + moveBox.width / 2, targetY])).toBe(true);

  // The strip's Spot <-> HyperEVM connector is disabled with the reason.
  const connector = role(page, "portfolio-funds-connector-spot-evm");
  await expect(connector).toHaveAttribute("aria-disabled", "true");
  await expect(role(page, "portfolio-funds-reasons")).toContainText(MASTER_ONLY);

  // The Transfer modal keeps HyperEVM visible but disabled, with the reason
  // as visible text it points at.
  await role(page, "portfolio-action-perps-spot").click();
  await expect(modal(page)).toBeVisible();
  const toEvm = role(page, "funding-transfer-to-hyperevm");
  await expect(toEvm).toHaveAttribute("aria-disabled", "true");
  const reasonId = await toEvm.getAttribute("aria-describedby");
  await expect(page.locator(`#${reasonId}`)).toHaveText(MASTER_ONLY);
  await expect(page.locator(`#${reasonId}`)).toBeVisible();
  await expect(role(page, "funding-transfer-place-reasons")).toContainText(MASTER_ONLY);
});

// --- (g) ------------------------------------------------------------------------------

test("(g) spectate shows HyperEVM rows read-only with the reason in the note @regression", async ({ page }) => {
  const rpc = await routeHyperEvmRpc(page, hyperEvmFixture({ owner: SPECTATED, holdings: { HYPE: "7.25", PURR: "12" } }));
  trackInfoResponses(page);
  const info = hyperCoreInfoFixture();
  info.spotStates[SPECTATED] = { balances: [spotBalance("USDC", "50.0"), spotBalance("PURR", "100")] };
  info.clearinghouseStates[SPECTATED] = clearinghouseState("250.0");
  await routeCoreInfo(page, info);
  await routeHyperCoreAccountStreams(page);
  await visitRoute(page, `/trade?spectate=${SPECTATED}`);
  await waitForAccountReads(page, SPECTATED);
  await openBalances(page);

  const table = page.locator("[data-parity-id='account-tables']");
  await expect(role(page, "location-chip-hyperevm").first()).toBeVisible();
  await expect(table).toContainText("7.25000000 HYPE");
  await expect(table).toContainText("12.00000 PURR");
  await expect(table).toContainText("Contract");
  for (const header of ["Send", "Transfer", "Repay"]) {
    await expect(table.getByText(header, { exact: true })).toHaveCount(0);
  }
  await expect(page.locator("[data-role^='balances-move-']")).toHaveCount(0);
  await expect(role(page, "balances-hyperevm-moves-blocked")).toHaveText(SPECTATE_READ_ONLY);
  expect(rpc.entries().some((entry) => entry.method === "eth_getBalance" && entry.params[0] === SPECTATED)).toBe(true);
});

// --- (l) ------------------------------------------------------------------------------

test("(l) the Portfolio strip totals HyperEVM with Total Equity and its connectors preset Transfer @regression", async ({ page }) => {
  await setupAccount(page, { evm: { HYPE: "12.5", USDC: "1000" } });
  const strip = role(page, "portfolio-funds-strip");
  await expect(strip).toBeVisible();
  await expect(role(page, "portfolio-funds-card-evm-value")).toHaveText(/^\$[0-9,]+\.[0-9]{2}$/);

  // Total value = Total Equity + HyperEVM, read in one frame (live prices tick).
  const figures = await page.evaluate(() => {
    const text = (name) => document.querySelector(`[data-role='${name}']`)?.textContent || "";
    return {
      total: text("portfolio-funds-total-value"),
      equity: text("portfolio-funds-total-status"),
      evm: text("portfolio-funds-card-evm-value")
    };
  });
  expect(figures.equity).toMatch(/^Total Equity \$/);
  // Each figure is rounded to the cent on its own, so they may differ by one.
  expect(Math.abs(usd(figures.total) - (usd(figures.equity) + usd(figures.evm)))).toBeLessThanOrEqual(0.0101);
  expect(usd(figures.evm)).toBeGreaterThanOrEqual(1000);

  const perpsSpot = role(page, "portfolio-funds-connector-perps-spot");
  await perpsSpot.click();
  await expect(modal(page)).toBeVisible();
  await expectPlaces(page, "perps", "spot");
  await page.keyboard.press("Escape");
  await expect(modal(page)).toHaveCount(0);
  await expect(perpsSpot).toBeFocused();

  const spotEvm = role(page, "portfolio-funds-connector-spot-evm");
  await spotEvm.click();
  await expect(modal(page)).toBeVisible();
  await expectPlaces(page, "spot", "hyperevm");
  await expect(role(page, "funding-transfer-asset-list")).toBeVisible();
  await expect(role(page, "funding-transfer-asset-list").locator("input:checked")).toHaveCount(1);
  await page.keyboard.press("Escape");
  await expect(spotEvm).toBeFocused();
});

// --- (m) ------------------------------------------------------------------------------

test("(m) the /trade HyperEVM line's Move opens HyperEVM -> Spot @regression", async ({ page }) => {
  await setupAccount(page, { route: "/trade", evm: { HYPE: "12.5", USDC: "1000" } });
  const line = role(page, "account-equity-hyperevm-line");
  await expect(line).toBeVisible();
  await expect(role(page, "account-equity-hyperevm-value")).toHaveText(/^\$[0-9,]+\.[0-9]{2}$/);
  const move = role(page, "account-equity-hyperevm-move");
  await move.click();
  await expect(modal(page)).toBeVisible();
  await expectPlaces(page, "hyperevm", "spot");
  await page.keyboard.press("Escape");
  await expect(modal(page)).toHaveCount(0);
  await expect(move).toBeFocused();
});

// --- (n) ------------------------------------------------------------------------------

for (const { route, viewport } of [
  { route: "/portfolio", viewport: { width: 1280, height: 800 } },
  { route: "/portfolio", viewport: { width: 1440, height: 900 } },
  { route: "/trade", viewport: { width: 1280, height: 800 } }
]) {
  test(`(n) opened from the lowest Balances row at the bottom of ${route} ${viewport.width}x${viewport.height}, the fix and submit are in view and hit-testable @regression`, async ({ page }) => {
    await page.setViewportSize(viewport);
    // No HYPE on HyperEVM, so a HyperEVM -> Spot draft shows the gas fix.
    await setupAccount(page, { route, evm: { HYPE: "0", PURR: "1" } });
    await openBalances(page);

    const lowest = await page.evaluate(() => {
      const moves = [...document.querySelectorAll("[data-role^='balances-move-']")]
        .filter((node) => !node.dataset.role.startsWith("balances-move-mobile-") &&
          !node.dataset.role.endsWith("-reason") && node.getClientRects().length > 0);
      moves.sort((a, b) => b.getBoundingClientRect().top - a.getBoundingClientRect().top);
      return moves[0]?.dataset.role || null;
    });
    expect(lowest).toBe("balances-move-hyperevm-1-spot");
    const opener = role(page, lowest);

    // The worst case: the opener as low on screen as a pointer can reach it.
    const placed = await placeNearViewportBottom(opener);
    expect(placed.bottom, `opener ${JSON.stringify(placed)}`).toBeGreaterThanOrEqual(0.75 * viewport.height);
    expect(placed.bottom).toBeLessThanOrEqual(viewport.height);
    expect(await hitTestable(opener)).toBe(true);
    const openerBox = await opener.boundingBox();
    await opener.click();
    const clickedBox = await opener.boundingBox();
    expect(Math.abs(clickedBox.y - openerBox.y), "the click did not scroll the opener").toBeLessThanOrEqual(2);
    await expect(modal(page)).toBeVisible();
    await expect(role(page, "funding-transfer-blocked")).toHaveAttribute("data-blocked-code", "no-evm-gas");

    const fix = role(page, "funding-transfer-gas-fix");
    const submit = role(page, "funding-transfer-submit");
    const fixBox = await fix.boundingBox();
    const submitBox = await submit.boundingBox();
    expect(inViewport(fixBox, viewport), `fix ${JSON.stringify(fixBox)}`).toBe(true);
    expect(inViewport(submitBox, viewport), `submit ${JSON.stringify(submitBox)}`).toBe(true);
    expect(await hitTestable(fix), "nothing paints over the fix").toBe(true);
    expect(await hitTestable(submit), "nothing paints over the submit").toBe(true);
    await fix.click({ trial: true });
    expect(await noHorizontalOverflow(page)).toBe(true);
  });
}

// --- (o) ------------------------------------------------------------------------------

test("(o) the 375 px mobile sheet opens from a card and keeps its submit in view @regression", async ({ page }) => {
  const viewport = { width: 375, height: 812 };
  await page.setViewportSize(viewport);
  await setupAccount(page, { evm: { HYPE: "12.5" } });
  await openBalances(page);

  await role(page, "mobile-balance-card-hyperevm-150").locator("button").first().click();
  const move = role(page, "balances-move-mobile-hyperevm-150-spot");
  await expect(move).toBeVisible();
  await move.click();

  const sheet = modal(page);
  await expect(sheet).toBeVisible();
  await expectPlaces(page, "hyperevm", "spot");
  const sheetBox = await sheet.boundingBox();
  expect(sheetBox.x).toBeLessThanOrEqual(1);
  expect(sheetBox.width).toBeGreaterThanOrEqual(viewport.width - 2);
  expect(sheetBox.y + sheetBox.height).toBeGreaterThanOrEqual(viewport.height - 1);

  // From and To stack on a phone.
  const fromBox = await role(page, "funding-transfer-from-group").boundingBox();
  const toBox = await role(page, "funding-transfer-to-group").boundingBox();
  expect(toBox.y).toBeGreaterThan(fromBox.y + fromBox.height - 1);

  await enterAmount(page, "10");
  const submit = role(page, "funding-transfer-submit");
  await expect(submit).toHaveText("Switch network & move 10 HYPE to Spot");
  await expect(submit).toBeEnabled();
  expect(inViewport(await submit.boundingBox(), viewport)).toBe(true);
  // The app's fixed mobile footer paints over nothing in the sheet.
  expect(await hitTestable(submit), "nothing paints over the sheet's submit").toBe(true);
  await submit.click({ trial: true });
  expect(await noHorizontalOverflow(page)).toBe(true);
});

// --- acceptance 8, 9 and 11 -----------------------------------------------------------

test("(e) with less than 0.05 HYPE on Spot the gas fix is disabled with its reason @regression", async ({ page }) => {
  await setupAccount(page, {
    evm: { HYPE: "0", PURR: "40" },
    spot: [spotBalance("USDC", "2105.4"), spotBalance("HYPE", "0.01")]
  });
  await openBalances(page);
  await role(page, "balances-move-hyperevm-1-spot").click();
  await expect(role(page, "funding-transfer-blocked")).toHaveAttribute("data-blocked-code", "no-evm-gas");
  const fix = role(page, "funding-transfer-gas-fix");
  await expect(fix).toHaveAttribute("aria-disabled", "true");
  await expect(role(page, "funding-transfer-fix-reason")).toHaveText("You need at least 0.05 HYPE on Spot.");
  await expect(fix).toHaveAttribute("aria-describedby", "funding-transfer-fix-reason");
  // Not natively disabled (so it keeps focus), but a click sends nothing.
  // (Playwright treats aria-disabled as not enabled, hence `force`.)
  expect(await fix.evaluate((el) => el.disabled)).toBe(false);
  await fix.click({ force: true });
  expect(await signedActions(page, "sendAsset")).toHaveLength(0);
  await expect(fix).toHaveAttribute("data-fix-status", "idle");
});

test("(e) the gas fix keeps keyboard focus while busy, and focus moves to the submit when gas lands @regression", async ({ page }) => {
  const { rpc } = await setupAccount(page, { evm: { HYPE: "0", PURR: "40" }, exchange: [OK] });
  await openBalances(page);
  await role(page, "balances-move-hyperevm-1-spot").click();
  await expect(role(page, "funding-transfer-blocked")).toHaveAttribute("data-blocked-code", "no-evm-gas");
  await enterAmount(page, "25");

  const fix = role(page, "funding-transfer-gas-fix");
  const close = role(page, "funding-modal-close");
  await fix.focus();
  await page.keyboard.press("Enter");
  await expect(fix).toHaveAttribute("data-fix-status", "sent");
  await expect(fix).toBeFocused();
  await expect(close).not.toBeFocused();
  // A repeated Enter lands on the busy fix, which ignores it; the modal and
  // its draft stay.
  await page.keyboard.press("Enter");
  await expect(modal(page)).toBeVisible();
  expect(await signedActions(page, "sendAsset")).toHaveLength(1);

  // The gas lands: the fix goes away and focus moves to the submit on
  // purpose, never to Close.
  rpc.update((fixture) => setHolding(fixture, OWNER, { HYPE: "0.05" }));
  await expect(role(page, "funding-transfer-gas-fix")).toHaveCount(0, { timeout: 15_000 });
  const submit = role(page, "funding-transfer-submit");
  await expect(submit).toHaveText("Switch network & move 25 PURR to Spot");
  await expect(submit).toBeFocused();
  await expect(close).not.toBeFocused();
  await expect(page.locator("#funding-transfer-amount-input-field")).toHaveValue("25");
});

test("unknown HyperEVM balances read as unavailable: no gas fix, and the strip total is a dash @regression", async ({ page }) => {
  // Every HyperEVM read is rate-limited, so no read ever lands.
  const { rpc } = await setupAccount(page, {
    evm: {},
    rpcOverrides: { rateLimitNext: 1000 },
    waitForEvm: false
  });
  await expect.poll(() => storeValue(page, ["hyperevm", "balances", "by-address", OWNER, "error-kind"]))
    .toBe("rate-limited");
  expect(rpc.entries().length).toBeGreaterThan(0);

  await expect(role(page, "portfolio-funds-total-value")).toHaveText("—");
  await expect(role(page, "portfolio-funds-total-status")).toHaveText("HyperEVM balances unavailable");

  await role(page, "portfolio-action-perps-spot").click();
  await role(page, "funding-transfer-from-hyperevm").click();
  await expectPlaces(page, "hyperevm", "spot");
  await expect(role(page, "funding-transfer-blocked")).toHaveAttribute("data-checking", "true");
  await expect(role(page, "funding-transfer-blocked-status")).toContainText("HyperEVM balances are unavailable right now.");
  await expect(role(page, "funding-transfer-gas-fix")).toHaveCount(0);
  await expect(role(page, "funding-transfer-submit")).toBeDisabled();
});

test("an unactivated HyperCore account can't move PURR in, and USDC shows the activation fee @regression", async ({ page }) => {
  await setupAccount(page, { evm: { HYPE: "12.5", PURR: "40", USDC: "1000" }, role: "missing" });
  await openBalances(page);
  await role(page, "balances-move-hyperevm-1-spot").click();
  await expectPlaces(page, "hyperevm", "spot");
  await expect(role(page, "funding-transfer-blocked")).toHaveAttribute("data-blocked-code", "core-account-missing");
  await expect(role(page, "funding-transfer-blocked-status")).toContainText(
    "Your HyperCore account isn't active yet. Move more than 1 USDC from HyperEVM to Spot first"
  );

  await role(page, "funding-transfer-asset-option-0").click();
  await expect(role(page, "funding-transfer-blocked")).toHaveCount(0);
  await enterAmount(page, "10");
  await expect(role(page, "funding-transfer-summary")).toContainText("Activation fee");
  await expect(role(page, "funding-transfer-summary")).toContainText("1 USDC (first transfer only)");
  await expect(role(page, "funding-transfer-submit")).toBeEnabled();
});

// --- acceptance 16 --------------------------------------------------------------------

// Press Tab until the focused element matches `selector`.
async function tabUntil(page, selector, limit = 90) {
  for (let i = 0; i < limit; i += 1) {
    if (await page.evaluate((css) => Boolean(document.activeElement?.matches(css)), selector)) return;
    await page.keyboard.press("Tab");
  }
  throw new Error(`Tab never reached ${selector}`);
}

const byRole = (name) => `[data-role='${name}']`;
const checkedAsset = (page) => role(page, "funding-transfer-asset-list").locator("input:checked");

test("a Spot -> HyperEVM move is completable by keyboard alone, places and asset included @regression", async ({ page }) => {
  await setupAccount(page, { evm: { HYPE: "12.5" }, exchange: [OK] });
  await openBalances(page);

  // Tab from the Balances tab to the PURR row's "To HyperEVM".
  await tabUntil(page, byRole("balances-move-spot-1-hyperevm"));
  const opener = role(page, "balances-move-spot-1-hyperevm");
  await page.keyboard.press("Enter");
  await expect(modal(page)).toBeVisible();
  await expectPlaces(page, "spot", "hyperevm");

  // Change the destination with Space, then come back.
  await tabUntil(page, byRole("funding-transfer-to-perps"));
  await page.keyboard.press("Space");
  await expectPlaces(page, "spot", "perps");
  await tabUntil(page, byRole("funding-transfer-to-hyperevm"));
  await page.keyboard.press("Space");
  await expectPlaces(page, "spot", "hyperevm");

  // Pick the asset with the arrow keys (a native radio group).
  await tabUntil(page, `${byRole("funding-transfer-asset-list")} input[type='radio']`);
  const purr = role(page, "funding-transfer-asset-option-1").locator("input");
  const start = await checkedAsset(page).getAttribute("value");
  await page.keyboard.press("ArrowDown");
  await expect(checkedAsset(page)).not.toHaveAttribute("value", start ?? "");
  for (let i = 0; i < 8 && !(await purr.isChecked()); i += 1) {
    await page.keyboard.press("ArrowDown");
  }
  await expect(purr).toBeChecked();
  await expect(purr).toBeFocused();

  const amount = page.locator("#funding-transfer-amount-input-field");
  await tabUntil(page, "#funding-transfer-amount-input-field");
  await page.keyboard.type("100");
  await expect(amount).toHaveValue("100");
  const submit = role(page, "funding-transfer-submit");
  await expect(submit).toHaveText("Move 100 PURR to HyperEVM");
  await tabUntil(page, byRole("funding-transfer-submit"));
  await page.keyboard.press("Enter");

  await expect(role(page, "funding-transfer-success")).toBeVisible();
  // The new view's heading takes focus, never the dialog's Close button.
  await expect(role(page, "funding-transfer-success-heading")).toBeFocused();
  expect(await signedActions(page, "sendAsset")).toHaveLength(1);
  await tabUntil(page, `${byRole("funding-transfer-success")} ${byRole("funding-transfer-done")}`);
  await page.keyboard.press("Enter");
  await expect(modal(page)).toHaveCount(0);
  await expect(opener).toBeFocused();
});

test("a HyperEVM -> Spot move recovers from a wallet rejection and finishes by keyboard alone @regression", async ({ page }) => {
  const { rpc, info } = await setupAccount(page, {
    evm: { HYPE: "12.5" },
    wallet: { sendTransactionErrors: [4001] }
  });
  creditSpotOnReceipt(rpc, info, { name: "HYPE", amount: "10", hash: simulatedTxHash(1) });
  await openBalances(page);

  await tabUntil(page, byRole("balances-move-hyperevm-150-spot"));
  const opener = role(page, "balances-move-hyperevm-150-spot");
  await page.keyboard.press("Enter");
  await expect(modal(page)).toBeVisible();
  await expectPlaces(page, "hyperevm", "spot");

  await tabUntil(page, "#funding-transfer-amount-input-field");
  await page.keyboard.type("10");
  await expect(role(page, "funding-transfer-submit")).toHaveText("Switch network & move 10 HYPE to Spot");
  await tabUntil(page, byRole("funding-transfer-submit"));
  await page.keyboard.press("Enter");

  // The wallet rejects the send: Back to edit, by key, keeps the draft.
  await expect(role(page, "funding-transfer-failed")).toBeVisible({ timeout: 10_000 });
  await expect(role(page, "funding-transfer-failed-heading")).toBeFocused();
  await expect(role(page, "funding-transfer-error")).toHaveText("Transfer rejected in wallet.");
  await expect(role(page, "funding-transfer-step-send")).toContainText("Rejected in wallet");
  await tabUntil(page, byRole("funding-transfer-back-to-edit"));
  await page.keyboard.press("Enter");
  await expect(role(page, "funding-transfer-form")).toBeVisible();
  await expect(page.locator("#funding-transfer-amount-input-field")).toBeFocused();
  await expect(page.locator("#funding-transfer-amount-input-field")).toHaveValue("10");

  await tabUntil(page, byRole("funding-transfer-submit"));
  await page.keyboard.press("Enter");
  await expect(role(page, "funding-transfer-success")).toBeVisible({ timeout: 10_000 });
  await expect(role(page, "funding-transfer-success-heading")).toBeFocused();
  await expect(role(page, "funding-transfer-success")).toHaveAttribute("data-arrival", "arrived", { timeout: 15_000 });
  const { transactions } = await debugCall(page, "walletSimulatorSnapshot");
  expect(transactions.map((tx) => tx.to)).toEqual([HYPE_SYSTEM_ADDRESS]);

  await tabUntil(page, `${byRole("funding-transfer-success")} ${byRole("funding-transfer-done")}`);
  await page.keyboard.press("Enter");
  await expect(modal(page)).toHaveCount(0);
  await expect(opener).toBeFocused();
});
