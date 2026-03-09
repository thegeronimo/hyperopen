# Lift Coverage for Runtime Effect Adapters

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The `coverage/index.html` report shows low coverage for `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs`, `/hyperopen/src/hyperopen/runtime/effect_adapters/funding.cljs`, `/hyperopen/src/hyperopen/runtime/effect_adapters/order.cljs`, and `/hyperopen/src/hyperopen/runtime/effect_adapters/vaults.cljs`. These files are adapter boundaries: they do not hold deep domain logic, but they do decide which runtime helpers, projections, API functions, and side-effect seams are wired together. After this change, `npm test` and the generated coverage report should exercise those wiring decisions directly, so regressions in dependency injection or wrapper arities fail fast.

## Progress

- [x] (2026-03-09 16:11Z) Read `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, the four target adapter namespaces, and their current tests.
- [x] (2026-03-09 16:11Z) Created and claimed `bd` issue `hyperopen-i0s` for this coverage task.
- [x] (2026-03-09 16:11Z) Authored this active ExecPlan with the target files, test strategy, and validation gates.
- [x] (2026-03-09 16:27Z) Added focused adapter tests for the vault, order, funding, and websocket seams that were still unexecuted.
- [x] (2026-03-09 16:34Z) Ran `npm run check`, `npm test`, `npm run test:websocket`, and `npm run coverage`; captured the resulting coverage lift for the four target files.

## Surprises & Discoveries

- Observation: `vaults.cljs` is the largest pure-wrapper gap.
  Evidence: The current report row is `18/108` statements and `0/10` functions covered, while the file is almost entirely thin effect wrappers.
- Observation: `order.cljs` similarly lacks direct adapter execution even though downstream order feedback runtime behavior is already tested elsewhere.
  Evidence: `/hyperopen/test/hyperopen/order/feedback_runtime_test.cljs` covers the runtime helper internals, but `/hyperopen/test/hyperopen/runtime/effect_adapters/order_test.cljs` currently only checks facade var identity.
- Observation: The websocket adapter already has a few focused tests, so the remaining lift should come from smaller wrapper-path assertions rather than new domain fixtures.
  Evidence: `/hyperopen/test/hyperopen/runtime/effect_adapters/websocket_test.cljs` already covers health synchronization, asset persistence, and init/reconnect seams.
- Observation: The local JS environment was not bootstrapped when validation started.
  Evidence: Before `npm ci`, `npm test` failed with `Cannot find module '@noble/secp256k1'` and `shadow-cljs: command not found` because `node_modules` was absent.
- Observation: Order adapter tests were more robust when they exercised the real toast/runtime behavior instead of redefining the toast helpers directly.
  Evidence: The first implementation used `with-redefs` around order feedback helper vars and failed at runtime; switching to real toast creation plus stubbed downstream `order-effects/*` entry points passed consistently in `npm test`.

## Decision Log

- Decision: Increase coverage by extending the existing adapter test namespaces instead of widening the `:test` build or adding new production seams.
  Rationale: The low rows are in adapter namespaces that already belong in `npm test`; the missing coverage is unexecuted wrapper code, not absent build inclusion.
  Date/Author: 2026-03-09 / Codex
- Decision: Use `npm ci` before the final validation run.
  Rationale: The repository lockfile existed and the missing `node_modules` tree prevented the required gates from running.
  Date/Author: 2026-03-09 / Codex
- Decision: Validate order adapter runtime binding through actual toast state and timeout ownership rather than redefining the helper functions.
  Rationale: That path proved the adapter contract directly and avoided redefinition behavior that did not hold under the compiled test build.
  Date/Author: 2026-03-09 / Codex

## Outcomes & Retrospective

Implementation completed with test-only changes plus one local environment bootstrap step (`npm ci`).

Observed coverage after `npm run coverage`:

- `vaults.cljs`: `18/108 -> 107/108` statements and lines, `0/10 -> 9/10` functions, `0/11 -> 9/11` branches.
- `websocket.cljs`: `216/293 -> 258/293` statements and lines, `17/27 -> 24/27` functions, branches now `41/48` (`85.41%`) versus `63.04%` in the baseline screenshot.
- `funding.cljs`: `123/154 -> 148/154` statements and lines, `5/12 -> 8/12` functions, `11/25 -> 14/25` branches.
- `order.cljs`: `86/100 -> 90/100` statements and lines, branches `7/18 -> 10/18`; function coverage remained `6/14`, but the adapter now has direct runtime-binding coverage for the toast and factory paths that were previously unexecuted by `npm test`.

Validation results:

- `npm run check`: pass
- `npm test`: pass
- `npm run test:websocket`: pass
- `npm run coverage`: pass

Overall complexity stayed roughly flat. The new tests add surface area, but they are localized to the existing adapter test namespaces and replace implicit coverage gaps with explicit adapter-contract assertions.

## Context and Orientation

The target files are all under `/hyperopen/src/hyperopen/runtime/effect_adapters/`. Each namespace exposes runtime effect handlers that sit between Nexus action/effect dispatch and lower-level modules such as `/hyperopen/src/hyperopen/vaults/effects.cljs`, `/hyperopen/src/hyperopen/order/effects.cljs`, `/hyperopen/src/hyperopen/funding/effects.cljs`, `/hyperopen/src/hyperopen/funding_comparison/effects.cljs`, and the websocket subscription/diagnostics modules.

The existing test entry points are:

- `/hyperopen/test/hyperopen/runtime/effect_adapters/vaults_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/order_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/funding_test.cljs`
- `/hyperopen/test/hyperopen/runtime/effect_adapters/websocket_test.cljs`

Those files already compile in the standard `:test` build because the `shadow-cljs.edn` `:test` regex includes `hyperopen\\.(?!websocket\\.).*-test`, which matches `hyperopen.runtime.effect-adapters.*-test`.

## Plan of Work

Extend `/hyperopen/test/hyperopen/runtime/effect_adapters/vaults_test.cljs` with direct wrapper tests for every fetch adapter plus the submit adapter. Each test will stub the corresponding `hyperopen.vaults.effects` function, invoke the adapter, and assert that the adapter passes the expected store, request arguments, and canonical API/projection functions.

Extend `/hyperopen/test/hyperopen/runtime/effect_adapters/order_test.cljs` with direct coverage for the feedback-toast helpers, default and explicit runtime arities, and the four API submit/cancel wrapper factories. The tests should prove that order adapter deps preserve the runtime-specific toast seam.

Extend `/hyperopen/test/hyperopen/runtime/effect_adapters/funding_test.cljs` with tests for the remaining fetch wrappers, invalid-coin predictability early return, and the send/transfer/withdraw/deposit adapter wrappers so both default and injected `show-toast!` branches execute.

Extend `/hyperopen/test/hyperopen/runtime/effect_adapters/websocket_test.cljs` with tests for fetch-candle adapter injection/defaults, unsubscribe and subscription wrappers, refresh/reset/copy/confirm helpers, and startup restore wiring. Where a private helper is only reachable through an injected callback, execute that callback from the stubbed downstream function so the branch is genuinely covered.

## Concrete Steps

From `/hyperopen`:

1. Edit the four runtime adapter test files listed above.
2. Run:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
   - `npm run coverage`
3. Inspect the coverage report rows for the four target namespaces and record the outcome in this plan.

Completed command log:

- `npm ci`
- `npm run check`
- `npm test`
- `npm run test:websocket`
- `npm run coverage`

Expected observable output:

  - The adapter test namespaces continue to pass in `npm test`.
  - `coverage/index.html` shows improved statement/function coverage for all four target files, with `vaults.cljs` and `order.cljs` moving materially from their current low-function baselines.

## Validation and Acceptance

Acceptance requires all required validation gates to pass:

- `npm run check`
- `npm test`
- `npm run test:websocket`
- `npm run coverage`

The coverage requirement is met when the report rows for the four target adapter namespaces increase from the current baseline and the new tests directly exercise the adapter wiring paths described above.

## Idempotence and Recovery

This work changes tests and documentation only. The edits are safe to rerun. If a wrapper assertion becomes too brittle because an injected dependency legitimately changes, update the assertion to reflect the adapter contract rather than deleting coverage.

## Artifacts and Notes

Current baseline from the user-provided coverage screenshot:

- `vaults.cljs`: `18/108` statements, `0/11` branches, `0/10` functions, `18/108` lines
- `websocket.cljs`: `216/293` statements, `29/46` branches, `17/27` functions, `216/293` lines
- `funding.cljs`: `123/154` statements, `11/25` branches, `5/12` functions, `123/154` lines
- `order.cljs`: `86/100` statements, `7/18` branches, `6/14` functions, `86/100` lines

Observed final report rows from `coverage/coverage-summary.json`:

- `/hyperopen/.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen/runtime/effect_adapters/funding.cljs`: statements/lines `148/154`, functions `8/12`, branches `14/25`
- `/hyperopen/.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen/runtime/effect_adapters/order.cljs`: statements/lines `90/100`, functions `6/14`, branches `10/18`
- `/hyperopen/.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen/runtime/effect_adapters/vaults.cljs`: statements/lines `107/108`, functions `9/10`, branches `9/11`
- `/hyperopen/.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen/runtime/effect_adapters/websocket.cljs`: statements/lines `258/293`, functions `24/27`, branches `41/48`

## Interfaces and Dependencies

No production interfaces change.

Dependencies used by the tests include:

- Runtime adapter namespaces in `/hyperopen/src/hyperopen/runtime/effect_adapters/*.cljs`
- Downstream effect/runtime namespaces already required by those adapters
- `with-redefs` to stub side-effect functions while preserving adapter signatures

Plan revision note: 2026-03-09 16:11Z - Initial plan created after repository/work-tracking review and target test seam inventory.
Plan revision note: 2026-03-09 16:34Z - Recorded completed test additions, dependency bootstrap, validation results, and final per-file coverage metrics; moved the plan to `/hyperopen/docs/exec-plans/completed/`.
