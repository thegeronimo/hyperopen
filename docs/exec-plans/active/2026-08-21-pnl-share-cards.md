# Let a trader post a position as a card, from the PNL cell, the way hl.xyz does

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It must be maintained in accordance with `/hyperopen/.agents/PLANS.md` (the full ExecPlan writing contract) and the public planning entry point `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

On app.hyperliquid.xyz, every row of the Positions table has a small green glyph tucked in behind the PNL (ROE %) figure. Clicking anywhere in that cell opens a modal holding a rendered card of the position — coin, side, leverage, an enormous return figure, entry and mark price, and the trader's referral link — with three buttons underneath: save the image, copy the link, post it on X. Traders use it constantly; it is the single most-screenshotted surface in perps trading, and it is how Hyperliquid's referral programme actually spreads. Hyperopen has no equivalent. A trader who wants to show a position has to take an operating-system screenshot of a dense, unflattering table row.

After this plan, the same affordance exists on hyperopen. The PNL (ROE %) cell in the Positions table — desktop table and mobile card alike — becomes a button carrying a small accent-coloured share glyph. Pressing it opens a share modal whose left half is a live 1080x608 card and whose right half lets the trader pick between two card designs, choose which optional fields appear, and write a caption. Save PNG downloads a real 2160x1216 PNG (on a phone it opens the native share sheet instead). Copy link copies the trader's own `/join/<code>` referral URL, or the plain site URL when they have no code yet. Post it on X opens a prefilled compose window carrying the caption and the link.

You can see it working without any special tooling: open the trade page with a wallet that has an open position, look at the Positions tab, click the PNL cell of any row, and a card appears. Switch the template radio from Neon arrow to Number as hero and the artwork changes while the numbers stay identical. Press Save PNG and a file named like `hyperopen-SOL-long-2026-08-21.png` lands in the downloads folder, 2160 pixels wide, with the numerals rendered in JetBrains Mono rather than a fallback face. Open a losing position instead and the card's arrow flips downward, the palette turns crimson, and the label under the number reads differently — the loss is not disguised.

## Context References

Public refs:
- Direct user request, 2026-08-21: "on hl.xyz you can post your pnl cards, we need to implement something similar. Use these design guidelines as well as study how hl.xyz implements it and create an execution plan then implement it."
- Claude Design project "Hyperopen P/L card variations", `73dc9f76-0418-42f7-bf6b-56d3c61575e9`, file `PnL Share Cards.dc.html`. Eight card directions across two turns, plus a live share-modal mock. Read through the `DesignSync` MCP tool.
- Hyperliquid's production implementation, read directly from its deployed bundle: `https://app.hyperliquid.xyz/assets/ShareModal-DRQ6yB96.js` and `https://app.hyperliquid.xyz/assets/font-WdlKaabY.js`. These are the parity source of truth for behaviour; they are minified third-party JavaScript and are cited as evidence, never copied.

Repo artifacts:
- `/hyperopen/docs/exec-plans/completed/2026-08-21-account-tab-lazy-module-repaint.md` — the lazy-module and memoized-slice machinery this plan reuses and must not re-break.
- `/hyperopen/docs/agent-guides/trading-ui-policy.md` — the honesty rules that govern every number printed on a shareable card.
- `/hyperopen/docs/BROWSER_TESTING.md` — the Playwright-versus-Browser-MCP routing this plan's QA follows.
- `/hyperopen/docs/THEMING.md` and `/hyperopen/src/styles/themes/palette.js` — the token vocabulary the card resolves against.

Local scratch refs (non-authoritative):
- Scratchpad copies of the decoded design file and the six repo-research reports under the session scratchpad directory. Non-authoritative; everything load-bearing has been restated in this plan.

## Progress

- [x] (2026-08-21 15:40Z) Read the design project end to end: eight directions, the 1e share-modal mock and its `ratio` / `overlay` / field-toggle / caption state.
- [x] (2026-08-21 15:52Z) Recovered Hyperliquid's real implementation from its production bundle: the card is an inline `<svg>`, rasterised by `XMLSerializer` into a `data:image/svg+xml,` URL, drawn into a canvas at 2x, with fonts base64-inlined as `@font-face` inside `<defs><style>`.
- [x] (2026-08-21 15:55Z) Confirmed the entry point and the exact payload: the whole PNL (ROE %) cell is the button, and the modal receives only `{coin, entryPx, markPx, returnOnEquity, side, leverage, address}`.
- [x] (2026-08-21 16:05Z) Mapped every hyperopen surface the feature touches: `positions-vm/position-row-vm`, the two positions tables, the lazy surface-module registry, the five drift-checked contract surfaces, and the six style gates.
- [x] (2026-08-21 16:12Z) Locked the three shaping decisions with the requester: two templates behind a picker, JetBrains Mono, Positions table only.
- [x] (2026-08-21 16:20Z) Milestone 0 — committed `tools/playwright/test/pnl-share-card-export.spec.mjs` and confirmed it red with a missing-function error, not a wrong-pixel one.
- [x] (2026-08-21 16:26Z) Milestone 1 — `views/pnl_share/card_data.cljs` plus 16 tests covering wins, losses, shorts, named dexes, missing fills and missing referral codes.
- [x] (2026-08-21 16:24Z) Milestone 2 — `views/pnl_share/palette.cljs` resolves `--ho-*` to concrete colours with dark-theme fallbacks for the node suite.
- [x] (2026-08-21 16:30Z) Milestone 3 — both templates as pure SVG hiccup (`neon_arrow`, `number_hero`) with shared primitives in `svg.cljs`.
- [x] (2026-08-21 16:33Z) Milestone 4 — share modal, module shim, surface registry, both shadow builds, app-view mount, `:pnl-share` default state.
- [x] (2026-08-21 16:32Z) Milestone 5 — six actions and two effects across all five contract surfaces; no effect-order policy needed because none is declared for this area.
- [x] (2026-08-21 16:34Z) Milestone 6 — the PNL cell is the trigger in both the desktop table and the mobile card, hidden in read-only mode.
- [x] (2026-08-21 16:56Z) Milestone 7 — eight Playwright tests green; visual review of four card variants and the modal at 375, 768, 1280 and 1440.
- [x] (2026-08-21 16:52Z) Milestone 8 — `npm run gates` reports 34/34 PASS, 6772 tests, 36542 assertions.
- [ ] Formal browser-QA sweep per `docs/agent-guides/browser-qa.md` — the functional and layout passes are done at all four widths; the remaining passes (keyboard-only traversal, screen-reader labelling, reduced-motion, high-contrast) are not yet recorded PASS/FAIL/BLOCKED.
- [ ] Move this plan to `docs/exec-plans/completed/` once that sweep is recorded.

