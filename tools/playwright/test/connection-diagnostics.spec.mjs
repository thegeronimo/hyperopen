import { expect, test } from "@playwright/test";
import { visitRoute, waitForIdle } from "../support/hyperopen.mjs";

const SUPPORT_ADDRESS = "0x1111111111111111111111111111111111111111";

test.setTimeout(90_000);

async function seedConnectionDiagnosticsState(page, mode = "online", options = {}) {
  await page.evaluate(
    ({ mode, address, open }) => {
      const c = globalThis.cljs?.core;
      const store = globalThis.hyperopen?.system?.store;

      if (!c || !store) {
        throw new Error("Hyperopen store or cljs core unavailable");
      }

      const keyword = c.keyword;
      const kwPath = (...segments) =>
        c.PersistentVector.fromArray(segments.map((segment) => keyword(segment)), true);
      const map = (entries) =>
        c.PersistentArrayMap.fromArray(
          entries.flatMap(([key, value]) => [keyword(key), value]),
          true
        );
      const streamKey = (...segments) => c.PersistentVector.fromArray(segments, true);
      const live = keyword("live");
      const delayed = keyword("delayed");
      const healthModeDelayed = mode === "delayed";
      const generatedAt = healthModeDelayed ? 40_000 : 10_000;
      const transport = map([
        ["state", keyword("connected")],
        ["freshness", healthModeDelayed ? delayed : live],
        ["last-recv-at-ms", healthModeDelayed ? 32_000 : 9_500],
        ["expected-traffic?", true],
        [
          "last-close",
          map([
            ["code", 1006],
            ["reason", "abnormal close"],
            ["at-ms", 8_000]
          ])
        ]
      ]);
      const groups = c.PersistentArrayMap.fromArray(
        [
          keyword("orders_oms"),
          map([["worst-status", keyword("idle")]]),
          keyword("market_data"),
          map([["worst-status", healthModeDelayed ? delayed : live]]),
          keyword("account"),
          map([["worst-status", keyword("event-driven")]])
        ],
        true
      );
      const streams = c.PersistentArrayMap.fromArray(
        [
          streamKey("trades", "BTC", null, null, null),
          map([
            ["group", keyword("market_data")],
            ["topic", "trades"],
            ["subscribed?", true],
            ["status", healthModeDelayed ? delayed : live],
            ["last-payload-at-ms", healthModeDelayed ? 30_000 : 9_300],
            ["stale-threshold-ms", 10_000],
            ["message-count", 12],
            ["descriptor", map([["type", "trades"], ["coin", "BTC"]])]
          ]),
          streamKey("openOrders", null, address, null, null),
          map([
            ["group", keyword("orders_oms")],
            ["topic", "openOrders"],
            ["subscribed?", true],
            ["status", keyword("idle")],
            ["last-payload-at-ms", 9_000],
            ["stale-threshold-ms", 8_000],
            ["message-count", 3],
            ["descriptor", map([["type", "openOrders"], ["user", address]])]
          ])
        ],
        true
      );
      const health = map([
        ["generated-at-ms", generatedAt],
        ["transport", transport],
        ["groups", groups],
        ["streams", streams],
        [
          "market-projection",
          map([
            ["score", 86],
            ["penalty", 14],
            ["recent-flushes", c.PersistentVector.EMPTY]
          ])
        ]
      ]);
      let nextState = c.deref(store);
      nextState = c.assoc_in(nextState, kwPath("websocket", "health"), health);
      nextState = c.assoc_in(nextState, kwPath("websocket-ui", "diagnostics-open?"), Boolean(open));
      nextState = c.assoc_in(nextState, kwPath("websocket-ui", "copy-status"), null);
      nextState = c.assoc_in(nextState, kwPath("websocket-ui", "reconnect-count"), 2);
      nextState = c.assoc_in(
        nextState,
        kwPath("websocket-ui", "diagnostics-timeline"),
        c.PersistentVector.fromArray(
          [
            map([
              ["event", keyword("connected")],
              ["at-ms", 9_000],
              ["details", map([["source", "playwright"]])]
            ])
          ],
          true
        )
      );
      c.reset_BANG_(store, nextState);
    },
    { mode, address: SUPPORT_ADDRESS, open: Boolean(options.open) }
  );
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
}

test("footer connection diagnostics opens as trader-first popover @smoke", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await visitRoute(page, "/trade");
  await seedConnectionDiagnosticsState(page, "online");

  const trigger = page.locator("[data-role='footer-connection-meter-button']");
  await expect(trigger).toBeVisible();
  await expect(trigger.locator("[data-role='footer-connection-meter-bar']")).toHaveCount(4);
  await expect(trigger).toHaveAttribute("aria-expanded", "false");
  await expect(trigger).toContainText("Online");
  await expect(trigger).not.toContainText(/\d+ms/);

  await seedConnectionDiagnosticsState(page, "online", { open: true });
  const popover = page.locator("[data-role='connection-diagnostics-popover']");
  await expect(popover).toBeVisible();
  await expect(trigger).toHaveAttribute("aria-expanded", "true");
  await expect(popover).toHaveAttribute("role", "dialog");
  await expect(popover).toHaveAttribute("aria-label", "Connection status");
  await expect.poll(async () => Math.round((await popover.boundingBox())?.width || 0)).toBe(380);
  await expect
    .poll(async () => {
      const triggerBox = await trigger.boundingBox();
      const popoverBox = await popover.boundingBox();
      return Math.round(Math.abs((popoverBox?.x || 0) - (triggerBox?.x || 0)));
    })
    .toBeLessThanOrEqual(12);
  await expect(popover.getByText("Everything is live", { exact: true })).toBeVisible();
  await expect(popover.getByText("All data is streaming normally.", { exact: true })).toBeVisible();
  await expect(popover.getByText("Orders", { exact: true })).toBeVisible();
  await expect(popover.getByText("Market data", { exact: true })).toBeVisible();
  await expect(popover.getByText("Account", { exact: true })).toBeVisible();
  await expect(popover.getByRole("switch", { name: "Show freshness labels" })).toBeVisible();
  await expect(page.locator("[data-role='connection-diagnostics-dev-toggle']")).toHaveAttribute(
    "aria-expanded",
    "false"
  );
  await expect(popover.getByText(/Score|Penalty|Bars|Build ID|Reveal sensitive|Market projection|Recent flushes|Transport|stream descriptors|raw connection/i)).toHaveCount(0);

  const copyButton = popover.getByRole("button", { name: "Copy diagnostics" });
  await copyButton.click();
  await expect(popover.getByRole("button", { name: /Copied/ })).toBeVisible();
  const copiedPayload = JSON.parse(await page.evaluate(() => navigator.clipboard.readText()));
  expect(copiedPayload["market-projection"]).toBeTruthy();
  expect(copiedPayload.streams.some((stream) => stream.descriptor)).toBe(true);
  expect(JSON.stringify(copiedPayload)).not.toContain(SUPPORT_ADDRESS);

  await popover.focus();
  await page.keyboard.press("Escape");
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(popover).toHaveCount(0);
  await expect(trigger).toHaveAttribute("aria-expanded", "false");
  await seedConnectionDiagnosticsState(page, "online", { open: true });
  await expect(popover).toBeVisible();
  await page.locator("[data-role='connection-diagnostics-backdrop']").click({ position: { x: 1, y: 1 } });
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(popover).toHaveCount(0);
});
