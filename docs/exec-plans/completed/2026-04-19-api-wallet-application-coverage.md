# Increase API-Wallet Application Coverage

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan follows `.agents/PLANS.md` from the repository root. Keep it self-contained and update it whenever scope, evidence, or implementation decisions change. The `bd` issue for this work is `hyperopen-l0sb`, titled `Increase API-wallet application test coverage`; it was closed as completed on 2026-04-19 at 21:48Z.

## Purpose / Big Picture

The API-wallet page lets a user name an API wallet, enter a wallet address, choose how many days the agent key should remain valid, and sort the displayed wallets. The current implementation already appears to contain the intended behavior, but the coverage screenshot and mutation scan show that two small application namespaces need stronger direct tests. After this work, a contributor can run the ClojureScript tests, coverage generation, and module-scoped mutation commands and observe that form validation, generated-key matching, expiry preview math, sort state normalization, and form-field normalization are all locked by repeatable tests.

This is expected to be a test-coverage pass. Do not change public APIs or production behavior unless the new tests reveal a real defect. If production code must change, record the defect, the failing command, and the rationale in this plan before making the smallest possible source edit.

## Progress

- [x] (2026-04-19 21:16Z) Confirmed live `bd` issue `hyperopen-l0sb` with `bd show hyperopen-l0sb --json`; it is an `IN_PROGRESS` task for increasing coverage on the two API-wallet application modules.
- [x] (2026-04-19 21:16Z) Audited the target source and existing tests: `src/hyperopen/api_wallets/application/form_policy.cljs`, `src/hyperopen/api_wallets/application/ui_state.cljs`, `test/hyperopen/api_wallets/application/form_policy_test.cljs`, and `test/hyperopen/api_wallets/application/ui_state_test.cljs`.
- [x] (2026-04-19 21:16Z) Confirmed the current mutation scan artifacts without coverage report `8` total mutation sites for `form_policy.cljs` and `11` total mutation sites for `ui_state.cljs`.
- [x] (2026-04-19 21:24Z) Added focused form-policy coverage in `test/hyperopen/api_wallets/application/form_policy_test.cljs` for nil/partial default merges, whitespace `:days-valid`, dynamic max-days error messaging, generated-key address matching, invalid-address non-match behavior, and expiry preview nil/arithmetic behavior.
- [x] (2026-04-19 21:24Z) Added focused UI-state coverage in `test/hyperopen/api_wallets/application/ui_state_test.cljs` for sort aliases/fallbacks, nil sort-state defaults, next-sort-state edges, form-field aliases/unsupported inputs, and normalize-form-value contracts.
- [x] (2026-04-19 21:28Z) Worker reproduced the RED contract with `npm test`; the run failed only in `generated-private-key-requires-a-normalized-valid-address-match-test`, reporting `1 failures, 0 errors` across `3298` tests and `18013` assertions.
- [x] (2026-04-19 21:28Z) Worker implemented the smallest production fix in `src/hyperopen/api_wallets/application/form_policy.cljs`: `generated-private-key` now requires both generated and form addresses to normalize to valid wallet addresses before comparing them.
- [x] (2026-04-19 21:28Z) Worker reran `npm test`; the run passed with `3298` tests, `18013` assertions, `0 failures`, and `0 errors`.
- [x] (2026-04-19 21:31Z) Worker ran the required post-source-change gates: `npm run check` exited `0`, `npm test` exited `0`, and `npm run test:websocket` exited `0` with `449` tests, `2701` assertions, `0 failures`, and `0 errors`.
- [x] (2026-04-19 21:35Z) Regenerated coverage with `npm run coverage`; the command exited `0`, wrote `coverage/lcov.info`, and reported overall coverage at `90.62%` statements, `69.45%` branches, `83.58%` functions, and `90.62%` lines.
- [x] (2026-04-19 21:38Z) Ran targeted mutation for `src/hyperopen/api_wallets/application/form_policy.cljs`; report `target/mutation/reports/2026-04-19T21-36-19.503581Z-src-hyperopen-api_wallets-application-form_policy.cljs.edn` shows `8` total sites, `8` covered, `0` uncovered, and `8/8` killed.
- [x] (2026-04-19 21:43Z) Ran targeted mutation for `src/hyperopen/api_wallets/application/ui_state.cljs`; report `target/mutation/reports/2026-04-19T21-41-46.717336Z-src-hyperopen-api_wallets-application-ui_state.cljs.edn` shows `11` total sites, `11` covered, `0` uncovered, and `11/11` killed.
- [x] (2026-04-19 21:44Z) Reran required final gates after coverage and mutation: `npm run check` exited `0`, `npm test` exited `0` with `3298` tests and `18013` assertions, and `npm run test:websocket` exited `0` with `449` tests and `2701` assertions.
- [x] (2026-04-19 21:44Z) Updated this ExecPlan with validation output, mutation report paths, remaining risks, and final outcome before moving it out of `active`.

