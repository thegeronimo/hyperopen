# Stop the optimizer reporting impossible volatility

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`. Keep it self-contained and leave at least one unchecked progress item while the plan remains active.

## Purpose / Big Picture

On 2026-08-23 a user ran a normal four-times-levered scenario at `/portfolio/optimize/draft` and the optimizer told them their portfolio's annualized volatility was **8,697.7%**, with a daily one-standard-deviation move of **±455.3%**. The risk/return scatter reported **7,199.72%** annualized volatility for HYPE — and roughly the same ~7,200% for every other asset in the book, including xyz:AAPL and xyz:SILVER. Apple stock and silver do not have the same volatility as a crypto perpetual. The number was not merely high; it was impossible, and nothing in the product said so. The "Conditioning" diagnostic in the right-hand rail cheerfully read **"Healthy"**.

The correct answer for that book is about **248%**. This was established by running this repository's own compiled estimator against live Hyperliquid data for the user's exact eighteen-asset universe at their exact weights (see `Surprises & Discoveries`). Every formula in the pipeline is correct. What is missing is any check that the *inputs* and the *outputs* of the risk model are physically possible.

After this change, three things are true that are not true today. First, a corrupt return observation — a single daily bar claiming a +30,000% move — is rejected at the point where history is loaded, with a warning that names the asset, the date, the offending value, and the bound it broke, instead of silently poisoning the covariance matrix for every asset in the book. Second, when the Ledoit-Wolf estimator gives up and collapses the covariance to a scaled identity (throwing away every correlation and giving all assets the same volatility), the run says so out loud instead of publishing the result as if it were a normal estimate. Third, the results rail carries a diagnostic that fires on *implausible magnitude*, not just on ill-conditioning, so a number like 7,200% can never again be presented next to the word "Healthy".

You can see it working. Load a scenario whose history contains an implausible bar and the rail shows a warning naming that asset and that date, while the reported volatility returns to a market-plausible figure. Force the degenerate estimator path and the rail's new "Risk magnitude" row reads implausible while "Conditioning" reads "Not usable" instead of "Healthy". Both are reachable from tests alone, and both are described concretely below.

This plan also fixes three smaller confirmed defects found during the same investigation, described in Milestone 4 and Milestone 5.

## Context References

Public refs:

- Direct user request on 2026-08-23. The user was running `http://localhost:8080/portfolio/optimize/draft?ofilter=active&osort=updated-desc&oview=setup&otab=recommendation&odiag=conditioning`, said "Even though I'm using four times leverage, the annualized volatility seems extremely high. I don't think that number is correct. I want you to check the mathematical assumptions behind how we're calculating it," and then added the decisive observation: "it looks like the individual assets annualized volatility is being incorrectly calculated. For example, hype does not have such a high volatility annually." They then asked for this execution plan.

Repo artifacts:

- `/hyperopen/docs/exec-plans/active/2026-06-22-optimizer-default-ledoit-wolf.md` is the parent ExecPlan. It flipped the default risk model to `:ledoit-wolf-dense` and its Decision Log already anticipated this failure in the abstract: "when variance estimates ARE reliable and genuinely heterogeneous, Ledoit-Wolf's scaled-identity target flattens real volatility differences more than diagonal-shrink." This plan addresses the saturated extreme of exactly that tradeoff. That plan also proposed, in its retrospective, that "a future refinement could make the default adaptive — pick Ledoit-Wolf only when conditioning is poor"; this plan does not do that, and Decision Log entry 6 explains why.
- `/hyperopen/docs/exec-plans/active/2026-05-18-optimizer-covariance-shrinkage.md` is the estimator-correctness ticket that built `:ledoit-wolf-dense`.
- `/hyperopen/docs/exec-plans/active/2026-07-08-optimizer-calendar-poisoning-universe-collapse.md` is the precedent for upgrading an anonymous data-drop into per-member named warnings. Milestone 1 follows its pattern.
- `/hyperopen/AGENTS.md` requires `npm run check`, `npm test`, and `npm run test:websocket` when code changes, and requires running `npm run setup:worktree` first in a fresh worktree.

Local scratch refs, non-authoritative:

- None.

## Orientation: how the volatility number is produced

A reader new to this repository needs six files. Read them in this order.

The optimizer loads price history from a backend at `https://price-history.hyperopen.xyz/v1/optimizer/history-bundle`. The response is normalized by `/hyperopen/src/hyperopen/portfolio/optimizer/application/history_loader/api_v2/bundle.cljs` and then aligned onto a shared calendar by `/hyperopen/src/hyperopen/portfolio/optimizer/application/history_loader/api_v2/alignment.cljs`, whose entry point is `align-api-v2-history-inputs`. Alignment produces a map with the key `:return-series-by-instrument`, which maps each instrument id to a vector of daily *simple returns* expressed as decimal fractions — the number 0.03 means a three-percent day. There are two ways it can produce that map. When every selected member has a usable pre-aligned vector from the backend, it copies the backend's `:aligned-returns-by-instrument`. Otherwise it falls back to a client-side path that reads each price point's own `:return` field through `point-return-map` in `/hyperopen/src/hyperopen/portfolio/optimizer/application/history_loader/calendar.cljs`. A third, separate series called `:expected-return-series-by-instrument` is built by `simple-return-series` in `/hyperopen/src/hyperopen/portfolio/optimizer/domain/history_series.cljs` directly from closing prices; it feeds expected returns, not risk, but it also feeds the mixed-frequency risk path.

