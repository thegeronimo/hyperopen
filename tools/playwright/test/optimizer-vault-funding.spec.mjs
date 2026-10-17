import { expect, test } from "../support/guarded_test.mjs";
import { routeHyperCoreAccountStreams } from "../support/hyperevm_fixtures.mjs";
import { debugCall, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

// Fund this allocation: a solved all-vault run opens the funding sheet from the
// verdict CTA; the plan counts existing deposits (an over-target unlocked vault
// is cut back), review lists withdrawals before deposits, and confirm sends
// agent-signed vaultTransfer actions one at a time. The exchange is the debug
// simulator and the account reads are intercepted, so nothing leaves the test.

const OWNER = "0x4444444444444444444444444444444444444444";
const VA = "0x1111111111111111111111111111111111111111";
const VB = "0x2222222222222222222222222222222222222222";
const VC = "0x3333333333333333333333333333333333333333";
const PRIVATE_KEY = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

const role = (page, name) => page.locator(`[data-role='${name}']`);

async function interceptAccountReads(page) {
  // Live balance streams for the fake wallet would report $0; the REST
  // answers below are the account's only balances.
  await routeHyperCoreAccountStreams(page);
  await page.route("**/info", async (route) => {
    const body = route.request().postData() || "";
    const type = (() => {
      try {
        return JSON.parse(body).type;
      } catch {
        return null;
      }
    })();
    const json = {
      userVaultEquities: [{ vaultAddress: VB, equity: "6000", lockedUntilTimestamp: 0 }],
      clearinghouseState: {
        withdrawable: "24180",
        marginSummary: { accountValue: "24180", totalMarginUsed: "0", totalNtlPos: "0", totalRawUsd: "24180" },
        crossMarginSummary: { accountValue: "24180", totalMarginUsed: "0", totalNtlPos: "0", totalRawUsd: "24180" },
        assetPositions: [],
        time: 0
      },
      spotClearinghouseState: { balances: [] },
      userAbstraction: "default"
    }[type];
    if (json !== undefined) {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(json) });
    } else {
      await route.continue();
    }
  });
}

async function seedSession(page) {
  await page.evaluate(
    ({ owner, privateKey }) => {
      const c = globalThis.cljs.core;
      const kw = (name) => c.keyword(name);
      const path = (...segments) => c.PersistentVector.fromArray(segments.map(kw), true);
      const lower = owner.toLowerCase();
      localStorage.setItem("hyperopen:agent-storage-mode:v1", "local");
      localStorage.setItem("hyperopen:agent-local-protection-mode:v1", "plain");
      localStorage.setItem(
        `hyperopen:agent-session:v1:${lower}`,
        JSON.stringify({
          "agent-address": "0x9999999999999999999999999999999999999999",
          "private-key": privateKey,
          "last-approved-at": 1700000000000,
          "nonce-cursor": 1700000000000
        })
      );
      let state = c.deref(globalThis.hyperopen.system.store);
      state = c.assoc_in(state, path("wallet", "connected?"), true);
      state = c.assoc_in(state, path("wallet", "address"), owner);
      state = c.assoc_in(state, path("wallet", "agent", "status"), kw("ready"));
      state = c.assoc_in(state, path("wallet", "agent", "storage-mode"), kw("local"));
      state = c.assoc_in(state, path("wallet", "agent", "local-protection-mode"), kw("plain"));
      state = c.assoc_in(state, path("wallet", "agent", "nonce-cursor"), 1700000000000);
      c.reset_BANG_(globalThis.hyperopen.system.store, state);
    },
    { owner: OWNER, privateKey: PRIVATE_KEY }
  );
}

