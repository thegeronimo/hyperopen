# Retire Funding Policy Namespace-Size Exception

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan follows `.agents/PLANS.md` from the repository root. Issue lifecycle tracking for this active work is `bd` issue `hyperopen-eh3c`.

## Purpose / Big Picture

`src/hyperopen/funding/domain/policy.cljs` is a pure funding-domain namespace that currently mixes several responsibilities in one 706-line file: amount and address normalization, funding balance availability, HyperUnit lifecycle and fee display policy, and deposit/send/transfer/withdraw preview request construction. The repository enforces a default 500-line namespace-size target, so this file needs a responsibility-based split rather than another larger exception.

After this change, callers can continue using the public `hyperopen.funding.domain.policy` namespace exactly as before, but maintainers can reason about and test each funding policy responsibility in a smaller file. The observable outcome is internal: the funding policy tests still pass, `npm run lint:namespace-sizes` no longer needs an exception for `src/hyperopen/funding/domain/policy.cljs`, and the required repo gates pass.

## Progress

- [x] (2026-05-01 23:25Z) Created and claimed `bd` issue `hyperopen-eh3c`.
- [x] (2026-05-01 23:25Z) Inspected `src/hyperopen/funding/domain/policy.cljs`, `test/hyperopen/funding/domain/policy_test.cljs`, and `test/hyperopen/funding/domain/policy_preview_test.cljs`.
- [x] (2026-05-01 23:26Z) Ran the RED guard by removing the funding policy exception and confirming `npm run lint:namespace-sizes` fails on `src/hyperopen/funding/domain/policy.cljs`.
- [x] (2026-05-01 23:28Z) Extracted amount/address formatting helpers to `src/hyperopen/funding/domain/amounts.cljs`.
- [x] (2026-05-01 23:28Z) Extracted balance and withdrawable availability policy to `src/hyperopen/funding/domain/availability.cljs`.
- [x] (2026-05-01 23:28Z) Extracted HyperUnit lifecycle, explorer, fee, and withdrawal queue policy to `src/hyperopen/funding/domain/hyperunit.cljs`.
- [x] (2026-05-01 23:28Z) Extracted request preview builders to `src/hyperopen/funding/domain/preview.cljs`.
- [x] (2026-05-01 23:28Z) Converted `src/hyperopen/funding/domain/policy.cljs` into a stable facade that preserves existing public names and the private test seams used by current tests.
- [x] (2026-05-01 23:32Z) Ran focused funding policy tests, namespace-size lint, and required gates: `npm run lint:namespace-sizes`, `npm run lint:namespace-boundaries`, `npm test`, `npm run test:websocket`, and `npm run check`.
- [x] (2026-05-01 23:33Z) Moved this ExecPlan to `docs/exec-plans/completed/` after acceptance passed.

## Surprises & Discoveries

- Observation: The existing tests use private vars from `hyperopen.funding.domain.policy`, including `direct-balance-row-available`, `summary-derived-withdrawable`, `withdrawable-usdc`, and `withdraw-preview-error`.
  Evidence: `test/hyperopen/funding/domain/policy_test.cljs` and `test/hyperopen/funding/domain/policy_preview_test.cljs` dereference those vars through `@#'hyperopen.funding.domain.policy/...`.

- Observation: The RED namespace-size guard catches the exact retired exception before production code changes.
  Evidence: `npm run lint:namespace-sizes` exited `1` with `[missing-size-exception] src/hyperopen/funding/domain/policy.cljs - namespace has 706 lines; add an exception entry in dev/namespace_size_exceptions.edn`.

- Observation: Splitting by responsibility leaves every new production funding policy namespace below the default 500-line size threshold.
  Evidence: `wc -l` reports `policy.cljs` 48 lines, `amounts.cljs` 60 lines, `availability.cljs` 188 lines, `hyperunit.cljs` 161 lines, and `preview.cljs` 311 lines.

- Observation: The first `npm test` attempt compiled successfully but failed before assertions because declared npm dependency `lucide` was absent from `node_modules`.
  Evidence: Node threw `Cannot find module 'lucide/dist/esm/icons/external-link.js'`; `package.json` and `package-lock.json` already declared `lucide`, and `npm install` restored the package without tracked manifest changes.

## Decision Log

