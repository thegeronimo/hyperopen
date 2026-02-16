import test from "node:test";
import assert from "node:assert/strict";
import { CDPClient } from "../src/cdp_client.mjs";

test("CDPClient send resolves when matching response arrives", async () => {
  const client = new CDPClient("ws://example");
  let sentPayload = null;

  client.connected = true;
  client.socket = {
    readyState: WebSocket.OPEN,
    send(data) {
      sentPayload = JSON.parse(data);
    }
  };

  const pending = client.send("Browser.getVersion", {});
  client.onMessage(JSON.stringify({ id: sentPayload.id, result: { product: "Chrome" } }));
  const result = await pending;

  assert.equal(result.product, "Chrome");
});

test("CDPClient waitForEvent filters by session", async () => {
  const client = new CDPClient("ws://example");
  const wait = client.waitForEvent("Page.loadEventFired", { sessionId: "a", timeoutMs: 1000 });
  client.onMessage(JSON.stringify({ method: "Page.loadEventFired", sessionId: "b", params: {} }));
  client.onMessage(JSON.stringify({ method: "Page.loadEventFired", sessionId: "a", params: {} }));
  const event = await wait;
  assert.equal(event.sessionId, "a");
});
