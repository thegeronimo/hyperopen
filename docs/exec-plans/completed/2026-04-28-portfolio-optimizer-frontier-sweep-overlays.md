---
owner: frontend
status: completed
created: 2026-04-28
source_of_truth: false
tracked_issue: hyperopen-126i
---

# Portfolio Optimizer Frontier Sweep Overlays

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while work proceeds.

This plan follows `/hyperopen/.agents/PLANS.md`. It is tracked by `bd` issue `hyperopen-126i` ("Add optimizer frontier sweep overlays").

## Purpose / Big Picture

Minimum variance currently solves one quadratic program and the results chart receives one feasible point. A single SVG point cannot form a visible efficient-frontier line, so the user cannot see where the recommended target sits relative to higher-risk and higher-return feasible portfolios. After this change, every solved optimizer result will include enough frontier context for the results page to draw a real frontier curve, while preserving the exact target-selection behavior for Minimum variance and Target return runs.

The results page will also explain the chosen allocation through asset overlays. By default the chart will show standalone asset points: each selected instrument plotted by its own expected return and volatility. A toggle will switch to portfolio contribution points: each selected instrument plotted by its signed contribution to the target portfolio's expected return and volatility. The goal is to let a user answer two separate questions without reading optimizer internals: "What do the selected assets look like on their own?" and "How is this target portfolio using those assets to produce the final risk-return profile?"

## Progress

- [x] (2026-04-28 18:32Z) Confirmed current implementation behavior: Minimum variance uses a single QP, `:frontier` contains one point, and the chart path has no visible segment.
- [x] (2026-04-28 18:32Z) Created `bd` feature issue `hyperopen-126i`.
- [x] (2026-04-28 18:32Z) Recorded product decision to support both standalone asset points and portfolio contribution points, with standalone as the default view.
- [x] (2026-04-28 18:47Z) Added RED tests for pure overlay metrics, minimum-variance display frontier payload, UI state/action wiring, results-chart overlay controls, and Playwright chart coverage.
- [x] (2026-04-28 18:48Z) Verified RED state: `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test` fails because `hyperopen.portfolio.optimizer.domain.frontier-overlays` is not available.
- [x] (2026-04-28 20:45Z) Implemented the requested UI/action/chart slice: local overlay mode default, normalized action/runtime wiring, scenario-to-chart mode threading, and standalone/contribution overlay rendering with stable data roles.
- [x] (2026-04-28 20:47Z) Resolved the transient engine delimiter issue during integration and restored CLJS compilation.
- [x] (2026-04-28 21:00Z) Implemented pure domain tests for standalone and contribution overlay metrics.
- [x] (2026-04-28 21:08Z) Implemented engine payload changes without changing target selection semantics.
- [x] (2026-04-28 21:12Z) Implemented results-chart overlay controls, labels, stable data roles, and UI state persistence.
- [x] (2026-04-28 21:18Z) Added focused UI tests and deterministic Playwright coverage.
- [x] (2026-04-28 21:42Z) Ran required gates and browser QA: `npm run check`, `npm test`, `npm run test:websocket`, focused Playwright, seeded results design review, and `npm run browser:cleanup`.

## Surprises & Discoveries

- Observation: `src/hyperopen/portfolio/optimizer/domain/objectives.cljs` sends `:minimum-variance` and `:target-return` through `:single-qp`, while `:max-sharpe` and `:target-volatility` already use `:frontier-sweep`.
  Evidence: `build-solver-plan` returns `{:strategy :single-qp}` for `:minimum-variance` and `:target-return`, and only uses `frontier-plan` for `:max-sharpe` and `:target-volatility`.
- Observation: The chart already renders an SVG `path`, but one frontier point produces only a move command, not a line segment.
  Evidence: `frontier_chart.cljs` builds `path-data` from `(:frontier result)` positions; the current screenshot shows "1 points" and no visible path.
- Observation: Existing optimizer progress UI already contains a frontier step even when the current run only solves one QP.
  Evidence: `src/hyperopen/portfolio/optimizer/application/progress.cljs` defines a "frontier sweep" progress row with a default detail of 40 points.
- Observation: After the UI/action/chart slice landed, the next compile blocker moved to `src/hyperopen/portfolio/optimizer/application/engine.cljs` with an unmatched `)` at line 189, before CLJS tests could run.
  Evidence: `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test` now exits with `Unmatched delimiter )` at `engine.cljs:189:48`.
