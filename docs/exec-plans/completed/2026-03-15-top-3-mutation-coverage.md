# Close Top-3 Mutation Coverage Gaps

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Linked `bd` issue: `hyperopen-zjvj`.

## Purpose / Big Picture

Hyperopen has targeted mutation-testing evidence showing three concrete blind spots in production logic that is already otherwise covered by the normal test suite: `/hyperopen/src/hyperopen/api/trading.cljs`, `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`, and `/hyperopen/src/hyperopen/websocket/orderbook_policy.cljs`. After this change, the repository should have direct tests for the missing parser, fallback, retry, and zero-value branches that mutation testing reported as surviving or uncovered. The visible proof is that the targeted mutation commands for those three modules report stronger results, while normal repository gates still pass and no public API changes are introduced.

## Progress

- [x] (2026-03-16 01:50Z) Created and claimed `bd` issue `hyperopen-zjvj`.
- [x] (2026-03-16 01:50Z) Created the active ExecPlan in `/hyperopen/docs/exec-plans/active/2026-03-15-top-3-mutation-coverage.md`.
- [x] (2026-03-16 02:04Z) Expanded `/hyperopen/test/hyperopen/api/trading/internal_seams_test.cljs`, `/hyperopen/test/hyperopen/api/trading/sign_and_submit_test.cljs`, and `/hyperopen/test/hyperopen/api/trading/session_invalidation_test.cljs` to cover simulator state transitions, JSON parsing and validation hooks, nonce error detection, chain-id parsing, signing-context fallback, missing-private-key rejection, retry exhaustion, payload destructuring, and session invalidation preservation branches.
- [x] (2026-03-16 02:19Z) Expanded `/hyperopen/test/hyperopen/vaults/domain/transfer_policy_test.cljs` to cover direct `parse-usdc-micros` boundary cases plus invalid, zero, and withdraw-all preview behavior.
- [x] (2026-03-16 02:27Z) Expanded `/hyperopen/test/hyperopen/websocket/orderbook_policy_test.cljs` to cover invalid price sorting, zero-value cumulative math, and default depth fallback behavior.
- [x] (2026-03-16 02:28Z) Ran focused validation while iterating and kept the change test-only because the new assertions did not expose a production defect.
- [x] (2026-03-16 02:42Z) Ran `npm run coverage`, the three targeted mutation commands, `npm run check`, `npm test`, and `npm run test:websocket` successfully on the final test-only code state.
- [x] (2026-03-16 02:44Z) Filed follow-up issue `hyperopen-e4v9` for the remaining likely-equivalent or tool-limited mutation residuals in `api/trading` and `transfer_policy`.
- [x] (2026-03-16 02:46Z) Closed `hyperopen-zjvj` and moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: The local `node_modules` state was incomplete enough that `npm test` could not find `shadow-cljs` until dependencies were installed.
  Evidence: The first test run failed with `sh: shadow-cljs: command not found`; after `npm install`, the required runners were available and the repository gates passed.

- Observation: `api/trading` line 128 now appears to be a genuinely equivalent survivor rather than a missing assertion.
  Evidence: After adding direct positive and negative tests for `nonce-error-response?`, the only remaining survivor in `/hyperopen/src/hyperopen/api/trading.cljs` is `= -> not=` at line 128, but the function still requires `str/includes? text "nonce"`, which already implies non-empty `text` and makes the status predicate mutation behaviorally irrelevant for the tested contract.

- Observation: The uncovered `api/trading` mutation sites in the signing/session helper cluster persisted even after adding explicit branch tests for retry exhaustion, missing private key, and session invalidation handling.
  Evidence: The targeted rerun reports only lines 366, 457, 458, 461, 468, 473, 478, and 490 as uncovered, while the surrounding helper behavior is exercised by new assertions in `/hyperopen/test/hyperopen/api/trading/sign_and_submit_test.cljs` and `/hyperopen/test/hyperopen/api/trading/session_invalidation_test.cljs`, which suggests a mutation coverage mapping gap rather than an untested user-visible branch.

- Observation: `transfer_policy` line 46 also looks equivalent after direct numeric-boundary tests were added.
  Evidence: The only remaining survivor in `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` is `0 -> 1` for the fallback in `(or (parse-utils/parse-int-value fraction-padded) 0)`, but `fraction-padded` is already constrained to a zero-padded digit string and the new boundary tests show the parser path succeeds for the reachable inputs.

