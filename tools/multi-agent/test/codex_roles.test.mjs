import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { loadRoleConfig, validateProjectConfig } from "../src/codex_roles.mjs";

async function makeTempRepo({ configToml, files }) {
  const repoRoot = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-codex-roles-"));
  await Promise.all(
    Object.entries(files).map(async ([filePath, contents]) => {
      const absolutePath = path.join(repoRoot, filePath);
      await fs.mkdir(path.dirname(absolutePath), { recursive: true });
      await fs.writeFile(absolutePath, contents);
    })
  );
  await fs.mkdir(path.join(repoRoot, ".codex"), { recursive: true });
  await fs.writeFile(path.join(repoRoot, ".codex", "config.toml"), configToml);
  return repoRoot;
}

const validRoleFiles = {
  ".codex/agents/spec-writer.toml": `name = "spec_writer"
description = "Clarifies scope."
model = "gpt-5.4"
model_reasoning_effort = "xhigh"
sandbox_mode = "workspace-write"
developer_instructions = "Write the plan."
nickname_candidates = ["Atlas"]
`,
  ".codex/agents/acceptance-tests.toml": `name = "acceptance_test_writer"
description = "Writes acceptance tests."
model = "gpt-5.4"
model_reasoning_effort = "xhigh"
sandbox_mode = "workspace-write"
developer_instructions = "Write acceptance tests."
`,
  ".codex/agents/edge-case-tests.toml": `name = "edge_case_test_writer"
description = "Writes edge-case tests."
model = "gpt-5.4"
model_reasoning_effort = "xhigh"
sandbox_mode = "workspace-write"
developer_instructions = "Write edge-case tests."
`,
  ".codex/agents/tdd_test_writer.toml": `name = "tdd_test_writer"
description = "Writes RED-phase tests."
model = "gpt-5.4"
model_reasoning_effort = "xhigh"
sandbox_mode = "workspace-write"
developer_instructions = "Write failing tests."
`,
  ".codex/agents/worker.toml": `name = "worker"
description = "Implements the smallest change."
model = "gpt-5.4"
model_reasoning_effort = "xhigh"
sandbox_mode = "workspace-write"
developer_instructions = "Implement the fix."
`,
  ".codex/agents/reviewer.toml": `name = "reviewer"
description = "Read-only reviewer."
model = "gpt-5.4"
model_reasoning_effort = "xhigh"
sandbox_mode = "read-only"
developer_instructions = "Review the change."
`,
  ".codex/agents/browser-debugger.toml": `name = "browser_debugger"
description = "Browser debugger."
model = "gpt-5.4"
model_reasoning_effort = "xhigh"
sandbox_mode = "workspace-write"
developer_instructions = "Inspect the browser flow."
`
};

const validProjectConfig = `[features]
multi_agent = true

[agents]
max_threads = 6
max_depth = 1

[agents.spec_writer]
description = "Clarifies scope."
config_file = ".codex/agents/spec-writer.toml"

[agents.acceptance_test_writer]
description = "Writes acceptance tests."
config_file = ".codex/agents/acceptance-tests.toml"

[agents.edge_case_test_writer]
description = "Writes edge-case tests."
config_file = ".codex/agents/edge-case-tests.toml"

[agents.tdd_test_writer]
description = "Writes RED-phase tests."
config_file = ".codex/agents/tdd_test_writer.toml"

[agents.worker]
description = "Implements the change."
config_file = ".codex/agents/worker.toml"

[agents.reviewer]
description = "Reviews the change."
config_file = ".codex/agents/reviewer.toml"

[agents.browser_debugger]
description = "Runs browser QA."
config_file = ".codex/agents/browser-debugger.toml"

[mcp_servers.hyperopen-browser]
command = "node"
args = ["./tools/browser-inspection/src/mcp_server.mjs"]
cwd = "."
`;

test("loadRoleConfig resolves manager roles through .codex/config.toml", async () => {
  const repoRoot = await makeTempRepo({ configToml: validProjectConfig, files: validRoleFiles });
  const roleConfig = await loadRoleConfig(repoRoot, "spec_writer");
  assert.equal(roleConfig.model, "gpt-5.4");
  assert.equal(roleConfig.model_reasoning_effort, "xhigh");
  assert.equal(roleConfig.sandbox_mode, "workspace-write");
  assert.equal(roleConfig.developer_instructions, "Write the plan.");
});

test("validateProjectConfig rejects manager role paths outside .codex/agents", async () => {
  const repoRoot = await makeTempRepo({
    configToml: validProjectConfig.replace(
      '.codex/agents/reviewer.toml',
      'agents/reviewer.toml'
    ),
    files: {
      ...validRoleFiles,
      "agents/reviewer.toml": validRoleFiles[".codex/agents/reviewer.toml"]
    }
  });
  await assert.rejects(() => validateProjectConfig(repoRoot), /point inside \.codex\/agents/);
});

test("validateProjectConfig rejects missing required role entries", async () => {
  const repoRoot = await makeTempRepo({
    configToml: validProjectConfig.replace(
      '\n[agents.tdd_test_writer]\ndescription = "Writes RED-phase tests."\nconfig_file = ".codex/agents/tdd_test_writer.toml"\n',
      "\n"
    ),
    files: validRoleFiles
  });
  await assert.rejects(() => validateProjectConfig(repoRoot), /expected project config to define role tdd_test_writer/);
});

test("validateProjectConfig rejects role files whose custom-agent name does not match the required role", async () => {
  const repoRoot = await makeTempRepo({
    configToml: validProjectConfig,
    files: {
      ...validRoleFiles,
      ".codex/agents/worker.toml": `name = "ui_fixer"
description = "Wrong name."
model = "gpt-5.4"
model_reasoning_effort = "xhigh"
sandbox_mode = "workspace-write"
developer_instructions = "Implement the fix."
`
    }
  });
  await assert.rejects(() => validateProjectConfig(repoRoot), /expected \.codex\/agents\/worker\.toml to declare name = "worker"/);
});

test("validateProjectConfig rejects configured optional roles whose custom-agent name does not match the config key", async () => {
  const repoRoot = await makeTempRepo({
    configToml: `${validProjectConfig}

[agents.architect_review]
description = "Architect review."
config_file = ".codex/agents/architect-review.toml"
`,
    files: {
      ...validRoleFiles,
      ".codex/agents/architect-review.toml": `name = "architect-review"
description = "Wrong optional role name."
model = "gpt-5.4"
model_reasoning_effort = "xhigh"
developer_instructions = "Review architecture."
`
    }
  });
  await assert.rejects(() => validateProjectConfig(repoRoot), /expected \.codex\/agents\/architect-review\.toml to declare name = "architect_review"/);
});