- Observation: Contribution mode initially rebuilt the shared chart coordinate system from contribution points only, which moved the unchanged frontier line when toggling modes.
  Evidence: Static review found the issue; a results-panel regression now asserts the generated frontier path is identical between standalone and contribution modes.
- Observation: The existing design-review route only exercised the setup screen, not the solved results surface where frontier overlays render.
  Evidence: Added `portfolio-optimizer-results-route`, seeded `scenario::qa-frontier` in the browser-inspection profile, and ran governed design review on `/portfolio/optimize/qa-frontier`.

## Decision Log

- Decision: Preserve Minimum variance target selection as a single minimum-variance QP and add a separate display frontier for chart context.
  Rationale: The target should remain the mathematically minimum-variance feasible portfolio. The extra sweep explains alternatives; it must not accidentally turn Minimum variance into a return-tilted selector.
  Date/Author: 2026-04-28 / Codex
- Decision: Reuse the existing return-tilt sweep shape for display frontier points.
  Rationale: The codebase already supports return-tilted QPs, OSQP solving, efficient-frontier filtering, and target selection for sweep results. Reusing that path minimizes new solver logic and makes Max Sharpe / Target volatility behavior consistent with the new display frontier.
  Date/Author: 2026-04-28 / Codex
- Decision: Default the overlay toggle to standalone asset points and provide contribution points as an advanced mode.
  Rationale: Standalone points are easier to understand at a glance. Contribution points are more faithful to portfolio construction but require careful labeling because they are signed contributions, not standalone portfolios.
  Date/Author: 2026-04-28 / Codex
- Decision: Define contribution risk as signed component contribution to total target volatility: `w_i * (Sigma w)_i / sigma_p`, where `w_i` is target weight, `Sigma` is the covariance matrix, and `sigma_p` is target portfolio volatility.
  Rationale: This is a standard decomposition whose components sum to total portfolio volatility when `sigma_p` is positive. It can be negative for hedges, which is important information and must be labeled as signed contribution.
  Date/Author: 2026-04-28 / Codex
- Decision: Keep the efficient-frontier coordinate frame stable across overlay modes.
  Rationale: Toggling between standalone, contribution, and none changes explanatory markers, not the frontier itself. A stable path prevents users from reading a display re-scale as a changed optimization result.
  Date/Author: 2026-04-28 / Codex
- Decision: Add a seeded browser-inspection target for solved optimizer results.
  Rationale: The setup route cannot verify the frontier chart. A deterministic `qa-frontier` persisted scenario gives governed design review a stable route for visual, styling, layout, interaction, and jank checks on the actual results surface.
  Date/Author: 2026-04-28 / Codex

## Context and Orientation

The optimizer engine lives in `src/hyperopen/portfolio/optimizer/application/engine.cljs`. It builds risk and return models, builds a solver plan in `src/hyperopen/portfolio/optimizer/domain/objectives.cljs`, solves the plan through `src/hyperopen/portfolio/optimizer/infrastructure/solver_adapter.cljs`, selects a point from solved results using `src/hyperopen/portfolio/optimizer/domain/frontier.cljs`, then emits a result payload consumed by the results page.

A "quadratic program" here means an optimization problem whose objective includes portfolio variance, written as `w^T Sigma w`, where `w` is the vector of target weights and `Sigma` is the covariance matrix. The OSQP adapter solves these problems in the web worker. A "frontier sweep" means solving a family of these quadratic programs with increasing return tilt values, then filtering out dominated points so the remaining curve shows the best expected return observed at each increasing volatility level.

The current results chart lives in `src/hyperopen/views/portfolio/optimize/frontier_chart.cljs`. It reads `(:frontier result)`, `(:expected-return result)`, `(:volatility result)`, `(:current-expected-return result)`, and `(:current-volatility result)`. It already has current and target markers plus clickable frontier points that can set a target-return or target-volatility objective and rerun. The new overlay controls should fit inside this component and preserve those existing interactions.

