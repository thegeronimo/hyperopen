# Stop the optimizer's Run summary reporting "Loading…" over data it already has, and stop one flaky request re-fetching the whole universe

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md` (the full ExecPlan writing contract) and `/hyperopen/docs/PLANS.md` (the public planning entry point).

## Purpose / Big Picture

On `/portfolio/optimize` the right rail's "Run summary" card ends with a `Status` row. The owner reports that it frequently sits on a spinning "Loading…" for a long time, while the rest of the same card already shows a settled universe, goal, exposure and history-data line.

Four separate defects sit behind that one spinner. This plan fixes three of them and removes one piece of pure waste. After this change:

- **A repeat visit is usable immediately.** Today the wallet's cached history bundle hydrates from IndexedDB and paints real data, but the Status row still reads "Loading…" and the optimizer refuses to run for the entire duration of the background refresh. After this change, a refresh that runs *over an already-loaded bundle* reads "Refreshing…" and the run is allowed. Only a load with no bundle behind it still blocks.
- **A single flaky `/info` response stops costing the whole universe.** Today one rejected request inside a small targeted fallback discards a fully successful backend bundle and restarts the *entire* universe down the slow serial legacy loader. After this change, a targeted-fallback failure degrades only the instruments that fallback was for.
- **A stalled request stops pinning the spinner forever.** Today there is no timeout, no abort and no retry anywhere on the history fetch, so a request that never settles leaves the Status row spinning until the page is reloaded, with no error and no way back. After this change the fetch has a deadline, and passing it produces the ordinary `:failed` state that already retains prior data and already offers a "Refresh history" action.
- **The legacy fallback stops sleeping after its final request.** Pure waste, removed.

How a human sees it working, end to end, is written out in each milestone's acceptance.

### Orientation for someone who has not read this code before

Four namespaces matter and they hand off in this order.

1. `/hyperopen/src/hyperopen/portfolio/optimizer/infrastructure/history_api_v2_client.cljs` talks to the optimizer history backend (`POST /v1/optimizer/history-bundle`). It chunks the universe at 100 instruments per request and runs the chunks concurrently.
2. `/hyperopen/src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs` wraps that. It calls the backend, decides which instruments the backend could not serve, and optionally fills those gaps from the older Hyperliquid `/info` endpoints ("the legacy fallback"). It also catches a total backend failure and falls back to the legacy loader for everything.
3. `/hyperopen/src/hyperopen/portfolio/optimizer/application/history_workflow.cljs` is the pure state machine. `begin-history-load` stamps `history-load-state` to `{:status :loading …}`; `apply-history-success` / `apply-history-error` move it to `:succeeded` / `:failed`. Nothing else in `src/` writes that path.
4. `/hyperopen/src/hyperopen/portfolio/optimizer/application/setup_readiness.cljs` reads that status and turns it into readiness, which `/hyperopen/src/hyperopen/portfolio/optimizer/application/view_model/setup.cljs` turns into the verdict the rail and the footer pill both render.

"Readiness" here means the derived map answering *can the optimizer run right now, and if not why*. `:runnable?` is the boolean the Run button and the run pipeline both consult.

## Context References

Public refs:

- Direct owner request, 2026-08-24, with a screenshot of the Run summary card: "On the optimize page, I frequently find that the status is loading for a long time. I'd like you to investigate this and see if you could figure out how we can improve the performance and if there's an opportunity for performance improvement here." Followed by: "create an execution plan to fix some of these real deficiencies that you found. or create solutions based upon them. and implement them."

Repo artifacts:

- `/hyperopen/docs/exec-plans/tech-debt-tracker.md` — the Portfolio-owned entry recording the profiled costs of this path: a 6.2 s backend `POST /v1/optimizer/history-bundle` for a 51-asset spectate universe, a `/info` 429 backoff storm, and ~1.6 s of main-thread `align-history`. That entry is the measured baseline this plan builds on; this plan does not re-measure the backend.
- `/hyperopen/docs/exec-plans/active/2026-07-08-optimizer-setup-alignment-render-stalls.md` — the prior render-path pass. Its memo fixes still hold; this plan does not touch them.
- `/hyperopen/docs/exec-plans/completed/2026-05-03-portfolio-optimizer-selection-history-prefetch.md` — the plan that deliberately routed selection-prefetch progress through the global `history-load-state`. Milestone 3 below changes what that shared status *means* for readiness, so this prior decision is load-bearing context and is quoted in the Decision Log.
- `/hyperopen/src/hyperopen/portfolio/optimizer/application/history_cache.cljs` — the stale-while-revalidate cache whose hydration deliberately leaves `history-load-state` untouched. That docstring is the origin of the symptom Milestone 3 fixes.

Local scratch refs (non-authoritative):

- A multi-agent source audit was run on 2026-08-24 (39 candidate findings confirmed, 12 refuted after adversarial verification). Its conclusions are reproduced in this plan in full; the transcripts are session-local and are not required to execute this plan.

## Progress

- [x] (2026-08-24) Root causes established by source reading and adversarial verification; four in-scope items separated from four deferred ones (see Decision Log).
- [x] (2026-08-24) M1 — Targeted gap-fill made non-rejecting in `maybe-request-targeted-legacy-fallback!`, so the total-failure `.catch` can only ever see a real backend failure. A failed gap-fill now resolves with the API bundle plus an `:optimizer-history-targeted-fallback-failed` warning. Red check confirmed the escalation directly: pre-change `@legacy-calls` was `[[:candle "ETH"] [:candle "BTC"] [:candle "ETH"] [:funding "BTC"] [:funding "ETH"]]` and the api-v2 series map came back empty.
- [x] (2026-08-24) M2 — `request-json!` now races an `AbortController`-backed deadline, defaulting to 60s from `:request-timeout-ms` in `/hyperopen/src/hyperopen/config.cljs`. A non-positive value disables it. Red check: without the deadline the suite HANGS rather than failing, which is the defect stated plainly.
- [x] (2026-08-24) M3 — `history-refreshing?` added to `setup_readiness.cljs`; `history-loading?` narrowed to a load with no `:loaded-at-ms` behind it; `:refreshing?` carried on readiness; a `:refreshing` level added to `readiness-status` / `run-verdict` and rendered in `setup_context.cljs` and `setup_actions.cljs`; Data-health copy made consistent. Both arms of the `or` moved together, as the plan warned.
- [x] (2026-08-24) M4 — `run-legacy-entries!` now spaces only *between* requests. Serialization deliberately unchanged.
- [x] (2026-08-24) Namespace-size exceptions recorded per repo convention: `setup_readiness_test.cljs` raised 780 → 840, and a new entry added for `history_client.cljs` (533 lines, cap 560) naming the legacy-loader/api-v2-orchestration split to make later.
- [x] (2026-08-24) Adversarial review of the finished diff (21 agents, 16 verdicts, 8 confirmed) found **four real defects in this plan's own first implementation** — the deadline escalating instead of failing, M1's swallow destroying history, the refresh painting green beside warnings, and discovery left unbounded. All four fixed and pinned by tests; see `Surprises & Discoveries`.
- [ ] M5 — Owner confirmation in the running app: on a repeat visit the Status row reads "Refreshing…" and the Run button works during the background refresh; with IndexedDB cleared it still reads "Loading…" and still blocks. Gates are green (34/34); this remaining item is the human observation described under `Validation and Acceptance`, which cannot be self-verified from this environment (the Browser pane cannot render this app — its tabs report `hidden`, so Replicant's requestAnimationFrame never fires).

## Milestones

### M1 — One flaky request must not cost the whole universe

**Scope and why.** `request-api-v2-history-bundle!` in `/hyperopen/src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs` builds this promise chain:

    (-> (history-api-v2-client/request-history-bundle! …)
        (.then  normalize)                     ; A
        (.then  maybe-request-targeted-legacy-fallback!)   ; B
        (.catch total-failure-legacy-fallback))            ; C

Stage B fills per-instrument gaps from `/info` for the small set of instruments the backend could not serve. Stage C exists for a different situation entirely: the backend request itself failed, so nothing is usable and the whole universe must come from `/info`.

Because C is attached *after* B, it also catches rejections thrown by B. So one rejected `/info` request, for one instrument, inside a two-instrument targeted fallback, throws away a completely successful 20-asset backend bundle and restarts the entire universe on the serial legacy loader. That loader issues one candle request per coin plus one funding request per perp, strictly sequentially, and each perp's funding request itself paginates ~18 times (500 rows per page over a 365-day window of hourly funding). A transient blip therefore converts the fastest path into the slowest one.

**What will exist afterwards.** The total-failure fallback is attached to the api-v2 request and its normalize step only. A targeted-fallback rejection resolves to the api-v2 bundle that was already successfully fetched, plus a warning naming what could not be filled — it never escalates.

**Work.** In `history_client.cljs`, move the `.catch` so it wraps only the request and stage A, then chain stage B after it. Add a `.catch` on the targeted fallback itself that returns the api-v2 bundle with a warning rather than rejecting.

**Acceptance.** A new test in `/hyperopen/test/hyperopen/portfolio/optimizer/infrastructure/history_client_fallback_test.cljs` seeds a backend response that serves both instruments but marks one as needing fallback, makes the `/info` candle request for that one instrument reject, and asserts that (a) the resolved bundle still contains the api-v2 series for the healthy instrument, and (b) the legacy loader was NOT invoked for the whole universe. This test fails before the change (the bundle comes back entirely legacy-sourced) and passes after.

### M2 — A stalled request must not pin the spinner forever

**Scope and why.** `request-json!` in `/hyperopen/src/hyperopen/portfolio/optimizer/infrastructure/history_api_v2_client.cljs` is a bare `fetch` call. Grepping both infrastructure namespaces for `AbortController`, `signal` and `timeout` returns nothing. `history-load-state` only leaves `:loading` via `apply-history-success` or `apply-history-error`, and both are driven by the fetch promise settling. A request that never settles therefore leaves the Status row spinning indefinitely, with no error surfaced and no recovery short of a page reload.

This matters more than its size suggests: the owner's complaint is that the status sits on Loading "for a long time", and an unbounded hang fits that description better than any of the incremental costs.

**What will exist afterwards.** The history-bundle fetch has a deadline. Passing it aborts the in-flight request and rejects, which flows through the existing `apply-history-error` path to `{:status :failed}`. The user then sees the copy that already exists for that state — "History load failed. Existing history, if any, is retained." — and the existing "Refresh history" affordance. Prior history data is retained, because `apply-history-error` only replaces the load state and never touches `history-data`.

**Work.** Add a timeout to `request-json!` driven by an `AbortController`, with the duration supplied from the `optimizer-history-api` config so it is tunable and testable, defaulting to 60 seconds. 60 s is deliberately far above the 6.2 s the tech-debt tracker measured for a 51-asset bundle, so this must never fire on a merely slow-but-progressing request; it exists only to bound a hang. Thread the value from `/hyperopen/src/hyperopen/config.cljs`.

**Acceptance.** A new test in `/hyperopen/test/hyperopen/portfolio/optimizer/infrastructure/history_api_v2_client_test.cljs` supplies a `fetch-fn` that never resolves and a 10 ms timeout, and asserts the returned promise rejects with a timeout error. A second assertion at the workflow level confirms the rejection lands as `{:status :failed}` with prior `history-data` intact.

### M3 — The status must describe the data, not the fetch

**Scope and why.** This is the milestone that fixes the reported symptom.

`/hyperopen/src/hyperopen/portfolio/optimizer/application/history_cache.cljs` implements a per-wallet stale-while-revalidate cache. On a repeat visit it hydrates the last successful bundle from IndexedDB so the page has real data immediately, and its docstring states that it deliberately leaves `history-load-state` alone because "readiness/cards judge usability from the hydrated data itself."

Readiness does not do that. `build-readiness` derives:

    history-loading? (= :loading (get-in state contracts/history-load-state-status-path))

from the load state alone, never looking at `history-data`, and feeds it into both `:reason :history-loading` and the `runnable?` conjunction. So during the background refresh the rail reads "Loading…", the footer pill reads "Loading history", and `runnable?` is false — over a bundle that is already complete and on screen.

The fix is to treat a load as blocking only when there is no usable bundle behind it. Concretely, `:loaded-at-ms` is present on `history-data` after any successful load *and* on any bundle hydrated from the cache (it is in `persisted-history-keys`), and absent before the first load ever completes. That single key separates "cold" from "refreshing" exactly.

Two things make this safe rather than a loosening of correctness:

- Adding an asset still blocks, through a different and more precise guard. `incomplete-history?` compares the requested universe's instrument ids against the ids the built request could actually serve; a newly added asset with no history is dropped from the request and the mismatch blocks the run. So "runnable during a refresh" never means "runnable with a missing asset".
- The product already holds that a refresh does not change the answer. `/hyperopen/src/hyperopen/portfolio/optimizer/application/view_model/setup.cljs` records, from an owner review dated 2026-07-04, that a refresh "adds at most the newest day of data, which does not move a covariance estimate or the allocation."

**A trap that must be handled or the change appears to do nothing.** `readiness-status` gates on a two-armed `or`:

    (or (contains? #{:history-loading :holdings-loading} (:reason readiness))
        (= :loading (:status history-load-state)))

The first arm comes from `build-readiness`; the second reads the raw store path directly. Narrowing only `build-readiness` leaves the second arm still true, and the rail keeps showing the spinner. Both arms must move together.

**What will exist afterwards.** A third verdict level, `:refreshing`, distinct from `:loading`. The rail and the footer pill render it as "Refreshing…" with a quiet indicator rather than a blocking spinner, and `runnable?` stays true, so the Run button works during a background refresh. A cold load — no bundle at all — is unchanged in every respect.

**Work.**

- In `setup_readiness.cljs`, narrow `history-loading?` to a load with no `:loaded-at-ms` behind it, and add a separate non-blocking `history-refreshing?` for the other case.
- In `view_model/setup.cljs`, add the `:refreshing` level to `readiness-status` and `run-verdict`, keeping `:loading` for the cold case, and fix the second arm of the `or` to match.
- In `/hyperopen/src/hyperopen/views/portfolio/optimize/setup_context.cljs` and `/hyperopen/src/hyperopen/views/portfolio/optimize/setup_actions.cljs`, render the new level. The Run CTA must read as ready-with-refresh, not blocked.

**Acceptance, observable by a human.** Load `/portfolio/optimize` for a wallet that has been there before, so the IndexedDB record `history-bundle::<address>` exists. The Run summary shows the universe, goal and exposure immediately, the Status row reads "Refreshing…" rather than "Loading…", and the Run button is usable during that window. When the refresh lands the row becomes "Ready to run" (or the amber warning variant). With IndexedDB cleared, the same page still shows "Loading…" and still blocks, exactly as today.

**Acceptance, automated.** `build-readiness-blocks-while-history-reload-is-pending-test` in `/hyperopen/test/hyperopen/portfolio/optimizer/application/setup_readiness_test.cljs` must keep passing untouched — its fixture has no `:loaded-at-ms`, so it is the cold case and must still block. That is the regression guard proving this change did not simply delete the blocking behavior. New tests cover the warm case in both `setup_readiness_test.cljs` and `view_model_setup_boundary_test.cljs`.

### M4 — Stop sleeping after the last legacy request

**Scope and why.** `run-legacy-entries!` in `history_client.cljs` chains each legacy request off the previous one and sleeps `legacy-fallback-request-spacing-ms` after every entry — including the final one, whose sleep delays the resolved bundle while no further request is waiting on it. Production config sets that value to 200 ms.

Deliberately **not** in scope: making these requests concurrent. See the Decision Log — the serial behaviour is a pinned invariant with a documented 429 history behind it.

**What will exist afterwards.** The spacing still separates consecutive requests; it no longer delays the result after the last one. Exactly 200 ms saved per legacy pass in production, and zero when no fallback is needed.

**Acceptance.** `request-history-bundle-throttles-legacy-info-requests-test` must keep passing unchanged — it asserts `(= 1 @max-active)` and that invariant is untouched. A new assertion confirms the sleep count is one fewer than the entry count.

### M5 — Gates

Run, from `/hyperopen/.claude/worktrees/optimize-page-performance-7dc075`:

    npm run gates

This runs `npm run check`, `npm test` and `npm run test:websocket` and prints one PASS/FAIL matrix without short-circuiting. A fresh worktree has no `node_modules` and `shadow-cljs` is not on `PATH`; `npm run setup:worktree` symlinks them from the main checkout and is invoked automatically by `npm test` / `npm run check`.

## Validation and Acceptance

Run every command from the worktree root, `/hyperopen/.claude/worktrees/optimize-page-performance-7dc075`.

**The required gates.** `npm run gates` runs the three required suites (`npm run check`, `npm test`, `npm run test:websocket`) plus the repo-state lint gates, and prints a single PASS/FAIL matrix without short-circuiting on the first failure:

    npm run gates

Success is `Overall: PASS` with 34/34. Two gates are worth knowing about in advance:

- `npm run lint:docs` prints `[stale-doc]` lines for roughly two dozen governed documents that are 95–105 days old. Those are pre-existing warnings unrelated to this work and do **not** fail the gate; the gate passes as long as no `Docs check failed:` line follows them.
- `npm run lint:namespace-sizes` enforces `/hyperopen/dev/namespace_size_exceptions.edn`. The new readiness tests push `test/hyperopen/portfolio/optimizer/application/setup_readiness_test.cljs` past its previous 780-line allowance, so that entry's `:max-lines` is raised to 840 and its `:reason` extended, per the repo convention of bumping with a recorded justification rather than silently growing.

**Proving the tests are meaningful, not merely green.** Every test added by this plan must fail against unmodified source. To reproduce that check, stash only the source changes, rebuild, and run:

    git stash push -m "red-check" -- src/
    npx shadow-cljs --force-spawn compile test && node out/test.js
    git stash pop

Expected failures, all four milestones (observed 2026-08-24):

    FAIL build-readiness-allows-running-while-refreshing-a-loaded-bundle-test
      expected: (true? (:runnable? readiness))   actual: (not (true? false))
    FAIL run-verdict-reports-refreshing-not-loading-over-a-loaded-bundle-test
      expected: (= :refreshing (:level verdict)) actual: (not (= :refreshing :loading))
    FAIL request-history-bundle-targeted-fallback-failure-does-not-refetch-universe-test
      expected: (= [[:candle "ETH"]] @legacy-calls)
      actual:   [[:candle "ETH"] [:candle "BTC"] [:candle "ETH"]
                 [:funding "BTC"] [:funding "ETH"]]
    FAIL request-history-bundle-does-not-space-after-the-final-legacy-request-test
      expected: (= 3 @sleeps)                    actual: (not (= 3 4))

Note that M2's red check does not produce a failure line — it produces a **hang**. With no timeout in the source, `request-history-bundle-rejects-when-the-request-exceeds-its-timeout-test` supplies a `fetch-fn` that never settles and the suite stops at `hyperopen.portfolio.optimizer.infrastructure.history-api-v2-client-test` and never finishes. That hang *is* the defect the milestone fixes, and it is why the M1/M4 red check must be run by stashing only `history_client.cljs` rather than all of `src/`.

**Acceptance a human can observe in the running app.** Start the dev server and open the optimizer for a wallet that has visited it before, so IndexedDB holds a `history-bundle::<address>` record:

    npm run dev

Then, at `http://localhost:8080/portfolio/optimize`:

1. The right-rail "Run summary" fills in immediately from the cached bundle, and its `Status` row reads **"Refreshing…"** with a dimmed spinner — not "Loading…".
2. The Run button is usable during that window. Clicking it starts an optimization rather than being refused.
3. When the background refresh lands, the row becomes "Ready to run" (or the amber "Ready with N warnings" variant).
4. Clear the origin's IndexedDB and reload. The same page now reads **"Loading…"** and blocks, exactly as before this change — the cold path is untouched.

The `data-verdict` attribute on `[data-role="portfolio-optimizer-run-summary-status"]` carries the level (`refreshing`, `loading`, `ready`, `blocked`), so any of the above can be asserted from a browser test without reading pixels.

## Surprises & Discoveries

- Observation: the behaviour that looks most like a bug — a background selection prefetch stamping the *global* `history-load-state` and blocking the run — is written specification, not an oversight, and is pinned by three named tests.
  Evidence: `/hyperopen/docs/exec-plans/completed/2026-05-03-portfolio-optimizer-selection-history-prefetch.md` instructs the branch to "set the global `:history-load-state` to loading with a request signature for that one-instrument universe", and its Decision Log records "Store prefetch progress separately while preserving the existing global history-load-state for compatibility." `setup_readiness_test.cljs` contains `build-readiness-blocks-while-history-reload-is-pending-test`, which pins `runnable? false` *with candle history already present*. This is why M3 is scoped to `:loaded-at-ms` rather than to the prefetch source: that scoping leaves the pinned test's fixture in the cold case, so it keeps passing unchanged.

