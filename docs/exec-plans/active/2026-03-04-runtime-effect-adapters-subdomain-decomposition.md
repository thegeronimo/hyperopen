# Decompose Runtime Effect Adapters by Subdomain with Stable Facade

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Today, `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` is a cross-domain module that mixes websocket, asset-selector, wallet, order, funding, and vault adapter behavior. This makes it expensive for a new contributor or stateless agent to find the right seam, and it increases accidental coupling risk when touching one domain.

After this refactor, runtime effect adapters will be decomposed into domain-focused namespaces under `/hyperopen/src/hyperopen/runtime/effect_adapters/`, while `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` remains a stable facade namespace that preserves existing public symbols and call signatures.

A contributor can verify success by confirming the monolith file is reduced to facade wiring, subdomain modules own domain logic, existing runtime wiring in `/hyperopen/src/hyperopen/app/effects.cljs` still compiles unchanged, and required gates pass (`npm run check`, `npm test`, `npm run test:websocket`).

## Progress

- [x] (2026-03-04 01:41Z) Audited current module complexity and fan-out baseline: `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` is 820 LOC and 40 require aliases (highest fan-out in `src/hyperopen`).
- [x] (2026-03-04 01:42Z) Audited runtime consumers and compatibility surfaces (`/hyperopen/src/hyperopen/app/effects.cljs`, `/hyperopen/src/hyperopen/app/bootstrap.cljs`, `/hyperopen/src/hyperopen/app/startup.cljs`, `/hyperopen/src/hyperopen/core/compat.cljs`, `/hyperopen/src/hyperopen/core/macros.clj`, runtime tests).
- [x] (2026-03-04 01:44Z) Created `bd` issue hierarchy for execution tracking: epic `hyperopen-63a` with child tasks `hyperopen-63a.1` through `hyperopen-63a.8`.
- [x] (2026-03-04 02:09Z) Created subdomain scaffold namespaces under `/hyperopen/src/hyperopen/runtime/effect_adapters/` (`common`, `websocket`, `asset_selector`, `wallet`, `order`, `funding`, `vaults`).
- [x] (2026-03-04 02:09Z) Moved shared helpers (effect handler wrappers, storage/navigation adapters, runtime error helpers) into `/hyperopen/src/hyperopen/runtime/effect_adapters/common.cljs` and delegated facade exports from `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`.
- [x] (2026-03-04 02:10Z) Added targeted facade parity regression coverage in `/hyperopen/test/hyperopen/runtime/effect_adapters_test.cljs` to assert shared adapter exports still resolve through the facade.
- [x] (2026-03-04 02:11Z) Ran quality gates: `npm run check`, `npm run test:websocket`, and full test suite via `npm run test:runner:generate && npx shadow-cljs compile test && node out/test.js` (all green).
- [ ] Milestone 1: Create subdomain namespace scaffold and convert `effect_adapters.cljs` into a stable facade-only module (completed: scaffold + shared helper extraction; remaining: move remaining non-shared logic behind facade delegates).
- [x] (2026-03-04 02:26Z) Extracted websocket/diagnostics runtime adapters into `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs` and delegated facade exports for health sync, websocket init/reconnect/refresh, diagnostics handlers, and startup active-asset restore seam.
- [x] (2026-03-04 02:26Z) Routed active-asset/orderbook/trades/webdata2 subscription adapters through websocket subdomain implementation while preserving facade override seam for `fetch-candle-snapshot` and active-market persistence callbacks.
- [x] (2026-03-04 02:26Z) Added websocket-facade parity assertions in `/hyperopen/test/hyperopen/runtime/effect_adapters_test.cljs`.
- [x] (2026-03-04 02:27Z) Re-ran required gates after websocket extraction: `npm run check`, `npm test`, `npm run test:websocket` (all green).
- [ ] Milestone 2: Extract websocket + diagnostics + asset-selector adapters into dedicated namespaces (completed: websocket + diagnostics extraction in `hyperopen-63a.2`; remaining: asset-selector extraction tracked in `hyperopen-63a.3`).
- [x] (2026-03-04 02:49Z) Extracted asset-selector adapters into `/hyperopen/src/hyperopen/runtime/effect_adapters/asset_selector.cljs`: icon queue/flush, market cache persist/restore, active market display persist/load, and owner-scoped active-ctx subscription diffing.
- [x] (2026-03-04 02:49Z) Rewired facade wrappers in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` to delegate asset-selector behavior while preserving the `schedule-animation-frame!` override seam used by core bootstrap tests.
- [x] (2026-03-04 02:49Z) Added asset-selector facade parity + seam regression checks in `/hyperopen/test/hyperopen/runtime/effect_adapters_test.cljs`.
- [x] (2026-03-04 02:50Z) Re-ran required gates after asset-selector extraction: `npm run check`, `npm test`, `npm run test:websocket` (all green).
- [x] Milestone 2: Extract websocket + diagnostics + asset-selector adapters into dedicated namespaces.
- [x] (2026-03-04 03:13Z) Extracted wallet adapters into `/hyperopen/src/hyperopen/runtime/effect_adapters/wallet.cljs` (connect/disconnect, storage-mode mutation, copy feedback lifecycle) and delegated facade exports in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`.
- [x] (2026-03-04 03:13Z) Preserved disconnect cleanup behavior by injecting facade-owned order-toast clear collaborators into `wallet-adapters/disconnect-wallet`.
- [x] (2026-03-04 03:13Z) Added wallet facade parity assertions in `/hyperopen/test/hyperopen/runtime/effect_adapters_test.cljs`.
- [x] (2026-03-04 03:14Z) Re-ran required gates after wallet extraction: `npm run check`, `npm test`, `npm run test:websocket` (all green).
- [ ] Milestone 3: Extract wallet + order adapters into dedicated namespaces (completed: wallet extraction in `hyperopen-63a.4`; remaining: order extraction in `hyperopen-63a.5`).
- [x] (2026-03-04 03:35Z) Extracted order adapters into `/hyperopen/src/hyperopen/runtime/effect_adapters/order.cljs` (order-feedback toast lifecycle + order API submit/cancel/TPSL/margin wrappers + make-api factories).
- [x] (2026-03-04 03:35Z) Delegated facade order APIs from `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` to `order` module, while preserving private order-toast collaborators used by wallet disconnect and funding/vault submit toasts.
- [x] (2026-03-04 03:35Z) Added order facade parity assertions in `/hyperopen/test/hyperopen/runtime/effect_adapters_test.cljs`.
- [x] (2026-03-04 03:36Z) Re-ran required gates after order extraction: `npm run check`, `npm test`, `npm run test:websocket` (all green).
- [x] Milestone 3: Extract wallet + order adapters into dedicated namespaces.
- [x] (2026-03-04 02:21Z) Extracted funding adapters into `/hyperopen/src/hyperopen/runtime/effect_adapters/funding.cljs`: funding predictability projection plus predicted-funding/funding-workflow API wrappers.
- [x] (2026-03-04 02:21Z) Rewired funding exports in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` to delegate to `funding` module while preserving facade-owned order toast seam injection for funding submit adapters.
- [x] (2026-03-04 02:21Z) Added funding facade parity + submit-wrapper seam regression coverage in `/hyperopen/test/hyperopen/runtime/effect_adapters_test.cljs`.
- [x] (2026-03-04 02:21Z) Re-ran required gates after funding extraction: `npm run check`, `npm test`, `npm run test:websocket` (all green).
- [ ] Milestone 4: Extract funding + vault adapters into dedicated namespaces (completed: funding extraction in `hyperopen-63a.6`; remaining: vault extraction in `hyperopen-63a.7`).
- [ ] Milestone 5: Decompose tests by subdomain, add facade contract assertions, and run required gates.

## Surprises & Discoveries

- Observation: Using `bd create --parent hyperopen-63a --deps discovered-from:hyperopen-63a` produced a dependency warning because `parent-child` already satisfies the relationship.
  Evidence: `bd` output: dependency already exists with type `parent-child`.

- Observation: `npm test` fails in this environment because the script invokes `shadow-cljs` directly, while the binary is only available via `npx`.
  Evidence: shell error `sh: shadow-cljs: command not found`; equivalent `npx shadow-cljs compile test` path succeeded.

- Observation: After installing dependencies in this worktree, `npm test` works as documented (the previous missing `shadow-cljs`/`@noble/secp256k1` errors were environment bootstrap issues, not code regressions).
  Evidence: `npm test` passed with `Ran 1822 tests containing 9431 assertions. 0 failures, 0 errors.`

- Observation: ClojureScript `with-redefs` against multi-arity adapter functions can fail if facade wrappers call `arity$N` directly.
  Evidence: `npm test` initially failed in `hyperopen.runtime.effect-adapters-test` with `TypeError: ... api_submit_funding_transfer_effect.cljs$core$IFn$_invoke$arity$4 is not a function` until submit wrappers used `apply`.

## Decision Log

- Decision: Keep `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` as a stable facade namespace for the full migration.
  Rationale: `/hyperopen/src/hyperopen/app/effects.cljs` and `/hyperopen/src/hyperopen/core/compat.cljs` rely on these exports; preserving the facade avoids broad wiring churn and honors architecture guidance to preserve stable public APIs.
  Date/Author: 2026-03-04 / Codex

- Decision: Use one subdomain module per runtime domain slice instead of one file per function.
  Rationale: The goal is reducing cross-domain coupling and improving orientation, not exploding file count. Domain-focused modules keep cohesion high while staying below architecture complexity thresholds.
  Date/Author: 2026-03-04 / Codex

- Decision: Split extraction in two waves (websocket+asset-selector, then wallet+order, then funding+vaults) before test decomposition.
  Rationale: This follows the highest-coupling seams first and keeps each change set behaviorally verifiable.
  Date/Author: 2026-03-04 / Codex

- Decision: Keep `schedule-animation-frame!` as a direct facade var alias (not wrapper function) when delegating to `common`.
  Rationale: Existing tests and compatibility seams use `with-redefs` on `hyperopen.runtime.effect-adapters/schedule-animation-frame!`; preserving var alias semantics avoids redef ambiguity during queue-asset icon runtime tests.
  Date/Author: 2026-03-04 / Codex

- Decision: Keep `subscribe-active-asset` as a facade wrapper (not direct var alias) when delegating to websocket module.
  Rationale: Existing tests rely on `with-redefs` against facade var `fetch-candle-snapshot`; wrapper-level dependency injection preserves that override seam while moving websocket subscription logic into the websocket subdomain namespace.
  Date/Author: 2026-03-04 / Codex

- Decision: Keep `queue-asset-icon-status` and `flush-queued-asset-icon-statuses!` as facade wrappers (not direct var aliases) while delegating to `asset_selector` module.
  Rationale: Existing tests use `with-redefs` on `hyperopen.runtime.effect-adapters/schedule-animation-frame!`; facade-level injection is required so redefs continue to affect queue scheduling behavior.
  Date/Author: 2026-03-04 / Codex

- Decision: Keep `disconnect-wallet` as a facade wrapper while moving core wallet logic to `wallet` module.
  Rationale: `disconnect-wallet` performs cross-domain cleanup (wallet copy timeout + order toast timeout/clear). Keeping a facade wrapper allows wallet module reuse while preserving explicit collaborator injection for order-toast cleanup without introducing wallet->order coupling.
  Date/Author: 2026-03-04 / Codex

- Decision: Expose order-toast lifecycle helpers from `order` module and keep facade-private aliases for cross-domain callers.
  Rationale: Funding/vault submit adapters and wallet disconnect currently rely on order toast collaborators; private facade aliases preserve existing call sites while removing order implementation logic from the monolith.
  Date/Author: 2026-03-04 / Codex

- Decision: Keep funding submit facade wrappers as explicit wrapper functions and invoke funding module submit effects via `apply`.
  Rationale: The wrappers preserve facade toast-seam injection while `apply` keeps `with-redefs` test seams stable for multi-arity adapter function redefinitions.
  Date/Author: 2026-03-04 / Codex

## Outcomes & Retrospective

Six extraction slices are complete: subdomain scaffold + shared helper seam (`common`), websocket/diagnostics seam (`websocket`), asset-selector seam (`asset_selector`), wallet seam (`wallet`), order seam (`order`), and funding seam (`funding`). The facade now delegates websocket, asset-selector, wallet, order, and funding behavior while preserving compatibility exports and override seams. Remaining work is concentrated in vault extraction and test decomposition milestones.

## Context and Orientation

In this repository, an “effect adapter” is a runtime-facing function that converts registry effect calls (`ctx`, `store`, args) into explicit calls to domain effect modules, runtime collaborators, and infrastructure boundaries. The adapter layer is where dependency injection, error normalization, and runtime sequencing meet.

Current ownership is centralized in one file:

- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`

