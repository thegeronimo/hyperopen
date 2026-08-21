# Bring the classic Account Equity panel to parity with the Hyperliquid frontend

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md` (the authoring contract) and `/hyperopen/docs/PLANS.md` (the public planning entry point).

## Purpose / Big Picture

Hyperopen's trade page shows one of two account panels. Accounts Hyperliquid reports as unified get "Unified Account Summary"; everyone else gets "Account Equity" plus a "Perps Overview" section. The unified panel was brought to parity with the venue on 2026-08-21. The classic panel was not, and it is in worse shape.

Two live accounts show what a trader sees today. On `0x68bd85dc5c94a5511e959d299617eb19cd26c298`, a classic account holding one cross HYPE position, our panel prints a Balance of **$114,106.23** and Perps of **$76,503.23** where the venue prints **$67,474.53** and **$29,871.53** — a $46,631 overstatement of money the trader does not have. On `0xb9aebb46919bccbf210537a1f2173690d9ee7af7`, a classic account whose entire book lives on the `xyz` dex, our panel prints **$0.00 Balance, $0.00 Maintenance Margin, 0.00% Cross Margin Ratio and 0.00x Cross Account Leverage** against a real $155,901 of perps equity carrying $45,766 of notional and $1,144 of maintenance margin. That is the same confident-zero failure that was fixed for unified accounts in commit `d4144be73`, still live on the classic path, and it hides genuine liquidation risk.

After this change, opening `https://app.hyperliquid.xyz/trade` and Hyperopen's trade page side by side for either wallet shows the same value, to the displayed two decimal places, on every row: Account Value, Spot, Perps, Balance, Unrealized PNL, Cross Margin Ratio, Maintenance Margin and Cross Account Leverage. The panel's own rows also reconcile — Account Value equals Spot plus Perps — which they do not today.

The work carries a second, equally important obligation. The metrics function being edited serves **both** panels, so a careless change here silently un-fixes the unified parity shipped hours earlier. Milestone 0 therefore builds an executable model of the venue's formulas and a fixture matrix covering both panels *before* any behaviour changes, so every later milestone is measured against the venue rather than against itself.

## Context References

Public refs:

- Direct user request, 2026-08-21, following the unified-panel parity work: "I want you to create an execution plan now to address this issue that you just brought up with me ... because it sounds like this has a larger blast radius, I want you to do more extensive testing to ensure there's no regressions and that the existing features you've already built don't regress as well."
- The debt entry this plan retires: `/hyperopen/docs/exec-plans/tech-debt-tracker.md`, entry beginning "The classic Account Equity panel's \"Balance\" and \"Perps\" rows do not match the venue's definitions."

Repo artifacts:

- `/hyperopen/docs/exec-plans/completed/2026-08-21-unified-account-summary-hl-parity.md` — the unified-panel parity work. Read its "Context and Orientation" for the venue's data shapes; this plan repeats what it needs but that plan carries the fuller decompiled listings.
- Commit `dc2ada72d` ("fix(trade): match the venue's unified account leverage and maintenance margin") — the change whose behaviour must not regress.
- `src/hyperopen/views/account_equity/metrics.cljs` — `derive-account-equity-metrics` computes every number for both panels.
- `src/hyperopen/views/account_equity/panels.cljs` — `classic-account-equity-view` renders the rows this plan fixes.
- `src/hyperopen/views/account_info/projections/balances.cljs` — `build-balance-rows` produces the rows behind Account Value and Spot, including one perps row per named dex.
- `src/hyperopen/views/account_info/projections/positions.cljs` — `collect-positions` already aggregates positions across every dex, which is why Unrealized PNL is whole-account today while everything beside it is base-dex only.
- `src/hyperopen/views/portfolio/vm/equity.cljs` — `perp-account-equity` consumes `:perps-value` and `:cross-account-value` as fallbacks.
- `src/hyperopen/views/degen/widgets.cljs` — consumes `:cross-margin-ratio` and `:account-value-display`.

Local scratch refs (non-authoritative):

- None.

## Progress

- [x] (2026-08-21 09:40Z) Confirmed the defect on a real classic account with an open cross book, `0x68bd85dc5c94a5511e959d299617eb19cd26c298`, by computing both formulas from its live `clearinghouseState`.
- [x] (2026-08-21 09:45Z) Found the more severe named-dex case, `0xb9aebb46919bccbf210537a1f2173690d9ee7af7`, where the classic panel renders four separate fake zeros over a six-figure book.
- [x] (2026-08-21 09:50Z) Recovered the venue's aggregation function `HM()` from its production bundle, which settles what "the clearinghouse state" means for every classic row.
- [x] (2026-08-21 09:55Z) Mapped the blast radius of `:base-balance`, `:perps-value`, `:cross-account-value`, `:cross-margin-ratio`, `:cross-account-leverage` and `:maintenance-margin` across the codebase.
- [x] (2026-08-21 11:20Z) Milestone 0 — venue oracle, 13-fixture matrix and the both-panel parity harness landed with no behaviour change. `npm test` ended at **5,933 tests / 32,739 assertions / 30 failures**, every failure in the new harness and every one of them classic. The list, which is the bug stated as test output:
  - base-dex cross **long**: Perps 5,600 vs 1,000; Balance 5,400 vs 800.
  - base-dex cross **short**: Perps 6,000 vs 2,000; Balance 7,500 vs 3,500.
  - **named-dex only**: Spot 156,001.46 vs 100; Perps 6,743.65 vs 155,901.46; Balance 0 vs 149,157.81; Maintenance 0 vs 1,144.15; Cross Margin Ratio nil vs 0.73%; Cross Account Leverage nil vs 0.29x.
  - **two collateral tokens**: Account Value 1,750 vs 3,700; Spot 750 vs 700; Perps 3,324 vs 3,000; Balance 3,200 vs 2,876; Ratio 5.00% vs 8.33%; Maintenance 50 vs 250.
  - **cross plus isolated**: Perps 6,500 vs 3,000; Balance 6,400 vs 2,900.
  - **degenerate, `crossMarginSummary` only**: Perps, Balance and Unrealized PNL invented from a summary that is not there.
  - **degenerate, numeric wire fields**: Perps 3,930 vs 1,200; Balance 3,840 vs 1,110.
  - the reconciliation invariant failed on 7 of the 9 classic fixtures.
  All ten unified assertions passed on the first run, against an oracle that shares no code with them — independent confirmation that `dc2ada72d` is at parity.
