# Fix optimizer vault frontier markers and names

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`. It is self-contained so a future contributor can continue from this file without relying on conversation history.

Tracked issue: `hyperopen-mzo4` ("Fix optimizer vault frontier markers and names").

## Purpose / Big Picture

The Portfolio Optimizer results frontier can now include Hyperliquid vaults as selected universe instruments. When a vault appears on the efficient-frontier chart, the current overlay tries to load a remote coin SVG from the vault address, which renders as a broken image. The hover card also shows the `vault:<address>` identity instead of the vault's human-readable name. After this change, users can scan vault positions on the frontier using a consistent inline vault marker and a deterministic short code, and hovering or focusing a vault marker shows the vault name.

The visible target is the designer-provided frontier treatment: vault overlays use a small cyan outlined stacked-layer marker with an adjacent short code such as `BCV`, while the hover card title uses a readable name such as `BTC Basis Carry Vault`.

## Progress

- [x] (2026-04-30 00:21Z) Created tracked issue `hyperopen-mzo4` with acceptance criteria for marker rendering, human-readable names, deterministic tests, and UI QA accounting.
- [x] (2026-04-30 00:22Z) Mapped the chart path from `src/hyperopen/views/portfolio/optimize/results_panel.cljs` to `frontier_chart.cljs` and `frontier_overlay_markers.cljs`.
- [x] (2026-04-30 00:22Z) Identified root causes: `frontier_overlay_markers.cljs` treats `vault:<address>` as a market icon key and `engine.cljs` builds overlay labels from `:coin` before `:name`.
- [x] (2026-04-30 00:27Z) Added RED tests for vault overlay label propagation, vault marker rendering without external image URLs, and short-code abbreviation behavior.
- [x] (2026-04-30 00:28Z) Ran the focused RED command. It compiled 1,446 files with 0 warnings and failed with 10 expected assertions: engine labels remained `vault:<address>`, and results markers still emitted remote vault-address image URLs.
- [x] (2026-04-30 00:35Z) Implemented vault label preference in `engine.cljs` and inline vault marker rendering plus deterministic short-code generation in `frontier_overlay_markers.cljs`.
- [x] (2026-04-30 00:36Z) Focused GREEN command passed: 15 tests, 209 assertions, 0 failures, 0 errors.
- [x] (2026-04-30 00:37Z) `npm run check` passed after splitting the new label helper and vault marker regression test to satisfy namespace-size guardrails.
- [x] (2026-04-30 00:37Z) `npm test` passed: 3,635 tests, 20,038 assertions, 0 failures, 0 errors.
- [x] (2026-04-30 00:38Z) `npm run test:websocket` passed: 461 tests, 2,798 assertions, 0 failures, 0 errors.
- [x] (2026-04-30 00:38Z) Focused Playwright optimizer chart regression passed on an alternate static-server port: 1 test passed.
- [x] (2026-04-30 00:39Z) Governed design review passed for `portfolio-optimizer-results-route` at widths `375`, `768`, `1280`, and `1440`; all six passes returned `PASS`, with only state-sampling blind spots for hover/active/disabled/loading states.
- [x] (2026-04-30 00:39Z) Browser-inspection cleanup succeeded and stopped `sess-1777509528337-2f1d5d`.
- [x] (2026-04-30 00:40Z) Closed tracked issue `hyperopen-mzo4` with validation summary.

## Surprises & Discoveries

- Observation: The broken icon is not a missing local asset. The overlay renderer constructs `https://app.hyperliquid.xyz/coins/<vault-address>.svg` because vault instruments are not recognized before calling `hyperopen.views.asset-icon/market-icon-url`.
  Evidence: `frontier_overlay_markers.cljs` derives `coin` from the raw `vault:<address>` instrument id, and `asset_icon.cljs` turns any non-blank non-`@` coin into a Hyperliquid coin SVG URL.

- Observation: The human vault name is present before solving, but the solver payload loses it for overlay labels.
  Evidence: `universe_candidates.cljs` and `actions.cljs` preserve `:name` and `:symbol` on vault draft instruments. `engine.cljs` `labels-by-instrument` currently prefers `:coin`, which is `vault:<address>` for vault instruments.

- Observation: The RED test failures match the expected missing behavior without compile errors.
  Evidence: `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.application.engine-test --test=hyperopen.views.portfolio.optimize.results-panel-test` failed with 10 assertions and 0 errors.

- Observation: The first `npm run check` failed on namespace-size exceptions after adding tests and engine logic directly to existing namespaces.
  Evidence: `src/hyperopen/portfolio/optimizer/application/engine.cljs` exceeded 600 lines and `test/hyperopen/views/portfolio/optimize/results_panel_test.cljs` exceeded 540 lines. Splitting `instrument_labels.cljs` and `frontier_overlay_markers_test.cljs` made `npm run lint:namespace-sizes` pass.

