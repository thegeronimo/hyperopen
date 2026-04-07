import { expect, test } from "@playwright/test";
import {
  RELEASE_ROUTE_METADATA_SCRIPT_PATH,
} from "../../release-assets/site_metadata.mjs";
import {
  CONTROL_CACHE_CONTROL,
  IMMUTABLE_CACHE_CONTROL,
  expectedDocumentHeaders,
} from "../../release-assets/security_headers.mjs";

function extractTitle(html) {
  const match = html.match(/<title>([\s\S]*?)<\/title>/i);
  return match ? match[1].trim() : null;
}

function extractCanonicalHref(html) {
  const match = html.match(/<link\b[^>]*rel=["']canonical["'][^>]*href=["']([^"']+)["'][^>]*>/i);
  return match ? match[1] : null;
}

function extractStylesheetHref(html) {
  const match = html.match(/<link\b[^>]*rel=["']stylesheet["'][^>]*href=["']([^"']+)["'][^>]*>/i);
  return match ? match[1] : null;
}

function extractMainScriptHref(html) {
  const match = html.match(/<script\b[^>]*src=["']([^"']*main[^"']*\.js)["'][^>]*><\/script>/i);
  return match ? match[1] : null;
}

test("robots.txt returns plain text without html @smoke", async ({ request }) => {
  const response = await request.get("/robots.txt");
  const body = await response.text();

  expect(response.ok()).toBe(true);
  expect(response.headers()["content-type"]).toContain("text/plain");
  expect(body).toContain("User-agent: *");
  expect(body).toContain("Sitemap:");
  expect(body).not.toMatch(/<html\b/i);
  expect(body).not.toMatch(/<!doctype html/i);
});

test("sitemap.xml returns xml @smoke", async ({ request }) => {
  const response = await request.get("/sitemap.xml");
  const body = await response.text();

  expect(response.ok()).toBe(true);
  expect(response.headers()["content-type"]).toMatch(/xml/i);
  expect(body).toMatch(/^<\?xml version="1\.0" encoding="UTF-8"\?>/i);
  expect(body).toContain("<urlset");
});

test("trade direct load returns the trade-specific title @smoke", async ({ request }) => {
  const response = await request.get("/trade");
  const body = await response.text();

  expect(response.ok()).toBe(true);
  expect(response.headers()["content-type"]).toContain("text/html");
  for (const [headerName, expectedValue] of Object.entries(expectedDocumentHeaders())) {
    expect(response.headers()[headerName]).toBe(expectedValue);
  }
  expect(extractTitle(body)).toBe("Trade perpetuals on Hyperliquid with an open-source client");
  expect(body).not.toMatch(/<script(?![^>]*\bsrc=)[^>]*>/i);
});

test("portfolio direct load returns the portfolio-specific title @smoke", async ({ request }) => {
  const response = await request.get("/portfolio");
  const body = await response.text();

  expect(response.ok()).toBe(true);
  expect(response.headers()["content-type"]).toContain("text/html");
  expect(extractTitle(body)).toBe("Portfolio analytics and tearsheets");
});

test("api direct load keeps canonical metadata lowercase @smoke", async ({ request }) => {
  const response = await request.get("/api");
  const body = await response.text();
  const canonicalHref = extractCanonicalHref(body);

  expect(response.ok()).toBe(true);
  expect(canonicalHref).toBeTruthy();
  expect(new URL(canonicalHref).pathname).toBe("/api");
  expect(canonicalHref).not.toContain("/API");
});

test("release assets expose immutable and control cache rules @smoke", async ({ request }) => {
  const tradeResponse = await request.get("/trade");
  const tradeBody = await tradeResponse.text();
  const cssHref = extractStylesheetHref(tradeBody);
  const mainScriptHref = extractMainScriptHref(tradeBody);

  expect(cssHref).toBeTruthy();
  expect(mainScriptHref).toBeTruthy();

  const cssResponse = await request.get(cssHref);
  const mainScriptResponse = await request.get(mainScriptHref);
  const metadataScriptResponse = await request.get(`/${RELEASE_ROUTE_METADATA_SCRIPT_PATH}`);
  const siteMetadataResponse = await request.get("/site-metadata.json");
  const serviceWorkerResponse = await request.get("/sw.js");

  expect(cssResponse.headers()["cache-control"]).toBe(IMMUTABLE_CACHE_CONTROL);
  expect(mainScriptResponse.headers()["cache-control"]).toBe(IMMUTABLE_CACHE_CONTROL);
  expect(metadataScriptResponse.headers()["cache-control"]).toBe(CONTROL_CACHE_CONTROL);
  expect(siteMetadataResponse.headers()["cache-control"]).toBe(CONTROL_CACHE_CONTROL);
  expect(serviceWorkerResponse.headers()["cache-control"]).toBe(CONTROL_CACHE_CONTROL);
});