- Observation: the obvious performance recommendation — parallelise the serial legacy fallback — is contradicted by this repo's own recorded history, and was dropped.
  Evidence: `request-history-bundle-throttles-legacy-info-requests-test` asserts `(= 1 @max-active)`, i.e. serialisation is a deliberate, named invariant. `/hyperopen/docs/exec-plans/tech-debt-tracker.md` records "the Hyperliquid `/info` 429 backoff storm (four duplicate parallel POSTs per retry round) delayed holdings→universe seeding … by ~4s". Parallelising is therefore a change that has previously produced a measured regression, and it is not worth bundling into a plan whose other changes are independently safe.

- Observation: the "second normalize pass" over the history payload is not redundant, despite looking it. Deleting it would break instrument identity.
  Evidence: `request-history-bundle-chunk!` passes the 1-arity `api-v2/normalize-history-body`, which receives no request and therefore has an empty backend-id→local-id map, leaving ids as raw backend ids. The second pass, `normalize-history-bundle` at `history_client.cljs:401`, is called *with* the request and is what maps them to local instrument ids. The fusion is still worth doing — it is roughly four full walks of ~21.9k candle points — but it is an identity-mapping change in an area with a recorded history of id defects, so it is deferred rather than bundled here.

### Defects found by adversarial review of this plan's own first implementation (2026-08-24)

