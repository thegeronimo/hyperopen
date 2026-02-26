# Portfolio Returns Tab Time Series (Flow-Adjusted Time-Weighted Returns)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, users can open a third chart tab on `/portfolio` named `Returns` and see a deterministic returns time series for the currently selected scope (`Perps + Spot + Vaults` or `Perps`) and range (`24H`, `7D`, `30D`, `All-time`). The series is now computed as cash-flow-adjusted time-weighted return (TWR) rather than raw equity ratio, so transfers/deposits no longer inflate reported performance.

Users can verify behavior by switching between `Account Value`, `PNL`, and `Returns` and observing that large account-value jumps caused by deposits/transfer events do not create artificial multi-thousand-percent returns.

## Progress

- [x] (2026-02-26 13:47Z) Reviewed planning and UI policy sources: `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, `/hyperopen/docs/agent-guides/trading-ui-policy.md`.
- [x] (2026-02-26 13:47Z) Reviewed returns research and external mapping evidence in `/hyperopen/docs/references/hyperliquid-portfolio-history-and-returns.md`.
- [x] (2026-02-26 13:47Z) Audited current portfolio data and rendering pipeline in `/hyperopen/src/hyperopen/api/endpoints/account.cljs`, `/hyperopen/src/hyperopen/portfolio/actions.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`, and `/hyperopen/src/hyperopen/views/portfolio_view.cljs`.
- [x] (2026-02-26 13:47Z) Identified existing test coverage to extend in `/hyperopen/test/hyperopen/portfolio/actions_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`, and `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`.
- [x] (2026-02-26 13:47Z) Authored this ExecPlan.
- [x] (2026-02-26 14:05Z) Implemented returns-series derivation and chart-tab integration in `/hyperopen/src/hyperopen/portfolio/actions.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`, and `/hyperopen/src/hyperopen/views/portfolio_view.cljs`.
- [x] (2026-02-26 14:05Z) Added and updated tests for tab normalization, returns derivation, and percent axis rendering in `/hyperopen/test/hyperopen/portfolio/actions_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`, and `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`.
- [x] (2026-02-26 14:05Z) Ran required validation gates successfully: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-26 14:30Z) Added `userNonFundingLedgerUpdates` request support through account endpoint/gateway/default/instance layers and startup collaborators.
- [x] (2026-02-26 14:30Z) Added portfolio bootstrap ledger fetch and persistence (`[:portfolio :ledger-updates]`) with deterministic time-window selection from portfolio history.
- [x] (2026-02-26 14:30Z) Replaced returns derivation with flow-adjusted time-weighted returns (Modified Dietz interval weighting) using ledger events + account-value history.
- [x] (2026-02-26 14:30Z) Added coverage for ledger request normalization, collaborator ledger bootstrap behavior, and perps flow handling (`accountClassTransfer`).
- [x] (2026-02-26 14:35Z) Hardened ledger event dedupe identity to `hash + time + delta` and added regression coverage for same-hash distinct flow events.

## Surprises & Discoveries

- Observation: Hyperliquid `portfolio` does not provide a dedicated returns history; it provides `accountValueHistory` and `pnlHistory` only.
  Evidence: `/hyperopen/docs/references/hyperliquid-portfolio-history-and-returns.md` (`Returns Over Time (Derived)`).

- Observation: `pnlHistory` can diverge from account value delta in longer windows when non-trading cash flows or scope differences are present, so using `pnlHistory` as the canonical returns source is fragile.
  Evidence: `/hyperopen/docs/references/hyperliquid-portfolio-history-and-returns.md` (`Field Meaning`, observed month divergence).

- Observation: Hyperopen already normalizes portfolio keys and stores the raw slice map under `:portfolio :summary-by-key`, which is sufficient to derive returns entirely in the VM layer.
  Evidence: `/hyperopen/src/hyperopen/api/endpoints/account.cljs` (`normalize-portfolio-summary`) and `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` (`selected-summary-entry`, `chart-history-rows`).

- Observation: The chart pipeline currently rounds account value and pnl points to integers before plotting for Hyperliquid parity, and y-axis labels are formatted as plain numbers.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` (`chart-data-points`) and `/hyperopen/src/hyperopen/views/portfolio_view.cljs` (`format-axis-number`).

