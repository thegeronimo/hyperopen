# Market Funding History Pagination for Hourly Markets

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and must be maintained in accordance with that file.

## Purpose / Big Picture

Today the active-asset funding predictability tooltip can show a truncated 30-day picture for some named-Dex perpetual markets such as `cash:USA500` and `km:US500`. Those markets emit roughly hourly funding rows, but the external `fundingHistory` endpoint appears to cap a single response at 500 rows. A 30-day request therefore returns only the earliest part of the requested window, which leaves the newest several days missing from `Rate History` and the highest lag bars missing from `Past Rate Correlation`.

After this change, callers that ask for a market funding history window will receive the full requested window even when the remote endpoint splits it across multiple capped pages. A contributor can verify the fix by running the focused endpoint and cache tests and by confirming that a simulated 30-day hourly window is reassembled from multiple 500-row pages into a complete set of rows.

## Progress

- [x] (2026-03-06 18:52Z) Reproduced the bug against the live endpoint and opened `bd` bug `hyperopen-9gr`.
- [x] (2026-03-06 18:57Z) Claimed `hyperopen-9gr` and reviewed `/hyperopen/.agents/PLANS.md` plus `/hyperopen/docs/WORK_TRACKING.md`.
- [x] (2026-03-06 18:59Z) Confirmed live behavior: `cash:USA500` and `km:US500` return exactly 500 rows for a 30-day `fundingHistory` request, ending on 2026-02-25 rather than 2026-03-06.
- [x] (2026-03-06 19:03Z) Implemented paginated market `fundingHistory` fetching in `/hyperopen/src/hyperopen/api/endpoints/market.cljs` without changing the public API signature.
- [x] (2026-03-06 19:07Z) Added endpoint tests covering multi-page reconstruction, capped non-advancing pages, and raw-page-count continuation after normalization drops.
- [x] (2026-03-06 19:10Z) Restored missing JavaScript dependencies with `npm ci` so required validation gates could run in this environment.
- [x] (2026-03-06 19:13Z) Passed `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-03-06 19:07Z) Closed `hyperopen-9gr`, moved this plan to `/hyperopen/docs/exec-plans/completed/`, and recorded final handoff notes.

## Surprises & Discoveries

- Observation: The live `fundingHistory` endpoint for named-Dex hourly markets returns capped ascending rows for a wide window rather than the newest rows.
  Evidence: A 30-day request for `cash:USA500` on 2026-03-06 returned 500 rows spanning `2026-02-04T19:00:00Z` through `2026-02-25T14:00:00Z`, while a 10-day request reached `2026-03-06T18:00:00Z`.

- Observation: The existing cache sync code can only recover the missing recent tail on a later refresh because it treats the first capped response as a complete snapshot for the current sync.
  Evidence: `/hyperopen/src/hyperopen/funding/history_cache.cljs` requests one `[start-time-ms,end-time-ms]` window per sync and persists the result immediately.

- Observation: The required npm scripts assumed `node_modules/.bin/shadow-cljs` was present, but this worktree initially had no installed JavaScript dependencies.
  Evidence: `npm test` and `npm run test:websocket` failed with `sh: shadow-cljs: command not found` until `npm ci` restored the lockfile install.

## Decision Log

- Decision: Fix pagination in `/hyperopen/src/hyperopen/api/endpoints/market.cljs` rather than in the tooltip or predictability reducer.
  Rationale: The 500-row cap is an external API behavior. The endpoint wrapper is the correct infrastructure boundary to hide that behavior so all callers receive complete windows.
  Date/Author: 2026-03-06 / Codex

- Decision: Continue paging while the normalized page size equals the observed cap and the newest normalized timestamp advances toward the requested end time.
  Rationale: This matches the already-proven `userFunding` pagination pattern, avoids infinite loops on malformed pages, and keeps the public `request-market-funding-history!` signature stable.
  Date/Author: 2026-03-06 / Codex

- Decision: Keep cache sync semantics unchanged after the endpoint fix.
  Rationale: Once the endpoint wrapper returns a complete requested window, `/hyperopen/src/hyperopen/funding/history_cache.cljs` can continue to merge, trim, and persist rows exactly as it does today.
  Date/Author: 2026-03-06 / Codex

- Decision: Generate page-specific `:dedupe-key` and `:cache-key` values for paginated market funding requests, even when the caller provides explicit key bases.
  Rationale: Reusing the same key across multiple pages would cause the info client single-flight/cache layer to treat page 2 as page 1. Page-specific keys preserve caller intent while keeping each transport request unique.
  Date/Author: 2026-03-06 / Codex

## Outcomes & Retrospective

Implemented as planned. `request-market-funding-history!` now walks forward through capped `fundingHistory` pages and returns one merged, deduped, ascending row vector to callers. The active-asset predictability path does not need cache-layer changes because its existing sync behavior now receives a complete requested window in one call chain.

The new tests lock in the exact bug class that affected `USA500-USDTO`: a first page of 500 rows is no longer treated as a complete 30-day answer, a malformed repeated capped page stops safely without looping forever, and pagination continues when a capped raw page loses rows during normalization. Required validation gates passed after restoring the lockfile install.

## Context and Orientation

The failing user-visible path starts in `/hyperopen/src/hyperopen/runtime/effect_adapters/funding.cljs`, where `sync-active-asset-funding-predictability` asks the funding history cache to provide the last 30 days of market funding rows. The cache orchestrator lives in `/hyperopen/src/hyperopen/funding/history_cache.cljs`. It loads any stored snapshot, requests the missing network window, merges rows by timestamp, trims to the rolling retention window, and persists the result.

The actual network request for market funding history is built in `/hyperopen/src/hyperopen/api/endpoints/market.cljs` by `request-market-funding-history!`. Today that function sends a single `POST /info` body shaped like `{"type":"fundingHistory","coin":...,"startTime":...,"endTime":...}` and normalizes whatever rows come back. It does not currently know that some markets require multiple calls to cover the full time range.

The predictability math itself is in `/hyperopen/src/hyperopen/funding/predictability.cljs`. It builds a fixed 30-day daily series and leaves missing days as `nil`. That behavior is correct; it is only wrong because the upstream funding history rows are incomplete. The SVG chart views in `/hyperopen/src/hyperopen/views/funding_rate_plot.cljs` and `/hyperopen/src/hyperopen/views/autocorrelation_plot.cljs` simply render the summary they are given.

The existing pagination model to copy is in `/hyperopen/src/hyperopen/api/endpoints/account.cljs`, where `request-user-funding-history!` keeps fetching pages until the normalized timestamps stop advancing or the requested end time is reached. A “page” in this repository means one capped response from the remote endpoint that must be chained with later requests to recover the full logical result.

## Plan of Work

First, update `/hyperopen/src/hyperopen/api/endpoints/market.cljs` so `request-market-funding-history!` pages forward across the requested time window. Add a small internal helper to strip any pagination-only options before each transport call, a helper to build one request body, and a recursive or loop-like Promise chain that requests the next page using `last-normalized-time-ms + 1` as the new `startTime`. Stop when the normalized page is empty, when the page size is smaller than the expected cap, when the last normalized timestamp does not advance, or when the requested end time has been reached. Merge accumulated rows in timestamp order before returning.

Second, keep `/hyperopen/src/hyperopen/funding/history_cache.cljs` behavior stable and rely on the improved endpoint wrapper to hand back complete rows. If implementation reveals that the cache tests need to reflect fuller results from a single fetch, update those tests without changing the cache’s public return contract.

Third, add focused tests in `/hyperopen/test/hyperopen/api/endpoints/market_test.cljs` that simulate a 30-day hourly request split into two or more pages. The tests must prove that the wrapper returns all rows in ascending order, sends subsequent requests with an incremented `startTime`, preserves explicit request options, and stops safely if a capped page fails to advance. Add or update cache-level tests in `/hyperopen/test/hyperopen/funding/history_cache_test.cljs` only if needed to lock in the user-visible symptom fix at the sync boundary.

Finally, run `/hyperopen` validation commands `npm run check`, `npm test`, and `npm run test:websocket`. Record the results here and close `hyperopen-9gr` if the fix is complete.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/api/endpoints/market.cljs` to add market funding history pagination helpers and update `request-market-funding-history!`.
2. Edit `/hyperopen/test/hyperopen/api/endpoints/market_test.cljs` to add capped multi-page and non-advancing-page coverage.
3. Edit `/hyperopen/test/hyperopen/funding/history_cache_test.cljs` only if a cache-level assertion is needed to prove the single-sync recovery path.
4. Run:
   `npm ci`
   `npm run check`
   `npm test`
   `npm run test:websocket`
