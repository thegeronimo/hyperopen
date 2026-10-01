import { expect, test } from "../support/guarded_test.mjs";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";
import {
  keyword,
  optimizerPath,
  readOptimizerState,
  seedOptimizerMarkets,
  seedOptimizerState,
  seedPatch,
  stateKey,
  stringMap
} from "../support/optimizer_state.mjs";

const DESIGN_REVIEW_VIEWPORTS = Object.freeze([
  { id: "review-375", width: 375, height: 812 },
  { id: "review-768", width: 768, height: 1024 },
  { id: "review-1280", width: 1280, height: 900 },
  { id: "review-1440", width: 1440, height: 900 }
]);
const OPTIMIZER_SPECTATE_ADDRESS = "0x162cc7c861ebd0c06b3d72319201150482518185";

function market(key, marketType, coin, symbol, dex = null) {
  return {
    key,
    "market-type": keyword(marketType),
    coin,
    symbol,
    ...(dex ? { dex } : {})
  };
}

function instrument(instrumentId, marketType, coin, symbol, extra = {}) {
  return {
    "instrument-id": instrumentId,
    "market-type": keyword(marketType),
    coin,
    ...(symbol ? { symbol } : {}),
    ...extra
  };
}

function candle(time, close) {
  return { time, close };
}

function emptyStringMap() {
  return stringMap([]);
}

function blackLittermanDraft({ id, name, universe, views = [], metadata = null }) {
  return {
    ...(id ? { id } : {}),
    ...(name ? { name } : {}),
    universe,
    objective: { kind: keyword("max-sharpe") },
    "return-model": {
      kind: keyword("black-litterman"),
      views
    },
    "risk-model": { kind: keyword("sample-covariance") },
    constraints: {
      "long-only?": true,
      "max-asset-weight": 1
    },
    ...(metadata ? { metadata } : {})
  };
}

async function seedMarkets(page) {
  await seedOptimizerMarkets(page, [
    market("perp:BTC", "perp", "BTC", "BTC-USDC", "hl"),
    market("perp:ETH", "perp", "ETH", "ETH-USDC", "hl"),
    market("perp:SOL", "perp", "SOL", "SOL-USDC", "hl"),
    market("perp:HYPE", "perp", "HYPE", "HYPE-USDC", "hl")
  ]);
}

async function seedBlackLittermanEditorState(page) {
  const absoluteView = {
    id: "view-1",
    kind: keyword("absolute"),
    "instrument-id": "perp:HYPE",
    return: 0.45,
    confidence: 0.75,
    horizon: keyword("1y"),
    notes: "Momentum conviction"
  };
  const relativeView = {
    id: "view-2",
    kind: keyword("relative"),
    "instrument-id": "perp:ETH",
    "comparator-instrument-id": "perp:SOL",
    direction: keyword("outperform"),
    return: 0.05,
    confidence: 0.5,
    horizon: keyword("6m"),
    notes: "Pair view"
  };
  await seedOptimizerState(page, [
    // The Return views editor renders one row per UNIVERSE instrument; the
    // /optimize/new preseed leaves the universe empty in tests, so seed it or
    // no rows exist at all.
    seedPatch(optimizerPath("draft", "universe"), [
      instrument("perp:BTC", "perp", "BTC", "BTC-USDC"),
      instrument("perp:ETH", "perp", "ETH", "ETH-USDC"),
      instrument("perp:SOL", "perp", "SOL", "SOL-USDC"),
      instrument("perp:HYPE", "perp", "HYPE", "HYPE-USDC")
    ]),
    seedPatch(optimizerPath("draft", "return-model"), {
      kind: keyword("black-litterman"),
      views: [absoluteView, relativeView]
    })
  ]);
}

