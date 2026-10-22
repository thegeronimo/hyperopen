# Show the trader's own trades on the price chart (fills at execution price, merged per candle, hover card, position ribbon) with a setting to turn it off

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `.agents/PLANS.md` (repository root). `docs/PLANS.md` summarises the same rules.


## Purpose / Big Picture

Today a trader looking at the HYPE chart in Hyperopen cannot see where they personally bought and sold unless they turn on the hidden-by-default "Fill markers" setting, and even then each fill is a small arrow floating above or below the candle. The arrow does not show the price they got, several fills in one candle stack into a tower, and nothing on the chart says whether they were long, short or flat at a given moment.

After this change, by default and for any asset, the chart shows:

1. A marker for every candle in which the trader traded. A short horizontal "execution tick" sits on the candle at the exact size-weighted average fill price. A thin stem runs from the tick to a round badge, which sits below the candle for buys (letter "B") and above it for sells (letter "S"). Same-side fills in one candle merge into one badge with a small count bubble ("3"). The badge diameter has three steps that reflect notional size relative to the trader's own median fill.
2. A hover card. Moving the mouse over a badge opens a card listing date, side, total size, average price, notional, fees, each underlying fill (time, size, price, maker/taker), position after the trade, realized PnL if any, and a link that opens the Trade History tab. Clicking a badge pins the card; Escape or clicking empty chart space unpins it.
3. A position ribbon: a thin strip along the bottom edge of the price pane, tinted with the long color while the trader held a long, the short color while short, and empty while flat.
4. Liquidation fills drawn as an amber diamond labelled "L", so a forced exit is never mistaken for a sell.

The existing live entry-price/PnL line and draggable liquidation line for the open position are unchanged.

A single setting, "Trades on chart", in the header Settings panel's Display section and as a quick toggle button in the chart's top menu, turns all of the above on or off. It defaults to on, persists in localStorage, and is shared by both entry points.

To see it working: run the app, connect or spectate an address with HYPE perp history, open HYPE on the 1D chart, and observe badges at the fill prices, a count bubble on multi-fill candles, a hover card, and the ribbon. Toggle "Trades on chart" off and all of it disappears without a reload.


## Context References

Public refs:

- Direct user request (2026-10-22): "I like your fills on chart recommendation. I guess we should create a setting alongside it where the user can toggle that off … create an execution plan to implement that now." The approved visual design is "A · Fills on chart (recommended default)" on the user's private design canvas (claude.ai artifact HPps5yd9FmTuwvRGdqE8R1, board `Main.dc.html`; board `MarkerSystem.dc.html` specifies the marker states). Every visual rule needed is restated in this plan, so the canvas is not required reading.

Repo artifacts:

- `docs/exec-plans/completed/2026-02-24-chart-position-pnl-liq-entry-overlays.md` (built the entry/PnL and liquidation DOM overlays this plan leaves in place).
- `docs/exec-plans/completed/2026-03-04-chart-position-overlay-entry-fill-indexing.md` (fill matching and timeframe alignment used by the current markers).
- `docs/exec-plans/completed/2026-03-30-trading-settings-marker-and-surface-redesign.md` (introduced the `:show-fill-markers?` setting this plan replaces).
- `docs/exec-plans/completed/2026-04-19-trading-chart-core-layer-split.md` (current chart namespace layering).
- `docs/BROWSER_STORAGE.md` (localStorage vs IndexedDB rules), `docs/BROWSER_TESTING.md`, `docs/agent-guides/trading-ui-policy.md`, `docs/agent-guides/ui-foundations.md`.

Local scratch refs (non-authoritative):

- None.


## Progress

- [x] (2026-10-22) Researched the existing chart, fill and settings code; drafted this plan.
- [x] (2026-10-22) Milestone 0: feasibility established from the LWC 5.0.8 source instead of a browser spike (see Surprises); the primitive namespace was built directly in Milestone 3.
- [x] (2026-10-22) Milestone 1: `:show-chart-trades?` (default on), Settings row `:chart-trades`, chart-toolbar "My trades" toggle, tests updated.
- [x] (2026-10-22) Milestone 2: `chart_trades_model.cljs` + `fill_fields.cljs`, 17 model tests; Opus review fixes applied (execution ordering of same-millisecond fills, liquidation `:sign`, fee token).
- [x] (2026-10-22) Milestone 3: `trade_markers_primitive.cljs` + `trade_layer.cljs`; old arrows and the entry circle removed.
- [x] (2026-10-22) Milestone 4: `trade_card.cljs` hover/pin card, tap-to-pin fallback, "Open in Trade History".
- [x] (2026-10-22) Milestone 5: ribbon drawn by the same primitive (bottom pane view).
- [x] (2026-10-22) Milestone 6: `account/chart_fill_history.cljs` + effects, windowed backward paging, action/effect registered (incl. Lean spec + regenerated formal vectors).
- [x] (2026-10-22) Browser QA on a live high-frequency HYPE account in spectate mode (dark theme, 5m/1h/1D, desktop width): markers, dots, card, pin, toggle, history paging. Three defects found and fixed (see Surprises).
- [x] (2026-10-22) `npm test` (7316 tests), `npm run test:websocket` (604), all lints, `test:styles` green; release build compiles with 0 warnings.
- [x] (2026-10-22) Final Opus review fixes: REST fills seed the chart history only when the request was for the current effective account; REST (aggregated) pages are authoritative for their span over per-trade websocket rows; older windows fetched unaggregated; boundary millisecond kept uncovered; coverage only grows; paging ends at a 2023 floor instead of one empty 90-day window; the chart vm resolves the effective account (chart view state now selects :wallet and :account-context).
- [x] (2026-10-22) Playwright: updated settings spec passes against a local build of this branch; `--grep @smoke` 41/46, the 5 failures reproduce on an unmodified `main` build served the same way (4 environmental) or pass serially 3/3 (subaccount vaultAddress, flaky only with 2 workers).
- [ ] Playwright: add a fixture-backed spec for the fill-details card; run the smoke suite against `npm run dev:browser-inspection` on port 8080.
- [ ] Browser QA in the institutional and hyperdegen themes, on a spot pair, a HIP-3 market and at phone width.
- [x] (2026-10-22) `npm run gates`: 34/34 PASS, 8122 tests, 44381 assertions.
- [ ] Move this plan to `completed/` once the two open items above are done.


