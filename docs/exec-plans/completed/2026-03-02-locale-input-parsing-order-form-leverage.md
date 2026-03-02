# Locale-Aware Parsing for Order-Form Leverage Inputs

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and must be maintained in accordance with that file.

## Purpose / Big Picture

Order-form leverage draft/set/confirm flows still normalized numeric text through dot-decimal parsing. After this change, users in locales such as `fr-FR` can enter comma-decimal leverage values (for example `"17,5"`) and get deterministic rounding and clamping behavior equivalent to dot-decimal input.

A user can verify this by setting `[:ui :locale]` to `"fr-FR"`, entering comma-decimal leverage text in the order leverage popover, confirming, and observing the same resulting leverage as equivalent dot-decimal input.

## Progress

- [x] (2026-03-02 21:05Z) Audited order-form transitions and identified remaining leverage parsing gap in `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`.
- [x] (2026-03-02 21:08Z) Added locale-aware leverage parsing for draft/set paths using `hyperopen.utils.parse/parse-localized-decimal`.
- [x] (2026-03-02 21:10Z) Fixed confirm-path behavior to read raw leverage draft text from `:order-form-ui` before normalization, preventing premature dot-decimal coercion.
- [x] (2026-03-02 21:11Z) Added `fr-FR` regression coverage in `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs`.
- [x] (2026-03-02 21:12Z) Ran required gates successfully: `npm test`, `npm run check`, `npm run test:websocket`.

## Surprises & Discoveries

- Observation: Confirm leverage flow consumed normalized UI state (`trading/order-form-ui-state`) where `:leverage-draft` was already parsed via `parseFloat`, which dropped locale decimal precision before transition-level parsing could run.
  Evidence: Initial test expected `22,5 -> 23`, but received `22` until confirm path switched to raw `:order-form-ui` leverage draft input.

## Decision Log

- Decision: Keep locale-aware leverage parsing in order-form transition boundaries rather than widening state-level UI normalization contracts to carry locale.
  Rationale: Transition boundaries already have app state and locale context and are the narrowest deterministic place to parse user-entered text without expanding global UI normalization surface area.
  Date/Author: 2026-03-02 / Codex

## Outcomes & Retrospective

This tranche closes the remaining order-form leverage locale gap in draft/set/confirm flows. Comma-decimal leverage input is now interpreted consistently in locale-aware contexts, while existing clamp/round semantics remain unchanged.

## Context and Orientation

Order-form leverage interactions are owned by `/hyperopen/src/hyperopen/trading/order_form_transitions.cljs`:

- `set-order-ui-leverage-draft`
- `set-order-ui-leverage`
- `confirm-order-ui-leverage`

Regression coverage lives in `/hyperopen/test/hyperopen/trading/order_form_transitions_test.cljs`.

## Plan of Work

Introduce a localized numeric parse helper in order-form transitions and route leverage draft/set inputs through it. Ensure confirm flow uses raw leverage draft text to avoid pre-normalization parse loss. Add focused locale test coverage and run required validation gates.

## Concrete Steps

From `/hyperopen`:

1. Update leverage-related transition paths to parse locale-aware decimal text.
2. Ensure confirm leverage resolves draft from raw `:order-form-ui` state.
3. Add `fr-FR` comma-decimal leverage tests in transitions test namespace.
4. Run required gates:
   - `npm test`
   - `npm run check`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance criteria:

1. `set-order-ui-leverage-draft` accepts locale comma-decimal text and rounds/clamps deterministically.
2. `set-order-ui-leverage` accepts locale comma-decimal text and projects canonical leverage consistently.
3. `confirm-order-ui-leverage` uses locale-aware draft interpretation without premature dot-decimal coercion.
4. Required repository validation gates pass.

## Idempotence and Recovery

Changes are additive and idempotent. If regressions appear, keep raw draft read behavior and narrow localized parsing to draft path first, then re-enable direct-set parsing once verified.

## Artifacts and Notes

Validation commands run:

    npm test
    npm run check
    npm run test:websocket

## Interfaces and Dependencies

No external dependencies were added. This work reuses `/hyperopen/src/hyperopen/utils/parse.cljs` locale-aware parsing helpers and existing order-form transition/state contracts.