async function seedBlackLittermanAutomaticReturnState(page) {
  await seedOptimizerState(page, [
    seedPatch(
      optimizerPath("draft"),
      blackLittermanDraft({
        universe: [instrument("perp:BTC", "perp", "BTC", "BTC-USDC")]
      })
    ),
    seedPatch(optimizerPath("history-data", "candle-history-by-coin", stateKey("BTC")), [
      candle(1000, "100"),
      candle(2000, "100.01"),
      candle(3000, "100.040003")
    ]),
    seedPatch(optimizerPath("history-data", "funding-history-by-coin"), emptyStringMap()),
    seedPatch(optimizerPath("market-cap-by-coin"), stringMap([["BTC", 1]])),
    seedPatch(optimizerPath("runtime", "as-of-ms"), 5000),
    seedPatch(optimizerPath("history-load-state"), {
      status: keyword("loading"),
      reason: keyword("selection-prefetch")
    })
  ]);
}

async function seedBlackLittermanBtcRunState(page) {
  await seedOptimizerState(page, [
    seedPatch(
      optimizerPath("draft"),
      blackLittermanDraft({
        id: "bl-draft-current",
        name: "BL Current Draft",
        universe: [
          instrument("perp:BTC", "perp", "BTC", "BTC-USDC"),
          instrument("perp:ETH", "perp", "ETH", "ETH-USDC")
        ]
      })
    ),
    seedPatch(
      optimizerPath("history-data", "candle-history-by-coin"),
      stringMap([
        [
          "BTC",
          [
            candle(1000, "100"),
            candle(2000, "99.92"),
            candle(3000, "100.01"),
            candle(4000, "99.7")
          ]
        ],
        [
          "ETH",
          [
            candle(1000, "2000"),
            candle(2000, "2020"),
            candle(3000, "2010"),
            candle(4000, "2030")
          ]
        ]
      ])
    ),
    seedPatch(optimizerPath("history-data", "funding-history-by-coin"), emptyStringMap()),
    seedPatch(optimizerPath("market-cap-by-coin"), stringMap([["BTC", 100], ["ETH", 100]])),
    seedPatch(optimizerPath("runtime", "as-of-ms"), 5000),
    seedPatch(optimizerPath("history-load-state"), { status: keyword("idle") })
  ]);
}

async function seedOptimizerAccountValue(page, accountValue) {
  await seedOptimizerState(page, [
    seedPatch(["account-context", "spectate-mode"], {
      "active?": true,
      address: OPTIMIZER_SPECTATE_ADDRESS,
      "started-at-ms": 1777046300000
    }),
    seedPatch(["webdata2"], {
      clearinghouseState: {
        marginSummary: { accountValue },
        assetPositions: []
      }
    })
  ]);
}

async function seedBlackLittermanDirtyRetainedResultState(page) {
  const activeView = {
    id: "view-1",
    kind: keyword("absolute"),
    "instrument-id": "perp:BTC",
    return: 0.2,
    confidence: 0.75,
    weights: stringMap([["perp:BTC", 1]])
  };
  await seedOptimizerState(page, [
    seedPatch(
      optimizerPath("draft"),
      blackLittermanDraft({
        universe: [instrument("perp:BTC", "perp", "BTC", "BTC-USDC")],
        views: [activeView],
        metadata: { "dirty?": true }
      })
    ),
    seedPatch(optimizerPath("history-data", "candle-history-by-coin", stateKey("BTC")), [
      candle(1000, "100"),
      candle(2000, "95"),
      candle(3000, "92"),
      candle(4000, "90")
    ]),
    seedPatch(optimizerPath("history-data", "funding-history-by-coin"), emptyStringMap()),
    seedPatch(optimizerPath("market-cap-by-coin"), stringMap([["BTC", 100]])),
    seedPatch(optimizerPath("runtime", "as-of-ms"), 5000),
    seedPatch(optimizerPath("history-load-state"), { status: keyword("idle") }),
    seedPatch(optimizerPath("run-state"), {
      status: keyword("running"),
      "run-id": "run-new-view",
      "started-at-ms": 5000
    }),
    seedPatch(optimizerPath("last-successful-run"), {
      "request-signature": { seed: "old-result" },
      "computed-at-ms": 4000,
      result: {
        status: keyword("solved"),
        "instrument-ids": ["perp:BTC"],
        "target-weights": [1],
        "current-weights": [0],
        frontier: [],
        "frontier-overlays": {}
      }
    })
  ]);
}

