import { expect, test } from "../support/guarded_test.mjs";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";

// The hypothetical tear sheet: a solved run that holds a vault gets a header
// link and a "Hypothetical performance" card on the Recommendation tab; both
// open /portfolio/optimize/draft/tear-sheet, which backtests the target weights
// over each asset's own history. Runs without vaults are unchanged.

const VAULT_ADDRESS = "0x1111111111111111111111111111111111111111";
const VAULT_ID = `vault:${VAULT_ADDRESS}`;

async function seedSolvedRun(page, { withVault }) {
  await page.evaluate(
    ({ vaultAddress, vaultId, withVault }) => {
      const c = globalThis.cljs.core;
      const kw = (name) => c.keyword(name);
      const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
      const vector = (items) => c.PersistentVector.fromArray(items, true);
      const path = (...segments) =>
        c.PersistentVector.fromArray(segments.map((segment) => kw(segment)), true);
      const day = 24 * 60 * 60 * 1000;
      const now = Date.now();

      // A vault sampled every 6 days for 300 days, plus a dense last month.
      const summary = (n, gapDays) => {
        const times = Array.from({ length: n }, (_, i) => now - gapDays * day * (n - 1 - i));
        const values = times.map((_, i) => 100000 * (1 + 0.002 * i + 0.01 * Math.sin(i)));
        return map([
          kw("accountValueHistory"), vector(times.map((t, i) => vector([t, String(values[i])]))),
          kw("pnlHistory"), vector(times.map((t, i) => vector([t, String(values[i] - 100000)])))
        ]);
      };
      const btcCandles = vector(
        Array.from({ length: 400 }, (_, i) =>
          map([kw("time-ms"), now - (399 - i) * day, kw("close"), String(60000 + 300 * Math.sin(i / 9))])
        )
      );

      const vault = map([
        kw("instrument-id"), vaultId,
        kw("market-type"), kw("vault"),
        kw("vault-address"), vaultAddress,
        kw("name"), "Steady Vault",
        kw("shortable?"), false
      ]);
      const btc = map([
        kw("instrument-id"), "perp:BTC",
        kw("market-type"), kw("perp"),
        kw("coin"), "BTC",
        kw("symbol"), "BTC-USDC",
        kw("name"), "Bitcoin"
      ]);
      const ids = withVault ? [vaultId, "perp:BTC"] : ["perp:BTC"];
      const weights = withVault ? [0.7, 0.3] : [1];
      const draft = map([
        kw("id"), "draft-current",
        kw("name"), "Tear sheet run",
        kw("universe"), vector(withVault ? [vault, btc] : [btc]),
        kw("objective"), map([kw("kind"), kw("minimum-variance")]),
        kw("return-model"), map([kw("kind"), kw("historical-mean")]),
        kw("risk-model"), map([kw("kind"), kw("sample-covariance")]),
        kw("constraints"), map([kw("long-only?"), true, kw("max-asset-weight"), 1, kw("gross-max"), 1]),
        kw("metadata"), map([kw("dirty?"), false])
      ]);
      const result = map([
        kw("status"), kw("solved"),
        kw("scenario-id"), "draft",
        kw("instrument-ids"), vector(ids),
        kw("target-weights"), vector(weights),
        kw("current-weights"), vector(ids.map(() => 0)),
        kw("target-weights-by-instrument"), map(ids.flatMap((id, i) => [id, weights[i]])),
        kw("current-weights-by-instrument"), map(ids.flatMap((id) => [id, 0])),
        kw("labels-by-instrument"), map(ids.flatMap((id) => [id, id === "perp:BTC" ? "BTC" : "Steady Vault"])),
        kw("expected-return"), 0.12,
        kw("volatility"), 0.2,
        kw("return-model"), kw("historical-mean"),
        kw("risk-model"), kw("sample-covariance"),
        kw("as-of-ms"), now,
        kw("frontier"), vector([
          map([kw("id"), 0, kw("expected-return"), 0.12, kw("volatility"), 0.2, kw("sharpe"), 0.6])
        ]),
        kw("frontier-overlays"), map([]),
        kw("performance"), map([kw("in-sample-sharpe"), 0.58, kw("shrunk-sharpe"), 0.29]),
        kw("diagnostics"), map([
          kw("gross-exposure"), 1, kw("net-exposure"), 1, kw("effective-n"), 1,
          kw("turnover"), 0.75, kw("binding-constraints"), vector([]),
          kw("covariance-conditioning"), map([kw("status"), kw("ok")])
        ])
      ]);

      const store = globalThis.hyperopen.system.store;
      let state = c.deref(store);
      state = c.assoc_in(state, path("portfolio", "optimizer", "draft"), draft);
      state = c.assoc_in(
        state,
        path("portfolio", "optimizer", "history-data"),
        map([
          kw("candle-history-by-coin"), map(["BTC", btcCandles]),
          kw("funding-history-by-coin"), map([]),
          kw("vault-details-by-address"), map([
            vaultAddress,
            map([
              kw("portfolio"), map([kw("all-time"), summary(50, 6), kw("month"), summary(45, 0.66)]),
              kw("leader-commission"), 0.1
            ])
          ])
        ])
      );
      state = c.assoc_in(
        state,
        path("portfolio", "optimizer", "active-scenario"),
        map([kw("loaded-id"), null, kw("status"), kw("computed")])
      );
      state = c.assoc_in(
        state,
        path("portfolio", "optimizer", "last-successful-run"),
        map([kw("request-signature"), null, kw("computed-at-ms"), now, kw("result"), result])
      );
      state = c.assoc_in(
        state,
        path("portfolio", "optimizer", "run-state"),
        map([kw("status"), kw("succeeded")])
      );
      state = c.assoc_in(state, path("portfolio-ui", "optimizer", "results-tab"), kw("recommendation"));
      c.reset_BANG_(store, state);
    },
    { vaultAddress: VAULT_ADDRESS, vaultId: VAULT_ID, withVault }
  );
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });
}