The covariance matrix is estimated by `estimate-risk-model` in `/hyperopen/src/hyperopen/portfolio/optimizer/domain/risk.cljs`. "Covariance matrix", written Σ, is a square table with one row and one column per asset; the entry on the diagonal for asset i is that asset's variance, and the off-diagonal entry for assets i and j says how much they move together. Its square root on the diagonal is that asset's volatility. `estimate-risk-model` has two branches. If any instrument has a *sparse* trading cadence it routes to `/hyperopen/src/hyperopen/portfolio/optimizer/domain/risk_mixed_frequency.cljs`. Otherwise it takes the dense branch, and for the live default model `:ledoit-wolf-dense` it calls `estimate` in `/hyperopen/src/hyperopen/portfolio/optimizer/domain/risk_ledoit_wolf.cljs`. Both branches multiply the per-day covariance by 365 to annualize it, exactly once.

"Ledoit-Wolf" is a standard statistical technique for estimating a covariance matrix when you have few observations relative to the number of assets. It computes the raw sample covariance S, computes a heavily simplified "target" matrix T, and returns a blend `shrinkage·T + (1 − shrinkage)·S`, choosing the blend weight from the data. In this codebase the target T is a *scaled identity*: every asset gets the same variance μ (the average of the diagonal of S) and every correlation is zero. So `shrinkage = 1` means "discard the sample entirely; assert that all assets have identical volatility and are mutually uncorrelated."

Finally, `/hyperopen/src/hyperopen/portfolio/optimizer/application/engine/payload.cljs` computes the published volatility at line 363 as `(sqrt (math/portfolio-variance target-weights (:covariance risk-result)))`, and `/hyperopen/src/hyperopen/portfolio/optimizer/domain/frontier_overlays.cljs` computes each asset's standalone volatility for the scatter as `sqrt(covariance[i][i])`. The right-hand rail is `/hyperopen/src/hyperopen/views/portfolio/optimize/results_diagnostics_rail.cljs`.

## The defects, precisely

There are six. The first two together produced the 8,697.7%; the rest were found on the way.

**D1 — nothing bounds the magnitude of a return.** Between a bar served by the backend and the covariance matrix there is no winsorization, no clamp, no plausibility check and no warning. `point-return-map` in `calendar.cljs:25-33` admits any value that is `finite-number?`; `usable-aligned-returns?` in `api_v2/alignment.cljs:120-127` checks only finiteness and vector length; `simple-return-series` in `history_series.cljs:40-51` checks only that both closes are finite and positive. A single absurd observation therefore reaches Σ intact.

**D2 — the Ledoit-Wolf shrinkage saturates silently, converting one bad datum into a whole-book failure.** In `risk_ledoit_wolf.cljs:121-125`:

    shrinkage (if (pos? delta-hat)
                (-> (/ beta-hat delta-hat)
                    (max 0)
                    (min 1))
                0)

One outlier drives the ratio `beta-hat/delta-hat` above 1, the `(min 1)` fires, and the covariance built at lines 127-135 becomes `365·μ·I` — a scaled identity. Every correlation is discarded and the single poisoned variance is broadcast onto all eighteen diagonals. This is reported as `{:kind :ledoit-wolf :target :scaled-identity :shrinkage 1}` with **no warning**, and — worse — on the dense branch that `:shrinkage` map never reaches the result payload at all, because `payload.cljs:460` publishes only `:risk-estimation`, which `risk.cljs` sets exclusively in the mixed-frequency branch. There is currently no channel whatsoever by which `shrinkage = 1` can reach the user.

**D3 — ragged series lengths yield a silent all-zero covariance.** `risk_ledoit_wolf.cljs:105-107` guards on `rectangular-series?`, and when the series are not all the same length it falls through to `risk_ledoit_wolf.cljs:142-147`, returning `(zero-matrix feature-count)` with `{:shrinkage 0}` and no warning. Because `:ledoit-wolf-dense` is the live default (`application/request_builder.cljs:15-16`), ragged history makes the whole portfolio report **0% volatility** with no explanation. This is the opposite failure from D1/D2 and is equally dishonest. Reproduced live.

**D4 — the conditioning diagnostic is structurally blind to this.** `covariance-conditioning` in `risk.cljs:138-158` returns the ratio of the largest to the smallest eigenvalue. A scaled identity has every eigenvalue equal, so its condition number is exactly 1 and its status is `:ok`. The rail prints "Healthy". Nothing anywhere asserts that an annualized variance is *plausible in absolute terms*. This is not a bug in the function — a ratio is the right thing for conditioning — but it means the one diagnostic the user trusted could never have caught this.

**D5 — `augment-risk-result-with-assumptions` drops the PSD-repair warning.** At `risk.cljs:344` the destructure is `{repaired :covariance} (repair-psd covariance)`, discarding the `:warning` key. The sibling call site at `risk.cljs:213-214` captures it as `psd-warning` and threads it through. So when a history assumption pushes the matrix indefinite and the code diagonally loads it back, the user is never told.

**D6 — `-Infinity` leaks into SVG coordinates.** `/hyperopen/src/hyperopen/views/portfolio/optimize/leverage_impact_panel.cljs:267-269` computes `(js/Math.log p5-ending-factor)` with no zero guard. At the volatility levels D1/D2 produce, the ending factors underflow to 0, `Math.log 0` is `-Infinity`, and that lands in the `:x1` and `:cx` attributes. This is the downstream cause of the "$0 → $0" and "Touch −50% odds 100% → 100%" cells the user saw.

## Milestones

### Milestone 0 — Reproduce, in tests, before changing anything

Nothing is fixed in this milestone. At the end of it the repository contains a new test namespace that *fails*, and the failure is an exact, checked-in description of the bug. This matters because every one of these defects is a silent one: without a red test first, a later refactor cannot tell whether the fix still holds.

Create `/hyperopen/test/hyperopen/portfolio/optimizer/domain/risk_degeneracy_test.cljs`, namespace `hyperopen.portfolio.optimizer.domain.risk-degeneracy-test`. New test files need no registration — `tools/generate-test-runner.mjs` auto-collects every `*_test.cljs` under `test/hyperopen` and derives the namespace from the path. Do **not** put these tests in `risk_test.cljs`: that file is at 430 lines against a hard 500-line ceiling enforced by `dev/check_namespace_sizes.clj`, and these tests are roughly 110 lines.

