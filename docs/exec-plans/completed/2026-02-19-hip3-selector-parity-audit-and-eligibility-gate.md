# HIP3 Selector Parity Audit and Eligibility Gate

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, the HIP3 tab in Hyperopen will apply a deterministic eligibility gate that matches Hyperliquid’s currently visible HIP3 behavior instead of showing the full raw named-dex universe. This reduces mismatch during parity checks and makes the list length users see in HIP3 consistent with the “tradable/eligible” subset rather than every named-dex listing.

## Progress

- [x] (2026-02-19 18:39Z) Audited Hyperliquid and Hyperopen HIP3 symbol pipelines using raw API responses and selector implementation traces.
- [x] (2026-02-19 18:39Z) Identified parity root cause: Hyperopen’s HIP3 tab currently filters only on `:hip3?`, while Hyperliquid applies an additional eligibility gate.
- [x] (2026-02-19 18:42Z) Implemented deterministic HIP3 eligibility projection in `/hyperopen/src/hyperopen/asset_selector/markets.cljs`.
- [x] (2026-02-19 18:42Z) Applied HIP3 eligibility gate in `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` with legacy cache compatibility for missing eligibility fields.
- [x] (2026-02-19 18:42Z) Persisted/restored eligibility in `/hyperopen/src/hyperopen/asset_selector/markets_cache.cljs`.
- [x] (2026-02-19 18:42Z) Updated tests for normalization, cache persistence, and HIP3 tab filtering.
- [x] (2026-02-19 18:42Z) Ran required validation gates (`npm run check`, `npm test`, `npm run test:websocket`) with all green.
- [x] (2026-02-19 18:42Z) Committed code and plan updates.

## Surprises & Discoveries

- Observation: The raw named-dex HIP3 universe from `perpDexs` + `metaAndAssetCtxs` is materially larger than Hyperliquid’s currently rendered HIP3 tab.
  Evidence: API audit returned 126 named-dex perp markets, while selector-level eligibility subsets were significantly smaller.
- Observation: Hyperliquid’s shipped frontend includes a dedicated HIP3/perp eligibility function (`Ce`) in addition to the basic “named dex perp” check (`Ee`).
  Evidence: The bundled selector logic checks `Ce` in HIP3/perps branches; `Ce` references delist status and an allowlist path.
- Observation: Legacy selector cache entries do not include the new eligibility key.
  Evidence: Existing cache normalization only persisted `:hip3?` before this change; without compatibility handling HIP3 tabs could temporarily hide cached rows until fresh network load.

## Decision Log

- Decision: Implement an explicit `:hip3-eligible?` projection during market normalization and make HIP3 tab filtering depend on it.
  Rationale: Keeping eligibility as normalized state avoids recomputing ad hoc in the view layer and keeps filtering deterministic/testable.
  Date/Author: 2026-02-19 / Codex
- Decision: Use a deterministic local gate based on delist status and minimum open-interest USD threshold.
  Rationale: This aligns with observed Hyperliquid output cardinality and uses fields already present in fetched market metadata/context.
  Date/Author: 2026-02-19 / Codex
- Decision: Set the minimum open-interest gate at 1,000,000 USD notional (`openInterest * markPx`) for normalized HIP3 eligibility.
  Rationale: This threshold produced the closest observable parity during the audit while remaining deterministic from current payload fields.
  Date/Author: 2026-02-19 / Codex
- Decision: Keep backward compatibility for legacy cached rows that do not include `:hip3-eligible?` by treating missing eligibility as visible.
  Rationale: Prevents temporary empty/regressed HIP3 tabs during cache hydration before fresh network normalization lands.
  Date/Author: 2026-02-19 / Codex

## Outcomes & Retrospective

HIP3 parity gating is now implemented end-to-end in normalization, view filtering, and cache persistence. The selector now carries `:hip3-eligible?` and `:delisted?` on normalized perp markets, HIP3 tab matching enforces eligibility when present, and legacy cached rows remain visible until refreshed. Targeted tests were updated and all required repository gates passed (`npm run check`, `npm test`, `npm run test:websocket`). The implementation and plan were finalized together in a single commit.

## Context and Orientation

Relevant modules and responsibilities:

- `/hyperopen/src/hyperopen/asset_selector/markets.cljs`: canonical normalization for perp/spot selector rows.
- `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`: tab filtering and list rendering logic.
- `/hyperopen/src/hyperopen/asset_selector/markets_cache.cljs`: cache normalization/persistence of selector rows.
- `/hyperopen/test/hyperopen/asset_selector/markets_test.cljs`: normalization unit tests.
- `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`: selector filter/render tests.
- `/hyperopen/test/hyperopen/asset_selector/markets_cache_test.cljs`: cache normalization tests.

The current mismatch is not a fetch-count issue in the loader path; it is a selector eligibility-policy mismatch. Hyperopen currently treats all named-dex perps as HIP3-visible, while Hyperliquid applies additional screening before surfacing HIP3 rows.

## Plan of Work

Add `:hip3-eligible?` as part of normalized perp market rows. Compute it from normalized fields so the behavior is pure and deterministic. Feed this field into HIP3 tab filtering. Keep other tabs unchanged. Update cache normalization so the new field survives hydration/persistence. Add targeted tests for normalization and tab filtering, including a compatibility case for legacy cached rows without the new key.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/asset_selector/markets.cljs` to project `:delisted?` and `:hip3-eligible?` for perp markets.
2. Edit `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` to make HIP3 tab matching require eligibility (with missing-field compatibility fallback).
3. Edit `/hyperopen/src/hyperopen/asset_selector/markets_cache.cljs` to persist/restore `:hip3-eligible?`.
4. Update tests:
   - `/hyperopen/test/hyperopen/asset_selector/markets_test.cljs`
   - `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`
   - `/hyperopen/test/hyperopen/asset_selector/markets_cache_test.cljs`
5. Run required gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
6. Commit the implementation and this ExecPlan update.

## Validation and Acceptance

Acceptance criteria:

- HIP3 rows are no longer selected solely by `:hip3?`; they also require `:hip3-eligible?` when that field is present.
- Perp normalization includes deterministic `:hip3-eligible?` and `:delisted?` fields.
- Cache normalization preserves `:hip3-eligible?` so selector behavior is stable across reloads.
- Existing non-HIP3 tab behavior remains unchanged.
- Required repository validation gates pass.

## Idempotence and Recovery

Changes are additive and deterministic. Re-running normalization or cache persistence produces the same output for the same input payloads. If the eligibility gate causes unexpected filtering, rollback is contained to the three edited modules and their corresponding tests.

## Artifacts and Notes

Primary implementation files:

- `/hyperopen/src/hyperopen/asset_selector/markets.cljs`
- `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`
- `/hyperopen/src/hyperopen/asset_selector/markets_cache.cljs`

Primary validation files:

- `/hyperopen/test/hyperopen/asset_selector/markets_test.cljs`
- `/hyperopen/test/hyperopen/views/asset_selector_view_test.cljs`
- `/hyperopen/test/hyperopen/asset_selector/markets_cache_test.cljs`

## Interfaces and Dependencies

No new dependencies are introduced. Existing interfaces remain intact; the change extends market row shape with `:hip3-eligible?` and `:delisted?`, both optional booleans.

Plan revision note: 2026-02-19 18:39Z - Initial plan created after parity audit findings and before implementation edits.
Plan revision note: 2026-02-19 18:42Z - Updated progress, decisions, discoveries, and outcomes after implementation and validation gates passed.
Plan revision note: 2026-02-19 18:42Z - Marked commit/finalization step complete to reflect completed execution state.
