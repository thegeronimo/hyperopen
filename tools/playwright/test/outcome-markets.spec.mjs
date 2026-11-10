import { expect, test } from "../support/guarded_test.mjs";
import { debugCall, dispatch, mobileViewport, oracle, visitRoute, waitForIdle } from "../support/hyperopen.mjs";
import {
  FED,
  FILLER_GAMES,
  GAME,
  IPO_NEVER,
  IPO_RANGE,
  IPO_WIDE,
  coin,
  feedWorldBooks,
  freezeAccountSync,
  installOutcomeWorld,
  openOutcomeTab,
  outcomeBalance,
  seedBalances,
  seedReadySession,
  simulatedOrders,
  usdcBalance,
  waitForCatalog
} from "../support/outcome_markets_world.mjs";

// The world these specs run in (see support/outcome_markets_world.mjs) is the
// live feed's awkward shape on purpose: the mark is never the book midpoint, a
// never-traded contract carries midPx 0.50 / prevDayPx "0.0" / no volume, and
// the books run from tight through 5-20 cents to wider than 20 cents. Every
// request is stubbed, orders go to the built-in exchange simulator only, and a
// request that reaches /exchange for real fails the test.

const GAME_TITLE = "Detroit Pistons vs Washington Wizards";
const FED_TITLE = "Fed — December 2026 decision";
const OK_ORDER = { status: "ok", response: { type: "order", data: { statuses: [{ resting: { oid: 777 } }] } } };

const settle = (page, quietMs = 250) => waitForIdle(page, { quietMs, timeoutMs: 6_000, pollMs: 50 });

/** Boot /trade on the stubbed world; optionally a ready wallet with balances. */
async function boot(page, { overrides = {}, failCatalog = false, balances = null, feed = true } = {}) {
  const world = await installOutcomeWorld(page);
  Object.assign(world.overrides, overrides);
  world.catalogFails(failCatalog);
  await visitRoute(page, "/trade");
  if (!failCatalog) {
    await waitForCatalog(page);
    // The seam that writes list/header top-of-book (see world file).
    if (feed) await feedWorldBooks(page, world);
  }
  if (balances) {
    // The simulator goes in first: a ready session arms its scheduleCancel
    // heartbeat the moment it exists, and that must not reach the network.
    await debugCall(page, "installExchangeSimulator", {
      signedActions: { default: { responses: [OK_ORDER, OK_ORDER, OK_ORDER, OK_ORDER] } }
    });
    await seedReadySession(page);
    await freezeAccountSync(page);
    await seedBalances(page, balances);
    await dispatch(page, [":actions/set-confirm-open-orders-enabled", false]);
    await settle(page, 150);
  }
  return world;
}

const listRow = (page, title) =>
  page.locator('[data-role="outcome-event-list"] [data-role="outcome-event-row"]').filter({ hasText: title });

const answer = (row, label) => row.locator('[data-role="outcome-answer"]').filter({ hasText: label });

/** Open the Outcome tab and pick `label` on the event row titled `title`. */
async function pickFromList(page, title, label) {
  await openOutcomeTab(page);
  await answer(listRow(page, title), label).first().click();
  await settle(page, 400);
  await expect(page.locator('[data-role="outcome-ticket"]')).toBeVisible();
}

const ticketOf = (page) => page.locator('[data-role="outcome-ticket"]');
const submitOf = (page) => ticketOf(page).locator('[data-parity-id="trade-submit-order-button"]');
const sideButton = (page, label) =>
  ticketOf(page).locator('[data-role="outcome-ticket-side"]').filter({ hasText: label });
const modeTab = (page, name) =>
  ticketOf(page).locator('[data-role="outcome-ticket-mode"]').getByRole("tab", { name, exact: true });
const orderForm = (page) => oracle(page, "order-form");
/** The header's Chance cell without its label. */
const headerChance = (page) =>
  page
    .locator('[data-role="outcome-header-chance"]')
    .innerText()
    .then((text) => text.replace(/^Chance\s*/, "").trim());

async function expectNoOrderSent(page, world) {
  expect(await simulatedOrders(page)).toHaveLength(0);
  expect(world.exchangeRequests).toHaveLength(0);
}

