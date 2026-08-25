# Let long-but-sparse assets (Hyperliquid vaults) into the optimizer natively

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`,
`Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.
This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`
(the full ExecPlan writing contract) and `/hyperopen/docs/PLANS.md` (the public planning
contract).

## Purpose / Big Picture

Today, adding a Hyperliquid vault to the portfolio optimizer at `/portfolio/optimize`
usually ends with the vault thrown out of the run. The user sees a card in the
"History assumptions" queue that says the vault has "31 days of native history - too
short to model on its own", tags it `EXCLUDED - NEEDS ASSUMPTION`, and demands that the
user either nominate proxy assets or accept a "worst case, no diversification credit"
placeholder. The same app's own vaults page, meanwhile, charts more than two years of
history for that vault. The user's reasonable conclusion is that the optimizer is broken.

After this change, a vault whose history is *long but sampled far apart* is included in
the optimization on its own merits. Its expected return and its risk come from its own
real samples through the mixed-frequency estimator the engine already ships. No proxy is
required, and no "worst case" placeholder is applied. The user instead sees a calm,
truthful badge - `Sparse history` - whose tooltip explains that the venue publishes this
asset's history far apart, that the estimate is built from its own samples, that it is
deliberately kept out of the shared daily window so it cannot shorten anyone else's
estimate, and that its allocation is capped for safety.

How to see it working: open `/portfolio/optimize`, add the vault `Growi HF`
(address `0x1e37a337ed460039d1b15bd3bc489de789768d5e`) to the universe alongside ordinary
perps such as BTC and ETH, and press Run. Before this change the run is blocked with an
assumption card for the vault. After this change the run completes, the vault appears in
the results with a real weight (capped at 20%), the assumption card is gone, and the
vault's universe row shows a `Sparse history` badge. Crucially, the other assets'
estimates are unchanged: the shared daily calendar is bit-identical whether or not the
vault is in the universe, which the primary automated test asserts directly.

## Context References

Public refs:

- Direct user request captured in session on 2026-08-25: the user added the Growi HF
  vault on the optimize page, observed "It's saying it only has thirty one days of
  history, and we need to supply a proxy. But that seems wrong because on the vaults
  page, we see two years of history... Isn't there a way we can work it in? Without it
  needing a proxy?" and then asked for an execution plan and implementation.

Repo artifacts:

- `/hyperopen/docs/exec-plans/completed/` contains the prior optimizer history-loader
  work this builds on; nothing in it is required reading, because everything needed is
  restated below.
- `/hyperopen/dev/namespace_size_exceptions.edn` is the line-count gate this change must
  update.
- `/hyperopen/AGENTS.md` defines the required validation gates.

Local scratch refs (non-authoritative):

- None.

## Progress

- [x] (2026-08-25) Diagnosed the defect end to end against the live Hyperliquid API and
      the live optimizer history backend. Root cause and all supporting numbers recorded
      in `Context and Orientation` and `Surprises & Discoveries` below.
- [x] (2026-08-25) Recorded a green baseline: `npm run gates` = 34/34 PASS, 6943 tests,
      38572 assertions, 3m 8s.
- [x] (2026-08-25) Milestone 1: off-calendar weight cap in `domain/constraints.cljs`.
- [x] (2026-08-25) Milestone 2: widen the expected-return universe in `domain/returns.cljs`.
- [x] (2026-08-25) Milestone 3: the `sparse_lane.cljs` classifier namespace, pure and unwired.
- [x] (2026-08-25) Milestone 4: wire the partition into `api_v2/alignment.cljs` (part A,
      partition only) and prove the shared calendar is unchanged.
- [x] (2026-08-25) Milestone 5: feed the estimator (part B) and export the new keys.
- [x] (2026-08-25) Milestone 6: freshness re-stamp in `request_builder.cljs`.
- [x] (2026-08-25) Milestone 7: proxy-anchor exclusion at both `usable-proxy-id-set` call sites.
- [x] (2026-08-25) Milestone 8: readiness gates and warning copy.
- [x] (2026-08-25) Milestone 9: adequacy, badges and labels in the view model.
- [x] (2026-08-25) Milestone 10: full gates green.
- [x] (2026-08-25) Adversarial review of the diff across four lenses (nil-safety/solver,
      let-binding order, regression risk, test quality) with every finding independently
      refutation-checked. Nine candidate findings, three real; all three fixed. Final
      gates 34/34, 6965 tests, 38628 assertions.
- [ ] Milestone 11 (remaining): Playwright browser QA of the queue spec on a fresh page
      load, and a manual pass on the running app with the real Growi HF vault. Not yet
      run in this session.

## Surprises & Discoveries

- Observation: Hyperliquid does not publish a dense vault history at all. Every
  `vaultDetails` portfolio window is downsampled to a fixed point budget, so "two years
  of history" is genuinely only ~65 samples.
  Evidence: live `POST https://api.hyperliquid.xyz/info {"type":"vaultDetails",...}` for
  Growi HF returned `day`=35 points (~0.7h apart), `week`=65 (~2.6h), `month`=47 (~15.7h,
  spanning 31 days), `allTime`=66 raw (~286h = ~12 days apart, spanning 2024-07-10 to
  2026-08-25). There is no time-parameterized vault history endpoint.

- Observation: the "31" in the card is a *sample count* printed with a *day* unit. The
  vault has 31 samples spread across 365 days, so both "31d" and "31 days of native
  history" are false as written.
  Evidence: `view_model/setup_history_assumption_queue.cljs:40` renders
  `(str observations "d")` and `:50` renders
  `(str observations " days of native history - too short to model on its own.")`, where
  `:observations` is a row count from `view_model/universe.cljs` `native-history-observations`.