## Surprises & Discoveries

- Observation: Hyperliquid's card carries far less data than the design assumes.
  Evidence: the lazy component is instantiated at `config-Dq8XJCAJ.js` with exactly `{onClose, coin, entryPx, markPx, returnOnEquity, side, leverage, address}`. There is no size, no held duration, no funding, no realised PnL and no timestamp anywhere on the card. Everything beyond those six fields in the design is hyperopen going further than the parity target, and each extra field has to earn its place against the honesty rules.

- Observation: Hyperliquid saves a file named `image.jpg` that is actually a PNG.
  Evidence: their rasterizer calls `canvas.toDataURL("image/jpg", 1)`. `image/jpg` is not a registered MIME type, so every browser silently falls back to `image/png`; the separate `toBlob(..., "image/jpeg")` path used for the mobile share sheet does produce a real JPEG. We will not reproduce the bug — this plan emits `image/png` and names the file `.png`.

- Observation: the coin icons hyperopen renders in tables cannot appear on the card.
  Evidence: `src/hyperopen/views/asset_icon.cljs:4` sources icons from `https://app.hyperliquid.xyz/coins/`, and that host returns no `Access-Control-Allow-Origin` header. Drawing one into a canvas taints it and makes `toBlob` throw `SecurityError`. Hyperliquid does not hit this because for them those icons are same-origin. The design had already solved this without knowing it: both chosen directions draw a monogram disc rather than a fetched icon.

- Observation: the bundle-budget gate cannot be tripped by this feature.
  Evidence: `tools/release-assets/check_bundle_budget.mjs` measures only the manifest entry whose `module-id` is `main`, and it was converted to a soft advisory that returns exit 0 on 2026-06-15. A card shipped in its own `:depends-on #{:main}` module is invisible to it and costs nothing.

- Observation: the design's turn-2 wordmark is not hyperopen's mark.
  Evidence: `github.md` in the design project claims it "reused the repo wordmark/favicon geometry as the four-slash Hyperopen mark", but `resources/public/favicon.svg` is two upright bars flanking a stroked ring — an H-bar-O monogram, in `#1DB99A`. There is no slashed geometry anywhere in the repo. The card will carry the real mark.

- Observation: a stale shadow-cljs server silently served an old bundle, and the Milestone 0 harness stayed red for the wrong reason.
  Evidence: an earlier `shadow-cljs --force-spawn compile` had left a JVM holding port 8080. Playwright's `webServer` started a second watch, which logged "TCP Port 8080 in use" and moved to 8081, while the tests kept hitting the stale server on 8080. The fix is to free the port before running; the symptom is a green compile and a red test that claims a just-written function does not exist.

- Observation: exploratory browser work on this feature cannot use a Browser MCP pane at all.
  Evidence: the pane's tab reports `document.hidden === true`. Replicant schedules its render through `requestAnimationFrame`, which never fires in a hidden tab, so app state updated correctly, `app-view` returned the modal hiccup when called by hand, and the DOM never changed. Every browser check here has to run through Playwright.

- Observation: the app's modal tier sits below the chart's overlay panes.
  Evidence: on the trade route, elements with computed z-index 100, 115, 118, 200 and 260 all carry `pointer-events: none`. Because `elementFromPoint` skips those, hit-testing reported the modal on top while they painted over it. The share modal was moved to `z-[300]`/`z-[301]`, above every pane. The funding modal at `z-[80]` and the API-wallets modal at `z-[280]` have the same latent problem and were left alone; that is a separate fix.

- Observation: `src/hyperopen/state/app_defaults.cljs` required `hyperopen.account-tab-modules` twice.
  Evidence: identical `:require` entries on two consecutive lines. Removing the duplicate is what put the namespace back under the 500-line cap after this plan added one line to it, so the size gate paid for itself.

- Observation: the referral code is not in app state on the trade route.
  Evidence: `src/hyperopen/startup/route_refresh.cljs:73` loads referral state only when `referrals-actions/referrals-route?` matches. Hyperliquid solves this by fetching `{type: "referral", user}` from inside the share modal on mount. Hyperopen must do the same, and must render the bare site link — never a placeholder code — while the fetch is in flight.

## Decision Log