5. Update this file’s `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` sections with actual results.
6. Close `hyperopen-9gr` with `bd close hyperopen-9gr --reason "Completed" --json` if validation passes and no follow-up blocker remains.

## Validation and Acceptance

Acceptance is behavior-based.

The endpoint wrapper acceptance is met when a test can stub two capped 500-row pages and `request-market-funding-history!` returns all rows from both pages in ascending timestamp order while issuing a second request whose `startTime` equals `last-row-time + 1`.

The regression acceptance is met when a test scenario representing roughly hourly funding over 30 days no longer truncates at 500 rows on the first sync. The proof can be either an endpoint test that reconstructs 720 hourly rows from multiple pages or a cache test that receives a complete first-sync result from the paginated endpoint seam.

Required validation gates:

- `npm run check`
- `npm test`
- `npm run test:websocket`

## Idempotence and Recovery

These edits are additive and safe to re-run. The pagination helper must guard against non-advancing pages so retries do not loop forever on malformed endpoint responses. If a test fails partway through implementation, the safe recovery path is to rerun the focused test files first, then rerun the full required gates once the focused failures are resolved.

No data migration is required. Existing cached snapshots remain valid; they will simply be supplemented with complete future window fetches after the endpoint wrapper is fixed.

## Artifacts and Notes

