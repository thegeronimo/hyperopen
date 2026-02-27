# Vault Detail Data Model and Timeframe Parity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained under `/hyperopen/.agents/PLANS.md` and follows that contract.

## Purpose / Big Picture

The vault detail page currently diverges from Hyperliquid in how chart timeframe selection is exposed and how tab data is modeled when Hyperliquid sources data from mixed transports (HTTP + websocket). After this change, a user can select vault chart timeframe directly from a dropdown on the detail chart (matching Hyperliquid behavior), and the vault detail data model accepts the same payload families Hyperliquid uses (`vaultDetails`, `webData2`, and websocket user-history channel shapes) so the tables render robustly from canonical payloads.

A user should be able to verify this by opening `/vaults/<address>`, changing chart timeframe (24H/7D/30D/All-time), and seeing chart data update while activity tabs continue to populate from normalized data.

## Progress

- [x] (2026-02-27 02:36Z) Captured Hyperliquid `POST /info` payload types for the vault detail route and validated initial request model (`vaultDetails`, `webData2`) using CDP request postData capture.
- [x] (2026-02-27 02:36Z) Captured websocket subscription model while tab-switching and confirmed channel-level sources (`userFills`, `userFundings`, `userHistoricalOrders`, `userNonFundingLedgerUpdates`, `userTwapHistory`, `userTwapSliceFills`) plus base account channels.
- [x] (2026-02-27 02:36Z) Wrote evidence artifacts to `tmp/hyperliquid-network/2026-02-27/` including request/response payload snapshots and websocket channel samples.
- [x] (2026-02-27 02:41Z) Implemented vault detail chart timeframe dropdown UI and wired it to `:actions/set-vaults-snapshot-range`.
- [x] (2026-02-27 02:41Z) Extended vault detail VM normalization to accept Hyperliquid websocket-shaped payload rows (nested `:fills`, `:fundings`, `:orderHistory`, `:nonFundingLedgerUpdates`, `:orders`, `:states`) in addition to existing API shapes.
- [x] (2026-02-27 02:41Z) Updated vault detail load/data aggregation logic to include component strategy addresses (from relationship child vaults) for fills/funding/order-history parity.
- [x] (2026-02-27 02:41Z) Added/adjusted tests for actions, VM, and view.
- [x] (2026-02-27 02:41Z) Ran `npm run check`, `npm test`, and `npm run test:websocket`.

## Surprises & Discoveries

- Observation: On the vault detail route, tab switches did not generate additional `POST /info` calls; only `vaultDetails` and `webData2` were posted.
  Evidence: CDP capture with forced clicks across Balances -> Depositors produced exactly 8 POSTs total with fixed type set.

- Observation: Hyperliquid still sources tab-history streams, but through websocket subscriptions instead of per-tab HTTP calls.
  Evidence: websocket sent frames included `subscribe` for `userFills`, `userFundings`, `userHistoricalOrders`, `userNonFundingLedgerUpdates`, `userTwapHistory`, and `userTwapSliceFills` during vault detail interaction.

- Observation: For the sampled HLP vault, fills/funding/order/twap-history websocket subscriptions targeted component strategy vault addresses, while non-funding ledger targeted the parent vault address.
  Evidence: `tmp/hyperliquid-network/2026-02-27/ws-channel-samples.json` showed user sets for channels split across child vault addresses and the parent.

## Decision Log

- Decision: Implement parity in this pass by strengthening data normalization and component-address aggregation in vault detail API fetch paths, rather than introducing a new vault-specific websocket subscription runtime.
  Rationale: This provides immediate functional parity improvements with low architectural risk and without introducing a new long-lived subscription lifecycle in this iteration.
  Date/Author: 2026-02-27 / Codex

- Decision: Use existing vault snapshot range (`:day`, `:week`, `:month`, `:all-time`) for the chart timeframe dropdown.
  Rationale: The detail VM already uses snapshot range to choose chart series windows from `vaultDetails.portfolio`; reusing this state avoids duplicated controls and keeps behavior deterministic.
  Date/Author: 2026-02-27 / Codex

- Decision: Reuse existing address-keyed vault history caches and extend `load-vault-detail` to enqueue child-address history fetches, instead of adding a new vault-only cache schema.
  Rationale: Existing projections already store history per address and VM aggregation can combine parent+child safely with minimal architectural churn.
  Date/Author: 2026-02-27 / Codex

## Outcomes & Retrospective

Completed. The vault detail chart now has a user-facing timeframe dropdown (24H/7D/30D/All-time) wired to the existing snapshot-range state, and vault detail history models now accept both API and websocket-shaped payload wrappers. Parent vaults now fetch and aggregate fills/funding/order-history across known child strategy addresses, matching the observed Hyperliquid parent-vault data sourcing model more closely.

Validation passed on all required gates (`npm run check`, `npm test`, `npm run test:websocket`). Remaining parity work, if needed, would be to introduce explicit vault-detail websocket lifecycle management rather than relying on API refresh paths.

## Context and Orientation