This monolith currently exports 71 public vars and is consumed by:

- `/hyperopen/src/hyperopen/app/effects.cljs` (runtime effect override map)
- `/hyperopen/src/hyperopen/app/bootstrap.cljs` and `/hyperopen/src/hyperopen/app/startup.cljs` (startup/bootstrap seams)
- `/hyperopen/src/hyperopen/core/compat.cljs` via `/hyperopen/src/hyperopen/core/macros.clj` (legacy core compatibility aliases)

Current test coverage touching this boundary includes:

- `/hyperopen/test/hyperopen/runtime/effect_adapters_test.cljs`
- `/hyperopen/test/hyperopen/app/effects_test.cljs`
- `/hyperopen/test/hyperopen/runtime/wiring_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/asset_cache_persistence_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/asset_selector_actions_test.cljs`

A novice implementing this plan should treat the existing public var names in `hyperopen.runtime.effect-adapters` as stable compatibility contracts unless an explicit follow-up issue approves API changes.

Issue tracking for this plan is in `bd`:

- Epic: `hyperopen-63a`
- Children: `hyperopen-63a.1` to `hyperopen-63a.8`

## Plan of Work

### Milestone 1: Scaffold Subdomain Modules and Preserve Facade Contract

Create `/hyperopen/src/hyperopen/runtime/effect_adapters/` and add initial namespaces:

- `/hyperopen/src/hyperopen/runtime/effect_adapters/common.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters/asset_selector.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters/wallet.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters/order.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters/funding.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters/vaults.cljs`

Move only shared helpers first (signature adapters, shared runtime-error helpers, shared storage/navigation adapters) so `effect_adapters.cljs` begins shrinking without behavior changes. Keep facade exports in `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` and delegate implementations to new modules.

Acceptance for this milestone is a green compile/test state with no runtime wiring changes in `/hyperopen/src/hyperopen/app/effects.cljs`.

### Milestone 2: Extract Websocket and Asset-Selector Adapter Logic

Move websocket and diagnostics behavior into `websocket.cljs`, including health sync, reconnect/refresh, subscription wrappers, diagnostics copy/reset/reveal, and startup active-asset restore seam. Move asset-selector-specific behavior into `asset_selector.cljs`, including icon status queue/flush, cache persist/restore, active market display persistence, and owner-scoped active-asset-ctx subscription diffs.

Preserve deterministic responsiveness rules from `/hyperopen/docs/RELIABILITY.md`: user-visible state updates remain ahead of heavy work where applicable.

Acceptance for this milestone is parity of websocket health diagnostics behavior and selector subscription behavior under existing tests.

### Milestone 3: Extract Wallet and Order Adapter Logic

