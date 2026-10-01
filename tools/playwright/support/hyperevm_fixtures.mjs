// Deterministic HyperEVM (and the HyperCore reads a HyperEVM Transfer needs)
// for Playwright.
//
// - `routeHyperEvmRpc(page, fixture)` answers every request to the public
//   HyperEVM RPC (https://rpc.hyperliquid.xyz/evm) from a fixture: native
//   balances, the gas price, Multicall3 aggregate3 reads (owner balanceOf,
//   system-address balanceOf, decimals()), allowance, eth_estimateGas and
//   receipts, JSON-RPC batches, and injected rate limits. `visitRoute` installs
//   it with an empty-balance fixture on every page, and the shared guard
//   (`hyperevm_rpc_guard.mjs`, run for every spec through
//   `guarded_test.mjs`) answers every other page and context the same way
//   and fails the test on any request the mock did not answer.
// - `routeHyperCoreInfo(page, fixture, { strict })` answers the account reads
//   a HyperEVM flow depends on (spotClearinghouseState, clearinghouseState,
//   userAbstraction, userRole, subAccounts) and the market catalog every
//   HyperEVM surface waits on (spotMeta, spotMetaAndAssetCtxs,
//   metaAndAssetCtxs, perpDexs, outcomeMeta), and lets every other info
//   request through; `strict` records each user-carrying read it let through.
//   `routeHyperCoreAccountStreams(page)` keeps the live websocket but never
//   subscribes a user's balance streams.
//
// Every value is production-shaped. The spotMeta token rows are verbatim
// mainnet rows (the same ones `test/hyperopen/hyperevm/test_support/
// fixtures.cljs` and `bridge_fixtures.cljs` pin), the universe rows are the
// live pairs of those tokens, and the gas price, gas estimates, bridge
// system balances and receipt shape come from live responses (provenance on
// each constant). Nothing here imports Playwright, so node tests can drive the
// JSON-RPC handler directly.

import fs from "node:fs";

export const HYPEREVM_RPC_URL = "https://rpc.hyperliquid.xyz/evm";
export const HYPEREVM_RPC_HOST = "rpc.hyperliquid.xyz";
export const HYPERCORE_INFO_URL = "https://api.hyperliquid.xyz/info";
export const HYPERCORE_WS_URL = "wss://api.hyperliquid.xyz/ws";

export const MULTICALL3_ADDRESS = "0xca11bde05977b3631167028862be2a173976ca11";
export const HYPE_SYSTEM_ADDRESS = "0x2222222222222222222222222222222222222222";
export const USDC_TOKEN_ADDRESS = "0xb88339cb7199b77e23db6e890353e22632ba630f";
export const USDC_CORE_DEPOSIT_WALLET = "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24";
export const SPOT_DESTINATION_DEX = 4294967295n;

export const SELECTORS = Object.freeze({
  balanceOf: "0x70a08231",
  transfer: "0xa9059cbb",
  approve: "0x095ea7b3",
  allowance: "0xdd62ed3e",
  coreDeposit: "0x2b2dfd2c",
  getEthBalance: "0x4d2301cc",
  aggregate3: "0x82ad56cb",
  decimals: "0x313ce567"
});

// Provenance: `eth_gasPrice` in the Milestone 1 live probe batch to
// https://rpc.hyperliquid.xyz/evm, 2026-09-30T14:11:05Z (0x666f5c3).
export const PROBE_GAS_PRICE_WEI = "107410883";

// Provenance: live `eth_estimateGas` answers on 2026-09-30 recorded in
// `src/hyperopen/hyperevm/domain/fees.cljs` (`fallback-gas-limits`): ~22.8k
// for native HYPE to 0x2222…, ~29.7k for a PURR transfer to its system
// address, ~56.2k for a USDC approve and 58-68k for a USDC spot deposit.
// The public RPC answers a JSON-RPC batch of more than 20 entries with this
// single error object (HTTP 200). Provenance: a 21-entry `eth_chainId` batch
// POSTed to https://rpc.hyperliquid.xyz/evm on 2026-10-01T00:28:43Z.
export const MAX_BATCH_SIZE = 20;
export const BATCH_TOO_LARGE_BODY = Object.freeze({
  jsonrpc: "2.0",
  id: null,
  error: { code: -32010, message: "The batch request was too large", data: "Exceeded max limit of 20" }
});

// `eth_estimateGas` of a USDC `deposit` without the CoreDepositWallet
// allowance reverts in native USDC's `transferFrom`. Provenance:
// `deposit(1000000000, 4294967295)` on 0x6b9e…0a24 from 0x1234…5678 (no
// allowance), https://rpc.hyperliquid.xyz/evm, 2026-10-01T00:28:43Z.
export const ALLOWANCE_REVERT = Object.freeze({
  code: 3,
  message: "execution reverted: ERC20: transfer amount exceeds allowance",
  data:
    "0x08c379a0" +
    "0000000000000000000000000000000000000000000000000000000000000020" +
    "0000000000000000000000000000000000000000000000000000000000000028" +
    "45524332303a207472616e7366657220616d6f756e74206578636565647320616c6c6f77616e6365" +
    "000000000000000000000000000000000000000000000000"
});

export const LIVE_GAS_ESTIMATES = Object.freeze({
  native: 22800n,
  erc20: 29700n,
  approve: 56200n,
  deposit: 63000n
});