async function readBlackLittermanDraftViews(page) {
  const views = await readOptimizerState(page, optimizerPath("draft", "return-model", "views"));
  const firstView = views?.[0] || null;
  return {
    count: views?.length || 0,
    firstInstrumentId: firstView?.["instrument-id"] || null,
    firstReturn: firstView?.return ?? null,
    firstConfidence: firstView?.confidence ?? null
  };
}

async function readBlackLittermanRunResult(page) {
  const result = await readOptimizerState(page, optimizerPath("last-successful-run", "result"));
  if (!result) {
    return null;
  }

  const standalone = result["frontier-overlays"]?.standalone || [];
  const standaloneBtc = standalone.find((point) => point["instrument-id"] === "perp:BTC");

  return {
    status: String(result.status),
    returnModel: String(result["return-model"]),
    viewCount: result["black-litterman-diagnostics"]?.["view-count"] ?? null,
    expectedBtc: result["expected-returns-by-instrument"]?.["perp:BTC"] ?? null,
    standaloneBtc: standaloneBtc?.["expected-return"] ?? null
  };
}

async function seedBlackLittermanVaultPreviewState(page) {
  const vaultAddress = "0x3333333333333333333333333333333333333333";
  const vaultId = `vault:${vaultAddress}`;

  const dayStartMs = (day) => new Date(`${day}T00:00:00.000Z`).getTime();
  const summaryFromPoints = (points) => ({
    accountValueHistory: points.map(([timeMs, accountValue]) => [timeMs, accountValue]),
    pnlHistory: points.map(([timeMs, _accountValue, pnlValue]) => [timeMs, pnlValue])
  });
  const t0 = dayStartMs("2026-05-01");
  const t1 = dayStartMs("2026-05-02");
  const t2 = dayStartMs("2026-05-03");
  const t3 = dayStartMs("2026-05-04");
  const absoluteView = {
    id: "view-1",
    kind: keyword("absolute"),
    "instrument-id": vaultId,
    return: 0.04,
    confidence: 0.8,
    weights: stringMap([[vaultId, 1]])
  };

  await seedOptimizerState(page, [
    seedPatch(
      optimizerPath("draft"),
      {
        universe: [
          instrument("perp:BTC", "perp", "BTC", "BTC-USDC"),
          instrument(vaultId, "vault", vaultId, undefined, {
            "vault-address": vaultAddress,
            name: "Alpha Yield"
          })
        ],
        objective: { kind: keyword("max-sharpe") },
        "return-model": {
          kind: keyword("black-litterman"),
          views: [absoluteView]
        },
        "risk-model": { kind: keyword("sample-covariance") },
        constraints: {
          "long-only?": true,
          "max-asset-weight": 0.75
        }
      }
    ),
    seedPatch(optimizerPath("history-data"), {
      "candle-history-by-coin": stringMap([
        [
          "BTC",
          [
            candle(t0, "100"),
            candle(t1, "101"),
            candle(t2, "99"),
            candle(t3, "102")
          ]
        ]
      ]),
      "funding-history-by-coin": emptyStringMap(),
      "vault-details-by-address": stringMap([
        [
          vaultAddress,
          {
            portfolio: {
              month: summaryFromPoints([
                [t0, 100, 0],
                [t1, 102, 2],
                [t2, 99, -1],
                [t3, 104, 4]
              ])
            }
          }
        ]
      ])
    }),
    seedPatch(optimizerPath("market-cap-by-coin"), stringMap([["BTC", 100], [vaultId, 100]])),
    seedPatch(optimizerPath("runtime", "as-of-ms"), t3 + 24 * 60 * 60 * 1000),
    seedPatch(optimizerPath("history-load-state"), { status: keyword("idle") })
  ]);
  return { vaultId };
}

