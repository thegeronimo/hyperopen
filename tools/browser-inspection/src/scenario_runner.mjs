import fs from "node:fs/promises";
import path from "node:path";
import { captureSnapshot } from "./capture_pipeline.mjs";
import { classifyErrorMessage } from "./failure_classification.mjs";
import {
  getDefaultScenarioDir,
  loadScenarios,
  loadScenarioRouting,
  selectScenarioTagsForChangedFiles
} from "./scenario_loader.mjs";
import { writeSnapshotArtifacts } from "./service.mjs";
import { ensureDir, safeNowIso, sleep, writeJsonFile } from "./util.mjs";

function getByPath(value, refPath) {
  const parts = String(refPath || "")
    .split(".")
    .map((part) => part.trim())
    .filter(Boolean);
  let current = value;
  for (const part of parts) {
    current = current?.[part];
  }
  return current;
}

function resolveRefs(value, context) {
  if (Array.isArray(value)) {
    return value.map((entry) => resolveRefs(entry, context));
  }
  if (!value || typeof value !== "object") {
    return value;
  }
  const keys = Object.keys(value);
  if (keys.length === 1 && keys[0] === "$ref") {
    return getByPath(context, value.$ref);
  }
  const out = {};
  for (const [key, entry] of Object.entries(value)) {
    out[key] = resolveRefs(entry, context);
  }
  return out;
}

function comparableJson(value) {
  return JSON.stringify(value, null, 2);
}

function compareExpectation(actual, expect, pathLabel = "result", mismatches = []) {
  if (expect && typeof expect === "object" && !Array.isArray(expect)) {
    const operatorKeys = ["$gt", "$gte", "$lt", "$lte", "$includes"];
    const hasOperator = operatorKeys.some((key) => Object.hasOwn(expect, key));
    if (hasOperator) {
      if (expect.$gt !== undefined && !(actual > expect.$gt)) {
        mismatches.push(`${pathLabel} expected > ${expect.$gt}, got ${actual}`);
      }
      if (expect.$gte !== undefined && !(actual >= expect.$gte)) {
        mismatches.push(`${pathLabel} expected >= ${expect.$gte}, got ${actual}`);
      }
      if (expect.$lt !== undefined && !(actual < expect.$lt)) {
        mismatches.push(`${pathLabel} expected < ${expect.$lt}, got ${actual}`);
      }
      if (expect.$lte !== undefined && !(actual <= expect.$lte)) {
        mismatches.push(`${pathLabel} expected <= ${expect.$lte}, got ${actual}`);
      }
      if (expect.$includes !== undefined) {
        const haystack = Array.isArray(actual) ? actual : String(actual || "");
        const needle = expect.$includes;
        const ok = Array.isArray(haystack)
          ? haystack.includes(needle)
          : String(haystack).includes(String(needle));
        if (!ok) {
          mismatches.push(`${pathLabel} expected to include ${needle}, got ${actual}`);
        }
      }
      return mismatches;
    }

    for (const [key, value] of Object.entries(expect)) {
      compareExpectation(actual?.[key], value, `${pathLabel}.${key}`, mismatches);
    }
    return mismatches;
  }

  if (JSON.stringify(actual) !== JSON.stringify(expect)) {
    mismatches.push(`${pathLabel} expected ${JSON.stringify(expect)}, got ${JSON.stringify(actual)}`);
  }
  return mismatches;
}

function scenarioStateForError(error, fallbackState = "product-regression") {
  if (fallbackState && fallbackState !== "product-regression") {
    return fallbackState;
  }
  const classification = classifyErrorMessage(error?.message || error);
  if (classification?.classification === "automation-gap") {
    return "automation-gap";
  }
  return "product-regression";
}

function severityRank(state) {
  switch (state) {
    case "automation-gap":
      return 4;
    case "product-regression":
      return 3;
    case "manual-exception":
      return 2;
    case "pass":
    default:
      return 1;
  }
}

function bundleState(results) {
  if ((results || []).length === 0) {
    return "automation-gap";
  }
  return [...results]
    .sort((left, right) => severityRank(right.state) - severityRank(left.state))[0]
    .state;
}

