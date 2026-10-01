import { expect, test } from "../support/guarded_test.mjs";
import { dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";
import {
  keyword as optimizerKeyword,
  optimizerPath,
  optimizerUiPath,
  readOptimizerState,
  seedOptimizerState,
  seedPatch,
  seedOptimizerMarkets,
  stringMap
} from "../support/optimizer_state.mjs";

async function seedRetainedDraftScenario(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
    const vector = (items) => c.PersistentVector.fromArray(items, true);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);

    const btcInstrument = map([
      kw("instrument-id"), "perp:BTC",
      kw("market-type"), kw("perp"),
      kw("coin"), "BTC",
      kw("symbol"), "BTC-USDC",
      kw("name"), "Bitcoin",
      kw("position-side"), kw("long")
    ]);
    const draft = map([
      kw("id"), "draft-current",
      kw("name"), "Retained Draft Smoke",
      kw("universe"), vector([btcInstrument]),
      kw("objective"), map([kw("kind"), kw("max-sharpe")]),
      kw("return-model"), map([kw("kind"), kw("historical-mean")]),
      kw("risk-model"), map([kw("kind"), kw("sample-covariance")]),
      kw("constraints"), map([
        kw("long-only?"), true,
        kw("max-asset-weight"), 1,
        kw("gross-max"), 1
      ]),
      kw("metadata"), map([kw("dirty?"), false])
    ]);
    const result = map([
      kw("status"), kw("solved"),
      kw("scenario-id"), "draft",
      kw("instrument-ids"), vector(["perp:BTC"]),
      kw("target-weights"), vector([1]),
      kw("current-weights"), vector([0.25]),
      kw("target-weights-by-instrument"), map(["perp:BTC", 1]),
      kw("current-weights-by-instrument"), map(["perp:BTC", 0.25]),
      kw("labels-by-instrument"), map(["perp:BTC", "BTC"]),
      kw("current-expected-return"), 0.03,
      kw("current-volatility"), 0.1,
      kw("expected-return"), 0.12,
      kw("volatility"), 0.2,
      kw("return-model"), kw("historical-mean"),
      kw("risk-model"), kw("sample-covariance"),
      kw("as-of-ms"), 1777046100000,
      kw("frontier"), vector([
        map([
          kw("id"), 0,
          kw("expected-return"), 0.12,
          kw("volatility"), 0.2,
          kw("sharpe"), 0.6
        ])
      ]),
      kw("frontier-overlays"), map([]),
      kw("current-performance"), map([
        kw("in-sample-sharpe"), 0.3,
        kw("shrunk-sharpe"), 0.15
      ]),
      kw("performance"), map([
        kw("in-sample-sharpe"), 0.58,
        kw("shrunk-sharpe"), 0.29
      ]),
      kw("diagnostics"), map([
        kw("gross-exposure"), 1,
        kw("net-exposure"), 1,
        kw("effective-n"), 1,
        kw("turnover"), 0.75,
        kw("binding-constraints"), vector([]),
        kw("covariance-conditioning"), map([kw("status"), kw("ok")])
      ]),
      kw("rebalance-preview"), map([
        kw("status"), kw("ready"),
        kw("capital-usd"), 10000,
        kw("summary"), map([
          kw("ready-count"), 1,
          kw("blocked-count"), 0,
          kw("gross-trade-notional-usd"), 7500,
          kw("estimated-fees-usd"), 2.5,
          kw("estimated-slippage-usd"), 8,
          kw("margin"), map([kw("after-utilization"), 0.2])
        ]),
        kw("rows"), vector([])
      ])
    ]);
    const store = globalThis.hyperopen.system.store;
    let state = c.deref(store);
    state = c.assoc_in(state, path("portfolio", "optimizer", "draft"), draft);
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "history-data"),
      map([
        kw("candle-history-by-coin"), map([
          "BTC", vector([
            map([kw("time-ms"), 1000, kw("close"), "100"]),
            map([kw("time-ms"), 2000, kw("close"), "102"]),
            map([kw("time-ms"), 3000, kw("close"), "105"]),
            map([kw("time-ms"), 4000, kw("close"), "109"])
          ])
        ]),
        kw("funding-history-by-coin"), map([
          "BTC", vector([map([kw("time-ms"), 1000, kw("funding-rate-raw"), 0])])
        ])
      ])
    );
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "runtime"),
      map([kw("as-of-ms"), 5000, kw("stale-after-ms"), 60000])
    );
    const readiness = hyperopen.portfolio.optimizer.application.setup_readiness.build_readiness(state);
    const request = c.get(readiness, kw("request"));
    const requestSignature =
      hyperopen.portfolio.optimizer.contracts.signatures.build_request_signature(request);
    const lastSuccessfulRun = map([
      kw("request-signature"), requestSignature,
      kw("computed-at-ms"), 1777046100000,
      kw("result"), result
    ]);
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "active-scenario"),
      map([kw("loaded-id"), null, kw("status"), kw("computed")])
    );
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "last-successful-run"),
      lastSuccessfulRun
    );
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "run-state"),
      map([kw("status"), kw("succeeded"), kw("request-signature"), requestSignature])
    );
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "scenario-load-state"),
      map([
        kw("status"), kw("loaded"),
        kw("scenario-id"), "draft",
        kw("started-at-ms"), 1777046099000,
        kw("completed-at-ms"), 1777046100000
      ])
    );
    state = c.assoc_in(
      state,
      path("portfolio-ui", "optimizer", "results-tab"),
      kw("recommendation")
    );
    c.reset_BANG_(store, state);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function seedTwoAssetDraftScenario(page) {
  await seedRetainedDraftScenario(page);
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
    const vector = (items) => c.PersistentVector.fromArray(items, true);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);

    const btcInstrument = map([
      kw("instrument-id"), "hl:perp:BTC",
      kw("market-type"), kw("perp"),
      kw("coin"), "BTC",
      kw("symbol"), "BTC-USDC",
      kw("name"), "Bitcoin",
      kw("position-side"), kw("long")
    ]);
    const ethInstrument = map([
      kw("instrument-id"), "hl:perp:ETH",
      kw("market-type"), kw("perp"),
      kw("coin"), "ETH",
      kw("symbol"), "ETH-USDC",
      kw("name"), "Ethereum",
      kw("position-side"), kw("long")
    ]);
    const draft = map([
      kw("id"), "draft-current",
      kw("name"), "Two Asset Draft Smoke",
      kw("universe"), vector([btcInstrument, ethInstrument]),
      kw("objective"), map([kw("kind"), kw("max-sharpe")]),
      kw("return-model"), map([kw("kind"), kw("historical-mean")]),
      kw("risk-model"), map([kw("kind"), kw("sample-covariance")]),
      kw("constraints"), map([
        kw("long-only?"), true,
        kw("max-asset-weight"), 1,
        kw("gross-max"), 1,
        kw("blocklist"), vector([])
      ]),
      kw("execution-assumptions"), map([
        kw("manual-capital-usdc"), 10000,
        kw("fallback-slippage-bps"), 20,
        kw("prices-by-id"), map(["perp:BTC", 100000, "perp:ETH", 5000, "perp:HYPE", 54]),
        kw("fee-bps-by-id"), map(["perp:BTC", 4, "perp:ETH", 4, "perp:HYPE", 4])
      ]),
      kw("metadata"), map([kw("dirty?"), false])
    ]);
    const result = map([
      kw("status"), kw("solved"),
      kw("scenario-id"), "draft",
      kw("instrument-ids"), vector(["perp:BTC", "perp:ETH"]),
      kw("target-weights"), vector([0.5, 0.5]),
      kw("current-weights"), vector([0.25, 0.15]),
      kw("target-weights-by-instrument"), map(["perp:BTC", 0.5, "perp:ETH", 0.5]),
      kw("current-weights-by-instrument"), map(["perp:BTC", 0.25, "perp:ETH", 0.15]),
      kw("labels-by-instrument"), map(["perp:BTC", "BTC", "perp:ETH", "ETH"]),
      kw("expected-returns-by-instrument"), map([
        "hl:perp:BTC", 0.195,
        "hl:perp:ETH", 0.165
      ]),
      kw("expected-return"), 0.12,
      kw("volatility"), 0.2,
      kw("return-model"), kw("historical-mean"),
      kw("risk-model"), kw("sample-covariance"),
      kw("as-of-ms"), 1777046100000,
      kw("frontier"), vector([
        map([kw("id"), 0, kw("expected-return"), 0.1, kw("volatility"), 0.18, kw("sharpe"), 0.55]),
        map([kw("id"), 1, kw("expected-return"), 0.12, kw("volatility"), 0.2, kw("sharpe"), 0.6])
      ]),
      kw("frontier-overlays"), map([]),
      kw("performance"), map([
        kw("in-sample-sharpe"), 0.58,
        kw("shrunk-sharpe"), 0.6
      ]),
      kw("diagnostics"), map([
        kw("gross-exposure"), 1,
        kw("net-exposure"), 1,
        kw("effective-n"), 2,
        kw("turnover"), 0.6,
        kw("binding-constraints"), vector([]),
        kw("covariance-conditioning"), map([kw("status"), kw("ok")])
      ]),
      kw("rebalance-preview"), map([
        kw("status"), kw("ready"),
        kw("capital-usd"), 10000,
        kw("summary"), map([
          kw("ready-count"), 2,
          kw("blocked-count"), 0,
          kw("gross-trade-notional-usd"), 6000,
          kw("estimated-fees-usd"), 2.5,
          kw("estimated-slippage-usd"), 8,
          kw("margin"), map([kw("after-utilization"), 0.2])
        ]),
        kw("rows"), vector([])
      ])
    ]);
    const store = globalThis.hyperopen.system.store;
    let state = c.deref(store);
    state = c.assoc_in(state, path("portfolio", "optimizer", "draft"), draft);
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "history-data"),
      map([
        kw("candle-history-by-coin"), map([
          "BTC", vector([
            map([kw("time-ms"), 1000, kw("close"), "100"]),
            map([kw("time-ms"), 2000, kw("close"), "102"]),
            map([kw("time-ms"), 3000, kw("close"), "105"]),
            map([kw("time-ms"), 4000, kw("close"), "109"])
          ]),
          "ETH", vector([
            map([kw("time-ms"), 1000, kw("close"), "100"]),
            map([kw("time-ms"), 2000, kw("close"), "101"]),
            map([kw("time-ms"), 3000, kw("close"), "100"]),
            map([kw("time-ms"), 4000, kw("close"), "102"])
          ]),
          "HYPE", vector([
            map([kw("time-ms"), 1000, kw("close"), "44"]),
            map([kw("time-ms"), 2000, kw("close"), "46"]),
            map([kw("time-ms"), 3000, kw("close"), "51"]),
            map([kw("time-ms"), 4000, kw("close"), "54"])
          ])
        ]),
        kw("funding-history-by-coin"), map([
          "BTC", vector([map([kw("time-ms"), 1000, kw("funding-rate-raw"), 0])]),
          "ETH", vector([map([kw("time-ms"), 1000, kw("funding-rate-raw"), 0])]),
          "HYPE", vector([map([kw("time-ms"), 1000, kw("funding-rate-raw"), 0])])
        ])
      ])
    );
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "runtime"),
      map([kw("as-of-ms"), 5000, kw("stale-after-ms"), 60000])
    );
    const readiness = hyperopen.portfolio.optimizer.application.setup_readiness.build_readiness(state);
    const request = c.get(readiness, kw("request"));
    const requestSignature =
      hyperopen.portfolio.optimizer.contracts.signatures.build_request_signature(request);
    const lastSuccessfulRun = map([
      kw("request-signature"), requestSignature,
      kw("computed-at-ms"), 1777046100000,
      kw("result"), result
    ]);
    state = c.assoc_in(state, path("portfolio", "optimizer", "last-successful-run"), lastSuccessfulRun);
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "run-state"),
      map([kw("status"), kw("succeeded"), kw("request-signature"), requestSignature])
    );
    state = c.assoc_in(
      state,
      path("portfolio-ui", "optimizer", "results-tab"),
      kw("recommendation")
    );
    c.reset_BANG_(store, state);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