test.describe("hypothetical tear sheet", () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test("a vault run opens a hypothetical tear sheet from the recommendation @regression", async ({ page }) => {
    await visitRoute(page, "/portfolio/optimize/draft");
    await seedSolvedRun(page, { withVault: true });

    const card = page.locator("[data-role='portfolio-optimizer-tear-sheet-card']");
    await expect(card).toBeVisible();
    await expect(card.locator("[data-role='portfolio-optimizer-tear-sheet-card-return']")).toHaveText(/^[+-]\d+\.\d{2}%$/);
    await expect(page.locator("[data-role='portfolio-optimizer-tear-sheet-link']")).toBeVisible();

    await card.locator("[data-role='portfolio-optimizer-tear-sheet-card-open']").click();
    await expect(page).toHaveURL(/\/portfolio\/optimize\/draft\/tear-sheet$/);

    const sheet = page.locator("[data-role='portfolio-optimizer-tear-sheet']");
    await expect(sheet.locator("[data-role='portfolio-optimizer-tear-sheet-badge']")).toHaveText(/hypothetical/i);
    await expect(sheet.locator("[data-role='portfolio-optimizer-tear-sheet-in-sample']")).toContainText("In-sample");
    await expect(sheet.locator("[data-role='portfolio-optimizer-tear-sheet-return']")).toHaveText(/^[+-]\d+\.\d{2}%$/);
    await expect(sheet.locator("[data-role='vault-detail-performance-metrics-card']")).toBeVisible();

    // The vault joins inside 1Y (300 days of history), so the strip shows it joining.
    await expect(sheet.locator("[data-role='portfolio-optimizer-tear-sheet-live-strip']")).toContainText("Steady Vault joins");

    // Range and method changes recompute the backtest.
    const oneYear = await sheet.locator("[data-role='portfolio-optimizer-tear-sheet-return']").textContent();
    await sheet.locator("[data-role='portfolio-optimizer-tear-sheet-range-three-month']").click();
    await expect(sheet.locator("[data-role='portfolio-optimizer-tear-sheet-return']")).not.toHaveText(oneYear);

    await sheet.locator("[data-role='portfolio-optimizer-tear-sheet-tab-holdings']").click();
    await expect(sheet.locator("[data-role='portfolio-optimizer-tear-sheet-holding-row']")).toHaveCount(2);

    await sheet.locator("[data-role='portfolio-optimizer-tear-sheet-tab-method']").click();
    await sheet.locator("[data-role='portfolio-optimizer-tear-sheet-missing-common']").click();
    await expect(sheet.locator("[data-role='portfolio-optimizer-tear-sheet-missing-common']")).toHaveAttribute("aria-checked", "true");

    await sheet.locator("[data-role='portfolio-optimizer-tear-sheet-back']").click();
    await expect(page).toHaveURL(/\/portfolio\/optimize\/draft$/);
    await expect(page.locator("[data-role='portfolio-optimizer-tear-sheet-card']")).toBeVisible();
  });

  test("a run without vaults shows no tear-sheet entry points @regression", async ({ page }) => {
    await visitRoute(page, "/portfolio/optimize/draft");
    await seedSolvedRun(page, { withVault: false });

    await expect(page.locator("[data-role='portfolio-optimizer-recommendation-tab']")).toBeVisible();
    await expect(page.locator("[data-role='portfolio-optimizer-tear-sheet-card']")).toHaveCount(0);
    await expect(page.locator("[data-role='portfolio-optimizer-tear-sheet-link']")).toHaveCount(0);
  });
});
