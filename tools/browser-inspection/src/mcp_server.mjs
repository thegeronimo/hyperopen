#!/usr/bin/env node
import * as z from "zod/v4";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { BrowserInspectionService } from "./service.mjs";

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
