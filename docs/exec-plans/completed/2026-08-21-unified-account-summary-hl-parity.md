# Bring the Unified Account Summary panel to parity with the Hyperliquid frontend

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md` (the authoring contract) and `/hyperopen/docs/PLANS.md` (the public planning entry point).

## Purpose / Big Picture

Hyperopen's trade page shows a panel titled "Unified Account Summary" for accounts that Hyperliquid reports as being in unified-account mode. Two of its numbers do not agree with the numbers the official Hyperliquid frontend shows for the same wallet at the same moment: "Unified Account Leverage" reads roughly 35% too high, and "Perps Maintenance Margin" reads roughly 30% too high.

A trader who has both tabs open sees two different answers to "how levered am I" and "how close am I to liquidation", and has no way to tell which one to trust. Because Hyperliquid is the venue that will actually liquidate the position, its number is the one that matters operationally, and ours is the one that must move.

After this change, opening `https://app.hyperliquid.xyz/trade` and Hyperopen's trade page side by side for the same wallet shows the same value, to the displayed two decimal places, for every row of the summary panel: Unified Account Ratio, Portfolio Value, Unrealized PNL, Perps Maintenance Margin and Unified Account Leverage.

## Context References

Public refs:

- Direct user request, 2026-08-21: "I'm comparing an account (0x4096d3377ae5ade578daae8188804740c8b1da3e) on hyperopen with hl.xyz ... our Unified Account Leverage shows 2.12x while hl shows 1.60x, Perp maintenance margin is also off. I want you to do a full investigation and get to the root cause why these differences occur and a plan to get us to parity with the HL.xyz implementation." Two screenshots were supplied, one per frontend. `hl.xyz` redirects to `app.hyperliquid.xyz`; it is the official venue frontend, not a third-party client.

Repo artifacts:

- `src/hyperopen/views/account_equity/metrics.cljs` — where every number in the panel is computed.
- `src/hyperopen/views/account_equity/panels.cljs` — where the panel is rendered, including row labels, row order and tooltip copy.
- `test/hyperopen/views/account_equity_unified_metrics_test.cljs` — the unit tests that pin the current (divergent) behaviour.
- `test/hyperopen/views/account_equity_view_test.cljs` — pins the current leverage tooltip string at line 209.
- `tools/playwright/test/account-equity-unified-isolated.spec.mjs` — the end-to-end spec that pins the rendered panel.
- Commit `d4144be73` ("fix(trade): count isolated positions in the unified account summary", 2026-08-20) — the change that introduced the divergence. Its reasoning is sound and must be preserved in spirit; see the Decision Log.
- `/hyperopen/docs/agent-guides/trading-ui-policy.md` line 62 — "MUST NOT display fake zeros/placeholders as real data". This rule is in direct tension with strict parity for one edge case; the Decision Log resolves it.

Local scratch refs (non-authoritative):

- None.

## Progress

- [x] (2026-08-21 08:40Z) Pulled live `clearinghouseState` for the reported wallet on the base dex and on all ten named HIP-3 dexes, plus `spotClearinghouseState` and `spotMetaAndAssetCtxs`, and reproduced both frontends' numbers arithmetically from that one snapshot.
- [x] (2026-08-21 08:45Z) Downloaded and decompiled the official frontend bundle (`https://app.hyperliquid.xyz/assets/index-CyXjSnYq.js` plus the `en-US` message catalogue) and recovered the exact functions that compute the three figures.
- [x] (2026-08-21 08:50Z) Confirmed the Unified Account Ratio is already at parity and identified the three independent defects behind the two divergent figures.
- [x] (2026-08-21 08:55Z) Confirmed the blast radius: `:unified-account-leverage` and `:maintenance-margin` have no consumers outside `src/hyperopen/views/account_equity/panels.cljs`.
- [x] (2026-08-21 09:02Z) Milestone 1 — leverage numerator is cross-only. `unified-notional-usd-value` became `unified-cross-notional-usd-value` and reads `[:crossMarginSummary :totalNtlPos]`.
- [x] (2026-08-21 09:03Z) Milestone 2 — leverage denominator widened. New `known-collateral-tokens` walks `market-by-key` for every `:perp` market's `:quote` and unions in the record quote tokens.
- [x] (2026-08-21 09:04Z) Milestone 3 — Perps Maintenance Margin is cross-only. `unified-isolated-maintenance-margin*` and `position-maintenance-margin` deleted; new `unified-isolated-notional-usd-value` feeds a `:isolated-notional` metric.
- [x] (2026-08-21 09:07Z) Milestone 4 — labels, row order, tooltips, gauge glyph and the 80% green/red split landed in `panels.cljs`.
- [x] (2026-08-21 09:15Z) Milestone 5 — `npm run gates` 34/34 (6,650 tests, 35,991 assertions); the panel spec passes; live parity confirmed against the reported wallet.
- [ ] Follow-up (deliberately out of scope, moved to `/hyperopen/docs/exec-plans/tech-debt-tracker.md`): `portfolioMargin` accounts are rendered with the unified formulas and labels.
- [ ] Follow-up (deliberately out of scope, moved to `/hyperopen/docs/exec-plans/tech-debt-tracker.md`): the classic panel's "Balance" and "Perps" rows do not match the venue's definitions.