// --- spotMeta ------------------------------------------------------------------
//
// Provenance: `curl -s -X POST https://api.hyperliquid.xyz/info
// -d '{"type":"spotMeta"}'`. Token rows: 2026-09-30T14:06Z (fixtures.cljs)
// and 15:08Z (bridge_fixtures.cljs), re-checked verbatim at 23:04Z. Universe
// rows (each token's USDC pair): 2026-09-30T23:04Z; JOFF and FUNT have none.

export const SPOT_TOKENS = Object.freeze({
  USDC: {
    name: "USDC", szDecimals: 8, weiDecimals: 8, index: 0,
    tokenId: "0x6d1e7cde53ba9467b783cb7c530ce054", isCanonical: true,
    evmContract: { address: "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24", evm_extra_wei_decimals: -2 },
    fullName: null, deployerTradingFeeShare: "0.0"
  },
  PURR: {
    name: "PURR", szDecimals: 0, weiDecimals: 5, index: 1,
    tokenId: "0xc1fb593aeffbeb02f85e0308e9956a90", isCanonical: true,
    evmContract: { address: "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e", evm_extra_wei_decimals: 13 },
    fullName: null, deployerTradingFeeShare: "0.0"
  },
  SIX: {
    name: "SIX", szDecimals: 2, weiDecimals: 8, index: 6,
    tokenId: "0x50a9391b4a40caffbe8b16303b95a0c1", isCanonical: false,
    evmContract: { address: "0x41de34fc45a770ebcd50200be93f080b4b05151f", evm_extra_wei_decimals: 10 },
    fullName: null, deployerTradingFeeShare: "0.0"
  },
  HOPE: {
    name: "HOPE", szDecimals: 0, weiDecimals: 5, index: 122,
    tokenId: "0xe6ac9d5a7cf91cdd9fdf34fcbbdd58e9", isCanonical: false,
    evmContract: { address: "0x869ac826b78bc1d9501014994196e41025b5224b", evm_extra_wei_decimals: 0 },
    fullName: "Purr $HOPE for the $HYPE! No planned utility.", deployerTradingFeeShare: "0.0"
  },
  HYPE: {
    name: "HYPE", szDecimals: 2, weiDecimals: 8, index: 150,
    tokenId: "0x0d01dc56dcaaca66ad901c959b4011ec", isCanonical: false,
    evmContract: null,
    fullName: "Hyperliquid", deployerTradingFeeShare: "0.0"
  },
  UBTC: {
    name: "UBTC", szDecimals: 5, weiDecimals: 10, index: 197,
    tokenId: "0x8f254b963e8468305d409b33aa137c67", isCanonical: false,
    evmContract: { address: "0x9fdbda0a5e284c32744d2f17ee5c74b284993463", evm_extra_wei_decimals: -2 },
    fullName: "Unit Bitcoin", deployerTradingFeeShare: "1.0"
  },
  JOFF: {
    name: "JOFF", szDecimals: 2, weiDecimals: 8, index: 296,
    tokenId: "0x8ecbaadbaf3f59a7e9f97af2688d479d", isCanonical: false,
    evmContract: { address: "0x5fe5c8627ac1aedc0422c2de6d789d924d81b5c2", evm_extra_wei_decimals: 0 },
    fullName: "bildin a purrty gud el 1", deployerTradingFeeShare: "1.0"
  },
  FUNT: {
    name: "FUNT", szDecimals: 1, weiDecimals: 6, index: 478,
    tokenId: "0x1aaa916f86510ab37b20fbffeb9c05bb", isCanonical: false,
    evmContract: { address: "0xd6f92d754818307d0e2853eada247178f1ae605b", evm_extra_wei_decimals: 2 },
    fullName: "Funtoken", deployerTradingFeeShare: "1.0"
  }
});

export const SPOT_UNIVERSE = Object.freeze([
  { tokens: [1, 0], name: "PURR/USDC", index: 0, isCanonical: true },
  { tokens: [6, 0], name: "@5", index: 5, isCanonical: false },
  { tokens: [122, 0], name: "@81", index: 81, isCanonical: false },
  { tokens: [150, 0], name: "@107", index: 107, isCanonical: false },
  { tokens: [197, 0], name: "@142", index: 142, isCanonical: false }
]);

// A mainnet spotMeta subset in live array order (FUNT sits after JOFF, as on
// mainnet, where array position and index diverge).
export const MAINNET_SPOT_META = Object.freeze({
  tokens: [
    SPOT_TOKENS.USDC, SPOT_TOKENS.PURR, SPOT_TOKENS.SIX, SPOT_TOKENS.HOPE,
    SPOT_TOKENS.HYPE, SPOT_TOKENS.UBTC, SPOT_TOKENS.JOFF, SPOT_TOKENS.FUNT
  ],
  universe: SPOT_UNIVERSE
});

// --- market catalog -------------------------------------------------------------
//
// The asset selector's full load (spotMeta, spotMetaAndAssetCtxs, perpDexs,
// metaAndAssetCtxs, outcomeMeta) must finish before `[:spot :meta]` is
// stored, and every HyperEVM surface waits on it. Live, a burst of test runs
// gets 429s from the public info API and the load never finishes, so
// `routeHyperCoreInfo` answers these too.
//
// Provenance: `curl -s -X POST https://api.hyperliquid.xyz/info` on
// 2026-09-30T23:34Z. `metaAndAssetCtxs`: the first six perps (array order is
// the asset id, so a prefix keeps every id right) with their margin tables
// and contexts. `spotMetaAndAssetCtxs`: the contexts of SPOT_UNIVERSE's pairs,
// at their pair indexes (the app reads `(nth ctxs index)`). `perpDexs`: the
// default dex only (live leads with `null`). `outcomeMeta`: the live keys
// with no outcome markets.