test("list: each book state reads honestly, most traded first, two ladders @regression", async ({ page }) => {
  const world = await boot(page);
  await openOutcomeTab(page);

  const list = page.locator('[data-role="outcome-event-list"]');
  await expect(page.locator('[data-role="outcome-sort-select"]')).toHaveValue("most-traded");

  await test.step("tight book shows a chance, never the mark", async () => {
    const game = listRow(page, GAME_TITLE);
    await expect(game).toHaveCount(1);
    await expect(game).toContainText(/DET\s*65%/);
    await expect(game).toContainText(/WAS\s*35%/);
    await expect(answer(game, "DET")).toHaveAttribute("data-quote-state", "live");
    // The mark says 58%; it is not a trade and must not surface.
    await expect(game).not.toContainText("58%");
  });

  await test.step("a never-traded contract says Not traded, not 50%", async () => {
    const never = listRow(page, "Acme Robotics IPO");
    await expect(never).toHaveCount(1);
    await expect(never).toContainText("Not traded");
    await expect(never).not.toContainText("50%");
    await expect(answer(never, "Yes")).toHaveAttribute("data-quote-state", "not-traded");
  });

  await test.step("a 5-20 cent book shows a muted range", async () => {
    const range = listRow(page, "Orbital Labs IPO");
    await expect(range).toContainText("40–50%");
    await expect(answer(range, "Yes")).toHaveAttribute("data-quote-state", "range");
    await expect(answer(range, "Yes").locator("span.text-ho-text-muted")).toHaveText("40–50%");
  });

  await test.step("a book wider than 20 cents says No market", async () => {
    const wide = listRow(page, "Nova Systems IPO");
    await expect(wide).toContainText("No market");
    await expect(answer(wide, "Yes")).toHaveAttribute("data-quote-state", "no-market");
  });

  await test.step("default order is most traded", async () => {
    const titles = await list.locator('[data-role="outcome-event-row"] .truncate.text-sm').allInnerTexts();
    expect(titles.slice(0, 3)).toEqual([GAME_TITLE, FED_TITLE, "Bitcoin above…"]);
    expect(titles.at(-1)).toBe("Acme Robotics IPO");
    const volumes = await list.locator('[data-role="outcome-event-row"]').evaluateAll((rows) =>
      rows.map((row) => row.querySelector(".num")?.textContent?.trim() ?? "")
    );
    const parse = (text) => {
      const m = /^([\d.]+)\s*([KM]?)/.exec(text);
      return m ? Number(m[1]) * (m[2] === "K" ? 1e3 : m[2] === "M" ? 1e6 : 1) : 0;
    };
    const numeric = volumes.map(parse);
    expect(numeric).toEqual([...numeric].sort((a, b) => b - a));
  });

  await test.step("rungs from two venues form two separate ladders", async () => {
    const ladders = list.locator('[data-role="outcome-event-row"]').filter({ hasText: "Bitcoin above…" });
    await expect(ladders).toHaveCount(2);
    const venues = await ladders.evaluateAll((rows) => rows.map((row) => row.getAttribute("title") ?? ""));
    expect(venues.filter((title) => title.includes("trade.xyz"))).toHaveLength(1);
    expect(venues.filter((title) => !title.includes("trade.xyz") && title.includes("out"))).toHaveLength(1);
    // Three rungs on `out`, two on trade.xyz: rungs are never merged across venues.
    const rungCounts = await ladders.evaluateAll((rows) =>
      rows.map((row) => row.querySelectorAll('[data-role="outcome-answer"]').length)
    );
    expect(rungCounts.sort()).toEqual([2, 3]);
  });
  expect(world.exchangeRequests).toHaveLength(0);
});

