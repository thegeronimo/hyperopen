# Trade Page Multi-Asset Deposit Rollout (Hyperliquid + HyperUnit Parity)

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Today `/trade` deposit supports only USDC on Arbitrum via Bridge2 transfer. Hyperliquid supports a wider set of assets and chain-specific deposit paths (direct on-chain deposit addresses for some assets and bridge/swap rails for others). After this change, users can choose any Hyperliquid-supported deposit asset from the trade-page modal and complete the correct per-asset flow without leaving the trading surface.

A user verifies behavior by opening `/trade`, selecting `Deposit`, seeing the complete supported asset list, and successfully progressing through the correct flow for each asset (sign-and-send where required, or generated deposit address + instructions where required).

## Progress

- [x] (2026-03-02 15:58Z) Re-read planning requirements in `/hyperopen/.agents/PLANS.md` and execution artifact policy in `/hyperopen/docs/PLANS.md`.
- [x] (2026-03-02 16:00Z) Audited current Hyperopen funding implementation in `/hyperopen/src/hyperopen/funding/actions.cljs`, `/hyperopen/src/hyperopen/funding/effects.cljs`, and `/hyperopen/src/hyperopen/views/funding_modal.cljs`; confirmed only USDC Bridge2 deposit is implemented.
- [x] (2026-03-02 16:08Z) Collected external protocol sources: Hyperliquid docs, Hyperliquid support pages, HyperUnit docs, and Hyperliquid frontend bundle evidence for current supported asset/chain map.
- [x] (2026-03-02 16:12Z) Authored this execution plan with discovery-first scope and per-coin milestones.
- [x] (2026-03-02 16:04Z) Completed Milestone 0 discovery: added `tools/extract_hl_deposit_assets.mjs` and generated `/hyperopen/docs/exec-plans/active/artifacts/2026-03-02-hl-deposit-assets-snapshot.json` from live frontend bundle data (14 assets, no unknown route keys).
- [x] (2026-03-02 16:17Z) Implemented shared multi-asset deposit domain model and modal flow state machine baseline (asset catalog, flow-kind-aware gating, and submit routing for USDC Bridge2 + USDT route flow).
- [x] (2026-03-02 16:23Z) Added initial shared implementation in `/hyperopen/src/hyperopen/funding/actions.cljs` and `/hyperopen/src/hyperopen/views/funding_modal.cljs` so all discovered assets appear in selection and unsupported flows are blocked with explicit copy.
- [x] (2026-03-02 16:26Z) Added tests for multi-asset catalog visibility and unsupported-flow submit protection in `/hyperopen/test/hyperopen/funding/actions_test.cljs`; verified with `npm test -- test/hyperopen/funding/actions_test.cljs` (suite pass) and `npm run check` (pass).
- [x] (2026-03-02 16:17Z) Completed Milestone 1 (`USDT`) route-flow implementation in `/hyperopen/src/hyperopen/funding/actions.cljs` and `/hyperopen/src/hyperopen/funding/effects.cljs`, including LiFi quote fetch, approval handling, swap tx submission, and Bridge2 USDC delta deposit submit.
- [x] (2026-03-02 16:17Z) Added/updated USDT route tests in `/hyperopen/test/hyperopen/funding/actions_test.cljs` and `/hyperopen/test/hyperopen/funding/effects_test.cljs`.
- [x] (2026-03-02 16:18Z) Ran required validation gates for this milestone: `npm run check`, `npm test`, `npm run test:websocket` (all pass; existing unrelated test compile warnings remain).
- [x] (2026-03-02 21:28Z) Completed Milestone 2 (`BTC`) HyperUnit address-flow implementation in `/hyperopen/src/hyperopen/funding/actions.cljs`, `/hyperopen/src/hyperopen/funding/effects.cljs`, and `/hyperopen/src/hyperopen/views/funding_modal.cljs` (generate/regenerate address flow, modal state persistence, and flow-aware CTA copy).
- [x] (2026-03-02 21:30Z) Added/updated BTC flow tests in `/hyperopen/test/hyperopen/funding/actions_test.cljs` and `/hyperopen/test/hyperopen/funding/effects_test.cljs`; updated compatibility map assertion in `/hyperopen/test/hyperopen/core_public_actions_test.cljs` for expanded funding modal defaults.
- [x] (2026-03-02 21:33Z) Re-ran required validation gates after Milestone 2: `npm run check`, `npm test`, `npm run test:websocket` (all pass; existing unrelated test compile warnings remain).
- [ ] Implement remaining per-coin milestones one by one with tests and manual verification (ETH, SOL, 2Z, BONK, ENA, FARTCOIN, MON, PUMP, SPX, XPL, plus any additional assets discovered in Milestone 0 such as USDH).
- [ ] Move this plan to completed after all per-coin acceptance criteria pass.