export const PERP_META_AND_ASSET_CTXS = Object.freeze([
  {
    universe: [
      { szDecimals: 5, name: "BTC", maxLeverage: 40, marginTableId: 56 },
      { szDecimals: 4, name: "ETH", maxLeverage: 25, marginTableId: 55 },
      { szDecimals: 2, name: "ATOM", maxLeverage: 5, marginTableId: 5 },
      { szDecimals: 1, name: "MATIC", maxLeverage: 20, marginTableId: 20, isDelisted: true },
      { szDecimals: 1, name: "DYDX", maxLeverage: 5, marginTableId: 5 },
      { szDecimals: 2, name: "SOL", maxLeverage: 20, marginTableId: 54 }
    ],
    marginTables: [
      [54, { description: "tiered 20x (2)", marginTiers: [{ lowerBound: "0.0", maxLeverage: 20 }, { lowerBound: "70000000.0", maxLeverage: 10 }] }],
      [55, { description: "tiered 25x", marginTiers: [{ lowerBound: "0.0", maxLeverage: 25 }, { lowerBound: "100000000.0", maxLeverage: 15 }] }],
      [56, { description: "tiered 40x", marginTiers: [{ lowerBound: "0.0", maxLeverage: 40 }, { lowerBound: "150000000.0", maxLeverage: 20 }] }]
    ],
    collateralToken: 0
  },
  [
    { funding: "0.0000125", openInterest: "34996.36874", prevDayPx: "83717.0", dayNtlVlm: "2898295358.5870132446", premium: "-0.000090952", oraclePx: "83560.6", markPx: "83552.0", midPx: "83552.5", impactPxs: ["83552.0", "83553.0"], dayBaseVlm: "34488.3269699999" },
    { funding: "0.0000125", openInterest: "1189861.2199999988", prevDayPx: "2681.1", dayNtlVlm: "1082533999.7752304077", premium: "0.0003201739", oraclePx: "2686.04", markPx: "2686.75", midPx: "2686.95", impactPxs: ["2686.9", "2687.0"], dayBaseVlm: "402071.8706999997" },
    { funding: "0.0000125", openInterest: "1576525.6200000001", prevDayPx: "1.7244", dayNtlVlm: "611416.1915879997", premium: "-0.0004599023", oraclePx: "1.7395", markPx: "1.7376", midPx: "1.7374", impactPxs: ["1.7368", "1.7387"], dayBaseVlm: "354851.55" },
    { funding: "0.0", openInterest: "0.0", prevDayPx: "0.37621", dayNtlVlm: "0.0", premium: null, oraclePx: "0.3754", markPx: "0.37621", midPx: null, impactPxs: null, dayBaseVlm: "0.0" },
    { funding: "0.0000125", openInterest: "18755081.2000000067", prevDayPx: "0.13982", dayNtlVlm: "386764.0599980001", premium: "0.0006961849", oraclePx: "0.14364", markPx: "0.1439", midPx: "0.14391", impactPxs: ["0.14374", "0.14405"], dayBaseVlm: "2671922.4999999995" },
    { funding: "0.0000046158", openInterest: "5722954.7200000007", prevDayPx: "119.32", dayNtlVlm: "316193702.1514998078", premium: "-0.0004336734", oraclePx: "118.0612", markPx: "118.0", midPx: "118.005", impactPxs: ["117.999", "118.01"], dayBaseVlm: "2646426.7100000014" }
  ]
]);

const SPOT_ASSET_CTX_BY_PAIR = Object.freeze({
  0: { prevDayPx: "0.13985", dayNtlVlm: "2660582.1677299999", markPx: "0.15434", midPx: "0.154255", circulatingSupply: "594817348.570389986", coin: "PURR/USDC", totalSupply: "594817359.8769099712", dayBaseVlm: "18252062.0" },
  5: { prevDayPx: "0.67873", dayNtlVlm: "0.0", markPx: "0.67873", midPx: "0.679745", circulatingSupply: "6658400.6332807504", coin: "@5", totalSupply: "6658400.6332807504", dayBaseVlm: "0.0" },
  81: { prevDayPx: "0.00000293", dayNtlVlm: "0.0", markPx: "0.00000453", midPx: "0.00006326", circulatingSupply: "749749377.2795100212", coin: "@81", totalSupply: "999749377.2795100212", dayBaseVlm: "0.0" },
  107: { prevDayPx: "86.143", dayNtlVlm: "103243605.6406403482", markPx: "90.491", midPx: "90.4915", circulatingSupply: "298665979.3863772154", coin: "@107", totalSupply: "998895543.7770146132", dayBaseVlm: "1179696.1199999936" },
  142: { prevDayPx: "83717.0", dayNtlVlm: "29361357.9908299632", markPx: "83515.0", midPx: "83515.5", circulatingSupply: "20999999.9969786294", coin: "@142", totalSupply: "20999999.9969786294", dayBaseVlm: "350.10881" }
});

export const SPOT_ASSET_CTXS = Object.freeze(
  Array.from({ length: 143 }, (_, index) => SPOT_ASSET_CTX_BY_PAIR[index] ?? null)
);

export const OUTCOME_META = Object.freeze({ outcomes: [], questions: [], deployers: [], feeScale: "1.0" });

