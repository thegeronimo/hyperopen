# Vaults Page Parity with Hyperliquid (Data + Presentation)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, `/vaults` in Hyperopen will present vault data with the same core information hierarchy and interaction model users expect from Hyperliquid: searchable vault list with `TVL/APR/Your Deposit/Age/Snapshot`, protocol-vs-user grouping, and vault detail pages with performance tabs and account activity context. Users will be able to discover vaults, inspect performance, and understand their own vault exposure from one place without opening external tools.

A user can verify this by opening `/vaults`, filtering/searching, opening a vault detail page, and confirming the same major layout blocks and data semantics seen on `https://app.hyperliquid.xyz/vaults`.

## Progress

- [x] (2026-02-26 16:53Z) Reviewed planning and architecture constraints: `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, `/hyperopen/docs/agent-guides/trading-ui-policy.md`.
- [x] (2026-02-26 16:53Z) Audited current Hyperopen routing, portfolio/account data pipelines, and relevant view/state modules (`/hyperopen/src/hyperopen/views/app_view.cljs`, `/hyperopen/src/hyperopen/views/header_view.cljs`, `/hyperopen/src/hyperopen/api/endpoints/account.cljs`, `/hyperopen/src/hyperopen/startup/collaborators.cljs`, `/hyperopen/src/hyperopen/state/app_defaults.cljs`).
- [x] (2026-02-26 16:53Z) Reconstructed live Hyperliquid Vaults list/detail behavior from production bundles (`/static/js/7445.80ed9d41.chunk.js`, `/static/js/4114.d9696726.chunk.js`) and live API responses.
- [x] (2026-02-26 16:53Z) Validated live data acquisition paths for vaults: stats-data list and `info` endpoint methods (`vaultSummaries`, `userVaultEquities`, `vaultDetails`, `webData2`).
- [x] (2026-02-26 16:53Z) Authored this ExecPlan.
- [x] (2026-02-26 17:14Z) Implemented vault API endpoint/gateway layer and default/instance exports:
  - Added `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs` with stats-data fetch, info endpoint requests (`vaultSummaries`, `userVaultEquities`, `vaultDetails`, `webData2`), and normalization helpers (`merge-vault-index-with-summaries`, tuple portfolio normalization reuse, relationship parsing).
  - Added `/hyperopen/src/hyperopen/api/gateway/vaults.cljs`.
  - Extended `/hyperopen/src/hyperopen/api/instance.cljs` and `/hyperopen/src/hyperopen/api/default.cljs` with vault request ops.
- [x] (2026-02-26 17:14Z) Implemented vault state defaults and projection helpers:
  - Added `:vaults-ui` and `:vaults` branches in `/hyperopen/src/hyperopen/state/app_defaults.cljs`.
  - Added vault projection transitions in `/hyperopen/src/hyperopen/api/projections.cljs` for list/summaries/equities/detail/webdata loading, success, and error flows.
  - Added/updated tests:
    - `/hyperopen/test/hyperopen/state/app_defaults_test.cljs`
    - `/hyperopen/test/hyperopen/api/projections_test.cljs`
- [ ] Implement vault fetch orchestration and action wiring.
- [ ] Implement `/vaults` list page (desktop + mobile parity structure).
- [ ] Implement `/vaults/:vaultAddress` detail page (overview/performance blocks and account context).
- [ ] Add route/nav integration and state/action wiring.
- [x] (2026-02-26 17:14Z) Added/updated API tests:
  - New: `/hyperopen/test/hyperopen/api/endpoints/vaults_test.cljs`
  - New: `/hyperopen/test/hyperopen/api/gateway/vaults_test.cljs`
  - Updated: `/hyperopen/test/hyperopen/api/instance_test.cljs`
  - Updated: `/hyperopen/test/hyperopen/api/default_test.cljs`
- [ ] Add tests for VM derivation, route rendering, and view interactions.
- [x] (2026-02-26 17:22Z) Ran required validation gates successfully:
  - `npm run check`
  - `npm test`
  - `npm run test:websocket`

## Surprises & Discoveries

- Observation: `info` `vaultSummaries` is not a full vault universe feed; it only returns very recent vaults (often empty).
  Evidence: Live response from `POST https://api.hyperliquid.xyz/info` with `{"type":"vaultSummaries"}` returned `[]` during this research window.