async function visitOptimizerNew(page) {
  const routeSurface = page.locator("[data-role='portfolio-optimizer-setup-route-surface']");
  let lastError = null;

  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      await visitRoute(page, "/portfolio/optimize/new", {
        routeModuleTimeoutMs: 30_000,
        idleOptions: { quietMs: 400, timeoutMs: 8_000, pollMs: 50 }
      });
      await expect(routeSurface).toBeVisible({ timeout: 30_000 });
      return;
    } catch (error) {
      lastError = error;
      if (attempt === 2) {
        throw error;
      }
      await page.goto("/trade");
      await page.waitForTimeout(250);
    }
  }

  throw lastError;
}

test("portfolio optimizer max sharpe return views setup editor uses compact row controls @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.setViewportSize({ width: 900, height: 900 });
  await visitOptimizerNew(page);

  await seedMarkets(page);
  await seedBlackLittermanEditorState(page);

  const editor = page.locator("[data-role='portfolio-optimizer-setup-use-my-views-editor']");
  await expect(editor).toBeVisible();
  await expect(editor).toContainText("Used by Maximum Sharpe");
  await expect(page.locator("[data-role='portfolio-optimizer-black-litterman-panel']"))
    .toHaveCount(0);
  await expect(editor.locator("select")).toHaveCount(0);

  const hypeRow = editor.locator("[data-role='portfolio-optimizer-objective-menu-view-row-perp:HYPE']");
  const hypeReturn = editor.locator("[data-role='portfolio-optimizer-objective-menu-view-perp:HYPE-return']");
  const hypeConfidenceHigh = editor.locator(
    "[data-role='portfolio-optimizer-objective-menu-view-perp:HYPE-confidence-high']"
  );
  const hypeConfidenceLow = editor.locator(
    "[data-role='portfolio-optimizer-objective-menu-view-perp:HYPE-confidence-low']"
  );
  const hypeReset = editor.locator("[data-role='portfolio-optimizer-objective-menu-view-perp:HYPE-reset']");
  const btcRow = editor.locator("[data-role='portfolio-optimizer-objective-menu-view-row-perp:BTC']");

  // The authored HYPE view carries honest provenance: user source chip,
  // selected confidence, and a reset-to-implied affordance.
  await expect(hypeRow).toHaveAttribute("data-source", "user");
  await expect(hypeRow.locator("[data-role='portfolio-optimizer-objective-menu-view-perp:HYPE-source']"))
    .toHaveAttribute("data-source", "user");
  await expect(hypeReturn).toHaveValue("45");
  await expect(hypeConfidenceHigh).toHaveAttribute("data-selected", "true");
  await expect(hypeReset).toBeVisible();

  // An untouched row stays implied: no user chip, no selected confidence.
  await expect(btcRow).toHaveAttribute("data-source", "implied");
  await expect(btcRow.locator("[data-role*='-confidence-'][data-selected='true']"))
    .toHaveCount(0);

  const readHypeView = async () => {
    const views = await readOptimizerState(page, optimizerPath("draft", "return-model", "views"));
    return (views || []).find((view) => view["instrument-id"] === "perp:HYPE") || null;
  };

  // Row edits materialize into the draft immediately — no Run/Apply staging.
  await hypeReturn.fill("12.5");
  await expect(hypeReturn).toHaveValue("12.5");
  await expect
    .poll(async () => (await readHypeView())?.return, {
      message: "typing a parseable return should update the draft view immediately",
      timeout: 4_000
    })
    .toBe(0.125);

  await hypeConfidenceLow.click();
  await expect(hypeConfidenceLow).toHaveAttribute("data-selected", "true");
  await expect
    .poll(async () => String((await readHypeView())?.["confidence-level"]), {
      message: "a confidence click should re-weight the authored view immediately",
      timeout: 4_000
    })
    .toBe("low");
  await expect(hypeReturn).toHaveValue("12.5");
});