// --- bridge state ---------------------------------------------------------------
//
// Provenance: the Milestone 2 live probe (2026-09-30T15:15Z), pinned in
// `bridge_fixtures.cljs`: `balanceOf(systemAddress)` and `decimals()` of the
// linked ERC-20s. HOPE's decimals() returns 0 where spotMeta implies 5, and
// JOFF's balanceOf and decimals() both revert.
export const LIVE_SYSTEM_UNITS = Object.freeze({
  1: "508596915622676377079152451", // PURR
  6: "0", // SIX
  122: "167579739", // HOPE
  197: "2099941788654544", // UBTC
  478: "0" // FUNT
});

export const LIVE_DECIMALS = Object.freeze({
  1: 18, // PURR: 5 + 13
  6: 18, // SIX: 8 + 10
  122: 0, // HOPE: spotMeta implies 5
  197: 8, // UBTC: 10 - 2
  478: 8 // FUNT: 6 + 2
});

// Provenance: `spotClearinghouseState` of the PURR and HYPE system addresses,
// 2026-09-30T15:08Z (bridge_fixtures.cljs), other balance rows omitted.
export const LIVE_SYSTEM_CORE_STATES = Object.freeze({
  "0x2000000000000000000000000000000000000001": {
    balances: [
      { coin: "PURR", token: 1, total: "91403084.7764399946", hold: "0.0", entryNtl: "10003597.4974620603" },
      { coin: "HYPE", token: 150, total: "1.45", hold: "0.0", entryNtl: "22.20677" }
    ]
  },
  [HYPE_SYSTEM_ADDRESS]: {
    balances: [
      { coin: "HYPE", token: 150, total: "51277474.282994248", hold: "0.0", entryNtl: "15765821169.0523014069" }
    ]
  }
});

// --- units and ABI --------------------------------------------------------------

export function tokenByName(name) {
  return SPOT_TOKENS[name] || null;
}

export function evmDecimals(token) {
  if (token.name === "HYPE") return 18;
  if (token.name === "USDC") return 6;
  return token.weiDecimals + token.evmContract.evm_extra_wei_decimals;
}

export function erc20Address(token) {
  if (token.name === "USDC") return USDC_TOKEN_ADDRESS;
  return token.evmContract ? token.evmContract.address.toLowerCase() : null;
}

export function systemAddress(index) {
  if (index === SPOT_TOKENS.HYPE.index) return HYPE_SYSTEM_ADDRESS;
  return `0x20${index.toString(16).padStart(38, "0")}`;
}

// Base units of `amountText` at `decimals`, flooring extra digits.
export function parseUnits(amountText, decimals) {
  const [whole, fraction = ""] = String(amountText).split(".");
  const padded = (fraction + "0".repeat(decimals)).slice(0, decimals);
  return BigInt(whole || "0") * 10n ** BigInt(decimals) + BigInt(padded || "0");
}

export function quantityHex(value) {
  return `0x${BigInt(value).toString(16)}`;
}

export function uintWord(value) {
  return BigInt(value).toString(16).padStart(64, "0");
}

export function addressWord(address) {
  return address.toLowerCase().replace(/^0x/, "").padStart(64, "0");
}

export function encodeBalanceOf(holder) {
  return `${SELECTORS.balanceOf}${addressWord(holder)}`;
}

export function encodeApprove(spender, units) {
  return `${SELECTORS.approve}${addressWord(spender)}${uintWord(units)}`;
}

export function encodeTransfer(recipient, units) {
  return `${SELECTORS.transfer}${addressWord(recipient)}${uintWord(units)}`;
}

export function encodeCoreDeposit(units, destinationDex = SPOT_DESTINATION_DEX) {
  return `${SELECTORS.coreDeposit}${uintWord(units)}${uintWord(destinationDex)}`;
}

function wordAt(hex, index) {
  return hex.slice(index * 64, index * 64 + 64);
}

function readUint(hex, byteOffset) {
  return BigInt(`0x${hex.slice(byteOffset * 2, byteOffset * 2 + 64) || "0"}`);
}

// Decode `aggregate3((address,bool,bytes)[])` calldata into
// `[{ target, allowFailure, callData }]`.
export function decodeAggregate3Calls(calldata) {
  const data = String(calldata).toLowerCase();
  if (!data.startsWith(SELECTORS.aggregate3)) {
    throw new Error(`Not aggregate3 calldata: ${data.slice(0, 10)}`);
  }
  const body = data.slice(10);
  const arrayOffset = Number(readUint(body, 0));
  const length = Number(readUint(body, arrayOffset));
  const headsStart = arrayOffset + 32;
  const calls = [];
  for (let i = 0; i < length; i += 1) {
    const tupleStart = headsStart + Number(readUint(body, headsStart + i * 32));
    const target = `0x${body.slice(tupleStart * 2 + 24, tupleStart * 2 + 64)}`;
    const allowFailure = readUint(body, tupleStart + 32) === 1n;
    const bytesStart = tupleStart + Number(readUint(body, tupleStart + 64));
    const bytesLength = Number(readUint(body, bytesStart));
    const callData = `0x${body.slice((bytesStart + 32) * 2, (bytesStart + 32 + bytesLength) * 2)}`;
    calls.push({ target, allowFailure, callData });
  }
  return calls;
}

function rightPad(hex) {
  const remainder = hex.length % 64;
  return remainder === 0 ? hex : hex + "0".repeat(64 - remainder);
}