The namespace needs three deftests. The first is a baseline proving the estimator behaves on clean input: feed a small rectangular set of three assets with plausible daily returns, assert the shrinkage is well below 1, assert the three diagonal volatilities are `distinct?`, and assert at least one off-diagonal entry is non-zero. The second reproduces D2: take the same fixture, replace one observation in one asset with `30.0` (a +3,000% day), and assert that the shrinkage is exactly `1`, that every diagonal entry is equal, that the off-diagonals are all `0`, and that `covariance-conditioning` returns `:status :ok` with condition number `1` — this last pair is the D4 characterization, and it is the assertion that makes the blindness impossible to miss. Then add the assertion that must be red: that `(:warnings result)` contains a warning whose `:code` is `:risk-shrinkage-saturated`. The third reproduces D3: pass `{"A" [0.01 -0.02 0.03 -0.01] "B" [0.02 -0.01 0.01]}` through `estimate-risk-model` with `:kind :ledoit-wolf-dense` and assert red that a `:ragged-return-series` warning is present and that the covariance is not entirely zero.

Pin the exact numbers by running the fixture once and copying the values, rather than hand-computing them; use the `near?` helper pattern already present in `risk_test.cljs`. Two facts make these fixtures reliable and were verified at runtime: a history containing only `:return-series-by-instrument` never routes to mixed-frequency (`mixed-frequency?` needs an explicit kind, a `[:risk-estimation :kind]` marker, or a sparse cadence, and with no price rows `sparse?` is false), and `sorted-instrument-ids` sorts the union of keys so `["A" "B" "C"]` ordering is stable and assertable.

Acceptance for this milestone: `npm test` reports the three new saturation/ragged assertions as failures and the rest of the suite green — the current baseline is 6,074 tests / 33,288 assertions passing. Record the exact counts in `Progress`.

### Milestone 1 — Reject implausible returns where history is ingested

This is the root fix. After this milestone, an implausible bar never reaches the covariance, and the user is told which asset and which date were rejected.

Add a new namespace `/hyperopen/src/hyperopen/portfolio/optimizer/domain/return_plausibility.cljs` holding two constants and one predicate, so that the engine, the loader, and the tests can never disagree about the bound. The rejection bound is a simple return of magnitude 2.0 — that is, a single daily bar moving more than ±200%. The advisory bound is 1.0, or ±100%. Both are decimal fractions matching the rest of the engine.

These numbers are measured, not guessed. Across 60 Hyperliquid perpetuals and 51,900 daily observations pulled from the production history backend on 2026-08-23, the median absolute daily return is 2.66%, the 99th percentile is 19.49%, the 99.9th is 42.44%, the 99.99th is 86.54%, and the single largest observation in the entire sample is 126.1% (BOME). Exactly four observations out of 51,900 exceed 100%, and **zero** exceed 200%. Meanwhile the corruption this plan is chasing requires a return of roughly +300, i.e. +30,000%, which is 150 times above the rejection bound. There is therefore a very wide margin on both sides: no real bar in three years of data would be rejected, and the corrupt bar is rejected by two orders of magnitude. Record this measurement in `Surprises & Discoveries` so a future contributor can re-derive the bound rather than treating it as arbitrary.

Apply the guard at three sites, which together are the minimal set that sees every return reaching a risk or return model. There is no single choke point; do not go looking for one.

The first is `point-return-map` in `history_loader/calendar.cljs:25-33`. Tighten its predicate from "finite" to "finite and plausible". This is the sole gate on the client-side point-level path, and everything downstream funnels through it: `point-level-return-calendar`, `returns-from-point-level`, `finite-return-times`, `peel-poisoning-members`, and `alignment/point-return-count`. Tightening here propagates coherently with no other change — implausible bars vanish from the shared calendar, the member's observation count falls, and the existing `:insufficient-return-history` exclusion at `api_v2/alignment.cljs:402` fires if it falls below the bar.

The second is `usable-aligned-returns?` in `api_v2/alignment.cljs:120-127`, the sole gate on the backend-aligned path. Add a plausibility conjunct. This flips `use-aligned?` to false, which routes the universe onto the point-level path where the first site then drops the offending timestamps. That demotion is established, load-bearing behaviour, not a new risk: multi-chunk loads already demote routinely because `history_api_v2_client/merged-calendar` intersects chunk calendars and the `(= (count return-calendar) (count returns))` check then fails. One caveat to cover with a test fixture: demotion requires the served series to carry per-point `:return` values, and `bundle/normalize-point` only sets `:return` when the payload has it. If a bundle ever carries aligned returns with no per-point returns, demotion would collapse the calendar. Add an explicit branch that keeps the member excluded-with-warning rather than emptying the universe.

The third is `simple-return-series` in `domain/history_series.cljs:40-51`. This path is covered by neither of the others and is not optional: it is the sole producer of `:expected-return-series-by-instrument`, which `domain/returns.cljs:14-16` *prefers* over `:return-series-by-instrument`, so a shared-calendar-only guard would leave the expected-return vector still poisoned. It is also what `risk_mixed_frequency.cljs:90` calls for the dense block. Here there is no shared timestamp to remove, so the only options are dropping the observation or excluding the member; drop the observation. Take care not to introduce a length mismatch with its sibling `return-intervals`: `returns.cljs:65` guards with `(when (= (count intervals) (count series)) …)` and on mismatch silently falls through to a different estimator with no warning. Whatever you drop from one, drop from the other.

