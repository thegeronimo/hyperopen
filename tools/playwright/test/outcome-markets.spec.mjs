import { expect, test } from "../support/guarded_test.mjs";
import { dispatch, visitRoute, waitForIdle } from "../support/hyperopen.mjs";

// Outcome contracts built from deployer templates, shaped like the live
// `outcomeMeta` feed (names are template keys, words live in the params).
// Ids are invented so no live websocket data can move the numbers asserted.
const GAME = 990001;
const FED = { question: 99002, fallback: 990020, hold: 990021, cut: 990022, hike: 990023 };
const DAY = 24 * 60 * 60 * 1000;

function utcStamp(ms) {
  const d = new Date(ms);
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}-${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}`;
}

function outcomeMeta(now) {
  const start = utcStamp(now + 6 * 60 * 60 * 1000);
  const deadline = utcStamp(now + 7 * DAY);
  const yesNo = [{ name: "Yes" }, { name: "No" }];
  return {
    outcomes: [
      {
        outcome: GAME,
        name: "template:sportsContestWinner",
        description: [
          "competition:NBA",
          "contestType:Game",
          "countedPlay:the full game including overtime",
          "officialSource:National Basketball Association (NBA)",
          "participantA:Detroit Pistons",
          "participantB:Washington Wizards",
          `resolutionDeadline:${deadline}`,
          `scheduledStart:${start}`,
          "shortNameA:DET",
          "shortNameB:WAS",
          "sport:Basketball"
        ].join("|"),
        sideSpecs: [{ name: "template:{shortNameA}" }, { name: "template:{shortNameB}" }],
        quoteToken: "USDC",
        venue: "out"
      },
      { outcome: FED.fallback, name: "template fallback", description: "other", sideSpecs: yesNo, quoteToken: "USDC", venue: "out" },
      { outcome: FED.hold, name: "template:policyRateNoChange", description: "", sideSpecs: yesNo, quoteToken: "USDC", venue: "out" },
      { outcome: FED.cut, name: "template:policyRateDecrease", description: "", sideSpecs: yesNo, quoteToken: "USDC", venue: "out" },
      { outcome: FED.hike, name: "template:policyRateIncrease", description: "", sideSpecs: yesNo, quoteToken: "USDC", venue: "out" }
    ],
    questions: [
      {
        question: FED.question,
        name: "template:policyRateDecision",
        description: [
          `decisionDeadline:${utcStamp(now + 60 * DAY)}`,
          "decisionLabel:December 2026",
          "institution:Federal Open Market Committee",
          "officialSource:Federal Open Market Committee",
          "policyMeasure:the upper bound of the target range for the federal funds rate",
          `scheduledDecision:${utcStamp(now + 30 * DAY)}`
        ].join("|"),
        fallbackOutcome: FED.fallback,
        namedOutcomes: [FED.hold, FED.cut, FED.hike],
        settledNamedOutcomes: []
      }
    ]
  };
}

function ctx(outcome, side, mark, prev, volume) {
  return {
    coin: `#${outcome * 10 + side}`,
    markPx: String(mark),
    midPx: String(mark),
    prevDayPx: String(prev),
    dayNtlVlm: String(volume),
    dayBaseVlm: String(volume),
    circulatingSupply: "33333.0",
    totalSupply: "184467440737095.53125"
  };
}

const OUTCOME_CTXS = [
  ctx(GAME, 0, 0.82, 0.818, 180000),
  ctx(GAME, 1, 0.18, 0.182, 40000),
  ctx(FED.fallback, 0, 0.5, 0, 0),
  ctx(FED.fallback, 1, 0.5, 0, 0),
  ctx(FED.hold, 0, 0.18, 0.2, 9000),
  ctx(FED.hold, 1, 0.82, 0.8, 9000),
  ctx(FED.cut, 0, 0.02, 0.02, 500),
  ctx(FED.cut, 1, 0.98, 0.98, 500),
  ctx(FED.hike, 0, 0.8, 0.78, 20000),
  ctx(FED.hike, 1, 0.2, 0.22, 20000)
];
// The fallback ("Other") never traded: the feed's placeholder pattern.
OUTCOME_CTXS[2].midPx = null;
OUTCOME_CTXS[3].midPx = null;