A 21-agent review pass over the finished diff produced 16 verdicts, 8 confirmed. Four were real defects introduced by the first cut of this work, and all four are now fixed. They are recorded because each one shows a way the first implementation looked correct and was not.

- Observation: **the M2 deadline never produced the `:failed` state it was written for.** The timeout rejection landed in the total-failure handler in `request-api-v2-history-bundle!`, which — with the shipped `:fallback-to-legacy? true` — reads any rejection as "the API is unavailable, serve the whole universe from `/info`". So a 60s deadline started a whole-universe serial legacy pass that carries no deadline of its own. The change replaced an unbounded hang with a bounded wait *followed by* an unbounded one.
  Evidence: `history_client.cljs` chain, `.catch` at the tail; `config.cljs:31` ships `:fallback-to-legacy? true`. Fixed by `history-api-v2-client/timeout-error?` plus the `escalate-to-legacy?` guard. Pinned by `request-history-bundle-timeout-does-not-escalate-to-legacy-fallback-test`, which fails pre-fix with "a timed-out load must reject, not fall back".

- Observation: **M1's swallow destroyed history.** The bundle it resolved with is `{:api-v2-history … :warnings …}` and carries none of `:candle-history-by-coin` / `:funding-history-by-coin` / `:vault-details-by-address` — those enter only via `with-targeted-legacy-fallback`. `apply-history-success` writes the bundle with `assoc-in`, replacing `history-data` **wholesale**, so a transient `/info` error wiped candles an earlier load had fetched, flipped those assets to `:incomplete-history`, and persisted the degraded bundle over the per-wallet IndexedDB record. The pre-change escalation, whatever else was wrong with it, ended in `apply-history-error`, which touches only `history-load-state` and keeps the data — exactly what the existing UI copy promises ("History load failed. Existing history, if any, is retained.").
  Fixed by splitting the two cases: `api-serves-every-fallback?` decides whether the gap-fill was a lossless top-up (degrade to a warning) or the only source of an asset's history (fail the load, via `no-escalation-error`, so prior data survives and the legacy loader is still not started). Pinned by the `-lossless-` and `-lossy-` pair in `history_client_fallback_test.cljs`.