async function stabilizeSeededOptimizerRun(page) {
  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const store = globalThis.hyperopen.system.store;
    const readiness =
      globalThis.hyperopen.portfolio.optimizer.application.setup_readiness.build_readiness(
        c.deref(store)
      );
    const request = c.get(readiness, kw("request"));

    if (!request) {
      return;
    }

    const signatures = globalThis.hyperopen.portfolio.optimizer.contracts.signatures;
    const requestSignature = signatures.build_request_signature(request);
    const inputSignature = signatures.optimizer_input_signature(request);
    const scenarioId = c.get(request, kw("scenario-id"));
    let state = c.deref(store);

    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "last-successful-run", "request-signature"),
      requestSignature
    );
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "run-state"),
      map([
        kw("status"), kw("succeeded"),
        kw("request-signature"), requestSignature
      ])
    );
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "optimization-progress"),
      map([kw("status"), kw("idle")])
    );
    state = c.assoc_in(
      state,
      path("portfolio-ui", "optimizer", "stale-auto-recompute"),
      map([
        kw("request-signature"), requestSignature,
        kw("input-signature"), inputSignature,
        kw("scenario-id"), scenarioId
      ])
    );
    c.reset_BANG_(store, state);
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

test("portfolio optimizer setup and retained draft detail routes render through view models @smoke @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.setViewportSize({ width: 1280, height: 900 });
  await visitRoute(page, "/portfolio/optimize/new", {
    routeModuleTimeoutMs: 30_000,
    idleOptions: { quietMs: 400, timeoutMs: 8_000, pollMs: 50 }
  });

  const setup = page.locator("[data-role='portfolio-optimizer-setup-route-surface']");
  await expect(setup).toBeVisible({ timeout: 30_000 });
  await expect(page.locator("[data-role='portfolio-optimizer-universe-search-input']"))
    .toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-run-draft']"))
    .toBeDisabled();

  await seedRetainedDraftScenario(page);
  await dispatch(page, [
    ":actions/navigate",
    "/portfolio/optimize/draft",
    { "replace?": true }
  ]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });

  const detail = page.locator("[data-role='portfolio-optimizer-scenario-detail-surface']");
  await expect(page).toHaveURL(/\/portfolio\/optimize\/draft/);
  await expect(detail).toBeVisible();
  await expect(detail).toHaveAttribute("data-scenario-id", "draft");
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-header']"))
    .toContainText("Retained Draft Smoke");
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-loading-state']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-tabs']"))
    .toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-kpi-strip']"))
    .toContainText("Expected Return");
  const sharpeKpi = page.locator("[data-role='portfolio-optimizer-scenario-kpi-sharpe']");
  await expect(sharpeKpi).toContainText("Sharpe · current → target");
  await expect(sharpeKpi).toContainText("0.3");
  await expect(sharpeKpi).toContainText("0.58");
  await expect(sharpeKpi).toContainText("+0.28 · raw Sharpe change");
  await expect(sharpeKpi).not.toContainText("0.29");

  await page.evaluate(() => {
    const c = globalThis.cljs.core;
    const kw = (name) => c.keyword(name);
    const path = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
    const store = globalThis.hyperopen.system.store;
    c.swap_BANG_(
      store,
      (state) => c.assoc_in(
        state,
        path("portfolio", "optimizer", "draft", "metadata", "dirty?"),
        true
      )
    );
  });
  await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });
  await expect(page.locator("[data-role='portfolio-optimizer-results-surface']"))
    .toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-rerun-stale']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-rerun-stale-result']"))
    .toHaveCount(0);
  await expect(page.locator("body"))
    .not.toContainText("Recompute");
  await expect(page.locator("[data-role='portfolio-optimizer-recommendation-stale-blocked']"))
    .toHaveCount(0);

  const navigationEntriesBeforeExecutionClick = await page.evaluate(() =>
    performance.getEntriesByType("navigation").length
  );

  // The standalone Rebalance preview tab was retired — the rebalance is staged straight
  // into Execution. Entering Execution must switch the tab in place (no full navigation)
  // and render the execution surface from the view model.
  await page.locator("[data-role='portfolio-optimizer-scenario-tab-execution']").click();

  const executionRoute = await page.evaluate(() => ({
    pathname: window.location.pathname,
    selectedTab: new URL(window.location.href).searchParams.get("otab")
  }));
  expect(executionRoute).toEqual({
    pathname: "/portfolio/optimize/draft",
    selectedTab: "execution"
  });
  await expect(page.locator("[data-role='portfolio-optimizer-execution-tab-shell']"))
    .toBeVisible();
  await expect.poll(async () => {
    const result = await readOptimizerState(page, [
      "portfolio",
      "optimizer",
      "last-successful-run",
      "result"
    ]);
    return result?.status;
  }).toBe("solved");

  const navigationEntriesAfterExecutionClick = await page.evaluate(() =>
    performance.getEntriesByType("navigation").length
  );
  expect(navigationEntriesAfterExecutionClick).toBe(navigationEntriesBeforeExecutionClick);
});