- Decision: Keep `hyperopen.funding.domain.policy` as a facade and move implementations behind it.
  Rationale: The request is a pure-domain first cut, and preserving public API names keeps callers stable while allowing the oversized implementation to split by responsibility.
  Date/Author: 2026-05-01 / Codex

- Decision: Use the existing namespace-size linter as the RED guard by retiring the exception before production extraction.
  Rationale: This is a structural refactor whose target behavior is the absence of the exception; `npm run lint:namespace-sizes` should fail before the split and pass after the split.
  Date/Author: 2026-05-01 / Codex

- Decision: Keep preview construction in a dedicated `hyperopen.funding.domain.preview` namespace rather than in the facade.
  Rationale: Deposit, send, transfer, and withdraw previews share amount parsing and availability policy but form a separate request-construction responsibility from balance derivation and HyperUnit lifecycle display.
  Date/Author: 2026-05-01 / Codex

## Outcomes & Retrospective

The funding policy split is complete. `src/hyperopen/funding/domain/policy.cljs` is now a 48-line stable facade, and the moved implementation lives in focused pure-domain namespaces for amounts, availability, HyperUnit policy, and preview request construction. This reduces complexity by separating four responsibilities that previously had to be read together, while keeping the public `hyperopen.funding.domain.policy` API stable for callers.

The only validation wrinkle was environmental: `node_modules/lucide` was missing even though `package.json` and `package-lock.json` declared it. Running `npm install` restored declared dependencies and did not change tracked dependency manifests.

## Context and Orientation

The root file for this work is `src/hyperopen/funding/domain/policy.cljs`. It is consumed by funding workflow code and tests as the public funding policy namespace. "Facade" means a small namespace that keeps stable function names and delegates to more focused implementation namespaces. The facade matters because the rest of the app should not need to change imports for a maintainability refactor.

The current responsibilities are:

- Amount and address helpers: `non-blank-text`, `parse-num`, `finite-number?`, `amount->text`, `normalize-amount-input`, `parse-input-amount`, `normalize-evm-address`, `normalize-withdraw-destination`, `format-usdc-display`, and `format-usdc-input`.
- Availability helpers: balance row parsing, spot and perps withdrawable calculation, withdraw asset enrichment/filtering/selection, `transfer-max-amount`, and `withdraw-max-amount`.
- HyperUnit helpers: lifecycle failure detection and recovery hints, explorer transaction URLs, fee estimate lookup, withdrawal queue lookup, chain-unit fee display.
- Preview builders: `transfer-preview`, `send-preview`, `withdraw-preview`, `deposit-preview`, and top-level `preview`.

The focused tests are `test/hyperopen/funding/domain/policy_test.cljs` and `test/hyperopen/funding/domain/policy_preview_test.cljs`. The generated runner already includes both tests. The repository validation gates for code changes are `npm run check`, `npm test`, and `npm run test:websocket`.

## Plan of Work

First remove the exception entry for `src/hyperopen/funding/domain/policy.cljs` from `dev/namespace_size_exceptions.edn` and run `npm run lint:namespace-sizes`. The command should fail because the file is still above 500 lines. This proves the guard catches the exact structural debt being retired.

Next extract the implementation in four pure namespaces. `amounts.cljs` should only depend on `clojure.string` and `hyperopen.domain.trading`. `availability.cljs` should depend on `clojure.string`, `hyperopen.funding.domain.amounts`, and `hyperopen.funding.domain.assets`. `hyperunit.cljs` should depend on `clojure.string`, `hyperopen.funding.domain.amounts`, and `hyperopen.funding.domain.lifecycle`. `preview.cljs` should depend on `hyperopen.funding.domain.amounts`, `hyperopen.funding.domain.assets`, and `hyperopen.funding.domain.availability`.

Finally replace `policy.cljs` with a small facade. Public names should remain callable as `hyperopen.funding.domain.policy/<name>`. Existing private test seams should remain present in the facade as private aliases so the current tests do not need to be rewritten just to observe the same behavior.

## Concrete Steps

Run all commands from `/Users/barry/.codex/worktrees/0532/hyperopen`.

