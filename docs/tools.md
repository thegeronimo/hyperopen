---
owner: platform
status: canonical
last_reviewed: 2026-03-04
review_cycle_days: 90
source_of_truth: true
---

# Hyperopen Agent Tooling Reference

Use this file as the single starting point for what actions this repo provides to agents.

## Quick map

1. For a quick list of build/test commands, start with this section in `/hyperopen/docs/references/toolchain.md`.
2. For shared command phrase lookup, use `/hyperopen/tools/phrase get "<phrase>"` and `/hyperopen/command-phrases.edn`.
3. For browser parity/debug workflows, use the Browser Inspection section below.
4. For issue tracking and session handoff rules, use `/hyperopen/docs/WORK_TRACKING.md`.
5. For exact browser inspection command syntax, see:
   - `/hyperopen/tools/browser-inspection/src/cli.mjs`
   - `/hyperopen/tools/browser-inspection/src/mcp_server.mjs`

## 1) Core build/test commands (`package.json` scripts)

| Command | Purpose | When to use |
| --- | --- | --- |
| `npm run check` | Lint, docs checks, and compile app/test builds | Before finishing code changes |
| `npm test` | Full test suite | Regular validation and regression confidence |
| `npm run test:websocket` | Websocket-only suite | Websocket runtime/API changes |
| `npm run test:runner:generate` | Regenerate test runner list | Usually after adding/removing test namespaces |
| `npm run browser:inspect -- --url <url> --target <label>` | One-off parity capture | Visual/runtime parity evidence and smoke checks |
| `npm run browser:compare` | One-off compare capture | Compare two targets (Hyperliquid vs local) |
| `npm run browser:mcp` | Start Codex MCP tools host | When invoking via MCP instead of CLI |
| `npm run test:browser-inspection` | Browser-inspection unit tests | Before changing inspection tooling |
| `npm run test:browser-inspection:smoke` | Optional real-Chrome smoke | Manual confidence check before parity workflows |

## 2) Skills available to agents

| Skill | Purpose | Trigger |
| --- | --- | --- |
| `$CODEX_HOME/skills/pdf/SKILL.md` | PDF reading/creation checks | Ask about PDF processing workflows |
| `$CODEX_HOME/skills/.system/skill-installer/SKILL.md` | Install Codex skills | Need a new skill or curated skill list |
| `$CODEX_HOME/skills/.system/skill-creator/SKILL.md` | Create/update new Codex skills | Need to author a reusable skill |

If a task explicitly names one of these skills, follow that skill workflow first.

## 3) Shared command phrase lookup

- Machine-readable registry: `/hyperopen/command-phrases.edn`
- Lookup command: `/hyperopen/tools/phrase get "<phrase>"`
- Registry schema: `:schema-version 2` with `:commands` and `:alias->id`.
- Lookup model: normalize input once (trim/collapse spaces/lowercase), then direct map lookups in `:alias->id` and `:commands`.

Example:
- `/hyperopen/tools/phrase get "land the worktree"`
- `/hyperopen/tools/phrase get "lan the worktree"`

## 4) Issue tracking tool (`bd`)

`bd` is the canonical issue tracker for this repository.

Common commands:
- `bd ready --json`
- `bd update <id> --claim --json`
- `bd create "Issue title" --description="<details>" -t bug|feature|task|epic|chore -p 0-4 --json`
- `bd close <id> --reason "Completed" --json`
- `bd backup status --json`

For policy details, including markdown-vs-`bd` boundaries and session-completion workflow, follow `/hyperopen/docs/WORK_TRACKING.md`.

## 5) Browser Inspection tools (CLI)

All browser-inspection tooling lives under `/hyperopen/tools/browser-inspection/`.

### Start/end session and tab management

| CLI command | Example | Use this when |
| --- | --- | --- |
| `node tools/browser-inspection/src/cli.mjs session start` | `... session start --headed --manage-local-app` | You need a long-lived tool session |
| `node tools/browser-inspection/src/cli.mjs session attach --attach-port <port>` | `... session attach --attach-port 9222` | You already have a Chrome with remote debugging |
| `node tools/browser-inspection/src/cli.mjs session list` | `... session list` | You need active Codex/browser sessions |
| `node tools/browser-inspection/src/cli.mjs session targets --attach-port <port>` | `... session targets --attach-port 9222` | You need to pick a target tab |
| `node tools/browser-inspection/src/cli.mjs session stop --session-id <id>` | `... session stop --session-id abc` | You want to cleanly close a session |

### Active page operations

| CLI command | Example | Use this when |
| --- | --- | --- |
| `node tools/browser-inspection/src/cli.mjs navigate` | `... navigate --session-id abc --url https://app.hyperliquid.xyz/trade --viewport desktop` | Moving a session tab to a URL |
| `node tools/browser-inspection/src/cli.mjs eval` | `... eval --session-id abc --expression "({url: location.href})"` | Lightweight read-only DOM/runtime checks |
| `node tools/browser-inspection/src/cli.mjs inspect` | `... inspect --url https://app.hyperliquid.xyz/trade --target hyperliquid` | One-off snapshot capture |
| `node tools/browser-inspection/src/cli.mjs compare` | `... compare --left-url ... --right-url ... --left-label ... --right-label ...` | Diff local vs reference across viewports |

### Safe defaults and constraints

- Session commands are read-only by design.
- Snapshot output is written to `/hyperopen/tmp/browser-inspection/`.
- Use explicit `--target-id` when attaching to avoid wrong tab capture.
- For tab-selection stability, follow `/hyperopen/docs/runbooks/browser-live-inspection.md` and use marker verification steps.

## 6) Browser Inspection tools (Codex MCP)

Register once in Codex once and then call MCP tools directly:
- `codex mcp add hyperopen-browser -- node ./tools/browser-inspection/src/mcp_server.mjs`
- `codex mcp list`

| MCP tool | MCP `inputSchema` intent |
| --- | --- |
| `browser_session_start` | Start a live Chrome inspection session |
| `browser_session_stop` | End a session by `sessionId` |
| `browser_sessions_list` | List active sessions |
| `browser_targets_list` | List page targets by session or attach endpoint |
| `browser_navigate` | Navigate a session target to a URL |
| `browser_eval` | Read-only JS eval in-session |
| `browser_capture_snapshot` | Capture snapshot artifacts for a target |
| `browser_compare_targets` | Capture+compare two URLs/targets |

## 7) Where definitions live

- Scripted command surface: `/hyperopen/package.json`
- Phrase registry: `/hyperopen/command-phrases.edn`
- Phrase lookup CLI: `/hyperopen/tools/phrase`
- MCP definitions: `/hyperopen/tools/browser-inspection/src/mcp_server.mjs`
- CLI definitions: `/hyperopen/tools/browser-inspection/src/cli.mjs`
- Runtime and config: `/hyperopen/tools/browser-inspection/src/service.mjs` and `/hyperopen/tools/browser-inspection/config/defaults.json`
- Operational runbook: `/hyperopen/docs/runbooks/browser-live-inspection.md`
