// Same-origin proxy for Hyperliquid's coin icons.
//
// The icons render fine in the DOM straight from app.hyperliquid.xyz, so the
// tables and charts have never needed this. The share card does: it is exported
// by serializing an <svg> and rasterizing it through an Image element, and that
// runs in a sandboxed document which cannot load ANY external resource. The
// icon therefore has to be embedded in the card as a data: URI, which means
// reading its bytes, which the icon host forbids -- verified 2026-08-21, it
// serves from S3/CloudFront with no Access-Control-Allow-Origin and no
// Vary: Origin, so fetch() rejects, an <img> taints the canvas, and an external
// href inside the serialized SVG silently renders the broken-image placeholder
// rather than the icon.
//
// Proxying them same-origin is the only way to get the bytes. The response is
// immutable and cached at the edge, so this costs one origin fetch per icon per
// edge location per day.
const UPSTREAM = "https://app.hyperliquid.xyz/coins/";

// Icon keys are symbols, optionally venue-prefixed (xyz:NVDA) or spot-suffixed
// (BTC_spot). Anything else is refused so this cannot be used as a generic
// open proxy.
const KEY_PATTERN = /^[A-Za-z0-9_@.-]{1,48}(:[A-Za-z0-9_@.-]{1,48})?$/;

const CACHE_CONTROL = "public, max-age=86400, stale-while-revalidate=604800";

export async function onRequestGet({ params }) {
  const raw = String(params?.key ?? "");
  if (!raw.endsWith(".svg")) {
    return new Response("Not found", { status: 404 });
  }

  const key = decodeURIComponent(raw.slice(0, -4));
  if (!KEY_PATTERN.test(key)) {
    return new Response("Not found", { status: 404 });
  }

  const upstream = await fetch(`${UPSTREAM}${encodeURI(key)}.svg`, {
    cf: { cacheTtl: 86400, cacheEverything: true }
  });

  if (!upstream.ok) {
    return new Response("Not found", { status: 404 });
  }

  const contentType = String(upstream.headers.get("content-type") || "");
  if (!contentType.includes("image/svg+xml")) {
    return new Response("Not found", { status: 404 });
  }

  return new Response(upstream.body, {
    status: 200,
    headers: {
      "content-type": "image/svg+xml",
      "cache-control": CACHE_CONTROL,
      "access-control-allow-origin": "*",
      "x-content-type-options": "nosniff"
    }
  });
}
