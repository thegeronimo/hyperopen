import http from "node:http";
import test from "node:test";
import assert from "node:assert/strict";
import { maybeStartLocalApp, maybeStopLocalApp } from "../src/local_app_manager.mjs";

async function createIndexServer() {
  const server = http.createServer((req, res) => {
    if (req.url?.startsWith("/index.html")) {
      res.writeHead(200, { "content-type": "text/html" });
      res.end("<!doctype html><title>Hyperopen</title>");
      return;
    }
    res.writeHead(404, { "content-type": "text/plain" });
    res.end("not found");
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (!address || typeof address === "string") {
    throw new Error("Failed to bind test server");
  }
  return {
    port: address.port,
    indexUrl: `http://127.0.0.1:${address.port}/index.html`,
    async close() {
      await new Promise((resolve) => server.close(resolve));
    }
  };
}

function managedLocalCommand(serverCount = 1) {
  return `node --input-type=module - <<'EOF'
import http from "node:http";

const servers = Array.from({ length: ${serverCount} }, () =>
  http.createServer((req, res) => {
    if (req.url?.startsWith("/index.html")) {
      res.writeHead(200, { "content-type": "text/html" });
      res.end("<!doctype html><title>Hyperopen managed</title>");
      return;
    }
    res.writeHead(404, { "content-type": "text/plain" });
    res.end("not found");
  })
);

await Promise.all(
  servers.map(
    (server) =>
      new Promise((resolve) =>
        server.listen(0, "127.0.0.1", () => {
          const address = server.address();
          if (!address || typeof address === "string") {
            process.exit(1);
            return;
          }
          console.log(\`shadow-cljs - HTTP server available at http://127.0.0.1:\${address.port}\`);
          resolve();
        })
      )
  )
);

setInterval(() => {}, 1000);
EOF`;
}

test("maybeStartLocalApp discovers the actual managed-local URL from process output", async () => {
  const state = await maybeStartLocalApp(
    {
      command: managedLocalCommand(),
      cwd: ".",
      url: "http://localhost:8080/index.html",
      startupTimeoutMs: 5000,
      pollIntervalMs: 25
    },
    {
      manageLocalApp: true
    }
  );

  try {
    assert.equal(state.startedByTool, true);
    assert.equal(state.requestedUrl, "http://localhost:8080/index.html");
    assert.match(state.url, /^http:\/\/127\.0\.0\.1:\d+\/index\.html$/);
    assert.notEqual(state.url, state.requestedUrl);
  } finally {
    await maybeStopLocalApp(state);
  }
});

test("maybeStartLocalApp still starts an isolated managed app when the requested URL is already occupied", async () => {
  const existing = await createIndexServer();
  const state = await maybeStartLocalApp(
    {
      command: managedLocalCommand(),
      cwd: ".",
      url: existing.indexUrl,
      startupTimeoutMs: 5000,
      pollIntervalMs: 25
    },
    {
      manageLocalApp: true
    }
  );

  try {
    assert.equal(state.startedByTool, true);
    assert.equal(state.requestedUrl, existing.indexUrl);
    assert.notEqual(state.url, existing.indexUrl);
  } finally {
    await maybeStopLocalApp(state);
    await existing.close();
  }
});

test("maybeStartLocalApp preserves all announced managed-local candidates in discovery order", async () => {
  const state = await maybeStartLocalApp(
    {
      command: managedLocalCommand(2),
      cwd: ".",
      url: "http://localhost:8080/index.html",
      startupTimeoutMs: 5000,
      pollIntervalMs: 25
    },
    {
      manageLocalApp: true
    }
  );

  try {
    assert.equal(state.startedByTool, true);
    assert.equal(Array.isArray(state.candidateUrls), true);
    assert.equal(state.candidateUrls.length, 2);
    assert.equal(state.url, state.candidateUrls[0]);
    assert.notEqual(state.candidateUrls[0], state.candidateUrls[1]);
    assert.match(state.candidateUrls[0], /^http:\/\/127\.0\.0\.1:\d+\/index\.html$/);
    assert.match(state.candidateUrls[1], /^http:\/\/127\.0\.0\.1:\d+\/index\.html$/);
  } finally {
    await maybeStopLocalApp(state);
  }
});
