# Refactor Indicator Kind and Series-Type Branching to Shared Polymorphic Dispatch

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Indicator processing currently branches on tags in multiple places: parameter validation in `/hyperopen/src/hyperopen/domain/trading/indicators/contracts.cljs`, view projection in `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`, and chart interop series creation in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/indicators.cljs`. Those branch points are functionally related but implemented independently.

After this change, these behaviors are dispatched through one shared polymorphic strategy namespace so new `:kind` and `:series-type` variants can be introduced by adding methods rather than growing separate `case` statements. Behavior remains the same. You can verify parity by running indicator and chart interop tests plus required repository validation gates.

## Progress

- [x] (2026-02-18 12:37Z) Reviewed `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and UI/runtime guardrails relevant to touched namespaces.
- [x] (2026-02-18 12:37Z) Audited existing branch points at `/hyperopen/src/hyperopen/domain/trading/indicators/contracts.cljs`, `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`, and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/indicators.cljs`.
- [x] (2026-02-18 12:38Z) Authored this active ExecPlan.
- [x] (2026-02-18 12:39Z) Implemented shared polymorphic dispatch namespace in `/hyperopen/src/hyperopen/domain/trading/indicators/polymorphism.cljs`.
- [x] (2026-02-18 12:39Z) Refactored `/hyperopen/src/hyperopen/domain/trading/indicators/contracts.cljs`, `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`, and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/indicators.cljs` to use shared dispatch.
- [x] (2026-02-18 12:40Z) Ran required gates: `npm run check`, `npm test`, `npm run test:websocket`.
- [x] (2026-02-18 12:40Z) Updated living-plan sections with final results and moved plan to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: The user-supplied `indicators.cljs (line 18)` branch maps to `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/indicators.cljs:18`, where `:series-type` dispatch currently uses `case`.
  Evidence: source audit via numbered listing of that file.
- Observation: Existing tests already cover the critical behavioral parity points for this refactor, including unknown-series-type fallback to line in chart interop and semantic marker projection in indicator view output.
  Evidence: `/hyperopen/test/hyperopen/views/trading_chart/utils/chart_interop_test.cljs` includes unknown `:series-type` assertion, and `/hyperopen/test/hyperopen/views/trading_chart/utils/indicators_test.cljs` asserts fractal marker output shape.
- Observation: All required validation gates passed with no failures or errors after the polymorphic dispatch refactor.
  Evidence: `npm run check` succeeded with zero compile warnings; `npm test` reported `1093` tests and `4961` assertions with `0` failures; `npm run test:websocket` reported `129` tests and `530` assertions with `0` failures.

## Decision Log

- Decision: Implement shared polymorphism as multimethods in a dedicated namespace and define operation-specific methods in each consumer namespace.
  Rationale: This keeps dependency direction safe (domain remains pure, UI adapters keep UI-specific behavior) while still giving one shared dispatch model.
  Date/Author: 2026-02-18 / Codex
- Decision: Preserve unknown-series fallback behavior in chart interop by using explicit line fallback after polymorphic dispatch returns `nil`.
  Rationale: Existing behavior for unknown `:series-type` defaults to line series creation and is covered by tests; preserving this avoids behavioral regression.
  Date/Author: 2026-02-18 / Codex

## Outcomes & Retrospective

Implementation completed and validated.

What changed:

- Added `/hyperopen/src/hyperopen/domain/trading/indicators/polymorphism.cljs` with shared multimethod contracts: `validate-param-value`, `series-operation`, and `marker-operation`.
- Replaced contracts-side `:kind` and `:series-type` branching in `/hyperopen/src/hyperopen/domain/trading/indicators/contracts.cljs` with shared dispatch methods keyed by param kind, contract series operation, and contract marker kind.
- Replaced `case` series projection and marker-kind lookup control flow in `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs` with shared operation dispatch methods.
- Replaced chart interop `:series-type` `case` in `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/indicators.cljs` with shared operation dispatch while preserving unknown fallback to line.

Validation summary:

- `npm run check`: pass
- `npm test`: pass (`1093` tests, `4961` assertions, `0` failures, `0` errors)
- `npm run test:websocket`: pass (`129` tests, `530` assertions, `0` failures, `0` errors)

## Context and Orientation