test("portfolio optimizer setup explains rejected solver output @smoke @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.setViewportSize({ width: 1280, height: 900 });
  await visitRoute(page, "/portfolio/optimize/new", {
    routeModuleTimeoutMs: 30_000,
    idleOptions: { quietMs: 400, timeoutMs: 8_000, pollMs: 50 }
  });

  await seedOptimizerState(page, [
    seedPatch(optimizerPath("draft"), {
      id: "draft-current",
      universe: [
        {
          "instrument-id": "perp:BTC",
          "market-type": optimizerKeyword("perp"),
          coin: "BTC",
          symbol: "BTC-USDC",
          name: "Bitcoin"
        },
        {
          "instrument-id": "perp:ETH",
          "market-type": optimizerKeyword("perp"),
          coin: "ETH",
          symbol: "ETH-USDC",
          name: "Ethereum"
        }
      ],
      objective: { kind: optimizerKeyword("minimum-variance") },
      constraints: {
        "long-only?": true,
        "max-asset-weight": 0.5,
        "gross-max": 1,
        "net-min": 1,
        "net-max": 1,
        "max-turnover": 2
      }
    }),
    seedPatch(optimizerPath("run-state"), {
      status: optimizerKeyword("infeasible"),
      "completed-at-ms": 1777046100000,
      result: {
        status: optimizerKeyword("infeasible"),
        reason: optimizerKeyword("solver-returned-invalid-solution"),
        message: "The solver reported a solution, but it violated optimizer constraints.",
        details: {
          violations: [
            {
              code: optimizerKeyword("solver-result-equality-violation"),
              "constraint-code": optimizerKeyword("net-exposure"),
              message: "net-exposure expected 1.0000 but solver returned 0.0000."
            },
            {
              code: optimizerKeyword("solver-result-turnover-violation"),
              "constraint-code": optimizerKeyword("turnover"),
              message: "turnover limit 2.0000 but solver returned 31.3133."
            },
            {
              code: optimizerKeyword("solver-result-equality-violation"),
              "constraint-code": optimizerKeyword("net-exposure"),
              message: "net-exposure expected 1.0000 but solver returned 0.0000."
            },
            {
              code: optimizerKeyword("solver-result-turnover-violation"),
              "constraint-code": optimizerKeyword("turnover"),
              message: "turnover limit 2.0000 but solver returned 31.3133."
            }
          ]
        }
      }
    })
  ]);

  const banner = page.locator("[data-role='portfolio-optimizer-infeasible-banner']");
  await expect(banner).toBeVisible();
  await expect(banner)
    .toContainText("The solver reported a solution, but it violated optimizer constraints.");
  await expect(banner)
    .toContainText("net-exposure expected 1.0000 but solver returned 0.0000.");
  await expect(banner)
    .toContainText("turnover limit 2.0000 but solver returned 31.3133.");
  await expect(banner.locator("span", { hasText: "solver-result-equality-violation" }))
    .toHaveCount(1);
  await expect(banner.locator("span", { hasText: "solver-result-turnover-violation" }))
    .toHaveCount(1);
  await expect(page.locator("[data-role='portfolio-optimizer-constraint-max-turnover-input']"))
    .toHaveAttribute("data-infeasible", "true");
  await expect(page.locator("[data-role='portfolio-optimizer-constraint-net-min-input']"))
    .toHaveAttribute("data-infeasible", "true");
  await expect(page.locator("[data-role='portfolio-optimizer-constraint-net-max-input']"))
    .toHaveAttribute("data-infeasible", "true");
});

for (const viewport of [
  { label: "mobile", width: 375, height: 900 },
  { label: "tablet", width: 768, height: 900 },
  { label: "desktop", width: 1280, height: 900 },
  { label: "wide", width: 1440, height: 900 }
]) {
  test(`portfolio optimizer setup explains infeasible net-min capacity presolve at ${viewport.label} @smoke @regression`, async ({ page }) => {
    test.setTimeout(90_000);

    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await visitRoute(page, "/portfolio/optimize/new", {
      routeModuleTimeoutMs: 30_000,
      idleOptions: { quietMs: 400, timeoutMs: 8_000, pollMs: 50 }
    });

    await seedOptimizerState(page, [
      seedPatch(optimizerPath("draft"), {
        id: "draft-current",
        universe: [
          {
            "instrument-id": "perp:BTC",
            "market-type": optimizerKeyword("perp"),
            coin: "BTC",
            symbol: "BTC-USDC",
            name: "Bitcoin"
          },
          {
            "instrument-id": "perp:ETH",
            "market-type": optimizerKeyword("perp"),
            coin: "ETH",
            symbol: "ETH-USDC",
            name: "Ethereum"
          }
        ],
        objective: { kind: optimizerKeyword("minimum-variance") },
        constraints: {
          "long-only?": false,
          "max-asset-weight": 1,
          "gross-max": 100,
          "net-min": 5,
          "net-max": 50
        }
      }),
      seedPatch(optimizerPath("run-state"), {
        status: optimizerKeyword("infeasible"),
        "completed-at-ms": 1777046100000,
        result: {
          status: optimizerKeyword("infeasible"),
          reason: optimizerKeyword("constraint-presolve"),
          details: {
            violations: [
              {
                code: optimizerKeyword("sum-upper-below-net-min"),
                "sum-upper": 2,
                "net-min": 5
              }
            ]
          }
        }
      })
    ]);

    const banner = page.locator("[data-role='portfolio-optimizer-infeasible-banner']");
    await expect(banner).toBeVisible();
    await expect(banner)
      .toContainText("Maximum possible net exposure is 2, below the minimum of 5.");
    await expect(banner)
      .toContainText("Lower Net Exposure Min, add eligible long assets, or raise Max Asset Weight.");
    await expect(banner.locator("span", { hasText: "sum-upper-below-net-min" }))
      .toHaveCount(1);
    await expect(page.locator("[data-role='portfolio-optimizer-constraint-max-asset-weight-input']"))
      .toHaveAttribute("data-infeasible", "true");
    await expect(page.locator("[data-role='portfolio-optimizer-constraint-max-asset-weight-input']"))
      .toHaveAttribute("aria-invalid", "true");
    await expect(page.locator("[data-role='portfolio-optimizer-constraint-net-min-input']"))
      .toHaveAttribute("data-infeasible", "true");
    await expect(page.locator("[data-role='portfolio-optimizer-constraint-net-min-input']"))
      .toHaveAttribute("aria-invalid", "true");
    await expect(page.locator("[data-role='portfolio-optimizer-constraint-net-max-input']"))
      .not.toHaveAttribute("data-infeasible", "true");
    await expect(page.locator("[data-role='portfolio-optimizer-constraint-net-max-input']"))
      .not.toHaveAttribute("aria-invalid", "true");
  });
}