## Surprises & Discoveries

- Observation: The official frontend states its own formula in its message catalogue, so this is not a matter of inference.
  Evidence: from `https://app.hyperliquid.xyz/assets/en-US-Dpwyw3I6.js` —
  `"unified.account.leverage.explanation": "Unified Account Leverage = Total Cross Positions Value / Total Collateral Balance."`
  and `"maintenance.margin.explanation": "The minimum portfolio value required to keep your cross positions open"`.
  Hyperopen's current tooltip says the opposite: "Counts cross and isolated positions together".

- Observation: The leverage denominator is built from the collateral token of **every** dex the venue knows about, not only the dexes the viewed wallet actually trades on. For this wallet that silently adds a USDH balance.
  Evidence: decompiled `Hd()` iterates `Object.values(multiverse)` to build the collateral-token set, then sums `spotBalance.total * price` over that set. `POST /info {"type":"meta","dex":"flx"}` returns `collateralToken: 360`, and token 360 is USDH. The wallet holds 8.28 USDH (≈ $8.24) and has never traded on `flx`, yet that $8.24 belongs in the denominator.

- Observation: The Unified Account Ratio is already a faithful port of the venue's function, including the clamp and the per-collateral-token maximum. No change is needed there.
  Evidence: decompiled `Vd()` groups `crossMaintenanceMarginUsed` and isolated `marginUsed` by each dex's collateral token, divides `crossMaintenance / (spotTotal - isolatedMarginUsed)` per token, takes the maximum and clamps to 1 — which is exactly `unified-account-ratio*` in `src/hyperopen/views/account_equity/metrics.cljs`. Both produce 12.14% on the synchronized snapshot, and the screenshots showed 12.18% (Hyperopen) against 12.20% (venue), a difference explained by the seconds between them.

- Observation: The ratio's denominator can be checked against a figure the venue publishes directly, which is a cheap regression oracle worth keeping.
  Evidence: `spotClearinghouseState.tokenToAvailableAfterMaintenance` for token 0 returned `198.060063`; `spotUsdcTotal - isolatedMarginUsed - crossMaintenanceMarginUsed` computes `198.060068`.

- Observation: `spotMetaAndAssetCtxs` does **not** return an asset-context array aligned by position with `meta.universe`. Zipping them by index produces wrong prices.
  Evidence: on the 2026-08-21 snapshot `meta.universe` had 326 entries while the context array had 717, and `universe[i].name` was `@109` where `ctxs[i].coin` was `@107`. Contexts must be joined to universe entries by the context's `coin` field. Hyperopen already does this correctly; the note exists so a future reader does not "fix" it into a zip.

- Observation: Hyperopen already subscribes to the clearinghouse state of every named dex, and the market catalogue already exposes four distinct perp collateral tokens, so the widened denominator is not hypothetical.
  Evidence: probing the live store while spectating the reported wallet returned `perp-dex-clearinghouse` keys `["abcd","para","cash","io","hyna","vntl","km","flx","xyz","mkts"]` and perp `:quote` tokens `["USDC","USDE","USDH","USDT0"]`. The old record-derived set could only ever see tokens attached to a dex whose quote resolved through a loaded market; the catalogue-derived set sees all four regardless.

- Observation: Running Playwright from a worktree cannot reuse the dev server on `:8080` -- that is the *main* checkout's build, so a green run would prove nothing about the change.
  Evidence: `npx playwright test ...` failed with "http://127.0.0.1:8080 is already used". The working lane is to compile the worktree's own build (`npm run gates` already does), run `npm run css:build`, serve `resources/public` behind a small SPA-fallback server (`page.goto('/trade')` 404s on a plain static server), and run with `PLAYWRIGHT_BASE_URL=http://127.0.0.1:8090 PLAYWRIGHT_REUSE_EXISTING_SERVER=true`.

- Observation (out of scope, needs its own investigation): `portfolioMargin` accounts are shown the unified panel.
  Evidence: `src/hyperopen/api/endpoints/account/portfolio.cljs:24` maps `"portfolioMargin"` to `:unified`. The venue treats it as a third mode with its own panel title ("Portfolio Margin Summary"), its own leverage definition (`"Portfolio Account Leverage = Total Cross Positions Value / LTV-adjusted Portfolio Value."` — the denominator additionally includes non-collateral spot holdings multiplied by their loan-to-value factor), a ratio read straight off `spotClearinghouseState.portfolioMarginRatio` rather than computed client-side, and an extra "Borrow Cap Used" row. The wallet in this report is `unifiedAccount`, so this does not affect the reported symptom.

