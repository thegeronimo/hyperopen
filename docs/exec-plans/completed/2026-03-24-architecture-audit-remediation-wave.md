# Architecture Audit Remediation Wave

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked `bd` work for this plan is epic `hyperopen-glb1` with child tasks `hyperopen-glb1.1` through `hyperopen-glb1.6`, and `bd` remains the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

The 2026-03-24 architecture audit described a repo that is strong at runtime boundaries but inconsistent about semantic ownership. Some directories tell the truth, especially websocket and parts of funding, vaults, and trading. Other directories overpromise: files under `domain/` still own modal state, pagination state, sort state, or session-normalization concerns, and some non-view modules still depend on helpers under `views/**`. That mismatch is expensive because humans and agents both trust path semantics.

After this remediation wave, the repository should be easier to reason about and harder to regress. The tree should become semantically truthful again, `npm run check` should enforce the first architecture guardrails instead of relying on memory, non-view code should stop importing `views/**` helpers, major contexts should have local ownership maps, and the next split wave should attack the highest-cost hotspots only after those boundaries are honest. A contributor should be able to prove the outcome by seeing guardrails enforced in CI, by confirming the cited misnamed `domain` modules no longer own UI or session state, by confirming no non-view namespace imports generic helpers from `views/**`, by confirming the runtime catalog remains authoritative, and by passing `npm run check`, `npm test`, and `npm run test:websocket`.

## Progress

- [x] (2026-03-24 21:34 EDT) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and `/hyperopen/.agents/PLANS.md` to refresh planning, tracking, and multi-agent constraints before writing this wave plan.
- [x] (2026-03-24 21:34 EDT) Audited the current codebase state against the architecture audit claims: confirmed the cited misnamed `domain` files, confirmed reverse imports from `views/**`, captured current line counts for the central hubs and oversized hotspots, and checked the active ExecPlans that overlap the runtime hubs and large route surfaces.
- [x] (2026-03-24 21:34 EDT) Created and claimed `bd` epic `hyperopen-glb1` and created child tasks `hyperopen-glb1.1` through `hyperopen-glb1.6` so this plan has concrete execution tracking instead of a prose-only backlog.
- [x] (2026-03-25 08:59 EDT) Incorporated the user-provided implementation backlog into this plan, replacing the earlier high-level milestone outline with explicit backlog items `ARCH-01` through `SRP-06`, exact file moves, focused validation, and hard ordering rules.
- [x] (2026-03-25 09:44 EDT) `ARCH-01` (`hyperopen-glb1.3`): added `/hyperopen/dev/check_namespace_sizes.clj`, `/hyperopen/dev/check_namespace_boundaries.clj`, both Babashka regression suites, `/hyperopen/dev/namespace_size_exceptions.edn`, `/hyperopen/dev/namespace_boundary_exceptions.edn`, package wiring, and the required architecture/quality/debt-doc updates. Validation passed with `npm run check`, `npm test`, and `npm run test:websocket` after installing repo dependencies with `npm ci` and moving one unrelated closed ExecPlan out of `/active/`. Closed `hyperopen-glb1.3` in `bd`.
- [x] (2026-03-25 10:23 EDT) `DDD-01` (`hyperopen-glb1.1`): moved `/hyperopen/src/hyperopen/funding/domain/modal_state.cljs` to `/hyperopen/src/hyperopen/funding/application/modal_state.cljs`, updated `/hyperopen/src/hyperopen/funding/application/modal_actions.cljs` to use the new application owner, added `/hyperopen/test/hyperopen/funding/application/modal_state_test.cljs`, regenerated `/hyperopen/test/test_runner_generated.cljs`, and passed `npm test`, `npm run check`, and `npm run test:websocket`.
- [x] (2026-03-25 10:35 EDT) `DDD-02` (`hyperopen-glb1.1`): moved `/hyperopen/src/hyperopen/vaults/domain/ui_state.cljs` to `/hyperopen/src/hyperopen/vaults/application/ui_state.cljs`, created `/hyperopen/src/hyperopen/vaults/application/transfer_state.cljs`, removed modal defaults from `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`, updated every listed caller plus the stable `hyperopen.vaults.actions/default-vault-transfer-modal-state` facade, added `/hyperopen/test/hyperopen/vaults/application/ui_state_test.cljs` and `/hyperopen/test/hyperopen/vaults/application/transfer_state_test.cljs`, regenerated `/hyperopen/test/test_runner_generated.cljs`, and re-passed `npm test`, `npm run check`, and `npm run test:websocket`.
- [x] (2026-03-25 10:51 EDT) `DDD-03` (`hyperopen-glb1.1`): created `/hyperopen/src/hyperopen/api_wallets/application/ui_state.cljs` and `/hyperopen/src/hyperopen/api_wallets/application/form_policy.cljs`, reduced `/hyperopen/src/hyperopen/api_wallets/domain/policy.cljs` to row merge and sort helpers only, updated `/hyperopen/src/hyperopen/api_wallets/actions.cljs`, `/hyperopen/src/hyperopen/api_wallets/effects.cljs`, `/hyperopen/src/hyperopen/views/api_wallets/vm.cljs`, and `/hyperopen/src/hyperopen/state/app_defaults.cljs`, added focused application tests, regenerated `/hyperopen/test/test_runner_generated.cljs`, re-passed `npm test`, `npm run check`, and `npm run test:websocket`, and closed `hyperopen-glb1.1` in `bd`.
- [x] (2026-03-25 11:03 EDT) `DIP-01` (`hyperopen-glb1.4`): moved `/hyperopen/src/hyperopen/views/chart/hover.cljs` to `/hyperopen/src/hyperopen/ui/chart/hover.cljs` and `/hyperopen/src/hyperopen/views/account_info/sort_kernel.cljs` to `/hyperopen/src/hyperopen/ui/table/sort_kernel.cljs`, updated every listed caller, moved the sort-kernel regression suite to `/hyperopen/test/hyperopen/ui/table/sort_kernel_test.cljs`, added `/hyperopen/test/hyperopen/ui/chart/hover_test.cljs`, removed the three resolved boundary exceptions from `/hyperopen/dev/namespace_boundary_exceptions.edn`, regenerated `/hyperopen/test/test_runner_generated.cljs`, and re-passed `npm test`, `npm run check`, and `npm run test:websocket`.
- [x] (2026-03-25 11:31 EDT) `DIP-02` (`hyperopen-glb1.4`): extracted `/hyperopen/src/hyperopen/vaults/application/list_vm.cljs` and `/hyperopen/src/hyperopen/portfolio/application/metrics_bridge.cljs`, reduced `/hyperopen/src/hyperopen/views/vaults/vm.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/metrics_bridge.cljs` to thin facades, moved the vault route helpers into `/hyperopen/src/hyperopen/vaults/infrastructure/routes.cljs`, updated the listed non-view and view callers, added focused application/infrastructure regression suites, removed the three resolved boundary exceptions from `/hyperopen/dev/namespace_boundary_exceptions.edn`, regenerated `/hyperopen/test/test_runner_generated.cljs`, re-passed `npm test`, `npm run check`, and `npm run test:websocket`, and closed `hyperopen-glb1.4` in `bd`.
- [x] (2026-03-25 12:26 EDT) `AGENT-01` (`hyperopen-glb1.2`): added `/hyperopen/src/hyperopen/websocket/BOUNDARY.md`, `/hyperopen/src/hyperopen/funding/BOUNDARY.md`, `/hyperopen/src/hyperopen/vaults/BOUNDARY.md`, `/hyperopen/src/hyperopen/trading/BOUNDARY.md`, `/hyperopen/src/hyperopen/portfolio/BOUNDARY.md`, and `/hyperopen/src/hyperopen/account/BOUNDARY.md`, documenting ownership, stable public seams, allowed dependency directions, forbidden dependencies, key tests, and short routing recipes for each bounded context. Validation passed with `npm run lint:docs`, and `hyperopen-glb1.2` was closed in `bd`.
- [x] (2026-03-25 13:04 EDT) `OCP-01` (`hyperopen-glb1.5`): extracted `/hyperopen/src/hyperopen/schema/runtime_registration/{websocket,trade,portfolio,vaults,funding,funding_comparison,leaderboard,staking,api_wallets,spectate_mode,wallet}.cljs`, reduced `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs` from 444 lines to a 99-line assembler plus uniqueness validation only, preserved the assembled action/effect ids, and passed focused validation with `npm run test:runner:generate`, `npx shadow-cljs compile test`, and `node out/test.js --test=hyperopen.schema.contracts-coverage-test,hyperopen.runtime.registry-composition-test,hyperopen.runtime.wiring-test` before re-passing `npm run check`, `npm test`, and `npm run test:websocket`. `hyperopen-glb1.5` remains open because `SRP-01` and `SRP-06` are still blocked by active overlap in `hyperopen-v894`.
- [x] (2026-03-25 14:12 EDT) `SRP-01` (`hyperopen-glb1.5`): split `/hyperopen/src/hyperopen/runtime/action_adapters.cljs` into `/hyperopen/src/hyperopen/runtime/action_adapters/{navigation,leaderboard,wallet,spectate_mode,websocket,ws_diagnostics}.cljs`, split `/hyperopen/src/hyperopen/runtime/collaborators.cljs` into `/hyperopen/src/hyperopen/runtime/collaborators/{wallet,asset_selector,chart,account_history,spectate_mode,order,vaults,leaderboard,funding_comparison,staking}.cljs`, reduced the root facade or assembler files to 130 and 81 lines respectively, split the mixed runtime hub tests into focused `action_adapters/*.cljs` and `collaborators/*.cljs` suites while keeping thin contract coverage at the old roots, regenerated `/hyperopen/test/test_runner_generated.cljs`, passed focused validation with `npm run test:runner:generate`, `npx shadow-cljs compile test`, and `node out/test.js --test=hyperopen.runtime.action-adapters-test,hyperopen.runtime.action-adapters.navigation-test,hyperopen.runtime.action-adapters.wallet-test,hyperopen.runtime.action-adapters.websocket-test,hyperopen.runtime.action-adapters.ws-diagnostics-test,hyperopen.runtime.collaborators-test,hyperopen.runtime.collaborators.action-maps-test,hyperopen.runtime.wiring-test`, then re-passed `npm test`, `npm run check`, and `npm run test:websocket`. `hyperopen-glb1.5` remains open for `SRP-06`, and `SRP-02` became unblocked on 2026-03-25 14:40 EDT when the shared browser-QA plan closed after its governed reruns passed.
- [x] (2026-03-25 15:19 EDT) `SRP-02` (`hyperopen-glb1.6`): split `/hyperopen/src/hyperopen/views/portfolio_view.cljs` into `/hyperopen/src/hyperopen/views/portfolio/{format,low_confidence,header,summary_cards,chart_view,performance_metrics_view,account_tabs}.cljs`, reduced the root route namespace from 1325 lines to a 43-line composition-only entrypoint, added focused render coverage for the extracted modules, retired the stale `/hyperopen/dev/namespace_size_exceptions.edn` entry for the old hotspot root, passed focused validation with `npm run test:runner:generate`, `npx shadow-cljs compile test`, and `node out/test.js --test=hyperopen.views.portfolio-view-test,hyperopen.views.portfolio.header-test,hyperopen.views.portfolio.summary-cards-test,hyperopen.views.portfolio.chart-view-test,hyperopen.views.portfolio.performance-metrics-view-test`, passed the required UI validation with `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs`, the managed-local browser scenario bundle `scenario-2026-03-25T19-13-02-130Z-35cbb799`, and the governed design review bundle `design-review-2026-03-25T19-13-34-154Z-ffc86e65`, and re-passed `npm run check`, `npm test`, and `npm run test:websocket`. `hyperopen-glb1.6` remains open for `SRP-03`, `SRP-04`, and `SRP-05`, and the next ordered item is `SRP-03`, which became unblocked on 2026-03-25 15:59 EDT when `hyperopen-2614` closed after the final manual desktop verification passed.
- [x] (2026-03-25 16:52 EDT) `SRP-03` (`hyperopen-glb1.6`): split `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` into `/hyperopen/src/hyperopen/views/asset_selector/{controls,icons,rows,layout,runtime,processing}.cljs`, reduced the root facade to 38 lines, split the root-heavy selector tests into focused suites under `/hyperopen/test/hyperopen/views/asset_selector/`, retired the stale `/hyperopen/dev/namespace_size_exceptions.edn` entries for the old hotspot root and root test, passed focused validation with `npm run test:runner:generate`, `npx shadow-cljs compile test`, and `node out/test.js --test=hyperopen.views.asset-selector.controls-test,hyperopen.views.asset-selector.rows-test,hyperopen.views.asset-selector.runtime-test,hyperopen.views.asset-selector.processing-test,hyperopen.views.asset-selector-view-test,hyperopen.views.active-asset-view-test,hyperopen.views.trade-view.render-cache-test`, passed the required UI validation with `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "asset selector"`, the managed-local browser scenario bundle `scenario-2026-03-25T20-51-59-487Z-fe196849`, and the governed design review bundle `design-review-2026-03-25T20-52-33-293Z-f06a96ad`, and re-passed `npm run check`, `npm test`, and `npm run test:websocket`. `hyperopen-glb1.6` remains open for `SRP-04` and `SRP-05`, and the next ordered item is `SRP-04`.
- [x] (2026-03-25 17:10 EDT) `SRP-04` (`hyperopen-glb1.6`): split `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` into `/hyperopen/src/hyperopen/views/trade/order_form_{controls,feedback,footer}.cljs`, reduced the root facade to 182 lines while keeping `render-order-form` and `order-form-view` stable, added focused suites at `/hyperopen/test/hyperopen/views/trade/order_form_view/{controls,feedback,footer}_test.cljs`, regenerated `/hyperopen/test/test_runner_generated.cljs`, retired the stale root size exception and added the explicit follow-on exception for `/hyperopen/src/hyperopen/views/trade/order_form_controls.cljs`, passed focused validation with `npm run test:runner:generate`, `npx shadow-cljs compile test`, and `node out/test.js --test=hyperopen.views.trade.order-form-view-test,hyperopen.views.trade.order-form-view.controls-test,hyperopen.views.trade.order-form-view.feedback-test,hyperopen.views.trade.order-form-view.footer-test,hyperopen.views.trade.order-form-view.entry-mode-test,hyperopen.views.trade.order-form-view.metrics-and-submit-test,hyperopen.views.trade.order-form-view.scale-preview-test,hyperopen.views.trade.order-form-view.size-and-slider-test,hyperopen.views.trade.order-form-view.styling-contract-test,hyperopen.views.workbench-render-seams-test,hyperopen.views.trade-view.render-cache-test,hyperopen.views.trade-view.mobile-surface-test`, passed the required UI validation with `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "order submit"`, the managed-local browser scenario bundle `scenario-2026-03-25T21-07-48-619Z-a6659670`, and the governed design review bundle `design-review-2026-03-25T21-08-08-082Z-738e1c5e`, and re-passed `npm run check`, `npm test`, and `npm run test:websocket`. `hyperopen-glb1.6` now remains open only for `SRP-05`, and the next ordered item is `SRP-05`.
- [x] (2026-03-25 17:45 EDT) `SRP-05` (`hyperopen-glb1.6`): split `/hyperopen/src/hyperopen/funding/application/modal_vm.cljs` into `/hyperopen/src/hyperopen/funding/application/modal_vm/{context,async,lifecycle,amounts,presentation,models}.cljs`, reduced the root orchestrator to 20 lines, split the modal-VM tests into focused suites under `/hyperopen/test/hyperopen/funding/application/modal_vm/`, retired the stale modal-VM size exception from `/hyperopen/dev/namespace_size_exceptions.edn`, passed focused validation with `npm run test:runner:generate`, `npx shadow-cljs compile test`, and `node out/test.js --test=hyperopen.funding.application.modal-vm-test,hyperopen.funding.application.modal-vm.context-test,hyperopen.funding.application.modal-vm.async-test,hyperopen.funding.application.modal-vm.lifecycle-test,hyperopen.funding.application.modal-vm.amounts-test,hyperopen.funding.application.modal-vm.presentation-test,hyperopen.funding.application.modal-vm.models-test,hyperopen.funding.actions-test,hyperopen.views.funding-modal-test,hyperopen.views.workbench-render-seams-test,hyperopen.telemetry.console-preload-test`, passed the required UI validation with `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "funding modal deposit flow"`, the managed-local browser scenario bundle `scenario-2026-03-25T21-41-09-994Z-446c7897`, and the governed design review bundle `design-review-2026-03-25T21-43-08-155Z-63f6a1a6`, re-passed `npm run check`, `npm test`, and `npm run test:websocket`, and closed `hyperopen-glb1.6` in `bd`. The next ordered item is `SRP-06`.
- [x] (2026-03-25 18:17 EDT) `SRP-06` (`hyperopen-glb1.5`): split `/hyperopen/src/hyperopen/schema/contracts.cljs` into `/hyperopen/src/hyperopen/schema/contracts/{common,action_args,effect_args,state,assertions}.cljs`, reduced the root public facade to 50 lines while preserving the stable `assert-*`, `validation-enabled?`, and contracted-id exports, split `/hyperopen/test/hyperopen/schema/contracts_test.cljs` into focused suites under `/hyperopen/test/hyperopen/schema/contracts/`, regenerated `/hyperopen/test/test_runner_generated.cljs`, retired the stale `/hyperopen/dev/namespace_size_exceptions.edn` entries for the old contracts root and root test, passed focused validation with `npm run test:runner:generate`, `npx shadow-cljs compile test`, and `node out/test.js --test=hyperopen.schema.contracts-test,hyperopen.schema.contracts.state-test,hyperopen.schema.contracts.action-args-test,hyperopen.schema.contracts.effect-args-test,hyperopen.schema.contracts.assertions-test,hyperopen.schema.contracts-coverage-test,hyperopen.runtime.registry-composition-test,hyperopen.runtime.wiring-test`, and re-passed `npm run check`, `npm test`, and `npm run test:websocket`. `hyperopen-glb1.5` is now ready to close, and this remediation wave has no remaining backlog items.

