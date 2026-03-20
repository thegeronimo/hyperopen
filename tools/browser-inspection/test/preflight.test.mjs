import http from "node:http";
import test from "node:test";
import assert from "node:assert/strict";
import { waitForUrl } from "../src/local_app_manager.mjs";
import { runPreflightChecks } from "../src/preflight.mjs";

async function createLocalServer() {
  const server = http.createServer((req, res) => {
    if (req.url?.startsWith("/index.html")) {
      res.writeHead(200, { "content-type": "text/html" });
      res.end("<!doctype html><title>Hyperopen</title>");
      return;
    }
    res.writeHead(404, { "content-type": "text/plain" });
    res.end("not found");
  });

  await new Promise((resolve) => {
    server.listen(0, "127.0.0.1", resolve);
  });

  const address = server.address();
  if (!address || typeof address === "string") {
    throw new Error("Failed to start test server");
  }

  const baseUrl = `http://127.0.0.1:${address.port}`;
  return {
    baseUrl,
    indexUrl: `${baseUrl}/index.html`,
    tradeUrl: `${baseUrl}/trade`,
    async close() {
      await new Promise((resolve) => server.close(resolve));
    }
  };
}

test("waitForUrl rejects a 404 deep link and accepts the bootstrap URL", async () => {
  const server = await createLocalServer();
  try {
    await assert.rejects(
      waitForUrl(server.tradeUrl, 200, 25),
      /Timed out waiting for local app/
    );
    await assert.doesNotReject(waitForUrl(server.indexUrl, 500, 25));
  } finally {
    await server.close();
  }
});

test("runPreflightChecks records a 404 local URL probe as not ready", async () => {
  const server = await createLocalServer();
  try {
    const result = await runPreflightChecks(
      {
        localApp: { url: server.indexUrl }
      },
      {
        localUrl: server.tradeUrl,
        urlTimeoutMs: 200
      }
    );

    const probe = result.checks.find((check) => check.id === "local-url-reachable");
    assert.equal(probe?.ok, false);
    assert.equal(probe?.required, false);
    assert.equal(result.summary.failedOptionalCheckIds.includes("local-url-reachable"), true);
  } finally {
    await server.close();
  }
});
