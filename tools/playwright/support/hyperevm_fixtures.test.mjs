import assert from "node:assert/strict";
import test from "node:test";

import {
  ALLOWANCE_REVERT,
  BATCH_TOO_LARGE_BODY,
  HYPE_SYSTEM_ADDRESS,
  LIVE_SYSTEM_UNITS,
  MAINNET_SPOT_META,
  MULTICALL3_ADDRESS,
  PERP_META_AND_ASSET_CTXS,
  SPOT_ASSET_CTXS,
  SPOT_UNIVERSE,
  SELECTORS,
  SPOT_TOKENS,
  USDC_CORE_DEPOSIT_WALLET,
  USDC_TOKEN_ADDRESS,
  NOT_HANDLED,
  answerHyperCoreInfo,
  approveOnReceipt,
  createHyperEvmRpcState,
  decodeAggregate3Calls,
  emptyHyperEvmFixture,
  encodeAggregate3Results,
  encodeApprove,
  encodeBalanceOf,
  encodeCoreDeposit,
  hyperCoreInfoFixture,
  hyperEvmFixture,
  handleHyperEvmRpc,
  isAccountBalanceSubscription,
  parseUnits,
  routeHyperCoreInfo,
  routeHyperEvmRpc,
  rpcEntries,
  simulatedTxHash,
  systemAddress
} from "./hyperevm_fixtures.mjs";

const OWNER = "0x1234567890abcdef1234567890abcdef12345678";
const PURR_ADDRESS = SPOT_TOKENS.PURR.evmContract.address;

// Provenance: the Milestone 1 live probe (test/hyperopen/hyperevm/
// test_support/fixtures.cljs): one aggregate3 of getEthBalance(0x2222…),
// PURR balanceOf(0x2222…) and USDC-CDW balanceOf(0x2222…), POSTed to
// https://rpc.hyperliquid.xyz/evm on 2026-09-30T14:11:05Z, and its answer.
const PROBE_AGGREGATE3_CALLDATA =
  "0x82ad56cb" +
  "0000000000000000000000000000000000000000000000000000000000000020" +
  "0000000000000000000000000000000000000000000000000000000000000003" +
  "0000000000000000000000000000000000000000000000000000000000000060" +
  "0000000000000000000000000000000000000000000000000000000000000120" +
  "00000000000000000000000000000000000000000000000000000000000001e0" +
  "000000000000000000000000ca11bde05977b3631167028862be2a173976ca11" +
  "0000000000000000000000000000000000000000000000000000000000000000" +
  "0000000000000000000000000000000000000000000000000000000000000060" +
  "0000000000000000000000000000000000000000000000000000000000000024" +
  "4d2301cc00000000000000000000000022222222222222222222222222222222" +
  "2222222200000000000000000000000000000000000000000000000000000000" +
  "0000000000000000000000009b498c3c8a0b8cd8ba1d9851d40d186f1872b44e" +
  "0000000000000000000000000000000000000000000000000000000000000001" +
  "0000000000000000000000000000000000000000000000000000000000000060" +
  "0000000000000000000000000000000000000000000000000000000000000024" +
  "70a0823100000000000000000000000022222222222222222222222222222222" +
  "2222222200000000000000000000000000000000000000000000000000000000" +
  "0000000000000000000000006b9e773128f453f5c2c60935ee2de2cbc5390a24" +
  "0000000000000000000000000000000000000000000000000000000000000001" +
  "0000000000000000000000000000000000000000000000000000000000000060" +
  "0000000000000000000000000000000000000000000000000000000000000024" +
  "70a0823100000000000000000000000022222222222222222222222222222222" +
  "2222222200000000000000000000000000000000000000000000000000000000";

const PROBE_AGGREGATE3_RESULT =
  "0x" +
  "0000000000000000000000000000000000000000000000000000000000000020" +
  "0000000000000000000000000000000000000000000000000000000000000003" +
  "0000000000000000000000000000000000000000000000000000000000000060" +
  "00000000000000000000000000000000000000000000000000000000000000e0" +
  "0000000000000000000000000000000000000000000000000000000000000160" +
  "0000000000000000000000000000000000000000000000000000000000000001" +
  "0000000000000000000000000000000000000000000000000000000000000040" +
  "0000000000000000000000000000000000000000000000000000000000000020" +
  "00000000000000000000000000000000000000000310c07978c3d1e3b9162654" +
  "0000000000000000000000000000000000000000000000000000000000000001" +
  "0000000000000000000000000000000000000000000000000000000000000040" +
  "0000000000000000000000000000000000000000000000000000000000000020" +
  "00000000000000000000000000000000000000000000000a1659588597734cd2" +
  "0000000000000000000000000000000000000000000000000000000000000000" +
  "0000000000000000000000000000000000000000000000000000000000000040" +
  "0000000000000000000000000000000000000000000000000000000000000000";