`/hyperopen/src/hyperopen/domain/trading/indicators/contracts.cljs` validates indicator inputs/results and currently includes a `case` over parameter spec `:kind`. It also constrains allowed result `:series-type` and marker `:kind` values.

`/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs` translates domain indicator outputs into chart-view payloads and currently selects series projection logic via `case` on `:series-type`.

`/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/indicators.cljs` maps projected series definitions to Lightweight Charts series instances and currently selects add-series behavior via `case` on `:series-type`.

In this plan, polymorphic dispatch means selecting behavior by tag value (`:kind` or `:series-type`) through extensible multimethod handlers keyed by tag identity.

## Plan of Work

Create `/hyperopen/src/hyperopen/domain/trading/indicators/polymorphism.cljs` with shared multimethod contracts:

- `validate-param-value` dispatched by param `:kind`.
- `series-operation` dispatched by `[operation series-type]`.
- `marker-operation` dispatched by `[operation kind]`.

Keep default methods fail-safe and parity-preserving: unknown param kinds pass through existing permissive behavior, unknown series operations return `nil`, and unknown marker operations return `nil`.

Refactor `/hyperopen/src/hyperopen/domain/trading/indicators/contracts.cljs` to remove inline `case` branches and delegate `:kind` validation plus result `:series-type` and marker `:kind` checks through the shared multimethods.

Refactor `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs` so series projection and marker projection delegate to shared multimethod dispatch instead of local branching.

Refactor `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/indicators.cljs` so series creation delegates to shared multimethod dispatch, preserving current fallback-to-line behavior for unknown series types.

Validate with existing tests that already exercise indicator shapes, marker rendering, and chart interop fallback behavior.

## Concrete Steps

From `/hyperopen`:

1. Add `/hyperopen/src/hyperopen/domain/trading/indicators/polymorphism.cljs` with shared multimethod declarations and default methods.
2. Edit `/hyperopen/src/hyperopen/domain/trading/indicators/contracts.cljs` to define `defmethod` handlers for param kinds, contract-level series validation operations, and contract-level marker kind operations.
3. Edit `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs` to define view-series and view-marker `defmethod` handlers and remove `case` logic.
4. Edit `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/indicators.cljs` to define chart-interop series `defmethod` handlers and remove `case` logic.
5. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
6. Update this plan with final evidence and move to `/hyperopen/docs/exec-plans/completed/2026-02-18-indicator-polymorphic-kind-series-dispatch.md`.

## Validation and Acceptance

Acceptance criteria:

- Parameter validation behavior in contracts remains unchanged for known and unknown param kinds.
- Indicator result validation still accepts only supported semantic marker kinds and supported series types.
- View adapter produces the same projected `:line` and `:histogram` series payloads and same fractal marker rendering.
- Chart interop still maps `:histogram` explicitly and defaults unknown series types to line series behavior.
- Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

## Idempotence and Recovery

This is a source-only refactor. Steps are idempotent: rerunning edits and tests is safe. If a regression appears, recover by restoring the affected defmethod body while leaving shared multimethod contracts in place, then rerun targeted indicator/chart tests and required gates.

## Artifacts and Notes

Planned changed files:

- `/hyperopen/src/hyperopen/domain/trading/indicators/polymorphism.cljs` (new)
- `/hyperopen/src/hyperopen/domain/trading/indicators/contracts.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/indicators.cljs`
- `/hyperopen/docs/exec-plans/completed/2026-02-18-indicator-polymorphic-kind-series-dispatch.md`

## Interfaces and Dependencies

Public APIs remain unchanged:

- `/hyperopen/src/hyperopen/domain/trading/indicators/contracts.cljs`: `valid-indicator-input?`, `valid-indicator-result?`, `enforce-indicator-result`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/indicator_view_adapter.cljs`: `project-domain-indicator`, `points-from-values`, `indicator-result`
- `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/indicators.cljs`: `add-indicator-series!`, `set-indicator-data!`, `indicator-pane-allocation`

Added dependency:

- Shared polymorphism helpers in `/hyperopen/src/hyperopen/domain/trading/indicators/polymorphism.cljs` used by domain contracts and UI indicator adapters.

Plan revision note: 2026-02-18 12:38Z - Initial plan created after source and policy audit.
Plan revision note: 2026-02-18 12:40Z - Updated progress, discoveries, decisions, and outcomes after implementing shared polymorphic dispatch and passing required validation gates.