- Observation: **M3 painted the green "ready" tone beside visible warning cards.** `readiness-status` returned `:refreshing` ahead of the caution branch, and `run-status` maps `:refreshing` to `tone :ready`. That is precisely the contradiction `run-verdict`'s own docstring forbids. Fixed by ordering cautions ahead of the refresh notice, plus a `(zero? warning-count)` guard in `run-verdict` so the property does not depend on that ordering alone.

- Observation: **M2 left `request-instruments!` (discovery) unbounded**, so the hang class this plan claims to close still had a second entrance. Discovery resolves the canonical backend ids; without them nothing qualifies for the api-v2 path, so a hung discovery silently drops every later load onto the legacy loader. Now carries the same deadline.

Also confirmed but deliberately not fixed here: the `:optimizer-history-targeted-fallback-failed` warning is written to `history-load-state :warnings`, which **no view reads** — grep confirms the Data health panel builds its list from `(:warnings request)`. That warning is therefore currently invisible. It is not load-bearing: the lossy case (the one a user needs to act on) now fails the load and surfaces through the panel's `:error-message`, and the lossless case costs the user nothing. Routing bundle warnings into the readiness panel is a separate change to the warning pipeline and is left for follow-up rather than bolted on here.

Notable refutations, recorded so they are not re-raised: a run started during a refresh does **not** race the in-flight load into torn state; the unguarded `.abort` cannot skip the reject; and `run-legacy-entries!` being all-or-nothing is pre-existing behaviour that M1 did not worsen.

