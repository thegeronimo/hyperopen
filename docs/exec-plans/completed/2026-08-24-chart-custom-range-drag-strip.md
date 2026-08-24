---
owner: worker
status: completed
created: 2026-08-24
surface: portfolio + vaults chart range
---

# Custom date range via chart drag (design 1c) — portfolio and vaults

## Purpose

Let a trader select a date window outside the fixed preset list, on both the
portfolio equity chart and the vault detail chart, by dragging a context strip
underneath the chart. The chosen window drives the chart, the summary stats and
the tearsheet alike, and is shareable through the URL.

## Context

Origin: a **direct user request** to implement option 1c of the Claude Design
project `94f9298a-7d95-4ac3-9534-42886c035714`
(`Custom Range.dc.html`, "Drag on the chart (context strip)").

Repo artifacts:

- `src/hyperopen/portfolio/custom_range.cljs` — pure range/drag domain
- `src/hyperopen/views/chart/range_strip_model.cljs` — shared strip view-model
- `src/hyperopen/views/chart/range_strip.cljs` — shared strip component

Today both surfaces expose only a fixed preset list (`:day :week :month
:three-month :six-month :one-year :two-year :all-time`). The whole windowing
pipeline is **cutoff-only**: `summary-window-cutoff-ms` returns a single start
bound and every consumer assumes the window ends at the last data point.

## Design (1c, as specified)

- `Custom…` is the **last item** of the existing preset menu; the preset list
  and the Range chip stay intact.
- Choosing it reveals an **all-time context strip under the chart**, seeded with
  the currently displayed window.
- Drag anywhere on the strip to sweep a new range; drag either handle to nudge
  one edge. Chip, chart and stats update **live during the drag**.
- Header hint switches to "Release to set the range" while dragging.
- Draft label reads `Mar 3 – Jun 12 · 102D`; `Done` collapses the strip.
- Chip reads `Mar 3 – Jun 12 ✕` while a custom range is live; `✕` restores the
  previous preset.
- The range belongs in the URL (`?from=2026-03-03&to=2026-06-12`) so a custom
  view is shareable and survives a refresh.
- Mobile: handle-only dragging with fat (14px) handles, plus a 44px `Done`.

## Key implementation decision

**Clip at the source instead of threading an end bound through every consumer.**

A custom range sources from the **all-time** summary bucket and clips its history
rows to `[from, to]`. Downstream the clipped series looks exactly like an
all-time series whose last point is `to` and whose first point is `from`, so the
existing metrics / benchmark / chart / tearsheet math needs no end-bound
plumbing. This mirrors what `derived-summary-entry` already does for the
derivable preset ranges.

## Acceptance criteria

- [ ] Portfolio: `Custom…` in the range menu opens the strip; dragging updates
      chart + summary stats + chip live; `✕` restores the previous preset.
- [ ] Vaults detail: same behaviour on the vault chart.
- [ ] Custom range round-trips through the URL as `from`/`to` and survives reload.
- [ ] Presets keep working unchanged; no preset regression.
- [ ] Drag math is pure and unit-tested (no DOM reads in the handler).
- [ ] `npm run gates` passes (`check`, `test`, `test:websocket`).
- [ ] Playwright coverage for the drag flow on both surfaces.

## Progress

- [x] Read design 1c and map the existing range surface.
- [x] Pure domain: `hyperopen.portfolio.custom-range` (normalize, clip, format,
      pointer→timestamp drag math) + unit tests (24 deftests).
- [x] Window clipping in portfolio VM + metrics + vault performance model.
- [x] Actions + full contract surface (args, registration, effect order, Lean).
- [x] URL round-trip (`from`/`to`) for portfolio and vault detail.
- [x] Shared `views/chart/range_strip_model.cljs` + `views/chart/range_strip.cljs`.
- [x] Wire portfolio chart card and vault detail chart card + chip affordances.
- [x] Styles (token-only, theme ratchet unchanged).
- [x] Unit tests for the new actions, window clipping and URL round-trip.
- [x] Gates green (`npm run gates`: 34/34).
- [x] Playwright coverage for the drag on both surfaces
      (`tools/playwright/test/chart-custom-range.spec.mjs`, 4 tests).
