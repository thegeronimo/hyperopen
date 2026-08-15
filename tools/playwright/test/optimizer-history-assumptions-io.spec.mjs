// Proxy-workflow agent IO: export the assumptions template, import a completed
// (and markdown-fenced) file, and watch the draft author itself. History-quality
// outcomes (Configured status, usable-proxy validation) are deliberately NOT
// asserted here — they depend on loaded history and stay covered by unit tests;
// this spec pins the deterministic file mechanics end to end.
import { readFile } from "node:fs/promises";
import { expect, test } from "@playwright/test";
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
    // BTC carries a full year+ of candles so it reads as a VERIFIED proxy
    // candidate; WLFI has none, so it is the short-history asset needing help.
    seedPatch(
      optimizerPath("history-data", "candle-history-by-coin", stateKey("BTC")),
      Array.from({ length: 400 }, (_unused, i) => ({ time: i, close: String(100 + (i % 7)) }))
    ),
    // A started entry enrolls WLFI in the workflow without loaded history
    // (pending history correctly never flags a card on its own).
    seedPatch(
      optimizerPath("draft", "history-assumptions"),
      stringMap([["perp:WLFI", wlfiEntry]])
    )
  ]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

// The section is a collapsed-by-exception <details> (2026-07-10): it rests
// closed when nothing needs attention, so toolbar interactions must open it
// first. Re-checked before every interaction group because a state change can
// legitimately re-collapse it (needs-attention transitioning true -> false
// drops the forced :open).
async function ensureAssumptionsOpen(page) {
  const section = page.locator("[data-role='portfolio-optimizer-history-assumptions-section']");
  if (!(await section.evaluate((el) => el.open))) {
    await section.locator("summary").first().click();
  }
}

// The queue leads with the model and keeps every hand-editing control behind
// one opt-in disclosure, so any assertion about chips/inputs has to open it.
// Both disclosures are DOM state, so this is a plain summary click.
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

  // One asset is in the workflow: the section's trailing status role exists
  // exactly while the workflow is non-empty (its text now carries configured/
  // needs-setup state rather than a raw count, so assert presence).
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumptions-count']"))
    .toHaveCount(1);
  await ensureAssumptionsOpen(page);

  // --- Export (workflow scope): the template carries the flagged asset, the
  // user-selected candidate menu, and the embedded agent instructions.
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
  // The file carries ONLY the workflow assets — no candidate menu (neither the
  // universe nor the ~1,500-market live catalog streaming into the page).
  expect(document["proxy-candidates"]).toBeUndefined();

  // --- Export (universe scope): every included universe asset is a row, each
  // with its own history-status, and NO redundant candidate menu.
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

  // --- Import: fill the template the way an agent would — and wrap it in a
  // markdown fence, which the import path must tolerate.
  const completed = structuredClone(document);
  completed.assets[0].approach = "proxy";
  // The agent proposes proxies from its own knowledge. The SOL ref uses the
  // exact malformed shape a real agent produced (the instrument-id written
  // into the `symbol` field) — resolution must accept it. perp:SOL is also
  // out-of-universe: import resolution is catalog-tolerant (same reach as the
  // in-app proxy typeahead) and stores it reference-only.
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

  // The draft entry is authored exactly as if hand-configured.
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

  // The out-of-universe proxy is stored reference-only, and the card shows the
  // agent's rationale plus both basket chips.
  const references = await readOptimizerState(page, [
    "portfolio", "optimizer", "draft", "proxy-reference-instruments"
  ]);
  // Assert on :coin — clj->js drops keyword namespaces, so the decoration key
  // :optimizer-history/instrument-id shadows :instrument-id in this JS view.
  expect((references ?? []).map((reference) => reference.coin)).toContain("SOL");
  // The import may settle the asset and let the section re-collapse; the queue
  // content is still there, one summary-click away.
  await ensureAssumptionsOpen(page);
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-rationale-perp:WLFI']"))
    .toContainText("Agent rationale: Anchor plus Solana ecosystem beta.");
  // The imported basket reads off the queue's model line without opening
  // anything — the panel leads with the model, not with the editor.
  await expect(page.locator("[data-role='portfolio-optimizer-history-assumption-model-line']"))
    .toContainText("BTC");
  // The chips themselves are editor state, so they live behind the opt-in
  // "Adjust by hand" drawer (queue restructure 2026-08-14).
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
  // the seeded entry is untouched.
  expect(entry["perp:WLFI"].proxy["instrument-ids"]).toEqual([]);
});
