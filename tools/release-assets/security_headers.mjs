import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const SECURITY_HEADERS_FILE_PATH = "_headers";
export const CONTROL_CACHE_CONTROL = "public, max-age=0, must-revalidate";
export const IMMUTABLE_CACHE_CONTROL = "public, max-age=31556952, immutable";
export const FONT_CACHE_CONTROL = "public, max-age=2592000";

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");

// The theme restore script is inlined into every release HTML head (one less
// render-blocking request); the CSP admits exactly that script by hash.
export const THEME_PRELOAD_INLINE_SOURCE = fs.readFileSync(
  path.join(REPO_ROOT, "resources", "public", "theme-preload.js"),
  "utf8"
);

export const THEME_PRELOAD_SCRIPT_HASH = `'sha256-${crypto
  .createHash("sha256")
  .update(THEME_PRELOAD_INLINE_SOURCE)
  .digest("base64")}'`;
export const DOCUMENT_PERMISSIONS_POLICY = [
  "accelerometer=()",
  "autoplay=()",
  "camera=()",
  "display-capture=()",
  "geolocation=()",
  "gyroscope=()",
  "microphone=()",
  "payment=()",
  "usb=()",
  "xr-spatial-tracking=()",
].join(", ");
export const DOCUMENT_REFERRER_POLICY = "strict-origin-when-cross-origin";

const DOCUMENT_CONNECT_SRC = [
  "'self'",
  "https://cloudflareinsights.com",
  "https://price-history.hyperopen.xyz",
  "https://api.hyperliquid.xyz",
  "wss://api.hyperliquid.xyz",
  "https://stats-data.hyperliquid.xyz",
  "https://app.hyperliquid.xyz",
  "https://api.hyperunit.xyz",
  "https://api.hyperunit-testnet.xyz",
  "https://app.across.to",
  "https://li.quest",
];

const DOCUMENT_IMG_SRC = [
  "'self'",
  "data:",
  "blob:",
  "https://app.hyperliquid.xyz",
];

function appendDirective(directives, name, values) {
  if (!Array.isArray(values) || values.length === 0) {
    directives.push(name);
    return;
  }

  directives.push(`${name} ${values.join(" ")}`);
}

function normalizeImmutableAssetPath(assetPath) {
  const normalized = String(assetPath || "").trim();
  if (!normalized.startsWith("/")) {
    throw new Error(`Expected immutable asset paths to start with '/': ${assetPath}`);
  }

  return normalized;
}

function formatHeaderBlock(pattern, entries) {
  const lines = [pattern];

  for (const entry of entries) {
    if (entry?.detach) {
      lines.push(`  ! ${entry.name}`);
      continue;
    }

    lines.push(`  ${entry.name}: ${entry.value}`);
  }

  return lines.join("\n");
}

export function buildContentSecurityPolicy() {
  const directives = [];

  appendDirective(directives, "default-src", ["'self'"]);
  appendDirective(directives, "base-uri", ["'self'"]);
  appendDirective(directives, "form-action", ["'self'"]);
  appendDirective(directives, "object-src", ["'none'"]);
  // 'wasm-unsafe-eval' admits WebAssembly compilation and nothing else: it does
  // not permit eval() or any other string-to-JS execution, so the same-origin
  // script-execution control this policy exists for is unchanged. Without it the
  // bundled OSQP solver -- an Emscripten WASM build inside osqp.min.js -- throws
  // a CSP CompileError, and infrastructure/osqp.cljs catches it into
  // fallback.cljs, which silently re-solves with pure-JS quadprog and still
  // reports :status :solved. The run looks correct and is roughly ten times
  // slower. It has to live in this policy rather than a worker-specific one:
  // the "/*" rule below applies these headers to /js/*.js as well, and a
  // dedicated worker takes its CSP from its own script response.
  appendDirective(directives, "script-src", [
    "'self'",
    "'wasm-unsafe-eval'",
    THEME_PRELOAD_SCRIPT_HASH,
    "https://static.cloudflareinsights.com",
  ]);
  appendDirective(directives, "style-src", ["'self'", "'unsafe-inline'"]);
  appendDirective(directives, "style-src-elem", ["'self'", "'unsafe-inline'"]);
  appendDirective(directives, "style-src-attr", ["'unsafe-inline'"]);
  appendDirective(directives, "font-src", ["'self'", "data:"]);
  appendDirective(directives, "img-src", DOCUMENT_IMG_SRC);
  appendDirective(directives, "connect-src", DOCUMENT_CONNECT_SRC);
  appendDirective(directives, "worker-src", ["'self'"]);
  appendDirective(directives, "child-src", ["'self'"]);
  appendDirective(directives, "frame-src", ["'none'"]);
  appendDirective(directives, "frame-ancestors", ["'none'"]);
  appendDirective(directives, "manifest-src", ["'self'"]);
  appendDirective(directives, "media-src", ["'self'", "blob:"]);

  return `${directives.join("; ")};`;
}

export function expectedDocumentHeaders() {
  return {
    "content-security-policy": buildContentSecurityPolicy(),
    "x-frame-options": "DENY",
    "x-content-type-options": "nosniff",
    "referrer-policy": DOCUMENT_REFERRER_POLICY,
    "permissions-policy": DOCUMENT_PERMISSIONS_POLICY,
    "cache-control": CONTROL_CACHE_CONTROL,
  };
}

export function buildReleaseHeadersFile({
  immutableAssetPaths = [],
} = {}) {
  const normalizedImmutableAssetPaths = [...new Set(immutableAssetPaths.map(normalizeImmutableAssetPath))]
    .sort();
  const documentHeaders = expectedDocumentHeaders();
  const lines = [
    "# Generated by tools/release-assets/security_headers.mjs",
    "# Cloudflare Pages applies these rules to the published static release root.",
    "",
    formatHeaderBlock("/*", [
      {
        name: "Content-Security-Policy",
        value: documentHeaders["content-security-policy"],
      },
      { name: "X-Frame-Options", value: documentHeaders["x-frame-options"] },
      {
        name: "X-Content-Type-Options",
        value: documentHeaders["x-content-type-options"],
      },
      { name: "Referrer-Policy", value: documentHeaders["referrer-policy"] },
      {
        name: "Permissions-Policy",
        value: documentHeaders["permissions-policy"],
      },
      { name: "Cache-Control", value: documentHeaders["cache-control"] },
    ]),
  ];

  lines.push(
    "",
    formatHeaderBlock("/fonts/*", [
      { detach: true, name: "Cache-Control" },
      { name: "Cache-Control", value: FONT_CACHE_CONTROL },
    ]),
  );

  for (const assetPath of normalizedImmutableAssetPaths) {
    lines.push(
      "",
      formatHeaderBlock(assetPath, [
        { detach: true, name: "Cache-Control" },
        { name: "Cache-Control", value: IMMUTABLE_CACHE_CONTROL },
      ]),
    );
  }

  return `${lines.join("\n")}\n`;
}
