import { spawn } from "node:child_process";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { ensureDir, sleep } from "./util.mjs";

async function pickFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, "127.0.0.1", () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
    server.on("error", reject);
  });
}

export async function waitForDebugEndpoint(port, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  const url = `http://127.0.0.1:${port}/json/version`;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) {
        const payload = await response.json();
        if (payload.webSocketDebuggerUrl) {
          return payload;
        }
      }
    } catch (_err) {
      // retry
    }
    await sleep(200);
  }
  throw new Error(`Timed out waiting for Chrome debug endpoint on port ${port}`);
}

export async function launchChrome(options) {
  const {
    chromePath,
    headless = true,
    extraArgs = [],
    ephemeralProfile = true,
    userDataDir,
    detached = true
  } = options;

  if (!chromePath) {
    throw new Error("Missing chromePath");
  }

  const port = await pickFreePort();
  const profilePath = userDataDir || path.join(os.tmpdir(), `hyperopen-browser-${Date.now()}-${port}`);
  await ensureDir(profilePath);

  const args = [
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profilePath}`,
    "about:blank"
  ];

  if (headless) {
    args.unshift("--headless=new");
  }
  args.unshift(...extraArgs);

  const child = spawn(chromePath, args, {
    detached,
    stdio: detached ? "ignore" : "pipe"
  });

  if (detached) {
    child.unref();
  }

  await waitForDebugEndpoint(port, 30000);

  return {
    pid: child.pid,
    port,
    userDataDir: profilePath,
    ephemeralProfile,
    detached
  };
}

export function processIsAlive(pid) {
  if (!pid || typeof pid !== "number") {
    return false;
  }
  try {
    process.kill(pid, 0);
    return true;
  } catch (_err) {
    return false;
  }
}

export async function killProcess(pid, options = {}) {
  const { killGroup = false } = options;
  if (!pid || typeof pid !== "number") {
    return false;
  }
  const signalTarget = killGroup ? -pid : pid;
  try {
    process.kill(signalTarget, "SIGTERM");
  } catch (_err) {
    return false;
  }

  const deadline = Date.now() + 3000;
  while (Date.now() < deadline) {
    if (!processIsAlive(pid)) {
      return true;
    }
    await sleep(100);
  }

  try {
    process.kill(signalTarget, "SIGKILL");
  } catch (_err) {
    // ignore
  }
  return !processIsAlive(pid);
}