Live reproduction notes from 2026-03-06:

    cash:USA500 30d request -> 500 rows
    first -> 2026-02-04T19:00:00.028Z
    last  -> 2026-02-25T14:00:00.023Z

    cash:USA500 10d request -> 240 rows
    first -> 2026-02-24T19:00:00.066Z
    last  -> 2026-03-06T18:00:00.056Z

The difference shows that the remote endpoint can serve recent data, but only when the requested window is small enough to stay below the cap.

Validation notes from 2026-03-06:

    npm ci
    added 281 packages, and audited 282 packages in 3s

    npm run check
    [:app] Build completed.
    [:portfolio-worker] Build completed.
    [:test] Build completed.

    npm test
    Ran 1955 tests containing 10032 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    Ran 333 tests containing 1840 assertions.
    0 failures, 0 errors.

## Interfaces and Dependencies

In `/hyperopen/src/hyperopen/api/endpoints/market.cljs`, keep the public interface:

    (request-market-funding-history! post-info! coin opts) -> js/Promise<Vec<Row>>

The returned row shape must remain:

    {:coin <string>
     :time-ms <integer>
     :time <integer>
     :funding-rate-raw <number>
     :fundingRate <number>
     :premium <number-or-nil>}

Internal helpers may be added in the same namespace, but callers in `/hyperopen/src/hyperopen/api/gateway/market.cljs`, `/hyperopen/src/hyperopen/api/default.cljs`, `/hyperopen/src/hyperopen/api/instance.cljs`, and `/hyperopen/src/hyperopen/funding/history_cache.cljs` must not need signature changes.

Revision note (2026-03-06 / Codex): Created the plan after reproducing the bug against the live endpoint and identifying single-call `fundingHistory` truncation on hourly named-Dex markets.
Revision note (2026-03-06 / Codex): Updated the plan after implementation to record the paginated endpoint fix, the dependency restore needed for validation, and the final gate results.