## Surprises & Discoveries

- Observation: `SRP-06` completed the runtime hotspot decomposition wave by retiring `/hyperopen/src/hyperopen/schema/contracts.cljs` as the last shared mixed hub left after `SRP-01`.
  Evidence: `wc -l` on 2026-03-25 now reports 81 lines for `/hyperopen/src/hyperopen/runtime/collaborators.cljs`, 130 for `/hyperopen/src/hyperopen/runtime/action_adapters.cljs`, 50 for the root `/hyperopen/src/hyperopen/schema/contracts.cljs` facade, and 407/207/151/130/82 lines for `/hyperopen/src/hyperopen/schema/contracts/{action_args,common,state,effect_args,assertions}.cljs`.

- Observation: the audit’s semantic-boundary examples are still present exactly where cited.
  Evidence: `/hyperopen/src/hyperopen/funding/domain/modal_state.cljs`, `/hyperopen/src/hyperopen/vaults/domain/ui_state.cljs`, `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`, and `/hyperopen/src/hyperopen/api_wallets/domain/policy.cljs` still exist, and the current source search still finds modal, pagination, sort, chart-series, or agent-session concerns in those namespaces.

- Observation: the strongest remaining architecture smell is now confined to the outer app/bootstrap and console-preload bridges rather than reusable helpers or shared view-model imports.
  Evidence: after `DIP-01` and `DIP-02`, `/hyperopen/dev/namespace_boundary_exceptions.edn` carries only three entries: `/hyperopen/src/hyperopen/app/bootstrap.cljs` -> `hyperopen.views.app-view`, plus the two `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` imports of `hyperopen.views.account-info.vm` and `hyperopen.views.trade.order-form-vm`.

- Observation: runtime registration authority is already institutionalized in the current tree, so the right follow-on is localization and decomposition, not a second attempt at inventing catalog authority.
  Evidence: the repo already has `/hyperopen/docs/architecture-decision-records/0019-command-action-catalog-authority.md`, `/hyperopen/docs/architecture-decision-records/0023-action-effect-runtime-registration-catalog-authority.md`, and the completed registration unification plan under `/hyperopen/docs/exec-plans/completed/2026-03-03-action-effect-registration-contract-metadata-unification.md`.

- Observation: no local boundary-map files exist under `/hyperopen/src/hyperopen` today, so contributors still have to start from global docs even for well-bounded contexts.
  Evidence: `find /hyperopen/src/hyperopen -name 'BOUNDARY.md' -o -name 'README.md'` returned no matches on 2026-03-24.

- Observation: after the `hyperopen-v894`, `hyperopen-v7jc`, and `hyperopen-2614` closeouts, there are no remaining overlap blockers on the next ordered hotspot slice.
  Evidence: `hyperopen-v894`, `hyperopen-6len`, `hyperopen-v7jc`, and `hyperopen-2614` are all closed, `SRP-01` and `SRP-02` have already landed, and no active ExecPlan now overlaps `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`.

- Observation: the live architecture-debt baseline shrank again at the end of the wave because the contracts split retired both stale schema-contract exceptions without introducing any new oversized namespaces.
  Evidence: after `SRP-06`, `/hyperopen/dev/namespace_size_exceptions.edn` now carries 79 entries generated from current `src/**/*.cljs` and `test/**/*.cljs` files above 500 lines, while `/hyperopen/dev/namespace_boundary_exceptions.edn` remains down at 3 non-view `hyperopen.views.*` import edges after `DIP-01` and `DIP-02`.

