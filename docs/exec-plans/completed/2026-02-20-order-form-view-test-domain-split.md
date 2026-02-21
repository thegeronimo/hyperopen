# Split Order Form View Tests into Domain-Focused Namespaces

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

This plan builds on the existing order-form modularization already covered by `/hyperopen/test/hyperopen/views/trade/order_form_component_primitives_test.cljs`, `/hyperopen/test/hyperopen/views/trade/order_form_component_sections_test.cljs`, and `/hyperopen/test/hyperopen/views/trade/order_form_vm_test.cljs`.

## Purpose / Big Picture

`/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` currently concentrates many unrelated concerns in one large namespace (entry-mode tab behavior, dropdown interactions, slider visuals, scale preview, liquidation rows, submit tooltips, and styling contracts). That structure makes targeted maintenance expensive for humans and coding agents because edits require loading broad unrelated context.

After this change, order-form view tests will be split into smaller domain-focused namespaces with shared test support helpers. The top-level view test file will become a thin composition/facade slice. The expected gain is faster test discovery, clearer SOLID/DDD ownership boundaries, and lower AI reasoning context load while preserving behavior and coverage.

Users can verify the change by opening one domain-specific file for a concern (for example scale preview or submit behavior), running required validation gates, and seeing no regressions.

## Progress

- [x] (2026-02-20 16:18Z) Re-read `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md` requirements for ExecPlan structure and living sections.
- [x] (2026-02-20 16:18Z) Audited `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` and confirmed mixed concerns: 940 lines and 54 `deftest` forms.
- [x] (2026-02-20 16:18Z) Audited adjacent order-form test topology and source boundaries (`order_form_view.cljs`, `order_form_vm_test.cljs`, component primitive/section tests) to avoid overlap.
- [x] (2026-02-20 16:18Z) Confirmed explicit runner wiring in `/hyperopen/test/test_runner.cljs`; split must update both `:require` and `run-tests` lists.
- [x] (2026-02-20 16:18Z) Authored this active ExecPlan before implementation.
- [x] (2026-02-20 16:22Z) Extracted shared order-form view test support into `/hyperopen/test/hyperopen/views/trade/order_form/test_support.cljs` (fixture builder + reusable hiccup selectors/helpers).
- [x] (2026-02-20 16:22Z) Created domain-focused order-form view namespaces under `/hyperopen/test/hyperopen/views/trade/order_form_view/` and moved all 54 original view tests by concern with unchanged test names/assertions.
- [x] (2026-02-20 16:22Z) Reduced `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` from 940 lines to a 27-line thin facade slice (2 integration tests).
- [x] (2026-02-20 16:22Z) Updated `/hyperopen/test/test_runner.cljs` to include all new order-form view test namespaces in both `:require` and `run-tests`.
- [x] (2026-02-20 16:22Z) Ran required validation gates successfully: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-20 16:22Z) Updated this ExecPlan with implementation evidence, discoveries, decisions, and retrospective.

## Surprises & Discoveries

- Observation: order-form tests are already partially modularized, but the primary view tests remain monolithic.
  Evidence: focused files exist for VM, submit policy, runtime gateway, primitives, and sections, while `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` originally contained 54 integration-level tests.

- Observation: the monolith repeats fixture inference logic that also exists in VM tests.
  Evidence: both `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` and `/hyperopen/test/hyperopen/views/trade/order_form_vm_test.cljs` define similar `base-state` normalization for UI-owned keys and inferred `entry-mode`.

- Observation: existing assertions include a mix of semantic behavior and CSS utility token checks.
  Evidence: tests in `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` assert both action payload wiring and exact class tokens such as `bg-[#50D2C1]`, `bg-[rgb(23,69,63)]`, and `order-size-slider-notch-active`.

- Observation: extracting tests by line range from the working tree became unsafe after shrinking the facade file.
  Evidence: after rewriting `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs`, subsequent range extraction returned only namespace lines; extraction was corrected by reading immutable source from `git show HEAD:test/hyperopen/views/trade/order_form_view_test.cljs`.

- Observation: runner wiring remained deterministic with explicit namespace inclusion.
  Evidence: `npm test` output lists all new namespaces: `entry-mode-test`, `size-and-slider-test`, `scale-preview-test`, `metrics-and-submit-test`, and `styling-contract-test`.

## Decision Log

- Decision: perform this as a behavior-preserving topology refactor first, not a production behavior change.
  Rationale: keeping assertions and behavior constant isolates risk to test organization and runner wiring.
  Date/Author: 2026-02-20 / Codex