- Observation (out of scope, needs its own investigation): the classic panel's "Balance" and "Perps" rows do not match the venue's definitions.
  Evidence: the venue computes Balance as `marginSummary.accountValue - sum(unrealizedPnl)` and Perps as `marginSummary.accountValue`. `derive-account-equity-metrics` computes `base-balance` as `crossAccountValue + crossTotalMarginUsed + crossTotalNtlPos` and Perps as `base-balance + unrealizedPnl`. Applying both formulas to this wallet's base-dex figures (accountValue 53.33, totalMarginUsed 39.57, totalNtlPos 293.74, unrealized 16.45) gives 386.64 against the venue's 36.88. The formula predates commit `089073d24` and no test pins its value. This must be confirmed against a genuinely classic account before being treated as a defect.

## Decision Log

- Decision: Match the venue's arithmetic exactly for Unified Account Leverage and Perps Maintenance Margin, rather than keeping Hyperopen's whole-book definitions and explaining the difference.
  Rationale: the venue owns liquidation. A number that disagrees with the venue's is not a second opinion, it is a number a trader cannot act on. The user's request is explicitly for parity.
  Date/Author: 2026-08-21, investigation.

- Decision: Keep the substance of commit `d4144be73` by disclosing isolated exposure rather than by folding it into the headline figures.
  Rationale: `d4144be73` fixed a real trust bug — an all-isolated unified book rendered `0.00x` and `$0.00` next to a seven-figure position, and a confident zero is worse than a blank. Strict parity reintroduces that display, because the venue's own code has no guard for it (`Hd()` returns `0` and `Vd()` returns `0` for an all-isolated book). The resolution is that the *headline figures* become cross-only, matching the venue, while the panel gains an explicit secondary disclosure whenever isolated notional is non-zero — for example a muted line reading "excludes $185,747 isolated". This satisfies `trading-ui-policy.md`'s prohibition on fake zeros (the zero is no longer unqualified) without putting a number on screen that disagrees with the venue.
  Date/Author: 2026-08-21, investigation.

- Decision: Leave `unified-account-ratio*` untouched, including its choice to return nil (rendering `--`) for an all-isolated book where the venue renders `0.00%`.
  Rationale: the formula is already identical; only the all-isolated edge case differs, and there the venue's `0.00%` is the fake zero that `trading-ui-policy.md` forbids. Because the ratio is the one figure whose *tooltip* promises "greater than 95% and your portfolio may be liquidated", a `0.00%` on a book that cannot be portfolio-liquidated is not just imprecise, it contradicts the tooltip. This is a deliberate, documented one-cell divergence, and the secondary disclosure introduced in Milestone 3 covers the same ground.
  Date/Author: 2026-08-21, investigation.

- Decision: Derive the leverage denominator's collateral-token set from the market catalogue (`[:asset-selector :market-by-key]`), not from the dexes present in `[:perp-dex-clearinghouse]`.
  Rationale: the catalogue already carries one entry per perp market with `:quote` set to that dex's collateral symbol (built in `build-perp-markets` at `src/hyperopen/asset_selector/markets.cljs:1213` from the dex meta's `:collateralToken`). That is the same information the venue reads out of its `multiverse`, it is already in app state, and it requires no new fetch.
  Date/Author: 2026-08-21, investigation.

## Outcomes & Retrospective

Complete as of 2026-08-21. The panel now agrees with the venue on every row.

Verified live against `0x4096d3377ae5ade578daae8188804740c8b1da3e` by rendering the real panel in a headless browser against this worktree's own build and, in the same test run, recomputing the venue's formulas from a fresh `/info` snapshot. The panel read leverage `1.598979`, maintenance `27.540638`, isolated notional `185.430650` and ratio `12.15%`; the venue's formulas over the snapshot gave `575.4419 / 360.1946 = 1.5976` and `$27.54`, with isolated notional `185.43`. Both render `1.60x` and `$27.54`. Before the change the same account rendered `2.12x` and `$35.81` against the venue's `1.60x` and `$27.46`.

The probe also confirmed the denominator fix does real work: the market catalogue exposes four perp collateral tokens (`USDC`, `USDE`, `USDH`, `USDT0`) where the old record-derived set would have seen only the ones attached to dexes with a clearinghouse snapshot. This wallet's USDH balance is now inside the denominator, worth 2.3% of the leverage reading on its own.

Complexity went down. Two bespoke helpers disappeared — `unified-notional-usd-value` (whole-book numerator) and `unified-isolated-maintenance-margin*` together with `position-maintenance-margin` and its `1 / (2 * maxLeverage)` derivation — and were replaced by direct reads of fields the venue publishes. `known-collateral-tokens` and `unified-isolated-notional-usd-value` are added, so the helper count is flat, but the *reasoning* the file carries is smaller: nothing in it now models a margin rate the venue never asked us to model, and the one remaining derived quantity (the ratio) is a line-for-line port of the venue's own function.

What remains: the two follow-ups above, both filed in the tech-debt tracker. Neither affects this wallet.