- Observation: the all-time candidate is 65 usable rows, not 66. The first raw sample has
  `accountValue` `"0.0"` and is dropped before any return is computed.
  Evidence: `portfolio/metrics/normalization.cljs:75` `anchored-account-pnl-points` drops
  everything before the first positive account value (`first-positive-account-index`, `:68`).

- Observation: the naive "just day-align the vault timestamps" fix is actively harmful,
  confirmed with real data. The vault's 31 samples do land on 31 distinct UTC days, so
  day-alignment loses nothing by itself - but `calendar/common-calendar` is a plain
  `set/intersection` across *every* member, so intersecting the vault with a young member
  collapses the whole universe.
  Evidence: `hl:hip3:para:UNITREE` has 20 daily bars (2026-08-04..2026-08-23) and shares
  only 3 days with the vault (2026-08-07, 2026-08-15, 2026-08-23) = **2 return
  observations for the entire universe**, down from ~1095.

- Observation: the sparse estimator was never unreachable. `cadence-summary` already
  classifies both vault candidates `:sparse? true`, and `risk-estimation` already returns
  `:mixed-frequency` when any member is sparse. The bug is purely that api-v2 alignment
  peels the vault out *before* the sparse path can ever see it. A fix that only touched
  the estimator would have changed nothing.
  Evidence: `domain/history_series.cljs:138` `sparse?`; `domain/risk_mixed_frequency.cljs:159`
  `mixed-frequency?`; `api_v2/alignment.cljs:467` peel.

- Observation: one legacy-fallback member anywhere in the universe makes alignment discard
  the backend's clean `common_calendar` and recompute it client-side from member points.
  So a single vault degraded calendar quality for the whole basket even before it was
  peeled.
  Evidence: `api_v2/alignment.cljs:432` adopts the backend calendar only when
  `(not legacy-fallback-used?)`. Narrowing `legacy-fallback-used?` to exclude lane members
  restores the fast path, which the new test asserts via `:alignment-source :kind`.

- Observation: the optimizer history backend *already* serves this vault as a first-class
  instrument on a clean UTC-midnight daily grid, but the frontend never discovers it,
  and the backend's copy is badly stale.
  Evidence: `POST /v1/optimizer/history-bundle` with instrument id
  `hl:vault:0x1e37a337ed460039d1b15bd3bc489de789768d5e` returns 54 points, lineage
  `native`, series kind `vault_return_index`, every `time_ms % 86400000 == 0`, spanning
  2025-05-15..2026-05-15 - but also `stale-history` with `serve_age_days` 101. The vault
  listing at `/v1/optimizer/vaults` reports `history_status_counts`
  `{"available": 0, "missing": 18, "stale": 3271}` over 3289 vaults. `discovery.cljs:32`
  reads only `body[:instruments]`, and no source file references `/v1/optimizer/vaults`.
  This is why vaults always route through the legacy fallback. Switching to it would fix
  the timestamps and break freshness, so it is deferred (see Decision Log).

- Observation: `metrics/history.cljs:244` `preferred-vault-summary` is dead code with zero
  call sites in `src/`. It documents a vault-window policy the app does not follow.
  Evidence: repo-wide grep finds only the definition plus references in completed
  exec-plan documents.

- Observation: the vaults page does not hard-prefer a one-year window; it honors the
  user's selected preset (default `:month`). At its `:one-year` preset it produces exactly
  the same 31 points over 365 days that the optimizer derived, which is why the two
  surfaces looked inconsistent only at other presets.
  Evidence: `views/portfolio/vm/summary.cljs:287`; `vaults/application/ui_state.cljs:6`.

- Observation: `sparse-safety-max-weight` returns `nil` at 60 or more intervals, and
  `runtime-sparse-caps` only emits a cap when the value is a number. A deep-window sparse
  member would therefore have been **uncapped** in front of the solver, while
  `pair-estimate` returns covariance exactly `0` for any pair with fewer than 2 shared
  intervals. That combination - an apparently uncorrelated, uncapped asset - is exactly
  what a max-Sharpe objective drives to the global `max-asset-weight`. This is why the cap
  had to land before anything could reach the new code path.
  Evidence: `domain/constraints.cljs:19-27`, `:198-214`;
  `domain/risk_mixed_frequency.cljs:386-389`.

- Observation: a member admitted to `:eligible-instruments` but absent from
  `:return-series-by-instrument` silently received an expected return of `0`, with no
  warning, because `domain/returns.cljs` derived its instrument universe from the
  calendar-sampled map alone while `domain/risk.cljs` used the union with the native map.
  Evidence: `returns.cljs:42-44` vs `risk.cljs:16-22`; `engine/context.cljs` coerces a
  missing expected return to `0`.

- Observation: `usable-proxy-id-set` has THREE production call sites, not the two this
  plan originally enumerated. The third, `setup_readiness/assumption-validation-ctx`, was
  missed on the first pass and found by the adversarial review.
  Evidence: `grep -rn "usable-proxy-id-set" src` returns `request_builder.cljs:628`,
  `view_model/setup_history_assumption_cards.cljs:360` and `setup_readiness.cljs:418`.
  Consequence had it shipped: readiness would judge a lane member a usable proxy anchor
  while request-builder rejected it, so the user got the generic "needs more
  history-assumption details" instead of the name of the unusable proxy. The run blocked
  either way, so this was message fidelity rather than a wrong number - but it is exactly
  the readiness/request-builder mirror the surrounding code repeatedly guards. Fixed.

