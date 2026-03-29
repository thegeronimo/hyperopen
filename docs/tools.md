---
owner: platform
status: canonical
last_reviewed: 2026-03-22
review_cycle_days: 90
source_of_truth: true
---

# Hyperopen Agent Tooling Reference

Use this file as the single starting point for what actions this repo provides to agents.

## Quick map

1. For a quick list of build/test commands, start with this section in `/hyperopen/docs/references/toolchain.md`.
2. For local Clojure discovery, semantic references, renames, diagnostics, and current-worktree Shadow nREPL lookup, use the Local Clojure Navigation and Analysis section below.
3. For shared command phrase lookup, use `/hyperopen/tools/phrase get "<phrase>"` and `/hyperopen/command-phrases.edn`.
4. For browser tool selection and exact Playwright versus Browser MCP routing, use `/hyperopen/docs/BROWSER_TESTING.md`.
5. For browser parity/debug workflows, use the Browser Inspection section below.
6. For CRAP hotspot analysis after generating coverage, use `bb tools/crap_report.clj --scope src` or `bb tools/crap_report.clj --module <path> --format json`.
7. For repo-local mutation testing on one covered module at a time, use `bb tools/mutate.clj --scan --module <path>` after `npm run coverage`.
8. For a checked-in overnight hotspot sweep, use `bb tools/mutate_nightly.clj` with targets from `/hyperopen/tools/mutate/nightly_targets.edn`.
9. For interactive feature, bug, and UI orchestration, invoke `$feature-flow`, `$bug-flow`, or `$ui-flow` explicitly.
10. For multi-agent role, artifact, and gate rules, use `/hyperopen/docs/MULTI_AGENT.md` and the manager under `/hyperopen/tools/multi-agent/`.
11. For issue tracking and session handoff rules, use `/hyperopen/docs/WORK_TRACKING.md`.
12. For Lean-backed formal-tool commands, use `npm run formal:verify -- --surface <vault-transfer|order-request-standard|order-request-advanced|effect-order-contract|trading-submit-policy|order-form-ownership>` and `npm run formal:sync -- --surface <surface>`.
13. For websocket TLA+ model-checking, use `npm run tla:verify -- --spec websocket-runtime` for the bounded safety pass and `npm run tla:verify -- --spec websocket-runtime-liveness` for the focused liveness pass.
14. For exact browser inspection command syntax, see:
   - `/hyperopen/tools/browser-inspection/src/cli.mjs`
   - `/hyperopen/tools/browser-inspection/src/mcp_server.mjs`

## 1) Core build/test commands (`package.json` scripts)

