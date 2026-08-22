import http from "node:http";
import { Readable } from "node:stream";

const PORT = Number(process.env.PORT ?? "8081");
const APP_ORIGIN = process.env.APP_ORIGIN ?? "http://localhost:8080";
const HYPERUNIT_MAINNET_URL =
  process.env.HYPERUNIT_MAINNET_URL ?? "https://api.hyperunit.xyz";
const HYPERUNIT_TESTNET_URL =
  process.env.HYPERUNIT_TESTNET_URL ?? "https://api.hyperunit-testnet.xyz";

// Mirrors functions/api/coin-icon/[key].js so the share card resolves real coin
// icons in local development too. The icon host sends no CORS header, so the
// bytes are only reachable same-origin -- see that file for the full reasoning.
const COIN_ICON_PREFIX = "/api/coin-icon/";
const COIN_ICON_UPSTREAM = "https://app.hyperliquid.xyz/coins/";
const COIN_ICON_KEY_PATTERN =
  /^[A-Za-z0-9_@.-]{1,48}(:[A-Za-z0-9_@.-]{1,48})?$/;

async function serveCoinIcon(req, res) {
  const { pathname } = new URL(req.url ?? "/", "http://localhost");
  const raw = pathname.slice(COIN_ICON_PREFIX.length);
  const key = raw.endsWith(".svg") ? decodeURIComponent(raw.slice(0, -4)) : null;

  if (!key || !COIN_ICON_KEY_PATTERN.test(key)) {
    res.statusCode = 404;
    res.end("Not found");
    return;
  }

  const upstream = await fetch(`${COIN_ICON_UPSTREAM}${encodeURI(key)}.svg`);
  const contentType = String(upstream.headers.get("content-type") || "");

  if (!upstream.ok || !contentType.includes("image/svg+xml")) {
    res.statusCode = 404;
    res.end("Not found");
    return;
  }

  res.statusCode = 200;
  res.setHeader("content-type", "image/svg+xml");
  res.setHeader("cache-control", "public, max-age=86400");
  res.setHeader("access-control-allow-origin", "*");
  Readable.fromWeb(upstream.body).pipe(res);
}

const HYPERUNIT_PREFIXES = [
  { prefix: "/api/hyperunit/mainnet", upstreamBaseUrl: HYPERUNIT_MAINNET_URL },
  { prefix: "/api/hyperunit/testnet", upstreamBaseUrl: HYPERUNIT_TESTNET_URL },
  { prefix: "/api/hyperunit", upstreamBaseUrl: HYPERUNIT_MAINNET_URL },
];

function stripPrefix(pathname, prefix) {
  const remainder = pathname.slice(prefix.length);
  if (!remainder) return "/";
  return remainder.startsWith("/") ? remainder : `/${remainder}`;
}

function resolveProxyTargetUrl(requestUrl) {
  const incoming = new URL(requestUrl, "http://localhost");
  const matched = HYPERUNIT_PREFIXES.find(({ prefix }) =>
    incoming.pathname.startsWith(prefix),
  );

  if (!matched) {
    return new URL(`${incoming.pathname}${incoming.search}`, APP_ORIGIN);
  }

  const targetPath = stripPrefix(incoming.pathname, matched.prefix);
  return new URL(`${targetPath}${incoming.search}`, matched.upstreamBaseUrl);
}

function filterRequestHeaders(rawHeaders) {
  const headers = {};
  for (const [key, value] of Object.entries(rawHeaders)) {
    if (typeof value === "undefined") continue;
    const lower = key.toLowerCase();
    if (lower === "host" || lower === "content-length" || lower === "connection") {
      continue;
    }
    headers[key] = value;
  }
  return headers;
}

async function readRequestBody(req) {
  const method = (req.method ?? "GET").toUpperCase();
  if (method === "GET" || method === "HEAD") return null;
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function proxyRequest(req, res) {
  const { pathname } = new URL(req.url ?? "/", "http://localhost");
  if (pathname.startsWith(COIN_ICON_PREFIX)) {
    await serveCoinIcon(req, res);
    return;
  }

  const targetUrl = resolveProxyTargetUrl(req.url ?? "/");
  const body = await readRequestBody(req);
  const upstreamResponse = await fetch(targetUrl, {
    method: req.method,
    headers: filterRequestHeaders(req.headers),
    body,
  });

  res.statusCode = upstreamResponse.status;
  for (const [key, value] of upstreamResponse.headers.entries()) {
    res.setHeader(key, value);
  }

  if (!upstreamResponse.body) {
    res.end();
    return;
  }

  Readable.fromWeb(upstreamResponse.body).pipe(res);
}

const server = http.createServer((req, res) => {
  proxyRequest(req, res).catch((err) => {
    res.statusCode = 502;
    res.setHeader("content-type", "application/json");
    res.end(
      JSON.stringify({
        error: "Proxy request failed.",
        message: err?.message ?? String(err),
      }),
    );
  });
});

server.on("error", (err) => {
  if (err?.code === "EADDRINUSE") {
    console.error(
      `[hyperunit-proxy] port ${PORT} is already in use. Set PORT=<free-port> npm run proxy:dev`,
    );
  } else {
    console.error("[hyperunit-proxy] server error:", err);
  }
  process.exit(1);
});

server.listen(PORT, () => {
  console.log(
    `[hyperunit-proxy] listening on http://localhost:${PORT} (app=${APP_ORIGIN})`,
  );
});
