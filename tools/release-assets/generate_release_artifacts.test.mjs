import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  DEFAULT_CANONICAL_ORIGIN,
  RELEASE_APP_SHELL_PLACEHOLDER,
  RELEASE_ROUTE_METADATA_SCRIPT_PATH,
  RELEASE_SEO_PLACEHOLDER,
  buildSiteMetadata,
  extractHeadRootAssetPublicPaths,
  normalizeCanonicalOrigin,
} from "./site_metadata.mjs";
import {
  CONTROL_CACHE_CONTROL,
  IMMUTABLE_CACHE_CONTROL,
  buildContentSecurityPolicy,
} from "./security_headers.mjs";
import {
  collectReleaseJavaScriptFiles,
  fingerprintFileName,
  generateReleaseArtifacts,
  hashContent,
  normalizeModuleUriToRelativeJsPath,
  resolveReleaseBuildInfo,
  resolveReleaseBuildId,
  rewriteMainModuleLoaderRuntime,
  rewriteAppIndexHtml,
  routePathToOutputHtmlPath,
} from "./generate_release_artifacts.mjs";
import { writeBuildIdFile } from "./write_build_id_file.mjs";

const SAMPLE_CANONICAL_ORIGIN = "https://app.hyperopen.example";
const SAMPLE_ROUTE_TITLE = "Trade perpetuals on Hyperliquid with an open-source client";
const SAMPLE_ROUTE_DESCRIPTION =
  "Use Hyperopen to trade Hyperliquid perpetuals with charts, order entry, and account context in one open-source client.";
const SAMPLE_RELEASE_SEO_HEAD_MARKUP = [
  '<link rel="canonical" href="https://app.hyperopen.example/trade" />',
  '<meta property="og:title" content="Trade perpetuals on Hyperliquid with an open-source client" />',
].join("\n");
const SAMPLE_RELEASE_APP_SHELL_MARKUP = [
  '<div data-hyperopen-route-shell="trade">',
  "  <h1>Trade perpetuals on Hyperliquid</h1>",
  "  <p>Loading the open-source trading workspace.</p>",
  "</div>",
].join("\n");
const SAMPLE_RELEASE_METADATA_SCRIPT_HREF = "/js/release-route-metadata.js";
const STATIC_ROUTE_PATHS = [
  "/",
  "/trade",
  "/portfolio",
  "/leaderboard",
  "/vaults",
  "/staking",
  "/funding-comparison",
  "/api",
];
const STATIC_ROUTE_HTML_FILES = STATIC_ROUTE_PATHS.map((routePath) =>
  routePathToOutputHtmlPath(routePath)
).sort();
const SAMPLE_MAIN_RELEASE_BUNDLE = [
  "function boot() { return true; }",
  "var loaderManager=new Tca;loaderManager.pk=!0;",
  "boot();",
  "",
].join("\n");

function buildSampleIndexHtml() {
  return `<!DOCTYPE html>
<html>
  <head>
    <meta
      name="description"
      content="Hyperopen - Open Source Trading Interface"
    />
    <title>Hyperopen</title>
    ${RELEASE_SEO_PLACEHOLDER}
    <meta property="og:image" content="/og/hyperopen-share.png" />
    <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
    <link rel="icon" type="image/png" sizes="32x32" href="/favicon-32x32.png" />
    <link rel="icon" type="image/png" sizes="16x16" href="/favicon-16x16.png" />
    <link rel="apple-touch-icon" href="/apple-touch-icon.png" />
    <link rel="shortcut icon" href="/favicon.ico" />
    <link rel="stylesheet" href="/css/main.css" />
  </head>
  <body>
    <div id="app" class="h-full">${RELEASE_APP_SHELL_PLACEHOLDER}</div>
    <script>
      (function () {
        const manifestUrl = "/js/manifest.json";
        const defaultMainScriptUrl = "/js/main.js";

        fetch(manifestUrl, { cache: "no-cache" })
          .then((response) => (response.ok ? response.json() : null))
          .then(() => {
            document.body.dataset.loaded = defaultMainScriptUrl;
          });
      })();
    </script>
  </body>
</html>`;
}

function extractTitle(documentHtml) {
  const match = documentHtml.match(/<title>([\s\S]*?)<\/title>/i);
  return match ? match[1].trim() : null;
}

