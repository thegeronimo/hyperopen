# HIP3 Order Placement Enablement

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, Hyperopen users can place orders on HIP3 (named-dex perpetual) markets from the existing order form, instead of always seeing “HIP-3 trading is not supported yet.” The behavior should match Hyperliquid protocol requirements by sending protocol-correct asset ids for builder-deployed perps and by preserving existing spot read-only behavior.

A user can verify the result by selecting a HIP3 market in the trade screen, filling valid order inputs, and observing that submit is enabled (when wallet/agent prerequisites are met) and the outgoing order action uses the expected HIP3 asset id.

## Progress

- [x] (2026-02-19 20:35Z) Audited current HIP3 submit block in local code (`submit-policy`, order-form banner, market identity read-only semantics).
- [x] (2026-02-19 20:35Z) Verified protocol requirements from current Hyperliquid docs (`exchange-endpoint.md`, `asset-ids.md`, `info-endpoint/perpetuals.md`).
- [x] (2026-02-19 20:35Z) Verified SDK parity expectations from `hyperliquid-python-sdk` (`Info` asset-id offset logic and builder-deployed dex order example).
- [x] (2026-02-19 20:35Z) Ran live browser inspection capture against `https://app.hyperliquid.xyz/trade` and recorded current UI constraints.
- [x] (2026-02-19 20:35Z) Authored this active ExecPlan.
- [x] (2026-02-19 20:44Z) Implemented canonical HIP3 asset-id projection in market normalization and cache/active-market restoration paths.
- [x] (2026-02-19 20:44Z) Wired order/cancel request builders to use canonical asset ids (default perps and HIP3) with fail-closed behavior for named-dex markets lacking canonical ids.
- [x] (2026-02-19 20:44Z) Removed HIP3 read-only gating and unsupported banner while preserving spot read-only behavior.
- [x] (2026-02-19 20:44Z) Added regression tests for HIP3 asset-id mapping, submit-policy behavior, cancel behavior, and UI/domain read-only semantics.
- [x] (2026-02-19 20:44Z) Ran required validation gates (`npm run check`, `npm test`, `npm run test:websocket`).

## Surprises & Discoveries

- Observation: HIP3 submit is blocked intentionally in the current submit policy and UI.
  Evidence: `/hyperopen/src/hyperopen/state/trading.cljs` maps HIP3 to `:hip3-read-only`, and `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` renders the HIP3 unsupported banner.

- Observation: Even if HIP3 read-only is removed, current order asset index wiring is not protocol-correct for builder-deployed perps.
  Evidence: `/hyperopen/src/hyperopen/state/trading.cljs` currently derives `:asset-idx` from `[:asset-contexts (keyword active-asset) :idx]`, which only maps default perp context keys and does not encode builder-dex offsets.

- Observation: Hyperopen currently stores per-dex universe-local indices (`:idx`) for HIP3 markets, not exchange asset ids.
  Evidence: `/hyperopen/src/hyperopen/asset_selector/markets.cljs` sets `:idx` from `map-indexed` within each `metaAndAssetCtxs` universe.

- Observation: Hyperliquid requires builder-deployed perp asset ids to be offset from default perp ids.
  Evidence: `asset-ids.md` states builder-deployed perp formula as `100000 + perp_dex_index * 10000 + index_in_meta`; `exchange-endpoint.md` order action field `a` points to the Asset IDs spec.

- Observation: Official SDK code and examples confirm that named-dex orders must use offset asset ids and that the API surface supports HIP3 abstraction actions.
  Evidence: `hyperliquid-python-sdk/hyperliquid/info.py` assigns builder-dex offsets starting at `110000`; `examples/basic_order_with_builder_deployed_dex.py` places orders on `<dex>:<coin>`; `exchange-endpoint.md` documents `agentSetDexAbstraction` and `agentSetAbstraction` actions.

- Observation: Live frontend inspection from this environment shows a restricted-jurisdiction overlay, which limits direct interactive validation from the public UI session.
  Evidence: `/hyperopen/tmp/browser-inspection/inspect-2026-02-19T20-26-55-604Z-1172be98/hyperliquid/desktop/snapshot.json` contains restricted-jurisdiction messaging.

## Decision Log

- Decision: Implement HIP3 enablement in the existing order pipeline, not via a parallel HIP3-only submit flow.
  Rationale: Reusing `submit-policy` -> `build-order-request` -> `api-submit-order` keeps behavior deterministic and preserves current architecture boundaries.
  Date/Author: 2026-02-19 / Codex

- Decision: Introduce canonical `:asset-id` and `:perp-dex-index` fields on normalized perp markets and treat request builder `:asset-idx` as canonical exchange asset id.
  Rationale: This resolves the protocol mismatch at the source and avoids duplicating asset-id math in multiple request paths.
  Date/Author: 2026-02-19 / Codex