test("portfolio optimizer draft results render namespaced market icons on frontier and exposure rows @smoke @regression", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await visitRoute(page, "/portfolio/optimize/new", {
    routeModuleTimeoutMs: 30_000,
    idleOptions: { quietMs: 400, timeoutMs: 8_000, pollMs: 50 }
  });

  const draft = {
    id: "draft-current",
    name: "Namespaced Icon Draft",
    universe: [
      {
        "instrument-id": "hl:hip3:xyz:GOLD",
        "market-type": optimizerKeyword("perp"),
        coin: "hl:hip3:xyz:GOLD",
        symbol: "GOLD",
        base: "GOLD"
      },
      {
        "instrument-id": "hl:hip3:xyz:AAPL",
        "market-type": optimizerKeyword("perp"),
        coin: "hl:hip3:xyz:AAPL",
        symbol: "AAPL",
        base: "AAPL"
      },
      {
        "instrument-id": "hl:hip3:xyz:SILVER",
        "market-type": optimizerKeyword("perp"),
        coin: "hl:hip3:xyz:SILVER",
        symbol: "SILVER",
        base: "SILVER"
      }
    ],
    objective: { kind: optimizerKeyword("max-sharpe") },
    "return-model": { kind: optimizerKeyword("historical-mean") },
    "risk-model": { kind: optimizerKeyword("sample-covariance") },
    constraints: { "long-only?": true, "max-asset-weight": 1, "gross-max": 1 },
    metadata: { "dirty?": false }
  };
  const result = {
    status: optimizerKeyword("solved"),
    "scenario-id": "draft",
    "instrument-ids": ["hl:hip3:xyz:GOLD", "hl:hip3:xyz:AAPL", "hl:hip3:xyz:SILVER"],
    "target-weights": [0.25, 0.5, 0.25],
    "current-weights": [0.4, 0.3, 0.3],
    "target-weights-by-instrument": stringMap([
      ["hl:hip3:xyz:GOLD", 0.25],
      ["hl:hip3:xyz:AAPL", 0.5],
      ["hl:hip3:xyz:SILVER", 0.25]
    ]),
    "current-weights-by-instrument": stringMap([
      ["hl:hip3:xyz:GOLD", 0.4],
      ["hl:hip3:xyz:AAPL", 0.3],
      ["hl:hip3:xyz:SILVER", 0.3]
    ]),
    "labels-by-instrument": stringMap([
      ["hl:hip3:xyz:GOLD", "GOLD"],
      ["hl:hip3:xyz:AAPL", "AAPL"],
      ["hl:hip3:xyz:SILVER", "SILVER"]
    ]),
    "current-expected-return": -1.0293,
    "current-volatility": 6.1769,
    "expected-return": 0.1873,
    volatility: 0.4921,
    "return-model": optimizerKeyword("historical-mean"),
    "risk-model": optimizerKeyword("sample-covariance"),
    "as-of-ms": 1777046100000,
    frontier: [
      {
        id: 0,
        "expected-return": 0.16,
        volatility: 0.42,
        sharpe: 0.38
      }
    ],
    "frontier-overlays": {
      standalone: [
        {
          "instrument-id": "hl:hip3:xyz:GOLD",
          label: "GOLD",
          "target-weight": 0.25,
          "expected-return": 0.18,
          volatility: 0.36
        },
        {
          "instrument-id": "hl:hip3:xyz:AAPL",
          label: "AAPL",
          "target-weight": 0.5,
          "expected-return": 0.2,
          volatility: 0.49
        }
      ],
      contribution: [
        {
          "instrument-id": "hl:hip3:xyz:AAPL",
          label: "AAPL",
          "target-weight": 0.5,
          "expected-return": 0.1,
          volatility: 0.24
        }
      ]
    },
    "current-performance": {
      "in-sample-sharpe": -0.16,
      "shrunk-sharpe": -0.08
    },
    performance: {
      "in-sample-sharpe": 0.38,
      "shrunk-sharpe": 0.19
    },
    diagnostics: {
      "gross-exposure": 1,
      "net-exposure": 1,
      "effective-n": 2.6,
      turnover: 0.45,
      "binding-constraints": [],
      "covariance-conditioning": { status: optimizerKeyword("ok") }
    },
    "rebalance-preview": {
      status: optimizerKeyword("ready"),
      "capital-usd": 10000,
      summary: {
        "ready-count": 3,
        "blocked-count": 0,
        "gross-trade-notional-usd": 4500,
        "estimated-fees-usd": 2.5,
        "estimated-slippage-usd": 8
      },
      rows: []
    }
  };
  const requestSignature = { seed: "namespaced-icon-smoke" };
  await seedOptimizerState(page, [
    seedPatch(optimizerPath("draft"), draft),
    seedPatch(optimizerPath("active-scenario"), {
      "loaded-id": null,
      status: optimizerKeyword("computed")
    }),
    seedPatch(optimizerPath("last-successful-run"), {
      "request-signature": requestSignature,
      "computed-at-ms": 1777046100000,
      result
    }),
    seedPatch(optimizerPath("run-state"), {
      status: optimizerKeyword("succeeded"),
      "request-signature": requestSignature
    }),
    seedPatch(optimizerPath("scenario-load-state"), {
      status: optimizerKeyword("loaded"),
      "scenario-id": "draft",
      "started-at-ms": 1777046099000,
      "completed-at-ms": 1777046100000
    }),
    seedPatch(optimizerUiPath("results-tab"), optimizerKeyword("recommendation"))
  ]);
  await stabilizeSeededOptimizerRun(page);
  await dispatch(page, [
    ":actions/navigate",
    "/portfolio/optimize/draft",
    { "replace?": true }
  ]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });

  for (const width of [375, 768, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
    await expect(page.locator("[data-role='portfolio-optimizer-results-surface']"))
      .toBeVisible();
    await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-asset-icon-img-GOLD']"))
      .toHaveAttribute("src", "https://app.hyperliquid.xyz/coins/xyz:GOLD.svg");
    await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-asset-icon-img-AAPL']"))
      .toHaveAttribute("src", "https://app.hyperliquid.xyz/coins/xyz:AAPL.svg");
    await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-asset-icon-img-SILVER']"))
      .toHaveAttribute("src", "https://app.hyperliquid.xyz/coins/xyz:SILVER.svg");
    const targetExposureOrder = await page
      .locator("[data-role='portfolio-optimizer-target-exposure-table']")
      .evaluate((table) =>
        [...table.querySelectorAll("[data-role]")]
          .map((node) => node.getAttribute("data-role"))
      );
    expect(targetExposureOrder.indexOf("portfolio-optimizer-target-exposure-change-header"))
      .toBeLessThan(targetExposureOrder.indexOf("portfolio-optimizer-target-exposure-delta-bar-header"));
    expect(targetExposureOrder.indexOf("portfolio-optimizer-target-exposure-change-AAPL"))
      .toBeLessThan(targetExposureOrder.indexOf("portfolio-optimizer-target-exposure-delta-bar-AAPL"));
    await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-change-AAPL']"))
      .toContainText("+20.0%");
    await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-change-AAPL']"))
      .toHaveCSS("color", "rgb(78, 166, 116)");
    await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-change-GOLD']"))
      .toContainText("-15.0%");
    await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-change-GOLD']"))
      .toHaveCSS("color", "rgb(194, 91, 91)");
    await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-delta-bar-AAPL']"))
      .toHaveAttribute("data-direction", "positive");
    await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-delta-bar-GOLD']"))
      .toHaveAttribute("data-direction", "negative");
    await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-delta-bar-AAPL']"))
      .toHaveCSS("--optimizer-delta-bar-size", "100%");
    await expect(page.locator("[data-role='portfolio-optimizer-target-exposure-delta-bar-GOLD']"))
      .toHaveCSS("--optimizer-delta-bar-size", "75%");
    const standaloneGoldMarker = page.locator(
      "[data-role='portfolio-optimizer-frontier-overlay-symbol-standalone-hl:hip3:xyz:GOLD']"
    );
    await expect(standaloneGoldMarker.locator("image"))
      .toHaveAttribute("href", "https://app.hyperliquid.xyz/coins/xyz:GOLD.svg");
    await expect(standaloneGoldMarker.locator("image"))
      .toHaveAttribute(
        "clip-path",
        "url(#portfolio-optimizer-frontier-overlay-symbol-standalone-hl-hip3-xyz-GOLD-clip)"
      );
  }

  await page.setViewportSize({ width: 1280, height: 900 });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.locator("[data-role='portfolio-optimizer-frontier-overlay-mode-contribution']").click();
  for (const width of [375, 768, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
    const contributionAaplMarker = page.locator(
      "[data-role='portfolio-optimizer-frontier-overlay-symbol-contribution-hl:hip3:xyz:AAPL']"
    );
    await expect(contributionAaplMarker.locator("image"))
      .toHaveAttribute("href", "https://app.hyperliquid.xyz/coins/xyz:AAPL.svg");
    await expect(contributionAaplMarker.locator("image"))
      .toHaveAttribute(
        "clip-path",
        "url(#portfolio-optimizer-frontier-overlay-symbol-contribution-hl-hip3-xyz-AAPL-clip)"
      );
  }
});

