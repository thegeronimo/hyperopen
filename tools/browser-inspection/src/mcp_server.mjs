#!/usr/bin/env node
import * as z from "zod/v4";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { BrowserInspectionService } from "./service.mjs";
import { runDesignReview } from "./design_review_runner.mjs";
import {
  getBoundingBoxes,
  getComputedStyles,
  listNativeControls,
  runFocusWalk,
  traceInteraction
} from "./dom_probes.mjs";
import { loadScenarios } from "./scenario_loader.mjs";
import { runScenarioBundle } from "./scenario_runner.mjs";

function textResult(payload) {
  return {
    content: [
      {
        type: "text",
        text: JSON.stringify(payload, null, 2)
      }
    ]
  };
}

function errorResult(error) {
  return {
    isError: true,
    content: [
      {
        type: "text",
        text: error?.stack || error?.message || String(error)
      }
    ]
  };
}

export async function buildServer() {
  const service = await BrowserInspectionService.create();

  const server = new McpServer({
    name: "hyperopen-browser-inspection",
    version: "0.1.0"
  });

  server.registerTool(
    "browser_scenarios_list",
    {
      description: "List browser QA scenario manifests, optionally filtered by scenario ids or tags.",
      inputSchema: {
        ids: z.array(z.string()).optional(),
        tags: z.array(z.string()).optional()
      }
    },
    async ({ ids, tags }) => {
      try {
        const scenarios = await loadScenarios({ ids, tags });
        return textResult({
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
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_scenarios_run",
    {
      description:
        "Run one or more deterministic browser QA scenarios and persist artifacts under the browser inspection artifact root.",
      inputSchema: {
        ids: z.array(z.string()).optional(),
        tags: z.array(z.string()).optional(),
        viewports: z.array(z.string()).optional(),
        dryRun: z.boolean().optional(),
        includeCompare: z.boolean().optional(),
        sessionId: z.string().optional(),
        headless: z.boolean().optional(),
        manageLocalApp: z.boolean().optional(),
        localUrl: z.string().optional(),
        attachPort: z.number().int().positive().optional(),
        attachHost: z.string().optional(),
        targetId: z.string().optional(),
        runKind: z.string().optional()
      }
    },
    async ({
      ids,
      tags,
      viewports,
      dryRun,
      includeCompare,
      sessionId,
      headless,
      manageLocalApp,
      localUrl,
      attachPort,
      attachHost,
      targetId,
      runKind
    }) => {
      try {
        const result = await runScenarioBundle(service, {
          scenarioIds: ids,
          tags,
          viewports,
          dryRun,
          includeCompare,
          sessionId,
          headless,
          manageLocalApp,
          localUrl,
          attachPort,
          attachHost,
          targetId,
          runKind: runKind || "scenario"
        });
        return textResult(result);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_design_review",
    {
      description:
        "Run the design-system browser QA review across the required passes and review viewports, writing artifacts under the browser inspection artifact root.",
      inputSchema: {
        changedFiles: z.array(z.string()).optional(),
        targetIds: z.array(z.string()).optional(),
        viewports: z.array(z.string()).optional(),
        dryRun: z.boolean().optional(),
        sessionId: z.string().optional(),
        headless: z.boolean().optional(),
        manageLocalApp: z.boolean().optional(),
        localUrl: z.string().optional(),
        attachPort: z.number().int().positive().optional(),
        attachHost: z.string().optional(),
        targetId: z.string().optional()
      }
    },
    async ({
      changedFiles,
      targetIds,
      viewports,
      dryRun,
      sessionId,
      headless,
      manageLocalApp,
      localUrl,
      attachPort,
      attachHost,
      targetId
    }) => {
      try {
        const result = await runDesignReview(service, {
          changedFiles,
          targetIds,
          viewports,
          dryRun,
          sessionId,
          headless,
          manageLocalApp,
          localUrl,
          attachPort,
          attachHost,
          targetId
        });
        return textResult(result);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_session_start",
    {
      description:
        "Start a live Chrome inspection session. Returns session metadata for follow-up navigate/eval/capture calls.",
      inputSchema: {
        headless: z.boolean().optional(),
        manageLocalApp: z.boolean().optional(),
        localUrl: z.string().optional(),
        attachPort: z.number().int().positive().optional(),
        attachHost: z.string().optional(),
        targetId: z.string().optional()
      }
    },
    async ({ headless, manageLocalApp, localUrl, attachPort, attachHost, targetId }) => {
      try {
        const session = await service.startSession({
          headless,
          manageLocalApp,
          localAppUrl: localUrl,
          attachPort,
          attachHost,
          targetId,
          readOnly: true
        });
        return textResult(session);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_session_stop",
    {
      description: "Stop a live inspection session and cleanup browser resources.",
      inputSchema: {
        sessionId: z.string()
      }
    },
    async ({ sessionId }) => {
      try {
        const ok = await service.stopSession(sessionId);
        return textResult({ ok, sessionId });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_sessions_stop_all",
    {
      description:
        "Stop every active browser-inspection session and cleanup launched browsers or tool-created tabs.",
      inputSchema: {}
    },
    async () => {
      try {
        const result = await service.stopAllSessions();
        return textResult(result);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_sessions_list",
    {
      description: "List active browser inspection sessions.",
      inputSchema: {}
    },
    async () => {
      try {
        const sessions = await service.listSessions();
        return textResult({ sessions });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_targets_list",
    {
      description:
        "List page targets (tabs) from an existing session or directly from a Chrome DevTools endpoint.",
      inputSchema: {
        sessionId: z.string().optional(),
        attachPort: z.number().int().positive().optional(),
        attachHost: z.string().optional()
      }
    },
    async ({ sessionId, attachPort, attachHost }) => {
      try {
        const targets = await service.listTargets({
          sessionId,
          attachPort,
          attachHost
        });
        return textResult(targets);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_navigate",
    {
      description: "Navigate an existing session target to a URL and return page metadata.",
      inputSchema: {
        sessionId: z.string(),
        url: z.string(),
        viewportName: z.string().optional()
      }
    },
    async ({ sessionId, url, viewportName }) => {
      try {
        const result = await service.navigate({ sessionId, url, viewportName });
        return textResult(result);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_eval",
    {
      description: "Run read-only JavaScript evaluation in an existing browser session.",
      inputSchema: {
        sessionId: z.string(),
        expression: z.string(),
        allowUnsafeEval: z.boolean().optional()
      }
    },
    async ({ sessionId, expression, allowUnsafeEval }) => {
      try {
        const result = await service.evaluate({ sessionId, expression, allowUnsafeEval });
        return textResult(result);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_get_computed_style",
    {
      description:
        "Inspect computed styles for one or more selectors in the current page of an existing browser session.",
      inputSchema: {
        sessionId: z.string(),
        selectors: z.array(z.string()),
        props: z.array(z.string()).optional(),
        maxMatches: z.number().int().positive().optional()
      }
    },
    async ({ sessionId, selectors, props, maxMatches }) => {
      try {
        const result = await getComputedStyles(service, sessionId, {
          selectors,
          props,
          maxMatches
        });
        return textResult(result);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_list_native_controls",
    {
      description:
        "Enumerate visible native form controls in the current page and flag special native widgets against an optional allowlist.",
      inputSchema: {
        sessionId: z.string(),
        allowlist: z.array(z.string()).optional()
      }
    },
    async ({ sessionId, allowlist }) => {
      try {
        const result = await listNativeControls(service, sessionId, {
          allowlist
        });
        return textResult(result);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_get_bounding_boxes",
    {
      description:
        "Capture bounding boxes and basic identity metadata for selector matches in the current page.",
      inputSchema: {
        sessionId: z.string(),
        selectors: z.array(z.string())
      }
    },
    async ({ sessionId, selectors }) => {
      try {
        const result = await getBoundingBoxes(service, sessionId, {
          selectors
        });
        return textResult(result);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_focus_walk",
    {
      description:
        "Walk keyboard-focusable elements in selector scope and report visible-focus coverage and tab-sequence metadata.",
      inputSchema: {
        sessionId: z.string(),
        selectors: z.array(z.string()).optional(),
        limit: z.number().int().positive().optional()
      }
    },
    async ({ sessionId, selectors, limit }) => {
      try {
        const result = await runFocusWalk(service, sessionId, {
          selectors,
          limit
        });
        return textResult(result);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_trace_interaction",
    {
      description:
        "Repeat focus and scroll interactions in selector scope and return layout-shift and long-task metrics.",
      inputSchema: {
        sessionId: z.string(),
        selectors: z.array(z.string()).optional(),
        focusLimit: z.number().int().positive().optional(),
        scrollFractions: z.array(z.number()).optional(),
        delayMs: z.number().int().positive().optional(),
        dispatchActions: z.array(z.unknown()).optional()
      }
    },
    async ({ sessionId, selectors, focusLimit, scrollFractions, delayMs, dispatchActions }) => {
      try {
        const result = await traceInteraction(service, sessionId, {
          selectors,
          focusLimit,
          scrollFractions,
          delayMs,
          dispatchActions
        });
        return textResult(result);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_capture_snapshot",
    {
      description:
        "Capture DevTools-grade snapshot artifacts for a single target URL (console/network/DOM/screenshot).",
      inputSchema: {
        sessionId: z.string().optional(),
        url: z.string(),
        targetLabel: z.string().optional(),
        viewports: z.array(z.string()).optional(),
        headless: z.boolean().optional(),
        manageLocalApp: z.boolean().optional(),
        localUrl: z.string().optional(),
        attachPort: z.number().int().positive().optional(),
        attachHost: z.string().optional(),
        targetId: z.string().optional()
      }
    },
    async ({
      sessionId,
      url,
      targetLabel,
      viewports,
      headless,
      manageLocalApp,
      localUrl,
      attachPort,
      attachHost,
      targetId
    }) => {
      try {
        const result = await service.capture({
          sessionId,
          url,
          targetLabel,
          viewports,
          headless,
          manageLocalApp,
          localAppUrl: localUrl,
          attachPort,
          attachHost,
          targetId
        });
        return textResult(result);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "browser_compare_targets",
    {
      description:
        "Capture and compare two targets (typically Hyperliquid vs local Hyperopen) across one or more viewports.",
      inputSchema: {
        sessionId: z.string().optional(),
        leftUrl: z.string().optional(),
        rightUrl: z.string().optional(),
        leftLabel: z.string().optional(),
        rightLabel: z.string().optional(),
        viewports: z.array(z.string()).optional(),
        headless: z.boolean().optional(),
        manageLocalApp: z.boolean().optional(),
        localUrl: z.string().optional(),
        attachPort: z.number().int().positive().optional(),
        attachHost: z.string().optional(),
        targetId: z.string().optional()
      }
    },
    async ({
      sessionId,
      leftUrl,
      rightUrl,
      leftLabel,
      rightLabel,
      viewports,
      headless,
      manageLocalApp,
      localUrl,
      attachPort,
      attachHost,
      targetId
    }) => {
      try {
        const result = await service.compare({
          sessionId,
          leftUrl,
          rightUrl,
          leftLabel,
          rightLabel,
          viewports,
          headless,
          manageLocalApp,
          localAppUrl: localUrl,
          attachPort,
          attachHost,
          targetId
        });
        return textResult(result);
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  return server;
}

export async function main() {
  const server = await buildServer();
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error) => {
    process.stderr.write(`${error?.stack || error}\n`);
    process.exit(1);
  });
}
