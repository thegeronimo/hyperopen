# Keep the optimizer's WebAssembly solver alive for a whole run, and stop paying for work nobody reads

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md` (the full ExecPlan writing contract) and `/hyperopen/docs/PLANS.md` (the public planning entry point).

## Purpose / Big Picture

Commit `9d85e56c0` added `'wasm-unsafe-eval'` to the release Content-Security-Policy, which lets the bundled OSQP WebAssembly solver run in production for the first time. That was the right fix — the pure-JavaScript fallback it replaces is 14.5x slower on a whole solve and 67.6x slower in the kernel — but it moves production onto a code path nobody had ever exercised at scale, and that path has a memory leak.

`osqp@0.0.2` leaks WebAssembly heap on every solve. Its CSC helper issues three `_malloc` calls per matrix and returns only the struct pointer; `cleanup()` frees the struct and never the six arrays behind it. Against a fixed, non-growable 16 MiB heap that gives every run a hard solve budget, and `infrastructure/osqp.cljs` spends one setup/solve/cleanup cycle per solve. A default run issues 56 solves. At N=100 the module aborts at solve 36; at N=150, solve 13.

Nothing crashes — `osqp.cljs:135` catches the abort and falls back to quadprog — so the user-visible symptom is that a large run quietly finishes on the slow solver anyway. At N=100 that measured 21 fallbacks costing 59.7 s of an 88.8 s run. That was the state before the vendored patch below; it is no longer the case.

This plan set out to make a default N=100 run complete entirely on OSQP. **It now does.** The first revision of this plan did not achieve it and recorded that as a negative result requiring one of three owner decisions; the second revision took the first of those three — patch or vendor `osqp` — and it worked, with no numerical change and no product trade. A run at N=100 went from a hard ceiling of 36 solves to 400 with the allocator flat, against a default plan of 56.

That changes what the rest of this plan is for. Several deferred items existed mainly to fit inside the heap budget; with no budget to fit inside, they are now pure speed plays and have to justify themselves on that alone. One of them — tiering or deriving the display sweep — costs product quality and is no longer worth its price, so it is now an explicit recommendation *against*, not a deferral.

The second revision also cleared the two `Float64Array` kernel ports the first revision deferred pending an unmeasurable usage share, and the codec-walk fusion it deferred pending a precondition that turned out not to be required. Three items remain open, each for a reason recorded below rather than for want of effort.

## Context References

Public refs:

- Direct user request, 2026-08-23: "What else can be done to increase the performance of the optimizer?", followed by "I want you to create an execution plan that addresses the issues you found and then implement it."
- Second revision, same day, after the first revision reported its negative result and its deferred list: "are you able to address the open items in the exec plan? Because if you can, then let's create an execution plan or just amend the one that we have and address the items."
- Parent work: commit `9d85e56c0` (`fix(optimizer): unblock the bundled WASM solver and surface its fallback`) and `20ccda2ff` (its follow-up comment correction). This plan continues directly from the profiling that commit's investigation produced.

Repo artifacts:

- `/hyperopen/AGENTS.md` — the operating contract this work follows, including the required validation gates.
- `/hyperopen/src/hyperopen/portfolio/optimizer/infrastructure/osqp.cljs` — the per-solve setup/cleanup cycle that leaks.
- `/hyperopen/src/hyperopen/portfolio/optimizer/application/engine/solver_health.cljs` — the fallback warning added by the parent commit; it is the observability this plan is verified against.
- `/hyperopen/dev/namespace_size_exceptions.edn` — several touched namespaces sit near their line caps.
- `/hyperopen/tools/optimizer/patch_osqp.mjs` — the vendored `osqp` patch, its four anchored edits and the full rationale for vendoring rather than rewriting `node_modules`.
- `/hyperopen/test/hyperopen/portfolio/optimizer/bit_parity.cljs` — the `js/Object.is` comparison the kernel parity suites are built on, and why `=` is unusable for them.

Local scratch refs (non-authoritative):

- Profiling harnesses were built under `/tmp` and are not retained. Every number quoted below is reproducible from the method described beside it.

## Progress

- [x] (2026-08-23) Established that OSQP is reachable in production only after the CSP fix, and that the bundled build leaks heap per solve.
- [x] (2026-08-23) Measured the leak mechanism: `osqp.min.js` helper `o()` issues three `_malloc`s per CSC and `cleanup()` frees only the returned struct pointer.
- [x] (2026-08-23) Measured first-OOM solve index against real matrices: N=60 -> 103, N=80 -> 58, N=90 -> 45, N=100 -> 36, N=150 -> 13. Default run is 56 solves at N>=60.
- [x] (2026-08-23) Milestone 1 — Solve-count ceiling. `display_frontier/display-frontier-objective` now applies the size tier as a `min` against any requested count instead of bailing out when one is set. Measured: a refinement asking for 80 points at N=100 now plans 16. The selection sweep was deliberately **not** tiered — see the Decision Log.
- [x] (2026-08-23) Milestone 2 — Payload diet. `solve/transportable-solver-results` drops `:problem :quadratic` at the payload boundary. Measured on a 40-point sweep: 7.55 MB -> 0.15 MB at N=100 (49x), 2.67 MB -> 0.18 MB at N=60 (14.8x).
- [x] (2026-08-23) Milestone 3 — Marshalling memo. `osqp/static-parts` caches P, A, l and u on a structural key; only the linear term is rebuilt per solve. Measured over a 40-point sweep: 16,170 ms -> 0.4 ms at N=100, 6,130 ms -> 0.5 ms at N=60. Bit-identity asserted element-wise in `osqp_static_cache_test.cljs`.
- [x] (2026-08-23) Milestone 4 — The repeated `P'omega^-1` in the Black-Litterman posterior is now bound once. The `encode-constraints` item was dropped, not done — see the Decision Log.
- [x] (2026-08-23) Merged the volatility-integrity work from `main` (`4b9da9e2a`) into this branch: no conflicts, main's logic byte-identical and primary, gates 34/34 PASS with both suites present (6,849 tests / 36,920 assertions).
- [x] (2026-08-23) Milestone 5 — `npm run gates` 34/34 PASS. The N=100 zero-fallback criterion **was not met**; recorded as a negative result under `Outcomes & Retrospective`.

