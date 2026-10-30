// WebSocket simulator for the Vol Range browser specs (HO-SPEC-014 v2.1 sections 9.1, 9.3).
//
// Owns the page's Hyperliquid WebSocket through `page.routeWebSocket`: nothing reaches the
// real venue socket. Each `subscribe` for `userFills` is answered with a subscription
// snapshot and each `subscribe` for `openOrders` with a default-dex order list, both for the
// subscribed user, so live monitoring can become `:live` once the resync has run. Every
// other subscription (market data, other account streams) is accepted and never answered.
//
// The app sends no pings, and transport freshness turns `:delayed` when no frame arrives for
// 10 s while a subscribed stream expects traffic (l2Book, trades). The simulator therefore
// pushes a harmless `{"channel":"pong"}` frame every `keepaliveMs` (any received frame
// updates the transport's receive time).
//
// `push(frame)` sends a frame on the current connection, `drop()` closes it as a lost
// connection would (the app reconnects and the route accepts the new socket, which gets
// fresh snapshots on re-subscription). `state.fills` and `state.orders` are what the next
// snapshots carry. `holdSnapshots()` queues the snapshot answers (monitoring then stays
// stale after a reconnect) until `releaseSnapshots()` sends them.

export const HYPERLIQUID_WS_URL = "wss://api.hyperliquid.xyz/ws";

const KEEPALIVE_FRAME = JSON.stringify({ channel: "pong" });

function safeSend(ws, text) {
  try {
    ws.send(text);
    return true;
  } catch (_e) {
    return false;
  }
}

/**
 * Install the simulator on `page` before its first navigation. `address` is the fixture
 * account (the default user of the frame helpers). Returns the handle.
 */
export async function installWsSimulator(page, { address, keepaliveMs = 3000 } = {}) {
  const user = String(address || "").toLowerCase();
  const timers = new Set();
  const handle = {
    address: user,
    // What the next userFills / openOrders snapshots carry.
    state: { fills: [], orders: [] },
    connections: 0,
    // Parsed client messages (subscribe / unsubscribe), newest last.
    received: [],
    current: null,
    held: null,

    userFillsFrame(fills, { snapshot = false, forUser = user } = {}) {
      return { channel: "userFills", data: { isSnapshot: snapshot, user: forUser, fills } };
    },

    openOrdersFrame(orders, { forUser = user, dex = "" } = {}) {
      return { channel: "openOrders", data: { user: forUser, dex, orders } };
    },

    /** Send `frame` (an object or a JSON string) on the current connection. */
    push(frame) {
      if (!handle.current) throw new Error("ws simulator: no open connection");
      const text = typeof frame === "string" ? frame : JSON.stringify(frame);
      if (!safeSend(handle.current, text)) throw new Error("ws simulator: send failed");
    },

    /** Close the current connection as a lost connection would; the app reconnects. */
    async drop() {
      const ws = handle.current;
      handle.current = null;
      if (ws) await ws.close({ code: 4000, reason: "Simulated connection loss" }).catch(() => {});
    },

    /** Queue snapshot answers instead of sending them. */
    holdSnapshots() {
      if (!handle.held) handle.held = [];
    },

    /** Send every queued snapshot answer (on its own connection, if still open). */
    releaseSnapshots() {
      const held = handle.held || [];
      handle.held = null;
      for (const answer of held) answer();
    },

    subscriptions(type) {
      return handle.received.filter((m) => m?.method === "subscribe" && m?.subscription?.type === type);
    }
  };

  await page.routeWebSocket(HYPERLIQUID_WS_URL, (ws) => {
    handle.connections += 1;
    handle.current = ws;
    const timer = setInterval(() => safeSend(ws, KEEPALIVE_FRAME), keepaliveMs);
    timers.add(timer);
    const stop = () => {
      clearInterval(timer);
      timers.delete(timer);
      if (handle.current === ws) handle.current = null;
    };
    ws.onClose(stop);
    ws.onMessage((message) => {
      let msg;
      try {
        msg = JSON.parse(String(message));
      } catch (_e) {
        return;
      }
      handle.received.push(msg);
      if (msg?.method !== "subscribe") return;
      const sub = msg.subscription || {};
      // Echo the subscribed user exactly, so the frame matches the subscription.
      const subUser = typeof sub.user === "string" ? sub.user : "";
      if (!subUser) return;
      let answer = null;
      if (sub.type === "userFills") {
        answer = () =>
          safeSend(ws, JSON.stringify(handle.userFillsFrame(handle.state.fills, { snapshot: true, forUser: subUser })));
      } else if (sub.type === "openOrders") {
        answer = () =>
          safeSend(ws, JSON.stringify(handle.openOrdersFrame(handle.state.orders, { forUser: subUser, dex: sub.dex || "" })));
      }
      if (!answer) return;
      if (handle.held) handle.held.push(answer);
      else answer();
    });
  });

  page.on("close", () => {
    for (const timer of timers) clearInterval(timer);
    timers.clear();
  });

  return handle;
}