- Observation: Hyperliquid Vaults list depends on a separate stats endpoint for full coverage.
  Evidence: Production bundle `main.e8bac9f7.js` exports a helper used as `gwk("vaults")`, resolved to `https://stats-data.hyperliquid.xyz/<chain>/vaults`; live `GET https://stats-data.hyperliquid.xyz/Mainnet/vaults` returned 9k+ rows.

- Observation: Vault list combines two sources: full stats-data list plus recent `vaultSummaries` rows newer than max `createTimeMillis` in the full list.
  Evidence: `/static/js/7445.80ed9d41.chunk.js` list logic computes `max(createTimeMillis)` from full list, then conditionally appends `vaultSummaries` rows above that threshold.

- Observation: `vaultDetails.portfolio` payload shape is tuple-array, not keyed map.
  Evidence: Live `vaultDetails` response returns `[["day", {...}], ["week", {...}], ...]`; Hyperliquid normalizes this before chart/stat usage.

- Observation: Parent/child vault relationships materially affect detail rendering and data fetch strategy.
  Evidence: `relationship.type` in live data includes `parent`, `child`, and `normal`; `/static/js/4114...` conditionally fetches `webData2` for parent-context handling and renders child strategy links under parent vaults.

## Decision Log

- Decision: Use `stats-data` as canonical list source, then merge `vaultSummaries` as recency patch.
  Rationale: This matches live Hyperliquid behavior and avoids empty/partial lists when `vaultSummaries` has no recent entries.
  Date/Author: 2026-02-26 / Codex

- Decision: Scope this plan to data + presentation parity first; transactional vault actions are wired only where existing signing/runtime seams are stable.
  Rationale: User request prioritizes obtaining and presenting vault data with similar layout. Full vault action parity can be layered without blocking read parity.
  Date/Author: 2026-02-26 / Codex

- Decision: Keep normalization and derivation pure in VM/domain-style helpers; place all network side effects in API gateway/startup/effect boundaries.
  Rationale: Maintains architecture rules in `/hyperopen/ARCHITECTURE.md` and keeps websocket/runtime decisions deterministic.
  Date/Author: 2026-02-26 / Codex

## Outcomes & Retrospective

Milestone 1 is complete, and Milestone 2 is partially complete. Vault data ingress/normalization plus vault state defaults/projections are implemented and covered by tests. Current verification command is passing: `npm run test:runner:generate && npx shadow-cljs compile test && node out/test.js`.

## Context and Orientation

Hyperopen currently renders only `/trade` and `/portfolio` in `/hyperopen/src/hyperopen/views/app_view.cljs`. Header navigation already includes a `Vaults` link in `/hyperopen/src/hyperopen/views/header_view.cljs`, but it is hardcoded inactive and route handling for `/vaults` is not implemented.

Current API plumbing supports many `info` endpoint methods through `/hyperopen/src/hyperopen/api/endpoints/account.cljs`, `/hyperopen/src/hyperopen/api/gateway/account.cljs`, `/hyperopen/src/hyperopen/api/instance.cljs`, and `/hyperopen/src/hyperopen/api/default.cljs`. Vault-specific read endpoints now exist in `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs` and `/hyperopen/src/hyperopen/api/gateway/vaults.cljs`.

Relevant live Hyperliquid data sources and contracts for this feature are:

1. Full vault list: `GET https://stats-data.hyperliquid.xyz/Mainnet/vaults`.
   Returned row shape matches list-page needs: `{apr, pnls, summary}` where `summary` contains `name`, `vaultAddress`, `leader`, `tvl`, `isClosed`, `relationship`, `createTimeMillis`.

2. Recent vault append feed: `POST https://api.hyperliquid.xyz/info` with `{ "type": "vaultSummaries" }`.
   This is a recency-only feed (not full coverage).