- Decision: ship two card templates, "Neon arrow" (design 2a) and "Number as hero" (design 1a), behind a picker in the modal.
  Rationale: 2a is the direction the requester's reference screenshot matched and is the one with a distinct visual identity; 1a is drawn entirely from `--ho-*` tokens and therefore proves the renderer is genuinely template-agnostic rather than one hard-coded drawing. Shipping both now costs roughly 1.6x one template and makes the remaining six directions a data-only addition later.
  Date/Author: 2026-08-21, requester decision.

- Decision: set the card's type in JetBrains Mono, not the design's Outfit or Space Grotesk.
  Rationale: an SVG rasterised through an `Image` element renders in an isolated document that cannot fetch anything, so any webfont must be base64-inlined into the SVG at export time. JetBrains Mono already ships in this repo at `resources/public/fonts/`, is same-origin, and is two 39KB files against Inter Variable's 352KB. It is also what the design's own turn-1 rule asks for — "JetBrains Mono for every number". The hero numeral loses Outfit's geometric character; that is the accepted cost.
  Date/Author: 2026-08-21, requester decision.

- Decision: the only entry point in this plan is the Positions table.
  Rationale: it is the surface in the requester's screenshot, its data is complete and live, and it needs no fill-walking. Hyperliquid additionally wires trade history, outcomes and spot balances; each needs its own adapter and, for trade history, a real episode-reconstruction engine. Those are follow-on work against the same modal.
  Date/Author: 2026-08-21, requester decision.

- Decision: drop the aspect-ratio picker from the design's 1e mock; ship 16:9 only.
  Rationale: the mock scales one layout into 1:1 and 9:16 boxes with container-query units, which distorts rather than re-lays-out. Doing it honestly means three hand-built layouts per template, six in total, for a control Hyperliquid does not offer at all. Deferred and recorded under "What This Plan Does Not Do" rather than shipped badly.
  Date/Author: 2026-08-21, plan author.

- Decision: the share affordance is hidden in spectate (read-only) mode.
  Rationale: matches Hyperliquid, which gates the trigger on `forActiveUser`. Posting a card that implies a stranger's position is yours is a misrepresentation the product should not make easy.
  Date/Author: 2026-08-21, plan author.

- Decision: card state lives in a new top-level app-db bucket `:pnl-share`, not under `:positions-ui`.
  Rationale: `:positions-ui` is inside `account-info-view-base-state-keys` in `src/hyperopen/views/trade_view.cljs`, so every keystroke in the caption field would bust the account panel's memo and re-render the entire Positions table. A bucket absent from every `select-keys` vector cannot do that, and the modal itself is mounted from `app_view`, which is not memoized.
  Date/Author: 2026-08-21, plan author.

- Decision: the rasterizer lives at `src/hyperopen/pnl_share/raster.cljs`, outside `hyperopen.views.*`.
  Rationale: `dev/check_namespace_boundaries.clj` requires a dated exception for any non-view namespace importing a view, and the effect adapter must import the rasterizer. Keeping the rasterizer, the actions and the naming helper outside `views/` means the feature adds no boundary exception at all.
  Date/Author: 2026-08-21, plan author.

- Decision: the card labels its figure "UNREALIZED P&L", not the design's "PROFIT & LOSS".
  Rationale: the card reports an open position marked against the live mark price, and the Positions table's own header already explains that mark price only estimates unrealized PnL. A public card that implies a settled result would be the exact misrepresentation `trading-ui-policy.md` forbids.
  Date/Author: 2026-08-21, plan author.

- Decision: `src/hyperopen/views/pnl_share/palette.cljs` carries a two-literal entry in `dev/theme_color_baseline.edn`.
  Rationale: the two counted literals are the strings `"rgb("` and `"rgba("` used to build colour values out of theme tokens — the file is the single sanctioned place colour construction happens, exactly as `views/trading_chart/utils/theme_colors.cljs` is. The entry was written by `bb -m dev.check-theme-colors --update`, which added one line and changed nothing else. No other file in this feature contains a colour literal.
  Date/Author: 2026-08-21, plan author.

- Decision: fields that cannot be derived are omitted, never zeroed.
  Rationale: `docs/agent-guides/trading-ui-policy.md` forbids displaying fake zeros as real data, and a share card is the most public thing this app renders. Held duration depends on finding an entry fill in the loaded `[:orders :fills]` window; when there is none, the Held field does not appear on the card at all.
  Date/Author: 2026-08-21, plan author.

## Outcomes & Retrospective

The feature works as intended. A trader goes from a position row to a postable PNG in two clicks; the two templates render character-for-character identical numbers while looking nothing alike; and the loss treatment flips the artwork, swaps to the sell palette and relabels the figure, with the minus sign carried in the text so nothing depends on colour alone. `npm run gates` is 34/34 with 6772 tests and 36542 assertions, and eight Playwright tests cover the flow end to end including the real 2160x1216 PNG.

Complexity rose about as much as expected: eleven new namespaces, one lazy module, six actions and two effects. Three things kept the cost down. The card is a pure function from the existing Positions row view-model to SVG hiccup, so it is unit-tested in the node suite like every other view here and needed no new test machinery. Choosing a monospaced face made every text width arithmetic, so the templates lay themselves out without measuring the DOM. And the only DOM-touching code is one rasterizer plus one effect adapter, both of which the Playwright harness exercises directly.

