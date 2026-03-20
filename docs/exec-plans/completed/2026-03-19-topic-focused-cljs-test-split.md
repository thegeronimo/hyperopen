# Split Oversized CLJS Test Namespaces Into Topic-Focused Files

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-gpuv`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

After this change, contributors and coding agents should be able to load one small test namespace for one behavior family instead of loading three monolithic files that currently total more than 4,500 lines.

Users should see no production behavior change. This is a behavior-preserving refactor of the test topology only. Success means the existing funding, order-mutation, and active-asset assertions still exist, but they are redistributed into smaller topic-focused namespaces with narrow local `test_support` helpers and the required repository gates remain green.

## Progress

- [x] (2026-03-20 01:14Z) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/.agents/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`.
- [x] (2026-03-20 01:14Z) Created and claimed `hyperopen-gpuv` for this refactor.
- [x] (2026-03-20 01:15Z) Audited the three target monoliths, captured all current `deftest` names, and confirmed `test/test_runner_generated.cljs` must be regenerated after namespace changes.
- [x] (2026-03-20 01:16Z) Authored this active ExecPlan before changing the test files.
- [x] (2026-03-20 03:24Z) Split `/hyperopen/test/hyperopen/funding/effects_test.cljs` into application-focused namespaces plus local funding test support while preserving a thin facade suite.
- [x] (2026-03-20 03:24Z) Split `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs` into topic-focused namespaces plus local order-effects test support, then deleted the empty umbrella file because it no longer carried composition coverage.
- [x] (2026-03-20 03:24Z) Split `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs` into source-seam namespaces plus local active-asset test support.
- [x] (2026-03-20 03:33Z) Regenerated `/hyperopen/test/test_runner_generated.cljs` and ran `npm test` on the final split state.
- [x] (2026-03-20 03:41Z) Ran `npm test` and `npm run test:websocket` successfully on the final state.
- [x] (2026-03-20 03:41Z) Ran `npm run check`; the refactor-specific work passed, but the command still fails on unrelated pre-existing active ExecPlans that reference closed `bd` issues.
- [x] (2026-03-20 03:42Z) Moved this ExecPlan out of `active` after recording the completed implementation state.

## Surprises & Discoveries

- Observation: the test runner is generated and uses explicit namespace lists rather than filesystem discovery.
  Evidence: `/hyperopen/test/test_runner_generated.cljs` currently requires `hyperopen.funding.effects-test`, `hyperopen.core-bootstrap.order-effects-test`, and `hyperopen.views.active-asset-view-test` directly.

- Observation: the funding source tree already has application seams that match the planned test splits.
  Evidence: `/hyperopen/src/hyperopen/funding/application/submit_effects.cljs`, `/hyperopen/src/hyperopen/funding/application/deposit_submit.cljs`, `/hyperopen/src/hyperopen/funding/application/hyperunit_query.cljs`, `/hyperopen/src/hyperopen/funding/application/hyperunit_submit.cljs`, and `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`.

- Observation: the active-asset source tree already has view-local seams that match the planned test splits.
  Evidence: `/hyperopen/src/hyperopen/views/active_asset/vm.cljs`, `/hyperopen/src/hyperopen/views/active_asset/row.cljs`, `/hyperopen/src/hyperopen/views/active_asset/icon_button.cljs`, and `/hyperopen/src/hyperopen/views/active_asset/funding_tooltip.cljs`.

- Observation: `npm run check` is currently blocked by unrelated repository hygiene, not by this refactor.
  Evidence: the command now fails in `lint:docs` because `/hyperopen/docs/exec-plans/active/2026-03-18-balances-tab-hyperliquid-parity-plan.md` and `/hyperopen/docs/exec-plans/active/2026-03-19-trading-settings-icon-popover-ui.md` still reference closed `bd` issues.

## Decision Log

- Decision: keep a thin top-level suite only where a real facade/composition seam exists, instead of keeping empty umbrella files everywhere.
  Rationale: the goal is lower context cost, not merely more files; a file that only re-exports topic tests adds indirection without value.
  Date/Author: 2026-03-20 / Codex

- Decision: split by source-module seam or stable behavior family, not by line count, success-vs-error, or viewport mode.
  Rationale: this keeps each resulting namespace aligned with how contributors decide what code to inspect and avoids recreating mixed context in smaller files.
  Date/Author: 2026-03-20 / Codex