3. User deposit mapping: `POST .../info` with `{ "type": "userVaultEquities", "user": <address> }`.
   Provides `{vaultAddress, equity, lockedUntilTimestamp}` for `Your Deposit` semantics.

4. Vault detail payload: `POST .../info` with `{ "type": "vaultDetails", "vaultAddress": <vault>, "user": <optional-address> }`.
   Returns metadata (`allowDeposits`, `alwaysCloseOnWithdraw`, followers, commission, relationship) and portfolio tuple array.

5. Vault webdata fallback: `POST .../info` with `{ "type": "webData2", "user": <vaultAddress> }`.
   Used by Hyperliquid detail route when rendering activity/positions context for vault addresses.

This plan uses the term `vault index row` to mean one normalized list entry suitable for sorting/filtering/rendering. It uses `portfolio tuple normalization` to mean transforming tuple arrays (`[["day", payload], ...]`) into keyword-map keys (`:day`, `:week`, `:month`, `:all-time`) used by view-model selectors.

## Plan of Work

### Milestone 1: Add Vault Data Endpoints and Normalizers

Implement additive API modules for vault reads and keep all external schema adaptation in endpoint/gateway layers.

Create `/hyperopen/src/hyperopen/api/endpoints/vaults.cljs` with:

- `request-vault-index!` calling `https://stats-data.hyperliquid.xyz/Mainnet/vaults` via `js/fetch` (or injected fetch fn), validating array fallback to `[]`.
- `request-vault-summaries!` posting `{"type":"vaultSummaries"}` through existing `post-info!`.
- `request-user-vault-equities!` posting `{"type":"userVaultEquities","user":...}`.
- `request-vault-details!` posting `{"type":"vaultDetails","vaultAddress":...,"user":...}`.
- `request-vault-webdata2!` posting `{"type":"webData2","user":vaultAddress}`.
- Normalizers for list rows and detail portfolio tuple arrays.

Create `/hyperopen/src/hyperopen/api/gateway/vaults.cljs` to expose stable call signatures and compose these endpoint functions.

Extend `/hyperopen/src/hyperopen/api/instance.cljs` and `/hyperopen/src/hyperopen/api/default.cljs` with new vault request ops. Keep existing public API functions backward-compatible.

### Milestone 2: Add Vault Projection State and Fetch Orchestration

Extend `/hyperopen/src/hyperopen/state/app_defaults.cljs` with two new state branches:

- `:vaults-ui` for view controls: search query, filter toggles (`leading/deposited/others/closed`), snapshot range (`:day/:week/:month/:all-time`), sort state, list/detail loading toggles, selected detail tab.
- `:vaults` for data/cache: `:index-rows`, `:recent-summaries`, `:user-equities`, `:details-by-address`, `:webdata-by-vault`, `:errors`, loaded timestamps.

Add projection helpers in `/hyperopen/src/hyperopen/api/projections.cljs` to set loading/error/success states for each fetch path.

Implement orchestration in a dedicated action/effect module (`/hyperopen/src/hyperopen/vaults/actions.cljs` plus adapter wiring) so route entry triggers deterministic fetch order:

1. Apply route/UI projection state first.
2. Trigger vault index + summaries fetch (parallel).
3. If wallet connected, trigger user equities.
4. On detail route, fetch `vaultDetails` (with optional user) and vault `webData2` when needed.

If any heavy effects are introduced as registered `:effects/*` IDs, add matching policy entries in `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs` so projection writes precede network work.

### Milestone 3: Route and Navigation Integration

Update `/hyperopen/src/hyperopen/views/app_view.cljs` so:

- `"/vaults"` renders a new list view component.
- `"/vaults/:vaultAddress"` renders detail view.
- Existing `/trade` and `/portfolio` behavior remains unchanged.

Update `/hyperopen/src/hyperopen/views/header_view.cljs` to mark `Vaults` active when route starts with `/vaults`.

Add robust vault-address parser/validator helper in new `vaults` view model namespace; reject invalid addresses with deterministic empty/error state instead of runtime exceptions.