Two things were harder than the plan assumed. The design brief and Hyperliquid disagree about how much belongs on a card — Hyperliquid ships six fields, the design ships twelve — and every extra field had to be justified against the honesty rules rather than simply drawn. And the two hours lost to a stale dev server plus a hidden Browser-pane tab were not implementation cost at all; both are recorded above so the next person recognises the symptoms in minutes.

## Context and Orientation

Hyperopen is a ClojureScript single-page trading client built on Replicant for rendering and a Nexus-style action/effect runtime for state. It has no server: everything runs in the browser against Hyperliquid's public API. The trade page renders a chart, an order form, and at the bottom an account panel with eight tabs.

The Positions tab is the surface this plan changes. Raw position rows arrive from Hyperliquid's `clearinghouseState` and land in app state at `[:webdata2 :clearinghouseState :assetPositions]` for the base venue and `[:perp-dex-clearinghouse "<dex>" :assetPositions]` for HIP-3 named venues. `hyperopen.views.account-info.projections.positions/collect-positions` merges them and stamps a `:dex` key on each row. A row is a map `{:type "oneWay" :position {...} :dex nil-or-string}`, where the inner `:position` map is Hyperliquid's own shape: `:coin`, `:szi` (signed size, negative for shorts), `:entryPx`, `:markPx`, `:positionValue`, `:unrealizedPnl`, `:returnOnEquity` (a fraction, not a percentage), `:liquidationPx`, `:marginUsed`, `:cumFunding`, and a nested `:leverage` map carrying `:value` and `:type` (`"cross"` or `"isolated"`).

`hyperopen.views.account-info.positions-vm/position-row-vm`, at `src/hyperopen/views/account_info/positions_vm.cljs:232`, turns one of those rows into the view-model the table renders. It already computes everything the card needs except the leverage value: `:side`, `:coin-label`, `:dex-label`, `:size-abs-num`, `:size-display`, `:entry-price`, `:mark-price`, `:pnl-num`, `:pnl-percent` (ROE already multiplied by 100), `:funding-display`, `:margin-mode`, and `:row-key`. It also carries the untouched `:position` map, from which the leverage value is read as `(get-in pos [:leverage :value])` — the desktop table does exactly this at `src/hyperopen/views/account_info/tabs/positions/desktop.cljs:35`.

The PNL (ROE %) cell itself is rendered at `desktop.cljs:104` as a single `[:div]` containing `(positions-shared/format-pnl-inline (:pnl-num row-vm) (:pnl-percent row-vm))`, which produces strings of the form `+$18,204.11 (+214.6%)`. The mobile equivalent is a summary item at `src/hyperopen/views/account_info/tabs/positions/mobile.cljs:147`. Both are the insertion point.

Modals in this repo are hand-rolled, not daisyUI dialogs. A heavy one lives in its own shadow-cljs module, is exported through an eight-line `*_module.cljs` shim using `goog/exportSymbol`, is registered in three parallel maps in `src/hyperopen/surface_modules.cljs`, gets a `:modules` entry in *both* the `:app` and `:release` build maps in `shadow-cljs.edn`, and is mounted from `src/hyperopen/views/app_view.cljs` alongside the funding and spectate modals. The action that opens it must emit `[:effects/load-surface-module :<surface-id>]` first. Focus handling comes from `hyperopen.views.ui.dialog-focus/dialog-focus-on-render`; click-away needs a `data-<x>-surface="true"` attribute on the panel plus an entry in `clickaway-surface-selectors` in `src/hyperopen/startup/runtime.cljs`.

Adding an action or effect is not a one-file change. `src/hyperopen/schema/contracts.cljs` throws at namespace load if the set of ids registered in `src/hyperopen/schema/runtime_registration/*.cljs` differs from the set of ids specced in `src/hyperopen/schema/contracts/action_args.cljs` and `effect_args.cljs`, and `test/hyperopen/schema/contracts_coverage_test.cljs` additionally forbids a new action from using the `::any-args` escape hatch. A divergence takes down the whole test build with a drift message rather than producing one failing test.

Six style gates bear on new view code. `lint:hiccup` rejects any `:class` string containing whitespace (classes must be separate vector elements) and any string key in a literal `:style` map. `lint:theme-colors` is a per-file ratchet on raw colour literals with a baseline in `dev/theme_color_baseline.edn`; a new file's allowance defaults to zero, so any raw hex fails until the baseline is updated. `lint:namespace-sizes` caps every file in `src` and `test` at 500 lines. `test/hyperopen/views/typography_scale_test.cljs` bans `text-[10|11|13|14|15px]` under `src/hyperopen/views/**`. Replicant writes SVG attribute keys through `setAttribute` verbatim, so camelCase keys such as `:strokeWidth` silently produce an attribute the renderer ignores — kebab-case only. And `src/**/domain/**` namespaces may never import `hyperopen.views.*`, which is why every namespace in this plan lives under `src/hyperopen/views/pnl_share/`.

The rasterisation technique is settled, because Hyperliquid's is readable and correct in outline. An inline `<svg>` node is serialised with `XMLSerializer`, wrapped as `data:image/svg+xml,` plus `encodeURIComponent` of the markup, loaded into an `Image`, drawn into a `<canvas>` at twice the intrinsic size, and read back with `toBlob`. The critical constraint, and the reason the font decision matters, is that an SVG loaded through an `Image` element renders in a sandboxed document that cannot fetch anything: no external stylesheet, no external font, no external image. Locally installed families such as `system-ui` still resolve, but a self-hosted webfont must be fetched separately, base64-encoded, and injected as an `@font-face` rule inside the SVG's own `<defs><style>` before serialisation. Hyperliquid ships 230KB of base64 font inside their lazy chunk to do this; hyperopen will instead fetch its two 39KB woff2 files from `/fonts/` at export time, where they are same-origin and usually already in the HTTP cache, and encode them once per session.

