---
owner: platform
status: canonical
last_reviewed: 2026-10-01
review_cycle_days: 90
source_of_truth: true
---

# Browser Testing Routing

## Purpose

Hyperopen uses two browser-testing tools on purpose. Playwright owns committed deterministic browser tests that should be reviewed, repeated, asserted, and run locally or in the dedicated manual GitHub Actions workflow. Browser MCP, via the browser-inspection subsystem, remains the right tool for exploratory investigation, live browser attach, parity compare, and governed design review.

## Use Playwright For

- any browser verification that should be committed to the repo
- deterministic smoke tests and regression coverage
- reusable fixtures, helpers, and assertions
- CI-safe browser execution in the dedicated manual Playwright workflow
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
- Run the quick local interactive smoke suite against the dev app build: `npm run test:playwright:smoke`
- Run the release-artifact SEO smoke suite against `out/release-public`: `npm run test:playwright:seo`
- Run Playwright headed with one worker: `npm run test:playwright:headed`
- Run the full committed Playwright suite, including the release-only SEO smoke: `npm run test:playwright:ci`
- Run the dedicated GitHub Actions Playwright workflow manually from the `Playwright` workflow in Actions
- Start the Browser MCP server: `npm run browser:mcp`
- Stop all tracked browser-inspection sessions: `npm run browser:cleanup`
- Run governed design review: `npm run qa:design-ui -- --targets trade-route --manage-local-app`
- Run the checked-in Browser MCP scenario bundle: `npm run qa:pr-ui`
- Attach Browser MCP to a live Chrome session: `node tools/browser-inspection/src/cli.mjs session attach --attach-port 9222 --target-id <target-id>`

## Cleanup Contract

Before concluding browser work, stop every browser-inspection session you created.

- Playwright runs should exit on their own. Browser-inspection and Browser MCP sessions must be cleaned up explicitly.
- Use `npm run browser:cleanup` as the repo-wide default cleanup step after Browser MCP or browser-inspection work.
- Use `node tools/browser-inspection/src/cli.mjs session stop --session-id <id>` or MCP `browser_session_stop` when only one session should be closed.
- Attached-session cleanup must close only the tool-created tab. It must not terminate the user's own browser window.
- Do not leave browser-inspection sessions running between tasks unless the user explicitly asks for a long-lived session.

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
- HyperCore <-> HyperEVM transfers in `/hyperopen/tools/playwright/test/funding-transfer-hyperevm.spec.mjs` (`@regression`), against the wallet and exchange simulators, a mocked HyperEVM RPC and fixed HyperCore account reads: the exact Spot -> HyperEVM `sendAsset`, the HyperEVM -> Spot wallet logs (switch, chain-pinned `eth_sendTransaction`, USDC approve then deposit), the gas top-up, receipt rate limits and the pending timeout, wallet refusals (4902, 4200, rejection, a chain change between transactions), subaccount and spectate read-only states, the Balances HyperEVM rows and filter, the Portfolio funds strip, the `/trade` HyperEVM line, and keyboard-only runs with focus on each view's heading

These tests intentionally reuse the existing `HYPEROPEN_DEBUG` bridge, simulator helpers, and `data-parity-id` or `data-role` anchors instead of adding a second browser-only app API.

Every spec imports `test` and `expect` from `/hyperopen/tools/playwright/support/guarded_test.mjs`, never from `@playwright/test` directly; `npm run test:playwright-support` fails a spec that does. Its automatic `hyperEvmRpcGuard` fixture answers the public HyperEVM RPC (`rpc.hyperliquid.xyz`) from an empty mock for the test's context and every context the test opens, so the app's HyperEVM balance poller never reaches the live RPC, and it fails the test at teardown if any request to that host was not answered by a mock. A spec that needs HyperEVM balances routes its own fixture with `routeHyperEvmRpc(page, fixture)` from `/hyperopen/tools/playwright/support/hyperevm_fixtures.mjs`.

The interactive suite runs against the dev app build because the bridge only exists in `goog.DEBUG` mode. The release-only SEO smoke stays separate so it can validate the generated `out/release-public` artifact, route metadata, and deployment-style cache headers without breaking bridge-based tests.

## Browser MCP Flows That Remain Exploratory

These workflows stay on the Browser MCP side and are not replaced by Playwright:

- live-session browser attach and DOM or network inspection
- Hyperliquid-vs-local parity compare
- governed six-pass design review and artifact bundles
- selector and repro discovery before writing stable tests
- flaky-browser investigation where a committed deterministic test does not exist yet

## Key Files

- Interactive Playwright config: `/hyperopen/playwright.config.mjs`
- Release SEO Playwright config: `/hyperopen/playwright.release.config.mjs`
- Playwright helpers and tests: `/hyperopen/tools/playwright/**`
- Browser MCP config and server registration: `/hyperopen/.codex/config.toml`
- Browser-inspection tooling: `/hyperopen/tools/browser-inspection/**`
- Browser QA contract: `/hyperopen/docs/agent-guides/browser-qa.md`
- Live inspection runbook: `/hyperopen/docs/runbooks/browser-live-inspection.md`
