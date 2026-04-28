---
owner: frontend
status: completed
created: 2026-04-28
source_of_truth: false
tracked_issue: hyperopen-am2v
---

# Portfolio Optimizer Frontier Callouts

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while work proceeds.

This plan follows `/hyperopen/.agents/PLANS.md`. It is tracked by `bd` issue `hyperopen-am2v` ("Add optimizer frontier point callouts").

## Purpose / Big Picture

The optimizer results chart now shows the efficient frontier, target/current portfolio markers, and selected-asset overlay markers. Those markers explain the shape visually, but the user still has to infer the exact metrics from labels or table rows. After this change, each plotted marker will expose a compact Hyperopen-styled callout on hover and focus. The callout will name the plotted object and show annualized expected return, annualized volatility, and Sharpe. Portfolio markers will also show gross and net exposure.

The goal is a readable first-pass chart with deeper detail available in place, without changing solver behavior or the target-selection math.

## Progress

- [x] (2026-04-28 20:05Z) Created `bd` feature issue `hyperopen-am2v`.
- [x] (2026-04-28 20:06Z) Inspected existing frontier chart, results-panel tests, Playwright optimizer regression, and optimizer CSS.
- [x] (2026-04-28 20:10Z) Added RED tests for marker callout data roles, content, focusability, and preserved frontier point actions.
- [x] (2026-04-28 20:13Z) Verified RED state: CLJS tests failed only on missing marker focusability and missing callout nodes/content.
- [x] (2026-04-28 20:20Z) Implemented SVG callouts with hover/focus reveal behavior and Hyperopen optimizer styling.
- [x] (2026-04-28 20:21Z) Verified CLJS test suite passes after implementation.
- [x] (2026-04-28 20:25Z) Added deterministic Playwright coverage for hover/focus callouts on target and asset overlay markers.
- [x] (2026-04-28 20:31Z) Fixed SVG hit-testing and focus-indicator issues found by focused Playwright and browser design review.
- [x] (2026-04-28 20:32Z) Ran focused CLJS tests, focused Playwright, browser QA design review, and browser cleanup.
- [x] (2026-04-28 20:40Z) Split overlay marker rendering out of `frontier_chart.cljs` to satisfy namespace-size gates.
- [x] (2026-04-28 20:44Z) Ran required repo gates: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-04-28 20:54Z) Re-ran focused Playwright, browser design review, and browser cleanup after the final rendering split.

## Surprises & Discoveries

- Observation: The current chart is rendered as pure Hiccup with no local Reagent state. A CSS hover/focus callout nested inside each SVG marker keeps the implementation simple while still supporting hover, keyboard focus, and click-to-focus behavior.
  Evidence: `frontier_chart.cljs` renders marker groups directly and already makes frontier points focusable/clickable through attributes and dispatch vectors.
- Observation: Standalone and contribution overlay metrics already arrive in the result payload, so this feature should not recompute optimizer domain outputs in the UI.
  Evidence: `:frontier-overlays` includes `:standalone` and `:contribution` vectors with `:expected-return`, `:volatility`, `:target-weight`, `:instrument-id`, and `:label`.
- Observation: Keeping hidden SVG callouts mounted with `opacity: 0` expanded marker group hit boxes and made pointer targeting unreliable.
  Evidence: Focused Playwright initially timed out hovering the target marker because the parent SVG intercepted pointer events; changing hidden callouts to `display: none` restored hit targeting.
- Observation: Contribution markers can overlap with the target marker and each other because signed contribution risk can cluster near zero.
  Evidence: Focused Playwright showed BTC contribution was covered first by the target hitbox and then by SOL's nearby contribution hitbox. The implementation now renders overlay markers above portfolio markers and uses keyboard focus coverage for clustered contribution points.
- Observation: The browser design-review focus probe checks the computed focus style on the focused SVG group, not only child focus-ring graphics.
  Evidence: The first design-review run failed `focus-indicator-visible` for chart markers even though child callouts were visible; adding a real group outline made the governed design review pass.

## Decision Log

- Decision: Render callouts inside the SVG and reveal them with CSS on marker hover, focus, or focus-within.
  Rationale: This supports hover and click/focus without introducing chart component state, avoids viewport-position bookkeeping, and keeps chart tests deterministic.
  Date/Author: 2026-04-28 / Codex
- Decision: Keep portfolio exposure metrics limited to current and target portfolio markers.
  Rationale: Gross and net exposure are portfolio-level values. Asset overlay markers should show target weight as context, while portfolio markers should show gross/net exposure.
  Date/Author: 2026-04-28 / Codex
- Decision: Use contribution-specific row labels in contribution mode.
  Rationale: Signed contribution points are not standalone asset portfolios, so labeling their axes and callout rows as contributions prevents a misleading risk-return interpretation.
  Date/Author: 2026-04-28 / Codex
- Decision: Render overlay markers after portfolio markers in the SVG stack.
  Rationale: Asset overlay points are the detailed explanatory layer. When a contribution point clusters near the target, the asset marker must remain inspectable.
  Date/Author: 2026-04-28 / Codex
- Decision: Preserve both visible focus rings and a computed focus outline on SVG marker groups.
  Rationale: Rings give precise visual affordance around the plotted point; the outline satisfies browser/QA focus detection on SVG focus targets.
  Date/Author: 2026-04-28 / Codex

