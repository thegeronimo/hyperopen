import assert from "node:assert/strict";
import test from "node:test";

import { createHyperEvmRpcGuard } from "./hyperevm_rpc_guard.mjs";
import { routeHyperEvmRpc } from "./hyperevm_fixtures.mjs";

const OWNER = "0x1234567890abcdef1234567890abcdef12345678";
const RPC_URL = "https://rpc.hyperliquid.xyz/evm";

// A stand-in for a Playwright BrowserContext: `request` listeners see every
// request first, then page-level handlers run (newest first), then
// context-level ones, as Playwright orders them. A handler that neither
// fulfills nor falls back leaves the request unanswered by any mock.
function fakeContext() {
  const listeners = { request: [], page: [] };
  const contextRoutes = [];
  const pageRoutes = [];
  const page = {
    route: async (matcher, handler) => {
      pageRoutes.unshift({ matcher, handler });
    }
  };
  const context = {
    page,
    on(event, fn) {
      (listeners[event] ||= []).push(fn);
    },
    pages() {
      return [page];
    },
    async route(matcher, handler) {
      contextRoutes.unshift({ matcher, handler });
    },
    async send(url, body, { method = "POST" } = {}) {
      let resolveResponse;
      const response = new Promise((resolve) => {
        resolveResponse = resolve;
      });
      const request = {
        url: () => url,
        method: () => method,
        postData: () => JSON.stringify(body),
        response: () => response
      };
      listeners.request.forEach((listener) => listener(request));
      let outcome = { kind: "network" };
      for (const { matcher, handler } of [...pageRoutes, ...contextRoutes]) {
        if (!matcher(new URL(url))) continue;
        let fellBack = false;
        let handled = null;
        await handler({
          request: () => request,
          fulfill: async (answer) => {
            handled = { kind: "fulfilled", answer };
          },
          abort: async () => {
            handled = { kind: "aborted" };
          },
          fallback: async () => {
            fellBack = true;
          }
        });
        if (!fellBack) {
          outcome = handled || { kind: "unanswered" };
          break;
        }
      }
      resolveResponse(outcome.kind === "fulfilled" ? outcome.answer : null);
      return { request, outcome };
    }
  };
  return context;
}

class FakeBrowser {
  async newContext() {
    return fakeContext();
  }
}

const getBalance = (id = 1) => ({ jsonrpc: "2.0", id, method: "eth_getBalance", params: [OWNER, "latest"] });

test("a protected context answers the RPC from the empty mock and stays clean", async () => {
  const guard = createHyperEvmRpcGuard({ settleMs: 10 });
  const context = fakeContext();
  await guard.protectContext(context);
  const { outcome } = await context.send(RPC_URL, getBalance());
  assert.equal(outcome.kind, "fulfilled");
  assert.equal(JSON.parse(outcome.answer.body).result, "0x0");
  assert.deepEqual(guard.contextEntries(context).map((entry) => entry.method), ["eth_getBalance"]);
  await context.send("https://api.hyperliquid.xyz/info", { type: "allMids" });
  assert.deepEqual(guard.observedUrls(), [`POST ${RPC_URL}`]);
  assert.deepEqual(await guard.violations(), { unmocked: [], unknown: [], oversizedBatches: [] });
  await guard.assertClean();
});

test("a page-level mock takes precedence and still counts as answered", async () => {
  const guard = createHyperEvmRpcGuard({ settleMs: 10 });
  const context = fakeContext();
  await guard.protectContext(context);
  const controller = await routeHyperEvmRpc(context.page);
  await context.send(RPC_URL, getBalance());
  assert.deepEqual(controller.entries().map((entry) => entry.method), ["eth_getBalance"]);
  assert.deepEqual(guard.contextEntries(context), []);
  await guard.assertClean();
});

test("a request that escapes the mock is reported whatever routes the page has", async () => {
  // The leak the guard exists for: a page handler registered after the mock
  // lets the request out (`continue()` live; `abort()` here, which reaches
  // no mock either). Detection never looks at which routes were installed.
  const guard = createHyperEvmRpcGuard({ settleMs: 10 });
  const context = fakeContext();
  await guard.protectContext(context);
  await routeHyperEvmRpc(context.page);
  await context.page.route((url) => url.search === "?escape", (route) => route.abort());
  const { outcome } = await context.send(`${RPC_URL}?escape`, getBalance());
  assert.equal(outcome.kind, "aborted");
  assert.deepEqual((await guard.violations()).unmocked, [`POST ${RPC_URL}?escape`]);
  await assert.rejects(guard.assertClean(), /without the mock answering them: \["POST https:\/\/rpc\.hyperliquid\.xyz\/evm\?escape"\]/);

  guard.acknowledge();
  await guard.assertClean();
});

test("unknown methods and oversized batches fail the test", async () => {
  const guard = createHyperEvmRpcGuard({ settleMs: 10 });
  const context = fakeContext();
  await guard.protectContext(context);
  await context.send(RPC_URL, { jsonrpc: "2.0", id: 1, method: "eth_blockNumber", params: [] });
  await context.send(RPC_URL, Array.from({ length: 21 }, (_, index) => getBalance(index + 1)));
  const { unknown, oversizedBatches } = await guard.violations();
  assert.deepEqual(unknown.map((entry) => entry.method), ["eth_blockNumber"]);
  assert.deepEqual(oversizedBatches, [21]);
  await assert.rejects(guard.assertClean(), /could not answer.*eth_blockNumber.*20-entry cap \(sizes\): \[21\]/);
});

test("contexts a test opens through browser.newContext() are protected until the patch is undone", async () => {
  const guard = createHyperEvmRpcGuard({ settleMs: 10 });
  const browser = new FakeBrowser();
  const restore = guard.patchBrowser(browser);
  assert.ok(Object.prototype.hasOwnProperty.call(browser, "newContext"));
  const fresh = await browser.newContext();
  const { outcome } = await fresh.send(RPC_URL, getBalance());
  assert.equal(outcome.kind, "fulfilled", "the opened context answers from the mock");
  assert.deepEqual(guard.contextEntries(fresh).map((entry) => entry.method), ["eth_getBalance"]);

  restore();
  assert.equal(Object.prototype.hasOwnProperty.call(browser, "newContext"), false);
  const unguarded = await browser.newContext();
  const after = await unguarded.send(RPC_URL, getBalance());
  assert.equal(after.outcome.kind, "network", "no route once the patch is undone");
  assert.deepEqual(guard.contextEntries(unguarded), []);
});