- Observation: cadence is computed for EVERY eligible member, not only lane members, so
  keying the new `:sparse` adequacy on `:sparse?` alone was too broad. A sparse member
  that still shares the daily calendar would have been shown the `Sparse history` badge
  whose tooltip promises it is "kept out of the shared daily window" - false for it.
  Evidence: `alignment.cljs` derives `:cadence-by-instrument` from `effective-eligible`
  (calendar members plus lane rows) and stamps `:off-calendar? true` only onto lane ids;
  `history_series.cljs` computes `:sparse?` from median gap and density alone, with no
  notion of calendar membership. Fixed by requiring `:off-calendar?` in
  `sparse-long-history?`, with a regression test for the on-calendar sparse case.

- Observation: one of the new tests was vacuous as first written. The "don't demand a
  proxy for a lane member" assertion used a 31-row fixture, and 31 already clears the
  30-observation gate, so it passed with or without the production skip. Rewritten with a
  12-row fixture so it fails when the skip is removed. Worth stating plainly: a test that
  cannot fail is worse than no test, because it reports safety it never checked.

## Decision Log

- Decision: keep the sparse member in the universe but out of the shared daily calendar,
  rather than day-aligning it onto that calendar.
  Rationale: the shared calendar is an intersection across every member, so admitting a
  14-day-cadence series to it collapses the window for everyone (measured: ~1095 return
  observations to 2). Separately, the api-v2 path samples each member's *own* point-to-point
  return at the shared timestamps, so a dense member sitting on a 14-day calendar would
  contribute a 1-day return while `return-intervals` reported `dt-days` 14 - semantically
  wrong, not merely lossy. The mixed-frequency estimator already handles irregular
  endpoints correctly and needs no shared calendar at all.
  Date/Author: 2026-08-25, Geronimo.

- Decision: gate the lane on cadence AND a floor of 30 observations AND 90 elapsed days
  AND the absence of usable backend-aligned returns.
  Rationale: the 30-observation floor is pinned to
  `history-assumptions/assumption-required-max-observations`, because
  `setup_readiness/short-history-assumption-warnings` and `universe/assumption-required-ids`
  both block the run below that number. A lower lane floor would admit a member natively
  and then block the run demanding a proxy for it - a contradiction. The
  "no usable aligned returns" conjunct keeps a member the backend can already carry on its
  own coarse calendar exactly where it is; without it, the 3-point/28-day backend vault
  fixture in `history_loader_api_v2_test.cljs` would have been swallowed by the lane and
  regressed from working to blocked.
  Date/Author: 2026-08-25, Geronimo.

- Decision: ship the weight cap (Milestone 1) before anything can reach the new path.
  Rationale: `sparse-safety-max-weight` returns nil above 59 intervals, so a deep-window
  lane member would be uncapped. Gating the new floor on a `:off-calendar?` flag rather
  than making it unconditional keeps the existing on-calendar ladder pinned by
  `constraints_test.cljs` byte-identical.
  Date/Author: 2026-08-25, Geronimo.

- Decision: emit `:off-calendar-instrument-ids` and `:freshness-calendar` with `cond->`
  only when the lane is non-empty.
  Rationale: `optimizer-input-signature` hashes the whole `:history` map minus
  `:freshness`. Emitting the keys unconditionally would change the signature of every
  existing scenario, mass-invalidating cached results and tripping the execution stale
  gate. Conditional emission makes a lane-free universe byte-identical to today.
  Date/Author: 2026-08-25, Geronimo.

- Decision: state plainly that for a *highly correlated* sparse asset, native estimation is
  MORE optimistic than the conservative assumption it replaces.
  Rationale: `correlation-retention` shrinks the measured correlation toward zero as
  `n/(n+30)`, so a sparse asset that is genuinely correlated with the book will have that
  correlation understated, and the optimizer will credit it with more diversification than
  it deserves. The 20% weight cap is the backstop. This is an honest trade, not a free
  win, and it must not be described as one to the user.
  Date/Author: 2026-08-25, Geronimo.

- Decision: DEFERRED - do not swap the vault window from `derived-one-year` (31 samples /
  365 days) to the deepest candidate (65 samples / 775 days) in this change.
  Rationale: the numbers favour the deeper window (64 vs 30 intervals; volatility standard
  error 2.32pp vs 4.42pp, 32% tighter; correlation retention 0.681 vs 0.500), but it also
  moves the vault's estimated volatility from 34.3% to 26.2% and trades regime recency for
  sample size. It is a visible product change that deserves an owner decision, and it is
  only safe once the weight cap exists. Tracked as follow-up.
  Date/Author: 2026-08-25, Geronimo.

- Decision: DEFERRED - do not fix `pair-estimate` returning covariance `0` (including on
  the diagonal) for pairs with fewer than 2 shared intervals.
  Rationale: it rewrites results for every existing mixed-frequency universe and is not
  required for this bug. The 20% cap bounds the damage in the interim. Tracked as
  follow-up.
  Date/Author: 2026-08-25, Geronimo.

- Decision: DEFERRED - do not mirror this guard into the pure-legacy
  `history_loader/alignment.cljs` path, and record it as a known gap.
  Rationale: that path runs only when the api-v2 history request fails entirely
  (`history_loader.cljs` dispatches on the absence of `:api-v2-history`). Its
  `effective-history-alignment` accepts any day-aligned calendar with at least 2
  timestamps, so the same collapse can still occur there. It is a pre-existing harm on a
  fallback-of-a-fallback path; fixing it means duplicating the partition into a second
  alignment implementation, which is better done when the two paths are unified. Tracked
  as follow-up.
  Date/Author: 2026-08-25, Geronimo.