- Observation: the `DIP-02` vault-list extraction made semantic ownership truthful, but it did not finish the size split for that cluster.
  Evidence: `wc -l` now reports 610 lines for `/hyperopen/src/hyperopen/vaults/application/list_vm.cljs` and only 21 lines for `/hyperopen/src/hyperopen/views/vaults/vm.cljs`, so the size exception had to move to the new application owner instead of staying behind on the view facade.

- Observation: the repo’s documented `npm test -- <namespace>` flow does not actually filter test namespaces in this worktree, but focused validation is still available through the generated runner.
  Evidence: `npm test -- hyperopen.portfolio.actions-test hyperopen.vaults.actions-test hyperopen.vaults.detail.activity-test hyperopen.ui.table.sort-kernel-test hyperopen.ui.chart.hover-test` printed `Unknown arg:` for each requested namespace and then ran the full `npm test` suite, which passed with 2684 tests and 14262 assertions. After `npm run test:runner:generate` and `npx shadow-cljs compile test`, `node out/test.js --test=hyperopen.schema.contracts-coverage-test,hyperopen.runtime.registry-composition-test,hyperopen.runtime.wiring-test` then ran 13 tests containing 43 assertions with 0 failures for the `OCP-01` slice.

- Observation: `npm run check` was initially blocked by an unrelated stale active ExecPlan, not by the new guardrails.
  Evidence: `/hyperopen/dev/check_docs.clj` failed on `/hyperopen/docs/exec-plans/active/2026-03-24-portfolio-interaction-state-qa-blind-spots.md`, and `/usr/local/bin/bd show hyperopen-6len --json` confirmed the parent epic plus both child tasks were already closed before the file was moved to `/hyperopen/docs/exec-plans/completed/2026-03-24-portfolio-interaction-state-qa-blind-spots.md`.

- Observation: the funding modal-state move was lower-risk than the audit prose implied because the misleading domain namespace only had one production caller.
  Evidence: before the move, `rg -n "funding\\.domain\\.modal-state|funding\\.application\\.modal-state" src test` showed `/hyperopen/src/hyperopen/funding/application/modal_actions.cljs` as the only production import of the old namespace.

- Observation: the new namespace-size guardrail immediately shaped the DDD-02 test placement.
  Evidence: the first `npm run check` after adding a facade regression to `/hyperopen/test/hyperopen/vaults/actions_test.cljs` failed with `[size-exception-exceeded] test/hyperopen/vaults/actions_test.cljs - namespace has 536 lines; exception allows at most 526`, so the regression was moved into the new focused `/hyperopen/test/hyperopen/vaults/application/transfer_state_test.cljs` instead of loosening the exception budget.

- Observation: the API-wallet split did not need a compatibility shim once the moved helpers were isolated, because the true domain slice turned out to be much smaller than the audit’s mixed-ownership baseline suggested.
  Evidence: after DDD-03, `wc -l` reports 54 lines for `/hyperopen/src/hyperopen/api_wallets/domain/policy.cljs`, 121 for `/hyperopen/src/hyperopen/api_wallets/application/ui_state.cljs`, and 44 for `/hyperopen/src/hyperopen/api_wallets/application/form_policy.cljs`, while `rg -n "api-wallets\\.domain\\.policy" src test` now shows only the slim domain file plus the callers that still use row merge and sort helpers.

## Decision Log

- Decision: adopt the user-provided backlog ordering as the execution contract for this wave.
  Rationale: the provided plan is more specific than the original audit summary and gives exact file moves, direct tests, and dependency ordering. The repo benefits more from a precise sequence than from another abstract roadmap.
  Date/Author: 2026-03-25 / Codex

- Decision: do `ARCH-01` first and complete `DDD-01` through `DIP-02` before any hotspot split.
  Rationale: the audit is explicit that semantic truthfulness matters more than arbitrary file chopping. Locking boundaries first prevents the split wave from recreating the same leaks under smaller filenames.
  Date/Author: 2026-03-25 / Codex

- Decision: keep one parent epic with six child tasks instead of exploding `bd` into a second-level ticket tree immediately.
  Rationale: the new backlog maps cleanly onto the existing child-task grouping: `ARCH-01` -> `hyperopen-glb1.3`, `DDD-01` through `DDD-03` -> `hyperopen-glb1.1`, `DIP-01` and `DIP-02` -> `hyperopen-glb1.4`, `AGENT-01` -> `hyperopen-glb1.2`, `OCP-01` plus `SRP-01` plus `SRP-06` -> `hyperopen-glb1.5`, and `SRP-02` through `SRP-05` -> `hyperopen-glb1.6`.
  Date/Author: 2026-03-25 / Codex

- Decision: preserve stable public facades unless all callers move in the same ticket.
  Rationale: the completed funding, vaults, portfolio-vm, effect-adapter, header, and funding-modal splits already proved that compatibility-first decomposition is the safest house pattern in this repo. The backlog provided by the user explicitly reinforces that rule.
  Date/Author: 2026-03-25 / Codex

- Decision: any new or moved test namespace must regenerate `/hyperopen/test/test_runner_generated.cljs`.
  Rationale: the user-provided backlog explicitly calls this out, and the repository already depends on the generated test runner for deterministic CLJS test discovery.
  Date/Author: 2026-03-25 / Codex

- Decision: enforce `hyperopen.views.*` imports through one general non-view boundary registry, while keeping `domain/` -> `hyperopen.views.*` as a hard failure with no exception escape hatch.
  Rationale: the audit’s strongest correctness claim is that `domain/` must stay semantically truthful, but the current tree still has a small number of non-view bridges that need time-bounded cleanup in `DIP-01` and `DIP-02`.
  Date/Author: 2026-03-25 / Codex

- Decision: seed the size guardrail from the full current `src/**/*.cljs` and `test/**/*.cljs` baseline instead of only the headline hotspots called out in the audit prose.
  Rationale: the architecture rule is repository-wide, and partial seeding would let unrelated oversized namespaces stay invisible until they surprise a later contributor.
  Date/Author: 2026-03-25 / Codex

- Decision: keep `app/bootstrap` and `telemetry/console_preload` as explicit temporary boundary exceptions in `ARCH-01` instead of blocking the guardrail rollout on new extraction work.
  Rationale: the current backlog already schedules the higher-value `DIP-01` and `DIP-02` removals, while these outer-boundary bridges need follow-up design work that would otherwise delay the first CI guardrail landing.
  Date/Author: 2026-03-25 / Codex

- Decision: move the funding modal-state namespace outright to `application/` instead of leaving a compatibility alias under `domain/`.
  Rationale: no production callers besides `modal_actions.cljs` depended on the old path, and keeping a stub under `domain/` would continue advertising the wrong owner for modal UI state.
  Date/Author: 2026-03-25 / Codex

- Decision: keep `hyperopen.vaults.domain.transfer-policy/vault-transfer-preview` independent of the new application-owned transfer-state namespace by treating modal input as partial data instead of importing the application default back into `domain/`.
  Rationale: DDD-02 is about semantic truthfulness, and reintroducing an application dependency into `domain/transfer_policy.cljs` would have preserved the same ownership lie under a different filename.
  Date/Author: 2026-03-25 / Codex

- Decision: satisfy the stable-facade regression for `hyperopen.vaults.actions/default-vault-transfer-modal-state` in the new focused transfer-state test instead of expanding `/hyperopen/test/hyperopen/vaults/actions_test.cljs` or raising its size exception.
  Rationale: `ARCH-01` is already live, and bypassing the first size failure by widening the exception would undercut the point of landing the guardrail one ticket earlier.
  Date/Author: 2026-03-25 / Codex

- Decision: keep `hyperopen.api-wallets.domain.policy/sorted-rows` in `domain/` by making callers normalize sort state through `/hyperopen/src/hyperopen/api_wallets/application/ui_state.cljs` instead of importing the new application normalization helpers back into `domain/`.
  Rationale: DDD-03 is the last semantic-boundary cleanup in `hyperopen-glb1.1`, and preserving a `domain -> application` edge just to reuse sort normalization would have recreated the same ownership confusion the ticket is supposed to retire.
  Date/Author: 2026-03-25 / Codex

- Decision: move the reusable hover and table-sort kernels into shared `hyperopen.ui.*` namespaces instead of duplicating them under portfolio, vaults, or account-info ownership.
  Rationale: `DIP-01` is a dependency-direction cleanup, and these helpers are already shared, pure, and UI-adjacent; a neutral `ui/` namespace removes the reverse imports without introducing a new bounded-context ownership lie.
  Date/Author: 2026-03-25 / Codex

- Decision: keep `/hyperopen/src/hyperopen/views/vaults/vm.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/metrics_bridge.cljs` as thin facades after `DIP-02` instead of forcing every remaining view caller to move in the same ticket.
  Rationale: `DIP-02` is about dependency direction, not a wholesale rename of every view-facing API; leaving thin compatibility facades keeps the non-view boundary truthful while containing the blast radius of the extraction.
  Date/Author: 2026-03-25 / Codex

## Outcomes & Retrospective

`ARCH-01`, `DDD-01`, `DDD-02`, `DDD-03`, `DIP-01`, `DIP-02`, `AGENT-01`, `OCP-01`, `SRP-01`, `SRP-02`, `SRP-03`, `SRP-04`, `SRP-05`, and `SRP-06` are now complete. The repository no longer relies on memory for the first architecture rules, funding no longer keeps modal UI state under a misleading `domain/` namespace, vaults no longer keeps list/detail UI state or transfer-modal defaults under `domain/`, API wallets no longer keeps modal state, generated state, or agent-session-driven form validation under `domain/`, the shared hover/sort kernels no longer live under misleading `views/**` namespaces, the remaining non-view callers no longer depend on `hyperopen.views.vaults.vm` or `hyperopen.views.portfolio.vm.metrics-bridge`, runtime registration authoring no longer requires editing one 444-line mixed catalog for every feature row, the runtime action/collaborator assembly no longer requires editing two 300-400 line mixed hubs for unrelated changes, the asset selector no longer keeps render, scroll-window, cache, and mobile/desktop layout logic collapsed into one 1,200-line route namespace, the order form no longer keeps control widgets, derived feedback, spectate affordances, fee copy, and footer metrics collapsed into one 992-line view root, the funding modal no longer keeps context builders, async state derivation, lifecycle panels, amount/ETA derivation, presentation copy, and final view assembly collapsed into one 940-line application namespace, and schema contracts no longer keep runtime drift checks, low-level specs, action/effect payload contracts, app-state contracts, and public assertions collapsed into one 998-line mixed hub. `npm run check` now enforces two concrete guardrails: oversized `.cljs` namespaces require a structured exception entry in `/hyperopen/dev/namespace_size_exceptions.edn`, and non-view imports of `hyperopen.views.*` require a structured exception entry in `/hyperopen/dev/namespace_boundary_exceptions.edn` unless the importer lives under `domain/`, which is now a hard failure.

