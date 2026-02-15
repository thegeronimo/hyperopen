# Order Form SOLID/DDD Slice 2 (Policy Unification and UI-State Boundary)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

This slice completes the next SOLID/DDD steps after the first VM extraction by removing remaining domain calls from the view, unifying submit policy, and splitting order draft state from order-form UI state in the app db. After this change, `order_form_view.cljs` renders from VM + presentation components, submit gating has one canonical policy function, and instrument parsing is shared across order form and orderbook views.

## Progress

- [x] (2026-02-15 20:08Z) Created active ExecPlan for SOLID/DDD slice 2 with scope covering items 1–7.
- [x] (2026-02-15 20:15Z) Introduced `/hyperopen/src/hyperopen/domain/market/instrument.cljs` and reused it from trading market identity and L2 orderbook symbol resolution.
- [x] (2026-02-15 20:16Z) Added `trading/submit-policy` and switched `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs` plus `/hyperopen/src/hyperopen/order/actions.cljs` submit flow to use it.
- [x] (2026-02-15 20:18Z) Split runtime state into `:order-form` and `:order-form-ui`, wired defaults/system/contracts, and added compatibility fallback selector `order-form-ui-state`.
- [x] (2026-02-15 20:19Z) Added `/hyperopen/src/hyperopen/views/trade/order_form_commands.cljs` and replaced inline action vectors in extracted presentation layer.
- [x] (2026-02-15 20:20Z) Added `/hyperopen/src/hyperopen/views/trade/order_form_components.cljs` and reduced `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` to VM/component assembly.
- [x] (2026-02-15 20:22Z) Added `/hyperopen/test/hyperopen/views/trade/order_form_vm_test.cljs`, wired `/hyperopen/test/test_runner.cljs`, and updated impacted tests (`core_bootstrap`, `app_defaults`, `state/trading`) for the new UI-state path.
- [x] (2026-02-15 20:24Z) Ran required gates successfully: `npm run check`, `npm test`, `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Existing tests rely heavily on constructing state by mutating `:order-form` with UI flags directly.
  Evidence: `order_form_view_test.cljs` and `core_bootstrap_test.cljs` repeatedly set `:pro-order-type-dropdown-open?`, `:price-input-focused?`, and `:tpsl-panel-open?` inside `:order-form` fixtures.

- Observation: Extracting SVG/inline controls into a new components namespace introduced two unmatched delimiters that blocked compile.
  Evidence: `npm run check` initially failed with parser errors at `/hyperopen/src/hyperopen/views/trade/order_form_components.cljs:329` and `:362`; both resolved after correcting vector closures.

- Observation: Unified submit policy changes required-field messaging for unfocused limit orders with empty `:price`.
  Evidence: after routing VM tooltips through `submit-policy`, tests now report required field `Size` (price is prepared from midpoint/reference) instead of `Price, Size`.

## Decision Log

- Decision: Apply a compatibility bridge while splitting state by adding a selector that derives effective UI flags from `:order-form-ui` with legacy fallback from `:order-form`.
  Rationale: This allows migrating runtime writes to `:order-form-ui` immediately while avoiding broad fixture rewrites in one pass.
  Date/Author: 2026-02-15 / Codex

- Decision: Keep `submit-policy` as the single place that runs `prepare-order-form-for-submit` before validation for both view and submit modes.
  Rationale: This eliminates duplicated gating between VM and actions and keeps user-visible disable reasons aligned with submit behavior.
  Date/Author: 2026-02-15 / Codex

- Decision: Add forward declarations for `normalize-order-form` and `build-order-request` inside `/hyperopen/src/hyperopen/state/trading.cljs`.
  Rationale: The new helper ordering introduced undeclared-var compile warnings; declarations preserve file readability without reordering large sections.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

The slice was completed end-to-end. `order_form_view.cljs` is now a thin renderer over VM and extracted components/commands, submit gating is centralized in `trading/submit-policy`, and UI-only flags were moved to `:order-form-ui` with compatibility fallback for legacy fixtures. Shared instrument parsing now lives in `/hyperopen/src/hyperopen/domain/market/instrument.cljs` and is reused by both trading identity and L2 orderbook rendering.

Validation outcomes match the plan purpose: all required gates pass with zero compile warnings. The largest residual gap is migration cleanup: compatibility fallback from `:order-form` to `:order-form-ui` still exists intentionally and can be removed in a later slice once all fixtures and call sites are fully migrated.

## Context and Orientation

Current order form code is split across:

- `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` (still contains large presentational helper volume and one domain query for price context).
- `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs` (derived state but still duplicates submit-policy logic).
- `/hyperopen/src/hyperopen/order/actions.cljs` (submit/action policy and UI-flag updates still intertwined with domain draft map).

The target boundary is:

- Domain/order draft: `:order-form`
- Presentation state: `:order-form-ui`
- Policy selector: `trading/submit-policy`
- Shared instrument parsing in one reusable domain namespace.

## Plan of Work

Milestone 1 introduces a shared instrument parser namespace and routes `trading.market/market-identity` plus `l2_orderbook_view` symbol resolution through it.

Milestone 2 introduces `trading/submit-policy` that returns prepared form, disable reason, and optional action error message. VM and submit action both consume this selector.

Milestone 3 moves presentation-only flags to `:order-form-ui` and updates actions/defaults/system wiring. A compatibility fallback keeps old fixture inputs working while runtime writes move to the new path.

Milestone 4 extracts commands and components from `order_form_view.cljs`, reducing it to a renderer assembly over VM data.

Milestone 5 adds dedicated VM tests and updates affected tests for new path expectations.

## Concrete Steps

From `/hyperopen`:

1. Add shared instrument namespace under `/hyperopen/src/hyperopen/domain/market/`.
2. Update `/hyperopen/src/hyperopen/domain/trading/market.cljs` and `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs` to use it.
3. Add `submit-policy` in `/hyperopen/src/hyperopen/state/trading.cljs`; consume it from `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs` and `/hyperopen/src/hyperopen/order/actions.cljs`.
4. Add `default-order-form-ui` and normalization in `/hyperopen/src/hyperopen/trading/order_form_state.cljs`; wire `/hyperopen/src/hyperopen/state/app_defaults.cljs` and `/hyperopen/src/hyperopen/system.cljs`.
5. Update action handlers (`order.actions`, `asset_selector.actions`) to write/read `:order-form-ui`.
6. Add `/hyperopen/src/hyperopen/views/trade/order_form_commands.cljs` and `/hyperopen/src/hyperopen/views/trade/order_form_components.cljs`; refactor `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` to use both.
7. Add `/hyperopen/test/hyperopen/views/trade/order_form_vm_test.cljs` and update test runner and impacted existing tests.
8. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance criteria:

- `order_form_view.cljs` has no direct dependency on `hyperopen.state.trading` domain selectors.
- VM and submit action both use one `trading/submit-policy` selector for disable reasons/prepared form.
- Runtime writes for dropdown/focus/tpsl flags go to `:order-form-ui` (not `:order-form`).
- Shared instrument parsing is reused by both trading market identity and L2 orderbook symbol rendering.
- Dedicated VM tests exist and pass.
- Required gates pass.

## Idempotence and Recovery

Changes are additive-first and can be safely retried. If migration regressions appear, fallback is to keep new selectors/components in place and temporarily map action writes back to legacy paths while preserving the new boundary code for incremental re-enable.

## Artifacts and Notes

Key validation transcripts (from `/hyperopen`):

    npm run check
    ...
    [:app] Build completed. (280 files, 10 compiled, 0 warnings, ...)
    [:test] Build completed. (385 files, 4 compiled, 0 warnings, ...)

    npm test
    ...
    Ran 800 tests containing 3113 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    ...
    Ran 86 tests containing 267 assertions.
    0 failures, 0 errors.

## Interfaces and Dependencies

Required interfaces for this slice:

- `/hyperopen/src/hyperopen/state/trading.cljs`: `submit-policy`, `order-form-ui-state`, `default-order-form-ui`.
- `/hyperopen/src/hyperopen/views/trade/order_form_commands.cljs`: command helpers returning action vectors.
- `/hyperopen/src/hyperopen/views/trade/order_form_components.cljs`: presentational component functions used by `order_form_view.cljs`.
- `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs`: consumes `submit-policy` and no duplicated submit gating logic.

Plan revision note: 2026-02-15 20:08Z - Initial plan created for SOLID/DDD slice 2 covering requested items 1–7.
Plan revision note: 2026-02-15 20:24Z - Updated all living sections with implementation progress, discoveries, decisions, and final validation evidence after completing the slice.