## Plan of Work

### Milestone 0 — Make the export path provable before drawing anything

The riskiest part of this feature is not the artwork, it is whether an SVG-to-PNG round trip with an inlined webfont actually produces correct bytes in a real browser. Prove that first, with a harness that shares no code with the card.

Add `tools/playwright/test/pnl-share-card-export.spec.mjs`. It visits the trade route through `tools/playwright/support/hyperopen.mjs`'s `visitRoute`, injects a fixed 200x100 reference SVG into the page containing one `<rect>` of a known colour and one `<text>` element in JetBrains Mono, then calls the exported rasterizer through the debug bridge and asserts four things about the result: the blob's first eight bytes are the PNG signature, the decoded image is 400x200 (twice the intrinsic size), the pixel at the centre of the rectangle equals the known colour, and the bounding box of the rendered glyphs is wider than it would be in the fallback stack — which is what proves the font actually inlined rather than silently falling back.

This spec must be committed while it is red, and the red must be a missing-function error, not a wrong-pixel error. That is the boundary that makes the rest of the plan a fact rather than a claim.

### Milestone 1 — The card data model, pure and honest

Create `src/hyperopen/views/pnl_share/card_data.cljs`. It exposes one function, `position-card-data`, taking the position row view-model produced by `positions-vm/position-row-vm` plus a context map `{:owner-address :referral-code :site-origin :now-ms :fills :template :options}`, and returning a flat map of already-formatted strings and a small number of raw numbers the templates need for colour decisions.

The map carries: `:coin-label` and `:dex-label` (base symbol plus venue chip, per the trading-UI policy on namespaced instruments); `:side` as `:long` or `:short`; `:leverage-label` such as `"LONG 20X"` built from `(get-in position [:leverage :value])`; `:roe-text` such as `"+214.6%"` formatted to one decimal with an explicit sign; `:roe-positive?`; `:pnl-text` such as `"+$18,204"`; `:entry-price-text` and `:mark-price-text` via `shared/format-trade-price`; `:size-text`; `:funding-text`; `:held-text`; `:handle-text` from `wallet/short-addr`; `:timestamp-text` in `YYYY-MM-DD HH:MM UTC`; `:join-link` and `:join-label`; and `:monogram` (first character of the base symbol) with `:monogram-gradient` derived deterministically from the symbol so the same coin always gets the same disc.

Three honesty rules are load-bearing and each gets its own test. `:held-text` is `nil` unless an entry fill for this coin and side is found in the supplied fills, computed by a small local `opened-at-ms` helper that mirrors the entry-fill selection already used at `src/hyperopen/views/trading_chart/utils/position_overlay_model.cljs:310` without importing that lazy module. `:join-link` falls back to the bare site origin, and `:join-label` to the bare host, whenever the referral code is absent or still loading — never a placeholder. And every optional field the modal can toggle off resolves to `nil` rather than an empty string, so a template can test presence rather than emptiness.

Tests go in `test/hyperopen/views/pnl_share/card_data_test.cljs`: a winning long, a losing long, a short, an isolated position, a HIP-3 named-dex position, a position with no fills loaded, and a wallet with no referral code.

### Milestone 2 — Resolve theme tokens to concrete colours

Create `src/hyperopen/views/pnl_share/palette.cljs`, modelled directly on `src/hyperopen/views/trading_chart/utils/theme_colors.cljs`. It reads `--ho-*` custom properties from the document root through `getComputedStyle`, caches per `data-theme` value, and falls back to the default dark theme's literals when there is no DOM — which is the case in the node test suite.

This indirection is not decoration. The card SVG is rasterised in a sandboxed document with no access to the page's `:root`, so `var(--ho-buy)` inside the card would resolve to nothing. Every colour must be a concrete string by the time the hiccup is built. Resolving through this namespace is also what keeps the two templates theme-aware for free: a card exported under HyperDegen picks up that theme's gold and greens.

Because this file carries the default-theme literals as fallbacks, it will trip `lint:theme-colors`. Add its baseline entry to `dev/theme_color_baseline.edn` in the same commit, with the count the linter reports, rather than scattering hex through the template files.

### Milestone 3 — Both templates as pure SVG hiccup

Create `src/hyperopen/views/pnl_share/card_view.cljs` as the dispatcher, plus `src/hyperopen/views/pnl_share/templates/neon_arrow.cljs` and `src/hyperopen/views/pnl_share/templates/number_hero.cljs`. Each template is a pure function from the Milestone 1 data map plus the Milestone 2 palette to a single `[:svg ...]` hiccup tree, 1080 wide by 608 tall, carrying explicit `:width` and `:height` attributes as well as a `:view-box` — Firefox rasterises a viewBox-only SVG at zero size.

Neon arrow reproduces design 2a. Its structure is a full-bleed dark plate, two radial gradient washes, a rotated contour field, a large gradient-filled arrow with a soft glow, and then the content column: the H-bar-O mark with the wordmark, the monogram disc with coin and leverage badge, the 132px gradient numeral with its spaced label beneath, an entry and mark price pair each in a rounded icon frame, a hairline rule, and a footer carrying the site link, the size-held-funding line, the handle and the timestamp. The loss treatment is the same skeleton with the arrow path replaced by the downward form, the palette swapped to the sell family, a "CERTIFIED L" pill beside the wordmark, and the numeral's label changed. Two details need care: `repeating-radial-gradient` and CSS masks have no SVG equivalent, so the contour field becomes a `<g>` of concentric ellipse strokes with a `<linearGradient>` mask applied through `mask=`; and the gradient numeral is a `<text>` with `fill="url(#...)"` rather than a `background-clip` trick.