- Observation: `npm test -- <pattern>` is not a supported per-namespace filter in this repository and still executes the full suite with an `Unknown arg` warning.
  Evidence: test runs printed `Unknown arg: portfolio/actions` and still completed with full-suite totals.

- Observation: Running multiple `npm test` commands in parallel can race on shared `shadow-cljs` build outputs and produce transient generated-JS syntax errors.
  Evidence: one concurrent run failed with `SyntaxError: Unexpected token '}'` in `.shadow-cljs/builds/test/dev/out/...` while sequential reruns passed.

- Observation: `userNonFundingLedgerUpdates` rows are heterogeneous union payloads; some delta types represent internal reclassification while others represent true external cash flow.
  Evidence: `/tmp/nktkas-hyperliquid/src/api/info/_methods/userNonFundingLedgerUpdates.ts` union schema and python SDK cassette samples.

- Observation: Websocket ledger stream in this repo is currently append-capped and not sufficient alone for reporting-grade 30D/all-time returns.
  Evidence: `/hyperopen/src/hyperopen/websocket/user.cljs` `upsert-seq` with `take 200`.

- Observation: Dedupe-by-hash collapses distinct ledger deltas emitted in the same transaction hash and can materially distort flow-adjusted returns.
  Evidence: regression fixture in `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs` with same hash carrying distinct deposit/withdraw deltas.

## Decision Log

- Decision: Implement `Returns` as cumulative raw equity return derived from `accountValueHistory` for the selected slice.
  Rationale: This avoids event-classification risk from ledger-based flow adjustment and reuses already-fetched data, which is the least error-prone path to a useful first release.
  Date/Author: 2026-02-26 / Codex

- Decision: Define the returns anchor as the first strictly positive account-value point in the selected history.
  Rationale: Division by zero is undefined, and some histories include leading zero periods. A positive anchor yields deterministic, finite returns.
  Date/Author: 2026-02-26 / Codex

- Decision: Exclude points before the anchor from the `Returns` plot and set anchor return to `0%`.
  Rationale: Pre-anchor values do not have a valid denominator and should not be shown as synthetic performance data.
  Date/Author: 2026-02-26 / Codex

- Decision: Add explicit percent-axis formatting for the returns tab while preserving existing numeric formatting for `Account Value` and `PNL`.
  Rationale: Returns are unitless percentages and should not be displayed as currency-like integers.
  Date/Author: 2026-02-26 / Codex

- Decision: Keep cash-flow-adjusted returns (ledger-assisted) out of this milestone and document it as a follow-up enhancement.
  Rationale: Correct classification of non-funding ledger events is valuable but higher risk; separating phases prevents false precision in the first ship.
  Date/Author: 2026-02-26 / Codex

- Decision: Replace raw-equity returns with flow-adjusted time-weighted returns using `accountValueHistory` plus `userNonFundingLedgerUpdates`.
  Rationale: User feedback confirmed raw-equity method materially overstates returns when capital flows occur in-window.
  Date/Author: 2026-02-26 / Codex

- Decision: Fetch ledger history at portfolio bootstrap for the exact observed portfolio history time window and merge with websocket ledger state in VM.
  Rationale: API fetch provides complete historical flow coverage; websocket stream keeps near-real-time freshness between bootstraps.
  Date/Author: 2026-02-26 / Codex

- Decision: Run validation gates sequentially rather than in parallel.
  Rationale: `shadow-cljs` test builds share output paths; sequential execution avoids nondeterministic build artifacts and gives trustworthy pass/fail evidence.
  Date/Author: 2026-02-26 / Codex

- Decision: Dedupe ledger events using composite identity (`hash + time + delta`) instead of hash-only identity.
  Rationale: Prevents accidental loss of distinct events under shared transaction hashes while still collapsing exact bootstrap/websocket duplicates.
  Date/Author: 2026-02-26 / Codex

## Outcomes & Retrospective