function word(value) {
  return BigInt(value).toString(16).padStart(64, "0");
}

function aggregateCall(calls) {
  // Re-encode through the probe layout: the handler must decode any
  // well-formed aggregate3, so build one with the same encoder shape.
  const tails = calls.map(({ target, callData }) => {
    const body = callData.replace(/^0x/, "");
    const padded = body + "0".repeat((64 - (body.length % 64)) % 64);
    return word(BigInt(`0x${target.slice(2)}`)) + word(1) + word(96) + word(body.length / 2) + padded;
  });
  let offset = 32 * tails.length;
  const heads = tails.map((tail) => {
    const current = offset;
    offset += tail.length / 2;
    return word(current);
  });
  return `${SELECTORS.aggregate3}${word(32)}${word(tails.length)}${heads.join("")}${tails.join("")}`;
}

test("simulated hashes match the wallet simulator's", () => {
  // simulators_evm_test.cljs pins the same two strings.
  assert.equal(simulatedTxHash(1), `0x${"e".repeat(56)}00000001`);
  assert.equal(simulatedTxHash(26), `0x${"e".repeat(56)}0000001a`);
});

test("aggregate3 decoding and encoding round-trip the live probe", () => {
  assert.deepEqual(decodeAggregate3Calls(PROBE_AGGREGATE3_CALLDATA), [
    {
      target: MULTICALL3_ADDRESS,
      allowFailure: false,
      callData: `0x4d2301cc${"0".repeat(24)}${"22".repeat(20)}`
    },
    { target: PURR_ADDRESS, allowFailure: true, callData: encodeBalanceOf(HYPE_SYSTEM_ADDRESS) },
    { target: USDC_CORE_DEPOSIT_WALLET, allowFailure: true, callData: encodeBalanceOf(HYPE_SYSTEM_ADDRESS) }
  ]);
  assert.equal(
    encodeAggregate3Results([
      { success: true, returnData: `0x${word(948706777700642844709365332n)}` },
      { success: true, returnData: `0x${word(186077856409651989714n)}` },
      { success: false, returnData: "0x" }
    ]),
    PROBE_AGGREGATE3_RESULT
  );
});

test("calldata encoders match the plan's golden vectors", () => {
  assert.equal(
    encodeCoreDeposit(1000000n),
    `0x2b2dfd2c${"0".repeat(58)}0f4240${"0".repeat(56)}ffffffff`
  );
  assert.equal(
    encodeApprove(USDC_CORE_DEPOSIT_WALLET, 1000000000n),
    `0x095ea7b3${"0".repeat(24)}6b9e773128f453f5c2c60935ee2de2cbc5390a24${word(1000000000n)}`
  );
  assert.equal(systemAddress(1), "0x2000000000000000000000000000000000000001");
  assert.equal(systemAddress(150), HYPE_SYSTEM_ADDRESS);
  assert.equal(parseUnits("12.5", 18), 12500000000000000000n);
  assert.equal(parseUnits("0.123456789", 6), 123456n);
});

