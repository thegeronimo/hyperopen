# Vault List Preview And Benchmark Split

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, Hyperopen should stop treating the large vault list payload as both a list-preview feed and a benchmark-history feed. A user opening `/portfolio` or `/vaults/<vault>` should no longer pay the `GET https://stats-data.hyperliquid.xyz/Mainnet/vaults` cost unless the vault benchmark UI actually needs vault metadata, while `/vaults` should keep the same list behavior using compact preview data instead of full per-range PnL arrays in app state.

The user-visible proof is straightforward. `/vaults` still renders the same snapshot sparklines and snapshot sorting behavior, but the normalized list rows only carry preview data. `/portfolio` and `/vaults/<vault>` still support vault benchmarks, but benchmark lines come from lazy-fetched vault detail history instead of piggybacking on list-row `:snapshot-by-key` data.

## Progress

- [x] (2026-03-18 01:50Z) Re-read `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and `/hyperopen/docs/MULTI_AGENT.md`, then re-audited the current vault route loading, endpoint normalization, and benchmark consumers.
- [x] (2026-03-18 01:50Z) Created and claimed `bd` issue `hyperopen-2piu` for this work as a follow-up to `hyperopen-r350`.
- [x] (2026-03-18 01:51Z) Confirmed the live API contract: `GET /Mainnet/vaults` is `13,937,470` bytes and `vaultSummaries` is a small recent-only patch feed with no `pnls`, so client-only state slimming reduces heap and downstream work but does not by itself reduce transferred bytes.
- [x] (2026-03-18 03:41Z) Implemented compact `:snapshot-preview-by-key` normalization, updated `/vaults` list readers to use preview series plus last values, and kept detail fallback readers compatible with preview data while preserving limited legacy test fallback support.
- [x] (2026-03-18 03:41Z) Moved vault benchmark history off list rows by introducing benchmark-only vault detail fetches, benchmark detail projections/cache, and portfolio/detail benchmark computations that read `vaultDetails.portfolio` instead of list-row `:snapshot-by-key`.
- [x] (2026-03-18 03:41Z) Deferred vault index bootstrap on `/portfolio` and `/vaults/<vault>` so non-list routes fetch vault metadata only when the vault benchmark selector opens or persisted vault benchmark selections already require it.
- [x] (2026-03-18 03:41Z) Extended regression coverage for endpoint normalization, route loading, benchmark selection, benchmark charting, and cache invalidation; passed `npm test`, `npm run test:websocket`, and `npm run check`.

## Surprises & Discoveries

- Observation: the list payload itself is the dominant transfer, but only the actual `/vaults` list route needs its preview semantics on first render.
  Evidence: live `curl -I https://stats-data.hyperliquid.xyz/Mainnet/vaults` returned `Content-Length: 13937470`, while `POST https://api.hyperliquid.xyz/info` with `{"type":"vaultSummaries"}` returned a tiny recent-only feed.

- Observation: `:snapshot-by-key` from normalized list rows is read by four production surfaces, but only one of them is truly list-preview behavior.
  Evidence: the current reads are in `/hyperopen/src/hyperopen/views/vaults/vm.cljs`, `/hyperopen/src/hyperopen/vaults/detail/performance.cljs`, `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs`, and `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`.

- Observation: `vaultDetails.portfolio` is rich enough to replace list-row snapshot arrays for vault benchmark charts and metrics.
  Evidence: live `vaultDetails` for `0xdfc24b077bc1425ad1dea75bcb6f8158e10df303` returned `portfolio.month.accountValueHistory` and `portfolio.month.pnlHistory` with full time-series rows, plus the same `day/week/month/allTime` families already normalized by `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs`.

- Observation: once vault benchmarks moved to lazy-fetched benchmark-detail history, the existing detail and portfolio benchmark caches no longer invalidated when benchmark details arrived.
  Evidence: after the initial implementation, vault benchmark overlay tests stayed empty until the cache keys in `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` were expanded to include `:benchmark-details-by-address` and `:details-by-address`.