### Milestone 4: Implement `/vaults` List View Parity Blocks

Create:

- `/hyperopen/src/hyperopen/views/vaults_view.cljs`
- `/hyperopen/src/hyperopen/views/vaults/vm.cljs`

Implement list parity structure based on extracted Hyperliquid behavior:

1. Header row: page title `Vaults`, action slot (create vault CTA can be hidden/disabled if transaction scope is deferred).
2. `Total Value Locked` card computed as sum of visible protocol+user vault TVL.
3. Filter toolbar: search box (search `name`, `leader`, `vaultAddress`), multi-select toggles (`Leading`, `Deposited`, `Others`, `Closed`), snapshot-range selector (`day/week/month/all-time`).
4. Two sections: `Protocol Vaults` and `User Vaults`.
5. Desktop table columns and widths aligned with live app semantics:
   - Vault (24%), Leader (16%), APR (12%), TVL (12%), Your Deposit (12%), Age (12%), Snapshot (12%).
6. Mobile card list with row-style key/value rendering and pagination controls.
7. Row click navigates to `/vaults/<vaultAddress>`.

Keep rendering deterministic and avoid duplicate sorting work by memoizing normalized rows with stable cache keys.

### Milestone 5: Implement `/vaults/:vaultAddress` Detail View Parity Blocks

Create:

- `/hyperopen/src/hyperopen/views/vault_detail_view.cljs`
- `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`

Implement detail parity structure:

1. Breadcrumb/header block with vault name/address and optional parent link for child vaults.
2. Primary actions row (`Deposit`, `Withdraw`; leader menu slot). If transactional scope is deferred, render disabled states with explicit `Coming soon` copy while preserving layout.
3. Four metric cards:
   - TVL, Past Month Return (APR), Your Deposits, All-time Earned.
4. Performance area:
   - Left: about/performance tabs (`About`, `Vault Performance`, `Your Performance`) with description/leader/profit-share/max-drawdown/volume semantics.
   - Right: portfolio chart for selected range.
5. Account activity panel using normalized vault `webData2` (and/or current active webdata when viewing self vault address) rendered through existing account-info table primitives.

Normalize `vaultDetails.portfolio` tuple arrays before any chart/stat derivation. Handle `relationship.type` cases explicitly (`normal`, `parent`, `child`) with deterministic fallback for unknown variants.

### Milestone 6: Optional Transactional Action Wiring (If Included in This Delivery)

If feature scope includes live vault actions, wire existing signing path in `/hyperopen/src/hyperopen/api/trading.cljs` to support:

- `createVault`
- `vaultTransfer` (deposit/withdraw)
- `vaultModify` (allowDeposits/alwaysCloseOnWithdraw)
- `vaultDistribute` (including close-on-zero semantics)

Use existing action signing boundaries; do not modify hash/signing core behavior unless strictly required.

If transactional scope is excluded for first ship, keep UI affordances present but non-destructive and documented as deferred in this plan.

### Milestone 7: Tests and Validation

Add focused tests:

- API endpoint normalization tests for stats-data rows, tuple normalization, and empty/error fallbacks.
- VM tests for list grouping/filter/sort/snapshot-range behavior.
- View tests for list columns, section headers, route-specific rendering, and detail metric blocks.
- Route/nav tests in app/header suites to assert `/vaults` activation and detail route dispatch.

Run required repository gates and capture pass/fail evidence in this plan.

## Concrete Steps

1. Implement vault API endpoint/gateway modules and add API instance/default exports.

   cd /hyperopen
   npm test

   Expected result: tests pass with new endpoint unit coverage; no regression in existing account/portfolio calls.

2. Add vault state defaults, projections, and action wiring (including registry/schema entries).

   cd /hyperopen
   npm test

   Expected result: action registration and spec-validation tests pass with new `:actions/*vault*` ids.

3. Implement `/vaults` list view + VM and integrate route/nav.

   cd /hyperopen
   npm test

   Expected result: list route renders and snapshot/search/filter interactions are covered by view/vm tests.