Do not winsorize, and do not clamp. The house precedent is unambiguous: `domain/rebalance.cljs:106-128` detects an implausible favorable fill and, rather than clamping it to zero, marks the estimate `:source :untrusted-snapshot-fill` with `:fallback-reason :implausible-favorable-fill`, on the explicit reasoning that a silent clamp would be "deceptive". Its test pin is `rebalance_test.cljs:523`. Mirror that: reject the observation, never rescale it, and emit a per-asset warning carrying `:code :implausible-return-observation`, the instrument id, the timestamp, the offending value, and the bound. A warning at the advisory bound should carry a distinct code and *keep* the observation.

Critically, the guard must **not** go inside `estimate-risk-model` or `covariance-matrix`. Four existing deftests at `risk_test.cljs:113-114, 127-128, 141-142, 173-174` feed `{"A" [1 2 3] "B" [2 4 6]}` as pure linear-algebra fixtures with `:periods-per-year 1` and assert exact covariance matrices derived from them. Any clamp at the estimator would silently rewrite all four expected matrices. Keeping the guard upstream leaves them untouched, which is also the architecturally correct answer: the estimator's job is arithmetic, and data quality is the loader's job.

Acceptance: a scenario whose history contains a +30,000% bar loads with that bar rejected, the rail shows the named warning, and the reported volatility returns to a plausible figure. In tests, a fixture with one implausible observation produces a covariance whose diagonals are differentiated and market-plausible, plus the warning.

### Milestone 2 — Make the Ledoit-Wolf estimator honest

Milestone 1 removes the trigger. Milestone 2 makes the estimator refuse to fail silently if anything else ever produces the same condition — a different data source, a future loader, an unforeseen degeneracy.

First, decide what saturation means. It is a genuine data-quality alarm, not a normal statistical outcome, and this was tested rather than assumed. Driving clean real data into the regime Ledoit-Wolf shrinkage exists to handle — 40 assets against 30 observations, then 40 against 10 — produced shrinkage of 0.894 and 0.806 respectively. Shrinking the real seven-asset crypto sample from 430 observations down to 3 moved shrinkage only between 0.02 and 0.48. Clean data never reached 1.0 in any configuration tried. A single corrupt observation reached exactly 1.0 immediately. So an alarm at saturation is safe.

In `risk_ledoit_wolf.cljs`, have `estimate` return a `:warnings` vector on both return paths. Emit `:risk-shrinkage-saturated` when the shrinkage is at or above 0.99, carrying the computed shrinkage, the sample count, and the feature count, with a message explaining in plain language that all correlation structure was discarded and every asset was assigned the same volatility. Use 0.99 rather than exactly 1.0 so that a near-saturated estimate — which is nearly as degenerate — is also caught. Emit `:ragged-return-series` on the non-rectangular path, carrying the per-series lengths so the rail can say which asset is short. Note that the existing `:sample-count` on that path reports the length of the *first* series only, which is itself misleading; pin the corrected behaviour in a test.

For D3, the ragged path must stop returning an all-zero matrix. Falling back to the pairwise sample covariance is the honest choice: `math/sample-covariance` requires equal lengths and yields nil (hence 0) for unequal pairs, but the diagonals are computed from each series against itself and are therefore always correct. That produces a matrix that understates diversification but reports each asset's real volatility — which is conservative and honest, where all-zeros is neither. Emit the warning either way.

Then plumb the warnings out. In `risk.cljs:265`, change `:warnings warnings*` to concatenate the Ledoit-Wolf result's warnings: `:warnings (vec (concat warnings* (:warnings ledoit-wolf-result)))`. Do **not** instead add `:warnings` to the `select-keys` vector at `risk.cljs:266-268` — that runs through `merge` and would overwrite `warnings*`, silently dropping the cadence and `:risk-model-renamed` warnings. Also add `:shrinkage` to the published payload in `payload.cljs`, so that the degeneracy is inspectable and not merely narrated; today the dense branch publishes no shrinkage at all.

Once a warning is in `(:warnings risk-result)` it reaches the user with **no UI work**: `payload.cljs` splices it into the result payload, and `results_diagnostics_rail.cljs:104-131` (`warning-rows`) renders any code generically via `opt-format/keyword-label` plus the warning's `:message`. There is no allowlist of warning codes to register. Verify this rather than assuming it, with one payload-level test asserting the code survives into the payload's `:warnings`.

The mixed-frequency dense block needs the same treatment separately. `risk.cljs:209-211` passes `:dense-block-estimator :ledoit-wolf-dense` into `mixed-frequency/matrix`, and `risk_mixed_frequency.cljs` already has the exact pattern to copy: `dense-ledoit-wolf-block` returns `{:block … :warnings …}` and emits `:dense-block-ledoit-wolf-unavailable` with a `:reason`. Mirror the existing test at `risk_test.cljs:301-316`.

Acceptance: the three Milestone 0 tests go green. Forcing a saturated estimate shows a "Risk shrinkage saturated" warning row in the results rail.

### Milestone 3 — A diagnostic that catches magnitude, not just conditioning

D4 is why this shipped, and it is worth fixing on its own terms: had any check asserted that 7,200% is not a possible annualized volatility, the user would have been told rather than left to notice.

Add a plausibility check over the covariance diagonal alongside `covariance-conditioning` in `risk.cljs`. It should report the maximum annualized volatility on the diagonal and a status, flagging when any diagonal volatility exceeds 5.0, that is 500% annualized. This bound is also measured: across the same 60-perpetual sample the highest single-asset annualized volatility is 390%, the 95th percentile is 188%, and the median is 110%. A 500% ceiling therefore clears every real asset with room to spare while sitting fourteen times below the 7,200% that prompted this work. Report the offending instruments by id so the rail can name them.

Then surface it. Read `results_diagnostics_rail.cljs` and add a row next to CONDITIONING using the same row structure and status vocabulary, so that a degenerate or implausible covariance reads as a caution rather than "Healthy". If a new row proves awkward, the acceptable alternative is to fold the magnitude status into the existing CONDITIONING row so that it cannot report `:ok` while the diagonal is implausible — but a separate row is preferred, because conditioning and magnitude are genuinely different questions and collapsing them is what caused the blindness.