### Second revision (2026-08-23), after the owner asked whether the deferred items could be addressed

- [x] (2026-08-23) Milestone 6 — **The heap leak is fixed and the Milestone 5 negative result is resolved.** `vendor/osqp/osqp_patched.mjs` is `osqp@0.0.2` with four mechanical edits: `o()` collects the three pointers it allocates per matrix, `setup()` threads one collector through both, and `cleanup()` frees them after the workspace teardown that still referenced them. Measured on the production problem shape: N=100 goes 36 solves -> 400 with the allocator break flat, N=150 goes 13 -> 60, N=280 goes 1 -> 25. A single solve stops fitting somewhere between N=280 and N=300, which the leak fix cannot help and never could.
- [x] (2026-08-23) Milestone 7 — `math/inverse` ported to a flat `Float64Array`. 944 ms -> 6 ms at n=100 and 62 ms -> 1 ms at n=40, measured through the compiled test build. Bit-identical, pinned by a new `domain/math_test.cljs` that `domain/math.cljs` did not previously have at all.
- [x] (2026-08-23) Milestone 8 — `risk/covariance-matrix` ported: per-series means hoisted out of the per-cell kernel, upper triangle only, observations packed into `Float64Array`. 8,253 ms -> 256 ms at 100 instruments x 730 observations; 650 ms -> 27 ms at 40 x 365. Bit-identical, pinned by `risk_covariance_parity_test.cljs`.
- [x] (2026-08-23) Milestone 9 — the two worker-boundary codec walks fused into one. 182.8 ms -> 127.4 ms per call on a 100-instrument, 365-bar result shape (1.43x, below the 2.0x this plan projected). The disjointness precondition the first revision recorded was retired rather than satisfied — see the Decision Log.
- [x] (2026-08-23) `npm run gates` 34/34 PASS across all four, 6,874 tests / 38,288 assertions.

### Still deferred, with the evidence needed to decide them

Four of the seven items the first revision deferred are now closed. `math/inverse`, `risk/covariance-matrix` and the codec-walk fusion landed as Milestones 7-9. OSQP workspace reuse is **withdrawn, not deferred**: it existed to escape the heap ceiling, the vendored patch escapes it with no numerical change at all, and reuse was never a per-solve speedup (1.2-1.9x *slower*). It should not be revisited.

Three remain, and each is open for a stated reason rather than for want of effort:

- [ ] **Do not derive or tier the display sweep.** This is now a recommendation against, and it needs an owner to accept or overrule it rather than an engineer to implement it. It saves 16 of 56 solves at N=100 and would draw a longer, denser curve, but: it does nothing for the product default `:minimum-variance`, which takes the closed-form path and keeps all 16 display solves; the heap pressure that motivated it is gone; and it is user-visible in three ways that are not obviously improvements. Point placement changes, so the drawn curve changes shape. `:frontier-summary :point-count` jumps (12 -> 37 measured at N=40), which reclassifies `refinement/frontier-quality` and `result-tier`. The Target-σ dial's upper bound is derived from the drawn frontier's `:max-vol` (`views/portfolio/optimize/target_sigma.cljs:36-67`), which at N=40 moves 0.2166 -> 0.7479 and takes the dial from its 40% floor to roughly 90%. The obvious scoping rule is also wrong: with a held-position lock, deriving `:constrained` from the selection sweep while `:unconstrained` keeps its own inverts the reference/constrained relationship and produces a reference curve 31% shorter than the curve it is meant to bound. If it is ever built, it must key on `(= :frontier-sweep (:strategy solver-plan))` **and** `constrained-frontier-alias?`, and suppress both modes together or neither.
- [ ] **Carry the closed-form candidate forward instead of recomputing it — but note it does not deliver this item's own metric.** The item as written is "stop `closed_form/eligible?` solving and discarding at plan time — 341 of 392 ms of `optimization-context`". Carry-forward leaves plan time untouched: `eligible?` still runs the full O(n^3) at `objectives.cljs:361`, inside `optimization-context`, and that 341 ms survives the change. What it removes is the *second*, solve-time solve at `solve.cljs:20` — a similar saving in a different place, and only on accepted runs (measured: `eligible?` and `solve-portfolio` within 0.4% of each other, `frontier-moments` 96-99% of each). Only the early-out reduces plan time, and the early-out remains unsafe. Two corrections for whoever builds it: do **not** carry `:metrics`, because `validate-solution` recomputes it and the carried copy is never read; and the span{1,mu} check is **not** a KKT certificate — it is a necessary condition shared by all four objectives, and three accepted-but-wrong candidates were demonstrated against the repo's own two-asset fixture, including a `:target-return 0.14` point with 34.7% worse variance.
- [ ] **Memoize the worker-boundary encode walk.** Reframed: the item said "de-duplicate the engine request", but the redundancy is a *serialization* artifact, not a data-model defect — candle row maps are already identity-shared in memory between `price-series-by-instrument` and `raw-price-series-by-instrument`, and `clj->js` re-encodes each one. An identity memo (a per-call `WeakMap`) preserves only sharing that already exists, so it cannot change a value: measured 23.6 MB -> 19.0 MB at N=100 and *faster* than today, 450 ms against 918 ms. Value-based hash-consing goes further, 23.6 MB -> 3.3 MB, but it is not value-preserving and must not be shipped without narrowing: two `=` sets with different insertion order encode to differently ordered arrays and get collapsed, two `=` array-maps with different key order likewise, and `-0.0` and `0.0` hash the same and get substituted. Not started because the safe floor still requires hand-rolling `clj->js`'s `map?` and `coll?` branches — delegating a subtree to `clj->js` on a miss means the walk never descends and only the root is ever memoized, measured at zero dedupe — and that deserves its own change with a `v8.serialize`-byte oracle. `JSON.stringify` is structurally incapable of catching the defects this can introduce, because it erases `-0`, `NaN` and `Infinity`.

### Found while doing this work, and since fixed

The owner asked for these four to be fixed rather than filed. All four are done, each with a test that fails against the old behaviour.

- [x] (2026-08-24) `normalize-black-litterman-view-weights` is now total. It `mapv`'d over `[:return-model :views]` without checking it was a sequence, and `normalize-worker-boundary` runs inside the worker's message listener on every inbound message — so a scalar `:views` took the listener down, and a string `:views`, being seqable in ClojureScript, was silently shredded into a vector of characters. Confirmed as 2 errors and 2 wrong answers before the fix. It now passes through what it does not recognise, which is how every other walk in that namespace already behaved.
- [x] (2026-08-24) `closed_form_support/within-bounds?` now pads both bound vectors instead of passing them straight to `map`. `map` over three collections stops at the shortest, so a short `:lower-bounds` truncated the whole check — including the upper bounds, which might well have covered those positions. The reachable failure is specific: three weights, three upper bounds, two lower bounds, and the third weight's upper bound was never enforced. Post-validation is the entire safety argument for accepting a closed-form portfolio without re-solving it, so a bound it does not check is a bound the closed-form path does not enforce. Tightening it broke no existing test.
- [x] (2026-08-24) `risk_ledoit_wolf_test` moved off `=` and onto `bit-parity/bit=`, and gained a non-finite fixture that makes the comparator load-bearing: a NaN observation propagates into the covariance, so the correct answer contains NaN and the assertion is red under `=` while both implementations agree exactly. Verified by switching the comparator back and watching it go red. `bit-parity` gained map support, its own test namespace — it is the oracle for four suites and had none — and the duplicate comparator in the codec fusion suite was folded into it.
- [x] (2026-08-24) The vendored `osqp` now passes `eps_prim_inf` in the sixth `_create_settings` slot rather than `eps_dual_inf` twice. No behaviour change today, and provably so: nothing sets either tolerance, so both take the same 1e-4 default and the value landing in that slot is identical before and after. The slot identification is measured rather than assumed — see the Discoveries entry.

## Surprises & Discoveries

