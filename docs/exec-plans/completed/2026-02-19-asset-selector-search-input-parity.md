# Asset Selector Search Input Parity With Hyperliquid

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, the asset selector search field in Hyperopen will behave like Hyperliquid’s: no blue browser-style focus ring, a lighter gray border on hover/focus, and a taller input hit target. Users should see a calmer, parity-aligned selector header and have an easier click/tap target.

## Progress

- [x] (2026-02-19 23:04Z) Audited Hyperopen selector search markup and current classes in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`.
- [x] (2026-02-19 23:04Z) Collected Hyperliquid selector input specs from the production bundle (`/tmp/hyperliquid-main.js`): regular height `33px`, radius `8px`, default border `#273035`, hover/focus border `#9AA3A4`, no blue ring.
- [x] (2026-02-19 23:05Z) Implemented parity-aligned search input class in `/hyperopen/src/styles/main.css` and applied it to selector input markup in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`.
- [x] (2026-02-19 23:05Z) Added selector view regression test for search-input styling contract in `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`.
- [x] (2026-02-19 23:06Z) Ran required validation gates: `npm run check`, `npm test`, and `npm run test:websocket` (all passing).
- [x] (2026-02-19 23:06Z) Finalized outcomes and prepared plan handoff to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: Browser-inspection sessions in this environment are read-only for interaction events (`click`, `dispatchEvent` patterns are blocked), so direct stateful interaction had to be replaced by bundle-level style extraction.
  Evidence: Browser inspection CLI rejected eval expressions with `Blocked eval expression in read-only mode`.
- Observation: Hyperliquid’s input component is centralized and reused, with explicit size tokens (`regular: 33`, `medium: 39`, `large: 48`) and focus/hover border transitions.
  Evidence: Extracted module `/tmp/hyperliquid-module-60470-pretty.js`.

## Decision Log

- Decision: Match Hyperliquid’s selector input behavior using explicit, local selector-search styles rather than relying on Tailwind forms defaults.
  Rationale: Tailwind forms defaults introduce the blue focus affordance we want to remove; explicit styles are deterministic and parity-oriented.
  Date/Author: 2026-02-19 / Codex
- Decision: Keep accessibility-compliant visible focus by using a neutral gray border change (not removing focus affordance entirely).
  Rationale: UI foundations require visible focus indicators and keyboard operability.
  Date/Author: 2026-02-19 / Codex
- Decision: Keep the parity values as explicit CSS constants on `.asset-selector-search-input` instead of introducing new global design tokens in this pass.
  Rationale: This task is a scoped parity adjustment; adding global tokens would broaden design-system change surface beyond user request.
  Date/Author: 2026-02-19 / Codex

## Outcomes & Retrospective

Hyperopen’s selector search input now mirrors Hyperliquid interaction behavior in the requested areas: the field uses a taller target (`33px`), default dark border, and a neutral lighter border on hover/focus with no blue focus ring. The change was contained to selector view markup and shared stylesheet components, with an added view test to protect against ring/class regressions. All required validation gates passed, so the change is ready to ship.

## Context and Orientation

The asset selector dropdown UI is rendered in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`, specifically `search-controls`. Tailwind and component-level CSS live in `/hyperopen/src/styles/main.css`. Selector view tests are in `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`.

The issue is visual interaction styling: focus/hover treatment and perceived height of the search field. No state-flow changes are needed.

## Plan of Work

Add a dedicated CSS component class for the asset selector search input in `/hyperopen/src/styles/main.css` that encodes Hyperliquid-like dimensions and state styling (default, hover, focus). Then update `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` to apply that class (plus icon spacing utility classes) to the search input. Add a test in `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs` that asserts the selector search input carries the parity class and no ring class contract regressions.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/styles/main.css`:
   - add `.asset-selector-search-input` component class with 33px height, 8px radius, dark border, lighter hover/focus border, and ring removal.
2. Edit `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`:
   - apply `.asset-selector-search-input` to the search input and preserve icon offset/padding.
3. Edit `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`:
   - assert the search input node includes the new class and focus-ring removal class contract.
4. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance criteria:

- Clicking the selector search input no longer shows a blue browser ring.
- Hover/focus state uses a lighter neutral border, not a blue accent.
- The input appears taller (Hyperliquid-like target) and remains keyboard focusable.
- Required repository validation gates pass.

## Idempotence and Recovery

Changes are styling and view-test only. Reapplying is safe. Recovery is a straightforward revert of the touched files if visual parity or accessibility checks fail.

## Artifacts and Notes

Reference extraction artifacts used for parity numbers:

- `/tmp/hyperliquid-module-60470-pretty.js` (input component dimensions and states)
- `/tmp/hyperliquid-module-71244-pretty.js` (color tokens)
- `/tmp/hyperliquid-asset-snippet-pretty.js` (selector usage: search input + strict toggle layout)

## Interfaces and Dependencies

No API or runtime interface changes. No new dependencies.

Plan revision note: 2026-02-19 23:04Z - Initial plan created after Hyperliquid selector/input style audit.
Plan revision note: 2026-02-19 23:06Z - Updated progress, decisions, and outcomes after implementation and full validation pass.
