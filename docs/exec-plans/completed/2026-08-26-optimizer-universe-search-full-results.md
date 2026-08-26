# Optimizer universe search: full, filterable results (design 1a)

Status: active
Owner: portfolio
Started: 2026-08-26

## Purpose / Big Picture

The optimizer's Universe search silently truncates. A query for `pump` matches 13
markets and the panel shows 6, with no indication the other 7 exist. The symbol
column is a fixed 66px track with `truncate`, so `PUMP-USDC`, `PUMP/USDE` and
`PUMP/USDH` all render as `PUMP-US…` and read as the same row. The perp — usually
the one the trader wants — sorts below eight spot pairs and falls under the cut.

Design 1a ("In-panel results", Claude Design project `Hyperopen search results
overflow`) keeps the results inside the existing narrow rail and makes every match
reachable: a scrollable grouped list, type and quote filter chips with live counts,
a hit count on the field, a quote pill so symbols never collapse, match
highlighting, a per-row liquidity meta line, and a footer that states how many of
how many are shown.

The alternative (1b, a 672px market-browser overlay) was rejected: the panel this
lives in is a ~348px left rail, and 1b would have to become a modal, which is a
different feature.

## Context References

Repo artifacts:

- `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs`
- `src/hyperopen/portfolio/optimizer/application/view_model/universe_search.cljs`
- `src/hyperopen/views/portfolio/optimize/setup_universe_search.cljs`
- `src/styles/surfaces/optimizer/universe.css`

Originating request: a direct user request on 2026-08-26 to "update the optimizer
search component to be like 1a", with the design supplied as a Claude Design
project.

- Design source: Claude Design project `01f2909f-f6cf-46be-baac-418f0f26a08a`
  ("Hyperopen search results overflow"), file `Universe Search.dc.html`,
  artboard `1a`.
- Durable context: `docs/agent-guides/ui-foundations.md`,
  `docs/agent-guides/trading-ui-policy.md`, `docs/BROWSER_TESTING.md`.
- The cap: `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs`
  `default-candidate-limit` = 6, applied by `(take …)` after ranking.
- The clipping: `src/styles/surfaces/optimizer/universe.css`
  `.optimizer-universe-candidate-row` `grid-template-columns: 66px …`.

## Progress

- [x] Map the constraint surface (six-agent investigation, 2026-08-26).
- [x] Milestone 1 — opts-scoped candidate limit + market-type hygiene.
- [x] Milestone 2 — view-model: filters, counts, groups, ordered keys.
- [x] Milestone 3 — contracts, defaults, actions, registration.
- [x] Milestone 4 — add-all action + effect-order policy + Lean sync.
- [x] Milestone 5 — views: search ns split, chips, grouped list, footer, hints.
- [x] Milestone 6 — CSS in `universe.css`.
- [x] Milestone 7 — unit tests (new namespaces; size caps are tight).
- [x] Milestone 8 — update the tests the redesign deliberately breaks.
- [x] Milestone 9 — `npm run gates` (34/34 PASS).
- [x] Milestone 10 — focused Playwright + browser QA (6/6 universe specs).

## Surprises & Discoveries

- **Replicant has no fragment tag.** `[:<> …]` compiles, passes every hiccup-data
  test, and then throws `createElement('<>')` at real render time, which silently
  killed the entire results subtree in the browser while the unit suite stayed
  green. The view tests inspect hiccup DATA and never run the renderer, so only
  Playwright caught it. `src/hyperopen/views/footer/links.cljs:78` still contains a
  `[:<>]` and is likely the same latent bug.
- **`coercion/non-blank-text` stringifies keywords.** It does `(some-> value str)`,
  so the stored default `:all` becomes the string `":all"`. The quote-filter
  normalizer read that as a quote token of `":ALL"` and filtered every row out —
  an empty list sitting under a "197 hits" header. Unit tests missed it because
  they omitted the key entirely (nil takes a different branch); the real app
  default is the keyword. Any normalizer reading a stored keyword must `name` it
  before text coercion.