## Surprises & Discoveries

- Observation: Hyperliquid docs for Bridge2 explicitly describe native USDC bridging, while non-USDC deposits are documented in HyperUnit docs/support content and frontend flows rather than exchange action docs.
  Evidence: Hyperliquid Bridge2 doc (`/trading/deposit-and-withdraw/bridge2`) versus HyperUnit deposit docs (`docs.hyperunit.xyz`).

- Observation: Hyperliquid support and HyperUnit docs contain conflicting minimum amounts for some assets (for example BTC and ETH values differ across pages), so implementation must not hardcode minima from one static page.
  Evidence: Hyperliquid support FAQ minimum articles vs HyperUnit docs pages (`supported-assets` and `how-to/deposit-and-withdraw`).

- Observation: Live Hyperliquid frontend contains a hardcoded asset->chain selector map including assets beyond the user screenshot list (`usdh` is present in the map).
  Evidence: `https://app.hyperliquid.xyz/static/js/main.be4d3bab.js`, module containing:

    d={
      usdc:[arbitrum_cctp,arbitrum,lifi,evm],
      usdh:[arbitrum_across],
      usdt:[lifi],
      btc:[bitcoin],
      eth:[ethereum],
      sol:[solana],
      "2z":[solana],
      bonk:[solana],
      ena:[ethereum],
      fart:[solana],
      mon:[monad],
      pump:[solana],
      spxs:[solana],
      xpl:[plasma]
    }

- Observation: Official Hyperliquid SDKs (for example `hyperliquid-python-sdk`) expose exchange actions like `usdClassTransfer` and `withdraw3` but do not provide end-to-end non-USDC HyperUnit deposit orchestration.
  Evidence: `hyperliquid-python-sdk` `exchange.py` includes `usd_class_transfer` and `withdraw_from_bridge`, but no HyperUnit `/gen`/`/v2/transfer` client flow.

- Observation: Live frontend extraction currently yields 14 deposit assets and includes `usdh`, which was not in the user-provided screenshot/list.
  Evidence: `/hyperopen/docs/exec-plans/active/artifacts/2026-03-02-hl-deposit-assets-snapshot.json` (`summary.assetCount = 14`, `summary.userMentionedCoverage.additional = [\"usdc\", \"usdh\"]`).

- Observation: Exposing the full list without per-flow handling would produce invalid network submits for non-USDC assets.
  Evidence: Existing submit effect `api-submit-funding-deposit!` in `/hyperopen/src/hyperopen/funding/effects.cljs` only supports USDC Bridge2 transfer parameters.

- Observation: `npm test -- <path>` currently logs `Unknown arg` from `out/test.js` and still executes the full generated suite, so “targeted” validation incurs full test runtime in this repo configuration.
  Evidence: command output during this milestone (`Unknown arg: test/hyperopen/funding/actions_test.cljs`) while reporting full suite count (`Ran 1695 tests containing 8808 assertions.`).

- Observation: HyperUnit `/gen` testnet endpoint can reject destination addresses with a blocked-address error even when request shape is valid, so UI must surface upstream API error text and preserve modal state.
  Evidence: manual endpoint probe against `https://api.hyperunit-testnet.xyz/gen/...` returning `{"error":"destination address is blocked"}` during Milestone 2 discovery.

## Decision Log

- Decision: Treat asset discovery as a mandatory first milestone that must run against live Hyperliquid sources before any per-coin code changes.
  Rationale: Supported asset sets and routes are operational data and can change independently of docs; stale assumptions would ship incorrect deposit options.
  Date/Author: 2026-03-02 / Codex

- Decision: Implement one coin at a time with a fixed milestone template (domain config, effect path, tests, manual proof) rather than batching all assets in one large change.
  Rationale: The user explicitly requested one-by-one rollout, and this reduces blast radius for funds-related UX.
  Date/Author: 2026-03-02 / Codex

- Decision: Keep USDC Bridge2 flow as-is and extend via a typed `deposit-flow-kind` model rather than branching ad hoc in view code.
  Rationale: Preserves determinism and keeps protocol-specific side effects confined to funding effects/infrastructure boundaries.
  Date/Author: 2026-03-02 / Codex

