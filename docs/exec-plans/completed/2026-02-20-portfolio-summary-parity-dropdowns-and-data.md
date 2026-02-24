# Portfolio Summary Parity: Dropdown Selectors, Data Fetch Surface, and Computation Alignment

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Hyperopen’s portfolio summary card currently renders static selector buttons and simplified metrics that diverge from Hyperliquid’s `/portfolio` behavior. After this change, the summary selectors become real dropdown controls, portfolio summary math aligns with Hyperliquid’s key behaviors (selected scope/time keying, PNL history delta, max drawdown calculation, and equity decomposition), and account bootstrap fetches portfolio/user-fee payloads into state so the UI can consume the same data class Hyperliquid uses.

A user can verify this by opening `/portfolio`, interacting with both top selectors as dropdown menus, and seeing summary values change according to selected range/scope while retaining deterministic fallbacks when upstream data is unavailable.

## Progress

- [x] (2026-02-20 00:12Z) Re-inspected Hyperliquid portfolio bundle behavior and extracted parity-critical details (selector options, `/info` portfolio request, summary formulas, max drawdown behavior).
- [x] (2026-02-20 00:12Z) Audited current Hyperopen portfolio view/VM/runtime wiring and identified parity gaps (static selector controls, no portfolio summary fetch, simplified metrics).
- [x] (2026-02-20 00:12Z) Authored this ExecPlan.
- [x] (2026-02-20 03:21Z) Implemented portfolio selector actions + runtime/contracts wiring.
- [x] (2026-02-20 03:21Z) Implemented portfolio/user-fee fetch support during account bootstrap and store projections.
- [x] (2026-02-20 03:21Z) Implemented portfolio VM parity upgrades (scope/time keying, drawdown, equity decomposition, fallback policy).
- [x] (2026-02-20 03:21Z) Replaced summary selector buttons with dropdown menus and wired interactions.
- [x] (2026-02-20 03:21Z) Added/updated tests for action behavior, VM math, startup bootstrap fetch integration, and view rendering.
- [x] (2026-02-20 03:21Z) Ran validation gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-20 03:21Z) Updated this plan with outcomes, discoveries, and revision note.

## Surprises & Discoveries

- Observation: Hyperliquid computes summary PNL directly from selected `pnlHistory` (`last - first`) and max drawdown from selected history payload, not from a constant fallback.
  Evidence: `/hyperopen/tmp/hyperliquid-185.pretty.js` portfolio module (`le.pnlHistory`, `arP(le)`).

- Observation: Hyperliquid summary selection keys are two-dimensional (`scope` + `time`) mapped to payload keys like `perpDay`, `perpWeek`, `perpMonth`, `perpAllTime`.
  Evidence: `/hyperopen/tmp/hyperliquid-185.pretty.js` callsite uses `(0, a.O2m)(G, N)` for selected portfolio slice.

- Observation: Hyperopen startup account bootstrap currently does not fetch portfolio/user-fee payloads.
  Evidence: `/hyperopen/src/hyperopen/startup/runtime.cljs` `bootstrap-account-data!` only fetches open orders/fills/spot state/abstraction/funding history + per-dex stage B.

- Observation: Hyperliquid `/info` `portfolio` payload is an array of `[key, summary]` tuples, and reducing it directly to a map is required before key selection.
  Evidence: `/hyperopen/tmp/module-36714.js` `pt` (`type: "portfolio"`) + `pr` reducer implementation.

## Decision Log

- Decision: Add portfolio and user-fee requests to account stage-A bootstrap rather than view-triggered fetch logic.
  Rationale: Keeps side effects at startup/infrastructure boundaries and preserves view/model purity.
  Date/Author: 2026-02-20 / Codex

- Decision: Implement summary dropdowns with explicit UI projection actions (`toggle` + `select`) and batched `:effects/save-many` writes.
  Rationale: Matches frontend policy requiring deterministic immediate UI transitions and single-transition projection updates.
  Date/Author: 2026-02-20 / Codex

- Decision: Compute max drawdown as peak-to-trough percentage from selected account value history, with `N/A` when no valid history.
  Rationale: Aligns with Hyperliquid behavior and avoids misleading hardcoded `0%` when history is absent.
  Date/Author: 2026-02-20 / Codex

- Decision: Canonicalize portfolio summary keys to kebab-case internal keys (`:day`, `:all-time`, `:perp-month`, etc.) at the endpoint normalization boundary.
  Rationale: Prevents repeated key-shape branching in VM/view code and keeps selector mapping deterministic.
  Date/Author: 2026-02-20 / Codex

## Outcomes & Retrospective

Delivered:
- Portfolio summary selectors are now dropdown menus with runtime-backed toggle/select actions (`scope` and `time-range`).
- Account stage-A bootstrap now fetches `portfolio` and `userFees`, with load/success/error projections in store.
- Portfolio VM now derives selected summary from fetched portfolio keys, computes PNL delta from history, computes max drawdown from history, and composes summary rows with abstraction-aware labels.
- Portfolio endpoint normalization now handles tuple-shaped portfolio payloads and canonical key mapping.
- Test coverage was added/updated for portfolio actions, VM/view behavior, startup/bootstrap integration, defaults, and runtime collaborator wiring.

Validation:
- `npm run check` passed.
- `npm test` passed.
- `npm run test:websocket` passed.

Retrospective:
- The most impactful bug source was payload-shape mismatch (`portfolio` tuple arrays vs map-row assumptions). Fixing normalization at the boundary substantially simplified downstream parity logic.
- Keeping selector state and dropdown visibility projections batched into single `:effects/save-many` updates kept UI transitions deterministic and matched frontend interaction policy.

## Context and Orientation

Portfolio UI code lives in:
- `/hyperopen/src/hyperopen/views/portfolio_view.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`

