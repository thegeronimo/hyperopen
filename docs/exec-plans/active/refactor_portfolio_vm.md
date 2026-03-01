# Refactor Portfolio ViewModel to Enforce Complexity Bounds

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The `src/hyperopen/views/portfolio/vm.cljs` namespace is currently 1,639 lines of code (LOC), violating the `ARCHITECTURE.md` requirement that namespaces must remain under 500 LOC. This monolithic file mixes multiple distinct application responsibilities: React-specific chart math, benchmark caching/filtering, heavy time-series data alignment, and asynchronous web-worker metric computation. By decomposing this namespace into smaller, highly-cohesive, SRP-compliant namespaces within a `vm/` subdirectory, we will improve maintainability, reduce cognitive load for both human engineers and AI agents, and ensure strict compliance with architectural constraints. The resulting code will expose the exact same View Model map so that the `portfolio_view.cljs` remains completely unaffected.

## Progress

- [ ] (Pending) Create execution plan.
- [ ] (Pending) Read and analyze `src/hyperopen/views/portfolio/vm.cljs` to map out its logical sections.
- [ ] (Pending) Create `src/hyperopen/views/portfolio/vm/constants.cljs` (Options, labels, and shared constants).
- [ ] (Pending) Create `src/hyperopen/views/portfolio/vm/history.cljs` (Series alignment, window rebasing, PNL delta math).
- [ ] (Pending) Create `src/hyperopen/views/portfolio/vm/benchmarks.cljs` (Sorting, caching, searching, vault logic).
- [ ] (Pending) Create `src/hyperopen/views/portfolio/vm/chart_math.cljs` (Domain calc, SVG math, ticks, degenerates).
- [ ] (Pending) Create `src/hyperopen/views/portfolio/vm/metrics_bridge.cljs` (Web Worker dispatch, sync fallback, cache invalidation).
- [ ] (Pending) Create `src/hyperopen/views/portfolio/vm/equity.cljs` (Account equity, vault equity, totals).
- [ ] (Pending) Refactor `src/hyperopen/views/portfolio/vm.cljs` to import sub-systems and compose the final model map.
- [ ] (Pending) Run test suite (`test/hyperopen/views/portfolio/vm_test.cljs`) and verify nothing broke.
- [ ] (Pending) Verify codebase passes `npm run check`, `npm test`, and `npm run test:websocket`.

## Surprises & Discoveries

(None yet)

## Decision Log

- Decision: Decompose `vm.cljs` into sub-namespaces (`chart-math`, `benchmarks`, `history`, `metrics-bridge`, `equity`, `constants`) and keep `vm.cljs` as the final aggregator function `portfolio-vm [state]`.
  Rationale: Ensures the UI component `portfolio_view.cljs` doesn't need to be rewritten, and keeps all state-derivation concerns cleanly bounded per SRP.
  Date/Author: 2026-03-01 / Gemini CLI

## Outcomes & Retrospective

(Pending completion)

## Context and Orientation

The current state of `src/hyperopen/views/portfolio/vm.cljs` is a "God file" representing the data layer for the entire Portfolio page. It processes everything from fetching fills from cache to doing standard deviation math for chart axis rendering. 

Key files involved:
- `src/hyperopen/views/portfolio/vm.cljs` (To be refactored into an aggregator)
- `src/hyperopen/views/portfolio/vm/constants.cljs` (New file)
- `src/hyperopen/views/portfolio/vm/history.cljs` (New file)
- `src/hyperopen/views/portfolio/vm/benchmarks.cljs` (New file)
- `src/hyperopen/views/portfolio/vm/chart_math.cljs` (New file)
- `src/hyperopen/views/portfolio/vm/metrics_bridge.cljs` (New file)
- `src/hyperopen/views/portfolio/vm/equity.cljs` (New file)
- `test/hyperopen/views/portfolio/vm_test.cljs` (Existing test suite)

## Plan of Work

1.  **Extract Constants:** Move static vectors (`summary-scope-options`, `chart-tab-options`) to `constants.cljs`.
2.  **Extract Chart Math:** Move SVG rendering math (`chart-domain`, `chart-y-ticks`, `chart-line-path`) to `chart_math.cljs`.
3.  **Extract Equity Logic:** Move account balance aggregators (`compute-total-equity`, `perp-account-equity`) to `equity.cljs`.
4.  **Extract Benchmarks:** Extract heavy options-filtering and vault ranking logic to `benchmarks.cljs`.
5.  **Extract History Data:** Move point parsing and array alignment (`aligned-benchmark-return-rows`, `history-window-rows`) to `history.cljs`.
6.  **Extract Worker Logic:** Move the `defonce` atom cache and the `request-metrics-computation!` logic to `metrics_bridge.cljs`.
7.  **Refactor Main VM:** Update `portfolio/vm.cljs` to `require` the new namespaces and act as the central dispatcher for `portfolio-vm`.
8.  **Run Tests:** Continuously run `npm test` to ensure the view model output map exactly matches expectations.

## Validation and Acceptance

- The file `src/hyperopen/views/portfolio/vm.cljs` will be significantly smaller (under 500 LOC).
- New namespaces will be created and individually remain under 500 LOC.
- `npm run check` passes without linter errors.
- `npm test` passes.
- `npm run test:websocket` passes.