// ABI-encode `(bool success, bytes returnData)[]`, aggregate3's return type.
export function encodeAggregate3Results(results) {
  const tails = results.map(({ success, returnData }) => {
    const body = String(returnData || "0x").replace(/^0x/, "");
    return uintWord(success ? 1 : 0) + uintWord(64) + uintWord(body.length / 2) + rightPad(body);
  });
  let offset = 32 * tails.length;
  const offsets = tails.map((tail) => {
    const current = offset;
    offset += tail.length / 2;
    return uintWord(current);
  });
  return `0x${uintWord(32)}${uintWord(tails.length)}${offsets.join("")}${tails.join("")}`;
}

// --- transactions ---------------------------------------------------------------

// The wallet simulator's `n`th transaction hash (1-based). It must equal
// `hyperopen.telemetry.console-preload.wallet-evm/simulated-tx-hash`.
export function simulatedTxHash(n) {
  return `0x${"e".repeat(56)}${n.toString(16).padStart(8, "0")}`;
}

// A successful receipt shaped like a live HyperEVM one (eth_getTransactionReceipt
// of 0x65573e6c…ae5d2 on 2026-09-30T23:05Z: type, status, cumulativeGasUsed,
// logs, logsBloom, transactionHash, transactionIndex, blockHash, blockNumber,
// gasUsed, effectiveGasPrice, from, to, contractAddress).
export function receiptFor(hash, { status = "0x1", from = null, to = null, gasUsed = "0x5910" } = {}) {
  return {
    type: "0x2",
    status,
    cumulativeGasUsed: gasUsed,
    logs: [],
    logsBloom: `0x${"0".repeat(512)}`,
    transactionHash: hash,
    transactionIndex: "0x0",
    blockHash: `0x${"b".repeat(56)}${hash.slice(-8)}`,
    blockNumber: "0x2d22e52",
    gasUsed,
    effectiveGasPrice: quantityHex(PROBE_GAS_PRICE_WEI),
    from,
    to,
    contractAddress: null
  };
}

// --- fixture --------------------------------------------------------------------

// Every address holds nothing; bridge health and gas are the live values.
export function emptyHyperEvmFixture() {
  return {
    gasPriceWei: PROBE_GAS_PRICE_WEI,
    native: {},
    erc20: {},
    systemUnits: { ...LIVE_SYSTEM_UNITS },
    decimals: { ...LIVE_DECIMALS },
    revertingTokens: [SPOT_TOKENS.JOFF.evmContract.address],
    allowances: {},
    estimates: { ...LIVE_GAS_ESTIMATES },
    estimateErrors: [],
    receipts: {},
    rateLimitNext: 0,
    // `(hash, receipt)` callbacks run when a successful receipt is answered,
    // e.g. to credit HyperCore or grant an approve's allowance, as the chain
    // does once the transaction is mined.
    receiptHooks: []
  };
}

// Grant `owner`'s allowance for `spender` on `token` once `hash`'s receipt is
// answered (the approve is mined), as `approve(spender, units)` does.
export function approveOnReceipt(fixture, { hash, token = USDC_TOKEN_ADDRESS, owner, spender = USDC_CORE_DEPOSIT_WALLET, units }) {
  fixture.receiptHooks.push((receiptHash, receipt) => {
    if (receiptHash !== hash || receipt.status !== "0x1") return;
    const holder = owner.toLowerCase();
    const tokenAllowances = fixture.allowances[token] || {};
    fixture.allowances[token] = {
      ...tokenAllowances,
      [holder]: { ...(tokenAllowances[holder] || {}), [spender]: BigInt(units).toString() }
    };
  });
  return fixture;
}

function allowanceUnits(fixture, token, owner, spender) {
  return BigInt(fixture.allowances[token]?.[String(owner).toLowerCase()]?.[spender] ?? "0");
}

// A fixture where `owner` holds `holdings` on HyperEVM, e.g.
// `{ HYPE: "12.5", PURR: "0", USDC: "1000" }` (decimal token amounts).
export function hyperEvmFixture({ owner, holdings = {}, overrides = {} } = {}) {
  const fixture = { ...emptyHyperEvmFixture(), ...overrides };
  if (owner) {
    setHolding(fixture, owner, holdings);
  }
  return fixture;
}

export function setHolding(fixture, owner, holdings) {
  const holder = owner.toLowerCase();
  for (const [name, amount] of Object.entries(holdings)) {
    const token = tokenByName(name);
    if (!token) throw new Error(`Unknown fixture token ${name}`);
    const units = parseUnits(amount, evmDecimals(token)).toString();
    if (name === "HYPE") {
      fixture.native = { ...fixture.native, [holder]: units };
    } else {
      const address = erc20Address(token);
      fixture.erc20 = {
        ...fixture.erc20,
        [address]: { ...(fixture.erc20[address] || {}), [holder]: units }
      };
    }
  }
  return fixture;
}

// --- JSON-RPC handler -----------------------------------------------------------

const RATE_LIMITED_BODY = Object.freeze({
  jsonrpc: "2.0",
  id: null,
  error: { code: -32005, message: "rate limited" }
});

function selectorOf(data) {
  return String(data || "0x").toLowerCase().slice(0, 10);
}

function argAddress(data, index) {
  return `0x${wordAt(String(data).toLowerCase().slice(10), index).slice(24)}`;
}

function tokenIndexByAddress(address) {
  const target = address.toLowerCase();
  for (const token of Object.values(SPOT_TOKENS)) {
    if (erc20Address(token) === target) return token.index;
  }
  return null;
}