- [x] Verified in a real browser on both surfaces (screenshots in the session).
- [x] Adversarial review round (4 lenses x refutation): 26 findings raised,
      12 confirmed and fixed, 14 refuted.
- [x] Keyboard nudging of the strip handles — **deferred** by the maintainer and
      carried in `docs/exec-plans/tech-debt-tracker.md` so it is not lost.
- [x] Land: merged into `main` and moved to `docs/exec-plans/completed/`.

## Surprises & Discoveries

- **Both surfaces freeze their entire view build while the chart reports hover**
  (`views/portfolio_view.cljs`, `views/vaults/detail_view.cljs`). Left alone,
  a drag beginning while the pointer had been over the chart would replay cached
  sections and the chart would not move. The custom-range state is now part of
  both cache keys.
- **Deriving every custom range from the all-time bucket would silently coarsen
  short windows.** All-time is sampled daily while `:week`/`:month` are fetched
  directly at much finer resolution. `custom-source-key` walks the buckets
  finest-first and picks the densest one that still reaches back past `:from`.
- **The end bound needed no plumbing at all.** Every consumer already reads the
  window end off the last sample, so clipping the source rows at `:to` and
  feeding `:from` to the existing cutoff machinery preserves the anchor-point and
  `:complete-window?` semantics that presets rely on, with no new parameters.

- The windowing pipeline is cutoff-only (start bound); an end bound would
  otherwise have to thread through `bounded-returns-window`, the metrics
  builder and the benchmark alignment. Clipping at the source avoids all of it.
- `hyperopen.portfolio.optimizer` already establishes the pure-drag precedent:
  `:event/clientX`, `:event.currentTarget/bounds`, `:event/pointer-buttons`
  placeholders resolved in the action, math pure in the handler
  (`setup_exposure_map.cljs`). The range strip reuses that shape.
- Vault detail already shares `portfolio-query-state/parse-range-value`, so the
  `from`/`to` params can share one parser across both surfaces.

## Decision Log

- **The range value is polymorphic: a preset keyword OR a `{:from :to}` map.**
  One binding per view-model (`portfolio-vm`, `vault-detail-vm`) swaps the
  preset for the custom window, and every downstream consumer — windowing,
  benchmark alignment, metrics signature and, critically, the memoization caches
  — becomes custom-aware without a new parameter. The vault caches compare their
  range key with `=`, so a map that differs by one day invalidates them for free;
  had the custom range been collapsed to a `:custom` token, `cached-portfolio-summary`
  would have returned a stale summary and the chart would not have moved during
  a drag at all.
- **The custom window is stored BESIDE the preset, never on top of it.**
  `[:vaults-ui :snapshot-range]` is shared with the vault LIST page and is
  persisted via `(name snapshot-range)`, which a map would break; the portfolio
  preset is persisted the same way. Keeping them separate also makes the chip's
  clear affordance trivial — the preset was never lost, so clearing is just
  selecting it again.
- **Handles are HTML, the sparkline is SVG.** The strip stretches to any width,
  so a `preserveAspectRatio="none"` SVG would smear fixed-width handle rects into
  unusable slabs. Handles are absolutely-positioned HTML in percentages; the
  sparkline keeps `vector-effect: non-scaling-stroke`.
- **One pointer handler set, on the container.** Hit-testing which handle a press
  grabs happens in the pure action from the pointer fraction, not via per-handle
  DOM listeners, so every sample of a gesture is measured against the same rect.
  Mixing rects between pointer-down and pointer-move is what makes a brush drift.
