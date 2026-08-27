# Optimizer: exposure reachability presolve, degenerate Minimum-risk gate, honest solver status

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

A Minimum-risk run on a 20-asset seeded-from-holdings universe failed with
`INFEASIBLE OPTIMIZATION / solver-returned-invalid-solution`, reporting
`net-band expected between 1.2500 and N/A but solver returned 0.0000` and
`gross-floor expected between 3.9950 and N/A but solver returned 0.0000`.

Three separate defects combine to produce that message:

1. **The request is genuinely infeasible and presolve does not say so.** The
   universe is fully single-signed (18 long, 2 short). The only two short-side
   assets are the two modelled-history assets, whose history-assumption caps
   (3% conservative, 5% proxy) bound the entire short book at 0.08x of NAV. On a
   fixed-sign box `net = gross - 2 * short`, so a 3.995x gross floor forces
   `net >= 3.835x`, while the net band allows at most ~1.362x. `domain/constraints`
   checks net bounds and gross bounds independently and never checks that the two
   are *jointly* reachable, so every individual check passes.
2. **The solver's status is never read, so infeasibility is reported as a lie.**
   The `osqp` npm wrapper's `solve()` returns only the primal vector; it never
   exposes `info.status_val`. `infrastructure/osqp.cljs` therefore hardcodes
   `:status :solved` for whatever comes back. On a primal-infeasible problem OSQP
   returns a near-zero iterate, which is stamped "solved" and only caught by the
   post-solve validator in `engine/target_selection.cljs` -- whose copy blames the
   solver for what is almost always an infeasible request.
3. **Minimum risk has no budget row, so it collapses to cash.** Textbook GMV is
   `min w'Ew s.t. sum(w) = 1`; the budget row is what forbids the empty book.
   Here the exposure policy replaces it, and every other constraint (per-asset
   caps, `gross-max` L1) is an *upper* bound. Clear the exposure floor and `w = 0`
   satisfies everything at exactly zero variance, so the engine correctly and
   silently returns an all-cash "optimum". The owner observed exactly this:
   "when I remove the constraint ... it basically just sets the target to zero for
   every single asset".

After this change: an unreachable exposure request is rejected at presolve with a
message naming the actual short/long capacity and the assets that cap it; a
Minimum-risk run with no exposure floor is rejected rather than silently
returning cash; OSQP infeasibility is classified at the solver boundary instead of
downstream; and every one of these surfaces offers the user concrete, one-click
paths to unblock the run.

## Context References

Public refs:

- Owner report 2026-08-27 with two screenshots (setup + infeasible banner; results
  tab showing 0 long / 0 short of 20 assets, every asset at a bound limit).
- Owner follow-up 2026-08-27: "when there is a warning like that surface, it would
  be helpful to give the user a suggestion for what they could do to unblock the
  optimizer. because it seems like they do have a few paths they can take."

Repo artifacts:

- `/hyperopen/src/hyperopen/portfolio/optimizer/domain/constraints.cljs` -- presolve `violations`.
- `/hyperopen/src/hyperopen/portfolio/optimizer/domain/objectives.cljs` -- `build-solver-plan`, the signed-linear gross floor and net band rows.
- `/hyperopen/src/hyperopen/portfolio/optimizer/infrastructure/osqp.cljs` -- `normalize-solution`.
- `/hyperopen/tools/optimizer/patch_osqp.mjs` -- the anchored vendored-osqp patch.
- `/hyperopen/src/hyperopen/views/portfolio/optimize/infeasible_panel.cljs` -- violation copy and control highlighting.
- `/hyperopen/src/hyperopen/portfolio/optimizer/application/view_model/setup.cljs` -- `warning-group-action`, the existing remediation-button pattern.

## Progress

- [x] (2026-08-27) Diagnosed all three defects from source; confirmed the sign
      structure and the 0.08x short capacity against the owner's screenshots.