## Decision Log

- Decision: fix the status semantics by keying on `:loaded-at-ms` (is there a usable bundle?) rather than on the load's source (was this a prefetch or a full load?).
  Rationale: the source-based framing was refuted — routing prefetch through the global status is written spec with tests behind it. The data-based framing expresses the thing the user actually cares about ("do you have something usable?"), covers the warm-cache case the owner reported, and — because the pinned test's fixture carries no `:loaded-at-ms` — leaves the deliberate cold-load blocking invariant provably intact.

- Decision: add a new `:refreshing` verdict level rather than silently reporting `:ready` during a refresh.
  Rationale: the refresh is real and the user should be able to see it is happening; the defect is that it *blocks*, not that it is visible. A distinct level also keeps the rail and the footer pill honest with each other, which the existing `run-verdict` docstring names as its whole purpose.

- Decision: default the new fetch timeout to 60 s.
  Rationale: the tech-debt tracker measured 6.2 s for a 51-asset bundle, the largest real case on record. 60 s is an order of magnitude above it, so it cannot fire on a slow-but-progressing request; it exists purely to bound a hang. It is config-driven so it can be tuned without a code change.

- Decision: do not parallelise the legacy fallback in this plan; remove only the trailing sleep.
  Rationale: serialisation is pinned by a named test and the repo has a recorded `/info` 429 storm from parallel requests. Removing the trailing sleep is pure waste-removal with no behavioural change and no test churn. Changing concurrency needs its own plan with a rate-limit measurement.