Number as hero reproduces design 1a using only palette tokens: a token-coloured plate with one radial dot field, the wordmark and handle across the top, coin and leverage badge, the 168px numeral with the realised figure set beside its baseline, an optional voice line drawn from `hyperopen.ui.voice`, a hairline rule, and a five-column stat row with the join link right-aligned.

Tests go in `test/hyperopen/views/pnl_share/templates_test.cljs` and walk the returned hiccup with `hyperopen.test-support.hiccup`: assert the hero numeral text, assert the accent colour differs between the winning and losing variants, assert that toggling a field off removes its node entirely rather than emptying it, assert every attribute key is kebab-case, and assert both templates render the identical `:roe-text` and `:entry-price-text` from the same data map.

### Milestone 4 — The share modal, as a lazy surface module

Create `src/hyperopen/views/pnl_share/modal.cljs` reproducing design 1e minus the ratio picker: a two-column panel whose left side holds a header line, the scaled card preview in a bordered well, and whose right rail holds the template picker, the "Show on card" checkboxes for entry/exit prices, funding and handle, a caption field with a 280-character counter matching Hyperliquid's limit, and the three actions. Under the buttons sits a one-line reassurance that the card is generated in the browser and nothing about the wallet leaves the page — which, unlike Hyperliquid's "renders server-side" copy, is literally true here.

The preview embeds the same SVG the exporter serialises, scaled with CSS to fit the well, and carries `:data-role "pnl-share-card-svg"` so the effect adapter can find it and the Playwright spec can assert on it. The panel gets `:role "dialog"`, `:aria-modal true`, `:aria-labelledby`, a `:replicant/on-render` bound to `dialog-focus/dialog-focus-on-render`, and a keydown handler dispatching the close action on Escape.

Wire the module: the eight-line shim `src/hyperopen/views/pnl_share_modal_module.cljs`; the id `:pnl-share-modal` added to all three maps in `src/hyperopen/surface_modules.cljs`; a `:pnl_share_modal {:entries [hyperopen.views.pnl-share-modal-module] :depends-on #{:main}}` entry in *both* the `:app` and `:release` `:modules` maps in `shadow-cljs.edn`; a `(surface-modules/render-surface-view state :pnl-share-modal)` call in `src/hyperopen/views/app_view.cljs` beside the existing two; and `data-pnl-share-surface="true"` on the panel plus an entry in `clickaway-surface-selectors` in `src/hyperopen/startup/runtime.cljs`.

Seed `:pnl-share` in `src/hyperopen/state/app_defaults.cljs` from a `default-pnl-share-state` function defined next to the actions. Do not add it to any `select-keys` vector in `src/hyperopen/views/trade_view.cljs`; that omission is deliberate and is what keeps caption typing from re-rendering the Positions table.

### Milestone 5 — Actions, effects, and the five contract surfaces

Add `src/hyperopen/pnl_share/actions.cljs` with six actions. `:actions/open-pnl-share-card` takes the position row map, emits the surface-module load first, writes the open state, and triggers a referral fetch when no code is present. `:actions/close-pnl-share-card` clears it. `:actions/set-pnl-share-option` takes an option key and a value and covers the template, the three field toggles and the caption in one contract rather than four. `:actions/save-pnl-share-card-image` emits the export effect. `:actions/copy-pnl-share-link` emits the copy effect. `:actions/handle-pnl-share-card-keydown` closes on Escape.

Add two effects. `:effects/export-pnl-share-card-png` lives in a new adapter namespace `src/hyperopen/runtime/effect_adapters/pnl_share.cljs` — not in `effect_adapters.cljs`, which is already at its 650-line size exception. It locates the SVG by data-role, fetches and base64-encodes the two JetBrains Mono woff2 files (chunking the encode, because `String.fromCharCode.apply` over a 39KB buffer overflows the call stack), clones the node and injects the `@font-face` block, serialises, rasterises at 2x, and then either calls `navigator.share` with the file when the device supports it or falls back to the repo's existing blob-and-anchor download idiom. `:effects/copy-pnl-share-link` reuses `hyperopen.wallet.copy-feedback-runtime/copy-text!` so the toast behaviour matches the address and spectate-link copies already in the app.

Every id must be added in the same commit to `src/hyperopen/schema/runtime_registration/` (a new `pnl_share.cljs` area file, referenced from the catalog), to `src/hyperopen/schema/contracts/action_args.cljs` and `effect_args.cljs` with real specs rather than `::any-args`, and — because the export effect is heavy IO — to `src/hyperopen/runtime/effect_order_contract.cljs`, followed by `bb tools/formal.clj sync --surface effect-order-contract` to regenerate the Lean surface. Skipping any one of these makes `contracts.cljs` throw at load and the entire test build fail with a drift message.

### Milestone 6 — The trigger in the Positions tables

In `src/hyperopen/views/account_info/tabs/positions/desktop.cljs`, replace the PNL cell's plain `[:div]` at line 104 with a `[:button]` carrying the same text plus a 14-pixel accent-coloured share glyph, `:data-role "pnl-share-trigger"`, a descriptive `:aria-label`, and `:on {:click [[:actions/open-pnl-share-card position-data]]}`. Render the button form only when `read-only?` is false; otherwise keep the existing `[:div]` exactly as it is. Mirror the change at `src/hyperopen/views/account_info/tabs/positions/mobile.cljs:147`.

