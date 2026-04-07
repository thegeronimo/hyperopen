# Retire Funding Effects Namespace-Size Exception

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan was `hyperopen-gwu2`, and that `bd` issue remained the lifecycle source of truth until it was closed as completed on 2026-04-07.

## Purpose / Big Picture

After this change, the funding deposit, send, transfer, fee-estimate, and Hyperunit withdrawal flows should behave exactly as they do today, but the implementation should stop concentrating transport wrappers, lifecycle polling adapters, parsing rules, and dependency-map composition inside one 878-line facade. A contributor should be able to change one funding boundary without rereading nearly the entire funding workflow namespace, and they should be able to prove behavior preservation through boundary-focused tests instead of only broad end-to-end coverage.

The visible proof is behavior-preserving. Runtime effect adapters in `/hyperopen/src/hyperopen/runtime/effect_adapters/funding.cljs` still call the same six public entrypoints from `/hyperopen/src/hyperopen/funding/effects.cljs`, funding modal submits still dispatch the same follow-up loads and errors, and Hyperunit lifecycle polling still updates the same modal lifecycle state. The structural proof is that `/hyperopen/src/hyperopen/funding/effects.cljs` drops below the namespace-size threshold so its exception can be removed from `/hyperopen/dev/namespace_size_exceptions.edn`.

## Progress

