# Browser Live Inspection and Parity Runbook

## Purpose

This runbook documents the browser inspection subsystem under `/hyperopen/tools/browser-inspection/`.
It enables live Chromium-browser control through Chrome DevTools Protocol (CDP) for debugging and one-command parity comparison between Hyperliquid and local Hyperopen.

## Defaults

- Local target: `http://localhost:8080/trade`
- Remote target: `https://app.hyperliquid.xyz/trade`
- Default launched browser: macOS Chrome path `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`
- Mode: headless, read-only, ephemeral profile
- Viewports: desktop and mobile
- Design-review viewports: `review-375`, `review-768`, `review-1280`, and `review-1440`
- Artifact root: `/hyperopen/tmp/browser-inspection/`

## Commands

Run from `/hyperopen`:

- Start MCP server:
  - `npm run browser:mcp`
- One-off inspect capture:
  - `npm run browser:inspect -- --url https://app.hyperliquid.xyz/trade --target hyperliquid`
- One-off parity compare:
  - `npm run browser:compare`
- Run the design-system review:
  - `npm run qa:design-ui -- --changed-files src/styles/main.css`
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
- List page targets for a Chromium CDP endpoint:
  - `node tools/browser-inspection/src/cli.mjs session targets --attach-port 9222`
- Attach to an existing Chromium CDP endpoint:
  - `node tools/browser-inspection/src/cli.mjs session attach --attach-port 9222`
- Attach to a specific existing tab:
  - `node tools/browser-inspection/src/cli.mjs session attach --attach-port 9222 --target-id <target-id>`
- Stop a session:
  - `node tools/browser-inspection/src/cli.mjs session stop --session-id <id>`
- Stop every tracked session:
  - `npm run browser:cleanup`

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
- `review-spec.json` (design-review runs)
- `summary.json` and `summary.md` (design-review runs)
- `passes/*.json` (design-review runs)
- `<target>/<viewport>/probes/*.json` (design-review runs)
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
If a design-review pass cannot complete because tooling or references are missing, mark that pass `BLOCKED`; do not reclassify it as a manual exception.

## Cleanup Contract

- One-off `inspect`, `compare`, `scenario run`, and `design-review` commands already clean up their temporary launched sessions in `finally`.
- Persistent `session start` and `session attach` workflows must be closed explicitly before you conclude the task.
- Use `npm run browser:cleanup` as the default end-of-task cleanup step when you used Browser MCP or browser-inspection sessions.
- `session stop` gracefully shuts down launched Chrome sessions and closes tool-created tabs on attached sessions without killing the user's own browser.

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

## Design Review Workflow

1. Run the design-system review:
   - `npm run qa:design-ui -- --changed-files <comma-separated-paths>`
2. Inspect the design-review summary:
   - `/hyperopen/tmp/browser-inspection/design-review-<timestamp>/summary.json`
3. Confirm every pass is explicitly marked `PASS`, `FAIL`, or `BLOCKED`:
   - `visual`
   - `native-control`
   - `styling-consistency`
   - `interaction`
   - `layout-regression`
   - `jank-perf`
4. If visual or styling references are missing, keep the affected pass `BLOCKED` and record the exact missing reference.
5. Do not conclude “looks good” unless every pass is explicitly accounted for.

## Desktop Trade Shell Regression Workflow

Use this flow when work can affect the desktop `/trade` layout, including chart sizing, order book sizing, order-ticket layout, the lower account tables, or app-shell spacing.

1. Run route smoke first:
   - `node tools/browser-inspection/src/cli.mjs scenario run --ids trade-route-smoke --manage-local-app`
2. Run the governed trade-route design review:
   - `npm run qa:design-ui -- --targets trade-route --manage-local-app`
3. Capture a desktop parity reference when the task is about proportions or shell geometry:
   - `npm run browser:compare -- --left-url http://localhost:8080/trade --right-url https://app.hyperliquid.xyz/trade --viewports desktop`
4. Inspect the live desktop shell with measured rects for:
   - chart panel
   - order-book panel
   - lower account panel