- Observation: The bundled OSQP WebAssembly heap is 16 MiB, fixed and non-growable, and exhaustion aborts rather than grows.
  Evidence: `node_modules/osqp/dist/osqp.min.js` declares a memory section of 256 pages initial and 256 maximum, and its `emscripten_resize_heap` is `function(A){G.length,e("OOM")}` — an abort with no growth attempt.

- Observation: The leak is in the package, not in our adapter. `cleanup()` frees the CSC struct pointers but never the arrays they wrap.
  Evidence: The helper `function o(A,I,g){const C=D._malloc(8*A.data.length); ... const Q=D._malloc(4*A.row_indices.length); ... const B=D._malloc(4*A.column_pointers.length); ... return D._create_csc_matrix(I,g,A.data.length,C,Q,B)}` returns only the struct. `cleanup()` calls `_free(pointer_to_P)` and `_free(pointer_to_A)`, so `C`, `Q` and `B` leak for both matrices every solve — `12*(nnzP+nnzA) + ~4*cols` bytes, about 114 KB per solve at N=100.

- Observation: An initial estimate of the leak rate was wrong by a factor of about 2.5 because it modelled `P` as a triangular covariance block.
  Evidence: A first probe predicted OOM at solve 91 for N=100 using `nnzP ~ N^2/2`. The real adapted problem has `4N` variables and `nnzP = 2N^2+3N`, and the measured first-OOM index is solve 36. An earlier probe using a fully dense `A` was wrong in the other direction and OOM'd on solve 1; the real `A` is sparse at `18N` nonzeros because bound rows are unit vectors and `dense->csc` skips zeros.

- Observation: Which of a run's two solve batches dominates depends on the objective, so neither can be treated as the small one.
  Evidence: Under `:minimum-variance` the selection takes the closed-form path and issues no QP solves at all, leaving the display sweep as the entire run. Under `:max-sharpe`, `objectives.cljs:490` rejects closed-form and selection becomes its own 40-point sweep — 40 of 80 solves at N=30, 40 of 56 at N=60 and N=100. This corrected a comment in `solver_health.cljs` (commit `20ccda2ff`).

- Observation: The `:frontier-points` tiering is silently skipped exactly when it matters most.
  Evidence: `application/display_frontier.cljs:92-98` returns the objective unchanged when `(:frontier-points objective)` is already set, so the `>50 -> 16` tier at `:85-90` never applies to a refinement-set budget. Refinement `:maximum` at N=100 therefore draws 80 display plus 80 selection solves against a measured 35-solve heap budget.

- Observation: The `:constrained` / `:unconstrained` frontier aliasing is already correct and is not a source of duplicate work.
  Evidence: Measured with no held locks, the run plans one sweep and emits `:aliases {:constrained :unconstrained}`; with locks it correctly plans two, at 72 solves for N=100.

- Observation: The earlier claim that workspace reuse is a large per-solve speedup was a benchmark artifact.
  Evidence: The control re-solved an identical `q` 240 times, so every iteration warm-started at the optimum. Re-measured across a real sweep with varying `q`, reuse is 1.2-1.9x *slower* per solve at N=30..80 and break-even at N=100 (N=60: 50.26 ms cold vs 67.62 ms reused). Its 6.7x end-to-end figure comes entirely from eliminating quadprog fallbacks.

- Observation: Today's cold OSQP path is not a numerical gold standard, which matters for how any warm-start change is judged.
  Evidence: At N=60 the current cold path already returns 14 of 56 points violating the 1e-5 feasibility check, and it is timing-nondeterministic — three identical `:equal-risk` invocations issued 73, 74 and 77 solves, because OSQP's adaptive rho is time-triggered.

- Observation: A previously reported 1.6 s main-thread cost for `setup_readiness` calling `build-engine-request` during render does not reproduce.
  Evidence: Measured at 1.20 ms per call at N=100. Two memos cover it, and `align-history` never re-runs because its memo key excludes `:as-of-ms`, with a 5 s as-of quantization bounding the miss rate.

- Observation: The CSC memo is worth far more than the per-solve ratio suggests, because it collapses a whole sweep to one build.
  Evidence: Building P, A, l and u for 40 sweep points measured 16,170 ms uncached and 0.4 ms warm at N=100 (6,130 ms -> 0.5 ms at N=60). The sweep reuses one covariance object, so the structural key's equality check short-circuits on identity and all 39 subsequent points hit.

- Observation: The memo does not help the heap at all, which is easy to assume it would.
  Evidence: `osqp.min.js` `setup()` calls `_malloc` and copies our arrays into the WebAssembly heap on every call regardless of whether the JavaScript-side arrays are the same objects. Caching the CSC changes only the JavaScript work, so the per-solve leak and the first-OOM solve index are unchanged.

- Observation: `osqp` cannot be upgraded out of the leak.
  Evidence: `npm view osqp versions` returns only `0.0.1` and `0.0.2`; `0.0.2` is `latest` and was last modified 2022-05-12. The package is unmaintained, so a fix means patching, vendoring, or replacing it.