function renderScenarioMarkdown(result) {
  const stepLines = result.steps
    .map(
      (step, index) =>
        `- ${index + 1}. \`${step.type}\` -> ${step.status}${step.message ? `: ${step.message}` : ""}`
    )
    .join("\n");

  return `# Scenario Result - ${result.scenarioId} (${result.viewport})

## Summary

- State: \`${result.state}\`
- Title: ${result.title}
- Route: \`${result.route}\`
- URL: \`${result.url}\`
- Started: \`${result.startedAt}\`
- Ended: \`${result.endedAt}\`

## Steps

${stepLines}

## Notes

- Snapshot path: ${result.snapshotPath ? `\`${result.snapshotPath}\`` : "none"}
- Screenshot path: ${result.screenshotPath ? `\`${result.screenshotPath}\`` : "none"}
- Compare run: ${result.compareRunId ? `\`${result.compareRunId}\`` : "not run"}
`;
}

function renderBundleMarkdown(summary) {
  const scenarioLines = summary.results
    .map(
      (result) =>
        `- \`${result.scenarioId}\` / \`${result.viewport}\`: ${result.state}${result.message ? ` - ${result.message}` : ""}`
    )
    .join("\n");

  return `# Scenario Bundle - ${summary.runId}

## Summary

- State: \`${summary.state}\`
- Mode: \`${summary.mode}\`
- Scenario count: ${summary.results.length}
- Started: \`${summary.startedAt}\`
- Ended: \`${summary.endedAt}\`

## Results

${scenarioLines}
`;
}

function parseArgsList(value) {
  if (!value) {
    return [];
  }
  return String(value)
    .split(",")
    .map((part) => part.trim())
    .filter(Boolean);
}

async function debugCall(service, sessionId, method, args = [], timeoutMs = 15000) {
  const expression = `(async () => {
    const api = globalThis.HYPEROPEN_DEBUG;
    if (!api) {
      throw new Error("HYPEROPEN_DEBUG unavailable");
    }
    const fn = api[${JSON.stringify(method)}];
    if (typeof fn !== "function") {
      throw new Error("HYPEROPEN_DEBUG." + ${JSON.stringify(method)} + " is unavailable");
    }
    return await fn(...${JSON.stringify(args)});
  })()`;
  const result = await service.evaluate({
    sessionId,
    expression,
    timeoutMs
  });
  return result.result;
}

async function evalExpression(service, sessionId, step) {
  const result = await service.evaluate({
    sessionId,
    expression: step.expression,
    allowUnsafeEval: Boolean(step.allowUnsafeEval),
    timeoutMs: step.timeoutMs || 15000
  });
  return result.result;
}

