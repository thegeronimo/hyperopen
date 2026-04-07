import fs from "node:fs";
import path from "node:path";
import http from "node:http";

const rootDir = path.resolve(
  process.cwd(),
  process.env.PLAYWRIGHT_STATIC_ROOT || "resources/public"
);
const port = Number(process.env.PLAYWRIGHT_WEB_PORT || 4173);
const headersFilePath = path.join(rootDir, "_headers");

const contentTypes = new Map([
  [".css", "text/css; charset=utf-8"],
  [".html", "text/html; charset=utf-8"],
  [".ico", "image/x-icon"],
  [".js", "text/javascript; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".png", "image/png"],
  [".svg", "image/svg+xml; charset=utf-8"],
  [".ttf", "font/ttf"],
  [".txt", "text/plain; charset=utf-8"],
  [".woff2", "font/woff2"],
  [".xml", "application/xml; charset=utf-8"]
]);

function isPathInsideRoot(candidatePath) {
  return candidatePath === rootDir || candidatePath.startsWith(`${rootDir}${path.sep}`);
}

function tryFile(filePath) {
  try {
    return fs.statSync(filePath).isFile();
  } catch (_error) {
    return false;
  }
}

function resolvePathWithinRoot(relativePath) {
  const candidatePath = path.resolve(rootDir, relativePath);
  if (!isPathInsideRoot(candidatePath)) {
    return null;
  }

  return candidatePath;
}

function resolveRequestPathname(requestUrl) {
  const { pathname } = new URL(requestUrl || "/", "http://127.0.0.1");
  const decodedPath = decodeURIComponent(pathname);
  const normalizedPath = decodedPath.replace(/\/+$/, "") || "/";
  return normalizedPath.startsWith("/") ? normalizedPath : `/${normalizedPath}`;
}

function parseHeadersRules(fileContent) {
  const rules = [];
  let activeRule = null;

  for (const rawLine of String(fileContent || "").split(/\r?\n/)) {
    const trimmedLine = rawLine.trim();
    if (!trimmedLine || trimmedLine.startsWith("#")) {
      continue;
    }

    const indented = /^\s/.test(rawLine);
    if (!indented) {
      activeRule = { pattern: trimmedLine, entries: [] };
      rules.push(activeRule);
      continue;
    }

    if (!activeRule) {
      throw new Error(`Invalid _headers entry without a rule pattern: ${rawLine}`);
    }

    if (trimmedLine.startsWith("! ")) {
      activeRule.entries.push({
        detach: true,
        name: trimmedLine.slice(2).trim(),
      });
      continue;
    }

    const separatorIndex = trimmedLine.indexOf(":");
    if (separatorIndex <= 0) {
      throw new Error(`Invalid _headers header line: ${rawLine}`);
    }

    activeRule.entries.push({
      name: trimmedLine.slice(0, separatorIndex).trim(),
      value: trimmedLine.slice(separatorIndex + 1).trim(),
    });
  }

  return rules;
}

function loadHeadersRules() {
  try {
    return parseHeadersRules(fs.readFileSync(headersFilePath, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") {
      return [];
    }

    throw error;
  }
}

function pathMatchesPattern(pattern, requestPathname) {
  if (pattern === requestPathname) {
    return true;
  }

  if (!pattern.includes("*")) {
    return false;
  }

  const escapedPattern = pattern.replace(/[.+?^${}()|[\]\\]/g, "\\$&");
  const regexPattern = `^${escapedPattern.replace(/\*/g, ".*")}$`;
  return new RegExp(regexPattern).test(requestPathname);
}

function buildMatchedHeaders(rules, requestPathname) {
  const headers = new Map();

  for (const rule of rules) {
    if (!pathMatchesPattern(rule.pattern, requestPathname)) {
      continue;
    }

    for (const entry of rule.entries) {
      const headerName = entry.name.toLowerCase();
      if (entry.detach) {
        headers.delete(headerName);
        continue;
      }

      const existing = headers.get(headerName);
      headers.set(headerName, {
        name: entry.name,
        value: existing ? `${existing.value}, ${entry.value}` : entry.value,
      });
    }
  }

  return Object.fromEntries(
    [...headers.values()].map(({ name, value }) => [name, value])
  );
}

function shouldTryDirectoryIndex(requestPathname) {
  return requestPathname.endsWith("/") || path.extname(requestPathname) === "";
}

function findNearest404Path(requestPathname) {
  const trimmedPath = requestPathname.replace(/^\/+|\/+$/g, "");
  const segments = trimmedPath ? trimmedPath.split("/") : [];
  const directorySegments = shouldTryDirectoryIndex(requestPathname)
    ? segments
    : segments.slice(0, -1);

  for (let index = directorySegments.length; index > 0; index -= 1) {
    const candidatePath = resolvePathWithinRoot(
      path.join(...directorySegments.slice(0, index), "404.html")
    );
    if (candidatePath && tryFile(candidatePath)) {
      return candidatePath;
    }
  }

  return null;
}

function resolveFilePath(requestUrl) {
  const requestPathname = resolveRequestPathname(requestUrl);
  const relativePath = requestPathname === "/" ? "" : requestPathname.replace(/^\/+/, "");
  const exactCandidatePath = resolvePathWithinRoot(relativePath);

  if (!exactCandidatePath) {
    return { requestPathname, statusCode: 403 };
  }

  if (tryFile(exactCandidatePath)) {
    return { filePath: exactCandidatePath, requestPathname, statusCode: 200 };
  }

  if (shouldTryDirectoryIndex(requestPathname)) {
    const directoryIndexPath = resolvePathWithinRoot(path.join(relativePath, "index.html"));
    if (directoryIndexPath && tryFile(directoryIndexPath)) {
      return { filePath: directoryIndexPath, requestPathname, statusCode: 200 };
    }
  }

  const root404Path = resolvePathWithinRoot("404.html");
  if (!root404Path || !tryFile(root404Path)) {
    const spaFallbackPath = resolvePathWithinRoot("index.html");
    if (spaFallbackPath && tryFile(spaFallbackPath)) {
      return { filePath: spaFallbackPath, requestPathname, statusCode: 200 };
    }

    return { requestPathname, statusCode: 404 };
  }

  return {
    filePath: findNearest404Path(requestPathname) || root404Path,
    requestPathname,
    statusCode: 404
  };
}

function sendNotFound(response) {
  response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
  response.end("File not found.");
}

function sendFile(response, filePath, statusCode = 200, extraHeaders = {}) {
  const extension = path.extname(filePath);
  response.writeHead(statusCode, {
    "Content-Type": contentTypes.get(extension) || "application/octet-stream",
    ...extraHeaders,
  });
  fs.createReadStream(filePath).pipe(response);
}

const headersRules = loadHeadersRules();

const server = http.createServer((request, response) => {
  let resolvedFile;
  try {
    resolvedFile = resolveFilePath(request.url);
  } catch (_error) {
    response.writeHead(400, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("Bad request");
    return;
  }

  if (!resolvedFile) {
    sendNotFound(response);
    return;
  }

  if (!resolvedFile.filePath) {
    if (resolvedFile.statusCode === 403) {
      response.writeHead(403, { "Content-Type": "text/plain; charset=utf-8" });
      response.end("Forbidden");
      return;
    }

    sendNotFound(response);
    return;
  }

  const { filePath, requestPathname, statusCode } = resolvedFile;
  if (!tryFile(filePath)) {
    sendNotFound(response);
    return;
  }

  sendFile(response, filePath, statusCode, buildMatchedHeaders(headersRules, requestPathname));
});

function shutdown() {
  server.close(() => process.exit(0));
}

server.listen(port, "127.0.0.1", () => {
  process.stdout.write(
    `Playwright static server listening on http://127.0.0.1:${port} from ${rootDir}\n`
  );
});

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
