# Anonymous Function Centralization Roadmap (Lambda Audit Follow-Through)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The repository currently contains many anonymous functions (`fn` and `#(...)`) that repeat the same business logic in multiple places. This makes behavior drift more likely because a future change to one copy can miss another copy.

After implementing this plan, a developer should be able to change shared sorting rules, indicator math kernels, async error-handling behavior, and common test stubs in one reusable definition rather than editing many inlined lambdas. The user-visible behavior must remain the same, but refactor velocity and change safety should improve because logic ownership becomes explicit and centralized.

## Progress

- [x] (2026-02-23 17:50Z) Authored this ExecPlan from the repository-wide anonymous-function audit and converted findings into a priority-ordered implementation roadmap.
- [x] (2026-02-23 18:22Z) Implemented Milestone 0 by adding `/hyperopen/tools/anonymous_function_duplication_report.clj`, generating checked-in baseline artifacts under `/hyperopen/docs/exec-plans/active/artifacts/`, and linking reproducible commands plus evidence from this plan.
- [x] (2026-02-23 18:29Z) Reworked Milestone 0 tooling structure into categorized namespaces (`cli-options`, `filesystem`, `analyzer`, `report-output`) and removed the generic single-file script naming.
- [x] (2026-02-23 18:39Z) Implemented Milestone 1 by adding `/hyperopen/src/hyperopen/views/account_info/sort_kernel.cljs`, migrating account-info tab sort orchestration to the shared kernel, and adding shared kernel tests in `/hyperopen/test/hyperopen/views/account_info/sort_kernel_test.cljs`.
- [x] (2026-02-23 18:51Z) Implemented Milestone 2 by extending `/hyperopen/src/hyperopen/domain/trading/indicators/math.cljs` with shared kernels (`finite-subtract`, band helpers, guarded ratio helpers, true-range helpers, ROC% helpers, HL2 helpers), migrating indicator families to consume those kernels, and adding parity coverage in `/hyperopen/test/hyperopen/domain/trading/indicators/math_kernels_test.cljs`, `/hyperopen/test/hyperopen/domain/trading/indicators/heavy_algorithms_test.cljs`, and `/hyperopen/test/hyperopen/domain/trading/indicators/family_parity_test.cljs`.
- [x] (2026-02-23 19:00Z) Implemented Milestone 3 by adding `/hyperopen/src/hyperopen/api/promise_effects.cljs`, migrating repeated promise success/error callback lambdas in `/hyperopen/src/hyperopen/api/fetch_compat.cljs`, `/hyperopen/src/hyperopen/runtime/api_effects.cljs`, `/hyperopen/src/hyperopen/startup/collaborators.cljs`, `/hyperopen/src/hyperopen/order/effects.cljs`, and `/hyperopen/src/hyperopen/api/market_metadata/facade.cljs`, and adding helper coverage in `/hyperopen/test/hyperopen/api/promise_effects_test.cljs`.
- [x] (2026-02-23 19:13Z) Implemented Milestone 4 by centralizing shared `pad2` time formatting helpers in `/hyperopen/src/hyperopen/utils/formatting.cljs`, migrating duplicated call-site lambdas in `/hyperopen/src/hyperopen/account/history/effects.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs`, and `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`, extracting chart interop numeric coercion helpers into `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/numeric.cljs`, centralizing websocket descriptor matcher builders in `/hyperopen/src/hyperopen/websocket/health.cljs`, centralizing repeated websocket tier lambdas in `/hyperopen/src/hyperopen/websocket/client.cljs`, and centralizing wallet accounts-list connection branching in `/hyperopen/src/hyperopen/wallet/core.cljs`.
- [x] (2026-02-23 19:33Z) Implemented Milestone 5 by adding shared test-support helpers in `/hyperopen/test/hyperopen/test_support/async.cljs`, `/hyperopen/test/hyperopen/test_support/api_stubs.cljs`, `/hyperopen/test/hyperopen/test_support/hiccup_selectors.cljs`, and `/hyperopen/test/hyperopen/views/trading_chart/test_support/series.cljs`; refactoring the targeted endpoint/trading/tab/chart tests to use shared async catch handlers, API/signing stubs, pagination predicates, and chart-series spies; and capturing a post-milestone duplication snapshot at `/hyperopen/docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-test-post-milestone5.txt`.
- [x] (2026-02-23 18:53Z) Required validation gates pass after Milestone 2 changes (`npm run check`, `npm test`, `npm run test:websocket`).
- [x] (2026-02-23 19:00Z) Required validation gates pass after Milestone 3 changes (`npm run check`, `npm test`, `npm run test:websocket`).
- [x] (2026-02-23 19:13Z) Required validation gates pass after Milestone 4 changes (`npm run check`, `npm test`, `npm run test:websocket`).
- [x] (2026-02-23 19:33Z) Required validation gates pass after Milestone 5 changes (`npm run check`, `npm test`, `npm run test:websocket`).