async function seedVaultRun(page, { allVault = true, account = true } = {}) {
  await page.evaluate(
    ({ va, vb, vc, owner, allVault, account }) => {
      const c = globalThis.cljs.core;
      const kw = (name) => c.keyword(name);
      const map = (entries) => c.PersistentArrayMap.fromArray(entries, true);
      const vector = (items) => c.PersistentVector.fromArray(items, true);
      const path = (...segments) => c.PersistentVector.fromArray(segments.map(kw), true);
      const ids = allVault ? [`vault:${va}`, `vault:${vb}`, `vault:${vc}`] : ["perp:BTC", "perp:ETH"];
      const weights = allVault ? [0.5, 0.3, 0.2] : [0.6, 0.4];
      const names = { [`vault:${va}`]: "Alpha", [`vault:${vb}`]: "Beta", [`vault:${vc}`]: "Gamma", "perp:BTC": "BTC", "perp:ETH": "ETH" };
      const signature = map([kw("input-signature"), "sig-1", kw("scenario-id"), null]);
      const result = map([
        kw("status"), kw("solved"),
        kw("scenario-id"), "draft",
        kw("instrument-ids"), vector(ids),
        kw("target-weights"), vector(weights),
        kw("current-weights"), vector(ids.map(() => 0)),
        kw("target-weights-by-instrument"), map(ids.flatMap((id, i) => [id, weights[i]])),
        kw("current-weights-by-instrument"), map(ids.flatMap((id) => [id, 0])),
        kw("labels-by-instrument"), map(ids.flatMap((id) => [id, names[id]])),
        kw("expected-return"), 0.12,
        kw("volatility"), 0.2,
        kw("return-model"), kw("historical-mean"),
        kw("risk-model"), kw("sample-covariance"),
        kw("frontier"), vector([map([kw("id"), 0, kw("expected-return"), 0.12, kw("volatility"), 0.2, kw("sharpe"), 0.6])]),
        kw("frontier-overlays"), map([]),
        kw("performance"), map([kw("in-sample-sharpe"), 0.58, kw("shrunk-sharpe"), 0.29]),
        kw("diagnostics"), map([kw("gross-exposure"), 1, kw("net-exposure"), 1, kw("effective-n"), 1, kw("turnover"), 0.75,
                                kw("binding-constraints"), vector([]), kw("covariance-conditioning"), map([kw("status"), kw("ok")])])
      ]);
      let state = c.deref(globalThis.hyperopen.system.store);
      state = c.assoc_in(state, path("portfolio", "optimizer", "active-scenario"), map([kw("loaded-id"), null, kw("status"), kw("computed")]));
      state = c.assoc_in(state, path("portfolio", "optimizer", "last-successful-run"),
                         map([kw("request-signature"), signature, kw("computed-at-ms"), 1, kw("result"), result]));
      state = c.assoc_in(state, path("portfolio", "optimizer", "run-state"),
                         map([kw("status"), kw("succeeded"), kw("request-signature"), signature]));
      state = c.assoc_in(state, path("portfolio-ui", "optimizer", "results-tab"), kw("recommendation"));
      if (account) {
      state = c.assoc_in(state, path("account", "mode"), kw("classic"));
      state = c.assoc_in(state, path("webdata2", "clearinghouseState"), map([kw("withdrawable"), "24180"]));
      state = c.assoc_in(state, path("vaults", "user-equities"),
                         vector([map([kw("vault-address"), vb, kw("equity"), 6000, kw("locked-until-ms"), 0])]));
      state = c.assoc_in(state, path("vaults", "user-equity-by-address"),
                         map([vb, map([kw("vault-address"), vb, kw("equity"), 6000, kw("locked-until-ms"), 0])]));
      state = c.assoc_in(state, path("vaults", "user-equities-for-address"), owner);
      }
      c.reset_BANG_(globalThis.hyperopen.system.store, state);
    },
    { va: VA, vb: VB, vc: VC, owner: OWNER, allVault, account }
  );
  await waitForIdle(page, { quietMs: 200, timeoutMs: 6_000, pollMs: 50 });
}

async function vaultTransfers(page) {
  const snapshot = await debugCall(page, "exchangeSimulatorSnapshot");
  return (snapshot?.calls ?? []).map((call) => call?.request?.action).filter((a) => a?.type === "vaultTransfer");
}