## Decision Log

- Decision: split preview data into an explicit list-only field instead of overloading `:snapshot-by-key` with sampled values.
  Rationale: list preview needs only short sparkline series plus a latest value, while portfolio/detail vault benchmarks need benchmark-grade history. Reusing one field for both would silently degrade analytics.
  Date/Author: 2026-03-18 / Codex

- Decision: use lazy benchmark detail fetches for selected vault benchmarks instead of keeping full list-row PnL arrays in store.
  Rationale: `vaultDetails.portfolio` already carries richer history than the list payload and can be loaded only for the vault benchmarks the user actually selected.
  Date/Author: 2026-03-18 / Codex

- Decision: defer vault index loading on non-list routes until benchmark UI state needs it.
  Rationale: `/portfolio` and `/vaults/<vault>` do not need the full vault universe on first render unless the user opens the vault benchmark selector or persisted vault benchmark state is already active.
  Date/Author: 2026-03-18 / Codex

## Outcomes & Retrospective

The implementation landed as planned. `/vaults` now normalizes preview-only snapshot data, while portfolio/detail vault benchmarks use lazily fetched benchmark detail history. Route loading on `/portfolio` and `/vaults/<vault>` no longer pays the 13.9 MB vault index cost by default, and benchmark-specific follow-up fetches are limited to the vault metadata and benchmark details actually needed by current UI state.

Validation also surfaced a real regression in benchmark cache invalidation after the history source moved off list rows. Fixing the cache keys in the detail and portfolio VMs ensured vault benchmark overlays re-render when benchmark details arrive asynchronously. Final validation passed with `npm test`, `npm run test:websocket`, and `npm run check`.

## Context and Orientation

The current list payload enters through `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs`. `request-vault-index!` fetches `https://stats-data.hyperliquid.xyz/Mainnet/vaults`, and `normalize-vault-index-row` currently turns each row into metadata plus `:snapshot-by-key`, which is a map from snapshot range to full PnL vectors. In this repository, “snapshot range” means one of the normalized list/detail time buckets such as `:day`, `:week`, `:month`, or `:all-time`.

The `/vaults` list VM in `/hyperopen/src/hyperopen/views/vaults/vm.cljs` uses those arrays only to build a small sparkline and a last snapshot value for sorting/display. The detail fallback in `/hyperopen/src/hyperopen/vaults/detail/performance.cljs` only needs a last value when the detail `:portfolio` summary is absent. The analytic consumers are `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`, which currently reuse list-row `:snapshot-by-key` as benchmark input.

Route bootstrap logic lives in `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs`. Today it calls `load-vault-list-effects` for `/vaults`, `/vaults/<vault>`, and `/portfolio`, so the large list payload is fetched even when the user never opens the vault benchmark UI. Portfolio benchmark selection lives in `/hyperopen/src/hyperopen/portfolio/actions.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`. Vault detail benchmark selection lives in `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs` and `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs`.

## Plan of Work

First, update `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs` so normalized list rows carry a compact preview field, not the full PnL arrays. The new field should remain keyed by effective snapshot ranges so `/vaults` keeps its range selector behavior. Each entry should hold a short preview series and a latest value already normalized into percentage terms using the row TVL when needed. Update `/hyperopen/src/hyperopen/views/vaults/vm.cljs` and `/hyperopen/src/hyperopen/vaults/detail/performance.cljs` to read that preview field, while keeping legacy fallback support for existing test fixtures where reasonable.

Second, add a separate benchmark-detail fetch path that stores normalized public vault details for benchmark-only use without driving the primary vault detail route loading flag. Wire that through `/hyperopen/src/hyperopen/vaults/effects.cljs`, `/hyperopen/src/hyperopen/runtime/effect_adapters/vaults.cljs`, `/hyperopen/src/hyperopen/app/effects.cljs`, `/hyperopen/src/hyperopen/schema/contracts.cljs`, `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`, `/hyperopen/src/hyperopen/state/app_defaults.cljs`, and `/hyperopen/src/hyperopen/api/projections.cljs`.