- [x] (2026-04-07 00:44Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`.
- [x] (2026-04-07 00:49Z) Audited `dev/namespace_size_exceptions.edn`, current hotspot sizes, and related completed plans to pick the first stateful exception to retire.
- [x] (2026-04-07 00:53Z) Created `hyperopen-gwu2` and linked this plan to that live `bd` task.
- [x] (2026-04-07 00:58Z) Chose `/hyperopen/src/hyperopen/funding/effects.cljs` as the highest-risk remaining named hotspot and documented the split strategy plus boundary-test matrix.
- [x] (2026-04-07 01:08Z) Extracted shared parsing, chain-config, token, and error-formatting ownership into `/hyperopen/src/hyperopen/funding/effects/common.cljs` while preserving the six public `hyperopen.funding.effects` entrypoints.
- [x] (2026-04-07 01:11Z) Extracted Hyperunit lifecycle/query wrappers into `/hyperopen/src/hyperopen/funding/effects/hyperunit_runtime.cljs`, transport and submit wrappers into `/hyperopen/src/hyperopen/funding/effects/transport_runtime.cljs`, and moved direct tests into the new boundary-owned suites under `/hyperopen/test/hyperopen/funding/effects/`.
- [x] (2026-04-07 01:27Z) Reduced `/hyperopen/src/hyperopen/funding/effects.cljs` to `219` lines, removed its exception from `/hyperopen/dev/namespace_size_exceptions.edn`, restored the missing JS toolchain with `npm ci`, and passed `npm test`, `npm run test:websocket`, and `npm run check`.

## Surprises & Discoveries

- Observation: two names from the user’s “biggest risk concentration” list are no longer live namespace-size exceptions in this worktree.
  Evidence: `wc -l` reports `/hyperopen/src/hyperopen/websocket/application/runtime_reducer.cljs` at `453` lines and `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs` at `408` lines, and neither path appears in `/hyperopen/dev/namespace_size_exceptions.edn`.

- Observation: `/hyperopen/src/hyperopen/funding/effects.cljs` is already acting mostly as a dependency-composition facade around smaller funding modules rather than as the sole owner of business logic.
  Evidence: the namespace delegates directly to `/hyperopen/src/hyperopen/funding/application/deposit_submit.cljs`, `/hyperopen/src/hyperopen/funding/application/hyperunit_query.cljs`, `/hyperopen/src/hyperopen/funding/application/hyperunit_submit.cljs`, `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`, `/hyperopen/src/hyperopen/funding/application/submit_effects.cljs`, and several `/hyperopen/src/hyperopen/funding/infrastructure/*.cljs` modules.

- Observation: the repo already has a strong boundary-test baseline for this area, but those tests are still anchored to the oversized facade rather than the seams that now exist in the implementation.
  Evidence: `/hyperopen/test/hyperopen/funding/effects_test.cljs`, `/hyperopen/test/hyperopen/funding/effects_wrappers_test.cljs`, `/hyperopen/test/hyperopen/funding/effects_api_wrappers_test.cljs`, and `/hyperopen/test/hyperopen/funding/application/lifecycle_polling_test.cljs` together provide 1,178 lines of direct helper, wrapper, and lifecycle assertions.

- Observation: the highest remaining UI-heavy exceptions are similar in raw size, but they carry extra browser-QA overhead that makes them a worse first retirement target if the immediate goal is to retire one stateful hotspot safely.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` is `866` lines, `/hyperopen/src/hyperopen/views/account_info_view.cljs` is `866` lines, and both live under `/hyperopen/src/hyperopen/views/**`, which would trigger the governed UI-doc and browser-QA path in `/hyperopen/AGENTS.md`.

- Observation: the first compile and test attempts were blocked by a missing JavaScript toolchain rather than by ClojureScript errors from the funding split.
  Evidence: before `npm ci`, `node_modules` did not exist in the worktree, `npm test` failed on `lucide/dist/esm/icons/external-link.js`, `npm run test:websocket` failed on `@noble/secp256k1`, and `npm run check` failed on `zod` and `smol-toml`.

- Observation: once the support helpers, Hyperunit runtime wrappers, and transport wrappers moved out, the original facade dropped far below the guardrail instead of only barely clearing it.
  Evidence: `wc -l` reports `/hyperopen/src/hyperopen/funding/effects.cljs` at `219` lines after the split, and `npm run lint:namespace-sizes` passed after removing the stale exception entry.

## Decision Log

- Decision: Choose `/hyperopen/src/hyperopen/funding/effects.cljs` as the first hotspot to plan.
  Rationale: among the user-named stateful exceptions that are still actually on the exception list, it is the largest (`878` lines), it is runtime-facing, and it can be retired without invoking UI browser-QA obligations.
  Date/Author: 2026-04-07 / Codex

- Decision: Keep `hyperopen.funding.effects` as the stable public facade and extract internal owners under `/hyperopen/src/hyperopen/funding/effects/`.
  Rationale: runtime collaborators and effect adapters already depend on the current namespace path, so the safest split removes internal concentration without creating call-site churn across the runtime.
  Date/Author: 2026-04-07 / Codex

- Decision: Move direct tests to the extracted boundary owners as the code moves, instead of preserving private-var reach-through tests against the oversized facade.
  Rationale: the user explicitly asked for boundary tests during the split, and boundary-owned tests will keep future regressions local instead of forcing contributors back through the monolith.
  Date/Author: 2026-04-07 / Codex

- Decision: Treat UI changes as out of scope for this ticket.
  Rationale: the selected hotspot is a funding workflow facade. Pulling view copy, DOM, or modal-layout changes into this split would expand the scope, require governed browser QA, and make it harder to prove that the split is behavior-preserving.
  Date/Author: 2026-04-07 / Codex

## Outcomes & Retrospective

Implementation is complete. The funding effect boundary now has one thin public facade plus three smaller internal owners: `/hyperopen/src/hyperopen/funding/effects/common.cljs`, `/hyperopen/src/hyperopen/funding/effects/hyperunit_runtime.cljs`, and `/hyperopen/src/hyperopen/funding/effects/transport_runtime.cljs`. The public runtime contract stayed fixed, the direct tests moved to the same ownership boundaries as the code, and the namespace-size exception for `/hyperopen/src/hyperopen/funding/effects.cljs` was retired.

Overall complexity decreased. The old file mixed pure parsing, Hyperunit lifecycle composition, transport wrappers, and public API wiring in one place. The new layout gives each concern one owner and leaves `hyperopen.funding.effects` responsible only for modal-local helpers and the six public API functions. The main lesson from the execution is that environment readiness still matters for structural refactors: the code changes compiled immediately, but the required gates were only meaningful after restoring `node_modules` with `npm ci`.

## Context and Orientation

In this repository, “funding effects” means the runtime-facing asynchronous operations behind the funding modal and related funding workflows. The public entrypoints are the six functions exported from `/hyperopen/src/hyperopen/funding/effects.cljs`:

- `api-fetch-hyperunit-fee-estimate!`
- `api-fetch-hyperunit-withdrawal-queue!`
- `api-submit-funding-transfer!`
- `api-submit-funding-send!`
- `api-submit-funding-withdraw!`
- `api-submit-funding-deposit!`

Those functions are consumed by `/hyperopen/src/hyperopen/runtime/collaborators.cljs` and `/hyperopen/src/hyperopen/runtime/effect_adapters/funding.cljs`. That is the contract that must remain stable.

Today, `/hyperopen/src/hyperopen/funding/effects.cljs` mixes four distinct responsibilities:

First, it owns normalization and configuration details such as chain IDs, token aliasing, address parsing, unit parsing, wallet error formatting, and Hyperunit base-URL resolution. Those are pure or nearly pure concerns.

Second, it owns Hyperunit lifecycle support for existing-address selection, polling-token installation, queue refreshes, and dependency injection into `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`.

Third, it owns transport wrappers for route quotes, ERC-20 reads, Hyperunit address requests, and deposit or withdrawal submit wrappers around `/hyperopen/src/hyperopen/funding/application/deposit_submit.cljs` and `/hyperopen/src/hyperopen/funding/application/hyperunit_submit.cljs`.

Fourth, it owns the public API facade that assembles dependency maps for `/hyperopen/src/hyperopen/funding/application/submit_effects.cljs` and `/hyperopen/src/hyperopen/funding/application/hyperunit_query.cljs`.

The surrounding funding modules already exist and should stay authoritative for the actual workflow logic:

- `/hyperopen/src/hyperopen/funding/application/hyperunit_query.cljs`
- `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`
- `/hyperopen/src/hyperopen/funding/application/hyperunit_submit.cljs`
- `/hyperopen/src/hyperopen/funding/application/deposit_submit.cljs`
- `/hyperopen/src/hyperopen/funding/application/submit_effects.cljs`
- `/hyperopen/src/hyperopen/funding/domain/lifecycle.cljs`
- `/hyperopen/src/hyperopen/funding/domain/lifecycle_operations.cljs`
- `/hyperopen/src/hyperopen/funding/infrastructure/*.cljs`

The new split should respect that existing layering rather than inventing a parallel funding architecture.

## Plan of Work

Milestone 1 extracts the pure and low-risk support layer out of the facade. Create `/hyperopen/src/hyperopen/funding/effects/common.cljs` and move the static chain configuration, token canonicalization, address-shape validation, amount parsing, amount-formatting, and generic error-message helpers there. This namespace should become the single owner for `normalize-chain-id`, `normalize-address`, `canonical-chain-token`, `same-chain-token?`, `protocol-address-matches-source-chain?`, `parse-usdc-units`, `parse-usdh-units`, `usdc-units->amount-text`, `wallet-error-message`, `fallback-exchange-response-error`, `fallback-runtime-error-message`, `resolve-deposit-chain-config`, and Hyperunit base-URL resolution. Keep these functions small and pure where they already are; do not fold runtime atoms or store mutation into this owner.

Milestone 2 extracts the Hyperunit lifecycle and query adapter layer. Create `/hyperopen/src/hyperopen/funding/effects/hyperunit_runtime.cljs` and move the polling-token atom, poll-token install and clear helpers, existing-address selection, existing-address request wrapper, prefetch wrapper, withdrawal-queue wrapper, lifecycle delay and awaiting-lifecycle helpers, and the three lifecycle-polling wrapper functions into that namespace. This owner should depend on `hyperopen.funding.effects.common`, `hyperopen.funding.application.lifecycle_guards`, `hyperopen.funding.application.hyperunit_query`, `hyperopen.funding.application.lifecycle_polling`, `hyperopen.funding.domain.lifecycle`, and `hyperopen.funding.domain.lifecycle_operations`. Keep `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs` as the behavior owner; the new runtime namespace is only responsible for composing the dependency map and managing the poll-token atom.

Milestone 3 extracts the transport and submit-wrapper layer. Create `/hyperopen/src/hyperopen/funding/effects/transport_runtime.cljs` and move the ERC-20 read wrappers, LiFi and Across route wrappers, Hyperunit address fetch wrappers, and the five submit-wrapper functions there: `submit-hyperunit-address-deposit-request!`, `submit-hyperunit-send-asset-withdraw-request!`, `submit-usdc-bridge2-deposit-tx!`, `submit-usdh-across-deposit-tx!`, and `submit-usdt-lifi-bridge2-deposit-tx!`. This owner should import the concrete infrastructure modules and take shared parsing and config helpers from `hyperopen.funding.effects.common` plus the existing-address helpers from `hyperopen.funding.effects.hyperunit_runtime`.

Milestone 4 reduces `/hyperopen/src/hyperopen/funding/effects.cljs` to a thin public facade. Keep the six public API functions, plus the small modal-close, submit-error, and post-submit refresh helpers if they remain shortest there. Update those public wrappers to delegate to `common`, `hyperunit_runtime`, and `transport_runtime` instead of re-declaring the entire dependency surface inline. The goal is for the facade to express “which owners compose this request” rather than “how every funding boundary works.”

Milestone 5 moves and tightens tests so the split is protected at the same boundaries as the code. Add direct tests under `/hyperopen/test/hyperopen/funding/effects/`:

- `/hyperopen/test/hyperopen/funding/effects/common_test.cljs`
- `/hyperopen/test/hyperopen/funding/effects/hyperunit_runtime_test.cljs`
- `/hyperopen/test/hyperopen/funding/effects/transport_runtime_test.cljs`

Migrate the current assertions from `/hyperopen/test/hyperopen/funding/effects_test.cljs`, `/hyperopen/test/hyperopen/funding/effects_wrappers_test.cljs`, and the dependency-map portion of `/hyperopen/test/hyperopen/funding/effects_api_wrappers_test.cljs` into those new boundary-owned suites. Leave `/hyperopen/test/hyperopen/funding/effects_api_wrappers_test.cljs` focused on the six public facade entrypoints. Keep `/hyperopen/test/hyperopen/funding/application/lifecycle_polling_test.cljs` as the behavioral regression suite for polling semantics; do not duplicate its terminal, retry, and stale-token scenarios in the new wrapper tests.

Milestone 6 retires the exception and closes the loop. Once `/hyperopen/src/hyperopen/funding/effects.cljs` is at or below `500` lines, remove its entry from `/hyperopen/dev/namespace_size_exceptions.edn`. If the facade is still above threshold after the first three extractions, continue moving the remaining small wrappers before touching the exception registry. Do not leave a stale exception entry behind.

## Concrete Steps

From the repository root (`/Users/barry/.codex/worktrees/5526/hyperopen`):

1. Create the new internal source namespaces and move the first low-risk support cluster:

   - `/hyperopen/src/hyperopen/funding/effects/common.cljs`
   - update `/hyperopen/src/hyperopen/funding/effects.cljs`

2. Create the Hyperunit lifecycle runtime owner and move the lifecycle/query adapter cluster:

   - `/hyperopen/src/hyperopen/funding/effects/hyperunit_runtime.cljs`
   - update `/hyperopen/src/hyperopen/funding/effects.cljs`

3. Create the transport runtime owner and move the route, RPC, and submit wrapper cluster:

   - `/hyperopen/src/hyperopen/funding/effects/transport_runtime.cljs`
   - update `/hyperopen/src/hyperopen/funding/effects.cljs`

4. Move tests to the same ownership boundaries:

   - `/hyperopen/test/hyperopen/funding/effects/common_test.cljs`
   - `/hyperopen/test/hyperopen/funding/effects/hyperunit_runtime_test.cljs`
   - `/hyperopen/test/hyperopen/funding/effects/transport_runtime_test.cljs`
   - update `/hyperopen/test/hyperopen/funding/effects_api_wrappers_test.cljs`
   - delete or slim `/hyperopen/test/hyperopen/funding/effects_test.cljs` and `/hyperopen/test/hyperopen/funding/effects_wrappers_test.cljs` only after their assertions have landed in the new boundary suites

5. Keep runtime-facing collaborators stable. `/hyperopen/src/hyperopen/runtime/collaborators.cljs` and `/hyperopen/src/hyperopen/runtime/effect_adapters/funding.cljs` should not need path or key changes. If they do, stop and treat that as a design deviation that must be recorded in this plan.

6. Use the smallest fast feedback loop first:

   - `npm run test:runner:generate`
   - `npx shadow-cljs --force-spawn compile test`

   This repo does not currently expose a supported namespace-filtered CLJS test runner through `npm test`, so the compile step is the safest first validation pass while the split is in flight.

7. After the extracted suites compile, run the full required gates for code changes:

   - `npm test`
   - `npm run check`
   - `npm run test:websocket`

8. After the facade is below threshold, update `/hyperopen/dev/namespace_size_exceptions.edn` and rerun `npm run check` so stale-exception detection proves the cleanup is real.

## Validation and Acceptance

Acceptance is complete when all of the following are true.

`/hyperopen/src/hyperopen/funding/effects.cljs` is `<= 500` lines, and its exception entry has been removed from `/hyperopen/dev/namespace_size_exceptions.edn`.

The public contract exposed by `hyperopen.funding.effects` is unchanged. `/hyperopen/src/hyperopen/runtime/collaborators.cljs` still publishes the same six collaborator keys, and `/hyperopen/src/hyperopen/runtime/effect_adapters/funding.cljs` still calls the same six facade functions without any behavior change.

Boundary tests exist at the same level as the extracted code. `common_test.cljs` proves chain, token, address, amount, and message-formatting behavior. `hyperunit_runtime_test.cljs` proves token-store, query-wrapper, queue-wrapper, and lifecycle-wrapper dependency composition. `transport_runtime_test.cljs` proves route-client, RPC, and submit-wrapper dependency composition. `effects_api_wrappers_test.cljs` proves the facade forwards defaults and overrides into the same application-layer owners as before.

Behavioral funding tests still pass. The existing workflow suites under `/hyperopen/test/hyperopen/funding/application/` and the runtime adapter suite `/hyperopen/test/hyperopen/runtime/effect_adapters/funding_test.cljs` must remain green so the split proves it preserved user-visible behavior and runtime integration.

The required repo gates pass: `npm test`, `npm run check`, and `npm run test:websocket`.

Browser QA is not required for this ticket as written because no `/hyperopen/src/hyperopen/views/**` behavior should change. If the implementation starts touching view namespaces or user-visible funding modal markup, stop and either split that work into a separate UI ticket or add the governed browser-QA path before calling the work complete.

## Idempotence and Recovery

This split is safe to perform incrementally because the public facade stays in place while internal owners move behind it. Move one boundary cluster at a time, run the compile gate, then proceed to the next cluster. If a move breaks tests, restore the facade call through the old namespace temporarily and retry the extraction in smaller pieces; do not force all four responsibility clusters to move in one patch.

Do not delete the exception entry early. The correct recovery path for a partial split is to keep the exception in place until the measured line count proves the facade is below threshold. Likewise, do not delete the old tests until their assertions have been recreated in the new boundary-owned suites.

## Artifacts and Notes

Current hotspot evidence used to select this plan:

  878 src/hyperopen/funding/effects.cljs
  866 src/hyperopen/views/trading_chart/core.cljs
  866 src/hyperopen/views/account_info_view.cljs
  661 src/hyperopen/vaults/effects.cljs
  560 src/hyperopen/startup/runtime.cljs
  453 src/hyperopen/websocket/application/runtime_reducer.cljs
  408 src/hyperopen/views/trading_chart/utils/chart_interop/position_overlays.cljs

Baseline funding boundary-test surface before the split:

  216 test/hyperopen/funding/effects_test.cljs
  294 test/hyperopen/funding/effects_wrappers_test.cljs
  275 test/hyperopen/funding/effects_api_wrappers_test.cljs
  393 test/hyperopen/funding/application/lifecycle_polling_test.cljs

These counts matter because they show both why `funding/effects.cljs` was selected first and why the split should move tests with the code instead of adding only more top-level integration coverage.

Final post-split evidence:

  219 src/hyperopen/funding/effects.cljs
  107 test/hyperopen/funding/effects/common_test.cljs
  215 test/hyperopen/funding/effects/hyperunit_runtime_test.cljs
  202 test/hyperopen/funding/effects/transport_runtime_test.cljs
   52 test/hyperopen/funding/effects/facade_test.cljs

Validation run after restoring `node_modules` with `npm ci`:

  npm run test:runner:generate
  npx shadow-cljs --force-spawn compile test
  npm test
  npm run test:websocket
  npm run check

## Interfaces and Dependencies

The public facade in `/hyperopen/src/hyperopen/funding/effects.cljs` must still export:

- `api-fetch-hyperunit-fee-estimate!`
- `api-fetch-hyperunit-withdrawal-queue!`
- `api-submit-funding-transfer!`
- `api-submit-funding-send!`
- `api-submit-funding-withdraw!`
- `api-submit-funding-deposit!`

Create these internal namespaces and keep them below the size threshold as they are introduced:

- `/hyperopen/src/hyperopen/funding/effects/common.cljs`
- `/hyperopen/src/hyperopen/funding/effects/hyperunit_runtime.cljs`
- `/hyperopen/src/hyperopen/funding/effects/transport_runtime.cljs`

`common.cljs` should own the shared constants and pure support functions currently used across the facade. `hyperunit_runtime.cljs` should own the Hyperunit poll-token atom and the dependency composition around `hyperunit_query` and `lifecycle_polling`. `transport_runtime.cljs` should own the wallet, route-client, ERC-20, and submit-wrapper composition. The facade should import these namespaces and assemble only the public request maps.

The split must continue to use the existing funding application and infrastructure modules, especially:

- `/hyperopen/src/hyperopen/funding/application/hyperunit_query.cljs`
- `/hyperopen/src/hyperopen/funding/application/lifecycle_polling.cljs`
- `/hyperopen/src/hyperopen/funding/application/hyperunit_submit.cljs`
- `/hyperopen/src/hyperopen/funding/application/deposit_submit.cljs`
- `/hyperopen/src/hyperopen/funding/application/submit_effects.cljs`
- `/hyperopen/src/hyperopen/funding/domain/lifecycle.cljs`
- `/hyperopen/src/hyperopen/funding/domain/lifecycle_operations.cljs`
- `/hyperopen/src/hyperopen/funding/infrastructure/erc20_rpc.cljs`
- `/hyperopen/src/hyperopen/funding/infrastructure/hyperunit_address_client.cljs`
- `/hyperopen/src/hyperopen/funding/infrastructure/hyperunit_client.cljs`
- `/hyperopen/src/hyperopen/funding/infrastructure/route_clients.cljs`
- `/hyperopen/src/hyperopen/funding/infrastructure/wallet_rpc.cljs`

No public runtime collaborator keys, request shapes, modal state paths, or follow-up dispatch events should change as part of this plan.

Change note (2026-04-07 / Codex): Initial ExecPlan created after selecting `/hyperopen/src/hyperopen/funding/effects.cljs` as the highest-risk remaining named stateful namespace-size exception and linking the work to `hyperopen-gwu2`.

Change note (2026-04-07 / Codex): Updated the plan after implementation, recorded the missing-`node_modules` environment blocker and `npm ci` recovery step, marked validation complete, and moved the plan from `active/` to `completed/` because acceptance criteria passed and `hyperopen-gwu2` was closed.