The grid template must not move. `positions-layout/positions-grid-template-class` defines eleven tracks and the header row is written against them; the trigger has to live inside the existing PNL cell, not become a new column.

### Milestone 7 — Browser QA, then Playwright

Exploratory work goes through Browser MCP against the local dev server: open the trade page in spectate mode against an address with live positions, seed nothing, and walk the real flow. Verify at 375, 768, 1280 and 1440 that the trigger is reachable, the modal fits, the preview scales without clipping, the caption counter tracks, both templates render, and the loss treatment appears on a losing row. Record each pass as PASS, FAIL or BLOCKED; "looks good" is not a result.

Then convert the stable path to `tools/playwright/test/pnl-share-card.spec.mjs`: seed a winning and a losing position through the debug bridge after freezing account sync, click the PNL cell, assert the modal opens and the card SVG carries the expected numerals, switch templates and assert the numerals are unchanged while the artwork node changes, toggle funding off and assert the node disappears, and finally run the Milestone 0 rasterizer against the real card and assert the PNG dimensions. Explicitly stop every Browser MCP session afterwards with `npm run browser:cleanup`.

### Milestone 8 — Gates and close-out

Run the full matrix, fix what it finds, and move this file from `docs/exec-plans/active/` to `docs/exec-plans/completed/` — `lint:docs` fails an active plan with no unchecked progress item, so the move is part of finishing, not an afterthought.

## Concrete Steps

All commands run from the worktree root, `/Users/barry/projects/hyperopen/.claude/worktrees/account-leverage-hl-xyz-parity-e67bda`.

Bootstrap once, before any gate, because a fresh worktree has no `node_modules` and `shadow-cljs` is not on `PATH`:

    npm run setup:worktree

After each milestone that adds a namespace, regenerate the runner and run the unit suite. A test namespace is discovered only if its filename ends in exactly `_test.cljs` and it sits under `test/hyperopen/`:

    node tools/generate-test-runner.mjs
    npm test

After any paren surgery, which is easy to cause in a file full of nested SVG hiccup:

    bb -m dev.check-delimiters --changed

After Milestone 2 and again after Milestone 3, re-baseline the colour ratchet and confirm it now passes:

    npm run lint:theme-colors
    bb -m dev.check-theme-colors --update
    npm run lint:theme-colors

After Milestone 5, regenerate the formal surface and confirm it verifies:

    bb tools/formal.clj sync --surface effect-order-contract
    bb tools/formal.clj verify --surface effect-order-contract

For the browser work in Milestone 7, start the dev server through the preview tooling rather than a bare shell, then run the narrowest spec first and broaden only once it is green:

    npx playwright test tools/playwright/test/pnl-share-card-export.spec.mjs
    npx playwright test tools/playwright/test/pnl-share-card.spec.mjs
    npm run browser:cleanup

The final matrix, which does not short-circuit on the first failure:

    npm run gates

Expect a 34-line PASS/FAIL table. Anything other than all-PASS is a blocker for moving this plan to `completed/`.

## Validation and Acceptance

The behavioural acceptance is a sequence anyone can run. Start the dev server, open `http://127.0.0.1:8080/trade`, enter spectate mode against an address holding at least one winning and one losing perp position, and select the Positions tab. Each PNL (ROE %) cell shows its usual `+$18,204.11 (+214.6%)` string; because spectate is read-only, no share glyph appears — that absence is itself an acceptance criterion. Connect a wallet with an open position instead, and the glyph appears.

Click the cell of the winning position. A modal opens over a dimmed backdrop, focus lands inside it, and Escape closes it. The card shows the coin's base symbol, a venue chip if the position is on a named dex, a `LONG 20X` badge, a large green figure identical to the percentage in the table row it came from, and entry and mark prices identical to that row's Entry Price and Mark Price cells. Uncheck "Funding paid" and the funding field vanishes from the card; re-check it and it returns. Switch the template to Number as hero: the artwork changes completely, and every number stays character-for-character the same.

Press Save PNG. A file named `hyperopen-<coin>-<side>-<date>.png` downloads. Opened, it is 2160 by 1216 pixels, the numerals are rendered in JetBrains Mono rather than a generic monospace fallback, and no field is blank or shows a stray `--`.

Click the losing position's cell. The arrow points down, the palette is the sell family, and the numeral is negative with an explicit minus sign — the loss is not colour-only, per the trading-UI policy.

For the automated proof: run `node tools/generate-test-runner.mjs && npm test` and expect the whole suite to pass with the new namespaces `hyperopen.views.pnl-share.card-data-test`, `hyperopen.views.pnl-share.templates-test`, `hyperopen.views.pnl-share.modal-test` and `hyperopen.pnl-share.actions-test` included. Each of those fails before its milestone's implementation lands and passes after. Then run `npx playwright test tools/playwright/test/pnl-share-card-export.spec.mjs` and expect one passing test; it must fail with a missing-function error when run at the Milestone 0 commit.

## Idempotence and Recovery

Every step is safe to repeat. `npm run setup:worktree` is a no-op once the symlink exists. `node tools/generate-test-runner.mjs` is deterministic and produces a git-clean file when no test namespaces changed. `bb tools/formal.clj sync` regenerates from source and is safe to re-run.

