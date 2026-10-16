# Vault screener (tear-sheet metric filters on the vaults page)

This ExecPlan is a living document. Keep `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` up to date as work proceeds.

## Purpose / Big Picture

The vaults list (`/vaults`) shows name, leader, APR, TVL, deposit, age and a sparkline, but none of the tear-sheet metrics (Sharpe, max drawdown, Sortino, ...) that each vault's detail page computes. The vault index payload cannot support them: it carries about 12 PnL-only points per range with no timestamps or account value. Real metrics need each vault's `vaultDetails` history, one `/info` request per vault.

After this change the vaults page has an **All vaults | Screener** switch. In the Screener a user:

1. narrows the universe for free from data already loaded (minimum age, minimum TVL, closed vaults excluded, child vaults rolled into their parent, optionally always including vaults they have deposited in), with a live count;
2. picks a measurement window and metric criteria (Sharpe ≥, Max drawdown ≤, Sortino, Calmar, Volatility, CAGR, Cumulative return, Longest drawdown, Omega, Profit factor) plus a minimum-history rule;
3. sees an estimate (vaults to evaluate, already cached, to fetch, time at one request every 2 s) and runs the screen;
4. watches a paced background run (progress, ETA, pause/resume/cancel, automatic slow-down while Hyperliquid's `/info` rate limit is cooling down) with provisional passing rows;
5. gets results grouped as Pass / Near miss / Fail / Not enough history, sortable by metric, with thresholds editable afterwards without refetching; changing the window recomputes from cache.

Metrics use exactly the detail page's pipeline (`returns-history-context` → returns chart points → `compute-metrics-sync`), so the screener's Sharpe for a vault equals the Sharpe on that vault's page for the same window.

Design reference: the canvas artifact "Vault Screener" (https://claude.ai/artifact/EjBBef9KSx4pPpdLm6ehfS), artboards 1–5.

## Context References

Public refs:
- Direct user request in this session (2026-10-16): "This was good. Go ahead and build it out." following the feasibility analysis and the Vault Screener design canvas.

Repo artifacts:
- List page: `src/hyperopen/views/vaults/list_view.cljs`, `src/hyperopen/vaults/application/list_vm/*.cljs` (parsed rows: `:tvl`, `:age-days`, `:is-closed?`, `:relationship-type`, `:has-deposit?`).
- Detail metrics pipeline: `src/hyperopen/vaults/detail/performance.cljs`, `src/hyperopen/views/vaults/detail_vm/chart_section.cljs`, `src/hyperopen/portfolio/application/metrics_bridge.cljs`, `src/hyperopen/portfolio/metrics/catalog.cljs`.
- Request: `src/hyperopen/api/endpoints/vaults/details.cljs` (`request-vault-details!`, accepts `:priority`).
- Rate-limit signal: `[:websocket-ui :info-rate-limit :until-ms]` (`src/hyperopen/websocket/diagnostics_runtime.cljs`).
- IndexedDB boundary: `src/hyperopen/platform/indexed_db.cljs` (`vault-index-store`).
- Pacing precedent: `src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs` (serialized `/info` requests after a 429 storm).

## Plan

New code (all under the existing vault route module):

- `src/hyperopen/vaults/screener/model.cljs` — pure: option sets, metric catalog, presets, config normalization, universe selection, criterion evaluation (pass / near / fail / thin), estimate and ETA formatting.
- `src/hyperopen/vaults/screener/metrics.cljs` — pure: a vault's normalized portfolio + window → the screener metric values, through the detail page's pipeline.
- `src/hyperopen/vaults/screener/commands.cljs` — pure actions returning effect vectors.
- `src/hyperopen/vaults/screener/runner.cljs` — the paced run interpreter (dependency-injected request, cache, clock and sleep).
- `src/hyperopen/vaults/infrastructure/screener_cache.cljs` — IndexedDB per-vault history records plus a small index record.
- `src/hyperopen/vaults/application/screener_vm.cljs` — view model.
- `src/hyperopen/views/vaults/screener_view.cljs` (+ `screener_view/*.cljs`) — builder, run panel, results.
- Wiring: vault actions, collaborators, runtime registration, route runtime module effect deps, action/effect arg contracts, list view switch.

State: config at `[:vaults-ui :screener]`, view at `[:vaults-ui :view]`, run/results at `[:vaults :screener]`. Raw histories stay out of the app store (in-memory atom in the runner plus IndexedDB).

## Progress

- [x] (2026-10-16) Research: list rows, detail metrics pipeline, request policy, info-client priorities and rate-limit signal, IndexedDB boundary, action/effect registration surface.
- [x] (2026-10-16) Design canvas approved by the user.
- [x] (2026-10-16) Pure model + metrics bridge with tests (`vaults/screener/model.cljs`, `metrics.cljs`).
- [x] (2026-10-16) Runner + IndexedDB cache with tests (`vaults/screener/runner.cljs`, `vaults/infrastructure/screener_cache.cljs`).
- [x] (2026-10-16) Actions/effects wiring and contracts (8 actions, 2 effects; action specs in `schema/contracts/vault_screener_action_args.cljs`).
- [x] (2026-10-16) Views: All vaults | Screener switch, builder, run panel, results (desktop table, phone cards).
- [x] (2026-10-16) `npm run gates` 34/34 PASS.
- [x] (2026-10-16) Browser QA: `tools/playwright/test/vault-screener.spec.mjs` (2 tests) green against the worktree build; desktop and 390px screenshots reviewed; live run against Hyperliquid (41 vaults, 1y+/$100k+): estimate 1 min 22 s, actual 81 s, 41 requests, zero 429s; HLP and Citadel 6M Sharpe/Max DD on the detail page equal the screener's (3.88, 2.21 / −2.71%).
- [x] (2026-10-16) Saved views (owner request after reviewing results; design canvas artboards 6–8): "Save results…" on a finished run opens a dialog (name suggested from the criteria, passing vs passing + near misses, update-members vs fixed list). The view stores members, per-member metrics and the recipe in IndexedDB (`vault-saved-views` record). The All vaults page gains a Views row; picking a view filters the list to its members (search/Filter/Range still apply) and adds Sharpe / Max DD columns from the last refresh. The view header offers Refresh (a screener run tagged `:saved-view-id`; the runner rewrites the view and reports who joined/left), Open in Screener, Rename, Keep fixed and Delete (with confirm). Playwright covers save → filtered list → reload persistence.
- [x] (2026-10-16) Optimizer hand-off (owner request; design canvas "Vaults to Optimizer", https://claude.ai/artifact/NsxnJDs1tegHQxr7AwrbJX): "Optimize these N vaults" on a saved view and an "Optimize…" menu on finished screener results (passing / passing + near misses / top 5 by Sharpe) open a review sheet (drop vaults, goal, Vault allocation rules). Confirm runs `:actions/confirm-vault-optimizer-handoff` then `:actions/navigate "/portfolio/optimize"` in one click; the first replaces the working optimizer draft via `portfolio.optimizer.actions.vault-handoff/seed-portfolio-optimizer-from-vaults` (fresh-start reset, vault universe capped at 40, `{:kind :vault-view}` source, long-only 1.00x / 25% cap). The route's draft restore only fills an untouched draft, so it cannot clobber the seed. The optimizer universe panel shows a "Vault view" source segment and source line. Execution of vault deposits is out of scope.
- [ ] Owner review of the live screener, saved views and optimizer hand-off; PR.

## Surprises & Discoveries

- Observation: the tear-sheet engine reports no drawdown statistics at all (status `:drawdown-unavailable`) for a series that never falls, so a monotonically rising vault landed in "Not enough data". The screener now reads a never-under-water window as 0% max drawdown and 0 longest-drawdown days (`with-zero-drawdown` in `vaults/screener/metrics.cljs`).
- Observation: older vaults carry ~15–17 all-time points per 6 months (~28 per year; Growi HF 69 total, Citadel 87). A 20-point default minimum pushed 10 of 41 large vaults into "Not enough data" on the 6M window; the options are now Any / 10 / 20 with 10 the default (0 of 41 excluded live).
- Observation: optimizer actions live in the lazily loaded portfolio route module (a lazy action throws when the module is not loaded), and Nexus expands every action of one dispatch against the same state snapshot. The hand-off therefore calls the optimizer's pure seeding function from the vault action (the optimizer actions namespace is already in the main bundle via navigation) and relies on the restore-only-when-untouched guard rather than on the navigate action seeing the new draft.
- Observation: a saved-view refresh reuses the screener's single run slot, so refreshing a view replaces whatever screener run was showing; the Screener tab then shows the refresh run (flagged stale if its universe differs from the builder).
- Observation: run state lives in the app store only, so a page reload drops the run panel; the per-vault histories survive in IndexedDB, so re-running is instant.

- Observation: the vault index (`stats-data.hyperliquid.xyz/Mainnet/vaults`, ~14 MB, 9,477 rows) has 11–14 PnL-only points per range; `normalize-vault-snapshot-preview` further keeps 8.
  Evidence: live payload inspection on 2026-10-16.
- Observation: `vaultDetails` returns day/week/month/allTime (+ perp variants) only, ~14–61 points each, ~28 KB per vault, ~0.33 s. 3M/6M/1Y windows are derived from allTime, exactly as the detail page does.
- Observation: open non-child vaults by (age ≥, TVL ≥): (180d, $10k) = 207, (0, $1k) = 502, (365d, $100k) = 41.

## Decision Log

- Decision: pace one `vaultDetails` request every 2 s at `:low` info-client priority, serialized.
  Rationale: about half of the per-IP `/info` budget (≈20 weight per call against ≈1,200/min), leaving trading and live data unaffected; parallel `/info` bursts have produced 429 storms before (optimizer history client).
  Date/Author: 2026-10-16 / Claude
- Decision: while the info client reports a rate-limit cooldown, wait it out and double the interval (cap 8 s) for the rest of the run.
  Date/Author: 2026-10-16 / Claude
- Decision: cache each vault's day/week/month/all-time portfolio in IndexedDB for 6 hours; a re-run or window change only fetches stale vaults.
  Date/Author: 2026-10-16 / Claude
- Decision: thresholds are evaluated in the view model, so editing them after a run needs no fetch; window or universe changes mark results stale and offer a re-run that is instant for cached vaults.
  Date/Author: 2026-10-16 / Claude
- Decision: "Not enough history" = fewer than the chosen minimum of return points in the window, or a criterion metric suppressed by the tear-sheet quality gates (nil value). Low-confidence values still rank but are marked.
  Date/Author: 2026-10-16 / Claude

## Validation and Acceptance

- `npm run gates` passes (check, test, test:websocket).
- Unit tests: universe counts per config; criterion evaluation (pass, near miss within 10%, fail, thin); percent unit conversion (Max DD 25 → -0.25 decimal bound); metrics bridge equals `performance-metrics-model` on the same details; runner paces requests, skips fresh cache, stops on pause/cancel, waits out a cooldown; commands emit projection before effects.
- Playwright: switch to Screener, the universe count changes with age/TVL controls, a run with stubbed `vaultDetails` produces results grouped into Pass/Fail.
- Visual review against the design canvas at desktop and phone widths.

## Outcomes & Retrospective

Shipped in this branch (not yet committed): the Screener tab on `/vaults` with free universe narrowing, ten tear-sheet criteria, a cache-aware estimate, a paced low-priority background run with pause/resume/cancel and rate-limit backoff, and Pass / Near miss / Fail / Not enough data results whose thresholds re-filter without refetching. Remaining: owner review, and possible follow-ups (persist the screener config in localStorage, a `?view=screener` deep link, persisting the last run's results across reloads).
