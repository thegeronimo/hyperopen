# Portfolio Returns Benchmark Overlays With Asset-Selector Symbols

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, users can open the `Returns` chart on `/portfolio`, search benchmark symbols from the same market universe used by the asset selector, add multiple benchmark chips, and see strategy returns plotted against all selected benchmark returns on the same chart. Benchmark lines must be computed from benchmark price candles converted to cumulative returns and aligned to the exact portfolio-returns timestamps so all lines are directly comparable point by point.

Users can verify the feature by selecting `Returns`, searching benchmark symbols (for example `SPY`, `BTC`), adding one or more chips, and seeing benchmark lines appear with a legend while y-axis formatting remains percentage-based. Benchmarks are ordered by descending open interest in the searchable suggestions.

## Progress

- [x] (2026-02-26 15:45Z) Reviewed planning, frontend, and runtime guardrails in `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, and `/hyperopen/docs/agent-guides/trading-ui-policy.md`.
- [x] (2026-02-26 15:45Z) Audited current portfolio returns pipeline and rendering in `/hyperopen/src/hyperopen/portfolio/actions.cljs`, `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`, and `/hyperopen/src/hyperopen/views/portfolio_view.cljs`.
- [x] (2026-02-26 15:45Z) Audited candle request/effect plumbing and contracts in `/hyperopen/src/hyperopen/runtime/app_effects.cljs`, `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`, `/hyperopen/src/hyperopen/api/fetch_compat.cljs`, and `/hyperopen/src/hyperopen/schema/contracts.cljs`.
- [x] (2026-02-26 15:45Z) Authored this ExecPlan.
- [x] (2026-02-26 15:57Z) Implemented benchmark selection actions, effect wiring, contracts, and runtime registration/ordering coverage.
- [x] (2026-02-26 15:59Z) Implemented benchmark return conversion and timestamp alignment in portfolio VM.
- [x] (2026-02-26 16:00Z) Implemented returns-tab benchmark selector and dual-series chart rendering in portfolio view.
- [x] (2026-02-26 16:01Z) Added/updated tests across actions, VM, view, contracts, and runtime validation.
- [x] (2026-02-26 16:02Z) Ran required validation gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-26 17:24Z) Revised selector UX from dropdown to searchable multi-select chips, sorted suggestions by open interest, and updated benchmark overlays to support multiple concurrent series.

## Surprises & Discoveries

- Observation: The returns tab already uses flow-adjusted time-weighted returns from account value history plus ledger flows, so benchmark logic must be additive and must not regress the existing returns method.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` (`returns-history-rows`, `ledger-flow-events`, and related tests in `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`).

- Observation: Candle fetching is currently tied to `:active-asset` in runtime effect plumbing, but candle storage is already coin-keyed (`[:candles coin interval]`).
  Evidence: `/hyperopen/src/hyperopen/runtime/app_effects.cljs` (`fetch-candle-snapshot!`) and `/hyperopen/src/hyperopen/api/projections.cljs` (`apply-candle-snapshot-success`).

- Observation: Runtime effect-order contracts only enforce projection-before-heavy behavior for explicitly covered actions, so any new benchmark-selection action that emits candle fetch effects must be added to the action policy map.
  Evidence: `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs` and `/hyperopen/test/hyperopen/runtime/validation_test.cljs`.