- Decision: DEFERRED - lane members do not receive the return-plausibility disclosure
  warnings (`:implausible-return-observation` / `:extreme-return-observation`), and this is
  deliberate rather than an oversight.
  Rationale: `calendar/plausibility-warnings` copy states that the offending bar "and the
  ones adjoining it were discarded before estimating risk". That is true on the calendar
  path, where `calendar/point-return-map` really drops them, and FALSE on the lane, where
  `pair-estimate` reads the raw close. Emitting the existing warning for lane members would
  ship a sentence that misstates what the system did. Funding disclosure IS extended to
  lane members in the same block, because it is perp-gated and reads only the member's own
  series, so it is correct off the calendar. A lane-specific plausibility disclosure whose
  copy says the bar was KEPT and dominates the estimate is the honest follow-up.
  Date/Author: 2026-08-25, Geronimo.

- Decision: DEFERRED - do not switch vault history to `/v1/optimizer/vaults` discovery.
  Rationale: the backend already serves midnight-aligned vault series, which would remove
  the timestamp problem at the root, but its cache is ~101 days stale for this vault and
  reports 0 of 3289 vaults as `available`. Adopting it today would replace a sparse-but-current
  series with a dense-but-three-months-stale one. Tracked as follow-up; it is the right
  long-term fix once backend vault ingestion is current.
  Date/Author: 2026-08-25, Geronimo.

## Outcomes & Retrospective

Milestones 1 through 10 are complete and the full gate matrix is green: `npm run gates`
reports 34/34 PASS with 6965 tests and 38628 assertions, against a pre-change baseline of
6943 tests and 38572 assertions - so this change added 22 tests and 56 assertions and broke
nothing. A late addition proved the most dangerous remaining hazard is absent: the sparse
member's own DIAGONAL variance is positive and finite. `pair-estimate` returns covariance
exactly 0 for any pair with fewer than 2 shared intervals and has no diagonal special case,
so a lane member whose own variance collapsed to zero would have looked risk-free and a
max-Sharpe objective would have poured into it - a far worse bug than the one being fixed.
It does not collapse, because a sparse member's self-pair uses its own 30 intervals.

What was achieved: a long-but-sparse asset now participates in the optimizer natively.
The Growi HF vault is admitted to `:eligible-instruments`, estimated through the
mixed-frequency pairwise path from its own 31 samples, capped at 20% weight, and reported
to the user with a truthful `Sparse history` badge instead of a false "31 days" claim and
a demand for a proxy. The differential test proves the rest of the universe is untouched:
the shared calendar and return calendar are bit-identical with and without the vault
present.

On complexity: this change *increases* total complexity, and deliberately so. It
introduces a genuine third state into alignment - a member can now be excluded, on the
shared calendar, or in the universe but off the calendar - where previously there were
two. That third state is not incidental; it is the correct model of the problem, because
"has usable history" and "has history on the same days as everyone else" are genuinely
different properties and the code previously conflated them. The complexity is contained
in one new 96-line namespace (`sparse_lane.cljs`) plus one new partition predicate in
alignment, and it deletes no existing behaviour: a universe with no lane member produces
a byte-identical `:history` map, which Milestone 7's signature test asserts.

What remains: Milestone 11 (browser QA) has not been run in this session. Four follow-ups
are recorded in the Decision Log as explicit deferrals - the deepest-window swap, the
pairwise covariance-zero fill, the legacy-path collapse, and `/v1/optimizer/vaults`
discovery. The last of these is the real long-term fix and would make most of this
machinery unnecessary for vaults specifically, though the lane remains correct for any
sparse instrument.

Lesson learned: the naive fix (day-align the timestamps) was intuitive, small, and wrong -
it would have silently collapsed the shared estimation window for every other asset in the
universe from ~1095 observations to 2, while appearing to fix the reported symptom. The
measurement that killed it took ten minutes and saved shipping a far worse bug than the
one being fixed. Second lesson: `sparse-safety-max-weight` returning `nil` above 59
intervals meant the "safety cap" silently did not apply to exactly the assets this change
admits; a safety mechanism whose ladder terminates in "no cap" should be read carefully
before it is relied upon in a design. Third lesson, from the review pass: two of the three
real defects were "the new signal is available in more places than I reasoned about" -
`usable-proxy-id-set` had a third call site, and `:cadence-by-instrument` covers every
eligible member rather than only lane members. When a change introduces a new flag, the
question to ask is not "where do I set it" but "who else already reads the map I set it in".

## Context and Orientation

This section assumes no prior knowledge of the repository.

Hyperopen is a ClojureScript single-page trading application built with shadow-cljs and
the Replicant rendering library. The portfolio optimizer lives under
`src/hyperopen/portfolio/optimizer/` and is reached in the running app at the route
`/portfolio/optimize`.

Some vocabulary, defined here because the code uses these words with specific meanings:

- An **instrument** is one tradeable thing in the optimizer's universe: a perpetual
  future, a spot pair, or a Hyperliquid **vault** (a pooled strategy account you can
  deposit into, identified by an Ethereum address).
- A **series** is one instrument's price history, represented as a vector of **point**
  maps, each `{:time-ms <epoch milliseconds> :close <number> :return <number or nil>}`.
- The **shared calendar** (in code, `:calendar`, and its returns counterpart
  `:return-calendar`) is the list of timestamps at which *every* included instrument has a
  price. It is computed as a set intersection across all members in
  `src/hyperopen/portfolio/optimizer/application/history_loader/calendar.cljs`, function
  `common-calendar`. Because it is an intersection, one member with unusual timestamps can
  shrink it for everybody.