## Surprises & Discoveries

- Observation: There was no active ExecPlan at `docs/exec-plans/active/2026-04-19-api-wallet-application-coverage.md` before this plan was created.
  Evidence: `ls -la docs/exec-plans/active` showed only `README.md` and `artifacts/`.

- Observation: The mutation scan artifacts exist, but they were generated without coverage-aware counts because `coverage/lcov.info` was not available to those scan runs.
  Evidence: `target/mutation/reports/2026-04-19T21-14-50.116768Z-src-hyperopen-api_wallets-application-form_policy.cljs.edn` reports `:total-sites 8` and `:coverage-available? false`; `target/mutation/reports/2026-04-19T21-14-50.116777Z-src-hyperopen-api_wallets-application-ui_state.cljs.edn` reports `:total-sites 11` and `:coverage-available? false`.

- Observation: The two target production namespaces are pure application logic rather than browser interaction code.
  Evidence: `form_policy.cljs` and `ui_state.cljs` export pure functions and depend only on `clojure.string`, the API-wallet UI-state namespace, and `hyperopen.wallet.agent-session`; no browser APIs, DOM calls, or view-rendering paths are involved.

- Observation: The RED-phase test batch exposed a real generated-key policy defect: two invalid wallet addresses normalize to `nil`, and `generated-private-key` currently treats that as a match.
  Evidence: `npm test` failed with `FAIL in (generated-private-key-requires-a-normalized-valid-address-match-test) (hyperopen/api_wallets/application/form_policy_test.cljs:105:7)`, where expected `(nil? (form-policy/generated-private-key {:address "not-a-wallet", :private-key "0xpriv"} "also-not-a-wallet"))` but actual was `"0xpriv"`. The full run reported `Ran 3298 tests containing 18013 assertions. 1 failures, 0 errors.`

- Observation: The UI-state coverage batch compiled and ran under the same `npm test` invocation without producing an additional failure.
  Evidence: The only failing assertion in the run was the generated-key invalid-address assertion in `form_policy_test.cljs`.

- Observation: The production fix resolves the generated-key invalid-address match without changing valid normalized matching.
  Evidence: After the source edit, `npm test` reported `Ran 3298 tests containing 18013 assertions. 0 failures, 0 errors.`

- Observation: The required gates for a production source change passed after the fix.
  Evidence: `npm run check` exited `0`; `npm test` exited `0` with `Ran 3298 tests containing 18013 assertions. 0 failures, 0 errors.`; `npm run test:websocket` exited `0` with `Ran 449 tests containing 2701 assertions. 0 failures, 0 errors.`

- Observation: This worktree initially had no installed `node_modules`, so the first direct ClojureScript test run failed at runtime loading `lucide/dist/esm/icons/external-link.js`.
  Evidence: `npm list lucide --depth=0` reported `(empty)`. Running `npm ci` installed `333` packages and allowed the normal repo test commands to execute. `npm ci` also reported `11` audit findings, which were not part of this coverage task.

- Observation: `agent-session/normalize-agent-valid-days` clamps values above `agent-session/max-agent-valid-days`; `form-errors` rejects invalid or non-positive day values but does not reject above-max positive values.
  Evidence: `src/hyperopen/wallet/agent_session.cljs` applies `(min max-agent-valid-days)` after parsing a positive value, and existing websocket agent-session coverage expects `999` to normalize to `max-agent-valid-days`.

- Observation: Targeted mutation testing fully covered and killed both small application modules.
  Evidence: `bb tools/mutate.clj --module src/hyperopen/api_wallets/application/form_policy.cljs --suite test --mutate-all` wrote `target/mutation/reports/2026-04-19T21-36-19.503581Z-src-hyperopen-api_wallets-application-form_policy.cljs.edn` with `8/8` killed and `0` uncovered. `bb tools/mutate.clj --module src/hyperopen/api_wallets/application/ui_state.cljs --suite test --mutate-all` wrote `target/mutation/reports/2026-04-19T21-41-46.717336Z-src-hyperopen-api_wallets-application-ui_state.cljs.edn` with `11/11` killed and `0` uncovered.