## Surprises & Discoveries

- Observation: The largest production duplication cluster is account-info tab column sorting, where five tabs independently reimplement nearly the same sort lambda architecture.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`.
- Observation: Indicator modules duplicate arithmetic lambdas across families (oscillators, trend, volatility), including finite guarded subtraction, ATR band construction, and percentage ratio formulas.
  Evidence: `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/classic.cljs`, `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/patterns.cljs`, `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/statistics.cljs`, `/hyperopen/src/hyperopen/domain/trading/indicators/trend/strength.cljs`, `/hyperopen/src/hyperopen/domain/trading/indicators/volatility/dispersion.cljs`, `/hyperopen/src/hyperopen/domain/trading/indicators/volatility/channels.cljs`, `/hyperopen/src/hyperopen/domain/trading/indicators/volatility/range.cljs`.
- Observation: Promise pipelines repeat almost identical `.then`/`.catch` lambdas that mutate store state and rethrow with `js/Promise.reject`.
  Evidence: `/hyperopen/src/hyperopen/api/fetch_compat.cljs`, `/hyperopen/src/hyperopen/runtime/api_effects.cljs`, `/hyperopen/src/hyperopen/startup/collaborators.cljs`, `/hyperopen/src/hyperopen/order/effects.cljs`, `/hyperopen/src/hyperopen/api/market_metadata/facade.cljs`.
- Observation: Test duplication is substantial and mostly concentrated in reusable patterns (async failure handler, endpoint stubs, pagination node predicates, signing stubs).
  Evidence: `101` occurrences of `(fn [err] (is false (str "Unexpected error: " err)) (done))` across `21` test files; repeated clusters in `/hyperopen/test/hyperopen/api/endpoints/*.cljs`, `/hyperopen/test/hyperopen/api/trading/*.cljs`, and `/hyperopen/test/hyperopen/views/account_info/tabs/*_test.cljs`.
- Observation: There is existing shared UI table infrastructure (`account_info/table.cljs`), but sorting logic itself is not centralized there yet.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/table.cljs` currently centralizes headers/layout only.
- Observation: A full parser-based baseline can be captured without parse failures across the complete `src` and `test` ClojureScript surfaces.
  Evidence: `/hyperopen/docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-src-baseline.txt` and `/hyperopen/docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-test-baseline.txt` both report `parse_errors=0` (`241` `src` files and `209` `test` files scanned).
- Observation: The parity-test target file named in Milestone 2 (`/hyperopen/test/hyperopen/domain/trading/indicators/math_kernels_test.cljs`) already existed with benchmark and parity coverage, so Milestone 2 should extend this file instead of introducing a duplicate test namespace.
  Evidence: `/hyperopen/test/hyperopen/domain/trading/indicators/math_kernels_test.cljs` existed before this milestone and now includes shared-kernel tests for finite subtraction, band arithmetic, safe percent ratios, true range, ROC%, and HL2.
- Observation: True-range logic had two first-candle conventions in production code (`prev-close = nil` fallback vs `prev-close = current close`). The shared kernel supports both call patterns without forcing semantic drift.
  Evidence: `/hyperopen/src/hyperopen/domain/trading/indicators/math.cljs` now provides `true-range-at`, `true-range-index`, and `true-range-values`; `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/helpers.cljs` preserves its first-candle behavior while `/hyperopen/src/hyperopen/domain/trading/indicators/trend/strength.cljs` and `/hyperopen/src/hyperopen/domain/trading/indicators/volatility/range.cljs` use the canonical indexed variant.
- Observation: Time formatting and chart numeric coercion already had stable behavior contracts in tests, so milestone 4 centralization could be done by extracting helpers and rewiring call sites without changing user-visible output.
  Evidence: `/hyperopen/test/hyperopen/utils/formatting_test.cljs`, `/hyperopen/test/hyperopen/views/l2_orderbook_view_test.cljs`, `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/baseline_test.cljs`, and `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/price_format_test.cljs` remained green after helper extraction.
- Observation: Milestone 5 reduced test-scope anonymous-function duplication metrics while keeping the full suite green.
  Evidence: `/hyperopen/docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-test-post-milestone5.txt` reports `total_lambda_arities=2296` (from `2369` baseline), `duplicate_groups=310` (from `322`), `duplicate_occurrences=1161` (from `1239`), `cross_file_duplicate_groups=133` (from `139`), and `large_duplicate_groups_size_ge_10=104` (from `115`).

## Decision Log

- Decision: Prioritize production logic centralization before test-only cleanup.
  Rationale: Production duplications carry the highest behavioral drift risk and should be reduced first; test helper extraction can safely follow once production seams stabilize.
  Date/Author: 2026-02-23 / Codex
- Decision: Start with account-info sorters as Priority 1.
  Rationale: This is a high-frequency, low-risk, high-readability refactor with clear deterministic acceptance criteria and concentrated file surface.
  Date/Author: 2026-02-23 / Codex
- Decision: Treat indicator math lambda extraction as Priority 2 with strict parity checks.
  Rationale: Indicator logic is numerically sensitive, so centralization must be coupled with direct parity tests to avoid silent output drift.
  Date/Author: 2026-02-23 / Codex
- Decision: Consolidate promise callback lambdas through small pure helper combinators instead of broad framework abstractions.
  Rationale: The repository favors deterministic and explicit runtime behavior; lightweight helpers reduce duplication without obscuring effects.
  Date/Author: 2026-02-23 / Codex
- Decision: Keep public APIs stable while centralizing internals.
  Rationale: AGENTS hard guardrails require scoped changes and preserving public interfaces unless explicitly requested.
  Date/Author: 2026-02-23 / Codex
- Decision: Check in a reproducible baseline utility script and captured outputs as Milestone 0 artifacts.
  Rationale: Future implementation threads need a stable way to re-run and compare duplication metrics before and after each milestone without reconstructing ad hoc tooling.
  Date/Author: 2026-02-23 / Codex
- Decision: Use a descriptive entrypoint name and category-based helper namespaces for baseline tooling.
  Rationale: The tool now communicates intent at the path level and keeps parsing, filesystem, analysis, and rendering concerns separated for maintainability.
  Date/Author: 2026-02-23 / Codex
- Decision: Implement Milestone 2 by extending the existing `/hyperopen/src/hyperopen/domain/trading/indicators/math.cljs` namespace rather than creating a sibling `math_kernels.cljs`.
  Rationale: Most target indicator modules already depend on `math.cljs`, so extending it minimizes wiring churn while preserving stable public interfaces.
  Date/Author: 2026-02-23 / Codex
- Decision: Preserve the pre-existing first-candle true-range semantics per caller while centralizing the arithmetic kernel.
  Rationale: This avoids subtle numeric drift in edge data while still removing repeated true-range formulas and index scaffolding from indicator modules.
  Date/Author: 2026-02-23 / Codex
- Decision: Place Milestone 3 promise helper combinators in `/hyperopen/src/hyperopen/api/promise_effects.cljs`.
  Rationale: The refactor target is API-facing effect behavior across API/runtime/startup/order namespaces, and the API namespace avoids introducing a reverse dependency from API code into runtime-only modules.
  Date/Author: 2026-02-23 / Codex
- Decision: Place Milestone 4 numeric coercion helpers in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/numeric.cljs` and reuse them from both baseline and price-format modules.
  Rationale: This keeps chart-interop parsing behavior consistent across modules while avoiding dependencies from chart code into unrelated namespaces.
  Date/Author: 2026-02-23 / Codex
- Decision: Place Milestone 5 helper surfaces in `/hyperopen/test/hyperopen/test_support/` and keep chart-series construction helpers in `/hyperopen/test/hyperopen/views/trading_chart/test_support/series.cljs`.
  Rationale: Cross-domain test helpers (`async`, `api_stubs`, `hiccup_selectors`) stay globally reusable while chart-specific JS spy scaffolding remains close to chart tests.
  Date/Author: 2026-02-23 / Codex

## Outcomes & Retrospective

Milestones 0, 1, 2, 3, 4, and 5 are complete. The repository now contains a reusable baseline generator at `/hyperopen/tools/anonymous_function_duplication_report.clj`, checked-in baseline outputs at `/hyperopen/docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-*.txt`, a shared account-info sorting kernel at `/hyperopen/src/hyperopen/views/account_info/sort_kernel.cljs`, a centralized indicator-math kernel surface in `/hyperopen/src/hyperopen/domain/trading/indicators/math.cljs`, a shared promise branch helper surface in `/hyperopen/src/hyperopen/api/promise_effects.cljs`, formatting/parsing/matcher helpers from milestone 4, and centralized test helper surfaces for milestone 5.

Milestone 2 migrated indicator arithmetic duplicates across oscillators, trend, volatility, and price modules to shared helpers for finite subtraction, band arithmetic, safe percent ratios, true-range calculation, ROC-percent derivation, and HL2 median derivation. The parity harness now includes explicit kernel tests in `/hyperopen/test/hyperopen/domain/trading/indicators/math_kernels_test.cljs` plus deterministic integration checks in `/hyperopen/test/hyperopen/domain/trading/indicators/heavy_algorithms_test.cljs` and `/hyperopen/test/hyperopen/domain/trading/indicators/family_parity_test.cljs`. Required validation gates are green for this milestone (`npm run check`, `npm test`, `npm run test:websocket`).

Milestone 3 migrated repeated promise `.then`/`.catch` callback lambdas to shared combinators (`apply-success-and-return`, `apply-error-and-reject`, `log-error-and-reject`, `log-apply-error-and-reject`, `reject-error`) in `/hyperopen/src/hyperopen/api/promise_effects.cljs`. API/runtime/startup/order/market-metadata flows now reuse these helpers while preserving existing signatures and return payload behavior. New coverage in `/hyperopen/test/hyperopen/api/promise_effects_test.cljs` validates helper behavior directly, and required validation gates are green for this milestone (`npm run check`, `npm test`, `npm run test:websocket`).

Milestone 4 centralized repeated formatting/time/parsing and websocket/wallet matcher lambdas by introducing shared time helpers in `/hyperopen/src/hyperopen/utils/formatting.cljs`, chart interop numeric coercion helpers in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/numeric.cljs`, reusable websocket descriptor matcher builders in `/hyperopen/src/hyperopen/websocket/health.cljs`, and a shared wallet accounts-connection branch helper in `/hyperopen/src/hyperopen/wallet/core.cljs`. Target call sites in account history, funding history, L2 orderbook, chart baseline/price format, and websocket client now reuse named helpers while preserving behavior. Required validation gates are green for this milestone (`npm run check`, `npm test`, `npm run test:websocket`).

Milestone 5 centralized repeated test lambdas through new helper modules: async catch handler helpers in `/hyperopen/test/hyperopen/test_support/async.cljs`, endpoint/signing stub helpers in `/hyperopen/test/hyperopen/test_support/api_stubs.cljs`, pagination selector predicates in `/hyperopen/test/hyperopen/test_support/hiccup_selectors.cljs`, and chart-series JS stub builders in `/hyperopen/test/hyperopen/views/trading_chart/test_support/series.cljs`. Refactors in `/hyperopen/test/hyperopen/api/endpoints/account_test.cljs`, `/hyperopen/test/hyperopen/api/endpoints/market_test.cljs`, `/hyperopen/test/hyperopen/api/endpoints/orders_test.cljs`, `/hyperopen/test/hyperopen/api/trading/session_invalidation_test.cljs`, `/hyperopen/test/hyperopen/api/trading/sign_and_submit_test.cljs`, `/hyperopen/test/hyperopen/views/account_info/tabs/funding_history_test.cljs`, `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs`, `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs`, and `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/series_test.cljs` now reuse those helpers while keeping assertions explicit. Required validation gates are green for this milestone (`npm run check`, `npm test`, `npm run test:websocket`), and post-milestone duplication counts in test scope decreased versus baseline.

## Context and Orientation

In this repository, a “lambda” means an anonymous function written as either `(fn [args] ...)` or shorthand `#(...)`. These forms are common and useful, but repeated inlined lambdas with equivalent bodies make maintenance harder.

This plan targets centralization where repeated lambdas encode domain rules, not where lambdas are trivial one-off adapters. A one-off event callback can remain inline; repeated business transforms, sorting kernels, and error propagation paths should move into named reusable functions.

The work is split into five implementation milestones after a baseline capture step. Each milestone is independently shippable and should keep behavior unchanged.

The main production areas in scope are:

- Account-info tab sorting in `/hyperopen/src/hyperopen/views/account_info/tabs/*.cljs`.
- Indicator math transforms in `/hyperopen/src/hyperopen/domain/trading/indicators/**/*.cljs` and common math helpers in `/hyperopen/src/hyperopen/domain/trading/indicators/math.cljs`.
- Promise-based effect adapters in `/hyperopen/src/hyperopen/api/*.cljs`, `/hyperopen/src/hyperopen/runtime/*.cljs`, `/hyperopen/src/hyperopen/startup/*.cljs`, and `/hyperopen/src/hyperopen/order/*.cljs`.
- Formatting/parsing and websocket topic matching in `/hyperopen/src/hyperopen/utils/formatting.cljs`, `/hyperopen/src/hyperopen/account/history/effects.cljs`, `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/*.cljs`, `/hyperopen/src/hyperopen/websocket/*.cljs`, and `/hyperopen/src/hyperopen/wallet/core.cljs`.

The main test areas in scope are:

- API endpoint and trading tests in `/hyperopen/test/hyperopen/api/**`.
- Account-info tab UI tests in `/hyperopen/test/hyperopen/views/account_info/tabs/**`.
- Chart interop tests in `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/**`.

## Plan of Work

### Milestone 0 (Baseline): Capture and Check in Duplication Baselines

Before refactoring, create a reproducible baseline artifact using `/hyperopen/tools/anonymous_function_duplication_report.clj` and check in outputs under `/hyperopen/docs/exec-plans/active/artifacts/`. Record duplicate lambda counts and high-value clusters so future threads can measure real reduction.

This milestone is complete. Baseline outputs are checked in at `/hyperopen/docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-src-baseline.txt` and `/hyperopen/docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-test-baseline.txt`, with summary and replay commands in `/hyperopen/docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-baseline.md`.

### Milestone 1 (Priority 1): Centralize Account-Info Sort Lambdas

Create a reusable sorting kernel for account-info tabs in `/hyperopen/src/hyperopen/views/account_info/table.cljs` or a new sibling module `/hyperopen/src/hyperopen/views/account_info/sort_kernel.cljs` (preferred when function count would otherwise bloat `table.cljs`).

Move repeated sort orchestration logic to named functions: column accessor selection, default fallback accessor, optional deterministic tie-breaker, and direction handling. Keep tab-specific field semantics local, but remove repeated mechanical lambda scaffolding.

Target files:

- `/hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`

Add shared tests for the new kernel in `/hyperopen/test/hyperopen/views/account_info/sort_kernel_test.cljs`, and keep existing tab behavior tests green.

### Milestone 2 (Priority 2): Centralize Indicator Math Lambdas into Named Kernels

Extend `/hyperopen/src/hyperopen/domain/trading/indicators/math.cljs` (or a new `/hyperopen/src/hyperopen/domain/trading/indicators/math_kernels.cljs`) with reusable helpers for repeated arithmetic lambdas.

Focus on duplicated transforms confirmed by the audit:

- finite-guarded subtraction (`a - b` only when both finite)
- band arithmetic (`base ± multiplier * spread`)
- safe percentage ratio (`100 * numerator / denominator` with denominator checks)
- repeated true-range index calculation
- repeated ROC-percent map-by-index logic
- repeated HL2 median index calculation

Replace inlined duplicates in:

- `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/helpers.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/momentum.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/classic.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/patterns.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/statistics.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/oscillators/structure.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/trend/strength.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/volatility/channels.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/volatility/dispersion.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/volatility/range.cljs`
- `/hyperopen/src/hyperopen/domain/trading/indicators/price.cljs`

Use parity tests to prove no numeric behavior changes, primarily in `/hyperopen/test/hyperopen/domain/trading/indicators/heavy_algorithms_test.cljs`, `/hyperopen/test/hyperopen/domain/trading/indicators/family_parity_test.cljs`, and `/hyperopen/test/hyperopen/domain/trading/indicators/math_kernels_test.cljs`.

### Milestone 3 (Priority 3): Centralize Repeated Promise Success/Error Lambdas

Create a small helper module for promise effect branches, such as `/hyperopen/src/hyperopen/runtime/promise_effects.cljs` or `/hyperopen/src/hyperopen/api/promise_effects.cljs`.

The helper should centralize repeated patterns such as:

- apply-success-and-return payload (`swap!` then return rows/data)
- apply-error-and-reject (`swap!` error projection then `js/Promise.reject`)
- optional logging plus error application plus reject

Refactor repeated lambdas in:

- `/hyperopen/src/hyperopen/api/fetch_compat.cljs`
- `/hyperopen/src/hyperopen/runtime/api_effects.cljs`
- `/hyperopen/src/hyperopen/startup/collaborators.cljs`
- `/hyperopen/src/hyperopen/order/effects.cljs`
- `/hyperopen/src/hyperopen/api/market_metadata/facade.cljs`

Keep function signatures and caller APIs stable.

### Milestone 4 (Priority 4): Centralize Formatting/Parsing/Websocket Matcher Lambdas

Extract and reuse the following repeated helpers:

- `pad2` formatting lambda currently duplicated across time-formatting call sites.
- numeric coercion + finite filter pipeline duplicated in chart interop.
- websocket user descriptor mapping repeated for several topic matchers.
- repeated “accounts list to connected/disconnected state” logic in wallet connection handlers.

Target files:

- `/hyperopen/src/hyperopen/account/history/effects.cljs`
- `/hyperopen/src/hyperopen/utils/formatting.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs`
- `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/baseline.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/price_format.cljs`
- `/hyperopen/src/hyperopen/websocket/health.cljs`
- `/hyperopen/src/hyperopen/websocket/client.cljs`
- `/hyperopen/src/hyperopen/wallet/core.cljs`

This milestone is complete when these repeated bodies are replaced by named helpers and existing behavior tests pass unchanged.

### Milestone 5 (Priority 5): Centralize Repeated Test Lambdas into Test Support Helpers

Create shared test helpers for repeated anonymous test callbacks and stubs.

Preferred new modules:

- `/hyperopen/test/hyperopen/test_support/async.cljs` for `unexpected-error` async catch handlers.
- `/hyperopen/test/hyperopen/test_support/api_stubs.cljs` for repeated endpoint `post-info!` and signing stubs.
- `/hyperopen/test/hyperopen/test_support/hiccup_selectors.cljs` for repeated pagination button predicates.

Refactor high-duplication tests first:

- `/hyperopen/test/hyperopen/api/endpoints/account_test.cljs`
- `/hyperopen/test/hyperopen/api/endpoints/market_test.cljs`
- `/hyperopen/test/hyperopen/api/endpoints/orders_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/session_invalidation_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/sign_and_submit_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/funding_history_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs`
- `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop/series_test.cljs`

This milestone is complete when repeated test helper lambdas are replaced by imports from shared test-support modules without reducing test clarity.

## Concrete Steps

Run all commands from `/Users//projects/hyperopen`.

1. Capture baseline duplicates for traceability.

    cd /Users//projects/hyperopen
    bb tools/anonymous_function_duplication_report.clj --scope src --top-files 25 --top-groups 20 > docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-src-baseline.txt
    bb tools/anonymous_function_duplication_report.clj --scope test --top-files 25 --top-groups 20 > docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-test-baseline.txt

    # Optional summary refresh:
    # update docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-baseline.md with the current metric header values.

2. Implement Milestone 1 and run focused account-info tests plus full test suite.

    cd /Users//projects/hyperopen
    npm test

3. Implement Milestone 2 and run indicator-focused tests and full suite.

    cd /Users//projects/hyperopen
    npm test

4. Implement Milestone 3 and run API/startup/order tests and full suite.

    cd /Users//projects/hyperopen
    npm test

5. Implement Milestone 4 and run websocket/wallet/chart tests and full suite.

    cd /Users//projects/hyperopen
    npm test

6. Implement Milestone 5 and run full suite.

    cd /Users//projects/hyperopen
    npm test

7. Run required validation gates for completion.

    cd /Users//projects/hyperopen
    npm run check
    npm test
    npm run test:websocket

Expected result: all commands exit successfully and no behavior regressions are observed in existing tests.

## Validation and Acceptance

Acceptance criteria for this plan:

1. Duplicate business-logic lambdas in the targeted production clusters are replaced by named reusable functions.
2. Account-info tab sort logic uses a shared sorting kernel with unchanged UI behavior.
3. Indicator arithmetic duplications are centralized and parity tests remain green.
4. Promise success/error refactor keeps runtime side effects and rejection behavior unchanged.
5. Formatting/parsing/websocket/wallet duplicated lambdas are centralized without user-visible regressions.
6. Test lambdas are centralized via test-support helpers in high-duplication suites.
7. Required validation gates pass: `npm run check`, `npm test`, and `npm run test:websocket`.
8. Post-refactor duplication snapshot shows fewer repeated lambda clusters in scoped files than baseline.

## Idempotence and Recovery

Each milestone should be landed independently in additive commits. If a milestone introduces regressions, keep the shared helper module but temporarily route specific callers back to prior local lambdas while preserving new helper tests. This allows iterative migration without throwing away validated reusable components.

The plan is idempotent because baseline capture commands and validation commands can be re-run at any time. For recovery, use standard git revert/cherry-pick workflows on the milestone commit boundary instead of partial manual rollback.

## Artifacts and Notes

Baseline duplication snapshot from audit (to be updated after each milestone):

    Generated by: /hyperopen/tools/anonymous_function_duplication_report.clj
    Summary: /hyperopen/docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-baseline.md

    src lambda arities: 910
    src duplicate groups: 59
    src duplicate occurrences: 142
    src cross-file duplicate groups: 28
    src large duplicate groups (size >= 10): 28

    test lambda arities: 2369
    test duplicate groups: 322
    test duplicate occurrences: 1239
    test cross-file duplicate groups: 139
    test large duplicate groups (size >= 10): 115

Highest-value production duplicate clusters discovered:

    /hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs:212   ; (fn [_] 0)
    /hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs:107       ; (fn [_] 0)
    /hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs:193      ; (fn [_] 0)
    /hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs:122          ; (fn [_] 0)
    /hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs:309      ; (fn [_] 0)

    /hyperopen/src/hyperopen/domain/trading/indicators/oscillators/helpers.cljs:50
    /hyperopen/src/hyperopen/domain/trading/indicators/oscillators/momentum.cljs:22
    /hyperopen/src/hyperopen/domain/trading/indicators/trend/strength.cljs:31
    /hyperopen/src/hyperopen/domain/trading/indicators/volatility/range.cljs:18

    /hyperopen/src/hyperopen/api/fetch_compat.cljs:168
    /hyperopen/src/hyperopen/runtime/api_effects.cljs:15
    /hyperopen/src/hyperopen/order/effects.cljs:98
    /hyperopen/src/hyperopen/startup/collaborators.cljs:48

Highest-value test duplicate clusters discovered:

    /hyperopen/test/hyperopen/api/compat_test.cljs:20
    /hyperopen/test/hyperopen/account/history/effects_test.cljs:146
    /hyperopen/test/hyperopen/api/endpoints/account_test.cljs:37
    ; repeated async unexpected-error catch callback across 21 files

Checked-in baseline outputs:

    /hyperopen/docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-src-baseline.txt
    /hyperopen/docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-test-baseline.txt

Post-Milestone 5 duplication snapshot:

    /hyperopen/docs/exec-plans/active/artifacts/2026-02-23-anonymous-function-centralization-test-post-milestone5.txt

    test lambda arities: 2296
    test duplicate groups: 310
    test duplicate occurrences: 1161
    test cross-file duplicate groups: 133
    test large duplicate groups (size >= 10): 104

## Interfaces and Dependencies

Preferred reusable interfaces at completion:

- In `/hyperopen/src/hyperopen/views/account_info/sort_kernel.cljs` (or `/hyperopen/src/hyperopen/views/account_info/table.cljs`), provide deterministic sort helpers:

    (defn sort-rows-by-column
      [rows {:keys [column direction accessor-by-column fallback-accessor tie-breaker]}]
      ;; returns vector with deterministic order
      ...)

- In `/hyperopen/src/hyperopen/domain/trading/indicators/math.cljs` (or new sibling kernel module), provide arithmetic helpers:

    (defn finite-subtract [a b] ...)
    (defn band-upper-value [base spread multiplier] ...)
    (defn band-lower-value [base spread multiplier] ...)
    (defn band-upper-values [base-values spread-values multiplier] ...)
    (defn band-lower-values [base-values spread-values multiplier] ...)
    (defn finite-ratio [numerator denominator] ...)
    (defn safe-percent-ratio [numerator denominator] ...)
    (defn true-range-at [high low prev-close] ...)
    (defn true-range-index [high-values low-values close-values idx] ...)
    (defn true-range-values [high-values low-values close-values] ...)
    (defn roc-percent-at [values idx period] ...)
    (defn roc-percent-values [values period] ...)
    (defn hl2-at [high-values low-values idx] ...)
    (defn hl2-values [high-values low-values] ...)

- In `/hyperopen/src/hyperopen/runtime/promise_effects.cljs` (or `/hyperopen/src/hyperopen/api/promise_effects.cljs`), provide promise branch helpers:

    (defn apply-success-and-return [store apply-fn & args] ...)
    (defn apply-error-and-reject [store apply-error-fn err] ...)
    (defn log-apply-error-and-reject [log-fn message store apply-error-fn err] ...)

- In `/hyperopen/test/hyperopen/test_support/async.cljs`, provide async test callback helpers:

    (defn unexpected-error-callback [done] ...)

- In `/hyperopen/test/hyperopen/test_support/hiccup_selectors.cljs`, provide selector predicates:

    (defn button-with-label? [label node] ...)

Dependencies remain internal to existing namespaces; no third-party libraries are required.

## Plan Revision Notes

- 2026-02-23 / Codex: Initial version created to convert the lambda duplication audit into a priority-ordered, implementation-ready ExecPlan for future threads.
- 2026-02-23 / Codex: Implemented Milestone 0 by adding `/hyperopen/tools/anonymous_function_duplication_report.clj`, generating parser-validated `src`/`test` baseline artifacts under `/hyperopen/docs/exec-plans/active/artifacts/`, and updating this plan with reproducible commands and evidence links.
- 2026-02-23 / Codex: Refactored Milestone 0 tooling to category-scoped namespaces under `/hyperopen/tools/anonymous_function_duplication/` and removed the generic single-file baseline script naming.
- 2026-02-23 / Codex: Implemented Milestone 1 by introducing `/hyperopen/src/hyperopen/views/account_info/sort_kernel.cljs`, centralizing account-info tab sort scaffolding across funding/order/trade/open-orders/positions tabs, and adding `/hyperopen/test/hyperopen/views/account_info/sort_kernel_test.cljs`.
- 2026-02-23 / Codex: Implemented Milestone 2 by extending `/hyperopen/src/hyperopen/domain/trading/indicators/math.cljs` with shared arithmetic kernels, migrating indicator families (`oscillators`, `trend`, `volatility`, `price`) to reuse those kernels, and extending indicator parity coverage in `/hyperopen/test/hyperopen/domain/trading/indicators/math_kernels_test.cljs`, `/hyperopen/test/hyperopen/domain/trading/indicators/heavy_algorithms_test.cljs`, and `/hyperopen/test/hyperopen/domain/trading/indicators/family_parity_test.cljs`.
- 2026-02-23 / Codex: Implemented Milestone 3 by adding `/hyperopen/src/hyperopen/api/promise_effects.cljs`, refactoring repeated promise success/error callbacks across API/runtime/startup/order/market-metadata flows, and adding helper-focused tests in `/hyperopen/test/hyperopen/api/promise_effects_test.cljs`.
- 2026-02-23 / Codex: Implemented Milestone 4 by adding shared time helpers in `/hyperopen/src/hyperopen/utils/formatting.cljs`, extracting chart interop numeric coercion helpers into `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/numeric.cljs`, centralizing websocket descriptor matcher/user mapping builders in `/hyperopen/src/hyperopen/websocket/health.cljs`, centralizing repeated websocket tier lambdas in `/hyperopen/src/hyperopen/websocket/client.cljs`, and centralizing wallet accounts-connection branching in `/hyperopen/src/hyperopen/wallet/core.cljs`.
