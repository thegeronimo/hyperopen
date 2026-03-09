#!/usr/bin/env node
import { BrowserInspectionService } from "./service.mjs";
import { loadScenarios } from "./scenario_loader.mjs";
import { parseCsvArg, runScenarioBundle } from "./scenario_runner.mjs";

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
  inspect --url <url> [--target <label>] [--viewports desktop,mobile] [--session-id <id>] [--headed] [--manage-local-app] [--attach-port <port>] [--attach-host <host>] [--target-id <cdp-target-id>]
  compare [--left-url <url>] [--right-url <url>] [--left-label <label>] [--right-label <label>] [--viewports desktop,mobile] [--session-id <id>] [--headed] [--manage-local-app] [--attach-port <port>] [--attach-host <host>] [--target-id <cdp-target-id>]
  preflight [--attach-port <port>] [--attach-host <host>] [--local-url <url>] [--strict]
  navigate --session-id <id> --url <url> [--viewport desktop]
  eval --session-id <id> --expression <js>
  scenario list [--tags critical,wallet] [--ids scenario-a,scenario-b]
  scenario run [--tags critical,wallet] [--ids scenario-a,scenario-b] [--viewports desktop,mobile] [--dry-run] [--include-compare] [--session-id <id>] [--headed] [--manage-local-app] [--attach-port <port>] [--attach-host <host>] [--target-id <cdp-target-id>] [--run-kind <kind>]
  session start [--headed] [--manage-local-app] [--attach-port <port>] [--attach-host <host>] [--target-id <cdp-target-id>]
  session attach --attach-port <port> [--attach-host <host>] [--manage-local-app] [--target-id <cdp-target-id>]
  session targets (--session-id <id> | --attach-port <port>) [--attach-host <host>]
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

  if (command === "session" && (subcommand === "start" || subcommand === "attach")) {
    if (subcommand === "attach" && !args["attach-port"]) {
      throw new Error("session attach requires --attach-port");
    }
    const session = await service.startSession({
      headless: !Boolean(args.headed),
      manageLocalApp: Boolean(args["manage-local-app"]),
      localAppUrl: args["local-url"],
      attachPort: args["attach-port"] || null,
      attachHost: args["attach-host"] || null,
      targetId: args["target-id"] || null
    });
    asJson(session);
    return;
  }

  if (command === "session" && subcommand === "targets") {
    if (!args["session-id"] && !args["attach-port"]) {
      throw new Error("session targets requires --session-id or --attach-port");
    }
    const result = await service.listTargets({
      sessionId: args["session-id"] || null,
      attachPort: args["attach-port"] || null,
      attachHost: args["attach-host"] || null
    });
    asJson(result);
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

  if (command === "scenario" && subcommand === "list") {
    const scenarios = await loadScenarios({
      ids: parseCsvArg(args.ids),
      tags: parseCsvArg(args.tags)
    });
    asJson({
      scenarios: scenarios.map((scenario) => ({
        id: scenario.id,
        title: scenario.title,
        route: scenario.route || scenario.url,
        severity: scenario.severity || "high",
        tags: scenario.tags,
        viewports: scenario.viewports,
        filePath: scenario.filePath
      }))
    });
    return;
  }

  if (command === "scenario" && subcommand === "run") {
    const service = await BrowserInspectionService.create();
    const result = await runScenarioBundle(service, {
      scenarioIds: parseCsvArg(args.ids),
      tags: parseCsvArg(args.tags),
      viewports: parseViewportList(args.viewports),
      dryRun: Boolean(args["dry-run"]),
      includeCompare: Boolean(args["include-compare"]),
      sessionId: args["session-id"] || null,
      headless: !Boolean(args.headed),
      manageLocalApp: args["manage-local-app"] ? true : undefined,
      attachPort: args["attach-port"] || null,
      attachHost: args["attach-host"] || null,
      targetId: args["target-id"] || null,
      localUrl: args["local-url"] || null,
      runKind: args["run-kind"] || "scenario"
    });
    asJson(result);
    if (!result.dryRun && result.state !== "pass") {
      process.exitCode = result.state === "manual-exception" ? 3 : 2;
    }
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

  if (command === "preflight") {
    const result = await service.preflight({
      attachPort: args["attach-port"] || null,
      attachHost: args["attach-host"] || null,
      localUrl: args["local-url"] || null
    });
    asJson(result);
    if (Boolean(args.strict) && !result.ok) {
      process.exitCode = 2;
    }
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
      localAppUrl: args["local-url"],
      attachPort: args["attach-port"] || null,
      attachHost: args["attach-host"] || null,
      targetId: args["target-id"] || null
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
      localAppUrl: args["local-url"],
      attachPort: args["attach-port"] || null,
      attachHost: args["attach-host"] || null,
      targetId: args["target-id"] || null
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
