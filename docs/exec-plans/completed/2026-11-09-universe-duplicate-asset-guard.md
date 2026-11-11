# Warn before adding a second listing of a stock already in the optimizer universe

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It must be maintained in accordance with `.agents/PLANS.md` (repository root).

## Purpose / Big Picture

The Hyperliquid catalog lists one stock several times: NVDA-USDC on one HIP-3 dex, NVDA-USDH on another, NVDA/USDC as spot. In a long hand-built optimizer universe a trader can search, add a second NVDA listing, and never notice the first. The optimizer then splits one bet across two near-perfectly correlated lines and the weights become unstable.

After this change, on `/portfolio/optimize/new` (or a draft), typing `NVDA` in the Universe search when NVDA-USDC is already included shows a banner ("You already have NVDA"), tags every other NVDA listing "Same as NVDA-USDC", and outlines the held row in the list below. Clicking "+ add" (or Enter) on a tagged row opens an inline confirm beside the row — Swap (default, keeps side and place), Add both (asked once per stock per session), Keep current (Esc). Add-all skips same-stock rows and says how many. If both are kept, the list groups them under an "NVDA × 2 · same stock" header, the panel header reads "1 stock listed twice", and Data health carries a caution (unless the two are on opposite sides — a deliberate hedge).

## Context References

Public refs:
- Direct maintainer request (2026-11-09, this session), with the approved design canvas "Universe duplicate-asset warning" (claude.ai artifact, private to the owner).

Repo artifacts:
- Universe search projection: `src/hyperopen/portfolio/optimizer/application/view_model/universe_search.cljs`, `.../view_model/universe.cljs`.

Local scratch refs (non-authoritative):
- None.

## Context and Orientation

- Matching rule and projections live in the new `src/hyperopen/portfolio/optimizer/application/universe_duplicates.cljs`: `underlying-key` (base with `dex:` prefix and quote token dropped; perps and spot only; vaults never match; no prefix/fuzzy matching), search-row annotation, add-all de-duplication, twin grouping, readiness warnings.
- The guard is on the panel's existing action `:actions/add-portfolio-optimizer-universe-instrument`, which gained an optional `choice` arg (`swap` | `both` | `keep`). No new action ids, so the effect-order policy and its Lean model are unchanged. The results-page add-asset popover uses `...-and-run`, which stays unguarded (it renders no confirm).
- UI state: `[:portfolio-ui :optimizer :universe-duplicate-prompt]` (market-key or nil) and `:universe-duplicate-acknowledged` (underlyings kept twice this session).

## Plan of Work

1. Pure matching + projections with unit tests.
2. Guarded add, swap (remove + add coalesced into one save, original position and side kept), both, keep; keyboard Enter/Escape answer an open confirm; add-all skips same-stock rows.
3. View: banner, same-as tag, inline confirm rendered as the row's sibling (the row is a click target), outlined held row, twin group header, header count.
4. Readiness caution `:duplicate-underlying`, readiness-only (never sent to the engine).

## Validation and Acceptance

- `test/hyperopen/portfolio/optimizer/application/universe_duplicates_test.cljs`, `test/hyperopen/portfolio/optimizer/universe_duplicate_actions_test.cljs`, `test/hyperopen/views/portfolio/optimize/setup_universe_duplicate_layout_test.cljs`.
- Required gates: `npm run check`, `npm test`, `npm run test:websocket` (`npm run gates`).
- Browser: search a held stock's ticker in the Universe panel and walk swap / both / keep.

## Progress

- [x] (2026-11-09) Design approved on canvas; code mapped.
- [x] (2026-11-09) Matching, actions, view-model, views, readiness caution implemented; `npm test` green (8113 tests, 0 failures).
- [x] (2026-11-09) `npm run gates` 34/34 PASS; Playwright `optimizer-universe-duplicate-guard.spec.mjs` 2/2 against the worktree build (real catalog merged with seeded markets); screenshots reviewed.
- [x] (2026-11-09) QA fixes: labels qualified with the dex ("NVDA-USDC · xyz") because the live catalog has two NVDA-USDC listings on different dexes; the confirm scrolls itself into view on mount because the result list's short scroll box hid its buttons.
- [x] (2026-11-09) Regression pass vs `main` (temp worktree at HEAD, same SPA server): existing optimizer + portfolio Playwright specs, 104 tests. 19 failed on the feature build; 13 fail identically on `main`; the other 6 were rerun — 4 pass, and 2 (`optimizer-history-assumptions-recommended:152`, `portfolio-regressions:3144`) are flaky on BOTH builds under `--repeat-each=3`. Before/after panel screenshots at 375 / 768 / 1440: idle panel pixel-identical; fixed from review — "stock" copy (wrong for crypto) → "asset", footer wrap with the long add-all label, confirm headline under the sticky group header (scroll-mt + compacted card), Swap label wrap, twin-header hint truncation, header count shortened to "· N duplicate(s)". Gates 34/34 after fixes.
- [x] (2026-11-09) Maintainer approved; committed on `feature/duplicate-asset-detection-ce5abb`.

## Surprises & Discoveries

- Observation: the live catalog lists NVDA-USDC on more than one dex, so "Same as NVDA-USDC" was ambiguous; labels now carry the dex.
  Evidence: QA screenshot of `NVD` search showed two NVDA-USDC and two NVDA-USDH perps.
- Observation: the codebase has no scroll-into-view effect, so the design's "Show it →" link was replaced by outlining the held row whenever the query matches it.
  Evidence: `grep -rn scrollIntoView src` returns nothing.

## Decision Log

- Decision: extend the existing add action with an optional choice instead of adding a new action.
  Rationale: avoids a new effect-order policy row and Lean regeneration for what is the same add.
  Date/Author: 2026-11-09 / Claude.
- Decision: add-all also collapses same-stock rows within the batch (PUMP perp + three PUMP spot pairs add once).
  Rationale: "add all" on a ticker should not create the duplicate the single-add guard prevents.
  Date/Author: 2026-11-09 / Claude.
- Decision: no readiness caution for opposite-side twins.
  Rationale: long spot / short perp of one asset is a deliberate hedge.
  Date/Author: 2026-11-09 / Claude.
- Decision: the design's Undo toast and "Keep one ▾" menu were not built; the twin header points at the existing per-row × instead.
  Rationale: no toast surface exists on this panel; × already removes a row in one click.
  Date/Author: 2026-11-09 / Claude.

## Outcomes & Retrospective

Shipped behind the existing add action; all gates green. Not built from the design: Undo toast, "Keep one ▾" menu, "Show it →" scroll link (see Decision Log). Shift+Enter for "Add both" is not wired (the keydown action only receives the key name); the button is reachable by Tab.