test("portfolio optimizer draft allocation add asset selector updates draft and starts recompute @smoke @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.setViewportSize({ width: 1280, height: 900 });
  await visitRoute(page, "/portfolio/optimize/new", {
    routeModuleTimeoutMs: 30_000,
    idleOptions: { quietMs: 400, timeoutMs: 8_000, pollMs: 50 }
  });

  await seedTwoAssetDraftScenario(page);
  await seedOptimizerMarkets(page, [
    {
      key: "perp:BTC",
      "market-type": optimizerKeyword("perp"),
      coin: "BTC",
      symbol: "BTC-USDC",
      base: "BTC",
      quote: "USDC",
      volume24h: 96000000
    },
    {
      key: "perp:ETH",
      "market-type": optimizerKeyword("perp"),
      coin: "ETH",
      symbol: "ETH-USDC",
      base: "ETH",
      quote: "USDC",
      volume24h: 84000000
    },
    {
      key: "perp:HYPE",
      "market-type": optimizerKeyword("perp"),
      coin: "HYPE",
      symbol: "HYPE-USDC",
      base: "HYPE",
      quote: "USDC",
      volume24h: 774000000
    },
    {
      key: "perp:SOL",
      "market-type": optimizerKeyword("perp"),
      coin: "SOL",
      symbol: "SOL-USDC",
      base: "SOL",
      quote: "USDC",
      volume24h: 62000000
    }
  ]);
  await dispatch(page, [
    ":actions/navigate",
    "/portfolio/optimize/draft",
    { "replace?": true }
  ]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });

  await expect(page.locator("[data-role='portfolio-optimizer-results-surface']"))
    .toBeVisible();

  const addAsset = page.locator("[data-role='portfolio-optimizer-draft-add-asset']");
  const popover = page.locator("[data-role='portfolio-optimizer-draft-add-asset-popover']");
  const searchInput = page.locator("[data-role='portfolio-optimizer-draft-add-asset-search-input']");
  const searchHint = page.locator("[data-role='portfolio-optimizer-draft-add-asset-search-add-hint']");
  const searchClear = page.locator("[data-role='portfolio-optimizer-draft-add-asset-search-clear']");
  const searchResults = page.locator("[data-role='portfolio-optimizer-draft-add-asset-search-results']");
  const hypeRow = page.locator("[data-role='portfolio-optimizer-draft-add-asset-candidate-row-perp:HYPE']");
  const candidateRows = page.locator(
    "[data-role^='portfolio-optimizer-draft-add-asset-candidate-row-']"
  );
  const activeCandidate = page.locator(
    "[data-role^='portfolio-optimizer-draft-add-asset-candidate-row-'][data-active='true']"
  );

  await addAsset.click();
  await expect(popover).toBeVisible();
  await expect(searchInput).toBeVisible();
  await expect(searchHint).toHaveCount(0);
  await expect(popover).toHaveCSS("background-color", "rgb(14, 16, 19)");
  await expect(popover).toHaveCSS("overflow-y", "hidden");
  await expect(searchInput).toHaveCSS("box-shadow", "none");
  await searchInput.fill("hype");
  await expect(searchClear).toHaveCount(0);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(hypeRow).toBeVisible();
  await expect(searchResults).toHaveCSS("scrollbar-width", "none");
  await expect(activeCandidate).toHaveCount(1);
  const firstActiveRole = await activeCandidate.getAttribute("data-role");
  expect(firstActiveRole).toEqual("portfolio-optimizer-draft-add-asset-candidate-row-perp:HYPE");
  const candidateCount = await candidateRows.count();
  if (candidateCount > 1) {
    await searchInput.press("ArrowDown");
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
    await expect(activeCandidate).toHaveCount(1);
    const secondActiveRole = await activeCandidate.getAttribute("data-role");
    expect(secondActiveRole).not.toEqual(firstActiveRole);
    await expect(activeCandidate).toHaveCSS("background-color", "rgba(212, 181, 88, 0.12)");
    await searchInput.press("ArrowUp");
    await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
    await expect(activeCandidate).toHaveAttribute(
      "data-role",
      firstActiveRole
    );
  }
  const selectedMarketKey = firstActiveRole.replace(
    "portfolio-optimizer-draft-add-asset-candidate-row-",
    ""
  );
  await searchInput.press("Enter");

  await expect(popover).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-results-surface']"))
    .toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-recommendation-stale-blocked']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-scenario-stale-banner']"))
    .toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-recompute-banner']"))
    .toBeVisible();
  await expect(page.locator("[data-role='portfolio-optimizer-progress-panel']"))
    .toBeVisible();
  await expect.poll(async () => {
    const universe = await readOptimizerState(page, ["portfolio", "optimizer", "draft", "universe"]);
    return universe
      .map((row) => row["instrument-id"])
      .some((instrumentId) =>
        instrumentId === selectedMarketKey || instrumentId === `hl:${selectedMarketKey}`
      );
  }).toBe(true);
  await expect.poll(async () => {
    const progress = await readOptimizerState(page, ["portfolio", "optimizer", "optimization-progress"]);
    return progress?.status;
  }, { timeout: 15_000 }).toBe("succeeded");
  await expect.poll(async () => {
    const result = await readOptimizerState(page, ["portfolio", "optimizer", "last-successful-run", "result"]);
    return result?.["instrument-ids"]?.map((instrumentId) =>
      String(instrumentId).replace(/^hl:/, "")
    );
  }).toContain("perp:HYPE");
});

test("portfolio optimizer draft allocation row can be excluded and rerun @smoke @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.setViewportSize({ width: 1280, height: 900 });
  await visitRoute(page, "/portfolio/optimize/new", {
    routeModuleTimeoutMs: 30_000,
    idleOptions: { quietMs: 400, timeoutMs: 8_000, pollMs: 50 }
  });

  await seedTwoAssetDraftScenario(page);
  await dispatch(page, [
    ":actions/navigate",
    "/portfolio/optimize/draft",
    { "replace?": true }
  ]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });

  const ethRow = page.locator("[data-role='portfolio-optimizer-target-exposure-asset-ETH']");
  const excludeEth = page.locator("[data-role='portfolio-optimizer-target-exposure-exclude-perp-ETH']");

  await expect(ethRow).toBeVisible();
  await ethRow.hover();
  await excludeEth.click();

  await expect(ethRow).toHaveAttribute("data-excluded", "true");
  await expect(ethRow).toContainText("excluded");
  await expect(ethRow).toContainText("sell to 0");
  await expect(ethRow).toContainText("0.00%");
  await expect.poll(async () => {
    const blocklist = await readOptimizerState(page, ["portfolio", "optimizer", "draft", "constraints", "blocklist"]);
    return blocklist;
  }).toContain("perp:ETH");
  await expect.poll(async () => {
    const progress = await readOptimizerState(page, ["portfolio", "optimizer", "optimization-progress"]);
    return progress?.status;
  }).toBe("succeeded");
  await expect.poll(async () => {
    const result = await readOptimizerState(page, ["portfolio", "optimizer", "last-successful-run", "result"]);
    return result?.["instrument-ids"]?.map((instrumentId) =>
      String(instrumentId).replace(/^hl:/, "")
    );
  }).toEqual(["perp:BTC"]);
  await expect.poll(async () => {
    const result = await readOptimizerState(page, ["portfolio", "optimizer", "last-successful-run", "result"]);
    return result?.frontier?.every((point) => point.weights?.length === 1);
  }).toBe(true);
});