## Decision Log

- Decision: Scope this pass to the three fully executed weak spots from the latest mutation run and leave larger scan-only modules out of scope.
  Rationale: The reported highest-confidence gaps are already localized to these three modules and can be closed without broadening into new product work.
  Date/Author: 2026-03-16 / Codex
- Decision: Prefer public behavior tests, but use private seam access for parser and signing helpers that do not expose stable public hooks.
  Rationale: This keeps most tests behavior-oriented while still letting the suite cover mutation branches that otherwise require brittle end-to-end fixtures.
  Date/Author: 2026-03-16 / Codex
- Decision: Keep the implementation test-only for this issue and defer the two residual mutation cases instead of changing production code to satisfy likely-equivalent mutants.
  Rationale: None of the new assertions exposed a real defect or behavioral regression, and the remaining survivors are better handled as documented follow-up work than by distorting reachable production logic.
  Date/Author: 2026-03-16 / Codex

## Outcomes & Retrospective

Implemented and validated with test-only changes. The repository changes are limited to the five target test namespaces, and no public API, schema, or runtime contract changed.

The `api/trading` suite now covers simulator set/clear semantics, `parse-json!` body parsing and contract validation, `nonce-error-response?`, chain-id normalization, missing-chain fallback in signing context resolution, private-key rejection, retry exhaustion, retry success, missing API wallet invalidation behavior, preserved-session behavior, and signature payload destructuring. `transfer_policy` now has direct `parse-usdc-micros` coverage for integer, leading-decimal, trailing-decimal, truncation, zero, max-safe-integer boundary, overflow, and smallest-positive-unit inputs, plus preview coverage for invalid vaults, invalid and zero amounts, and withdraw-all zero-USD requests. `orderbook_policy` now covers invalid-price sorting, zero-valued cumulative math, empty cumulative output, and default depth fallback behavior.

The targeted mutation reruns materially improved the three scoped modules. `/hyperopen/src/hyperopen/api/trading.cljs` improved from the reported `35/43` kills to `42/43` kills with `1` remaining survivor and the same `8` uncovered helper-cluster sites. `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` improved from `20/26` kills to `25/26` kills with `0` uncovered sites. `/hyperopen/src/hyperopen/websocket/orderbook_policy.cljs` finished at `16/16` kills with `0` survivors and `0` uncovered sites.

The remaining mutation residuals were documented instead of papered over. Follow-up issue `hyperopen-e4v9` tracks the likely-equivalent `api/trading` survivor at line 128, the likely-equivalent `transfer_policy` survivor at line 46, and the still-uncovered `api/trading` signing/session helper lines that appear to be a mutation coverage mapping limitation. Complexity stayed flat to slightly lower because the production code did not change; only the behavioral contract around the existing code is now more explicit.

## Context and Orientation

The mutation tool lives under `/hyperopen/tools/mutate.clj` and reports survivors or uncovered sites by source line. The current target modules already have focused test namespaces:

- `/hyperopen/test/hyperopen/api/trading/internal_seams_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/sign_and_submit_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/session_invalidation_test.cljs`
- `/hyperopen/test/hyperopen/vaults/domain/transfer_policy_test.cljs`
- `/hyperopen/test/hyperopen/websocket/orderbook_policy_test.cljs`

`api/trading` contains pure helpers and asynchronous request/signing paths around JSON parsing, chain-id normalization, simulator responses, nonce retry, and session invalidation. `transfer_policy` is pure domain logic for vault-transfer modal preview and USDC micros parsing. `orderbook_policy` is pure websocket shaping logic for price sorting, cumulative totals, and render-depth slicing. The expected implementation shape is test-first and mostly test-only. Production code should only change if a new failing regression test demonstrates an actual bug rather than just a shallow assertion gap.

## Plan of Work

First, extend the existing `api/trading` tests to cover the branches named in the mutation report. Add direct assertions for debug simulator set/clear semantics, `parse-json!` text parsing and validation invocation, `nonce-error-response?`, `parse-chain-id-int`, user signing context fallback, and the private `sign-and-post-agent-action!` branches for default options, retry exhaustion, missing private key rejection, and both missing-api-wallet invalidate and preserve behavior.

