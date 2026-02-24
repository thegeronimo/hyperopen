# HIP3 Strict-Mode Parity With Hyperliquid

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, Hyperopen’s HIP3 tab behavior will match Hyperliquid’s mode semantics: when strict mode is off, HIP3 shows the full named-dex HIP3 universe; when strict mode is on, HIP3 applies the stricter eligibility gate. This removes the current always-gated behavior that can make Hyperopen look much smaller than Hyperliquid in default mode.

## Progress

- [x] (2026-02-19 19:42Z) Confirmed parity root cause from live audit and code: HIP3 gating is currently unconditional in Hyperopen, while Hyperliquid applies strict-dependent branch logic.
- [x] (2026-02-19 19:43Z) Updated selector tab filtering to make HIP3 eligibility strict-dependent.
- [x] (2026-02-19 19:43Z) Added perps strict-mode parity handling for named-dex rows (`perps` tab behavior consistency).
- [x] (2026-02-19 19:43Z) Updated selector view tests to cover strict-off/full and strict-on/gated HIP3 behavior.
- [x] (2026-02-19 19:43Z) Ran required validation gates (`npm run check`, `npm test`, `npm run test:websocket`) with all green.
- [x] (2026-02-19 19:43Z) Finalized plan outcomes.

## Surprises & Discoveries

- Observation: Hyperliquid’s minified selector branch uses strict-dependent logic for HIP3: `(n ? Ce : Ee)`.
  Evidence: In `/hyperopen/tmp/hyperliquid-main.ccb853ef.js`, the `hip-3` branch checks `n ? Ce(e,f) : Ee(e)`.
- Observation: Hyperopen currently applies HIP3 eligibility unconditionally in `tab-match?`.
  Evidence: `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` currently requires `hip3-tab-eligible?` for `:hip3` regardless of strict mode.

## Decision Log

- Decision: Treat strict mode as selector universe mode for HIP3/perps, not only search matching.
  Rationale: This aligns with observed Hyperliquid tab semantics and user expectations for HIP3 parity.
  Date/Author: 2026-02-19 / Codex
- Decision: Keep legacy cache compatibility fallback for rows missing `:hip3-eligible?`.
  Rationale: Avoids temporary empty lists while cache/refresh converges.
  Date/Author: 2026-02-19 / Codex
- Decision: Limit this parity fix to strict-mode tab semantics in selector filtering, without changing market normalization in this pass.
  Rationale: Root cause is mode wiring in `tab-match?`; this is the smallest corrective scope with low regression risk.
  Date/Author: 2026-02-19 / Codex

## Outcomes & Retrospective

Selector filtering now mirrors Hyperliquid mode behavior for HIP3/perps: strict off shows full HIP3 rows, strict on applies eligibility for HIP3 rows. The change is localized to `tab-match?` strict-aware logic and tests. Validation gates passed in full (`npm run check`, `npm test`, `npm run test:websocket`), confirming no regressions across app and websocket suites.

## Context and Orientation

Relevant files:

- `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` contains `tab-match?` and strict/search filtering flow.
- `/hyperopen/src/hyperopen/asset_selector/markets.cljs` contains normalized `:hip3?` and `:hip3-eligible?` projection.
- `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs` contains tab/filter behavior tests.

Current behavior mismatch is at selector filtering time, not data fetch time.

## Plan of Work

Make `tab-match?` strict-aware. HIP3 tab should require eligibility only when strict is enabled; with strict disabled it should include all HIP3 rows. Apply matching logic to `:perps` tab for strict-mode parity with Hyperliquid’s named-dex behavior. Update tests accordingly and run required gates.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`:
   - make `tab-match?` accept strict mode
   - make `:hip3` logic strict-dependent
   - make `:perps` logic strict-dependent for HIP3 rows
2. Edit `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`:
   - assert HIP3 strict-off includes full HIP3 set
   - assert HIP3 strict-on applies eligibility gate
   - assert perps strict-on behavior remains consistent
3. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance criteria:

- With strict off, HIP3 tab does not filter out HIP3 rows by eligibility.
- With strict on, HIP3 tab applies `:hip3-eligible?` filtering.
- Perps tab strict-on excludes ineligible HIP3 rows but keeps non-HIP3 perps.
- Required repository gates pass.

## Idempotence and Recovery

Changes are pure filtering logic and test updates. Re-running the steps is safe. Rollback is localized to the view filter function and related tests.

## Artifacts and Notes

Implementation focus:

- `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`
- `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`

## Interfaces and Dependencies

No new dependencies. Existing public interfaces remain stable.

Plan revision note: 2026-02-19 19:42Z - Initial plan created before strict-mode parity implementation.
Plan revision note: 2026-02-19 19:43Z - Updated progress, decision log, and outcomes after implementation and validation completion.