- Decision: introduce shared order-form view test support helpers (fixtures + hiccup selectors) and consume them across split namespaces.
  Rationale: this removes duplicated traversal logic and fixture drift while making each domain test file shorter and legible.
  Date/Author: 2026-02-20 / Codex

- Decision: keep a thin top-level `order_form_view_test.cljs` file for facade/composition contracts only.
  Rationale: domain split should not remove confidence in top-level integration wiring.
  Date/Author: 2026-02-20 / Codex

- Decision: preserve existing assertion semantics by copying original `deftest` blocks directly from `HEAD` into new files rather than rewriting assertions.
  Rationale: this keeps migration behavior-preserving and minimizes accidental logic drift during topology changes.
  Date/Author: 2026-02-20 / Codex

- Decision: isolate style-token-heavy assertions in `styling_contract_test.cljs` and keep value/flow assertions in `metrics_and_submit_test.cljs` and `size_and_slider_test.cljs`.
  Rationale: this reduces cross-domain context and makes token-coupled assertions easy to locate for future UI refactors.
  Date/Author: 2026-02-20 / Codex

## Outcomes & Retrospective

Implementation completed as a behavior-preserving test-topology refactor.

- Added shared support namespace: `/hyperopen/test/hyperopen/views/trade/order_form/test_support.cljs`.
- Added domain-focused namespaces:
  - `/hyperopen/test/hyperopen/views/trade/order_form_view/entry_mode_test.cljs`
  - `/hyperopen/test/hyperopen/views/trade/order_form_view/size_and_slider_test.cljs`
  - `/hyperopen/test/hyperopen/views/trade/order_form_view/scale_preview_test.cljs`
  - `/hyperopen/test/hyperopen/views/trade/order_form_view/metrics_and_submit_test.cljs`
  - `/hyperopen/test/hyperopen/views/trade/order_form_view/styling_contract_test.cljs`
- Reduced facade file: `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` from 940 lines to 27 lines.
- Preserved test coverage count at 54 total view tests across the split files.

Validation outcomes:

- `npm run check`: pass (0 lint/doc/compile failures, 0 compile warnings).
- `npm test`: pass (`Ran 1168 tests containing 5445 assertions. 0 failures, 0 errors.`).
- `npm run test:websocket`: pass (`Ran 135 tests containing 587 assertions. 0 failures, 0 errors.`).

Residual risk is low. The main maintenance risk remains explicit test-runner wiring drift if additional order-form view namespaces are added later without updating `/hyperopen/test/test_runner.cljs`.

## Context and Orientation

The production entrypoint `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` composes view sections from VM data and handler maps. It delegates complex behavior to VM and section/primitives modules:

- `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs`
- `/hyperopen/src/hyperopen/views/trade/order_form_component_primitives.cljs`
- `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs`

Current tests mirror this decomposition only partially. Focused test files already exist for VM and components, but `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` still mixes many concerns in one namespace.

In this plan, “domain-focused namespace” means one test file with one primary ownership area (entry tabs, size/slider controls, scale preview, submit/metrics behavior, or facade composition), so contributors can locate relevant tests without loading unrelated contexts.

The test runner is explicit (`/hyperopen/test/test_runner.cljs`); new namespaces must be added to both namespace requires and the `run-tests` list or they will not execute.

## Plan of Work

Milestone 1 extracts shared support. Create `/hyperopen/test/hyperopen/views/trade/order_form/test_support.cljs` with reusable fixture builders and hiccup selector helpers currently duplicated in the monolith (for example string collection, node finders, metric helpers, and `base-state`). Keep helper APIs semantic and deterministic.

Milestone 2 creates domain test namespaces under `/hyperopen/test/hyperopen/views/trade/order_form_view/` and moves existing `deftest` forms by concern with minimal assertion changes:

- `entry_mode_test.cljs`
- `size_and_slider_test.cljs`
- `scale_preview_test.cljs`
- `metrics_and_submit_test.cljs`
- `styling_contract_test.cljs`

Milestone 3 reduces `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` to a thin facade integration slice focused on high-level composition/wiring.

Milestone 4 updates `/hyperopen/test/test_runner.cljs` to include all new order-form view test namespaces.

Milestone 5 runs required validation gates and updates this plan’s living sections with final evidence.

## Concrete Steps

1. Create shared support namespace:

   - `/hyperopen/test/hyperopen/views/trade/order_form/test_support.cljs`

2. Create new domain-focused namespace files under `/hyperopen/test/hyperopen/views/trade/order_form_view/` and migrate tests from `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs`.

3. Keep a reduced `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` with only facade/composition checks.