The one deliberate divergence from the venue survives: an all-isolated unified book shows `--` for the ratio where the venue shows `0.00%`. Its leverage and maintenance figures now read `0.00x` and `$0.00` exactly as the venue does, but the panel prints "Excludes $N isolated" beneath them, so no zero on this panel stands unqualified.

## Context and Orientation

Hyperopen is a ClojureScript single-page trading front end for the Hyperliquid exchange. Application state is one big map; view namespaces under `src/hyperopen/views/**` are pure functions from that map to hiccup (vector-shaped markup rendered by Replicant).

Three terms recur below and must be clear before reading further.

A **dex** here is a perpetuals venue inside Hyperliquid. There is one base dex, addressed by an empty dex name, and a set of named "HIP-3" dexes deployed by third parties (`xyz`, `flx`, `vntl`, `hyna`, `km`, `abcd`, `cash`, `para`, `mkts`, `io` as of this writing). Each dex declares one **collateral token** — the spot token that backs positions on it. The base dex and `xyz` use USDC; `flx` uses USDH.

A **cross** position shares one margin pool with every other cross position on its dex, so they can liquidate together. An **isolated** position carries its own margin and liquidates alone. On a unified account, an isolated position's margin is still drawn from the shared spot balance, which is why it shows up inside the spot balance's `hold` field.

A **unified account** is a Hyperliquid account mode in which spot and perps balances are merged and all USDC collateral is usable as cross margin. Hyperopen learns the mode from the `userAbstraction` info endpoint and stores it at `[:account :mode]` as `:unified` or `:classic`.

The panel under discussion is built in `src/hyperopen/views/account_equity/panels.cljs`. `account-equity-view` branches on `unified-account?` and renders either `classic-account-equity-view` or `unified-account-summary-view`. Every number those two functions display is computed once, upstream, by `derive-account-equity-metrics` in `src/hyperopen/views/account_equity/metrics.cljs`, and memoized by identity on the five state buckets it reads.

The relevant helpers in that file today are `unified-notional-usd-value` (the leverage numerator), `unified-collateral-usd-value` (the leverage denominator), `unified-account-leverage*` (their quotient), `unified-cross-maintenance-margin*` and `unified-isolated-maintenance-margin*` (summed by `unified-maintenance-margin*`), and `unified-account-ratio*`. They all operate on a sequence of "records" produced by `unified-clearinghouse-state-records`, where one record is `{:dex <name or nil> :quote-token <symbol> :state <clearinghouseState map>}`, one per dex the wallet has a clearinghouse snapshot for.

The venue's implementation, recovered from its production bundle, is two functions. Written back into readable JavaScript, the leverage is:

    function unifiedAccountLeverage({mode, clearinghouseStates, multiverse, supplies, spotAssetCtxs, spotMeta, spotBalances}) {
      let numerator = 0;
      for (const state of Object.values(clearinghouseStates))
        numerator += state.crossMarginSummary.totalNtlPos;

      const collateralTokens = new Set();
      for (const dex of Object.values(multiverse)) {
        const name = tokenByIndex(dex.collateralToken, spotMeta)?.name;
        if (name) collateralTokens.add(name);
      }

      let denominator = 0;
      collateralTokens.forEach(name => {
        denominator += (spotBalanceFor(name, spotBalances)?.total ?? 0) * priceOf(name, spotAssetCtxs, spotMeta);
      });

      if (mode === "portfolioMargin")
        for (const supply of supplies)
          if (!collateralTokens.has(supply.tokenName) && supply.ltv > 0)
            denominator += (spotBalanceFor(supply.tokenName, spotBalances)?.total ?? 0) * supply.oraclePx * supply.ltv;

      return Math.max(0, numerator / (denominator + 1e-8));
    }

and the ratio is:

    function unifiedAccountRatio({multiverse, clearinghouseStates, spotBalances, spotMeta}) {
      const collateralTokenByDex = {};
      for (const dex of Object.values(multiverse))
        collateralTokenByDex[dex.pdi] = tokenByIndex(dex.collateralToken, spotMeta)?.name ?? null;

      const crossMaintenance = {}, isolatedMargin = {};
      for (const [pdi, state] of Object.entries(clearinghouseStates)) {
        const token = collateralTokenByDex[Number(pdi)];
        if (!token) continue;
        crossMaintenance[token] = (crossMaintenance[token] ?? 0) + state.crossMaintenanceMarginUsed;
        for (const {position} of state.assetPositions)
          if (position.leverage.type === "isolated")
            isolatedMargin[token] = (isolatedMargin[token] ?? 0) + position.marginUsed;
      }

      let worst = 0;
      for (const [token, maintenance] of Object.entries(crossMaintenance)) {
        const available = (spotBalanceFor(token, spotBalances)?.total ?? 0) - (isolatedMargin[token] ?? 0);
        if (available > 0) worst = Math.max(worst, maintenance / available);
      }
      return Math.min(worst, 1);
    }