- Observation: Asset selector market entries can include duplicate user-facing symbols across market types/dexes, so benchmark options should key off `:coin` and preserve deterministic order by cache rank.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` (`benchmark-selector-options`) and `/hyperopen/src/hyperopen/asset_selector/markets_cache.cljs` cache-order semantics.

## Decision Log

- Decision: Benchmark symbols will be sourced from `[:asset-selector :markets]` and stored as benchmark coin identity in portfolio UI state.
  Rationale: This directly satisfies the requirement that benchmark choices come from the same symbol universe as the asset selector.
  Date/Author: 2026-02-26 / Codex

- Decision: Benchmark candle fetch effect will be extended to accept optional `:coin`, defaulting to `:active-asset` when omitted.
  Rationale: This keeps existing chart behavior unchanged while enabling benchmark fetches for arbitrary symbols.
  Date/Author: 2026-02-26 / Codex

- Decision: Benchmark series will be converted to cumulative returns and sampled at portfolio return timestamps using latest-known benchmark close at or before each portfolio timestamp.
  Rationale: This produces deterministic time alignment without introducing interpolation artifacts beyond available market data.
  Date/Author: 2026-02-26 / Codex

- Decision: Returns chart will render multiple series (strategy plus optional benchmark) with a compact legend in the chart header when benchmark is enabled.
  Rationale: Users need clear visual distinction and naming for both lines while preserving current chart card structure.
  Date/Author: 2026-02-26 / Codex

- Decision: Returns benchmark selection UI will use a searchable chip-input pattern matching funding-history coin search behavior, including keyboard Enter/Escape handling and removable chips.
  Rationale: This supports fast discovery, multi-selection, and consistent UX across portfolio and account-history filtering controls.
  Date/Author: 2026-02-26 / Codex

- Decision: Benchmark suggestions will be ordered by descending market open interest, with deterministic secondary ordering by cache order and symbol identity.
  Rationale: Prioritizing high open-interest symbols improves selection relevance while preserving stable ordering.
  Date/Author: 2026-02-26 / Codex

## Outcomes & Retrospective

Implemented end-to-end benchmark overlay support on the portfolio Returns tab:

- Added benchmark selection actions and projection-first fetch wiring, including optional `:coin` support on candle snapshot effects and associated runtime/contract registrations.
- Added portfolio VM support for benchmark option derivation from asset-selector markets (sorted by open interest), benchmark candle close parsing, cumulative return conversion, and timestamp alignment to strategy return points.
- Added portfolio chart multi-series model output and view rendering updates for searchable multi-select benchmark chips, removable benchmarks, multi-line paths, and a legend when multiple series are visible.
- Extended tests in actions/runtime/contracts defaults plus portfolio VM/view to cover benchmark option derivation, aligned benchmark series math, and rendered benchmark controls/paths.
- Validation gates passed: `npm run check`, `npm test`, and `npm run test:websocket`.

## Context and Orientation

The portfolio chart state is built in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` and rendered in `/hyperopen/src/hyperopen/views/portfolio_view.cljs`. The chart currently exposes one plotted path (`:path`) and point set (`:points`) based on selected tab (`:account-value`, `:pnl`, `:returns`). The returns tab already computes flow-adjusted TWR from account value history and ledger updates.

Portfolio interactions are handled in `/hyperopen/src/hyperopen/portfolio/actions.cljs`, with action IDs registered in `/hyperopen/src/hyperopen/registry/runtime.cljs`, handler composition in `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`, and collaborator mapping in `/hyperopen/src/hyperopen/runtime/collaborators.cljs`.

Candle fetching currently flows through `:effects/fetch-candle-snapshot` and stores raw rows by coin and interval. Effect argument contracts are validated in `/hyperopen/src/hyperopen/schema/contracts.cljs`. Runtime ordering guarantees for heavy effects are enforced in `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`.

For this plan, “benchmark return series” means cumulative percent returns derived from benchmark candle close prices, normalized so the first aligned point is `0%`, then compared against portfolio returns at matching timestamps.

## Plan of Work

First, extend portfolio UI action surface with benchmark-specific state transitions. Add benchmark selection/clearing actions and deterministic candle-request configuration by selected portfolio range. Selecting a benchmark will project the new benchmark coin into portfolio UI state before emitting the candle fetch effect, and clearing benchmark will remove it without network side effects.

Second, extend candle fetch plumbing so the existing effect can optionally fetch for a provided coin. This requires changes in runtime app effect adapter plumbing, fetch compatibility layer, and effect contracts so `:effects/fetch-candle-snapshot` accepts `:coin` in addition to the existing arguments.

Third, update returns VM modeling. Build benchmark options from asset-selector markets, parse benchmark candle series for the requested interval, convert close-price history into cumulative returns, and align that benchmark return curve to portfolio return timestamps by forward-filling the latest known candle close up to each portfolio point. Emit chart model data as explicit multi-series structure so the view can render strategy and benchmark lines in one coordinate system.

Fourth, update portfolio view rendering. Keep existing tabs and percent y-axis behavior, add benchmark selector controls visible only on `Returns`, and render one SVG path per series with deterministic colors and legend labels. Ensure class/style representation follows UI policy constraints.

Fifth, extend tests across affected layers. Cover action normalization and emitted effects, effect contract updates, effect-order policy updates, VM benchmark conversion/alignment logic, and view rendering for benchmark controls and dual-line output.

Finally, run required repository validation gates and capture outcomes in this plan.

## Concrete Steps

1. Edit `/hyperopen/src/hyperopen/portfolio/actions.cljs` to add benchmark state actions and range-to-candle request mapping, then wire benchmark fetch emission with projection-first ordering.

