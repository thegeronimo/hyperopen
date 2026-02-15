# Raise Lowest Function Coverage for Telemetry/UI/WS Schema Wallet Paths

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The current coverage report still has red and near-red function coverage hotspots in utility and websocket-focused paths. After this change, those lowest rows will have direct tests that execute their currently uncalled functions, reducing blind spots for browser-safety telemetry, UI preference restoration, schema contract validation in websocket runs, and wallet address watcher behavior. A user-visible proof is that the next generated `coverage/index.html` shows materially higher function coverage for the listed rows and all required repository gates stay green.

## Progress

- [x] (2026-02-15 23:22Z) Identified current lowest function-coverage rows from `coverage/index.html`: `test/.../telemetry`, `test/.../ui`, `ws-test/.../hyperopen`, `ws-test/.../schema`, `ws-test/.../wallet`.
- [x] (2026-02-15 23:22Z) Mapped rows to concrete files and uncovered function names from `coverage/lcov.info`.
- [x] (2026-02-15 23:23Z) Created active ExecPlan with baseline metrics and implementation sequence.
- [x] (2026-02-15 23:26Z) Added focused tests for `telemetry/console_warning.cljs` and `ui/preferences.cljs` in the main test suite.
- [x] (2026-02-15 23:27Z) Added focused tests for `platform.cljs` and `telemetry.cljs`; added websocket-specific low-coverage suite matching ws-test `:ns-regexp`.
- [x] (2026-02-15 23:27Z) Expanded `wallet/address_watcher_test.cljs` to execute record/protocol/listener/start-watching paths.
- [x] (2026-02-15 23:29Z) Added websocket schema coverage assertions in `hyperopen.websocket.coverage-low-functions-test` to execute currently missed contract functions in ws-test context.
- [x] (2026-02-15 23:32Z) Ran `npm run check`, `npm test`, `npm run test:websocket`, and `npm run coverage` successfully after final edits.
- [x] (2026-02-15 23:32Z) Captured final before/after coverage metrics for all five target rows.

## Surprises & Discoveries

- Observation: `ws-test/.../schema` function coverage is low primarily because websocket runner excludes existing schema contract test namespaces that already exist in the main test runner.
  Evidence: `test/test_runner.cljs` includes `hyperopen.schema.contracts-test` and `hyperopen.schema.contracts-coverage-test`, while `test/websocket_test_runner.cljs` does not.
- Observation: `test/.../telemetry` low row maps to `/hyperopen/src/hyperopen/telemetry/console_warning.cljs`, which has no dedicated tests.
  Evidence: Coverage file breakdown shows only `console_warning.cljs` with `0/5` function coverage.
- Observation: `ws-test/.../hyperopen` low function coverage is concentrated in `platform.cljs` and `telemetry.cljs`.
  Evidence: Breakdown shows `platform.cljs` `3/11` and `telemetry.cljs` `3/8` functions in ws-test output.
- Observation: Updating `test/websocket_test_runner.cljs` alone does not affect `npm run test:websocket` coverage because the ws build uses `:ns-regexp`, not runner namespace lists.
  Evidence: `/hyperopen/shadow-cljs.edn` defines `:ws-test` with `:ns-regexp "^(hyperopen\\.websocket\\..*-test|hyperopen\\.wallet\\.address-watcher-test)$"`.
- Observation: Requiring `hyperopen.system` inside a ws-only coverage test pulled in extra wallet modules and temporarily reduced wallet aggregate function coverage.
  Evidence: Wallet row dropped when `agent_session.cljs` entered ws-test coverage; removing `hyperopen.system` and using a local valid app-state fixture restored wallet row to high coverage.

## Decision Log

- Decision: Cover the low rows with deterministic unit-level tests at function/module boundaries rather than broad integration additions.
  Rationale: This directly addresses missed functions with minimal runtime complexity and lower flake risk.
  Date/Author: 2026-02-15 / Codex
- Decision: Improve ws-test schema coverage by including existing schema contract suites in websocket runner.
  Rationale: Reuses maintained contract tests and avoids duplicating validation logic in parallel suites.
  Date/Author: 2026-02-15 / Codex
- Decision: Run new `platform` and `telemetry` tests in both runners.
  Rationale: The same modules appear in both test and ws-test coverage trees; dual-run gives consistent coverage uplift.
  Date/Author: 2026-02-15 / Codex

## Outcomes & Retrospective

Pending implementation. This section will be updated with final coverage deltas and gate results.
Implementation completed. All five targeted low-function rows moved from low/medium to high function coverage.

Before -> after function coverage:

- `test/dev/out/cljs-runtime/hyperopen/telemetry`: `0/5` (`0%`) -> `5/5` (`100%`)
- `test/dev/out/cljs-runtime/hyperopen/ui`: `0/1` (`0%`) -> `1/1` (`100%`)
- `ws-test/dev/out/cljs-runtime/hyperopen/schema`: `5/21` (`23.8%`) -> `21/21` (`100%`)
- `ws-test/dev/out/cljs-runtime/hyperopen`: `6/19` (`31.57%`) -> `17/19` (`89.47%`)
- `ws-test/dev/out/cljs-runtime/hyperopen/wallet`: `6/12` (`50%`) -> `10/12` (`83.33%`)

Validation summary:

- `npm run check`: pass
- `npm test`: pass (854 tests, 3829 assertions, 0 failures, 0 errors)
- `npm run test:websocket`: pass (91 tests, 301 assertions, 0 failures, 0 errors)
- `npm run coverage`: pass (overall function coverage `1882/2463`, `76.41%`)

## Context and Orientation

Coverage targets and baselines for this plan:

- `test/dev/out/cljs-runtime/hyperopen/telemetry`: function coverage `0/5` (`0%`)
- `test/dev/out/cljs-runtime/hyperopen/ui`: function coverage `0/1` (`0%`)
- `ws-test/dev/out/cljs-runtime/hyperopen`: function coverage `6/19` (`31.57%`)
- `ws-test/dev/out/cljs-runtime/hyperopen/schema`: function coverage `5/21` (`23.8%`)
- `ws-test/dev/out/cljs-runtime/hyperopen/wallet`: function coverage `6/12` (`50%`)

Primary files behind those rows:

- `/hyperopen/src/hyperopen/telemetry/console_warning.cljs`
- `/hyperopen/src/hyperopen/ui/preferences.cljs`
- `/hyperopen/src/hyperopen/platform.cljs`
- `/hyperopen/src/hyperopen/telemetry.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`
- `/hyperopen/src/hyperopen/wallet/address_watcher.cljs`

Relevant existing test runners:

- Main runner: `/hyperopen/test/test_runner.cljs`
- Websocket runner: `/hyperopen/test/websocket_test_runner.cljs`

## Plan of Work

Milestone 1 adds utility tests for browser-console warning and UI preference restoration. The tests will mock browser globals (`document`, `console.log`, local-storage accessors) to execute code paths safely in Node-based CLJS tests.

Milestone 2 adds deterministic tests for platform wrappers and telemetry event logging. These tests exercise timer/microtask/local-storage wrappers, telemetry emit/log/event serialization, and clear/reset behavior.

Milestone 3 expands wallet address watcher tests to execute uncovered record/protocol and watcher-listener paths. Tests will verify record creation, protocol dispatch, start-watching/listener notifications, and pending subscription behavior.

Milestone 4 raises websocket schema coverage by including existing schema contract suites in the websocket test runner.

Milestone 5 runs required validation gates and regenerates coverage, then records exact coverage deltas for each target row.

## Concrete Steps

From `/hyperopen`:

1. Add tests:
   - `/hyperopen/test/hyperopen/telemetry/console_warning_test.cljs`
   - `/hyperopen/test/hyperopen/ui/preferences_test.cljs`
   - `/hyperopen/test/hyperopen/platform_test.cljs`
   - `/hyperopen/test/hyperopen/telemetry_test.cljs`
2. Expand:
   - `/hyperopen/test/hyperopen/wallet/address_watcher_test.cljs`
3. Update runners:
   - `/hyperopen/test/test_runner.cljs`
   - `/hyperopen/test/websocket_test_runner.cljs`
4. Run commands:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`
   - `npm run coverage`
5. Validate target row metrics in `/hyperopen/coverage/index.html`.

## Validation and Acceptance

Acceptance criteria:

- All five target rows show increased function coverage versus baseline.
- Red/lowest hotspots are no longer the same uncovered-function profile.
- Required gates pass:
  - `npm run check`
  - `npm test`
  - `npm run test:websocket`

## Idempotence and Recovery

Changes are additive tests and runner wiring only. Re-running commands is safe. If a global-mocking test causes cross-test contamination, restore original globals in `finally` blocks and rerun affected suites.

## Artifacts and Notes

Baseline metrics captured from `coverage/index.html` generated at `2026-02-15T23:19:26Z`.

## Interfaces and Dependencies

No production interfaces change. Tests rely on existing public functions and protocol surfaces in:

- `/hyperopen/src/hyperopen/telemetry/console_warning.cljs`
- `/hyperopen/src/hyperopen/ui/preferences.cljs`
- `/hyperopen/src/hyperopen/platform.cljs`
- `/hyperopen/src/hyperopen/telemetry.cljs`
- `/hyperopen/src/hyperopen/wallet/address_watcher.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`

Plan revision note: 2026-02-15 23:23Z - Initial plan created for the latest low-function coverage rows in test and ws-test output.
Plan revision note: 2026-02-15 23:32Z - Updated living sections after implementation, ws-regexp-aligned tests, and successful validation/coverage reruns with before/after metrics.