function balanceOfUnits(fixture, tokenAddress, holder) {
  const index = tokenIndexByAddress(tokenAddress);
  const systemHolder = index === null ? null : systemAddress(index);
  if (systemHolder && holder === systemHolder && fixture.systemUnits[index] !== undefined) {
    return BigInt(fixture.systemUnits[index]);
  }
  return BigInt(fixture.erc20[tokenAddress]?.[holder] ?? "0");
}

// One aggregate3 inner call: `{ success, returnData }`.
function answerInnerCall(fixture, { target, callData }) {
  const token = target.toLowerCase();
  const selector = selectorOf(callData);
  const reverts = fixture.revertingTokens.some((address) => address.toLowerCase() === token);
  if (selector === SELECTORS.getEthBalance) {
    const holder = argAddress(callData, 0);
    return { success: true, returnData: `0x${uintWord(fixture.native[holder] ?? "0")}` };
  }
  if (reverts) {
    return { success: false, returnData: "0x" };
  }
  if (selector === SELECTORS.balanceOf) {
    return { success: true, returnData: `0x${uintWord(balanceOfUnits(fixture, token, argAddress(callData, 0)))}` };
  }
  if (selector === SELECTORS.decimals) {
    const index = tokenIndexByAddress(token);
    const decimals = index === null ? undefined : fixture.decimals[index];
    return decimals === undefined
      ? { success: false, returnData: "0x" }
      : { success: true, returnData: `0x${uintWord(decimals)}` };
  }
  return { success: false, returnData: "0x" };
}

function estimateFor(fixture, tx) {
  const selector = selectorOf(tx?.data);
  if (!tx?.data || tx.data === "0x") return fixture.estimates.native;
  if (selector === SELECTORS.approve) return fixture.estimates.approve;
  if (selector === SELECTORS.coreDeposit) return fixture.estimates.deposit;
  return fixture.estimates.erc20;
}

function rpcResult(id, result) {
  return { jsonrpc: "2.0", id, result };
}

function rpcError(id, code, message, data) {
  return { jsonrpc: "2.0", id, error: data === undefined ? { code, message } : { code, message, data } };
}

// A USDC deposit that `transferFrom` would refuse: the owner's allowance for
// the CoreDepositWallet is below the amount.
function depositLacksAllowance(fixture, tx) {
  if (selectorOf(tx?.data) !== SELECTORS.coreDeposit) return false;
  if (String(tx?.to || "").toLowerCase() !== USDC_CORE_DEPOSIT_WALLET) return false;
  const units = BigInt(`0x${wordAt(String(tx.data).toLowerCase().slice(10), 0) || "0"}`);
  return allowanceUnits(fixture, USDC_TOKEN_ADDRESS, tx.from, USDC_CORE_DEPOSIT_WALLET) < units;
}

// Answer one JSON-RPC request object. `state` records unknown methods.
function answerEntry(state, entry) {
  const { fixture } = state;
  const { id, method, params = [] } = entry || {};
  switch (method) {
    case "eth_getBalance":
      return rpcResult(id, quantityHex(fixture.native[String(params[0]).toLowerCase()] ?? "0"));
    case "eth_gasPrice":
      return rpcResult(id, quantityHex(fixture.gasPriceWei));
    case "eth_chainId":
      return rpcResult(id, "0x3e7");
    case "eth_estimateGas": {
      const error = fixture.estimateErrors.shift();
      if (error) return rpcError(id, error.code, error.message, error.data);
      if (depositLacksAllowance(fixture, params[0])) {
        return rpcError(id, ALLOWANCE_REVERT.code, ALLOWANCE_REVERT.message, ALLOWANCE_REVERT.data);
      }
      return rpcResult(id, quantityHex(estimateFor(fixture, params[0])));
    }
    case "eth_call": {
      const call = params[0] || {};
      const to = String(call.to || "").toLowerCase();
      const selector = selectorOf(call.data);
      if (to === MULTICALL3_ADDRESS && selector === SELECTORS.aggregate3) {
        const calls = decodeAggregate3Calls(call.data);
        return rpcResult(id, encodeAggregate3Results(calls.map((inner) => answerInnerCall(fixture, inner))));
      }
      if (selector === SELECTORS.allowance) {
        const owner = argAddress(call.data, 0);
        const spender = argAddress(call.data, 1);
        return rpcResult(id, `0x${uintWord(allowanceUnits(fixture, to, owner, spender))}`);
      }
      if (selector === SELECTORS.balanceOf || selector === SELECTORS.decimals) {
        const { success, returnData } = answerInnerCall(fixture, { target: to, callData: call.data });
        return success ? rpcResult(id, returnData) : rpcError(id, 3, "execution reverted");
      }
      state.unknown.push({ method, params });
      return rpcError(id, 3, "execution reverted");
    }
    case "eth_getTransactionReceipt": {
      const hash = String(params[0]);
      const queue = fixture.receipts[hash];
      const answer = Array.isArray(queue) && queue.length > 0 ? queue.shift() : "receipt";
      if (answer === "pending") return rpcResult(id, null);
      const receipt = answer === "receipt" ? receiptFor(hash) : { ...receiptFor(hash), ...answer };
      for (const hook of fixture.receiptHooks || []) hook(hash, receipt);
      return rpcResult(id, receipt);
    }
    default:
      state.unknown.push({ method, params });
      return rpcError(id, -32601, `the method ${method} does not exist/is not available`);
  }
}