async function executeStep({
  service,
  sessionId,
  scenario,
  viewportName,
  step,
  context,
  runDir,
  includeCompare
}) {
  const resolvedStep = resolveRefs(step, context);
  const label = `${scenario.id}/${viewportName}/${step.type}`;
  switch (resolvedStep.type) {
    case "navigate":
      return service.navigate({
        sessionId,
        url: resolvedStep.url || scenario.url,
        viewportName
      });
    case "dispatch":
      return debugCall(service, sessionId, "dispatch", [resolvedStep.action], resolvedStep.timeoutMs);
    case "dispatch_many":
      return debugCall(service, sessionId, "dispatchMany", [resolvedStep.actions], resolvedStep.timeoutMs);
    case "wait_for_idle":
      return debugCall(
        service,
        sessionId,
        "waitForIdle",
        [resolvedStep.options || {
          quietMs: resolvedStep.quietMs || 250,
          timeoutMs: resolvedStep.timeoutMs || 6000,
          pollMs: resolvedStep.pollMs || 50
        }],
        resolvedStep.timeoutMs || 7000
      );
    case "debug_call":
      return debugCall(
        service,
        sessionId,
        resolvedStep.method,
        resolvedStep.args || [],
        resolvedStep.timeoutMs || 15000
      );
    case "eval":
      return evalExpression(service, sessionId, resolvedStep);
    case "wait_for_eval": {
      const timeoutMs = resolvedStep.timeoutMs || 5000;
      const pollMs = resolvedStep.pollMs || 100;
      const startedAt = Date.now();
      let lastResult = null;
      let lastMismatches = [];
      do {
        lastResult = await evalExpression(service, sessionId, resolvedStep);
        lastMismatches = resolvedStep.expect
          ? compareExpectation(lastResult, resolvedStep.expect)
          : [];
        if (lastMismatches.length === 0) {
          return lastResult;
        }
        await sleep(pollMs);
      } while (Date.now() - startedAt < timeoutMs);
      throw new Error(
        `wait_for_eval timed out for ${label}: ${lastMismatches.join("; ")}`
      );
    }
    case "oracle":
      return debugCall(
        service,
        sessionId,
        "oracle",
        [resolvedStep.name, resolvedStep.args || {}],
        resolvedStep.timeoutMs || 15000
      );
    case "wait_for_oracle": {
      const timeoutMs = resolvedStep.timeoutMs || 5000;
      const pollMs = resolvedStep.pollMs || 100;
      const startedAt = Date.now();
      let lastResult = null;
      let lastMismatches = [];
      do {
        lastResult = await debugCall(
          service,
          sessionId,
          "oracle",
          [resolvedStep.name, resolvedStep.args || {}],
          timeoutMs
        );
        lastMismatches = resolvedStep.expect
          ? compareExpectation(lastResult, resolvedStep.expect)
          : [];
        if (lastMismatches.length === 0) {
          return lastResult;
        }
        await sleep(pollMs);
      } while (Date.now() - startedAt < timeoutMs);
      throw new Error(
        `wait_for_oracle timed out for ${label}: ${lastMismatches.join("; ")}`
      );
    }
    case "sleep":
      await sleep(resolvedStep.ms || 100);
      return { sleptMs: resolvedStep.ms || 100 };
    case "capture": {
      const payload = await captureSnapshot(service.sessionManager, sessionId, {
        navigate: false,
        viewportName,
        viewport: service.config.viewports[viewportName],
        targetLabel: resolvedStep.targetLabel || scenario.id
      });
      const persisted = await writeSnapshotArtifacts(runDir, payload);
      return {
        snapshotPath: persisted.snapshotPath,
        screenshotPath: persisted.screenshotPath
      };
    }
    case "compare": {
      if (!includeCompare) {
        return { skipped: true, reason: "compare disabled for this run" };
      }
      const result = await service.compare({
        leftUrl: resolvedStep.leftUrl || service.config.targets.remote.url,
        rightUrl: resolvedStep.rightUrl || scenario.url,
        leftLabel: resolvedStep.leftLabel || service.config.targets.remote.label,
        rightLabel: resolvedStep.rightLabel || scenario.id,
        viewports: [viewportName],
        headless: true
      });
      return {
        runId: result.runId,
        runDir: result.runDir
      };
    }
    default:
      throw new Error(`Unsupported scenario step type at runtime: ${label}`);
  }
}

async function executeScenarioViewport({
  service,
  sessionId,
  scenario,
  viewportName,
  runDir,
  includeCompare
}) {
  const startedAt = safeNowIso();
  const context = {};
  const steps = [];
  let snapshotPath = null;
  let screenshotPath = null;
  let compareRunId = null;
  let state = "pass";
  let message = null;
  let lastStepType = null;

  try {
    for (const [index, rawStep] of scenario.steps.entries()) {
      lastStepType = rawStep.type;
      const result = await executeStep({
        service,
        sessionId,
        scenario,
        viewportName,
        step: rawStep,
        context,
        runDir,
        includeCompare
      });

      const mismatches = rawStep.expect
        ? compareExpectation(result, resolveRefs(rawStep.expect, context))
        : [];

      if (mismatches.length > 0) {
        throw new Error(`Step ${index + 1} expectation failed: ${mismatches.join("; ")}`);
      }

      if (rawStep.saveAs) {
        context[rawStep.saveAs] = result;
      }

      if (rawStep.type === "capture") {
        snapshotPath = result.snapshotPath || snapshotPath;
        screenshotPath = result.screenshotPath || screenshotPath;
      }
      if (rawStep.type === "compare" && result.runId) {
        compareRunId = result.runId;
      }

      steps.push({
        type: rawStep.type,
        status: "pass",
        result
      });
    }
  } catch (error) {
    state = scenarioStateForError(error, scenario.onFailureState);
    message = error?.message || String(error);
    steps.push({
      type: lastStepType || "unknown",
      status: "failed",
      message
    });
  }

  const endedAt = safeNowIso();
  return {
    scenarioId: scenario.id,
    title: scenario.title,
    route: scenario.route || scenario.url,
    url: scenario.url,
    severity: scenario.severity || "high",
    viewport: viewportName,
    state,
    message,
    startedAt,
    endedAt,
    snapshotPath,
    screenshotPath,
    compareRunId,
    steps
  };
}