- **Keyboard nudging is deliberately not in this pass, and was then deferred.**
  All eight presets remain fully keyboard-operable, so nothing regressed — but
  that argues it is not a *regression*, not that it is not a *gap*: the
  custom-range capability itself is pointer-only, which WCAG 2.1 SC 2.1.1
  (Keyboard) flags. The maintainer reviewed this and chose to defer it rather
  than block the landing, so it moved to `docs/exec-plans/tech-debt-tracker.md`
  with concrete retirement criteria instead of being dropped.

- **Custom range is one value, not draft+committed.** Drag writes it directly
  (state-only, no effects) so the chart follows live; only pointer-up / Done
  emit the URL + benchmark-refetch effects. Keeps pointer-rate dispatch cheap.
- **Benchmark candles for a custom range use the all-time request** (`:1d`,
  5000 bars): it is the widest window and therefore covers any sub-range.
- **Strip lives under the chart, not on it.** Brushing the main d3 chart would
  collide with its hover/tooltip runtime; a separate context strip is what the
  design specifies and keeps `views/chart/d3/runtime.cljs` untouched.

## Review round (post-implementation)

An adversarial review over the change set raised 26 findings; 12 survived
refutation and were fixed. The dominant theme was **benchmark candle coverage**,
and its root cause was a decision in this very plan that the implementation then
contradicted:

- **Candle requests must use the ALL-TIME bucket for a custom range.** The
  Decision Log below says exactly that, and for the right reason — candles are
  fetched as the newest N bars ending NOW, with no end bound, so a request sized
  to the custom SPAN only reaches back ~1.3x that span. The implementation had
  drifted to a span-sized request (`span-preset`), which meant a custom window
  further in the past than that came back with no overlapping candles: the
  benchmark line either vanished, or — worse — silently rebased to the oldest
  candle actually returned and reported plausible-but-wrong alpha. Reverted to
  the plan. `span-preset` survives, but only for choosing a tooltip timestamp
  format, which is what it is actually good for.
- **Every benchmark fetch site must read the EFFECTIVE range, not the preset.**
  `select-portfolio-chart-tab`, `select-portfolio-returns-benchmark`,
  `set-vault-detail-chart-series`, `select-vault-detail-returns-benchmark` and
  the vault route loader all still read the preset key, so they fetched at one
  interval while the view model read at another. The worst case was the shared
  link this feature exists to produce: the vault route loader fetched at the
  preset's interval, nothing ever fetched at the custom window's, and the
  "Vault analytics are still syncing" banner never cleared.
- **Opening `Custom…` is not a no-op.** The seed is day-snapped and switches the
  candle bucket, so the action now publishes to the URL and refetches (and gained
  an effect-order policy on both surfaces) instead of silently applying a window
  the URL never mentioned.
- **A window outside the account's history now reads as empty.** It previously
  returned nil from the derived-entry path and fell through to the fallback
  chain, which for a custom range starts at all-time — so a shared link naming a
  window this account has no data for plotted the account's ENTIRE history under
  a custom label. An empty window is honest; the wrong window is not.
- **The vault tearsheet's Range chip vanished** whenever a custom range was
  applied, because that panel gates the whole control on
  `(keyword? selected-timeframe)`. It now receives the preset, like the chart menu.
- **Tooltip timestamps keyed off the preset**, so 24H + a six-month custom window
  rendered every tooltip as a bare clock time.
- **The strip was inert for a history spanning one UTC day**, because the domain
  widening test ran on raw ms while every consumer day-snaps.

## Follow-up: tearsheet range sync (user-reported)

The tearsheet's Range chip still labelled itself with the PRESET while its numbers
were already computed over the custom window, and it had no way to set one. The
first review round had "fixed" this in the wrong direction — passing the preset so
the chip would stop vanishing — which cured the symptom and kept the lie.

Corrected:

- **The chip labels itself with the window in force** and grows the same clear
  control as the chart's, on both surfaces.