Consider whether this should also gate the run. There are two independent warning pipelines in this codebase: a pre-run readiness pipeline that can block a run, and a post-run engine-result pipeline that is purely advisory. The only existing bridge from the risk domain into the blocking pipeline is `risk/missing-native-risk-history-warnings`, called from `setup_readiness/build-readiness`. Blocking on saturation alone would be too aggressive, since high shrinkage can be legitimate. The defensible rule is to block only on the *conjunction* of saturation and implausible magnitude, since neither alone is conclusive and together they are unambiguous. Implement the diagnostic first, decide the gate second, and record the decision in the Decision Log.

Acceptance: a run whose covariance diagonal implies 7,200% volatility shows a caution row naming the affected assets. The rail never shows "Healthy" for a scaled-identity matrix.

### Milestone 4 — The two small confirmed defects

Fix D5 by making `risk.cljs:344` capture and thread the repair warning exactly as `risk.cljs:213-214` does. Test it in `history_assumptions_test.cljs`, which is at 154 lines and has ample headroom; the house style there passes hand-built `{:model … :instrument-ids … :covariance …}` maps directly rather than going through the engine. Build a fixture that genuinely goes indefinite — a near-perfectly-correlated base pair plus a 0.99 correlation floor against a high-volatility assumed asset is the reliable way — and confirm it by running `covariance-conditioning` on the augmented matrix before asserting, rather than guessing the numbers.

Fix D6 by guarding the three `js/Math.log` calls at `leverage_impact_panel.cljs:267-269` against non-positive input, so a marker coordinate is always finite. Note this file is at 443 lines against the 500 ceiling; keep the guard small. Test in `leverage_impact_panel_test.cljs` by asserting that no `:x1`, `:x2`, or `:cx` attribute stringifies to something containing "Infinity" or "NaN". Read the override keys off `test/hyperopen/portfolio/optimizer/fixtures.cljs` `sample-solved-result`, and note that the panel is gated off below a leverage threshold — `leverage_impact_panel_test.cljs:26-27` asserts `nil` below the gate — so the fixture must clear that gate or the test asserts against nothing.

### Milestone 5 — Close the annualization test hole

Four deftests in `risk_test.cljs` (lines 112, 126, 140, 172) pass `:periods-per-year 1`, which neutralizes the annualizer. The consequence, confirmed by mutation during the investigation, is that changing `risk.cljs:31` to `(* periods-per-year periods-per-year (sample-covariance …))` leaves the entire 6,074-test suite green. A squared annualization is precisely a 19.1× volatility error — the same order as the bug this plan exists to prevent — so this hole is directly load-bearing.

Add deftests that pin the annualization at 365 end to end: one for `:sample-covariance`, one for `:diagonal-shrink`, and one confirming that omitting `:periods-per-year` defaults to daily annualization. Use a small fixed return series and assert the exact expected annualized variance, so that squaring the factor fails. The live Ledoit-Wolf and mixed-frequency annualizers already have independent reference implementations pinning them at 365 (`risk_ledoit_wolf_test.cljs:98` and `:125`, `risk_test.cljs:41-53` and `:368`), so this milestone closes the remaining `:sample-covariance` and `:diagonal-shrink` gap. These three tests are small; `risk_test.cljs` at 430 lines can absorb roughly 35 more and stay under 500, but state that budget explicitly so a later edit does not blow the ceiling.

Do not change the four existing fixtures' return values. They are legitimate linear-algebra fixtures and Milestone 1 deliberately keeps the plausibility guard upstream so they remain valid.

## Progress

