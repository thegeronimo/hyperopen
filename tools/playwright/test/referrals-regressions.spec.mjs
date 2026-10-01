import { expect, test } from "../support/guarded_test.mjs";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";

const ownerAddress = "0x1234567890abcdef1234567890abcdef12345678";
const spectateAddress = "0x999e9a397b703d68af21113abededd827b309068";

const liveReadyPayload = {
  tokenToState: [
    [
      0,
      {
        cumVlm: "5034741.3799999999",
        unclaimedRewards: "0.08258155",
        claimedRewards: "208.64995482",
        builderRewards: "0.0"
      }
    ],
    [
      235,
      {
        cumVlm: "0.0",
        unclaimedRewards: "0.00081898",
        claimedRewards: "0.0",
        builderRewards: "0.0"
      }
    ],
    [
      360,
      {
        cumVlm: "680.37",
        unclaimedRewards: "0.82911511",
        claimedRewards: "0.0",
        builderRewards: "0.0"
      }
    ]
  ],
  referrerState: {
    stage: "ready",
    data: {
      code: "MYCODE",
      nReferrals: 6,
      referralStates: [
        {
          user: "0xb4802e7c9d966ad98106edc14a9bfe97e800098",
          cumVlm: "6226785.1799999997",
          cumRewardedFeesSinceReferred: "4428.91413651",
          cumFeesRewardedToReferrer: "187.35791924",
          timeJoined: 1761012412763
        },
        {
          user: "0x37baedaa536f7144e72915c683e6095177d3e7e8",
          cumVlm: "1133032.6599999999",
          cumRewardedFeesSinceReferred: "730.51948073",
          cumFeesRewardedToReferrer: "19.86966653",
          timeJoined: 1764625665863
        },
        {
          user: "0x8a19694c2f5e721103da1b6b6cc0bc692fafd8ad",
          cumVlm: "40724.06",
          cumRewardedFeesSinceReferred: "61.14729245",
          cumFeesRewardedToReferrer: "1.12201504",
          timeJoined: 1760709224945
        },
        {
          user: "0xf2484db7f0e27f88659e41679e3d1765ac9fc2bd",
          cumVlm: "8914.11",
          cumRewardedFeesSinceReferred: "10.23690749",
          cumFeesRewardedToReferrer: "0.34230256",
          timeJoined: 1765568181361
        },
        {
          user: "0x43b299a16fff6036d136852afbc373098ce977b7",
          cumVlm: "1406.89",
          cumRewardedFeesSinceReferred: "0.406336",
          cumFeesRewardedToReferrer: "0.040633",
          timeJoined: 1763614157329
        },
        {
          user: "0x623cca9b9a93a58b989350a0f76a0ec1ea110c96",
          cumVlm: "0.0",
          cumRewardedFeesSinceReferred: "0.0",
          cumFeesRewardedToReferrer: "0.0",
          timeJoined: 1765221864482
        }
      ]
    }
  }
};

async function interceptReferralApi(page, raw) {
  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    if (payload?.type === "referral") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(raw)
      });
      return;
    }

    await route.continue();
  });
}

async function seedReferralsState(page, options = {}) {
  await page.evaluate(({ owner, referralState, spectate }) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;

    if (!c || !store) {
      throw new Error("Hyperopen store or cljs core unavailable");
    }

    const keyword = c.keyword;
    const kwPath = (...segments) =>
      c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
    const opts = c.PersistentArrayMap.fromArray([keyword("keywordize-keys"), true], true);
    const raw = c.js__GT_clj(referralState.raw, opts);
    const ui = c.js__GT_clj(referralState.ui, opts);

    let nextState = c.deref(store);
    nextState = c.assoc_in(nextState, kwPath("wallet", "address"), owner);
    nextState = c.assoc_in(nextState, kwPath("wallet", "connected?"), true);
    nextState = c.assoc_in(
      nextState,
      kwPath("account-context", "spectate-mode", "active?"),
      Boolean(spectate)
    );
    nextState = c.assoc_in(
      nextState,
      kwPath("account-context", "spectate-mode", "address"),
      spectate ?? null
    );
    nextState = c.assoc_in(nextState, kwPath("referrals", "raw"), raw);
    nextState = c.assoc_in(nextState, kwPath("referrals", "loading?"), false);
    nextState = c.assoc_in(nextState, kwPath("referrals", "error"), null);
    nextState = c.assoc_in(nextState, kwPath("referrals-ui"), ui);
    c.reset_BANG_(store, nextState);

    const renderApp = globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_;
    if (typeof renderApp === "function") {
      renderApp(c.deref(store));
    }
  }, {
    owner: ownerAddress,
    spectate: options.spectate ?? null,
    referralState: {
      raw: options.raw ?? {
        tokenToState: {
          USDC: {
            unclaimedRewards: "3.5",
            claimedRewards: "1.5"
          }
        },
        referrerState: {
          stage: options.stage ?? "needToCreateCode",
          data: options.data ?? {}
        }
      },
      ui: options.ui ?? {
        "active-tab": "referrals",
        "active-modal": null,
        "last-error": null,
        "submitting?": null,
        form: {
          code: "",
          "new-code": ""
        }
      }
    }
  });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

