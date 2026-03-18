# TWAP Cancel CRAP Reduction

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Linked work: `hyperopen-oskz` ("Reduce CRAP in order TWAP cancel status parsing", closed as completed).

## Purpose / Big Picture

After this change, the TWAP cancel path in `/hyperopen/src/hyperopen/order/effects.cljs` will keep the same user-visible behavior while carrying less branch complexity in the hotspot function that currently dominates the file's CRAP report. A contributor should be able to run the order-effects tests, then generate fresh coverage and confirm that `twap-cancel-status-error-value` no longer appears as the same high-risk hotspot.

The observable behavior stays simple: successful TWAP termination still shows `TWAP terminated.`, malformed or exchange-supplied error statuses still surface a trimmed human-readable error string, and the refactor is guarded by direct public-path tests through `api-cancel-order`.

## Progress

- [x] (2026-03-18 01:50Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/.agents/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md` for planning, validation, and `bd` requirements.
- [x] (2026-03-18 01:50Z) Created and claimed `hyperopen-oskz` so this plan references live `bd` work as required by `/hyperopen/docs/PLANS.md`.
- [x] (2026-03-18 01:52Z) Inspected `/hyperopen/src/hyperopen/order/effects.cljs` and `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs` to identify the public TWAP cancel seam and the duplicated error-normalization logic.
- [x] (2026-03-18 01:53Z) Authored this active ExecPlan before implementation.
- [x] (2026-03-18 01:57Z) Replaced the duplicated TWAP parser by extracting shared status-normalization helpers in `/hyperopen/src/hyperopen/order/effects.cljs` and routing `twap-cancel-outcome` through `cancel-status-error-value`.
- [x] (2026-03-18 01:59Z) Added four public-path TWAP cancel regressions in `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs` covering success, trimmed string failure, nested `:error` map failure, and whitespace-only error payloads.
- [x] (2026-03-18 02:05Z) Discovered the workspace was missing `node_modules`, ran `npm ci`, and restored the repo-declared JavaScript toolchain (`shadow-cljs`, `zod`, `smol-toml`) without changing tracked source files.
- [x] (2026-03-18 02:12Z) Passed `npm run check`, `npm test`, `npm run test:websocket`, and `npm run coverage`, then generated a fresh CRAP report showing zero `crappy-functions` in `/hyperopen/src/hyperopen/order/effects.cljs`.
- [x] (2026-03-18 02:03Z) Closed `hyperopen-oskz`, moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/`, and prepared the final handoff summary.

## Surprises & Discoveries

- Observation: the current CRAP hotspot is not just the private helper body; it is amplified by the lack of direct TWAP cancel branch coverage in the public order-effects suite.
  Evidence: `rg -n "TWAP termination failed|TWAP terminated|twapCancel" /hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs` returned no matches before implementation.

- Observation: the repo-local CRAP tool does not calculate anything unless `coverage/lcov.info` already exists.
  Evidence: `bb tools/crap_report.clj --module src/hyperopen/order/effects.cljs --format json` failed with `Missing coverage/lcov.info. Run npm run coverage first.`

- Observation: the validation failures seen on the first pass were environmental rather than source regressions.
  Evidence: before `npm ci`, `npm test` failed with `sh: shadow-cljs: command not found` and `npm run check` failed in the multi-agent Node tests with `ERR_MODULE_NOT_FOUND` for `zod` and `smol-toml`; after `npm ci`, the same commands passed unchanged.

- Observation: removing the duplicate TWAP helper and covering the shared cancel parser eliminated the hotspot entirely instead of merely lowering it under the threshold.
  Evidence: `bb tools/crap_report.clj --module src/hyperopen/order/effects.cljs --format json` now reports `crappy-functions: 0`, `max-crap: 19.744470588235295`, and no entry for `twap-cancel-status-error-value`.

## Decision Log

- Decision: reduce CRAP by extracting shared status normalization helpers instead of merely adding more tests around the current `twap-cancel-status-error-value` body.
  Rationale: CRAP is driven by both complexity and coverage. The fastest stable reduction is to shrink the branch surface of the hotspot itself and then cover the remaining public behavior.
  Date/Author: 2026-03-18 / Codex

- Decision: add tests through `core/api-cancel-order` rather than exposing or directly invoking private helpers.
  Rationale: the repository already treats `order-effects` as an effect module with public seams; behavior-level tests preserve encapsulation and protect the real user-visible TWAP cancel flow.
  Date/Author: 2026-03-18 / Codex

- Decision: remove `twap-cancel-status-error-value` entirely and reuse `cancel-status-error-value` rather than keeping two thin wrappers.
  Rationale: the functions were behaviorally identical, so keeping both names would preserve duplication without adding clarity. Reusing the shared cancel parser is the smallest change that meaningfully reduces complexity.
  Date/Author: 2026-03-18 / Codex

- Decision: run `npm ci` once the first validation pass proved the workspace lacked repo-declared JavaScript dependencies.
  Rationale: the lockfile was present and the failures were toolchain-resolution errors, so restoring the expected local install was the least risky way to complete the required validation gates.
  Date/Author: 2026-03-18 / Codex

## Outcomes & Retrospective

The change is complete. `/hyperopen/src/hyperopen/order/effects.cljs` now uses `non-success-status-text`, `status-entry-error-value`, `error-value-message`, and `nonblank-message` as small shared helpers, and `twap-cancel-outcome` reuses `cancel-status-error-value` instead of maintaining a second copy of the same parser. `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs` now includes direct TWAP cancel behavior coverage for the public effect path.

This reduced overall complexity. The previous hotspot existed because two identical parsing trees were maintained separately and one of them lacked direct TWAP-facing coverage. The final CRAP report for `/hyperopen/src/hyperopen/order/effects.cljs` shows no functions above the threshold, with module `max-crap` down to `19.744470588235295` on `api-cancel-order` and the user-reported `twap-cancel-status-error-value` no longer present.

## Context and Orientation

`/hyperopen/src/hyperopen/order/effects.cljs` owns runtime behavior for order submit, cancel, TWAP termination, position TP/SL, and isolated-margin updates. The user-facing cancel entrypoint is `api-cancel-order`. That function calls `twap-cancel-outcome` when `[:action :type]` is `"twapCancel"` and otherwise calls the normal batch `cancel-outcome`.

The hotspot called out by the user was `twap-cancel-status-error-value`. It repeated the same broad three-stage logic already used by normal cancel status handling: inspect the raw status entry, extract an `:error` payload or non-`"success"` string, convert maps and other values into a message string, trim whitespace, and return `nil` for blank success-like values. The normal cancel path already used `cancel-status-error-value` with effectively the same branch structure. That duplication inflated complexity without adding distinct behavior.

The main regression suite for this file is `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`. Existing tests already exercised normal cancel success, total failure, and partial batch failure through `core/api-cancel-order`; this change added the missing direct TWAP cancel coverage on that same public seam.

## Plan of Work

First, introduce small helper functions in `/hyperopen/src/hyperopen/order/effects.cljs` that each do one job: normalize a non-success string status into a candidate error payload, extract an error payload from a status entry, and convert any candidate error payload into a trimmed display string. Then rewrite `cancel-status-error-value` as a thin wrapper over those helpers and remove the duplicate TWAP-only helper entirely. Keep the normal cancel semantics unchanged, including `"success"` string handling and map `:error` precedence.

Next, extend `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs` with TWAP-focused tests that go through `core/api-cancel-order`. Add at least one success case proving `TWAP terminated.` still appears, plus failure cases that prove string statuses, nested `{:error {:message ...}}` maps, and blank/whitespace values are normalized the same way after the refactor.

Finally, run the required repository validation gates, then generate fresh coverage and a module-scoped CRAP report for `/hyperopen/src/hyperopen/order/effects.cljs`. Record the resulting score movement and whether the hotspot is still present. If a follow-up hotspot remains, capture that in the retrospective and in `bd` only if more work is needed beyond this session.

## Concrete Steps

Work from `/hyperopen`.

1. Edit `/hyperopen/src/hyperopen/order/effects.cljs` to replace the duplicated TWAP cancel status parser with shared helper functions and narrower wrappers.
2. Edit `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs` to add public-path TWAP cancel regression tests.
3. Restore the repo-local JavaScript toolchain when needed:

      npm ci

4. Run:

      npm run check
      npm test
      npm run test:websocket
      npm run coverage
      bb tools/crap_report.clj --module src/hyperopen/order/effects.cljs --format json

5. Update this plan with outcomes, discoveries, and exact validation notes.
6. Close `hyperopen-oskz` with `bd close hyperopen-oskz --reason "Completed" --json` after acceptance passes.

## Validation and Acceptance

Acceptance is complete when all of the following are true:

1. `core/api-cancel-order` still reports TWAP success with a success toast of `TWAP terminated.` when the exchange returns an `ok` response with success-like status data.
2. `core/api-cancel-order` still reports TWAP failure with `TWAP termination failed: <message>` when the exchange returns a non-success status string or an `:error` map containing a message.
3. Blank strings and whitespace-only error payloads do not produce misleading error text.
4. `npm run check`, `npm test`, and `npm run test:websocket` pass.
5. `npm run coverage` completes and `bb tools/crap_report.clj --module src/hyperopen/order/effects.cljs --format json` shows that `twap-cancel-status-error-value` is no longer reported and that the module has zero functions above the CRAP threshold.

## Idempotence and Recovery

The refactor is source-local and safe to rerun. If a test fails mid-change, restore the last known-good helper structure in `/hyperopen/src/hyperopen/order/effects.cljs`, rerun the public order-effects tests, and retry in smaller edits. Coverage and CRAP generation are read-only with respect to source, so they can be repeated until the evidence is captured cleanly.

## Artifacts and Notes

Important baseline artifacts before implementation:

    bd issue: hyperopen-oskz
    hotspot: twap-cancel-status-error-value in /hyperopen/src/hyperopen/order/effects.cljs
    reported CRAP: 101.91
    pre-implementation CRAP tool result: Missing coverage/lcov.info. Run npm run coverage first.

Important completion artifacts:

    npm run check: pass
    npm test: Ran 2489 tests containing 13057 assertions. 0 failures, 0 errors.
    npm run test:websocket: Ran 396 tests containing 2263 assertions. 0 failures, 0 errors.
    npm run coverage: Statements 90.68%, Branches 68.45%, Functions 85.48%, Lines 90.68%.
    bb tools/crap_report.clj --module src/hyperopen/order/effects.cljs --format json:
      crappy-functions: 0
      max-crap: 19.744470588235295
      removed hotspot: twap-cancel-status-error-value

## Interfaces and Dependencies

No public API changes are planned. The touched interfaces remain:

- `/hyperopen/src/hyperopen/order/effects.cljs`
  `api-cancel-order`
  `cancel-status-error-value`
  `twap-cancel-outcome`
  `non-success-status-text`
  `status-entry-error-value`
  `error-value-message`
  `nonblank-message`

- `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`
  `core/api-cancel-order` integration-style regression tests

Plan revision note: updated this ExecPlan on 2026-03-18 after implementation to record the shared-helper refactor, the temporary `npm ci` environment repair required for validation, and the final CRAP/coverage results before moving the plan to completed.
