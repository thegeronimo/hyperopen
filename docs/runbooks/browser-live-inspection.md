# Browser Live Inspection and Parity Runbook

## Purpose

This runbook documents the browser inspection subsystem under `/hyperopen/tools/browser-inspection/`.
It enables live Chrome control for debugging and one-command parity comparison between Hyperliquid and local Hyperopen.

## Defaults

- Local target: `http://localhost:8080/trade`
- Remote target: `https://app.hyperliquid.xyz/trade`
- Browser: macOS Chrome path `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`
- Mode: headless, read-only, ephemeral profile
- Viewports: desktop and mobile
- Artifact root: `/hyperopen/tmp/browser-inspection/`

## Commands

Run from `/hyperopen`:

- Start MCP server:
  - `npm run browser:mcp`
- One-off inspect capture:
  - `npm run browser:inspect -- --url https://app.hyperliquid.xyz/trade --target hyperliquid`
- One-off parity compare:
  - `npm run browser:compare`
- List checked-in scenarios:
  - `node tools/browser-inspection/src/cli.mjs scenario list --tags critical`
- Run one scenario or tag bundle:
  - `node tools/browser-inspection/src/cli.mjs scenario run --ids wallet-enable-trading-simulated`
- Run the PR-critical UI bundle:
  - `npm run qa:pr-ui`
- Preflight checks before local or attach workflows:
  - `npm run browser:preflight`
- Standard nightly UI QA wrapper:
  - `npm run qa:nightly-ui -- --allow-non-main`
- List active sessions:
  - `node tools/browser-inspection/src/cli.mjs session list`
- Start a persistent session:
  - `node tools/browser-inspection/src/cli.mjs session start`
- List page targets for a Chrome endpoint:
  - `node tools/browser-inspection/src/cli.mjs session targets --attach-port 9222`
- Attach to an existing Chrome DevTools endpoint:
  - `node tools/browser-inspection/src/cli.mjs session attach --attach-port 9222`
- Attach to a specific existing tab:
  - `node tools/browser-inspection/src/cli.mjs session attach --attach-port 9222 --target-id <target-id>`
- Stop a session:
  - `node tools/browser-inspection/src/cli.mjs session stop --session-id <id>`

## Codex MCP Registration

Register once in Codex:

- `codex mcp add hyperopen-browser -- node ./tools/browser-inspection/src/mcp_server.mjs`

Then restart Codex and verify:

- `codex mcp list`

## Artifact Layout

Each run creates `/hyperopen/tmp/browser-inspection/<run-id>/` with:

- `manifest.json` (run metadata and artifact index)
- `<target>/<viewport>/snapshot.json`
- `<target>/<viewport>/screenshot.png`
- `<viewport>-report.json` (compare runs)
- `<viewport>-report.md` (compare runs)
- `<viewport>-visual-diff.png` (compare runs)
- `scenarios/*.json` and `scenarios/*.md` (scenario-bundle runs)

Nightly wrapper runs additionally create `/hyperopen/tmp/browser-inspection/nightly-ui-qa-<timestamp>/` with:

- `run-meta.json`
- `summary.json`
- `preflight.json`
- `attempt-summary.tsv`
- `failure-classification.json`
- per-scenario `scenarios/*.json` and `scenarios/*.md`

## Safety and Redaction

Redaction is enabled by default for:

- Authorization and cookie headers
- signature/token/secret-like keys
- hex wallet-like values and JWT-like strings

Read-only guardrails block:

- Restricted URL schemes (for example `chrome:` and `javascript:`)
- Known mutating eval patterns unless explicitly overridden

Scenario captures prefer `HYPEROPEN_DEBUG.qaSnapshot()` when the debug bridge exposes it. That keeps scenario artifacts bounded while preserving the full `HYPEROPEN_DEBUG.snapshot()` object for manual console debugging.

If the nightly or PR bundle reports `manual-exception`, follow `/hyperopen/docs/qa/agent-first-ui-manual-exceptions.md` instead of inventing an ad hoc manual matrix.