test("portfolio optimizer draft objective menu changes objective and reruns frontier @smoke @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.setViewportSize({ width: 1280, height: 900 });
  await visitRoute(page, "/portfolio/optimize/new", {
    routeModuleTimeoutMs: 30_000,
    idleOptions: { quietMs: 400, timeoutMs: 8_000, pollMs: 50 }
  });

  await seedTwoAssetDraftScenario(page);
  await dispatch(page, [
    ":actions/navigate",
    "/portfolio/optimize/draft",
    { "replace?": true }
  ]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });

  const trigger = page.locator("[data-role='portfolio-optimizer-objective-menu-trigger']");
  const menu = page.locator("[data-role='portfolio-optimizer-objective-menu']");
  const minimumVolatility = page.locator(
    "[data-role='portfolio-optimizer-objective-menu-option-minimum-volatility']"
  );
  const apply = page.locator("[data-role='portfolio-optimizer-objective-menu-apply']");

  await expect(page.locator("[data-role='portfolio-optimizer-results-surface']"))
    .toBeVisible();
  await expect(trigger).toContainText("Maximum Sharpe");
  await trigger.click();
  await expect(menu).toBeVisible();
  await expect(menu).toContainText("Change objective");
  // Apply is enabled even before a selection change: re-applying Maximum
  // Sharpe on this historical-mean draft would switch it onto the views-aware
  // return model, which is a real change under the consolidated model.
  await expect(apply).toBeEnabled();
  await minimumVolatility.click();
  await expect(minimumVolatility).toHaveAttribute("data-selected", "true");
  await expect(apply).toBeEnabled();
  await apply.click();

  await expect(menu).toHaveCount(0);
  await expect(page.locator("[data-role='portfolio-optimizer-recompute-banner']"))
    .toBeVisible();
  await expect.poll(async () => {
    const draftObjective = await readOptimizerState(page, [
      "portfolio",
      "optimizer",
      "draft",
      "objective",
      "kind"
    ]);
    return draftObjective;
  }).toBe("minimum-variance");
  await expect.poll(async () => {
    const progress = await readOptimizerState(page, [
      "portfolio",
      "optimizer",
      "optimization-progress"
    ]);
    return progress?.status;
  }, { timeout: 15_000 }).toBe("succeeded");
  await expect.poll(async () => {
    const result = await readOptimizerState(page, [
      "portfolio",
      "optimizer",
      "last-successful-run",
      "result"
    ]);
    return result?.solver?.["objective-kind"];
  }).toBe("minimum-variance");
  await expect.poll(async () => {
    const result = await readOptimizerState(page, [
      "portfolio",
      "optimizer",
      "last-successful-run",
      "result"
    ]);
    return result?.frontier?.length ?? 0;
  }).toBeGreaterThan(0);
  await expect(trigger).toContainText("Minimum risk");
});