// The product's own feed: visible list rows subscribe `bbo` (top of book) for
// side 0 of each shown contract, and the projection writes it onto the
// catalog. No test feed here: a tight book must still read as a chance.
test("list rows get their own book from the product, no test feed @regression", async ({ page }) => {
  const world = await boot(page, { feed: false });
  await openOutcomeTab(page);
  const game = listRow(page, GAME_TITLE);
  await expect(game).toContainText(/DET\s*65%/, { timeout: 8_000 });
  expect(world.subscriptions.some((sub) => sub.type === "bbo" && sub.coin === coin(GAME, 0))).toBe(true);
  // One book serves both sides: side 1 is never subscribed on its own.
  expect(world.subscriptions.some((sub) => sub.type === "bbo" && sub.coin === coin(GAME, 1))).toBe(false);

  // A quiet book never pushes `bbo`; the one REST snapshot per subscription
  // seeds it, so the row reads a chance instead of a dash.
  const quiet = listRow(page, "Boston Celtics");
  await expect(quiet).toContainText(/BOS\s*51%/, { timeout: 8_000 });
  expect(world.subscriptions.some((sub) => sub.type === "bbo" && sub.coin === coin(FILLER_GAMES[0], 0))).toBe(true);
  expect(world.l2BookRequests.filter((c) => c === coin(FILLER_GAMES[0], 0))).toHaveLength(1);
  // An empty book is seeded as empty: Not traded, never a dash.
  await expect(listRow(page, "Acme Robotics IPO")).toContainText("Not traded");
});

test("one number: list row, header and ticket side agree for the same market @regression", async ({ page }) => {
  await boot(page);

  const cases = [
    { title: GAME_TITLE, label: "DET", list: /DET\s*65%/, header: /^DET 65% · 35% WAS$/, side: "65% chance", other: ["WAS", "35% chance"] },
    { title: FED_TITLE, label: "Cut", list: /Cut\s*31%/, header: /^31%$/, side: "31% chance", other: ["Any other answer", "69% chance"] },
    { title: "Orbital Labs IPO", label: "Yes", list: /Yes\s*40–50%/, header: /^40–50%$/, side: "40–50% chance", other: ["No", "50–60% chance"] }
  ];
  for (const item of cases) {
    await test.step(item.title, async () => {
      await openOutcomeTab(page);
      const row = listRow(page, item.title);
      await expect(row).toContainText(item.list);
      await answer(row, item.label).first().click();
      await settle(page, 400);

      await expect.poll(() => headerChance(page)).toMatch(item.header);
      await expect(sideButton(page, item.label)).toContainText(item.side);
      await expect(ticketOf(page).locator('[data-role="outcome-ticket-side"]').nth(1)).toContainText(item.other[1]);
    });
  }
});