test("a balance batch answers native HYPE, the gas price and every aggregate3 call", () => {
  const state = createHyperEvmRpcState(
    hyperEvmFixture({ owner: OWNER, holdings: { HYPE: "12.5", PURR: "250", USDC: "1000" } })
  );
  const calls = [
    { target: PURR_ADDRESS, callData: encodeBalanceOf(OWNER) },
    { target: USDC_TOKEN_ADDRESS, callData: encodeBalanceOf(OWNER) },
    { target: PURR_ADDRESS, callData: encodeBalanceOf(systemAddress(1)) },
    { target: SPOT_TOKENS.SIX.evmContract.address, callData: encodeBalanceOf(systemAddress(6)) },
    { target: SPOT_TOKENS.PURR.evmContract.address, callData: SELECTORS.decimals },
    { target: SPOT_TOKENS.HOPE.evmContract.address, callData: SELECTORS.decimals },
    { target: SPOT_TOKENS.JOFF.evmContract.address, callData: encodeBalanceOf(OWNER) },
    { target: "0x00000000000000000000000000000000000000aa", callData: SELECTORS.decimals }
  ];
  const { status, body } = handleHyperEvmRpc(state, [
    { jsonrpc: "2.0", id: 1, method: "eth_getBalance", params: [OWNER, "latest"] },
    { jsonrpc: "2.0", id: 2, method: "eth_gasPrice", params: [] },
    { jsonrpc: "2.0", id: 3, method: "eth_call", params: [{ to: MULTICALL3_ADDRESS, data: aggregateCall(calls) }, "latest"] }
  ]);
  assert.equal(status, 200);
  assert.deepEqual(body.slice(0, 2), [
    { jsonrpc: "2.0", id: 1, result: "0xad78ebc5ac620000" },
    { jsonrpc: "2.0", id: 2, result: "0x666f5c3" }
  ]);
  assert.equal(
    body[2].result,
    encodeAggregate3Results([
      { success: true, returnData: `0x${word(250n * 10n ** 18n)}` },
      { success: true, returnData: `0x${word(1000000000n)}` },
      { success: true, returnData: `0x${word(LIVE_SYSTEM_UNITS[1])}` },
      { success: true, returnData: `0x${word(0)}` },
      { success: true, returnData: `0x${word(18)}` },
      { success: true, returnData: `0x${word(0)}` },
      { success: false, returnData: "0x" },
      { success: false, returnData: "0x" }
    ])
  );
});

test("rate limits answer the whole request with the live single error object", () => {
  const fixture = emptyHyperEvmFixture();
  fixture.rateLimitNext = 1;
  const state = createHyperEvmRpcState(fixture);
  const batch = [
    { jsonrpc: "2.0", id: 1, method: "eth_gasPrice", params: [] },
    { jsonrpc: "2.0", id: 2, method: "eth_chainId", params: [] }
  ];
  assert.deepEqual(handleHyperEvmRpc(state, batch).body, {
    jsonrpc: "2.0",
    id: null,
    error: { code: -32005, message: "rate limited" }
  });
  assert.deepEqual(handleHyperEvmRpc(state, batch).body, [
    { jsonrpc: "2.0", id: 1, result: "0x666f5c3" },
    { jsonrpc: "2.0", id: 2, result: "0x3e7" }
  ]);
  assert.equal(rpcEntries(state).length, 4);
});

test("receipts follow their queue, then succeed, and report each success", () => {
  const hash = simulatedTxHash(1);
  const seen = [];
  const fixture = emptyHyperEvmFixture();
  fixture.receipts[hash] = ["rate-limited", "pending"];
  fixture.receiptHooks.push((receiptHash, receipt) => seen.push([receiptHash, receipt.status]));
  const state = createHyperEvmRpcState(fixture);
  const request = { jsonrpc: "2.0", id: 1, method: "eth_getTransactionReceipt", params: [hash] };
  assert.equal(handleHyperEvmRpc(state, request).body.error.code, -32005);
  assert.deepEqual(handleHyperEvmRpc(state, request).body, { jsonrpc: "2.0", id: 1, result: null });
  const receipt = handleHyperEvmRpc(state, request).body.result;
  assert.equal(receipt.status, "0x1");
  assert.equal(receipt.transactionHash, hash);
  assert.equal(receipt.type, "0x2");
  assert.deepEqual(seen, [[hash, "0x1"]]);
  fixture.receipts[hash] = [{ status: "0x0" }];
  assert.equal(handleHyperEvmRpc(state, request).body.result.status, "0x0");
});