- [x] (2026-08-21 11:45Z) Milestone 1 — `aggregate-clearinghouse-usd` added with six direct tests, and `clearinghouse-state-records` made unconditional.
- [x] (2026-08-21 11:55Z) Milestone 2 — Perps is the aggregate account value; Balance is that net of unrealized PNL. `cross-derived-balance` and `fallback-balance` deleted.
- [x] (2026-08-21 11:55Z) Milestone 3 — Cross Margin Ratio, Maintenance Margin and Cross Account Leverage all read the aggregate.
- [x] (2026-08-21 12:05Z) Milestone 4 — `unrealized-from-summary` deleted, Spot stopped counting named-dex perps rows, classic Account Value derived as Spot + Perps. `npm test` green at **5,939 tests / 32,768 assertions / 0 failures**.
- [x] (2026-08-21 13:10Z) Milestone 5 — `account-equity-classic-named-dex.spec.mjs` passes and, stash-verified against the pre-change build, fails exactly as the bug predicts (Spot $156,001.46, Perps $6,743.65, Balance $0.00, Maintenance $0.00, Ratio `--`, Leverage `--`). `account-equity-venue-parity.live.spec.mjs` passes against both anchor wallets live. Full gates green.

## Surprises & Discoveries

- Observation: The venue does not compute the classic rows from the base dex. It sums every dex into one synthetic clearinghouse state first, converting each dex's figures to USD through that dex's collateral token, and derives every row from the sum.
  Evidence: recovered from `https://app.hyperliquid.xyz/assets/index-CyXjSnYq.js`. `HM()` is the aggregator and `UM()` is the hook that feeds the panel; the panel binds `r` from it and reads `r.marginSummary`, `r.crossMarginSummary`, `r.crossMaintenanceMarginUsed` and `r.assetPositions`. Written back into readable JavaScript:

      function aggregateClearinghouseStates(statesByPdi, multiverse, spotAssetCtxs, spotMeta) {
        let marginSummary = zeroSummary(), crossMarginSummary = zeroSummary();
        let crossMaintenanceMarginUsed = 0, withdrawable = 0, assetPositions = [];
        for (const dex of Object.values(multiverse)) {
          const state = statesByPdi[dex.pdi];
          if (!state) continue;
          const price = tokenUsdPrice(dex.collateralToken, spotAssetCtxs, spotMeta);
          marginSummary      = addSummary(marginSummary,      state.marginSummary,      price);
          crossMarginSummary = addSummary(crossMarginSummary, state.crossMarginSummary, price);
          crossMaintenanceMarginUsed += state.crossMaintenanceMarginUsed * price;
          withdrawable               += state.withdrawable * price;
          assetPositions.push(...state.assetPositions);
        }
        return {assetPositions, marginSummary, crossMarginSummary, crossMaintenanceMarginUsed, withdrawable};
      }

  Every field is USD-converted per dex before summing, exactly as the unified leverage numerator already does.

- Observation: The classic panel today mixes two scopes in a single arithmetic expression, which is how it produces numbers that contradict each other on screen.
  Evidence: `collect-positions` in `src/hyperopen/views/account_info/projections/positions.cljs` concatenates base-dex and named-dex positions, so `:unrealized-pnl` is whole-account. `cross-derived-balance` reads only `[:webdata2 :clearinghouseState :crossMarginSummary]`, so it is base-dex only. `perps-value` is their sum. On `0xb9aebb46919bccbf210537a1f2173690d9ee7af7` that yields a panel reading "Balance $0.00, Unrealized PNL +$6,743.65, Perps $6,743.65" for an account whose perps equity is $155,901.46.

- Observation: Our tooltip copy is already the venue's, verbatim. Only the arithmetic diverges, which makes the bug quieter than it should be — the Perps row promises "Balance + Unrealized PNL" and delivers exactly that, over a Balance that is wrong.
  Evidence: `en-US-Dpwyw3I6.js` gives `"balance.explanation": "Total Net Transfers + Total Realized Profit + Total Net Funding Fees"` and `"account.equity.explanation": "Balance + Unrealized PNL (approximate account value if all positions were closed)"`; both strings appear unchanged in `classic-account-equity-view`.

- Observation: `cross-derived-balance` is not merely mis-scoped, it is dimensionally meaningless. It adds a dollar equity, a dollar margin requirement and a dollar notional together.
  Evidence: the venue's identity is `marginSummary.accountValue = totalRawUsd + Σ signed positionValue`, where `totalNtlPos` is the sum of absolute notionals. On `0x68bd85dc…` (a short) `accountValue 29,871.53 = totalRawUsd 106,448.53 − totalNtlPos 76,577.00`; on the unified wallet from the prior plan (longs) `53.33 = −240.41 + 293.74`. Neither identity contains `accountValue + totalMarginUsed + totalNtlPos`. The formula predates commit `089073d24`, which only moved it between files, and no test pins its value.

- Observation: The error's size and even its sign depend on position direction, so a spot check on a long-only account can look almost right while a short account is wildly wrong.
  Evidence: our Balance exceeds the venue's by `totalMarginUsed + totalNtlPos + Σ unrealizedPnl`. On the short anchor that is `7,657.70 + 76,577.00 + (−37,603.00) = 46,631.70`, a 1.7x overstatement. A long book with the same notional and a positive PNL of the same magnitude would be overstated by 1.2x more. Any fixture matrix must contain both a long and a short case or it will under-detect regressions.

