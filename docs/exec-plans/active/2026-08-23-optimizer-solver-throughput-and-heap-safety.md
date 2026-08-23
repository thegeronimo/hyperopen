# Keep the optimizer's WebAssembly solver alive for a whole run, and stop paying for work nobody reads

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md` (the full ExecPlan writing contract) and `/hyperopen/docs/PLANS.md` (the public planning entry point).

## Purpose / Big Picture

Commit `9d85e56c0` added `'wasm-unsafe-eval'` to the release Content-Security-Policy, which lets the bundled OSQP WebAssembly solver run in production for the first time. That was the right fix — the pure-JavaScript fallback it replaces is 14.5x slower on a whole solve and 67.6x slower in the kernel — but it moves production onto a code path nobody had ever exercised at scale, and that path has a memory leak.

`osqp@0.0.2` leaks WebAssembly heap on every solve. Its CSC helper issues three `_malloc` calls per matrix and returns only the struct pointer; `cleanup()` frees the struct and never the six arrays behind it. Against a fixed, non-growable 16 MiB heap that gives every run a hard solve budget, and `infrastructure/osqp.cljs` spends one setup/solve/cleanup cycle per solve. A default run issues 56 solves. At N=100 the module aborts at solve 36; at N=150, solve 13.

Nothing crashes — `osqp.cljs:135` catches the abort and falls back to quadprog — so the user-visible symptom is that a large run quietly finishes on the slow solver anyway. At N=100 that measures 21 fallbacks costing 59.7 s of an 88.8 s run. The CSP fix therefore delivers its full benefit only up to about N=80 today.

This plan set out to make a default N=100 run complete entirely on OSQP. It did not achieve that, and the gap is documented under `Outcomes & Retrospective` rather than hidden: closing it needs one of three owner decisions, each of which trades something real (a vendored dependency patch, recommendation quality, or numerical determinism). What it did achieve is measured: the result payload the worker ships back shrank 49x at N=100, the per-sweep marshalling that dominates a working OSQP solve is now paid once per run instead of once per point, and the pathological refinement cases that planned 160-240 solves against a 36-solve heap budget are gone.

This plan deliberately does **not** attempt every optimization the profiling turned up. Several of the largest measured ratios apply to code paths whose real-world reach is unmeasured, and the single biggest end-to-end win — reusing one OSQP workspace across a sweep — is numerically unsafe in its obvious form. Those are recorded below as unchecked, deferred items with the evidence needed to decide them later.

## Context References

Public refs:

- Direct user request, 2026-08-23: "What else can be done to increase the performance of the optimizer?", followed by "I want you to create an execution plan that addresses the issues you found and then implement it."
- Parent work: commit `9d85e56c0` (`fix(optimizer): unblock the bundled WASM solver and surface its fallback`) and `20ccda2ff` (its follow-up comment correction). This plan continues directly from the profiling that commit's investigation produced.

Repo artifacts:

- `/hyperopen/AGENTS.md` — the operating contract this work follows, including the required validation gates.
- `/hyperopen/src/hyperopen/portfolio/optimizer/infrastructure/osqp.cljs` — the per-solve setup/cleanup cycle that leaks.
- `/hyperopen/src/hyperopen/portfolio/optimizer/application/engine/solver_health.cljs` — the fallback warning added by the parent commit; it is the observability this plan is verified against.
- `/hyperopen/dev/namespace_size_exceptions.edn` — several touched namespaces sit near their line caps.

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
- [x] (2026-08-23) Milestone 5 — `npm run gates` 34/34 PASS. The N=100 zero-fallback criterion **was not met**; recorded as a negative result under `Outcomes & Retrospective`.

### Deferred, with the evidence needed to decide them

- [ ] OSQP workspace reuse (`update({q,l,u})` instead of setup-per-solve). Measured 6.7x end-to-end at N=100 and it removes the OOM outright, but it is **not** a per-solve speedup (1.2-1.9x slower per solve) and warm starting produced a solution 33.8% worse in objective that still passes the 1e-5 feasibility check, so `target-selection` would accept it. Needs the cross-evaluation certificate described in `Decision Log` before it can ship. Alternative worth pricing first: patch or vendor `osqp` so `cleanup()` frees the six CSC arrays, which fixes the leak with no numerical change at all.
- [ ] Port `risk.cljs/covariance-matrix` to `Float64Array`. Measured 10,605.7 -> 6.00 ms with zero error, and it is an 11.5 s slice of a 13.2 s run plus a 12.25 s main-thread freeze — the largest absolute number in the system. Deferred because the product default `:ledoit-wolf-dense` never calls it, and the share of users on `:diagonal-shrink` / `:sample-covariance` is unmeasured. Also needs a ragged-calendar equality fixture first: `math/sample-covariance` filters non-finite values and returns `nil` for mismatched lengths, and a naive port would silently change proxy-history results.
- [ ] Port `math/inverse` to `Float64Array`. Measured 883 -> 2.65 ms, bit-identical. Deferred because it fires only on the Black-Litterman path with at least one view, whose usage share is unmeasured.
- [ ] Stop `closed_form/eligible?` solving and discarding at plan time — 341 of 392 ms of `optimization-context` at N=100. Deferred because eligibility is safety-critical: it post-validates the candidate against every encoded constraint, so any early-out must not admit an infeasible closed-form answer.
- [ ] Skip the display sweep when it duplicates the selection sweep (-16 of 56 solves, and a denser curve). Deferred until after Milestone 1, whose tiering changes the numbers this would be measured against.
- [ ] De-duplicate the engine request (50.6% of 34.4 MB measured redundant). Deferred: the redundancy is real only without proxy substitution and with one common calendar, and the proxy-substituted fixture was never built.
- [ ] Fuse the two codec walks in `normalize-worker-boundary` (2.0x, ~690 ms worker + ~70 ms main). Deferred as lower value than Milestones 1-3 and dependent on `enum-value-keys` and `instrument-keyed-map-keys` staying disjoint, which needs its own guard test.

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

## Decision Log

- Decision (2026-08-23): Treat the OOM as the headline problem and rank reliability above raw speed. Rationale: the CSP fix's entire benefit is forfeited on any run that exhausts the heap, because the remaining solves return to the solver the CSP fix was meant to escape.

- Decision (2026-08-23): Do not ship OSQP workspace reuse in this plan despite it being the largest measured end-to-end win. Rationale: the worst warm-started divergence produced was a weight delta of 0.128 with an objective 33.8% worse than cold, and it passed the 1e-5 feasibility check, so `target-selection` would have accepted it silently. `warm_start:false` reduces the worst case to 2.4% but does not eliminate it. A convergence gate cannot be built on this wrapper — `solve()` returns only the solution vector and the Emscripten module is closure-private, so `work->info` is unreachable. The gate that does work is cross-evaluating each sweep point's weights on its neighbours' problems and re-solving any point whose relative gap exceeds ~1e-4 (cold baseline sits at 1e-6). That is a larger piece of work than this plan carries.

- Decision (2026-08-23): Prefer reducing solve count over reusing the workspace as the first response to the OOM. Rationale: it changes no numerics, and the tiering it applies was already the codebase's own intent — the bypass at `display_frontier.cljs:92` is a defect, not a design choice.

- Decision (2026-08-23): Accept a coarser display frontier at large universes as the cost of completing on OSQP. Rationale: a 16-point curve computed on the fast solver is strictly better than a 40-point curve where two thirds of the points were computed on a fallback solver after the heap died. This is a genuine product trade and is flagged here for the owner to reverse if they disagree.

- Decision (2026-08-23): Defer both `Float64Array` kernel ports despite ratios of 333x and 1768x. Rationale: each is gated on an unmeasured usage share. `covariance-matrix` is never called by the product default risk model, and `math/inverse` fires only on Black-Litterman with views. Spending effort before knowing the reach risks optimizing a dead path, and the covariance port additionally needs a ragged-calendar equality fixture to be safe.

- Decision (2026-08-23): Do **not** tier the selection sweep, reversing the intent recorded when this plan was written. Rationale: on reflection the two sweeps are not comparable. Coarsening the display sweep changes the resolution of a drawn curve; coarsening the selection sweep changes which portfolio the optimizer recommends, because `:max-sharpe` picks its answer off that grid. Trading recommendation quality for heap headroom is a different and much larger decision than trading curve resolution, and it should not be made silently inside a performance plan. The consequence is that Milestone 5's N=100 criterion fails, which is recorded rather than papered over.

- Decision (2026-08-23): Drop the `encode-constraints` de-duplication from Milestone 4. Rationale: `context.cljs` computes one encoding while `display_frontier/build-plans` computes a constrained and an unconstrained one, so passing the existing value in saves at most one of three calls — about 3.5 ms of a measured 7 ms. Establishing that the context's encoding is genuinely identical to the display sweep's `:constrained` encoding is a correctness question, and it is not worth answering for 3.5 ms.

- Decision (2026-08-23): Strip only `:problem :quadratic` from `:solver-results` rather than dropping `:solver-results` entirely. Rationale: dropping the key outright is a larger payload win (65x vs 22x) but requires relaxing `contracts/specs.cljs:347`, and `target-selection` reads sibling keys of `:problem`. Stripping the one dense field captures most of the benefit at near-zero risk.

## Validation and Acceptance Criteria

Required gates on every milestone that changes code, per `AGENTS.md`:

- `npm run gates` reports 34/34 PASS. This runs `npm run check`, `npm test` and `npm run test:websocket` without short-circuiting.

Milestone-specific acceptance:

- Milestone 1: the display sweep never plans more points than the size tier allows, including when refinement has set `:frontier-points`; the selection sweep is tiered by the same rule; a unit test pins the previously-bypassed case.
- Milestone 2: `:solver-results` entries no longer carry `:problem :quadratic`; every existing reader still passes; a test asserts the key is absent and that `target-selection` still selects correctly.
- Milestone 3: the memoized CSC conversion is byte-identical to the unmemoized one; a test compares the produced CSC arrays element-wise for a run that plans more than one distinct `A`.
- Milestone 4: no behaviour change; `encode-constraints` is called once per run rather than three times.
- Milestone 5: a realistic N=100 run completes with zero `:solver-fallback-used` warnings, measured through the engine with the real OSQP solver wired in. If it does not, that is recorded here as a negative result rather than the milestone being quietly narrowed.

Explicitly out of scope for this plan, and therefore not part of its acceptance: the deferred items listed under `Progress`.

## Outcomes & Retrospective

Landed, all measured on Node v24.0.2 / Apple M2 Max through the compiled `test` build:

| Change | Measured effect |
|---|---|
| Frontier tier as a ceiling | A refinement requesting 80 display points at N=100 now plans 16. Worst reachable display sweep drops from 80 points to the tier. |
| `:solver-results` payload strip | 7.55 MB -> 0.15 MB at N=100 over a 40-point sweep (49x); 2.67 MB -> 0.18 MB at N=60 (14.8x). |
| CSC memo | Marshalling for a 40-point sweep 16,170 ms -> 0.4 ms at N=100; 6,130 ms -> 0.5 ms at N=60. Byte-identical P/A/l/u asserted element-wise. |
| Black-Litterman CSE | `P'omega^-1` evaluated once instead of twice; ~3.5 ms of a measured 10.4 ms posterior. |