- Observation: The dense 2N x 2N quadratic block is mathematically necessary, not an adapter defect.
  Evidence: `problem_adapter/split-quadratic` expands `w = w+ - w-`, so `w'Qw` legitimately produces all four sign-combination blocks of the covariance. An early hypothesis that the adapter was needlessly densifying `P` (and that fixing it would quadruple the heap budget for free) is wrong.

- Observation: The volatility-integrity work on `main` (`4b9da9e2a`) merged into this branch with zero conflicts, and every file it touches is byte-identical here.
  Evidence: `git merge main` reported no conflicts. Hashing each of the nine files that commit changed against `main` — `return_plausibility.cljs`, `risk.cljs`, `risk_ledoit_wolf.cljs`, `history_series.cljs`, `diagnostics.cljs`, `calendar.cljs`, `api_v2/alignment.cljs`, `results_diagnostics_rail.cljs`, `leverage_impact_panel.cljs` — returns identical digests. The two files both branches edited, `payload.cljs` and `dev/namespace_size_exceptions.edn`, touched disjoint regions: main added `:risk-shrinkage` near the end of the payload map and raised the caps for `alignment.cljs` and `results_diagnostics_rail.cljs`, while this branch edited the warnings concat, the `solved-payload` signature and the `payload.cljs` cap. Merged `payload.cljs` is 536 lines against the 540 cap this plan set.

- Observation: The payload strip in Milestone 2 does not endanger the new risk-integrity signals, which was the one plausible way this merge could have regressed silently.
  Evidence: `covariance-plausibility` takes the covariance as a direct argument from `risk-result` (`diagnostics.cljs:130`), not from `:solver-results`; grepping every file main's commit touched finds no reader of `:solver-results` at all. Confirmed at runtime through the merged engine: `:risk-shrinkage` is present in the payload, `:covariance-plausibility` is present in `:diagnostics`, and the check still returns `:implausible` for a poisoned diagonal and `:ok` for a sane one. Pinned by `payload-strip-does-not-drop-the-risk-integrity-signals-test`.

- Observation (second revision): The vendored patch is bit-identical, and proving that required separating it from pre-existing nondeterminism that looks exactly like a regression.
  Evidence: patched and stock disagreed on 1 of 36 sweep points at N=100, by 7.689e-3. Running stock against *itself* three times produced the same disagreement on the same point, #14, by the same amount — runs B and C agreed with each other and with the patched build bit-for-bit, run A differed. Solve #14 is a bistable point under OSQP's time-triggered adaptive rho, not an effect of the patch. Without the stock-vs-stock control this would have read as the patch changing answers.

- Observation (second revision): The obvious way to wire the vendored file in ships a solver that works in development and throws in production.
  Evidence: a relative require of the vendored copy from `osqp.cljs` routes it through Closure's `closure-js` pipeline instead of shadow-cljs's `shadow-js` pipeline, and `:advanced` then renames the Emscripten glue. In the release bundle built that way, `_malloc`, `HEAPF64` and the `asm.j` WASM export accessor all had zero occurrences, against 7, 7 and 1 in the stock baseline. `:js-options {:resolve ...}` keeps the file on the npm path and preserves all three exactly. A top-level `:js-options` does not work either: it is silently ignored per build, verified by a clean rebuild of `portfolio-optimizer-worker` that still resolved the published package with no warning.

- Observation (second revision): The leak fix removes the per-run budget but not a per-solve ceiling, and the two were easy to conflate.
  Evidence: patched, N=280 completes 25 sweep points where stock completes 1; N=300 fails on solve **1**. A single N=300 problem does not fit in 16 MiB at all — `nnzP` is `2N^2+3N` = 180,900, so the P arrays alone are 2.17 MB before OSQP's workspace and KKT factorization. No amount of freeing helps that.

- Observation (second revision): The deferral criterion for the covariance port was unsatisfiable, not merely unsatisfied.
  Evidence: it gated on measuring "the share of users on `:diagonal-shrink` / `:sample-covariance`". There is no product analytics in this repo — `telemetry.cljs:41-43` is an in-memory ring buffer gated on `goog.DEBUG`, and grepping for posthog/segment/amplitude/mixpanel/plausible/gtag returns nothing. Reachability from code is the criterion that can actually be evaluated: 2 of the 4 user-selectable models force the function, the engine's own fallback at `risk.cljs:220` is `:diagonal-shrink` for any request omitting `:risk-model`, a persisted draft carrying the pre-rename `:ledoit-wolf` normalizes onto it, and the product default reaches it whenever Ledoit-Wolf reports ragged input.