- **The list was never the performance problem.** The full panel projects and
  renders in ~1.4 ms with an active search (0.04 ms closed), measured in-browser.
  A 4-viewport spec that sits at its timeout got *slower* with zero rows than with
  25, so the cost is that spec's four route visits plus forced-layout reads.
- **Two Playwright reds were already red on main** (verified by restoring the
  pre-change tree and re-running): the selected-row `toContainText("vault")`
  assertion, stale since the TYPE column went by-exception, and three
  add-asset-popover / objective-menu containment specs. Playwright is
  workflow_dispatch-only, so nothing caught them.

- **The 6-cap is not the Universe panel's private cap.** It is the only bound on
  three surfaces: the Universe search, the proxy typeahead
  (`setup_history_assumption_fields.cljs`), and the results-page "Add to universe"
  popover (`target_exposure_table.cljs`). That popover calls `universe-panel-model`
  with `:include-blank-candidates? true` and therefore queries with a **blank**
  string — measured live at ~838 catalog markets + ~9,462 non-child vault rows
  ≈ 10,300 rows, truncated to 6. Deleting the `take` renders all of them the moment
  the popover opens. The cap must become an `opts` key with 6 as the default, and
  only the Universe panel opts out.
- **Outcome (prediction) markets are already candidates and were only hidden by the
  cap.** `[:asset-selector :markets]` concats `outcome-markets`, nothing in the
  optimizer path filters them, they default to `:quote "USDH"` (so they would inflate
  the USDH chip), they belong to no PERP/SPOT/VAULT group, and
  `market->universe-instrument` rejects them — so "+ add" on one is a silent no-op
  today. Uncapping forces the issue; they are filtered at source.
- **Quote is a real field, not a string split** — `:quote` exists on perp and spot
  entries. But the separators differ (`BASE-QUOTE` for perps, `BASE/QUOTE` for spot)
  and **vault candidates carry no `:quote` and no `:base` at all** (7 keys total), so
  vaults need a fourth state in the quote facet rather than falling into a "USDC"
  fallback.
- **The quote chip set cannot be hard-coded.** Live spotMeta quotes are USDC 309,
  USDH 11, USDT0 5, USDE 1, and perp `:quote` is the per-dex collateral token. The
  design's `any/USDC/USDE/USDH` would drop USDT0 and every HIP-3 quote into no
  bucket. The chips are derived from the match set.
- **`adv-label` was already computed and thrown away.** `candidate-row-model` has
  emitted `:adv-label` (via `:volume24h` → `:openInterest` → `:tvl`) all along and
  the view never destructured it. Requirement 8 needed no new view-model work.
- **Four incompatible `normalize-market-type` functions exist** — one open kebab-caser,
  one closed whitelist that drops `:vault`, one that returns `:perps` (plural). Import
  the wrong one and the vault chip silently counts zero.