The maintenance-margin row needs no function at all on the venue's side: it renders `crossMaintenanceMarginUsed` from the aggregated clearinghouse state directly.

The reported wallet is `0x4096d3377ae5ade578daae8188804740c8b1da3e`. On a synchronized snapshot taken at 2026-08-21 08:42Z it held cross positions on the base dex and on `xyz`, five isolated positions on `xyz` (TSM, BABA, EWZ, NOW, UNITREE), 352.913498 USDC and 8.281221 USDH in spot, and no balance on any other dex. That snapshot produces:

    whole-book notional  (sum of marginSummary.totalNtlPos)       761.7869
    cross-only notional  (sum of crossMarginSummary.totalNtlPos)  575.9602
    isolated notional                                             185.8267
    cross maintenance    (sum of crossMaintenanceMarginUsed)       27.6300
    isolated maintenance (sum of positionValue / (2 * maxLev))      8.3700
    spot USDC total                                               352.9135
    spot USDC + USDH at USD prices                                361.1578

    venue     leverage = 575.9602 / 361.1578 = 1.5948x  -> renders 1.59x
    hyperopen leverage = 761.7869 / 352.9135 = 2.1586x  -> renders 2.16x
    venue     maintenance = $27.63
    hyperopen maintenance = $36.00
    both      ratio       = 12.14%

The total inflation factor is 1.3535, of which 1.3226 comes from the numerator and 1.0234 from the denominator. The screenshots the user supplied were taken minutes earlier and showed 2.12x against 1.60x, a factor of 1.325 — the same defect measured through a slightly different set of marks.

## Plan of Work

Every edit in Milestones 1 through 3 lands in `src/hyperopen/views/account_equity/metrics.cljs`; Milestone 4 lands in `src/hyperopen/views/account_equity/panels.cljs`. Nothing outside the panel consumes the two figures being changed — `:unified-account-leverage` is read only by `panels.cljs`, and `:maintenance-margin` likewise, while `src/hyperopen/views/portfolio/vm.cljs` and `src/hyperopen/views/degen/widgets.cljs` read only `:unrealized-pnl`, `:account-value-display`, `:unified-account-ratio` and `:cross-margin-ratio`. That containment is what makes this safe to do in one pass.

Milestone 1 changes the leverage numerator. `unified-notional-usd-value` currently reads `[:marginSummary :totalNtlPos]` from each record. Change it to read `[:crossMarginSummary :totalNtlPos]`, and rename it to `unified-cross-notional-usd-value` so the name states the lens. Keep the per-record conversion through the record's quote token exactly as it is: that conversion was itself a fix in `d4144be73` and is correct — a dex quoting in USDH must have its notional multiplied by the USDH price before being added to a USD total. Note that `cross-notional-total`, a separate helper used only by the ratio to distinguish "no cross positions" from "cross positions worth nothing", already reads the cross field and needs no change; do not merge the two, because that one deliberately ignores quote-token mix.

Milestone 2 widens the denominator. `unified-collateral-usd-value` currently derives its token set as `(set (keep :quote-token records))`, which only sees dexes the wallet has a clearinghouse snapshot for. Replace that with a new helper — call it `known-collateral-tokens` — that walks `market-by-key`, keeps every entry whose `:market-type` is `:perp`, and collects `(normalized-token-name (:quote market))`. Union the result with the record quote tokens so that a dex present in state but somehow missing from the catalogue still contributes. Feed that union to the existing per-token `spot-total * usd-price` sum. Guard the price lookup as it is guarded today: a token with no resolvable price contributes nothing rather than a zero, and `sum-when-present` already returns nil when nothing at all resolves.

Milestone 3 changes the maintenance figure. `unified-maintenance-margin*` should return only `unified-cross-maintenance-margin*`; delete `unified-isolated-maintenance-margin*` and `position-maintenance-margin` along with the `1 / (2 * maxLeverage)` derivation and its explanatory docstring, since nothing else uses them. Keep `isolated-margin-by-token` — the ratio still needs it. Then add the disclosure the Decision Log calls for: expose a new metric key, `:isolated-notional`, computed from the existing `isolated-notional-total` helper (converted to USD per record quote token, the same way the numerator is), and render it in Milestone 4. It must be nil, not zero, when there are no isolated positions, so the panel can omit the line entirely rather than print "excludes $0.00".

Milestone 4 is presentation. In `panels.cljs`, rename the first row from "Unified Account Value" to "Portfolio Value" to match the venue, and reorder `unified-account-summary-view` to the venue's order: Unified Account Ratio, Portfolio Value, Unrealized PNL, Perps Maintenance Margin, Unified Account Leverage. Replace the two tooltip strings with the venue's own wording — `"Unified Account Leverage = Total Cross Positions Value / Total Collateral Balance."` and `"The minimum portfolio value required to keep your cross positions open"` — and keep the existing extra sentence on the ratio tooltip explaining the cross-only lens, since that sentence documents a real Hyperopen divergence rather than contradicting the venue. Colour the ratio value green below 80% and red at or above it, matching the venue's threshold, and place the gauge glyph beside it that the user's screenshot shows. Finally, render the isolated disclosure beneath the leverage row whenever `:isolated-notional` is non-nil and positive.

