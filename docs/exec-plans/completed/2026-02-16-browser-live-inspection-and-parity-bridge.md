# Browser Live Inspection and Parity Bridge

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, Codex and developers can run live browser inspection sessions against a real Chrome instance, capture DevTools-style diagnostics, and run one command to compare Hyperliquid vs local Hyperopen with semantic and screenshot parity output. The result is faster debugging and repeatable UI parity analysis without manual-only workflows.

## Progress

- [x] (2026-02-16 16:55Z) Created browser inspection module tree under `/hyperopen/tools/browser-inspection/`.
- [x] (2026-02-16 17:00Z) Added config/contracts/artifact/session runtime modules and direct CDP client implementation.
- [x] (2026-02-16 17:04Z) Added capture pipeline (console/network/DOM/screenshot) and parity comparator with dynamic masking and visual diff output.
- [x] (2026-02-16 17:08Z) Added CLI and MCP server interfaces with tool and command coverage from the implementation plan.
- [x] (2026-02-16 17:12Z) Added additive `data-parity-id` anchors in targeted trade view files.
- [x] (2026-02-16 17:14Z) Added browser inspection tests and npm scripts.
- [x] (2026-02-16 17:23Z) Ran browser inspection test suite (`npm run test:browser-inspection`) with all non-smoke tests green.
- [x] (2026-02-16 17:25Z) Ran required repository validation gates (`npm run check`, `npm test`, `npm run test:websocket`) with all green.
- [x] (2026-02-16 17:26Z) Ran optional real-browser smoke (`npm run test:browser-inspection:smoke`) after trimming inspect output payload size.
- [x] (2026-02-16 17:26Z) Finalized docs updates and retrospective notes.

## Surprises & Discoveries

- Observation: The local machine already supports direct Node WebSocket access to Chrome CDP without extra transport libraries, which reduced implementation complexity.
  Evidence: Direct `WebSocket` calls against `http://127.0.0.1:<port>/json/version` `webSocketDebuggerUrl` worked in local feasibility checks.
- Observation: Existing views already expose `data-role` hooks in several areas, so parity anchors could be additive and minimal.
  Evidence: Multiple `:data-role` attrs existed in header/orderbook/chart/account views before this change.
- Observation: Returning full semantic capture payloads on CLI stdout can exceed Node child-process default `maxBuffer` during smoke tests.
  Evidence: smoke run initially failed with `ERR_CHILD_PROCESS_STDIO_MAXBUFFER` on `inspect`; resolved by returning summary pointers instead of full semantic blobs.
- Observation: Killing only the local-app shell pid can leave detached watcher children running.
  Evidence: `npm run dev` watch processes remained after compare teardown until process-group termination was added.

## Decision Log

- Decision: Use direct CDP + Chrome instead of Playwright/Puppeteer for v1.
  Rationale: Lower dependency surface while still enabling DevTools-grade data capture and persistent sessions.
  Date/Author: 2026-02-16 / Codex
- Decision: Keep MCP as primary interface and CLI as equivalent fallback.
  Rationale: Codex toolability requires MCP; CLI keeps manual/CI and local debugging workable.
  Date/Author: 2026-02-16 / Codex
- Decision: Default to read-only guardrails, headless mode, ephemeral profiles, and redaction.
  Rationale: Minimizes unintended side effects and sensitive data leakage in debugging artifacts.
  Date/Author: 2026-02-16 / Codex
- Decision: Keep semantic snapshots on disk artifacts but return summarized CLI payloads.
  Rationale: Preserves full data for analysis while keeping command output robust and testable.
  Date/Author: 2026-02-16 / Codex
- Decision: Stop managed local app via process-group termination.
  Rationale: Ensures no lingering `tailwindcss`/`shadow-cljs` watchers remain after compare/inspect flows that auto-start local dev runtime.
  Date/Author: 2026-02-16 / Codex

## Outcomes & Retrospective

The browser inspection subsystem is now implemented end-to-end with CDP session control, capture pipeline, parity compare engine, MCP server, CLI fallback, artifact retention, and additive UI parity anchors. Required repository gates passed (`npm run check`, `npm test`, `npm run test:websocket`), browser inspection tests passed, and optional smoke passed with a real Chrome session. The feature is operational for read-only debugging and Hyperliquid-vs-local parity review.

## Context and Orientation

The implementation lives in `/hyperopen/tools/browser-inspection/` and is intentionally isolated from application runtime logic. The app-side changes are additive selector anchors in view markup only (`data-parity-id`), with no action/reducer/effect behavior changes.

Primary integration points:

- `/hyperopen/tools/browser-inspection/src/cli.mjs` (manual + CI usage)
- `/hyperopen/tools/browser-inspection/src/mcp_server.mjs` (Codex MCP tools)
- `/hyperopen/tools/browser-inspection/src/service.mjs` (shared orchestration)
- `/hyperopen/src/hyperopen/views/**` (parity-id anchors)

## Plan of Work

Implement browser tooling in milestones: configuration/contracts, session runtime, capture pipeline, parity compare engine, interfaces (CLI + MCP), UI parity anchors, and docs/tests. Keep all side effects in tooling modules and preserve app runtime invariants.

## Concrete Steps

From `/hyperopen`:

1. Install dependencies: `npm install --save-dev @modelcontextprotocol/sdk zod pixelmatch pngjs`.
2. Run browser inspection tests: `npm run test:browser-inspection`.
3. Run required repository gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
4. Optional smoke test:
   - `npm run test:browser-inspection:smoke`

## Validation and Acceptance

Acceptance criteria:

- MCP server exposes session, navigate, eval, capture, compare, and list tools.
- CLI mirrors core workflows (`inspect`, `compare`, `session start/stop/list`).
- Compare run produces semantic and visual reports for desktop and mobile viewports.
- Artifacts and manifests are written under `/hyperopen/tmp/browser-inspection/` with retention pruning.
- Local app view changes are additive parity anchors only.

## Idempotence and Recovery

Tooling modules are additive and safe to rerun. Runs are isolated under timestamped directories and stale runs are pruned by retention policy. If a capture fails, the run manifest is marked failed and can be retried without repository cleanup.

## Artifacts and Notes

Planned changed paths include:

- `/hyperopen/tools/browser-inspection/src/*.mjs`
- `/hyperopen/tools/browser-inspection/config/defaults.json`
- `/hyperopen/tools/browser-inspection/test/*.test.mjs`
- `/hyperopen/src/hyperopen/views/**` (selected files only, additive attrs)
- `/hyperopen/docs/runbooks/browser-live-inspection.md`
- `/hyperopen/docs/references/toolchain.md`
- `/hyperopen/docs/product-specs/phase1-trade-parity-notes.md`

## Interfaces and Dependencies

New dependencies:

- `@modelcontextprotocol/sdk` for MCP server transport and tool registration.
- `zod` for MCP input schemas.
- `pngjs` and `pixelmatch` for visual diff generation.

Primary interfaces added:

- CLI commands in `/hyperopen/tools/browser-inspection/src/cli.mjs`
- MCP tools in `/hyperopen/tools/browser-inspection/src/mcp_server.mjs`
- Shared service layer in `/hyperopen/tools/browser-inspection/src/service.mjs`

Plan revision note: 2026-02-16 17:14Z - Initial plan record created and populated with implemented milestones and pending validation steps.
Plan revision note: 2026-02-16 17:26Z - Updated progress, discoveries, decisions, and outcomes after all validation and smoke gates passed.