test("estimates follow the live answers per transaction kind; errors are queued", () => {
  const fixture = emptyHyperEvmFixture();
  fixture.estimateErrors.push({ code: 3, message: "execution reverted" });
  const state = createHyperEvmRpcState(fixture);
  const estimate = (tx) =>
    handleHyperEvmRpc(state, { jsonrpc: "2.0", id: 7, method: "eth_estimateGas", params: [tx] }).body;
  assert.deepEqual(estimate({ from: OWNER, to: HYPE_SYSTEM_ADDRESS, value: "0x1" }), {
    jsonrpc: "2.0",
    id: 7,
    error: { code: 3, message: "execution reverted" }
  });
  assert.equal(estimate({ from: OWNER, to: HYPE_SYSTEM_ADDRESS, value: "0x1" }).result, "0x5910");
  assert.equal(estimate({ to: USDC_TOKEN_ADDRESS, data: encodeApprove(USDC_CORE_DEPOSIT_WALLET, 1n) }).result, "0xdb88");
  // A deposit estimate reverts like native USDC's transferFrom until the
  // approve is mined, then answers the live estimate.
  const deposit = { from: OWNER, to: USDC_CORE_DEPOSIT_WALLET, data: encodeCoreDeposit(1000000000n) };
  assert.deepEqual(estimate(deposit).error, ALLOWANCE_REVERT);
  approveOnReceipt(fixture, { hash: simulatedTxHash(1), owner: OWNER, units: 999999999n });
  handleHyperEvmRpc(state, { jsonrpc: "2.0", id: 8, method: "eth_getTransactionReceipt", params: [simulatedTxHash(1)] });
  assert.deepEqual(estimate(deposit).error, ALLOWANCE_REVERT, "an allowance one unit short still reverts");
  approveOnReceipt(fixture, { hash: simulatedTxHash(2), owner: OWNER.toUpperCase().replace("0X", "0x"), units: 1000000000n });
  handleHyperEvmRpc(state, { jsonrpc: "2.0", id: 9, method: "eth_getTransactionReceipt", params: [simulatedTxHash(2)] });
  assert.equal(estimate(deposit).result, "0xf618");
  assert.equal(estimate({ to: PURR_ADDRESS, data: `${SELECTORS.transfer}${"0".repeat(128)}` }).result, "0x7404");
});

test("allowance reads answer the fixture; unknown methods are recorded", () => {
  const fixture = emptyHyperEvmFixture();
  fixture.allowances[USDC_TOKEN_ADDRESS] = { [OWNER]: { [USDC_CORE_DEPOSIT_WALLET]: "5000000" } };
  const state = createHyperEvmRpcState(fixture);
  const data = `${SELECTORS.allowance}${OWNER.slice(2).padStart(64, "0")}${USDC_CORE_DEPOSIT_WALLET.slice(2).padStart(64, "0")}`;
  assert.equal(
    handleHyperEvmRpc(state, {
      jsonrpc: "2.0",
      id: 1,
      method: "eth_call",
      params: [{ to: USDC_TOKEN_ADDRESS, data }, "latest"]
    }).body.result,
    `0x${word(5000000n)}`
  );
  assert.equal(
    handleHyperEvmRpc(state, { jsonrpc: "2.0", id: 2, method: "eth_sendRawTransaction", params: ["0x"] }).body.error.code,
    -32601
  );
  assert.deepEqual(state.unknown, [{ method: "eth_sendRawTransaction", params: ["0x"] }]);
});

function fakePage() {
  const routes = [];
  return {
    routes,
    async route(matcher, handler) {
      routes.push({ matcher, handler });
    },
    async request(url, body) {
      const request = {
        url: () => url,
        method: () => "POST",
        postData: () => JSON.stringify(body),
        postDataJSON: () => body
      };
      const match = routes.find(({ matcher }) =>
        typeof matcher === "function" ? matcher(new URL(url)) : matcher === url
      );
      if (!match) return { request, fulfilled: null, fellBack: false };
      let fulfilled = null;
      let fellBack = false;
      await match.handler({
        request: () => request,
        fulfill: async (response) => {
          fulfilled = response;
        },
        fallback: async () => {
          fellBack = true;
        }
      });
      return { request, fulfilled, fellBack };
    }
  };
}

test("routeHyperEvmRpc answers the RPC host only and swaps fixtures in place", async () => {
  const page = fakePage();
  const controller = await routeHyperEvmRpc(page, emptyHyperEvmFixture());
  assert.equal(page.routes.length, 1);
  assert.equal(page.routes[0].matcher(new URL("https://api.hyperliquid.xyz/info")), false);

  const first = await page.request("https://rpc.hyperliquid.xyz/evm", {
    jsonrpc: "2.0",
    id: 1,
    method: "eth_getBalance",
    params: [OWNER, "latest"]
  });
  assert.equal(JSON.parse(first.fulfilled.body).result, "0x0");

  const again = await routeHyperEvmRpc(page, hyperEvmFixture({ owner: OWNER, holdings: { HYPE: "1" } }));
  assert.equal(again, controller);
  assert.equal(page.routes.length, 1, "a second call swaps the fixture instead of stacking a route");
  const second = await page.request("https://rpc.hyperliquid.xyz/evm", {
    jsonrpc: "2.0",
    id: 2,
    method: "eth_getBalance",
    params: [OWNER, "latest"]
  });
  assert.equal(JSON.parse(second.fulfilled.body).result, "0xde0b6b3a7640000");
  assert.deepEqual(controller.entries().map((entry) => entry.id), [1, 2]);
  assert.deepEqual(controller.unknown(), []);
});