4. Update `/hyperopen/test/test_runner.cljs`:

   - add `:require` entries for new namespaces,
   - add matching symbols in `run-tests` list,
   - keep unrelated runner wiring untouched.

5. Run required gates from `/hyperopen`:

      npm run check
      npm test
      npm run test:websocket

6. Update this ExecPlan sections (`Progress`, `Surprises & Discoveries`, `Decision Log`, `Outcomes & Retrospective`, and revision note) with implementation evidence.

## Validation and Acceptance

Acceptance criteria:

1. `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` is reduced to thin facade/composition scope.
2. Domain-focused test files exist under `/hyperopen/test/hyperopen/views/trade/order_form_view/` and each has one primary concern.
3. Shared helper logic is centralized in `/hyperopen/test/hyperopen/views/trade/order_form/test_support.cljs` rather than repeated private defs.
4. `/hyperopen/test/test_runner.cljs` includes every new namespace in both `:require` and `run-tests` wiring.
5. Required validation gates pass: `npm run check`, `npm test`, `npm run test:websocket`.
6. No intentional production behavior changes are introduced.

## Idempotence and Recovery

This refactor is additive and can be applied incrementally. Safe recovery path: if a moved namespace fails to compile, temporarily keep the corresponding tests in the original monolith while fixing the new file, then remove duplicates once green. Recovery should be normal source edits and re-running required gates; no destructive operations are needed.

## Artifacts and Notes

Initial scoping evidence:

- `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs`: 940 lines.
- `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs`: 54 `deftest` forms.
- Existing adjacent focused tests:
  - `/hyperopen/test/hyperopen/views/trade/order_form_component_primitives_test.cljs`
  - `/hyperopen/test/hyperopen/views/trade/order_form_component_sections_test.cljs`
  - `/hyperopen/test/hyperopen/views/trade/order_form_vm_test.cljs`
  - `/hyperopen/test/hyperopen/views/trade/order_form_vm_submit_test.cljs`
- Runner wiring file:
  - `/hyperopen/test/test_runner.cljs`

Implementation evidence:

- Line-count migration:
  - before: `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` = 940 lines
  - after: `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` = 27 lines
- New support/helpers:
  - `/hyperopen/test/hyperopen/views/trade/order_form/test_support.cljs` = 162 lines
- New test distribution:
  - `/hyperopen/test/hyperopen/views/trade/order_form_view/entry_mode_test.cljs` = 14 tests
  - `/hyperopen/test/hyperopen/views/trade/order_form_view/size_and_slider_test.cljs` = 10 tests
  - `/hyperopen/test/hyperopen/views/trade/order_form_view/scale_preview_test.cljs` = 11 tests
  - `/hyperopen/test/hyperopen/views/trade/order_form_view/metrics_and_submit_test.cljs` = 10 tests
  - `/hyperopen/test/hyperopen/views/trade/order_form_view/styling_contract_test.cljs` = 7 tests
  - `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` = 2 tests
- Total preserved view tests after split: 54.
- Runner wiring update:
  - Added new namespaces to both `:require` vector and `run-tests` symbol list in `/hyperopen/test/test_runner.cljs`.
- Final gate evidence:
  - `npm run check` passed.
  - `npm test` passed (`0 failures, 0 errors`).
  - `npm run test:websocket` passed (`0 failures, 0 errors`).

## Interfaces and Dependencies

No production interface changes are required. This plan changes test topology and runner wiring only.

Expected new test namespaces:

- `hyperopen.views.trade.order-form.test-support`
- `hyperopen.views.trade.order-form-view.entry-mode-test`
- `hyperopen.views.trade.order-form-view.size-and-slider-test`
- `hyperopen.views.trade.order-form-view.scale-preview-test`
- `hyperopen.views.trade.order-form-view.metrics-and-submit-test`
- `hyperopen.views.trade.order-form-view.styling-contract-test`
- `hyperopen.views.trade.order-form-view-test` (thin facade retained)

Dependencies that must remain consistent:

- `cljs.test` macros and assertion semantics.
- Explicit namespace wiring in `/hyperopen/test/test_runner.cljs`.
- Existing order-form module boundaries in `/hyperopen/src/hyperopen/views/trade/`.

Plan revision note: 2026-02-20 16:18Z - Initial plan created from order-form view test audit with domain-aligned split strategy, shared test-support extraction, and required validation gates.
Plan revision note: 2026-02-20 16:22Z - Completed implementation: extracted shared test support, split 54 view tests into five domain-focused namespaces plus thin facade file, updated runner wiring, and passed required validation gates.
