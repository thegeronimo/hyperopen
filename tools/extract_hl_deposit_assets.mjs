#!/usr/bin/env node

import vm from "node:vm";

const DEFAULT_TRADE_URL = "https://app.hyperliquid.xyz/trade";
const DEFAULT_TIMEOUT_MS = 15_000;

const FLOW_KIND_BY_CHAIN_KEY = Object.freeze({
  arbitrum: "bridge2",
  arbitrum_cctp: "bridge2",
  evm: "bridge2",
  bitcoin: "hyperunit-address",
  ethereum: "hyperunit-address",
  solana: "hyperunit-address",
  monad: "hyperunit-address",
  plasma: "hyperunit-address",
  lifi: "route",
  arbitrum_across: "route",
  base_across: "route",
});

function parseArgs(argv) {
  const args = {tradeUrl: DEFAULT_TRADE_URL};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (token === "--trade-url") {
      args.tradeUrl = argv[i + 1] ?? DEFAULT_TRADE_URL;
      i += 1;
    }
  }
  return args;
}

function timeoutSignal(ms) {
  if (typeof AbortSignal?.timeout === "function") {
    return AbortSignal.timeout(ms);
  }
  const controller = new AbortController();
  setTimeout(() => controller.abort(), ms);
  return controller.signal;
}

async function fetchText(url) {
  const response = await fetch(url, {signal: timeoutSignal(DEFAULT_TIMEOUT_MS)});
  if (!response.ok) {
    throw new Error(`Failed to fetch ${url} (${response.status})`);
  }
  return response.text();
}

function resolveMainBundleUrl(tradeUrl, html) {
  const scriptMatch =
    html.match(/<script[^>]+src="([^"]*\/static\/js\/main\.[^"]+\.js)"/i) ??
    html.match(/src="([^"]*\/static\/js\/main\.[^"]+\.js)"/i);
  if (!scriptMatch) {
    throw new Error("Unable to locate main Hyperliquid bundle URL.");
  }
  return new URL(scriptMatch[1], tradeUrl).href;
}

function extractDepositSelectorLiterals(bundleText) {
  const regex = /const c=(\[[\s\S]*?\]),u=(\[[\s\S]*?\]),d=(\{[\s\S]*?\});function h\(/;
  const match = bundleText.match(regex);
  if (!match) {
    throw new Error("Unable to locate deposit selector asset map in frontend bundle.");
  }
  return {cLiteral: match[1], uLiteral: match[2], dLiteral: match[3]};
}

function evaluateAssetMap({cLiteral, uLiteral, dLiteral}) {
  const source = [
    `"use strict";`,
    `const c = ${cLiteral};`,
    `const u = ${uLiteral};`,
    `const d = ${dLiteral};`,
    `d;`,
  ].join("\n");
  const value = vm.runInNewContext(source, Object.create(null), {timeout: 1000});
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Extracted deposit asset map is not an object.");
  }
  return value;
}

function normalizeAssetEntries(rawMap) {
  return Object.entries(rawMap)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([assetKey, routes]) => {
      const routeEntries = (Array.isArray(routes) ? routes : []).map((entry) => {
        const chainKey = String(entry?.value ?? "");
        return {
          chainKey,
          label: String(entry?.label ?? chainKey),
          flowKind: FLOW_KIND_BY_CHAIN_KEY[chainKey] ?? "unknown",
        };
      });
      const flowKinds = Array.from(new Set(routeEntries.map((route) => route.flowKind))).sort();
      return {
        assetKey,
        routes: routeEntries,
        flowKinds,
      };
    });
}

function summarizeByFlowKind(assets) {
  const grouped = {};
  for (const asset of assets) {
    for (const flowKind of asset.flowKinds) {
      if (!grouped[flowKind]) {
        grouped[flowKind] = [];
      }
      grouped[flowKind].push(asset.assetKey);
    }
  }
  for (const key of Object.keys(grouped)) {
    grouped[key].sort();
  }
  return grouped;
}

function knownCoverage(assets) {
  const userMentionedAssets = [
    "usdt",
    "btc",
    "eth",
    "sol",
    "2z",
    "bonk",
    "ena",
    "fart",
    "mon",
    "pump",
    "spxs",
    "xpl",
  ];
  const available = new Set(assets.map((asset) => asset.assetKey));
  return {
    userMentionedAssets,
    present: userMentionedAssets.filter((asset) => available.has(asset)),
    missing: userMentionedAssets.filter((asset) => !available.has(asset)),
    additional: assets
      .map((asset) => asset.assetKey)
      .filter((asset) => !userMentionedAssets.includes(asset)),
  };
}

function formatSnapshot({tradeUrl, bundleUrl, assets}) {
  const unknownRoutes = assets
    .flatMap((asset) =>
      asset.routes
        .filter((route) => route.flowKind === "unknown")
        .map((route) => ({assetKey: asset.assetKey, chainKey: route.chainKey})),
    )
    .sort((a, b) =>
      a.assetKey === b.assetKey
        ? a.chainKey.localeCompare(b.chainKey)
        : a.assetKey.localeCompare(b.assetKey),
    );

  return {
    generatedAt: new Date().toISOString(),
    sources: {
      tradePageUrl: tradeUrl,
      bundleUrl,
      extractionType: "hyperliquid-frontend-bundle",
    },
    assumptions: {
      flowKindByChainKey: FLOW_KIND_BY_CHAIN_KEY,
      notes: [
        "bridge2 indicates direct wallet transaction style flow (existing Hyperopen USDC implementation baseline).",
        "hyperunit-address indicates deposit-address generation/instruction flow.",
        "route indicates third-party bridge/swap route flow keys (for example lifi/across).",
      ],
    },
    assets,
    summary: {
      assetCount: assets.length,
      byFlowKind: summarizeByFlowKind(assets),
      userMentionedCoverage: knownCoverage(assets),
      unknownRoutes,
    },
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const html = await fetchText(args.tradeUrl);
  const bundleUrl = resolveMainBundleUrl(args.tradeUrl, html);
  const bundleText = await fetchText(bundleUrl);
  const literals = extractDepositSelectorLiterals(bundleText);
  const rawMap = evaluateAssetMap(literals);
  const assets = normalizeAssetEntries(rawMap);
  const snapshot = formatSnapshot({tradeUrl: args.tradeUrl, bundleUrl, assets});
  process.stdout.write(`${JSON.stringify(snapshot, null, 2)}\n`);
}

main().catch((error) => {
  process.stderr.write(`extract_hl_deposit_assets failed: ${error.message}\n`);
  process.exit(1);
});
