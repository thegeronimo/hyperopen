import fs from "node:fs/promises";
import path from "node:path";
import * as TOML from "smol-toml";
import { z } from "zod";

const roleConfigSchema = z.object({
  model: z.string().min(1),
  model_reasoning_effort: z.enum(["none", "minimal", "low", "medium", "high", "xhigh"]),
  sandbox_mode: z.enum(["read-only", "workspace-write", "danger-full-access"]),
  developer_instructions: z.string().min(1)
});

const expectedRoles = {
  spec_writer: "agents/spec-writer.toml",
  acceptance_test_writer: "agents/acceptance-tests.toml",
  edge_case_test_writer: "agents/edge-case-tests.toml",
  worker: "agents/worker.toml",
  reviewer: "agents/reviewer.toml",
  browser_debugger: "agents/browser-debugger.toml"
};

export async function readProjectConfig(repoRoot) {
  const filePath = path.join(repoRoot, ".codex", "config.toml");
  const parsed = TOML.parse(await fs.readFile(filePath, "utf8"));
  return parsed;
}

export async function validateProjectConfig(repoRoot) {
  const config = await readProjectConfig(repoRoot);
  if (config?.features?.multi_agent !== true) {
    throw new Error("expected .codex/config.toml to enable features.multi_agent");
  }
  if (config?.agents?.max_threads !== 6 || config?.agents?.max_depth !== 1) {
    throw new Error("expected .codex/config.toml to set agents.max_threads=6 and agents.max_depth=1");
  }
  for (const [roleName, configFile] of Object.entries(expectedRoles)) {
    if (config?.agents?.[roleName]?.config_file !== configFile) {
      throw new Error(`expected project config for ${roleName} to point to ${configFile}`);
    }
  }
  if (config?.mcp_servers?.["hyperopen-browser"]?.args?.[0] !== "./tools/browser-inspection/src/mcp_server.mjs") {
    throw new Error("expected project config to register the hyperopen-browser MCP server");
  }
  return config;
}

export async function loadRoleConfig(repoRoot, roleName) {
  const roleFile = expectedRoles[roleName];
  if (!roleFile) {
    throw new Error(`unknown role: ${roleName}`);
  }
  const parsed = TOML.parse(await fs.readFile(path.join(repoRoot, roleFile), "utf8"));
  return roleConfigSchema.parse(parsed);
}

export async function loadAllRoleConfigs(repoRoot) {
  const configs = {};
  for (const roleName of Object.keys(expectedRoles)) {
    configs[roleName] = await loadRoleConfig(repoRoot, roleName);
  }
  return configs;
}