Second, expand the vault transfer policy tests so `parse-usdc-micros` is explicitly exercised for integer, trailing-decimal, leading-decimal, six-digit truncation, zero, and overflow behavior. Add preview assertions for invalid vault routing, disabled deposits, invalid or zero amount input, localized decimal input, and withdraw-all producing a valid zero-USD request.

Third, expand the orderbook policy tests to cover invalid or missing price fallback to `0`, invalid or missing size fallback to `0`, cumulative totals starting from zero, empty cumulative output, and `build-book` using the default depth limit when `max-levels` is invalid while keeping the correct display slices and best bid/ask selection.

Fourth, run targeted tests, then the targeted mutation commands, then the required repository gates. If a mutant still survives and appears equivalent or unhelpful to kill without distorting the code, document that specific evidence here and create follow-up `bd` work before finishing.

## Concrete Steps

From the repository root:

1. Edit the target test files listed above.
2. Run focused tests while iterating:
   - `npm test`
   - `npm run test:websocket`
3. Rebuild LCOV:
   - `npm run coverage`
4. Run targeted mutation commands:
   - `bb tools/mutate.clj --module src/hyperopen/api/trading.cljs --suite test --mutate-all`
   - `bb tools/mutate.clj --module src/hyperopen/vaults/domain/transfer_policy.cljs --suite test --mutate-all`
   - `bb tools/mutate.clj --module src/hyperopen/websocket/orderbook_policy.cljs --suite auto --mutate-all`
5. Run final gates:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance requires all new tests to pass, the required repository gates to pass, and the targeted mutation reruns for the three scoped modules to improve enough that the currently reported survivor and uncovered lines are no longer present or are explicitly documented as equivalent/deferred with follow-up tracking. No public API, schema, or runtime contract should change.

## Idempotence and Recovery

The edits are limited to tracked test files unless a real regression forces a production fix. The commands listed above are safe to rerun. If mutation testing is interrupted, rerunning the same command should restore the original module source through the existing mutation-tool backup logic before continuing.

## Artifacts and Notes

Updated test namespaces:

- `/hyperopen/test/hyperopen/api/trading/internal_seams_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/sign_and_submit_test.cljs`
- `/hyperopen/test/hyperopen/api/trading/session_invalidation_test.cljs`
- `/hyperopen/test/hyperopen/vaults/domain/transfer_policy_test.cljs`
- `/hyperopen/test/hyperopen/websocket/orderbook_policy_test.cljs`

Mutation report artifacts captured during this pass:

- `/hyperopen/target/mutation/reports/2026-03-16T02-12-46.913778Z-src-hyperopen-api-trading.cljs.edn`
- `/hyperopen/target/mutation/reports/2026-03-16T02-37-01.198580Z-src-hyperopen-vaults-domain-transfer_policy.cljs.edn`
- `/hyperopen/target/mutation/reports/2026-03-16T02-42-18.019798Z-src-hyperopen-websocket-orderbook_policy.cljs.edn`

Validation commands completed successfully on the final code state:

- `npm run coverage`
- `bb tools/mutate.clj --module src/hyperopen/api/trading.cljs --suite test --mutate-all`
- `bb tools/mutate.clj --module src/hyperopen/vaults/domain/transfer_policy.cljs --suite test --mutate-all`
- `bb tools/mutate.clj --module src/hyperopen/websocket/orderbook_policy.cljs --suite auto --mutate-all`
- `npm run check`
- `npm test`
- `npm run test:websocket`

## Interfaces and Dependencies

No public interface changes are planned. The work depends on the existing ClojureScript test runner, the websocket test runner, and the repo-local mutation tool. Private seam access through `@#'namespace/private-var` is allowed for targeted helper coverage in `api/trading`.

Plan revision note: 2026-03-16 01:50Z - Initial plan authored for `hyperopen-zjvj` before test expansion.
Plan revision note: 2026-03-16 02:44Z - Updated after test-only implementation, successful repository gates, targeted mutation reruns, and filing follow-up issue `hyperopen-e4v9` for the residual likely-equivalent or tool-limited mutation cases.
Plan revision note: 2026-03-16 02:46Z - Finalized after closing `hyperopen-zjvj` and moving the ExecPlan to `completed`.
