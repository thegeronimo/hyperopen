# Explain what a HIP-3 symbol is, in the market header and the asset selector

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current as work proceeds. Maintain it according to `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

HIP-3 markets have tickers that say little about what you are trading. `SPCX`, `ANTH`, `10Y`, `XYZ100` and `vntl:SPACEX` could be a share, a pre-IPO valuation in billions, a bond yield or an index. trade[XYZ] shows a one-line issuer subtitle under the ticker ("Space Exploration Technologies Corp."). Hyperopen shows only the ticker and the dex chip.

The deployers already publish this information on-chain. Hyperliquid's `/info` API exposes it through two request types:

- `{"type":"perpConciseAnnotations"}` is one bulk call. It returns `[[coin, {category, displayName?, keywords?}], ...]` for every annotated HIP-3 market (221 on 2026-10-15). It has no descriptions.
- `{"type":"perpAnnotation","coin":"xyz:SPCX"}` is per coin. It returns `{category, description, displayName?, keywords?}`, or `null` when the deployer published nothing.

`perpDexs` (already fetched) carries each deployer's `fullName`, for example `flx` → "Felix Exchange".

After this change:

1. **Header subtitle (design A).** The market header shows a second line under the ticker for any annotated HIP-3 market. The issuer is parsed from the description where it can be ("Tesla, Inc."); otherwise the line shows the display name or a category label. Hovering or focusing the market button shows the deployer's full description, attributed to the deployer.
2. **Fallbacks (design E).** Every HIP-3 market keeps the subtitle line, with degrading content:
   - full annotation → issuer and category;
   - free-form description → its first clause;
   - name only → display name and category;
   - nothing published → "Market by <deployer full name>";
   - not yet loaded → nothing, with no layout jump on desktop.
   Deployer text is always rendered as plain text.
3. **Selector (design C).**
   - Each annotated row carries a muted inline descriptor after the chips: the issuer if its description has been loaded this session, else the display name when it differs from the ticker (e.g. `io:OAI` → "OPENAI").
   - Search matches deployer keywords and display names, so "spacex", "openai" and "tesla" find their markets.
   - When the Tradfi tab is active, a sub-tab row filters by asset class: All, Stocks, Indices, Commodities, Pre-IPO, FX, Rates.

To see it working: run the app, select `xyz:SPCX`, and read "Space Exploration Technologies Corp. · Stock" under the ticker. Open the selector, type "spacex", and both `xyz:SPCX` and `vntl:SPACEX` appear. Click Tradfi → Pre-IPO.

## Context References

Public refs:

- Direct user request, 2026-10-15: "come up with a few designs for how this can be most intuitively displayed", then "go ahead and implement it" after the recommendation to build A + C + E first and B (spec panel) and D (mobile sheet) later.
- The design canvas is at https://claude.ai/artifact/B2aHnmL1z8kqo4XeM9A3PT. It is private to the requester.

Repo artifacts:

- `/hyperopen/docs/exec-plans/completed/2026-08-21-account-tab-lazy-module-repaint.md`: the memoized trade-view state-slice trap. New app-db keys read by the header must be added to `panel-dependency-state`.
- `/hyperopen/docs/BROWSER_TESTING.md`, `/hyperopen/docs/agent-guides/browser-qa.md`.

Local scratch refs (non-authoritative):

- None.

## Scope and Non-Goals

In scope: the request plumbing, pure normalization and subtitle derivation, the app-db projection, the per-coin lazy effect, the header subtitle and hover description, the selector inline descriptor, keyword search, and the Tradfi asset-class sub-tabs.

Non-goals, which are follow-ups:

- The full "About this market" spec panel (design B).
- The mobile bottom sheet (design D).
- Persisting descriptions across sessions.
- Bulk-fetching every description. `perpAnnotation` is one request per coin, so bulk-fetching 221 of them would cost a large share of the per-IP info rate budget.

## Progress

- [x] (2026-10-15) Confirm the endpoints and live coverage by deployer. Design canvas approved.
- [x] (2026-10-15) Pure annotation namespace `src/hyperopen/asset_selector/annotations.cljs`. Tuned against all 221 live descriptions: every xyz single-share market yields its issuer (115/115).
- [x] (2026-10-15) Endpoints, gateway and default API wiring for both request types. `perpDexs` `fullName` now projects to `:perp-dex-full-name-by-name`.
- [x] (2026-10-15) `:perp-annotations` projection and `:effects/sync-perp-annotations`. Emitted on select-asset and subscribe-to-asset for HIP-3 coins, and on selector open until the bulk payload loads.
- [x] (2026-10-15) Header subtitle and viewport-fixed hover description, with all fallbacks.
- [x] (2026-10-15) Selector descriptor, keyword and display-name search, Tradfi asset-class sub-tabs.
- [x] (2026-10-15) `npm run gates` 34/34 PASS. `bb tools/formal.clj verify --surface effect-order-contract` PASS.
- [x] (2026-10-15) Browser QA against the worktree build on :8090 with live mainnet data, at 1440 and 375.
- [ ] Owner acceptance, then move this plan to `completed/`.

## Surprises & Discoveries

- The subtitle trade[XYZ] shows has no API field. It is lifted from the description ("references 1 share of Space Exploration Technologies Corp. Class A common stock").
- Category labels are inconsistent across deployers: `fx`/`FX`, `stock`/`stocks`.
- dreamcash, HyENA and ABCDEx publish no annotations. `perpAnnotation` returns JSON `null` for their coins. EntropyIO publishes empty descriptions for pre-IPO markets.
- ClojureScript `str/split` with a limit locates each match with `indexOf` of the matched text. That ignores regex lookbehind, so "U.S. companies" split at the first ". ". Use the no-limit form, which is native `String.prototype.split`.
- The market strip is an `overflow-x-auto` container about 79px tall, so it clips an absolutely positioned hover card. The description card is viewport-fixed instead, positioned from the trigger's rect on mouseenter or focus.
- Firing the effect only for HIP-3 coins left the selector without annotations after a reload on BTC. Opening the selector now requests the bulk payload until it has loaded.
- A desktop selector descriptor squeezed the ticker to "S…". The ticker is `shrink-0` whenever a descriptor is present.
- On a 375px phone the header ticker truncates ("TS…"). This predates the change: the ticker measures the same with the subtitle hidden (37px visible of 91px).

## Decision Log

- Decision: keep annotations in their own app-db key and merge them into markets at the selector view-model boundary, instead of writing them into catalog market entries. Rationale: catalog markets are rebuilt on every refresh and whitelisted by two caches, so a separate key avoids touching both cache codecs and the merge ordering between two independent fetches. Date: 2026-10-15.
- Decision: one effect, `:effects/sync-perp-annotations coin`, ensures the bulk concise payload once per session (single flight) and then the coin's detail. Rationale: there is one registration chain, and the existing emission points (select-asset, subscribe-to-asset) cover both startup and switching markets. Date: 2026-10-15.
- Decision: selector rows stay one line. The descriptor is inline, using the existing outcome-summary pattern. Rationale: rows are virtualized at a fixed `row-height-px`. Date: 2026-10-15.

## Outcomes & Retrospective

Shipped designs A, C and E.

Header, live checks:
- `xyz:SPCX` reads "Space Exploration Technologies Corp. · Stock", and hovering shows the deployer's description attributed to XYZ.
- `para:CRWD` → "CrowdStrike · Stock".
- `io:OAI` → "OPENAI · Pre-IPO".
- `km:US500` → its first clause plus "Index".
- `cash:HOOD` → "Market by dreamcash".
- BTC shows no subtitle.

Selector:
- "spacex" finds `xyz:SPCX` and `vntl:SPACEX`.
- Tradfi → Pre-IPO lists the seven pre-IPO markets across io, xyz, para and vntl.

Remaining limits and follow-ups:
- Selector rows show the issuer only for markets whose description has loaded this session. Otherwise they show the deployer's display name where it differs from the ticker. Descriptions are per-coin requests, so persisting them is the follow-up that would fill rows over time.
- Designs B (spec panel) and D (mobile sheet) are not built.

## Validation

- Unit tests:
  - the annotations namespace (normalizers and subtitle derivation over real description fixtures);
  - the projection;
  - effect emission in select-asset and subscribe-to-asset;
  - query keyword search and sub-tab matching;
  - header and row view rendering.
- `npm run check`, `npm test`, `npm run test:websocket`.
- Browser QA: header subtitle for `xyz:SPCX`, `km:US500`, `io:ANTH` and a `cash:` market; selector search "spacex"; Tradfi sub-tabs; widths 375, 768, 1280 and 1440.