test("portfolio optimizer max sharpe return views prepopulate the implied baseline while history is loading @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.setViewportSize({ width: 900, height: 900 });
  await visitOptimizerNew(page);

  await seedBlackLittermanAutomaticReturnState(page);

  const editor = page.locator("[data-role='portfolio-optimizer-setup-use-my-views-editor']");
  const returnInput = editor.locator("[data-role='portfolio-optimizer-objective-menu-view-perp:BTC-return']");
  const btcRow = editor.locator("[data-role='portfolio-optimizer-objective-menu-view-row-perp:BTC']");
  await expect(page.locator("[data-role='portfolio-optimizer-black-litterman-panel']"))
    .toHaveCount(0);
  await expect(returnInput).toHaveValue("7.3");
  await expect(btcRow).toHaveAttribute("data-source", "implied");
});

test("portfolio optimizer inline BTC view edit materializes immediately and runs through the worker result @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.setViewportSize({ width: 900, height: 900 });
  await visitOptimizerNew(page);

  await seedBlackLittermanBtcRunState(page);
  await seedOptimizerAccountValue(page, "1000");

  const editor = page.locator("[data-role='portfolio-optimizer-setup-use-my-views-editor']");
  const btcReturn = editor.locator("[data-role='portfolio-optimizer-objective-menu-view-perp:BTC-return']");
  const btcHigh = editor.locator("[data-role='portfolio-optimizer-objective-menu-view-perp:BTC-confidence-high']");

  // Author the view through the row controls: a parseable keystroke plus a
  // confidence click write the draft view immediately — Run no longer
  // materializes anything from UI buffers.
  await expect(btcReturn).toBeVisible();
  await btcReturn.fill("20");
  await btcHigh.click();
  await expect(btcReturn).toHaveValue("20");
  await expect(btcHigh).toHaveAttribute("data-selected", "true");

  await expect
    .poll(async () => (await readBlackLittermanDraftViews(page)).count, {
      message: "the inline edit should materialize the BTC view before Run is clicked",
      timeout: 4_000
    })
    .toBeGreaterThanOrEqual(1);
  await expect
    .poll(() => readBlackLittermanDraftViews(page), {
      message: "the authored BTC view should carry the intended return and confidence",
      timeout: 4_000
    })
    .toMatchObject({
      firstInstrumentId: "perp:BTC",
      firstReturn: 0.2,
      firstConfidence: 0.75
    });

  await page.locator("[data-role='portfolio-optimizer-run-draft']").click();

  // A run started from /optimize/new always reveals the DRAFT results surface
  // (success-commands navigates to the literal "draft" scenario path) — the
  // seeded draft id identifies the run, not the reveal route.
  await expect(page).toHaveURL(/\/portfolio\/optimize\/draft/, {
    timeout: 15_000
  });

  await expect
    .poll(() => readBlackLittermanRunResult(page), {
      message: "worker-backed BL run should produce positive BTC effective return",
      timeout: 4_000
    })
    .toMatchObject({
      status: "solved",
      returnModel: "black-litterman"
    });

  const result = await readBlackLittermanRunResult(page);
  expect(result.viewCount).toBeGreaterThanOrEqual(1);
  expect(result.expectedBtc).toBeGreaterThan(0);
  expect(result.standaloneBtc).toBeGreaterThan(0);

  await expect(page.locator("[data-role='portfolio-optimizer-view-weights']"))
    .toHaveCount(0);
  await seedOptimizerAccountValue(page, "2000");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-stale-banner']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-recommendation-stale-blocked']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-results-surface']"))
    .toContainText("Allocation");
  await expect(page.locator("[data-role='portfolio-optimizer-frontier-panel']"))
    .toContainText("Efficient Frontier");
});