## Decision Log

- Decision: Keep this pass scoped to the two existing test namespaces and do not add new production APIs.
  Rationale: The `bd` issue and user request are specifically about increasing coverage for `form_policy.cljs` and `ui_state.cljs`; the source behavior already matches the observed contract from inspection.
  Date/Author: 2026-04-19 / spec_writer

- Decision: Use `npm run coverage` before targeted mutation testing.
  Rationale: `bb tools/mutate.clj` reads `coverage/lcov.info` for coverage-aware mutation selection and currently reports coverage-aware counts as unavailable without that file.
  Date/Author: 2026-04-19 / spec_writer

- Decision: Treat module-scoped mutation success as a required acceptance signal, not just a diagnostic.
  Rationale: The screenshot-driven request is about coverage quality, and these two modules have only `8` and `11` mutation sites, so requiring zero survivors and zero uncovered sites is a reasonable, observable bar.
  Date/Author: 2026-04-19 / spec_writer

- Decision: Do not require Browser MCP, Playwright, or browser QA for this plan.
  Rationale: The touched code is pure ClojureScript policy/state code under `src/hyperopen/api_wallets/application/**`, and acceptance is observable through deterministic ClojureScript tests, coverage, and mutation reports rather than a browser flow.
  Date/Author: 2026-04-19 / spec_writer

- Decision: Create only the active ExecPlan in this spec-writer pass and do not emit a separate `spec.json` artifact.
  Rationale: The user explicitly requested a repo-local active ExecPlan only.
  Date/Author: 2026-04-19 / spec_writer

- Decision: Fix `form-policy/generated-private-key` by binding both normalized addresses and requiring both values to be present before equality comparison.
  Rationale: `normalize-wallet-address` returns `nil` for invalid wallet addresses, and the previous direct equality check treated two invalid addresses as a match via `(= nil nil)`. Requiring both normalized addresses preserves valid mixed-case matching and mismatch rejection while preventing invalid generated/form address pairs from returning a private key.
  Date/Author: 2026-04-19 / worker

- Decision: Keep form-policy day validation tests focused on blank, invalid, and non-positive values, not above-max positive values.
  Rationale: The shared wallet session normalizer clamps above-max positive values to `agent-session/max-agent-valid-days`; changing `form-errors` to reject those values would alter existing session policy and exceed this coverage task.
  Date/Author: 2026-04-19 / Codex

## Outcomes & Retrospective

This coverage pass is complete. RED-phase test materialization exposed a production defect in `form-policy/generated-private-key`; worker implemented the minimal production fix in `src/hyperopen/api_wallets/application/form_policy.cljs` and the final tree passed coverage, targeted mutation, and required repository gates. `npm run coverage` exited `0` and wrote `coverage/lcov.info`. Targeted mutation killed `8/8` mutants for `form_policy.cljs` and `11/11` mutants for `ui_state.cljs`, with `0` uncovered mutation sites in both modules. Fresh final gates passed: `npm run check`, `npm test`, and `npm run test:websocket` all exited `0`.

The result slightly reduces overall risk without adding material complexity. The only source change adds explicit normalized-address presence checks before returning a generated private key; the rest of the work strengthens existing tests around pure form and UI-state policy. The main residual risk is environmental rather than code-related: `npm ci` reported `11` audit findings in the existing dependency tree, but dependency remediation is outside this task.

## Context and Orientation

The repository root for this worktree is `/Users/barry/.codex/worktrees/6272/hyperopen`. Run all commands in this plan from that directory. `bd` is Hyperopen's local issue tracker, and `hyperopen-l0sb` is the live issue for this coverage task.

The first target production file is `src/hyperopen/api_wallets/application/form_policy.cljs`. Its namespace is `hyperopen.api-wallets.application.form-policy`. It exports pure form policy helpers:

- `form-errors [form]` returns a map with `:name`, `:address`, and `:days-valid` error strings or `nil` values.
- `form-valid? [form]` returns true when every value from `form-errors` is `nil`.
- `first-form-error [form]` returns the first non-nil error string from `form-errors`.
- `generated-private-key [generated-state form-address]` returns the generated private key only when the generated address and current form address normalize to the same wallet address.
- `valid-until-preview-ms [server-time-ms days-valid]` returns an expiry timestamp in milliseconds only when the server time is numeric and the day count normalizes through the wallet agent-session policy.

The observed `form-policy/form-errors` behavior to preserve is: merge the input with `ui-state/default-form`, tolerate `nil` form input, trim the name before requiring it, normalize the wallet address through `hyperopen.wallet.agent-session/normalize-wallet-address`, allow blank `:days-valid`, reject invalid or non-positive `:days-valid`, and compose the max-days error string from `wallet.agent-session/max-agent-valid-days`. Above-max positive day values are clamped by the shared wallet session normalizer rather than rejected.

The observed `form-policy/generated-private-key` behavior to preserve is: return `(:private-key generated-state)` only when the normalized `(:address generated-state)` equals the normalized form address. Case differences and other accepted address normalization should not prevent a match; mismatches and invalid addresses should return `nil`.

The observed `form-policy/valid-until-preview-ms` behavior to preserve is: return `nil` unless `days-valid` normalizes and `server-time-ms` is numeric. When both are valid, add `normalized-days * 24 * 60 * 60 * 1000` to `server-time-ms`.

The second target production file is `src/hyperopen/api_wallets/application/ui_state.cljs`. Its namespace is `hyperopen.api-wallets.application.ui-state`. It exports default UI state constructors and normalization helpers:

- `default-sort-state []` returns `{:column :name :direction :asc}`.
- `default-form []` returns `{:name "" :address "" :days-valid ""}`.
- `default-modal-state []` returns closed modal state.
- `default-generated-state []` returns generated-key state with nil address and private key.
- `normalize-sort-column [value]` canonicalizes accepted sort columns.
- `normalize-sort-direction [value]` canonicalizes sort direction.
- `normalize-sort-state [sort-state]` canonicalizes both pieces of a sort-state map and tolerates `nil`.
- `next-sort-state [current-sort column]` returns the next sort state after a user selects a column.
- `normalize-form-field [value]` canonicalizes accepted form fields.
- `normalize-form-value [field value]` canonicalizes a form input value based on the field.

The observed `ui-state` behavior to preserve is: `normalize-sort-column` accepts keywords and normalized string aliases for `:name`, `:address`, and `:valid-until`; invalid and non-string/non-keyword values fall back to `:name`. Address aliases include strings such as `wallet`, `wallet-address`, and `api-wallet-address`. Valid-until aliases include strings such as `validUntil` and `valid-until-ms` after the function trims, lower-cases, and replaces non-alphanumeric runs with hyphens.

The observed sort-direction behavior to preserve is: `normalize-sort-direction` accepts `:asc` and `:desc` keywords, accepts strings after trim and lower-case, and falls back to `:asc` for invalid, nil, or unsupported values. `normalize-sort-state` should tolerate `nil` by returning the default sort state.

The observed next-sort behavior to preserve is: `next-sort-state` toggles direction when the selected column matches the current normalized column; when changing columns, it defaults `:valid-until` to `:desc` and other new columns to `:asc`.

The observed form-field behavior to preserve is: `normalize-form-field` accepts keyword and normalized string names only for `:name`, `:address`, and `:days-valid`; unsupported fields return `nil`. `normalize-form-value` preserves name strings exactly, trims and lower-cases addresses, strips non-digits from `:days-valid`, treats nil values as the empty string, and stringifies unknown field values.

The existing test files are `test/hyperopen/api_wallets/application/form_policy_test.cljs` and `test/hyperopen/api_wallets/application/ui_state_test.cljs`. They already cover default state, one valid form, some invalid form errors, generated-key mismatch, basic preview nil behavior, basic sort aliasing, and a few form normalization cases. The missing coverage is the set of edge and alias behaviors listed above.

Mutation testing means temporarily changing source expressions in a controlled way and checking whether tests fail. A killed mutant means the tests detected the injected behavior change. A survivor means the test suite still passed and coverage should be strengthened. LCOV is the coverage file format produced by `npm run coverage`; this repo writes the needed file at `coverage/lcov.info`.

## Plan of Work

Begin with a clean orientation pass. Run `bd show hyperopen-l0sb --json` to confirm the live issue, and run `git status --short` to see unrelated local changes before editing. Do not edit anything under `src/hyperopen/**` during the expected test-only implementation.

