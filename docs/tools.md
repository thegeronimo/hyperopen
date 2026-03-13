---
owner: platform
status: canonical
last_reviewed: 2026-03-10
review_cycle_days: 90
source_of_truth: true
---

# Hyperopen Agent Tooling Reference

Use this file as the single starting point for what actions this repo provides to agents.

## Quick map

1. For a quick list of build/test commands, start with this section in `/hyperopen/docs/references/toolchain.md`.
2. For local Clojure discovery, semantic references, renames, diagnostics, and current-worktree Shadow nREPL lookup, use the Local Clojure Navigation and Analysis section below.
3. For shared command phrase lookup, use `/hyperopen/tools/phrase get "<phrase>"` and `/hyperopen/command-phrases.edn`.
4. For browser parity/debug workflows, use the Browser Inspection section below.
5. For CRAP hotspot analysis after generating coverage, use `bb tools/crap_report.clj --scope src` or `bb tools/crap_report.clj --module <path> --format json`.
6. For issue tracking and session handoff rules, use `/hyperopen/docs/WORK_TRACKING.md`.
7. For exact browser inspection command syntax, see:
   - `/hyperopen/tools/browser-inspection/src/cli.mjs`
   - `/hyperopen/tools/browser-inspection/src/mcp_server.mjs`

## 1) Core build/test commands (`package.json` scripts)

| Command | Purpose | When to use |
| --- | --- | --- |
| `npm run check` | Lint, docs checks, and compile app/test builds | Before finishing code changes |
| `npm test` | Full test suite | Regular validation and regression confidence |
| `npm run test:crap` | Fast Babashka tests for CRAP-tool parsing and report math | Before changing the CRAP analyzer/reporter |
| `npm run lint:delimiters -- --changed` | Fast Clojure/CLJS reader preflight on changed files | Before `npm test`, `npm run test:websocket`, or manual `shadow-cljs` compiles after syntax-heavy edits |
| `npm run test:websocket` | Websocket-only suite | Websocket runtime/API changes |
| `npm run dev` | Watch app and portfolio worker builds alongside Tailwind | Normal frontend development |
| `npm run dev:portfolio` | Watch app, Portfolio workbench, and portfolio worker builds alongside Tailwind | When you need the main app and workbench together |
| `npm run portfolio` | Watch only the Portfolio workbench plus Tailwind | Isolated component workbench iteration |
| `npm run portfolio:watch` | Watch the dedicated Shadow `:portfolio` target | Workbench-only CLJS compile loop |
| `npm run test:runner:generate` | Regenerate test runner list | Usually after adding/removing test namespaces |
| `bb tools/crap_report.clj --scope src` | Print CRAP hotspots from existing `coverage/lcov.info` | After `npm run coverage` when triaging risky functions/modules |
| `npm run browser:inspect -- --url <url> --target <label>` | One-off parity capture | Visual/runtime parity evidence and smoke checks |
| `npm run browser:compare` | One-off compare capture | Compare two targets (Hyperliquid vs local) |
| `npm run qa:pr-ui` | Run the fixed critical UI scenario bundle | Required merge-time validation for high-risk UI changes |
| `npm run qa:nightly-ui` | Run the broader nightly UI scenario bundle | Full desktop/mobile matrix and nightly reporting |
| `npm run browser:mcp` | Start Codex MCP tools host | When invoking via MCP instead of CLI |
| `npm run test:browser-inspection` | Browser-inspection unit tests | Before changing inspection tooling |
| `npm run test:browser-inspection:smoke` | Optional real-Chrome smoke | Manual confidence check before parity workflows |

## 2) Local Clojure navigation and analysis

| Command | Purpose | When to use |
| --- | --- | --- |
| `rg -n "<pattern>" src test` | Fast text search across repo source and tests | First-pass discovery, unknown symbol names, or broad grep-driven audits |
| `bb -m dev.check-delimiters --changed` | Reader-level syntax preflight for changed Clojure/CLJS/EDN files | Catch unmatched delimiters and EOF reader errors before expensive compiles/tests |
| `clojure-lsp diagnostics --project-root .` | Semantic diagnostics using project analysis | After edits or before handoff when you want unresolved-symbol, namespace, and lint feedback |
| `clojure-lsp references --project-root . --from <fqns> --raw` | Symbol-accurate reference lookup | You know the fully qualified symbol and need real references instead of text matches |
| `clojure-lsp rename --project-root . --from <old-fqns> --to <new-fqns> --dry` | Preview semantic rename patch without editing files | Rename planning, API moves, and safe scope checks before applying a rename |
| `tools/shadow-nrepl-port` or `npm run nrepl:port` | Resolve the active Shadow nREPL port for the current git worktree as JSON | Codex or local tooling needs the dev REPL that belongs to this worktree, not another worktree on the same machine |
| `clojure-lsp` via a persistent editor/LSP session | Warm semantic navigation for definition, references, and rename | Repeated symbol work where one-time analysis startup can be amortized across many lookups |

