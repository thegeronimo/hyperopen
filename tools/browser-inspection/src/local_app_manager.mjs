import { spawn } from "node:child_process";
import { killProcess, processIsAlive } from "./chrome_launcher.mjs";
import { classifyErrorMessage, remediationForClassification } from "./failure_classification.mjs";
import { rebaseUrlToOrigin } from "./local_origin.mjs";
import { sleep } from "./util.mjs";

const HTTP_SERVER_URL_PATTERN = /HTTP server available at (https?:\/\/\S+)/g;

function extractHttpServerUrls(text) {
  if (!text) {
    return [];
  }
  return [...new Set([...String(text).matchAll(HTTP_SERVER_URL_PATTERN)].map((match) => match[1]))];
}

async function waitForResolvedUrl(getUrl, timeoutMs = 120000, pollIntervalMs = 1000) {
  const deadline = Date.now() + timeoutMs;
  let lastUrls = [];
  while (Date.now() < deadline) {
    const nextUrls = getUrl();
    if (Array.isArray(nextUrls) && nextUrls.length > 0) {
      lastUrls = nextUrls;
    }
    if (lastUrls.length === 0) {
      await sleep(pollIntervalMs);
      continue;
    }
    for (const candidateUrl of lastUrls) {
      try {
        const response = await fetch(candidateUrl, { redirect: "follow" });
        if (response.status >= 200 && response.status < 300) {
          return {
            readyUrl: candidateUrl,
            candidateUrls: lastUrls
          };
        }
      } catch (_err) {
        // keep polling
      }
    }
    await sleep(pollIntervalMs);
  }
  throw new Error(
    `Timed out waiting for local app at ${lastUrls.length > 0 ? lastUrls.join(", ") : "the managed local app URL"}`
  );
}

export async function waitForUrl(url, timeoutMs = 120000, pollIntervalMs = 1000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, { redirect: "follow" });
      if (response.status >= 200 && response.status < 300) {
        return true;
      }
    } catch (_err) {
      // keep polling
    }
    await sleep(pollIntervalMs);
  }
  throw new Error(`Timed out waiting for local app at ${url}`);
}

export async function maybeStartLocalApp(config, options = {}) {
  const {
    manageLocalApp = false,
    command = config.command,
    cwd = config.cwd,
    url = config.url,
    startupTimeoutMs = config.startupTimeoutMs,
    pollIntervalMs = config.pollIntervalMs
  } = options;

  if (!manageLocalApp) {
    await waitForUrl(url, 10000, 500).catch(() => null);
    return {
      managed: false,
      startedByTool: false,
      pid: null,
      url,
      command
    };
  }

  const child = spawn("sh", ["-lc", command], {
    cwd,
    detached: true,
    stdio: ["ignore", "pipe", "pipe"]
  });
  let outputBuffer = "";
  let candidateUrls = [];
  const handleOutputChunk = (chunk) => {
    outputBuffer = `${outputBuffer}${chunk}`.slice(-8000);
    const discoveredUrls = extractHttpServerUrls(outputBuffer);
    if (discoveredUrls.length > 0) {
      candidateUrls = discoveredUrls.map((discoveredUrl) => rebaseUrlToOrigin(url, discoveredUrl));
    }
  };

  for (const stream of [child.stdout, child.stderr]) {
    if (!stream) {
      continue;
    }
    stream.setEncoding("utf8");
    stream.on("data", handleOutputChunk);
  }

  const exitPromise = new Promise((resolve) => {
    child.once("exit", (code, signal) => {
      resolve({ code, signal });
    });
  });

  const winner = await Promise.race([
    waitForResolvedUrl(() => candidateUrls, startupTimeoutMs, pollIntervalMs).then((details) => ({
      kind: "ready",
      ...details
    })),
    exitPromise.then((details) => ({ kind: "exit", details }))
  ]);

  if (winner.kind === "exit") {
    const status = winner.details?.code !== null ? `code ${winner.details?.code}` : `signal ${winner.details?.signal || "unknown"}`;
    const classified = classifyErrorMessage(outputBuffer || `Local app exited early (${status}).`);
    const remediation = classified ? remediationForClassification(classified) : null;
    const remediationSuffix = remediation ? ` Remediation: ${remediation}` : "";
    const stderrSuffix = outputBuffer ? ` Output tail: ${outputBuffer.replace(/\s+/g, " ").trim()}` : "";
    throw new Error(`Local app command exited early (${status}).${stderrSuffix}${remediationSuffix}`);
  }

  child.unref();
  return {
    managed: true,
    startedByTool: true,
    pid: child.pid,
    requestedUrl: url,
    candidateUrls: winner.candidateUrls,
    url: winner.readyUrl,
    command
  };
}

export async function maybeStopLocalApp(localAppState) {
  if (!localAppState?.startedByTool || !localAppState?.pid) {
    return false;
  }
  if (!processIsAlive(localAppState.pid)) {
    return true;
  }
  return killProcess(localAppState.pid, { killGroup: true });
}