test("portfolio optimizer draft objective menu edits max sharpe return views immediately @smoke @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.setViewportSize({ width: 1280, height: 900 });
  await visitRoute(page, "/portfolio/optimize/new", {
    routeModuleTimeoutMs: 30_000,
    idleOptions: { quietMs: 400, timeoutMs: 8_000, pollMs: 50 }
  });

  await seedTwoAssetDraftScenario(page);
  await dispatch(page, [
    ":actions/navigate",
    "/portfolio/optimize/draft",
    { "replace?": true }
  ]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });

  const trigger = page.locator("[data-role='portfolio-optimizer-objective-menu-trigger']");
  const menu = page.locator("[data-role='portfolio-optimizer-objective-menu']");
  const maximumSharpe = page.locator(
    "[data-role='portfolio-optimizer-objective-menu-option-max-sharpe']"
  );
  const minimumVolatility = page.locator(
    "[data-role='portfolio-optimizer-objective-menu-option-minimum-volatility']"
  );
  const useMyViews = page.locator(
    "[data-role='portfolio-optimizer-objective-menu-option-use-my-views']"
  );
  const editor = page.locator(
    "[data-role='portfolio-optimizer-objective-menu-use-my-views-editor']"
  );
  // Row locators are scoped to the menu editor: once the views-aware model is
  // active the results rail renders the same row roles.
  const btcReturn = editor.locator(
    "[data-role='portfolio-optimizer-objective-menu-view-hl:perp:BTC-return']"
  );
  const btcRow = editor.locator(
    "[data-role='portfolio-optimizer-objective-menu-view-row-hl:perp:BTC']"
  );
  const ethReturn = editor.locator(
    "[data-role='portfolio-optimizer-objective-menu-view-hl:perp:ETH-return']"
  );
  const btcStepDown = editor.locator(
    "[data-role='portfolio-optimizer-objective-menu-view-hl:perp:BTC-step-down']"
  );
  const addView = page.locator("[data-role='portfolio-optimizer-objective-menu-add-view']");
  const cancel = page.locator("[data-role='portfolio-optimizer-objective-menu-cancel']");
  const apply = page.locator("[data-role='portfolio-optimizer-objective-menu-apply']");

  await trigger.click();
  await expect(menu).toBeVisible();
  // Return views are an input policy carried by Maximum Sharpe — there is no
  // separate "use my views" objective, and the views editor renders under the
  // pending max-sharpe selection (pending defaults to the current key).
  await expect(useMyViews).toHaveCount(0);
  await expect(menu.locator("[data-role^='portfolio-optimizer-objective-menu-option-']"))
    .toHaveCount(4);
  await expect(maximumSharpe).toHaveAttribute("data-selected", "true");
  await expect(editor).toBeVisible();
  await expect(editor).toContainText("Return views");
  await expect(editor).not.toContainText("Relative views");
  await expect(addView).toHaveCount(0);
  await expect(cancel).toBeVisible();
  await expect(apply).toBeVisible();
  await expect(menu.locator("select")).toHaveCount(0);
  // The draft's return model is still historical-mean, so views are OFF: the
  // panel says so honestly instead of rendering rows that would not be used.
  await expect(editor.locator(
    "[data-role='portfolio-optimizer-objective-menu-use-my-views-editor-views-off']"
  )).toBeVisible();
  await expect(editor.locator(
    "[data-role='portfolio-optimizer-objective-menu-use-my-views-editor-views-on']"
  )).toBeVisible();
  await expect(editor.locator(
    "[data-role='portfolio-optimizer-objective-menu-use-my-views-editor-rows']"
  )).toHaveCount(0);
  await expect(btcReturn).toHaveCount(0);
  await expect(ethReturn).toHaveCount(0);
  await expect(btcRow).toHaveCount(0);
  await expect(btcStepDown).toHaveCount(0);
  await expect(menu).toHaveCSS("outline-style", "none");

  // Apply is enabled even though max-sharpe is already current: applying
  // attaches the views-aware return model to the historical-mean draft.
  await expect(apply).toBeEnabled();
  await apply.click();

  await expect(menu).toHaveCount(0);
  // Zero views is a valid state — the run proceeds with posterior = baseline;
  // nothing is materialized from the UI buffers.
  await expect.poll(async () => {
    const returnModel = await readOptimizerState(page, [
      "portfolio",
      "optimizer",
      "draft",
      "return-model"
    ]);
    return {
      kind: returnModel?.kind,
      views: returnModel?.views || []
    };
  }).toEqual({ kind: "black-litterman", views: [] });

  await expect.poll(async () => {
    const progress = await readOptimizerState(page, [
      "portfolio",
      "optimizer",
      "optimization-progress"
    ]);
    return progress?.status;
  }, { timeout: 15_000 }).toBe("succeeded");

  const railEditor = page.locator("[data-role='portfolio-optimizer-results-your-views-editor']");
  const railSummary = railEditor.locator(
    "[data-role='portfolio-optimizer-results-your-views-editor-summary']"
  );
  const railBtcReturn = railEditor.locator(
    "[data-role='portfolio-optimizer-objective-menu-view-hl:perp:BTC-return']"
  );
  const railBtcHigh = railEditor.locator(
    "[data-role='portfolio-optimizer-objective-menu-view-hl:perp:BTC-confidence-high']"
  );
  const railBtcSource = railEditor.locator(
    "[data-role='portfolio-optimizer-objective-menu-view-hl:perp:BTC-source']"
  );
  const railEthReturn = railEditor.locator(
    "[data-role='portfolio-optimizer-objective-menu-view-hl:perp:ETH-return']"
  );
  const railEthLow = railEditor.locator(
    "[data-role='portfolio-optimizer-objective-menu-view-hl:perp:ETH-confidence-low']"
  );
  await expect(railEditor).toBeVisible();
  await expect(railEditor).toContainText("Return views");
  await expect(railEditor).not.toContainText("Your return views");
  // The rail editor is collapsed by default — input editing is by-exception on
  // the results page — so the summary toggle carries the title + live counts and
  // the rows stay hidden until opened.
  const railToggle = railEditor.locator(
    "[data-role='portfolio-optimizer-results-your-views-editor-toggle']"
  );
  await expect(railToggle).toBeVisible();
  await expect(railBtcReturn).toBeHidden();
  // Scroll the app root fully to the bottom first: the collapsed editor is the
  // last rail row, and a minimal scroll-into-view leaves it flush with the
  // viewport bottom where the fixed footer (z-170) intercepts the click point.
  // At max scroll the main pb clearance lifts it above the footer.
  await page.evaluate(() => {
    const root = document.querySelector("[data-parity-id='app-root']");
    if (root) root.scrollTop = root.scrollHeight;
  });
  await railToggle.click();
  await expect(page.locator("[data-role='portfolio-optimizer-results-your-views-apply']"))
    .toBeVisible();

  // With the views-aware model active, rows render with the implied BASELINE
  // pre-filled (the raw estimate the solver uses for viewless assets — NOT the
  // last run's posterior, which used to pose as the seed here) and honest
  // provenance: implied, no selected confidence.
  await expect(railBtcReturn).not.toHaveValue("");
  await expect(railEthReturn).not.toHaveValue("");
  await expect(railBtcSource).toHaveAttribute("data-source", "implied");
  await expect(railEditor.locator("[data-role*='-confidence-'][data-selected='true']"))
    .toHaveCount(0);

  // Rail edits author absolute views IMMEDIATELY — no Apply round-trip.
  await railBtcReturn.fill("18");
  await railBtcHigh.click();
  await railEthReturn.fill("16.5");
  await railEthLow.click();
  await expect.poll(async () => {
    const returnModel = await readOptimizerState(page, [
      "portfolio",
      "optimizer",
      "draft",
      "return-model"
    ]);
    return {
      kind: returnModel?.kind,
      views: (returnModel?.views || []).map((view) => ({
        instrumentId: view["instrument-id"],
        value: view.return,
        confidence: view.confidence,
        confidenceLevel: view["confidence-level"]
      }))
    };
  }).toEqual({
    kind: "black-litterman",
    views: [
      {
        instrumentId: "hl:perp:BTC",
        value: 0.18,
        confidence: 0.75,
        confidenceLevel: "high"
      },
      {
        instrumentId: "hl:perp:ETH",
        value: 0.165,
        confidence: 0.25,
        confidenceLevel: "low"
      }
    ]
  });
  await expect(railBtcHigh).toHaveAttribute("data-selected", "true");
  await expect(railEthLow).toHaveAttribute("data-selected", "true");
  await expect(railBtcSource).toHaveAttribute("data-source", "user");
  await expect(railSummary).toContainText("2 your views");

  await trigger.click();
  await expect(menu).toBeVisible();
  await expect(editor).toBeVisible();
  const editorBtcRow = editor.locator("[data-role='portfolio-optimizer-objective-menu-view-row-hl:perp:BTC']");
  const editorBtcReturn = editor.locator("[data-role='portfolio-optimizer-objective-menu-view-hl:perp:BTC-return']");
  const editorEthReturn = editor.locator("[data-role='portfolio-optimizer-objective-menu-view-hl:perp:ETH-return']");
  const editorBtcHigh = editor.locator("[data-role='portfolio-optimizer-objective-menu-view-hl:perp:BTC-confidence-high']");
  const editorEthLow = editor.locator("[data-role='portfolio-optimizer-objective-menu-view-hl:perp:ETH-confidence-low']");
  await expect(editorBtcRow).toBeInViewport({ ratio: 1 });
  await expect(editorBtcReturn).toHaveValue("18");
  await expect(editorEthReturn).toHaveValue("16.5");
  await expect(editorBtcHigh).toHaveAttribute("data-selected", "true");
  await expect(editorEthLow).toHaveAttribute("data-selected", "true");

  // Arrow-key stepping on a views-aware draft updates the authored view
  // immediately (buffer and draft stay in lockstep), and steps back cleanly.
  await editorBtcReturn.focus();
  await page.keyboard.press("ArrowUp");
  await expect(editorBtcReturn).toHaveValue("18.5");
  await expect(editorBtcReturn).toBeFocused();
  await expect.poll(async () => {
    const returnModel = await readOptimizerState(page, [
      "portfolio",
      "optimizer",
      "draft",
      "return-model"
    ]);
    return returnModel?.views?.find(
      (view) => view["instrument-id"] === "hl:perp:BTC"
    )?.return;
  }).toBe(0.185);
  await editor.locator(
    "[data-role='portfolio-optimizer-objective-menu-view-hl:perp:BTC-step-down']"
  ).click();
  await expect(editorBtcReturn).toHaveValue("18");

  const rowBox = await editorBtcRow.boundingBox();
  const returnBox = await editorBtcReturn.boundingBox();
  const confidenceBox = await editorBtcHigh.boundingBox();
  expect(rowBox?.height ?? 0).toBeGreaterThanOrEqual(44);
  expect(returnBox?.height ?? 0).toBeGreaterThanOrEqual(28);
  expect(confidenceBox?.height ?? 0).toBeGreaterThanOrEqual(28);

  // Picking a non-Sharpe objective no longer downgrades the return model: the
  // views-aware model and the authored views survive the switch.
  await minimumVolatility.click();
  await expect(minimumVolatility).toHaveAttribute("data-selected", "true");
  await expect(editor).toHaveCount(0);
  await expect(apply).toBeEnabled();
  await apply.click();

  await expect(menu).toHaveCount(0);
  await expect(trigger).toContainText("Minimum risk");
  await expect.poll(async () => {
    const draft = await readOptimizerState(page, [
      "portfolio",
      "optimizer",
      "draft"
    ]);
    return {
      objective: draft?.objective?.kind,
      returnModel: draft?.["return-model"]?.kind,
      viewInstruments: (draft?.["return-model"]?.views || []).map(
        (view) => view["instrument-id"]
      )
    };
  }).toEqual({
    objective: "minimum-variance",
    returnModel: "black-litterman",
    viewInstruments: ["hl:perp:BTC", "hl:perp:ETH"]
  });
  await expect(railEditor).toBeVisible();
});