Recommended split:
- Start with `rg` when speed matters most or when you do not yet know the exact symbol you are chasing.
- Run `bb -m dev.check-delimiters --changed` after Clojure-family edits when you want a cheap reader-only preflight before `shadow-cljs` compile or test commands.
- Switch to `clojure-lsp` once you know the fully qualified symbol and correctness matters more than raw command latency.
- Use `tools/shadow-nrepl-port` instead of global `ps`/`lsof` scans when you need the live Shadow nREPL for this worktree. The helper resolves the current git top-level, reads `target/shadow-cljs/nrepl.port`, and verifies the port is still listening before returning JSON.
- Prefer a persistent `clojure-lsp` session for repeated navigation. One-shot CLI calls pay analysis startup cost each time.
- Treat standalone `clj-kondo` as optional. `clojure-lsp` uses `clj-kondo` analysis internally, but the separate `clj-kondo` binary is not guaranteed to be installed on `PATH` in every local environment.

Benchmark note:
- Local measurements on 2026-03-07 in this repository showed `rg` discovery runs around `0.03s` to `0.07s`, one-shot `clojure-lsp` CLI reference and rename dry-run commands around `15s` to `17s`, and warm in-session `textDocument/definition` requests around `0.024s` after an initial `16.6s` server startup. Treat these as order-of-magnitude guidance, not a performance contract.

Live local bug workflow selection:
- Start with browser inspection when the task is about a specific running tab or interaction: reproducing UI bugs, checking console errors, inspecting DOM/network state, or understanding what the user saw in the browser. Use the attach and tab-selection workflow from `/hyperopen/docs/runbooks/browser-live-inspection.md`.
- Browser attach requires a reachable CDP endpoint from a Chromium-family browser such as Chrome or Brave, typically started with `--remote-debugging-port=<port>`. The toolchain cannot retroactively attach to a normal browser session that was launched without remote debugging enabled.
- Use the worktree-scoped Shadow nREPL when the task requires evaluating ClojureScript, inspecting app runtime state, or calling functions inside the current local build.
- For bugs in a running local dev session, prefer browser attach first when a CDP endpoint is available and escalate to `tools/shadow-nrepl-port` or `npm run nrepl:port` only when browser evidence is insufficient.
- Never choose a Shadow nREPL by global process scan when multiple worktrees may be active; always resolve it from the current worktree.

## 3) Skills available to agents

| Skill | Purpose | Trigger |
| --- | --- | --- |
| `$CODEX_HOME/skills/pdf/SKILL.md` | PDF reading/creation checks | Ask about PDF processing workflows |
| `$CODEX_HOME/skills/.system/skill-installer/SKILL.md` | Install Codex skills | Need a new skill or curated skill list |
| `$CODEX_HOME/skills/.system/skill-creator/SKILL.md` | Create/update new Codex skills | Need to author a reusable skill |

If a task explicitly names one of these skills, follow that skill workflow first.

## 4) Shared command phrase lookup

- Machine-readable registry: `/hyperopen/command-phrases.edn`
- Lookup command: `/hyperopen/tools/phrase get [--suggest] [--accept-fuzzy] "<phrase>"`
- Registry schema: `:schema-version 2` with `:commands` and `:alias->id`.
- Lookup model: normalize input once (trim/collapse spaces/lowercase), then direct map lookups in `:alias->id` and `:commands`.
- `--suggest`: when no direct hit, print top fuzzy suggestions and exit non-zero.
- `--accept-fuzzy`: when no direct hit, accept the best fuzzy match only if it clears score/gap thresholds.

Example:
- `/hyperopen/tools/phrase get "land the worktree"`
- `/hyperopen/tools/phrase get "lan the worktree"`
- `/hyperopen/tools/phrase get --suggest "land the work tree"`
- `/hyperopen/tools/phrase get --accept-fuzzy "land the work tree"`

## 5) Issue tracking tool (`bd`)

`bd` is the canonical issue tracker for this repository.

Common commands:
- `bd ready --json`
- `bd update <id> --claim --json`
- `bd create "Issue title" --description="<details>" -t bug|feature|task|epic|chore -p 0-4 --json`
- `bd close <id> --reason "Completed" --json`
- `bd backup status --json`

For policy details, including markdown-vs-`bd` boundaries and session-completion workflow, follow `/hyperopen/docs/WORK_TRACKING.md`.

## 6) Browser Inspection tools (CLI)

All browser-inspection tooling lives under `/hyperopen/tools/browser-inspection/`.

### Start/end session and tab management