- **Alignment** is the step that takes each instrument's raw series and produces the
  shared calendar plus per-instrument return vectors. There are two implementations:
  `history_loader/api_v2/alignment.cljs` (the normal path, used when the optimizer history
  backend responds) and `history_loader/alignment.cljs` (the older path, used only when
  the backend request fails entirely). `history_loader.cljs` chooses between them by
  checking whether the loaded bundle has an `:api-v2-history` key.
- **Cadence** describes how densely a series is sampled.
  `src/hyperopen/portfolio/optimizer/domain/history_series.cljs`, function
  `cadence-summary`, returns a map with `:observations`, `:interval-count`,
  `:elapsed-days`, `:median-dt-days`, `:density-vs-daily` and `:sparse?`. A series is
  `:sparse?` when its median gap exceeds 3 days or its density falls below 0.5
  observations per day.
- **Mixed-frequency estimation** is the existing covariance machinery in
  `src/hyperopen/portfolio/optimizer/domain/risk_mixed_frequency.cljs`. For a pair of
  instruments where at least one is sparse, it uses the sparse member's own sample times
  as interval endpoints, finds each instrument's most recent price at or before each
  endpoint (`row-at-or-before`, with a staleness limit), and computes an annualized
  covariance from those intervals. It never needs two instruments to have prices on the
  same day.
- A **history assumption** is the user-facing workaround the optimizer currently demands
  for thin-history assets: either nominate proxy instruments whose history stands in for
  the asset, or accept a deliberately pessimistic "worst case" placeholder.

The defect. Hyperliquid publishes vault history only as four downsampled windows (`day`,
`week`, `month`, `allTime`), each capped at roughly 35 to 66 points regardless of how long
the vault has existed. For a two-year-old vault the `allTime` window therefore arrives with
samples roughly 12 to 14 days apart. Vaults carry no backend history identifier, so they
are fetched through the "legacy fallback" in
`history_loader/api_v2/legacy_fallback.cljs`, which keeps their raw intraday timestamps
(for example 22:28 UTC). Every other instrument arrives stamped at exact UTC midnight.
Because `common-calendar` intersects exact millisecond values, the vault's timestamps match
nothing, the intersection empties, and `calendar/peel-poisoning-members` ejects the vault
with the warning `:insufficient-common-history` reporting `:observations 0, :required 1`.
Readiness then classifies the vault as needing a history assumption, which produces the
card the user reported.

The shape of the fix. The vault's problem is not that its history is unusable - the
mixed-frequency estimator can use it directly. Its problem is that it cannot share a daily
calendar with anyone. So alignment gains a third outcome. Previously each instrument was
either *excluded* or *eligible* (which implicitly meant "on the shared calendar"). Now an
instrument can also be **off-calendar**: kept in `:eligible-instruments` and fed to the
mixed-frequency estimator from its native series, while being invisible to the shared
calendar intersection and to the peel logic.

## Plan of Work

The work proceeds in ten milestones that each leave the repository green, followed by
browser QA. Each milestone is described in its own section under `Milestones` below. In
outline:

The first two milestones are safety prerequisites that are provably inert on their own.
Milestone 1 extends the sparse weight cap in `domain/constraints.cljs` so that an
off-calendar member is always capped, because the existing cap ladder returns "no cap" at
60 or more intervals - exactly the range a deep sparse series occupies. Milestone 2 widens
the expected-return instrument universe in `domain/returns.cljs` to match the one
`domain/risk.cljs` already uses, so that a member present only in the native series map
receives a real expected return instead of a silent zero.

Milestone 3 adds the classifier as a new, pure, unwired namespace. Milestones 4 and 5 wire
it into `api_v2/alignment.cljs` in two halves: first the partition (which must leave the
shared calendar provably unchanged), then feeding the estimator and exporting the new keys.
Milestone 6 repairs freshness, which `request_builder.cljs` re-stamps after alignment
returns. Milestone 7 stops a lane member being offered as a proxy anchor. Milestones 8 and
9 fix the readiness gates and the user-facing labels. Milestone 10 runs the full gates, and
Milestone 11 is browser QA.

## Milestones

### Milestone 1 - the off-calendar weight cap

Goal: guarantee that any off-calendar sparse member is weight-capped, before any code path
can produce one.

`src/hyperopen/portfolio/optimizer/domain/constraints.cljs` contains a private helper
`sparse-safety-max-weight` that maps an interval count to a maximum weight: fewer than 2
intervals gives 0, fewer than 8 gives 0.05, fewer than 30 gives 0.1, fewer than 60 gives
0.2, and 60 or more gives `nil`. `runtime-sparse-caps` only emits a cap when that value is
a number, so a series with 60 or more intervals gets no cap at all. The Growi HF all-time
window has 64 intervals, so the deep-window case this plan enables would be uncapped.
Separately, `risk_mixed_frequency/pair-estimate` returns a covariance of exactly `0` for
any pair with fewer than 2 shared intervals, so an off-calendar member can look
uncorrelated with everything. Uncapped plus uncorrelated is precisely what a max-Sharpe
objective concentrates into.