test("portfolio optimizer max sharpe return views popover stays usable across review widths @smoke @regression", async ({ page }) => {
  test.setTimeout(180_000);

  for (const viewport of [
    { width: 375, height: 812 },
    { width: 768, height: 900 },
    { width: 1280, height: 900 },
    { width: 1440, height: 900 }
  ]) {
    await test.step(`viewport ${viewport.width}`, async () => {
      await page.setViewportSize(viewport);
      await visitRoute(page, "/portfolio/optimize/new", {
        routeModuleTimeoutMs: 30_000,
        idleOptions: { quietMs: 400, timeoutMs: 8_000, pollMs: 50 }
      });

      await seedTwoAssetDraftScenario(page);
      // The dense row layout is what this test protects, so put the draft on
      // the views-aware model (rows only render there — historical drafts show
      // the views-off note instead).
      await dispatch(page, [
        ":actions/apply-portfolio-optimizer-setup-preset",
        ":max-sharpe"
      ]);
      await dispatch(page, [
        ":actions/navigate",
        "/portfolio/optimize/draft",
        { "replace?": true }
      ]);
      await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });

      const trigger = page.locator("[data-role='portfolio-optimizer-objective-menu-trigger']");
      const menu = page.locator("[data-role='portfolio-optimizer-objective-menu']");
      const maximumSharpe = page.locator(
        "[data-role='portfolio-optimizer-objective-menu-option-max-sharpe']"
      );
      const editor = page.locator(
        "[data-role='portfolio-optimizer-objective-menu-use-my-views-editor']"
      );
      const addView = page.locator("[data-role='portfolio-optimizer-objective-menu-add-view']");
      const footerActions = page.locator("[data-role='portfolio-optimizer-objective-menu-apply']");

      await trigger.scrollIntoViewIfNeeded();
      await trigger.click();
      await expect(menu).toBeVisible();
      // The views editor renders inline under the max-sharpe option (there is
      // no separate "use my views" option anymore).
      await maximumSharpe.click();
      await expect(editor).toBeVisible();
      await expect(editor).toContainText("Return views");
      await expect(editor).not.toContainText("Relative views");
      await expect(addView).toHaveCount(0);
      // The popover is page-anchored under the trigger; on narrow single-column
      // layouts the trigger can sit low enough that reaching the footer takes a
      // page scroll (same as any long dropdown) — usable means reachable.
      await footerActions.scrollIntoViewIfNeeded();
      await expect(footerActions).toBeInViewport({ ratio: 1 });
      await expect(menu.locator("select")).toHaveCount(0);
      await expect(menu.locator("input[type='number']")).toHaveCount(0);
      await expect(menu.locator("[data-role$='confidence-high']")).toHaveCount(2);

      const box = await menu.boundingBox();
      expect(box).not.toBeNull();
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);

      const btcReturn = menu.locator(
        "[data-role='portfolio-optimizer-objective-menu-view-hl:perp:BTC-return']"
      );
      const btcHigh = menu.locator(
        "[data-role='portfolio-optimizer-objective-menu-view-hl:perp:BTC-confidence-high']"
      );
      // Rows edit the views-aware draft immediately: the fill authors the view
      // and the confidence click re-weights it.
      await btcReturn.fill("18");
      await expect(btcReturn).toHaveValue("18");
      await btcHigh.click();
      await expect(btcHigh).toHaveAttribute("data-selected", "true");
    });
  }
});

test("portfolio optimizer draft objective menu stays contained across viewports @smoke @regression", async ({ page }) => {
  test.setTimeout(180_000);

  for (const viewport of [
    { width: 375, height: 812 },
    { width: 768, height: 900 },
    { width: 1280, height: 900 },
    { width: 1440, height: 900 }
  ]) {
    await test.step(`viewport ${viewport.width}`, async () => {
      await page.setViewportSize(viewport);
      await visitRoute(page, "/portfolio/optimize/new", {
        routeModuleTimeoutMs: 30_000,
        idleOptions: { quietMs: 400, timeoutMs: 8_000, pollMs: 50 }
      });

      await seedRetainedDraftScenario(page);
      await dispatch(page, [
        ":actions/navigate",
        "/portfolio/optimize/draft",
        { "replace?": true }
      ]);
      await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });

      const trigger = page.locator("[data-role='portfolio-optimizer-objective-menu-trigger']");
      const menu = page.locator("[data-role='portfolio-optimizer-objective-menu']");
      const backdrop = page.locator("[data-role='portfolio-optimizer-objective-menu-backdrop']");

      await trigger.scrollIntoViewIfNeeded();
      const triggerBox = await trigger.boundingBox();
      expect(triggerBox).not.toBeNull();

      await trigger.click();
      await expect(menu).toBeVisible();
      await expect(menu).toHaveCSS("background-color", "rgb(16, 19, 22)");
      await expect(backdrop).toHaveCount(0);

      const box = await menu.boundingBox();
      expect(box).not.toBeNull();
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);
      expect(box.y).toBeGreaterThanOrEqual(0);
      expect(box.y + box.height).toBeLessThanOrEqual(viewport.height);
      expect(box.y).toBeGreaterThanOrEqual(triggerBox.y + triggerBox.height - 2);
      expect(Math.abs(box.x - triggerBox.x)).toBeLessThanOrEqual(
        viewport.width >= 1280 ? 24 : 180
      );
    });
  }
});

test("portfolio optimizer draft add asset selector stays contained and focused across viewports @smoke @regression", async ({ page }) => {
  test.setTimeout(180_000);

  for (const viewport of [
    { width: 375, height: 812 },
    { width: 768, height: 900 },
    { width: 1280, height: 900 },
    { width: 1440, height: 900 }
  ]) {
    await test.step(`viewport ${viewport.width}`, async () => {
      await page.setViewportSize(viewport);
      await visitRoute(page, "/portfolio/optimize/new", {
        routeModuleTimeoutMs: 30_000,
        idleOptions: { quietMs: 400, timeoutMs: 8_000, pollMs: 50 }
      });

      await seedRetainedDraftScenario(page);
      await seedOptimizerMarkets(page, [
        {
          key: "perp:BTC",
          "market-type": optimizerKeyword("perp"),
          coin: "BTC",
          symbol: "BTC-USDC",
          base: "BTC",
          quote: "USDC",
          volume24h: 96000000
        },
        {
          key: "perp:ETH",
          "market-type": optimizerKeyword("perp"),
          coin: "ETH",
          symbol: "ETH-USDC",
          base: "ETH",
          quote: "USDC",
          volume24h: 84000000
        }
      ]);
      await dispatch(page, [
        ":actions/navigate",
        "/portfolio/optimize/draft",
        { "replace?": true }
      ]);
      await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });

      const addAsset = page.locator("[data-role='portfolio-optimizer-draft-add-asset']");
      const popover = page.locator("[data-role='portfolio-optimizer-draft-add-asset-popover']");
      const searchInput = page.locator("[data-role='portfolio-optimizer-draft-add-asset-search-input']");
      const ethRow = page.locator("[data-role='portfolio-optimizer-draft-add-asset-candidate-row-perp:ETH']");

      // Center the anchor first: a minimal scroll can leave the button flush
      // with the viewport bottom, where the downward-opening popover's
      // containment depends on incidental page height above the anchor rather
      // than on the popover's own sizing (which is what this test pins).
      await addAsset.evaluate((el) => el.scrollIntoView({ block: "center" }));
      await addAsset.click();
      await expect(popover).toBeVisible();
      await expect(searchInput).toHaveAttribute("type", "search");
      await expect(searchInput).toBeFocused();
      await page.keyboard.type("eth");
      await expect(searchInput).toHaveValue("eth");
      await expect(ethRow).toBeVisible();

      const box = await popover.boundingBox();
      expect(box).not.toBeNull();
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);
      expect(box.y).toBeGreaterThanOrEqual(0);
      expect(box.y + box.height).toBeLessThanOrEqual(viewport.height);
    });
  }
});