Move wallet connection/copy/storage-mode behavior into `wallet.cljs`, including timeout lifecycle helpers. Move order submission/cancel/position wrappers and toast helpers into `order.cljs`, preserving injected collaborators (`dispatch!`, error normalization, and toast scheduling).

Keep all existing factory function signatures (`make-disconnect-wallet`, `make-copy-wallet-address`, `make-api-*`) unchanged at facade level.

Acceptance for this milestone is parity of order toast scheduling and wallet copy/disconnect cleanup semantics.

### Milestone 4: Extract Funding and Vault Adapter Logic

Move funding predictability and funding workflow API wrappers into `funding.cljs`. Move vault API wrappers into `vaults.cljs`.

Retain collaborator boundaries and runtime error normalization contracts exactly as in current implementation.

Acceptance for this milestone is unchanged API effect behavior in app wiring and related runtime tests.

### Milestone 5: Decompose Tests and Finalize Validation

Split the monolithic runtime adapter test file into subdomain-oriented tests that mirror source ownership, for example:

- `/hyperopen/test/hyperopen/runtime/effect_adapters/common_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/websocket_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/asset_selector_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/wallet_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/order_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/funding_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/vaults_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/facade_contract_test.cljs`

Update `/hyperopen/test/test_runner_generated.cljs` with any moved or new test namespaces.

Acceptance for this milestone is all required gates passing and facade contract coverage preventing export drift.

## Concrete Steps

All commands run from `/hyperopen`.

1. Capture baseline and export inventory before edits:

    wc -l src/hyperopen/runtime/effect_adapters.cljs
    rg -n '^\(defn|^\(def ' src/hyperopen/runtime/effect_adapters.cljs
    rg -n "effect-adapters" src/hyperopen/app src/hyperopen/core test/hyperopen

2. Create new adapter namespace files and move shared helpers first. Keep facade namespace exports compiling after each move.

3. Extract domain slices milestone-by-milestone, running targeted tests after each milestone:

    npm test -- --focus=runtime/effect_adapters

   If focused test execution is unavailable in this test runner, run full `npm test` after each milestone.

4. Split test files by subdomain and update `/hyperopen/test/test_runner_generated.cljs`.

5. Run full required gates:

    npm run check
    npm test
    npm run test:websocket

Expected outcome: all commands exit `0`.

## Validation and Acceptance

This refactor is accepted when all conditions are true:

- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` remains present as a facade namespace and exports all legacy public vars used by runtime wiring.
- Domain logic formerly centralized in the monolith now lives in subdomain namespaces under `/hyperopen/src/hyperopen/runtime/effect_adapters/`.
- No public behavior regressions are observed in runtime wiring, bootstrap/startup seams, or core compatibility exports.
- All required gates pass:
  - `npm run check`
  - `npm test`
  - `npm run test:websocket`
- Refactor completion confidence is documented at or above `84.7%` per `/hyperopen/docs/QUALITY_SCORE.md`.

## Idempotence and Recovery

Each extraction step is a move/delegate operation and can be repeated safely if interrupted. If a milestone fails mid-way, restore forward progress by ensuring facade namespace vars still resolve and point at one canonical implementation.

If tests fail after extraction, first search for stale namespace references and unresolved requires:

    rg -n "runtime.effect-adapters|runtime/effect_adapters" src test

Then verify no symbol was dropped from facade exports by comparing against the baseline inventory captured in Milestone 1.

## Artifacts and Notes

Baseline evidence captured before implementation:

- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`: 820 LOC
- Require alias fan-out: 40 (highest in `src/hyperopen` at planning time)
- Public exports to preserve: 71 vars

`bd` tracking structure:

- `hyperopen-63a` (epic)
- `hyperopen-63a.1` scaffold/facade
- `hyperopen-63a.2` websocket/diagnostics extraction
- `hyperopen-63a.3` asset-selector extraction
- `hyperopen-63a.4` wallet extraction
- `hyperopen-63a.5` order extraction
- `hyperopen-63a.6` funding extraction
- `hyperopen-63a.7` vault extraction
- `hyperopen-63a.8` test decomposition + facade contract

## Interfaces and Dependencies

Stable interface contract: `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs` must keep these public var names available throughout migration, with unchanged call signatures:

`append-diagnostics-event!`, `sync-websocket-health-with-runtime!`, `sync-websocket-health!`, `save`, `save-many`, `local-storage-set`, `local-storage-set-json`, `schedule-animation-frame!`, `flush-queued-asset-icon-statuses!`, `queue-asset-icon-status`, `make-queue-asset-icon-status`, `push-state`, `replace-state`, `make-fetch-candle-snapshot`, `fetch-candle-snapshot`, `make-init-websocket`, `init-websocket`, `persist-asset-selector-markets-cache!`, `restore-asset-selector-markets-cache!`, `persist-active-market-display!`, `load-active-market-display`, `subscribe-active-asset`, `unsubscribe-active-asset`, `subscribe-orderbook`, `subscribe-trades`, `unsubscribe-orderbook`, `unsubscribe-trades`, `subscribe-webdata2`, `unsubscribe-webdata2`, `sync-asset-selector-active-ctx-subscriptions`, `connect-wallet`, `disconnect-wallet`, `make-disconnect-wallet`, `set-agent-storage-mode`, `copy-wallet-address`, `make-copy-wallet-address`, `make-reconnect-websocket`, `reconnect-websocket`, `refresh-websocket-health`, `make-refresh-websocket-health`, `ws-reset-subscriptions`, `confirm-ws-diagnostics-reveal`, `copy-websocket-diagnostics`, `restore-active-asset!`, `api-submit-order`, `api-cancel-order`, `api-submit-position-tpsl`, `api-submit-position-margin`, `make-api-submit-order`, `make-api-cancel-order`, `make-api-submit-position-tpsl`, `make-api-submit-position-margin`, `sync-active-asset-funding-predictability`, `fetch-asset-selector-markets-effect`, `api-load-user-data-effect`, `api-fetch-predicted-fundings-effect`, `api-fetch-vault-index-effect`, `api-fetch-vault-summaries-effect`, `api-fetch-user-vault-equities-effect`, `api-fetch-vault-details-effect`, `api-fetch-vault-webdata2-effect`, `api-fetch-vault-fills-effect`, `api-fetch-vault-funding-history-effect`, `api-fetch-vault-order-history-effect`, `api-fetch-vault-ledger-updates-effect`, `api-submit-vault-transfer-effect`, `api-fetch-hyperunit-fee-estimate-effect`, `api-fetch-hyperunit-withdrawal-queue-effect`, `api-submit-funding-transfer-effect`, `api-submit-funding-withdraw-effect`, `api-submit-funding-deposit-effect`.

Dependencies and invariants to preserve while extracting:

- `/hyperopen/ARCHITECTURE.md`: preserve stable APIs and bounded module complexity.
- `/hyperopen/docs/RELIABILITY.md`: keep effect ordering deterministic and side effects at adapter/infrastructure boundaries only.
- `/hyperopen/docs/QUALITY_SCORE.md`: required test gates and confidence threshold.

Plan Revision Note (2026-03-04 / Codex): Initial ExecPlan authored from current repository state and linked to newly created `bd` issue hierarchy for execution tracking.
