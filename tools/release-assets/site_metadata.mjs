import path from "node:path";

export const CANONICAL_ORIGIN_ENV_VAR = "HYPEROPEN_SITE_ORIGIN";
export const DEFAULT_CANONICAL_ORIGIN = "https://hyperopen.xyz";
export const SITE_METADATA_FILE_PATH = "site-metadata.json";
export const ROBOTS_FILE_PATH = "robots.txt";
export const SITEMAP_FILE_PATH = "sitemap.xml";
export const RELEASE_SEO_PLACEHOLDER = "<!-- HYPEROPEN_RELEASE_SEO_HEAD -->";
export const RELEASE_APP_SHELL_PLACEHOLDER =
  "<!-- HYPEROPEN_RELEASE_APP_LOADING_SHELL -->";
export const REQUIRED_ROOT_PUBLIC_PATHS = ["/sw.js"];
export const DEFAULT_TWITTER_CARD = "summary";

export const PUBLIC_ROUTE_METADATA = [
  {
    id: "home",
    path: "/",
    match: "exact",
    title: "Hyperopen - Open-source Hyperliquid trading client",
    description:
      "Open-source Hyperliquid client for perpetual trading, portfolio analytics, vault tracking, staking, and API wallet access.",
    heroTitle: "Hyperopen",
    heroDescription:
      "Loading the open-source Hyperliquid trading client for perpetuals, analytics, and account tools.",
    twitterCard: DEFAULT_TWITTER_CARD,
  },
  {
    id: "trade",
    path: "/trade",
    match: "prefix",
    title: "Trade perpetuals on Hyperliquid with an open-source client",
    description:
      "Use Hyperopen to trade Hyperliquid perpetuals with charts, order entry, and account context in one open-source client.",
    heroTitle: "Trade perpetuals on Hyperliquid",
    heroDescription:
      "Loading the open-source trading workspace with charts, order entry, and market context.",
    twitterCard: DEFAULT_TWITTER_CARD,
  },
  {
    id: "portfolio",
    path: "/portfolio",
    match: "prefix",
    title: "Portfolio analytics and tearsheets",
    description:
      "Review portfolio analytics, position history, and trader tearsheets from Hyperopen's Hyperliquid client.",
    heroTitle: "Portfolio analytics and tearsheets",
    heroDescription:
      "Loading trader analytics, position history, and performance tearsheets.",
    twitterCard: DEFAULT_TWITTER_CARD,
  },
  {
    id: "leaderboard",
    path: "/leaderboard",
    match: "exact",
    title: "Hyperliquid trader leaderboard",
    description:
      "Track ranked Hyperliquid trader performance, returns, and leaderboard context in Hyperopen.",
    heroTitle: "Hyperliquid trader leaderboard",
    heroDescription:
      "Loading ranked trader performance, returns, and leaderboard context.",
    twitterCard: DEFAULT_TWITTER_CARD,
  },
  {
    id: "vaults",
    path: "/vaults",
    match: "prefix",
    title: "Vault analytics and performance tracking",
    description:
      "Inspect vault analytics, performance, allocations, and depositor activity in Hyperopen.",
    heroTitle: "Vault analytics and performance tracking",
    heroDescription:
      "Loading vault performance, allocations, and depositor activity.",
    twitterCard: DEFAULT_TWITTER_CARD,
  },
  {
    id: "staking",
    path: "/staking",
    match: "prefix",
    title: "HYPE staking dashboard and validator performance",
    description:
      "Review validator performance, staking balances, and reward context from Hyperopen's HYPE staking dashboard.",
    heroTitle: "HYPE staking dashboard and validator performance",
    heroDescription:
      "Loading validator performance, staking balances, and reward context.",
    twitterCard: DEFAULT_TWITTER_CARD,
  },
  {
    id: "funding-comparison",
    path: "/funding-comparison",
    match: "exact",
    title: "Funding rate comparison across Hyperliquid, Binance, and Bybit",
    description:
      "Compare funding rates across Hyperliquid, Binance, and Bybit from Hyperopen's funding comparison dashboard.",
    heroTitle: "Funding rate comparison",
    heroDescription:
      "Loading cross-exchange funding comparisons for Hyperliquid, Binance, and Bybit.",
    twitterCard: DEFAULT_TWITTER_CARD,
  },
  {
    id: "api",
    path: "/api",
    match: "exact",
    title: "API wallet authorization and management",
    description:
      "Authorize and manage API wallets for Hyperopen trading and account access.",
    heroTitle: "API wallet authorization and management",
    heroDescription:
      "Loading API wallet authorization, account access, and management tools.",
    twitterCard: DEFAULT_TWITTER_CARD,
  },
];