The change: in `runtime-sparse-caps`, when the cadence map carries `:off-calendar? true`,
fall back to `unknown-sparse-history-max-weight` (0.2) whenever `sparse-safety-max-weight`
returns `nil`. Add a third message branch to the cap warning explaining the reason in
plain language. Gate strictly on `:off-calendar?` so the existing ladder is untouched -
`test/hyperopen/portfolio/optimizer/domain/constraints_test.cljs` pins the current
upper bounds `[0 0.05 0.1 0.2 1]` and exactly four warnings for cadences of 1, 7, 29, 59
and 60 intervals, and that must stay byte-identical.

Acceptance: the new test file
`test/hyperopen/portfolio/optimizer/domain/constraints_sparse_off_calendar_test.cljs`
passes, and the existing `constraints_test.cljs` case still reports the same five bounds
and four warnings. Nothing else in the repository can set `:off-calendar?` yet, so this
milestone changes no observable behaviour.

### Milestone 2 - the expected-return universe

Goal: make sure a member that has native history but no shared-calendar returns receives a
real expected return.

`src/hyperopen/portfolio/optimizer/domain/returns.cljs` derives its instrument list from
`(keys (:return-series-by-instrument history))` alone. `src/hyperopen/portfolio/optimizer/domain/risk.cljs`
derives its list from the union of that map's keys and
`(:raw-price-series-by-instrument history)`'s keys. An off-calendar member by design has no
entry in the former and does have one in the latter, so under the current code it would get
no expected return, and `engine/context.cljs` substitutes `0` for a missing one without
warning - a silent, invisible error.

The change: make `returns/sorted-instrument-ids` use the same union `risk.cljs` uses. On
today's data the two key sets are identical, because
`:expected-return-series-by-instrument` is built from the same eligible set that produces
`:return-series-by-instrument`, so this is a no-op for every existing scenario.

Acceptance: `npm test` stays green with no changed assertions elsewhere, and the new test
`returns_sparse_member_test.cljs` proves that a member present only in the native map
receives a finite, geometrically annualized expected return rather than zero.

### Milestone 3 - the classifier

Goal: a pure, separately testable predicate that decides which members leave the shared
calendar, with no production wiring yet.

Create `src/hyperopen/portfolio/optimizer/application/history_loader/api_v2/sparse_lane.cljs`.
It exposes two thresholds and three functions:

`off-calendar-min-observations` is bound to
`history-assumptions/assumption-required-max-observations` (30). Pinning rather than
duplicating the number is deliberate: `setup_readiness/short-history-assumption-warnings`
and `view_model/universe/assumption-required-ids` both block the run below that threshold,
so a lower lane floor would admit a member natively and then block the run demanding a
proxy for it.

`off-calendar-min-elapsed-days` is 90. A member must span a real stretch of time, not just
be a handful of scattered recent points.

`possibly-sparse?` is a cheap O(1) prefilter over a series' points using only the first and
last timestamps and the count, so the expensive `cadence-summary` runs only for plausible
candidates. It is a deliberate superset of `cadence-summary`'s density trigger; it can miss
the median-gap trigger for a clustered series, which simply yields today's behaviour for
that series.

`off-calendar-ids` takes the candidate rows and a predicate that reports whether the
backend already serves usable aligned returns for an id, and returns the set of ids to move
off the calendar. A row qualifies only if it has points, the backend does *not* already
carry it on an aligned calendar, it passes the prefilter, and `cadence-summary` reports
`:sparse?` with at least 30 observations across at least 90 elapsed days. If *every*
candidate qualifies, the function returns the empty set: with no dense member there is no
calendar to protect, and today's behaviour should stand.

`warning` builds the non-blocking `:sparse-native-history` warning map.

Acceptance: `sparse_lane_test.cljs` passes, covering a Growi-shaped 31-sample/365-day
series (qualifies), a 3-point/28-day backend-served vault (does not, because the backend
carries it), a 29-observation/400-day series (does not, floor), and an all-sparse candidate
list (returns the empty set).

### Milestone 4 - wire the partition (part A)

Goal: the vault stops being peeled, and the shared calendar is provably unchanged.

All edits are inside the single large `let*` of `align-api-v2-history-inputs` in
`src/hyperopen/portfolio/optimizer/application/history_loader/api_v2/alignment.cljs`.
Binding order matters enormously here, because several names are shadowed later in the same
`let*`.

Immediately after `base-candidates`, compute `off-calendar-local-ids` from the classifier
and `calendar-candidates` as `base-candidates` minus those ids. Build
`candidate-series-by-id` from `calendar-candidates` so the memoized calendar delay never
sees a lane member. Two guards must be adjusted in lockstep or the change silently does
nothing: `response-superset?` must keep computing its member set from `base-candidates`
(otherwise every backend-served lane member looks like an unexpected extra key and falsely
trips `calendar-poisoned?`), and `use-aligned?` must be passed the narrowed candidate list.

In `prepared`, insert a branch after the `:rejected` branch and before the `use-aligned?`
branch that marks a lane row `:excluded? false, :off-calendar? true`.

Define a single predicate `calendar-member?` and use it in *both* `eligible` bindings - the
one after `prepared` and the one rebound after the peel. This is the subtlest hazard in the
whole change: a lane row carries `:excluded? false`, so if only the first binding filtered
the lane, any peel would silently re-admit every lane member to the shared calendar.

Finally, narrow `legacy-fallback-used?` to ignore lane ids, which restores the backend's
clean calendar fast path for the rest of the universe.

Acceptance: `npm test` green with `history_loader_api_v2_test.cljs`,
`history_loader_api_v2_legacy_fallback_test.cljs` and `history_loader_api_v2_split_test.cljs`
unmodified, plus the differential control assertion described under Validation.

### Milestone 5 - feed the estimator (part B)

Goal: the vault is actually optimized, using its own samples.