- [x] (2026-08-27) Milestone 1: reachability presolve check (`:net-unreachable-given-sides`).
- [x] (2026-08-27) Milestone 2: degenerate Minimum-risk gate (`:minimum-risk-without-exposure-floor`).
- [x] (2026-08-27) Milestone 3: honest OSQP status at the solver boundary.
- [x] (2026-08-27) Milestone 4: actionable remediation across the infeasible banner and readiness warnings.
- [x] (2026-08-27) Milestone 5: required gates (`npm run gates`) plus focused optimizer suites.
- [x] (2026-08-27) Repaired the two degenerate parity fixtures the cash-collapse
      gate exposed and hardened `first-problem`, restoring the full 898-namespace
      `npm test` run (see `Surprises & Discoveries`).

- [x] (2026-08-27) Corrected the remediation math: every suggested value is now
      computed against the REALIZED-gross band at the gross FLOOR, not the
      relaxed `q * gross-ceiling` slack the feasibility test uses. Closed-loop
      verified: 22 of 22 dispatched one-click fixes clear their own
      infeasibility through the real action + engine round trip.
- [x] (2026-08-27) `npm run gates` 34/34 PASS; `npm test` 903/903 namespaces,
      6375 tests / 35997 assertions, 0 failures; `npm run test:websocket` 584
      tests, 0 failures.
- [ ] Owner review of the two product-visible decisions recorded below (the
      Conservative preset gross pin, and blocking rather than silently
      returning cash), plus a browser-QA pass over the rendered banner.

## Decision Log

- Reachability is checked with *sound necessary* bounds, never an exact 2D
  reachable-set solve: `net = gross - 2S` with `S in [S_min, S_max]` and
  `net = 2L - gross` with `L in [L_min, L_max]` give an interval that can never
  declare a feasible request infeasible.
- The new check is emitted only when no coarser net/gross feasibility code already
  fired, so existing violation expectations are unchanged.

## Surprises & Discoveries

- The `osqp` wrapper cannot report status without walking the OSQP workspace
  struct in the wasm heap; there is no exported accessor. See Milestone 3 notes.

- **`npm test` reports success on a run that never finished.** The cash-collapse
  gate of Milestone 2 correctly rejected two pre-existing parity fixtures in
  `solver-adapter-parity-test` that had only exposure *ceilings*. Those fixtures
  did `(assoc (first (:problems plan)) ...)` unguarded, so a rejected plan (no
  `:problems`) produced a problem map with no `:quadratic`, and
  `solve-with-quadprog` dereferenced `.length` on `nil`. `cljs.test` wraps the
  call that *returns* an `(async done)` object in `try`/`catch`, but not the
  later *invocation* of its body — so the throw escaped a scheduler callback,
  `done` never fired, and the runner abandoned the rest of the suite while still
  exiting 0:

        $ npm test; echo "EXIT=$?"
        ...
        FAIL in (signed-gross-and-net-exposure-fixture-matches-between-solvers-test) (:)
        Unexpected error: TypeError: Cannot read properties of undefined (reading 'length')
        WARNING: Async test called done more than one time.
        EXIT=0
        $ grep -c '^Testing ' baseline.txt
        392                      # of 898 namespaces; no "Ran N tests" summary at all

  Every test namespace added by this plan sits alphabetically after the abort
  point and was therefore never executed, while the gate stayed green. Two
  lessons, both now encoded: **a passing `npm test` exit code is not evidence the
  suite ran** — check for the `Ran N tests` summary and the namespace count; and
  a presolve gate that newly rejects a request will decapitate any async test
  that indexes into `:problems` without checking `:status` first.

- **Adding a `:gross-floor` alone does not fix such a fixture.** The floor is
  silently dropped unless `gross-floor-signs` can return a full sign vector,
  which needs every asset single-signed. A universe whose instruments carry no
  `:position-side` gets bounds that straddle zero, so the floor never reaches the
  encoding and the request still collapses to cash. `:position-side` and
  `:gross-floor` are a *pair*; neither works without the other.

## Validation

Run everything from the worktree root. A fresh worktree has no `node_modules`, so
run `npm run setup:worktree` first if any gate fails with an opaque error
(`npm test` and `npm run check` invoke that guard themselves).

### Required gates

