import { Agent, MCPServerStdio, Runner, connectMcpServers } from "@openai/agents";
import { loadRoleConfig } from "./codex_roles.mjs";

function createCodexServer(repoRoot, roleName, roleConfig) {
  return new MCPServerStdio({
    name: `codex-${roleName}`,
    command: "codex",
    args: [
      "--cd",
      repoRoot,
      "--sandbox",
      roleConfig.sandbox_mode,
      "--ask-for-approval",
      "never",
      "mcp-server"
    ],
    cwd: repoRoot,
    clientSessionTimeoutSeconds: 3600
  });
}

function createBrowserServer(repoRoot) {
  return new MCPServerStdio({
    name: "hyperopen-browser",
    command: "node",
    args: ["./tools/browser-inspection/src/mcp_server.mjs"],
    cwd: repoRoot,
    clientSessionTimeoutSeconds: 3600
  });
}

function buildInstructions(roleConfig, prompt) {
  return `${roleConfig.developer_instructions.trim()}\n\n${prompt.trim()}`;
}

export async function runStructuredAgentPhase({
  repoRoot,
  roleName,
  prompt,
  outputSchema,
  includeBrowserMcp = false,
  maxTurns = 30
}) {
  if (!process.env.OPENAI_API_KEY) {
    throw new Error(
      "OPENAI_API_KEY is required for real multi-agent runs. Use npm run agent:dry-run for offline validation."
    );
  }
  const roleConfig = await loadRoleConfig(repoRoot, roleName);
  const servers = [createCodexServer(repoRoot, roleName, roleConfig)];
  if (includeBrowserMcp) {
    servers.push(createBrowserServer(repoRoot));
  }
  const connected = await connectMcpServers(servers, { connectInParallel: true });
  try {
    const agent = new Agent({
      name: roleName,
      handoffDescription: `${roleName} for the Hyperopen multi-agent workflow`,
      instructions: buildInstructions(roleConfig, prompt),
      model: roleConfig.model,
      modelSettings: {
        parallelToolCalls: false,
        reasoning: { effort: roleConfig.model_reasoning_effort }
      },
      mcpServers: connected.active,
      outputType: outputSchema
    });
    const runner = new Runner({ tracingDisabled: true });
    const result = await runner.run(agent, prompt, { maxTurns });
    if (!result.finalOutput) {
      throw new Error(`agent ${roleName} did not produce a structured final output`);
    }
    return result.finalOutput;
  } finally {
    await connected.close();
  }
}