function escapeHtmlText(text) {
  return String(text ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function escapeHtmlAttribute(text) {
  return escapeHtmlText(text)
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function escapeHtmlJson(text) {
  return String(text ?? "")
    .replace(/</g, "\\u003C")
    .replace(/>/g, "\\u003E")
    .replace(/&/g, "\\u0026");
}

function xmlEscape(text) {
  return String(text ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function parseHead(indexHtml) {
  const match = indexHtml.match(/<head\b[^>]*>([\s\S]*?)<\/head>/i);
  if (!match) {
    throw new Error("Expected app index.html to contain a <head> element.");
  }

  return match[1];
}

function looksLikeHashPrefixedPagesPreviewHost(hostname) {
  const host = String(hostname || "").toLowerCase();
  if (!host.endsWith(".pages.dev")) {
    return false;
  }

  const labels = host.split(".");
  if (labels.length < 4) {
    return false;
  }

  return /^[a-f0-9]{7,}$/i.test(labels[0]);
}

function validateRouteMetadata(route) {
  if (!route || typeof route !== "object") {
    throw new Error("Expected route metadata to be an object.");
  }

  for (const field of ["id", "path", "title", "description", "heroTitle", "heroDescription"]) {
    if (typeof route[field] !== "string" || route[field].trim().length === 0) {
      throw new Error(`Expected route metadata to include a non-empty ${field}.`);
    }
  }

  return route;
}

export function normalizePublicPath(publicPath) {
  const text = String(publicPath || "").trim();
  if (!text) {
    return "/";
  }

  const [withoutFragment] = text.split("#", 1);
  const [withoutQuery] = withoutFragment.split("?", 1);
  const prefixed = withoutQuery.startsWith("/") ? withoutQuery : `/${withoutQuery}`;
  const normalized = prefixed.replace(/\/+$/, "");
  return normalized || "/";
}

export function publicPathToRelativePath(publicPath) {
  const normalized = normalizePublicPath(publicPath);
  if (normalized === "/") {
    throw new Error("Expected a file-like public path, but received '/'.");
  }

  return normalized.replace(/^\/+/, "");
}

export function normalizeCanonicalOrigin(
  envValue = process.env[CANONICAL_ORIGIN_ENV_VAR]
) {
  const rawValue = typeof envValue === "string" ? envValue.trim() : "";
  if (!rawValue) {
    return DEFAULT_CANONICAL_ORIGIN;
  }

  const candidate = /^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(rawValue)
    ? rawValue
    : `https://${rawValue}`;

  let parsed;
  try {
    parsed = new URL(candidate);
  } catch (_error) {
    throw new Error(`Invalid ${CANONICAL_ORIGIN_ENV_VAR} value: ${rawValue}`);
  }

  if (!["http:", "https:"].includes(parsed.protocol)) {
    throw new Error(
      `Expected ${CANONICAL_ORIGIN_ENV_VAR} to use http or https, received: ${rawValue}`
    );
  }

  if (looksLikeHashPrefixedPagesPreviewHost(parsed.hostname)) {
    return DEFAULT_CANONICAL_ORIGIN;
  }

  return parsed.origin;
}

export function extractHeadRootAssetPublicPaths(indexHtml) {
  const headHtml = parseHead(indexHtml);
  const assetPaths = new Set();
  const attributePattern = /\b(?:href|content)=["']([^"']+)["']/gi;

  for (const match of headHtml.matchAll(attributePattern)) {
    const candidate = match[1];
    if (typeof candidate !== "string" || !candidate.startsWith("/")) {
      continue;
    }

    const normalized = normalizePublicPath(candidate);
    if (normalized.startsWith("/css/") || normalized.startsWith("/js/")) {
      continue;
    }

    if (!path.extname(normalized)) {
      continue;
    }

    assetPaths.add(normalized);
  }

  return [...assetPaths].sort();
}

export function collectReleaseRootAssetPublicPaths(indexHtml) {
  return [
    ...new Set([
      ...REQUIRED_ROOT_PUBLIC_PATHS.map(normalizePublicPath),
      ...extractHeadRootAssetPublicPaths(indexHtml),
    ]),
  ].sort();
}

export function buildSiteMetadata({ canonicalOrigin, indexHtml }) {
  const origin = normalizeCanonicalOrigin(canonicalOrigin);

  return {
    siteName: "Hyperopen",
    origin,
    routes: PUBLIC_ROUTE_METADATA.map((route) => ({
      ...validateRouteMetadata(route),
      path: normalizePublicPath(route.path),
      twitterCard:
        typeof route.twitterCard === "string" && route.twitterCard.trim()
          ? route.twitterCard
          : DEFAULT_TWITTER_CARD,
    })),
    rootAssetPaths: collectReleaseRootAssetPublicPaths(indexHtml),
  };
}

export function buildRobotsTxt(siteMetadata) {
  return [
    "User-agent: *",
    "Allow: /",
    `Sitemap: ${siteMetadata.origin}${normalizePublicPath(`/${SITEMAP_FILE_PATH}`)}`,
  ].join("\n");
}

export function buildSitemapXml(siteMetadata) {
  const urls = siteMetadata.routes
    .map(
      (route) =>
        `  <url><loc>${xmlEscape(`${siteMetadata.origin}${route.path}`)}</loc></url>`
    )
    .join("\n");

  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    urls,
    "</urlset>",
  ].join("\n");
}

export function buildRouteLoadingShellMarkup(routeMetadata) {
  const route = validateRouteMetadata(routeMetadata);

  return [
    `<div class="h-full bg-base-100 text-trading-text" data-hyperopen-route-shell="${escapeHtmlAttribute(route.id)}" aria-live="polite" aria-busy="true">`,
    '  <main class="app-shell-gutter flex min-h-full items-center py-12 md:py-16">',
    '    <section class="mx-auto w-full max-w-3xl space-y-4 rounded-2xl border border-base-300/80 bg-base-100/95 p-6 shadow-sm md:p-8">',
    `      <h1 class="text-3xl font-semibold tracking-tight md:text-4xl">${escapeHtmlText(route.heroTitle)}</h1>`,
    `      <p class="max-w-2xl text-base leading-7 text-trading-text-secondary md:text-lg">${escapeHtmlText(route.heroDescription)}</p>`,
    "    </section>",
    "  </main>",
    "</div>",
  ].join("\n");
}

export function buildReleaseSeoHeadMarkup(siteMetadata, routeMetadata) {
  const route = validateRouteMetadata(routeMetadata);
  const routeUrl = `${siteMetadata.origin}${normalizePublicPath(route.path)}`;
  const metadataJson = escapeHtmlJson(JSON.stringify(siteMetadata));
  const title = escapeHtmlAttribute(route.title);
  const description = escapeHtmlAttribute(route.description);
  const twitterCard = escapeHtmlAttribute(route.twitterCard || DEFAULT_TWITTER_CARD);
  const canonicalUrl = escapeHtmlAttribute(routeUrl);

  return [
    `<link rel="canonical" href="${canonicalUrl}" data-hyperopen-seo="canonical" />`,
    `<meta property="og:title" content="${title}" data-hyperopen-seo="og:title" />`,
    `<meta property="og:description" content="${description}" data-hyperopen-seo="og:description" />`,
    `<meta property="og:url" content="${canonicalUrl}" data-hyperopen-seo="og:url" />`,
    `<meta name="twitter:card" content="${twitterCard}" data-hyperopen-seo="twitter:card" />`,
    `<meta name="twitter:title" content="${title}" data-hyperopen-seo="twitter:title" />`,
    `<meta name="twitter:description" content="${description}" data-hyperopen-seo="twitter:description" />`,
    `<script id="hyperopen-site-metadata" type="application/json">${metadataJson}</script>`,
    "<script>",
    "  (function () {",
    "    const metadataElement = document.getElementById(\"hyperopen-site-metadata\");",
    "    if (!metadataElement) {",
    "      return;",
    "    }",
    "",
    "    let metadata;",
    "    try {",
    "      metadata = JSON.parse(metadataElement.textContent || \"{}\");",
    "    } catch (_error) {",
    "      return;",
    "    }",
    "",
    "    const origin = typeof metadata.origin === \"string\" ? metadata.origin : \"\";",
    "    const routes = Array.isArray(metadata.routes) ? metadata.routes : [];",
    "    if (!origin || routes.length === 0) {",
    "      return;",
    "    }",
    "",
    "    function normalizePath(pathname) {",
    "      const raw = typeof pathname === \"string\" ? pathname : \"/\";",
    "      const withoutHash = raw.split(\"#\", 1)[0] || \"/\";",
    "      const withoutQuery = withoutHash.split(\"?\", 1)[0] || \"/\";",
    "      const withSlash = withoutQuery.startsWith(\"/\") ? withoutQuery : `/${withoutQuery}`;",
    "      const normalized = withSlash.replace(/\\/+$/, \"\") || \"/\";",
    "      return normalized.toLowerCase();",
    "    }",
    "",
    "    function routeMatches(route, currentPath) {",
    "      const routePath = normalizePath(route.path || \"/\");",
    "      if ((route.match || \"exact\") === \"prefix\") {",
    "        return currentPath === routePath || currentPath.startsWith(`${routePath}/`);",
    "      }",
    "",
    "      return currentPath === routePath;",
    "    }",
    "",
    "    function findRoute(pathname) {",
    "      const currentPath = normalizePath(pathname);",
    "      return (",
    "        routes.find((route) => routeMatches(route, currentPath)) ||",
    "        routes.find((route) => normalizePath(route.path || \"/\") === \"/\") ||",
    "        routes[0] ||",
    "        null",
    "      );",
    "    }",
    "",
    "    function ensureCanonicalLink() {",
    "      let link = document.querySelector(\"link[rel='canonical']\");",
    "      if (!link) {",
    "        link = document.createElement(\"link\");",
    "        link.setAttribute(\"rel\", \"canonical\");",
    "        document.head.appendChild(link);",
    "      }",
    "      return link;",
    "    }",
    "",
    "    function ensureDescriptionMeta() {",
    "      let meta = document.querySelector(\"meta[name='description']\");",
    "      if (!meta) {",
    "        meta = document.createElement(\"meta\");",
    "        meta.setAttribute(\"name\", \"description\");",
    "        document.head.appendChild(meta);",
    "      }",
    "      return meta;",
    "    }",
    "",
    "    function ensureMeta(selector, attributes) {",
    "      let meta = document.querySelector(selector);",
    "      if (!meta) {",
    "        meta = document.createElement(\"meta\");",
    "        for (const [key, value] of Object.entries(attributes)) {",
    "          meta.setAttribute(key, value);",
    "        }",
    "        document.head.appendChild(meta);",
    "      }",
    "      return meta;",
    "    }",
    "",
    "    function setMeta(selector, attributes, content) {",
    "      ensureMeta(selector, attributes).setAttribute(\"content\", content);",
    "    }",
    "",
    "    function applyRoute(route) {",
    "      if (!route || typeof route.path !== \"string\") {",
    "        return;",
    "      }",
    "",
    "      const canonicalUrl = `${origin}${normalizePath(route.path)}`;",
    "      ensureCanonicalLink().setAttribute(\"href\", canonicalUrl);",
    "",
    "      if (typeof route.title === \"string\" && route.title.trim()) {",
    "        document.title = route.title;",
    "      }",
    "",
    "      if (typeof route.description === \"string\" && route.description.trim()) {",
    "        ensureDescriptionMeta().setAttribute(\"content\", route.description);",
    "      }",
    "",
    "      const title = typeof route.title === \"string\" ? route.title : \"\";",
    "      const description = typeof route.description === \"string\" ? route.description : \"\";",
    "      const twitterCard =",
    "        typeof route.twitterCard === \"string\" && route.twitterCard.trim()",
    "          ? route.twitterCard",
    "          : \"summary\";",
    "",
    "      setMeta(\"meta[property='og:title']\", { property: \"og:title\" }, title);",
    "      setMeta(",
    "        \"meta[property='og:description']\",",
    "        { property: \"og:description\" },",
    "        description",
    "      );",
    "      setMeta(\"meta[property='og:url']\", { property: \"og:url\" }, canonicalUrl);",
    "      setMeta(\"meta[name='twitter:card']\", { name: \"twitter:card\" }, twitterCard);",
    "      setMeta(\"meta[name='twitter:title']\", { name: \"twitter:title\" }, title);",
    "      setMeta(",
    "        \"meta[name='twitter:description']\",",
    "        { name: \"twitter:description\" },",
    "        description",
    "      );",
    "    }",
    "",
    "    function syncMetadata() {",
    "      applyRoute(findRoute(window.location.pathname || \"/\"));",
    "    }",
    "",
    "    const historyApi = window.history;",
    "    for (const methodName of [\"pushState\", \"replaceState\"]) {",
    "      const original = historyApi && historyApi[methodName];",
    "      if (typeof original !== \"function\") {",
    "        continue;",
    "      }",
    "",
    "      historyApi[methodName] = function (...args) {",
    "        const result = original.apply(this, args);",
    "        syncMetadata();",
    "        return result;",
    "      };",
    "    }",
    "",
    "    window.addEventListener(\"popstate\", syncMetadata);",
    "    syncMetadata();",
    "  })();",
    "</script>",
  ].join("\n");
}