Per `AGENTS.md`, every milestone that changes code must pass all three. Run them
together as `npm run gates`, which prints a single PASS/FAIL matrix and does
**not** short-circuit on the first failure:

- `npm run check` — repo-state lints. `lint:docs` runs early in this chain, so a
  malformed ExecPlan fails `check` before anything compiles; this plan must keep
  a `## Validation` heading and at least one unchecked `- [ ]` item in `Progress`
  for as long as it stays under `active/`.
- `npm test` — the ClojureScript suite.
- `npm run test:websocket` — the websocket runtime suite.

**How to read `npm test`, and why the exit code is not enough.** A test that
throws in the synchronous part of an `(async done)` body abandons the run *and
still exits 0* (see `Surprises & Discoveries`). Accept the gate only when both of
these hold:

    $ npm test 2>&1 | tee /tmp/test.txt | tail -4
    === Test Results ===
    Ran 6314 tests containing 35567 assertions.
    0 failures, 0 errors.

    $ grep -c '^Testing ' /tmp/test.txt
    898                        # must equal the count generate-test-runner.mjs reports

A missing `Ran N tests` line, or a namespace count below the generated total, is
a failure however green the exit code looks.

### New and changed test namespaces, and what each proves

Milestone 1 — reachability presolve:

- `hyperopen.portfolio.optimizer.domain.exposure-reachability-test` (new). Proves
  the joint check fires on the reported shape (a 3.995x gross floor against 0.08x
  of short capacity) and that it is *sound*: it stays silent on a two-sided
  universe where the floor was dropped, on a long-only book sitting exactly at
  its net target, on locked weights, and on degenerate inputs, and it defers to
  the coarser `gross-floor-above-gross-max` / long-capacity codes when those
  already fired — so no pre-existing violation expectation moves.

Milestone 2 — degenerate Minimum risk:

- `hyperopen.portfolio.optimizer.domain.objectives-test` (extended). Proves the
  ceilings-only case is rejected as `:objective-collapses-to-cash` carrying
  `:minimum-risk-without-exposure-floor`; that each thing which genuinely forbids
  `w = 0` — a gross floor, a non-zero net equality, a positive net floor, a
  held-position lock — still plans a solve; that a *zero* net pin is still a
  collapse; that the suggested floor is read from the current book; and that the
  gate leaves Equal Risk and Inverse Volatility alone.

Milestone 3 — honest solver status:

- `hyperopen.portfolio.optimizer.infrastructure.osqp-solution-classification-test`
  (new). Proves the row classifier agrees with what `target-selection` accepts,
  treats OSQP infinity as an absent bound rather than a number, flags the
  `OSQP_NAN` sentinel against a finite row, and reports the worst row. Its
  end-to-end cases prove a primal-infeasible request now surfaces as infeasible
  rather than a confident `0.0000`, that the same shape with a reachable net band
  still solves, and that an ordinary long-only request is unaffected.
- `hyperopen.portfolio.optimizer.application.engine-solver-diagnostics-test`
  (extended). Proves the downstream copy stops inventing a missing bound for
  one-sided rows, keeps reading two-sided rows as a range, and repeats the
  solver's own explanation instead of blaming the solver.
- `hyperopen.portfolio.optimizer.infrastructure.solver-adapter-parity-test`
  (repaired). The two `:minimum-variance` fixtures that had only ceilings now
  pin `:position-side` per asset *and* set a slack `:gross-floor` (0.5 under the
  1.2x gross max; 0.2 under the turnover-implied 0.5x gross). Both are required:
  without single-signed bounds `gross-floor-signs` returns `nil` and the floor is
  dropped. The asserted parity weights are unchanged — `[0.6 -0.6]` and
  `[0.25 -0.25]` clear both floors — which is the evidence that the floors fixed
  the degeneracy without moving either optimum. `first-problem` now returns a
  marked pseudo-problem instead of indexing blindly, so a future presolve
  regression fails one test with a readable message rather than killing the run.

Milestone 4 — remediation:

- `hyperopen.views.portfolio.optimize.infeasible-panel-test` (new). Proves each
  offered path is derived from the violation payload rather than hardcoded: the
  raise-net-target path pins net to the reachable bound, the widen-band path
  derives the smallest clearing band, the short-capacity path is copy-only and
  names the capping assets, suggestions whose payload fields are missing are
  omitted, the below-band case mirrors the long side, the Minimum-risk block
  leads with the suggested gross floor and degrades to copy with no current book,
  and an unmodelled violation code renders no remediation block at all.

### Acceptance

- `npm run gates` reports its full PASS matrix with no FAIL row.
- `npm test` reaches `Ran N tests` with all 898 namespaces executed.
- Reverting any one of the three source fixes turns its own namespace red and
  nothing else — record that red-then-green evidence here on completion.

Browser QA: Milestone 4 touches `views/portfolio/optimize/**`, so run the
smallest relevant Playwright command first and broaden only after it passes, per
`docs/BROWSER_TESTING.md`. Explicitly stop any Browser MCP session opened for
exploration before concluding.

## Outcomes & Retrospective

Shipped, all required gates green (34/34).

What the work actually turned out to be: three independent defects that combined
into one misleading symptom. The reported message blamed the solver; the solver
was the only component behaving correctly.

- **OSQP was right all along.** Probing the vendored wasm showed OSQP returns
  `PRIMAL_INFEASIBLE` on the reported request and fills `solution->x` with
  `OSQP_NAN`, which its `constants.h` defines as `((c_float)0x7fc00000)` -- a
  CAST, so every element is the finite double `2143289344.0`. The split-variable
  decode computes `w = x_plus - x_minus`, and the identical sentinel cancels to
  EXACTLY `0.0`. That is the entire provenance of "solver returned 0.0000": a
  correct infeasibility verdict, laundered into a plausible all-cash portfolio
  by a wrapper that never exposed `info.status_val`.
- **The request was genuinely infeasible**, and presolve could not say so
  because it checked net and gross independently. The two short-side assets were
  the only modelled-history members, and their assumption caps (3% conservative,
  5% proxy) bounded the whole short book at 0.08x -- so a 3.995x gross floor
  forced net >= 3.835x against a band allowing at most ~1.362x.
- **Minimum risk had no budget row.** With only ceilings, cash is globally
  optimal at zero variance. The shipped `:conservative` preset (zero net, gross
  ceiling only) had therefore ALWAYS returned an all-cash book under the default
  objective; the gate did not break it, it made it visible.

Lessons worth carrying:

- A *sound necessary* bound is right for deciding infeasibility and wrong for
  proposing a remedy. Three call sites derived remediation values from the
  presolve's relaxed `q * gross-ceiling` slack; the band binds at the gross
  FLOOR, so each wrote a value that was still infeasible -- and the cash-collapse
  gate's button wrote a floor that tripped the reachability check, whose button
  wrote a value that tripped the gate: a closed two-state loop. The fix was one
  shared helper; the guard is the closed-loop test.
- Unit tests on hand-built maps could not have caught the two worst defects. The
  panel gated on a keyword VALUE (`:direction`) that the worker boundary
  stringifies, so the whole remediation block rendered in tests and vanished in
  production. Only a wire round-trip test reaches that.
- A throw inside an `(async done)` block silently DECAPITATED the suite: 392 of
  898 namespaces ran, no summary line, exit code 0. `first-problem` is now
  hardened so a plan-status regression reds one test instead of hiding half the
  suite.

Product-visible decisions, flagged for owner review:

1. `:conservative` gained `:gross-min 1.0` (its own advertised gross figure) and
   `policy->constraints` now treats a zero-width gross band as a PIN that
   survives pad interaction, so the preset stops silently meaning "hold cash".
2. The cash-collapse case BLOCKS with remediation rather than returning an
   all-cash answer. This is deliberate: the owner's report was precisely that
   the silent all-cash answer "is clearly not what we want".

Deferred, deliberately: a browser-QA pass over the rendered banner (repo
contract asks for one on UI work); the pad still renders a pinned gross band and
a `gross <= X` ceiling identically, distinguished only by the echo line.