test("a batch over 20 entries gets the live -32010 error and nothing in it runs", () => {
  const hash = simulatedTxHash(1);
  const fixture = emptyHyperEvmFixture();
  fixture.receipts[hash] = ["pending"];
  const state = createHyperEvmRpcState(fixture);
  const batch = Array.from({ length: 21 }, (_, index) => ({
    jsonrpc: "2.0",
    id: index + 1,
    method: "eth_getTransactionReceipt",
    params: [hash]
  }));
  assert.deepEqual(handleHyperEvmRpc(state, batch).body, BATCH_TOO_LARGE_BODY);
  assert.deepEqual(state.oversizedBatches, [21]);
  assert.deepEqual(fixture.receipts[hash], ["pending"], "no queue was consumed");
  assert.equal(handleHyperEvmRpc(state, batch.slice(0, 20)).body.length, 20, "20 entries are answered");
});

test("a rate-limited receipt answers its whole batch before any other entry runs", () => {
  const limited = simulatedTxHash(1);
  const other = simulatedTxHash(2);
  const fixture = emptyHyperEvmFixture();
  const hooks = [];
  fixture.receipts[limited] = ["rate-limited"];
  fixture.receipts[other] = ["pending", "pending"];
  fixture.receiptHooks.push((hash) => hooks.push(hash));
  const state = createHyperEvmRpcState(fixture);
  const read = (id, hash) => ({ jsonrpc: "2.0", id, method: "eth_getTransactionReceipt", params: [hash] });
  const batch = [read(1, other), read(2, limited), read(3, simulatedTxHash(3))];
  assert.equal(handleHyperEvmRpc(state, batch).body.error.code, -32005);
  assert.deepEqual(fixture.receipts[other], ["pending", "pending"], "the other receipt's queue is untouched");
  assert.deepEqual(fixture.receipts[limited], [], "only the rate-limit marker is consumed");
  assert.deepEqual(hooks, [], "no receipt hook fired for the unanswered batch");
  const answers = handleHyperEvmRpc(state, batch).body;
  assert.deepEqual(answers.map((answer) => answer.result?.status ?? null), [null, "0x1", "0x1"]);
  assert.deepEqual(hooks, [limited, simulatedTxHash(3)]);
});

test("strict HyperCore info records each user read it lets through", async () => {
  const page = fakePage();
  const fixture = hyperCoreInfoFixture();
  await routeHyperCoreInfo(page, fixture, { strict: true });
  const roleAnswer = await page.request("https://api.hyperliquid.xyz/info", { type: "userRole", user: OWNER });
  assert.deepEqual(JSON.parse(roleAnswer.fulfilled.body), { role: "missing" });
  const subAccounts = await page.request("https://api.hyperliquid.xyz/info", { type: "subAccounts", user: OWNER });
  assert.equal(subAccounts.fulfilled.body, "null", "live answers null for an address with no subaccounts");
  const fills = await page.request("https://api.hyperliquid.xyz/info", { type: "userTwapSliceFills", user: OWNER });
  assert.equal(fills.fellBack, true);
  const mids = await page.request("https://api.hyperliquid.xyz/info", { type: "allMids" });
  assert.equal(mids.fellBack, true);
  assert.deepEqual(fixture.unhandledUserRequests, [{ type: "userTwapSliceFills", user: OWNER }]);
});

test("account-less user reads answer the live empty-account shapes", () => {
  const fixture = hyperCoreInfoFixture();
  assert.deepEqual(answerHyperCoreInfo(fixture, { type: "userFills", user: OWNER }), []);
  assert.deepEqual(answerHyperCoreInfo(fixture, { type: "delegatorSummary", user: OWNER }), {
    delegated: "0.0",
    undelegated: "0.0",
    totalPendingWithdrawal: "0.0",
    nPendingWithdrawals: 0
  });
  const portfolio = answerHyperCoreInfo(fixture, { type: "portfolio", user: OWNER });
  assert.deepEqual(portfolio.map(([period]) => period), [
    "day", "week", "month", "allTime", "perpDay", "perpWeek", "perpMonth", "perpAllTime"
  ]);
  assert.equal(answerHyperCoreInfo(fixture, { type: "userFees", user: OWNER }).userCrossRate, "0.00045");
  assert.equal(answerHyperCoreInfo(fixture, { type: "portfolio" }), NOT_HANDLED, "only user reads");
  assert.equal(answerHyperCoreInfo(fixture, { type: "userTwapSliceFills", user: OWNER }), NOT_HANDLED);
});