## Full Compare Workflow

1. Ensure local app is running (or pass `--manage-local-app`).
2. Run:
   - `npm run browser:compare -- --manage-local-app`
3. Open the generated report:
  - `/hyperopen/tmp/browser-inspection/<run-id>/desktop-report.md`
  - `/hyperopen/tmp/browser-inspection/<run-id>/mobile-report.md`

## Nightly UI QA Workflow

1. Run deterministic preflight:
   - `npm run browser:preflight`
2. Run nightly matrix:
   - `npm run qa:nightly-ui -- --allow-non-main`
3. Read outputs:
   - `/hyperopen/tmp/browser-inspection/nightly-ui-qa-<timestamp>/failure-classification.json`
   - `/hyperopen/docs/qa/nightly-ui-report-<YYYY-MM-DD>.md`
4. If local bind is blocked, rerun with attach fallback:
   - `npm run qa:nightly-ui -- --attach-port 9222 --target-id <target-id>`

## PR UI QA Workflow

1. Run the critical bundle:
   - `npm run qa:pr-ui`
2. Inspect the bundle summary:
   - `/hyperopen/tmp/browser-inspection/pr-ui-<timestamp>/summary.json`
3. Drill into a failing scenario when needed:
   - `/hyperopen/tmp/browser-inspection/pr-ui-<timestamp>/scenarios/<scenario>-<viewport>.md`

## Optional Smoke Test

- `npm run test:browser-inspection:smoke`

This is opt-in and uses real Chrome.

## Attach to Your Own Browser

1. Start a visible Chrome window with remote debugging enabled:
   - `open -na "Google Chrome" --args --remote-debugging-port=9222 --user-data-dir=/tmp/hyperopen-cdp-visible --new-window http://localhost:8080/trade`
2. Confirm the endpoint is not headless:
   - `curl -s http://127.0.0.1:9222/json/version`
   - Ensure `User-Agent` does not contain `HeadlessChrome`.
3. In the exact tab you want to inspect, set a temporary marker in DevTools Console:
   - `window.__codex_marker = "my-live-tab"; document.title = document.title + " [my-live-tab]";`
4. List available tabs and choose the one with the marker in `title`:
   - `node tools/browser-inspection/src/cli.mjs session targets --attach-port 9222`
5. Start an attached session pinned to that tab:
   - `node tools/browser-inspection/src/cli.mjs session attach --attach-port 9222 --target-id <target-id>`
6. Verify target identity before capture:
   - `node tools/browser-inspection/src/cli.mjs eval --session-id <id> --expression "({href: location.href, title: document.title, marker: window.__codex_marker})"`
7. Reuse the returned `sessionId` for `navigate`, `eval`, `inspect`, or `compare`.
8. Stop only the tool session when done:
   - `node tools/browser-inspection/src/cli.mjs session stop --session-id <id>`

Attached mode does not terminate your Chrome process.

## Deterministic Tab Identification

When multiple targets are returned, use this checklist:

1. Always attach with explicit `--target-id`.
2. Confirm all of the following before capture:
   - `url` is the expected app URL.
   - `title` includes your temporary marker.
   - `eval` returns the same marker from `window.__codex_marker`.
3. If any value does not match:
   - Stop the session.
   - Re-run `session targets`.
   - Re-attach with the corrected `target-id`.

## Troubleshooting

- Wrong session or wrong tab:
  - Run `curl -s http://127.0.0.1:9222/json/version`.
  - If `User-Agent` contains `HeadlessChrome`, you are connected to a non-visible Chrome process.
  - Restart with the visible launch command above and reselect target by marker.
- Chrome not found:
  - Export custom path via env variable configured in defaults (`BROWSER_INSPECTION_CHROME_PATH`).
- Local app not reachable:
  - Start app manually with `npm run dev` or pass `--manage-local-app`.
- Session not found:
  - Check `session list`; stale sessions are pruned automatically if browser pid is no longer alive.