Add the first focused RED batch in `test/hyperopen/api_wallets/application/form_policy_test.cljs`. It should be a new `deftest` or a small set of tightly related assertions that cover the missing form-policy contract. Require `hyperopen.wallet.agent-session` as `agent-session` if the test needs to compose the expected max-days error string without hardcoding the limit. The batch should prove that `form-errors` merges `ui-state/default-form` for nil or partial forms, trims a whitespace-padded name, normalizes a mixed-case wallet address, allows blank or whitespace-only `:days-valid`, rejects invalid days such as letters and zero, and reports the max-days message by using the current `agent-session/max-agent-valid-days` value. The same batch should also prove normalized address matching for `generated-private-key`, mismatch rejection, invalid-address non-match behavior, and `valid-until-preview-ms` returning nil for invalid days or non-numeric server time while returning `server-time-ms + days * 86,400,000` for valid input.

Run `npm test` immediately after adding the form-policy batch. The desired RED signal is that the newly added test batch executes and fails only because an expectation or test fixture still needs correction, or because a real defect has been found. If the new batch passes immediately, record that as a coverage-only discovery in this plan and continue; do not invent a production change just to force a RED failure. If there is a failure caused by incorrect test data, fix only the test data or expected value and rerun `npm test`. If there is a real production defect, record the failing assertion and exact command in `Surprises & Discoveries`, then make the smallest source change needed and explain it in `Decision Log`.

Add the second focused RED batch in `test/hyperopen/api_wallets/application/ui_state_test.cljs`. It should cover the sort and form normalization contract directly. Include representative assertions for accepted sort columns from keywords and strings, including `:name`, `:address`, `:valid-until`, `wallet`, `wallet address`, `api_wallet_address`, `validUntil`, and `valid-until-ms`. Include invalid sort-column inputs such as `nil`, a number, and an unsupported string, all falling back to `:name`. Cover sort directions from `:asc`, `:desc`, uppercase or whitespace-padded strings, invalid strings, nil, and non-string/non-keyword values. Cover `normalize-sort-state nil` returning the default state. Cover `next-sort-state` toggling when the selected column matches the current normalized column, defaulting a new `:valid-until` column to `:desc`, and defaulting other new columns to `:asc`.

In the same UI-state test batch, cover `normalize-form-field` for accepted keywords and normalized strings for `:name`, `:address`, and `:days-valid`, plus unsupported inputs returning `nil`. Cover `normalize-form-value` preserving name strings exactly, trimming and lower-casing addresses, stripping all non-digits from days-valid input, returning empty strings for nil values, and stringifying unknown field values.

Run `npm test` immediately after adding the UI-state batch. Apply the same RED/GREEN rule: failures should either be corrected in the test data or recorded as real source defects before a minimal source change. Once both focused batches are stable, run the full validation sequence.

No performance optimization is planned. There is no performance-motivated algorithm or data-structure change here, so no baseline profiling is required. The only measurement work is coverage and mutation testing, which verifies test adequacy rather than runtime performance.

## Concrete Steps

Use this exact working directory:

    cd /Users/barry/.codex/worktrees/6272/hyperopen

Confirm issue and local state:

    bd show hyperopen-l0sb --json
    git status --short

Add the form-policy test batch in `test/hyperopen/api_wallets/application/form_policy_test.cljs`. Then run:

    npm test

Expected RED/GREEN interpretation:

    A useful RED run fails in the newly added form-policy test name only.
    A coverage-only immediate GREEN run is acceptable if the new assertions already match current behavior.
    Any unrelated failure must be recorded before continuing.

Correct test data or expectations if needed, then rerun:

    npm test

Add the UI-state test batch in `test/hyperopen/api_wallets/application/ui_state_test.cljs`. Then run:

    npm test

Expected RED/GREEN interpretation:

    A useful RED run fails in the newly added UI-state test name only.
    A coverage-only immediate GREEN run is acceptable if the new assertions already match current behavior.
    Any unrelated failure must be recorded before continuing.

Once both targeted batches are stable, run the full main test suite:

    npm test

Regenerate coverage before mutation testing:

    npm run coverage

Run targeted mutation testing for both modules, using the exact module paths from the `bd` issue:

    bb tools/mutate.clj --module src/hyperopen/api_wallets/application/form_policy.cljs --suite test --mutate-all
    bb tools/mutate.clj --module src/hyperopen/api_wallets/application/ui_state.cljs --suite test --mutate-all