The vault detail surface is rendered by `/hyperopen/src/hyperopen/views/vault_detail_view.cljs` and receives its data from `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`. Vault route loading effects are emitted from `/hyperopen/src/hyperopen/vaults/actions.cljs`, then fulfilled by vault API effects/projections (`/hyperopen/src/hyperopen/vaults/effects.cljs` and `/hyperopen/src/hyperopen/api/projections.cljs`).

In this repository, a "vault detail activity tab" means one of the tables under balances/positions/open orders/twap/trade history/funding history/order history/deposits+withdrawals/depositors. A "component strategy address" means a child vault address under `relationship.childAddresses` in `vaultDetails` for parent strategy vaults.

Hyperliquid network evidence for this task is stored in:

- `/hyperopen/tmp/hyperliquid-network/2026-02-27/vaultDetails.request.json`
- `/hyperopen/tmp/hyperliquid-network/2026-02-27/vaultDetails.response.json`
- `/hyperopen/tmp/hyperliquid-network/2026-02-27/webData2.request.json`
- `/hyperopen/tmp/hyperliquid-network/2026-02-27/webData2.response.json`
- `/hyperopen/tmp/hyperliquid-network/2026-02-27/ws-channel-samples.json`

## Plan of Work

First, add chart timeframe UI affordance in `vault_detail_view.cljs` near the existing Account Value/PNL toggles. The control will expose 24H, 7D, 30D, and All-time options and dispatch `:actions/set-vaults-snapshot-range`.

Second, update `detail_vm.cljs` so all activity row builders accept both existing API payloads and websocket channel payload wrappers used by Hyperliquid (`{:data {:fills [...]}}`, `{:data {:fundings [...]}}`, `{:data {:orderHistory [...]}}`, `{:data {:nonFundingLedgerUpdates [...]}}`, plus open-order/twap wrappers).

Third, update `vaults/actions.cljs` and `detail_vm.cljs` to include component strategy addresses in fills/funding/order-history fetch and aggregation, so parent vault activity tabs can reflect the same model Hyperliquid uses for HLP-style parent vaults.

Fourth, update tests in:

- `/hyperopen/test/hyperopen/vaults/actions_test.cljs`
- `/hyperopen/test/hyperopen/views/vaults/detail_vm_test.cljs`
- `/hyperopen/test/hyperopen/views/vault_detail_view_test.cljs`

## Concrete Steps

Run from repository root `/hyperopen`:

1. Create and maintain this ExecPlan while implementing.
2. Edit vault actions/VM/view/test files listed above.
3. Validate with:

    npm run check
    npm test
    npm run test:websocket

Expected success is all commands exiting 0.

## Validation and Acceptance

Acceptance criteria are:

- Vault detail chart header shows a timeframe dropdown with 24H, 7D, 30D, and All-time.
- Selecting a timeframe dispatches `:actions/set-vaults-snapshot-range` and updates the rendered chart series window.
- Activity tabs remain populated under both API and websocket-shaped payload variants.
- For parent vaults with child strategy addresses in relationship data, fills/funding/order history aggregation includes those addresses.
- Test suite and validation gates pass.

## Idempotence and Recovery

Edits are additive and idempotent. Re-running tests is safe. If a partial change fails tests, revert only the affected hunks and re-run the same commands until all gates pass.

## Artifacts and Notes

Representative observed `POST /info` payloads:

    {"type":"vaultDetails","vaultAddress":"0xdfc24b077bc1425ad1dea75bcb6f8158e10df303"}
    {"type":"webData2","user":"0xdfc24b077bc1425ad1dea75bcb6f8158e10df303"}

Representative websocket subscriptions observed on vault detail:

    {"method":"subscribe","subscription":{"type":"userFills","user":"0x010461c14e146ac35fe42271bdc1134ee31c703a","aggregateByTime":true}}
    {"method":"subscribe","subscription":{"type":"userFundings","user":"0x31ca8395cf837de08b24da3f660e77761dfb974b"}}
    {"method":"subscribe","subscription":{"type":"userHistoricalOrders","user":"0x010461c14e146ac35fe42271bdc1134ee31c703a"}}
    {"method":"subscribe","subscription":{"type":"userNonFundingLedgerUpdates","user":"0xdfc24b077bc1425ad1dea75bcb6f8158e10df303"}}

## Interfaces and Dependencies

No new external libraries are required. Existing interfaces remain unchanged:

- `hyperopen.vaults.actions/load-vault-detail`
- `hyperopen.views.vaults.detail-vm/vault-detail-vm`
- `hyperopen.views.vault-detail-view/vault-detail-view`

The implementation may add internal helper functions for component-address extraction and payload-row normalization, but must preserve existing public action/effect IDs.

Revision note (2026-02-27): Initial plan authored after direct HTTP and websocket capture of Hyperliquid vault detail data paths to ground implementation in observed payloads rather than inferred structure.
Revision note (2026-02-27): Updated progress, decision log, and outcomes after implementation and full validation gate completion.
