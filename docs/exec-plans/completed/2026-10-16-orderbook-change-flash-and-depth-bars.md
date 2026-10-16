# Order book change flash, compositor depth bars, and tick-precision prices

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

Context: direct maintainer request (2026-10-16). The maintainer asked why trade.xyz's order book feels more real-time than ours and asked for improvements.

## Purpose / Big Picture

trade.xyz's order book feels more live than ours, but the data is not faster. Headless Playwright measurements on `xyz:XYZ100` showed both books redrawing at the same ~540ms cadence from the same Hyperliquid `l2` feed, both at about 120fps, and both keeping row DOM nodes stable. The difference is in presentation:

- trade.xyz flashes each changed row with a 500ms tinted glow (about 7 flashes per update). We showed no change cue.
- Their depth bars ease over 200ms. Ours took 100ms, which reads as a snap.
- They print prices at tick precision (`29,904`). We printed `29,899.00`.

After this change, the user sees a short glow on each price level whose size changed or that just appeared. Depth bars glide, and the browser animates them with `transform` so the row is not laid out again on every frame. Ladder prices use the book's real tick precision.

## Progress

- [x] (2026-10-16) Measured both books with Playwright and identified the presentational gaps.
- [x] (2026-10-16) Policy: `book-price-decimals`, `format-level-price`, `cumulative-bar-scale`, `level-changes`, `annotate-level-changes`; `same-render-book?` ignores annotations.
- [x] (2026-10-16) `publish-book!` annotates every published book against the previous one.
- [x] (2026-10-16) View: `scaleX` depth bars (desktop and mobile split), flash classes from `level-flash-class`, wired through `panel`.
- [x] (2026-10-16) CSS: `ob-flash-{bid,ask}-{a,b}` classes, keyframes, and a reduced-motion opt-out.
- [x] (2026-10-16) Tests: new `orderbook-level-changes-test`, and an extended `depth-transition-test`; `npm test` is green.
- [x] (2026-10-16) `npm run gates`: 34/34 PASS (7819 tests, 43032 assertions).
- [x] (2026-10-16) Headless Playwright QA against the worktree's own build (static SPA server on :8097): `xyz:XYZ100` and `BTC` at 1440x900, and `BTC` at 375x812 (mobile split).

## Surprises & Discoveries

- Observation: Replicant reads keys only from `:replicant/key` in the attribute map. The `^{:key ...}` metadata on order book rows is ignored, so rows are reused by position, just as on trade.xyz.
  Evidence: `replicant/hiccup_headers.cljc` `get-key` reads `(:replicant/key attrs)`. A MutationObserver run saw 0 row additions while all 40 prices shifted.
  Consequence: a flash cannot be driven by element lifecycle. The flash class is derived from data instead: the set of changed prices plus the update number.

- Observation: early measurements in the desktop app's browser pane showed an irregular 66–1000ms update cadence. That was the hidden pane being throttled to 1fps, not a real defect. Headless Playwright showed a steady cadence.

## Decision Log

- Decision: flash by price level rather than by row position.
  Rationale: trade.xyz flashes by position, so a one-tick move flashes the whole ladder. Flashing by price shows only the levels that actually changed.
- Decision: alternate between `a` and `b` keyframe names on the parity of `:flash-version`.
  Rationale: changing the animation name restarts a CSS animation without an extra render to remove the class first. The class is present only in the update in which the level changed.
- Decision: a previous book that shares no visible price with the next one is not treated as a predecessor.
  Rationale: this avoids a whole-ladder flash on first load, coin switch, or precision change.
- Decision: the bid flash uses `--ho-buy`, not the legacy `rgb(31,166,125)` bid literal.
  Rationale: `lint:theme-colors` ratchets raw literals, and the token follows every theme.

## Outcomes & Retrospective

Shipped as planned. QA evidence, 15s per market at 1440x900:

- `xyz:XYZ100`: 730 flash starts over 31 updates. Every one landed on a level whose size changed; none fired on an unchanged level and none were missed.
- `BTC`: 888 flash starts, with the same zero misfires and zero misses. On BTC, about 10 of 16 visible rows flash per update.
- Bars use `transform 0.2s` with `scaleX(...)`. Prices print at tick precision (`81,965`; spread `1`). Animation names alternate between `ob-level-flash-a` and `ob-level-flash-b`. No page errors.
- Mobile split (375px): bids anchor right (`origin-right`), asks anchor left, and flashes fire on each half.

Remaining risks:

- If two changing updates for a level are coalesced into one frame and their versions share parity, the class does not change and that flash is skipped. This is rare and harmless.
- The flash rate on very active books is high. Lowering the peak opacity in the keyframes (`trading-controls.css`) is the tuning knob.
- The `^{:key}` metadata on order book rows is inert (Replicant ignores it). It was left in place because positional reuse is the desired behaviour.