Implemented outcome: `/portfolio` now supports a third chart tab, `Returns`, computed as flow-adjusted time-weighted return. The runtime fetches historical non-funding ledger updates for the portfolio time window, persists them under portfolio state, and the VM computes chained interval returns with flow adjustment and Modified Dietz weighting. The view keeps signed percent axis formatting while preserving existing number formatting for account value and pnl tabs.

Tests and validation outcome: action, VM, and view tests were extended and all required gates passed (`npm run check`, `npm test`, `npm run test:websocket`). The only execution caveat discovered was parallel `npm test` race behavior with shared shadow build outputs; sequential runs were clean and stable.

## Context and Orientation

Portfolio history enters Hyperopen via `request-portfolio!` in `/hyperopen/src/hyperopen/api/endpoints/account.cljs`, which posts `{"type":"portfolio","user":...}` to Hyperliquid and normalizes the response keys (`day`, `week`, `month`, `allTime`, `perpDay`, etc.) into keywords stored at `[:portfolio :summary-by-key]`.

The portfolio page view model in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` chooses the active summary slice based on UI selectors (`:summary-scope`, `:summary-time-range`) and then chooses one chart history array based on selected chart tab. Today that tab supports `:account-value` and `:pnl`. The rendered chart path is a deterministic step-line built from normalized points.

The portfolio page UI in `/hyperopen/src/hyperopen/views/portfolio_view.cljs` renders tab buttons from VM-provided tab options and draws axis labels plus chart path. The action that changes tabs is `:actions/select-portfolio-chart-tab`, implemented in `/hyperopen/src/hyperopen/portfolio/actions.cljs`.

In this plan, “time-weighted return” means interval-by-interval performance calculation that removes external cash flows from returns: for each interval `(t-1, t]`, compute net flow and weighted flow from ledger events, compute interval return using a flow-adjusted denominator (Modified Dietz style), and chain interval returns multiplicatively.

## Plan of Work

Milestone 1 extends chart-tab state and normalization so `:returns` is a first-class tab value. Update `/hyperopen/src/hyperopen/portfolio/actions.cljs` by adding `:returns` to `chart-tab-options` and adding string/keyword normalization aliases (`"returns"`, optionally `"return"`) in `normalize-portfolio-chart-tab`. Keep existing default behavior unchanged.

Milestone 2 introduces a pure returns-series derivation path in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`. Reuse existing history parsing helpers (`history-point-value`, `history-point-time-ms`) against `accountValueHistory` and derive chart points with these rules: detect first strictly positive anchor, compute cumulative percent return for anchor and later points, and drop pre-anchor or non-finite points. Wire `chart-history-rows` so `:returns` resolves to this derived series.

Milestone 3 updates chart value normalization and axis metadata in VM so returns do not inherit account-value integer semantics blindly. Keep existing rounding behavior for account value and pnl; for returns, preserve sufficient decimal precision to avoid flattening small moves. Provide a simple VM flag (for example `:axis-kind :percent` vs `:number`) so the view can format labels without inferring from strings.

Milestone 4 updates `/hyperopen/src/hyperopen/views/portfolio_view.cljs` to render the new tab and format y-axis labels by axis kind. `:percent` labels should include a sign when positive and a `%` suffix (for example `+12.34%`, `-3.50%`, `0.00%`). Y-axis gutter measurement must use the same formatter used for display to prevent clipping.

Milestone 5 expands tests with deterministic fixtures:

- `/hyperopen/test/hyperopen/portfolio/actions_test.cljs`: normalize/select behavior accepts `returns`.
- `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`: returns derivation from account value history, leading-zero anchor handling, and tab-source switching includes `Returns`.
- `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`: renders `Returns` tab button/data-role and percent axis text when selected.

Milestone 6 runs required repository gates and records pass/fail outputs in this plan.

## Concrete Steps

1. Extend chart-tab normalization and VM tab options for `:returns`.

   cd /Users//projects/hyperopen
   npm test -- portfolio/actions

   Expected result: repository runs full test suite and includes passing `hyperopen.portfolio.actions-test` coverage for `returns` normalization.