// Whether `entry` reads a receipt whose queue is headed by "rate-limited".
function rateLimitedReceipt(fixture, entry) {
  if (entry?.method !== "eth_getTransactionReceipt") return false;
  const queue = fixture.receipts[String(entry.params?.[0])];
  return Array.isArray(queue) && queue[0] === "rate-limited";
}

// Answer a JSON-RPC POST body (one request or a batch) as `{ status, body }`,
// as the live endpoint does:
// - a pending rate limit (`fixture.rateLimitNext`) or a receipt queued as
//   "rate-limited" answers the whole HTTP request with the single -32005
//   error object, batch or not, before any entry runs (so nothing else in
//   the batch shifts a queue or fires a receipt hook);
// - a batch of more than 20 entries gets the single -32010 error object and
//   is recorded in `state.oversizedBatches` (the app chunks to 20).
export function handleHyperEvmRpc(state, body) {
  const { fixture } = state;
  state.requests.push(body);
  if (fixture.rateLimitNext > 0) {
    fixture.rateLimitNext -= 1;
    return { status: 200, body: RATE_LIMITED_BODY };
  }
  const entries = Array.isArray(body) ? body : [body];
  if (Array.isArray(body) && body.length > MAX_BATCH_SIZE) {
    state.oversizedBatches.push(body.length);
    return { status: 200, body: BATCH_TOO_LARGE_BODY };
  }
  const limited = entries.filter((entry) => rateLimitedReceipt(fixture, entry));
  if (limited.length > 0) {
    for (const entry of limited) fixture.receipts[String(entry.params[0])].shift();
    return { status: 200, body: RATE_LIMITED_BODY };
  }
  const answers = entries.map((entry) => answerEntry(state, entry));
  return { status: 200, body: Array.isArray(body) ? answers : answers[0] };
}

export function createHyperEvmRpcState(fixture = emptyHyperEvmFixture()) {
  return { fixture, requests: [], unknown: [], oversizedBatches: [] };
}

// Every JSON-RPC request entry the handler answered, flattened, in order.
export function rpcEntries(state) {
  return state.requests.flatMap((body) => (Array.isArray(body) ? body : [body]));
}

// --- Playwright routing ---------------------------------------------------------

const rpcControllers = new WeakMap();

// Every request a mock handler answered, page- or context-level. The guard
// (`hyperevm_rpc_guard.mjs`) observes requests at the context level, apart
// from any route, and fails a test on one this set never received.
const mockedRequests = new WeakSet();

export function isHyperEvmRpcUrl(url) {
  try {
    return new URL(String(url?.href ?? url)).host === HYPEREVM_RPC_HOST;
  } catch {
    return false;
  }
}

export function wasHyperEvmRequestMocked(request) {
  return mockedRequests.has(request);
}

// Answer a routed HyperEVM RPC request from `state`'s fixture.
export async function fulfillHyperEvmRoute(route, state) {
  const request = route.request();
  let body = null;
  try {
    body = JSON.parse(request.postData() || "null");
  } catch {
    body = null;
  }
  mockedRequests.add(request);
  if (request.method() !== "POST" || body === null) {
    state.unknown.push({ method: request.method(), url: request.url() });
    await route.fulfill({ status: 405, contentType: "application/json", body: "{}" });
    return;
  }
  const { status, body: answer } = handleHyperEvmRpc(state, body);
  await route.fulfill({
    status,
    contentType: "application/json",
    headers: { "access-control-allow-origin": "*" },
    body: JSON.stringify(answer)
  });
}

// Route every request this page makes to the HyperEVM RPC host through the
// fixture. Calling it again on the same page swaps the fixture in place. The
// controller exposes `entries()` (every JSON-RPC entry answered, in order)
// and `unknown()` (methods the mock could not answer); whether anything
// escaped the mock is the guard's job, observed apart from this route.
export async function routeHyperEvmRpc(page, fixture = emptyHyperEvmFixture()) {
  const existing = rpcControllers.get(page);
  if (existing) {
    existing.setFixture(fixture);
    return existing;
  }

  const state = createHyperEvmRpcState(fixture);
  await page.route(isHyperEvmRpcUrl, (route) => fulfillHyperEvmRoute(route, state));

  const controller = {
    state,
    get fixture() {
      return state.fixture;
    },
    setFixture(next) {
      state.fixture = next;
    },
    update(fn) {
      fn(state.fixture);
    },
    entries() {
      return rpcEntries(state);
    },
    unknown() {
      return [...state.unknown];
    }
  };
  rpcControllers.set(page, controller);
  return controller;
}

export function hyperEvmRpcController(page) {
  return rpcControllers.get(page) || null;
}

// --- HyperCore info -------------------------------------------------------------

// A clearinghouseState shaped like the live one (2026-09-30T23:04Z).
export function clearinghouseState(accountValue = "0.0", withdrawable = accountValue) {
  const summary = {
    accountValue,
    totalNtlPos: "0.0",
    totalRawUsd: accountValue,
    totalMarginUsed: "0.0"
  };
  return {
    marginSummary: summary,
    crossMarginSummary: { ...summary },
    crossMaintenanceMarginUsed: "0.0",
    withdrawable,
    assetPositions: [],
    time: 1790809480164
  };
}

// A spotClearinghouseState row, shaped like the live one.
export function spotBalance(name, total, hold = "0.0", entryNtl = "0.0") {
  const token = tokenByName(name);
  return { coin: name, token: token.index, total, hold, entryNtl };
}