Two steps need care on retry. `bb -m dev.check-theme-colors --update` rewrites `dev/theme_color_baseline.edn`; if it ratchets a number the wrong way, revert that single file and re-run rather than hand-editing. And moving this plan to `docs/exec-plans/completed/` must be a `git mv` — leaving a copy in `active/` with all boxes checked fails `lint:docs` with `:active-exec-plan-no-unchecked-progress`.

Rollback is clean at every milestone boundary because nothing before Milestone 6 is reachable from the UI: the module, the templates and the actions can all exist without the Positions table knowing about them. If the export path proves unworkable in some browser, Milestone 6 is a two-file revert that removes the entry point while leaving the rest inert.

Leave the environment clean: stop the dev server, and run `npm run browser:cleanup` to close any Browser MCP session opened during Milestone 7.

## Artifacts and Notes

Hyperliquid's rasterizer, recovered from `assets/font-WdlKaabY.js` and reformatted for legibility. This is the shape hyperopen's effect adapter mirrors, with `image/jpg` corrected to `image/png`:

    const loadImage = async (src, scale = 1) => {
      const img = new Image();
      return new Promise((resolve, reject) => {
        img.onload = () => { img.width *= scale; img.height *= scale; resolve(img); };
        img.onerror = reject;
        img.src = src;
      });
    };

    const svgToCanvas = async (svgNode, postProcess) => {
      const xml = new XMLSerializer().serializeToString(svgNode);
      const img = await loadImage(`data:image/svg+xml,${encodeURIComponent(xml)}`, 2);
      const canvas = document.createElement("canvas");
      canvas.width = img.width; canvas.height = img.height;
      const ctx = canvas.getContext("2d");
      ctx.drawImage(img, 0, 0, img.width, img.height);
      return { dataUrl: canvas.toDataURL("image/jpg", 1),
               dataBlob: await new Promise(r => canvas.toBlob(r, "image/jpeg")) };
    };

And their font inlining, which is the part that cannot be skipped:

    function faceCss(family, base64, format) {
      return `@font-face { font-family: '${family}'; src: url(data:font/${format};base64,${base64}) format('${format}'); }`;
    }

The exact props Hyperliquid's modal receives, from `config-Dq8XJCAJ.js`, which bounds what parity actually requires:

    { onClose, coin, entryPx, markPx, returnOnEquity, side, leverage, address }

And their trigger, which confirms the whole cell is the button and the glyph is appended after the text:

    onClick: () => setModalState({ status: `share`, coin: k, entryPx: S,
                                   markPx: j, returnOnEquity: E, side: F,
                                   leverage: C.value })

## Interfaces and Dependencies

No new npm dependency. The rasterizer is `XMLSerializer`, `Image`, `<canvas>` and `toBlob`, all of which are platform APIs; the download path reuses the blob-and-anchor idiom already present in five places in this repo, and the clipboard path reuses `hyperopen.wallet.copy-feedback-runtime/copy-text!`. Fonts come from the two files already at `resources/public/fonts/JetBrainsMono-{Regular,Medium}.woff2`.

In `src/hyperopen/views/pnl_share/card_data.cljs`, define:

    (defn position-card-data
      [row-vm {:keys [owner-address referral-code site-origin now-ms fills template options]}]
      ;; => map of formatted strings; optional fields are nil when unavailable
      )

    (defn opened-at-ms
      [coin side fills]
      ;; => epoch millis of the latest entry fill for this coin and side, or nil
      )

In `src/hyperopen/views/pnl_share/palette.cljs`, define:

    (defn token [token-name])          ;; => concrete CSS colour string
    (defn card-palette [side winning?]) ;; => map of role -> concrete colour

In `src/hyperopen/views/pnl_share/card_view.cljs`, define:

    (defn card-svg [card-data palette])  ;; => [:svg {...} ...], 1080x608

with both `hyperopen.views.pnl-share.templates.neon-arrow/render` and
`hyperopen.views.pnl-share.templates.number-hero/render` sharing that signature.

In `src/hyperopen/views/pnl_share/modal.cljs`, define:

    (defn pnl-share-modal-view [state])  ;; => hiccup or nil when closed

exported through `src/hyperopen/views/pnl_share_modal_module.cljs` as
`hyperopen.views.pnl_share_modal_module.pnl_share_modal_view`, and registered under
the surface id `:pnl-share-modal`.

In `src/hyperopen/pnl_share/actions.cljs`, define `default-pnl-share-state` plus the six
action handlers, each with the signature `(fn [state & args] -> [[:effects/... ...] ...])`
that this repo's Nexus registry requires.

In `src/hyperopen/runtime/effect_adapters/pnl_share.cljs`, define:

    (defn export-pnl-share-card-png-effect
      [{:keys [selector filename scale font-urls] :as args}])

    (defn copy-pnl-share-link-effect
      [{:keys [link]}])

## What This Plan Does Not Do

It does not add the aspect-ratio picker from design 1e. It does not implement the mascot or image overlay slot — Hyperliquid ships ten character illustrations behind theirs, and design 2c offers a bring-your-own-art variant; both are follow-on work and neither is required for the flow to be useful. It does not add the remaining six card directions, though the template dispatcher is built so that each is a new namespace and a new entry rather than a refactor. It does not put a share affordance on trade history, outcomes or spot balances. It does not touch the referrals route or change how referral codes are created — it only reads a code that already exists. And it does not add server-side card rendering or an OpenGraph image endpoint; there is no server, and the card is generated entirely in the browser.