async function stubOutcomeFeed(page) {
  const now = Date.now();
  await page.route("https://api.hyperliquid.xyz/info", async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    if (payload?.type === "outcomeMeta") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(outcomeMeta(now)) });
      return;
    }
    if (payload?.type === "spotMetaAndAssetCtxs") {
      const response = await route.fetch();
      const [spotMeta, ctxs] = await response.json();
      const live = (ctxs || []).filter((entry) => !String(entry?.coin || "").startsWith("#"));
      await route.fulfill({
        response,
        contentType: "application/json",
        body: JSON.stringify([spotMeta, [...live, ...OUTCOME_CTXS]])
      });
      return;
    }
    await route.continue();
  });
}

async function waitForCatalog(page) {
  await expect.poll(async () => page.evaluate(() => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;
    if (!c || !store) return false;
    const path = c.PersistentVector.fromArray([c.keyword("asset-selector"), c.keyword("market-by-key")], true);
    const byKey = c.get_in(c.deref(store), path);
    return Boolean(byKey && c.get(byKey, `outcome:${990001}`));
  }), { timeout: 30_000 }).toBe(true);
}

async function openOutcomeTab(page) {
  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.getByRole("button", { name: "Outcome", exact: true }).first().click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

test("outcome tab lists readable events and an answer opens the ticket on that side @regression", async ({ page }) => {
  await stubOutcomeFeed(page);
  await visitRoute(page, "/trade");
  await waitForCatalog(page);
  await openOutcomeTab(page);

  const list = page.locator('[data-role="outcome-event-list"]');
  const game = list.locator('[data-role="outcome-event-row"]').filter({ hasText: "Detroit Pistons vs Washington Wizards" });
  await expect(game).toHaveCount(1);
  await expect(game).toContainText(/DET\s*82%/);
  await expect(game).toContainText(/WAS\s*18%/);
  await expect(game).toContainText(/Starts in \d+h/);
  await expect(list).not.toContainText("template");

  await game.locator('[data-role="outcome-answer"]').filter({ hasText: "WAS" }).click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 6_000, pollMs: 50 });

  const header = page.locator('[data-role="outcome-header"]');
  await expect(header).toContainText("Sports · NBA");
  await expect(header).toContainText(/DET 82% · 18% WAS/);

  const ticket = page.locator('[data-role="outcome-ticket"]');
  const was = ticket.locator('[data-role="outcome-ticket-side"]').filter({ hasText: "WAS" });
  await expect(was).toHaveAttribute("aria-pressed", "true");
  await expect(ticket).toContainText("Pick a side");

  await ticket.getByRole("tab", { name: "Limit", exact: true }).click();
  await page.locator("#outcome-ticket-limit-price").fill("20");
  await page.locator("#outcome-ticket-amount").fill("10");
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  const summary = ticket.locator('[data-role="outcome-ticket-summary"]');
  await expect(summary).toContainText("50 WAS");
  await expect(summary).toContainText("Pays if WAS wins");
  await expect(summary).toContainText("$50.00");
  await expect(ticket).toContainText("Place limit · 50 WAS at 20.000¢");
});

test("pick-one events list every answer with an honest Other line @regression", async ({ page }) => {
  await stubOutcomeFeed(page);
  await visitRoute(page, "/trade");
  await waitForCatalog(page);
  await openOutcomeTab(page);

  await page.locator('[data-role="outcome-category-chip"]').filter({ hasText: /^Economics/ }).click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  const fed = page.locator('[data-role="outcome-event-row"]').filter({ hasText: "Fed — December 2026 decision" });
  await expect(fed).toHaveCount(1);
  await expect(fed).toContainText(/Hike\s*80%/);

  await fed.locator('[data-role="outcome-answer"]').filter({ hasText: "Hold" }).click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 6_000, pollMs: 50 });

  const options = page.locator('[data-role="outcome-ticket-options"]');
  await expect(options.locator('[data-role="outcome-ticket-option"]')).toHaveCount(3);
  await expect(options.locator('[data-role="outcome-ticket-option"]').first()).toContainText("Hike");
  await expect(options.locator('[data-role="outcome-ticket-other"]')).toContainText("Not traded");
  await expect(page.locator('[data-role="outcome-ticket"]')).toContainText("Buy Yes on Hold");
});