2. Implement VM returns derivation and axis metadata.

   cd /Users//projects/hyperopen
   npm test -- views/portfolio/vm

   Expected result: repository runs full test suite and includes passing `hyperopen.views.portfolio.vm-test` cases for anchor handling and cumulative returns.

3. Implement view formatting changes for percent axis and tab rendering.

   cd /Users//projects/hyperopen
   npm test -- views/portfolio_view

   Expected result: repository runs full test suite and includes passing `hyperopen.views.portfolio-view-test` assertions for returns-tab rendering and percent y-axis labels.

4. Run required validation gates.

   cd /Users//projects/hyperopen
   npm run check
   npm test
   npm run test:websocket

   Expected result: all commands exit with status 0.

## Validation and Acceptance

Acceptance is met when all of the following are true:

1. Portfolio chart shows three tabs: `Account Value`, `PNL`, and `Returns`.
2. Selecting `Returns` displays a flow-adjusted time-weighted return series derived from `accountValueHistory` plus non-funding ledger updates.
3. Cash-flow jumps (for example deposits and account-class transfers) do not create artificial return spikes.
4. Returns y-axis labels are percent-formatted, including sign and `%` suffix.
5. Switching between tabs does not regress existing account-value/pnl rendering.
6. Required validation gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

Manual spot-check scenario:

- Open `/portfolio`, select `30D`, then switch `Account Value -> Returns`.
- Confirm the first returns point is `0%` and later points reflect cumulative account-value change.
- Switch scope to `Perps` and confirm series recomputes without console/runtime errors.

## Idempotence and Recovery

The implementation steps are additive and idempotent. Re-running tests and validation commands is safe.

If returns derivation introduces unexpected edge behavior, fallback recovery is to keep `:returns` normalization in actions but temporarily omit the tab from VM `chart-tab-options` while preserving all existing tabs and chart behavior. This allows rollback of user-visible exposure without touching API or startup data flows.

## Artifacts and Notes

Primary research anchor for methodology and payload semantics:

- `/hyperopen/docs/references/hyperliquid-portfolio-history-and-returns.md`

Primary implementation targets:

- `/hyperopen/src/hyperopen/portfolio/actions.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
- `/hyperopen/src/hyperopen/views/portfolio_view.cljs`
- `/hyperopen/test/hyperopen/portfolio/actions_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`

Scope now includes cash-flow-adjusted returns using `userNonFundingLedgerUpdates`; no additional follow-up is required for this capability.

Validation artifacts captured during implementation:

- `npm run check`: pass.
- `npm test`: pass (`Ran 1395 tests containing 6880 assertions. 0 failures, 0 errors.`).
- `npm run test:websocket`: pass (`Ran 153 tests containing 696 assertions. 0 failures, 0 errors.`).

## Interfaces and Dependencies

No new external library dependency is required.

Interfaces expected at completion:

- `hyperopen.portfolio.actions/normalize-portfolio-chart-tab` accepts and normalizes returns tokens.
- `hyperopen.views.portfolio.vm/portfolio-vm` chart model exposes a `Returns` tab and returns-derived point series.
- `hyperopen.views.portfolio-view/chart-card` formats y-axis labels according to chart axis kind (`number` vs `percent`).

Existing interfaces that must remain stable:

- `:actions/select-portfolio-chart-tab` action id and payload shape.
- Portfolio summary data contract at `[:portfolio :summary-by-key]`.
- Existing account-value and pnl chart behavior.

Plan revision note: 2026-02-26 13:47Z - Initial plan authored from portfolio returns research and current portfolio VM/view/actions audit; selected low-risk raw-equity cumulative-returns approach for first release.
Plan revision note: 2026-02-26 14:05Z - Marked initial implementation complete, documented test-run caveats (unsupported pattern args and parallel shadow build races), and recorded successful validation gate evidence.
Plan revision note: 2026-02-26 14:30Z - Scope elevated to reporting-grade flow-adjusted TWR after user feedback; added ledger API fetch pipeline, flow classification, interval chaining logic, and corresponding tests/validation evidence.
Plan revision note: 2026-02-26 14:35Z - Hardened ledger dedupe semantics to avoid same-hash event collapse and added regression + validation reruns.