Milestone 5 is verification, described in full under Validation and Acceptance.

## Concrete Steps

All commands run from the repository root, which in this worktree is `/Users/barry/projects/hyperopen/.claude/worktrees/account-leverage-hl-xyz-parity-e67bda`.

A fresh worktree has no `node_modules`, and `shadow-cljs` is not on `PATH`, so every gate fails with an opaque error that is environmental rather than a code defect. Bootstrap first:

    npm run setup:worktree

`npm test` and `npm run check` invoke that guard themselves, but running it once up front makes the first failure legible.

To re-derive the ground-truth numbers at any point, take a synchronized snapshot of the venue's own state for the wallet:

    ADDR=0x4096d3377ae5ade578daae8188804740c8b1da3e
    curl -s -X POST https://api.hyperliquid.xyz/info -H 'Content-Type: application/json' \
      -d "{\"type\":\"clearinghouseState\",\"user\":\"$ADDR\"}" -o /tmp/s_main.json &
    curl -s -X POST https://api.hyperliquid.xyz/info -H 'Content-Type: application/json' \
      -d "{\"type\":\"clearinghouseState\",\"user\":\"$ADDR\",\"dex\":\"xyz\"}" -o /tmp/s_xyz.json &
    curl -s -X POST https://api.hyperliquid.xyz/info -H 'Content-Type: application/json' \
      -d "{\"type\":\"spotClearinghouseState\",\"user\":\"$ADDR\"}" -o /tmp/s_spot.json &
    curl -s -X POST https://api.hyperliquid.xyz/info -H 'Content-Type: application/json' \
      -d '{"type":"spotMetaAndAssetCtxs"}' -o /tmp/s_spotmeta.json &
    wait

Discovering which dexes a wallet actually uses, and which collateral token each declares:

    curl -s -X POST https://api.hyperliquid.xyz/info -H 'Content-Type: application/json' \
      -d '{"type":"perpDexs"}'
    curl -s -X POST https://api.hyperliquid.xyz/info -H 'Content-Type: application/json' \
      -d '{"type":"meta","dex":"flx"}'

The second returns `{"universe": [...], "marginTables": [...], "collateralToken": 360}`. Token 360 is USDH; this is the reading that motivates Milestone 2. Note that `perpDexs` does **not** carry `collateralToken`; only the per-dex `meta` call does.

After each milestone, run the unit suite alone for a fast signal:

    npm test

Before declaring the work done, run the full gate matrix, which does not short-circuit on the first failure:

    npm run gates

Then the one end-to-end spec that covers this panel:

    npx playwright test tools/playwright/test/account-equity-unified-isolated.spec.mjs

## Validation and Acceptance

Acceptance is behavioural, and the primary check is a side-by-side comparison against the venue.

First, the unit level. `test/hyperopen/views/account_equity_unified_metrics_test.cljs` currently encodes the behaviour being replaced and will fail before the change is complete and pass after it is updated. Three of its cases change meaning and must be rewritten rather than deleted, so the new contract stays pinned:

`unified-leverage-counts-isolated-positions-test` asserts 1.7x for a book of $1,700 isolated notional against $1,000 collateral with nothing cross. Under the venue's definition that book has zero cross notional, so leverage is `0.0x`. Rename it to `unified-leverage-is-cross-only-test` and assert `0.0`, and add a sibling case with both cross and isolated legs — for instance $500 cross and $1,200 isolated against $1,000 collateral — asserting `0.5` so that the isolated leg is provably excluded rather than merely absent.

`unified-maintenance-margin-counts-isolated-positions-test` asserts `58.75`, the sum of `700/80 + 1000/20`. Under the venue's definition it becomes whatever `crossMaintenanceMarginUsed` says, which in that fixture is `0.0`. Rename it to `unified-maintenance-margin-is-cross-only-test`.

`unified-maintenance-margin-is-nil-when-max-leverage-missing-test` no longer has a subject, because no maintenance rate is derived from `maxLeverage` any more. Delete it, and add in its place `unified-all-isolated-panel-discloses-excluded-notional-test`, asserting that the rendered panel contains both `0.00x` and a string naming the excluded isolated notional, which is the guarantee that replaces it.

`unified-all-isolated-panel-shows-no-fake-zeros-test` asserts `(not (contains? texts "0.00x"))`. That assertion is now wrong by design; replace it with the disclosure assertion above. The `--` assertion for the ratio stays, because the ratio's nil behaviour is unchanged.

