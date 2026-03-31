---
owner: platform
status: canonical
last_reviewed: 2026-03-22
review_cycle_days: 90
source_of_truth: true
---

# Browser Testing Routing

## Purpose

Hyperopen uses two browser-testing tools on purpose. Playwright owns committed deterministic browser tests that should be reviewed, repeated, asserted, and run in CI. Browser MCP, via the browser-inspection subsystem, remains the right tool for exploratory investigation, live browser attach, parity compare, and governed design review.

## Use Playwright For

- any browser verification that should be committed to the repo
- deterministic smoke tests and regression coverage
- reusable fixtures, helpers, and assertions
- CI-safe browser execution
- multi-viewport browser validation when the flow is stable enough for repeatable assertions

## Use Browser MCP For

- exploratory debugging and one-off investigation
- live attachment to an existing Chromium-family browser session
- reproducing flaky behavior and inspecting current DOM, console, network, layout, or state
- governed design-review passes from `/hyperopen/docs/agent-guides/browser-qa.md`
- Hyperliquid parity compare and artifact-heavy browser evidence gathering
- discovering selectors or flow details before promoting the stable path into Playwright

## Promotion Rule

After Browser MCP exploration stabilizes a flow, convert that stable local path into a Playwright test unless the task is explicitly exploratory. Do not treat Browser MCP evidence as a substitute for committed regression coverage when the flow can be made deterministic.

## Exact Commands

- Install Playwright browsers once: `npm run test:playwright:install`
- Run the quick local smoke suite: `npm run test:playwright:smoke`
- Run Playwright headed with one worker: `npm run test:playwright:headed`
- Run the full committed Playwright suite: `npm run test:playwright:ci`
- Start the Browser MCP server: `npm run browser:mcp`
- Run governed design review: `npm run qa:design-ui -- --targets trade-route --manage-local-app`
- Run the checked-in Browser MCP scenario bundle: `npm run qa:pr-ui`
- Attach Browser MCP to a live Chrome session: `node tools/browser-inspection/src/cli.mjs session attach --attach-port 9222 --target-id <target-id>`

## Initial Playwright Coverage

The committed Playwright suite covers these stable local flows:

- route smoke for `/trade`, `/staking`, `/portfolio`, `/portfolio/trader`, `/leaderboard`, and `/vaults` at desktop and mobile widths
- asset selector opens and selects `ETH`
- funding deposit flow reaches `Deposit USDC`
- staking route disconnected gating and validator timeframe selection
- wallet connect plus enable-trading flow with the built-in wallet and exchange simulators
- order submit and cancel gating with the built-in simulators
- mobile account-surface selection to the `Positions` tab
- mobile position-margin presentation as a bottom sheet

These tests intentionally reuse the existing `HYPEROPEN_DEBUG` bridge, simulator helpers, and `data-parity-id` or `data-role` anchors instead of adding a second browser-only app API.

## Browser MCP Flows That Remain Exploratory

These workflows stay on the Browser MCP side and are not replaced by Playwright:

- live-session browser attach and DOM or network inspection
- Hyperliquid-vs-local parity compare
- governed six-pass design review and artifact bundles
- selector and repro discovery before writing stable tests
- flaky-browser investigation where a committed deterministic test does not exist yet

## Key Files

- Playwright config: `/hyperopen/playwright.config.mjs`
- Playwright helpers and tests: `/hyperopen/tools/playwright/**`
- Browser MCP config and server registration: `/hyperopen/.codex/config.toml`
- Browser-inspection tooling: `/hyperopen/tools/browser-inspection/**`
- Browser QA contract: `/hyperopen/docs/agent-guides/browser-qa.md`
- Live inspection runbook: `/hyperopen/docs/runbooks/browser-live-inspection.md`