test.describe("fund an optimizer vault allocation", () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test("plans and sends withdrawals before deposits straight from the plan @regression", async ({ page }) => {
    await interceptAccountReads(page);
    await visitRoute(page, "/portfolio/optimize/draft");
    await seedSession(page);
    await seedVaultRun(page);
    const ok = { status: "ok", response: { type: "default" } };
    const refused = { status: "err", response: "Vault is not accepting deposits" };
    await debugCall(page, "installExchangeSimulator", {
      signedActions: { default: { responses: [ok, ok, refused, refused, refused] } }
    });

    const cta = role(page, "portfolio-optimizer-vault-funding-cta");
    await expect(cta).toHaveText(/Fund this allocation/);
    await cta.click();

    const sheet = role(page, "portfolio-optimizer-vault-funding-sheet");
    await expect(sheet).toBeVisible();
    await sheet.locator("[data-role='portfolio-optimizer-vault-funding-amount']").fill("4000");
    const amount = sheet.locator("[data-role='portfolio-optimizer-vault-funding-amount']");
    await amount.blur();
    await expect(amount).toHaveValue("4,000.00");

    // Beta holds 6,000 of 10,000 against a 30% target: 3,000 comes out and,
    // with the new 4,000, fills Alpha (5,000) and Gamma (2,000) exactly.
    await expect(role(page, "portfolio-optimizer-vault-funding-total-withdraw")).toHaveText("$3,000.00");
    await expect(role(page, "portfolio-optimizer-vault-funding-total-deposit")).toHaveText("$7,000.00");
    await expect(sheet.locator(`[data-address='${VB}']`)).toContainText("Withdraw $3,000.00");

    // Each row says when its money unlocks; the new positions lock for a day.
    await expect(sheet.locator(`[data-address='${VA}'] [data-role='portfolio-optimizer-vault-funding-lock']`)).toContainText("1 day lock-up");
    await expect(sheet.locator(`[data-address='${VB}'] [data-role='portfolio-optimizer-vault-funding-lock']`)).toContainText("Free now");

    // No review step: the button names what is sent.
    const send = role(page, "portfolio-optimizer-vault-funding-confirm");
    await expect(send).toHaveText("Withdraw $3,000.00 and deposit $7,000.00");
    await send.click();

    const progress = role(page, "portfolio-optimizer-vault-funding-progress");
    await expect(progress).toHaveAttribute("data-run-status", "partial", { timeout: 15_000 });
    const transfers = await vaultTransfers(page);
    expect(transfers.map((a) => [a.vaultAddress, a.isDeposit, a.usd])).toEqual([
      [VB, false, 3000000000],
      [VA, true, 5000000000],
      [VC, true, 2000000000]
    ]);
    await expect(progress.locator("[data-status='failed']")).toContainText("Vault is not accepting deposits");
    await expect(role(page, "portfolio-optimizer-vault-funding-retry")).toHaveText("Retry 1 transfer");
  });

  test("turning off existing deposits splits only the new cash @regression", async ({ page }) => {
    await interceptAccountReads(page);
    await visitRoute(page, "/portfolio/optimize/draft");
    await seedSession(page);
    await seedVaultRun(page);
    await role(page, "portfolio-optimizer-vault-funding-cta").click();
    await role(page, "portfolio-optimizer-vault-funding-amount").fill("1000");
    await role(page, "portfolio-optimizer-vault-funding-include-existing").click();
    await expect(role(page, "portfolio-optimizer-vault-funding-total-withdraw")).toHaveText("$0.00");
    await expect(role(page, "portfolio-optimizer-vault-funding-total-deposit")).toHaveText("$1,000.00");
    await expect(page.locator(`[data-address='${VB}']`)).toContainText("Deposit $300.00");
  });

  test("equal weight splits evenly across the picked vaults @regression", async ({ page }) => {
    await interceptAccountReads(page);
    await visitRoute(page, "/portfolio/optimize/draft");
    await seedSession(page);
    await seedVaultRun(page);
    await role(page, "portfolio-optimizer-vault-funding-cta").click();
    await role(page, "portfolio-optimizer-vault-funding-amount").fill("4000");
    await role(page, "portfolio-optimizer-vault-funding-split-equal").click();
    await expect(role(page, "portfolio-optimizer-vault-funding-split-equal")).toHaveAttribute("aria-checked", "true");
    // 10,000 split three ways: Beta's 6,000 is cut to 3,333.33.
    await expect(role(page, "portfolio-optimizer-vault-funding-total-withdraw")).toHaveText("$2,666.66");
    await expect(page.locator(`[data-address='${VA}']`)).toContainText("33.3%");
  });

  test("a run without vaults keeps Review trades @regression", async ({ page }) => {
    await visitRoute(page, "/portfolio/optimize/draft");
    await seedVaultRun(page, { allVault: false, account: false });
    await expect(role(page, "portfolio-optimizer-recommendation-tab")).toBeVisible();
    await expect(role(page, "portfolio-optimizer-vault-funding-cta")).toHaveCount(0);
    await expect(role(page, "portfolio-optimizer-recommendation-rebalance-cta")).toBeVisible();
  });
});