1. Remove the funding policy exception from `dev/namespace_size_exceptions.edn`.
2. Run `npm run lint:namespace-sizes`. Expected RED result: failure mentioning `src/hyperopen/funding/domain/policy.cljs` has more than 500 lines and is missing a size exception.
3. Add the four extracted namespaces and convert `policy.cljs` into a facade.
4. Run `npm test -- --help` only if the test runner command shape is unclear; otherwise use the repo’s existing `npm test` gate and the focused generated CLJS tests through the normal runner.
5. Run `npm run lint:namespace-sizes`, `npm run check`, `npm test`, and `npm run test:websocket`.
6. Update this plan with validation evidence, move it to `docs/exec-plans/completed/`, and close `hyperopen-eh3c`.

## Validation and Acceptance

Acceptance is met when:

- `dev/namespace_size_exceptions.edn` no longer contains `src/hyperopen/funding/domain/policy.cljs`.
- `src/hyperopen/funding/domain/policy.cljs` is below the default namespace-size threshold.
- The new extracted namespaces are each below the default namespace-size threshold.
- The existing policy tests still pass through `npm test`.
- `npm run lint:namespace-sizes`, `npm run check`, `npm test`, and `npm run test:websocket` exit `0`.

## Idempotence and Recovery

The refactor is additive until the facade replacement step. If a moved helper causes a test failure, keep the extracted namespace and fix the facade or dependency direction rather than reverting unrelated files. If namespace-size lint finds a new oversized extracted namespace, split that namespace by responsibility before re-adding any exception. Do not run `git pull --rebase` or `git push` during this work.

## Artifacts and Notes

The RED and final validation transcripts will be summarized here after the commands run.

RED transcript:

    > hyperopen@0.1.0 lint:namespace-sizes
    > bb -m dev.check-namespace-sizes

    [missing-size-exception] src/hyperopen/funding/domain/policy.cljs - namespace has 706 lines; add an exception entry in dev/namespace_size_exceptions.edn

Initial green structural checks:

    npm run lint:namespace-sizes
    Namespace size check passed.

    npm run lint:namespace-boundaries
    Namespace boundary check passed.

Behavioral test transcript:

    npm test
    Ran 3680 tests containing 20281 assertions.
    0 failures, 0 errors.

Websocket test transcript:

    npm run test:websocket
    Ran 520 tests containing 3027 assertions.
    0 failures, 0 errors.

Full gate transcript summary:

    npm run check
    Docs check passed.
    Namespace size check passed.
    Namespace boundary check passed.
    [:app] Build completed. (962 files, 937 compiled, 0 warnings, 13.01s)
    [:portfolio] Build completed. (707 files, 706 compiled, 0 warnings, 9.29s)
    [:portfolio-worker] Build completed. (62 files, 61 compiled, 0 warnings, 3.61s)
    [:portfolio-optimizer-worker] Build completed. (77 files, 76 compiled, 0 warnings, 4.25s)
    [:vault-detail-worker] Build completed. (63 files, 62 compiled, 0 warnings, 3.58s)
    [:test] Build completed. (1536 files, 4 compiled, 0 warnings, 5.47s)

Revision note: 2026-05-01. Updated this plan after implementation and validation to record the final split, the dependency restoration wrinkle, and the evidence from all required gates.

Revision note: 2026-05-01. Moved this plan from active to completed after all acceptance criteria passed.

## Interfaces and Dependencies

At completion, these implementation namespaces must exist:

- `hyperopen.funding.domain.amounts`
- `hyperopen.funding.domain.availability`
- `hyperopen.funding.domain.hyperunit`
- `hyperopen.funding.domain.preview`

The stable facade namespace `hyperopen.funding.domain.policy` must continue to expose the public functions currently used by callers:

- `non-blank-text`
- `parse-num`
- `finite-number?`
- `amount->text`
- `normalize-amount-input`
- `parse-input-amount`
- `normalize-evm-address`
- `normalize-withdraw-destination`
- `normalize-mode`
- `normalize-deposit-step`
- `normalize-withdraw-step`
- `withdraw-assets`
- `withdraw-assets-filtered`
- `withdraw-asset`
- `transfer-max-amount`
- `withdraw-max-amount`
- `format-usdc-display`
- `format-usdc-input`
- `hyperunit-lifecycle-failure?`
- `hyperunit-lifecycle-recovery-hint`
- `hyperunit-explorer-tx-url`
- `hyperunit-fee-entry`
- `hyperunit-withdrawal-queue-entry`
- `estimate-fee-display`
- `transfer-preview`
- `send-preview`
- `withdraw-preview`
- `deposit-preview`
- `preview`