Action/runtime wiring that must include any new action IDs:
- `/hyperopen/src/hyperopen/core/public_actions.cljs`
- `/hyperopen/src/hyperopen/core/macros.clj`
- `/hyperopen/src/hyperopen/registry/runtime.cljs`
- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`

Account bootstrap and API fetch surface:
- `/hyperopen/src/hyperopen/startup/runtime.cljs`
- `/hyperopen/src/hyperopen/startup/composition.cljs`
- `/hyperopen/src/hyperopen/startup/collaborators.cljs`
- `/hyperopen/src/hyperopen/api/endpoints/account.cljs`
- `/hyperopen/src/hyperopen/api/default.cljs`
- `/hyperopen/src/hyperopen/api/instance.cljs`
- `/hyperopen/src/hyperopen/api/projections.cljs`

Default state shape:
- `/hyperopen/src/hyperopen/state/app_defaults.cljs`

Primary tests to adjust:
- `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`
- `/hyperopen/test/hyperopen/startup/runtime_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap_test.cljs`
- `/hyperopen/test/hyperopen/state/app_defaults_test.cljs`
- plus any action/runtime contract coverage tests impacted by new action IDs.

## Plan of Work

Milestone 1 introduces a dedicated portfolio action namespace for summary selector dropdown control and selection state updates. These actions are wired through contracts, registry binding, public exports, and runtime collaborators so view interactions are first-class runtime actions.

Milestone 2 adds a portfolio data fetch surface by introducing account endpoint requests for `portfolio` and `userFees`, then pulling those requests during stage-A account bootstrap and projecting results into store state.

Milestone 3 upgrades portfolio VM derivation to mirror Hyperliquid summary semantics: selected scope/time key mapping, PNL from history deltas, max drawdown from account value history, and total-equity composition that respects top-up abstraction behavior.

Milestone 4 updates the portfolio summary card controls from static buttons to dropdown menus driven by the new action/state model, while preserving keyboard accessibility and deterministic class/state transitions.

Milestone 5 updates tests and runs required validation gates.

## Concrete Steps

1. Add `/hyperopen/src/hyperopen/portfolio/actions.cljs` with:
   - selector toggle actions,
   - selector select actions,
   - normalization of scope/time values,
   - single-batch projection effects (`:effects/save-many`).

2. Register these actions end-to-end in:
   - `/hyperopen/src/hyperopen/schema/contracts.cljs`
   - `/hyperopen/src/hyperopen/core/public_actions.cljs`
   - `/hyperopen/src/hyperopen/core/macros.clj`
   - `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
   - `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
   - `/hyperopen/src/hyperopen/registry/runtime.cljs`

3. Add portfolio state defaults in `/hyperopen/src/hyperopen/state/app_defaults.cljs` for:
   - selector UI state,
   - fetched portfolio summary data,
   - fetched user-fee data and load/error metadata.

4. Add portfolio/user-fee API requests and projections in:
   - `/hyperopen/src/hyperopen/api/endpoints/account.cljs`
   - `/hyperopen/src/hyperopen/api/default.cljs`
   - `/hyperopen/src/hyperopen/api/instance.cljs`
   - `/hyperopen/src/hyperopen/api/projections.cljs`

5. Add bootstrap wiring for stage-A data fetches in:
   - `/hyperopen/src/hyperopen/startup/collaborators.cljs`
   - `/hyperopen/src/hyperopen/startup/composition.cljs`
   - `/hyperopen/src/hyperopen/startup/runtime.cljs`

6. Update portfolio VM and view in:
   - `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
   - `/hyperopen/src/hyperopen/views/portfolio_view.cljs`

7. Add/update tests in the impacted namespaces and test runner registration where needed.

8. Run from `/hyperopen`:

    npm run check
    npm test
    npm run test:websocket

## Validation and Acceptance

The implementation is accepted when:

1. `/portfolio` summary selectors are dropdown menus that open/close and apply selected values via runtime actions.
2. VM summary values reflect selected scope/time, using fetched portfolio data when present and deterministic fallbacks when absent.
3. Max drawdown renders as computed value when history exists and `N/A` when it does not.
4. Account bootstrap fetches and stores portfolio/user-fee payloads for connected addresses.
5. Required validation gates pass with no regressions.

## Idempotence and Recovery

All edits are additive/source-level and safe to reapply. If bootstrap fetch changes cause regressions, recovery is to temporarily disable stage-A portfolio/user-fee calls while keeping selector/UI and VM fallback logic intact. This preserves portfolio interaction parity while isolating API changes.

## Artifacts and Notes

Parity reference artifacts used in this plan:
- `/hyperopen/tmp/hyperliquid-185.pretty.js`
- `/hyperopen/tmp/hyperliquid-6951.pretty.js`
- `/hyperopen/tmp/hyperliquid-main.ccb853ef.js`
- `/hyperopen/tmp/hyperliquid-portfolio-full.png`
- `/hyperopen/tmp/hyperliquid-portfolio-mobile390.png`

## Interfaces and Dependencies

New action interfaces expected at completion:
- `:actions/toggle-portfolio-summary-scope-dropdown`
- `:actions/select-portfolio-summary-scope`
- `:actions/toggle-portfolio-summary-time-range-dropdown`
- `:actions/select-portfolio-summary-time-range`

New API request helpers expected at completion:
- `request-portfolio!`
- `request-user-fees!`

State interfaces expected at completion:
- `:portfolio-ui` selector state
- `:portfolio` fetched summary/user-fee state

Plan revision note: 2026-02-20 00:12Z - Initial plan created for portfolio summary parity pass (dropdowns + fetch + VM computation alignment).
Plan revision note: 2026-02-20 03:21Z - Completed implementation, tests, and validation gates; recorded final outcomes/discoveries.