## Context and Orientation

The chart implementation lives in `src/hyperopen/views/portfolio/optimize/frontier_chart.cljs`. It already receives the solved optimizer result, visible overlay mode, efficient frontier points, target/current portfolio metrics, and selected asset overlay points. It also preserves interactive frontier point behavior through click and drag dispatch vectors.

The results shell and unit coverage live in `src/hyperopen/views/portfolio/optimize/results_panel.cljs` and `test/hyperopen/views/portfolio/optimize/results_panel_test.cljs`. Browser regression coverage for the optimizer route lives in `tools/playwright/test/portfolio-regressions.spec.mjs`.

UI style overrides for the optimizer surface live in `src/styles/main.css` under `.portfolio-optimizer-v4`.

## Plan of Work

First, add view-level tests that fail before implementation. The test should assert that the target marker has a callout with the target label, expected return, volatility, Sharpe, gross exposure, and net exposure. It should assert that current and overlay markers are focusable and expose callouts, that standalone and contribution overlays use distinct callout roles, and that existing frontier point click/drag actions are unchanged.

Second, implement a small callout helper for the frontier chart. The helper will accept a marker location, display label, point metrics, row labels, optional exposure values, and a stable data role. It will draw a compact SVG surface with label and metric rows, position it inside the viewBox, and mark it `aria-hidden` so screen readers use the marker's `aria-label`.

Third, add marker summaries for each plotted object:

- Frontier sweep point: label such as "Frontier point 2"; annualized return, annualized volatility, Sharpe.
- Target portfolio: target result metrics plus gross/net exposure from diagnostics or target weights fallback.
- Current portfolio: current result metrics plus gross/net exposure from current weights fallback.
- Standalone asset overlay: asset label, annualized expected return, annualized volatility, derived Sharpe, target weight.
- Contribution asset overlay: asset label, return contribution, signed volatility contribution, contribution Sharpe where meaningful, target weight.

Fourth, add optimizer-scoped CSS for callout reveal. Markers should remain readable by default, and callouts should appear on hover, focus, or focus-within. The style should use existing optimizer tokens rather than copying the reference screenshot.

Fifth, add deterministic Playwright assertions to the existing optimizer recommendation regression. Hover the target marker and a standalone asset marker, assert the callout becomes opaque and contains the expected labels, then switch to contribution mode and assert the contribution callout appears.

Finally, run focused verification first, then the required gates:

    npm run test:runner:generate
    npx shadow-cljs --force-spawn compile test && node out/test.js
    PLAYWRIGHT_BASE_URL=http://127.0.0.1:8080 npx playwright test --config tmp/playwright/reuse-existing.config.mjs tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer recommendation chart"
    npm run check
    npm test
    npm run test:websocket

For UI signoff, run browser QA per `/hyperopen/docs/FRONTEND.md` and `/hyperopen/docs/BROWSER_TESTING.md`, including the optimizer results surface across 375, 768, 1280, and 1440 widths, then finish with `npm run browser:cleanup`.

## Validation and Acceptance

The feature is accepted when hovering, tab-focusing, or clicking a point on the efficient-frontier plot reveals a small informational callout for that marker. The callout must use the marker's own identity, not a generic title, and must report the correct annualized expected return, annualized volatility, and Sharpe. Target and current portfolio callouts must also include gross and net exposure. Existing chart interactions, including clicking or dragging frontier sweep points to set a target and rerun, must keep working.

The UI should feel native to the optimizer v4 surface: compact, dark, data-dense, and not visually copied from the reference screenshot.

## Idempotence and Recovery

The implementation is additive to rendering. It does not mutate scenarios, optimizer results, current portfolio data, or solver behavior. If callout metrics are missing or non-finite, the display should render `n/a` instead of throwing or hiding the marker.

The tests and browser commands are safe to rerun. If browser-inspection or Browser MCP sessions are interrupted, run `npm run browser:cleanup` before the next browser validation pass.

## Outcomes & Retrospective

Implemented. The efficient-frontier chart now renders hover/focus callouts for frontier sweep points, target/current portfolio markers, and standalone/contribution asset overlay markers. Callouts show the plotted object name plus annualized expected return, annualized volatility, and Sharpe. Target and current portfolio callouts also include gross and net exposure; asset overlays include target weight.

The implementation keeps the solver and optimizer result payload unchanged. It splits reusable callout and overlay marker rendering into focused view namespaces so the chart owner remains under the namespace-size guard. SVG hitboxes make hover targets forgiving, and focus rings plus a computed focus outline make keyboard focus visible and design-review compliant. Overlay markers render above portfolio markers so clustered contribution points remain inspectable; deterministic Playwright uses hover for non-overlapped target/standalone points and focus for clustered contribution points.

Validation completed on 2026-04-28: `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js`, focused Playwright for the optimizer recommendation chart, browser design review for `portfolio-optimizer-results-route` across `review-375`, `review-768`, `review-1280`, and `review-1440`, `npm run browser:cleanup`, `npm run check`, `npm test`, and `npm run test:websocket`. After the namespace split, focused Playwright and browser design review were rerun; the final design-review bundle is `tmp/browser-inspection/design-review-2026-04-28T20-23-08-849Z-bd2aeaac`.