- **Namespace-size gates are the likeliest failure, not behaviour.**
  `setup_universe.cljs` 496/500 with no exception entry (4 lines of headroom for ten
  new affordances); `action_args.cljs` 781/**781** — zero headroom;
  `target_exposure_table.cljs` 556/556 — zero headroom;
  `view_model/universe.cljs` 615/630. Two independently-passing branches can breach a
  cap only on the merge, which this repo has hit before.
- **`market-keys` is positional and is serialized into the keydown handler.**
  Grouping the list means the rendered row order, the `idx` on each row's DOM id, and
  `market-keys` must all agree, or ArrowDown+Enter adds the wrong asset.
- **A quote pill breaks a Playwright text assertion.**
  `portfolio-regressions.spec.mjs:1967` asserts `toContainText("ETH-USDC")`; splitting
  the symbol into two nodes concatenates to `ETHUSDC`.
- **CSS is boxed in.** `tools/styles/optimizer_css_split.test.mjs` deepEquals the
  optimizer partial directory against exactly six filenames (no new partial) and fails
  any partial containing `[data-role` or `[class*=`. Arbitrary Tailwind `grid-cols-[…]`
  is banned here because the JIT drops comma-bearing values on watch rebuilds. There is
  no `--optimizer-success` token (use `--optimizer-long`), and `rounded-*` is reset to
  2px inside `.portfolio-optimizer` (only `rounded-full` survives).

## Decision Log

1. **Scope the cap through `opts`, never delete it.** `candidate-markets` gains a
   `:limit` opt defaulting to `default-candidate-limit`. The Universe panel passes a
   wide limit; the popover and proxy picker are untouched. Rejected: deleting the
   `take` (unbounds three surfaces); a per-view `take` in the view (leaves the
   view-model's `market-keys` uncapped, breaking keyboard nav).
2. **Filter `:outcome` out of optimizer candidates at source.** They cannot be added,
   so showing them is a dead end. This is a deliberate behaviour change to the
   existing search, recorded here.
3. **Derive the quote facet from the match set**, not from the design's literal
   `any/USDC/USDE/USDH`, so USDT0 and HIP-3 collateral quotes get chips. Vaults are
   excluded from the quote facet rather than bucketed.
4. **Reset the new filters wherever the query resets** — all three existing reset
   sites plus the clear button. This keeps the filter state from leaking invisibly
   into the draft-add-asset popover, which shares the same UI paths and has no chip
   UI to reveal a stuck filter. Rejected: new per-surface paths (more state, more
   drift, no user-visible gain).
5. **Cap "+ add all".** The design's footer action is unbounded; a two-character query
   can match hundreds of instruments, each triggering history work. The action adds at
   most `add-all-limit` and the label states the real number.
6. **Coalesce add-all into a single prefetch effect** so it can reuse the existing
   `:allow-duplicate-heavy-effects? false` policy shape rather than relaxing it.
7. **Split the view rather than buy a size exception.** The search UI moves to
   `setup_universe_search.cljs`; `setup_universe.cljs` keeps the panel shell and the
   selected table. An exception nobody retires is worse than a namespace.

## Validation and Acceptance

Gates (all required, per AGENTS.md):

- `npm run setup:worktree` first (fresh worktree; shadow-cljs is local-only).
- `npm run gates` → `npm run check`, `npm test`, `npm run test:websocket`.
- Focused Playwright for the universe specs before broadening.

Acceptance:

- A query matching more than 6 markets shows every match, scrollable, with a hit
  count and an honest "N of M" footer.
- `PUMP-USDC` / `PUMP/USDE` / `PUMP/USDH` are visually distinct at the rail's width.
- Type and quote chips filter the list and their counts agree with the rows.
- ArrowDown/ArrowUp/Enter still add the row the cursor is on, with grouping applied.
- The draft-add-asset popover and the proxy typeahead still show at most 6.

## Outcomes & Retrospective

Shipped. `npm run gates` 34/34 PASS (7020 tests, 38822 assertions); the six
universe Playwright specs pass; the panel was verified visually at 1440x1000.

What the panel does now, against the motivating case: a `pump` query reports its
hit count beside the field, groups matches under sticky PERPS / SPOT / VAULTS
headers with per-group counts, renders each quote token as its own tinted pill so
`PUMP/USDC`, `PUMP/USDE` and `PUMP/USDH` are distinct, highlights the matched
substring, shows 24h volume (or TVL) per row, and closes with an honest
`25 of 27 matches shown` plus a capped `+ add first 20`.

Judgement calls worth re-reading later:

- The cap is 25, not unlimited. The defect was SILENT truncation; a realistic
  query is now whole and anything beyond the cut is counted and reachable through
  the facets. Unbounded would render most of a ~9,500-row vault index per
  keystroke, and the rows are not virtualized.
- The hit line counts the same set the chips describe, so it can never contradict
  them; the footer is the single place that names both numbers.
- Filtering `:outcome` markets out of optimizer candidates is a deliberate
  behaviour change. They could never be added, so showing them was a dead end that
  only the old six-row cap hid.

Follow-ups deliberately NOT taken here: the `[:<>]` in `footer/links.cljs`, and the
pre-existing add-asset-popover / objective-menu containment reds.
