// A realistic, fully stubbed outcome-markets world for the Playwright specs.
//
// Nothing here reaches the live venue: `/info` is answered locally, `/exchange`
// is guarded (a real request fails the test), the websocket is owned by
// `page.routeWebSocket` and answers `l2Book` subscriptions from `BOOKS`.
//
// The feed is deliberately unkind. `markPx` is never the book midpoint (a mark
// is not a trade), a never-traded contract carries the live placeholder
// (`midPx` 0.50, `prevDayPx` "0.0", no volume), and the books run from tight to
// wide. Ids are invented so no live websocket data can move the numbers.
import { dispatch, waitForIdle } from "./hyperopen.mjs";

export const DAY = 24 * 60 * 60 * 1000;
export const HL_INFO_URL = "https://api.hyperliquid.xyz/info";
export const HL_EXCHANGE_URL = "https://api.hyperliquid.xyz/exchange";
export const HL_WS_URL = "wss://api.hyperliquid.xyz/ws";
export const WALLET = "0x1111111111111111111111111111111111111111";

// Contract ids. A side coin is `#<outcome * 10 + side>`.
export const GAME = 990001; // head-to-head, tight book
export const FED = { question: 99002, fallback: 990020, hold: 990021, cut: 990022, hike: 990023 };
export const IPO_NEVER = 990030; // never traded: placeholder quotes
export const IPO_RANGE = 990031; // 10c spread
export const IPO_WIDE = 990032; // 40c spread
export const LADDER_OUT = [990040, 990041, 990042]; // venue `out`
export const LADDER_TXYZ = [990050, 990051]; // venue `txyz`
export const FILLER_GAMES = [990060, 990061, 990062];
export const FILLER_RUNGS = [990070, 990071];

export const coin = (outcome, side) => `#${outcome * 10 + side}`;
export const wireCoin = (outcome, side) => `+${outcome * 10 + side}`;

