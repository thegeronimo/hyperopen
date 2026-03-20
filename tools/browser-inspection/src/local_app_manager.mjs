import { spawn } from "node:child_process";
import { killProcess, processIsAlive } from "./chrome_launcher.mjs";
import { classifyErrorMessage, remediationForClassification } from "./failure_classification.mjs";
import { sleep } from "./util.mjs";

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

  const existing = await waitForUrl(url, 3000, 300).then(() => true).catch(() => false);
  if (existing) {
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
    stdio: ["ignore", "ignore", "pipe"]
  });
  let stderrBuffer = "";
  if (child.stderr) {
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => {
      stderrBuffer = `${stderrBuffer}${chunk}`.slice(-4000);
    });
  }

  const exitPromise = new Promise((resolve) => {
    child.once("exit", (code, signal) => {
      resolve({ code, signal });
    });
  });

  const winner = await Promise.race([
    waitForUrl(url, startupTimeoutMs, pollIntervalMs).then(() => ({ kind: "ready" })),
    exitPromise.then((details) => ({ kind: "exit", details }))
  ]);

  if (winner.kind === "exit") {
    const status = winner.details?.code !== null ? `code ${winner.details?.code}` : `signal ${winner.details?.signal || "unknown"}`;
    const classified = classifyErrorMessage(stderrBuffer || `Local app exited early (${status}).`);
    const remediation = classified ? remediationForClassification(classified) : null;
    const remediationSuffix = remediation ? ` Remediation: ${remediation}` : "";
    const stderrSuffix = stderrBuffer ? ` Stderr tail: ${stderrBuffer.replace(/\\s+/g, " ").trim()}` : "";
    throw new Error(`Local app command exited early (${status}).${stderrSuffix}${remediationSuffix}`);
  }

  child.unref();
  return {
    managed: true,
    startedByTool: true,
    pid: child.pid,
    url,
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