- Decision: prefer local `test_support` namespaces for each split family and only promote helpers to a broader shared namespace when they are truly cross-domain.
  Rationale: the repo already follows local-first test support patterns, and over-centralized helpers become a new context sink.
  Date/Author: 2026-03-20 / Codex

## Outcomes & Retrospective

Implementation completed as a behavior-preserving test-only refactor.

Funding coverage now lives in:

- `/hyperopen/test/hyperopen/funding/application/submit_effects_test.cljs`
- `/hyperopen/test/hyperopen/funding/application/deposit_submit_test.cljs`
- `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
- `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
- `/hyperopen/test/hyperopen/funding/application/lifecycle_polling_test.cljs`
- `/hyperopen/test/hyperopen/funding/test_support/effects.cljs`
- `/hyperopen/test/hyperopen/funding/test_support/hyperunit.cljs`

Order-effects coverage now lives in:

- `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_refresh_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/order_effects/cancel_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/order_effects/position_tpsl_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/order_effects/position_margin_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/order_effects/test_support.cljs`

Active-asset coverage now lives in:

- `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs`
- `/hyperopen/test/hyperopen/views/active_asset/vm_test.cljs`
- `/hyperopen/test/hyperopen/views/active_asset/icon_button_test.cljs`
- `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
- `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_popover_test.cljs`
- `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs`
- `/hyperopen/test/hyperopen/views/active_asset/test_support.cljs`

Validation outcome:

- `npm test` passed.
- `npm run test:websocket` passed.
- `npm run check` remains blocked by unrelated pre-existing active ExecPlans that still point at closed `bd` issues.

No production source files under `/hyperopen/src/**` were changed.

## Context and Orientation

The three target files are:

- `/hyperopen/test/hyperopen/funding/effects_test.cljs` (`49` tests, `1851` lines)
- `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs` (`28` tests, `1388` lines)
- `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs` (`40` tests, `1266` lines)

These files currently mix multiple distinct concerns in one namespace:

- funding submit entrypoints, HyperUnit queries, lifecycle polling, transport-wrapper helpers, and asset-specific address-generation matrix cases
- order submit refresh policy, submit failures, cancel/TWAP cancel flows, and position TP/SL or margin modal submits
- active-asset top-level view wiring, VM pruning, row rendering, icon probe lifecycle, funding-tooltip popover interaction, and funding-tooltip model coverage

The runner file `/hyperopen/test/test_runner_generated.cljs` is generated by `npm run test:runner:generate`. It must not be edited by hand. Any moved or renamed test namespace must be reflected by rerunning the generator or the tests will silently stop executing.

## Plan of Work

First, create narrowly scoped local test-support namespaces for the split areas that actually share setup. These helpers should cover builders, fake image nodes, refresh-mock installers, toast/dispatch captors, and fixture payloads, but they must not hide the core assertions.

Second, split funding tests along the existing application seams in the source tree and compress the repeated HyperUnit deposit-address asset matrix into a table-driven test with one `testing` block per asset and chain combination.

Third, split order-effects tests by mutation family: submit refresh policy, submit failures and guards, cancel flows, TP/SL submit flows, and margin submit flows.

Fourth, split active-asset tests by source seam: top-level view wiring, VM, row rendering, icon button, funding-tooltip popover behavior, and funding-tooltip model behavior.

Fifth, regenerate the test runner after each subject wave, run `npm test` after each wave to localize breakages, then run the required repo gates on the final state.

## Concrete Steps

1. Add the active ExecPlan and linked `bd` issue.
2. Add funding support namespaces under `/hyperopen/test/hyperopen/funding/test_support/` and move funding tests into application-focused files under `/hyperopen/test/hyperopen/funding/application/`.
3. Add order-effects support under `/hyperopen/test/hyperopen/core_bootstrap/order_effects/test_support.cljs` and move order tests into `/hyperopen/test/hyperopen/core_bootstrap/order_effects/`.
4. Add active-asset support under `/hyperopen/test/hyperopen/views/active_asset/test_support.cljs` and move tests into `/hyperopen/test/hyperopen/views/active_asset/`.
5. Regenerate the runner with `npm run test:runner:generate`.
6. Run `npm test` after each subject migration.
7. Run the final required gates:

   `npm run check`
   `npm test`
   `npm run test:websocket`

## Validation and Acceptance

Acceptance is satisfied when:

1. Every current `deftest` from the three monoliths appears in a destination namespace listed in the migration matrix below.
2. The repeated HyperUnit deposit-address asset cases are preserved in a smaller table-driven test without reducing failure locality.
3. The resulting files are topic-focused and no replacement file remains an unrelated kitchen-sink namespace.
4. `npm run test:runner:generate`, `npm test`, `npm run check`, and `npm run test:websocket` pass on the final state.
5. No production source files under `/hyperopen/src/**` are changed.

## Idempotence and Recovery

This refactor is safe to repeat because it only changes test topology and the generated runner file. If a split introduces failures, recovery is to restore the affected tests to one namespace within the same subject area, rerun the runner generator, and continue incrementally. No destructive repository operation is required.

## Migration Matrix

### Funding

- `/hyperopen/test/hyperopen/funding/effects_test.cljs`
  - `api-submit-funding-send-no-wallet-sets-error-test` -> `/hyperopen/test/hyperopen/funding/application/submit_effects_test.cljs`
  - `api-submit-funding-send-success-closes-modal-and-refreshes-test` -> `/hyperopen/test/hyperopen/funding/application/submit_effects_test.cljs`
  - `api-submit-funding-transfer-no-wallet-sets-error-test` -> `/hyperopen/test/hyperopen/funding/application/submit_effects_test.cljs`
  - `api-submit-funding-transfer-blocks-mutations-while-spectate-mode-active-test` -> `/hyperopen/test/hyperopen/funding/application/submit_effects_test.cljs`
  - `api-submit-funding-transfer-success-closes-modal-and-refreshes-test` -> `/hyperopen/test/hyperopen/funding/application/submit_effects_test.cljs`
  - `api-submit-funding-transfer-error-response-sets-error-state-test` -> `/hyperopen/test/hyperopen/funding/application/submit_effects_test.cljs`
  - `api-submit-funding-withdraw-no-wallet-sets-error-test` -> `/hyperopen/test/hyperopen/funding/application/submit_effects_test.cljs`
  - `api-submit-funding-withdraw-blocks-mutations-while-spectate-mode-active-test` -> `/hyperopen/test/hyperopen/funding/application/submit_effects_test.cljs`
  - `api-submit-funding-withdraw-success-closes-modal-and-refreshes-test` -> `/hyperopen/test/hyperopen/funding/application/submit_effects_test.cljs`
  - `api-submit-funding-withdraw-runtime-error-sets-error-state-test` -> `/hyperopen/test/hyperopen/funding/application/submit_effects_test.cljs`
  - `api-submit-funding-deposit-blocks-mutations-while-spectate-mode-active-test` -> `/hyperopen/test/hyperopen/funding/application/deposit_submit_test.cljs`
  - `api-submit-funding-deposit-no-wallet-sets-error-test` -> `/hyperopen/test/hyperopen/funding/application/deposit_submit_test.cljs`
  - `api-submit-funding-deposit-success-closes-modal-and-refreshes-test` -> `/hyperopen/test/hyperopen/funding/application/deposit_submit_test.cljs`
  - `api-submit-funding-deposit-usdt-route-delegates-to-lifi-submitter-test` -> `/hyperopen/test/hyperopen/funding/application/deposit_submit_test.cljs`
  - `api-submit-funding-deposit-usdh-route-delegates-to-across-submitter-test` -> `/hyperopen/test/hyperopen/funding/application/deposit_submit_test.cljs`
  - `api-submit-funding-deposit-runtime-error-sets-error-state-test` -> `/hyperopen/test/hyperopen/funding/application/deposit_submit_test.cljs`
  - `api-fetch-hyperunit-fee-estimate-updates-modal-on-success-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
  - `api-fetch-hyperunit-fee-estimate-sets-error-state-on-failure-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
  - `api-fetch-hyperunit-fee-estimate-prefetches-existing-hyperunit-deposit-address-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
  - `api-fetch-hyperunit-withdrawal-queue-updates-modal-on-success-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
  - `api-fetch-hyperunit-withdrawal-queue-sets-error-state-on-failure-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
  - `select-existing-hyperunit-deposit-address-normalizes-chain-aliases-and-validates-address-shape-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
  - `hyperunit-source-chain-candidates-normalizes-aliases-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
  - `protocol-address-matches-source-chain-validates-address-shapes-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
  - `request-hyperunit-operations-uses-direct-base-url-candidate-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
  - `hyperunit-request-error-message-network-failure-is-actionable-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
  - `submit-hyperunit-address-deposit-request-reuses-existing-address-before-generate-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `api-submit-funding-withdraw-hyperunit-send-asset-polls-and-updates-lifecycle-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `api-submit-funding-deposit-hyperunit-address-keeps-modal-open-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `api-submit-funding-deposit-hyperunit-address-reused-response-shows-existing-address-toast-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `api-submit-funding-deposit-sync-submitter-throw-sets-error-and-clears-submitting-test` -> `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `api-submit-funding-deposit-hyperunit-address-terminal-lifecycle-refreshes-user-data-test` -> `/hyperopen/test/hyperopen/funding/application/lifecycle_polling_test.cljs`
  - `api-submit-funding-deposit-hyperunit-address-polls-and-updates-lifecycle-test` -> `/hyperopen/test/hyperopen/funding/application/lifecycle_polling_test.cljs`
  - `api-submit-funding-deposit-hyperunit-address-schedules-next-poll-from-state-next-attempt-test` -> `/hyperopen/test/hyperopen/funding/application/lifecycle_polling_test.cljs`
  - `funding-effect-helper-normalizers-cover_chain_address_and_token_branches_test` -> `/hyperopen/test/hyperopen/funding/effects_test.cljs`
  - `funding-effect-unit_and_error_helpers_cover_numeric_and_message_fallbacks_test` -> `/hyperopen/test/hyperopen/funding/effects_test.cljs`
  - `funding-effect-config_resolution_prefers_action_wallet_and_default_branches_test` -> `/hyperopen/test/hyperopen/funding/effects_test.cljs`
  - `funding-effect_route_and_rpc_wrappers_pass_expected_options_test` -> `/hyperopen/test/hyperopen/funding/effects_test.cljs`

- HyperUnit deposit-address asset matrix currently in `/hyperopen/test/hyperopen/funding/effects_test.cljs`
  - `btc` from `bitcoin` / `Bitcoin` -> table-driven case in `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `eth` from `ethereum` / `Ethereum` -> table-driven case in `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `sol` from `solana` / `Solana` -> table-driven case in `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `2z` from `solana` / `Solana` -> table-driven case in `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `bonk` from `solana` / `Solana` -> table-driven case in `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `ena` from `ethereum` / `Ethereum` -> table-driven case in `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `fart` from `solana` / `Solana` -> table-driven case in `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `mon` from `monad` / `Monad` -> table-driven case in `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `pump` from `solana` / `Solana` -> table-driven case in `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `spxs` from `solana` / `Solana` -> table-driven case in `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`
  - `xpl` from `plasma` / `Plasma` -> table-driven case in `/hyperopen/test/hyperopen/funding/application/hyperunit_submit_test.cljs`

### Order Effects

- `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`
  - `api-submit-order-effect-shows-success-toast-and-refreshes-history-and-open-orders-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_refresh_test.cljs`
  - `api-submit-order-effect-refreshes-dex-open-orders-and-skips-per-dex-clearinghouse-when-ws-snapshot-ready-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_refresh_test.cljs`
  - `api-submit-order-effect-refreshes-dex-open-orders-for-event-driven-ws-streams-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_refresh_test.cljs`
  - `api-submit-order-effect-uses-rest-refresh-when-ws-first-flag-disabled-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_refresh_test.cljs`
  - `api-submit-order-effect-treats-nested-status-errors-as-failures-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`
  - `api-submit-order-effect-refreshes-when-submit-response-is-partial-success-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`
  - `api-submit-order-effect-runs-pre-submit-actions-before-order-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`
  - `api-submit-order-effect-aborts-when-pre-submit-action-fails-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`
  - `api-submit-order-effect-surfaces-top-level-exchange-errors-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`
  - `api-submit-order-effect-opens-enable-trading-recovery-modal-for-missing-agent-wallet-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`
  - `api-submit-order-effect-opens-enable-trading-recovery-modal-when-agent-not-ready-locally-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`
  - `api-submit-order-effect-handles-runtime-rejections-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`
  - `api-submit-order-effect-blocks-mutations-while-spectate-mode-active-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`
  - `api-cancel-order-effect-shows-success-toast-and-refreshes-open-orders-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/cancel_test.cljs`
  - `api-cancel-order-effect-restores-optimistically-hidden-order-on-failure-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/cancel_test.cljs`
  - `api-cancel-order-effect-prunes-only-successful-orders-on-partial-batch-failure-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/cancel_test.cljs`
  - `api-cancel-order-effect-shows-twap-success-toast-and-refreshes-account-surfaces-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/cancel_test.cljs`
  - `api-cancel-order-effect-surfaces-twap-string-status-errors-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/cancel_test.cljs`
  - `api-cancel-order-effect-surfaces-twap-error-map-messages-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/cancel_test.cljs`
  - `api-cancel-order-effect-ignores-blank-twap-error-values-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/cancel_test.cljs`
  - `api-cancel-order-effect-blocks-mutations-while-spectate-mode-active-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/cancel_test.cljs`
  - `api-submit-position-tpsl-effect-validates-preconditions-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/position_tpsl_test.cljs`
  - `api-submit-position-tpsl-effect-success-resets-modal-and-refreshes-surfaces-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/position_tpsl_test.cljs`
  - `api-submit-position-tpsl-effect-handles-runtime-errors-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/position_tpsl_test.cljs`
  - `api-submit-position-margin-effect-validates-preconditions-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/position_margin_test.cljs`
  - `api-submit-position-margin-effect-success-resets-modal-and-refreshes-surfaces-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/position_margin_test.cljs`
  - `api-submit-position-margin-effect-surfaces-exchange-errors-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/position_margin_test.cljs`
  - `api-submit-position-margin-effect-handles-runtime-errors-test` -> `/hyperopen/test/hyperopen/core_bootstrap/order_effects/position_margin_test.cljs`

### Active Asset

- `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs`
  - `active-asset-row-symbol-fallback-test` -> `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
  - `active-asset-list-spot-id-market-resolution-fallback-test` -> `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs`
  - `active-asset-panel-dependency-state-prunes-closed-selector-roots-test` -> `/hyperopen/test/hyperopen/views/active_asset/vm_test.cljs`
  - `active-asset-panel-dependency-state-keeps-open-selector-state-and-market-fallback-test` -> `/hyperopen/test/hyperopen/views/active_asset/vm_test.cljs`
  - `asset-icon-spot-includes-chevron-test` -> `/hyperopen/test/hyperopen/views/active_asset/icon_button_test.cljs`
  - `active-asset-trigger-does-not-apply-hover-highlight-test` -> `/hyperopen/test/hyperopen/views/active_asset/icon_button_test.cljs`
  - `asset-icon-renders-neutral-surface-while-probing-and-registers-render-hook-test` -> `/hyperopen/test/hyperopen/views/active_asset/icon_button_test.cljs`
  - `asset-icon-probe-hook-dispatches-loaded-for-complete-images-test` -> `/hyperopen/test/hyperopen/views/active_asset/icon_button_test.cljs`
  - `asset-icon-probe-hook-dispatches-missing-for-complete-broken-images-test` -> `/hyperopen/test/hyperopen/views/active_asset/icon_button_test.cljs`
  - `asset-icon-renders-visible-image-when-icon-is-marked-loaded-test` -> `/hyperopen/test/hyperopen/views/active_asset/icon_button_test.cljs`
  - `asset-icon-falls-back-to-monogram-when-icon-is-known-missing-test` -> `/hyperopen/test/hyperopen/views/active_asset/icon_button_test.cljs`
  - `asset-icon-renders-namespaced-icon-for-component-markets-test` -> `/hyperopen/test/hyperopen/views/active_asset/icon_button_test.cljs`
  - `asset-icon-renders-cross-dex-alias-icon-when-primary-key-missing-test` -> `/hyperopen/test/hyperopen/views/active_asset/icon_button_test.cljs`
  - `active-asset-row-uses-app-shell-left-gutter-test` -> `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
  - `active-asset-row-applies-numeric-utility-to-live-values-test` -> `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
  - `active-asset-row-prioritizes-symbol-column-during-resize-test` -> `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
  - `active-asset-row-renders-dex-and-leverage-chips-test` -> `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
  - `active-asset-row-renders-coin-namespace-chip-when-dex-missing-test` -> `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
  - `active-asset-row-render-visible-branch-skips-hidden-renderer-test` -> `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
  - `select-asset-row-renders-mobile-empty-state-only-on-mobile-viewport-test` -> `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
  - `select-asset-row-renders-desktop-empty-state-only-on-desktop-viewport-test` -> `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
  - `mobile-active-asset-row-collapses-details-by-default-test` -> `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
  - `mobile-active-asset-row-renders-disclosure-panel-when-open-test` -> `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
  - `mobile-active-asset-row-renders-open-funding-tooltip-content-when-visible-test` -> `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
  - `active-asset-row-renders-24h-change-without-funding-rate-test` -> `/hyperopen/test/hyperopen/views/active_asset/row_test.cljs`
  - `active-asset-panel-passes-scroll-top-to-selector-wrapper-test` -> `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs`
  - `tooltip-click-pinnable-dismiss-target-clears-visible-state-test` -> `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_popover_test.cljs`
  - `tooltip-click-pinnable-trigger-toggles-pinned-state-test` -> `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_popover_test.cljs`
  - `tooltip-click-pinnable-renders-body-when-open-test` -> `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_popover_test.cljs`
  - `active-asset-row-skips-funding-tooltip-derivation-when-closed-test` -> `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs`
  - `active-asset-row-funding-tooltip-memoizes-by-summary-signature-test` -> `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs`
  - `active-asset-row-funding-tooltip-shows-position-projections-test` -> `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs`
  - `active-asset-row-funding-tooltip-short-position-shows-positive-payment-test` -> `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs`
  - `active-asset-row-funding-tooltip-uses-hypothetical-position-when-no-open-position-test` -> `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs`
  - `active-asset-row-funding-tooltip-uses-negative-hypothetical-value-for-short-direction-test` -> `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs`
  - `active-asset-row-funding-tooltip-parses-localized-hypothetical-value-input-test` -> `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs`
  - `active-asset-row-funding-tooltip-renders-predictability-metrics-test` -> `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs`
  - `active-asset-row-funding-tooltip-renders-predictability-loading-and-insufficient-copy-test` -> `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs`
  - `active-asset-row-funding-tooltip-uses-opaque-high-stack-surface-test` -> `/hyperopen/test/hyperopen/views/active_asset/funding_tooltip_model_test.cljs`

## Artifacts and Notes

Current size baseline:

- `/hyperopen/test/hyperopen/funding/effects_test.cljs`: `1851` lines, `49` tests
- `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`: `1388` lines, `28` tests
- `/hyperopen/test/hyperopen/views/active_asset_view_test.cljs`: `1266` lines, `40` tests

Expected new support namespaces:

- `/hyperopen/test/hyperopen/funding/test_support/effects.cljs`
- `/hyperopen/test/hyperopen/funding/test_support/hyperunit.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/order_effects/test_support.cljs`
- `/hyperopen/test/hyperopen/views/active_asset/test_support.cljs`

## Interfaces and Dependencies

No production interface changes are intended. This plan only changes test namespace topology, test-support organization, and the generated test-runner namespace list.

Expected new test namespaces:

- `hyperopen.funding.application.submit-effects-test`
- `hyperopen.funding.application.deposit-submit-test`
- `hyperopen.funding.application.hyperunit-query-test`
- `hyperopen.funding.application.hyperunit-submit-test`
- `hyperopen.funding.application.lifecycle-polling-test`
- `hyperopen.core-bootstrap.order-effects.submit-refresh-test`
- `hyperopen.core-bootstrap.order-effects.submit-failures-test`
- `hyperopen.core-bootstrap.order-effects.cancel-test`
- `hyperopen.core-bootstrap.order-effects.position-tpsl-test`
- `hyperopen.core-bootstrap.order-effects.position-margin-test`
- `hyperopen.views.active-asset.vm-test`
- `hyperopen.views.active-asset.icon-button-test`
- `hyperopen.views.active-asset.row-test`
- `hyperopen.views.active-asset.funding-tooltip-popover-test`
- `hyperopen.views.active-asset.funding-tooltip-model-test`

Plan revision note: 2026-03-20 01:16Z - Created the initial active ExecPlan, linked it to `hyperopen-gpuv`, and recorded the full migration matrix before implementation.
