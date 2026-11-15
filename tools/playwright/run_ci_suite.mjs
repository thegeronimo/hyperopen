import { spawn } from "node:child_process";

function runPlaywright(args) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      process.platform === "win32" ? "npx.cmd" : "npx",
      ["playwright", "test", ...args],
      {
        stdio: "inherit",
        env: { ...process.env, CI: "1" },
      }
    );

    child.on("error", reject);
    child.on("exit", (code, signal) => {
      if (signal) {
        reject(new Error(`playwright exited via signal ${signal}`));
        return;
      }

      resolve(code ?? 1);
    });
  });
}

async function main() {
  const interactiveExitCode = await runPlaywright([]);
  const releaseExitCode = await runPlaywright(["-c", "playwright.release.config.mjs"]);

  if (interactiveExitCode !== 0 || releaseExitCode !== 0) {
    process.exitCode = 1;
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