The result reduced coordination complexity again. The repo still carries 79 size exceptions and 3 boundary exceptions, so the codebase is not yet simple in aggregate, but the debt is explicit, time-bounded, and enforced in CI instead of being hidden in prose. `DIP-02` retired the remaining `hyperopen-glb1.4` reverse imports by moving the shared vault list model into `/hyperopen/src/hyperopen/vaults/application/list_vm.cljs`, moving the shared portfolio metrics bridge into `/hyperopen/src/hyperopen/portfolio/application/metrics_bridge.cljs`, and leaving the old view namespaces as thin facades for compatibility. `AGENT-01` then added local ownership maps directly under the six major bounded contexts so contributors can answer “what owns this?” and “where does this change go?” from the nearest subtree instead of starting from global docs. `OCP-01` then localized runtime registration authoring into `/hyperopen/src/hyperopen/schema/runtime_registration/*.cljs` while keeping `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs` as the single assembled authority and shrinking that catalog from 444 lines to 99. `SRP-01` then split `/hyperopen/src/hyperopen/runtime/action_adapters.cljs` and `/hyperopen/src/hyperopen/runtime/collaborators.cljs` into submodules that match the established `runtime/effect_adapters/**` pattern, leaving the root facades at 130 and 81 lines while preserving the public runtime surface. `SRP-02` then split `/hyperopen/src/hyperopen/views/portfolio_view.cljs` into seven render-focused modules and reduced the root route namespace to 43 lines while preserving the existing data-role contracts and passing the required Playwright plus governed browser-QA passes. `SRP-03` then split `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` into focused controls, rows, layout, runtime, processing, and icon modules, reduced the root facade to 38 lines, preserved the public selector seams, and retired the stale size exceptions for the old root and root test. `SRP-04` then split `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` into focused controls, feedback, and footer modules, reduced the root facade to 182 lines, preserved the public `render-order-form` and `order-form-view` seams, and moved the remaining order-form control debt into one explicit `order_form_controls.cljs` exception instead of leaving it hidden in a single mixed root namespace. `SRP-05` then split `/hyperopen/src/hyperopen/funding/application/modal_vm.cljs` into focused context, async, lifecycle, amounts, presentation, and model modules, reduced the public root to a 20-line orchestration-only facade, split the modal VM tests into focused suites, and retired the stale root size exception instead of leaving the mixed funding pipeline hidden in one 940-line namespace. `SRP-06` then split `/hyperopen/src/hyperopen/schema/contracts.cljs` into focused common, action-args, effect-args, state, and assertion owners, reduced the public root to a 50-line facade, split the contracts-heavy regression coverage into focused suites under `/hyperopen/test/hyperopen/schema/contracts/`, and retired the stale contracts size exceptions instead of leaving the last runtime hotspot hidden in one 998-line namespace. `hyperopen-glb1.5` is now ready to close in `bd`, and the remediation-wave backlog is complete.

## Context and Orientation

This plan uses a few terms in a very specific way.

A "semantically truthful" namespace means the path matches the real ownership. A file under `domain/` should own pure business rules, parsing, normalization, or invariants. It should not own modal open state, search input text, sort direction, page size, chart-series selection, dropdown state, or session normalization that depends on application-specific collaborators. A file under `views/` should be presentation-specific enough that non-view code does not need to import it.

A "hub file" means a central assembly file that must be edited for many unrelated changes. Earlier in this wave that risk was concentrated in `runtime/collaborators.cljs`, `runtime/action_adapters.cljs`, `schema/runtime_registration_catalog.cljs`, and `schema/contracts.cljs`. After `OCP-01` and `SRP-01`, the main shared runtime hub still left is `schema/contracts.cljs`.

A "compatibility facade" means a stable public namespace that keeps existing callers working while delegating real behavior to smaller owner modules. This repo already does that successfully in several areas, including funding, runtime effect adapters, portfolio VM, vault actions, and the header view.

The specific misnamed `domain/` files that motivated `DDD-01` through `DDD-03` are now retired, and `DIP-01` plus `DIP-02` have already removed the reusable helper and shared view-model imports from non-view code. The unfinished semantic-boundary work for this plan is now limited to the remaining outer-boundary `views/**` bridges described below, plus the hotspot splits that still need those honest boundaries as a prerequisite.

The strongest remaining reverse imports from `views/**` that this plan must remove are:

- `/hyperopen/src/hyperopen/app/bootstrap.cljs` -> `hyperopen.views.app-view`
- `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` -> `hyperopen.views.account-info.vm`
- `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` -> `hyperopen.views.trade.order-form-vm`

The current oversized hotspots that this plan will address only after the boundaries are honest are:

- `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` (1252 LOC)
- `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` (992 LOC)
- `/hyperopen/src/hyperopen/views/account_info_view.cljs` (866 LOC)
- `/hyperopen/src/hyperopen/funding/application/modal_vm.cljs` (940 LOC)
- `/hyperopen/src/hyperopen/api/projections.cljs` (927 LOC)
- `/hyperopen/src/hyperopen/schema/contracts.cljs` (998 LOC)

The repo already contains strong precedent for how to do this safely:

- `/hyperopen/docs/exec-plans/completed/2026-03-03-funding-bounded-context-domain-application-infrastructure-split.md`
- `/hyperopen/docs/exec-plans/completed/2026-03-04-runtime-effect-adapters-subdomain-decomposition.md`
- `/hyperopen/docs/exec-plans/completed/2026-03-06-portfolio-vm-facade-and-runtime-extraction.md`
- `/hyperopen/docs/exec-plans/completed/2026-03-06-vaults-actions-solid-ddd-decomposition.md`
- `/hyperopen/docs/exec-plans/completed/2026-03-22-header-view-ddd-refactor.md`
- `/hyperopen/docs/exec-plans/completed/2026-03-23-funding-modal-view-module-split.md`

The deferred plan `/hyperopen/docs/exec-plans/deferred/2026-02-25-file-size-guardrail-exceptions-splitting-strategy-maintainability.md` is also part of the baseline. This wave should reuse its namespace-size checker, exception-registry, and `npm run check` integration ideas instead of re-planning them from scratch.

## Plan of Work

The execution order below is mandatory.

- Do `ARCH-01` first.
- Do `DDD-01` through `DIP-02` before any hotspot split.
- Keep existing public facades unless all callers move in the same ticket.
- Any new or moved test namespace means regenerating `/hyperopen/test/test_runner_generated.cljs`.

### ARCH-01 — Enforce Architecture Guardrails In CI

Depends on: nothing. Effort: small. Tracker: `hyperopen-glb1.3`.

Create:

- `/hyperopen/dev/check_namespace_sizes.clj`
- `/hyperopen/dev/check_namespace_sizes_test.clj`
- `/hyperopen/dev/check_namespace_boundaries.clj`
- `/hyperopen/dev/check_namespace_boundaries_test.clj`
- `/hyperopen/dev/namespace_size_exceptions.edn`
- `/hyperopen/dev/namespace_boundary_exceptions.edn`

Modify:

- `/hyperopen/package.json`
- `/hyperopen/ARCHITECTURE.md`
- `/hyperopen/docs/QUALITY_SCORE.md`
- `/hyperopen/docs/exec-plans/tech-debt-tracker.md`

Acceptance:

- `npm run check` fails on forbidden imports such as `domain -> hyperopen.views.*`.
- `npm run check` fails on oversized namespaces without an explicit exception entry.
- Exception entries require `:path`, `:owner`, `:reason`, `:max-lines`, and `:retire-by`.
- Checker tests exist and follow the same pattern as `/hyperopen/dev/check_docs_test.clj`.

Focused validation:

- `npm run check`

### DDD-01 — Move Funding Modal State Out Of `domain`

Depends on: `ARCH-01`. Effort: small. Tracker: `hyperopen-glb1.1`.

Move:

- `/hyperopen/src/hyperopen/funding/domain/modal_state.cljs`
  -> `/hyperopen/src/hyperopen/funding/application/modal_state.cljs`

Modify:

- `/hyperopen/src/hyperopen/funding/application/modal_actions.cljs`

Add:

- `/hyperopen/test/hyperopen/funding/application/modal_state_test.cljs`

Acceptance:

- No file under `/hyperopen/src/hyperopen/funding/domain/` owns modal UI fields like `:open?`, `:anchor`, `:deposit-search-input`, `:withdraw-search-input`, `:submitting?`, or `:error`.
- `hyperopen.funding.application.modal-actions/default-funding-modal-state` remains stable.
- `hyperopen.funding.application.modal-actions/normalize-modal-state` still produces the same shape.

Focused validation:

- `npm test -- hyperopen.funding.actions-test`
- `npm test -- hyperopen.funding.application.modal-vm-test`

### DDD-02 — Clean Vault UI State Out Of `domain`

Depends on: `ARCH-01`. Effort: medium. Tracker: `hyperopen-glb1.1`.

Move:

- `/hyperopen/src/hyperopen/vaults/domain/ui_state.cljs`
  -> `/hyperopen/src/hyperopen/vaults/application/ui_state.cljs`

Create:

- `/hyperopen/src/hyperopen/vaults/application/transfer_state.cljs`

Move `default-vault-transfer-modal-state` from:

- `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`
  -> `/hyperopen/src/hyperopen/vaults/application/transfer_state.cljs`

Update callers:

- `/hyperopen/src/hyperopen/vaults/infrastructure/persistence.cljs`
- `/hyperopen/src/hyperopen/vaults/infrastructure/preview_cache.cljs`
- `/hyperopen/src/hyperopen/vaults/application/list_commands.cljs`
- `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs`
- `/hyperopen/src/hyperopen/vaults/application/transfer_commands.cljs`
- `/hyperopen/src/hyperopen/vaults/actions.cljs`
- `/hyperopen/src/hyperopen/state/app_defaults.cljs`
- `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs`
- `/hyperopen/src/hyperopen/views/vaults/vm.cljs`
- `/hyperopen/src/hyperopen/views/vaults/detail/transfer.cljs`
- `/hyperopen/src/hyperopen/vaults/effects.cljs`

Add:

- `/hyperopen/test/hyperopen/vaults/application/ui_state_test.cljs`
- `/hyperopen/test/hyperopen/vaults/application/transfer_state_test.cljs`

Acceptance:

- `/hyperopen/src/hyperopen/vaults/domain/ui_state.cljs` no longer exists.
- `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` contains transfer policy only, not modal defaults.
- No file under `/hyperopen/src/hyperopen/vaults/domain/` owns `:open?`, `:submitting?`, `:error`, page-size dropdown state, tab state, or chart-series UI state.
- `hyperopen.vaults.actions/default-vault-transfer-modal-state` remains stable as a public facade export.

Focused validation:

- `npm test -- hyperopen.vaults.domain.transfer-policy-test`
- `npm test -- hyperopen.vaults.application.transfer-commands-test`
- `npm test -- hyperopen.vaults.actions-test`
- `npm test -- hyperopen.views.vaults.vm-test`
- `npm test -- hyperopen.views.vaults.detail-vm-test`

### DDD-03 — Split API-Wallet UI And Session Validation Out Of `domain`