- Decision: Consider the current working set as “candidate inventory” only until Milestone 0 snapshot is generated in-repo from live sources.
  Rationale: Docs inconsistencies and frontend hash churn require explicit re-validation at implementation start.
  Date/Author: 2026-03-02 / Codex

- Decision: Treat `usdh` as an in-scope post-user-list milestone asset (after user-requested 12 assets), because it is present in the live canonical frontend extraction.
  Rationale: Avoids knowingly shipping an incomplete deposit list versus the canonical Hyperliquid surface.
  Date/Author: 2026-03-02 / Codex

- Decision: Gate unsupported deposit flow kinds in `deposit-preview` with explicit user-facing error copy until each coin milestone implements real side effects.
  Rationale: Prevents sending incorrect transactions while still enabling iterative rollout and visible progress on catalog parity.
  Date/Author: 2026-03-02 / Codex

- Decision: Implement `USDT` route flow as a deterministic two-transaction wallet pipeline (`approve if needed` -> `swap` -> `Bridge2 deposit` using observed USDC balance delta) rather than trusting quote output amounts alone.
  Rationale: Using measured post-swap USDC delta prevents stale-quote drift from depositing an incorrect amount and keeps USDC Bridge2 logic as the single deposit submit path.
  Date/Author: 2026-03-02 / Codex

- Decision: Reuse existing `:effects/api-submit-funding-deposit` for HyperUnit address generation with a `:keep-modal-open?` response contract instead of introducing a new runtime effect ID.
  Rationale: Keeps action/effect registry surface stable while preserving deterministic state updates and minimizing blast radius for milestone-by-milestone rollout.
  Date/Author: 2026-03-02 / Codex

## Outcomes & Retrospective

Planning + discovery + Milestone 1 (`USDT`) + Milestone 2 (`BTC`) completed.

Achieved now:
- Defined a discovery-first execution strategy that prevents stale asset assumptions.
- Produced one-by-one per-coin implementation milestones and acceptance gates.
- Documented current known asset inventory and data-source conflicts.
- Created a reproducible extraction utility and captured a timestamped canonical asset snapshot artifact from live frontend data.
- Added shared multi-asset catalog plumbing and flow-kind gating in funding action/view layers with test coverage.
- Implemented first route-flow coin (`USDT`) end-to-end through funding action + effect pipeline with wallet submit behavior and regression-safe tests.
- Implemented first HyperUnit address-flow coin (`BTC`) with address-generation request path, modal in-place address rendering, and regenerate behavior.
- Revalidated required repo gates after milestone implementation (`npm run check`, `npm test`, `npm run test:websocket`).

Not yet achieved:
- Remaining per-coin route/address flows (`ETH`, `SOL`, `2Z`, `BONK`, `ENA`, `FARTCOIN`, `MON`, `PUMP`, `SPX`, `XPL`, `USDH`) are not implemented yet.
- No manual per-coin end-to-end verification artifacts yet beyond code-level/test-level validation.

## Context and Orientation

Current funding behavior is split across these repository surfaces:

- `/hyperopen/src/hyperopen/funding/actions.cljs`: modal state, field normalization, deposit/transfer/withdraw previews, and submit action dispatch.
- `/hyperopen/src/hyperopen/funding/effects.cljs`: side effects for transfer, withdraw, and USDC Bridge2 deposit transaction submission.
- `/hyperopen/src/hyperopen/views/funding_modal.cljs`: UI for deposit asset selection and amount step.
- `/hyperopen/src/hyperopen/state/app_defaults.cljs`: default funding modal state.
- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`, `/hyperopen/src/hyperopen/runtime/collaborators.cljs`, `/hyperopen/src/hyperopen/schema/contracts.cljs`: runtime registration and contract enforcement for effects/actions.

Terminology used in this plan:

- “Bridge2 flow” means wallet-signed ERC20 transfer to Hyperliquid Bridge2 contract (current USDC implementation).
- “HyperUnit address flow” means requesting a chain+asset deposit address and memo/instructions, then user sends funds from the source chain wallet/exchange.
- “Route flow” means bridge/swap-assisted rails (for example LI.FI or Across-backed paths) that can differ from direct deposit-address behavior.
- “Coin milestone” means one asset shipped end-to-end (UI, effects/domain, tests, manual proof) before moving to the next asset.

## Plan of Work

Milestone 0 is mandatory and must complete before any coin work. Add a small discovery utility under `tools/` that pulls the current Hyperliquid frontend bundle (or parses already-fetched bundle text), extracts the asset->chain map used by the deposit selector, and writes a timestamped artifact under `/hyperopen/docs/exec-plans/active/artifacts/`. In the same milestone, cross-check against Hyperliquid docs/support and HyperUnit docs to classify each asset into one of three concrete flow kinds (`:bridge2`, `:hyperunit-address`, `:route`). This milestone produces the authoritative asset inventory and removes ambiguity for all later milestones.

After Milestone 0, implement a shared multi-asset deposit architecture once, then execute per-coin milestones. The shared architecture introduces a canonical deposit asset catalog in `funding/actions` (or new `funding/model` namespace) including symbol, display name, network label, chain selector key, flow kind, and validation metadata. UI remains two-step (asset select -> flow step), but the second step becomes flow-kind aware: USDC remains amount-entry + wallet submission, address-flow coins render address/instructions, and route-flow coins render route launch/submit behavior.

Then implement coin milestones in this exact order to match user-requested list while keeping the discovery gate first:

1. `USDT` (route flow, currently indicated by `lifi`).
2. `BTC` (Bitcoin network address flow).
3. `ETH` (Ethereum address flow).
4. `SOL` (Solana address flow).
5. `2Z` (`ZZ`) (Solana address flow).
6. `BONK` (Solana address flow).
7. `ENA` (Ethereum address flow).
8. `FARTCOIN` (`fart`) (Solana address flow).
9. `MON` (Monad address flow).
10. `PUMP` (Solana address flow).
11. `SPX` (`spxs`) (Solana address flow).
12. `XPL` (Plasma address flow).
13. Any additional assets discovered in Milestone 0 but not in the user list (currently candidate: `USDH`).

Each coin milestone follows the same pattern: add catalog/config entry, implement/enable the correct effect path, update UI copy and warnings, add unit tests, run targeted manual flow check, and commit before starting the next coin.

## Concrete Steps

Run all commands from repository root `/hyperopen`.

1. Create discovery artifact tooling and snapshot current support list.

    mkdir -p docs/exec-plans/active/artifacts
    # add tools/extract_hl_deposit_assets.mjs (new)
    node tools/extract_hl_deposit_assets.mjs > docs/exec-plans/active/artifacts/2026-03-02-hl-deposit-assets-snapshot.json

Expected output contains asset keys and chain route keys similar to `usdc`, `usdt`, `btc`, `eth`, `sol`, `2z`, `bonk`, `ena`, `fart`, `mon`, `pump`, `spxs`, `xpl`, and any additional discovered keys.

2. Add/extend funding domain model for flow-kind aware deposit definitions.

    # edit
    src/hyperopen/funding/actions.cljs
    src/hyperopen/state/app_defaults.cljs
    # optional new namespace
    src/hyperopen/funding/model.cljs

3. Add infrastructure client(s) for non-USDC deposit mechanisms.

    # expected new/updated files
    src/hyperopen/funding/effects.cljs
    src/hyperopen/api/funding.cljs                # new if needed for HyperUnit HTTP client
    src/hyperopen/api/endpoints/funding.cljs      # new if needed for endpoint wrappers

4. Update modal rendering for flow-kind aware step-2 content.

    # edit
    src/hyperopen/views/funding_modal.cljs

5. Register any new effects/actions and schema contracts.

    # edit if new IDs are introduced
    src/hyperopen/runtime/effect_adapters.cljs
    src/hyperopen/runtime/collaborators.cljs
    src/hyperopen/runtime/registry_composition.cljs
    src/hyperopen/registry/runtime.cljs
    src/hyperopen/app/effects.cljs
    src/hyperopen/app/actions.cljs
    src/hyperopen/schema/contracts.cljs
    src/hyperopen/runtime/effect_order_contract.cljs

6. Implement each coin milestone one-by-one with tests and commit after each coin.

    # suggested commit cadence
    git commit -m "Add deposit flow for USDT"
    git commit -m "Add deposit flow for BTC"
    ...

7. Execute required validation gates after each coin milestone or at minimum after every two coin milestones.

    npm run check
    npm test
    npm run test:websocket

## Validation and Acceptance

Global acceptance is satisfied only when all conditions are true:

1. Deposit asset picker shows the full canonical asset list from Milestone 0 snapshot.
2. Selecting each asset transitions to the correct second-step flow and never falls back to incorrect USDC-only behavior.
3. USDC deposit regression check passes: wallet tx still submits to Bridge2 on selected Arbitrum network and retains existing success/error handling.
4. Each non-USDC asset has deterministic validation and clear instructions/errors specific to its flow kind.
5. Portfolio/unified summary refresh behavior remains correct after successful deposit events.
6. Required validation gates pass (`npm run check`, `npm test`, `npm run test:websocket`).
7. Manual verification log exists in the plan artifacts for each coin milestone (date, wallet/network used, observed result).

Per-coin acceptance template (must be repeated for every milestone):

- Asset appears in picker with correct icon/label/network.
- Step-2 UI matches expected flow kind.
- Submit/continue path triggers expected effect(s) with valid payload.
- Error path is user-readable and does not clear form state unexpectedly.
- Targeted tests for that asset pass.

## Idempotence and Recovery

Milestone 0 discovery and artifact generation is safe to rerun and should be rerun before starting new coin work if frontend hash changes. Per-coin milestones are additive and isolated by design. If a coin implementation introduces instability, revert only that coin’s commit and keep previous coin milestones intact.

Do not remove existing USDC behavior while adding new assets. Keep a strict fallback that blocks submit with a clear message when flow metadata is missing for a selected asset.

## Artifacts and Notes

Current canonical inventory snapshot (Milestone 0, generated 2026-03-02T16:04:14.673Z):

- User-provided list: `USDT`, `BTC`, `ETH`, `SOL`, `2Z`, `BONK`, `ENA`, `FARTCOIN`, `MON`, `PUMP`, `SPX`, `XPL`.
- Additional live asset beyond user list: `USDH`.
- Existing already-implemented baseline: `USDC`.
- Canonical artifact: `/hyperopen/docs/exec-plans/active/artifacts/2026-03-02-hl-deposit-assets-snapshot.json`.

External sources used for this planning snapshot:

- Hyperliquid Bridge2 docs: `https://hyperliquid.gitbook.io/hyperliquid-docs/trading/deposit-and-withdraw/bridge2`
- Hyperliquid onboarding/support article (supported assets): `https://hyperliquid.gitbook.io/hyperliquid-docs/onboarding/how-to-fund-your-account`
- Hyperliquid support minimum/deposit network pages (BTC, ETH/ENA, Solana-set, MON, XPL): `https://support.hyperliquid.xyz/`
- HyperUnit docs (supported assets, API operations, transfer endpoint): `https://docs.hyperunit.xyz/`
- Hyperliquid frontend bundle evidence for asset-chain map: `https://app.hyperliquid.xyz/static/js/main.be4d3bab.js`
- Hyperliquid Python SDK exchange actions reference: `https://github.com/hyperliquid-dex/hyperliquid-python-sdk`