- Decision: Keep scope focused on order/cancel correctness and HIP3 read-only removal; do not include automatic abstraction-mode mutation in this pass.
  Rationale: Account abstraction is a separate user-state mutation concern and can be layered later; core HIP3 order support should ship first with deterministic payload correctness.
  Date/Author: 2026-02-19 / Codex

## Outcomes & Retrospective

Implementation completed successfully. Hyperopen now projects canonical exchange `:asset-id` for perp markets (including HIP3 named-dex perps), routes order/cancel resolution through canonical ids, and no longer blocks HIP3 via local read-only policy. Spot behavior remains read-only and unchanged.

Validation outcomes:

- `npm run check`: pass
- `npm test`: pass
- `npm run test:websocket`: pass

Key retrospective note: The critical correctness guard was fail-closed behavior for named-dex markets if canonical `:asset-id` is missing, preventing accidental fallback to default-perp local indices.

## Context and Orientation

HIP3 in this repository means perpetual markets with a non-empty `:dex` (for example `hyna:BTC` or `xyz:AAPL`). The exchange order payload field `:a` is the asset id used by Hyperliquid matching logic. For default perps, `:a` is the universe index. For builder-deployed perps, `:a` must include a dex offset.

Relevant modules and current responsibilities:

- `/hyperopen/src/hyperopen/asset_selector/markets.cljs` normalizes perp and spot selector rows. It currently emits `:idx` and `:dex` but not canonical exchange `:asset-id`.
- `/hyperopen/src/hyperopen/api/endpoints/market.cljs` assembles selector state from `perpDexs`, `metaAndAssetCtxs`, and spot data.
- `/hyperopen/src/hyperopen/asset_selector/markets_cache.cljs` persists/restores selector market rows; any new canonical identity fields needed by submit logic must survive cache round-trips.
- `/hyperopen/src/hyperopen/state/trading.cljs` builds submit policy and trading context. It currently blocks HIP3 and resolves `:asset-idx` from default perp asset contexts.
- `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs` builds wire action payloads and reads `command-context :asset-idx` into order field `:a`.
- `/hyperopen/src/hyperopen/api/trading.cljs` builds cancel payloads and falls back to market-derived indices.
- `/hyperopen/src/hyperopen/domain/market/instrument.cljs` and `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` currently treat HIP3 as read-only in UI behavior.

Protocol facts that this plan must encode directly in code behavior:

- `metaAndAssetCtxs` accepts optional `dex` and returns per-dex universe indexing.
- `perpDexs` enumerates named dexs; default perp dex is index `0`.
- Builder-deployed perp asset ids follow `100000 + perp_dex_index * 10000 + index_in_meta`.

## Plan of Work

Milestone 1 introduces canonical asset-id projection during market normalization. Add pure helpers in `/hyperopen/src/hyperopen/asset_selector/markets.cljs` to derive builder-dex asset ids from dex index and universe index. Extend perp market rows to include `:perp-dex-index` and `:asset-id` for every perp row (default and named dex). Update `/hyperopen/src/hyperopen/api/endpoints/market.cljs` so `build-market-state` passes the correct dex index when building each dex’s markets. Update `/hyperopen/src/hyperopen/asset_selector/markets_cache.cljs` normalization so `:asset-id` and `:perp-dex-index` persist through cache hydration.

Milestone 2 rewires request construction to consume canonical asset ids. Update `/hyperopen/src/hyperopen/state/trading.cljs` trading-context construction to use active-market canonical `:asset-id` first, while keeping deterministic fallback for default-perp compatibility if needed. Ensure `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs` continues reading `:asset-idx` as canonical exchange id with no behavior split. Update `/hyperopen/src/hyperopen/api/trading.cljs` cancel request fallback so market lookup prefers `:asset-id` over local `:idx`.

Milestone 3 removes HIP3 read-only policy and messaging. Update `/hyperopen/src/hyperopen/domain/market/instrument.cljs` so `:read-only?` reflects spot-only restrictions instead of spot-or-HIP3. Remove `:hip3-read-only` gating branches/messages from `/hyperopen/src/hyperopen/state/trading.cljs`. Update `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` to stop rendering the HIP3 unsupported banner and to avoid disabling controls due solely to HIP3 identity.

Milestone 4 adds regression coverage and protocol-parity assertions. Add or update tests in the following files:

- `/hyperopen/test/hyperopen/asset_selector/markets_test.cljs` for builder-dex `:asset-id` and `:perp-dex-index` projection.
- `/hyperopen/test/hyperopen/asset_selector/markets_cache_test.cljs` for cache persistence of new identity fields.
- `/hyperopen/test/hyperopen/state/trading_test.cljs` for submit-policy HIP3 behavior and canonical context asset-id wiring.
- `/hyperopen/test/hyperopen/domain/market/instrument_test.cljs` for read-only semantics (spot stays read-only; HIP3 becomes tradable).
- `/hyperopen/test/hyperopen/api/trading_test.cljs` for cancel fallback preferring canonical `:asset-id`.
- `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` and `/hyperopen/test/hyperopen/views/trade/order_form_vm_test.cljs` for removal of HIP3 unsupported/read-only UI behavior.