Depends on: `ARCH-01`. Effort: medium. Tracker: `hyperopen-glb1.1`.

Create:

- `/hyperopen/src/hyperopen/api_wallets/application/ui_state.cljs`
- `/hyperopen/src/hyperopen/api_wallets/application/form_policy.cljs`

Move from `/hyperopen/src/hyperopen/api_wallets/domain/policy.cljs`
to `/hyperopen/src/hyperopen/api_wallets/application/ui_state.cljs`:

- `default-sort-state`
- `default-form`
- `default-modal-state`
- `default-generated-state`
- `normalize-sort-column`
- `normalize-sort-direction`
- `normalize-sort-state`
- `next-sort-state`
- `normalize-form-field`
- `normalize-form-value`

Move from `/hyperopen/src/hyperopen/api_wallets/domain/policy.cljs`
to `/hyperopen/src/hyperopen/api_wallets/application/form_policy.cljs`:

- `form-errors`
- `form-valid?`
- `first-form-error`
- `generated-private-key`
- `valid-until-preview-ms`

Keep in `/hyperopen/src/hyperopen/api_wallets/domain/policy.cljs`:

- `approval-name-for-row`
- `merged-rows`
- `sorted-rows`
- row comparison helpers only

Update callers:

- `/hyperopen/src/hyperopen/api_wallets/actions.cljs`
- `/hyperopen/src/hyperopen/api_wallets/effects.cljs`
- `/hyperopen/src/hyperopen/views/api_wallets/vm.cljs`
- `/hyperopen/src/hyperopen/state/app_defaults.cljs`

Add:

- `/hyperopen/test/hyperopen/api_wallets/application/ui_state_test.cljs`
- `/hyperopen/test/hyperopen/api_wallets/application/form_policy_test.cljs`

Acceptance:

- `/hyperopen/src/hyperopen/api_wallets/domain/policy.cljs` no longer requires `hyperopen.wallet.agent-session`.
- `/hyperopen/src/hyperopen/api_wallets/domain/policy.cljs` no longer owns modal state, generated state, or raw form state.
- Existing public behavior in API-wallet actions and views stays the same.

Focused validation:

- `npm test -- hyperopen.api-wallets.domain.policy-test`
- `npm test -- hyperopen.api-wallets.actions-test`
- `npm test -- hyperopen.api-wallets.effects-test`
- `npm test -- hyperopen.views.api-wallets.vm-test`

### DIP-01 — Move Generic Helpers Out Of `views/**`

Depends on: `DDD-02`. Effort: small. Tracker: `hyperopen-glb1.4`.

Move:

- `/hyperopen/src/hyperopen/views/chart/hover.cljs`
  -> `/hyperopen/src/hyperopen/ui/chart/hover.cljs`
- `/hyperopen/src/hyperopen/views/account_info/sort_kernel.cljs`
  -> `/hyperopen/src/hyperopen/ui/table/sort_kernel.cljs`

Update callers:

- `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs`
- `/hyperopen/src/hyperopen/portfolio/actions.cljs`
- `/hyperopen/src/hyperopen/vaults/detail/activity.cljs`
- `/hyperopen/src/hyperopen/views/account_info/positions_vm.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`

Move tests:

- `/hyperopen/test/hyperopen/views/account_info/sort_kernel_test.cljs`
  -> `/hyperopen/test/hyperopen/ui/table/sort_kernel_test.cljs`

Add:

- `/hyperopen/test/hyperopen/ui/chart/hover_test.cljs`

Acceptance:

- No non-view namespace requires `hyperopen.views.chart.hover`.
- No non-view namespace requires `hyperopen.views.account-info.sort-kernel`.
- The new namespaces are view-agnostic and have direct tests.

Focused validation:

- `npm test -- hyperopen.portfolio.actions-test`
- `npm test -- hyperopen.vaults.actions-test`
- `npm test -- hyperopen.vaults.detail.activity-test`
- `npm test -- hyperopen.ui.table.sort-kernel-test`
- `npm test -- hyperopen.ui.chart.hover-test`

### DIP-02 — Remove The Remaining Non-View Dependencies On `views/**`

Depends on: `DIP-01`. Effort: large. Tracker: `hyperopen-glb1.4`.

Create:

- `/hyperopen/src/hyperopen/vaults/application/list_vm.cljs`
- `/hyperopen/src/hyperopen/portfolio/application/metrics_bridge.cljs`

Move pure list-model logic, including the public builders `vault-list-vm` and `build-startup-preview-record`, out of:

- `/hyperopen/src/hyperopen/views/vaults/vm.cljs`
  -> `/hyperopen/src/hyperopen/vaults/application/list_vm.cljs`

Move the full contents of:

- `/hyperopen/src/hyperopen/views/portfolio/vm/metrics_bridge.cljs`
  -> `/hyperopen/src/hyperopen/portfolio/application/metrics_bridge.cljs`

Make the old view files thin facades unless all callers move in the same ticket.

Move route helpers out of `/hyperopen/src/hyperopen/views/vaults/vm.cljs` by adding:

- `vault-route?`
- `vault-detail-route?`
- `selected-vault-address`

to:

- `/hyperopen/src/hyperopen/vaults/infrastructure/routes.cljs`

Update callers:

- `/hyperopen/src/hyperopen/vaults/infrastructure/preview_cache.cljs`
- `/hyperopen/src/hyperopen/route_modules.cljs`
- `/hyperopen/src/hyperopen/vaults/detail/metrics_bridge.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/vm/performance.cljs`

Add:

- `/hyperopen/test/hyperopen/vaults/infrastructure/preview_cache_test.cljs`
- `/hyperopen/test/hyperopen/vaults/application/list_vm_test.cljs`
- `/hyperopen/test/hyperopen/portfolio/application/metrics_bridge_test.cljs`

Acceptance:

- No namespace outside `/hyperopen/src/hyperopen/views/` requires `hyperopen.views.vaults.vm`.
- No namespace outside `/hyperopen/src/hyperopen/views/` requires `hyperopen.views.portfolio.vm.metrics-bridge`.
- `/hyperopen/src/hyperopen/route_modules.cljs` no longer depends on `views.vaults.vm` for route parsing.
- `/hyperopen/src/hyperopen/vaults/infrastructure/preview_cache.cljs` no longer depends on a view VM.

Focused validation:

- `npm test -- hyperopen.route-modules-test`
- `npm test -- hyperopen.views.vaults.vm-test`
- `npm test -- hyperopen.vaults.infrastructure.preview-cache-test`
- `npm test -- hyperopen.vaults.detail.metrics-bridge-test`
- `npm test -- hyperopen.views.portfolio.vm.metrics-bridge-test`

### AGENT-01 — Add Local Ownership Maps For Major Bounded Contexts

Depends on: `DDD-01` through `DIP-02`. Effort: small. Tracker: `hyperopen-glb1.2`.

Create:

- `/hyperopen/src/hyperopen/websocket/BOUNDARY.md`
- `/hyperopen/src/hyperopen/funding/BOUNDARY.md`
- `/hyperopen/src/hyperopen/vaults/BOUNDARY.md`
- `/hyperopen/src/hyperopen/trading/BOUNDARY.md`
- `/hyperopen/src/hyperopen/portfolio/BOUNDARY.md`
- `/hyperopen/src/hyperopen/account/BOUNDARY.md`

Acceptance:

- Each file states what the context owns.
- Each file states stable public seams.
- Each file states allowed dependency directions and forbidden dependencies.
- Each file states the key tests to run.
- Each file states the shortest “where this change goes” recipes.

### OCP-01 — Localize Runtime Registration Authoring Without Losing The Single Authority

Depends on: `ARCH-01`. Effort: medium. Tracker: `hyperopen-glb1.5`.

Create:

- `/hyperopen/src/hyperopen/schema/runtime_registration/websocket.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration/trade.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration/portfolio.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration/vaults.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration/funding.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration/funding_comparison.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration/leaderboard.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration/staking.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration/api_wallets.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration/spectate_mode.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration/wallet.cljs`

Refactor:

- `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`
  into a composition-only assembler over those row files

Acceptance:

- `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs` remains the canonical assembled catalog.
- Action and effect IDs do not change.
- Adding or removing a feature registration row touches only the owning row file plus the assembler.

Focused validation:

- `npm run test:runner:generate`
- `npx shadow-cljs compile test`
- `node out/test.js --test=hyperopen.schema.contracts-coverage-test,hyperopen.runtime.registry-composition-test,hyperopen.runtime.wiring-test`

### SRP-01 — Split The Runtime Composition Hubs To Match `effect_adapters/**`

Depends on: `OCP-01`. Effort: large. Tracker: `hyperopen-glb1.5`.

Hold cleared on 2026-03-25 13:49 EDT when `hyperopen-v894` was closed.

Create under `/hyperopen/src/hyperopen/runtime/action_adapters/`:

- `navigation.cljs`
- `leaderboard.cljs`
- `wallet.cljs`
- `spectate_mode.cljs`
- `websocket.cljs`
- `ws_diagnostics.cljs`

Move grouped functions out of `/hyperopen/src/hyperopen/runtime/action_adapters.cljs` into those files and keep the root file as a stable facade.

Create under `/hyperopen/src/hyperopen/runtime/collaborators/`:

- `wallet.cljs`
- `asset_selector.cljs`
- `chart.cljs`
- `account_history.cljs`
- `spectate_mode.cljs`
- `order.cljs`
- `vaults.cljs`
- `leaderboard.cljs`
- `funding_comparison.cljs`
- `staking.cljs`

Move grouped `*-action-deps` helpers out of `/hyperopen/src/hyperopen/runtime/collaborators.cljs` into those files and keep the root file as assembler or facade only.

Split tests:

- `/hyperopen/test/hyperopen/runtime/action_adapters_test.cljs`
  -> `/hyperopen/test/hyperopen/runtime/action_adapters/*.cljs`
- `/hyperopen/test/hyperopen/runtime/collaborators_test.cljs`
  -> `/hyperopen/test/hyperopen/runtime/collaborators/*.cljs`

Keep thin facade contract tests at the old roots.

Acceptance:

- `/hyperopen/src/hyperopen/runtime/action_adapters.cljs` contains facade wiring only.
- `/hyperopen/src/hyperopen/runtime/collaborators.cljs` contains assembly only.
- Public var names and runtime behavior remain stable.
- The pattern matches the existing `runtime/effect_adapters/**` decomposition.

Focused validation:

- `npm run test:runner:generate`
- `npx shadow-cljs compile test`
- `node out/test.js --test=hyperopen.runtime.action-adapters-test,hyperopen.runtime.action-adapters.navigation-test,hyperopen.runtime.action-adapters.wallet-test,hyperopen.runtime.action-adapters.websocket-test,hyperopen.runtime.action-adapters.ws-diagnostics-test,hyperopen.runtime.collaborators-test,hyperopen.runtime.collaborators.action-maps-test,hyperopen.runtime.wiring-test`