// The live answers an address with no HyperCore account gets for the user
// reads the HyperEVM surfaces trigger besides balances (portfolio, userFees,
// delegatorSummary, the ledger, funding, fills and orders). Provenance in the
// file: captured 2026-10-01T00:41:09Z for the spec's fixture owner.
export const ACCOUNT_LESS_USER_READS = Object.freeze(
  Object.fromEntries(
    Object.entries(
      JSON.parse(fs.readFileSync(new URL("./account_less_user_reads.json", import.meta.url), "utf8")).answers
    ).map(([type, { answer }]) => [type, answer])
  )
);

export function hyperCoreInfoFixture() {
  return {
    userReads: { ...ACCOUNT_LESS_USER_READS },
    spotMeta: MAINNET_SPOT_META,
    spotAssetCtxs: SPOT_ASSET_CTXS,
    perpMetaAndAssetCtxs: PERP_META_AND_ASSET_CTXS,
    perpDexs: [null],
    outcomeMeta: OUTCOME_META,
    spotStates: { ...LIVE_SYSTEM_CORE_STATES },
    clearinghouseStates: {},
    userRoles: {},
    userAbstractions: {},
    subAccounts: {},
    requests: []
  };
}

// `answerHyperCoreInfo` returns this for a request it leaves to the network.
export const NOT_HANDLED = Symbol("hyperCoreInfo.notHandled");

function orNotHandled(value) {
  return value === undefined || value === null ? NOT_HANDLED : value;
}

// Answer one info POST body, or NOT_HANDLED to let it through. JSON `null` is
// a real answer: live `subAccounts` of an address with none is `null`
// (2026-10-01T00:28:43Z).
export function answerHyperCoreInfo(fixture, payload) {
  const user = String(payload?.user || "").toLowerCase();
  switch (payload?.type) {
    case "spotMeta":
      return orNotHandled(fixture.spotMeta);
    case "spotMetaAndAssetCtxs":
      return fixture.spotMeta && fixture.spotAssetCtxs ? [fixture.spotMeta, fixture.spotAssetCtxs] : NOT_HANDLED;
    case "metaAndAssetCtxs":
      return payload.dex ? NOT_HANDLED : orNotHandled(fixture.perpMetaAndAssetCtxs);
    case "perpDexs":
      return orNotHandled(fixture.perpDexs);
    case "outcomeMeta":
      return orNotHandled(fixture.outcomeMeta);
    case "spotClearinghouseState":
      return fixture.spotStates[user] || { balances: [] };
    case "clearinghouseState":
      if (payload.dex) return NOT_HANDLED;
      return fixture.clearinghouseStates[user] || clearinghouseState();
    case "userRole":
      return fixture.userRoles[user] || { role: "missing" };
    case "userAbstraction":
      return fixture.userAbstractions[user] || "default";
    case "subAccounts":
      return fixture.subAccounts[user] ?? null;
    default:
      if (user && fixture.userReads && Object.hasOwn(fixture.userReads, payload.type)) {
        return fixture.userReads[payload.type];
      }
      return NOT_HANDLED;
  }
}

// `strict` records, in `fixture.unhandledUserRequests`, every info request
// that carries a `user` and that the fixture let through to the live API, so
// a spec can assert that no account read it depends on is live.
export async function routeHyperCoreInfo(page, fixture = hyperCoreInfoFixture(), { strict = false } = {}) {
  fixture.unhandledUserRequests = fixture.unhandledUserRequests || [];
  await page.route(HYPERCORE_INFO_URL, async (route) => {
    const request = route.request();
    let payload = null;
    try {
      payload = request.postDataJSON();
    } catch {
      payload = null;
    }
    const answer = payload ? answerHyperCoreInfo(fixture, payload) : NOT_HANDLED;
    if (answer === NOT_HANDLED) {
      if (strict && payload?.user) fixture.unhandledUserRequests.push(payload);
      await route.fallback();
      return;
    }
    fixture.requests.push(payload);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(answer)
    });
  });
  return fixture;
}

// The user streams that carry balances. A fixture account has no live
// HyperCore state, so the live stream would overwrite the fixture's REST
// answers with zeros.
export const ACCOUNT_BALANCE_STREAMS = Object.freeze([
  "clearinghouseState",
  "webData2",
  "spotState"
]);

// Whether a page -> server websocket message subscribes (or unsubscribes) a
// user's balance stream.
export function isAccountBalanceSubscription(message) {
  let payload = null;
  try {
    payload = JSON.parse(String(message));
  } catch {
    return false;
  }
  const subscription = payload?.subscription;
  return (
    (payload?.method === "subscribe" || payload?.method === "unsubscribe") &&
    Boolean(subscription?.user) &&
    ACCOUNT_BALANCE_STREAMS.includes(subscription?.type)
  );
}

// Keep the live market websocket, but never subscribe a user's balance
// streams, so `routeHyperCoreInfo`'s REST answers stay the account's only
// balances. Returns the dropped messages.
export async function routeHyperCoreAccountStreams(page) {
  const dropped = [];
  await page.routeWebSocket(HYPERCORE_WS_URL, (ws) => {
    const server = ws.connectToServer();
    ws.onMessage((message) => {
      if (isAccountBalanceSubscription(message)) {
        dropped.push(String(message));
        return;
      }
      server.send(message);
    });
  });
  return dropped;
}