- **The tearsheet has its own `Custom…` row**, and it reveals a strip *in place*.
  The strip-open state is now a TARGET (`:chart` / `:metrics` / nil) rather than a
  boolean: one shared range, but the strip appears in whichever panel asked for
  it. A single boolean would have opened the strip under the chart — possibly
  scrolled off screen — and read as nothing happening.
- **Both chips move together** because both read the same `[:vaults-ui
  :detail-custom-range]` / `[:portfolio-ui :summary-custom-range]`; there is only
  ever one window, so they cannot drift.
- The metrics **loading overlay used to cover its own strip** (`z-10`), and the
  panel enters loading on every range change — so a drag blocked itself after the
  first sample. The strip now sits above it: recomputing metrics must never
  disable the control that triggered the recompute.
- Range writes now close **both** vault timeframe menus, and a long custom span no
  longer wraps the chip onto three lines in the narrower tearsheet header.
- **The tearsheet's metric column widens for a custom span** (220px -> 320px).
  `Range Oct 21 '24 - Mar 31 '26` plus its caret and clear control does not fit
  the preset-sized column, so the chip overlapped the first benchmark column
  header. The column cannot be content-sized: the header and the metric rows are
  separate grid containers that only line up because they share one literal track
  list. A Playwright assertion now measures the chip's right edge against the
  first benchmark label so the overlap cannot come back silently.

## Follow-up: handle cursor (user-reported)

The handles carried `pointer-events: none` — the comment said they "must not
swallow the press" — so hover never reached them and the cursor stayed
`crosshair` over an edge you could actually drag. The premise was wrong: pointer
events bubble, the listeners live on the track, and `currentTarget` (and so the
measured rect) is the track either way. Handles now take pointer events and
report `ew-resize`.

An invisible `::before` extends the cursor region to the 14px radius the action
actually grabs within, so the cursor promises exactly the target that works
rather than a narrower one. `ew-resize` is also held for the whole gesture, so it
does not flicker back to the track's crosshair when the pointer slips off the
handle it is dragging. Playwright asserts the cursor at the handle centre, inside
the invisible margin, and over open track.

## Validation

- `npm run gates` — 34/34 (`npm run check`, `npm test`, `npm run test:websocket`).
- `npm test` — 6195 tests / 35104 assertions, 0 failures.
- `PLAYWRIGHT_BASE_URL=http://127.0.0.1:8090 npx playwright test
  tools/playwright/test/chart-custom-range.spec.mjs --workers=1` — 5 passed,
  including a test that drives the tearsheet chip, its own strip, and the
  chart<->tearsheet sync end to end, and measures the header chip against the
  first benchmark column to catch overlap.
- Manual browser verification on both surfaces (portfolio equity chart and vault
  detail chart): strip reveals, sweep-drag and handle-drag both track the
  pointer, chip/stats/URL follow live, Done collapses, clear restores the preset.

## Outcomes & Retrospective

Shipped on both surfaces. Three defects were caught by the tests and the browser
pass rather than by review, and all three were the "silently wrong window" class
this feature is most exposed to:

1. **`vault-detail-returns-benchmark-fetch-effects` collapsed a custom range back
   to a preset.** `normalize-vault-snapshot-range` only recognizes presets and
   silently returns the default for anything else, so a two-year custom window
   would have fetched 30 days of benchmark candles and the benchmark line would
   have stopped short of the chart with no error anywhere. Caught by a unit test
   that asserted the candle interval, not just that a fetch happened.
2. **Re-opening the strip on an already-custom window stretched it to today.**
   The seed recomputed the end bound from the data domain instead of reusing the
   applied window. Caught by the Playwright handle-drag test.
3. **The vault chip's clear control wrapped onto its own line**, because the
   trigger is a block-level flex button and the menu container was not a flex
   row. Only a screenshot could have caught this — every DOM assertion passed.

The load-bearing design choice was making the range value polymorphic (preset
keyword OR `{:from :to}` map) rather than introducing a `:custom` token. It made
the memoization caches correct for free, since they already compare their range
key with `=`.