- Observation (second revision): The first revision's own note that the covariance port's premise had gone stale was right, and understated.
  Evidence: `risk.cljs:305-307` routes `:ledoit-wolf-dense` — the product default — into the pairwise sample covariance whenever the Ledoit-Wolf estimator emits `:ragged-return-series`. The volatility-integrity work makes that ordinary rather than exotic, because `return_plausibility` now rejects implausible bars at ingestion instead of winsorizing them, which is exactly what produces unequal-length series.

- Observation (second revision): Plain `=` is the wrong oracle for these parity tests, in both directions, and it fails in the direction that looks like a real defect.
  Evidence: the covariance parity suite failed 6 assertions comparing the reference implementation to **itself**, because `(= ##NaN ##NaN)` is false and the estimator propagates NaN by design. In the other direction `(= 0.0 -0.0)` is true, so a port that flipped a zero's sign would pass — and a signed zero survives into later arithmetic, since `1/0.0` is `Infinity` and `1/-0.0` is `-Infinity`. `test/hyperopen/portfolio/optimizer/bit_parity.cljs` now provides `js/Object.is` semantics for this. `risk_ledoit_wolf_test.cljs` still has the same hole.

- Observation (second revision): The codec fusion's stated precondition was not the real one, and the real one is an ordering constraint that a plausible implementation gets wrong.
  Evidence: the two key sets are disjoint, but the fusion does not depend on it — `keyword-value` changes only strings and keywords, `stringify-instrument-keyed-map` changes only maps, and each walk leaves the other's inputs untouched, so the rules commute unconditionally. What the equivalence does depend on is staying post-order. A pre-order variant diverged on 3,224 of 30,000 random trees in an independent check. The fixture that demonstrates it has to be an instrument key *directly* beneath an instrument key: nesting one level deeper does not distinguish the orders, because `stringify-instrument-keyed-map` rewrites keys only one level down — the first fixture written for this passed against both orders and would have shipped a test that proved nothing.

- Observation (second revision): The measured wins for two items came in well below what this plan projected, and are recorded at their measured values.
  Evidence: the codec fusion measured 1.43x, not the projected 2.0x. The covariance port measured 8,253 ms -> 256 ms (32x) through the compiled test build, not the 10,605.7 -> 6.00 ms (1768x) this plan quoted; the residual 256 ms is the irreducible O(n^2 T) accumulation, 3.7M multiply-adds after the triangle halving, measured in a non-optimized dev build.

- Observation (2026-08-24): The `osqp` settings slot was identified by measurement, not by reading the package's type declaration.
  Evidence: `solve()` returns only the solution vector, so a wrong setting cannot be observed directly. Timing infeasibility detection does discriminate. On a primal-infeasible problem, with the sixth slot patched to receive `eps_prim_inf`, loosening it from 1e-15 to 1e-1 detects infeasibility 2.7x sooner; with the slot left as published, the same change measures 0.82x, inside noise. A feasible problem is unaffected either way, which rules out the slot being `alpha` — a relaxation parameter would move the feasible path too. The first attempt at this measurement was confounded by WebAssembly module instantiation landing inside the first timed batch, which made the unpatched build look 35x sensitive to a setting it ignores entirely.

## Decision Log

- Decision (2026-08-23): Treat the OOM as the headline problem and rank reliability above raw speed. Rationale: the CSP fix's entire benefit is forfeited on any run that exhausts the heap, because the remaining solves return to the solver the CSP fix was meant to escape.

- Decision (2026-08-23): Do not ship OSQP workspace reuse in this plan despite it being the largest measured end-to-end win. Rationale: the worst warm-started divergence produced was a weight delta of 0.128 with an objective 33.8% worse than cold, and it passed the 1e-5 feasibility check, so `target-selection` would have accepted it silently. `warm_start:false` reduces the worst case to 2.4% but does not eliminate it. A convergence gate cannot be built on this wrapper — `solve()` returns only the solution vector and the Emscripten module is closure-private, so `work->info` is unreachable. The gate that does work is cross-evaluating each sweep point's weights on its neighbours' problems and re-solving any point whose relative gap exceeds ~1e-4 (cold baseline sits at 1e-6). That is a larger piece of work than this plan carries.

- Decision (2026-08-23): Prefer reducing solve count over reusing the workspace as the first response to the OOM. Rationale: it changes no numerics, and the tiering it applies was already the codebase's own intent — the bypass at `display_frontier.cljs:92` is a defect, not a design choice.

- Decision (2026-08-23): Accept a coarser display frontier at large universes as the cost of completing on OSQP. Rationale: a 16-point curve computed on the fast solver is strictly better than a 40-point curve where two thirds of the points were computed on a fallback solver after the heap died. This is a genuine product trade and is flagged here for the owner to reverse if they disagree.

