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

export async function installWsSimulator(page, { address, keepaliveMs = 3000 } = {}) {
  const user = String(address || "").toLowerCase();
  const timers = new Set();
  const handle = {
    address: user,
    state: { fills: [], orders: [] },
    connections: 0,
    received: [],
    current: null,
    held: null,

    userFillsFrame(fills, { snapshot = false, forUser = user } = {}) {
      return { channel: "userFills", data: { isSnapshot: snapshot, user: forUser, fills } };
    },

    openOrdersFrame(orders, { forUser = user, dex = "" } = {}) {
      return { channel: "openOrders", data: { user: forUser, dex, orders } };
    },

    push(frame) {
      if (!handle.current) throw new Error("ws simulator: no open connection");
      const text = typeof frame === "string" ? frame : JSON.stringify(frame);
      if (!safeSend(handle.current, text)) throw new Error("ws simulator: send failed");
    },

    async drop() {
      const ws = handle.current;
      handle.current = null;
      if (ws) await ws.close({ code: 4000, reason: "Simulated connection loss" }).catch(() => {});
    },

    holdSnapshots() {
      if (!handle.held) handle.held = [];
    },

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
