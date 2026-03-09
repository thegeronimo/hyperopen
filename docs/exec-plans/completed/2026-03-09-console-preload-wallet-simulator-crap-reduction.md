---
owner: platform
status: completed
last_reviewed: 2026-03-09
review_cycle_days: 90
source_of_truth: false
---

# Reduce Console Preload Wallet Simulator CRAP

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, the dev-only wallet and exchange simulator helpers behind `HYPEROPEN_DEBUG` remain behaviorally identical, but the simulator logic is extracted out of `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` into a focused internal namespace with smaller pure helpers and direct regression tests. A contributor should be able to change simulator request handling or QA reset behavior without reopening one 700-line preload namespace or relying on indirect debug-API smoke coverage.

The observable proof from `/hyperopen` is:

    npm run check
    npm test
    npm run test:websocket
    npm run coverage
    npm run crap:report -- --module src/hyperopen/telemetry/console_preload.cljs --format json
    npm run crap:report -- --module src/hyperopen/telemetry/console_preload/simulators.cljs --format json

The first three commands are the repository gates. The coverage and CRAP commands prove that the old hotspot functions are no longer in `console_preload.cljs` and that the touched preload/simulator namespaces have no functions above the default CRAP threshold of `30`.

## Progress

- [x] (2026-03-09 23:02Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/ARCHITECTURE.md`, the target preload/test namespaces, and prior CRAP-remediation ExecPlans to confirm scope and guardrails.
- [x] (2026-03-09 23:02Z) Created and claimed `bd` issue `hyperopen-zcd` for this hotspot reduction work.
- [x] (2026-03-09 23:02Z) Authored this active ExecPlan.
- [x] (2026-03-09 23:08Z) Extracted the wallet/exchange simulator block into `/hyperopen/src/hyperopen/telemetry/console_preload/simulators.cljs` and rewired `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` to delegate the JS debug API entries without changing names or behavior.
- [x] (2026-03-09 23:10Z) Added focused simulator regression tests in `/hyperopen/test/hyperopen/telemetry/console_preload/simulators_test.cljs` and regenerated `/hyperopen/test/test_runner_generated.cljs`.
- [x] (2026-03-09 23:16Z) Added direct preload helper tests in `/hyperopen/test/hyperopen/telemetry/console_preload_test.cljs` for `normalize-debug-wire-value` and both `wait-for-idle` resolution modes after the first CRAP rerun surfaced two remaining preload hotspots.
- [x] (2026-03-09 23:20Z) Ran `npm run check`, `npm test`, `npm run test:websocket`, `npm run coverage`, and both module CRAP reports successfully; confirmed zero functions above the CRAP threshold in the touched preload and simulator namespaces.
- [x] (2026-03-09 23:20Z) Close `hyperopen-zcd` and move this plan to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` is 742 lines, well above the architecture guidance target for new namespaces under 500 lines.
  Evidence: `wc -l src/hyperopen/telemetry/console_preload.cljs` returned `742`.
- Observation: The named hotspots sit inside one cohesive simulator block rather than being scattered across the preload namespace.
  Evidence: `normalize-wallet-simulator-config` starts at line 411 and `install-wallet-simulator!` starts at line 500, adjacent to `clear-wallet-simulator!`, `emit-wallet-simulator!`, exchange simulator helpers, and `qa-reset!`.
- Observation: This worktree initially lacks `node_modules` and fresh coverage artifacts.
  Evidence: `test -d node_modules && echo present || echo missing` returned `missing`, and `npm run crap:report -- --module src/hyperopen/telemetry/console_preload.cljs --format json` failed with `Missing coverage/lcov.info. Run npm run coverage first.`
- Observation: Removing the simulator block from `console_preload.cljs` was not sufficient by itself to meet the zero-hotspot target for the preload namespace.
  Evidence: The first post-extraction CRAP report for `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` still reported `wait-for-idle` at `CRAP 124.52` and `normalize-debug-wire-value` at `CRAP 33.0`.
- Observation: `wait-for-idle` timeout-path coverage is easier to trigger by mutating the real app-store route path during the poll loop than by relying on asynchronous `with-redefs`.
  Evidence: The final passing test toggles `[:router :path]` on the real `app-system/store` atom at 1ms intervals until `waitForIdle` returns `{:settled false ...}` under a `quiet-ms` threshold of `20`.

## Decision Log

- Decision: Use a structural split into a new internal namespace `/hyperopen/src/hyperopen/telemetry/console_preload/simulators.cljs` instead of only extracting file-local helpers.
  Rationale: The hotspot functions live in one cohesive dev-only simulator boundary, and moving that block out reduces both per-function CRAP and the oversized preload namespace without changing the public debug API.
  Date/Author: 2026-03-09 / Codex
- Decision: Keep `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` as the only preload owner of `HYPEROPEN_DEBUG`.
  Rationale: Prior debug-API work and the current dev build wiring already establish that namespace as the single dev preload entrypoint. This refactor should reduce complexity, not widen the externally visible preload surface.
  Date/Author: 2026-03-09 / Codex
- Decision: Treat success as zero remaining CRAP hotspots above threshold in the touched preload/simulator namespaces, not just lower scores for the two named functions.
  Rationale: Recent CRAP-remediation work in this repo closes issues when the affected module drops below threshold entirely. That yields a clearer finish line and prevents simply relocating a hotspot into a new file.
  Date/Author: 2026-03-09 / Codex
- Decision: When the first CRAP rerun still flagged preload-only helpers, widen direct preload coverage instead of splitting the namespace a second time.
  Rationale: `wait-for-idle` and `normalize-debug-wire-value` were already modestly sized after the simulator extraction. Their scores were dominated by missing direct coverage, so targeted tests were the smallest safe change that achieved the zero-hotspot target.
  Date/Author: 2026-03-09 / Codex

## Outcomes & Retrospective

Implementation completed. `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` now delegates wallet/exchange simulator behavior to `/hyperopen/src/hyperopen/telemetry/console_preload/simulators.cljs`, and the simulator boundary has direct regression coverage in `/hyperopen/test/hyperopen/telemetry/console_preload/simulators_test.cljs`. The preload smoke test file also now directly exercises `normalize-debug-wire-value` and `wait-for-idle`, which removed the last CRAP hotspots in the preload namespace without changing the dev-only `HYPEROPEN_DEBUG` interface.

This reduced overall complexity. The original user-reported hotspots were `normalize-wallet-simulator-config` at `CRAP 240.00` and `install-wallet-simulator!` at `CRAP 380.00` inside a 742-line preload namespace. After the refactor and widened tests:

- `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` dropped to 534 lines and the module CRAP report showed `crappy-functions = 0`, `project-crapload = 0.0`, `wait-for-idle` at `CRAP 12.17`, and `normalize-debug-wire-value` at `CRAP 9.02`.
- `/hyperopen/src/hyperopen/telemetry/console_preload/simulators.cljs` reported `crappy-functions = 0`, `project-crapload = 0.0`, `normalize-wallet-simulator-config` at `CRAP 4.0`, and module `max-crap = 6.32`.

## Context and Orientation

`/hyperopen/src/hyperopen/telemetry/console_preload.cljs` is the dev-only preload that installs `globalThis.HYPEROPEN_DEBUG`. It currently owns four broad responsibilities: runtime action dispatch helpers, DOM/oracle helpers, QA simulator helpers, and snapshot/download utilities. The hotspot block begins with wallet simulator config normalization and continues through wallet simulator state management, exchange simulator installation, and `qa-reset!`.

The wallet simulator emulates an EIP-1193 provider for the real wallet runtime in `/hyperopen/src/hyperopen/wallet/core.cljs`. It installs a fake `window.ethereum`, responds to `eth_accounts`, `eth_requestAccounts`, typed-data signing, and chain switching, then wires the simulator into `wallet-core` by setting the provider override, resetting listener state, and reattaching listeners against `app-system/store`.

The exchange simulator is the trading-side analogue in `/hyperopen/src/hyperopen/api/trading.cljs`. `qa-reset!` is the coordination helper that clears debug action traces, resets wallet handler suppression, clears both simulators, clears telemetry, and clears websocket flight recording.

The current direct tests in `/hyperopen/test/hyperopen/telemetry/console_preload_test.cljs` only prove that these functions are exposed and that wallet-connected-handler suppression toggles correctly. This refactor will add a new focused simulator test namespace for the extracted logic while keeping the existing preload test namespace as a public API smoke suite.

## Plan of Work

First, create `/hyperopen/src/hyperopen/telemetry/console_preload/simulators.cljs` and move the simulator-owned atoms, constants, snapshots, install/clear/emit helpers, exchange simulator helpers, and `qa-reset!` into it. Keep the namespace internal and focused on the dev-only simulator boundary. Import the same collaborators currently used by the preload namespace: `app-system`, `wallet-core`, `trading-api`, `telemetry`, `runtime-validation`, and `ws-client`.

Second, decompose the current hotspot helpers while moving them. `normalize-wallet-simulator-config` should become a short assembler over small pure helpers for coercing map-like input, reading aliased keys, normalizing string vectors, and normalizing optional message fields. `install-wallet-simulator!` should become a coordinator that delegates to helpers for capturing previous globals, building request handlers, building listener handlers, installing the provider, and activating the wallet-core override/listener path.

Third, update `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` so it requires the new simulator namespace and only delegates debug API entries to it. The JS debug API names and semantics must remain unchanged.

Fourth, add `/hyperopen/test/hyperopen/telemetry/console_preload/simulators_test.cljs` with direct regression coverage for normalization, provider request methods, listener behavior, install/clear restore behavior, and `qa-reset!`. Keep `/hyperopen/test/hyperopen/telemetry/console_preload_test.cljs` as the public debug-API smoke layer and update it only if required by the extraction.

Finally, regenerate the test runner, run the required gates, generate fresh coverage, rerun module CRAP reports for both touched namespaces, update this plan with the results, close `hyperopen-zcd`, and move this file to `/hyperopen/docs/exec-plans/completed/`.

## Concrete Steps

From `/hyperopen`:

1. Create `/hyperopen/src/hyperopen/telemetry/console_preload/simulators.cljs` and move/refactor the simulator helpers out of `/hyperopen/src/hyperopen/telemetry/console_preload.cljs`.
2. Rewire the debug API assembly in `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` to delegate to the new namespace without changing JS-facing names.
3. Add `/hyperopen/test/hyperopen/telemetry/console_preload/simulators_test.cljs`.
4. Regenerate `/hyperopen/test/test_runner_generated.cljs` with `npm run test:runner:generate`.
5. Run:

       npm ci
       npm run check
       npm test
       npm run test:websocket
       npm run coverage
       npm run crap:report -- --module src/hyperopen/telemetry/console_preload.cljs --format json
       npm run crap:report -- --module src/hyperopen/telemetry/console_preload/simulators.cljs --format json

Expected result: the gates pass, both CRAP reports show `crappy-functions` equal to `0`, and the named hotspot functions no longer appear in `console_preload.cljs`.

## Validation and Acceptance

The work is complete when all of the following are true:

- The JS debug API still exposes `qaReset`, `setWalletConnectedHandlerMode`, `installWalletSimulator`, `walletSimulatorEmit`, `clearWalletSimulator`, `installExchangeSimulator`, and `clearExchangeSimulator`.
- The extracted simulator namespace directly covers map and JS-object config normalization, camelCase/kebab-case aliases, default values, each supported provider request method, `wallet_switchEthereumChain` chain updates plus `chainChanged` emission, listener registration/removal, install/clear restoration of previous globals and wallet-core override state, and `qa-reset!` side effects.
- `npm run check`, `npm test`, and `npm run test:websocket` pass.
- After `npm run coverage`, both module CRAP reports complete successfully and show no functions above the threshold in the touched preload/simulator namespaces.

## Idempotence and Recovery

This refactor is source-only plus test additions. Re-running the extraction steps is safe. If the new simulator namespace introduces a regression, verify the direct simulator tests first because they isolate the request and listener contract faster than the full preload smoke tests. If the worktree is missing dependencies, `npm ci` is the expected recovery step before the validation commands. If coverage generation fails after the functional gates pass, rerun only `npm run coverage` and the CRAP report commands once the environment is stable, because those steps do not alter application code.

## Artifacts and Notes

Issue tracking:

- `bd` issue `hyperopen-zcd`: "Reduce CRAP in console preload wallet simulator helpers"

Pre-change hotspot context from the user report:

- `install-wallet-simulator!` in `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` at line 500 scored `CRAP 380.00`.
- `normalize-wallet-simulator-config` in `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` at line 411 scored `CRAP 240.00`.

Post-implementation validation from `/hyperopen`:

- `npm run check` passed.
- `npm test` passed with `Ran 2222 tests containing 11654 assertions. 0 failures, 0 errors.`
- `npm run test:websocket` passed with `Ran 370 tests containing 2120 assertions. 0 failures, 0 errors.`
- `npm run coverage` passed with `Statements 91.02%`, `Branches 67.84%`, `Functions 86.05%`, and `Lines 91.02%`.
- `npm run crap:report -- --module src/hyperopen/telemetry/console_preload.cljs --format json` reported `crappy-functions = 0`, `project-crapload = 0.0`, and `max-crap = 20.0`.
- `npm run crap:report -- --module src/hyperopen/telemetry/console_preload/simulators.cljs --format json` reported `crappy-functions = 0`, `project-crapload = 0.0`, and `max-crap = 6.318359375`.

## Interfaces and Dependencies

The public JS debug API exposed by `/hyperopen/src/hyperopen/telemetry/console_preload.cljs` must remain stable.

The new internal namespace must export compatible ClojureScript entrypoints for:

    install-wallet-simulator!
    emit-wallet-simulator!
    clear-wallet-simulator!
    set-wallet-connected-handler-mode!
    install-exchange-simulator!
    clear-exchange-simulator!
    qa-reset!

It may also expose file-local or namespace-private helpers for config normalization, request dispatch, listener management, global-state capture, and snapshot assembly. It must not add any production build behavior or new public runtime API.

Plan revision note: 2026-03-09 23:02Z - Initial active ExecPlan created after claiming `hyperopen-zcd` and auditing the preload hotspot block, repo guardrails, and current test/coverage state.
Plan revision note: 2026-03-09 23:20Z - Recorded the implemented simulator extraction, widened preload helper coverage, passing repository gates, and final CRAP reports showing zero hotspots in both touched namespaces.