- Observation: The managed design-review command could not start a second Shadow server while an existing watcher was already running.
  Evidence: `npm run qa:design-ui -- --targets portfolio-optimizer-route --manage-local-app` failed with `shadow-cljs already running in project on http://localhost:9630`. Starting a read-only browser-inspection session against the existing local app and passing `--session-id` let `portfolio-optimizer-results-route` design review complete with `PASS`.

## Decision Log

- Decision: Keep vault markers inline in SVG instead of loading a new raster or external image.
  Rationale: An inline marker cannot break due to remote asset availability, matches the designer's cyan stacked-layer treatment, and remains deterministic in tests and screenshots.
  Date/Author: 2026-04-30 / Codex

- Decision: Use the preserved vault name as the overlay label and derive a separate short code for the visible chart tag.
  Rationale: The full name belongs in the hover card and ARIA label, while the chart needs a compact label that does not crowd nearby frontier points.
  Date/Author: 2026-04-30 / Codex

- Decision: Derive short codes from the human-readable vault name using a deterministic rule: prefer explicit `:abbreviation`, `:short-name`, or `:ticker` if supplied; otherwise drop a leading ticker-like token when it is followed by a descriptive phrase; use initials for three-token phrases; use a compact single keyword code for one-word names or two-word names ending in generic terms like `Vault`.
  Rationale: This makes labels stable across renders and produces designer-like labels such as `BCV` from `BTC Basis Carry Vault` while still producing readable fallbacks for arbitrary names.
  Date/Author: 2026-04-30 / Codex

## Outcomes & Retrospective

Implemented the optimizer results vault marker fix. The engine now preserves human-readable vault names in frontier overlay rows, and the results chart now draws vaults as inline SVG markers with deterministic short-code labels instead of remote image nodes. `BTC Basis Carry Vault` produces `BCV`, the callout and ARIA label use `BTC Basis Carry Vault`, and the marker path no longer requests `https://app.hyperliquid.xyz/coins/<vault-address>.svg`.

Validation passed across focused tests, full ClojureScript tests, websocket tests, `npm run check`, focused Playwright optimizer chart regression, and governed design review for the optimizer results route. The implementation modestly increases complexity by adding a small label helper namespace and private marker abbreviation helpers, but it reduces operational fragility by removing a broken remote-image path and making vault identity readable in the chart.

## Context and Orientation

The optimized results surface is rendered by `src/hyperopen/views/portfolio/optimize/results_panel.cljs`. Its frontier chart is delegated to `src/hyperopen/views/portfolio/optimize/frontier_chart.cljs`, which asks `src/hyperopen/views/portfolio/optimize/frontier_overlay_markers.cljs` to draw standalone and contribution overlays. An overlay point contains `:instrument-id`, `:label`, `:expected-return`, `:volatility`, and `:target-weight`.

Vault optimizer instruments use `:instrument-id` and `:coin` in the form `vault:<normalized-address>`, plus `:market-type :vault`, `:vault-address`, and usually `:name` and `:symbol`. The optimizer engine assembles overlay rows in `src/hyperopen/portfolio/optimizer/application/engine.cljs` by passing `:labels-by-instrument` into `src/hyperopen/portfolio/optimizer/domain/frontier_overlays.cljs`.

The existing non-vault marker path should stay unchanged. Spot and perp overlays may continue using `hyperopen.views.asset-icon/market-icon-url` and fall back to text when a market icon is unavailable. Only vault overlays should bypass external image URLs and draw the inline vault marker.

## Plan of Work

First, add failing tests before source edits. Extend `test/hyperopen/portfolio/optimizer/application/engine_test.cljs` with a solved run that includes a vault instrument named `BTC Basis Carry Vault` and assert that both standalone and contribution overlay rows use that name as `:label`, not `vault:<address>`. Extend `test/hyperopen/views/portfolio/optimize/results_panel_test.cljs` with a vault overlay point and assert that the marker contains no `:image`, renders vault-specific inline icon nodes, renders the short code `BCV`, and the callout contains `BTC Basis Carry Vault`.

Second, update `src/hyperopen/portfolio/optimizer/application/engine.cljs`. Change `labels-by-instrument` so vault instruments prefer `:name`, then `:symbol`, then `:coin`, then the instrument id. Non-vault instruments should preserve the existing coin-first behavior.

Third, update `src/hyperopen/views/portfolio/optimize/frontier_overlay_markers.cljs`. Add vault detection for overlay points by `:market-type :vault` if present or by `instrument-id` prefix `vault:`. Add short-code helpers and an inline SVG marker helper that draws a small cyan rounded square, stacked-layer icon paths, and an adjacent short-code pill. Use this helper for both standalone and contribution vault overlays. Keep existing image/text behavior for spot and perp overlays.

