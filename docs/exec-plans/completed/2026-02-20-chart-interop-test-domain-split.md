# Split Chart Interop Tests into Domain-Focused Namespaces

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

This plan builds on the production-side chart interop modularization documented in `/hyperopen/docs/exec-plans/completed/2026-02-16-chart-interop-solid-ddd-split.md` and `/hyperopen/docs/exec-plans/completed/2026-02-16-chart-interop-solid-ddd-followup-wave.md`.

## Purpose / Big Picture

The chart interop tests currently live in one large namespace, `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`, that mixes facade checks, module unit tests, DOM integration behavior, and cross-cutting helpers. That structure makes targeted maintenance expensive for humans and coding agents because finding relevant cases requires loading unrelated domains.

After this change, chart interop tests will be split into smaller namespaces aligned to production module boundaries under `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/*.cljs`. The result is faster test discovery, lower context overhead for AI-assisted edits, clearer SOLID/DDD ownership boundaries, and unchanged runtime behavior.

A user-visible proof is that tests for one concern (for example volume indicator overlays) can be found in one dedicated file, while required gates (`npm run check`, `npm test`, `npm run test:websocket`) still pass.

## Progress

- [x] (2026-02-20 00:00Z) Re-read `/hyperopen/.agents/PLANS.md` and local active ExecPlan conventions for structure and living sections.
- [x] (2026-02-20 00:00Z) Audited `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` and confirmed scope: 1398 lines, 50 `deftest` forms, multiple mixed domains.
- [x] (2026-02-20 00:00Z) Mapped production module boundaries in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/*.cljs` to candidate test namespaces.
- [x] (2026-02-20 00:00Z) Confirmed explicit runner wiring in `/hyperopen/test/test_runner.cljs`; split implementation must update both `:require` and `run-tests` lists.
- [x] (2026-02-20 00:00Z) Authored this active ExecPlan before implementation.
- [x] (2026-02-20 14:00Z) Extracted fake DOM and DOM utility helpers into `/hyperopen/test/hyperopen/views/trading_chart/test_support/fake_dom.cljs` and rewired chart interop tests to consume shared helpers.
- [x] (2026-02-20 14:00Z) Created module-focused namespaces under `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/` and moved all 50 original `deftest` forms by domain with unchanged assertions.
- [x] (2026-02-20 14:00Z) Reduced `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` from 1398 lines to a 327-line facade/integration slice.
- [x] (2026-02-20 14:00Z) Updated `/hyperopen/test/test_runner.cljs` with explicit requires and `run-tests` entries for every new chart interop test namespace.
- [x] (2026-02-20 14:00Z) Ran required gates successfully: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-20 15:06Z) Follow-up pass: further reduced facade file by moving domain-specific facade-entry tests into module files and adding dedicated chart creation namespace.
- [x] (2026-02-20 15:06Z) Validated follow-up topology via `npm test` (0 failures, 0 errors).

## Surprises & Discoveries

- Observation: test domains are interleaved instead of contiguous by module, so related cases are hard to discover.
  Evidence: baseline tests are at non-adjacent locations (`:263`, `:533`, `:1193`, `:1199`, `:1225`) in `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`.

- Observation: production code is already decomposed into focused interop modules, but test topology does not mirror that decomposition.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/` contains dedicated files (`series.cljs`, `legend.cljs`, `open_order_overlays.cljs`, `volume_indicator_overlay.cljs`, etc.), while tests are centralized in one file.

- Observation: fake DOM/test utilities needed by multiple domains are currently local private defs in the monolith.
  Evidence: `make-fake-element`, `make-fake-document`, `collect-text-content`, `find-dom-node`, and event helpers are defined at the top of `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`.

- Observation: moving tests by manual copy/paste would have high risk of subtle assertion drift because many forms are long and interleaved.
  Evidence: the file has 50 test forms across 1398 lines with deep nested literal structures and repeated helper usage.

- Observation: extracting exact `deftest` blocks to temporary files first made the split behavior-preserving and count-preserving.
  Evidence: final split still contains exactly 50 tests (`13 + 4 + 3 + 12 + 2 + 4 + 3 + 2 + 1 + 3 + 3 = 50`) and `npm test` remained green.

- Observation: keeping chart creation behavior inside the facade test namespace still left a mixed concern hotspot after the first split.
  Evidence: post-split `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` was 327 lines and still included chart creation/hide-volume behavior.

## Decision Log

- Decision: split tests by production module boundary rather than by arbitrary line-count chunks.
  Rationale: this aligns with DDD/SRP boundaries already in source code and reduces cross-domain context loading.
  Date/Author: 2026-02-20 / Codex

- Decision: keep a thin facade-level test namespace at `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` for wrapper/orchestration contracts.
  Rationale: module tests should not remove confidence in the public `chart-interop` API composition layer.
  Date/Author: 2026-02-20 / Codex

- Decision: extract reusable fake DOM helpers into a dedicated test-support namespace under `views/trading_chart/test_support`.
  Rationale: avoids duplicated fake DOM logic across legend, volume overlay, and open-order overlay test files.
  Date/Author: 2026-02-20 / Codex

- Decision: migrate tests by extracting raw `deftest` forms and reassembling files rather than rewriting tests by hand.
  Rationale: preserves test names and assertions exactly, minimizing migration risk while changing topology.
  Date/Author: 2026-02-20 / Codex

- Decision: keep some behavior tests in module-focused files even when they enter through `chart-interop` facade functions.
  Rationale: those tests still document the owning behavioral domain (for example open-order overlays, legend, and visible-range persistence) and improve discoverability for targeted maintenance.
  Date/Author: 2026-02-20 / Codex

- Decision: add `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/chart_creation_test.cljs` for chart-construction behaviors instead of keeping them in the thin facade wrapper file.
  Rationale: chart creation checks are integration-style behavior and form a coherent domain that would otherwise bloat the facade contract namespace.
  Date/Author: 2026-02-20 / Codex

## Outcomes & Retrospective

Implementation completed as a behavior-preserving test topology refactor.

- Added shared helper namespace: `/hyperopen/test/hyperopen/views/trading_chart/test_support/fake_dom.cljs`.
- Added domain-focused test namespaces under `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/`:
  - `chart_creation_test.cljs`
  - `visible_range_persistence_test.cljs`
  - `transforms_test.cljs`
  - `series_test.cljs`
  - `price_format_test.cljs`
  - `baseline_test.cljs`
  - `legend_test.cljs`
  - `markers_test.cljs`
  - `indicators_test.cljs`
  - `volume_indicator_overlay_test.cljs`
  - `open_order_overlays_test.cljs`
- Reduced facade file: `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` from 1398 lines to 327 lines.
- Follow-up reduction: `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` from 327 lines to 180 lines.
- Preserved total chart interop test count at 50 tests while distributing by domain.
- Updated explicit test runner wiring in `/hyperopen/test/test_runner.cljs` for all new namespaces.

Validation outcomes:

- `npm run check`: pass (all lint/docs checks green; `shadow-cljs compile app` and `shadow-cljs compile test` with 0 warnings)
- `npm test`: pass (`Ran 1168 tests containing 5445 assertions. 0 failures, 0 errors.`)
- `npm run test:websocket`: pass (`Ran 135 tests containing 587 assertions. 0 failures, 0 errors.`)
- Follow-up validation:
  - `npm test`: pass (`Ran 1168 tests containing 5445 assertions. 0 failures, 0 errors.`)

Residual risk is low. Main risk area is future drift if new chart-interop submodules are added without corresponding runner wiring updates in `/hyperopen/test/test_runner.cljs`.

## Context and Orientation

The public chart interop facade lives in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs` and delegates behavior to module namespaces under `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/`.

Current test topology does not match this structure. `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` currently mixes these concerns in one file:

- visible-range persistence behavior,
- transforms and series data synchronization,
- baseline subscription behavior,
- price format inference,
- legend rendering behavior,
- markers plugin lifecycle,
- indicator interop and pane allocation,
- volume indicator overlay integration,
- open-order overlay integration,
- facade wrapper delegation checks.

In this plan, “domain-focused namespace” means one test file whose primary ownership matches one production module or one intentional cross-module seam.

## Plan of Work

Milestone 1 extracts reusable fake DOM helpers. Create `/hyperopen/test/hyperopen/views/trading_chart/test_support/fake_dom.cljs` and move helper functions from the monolith test file without changing behavior.

Milestone 2 creates module-focused test namespaces under `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/` and moves test forms by behavior prefix:

- `visible_range_persistence_test.cljs`
- `transforms_test.cljs`
- `series_test.cljs`
- `price_format_test.cljs`
- `baseline_test.cljs`
- `legend_test.cljs`
- `markers_test.cljs`
- `indicators_test.cljs`
- `volume_indicator_overlay_test.cljs`
- `open_order_overlays_test.cljs`

Milestone 3 reduces `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` to a thin facade/integration slice that checks delegation, boundary guard behavior, and top-level chart creation wrappers.

Milestone 4 updates `/hyperopen/test/test_runner.cljs` with explicit namespace requires and `run-tests` entries for every new chart interop test namespace.

Milestone 5 runs required validation gates and updates this plan’s living sections with command outcomes, moved-test mapping, and final retrospective.

## Concrete Steps

1. Create shared test-support helper namespace:

   - `/hyperopen/test/hyperopen/views/trading_chart/test_support/fake_dom.cljs`

2. Create module test namespaces under `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/` and move existing `deftest` forms into the corresponding domain file, preserving assertions and names unless rename is needed to avoid collisions.

3. Rewrite `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` as a thin facade contract file.

4. Update `/hyperopen/test/test_runner.cljs`:

   - add new namespace requires,
   - add new namespaces to `run-tests`,
   - keep existing unrelated test wiring untouched.

5. Run required commands from `/hyperopen`:

       npm run check
       npm test
       npm run test:websocket

6. Update this ExecPlan (Progress, Surprises & Discoveries, Decision Log, Outcomes & Retrospective, revision note) with final evidence.

## Validation and Acceptance

Acceptance is met when all of the following are true:

1. `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` is no longer a large mixed-domain file and is reduced to facade/integration scope.
2. Module-focused chart interop test files exist under `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/` and each file has one primary concern.
3. Reusable fake DOM helpers are centralized in `/hyperopen/test/hyperopen/views/trading_chart/test_support/fake_dom.cljs`.
4. `/hyperopen/test/test_runner.cljs` includes every new chart interop test namespace in both namespace require and `run-tests` lists.
5. Required validation gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

This refactor is additive and can be applied incrementally. Safe recovery path: if a moved namespace fails to compile, temporarily keep the original tests in `chart_interop_test.cljs` while fixing the new namespace, then remove duplicates once green. No production behavior changes or destructive operations are required.

## Artifacts and Notes

Initial audit evidence:

- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`: 1398 lines, 50 `deftest` forms.
- Production module map:
  - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence.cljs`
  - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/transforms.cljs`
  - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/series.cljs`
  - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/price_format.cljs`
  - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/baseline.cljs`
  - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/legend.cljs`
  - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/markers.cljs`
  - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/indicators.cljs`
  - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/volume_indicator_overlay.cljs`
  - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays.cljs`

Final implementation evidence:

- Final chart interop test distribution by file:
  - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs`: 6 tests
  - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/visible_range_persistence_test.cljs`: 6 tests
  - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/transforms_test.cljs`: 3 tests
  - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/series_test.cljs`: 12 tests
  - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/price_format_test.cljs`: 2 tests
  - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/baseline_test.cljs`: 4 tests
  - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/legend_test.cljs`: 4 tests
  - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/markers_test.cljs`: 3 tests
  - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/indicators_test.cljs`: 1 test
  - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/volume_indicator_overlay_test.cljs`: 3 tests
  - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/open_order_overlays_test.cljs`: 3 tests
  - `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/chart_creation_test.cljs`: 3 tests
- Total preserved chart interop tests: 50.
- Line-count change:
  - before: `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` = 1398 lines
  - after initial split: `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` = 327 lines
  - after follow-up split: `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` = 180 lines
- Runner evidence: `npm test` output includes all new namespaces under `hyperopen.views.trading-chart.utils.chart-interop.*-test`.

## Interfaces and Dependencies

No production interface changes are required. This plan changes only test topology and runner wiring.

Expected new test namespaces:

- `hyperopen.views.trading-chart.test-support.fake-dom`
- `hyperopen.views.trading-chart.utils.chart-interop.visible-range-persistence-test`
- `hyperopen.views.trading-chart.utils.chart-interop.transforms-test`
- `hyperopen.views.trading-chart.utils.chart-interop.series-test`
- `hyperopen.views.trading-chart.utils.chart-interop.price-format-test`
- `hyperopen.views.trading-chart.utils.chart-interop.baseline-test`
- `hyperopen.views.trading-chart.utils.chart-interop.legend-test`
- `hyperopen.views.trading-chart.utils.chart-interop.markers-test`
- `hyperopen.views.trading-chart.utils.chart-interop.indicators-test`
- `hyperopen.views.trading-chart.utils.chart-interop.volume-indicator-overlay-test`
- `hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays-test`
- `hyperopen.views.trading-chart.utils.chart-interop.chart-creation-test`
- `hyperopen.views.trading-chart.utils.chart-interop-test` (thin facade retained)

Dependencies that must remain consistent:

- `cljs.test` assertion semantics.
- explicit namespace wiring in `/hyperopen/test/test_runner.cljs`.
- existing production chart interop module boundaries under `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/`.

Plan revision note: 2026-02-20 00:00Z - Initial plan created from chart interop test audit with module-aligned split strategy, fake DOM helper extraction, and required validation gates.
Plan revision note: 2026-02-20 14:00Z - Completed implementation: extracted fake DOM support, split chart interop tests into 10 module-focused namespaces plus thin facade file, updated test runner wiring, and passed all required validation gates.
Plan revision note: 2026-02-20 15:06Z - Follow-up split completed: moved remaining domain-heavy cases out of facade file, added `chart_creation_test`, and revalidated with `npm test`.