function utcStamp(ms) {
  const d = new Date(ms);
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}-${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}`;
}

const YES_NO = [{ name: "Yes" }, { name: "No" }];

function outcome(id, name, description, extra = {}) {
  return { outcome: id, name, description, sideSpecs: YES_NO, quoteToken: "USDC", venue: "out", ...extra };
}

function game(id, a, b, shortA, shortB, startMs, deadlineMs) {
  return {
    outcome: id,
    name: "template:sportsContestWinner",
    description: [
      "competition:NBA",
      "contestType:Game",
      "countedPlay:the full game including overtime",
      "officialSource:National Basketball Association (NBA)",
      `participantA:${a}`,
      `participantB:${b}`,
      `resolutionDeadline:${utcStamp(deadlineMs)}`,
      `scheduledStart:${utcStamp(startMs)}`,
      `shortNameA:${shortA}`,
      `shortNameB:${shortB}`,
      "sport:Basketball"
    ].join("|"),
    sideSpecs: [{ name: "template:{shortNameA}" }, { name: "template:{shortNameB}" }],
    quoteToken: "USDC",
    venue: "out"
  };
}

function ipo(id, company) {
  return outcome(id, "template:companyIpoConfirmed", `company:${company}|dateTime:${utcStamp(Date.now() + 40 * DAY)}`);
}

function rung(id, subject, threshold, timeMs, venue) {
  return outcome(
    id,
    "template:binaryPrice",
    [
      `perp:${subject}`,
      `threshold:${threshold}`,
      `time:${utcStamp(timeMs)}`,
      "priceDescription:the BTC mark price",
      "seconds:60"
    ].join("|"),
    { venue }
  );
}

export function outcomeMeta(now) {
  const soon = now + 6 * 60 * 60 * 1000;
  const deadline = now + 7 * DAY;
  const settle = now + 9 * DAY;
  return {
    outcomes: [
      game(GAME, "Detroit Pistons", "Washington Wizards", "DET", "WAS", soon, deadline),
      outcome(FED.fallback, "template fallback", "other"),
      outcome(FED.hold, "template:policyRateNoChange", ""),
      outcome(FED.cut, "template:policyRateDecrease", ""),
      outcome(FED.hike, "template:policyRateIncrease", ""),
      ipo(IPO_NEVER, "Acme Robotics"),
      ipo(IPO_RANGE, "Orbital Labs"),
      ipo(IPO_WIDE, "Nova Systems"),
      ...LADDER_OUT.map((id, i) => rung(id, "BTC", 78000 + i * 2000, settle, "out")),
      ...LADDER_TXYZ.map((id, i) => rung(id, "BTC", 78000 + i * 2000, settle, "txyz")),
      game(FILLER_GAMES[0], "Boston Celtics", "Miami Heat", "BOS", "MIA", soon + 3600_000, deadline),
      game(FILLER_GAMES[1], "Denver Nuggets", "Utah Jazz", "DEN", "UTA", soon + 7200_000, deadline),
      game(FILLER_GAMES[2], "Phoenix Suns", "Dallas Mavericks", "PHX", "DAL", soon + 10800_000, deadline),
      rung(FILLER_RUNGS[0], "ETH", 3600, settle + DAY, "out"),
      rung(FILLER_RUNGS[1], "SOL", 190, settle + 2 * DAY, "out")
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

// One entry per contract: the asset context the feed publishes (`mark` is NOT
// the midpoint of the book on purpose) and the side-0 book (`null` = empty).
// Volumes are shares per contract (mirrored across both sides).
const CONTRACTS = {
  [GAME]: { mark: 0.58, prev: 0.6, volume: 180000, book: { bid: 0.64, ask: 0.66 } },
  [FED.hold]: { mark: 0.55, prev: 0.5, volume: 90000, book: { bid: 0.6, ask: 0.62 } },
  [FED.cut]: { mark: 0.36, prev: 0.3, volume: 60000, book: { bid: 0.3, ask: 0.32 } },
  [FED.hike]: { mark: 0.1, prev: 0.05, volume: 20000, book: { bid: 0.06, ask: 0.08 } },
  // The fallback and one IPO never traded: placeholders, no volume, empty book.
  [FED.fallback]: { mark: 0.5, prev: 0, volume: 0, book: null, mid: 0.5 },
  [IPO_NEVER]: { mark: 0.5, prev: 0, volume: 0, book: null, mid: 0.5 },
  [IPO_RANGE]: { mark: 0.62, prev: 0.45, volume: 5000, book: { bid: 0.4, ask: 0.5 } },
  [IPO_WIDE]: { mark: 0.7, prev: 0.4, volume: 3000, book: { bid: 0.2, ask: 0.6 } },
  [LADDER_OUT[0]]: { mark: 0.7, prev: 0.68, volume: 50000, book: { bid: 0.66, ask: 0.68 } },
  [LADDER_OUT[1]]: { mark: 0.5, prev: 0.48, volume: 40000, book: { bid: 0.44, ask: 0.46 } },
  [LADDER_OUT[2]]: { mark: 0.3, prev: 0.28, volume: 30000, book: { bid: 0.22, ask: 0.24 } },
  [LADDER_TXYZ[0]]: { mark: 0.69, prev: 0.65, volume: 12000, book: { bid: 0.62, ask: 0.64 } },
  [LADDER_TXYZ[1]]: { mark: 0.48, prev: 0.45, volume: 9000, book: { bid: 0.4, ask: 0.42 } },
  // A quiet book: the venue's `bbo` only pushes on change, so this one never
  // answers a bbo subscription; only the REST l2Book snapshot reports it.
  [FILLER_GAMES[0]]: { mark: 0.52, prev: 0.5, volume: 8000, book: { bid: 0.5, ask: 0.52 }, quiet: true },
  [FILLER_GAMES[1]]: { mark: 0.45, prev: 0.5, volume: 7000, book: { bid: 0.44, ask: 0.46 } },
  [FILLER_GAMES[2]]: { mark: 0.4, prev: 0.4, volume: 6000, book: { bid: 0.38, ask: 0.4 } },
  [FILLER_RUNGS[0]]: { mark: 0.35, prev: 0.3, volume: 4000, book: { bid: 0.33, ask: 0.35 } },
  [FILLER_RUNGS[1]]: { mark: 0.25, prev: 0.2, volume: 2000, book: { bid: 0.23, ask: 0.25 } }
};

export const contractIds = () => Object.keys(CONTRACTS).map(Number);

const money = (n) => (Math.round(n * 100000) / 100000).toFixed(5);

function ctxFor(id, side, entry) {
  const mark = side === 0 ? entry.mark : 1 - entry.mark;
  const mid = entry.mid ?? (entry.book ? (entry.book.bid + entry.book.ask) / 2 : mark);
  return {
    coin: coin(id, side),
    markPx: String(mark),
    midPx: entry.book === null && entry.mid === undefined ? null : String(side === 0 ? mid : 1 - mid),
    prevDayPx: entry.prev === 0 ? "0.0" : String(side === 0 ? entry.prev : 1 - entry.prev),
    dayNtlVlm: String(entry.volume),
    dayBaseVlm: String(entry.volume),
    circulatingSupply: "33333.0",
    totalSupply: "184467440737095.53125"
  };
}

export function outcomeCtxs(overrides = {}) {
  return Object.entries({ ...CONTRACTS, ...overrides }).flatMap(([id, entry]) => [
    ctxFor(Number(id), 0, entry),
    ctxFor(Number(id), 1, entry)
  ]);
}

/** Top of book of one side coin as `{bid, ask}` (null = empty side). */
export function sideBook(coinName, overrides = {}) {
  const table = { ...CONTRACTS, ...overrides };
  const match = /^#(\d+)$/.exec(coinName);
  if (!match) return null;
  const encoded = Number(match[1]);
  const side = encoded % 10;
  const entry = table[(encoded - side) / 10];
  if (!entry || !entry.book) return { bid: null, ask: null };
  return side === 0
    ? { bid: entry.book.bid, ask: entry.book.ask }
    : { bid: 1 - entry.book.ask, ask: 1 - entry.book.bid };
}

/** True for a contract whose book never changes, so `bbo` never reports it. */
function isQuiet(coinName, overrides = {}) {
  const match = /^#(\d+)$/.exec(coinName);
  if (!match) return false;
  const encoded = Number(match[1]);
  const entry = { ...CONTRACTS, ...overrides }[(encoded - (encoded % 10)) / 10];
  return Boolean(entry?.quiet);
}

function levels(book) {
  const level = (px) => ({ px: money(px), sz: "5000.0", n: 3 });
  return [book.bid == null ? [] : [level(book.bid), level(Math.max(0.01, book.bid - 0.02))],
    book.ask == null ? [] : [level(book.ask), level(Math.min(0.99, book.ask + 0.02))]];
}

const SPOT_META = {
  tokens: [
    { name: "USDC", szDecimals: 8, weiDecimals: 8, index: 0, tokenId: "0x6d1e7cde53ba9467b783cb7c530ce054", isCanonical: true, evmContract: null, fullName: null },
    { name: "PURR", szDecimals: 0, weiDecimals: 5, index: 1, tokenId: "0xc1fb593aeffbeb02f85e0308e9956a90", isCanonical: true, evmContract: null, fullName: null }
  ],
  universe: [{ name: "PURR/USDC", tokens: [1, 0], index: 0, isCanonical: true }]
};

const PERP_META = {
  universe: [
    { name: "BTC", szDecimals: 5, maxLeverage: 40 },
    { name: "ETH", szDecimals: 4, maxLeverage: 25 },
    { name: "SOL", szDecimals: 2, maxLeverage: 20 }
  ],
  marginTables: []
};

function perpCtx(price) {
  return {
    funding: "0.0000125",
    openInterest: "1000",
    prevDayPx: String(price),
    dayNtlVlm: "1000000",
    premium: "0",
    oraclePx: String(price),
    markPx: String(price),
    midPx: String(price),
    impactPxs: [String(price - 1), String(price + 1)],
    dayBaseVlm: "100"
  };
}

/**
 * Own `/info`, `/exchange` and the websocket for `page` (call before the first
 * navigation). Returns a handle:
 *   `exchangeRequests`  real POSTs that reached `/exchange` (must stay empty:
 *                       simulator-answered orders never get this far)
 *   `catalogFails(on)`  make `outcomeMeta` fail (503) while `on` is true
 *   `subscriptions`     parsed websocket subscribe messages
 *   `l2BookRequests`    coins of REST `l2Book` snapshots asked for
 *   `overrides`         per-contract overrides of CONTRACTS, applied to the
 *                       catalog and the books served from now on
 */
export async function installOutcomeWorld(page, { now = Date.now() } = {}) {
  const world = {
    now,
    overrides: {},
    exchangeRequests: [],
    subscriptions: [],
    failCatalog: false,
    infoTypes: [],
    l2BookRequests: [],
    catalogFails(on) {
      world.failCatalog = Boolean(on);
    },
    current: null,
    push(frame) {
      world.current?.send(JSON.stringify(frame));
    }
  };

  await page.route(HL_INFO_URL, async (route) => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const type = payload?.type;
    world.infoTypes.push(type);
    const json = (body, status = 200) =>
      route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    switch (type) {
      case "outcomeMeta":
        return world.failCatalog ? json({ error: "unavailable" }, 503) : json(outcomeMeta(now));
      case "spotMeta":
        return json(SPOT_META);
      case "spotMetaAndAssetCtxs":
        return json([
          SPOT_META,
          [
            { prevDayPx: "0.1", dayNtlVlm: "1", markPx: "0.1", midPx: "0.1", circulatingSupply: "1", coin: "PURR/USDC", totalSupply: "1", dayBaseVlm: "1" },
            ...outcomeCtxs(world.overrides)
          ]
        ]);
      case "metaAndAssetCtxs":
        return json([PERP_META, [perpCtx(66880), perpCtx(3410), perpCtx(168)]]);
      case "perpDexs":
        return json([null]);
      case "perpConciseAnnotations":
        return json([]);
      case "candleSnapshot":
        return json([]);
      case "l2Book": {
        world.l2BookRequests.push(payload.coin);
        const book = sideBook(payload.coin, world.overrides);
        return book
          ? json({ coin: payload.coin, time: Date.now(), levels: levels(book) })
          : json(null);
      }
      default:
        return json([]);
    }
  });

  // A real order can never leave the page: anything that gets here is a bug.
  await page.route(HL_EXCHANGE_URL, async (route) => {
    world.exchangeRequests.push(route.request().postData() || "");
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ status: "err", response: "blocked by outcome-markets spec" })
    });
  });

  await page.routeWebSocket(HL_WS_URL, (ws) => {
    world.current = ws;
    const keepalive = setInterval(() => {
      try {
        ws.send(JSON.stringify({ channel: "pong" }));
      } catch (_e) {
        clearInterval(keepalive);
      }
    }, 3000);
    ws.onClose(() => {
      clearInterval(keepalive);
      if (world.current === ws) world.current = null;
    });
    ws.onMessage((message) => {
      let msg;
      try {
        msg = JSON.parse(String(message));
      } catch (_e) {
        return;
      }
      if (msg?.method !== "subscribe") return;
      world.subscriptions.push(msg.subscription);
      const sub = msg.subscription || {};
      if (sub.type === "bbo" && typeof sub.coin === "string" && !isQuiet(sub.coin, world.overrides)) {
        const book = sideBook(sub.coin, world.overrides);
        if (book) {
          const level = (px) => (px == null ? null : { px: money(px), sz: "5000.0", n: 3 });
          ws.send(
            JSON.stringify({ channel: "bbo", data: { coin: sub.coin, time: Date.now(), bbo: [level(book.bid), level(book.ask)] } })
          );
        }
      }
      if (sub.type === "l2Book" && typeof sub.coin === "string") {
        const book = sideBook(sub.coin, world.overrides);
        if (book) {
          ws.send(
            JSON.stringify({
              channel: "l2Book",
              data: { coin: sub.coin, time: Date.now(), levels: levels(book) }
            })
          );
        }
      }
    });
  });

  page.on("close", () => {
    world.current = null;
  });
  return world;
}

/** Wait until the outcome catalog (all fixture events) is in the store. */
export async function waitForCatalog(page, timeout = 30_000) {
  await page.waitForFunction(
    (key) => {
      const c = globalThis.cljs?.core;
      const store = globalThis.hyperopen?.system?.store;
      if (!c || !store) return false;
      const path = c.PersistentVector.fromArray([c.keyword("asset-selector"), c.keyword("market-by-key")], true);
      const byKey = c.get_in(c.deref(store), path);
      return Boolean(byKey && c.get(byKey, key));
    },
    `outcome:${GAME}`,
    { timeout }
  );
}

/**
 * The top-of-book seam: `apply-outcome-top-of-book-update` writes a side's best
 * bid/ask onto every selector market that holds it, which is what list rows,
 * headers and the stale-price rule read through the one chance rule. The
 * product feeds it from `bbo` subscriptions; feeding it here makes every
 * contract's book known at once, independent of which rows are on screen.
 */
export async function feedTopOfBooks(page, books) {
  await page.evaluate((entries) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;
    const projection = globalThis.hyperopen?.asset_selector?.market_live_projection;
    if (!c || !store || !projection) throw new Error("projection namespace unavailable");
    let state = c.deref(store);
    for (const [coinName, top] of entries) {
      const arg = c.PersistentArrayMap.fromArray(
        [c.keyword("bid"), top.bid == null ? null : String(top.bid), c.keyword("ask"), top.ask == null ? null : String(top.ask)],
        true
      );
      state = projection.apply_outcome_top_of_book_update(state, coinName, arg);
    }
    c.reset_BANG_(store, state);
    globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_?.(c.deref(store));
  }, Object.entries(books));
}

/** Feed every fixture contract's two sides from the world books. */
export async function feedWorldBooks(page, world) {
  const books = {};
  for (const id of contractIds()) {
    for (const side of [0, 1]) {
      const name = coin(id, side);
      books[name] = sideBook(name, world.overrides);
    }
  }
  await feedTopOfBooks(page, books);
}


/** Stop the account stream bootstrap so seeded balances are not overwritten. */
export async function freezeAccountSync(page, address = WALLET) {
  await page.evaluate((nextAddress) => {
    const store = globalThis.hyperopen?.system?.store;
    const watcher = globalThis.hyperopen?.wallet?.address_watcher;
    const webdata2 = globalThis.hyperopen?.websocket?.webdata2;
    const userSubscriptions = globalThis.hyperopen?.websocket?.user_runtime?.subscriptions;
    if (!store || !watcher || !webdata2 || !userSubscriptions) throw new Error("account sync runtime unavailable");
    watcher.stop_watching_BANG_(store);
    watcher.remove_handler_BANG_("webdata2-subscription-handler");
    watcher.remove_handler_BANG_("user-ws-subscription-handler");
    watcher.remove_handler_BANG_("startup-account-bootstrap-handler");
    webdata2.unsubscribe_webdata2_BANG_(nextAddress);
    userSubscriptions.unsubscribe_user_BANG_(nextAddress);
  }, address);
}

/** A connected wallet with a ready (plain, local) agent session: orders sign. */
export async function seedReadySession(page, address = WALLET) {
  await page.evaluate((walletAddress) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;
    const kw = c.keyword;
    const path = (...segments) => c.PersistentVector.fromArray(segments.map((s) => kw(s)), true);
    const lower = String(walletAddress).toLowerCase();
    // Well-known throwaway key (private key 1) so the app can really sign; the exchange is a simulator.
    const agentAddress = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf";
    const approvedAt = 1700000000000;
    localStorage.setItem("hyperopen:agent-storage-mode:v1", "local");
    localStorage.setItem("hyperopen:agent-local-protection-mode:v1", "plain");
    localStorage.removeItem(`hyperopen:agent-passkey-session:v1:${lower}`);
    localStorage.setItem(
      `hyperopen:agent-session:v1:${lower}`,
      JSON.stringify({ "agent-address": agentAddress, "private-key": "0x0000000000000000000000000000000000000000000000000000000000000001", "last-approved-at": approvedAt, "nonce-cursor": approvedAt })
    );
    let state = c.deref(store);
    const set = (p, v) => {
      state = c.assoc_in(state, p, v);
    };
    set(path("wallet", "connected?"), true);
    set(path("wallet", "address"), walletAddress);
    set(path("wallet", "chain-id"), "0xa4b1");
    set(path("wallet", "agent", "status"), kw("ready"));
    set(path("wallet", "agent", "storage-mode"), kw("local"));
    set(path("wallet", "agent", "local-protection-mode"), kw("plain"));
    set(path("wallet", "agent", "passkey-supported?"), false);
    set(path("wallet", "agent", "agent-address"), agentAddress);
    set(path("wallet", "agent", "last-approved-at"), approvedAt);
    set(path("wallet", "agent", "nonce-cursor"), approvedAt);
    set(path("wallet", "agent", "error"), null);
    set(path("wallet", "agent", "recovery-modal-open?"), false);
    c.reset_BANG_(store, state);
  }, address);
}

/** Write balances of the connected account (and a ready agent session). */
export async function seedBalances(page, balances) {
  await page.evaluate((rows) => {
    const c = globalThis.cljs?.core;
    const store = globalThis.hyperopen?.system?.store;
    const kw = c.keyword;
    const opts = c.PersistentArrayMap.fromArray([kw("keywordize-keys"), true], true);
    const state = c.assoc_in(
      c.deref(store),
      c.PersistentVector.fromArray([kw("spot"), kw("clearinghouse-state")], true),
      c.js__GT_clj({ balances: rows }, opts)
    );
    c.reset_BANG_(store, state);
    globalThis.hyperopen?.app?.bootstrap?.render_app_BANG_?.(c.deref(store));
  }, balances);
}

export const usdcBalance = (total = "1000") => ({ coin: "USDC", token: 0, hold: "0", total, entryNtl: "0" });

export const outcomeBalance = (outcomeId, side, total, entryNtl) => ({
  coin: wireCoin(outcomeId, side),
  token: 100000000 + outcomeId * 10 + side,
  hold: "0",
  total: String(total),
  entryNtl: String(entryNtl)
});

/** Open the Outcome tab of the market selector. */
export async function openOutcomeTab(page) {
  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await page.getByRole("button", { name: "Outcome", exact: true }).first().click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

/** The exchange simulator's recorded `order` actions. */
export async function simulatedOrders(page) {
  const snapshot = await page.evaluate(() => globalThis.HYPEROPEN_DEBUG.exchangeSimulatorSnapshot());
  return (snapshot?.calls ?? [])
    .map((call) => call?.request?.action)
    .filter((action) => action?.type === "order");
}