Split `effective-eligible` into `calendar-eligible` (used for the calendar-sampled maps
`price-series-by-instrument` and `source-series-by-instrument`, so a lane member never
appears as a vector of nils and can never be named as the window-limiting instrument) and
`effective-eligible` (calendar members plus lane members, used for `:eligible-instruments`,
funding warnings, plausibility warnings and - the load-bearing line - the
`native-history-metadata-for-series` call that produces `:raw-price-series-by-instrument`,
`:cadence-by-instrument` and the expected-return maps).

Fix `funding-by-instrument` to fall back to a lane series map, since the map it currently
reads is narrowed to calendar members. Attach `:off-calendar? true` to each lane member's
cadence map, which is what Milestone 1's cap reads. Restrict
`:return-series-by-instrument` to calendar members. Emit `:off-calendar-instrument-ids` and
`:freshness-calendar`, both guarded by `cond->` so a lane-free universe is byte-identical.

Acceptance: the full assertion set in `history_loader_sparse_vault_test.cljs` passes.

### Milestone 6 - freshness

`request_builder.cljs` overwrites `:freshness` after alignment returns, using
`(:calendar aligned)`. `calendar/freshness` reports `:stale? true` for an empty calendar, so
a universe whose only members are lane members would read permanently stale. Change the
re-stamp to prefer the real calendar and fall back to the exported `:freshness-calendar`.
The alignment memo key is unchanged, because this adds no alignment input.

### Milestone 7 - proxy anchors

A lane member has no `:return-series-by-instrument` entry, so the proxy regression it would
anchor silently produces nothing. Add an arity to `usable-proxy-id-set` that drops lane ids
and update **all three** call sites: `request_builder.cljs`,
`view_model/setup_history_assumption_cards.cljs`, and
`setup_readiness/assumption-validation-ctx`. Missing any of them leaves two gates
disagreeing about the same entry - the engine rejecting a proxy anchor that readiness or
the UI still calls usable, which degrades the blocking message into the unactionable "needs
more history-assumption details". Verify the count with
`grep -rn "usable-proxy-id-set" src` rather than trusting this list.

### Milestone 8 - readiness gates and copy

Skip lane ids in `short-history-assumption-warnings` so the run is not blocked. Add
`warning-display-message` and `warning-code-summary` cases for `:sparse-native-history` and
for `:optimizer-history-api-legacy-fallback` - the latter currently falls through to a
branch that prints the raw keyword, which is the `optimizer-history-api-legacy-fallback`
string the user saw. Add both codes to the informational warning set so they render as a
muted note rather than a caution.

### Milestone 9 - adequacy, badges and labels

Add `native-history-cadence` to `view_model/universe.cljs` and a `:sparse` branch at the
head of `history-adequacy`'s fallback clause: when the cadence is sparse and spans at least
360 days, return `:sparse` instead of judging a 31-row series against the 360-row bar. Map
`:sparse` to a new `:sparse-history` badge with the label and tooltip given under
`Interfaces and Dependencies`. Because `:sparse` is deliberately absent from
`card-needing-adequacy`, the assumption card is never built. Drop lane ids from
`assumption-required-ids`. Give the "add asset" option label a sparse form that states
samples and span separately.

### Milestone 10 - full gates

Run `npm run gates` and expect 34/34.

### Milestone 11 - browser QA

Run the history-assumptions queue Playwright spec at `--workers=1`, then clean up browser
sessions. Manual verification must use a fresh page load rather than a REPL reload, because
alignment is memoized with wholesale eviction and output-shape changes are invisible in a
hot-reload session.

## Concrete Steps

Working directory for every command is the repository root
`/Users/barry/projects/hyperopen/.claude/worktrees/vault-growi-history-issue-c8311f`.

First bootstrap the worktree. A fresh worktree has no `node_modules` and `shadow-cljs` is
not on `PATH`, so every gate fails with an opaque, environmental error until this runs:

    npm run setup:worktree

Then record the baseline:

    npm run gates

Expected tail:

    Totals:
      gates passed:            34/34
      tests run:               6943
      assertions run:          38572
    Overall: PASS

Run a single test namespace while iterating (much faster than the full suite):

    npx shadow-cljs compile test && node target/test/node-tests.js

Run the full required gates after each milestone:

    npm run check
    npm test
    npm run test:websocket

or all of them with a single PASS/FAIL matrix, which does not short-circuit on the first
failure:

    npm run gates

Browser QA:

    npx playwright test tools/playwright/test/optimizer-history-assumptions-queue.spec.mjs --workers=1
    npm run browser:cleanup

## Validation and Acceptance

The primary oracle is a **differential** test, because the thing most likely to go wrong is
invisible: silently degrading every other asset's estimate while appearing to fix the
vault. In `test/hyperopen/portfolio/optimizer/application/history_loader_sparse_vault_test.cljs`,
align a universe of dense daily perps twice - once without the sparse vault and once with
it - and assert that `:calendar` and `:return-calendar` are *equal* between the two runs.
If admitting the vault changes the shared calendar by even one timestamp, the partition is
leaking and the test fails.

Around that control, assert the positive behaviour: the vault appears in
`:eligible-instruments` and in `:off-calendar-instrument-ids`; it is absent from
`:return-series-by-instrument` and `:price-series-by-instrument`; it is present in
`:raw-price-series-by-instrument`, `:expected-return-series-by-instrument` and
`:cadence-by-instrument` with `:off-calendar? true`; `:risk-estimation` reports
`:mixed-frequency`; no `:insufficient-common-history` warning is emitted; the
`:history-window` limiting instrument is not the vault; and `:alignment-source :kind` is
`:api-v2-aligned-returns`, which proves the `legacy-fallback-used?` narrowing restored the
backend fast path.