- Decision (2026-08-23): Defer both `Float64Array` kernel ports despite ratios of 333x and 1768x. Rationale: each is gated on an unmeasured usage share. `covariance-matrix` is never called by the product default risk model, and `math/inverse` fires only on Black-Litterman with views. Spending effort before knowing the reach risks optimizing a dead path, and the covariance port additionally needs a ragged-calendar equality fixture to be safe.

- Decision (2026-08-23): Do **not** tier the selection sweep, reversing the intent recorded when this plan was written. Rationale: on reflection the two sweeps are not comparable. Coarsening the display sweep changes the resolution of a drawn curve; coarsening the selection sweep changes which portfolio the optimizer recommends, because `:max-sharpe` picks its answer off that grid. Trading recommendation quality for heap headroom is a different and much larger decision than trading curve resolution, and it should not be made silently inside a performance plan. The consequence is that Milestone 5's N=100 criterion fails, which is recorded rather than papered over.

- Decision (2026-08-23): Drop the `encode-constraints` de-duplication from Milestone 4. Rationale: `context.cljs` computes one encoding while `display_frontier/build-plans` computes a constrained and an unconstrained one, so passing the existing value in saves at most one of three calls — about 3.5 ms of a measured 7 ms. Establishing that the context's encoding is genuinely identical to the display sweep's `:constrained` encoding is a correctness question, and it is not worth answering for 3.5 ms.

- Decision (2026-08-23): Strip only `:problem :quadratic` from `:solver-results` rather than dropping `:solver-results` entirely. Rationale: dropping the key outright is a larger payload win (65x vs 22x) but requires relaxing `contracts/specs.cljs:347`, and `target-selection` reads sibling keys of `:problem`. Stripping the one dense field captures most of the benefit at near-zero risk.

- Decision (2026-08-23, second revision): Take owner option 1 — vendor a patched `osqp` — and close options 2 and 3. Rationale: it is the only one of the three with no numerical and no product cost, and it turned out to be small. Tiering the selection sweep would have changed which portfolio is recommended; workspace reuse would have shipped answers that pass the feasibility check while being materially worse. Neither needs to be considered again while the vendored copy holds.

- Decision (2026-08-23, second revision): Commit the patched artifact rather than rewriting `node_modules` from a lifecycle hook. Rationale: `npm run build` on a fresh CI checkout has no hook to hang a rewrite off, and an install whose contents differ from what npm placed there is invisible. The committed copy is regenerated by `tools/optimizer/patch_osqp.mjs`, whose four edits are anchored on byte-exact slices and fail loudly if upstream changes shape, and `patch_osqp.test.mjs` re-runs the transform on every `npm run check` so a version bump cannot leave a stale copy behind.

- Decision (2026-08-23, second revision): Guard the wiring with a blanket invariant — *every* shadow-cljs build resolves `"osqp"` to the vendored copy — rather than "every build that compiles the optimizer". Rationale: the conditional version needs a dependency graph nobody will maintain, and the failure it guards is a new build added later that quietly gets the leaky package back. The blanket rule is checkable in a few lines and was mutation-tested by removing one resolve and confirming the guard fails.

- Decision (2026-08-23, second revision): Keep `math/inverse`'s pivot selection as a `sort-by`, and port only the elimination. Rationale: pivot selection is O(n^2 log n) on n values and was never the cost, while rewriting it as a linear scan changes which row wins a tie — `sort-by` is stable, so ties go to the lowest row index and a scan written with `>=` takes the highest. Both are valid pivots and the resulting inverses differ only in the last bits, which no tolerance-based test would catch. Leaving it alone makes bit-identity structural rather than argued.

- Decision (2026-08-23, second revision): Replace the covariance port's deferral criterion rather than satisfy it. Rationale: the stated gate — measured usage share — cannot be evaluated in a codebase with no analytics, so waiting on it is waiting forever. Reachability from code is evaluable and says the path is live.

- Decision (2026-08-23, second revision): Turn the display-sweep item from a deferral into a recommendation against. Rationale: its main justification was heap headroom, which no longer exists. What remains is 16 solves at N=100 on non-default objectives only, against three user-visible changes — curve shape, `result-tier` reclassification, and a Target-σ dial whose range more than triples. That is a product trade, and with the reliability motivation gone it is not a trade worth making silently.

## Validation and Acceptance Criteria

Required gates on every milestone that changes code, per `AGENTS.md`:

- `npm run gates` reports 34/34 PASS. This runs `npm run check`, `npm test` and `npm run test:websocket` without short-circuiting.

Milestone-specific acceptance:

- Milestone 1: the display sweep never plans more points than the size tier allows, including when refinement has set `:frontier-points`; the selection sweep is tiered by the same rule; a unit test pins the previously-bypassed case.
- Milestone 2: `:solver-results` entries no longer carry `:problem :quadratic`; every existing reader still passes; a test asserts the key is absent and that `target-selection` still selects correctly.
- Milestone 3: the memoized CSC conversion is byte-identical to the unmemoized one; a test compares the produced CSC arrays element-wise for a run that plans more than one distinct `A`.
- Milestone 4: no behaviour change; `encode-constraints` is called once per run rather than three times.
- Milestone 5: a realistic N=100 run completes with zero `:solver-fallback-used` warnings, measured through the engine with the real OSQP solver wired in. If it does not, that is recorded here as a negative result rather than the milestone being quietly narrowed.
- Milestone 6: a sweep longer than the unpatched heap budget completes with every solve on OSQP, driven through the real solver rather than a model of it; the answers are bit-identical to the unpatched package modulo its own run-to-run nondeterminism, established with a stock-vs-stock control rather than assumed; and the release bundle keeps the Emscripten glue that `:advanced` would otherwise rename.
- Milestones 7 and 8: each ported kernel is bit-identical to a frozen copy of the implementation it replaced, compared with `js/Object.is` semantics rather than `=` or a tolerance, over fixtures that concentrate on the degenerate cases — pivot ties, the exact singularity boundary, non-finite holes, ragged series — rather than on happy-path matrices.
- Milestone 9: the fused walk is output-identical to the two-pass composition over fixtures generated from the real key sets plus random trees, and the suite proves the post-order constraint is load-bearing by keeping the pre-order variant and asserting it diverges.

Explicitly out of scope for this plan, and therefore not part of its acceptance: the items still listed as deferred under `Progress`.

## Outcomes & Retrospective

Landed, all measured on Node v24.0.2 / Apple M2 Max through the compiled `test` build unless stated:

| Change | Measured effect |
|---|---|
| Frontier tier as a ceiling | A refinement requesting 80 display points at N=100 now plans 16. Worst reachable display sweep drops from 80 points to the tier. |
| `:solver-results` payload strip | 7.55 MB -> 0.15 MB at N=100 over a 40-point sweep (49x); 2.67 MB -> 0.18 MB at N=60 (14.8x). |
| CSC memo | Marshalling for a 40-point sweep 16,170 ms -> 0.4 ms at N=100; 6,130 ms -> 0.5 ms at N=60. Byte-identical P/A/l/u asserted element-wise. |
| Black-Litterman CSE | `P'omega^-1` evaluated once instead of twice; ~3.5 ms of a measured 10.4 ms posterior. |
| **Vendored `osqp` leak fix** | **N=100: 36 solves -> 400 with the allocator flat. N=150: 13 -> 60. N=280: 1 -> 25. Bit-identical.** |
| `math/inverse` -> `Float64Array` | 944 ms -> 6 ms at n=100; 62 ms -> 1 ms at n=40. Bit-identical. |
| `risk/covariance-matrix` -> hoisted means + `Float64Array` | 8,253 ms -> 256 ms at 100 x 730; 650 ms -> 27 ms at 40 x 365. Bit-identical. |
| Fused codec walk | 182.8 ms -> 127.4 ms per worker message at 100 instruments x 365 bars (1.43x). Output-identical. |

**The negative result recorded in the first revision is resolved.** A default N=100 run issues 40 selection solves plus 16 display solves; the heap budget was 36 and is now unbounded for any universe whose individual solves fit, which they do to at least N=280. The reliability caveat that the CSP fix "delivers its full benefit up to roughly N=80" no longer applies.

**What is still true.** A single solve stops fitting in the 16 MiB heap somewhere between N=280 and N=300, and freeing cannot change that — it is the size of one problem, not the accumulation of many. The `:solver-fallback-used` warning added in `9d85e56c0` remains the signal if that is ever reached, and it is now a genuine alarm rather than the routine occurrence it was.

**What went well.** Two habits paid for themselves. The stock-vs-stock control on the solver: patched and unpatched disagreed on one sweep point, which looked exactly like a regression until running the unpatched package against itself reproduced the same disagreement on the same point — the honest conclusion was pre-existing nondeterminism, and without the control the patch would have been wrongly blamed or, worse, wrongly cleared by a looser tolerance. And writing each parity oracle *before* the port and confirming it green against the unmodified code: that is what surfaced the `=`-versus-NaN problem, on a suite comparing a function to itself, rather than during the port where it would have been read as a divergence.

**What to watch.** Three things. The vendored `osqp` is a fork of an unmaintained package and carries a deletion prompt in its own test suite — if upstream ever fixes this, the whole directory and all eight resolves should go. The `:js-options :resolve` mechanism is load-bearing in a non-obvious way: the tidier-looking relative require compiles clean, passes every test in dev, and ships a release bundle whose Emscripten glue has been renamed into nonsense, so the blanket build guard is not bureaucracy. And `static-parts` remains a `defonce` atom that is only safe because the optimizer worker is recreated per run.
