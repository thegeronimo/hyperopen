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
- List active sessions:
  - `node tools/browser-inspection/src/cli.mjs session list`
- Start a persistent session:
  - `node tools/browser-inspection/src/cli.mjs session start`
- Attach to an existing Chrome DevTools endpoint:
  - `node tools/browser-inspection/src/cli.mjs session attach --attach-port 9222`
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

## Safety and Redaction

Redaction is enabled by default for:

- Authorization and cookie headers
- signature/token/secret-like keys
- hex wallet-like values and JWT-like strings

Read-only guardrails block:

- Restricted URL schemes (for example `chrome:` and `javascript:`)
- Known mutating eval patterns unless explicitly overridden

## Full Compare Workflow

1. Ensure local app is running (or pass `--manage-local-app`).
2. Run:
   - `npm run browser:compare -- --manage-local-app`
3. Open the generated report:
   - `/hyperopen/tmp/browser-inspection/<run-id>/desktop-report.md`
   - `/hyperopen/tmp/browser-inspection/<run-id>/mobile-report.md`

## Optional Smoke Test

- `npm run test:browser-inspection:smoke`

This is opt-in and uses real Chrome.

## Attach to Your Own Browser

1. Start Chrome with remote debugging enabled:
   - `/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --remote-debugging-port=9222 --user-data-dir=/tmp/hyperopen-cdp`
2. Start an attached session:
   - `node tools/browser-inspection/src/cli.mjs session attach --attach-port 9222`
3. Reuse the returned `sessionId` for `navigate`, `eval`, `inspect`, or `compare`.
4. Stop only the tool session when done:
   - `node tools/browser-inspection/src/cli.mjs session stop --session-id <id>`

Attached mode does not terminate your Chrome process.

## Troubleshooting

- Chrome not found:
  - Export custom path via env variable configured in defaults (`BROWSER_INSPECTION_CHROME_PATH`).
- Local app not reachable:
  - Start app manually with `npm run dev` or pass `--manage-local-app`.
- Session not found:
  - Check `session list`; stale sessions are pruned automatically if browser pid is no longer alive.