The scenario results shell lives in `src/hyperopen/views/portfolio/optimize/results_panel.cljs` and `src/hyperopen/views/portfolio/optimize/scenario_detail_view.cljs`. UI-facing work must follow `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/BROWSER_TESTING.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, and `/hyperopen/docs/agent-guides/trading-ui-policy.md`. Use Playwright for committed deterministic browser coverage and account for the browser-QA passes before signoff.

## Plan of Work

First, add pure domain coverage for the data that must exist before any UI work. Create a small domain namespace, `src/hyperopen/portfolio/optimizer/domain/frontier_overlays.cljs`, responsible for producing asset overlay rows from instrument ids, target weights, expected returns, and covariance. It should expose a pure function such as `asset-overlay-points` that returns one row per selected instrument with `:instrument-id`, `:target-weight`, `:standalone`, and `:contribution` maps. The standalone map should contain `:expected-return` and `:volatility`, where volatility is `sqrt(max(0, covariance[i][i]))`. The contribution map should contain `:expected-return` as `target-weight * expected-return_i` and `:volatility` as `target-weight * (Sigma w)_i / portfolio-volatility`. If portfolio volatility is zero or not finite, contribution volatility should be `nil` rather than fake zero.

Second, split solver intent from chart context in the engine. Keep `objectives/build-solver-plan` as the authority for target selection. Add a display-frontier plan builder in `objectives.cljs`, or a similarly named pure helper, that returns return-tilted frontier problems for any feasible request. In `engine.cljs`, solve the target plan as today, then solve display frontier problems only when the target plan did not already use `:frontier-sweep`. For `:max-sharpe` and `:target-volatility`, reuse the existing sweep results as the display frontier. For `:minimum-variance` and `:target-return`, add the display sweep results to the final payload without using them to select the target. The final result should make this distinction clear, for example by keeping `:solver` as the target solver metadata and adding `:frontier-summary` with `{:source :display-sweep, :point-count n}`.

Third, make the result payload self-explanatory. Continue emitting `:frontier` as the chart frontier points so existing chart consumers keep working. Add `:frontier-overlays` with at least `{:standalone [...], :contribution [...]}` or another stable shape that keeps both modes available without recomputation in the view. Include `:weight`, `:coin` or display label if already available without coupling to UI formatting, and enough numeric fields for tooltips. Do not add browser or DOM concerns to the engine.

Fourth, update worker and wire normalization only if the new payload introduces keyword-valued enum fields or instrument-keyed maps. If overlay rows use vectors with string `:instrument-id` values and keyword `:kind` fields, extend `src/hyperopen/portfolio/optimizer/infrastructure/wire.cljs` only for those enum keys. Avoid adding nested maps keyed by instrument id unless there is a strong reason, because the existing wire layer already has a narrow set of instrument-keyed paths.

Fifth, update `frontier_chart.cljs` so the chart has a compact segmented control for overlay mode. Use three states: `:standalone`, `:contribution`, and `:none`. The default should be `:standalone`. Store the selected mode in `[:portfolio-ui :optimizer :frontier-overlay-mode]`, add a default in `src/hyperopen/portfolio/optimizer/defaults.cljs`, and add a normalizing action in `src/hyperopen/portfolio/optimizer/actions.cljs` plus runtime/action contract registrations. This state does not need to be shareable in the URL for the first implementation; it is a presentation preference, not a scenario identity.

Sixth, render overlays with clear visual separation from frontier and target markers. The efficient frontier line should remain the primary shape. Target and current markers should retain their existing labels. Standalone asset points should use a muted neutral marker and tooltip copy like "BTC standalone: return X, volatility Y, target weight Z". Contribution points should use a different marker shape or dashed halo and tooltip copy like "BTC contribution: return contribution X, volatility contribution Y, target weight Z". Do not rely on color alone; use marker shape, tooltip text, legend text, and data-role hooks.

Seventh, update chart domain handling so overlay points participate in axis ranges only when they are visible in the selected mode. Contribution volatility can be negative for hedges; the x-domain helper must support that without clipping points or labeling them as standalone volatility. When contribution mode is active, axis labels and reading guidance must say "signed volatility contribution" instead of implying every marker is a standalone volatility point. The frontier line itself remains in risk/return space.

Eighth, add tests at each boundary. Add CLJS domain tests for standalone and contribution overlay calculations, including a hedge case with negative risk contribution. Add engine tests proving a Minimum variance run still uses one target-selection QP but emits a multi-point display frontier. Add view tests proving the default overlay mode is standalone, the mode toggle dispatches the new action, overlay points render with stable `data-role` hooks, and existing frontier click/drag actions are unchanged. Add action/default/schema tests for the new UI state and action. Add or update deterministic Playwright coverage for `/portfolio/optimize/:scenario-id?otab=recommendation` to assert that a Minimum variance result displays a visible frontier path, overlay toggle controls, and asset overlay markers.

## Concrete Steps

Start by writing failing tests:

    cd /Users/barry/projects/hyperopen
    npx shadow-cljs --force-spawn compile test && node out/test.js --focus hyperopen.portfolio.optimizer.domain.frontier-overlays-test

Expect the focused frontier overlay namespace to fail before the new namespace exists. If the test runner does not support `--focus` for this namespace, run the full generated CLJS test command and confirm the new test failure is the relevant failure.

Implement the pure overlay namespace and tests:

    src/hyperopen/portfolio/optimizer/domain/frontier_overlays.cljs
    test/hyperopen/portfolio/optimizer/domain/frontier_overlays_test.cljs

The tests should assert that standalone BTC with variance `0.04` has volatility `0.2`, that a target weight of `0.3` and expected return `0.1` has return contribution `0.03`, and that a covariance row that hedges the target can produce a negative signed volatility contribution.

Then write failing engine tests in `test/hyperopen/portfolio/optimizer/application/engine_test.cljs`. Add a test named along the lines of `minimum-variance-emits-display-frontier-without-changing-selected-target-test`. Use a fixture `solve-problem` that records each problem, returns `[0.5 0.5]` for the target plan, and returns two or more distinct solved points for display frontier problems. Assert that `(:target-weights result)` equals the target solve weights, that `(:frontier result)` has more than one point, and that `(:solver :strategy result)` still reports the target strategy rather than pretending Minimum variance selected from the sweep.

Implement engine changes in:

    src/hyperopen/portfolio/optimizer/domain/objectives.cljs
    src/hyperopen/portfolio/optimizer/application/engine.cljs

Keep selection logic in `selected-point` or its replacement explicit: target-selection results choose target weights; display-frontier results provide chart points. Do not pass display sweep results into `frontier/select-frontier-point` for Minimum variance.

Add UI state/action tests before UI implementation:

    test/hyperopen/portfolio/optimizer/defaults_test.cljs
    test/hyperopen/portfolio/optimizer/actions_test.cljs
    test/hyperopen/schema/contracts/action_args_test.cljs, if action contract coverage exists for similar actions

Implement the default and action wiring in:

    src/hyperopen/portfolio/optimizer/defaults.cljs
    src/hyperopen/portfolio/optimizer/actions.cljs
    src/hyperopen/runtime/action_adapters.cljs
    src/hyperopen/schema/runtime_registration/portfolio.cljs
    src/hyperopen/schema/contracts/action_args.cljs

The action should normalize unknown values to `:standalone` and emit one `:effects/save` to `[:portfolio-ui :optimizer :frontier-overlay-mode]`.

Add view tests in `test/hyperopen/views/portfolio/optimize/results_panel_test.cljs` for the default overlay and toggle actions. Then update:

    src/hyperopen/views/portfolio/optimize/results_panel.cljs
    src/hyperopen/views/portfolio/optimize/frontier_chart.cljs

Thread the selected overlay mode into `frontier-chart`. Keep `frontier-chart` mostly pure by passing mode as data rather than reading global state inside the component. Preserve existing data roles such as `portfolio-optimizer-frontier-path`, `portfolio-optimizer-frontier-target-marker`, and `portfolio-optimizer-frontier-point-N`.

Add deterministic Playwright coverage under `tools/playwright/**` following the existing optimizer route patterns. The stable acceptance path should seed or navigate to a Minimum variance result scenario, then assert that the chart contains a non-empty frontier path with more than a move-only `d` value, standalone overlay markers are visible by default, and switching to contribution mode changes marker roles or labels.

Run focused verification first:

    cd /Users/barry/projects/hyperopen
    npx shadow-cljs --force-spawn compile test && node out/test.js

Then run the smallest relevant Playwright command for the optimizer route. If no optimizer-specific Playwright command exists, add one and document it here during implementation. After focused tests pass, run required gates:

    npm run check
    npm test
    npm run test:websocket

For UI signoff, run browser QA per `/hyperopen/docs/FRONTEND.md` and `/hyperopen/docs/BROWSER_TESTING.md`. Account for the six browser-QA passes and the four widths `375`, `768`, `1280`, and `1440`. If Browser MCP or browser-inspection sessions are created, finish with:

    npm run browser:cleanup

## Validation and Acceptance

The feature is accepted when a user can run a Minimum variance optimization, open the Recommendation tab, and see a visible efficient-frontier curve instead of a single point. The target marker must still correspond to the Minimum variance QP result, not a different return-tilted sweep point.

The chart must default to standalone asset overlays. A selected asset such as BTC should appear as its own risk-return marker with tooltip or accessible label text that includes standalone return, standalone volatility, and target weight. Switching the toggle to contribution mode should show each selected asset's signed contribution to target return and signed contribution to target volatility, with labels that make it clear these are portfolio contributions, not standalone asset portfolios.

Existing interactions must still work: clicking or dragging frontier points should still set Target return or Target volatility and rerun. Current and target markers must remain visible. The allocation table and trust diagnostics rail must still render.

Focused CLJS tests should fail before implementation and pass after. Required gates after code changes are `npm run check`, `npm test`, and `npm run test:websocket`. UI completion also requires Playwright coverage plus browser-QA accounting across the required widths and passes.

## Idempotence and Recovery

The engine change should be additive to the result payload and should not mutate stored scenarios or current portfolio data. Re-running an optimization should overwrite the previous run state exactly as it does today. If the display frontier sweep fails while target selection succeeds, prefer a solved result with the target, diagnostics, warnings, and an empty or partial `:frontier` plus a warning such as `:display-frontier-unavailable`; do not fail the entire optimization unless target selection itself fails. Record the final behavior in the Decision Log when implemented.

All tests and browser commands are safe to rerun. If Browser MCP or browser-inspection sessions are interrupted, run `npm run browser:cleanup` before starting the next browser validation pass.

## Interfaces and Dependencies

The new pure overlay function should not depend on browser APIs, DOM state, worker globals, or UI namespaces. It should accept ordinary ClojureScript data and return ordinary maps/vectors. A target interface is:

    (asset-overlay-points {:instrument-ids [...]
                           :target-weights [...]
                           :expected-returns [...]
                           :covariance [[...]]
                           :labels-by-instrument {...}})

The engine result payload should keep `:frontier` as the visible chart frontier for compatibility. It should add overlay data in a stable shape such as:

    :frontier-overlays
    {:standalone [{:instrument-id "perp:BTC"
                   :label "BTC"
                   :target-weight 0.063
                   :expected-return 0.12
                   :volatility 0.45}]
     :contribution [{:instrument-id "perp:BTC"
                     :label "BTC"
                     :target-weight 0.063
                     :expected-return 0.00756
                     :volatility 0.021}]}

Names may change during implementation if tests and callers are updated consistently, but the semantics must not change: standalone means the instrument as a one-asset portfolio, contribution means signed contribution inside the target portfolio.

The new UI action should be named predictably, for example `:actions/set-portfolio-optimizer-frontier-overlay-mode`, and should accept one argument: `:standalone`, `:contribution`, or `:none`. Unknown values must normalize to `:standalone`.

## Outcomes & Retrospective

Implemented. Minimum variance and target-return runs now keep their efficient target solve but run a separate display frontier sweep for chart context. Existing frontier-sweep objectives reuse their target sweep as chart data. Results include `:frontier-summary` and `:frontier-overlays` so the UI can explain both the feasible frontier and the selected assets' standalone/contribution behavior without recomputing portfolio math in views.

The chart defaults to standalone asset markers, offers contribution and none modes, and preserves click/drag frontier interactions. Contribution mode uses signed contribution values while keeping the frontier coordinate frame stable. The engine returns a solved target with a `:display-frontier-unavailable` warning if the display sweep fails.

Validation completed on 2026-04-28: `npm run check`, `npm test`, `npm run test:websocket`, focused Playwright for the optimizer recommendation chart, seeded governed design review for `portfolio-optimizer-results-route` across 375/768/1280/1440, and `npm run browser:cleanup`.

## Artifacts and Notes

Browser-inspection artifact for the passing results-surface QA run:

    /Users/barry/projects/hyperopen/tmp/browser-inspection/design-review-2026-04-28T19-41-40-876Z-b37a639d