5. Switch these seven tabs in the live app and confirm the outer account-panel box stays stable:
   - `Balances`
   - `Positions`
   - `Open Orders`
   - `TWAP`
   - `Trade History`
   - `Funding History`
   - `Order History`
6. Treat the review as failed if any of the following occur:
   - the lower account panel changes outer height or width when switching those tabs
   - the chart bottom edge is no longer flush with the top edge of the lower account panel
   - the order-book bottom edge is no longer flush with the top edge of the lower account panel
   - a blank band appears under the chart inside the top trading row
   - the lower account panel collapses toward content height instead of preserving the governed desktop shell proportion
7. Save the artifact paths or measured rects in the final QA result so future regressions have a concrete baseline.

For direct geometry probes in an attached or launched session, use:

- `node tools/browser-inspection/src/cli.mjs eval --session-id <id> --expression "(() => { const rect = (sel) => { const el = document.querySelector(sel); if (!el) return null; const r = el.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,bottom:r.bottom,right:r.right}; }; return { chart: rect('[data-parity-id=\"trade-chart-panel\"]'), orderbook: rect('[data-parity-id=\"trade-orderbook-panel\"]'), account: rect('[data-parity-id=\"trade-account-tables-panel\"]') }; })()"`

## Optional Smoke Test

- `npm run test:browser-inspection:smoke`

This is opt-in and uses real Chrome.

## Attach Requirements

- Attach mode works with browsers that expose Chrome DevTools Protocol, including Chromium-family browsers such as Google Chrome and Brave.
- The target browser must be started with a reachable remote debugging endpoint, typically `--remote-debugging-port=<port>`.
- A normal browser session that was launched without remote debugging cannot be attached retroactively by this toolchain. Relaunch it with remote debugging enabled first.
- Prefer a dedicated `--user-data-dir` for attach workflows to avoid profile lock conflicts and to keep the debug session isolated.

## Attach to Your Own Browser

1. Start a visible Chromium-family browser window with remote debugging enabled. Example with Chrome:
   - `open -na "Google Chrome" --args --remote-debugging-port=9222 --user-data-dir=/tmp/hyperopen-cdp-visible --new-window http://localhost:8080/trade`
2. Example with Brave:
   - `open -na "Brave Browser" --args --remote-debugging-port=9222 --user-data-dir=/tmp/hyperopen-cdp-brave --new-window http://localhost:8080/trade`
3. Confirm the endpoint is not headless:
   - `curl -s http://127.0.0.1:9222/json/version`
   - Ensure `User-Agent` does not contain `HeadlessChrome`.
4. In the exact tab you want to inspect, set a temporary marker in DevTools Console:
   - `window.__codex_marker = "my-live-tab"; document.title = document.title + " [my-live-tab]";`
5. List available tabs and choose the one with the marker in `title`:
   - `node tools/browser-inspection/src/cli.mjs session targets --attach-port 9222`
6. Start an attached session pinned to that tab:
   - `node tools/browser-inspection/src/cli.mjs session attach --attach-port 9222 --target-id <target-id>`
7. Verify target identity before capture:
   - `node tools/browser-inspection/src/cli.mjs eval --session-id <id> --expression "({href: location.href, title: document.title, marker: window.__codex_marker})"`
8. Reuse the returned `sessionId` for `navigate`, `eval`, `inspect`, or `compare`.
9. Stop only the tool session when done:
   - `node tools/browser-inspection/src/cli.mjs session stop --session-id <id>`
   - or `npm run browser:cleanup`

Attached mode does not terminate your browser process.

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
- Browser not found for locally launched mode:
  - Export custom path via env variable configured in defaults (`BROWSER_INSPECTION_CHROME_PATH`), or use attach mode with a manually launched Chromium browser such as Brave.
- Local app not reachable:
  - Start app manually with `npm run dev` or pass `--manage-local-app`.
- Session not found:
  - Check `session list`; stale sessions are pruned automatically if browser pid is no longer alive.