- [x] (2026-08-23) Diagnosed the defect end to end. Proved the formulas correct by running this repo's compiled `risk/estimate-risk-model` in the browser against live Hyperliquid candles for the user's exact 18-asset universe at their exact weights: portfolio volatility 248.5%, per-asset volatilities differentiated and plausible (ETH 66.8%, SOL 69.8%, HYPE 94.9%, ETHFI 107%, xyz:AAPL 25.6%, xyz:NVDA 39.8%, xyz:SILVER 77.2%, xyz:UNITREE 135.8%). Confirmed the same for `:diagonal-shrink` (248.3%) and `:mixed-frequency` (263.5%).
- [x] (2026-08-23) Reproduced the collapse: injecting one +3,000% observation into a single asset drives Ledoit-Wolf shrinkage from 0.0197 to exactly 1 and makes all seven diagonals identical at 1,047%. A shared +2,700% print gives 2,486% for all seven.
- [x] (2026-08-23) Verified D5 (`risk.cljs:344` drops the PSD warning), D6 (`leverage_impact_panel.cljs:267-269` unguarded `Math.log`), and the annualization test hole (`risk_test.cljs` lines 112/126/140/172).
- [x] (2026-08-23) Confirmed the working tree is clean of the mutation-harness edits observed during the audit — `grep` for `(* periods-per-year periods-per-year` in `risk.cljs` and `risk_ledoit_wolf.cljs` returns nothing.
- [x] (2026-08-23) Measured the plausibility bounds against 60 perpetuals / 51,900 daily observations from the production history backend (figures in Milestone 1 and `Surprises & Discoveries`).
- [x] (2026-08-23) Milestone 0: added `test/.../domain/risk_degeneracy_test.cljs`. Ended RED exactly as designed - 13 assertions failing, all in the new namespace, rest of the suite green at 6,076 tests / 33,313 assertions. The characterization half PASSED, proving the bug: shrinkage exactly 1, all diagonals equal, `covariance-conditioning` reporting `:ok` with condition number 1.
- [x] (2026-08-23) Milestone 1: added `domain/return_plausibility.cljs` (bounds 2.0 reject / 1.0 advisory / 5.0 volatility, all measured). Guard applied at `calendar/point-return-map`, `api_v2/alignment/usable-aligned-returns?`, and `history_series/simple-return-series` + `return-intervals` (identically, via a shared `contaminated-pairs`, so the two cannot desync). Warnings built in `calendar/plausibility-warnings` and spliced into alignment's warning list.
- [x] (2026-08-23) Milestone 2: `risk_ledoit_wolf/estimate` now returns `:warnings` on both paths (`:risk-shrinkage-saturated` at >= 0.99, `:ragged-return-series` with the offending lengths). `estimate-risk-model` concats them (never merges) and substitutes the pairwise sample covariance on the ragged warning. `:risk-shrinkage` published in the payload - previously the dense branch gave shrinkage NO channel to the UI at all.
- [x] (2026-08-23) Milestone 3: added `risk/covariance-plausibility`, published via `domain/diagnostics` as `:covariance-plausibility`, and surfaced as a new "Risk magnitude" trust row. Conditioning now reads "Not usable" instead of "Healthy" whenever magnitude fails.
- [x] (2026-08-23) Milestone 4: D5 fixed (`risk.cljs` `augment-risk-result-with-assumptions` now threads the `repair-psd` warning) and D6 fixed (`leverage_impact_panel` `x-px` clamps non-finite input). Both covered by new tests.
- [x] (2026-08-23) Milestone 5: added three annualization guards to `risk_test.cljs`. **Verified they fire**: squaring `risk.cljs:32` - the mutation that previously survived the whole suite - killed 11 assertions across three of the new tests. Mutation reverted and confirmed clean.
- [x] (2026-08-23) `npm run gates`: **34/34 PASS**, 6,830 tests / 36,831 assertions, 2m53s. Final `npm test` 6,089 tests / 33,367 assertions, 0 failures, 0 errors (from a 6,074/33,288 baseline).
- [x] (2026-08-23) End-to-end acceptance on REAL data (7 Hyperliquid perps, 430 daily bars) through the real compiled estimator and the real shared-calendar loader path. Clean: 257% portfolio, vols [67 107 95 98 97 70 95]%, magnitude `:ok`, no warnings - i.e. **the guard is a no-op on real data**. With a +40,000% close injected into HYPE: **242%**, vols [67 106 95 97 97 70 94]%, magnitude `:ok`, no warnings, shrinkage 0.0239. Before the change the same input produced a scaled-identity collapse at ~9,000% for every asset.
- [ ] Re-run the user's own saved scenario in their session and confirm the reported volatility is market-plausible and any rejected observation is named on screen. BLOCKED: `:8080` is served by the user's own shadow-cljs process against the main checkout, not this worktree, and the scenario is a per-wallet IndexedDB draft this session cannot reach. Needs the user to run the worktree build against their wallet. Then move this plan to `completed/`.

## Surprises & Discoveries