- Decision: defer the freshness gate that would skip the revalidate entirely.
  Rationale: it is the single largest win — it stops the fetch happening at all on a repeat visit rather than making it non-blocking — but it decides *how stale is too stale* for the data an allocation is computed from. That is a product-risk call for the owner, not a performance tuning knob, and it deserves an explicit threshold decision. M3 delivers most of the user-visible benefit (the page is usable immediately) without taking that risk. Recorded for a follow-up plan.

## Outcomes & Retrospective

(2026-08-24) All four code milestones landed. `npm run gates` reports **34/34 PASS**, 6,950 tests / 38,594 assertions. M5 remains open pending the owner's observation in the running app, which is the one thing this environment cannot self-verify.

**What changed, in one line each.** A failed gap-fill for a couple of instruments can no longer discard a successful backend bundle and refetch the whole universe. A hung request can no longer pin the status spinner forever. A background refresh over an already-loaded bundle no longer claims to be "Loading" and no longer blocks the run. The legacy fallback no longer sleeps after its last request.

**Did this reduce or increase complexity?** Slightly increased, and deliberately so. The change adds one concept — a load can be *cold* or a *refresh* — expressed as one predicate (`history-refreshing?`) and one verdict level (`:refreshing`). That is a genuine distinction the system was previously collapsing, and the collapse is what produced the reported bug: the stale-while-revalidate cache was written assuming readiness judged usability from the data, while readiness actually judged it from the fetch. Naming the distinction makes the two halves agree. The other three changes are net simplifications: an error handler now has the narrow scope its name always implied, a fetch has the deadline every network call should have, and a loop stopped doing one thing it never needed to do.