| CLI command | Example | Use this when |
| --- | --- | --- |
| `node tools/browser-inspection/src/cli.mjs session start` | `... session start --headed --manage-local-app` | You need a long-lived tool session |
| `node tools/browser-inspection/src/cli.mjs session attach --attach-port <port>` | `... session attach --attach-port 9222` | You already have a Chromium-family browser with remote debugging |
| `node tools/browser-inspection/src/cli.mjs session list` | `... session list` | You need active Codex/browser sessions |
| `node tools/browser-inspection/src/cli.mjs session targets --attach-port <port>` | `... session targets --attach-port 9222` | You need to pick a target tab |
| `node tools/browser-inspection/src/cli.mjs session stop --session-id <id>` | `... session stop --session-id abc` | You want to cleanly close a session |

### Active page operations

| CLI command | Example | Use this when |
| --- | --- | --- |
| `node tools/browser-inspection/src/cli.mjs navigate` | `... navigate --session-id abc --url https://app.hyperliquid.xyz/trade --viewport desktop` | Moving a session tab to a URL |
| `node tools/browser-inspection/src/cli.mjs eval` | `... eval --session-id abc --expression "({url: location.href})"` | Lightweight read-only DOM/runtime checks |
| `node tools/browser-inspection/src/cli.mjs preflight` | `... preflight --strict` | Validate local/attach prerequisites before expensive capture workflows |
| `node tools/browser-inspection/src/cli.mjs inspect` | `... inspect --url https://app.hyperliquid.xyz/trade --target hyperliquid` | One-off snapshot capture |
| `node tools/browser-inspection/src/cli.mjs compare` | `... compare --left-url ... --right-url ... --left-label ... --right-label ...` | Diff local vs reference across viewports |
| `node tools/browser-inspection/src/cli.mjs scenario list` | `... scenario list --tags critical,wallet` | Discover checked-in UI scenarios and tag bundles |
| `node tools/browser-inspection/src/cli.mjs scenario run` | `... scenario run --ids wallet-enable-trading-simulated` | Run one scenario or a tagged bundle through the scenario runner |

### Safe defaults and constraints

- Session commands are read-only by design.
- Snapshot output is written to `/hyperopen/tmp/browser-inspection/`.
- Scenario bundles classify each scenario as `pass`, `product-regression`, `automation-gap`, or `manual-exception`.
- Scenario captures prefer the compact dev-only `HYPEROPEN_DEBUG.qaSnapshot()` payload when available so artifacts stay bounded even on subscription-heavy routes.
- Nightly QA wrapper command is `npm run qa:nightly-ui`; each run writes a timestamped bundle under `/hyperopen/tmp/browser-inspection/nightly-ui-qa-*/` including `preflight.json`, `attempt-summary.tsv`, and `failure-classification.json`.
- Use explicit `--target-id` when attaching to avoid wrong tab capture.
- For tab-selection stability, follow `/hyperopen/docs/runbooks/browser-live-inspection.md` and use marker verification steps.
- Browser attach only works when the user or tool launched a compatible Chromium browser with a reachable CDP endpoint. Without that, use the worktree-scoped Shadow nREPL for runtime-state inspection instead of assuming tab-level browser access exists.
- For live local bug triage, prefer browser attach first when available. Reach for the worktree-scoped Shadow nREPL only after browser inspection stops being enough to explain the behavior.

## 7) Browser Inspection tools (Codex MCP)

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
| `browser_scenarios_list` | List checked-in scenario manifests by id or tag |
| `browser_scenarios_run` | Run one scenario or a tagged scenario bundle |

## 8) Where definitions live

- Scripted command surface: `/hyperopen/package.json`
- Worktree-scoped Shadow nREPL helper: `/hyperopen/tools/shadow-nrepl-port`
- UI workbench source: `/hyperopen/portfolio/hyperopen/workbench/`
- UI workbench scenes: `/hyperopen/portfolio/hyperopen/workbench/scenes/**`
- UI workbench shared helpers/builders: `/hyperopen/portfolio/hyperopen/workbench/support/**`
- Phrase registry: `/hyperopen/command-phrases.edn`
- Phrase lookup CLI: `/hyperopen/tools/phrase`
- MCP definitions: `/hyperopen/tools/browser-inspection/src/mcp_server.mjs`
- CLI definitions: `/hyperopen/tools/browser-inspection/src/cli.mjs`
- Runtime and config: `/hyperopen/tools/browser-inspection/src/service.mjs` and `/hyperopen/tools/browser-inspection/config/defaults.json`
- Operational runbook: `/hyperopen/docs/runbooks/browser-live-inspection.md`

UI workbench scene conventions:
- File names end with `_scenes.cljs`
- Namespaces end with `-scenes`
- Keep reusable scene-only data/layout helpers in `/hyperopen/portfolio/hyperopen/workbench/support/**`
- Keep workbench namespaces out of the normal production require path
