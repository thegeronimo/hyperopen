# Portfolio Chart Parity: Account Value and PNL Tabs on `/portfolio`

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Hyperopen’s portfolio page currently renders a static chart shell, so users cannot switch between Account Value and PNL history or read a real account history graph. After this change, `/portfolio` will render a real chart card with tabbed series switching (`Account Value` / `PNL`) and axis scaling from live portfolio summary history, matching Hyperliquid’s observed behavior from `accountValueHistory` and `pnlHistory`.

A user can verify this by opening `/portfolio`, toggling the chart tab between `Account Value` and `PNL`, and observing that the plotted step-line and y-axis ticks update to the selected series while preserving deterministic empty-state behavior when history is missing.

## Progress

- [x] (2026-02-20 00:50Z) Re-inspected Hyperliquid portfolio chart implementation from captured/prettified bundle artifacts.
- [x] (2026-02-20 00:50Z) Audited current Hyperopen portfolio chart card and VM data availability.
- [x] (2026-02-20 00:52Z) Authored this ExecPlan.
- [x] (2026-02-20 01:01Z) Implemented chart tab action/state wiring for `Account Value` vs `PNL`.
- [x] (2026-02-20 01:02Z) Implemented VM-level chart-series derivation and y-axis tick computation from selected summary history.
- [x] (2026-02-20 01:03Z) Replaced static chart shell with an SVG step-line renderer and tab controls.
- [x] (2026-02-20 01:04Z) Added/adjusted tests for actions, VM derivation, contracts, defaults, runtime wiring, and portfolio view rendering.
- [x] (2026-02-20 01:05Z) Ran required validation gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-20 01:06Z) Updated this plan with outcomes, discoveries, and revision note after implementation.

## Surprises & Discoveries

- Observation: Hyperliquid’s portfolio chart card keeps local tab state with values `accountValue` and `pnl`, and chooses between `accountValueHistory` and `pnlHistory` for plotting.
  Evidence: `/hyperopen/tmp/hyperliquid-6951.pretty.js` (`const [u, p] = useState("pnl")` and `"accountValue" === t ? u : p` mapping).

- Observation: Hyperliquid renders the line as a step chart and sets y-axis domain to automatic bounds.
  Evidence: `/hyperopen/tmp/hyperliquid-6951.pretty.js` (`type: "step"`, `domain: ["auto", "auto"]`, `tickCount: 4`).

- Observation: Existing portfolio VM already had canonical selected summary entry logic, so chart derivation could reuse that selection directly without adding new summary-key resolution logic.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` (`selected-summary-entry` and `selected-key` usage in `portfolio-vm`).

## Decision Log

- Decision: Persist selected chart tab in Hyperopen app state (`:portfolio-ui`) instead of local component-only state.
  Rationale: Hyperopen interaction actions are runtime-driven; keeping tab selection in state preserves deterministic rendering and testability under the existing action architecture.
  Date/Author: 2026-02-20 / Codex

- Decision: Implement a minimal deterministic SVG chart renderer in `portfolio_view.cljs` instead of introducing a new charting dependency for this card.
  Rationale: The page already has all required data in VM shape, and this avoids dependency/runtime churn while delivering parity for tabbed history display.
  Date/Author: 2026-02-20 / Codex

- Decision: Keep summary scope/time selectors in the summary card and add only chart tab switching to the chart card in this phase.
  Rationale: The user request focused on Account Value vs PNL tabbed chart behavior, and current selector behavior was already implemented in the prior portfolio summary parity phase.
  Date/Author: 2026-02-20 / Codex

## Outcomes & Retrospective

Delivered outcomes:

- Added `:actions/select-portfolio-chart-tab` with normalization in `/hyperopen/src/hyperopen/portfolio/actions.cljs`.
- Wired the new action through runtime contracts/registry/public exports/collaborator composition.
- Added `[:portfolio-ui :chart-tab]` default state (`:account-value`).
- Extended portfolio VM with chart derivation (`:chart`) including selected tab, tab metadata, normalized series points, step path, and y-axis ticks from selected summary history.
- Replaced placeholder chart shell with a tabbed SVG step-line chart in `/hyperopen/src/hyperopen/views/portfolio_view.cljs`.
- Updated tests across actions, VM, view, schema contracts, runtime collaborators, and app defaults.

Validation outcomes:

- `npm run check` passed.
- `npm test` passed.
- `npm run test:websocket` passed.

Retrospective:

- Reusing existing summary selection logic kept the implementation smaller and reduced regression risk.
- The SVG-based approach achieved the required parity behavior with minimal runtime footprint and no dependency expansion.

## Context and Orientation

Portfolio view UI entrypoint: `/hyperopen/src/hyperopen/views/portfolio_view.cljs`.

Portfolio page VM derivation entrypoint: `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`.

Portfolio UI actions and selectors live in `/hyperopen/src/hyperopen/portfolio/actions.cljs` and are wired through:

- `/hyperopen/src/hyperopen/core/public_actions.cljs`
- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
- `/hyperopen/src/hyperopen/registry/runtime.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`

Portfolio UI default state shape is defined in `/hyperopen/src/hyperopen/state/app_defaults.cljs`.

Current tests covering this area live in:

- `/hyperopen/test/hyperopen/portfolio/actions_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`

Hyperliquid behavior reference artifacts used for parity:

- `/hyperopen/tmp/hyperliquid-6951.pretty.js`
- `/hyperopen/tmp/hyperliquid-185.pretty.js`
- live route shell: `https://app.hyperliquid.xyz/portfolio`