function extractCanonicalHref(documentHtml) {
  const match = documentHtml.match(/<link\b[^>]*rel=["']canonical["'][^>]*href=["']([^"']+)["'][^>]*>/i);
  return match ? match[1] : null;
}

function extractMetaContent(documentHtml, { name, property }) {
  const attributeName = name ? "name" : "property";
  const attributeValue = name ?? property;
  const pattern = new RegExp(
    `<meta\\b[^>]*${attributeName}=(["'])${attributeValue.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\1[^>]*content=(["'])([\\s\\S]*?)\\2[^>]*>`,
    "i"
  );
  const match = documentHtml.match(pattern);
  return match ? match[3] : null;
}

function escapeRegex(text) {
  return String(text).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

async function writeFixtureFile(root, relativePath, content, missingRelativePaths) {
  if (missingRelativePaths.has(relativePath)) {
    return;
  }

  const absolutePath = path.join(root, relativePath);
  await fs.mkdir(path.dirname(absolutePath), { recursive: true });
  await fs.writeFile(absolutePath, content);
}

async function writeReleaseFixture(sourceRoot, { missingRelativePaths = [] } = {}) {
  const missing = new Set(missingRelativePaths);

  await fs.mkdir(sourceRoot, { recursive: true });
  await writeFixtureFile(sourceRoot, "index.html", buildSampleIndexHtml(), missing);
  await writeFixtureFile(
    sourceRoot,
    path.join("css", "main.css"),
    "body { color: white; }\n",
    missing
  );
  await writeFixtureFile(
    sourceRoot,
    path.join("js", "manifest.json"),
    JSON.stringify([
      { "module-id": "main", "output-name": "main.HASH.js" },
      { "module-id": "trade_chart", "output-name": "trade_chart.CHUNK.js" },
    ]),
    missing
  );
  await writeFixtureFile(
    sourceRoot,
    path.join("js", "module-loader.json"),
    JSON.stringify({
      "module-uris": {
        main: [],
        trade_chart: ["/js/trade_chart.CHUNK.js"],
      },
    }),
    missing
  );
  await writeFixtureFile(
    sourceRoot,
    path.join("js", "main.HASH.js"),
    SAMPLE_MAIN_RELEASE_BUNDLE,
    missing
  );
  await writeFixtureFile(
    sourceRoot,
    path.join("js", "trade_chart.CHUNK.js"),
    "console.log('chunk');\n",
    missing
  );
  await writeFixtureFile(
    sourceRoot,
    path.join("js", "portfolio_worker.js"),
    "worker();\n",
    missing
  );
  await writeFixtureFile(
    sourceRoot,
    path.join("js", "vault_detail_worker.js"),
    "worker();\n",
    missing
  );
  await writeFixtureFile(sourceRoot, path.join("js", "main.js"), "stale();\n", missing);
  await writeFixtureFile(sourceRoot, path.join("js", "portfolio.js"), "stale();\n", missing);
  await writeFixtureFile(
    sourceRoot,
    path.join("js", "cljs-runtime", "stale.map"),
    "stale",
    missing
  );
  await writeFixtureFile(
    sourceRoot,
    path.join("fonts", "InterVariable.woff2"),
    "font",
    missing
  );
  await writeFixtureFile(
    sourceRoot,
    "sw.js",
    "self.addEventListener('fetch', () => {});",
    missing
  );
  await writeFixtureFile(sourceRoot, "favicon.svg", "<svg></svg>\n", missing);
  await writeFixtureFile(sourceRoot, "favicon-16x16.png", "png16", missing);
  await writeFixtureFile(sourceRoot, "favicon-32x32.png", "png32", missing);
  await writeFixtureFile(sourceRoot, "apple-touch-icon.png", "apple", missing);
  await writeFixtureFile(sourceRoot, "favicon.ico", "ico", missing);
  await writeFixtureFile(sourceRoot, path.join("og", "hyperopen-share.png"), "og", missing);
  await writeFixtureFile(sourceRoot, "ui-workbench.html", "<html></html>", missing);
}

test("rewriteAppIndexHtml replaces the default stylesheet href and injects route SEO markup", () => {
  const rewritten = rewriteAppIndexHtml(buildSampleIndexHtml(), {
    cssHref: "/css/main.ABC123.css",
    releaseMetadataScriptHref: SAMPLE_RELEASE_METADATA_SCRIPT_HREF,
    mainScriptHref: "/js/main.HASH.js",
    title: SAMPLE_ROUTE_TITLE,
    description: SAMPLE_ROUTE_DESCRIPTION,
    releaseSeoHeadMarkup: SAMPLE_RELEASE_SEO_HEAD_MARKUP,
    releaseAppShellMarkup: SAMPLE_RELEASE_APP_SHELL_MARKUP,
  });

  assert.equal(extractTitle(rewritten), SAMPLE_ROUTE_TITLE);
  assert.equal(
    extractMetaContent(rewritten, { name: "description" }),
    SAMPLE_ROUTE_DESCRIPTION
  );
  assert.equal(
    extractCanonicalHref(rewritten),
    "https://app.hyperopen.example/trade"
  );
  assert.match(rewritten, /href="\/css\/main\.ABC123\.css"/);
  assert.doesNotMatch(rewritten, /href="\/css\/main\.css"/);
  assert.match(
    rewritten,
    /<script defer src="\/js\/release-route-metadata\.js"><\/script>/
  );
  assert.match(rewritten, /<script defer src="\/js\/main\.HASH\.js"><\/script>/);
  assert.doesNotMatch(rewritten, /manifestUrl/);
  assert.doesNotMatch(rewritten, /hyperopen-site-metadata/);
  assert.doesNotMatch(rewritten, /<script(?![^>]*\bsrc=)[^>]*>/i);
  assert.doesNotMatch(rewritten, new RegExp(RELEASE_SEO_PLACEHOLDER));
  assert.doesNotMatch(rewritten, new RegExp(RELEASE_APP_SHELL_PLACEHOLDER));
  assert.match(rewritten, /data-hyperopen-route-shell="trade"/);
});

test("fingerprint helpers preserve the base filename and use uppercase hashes", () => {
  const fingerprint = hashContent(Buffer.from("abc"));
  assert.equal(fingerprint, fingerprint.toUpperCase());
  assert.equal(fingerprintFileName("main.css", fingerprint), `main.${fingerprint}.css`);
});

test("normalizeCanonicalOrigin prefers a stable default over hash-prefixed preview urls", () => {
  assert.equal(normalizeCanonicalOrigin(undefined), DEFAULT_CANONICAL_ORIGIN);
  assert.equal(
    normalizeCanonicalOrigin("https://2f1a3b4c.hyperopen.pages.dev/path?x=1#frag"),
    DEFAULT_CANONICAL_ORIGIN
  );
  assert.equal(
    normalizeCanonicalOrigin("https://app.hyperopen.example/trade"),
    "https://app.hyperopen.example"
  );
});

test("extractHeadRootAssetPublicPaths captures favicon and social assets from the head", () => {
  assert.deepEqual(extractHeadRootAssetPublicPaths(buildSampleIndexHtml()), [
    "/apple-touch-icon.png",
    "/favicon-16x16.png",
    "/favicon-32x32.png",
    "/favicon.ico",
    "/favicon.svg",
    "/og/hyperopen-share.png",
  ]);
});

test("collectReleaseJavaScriptFiles keeps only explicit release assets", () => {
  const files = collectReleaseJavaScriptFiles({
    manifest: [
      { "module-id": "main", "output-name": "main.HASH.js" },
      { "module-id": "trade_chart", "output-name": "trade_chart.HASH.js" },
    ],
    moduleLoader: {
      "module-uris": {
        main: [],
        trade_chart: ["/js/trade_chart.HASH.js"],
      },
    },
  });

  assert.deepEqual(files, [
    "main.HASH.js",
    "module-loader.json",
    "portfolio_worker.js",
    "trade_chart.HASH.js",
    "vault_detail_worker.js",
  ]);
});

test("rewriteMainModuleLoaderRuntime switches shadow-cljs release loading to script tags", () => {
  const rewritten = rewriteMainModuleLoaderRuntime(SAMPLE_MAIN_RELEASE_BUNDLE);

  assert.match(rewritten, /var loaderManager=new Tca;loaderManager\.tk=!0;/);
  assert.doesNotMatch(rewritten, /loaderManager\.pk=!0/);
});

test("rewriteMainModuleLoaderRuntime tolerates constructor minification changes", () => {
  const rewritten = rewriteMainModuleLoaderRuntime(
    "var loaderManager=new Uca;loaderManager.pk=!0;\n"
  );

  assert.match(rewritten, /var loaderManager=new Uca;loaderManager\.tk=!0;/);
  assert.doesNotMatch(rewritten, /loaderManager\.pk=!0/);
});

test("normalizeModuleUriToRelativeJsPath preserves nested js paths and strips query strings", () => {
  assert.equal(
    normalizeModuleUriToRelativeJsPath("/js/chunks/trade_chart.HASH.js?v=1"),
    "chunks/trade_chart.HASH.js"
  );
  assert.equal(normalizeModuleUriToRelativeJsPath("/assets/trade_chart.HASH.js"), null);
});

test("buildSiteMetadata keeps /api lowercase in the generated metadata", () => {
  const metadata = buildSiteMetadata({
    canonicalOrigin: SAMPLE_CANONICAL_ORIGIN,
    indexHtml: buildSampleIndexHtml(),
  });

  assert.equal(metadata.origin, SAMPLE_CANONICAL_ORIGIN);
  assert.equal(metadata.routes.find((route) => route.id === "api")?.path, "/api");
  assert.ok(!metadata.routes.some((route) => route.path === "/API"));
});

test("buildSiteMetadata preserves a non-empty release build id", () => {
  const metadata = buildSiteMetadata({
    canonicalOrigin: SAMPLE_CANONICAL_ORIGIN,
    indexHtml: buildSampleIndexHtml(),
    buildId: "abc123def456",
  });

  assert.equal(metadata.buildId, "abc123def456");
});

test("buildSiteMetadata preserves normalized release build info", () => {
  const metadata = buildSiteMetadata({
    canonicalOrigin: SAMPLE_CANONICAL_ORIGIN,
    indexHtml: buildSampleIndexHtml(),
    buildInfo: {
      sha: "f18fbc2a3b00e4e324b39796ffdc1a9cd9cff7e619c",
      short: "f18fbc2",
      branch: "main",
      message: "perf(portfolio): memoize returns chart series",
      deployedAt: "2026-04-17T14:28:00.000Z",
      env: "prod",
      region: "global",
    },
  });

  assert.deepEqual(metadata.build, {
    sha: "f18fbc2a3b00e4e324b39796ffdc1a9cd9cff7e619c",
    short: "f18fbc2",
    branch: "main",
    message: "perf(portfolio): memoize returns chart series",
    deployedAt: "2026-04-17T14:28:00.000Z",
    env: "prod",
    region: "global",
  });
  assert.equal(metadata.buildId, "f18fbc2a3b00e4e324b39796ffdc1a9cd9cff7e619c");
});

test("resolveReleaseBuildId prefers the environment override", () => {
  const previousBuildId = process.env.HYPEROPEN_BUILD_ID;
  process.env.HYPEROPEN_BUILD_ID = "env-build-id";

  try {
    assert.equal(resolveReleaseBuildId(), "env-build-id");
  } finally {
    if (previousBuildId === undefined) {
      delete process.env.HYPEROPEN_BUILD_ID;
    } else {
      process.env.HYPEROPEN_BUILD_ID = previousBuildId;
    }
  }
});

test("resolveReleaseBuildInfo prefers explicit and Cloudflare Pages metadata", () => {
  const previousEnv = {
    HYPEROPEN_BUILD_ID: process.env.HYPEROPEN_BUILD_ID,
    HYPEROPEN_BUILD_MESSAGE: process.env.HYPEROPEN_BUILD_MESSAGE,
    HYPEROPEN_DEPLOYED_AT: process.env.HYPEROPEN_DEPLOYED_AT,
    HYPEROPEN_BUILD_ENV: process.env.HYPEROPEN_BUILD_ENV,
    HYPEROPEN_BUILD_REGION: process.env.HYPEROPEN_BUILD_REGION,
    CF_PAGES_COMMIT_SHA: process.env.CF_PAGES_COMMIT_SHA,
    CF_PAGES_BRANCH: process.env.CF_PAGES_BRANCH,
  };
  process.env.CF_PAGES_COMMIT_SHA = "9b2ef51a8cc0d4e19bf832a04eebfc71c2a09a81d6";
  process.env.CF_PAGES_BRANCH = "feature/twap-preview";
  process.env.HYPEROPEN_BUILD_MESSAGE = "feat(trade): add TWAP slicing preview";
  process.env.HYPEROPEN_DEPLOYED_AT = "2026-04-17T15:02:00.000Z";
  process.env.HYPEROPEN_BUILD_ENV = "staging";
  process.env.HYPEROPEN_BUILD_REGION = "global";
  delete process.env.HYPEROPEN_BUILD_ID;

  try {
    assert.deepEqual(resolveReleaseBuildInfo(), {
      sha: "9b2ef51a8cc0d4e19bf832a04eebfc71c2a09a81d6",
      short: "9b2ef51",
      branch: "feature/twap-preview",
      message: "feat(trade): add TWAP slicing preview",
      deployedAt: "2026-04-17T15:02:00.000Z",
      env: "staging",
      region: "global",
    });
  } finally {
    for (const [key, value] of Object.entries(previousEnv)) {
      if (value === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = value;
      }
    }
  }
});

test("resolveReleaseBuildInfo does not include commit-link metadata", () => {
  const previousEnv = {
    HYPEROPEN_BUILD_ID: process.env.HYPEROPEN_BUILD_ID,
  };
  process.env.HYPEROPEN_BUILD_ID = "10666e2abcdef10666e2abcdef10666e2abcdef0";

  try {
    assert.equal(Object.hasOwn(resolveReleaseBuildInfo(), "commitHref"), false);
  } finally {
    for (const [key, value] of Object.entries(previousEnv)) {
      if (value === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = value;
      }
    }
  }
});

test("writeBuildIdFile writes the resolved build id to a public text asset", async () => {
  const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-build-id-"));
  const outputPath = path.join(tempRoot, "build-id.txt");

  const result = await writeBuildIdFile({
    outputPath,
    buildId: "10666e2abcdef",
  });

  assert.equal(result.buildId, "10666e2abcdef");
  assert.equal(await fs.readFile(outputPath, "utf8"), "10666e2abcdef\n");
});

test("generateReleaseArtifacts assembles deterministic route-specific release pages", async () => {
  const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-release-assets-"));
  const sourceRoot = path.join(tempRoot, "source");
  const outputRoot = path.join(tempRoot, "output");

  await writeReleaseFixture(sourceRoot);

  const result = await generateReleaseArtifacts({
    sourceRoot,
    outputRoot,
    canonicalOrigin: SAMPLE_CANONICAL_ORIGIN,
  });

  const generatedRootIndex = await fs.readFile(path.join(outputRoot, "index.html"), "utf8");
  const generatedTrade = await fs.readFile(path.join(outputRoot, "trade", "index.html"), "utf8");
  const generatedPortfolio = await fs.readFile(
    path.join(outputRoot, "portfolio", "index.html"),
    "utf8"
  );
  const generatedApi = await fs.readFile(path.join(outputRoot, "api", "index.html"), "utf8");
  const generatedCss = await fs.readFile(
    path.join(outputRoot, "css", result.cssFileName),
    "utf8"
  );
  const generatedHeaders = await fs.readFile(path.join(outputRoot, "_headers"), "utf8");
  const copiedMain = await fs.readFile(path.join(outputRoot, "js", "main.HASH.js"), "utf8");
  const routeMetadataScript = await fs.readFile(
    path.join(outputRoot, RELEASE_ROUTE_METADATA_SCRIPT_PATH),
    "utf8"
  );
  const copiedFont = await fs.readFile(
    path.join(outputRoot, "fonts", "InterVariable.woff2"),
    "utf8"
  );
  const robotsTxt = await fs.readFile(path.join(outputRoot, "robots.txt"), "utf8");
  const sitemapXml = await fs.readFile(path.join(outputRoot, "sitemap.xml"), "utf8");
  const siteMetadata = JSON.parse(
    await fs.readFile(path.join(outputRoot, "site-metadata.json"), "utf8")
  );

  assert.deepEqual(result.generatedRouteHtmlFiles, STATIC_ROUTE_HTML_FILES);
  for (const relativePath of STATIC_ROUTE_HTML_FILES) {
    await fs.access(path.join(outputRoot, relativePath));
  }

  assert.match(generatedTrade, new RegExp(`href="${result.cssHref.replace(".", "\\.")}"`));
  assert.match(
    generatedTrade,
    new RegExp(
      `<script defer src="${escapeRegex(result.releaseMetadataScriptHref)}"><\\/script>`
    )
  );
  assert.match(generatedTrade, /<script defer src="\/js\/main\.HASH\.js"><\/script>/);
  assert.doesNotMatch(generatedTrade, /manifestUrl/);
  assert.doesNotMatch(generatedTrade, /hyperopen-site-metadata/);
  assert.doesNotMatch(generatedTrade, /<script(?![^>]*\bsrc=)[^>]*>/i);

  assert.equal(
    extractTitle(generatedTrade),
    "Trade perpetuals on Hyperliquid with an open-source client"
  );
  assert.equal(
    extractMetaContent(generatedTrade, { name: "description" }),
    "Use Hyperopen to trade Hyperliquid perpetuals with charts, order entry, and account context in one open-source client."
  );
  assert.equal(extractCanonicalHref(generatedTrade), `${SAMPLE_CANONICAL_ORIGIN}/trade`);
  assert.equal(
    extractMetaContent(generatedTrade, { property: "og:title" }),
    "Trade perpetuals on Hyperliquid with an open-source client"
  );
  assert.equal(
    extractMetaContent(generatedTrade, { property: "og:description" }),
    "Use Hyperopen to trade Hyperliquid perpetuals with charts, order entry, and account context in one open-source client."
  );
  assert.equal(
    extractMetaContent(generatedTrade, { property: "og:url" }),
    `${SAMPLE_CANONICAL_ORIGIN}/trade`
  );
  assert.equal(extractMetaContent(generatedTrade, { name: "twitter:card" }), "summary");
  assert.equal(
    extractMetaContent(generatedTrade, { name: "twitter:title" }),
    "Trade perpetuals on Hyperliquid with an open-source client"
  );
  assert.equal(
    extractMetaContent(generatedTrade, { name: "twitter:description" }),
    "Use Hyperopen to trade Hyperliquid perpetuals with charts, order entry, and account context in one open-source client."
  );
  assert.match(generatedTrade, /<h1[^>]*>Trade perpetuals on Hyperliquid<\/h1>/);
  assert.match(
    generatedTrade,
    /<p[^>]*>Loading the open-source trading workspace with charts, order entry, and market context\.<\/p>/
  );

  assert.equal(extractTitle(generatedPortfolio), "Portfolio analytics and tearsheets");
  assert.equal(
    extractMetaContent(generatedPortfolio, { name: "description" }),
    "Review portfolio analytics, position history, and trader tearsheets from Hyperopen's Hyperliquid client."
  );
  assert.equal(
    extractCanonicalHref(generatedPortfolio),
    `${SAMPLE_CANONICAL_ORIGIN}/portfolio`
  );
  assert.equal(
    extractMetaContent(generatedPortfolio, { property: "og:url" }),
    `${SAMPLE_CANONICAL_ORIGIN}/portfolio`
  );
  assert.match(generatedPortfolio, /<h1[^>]*>Portfolio analytics and tearsheets<\/h1>/);
  assert.notEqual(extractTitle(generatedTrade), extractTitle(generatedPortfolio));
  assert.notEqual(
    extractMetaContent(generatedTrade, { name: "description" }),
    extractMetaContent(generatedPortfolio, { name: "description" })
  );

  assert.equal(extractCanonicalHref(generatedRootIndex), `${SAMPLE_CANONICAL_ORIGIN}/`);
  assert.equal(extractCanonicalHref(generatedApi), `${SAMPLE_CANONICAL_ORIGIN}/api`);
  assert.equal(
    extractMetaContent(generatedApi, { property: "og:url" }),
    `${SAMPLE_CANONICAL_ORIGIN}/api`
  );
  assert.equal(
    extractMetaContent(generatedApi, { name: "twitter:title" }),
    "API wallet authorization and management"
  );
  assert.doesNotMatch(generatedApi, /\/API/);

  assert.equal(generatedCss, "body { color: white; }\n");
  assert.match(copiedMain, /var loaderManager=new Tca;loaderManager\.tk=!0;/);
  assert.doesNotMatch(copiedMain, /loaderManager\.pk=!0/);
  assert.equal(copiedFont, "font");
  assert.match(routeMetadataScript, /const metadata = \{/);
  assert.match(routeMetadataScript, /globalThis\.HYPEROPEN_BUILD_ID = buildId;/);
  assert.match(routeMetadataScript, /globalThis\.HYPEROPEN_BUILD = build;/);
  assert.match(routeMetadataScript, /window\.addEventListener\("popstate", syncMetadata\)/);

  assert.equal(
    robotsTxt,
    `User-agent: *\nAllow: /\nSitemap: ${SAMPLE_CANONICAL_ORIGIN}/sitemap.xml\n`
  );
  assert.match(robotsTxt, /^User-agent: \*/);
  assert.doesNotMatch(robotsTxt, /^<\?xml/m);
  assert.match(sitemapXml, /^<\?xml version="1\.0" encoding="UTF-8"\?>/);
  for (const route of STATIC_ROUTE_PATHS) {
    assert.match(
      sitemapXml,
      new RegExp(
        `<loc>${SAMPLE_CANONICAL_ORIGIN.replace(/\./g, "\\.")}${route === "/" ? "/" : route.replace(/\//g, "\\/")}</loc>`
      )
    );
  }

  assert.equal(siteMetadata.origin, SAMPLE_CANONICAL_ORIGIN);
  assert.equal(siteMetadata.build.sha.length, 40);
  assert.equal(siteMetadata.build.short.length, 7);
  assert.match(siteMetadata.build.deployedAt, /^\d{4}-\d{2}-\d{2}T/);
  assert.equal(Object.hasOwn(siteMetadata.build, "commitHref"), false);
  assert.equal(siteMetadata.routes.find((route) => route.id === "api")?.path, "/api");
  assert.deepEqual(result.immutableAssetPaths, [
    `/css/${result.cssFileName}`,
    "/js/main.HASH.js",
    "/js/trade_chart.CHUNK.js",
  ]);
  assert.equal(result.releaseMetadataScriptHref, `/${RELEASE_ROUTE_METADATA_SCRIPT_PATH}`);
  assert.equal(result.securityHeadersPath, path.join(outputRoot, "_headers"));
  assert.deepEqual(result.copiedRootAssetPaths, [
    "apple-touch-icon.png",
    "favicon-16x16.png",
    "favicon-32x32.png",
    "favicon.ico",
    "favicon.svg",
    "og/hyperopen-share.png",
    "sw.js",
  ]);
  assert.deepEqual(siteMetadata.rootAssetPaths, [
    "/apple-touch-icon.png",
    "/favicon-16x16.png",
    "/favicon-32x32.png",
    "/favicon.ico",
    "/favicon.svg",
    "/og/hyperopen-share.png",
    "/sw.js",
  ]);

  for (const relativePath of result.copiedRootAssetPaths) {
    await fs.access(path.join(outputRoot, relativePath));
  }

  assert.match(generatedHeaders, /^\/\*\n/m);
  assert.match(
    generatedHeaders,
    new RegExp(
      `Content-Security-Policy: ${escapeRegex(buildContentSecurityPolicy())}`
    )
  );
  assert.match(
    generatedHeaders,
    new RegExp(`Cache-Control: ${escapeRegex(CONTROL_CACHE_CONTROL)}`)
  );
  assert.match(
    generatedHeaders,
    new RegExp(
      `^${escapeRegex(`/css/${result.cssFileName}`)}\\n  ! Cache-Control\\n  Cache-Control: ${escapeRegex(IMMUTABLE_CACHE_CONTROL)}`,
      "m"
    )
  );
  assert.match(
    generatedHeaders,
    new RegExp(
      `^${escapeRegex("/js/main.HASH.js")}\\n  ! Cache-Control\\n  Cache-Control: ${escapeRegex(IMMUTABLE_CACHE_CONTROL)}`,
      "m"
    )
  );
  assert.match(
    generatedHeaders,
    new RegExp(
      `^${escapeRegex("/js/trade_chart.CHUNK.js")}\\n  ! Cache-Control\\n  Cache-Control: ${escapeRegex(IMMUTABLE_CACHE_CONTROL)}`,
      "m"
    )
  );
  assert.doesNotMatch(generatedHeaders, /\/js\/module-loader\.json\n  ! Cache-Control/);
  assert.doesNotMatch(generatedHeaders, /\/js\/release-route-metadata\.js\n  ! Cache-Control/);

  await assert.rejects(fs.access(path.join(outputRoot, "css", "main.css")));
  await assert.rejects(fs.access(path.join(outputRoot, "js", "manifest.json")));
  await assert.rejects(fs.access(path.join(outputRoot, "js", "main.js")));
  await assert.rejects(fs.access(path.join(outputRoot, "js", "portfolio.js")));
  await assert.rejects(fs.access(path.join(outputRoot, "js", "cljs-runtime", "stale.map")));
  await assert.rejects(fs.access(path.join(outputRoot, "ui-workbench.html")));
});

test("generateReleaseArtifacts fails closed when a required release asset is missing", async () => {
  const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-release-assets-missing-"));
  const sourceRoot = path.join(tempRoot, "source");
  const outputRoot = path.join(tempRoot, "output");

  await writeReleaseFixture(sourceRoot, {
    missingRelativePaths: [path.join("js", "portfolio_worker.js")],
  });

  await assert.rejects(
    generateReleaseArtifacts({ sourceRoot, outputRoot }),
    /Expected release asset to exist: js\/portfolio_worker\.js/
  );
});

test("generateReleaseArtifacts fails closed when the main module loader runtime is not rewritable", async () => {
  const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-release-assets-loader-"));
  const sourceRoot = path.join(tempRoot, "source");
  const outputRoot = path.join(tempRoot, "output");

  await writeReleaseFixture(sourceRoot);
  await fs.writeFile(path.join(sourceRoot, "js", "main.HASH.js"), "console.log('main');\n");

  await assert.rejects(
    generateReleaseArtifacts({ sourceRoot, outputRoot }),
    /Expected the main module to contain the shadow-cljs eval-based loader runtime/
  );
});

test("generateReleaseArtifacts fails closed when a declared root SEO asset is missing", async () => {
  const tempRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "hyperopen-release-assets-missing-root-")
  );
  const sourceRoot = path.join(tempRoot, "source");
  const outputRoot = path.join(tempRoot, "output");

  await writeReleaseFixture(sourceRoot, {
    missingRelativePaths: ["favicon.svg"],
  });

  await assert.rejects(
    generateReleaseArtifacts({ sourceRoot, outputRoot }),
    /Expected release root asset to exist: favicon\.svg/
  );
});

test("rewriteAppIndexHtml works against the real tracked app entry", async () => {
  const projectRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..", "..");
  const realIndexPath = path.join(projectRoot, "resources", "public", "index.html");
  const realIndexHtml = await fs.readFile(realIndexPath, "utf8");

  const rewritten = rewriteAppIndexHtml(realIndexHtml, {
    cssHref: "/css/main.TEST.css",
    releaseMetadataScriptHref: SAMPLE_RELEASE_METADATA_SCRIPT_HREF,
    mainScriptHref: "/js/main.TEST.js",
    title: SAMPLE_ROUTE_TITLE,
    description: SAMPLE_ROUTE_DESCRIPTION,
    releaseSeoHeadMarkup: SAMPLE_RELEASE_SEO_HEAD_MARKUP,
    releaseAppShellMarkup: SAMPLE_RELEASE_APP_SHELL_MARKUP,
  });

  assert.equal(extractTitle(rewritten), SAMPLE_ROUTE_TITLE);
  assert.equal(
    extractMetaContent(rewritten, { name: "description" }),
    SAMPLE_ROUTE_DESCRIPTION
  );
  assert.match(rewritten, /href="\/css\/main\.TEST\.css"/);
  assert.match(
    rewritten,
    /<script defer src="\/js\/release-route-metadata\.js"><\/script>/
  );
  assert.match(rewritten, /<script defer src="\/js\/main\.TEST\.js"><\/script>/);
  assert.match(rewritten, /rel="canonical"/);
  assert.match(rewritten, /data-hyperopen-route-shell="trade"/);
  assert.doesNotMatch(rewritten, /manifestUrl/);
  assert.doesNotMatch(rewritten, /<script(?![^>]*\bsrc=)[^>]*>/i);
  assert.doesNotMatch(rewritten, new RegExp(RELEASE_SEO_PLACEHOLDER));
  assert.doesNotMatch(rewritten, new RegExp(RELEASE_APP_SHELL_PLACEHOLDER));
});