| Command | Purpose | When to use |
| --- | --- | --- |
| `npm run check` | Lint, docs checks, and compile app/test builds | Before finishing code changes |
| `npm test` | Full test suite | Regular validation and regression confidence |
| `npm run test:playwright:install` | Install Chromium for the Playwright suite | First-time local browser-test setup |
| `npm run test:playwright:smoke` | Run the quick committed Playwright smoke suite | Fast local browser regression check before broader gates |
| `npm run test:playwright:headed` | Run Playwright headed with one worker | Local browser-flow debugging with the committed suite |
| `npm run test:playwright:ci` | Run the full committed Playwright suite in CI mode | CI-safe browser regression coverage or a full local browser pass |
| `npm run test:crap` | Fast Babashka tests for CRAP-tool parsing and report math | Before changing the CRAP analyzer/reporter |
| `npm run test:mutation` | Fast Babashka tests for the mutation tool | Before changing `/hyperopen/tools/mutate/**` |
| `npm run test:formal-tooling` | Fast Babashka tests for the formal wrapper and generated-source checks | Before changing `/hyperopen/tools/formal/**` |
| `npm run test:tla-tooling` | Fast Babashka tests for the websocket TLA+ wrapper and TLC output-root guarantees | Before changing `/hyperopen/tools/tla.clj` or the websocket TLA wrapper contract |
| `npm run lint:delimiters -- --changed` | Fast Clojure/CLJS reader preflight on changed files | Before `npm test`, `npm run test:websocket`, or manual `shadow-cljs` compiles after syntax-heavy edits |
| `npm run test:websocket` | Websocket-only suite | Websocket runtime/API changes |
| `npm run mutate:nightly` | Rebuild coverage, run the configured nightly mutation sweep, and write aggregate summaries under `target/mutation/nightly/**` | Overnight hotspot sweeps or local batch mutation audits |
| `npm run dev` | Watch app and portfolio worker builds alongside Tailwind | Normal frontend development |
| `npm run dev:portfolio` | Watch app, Portfolio workbench, and portfolio worker builds alongside Tailwind | When you need the main app and workbench together |
| `npm run portfolio` | Watch only the Portfolio workbench plus Tailwind | Isolated component workbench iteration |
| `npm run portfolio:watch` | Watch the dedicated Shadow `:portfolio` target | Workbench-only CLJS compile loop |
| `npm run test:runner:generate` | Regenerate test runner list | Usually after adding/removing test namespaces |
| `npm run agent:dry-run -- --issue <bd-id>` | Produce a synthetic multi-agent artifact bundle without model execution | Validate manager wiring, artifact paths, and phase ordering locally |
| `npm run agent:ticket -- --issue <bd-id>` | Run the repo-local multi-agent workflow for one ticket | Local manager-style orchestration for complex tickets |
| `npm run agent:resume-ticket -- --issue <bd-id>` | Resume a prior multi-agent ticket run from existing artifacts | Continue after an interrupted local run |
| `npm run test:multi-agent` | Multi-agent manager unit tests | Before changing `/hyperopen/tools/multi-agent/**`, role config, or artifact contracts |
| `bb tools/crap_report.clj --scope src` | Print CRAP hotspots from existing `coverage/lcov.info` | After `npm run coverage` when triaging risky functions/modules |
| `bb tools/mutate.clj --scan --module src/hyperopen/...` | Report mutation counts and coverage-aware eligible sites for one module | After `npm run coverage` when validating a hotspot module or pure policy seam |
| `bb tools/mutate.clj --module src/hyperopen/...` | Run mutation testing for one module and write artifacts under `target/mutation/**` | Local confidence pass on a covered module after targeted refactors or bug fixes |
| `bb tools/mutate_nightly.clj` | Run the checked-in nightly mutation target list serially and aggregate markdown/JSON summaries | Overnight mutation sweeps, regression spotting, or local ranking of weak modules |
| `npm run formal:verify -- --surface vault-transfer` | Build the Lean workspace, check the selected formal surface manifest, and for modeled surfaces confirm the checked-in generated source is current | After changing formal tooling, proof models, or generated formal vectors |
| `npm run formal:sync -- --surface vault-transfer` | Regenerate the deterministic formal manifest and, for modeled surfaces, refresh the checked-in generated vector namespace | When you need to refresh manifests or generated formal vectors after a proof/model update |
| `npm run tla:verify -- --spec websocket-runtime` | Run TLC against the bounded websocket runtime safety model and write transient artifacts under `/hyperopen/target/tla/**` | After changing `/hyperopen/spec/tla/**`, websocket TLA tooling, or reducer invariants mirrored in the safety model |
| `npm run tla:verify -- --spec websocket-runtime-liveness` | Run TLC against the focused websocket runtime liveness model/config and write transient artifacts under `/hyperopen/target/tla/**` | After changing websocket reconnect or market-flush eventuality assumptions encoded in the TLA+ model |
| `npm run browser:inspect -- --url <url> --target <label>` | One-off parity capture | Visual/runtime parity evidence and smoke checks |
| `npm run browser:compare` | One-off compare capture | Compare two targets (Hyperliquid vs local) |
| `npm run qa:design-ui` | Run the design-system browser QA contract | Required evidence-backed conformance review for UI-facing changes |
| `npm run qa:pr-ui` | Run the fixed critical UI scenario bundle | Required merge-time validation for high-risk UI changes |
| `npm run qa:nightly-ui` | Run the broader nightly UI scenario bundle | Full desktop/mobile matrix and nightly reporting |
| `npm run browser:mcp` | Start Codex MCP tools host | When invoking via MCP instead of CLI |
| `npm run test:browser-qa-evals` | Run the design-review eval corpus and narrow graders | Before changing design-review report rules or browser-QA prompts |
| `npm run test:browser-inspection` | Browser-inspection unit tests | Before changing inspection tooling |
| `npm run test:browser-inspection:smoke` | Optional real-Chrome smoke | Manual confidence check before parity workflows |

## 10) Formal tooling

Use `tools/formal.clj` for the repo-local Lean workflow. The wrapper stays out of the proof-execution path in normal repo commands, but the wrapper's own Babashka tests run in `npm run check`.

Supported surfaces:

- `vault-transfer`
- `order-request-standard`
- `order-request-advanced`
- `effect-order-contract`
- `trading-submit-policy`
- `order-form-ownership`

Current surface state:

- `vault-transfer`: modeled; emits transient generated source under `/hyperopen/target/formal/` and syncs the checked-in bridge under `/hyperopen/test/hyperopen/formal/vault_transfer_vectors.cljs`
- `order-request-standard`: modeled; emits transient generated source under `/hyperopen/target/formal/` and syncs the checked-in bridge under `/hyperopen/test/hyperopen/formal/order_request_standard_vectors.cljs`
- `order-request-advanced`: modeled; emits transient generated source under `/hyperopen/target/formal/` and syncs the checked-in bridge under `/hyperopen/test/hyperopen/formal/order_request_advanced_vectors.cljs`
- `effect-order-contract`: modeled; emits transient generated source under `/hyperopen/target/formal/` and syncs the checked-in bridge under `/hyperopen/test/hyperopen/formal/effect_order_contract_vectors.cljs`
- `trading-submit-policy`: modeled; emits transient generated source under `/hyperopen/target/formal/` and syncs the checked-in bridge under `/hyperopen/test/hyperopen/formal/trading_submit_policy_vectors.cljs`
- `order-form-ownership`: modeled; emits transient generated source under `/hyperopen/target/formal/` and syncs the checked-in bridge under `/hyperopen/test/hyperopen/formal/order_form_ownership_vectors.cljs`

The generated manifests live under `/hyperopen/tools/formal/generated/`, transient generated source lives under `/hyperopen/target/formal/`, and the Lean workspace lives under `/hyperopen/spec/lean/`.

The websocket runtime TLA+ track is separate on purpose. Use `tools/tla.clj`, `npm run tla:verify -- --spec websocket-runtime`, and `npm run tla:verify -- --spec websocket-runtime-liveness` for TLC runs against `/hyperopen/spec/tla/websocket_runtime.tla`. The wrapper looks for `TLA2TOOLS_JAR` first and `/hyperopen/tools/tla/vendor/tla2tools.jar` second, and it writes TLC artifacts only under `/hyperopen/target/tla/**`.

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
| `/hyperopen/.agents/skills/feature-flow/SKILL.md` | Explicit workflow for complex features and refactors | Invoke explicitly with `$feature-flow` |
| `/hyperopen/.agents/skills/bug-flow/SKILL.md` | Explicit workflow for diagnosis-first bug work | Invoke explicitly with `$bug-flow` |
| `/hyperopen/.agents/skills/ui-flow/SKILL.md` | Explicit workflow for governed UI work | Invoke explicitly with `$ui-flow` |
| `/hyperopen/.agents/skills/playwright-e2e/SKILL.md` | Playwright routing for committed deterministic browser coverage | Use when browser work should land as repeatable tests or CI coverage |
| `/hyperopen/.agents/skills/browser-mcp-explore/SKILL.md` | Browser MCP routing for exploratory browser work | Use when the work is exploratory, live-session, or parity/design-review oriented |
| `/hyperopen/.agents/skills/spec-writer/SKILL.md` | ExecPlan-first spec writing for multi-agent tickets | Use when acting as `spec_writer` |
| `/hyperopen/.agents/skills/acceptance-tests/SKILL.md` | Acceptance/integration proposal workflow | Use when acting as `acceptance_test_writer` |
| `/hyperopen/.agents/skills/edge-case-tests/SKILL.md` | Boundary-case and invariant proposal workflow | Use when acting as `edge_case_test_writer` |
| `/hyperopen/.agents/skills/static-review/SKILL.md` | Findings-first read-only review workflow | Use when acting as `reviewer` |
| `/hyperopen/.agents/skills/browser-qa/SKILL.md` | Browser QA workflow on top of browser-inspection and governed passes | Use when acting as `browser_debugger` |
| `$CODEX_HOME/skills/pdf/SKILL.md` | PDF reading/creation checks | Ask about PDF processing workflows |
| `$CODEX_HOME/skills/.system/skill-installer/SKILL.md` | Install Codex skills | Need a new skill or curated skill list |
| `$CODEX_HOME/skills/.system/skill-creator/SKILL.md` | Create/update new Codex skills | Need to author a reusable skill |

If a task explicitly names one of these skills, follow that skill workflow first. The workflow skills are explicit-only by policy and do not trigger ambiently.

## 4) Multi-Agent Manager

All repo-local multi-agent orchestration lives under `/hyperopen/tools/multi-agent/`.

Commands:

- `node tools/multi-agent/src/cli.mjs dry-run --issue <bd-id>`
- `node tools/multi-agent/src/cli.mjs ticket --issue <bd-id>`
- `node tools/multi-agent/src/cli.mjs resume-ticket --issue <bd-id>`

Key rules:

- Use a `bd` issue id as the workflow key.
- Artifacts are written under `/hyperopen/tmp/multi-agent/<bd-id>/`.
- Native Codex project config lives under `/hyperopen/.codex/config.toml`.
- Repo-local checked-in agent definitions live under `/hyperopen/.codex/agents/*.toml`.
- Real runs require `OPENAI_API_KEY` and a local `codex` CLI.
- Browser QA still depends on the existing browser-inspection MCP server and contracts.

## 5) Shared command phrase lookup

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

## 6) Issue tracking tool (`bd`)

`bd` is the canonical issue tracker for this repository.