## Plan of Work

Milestone 1 adds chart-tab runtime support by introducing a new portfolio action that selects chart mode (`:account-value` or `:pnl`) and stores it in `:portfolio-ui`. This preserves deterministic action-driven state transitions and keeps behavior testable.

Milestone 2 extends the portfolio VM to produce chart-ready data for the selected summary key and selected chart tab. This includes normalizing history points, selecting the correct history source, deriving y-axis ticks, and exposing all rendering fields as pure view-model data.

Milestone 3 replaces the static placeholder chart shell with a real SVG step-line chart card and tab buttons in `portfolio_view.cljs`, using VM output for labels, axis ticks, and path coordinates while keeping empty-state behavior deterministic.

Milestone 4 updates tests and executes all required repository validation gates.

## Concrete Steps

1. Add chart-tab action(s) and normalization in `/hyperopen/src/hyperopen/portfolio/actions.cljs`.
2. Register action IDs and public bindings in contracts/registry/collaborator/public-actions files listed above.
3. Add default chart-tab value in `/hyperopen/src/hyperopen/state/app_defaults.cljs`.
4. Extend `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` with chart-series derivation for selected summary and tab.
5. Replace placeholder chart UI in `/hyperopen/src/hyperopen/views/portfolio_view.cljs` with tabbed SVG chart rendering.
6. Update tests in:
   - `/hyperopen/test/hyperopen/portfolio/actions_test.cljs`
   - `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`
   - `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`
7. Run from repo root (`/Users//projects/hyperopen`):

    npm run check
    npm test
    npm run test:websocket

## Validation and Acceptance

The work is accepted when:

1. `/portfolio` chart card provides two interactive tabs: `Account Value` and `PNL`.
2. Selecting either tab updates the rendered chart series using the corresponding history (`accountValueHistory` or `pnlHistory`) for the current scope/time summary key.
3. Chart rendering remains deterministic for empty/missing history (no runtime errors, clear baseline/empty-state display).
4. Tests cover action behavior, VM derivation, and UI rendering expectations.
5. `npm run check`, `npm test`, and `npm run test:websocket` pass.

## Idempotence and Recovery

All edits are source-level and safe to rerun. If chart rendering regresses view stability, recovery is to keep the new VM/action state shape but temporarily swap the view back to a non-interactive shell while preserving tabs and tests for state transitions.

## Artifacts and Notes

Direct implementation references:

- `/hyperopen/tmp/hyperliquid-6951.pretty.js` for tab behavior and chart source switching.
- `/hyperopen/tmp/hyperliquid-185.pretty.js` for portfolio summary keying and selected-slice usage.

## Interfaces and Dependencies

Interfaces expected after completion:

- New action ID `:actions/select-portfolio-chart-tab`.
- New state path `[:portfolio-ui :chart-tab]` with values `:account-value` or `:pnl`.
- VM output block `:chart` containing selected tab, tab options, y-axis ticks, and normalized series points for rendering.

No external dependencies are introduced; implementation uses existing ClojureScript + Hiccup rendering.

Plan revision note: 2026-02-20 00:52Z - Initial plan created for portfolio chart parity (Account Value / PNL tabbed graph).
Plan revision note: 2026-02-20 01:06Z - Completed implementation, tests, and required validation gates; moved plan to completed.