export async function runScenarioBundle(service, options = {}) {
  const scenarioDir = options.scenarioDir || getDefaultScenarioDir();
  const selectedScenarios = await loadScenarios({
    scenarioDir,
    ids: options.scenarioIds || [],
    tags: options.tags || []
  });

  if (selectedScenarios.length === 0) {
    throw new Error("No scenarios matched the requested selection.");
  }

  if (options.dryRun) {
    return {
      dryRun: true,
      scenarioDir,
      selected: selectedScenarios.map((scenario) => ({
        id: scenario.id,
        title: scenario.title,
        tags: scenario.tags,
        viewports: options.viewports?.length ? options.viewports : scenario.viewports
      }))
    };
  }

  const run = await service.sessionManager.store.createRun(options.runKind || "scenario", {
    requestedAt: safeNowIso(),
    scenarioIds: selectedScenarios.map((scenario) => scenario.id),
    tags: options.tags || []
  });
  const runDir = run.runDir;
  const preflight = await service.preflight({
    attachPort: options.attachPort || null,
    attachHost: options.attachHost || null,
    localUrl: options.localUrl || service.config.localApp.url
  });
  await writeJsonFile(path.join(runDir, "preflight.json"), preflight);

  const startedAt = safeNowIso();
  const results = [];
  const mode = options.attachPort ? "attach" : "local";

  if (!preflight.ok) {
    const summary = {
      runId: run.runId,
      runDir,
      state: "automation-gap",
      mode,
      startedAt,
      endedAt: safeNowIso(),
      preflight,
      results
    };
    await writeJsonFile(path.join(runDir, "summary.json"), summary);
    await fs.writeFile(path.join(runDir, "summary.md"), renderBundleMarkdown(summary));
    await service.sessionManager.store.failRun(runDir, "Preflight failed");
    return summary;
  }

  let session = null;
  try {
    session = options.sessionId
      ? { id: options.sessionId }
      : await service.startSession({
          headless: options.headless ?? true,
          manageLocalApp: options.manageLocalApp ?? mode === "local",
          localAppUrl: options.localUrl || service.config.localApp.url,
          attachPort: options.attachPort || null,
          attachHost: options.attachHost || null,
          targetId: options.targetId || null,
          readOnly: true
        });

    await ensureDir(path.join(runDir, "scenarios"));
    for (const scenario of selectedScenarios) {
      const scenarioViewports = options.viewports?.length ? options.viewports : scenario.viewports;
      for (const viewportName of scenarioViewports) {
        const result = await executeScenarioViewport({
          service,
          sessionId: session.id,
          scenario,
          viewportName,
          runDir,
          includeCompare: Boolean(options.includeCompare)
        });
        results.push(result);
        const baseName = `${scenario.id}-${viewportName}`;
        const jsonPath = path.join(runDir, "scenarios", `${baseName}.json`);
        const markdownPath = path.join(runDir, "scenarios", `${baseName}.md`);
        await writeJsonFile(jsonPath, result);
        await fs.writeFile(markdownPath, renderScenarioMarkdown(result));
        await service.sessionManager.store.appendArtifact(runDir, {
          type: "scenario-result",
          scenarioId: scenario.id,
          viewport: viewportName,
          state: result.state,
          jsonPath,
          markdownPath
        });
      }
    }
  } catch (error) {
    const state = scenarioStateForError(error, "automation-gap");
    results.push({
      scenarioId: "session-start",
      title: "Session startup",
      route: "n/a",
      url: "n/a",
      severity: "critical",
      viewport: "n/a",
      state,
      message: error?.message || String(error),
      startedAt,
      endedAt: safeNowIso(),
      steps: []
    });
  } finally {
    if (!options.sessionId && session?.id) {
      await service.stopSession(session.id).catch(() => null);
    }
  }

  const summary = {
    runId: run.runId,
    runDir,
    state: bundleState(results),
    mode,
    startedAt,
    endedAt: safeNowIso(),
    preflight,
    results
  };

  await writeJsonFile(path.join(runDir, "summary.json"), summary);
  await fs.writeFile(path.join(runDir, "summary.md"), renderBundleMarkdown(summary));
  if (summary.state === "pass") {
    await service.sessionManager.store.completeRun(runDir, summary);
  } else {
    await service.sessionManager.store.failRun(runDir, summary.state);
  }
  return summary;
}

export async function resolvePrSelection(options = {}) {
  const routing = await loadScenarioRouting(options.routingPath);
  return selectScenarioTagsForChangedFiles(options.changedFiles || [], routing);
}

export function parseCsvArg(value) {
  return parseArgsList(value);
}