A new case must cover Milestone 2 directly: build a state whose `market-by-key` contains a perp market on a dex quoting `USDH` while `perp-dex-clearinghouse` contains no snapshot for that dex, give the wallet a USDH spot balance, and assert that the balance lands in the leverage denominator. Without this case the widened denominator has no regression guard, since the existing fixtures are USDC-only.

`test/hyperopen/views/account_equity_view_test.cljs:209` pins the leverage tooltip verbatim and must be updated to the venue's wording.

Second, the end-to-end level. `tools/playwright/test/account-equity-unified-isolated.spec.mjs` asserts `1.70x` at line 125 and `$58.75` at line 127; both become the cross-only values for its fixture. Two traps in that spec are worth restating because they cost time when they bite: seeding account surfaces throws from `hyperopen.account.lifecycle-invariants` unless spectate mode has given the state an effective account first, so dispatch `:actions/start-spectate-mode` before seeding; and the seed must wait for the real spectate fetches to settle — `waitForIdle({quietMs: 800, timeoutMs: 12000})` — before sync is detached, or a late response silently overwrites the seed and the panel renders the real account's classic view instead.

Third, and decisively, the live comparison. Take the synchronized snapshot from Concrete Steps, then open Hyperopen's trade page in spectate mode on `0x4096d3377ae5ade578daae8188804740c8b1da3e` and compute the venue's three figures from the snapshot by hand. Acceptance is that the panel's Unified Account Leverage matches `sum(crossMarginSummary.totalNtlPos) / sum(collateralTokenSpotTotal * usdPrice)` to two decimal places, that Perps Maintenance Margin matches `sum(crossMaintenanceMarginUsed)` to the cent, and that Unified Account Ratio still matches `max over collateral tokens of crossMaintenance / (spotTotal - isolatedMarginUsed)` clamped to 1. On the 2026-08-21 08:42Z snapshot those targets are `1.59x`, `$27.63` and `12.14%`; on a later snapshot they will differ, which is why the check is against recomputed values rather than against these constants.

A useful independent cross-check on the ratio's denominator, which costs nothing: `spotClearinghouseState.tokenToAvailableAfterMaintenance` for the collateral token must equal `spotTotal - isolatedMarginUsed - crossMaintenanceMarginUsed`. If that identity breaks, the ratio's inputs are wrong regardless of what the ratio renders.

Finally, run `npm run gates` and expect a PASS row for `npm run check`, `npm test` and `npm run test:websocket`. Report the counts. Eight Playwright specs in the local suite were already red before this work — four in `trade-regressions`, three optimizer draft-menu smoke specs, and the `routes.smoke` build badge — so confirm any red by stash-verifying against a pre-change build rather than assuming this change caused it.

## Idempotence and Recovery

Every step is safe to repeat. `npm run setup:worktree` is a symlink guard that no-ops when `node_modules` is already present. The `curl` snapshots write to `/tmp` and can be retaken at will; because marks move, retaking them changes the expected numbers, which is why acceptance is defined as an identity to recompute rather than a constant to match.

The code changes are confined to two files plus their tests and one Playwright spec, with no state-shape or API-surface changes, so `git checkout -- src/hyperopen/views/account_equity/` restores the prior behaviour completely. The one new metric key, `:isolated-notional`, is additive; nothing outside the panel reads it.

If a milestone lands and the panel goes blank rather than wrong, suspect the memoization in `memoized-account-equity-metrics`, which caches on identity of five state buckets: call `reset-account-equity-metrics-cache!` before re-reading metrics in tests, as the existing helpers already do.

## Artifacts and Notes

The venue's leverage function, as shipped, minified, recovered from `https://app.hyperliquid.xyz/assets/index-CyXjSnYq.js` on 2026-08-21. It is quoted verbatim because the readable version above is a translation and a future reader may want the original to check that translation:

    function Hd(e){let{mode:t,clearinghouseStates:n,multiverse:r,supplies:i,spotAssetCtxs:a,spotMeta:o,spotBalances:s}=e,c=0;
    for(let e of Object.values(n))c+=e.crossMarginSummary.totalNtlPos;
    let l=new Set;for(let e of Object.values(r)){let t=Zi(e.collateralToken,o)?.name;t&&l.add(t)}
    let u=0;if(l.forEach(e=>{let t=oa(e,s),n=Ar(e,a,o);u+=(t?.total??0)*n}),t===`portfolioMargin`)
    for(let e of i){let t=Fr(e.tokenName);if(!l.has(t)&&e.ltv>0){let n=oa(t,s);u+=(n?.total??0)*e.oraclePx*e.ltv}}
    return Math.max(0,c/(u+1e-8))}

The row that renders the maintenance figure, showing that it is a direct read of `crossMaintenanceMarginUsed` with no isolated contribution — `v` is bound earlier as `r?.crossMaintenanceMarginUsed??0`:

    N=t=>({left:e.formatMessage({id:t}),right:`$${wr(v,2)}`,explanation:e.formatMessage({id:`maintenance.margin.explanation`})})

