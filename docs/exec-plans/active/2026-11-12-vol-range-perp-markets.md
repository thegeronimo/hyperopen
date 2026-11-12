# Vol Range on perp and HIP-3 markets

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It is maintained in accordance with `/hyperopen/docs/PLANS.md` and the detailed template in `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Volatility range ladders are admitted only on USDC spot pairs (HO-SPEC-014 v2.1 section 3.1). On a perp or HIP-3 market the ticket shows "Volatility range is available on USDC spot markets." After this change a user can plan, review, place, monitor, repair, replace and report a ladder on any delta-one market: spot pairs, base-dex perps and HIP-3 perps. Outcome markets stay ineligible.

## Context References

- Direct user request in this session (2026-11-12). User decisions: shorts are allowed on perps (inventory is the signed position); reporting is in scope, including funding.
- Product spec: `/hyperopen/docs/product-specs/vol-range-implementation-spec.md` (sections 1.2, 3, 6.5, 6.6, 12 amended by this plan).
- Hyperliquid info API (read 2026-11-12): `clearinghouseState` takes an optional `dex` and returns `assetPositions[].position {coin szi leverage}` and `withdrawable`; `activeAssetData {user coin}` returns `leverage {type value}` and works for `dex:COIN`; `frontendOpenOrders` takes an optional `dex` and without it returns the first perp dex plus spot only; `userFunding {user startTime endTime}` returns `{time hash delta {type "funding" coin usdc szi fundingRate}}`, at most 500 rows per response; perp fills carry `feeToken`.

## Progress

- [x] (2026-11-12) Mapped the spot assumptions; user decisions recorded.
- [x] (2026-11-12) Stage 1: eligibility, identity and market snapshot for perps (`contracts/supported-market?`, `perp-base-id`; `snapshots`, `actions`, `ticket_model`).
- [x] (2026-11-12) Stage 2: perp account snapshot (`snapshots/perp-balances`: signed position, margin capacity) from state and from the barrier read.
- [x] (2026-11-12) Stage 3: signed inventory limits and margin admission (`admission/validate-inputs` `:signed-inventory?`, `plan_core` `:plan/insufficient-margin`, `fees/perp-settlement`).
- [x] (2026-11-12) Stage 4: placement path: `reads/read-account-snapshot!` takes `:scopes` (dex clearinghouse state, `activeAssetData`, dex open orders); every caller passes its scope; campaign key, scope and baseline for perps; reload recovery runs on perps.
- [x] (2026-11-12) Stage 5: reporting: perp fill classification, `:ledger-funding` records and the `:funding` coverage stream, funding cash events, balance checks against the position, activity and export.
- [x] (2026-11-12) Stage 6a: ticket copy (position, margin note), spec section 3.4, tests (`test/hyperopen/vol_range/perp_markets_test.cljs`).
- [ ] Stage 6b: gates, browser QA on a perp and a HIP-3 market, owner test with a funded account before relying on it with size.

## Decision Log

- Decision: the account snapshot keeps its `:base`/`:quote` shape for perps. `:base :total` is the signed position; `:quote :available` is the buy notional capacity (`withdrawable x leverage`); `:base :available` is the sell capacity in base units at the mid. A new `:margin {:leverage :type :available :notional-capacity}` key (nil for spot) carries the venue figures. Rationale: repair, replace, pending reservations and revalidation all consume `:base`/`:quote`, so they keep working without a parallel code path.
- Decision: margin available is the dex clearinghouse `withdrawable`, the same figure the order ticket shows as Available to Trade; leverage is the venue's per-asset leverage (position leverage from state, `activeAssetData` in the barrier read). The undocumented element order of `availableToTrade`/`maxTradeSzs` is not relied on. Leverage is never changed as a hidden pre-action.
- Decision: quantity that reduces an existing position needs no margin. The planner counts only the position-increasing share of each side against capacity (prorated by quantity). Each side is checked on its own; the venue remains the final arbiter and per-leg rejections are handled by the existing lifecycle.
- Decision: on perps the protected core ("Hold at least") defaults to no limit and may be negative; the maximum may be any number. On spot nothing changes.
- Decision: perp fees settle in the quote token on both sides, so base fee bounds are zero.
- Decision: the reporting key for a perp is the synthetic base id `perp:<coin>` (the coin carries the dex prefix). Coverage, relevance and balance checks reuse the per-base-token machinery. Funding rows are a third stream (`:funding`) and enter the campaign as cash events; they are counted in the ladder contribution, and the copy says so.
- Decision: account modes. Spot keeps its allowlist. Perps additionally admit `unifiedAccount` and `portfolioMargin` only if the margin figures are read from the venue; for this change they stay blocked (the borrowing fixtures the spec asks for do not exist).

- Decision: the ticket plans a perp at the venue's leverage. The position's leverage is used when there is one; otherwise the execution adapter reads `activeAssetData` into `[:vol-range :perp-asset]` on every ladder sync, and the ticket's own leverage setting stands in only until that read lands. `revalidation/revalidate` refuses with `:guard/leverage-changed` when the barrier read's leverage or margin mode differs from the one planned with, and the adapter then re-reads it.
- Decision: a perp update (replace) compares account observations by position and venue margin facts, not by the mid-priced available figures (`replace-state/store-balances`).
- Decision: the matched-fills total includes window funding, so it still equals the ladder contribution.

## Surprises & Discoveries

- The Playwright vol-range helpers detected "fixture seeded" by the ticket heading being visible. The default BTC perp now shows the ticket too, so they check the identity strip for the fixture market.

- On Node 24 (`navigator.locks` exists) `npm test` stops silently at `nonce-coordinator-test` with exit 0, so `npm run gates` reports PASS without running most of the suite, and `campaign-executor-test/an-unresolved-send-attempt-refuses-the-restart-test` fails (`:busy`). Both predate this change. Fixed in `test/hyperopen/test_support/node_env.cljs`: test bundles hide `navigator.locks` (CI's Node 22 has none) and a run that ends before its summary exits 1; gates now run 9,243 tests and `npm run test:vol-range` 1,607 with 0 failures. With `node --no-experimental-global-navigator out/test.js` the main suite runs 8,414 tests (one unrelated failure, `footer_view_test` 3g hints, which needs `navigator`) and `out/vol-range-test.js` runs 1,607 with 0 failures.
- A standard account has no in-app way to fund a HIP-3 dex it holds nothing in: the Transfer dialog reaches a named dex only from that dex's Balances row. The ticket now offers the transfer, and the dialog names the dex.

- `frontendOpenOrders` without `dex` omits HIP-3 orders, so the barrier read must pass the market's dex.
- The capability matrix says `:execution? false`, but execution is enabled by config flags and is live on mainnet.

## Validation

`npm run gates`; focused vol-range tests; smallest Playwright vol-range spec; browser QA of the ticket on a perp and a HIP-3 market.

## Remaining risks

- The margin model (`withdrawable x leverage`, each side on its own, closing quantity free) is not verified against the venue with a funded account. The venue rejects what it does not accept and the per-leg lifecycle handles that, but admission may be looser or tighter than the venue.
- Repair on perps reuses the base-unit capacities (priced at the mid) and the spot wording of `:repair/insufficient-usdc` / `:repair/insufficient-base`.
- An update preview on a perp does not release the margin the old ladder's orders hold, so a margin-bound update can look unfunded until the cancel is confirmed.
- Hold P&L excludes the funding the held position would have paid; funding is counted in the ladder contribution.
- Non-USDC HIP-3 quotes, outcome markets and unified/portfolio-margin accounts stay blocked.

## Outcomes & Retrospective

Pending owner test on a funded account.