Fourth, update styling only if the SVG helper needs a shared class. Prefer SVG attributes for marker geometry and color because these markers live inside the chart coordinate system. If CSS is added to `src/styles/main.css`, it must be narrowly scoped under `.portfolio-optimizer-v4` and use existing optimizer tokens.

Fifth, run focused tests and validation. Start with the new focused ClojureScript tests and then run the required repository gates from `AGENTS.md`: `npm run check`, `npm test`, and `npm run test:websocket`. Because this is UI-facing, also account for the six browser-QA passes at widths `375`, `768`, `1280`, and `1440`; if governed browser-inspection tooling blocks, record `BLOCKED` with evidence and run `npm run browser:cleanup`.

## Concrete Steps

Run commands from `/Users/barry/projects/hyperopen`.

After adding RED tests, run:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.application.engine-test --test=hyperopen.views.portfolio.optimize.results-panel-test

Expected RED result before implementation: the new assertions fail because vault overlay labels are still `vault:<address>` and vault markers still use an image URL based on the vault address.

After implementation, run the same focused command and expect 0 failures and 0 errors for the selected tests. Then run:

    npm run check
    npm test
    npm run test:websocket

For browser QA, run the smallest relevant deterministic Playwright coverage if the optimized results scenario can be reached reliably:

    npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs --grep "portfolio optimizer"

Then run governed design review or explicitly record the blocker:

    npm run qa:design-ui -- --targets portfolio-optimizer-route --manage-local-app
    npm run browser:cleanup

## Validation and Acceptance

Acceptance requires focused tests to prove three behaviors. First, optimizer overlay rows for vault instruments use human-readable names from the selected universe instead of `vault:<address>`. Second, vault overlay markers in the results frontier contain no external image node and cannot produce a broken icon. Third, visible vault tags are deterministic short codes derived from the vault name, with `BTC Basis Carry Vault` producing `BCV`.

Manual or browser validation should confirm that `/portfolio/optimize` results show vault markers with a cyan stacked-layer icon and compact label, and that hover/focus callouts show the vault name plus expected return, volatility, Sharpe, and target weight rows. All browser-inspection sessions created during validation must be cleaned up before signoff.

## Idempotence and Recovery

The changes are additive and local. Re-running tests and browser QA is safe. If the short-code heuristic produces a surprising label for a real vault, the implementation should still display the full name in the callout and ARIA label; the short code can be adjusted without changing optimizer data contracts. If importing a Lucide icon increases bundle or interop risk, keep the inline path data local to the marker helper and document it as a stable SVG shape.

## Artifacts and Notes

Important files for this plan:

    src/hyperopen/portfolio/optimizer/application/instrument_labels.cljs
    src/hyperopen/portfolio/optimizer/application/engine.cljs
    src/hyperopen/views/portfolio/optimize/frontier_overlay_markers.cljs
    test/hyperopen/portfolio/optimizer/application/engine_test.cljs
    test/hyperopen/views/portfolio/optimize/frontier_overlay_markers_test.cljs
    test/hyperopen/views/portfolio/optimize/results_panel_test.cljs
    tools/playwright/test/portfolio-regressions.spec.mjs

Plan revision note: 2026-04-30 00:22Z - Created the active ExecPlan after read-only source exploration and issue creation. The plan scopes the work to marker rendering and overlay labels only; it intentionally does not reopen vault universe search or vault history loading.

Plan revision note: 2026-04-30 00:28Z - Added RED test progress and evidence. The failing assertions prove the current implementation still emits address labels and broken vault icon URLs.

Plan revision note: 2026-04-30 00:36Z - Recorded the source implementation and focused GREEN test evidence. Full gates and browser QA are still pending.

Plan revision note: 2026-04-30 00:40Z - Recorded full validation and browser-QA evidence, updated outcomes, and moved the completed plan from `docs/exec-plans/active/` to `docs/exec-plans/completed/`.

Plan revision note: 2026-04-30 00:41Z - Recorded closure of tracked issue `hyperopen-mzo4`.

## Interfaces and Dependencies

At completion, `hyperopen.portfolio.optimizer.domain.frontier-overlays/overlay-series` should continue returning the same shape. The only intended data change is the value of `:label` for vault rows: it should be a human-readable vault name when one exists.

At completion, `hyperopen.views.portfolio.optimize.frontier-overlay-markers/marker` should continue accepting the existing `opts` map from `frontier_chart.cljs`. It may add private helpers for vault detection, abbreviation, and inline SVG marker content, but it should not require callers to pass new options.
