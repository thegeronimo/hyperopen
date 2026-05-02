#!/usr/bin/env node
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const DEV_SCRIPT_RE =
  /\bnpm (?:run|exec) (?:dev|dev:browser-inspection|dev:portfolio|dev:proxy|portfolio|cljs:watch|cljs:watch:portfolio|portfolio:watch|css:watch|shadow-cljs)\b/;
const WATCH_TOOL_RE =
  /\b(?:concurrently|shadow-cljs|tailwindcss)\b.*\b(?:watch|--watch|server status)\b/;
const SHADOW_JAVA_RE =
  /\bjava\b.*\bshadow\.cljs\.devtools\.cli\b.*\b(?:watch|server status)\b/;

export function parseProcessTable(stdout) {
  return stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .filter((line) => !line.startsWith("PID "))
    .map((line) => {
      const match = line.match(/^(\d+)\s+(\d+)\s+(\d+)\s+(.+)$/);
      if (!match) {
        return null;
      }
      return {
        pid: Number(match[1]),
        ppid: Number(match[2]),
        pgid: Number(match[3]),
        command: match[4]
      };
    })
    .filter(Boolean);
}

function commandMentionsCwd(command, cwd) {
  return command.includes(cwd) || command.includes(`INIT_CWD=${cwd}`);
}

function isDevServerRoot(processInfo, cwd) {
  const { command } = processInfo;
  if (!commandMentionsCwd(command, cwd)) {
    return false;
  }
  return DEV_SCRIPT_RE.test(command)
    || WATCH_TOOL_RE.test(command)
    || SHADOW_JAVA_RE.test(command);
}

function childrenByParent(processes) {
  const children = new Map();
  for (const processInfo of processes) {
    const current = children.get(processInfo.ppid) ?? [];
    current.push(processInfo);
    children.set(processInfo.ppid, current);
  }
  return children;
}

export function collectKillTargets(processes, options) {
  const { cwd, selfPid } = options;
  const self = processes.find((processInfo) => processInfo.pid === selfPid);
  const selfPgid = self?.pgid;
  const children = childrenByParent(processes);
  const targets = new Map();
  const queue = processes.filter((processInfo) => isDevServerRoot(processInfo, cwd));

  while (queue.length > 0) {
    const processInfo = queue.shift();
    if (!processInfo
        || processInfo.pid === selfPid
        || (selfPgid && processInfo.pgid === selfPgid)
        || targets.has(processInfo.pid)) {
      continue;
    }
    targets.set(processInfo.pid, processInfo);
    for (const child of children.get(processInfo.pid) ?? []) {
      queue.push(child);
    }
  }

  return [...targets.values()].sort((a, b) => a.pid - b.pid);
}

export function uniqueProcessGroups(targets) {
  return [...new Set(targets.map((processInfo) => processInfo.pgid))]
    .sort((a, b) => a - b)
    .map((pgid) => -pgid);
}

async function readProcesses() {
  const { stdout } = await execFileAsync("ps", ["-axo", "pid,ppid,pgid,command"], {
    maxBuffer: 8 * 1024 * 1024
  });
  return parseProcessTable(stdout);
}

function processExists(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function killTarget(target, signal) {
  try {
    process.kill(target, signal);
    return true;
  } catch (error) {
    return error?.code === "ESRCH";
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function main() {
  const args = new Set(process.argv.slice(2));
  const dryRun = args.has("--dry-run");
  const cwd = process.cwd();
  const processes = await readProcesses();
  const targets = collectKillTargets(processes, { cwd, selfPid: process.pid });
  const groups = uniqueProcessGroups(targets);

  if (targets.length === 0) {
    console.log("No Hyperopen dev server processes found for this worktree.");
    return;
  }

  console.log(`${dryRun ? "Would stop" : "Stopping"} ${targets.length} Hyperopen dev server process(es):`);
  for (const processInfo of targets) {
    console.log(`  pid=${processInfo.pid} pgid=${processInfo.pgid} ${processInfo.command}`);
  }

  if (dryRun) {
    return;
  }

  for (const group of groups) {
    killTarget(group, "SIGTERM");
  }

  await sleep(800);

  const remaining = targets.filter((processInfo) => processExists(processInfo.pid));
  if (remaining.length > 0) {
    for (const group of uniqueProcessGroups(remaining)) {
      killTarget(group, "SIGKILL");
    }
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error) => {
    console.error(error?.stack || error);
    process.exitCode = 1;
  });
}