**The most valuable thing learned.** Two of the highest-ranked findings from the investigation that produced this plan did not survive contact with the repository, and both would have been damaging to implement:

- "Prefetch stamping the global load state is a bug" — it is written specification with three tests behind it. Had the fix keyed on the load's *source*, it would have fought a deliberate design and broken pinned tests. Keying on `:loaded-at-ms` instead expresses the user-facing question ("is there usable data?") and left `build-readiness-blocks-while-history-reload-is-pending-test` passing untouched, which is now the regression guard proving the blocking behavior was narrowed rather than deleted.
- "Parallelise the serial legacy fallback" — the serialization is a named, pinned invariant, and the tech-debt tracker records a real `/info` 429 backoff storm caused by parallel requests. The recommendation was dropped and only the trailing sleep removed.

The general lesson: in this repository, a docstring or a test name is frequently the *record of a prior incident*. Reading them before changing the behavior they guard is the difference between a fix and a regression.

**What remains.** Four items from the same investigation are deliberately not in this plan, in descending value:

1. **Skip the revalidate entirely when the cached bundle is fresh.** The largest single win — it stops the fetch happening at all on a repeat visit rather than making it non-blocking. `max-hydration-age-ms` (7 days) gates display only; nothing gates the fetch, and `history-request` hardcodes 1095 bars without ever consulting `:loaded-at-ms`. Deferred because choosing the staleness threshold is a product-risk decision about the data an allocation is computed from, not a tuning knob. Needs an owner decision on the threshold.
2. **Fuse the double normalize pass** (~4 full walks of ~21.9k candle points). Deferred because the second pass is where backend→local instrument-id canonicalization happens, and this area has a recorded history of id defects.
3. **Bounded concurrency for the legacy fallback.** Needs a rate-limit measurement first, for the reason recorded above.
4. **Parallel or larger-page funding pagination** (~18 sequential pages per perp over a 365-day hourly window).