test.describe("ticket with a position @regression", () => {
  const BALANCES = [usdcBalance("1000"), outcomeBalance(GAME, 0, 100, 50)];

  test("single clicks leave one consistent form and send nothing until submit", async ({ page }) => {
    const world = await boot(page, { balances: BALANCES });
    await pickFromList(page, GAME_TITLE, "DET");
    const shares = page.locator("#outcome-ticket-shares");
    const amount = page.locator("#outcome-ticket-amount");
    await expect(ticketOf(page)).toContainText("You hold 100 DET");

    await test.step("Cash out is a SELL of whole shares, never a $100 buy", async () => {
      await modeTab(page, "Cash out").click();
      await settle(page);
      await expect(modeTab(page, "Cash out")).toHaveAttribute("aria-selected", "true");
      await expect(shares).toHaveValue("100");
      await expect(amount).toHaveCount(0);
      expect(await orderForm(page)).toMatchObject({ side: "sell", sizeDisplay: "100" });
      // The form opens on Limit with no price: a sale that cannot be sent yet.
      await expect(submitOf(page)).toBeDisabled();
      await ticketOf(page).getByRole("tab", { name: "Market", exact: true }).click();
      await settle(page);
      await expect(submitOf(page)).toContainText("Cash out 100 DET");
      expect(await orderForm(page)).toMatchObject({ side: "sell", type: "market", sizeDisplay: "100" });
    });

    await test.step("share presets keep it a sell of whole shares", async () => {
      for (const [label, expected] of [["25%", "25"], ["50%", "50"], ["All · 100", "100"]]) {
        await ticketOf(page).locator('[data-role="outcome-ticket-shares"]').getByRole("button", { name: label, exact: true }).click();
        await settle(page, 150);
        await expect(shares).toHaveValue(expected);
        expect(await orderForm(page)).toMatchObject({ side: "sell", sizeDisplay: expected });
        await expect(submitOf(page)).toContainText(`${expected} DET`);
      }
    });

    await test.step("Buy returns to a dollar buy with an empty size", async () => {
      await modeTab(page, "Buy").click();
      await settle(page);
      await expect(modeTab(page, "Buy")).toHaveAttribute("aria-selected", "true");
      await expect(amount).toHaveValue("");
      expect(await orderForm(page)).toMatchObject({ side: "buy", sizeDisplay: "" });
      await ticketOf(page).locator('[data-role="outcome-ticket-amount"]').getByRole("button", { name: "$50", exact: true }).click();
      await settle(page, 150);
      await expect(amount).toHaveValue("50");
      expect(await orderForm(page)).toMatchObject({ side: "buy", sizeDisplay: "50" });
    });

    await test.step("a side button re-selects the side and leaves a buy form", async () => {
      await sideButton(page, "WAS").click();
      await settle(page);
      await expect(sideButton(page, "WAS")).toHaveAttribute("aria-pressed", "true");
      await expect(sideButton(page, "DET")).not.toHaveAttribute("aria-pressed", "true");
      const form = await orderForm(page);
      expect(form.activeAsset).toBe(coin(GAME, 1));
      expect(form.side).toBe("buy");
      await expect(modeTab(page, "Buy")).toHaveAttribute("aria-selected", "true");
      await expect(amount).toHaveValue(form.sizeDisplay ?? "");
    });

    await expectNoOrderSent(page, world);

    await test.step("submitting the cash out sends one SELL of 100 shares (stub only)", async () => {
      await sideButton(page, "DET").click();
      await settle(page);
      await modeTab(page, "Cash out").click();
      await settle(page);
      await expect(shares).toHaveValue("100");
      await expect(submitOf(page)).toBeEnabled();
      await submitOf(page).click();
      await expect.poll(async () => (await simulatedOrders(page)).length, { timeout: 10_000 }).toBe(1);
      const [action] = await simulatedOrders(page);
      expect(action.orders).toHaveLength(1);
      const [order] = action.orders;
      expect(order.b).toBe(false);
      expect(Number(order.s)).toBe(100);
      expect(world.exchangeRequests).toHaveLength(0);
    });
  });

  test("a blank limit price blocks submit and names what is missing", async ({ page }) => {
    const world = await boot(page, { balances: BALANCES });
    await pickFromList(page, GAME_TITLE, "DET");
    await modeTab(page, "Cash out").click();
    await settle(page);
    await ticketOf(page).getByRole("tab", { name: "Limit", exact: true }).click();
    await settle(page);

    const price = page.locator("#outcome-ticket-limit-price");
    await expect(price).toHaveValue("");
    await expect(page.locator("#outcome-ticket-shares")).toHaveValue("100");
    await expect(submitOf(page)).toHaveText("Enter a limit price");
    await expect(submitOf(page)).toBeDisabled();
    await submitOf(page).click({ force: true });
    await price.press("Enter");
    await settle(page, 150);
    await expectNoOrderSent(page, world);

    await price.fill("60");
    await settle(page);
    await expect(submitOf(page)).toBeEnabled();
    await expect(submitOf(page)).toContainText("100 DET at 60.000¢");
    await expectNoOrderSent(page, world);
  });

  test("a buy with a blank limit price is not submittable either", async ({ page }) => {
    const world = await boot(page, { balances: BALANCES });
    await pickFromList(page, GAME_TITLE, "DET");
    await ticketOf(page).getByRole("tab", { name: "Limit", exact: true }).click();
    await page.locator("#outcome-ticket-amount").fill("10");
    await settle(page);
    await expect(page.locator("#outcome-ticket-limit-price")).toHaveValue("");
    await expect(submitOf(page)).toBeDisabled();
    await expect(submitOf(page)).not.toContainText("¢");
    await expectNoOrderSent(page, world);
    await page.locator("#outcome-ticket-limit-price").fill("40");
    await settle(page);
    await expect(submitOf(page)).toBeEnabled();
    await expect(submitOf(page)).toContainText("at 40.000¢");
  });

  test("fees: a buy shows what you keep after settlement, a cash out shows fee and net", async ({ page }) => {
    await boot(page, { balances: BALANCES });
    await pickFromList(page, GAME_TITLE, "DET");
    const summary = ticketOf(page).locator('[data-role="outcome-ticket-summary"]');

    await ticketOf(page).getByRole("tab", { name: "Market", exact: true }).click();
    await ticketOf(page).locator('[data-role="outcome-ticket-amount"]').getByRole("button", { name: "$50", exact: true }).click();
    await settle(page);
    await expect(summary).toBeVisible();
    await expect(summary).toContainText("You keep if right");
    await expect(summary).toContainText(/Settlement fee \(0\.14%\)/);
    await expect(summary).toContainText(/Fee to buy\s*None/);
    await expect(summary).not.toContainText("You receive");

    await modeTab(page, "Cash out").click();
    await settle(page);
    await expect(summary).toContainText(/Fee \(0\.14%\)/);
    await expect(summary).toContainText("You receive");
    await expect(summary).not.toContainText("You keep if right");
    await expect(summary).not.toContainText("Fee to buy");
  });
});

