owner: product+platform
status: proposed
source_of_truth: false
based_on:
  - /mnt/data/review_zip_extracted/portfolio-optimizer-external-review/README.md
  - /mnt/data/review_zip_extracted/portfolio-optimizer-external-review/scenario-data/scenario-result.json
  - /mnt/data/HO-PDD-002_portfolio_optimization.md

# Portfolio Optimizer V1 Remediation ExecPlan for Codex

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`. It is self-contained so a future contributor can continue from this file without relying on conversation history.

Tracked issue: `hyperopen-zenl` ("Remediate portfolio optimizer V1 implementation").

## Mission

Turn the current `codex/portfolio-optimizer-v1-foundations` branch from a broad foundations spike into a mergeable, reviewable V1 slice that actually satisfies the product contract.

This pass is not allowed to broaden scope. It must fix correctness, semantics, and UI-contract gaps before any additional feature expansion.

## Non-negotiable constraints

1. Do not continue repo-wide cleanup work in this pass.
2. Do not hide mathematical or execution problems behind copy or silent defaults.
3. Do not ship a branch whose acceptance artifact still has zero capital, zero notionals, and `ready` execution rows.
4. Do not claim Black-Litterman, Ledoit-Wolf, condition-number diagnostics, or orderbook-aware execution if the implementation is still partial or mislabeled.
5. If a design or implementation detail conflicts with the current spike, prefer the PDD and latest design direction, not the current UI.

## Primary blockers to fix

### Blocker A — branch scope is too large
The current diff includes many unrelated refactors and docs unrelated to the optimizer surface. This makes review unsafe.

### Blocker B — default minimum-variance posture is hidden and unsafe
The current fix for all-zero min-variance runs uses hidden exposure constraints (`net-min`, signed posture) rather than an explicit user-facing control.

### Blocker C — execution preview semantics are wrong
Rows can be marked `ready` while capital, quantity, and delta-notional are all zero.

### Blocker D — Black-Litterman is selectable but not authorable
The engine supports views, but the UI does not let users create or edit them.

### Blocker E — diagnostics are mislabeled or too weak
The branch currently reports a diagonal-ratio as covariance conditioning and labels a diagonal shrink approximation as Ledoit-Wolf.

### Blocker F — results UI is still a wiring surface, not the intended product
Missing or underbuilt: stale banner, assumptions strip, summary strip, proper three-column layout, signed exposure representation, trust panel, robust diagnostics, and a real rebalance review.

### Blocker G — tracking is too thin
Tracking needs real scenario lifecycle semantics and richer predicted-vs-realized surfaces.

### Blocker H — acceptance artifacts are not valid
The current bundle uses a zero-capital scenario, which invalidates the execution proof.

## Hard acceptance criteria for this remediation pass

A branch is only review-ready when all of the following are true:

1. The optimizer diff is reviewable and restricted to optimizer-relevant files plus direct dependencies.
2. The default minimum-variance run no longer relies on a hidden `net-min` hack.
3. Rebalance preview never marks rows `ready` when delta-notional or executable quantity is zero.
4. At least one captured acceptance scenario has non-zero NAV, non-zero target notionals, non-zero executable quantities, and non-zero aggregate cost.
5. Black-Litterman views can be added, edited, removed, and saved through the UI.
6. Either true Ledoit-Wolf shrinkage is implemented, or the UI and engine are renamed to the actual method being used.
7. Covariance conditioning uses a real matrix condition number, not a diagonal proxy.
8. Funding decomposition renders correctly on the results page.
9. Results surface includes: stale-state banner, assumptions strip, signed current-vs-target presentation, frontier chart, diagnostics/trust panel, rebalance preview CTA.
10. Rebalance preview includes blocked-vs-ready semantics, notional totals, cost/slippage totals, and margin/capital impact.
11. Tracking uses canonical scenario states and includes drift plus realized-vs-predicted surfaces.
12. Updated screenshots and scenario payloads prove the branch behavior without caveat notes about zero notionals.

---

## Progress

- [x] (2026-04-25 21:22Z) Created remediation branch `codex/portfolio-optimizer-v1-remediation` from spike head `8ab4c711447e2a87a8a2d31698aab03a4d56aaac`.
- [x] (2026-04-25 21:25Z) Stored the user-provided remediation plan under `docs/exec-plans/active/2026-04-25-portfolio-optimizer-v1-remediation.md` and created tracked issue `hyperopen-zenl`.
- [x] (2026-04-25 21:55Z) Completed Phase 0 scope pruning. The working tree diff against `main` is restricted to optimizer code plus required route/runtime/build/test/browser-storage collateral, and `npm run check`, `npm test`, and `npm run test:websocket` passed.
- [x] (2026-04-25 22:08Z) Completed Phase 1 capital-base and execution-readiness correctness. Snapshot readiness is split into `:snapshot-loaded?`, `:capital-ready?`, and `:execution-ready?`; zero-capital and below-lot rebalance rows are blocked; preview summaries count executable rows only; and the required gates passed.
- [x] (2026-04-26 01:57Z) Completed Phase 2 defaults and minimum-variance honesty. Defaults no longer include hidden `net-min`, default leverage/weight/tolerance values match the remediation contract, signed minimum-variance near-cash solutions are surfaced with an explicit warning, and the required gates passed.
- [x] (2026-04-26 02:15Z) Completed Phase 3 Black-Litterman authoring UI and persistence. BL now has absolute/relative view add/edit/remove actions, draft and saved scenario persistence coverage, request-builder view normalization with confidence variance, prior-source/weight transparency in setup, and the required gates passed.
- [x] (2026-04-26 02:23Z) Completed Phase 4 worker wire normalization and funding rendering. Known instrument-keyed request/response maps normalize to string keys across worker boundaries, slash-containing spot IDs are preserved, result payloads include current/target weight maps, funding-source enum values survive normalization, and the required gates passed.
- [x] (2026-04-26 02:36Z) Completed Phase 5 risk and diagnostics correction. The mislabeled Ledoit-Wolf path is now honestly labeled `diagonal-shrink` with a legacy alias warning, covariance conditioning uses eigenvalue-derived condition/min/max values, low-observation expected-return warnings are emitted, shrunk in-sample Sharpe is carried on results, per-instrument sensitivity is surfaced, and the required gates passed.
- [x] (2026-04-26 02:51Z) Completed Phase 6 results surface upgrade. Results now render a stale/rerun banner driven by current-vs-last request comparison, a run-assumptions strip, three-column allocation/frontier/diagnostics panels, signed exposure bars with long/short metadata, a trust/caution rail, and visible binding/sensitivity diagnostics. Focused view tests, a targeted Playwright optimizer slice, and the required gates passed.
- [x] (2026-04-26 03:17Z) Completed Phase 7 rebalance review upgrade. Rebalance preview now carries orderbook-derived cost context with fallback labeling, fee/slippage estimates, margin impact/utilization summary, expanded review/modal columns, and browser coverage for cost/margin rendering. The route system now loads optimizer scenario/index state on optimizer navigation and startup refresh, and stale-result comparison ignores injected live cost contexts while preserving user-controlled execution assumption comparison.
- [x] (2026-04-26 04:04Z) Completed Phase 8 scenario lifecycle and tracking. Successful worker runs now promote active scenarios to `:computed`, failed/blocked execution attempts no longer overwrite saved/computed scenario lifecycle status, manual tracking enable persists `:tracking` to the saved scenario/index record, unsaved computed scenarios disable manual tracking until saved, tracking snapshots include NAV and predicted-volatility baselines, realized return is computed from the first tracked NAV baseline, and the tracking panel now renders predicted-vol, drift chart, realized-vs-predicted path, and a re-optimize-from-current CTA. Focused CLJS tracking/lifecycle tests, targeted Playwright tracking reload/rerun coverage, and the required gates passed.
- [x] (2026-04-26 04:31Z) Completed Phase 9 acceptance artifact refresh. Generated `docs/exec-plans/completed/artifacts/portfolio-optimizer-v1-remediation-review/` with four scenario JSON payloads and six screenshots covering non-zero executable perps, mixed spot/perp blocked semantics, Black-Litterman absolute/relative views with prior source, and infeasible-constraint UX. Artifact generation used the live optimizer route and passed Playwright assertions.

## Surprises & Discoveries

- Observation: The spike branch included broad non-optimizer work that must not remain in the remediation review stack.
  Evidence: The initial diff from `main` included account endpoint splits, chart overlay extraction, wallet/vault/trade/footer changes, unrelated completed ExecPlans, and non-optimizer tests. Phase 0 restores those paths to `main` while retaining optimizer-specific route/runtime/build collateral.

- Observation: Optimizer scenario persistence requires one shared IndexedDB store registration outside the optimizer namespace.
  Evidence: `src/hyperopen/portfolio/optimizer/infrastructure/persistence.cljs` uses `hyperopen.platform.indexed-db/portfolio-optimizer-store`. Removing `src/hyperopen/platform/indexed_db.cljs` from the review stack produced compile warnings, so Phase 0 keeps the minimal store-name and DB-version change as required browser-storage collateral.

- Observation: Optimizer effects must be included in the runtime registration catalog to keep contract metadata coherent.
  Evidence: `npm test` failed with "Effect contract metadata drift detected" after pruning `src/hyperopen/schema/runtime_registration_catalog.cljs`; the optimizer effect specs existed but `portfolio/effect-binding-rows` was not included in the catalog's effect rows.

- Observation: Optimizer runtime handlers must be wired into the app-level action/effect dependency graph.
  Evidence: After restoring the registration catalog, `npm test` failed at runtime bootstrap with "Missing effect handler :run-portfolio-optimizer"; `src/hyperopen/app/actions.cljs` and `src/hyperopen/app/effects.cljs` need the optimizer handler groups so the registry composition can resolve registered optimizer ids.

- Observation: A true minimum invested gross-exposure floor is not safely expressible in the current signed split-variable QP encoding.
  Evidence: A lower bound such as `p+n >= floor` can be satisfied by paired positive and negative split variables that decode back to zero signed weight. Phase 2 therefore keeps the explicit optional `Net Min` floor and low-invested warning instead of adding a misleading `minimum-invested-exposure` default.

- Observation: Black-Litterman authoring would have pushed existing optimizer action and view test namespaces over the governed namespace-size thresholds if added inline.
  Evidence: The first Phase 3 `npm run check` attempt failed `lint:namespace-sizes` for `src/hyperopen/portfolio/optimizer/actions.cljs`, `test/hyperopen/portfolio/optimizer/actions_test.cljs`, and `test/hyperopen/views/portfolio/optimize/view_test.cljs`. The implementation was split into focused BL action and panel test namespaces instead of adding new size exceptions.

- Observation: Worker response normalization had the same instrument-key hazard as worker request decoding.
  Evidence: `worker-client/normalize-worker-message` uses `js->clj :keywordize-keys true`, so payload maps such as `return-decomposition-by-instrument` were converted from string instrument IDs into keywords before the results panel looked them up by string. Slash-containing spot IDs also require `(subs (str keyword) 1)` instead of `name` to avoid dropping the namespace portion.

- Observation: A naive immutable Jacobi eigenvalue implementation was too slow for the existing worker runaway budget.
  Evidence: The focused optimizer suite initially failed the 60-instrument worker guard at 5480ms against a 5000ms budget. Phase 5 now uses a bounded mutable cyclic Jacobi pass for the conditioning diagnostic, preserving eigenvalue-derived outputs while keeping the worker performance test green.

- Observation: The old Playwright default-minimum-variance assertion conflicted with Phase 2's signed minimum-variance honesty decision.
  Evidence: The targeted Playwright run showed the default signed minimum-variance scenario can legitimately return a near-cash allocation with `low-invested-exposure`. The browser regression now asserts visible results plus the honest warning when gross target exposure is near zero, rather than requiring default full investment.

- Observation: Injected live orderbook cost contexts are too volatile to participate in stale-result comparison.
  Evidence: After Phase 7 injected `:cost-contexts-by-id` into readiness requests, a fresh browser optimization immediately displayed the stale/rerun banner because live/fallback cost context fields changed outside the optimizer model inputs. `stale-result?` now strips those injected contexts before comparing the last successful request with current readiness.

- Observation: Optimizer scenario routes were rendered by the portfolio module but not refreshed by the shared route-effect wiring.
  Evidence: Playwright persisted-scenario tests navigated to `/portfolio/optimize/scn_playwright_tracking_reload` and rendered the workspace route shell without the saved run or rebalance preview button. `runtime.action-adapters.navigation` and `startup.route-refresh` now route optimizer paths through `load-portfolio-optimizer-route`, and optimizer paths no longer trigger canonical portfolio chart-tab bootstrap.

- Observation: Manual tracking enable must be persisted, not only reflected in local UI state.
  Evidence: A saved scenario manually promoted to tracking would otherwise revert on reload because only `:active-scenario` and draft state changed. Phase 8 adds `:effects/enable-portfolio-optimizer-manual-tracking`, persists the updated scenario record/index through the existing scenario persistence boundary, and clears stale tracking snapshots for the promoted scenario.

- Observation: Adding lifecycle persistence directly to the top-level optimizer effect adapter exceeded namespace-size limits.
  Evidence: `npm run lint:namespace-sizes` failed for `src/hyperopen/runtime/effect_adapters/portfolio_optimizer.cljs` and `src/hyperopen/runtime/effect_adapters.cljs`. The implementation moved the manual-tracking persistence body into `portfolio_optimizer_scenarios.cljs` and kept the facade adapter as a thin wrapper instead of adding size exceptions.

- Observation: The previous review bundle caveat about zero notional proof is now obsolete.
  Evidence: Scenario A in `docs/exec-plans/completed/artifacts/portfolio-optimizer-v1-remediation-review/scenario-data/scenario-a-perp-executable.json` has `capital-usd = 100000`, `ready-count = 4`, `gross-trade-notional-usd = 28000`, `estimated-fees-usd = 9.8`, and non-zero target weights. Scenario B separately proves blocked spot semantics while retaining ready perp rows.

## Decision Log

- Decision: Execute remediation on a new branch named `codex/portfolio-optimizer-v1-remediation`, not directly on `codex/portfolio-optimizer-v1-foundations`.
  Rationale: The remediation plan requires commit alignment and a reviewable optimizer-only stack. A new branch preserves the spike head while allowing scope pruning and subsequent commits to match the plan.
  Date/Author: 2026-04-25 / Codex

- Decision: Keep the minimal `src/hyperopen/platform/indexed_db.cljs` store registration as required collateral for optimizer scenario persistence.
  Rationale: Browser persistence must use the shared IndexedDB boundary per `docs/BROWSER_STORAGE.md`; adding a feature-specific store constant there is narrower and safer than introducing an ad hoc storage path under optimizer infrastructure.
  Date/Author: 2026-04-25 / Codex

- Decision: Keep the minimal `src/hyperopen/schema/runtime_registration_catalog.cljs` change that adds `portfolio/effect-binding-rows` to the effect catalog.
  Rationale: Optimizer effect specs and runtime bindings must stay in the same registration source of truth as every other effect; without this one-line catalog hook, the test target fails at import time.
  Date/Author: 2026-04-25 / Codex

- Decision: Keep the minimal optimizer handler groups in `src/hyperopen/app/actions.cljs` and `src/hyperopen/app/effects.cljs`.
  Rationale: These files are the existing composition seam between runtime registration and concrete action/effect adapters. Adding optimizer groups there avoids a parallel runtime shell and lets the existing registry boot normally.
  Date/Author: 2026-04-25 / Codex

- Decision: Do not add a V1 `minimum-invested-exposure` control until the solver ADR supports a true signed gross-exposure lower bound.
  Rationale: The current solver can honestly enforce net exposure floors, max gross exposure, and max asset weight. Pretending it can enforce a minimum gross invested floor would recreate the hidden-exposure bug under a different label. The UI now labels `Net Min` as an optional floor and the engine warns when signed minimum variance returns near cash.
  Date/Author: 2026-04-26 / Codex

- Decision: Put BL view draft mutations in `hyperopen.portfolio.optimizer.black-litterman-actions` and wire them through the existing runtime action adapter/catalog.
  Rationale: BL view authoring is a cohesive subdomain and keeping it out of the already-large optimizer action namespace preserves the repo's namespace-size guard while still using the existing action/effect runtime conventions.
  Date/Author: 2026-04-26 / Codex

- Decision: Centralize optimizer worker-boundary normalization in `hyperopen.portfolio.optimizer.infrastructure.wire`.
  Rationale: Request normalization in the worker and response normalization in the client must share the same list of instrument-keyed map paths and the same keyword-to-string conversion rules; keeping the behavior in one boundary module prevents future `keywordize-keys` regressions.
  Date/Author: 2026-04-26 / Codex

- Decision: Rename the current risk estimator to `diagonal-shrink` instead of claiming Ledoit-Wolf in V1 remediation.
  Rationale: The implementation shrinks off-diagonal sample covariance entries toward zero; it is not a full Ledoit-Wolf estimator with data-derived shrinkage intensity. Renaming is the honest V1 fix and a legacy `:ledoit-wolf` alias remains only to normalize old saved drafts with a warning.
  Date/Author: 2026-04-26 / Codex

- Decision: Treat result staleness as a stable request comparison, not as the draft dirty flag.
  Rationale: `:metadata :dirty?` tracks saved-scenario persistence, so a freshly run unsaved draft can still be dirty. The stale banner now compares the last successful run request with the current readiness request after removing volatile clock-only fields.
  Date/Author: 2026-04-26 / Codex

- Decision: Keep orderbook-derived cost context inside the run/preview request, but exclude the generated context map from stale-result comparison.
  Rationale: The cost context is an execution preview input and can change with live orderbook state. Treating it as an optimization input made current results appear stale immediately after a successful run, even when universe, objective, return model, risk model, constraints, and user execution assumptions had not changed.
  Date/Author: 2026-04-26 / Codex

- Decision: Optimizer routes must participate in both navigation-time and startup/address-refresh route loading.
  Rationale: `/portfolio/optimize` and `/portfolio/optimize/:scenario-id` are rendered inside the portfolio route module, but their scenario index and saved scenario state are optimizer-specific data dependencies. The existing route loader did not call optimizer route actions during client-side navigation, so persisted scenarios could show an empty draft shell.
  Date/Author: 2026-04-26 / Codex

- Decision: Manual tracking enable is a persisted scenario lifecycle transition, not a local save-many mutation.
  Rationale: Tracking is part of the canonical scenario state contract. Keeping it local would make saved scenarios lie after refresh and would undermine read-only tracking workflows. The action now dispatches a dedicated effect that updates the saved scenario record, scenario index summary, active scenario state, and tracking state through existing persistence boundaries.
  Date/Author: 2026-04-26 / Codex

- Decision: Keep regenerated Phase 9 artifacts under the completed ExecPlan artifact directory.
  Rationale: These are implementation-review proof artifacts, not runtime assets. Storing them under `docs/exec-plans/completed/artifacts/portfolio-optimizer-v1-remediation-review/` keeps them discoverable with the plan while avoiding source, build output, dependency directories, browser caches, and Playwright reports.
  Date/Author: 2026-04-26 / Codex

## Outcomes & Retrospective

The remediation pass is implementation-complete through Phase 9. The branch now has a reviewable commit stack through `optimizer/acceptance-artifacts-refresh`, all required gates passed after Phase 8 code changes, and the Phase 9 artifact generator produced proof scenarios with non-zero executable notionals plus blocked-spot and infeasible-state coverage. The remaining work is external review, not an unimplemented phase in this ExecPlan.

## Phase 0 — isolate the work into a reviewable optimizer-only branch

### Goal
Reduce review risk before any further feature work.

### Instructions for Codex

- Create a new remediation branch from the current spike head.
- Produce an optimizer-only patch stack.
- Do not add more unrelated refactors.
- If some unrelated files are already entangled and cannot be cleanly removed, document them explicitly in the PR summary under `required collateral changes`.

### Allowed file families in this pass

- `src/hyperopen/portfolio/optimizer/**`
- `src/hyperopen/views/portfolio/optimize/**`
- `src/hyperopen/portfolio/routes.cljs`
- `src/hyperopen/views/portfolio_view.cljs`
- `src/hyperopen/runtime/effect_adapters/portfolio_optimizer*.cljs`
- `src/hyperopen/app/actions.cljs` and `src/hyperopen/app/effects.cljs` only for optimizer runtime handler groups
- `src/hyperopen/schema/runtime_registration/portfolio.cljs`
- `src/hyperopen/schema/runtime_registration_catalog.cljs` only for adding portfolio effect rows to the effect catalog
- `src/hyperopen/state/app_defaults.cljs`
- `src/hyperopen/platform/indexed_db.cljs` only for the optimizer scenario IndexedDB store registration
- direct tests under `test/hyperopen/portfolio/optimizer/**`, `test/hyperopen/views/portfolio/optimize/**`, and a minimal number of route/runtime tests
- minimal build config files only if required (`shadow-cljs.edn`, `package.json`, `package-lock.json`)

### Exit criteria
- The diff no longer contains unrelated account, chart overlay, footer, vault, or wallet refactors.
- The PR is legible to a portfolio optimizer reviewer.

---

## Phase 1 — fix capital-base semantics and execution readiness correctness

### Goal
Make the current portfolio snapshot and rebalance preview numerically honest.

### Files to touch

- `src/hyperopen/portfolio/optimizer/application/current_portfolio.cljs`
- `src/hyperopen/portfolio/optimizer/domain/rebalance.cljs`
- `src/hyperopen/portfolio/optimizer/application/execution.cljs`
- `src/hyperopen/views/portfolio/optimize/results_panel.cljs`
- `src/hyperopen/views/portfolio/optimize/execution_modal.cljs`
- corresponding tests

### Required changes

1. Split `loaded?` semantics in current portfolio snapshot into:
   - `snapshot-loaded?`
   - `capital-ready?`
   - `execution-ready?`

2. `capital-ready?` must require a positive usable capital base.

3. If capital base is zero or missing:
   - results may still show target weights,
   - but all rebalance rows must be marked blocked or unavailable,
   - never `ready`.

4. In `domain/rebalance.cljs`, a row can only be `:ready` when all are true:
   - delta weight exceeds tolerance,
   - capital base is positive,
   - mark price is finite and positive,
   - delta notional is non-zero,
   - rounded executable quantity is positive,
   - market type is executable in current stack.

5. Add explicit reasons for blocked rows:
   - `:missing-capital-base`
   - `:zero-delta-notional`
   - `:quantity-below-lot`
   - `:spot-submit-unsupported`
   - `:missing-price`
   - `:market-metadata-missing`

6. `side` must not remain `:none` on a row presented as executable.

7. Execution summary cards must derive from executable rows only.

### Tests to add/update

- zero-capital preview => `ready-count = 0`
- zero quantity after rounding => blocked with `:quantity-below-lot`
- mixed ready + blocked rows => `:partially-blocked`
- ready row always has positive quantity and non-zero notional

### Exit criteria
- No screenshot or payload can show `ready > 0` with `$0` gross trade.

---

## Phase 2 — remove the hidden minimum-variance hack and restore safe defaults

### Goal
Make defaults honest and user-facing.

### Files to touch

- `src/hyperopen/portfolio/optimizer/defaults.cljs`
- `src/hyperopen/portfolio/optimizer/actions.cljs`
- `src/hyperopen/views/portfolio/optimize/workspace_view.cljs`
- `src/hyperopen/views/portfolio/optimize/setup_readiness_panel.cljs`
- related tests

### Required changes

1. Remove the hidden default `net-min 0.8` posture.
2. Restore defaults to the product contract unless explicitly overruled:
   - `long-only? false`
   - `gross-max 3.0`
   - `net-max 1.5`
   - `max-asset-weight 0.25`
   - `rebalance-tolerance 0.03`
   - no implicit minimum net exposure by default
3. If the solver can return an all-cash or near-cash min-variance solution, surface that honestly.
4. Add an explicit advanced control only if needed:
   - `minimum-invested-exposure` or equivalent
   - default OFF / nil
   - clearly labeled as a forcing constraint
5. If minimum variance returns a mostly-cash solution, show a callout suggesting:
   - target return
   - target volatility
   - invested floor
   rather than silently forcing exposure.

### Tests to add/update

- default draft no longer contains `net-min`
- minimum variance with low-return universe may return low invested exposure without hidden mutation
- forcing invested exposure only happens when the user explicitly sets it

### Exit criteria
- The default run posture is not secretly risk-seeking.

---

## Phase 3 — implement real Black-Litterman authoring and prior transparency

### Goal
Turn BL from a hidden engine mode into a real product feature.

### Files to touch

- `src/hyperopen/portfolio/optimizer/actions.cljs`
- `src/hyperopen/portfolio/optimizer/application/request_builder.cljs`
- `src/hyperopen/portfolio/optimizer/infrastructure/prior_data.cljs`
- `src/hyperopen/views/portfolio/optimize/workspace_view.cljs`
- possibly add `src/hyperopen/views/portfolio/optimize/black_litterman_views_panel.cljs`
- corresponding tests

### Required changes

1. Add UI for BL views with support for:
   - absolute view
   - relative view
   - expected return / spread value
   - confidence
   - remove row
   - add row

2. Persist BL views in draft and saved scenario records.

3. Surface prior metadata in the setup UI:
   - prior source (`market-cap`, `current-portfolio`, fallback)
   - read-only implied prior values per asset
   - warning when market-cap prior is incomplete and fallback is used

4. Ensure BL remains a return-model path, not an objective.

### Tests to add/update

- add/edit/remove BL view actions
- request builder includes views and prior source
- scenario save/load roundtrip preserves BL views
- UI render test for BL editor visibility only when return model is BL

### Exit criteria
- A user can author BL views end-to-end and see their prior context.

---

## Phase 4 — fix wire normalization and funding decomposition rendering

### Goal
Stop losing instrument-keyed maps across the worker boundary.

### Files to touch

- `src/hyperopen/portfolio/optimizer/infrastructure/wire.cljs`
- `src/hyperopen/portfolio/optimizer/infrastructure/worker_client.cljs`
- `src/hyperopen/portfolio/optimizer/worker.cljs`
- `src/hyperopen/views/portfolio/optimize/results_panel.cljs`
- worker / wire tests

### Required changes

1. Standardize instrument-keyed maps as string-keyed maps on both request and response boundaries.
2. Do not `keywordize-keys` nested instrument IDs into lookup-breaking keywords.
3. Normalize result payloads so these maps remain stable:
   - `return-decomposition-by-instrument`
   - `current-weights-by-instrument`
   - `target-weights-by-instrument`
   - any diagnostics keyed by instrument ID
4. Update results panel lookups to use the normalized key format.

### Tests to add/update

- worker roundtrip preserves string instrument IDs
- funding decomposition renders non-`N/A` values from payload
- mixed keyword/string input maps normalize deterministically

### Exit criteria
- If the payload contains decomposition data, the UI renders it correctly.

---

## Phase 5 — make the math and diagnostics honest

### Goal
Remove mislabeled math and surface real instability signals.

### Files to touch

- `src/hyperopen/portfolio/optimizer/domain/risk.cljs`
- `src/hyperopen/portfolio/optimizer/domain/diagnostics.cljs`
- `src/hyperopen/portfolio/optimizer/domain/returns.cljs`
- `src/hyperopen/views/portfolio/optimize/results_panel.cljs`
- maybe `src/hyperopen/views/portfolio/optimize/run_status_panel.cljs`
- tests

### Required changes

1. Replace the fake Ledoit-Wolf implementation with one of:
   - true Ledoit-Wolf shrinkage, or
   - rename the method everywhere to `diagonal-shrink` and stop claiming LW.

2. Replace diagonal-ratio conditioning with a real covariance condition number:
   - based on eigenvalues or SVD
   - include min eigenvalue
   - include explicit status thresholds

3. Add sample-size / low-observation warnings when expected returns are estimated from tiny histories.

4. Add an in-sample Sharpe warning or shrunk Sharpe estimate.

5. Replace sparkline-only sensitivity with per-asset sensitivity output suitable for the right-side diagnostics panel.

6. Ensure funding decomposition uses real return + funding totals and labels the funding source.

### Tests to add/update

- condition number test on known matrices
- Ledoit-Wolf parity test or rename contract test
- low-sample return estimator emits warnings
- sensitivity test produces per-asset output, not just portfolio scalar changes

### Exit criteria
- No mislabeled diagnostics remain on the results page.

---

## Phase 6 — upgrade the results surface to the actual V1 UI contract

### Goal
Move from a data plumbing view to the intended product surface.

### Files to touch

- `src/hyperopen/views/portfolio/optimize/workspace_view.cljs`
- `src/hyperopen/views/portfolio/optimize/results_panel.cljs`
- `src/hyperopen/views/portfolio/optimize/frontier_chart.cljs`
- maybe add small helper panels/components under `src/hyperopen/views/portfolio/optimize/**`
- tests and Playwright screenshots

### Required changes

1. Add explicit stale-state banner with `Run Again` CTA.
2. Add assumptions strip showing:
   - objective
   - return model
   - risk model
   - lookback
   - funding assumption
3. Promote results into a real three-column layout:
   - left: current vs target + signed exposure / diverging bars
   - center: frontier chart + current/target markers
   - right: diagnostics + trust/caution block
4. Show signed exposure explicitly, not just normalized weights.
5. Keep the frontier interactive:
   - hover/preview
   - click to set target point
   - drag across points if already implemented
6. Add trust/caution box with plain-language warnings.
7. Add visible binding-constraint highlighting when infeasible or near-bound.

### Tests to add/update

- stale banner appears after draft edits diverge from last run
- frontier click changes target objective parameter
- results render signed negative weights distinctly
- infeasible case highlights the relevant controls

### Exit criteria
- The results surface matches the intended three-panel information architecture.

---

## Phase 7 — build a real rebalance review and cost/margin surface

### Goal
Make rebalance preview credible.

### Files to touch

- `src/hyperopen/portfolio/optimizer/application/orderbook_loader.cljs`
- `src/hyperopen/portfolio/optimizer/application/execution.cljs`
- `src/hyperopen/views/portfolio/optimize/results_panel.cljs`
- `src/hyperopen/views/portfolio/optimize/execution_modal.cljs`
- any runtime effect adapter that assembles orderbooks / fee tiers
- tests

### Required changes

1. Wire orderbook-derived cost contexts into preview generation.
2. Distinguish cost source explicitly:
   - live orderbook
   - stale orderbook
   - fallback bps
3. Expand preview summary to include:
   - gross trade notional
   - fee total
   - slippage total
   - margin utilization before/after
   - cross-margin warning thresholds
4. Add per-row execution detail:
   - size
   - price
   - est. fill / slippage
   - order type
   - drop / skip row
5. Preserve blocked rows visibly, especially for spot.
6. Ensure execution status transitions support:
   - executed
   - partially-executed
   - failed
   - blocked

### Tests to add/update

- live orderbook path beats fallback-bps when data available
- margin summary updates for preview
- blocked spot rows remain visible and non-submittable
- partial-execution status ledger works

### Exit criteria
- Preview is believable as an execution-grade review, not a placeholder.

---

## Phase 8 — fix scenario lifecycle and tracking semantics

### Goal
Bring scenario state and tracking into line with the product contract.

### Files to touch

- `src/hyperopen/portfolio/optimizer/application/scenario_records.cljs`
- `src/hyperopen/portfolio/optimizer/application/tracking.cljs`
- `src/hyperopen/views/portfolio/optimize/tracking_panel.cljs`
- maybe `src/hyperopen/views/portfolio/optimize/index_view.cljs`
- tests

### Required changes

1. Canonical scenario states:
   - `:draft`
   - `:computed`
   - `:saved`
   - `:executed`
   - `:partially-executed`
   - `:tracking`
   - `:archived`

2. Remove ambiguous `Implemented` or equivalent state language.
3. Tracking only becomes active after execution or explicit manual tracking enable.
4. Replace vague tracking metrics with explicit ones:
   - weight drift RMS
   - max absolute drift
   - realized return since execution
   - predicted return / vol baseline
5. Add at least the first iteration of:
   - drift chart
   - realized-vs-predicted path
   - re-optimize-from-current CTA

### Tests to add/update

- scenario lifecycle transition tests
- tracking snapshot append tests
- tracking view render tests for executed vs non-executed scenarios

### Exit criteria
- Tracking is no longer a thin table pretending to be a research loop.

---

## Phase 9 — regenerate acceptance artifacts with real proof scenarios

### Goal
Replace the current weak review bundle with proof-quality artifacts.

### Required scenarios

### Scenario A — non-zero capital, perp-only, executable
- positive NAV
- non-zero current or explicit synthetic capital base
- non-zero delta notionals
- non-zero quantities
- non-zero fee/slippage totals
- at least one submitted-ready row

### Scenario B — mixed spot + perp
- at least one perp row ready
- at least one spot row blocked with explicit reason
- preview status `partially-blocked`

### Scenario C — Black-Litterman
- authored absolute and relative views
- visible prior source
- changed posterior vs baseline weights

### Scenario D — infeasible constraints
- binding constraints surfaced
- plain-language failure, not generic error

### Artifacts to regenerate

- screenshots:
  - workspace full page
  - results surface
  - target allocation table
  - rebalance preview
  - BL views editor state
  - infeasible constraints state
- scenario JSON payloads for each scenario
- updated README without caveat language about zero notional proof

### Exit criteria
- A reviewer can verify execution semantics and math honesty from the bundle alone.

---

## Required test plan before handoff back for review

### Unit / domain
- defaults
- current portfolio capital readiness
- rebalance row readiness semantics
- BL prior resolution and views
- worker wire normalization
- risk model and conditioning
- returns warnings / sample size
- diagnostics and sensitivity
- scenario lifecycle and tracking

### Integration
- run from draft -> save -> results -> preview -> execute
- mixed spot/perp blocked-vs-ready preview
- stale edit -> banner -> rerun
- BL edit -> save -> reload

### Playwright
- setup authoring happy path
- stale-state flow
- BL views flow
- non-zero capital rebalance preview
- mixed universe blocked row flow
- tracking refresh flow

### Performance
- interactive run on 4–10 assets remains sub-second on warm cache
- preview generation with orderbook lookups remains responsive

---

## Commit plan Codex should follow

1. `optimizer/remediation-scope-prune`
2. `optimizer/capital-and-preview-correctness`
3. `optimizer/defaults-and-minvar-honesty`
4. `optimizer/black-litterman-editor`
5. `optimizer/wire-normalization-and-funding-render`
6. `optimizer/risk-and-diagnostics-correction`
7. `optimizer/results-surface-upgrade`
8. `optimizer/rebalance-review-upgrade`
9. `optimizer/tracking-and-status-lifecycle`
10. `optimizer/acceptance-artifacts-refresh`

Each commit must leave tests green.

---

## Definition of done

This remediation is done only when:

- the branch is reviewable,
- the math is honestly labeled,
- zero-capital scenarios no longer fake execution readiness,
- BL views are user-authorable,
- diagnostics are credible,
- the results and preview UI look like the intended product rather than a debug surface,
- and the acceptance bundle proves that with non-zero-capital scenarios.