2. Edit `/hyperopen/src/hyperopen/schema/contracts.cljs`, `/hyperopen/src/hyperopen/runtime/app_effects.cljs`, `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`, and `/hyperopen/src/hyperopen/api/fetch_compat.cljs` to support optional `:coin` in candle fetch effects.

3. Edit runtime registration and collaborator wiring (`/hyperopen/src/hyperopen/runtime/collaborators.cljs`, `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`, `/hyperopen/src/hyperopen/registry/runtime.cljs`, and `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`) so new actions are registered and covered by effect-order contract where applicable.

4. Edit `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` to emit benchmark options, selected benchmark metadata, and multi-series returns chart output with timestamp-synced benchmark return points.

5. Edit `/hyperopen/src/hyperopen/views/portfolio_view.cljs` to render benchmark selector controls on returns tab, legend entries, and multi-path chart output.

6. Update tests in:
   - `/hyperopen/test/hyperopen/portfolio/actions_test.cljs`
   - `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`
   - `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`
   - `/hyperopen/test/hyperopen/schema/contracts_test.cljs`
   - `/hyperopen/test/hyperopen/runtime/validation_test.cljs`
   - `/hyperopen/test/hyperopen/runtime/collaborators_test.cljs` (if collaborator map keys change)

7. Run validation commands from `/Users//projects/hyperopen`:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance is met when all of the following are true:

1. `Returns` tab includes a searchable benchmark selector populated from asset-selector symbols.
2. Suggestions are deterministically sorted by descending open interest.
3. Selecting one or more benchmarks adds removable chips and triggers candle fetch for each selected benchmark coin without mutating active trading asset.
4. Each benchmark chart line is derived from benchmark prices converted to cumulative percent returns.
5. Benchmark points are timestamp-aligned to portfolio returns points; all lines share x coordinates.
6. Returns chart renders strategy plus selected benchmarks with clear legend labels and percent axis labels.
7. Removing/clearing benchmarks removes their lines while preserving the strategy line.
8. Existing account value and pnl chart behavior remains unchanged.
9. Required validation gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

Manual scenario to verify in running app:

- Open `/portfolio`.
- Select `Returns` tab.
- Search and add one or more benchmarks from selector suggestions.
- Confirm benchmark chips appear and legend shows all visible series.
- Remove a chip and confirm the matching benchmark line is removed.
- Change summary range from `30D` to `All-time` and verify benchmark updates and remains aligned with returns timeline.

## Idempotence and Recovery

All code changes are additive and can be re-run safely. Re-running tests and validation gates is safe.

If benchmark fetch logic introduces regressions, recovery path is to keep benchmark UI projection state but temporarily disable benchmark fetch emission and series rendering while preserving existing single-series returns behavior.

## Artifacts and Notes

Primary code targets:

- `/hyperopen/src/hyperopen/portfolio/actions.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
- `/hyperopen/src/hyperopen/views/portfolio_view.cljs`
- `/hyperopen/src/hyperopen/runtime/app_effects.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
- `/hyperopen/src/hyperopen/api/fetch_compat.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`
- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/runtime/registry_composition.cljs`
- `/hyperopen/src/hyperopen/registry/runtime.cljs`

No new external dependencies are expected.

## Interfaces and Dependencies

Interfaces that must exist at completion:

- `hyperopen.portfolio.actions/select-portfolio-returns-benchmark` to set benchmark and emit benchmark candle fetch effect.
- `hyperopen.portfolio.actions/clear-portfolio-returns-benchmark` to remove benchmark selection.
- `:effects/fetch-candle-snapshot` accepting optional `:coin` argument while preserving existing `:interval` and `:bars` behavior.
- Portfolio VM chart model including multiple line series for returns (strategy + optional benchmark), with stable keys/labels and path strings.

Existing interfaces that must remain stable:

- `:actions/select-portfolio-chart-tab` payload semantics.
- Existing active-asset chart fetch behavior when `:coin` is not provided.
- Portfolio summary selectors and tabs.

Plan revision note: 2026-02-26 15:45Z - Initial benchmark-overlay ExecPlan authored after auditing portfolio, asset-selector, candle fetch, contract, and runtime wiring code paths.
Plan revision note: 2026-02-26 16:02Z - Marked implementation complete, documented deterministic benchmark-option ordering and final validation outcomes.
Plan revision note: 2026-02-26 17:24Z - Revised scope and implementation notes for searchable multi-select benchmark chips sorted by open interest and validated final gates post-redesign.
