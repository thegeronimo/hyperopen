import { readFile } from "node:fs/promises";
import { expect, test } from "../support/guarded_test.mjs";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";
import {
  keyword,
  optimizerPath,
  seedOptimizerMarkets,
  seedOptimizerState,
  seedPatch,
  readOptimizerState,
  stateKey,
  stringMap
} from "../support/optimizer_state.mjs";

function instrument(instrumentId, marketType, coin, symbol) {
  return {
    "instrument-id": instrumentId,
    "market-type": keyword(marketType),
    coin,
    symbol
  };
}

const wlfiEntry = {
  behavior: keyword("proxy"),
  "expected-return": 0,
  volatility: 0.8,
  "max-weight": 0.05,
  proxy: {
    "instrument-ids": [],
    "relationship-strength": keyword("medium"),
    "prior-weights": null
  }
};

async function seedWorkflow(page) {
  await seedOptimizerMarkets(page, [
    { key: "perp:BTC", "market-type": "perp", coin: "BTC", symbol: "BTC-USDC", name: "Bitcoin" },
    { key: "perp:SOL", "market-type": "perp", coin: "SOL", symbol: "SOL-USDC", name: "Solana" }
  ]);
  await seedOptimizerState(page, [
    seedPatch(optimizerPath("draft", "universe"), [
      instrument("perp:WLFI", "perp", "WLFI", "WLFI-USDC"),
      instrument("perp:BTC", "perp", "BTC", "BTC-USDC")
    ]),
    seedPatch(
      optimizerPath("history-data", "candle-history-by-coin", stateKey("BTC")),
      Array.from({ length: 400 }, (_unused, i) => ({ time: i, close: String(100 + (i % 7)) }))
    ),
    seedPatch(
      optimizerPath("draft", "history-assumptions"),
      stringMap([["perp:WLFI", wlfiEntry]])
    )
  ]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function ensureAssumptionsOpen(page) {
  const section = page.locator("[data-role='portfolio-optimizer-history-assumptions-section']");
  if (!(await section.evaluate((el) => el.open))) {
    await section.locator("summary").first().click();
  }
}

async function ensureAdjustOpen(page, instrumentId) {
  const drawer = page.locator(
    `[data-role='portfolio-optimizer-history-assumption-adjust-${instrumentId}']`
  );
  await expect(drawer).toHaveCount(1);
  if (!(await drawer.evaluate((el) => el.open))) {
    await drawer.locator("summary").first().click();
  }
}

async function importFile(page, name, contents) {
  await ensureAssumptionsOpen(page);
  const [chooser] = await Promise.all([
    page.waitForEvent("filechooser"),
    page.click("[data-role='portfolio-optimizer-history-assumptions-import']")
  ]);
  await chooser.setFiles({
    name,
    mimeType: "application/json",
    buffer: Buffer.from(contents)
  });
}

test("portfolio optimizer history assumptions export/import round-trips an agent file @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");
  await seedWorkflow(page);

  await expect(page.locator("[data-role='portfolio-optimizer-history-assumptions-count']"))
    .toHaveCount(1);
  await ensureAssumptionsOpen(page);

  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.click("[data-role='portfolio-optimizer-history-assumptions-export-workflow']")
  ]);
  expect(download.suggestedFilename()).toMatch(/^history-assumptions-workflow-\d+\.json$/);
  const document = JSON.parse(await readFile(await download.path(), "utf8"));
  expect(document.type).toBe("hyperopen-optimizer-history-assumptions");
  expect(document.version).toBe(1);
  expect(document["export-scope"]).toBe("proxy-workflow");
  expect(Array.isArray(document.instructions)).toBe(true);
  expect(document["history-policy"]).toEqual({
    "minimum-history-days": 360,
    "preferred-history-days": 720,
    "candle-interval": "1d"
  });
  expect(document.assets.map((asset) => asset["instrument-id"])).toEqual(["perp:WLFI"]);
  expect(document.assets[0].approach).toBe("proxy");
  expect(document["proxy-candidates"]).toBeUndefined();

  const [universeDownload] = await Promise.all([
    page.waitForEvent("download"),
    page.click("[data-role='portfolio-optimizer-history-assumptions-export-universe']")
  ]);
  expect(universeDownload.suggestedFilename()).toMatch(/^history-assumptions-universe-\d+\.json$/);
  const universeDocument = JSON.parse(await readFile(await universeDownload.path(), "utf8"));
  expect(universeDocument["export-scope"]).toBe("universe");
  expect(universeDocument.assets.map((asset) => [asset["instrument-id"], asset["history-status"]]))
    .toEqual([["perp:WLFI", "unverified"], ["perp:BTC", "verified"]]);
  expect(universeDocument["proxy-candidates"]).toBeUndefined();

  await expect(page.locator("[data-role='portfolio-optimizer-history-assumptions-io-note']"))
    .toHaveAttribute("data-kind", "success");

  const completed = structuredClone(document);
  completed.assets[0].approach = "proxy";
  completed.assets[0].proxies = [
    { "instrument-id": "perp:BTC", weight: 0.7 },
    { symbol: "perp:SOL", weight: 0.3 }
  ];
  completed.assets[0]["relationship-strength"] = "high";
  completed.assets[0].rationale = "Anchor plus Solana ecosystem beta.";
  await importFile(page, "completed.json", "```json\n" + JSON.stringify(completed, null, 2) + "\n```");

  const note = page.locator("[data-role='portfolio-optimizer-history-assumptions-io-note']");
  await expect(note).toContainText("Configured 1 asset");
  await expect(note).toHaveAttribute("data-kind", "success");

  const entry = await readOptimizerState(page, [
    "portfolio", "optimizer", "draft", "history-assumptions"
  ]);
  const imported = entry["perp:WLFI"];
  expect(imported.behavior).toBe("proxy");
  expect(imported.proxy["instrument-ids"]).toEqual(["perp:BTC", "perp:SOL"]);
  expect(imported.proxy["relationship-strength"]).toBe("high");
  expect(imported.proxy["prior-weights"]).toEqual({ "perp:BTC": 0.7, "perp:SOL": 0.3 });
  expect(imported.metadata.source).toBe("agent-import");
  expect(imported.metadata.rationale).toBe("Anchor plus Solana ecosystem beta.");

  const references = await readOptimizerState(page, [
    "portfolio", "optimizer", "draft", "proxy-reference-instruments"
  ]);
  expect((references ?? []).map((reference) => reference.coin)).toContain("SOL");
  await ensureAssumptionsOpen(page);
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-rationale-perp:WLFI']"))
    .toContainText("Agent rationale: Anchor plus Solana ecosystem beta.");
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-model-line']"))
    .toContainText("BTC");
  await ensureAdjustOpen(page, "perp:WLFI");
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-proxy-chip-perp:WLFI-perp:BTC']"))
    .toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-proxy-chip-perp:WLFI-perp:SOL']"))
    .toBeVisible();
});

test("portfolio optimizer history assumptions import rejects an unusable file @regression", async ({ page }) => {
  await visitRoute(page, "/portfolio/optimize/new");
  await seedWorkflow(page);

  await importFile(page, "garbage.json", "this is not json");

  const note = page.locator("[data-role='portfolio-optimizer-history-assumptions-io-note']");
  await expect(note).toContainText("not valid history-assumptions JSON");
  await expect(note).toHaveAttribute("data-kind", "error");

  const entry = await readOptimizerState(page, [
    "portfolio", "optimizer", "draft", "history-assumptions"
  ]);
  expect(entry["perp:WLFI"].proxy["instrument-ids"]).toEqual([]);
});