Run required gates after code changes:

    npm run check
    npm test
    npm run test:websocket

After validation, update this plan with the command outcomes and the mutation report paths under `target/mutation/reports/`.

## Validation and Acceptance

Acceptance is observable through commands and test output, not through visual inspection.

The main ClojureScript suite must pass. Run `npm test` from `/Users/barry/.codex/worktrees/6272/hyperopen` and expect exit code `0` with a final ClojureScript test summary that reports zero failures and zero errors. The new test names in `test/hyperopen/api_wallets/application/form_policy_test.cljs` and `test/hyperopen/api_wallets/application/ui_state_test.cljs` must be included in the run because `tools/generate-test-runner.mjs` discovers all `test/hyperopen/**/*_test.cljs` files.

The form-policy acceptance criteria are concrete behaviors. Tests must demonstrate that `form-policy/form-errors` returns the name and address required messages for nil or blank defaults, returns nil errors for a trimmed nonblank name and normalized valid address, allows blank days-valid, rejects invalid and non-positive days-valid values with a message containing the current `agent-session/max-agent-valid-days`, and still makes `form-valid?` and `first-form-error` agree with the returned error map. Tests must demonstrate that `generated-private-key` returns the private key only for normalized valid address matches and returns nil for invalid-address pairs. Tests must demonstrate that `valid-until-preview-ms` returns nil for invalid days or nonnumeric server time and returns the exact millisecond sum for valid inputs.

The UI-state acceptance criteria are concrete behaviors. Tests must demonstrate that `normalize-sort-column` accepts the canonical keywords and representative normalized string aliases for name, address, and valid-until; invalid and non-string/non-keyword values must fall back to `:name`. Tests must demonstrate that `normalize-sort-direction` accepts `:asc`, `:desc`, and trimmed/lower-cased string forms while falling back to `:asc` otherwise. Tests must demonstrate that `normalize-sort-state nil` returns `{:column :name :direction :asc}`. Tests must demonstrate that `next-sort-state` toggles matching columns and applies the documented new-column defaults. Tests must demonstrate that `normalize-form-field` accepts only `:name`, `:address`, and `:days-valid`, and that `normalize-form-value` preserves, normalizes, strips, or stringifies values according to the field.

Coverage must be refreshed before mutation. Run `npm run coverage` and expect exit code `0`; the observable artifact is `coverage/lcov.info`.

Targeted mutation acceptance is complete for these small modules. `bb tools/mutate.clj --module src/hyperopen/api_wallets/application/form_policy.cljs --suite test --mutate-all` reported `Total mutation sites: 8`, `Covered mutation sites: 8`, `Uncovered mutation sites: 0`, and `8/8 mutants killed (100.0%)`. `bb tools/mutate.clj --module src/hyperopen/api_wallets/application/ui_state.cljs --suite test --mutate-all` reported `Total mutation sites: 11`, `Covered mutation sites: 11`, `Uncovered mutation sites: 0`, and `11/11 mutants killed (100.0%)`.

The required repository gates passed after code changes and mutation testing. `npm run check`, `npm test`, and `npm run test:websocket` were run from the repository root and exited `0`. The final `npm test` summary was `Ran 3298 tests containing 18013 assertions. 0 failures, 0 errors.` The final `npm run test:websocket` summary was `Ran 449 tests containing 2701 assertions. 0 failures, 0 errors.`

The touched tracked files are `docs/exec-plans/active/2026-04-19-api-wallet-application-coverage.md`, `src/hyperopen/api_wallets/application/form_policy.cljs`, `test/hyperopen/api_wallets/application/form_policy_test.cljs`, and `test/hyperopen/api_wallets/application/ui_state_test.cljs`. The source edit is justified by the RED production defect recorded above.

## Idempotence and Recovery

The planned test edits are additive and safe to repeat. If a test run fails, rerun the smallest relevant command after correcting the test data or recorded defect. `npm run coverage` removes and recreates `.coverage` and `coverage`, so it is safe to rerun when coverage output is stale.

The mutation tool temporarily mutates the target source file and normally restores it after each mutant. If mutation testing is interrupted, inspect the two source files with `git diff -- src/hyperopen/api_wallets/application/form_policy.cljs src/hyperopen/api_wallets/application/ui_state.cljs` before continuing. If either source file contains mutation residue and there are no intentional source edits recorded in this plan, restore the original expression manually with a small patch rather than reverting unrelated user work.

