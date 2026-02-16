#!/usr/bin/env node
import { BrowserInspectionService } from "./service.mjs";

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith("--")) {
      out._.push(token);
      continue;
    }

    const stripped = token.slice(2);
    if (stripped.includes("=")) {
      const [k, ...rest] = stripped.split("=");
      out[k] = rest.join("=");
      continue;
    }

    const next = argv[i + 1];
    if (!next || next.startsWith("--")) {
      out[stripped] = true;
      continue;
    }

    out[stripped] = next;
    i += 1;
  }
  return out;
}

function parseViewportList(value) {
  if (!value) {
    return null;
  }
  return String(value)
    .split(",")
    .map((part) => part.trim())
    .filter(Boolean);
}

function asJson(value) {
  process.stdout.write(`${JSON.stringify(value, null, 2)}\n`);
}

function usage() {
  process.stdout.write(`Usage:
  inspect --url <url> [--target <label>] [--viewports desktop,mobile] [--session-id <id>] [--headed] [--manage-local-app]
  compare [--left-url <url>] [--right-url <url>] [--left-label <label>] [--right-label <label>] [--viewports desktop,mobile] [--session-id <id>] [--headed] [--manage-local-app]
  navigate --session-id <id> --url <url> [--viewport desktop]
  eval --session-id <id> --expression <js>
  session start [--headed] [--manage-local-app]
  session stop --session-id <id>
  session list
`);
}

async function run() {
  const args = parseArgs(process.argv.slice(2));
  const [command, subcommand] = args._;

  if (!command) {
    usage();
    process.exitCode = 1;
    return;
  }

  const service = await BrowserInspectionService.create();

  if (command === "session" && subcommand === "start") {
    const session = await service.startSession({
      headless: !Boolean(args.headed),
      manageLocalApp: Boolean(args["manage-local-app"]),
      localAppUrl: args["local-url"]
    });
    asJson(session);
    return;
  }

  if (command === "session" && subcommand === "stop") {
    if (!args["session-id"]) {
      throw new Error("session stop requires --session-id");
    }
    const ok = await service.stopSession(args["session-id"]);
    asJson({ ok, sessionId: args["session-id"] });
    return;
  }

  if (command === "session" && subcommand === "list") {
    const sessions = await service.listSessions();
    asJson({ sessions });
    return;
  }

  if (command === "navigate") {
    if (!args["session-id"] || !args.url) {
      throw new Error("navigate requires --session-id and --url");
    }
    const result = await service.navigate({
      sessionId: args["session-id"],
      url: args.url,
      viewportName: args.viewport || null
    });
    asJson(result);
    return;
  }

  if (command === "eval") {
    if (!args["session-id"] || !args.expression) {
      throw new Error("eval requires --session-id and --expression");
    }
    const result = await service.evaluate({
      sessionId: args["session-id"],
      expression: args.expression,
      allowUnsafeEval: Boolean(args["allow-unsafe-eval"])
    });
    asJson(result);
    return;
  }

  if (command === "inspect") {
    if (!args.url && !args["session-id"]) {
      throw new Error("inspect requires --url when no --session-id is provided");
    }
    const result = await service.capture({
      sessionId: args["session-id"] || null,
      url: args.url || service.config.targets.local.url,
      targetLabel: args.target || "target",
      viewports: parseViewportList(args.viewports),
      headless: !Boolean(args.headed),
      manageLocalApp: Boolean(args["manage-local-app"]),
      localAppUrl: args["local-url"]
    });
    asJson(result);
    return;
  }

  if (command === "compare") {
    const result = await service.compare({
      sessionId: args["session-id"] || null,
      leftUrl: args["left-url"],
      rightUrl: args["right-url"],
      leftLabel: args["left-label"],
      rightLabel: args["right-label"],
      viewports: parseViewportList(args.viewports),
      headless: !Boolean(args.headed),
      manageLocalApp: Boolean(args["manage-local-app"]),
      localAppUrl: args["local-url"]
    });
    asJson(result);
    return;
  }

  usage();
  process.exitCode = 1;
}

run().catch((error) => {
  process.stderr.write(`${error?.stack || error?.message || error}\n`);
  process.exitCode = 1;
});