The row list for the unified panel, which fixes both the order and the labels for Milestone 4:

    ue=[{left:Z({id:se}), right:<ratio with gauge icon, coloured>, explanation:ce},
        F(`portfolio.value`), M /* unrealized pnl */,
        ...S?[]:[{left:Z({id:`borrow.cap.used`}), ...}],
        N(`perp.maintenance.margin`), P(le,j,V)]

with `se`/`ce` resolving to `unified.account.ratio` / `unified.account.ratio.explanation` and `le`/`V` to `unified.account.leverage` / `unified.account.leverage.explanation` when the account is `unifiedAccount`, and to the `portfolio.*` variants when it is `portfolioMargin`. The colour rule is `R = ae > .8 ? red_500 : green_300`, and the glyph is chosen by the same threshold.

The live snapshot that anchors every number in this plan, taken 2026-08-21 08:42Z:

    base dex   marginSummary.totalNtlPos   295.651034   crossMarginSummary.totalNtlPos   295.651034   crossMaintenanceMarginUsed  19.673326
    xyz  dex   marginSummary.totalNtlPos   466.135880   crossMarginSummary.totalNtlPos   280.309150   crossMaintenanceMarginUsed   7.959243
    spot       USDC total 352.913498 @ $1.00   USDH total 8.281221 @ $0.99554
    isolated on xyz: TSM, BABA, EWZ, NOW, UNITREE — 185.83 notional, 125.35 marginUsed

    venue     leverage = (295.651034 + 280.309150) / (352.913498 + 8.244287) = 1.5948x -> 1.59x
    hyperopen leverage = (295.651034 + 466.135880) / 352.913498                = 2.1586x -> 2.16x
    venue     maintenance = 19.673326 + 7.959243 = $27.63
    hyperopen maintenance = 27.632569 + 8.370000 = $36.00

## Interfaces and Dependencies

No new libraries or services. Everything needed is already in application state: `[:webdata2 :clearinghouseState]` for the base dex, `[:perp-dex-clearinghouse]` for named dexes, `[:spot :clearinghouse-state :balances]` for spot totals, and `[:asset-selector :market-by-key]` for per-dex collateral symbols and prices.

In `src/hyperopen/views/account_equity/metrics.cljs`, the following private functions must exist at the end of Milestone 3, with these meanings:

    (defn- known-collateral-tokens [market-by-key records] ...)
    ;; => set of normalized token symbols: every :quote of every :perp market in
    ;;    the catalogue, unioned with the :quote-token of every record.

    (defn- unified-cross-notional-usd-value [records balance-row-by-token market-by-key] ...)
    ;; => USD sum of each record's crossMarginSummary.totalNtlPos, converted
    ;;    through that record's quote token. nil when nothing resolves.

    (defn- unified-collateral-usd-value [records balance-row-by-token market-by-key] ...)
    ;; => USD sum of spot :total-balance * price over known-collateral-tokens.

    (defn- unified-isolated-notional-usd-value [records balance-row-by-token market-by-key] ...)
    ;; => USD sum of |positionValue| over isolated positions. nil when there are
    ;;    none, so the panel can omit the disclosure line entirely.

`unified-isolated-maintenance-margin*` and `position-maintenance-margin` must no longer exist. `unified-account-ratio*`, `isolated-margin-by-token`, `cross-notional-total` and `isolated-notional-total` must survive unchanged.

`derive-account-equity-metrics` must return, for a unified account, `:unified-account-leverage` as the cross-only quotient, `:maintenance-margin` as `unified-cross-maintenance-margin*` alone, `:unified-account-ratio` exactly as today, and a new `:isolated-notional`. It must keep the `(if unified? ... classic)` shape rather than an `(or ... classic)` fallback chain: an `or` here re-manufactures the classic cross-only formula whenever a unified figure legitimately resolves to nil or zero, which is precisely the failure that commit `d4144be73` had to unwind.

In `src/hyperopen/views/account_equity/panels.cljs`, `unified-account-summary-view` must accept the new `:isolated-notional` key alongside the existing ones and render it only when it is a positive number.

---

Revision note, 2026-08-21: implementation completed against this plan as written; no milestone was re-scoped. `Progress` was checked off with timestamps, `Surprises & Discoveries` gained the live-probe collateral-token finding and the worktree Playwright lane, and `Outcomes & Retrospective` was written with the live parity evidence. One planned test rename was adjusted during implementation: `unified-all-isolated-panel-shows-no-fake-zeros-test` was replaced by two cases rather than one -- `unified-all-isolated-panel-discloses-excluded-notional-test` for the disclosure and `unified-panel-omits-the-disclosure-without-isolated-positions-test` for its absence -- because a disclosure that fires on an empty set would itself be a fake number. `unified-account-summary-aggregates-named-dex-clearinghouse-states-test` in `test/hyperopen/views/account_equity_view_test.cljs` also had to move from 3.5/0.375 to 1.0/0.125; the plan did not name it because it lives in the other test namespace, and its absence from the plan cost one red run.