**Negative result, recorded as required by the acceptance criteria.** A default N=100 run does **not** yet complete with zero solver fallbacks. The default plan issues 40 selection solves plus 16 display solves; the measured heap budget is 36 solves at N=100, so roughly 20 solves still fall back to quadprog. Milestone 1 removes the pathological refinement cases (the reachable worst case falls from 160-240 solves) but does not clear the default budget, and Milestone 3 does not help the heap at all because the leak is inside `setup()`, below our caching.

Closing that gap requires one of three owner decisions, none of which is a pure engineering choice:

1. **Patch or vendor `osqp`** so `cleanup()` frees the six CSC arrays. The only option with no numerical or product change, but the package is unmaintained (last published 2022-05-12), so this means carrying a patch or a fork.
2. **Tier the selection sweep** (40 -> 16 points at N>50). Clears the budget, but changes which portfolio is recommended, not just how the curve is drawn.
3. **Reuse one OSQP workspace.** Removes the OOM and measures 6.7x end-to-end, but warm starting produced a solution 33.8% worse in objective that still passed the 1e-5 feasibility check, so it needs the cross-evaluation certificate described in the Decision Log first.

Until one of those lands, the practical guidance is that the CSP fix delivers its full benefit up to roughly N=80, and the `:solver-fallback-used` warning added in commit `9d85e56c0` makes any run above that self-reporting rather than silent.

What went well: the bit-identity test for the CSC memo was written before the memo was trusted, and the `structural-key` design was chosen specifically so that its equality check short-circuits on identity for the expensive members. What to watch: `static-parts` is a `defonce` atom, which is safe here only because the optimizer worker is recreated per run; if the worker ever becomes long-lived, the two-entry bound is what keeps it from retaining stale matrices.