- Observation: The venue subscribes to all dexes with a single websocket topic; Hyperopen fans out one `clearinghouseState` subscription per dex.
  Evidence: the bundle builds `{type: "allDexsClearinghouseState", user}`, while `sync-perp-dex-clearinghouse-subscriptions!` in `src/hyperopen/websocket/user_runtime/subscriptions.cljs` issues one subscription per named dex plus the base dex. This is out of scope here — the data we need is already in state either way — but it is worth knowing before anyone tries to reduce subscription count.

- Observation: The blast radius of the two figures being changed is narrow, but not empty, and one consumer masks the bug rather than propagating it.
  Evidence: `:base-balance` has no consumer outside `panels.cljs`. `:perps-value` reaches `account-value-display` only when `portfolio-value` is nil (see `derive-account-value-display`), and reaches `perp-account-equity` in `src/hyperopen/views/portfolio/vm/equity.cljs` only as a fourth-choice fallback behind `marginSummary.accountValue`, `crossMarginSummary.accountValue` and `:cross-account-value` — so the Portfolio page already shows the right number and will not move. `:cross-margin-ratio` reaches `src/hyperopen/views/degen/widgets.cljs`, and `:cross-account-value` reaches `perp-account-equity` as a third-choice fallback; both will move once Milestone 3 lands, and that is intended.

- Observation: The Spot row had a second defect nobody had written down. `spot-equity` excluded the balance row keyed `perps-usdc` — the base dex's perps equity — but not the `perps-usdc-<dex>` row that `named-dex-perps-rows` emits for every named dex. A classic account trading on a HIP-3 dex therefore reported that dex's entire perps equity as a spot holding.
  Evidence: on the named-dex fixture the Spot row read $156,001.46 against $100 of actual spot. This is why the panel's Account Value looked roughly right while the rows beneath it did not: Account Value came from `portfolio-usdc-value`, which sums every row including the perps ones, so it accidentally landed near Spot + Perps by double-counting in two places at once.

- Observation: Once the aggregate existed, the classic and unified Maintenance Margin rows turned out to be the same expression, and the `(if unified? ...)` branch that had separated them collapsed.
  Evidence: `unified-cross-maintenance-margin*` summed `crossMaintenanceMarginUsed` across records, USD-converted per record — which is precisely `(:maintenance-margin aggregate)`. The same held for the unified leverage numerator and `(:cross-total-ntl-pos aggregate)`. Both wrappers were deleted and `:maintenance-margin` became unconditional. The venue makes the same choice: one aggregation feeds both of its panels.

- Observation: Deleting `unrealized-from-summary` outright would have regressed a funded-but-flat account from `$0.00` to `--`.
  Evidence: with no positions, `unrealized-from-positions` is nil, and the deleted fallback was the only thing putting a number on that row. A flat book's unrealized PNL is genuinely zero and the venue prints `$0.00`. The replacement signal is whether any record's state actually carries an `assetPositions` list: an empty list means the book is flat, an absent key means we have not been told. The degenerate fixture with no `assetPositions` key pins the difference.

- Observation: `metrics.cljs` crossed the repository's 500-line namespace ceiling as soon as the aggregate landed, at 509 lines.
  Evidence: `npm run lint:namespace-sizes` fails above 500 (`dev/check_namespace_sizes.clj`). The namespace was carrying three separable jobs, so it was split rather than granted an exception.

## Decision Log

- Decision: Build the regression harness in Milestone 0, before any behaviour change, rather than adding tests alongside each fix.
  Rationale: the function being edited serves both panels, and the unified behaviour it now encodes was shipped hours earlier and is not yet load-bearing in anyone's habits. A harness written after the fact tends to encode whatever the code does; a harness written first, from the venue's own source, encodes what the code *should* do. This is also what the requester asked for in as many words.
  Date/Author: 2026-08-21, planning.

- Decision: Model the venue with an executable oracle in the test tree rather than hand-computed expected values.
  Rationale: hand-computed constants rot the moment a fixture changes and say nothing about cases nobody thought to enumerate. An oracle transcribed from the venue's own decompiled source, and deliberately structured the way the venue structures it — aggregate first, then derive — is independent of our implementation, which derives per record and never materialises an aggregate. Agreement between two differently-shaped implementations is real evidence; agreement between a formula and a copy of itself is not.
  Date/Author: 2026-08-21, planning.

- Decision: Fix the named-dex aggregation (Milestone 3) in the same plan as the Balance and Perps formulas, rather than filing it separately.
  Rationale: they are the same expression. Correct Balance is aggregate `accountValue` minus aggregate unrealized PNL; fixing the formula while leaving the scope base-dex-only would replace one wrong number with a different wrong number on any multi-dex account. The aggregation defect is also the more severe of the two — it renders four fake zeros over a live six-figure book — so shipping the formula fix without it would be shipping the smaller half.
  Date/Author: 2026-08-21, planning.

- Decision: Keep the "Account Value" row, which the venue's classic panel does not have.
  Rationale: it is an addition, not a divergence — the venue shows Spot and Perps and leaves the reader to add them. Removing it would be a regression in usefulness with no parity benefit. It does, however, get a new obligation: it must equal Spot plus Perps, which today it does not, because it is computed from balance rows while Perps is computed from the summary. Milestone 4 pins that as an invariant.
  Date/Author: 2026-08-21, planning.

- Decision: Gate the live-parity spec behind an environment variable and keep it out of CI.
  Rationale: it depends on the public Hyperliquid API and on two wallets continuing to hold the shapes they hold today. That makes it excellent verification and terrible CI. The repo already uses this pattern for `RUN_BROWSER_INSPECTION_SMOKE`. The fixture matrix, which is hermetic, is what protects the build.
  Date/Author: 2026-08-21, planning.