### SRP-02 — Split `views/portfolio_view.cljs`

Depends on: `DIP-02`. Effort: large. Tracker: `hyperopen-glb1.6`.

Hold cleared on 2026-03-25 14:40 EDT when `/hyperopen/docs/exec-plans/completed/2026-03-19-shared-browser-qa-route-regressions.md` became ready to close after the governed managed-local scenario bundle, `qa:design-ui`, `qa:nightly-ui`, and the required repo gates all passed.

Create:

- `/hyperopen/src/hyperopen/views/portfolio/format.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/low_confidence.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/header.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/summary_cards.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/chart_view.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/performance_metrics_view.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/account_tabs.cljs`

Move functions from `/hyperopen/src/hyperopen/views/portfolio_view.cljs` by concern:

- formatting and axis helpers -> `format.cljs`
- low-confidence banner helpers -> `low_confidence.cljs`
- inspection header, top actions, background status -> `header.cljs`
- summary card, deposits or withdrawals, metric cards -> `summary_cards.cljs`
- chart rendering and benchmark controls -> `chart_view.cljs`
- performance metric grid rendering -> `performance_metrics_view.cljs`
- tab-order, click-actions, and overrides -> `account_tabs.cljs`

Keep `portfolio-view` and `route-view` in the root as composition only.

Add:

- `/hyperopen/test/hyperopen/views/portfolio/header_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio/summary_cards_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio/chart_view_test.cljs`
- `/hyperopen/test/hyperopen/views/portfolio/performance_metrics_view_test.cljs`

Acceptance:

- `/hyperopen/src/hyperopen/views/portfolio_view.cljs` is a thin composition root.
- Data-role contracts used by existing UI tests stay unchanged.
- No extracted file mixes formatting, rendering, and route composition.

Focused validation:

- `npm test -- hyperopen.views.portfolio-view-test`
- `npm test -- hyperopen.views.portfolio.header-test`
- `npm test -- hyperopen.views.portfolio.summary-cards-test`
- `npm test -- hyperopen.views.portfolio.chart-view-test`
- `npm test -- hyperopen.views.portfolio.performance-metrics-view-test`

### SRP-03 — Split `views/asset_selector_view.cljs`

Depends on: `DIP-01`. Effort: large. Tracker: `hyperopen-glb1.6`.

Hold cleared on 2026-03-25 15:59 EDT when `/hyperopen/docs/exec-plans/completed/2026-03-23-asset-selector-rapid-scroll-flicker-jank.md` moved out of `/active/` after the final manual desktop verification passed.

Create:

- `/hyperopen/src/hyperopen/views/asset_selector/controls.cljs`
- `/hyperopen/src/hyperopen/views/asset_selector/icons.cljs`
- `/hyperopen/src/hyperopen/views/asset_selector/rows.cljs`
- `/hyperopen/src/hyperopen/views/asset_selector/layout.cljs`
- `/hyperopen/src/hyperopen/views/asset_selector/runtime.cljs`
- `/hyperopen/src/hyperopen/views/asset_selector/processing.cljs`

Move from `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` by concern:

- controls, search, tabs, and sort UI -> `controls.cljs`
- Lucide star and favorite button helpers -> `icons.cljs`
- row rendering and shortcut footer -> `rows.cljs`
- desktop and mobile dropdown layout -> `layout.cljs`
- scroll atoms, timeout handling, on-render lifecycle, and virtual window logic -> `runtime.cljs`
- processed-assets cache and list preprocessing -> `processing.cljs`

Keep `asset-selector-dropdown` and `asset-selector-wrapper` in the root only if they are facades; otherwise move them and keep the root as aliases.

Add:

- `/hyperopen/test/hyperopen/views/asset_selector/controls_test.cljs`
- `/hyperopen/test/hyperopen/views/asset_selector/rows_test.cljs`
- `/hyperopen/test/hyperopen/views/asset_selector/runtime_test.cljs`
- `/hyperopen/test/hyperopen/views/asset_selector/processing_test.cljs`

Acceptance:

- The root file becomes a thin facade or composition entrypoint.
- Scroll and runtime logic are isolated from render-only code.
- The keyboard-highlight regression stays covered.

Focused validation:

- `npm test -- hyperopen.views.asset-selector-view-test`
- `npm test -- hyperopen.views.asset-selector.controls-test`
- `npm test -- hyperopen.views.asset-selector.rows-test`
- `npm test -- hyperopen.views.asset-selector.runtime-test`
- `npm test -- hyperopen.views.asset-selector.processing-test`

### SRP-04 — Finish The Order-Form View Split

Depends on: `DIP-01`. Effort: medium. Tracker: `hyperopen-glb1.6`.

Create:

- `/hyperopen/src/hyperopen/views/trade/order_form_controls.cljs`
- `/hyperopen/src/hyperopen/views/trade/order_form_feedback.cljs`
- `/hyperopen/src/hyperopen/views/trade/order_form_footer.cljs`

Move from `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`:

- leverage, margin mode, size-unit, side, balances, and size-row helpers -> `order_form_controls.cljs`
- unsupported-market banner, TWAP preview, TPSL panel model, and spectate affordances -> `order_form_feedback.cljs`
- fee helpers, footer metrics, and submit row -> `order_form_footer.cljs`

Keep `render-order-form` and `order-form-view` in the root as composition only.

Add:

- `/hyperopen/test/hyperopen/views/trade/order_form_view/controls_test.cljs`
- `/hyperopen/test/hyperopen/views/trade/order_form_view/feedback_test.cljs`
- `/hyperopen/test/hyperopen/views/trade/order_form_view/footer_test.cljs`

Acceptance:

- `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` is no longer a mixed control, render, and feedback kitchen-sink file.
- No private function in the root file remains a hotspot-scale branch sink.
- Existing root facade tests stay thin; detailed tests move to the new suites.

Focused validation:

- `npm test -- hyperopen.views.trade.order-form-view-test`
- `npm test -- hyperopen.views.trade.order-form-view.controls-test`
- `npm test -- hyperopen.views.trade.order-form-view.feedback-test`
- `npm test -- hyperopen.views.trade.order-form-view.footer-test`

### SRP-05 — Split `funding/application/modal_vm.cljs` By Helper Cluster

Depends on: `DDD-01`. Effort: medium. Tracker: `hyperopen-glb1.6`.

Create:

- `/hyperopen/src/hyperopen/funding/application/modal_vm/context.cljs`
- `/hyperopen/src/hyperopen/funding/application/modal_vm/async.cljs`
- `/hyperopen/src/hyperopen/funding/application/modal_vm/lifecycle.cljs`
- `/hyperopen/src/hyperopen/funding/application/modal_vm/amounts.cljs`
- `/hyperopen/src/hyperopen/funding/application/modal_vm/presentation.cljs`
- `/hyperopen/src/hyperopen/funding/application/modal_vm/models.cljs`

Move from `/hyperopen/src/hyperopen/funding/application/modal_vm.cljs` by concern:

- base, asset, generated, and preview context builders -> `context.cljs`
- fee and withdrawal-queue derivation -> `async.cljs`
- lifecycle labels, outcomes, and panel state -> `lifecycle.cljs`
- max amount, network fee, and ETA helpers -> `amounts.cljs`
- titles, submit labels, summary rows, status messages, and feedback model -> `presentation.cljs`
- `modal-model`, `deposit-model`, `transfer-model`, `send-model`, `withdraw-model`, `legacy-model`, and `build-view-model` -> `models.cljs`

Keep `funding-modal-view-model` in the root as orchestrator only.

Split `/hyperopen/test/hyperopen/funding/application/modal_vm_test.cljs`
into `/hyperopen/test/hyperopen/funding/application/modal_vm/*.cljs`
and keep the root test as facade or integration coverage.

Acceptance:

- `/hyperopen/src/hyperopen/funding/application/modal_vm.cljs` is a thin orchestrator.
- The output shape of `funding-modal-view-model` does not change.
- No modal VM submodule mixes async derivation, lifecycle derivation, and final view assembly.

Focused validation:

- `npm test -- hyperopen.funding.application.modal-vm-test`

### SRP-06 — Split `schema/contracts.cljs`

Depends on: `OCP-01`. Effort: large. Tracker: `hyperopen-glb1.5`.

Hold until the active order-confirmation plan touching `/hyperopen/src/hyperopen/schema/contracts.cljs` is closed.

Create:

- `/hyperopen/src/hyperopen/schema/contracts/common.cljs`
- `/hyperopen/src/hyperopen/schema/contracts/action_args.cljs`
- `/hyperopen/src/hyperopen/schema/contracts/effect_args.cljs`
- `/hyperopen/src/hyperopen/schema/contracts/state.cljs`
- `/hyperopen/src/hyperopen/schema/contracts/assertions.cljs`

Move from `/hyperopen/src/hyperopen/schema/contracts.cljs` by concern:

- primitive helpers and shared low-level specs -> `common.cljs`
- action payload specs and `action-args-spec-by-id` -> `action_args.cljs`
- effect payload specs and `effect-args-spec-by-id` -> `effect_args.cljs`
- funding, app-state, and order-form state specs -> `state.cljs`
- `assertion-error`, `assert-spec!`, and all public `assert-*` entrypoints -> `assertions.cljs`

Keep in the root:

- `contracted-action-ids`
- `contracted-effect-ids`
- `action-ids-using-any-args`
- public re-exports for the `assert-*` API

Split `/hyperopen/test/hyperopen/schema/contracts_test.cljs`
into `/hyperopen/test/hyperopen/schema/contracts/*.cljs`
and keep `/hyperopen/test/hyperopen/schema/contracts_coverage_test.cljs` as the drift and coverage authority.

Acceptance:

- `/hyperopen/src/hyperopen/schema/contracts.cljs` is an aggregator or facade, not a mixed 1000-line spec file.
- Contract drift checks against `runtime_registration_catalog` stay intact.
- Existing `order_form_contracts` and `order_form_command_contracts` remain separate; do not fold them back into the root.

Focused validation:

- `npm test -- hyperopen.schema.contracts-test`
- `npm test -- hyperopen.schema.contracts-coverage-test`
- `npm test -- hyperopen.runtime.registry-composition-test`
- `npm test -- hyperopen.runtime.wiring-test`

## Concrete Steps

Run all commands from `/Users/barry/projects/hyperopen`.

1. Before each backlog item, confirm the live tracker state and the hold conditions:

   `/usr/local/bin/bd show hyperopen-glb1 --json`

   `/usr/local/bin/bd children hyperopen-glb1 --json`

2. Before editing, inspect the exact target files and current callers listed in the backlog item. Do not widen scope until the exact callers are understood.

3. Execute the file moves and caller updates exactly as listed in the backlog item. If a public facade stays, make it a thin alias or composition root. If all callers move in the same ticket, remove the old facade completely.

4. Any time a test namespace is added or moved, regenerate the test runner immediately:

   `npm run test:runner:generate`

