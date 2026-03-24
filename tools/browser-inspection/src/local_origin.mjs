function parseUrlOrNull(rawUrl) {
  try {
    return new URL(rawUrl);
  } catch (_error) {
    return null;
  }
}

function normalizedPort(url) {
  if (url.port) {
    return url.port;
  }
  if (url.protocol === "https:") {
    return "443";
  }
  if (url.protocol === "http:") {
    return "80";
  }
  return "";
}

function normalizeHostname(hostname) {
  if (hostname === "localhost" || hostname === "127.0.0.1" || hostname === "::1") {
    return "loopback";
  }
  return hostname;
}

function originKeyForUrl(url) {
  return `${url.protocol}//${normalizeHostname(url.hostname)}:${normalizedPort(url)}`;
}

function configuredLocalOriginKeys(config = {}, session = null) {
  return new Set(
    [
      config?.localApp?.url,
      config?.targets?.local?.url,
      session?.localApp?.requestedUrl,
      session?.localApp?.url,
      ...(session?.localApp?.candidateUrls || [])
    ]
      .map(parseUrlOrNull)
      .filter(Boolean)
      .map(originKeyForUrl)
  );
}

export function rebaseUrlToOrigin(rawUrl, originUrl) {
  const source = parseUrlOrNull(rawUrl);
  const origin = parseUrlOrNull(originUrl);
  if (!source || !origin) {
    return rawUrl;
  }
  const rebased = new URL(origin.toString());
  rebased.pathname = source.pathname;
  rebased.search = source.search;
  rebased.hash = source.hash;
  return rebased.toString();
}

export function resolveManagedLocalUrl(rawUrl, session, config = {}) {
  const source = parseUrlOrNull(rawUrl);
  const localAppUrl = parseUrlOrNull(session?.localApp?.url);
  if (!source || !localAppUrl) {
    return rawUrl;
  }

  const sourceOriginKey = originKeyForUrl(source);
  const localOriginKey = originKeyForUrl(localAppUrl);
  if (sourceOriginKey === localOriginKey) {
    return rawUrl;
  }

  const allowedOrigins = configuredLocalOriginKeys(config, session);
  if (!allowedOrigins.has(sourceOriginKey)) {
    return rawUrl;
  }

  return rebaseUrlToOrigin(rawUrl, localAppUrl.toString());
}

export function resolveManagedLocalTarget(target, session, config = {}) {
  if (!target?.url) {
    return target;
  }
  return {
    ...target,
    url: resolveManagedLocalUrl(target.url, session, config)
  };
}