## Surprises & Discoveries

- Observation: a simpler version of this feature already exists but is off by default.
  Evidence: `src/hyperopen/trading_settings.cljs` `default-state` has `:show-fill-markers? false`; `src/hyperopen/views/trading_chart/utils/position_overlay_model.cljs` `build-fill-markers` emits one `{:position "belowBar"/"aboveBar" :shape "arrowUp"/"arrowDown" :text "B"/"S"}` marker per fill.

- Observation: the live websocket fill handler truncates the shared fills vector to 200 rows, so after any new live fill the chart (and Trade History) loses everything older than the newest 200 fills, even if the REST snapshot loaded up to 2000.
  Evidence: `src/hyperopen/websocket/user_runtime/handlers.cljs` line 11-14, `upsert-seq` ends in `(vec (take 200 combined))`, applied to `[:orders :fills]` at line 105.

- Observation: the installed lightweight-charts (5.0.8) supports price-anchored built-in markers (`atPriceTop`, `atPriceBottom`, `atPriceMiddle` with a `price` field) and series primitives with `hitTest`. No code in the repo uses primitives yet; every existing overlay is absolutely positioned DOM.
  Evidence: `node_modules/lightweight-charts/dist/typings.d.ts` lines 1847 and 2354 (`attachPrimitive`), 1965 and 2543 (`hitTest?(x, y): PrimitiveHoveredItem | null`).

- Observation: the "L"/"S" entry circle for the open position is drawn whether or not fill markers are on.
  Evidence: `position_overlay_model.cljs` lines 328-333 build `:entry-marker` unconditionally; `core.cljs` line 42 always appends it.

- Observation (Milestone 0, from the LWC 5.0.8 source): `hitTest` lives on the primitive object itself (not on a pane view); its `externalId` reaches both `subscribeCrosshairMove` and `subscribeClick` params as `hoveredObjectId`. Removing a series destroys it and detaches its primitives, so a chart-type swap must re-attach (handled by comparing the series identity on every sync). `requestUpdate` is a full model update. Touch taps do NOT run the primitive hit test (the click param has no `hoveredObjectId`), so the layer hit-tests the painted layout at `param.point` itself.
  Evidence: `lightweight-charts.development.mjs` `PrimitiveWrapper._internal_hitTest` (~2792), `convertPrimitiveHitResult` (~5009), `hoveredObjectId: param._internal_hoveredObject` (~12117), `requestUpdate: () => ..._internal_fullUpdate()` (~11651).

- Observation: same-millisecond fills (one order sweeping several levels) sorted by trade-id text gave the wrong "position after"; ordering now uses sign × startPosition, which moves monotonically with execution within a side.
  Evidence: model test `orders-same-millisecond-fills-by-execution-not-by-trade-id-text-test` (tids 9, 10, 11).

- Observation (browser QA): the trade page passes the chart a memoized SUBSET of state (`trade-chart-view-base-state-keys` in `views/trade_view.cljs`) that lacked `:chart-trades` and the wallet/spectate slices, so the chart silently fell back to the 30-row capped fills and drew 1 group where the model produced 21. Unit tests could not see this. Fixed by selecting `:chart-trades` and reading the history's own `:address` in the chart vm (the history is reset whenever the effective account changes).

- Observation (browser QA): `userFillsByTime` returns the OLDEST fills at or after `startTime` (max 2000), not the newest before `endTime`. Paging with `startTime 0` therefore jumped to the account's oldest retained fills and left a 5-hour hole (08:51 → 14:04 UTC on the test account). Replaced with time windows ending at the covered boundary, each paged forward until a short page, and an explicit `:covered-from-ms`. After the fix the same account loaded 10000 fills with no gap.

- Observation (browser QA): at 5m on a high-frequency account the 16–24 px badges piled up at ~6–7 px bar spacing; dots now take over below 12 px per bar, and dots got a light ring (they share the candle colors and were invisible on candles).

- Observation (browser QA): a marker pane view with zOrder "top" paints on the crosshair canvas, which keeps the last frame after `detachPrimitive` until the cursor moves (toggling the setting off left stale badges). Markers now use zOrder "normal" (main canvas, drawn after the series), and clearing empties the primitive before detaching so its repaint request clears the canvas. This also stops repainting markers on every mouse move.

