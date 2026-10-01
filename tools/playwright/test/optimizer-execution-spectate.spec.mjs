import { expect, test } from "../support/guarded_test.mjs";
import { dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";
import { readOptimizerState } from "../support/optimizer_state.mjs";

// A read-only (Spectate) viewer must still be able to model execution: the strategy tiles and
// per-order editors only re-project estimated costs, so they stay interactive while Arm/Confirm
// (which would send live orders) stay blocked. This pins that end-to-end in the real app.

const SPECTATE_ADDRESS = "0x162cc7c861ebd0c06b3d72319201150482518185";

// Seeds a current (non-stale) solved run whose rebalance preview has one ready BTC buy, plus an
// ACTIVE spectate session — seeded directly into state so no identity-swap side effect can wipe
// the seeded run. Mirrors the proven seedRetainedDraftScenario shape from the optimizer smoke spec.
async function seedSpectatedSolvedRunWithReadyOrder(page) {
  await page.evaluate((spectateAddress) => {
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
      kw("name"), "Spectate Execution Sim",
      kw("universe"), vector([btcInstrument]),
      kw("objective"), map([kw("kind"), kw("minimum-variance")]),
      kw("return-model"), map([kw("kind"), kw("historical-mean")]),
      kw("risk-model"), map([kw("kind"), kw("sample-covariance")]),
      kw("constraints"), map([
        kw("long-only?"), true,
        kw("max-asset-weight"), 1,
        kw("gross-max"), 1
      ]),
      kw("metadata"), map([kw("dirty?"), false])
    ]);
    // One READY perp buy (positive qty, non-zero delta, tradeable type) with a cost block so the
    // KPI strip / strategy tiles have real numbers to re-project.
    const readyRow = map([
      kw("instrument-id"), "perp:BTC",
      kw("instrument-type"), kw("perp"),
      kw("status"), kw("ready"),
      kw("side"), kw("buy"),
      kw("quantity"), 0.5,
      kw("delta-notional-usd"), 5000,
      kw("cost"), map([
        kw("source"), kw("snapshot"),
        kw("slippage-bps"), 3, kw("estimated-slippage-usd"), 1.5,
        kw("fee-bps"), 4.5, kw("estimated-fee-usd"), 2.25,
        kw("maker-fee-bps"), 1.5, kw("maker-fee-usd"), 0.75
      ])
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
      kw("expected-return"), 0.12,
      kw("volatility"), 0.2,
      kw("return-model"), kw("historical-mean"),
      kw("risk-model"), kw("sample-covariance"),
      kw("as-of-ms"), 1777046100000,
      kw("frontier"), vector([
        map([kw("id"), 0, kw("expected-return"), 0.12, kw("volatility"), 0.2, kw("sharpe"), 0.6])
      ]),
      kw("frontier-overlays"), map([]),
      kw("performance"), map([kw("in-sample-sharpe"), 0.58, kw("shrunk-sharpe"), 0.29]),
      kw("diagnostics"), map([
        kw("gross-exposure"), 1, kw("net-exposure"), 1, kw("effective-n"), 1,
        kw("turnover"), 0.75, kw("binding-constraints"), vector([]),
        kw("covariance-conditioning"), map([kw("status"), kw("ok")])
      ]),
      kw("rebalance-preview"), map([
        kw("status"), kw("ready"),
        kw("capital-usd"), 10000,
        kw("summary"), map([
          kw("ready-count"), 1,
          kw("blocked-count"), 0,
          kw("gross-trade-notional-usd"), 5000,
          kw("estimated-fees-usd"), 2.25,
          kw("estimated-slippage-usd"), 1.5,
          kw("margin"), map([kw("after-utilization"), 0.2])
        ]),
        kw("rows"), vector([readyRow])
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
    const readiness =
      hyperopen.portfolio.optimizer.application.setup_readiness.build_readiness(state);
    const request = c.get(readiness, kw("request"));
    const requestSignature =
      hyperopen.portfolio.optimizer.contracts.signatures.build_request_signature(request);
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "active-scenario"),
      map([kw("loaded-id"), null, kw("status"), kw("computed")])
    );
    state = c.assoc_in(
      state,
      path("portfolio", "optimizer", "last-successful-run"),
      map([
        kw("request-signature"), requestSignature,
        kw("computed-at-ms"), 1777046100000,
        kw("result"), result
      ])
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
    // Active spectate session (seeded, not via the action) => the staged plan is read-only.
    state = c.assoc_in(
      state,
      path("account-context", "spectate-mode"),
      map([kw("active?"), true, kw("address"), spectateAddress, kw("started-at-ms"), 1777046090000])
    );
    c.reset_BANG_(store, state);
  }, SPECTATE_ADDRESS);
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });
}

test("spectate viewers can model execution strategy while sending stays blocked @regression", async ({ page }) => {
  test.setTimeout(90_000);

  await page.setViewportSize({ width: 1280, height: 900 });
  await visitRoute(page, "/portfolio/optimize/new", {
    routeModuleTimeoutMs: 30_000,
    idleOptions: { quietMs: 400, timeoutMs: 8_000, pollMs: 50 }
  });

  await seedSpectatedSolvedRunWithReadyOrder(page);
  await dispatch(page, [
    ":actions/navigate",
    "/portfolio/optimize/draft",
    { "replace?": true }
  ]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 8_000, pollMs: 50 });

  // Enter Execution — this stages the plan under the active spectate session.
  await page.locator("[data-role='portfolio-optimizer-scenario-tab-execution']").click();
  await expect(page.locator("[data-role='portfolio-optimizer-execution-tab-shell']")).toBeVisible();

  const band = page.locator("[data-role='portfolio-optimizer-execution-control-band']");
  const notice = page.locator("[data-role='portfolio-optimizer-execution-readonly']");
  const marketTile = page.locator("[data-role='portfolio-optimizer-execution-mode-market']");
  const arm = page.locator("[data-role='portfolio-optimizer-execution-arm']");

  // The notice explains the split: read-only, but you can still model here.
  await expect(notice).toBeVisible();
  await expect(notice).toContainText("Spectate Mode is read-only");
  await expect(notice).toContainText("still model execution strategies");

  // The strategy tiles are interactive (not disabled) even though this is a read-only view.
  await expect(band).toBeVisible();
  await expect(marketTile).toBeEnabled();

  // Sending stays blocked: Arm is disabled.
  await expect(arm).toBeDisabled();

  // Clicking a strategy tile changes the modeled default order type (a pure projection change).
  await marketTile.click();
  await expect.poll(async () =>
    readOptimizerState(page, ["portfolio", "optimizer", "execution-modal", "default-order-type"])
  ).toBe("market");
  await expect(marketTile).toHaveAttribute("data-active", "true");

  // Still read-only after modeling — the plan never became sendable.
  await expect(arm).toBeDisabled();
  expect(
    await readOptimizerState(page, ["portfolio", "optimizer", "execution-modal", "plan", "execution-disabled?"])
  ).toBe(true);
});
