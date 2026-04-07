import {
  RELEASE_ROUTE_METADATA_SCRIPT_PATH,
} from "./site_metadata.mjs";
import {
  CONTROL_CACHE_CONTROL,
  IMMUTABLE_CACHE_CONTROL,
  expectedDocumentHeaders,
} from "./security_headers.mjs";
import { fileURLToPath } from "node:url";

const DEFAULT_DOCUMENT_PATH = "/trade";

function resolveOrigin(argvValue = process.argv[2], envValue = process.env.HYPEROPEN_VERIFY_ORIGIN) {
  const rawValue = typeof argvValue === "string" && argvValue.trim()
    ? argvValue.trim()
    : (typeof envValue === "string" ? envValue.trim() : "");

  if (!rawValue) {
    throw new Error(
      "Provide the deployment origin as the first argument or via HYPEROPEN_VERIFY_ORIGIN."
    );
  }

  return new URL(rawValue).origin;
}

function assertHeaderEquals(response, headerName, expectedValue, label) {
  const actualValue = response.headers.get(headerName);

  if (actualValue !== expectedValue) {
    throw new Error(
      `${label} expected ${headerName}: ${expectedValue} but received ${actualValue ?? "<missing>"}`
    );
  }
}

function extractRequiredMatch(text, pattern, label) {
  const match = text.match(pattern);
  if (!match?.[1]) {
    throw new Error(`Could not find ${label} in deployment HTML.`);
  }

  return match[1];
}

async function requestOk(fetchFn, url) {
  const response = await fetchFn(url, { redirect: "follow" });
  if (!response.ok) {
    throw new Error(`Expected ${url} to return 2xx, received ${response.status}.`);
  }

  return response;
}

async function verifyDocument(fetchFn, origin, pathName) {
  const url = new URL(pathName, origin);
  const response = await requestOk(fetchFn, url);
  const html = await response.text();

  for (const [headerName, expectedValue] of Object.entries(expectedDocumentHeaders())) {
    assertHeaderEquals(response, headerName, expectedValue, url.pathname);
  }

  if (/<script(?![^>]*\bsrc=)[^>]*>/i.test(html)) {
    throw new Error(`${url.pathname} still contains an executable inline <script> tag.`);
  }

  const cssHref = extractRequiredMatch(
    html,
    /<link\b[^>]*rel=["']stylesheet["'][^>]*href=["']([^"']+)["'][^>]*>/i,
    "fingerprinted stylesheet href"
  );
  const mainScriptHref = extractRequiredMatch(
    html,
    /<script\b[^>]*src=["']([^"']*main[^"']*\.js)["'][^>]*><\/script>/i,
    "main script href"
  );

  return { cssHref, html, mainScriptHref, url: url.toString() };
}

async function verifyAssetCache(fetchFn, origin, assetPath, expectedCacheControl) {
  const url = new URL(assetPath, origin);
  const response = await requestOk(fetchFn, url);
  assertHeaderEquals(response, "cache-control", expectedCacheControl, url.pathname);
  return url.pathname;
}

export async function verifyDeploymentHeaders({
  origin,
  fetchFn = fetch,
  logFn = () => {},
} = {}) {
  const normalizedOrigin = resolveOrigin(origin);
  const documentResult = await verifyDocument(fetchFn, normalizedOrigin, DEFAULT_DOCUMENT_PATH);

  const verifiedPaths = [];
  verifiedPaths.push(
    await verifyAssetCache(fetchFn, normalizedOrigin, documentResult.cssHref, IMMUTABLE_CACHE_CONTROL)
  );
  verifiedPaths.push(
    await verifyAssetCache(fetchFn, normalizedOrigin, documentResult.mainScriptHref, IMMUTABLE_CACHE_CONTROL)
  );
  verifiedPaths.push(
    await verifyAssetCache(
      fetchFn,
      normalizedOrigin,
      `/${RELEASE_ROUTE_METADATA_SCRIPT_PATH}`,
      CONTROL_CACHE_CONTROL
    )
  );
  verifiedPaths.push(
    await verifyAssetCache(fetchFn, normalizedOrigin, "/site-metadata.json", CONTROL_CACHE_CONTROL)
  );
  verifiedPaths.push(
    await verifyAssetCache(fetchFn, normalizedOrigin, "/sw.js", CONTROL_CACHE_CONTROL)
  );

  const summary = {
    origin: normalizedOrigin,
    documentPath: DEFAULT_DOCUMENT_PATH,
    verifiedPaths,
  };

  logFn(
    `Verified deployment headers for ${normalizedOrigin}: ${[
      DEFAULT_DOCUMENT_PATH,
      ...verifiedPaths,
    ].join(", ")}`
  );

  return summary;
}

async function main() {
  const summary = await verifyDeploymentHeaders({
    origin: process.argv[2] || process.env.HYPEROPEN_VERIFY_ORIGIN,
    logFn: (message) => process.stdout.write(`${message}\n`),
  });

  process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
}

const entryScriptPath = process.argv[1]
  ? process.argv[1]
  : null;
const currentFilePath = fileURLToPath(import.meta.url);

if (entryScriptPath && currentFilePath === entryScriptPath) {
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}