Boundary cases in the same file: a 29-observation series stays on the calendar and is
peeled exactly as today (the floor holds); a 20-bar, 19-day young listing stays on the
calendar (a regression guard for the whole-universe poisoning class of bug); and an
all-sparse universe behaves exactly as it does today (the degenerate guard).

Readiness acceptance, in `setup_readiness_sparse_vault_test.cljs`: with the vault present,
readiness reports `:runnable? true`, an empty blocking-warning list, and no
`:history-assumption-required` warning.

View-model acceptance, in `view_model_sparse_adequacy_test.cljs`: `history-adequacy`
returns `:sparse` for a 31-row, 365-day sparse cadence and still returns `:short` for a
65-row, 65-day dense one; and no assumption card is produced for the sparse asset.

Signature acceptance: a universe with no lane member must produce a `:history` map with no
`:off-calendar-instrument-ids` and no `:freshness-calendar` key, and therefore an unchanged
optimizer input signature.

Human-observable acceptance: on `/portfolio/optimize`, adding Growi HF next to BTC and ETH
must produce a runnable scenario with no assumption card for the vault, a `Sparse history`
badge on its universe row, and a result in which the vault carries a non-zero weight no
greater than 20%.

## Idempotence and Recovery

Every step is a plain source edit and can be repeated safely. `npm run setup:worktree` is
idempotent - it symlinks `node_modules` from the main checkout if missing and otherwise does
nothing. No migration, no destructive operation, and no persisted-state change is involved:
`[:portfolio-ui :optimizer]` is in-memory only, and the new alignment output keys are
derived per run rather than stored.

The one recovery hazard worth naming: because the new output keys are emitted conditionally,
a partially applied Milestone 5 can leave the lane member eligible but starved of native
history. If gates fail midway, revert `alignment.cljs` to `HEAD` and re-apply Milestones 4
and 5 together; they are only meaningful as a pair.

## Artifacts and Notes

The measurement that killed the naive fix, computed against live data:

    vault derived-one-year candidate: 31 samples, 2025-08-25 .. 2026-08-25 (365 days)
    day-aligned, those land on 31 distinct UTC days
    hl:hip3:para:UNITREE: 20 daily bars, 2026-08-04 .. 2026-08-23
    shared days = 2026-08-07, 2026-08-15, 2026-08-23  ->  3 timestamps
    shared RETURN observations = 2      (was ~1095 without the vault)

The estimator comparison between the two candidate windows, using the engine's own
`pair-endpoints` / `interval-log-return` / `annualized-interval-covariance` against real
BTC daily closes:

    derived-one-year:  n=30 intervals, vol 34.27%, vol s.e. 4.42pp (12.9% rel),
                       BTC corr +0.1856 raw, retention 30/60 = 0.500, effective +0.0928
    all-time:          n=64 intervals, vol 26.19%, vol s.e. 2.32pp ( 8.8% rel),
                       BTC corr +0.0219 raw, retention 64/94 = 0.681, effective +0.0149

## Interfaces and Dependencies

New namespace
`src/hyperopen/portfolio/optimizer/application/history_loader/api_v2/sparse_lane.cljs`,
which requires `hyperopen.portfolio.optimizer.domain.history-series` and
`hyperopen.portfolio.optimizer.domain.history-assumptions` (an application namespace may
require a domain namespace; `api_v2/alignment.cljs` already does). It must define:

    (def off-calendar-min-observations ...)   ; = assumption-required-max-observations (30)
    (def off-calendar-min-elapsed-days 90)
    (defn possibly-sparse? [points] ...)      ; -> boolean
    (defn off-calendar-ids [base-candidates aligned-usable?] ...)  ; -> set of ids
    (defn warning [instrument-id cadence] ...) ; -> warning map, code :sparse-native-history

New alignment output keys, both emitted only when the lane is non-empty:

    :off-calendar-instrument-ids   ; vector of instrument-id strings
    :freshness-calendar            ; vector of epoch-ms, for the request-builder re-stamp

`:off-calendar-instrument-ids` is a vector of strings and therefore needs no entry in
`instrument_keyed_codec.cljs`, whose boundary normalization rewrites keys only for
registered instrument-keyed *maps*.

Exact user-facing copy:

Badge label: `Sparse history`

Badge tooltip: `Its own history spans a year or more but the venue publishes it far apart.
Return and risk are estimated from its own samples with the mixed-frequency model, and it
is kept out of the shared daily window so it cannot shorten the other assets' estimate -
its allocation is capped.`

Data-health note, single asset: `Growi HF: 31 samples across 365 days - estimated from its
own samples, outside the shared daily window.`

Data-health note, several assets: `3 assets are estimated from their own sparse samples,
outside the shared daily window`

Legacy-fallback note (replacing the raw keyword leak): `Growi HF: served from Hyperliquid
directly because the optimizer history service has no record for it.`

Weight-cap warning: `sparse history weight cap applied at 20% because its samples are too
far apart to join the shared daily window.`

Gate bookkeeping: `dev/namespace_size_exceptions.edn` entries for
`api_v2/alignment.cljs`, `view_model/universe.cljs`, `request_builder.cljs` and
`domain/constraints.cljs` each need their `:max-lines` raised with an appended `:reason`
sentence naming the off-calendar sparse partition. New test cases must go in new files:
`setup_readiness_test.cljs` and `request_builder_test.cljs` are both within a handful of
lines of their caps.