5. Run the focused validation commands listed in the backlog item first. If those pass, run the required repo gates:

   `npm run check`

   `npm test`

   `npm run test:websocket`

6. When a backlog item changes UI behavior, run the smallest relevant Playwright suite before broadening:

   portfolio or account-info route changes:
   `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs`

   trade, asset-selector, funding-modal, or order-form route changes:
   `npx playwright test tools/playwright/test/trade-regressions.spec.mjs`

   route-shell or composition changes:
   `npx playwright test tools/playwright/test/routes.smoke.spec.mjs`

7. After each completed backlog item, update this ExecPlan’s `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective`, then update the corresponding `bd` child task before moving to the next item.

## Validation and Acceptance

This backlog is done only when all of the following are true:

- no `domain` namespace requires `hyperopen.views.*`
- no `domain` namespace owns modal, form, search, pagination, sort, or dropdown state
- no non-view namespace imports reusable helpers from `views/**`
- runtime registration still has one authoritative assembled catalog
- root hotspot files are thin facades or composition roots
- `npm run check`, `npm test`, and `npm run test:websocket` all pass

## Idempotence and Recovery

This wave should be executed in additive, compatibility-first slices. Guardrails come first, then domain and dependency cleanup, then local boundary maps, then runtime-authority localization, then hotspot splits. If a split proves too risky, keep the guardrails and exception registry intact, leave a time-bounded exception entry for the remaining oversized namespace, and continue with a narrower extraction rather than disabling the rule.

If a backlog item breaks callers, restore the stable facade and move the implementation behind it instead of reverting to the old misleading ownership. If an item is blocked by active overlap in another tracked task, stop and record the blocker rather than forcing the change into a live collision. If a test namespace move leaves discovery broken, regenerate `/hyperopen/test/test_runner_generated.cljs` before assuming the implementation is wrong.

## Artifacts and Notes

Current `bd` mapping:

- `hyperopen-glb1.3` covers `ARCH-01`
- `hyperopen-glb1.1` covers `DDD-01`, `DDD-02`, and `DDD-03`
- `hyperopen-glb1.4` covers `DIP-01` and `DIP-02`
- `hyperopen-glb1.2` covers `AGENT-01`
- `hyperopen-glb1.5` covers `OCP-01`, `SRP-01`, and `SRP-06`
- `hyperopen-glb1.6` covers `SRP-02`, `SRP-03`, `SRP-04`, and `SRP-05`

Relevant overlap work to check before touching shared files:

- `hyperopen-v894` - closed on 2026-03-25 during the order-confirmation closeout pass, so it no longer blocks `runtime/collaborators.cljs` or `/hyperopen/src/hyperopen/schema/contracts.cljs`
- `hyperopen-v7jc` - closed on 2026-03-25 after the managed-local shared browser-QA reruns, so it no longer blocks `/hyperopen/src/hyperopen/views/portfolio_view.cljs`
- `hyperopen-2614` - closed on 2026-03-25 after the final manual desktop verification confirmed the `/trade` asset selector no longer shows jank or flickering, so it no longer blocks `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`
- `hyperopen-6len` - closed on 2026-03-25 during validation; the stale active ExecPlan was moved to `/hyperopen/docs/exec-plans/completed/2026-03-24-portfolio-interaction-state-qa-blind-spots.md`, so it no longer blocks future work

Second-wave candidates to reassess after this backlog lands:

- `/hyperopen/src/hyperopen/views/api_wallets_view.cljs`
- `/hyperopen/src/hyperopen/views/vaults/detail/activity.cljs`
- `/hyperopen/src/hyperopen/views/vaults/list_view.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`

## Interfaces and Dependencies

Stable public behavior that must remain intact across this wave:

- `hyperopen.funding.application.modal-actions/default-funding-modal-state`
- `hyperopen.funding.application.modal-actions/normalize-modal-state`
- `hyperopen.vaults.actions/default-vault-transfer-modal-state`
- API-wallet actions, effects, and view-model behavior on the existing route
- action and effect IDs in `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`
- public `assert-*` APIs and contracted ID exports in `/hyperopen/src/hyperopen/schema/contracts.cljs`

Stable structural rules that must hold at the end:

- `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs` remains the canonical assembled catalog
- `/hyperopen/src/hyperopen/runtime/action_adapters.cljs` and `/hyperopen/src/hyperopen/runtime/collaborators.cljs` remain stable facades or assemblers even after decomposition
- `/hyperopen/src/hyperopen/views/portfolio_view.cljs`, `/hyperopen/src/hyperopen/views/asset_selector_view.cljs`, `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`, `/hyperopen/src/hyperopen/funding/application/modal_vm.cljs`, and `/hyperopen/src/hyperopen/schema/contracts.cljs` end as thin composition roots or aggregators, not mixed hotspot files

Plan update note: 2026-03-25 08:59 EDT - Replaced the earlier high-level milestone outline with the user-provided backlog `ARCH-01` through `SRP-06`, including exact file moves, focused validations, hold conditions, global exit criteria, and explicit `bd` task mapping.

Plan update note: 2026-03-25 09:44 EDT - Completed `ARCH-01` by adding namespace-size and namespace-boundary CI guardrails, seeding both exception registries from the live tree, wiring the new checks into `npm run check`, updating architecture/quality/debt docs, running `npm ci`, passing `npm run check`, `npm test`, and `npm run test:websocket`, and closing `hyperopen-glb1.3` in `bd`. Validation also exposed a stale closed active ExecPlan, which was moved to `/completed/` so docs governance stayed truthful.

Plan update note: 2026-03-25 10:23 EDT - Completed `DDD-01` by moving funding modal state ownership from `/hyperopen/src/hyperopen/funding/domain/modal_state.cljs` to `/hyperopen/src/hyperopen/funding/application/modal_state.cljs`, updating the modal-actions facade, adding direct regression coverage, regenerating the test runner, and re-passing `npm test`, `npm run check`, and `npm run test:websocket`.

Plan update note: 2026-03-25 10:35 EDT - Completed `DDD-02` by moving vault UI-state ownership from `/hyperopen/src/hyperopen/vaults/domain/ui_state.cljs` to `/hyperopen/src/hyperopen/vaults/application/ui_state.cljs`, extracting `/hyperopen/src/hyperopen/vaults/application/transfer_state.cljs`, removing transfer-modal defaults from `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`, updating all listed callers, adding focused application-level regression coverage, regenerating the test runner, and re-passing `npm test`, `npm run check`, and `npm run test:websocket`. The first `npm run check` also caught an oversized existing hotspot test, so the new facade regression was relocated into the focused transfer-state test instead of widening the size exception.

Plan update note: 2026-03-25 10:51 EDT - Completed `DDD-03` by splitting API-wallet UI/reset/normalization helpers into `/hyperopen/src/hyperopen/api_wallets/application/ui_state.cljs`, splitting session-driven form validation into `/hyperopen/src/hyperopen/api_wallets/application/form_policy.cljs`, reducing `/hyperopen/src/hyperopen/api_wallets/domain/policy.cljs` to row merge and sort helpers only, updating actions/effects/view-model/default-state callers, adding focused application-level regression coverage plus an app-defaults wiring check, regenerating the test runner, re-passing `npm test`, `npm run check`, and `npm run test:websocket`, and closing `hyperopen-glb1.1` in `bd`.

Plan update note: 2026-03-25 11:03 EDT - Completed `DIP-01` by moving the shared hover and table-sort helpers from `/hyperopen/src/hyperopen/views/` to `/hyperopen/src/hyperopen/ui/`, updating all listed callers, moving the sort-kernel regression suite, adding direct hover-helper coverage, removing the three resolved boundary exceptions from `/hyperopen/dev/namespace_boundary_exceptions.edn`, regenerating the test runner, and re-passing `npm test`, `npm run check`, and `npm run test:websocket`. The planned focused `npm test -- <namespace>` command form is not supported by this repo’s test runner, so validation widened to the full `npm test` suite after the runner reported each namespace argument as `Unknown arg:`.

Plan update note: 2026-03-25 11:31 EDT - Completed `DIP-02` by extracting the shared vault list model into `/hyperopen/src/hyperopen/vaults/application/list_vm.cljs`, extracting the shared portfolio metrics bridge into `/hyperopen/src/hyperopen/portfolio/application/metrics_bridge.cljs`, reducing `/hyperopen/src/hyperopen/views/vaults/vm.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/metrics_bridge.cljs` to thin facades, moving the vault route helpers into `/hyperopen/src/hyperopen/vaults/infrastructure/routes.cljs`, updating all listed callers, adding focused application/infrastructure regression suites, migrating the stale vaults VM size exception onto the new `/hyperopen/src/hyperopen/vaults/application/list_vm.cljs` owner, removing the three resolved `DIP-02` boundary exceptions, regenerating the test runner, re-passing `npm test`, `npm run check`, and `npm run test:websocket`, and closing `hyperopen-glb1.4` in `bd`.

Plan update note: 2026-03-25 12:26 EDT - Completed `AGENT-01` by adding `/hyperopen/src/hyperopen/websocket/BOUNDARY.md`, `/hyperopen/src/hyperopen/funding/BOUNDARY.md`, `/hyperopen/src/hyperopen/vaults/BOUNDARY.md`, `/hyperopen/src/hyperopen/trading/BOUNDARY.md`, `/hyperopen/src/hyperopen/portfolio/BOUNDARY.md`, and `/hyperopen/src/hyperopen/account/BOUNDARY.md`, each documenting current ownership, stable public seams, dependency rules, key tests, and short routing recipes. Validation passed with `npm run lint:docs`, and `hyperopen-glb1.2` was closed in `bd`.

Plan update note: 2026-03-25 13:04 EDT - Completed `OCP-01` by extracting per-context runtime registration row modules under `/hyperopen/src/hyperopen/schema/runtime_registration/`, reducing `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs` from 444 lines to a 99-line composition-only assembler plus duplicate-id validation, and preserving the assembled action/effect ids consumed by runtime wiring and contract drift checks. Focused validation passed with `npm run test:runner:generate`, `npx shadow-cljs compile test`, and `node out/test.js --test=hyperopen.schema.contracts-coverage-test,hyperopen.runtime.registry-composition-test,hyperopen.runtime.wiring-test`, and the final revision re-passed `npm run check`, `npm test`, and `npm run test:websocket`. `hyperopen-glb1.5` remains open because `SRP-01` and `SRP-06` are still blocked by `hyperopen-v894`.

Plan update note: 2026-03-25 13:49 EDT - Closed the overlapping order-confirmation task `hyperopen-v894` by verifying the landed Trading Settings confirmation feature still exists on the current branch, moving its ExecPlan to `/hyperopen/docs/exec-plans/completed/`, and closing the `bd` task. `SRP-01` and `SRP-06` are now unblocked, but they remain unstarted in this plan.

