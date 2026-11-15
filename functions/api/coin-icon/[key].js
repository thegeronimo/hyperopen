const UPSTREAM = "https://app.hyperliquid.xyz/coins/";

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