Common commands:
- `bd ready --json`
- `bd update <id> --claim --json`
- `bd create "Issue title" --description="<details>" -t bug|feature|task|epic|chore -p 0-4 --json`
- `bd close <id> --reason "Completed" --json`
- `bd backup status --json`

For policy details, including markdown-vs-`bd` boundaries and session-completion workflow, follow `/hyperopen/docs/WORK_TRACKING.md`.

## 7) Browser Inspection tools (CLI)

All browser-inspection tooling lives under `/hyperopen/tools/browser-inspection/`.

Playwright owns committed deterministic browser assertions and CI-safe regression coverage. Browser inspection remains the exploratory, attach, parity-compare, and design-review tool. Start with `/hyperopen/docs/BROWSER_TESTING.md` when you need to choose between them.

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
| `node tools/browser-inspection/src/cli.mjs design-review` | `... design-review --changed-files src/styles/main.css` | Run the six-pass design-system review and write `summary.json` / `summary.md` |
| `node tools/browser-inspection/src/cli.mjs scenario list` | `... scenario list --tags critical,wallet` | Discover checked-in UI scenarios and tag bundles |
| `node tools/browser-inspection/src/cli.mjs scenario run` | `... scenario run --ids wallet-enable-trading-simulated` | Run one scenario or a tagged bundle through the scenario runner |

### Safe defaults and constraints

- Session commands are read-only by design.
- Snapshot output is written to `/hyperopen/tmp/browser-inspection/`.
- Scenario bundles classify each scenario as `pass`, `product-regression`, `automation-gap`, or `manual-exception`.
- Design-review runs classify each named pass as `PASS`, `FAIL`, or `BLOCKED`, then aggregate an overall `PASS`, `FAIL`, or `BLOCKED` state.
- Design-review artifacts are written under `/hyperopen/tmp/browser-inspection/design-review-*/` and include `review-spec.json`, `summary.json`, `summary.md`, per-pass JSON, screenshots, DOM/style captures, and interaction traces.
- Scenario captures prefer the compact dev-only `HYPEROPEN_DEBUG.qaSnapshot()` payload when available so artifacts stay bounded even on subscription-heavy routes.
- Nightly QA wrapper command is `npm run qa:nightly-ui`; each run writes a timestamped bundle under `/hyperopen/tmp/browser-inspection/nightly-ui-qa-*/` including `preflight.json`, `attempt-summary.tsv`, and `failure-classification.json`.
- Missing references or unavailable probes are `BLOCKED` design-review outcomes, not manual exceptions. Manual exceptions stay limited to real extension, hardware-wallet, browser-permission, and third-party provider UI.
- Use explicit `--target-id` when attaching to avoid wrong tab capture.
- For tab-selection stability, follow `/hyperopen/docs/runbooks/browser-live-inspection.md` and use marker verification steps.
- Browser attach only works when the user or tool launched a compatible Chromium browser with a reachable CDP endpoint. Without that, use the worktree-scoped Shadow nREPL for runtime-state inspection instead of assuming tab-level browser access exists.
- For live local bug triage, prefer browser attach first when available. Reach for the worktree-scoped Shadow nREPL only after browser inspection stops being enough to explain the behavior.

## 8) Browser Inspection tools (Codex MCP)

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
| `browser_design_review` | Run the six-pass design-system review and persist artifacts |
| `browser_get_computed_style` | Inspect computed styles for selector matches |
| `browser_list_native_controls` | Enumerate visible native controls and allowlist mismatches |
| `browser_get_bounding_boxes` | Capture selector bounding boxes and identity metadata |
| `browser_focus_walk` | Walk keyboard focus order and visible-focus coverage |
| `browser_trace_interaction` | Sample layout-shift and long-task metrics for repeated interactions |
| `browser_capture_snapshot` | Capture snapshot artifacts for a target |
| `browser_compare_targets` | Capture+compare two URLs/targets |
| `browser_scenarios_list` | List checked-in scenario manifests by id or tag |
| `browser_scenarios_run` | Run one scenario or a tagged scenario bundle |

## 9) Where definitions live

- Scripted command surface: `/hyperopen/package.json`
- Browser-testing routing doc: `/hyperopen/docs/BROWSER_TESTING.md`
- Playwright config: `/hyperopen/playwright.config.mjs`
- Playwright helpers and tests: `/hyperopen/tools/playwright/**`
- Multi-agent manager: `/hyperopen/tools/multi-agent/src/cli.mjs`
- Multi-agent policy: `/hyperopen/docs/MULTI_AGENT.md`
- Repo-local Codex project config: `/hyperopen/.codex/config.toml`
- Repo-local checked-in agent files: `/hyperopen/.codex/agents/*.toml`
- Repo-local multi-agent skills: `/hyperopen/.agents/skills/**`
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