Third, update `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs` so selector options still come from `:merged-index-rows`, but benchmark series come from benchmark-detail `:portfolio` history when a vault benchmark is selected. Add route/action helpers so `/portfolio` and `/vaults/<vault>` fetch list metadata only when the vault benchmark selector is opened or persisted vault benchmark state already references a vault, and fetch benchmark details only for the selected vault benchmarks that are still missing.

Finally, extend the focused vault and portfolio regressions, then run the required repository gates.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/556b/hyperopen`.

Targeted test commands while iterating:

    npm test -- hyperopen.api.endpoints.vaults-test hyperopen.vaults.application.route-loading-test hyperopen.vaults.actions-test hyperopen.portfolio.actions-test hyperopen.views.vaults.vm-test hyperopen.views.vaults.detail-vm-test hyperopen.vaults.detail.benchmarks-test hyperopen.views.portfolio.vm.benchmarks-test

Required repository gates before completion:

    npm run check
    npm test
    npm run test:websocket

Issue completion command after acceptance:

    bd close hyperopen-2piu --reason "Completed" --json

## Validation and Acceptance

Acceptance is behavioral.

On `/vaults`, the list still renders snapshot sparklines and snapshot sorting for all supported ranges, but the normalized index rows expose preview-only snapshot data rather than full PnL arrays.

On `/portfolio` and `/vaults/<vault>`, opening the route with no vault benchmark UI state should no longer bootstrap the vault index. Opening the vault benchmark selector or restoring persisted vault benchmark selections should fetch the needed list metadata, and selecting a vault benchmark should fetch benchmark details so the chart and performance metrics still render vault benchmark lines.

The final repository gates must pass:

- `npm run check`
- `npm test`
- `npm run test:websocket`

## Idempotence and Recovery

This change is additive and safe to re-run. The new preview field and benchmark-detail cache are derived from remote reads and can be recomputed by reloading the app. If any benchmark regression appears, the safest rollback is to restore the previous benchmark source selection before adjusting preview sampling. Keep follow-up work in `bd`; do not create parallel markdown trackers.

## Artifacts and Notes

Tracking artifacts created for this work:

    bd issue: hyperopen-2piu
    completed ExecPlan: /hyperopen/docs/exec-plans/completed/2026-03-18-vault-list-preview-benchmark-split.md

Live API evidence captured during research:

    GET https://stats-data.hyperliquid.xyz/Mainnet/vaults
      Content-Length: 13937470

    POST https://api.hyperliquid.xyz/info {"type":"vaultSummaries"}
      returned a recent-only patch row and no pnls

## Interfaces and Dependencies

The main implementation surfaces are:

- `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs`
- `/hyperopen/src/hyperopen/api/projections.cljs`
- `/hyperopen/src/hyperopen/vaults/effects.cljs`
- `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs`
- `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs`
- `/hyperopen/src/hyperopen/portfolio/actions.cljs`
- `/hyperopen/src/hyperopen/views/vaults/vm.cljs`
- `/hyperopen/src/hyperopen/vaults/detail/performance.cljs`
- `/hyperopen/src/hyperopen/vaults/detail/benchmarks.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm/benchmarks.cljs`

The new route/action behavior should continue using existing `request-vault-index!`, `request-vault-summaries!`, and `request-vault-details!` API boundaries. The new benchmark-detail fetch path should not mutate the primary route detail loading UI flag for unrelated benchmark vault addresses.

Plan revision note: 2026-03-18 01:51Z - Initial active ExecPlan created after live API verification and consumer audit so implementation can proceed against a specific split between list previews, lazy list metadata, and benchmark detail history.
