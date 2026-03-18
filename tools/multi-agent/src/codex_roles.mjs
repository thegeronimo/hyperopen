import fs from "node:fs/promises";
import path from "node:path";
import * as TOML from "smol-toml";
import { z } from "zod";

const requiredRoleNames = [
  "spec_writer",
  "acceptance_test_writer",
  "edge_case_test_writer",
  "worker",
  "reviewer",
  "browser_debugger"
];

const roleConfigSchema = z
  .object({
    name: z.string().min(1),
    description: z.string().min(1),
    model: z.string().min(1),
    model_reasoning_effort: z.enum(["none", "minimal", "low", "medium", "high", "xhigh"]),
    sandbox_mode: z.enum(["read-only", "workspace-write", "danger-full-access"]),
    developer_instructions: z.string().min(1)
  })
  .passthrough();

function normalizeRoleConfigPath(roleName, configFile) {
  if (typeof configFile !== "string" || configFile.length === 0) {
    throw new Error(`expected project config for ${roleName} to declare a config_file`);
  }
  const normalized = path.posix.normalize(configFile);
  if (path.posix.isAbsolute(normalized) || normalized === ".." || normalized.startsWith("../")) {
    throw new Error(`expected project config for ${roleName} to use a repo-relative config_file`);
  }
  if (!normalized.startsWith(".codex/agents/")) {
    throw new Error(`expected project config for ${roleName} to point inside .codex/agents`);
  }
  return normalized;
}

function resolveRequiredRoleConfig(config, roleName) {
  const roleConfig = config?.agents?.[roleName];
  if (!roleConfig || typeof roleConfig !== "object") {
    throw new Error(`expected project config to define required role ${roleName}`);
  }
  return normalizeRoleConfigPath(roleName, roleConfig.config_file);
}

async function parseRoleConfig(repoRoot, roleName, configFile) {
  const parsed = TOML.parse(await fs.readFile(path.join(repoRoot, configFile), "utf8"));
  const roleConfig = roleConfigSchema.parse(parsed);
  if (roleConfig.name !== roleName) {
    throw new Error(`expected ${configFile} to declare name = "${roleName}"`);
  }
  return {
    model: roleConfig.model,
    model_reasoning_effort: roleConfig.model_reasoning_effort,
    sandbox_mode: roleConfig.sandbox_mode,
    developer_instructions: roleConfig.developer_instructions
  };
}

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
  for (const roleName of requiredRoleNames) {
    const configFile = resolveRequiredRoleConfig(config, roleName);
    await parseRoleConfig(repoRoot, roleName, configFile);
  }
  if (config?.mcp_servers?.["hyperopen-browser"]?.args?.[0] !== "./tools/browser-inspection/src/mcp_server.mjs") {
    throw new Error("expected project config to register the hyperopen-browser MCP server");
  }
  return config;
}

export async function loadRoleConfig(repoRoot, roleName) {
  if (!requiredRoleNames.includes(roleName)) {
    throw new Error(`unknown role: ${roleName}`);
  }
  const config = await readProjectConfig(repoRoot);
  const configFile = resolveRequiredRoleConfig(config, roleName);
  return parseRoleConfig(repoRoot, roleName, configFile);
}

export async function loadAllRoleConfigs(repoRoot) {
  const configs = {};
  for (const roleName of requiredRoleNames) {
    configs[roleName] = await loadRoleConfig(repoRoot, roleName);
  }
  return configs;
}