## Interfaces and Dependencies

No mandatory new third-party NPM dependency is planned; use existing fetch/promise/runtime patterns.

Expected interfaces to exist after implementation:

- Funding catalog resolver returning complete deposit asset metadata and flow kind.
- Funding effect path for each flow kind:
  - existing `:effects/api-submit-funding-deposit` for `:bridge2` assets,
  - new effect(s) for `:hyperunit-address` address generation / polling,
  - new effect(s) for `:route` launch/submit handling.
- View-model fields that describe second-step rendering contract (`:deposit-flow-kind`, `:deposit-address`, `:deposit-instructions`, `:route-kind`, etc.).
- Test fixtures enumerating canonical supported assets and their expected flow metadata.

Revision note (2026-03-02): Created new ExecPlan to expand trade-page deposit from USDC-only to full Hyperliquid-supported asset set with discovery-first gating and one-coin-at-a-time milestones per user request.
Revision note (2026-03-02): Completed Milestone 0 discovery by adding `tools/extract_hl_deposit_assets.mjs` and generating canonical snapshot artifact `2026-03-02-hl-deposit-assets-snapshot.json`; updated progress, discoveries, decisions, and inventory sections accordingly.
Revision note (2026-03-02): Began shared implementation milestone by introducing multi-asset catalog visibility and safe unsupported-flow gating in funding actions/modal plus tests; kept per-coin route/address effects pending.
Revision note (2026-03-02): Completed Milestone 1 (`USDT`) route flow and Milestone 2 (`BTC`) HyperUnit address flow with funding action/effect/view updates, expanded tests, and passing validation gates (`npm run check`, `npm test`, `npm run test:websocket`).