Do not run `git pull --rebase`, `git push`, or remote sync commands unless the user explicitly asks for remote sync in the current session.

## Artifacts and Notes

Live issue evidence from `bd show hyperopen-l0sb --json`:

    id: hyperopen-l0sb
    title: Increase API-wallet application test coverage
    status: in_progress
    priority: 2
    issue_type: task
    description: Add focused tests for src/hyperopen/api_wallets/application/form_policy.cljs and src/hyperopen/api_wallets/application/ui_state.cljs, then run coverage and mutation testing for the strengthened target modules.

Mutation scan evidence without coverage:

    form_policy.cljs:
      module: src/hyperopen/api_wallets/application/form_policy.cljs
      total-sites: 8
      coverage-available?: false

    ui_state.cljs:
      module: src/hyperopen/api_wallets/application/ui_state.cljs
      total-sites: 11
      coverage-available?: false

Final coverage and mutation evidence:

    npm run coverage
      Statements: 90.62%
      Branches: 69.45%
      Functions: 83.58%
      Lines: 90.62%

    target/mutation/reports/2026-04-19T21-36-19.503581Z-src-hyperopen-api_wallets-application-form_policy.cljs.edn
      Total mutation sites: 8
      Covered mutation sites: 8
      Uncovered mutation sites: 0
      Summary: 8/8 mutants killed (100.0%)

    target/mutation/reports/2026-04-19T21-41-46.717336Z-src-hyperopen-api_wallets-application-ui_state.cljs.edn
      Total mutation sites: 11
      Covered mutation sites: 11
      Uncovered mutation sites: 0
      Summary: 11/11 mutants killed (100.0%)

Current file sizes from inspection:

    44  src/hyperopen/api_wallets/application/form_policy.cljs
    121 src/hyperopen/api_wallets/application/ui_state.cljs
    52  test/hyperopen/api_wallets/application/form_policy_test.cljs
    56  test/hyperopen/api_wallets/application/ui_state_test.cljs

## Interfaces and Dependencies

No new runtime dependency is planned. Keep using `cljs.test` in the existing ClojureScript test files. If the form-policy test needs the max-days value, add a test-only require of `hyperopen.wallet.agent-session` as `agent-session` and build the expected string from `agent-session/max-agent-valid-days`.

The public production interfaces that must remain compatible are:

- `hyperopen.api-wallets.application.form-policy/form-errors`
- `hyperopen.api-wallets.application.form-policy/form-valid?`
- `hyperopen.api-wallets.application.form-policy/first-form-error`
- `hyperopen.api-wallets.application.form-policy/generated-private-key`
- `hyperopen.api-wallets.application.form-policy/valid-until-preview-ms`
- `hyperopen.api-wallets.application.ui-state/default-sort-state`
- `hyperopen.api-wallets.application.ui-state/default-form`
- `hyperopen.api-wallets.application.ui-state/default-modal-state`
- `hyperopen.api-wallets.application.ui-state/default-generated-state`
- `hyperopen.api-wallets.application.ui-state/normalize-sort-column`
- `hyperopen.api-wallets.application.ui-state/normalize-sort-direction`
- `hyperopen.api-wallets.application.ui-state/normalize-sort-state`
- `hyperopen.api-wallets.application.ui-state/next-sort-state`
- `hyperopen.api-wallets.application.ui-state/normalize-form-field`
- `hyperopen.api-wallets.application.ui-state/normalize-form-value`

The validation tooling dependencies are already present in this repository:

- `npm test` runs `npm run test:runner:generate`, compiles the `test` Shadow CLJS build, and runs `node out/test.js`.
- `npm run coverage` generates `coverage/lcov.info`, which mutation testing needs.
- `bb tools/mutate.clj --module <module> --suite test --mutate-all` runs module-scoped mutation testing against the main ClojureScript test suite.
- `npm run check`, `npm test`, and `npm run test:websocket` are the required gates after code changes.

Plan update note (2026-04-19 21:16Z): Created the active ExecPlan for `hyperopen-l0sb` from the user-provided scope, live `bd` issue data, inspected source/test behavior, and current mutation scan artifacts. Work remains active, so the `Progress` section intentionally retains unchecked items.