test("HyperCore info answers the account reads and lets everything else through", () => {
  const fixture = hyperCoreInfoFixture();
  fixture.spotStates[OWNER] = { balances: [{ coin: "PURR", token: 1, total: "9800", hold: "0.0", entryNtl: "0.0" }] };
  fixture.userRoles[OWNER] = { role: "user" };
  assert.equal(answerHyperCoreInfo(fixture, { type: "spotMeta" }), MAINNET_SPOT_META);
  assert.equal(answerHyperCoreInfo(fixture, { type: "spotClearinghouseState", user: OWNER.toUpperCase().replace("0X", "0x") }).balances[0].total, "9800");
  assert.deepEqual(answerHyperCoreInfo(fixture, { type: "spotClearinghouseState", user: "0xabc" }), { balances: [] });
  assert.equal(
    answerHyperCoreInfo(fixture, { type: "spotClearinghouseState", user: HYPE_SYSTEM_ADDRESS }).balances[0].total,
    "51277474.282994248"
  );
  assert.deepEqual(answerHyperCoreInfo(fixture, { type: "userRole", user: OWNER }), { role: "user" });
  assert.deepEqual(answerHyperCoreInfo(fixture, { type: "userRole", user: "0xabc" }), { role: "missing" });
  assert.equal(answerHyperCoreInfo(fixture, { type: "userAbstraction", user: OWNER }), "default");
  assert.equal(answerHyperCoreInfo(fixture, { type: "clearinghouseState", user: OWNER }).withdrawable, "0.0");
  assert.equal(answerHyperCoreInfo(fixture, { type: "clearinghouseState", user: OWNER, dex: "xyz" }), NOT_HANDLED);
  assert.equal(answerHyperCoreInfo(fixture, { type: "subAccounts", user: OWNER }), null);
  assert.equal(answerHyperCoreInfo(fixture, { type: "allMids" }), NOT_HANDLED);
});

test("the market catalog answers keep every pair's context at its index", () => {
  const fixture = hyperCoreInfoFixture();
  const [spotMeta, ctxs] = answerHyperCoreInfo(fixture, { type: "spotMetaAndAssetCtxs" });
  assert.equal(spotMeta, MAINNET_SPOT_META);
  assert.equal(ctxs, SPOT_ASSET_CTXS);
  for (const pair of SPOT_UNIVERSE) {
    assert.equal(ctxs[pair.index].coin, pair.name, `pair ${pair.name} context at ${pair.index}`);
  }
  const [perpMeta, perpCtxs] = answerHyperCoreInfo(fixture, { type: "metaAndAssetCtxs" });
  assert.equal(perpMeta, PERP_META_AND_ASSET_CTXS[0]);
  assert.equal(perpMeta.universe.length, perpCtxs.length);
  assert.equal(perpMeta.universe[0].name, "BTC");
  assert.equal(answerHyperCoreInfo(fixture, { type: "metaAndAssetCtxs", dex: "xyz" }), NOT_HANDLED);
  assert.deepEqual(answerHyperCoreInfo(fixture, { type: "perpDexs" }), [null]);
  assert.deepEqual(answerHyperCoreInfo(fixture, { type: "outcomeMeta" }).outcomes, []);
});

test("only a user's balance-stream subscriptions are dropped from the websocket", () => {
  const subscribe = (subscription, method = "subscribe") => JSON.stringify({ method, subscription });
  assert.equal(isAccountBalanceSubscription(subscribe({ type: "clearinghouseState", user: OWNER, dex: "" })), true);
  assert.equal(isAccountBalanceSubscription(subscribe({ type: "webData2", user: OWNER }, "unsubscribe")), true);
  assert.equal(isAccountBalanceSubscription(subscribe({ type: "spotState", user: OWNER })), true);
  assert.equal(isAccountBalanceSubscription(subscribe({ type: "openOrders", user: OWNER })), false);
  assert.equal(isAccountBalanceSubscription(subscribe({ type: "allMids" })), false);
  assert.equal(isAccountBalanceSubscription(JSON.stringify({ method: "ping" })), false);
  assert.equal(isAccountBalanceSubscription("not json"), false);
});