- Decision: Split `account_equity/metrics.cljs` into `pricing.cljs` (USD conversion and the venue's aggregation), `unified.cljs` (the unified-only figures) and `metrics.cljs` (the one derivation both panels read), rather than taking a namespace-size exception.
  Rationale: the 500-line ceiling was the prompt, not the reason. The file already held three jobs that only ever spoke to each other through a handful of names, and the aggregate belongs with the conversion it depends on rather than beside the panel arithmetic that consumes it. The split is 157 / 188 / 213 lines with a single public seam each, and `token-price-usd` is re-exported from `metrics` so existing callers are untouched.
  Date/Author: 2026-08-21, implementation.

- Decision: Do not adopt the venue's `+ 1e-8` divide-by-zero guard for the two classic quotients; declare the difference as a fixture divergence instead.
  Rationale: the guard makes a flat account read `0.00%` and `0.00x`, which is truthful for that account — but the same guard prints an equally confident `0.00x` for any account whose cross equity we failed to read, which is the exact failure this plan exists to remove. `safe-div` returning nil keeps the two cases distinguishable at the cost of a `--` on a genuinely flat book. The divergence is declared on the flat fixture with that reasoning attached, so the choice is visible rather than accidental.
  Date/Author: 2026-08-21, implementation.

- Decision: Derive the classic Account Value row as Spot + Perps rather than from `portfolio-usdc-value`.
  Rationale: the row exists to reconcile the two rows beneath it, and summing balance rows cannot do that. Balance rows count a named dex's perps equity as its own row and record it in that dex's collateral token unconverted, so a dex settling in anything other than a dollar lands in a USD total at face value. Summing the two figures the panel already displays makes the invariant true by construction. The unified panel keeps `portfolio-usdc-value`, which is correct there: its balance rows are merged into spot-only.
  Date/Author: 2026-08-21, implementation.

## Outcomes & Retrospective

Both classic defects are fixed and both panels are now measured against an executable model of the venue rather than against themselves.

The formula defect: Perps is the aggregate `marginSummary.accountValue` and Balance is that net of unrealized PNL, which is what our own tooltips already promised. `cross-derived-balance` -- a sum of a dollar equity, a dollar margin requirement and a dollar notional, whose meaning nobody could state -- is gone, along with `fallback-balance` and `unrealized-from-summary`.

The scope defect: every classic row now reads a single synthetic clearinghouse state summed across every dex, each converted to USD through its own collateral token, exactly as the venue's `HM()` does. The account that rendered four confident zeros over a $155,901 book now renders $149,157.81 Balance, $155,901.46 Perps, $1,144.15 Maintenance Margin, 0.73% Cross Margin Ratio and 0.29x Cross Account Leverage.

Two defects nobody had written down turned up on the way and are fixed here as well. `spot-equity` counted the `perps-usdc-<dex>` balance row of every named dex as a spot holding, so the Spot row on a HIP-3 account reported that dex's entire perps equity. And the classic Account Value row was summed from balance rows rather than from the two rows beneath it, so it could not reconcile with them and silently absorbed a named dex's collateral token into a USD total unconverted.

**Live verification, 2026-08-21.** `RUN_VENUE_PARITY=1` against both anchors, comparing the rendered panel with the venue's formulas recomputed from a snapshot taken in the same run. `0x68bd85dc…` rendered Perps $29,683.64 against a recomputed $29,705.64; `0xb9aebb46…` rendered Perps $155,868.78 against $155,869.30 and Maintenance Margin $1,144.62 against $1,144.45. The residual differences are drift -- the venue pushes user state on events rather than continuously, so the panel's marks can be seconds old -- which is why the spec also asserts the two identities that hold between the rendered figures alone, at whatever instant they were marked.

**On complexity.** The source tree grew by one concept and shed two. `metrics.cljs` went from 473 lines doing three jobs to 167 doing one, with `pricing.cljs` (188) owning USD conversion and the venue's aggregation and `unified.cljs` (178) owning the figures only a unified account has. Two near-duplicate helpers disappeared once the aggregate existed -- `unified-cross-maintenance-margin*` and `unified-cross-notional-usd-value` were each a special case of it -- and with them the `(if unified? ...)` branch on Maintenance Margin, because both panels turn out to render the same figure. Net source lines are up by about 60; the number of distinct ideas is down by one.

The test tree grew by roughly 1,000 lines of ClojureScript plus two browser specs, and that is the point of the exercise rather than a cost. The suite went from 5,929 tests / 32,591 assertions to 5,939 / 32,768.

**What the harness bought.** Milestone 0 ended with 30 failures, every one classic, and every unified assertion green on its first run against an oracle that shares no code with the implementation -- including its number parser. That is independent evidence that `dc2ada72d` was already at parity, which no amount of re-reading our own code could have established. It also caught two things the milestones had not anticipated: the Spot row's double-count, which showed up as a fixture failure nobody had predicted, and the fact that deleting `unrealized-from-summary` would have regressed a funded-but-flat account from `$0.00` to `--`.

**What would have gone wrong without it.** Milestones 2 and 3 rewrote the expression that computes every number on both panels. The unified work had shipped hours earlier, was not yet load-bearing in anyone's habits, and had exactly two focused tests plus one browser spec guarding it. A regression there would have been invisible until someone compared the panel to the venue again.

## Context and Orientation

Read this section even if you know the repository; it defines the terms the rest of the plan uses.

Hyperopen is a ClojureScript single-page trading front end for the Hyperliquid exchange, built with shadow-cljs and rendered by Replicant. Application state is one large map. View namespaces under `src/hyperopen/views/**` are pure functions from that map to hiccup, which is markup expressed as nested Clojure vectors.

A **dex** is a perpetuals venue inside Hyperliquid. There is one base dex, addressed by an empty dex name, and a set of third-party "HIP-3" dexes (`xyz`, `flx`, `vntl`, `hyna`, `km`, `abcd`, `cash`, `para`, `mkts`, `io` at the time of writing). Each dex declares one **collateral token**, the spot token that backs positions on it: the base dex and `xyz` use USDC, `flx` uses USDH. A dex's `clearinghouseState` reports its figures in its own collateral token, so figures from two dexes cannot be added without converting each to USD first.

A **cross** position shares one margin pool with the other cross positions on its dex. An **isolated** position carries its own margin and liquidates alone.

An **account mode** comes from Hyperliquid's `userAbstraction` info endpoint. Hyperopen normalises it in `src/hyperopen/api/endpoints/account/portfolio.cljs` to `:unified` (for `unifiedAccount` and `portfolioMargin`) or `:classic` (for `dexAbstraction`, `default` and `disabled`), and stores it at `[:account :mode]`. **This plan is about `:classic`.** Everything the unified panel does is already correct and must stay that way.

The panel lives in `src/hyperopen/views/account_equity/panels.cljs`. `account-equity-view` branches on `unified-account?` and calls either `unified-account-summary-view` or `classic-account-equity-view`. The classic view renders two sections. "Account Equity" shows Account Value, Spot and Perps. "Perps Overview" shows Balance, Unrealized PNL, Cross Margin Ratio, Maintenance Margin and Cross Account Leverage.

Every number on both panels comes from `derive-account-equity-metrics` in `src/hyperopen/views/account_equity/metrics.cljs`, memoized by identity on five state buckets. The classic-relevant bindings today are:

    clearinghouse-state      = [:webdata2 :clearinghouseState]            ; BASE DEX ONLY
    cross-account-value      = crossMarginSummary.accountValue            ; base dex only
    cross-total-ntl-pos      = crossMarginSummary.totalNtlPos             ; base dex only
    cross-total-margin-used  = crossMarginSummary.totalMarginUsed         ; base dex only
    maintenance-margin       = crossMaintenanceMarginUsed                 ; base dex only
    positions                = collect-positions(webdata2, perp-dex-clearinghouse)  ; ALL DEXES
    unrealized-pnl           = Σ positions unrealizedPnl                  ; all dexes
    cross-derived-balance    = cross-account-value + cross-total-margin-used + cross-total-ntl-pos
    base-balance             = cross-derived-balance or (totalRawUsd or perps-row total)
    perps-value              = base-balance + unrealized-pnl
    cross-margin-ratio       = maintenance-margin / cross-account-value
    cross-account-leverage   = cross-total-ntl-pos / cross-account-value

The venue's equivalents, all computed from the aggregate described in `Surprises & Discoveries`:

    Balance                = marginSummary.accountValue − Σ unrealizedPnl
    Perps                  = marginSummary.accountValue
    Spot                   = total spot portfolio value in USD
    Unrealized PNL         = Σ unrealizedPnl
    Cross Margin Ratio     = crossMaintenanceMarginUsed / crossMarginSummary.accountValue
    Maintenance Margin     = crossMaintenanceMarginUsed
    Cross Account Leverage = crossMarginSummary.totalNtlPos / crossMarginSummary.accountValue

The last three already match ours in shape; they diverge only in scope, which Milestone 3 addresses. The first two diverge in both.

Two live wallets anchor the work. Neither is a fixture — they are real accounts whose state will drift, so the plan states the shape they demonstrate rather than treating their numbers as constants.

`0x68bd85dc5c94a5511e959d299617eb19cd26c298` is `disabled` (therefore classic), trades only on the base dex, and holds one cross HYPE short. As of 2026-08-21 09:40Z its `clearinghouseState` reported `accountValue 29,871.526234`, `totalNtlPos 76,577.0`, `totalRawUsd 106,448.526234`, `totalMarginUsed 7,657.7`, `crossMaintenanceMarginUsed 3,828.85`, and a single position with `positionValue 76,577.0` and `unrealizedPnl −37,603.0`. Its spot holdings are worth about $4.07. The venue therefore shows Balance $67,474.53 and Perps $29,871.53; we show $114,106.23 and $76,503.23. This wallet exercises the formula defect in its largest form, because the negative PNL and the large notional pull in the same direction.

`0xb9aebb46919bccbf210537a1f2173690d9ee7af7` is also `disabled`, has an empty base dex, and carries its whole book on `xyz`: `accountValue 155,901.461224`, `totalNtlPos 45,765.91629`, `totalMarginUsed 15,255.30543`, `crossMaintenanceMarginUsed 1,144.147907`, one position with `unrealizedPnl +6,743.65`. The venue shows Perps $155,901.46, Balance $149,157.81, Maintenance Margin $1,144.15, Cross Margin Ratio 0.73% and Cross Account Leverage 0.29x. We show Balance $0.00, Perps $6,743.65, Maintenance Margin $0.00, Cross Margin Ratio 0.00% and Cross Account Leverage 0.00x. This wallet exercises the scope defect, and it is the reason Milestone 3 is not optional.

## Plan of Work

**Milestone 0 — the harness.** Nothing in `src/**` changes. Three new test files appear.

`test/hyperopen/views/account_equity_venue_oracle.cljs` is a transcription of the venue's formulas, written in the venue's shape: a function that folds a map of per-dex clearinghouse states plus a collateral-token-and-price lookup into one aggregate state, and functions that derive each row from that aggregate. It must not call anything from `hyperopen.views.account-equity.*`. Its only job is to say what the venue would print, given the same inputs. The decompiled source it transcribes is quoted in `Surprises & Discoveries` above and in `Artifacts and Notes` below.

`test/hyperopen/views/account_equity_fixtures.cljs` holds named state maps, each a complete `state` argument for `account-equity-metrics`, together with the raw per-dex inputs the oracle needs. Build them from one constructor so a fixture cannot drift between what the metrics see and what the oracle sees — the constructor takes per-dex clearinghouse states, spot balances, market entries and an account mode, and returns both the app-state map and the oracle input. The matrix must include, at minimum: classic flat; classic base-dex single cross long; classic base-dex single cross short; classic named-dex only; classic base plus named dex; classic with an isolated position present; unified all-cross; unified all-isolated; unified mixed; unified with two collateral tokens (USDC and USDH) where one dex settles in the second; and degenerate shapes — absent `marginSummary`, absent `crossMarginSummary`, absent `assetPositions`, absent spot state, numeric fields arriving as numbers rather than strings, and an empty `:perp-dex-clearinghouse` map.

`test/hyperopen/views/account_equity_parity_test.cljs` is the table-driven test. For each fixture it computes `account-equity-metrics` and the oracle's expectations and asserts they agree on every row both panels display. Where our implementation deliberately diverges — the unified ratio returning nil on an all-isolated book where the venue returns 0 — the divergence is expressed as an explicit exception in the test with a comment naming the reason, not by omitting the assertion.

Milestone 0 ends with `npm test` green and, crucially, with the classic fixtures **failing** the parity assertions. Those failures are the bug. Record the failure list in `Progress` before moving on; a Milestone 0 that passes everywhere means the oracle is wrong or the matrix is too thin.

**Milestone 1 — the aggregate.** Add a pure helper to `src/hyperopen/views/account_equity/metrics.cljs` that mirrors the venue's `HM()`: given the same records `unified-clearinghouse-state-records` already builds — one per dex, each carrying its quote token and state — plus the balance-row and market lookups already in scope, return one aggregate map with `:account-value`, `:cross-account-value`, `:cross-total-ntl-pos`, `:cross-total-margin-used`, `:total-raw-usd` and `:maintenance-margin`, each summed in USD through its record's quote token. Note that `unified-clearinghouse-state-records` is currently only built when the account is unified; Milestone 1 makes it unconditional, which is a pure refactor because the function reads nothing mode-specific.

Follow the nil discipline the file already uses: `sum-when-present` returns nil when no record contributes, and a record whose quote token has no resolvable price contributes nothing rather than a zero. A row with no derivable value must render `--`, never `$0.00`. This is the rule `/hyperopen/docs/agent-guides/trading-ui-policy.md` line 62 states and the reason the named-dex wallet's four zeros are a defect rather than a rounding issue.

The helper is not wired into anything in this milestone. Its tests are direct: build the aggregate from a two-dex fixture where one dex settles in USDH, assert each field equals the USD-converted sum, and assert nil propagation on the degenerate shapes.

**Milestone 2 — Balance and Perps.** Replace `cross-derived-balance` and the `perps-value` expression. Perps becomes the aggregate `:account-value`. Balance becomes that minus `unrealized-pnl`. Delete `cross-derived-balance` and `fallback-balance` if nothing else needs them; keep whatever fallback is genuinely required for a state that has no clearinghouse data at all, and make sure that fallback yields nil rather than zero. The classic fixtures for the base-dex long and short cases must now agree with the oracle.

**Milestone 3 — scope.** Point `cross-account-value`, `cross-total-ntl-pos` and `maintenance-margin` at the aggregate's fields instead of `[:webdata2 :clearinghouseState]`. `cross-margin-ratio` and `cross-account-leverage` follow automatically because they are quotients of those bindings. The named-dex fixture must now agree with the oracle, and the four fake zeros must become real numbers.

Take care with one thing here: `maintenance-margin` is returned directly as `:maintenance-margin` for classic accounts and is overridden by `unified-maintenance-margin*` for unified ones. The unified branch must keep using its own helper. The parity test's unified fixtures are the guard.

**Milestone 4 — the fallback and the invariant.** `unrealized-from-summary` computes `accountValue − totalRawUsd`, which by the venue's own identity is the signed position value, not unrealized PNL. It is used only when no positions are present in state, in which case the true answer is zero, so replace it with nil and let `unrealized-pnl` fall back to the position sum alone. Then add the reconciliation invariant to the parity test: for every classic fixture, the rendered Account Value string must equal the rendered Spot plus Perps to the cent.

**Milestone 5 — proof.** A new Playwright spec seeds the named-dex classic shape and asserts the panel shows real numbers rather than zeros. An opt-in live spec, run manually, points at both anchor wallets and compares the rendered panel against the venue's formulas recomputed from a fresh `/info` snapshot in the same run. Full gates.

## Concrete Steps

All commands run from the repository root. In this worktree that is `/Users/barry/projects/hyperopen/.claude/worktrees/account-leverage-hl-xyz-parity-e67bda`.

A fresh worktree has no `node_modules` and `shadow-cljs` is not on `PATH`, so an unbootstrapped checkout makes every gate fail with an opaque error that is environmental rather than a code defect. Bootstrap once:

    npm run setup:worktree

Fast signal during development:

    npm test

Full gate matrix, which does not short-circuit on the first failure:

    npm run gates

Expect a final block of the shape:

    Totals:
      gates passed:            34/34
      tests run:               6650
      assertions run:          35991
    Overall: PASS

To re-derive the anchor wallets' figures at any time:

    ADDR=0x68bd85dc5c94a5511e959d299617eb19cd26c298
    curl -s -X POST https://api.hyperliquid.xyz/info -H 'Content-Type: application/json' \
      -d "{\"type\":\"userAbstraction\",\"user\":\"$ADDR\"}"
    curl -s -X POST https://api.hyperliquid.xyz/info -H 'Content-Type: application/json' \
      -d "{\"type\":\"clearinghouseState\",\"user\":\"$ADDR\"}"

`userAbstraction` must return something other than `unifiedAccount` or `portfolioMargin` for the wallet to exercise the classic panel. For the named-dex wallet add `"dex":"xyz"` to the second call. A dex's collateral token comes from `{"type":"meta","dex":"<name>"}` — `perpDexs` does **not** carry it.

Should either anchor wallet close its positions, find a replacement by scanning the public leaderboard for a classic account with an open book:

    curl -s https://stats-data.hyperliquid.xyz/Mainnet/leaderboard -o /tmp/lb.json

then, for a sample of `leaderboardRows[].ethAddress`, keep the first whose `userAbstraction` is not `unifiedAccount`/`portfolioMargin` and whose `clearinghouseState` has a non-empty `assetPositions`. Be gentle: batch six addresses at a time with a short pause, because `/info` rate-limits and the repo surfaces 429s rather than hiding them.

Running Playwright from a worktree needs its own server, because port 8080 is held by the main checkout's dev server and reusing it would exercise the *main* checkout's build, proving nothing about the change. After `npm run gates` has compiled the `app` build:

    npm run css:build

then serve `resources/public` behind a small SPA-fallback HTTP server on a free port — a plain static server returns 404 for client routes such as `/trade`, and the Playwright helper `visitRoute` navigates there first — and run:

    PLAYWRIGHT_BASE_URL=http://127.0.0.1:8090 PLAYWRIGHT_REUSE_EXISTING_SERVER=true \
      npx playwright test tools/playwright/test/account-equity-classic-named-dex.spec.mjs --workers=1

## Validation and Acceptance

Acceptance is behavioural at three levels, and the requester's explicit ask — that the existing unified work must not regress — is enforced at the first two.

**Level one, the fixture matrix.** `npm test` runs `account-equity-parity-test` over every fixture. Acceptance is that every classic fixture agrees with the oracle on Account Value, Spot, Perps, Balance, Unrealized PNL, Cross Margin Ratio, Maintenance Margin and Cross Account Leverage, and every unified fixture agrees on Unified Account Ratio, Portfolio Value, Unrealized PNL, Perps Maintenance Margin and Unified Account Leverage. The named exceptions — currently only the all-isolated ratio — are asserted explicitly as divergences so that a future change which accidentally "fixes" them fails loudly.

This matrix is what protects the unified work. It contains the all-isolated case that must still read `0.00x`, `$0.00` and `--`; the mixed case whose leverage must exclude the isolated leg; and the two-collateral-token case whose denominator must include a USDH balance on a dex the wallet does not trade. Those are the three behaviours commit `dc2ada72d` introduced, and after Milestone 0 they are pinned against the venue's own formulas rather than against hand-written constants.

Alongside the matrix, keep the existing focused suites — `account_equity_unified_metrics_test.cljs` and `account_equity_view_test.cljs` — untouched in intent. They test rendered strings and edge-case messaging, which the oracle has no opinion about. If a change makes one of them fail, that is a real signal, not a stale expectation, unless the venue's behaviour itself is what changed.

**Level two, the panel invariant.** For every classic fixture, the rendered Account Value must equal Spot plus Perps to the cent. This catches a whole class of defect the oracle cannot: it does not matter whether both halves came from the venue's formula if the panel then displays them inconsistently. The invariant fails today on both anchor shapes.

**Level three, the browser.** A new spec, `tools/playwright/test/account-equity-classic-named-dex.spec.mjs`, seeds a classic account whose base dex is empty and whose `xyz` dex carries a real cross book, then asserts the panel renders real Balance, Perps, Maintenance Margin, Cross Margin Ratio and Cross Account Leverage values and contains no `$0.00`, `0.00%` or `0.00x`. Two traps in the existing account-panel spec apply here and will cost an hour each if ignored: seeding account surfaces throws from `hyperopen.account.lifecycle-invariants` unless spectate mode has given the state an effective account first, so dispatch `:actions/start-spectate-mode` before seeding; and the seed must wait for the real spectate fetches to settle — `waitForIdle({quietMs: 800, timeoutMs: 12000})` — before sync is detached, or a late response silently overwrites the seed and the panel renders the real account instead.

The existing `tools/playwright/test/account-equity-unified-isolated.spec.mjs` must continue to pass unchanged. It is the end-to-end guard on the shipped unified behaviour.

**Live verification.** A second new spec, `tools/playwright/test/account-equity-venue-parity.live.spec.mjs`, skips itself unless `RUN_VENUE_PARITY=1` is set. For each anchor wallet it spectates the address, waits for idle, reads the rendered panel, fetches `clearinghouseState` for the base dex and every named dex plus `spotClearinghouseState` and `spotMetaAndAssetCtxs` in the same run, recomputes the venue's formulas, and asserts the rendered strings match. Because it recomputes rather than comparing against constants, it stays correct as the wallets' positions move. It is excluded from CI by the env gate; note that exclusion in the spec's header comment so nobody "fixes" it later.

Run it as:

    RUN_VENUE_PARITY=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:8090 PLAYWRIGHT_REUSE_EXISTING_SERVER=true \
      npx playwright test tools/playwright/test/account-equity-venue-parity.live.spec.mjs --workers=1

Acceptance for `0x68bd85dc5c94a5511e959d299617eb19cd26c298` is that Perps equals the aggregate `marginSummary.accountValue` and Balance equals that minus the summed `unrealizedPnl`, both to the cent — around $29,871.53 and $67,474.53 at the figures recorded above, though the live values will differ. Acceptance for `0xb9aebb46919bccbf210537a1f2173690d9ee7af7` is that none of Balance, Perps, Maintenance Margin, Cross Margin Ratio or Cross Account Leverage renders as zero, and each matches the `xyz` dex's own figures — around $149,157.81, $155,901.46, $1,144.15, 0.73% and 0.29x.

**Gates.** `npm run gates` must report 34/34. The suite currently runs 6,650 tests and 35,991 assertions; the number will rise with the new matrix, and the new count belongs in `Progress`. Eight Playwright specs in the local suite were already red before this work — four in `trade-regressions`, three optimizer draft-menu smoke specs, and the `routes.smoke` build badge. Confirm any red by stash-verifying against a pre-change build rather than assuming this change caused it: `git stash`, re-run the spec, `git stash pop`.

**Mutation coverage.** The repo runs `npm run test:mutation` inside the gate matrix. After the matrix lands, spot-check that it still passes and that the new test files are not merely asserting shapes — a parity assertion that compares nil to nil on a fixture whose oracle also returns nil proves nothing. Every fixture must have at least one row with a non-trivial expected value.

## Idempotence and Recovery

Every step is safe to repeat. `npm run setup:worktree` no-ops when `node_modules` is already linked. The `curl` snapshots write to `/tmp` and can be retaken freely; retaking them changes the expected numbers, which is why acceptance is defined as an identity to recompute rather than a constant to match.

Milestones 0 and 1 are purely additive — new test files and an unwired helper — so they can be landed and left in place even if the rest is deferred. Milestones 2, 3 and 4 change behaviour and are confined to `derive-account-equity-metrics`; `git checkout -- src/hyperopen/views/account_equity/` restores the prior behaviour completely.

If the panel goes blank rather than wrong after a milestone, suspect the memoization in `memoized-account-equity-metrics`, which caches on identity of five state buckets. Call `reset-account-equity-metrics-cache!` before re-reading metrics in tests, as the existing helpers already do.

If either anchor wallet changes shape mid-implementation, the leaderboard scan in `Concrete Steps` finds a replacement; update this plan's `Context and Orientation` with the new address and figures rather than leaving stale ones in place.

## Artifacts and Notes

The venue's aggregator as shipped, minified, from `https://app.hyperliquid.xyz/assets/config-Dq8XJCAJ.js` on 2026-08-21. It is quoted verbatim because the readable version in `Surprises & Discoveries` is a translation and the oracle must be transcribed from the original:

    function HM(e,t,n,r){let i=x8(),a=x8(),o=0,s=0,c=[];
    for(let l of Object.values(t)){let t=e[l.pdi];if(t){let e=n_(l.collateralToken,n,r);
    i=S8(i,t.marginSummary,e),a=S8(a,t.crossMarginSummary,e),
    o+=t.crossMaintenanceMarginUsed*e,s+=t.withdrawable*e,c.push(...t.assetPositions)}}
    return{assetPositions:c,marginSummary:i,crossMarginSummary:a,crossMaintenanceMarginUsed:o,withdrawable:s}}

`x8()` is a zero summary, `S8(acc, summary, price)` adds a price-scaled summary onto an accumulator, and `n_(collateralToken, spotAssetCtxs, spotMeta)` is that token's USD price. `UM()` memoizes `HM` over the active and master accounts and is what the panel consumes.

The classic panel's rows as the venue builds them, from `index-CyXjSnYq.js`, with `g` bound to the aggregate `marginSummary`, `_` to the aggregate `crossMarginSummary`, `v` to the aggregate `crossMaintenanceMarginUsed`, `E` to the summed `unrealizedPnl` and `ee` to spot portfolio value:

    ie=[F(`spot`), {left:`perps`, right:ht({val:g?.accountValue??0,...}),
                    explanation:`account.equity.explanation`}]

    rows:[{left:`balance`, right:`$${wr((g?.accountValue??0)-E,2)}`, explanation:`balance.explanation`},
          M /* unrealized pnl */,
          {left:`cross.margin.ratio`, right:`${wr(k*100,2)}%`, ...},
          N(`maintenance.margin`),
          P(`cross.account.leverage`,A,`cross.account.leverage.explanation`)]

with `k = v/(_?.accountValue??0 + 1e-8)` and `A = (_?.totalNtlPos??0)/(_?.accountValue??0 + 1e-8)`.

The live evidence for the two anchor wallets, 2026-08-21:

    0x68bd85dc5c94a5511e959d299617eb19cd26c298   abstraction "disabled", base dex only
      accountValue 29871.526234  totalNtlPos 76577.0  totalRawUsd 106448.526234
      totalMarginUsed 7657.7     crossMaintenanceMarginUsed 3828.85
      one cross HYPE position, positionValue 76577.0, unrealizedPnl -37603.0
      spot ~ $4.07
      venue Balance 67474.53   ours 114106.23   (over by totalMarginUsed + totalNtlPos + upnl = 46631.70)
      venue Perps   29871.53   ours  76503.23

    0xb9aebb46919bccbf210537a1f2173690d9ee7af7   abstraction "disabled", base dex empty
      xyz: accountValue 155901.461224  totalNtlPos 45765.91629  totalMarginUsed 15255.30543
           crossMaintenanceMarginUsed 1144.147907   one position, unrealizedPnl +6743.65
      venue Balance 149157.81  Perps 155901.46  Maint 1144.15  Ratio 0.73%  Leverage 0.29x
      ours  Balance      0.00  Perps   6743.65  Maint    0.00  Ratio 0.00%  Leverage 0.00x

## Interfaces and Dependencies

No new libraries or services. Every input is already in application state: `[:webdata2 :clearinghouseState]` for the base dex, `[:perp-dex-clearinghouse]` for named dexes, `[:spot :clearinghouse-state :balances]` for spot totals and `[:asset-selector :market-by-key]` for per-dex collateral symbols and prices.

In `src/hyperopen/views/account_equity/metrics.cljs`, the following must exist at the end of Milestone 1:

    (defn- aggregate-clearinghouse-usd
      "One synthetic clearinghouse state in USD, summed across every dex the
       wallet has a snapshot for, each dex converted through its own collateral
       token. Mirrors the venue's own aggregation, which is what its classic
       panel reads. nil fields where nothing resolved -- never zero."
      [records balance-row-by-token market-by-key]
      ;; => {:account-value _ :cross-account-value _ :cross-total-ntl-pos _
      ;;     :cross-total-margin-used _ :total-raw-usd _ :maintenance-margin _}
      )

`unified-clearinghouse-state-records` must become unconditional — built for classic accounts too — and keep its current shape, a vector of `{:dex _ :quote-token _ :state _}`.

`derive-account-equity-metrics` must return, for a classic account: `:perps-value` = the aggregate `:account-value`; `:base-balance` = that minus `:unrealized-pnl`; `:cross-account-value`, `:maintenance-margin`, `:cross-margin-ratio` and `:cross-account-leverage` all derived from the aggregate. `cross-derived-balance` and `unrealized-from-summary` must no longer exist. The unified branch must be untouched: `:unified-account-leverage`, `:unified-account-ratio`, `:isolated-notional` and the unified `:maintenance-margin` override all keep their current definitions.

In `test/hyperopen/views/account_equity_venue_oracle.cljs`:

    (defn aggregate [per-dex-states collateral-price-by-dex]) ; => venue-shaped aggregate map
    (defn classic-rows [aggregate spot-usd])                  ; => {:account-value :spot :perps :balance
                                                              ;     :unrealized-pnl :cross-margin-ratio
                                                              ;     :maintenance-margin :cross-account-leverage}
    (defn unified-rows [aggregate per-dex-states spot-balances collateral-tokens prices])
                                                              ; => {:ratio :portfolio-value :unrealized-pnl
                                                              ;     :maintenance-margin :leverage :isolated-notional}

The oracle must depend on nothing under `hyperopen.views.account-equity.*`. If it needs a number parser or a currency formatter, give it its own three-line versions rather than importing ours; sharing a helper is how two implementations quietly become one.