- Observation: the theme CSS files are generated from `src/styles/themes/palette.js`; the new `--ho-chart-liquidation` token had to be added there (`CHART_TOKEN_KEYS` + each theme's `chart` map) and regenerated, not hand-edited.


## Decision Log

- Decision: Replace the setting key `:show-fill-markers?` (default off) with a new key `:show-chart-trades?` (default on), labelled "Trades on chart". Do not migrate the old value.
  Rationale: The user asked for the feature on with an off switch. Flipping the old key's default would not reach most users: `persist-trading-settings` saves the whole normalised map, so anyone who ever touched any trading setting already has an explicit `false` stored under the old key and cannot be told apart from someone who chose off. A new key gives everyone the new default once. Users who had explicitly turned the old markers on still end up on.
  Date/Author: 2026-10-22 / Claude (plan author)

- Decision: One master setting only. No separate sub-toggles for markers versus ribbon.
  Rationale: The user asked for "a setting" to turn the feature off. The design canvas showed per-layer chips, but they add three persisted keys and UI for an unproven need. Sub-toggles can be added later without breaking this key.
  Date/Author: 2026-10-22 / Claude

- Decision: Draw markers and the ribbon with a lightweight-charts series primitive (custom canvas drawing attached to the main series), not with built-in series markers and not with DOM overlays. The hover card stays DOM.
  Rationale: Built-in markers can now sit at a price, but they cannot draw the stem, the separate execution tick, the count bubble or the liquidation diamond, and they report no per-marker hover. DOM overlays (the pattern used for position and order lines) are fine for two or three rows. They would mean hundreds of absolutely positioned nodes repositioned on every pan and zoom frame. A primitive is painted by the chart in the same frame as the candles, so it pans, zooms and appears in screenshots for free, and `hitTest` gives hover and click identification. The card is rich text, which is easier and more accessible as DOM, and only one exists at a time.
  Date/Author: 2026-10-22 / Claude

- Decision: Snap each fill to the candle whose open time is the latest one at or before the fill time, using a binary search over the loaded candle times, instead of the existing epoch-floor `align-time-to-timeframe`.
  Rationale: Flooring from the Unix epoch does not match Hyperliquid's weekly and monthly candle opens, so markers would land on non-existent bars at `:1w` and `:1M`. Snapping to real candle times is always correct, and fills older than the oldest loaded candle are simply hidden until left-side backfill loads that range.
  Date/Author: 2026-10-22 / Claude

- Decision: The position ribbon covers perps (including HIP-3 dex perps) only. It is not drawn for spot assets.
  Rationale: Hyperliquid's `startPosition` on spot fills is a token balance, not a directional position, so "long/short" has no meaning there. Spot assets still get fill markers and the hover card.
  Date/Author: 2026-10-22 / Claude

- Decision: Keep the chart's fill history in a domain namespace (`hyperopen.account.chart-fill-history`) with its own minimal fill identity, instead of reusing the view-side `fill_fields` readers.
  Rationale: the REST projection and websocket handler must write it, and `lint:namespace-boundaries` forbids non-view namespaces importing `hyperopen.views.*`.
  Date/Author: 2026-10-22 / Claude

- Decision: Page older fills as time windows ending at `:covered-from-ms`, first window sized like the newest page's span (1 hour to 90 days), doubled after an empty window, each paged forward from the last returned time (≤ 5 requests); stop for the session on an error or when 10000 fills are held.
  Rationale: `userFillsByTime` returns the oldest 2000 at or after `startTime`, so only a bounded window paged forward yields contiguous history; coverage must be tracked separately from the oldest fill time because a window can be empty.
  Date/Author: 2026-10-22 / Claude

- Decision: Raise three existing namespace-size exceptions by the lines this feature added (`effect_order_contract.cljs` 755→761, `schema/contracts/action_args.cljs` 756→759, `core/public_actions.cljs` 503→506), each with an appended reason, following those entries' history; avoid touching `api/default.cljs` (the effect calls `api.default.orders` directly).
  Rationale: each file was exactly at its cap, and splitting them is the separate cleanup those entries already schedule.
  Date/Author: 2026-10-22 / Claude

- Decision: Retire the old arrow fill markers and the "L"/"S" entry circle when this lands.
  Rationale: The new markers replace both. The entry circle duplicates the opening fill badge and the entry-price line already marks the open position. Keeping them would put two symbols on the same candle.
  Date/Author: 2026-10-22 / Claude


## Outcomes & Retrospective

(2026-10-22, implementation complete pending the open Progress items.) Everything in Purpose shipped: badges at execution price merged per candle with counts and three sizes, dots when crowded, liquidation diamonds, the hover/pin card with Trade History link (tap-to-pin on touch), the perp position ribbon, a default-on "Trades on chart" setting with a toolbar toggle, and an uncapped chart fill history that pages back through older fills. Deferred: a Playwright spec for the card, multi-theme/spot/HIP-3/phone browser QA, and the full gate matrix record.

Complexity rose: about 1,100 lines of source across seven new namespaces (`fill_fields`, `chart_trades_model`, `trade_markers_primitive`, `trade_card`, `trade_layer`, `account.chart_fill_history`, its effects) plus one new action/effect pair, offset by deleting `build-fill-markers`, the entry marker and the positional decoration-pass signature. The rise is concentrated in self-contained, tested namespaces; the riskiest part is the history paging, whose behavior was confirmed against the live API.

Lesson: two of the three real defects (the chart's state subset and the `userFillsByTime` ordering) were invisible to unit tests and to two code reviews, and surfaced only in the browser against live data.


## Context and Orientation

Hyperopen is a ClojureScript single-page trading frontend for the Hyperliquid exchange. It is built with shadow-cljs and renders with Replicant. Replicant is a small virtual-DOM library: views are Clojure data in hiccup form (vectors like `[:div {:class "x"} "text"]`), and `:replicant/on-render` attributes run a function when a DOM node is mounted, updated or unmounted. All application state lives in one atom called the store. Views receive `state` and derive a "view model" (`vm`) from it. User intents are dispatched as action vectors such as `[:actions/set-fill-markers-enabled true]`. Actions return effects such as `[:effects/save [:trading-settings] s]` or `[:effects/local-storage-set-json key value]`, which the runtime executes.

The price chart uses the `lightweight-charts` npm package, version 5.0.8, abbreviated LWC. Three LWC terms matter here:

- A **series** is one drawn data set. The main series is the candles; it maps a price to a pixel `y` with `series.priceToCoordinate(price)`.
- The **time scale** maps a time to a pixel `x` with `chart.timeScale().timeToCoordinate(time)`. LWC time values in this app are Unix seconds.
- A **series primitive** is an object you attach with `series.attachPrimitive(obj)`. LWC calls its `paneViews()` method every frame to get renderers. Each renderer's `draw(target)` paints onto the chart's canvas, and its optional `hitTest(x, y)` returns `{externalId, zOrder, cursorStyle}` when the mouse is over something it drew. LWC then reports that `externalId` as `param.hoveredObjectId` in `chart.subscribeCrosshairMove` and `chart.subscribeClick` callbacks. `attached({chart, series, requestUpdate})` is called once on attach and gives you `requestUpdate()` to trigger a repaint after data changes.

The chart code lives under `src/hyperopen/views/trading_chart/`:

- `core.cljs` `trading-chart-view` builds the chart view model through `vm.cljs` `chart-view-model`. It then renders `chart-canvas`, a div whose `:replicant/on-render` is `runtime/chart-canvas-on-render`. The div's `:replicant/key` includes the symbol and timeframe, so changing either fully remounts the chart.
- `runtime.cljs` owns the lifecycle. `mount-chart!` creates the chart. `update-chart!` pushes new data and, on a chart-type change, swaps the main series in place (`swap-main-series!`). `unmount-chart!` clears overlays and calls `chart.remove()`. After mount and update, `schedule-chart-decoration-pass!` runs `apply-chart-decorations!` in a `requestAnimationFrame`. That pass calls `ci/set-main-series-markers!`, `sync-position-overlays!`, `sync-open-order-overlays!` and other overlay syncs. Per-chart mutable state for overlays is kept in module-level `WeakMap`s keyed by the chart object (see `utils/chart_interop/markers.cljs` line 4 and `utils/chart_interop/position_overlays/support.cljs` line 4).
- `utils/chart_interop.cljs` creates the chart (`create-chart-with-volume-and-series!`, `create-chart-with-indicators!`) and returns a handle `#js {:chart :mainSeries :volumeSeries ...}`. Public interop functions validate inputs with `schema/chart_interop_contracts.cljs` (`assert-chart-handle!`, `assert-candles!`).
- `utils/position_overlay_model.cljs` is pure code that turns raw fills into chart data. It contains fill field readers that tolerate several field spellings (`fill-price`, `fill-size`, `fill-time`, `fill-side`, `fill-coin`), `asset-fill-match?` (decides whether a fill belongs to the active asset, handling perp names like `HYPE`, spot ids like `@107`, pair names like `PURR/USDC` and HIP-3 names like `xyz:HO`), `fill-identity` (dedupe key: `:tid`, else `:id`, else `:oid`, else a tuple), `build-fill-markers` (today's arrows), and `build-position-overlay` (entry line, liquidation line, entry circle).
- `utils/data_processing.cljs` `process-candle-data` converts raw candles `{:t :o :h :l :c :v}` into `{:time <unix seconds> :open :high :low :close :volume}`. `vm.cljs` exposes the memoised result as `candle-data`.
- `utils/theme_colors.cljs` `token` reads CSS custom properties such as `--ho-chart-long` and `--ho-chart-short`, defined in `src/styles/themes/dark.css`, `institutional.css` and `hyperdegen.css`. `chart_interop.cljs` `restyle-chart-theme!` reruns when the `data-theme` attribute changes. The lint `npm run lint:theme-colors` rejects hard-coded colors in chart code, so all colors must come from tokens.
- Hover patterns to copy: `utils/chart_interop/legend.cljs` lines 328-333 and `utils/chart_interop/volume_indicator_overlay.cljs` lines 411-433 subscribe to crosshair moves and position DOM.

Fill data. A **fill** is one execution of an order. Hyperliquid returns fills as maps with `:coin`, `:px` (price string), `:sz` (size string), `:side` (`"B"` buy or `"A"` sell), `:time` (milliseconds), `:dir` (for example `"Open Long"` or `"Close Short"`), `:startPosition` (signed position size before this fill), `:closedPnl`, `:fee`, `:oid`, `:tid`, `:hash`, `:crossed` (true means taker), and `:liquidation` (present only on liquidation fills). They arrive in two ways:

- **REST.** `src/hyperopen/api/endpoints/orders.cljs` `request-user-fills!` posts `{"type":"userFills","user":<address>,"aggregateByTime":true}` and gets at most the newest 2000 fills. The result lands in `[:orders :fills]` through `api/projections/orders.cljs` `apply-user-fills-success`.
- **WebSocket.** The `userFills` subscription is handled in `websocket/user_runtime/handlers.cljs` lines 93-108. A snapshot replaces `[:orders :fills]`; later messages are deduplicated and prepended, and the vector is truncated to 200 rows.

The chart reads fills through `vm.cljs` `chart-fills` (prefers `[:orders :fills]`, falls back to `[:webdata2 :fills]`). The Trade History tab (`views/account_info/tabs/trade_history.cljs`) reads the same source. The address is `account-context/effective-account-address`, which also covers spectate mode (viewing someone else's address). Hyperliquid also offers `{"type":"userFillsByTime","user":..., "startTime":ms, "endTime":ms, "aggregateByTime":true}`, which returns up to 2000 fills in a window, and only the newest 10000 fills per user are retrievable at all. It is not used in the repo yet.

Settings. `src/hyperopen/trading_settings.cljs` holds the trading settings map, stored in localStorage under `"hyperopen:trading-settings:v1"`. It defines `default-state`, `normalize-state` (fills in defaults; `true?` means default off, `(not (false? ...))` means default on), `restore-state`, and one selector per key, for example `show-fill-markers?`. To add a boolean setting, change every one of these places, using `:show-fill-markers?` as the template:

- `src/hyperopen/trading_settings.cljs`: default, normalise, selector.
- `src/hyperopen/header/actions.cljs` around line 123 (`set-fill-markers-enabled`, which calls `persist-trading-settings`).
- `src/hyperopen/runtime/collaborators/spectate_mode.cljs` line 22 (action deps map).
- `src/hyperopen/schema/contracts/action_args.cljs` line 221 (argument spec, `::common/boolean-args`).
- `src/hyperopen/schema/runtime_registration/wallet.cljs` line 28 (action registration).
- `src/hyperopen/views/header/vm.cljs` lines 395-402 (`settings-row :fill-markers ...` in the `:display` section; `settings-row` helper at line 205; icon `:marker` from `views/header/icons.cljs` line 166).
- Startup restore in `src/hyperopen/startup/restore.cljs` line 39 and defaults in `src/hyperopen/state/app_defaults.cljs` lines 107 and 476.
- Chart consumption in `vm.cljs` line 203.

Repository rules that constrain this work: namespaces may not exceed 500 lines (`dev/check_namespace_sizes.clj`; exceptions in `dev/namespace_size_exceptions.edn`), so new code goes in new namespaces. Tests are `cljs.test` files named `test/hyperopen/**/*_test.cljs` and are discovered automatically. Overlay tests use the fake DOM in `test/hyperopen/views/trading_chart/test_support/fake_dom.cljs` and fake series in `test_support/series.cljs`.


## Plan of Work

The work is split into eight milestones. Each one leaves the app working and its tests passing.

### Milestone 0 — Prototype the primitive (prototyping; keep or discard)

Goal: prove, before building on it, that an LWC 5.0.8 series primitive written in ClojureScript can paint on the main series, receive `hitTest` hover and click identification, and survive the in-place main-series swap that happens on chart-type change.

Create `src/hyperopen/views/trading_chart/utils/chart_interop/trade_markers_primitive.cljs` with a minimal primitive. It is a JS object built with `#js {...}` that has:

- `attached` storing `requestUpdate`,
- `detached`,
- `updateAllViews` (no-op),
- `paneViews` returning one view whose `renderer()` returns `#js {:draw (fn [target] ...)}`.

In `draw`, call `(.useBitmapCoordinateSpace target (fn [scope] ...))` and draw one filled circle at the last candle's close using `(.-context scope)`. Multiply CSS pixel coordinates by `(.-horizontalPixelRatio scope)` and `(.-verticalPixelRatio scope)`. Add `hitTest` on the pane view returning `#js {:externalId "probe" :zOrder "top" :cursorStyle "pointer"}` when within 10 px of the circle. Temporarily attach it in `runtime.cljs` `mount-chart!` behind a `goog-define` or local `def` flag. Log `param.hoveredObjectId` from a `subscribeCrosshairMove` handler.

Run the app, hover the circle, and confirm: the cursor becomes a pointer, the log shows `"probe"`, the circle pans and zooms with the candles, and it still shows after switching chart type (or re-attach it in `swap-main-series!` if it does not). Record the outcome in Surprises & Discoveries. Promote the namespace to Milestone 3 if this works. If `hitTest` does not fire, fall back to hit-testing in the crosshair callback using the model's pixel positions. Note that in the Decision Log.

### Milestone 1 — The setting

Goal: "Trades on chart" exists, defaults on, persists, and gates the existing fill markers, so the feature is controllable before any new drawing exists.

In `src/hyperopen/trading_settings.cljs`, remove `:show-fill-markers?` from `default-state`, `normalize-state` and the selector. Add `:show-chart-trades? true` to `default-state`, `:show-chart-trades? (not (false? (:show-chart-trades? settings)))` to `normalize-state`, and a selector `show-chart-trades?`.

Rename the action `set-fill-markers-enabled` to `set-chart-trades-enabled` (persisting `:show-chart-trades?`) in `header/actions.cljs`, and update `spectate_mode.cljs`, `action_args.cljs` and `runtime_registration/wallet.cljs` to the new action keyword `:actions/set-chart-trades-enabled`.

In `views/header/vm.cljs`, change the row to `(settings-row :chart-trades "Trades on chart" "Show your buys, sells and position history on the price chart." (trading-settings/show-chart-trades? state) :marker [[:actions/set-chart-trades-enabled (not (trading-settings/show-chart-trades? state))]] :tooltip "Markers at your fill prices, a details card on hover, and a long/short ribbon.")`. The row's `data-role` becomes `trading-settings-chart-trades-row`.

Add the quick toggle to the chart's top menu. `core.cljs` delegates `chart-top-menu` to a `toolbar` namespace; open the namespace named in `core.cljs`'s `:require` for `toolbar`. Add a button labelled "My trades" with `aria-pressed` bound to the selector and an `:on {:click [[:actions/set-chart-trades-enabled (not on?)]]}`, styled like the neighbouring toolbar buttons, with `data-role "chart-toolbar-trades-toggle"`. Both entry points dispatch the same action, so they always agree.

In `vm.cljs`, replace the `show-fill-markers?` binding with `show-chart-trades?` from the new selector and thread it to the same places. Update every test listed in Context and Orientation that names the old key, action or row id. Search with `rg -n "fill-markers|show-fill-markers|set-fill-markers-enabled" src test tools` until only `build-fill-markers` and its own tests remain.

Acceptance: `npm test` passes. In the browser, a fresh profile shows the old arrow markers by default. Unchecking "Trades on chart" in Settings, or clicking "My trades" in the chart toolbar, hides them, and the other control reflects the change. Reloading keeps the choice.

### Milestone 2 — Pure model

Goal: one tested, pure function turns fills plus candles into everything the renderer and card need.

Create `src/hyperopen/views/trading_chart/utils/chart_trades_model.cljs`. Reuse the field readers, `asset-fill-match?` and `fill-identity` from `position_overlay_model.cljs`; make any you need public there rather than duplicating them. Define:

    (defn build-chart-trades
      [{:keys [active-asset fills market-by-key candle-data perp?]}]
      ;; => {:groups [group ...] :ribbon [segment ...] :median-notional n}
      ...)

The steps:

- Filter fills to the active asset with `asset-fill-match?`, and deduplicate by `fill-identity`.
- For each fill, find its candle by binary search on `candle-data` `:time` (seconds): the last candle whose time is at or before `(quot time-ms 1000)`. Drop fills before the first candle.
- Group by `[candle-time side-kind]`, where `side-kind` is `:buy`, `:sell`, or `:liquidation` (a fill with a non-nil `:liquidation`).
- For each group produce a map:
  - `:id`: a string, `(str candle-time "-" (name side-kind))`, used as the primitive's `externalId`.
  - `:time` (candle seconds), `:side-kind`.
  - `:count`, `:size` (sum of absolute sizes).
  - `:avg-price` (sum of price×size divided by size).
  - `:notional` (sum of price×size), `:fees` (sum of `:fee`), `:closed-pnl` (sum of `:closedPnl`).
  - `:position-after`: `:startPosition` of the latest fill plus its signed size.
  - `:candle-high` and `:candle-low` (copied from the candle, so the renderer can place badges outside the wick).
  - `:fills`: the underlying fills, each as `{:time-ms :size :price :maker?}`, where `:maker?` is `(not (:crossed fill))`, sorted by time.
- Compute `:median-notional` over all groups, and set each group's `:size-step` to `:small` if its notional is under 0.5× the median, `:large` if over 2×, and `:typical` otherwise.

For the ribbon, when `perp?` is true, walk the asset's fills in time order. After each fill, the position is `startPosition + signed size` (buy positive, sell negative). Emit segments `{:from-time :to-time :direction (:long|:short) :size}`: each runs from that fill's candle time to the next fill's candle time, or to the last candle time for the final fill, and is skipped when the position is zero. Before the earliest fill, the position is that fill's `startPosition`. If that is non-zero, emit a segment from the first candle time to the first fill's candle time.

Create `test/hyperopen/views/trading_chart/utils/chart_trades_model_test.cljs` covering:

- snapping a fill exactly on a candle open and one mid-candle;
- dropping fills older than the first candle;
- merging three same-side fills into one group with the correct weighted average;
- a buy and a sell in one candle staying as two groups;
- a liquidation fill getting `:liquidation`;
- size steps around the median;
- duplicate `:tid` deduplication;
- a spot coin (`@107`) matching its pair;
- ribbon segments for long → flat → short → flat, with a non-zero starting position;
- no ribbon when `perp?` is false.

Acceptance: `npm test` shows the new tests passing. Nothing visible changes yet.

### Milestone 3 — Draw the markers

Goal: the new markers replace the arrows on screen.

Grow `trade_markers_primitive.cljs` into the real renderer. Expose:

    (defn create-trade-markers-primitive [] ...)          ;; returns the JS primitive object
    (defn set-trade-groups! [primitive groups opts] ...)  ;; opts: {:selected-id :colors}

The renderer keeps the latest groups in a closure atom and calls `requestUpdate` when they change. In `draw`, for each group whose `x = timeScale.timeToCoordinate(time)` is non-nil:

- Compute `yTick = series.priceToCoordinate(avg-price)`.
- Badge diameter is 16, 20 or 24 CSS px for `:small`, `:typical`, `:large`.
- For buys and liquidations, the badge's top edge sits at `priceToCoordinate(candle-low) + 8`. For sells, its bottom edge sits at `priceToCoordinate(candle-high) - 8`.
- Draw a 14 px wide, 2 px tall execution tick centred on `x` at `yTick`, with a 1 px outline in the chart background color.
- Draw a 1 px stem between the tick and the badge at 75% opacity.
- Draw the badge as a filled circle in the long color with "B" for buys and the short color with "S" for sells. Liquidations are an amber rotated square with "L". Use the app's UI font at 9, 10 or 11 px bold, and dark text (the chart background color) on the badge.
- If `:count` > 1, draw a 14 px count bubble at the badge's top-right: background color fill, 1 px ring in the side color, count text in the side color.
- The group whose `:id` equals `:selected-id` gets a 2 px ring in its side color, 2 px outside the badge, plus a dotted horizontal guide from the tick to the right edge of the pane.

Density rule: compute `barSpacing` from `chart.timeScale().options().barSpacing`. When it is under 6 CSS px, or more than 60 groups are visible, draw only a 6 px dot at the tick (no stem, letter or bubble).

`hitTest` checks the badge circles, or the dots in dense mode, with a minimum hit radius of 16 CSS px (22 px when `(.-maxTouchPoints js/navigator)` > 0). It returns `#js {:externalId id :zOrder "top" :cursorStyle "pointer"}`.

Colors: read through `theme_colors/token`. Long and short come from `--ho-chart-long` and `--ho-chart-short`. Add a new token `--ho-chart-liquidation` (amber) to all three theme files. The bubble and tick outline use the existing chart background token. Read colors in `set-trade-groups!` (passed via `opts`) so `restyle-chart-theme!` can refresh them by calling it again.

Wiring:

- In `runtime.cljs` `mount-chart!`, create the primitive and attach it to the main series. Store it in the per-chart runtime state (`runtime_state.cljs`). Re-attach it to the new series in `swap-main-series!`, and detach it in `unmount-chart!`.
- In `apply-chart-decorations!`, call `set-trade-groups!` with `(:trade-groups chart-runtime-options)` when the setting is on, else with `[]`.
- In `vm.cljs`, add a memoised `build-chart-trades` call, keyed on active asset, fills, market-by-key and candle-data, gated by `show-chart-trades?`. Pass `:trade-groups`, `:trade-ribbon` and the groups-by-id map through `chart-runtime-options`. `perp?` comes from the active market (`(not= :spot (:market-type active-market))` or the existing spot check in `asset-fill-match?`'s helpers).
- In `core.cljs`, stop merging fill markers and the entry marker into `memoized-main-series-markers`; only indicator markers remain.
- Delete `build-fill-markers`, `:fill-markers` and `:entry-marker` from `position_overlay_model.cljs`, along with their tests and the `memoized-fill-markers` code in `vm.cljs`.

Tests: `test/hyperopen/views/trading_chart/utils/chart_interop/trade_markers_primitive_test.cljs`. Build a fake bitmap scope whose `context` records calls (`arc`, `fillRect`, `fillText`) and fake chart/series objects whose `timeToCoordinate` and `priceToCoordinate` are linear functions. Assert that one buy group draws a circle below the low with text "B", a sell draws above the high, a count of 3 draws text "3", and dense mode draws no text. Assert that `hitTest` returns the id inside the radius and nil outside. Extend `core_test.cljs` and `runtime_test.cljs` for the new option threading and the attach/re-attach/detach calls.

Acceptance: on HYPE 1D with history, badges sit below and above candles with ticks at fill prices, multi-fill candles show a count, and zooming far out turns them into dots. The old arrows and the "L"/"S" circle are gone. Turning the setting off removes everything.

### Milestone 4 — Hover and pinned card

Goal: hovering or clicking a badge shows the details card.

Create `src/hyperopen/views/trading_chart/utils/chart_interop/trade_tooltip_overlay.cljs`, modelled on `volume_indicator_overlay.cljs`:

- On mount, create one absolutely positioned card element inside the chart container, hidden by default, with `role "dialog"` and `aria-label "Fill details"`.
- Subscribe to `chart.subscribeCrosshairMove` and `chart.subscribeClick`. When `param.hoveredObjectId` names a group, look it up in the groups-by-id map supplied by the decoration pass and render it. Hide on move-away unless pinned. A click on a group pins it (and sets `:selected-id` on the primitive); a click on empty space or Escape unpins.
- Position the card 24 px right of the badge. Flip it to the left when that would overflow the container. Clamp it vertically inside the pane.

Card content, top to bottom:

- side badge and title ("Bought 60 HYPE", "Sold 120 HYPE", "Liquidated 40 HYPE"), with the candle date on the right;
- a three-column row: Avg price, Notional, Fees;
- a small table of fills (time in UTC, size, price, Maker/Taker);
- "Position after" (for example "Long 120 HYPE");
- "Realized PnL", signed and colored, only when the closed PnL is non-zero;
- a link "Open in Trade History →" that dispatches `[:actions/select-account-info-tab :trade-history]`.

Before wiring, check the exact tab argument shape against `src/hyperopen/schema/contracts/action_args.cljs` `::common/tab-args`. Format prices with the chart's existing price formatter (`utils/chart_interop/price_format.cljs`) and money with `hyperopen.utils.formatting`. Build the card's DOM with the same helper style the position-overlay rows use (`position_overlays/rows.cljs`), and only replace its contents when the hovered id changes.

Tests with the fake DOM: hovering an id shows the card with the right title and N fill rows; moving away hides it; a click pins it; Escape unpins it; clicking the link dispatches the tab action.

Acceptance: in the browser, hovering any badge opens the card next to it, clicking pins it, and the link switches the bottom panel to Trade History.

### Milestone 5 — Position ribbon

Goal: the long/short strip along the bottom of the price pane.

Add a second pane view to the primitive with `zOrder "bottom"` so candles paint over it. For each `:trade-ribbon` segment, draw a rectangle from `timeToCoordinate(from-time)` to `timeToCoordinate(to-time)`, clamped to the visible range: 6 CSS px tall, flush with the bottom of the pane, filled with the long or short color at 25% alpha, with a 2 px top edge at full color. Draw nothing for spot.

Add a ribbon test to the primitive test file, and extend the model test if segment edge cases appear.

Acceptance: on a perp with history, the strip matches the periods the trader held long or short, as confirmed against the Trade History "Direction" column. It is absent on a spot pair.

### Milestone 6 — Deeper and stable fill history

Goal: markers do not disappear after a live fill, and older trades appear when the trader scrolls back.

Add a dedicated store slice `[:chart-trades :by-address <address>]` holding `{:fills [...] :oldest-ms n :exhausted? bool :loading? bool}`. Do not change `[:orders :fills]` or its 200-row cap; Trade History behaviour is out of scope.

Seed the slice whenever the REST `userFills` result lands (`apply-user-fills-success`) or a websocket snapshot arrives. Merge live websocket fills into it with `fill-identity` deduplication and no cap.

Add `request-user-fills-by-time!` to `api/endpoints/orders.cljs`, posting `{"type":"userFillsByTime","user":addr,"startTime":0,"endTime":<oldest-ms - 1>,"aggregateByTime":true}`. Give it its own request policy entry in `api/request_policy.cljs`. Add an effect `:effects/fetch-chart-trade-history` that requests it, appends results, sets `:oldest-ms`, and sets `:exhausted?` when fewer than 2000 rows return or 10000 total have been loaded.

Trigger the effect from the chart's existing left-side backfill path (`runtime.cljs` around line 101, which fetches older candles). When the oldest loaded candle is older than `:oldest-ms` and the slice is not exhausted or loading, fetch more fills. Point `vm.cljs` `chart-fills` at the new slice for the effective address, falling back to today's source when the slice is empty. Clear the slice on account switch in `startup/runtime/account_state.cljs`.

Per `docs/BROWSER_STORAGE.md`, this cache is in memory only. Do not persist it.

Tests:

- a projection test that 250 seeded fills plus one live fill keeps 251;
- an effect test that a short page sets `:exhausted?`;
- a runtime test that scrolling left past `:oldest-ms` emits the fetch once.

Acceptance: place a small live trade and the older markers stay. Scroll back on an account with more than 2000 fills, and older markers appear as candles backfill.

### Milestone 7 — End-to-end validation and close-out

Update `tools/playwright/test/trade-regressions.spec.mjs` near lines 4002 and 4045, where the old fill-marker setting is exercised. Use the new row id `trading-settings-chart-trades-row`, assert it defaults on, and toggle it off and back on via both the Settings row and `chart-toolbar-trades-toggle`. Canvas drawing cannot be asserted from the DOM, so assert behaviour through the card: with a mocked fills fixture, move the mouse to a badge position computed from the fixture, then expect `[role=dialog][aria-label="Fill details"]` with the expected title.

Do browser QA per `docs/BROWSER_TESTING.md` in the dark, institutional and hyperdegen themes at 1D, 1h and 1W, on one perp, one spot pair and one HIP-3 market, and at a phone-width viewport (tap a badge to open the card). Record results in Artifacts and Notes, fill in Outcomes & Retrospective, and move this file to `docs/exec-plans/completed/`.


## Concrete Steps

All commands run from the repository root, `/Users/barry/projects/hyperopen` (or a fresh worktree of it).

In a fresh worktree, first run:

    npm run setup:worktree

While developing, run the unit tests often:

    npm test

Expect the summary to end with `0 failures, 0 errors.` New test namespaces are picked up automatically by `tools/generate-test-runner.mjs`.

After each milestone, run the full gates:

    npm run check
    npm test
    npm run test:websocket

or all three with a pass/fail matrix:

    npm run gates

`npm run check` includes the namespace-size, namespace-boundary, theme-color and docs lints. A failure from `lint:namespace-sizes` means a namespace passed 500 lines; split it rather than adding an exception. A failure from `lint:theme-colors` means a hard-coded color slipped into chart code; use a `--ho-chart-*` token.

Focused Playwright run for Milestone 7:

    npx playwright test tools/playwright/test/trade-regressions.spec.mjs -g "trades on chart"

Leftover-reference search for Milestone 1:

    rg -n "show-fill-markers|set-fill-markers-enabled|fill-markers-row" src test tools

Expect no output once Milestone 3 has also removed `build-fill-markers`.


## Validation and Acceptance

The feature is accepted when all of the following hold in a running app (`npm run dev`, or the repo's usual dev command), connected to or spectating an address with HYPE perp history:

1. With a fresh browser profile, opening HYPE 1D shows round B badges below the candles and S badges above them. Each has a short tick on the candle at the fill price. A candle where three buys happened shows one B badge with a "3" bubble.
2. Hovering a badge shows the "Fill details" card with the correct count of fill rows, and the average price equals the size-weighted average shown in Trade History for those rows. Clicking pins it; Escape closes it; "Open in Trade History" switches the bottom panel tab.
3. A thin strip along the bottom of the price pane is long-colored during long periods, short-colored during short periods, and empty while flat. It is absent on a spot pair.
4. A liquidation fill, if the account has one, shows an amber "L" diamond.
5. Zooming far out turns badges into small dots. Zooming in restores them.
6. Turning off "Trades on chart" in Settings, or clicking "My trades" in the chart toolbar, removes markers, card and ribbon immediately, and the other control shows off. After a reload it stays off. The open-position entry/PnL line and the liquidation line remain visible either way.
7. `npm run gates` reports PASS for check, test and websocket tests. The new tests `chart_trades_model_test`, `trade_markers_primitive_test` and `trade_tooltip_overlay_test` exist and pass. The updated settings tests fail if the default is changed back to off.


## Idempotence and Recovery

Every step is additive or a rename covered by tests, so re-running a milestone's edits and tests is safe. The setting change is forward-only by design: the old localStorage key `:show-fill-markers?` is ignored, not deleted, so rolling back the code restores the old behaviour with the user's old value intact. If the primitive approach fails in Milestone 0, stop and record the failure. The fallback (built-in `atPriceTop`/`atPriceBottom` series markers with `size` set from `:size-step`, plus crosshair-distance hover) still meets acceptance items 1, 2, 5 and 6, minus the stem and count bubble; record the gap in the Decision Log. Milestone 6 adds a new store slice and a new request, and touches nothing existing, so it can be reverted alone.


## Artifacts and Notes

Fill in during implementation with short evidence: test summary lines, a note of which accounts and markets were used for browser QA, and screenshot file paths if captured.


## Interfaces and Dependencies

No new npm dependencies. `lightweight-charts` 5.0.8 is already installed and provides `ISeriesPrimitive`, `attachPrimitive`, `detachPrimitive`, `hitTest`, `subscribeCrosshairMove` and `subscribeClick`.

At the end of the plan these must exist:

In `src/hyperopen/trading_settings.cljs`:

    (defn show-chart-trades? [state]) ;; => boolean, default true

In `src/hyperopen/header/actions.cljs`:

    (defn set-chart-trades-enabled [state enabled?]) ;; => effects persisting :show-chart-trades?

In `src/hyperopen/views/trading_chart/utils/chart_trades_model.cljs`:

    (defn build-chart-trades
      [{:keys [active-asset fills market-by-key candle-data perp?]}])
    ;; => {:groups [{:id "1729468800-buy" :time 1729468800 :side-kind :buy
    ;;               :count 3 :size 60 :avg-price 86.94 :notional 5216.4
    ;;               :fees 1.43 :closed-pnl 0 :position-after 120
    ;;               :candle-high 88.1 :candle-low 85.2 :size-step :typical
    ;;               :fills [{:time-ms ... :size 20 :price 86.90 :maker? true} ...]}]
    ;;     :ribbon [{:from-time ... :to-time ... :direction :long :size 120}]
    ;;     :median-notional 3600.0}

In `src/hyperopen/views/trading_chart/utils/chart_interop/trade_markers_primitive.cljs`:

    (defn create-trade-markers-primitive [])
    (defn set-trade-groups! [primitive groups {:keys [selected-id ribbon colors]}])

In `src/hyperopen/views/trading_chart/utils/chart_interop/trade_tooltip_overlay.cljs`:

    (defn sync-trade-tooltip! [chart-obj container {:keys [groups-by-id dispatch!]}])
    (defn clear-trade-tooltip! [chart-obj])

In `src/hyperopen/api/endpoints/orders.cljs` (Milestone 6):

    (defn request-user-fills-by-time! [deps address {:keys [start-ms end-ms]}])

New theme token `--ho-chart-liquidation` in `src/styles/themes/dark.css`, `institutional.css` and `hyperdegen.css`.

---

Change note (2026-10-22): Initial version, written from the user's request and the approved "Fills on chart" design. It is based on a read of the chart, fills and settings code at main `540a077c5`.

Change note (2026-10-22, later): implementation recorded — Progress, Surprises & Discoveries (Milestone 0 findings and three defects found in browser QA), Decision Log (history namespace placement, windowed paging, size-exception bumps) and Outcomes updated.

Change note (2026-10-22, final): recorded the final review fixes, the Playwright results (with the main-baseline comparison) and the gate matrix.