- Observation: The pipeline's mathematics is entirely correct. The `w'Σw` product, the single ×365 annualization on all three estimator branches, the square-root-of-time ladder, the `format-pct` single ×100, weights-as-NAV-fractions, id-to-covariance index alignment, `parse-percent-text` dividing by 100, and the assumption units (0.8 meaning 80%) all check out. A 56-agent adversarial audit refuted every proposed formula defect. The failure is entirely one of input validation and output plausibility.
  Evidence: running the repo's own `estimate-risk-model` against live candles for the user's universe and weights returned 248.5%, against the 8,697.7% on screen.

- Observation: Clean data never saturates the Ledoit-Wolf shrinkage, which is what makes saturation a safe alarm.
  Evidence: seven real crypto assets at 430 / 120 / 60 / 30 / 15 / 10 / 5 / 3 observations gave shrinkage 0.020 / 0.098 / 0.208 / 0.387 / 0.481 / 0.411 / 0.421 / 0.218. Synthetic clean data at 40 assets against 30 observations gave 0.894, and at 40 against 10 gave 0.806. One corrupt observation gave exactly 1.

- Observation: A corrupt observation does not merely inflate one asset — under the default risk model it flattens the entire book, which is why the user's scatter showed silver and Apple at the same volatility as HYPE.
  Evidence: with a single +3,000% print in HYPE only, all seven assets reported 1,047%; the spread across genuinely different assets went from 67–107% to zero.

- Observation: Under a scaled identity, Equal-Risk contributions reduce in closed form to `w_i² / Σw²`, which explains the user's first screenshot exactly.
  Evidence: seven names at w=0.5 and eleven at w=0.05 give `0.25/1.7775 = 14.06%` and `0.0025/1.7775 = 0.141%`. The screen showed 14.1% for all seven, with identical +8.5-point deviations, and ~0.1% for the rest. Under any real covariance those seven would differ.

- Observation: The production history backend was clean at the time of investigation, so the corrupt datum is session-local — a cached or merged bundle, or an instrument outside the fetched set. This does not weaken the plan: the guard is the fix regardless of which series carries the bad bar.
  Evidence: a fresh `POST /v1/optimizer/history-bundle` for the user's universe returned fractional returns with sane annualized volatilities (HYPE 97.6%, ETH 60.9%, SOL 55.6%) and a maximum absolute return of 0.494 across more than 15,000 observations.

- Observation: The backend aligns only the `crypto_24_7` members. Requesting twenty instruments returned `aligned_returns_by_instrument` for just the eight 24/7 crypto names; every HIP-3 equity and commodity perpetual came back with no aligned entry, so `use-aligned?` is false in practice for mixed universes and the client-side point-level path runs. This matters for Milestone 1: both paths are live and both need the guard.
  Evidence: `alignedFor: ["HYPE","ETH","SOL","LDO","ETHFI","NEAR","TAO","PUMP","xyz:SPCX"]` for a twenty-member request.

- Observation: Empirical bounds for the guard, measured across 60 perpetuals and 51,900 daily observations. Median absolute daily return 2.66%; p99 19.49%; p99.9 42.44%; p99.99 86.54%; maximum 126.1%. Four observations exceed 100%; **zero** exceed 200%; zero exceed 1000%. Per-asset annualized volatility ranged from 47% to 390%, median 110%, p95 188%.
  Evidence: aggregated from a `lookback_days: 1095` bundle over the first 60 `hl_perp` instruments in discovery.

- Observation: A corrupt price produces TWO bad returns, not one, and only one of them breaks any bound. A close spiking to 400x gives +40,000% (rejected) and then -99.75% coming back - which is inside any sane bound yet just as poisonous to a variance. The first version of the guard dropped only the spike and still left the asset at 613% volatility. The fix condemns every pair touching a suspect close, which is also direction-symmetric: a collapse to near zero gives the bounded leg FIRST.
  Evidence: the plan's own end-to-end test failed on its first run with `(not (every? #object[Function] [6.132526535198904 0.5174038036051373 0.4701933654392118]))`.

- Observation: Dropping bars per-instrument would have caused a second, quieter failure. It desyncs series lengths, which trips the Ledoit-Wolf ragged path, which discards that asset's correlations entirely - the book came back at 232% with `:ragged-return-series` and shrinkage 0. The real loader avoids this because `point-level-return-calendar` keeps only timestamps EVERY member can supply, so a dropped bar leaves the shared calendar for all of them and the vectors stay rectangular. Pinned by `dropping-a-bar-keeps-every-series-the-same-length-test`.
  Evidence: real-data run through `simple-return-series` alone gave `warnings [:ragged-return-series]` and 232%; the same data through `returns-from-point-level` gave no warnings and 242%.

- Observation: The IndexedDB history cache does NOT bypass the guard. `history_cache/persisted-history-keys` persists `:api-v2-history` - the raw normalized bundle - not the aligned `:history`, so a restored draft re-runs alignment and is guarded. This mattered because a cached bundle was the leading suspect for the user's session-local corruption.

- Observation: The guard deliberately does not sit inside `estimate-risk-model`, so poison injected directly into `:return-series-by-instrument` still collapses the covariance. That is by design (four existing deftests feed `[1 2 3]` as pure linear-algebra fixtures), and the Milestone 2/3 backstop is what covers it: the same input now reports `:risk-shrinkage-saturated` and magnitude `:implausible` instead of publishing 1,047% per asset as a risk estimate.

- Observation: A mutation-testing harness was seen rewriting `risk.cljs:31`, `risk_ledoit_wolf.cljs:131`, and `risk_mixed_frequency.cljs:321` during the audit. The tree is clean now, but anyone reproducing this work must `git diff` first — a stray squared annualization alone produces a 19.1× volatility error and would send an investigator down the wrong path.

## Decision Log

- Decision: Place the plausibility guard in the history-ingestion layer, not in the estimator.
  Rationale: Four existing deftests at `risk_test.cljs:113-114, 127-128, 141-142, 173-174` feed `{"A" [1 2 3] "B" [2 4 6]}` as pure linear-algebra fixtures and assert exact matrices; a clamp in `covariance-matrix` would silently invalidate all four. Independently, this is the correct separation: the estimator does arithmetic, the loader owns data quality. It also means the guard covers the expected-return vector, which a covariance-only guard would miss.
  Date/Author: 2026-08-23 / Claude.

- Decision: Reject implausible observations; never winsorize or clamp them.
  Rationale: The house precedent is `domain/rebalance.cljs:106-128`, which refuses to clamp an implausible favorable fill to zero on the stated grounds that the clamp would be "deceptive", and instead marks the estimate untrusted with a named reason. A winsorized return would produce a plausible-looking number derived from data the system knows is wrong, which is the same class of dishonesty as the 8,697.7% itself.
  Date/Author: 2026-08-23 / Claude.

- Decision: Set the rejection bound at |r| ≥ 2.0 and the advisory bound at |r| ≥ 1.0.
  Rationale: Measured, not assumed. Zero of 51,900 real daily observations exceed 2.0 and only four exceed 1.0, while the corruption under investigation requires roughly 300. The bound therefore has a 150× margin against the failure and an infinite margin against observed real data. The advisory tier exists so that a genuine extreme move (BOME's 126% day) is disclosed rather than either hidden or discarded.
  Date/Author: 2026-08-23 / Claude.

- Decision: Warn at shrinkage ≥ 0.99 rather than capping the shrinkage below 1.
  Rationale: Capping would hide the condition while still producing a near-degenerate matrix, trading a visibly wrong number for an invisibly wrong one. Saturation is a symptom; the disease is the input, which Milestone 1 removes. The warning is the backstop for whatever Milestone 1 does not catch. The 0.99 threshold rather than exactly 1.0 catches near-saturation, which is nearly as degenerate.
  Date/Author: 2026-08-23 / Claude.

- Decision: On ragged input, fall back to the pairwise sample covariance rather than continuing to return an all-zero matrix.
  Rationale: All-zeros reports 0% portfolio volatility for a real book — a false negative exactly as dangerous as the false positive this plan fixes, and more likely to be believed. The pairwise sample covariance gets every diagonal right and zeroes only the unequal-length off-diagonals, which understates diversification and is therefore conservative. Both behaviours carry the warning; only the fallback is honest about magnitude.
  Date/Author: 2026-08-23 / Claude.

- Decision: Do not make the default risk model adaptive, despite the parent ExecPlan floating that idea.
  Rationale: `/hyperopen/docs/exec-plans/active/2026-06-22-optimizer-default-ledoit-wolf.md` suggested picking Ledoit-Wolf only when conditioning is poor. That would not have prevented this bug — conditioning reported "Healthy" throughout — and it would add a data-dependent branch to the default path, making runs harder to reason about. Validating inputs and disclosing degeneracy addresses the actual failure; switching estimators addresses a symptom.
  Date/Author: 2026-08-23 / Claude.

- Decision: Block a run only on the conjunction of shrinkage saturation and implausible diagonal magnitude, if a block is added at all; implement the diagnostic first and decide the gate second.
  Rationale: High shrinkage alone can be legitimate on thin data, which is the whole reason Ledoit-Wolf is the default; blocking on it would break the common case the parent ExecPlan optimized for. Implausible magnitude alone might in principle be a genuinely extreme book. Together they are unambiguous. Deferring the gate decision until the diagnostic exists avoids guessing at a threshold before there is data on how often it fires.
  Date/Author: 2026-08-23 / Claude.

## Validation and Acceptance

Run every gate from the repository root. In a fresh worktree run `npm run setup:worktree` first — a worktree has no `node_modules` and `shadow-cljs` is not on `PATH`, so an unbootstrapped checkout makes every gate fail with an opaque error that is environmental, not a code defect. Then run `npm run gates`, which derives its list from the `check` script and appends `npm test` and `npm run test:websocket`, producing a single PASS/FAIL matrix without short-circuiting on the first failure. The current baseline to beat is 6,074 tests containing 33,288 assertions, zero failures.

Two structural gates constrain this work and will fail the build if ignored. `npm run lint:namespace-sizes` enforces a hard 500-line ceiling per file, scanning both `src` and `test`; `risk_test.cljs` is at 430, `leverage_impact_panel.cljs` at 443, and `risk.cljs` at 347. If a file must legitimately exceed the ceiling, add an entry to `dev/namespace_size_exceptions.edn` with all five required keys — `:path` (must resolve to a scanned file), `:owner` (lowercase kebab), `:reason` (non-blank prose naming what merged and what split will retire it), `:max-lines` (positive integer), and `:retire-by` (a `YYYY-MM-DD` date not in the past). An exception cannot be added pre-emptively: an entry whose file is still under 500 lines fails as `[stale-size-exception]`. `npm run lint:docs` validates this very file, requiring the six section headings, at least one unchecked `- [ ]` progress item, and a durable context reference while it remains in `active/`.

Behavioural acceptance, which is what actually matters. Load the user's scenario at `/portfolio/optimize/draft`. The reported annualized volatility must be market-plausible — for a four-times-levered crypto book, roughly 200–350%, and specifically not four digits. The risk/return scatter must show differentiated per-asset volatilities: HYPE near 95%, ETH near 67%, xyz:AAPL near 26%, xyz:SILVER near 77%. If any observation was rejected, the results rail must show a warning row naming the asset and the date. If the covariance is degenerate for any reason, the rail must show a caution row rather than "Healthy", and the published payload must carry the shrinkage value.

Test acceptance. Every one of the three Milestone 0 alarm assertions must go from red to green, and none of them may be deleted to achieve it. The three Milestone 5 annualization tests must fail if `risk.cljs:31` is temporarily changed to `(* periods-per-year periods-per-year (sample-covariance …))` — verify this by actually making that edit, observing the failure, and reverting it; a guard test that has never been seen to fail is not a guard. `npm test` and `npm run test:websocket` must report zero failures and zero errors. Record the final counts in `Progress`.

## Idempotence and Recovery

Every change is additive: one new domain namespace, one new test namespace, new warning codes, tightened predicates, and a new diagnostic. Re-running any milestone is safe. Reverting is per-milestone and independent — the plausibility guard, the estimator warnings, the magnitude diagnostic, and the two small fixes have no ordering dependency beyond Milestone 0 preceding Milestones 2 and 3, and none of them touch persisted data.

There is no migration. Saved scenarios are unaffected because they carry their own explicit risk model and are re-solved from freshly loaded history on every run. The one user-visible behavioural change is that a scenario whose history contains an implausible bar will now load with that bar excluded and a warning shown, where previously it silently produced a corrupt covariance; that is the intended effect, and the warning makes it self-explanatory rather than mysterious.

## Outcomes & Retrospective

All six defects are fixed and every gate passes: **34/34**, 6,830 tests / 36,831 assertions. The optimizer no longer has a path by which an impossible volatility reaches the screen unremarked. A corrupt bar is rejected and named at ingestion; if one reaches the estimator anyway, saturated shrinkage is reported instead of published; and a magnitude check now stands beside the conditioning ratio that was structurally incapable of catching this.

Measured on real data, the change is a no-op when nothing is wrong (257% before and after, identical per-asset volatilities) and decisive when something is: a +40,000% bar that previously collapsed the whole book to ~9,000% per asset now costs three of 430 observations and lands at 242%.

Complexity is modestly up and the trade is worth it: one new 190-line domain namespace, three tightened predicates, two new warning codes, one new diagnostic, and one new trust row, against a class of failure that produced a confidently-wrong number with a green health indicator beside it. Two namespace-size exceptions were raised (`api_v2/alignment.cljs` 580 to 600, `results_diagnostics_rail.cljs` 530 to 560), each with the reason and retirement path recorded.

Three things are worth carrying forward. First, the bound is measured rather than chosen, and the measurement is written down in the namespace docstring so the next contributor can re-derive it instead of treating 2.0 as folklore. Second, the plan's own end-to-end test caught a genuine design error on its first run - the guard initially dropped only the spike and not the revert - which is the argument for writing the acceptance test before believing the fix. Third, and most important: every formula in the chain was individually correct and every unit was individually consistent, and the system still produced an answer wrong by a factor of thirty-five, because no component was responsible for asking whether the final number was possible. Scale-invariant diagnostics such as a condition number cannot answer that question by construction. Note also that this bug was found by a user's intuition about a rendered number and not by any of 6,074 tests, and consider what other published quantities in this product have no plausibility bound at all.