test("stale prices need the confirmation: Enter or a click alone sends nothing @regression", async ({ page }) => {
  // Cut at 51% beside Hold at 61% and Hike at 7%: only one answer can win.
  const world = await boot(page, {
    balances: [usdcBalance("1000")],
    overrides: { [FED.cut]: { mark: 0.5, prev: 0.3, volume: 60000, book: { bid: 0.5, ask: 0.52 } } }
  });
  await pickFromList(page, FED_TITLE, "Cut");
  const ticket = ticketOf(page);
  await expect(ticket.locator('[data-role="outcome-ticket-stale-warning"]')).toContainText("Listed answers add up to 119%");
  const confirm = ticket.locator('[data-role="outcome-stale-confirm"]');
  await expect(confirm).not.toBeChecked();

  await ticket.getByRole("tab", { name: "Limit", exact: true }).click();
  await page.locator("#outcome-ticket-limit-price").fill("30");
  await page.locator("#outcome-ticket-amount").fill("30");
  await settle(page);
  await expect(submitOf(page)).toBeDisabled();

  await page.locator("#outcome-ticket-amount").press("Enter");
  await page.locator("#outcome-ticket-limit-price").press("Enter");
  await submitOf(page).click({ force: true });
  await settle(page, 200);
  await expectNoOrderSent(page, world);

  await confirm.check();
  await settle(page);
  await expect(submitOf(page)).toBeEnabled();
  await expectNoOrderSent(page, world);
  await submitOf(page).click();
  await expect.poll(async () => (await simulatedOrders(page)).length, { timeout: 10_000 }).toBe(1);
  expect(world.exchangeRequests).toHaveLength(0);
});

test("header picker and ticket rows agree on the selected answer @regression", async ({ page }) => {
  await boot(page);
  await pickFromList(page, FED_TITLE, "Hold");
  const trigger = page.locator('[data-role="outcome-option-select-trigger"]');
  const options = ticketOf(page).locator('[data-role="outcome-ticket-options"]');
  const chanceOf = (label) =>
    options.locator('[data-role="outcome-ticket-option"]').filter({ hasText: label }).locator(".num").first();
  await expect(trigger).toContainText("Hold");

  await test.step("pick Hike from the header picker", async () => {
    await trigger.click();
    await page
      .locator('[data-role="outcome-option-select-menu"] [role="option"]')
      .filter({ hasText: "Hike" })
      .first()
      .click();
    await settle(page, 400);
    await expect(trigger).toContainText("Hike");
    await expect(sideButton(page, "Hike")).toHaveAttribute("aria-pressed", "true");
    await expect(submitOf(page)).toContainText("Buy Yes on Hike");
    await expect.poll(() => headerChance(page)).toBe("7%");
    await expect(chanceOf("Hike")).toHaveText("7%");
  });

  await test.step("pick No on Cut from the ticket rows", async () => {
    await options.getByRole("button", { name: /^Buy No on Cut at/ }).click();
    await settle(page, 400);
    await expect(trigger).toContainText("Cut");
    await expect(sideButton(page, "Any other answer")).toHaveAttribute("aria-pressed", "true");
    await expect(submitOf(page)).toContainText("Buy No on Cut");
  });

  await test.step("pick Yes on Hold from the ticket rows", async () => {
    await options.getByRole("button", { name: /^Buy Yes on Hold at/ }).click();
    await settle(page, 400);
    await expect(trigger).toContainText("Hold");
    await expect(submitOf(page)).toContainText("Buy Yes on Hold");
    await expect.poll(() => headerChance(page)).toBe("61%");
  });
});