Plan update note: 2026-03-25 14:12 EDT - Completed `SRP-01` by extracting `/hyperopen/src/hyperopen/runtime/action_adapters/{navigation,leaderboard,wallet,spectate_mode,websocket,ws_diagnostics}.cljs`, extracting `/hyperopen/src/hyperopen/runtime/collaborators/{wallet,asset_selector,chart,account_history,spectate_mode,order,vaults,leaderboard,funding_comparison,staking}.cljs`, reducing `/hyperopen/src/hyperopen/runtime/action_adapters.cljs` and `/hyperopen/src/hyperopen/runtime/collaborators.cljs` to facade or assembler-only roots, splitting the old mixed runtime hub tests into focused suites under `/hyperopen/test/hyperopen/runtime/action_adapters/` and `/hyperopen/test/hyperopen/runtime/collaborators/` while keeping thin root contract coverage, regenerating `/hyperopen/test/test_runner_generated.cljs`, passing focused validation with `node out/test.js --test=hyperopen.runtime.action-adapters-test,hyperopen.runtime.action-adapters.navigation-test,hyperopen.runtime.action-adapters.wallet-test,hyperopen.runtime.action-adapters.websocket-test,hyperopen.runtime.action-adapters.ws-diagnostics-test,hyperopen.runtime.collaborators-test,hyperopen.runtime.collaborators.action-maps-test,hyperopen.runtime.wiring-test`, and re-passing `npm test`, `npm run check`, and `npm run test:websocket`. `hyperopen-glb1.5` remains open for `SRP-06`, and the next ordered backlog item is `SRP-02`, which is still blocked by the active shared browser-QA plan.

Plan update note: 2026-03-25 14:40 EDT - Closed the shared browser-QA overlap task `hyperopen-v7jc` by aligning `/hyperopen/tools/browser-inspection/scenarios/portfolio-interaction-states.json` with the committed inactive-tab aria contract, rerunning `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs`, rerunning the governed managed-local scenario bundle `scenario-2026-03-25T18-29-09-111Z-2d8afb27`, passing `npm run qa:design-ui -- --targets trade-route,portfolio-route,vaults-route --manage-local-app` with review bundle `design-review-2026-03-25T18-30-06-865Z-e543f593`, passing `npm run qa:nightly-ui -- --allow-non-main --manage-local-app` with nightly bundle `nightly-ui-qa-2026-03-25T18-32-55-494Z-86ee6cd1`, adding `/hyperopen/docs/qa/nightly-ui-report-2026-03-25.md`, and re-passing `npm run check`, `npm test`, and `npm run test:websocket`. `SRP-02` is now unblocked and is the next ordered backlog item.

Plan update note: 2026-03-25 15:19 EDT - Completed `SRP-02` by extracting `/hyperopen/src/hyperopen/views/portfolio/{format,low_confidence,header,summary_cards,chart_view,performance_metrics_view,account_tabs}.cljs`, reducing `/hyperopen/src/hyperopen/views/portfolio_view.cljs` from 1325 lines to a 43-line composition-only route entrypoint, adding focused regression suites under `/hyperopen/test/hyperopen/views/portfolio/`, updating `/hyperopen/test/hyperopen/views/typography_scale_test.cljs` to follow the moved canvas-font measurement owner, retiring the stale `/hyperopen/dev/namespace_size_exceptions.edn` entry for the old hotspot root, and re-passing `npm run check`, `npm test`, and `npm run test:websocket`. Required UI validation passed with `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs`, the managed-local scenario bundle `scenario-2026-03-25T19-13-02-130Z-35cbb799`, and the governed design review bundle `design-review-2026-03-25T19-13-34-154Z-ffc86e65`, which returned `PASS` across all six passes at `375`, `768`, `1280`, and `1440` widths. `hyperopen-glb1.6` remains open, and the next ordered item is `SRP-03`, which is still blocked by `hyperopen-2614`.

Plan update note: 2026-03-25 15:59 EDT - Closed `hyperopen-2614` by recording the final manual desktop verification in `/hyperopen/docs/exec-plans/completed/2026-03-23-asset-selector-rapid-scroll-flicker-jank.md`: the user rapidly scrolled the `/trade` asset selector all the way down and back up in a live browser session and reported no visible jank and no flickering. With that plan moved out of `/active/`, `SRP-03` is now the next ordered backlog item and is no longer blocked.

Plan update note: 2026-03-25 16:52 EDT - Completed `SRP-03` by extracting `/hyperopen/src/hyperopen/views/asset_selector/{controls,icons,rows,layout,runtime,processing}.cljs`, reducing `/hyperopen/src/hyperopen/views/asset_selector_view.cljs` to a 38-line facade, splitting the selector-heavy tests into focused suites under `/hyperopen/test/hyperopen/views/asset_selector/`, and removing the stale `/hyperopen/dev/namespace_size_exceptions.edn` entries for the old root and root test. Focused validation passed with `npm run test:runner:generate`, `npx shadow-cljs compile test`, and `node out/test.js --test=hyperopen.views.asset-selector.controls-test,hyperopen.views.asset-selector.rows-test,hyperopen.views.asset-selector.runtime-test,hyperopen.views.asset-selector.processing-test,hyperopen.views.asset-selector-view-test,hyperopen.views.active-asset-view-test,hyperopen.views.trade-view.render-cache-test`. Required UI validation passed with `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "asset selector"`, the managed-local scenario bundle `scenario-2026-03-25T20-51-59-487Z-fe196849`, and the governed design review bundle `design-review-2026-03-25T20-52-33-293Z-f06a96ad`, which returned `PASS` across all six passes at `375`, `768`, `1280`, and `1440` widths with only the standard `state-sampling-limited` blind spots. The final repo gates re-passed with `npm test`, `npm run check`, and `npm run test:websocket`, so `hyperopen-glb1.6` now remains open only for `SRP-04` and `SRP-05`, and the next ordered backlog item is `SRP-04`.

Plan update note: 2026-03-25 17:10 EDT - Completed `SRP-04` by extracting `/hyperopen/src/hyperopen/views/trade/order_form_{controls,feedback,footer}.cljs`, reducing `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` to a 182-line composition facade, adding focused regression suites under `/hyperopen/test/hyperopen/views/trade/order_form_view/{controls,feedback,footer}_test.cljs`, regenerating `/hyperopen/test/test_runner_generated.cljs`, removing the stale `/hyperopen/dev/namespace_size_exceptions.edn` entry for the old root, and adding the explicit follow-on exception for `/hyperopen/src/hyperopen/views/trade/order_form_controls.cljs` at 543 lines. Focused validation passed with `npm run test:runner:generate`, `npx shadow-cljs compile test`, and `node out/test.js --test=hyperopen.views.trade.order-form-view-test,hyperopen.views.trade.order-form-view.controls-test,hyperopen.views.trade.order-form-view.feedback-test,hyperopen.views.trade.order-form-view.footer-test,hyperopen.views.trade.order-form-view.entry-mode-test,hyperopen.views.trade.order-form-view.metrics-and-submit-test,hyperopen.views.trade.order-form-view.scale-preview-test,hyperopen.views.trade.order-form-view.size-and-slider-test,hyperopen.views.trade.order-form-view.styling-contract-test,hyperopen.views.workbench-render-seams-test,hyperopen.views.trade-view.render-cache-test,hyperopen.views.trade-view.mobile-surface-test`. Required UI validation passed with `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "order submit"`, the managed-local scenario bundle `scenario-2026-03-25T21-07-48-619Z-a6659670`, and the governed design review bundle `design-review-2026-03-25T21-08-08-082Z-738e1c5e`, which returned `PASS` across all six passes at `375`, `768`, `1280`, and `1440` widths with only the standard `state-sampling-limited` blind spots. The final repo gates re-passed with `npm test`, `npm run check`, and `npm run test:websocket`, so `hyperopen-glb1.6` now remains open only for `SRP-05`, and the next ordered backlog item is `SRP-05`.

Plan update note: 2026-03-25 17:45 EDT - Completed `SRP-05` by extracting `/hyperopen/src/hyperopen/funding/application/modal_vm/{context,async,lifecycle,amounts,presentation,models}.cljs`, reducing `/hyperopen/src/hyperopen/funding/application/modal_vm.cljs` to a 20-line orchestration facade, splitting the modal-VM regression coverage into focused suites under `/hyperopen/test/hyperopen/funding/application/modal_vm/`, regenerating `/hyperopen/test/test_runner_generated.cljs`, and removing the stale `/hyperopen/dev/namespace_size_exceptions.edn` entry for the old root. Focused validation passed with `npm run test:runner:generate`, `npx shadow-cljs compile test`, and `node out/test.js --test=hyperopen.funding.application.modal-vm-test,hyperopen.funding.application.modal-vm.context-test,hyperopen.funding.application.modal-vm.async-test,hyperopen.funding.application.modal-vm.lifecycle-test,hyperopen.funding.application.modal-vm.amounts-test,hyperopen.funding.application.modal-vm.presentation-test,hyperopen.funding.application.modal-vm.models-test,hyperopen.funding.actions-test,hyperopen.views.funding-modal-test,hyperopen.views.workbench-render-seams-test,hyperopen.telemetry.console-preload-test`. Required UI validation passed with `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "funding modal deposit flow"`, the managed-local scenario bundle `scenario-2026-03-25T21-41-09-994Z-446c7897`, and the governed design review bundle `design-review-2026-03-25T21-43-08-155Z-63f6a1a6`, which returned `PASS` across all six passes at `375`, `768`, `1280`, and `1440` widths with only the standard `state-sampling-limited` blind spots. The final repo gates re-passed with `npm run check`, `npm test`, and `npm run test:websocket`, `hyperopen-glb1.6` was closed in `bd`, and the next ordered backlog item is `SRP-06`.

Plan update note: 2026-03-25 18:17 EDT - Completed `SRP-06` by extracting `/hyperopen/src/hyperopen/schema/contracts/{common,action_args,effect_args,state,assertions}.cljs`, reducing `/hyperopen/src/hyperopen/schema/contracts.cljs` to a 50-line public facade, splitting the contracts-heavy root regression file into focused suites under `/hyperopen/test/hyperopen/schema/contracts/` while keeping `/hyperopen/test/hyperopen/schema/contracts_coverage_test.cljs` as the runtime drift authority, regenerating `/hyperopen/test/test_runner_generated.cljs`, and removing the stale `/hyperopen/dev/namespace_size_exceptions.edn` entries for the old contracts root and root test. Focused validation passed with `npm run test:runner:generate`, `npx shadow-cljs compile test`, and `node out/test.js --test=hyperopen.schema.contracts-test,hyperopen.schema.contracts.state-test,hyperopen.schema.contracts.action-args-test,hyperopen.schema.contracts.effect-args-test,hyperopen.schema.contracts.assertions-test,hyperopen.schema.contracts-coverage-test,hyperopen.runtime.registry-composition-test,hyperopen.runtime.wiring-test`. The final repo gates re-passed with `npm run check`, `npm test`, and `npm run test:websocket`, so `hyperopen-glb1.5` is ready to close and the remediation-wave backlog is complete.
