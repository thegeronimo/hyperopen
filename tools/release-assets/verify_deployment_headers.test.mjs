import assert from "node:assert/strict";
import http from "node:http";
import test from "node:test";

import {
  RELEASE_ROUTE_METADATA_SCRIPT_PATH,
} from "./site_metadata.mjs";
import {
  CONTROL_CACHE_CONTROL,
  IMMUTABLE_CACHE_CONTROL,
  expectedDocumentHeaders,
} from "./security_headers.mjs";
import { verifyDeploymentHeaders } from "./verify_deployment_headers.mjs";

function createServer(headersByPath = {}) {
  const documentHeaders = expectedDocumentHeaders();
  const tradeHtml = `<!DOCTYPE html>
<html>
  <head>
    <title>Trade</title>
    <meta name="description" content="Trade route" />
    <link rel="stylesheet" href="/css/main.ABC123.css" />
  </head>
  <body>
    <div id="app"></div>
    <script defer src="/${RELEASE_ROUTE_METADATA_SCRIPT_PATH}"></script>
    <script defer src="/js/main.HASH.js"></script>
  </body>
</html>`;

  return http.createServer((request, response) => {
    const headers = headersByPath[request.url] || {};

    if (request.url === "/trade") {
      response.writeHead(200, {
        "content-type": "text/html; charset=utf-8",
        ...documentHeaders,
        ...headers,
      });
      response.end(tradeHtml);
      return;
    }

    if (request.url === "/css/main.ABC123.css") {
      response.writeHead(200, {
        "content-type": "text/css; charset=utf-8",
        "cache-control": IMMUTABLE_CACHE_CONTROL,
        ...headers,
      });
      response.end("body{}");
      return;
    }

    if (request.url === "/js/main.HASH.js") {
      response.writeHead(200, {
        "content-type": "text/javascript; charset=utf-8",
        "cache-control": IMMUTABLE_CACHE_CONTROL,
        ...headers,
      });
      response.end("console.log('main');");
      return;
    }

    if (
      request.url === `/${RELEASE_ROUTE_METADATA_SCRIPT_PATH}` ||
      request.url === "/site-metadata.json" ||
      request.url === "/sw.js"
    ) {
      response.writeHead(200, {
        "content-type": "application/json; charset=utf-8",
        "cache-control": CONTROL_CACHE_CONTROL,
        ...headers,
      });
      response.end("{}");
      return;
    }

    response.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    response.end("missing");
  });
}

test("verifyDeploymentHeaders succeeds when document and asset headers match the contract", async () => {
  const server = createServer();
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  const origin = `http://127.0.0.1:${address.port}`;

  try {
    const summary = await verifyDeploymentHeaders({ origin });

    assert.equal(summary.origin, origin);
    assert.equal(summary.documentPath, "/trade");
    assert.deepEqual(summary.verifiedPaths, [
      "/css/main.ABC123.css",
      "/js/main.HASH.js",
      `/${RELEASE_ROUTE_METADATA_SCRIPT_PATH}`,
      "/site-metadata.json",
      "/sw.js",
    ]);
  } finally {
    await new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
  }
});

test("verifyDeploymentHeaders fails closed when the document CSP drifts", async () => {
  const server = createServer({
    "/trade": { "content-security-policy": "default-src 'self';" },
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  const origin = `http://127.0.0.1:${address.port}`;

  try {
    await assert.rejects(
      verifyDeploymentHeaders({ origin }),
      /expected content-security-policy/i
    );
  } finally {
    await new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
  }
});
