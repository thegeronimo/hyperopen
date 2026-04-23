# Build Portfolio Optimizer V1 Inside the Existing Portfolio Route Family

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/docs/PLANS.md`, with the detailed ExecPlan template in `/hyperopen/.agents/PLANS.md`, and must be maintained in accordance with those files. It is self-contained so a future contributor can execute the work from this file alone without needing prior chat history or the superseded draft plan.

Tracked issue: `hyperopen-oj0n` ("Rewrite portfolio optimizer implementation plan as canonical ExecPlan").

## Purpose / Big Picture

After this work, a desktop user can stay inside HyperOpen's existing `/portfolio` route family, open `/portfolio/optimize`, create or load an optimization scenario, choose a separate objective, return model, and risk model, run the optimizer off the main thread, inspect signed target exposure and diagnostics, save the scenario locally, preview the rebalance with fees and slippage estimates, and execute supported rows through the current order stack. A perp-only rebalance can reach a fully executed state. A mixed scenario that contains blocked spot rows or failed rows must remain honest and land in `:partially-executed` with recovery controls instead of pretending V1 is preview-only.

The observable proof is a routed workflow. `/portfolio/optimize` lists local scenarios. `/portfolio/optimize/new` opens a power-user setup workspace aligned to the current portfolio tearsheet. `/portfolio/optimize/:scenario-id` loads the saved scenario, preserves the last successful result during reruns, exposes stale-state explicitly, shows frontier and diagnostics, and supports confirm-and-execute for rows the current trading stack can truly submit. Tracking is available for scenarios that have reached `:executed` or `:partially-executed`.

## Progress

- [x] (2026-04-23 17:53Z) Created and claimed `bd` issue `hyperopen-oj0n` for promoting the optimizer plan into the repo's canonical ExecPlan flow.
- [x] (2026-04-23 17:53Z) Audited the existing `/portfolio` route family, route module loader, portfolio worker, tearsheet views, route query state, account bootstrap seams, IndexedDB helper, trading submit stack, and order request builders.
- [x] (2026-04-23 17:53Z) Audited the attached design pack in `/Users/barry/Downloads/hyperopen_portfolio_optimizer_design_pack`, including `MANIFEST.md` and the wireframe and mid-fi boards mapped to `/portfolio/optimize`, `/portfolio/optimize/new`, and `/portfolio/optimize/:scenario-id`.
- [x] (2026-04-23 17:53Z) Rewrote the previous noncanonical draft into this active ExecPlan and folded in the contract corrections from the review: real execution semantics, scenario lifecycle, mandatory constraints, default return model, tracking naming, and solver uncertainty.
- [x] (2026-04-23 18:08Z) Ran `npm run lint:docs` after the rewrite to verify that the canonical ExecPlan passed the repository's documentation check.
- [x] (2026-04-23 18:22Z) Addressed review findings by making max asset weight an explicit contract, embedding the visual requirements so the plan is self-contained, replacing older product read-only wording with repo-facing Spectate Mode terminology, and removing the obsolete redirect file.
- [x] (2026-04-23 20:53Z) Implemented the first Phase 1 route/query-state foundation: `/portfolio/optimize`, `/portfolio/optimize/new`, and one-segment `/portfolio/optimize/:scenario-id` parsing; optimizer-owned query keys; optimizer route-query dispatch; and route-module coverage that keeps optimizer paths in the existing portfolio module.
- [x] (2026-04-23 20:53Z) Ran focused route/query-state validation with `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.routes-test --test=hyperopen.portfolio.optimizer.query-state-test --test=hyperopen.route-query-state-test --test=hyperopen.route-modules-test`; after restoring missing lockfile dependencies with `npm install`, the focused suite passed with 21 tests and 52 assertions.
- [x] (2026-04-23 21:01Z) Added the optimizer bounded-context boundary file, a non-view `current-portfolio` application seam, and a portfolio-shell delegate with scaffold optimizer index/workspace views so optimizer routes no longer fall through to the legacy tearsheet.
- [x] (2026-04-23 21:01Z) Ran focused optimizer foundation validation with 27 tests and 85 assertions, then ran the existing legacy portfolio view regression slice with 20 tests and 254 assertions; both passed with zero failures and zero warnings.
- [x] (2026-04-23 21:08Z) Completed the remaining Phase 1 infrastructure foundations: optimizer IndexedDB store and EDN-preserving persistence wrapper, dedicated `:portfolio-optimizer-worker` Shadow target and package scripts, and account bootstrap predicate correction so optimizer routes stay eager instead of inheriting the legacy performance-tab deferral.
- [x] (2026-04-23 21:08Z) Ran focused Phase 1 validation with 46 tests and 166 assertions plus `npx shadow-cljs --force-spawn compile portfolio-optimizer-worker`; all passed with zero failures and zero warnings.
- [x] (2026-04-23 22:13Z) Completed the solver spike harness and recorded ADR 0027 selecting a worker-isolated OSQP adapter as the first production solver path, with quadprog retained as fallback and parity oracle.
- [x] (2026-04-23 23:02Z) Implemented Phase 3 data assembly seams: arbitrary-universe history request planning and client, common-calendar return alignment, per-perp funding carry summaries, Black-Litterman prior source resolution, orderbook subscription planning, and engine request assembly.
- [x] (2026-04-23 23:02Z) Ran focused Phase 3 validation with `node out/test.js --test=hyperopen.portfolio.optimizer.application.history-loader-test --test=hyperopen.portfolio.optimizer.infrastructure.history-client-test --test=hyperopen.portfolio.optimizer.infrastructure.prior-data-test --test=hyperopen.portfolio.optimizer.application.orderbook-loader-test --test=hyperopen.portfolio.optimizer.application.request-builder-test`; 12 tests and 55 assertions passed with zero failures and zero errors.
- [x] (2026-04-23) Added the first Phase 4 pure-domain engine slice: numeric helpers, expected return estimation with funding decomposition, sample and diagonal-shrinkage risk models, Black-Litterman posterior math, constraint encoding, frontier point selection, diagnostics, and long-only/signed-aware dust weight cleaning.
- [x] (2026-04-23) Ran focused Phase 4 validation with `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.domain.returns-test --test=hyperopen.portfolio.optimizer.domain.risk-test --test=hyperopen.portfolio.optimizer.domain.black-litterman-test --test=hyperopen.portfolio.optimizer.domain.constraints-test --test=hyperopen.portfolio.optimizer.domain.frontier-test --test=hyperopen.portfolio.optimizer.domain.diagnostics-test --test=hyperopen.portfolio.optimizer.domain.weight-cleaning-test`; 19 tests and 61 assertions passed with zero failures and zero warnings.
- [x] (2026-04-23) Added the second Phase 4 pure-domain seam: solver-neutral objective-to-QP/frontier plan construction, constraint-presolve propagation, target-return feasibility checks, and rebalance preview row shaping with ready, blocked spot, missing-price, and tolerance states.
- [x] (2026-04-23) Ran focused Phase 4 objective/rebalance validation with `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.domain.objectives-test --test=hyperopen.portfolio.optimizer.domain.rebalance-test --test=hyperopen.portfolio.optimizer.domain.constraints-test`; 10 tests and 49 assertions passed with zero failures and zero warnings.
- [x] (2026-04-23) Added the Phase 4 application engine runner seam: it assembles return and risk models, applies Black-Litterman when selected, encodes constraints, builds the solver-neutral plan, invokes an injected solver, selects frontier results, cleans weights, computes diagnostics, and builds rebalance preview output.
- [x] (2026-04-23) Ran focused Phase 4 engine validation with `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.application.engine-test --test=hyperopen.portfolio.optimizer.domain.objectives-test --test=hyperopen.portfolio.optimizer.domain.rebalance-test`; 9 tests and 57 assertions passed with zero failures and zero warnings.
- [x] (2026-04-23) Added Phase 4 package adapter foundations for `osqp@0.0.2` and `quadprog@1.6.1`. The adapter keeps package imports in `portfolio.optimizer.infrastructure`, solves the current long-only direct QP shape through quadprog synchronously and OSQP asynchronously, and returns structured `:unsupported` for split-variable gross-exposure constraints until that expansion is implemented.
- [x] (2026-04-23) Ran focused solver-adapter validation with `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.infrastructure.solver-adapter-test`; 4 tests and 13 assertions passed with zero failures and zero warnings.
- [x] Implement the Phase 1 route, query-state, portfolio shell delegation, current-holdings snapshot, account bootstrap participation, worker target registration, and IndexedDB scenario store/versioning foundations.
- [x] Implement the Phase 3 arbitrary-universe history, funding, orderbook preview planning, BL prior, and request-builder foundations.
- [ ] Implement split-variable gross/net/turnover solver expansion, fixture parity coverage, worker bridge, setup/results UI, execution path, and tracking flow.

## Surprises & Discoveries

- Observation: HyperOpen already routes every `/portfolio` child path through one lazy route module, so the optimizer should extend the existing route family instead of creating a second shell.
  Evidence: `src/hyperopen/portfolio/routes.cljs` currently distinguishes only `:page`, `:trader`, `:other`, and `src/hyperopen/route_modules.cljs` maps all portfolio paths to `:portfolio`.

- Observation: The current portfolio surface is still a single shell view, which makes in-shell delegation the correct seam.
  Evidence: `src/hyperopen/views/portfolio_view.cljs` exports one `route_view` and renders the portfolio header, summary grid, and account table directly.

- Observation: The repository already has a worker precedent for portfolio analytics, so a dedicated optimizer worker fits the current architecture.
  Evidence: `src/hyperopen/portfolio/worker.cljs` handles portfolio metrics, `src/hyperopen/portfolio/application/metrics_bridge.cljs` owns the bridge, and `shadow-cljs.edn` plus `package.json` already build and watch `portfolio-worker`.

- Observation: Arbitrary-universe history loading is not wired at the application layer even though the lower market endpoint can already request arbitrary coins.
  Evidence: the optimizer draft initially proposed a new history loader because `src/hyperopen/api/fetch_compat.cljs` is active-asset-centric, while `src/hyperopen/api/endpoints/market.cljs` already supports arbitrary `coin` request payloads.

- Observation: Spot order execution is still explicitly blocked by the current submit policy, so V1 cannot silently downgrade to preview-only or claim universal execution.
  Evidence: `src/hyperopen/trading/submit_policy.cljs` emits `:spot-read-only` with the exact message `"Spot trading is not supported yet."`

- Observation: IndexedDB support in this repo is key-value only, so scenario history requires an explicit index record rather than store iteration.
  Evidence: `src/hyperopen/platform/indexed_db.cljs` exposes `get-json!`, `put-json!`, and `delete-key!`, but no cursor or list scan helper.

- Observation: Several items that the previous draft treated as open questions are already fixed by the product contract and should not be reopened during implementation.
  Evidence: the review corrected the draft on scenario lifecycle, read-only analysis behavior, Black-Litterman prior posture, mandatory V1 constraints, default return-model framing, and the rejection of `Implemented` as a runtime status.

- Observation: `osqp@0.0.2` can solve the benchmark fixtures much faster than JS alternatives, but it must be isolated behind the optimizer worker and configured explicitly.
  Evidence: `node tools/optimizer/solver_spike_benchmark.mjs --external-root=/tmp/hyperopen-solver-spike.x2HSY7/packages --warmup=1 --runs=3` solved all 36 OSQP runs with mean 0.57 ms, max 0.98 ms, and max constraint violation 1.39e-17 after setting `verbose: false`, tighter tolerances, and polish.

- Observation: `quadprog@1.6.1` is precise and small enough to keep as a fallback or parity oracle, but it is slower than OSQP on the same deterministic fixtures.
  Evidence: the same solver spike solved all 36 quadprog runs with mean 14.59 ms, max 63.24 ms, and max constraint violation 1.39e-17.

- Observation: the internal projected-gradient spike baseline is deterministic and useful for harness tests, but it is not suitable as the production V1 QP solver.
  Evidence: the internal baseline solved all 36 fixtures with mean 47.78 ms and max 208.85 ms, and it would require substantially more custom work to match package solver infeasibility and frontier behavior.

- Observation: adding `osqp@0.0.2` and `quadprog@1.6.1` did not add transitive runtime dependencies, but `npm install` still reports the repository's current npm audit state as 11 findings.
  Evidence: `npm view osqp@0.0.2 license version dist.unpackedSize dependencies --json` reports Apache-2.0, version 0.0.2, 137705 unpacked bytes, and no dependencies; `npm view quadprog@1.6.1 license version dist.unpackedSize dependencies --json` reports MIT, version 1.6.1, 33978 unpacked bytes, and no dependencies; `npm install --save osqp@0.0.2 quadprog@1.6.1` completed but printed `11 vulnerabilities (3 moderate, 8 high)`.

- Observation: the current market endpoint already supports arbitrary candle and market funding requests, so Phase 3 can stay dependency-injected instead of adding a new global API facade immediately.
  Evidence: `src/hyperopen/api/endpoints/market.cljs` exposes `request-candle-snapshot!` and `request-market-funding-history!` that both accept explicit `coin`; `src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs` now wraps those semantics behind optimizer-owned deps.

- Observation: orderbook preview loading should be planned as subscription effects, not direct websocket mutation.
  Evidence: existing orderbook runtime owns websocket state in `src/hyperopen/websocket/orderbook.cljs`, while `src/hyperopen/portfolio/optimizer/application/orderbook_loader.cljs` only returns `[:effects/subscribe-orderbook coin]` effects and live/stale/fallback cost context labels.

## Decision Log

- Decision: Keep the optimizer inside the existing `/portfolio` route family and lazy route module instead of creating a second shell or a new top-level route module for V1.
  Rationale: the current route parser and route-module loader already treat `/portfolio` as one bounded surface, and `views/portfolio_view.cljs` is the right delegation seam.
  Date/Author: 2026-04-23 / Codex

- Decision: Treat V1 as an execution-capable feature, not as preview-only.
  Rationale: the product contract includes order generation, confirm-and-execute, execution states, partial execution, and failure recovery. The plan must therefore support real execution where the existing trading stack allows it, while blocked spot rows remain explicit and honest.
  Date/Author: 2026-04-23 / Codex

- Decision: Use scenario states `:draft`, `:computed`, `:saved`, `:executed`, `:partially-executed`, and `:archived`. Do not use `:implemented`.
  Rationale: the review identified `Implemented` as a design-pack artifact that would create the wrong persistence and UI contract. Tracking is a surface layered on top of executed states, not a replacement status.
  Date/Author: 2026-04-23 / Codex

- Decision: Keep Black-Litterman in the return-model layer and separate it from the objective layer, with market-cap prior preferred and current-portfolio fallback clearly labeled in diagnostics.
  Rationale: the attached design pack and the contract correction both require this split, and the repo currently lacks a first-class market-cap seam.
  Date/Author: 2026-04-23 / Codex

- Decision: Default the return model to Historical Mean for first-time scenarios and default the risk model to Ledoit-Wolf shrinkage.
  Rationale: the prior draft drifted to EW Mean as the default without marking it as a deliberate product divergence. The implementation source of truth must return to the stated V1 default.
  Date/Author: 2026-04-23 / Codex

- Decision: Keep the solver choice open through a dedicated prototype milestone and ADR instead of assuming an internal CLJS solver.
  Rationale: the prior draft turned a difficult numerical problem into a hidden default. This repo has no existing QP stack, and the product appendix leaves multiple viable approaches open. A benchmarked spike is required before blessing one.
  Date/Author: 2026-04-23 / Codex

- Decision: Rename the tracking metric that measures distance from the saved target to `:weight-drift-rms` or `:distance-to-target`, and reserve the term "tracking error" for true benchmark-relative excess-return volatility only.
  Rationale: the product surface is about drift from the target portfolio, not benchmark-relative tracking error.
  Date/Author: 2026-04-23 / Codex

- Decision: Use the repo term Spectate Mode for the read-only account mode, even where product notes used older read-only terminology.
  Rationale: the implementation should point engineers at `hyperopen.account.context` and the current mutations-blocked path, not at nonexistent identifiers.
  Date/Author: 2026-04-23 / Codex

- Decision: Store optimizer scenarios, drafts, and tracking records in the optimizer IndexedDB store as EDN-encoded envelopes rather than raw `clj->js` JSON payloads.
  Rationale: the low-level IndexedDB helper is JSON-oriented and does not preserve keyword values or string map keys through `clj->js` and `js->clj :keywordize-keys`. Scenario lifecycle status, objective ids, and user-provided string-key maps must roundtrip exactly.
  Date/Author: 2026-04-23 / Codex

- Decision: Select a worker-isolated OSQP adapter as the first production solver path for V1, retain quadprog as fallback and fixture parity oracle, and reject a custom in-repo QP solver for V1.
  Rationale: ADR 0027 records the benchmark. OSQP was fastest and satisfied deterministic fixture constraints when configured with quiet high-precision settings. quadprog was precise but slower. The internal projected-gradient baseline would turn V1 into a numerical-methods project.
  Date/Author: 2026-04-23 / Codex

- Decision: Keep Phase 3 history and prior loading dependency-injected behind optimizer-owned seams rather than adding app-wide effects before the engine contract stabilizes.
  Rationale: the repo already has explicit arbitrary-coin market gateway functions, and the next engine phase needs deterministic assembled inputs more than UI-triggered fetch effects. Dependency injection keeps tests pure and leaves runtime effect wiring for the worker bridge phase.
  Date/Author: 2026-04-23 / Codex

- Decision: Keep Phase 4 estimator, constraint, frontier, diagnostic, and cleaning logic pure and dependency-free, with package solver integration deferred to the dedicated runner and worker bridge.
  Rationale: expected returns, covariance, Black-Litterman, infeasibility presolve, and diagnostics are deterministic domain math that should stay testable without browser workers or JS solver packages. Solver adapter failures should not contaminate these core contracts.
  Date/Author: 2026-04-23 / Codex

## Outcomes & Retrospective

This work promotes the planning into the repository's canonical active ExecPlan flow, removes the misleading parts of the earlier draft, and starts implementation through the route, persistence, worker-target, solver-spike, data-assembly, and pure-domain math foundations. The plan treats the execution contract honestly, turns prior "owner questions" that were already answered into requirements, fixes the scenario lifecycle, makes the UI acceptance criteria concrete, and resolves the main solver uncertainty through ADR 0027. Overall complexity is reduced because the route, state, lifecycle, UI, and solver contracts are now explicit, while the remaining complexity is acknowledged as real implementation work rather than hidden scope drift.

## Context and Orientation

The working directory is `/Users/barry/.codex/worktrees/d394/hyperopen`.

HyperOpen is a Shadow CLJS app with one shared application state tree and pure action namespaces that return effect vectors. Action bindings live in `src/hyperopen/schema/runtime_registration/*.cljs`. Runtime effect adapters live in `src/hyperopen/runtime/effect_adapters/*.cljs`. Shareable route query state is coordinated centrally through `src/hyperopen/route_query_state.cljs`, which delegates to surface-specific query-state namespaces such as `src/hyperopen/portfolio/query_state.cljs`. This means the optimizer should follow the same pattern: pure actions, explicit effect ids, route-aware query-state ownership, and browser-specific work confined to infrastructure and runtime adapter namespaces.

The current portfolio route family is small but important. `src/hyperopen/portfolio/routes.cljs` recognizes `/portfolio`, `/portfolio/trader/<address>`, and otherwise collapses every other `/portfolio/...` path to `:other`. `src/hyperopen/route_modules.cljs` lazy-loads a single `:portfolio_route` module for every portfolio path. `src/hyperopen/views/portfolio_view.cljs` is still the only route-family view and renders the current tearsheet surface directly. This is why the optimizer must be implemented as an in-shell expansion of the existing portfolio family instead of as a parallel app.

The repo already contains portfolio analytics and worker seams worth reusing. `src/hyperopen/portfolio/worker.cljs` and `src/hyperopen/portfolio/application/metrics_bridge.cljs` show how a browser worker is created, messaged, and normalized back into state. The optimizer should copy that shape, but it should get its own worker target so it does not bloat or destabilize the current metrics worker.

The account and portfolio read models are not yet cleanly extracted. Current balances, positions, and equity ingredients are still partly owned by view namespaces such as `src/hyperopen/views/account-info/projections/balances.cljs`, `src/hyperopen/views/account-info/projections/positions.cljs`, and `src/hyperopen/views/account_equity_view.cljs`. The optimizer cannot depend on `hyperopen.views.*`, so one of the first milestones is to extract or bless a non-view seam for holdings, capital base, and cross-margin account context.

The current account bootstrap path is route-aware. `src/hyperopen/account/surface_service.cljs` and `src/hyperopen/startup/runtime.cljs` decide which account surfaces are fetched first and which deferred fetches run after paint. The optimizer routes must participate in that route-aware bootstrap instead of bolting on their own parallel loading pipeline.

The trading stack already exposes useful seams. `src/hyperopen/trading/submit_policy.cljs` is the canonical pure submit gate, `src/hyperopen/api/gateway/orders/commands.cljs` builds normalized order requests, `src/hyperopen/domain/trading/market.cljs` computes order summaries, fees, and slippage estimates from market context and orderbook state, `src/hyperopen/order/actions.cljs` handles single-order confirmation, and `src/hyperopen/runtime/effect_adapters/order.cljs` plus `src/hyperopen/order/effects.cljs` submit orders and refresh account surfaces. The optimizer should reuse these primitives where they fit, but it needs its own batch confirmation and execution orchestration because the current confirmation modal is one-request oriented.

The current local persistence layer is intentionally small. `src/hyperopen/platform/indexed_db.cljs` supports keyed puts and gets only. That means scenario history cannot rely on object-store scans. V1 persistence must be built around explicit index records keyed by wallet address, with scenario records and tracking series stored separately.

The design pack in `/Users/barry/Downloads/hyperopen_portfolio_optimizer_design_pack` was inspected while authoring this plan, but this ExecPlan must stand alone. The UI requirements from those images are embedded below: the optimizer is a dense desktop workspace with a persistent left rail, matte near-black portfolio background, thin one-pixel separators, compact uppercase labels, numeric tables, restrained cyan or teal accents, explicit stale and infeasible states, and side-by-side current-versus-target analysis. The setup route has three zones: left rail for scenario navigation and section status, center panels for universe, objective, return model, risk model, constraints, and execution assumptions, and a right rail for freshness, warnings, and trust diagnostics. The results route has a top scenario header and stale banner area, a summary metrics strip, a signed current-versus-target exposure table, an efficient frontier panel, a diagnostics and trust panel, and a rebalance preview action that opens an execution-grade preview. The tracking surface remains inside the scenario workspace and shows target drift, realized-versus-expected return, execution status, and saved snapshots.

The PDD corrections supplied in review are embedded as explicit requirements in this plan, so the implementer does not need the prior chat transcript or the previous draft to know the intended V1 contract.

### Executive Summary

V1 includes three routes inside the existing portfolio shell. `/portfolio/optimize` is the scenario board. `/portfolio/optimize/new` is the setup workspace for an unsaved draft. `/portfolio/optimize/:scenario-id` is the saved-scenario workspace that preserves the last successful run, exposes stale-state, supports rerun, shows diagnostics, shows rebalance preview, and hosts confirm-and-execute plus tracking for executed scenarios.

V1 includes four objectives: Minimum Variance, Max Sharpe, Target Volatility, and Target Return. V1 includes three return models: Historical Mean, EW Mean, and Black-Litterman. Black-Litterman is a return-model mode, not a peer objective. V1 also includes a separate risk-model control surface. The default return model is Historical Mean. The default covariance estimator is Ledoit-Wolf shrinkage.

V1 includes actual rebalance order generation, cost and slippage preview, confirm-and-execute, execution recovery, and scenario states that distinguish `:saved`, `:executed`, and `:partially-executed`. Because the present trading stack blocks spot submit, V1 must execute supported rows honestly and mark blocked spot rows explicitly. V1 must not pretend that the feature is preview-only, and it must not pretend that unsupported rows were executed.

V1 excludes cloud persistence, background monitoring jobs, mobile-first authoring, factor models, tax lots, optimization objectives beyond the four listed above, and any second visual language or second app shell. Spectate Mode is analysis-only: it may load and save scenarios locally but must not permit execution.

Recommended sequencing is straightforward. First, extract the non-view holdings and capital-base seam and extend the route family cleanly. Next, add arbitrary-universe history loading and run the solver spike milestone. Then implement the worker-backed domain core and the setup UI. Only after the engine and results contracts are stable should scenario persistence, rebalance execution, and tracking land.

### Repo Reconnaissance Summary

The current repo already provides the route-family seam, worker precedent, query-state pattern, indexed persistence helper, order submission primitives, fee and slippage calculations, account bootstrap rules, and a visual system that the optimizer should inherit. The current `/portfolio` surface already behaves like a route-family shell even though it only renders one view today. The optimizer should reuse that.

What is missing is equally important. There is no optimizer bounded context, no scenario persistence model, no arbitrary-universe history orchestrator, no solver or matrix layer, no scenario lifecycle contract, no batch rebalance execution path, and no extracted holdings/capital-base seam that avoids `hyperopen.views.*`. There is also no existing market-cap data provider. That gap matters for Black-Litterman.

The biggest repo-to-feature mismatches are the view-owned account math, the active-asset bias in market-history loading, the key-value-only IndexedDB helper, the one-request confirmation modal, and the hard stop on spot submit. The plan below treats each of those mismatches as a first-class implementation phase instead of assuming they solve themselves.

## Proposed Architecture

### Route Structure

Extend `src/hyperopen/portfolio/routes.cljs` so `parse-portfolio-route` recognizes three optimizer kinds in addition to the existing page and trader variants:

    {:kind :optimize-index    :path "/portfolio/optimize"}
    {:kind :optimize-new      :path "/portfolio/optimize/new"}
    {:kind :optimize-scenario :path "/portfolio/optimize/<scenario-id>" :scenario-id "..."}

Keep `portfolio-route?` true for all three kinds so the top navigation and lazy route module behavior continue to work. Add helpers for `portfolio-optimize-route?`, `portfolio-optimize-index-path`, `portfolio-optimize-new-path`, and `portfolio-optimize-scenario-path`. Leave all optimizer paths inside the existing `:portfolio_route` lazy module for V1. If bundle analysis later shows the portfolio route module becoming too heavy, split the optimizer into its own route module in a follow-up plan rather than prematurely now.

`src/hyperopen/views/portfolio_view.cljs` should become the portfolio route-family delegate. It should switch between the legacy tearsheet view and a new optimizer route-family view namespace, but it should preserve the existing outer shell, background treatment, and shared account context behavior.

### Module and Namespace Layout

Add a new bounded context at `src/hyperopen/portfolio/optimizer/BOUNDARY.md`. This context owns optimizer state normalization, scenario lifecycle, worker request and response contracts, rebalance preview shaping, execution orchestration, and diagnostics. It does not own the global account model, orderbook runtime, or low-level exchange signing.

Use the following namespace layout:

    src/hyperopen/portfolio/optimizer/actions.cljs
    src/hyperopen/portfolio/optimizer/query_state.cljs

    src/hyperopen/portfolio/optimizer/domain/universe.cljs
    src/hyperopen/portfolio/optimizer/domain/returns.cljs
    src/hyperopen/portfolio/optimizer/domain/risk.cljs
    src/hyperopen/portfolio/optimizer/domain/black_litterman.cljs
    src/hyperopen/portfolio/optimizer/domain/constraints.cljs
    src/hyperopen/portfolio/optimizer/domain/objectives.cljs
    src/hyperopen/portfolio/optimizer/domain/frontier.cljs
    src/hyperopen/portfolio/optimizer/domain/weight_cleaning.cljs
    src/hyperopen/portfolio/optimizer/domain/diagnostics.cljs
    src/hyperopen/portfolio/optimizer/domain/rebalance.cljs

    src/hyperopen/portfolio/optimizer/application/current_portfolio.cljs
    src/hyperopen/portfolio/optimizer/application/history_loader.cljs
    src/hyperopen/portfolio/optimizer/application/orderbook_loader.cljs
    src/hyperopen/portfolio/optimizer/application/request_builder.cljs
    src/hyperopen/portfolio/optimizer/application/run_bridge.cljs
    src/hyperopen/portfolio/optimizer/application/execution.cljs
    src/hyperopen/portfolio/optimizer/application/tracking.cljs
    src/hyperopen/portfolio/optimizer/application/scenario_summary.cljs

    src/hyperopen/portfolio/optimizer/infrastructure/persistence.cljs
    src/hyperopen/portfolio/optimizer/infrastructure/worker_client.cljs
    src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs
    src/hyperopen/portfolio/optimizer/infrastructure/prior_data.cljs

    src/hyperopen/portfolio/optimizer/worker.cljs
    src/hyperopen/runtime/effect_adapters/portfolio_optimizer.cljs

    src/hyperopen/views/portfolio/optimize/index_view.cljs
    src/hyperopen/views/portfolio/optimize/workspace_view.cljs
    src/hyperopen/views/portfolio/optimize/vm.cljs
    src/hyperopen/views/portfolio/optimize/setup_panels.cljs
    src/hyperopen/views/portfolio/optimize/results_panels.cljs
    src/hyperopen/views/portfolio/optimize/frontier_chart.cljs
    src/hyperopen/views/portfolio/optimize/diagnostics_panel.cljs
    src/hyperopen/views/portfolio/optimize/rebalance_preview.cljs
    src/hyperopen/views/portfolio/optimize/execution_modal.cljs
    src/hyperopen/views/portfolio/optimize/tracking_panel.cljs

The optimizer domain is pure. Application namespaces convert repo state and API payloads into domain requests and outputs. Infrastructure namespaces talk to IndexedDB, workers, browser APIs, or external data sources. Views render only the state prepared for them and do not perform optimizer math.

### State Model

Keep durable optimizer data under `[:portfolio :optimizer ...]` and transient view state under `[:portfolio-ui :optimizer ...]`, following current portfolio conventions. The scenario draft must always be separate from the last successful result and from the saved scenario record.

Use this high-level shape:

    {:portfolio
     {:optimizer
      {:scenario-index {:loaded? false
                        :by-id {}
                        :ordered-ids []}
       :active-scenario {:loaded-id nil
                         :saved-record nil}
       :last-successful-run {:request-signature nil
                             :result nil
                             :computed-at-ms nil}
       :run-state {:status :idle
                   :run-id nil
                   :error nil}
       :rebalance-preview {:status :idle
                           :preview nil
                           :error nil}
       :execution {:status :idle
                   :attempt nil
                   :history []}
       :tracking {:by-scenario-id {}
                  :loaded? false}}}
     :portfolio-ui
     {:optimizer
      {:draft nil
       :dirty? false
       :stale? false
       :workspace-panel :setup
       :results-tab :allocation
       :diagnostics-tab :conditioning
       :list-filter :active
       :list-sort :updated-desc
       :execution-modal nil}}}

The saved scenario record is immutable until the user explicitly saves. The draft is the mutable working copy. The last successful result remains visible during reruns and must not be cleared merely because a new run started. `:stale?` means the visible result no longer matches either the draft inputs or the underlying account and market data signatures.

### Scenario Lifecycle

Use these scenario states:

    :draft
    :computed
    :saved
    :executed
    :partially-executed
    :archived

`:draft` means there is no successful run for the current working copy or the draft has diverged from the last successful run. `:computed` means the current working copy has a successful result that has not yet been saved. `:saved` means the scenario and its last successful run are persisted locally, but no execution has been recorded. `:executed` means every intended executable row was successfully submitted and no blocked rows remain. `:partially-executed` means some rows executed and some rows were blocked or failed. Tracking is not a status. It is a workspace surface that becomes meaningful once a scenario has reached `:executed` or `:partially-executed`.

### Main Thread Versus Worker

All heavy numerical work runs in a dedicated optimizer worker. That includes expected-return estimation, covariance estimation, Black-Litterman posterior calculation, frontier construction, sensitivity analysis, and constraint diagnostics. The main thread owns only draft normalization, obvious presolve validation, persistence bookkeeping, and view updates.

Add a new Shadow target and browser asset such as `/js/portfolio_optimizer_worker.js`. Do not fold this into `portfolio_worker.js`. The current portfolio worker is stable and narrow; the optimizer worker will have very different dependency and runtime characteristics.

### Numerical Layer and Solver Strategy

Use a small matrix helper for covariance, shrinkage, and Black-Litterman calculations. ADR 0027 selects a worker-isolated OSQP adapter as the first production solver path, with quadprog retained as a fallback and fixture parity oracle. Do not implement a custom in-repo constrained QP solver for V1.

Keep solver imports behind the optimizer worker and a small protocol so the main app bundle does not absorb numerical code. Minimum Variance and Target Return are direct QP solves. Max Sharpe should be selected from the efficient frontier or repeated return-tilted QP solves. Target Volatility should be implemented through efficient-frontier sweep and point selection rather than as a direct nonlinear QP objective.

The benchmarked decision used deterministic 20-, 40-, and 60-instrument universes across Minimum Variance, Max Sharpe, Target Return, and Target Volatility. OSQP solved all 36 measured runs with mean 0.57 ms and max 0.98 ms once configured with quiet high-precision settings. quadprog solved all 36 measured runs with mean 14.59 ms and max 63.24 ms. The internal projected-gradient harness remains only a deterministic spike baseline.

### Return Models, Risk Models, and Objective Plug-In Shape

The engine request contract must keep these concerns separate:

    {:universe ...
     :current-portfolio ...
     :return-model ...
     :risk-model ...
     :objective ...
     :constraints ...
     :execution-assumptions ...}

Return-model functions consume aligned histories and emit expected-return vectors plus decomposition metadata. Risk-model functions emit covariance matrices plus conditioning metadata. Objectives convert those into solver-ready target functions or constraints. Black-Litterman consumes the chosen covariance matrix and prior weights, then emits a posterior expected-return vector. It does not replace the risk model.

### Diagnostics

Diagnostics belong to the engine output, not to view-model re-derivation. Every successful run must emit machine-readable diagnostics covering covariance conditioning, shrinkage, active and binding constraints, effective diversification, gross and net exposure, turnover, data freshness, and sensitivity analysis. Failed or infeasible runs must emit structured explanations instead of generic error strings so the UI can point to the exact control or range that caused the issue.

### Scenario Persistence

Persist scenarios locally in IndexedDB through a new store name added to `src/hyperopen/platform/indexed_db.cljs`, but keep the records keyed because the helper does not scan stores. The persistence namespace should use explicit keys like:

    scenario-index::<wallet-address>
    scenario::<scenario-id>
    tracking::<scenario-id>
    draft::<wallet-address>

The scenario index record stores ordered ids, summary fields, and archive flags. Individual scenario records store the config, saved lifecycle status, last successful result, execution ledger, and last drift summary. Local storage may still hold tiny UI preferences such as list sort, diagnostics tab, and last opened scenario id, but not canonical scenario data.

### Rebalance Preview and Execution Integration

Rebalance preview is not just a delta table. It must produce execution-grade order intents, cost estimates, and blockers. Each row needs current exposure, target exposure, signed delta, estimated quantity, estimated fees, estimated slippage, execution eligibility, and an explicit blocker if it cannot be submitted. The preview summary must show cross-margin before-and-after context, including gross exposure, net exposure, residual cash, and estimated cost drag.

Execution reuses current trading primitives at the right level. Use `hyperopen.api.gateway.orders.commands/build-order-request` to build normalized order requests from optimizer row intents, use `hyperopen.trading.submit-policy/submit-policy` for pure gating, and reuse the lower-level exchange submission path in `hyperopen.api.trading/submit-order!` or extracted order-effect helpers so the optimizer does not duplicate signing or result classification. Do not overload the current single-order confirmation modal. Instead, add an optimizer-specific confirmation modal that summarizes all executable rows, all blocked rows, expected costs, and the exact partial-execution outcome if the user chooses to execute only the supported subset.

Spectate Mode must reuse the same read-only gating used elsewhere in the repo. Analysis and local saves remain allowed. Execution controls must disable through the existing mutations-blocked message path.

## File-By-File Touch List

### Existing Files To Modify

`package.json`
Add the optimizer worker target to watch, build, and compile commands.

`shadow-cljs.edn`
Add `:portfolio-optimizer-worker` as a browser target and ensure production builds emit `/js/portfolio_optimizer_worker.js`.

`src/hyperopen/portfolio/routes.cljs`
Extend route parsing and helper builders for `/portfolio/optimize`, `/portfolio/optimize/new`, and `/portfolio/optimize/:scenario-id`.

`src/hyperopen/route_query_state.cljs`
Teach the route-query-state entrypoint to dispatch optimizer query parameters separately from the current portfolio tearsheet query state.

`src/hyperopen/portfolio/query_state.cljs`
Likely unchanged for legacy portfolio controls, but read carefully so the new optimizer query-state namespace does not reuse or collide with existing keys.

`src/hyperopen/views/portfolio_view.cljs`
Turn the current portfolio view into a route-family delegate that chooses between the legacy tearsheet and optimizer route-family view.

`src/hyperopen/account/surface_service.cljs`
Add optimizer-route-aware bootstrap behavior so optimizer routes fetch the same account surfaces and funding history the current portfolio and trade routes rely on.

`src/hyperopen/startup/runtime.cljs`
Route-aware startup should recognize optimizer routes and schedule the right post-render fetches and subscriptions.

`src/hyperopen/platform/indexed_db.cljs`
Add the optimizer store name and bump the app IndexedDB version.

`src/hyperopen/schema/runtime_registration/portfolio.cljs`
Register optimizer actions and effect bindings alongside existing portfolio actions.

`src/hyperopen/schema/contracts/effect_args.cljs`
Add contracts for new optimizer effect payloads.

`src/hyperopen/runtime/effect_order_contract.cljs`
Place optimizer worker, persistence, orderbook, and execution effects in the correct ordering buckets so heavy work stays out of immediate projection phases.

`src/hyperopen/views/app_view.cljs`
Render the optimizer execution confirmation modal if the modal is attached at app-shell scope.

`src/hyperopen/api/gateway/orders/commands.cljs`
Possibly expose a smaller reusable seam or helper if the optimizer needs to build requests without copying trade-only plumbing.

`src/hyperopen/domain/trading/market.cljs`
Likely expose a reusable order-summary or fee/slippage seam for optimizer preview rows.

`src/hyperopen/order/effects.cljs`
Likely extract reusable submission outcome normalization or consolidated post-order refresh logic so batch execution can reuse the existing behavior without per-row UI duplication.

`src/hyperopen/trading/submit_policy.cljs`
Potentially expose a smaller pure seam if optimizer execution needs row-level submit checks without depending on trade-view-only wrappers.

`src/hyperopen/portfolio/BOUNDARY.md`
Mention the optimizer as a child bounded context and list the stable public seams.

### New Files With High Confidence

`src/hyperopen/portfolio/optimizer/BOUNDARY.md`
Defines ownership, public seams, and dependency rules for the optimizer bounded context.

`src/hyperopen/portfolio/optimizer/actions.cljs`
Pure actions for draft edits, run requests, save/load/archive, execution modal open and close, preview generation, execution start, execution recovery, and tracking refresh.

`src/hyperopen/portfolio/optimizer/query_state.cljs`
Owns optimizer shareable query keys such as list filter and sort, active results tab, diagnostics tab, and scenario workspace subview.

`src/hyperopen/portfolio/optimizer/application/current_portfolio.cljs`
Builds the non-view holdings and capital-base snapshot from account state.

`src/hyperopen/portfolio/optimizer/application/history_loader.cljs`
Loads and aligns arbitrary-universe candles and funding histories.

`src/hyperopen/portfolio/optimizer/application/orderbook_loader.cljs`
Loads or subscribes orderbook snapshots for rebalance-preview rows when cost estimation needs live depth.

`src/hyperopen/portfolio/optimizer/application/request_builder.cljs`
Normalizes the draft, histories, and current portfolio into the worker request contract.

`src/hyperopen/portfolio/optimizer/application/run_bridge.cljs`
Owns worker request ids, stale-response protection, rerun bookkeeping, and retention of the last successful result.

`src/hyperopen/portfolio/optimizer/application/execution.cljs`
Builds row-level order intents, derives execution gating, computes cost estimates, and shapes execution attempts plus recovery records.

`src/hyperopen/portfolio/optimizer/application/tracking.cljs`
Builds drift snapshots, predicted-versus-realized summaries, and tracking panel data.

`src/hyperopen/portfolio/optimizer/application/scenario_summary.cljs`
Builds compact list rows for `/portfolio/optimize`.

`src/hyperopen/portfolio/optimizer/infrastructure/persistence.cljs`
Reads and writes IndexedDB scenario, index, tracking, and draft records.

`src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs`
Fetches arbitrary-coin price and funding histories using existing market endpoints.

`src/hyperopen/portfolio/optimizer/infrastructure/prior_data.cljs`
Resolves Black-Litterman prior weights with market-cap-first and current-portfolio fallback behavior.

`src/hyperopen/portfolio/optimizer/infrastructure/worker_client.cljs`
Owns worker lifecycle and message normalization.

`src/hyperopen/portfolio/optimizer/worker.cljs`
Worker entrypoint for engine runs.

`src/hyperopen/runtime/effect_adapters/portfolio_optimizer.cljs`
Runtime adapter for optimizer effects: run, save, load, preview, execute, tracking refresh, and orderbook prefetch.

`src/hyperopen/views/portfolio/optimize/index_view.cljs`
Scenario board route view.

`src/hyperopen/views/portfolio/optimize/workspace_view.cljs`
Shared workspace shell for `/new` and `/:scenario-id`.

`src/hyperopen/views/portfolio/optimize/vm.cljs`
Optimizer view model.

`src/hyperopen/views/portfolio/optimize/setup_panels.cljs`
Universe, objective, return-model, risk-model, constraint, and execution-assumption panels.

`src/hyperopen/views/portfolio/optimize/results_panels.cljs`
Summary metrics, allocation comparison, return decomposition, and stale-state panels.

`src/hyperopen/views/portfolio/optimize/frontier_chart.cljs`
Efficient frontier chart with target selection interaction.

`src/hyperopen/views/portfolio/optimize/diagnostics_panel.cljs`
Diagnostics and infeasibility explanation surface.

`src/hyperopen/views/portfolio/optimize/rebalance_preview.cljs`
Execution-grade preview table and cross-margin summary.

`src/hyperopen/views/portfolio/optimize/execution_modal.cljs`
Batch confirmation and partial-execution disclosure modal.

`src/hyperopen/views/portfolio/optimize/tracking_panel.cljs`
Tracking, drift, and predicted-versus-realized panel.

### Likely Test Files

    test/hyperopen/portfolio/routes_test.cljs
    test/hyperopen/portfolio/optimizer/actions_test.cljs
    test/hyperopen/portfolio/optimizer/query_state_test.cljs
    test/hyperopen/portfolio/optimizer/application/current_portfolio_test.cljs
    test/hyperopen/portfolio/optimizer/application/history_loader_test.cljs
    test/hyperopen/portfolio/optimizer/application/run_bridge_test.cljs
    test/hyperopen/portfolio/optimizer/application/execution_test.cljs
    test/hyperopen/portfolio/optimizer/domain/returns_test.cljs
    test/hyperopen/portfolio/optimizer/domain/risk_test.cljs
    test/hyperopen/portfolio/optimizer/domain/black_litterman_test.cljs
    test/hyperopen/portfolio/optimizer/domain/constraints_test.cljs
    test/hyperopen/portfolio/optimizer/domain/frontier_test.cljs
    test/hyperopen/portfolio/optimizer/domain/diagnostics_test.cljs
    test/hyperopen/portfolio/optimizer/infrastructure/persistence_test.cljs
    test/hyperopen/views/portfolio/optimize/*_test.cljs
    tools/playwright/test/portfolio-optimize.spec.mjs

## Math and Engine Design

### Portfolio Representation for Unified Cross-Margin Spot and Perps

Use signed notional weights relative to a capital base. The capital base starts from the current account value produced by the extracted holdings seam and is reduced by any explicit cash reserve before optimization. Each risky instrument gets a signed target weight. Positive weights are long exposure. Negative weights are short exposure and are only legal for shortable instruments, which in V1 means perps.

Do not put cash into the covariance matrix as a zero-variance asset. Treat cash as the residual:

    cash-weight = 1 - sum(target-risky-weights)

This keeps the risky covariance matrix well-conditioned and makes cash floor constraints explicit.

Each universe row should carry at least:

    {:instrument-id ...
     :coin ...
     :market-type :perp|:spot
     :dex ...
     :shortable? ...
     :mark-price ...
     :sz-decimals ...
     :current-qty ...
     :signed-notional-usdc ...
     :current-weight ...
     :funding-carry-ann ...}

### Signed Exposure Versus Long-Only

The setup UI must expose a long-only toggle separate from other constraints. When long-only is enabled, every asset lower bound is clamped to zero. When long-only is disabled, only rows marked `:shortable?` may go negative. Spot remains non-shortable in V1 even if the account is unified.

Gross exposure is:

    gross = sum(abs(w_i))

Net exposure is:

    net = sum(w_i)

Both must be first-class controls and first-class result metrics. The results surface must show signed exposure, not only normalized pie slices or absolute weights.

### Expected Return Estimators

Historical Mean uses the arithmetic mean of aligned daily returns and annualizes with 365 periods. This is the default return model. EW Mean uses exponentially weighted daily returns with a configurable half-life and the same annualization. Both estimators must surface the raw price-return component separately from any funding carry adjustment so the results UI can show the decomposition.

Perp expected return is:

    expected-return = price-return-estimate + funding-carry-estimate

If funding history is unavailable, the funding term must fall back to zero and emit a visible warning in both diagnostics and the return-decomposition panel.

### Covariance Estimators and Shrinkage

V1 supports sample covariance, exponentially weighted covariance, and Ledoit-Wolf shrinkage. Ledoit-Wolf is the default estimator. Every covariance output must include conditioning metadata: minimum eigenvalue, condition number, shrinkage coefficient if applicable, and whether a positive-semidefinite repair was applied.

Use a small positive eigenvalue floor after annualization so near-singular universes remain numerically usable. Record the floor in diagnostics whenever it changes the matrix.

### Black-Litterman Inputs and Posterior

Black-Litterman is a return model. It consumes the chosen covariance matrix and prior weights, then emits a posterior expected-return vector. The preferred prior is market-cap weights. If a market-cap feed is unavailable for one or more universe assets, the implementation must fall back to current-portfolio weights for those assets and label the actual source used in diagnostics and in the result metadata.

The minimum BL input contract is:

    {:prior {:kind :market-cap
             :fallback-kind :current-portfolio
             :delta 2.5
             :tau 0.05}
     :views [{:kind :absolute|:relative
              :lhs [...]
              :rhs [...]
              :annualized-return ...
              :confidence ...}]}

The posterior math should follow the standard closed form:

    pi    = delta * Sigma * w_ref
    M     = inv(inv(tau * Sigma) + P' * inv(Omega) * P)
    mu_bl = M * (inv(tau * Sigma) * pi + P' * inv(Omega) * q)

Diagnostics must report the prior source, number of views, confidence inputs, and posterior-shift magnitude.

### Constraint Encoding

These are V1 requirements, not open questions:

    global long-only toggle
    gross leverage cap
    net exposure bounds
    max asset weight
    dust threshold
    per-asset overrides
    held-position lock
    per-perp leverage cap
    allowlist and blocklist
    max turnover
    rebalance tolerance

Encode them as follows.

Long-only toggle and per-asset overrides map to lower and upper box bounds. Max asset weight is a global absolute-weight cap applied to every non-cash instrument before per-asset tightening. In signed mode it means `abs(target-weight) <= max-asset-weight`; in long-only mode it is simply the global upper bound. Per-asset overrides may tighten the global cap for a specific instrument but should not relax it in V1 unless a future ADR explicitly changes that behavior. Held-position lock means `target-weight = current-weight` for that instrument. Allowlist and blocklist shape the universe before solving. Gross leverage and net exposure are explicit scalar constraints. Per-perp leverage cap becomes an additional absolute weight cap for each perp row. Max turnover is:

    0.5 * sum(abs(w_target - w_current)) <= turnover-max

Rebalance tolerance is a post-solve no-trade band. If the target minus current weight is within the tolerance for a row, the cleaned target snaps back to the current weight and the row is marked as "within tolerance" rather than traded.

Presolve validation must reject impossible combinations, including contradictory bounds, impossible held locks, targets outside the feasible frontier, or leverage and cash settings that cannot coexist.

### Objective Encoding

Minimum Variance minimizes `w' Sigma w`.

Target Return minimizes variance subject to `mu' w >= target-return`.

Target Volatility maximizes expected return subject to `sqrt(w' Sigma w) <= target-volatility`.

Max Sharpe is not a bespoke peer solver. It is a frontier selection mode that picks the feasible point with the maximum ratio `(mu' w - rf) / volatility`, with `rf = 0` for V1 because the repo has no risk-free data seam.

### Efficient Frontier Computation

The frontier is required for three reasons: results visualization, Max Sharpe selection, and frontier-driven objective authoring. Compute a constrained frontier over a fixed grid of feasible target returns or volatilities. Interactive runs should target about 25 points for 20 to 40 instruments and may degrade to 15 points for 60 instruments or when sensitivity diagnostics are enabled.

The results chart must support click or drag interaction on the frontier marker. V1 should snap the marker to computed frontier points, update the draft target-return or target-volatility field, and mark the scenario dirty so the user can rerun deliberately. The chart must not silently mutate the saved result behind the user's back.

### Weight Cleaning and Dust Handling

Weight cleaning happens after solving. Cleaning must respect the configured dust threshold, exchange size precision, and rebalance tolerance. Tiny deltas should not be silently redistributed across other risky assets. They should roll into cash or remain at current weight depending on whether the no-trade band or dust rule triggered. The cleaned result must still re-check all constraints and return a machine-readable warning if cleaning materially altered the original optimum.

### Diagnostics

At minimum, every successful run must emit:

    covariance conditioning and PSD repair details
    active and binding constraints
    feasible frontier range
    effective N and concentration
    gross and net exposure
    turnover in weight and USDC terms
    funding contribution by perp row
    sensitivity analysis for top-weighted assets
    data completeness and freshness flags

Every infeasible run must emit:

    violated constraints
    nearest feasible range if known
    binding controls to highlight in the UI
    whether infeasibility came from presolve or from the solver

### Solver Alternatives and Recommendation

ADR 0027 decides the V1 path. Use worker-isolated OSQP first. Keep quadprog as a fallback and fixture parity oracle. Do not use the internal projected-gradient spike baseline as the production optimizer.

The harness measured 12 deterministic problems with three measured runs each, covering 20-, 40-, and 60-instrument universes across the four V1 objectives. OSQP solved 36/36 runs with mean 0.57 ms, max 0.98 ms, max sum error 4.44e-16, and max bound violation 1.39e-17. quadprog solved 36/36 runs with mean 14.59 ms, max 63.24 ms, max sum error 7.77e-16, and max bound violation 1.39e-17. The projected-gradient baseline solved 36/36 but was slower at mean 47.78 ms and max 208.85 ms.

The remaining solver work is dependency integration and constraint coverage, not solver selection. Before execution preview depends on optimizer output, add fixture parity for signed gross and net exposure, turnover caps, held-position locks, infeasible target return, and per-perp caps. If OSQP browser bundling or dependency review fails, fall back to quadprog for V1 instead of reopening a custom solver build.

## UI Implementation Mapping

The optimizer must inherit the current tearsheet styling system. That means the same matte background, dense panel frames, thin dividers, compact numeric typography, and restrained cyan accents already present in the portfolio and account surfaces. Do not introduce a separate design language, rounded consumer cards, or a wizard flow.

### Screen Breakdown

`/portfolio/optimize` is a board of saved scenarios with dense rows, status chips, freshness, last-run summary, and a "New Scenario" action. It is not a marketing landing page. The list should be scan-first: scenario name, lifecycle status, objective, return model, risk model, expected return, volatility, Sharpe, drift or stale marker, updated time, and row actions fit in a compact table or board row.

`/portfolio/optimize/new` is a desktop-first analytical workspace with a persistent left rail, dense setup panels, a trust or caution side panel, and explicit run controls. Return-model controls and risk-model controls must be visibly separate. Black-Litterman view editing belongs in the return-model section, not in the objective section. The left rail should show scenario identity, sections, dirty or complete state per section, and save/run actions. The center should use stacked dense panels for universe, objective, return model, risk model, constraints, and execution assumptions. The constraints panel must include a global max asset weight numeric control in addition to per-asset overrides. The right rail should show data freshness, missing-history warnings, BL prior source, covariance trust, and execution readiness.

`/portfolio/optimize/:scenario-id` shows a stale-state banner when applicable, a current-versus-target comparison, summary metrics, a frontier chart, diagnostics, and a rebalance preview entry point. The results surface must show signed exposure and funding decomposition, not just target weights. The top area should preserve scenario identity and lifecycle state. The middle should prioritize side-by-side current-versus-target rows, signed delta, gross and net exposure, cash residual, and current stale reason. The frontier and diagnostics panels should remain visible without hiding the allocation table behind a wizard step.

Tracking and post-execution detail lives inside the scenario workspace, not as a new top-level route. V1 remains desktop-first. The tracking panel should show execution status, target drift, max absolute weight gap, turnover-to-target, expected return at save, realized return since execution, freshness, and snapshot history in the same dense table language as the results route.

### Reusable Component Inventory

Both `/new` and `/:scenario-id` should share a workspace shell, persistent left rail, assumptions strip, summary sidebar, and freshness footer. The scenario board and scenario detail should share status chip styling and summary row formatting. Results and tracking should share the same scenario header and lifecycle badge treatment.

The following component families should be reused rather than rebuilt ad hoc:

    workspace shell
    scenario header
    assumptions strip
    dense panel frame
    summary metrics block
    signed exposure table
    frontier chart
    diagnostics panel
    rebalance preview table
    execution confirmation modal

### Required Interaction States

The UI must explicitly handle unsaved draft, running, run complete, stale result, infeasible result, partial-data warning, read-only Spectate Mode, saved, executed, partially executed, archived, and execution recovery states. Skeletons belong inside existing panels, not as full-screen generic loading overlays.

The stale state is especially important. The last successful result remains on screen. A top banner explains why it is stale and offers rerun. The UI must not blank the results surface merely because a rerun or data refresh has started.

Binding-constraint highlighting is required. When a run is infeasible, the banner must name the failing constraints and the affected controls or table rows must be visually highlighted. This is a correctness requirement, not a polish task.

### Hard UI Acceptance Criteria

The setup surface must expose separate controls for objective, return model, and risk model. It must not collapse Black-Litterman into the objective picker.

The setup constraints panel must expose global max asset weight as a first-class numeric control, not only hidden per-row overrides.

The results surface must show signed exposure and cash residual directly. Showing normalized absolute allocation only is insufficient.

The results surface must show funding decomposition for perp expected returns somewhere visible without requiring a debugger or hidden JSON inspection.

The frontier chart must allow click or drag target selection and must feed that selection back into the draft objective controls.

The infeasible path must highlight the binding or violated constraints that caused the failure and explain the feasible range when the engine can derive it.

The rebalance preview must show blocked rows, executable rows, estimated fees, estimated slippage, and account-level cross-margin summary before confirmation.

## Persistence and Data Contracts

Use EDN-style maps internally. Persist scenario versioning from day one.

### Optimization Request Config

    {:schema-version 1
     :scenario-id "scn_01HS6PQ..."
     :wallet-address "0xabc..."
     :name "Core Carry"
     :capital-base {:mode :account-nav
                    :nav-usdc 125000.0
                    :cash-reserve-usdc 7500.0}
     :universe {:instrument-ids ["perp:BTC" "perp:ETH" "spot:SOL/USDC"]
                :allowlist ["perp:BTC" "perp:ETH" "spot:SOL/USDC"]
                :blocklist []
                :held-locks ["spot:SOL/USDC"]}
     :objective {:kind :target-volatility
                 :target-volatility-ann 0.28}
     :return-model {:kind :historical-mean
                    :lookback-days 180}
     :risk-model {:kind :ledoit-wolf
                  :lookback-days 180
                  :target :diagonal}
     :black-litterman {:enabled? false
                       :prior {:kind :market-cap
                               :fallback-kind :current-portfolio
                               :delta 2.5
                               :tau 0.05}
                       :views []}
     :constraints {:long-only? false
                   :max-asset-weight 0.35
                   :gross-max 1.5
                   :net-min 0.80
                   :net-max 1.10
                   :max-turnover 0.35
                   :rebalance-tolerance 0.01
                   :dust-usdc 50.0
                   :asset-overrides {"perp:BTC" {:min -0.20 :max 0.35}
                                     "perp:ETH" {:max 0.30}}
                   :perp-leverage {"perp:BTC" 4
                                   "perp:ETH" 3}}
     :execution-assumptions {:default-order-type :market
                             :slippage-fallback-bps 25
                             :fee-mode :taker}
     :metadata {:created-at-ms 1776960000000
                :updated-at-ms 1776963600000}}

### Optimization Result

    {:schema-version 1
     :scenario-id "scn_01HS6PQ..."
     :run-id "run_01HS6Q4..."
     :request-signature "sha256:..."
     :computed-at-ms 1776964200000
     :source-snapshots {:portfolio-loaded-at-ms 1776964100000
                        :candle-watermarks {"perp:BTC" 1776963600000}
                        :funding-watermarks {"perp:BTC" 1776960000000}}
     :status :ok
     :objective {:kind :target-volatility
                 :target-volatility-ann 0.28}
     :summary {:expected-return-ann 0.34
               :volatility-ann 0.28
               :sharpe 1.21
               :gross-exposure 1.18
               :net-exposure 0.97
               :cash-weight 0.03}
     :weights {:current {"perp:BTC" 0.22
                         "perp:ETH" 0.18}
               :target {"perp:BTC" 0.31
                        "perp:ETH" 0.12
                        "spot:SOL/USDC" -0.00}}
     :return-decomposition {"perp:BTC" {:price-alpha-ann 0.22
                                        :funding-carry-ann -0.03
                                        :total-ann 0.19}}
     :frontier {:points [{:id "pt-01" :return-ann 0.18 :volatility-ann 0.16 :sharpe 1.12}
                         {:id "pt-02" :return-ann 0.22 :volatility-ann 0.19 :sharpe 1.19}]}
     :diagnostics {:conditioning {:condition-number 148.2
                                  :min-eigenvalue 0.00037
                                  :psd-repair? false
                                  :shrinkage 0.21}
                   :constraints {:binding [:gross-max]
                                 :violated []
                                 :feasible-return-range [0.11 0.36]}
                   :turnover {:weight-turnover 0.27
                              :notional-turnover-usdc 33750.0}
                   :concentration {:effective-n-net 5.8
                                   :effective-n-gross 4.9
                                   :top-5-weight 0.72}}}

### Saved Scenario

    {:schema-version 1
     :id "scn_01HS6PQ..."
     :wallet-address "0xabc..."
     :name "Core Carry"
     :status :saved
     :config {...}
     :saved-run {...}
     :execution-ledger []
     :tracking-summary {:last-snapshot-at-ms nil
                        :weight-drift-rms nil}
     :created-at-ms 1776960000000
     :updated-at-ms 1776964200000}

### Rebalance Preview

    {:scenario-id "scn_01HS6PQ..."
     :run-id "run_01HS6Q4..."
     :computed-at-ms 1776964200000
     :summary {:gross-turnover 0.27
               :estimated-fees-usdc 38.7
               :estimated-slippage-usdc 61.2
               :blocked-count 1
               :executable-count 2
               :post-trade {:gross-exposure 1.18
                            :net-exposure 0.97
                            :cash-weight 0.03}}
     :rows [{:instrument-id "perp:BTC"
             :market-type :perp
             :current-weight 0.22
             :target-weight 0.31
             :delta-weight 0.09
             :delta-notional-usdc 11250.0
             :est-qty 0.177
             :side :buy
             :fees-usdc 8.4
             :slippage-usdc 14.2
             :execution-status :ready
             :request {...}}
            {:instrument-id "spot:SOL/USDC"
             :market-type :spot
             :delta-weight -0.02
             :delta-notional-usdc -2200.0
             :side :sell
             :execution-status :blocked
             :blocker :spot-submit-unsupported}]}

### Tracking Snapshot

    {:scenario-id "scn_01HS6PQ..."
     :snapshot-at-ms 1777046400000
     :execution-status :partially-executed
     :current {:nav-usdc 127500.0
               :weights {"perp:BTC" 0.28
                         "perp:ETH" 0.15}}
     :target {:weights {"perp:BTC" 0.31
                        "perp:ETH" 0.12}}
     :drift {:weight-drift-rms 0.021
             :max-abs-weight-gap 0.03
             :turnover-to-target 0.07}
     :performance {:expected-return-ann-at-save 0.34
                   :realized-return-since-execution 0.021}
     :freshness {:portfolio-loaded-at-ms 1777046200000
                 :history-complete? true}}

## Plan of Work

### Phase 1: Foundations and Read-Side Seams

This phase makes the repo structurally ready for the optimizer without committing to engine math yet. Extend the route parser, add optimizer query-state ownership, introduce the optimizer bounded context, and extract a non-view seam for holdings, capital base, and current unified account exposure. The goal is that an optimizer draft can exist in store and the app can route to `/portfolio/optimize*` without importing `hyperopen.views.*` into optimizer logic.

Modify `src/hyperopen/portfolio/routes.cljs`, `src/hyperopen/route_query_state.cljs`, `src/hyperopen/views/portfolio_view.cljs`, `src/hyperopen/account/surface_service.cljs`, `src/hyperopen/startup/runtime.cljs`, and `src/hyperopen/schema/runtime_registration/portfolio.cljs`. Create the optimizer boundary, actions, and query-state namespaces, plus `application/current_portfolio.cljs`. Add route parsing tests, query-state tests, and holdings-projection tests that cover unified accounts, trader portfolio routes, and Spectate Mode read-only behavior.

This phase exits when `/portfolio/optimize`, `/portfolio/optimize/new`, and `/portfolio/optimize/:scenario-id` route correctly inside the existing portfolio shell and the optimizer can build a current-portfolio snapshot without depending on any view namespace.

### Phase 2: Solver Spike and Numerical ADR

This phase is intentionally a prototype milestone. It now has a committed deterministic fixture harness at `tools/optimizer/solver_spike_benchmark.mjs` and focused coverage through `npm run test:optimizer-spike`. The spike measured 20-, 40-, and 60-instrument workloads across Minimum Variance, Max Sharpe, Target Return, and Target Volatility.

Phase 2 exits with ADR 0027. The selected V1 path is OSQP in the optimizer worker, with quadprog as fallback and parity oracle. Do not build the full optimizer UI here, and do not implement a custom QP solver for V1.

### Phase 3: Arbitrary-Universe History and Prior Data Loading

This phase builds the data assembly layer the engine requires. It now creates `application/history_loader.cljs`, `infrastructure/history_client.cljs`, `infrastructure/prior_data.cljs`, `application/orderbook_loader.cljs`, and `application/request_builder.cljs`. The history loader plans candles for arbitrary coins, aligns histories to common daily buckets, merges funding history for perps, and exposes completeness flags. The prior-data seam resolves market-cap-first Black-Litterman weights with current-portfolio fallback and explicit source labeling.

`application/orderbook_loader.cljs` plans later execution-preview depth through `[:effects/subscribe-orderbook coin]` effects for missing or stale books only. It labels live, stale, and fallback cost contexts but does not mutate websocket state directly.

This phase exits when the request builder can assemble aligned return inputs, funding carry inputs, and BL prior metadata for a chosen universe, and when missing data produces structured warnings instead of silent row drops. That exit condition is satisfied by the focused Phase 3 tests recorded in `Progress`.

### Phase 4: Pure Engine Core

Implement the chosen solver path plus pure domain namespaces for returns, risk, Black-Litterman, constraints, objectives, frontier, weight cleaning, diagnostics, and rebalance shaping. The engine must accept a normalized request map and return a deterministic result map or a structured infeasibility payload. It must encode every mandatory V1 constraint and emit funding decomposition, binding constraints, effective N, turnover, and sensitivity analysis.

Write pure unit tests first. Add property tests for bounds, gross leverage, long-only behavior, and cleaning invariants. Add fixture-based parity tests against committed reference outputs, using Python or another offline tool only to generate the fixed reference files. The repo does not need a live Python dependency at runtime; the references should be committed fixtures.

This phase exits when the engine produces stable results for realistic mixed spot and perp universes and reports structured infeasibility reasons for impossible targets.

### Phase 5: Worker Bridge and Runtime Integration

Create the optimizer worker target, the worker client, the run bridge, and the runtime effect adapter. The worker bridge must guard against stale responses, preserve the last successful result during reruns, and keep the main thread responsive. Add dedupe by request signature so repeated identical runs do not waste worker cycles.

Add RED tests for late-result handling, duplicate-run dedupe, and route changes during in-flight runs. This phase exits when the optimizer can run from the app store without blocking the UI and without letting late worker responses overwrite fresher drafts.

### Phase 6: Setup Workspace UI

Build `/portfolio/optimize/new` first. The left rail should hold section navigation and scenario actions. The main workspace should separate universe selection, objective, return model, risk model, constraints, and execution assumptions. The constraints panel must include the long-only toggle, gross leverage, net exposure, max asset weight, dust threshold, per-asset overrides, held-position lock, per-perp leverage, allowlist and blocklist, max turnover, and rebalance tolerance. The right-side panel should show trust notes, freshness, and warnings. The setup route must feel like a workspace, not a wizard.

Explicitly test that objective, return-model, and risk-model controls are independent. Add component and view-model tests for dirty state, missing data warnings, and Spectate Mode execution disablement. This phase exits when a user can create a complete draft, inspect assumptions, and launch a run without leaving the route.

### Phase 7: Results Workspace and Diagnostics

Build `/portfolio/optimize/:scenario-id` next. The scenario route should load saved config and last successful result immediately, then compute current staleness against live account and market signatures. The route must show the stale banner without clearing results, expose a current-versus-target signed exposure table, show funding decomposition, show summary metrics, and render the frontier chart and diagnostics panel.

The frontier chart must support click or drag target selection that updates the draft objective and marks the scenario dirty. The infeasible run path must call out binding constraints and highlight the affected controls or table rows. This phase exits when saved scenarios can be loaded, understood, marked stale, and rerun from the same workspace.

### Phase 8: Scenario Persistence and History Board

Build local persistence and `/portfolio/optimize`. The history board should list saved scenarios, statuses, freshness, and last-run summaries. Saving a scenario must persist config, last successful result, lifecycle status, and compact list-summary data through IndexedDB. Duplicate and archive actions should work without corrupting the address-scoped index record.

Write serialization round-trip tests and migration tests immediately. This phase exits when scenarios survive refresh, load instantly from the board, and retain the correct lifecycle state and last-run result.

### Phase 9: Rebalance Preview, Cost Model, and Execution

This phase turns results into action. Build `application/execution.cljs`, `views/portfolio/optimize/rebalance_preview.cljs`, and `views/portfolio/optimize/execution_modal.cljs`. The preview must generate row intents, cost estimates, and blockers. Use `domain/trading/market.cljs` for slippage and fee estimation when orderbook or best-price data is available, and use a conservative configured fallback basis-point assumption when it is not. Show which source was used.

Execution must submit supported rows through the current trading stack. The plan recommendation is to build a dedicated optimizer execution effect that either batches normalized order actions when the exchange shape and validation rules allow it or submits rows sequentially with consolidated result handling when batching would hide failures. Record the exact decision after implementation spike or the first execution prototype. Either path must persist an execution ledger, summarize failed rows, refresh account surfaces after mutation, and drive lifecycle changes to `:executed` or `:partially-executed`.

Spectate Mode remains read-only here. Mixed scenarios with blocked spot rows may still execute supported perp rows, but the confirmation modal must say that outcome explicitly before submission. This phase exits when the app can move from computed result to honest confirm-and-execute behavior with failure recovery and partial-execution persistence.

### Phase 10: Tracking and Drift

Build tracking only after execution exists. Tracking should append drift snapshots for executed or partially executed scenarios, show weight drift and predicted-versus-realized return, and preserve the target-versus-current comparison. Use `:weight-drift-rms` or `:distance-to-target`, not benchmark-relative tracking-error terminology.

Snapshots may be refreshed on-demand or on scenario load in V1, but the behavior must be explicit and deterministic. There is no background job in V1. This phase exits when an executed scenario can show drift history after refresh and the tracking panel survives reloads.

### Phase 11: QA, Performance, and Hardening

Run the repo's required gates plus the smallest relevant Playwright coverage first, then broader regression coverage if needed. Add performance assertions for realistic universe sizes and worker-run times. Verify route reloads, persistence recovery, stale result retention, execution failure recovery, and Spectate Mode read-only behavior. This phase exits when the required repo gates pass and the optimizer route family has deterministic browser coverage for the main user flow.

## Concrete Steps

All commands below run from `/Users/barry/.codex/worktrees/d394/hyperopen`.

1. Start with focused RED tests for route parsing and query-state ownership, then implement Phase 1 until those tests pass.

       npm run test:runner:generate
       npx shadow-cljs --force-spawn compile test
       node out/test.js --test=hyperopen.portfolio.routes-test --test=hyperopen.portfolio.optimizer.query-state-test

2. Add focused tests for the extracted current-portfolio seam and startup bootstrap behavior, then implement the route and account-read foundations.

       node out/test.js --test=hyperopen.portfolio.optimizer.application.current-portfolio-test --test=hyperopen.app.startup-test

3. Implement the solver spike behind a focused harness. This is complete through ADR 0027, and the focused test should stay in the gate.

       npm run test:optimizer-spike
       node tools/optimizer/solver_spike_benchmark.mjs --candidate=projected-gradient-js --warmup=2 --runs=3
       node tools/optimizer/solver_spike_benchmark.mjs --external-root=<unpacked-solver-packages> --warmup=1 --runs=3

   To rerun external package evidence without installing runtime dependencies in the repo, unpack `osqp@0.0.2` and `quadprog@1.6.1` into a temporary directory and pass that directory as `--external-root`.

4. Add RED unit and property tests for the engine, then implement domain namespaces until the focused suite passes.

       node out/test.js --test=hyperopen.portfolio.optimizer.domain.returns-test --test=hyperopen.portfolio.optimizer.domain.risk-test --test=hyperopen.portfolio.optimizer.domain.black-litterman-test --test=hyperopen.portfolio.optimizer.domain.constraints-test --test=hyperopen.portfolio.optimizer.domain.frontier-test --test=hyperopen.portfolio.optimizer.domain.diagnostics-test

   The pure-domain slices, application runner seam, and first package adapters are complete. The remaining Phase 4 implementation work is split-variable constraint expansion and committed fixture parity coverage.

5. Add the worker target, bridge, and route integration, then verify compilation.

       npx shadow-cljs --force-spawn compile app
       npx shadow-cljs --force-spawn compile portfolio-optimizer-worker

6. Build the setup and results routes with focused view tests before broader browser coverage.

       node out/test.js --test=hyperopen.views.portfolio.optimize.index-view-test --test=hyperopen.views.portfolio.optimize.workspace-view-test --test=hyperopen.views.portfolio.optimize.results-view-test

7. Add rebalance preview and execution tests, including blocked spot rows, partial execution, and Spectate Mode disablement.

       node out/test.js --test=hyperopen.portfolio.optimizer.application.execution-test --test=hyperopen.views.portfolio.optimize.execution-modal-test

8. Run the repo's required gates and the smallest deterministic Playwright coverage for the optimizer route family.

       npm run check
       npm test
       npm run test:websocket
       npx playwright test tools/playwright/test/portfolio-optimize.spec.mjs --project=desktop

9. Only after the focused desktop flow passes, widen browser coverage if the optimizer changes shared portfolio shell behavior.

## Validation and Acceptance

The implementation is accepted only when all of the following are true.

The route parser distinguishes `/portfolio/optimize`, `/portfolio/optimize/new`, and `/portfolio/optimize/:scenario-id`, and the existing `/portfolio` and `/portfolio/trader/<address>` behavior still works.

The optimizer routes load inside the existing `:portfolio_route` module and the same outer portfolio shell. There is no second app shell.

The setup workspace exposes separate controls for objective, return model, and risk model. Historical Mean is the default return model for a first-time scenario. Ledoit-Wolf is the default covariance estimator.

The result surface shows signed current and target exposure, gross and net exposure, cash residual, funding decomposition for perps, frontier interaction, stale-state retention, and a diagnostics panel with binding constraints and infeasibility explanations.

The lifecycle contract uses `:draft`, `:computed`, `:saved`, `:executed`, and `:partially-executed`. No persisted runtime status or UI badge uses `:implemented`.

Scenario persistence survives refresh, is address-scoped, and does not require object-store scans.

Rebalance preview shows execution-ready rows, blocked rows, estimated fees, estimated slippage, and a cross-margin summary. The preview must show whether each estimate came from live book data or a fallback assumption.

Confirm-and-execute submits supported rows through the current trading stack, blocks unsupported spot rows honestly, writes an execution ledger, and lands in `:executed` or `:partially-executed` appropriately.

Spectate Mode can inspect and save scenarios locally but cannot execute.

Focused RED tests fail before the implementation and pass after it. The final repo gates `npm run check`, `npm test`, and `npm run test:websocket` pass. The optimizer route family has deterministic Playwright coverage for the main desktop flow.

## Testing Plan

Unit tests cover returns, risk estimators, Black-Litterman posterior math, constraint encoding, weight cleaning, diagnostics, execution preview shaping, and tracking drift math.

Property tests cover long-only invariants, max asset weight, gross leverage caps, cash-floor preservation, turnover caps, held-position locks, and cleaning stability. A generated test should be able to perturb a feasible input and prove that bound or leverage constraints never break after cleaning.

Fixture-based tests compare committed optimizer requests and outputs for representative universes: concentrated perps, mixed spot and perps, infeasible target return, infeasible target volatility, and partial-data inputs.

Parity tests against a reference implementation are valuable but must remain fixture-based. Generate the references offline, commit the normalized JSON or EDN output, and compare within numeric tolerances in CI.

UI state tests cover the scenario board, setup workspace, stale-state results route, infeasible runs, read-only Spectate Mode, and execution modal states. Integration tests cover rebalance preview, blocked spot rows, partial execution, execution recovery, and scenario serialization. Performance tests cover worker latency and frontier generation for 20, 40, and 60 instruments.

## Performance and Reliability Plan

Target interactive universes are 10 to 40 instruments. V1 should support up to 60 instruments with degraded frontier density or sensitivity depth. Above 60 instruments, the app should warn explicitly rather than pretending the run will remain interactive.

The target latency budget is under 16 ms for main-thread draft validation, under 300 ms warm for a 20-instrument run, under 800 ms warm for a 40-instrument run, and under 1500 ms for a 60-instrument run with reduced frontier density. These are worker latencies, not total end-to-end route load times.

Cache candle histories, funding histories, and saved last-successful results. Dedupe worker runs by request signature. Do not cache stale execution previews across data-signature changes; previews depend on live holdings, prices, and often orderbook state.

Failure modes to handle explicitly include missing market history, missing funding history, insufficient observations, BL prior fallback, infeasible constraints, covariance singularity, worker failure, persistence failure, blocked spot rows, and partial exchange submission failures. The fallback behavior is to keep the last successful result visible, surface structured warnings, and avoid destructive state loss.

## Interfaces and Dependencies

The optimizer bounded context should expose these stable seams by the time the feature is complete:

`hyperopen.portfolio.optimizer.actions`
Pure action functions for edits, runs, saves, loads, preview generation, execution start, execution recovery, and tracking refresh.

`hyperopen.portfolio.optimizer.query-state`
Owned query keys and functions `parse-optimizer-query`, `apply-optimizer-query-state`, and `optimizer-query-params`.

`hyperopen.portfolio.optimizer.application.current-portfolio/current-portfolio-snapshot`
Returns the current holdings, capital base, and exposure context without importing view code.

`hyperopen.portfolio.optimizer.application.request-builder/build-run-request`
Converts draft plus histories plus current portfolio into the pure engine request.

`hyperopen.portfolio.optimizer.application.run-bridge/request-run!`
Posts a run request to the worker, tags it with a run id and request signature, and ignores stale responses.

`hyperopen.portfolio.optimizer.application.execution/build-preview`
Returns preview rows, blockers, cost estimates, and account-level post-trade summary.

`hyperopen.portfolio.optimizer.application.execution/execute-preview!`
Consumes the preview or executable subset, submits supported rows, and records the execution ledger.

`hyperopen.portfolio.optimizer.infrastructure.persistence`
Exposes load and save functions for scenario index records, scenario records, draft records, and tracking series.

`hyperopen.portfolio.optimizer.worker`
Understands messages like:

    {:type "run-optimizer"
     :id "run_..."
     :payload {...}}

and returns:

    {:type "optimizer-result"
     :id "run_..."
     :payload {...}}

Use a worker-only dependency boundary for OSQP, quadprog, and any matrix helper. Keep matrix and solver imports out of the main route view code.

## ADRs and Decisions Required

### ADR 1: Production Solver Selection

Decision: accepted in `docs/architecture-decision-records/0027-portfolio-optimizer-solver-selection.md`.

Why it matters: this affects correctness, latency, bundle shape, and whether the optimizer becomes a long-lived science project.

Recommendation: use a worker-isolated OSQP adapter as the first production solver path, retain quadprog as fallback and parity oracle, and do not build a custom in-repo QP solver for V1.

Blockers and unknowns: dependency review and browser bundling for `osqp@0.0.2`; if either fails, fall back to quadprog for V1.

### ADR 2: Black-Litterman Prior Data Source

Decision: choose the concrete implementation for market-cap prior data.

Why it matters: the product contract wants market-cap-first with current-portfolio fallback, but the repo has no current market-cap feed.

Recommendation: add an explicit prior-data seam that can surface `:source :market-cap` or `:source :fallback-current-portfolio`, and do not hide which source actually ran.

Blockers and unknowns: which external data source, if any, is acceptable for V1 and how aggressively it should be cached.

### ADR 3: Batch Execution Transport

Decision: submit rebalance rows as one grouped action where safe, or sequentially with consolidated feedback.

Why it matters: batching improves UX and cost-summary fidelity, while sequential submission may offer clearer partial-failure handling in the current runtime.

Recommendation: evaluate the exchange action shape and validation path during the execution milestone, then prefer the simplest transport that still preserves clear failure attribution.

Blockers and unknowns: whether mixed-asset grouped order actions fit the current request builder and response-classification seams cleanly enough.

### ADR 4: Cost Estimate Fallback Policy

Decision: define the production fallback when no live orderbook depth is available for a preview row.

Why it matters: V1 requires cost and slippage modeling, but arbitrary-universe rows will not always have live depth loaded.

Recommendation: use live orderbook-derived slippage when available and a conservative configured basis-point fallback otherwise, while labeling the source at row level and summary level.

Blockers and unknowns: whether product wants the fallback to be user-configurable in V1 or a fixed house assumption.

## Open Questions

The product contract already answered many questions that the earlier draft reopened, so this list stays narrow.

Which concrete market-cap data source, if any, is acceptable for the Black-Litterman prior in V1, and what caching policy is acceptable for it?

Does rebalance execution use a grouped exchange order action or sequential row submissions after the execution prototype is tested against current request-shaping seams?

Should the fallback slippage assumption be fixed by the product or user-configurable in V1 when live depth is unavailable?

## Idempotence and Recovery

All planning and implementation steps in this document are safe to rerun. Route and unit tests are repeatable. Worker builds and app builds overwrite generated assets but do not mutate source-of-truth code or local persistence records.

If a migration to the optimizer IndexedDB store fails, the safe fallback is to preserve existing stores, create a new versioned optimizer store, and let the app rebuild the scenario index from the explicit address-scoped index key. Do not rely on iterating object stores that the helper cannot scan.

If execution integration proves too unstable for a grouped batch submit, fall back to sequential submission with consolidated result handling rather than dropping execution from scope. That fallback still satisfies the V1 contract as long as supported rows truly execute and blocked rows are honest.

Do not use destructive git commands to recover from intermediate failures. This feature spans routing, persistence, workers, and execution; recovery should happen by reverting the last local code change or by disabling the newest optimizer effect binding until the focused test suite passes again.

## Artifacts and Notes

Key reconnaissance facts captured for implementers:

    src/hyperopen/portfolio/routes.cljs
    parse-portfolio-route currently returns only :page, :trader, :other, or :non-portfolio.

    src/hyperopen/route_modules.cljs
    every /portfolio* path currently resolves to route module id :portfolio and module name "portfolio_route".

    src/hyperopen/views/portfolio_view.cljs
    the portfolio surface is still one route-family shell view and already owns the tearsheet background and panel layout.

    src/hyperopen/portfolio/worker.cljs
    the repo already has a dedicated portfolio worker entrypoint pattern.

    src/hyperopen/trading/submit_policy.cljs
    spot submission is explicitly blocked with reason :spot-read-only and the message "Spot trading is not supported yet."

    src/hyperopen/platform/indexed_db.cljs
    IndexedDB helper supports keyed get/put/delete only; it does not expose iteration.

    /Users/barry/Downloads/hyperopen_portfolio_optimizer_design_pack/MANIFEST.md
    design pack evidence was inspected while authoring this plan; the actionable UI contract is embedded in this file so future implementers do not need that external directory.

## Revision Notes

- 2026-04-23 / Codex: Created the canonical active ExecPlan from the earlier draft, aligned it to the repo's planning contract, corrected the scenario lifecycle and execution semantics, made the UI acceptance criteria explicit, restored the product default return-model framing, and moved solver selection into a benchmark-backed ADR milestone.
- 2026-04-23 / Codex: Addressed review findings before commit by adding the global max asset weight field, UI control, and encoding rule, replacing stale read-only mode wording with Spectate Mode, embedding the visual contract from the design pack, pointing durable ADRs to `docs/architecture-decision-records/`, and removing the noncanonical draft redirect file.
- 2026-04-23 / Codex: Added the deterministic solver spike harness, benchmarked internal projected-gradient, quadprog, and OSQP candidates, accepted ADR 0027, and updated the plan so Phase 4 starts from a worker-isolated OSQP path with quadprog fallback instead of reopening solver selection.
- 2026-04-23 / Codex: Added Phase 3 arbitrary-universe history, funding carry, BL prior, orderbook planning, and request-builder seams with focused tests, keeping API and websocket side effects behind optimizer-owned dependency boundaries.
- 2026-04-23 / Codex: Added the first Phase 4 pure-domain optimizer math slice with focused tests for returns, risk, Black-Litterman, constraints, frontier selection, diagnostics, and long-only/signed-aware weight cleaning. Solver adapter and rebalance shaping remain in Phase 4.
- 2026-04-23 / Codex: Added the second Phase 4 pure-domain seam for solver-neutral objective plan construction and rebalance preview row shaping. OSQP/quadprog adapter and fixture parity remain in Phase 4.
- 2026-04-23 / Codex: Added the Phase 4 application engine runner seam with injected solver execution, frontier selection, diagnostics, and rebalance preview assembly. OSQP/quadprog package adapters and fixture parity remain before Phase 4 exits.
- 2026-04-23 / Codex: Added `osqp@0.0.2` and `quadprog@1.6.1` plus focused infrastructure adapters for direct long-only QP solves. Split-variable gross exposure, turnover constraints, and fixture parity remain before Phase 4 exits.