4. Implement `/vaults/:vaultAddress` detail view + VM.

   cd /hyperopen
   npm test

   Expected result: detail route renders deterministic metric/performance blocks for fixture payloads.

5. Run required validation gates.

   cd /hyperopen
   npm run check
   npm test
   npm run test:websocket

   Expected result: all commands exit 0.

## Validation and Acceptance

Acceptance is met when all of the following are true:

1. Navigating to `/vaults` renders a dedicated vaults page (not trade fallback).
2. Vault list rows populate from live/fetched vault index data and include columns: `Vault`, `Leader`, `APR`, `TVL`, `Your Deposit`, `Age`, `Snapshot`.
3. Search and list filter toggles (`Leading`, `Deposited`, `Others`, `Closed`) deterministically change visible rows.
4. `Total Value Locked` card updates from the rendered vault dataset.
5. Clicking a row navigates to `/vaults/<address>` and loads vault detail content.
6. Detail page shows about/performance blocks and portfolio chart data derived from normalized `vaultDetails.portfolio` tuples.
7. If wallet is connected and has vault equities, `Your Deposit` and detail personal metrics reflect those values.
8. Required gates pass: `npm run check`, `npm test`, `npm run test:websocket`.

Manual parity spot-check:

- Open Hyperliquid Vaults and Hyperopen Vaults side-by-side.
- Verify same major section ordering and column semantics on desktop.
- Verify mobile card rendering preserves the same field ordering and click-through behavior.

## Idempotence and Recovery

All plan steps are additive and safe to rerun.

If stats-data endpoint fetch fails, fallback behavior should keep page functional:

- Show deterministic empty state for list sections.
- Preserve search/filter controls.
- Surface recoverable error messaging without route breakage.

If detail fetch fails for one vault, keep list page usable and allow navigation back without stale global error state.

If transactional actions are enabled and any action returns API error, preserve read-only vault data and show explicit error toast; do not clear vault caches.

## Artifacts and Notes

Live parity evidence used in this plan:

- Hyperliquid app shell: `https://app.hyperliquid.xyz/vaults`
- Hyperliquid bundle route chunks:
  - `/static/js/main.e8bac9f7.js`
  - `/static/js/7445.80ed9d41.chunk.js` (vault list route)
  - `/static/js/4114.d9696726.chunk.js` (vault detail route)
- Data endpoints:
  - `GET https://stats-data.hyperliquid.xyz/Mainnet/vaults`
  - `POST https://api.hyperliquid.xyz/info` with `vaultSummaries`, `userVaultEquities`, `vaultDetails`, `webData2`

Reference contracts:

- Info endpoint docs (`vaultDetails`, `userVaultEquities`):
  - `https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint`
- Exchange endpoint docs (`vaultTransfer`):
  - `https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint`

## Interfaces and Dependencies

No new third-party dependency is required.

New interfaces expected after implementation:

- `hyperopen.api.endpoints.vaults/request-vault-index!`
- `hyperopen.api.endpoints.vaults/request-vault-summaries!`
- `hyperopen.api.endpoints.vaults/request-user-vault-equities!`
- `hyperopen.api.endpoints.vaults/request-vault-details!`
- `hyperopen.api.endpoints.vaults/request-vault-webdata2!`
- `hyperopen.views.vaults.vm/vaults-vm`
- `hyperopen.views.vaults.detail-vm/vault-detail-vm`
- `hyperopen.views.vaults-view/vaults-view`
- `hyperopen.views.vault-detail-view/vault-detail-view`

Stable interfaces that must remain unchanged:

- Existing websocket client seam in `/hyperopen/src/hyperopen/websocket/client.cljs`.
- Existing order signing payload rules in `/hyperopen/src/hyperopen/utils/hl_signing.cljs`.
- Existing trade and portfolio route behavior in `/hyperopen/src/hyperopen/views/app_view.cljs`.

Plan revision note: 2026-02-26 16:53Z - Initial plan authored from live Hyperliquid Vaults bundle/API reconstruction and local Hyperopen architecture audit; selected stats-data + info-endpoint hybrid as canonical ingestion strategy for list/detail parity.