test("layout: nine full list rows and an untruncated multi-answer name at 1440x900 @regression", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await boot(page);
  await openOutcomeTab(page);

  const full = await page.locator('[data-role="outcome-event-list"]').evaluate((list) => {
    const box = list.getBoundingClientRect();
    const rows = [...list.querySelectorAll('[data-role="outcome-event-row"]')];
    const inside = rows.filter((row) => {
      const r = row.getBoundingClientRect();
      return r.top >= box.top - 0.5 && r.bottom <= box.bottom + 0.5 && r.bottom <= window.innerHeight + 0.5;
    });
    return { visible: inside.length, total: rows.length, listHeight: Math.round(box.height) };
  });
  expect(full.total).toBeGreaterThanOrEqual(9);
  expect(full.visible, `list is ${full.listHeight}px tall`).toBeGreaterThanOrEqual(9);

  await answer(listRow(page, FED_TITLE), "Hold").first().click();
  await settle(page, 400);
  const header = page.locator('[data-role="outcome-header"]');
  await expect(header).toHaveAttribute("data-layout", "two-line");
  const name = await header.locator('[data-role="outcome-market-name-hover-region"]').evaluate((region, title) => {
    const holders = [...region.querySelectorAll("*")].filter(
      (el) => el.offsetParent !== null && [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim() === title)
    );
    return holders.map((el) => {
      const chain = [];
      for (let node = el; node && node !== region; node = node.parentElement) {
        chain.push({ tag: node.tagName, scroll: node.scrollWidth, client: node.clientWidth, overflow: getComputedStyle(node).textOverflow });
      }
      return chain;
    });
  }, FED_TITLE);
  expect(name.length, "the event name is rendered in the header").toBeGreaterThan(0);
  for (const chain of name) {
    for (const link of chain) {
      expect(link.scroll, JSON.stringify(chain)).toBeLessThanOrEqual(link.client + 1);
    }
  }
});

test.describe("phone layout @mobile", () => {
  test.use(mobileViewport);

  test("Buy and the ticket sheet's Buy stay in the viewport; Escape closes @regression", async ({ page }) => {
    await boot(page);
    await dispatch(page, [":actions/select-asset", coin(GAME, 0)]);
    await settle(page, 400);

    const inViewport = async (locator) => {
      const box = await locator.boundingBox();
      const size = page.viewportSize();
      expect(box, "element is rendered").not.toBeNull();
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.y).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(size.width + 0.5);
      expect(box.y + box.height).toBeLessThanOrEqual(size.height + 0.5);
    };

    const buy = page.locator('[data-role="outcome-mobile-buy"]');
    await expect(buy).toBeVisible();
    await inViewport(buy);

    await buy.click();
    const sheet = page.locator('[data-role="outcome-ticket-sheet"]');
    await expect(sheet).toBeVisible();
    await expect(sheet.locator('[data-role="outcome-ticket"]')).toBeVisible();
    const sheetBuy = sheet.locator('[data-parity-id="trade-submit-order-button"]');
    await expect(sheetBuy).toBeVisible();
    await inViewport(sheetBuy);
    await inViewport(sheet);

    await page.keyboard.press("Escape");
    await expect(sheet).toHaveCount(0);
    await expect(buy).toBeVisible();
    await inViewport(buy);
  });
});

test("a failed catalog load shows an error with Retry, not No events found @regression", async ({ page }) => {
  const world = await boot(page, { failCatalog: true });
  await openOutcomeTab(page);

  // The app retries the catalog before it gives up; the error appears after that.
  const alert = page.locator('[data-role="outcome-event-list"], [data-role="outcome-sort-row"]').locator("xpath=..").getByRole("alert");
  await expect(alert.first()).toBeVisible({ timeout: 25_000 });
  const retry = alert.getByRole("button", { name: "Retry" });
  await expect(retry).toBeVisible();
  await expect(page.getByText("No events found")).toHaveCount(0);
  // With nothing to show, the error should say the load failed rather than
  // claim a "last list" (a stale-list banner over an empty list).
  expect.soft(await page.locator('[data-role="outcome-load-error"]').count(), "full error state for an empty catalog").toBe(1);

  world.catalogFails(false);
  await retry.click();
  await expect(listRow(page, GAME_TITLE)).toHaveCount(1, { timeout: 20_000 });
  await expect(alert).toHaveCount(0);
});