test("portfolio optimizer setup hides stale retained weights during a view rerun @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.setViewportSize({ width: 900, height: 900 });
  await visitOptimizerNew(page);

  await seedBlackLittermanDirtyRetainedResultState(page);

  await expect(page.locator("[data-role='portfolio-optimizer-last-successful-run']"))
    .toContainText("Retaining last successful result while rerunning.");
  await expect(page.locator("[data-role='portfolio-optimizer-view-weights']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-results-link']"))
    .toHaveCount(0);
});

test("portfolio optimizer max sharpe views blend explainer renders vertical bars across review widths @regression", async ({ page }) => {
  test.setTimeout(90_000);

  for (const viewport of DESIGN_REVIEW_VIEWPORTS) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await visitOptimizerNew(page);

    const { vaultId } = await seedBlackLittermanVaultPreviewState(page);
    const preview = page.locator("[data-role='portfolio-optimizer-black-litterman-preview-panel']");
    const blendShell = page.locator("[data-role='portfolio-optimizer-views-blend-shell']");
    const legend = page.locator("[data-role='portfolio-optimizer-setup-use-my-views-legend']");
    const chartShell = page.locator("[data-role='portfolio-optimizer-setup-use-my-views-chart-shell']");
    const cards = page.locator("[data-role='portfolio-optimizer-setup-use-my-views-insight-cards']");
    const actionBar = page.locator("[data-role='portfolio-optimizer-setup-bottom-actions']");
    const editor = page.locator("[data-role='portfolio-optimizer-setup-use-my-views-editor']");

    // The old center workspace is gone; the trust explainer collapsed into a
    // <details> disclosure on the policy pane whose summary reveals it.
    await expect(page.locator("[data-role='portfolio-optimizer-setup-use-my-views-workspace']"))
      .toHaveCount(0);
    await blendShell.scrollIntoViewIfNeeded();
    await expect(blendShell).toBeVisible();
    await expect(blendShell).toContainText("How your views shape the forecast");
    await expect(legend).toBeHidden();
    await blendShell.locator("summary").click();
    await expect(legend).toBeVisible();
    await preview.scrollIntoViewIfNeeded();

    await expect(editor).toBeVisible();
    await expect(editor).toContainText("Used by Maximum Sharpe");
    await expect(editor.locator("[data-role='portfolio-optimizer-objective-menu-view-vault:0x3333333333333333333333333333333333333333-return']"))
      .toHaveValue("4");
    await expect(page.locator("[data-role='portfolio-optimizer-black-litterman-panel']"))
      .toHaveCount(0);
    await expect(page.locator("[data-role='portfolio-optimizer-setup-summary-heading']")).toHaveCount(0);
    await expect(page.locator("[data-role='portfolio-optimizer-setup-summary-panel']")).toHaveCount(0);
    await expect(legend.locator("[data-role='portfolio-optimizer-setup-use-my-views-legend-market-reference']"))
      .toContainText("Implied baseline");
    await expect(legend.locator("[data-role='portfolio-optimizer-setup-use-my-views-legend-your-view']"))
      .toContainText("Your view");
    await expect(legend.locator("[data-role='portfolio-optimizer-setup-use-my-views-legend-combined-output']"))
      .toContainText("Combined output");
    await expect(chartShell).toBeVisible();
    const marketReferenceCard = cards.locator(
      "[data-role='portfolio-optimizer-setup-use-my-views-card-market-reference']"
    );
    const yourViewsCard = cards.locator(
      "[data-role='portfolio-optimizer-setup-use-my-views-card-your-views']"
    );
    const combinedOutputCard = cards.locator(
      "[data-role='portfolio-optimizer-setup-use-my-views-card-combined-output']"
    );
    await expect(marketReferenceCard).toHaveCount(1);
    await expect(yourViewsCard).toHaveCount(1);
    await expect(combinedOutputCard).toHaveCount(1);
    await expect(marketReferenceCard)
      .toContainText("What the model assumes before your views");
    await expect(marketReferenceCard).toContainText("BTC");
    await expect(marketReferenceCard).toContainText("Alpha Yield");
    await expect(yourViewsCard).toContainText("What you're changing");
    await expect(yourViewsCard).toContainText("1 view active");
    await expect(yourViewsCard).toContainText("Alpha Yield");
    await expect(yourViewsCard).toContainText("+4%");
    await expect(yourViewsCard).toContainText("high");
    await expect(combinedOutputCard)
      .toContainText("How much your views actually matter");
    await expect(combinedOutputCard).toContainText("Alpha Yield");
    await expect(combinedOutputCard).toContainText("→");
    await expect(combinedOutputCard).toContainText("(");
    await expect(actionBar).toBeVisible();
    await expect(actionBar.locator("[data-role='portfolio-optimizer-run-draft']"))
      .toBeVisible();
    await expect(actionBar.locator("[data-role='portfolio-optimizer-save-scenario']"))
      .toBeVisible();
    await expect(preview).toBeVisible();
    await expect(preview).toContainText("Alpha Yield");
    await expect(preview).not.toContainText(vaultId);
    await expect(preview.locator("[data-role='portfolio-optimizer-black-litterman-preview-legend']"))
      .toHaveCount(0);

    const chartGeometry = await preview
      .locator("[data-role='portfolio-optimizer-black-litterman-preview-svg']")
      .evaluate((svg) => {
        const readRect = (role) => {
          const rect = svg.querySelector(`[data-role="${role}"]`);
          return rect
            ? {
                tagName: rect.tagName.toLowerCase(),
                x: Number(rect.getAttribute("x")),
                y: Number(rect.getAttribute("y")),
                width: Number(rect.getAttribute("width")),
                height: Number(rect.getAttribute("height"))
              }
            : null;
        };

        return {
          prior: readRect("portfolio-optimizer-black-litterman-preview-bar-prior-perp:BTC"),
          posterior: readRect("portfolio-optimizer-black-litterman-preview-bar-posterior-perp:BTC"),
          legendTransform: svg
            .querySelector("[data-role='portfolio-optimizer-black-litterman-preview-legend']")
            ?.getAttribute("transform"),
          legendItemTransforms: Array.from(
            svg.querySelectorAll("[data-role='portfolio-optimizer-black-litterman-preview-legend'] > g")
          ).map((legendItem) => legendItem.getAttribute("transform")),
          horizontalOverflow: document.documentElement.scrollWidth - window.innerWidth
        };
      });

    expect(chartGeometry.prior?.tagName, `${viewport.id} prior bar`).toBe("rect");
    expect(chartGeometry.posterior?.tagName, `${viewport.id} posterior bar`).toBe("rect");
    expect(chartGeometry.prior.height, `${viewport.id} prior bar height`).toBeGreaterThan(0);
    expect(chartGeometry.posterior.height, `${viewport.id} posterior bar height`).toBeGreaterThan(0);
    expect(chartGeometry.prior.width, `${viewport.id} grouped bar width`)
      .toBe(chartGeometry.posterior.width);
    expect(chartGeometry.prior.x, `${viewport.id} grouped bar x separation`)
      .not.toBe(chartGeometry.posterior.x);
    expect(chartGeometry.legendTransform, `${viewport.id} legend position`)
      .toBeUndefined();
    expect(chartGeometry.legendItemTransforms, `${viewport.id} legend columns`)
      .toEqual([]);
    expect(chartGeometry.horizontalOverflow, `${viewport.id} horizontal overflow`).toBeLessThanOrEqual(1);
  }
});