test("referrals route opens modals, validates code, and switches tabs @regression", async ({ page }) => {
  await interceptReferralApi(page, {
    referrerState: {
      stage: "needToCreateCode",
      data: {}
    }
  });
  await visitRoute(page, "/referrals");
  await seedReferralsState(page);

  await expect(page.locator("[data-parity-id='referrals-root']")).toBeVisible();
  await expect(page.locator("[data-role='referrals-open-enter-code']")).toBeVisible();
  await expect(page.locator("[data-role='referrals-open-create-code']")).toBeVisible();

  await page.locator("[data-role='referrals-open-enter-code']").click();
  await expect(page.locator("[data-role='referrals-modal']")).toBeVisible();
  await expect(page.locator("[data-role='referrals-modal-title']")).toHaveText("Enter Referral Code");
  await page.locator("[data-role='referrals-modal-submit']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(page.locator("[data-role='referrals-modal-error']")).toContainText("Enter a valid referral code.");

  await page.locator("[data-role='referrals-modal-code-input']").fill("friend1");
  await expect(page.locator("[data-role='referrals-modal-normalized-code']")).toHaveText("FRIEND1");
  await page.locator("[data-role='referrals-modal-close']").click();
  await expect(page.locator("[data-role='referrals-modal']")).toHaveCount(0);

  await page.locator("[data-role='referrals-open-create-code']").click();
  await expect(page.locator("[data-role='referrals-modal-title']")).toHaveText("Create Referral Code");
  await page.locator("[data-role='referrals-modal-new-code-input']").fill("mycode");
  await expect(page.locator("[data-role='referrals-modal-new-code-input']")).toHaveValue("mycode");
  await page.locator("[data-role='referrals-modal-close']").click();

  await page.locator("[data-role='referrals-tab-legacy-reward-history']").click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(page.locator("[data-role='referrals-legacy-empty']")).toHaveText("No rewards earned");
});

test("referrals ready state exposes share and claim modal flows @regression", async ({ page }) => {
  await interceptReferralApi(page, liveReadyPayload);
  await visitRoute(page, "/referrals");
  await seedReferralsState(page, {
    raw: liveReadyPayload,
    stage: "ready",
    data: {
      code: "MYCODE",
      nReferrals: 2,
      referralStates: [
        {
          user: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
          cumVlm: "6226785.1799999997",
          cumRewardedFeesSinceReferred: "4428.91413651",
          cumFeesRewardedToReferrer: "187.35791924",
          timeJoined: 1761012412763
        }
      ]
    }
  });

  await expect(page.locator("[data-role='referrals-own-code']")).toHaveText("MYCODE");
  await expect(page.locator("[data-role='referrals-join-link']")).toHaveText("/join/MYCODE");
  await expect(page.locator("[data-role='referrals-stat-traders']")).toContainText("6");
  await expect(page.locator("[data-role='referrals-stat-rewards']")).toContainText("$209.56");
  await expect(page.locator("[data-role='referrals-stat-claimable']")).toContainText("$0.91");
  await expect(page.locator("[data-role='referrals-rewards-panel']")).toHaveCount(0);
  const rowCells = page.locator("[data-role='referrals-row']").first().locator("> span");
  await expect(rowCells.nth(0)).toHaveText(/0xb480.*0098/);
  await expect(rowCells.nth(1)).toHaveText("10/20/2025 - 22:06:52");
  await expect(rowCells.nth(2)).toHaveText("$6,226,785.18");
  await expect(rowCells.nth(3)).toHaveText("$4,428.91");
  await expect(rowCells.nth(4)).toHaveText("$187.36");

  const claimButtonBox = await page.locator("[data-role='referrals-open-claim-rewards']").boundingBox();
  const tableBox = await page.locator("[data-role='referrals-table-panel']").boundingBox();
  expect(claimButtonBox).not.toBeNull();
  expect(tableBox).not.toBeNull();
  expect(claimButtonBox.y).toBeLessThan(tableBox.y);

  await page.locator("[data-role='referrals-open-share-code']").click();
  await expect(page.locator("[data-role='referrals-modal-title']")).toHaveText("Share Referral Code");
  await expect(page.locator("[data-role='referrals-modal-join-link']")).toHaveText("/join/MYCODE");
  await page.locator("[data-role='referrals-modal-submit']").click();
  await expect(page.locator("[data-role='referrals-modal']")).toHaveCount(0);

  await page.locator("[data-role='referrals-open-claim-rewards']").click();
  await expect(page.locator("[data-role='referrals-modal-title']")).toHaveText("Claim Rewards");
  await expect(page.locator("[data-role='referrals-modal-claim-total']")).toHaveText("$0.91");
  await expect(page.locator("[data-role='referrals-modal-claim-row']").first()).toContainText("USDC");
});

test("referrals table columns sort rows and expose wider date column @regression", async ({ page }) => {
  await interceptReferralApi(page, liveReadyPayload);
  await visitRoute(page, "/referrals");
  await seedReferralsState(page, {
    raw: liveReadyPayload
  });

  const rows = page.locator("[data-role='referrals-row']");
  await expect(rows).toHaveCount(6);
  await expect(rows.first().locator("> span").first()).toHaveText(/0xb480.*0098/);
  await expect(page.locator("[data-role='referrals-sort-total-volume']")).toHaveAttribute("aria-sort", "descending");

  const headerGrid = page.locator("[data-role='referrals-table-header']");
  await expect(headerGrid).toBeVisible();
  const gridColumns = await headerGrid.evaluate((node) => getComputedStyle(node).gridTemplateColumns);
  const firstColumns = gridColumns.split(" ").slice(0, 2).map((value) => Number.parseFloat(value));
  expect(firstColumns[1]).toBeGreaterThan(180);

  await page.locator("[data-role='referrals-sort-date-joined']").click();
  await expect(page.locator("[data-role='referrals-sort-date-joined']")).toHaveAttribute("aria-sort", "descending");
  await expect(rows.first().locator("> span").first()).toHaveText(/0xf248.*c2bd/);

  await page.locator("[data-role='referrals-sort-date-joined']").click();
  await expect(page.locator("[data-role='referrals-sort-date-joined']")).toHaveAttribute("aria-sort", "ascending");
  await expect(rows.first().locator("> span").first()).toHaveText(/0x8a19.*d8ad/);

  await page.locator("[data-role='referrals-sort-your-rewards']").click();
  await expect(page.locator("[data-role='referrals-sort-your-rewards']")).toHaveAttribute("aria-sort", "descending");
  await expect(rows.first().locator("> span").first()).toHaveText(/0xb480.*0098/);
});

test("referrals spectate mode populates live reward pair stats @regression", async ({ page }) => {
  await interceptReferralApi(page, liveReadyPayload);
  await visitRoute(page, "/referrals");
  await seedReferralsState(page, {
    raw: liveReadyPayload,
    spectate: spectateAddress
  });

  await expect(page.locator("[data-role='referrals-read-only']")).toContainText("Spectate Mode is read-only");
  await expect(page.locator("[data-role='referrals-stat-traders']")).toContainText("6");
  await expect(page.locator("[data-role='referrals-stat-rewards']")).toContainText("$209.56");
  await expect(page.locator("[data-role='referrals-stat-claimable']")).toContainText("$0.91");
  await expect(page.locator("[data-role='referrals-open-claim-rewards']")).toBeDisabled();
});

test("join route preserves normalized code and requires confirmation @regression", async ({ page }) => {
  await visitRoute(page, "/join/friend1");

  await expect(page.locator("[data-parity-id='referrals-root']")).toBeVisible();
  await expect(page.locator("[data-role='referrals-modal']")).toBeVisible();
  await expect(page.locator("[data-role='referrals-modal-title']")).toHaveText("Confirm Referral Code");
  await expect(page.locator("[data-role='referrals-modal-normalized-code']")).toHaveText("FRIEND1");
  await expect(page.locator("[data-role='referrals-modal-code-input']")).toHaveValue("FRIEND1");
  await expect(page.locator("[data-role='referrals-modal-submit']")).toBeDisabled();
});

test("referrals layout contains core modal controls at review widths @regression", async ({ page }) => {
  await interceptReferralApi(page, {
    referrerState: {
      stage: "needToCreateCode",
      data: {}
    }
  });
  for (const width of [375, 768, 1280, 1440]) {
    await page.setViewportSize({ width, height: 760 });
    await visitRoute(page, "/referrals");
    await seedReferralsState(page);

    await expect(page.locator("[data-parity-id='referrals-root']")).toBeVisible();
    await page.locator("[data-role='referrals-open-enter-code']").click();
    await expect(page.locator("[data-role='referrals-modal']")).toBeVisible();

    const modalBox = await page.locator("[data-role='referrals-modal']").boundingBox();
    expect(modalBox).not.toBeNull();
    expect(modalBox.x).toBeGreaterThanOrEqual(0);
    expect(modalBox.x + modalBox.width).toBeLessThanOrEqual(width);
    await expect(page.locator("[data-role='referrals-modal-code-input']")).toBeVisible();
    await expect(page.locator("[data-role='referrals-modal-submit']")).toBeVisible();
    await page.locator("[data-role='referrals-modal-close']").click();
    await expect(page.locator("[data-role='referrals-modal']")).toHaveCount(0);
  }
});