Milestone 5 runs full repository gates and records evidence. Execute required commands, capture any failures and fixes in this plan, and update `Progress`, `Decision Log`, and `Outcomes & Retrospective` before moving this plan to completed.

## Concrete Steps

Run from `/hyperopen`.

1. Implement canonical perp asset-id helpers and row fields.
   - Edit `/hyperopen/src/hyperopen/asset_selector/markets.cljs`.
   - Edit `/hyperopen/src/hyperopen/api/endpoints/market.cljs`.
   - Edit `/hyperopen/src/hyperopen/asset_selector/markets_cache.cljs`.

2. Wire canonical asset ids through order/cancel context.
   - Edit `/hyperopen/src/hyperopen/state/trading.cljs`.
   - Edit `/hyperopen/src/hyperopen/api/trading.cljs`.

3. Remove HIP3 read-only behavior.
   - Edit `/hyperopen/src/hyperopen/domain/market/instrument.cljs`.
   - Edit `/hyperopen/src/hyperopen/state/trading.cljs`.
   - Edit `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`.

4. Add regression tests in the files listed in Milestone 4.

5. Run validation gates:

   npm run check
   npm test
   npm run test:websocket

Expected transcript shape:

- `npm run check` finishes with all lint/compile stages passing.
- `npm test` finishes with 0 failures and 0 errors.
- `npm run test:websocket` finishes with 0 failures and 0 errors.

## Validation and Acceptance

Acceptance criteria for this plan:

- Selecting a HIP3 market no longer forces submit policy reason `:hip3-read-only`.
- HIP3 order form no longer shows the unsupported banner text.
- HIP3 order payload uses canonical builder-dex asset id, not per-dex local index.
- Default perp payload asset ids remain unchanged.
- Spot markets remain read-only and still show existing unsupported-spot behavior.
- Cancel request fallback resolves HIP3 market ids using canonical `:asset-id` when order rows lack explicit asset fields.
- Required repository gates pass.

Manual behavior check after implementation (with a funded tradable account):

- Select one named-dex HIP3 market, place a low-size limit order, and verify a successful exchange response or a market-condition error unrelated to unsupported/HIP3-read-only gating.

## Idempotence and Recovery

All planned changes are deterministic and additive over existing pure transforms and selectors. Re-running normalization and cache hydration should produce stable outputs for the same API inputs. If a regression appears, recovery is localized:

- Re-enable `:hip3-read-only` policy branch in `/hyperopen/src/hyperopen/state/trading.cljs`.
- Keep canonical `:asset-id` projection code in place (safe for default perps) while debugging UI policy.
- Re-run full validation gates to confirm recovery state.

No destructive migration or external state mutation is required.

## Artifacts and Notes

Research artifacts used to seed this plan:

- Live capture: `/hyperopen/tmp/browser-inspection/inspect-2026-02-19T20-26-55-604Z-1172be98/`.
- Hyperliquid frontend bundle snapshot inspected for parity clues: `/hyperopen/tmp/hyperliquid-main.ccb853ef.js`.
- Local API sampling artifacts (perp dex roster and counts): `/hyperopen/tmp/perpDexs.json`, `/hyperopen/tmp/meta_ctx_counts.json`.

Protocol facts embedded from current Hyperliquid docs (2026-02-19 verification):

- `exchange-endpoint.md` order action `a` references Asset IDs.
- `asset-ids.md` defines builder-deployed perp asset-id formula.
- `exchange-endpoint.md` documents `agentSetDexAbstraction` and `agentSetAbstraction` actions.
- `info-endpoint/perpetuals.md` documents `metaAndAssetCtxs` with optional `dex` and `perpDexs`.

## Interfaces and Dependencies

Interfaces that must exist at completion:

- Normalized perp market rows include canonical identity fields:
  - `:idx` (local universe index, existing)
  - `:perp-dex-index` (new integer; `0` for default perp dex)
  - `:asset-id` (new integer; exchange order/cancel id)

- Trading command context keeps `:asset-idx` as canonical exchange id consumed by existing request builders.

- Submit policy and VM/view contracts keep `:hip3?` identity, but HIP3 no longer implies `:read-only?`.

No new external runtime dependencies are required.

Plan revision note: 2026-02-19 20:35Z - Initial plan created in response to HIP3 order-placement enablement request, incorporating codebase audit, protocol verification, and live frontend inspection constraints.
